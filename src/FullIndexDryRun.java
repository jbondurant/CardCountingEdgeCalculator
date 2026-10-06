import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The dry run of the joint full-index run (FullIndexFit.scoreJoint), on the first shoes of the
 * run's state file, read and never written, before the full run is sized (FULL_INDEX_PLAYS.md
 * section 6).
 *
 * It runs the whole thing as the full run will, at a smaller size: every count (the null count
 * and the catalog), at every penetration, both folds and the learning curve's half fits, fitted
 * together, then given their last rounds and scored in batches. Each round's figures are
 * printed: the states visited and the walks, the CPU seconds a state for the trees, the walks
 * and the split values, and the distinct post-split plays per (state, pair deal) over every
 * strategy of the round. Split values are kept per state from round to round, so a play met in
 * an earlier round is not valued again; each round also gives the distinct plays that are not
 * the chart's, which is what a cache kept for one state's visit would value, so both are known
 * from one run. Then the rounds every strategy took, the heap's peak while fitting and while
 * scoring, what the split values kept hold, and the batches.
 *
 * From these it projects the full run's wall time and memory for 8,000 to 15,000 states, and
 * the most states that fit in the hours given. The projection is linear in the states for
 * everything but the play groups, which grow as their square root; what each figure assumes
 * is printed beside it (Projection).
 *
 * Run it after ./run-tests.sh has compiled the sources, with the mongo jar on the class path
 * for the catalog:
 * java -Xmx10g -cp build/classes:lib/mongo-java-driver-3.12.14.jar FullIndexDryRun [state file]
 * [shoes] [resamples] [threads] [mPlay] [hours]
 */
public final class FullIndexDryRun {

    static final int[] PENS = {0, 1, 2};
    static final int[] SIZES = {8000, 10000, 12000, 15000};

    /** What the dry run measured, for the projection. */
    static final class Measured {
        ScoringStates st;
        int threads;
        int resamples;
        double mPlay;
        int counts;
        int strategies;
        FullIndexFit.Scored scored;
        /** Rounds each strategy was active in the passes (passes and clean-ups), and how many were capped. */
        final List<Integer> strategyRounds = new ArrayList<>();
        int capped;
        /** First-decision and later groups over every strategy, on average. */
        double firstGroups;
        double laterGroups;
        long peakFit;
        long heldAfterFit;
        long peakScore;
        long statesHeld;
        long cacheEntries;
        long cacheBytes;
        /** What every strategy holds per state of its penetration while it fits: insurance values, roles and training states. */
        double arrayBytes;
        double wall;
    }

    public static void main(String[] args) throws Exception {
        Path data = Paths.get(args.length > 0 ? args[0] : "runs/montreal-80.states");
        int shoes = args.length > 1 ? Integer.parseInt(args[1]) : 300;
        int resamples = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : 6;
        double mPlay = args.length > 4 ? Double.parseDouble(args[4]) : SystemScorer.M_PLAY;
        double hours = args.length > 5 ? Double.parseDouble(args[5]) : 16;
        PrintStream out = System.out;
        long t0 = System.nanoTime();
        ScoringStates st = FullIndexPilot.load(data, shoes);
        out.printf("# Full-index dry run%n%n");
        out.printf("FullIndexDryRun: the first %d shoes of %s, %d valued states, read and never written; M_play %.2f; "
                        + "%d resamples of seed %d; %d threads of %d processors; max heap %.0f MB; java %s.%n", shoes, data,
                st.size, mPlay, resamples, Bootstrap.SEED, threads, Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / 1e6, System.getProperty("java.vm.version"));
        out.printf("Started %s, load average %.2f; states read in %.1f s.%n", java.time.LocalDateTime.now(),
                FullIndexTimingReport.loadAverage(), (System.nanoTime() - t0) / 1e9);
        out.flush();
        Measured m = run(st, resamples, threads, mPlay, out);
        print(out, m);
        Projection.print(out, data, m, hours);
        out.printf("%nFinished %s, load average %.2f.%n", java.time.LocalDateTime.now(), FullIndexTimingReport.loadAverage());
    }

    /** Runs the joint fit and scoring on the states given, every count at every penetration, and measures it. */
    static Measured run(ScoringStates st, int resamples, int threads, double mPlay, PrintStream log) throws Exception {
        Measured m = new Measured();
        m.st = st;
        m.threads = threads;
        m.resamples = resamples;
        m.mPlay = mPlay;
        SystemScorer scorer = new SystemScorer(st, new Bootstrap(st.shoes, resamples, Bootstrap.SEED), mPlay);
        List<CountKeys.Count> counts = new ArrayList<>();
        counts.add(CountKeys.nullCount());
        counts.addAll(CountKeys.catalog());
        m.counts = counts.size();
        m.statesHeld = FullIndexPilot.usedAfterGc();
        FullIndexTimingReport.resetPeaks();
        FullIndexFit[] fitOf = new FullIndexFit[1];
        FullIndexFit.Watch watch = new FullIndexFit.Watch() {
            @Override
            public void fitted(List<FullIndexFit.Fits> fits, FullIndexFit fit) {
                fitOf[0] = fit;
                m.peakFit = FullIndexTimingReport.peakHeap();
                m.heldAfterFit = FullIndexPilot.usedAfterGc();
                double fg = 0;
                double lg = 0;
                for (FullIndexFit.Fits f : fits) {
                    for (FullIndexFit.Strategy t : f.all()) {
                        m.strategies++;
                        m.strategyRounds.add(t.passes + t.cleanUps);
                        m.capped += t.capped ? 1 : 0;
                        fg += t.groups.count();
                        lg += t.later.count();
                        m.arrayBytes += 10.0 * t.p.states.length + 4.0 * t.use.length;
                    }
                }
                m.firstGroups = fg / m.strategies;
                m.laterGroups = lg / m.strategies;
                log.printf("%nFitted %d strategies in %d rounds; heap peak %.0f MB while fitting, %.0f MB held after.%n",
                        m.strategies, fit.rounds.size(), m.peakFit / 1e6, m.heldAfterFit / 1e6);
                log.flush();
                FullIndexTimingReport.resetPeaks();
            }

            @Override
            public void finalRound(List<FullIndexFit.Fits> batch) {
                log.printf("A batch of %d counts had its last round; scoring it.%n", batch.size());
                log.flush();
            }
        };
        long w0 = System.nanoTime();
        double budget = 0.45 * Runtime.getRuntime().maxMemory();
        m.scored = FullIndexFit.scoreJoint(scorer, FullIndexFit.built(st), counts, PENS, budget, threads, true, true, null,
                watch, System.err);
        m.wall = (System.nanoTime() - w0) / 1e9;
        m.peakScore = FullIndexTimingReport.peakHeap();
        long with = FullIndexPilot.usedAfterGc();
        for (Map<String, Double> kept : fitOf[0].splitCache.values()) {
            m.cacheEntries += kept.size();
        }
        fitOf[0].splitCache.clear();
        m.cacheBytes = Math.max(0, with - FullIndexPilot.usedAfterGc());
        return m;
    }

    static void print(PrintStream out, Measured m) {
        FullIndexFit.Scored sc = m.scored;
        out.printf("%n## The joint passes%n%n");
        out.printf("%d counts, %d strategies (each count at %d penetrations, each fold and each half fit), fitted together "
                + "in %.1f s on the wall. Split values kept per state from round to round.%n%n", m.counts, m.strategies,
                PENS.length, sc.fitWall);
        roundTable(out, sc.rounds.get(0), m.st.size);
        FullIndexFit.RoundStats ft = FullIndexPilot.total(sc.rounds.get(0));
        out.printf("%nIn all: %d state visits, %d walks, %d of them worked out; CPU a state visit: trees %.4f s, walks %.4f s "
                        + "(%.3f ms a strategy's walk, its reach and sums included), splits %.4f s (%.1f ms a split valued); "
                        + "%.2f threads busy of %d.%n",
                ft.states, ft.walks, ft.walksMade, ft.treeCpu / ft.states, ft.walkCpu / ft.states,
                1000 * ft.walkCpu / Math.max(1, ft.walks), ft.splitCpu / ft.states,
                1000 * ft.splitCpu / Math.max(1, ft.splitValued), cpu(ft) / ft.wall, m.threads);
        out.printf("Rounds a strategy fitted (passes and clean-ups): %s; %d of %d capped. On average %.2f first-decision "
                + "groups and %.2f later groups a strategy.%n", FullIndexPilot.histogram(m.strategyRounds), m.capped,
                m.strategies, m.firstGroups, m.laterGroups);
        out.printf("Heap: %.0f MB held with the states alone; peak %.0f MB while fitting (each pool's peak added, so at "
                        + "most this); %.0f MB held after the passes, policies, sums and split values kept included.%n",
                m.statesHeld / 1e6, m.peakFit / 1e6, m.heldAfterFit / 1e6);

        out.printf("%n## The last rounds and the scoring%n%n");
        out.println("| Batch | Counts | Estimated bytes | Last round wall s | CPU s a state: trees | walks | splits | Splits "
                + "valued | Scoring wall s |");
        out.println("|---|---|---|---|---|---|---|---|---|");
        for (int b = 0; b < sc.batches.size(); b++) {
            FullIndexFit.RoundStats r = FullIndexPilot.total(sc.rounds.get(b + 1));
            out.printf("| %d | %d | %.0f MB | %.1f | %.4f | %.4f | %.4f | %d | %.1f |%n", b, sc.batches.get(b).size(),
                    sc.batchBytes.get(b) / 1e6, sc.finalWall.get(b), r.treeCpu / r.states, r.walkCpu / r.states,
                    r.splitCpu / r.states, r.splitValued, sc.scoreWall.get(b));
        }
        double scoreCpu = 0;
        for (double c : sc.scoreCpu) {
            scoreCpu += c;
        }
        out.printf("%nScoring: %.1f CPU s over the %d counts, %.2f s a count, %d resamples. Peak heap from the last rounds "
                        + "on %.0f MB. Split values kept at the end: %d, %.1f a state, holding %.0f MB, %.0f bytes each.%n",
                scoreCpu, m.counts, scoreCpu / m.counts, m.resamples, m.peakScore / 1e6, m.cacheEntries,
                (double) m.cacheEntries / m.st.size, m.cacheBytes / 1e6, (double) m.cacheBytes / Math.max(1, m.cacheEntries));
        out.printf("Everything on the wall: %.1f s.%n", m.wall);
    }

    static double cpu(FullIndexFit.RoundStats r) {
        return r.treeCpu + r.walkCpu + r.splitCpu;
    }

    /** One line a round: its strategies and what a state visit cost in it. */
    static void roundTable(PrintStream out, List<FullIndexFit.RoundStats> rounds, int states) {
        out.println("| Round | Strategies | States | Walks a state | worked out | Wall s | CPU s a state: trees | walks | "
                + "splits | Split asks a state | the chart's | already valued | valued | Distinct plays a pair deal | "
                + "most | not the chart's, a state |");
        out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (FullIndexFit.RoundStats r : rounds) {
            double n = Math.max(1, r.states);
            out.printf("| %d | %d | %d | %.1f | %.1f | %.1f | %.4f | %.4f | %.4f | %.1f | %.1f | %.1f | %.2f | %.2f | %d | "
                            + "%.2f |%n", r.round, r.strategies, r.states, r.walks / n, r.walksMade / n, r.wall, r.treeCpu / n,
                    r.walkCpu / n, r.splitCpu / n,
                    r.splitAsks / n, r.splitChart / n, r.splitKept / n, r.splitValued / n,
                    r.pairDeals == 0 ? 0 : (double) r.distinctPlays / r.pairDeals, r.mostPlays, r.distinctOther / n);
        }
    }

    // ------------------------------------------------------------------ the full run

    /**
     * The full run projected from the dry run, for each number of states in SIZES and for the
     * most that fit in the hours given.
     *
     * The passes. Every state is visited in as many rounds as the dry run's passes took, and a
     * state visit costs what it cost there: its trees, its walks and the split values worked out
     * afresh, each summed over the rounds. The groups grow as the square root of the states, so
     * a larger run can take more passes and meet more distinct post-split plays at a state; the
     * projection is also given with every strategy at the cap, its walks and splits a state taken
     * at the dry run's busiest round, which is far more than the dry run did.
     *
     * The last rounds. Each batch builds every state's trees once, and every state's walks and
     * splits are those of the dry run's last rounds, once in all. The split values kept from the
     * passes serve them.
     *
     * The scoring. Its CPU is linear in the states and in the resamples, from the dry run's,
     * and its counts run on the threads at once.
     *
     * Memory. The states, the strategies' per-state arrays and the split values kept grow with
     * the states, and the sums each thread holds for each strategy with the groups. A batch is
     * as many counts as fit in the heap left, at the dry run's bytes a count scaled by the states.
     *
     * Wall time is the CPU of the passes and last rounds over the threads the dry run's passes
     * kept busy, plus the scoring's over the threads, on this machine at the dry run's load.
     */
    static final class Projection {

        static final double HEAP = 10e9;
        static final double FILL = 0.8;

        static void print(PrintStream out, Path data, Measured m, double hours) throws java.io.IOException {
            FullIndexFit.Scored sc = m.scored;
            int fileStates = StateStore.records(data);
            int s = m.st.size;
            out.printf("%n## The full run, projected%n%n");
            out.printf("The file holds %,d states. The dry run fitted %d; the full run's states are the first shoes of the "
                    + "file, a random sample, as the dry run's are.%n", fileStates, s);
            List<FullIndexFit.RoundStats> fit = sc.rounds.get(0);
            double treeR = 0;
            double walkR = 0;
            double splitR = 0;
            double busiest = 0;
            for (FullIndexFit.RoundStats r : fit) {
                treeR += r.treeCpu / s;
                walkR += r.walkCpu / s;
                splitR += r.splitCpu / s;
                busiest = Math.max(busiest, (r.walkCpu + r.splitCpu) / s);
            }
            FullIndexFit.RoundStats ft = FullIndexPilot.total(fit);
            double busy = cpu(ft) / ft.wall;
            double treeState = ft.treeCpu / ft.states;
            double finalWalk = 0;
            double finalSplit = 0;
            double batchBytes = 0;
            for (int b = 0; b < sc.batches.size(); b++) {
                FullIndexFit.RoundStats r = FullIndexPilot.total(sc.rounds.get(b + 1));
                finalWalk += r.walkCpu / s;
                finalSplit += r.splitCpu / s;
                batchBytes += sc.batchBytes.get(b);
            }
            double perCount = batchBytes / m.counts / s;
            double scoreCpu = 0;
            for (double c : sc.scoreCpu) {
                scoreCpu += c;
            }
            double scoreState = scoreCpu / s / m.resamples;
            int cap = FullIndexFit.MAX_PASSES + FullIndexFit.CLEAN_UPS;
            out.printf("From the dry run, CPU seconds a state: the passes' trees %.3f, walks %.3f and splits %.3f over its "
                            + "%d rounds; a last round's trees %.4f a batch; the last rounds' walks %.3f and splits %.3f; "
                            + "scoring %.3g a resample (every count); %.2f threads busy in the passes. A count's batch holds "
                            + "%.0f KB a state.%n", treeR, walkR, splitR, fit.size(), treeState, finalWalk, finalSplit,
                    scoreState, busy, perCount / 1e3);
            double statesBytes = FullIndexPilot.Projection.statesBytes(m.st) / s;
            double cacheBytes = (double) m.cacheBytes / s;
            double arrayBytes = m.arrayBytes / s;
            // Policies, groups and the rest the strategies hold, which grow with the groups.
            double fitBytes = Math.max(0, m.heldAfterFit - m.statesHeld - m.cacheBytes - m.arrayBytes);
            double accBytes = (double) m.threads * m.strategies * m.firstGroups * Deals.COUNT * 16;
            out.printf("Memory: a state's own values %.1f KB, the strategies' arrays for it %.1f KB, the split values kept "
                            + "for it %.1f KB; the policies and the rest the strategies hold %.0f MB, and the threads' sums "
                            + "in a round %.0f MB, at the dry run's %.2f groups a strategy, both growing as the groups do, "
                            + "with the square root of the states. Heap %.0f GB, of which %.0f%% is filled.%n%n",
                    statesBytes / 1e3, arrayBytes / 1e3, cacheBytes / 1e3, fitBytes / 1e6, accBytes / 1e6, m.firstGroups,
                    HEAP / 1e9, 100 * FILL);
            statesBytes += arrayBytes;
            out.println("| States | Resamples | Rounds | Batches | Heap while fitting | CPU hours: passes' trees | walks | "
                    + "splits | last rounds | scoring | Wall hours | Wall hours, every strategy at the cap |");
            out.println("|---|---|---|---|---|---|---|---|---|---|---|---|");
            List<Integer> sizes = new ArrayList<>();
            for (int n : SIZES) {
                sizes.add(n);
            }
            for (int resamples : new int[]{200, 1000}) {
                // The most states, in steps of 500, whose projected wall time fits in the hours given.
                int most = 0;
                for (int n = 500; n <= fileStates; n += 500) {
                    if (wall(n, m, treeR, walkR, splitR, treeState, finalWalk, finalSplit, scoreState, busy, perCount,
                            statesBytes, fitBytes, cacheBytes, accBytes, resamples, false)[0] > hours * 3600) {
                        break;
                    }
                    most = n;
                }
                List<Integer> rows = new ArrayList<>(sizes);
                rows.add(most);
                for (int n : rows) {
                    double[] w = wall(n, m, treeR, walkR, splitR, treeState, finalWalk, finalSplit, scoreState, busy,
                            perCount, statesBytes, fitBytes, cacheBytes, accBytes, resamples, false);
                    double[] c = wall(n, m, treeR * cap / fit.size(), busiest * cap, 0, treeState, finalWalk, finalSplit,
                            scoreState, busy, perCount, statesBytes, fitBytes, cacheBytes, accBytes, resamples, true);
                    out.printf("| %,d%s | %d | %d | %.0f | %.1f GB | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f |%n", n,
                            n == most ? " (most in " + hours + " h)" : "", resamples, fit.size(), w[1], w[2] / 1e9, w[3], w[4],
                            w[5], w[6], w[7], w[0] / 3600, c[0] / 3600);
                }
            }
        }

        /**
         * {wall seconds, batches, heap while fitting, CPU hours of the passes' trees, walks and
         * splits, of the last rounds and of the scoring} at n states.
         */
        static double[] wall(int n, Measured m, double treeR, double walkR, double splitR, double treeState,
                             double finalWalk, double finalSplit, double scoreState, double busy, double perCount,
                             double statesBytes, double fitBytes, double cacheBytes, double accBytes, int resamples,
                             boolean capped) {
            double grow = Math.sqrt((double) n / m.st.size);
            double held = n * (statesBytes + cacheBytes) + fitBytes * grow;
            double fitHeap = held + accBytes * grow;
            double room = FILL * HEAP - held;
            double batches = Math.max(1, Math.ceil(m.counts * perCount * n / Math.max(1, room)));
            double trees = n * treeR / 3600;
            double walks = n * walkR / 3600;
            double splits = n * splitR / 3600;
            double last = n * (batches * treeState + finalWalk + finalSplit) / 3600;
            double scoring = n * scoreState * resamples / 3600;
            double wall = (trees + walks + splits + last) * 3600 / busy + scoring * 3600 / m.threads;
            return new double[]{wall, batches, fitHeap, trees, walks, splits, last, scoring};
        }
    }
}
