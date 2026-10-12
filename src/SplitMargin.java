import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The split margin (COUNT_DESIGN.md, sections 2 and 4): what playing split hands better than
 * the chart adds to a state, delta, whose flat mean over the twin's states, times the widest
 * spread S, is the margin by which a count with full play could beat the full-play perfect row,
 * which plays split hands by the chart.
 *
 * A count with full play plays a state's split hands by a SplitPlay drawn from its tables at the
 * deal's group: a hit or stand for each later total, soft or hard, against the up-card, and a
 * first move for each second card. So the most any count's split play can make of a pair deal at
 * a state is the best SplitPlay for that state alone. This finds it by coordinate ascent from the
 * chart's: each contested later total in turn (hard 12 to 16, soft 13 to 18), then each second
 * card's first move (each move legal on a split hand put first, the chart's ranking after it),
 * keeping any change that raises V(Split), until a whole pass changes nothing. A local optimum:
 * delta is measured from below as far as the ascent stops short of the best SplitPlay, and the
 * tool's own lines say so, as must anything that uses them. The mean is given at each penetration,
 * over the sample's states of that penetration (summary), since each penetration's full-play bound
 * takes its own margin.
 *
 * A state's delta, in the units of its round value, is the sum over the pair deals it can deal
 * of P(i) (1 - P(nat|i)) x (the best first move with Split at its best play, less the best first
 * move with Split at the chart's), as the perfect row takes its first moves (FullIndexFit.perfect).
 *
 * Three additions (PLAN_COMPUTE.md 4.3), so the margin can be tightened and its gap looked at:
 * - --out FILE writes each state's delta, its chart value and its per-deal gains (writeOut), so
 *   the margin can be worked out again without the 45-minute run.
 * - The rearranged margin (margins): S x mean(delta) supposes every state bet the top bet. The
 *   bound bets the same schedule as every count, so the most better split play could add is the
 *   schedule's bets paired with delta largest to largest (rearranged), OV(delta), with an SE from
 *   draws of the sample's states. Printed after every run, and from a file --out wrote by
 *   --rearranged FILE, which reads no state.
 * - --restarts K --restart-states N runs the ascent again from K random split plays on N states
 *   (restarts) and reports the largest gain over the chart-start optimum: evidence about how much
 *   the ascent misses by stopping at a local optimum, not a proof.
 *
 * java -Xmx8g -cp CLASSES:lib/mongo-java-driver-3.12.14.jar SplitMargin TWIN_STATES [every k-th state] [threads]
 *     [--out FILE] [--restarts K --restart-states N] [--seed S] [--resamples B]
 * java -cp CLASSES SplitMargin --rearranged FILE [--resamples B] [--seed S]
 */
final class SplitMargin {

    private SplitMargin() {
    }

    /** One state's delta, and how many splits were valued afresh to find it. */
    static final class Delta {
        double delta;
        int valued;
        /** Per pair deal looked at, what its best split play adds to V(Split) over the chart's. */
        final List<double[]> gains = new ArrayList<>();
    }

    /** The pair deals a state can be dealt and split, in deal order. */
    static int[] pairDeals(RoundRules rules) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < Deals.COUNT; i++) {
            if (Deals.P1[i] == Deals.P2[i] && rules.maxHandsFor(Deals.P1[i]) >= 2) {
                out.add(i);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** A state's delta over the pair deals given (every one when null). */
    static Delta delta(RoundRules rules, StateStore.Record r, ExactRound engine, int[] deals) {
        int[] left = r.ranksLeft();
        SplitValues sv = new SplitValues(rules, left, r.values, new java.util.HashMap<>(), engine);
        int moves = ScoringStates.MOVES;
        int split = SystemScorer.SPLIT;
        Delta out = new Delta();
        for (int i : deals == null ? pairDeals(rules) : deals) {
            double p = Deals.probability(left, i);
            if (p == 0 || !StateValuer.legal(rules, i, PlayerMove.Split)) {
                continue;
            }
            double dn = Deals.dealerNatural(left, i);
            double other = Double.NEGATIVE_INFINITY;
            for (int m = 0; m < moves; m++) {
                if (m != split && StateValuer.legal(rules, i, StateValuer.MOVES[m])) {
                    other = Math.max(other, r.values[i * moves + m]);
                }
            }
            double chart = r.values[i * moves + split];
            double best = bestSplit(rules, sv, i, chart);
            out.gains.add(new double[]{i, best - chart});
            out.delta += p * (1 - dn) * (Math.max(other, best) - Math.max(other, chart));
        }
        out.valued = sv.valued;
        return out;
    }

    /** The best V(Split) the coordinate ascent finds for pair deal i, from the chart's play, worth chart. */
    static double bestSplit(RoundRules rules, SplitValues sv, int deal, double chart) {
        int up = Deals.UP[deal];
        // The later table: contested totals, hard 12 to 16 at [total], soft 13 to 18 at [22 + total].
        boolean[] stands = new boolean[44];
        for (int total = 4; total <= 21; total++) {
            for (boolean soft : new boolean[]{false, true}) {
                stands[(soft ? 22 : 0) + total] = PlayerTree.CHART.stands(total, soft, up, 0);
            }
        }
        // Each second card's first move put first, or null for the chart's ranking alone.
        return ascend(rules, sv, deal, stands, new PlayerMove[11], chart);
    }

    /** The moves a second card's first move may be put first as, in the order the ascent tries them. */
    static final PlayerMove[] OPTIONS = {PlayerMove.Stand, PlayerMove.Hit, PlayerMove.Double, PlayerMove.Split};

    /**
     * The coordinate ascent from the tables given, worth start: each contested later total in
     * turn, then each second card's first move, keeping any change that raises V(Split), until a
     * whole pass changes nothing. The tables are changed in place to the play it ends at.
     */
    static double ascend(RoundRules rules, SplitValues sv, int deal, boolean[] stands, PlayerMove[] first, double start) {
        int rank = Deals.P1[deal];
        int up = Deals.UP[deal];
        double best = start;
        boolean changed = true;
        PlayerMove[] options = OPTIONS;
        while (changed) {
            changed = false;
            for (int total = 4; total <= 21; total++) {
                for (boolean soft : new boolean[]{false, true}) {
                    if (!PlayerTree.contested(total, soft)) {
                        continue;
                    }
                    int at = (soft ? 22 : 0) + total;
                    stands[at] = !stands[at];
                    double v = sv.value(deal, play(rules, rank, up, stands, first));
                    if (v > best + 1e-12) {
                        best = v;
                        changed = true;
                    } else {
                        stands[at] = !stands[at];
                    }
                }
            }
            for (int second = 1; second <= 10; second++) {
                PlayerMove was = first[second];
                for (PlayerMove m : options) {
                    if (m == was) {
                        continue;
                    }
                    first[second] = m;
                    double v = sv.value(deal, play(rules, rank, up, stands, first));
                    if (v > best + 1e-12) {
                        best = v;
                        was = m;
                        changed = true;
                    } else {
                        first[second] = was;
                    }
                }
            }
        }
        return best;
    }

    /** The SplitPlay of these tables: a contested total's answer from stands, the chart's elsewhere. */
    static SplitPlay play(RoundRules rules, int rank, int up, boolean[] stands, PlayerMove[] first) {
        boolean[] s = stands.clone();
        PlayerMove[] f = first.clone();
        return new SplitPlay(rank, up, (total, soft) -> PlayerTree.contested(total, soft) ? s[(soft ? 22 : 0) + total]
                : PlayerTree.CHART.stands(total, soft, up, 0), deal -> {
            PlayerMove[] chart = FullIndexFit.chartRanking(rules, deal);
            int second = Deals.P1[deal] == rank ? Deals.P2[deal] : Deals.P1[deal];
            if (f[second] == null) {
                return chart;
            }
            PlayerMove[] out = new PlayerMove[chart.length + 1];
            out[0] = f[second];
            System.arraycopy(chart, 0, out, 1, chart.length);
            return out;
        });
    }

    // ------------------------------------------------------------------ restarts (PLAN_COMPUTE.md 4.3, change 3)

    /** One state's restarts: its delta from the chart's start, and with the best of every restart taken too. */
    static final class Restart {
        double delta0;
        double delta;
        /**
         * Per pair deal: {deal, the chart-start optimum, the best over it and every restart, the
         * smallest of each restart's end less its start, the highest end of any restart}.
         */
        final List<double[]> deals = new ArrayList<>();

        /** What the restarts found beyond the chart-start optimum: never below zero, since that optimum is among them. */
        double gain() {
            return delta - delta0;
        }
    }

    /**
     * The ascent run again from k random split plays for each pair deal of a state: each contested
     * later total stood on or hit at random, each second card's first move put first at random or
     * left to the chart's ranking, each draw from a stream fixed by the seed, the state and the deal.
     * Each deal keeps the best of the chart-start optimum and every restart's end, so the state's
     * delta with restarts is never below its delta from the chart's start; the difference is what
     * the chart-start ascent missed by stopping at a local optimum on these starts. Evidence about
     * that gap, not a proof that none is left.
     */
    static Restart restarts(RoundRules rules, StateStore.Record r, ExactRound engine, int[] deals, int k, long seed) {
        int[] left = r.ranksLeft();
        SplitValues sv = new SplitValues(rules, left, r.values, new java.util.HashMap<>(), engine);
        int moves = ScoringStates.MOVES;
        int split = SystemScorer.SPLIT;
        Restart out = new Restart();
        for (int i : deals == null ? pairDeals(rules) : deals) {
            double p = Deals.probability(left, i);
            if (p == 0 || !StateValuer.legal(rules, i, PlayerMove.Split)) {
                continue;
            }
            double dn = Deals.dealerNatural(left, i);
            double other = Double.NEGATIVE_INFINITY;
            for (int m = 0; m < moves; m++) {
                if (m != split && StateValuer.legal(rules, i, StateValuer.MOVES[m])) {
                    other = Math.max(other, r.values[i * moves + m]);
                }
            }
            double chart = r.values[i * moves + split];
            double b0 = bestSplit(rules, sv, i, chart);
            double best = b0;
            double lowest = Double.POSITIVE_INFINITY;
            double highest = Double.NEGATIVE_INFINITY;
            for (int t = 0; t < k; t++) {
                java.util.SplittableRandom rng = new java.util.SplittableRandom(ShoeRun.mix(seed, (long) r.shoe << 16
                        | r.round, (long) i << 16 | t));
                boolean[] stands = new boolean[44];
                for (int total = 4; total <= 21; total++) {
                    for (boolean soft : new boolean[]{false, true}) {
                        stands[(soft ? 22 : 0) + total] = PlayerTree.contested(total, soft) ? rng.nextBoolean()
                                : PlayerTree.CHART.stands(total, soft, Deals.UP[i], 0);
                    }
                }
                PlayerMove[] first = new PlayerMove[11];
                for (int second = 1; second <= 10; second++) {
                    int pick = rng.nextInt(OPTIONS.length + 1);
                    first[second] = pick == OPTIONS.length ? null : OPTIONS[pick];
                }
                double start = sv.value(i, play(rules, Deals.P1[i], Deals.UP[i], stands, first));
                double end = ascend(rules, sv, i, stands, first, start);
                lowest = Math.min(lowest, end - start);
                highest = Math.max(highest, end);
                best = Math.max(best, end);
            }
            out.deals.add(new double[]{i, b0, best, lowest, highest});
            out.delta0 += p * (1 - dn) * (Math.max(other, b0) - Math.max(other, chart));
            out.delta += p * (1 - dn) * (Math.max(other, best) - Math.max(other, chart));
        }
        return out;
    }

    // ------------------------------------------------------------------ the run

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Options o = Options.parse(args);
        if (o.rearranged != null) {
            rearrangedReport(readOut(o.rearranged), o.resamples, o.seed, System.out);
            return;
        }
        Path file = o.states;
        CustomCounts.requireTwin(file);
        if (o.out != null) {
            CeilingReport.requireSavable(o.out, 0);
        }
        RoundRules rules = StateRun.montreal();
        int records = StateStore.records(file);
        List<StateStore.Record> sample = new ArrayList<>();
        for (int at = 0; at < records; at += o.every) {
            StateStore.Record r = StateStore.read(file, at, 1).get(0);
            if (r.depth < SystemScorer.CUTS[SystemScorer.CUTS.length - 1]) {
                sample.add(r);
            }
        }
        PrintStream out = System.out;
        long t0 = System.nanoTime();
        Measured m = measure(rules, sample, o.threads, o.restarts, o.restartStates, o.seed);
        long splits = 0;
        for (int v : m.valued) {
            splits += v;
        }
        String head = String.format("SplitMargin %s on %s: every %d-th record at 80%%, %d states, %d splits valued, "
                + "%d restarts on %d states (seed %d), %.0f s.", FullIndexRun.commit(), file, o.every, sample.size(), splits,
                o.restarts, o.restarts == 0 ? 0 : Math.min(o.restartStates, sample.size()), o.seed,
                (System.nanoTime() - t0) / 1e9);
        out.println(head);
        print(out, sample, m.delta);
        List<Row> rows = rows(rules, sample, m);
        if (o.restarts > 0) {
            printRestarts(rows, out);
        }
        rearrangedReport(rows, o.resamples, o.seed, out);
        if (o.out != null) {
            writeOut(o.out, head, rows);
            out.println("Each state's delta and per-deal gains written to " + o.out + ".");
        }
    }

    /**
     * The flat mean of delta at each penetration, weighted 1/q, over the sample's states of that
     * penetration (depth under its cut): [penetration] {mean, its SE, states}.
     */
    static double[][] summary(List<StateStore.Record> sample, double[] delta) {
        double[][] out = new double[SystemScorer.CUTS.length][];
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            double num = 0;
            double den = 0;
            int n = 0;
            for (int k = 0; k < delta.length; k++) {
                if (sample.get(k).depth < SystemScorer.CUTS[p]) {
                    double w = 1 / sample.get(k).q;
                    num += w * delta[k];
                    den += w;
                    n++;
                }
            }
            double mean = num / den;
            double ss = 0;
            for (int k = 0; k < delta.length; k++) {
                if (sample.get(k).depth < SystemScorer.CUTS[p]) {
                    double w = 1 / sample.get(k).q;
                    ss += w * w * (delta[k] - mean) * (delta[k] - mean);
                }
            }
            out[p] = new double[]{mean, Math.sqrt(ss) / den, n};
        }
        return out;
    }

    /** summary's lines, each saying the mean is measured from below. */
    static void print(PrintStream out, List<StateStore.Record> sample, double[] delta) {
        out.println("Flat mean of delta, weighted 1/q, and the margin at S = " + SystemScorer.SPREADS + ": a lower bound, "
                + "since the coordinate ascent stops at a local optimum of each state's split play; a full-play bound "
                + "plus this margin is conditional on it.");
        double[][] m = summary(sample, delta);
        for (int p = 0; p < m.length; p++) {
            out.printf(Locale.ROOT, "- %s: %.5f%% a round (SE %.5f%%) over %d states; the margin at S = %d, at least "
                            + "%.4f%%.%n", SystemScorer.PENETRATIONS[p], 100 * m[p][0], 100 * m[p][1], (int) m[p][2],
                    SystemScorer.SPREADS, 100 * SystemScorer.SPREADS * m[p][0]);
        }
    }

    // ------------------------------------------------------------------ options

    /** The command line: a twin state file, then every k-th record and threads, then flags. */
    static final class Options {
        Path states;
        int every = 1000;
        int threads = 6;
        /** --out FILE: each state's delta and per-deal gains, written once the run ends (change 1). */
        Path out;
        /** --restarts K --restart-states N (change 3). */
        int restarts;
        int restartStates = 40;
        /** --rearranged FILE: the rearranged margin from a file --out wrote, reading no state (change 2). */
        Path rearranged;
        int resamples = 1000;
        long seed = Bootstrap.SEED;

        static Options parse(String[] args) {
            Options o = new Options();
            int positional = 0;
            for (int a = 0; a < args.length; a++) {
                switch (args[a]) {
                    case "--out":
                        o.out = Paths.get(args[++a]);
                        break;
                    case "--restarts":
                        o.restarts = Integer.parseInt(args[++a]);
                        break;
                    case "--restart-states":
                        o.restartStates = Integer.parseInt(args[++a]);
                        break;
                    case "--rearranged":
                        o.rearranged = Paths.get(args[++a]);
                        break;
                    case "--resamples":
                        o.resamples = Integer.parseInt(args[++a]);
                        break;
                    case "--seed":
                        o.seed = Long.parseLong(args[++a]);
                        break;
                    default:
                        if (args[a].startsWith("--")) {
                            throw new IllegalArgumentException("usage: SplitMargin TWIN_STATES [every k-th state] [threads] "
                                    + "[--out FILE] [--restarts K --restart-states N] [--seed S] [--resamples B]; or "
                                    + "SplitMargin --rearranged FILE [--resamples B] [--seed S]; not " + args[a]);
                        }
                        if (positional == 0) {
                            o.states = Paths.get(args[a]);
                        } else if (positional == 1) {
                            o.every = Integer.parseInt(args[a]);
                        } else if (positional == 2) {
                            o.threads = Integer.parseInt(args[a]);
                        } else {
                            throw new IllegalArgumentException("one state file, every k-th state and threads; not " + args[a]);
                        }
                        positional++;
                }
            }
            if ((o.states == null) == (o.rearranged == null)) {
                throw new IllegalArgumentException("give a twin state file, or --rearranged FILE, not both");
            }
            if (o.restarts < 0 || o.restartStates < 1 || o.resamples < 2) {
                throw new IllegalArgumentException("restarts at least 0, restart states at least 1, resamples at least 2");
            }
            return o;
        }
    }

    // ------------------------------------------------------------------ measuring

    /** What a run measured over its sample, by sample index. */
    static final class Measured {
        double[] delta;
        int[] valued;
        List<List<double[]>> gains = new ArrayList<>();
        /** The restart gain of each state restarted, NaN for the others. */
        double[] restartGain;
    }

    /**
     * Each sampled state's delta from the chart's start, and, given k restarts, the restart gain
     * of n states spread evenly through the sample (the n-th parts of it, at indices j x size / n).
     * Each state's figures depend on nothing but the state, the seed and k, so the thread count
     * does not change them.
     */
    static Measured measure(RoundRules rules, List<StateStore.Record> sample, int threads, int k, int n, long seed)
            throws Exception {
        Measured m = new Measured();
        int size = sample.size();
        m.delta = new double[size];
        m.valued = new int[size];
        m.restartGain = new double[size];
        java.util.Arrays.fill(m.restartGain, Double.NaN);
        List<List<double[]>> gains = new ArrayList<>();
        for (int j = 0; j < size; j++) {
            gains.add(null);
        }
        java.util.Set<Integer> restarted = new java.util.TreeSet<>();
        if (k > 0) {
            for (int j = 0; j < Math.min(n, size); j++) {
                restarted.add((int) ((long) j * size / Math.min(n, size)));
            }
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
            List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                jobs.add(pool.submit(() -> {
                    ExactRound engine = new ExactRound(rules);
                    for (int j = next.getAndIncrement(); j < size; j = next.getAndIncrement()) {
                        Delta d = delta(rules, sample.get(j), engine, null);
                        m.delta[j] = d.delta;
                        m.valued[j] = d.valued;
                        gains.set(j, d.gains);
                        if (restarted.contains(j)) {
                            m.restartGain[j] = restarts(rules, sample.get(j), engine, null, k, seed).gain();
                        }
                    }
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> j : jobs) {
                j.get();
            }
        } finally {
            pool.shutdown();
        }
        m.gains = gains;
        return m;
    }

    // ------------------------------------------------------------------ the out file (change 1)

    /** One sampled state as --out writes it. */
    static final class Row {
        int shoe;
        int round;
        int depth;
        double q;
        /** The state's round value under the chart's first moves, without insurance (ScoringStates.chart). */
        double chart;
        double delta;
        int valued;
        double restartGain = Double.NaN;
        List<double[]> gains = new ArrayList<>();
    }

    /** The sample's rows: each state's figures, with its chart value worked out as the scorer does. */
    static List<Row> rows(RoundRules rules, List<StateStore.Record> sample, Measured m) {
        List<Row> out = new ArrayList<>();
        if (sample.isEmpty()) {
            return out;
        }
        ScoringStates st = new ScoringStates(new ArrayList<>(sample), sample.get(sample.size() - 1).shoe + 1, rules);
        for (int j = 0; j < sample.size(); j++) {
            Row r = new Row();
            r.shoe = st.shoe[j];
            r.round = st.round[j];
            r.depth = st.depth[j];
            r.q = st.q[j];
            r.chart = st.chart[j];
            r.delta = m.delta[j];
            r.valued = m.valued[j];
            r.restartGain = m.restartGain[j];
            r.gains = m.gains.get(j);
            out.add(r);
        }
        return out;
    }

    static final String COLUMNS = "shoe\tround\tdepth\tq\tchart\tdelta\tvalued\trestartGain\tgains";

    /**
     * Writes the rows to a new file, whole, through FILE.tmp, refusing one that exists: a comment line of the run, one saying
     * delta is measured from below, the columns, and a line per state, every figure as Java prints a
     * double so it reads back to the bit; gains as deal:gain pairs joined by semicolons.
     */
    static void writeOut(Path file, String head, List<Row> rows) throws java.io.IOException {
        StringBuilder b = new StringBuilder();
        b.append("# ").append(head.replace("\n", " ")).append('\n');
        b.append("# delta is measured from below: the coordinate ascent from the chart's split play stops at a local "
                + "optimum of each state's split play\n");
        b.append(COLUMNS).append('\n');
        for (Row r : rows) {
            StringBuilder g = new StringBuilder();
            for (double[] d : r.gains) {
                g.append(g.length() == 0 ? "" : ";").append((int) d[0]).append(':').append(d[1]);
            }
            b.append(r.shoe).append('\t').append(r.round).append('\t').append(r.depth).append('\t').append(r.q).append('\t')
                    .append(r.chart).append('\t').append(r.delta).append('\t').append(r.valued).append('\t')
                    .append(r.restartGain).append('\t').append(g).append('\n');
        }
        if (java.nio.file.Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(file + " exists; the states' deltas go to a new file");
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        java.nio.file.Files.write(tmp, b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE_NEW);
        java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** Reads what writeOut wrote. */
    static List<Row> readOut(Path file) throws java.io.IOException {
        List<Row> out = new ArrayList<>();
        boolean columns = false;
        for (String l : java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
            if (l.startsWith("#") || l.isEmpty()) {
                continue;
            }
            if (!columns) {
                if (!l.equals(COLUMNS)) {
                    throw new IllegalStateException(file + " is not a file SplitMargin --out wrote: its columns are " + l);
                }
                columns = true;
                continue;
            }
            String[] f = l.split("\t", -1);
            Row r = new Row();
            r.shoe = Integer.parseInt(f[0]);
            r.round = Integer.parseInt(f[1]);
            r.depth = Integer.parseInt(f[2]);
            r.q = Double.parseDouble(f[3]);
            r.chart = Double.parseDouble(f[4]);
            r.delta = Double.parseDouble(f[5]);
            r.valued = Integer.parseInt(f[6]);
            r.restartGain = Double.parseDouble(f[7]);
            for (String g : f[8].isEmpty() ? new String[0] : f[8].split(";")) {
                String[] dg = g.split(":");
                r.gains.add(new double[]{Integer.parseInt(dg[0]), Double.parseDouble(dg[1])});
            }
            out.add(r);
        }
        return out;
    }

    /** The restarts' lines: how many states were restarted, how many gained, the largest gain and the mean. */
    static void printRestarts(List<Row> rows, PrintStream out) {
        int n = 0;
        int gained = 0;
        double largest = 0;
        double sum = 0;
        for (Row r : rows) {
            if (!Double.isNaN(r.restartGain)) {
                n++;
                sum += r.restartGain;
                largest = Math.max(largest, r.restartGain);
                gained += r.restartGain > 1e-12 ? 1 : 0;
            }
        }
        out.printf(Locale.ROOT, "Restarts: %d states restarted; on %d the restarts found a better split play than the "
                + "ascent from the chart's; the largest gain in a state's delta %.6f%%, the mean %.6f%%. Evidence about the "
                + "local-optimum gap on these starts, not a proof that none is left.%n", n, gained, 100 * largest,
                n == 0 ? 0 : 100 * sum / n);
    }

    // ------------------------------------------------------------------ the rearranged margin (change 2)

    /**
     * The most that bets with the distribution (b, wb) can make of values with the distribution
     * (a, wa), each side's weights taken as shares of its whole: both sorted in decreasing order and
     * paired share by share, the largest value with the largest bet. By the rearrangement
     * inequality no other way of handing out those bets does better, so this is the tight margin
     * OV(delta) where S x mean(delta) supposes every state bet the top bet.
     */
    static double rearranged(double[] a, double[] wa, double[] b, double[] wb) {
        Integer[] ia = decreasing(a);
        Integer[] ib = decreasing(b);
        double ta = 0;
        double tb = 0;
        for (double w : wa) {
            ta += w;
        }
        for (double w : wb) {
            tb += w;
        }
        double sum = 0;
        int i = 0;
        int j = 0;
        double ra = ia.length > 0 ? wa[ia[0]] / ta : 0;
        double rb = ib.length > 0 ? wb[ib[0]] / tb : 0;
        while (i < ia.length && j < ib.length) {
            double t = Math.min(ra, rb);
            sum += t * a[ia[i]] * b[ib[j]];
            ra -= t;
            rb -= t;
            if (ra <= 1e-15) {
                i++;
                ra = i < ia.length ? wa[ia[i]] / ta : 0;
            }
            if (rb <= 1e-15) {
                j++;
                rb = j < ib.length ? wb[ib[j]] / tb : 0;
            }
        }
        return sum;
    }

    private static Integer[] decreasing(double[] v) {
        Integer[] idx = new Integer[v.length];
        for (int k = 0; k < v.length; k++) {
            idx[k] = k;
        }
        java.util.Arrays.sort(idx, (x, y) -> Double.compare(v[y], v[x]));
        return idx;
    }

    /**
     * At penetration p, over the rows of that penetration (depth under its cut) drawn with the
     * multiplicities given (null for each once): for each spread S from 1 to 8, {S x mean(delta),
     * the rearranged margin}, delta weighted 1/q, each with add added to every state's delta. The
     * bets are the same schedule's reference bets, clamp(chart / 0.25%, 1, S), as every count is
     * handed them (SystemScorer.scheduleIntegral); their distribution is the sample's own, weighted
     * 1/q like delta's. [S] holds spread S; [0] is unused.
     */
    static double[][] margins(List<Row> rows, int p, int[] times, double add) {
        List<Integer> in = new ArrayList<>();
        for (int k = 0; k < rows.size(); k++) {
            if (rows.get(k).depth < SystemScorer.CUTS[p] && (times == null || times[k] > 0)) {
                in.add(k);
            }
        }
        double[] d = new double[in.size()];
        double[] w = new double[in.size()];
        double[] chart = new double[in.size()];
        double mean = 0;
        double total = 0;
        for (int j = 0; j < d.length; j++) {
            Row r = rows.get(in.get(j));
            d[j] = r.delta + add;
            w[j] = (times == null ? 1 : times[in.get(j)]) / r.q;
            chart[j] = r.chart;
            mean += w[j] * d[j];
            total += w[j];
        }
        mean /= total;
        double[][] out = new double[SystemScorer.SPREADS + 1][];
        for (int s = 1; s <= SystemScorer.SPREADS; s++) {
            double[] bets = new double[d.length];
            for (int j = 0; j < d.length; j++) {
                bets[j] = SystemScorer.clamp(chart[j] / SystemScorer.UNIT, s);
            }
            out[s] = new double[]{s * mean, rearranged(d, w, bets, w)};
        }
        return out;
    }

    /**
     * The rearranged margin's SE at each penetration and spread: the sample's states drawn again with
     * replacement, resamples times, from a stream of the seed given, and the margin's standard
     * deviation over the draws. [p][S].
     */
    static double[][] marginSE(List<Row> rows, int resamples, long seed) {
        double[][][] draws = new double[SystemScorer.CUTS.length][SystemScorer.SPREADS + 1][resamples];
        java.util.SplittableRandom rng = new java.util.SplittableRandom(seed);
        for (int b = 0; b < resamples; b++) {
            int[] times = new int[rows.size()];
            for (int k = 0; k < rows.size(); k++) {
                times[rng.nextInt(rows.size())]++;
            }
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                double[][] m = margins(rows, p, times, 0);
                for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                    draws[p][s][b] = m[s][1];
                }
            }
        }
        double[][] se = new double[SystemScorer.CUTS.length][SystemScorer.SPREADS + 1];
        for (int p = 0; p < se.length; p++) {
            for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                se[p][s] = Bootstrap.interval(0, draws[p][s]).se;
            }
        }
        return se;
    }

    /**
     * The rearranged margin beside S x mean(delta) at each penetration and spread, with its SE, and
     * again with the largest restart gain added to every state's delta where restarts were run. The
     * lines say what the margin rests on: delta from below, the bets' distribution from the sample.
     */
    static void rearrangedReport(List<Row> rows, int resamples, long seed, PrintStream out) {
        double largest = Double.NaN;
        for (Row r : rows) {
            if (!Double.isNaN(r.restartGain)) {
                largest = Double.isNaN(largest) ? r.restartGain : Math.max(largest, r.restartGain);
            }
        }
        double[][] se = marginSE(rows, resamples, seed);
        out.printf(Locale.ROOT, "%nThe rearranged margin over %d states, %% of a unit a round: the schedule's bets "
                + "(clamp(chart / 0.25%%, 1, S), the sample's own distribution, weighted 1/q) paired with delta largest "
                + "to largest, the most any count's split play could add betting that schedule if delta were exact. "
                + "delta is measured from below, so this is too. SE from %d draws of the states (seed %d).%s%n", rows.size(),
                resamples, seed, Double.isNaN(largest) ? " No restarts were run." : String.format(Locale.ROOT, " The last "
                        + "column adds the largest restart gain, %.6f%%, to every state's delta.", 100 * largest));
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            double[][] m = margins(rows, p, null, 0);
            double[][] g = Double.isNaN(largest) ? null : margins(rows, p, null, largest);
            out.printf("%n%s penetration:%n%n| Spread | S x mean delta | Rearranged | SE |%s%n|---|---|---|---|%s%n",
                    SystemScorer.PENETRATIONS[p], g == null ? "" : " Rearranged, restart gain added |",
                    g == null ? "" : "---|");
            for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                out.printf(Locale.ROOT, "| %s | %.5f | %.5f | %.5f |%s%n", s == 1 ? "flat" : "1-" + s, 100 * m[s][0],
                        100 * m[s][1], 100 * se[p][s], g == null ? "" : String.format(Locale.ROOT, " %.5f |", 100 * g[s][1]));
            }
        }
    }
}
