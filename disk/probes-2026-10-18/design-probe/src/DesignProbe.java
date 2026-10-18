import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;

/**
 * Scratch probe (read-only). Two questions for the under-2 GB design:
 * (1) Under the 372 real fitted later plays (fit.ckpt), what share of a strategy's scoring parts
 *     depart from the chart (walk.partDeparts)? Only those need a Hit value of their own in a
 *     last round (FullIndexFit.record: non-departing parts take st.move's stored V(Hit)).
 * (2) Per up-card: what share of PlayerTree's build CPU, and how many cells N needs (union of
 *     all strategies), so a partial store of the costliest rows can be sized.
 *
 * args: stateFile fitCkpt offsets(comma) perChunk
 */
public class DesignProbe {
    static final ThreadMXBean BEAN = ManagementFactory.getThreadMXBean();
    static long cpu() { return BEAN.getCurrentThreadCpuTime(); }
    static final int HN = PlayerTree.Hands.ALL.size;
    static final int OUT = ExactRound.DealerOutcomes.OUTCOMES;

    public static void main(String[] a) throws Exception {
        Path data = Paths.get(a[0]);
        String[] offs = a[2].split(",");
        int per = Integer.parseInt(a[3]);

        List<CountKeys.Count> counts = new ArrayList<>();
        counts.add(CountKeys.nullCount());
        counts.addAll(CountKeys.catalog());

        List<TreeProbe.Strat> strats = new ArrayList<>();
        List<Boolean> isHalf = new ArrayList<>();
        try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(Paths.get(a[1])), 1 << 20))) {
            in.readUTF();
            Object[] x = (Object[]) in.readObject();
            @SuppressWarnings("unchecked") List<FullIndexFit.State> states = (List<FullIndexFit.State>) x[1];
            int shown = 0;
            for (FullIndexFit.State s : states) {
                TreeProbe.Strat t = new TreeProbe.Strat();
                t.name = s.name;
                for (CountKeys.Count c : counts) {
                    for (int p = 0; p < 3; p++) {
                        if (s.name.startsWith(c.name + " " + SystemScorer.PENETRATIONS[p] + " fold")) {
                            if (t.count != null && t.count.name.length() >= c.name.length()) continue;
                            t.count = c; t.pen = p;
                        }
                    }
                }
                if (t.count == null) throw new IllegalStateException("no count for " + s.name);
                t.later = s.later;
                t.stands = s.now == null ? null : s.now.stands;
                strats.add(t);
                isHalf.add(s.name.toLowerCase().contains("half"));
                if (shown++ < 4) System.out.println("strategy name: " + s.name);
            }
        }
        int halves = 0;
        for (boolean h : isHalf) if (h) halves++;
        System.out.printf("strategies %d (%d named half)%n", strats.size(), halves);

        List<StateStore.Record> sample = new ArrayList<>();
        for (String o : offs) sample.addAll(StateStore.read(data, Integer.parseInt(o), per));
        ExactRound engine = new ExactRound(StateRun.montreal());
        for (int i = 0; i < Math.min(3, sample.size()); i++) new PlayerTree(engine, sample.get(i).ranksLeft());

        long allParts = 0, liveParts = 0, depParts = 0;           // over (strategy, state) pairs
        long[] livePen = new long[3], depPen = new long[3];
        long liveHalf = 0, depHalf = 0, liveFold = 0, depFold = 0;
        Map<String, long[]> perCount = new LinkedHashMap<>();
        double[] upCpu = new double[11];
        long[] upN = new long[11], upFeas = new long[11];
        double fullCpu = 0;
        long pairs = 0;
        for (int si = 0; si < sample.size(); si++) {
            StateStore.Record r = sample.get(si);
            int[] shoe = r.ranksLeft();
            long c0 = cpu();
            PlayerTree tree = new PlayerTree(engine, shoe);
            fullCpu += (cpu() - c0) / 1e9;
            perUp(engine, shoe, upCpu);
            for (int u = 1; u <= 10; u++) {
                double[] row = tree.standRow(u);
                for (int h = 0; h < HN; h++) if (!Double.isNaN(row[h])) upFeas[u]++;
            }
            boolean[][] Nall = new boolean[11][HN];
            for (CountKeys.Count c : counts) {
                PlayerTree.Counted ct = tree.count(c, r.dealt);
                TreeProbe.need(ct, PlayerTree.CHART, null, Nall);
                for (int k = 0; k < strats.size(); k++) {
                    TreeProbe.Strat st = strats.get(k);
                    if (st.count != c || r.depth >= SystemScorer.CUTS[st.pen]) continue;
                    PlayerTree.Walk w = ct.walk(st.play(), PlayerTree.Mode.CONTESTED);
                    TreeProbe.need(ct, st.play(), null, Nall);
                    long all = ct.partFrom[Deals.COUNT] - ct.partFrom[0];
                    long live = 0, dep = 0;
                    for (int i = 0; i < Deals.COUNT; i++) {
                        if (ct.chance[i] == 0 || Deals.playerNatural(i)) continue;
                        for (int q = ct.partFrom[i]; q < ct.partFrom[i + 1]; q++) {
                            live++;
                            if (w.partDeparts[q]) dep++;
                        }
                    }
                    pairs++;
                    allParts += all; liveParts += live; depParts += dep;
                    livePen[st.pen] += live; depPen[st.pen] += dep;
                    if (isHalf.get(k)) { liveHalf += live; depHalf += dep; } else { liveFold += live; depFold += dep; }
                    long[] pc = perCount.computeIfAbsent(c.name, z -> new long[3]);
                    pc[0] += all; pc[1] += live; pc[2] += dep;
                }
            }
            for (int u = 1; u <= 10; u++) for (int h = 0; h < HN; h++) if (Nall[u][h]) upN[u]++;
            if (si % 10 == 9) System.out.printf("  %d states done%n", si + 1);
        }
        int n = sample.size();
        System.out.printf("%n%d states (offsets %s, %d each); depths:", n, a[2], per);
        for (StateStore.Record r : sample) System.out.print(" " + r.depth);
        System.out.println();
        System.out.printf("(strategy, state) pairs walked: %d%n", pairs);
        System.out.printf("parts: all %d, live (chance > 0, not a natural) %d (%.1f%% of all), departing %d = %.1f%% of live, %.1f%% of all%n",
                allParts, liveParts, 100.0 * liveParts / allParts, depParts, 100.0 * depParts / liveParts, 100.0 * depParts / allParts);
        for (int p = 0; p < 3; p++) System.out.printf("  at %s: departing %.1f%% of live%n", SystemScorer.PENETRATIONS[p], 100.0 * depPen[p] / Math.max(1, livePen[p]));
        System.out.printf("  fold fits %.1f%%, half fits %.1f%% of live%n", 100.0 * depFold / Math.max(1, liveFold), 100.0 * depHalf / Math.max(1, liveHalf));
        double lo = 1, hi = 0; String loN = "", hiN = "";
        for (Map.Entry<String, long[]> e : perCount.entrySet()) {
            double f = e.getValue()[2] / (double) e.getValue()[0];
            if (f < lo) { lo = f; loN = e.getKey(); }
            if (f > hi) { hi = f; hiN = e.getKey(); }
        }
        System.out.printf("  per count, departing share of all parts: lowest %.1f%% (%s), highest %.1f%% (%s)%n", 100 * lo, loN, 100 * hi, hiN);
        System.out.printf("%nPlayerTree build %.4f s CPU a state; replicated per up-card (setup + batched distributions + stood):%n", fullCpu / n);
        double tot = 0; long totN = 0, totF = 0;
        for (int u = 1; u <= 10; u++) { tot += upCpu[u]; totN += upN[u]; totF += upFeas[u]; }
        for (int u = 1; u <= 10; u++) {
            System.out.printf("  up %2d: %.1f%% of build CPU; feasible cells %.0f a state; N (all 372 + chart) %.0f a state = %.1f%% of N%n",
                    u, 100 * upCpu[u] / tot, upFeas[u] / (double) n, upN[u] / (double) n, 100.0 * upN[u] / totN);
        }
        System.out.printf("  total replicated %.4f s; N %.0f of feasible %.0f a state (%.1f%%)%n", tot / n, totN / (double) n, totF / (double) n, 100.0 * totN / totF);
    }

    /** PlayerTree's constructor per up-card, timed: the same calls TreeProbe.build makes, every cell. */
    static void perUp(ExactRound engine, int[] shoe, double[] upCpu) {
        PlayerTree.Hands H = PlayerTree.Hands.ALL;
        int cards = 0;
        for (int r = 1; r <= 10; r++) cards += shoe[r];
        double[] outcomes = new double[OUT];
        double[][] many = new double[OUT][HN];
        int[][] counts = new int[11][HN];
        int[] sizes = new int[HN];
        int[] which = new int[HN];
        int[] left = new int[11];
        for (int u = 1; u <= 10; u++) {
            if (shoe[u] == 0) continue;
            long c0 = cpu();
            double[] s = new double[HN];
            ExactRound.DealerOutcomes dealer = engine.dealerOutcomes(shoe, u);
            int together = 0;
            for (int h = 0; h < HN; h++) {
                boolean fits = true;
                for (int r = 1; r <= 10; r++) {
                    left[r] = shoe[r] - H.held[h * 11 + r] - (r == u ? 1 : 0);
                    fits &= left[r] >= 0;
                }
                if (!fits) continue;
                int after = cards - H.cards[h] - 1;
                if (after > dealer.mostCardsToRunOutOf()) {
                    for (int r = 1; r <= 10; r++) counts[r][together] = left[r];
                    sizes[together] = after;
                    which[together] = h;
                    together++;
                    continue;
                }
                dealer.outcomes(left, outcomes);
                s[h] = ExactRound.DealerOutcomes.stood(H.total(h), outcomes);
            }
            if (together > 0) dealer.outcomes(counts, sizes, together, many);
            for (int k = 0; k < together; k++) s[which[k]] = ExactRound.DealerOutcomes.stood(H.total(which[k]), many, k);
            upCpu[u] += (cpu() - c0) / 1e9;
        }
    }
}
