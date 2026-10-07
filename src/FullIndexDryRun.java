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
 * --counts FILE runs the counts a candidates file or custom-counts.json lists in place of the
 * null count and the catalog, and --pens 2 (or 0,1,2) those penetrations alone, as FullIndexRun
 * takes them; on the main state file only the registration is taken, and the look is written to
 * the ledger (CustomCounts.lookOnMain).
 * java -Xmx10g -cp CLASSES:lib/mongo-java-driver-3.12.14.jar FullIndexDryRun [--counts FILE]
 * [--pens P,P] [state file] [shoes] [resamples] [threads] [work directory]
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
        /** The penetrations run. */
        int[] pens = PENS;
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
        /** The bytes of every scoring item's checkpointed Result. */
        long partBytes;
        double syncSeconds;
        /** CPU seconds of the whole process over the run, every thread and the collector included. */
        double processCpu;
        double wall;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("project")) {
            // project STATE_FILE SUMMARY...: the whole file projected from several dry runs.
            List<Path> runs = new ArrayList<>();
            for (int a = 2; a < args.length; a++) {
                runs.add(Paths.get(args[a]));
            }
            Growth.print(System.out, StateStore.records(Paths.get(args[1])), runs);
            return;
        }
        Path countsFile = null;
        int[] pens = PENS;
        List<String> rest = new ArrayList<>();
        for (int a = 0; a < args.length; a++) {
            if (args[a].equals("--counts")) {
                countsFile = Paths.get(args[++a]);
            } else if (args[a].equals("--pens")) {
                pens = FullIndexRun.pens(args[++a]);
            } else {
                rest.add(args[a]);
            }
        }
        args = rest.toArray(new String[0]);
        Path data = Paths.get(args.length > 0 ? args[0] : "runs/montreal-80.states");
        int shoes = args.length > 1 ? Integer.parseInt(args[1]) : 2000;
        int resamples = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : 6;
        Path work = Paths.get(args.length > 4 ? args[4] : "runs/full-index/dry-run");
        PrintStream out = System.out;
        long t0 = System.nanoTime();
        List<CountKeys.Count> counts = null;
        if (countsFile != null) {
            CustomCounts.Listing listing = CustomCounts.read(countsFile);
            if (CustomCounts.isMain(data)) {
                String blob = CustomCounts.lookOnMain(listing, FullIndexRun.commit(), "FullIndexDryRun on the first "
                        + shoes + " shoes, " + resamples + " resamples, penetrations " + java.util.Arrays.toString(pens));
                out.printf("Counts from %s, git blob %s.%n", countsFile, blob);
            }
            counts = listing.counts();
        }
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
        Measured m = run(st, resamples, threads, work, out, counts, pens);
        print(out, m);
        Projection.print(out, data, m);
        Path summary = work.resolve("summary-" + st.size + ".txt");
        java.nio.file.Files.write(summary, summary(m).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.printf("%nIts figures for FullIndexDryRun project: %s%n", summary);
        out.printf("%nFinished %s, load average %.2f.%n", java.time.LocalDateTime.now(), FullIndexTimingReport.loadAverage());
    }

    /**
     * Runs the joint fit and scoring on the states given as FullIndexRun runs the whole file:
     * every count at every penetration, trees kept in a TreeStore and checkpoints written, in a
     * fresh directory under work; and one scoring item to a batch, as the whole file's batches
     * hold one to three. Measures it.
     */
    static Measured run(ScoringStates st, int resamples, int threads, Path work, PrintStream log) throws Exception {
        return run(st, resamples, threads, work, log, null, PENS);
    }

    /** The same with the counts given (the null count and the catalog when null), at the penetrations given. */
    static Measured run(ScoringStates st, int resamples, int threads, Path work, PrintStream log,
                        List<CountKeys.Count> given, int[] pens) throws Exception {
        Measured m = new Measured();
        m.st = st;
        m.threads = threads;
        m.resamples = resamples;
        m.mPlay = SystemScorer.M_PLAY;
        m.pens = pens.clone();
        SystemScorer scorer = new SystemScorer(st, new Bootstrap(st.shoes, resamples, Bootstrap.SEED), m.mPlay);
        scorer.ownValueRows = given != null;
        List<CountKeys.Count> counts = new ArrayList<>();
        if (given == null) {
            counts.add(CountKeys.nullCount());
            counts.addAll(CountKeys.catalog());
        } else {
            counts.addAll(given);
        }
        m.counts = counts.size();
        m.statesHeld = FullIndexPilot.usedAfterGc();
        Path dir = work.resolve("run-" + System.currentTimeMillis());
        java.nio.file.Files.createDirectories(dir);
        TreeStore store = new TreeStore(st, dir.resolve("trees.bin"), "dry run " + st.size, FullIndexRun.DISK_MARGIN);
        FullIndexCheckpoint ck = new FullIndexCheckpoint(dir.resolve("checkpoints"), "dry run " + st.size);
        FullIndexTimingReport.resetPeaks();
        FullIndexFit[] fitOf = new FullIndexFit[1];
        FullIndexFit.JointRun run = new FullIndexFit.JointRun(scorer, counts, pens, threads);
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
                    if (f == null) {
                        continue;
                    }
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
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(ck.dir)) {
            m.partBytes = files.filter(f -> f.getFileName().toString().startsWith("part-")).mapToLong(f -> {
                try {
                    return java.nio.file.Files.size(f);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).sum();
        }
        // The dry run's own trees are only for timing it; they would take a gigabyte or more of disk.
        java.nio.file.Files.delete(dir.resolve("trees.bin"));
        return m;
    }

    static void print(PrintStream out, Measured m) {
        FullIndexFit.Scored sc = m.scored;
        out.printf("%n## The joint passes%n%n");
        out.printf("%d counts, %d strategies (each count at %d penetrations, each fold and each half fit), fitted together "
                + "in %.1f s on the wall. Split values kept per state from round to round; trees built in the first round "
                + "and read back from the tree store after.%n%n", m.counts, m.strategies, m.pens.length, sc.fitWall);
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

    /**
     * The dry run's figures a line each, "name value", for FullIndexDryRun project: wall seconds
     * and CPU seconds by kind over the passes, the last rounds and the scoring, bytes held, and
     * every scoring item's bytes.
     */
    static String summary(Measured m) {
        FullIndexFit.Scored sc = m.scored;
        FullIndexFit.RoundStats ft = FullIndexPilot.total(sc.rounds.get(0));
        double finalWall = 0;
        double scoreWall = 0;
        FullIndexFit.RoundStats lt = new FullIndexFit.RoundStats();
        StringBuilder items = new StringBuilder();
        for (int b = 0; b < sc.batches.size(); b++) {
            finalWall += sc.finalWall.get(b);
            scoreWall += sc.scoreWall.get(b);
            for (FullIndexFit.RoundStats r : sc.rounds.get(b + 1)) {
                lt.add(r);
            }
            items.append(b == 0 ? "" : ",").append(Math.round(sc.batchBytes.get(b)));
        }
        StringBuilder b = new StringBuilder();
        java.util.function.BiConsumer<String, Object> line = (k, v) -> b.append(k).append(' ').append(v).append('\n');
        line.accept("states", m.st.size);
        line.accept("shoes", m.st.shoes);
        line.accept("threads", m.threads);
        line.accept("resamples", m.resamples);
        line.accept("strategies", m.strategies);
        line.accept("rounds", sc.rounds.get(0).size());
        line.accept("capped", m.capped);
        line.accept("firstGroups", m.firstGroups);
        line.accept("laterGroups", m.laterGroups);
        line.accept("passWall", sc.fitWall);
        line.accept("passTreeCpu", ft.treeCpu);
        line.accept("passWalkCpu", ft.walkCpu);
        line.accept("passSplitCpu", ft.splitCpu);
        line.accept("passSplitsValued", ft.splitValued);
        line.accept("finalWall", finalWall);
        line.accept("finalTreeCpu", lt.treeCpu);
        line.accept("finalWalkCpu", lt.walkCpu);
        line.accept("finalSplitCpu", lt.splitCpu);
        line.accept("scoreWall", scoreWall);
        line.accept("statesModelBytes", FullIndexPilot.Projection.statesBytes(m.st));
        line.accept("statesHeld", m.statesHeld);
        line.accept("arrayBytes", m.arrayBytes);
        line.accept("heldAfterFit", m.heldAfterFit);
        line.accept("cacheEntries", m.cacheEntries);
        line.accept("cacheBytes", m.cacheBytes);
        line.accept("peakFit", m.peakFit);
        line.accept("peakScore", m.peakScore);
        line.accept("treeFileBytes", m.treeFileBytes);
        line.accept("checkpointBytes", m.checkpointBytes);
        line.accept("partBytes", m.partBytes);
        line.accept("wall", m.wall);
        line.accept("processCpu", m.processCpu);
        line.accept("items", items);
        return b.toString();
    }

    /**
     * The whole file projected from several dry runs (FULL_INDEX_PLAYS.md section 6), each of
     * its figures carried to the file's states by the growth the runs show.
     *
     * Each figure a state (or, for the plays and sums the strategies hold, in all) is fitted as
     * a x n^b by least squares on the logarithms over the runs, and the fit printed beside what
     * each run measured. The passes' wall time is split by CPU into trees, walks and split values,
     * each fitted on its own, since the split values grow with the play groups and the rest do
     * not. The last rounds and the scoring are taken from their wall time, which charges them as
     * the code runs them: a dry run gives each scoring item a batch of its own, as the file's
     * batches hold one to three, and shares each item's resamples among the threads.
     *
     * Memory: the states and the strategies' arrays a state as measured; the split values kept a
     * state and the plays the strategies hold by their fits. The batches are formed as JointRun
     * forms them, greedily in order from each item's bytes, the largest run's items carried to
     * the file's states, within 80% of a 10 GiB heap less what the run holds once fitted; the
     * largest item must fit. On disk: the tree store, the fit checkpoint (growing with the plays,
     * taken as the square root of the states) and the items' checkpoints (with the states).
     */
    static final class Growth {

        static void print(PrintStream out, int n, List<Path> files) throws java.io.IOException {
            List<java.util.Map<String, String>> runs = new ArrayList<>();
            for (Path f : files) {
                java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
                for (String l : java.nio.file.Files.readAllLines(f)) {
                    int sp = l.indexOf(' ');
                    m.put(l.substring(0, sp), l.substring(sp + 1));
                }
                runs.add(m);
            }
            runs.sort(java.util.Comparator.comparingDouble(r -> d(r, "states")));
            int k = runs.size();
            double[] states = new double[k];
            for (int i = 0; i < k; i++) {
                states[i] = d(runs.get(i), "states");
            }
            out.printf("# The whole file projected from %d dry runs%n%n", k);
            out.printf("The file holds %,d valued states; the dry runs %s. Each figure is fitted as a x n^b on the "
                    + "logarithms over the runs.%n%n", n, java.util.Arrays.toString(states));
            out.println("| Figure | " + String.join(" | ", label(states)) + " | b | at " + String.format("%,d", n) + " |");
            StringBuilder sep = new StringBuilder("|---|");
            for (int i = 0; i < k + 2; i++) {
                sep.append("---|");
            }
            out.println(sep);
            // Wall seconds a state of the passes, by kind, from the CPU shares.
            double[] trees = new double[k];
            double[] walks = new double[k];
            double[] splits = new double[k];
            double[] last = new double[k];
            double[] score = new double[k];
            double[] cache = new double[k];
            double[] plays = new double[k];
            double[] valued = new double[k];
            double[] rounds = new double[k];
            double[] groups = new double[k];
            double[] parts = new double[k];
            for (int i = 0; i < k; i++) {
                java.util.Map<String, String> r = runs.get(i);
                double cpu = d(r, "passTreeCpu") + d(r, "passWalkCpu") + d(r, "passSplitCpu");
                double wall = d(r, "passWall");
                trees[i] = wall * d(r, "passTreeCpu") / cpu / states[i];
                walks[i] = wall * d(r, "passWalkCpu") / cpu / states[i];
                splits[i] = wall * d(r, "passSplitCpu") / cpu / states[i];
                last[i] = d(r, "finalWall") / states[i];
                score[i] = d(r, "scoreWall") / states[i] / d(r, "resamples");
                cache[i] = d(r, "cacheBytes") / states[i];
                plays[i] = Math.max(1, d(r, "heldAfterFit") - d(r, "statesHeld") - d(r, "cacheBytes") - d(r, "arrayBytes"));
                valued[i] = d(r, "passSplitsValued") / states[i];
                rounds[i] = d(r, "rounds");
                groups[i] = d(r, "firstGroups");
                parts[i] = d(r, "partBytes") / 1e6;
            }
            double[][] fit = {
                    row(out, "first-decision groups a strategy", states, groups, n, "%.2f"),
                    row(out, "rounds of the passes", states, rounds, n, "%.1f"),
                    row(out, "splits valued a state in the passes", states, valued, n, "%.0f"),
                    row(out, "passes: trees, wall s a state", states, trees, n, "%.4f"),
                    row(out, "passes: walks, wall s a state", states, walks, n, "%.4f"),
                    row(out, "passes: split values, wall s a state", states, splits, n, "%.4f"),
                    row(out, "last rounds, wall s a state", states, last, n, "%.4f"),
                    row(out, "scoring, wall s a state and resample", states, score, n, "%.3g"),
                    row(out, "split values kept, bytes a state", states, cache, n, "%.0f"),
                    row(out, "plays and the rest held after the passes, MB", states, mb(plays), n, "%.0f"),
                    row(out, "items' checkpoints at 200 resamples, MB", states, parts, n, "%.0f")};
            double passes = (fit[3][1] + fit[4][1] + fit[5][1]) * n / 3600;
            double lastRounds = fit[6][1] * n / 3600;

            java.util.Map<String, String> big = runs.get(k - 1);
            double scale = n / states[k - 1];
            double heap = 0.8 * 10.0 * (1L << 30);
            double held = n * (d(big, "statesModelBytes") / states[k - 1] + d(big, "arrayBytes") / states[k - 1]
                    + fit[8][1]) + fit[9][1] * 1e6;
            double room = heap - held;
            String[] itemText = big.get("items").split(",");
            double most = 0;
            int batches = 0;
            double used = 0;
            for (String t : itemText) {
                double b = Double.parseDouble(t) * scale;
                most = Math.max(most, b);
                if (batches == 0 || used + b > room) {
                    batches++;
                    used = 0;
                }
                used += b;
            }
            out.printf("%nMemory at %,d states: %.2f GB held once fitted (the states and strategies' arrays as measured, "
                            + "the split values kept and the plays by their fits), leaving %.2f GB of 80%% of a 10 GiB heap "
                            + "for a batch. The largest item holds %.2f GB%s; the %d items go in %d batches. On disk: the "
                            + "tree store %.1f GB; the fit checkpoint about %.2f GB (growing as the square root of the "
                            + "states); the items' checkpoints about %.2f GB at 200 resamples and at most five times that "
                            + "at 1,000, their resamples being most of them.%n%n", n, held / 1e9, room / 1e9,
                    most / 1e9, most > room ? ", MORE THAN FITS" : ", and fits", itemText.length, batches,
                    TreeStore.bytes(n) / 1e9, d(big, "checkpointBytes") * Math.sqrt(scale) / 1e9, fit[10][1] / 1e3);
            out.println("| Resamples | Passes h | Last rounds h | Scoring h | Total h |");
            out.println("|---|---|---|---|---|");
            for (int resamples : new int[]{200, 1000}) {
                double scoring = fit[7][1] * n * resamples / 3600;
                out.printf("| %d | %.1f | %.1f | %.1f | %.1f |%n", resamples, passes, lastRounds, scoring,
                        passes + lastRounds + scoring);
            }
        }

        private static double d(java.util.Map<String, String> r, String key) {
            String v = r.get(key);
            if (v == null) {
                throw new IllegalArgumentException("a dry run's figures lack " + key);
            }
            return Double.parseDouble(v);
        }

        private static String[] label(double[] states) {
            String[] out = new String[states.length];
            for (int i = 0; i < states.length; i++) {
                out[i] = String.format("%,.0f states", states[i]);
            }
            return out;
        }

        private static double[] mb(double[] bytes) {
            double[] out = new double[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                out[i] = bytes[i] / 1e6;
            }
            return out;
        }

        /** Prints a figure's values, its fitted exponent and its value at n; returns {b, value at n}. */
        static double[] row(PrintStream out, String name, double[] x, double[] y, int n, String format) {
            double[] ab = powerFit(x, y);
            double at = ab[0] * Math.pow(n, ab[1]);
            StringBuilder b = new StringBuilder("| " + name + " | ");
            for (double v : y) {
                b.append(String.format(format, v)).append(" | ");
            }
            b.append(String.format("%.2f", ab[1])).append(" | ").append(String.format(format, at)).append(" |");
            out.println(b);
            return new double[]{ab[1], at};
        }

        /** {a, b} of y = a x^b by least squares on the logarithms; with one point, b = 0. */
        static double[] powerFit(double[] x, double[] y) {
            int k = x.length;
            double sx = 0;
            double sy = 0;
            double sxx = 0;
            double sxy = 0;
            for (int i = 0; i < k; i++) {
                double lx = Math.log(x[i]);
                double ly = Math.log(Math.max(y[i], 1e-300));
                sx += lx;
                sy += ly;
                sxx += lx * lx;
                sxy += lx * ly;
            }
            double den = k * sxx - sx * sx;
            double b = k < 2 || den == 0 ? 0 : (k * sxy - sx * sy) / den;
            double a = Math.exp((sy - b * sx) / k);
            return new double[]{a, b};
        }
    }
}
