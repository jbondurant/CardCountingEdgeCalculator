import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The dry run of the full-index run (FullIndexRun), on the first shoes of the run's state file,
 * read and never written, before the whole file is run (FULL_INDEX_PLAYS.md section 6).
 *
 * It runs the whole thing as FullIndexRun will, at a smaller size: every count (the null count
 * and the catalog's entries), at every penetration, both folds and the learning curve's half
 * fits, fitted together by FullIndexFit.JointRun, every state's trees built once into a tree
 * store and read back after, a checkpoint written after every round and every item scored;
 * then the scoring items, one to a batch, as the whole file's batches hold one to three. Each
 * round's figures are printed: the states visited and the walks, the CPU seconds a state for
 * the trees, the walks and the split values, and the distinct post-split plays per (state,
 * pair deal) over every strategy of the round, with those not the chart's, which is what a
 * cache kept for one state's visit would value. Then the rounds every strategy took, the heap's
 * peak, the split values kept, the tree store and the checkpoints, and the last rounds and the
 * scoring.
 *
 * From these it projects the whole file's run, at 200 and 1,000 resamples (Projection).
 *
 * Its trees and checkpoints go to a directory of their own under the work directory given.
 * java -Xmx10g -cp CLASSES:lib/mongo-java-driver-3.12.14.jar FullIndexDryRun [state file] [shoes]
 * [resamples] [threads] [work directory]
 */
public final class FullIndexDryRun {

    static final int[] PENS = FullIndexRun.PENS;

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
        /** The tree store: trees built and read back, and the bytes of its file. */
        long treesBuilt;
        long treesRead;
        long treeFileBytes;
        /** The last fit checkpoint's bytes, and the seconds spent forcing trees to disk before checkpoints. */
        long checkpointBytes;
        double syncSeconds;
        /** CPU seconds of the whole process over the run, every thread and the collector included. */
        double processCpu;
        double wall;
    }

    public static void main(String[] args) throws Exception {
        Path data = Paths.get(args.length > 0 ? args[0] : "runs/montreal-80.states");
        int shoes = args.length > 1 ? Integer.parseInt(args[1]) : 2000;
        int resamples = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : 6;
        Path work = Paths.get(args.length > 4 ? args[4] : "runs/full-index/dry-run");
        PrintStream out = System.out;
        long t0 = System.nanoTime();
        ScoringStates st = FullIndexPilot.load(data, shoes);
        out.printf("# Full-index dry run%n%n");
        out.printf("FullIndexDryRun: the first %d shoes of %s, %d valued states, read and never written; M_play %.2f; "
                        + "%d resamples of seed %d; %d threads of %d processors; max heap %.0f MB; java %s; trees and "
                        + "checkpoints in %s.%n", shoes, data, st.size, SystemScorer.M_PLAY, resamples, Bootstrap.SEED,
                threads, Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() / 1e6,
                System.getProperty("java.vm.version"), work);
        out.printf("Started %s, load average %.2f; states read in %.1f s.%n", java.time.LocalDateTime.now(),
                FullIndexTimingReport.loadAverage(), (System.nanoTime() - t0) / 1e9);
        out.flush();
        Measured m = run(st, resamples, threads, work, out);
        print(out, m);
        Projection.print(out, data, m);
        out.printf("%nFinished %s, load average %.2f.%n", java.time.LocalDateTime.now(), FullIndexTimingReport.loadAverage());
    }

    /**
     * Runs the joint fit and scoring on the states given as FullIndexRun runs the whole file:
     * every count at every penetration, trees kept in a TreeStore and checkpoints written, in a
     * fresh directory under work; and one scoring item to a batch, as the whole file's batches
     * hold one to three. Measures it.
     */
    static Measured run(ScoringStates st, int resamples, int threads, Path work, PrintStream log) throws Exception {
        Measured m = new Measured();
        m.st = st;
        m.threads = threads;
        m.resamples = resamples;
        m.mPlay = SystemScorer.M_PLAY;
        SystemScorer scorer = new SystemScorer(st, new Bootstrap(st.shoes, resamples, Bootstrap.SEED), m.mPlay);
        List<CountKeys.Count> counts = new ArrayList<>();
        counts.add(CountKeys.nullCount());
        counts.addAll(CountKeys.catalog());
        m.counts = counts.size();
        m.statesHeld = FullIndexPilot.usedAfterGc();
        Path dir = work.resolve("run-" + System.currentTimeMillis());
        java.nio.file.Files.createDirectories(dir);
        TreeStore store = new TreeStore(st, dir.resolve("trees.bin"), "dry run " + st.size, FullIndexRun.DISK_MARGIN);
        FullIndexCheckpoint ck = new FullIndexCheckpoint(dir.resolve("checkpoints"), "dry run " + st.size);
        FullIndexTimingReport.resetPeaks();
        FullIndexFit[] fitOf = new FullIndexFit[1];
        FullIndexFit.JointRun run = new FullIndexFit.JointRun(scorer, counts, PENS, threads);
        run.trees = store;
        run.batchBytes = 1;
        run.checkpoint = ck;
        run.log = System.err;
        run.beforeCheckpoint = () -> {
            long c0 = System.nanoTime();
            store.sync();
            m.syncSeconds += (System.nanoTime() - c0) / 1e9;
        };
        run.afterRound = r -> {
            try {
                m.checkpointBytes = java.nio.file.Files.size(ck.dir.resolve("fit.ckpt"));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        run.watch = new FullIndexFit.Watch() {
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
                for (SplitValues.Packed kept : fit.splitCache) {
                    if (kept != null) {
                        m.cacheEntries += kept.entries();
                        m.cacheBytes += kept.bytes();
                    }
                }
                log.printf("%nFitted %d strategies in %d rounds; heap peak %.0f MB while fitting, %.0f MB held after.%n",
                        m.strategies, fit.rounds.size(), m.peakFit / 1e6, m.heldAfterFit / 1e6);
                log.flush();
                FullIndexTimingReport.resetPeaks();
            }
        };
        long w0 = System.nanoTime();
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long cpu0 = os.getProcessCpuTime();
        m.scored = run.run();
        m.wall = (System.nanoTime() - w0) / 1e9;
        m.processCpu = (os.getProcessCpuTime() - cpu0) / 1e9;
        m.peakScore = FullIndexTimingReport.peakHeap();
        store.close();
        m.treesBuilt = store.built();
        m.treesRead = store.read();
        m.treeFileBytes = java.nio.file.Files.size(dir.resolve("trees.bin"));
        return m;
    }

    static void print(PrintStream out, Measured m) {
        FullIndexFit.Scored sc = m.scored;
        out.printf("%n## The joint passes%n%n");
        out.printf("%d counts, %d strategies (each count at %d penetrations, each fold and each half fit), fitted together "
                + "in %.1f s on the wall. Split values kept per state from round to round; trees built in the first round "
                + "and read back from the tree store after.%n%n", m.counts, m.strategies, PENS.length, sc.fitWall);
        roundTable(out, sc.rounds.get(0));
        FullIndexFit.RoundStats ft = FullIndexPilot.total(sc.rounds.get(0));
        out.printf("%nIn all: %d state visits, %d walks, %d of them worked out; CPU a state visit: trees %.4f s, walks %.4f s "
                        + "(%.3f ms a strategy's walk, its reach and sums included), splits %.4f s (%.1f ms a split valued); "
                        + "%.2f threads busy of %d.%n", ft.states, ft.walks, ft.walksMade, ft.treeCpu / ft.states,
                ft.walkCpu / ft.states, 1000 * ft.walkCpu / Math.max(1, ft.walks), ft.splitCpu / ft.states,
                1000 * ft.splitCpu / Math.max(1, ft.splitValued), cpu(ft) / ft.wall, m.threads);
        out.printf("Rounds a strategy fitted (passes and clean-ups): %s; %d of %d capped. On average %.2f first-decision "
                + "groups and %.2f later groups a strategy.%n", FullIndexPilot.histogram(m.strategyRounds), m.capped,
                m.strategies, m.firstGroups, m.laterGroups);
        out.printf("Heap: %.0f MB held with the states alone; peak %.0f MB while fitting (each pool's peak added, so at "
                        + "most this); %.0f MB held after the passes, policies, sums and split values kept included. Split "
                        + "values kept after the passes: %d, %.1f a state, %.1f KB a state packed.%n", m.statesHeld / 1e6,
                m.peakFit / 1e6, m.heldAfterFit / 1e6, m.cacheEntries, (double) m.cacheEntries / m.st.size,
                (double) m.cacheBytes / m.st.size / 1e3);
        out.printf("Tree store: %d trees built, %d read back, %.0f MB on disk (%.0f KB a state); the last fit checkpoint "
                + "%.1f MB; %.1f s forcing trees to disk before checkpoints.%n", m.treesBuilt, m.treesRead,
                m.treeFileBytes / 1e6, m.treeFileBytes / 1e3 / m.st.size, m.checkpointBytes / 1e6, m.syncSeconds);

        out.printf("%n## The last rounds and the scoring%n%n");
        FullIndexFit.RoundStats lt = new FullIndexFit.RoundStats();
        double finalWall = 0;
        double scoreWall = 0;
        double most = 0;
        double bytes = 0;
        for (int b = 0; b < sc.batches.size(); b++) {
            for (FullIndexFit.RoundStats r : sc.rounds.get(b + 1)) {
                lt.add(r);
            }
            finalWall += sc.finalWall.get(b);
            scoreWall += sc.scoreWall.get(b);
            most = Math.max(most, sc.batchBytes.get(b));
            bytes += sc.batchBytes.get(b);
        }
        out.printf("%d batches of one scoring item each (a count's fold fits or half fits at one penetration), as the "
                        + "whole file's batches hold one to three: last rounds %.1f s and scoring %.1f s on the wall. The last "
                        + "rounds' CPU a state, over every batch: trees read %.4f s, walks %.4f s, splits %.4f s (%d valued). "
                        + "An item holds %.1f KB a state on average and %.1f KB at most.%n", sc.batches.size(), finalWall,
                scoreWall, lt.treeCpu / m.st.size, lt.walkCpu / m.st.size, lt.splitCpu / m.st.size, lt.splitValued,
                bytes / sc.batches.size() / m.st.size / 1e3, most / m.st.size / 1e3);
        out.printf("Peak heap from the last rounds on %.0f MB. Everything on the wall: %.1f s, %.1f s of the process's "
                + "CPU.%n", m.peakScore / 1e6, m.wall, m.processCpu);
    }

    static double cpu(FullIndexFit.RoundStats r) {
        return r.treeCpu + r.walkCpu + r.splitCpu;
    }

    /** One line a round: its strategies and what a state visit cost in it. */
    static void roundTable(PrintStream out, List<FullIndexFit.RoundStats> rounds) {
        out.println("| Round | Strategies | States | Walks a state | worked out | Wall s | CPU s a state: trees | walks | "
                + "splits | Split asks a state | the chart's | already valued | valued | Distinct plays a pair deal | "
                + "most | not the chart's, a state |");
        out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (FullIndexFit.RoundStats r : rounds) {
            double n = Math.max(1, r.states);
            out.printf("| %d | %d | %d | %.1f | %.1f | %.1f | %.4f | %.4f | %.4f | %.1f | %.1f | %.1f | %.2f | %.2f | %d | "
                            + "%.2f |%n", r.round, r.strategies, r.states, r.walks / n, r.walksMade / n, r.wall, r.treeCpu / n,
                    r.walkCpu / n, r.splitCpu / n, r.splitAsks / n, r.splitChart / n, r.splitKept / n, r.splitValued / n,
                    r.pairDeals == 0 ? 0 : (double) r.distinctPlays / r.pairDeals, r.mostPlays, r.distinctOther / n);
        }
    }

    // ------------------------------------------------------------------ the full run

    /**
     * The whole file's run projected from the dry run (FULL_INDEX_PLAYS.md section 6), at 200
     * and at 1,000 resamples, with what each figure assumes.
     *
     * Time, from the dry run's wall seconds a state, which take in the machine's other load and
     * the threads' contention as they were:
     * - the passes: their wall split by CPU into trees (built in the first round, read back
     *   after), walks and split values, each times the states. The groups grow as the square root
     *   of the states, so a larger run meets more distinct post-split plays a state: the split
     *   values are also taken growing as the square root of the groups and as the groups, which
     *   brackets the growth from the 300-shoe run to the 2,000-shoe one (FULL_INDEX_PLAYS.md
     *   section 6). They are also taken with every strategy at the cap, six passes and two
     *   clean-ups at the walks and splits of the busiest round, far more than any run took;
     * - the last rounds: their wall a state times the states. The dry run gives each item a
     *   batch, as the whole file's batches hold one to three, so the trees are read as often;
     * - the scoring: its wall a state and a resample times the states and the resamples, each
     *   item's resamples shared among the threads as the full run shares them.
     *
     * Memory: the states, the strategies' arrays and the split values kept grow with the states;
     * the policies and the threads' sums with the groups; the tree store's file and the items
     * with the states. A batch holds as many items as fit in 80% of the heap less what the run
     * holds once fitted.
     */
    static final class Projection {

        static final double HEAP = 10.0 * (1L << 30);
        static final double FILL = 0.8;

        static void print(PrintStream out, Path data, Measured m) throws java.io.IOException {
            FullIndexFit.Scored sc = m.scored;
            int n = StateStore.records(data);
            int s = m.st.size;
            double scale = (double) n / s;
            double grow = Math.sqrt(scale);
            List<FullIndexFit.RoundStats> fit = sc.rounds.get(0);
            FullIndexFit.RoundStats ft = FullIndexPilot.total(fit);
            double cpu = cpu(ft);
            double passWall = sc.fitWall;
            double treeShare = ft.treeCpu / cpu;
            double walkShare = ft.walkCpu / cpu;
            double splitShare = ft.splitCpu / cpu;
            // The busiest round's walks and split values, in wall seconds.
            double busyWalk = 0;
            double busySplit = 0;
            for (FullIndexFit.RoundStats r : fit) {
                if (r.walkCpu + r.splitCpu > (busyWalk + busySplit) / passWall * cpu) {
                    busyWalk = r.walkCpu / cpu * passWall;
                    busySplit = r.splitCpu / cpu * passWall;
                }
            }
            int cap = FullIndexFit.MAX_PASSES + FullIndexFit.CLEAN_UPS;
            double finalWall = 0;
            double scoreWall = 0;
            double itemBytes = 0;
            double itemMost = 0;
            for (int b = 0; b < sc.batches.size(); b++) {
                finalWall += sc.finalWall.get(b);
                scoreWall += sc.scoreWall.get(b);
                itemBytes += sc.batchBytes.get(b);
                itemMost = Math.max(itemMost, sc.batchBytes.get(b));
            }
            int items = sc.batches.size();
            out.printf("%n## The whole file, projected%n%n");
            out.printf("The file holds %,d valued states, %.1f times the dry run's %,d. From the dry run, wall seconds a "
                            + "state: the passes %.3f (trees %.0f%%, walks %.0f%%, split values %.0f%% of their CPU) over %d "
                            + "rounds; the last rounds %.3f; the scoring %.3g a resample.%n", n, scale, s, passWall / s,
                    100 * treeShare, 100 * walkShare, 100 * splitShare, fit.size(), finalWall / s, scoreWall / s / m.resamples);

            double states = FullIndexPilot.Projection.statesBytes(m.st) * scale;
            double arrays = m.arrayBytes * scale;
            double cache = m.cacheBytes * scale;
            double fixed = Math.max(0, m.heldAfterFit - m.statesHeld - m.cacheBytes - m.arrayBytes) * grow;
            double sums = (double) m.threads * m.strategies * m.firstGroups * grow * Deals.COUNT * 16;
            double held = states + arrays + cache + fixed;
            double room = FILL * HEAP - held;
            double itemsBytes = itemBytes * scale;
            double perBatch = Math.max(1, Math.floor(room / (itemsBytes / items)));
            out.printf("Memory at %,d states: the states %.2f GB, the strategies' arrays %.2f GB, the split values kept "
                            + "%.2f GB (as many a state as the dry run kept; more if the plays grow with the groups), the "
                            + "policies and the rest %.2f GB, and the threads' sums in a round %.2f GB: %.2f GB held while "
                            + "fitting and %.2f GB after. A batch may then hold %.2f GB: an item holds %.2f GB on average "
                            + "and %.2f GB at most, so about %.0f items a batch of the %d. The tree store takes %.1f GB of "
                            + "disk, a fit checkpoint about %.0f MB.%n%n", n, states / 1e9, arrays / 1e9, cache / 1e9,
                    fixed / 1e9, sums / 1e9, (held + sums) / 1e9, held / 1e9, room / 1e9, itemsBytes / items / 1e9,
                    itemMost * scale / 1e9, perBatch, items, TreeStore.bytes(n) / 1e9, m.checkpointBytes * grow / 1e6);
            if (itemMost * scale > room) {
                out.printf("The largest item does not fit beside what the run holds: it would need %.2f GB of %.2f.%n%n",
                        itemMost * scale / 1e9, room / 1e9);
            }

            out.println("| Resamples | Split values a state | Rounds | Passes h | Last rounds h | Scoring h | Total h |");
            out.println("|---|---|---|---|---|---|---|");
            String[] growth = {"as in the dry run", "growing as the square root of the groups", "growing as the groups"};
            double[] factor = {1, Math.sqrt(grow), grow};
            for (int resamples : new int[]{200, 1000}) {
                double last = finalWall * scale / 3600;
                double scoring = scoreWall / m.resamples * resamples * scale / 3600;
                for (int g = 0; g < factor.length; g++) {
                    double passes = passWall * scale * (treeShare + walkShare + splitShare * factor[g]) / 3600;
                    out.printf("| %d | %s | %d | %.1f | %.1f | %.1f | %.1f |%n", resamples, growth[g], fit.size(), passes,
                            last, scoring, passes + last + scoring);
                }
                double passes = (passWall * treeShare + cap * (busyWalk + busySplit * grow)) * scale / 3600;
                out.printf("| %d | at the busiest round's, growing as the groups | %d, the cap | %.1f | %.1f | %.1f | %.1f |%n",
                        resamples, cap, passes, last, scoring, passes + last + scoring);
            }
        }
    }
}
