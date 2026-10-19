import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Scratch probe (not repo code): which stand values a CONTESTED walk can read, how many a
 * state holds, whether a tree built and stored with only those gives the same walks to the
 * bit, and how long building only those takes against a full build.
 */
public class TreeSubsetProbe {

    static final PlayerTree.Hands H = PlayerTree.Hands.ALL;

    /** Hands whose stand value a walk of this mode reads in its backward loop (ASKED nodes and stand targets). */
    static boolean[] readHands(PlayerTree.Graph g, PlayerTree.Mode mode, boolean reachableOnly) {
        PlayerTree.Live live = g.live(mode);
        int size = live.node.length;
        boolean[] on = new boolean[size];
        if (reachableOnly) {
            // positions reachable from a start through visited nodes that may hit (START, HITS, ASKED).
            for (int j = 0; j < size; j++) {
                if (live.role[j] == PlayerTree.START) on[j] = true;
            }
            for (int j = 0; j < size; j++) {   // positions are in fewest-cards-first order
                if (!on[j]) continue;
                for (int s = 0; s < g.slots; s++) {
                    int to = live.target[j * g.slots + s];
                    if (to >= 0) on[to] = true;
                }
            }
        } else {
            Arrays.fill(on, true);
        }
        boolean[] read = new boolean[H.size];
        for (int j = 0; j < size; j++) {
            if (!on[j]) continue;
            if (live.role[j] == PlayerTree.ASKED) read[g.hand[live.node[j]]] = true;
            for (int s = 0; s < g.slots; s++) {
                int to = live.target[j * g.slots + s];
                if (to <= -2) read[-2 - to] = true;
            }
        }
        return read;
    }

    static int count(boolean[] b) {
        int n = 0;
        for (boolean x : b) n += x ? 1 : 0;
        return n;
    }

    /** PlayerTree's constructor, restricted to the hands needed; others feasible get 0.0, infeasible NaN. */
    static double[][] prunedRows(ExactRound engine, int[] shoe, boolean[] need) {
        int cards = 0;
        for (int r = 1; r <= 10; r++) cards += shoe[r];
        double[][] stand = new double[11][];
        double[] outcomes = new double[ExactRound.DealerOutcomes.OUTCOMES];
        double[][] many = new double[ExactRound.DealerOutcomes.OUTCOMES][H.size];
        int[][] counts = new int[11][H.size];
        int[] sizes = new int[H.size];
        int[] which = new int[H.size];
        int[] left = new int[11];
        for (int u = 1; u <= 10; u++) {
            double[] s = new double[H.size];
            Arrays.fill(s, Double.NaN);
            stand[u] = s;
            if (shoe[u] == 0) continue;
            ExactRound.DealerOutcomes dealer = engine.dealerOutcomes(shoe, u);
            int together = 0;
            for (int h = 0; h < H.size; h++) {
                boolean fits = true;
                for (int r = 1; r <= 10; r++) {
                    left[r] = shoe[r] - H.held[h * 11 + r] - (r == u ? 1 : 0);
                    fits &= left[r] >= 0;
                }
                if (!fits) continue;
                if (!need[h]) {
                    s[h] = 0.0;   // feasible, never read by the walk
                    continue;
                }
                int after = cards - H.cards[h] - 1;
                if (after > dealer.mostCardsToRunOutOf()) {
                    for (int r = 1; r <= 10; r++) counts[r][together] = left[r];
                    sizes[together] = after;
                    which[together] = h;
                    together++;
                    continue;
                }
                if (dealer.outcomes(left, outcomes)) throw new IllegalStateException("runs out");
                s[h] = ExactRound.DealerOutcomes.stood(H.total(h), outcomes);
            }
            dealer.outcomes(counts, sizes, together, many);
            for (int k = 0; k < together; k++) {
                s[which[k]] = ExactRound.DealerOutcomes.stood(H.total(which[k]), many, k);
            }
        }
        return stand;
    }

    /** A deterministic arbitrary later play: stand or hit at each (spot, key) by a hash. */
    static PlayerTree.LaterPlay randomPlay(long seed) {
        return (total, soft, up, key) -> {
            long x = seed ^ (total * 1_000_003L) ^ (soft ? 7919L : 0) ^ (up * 104_729L) ^ (key * 15_485_863L);
            x ^= x >>> 33; x *= 0xff51afd7ed558ccdL; x ^= x >>> 33;
            return (x & 1) == 0;
        };
    }

    public static void main(String[] a) throws Exception {
        Path data = Paths.get(a[0]);
        int shoes = Integer.parseInt(a[1]);
        ScoringStates st = FullIndexPilot.load(data, shoes);
        System.out.printf("states loaded: %d from %d shoes%n", st.size, shoes);
        List<CountKeys.Count> cat = CountKeys.catalog();

        // ---- structure: hands read, per graph, per mode
        int[] ones = new int[11];
        Arrays.fill(ones, 1);
        PlayerTree.Graph g1 = PlayerTree.Graph.of(ones);
        boolean[] needC = readHands(g1, PlayerTree.Mode.CONTESTED, false);
        boolean[] needCr = readHands(g1, PlayerTree.Mode.CONTESTED, true);
        boolean[] needE = readHands(g1, PlayerTree.Mode.EVERY_NODE, false);
        int starts = 0, three = 0;
        for (int h = 0; h < H.size; h++) { if (H.cards[h] == 2) starts++; else three++; }
        System.out.printf("hands per up-card: %d (%d two-card starts, %d of 3+ cards)%n", H.size, starts, three);
        System.out.printf("rank graph: %d nodes; contested live %d positions; every-node live %d%n",
                g1.size, g1.live(PlayerTree.Mode.CONTESTED).node.length, g1.live(PlayerTree.Mode.EVERY_NODE).node.length);
        System.out.printf("hands read: contested walk %d (reachable-only %d); every-node walk (perfect) %d%n",
                count(needC), count(needCr), count(needE));
        int contestedHands = 0, standTargets = 0;
        for (int h = 0; h < H.size; h++) {
            if (!needC[h]) continue;
            if (H.cards[h] >= 3 && PlayerTree.contested(H.total(h), H.soft(h))) contestedHands++; else standTargets++;
        }
        System.out.printf("  of the contested walk's: %d contested hands (3+ cards), %d chart-stand children%n",
                contestedHands, standTargets);
        // the same hand set for every count's graph?
        Set<String> seen = new HashSet<>();
        for (CountKeys.Count c : cat) {
            int[] cl = new int[11];
            for (int r = 1; r <= 10; r++) cl[r] = c.classes(r);
            String k = Arrays.toString(cl);
            if (!seen.add(k)) continue;
            PlayerTree.Graph g = PlayerTree.Graph.of(cl);
            boolean[] n = readHands(g, PlayerTree.Mode.CONTESTED, false);
            System.out.printf("graph classes %s (%s...): %d nodes, contested live %d, hands read %d, same set as rank graph: %b%n",
                    k, c.name.substring(0, Math.min(20, c.name.length())), g.size,
                    g.live(PlayerTree.Mode.CONTESTED).node.length, count(n), Arrays.equals(n, needC));
        }

        // ---- per state: feasible hands, needed feasible hands; build times; bit checks
        ExactRound engine = new ExactRound(st.rules);
        long feasAll = 0, feasNeed = 0, feasNeedR = 0, rows = 0;
        int minNeed = Integer.MAX_VALUE, maxNeed = 0;
        double tFull = 0, tPruned = 0;
        long mismatchStand = 0, compared = 0;
        // warm up
        for (int s = 0; s < Math.min(5, st.size); s++) {
            int[] shoe = ShoeRun.ranksLeft(st.dealt[s]);
            new PlayerTree(engine, shoe);
            prunedRows(engine, shoe, needC);
        }
        List<CountKeys.Count> test = new ArrayList<>();
        for (CountKeys.Count c : cat) {
            if (c.name.startsWith("Hi-Lo") || c.name.startsWith("Red Seven") || c.name.startsWith("KISS I ")
                    || c.name.equals("KISS I") || c.name.startsWith("Omega II") || c.name.startsWith("BRH-I ") || c.name.equals("BRH-I")) {
                test.add(c);
            }
        }
        System.out.print("walk check counts:");
        for (CountKeys.Count c : test) System.out.print(" [" + c.name + "]");
        System.out.println();
        long walkParts = 0, walkPartDiff = 0, decN = 0, decDiff = 0, departs = 0;
        for (int s = 0; s < st.size; s++) {
            int[] shoe = ShoeRun.ranksLeft(st.dealt[s]);
            long t0 = System.nanoTime();
            PlayerTree full = new PlayerTree(engine, shoe);
            long t1 = System.nanoTime();
            double[][] pr = prunedRows(engine, shoe, needC);
            long t2 = System.nanoTime();
            tFull += (t1 - t0) / 1e9;
            tPruned += (t2 - t1) / 1e9;
            int needHere = 0;
            for (int u = 1; u <= 10; u++) {
                double[] fr = full.standRow(u);
                for (int h = 0; h < H.size; h++) {
                    boolean f = !Double.isNaN(fr[h]);
                    if (Double.isNaN(fr[h]) != Double.isNaN(pr[u][h])) mismatchStand++;
                    if (!f) continue;
                    feasAll++;
                    if (needC[h]) {
                        feasNeed++;
                        needHere++;
                        compared++;
                        if (Double.doubleToRawLongBits(fr[h]) != Double.doubleToRawLongBits(pr[u][h])) mismatchStand++;
                    }
                    if (needCr[h]) feasNeedR++;
                }
                rows++;
            }
            minNeed = Math.min(minNeed, needHere);
            maxNeed = Math.max(maxNeed, needHere);
            PlayerTree pruned = new PlayerTree(shoe, pr);
            for (CountKeys.Count c : test) {
                PlayerTree.Counted cf = full.count(c, st.dealt[s]);
                PlayerTree.Counted cp = pruned.count(c, st.dealt[s]);
                for (int pl = 0; pl < 3; pl++) {
                    PlayerTree.LaterPlay play = pl == 0 ? PlayerTree.CHART : randomPlay(pl * 977L + s);
                    PlayerTree.Walk wf = cf.walk(play, PlayerTree.Mode.CONTESTED);
                    PlayerTree.Walk wp = cp.walk(play, PlayerTree.Mode.CONTESTED);
                    for (int q = 0; q < wf.partHit.length; q++) {
                        walkParts++;
                        if (Double.doubleToRawLongBits(wf.partHit[q]) != Double.doubleToRawLongBits(wp.partHit[q])
                                || wf.partDeparts[q] != wp.partDeparts[q]) walkPartDiff++;
                        if (wf.partDeparts[q]) departs++;
                    }
                    final int ss = s; PlayerTree.FirstHits fh = (deal, part) -> ((deal * 31 + part + ss) % 3) != 0;
                    wf.reach(fh, 1.7);
                    wp.reach(fh, 1.7);
                    List<double[]> df = new ArrayList<>();
                    List<double[]> dp = new ArrayList<>();
                    wf.decisions((spot, key, r, gg) -> df.add(new double[]{spot, key, r, gg}));
                    wp.decisions((spot, key, r, gg) -> dp.add(new double[]{spot, key, r, gg}));
                    if (df.size() != dp.size()) {
                        decDiff += Math.abs(df.size() - dp.size()) + 1;
                    } else {
                        for (int k = 0; k < df.size(); k++) {
                            decN++;
                            for (int z = 0; z < 4; z++) {
                                if (Double.doubleToRawLongBits(df.get(k)[z]) != Double.doubleToRawLongBits(dp.get(k)[z])) {
                                    decDiff++;
                                    break;
                                }
                            }
                        }
                    }
                    if (Double.doubleToRawLongBits(wf.terminalMass()) != Double.doubleToRawLongBits(wp.terminalMass())) decDiff++;
                }
            }
        }
        System.out.printf("states %d: feasible stand values a state %.1f (of %d slots); needed by the contested walk %.1f a state (min %d, max %d); reachable-only %.1f%n",
                st.size, feasAll / (double) st.size, 10 * H.size, feasNeed / (double) st.size, minNeed, maxNeed, feasNeedR / (double) st.size);
        System.out.printf("bytes a state: full row store %d B; needed doubles only %.0f B (%.1f%%); 60,743 states: %.2f GB vs %.2f GB%n",
                10L * H.size * 8 + 48, feasNeed * 8.0 / st.size, 100.0 * feasNeed / st.size / (10.0 * H.size),
                (10L * H.size * 8 + 48) * 60743 / 1e9, (feasNeed * 8.0 / st.size + 48) * 60743 / 1e9);
        System.out.printf("stand values compared %d, bit mismatches (incl. NaN pattern) %d%n", compared, mismatchStand);
        System.out.printf("walks: part Hit values compared %d, differing %d (departing parts %d); decisions compared %d, differing %d%n",
                walkParts, walkPartDiff, departs, decN, decDiff);
        System.out.printf("build CPU a state, one thread at nice 15: full %.4f s, needed-only %.4f s (%.1f%%)%n",
                tFull / st.size, tPruned / st.size, 100 * tPruned / tFull);
    }
}
