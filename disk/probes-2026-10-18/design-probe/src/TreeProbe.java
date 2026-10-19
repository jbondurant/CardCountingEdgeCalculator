import java.io.*;
import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Scratch probe (read-only): where a PlayerTree's build time goes, which of its stand cells the
 * walks of a full-index run read, and whether a tree built on only those cells gives the same bits.
 *
 * args: stateFile fitCkptOrNone offsets(comma) perChunk shareChunk
 */
public class TreeProbe {
    static final ThreadMXBean BEAN = ManagementFactory.getThreadMXBean();
    static long cpu() { return BEAN.getCurrentThreadCpuTime(); }
    static final PlayerTree.Hands HANDS = PlayerTree.Hands.ALL;
    static final int HN = HANDS.size;
    static final double POISON = 1e250;
    static final int OUT = ExactRound.DealerOutcomes.OUTCOMES;
    static final RoundRules RULES = StateRun.montreal();
    static final int[] CHART_MOVE = ScoringStates.chartMoves(RULES);

    static final class Strat {
        String name; CountKeys.Count count; int pen; SystemScorer.Groups later; boolean[] stands;
        PlayerTree.LaterPlay play() {
            if (stands == null) return PlayerTree.CHART;
            final boolean[] s = stands; final SystemScorer.Groups lg = later;
            return (total, soft, up, key) -> {
                int c = FullIndexFit.cellOf(PlayerTree.spot(total, soft, up));
                return c < 0 ? PlayerTree.CHART.stands(total, soft, up, key) : s[lg.of(key) * FullIndexFit.CELLS + c];
            };
        }
    }

    public static void main(String[] a) throws Exception {
        Path data = Paths.get(a[0]);
        String fit = a[1];
        String[] offs = a[2].split(",");
        int per = Integer.parseInt(a[3]);
        int shareChunk = Integer.parseInt(a[4]);
        boolean poisonChecks = !(a.length > 5 && a[5].equals("nopoison"));
        FastProbe.FALL = ExactRound.DealerOutcomes.class.getDeclaredField("fall"); FastProbe.FALL.setAccessible(true);
        FastProbe.INV = ExactRound.DealerOutcomes.class.getDeclaredField("inverseFall"); FastProbe.INV.setAccessible(true);

        List<CountKeys.Count> counts = new ArrayList<>();
        counts.add(CountKeys.nullCount());
        counts.addAll(CountKeys.catalog());
        System.out.printf("counts: %d; hands per up-card %d, cells a tree %d%n", counts.size(), HN, 10 * HN);

        List<Strat> strats = new ArrayList<>();
        if (!fit.equals("none")) {
            long t0 = System.nanoTime();
            try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(Paths.get(fit)), 1 << 20))) {
                in.readUTF();
                Object[] x = (Object[]) in.readObject();
                @SuppressWarnings("unchecked") List<FullIndexFit.State> states = (List<FullIndexFit.State>) x[1];
                for (FullIndexFit.State s : states) {
                    Strat t = new Strat();
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
                }
            }
            int chartPlays = 0;
            for (Strat t : strats) if (t.stands == null) chartPlays++;
            System.out.printf("fit.ckpt: %d strategies read in %.1f s; %d play the chart after the first move%n",
                    strats.size(), (System.nanoTime() - t0) / 1e9, chartPlays);
        }

        // ---------------------------------------------------------------- sharing across states
        List<StateStore.Record> big = StateStore.read(data, 0, shareChunk);
        Map<Integer, List<StateStore.Record>> byShoe = new TreeMap<>();
        for (StateStore.Record r : big) byShoe.computeIfAbsent(r.shoe, k -> new ArrayList<>()).add(r);
        int multi = 0, inMulti = 0;
        int lastShoe = big.get(big.size() - 1).shoe;
        StringBuilder diffs = new StringBuilder();
        for (List<StateStore.Record> l : byShoe.values()) {
            if (l.size() > 1) {
                multi++; inMulti += l.size();
                for (int i = 1; i < l.size(); i++) {
                    diffs.append(" ").append(l.get(i).depth - l.get(i - 1).depth);
                }
            }
        }
        int minDepth = Integer.MAX_VALUE, maxDepth = 0;
        for (StateStore.Record r : big) { minDepth = Math.min(minDepth, r.depth); maxDepth = Math.max(maxDepth, r.depth); }
        System.out.printf("first %d records: shoes 0..%d, %d shoes hold them; %d shoes hold 2+ states (%d states); "
                        + "depth gaps between same-shoe states:%s; depths %d..%d%n",
                big.size(), lastShoe, byShoe.size(), multi, inMulti, diffs, minDepth, maxDepth);

        // ---------------------------------------------------------------- the sample
        List<StateStore.Record> sample = new ArrayList<>();
        for (String o : offs) sample.addAll(StateStore.read(data, Integer.parseInt(o), per));
        big = null;
        ExactRound engine = new ExactRound(RULES);
        FastProbe.Plan[] plans = new FastProbe.Plan[11];
        for (int u = 1; u <= 10; u++) plans[u] = FastProbe.plan(engine, sample.get(0).ranksLeft(), u);
        for (int i = 0; i < 4; i++) FastProbe.fast2(engine, plans, sample.get(i % sample.size()).ranksLeft(), null, new long[3]);
        // warm up the JIT on a few trees
        for (int i = 0; i < Math.min(4, sample.size()); i++) {
            new PlayerTree(engine, sample.get(i).ranksLeft());
            build(engine, sample.get(i).ranksLeft(), null, new long[6]);
        }

        dealerEntries(engine, sample.get(0).ranksLeft());

        double sFull = 0, sRep = 0; long[] parts = new long[6];
        double sR = 0, sNall = 0, sNcount = 0, sF2 = 0, sF2N = 0, sF2H = 0; long needCpu = 0, walkCpu = 0;
        long cFeas = 0, cR = 0, cE = 0, cNall = 0, cNallChart = 0, cNchartOnly = 0, cNcountSum = 0, cNstratSum = 0;
        long nCountUnions = 0, nStratWalks = 0;
        long cNcountMax = 0;
        long bitDiff = 0, cellsCompared = 0;
        long valFail = 0, valChecks = 0, nValFail = 0, nValChecks = 0;
        long rawBytes = 0, deflBytes = 0, xorBytes = 0;
        @SuppressWarnings("unchecked") Set<Long>[] keys = new Set[11];
        for (int u = 1; u <= 10; u++) keys[u] = new HashSet<>();
        long keyTotal = 0;
        Random rnd = new Random(20261018);
        Map<String, long[]> perCount = new LinkedHashMap<>();

        for (int si = 0; si < sample.size(); si++) {
            StateStore.Record r = sample.get(si);
            int[] shoe = r.ranksLeft();

            long c0 = cpu();
            PlayerTree tree = new PlayerTree(engine, shoe);
            long c1 = cpu();
            sFull += (c1 - c0) / 1e9;
            long[] t = new long[6];
            double[][] rep = build(engine, shoe, null, t);
            for (int k = 0; k < 6; k++) parts[k] += t[k];
            sRep += (t[0] + t[1] + t[2] + t[3]) / 1e9;
            boolean[][] feas = new boolean[11][HN];
            for (int u = 1; u <= 10; u++) {
                double[] row = tree.standRow(u);
                for (int h = 0; h < HN; h++) {
                    if (!Double.isNaN(row[h])) {
                        feas[u][h] = true; cFeas++;
                        long pk = pack(shoe, h, u);
                        keys[u].add(pk); keyTotal++;
                    }
                    if (Double.doubleToRawLongBits(row[h]) != Double.doubleToRawLongBits(rep[u][h])) bitDiff++;
                }
            }

            // R: what the eager contested walk reads, union over every count; E: the perfect walk
            boolean[][] R = new boolean[11][HN];
            Map<CountKeys.Count, PlayerTree.Counted> counted = new IdentityHashMap<>();
            for (CountKeys.Count c : counts) {
                PlayerTree.Counted ct = tree.count(c, r.dealt);
                counted.put(c, ct);
                markEager(ct, PlayerTree.Mode.CONTESTED, R);
            }
            boolean[][] E = new boolean[11][HN];
            markEager(counted.get(counts.get(0)), PlayerTree.Mode.EVERY_NODE, E);
            cR += count(R); cE += count(E);

            // N: what a top-down walk needs, per strategy, per count (with the chart, pass 0), and in all
            boolean[][] Nall = new boolean[11][HN];
            boolean[][] NallChart = new boolean[11][HN];
            boolean[][] NchartOnly = new boolean[11][HN];
            boolean[] chartHits = new boolean[Deals.COUNT];
            for (int i = 0; i < Deals.COUNT; i++) chartHits[i] = CHART_MOVE[i] >= 0 && StateValuer.MOVES[CHART_MOVE[i]] == PlayerMove.Hit;
            boolean[][] hiLoUnion = null;
            for (CountKeys.Count c : counts) {
                PlayerTree.Counted ct = counted.get(c);
                boolean[][] Nc = new boolean[11][HN];
                need(ct, PlayerTree.CHART, null, Nc);
                need(ct, PlayerTree.CHART, chartHits, NchartOnly);
                for (Strat st : strats) {
                    if (st.count != c || r.depth >= SystemScorer.CUTS[st.pen]) continue;
                    boolean[][] Ns = new boolean[11][HN];
                    long w0 = cpu();
                    need(ct, st.play(), null, Ns);
                    long w1 = cpu();
                    PlayerTree.Walk ww = ct.walk(st.play(), PlayerTree.Mode.CONTESTED);
                    ww.reach((d, q) -> true, 1.0);
                    ww.decisions((sp, k, rr, gg) -> { });
                    long w2 = cpu();
                    needCpu += w1 - w0; walkCpu += w2 - w1;
                    cNstratSum += count(Ns); nStratWalks++;
                    or(Nc, Ns);
                    need(ct, st.play(), chartHits, NallChart);
                }
                long nc = count(Nc);
                cNcountSum += nc; nCountUnions++;
                cNcountMax = Math.max(cNcountMax, nc);
                perCount.computeIfAbsent(c.name, k -> new long[2])[0] += nc;
                perCount.get(c.name)[1]++;
                or(Nall, Nc);
                if (c.name.startsWith("Hi-Lo") && !c.name.startsWith("Hi-Lo Lite")) hiLoUnion = Nc;
            }
            cNall += count(Nall); cNallChart += count(NallChart); cNchartOnly += count(NchartOnly);

            // sub-trees built on R, on N in all, on Hi-Lo's N alone: time and bits
            long[] tr = new long[6];
            double[][] rOnly = build(engine, shoe, R, tr);
            sR += (tr[0] + tr[1] + tr[2] + tr[3]) / 1e9;
            long[] tn = new long[6];
            double[][] nOnly = build(engine, shoe, Nall, tn);
            sNall += (tn[0] + tn[1] + tn[2] + tn[3]) / 1e9;
            long[] th = new long[6];
            if (hiLoUnion != null) {
                build(engine, shoe, hiLoUnion, th);
                sNcount += (th[0] + th[1] + th[2] + th[3]) / 1e9;
            }
            long q0 = cpu();
            double[][] fFull = FastProbe.fast2(engine, plans, shoe, null, new long[3]);
            long q1 = cpu();
            double[][] fN = FastProbe.fast2(engine, plans, shoe, Nall, new long[3]);
            long q2 = cpu();
            double[][] fH = hiLoUnion == null ? null : FastProbe.fast2(engine, plans, shoe, hiLoUnion, new long[3]);
            long q3 = cpu();
            sF2 += (q1 - q0) / 1e9; sF2N += (q2 - q1) / 1e9; sF2H += (q3 - q2) / 1e9;
            for (int u = 1; u <= 10; u++) {
                double[] row = tree.standRow(u);
                for (int h = 0; h < HN; h++) {
                    cellsCompared++;
                    if (Double.doubleToRawLongBits(row[h]) != Double.doubleToRawLongBits(fFull[u][h])) bitDiff++;
                    if (Nall[u][h]) { cellsCompared++; if (Double.doubleToRawLongBits(row[h]) != Double.doubleToRawLongBits(fN[u][h])) bitDiff++; }
                    if (fH != null && hiLoUnion[u][h]) { cellsCompared++; if (Double.doubleToRawLongBits(row[h]) != Double.doubleToRawLongBits(fH[u][h])) bitDiff++; }
                }
            }
            for (int u = 1; u <= 10; u++) {
                double[] row = tree.standRow(u);
                for (int h = 0; h < HN; h++) {
                    if (R[u][h]) { cellsCompared++; if (Double.doubleToRawLongBits(row[h]) != Double.doubleToRawLongBits(rOnly[u][h])) bitDiff++; }
                    if (Nall[u][h]) { cellsCompared++; if (Double.doubleToRawLongBits(row[h]) != Double.doubleToRawLongBits(nOnly[u][h])) bitDiff++; }
                }
            }

            // compression of R's cells, as raw doubles
            ByteBuffer bb = ByteBuffer.allocate(8 * 10 * HN);
            ByteBuffer bx = ByteBuffer.allocate(8 * 10 * HN);
            for (int u = 1; u <= 10; u++) {
                double[] row = tree.standRow(u);
                long prev = 0;
                for (int h = 0; h < HN; h++) {
                    if (!R[u][h]) continue;
                    long bits = Double.doubleToRawLongBits(row[h]);
                    bb.putLong(bits);
                    bx.putLong(bits ^ prev);
                    prev = bits;
                }
            }
            rawBytes += bb.position();
            deflBytes += deflate(bb.array(), bb.position());
            xorBytes += deflate(bx.array(), bx.position());

            // validation by poison: R for every walk of a few plays; N per strategy for its own walk
            if (!poisonChecks) { if (si % 10 == 9) System.out.printf("  %d states done%n", si + 1); continue; }
            double[][] poisonR = poison(tree, R);
            PlayerTree pR = new PlayerTree(shoe, poisonR);
            for (CountKeys.Count c : counts) {
                boolean core = c == counts.get(0) || c.name.startsWith("Hi-Lo") || c.name.startsWith("KISS I")
                        || c.name.startsWith("Red Seven");
                if (!core) continue;
                PlayerTree.Counted a1 = counted.get(c);
                PlayerTree.Counted b1 = pR.count(c, r.dealt);
                List<PlayerTree.LaterPlay> plays = new ArrayList<>();
                plays.add(PlayerTree.CHART);
                plays.add(thresholds(rnd, a1.count.keyAfter(a1.rc, a1.depth, 0, 3), 4));
                plays.add((tot, soft, up, key) -> PlayerTree.contested(tot, soft) || PlayerTree.CHART.stands(tot, soft, up, key));
                plays.add((tot, soft, up, key) -> !PlayerTree.contested(tot, soft) && PlayerTree.CHART.stands(tot, soft, up, key));
                for (Strat st : strats) if (st.count == c && r.depth < SystemScorer.CUTS[st.pen]) plays.add(st.play());
                for (PlayerTree.LaterPlay p : plays) {
                    valChecks++;
                    if (!sameAll(a1, b1, p)) valFail++;
                }
                for (Strat st : strats) {
                    if (st.count != c || r.depth >= SystemScorer.CUTS[st.pen]) continue;
                    boolean[][] Ns = new boolean[11][HN];
                    need(a1, st.play(), null, Ns);
                    PlayerTree pN = new PlayerTree(shoe, poison(tree, Ns));
                    nValChecks++;
                    if (!sameUsed(a1, pN.count(c, r.dealt), st.play())) nValFail++;
                }
                // the chart alone, pass 0, with N from the chart's first moves
                boolean[][] Nc0 = new boolean[11][HN];
                need(a1, PlayerTree.CHART, chartHits, Nc0);
                PlayerTree pC = new PlayerTree(shoe, poison(tree, Nc0));
                nValChecks++;
                if (!sameUsedChartFirst(a1, pC.count(c, r.dealt), chartHits)) nValFail++;
            }
            if (si % 10 == 9) System.out.printf("  %d states done%n", si + 1);
        }

        int n = sample.size();
        System.out.printf("%n%d states sampled (offsets %s, %d each); depths:", n, a[2], per);
        for (StateStore.Record r : sample) System.out.print(" " + r.depth);
        System.out.println();
        System.out.printf("tree build, as PlayerTree does it: %.4f s CPU a state (one thread, nice 15, machine shared)%n", sFull / n);
        System.out.printf("  replicated: %.4f s; of it dealerOutcomes setup %.4f, gathering the shoes %.4f, the batched distributions %.4f, stood %.4f; "
                        + "shoes valued one at a time %d in all%n",
                sRep / n, parts[0] / 1e9 / n, parts[1] / 1e9 / n, parts[2] / 1e9 / n, parts[3] / 1e9 / n, parts[4]);
        System.out.printf("cells a state: feasible %.1f of %d; R (the contested walk as written, every count, any play) %.1f (%.1f%% of feasible); "
                        + "E (the perfect walk) %.1f%n", cFeas / (double) n, 10 * HN, cR / (double) n, 100.0 * cR / cFeas, cE / (double) n);
        System.out.printf("  N (a top-down walk: departing parts + reached decisions, every start hitting): one strategy %.1f (%.1f%%), "
                        + "one count's 12 strategies + the chart %.1f (%.1f%%, most %d), all %d strategies + chart %.1f (%.1f%%)%n",
                cNstratSum / (double) Math.max(1, nStratWalks), 100.0 * cNstratSum / Math.max(1, nStratWalks) / (cFeas / (double) n),
                cNcountSum / (double) nCountUnions, 100.0 * cNcountSum / nCountUnions / (cFeas / (double) n), cNcountMax,
                strats.size(), cNall / (double) n, 100.0 * cNall / cFeas);
        System.out.printf("  N with the chart's first moves only: every strategy %.1f (%.1f%%); the chart alone (pass 0's later play) %.1f (%.1f%%)%n",
                cNallChart / (double) n, 100.0 * cNallChart / cFeas, cNchartOnly / (double) n, 100.0 * cNchartOnly / cFeas);
        for (Map.Entry<String, long[]> e : perCount.entrySet()) {
            System.out.printf("    %-60s N %.1f (%.1f%%)%n", e.getKey().length() > 60 ? e.getKey().substring(0, 60) : e.getKey(),
                    e.getValue()[0] / (double) e.getValue()[1], 100.0 * e.getValue()[0] / e.getValue()[1] / (cFeas / (double) n));
        }
        System.out.printf("build on R only %.4f s CPU a state (%.1f%% of the replicated full build); on N (all strategies) %.4f s (%.1f%%); "
                        + "on Hi-Lo's N %.4f s (%.1f%%)%n", sR / n, 100 * sR / sRep, sNall / n, 100 * sNall / sRep, sNcount / n, 100 * sNcount / sRep);
        System.out.printf("per strategy at a state: the structural pass (need) %.3f ms CPU, the walk with reach and decisions %.3f ms%n",
                needCpu / 1e6 / Math.max(1, nStratWalks), walkCpu / 1e6 / Math.max(1, nStratWalks));
        System.out.printf("regrouped build (FastProbe.fast2): every cell %.4f s (%.1f%% of PlayerTree), N in all %.4f s (%.1f%%), Hi-Lo's N %.4f s (%.1f%%)%n",
                sF2 / n, 100 * sF2 / sFull, sF2N / n, 100 * sF2N / sFull, sF2H / n, 100 * sF2H / sFull);
        System.out.printf("bits: %d cells compared (full replica, R-only, N-only against PlayerTree), %d differ%n", cellsCompared + cFeas, bitDiff);
        System.out.printf("poison checks: eager walks on an R-only tree %d of %d differ in any output; top-down N per play %d of %d differ in a used output%n",
                valFail, valChecks, nValFail, nValChecks);
        System.out.printf("R's cells as doubles: %.0f B a state raw, deflate %.0f B (%.2fx), xor-previous + deflate %.0f B (%.2fx)%n",
                rawBytes / (double) n, deflBytes / (double) n, rawBytes / (double) deflBytes, xorBytes / (double) n, rawBytes / (double) xorBytes);
        long distinct = 0;
        for (int u = 1; u <= 10; u++) distinct += keys[u].size();
        System.out.printf("dealer shoes (up-card, S - H - u) over the sample: %d asked, %d distinct%n", keyTotal, distinct);
    }

    static long pack(int[] shoe, int h, int u) {
        long k = 0;
        for (int r = 1; r <= 10; r++) {
            int left = shoe[r] - HANDS.held[h * 11 + r] - (r == u ? 1 : 0);
            k = k * (r == 10 ? 256 : 64) + left;
        }
        return k;
    }

    static int deflate(byte[] b, int len) {
        Deflater d = new Deflater(9);
        d.setInput(b, 0, len);
        d.finish();
        byte[] out = new byte[len + 1024];
        int total = 0;
        while (!d.finished()) total += d.deflate(out);
        d.end();
        return total;
    }

    static void dealerEntries(ExactRound engine, int[] shoe) throws Exception {
        Field f = ExactRound.DealerOutcomes.class.getDeclaredField("dealer");
        f.setAccessible(true);
        StringBuilder sb = new StringBuilder("dealer hands by up-card (entries, rank factors):");
        long all = 0;
        for (int u = 1; u <= 10; u++) {
            ExactRound.DealerOutcomes d = engine.dealerOutcomes(shoe, u);
            Object dh = f.get(d);
            Field fe = dh.getClass().getDeclaredField("entries"); fe.setAccessible(true);
            Field fs = dh.getClass().getDeclaredField("start"); fs.setAccessible(true);
            int e = fe.getInt(dh);
            int[] st = (int[]) fs.get(dh);
            sb.append(String.format(" %d:%d,%d", u, e, st[e] - st[0]));
            all += e + (st[e] - st[0]);
        }
        System.out.println(sb + "; mostCardsToRunOutOf(up 2) " + engine.dealerOutcomes(shoe, 2).mostCardsToRunOutOf());
    }

    /** PlayerTree's constructor, with want selecting the cells (null: every one), and CPU split into parts. */
    static double[][] build(ExactRound engine, int[] shoe, boolean[][] want, long[] t) {
        int cards = 0;
        for (int r = 1; r <= 10; r++) cards += shoe[r];
        double[] outcomes = new double[OUT];
        double[][] many = new double[OUT][HN];
        int[][] counts = new int[11][HN];
        int[] sizes = new int[HN];
        int[] which = new int[HN];
        int[] left = new int[11];
        double[][] stand = new double[11][];
        for (int u = 1; u <= 10; u++) {
            double[] s = new double[HN];
            Arrays.fill(s, Double.NaN);
            stand[u] = s;
            if (shoe[u] == 0) continue;
            long c0 = cpu();
            ExactRound.DealerOutcomes dealer = engine.dealerOutcomes(shoe, u);
            long c1 = cpu();
            int together = 0;
            for (int h = 0; h < HN; h++) {
                if (want != null && !want[u][h]) continue;
                boolean fits = true;
                for (int r = 1; r <= 10; r++) {
                    left[r] = shoe[r] - HANDS.held[h * 11 + r] - (r == u ? 1 : 0);
                    fits &= left[r] >= 0;
                }
                if (!fits) continue;
                int after = cards - HANDS.cards[h] - 1;
                if (after > dealer.mostCardsToRunOutOf()) {
                    for (int r = 1; r <= 10; r++) counts[r][together] = left[r];
                    sizes[together] = after;
                    which[together] = h;
                    together++;
                    continue;
                }
                t[4]++;
                if (dealer.outcomes(left, outcomes)) throw new IllegalStateException("runs out");
                s[h] = ExactRound.DealerOutcomes.stood(HANDS.total(h), outcomes);
            }
            long c2 = cpu();
            if (together > 0) dealer.outcomes(counts, sizes, together, many);
            long c3 = cpu();
            for (int k = 0; k < together; k++) s[which[k]] = ExactRound.DealerOutcomes.stood(HANDS.total(which[k]), many, k);
            long c4 = cpu();
            t[0] += c1 - c0; t[1] += c2 - c1; t[2] += c3 - c2; t[3] += c4 - c3;
            t[5] += together;
        }
        return stand;
    }

    static double[][] poison(PlayerTree tree, boolean[][] keep) {
        double[][] p = new double[11][];
        for (int u = 1; u <= 10; u++) {
            double[] row = tree.standRow(u).clone();
            for (int h = 0; h < HN; h++) if (!keep[u][h] && !Double.isNaN(row[h])) row[h] = POISON;
            p[u] = row;
        }
        p[0] = null;
        return p;
    }

    static long count(boolean[][] m) {
        long c = 0;
        for (int u = 1; u <= 10; u++) for (boolean b : m[u]) if (b) c++;
        return c;
    }

    static void or(boolean[][] into, boolean[][] from) {
        for (int u = 1; u <= 10; u++) for (int h = 0; h < HN; h++) into[u][h] |= from[u][h];
    }

    /** Every stand cell the walk as written reads, whatever the play: asked nodes, and the hands its draws stand on. */
    static void markEager(PlayerTree.Counted c, PlayerTree.Mode mode, boolean[][] mark) {
        PlayerTree.Graph g = c.graph;
        PlayerTree.Live live = g.live(mode);
        int slots = g.slots;
        for (int t = 0; t < c.trees; t++) {
            int u = c.treeUp[t], upSlot = c.treeUpSlot[t];
            boolean[] ok = c.feasible[t];
            for (int j = 0; j < live.node.length; j++) {
                int node = live.node[j];
                if (!ok[node]) continue;
                if (live.role[j] == PlayerTree.ASKED) mark[u][g.hand[node]] = true;
                for (int s = 0; s < slots; s++) {
                    int cnt = c.left[s] - g.taken[node * slots + s] - (s == upSlot ? 1 : 0);
                    if (cnt == 0) continue;
                    int to = live.target[j * slots + s];
                    if (to <= -2) mark[u][-2 - to] = true;
                }
            }
        }
    }

    /**
     * The cells a top-down contested walk of play P needs: Hit values of the parts that depart
     * from the chart (the others take the stored V(Hit)), and stand and Hit at every asked node
     * reach arrives at (the gaps). Reach starts at every start (hits == null) or at the deals
     * whose chart move is Hit.
     */
    static void need(PlayerTree.Counted c, PlayerTree.LaterPlay P, boolean[] hits, boolean[][] mark) {
        PlayerTree.Graph g = c.graph;
        PlayerTree.Live live = g.live(PlayerTree.Mode.CONTESTED);
        int slots = g.slots;
        int size = live.node.length;
        int T = c.trees;
        boolean[][] stay = new boolean[T][size], dep = new boolean[T][size];
        for (int t = 0; t < T; t++) {
            int u = c.treeUp[t], upSlot = c.treeUpSlot[t];
            boolean[] ok = c.feasible[t];
            int[] keys = c.key[t];
            for (int j = size - 1; j >= 0; j--) {
                int node = live.node[j];
                if (!ok[node]) continue;
                boolean below = false;
                for (int s = 0; s < slots; s++) {
                    int cnt = c.left[s] - g.taken[node * slots + s] - (s == upSlot ? 1 : 0);
                    if (cnt == 0) continue;
                    int to = live.target[j * slots + s];
                    if (to >= 0) below |= dep[t][to];
                }
                if (live.role[j] == PlayerTree.ASKED) {
                    int h = g.hand[node];
                    int total = HANDS.total(h);
                    boolean soft = HANDS.soft(h);
                    boolean st = P.stands(total, soft, u, keys[node]);
                    stay[t][j] = st;
                    dep[t][j] = st != PlayerTree.CHART.stands(total, soft, u, 0) || (!st && below);
                } else {
                    dep[t][j] = below;
                }
            }
        }
        boolean[][] needH = new boolean[T][size], needV = new boolean[T][size], reached = new boolean[T][size];
        for (int i = 0; i < Deals.COUNT; i++) {
            for (int p = c.partFrom[i]; p < c.partFrom[i + 1]; p++) {
                boolean departs = false;
                for (int q = c.compFrom[i]; q < c.compFrom[i + 1]; q++) {
                    if (c.compPart[q] == p) departs |= dep[c.compTree[q]][live.position[c.compNode[q]]];
                }
                if (!departs) continue;
                for (int q = c.compFrom[i]; q < c.compFrom[i + 1]; q++) {
                    if (c.compPart[q] == p) needH[c.compTree[q]][live.position[c.compNode[q]]] = true;
                }
            }
            if (hits == null || hits[i]) {
                for (int q = c.compFrom[i]; q < c.compFrom[i + 1]; q++) reached[c.compTree[q]][live.position[c.compNode[q]]] = true;
            }
        }
        for (int t = 0; t < T; t++) {
            int u = c.treeUp[t], upSlot = c.treeUpSlot[t];
            for (int j = 0; j < size; j++) {
                if (!reached[t][j]) continue;
                if (live.role[j] == PlayerTree.ASKED) {
                    mark[u][g.hand[live.node[j]]] = true;
                    needH[t][j] = true;
                    if (stay[t][j]) continue;
                }
                int node = live.node[j];
                for (int s = 0; s < slots; s++) {
                    int to = live.target[j * slots + s];
                    if (to < 0) continue;
                    int cnt = c.left[s] - g.taken[node * slots + s] - (s == upSlot ? 1 : 0);
                    if (cnt == 0) continue;
                    reached[t][to] = true;
                }
            }
            for (int j = 0; j < size; j++) {
                int node = live.node[j];
                if (needV[t][j]) {
                    if (live.role[j] == PlayerTree.ASKED && stay[t][j]) mark[u][g.hand[node]] = true;
                    else needH[t][j] = true;
                }
                if (!needH[t][j]) continue;
                for (int s = 0; s < slots; s++) {
                    int cnt = c.left[s] - g.taken[node * slots + s] - (s == upSlot ? 1 : 0);
                    if (cnt == 0) continue;
                    int to = live.target[j * slots + s];
                    if (to == PlayerTree.Graph.BUST) continue;
                    if (to >= 0) needV[t][to] = true;
                    else mark[u][-2 - to] = true;
                }
            }
        }
    }

    static PlayerTree.LaterPlay thresholds(Random rnd, int centre, int spread) {
        int[] at = new int[PlayerTree.SPOTS];
        for (int s = 0; s < at.length; s++) at[s] = centre - spread + rnd.nextInt(2 * spread + 1);
        return (total, soft, up, key) -> PlayerTree.contested(total, soft)
                ? key >= at[PlayerTree.spot(total, soft, up)]
                : PlayerTree.CHART.stands(total, soft, up, key);
    }

    static final class Rec implements PlayerTree.DecisionSink {
        final List<long[]> rows = new ArrayList<>();
        public void add(int spot, int key, double reach, double gap) {
            rows.add(new long[]{spot, key, Double.doubleToRawLongBits(reach), Double.doubleToRawLongBits(gap)});
        }
        boolean same(Rec o) {
            if (rows.size() != o.rows.size()) return false;
            for (int i = 0; i < rows.size(); i++) if (!Arrays.equals(rows.get(i), o.rows.get(i))) return false;
            return true;
        }
    }

    static boolean bitsEqual(double[] x, double[] y) {
        if (x.length != y.length) return false;
        for (int i = 0; i < x.length; i++) if (Double.doubleToRawLongBits(x[i]) != Double.doubleToRawLongBits(y[i])) return false;
        return true;
    }

    /** Every output of the eager walk, for every first-move rule: same bits on the full tree and the R-only one. */
    static boolean sameAll(PlayerTree.Counted a, PlayerTree.Counted b, PlayerTree.LaterPlay p) {
        PlayerTree.Walk wa = a.walk(p, PlayerTree.Mode.CONTESTED);
        PlayerTree.Walk wb = b.walk(p, PlayerTree.Mode.CONTESTED);
        if (!bitsEqual(wa.partHit, wb.partHit) || !Arrays.equals(wa.partDeparts, wb.partDeparts)) return false;
        for (int t = 0; t < wa.value.length; t++) {
            if (!bitsEqual(wa.value[t], wb.value[t]) || !bitsEqual(wa.hit[t], wb.hit[t])) return false;
        }
        wa.reach((d, q) -> true, 1.2345);
        wb.reach((d, q) -> true, 1.2345);
        Rec ra = new Rec(), rb = new Rec();
        wa.decisions(ra);
        wb.decisions(rb);
        return ra.same(rb) && Double.doubleToRawLongBits(wa.terminalMass()) == Double.doubleToRawLongBits(wb.terminalMass());
    }

    /** The outputs a run uses: Hit of the parts that depart, the departures, and the decisions with every start hitting. */
    static boolean sameUsed(PlayerTree.Counted a, PlayerTree.Counted b, PlayerTree.LaterPlay p) {
        PlayerTree.Walk wa = a.walk(p, PlayerTree.Mode.CONTESTED);
        PlayerTree.Walk wb = b.walk(p, PlayerTree.Mode.CONTESTED);
        if (!Arrays.equals(wa.partDeparts, wb.partDeparts)) return false;
        for (int q = 0; q < wa.partHit.length; q++) {
            if (wa.partDeparts[q] && Double.doubleToRawLongBits(wa.partHit[q]) != Double.doubleToRawLongBits(wb.partHit[q])) return false;
        }
        wa.reach((d, q) -> true, 1.2345);
        wb.reach((d, q) -> true, 1.2345);
        Rec ra = new Rec(), rb = new Rec();
        wa.decisions(ra);
        wb.decisions(rb);
        return ra.same(rb);
    }

    static boolean sameUsedChartFirst(PlayerTree.Counted a, PlayerTree.Counted b, boolean[] hits) {
        PlayerTree.Walk wa = a.walk(PlayerTree.CHART, PlayerTree.Mode.CONTESTED);
        PlayerTree.Walk wb = b.walk(PlayerTree.CHART, PlayerTree.Mode.CONTESTED);
        if (!Arrays.equals(wa.partDeparts, wb.partDeparts)) return false;
        for (int q = 0; q < wa.partHit.length; q++) {
            if (wa.partDeparts[q] && Double.doubleToRawLongBits(wa.partHit[q]) != Double.doubleToRawLongBits(wb.partHit[q])) return false;
        }
        wa.reach((d, q) -> hits[d], 1.2345);
        wb.reach((d, q) -> hits[d], 1.2345);
        Rec ra = new Rec(), rb = new Rec();
        wa.decisions(ra);
        wb.decisions(rb);
        return ra.same(rb);
    }
}
