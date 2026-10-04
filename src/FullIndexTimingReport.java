import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How long the player tree's parts take on the run's stored states, the figures FULL_INDEX_PLAYS.md
 * section 6 sizes the full-index fit from: per state, building the trees; per count and state,
 * setting up the count's tree and walking it once under a count-based play (values backward,
 * reach forward, the gaps handed to a sink); and per call, valuing a split under a post-split
 * play that is not the chart's. Each is timed single-threaded and then with whole states on
 * each of several threads, as the fit will run, and the heap's peak is shown for each pass.
 *
 * The states are the first of the file, read and never written. The counts timed are Hi-Lo,
 * which splits no rank, and KISS I, which splits two and has the largest tree. The play stands
 * at hard 12 to 16 and soft 13 to 18 from a random threshold near the state's keys and plays the
 * chart elsewhere; the first moves are the chart's. The split play is the chart's but for a later
 * table that stands on hard 16 and hits soft 18 against every up-card, so every split that can
 * reach either is valued afresh; the rest come from the stored values and are counted apart.
 * Splits are valued on every k-th state (5 by default), which gives thousands of calls.
 *
 * It does not count the distinct post-split policies per (state, deal), which section 6 also
 * lists. A post-split policy is read from a count group's fitted plays, which stage 1 does not
 * have, so stage 2 counts them, from pass 0's policy and again after pass 1 (section 6).
 *
 * CPU seconds are the thread's own (ThreadMXBean), wall seconds the clock's. On a shared
 * machine the CPU time also grows with what else runs, so the load average is printed at the
 * start and the end; compare figures only from one sitting (COUNTING_COMPARISON.md section 7).
 * Run it after ./run-tests.sh has compiled the sources, with the mongo jar on the class path for
 * the catalog: java -cp build/classes:lib/mongo-java-driver-3.12.14.jar FullIndexTimingReport
 * [state file] [states] [threads] [split every k-th state]
 */
public final class FullIndexTimingReport {

    private static final RoundRules RULES = StateRun.montreal();

    private FullIndexTimingReport() {
    }

    /** CPU nanoseconds by part, and counts, over the states one thread did. */
    private static final class Tally {
        long tree;
        long hiLoCount;
        long hiLoContested;
        long hiLoEvery;
        long kissCount;
        long kissContested;
        long splits;
        int splitsValued;
        int splitsStored;
        int states;
        double checksum;

        void add(Tally t) {
            tree += t.tree;
            hiLoCount += t.hiLoCount;
            hiLoContested += t.hiLoContested;
            hiLoEvery += t.hiLoEvery;
            kissCount += t.kissCount;
            kissContested += t.kissContested;
            splits += t.splits;
            splitsValued += t.splitsValued;
            splitsStored += t.splitsStored;
            states += t.states;
            checksum += t.checksum;
        }
    }

    public static void main(String[] args) throws Exception {
        Path data = Paths.get(args.length > 0 ? args[0] : "runs/montreal-80.states");
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 7;
        int splitEvery = args.length > 3 ? Integer.parseInt(args[3]) : 5;
        List<StateStore.Record> states = StateStore.read(data, 0, count);
        CountKeys.Count hiLo = named("Hi-Lo");
        CountKeys.Count kiss = named("KISS I");
        System.out.printf("FullIndexTimingReport: the first %d of %d states in %s; %d processors; %s %s; "
                        + "max heap %.0f MB%n", count, StateStore.records(data), data,
                Runtime.getRuntime().availableProcessors(), System.getProperty("os.arch"),
                System.getProperty("java.vm.version"), Runtime.getRuntime().maxMemory() / 1e6);
        System.out.printf("load average at the start: %.2f%n", loadAverage());

        // A few states first, so the JIT has compiled the code before anything is timed.
        Tally warm = new Tally();
        for (int s = 0; s < Math.min(20, count); s++) {
            time(states.get(s), hiLo, kiss, true, warm);
        }

        resetPeaks();
        long start = System.nanoTime();
        Tally one = new Tally();
        for (int s = 0; s < count; s++) {
            time(states.get(s), hiLo, kiss, s % splitEvery == 0, one);
        }
        double oneWall = (System.nanoTime() - start) / 1e9;
        print("one thread", one, oneWall, 1);
        System.out.printf("  peak heap %.0f MB (the sum of each heap pool's peak, so at most this)%n", peakHeap() / 1e6);

        resetPeaks();
        AtomicInteger next = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Tally>> jobs = new ArrayList<>();
        start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            jobs.add(pool.submit(() -> {
                Tally mine = new Tally();
                for (int s = next.getAndIncrement(); s < count; s = next.getAndIncrement()) {
                    time(states.get(s), hiLo, kiss, s % splitEvery == 0, mine);
                }
                return mine;
            }));
        }
        Tally all = new Tally();
        for (Future<Tally> f : jobs) {
            all.add(f.get());
        }
        double allWall = (System.nanoTime() - start) / 1e9;
        pool.shutdown();
        print(threads + " threads", all, allWall, threads);
        System.out.printf("  peak heap %.0f MB%n", peakHeap() / 1e6);
        System.out.printf("  values summed to %+.9f on one thread and %+.9f on %d%n", one.checksum, all.checksum, threads);

        held(states, hiLo, Math.min(200, count));
        System.out.printf("load average at the end: %.2f%n", loadAverage());
    }

    /** Builds one state's trees, walks Hi-Lo both ways and KISS I once, and values its splits if asked. */
    private static void time(StateStore.Record r, CountKeys.Count hiLo, CountKeys.Count kiss, boolean splits, Tally t) {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        int[] shoe = r.ranksLeft();
        Random rnd = new Random(r.shoe * 1000L + r.round);

        long c0 = bean.getCurrentThreadCpuTime();
        PlayerTree tree = new PlayerTree(new ExactRound(RULES), shoe);
        long c1 = bean.getCurrentThreadCpuTime();
        t.tree += c1 - c0;

        PlayerTree.Counted h = tree.count(hiLo, r.dealt);
        long c2 = bean.getCurrentThreadCpuTime();
        t.hiLoCount += c2 - c1;
        PlayerTree.LaterPlay play = thresholds(rnd, hiLo.keyAfter(h.rc, h.depth, 0, 3), 3);
        PlayerTree.FirstHits first = chartFirst(h);
        double[] sink = new double[PlayerTree.SPOTS * 64];
        t.checksum += walk(h, play, first, PlayerTree.Mode.CONTESTED, 1 / r.q, sink);
        long c3 = bean.getCurrentThreadCpuTime();
        t.hiLoContested += c3 - c2;
        t.checksum += walk(h, play, first, PlayerTree.Mode.EVERY_NODE, 1 / r.q, sink);
        long c4 = bean.getCurrentThreadCpuTime();
        t.hiLoEvery += c4 - c3;

        PlayerTree.Counted k = tree.count(kiss, r.dealt);
        long c5 = bean.getCurrentThreadCpuTime();
        t.kissCount += c5 - c4;
        PlayerTree.LaterPlay kissPlay = thresholds(rnd, kiss.keyAfter(k.rc, k.depth, 0, 3), 5);
        t.checksum += walk(k, kissPlay, chartFirst(k), PlayerTree.Mode.CONTESTED, 1 / r.q, sink);
        long c6 = bean.getCurrentThreadCpuTime();
        t.kissContested += c6 - c5;
        t.states++;

        if (splits) {
            SplitValues values = new SplitValues(RULES, shoe, r.values);
            for (int i = 0; i < Deals.COUNT; i++) {
                if (Deals.P1[i] != Deals.P2[i]) {
                    continue;
                }
                int stored = values.fromStore;
                long s0 = bean.getCurrentThreadCpuTime();
                double v = values.value(i, splitPlay(Deals.P1[i], Deals.UP[i]));
                long s1 = bean.getCurrentThreadCpuTime();
                if (Double.isNaN(v)) {
                    continue;
                }
                t.checksum += v;
                if (values.fromStore > stored) {
                    t.splitsStored++;
                } else {
                    t.splits += s1 - s0;
                    t.splitsValued++;
                }
            }
        }
    }

    /** One walk: values, reach and the gaps into a flat sink by spot and clamped key. Returns a checksum. */
    private static double walk(PlayerTree.Counted c, PlayerTree.LaterPlay play, PlayerTree.FirstHits first,
                               PlayerTree.Mode mode, double weight, double[] sink) {
        PlayerTree.Walk w = c.walk(play, mode);
        w.reach(first, weight);
        w.gaps((spot, key, gap) -> sink[spot * 64 + Math.max(0, Math.min(63, key + 32))] += gap);
        double sum = 0;
        for (double v : w.partHit) {
            if (!Double.isNaN(v)) {
                sum += v;
            }
        }
        return sum;
    }

    private static PlayerTree.LaterPlay thresholds(Random rnd, int centre, int spread) {
        int[] at = new int[PlayerTree.SPOTS];
        for (int s = 0; s < at.length; s++) {
            at[s] = centre - spread + rnd.nextInt(2 * spread + 1);
        }
        return (total, soft, up, key) -> PlayerTree.contested(total, soft)
                ? key >= at[PlayerTree.spot(total, soft, up)]
                : PlayerTree.CHART.stands(total, soft, up, key);
    }

    private static final int[] CHART_FIRST = ScoringStates.chartMoves(RULES);

    private static PlayerTree.FirstHits chartFirst(PlayerTree.Counted c) {
        return (deal, part) -> StateValuer.MOVES[CHART_FIRST[deal]] == PlayerMove.Hit;
    }

    /** The chart's split play, but standing on hard 16 and hitting soft 18 against every up-card. */
    private static SplitPlay splitPlay(int rank, int up) {
        RoundPolicy chart = BasicStrategy.chart();
        return new SplitPlay(rank, up, (total, soft) -> soft ? total >= 19 : total >= 16
                || PlayerTree.CHART.stands(total, false, up, 0), deal -> {
            int p1 = Deals.P1[deal];
            int p2 = Deals.P2[deal];
            boolean ace = p1 == 1 || p2 == 1;
            int hard = p1 + p2;
            int total = ace && hard <= 11 ? hard + 10 : hard;
            boolean soft = ace && hard <= 11;
            int pair = p1 == p2 ? p1 : 0;
            return new PlayerMove[]{
                    chart.choose(new RoundPolicy.Decision(total, soft, 2, pair, up, false, true, total < 21, true,
                            pair != 0, false)),
                    chart.choose(new RoundPolicy.Decision(total, soft, 2, pair, up, false, true, total < 21, true,
                            false, false)),
                    PlayerMove.Stand};
        });
    }

    private static void print(String what, Tally t, double wall, int threads) {
        System.out.printf("%s: %d states in %.1f s wall, %.4f s wall a state%n", what, t.states, wall, wall / t.states);
        System.out.printf("  trees of a state          %.4f s CPU%n", t.tree / 1e9 / t.states);
        System.out.printf("  Hi-Lo: count's tree       %.5f s CPU a state; one walk, contested %.5f s, every node %.5f s%n",
                t.hiLoCount / 1e9 / t.states, t.hiLoContested / 1e9 / t.states, t.hiLoEvery / 1e9 / t.states);
        System.out.printf("  KISS I: count's tree      %.5f s CPU a state; one walk, contested %.5f s%n",
                t.kissCount / 1e9 / t.states, t.kissContested / 1e9 / t.states);
        System.out.printf("  splits: %d valued, %.4f s CPU a call; %d taken from the stored values%n",
                t.splitsValued, t.splitsValued == 0 ? 0 : t.splits / 1e9 / t.splitsValued, t.splitsStored);
    }

    /** The heap a state's trees hold: the rank-level tree, Hi-Lo's and KISS I's count trees, and one walk of each. */
    private static void held(List<StateStore.Record> states, CountKeys.Count hiLo, int n) {
        CountKeys.Count kiss = named("KISS I");
        Runtime rt = Runtime.getRuntime();
        long before = usedAfterGc(rt);
        List<Object> keep = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            StateStore.Record r = states.get(s);
            PlayerTree tree = new PlayerTree(new ExactRound(RULES), r.ranksLeft());
            keep.add(tree);
        }
        long trees = usedAfterGc(rt);
        for (int s = 0; s < n; s++) {
            StateStore.Record r = states.get(s);
            PlayerTree tree = (PlayerTree) keep.get(s);
            PlayerTree.Counted h = tree.count(hiLo, r.dealt);
            keep.add(h.walk(PlayerTree.CHART, PlayerTree.Mode.CONTESTED));
            PlayerTree.Counted k = tree.count(kiss, r.dealt);
            keep.add(k.walk(PlayerTree.CHART, PlayerTree.Mode.CONTESTED));
        }
        long counted = usedAfterGc(rt);
        System.out.printf("held in memory, over %d states (%d objects kept): the trees %.0f KB a state; Hi-Lo's and "
                        + "KISS I's count trees with a contested walk each, %.0f KB more%n", n, keep.size(),
                (trees - before) / 1e3 / n, (counted - trees) / 1e3 / n);
    }

    private static long usedAfterGc(Runtime rt) {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    static void resetPeaks() {
        System.gc();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP) {
                p.resetPeakUsage();
            }
        }
    }

    static long peakHeap() {
        long sum = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP) {
                sum += p.getPeakUsage().getUsed();
            }
        }
        return sum;
    }

    static double loadAverage() {
        return ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
    }

    private static CountKeys.Count named(String system) {
        for (CountKeys.Count c : CountKeys.catalog()) {
            if (c.systems.contains(system)) {
                return c;
            }
        }
        throw new IllegalArgumentException(system);
    }
}
