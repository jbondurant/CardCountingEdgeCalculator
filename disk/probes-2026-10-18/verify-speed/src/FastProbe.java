import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/**
 * Scratch probe (read-only): a regrouped build of a PlayerTree's stand values that keeps every
 * multiplication and every addition of ExactRound.DealerOutcomes.outcomes(counts, ...) per shoe,
 * in the same order, but works each entry's product once per distinct projection of the hand on
 * the entry's ranks instead of once per shoe. Compared bit for bit with PlayerTree, and timed.
 *
 * args: stateFile offsets(comma) perChunk
 */
public class FastProbe {
    static final ThreadMXBean BEAN = ManagementFactory.getThreadMXBean();
    static long cpu() { return BEAN.getCurrentThreadCpuTime(); }
    static final PlayerTree.Hands HANDS = PlayerTree.Hands.ALL;
    static final int HN = HANDS.size;
    static final int OUT = ExactRound.DealerOutcomes.OUTCOMES;
    static final RoundRules RULES = StateRun.montreal();

    /** One up-card's dealer hands, regrouped: state-independent. */
    static final class Plan {
        int entries;
        int[] start; byte[] ranks; byte[] counts; int[] size; double[] orders; int[] outcome;
        int[] setOf;            // entry -> rank-set id
        short[][] idx;          // set id -> per hand, its tuple
        byte[][][] digits;      // set id -> tuple -> H[r] for the set's ranks, ascending
        int[] tuples;           // set id -> number of tuples
        int sets;
        long tupleWork;         // sum over entries of tuples x factors
        long shoeWork;          // sum over entries of factors (per shoe)
    }

    static Plan plan(ExactRound engine, int[] anyShoe, int u) throws Exception {
        ExactRound.DealerOutcomes d = engine.dealerOutcomes(anyShoe, u);
        Field f = ExactRound.DealerOutcomes.class.getDeclaredField("dealer");
        f.setAccessible(true);
        Object dh = f.get(d);
        Class<?> c = dh.getClass();
        Plan p = new Plan();
        p.entries = get(c, dh, "entries", Integer.class);
        p.start = get(c, dh, "start", int[].class);
        p.ranks = get(c, dh, "ranks", byte[].class);
        p.counts = get(c, dh, "counts", byte[].class);
        p.size = get(c, dh, "size", int[].class);
        p.orders = get(c, dh, "orders", double[].class);
        p.outcome = get(c, dh, "outcome", int[].class);
        Map<Integer, Integer> setId = new HashMap<>();
        List<Integer> masks = new ArrayList<>();
        p.setOf = new int[p.entries];
        for (int i = 0; i < p.entries; i++) {
            int m = 0;
            for (int j = p.start[i]; j < p.start[i + 1]; j++) m |= 1 << p.ranks[j];
            Integer id = setId.get(m);
            if (id == null) { id = masks.size(); setId.put(m, id); masks.add(m); }
            p.setOf[i] = id;
        }
        p.sets = masks.size();
        p.idx = new short[p.sets][HN];
        p.digits = new byte[p.sets][][];
        p.tuples = new int[p.sets];
        for (int s = 0; s < p.sets; s++) {
            int m = masks.get(s);
            Map<Long, Integer> tu = new HashMap<>();
            List<byte[]> list = new ArrayList<>();
            for (int h = 0; h < HN; h++) {
                long key = 0;
                byte[] dg = new byte[11];
                for (int r = 1; r <= 10; r++) {
                    if ((m & (1 << r)) != 0) {
                        int k = HANDS.held[h * 11 + r];
                        dg[r] = (byte) k;
                        key = key * 16 + k;
                    }
                }
                Integer t = tu.get(key);
                if (t == null) { t = list.size(); tu.put(key, t); list.add(dg); }
                p.idx[s][h] = (short) (int) t;
            }
            p.tuples[s] = list.size();
            p.digits[s] = list.toArray(new byte[0][]);
        }
        for (int i = 0; i < p.entries; i++) {
            int fct = p.start[i + 1] - p.start[i];
            p.tupleWork += (long) p.tuples[p.setOf[i]] * fct;
            p.shoeWork += fct;
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    static <T> T get(Class<?> c, Object o, String name, Class<T> type) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    static Field FALL, INV;

    /** The stand values of every feasible hand, regrouped. */
    static double[][] fast(ExactRound engine, Plan[] plans, int[] shoe, long[] t) throws Exception {
        int cards = 0;
        for (int r = 1; r <= 10; r++) cards += shoe[r];
        double[][] stand = new double[11][];
        int[] which = new int[HN];
        int[] sizes = new int[HN];
        short[] sub = new short[HN];
        double[][] many = new double[OUT][HN];
        double[] T = new double[4096];
        for (int u = 1; u <= 10; u++) {
            double[] s = new double[HN];
            Arrays.fill(s, Double.NaN);
            stand[u] = s;
            if (shoe[u] == 0) continue;
            long c0 = cpu();
            ExactRound.DealerOutcomes dealer = engine.dealerOutcomes(shoe, u);
            double[][][] fall = (double[][][]) FALL.get(dealer);
            double[][] inv = (double[][]) INV.get(dealer);
            Plan p = plans[u];
            int together = 0;
            for (int h = 0; h < HN; h++) {
                boolean fits = true;
                for (int r = 1; r <= 10; r++) fits &= shoe[r] - HANDS.held[h * 11 + r] - (r == u ? 1 : 0) >= 0;
                if (!fits) continue;
                int after = cards - HANDS.cards[h] - 1;
                if (after <= dealer.mostCardsToRunOutOf()) throw new IllegalStateException("small shoe");
                which[together] = h;
                sizes[together] = after;
                together++;
            }
            for (int o = 0; o < OUT; o++) Arrays.fill(many[o], 0, together, 0);
            long c1 = cpu();
            for (int i = 0; i < p.entries; i++) {
                int set = p.setOf[i];
                byte[][] dg = p.digits[set];
                int nt = p.tuples[set];
                int j0 = p.start[i], j1 = p.start[i + 1];
                double o0 = p.orders[i];
                for (int q = 0; q < nt; q++) {
                    double v = o0;
                    byte[] d = dg[q];
                    for (int j = j0; j < j1; j++) {
                        int r = p.ranks[j];
                        int cnt = shoe[r] - (r == u ? 1 : 0) - d[r];
                        if (cnt < 0) { v = Double.NaN; break; }   // no feasible shoe holds this tuple
                        v *= fall[r][cnt][p.counts[j]];
                    }
                    T[q] = v;
                }
                short[] ix = p.idx[set];
                int size = p.size[i];
                double[] into = many[p.outcome[i]];
                for (int k = 0; k < together; k++) {
                    double v = T[ix[which[k]]];
                    if (v != 0 && size <= sizes[k]) into[k] += v * inv[sizes[k]][size];
                }
            }
            long c2 = cpu();
            for (int k = 0; k < together; k++) s[which[k]] = ExactRound.DealerOutcomes.stood(HANDS.total(which[k]), many, k);
            long c3 = cpu();
            t[0] += c1 - c0; t[1] += c2 - c1; t[2] += c3 - c2;
        }
        return stand;
    }


    /** Variant: every hand slot (infeasible ones written and ignored), the inverse falling factorial taken per size. */
    static double[][] fast2(ExactRound engine, Plan[] plans, int[] shoe, boolean[][] want, long[] t) throws Exception {
        int cards = 0;
        for (int r = 1; r <= 10; r++) cards += shoe[r];
        double[][] stand = new double[11][];
        double[][] many = new double[OUT][HN];
        double[] T = new double[4096];
        int[] which = new int[HN];
        for (int u = 1; u <= 10; u++) {
            double[] s = new double[HN];
            Arrays.fill(s, Double.NaN);
            stand[u] = s;
            if (shoe[u] == 0) continue;
            long c0 = cpu();
            ExactRound.DealerOutcomes dealer = engine.dealerOutcomes(shoe, u);
            double[][][] fall = (double[][][]) FALL.get(dealer);
            double[][] inv = (double[][]) INV.get(dealer);
            Plan p = plans[u];
            int maxSize = 0;
            for (int i = 0; i < p.entries; i++) maxSize = Math.max(maxSize, p.size[i]);
            int n = 0;
            for (int h = 0; h < HN; h++) {
                boolean fits = want == null || want[u][h];
                for (int r = 1; r <= 10 && fits; r++) fits = shoe[r] - HANDS.held[h * 11 + r] - (r == u ? 1 : 0) >= 0;
                if (!fits) continue;
                if (cards - HANDS.cards[h] - 1 <= Math.max(dealer.mostCardsToRunOutOf(), maxSize)) throw new IllegalStateException("small shoe");
                which[n++] = h;
            }
            // the shoes in hand order, compacted: their tuple per rank set made once per state
            double[][] invS = new double[maxSize + 1][n];
            for (int k = 0; k < n; k++) {
                int after = cards - HANDS.cards[which[k]] - 1;
                for (int z = 0; z <= maxSize; z++) invS[z][k] = inv[after][z];
            }
            for (int o = 0; o < OUT; o++) Arrays.fill(many[o], 0, n, 0);
            short[][] ixc = new short[p.sets][];
            for (int set = 0; set < p.sets; set++) {
                short[] ix = p.idx[set];
                short[] c = new short[n];
                for (int k = 0; k < n; k++) c[k] = ix[which[k]];
                ixc[set] = c;
            }
            long c1 = cpu();
            for (int i = 0; i < p.entries; i++) {
                int set = p.setOf[i];
                byte[][] dg = p.digits[set];
                int nt = p.tuples[set];
                int j0 = p.start[i], j1 = p.start[i + 1];
                double o0 = p.orders[i];
                for (int q = 0; q < nt; q++) {
                    double v = o0;
                    byte[] d = dg[q];
                    for (int j = j0; j < j1; j++) {
                        int r = p.ranks[j];
                        int cnt = shoe[r] - (r == u ? 1 : 0) - d[r];
                        if (cnt < 0) { v = 0; break; }   // no shoe asked for holds this tuple
                        v *= fall[r][cnt][p.counts[j]];
                    }
                    T[q] = v;
                }
                short[] ix = ixc[set];
                double[] iv = invS[p.size[i]];
                double[] into = many[p.outcome[i]];
                for (int k = 0; k < n; k++) {
                    double v = T[ix[k]];
                    if (v != 0) into[k] += v * iv[k];
                }
            }
            long c2 = cpu();
            for (int k = 0; k < n; k++) s[which[k]] = ExactRound.DealerOutcomes.stood(HANDS.total(which[k]), many, k);
            long c3 = cpu();
            t[0] += c1 - c0; t[1] += c2 - c1; t[2] += c3 - c2;
        }
        return stand;
    }

    public static void main(String[] a) throws Exception {
        Path data = Paths.get(a[0]);
        String[] offs = a[1].split(",");
        int per = Integer.parseInt(a[2]);
        FALL = ExactRound.DealerOutcomes.class.getDeclaredField("fall"); FALL.setAccessible(true);
        INV = ExactRound.DealerOutcomes.class.getDeclaredField("inverseFall"); INV.setAccessible(true);
        List<StateStore.Record> sample = new ArrayList<>();
        for (String o : offs) sample.addAll(StateStore.read(data, Integer.parseInt(o), per));
        ExactRound engine = new ExactRound(RULES);
        Plan[] plans = new Plan[11];
        long tw = 0, sw = 0, ent = 0, sets = 0, maxT = 0;
        for (int u = 1; u <= 10; u++) {
            plans[u] = plan(engine, sample.get(0).ranksLeft(), u);
            tw += plans[u].tupleWork; sw += plans[u].shoeWork; ent += plans[u].entries; sets += plans[u].sets;
            for (int s = 0; s < plans[u].sets; s++) maxT = Math.max(maxT, plans[u].tuples[s]);
            System.out.printf("up %2d: %4d entries, %3d rank sets, factors a shoe %5d, tuple factors %8d (%.1f shoes' worth)%n",
                    u, plans[u].entries, plans[u].sets, plans[u].shoeWork, plans[u].tupleWork,
                    plans[u].tupleWork / (double) plans[u].shoeWork);
        }
        System.out.printf("all: %d entries, %d rank sets, most tuples of a set %d%n", ent, sets, maxT);
        // warm up both
        for (int i = 0; i < 4; i++) {
            new PlayerTree(engine, sample.get(i % sample.size()).ranksLeft());
            fast(engine, plans, sample.get(i % sample.size()).ranksLeft(), new long[3]);
        }
        double tTree = 0, tFast = 0; long[] parts = new long[3];
        long cells = 0, diff = 0;
        for (StateStore.Record r : sample) {
            int[] shoe = r.ranksLeft();
            long c0 = cpu();
            PlayerTree tree = new PlayerTree(engine, shoe);
            long c1 = cpu();
            long[] t = new long[3];
            double[][] f = fast(engine, plans, shoe, t);
            long c2 = cpu();
            tTree += (c1 - c0) / 1e9; tFast += (c2 - c1) / 1e9;
            for (int k = 0; k < 3; k++) parts[k] += t[k];
            for (int u = 1; u <= 10; u++) {
                double[] row = tree.standRow(u);
                for (int h = 0; h < HN; h++) {
                    cells++;
                    if (Double.doubleToRawLongBits(row[h]) != Double.doubleToRawLongBits(f[u][h])) diff++;
                }
            }
        }
        int n = sample.size();
        System.out.printf("%d states: PlayerTree %.4f s CPU a state; regrouped %.4f s (%.2fx faster): setup %.4f, entries %.4f, stood %.4f%n",
                n, tTree / n, tFast / n, tTree / tFast, parts[0] / 1e9 / n, parts[1] / 1e9 / n, parts[2] / 1e9 / n);
        System.out.printf("cells compared %d (every cell, NaN included), %d differ in any bit%n", cells, diff);
        // second pass in the other order, to see the timing is not an ordering artefact
        tTree = 0; tFast = 0;
        for (StateStore.Record r : sample) {
            int[] shoe = r.ranksLeft();
            long c0 = cpu();
            fast(engine, plans, shoe, new long[3]);
            long c1 = cpu();
            new PlayerTree(engine, shoe);
            long c2 = cpu();
            tFast += (c1 - c0) / 1e9; tTree += (c2 - c1) / 1e9;
        }
        double tF2 = 0; long[] p2 = new long[3]; long d2 = 0;
        for (StateStore.Record r : sample) {
            int[] shoe = r.ranksLeft();
            PlayerTree tree = new PlayerTree(engine, shoe);
            long c0 = cpu();
            double[][] f2 = fast2(engine, plans, shoe, null, p2);
            tF2 += (cpu() - c0) / 1e9;
            for (int u = 1; u <= 10; u++) {
                double[] row = tree.standRow(u);
                for (int h = 0; h < HN; h++) if (Double.doubleToRawLongBits(row[h]) != Double.doubleToRawLongBits(f2[u][h])) d2++;
            }
        }
        System.out.printf("variant 2 (compacted, per-size inverse): %.4f s a state (%.2fx PlayerTree's %.4f); setup %.4f, entries %.4f; %d cells differ%n",
                tF2 / n, tTree / tF2, tTree / n, p2[0] / 1e9 / n, p2[1] / 1e9 / n, d2);
        System.out.printf("again, regrouped first: PlayerTree %.4f s, regrouped %.4f s (%.2fx)%n", tTree / n, tFast / n, tTree / tFast);
    }
}
