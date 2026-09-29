import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Scores every counting system on a state file and prints the comparison as markdown
 * (COUNTING_COMPARISON.md, sections 6 to 8).
 *
 * java -Xmx6g -cp build/classes:lib/mongo-java-driver-3.12.14.jar ComparisonReport FILE
 *      [--systems NAME,NAME,...] [--resamples N] [--threads T] [--calibrate K]
 *
 * The file is only read. Its shoes are dealt again from its seed, which checks every stored
 * state and gives the states that were not valued, for the insurance correlation. Hi-Lo and
 * the null count are always scored, since every difference is taken from Hi-Lo and every
 * efficiency from the null count; --systems restricts the rest to the catalog systems named.
 * --calibrate K also scores K disjoint sub-runs of the shoes alone, to check the standard
 * errors the run is sized by against the spread of estimates made on different shoes.
 *
 * Every figure is in units of the bet, shown as a percentage. Differences are paired: a row
 * that plays its own fit is compared with Hi-Lo playing its own, and a betting-only row with
 * Hi-Lo betting only, on the same states and the same bootstrap resamples.
 */
public final class ComparisonReport {

    /** Section 8's first two targets: an EV or a difference known to +-0.05% of a unit, at 95%. */
    static final double EV_TARGET = 0.0005;
    /** Section 8's third: close calls costing under 0.005% of a unit per round. */
    static final double BOUND_TARGET = 0.00005;
    static final double Z95 = 1.959963984540054;
    static final String HI_LO = "Hi-Lo";

    private ComparisonReport() {
    }

    static final class Options {
        /** The catalog systems to score beside Hi-Lo and the null count; null for all of them. */
        List<String> systems;
        int resamples = Bootstrap.RESAMPLES;
        int threads = 1;
        /** Disjoint sub-runs to calibrate the standard errors on; 0 for none. */
        int calibrate;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: ComparisonReport FILE [--systems NAME,NAME,...] [--resamples N] [--threads T]"
                    + " [--calibrate K]");
            System.exit(2);
        }
        Options o = new Options();
        for (int a = 1; a < args.length; a++) {
            switch (args[a]) {
                case "--systems":
                    o.systems = Arrays.asList(args[++a].split(","));
                    break;
                case "--resamples":
                    o.resamples = Integer.parseInt(args[++a]);
                    break;
                case "--threads":
                    o.threads = Integer.parseInt(args[++a]);
                    break;
                case "--calibrate":
                    o.calibrate = Integer.parseInt(args[++a]);
                    break;
                default:
                    throw new IllegalArgumentException("unknown option " + args[a]);
            }
        }
        Locale.setDefault(Locale.ROOT);
        run(Paths.get(args[0]), o, System.out);
    }

    /** One line of a table: an entry, and the Hi-Lo entry it is compared with. */
    static final class Row {
        final SystemScorer.Entry entry;
        final SystemScorer.Entry baseline;
        /** Whether the entry is the null count's, a baseline rather than a system compared. */
        final boolean nullCount;

        Row(SystemScorer.Entry entry, SystemScorer.Entry baseline, boolean nullCount) {
            this.entry = entry;
            this.baseline = baseline;
            this.nullCount = nullCount;
        }

        /**
         * The family whose max-|t| multiplier covers this row's difference from Hi-Lo: OWN_FIT
         * for a system or variant playing its own fit, BETTING_ONLY for one betting only, and
         * -1 for Hi-Lo itself, which has no difference, and for the null count, a baseline.
         */
        int family() {
            if (nullCount || entry == baseline) {
                return -1;
            }
            return entry.bettingOnly ? BETTING_ONLY : OWN_FIT;
        }
    }

    /** The two families of simultaneous intervals: the systems compared, playing their own fits, and betting only. */
    static final int OWN_FIT = 0;
    static final int BETTING_ONLY = 1;

    /** Everything the tables are printed from. */
    static final class Scored {
        ScoringStates st;
        AllStates all;
        SystemScorer scorer;
        StateStore.Header header;
        List<CountKeys.Count> counts;
        List<SystemScorer.Result> results;
        SystemScorer.Result nullCount;
        SystemScorer.Result hiLo;
        List<SystemScorer.Entry> references;
        /** How each count's decision bound falls with the run, in the order of results. */
        Scaling scaling;
        double readSeconds;
        double replaySeconds;
        double scoreSeconds;
        int threads;
    }

    static void run(Path file, Options o, PrintStream out) throws Exception {
        Scored sc = score(file, o);
        print(file, sc, out);
        if (o.calibrate > 1) {
            calibration(sc, sc.counts, o.calibrate, sc.scorer.boot.resamples, sc.threads, out);
        }
    }

    static Scored score(Path file, Options o) throws Exception {
        Scored sc = new Scored();
        long t0 = System.nanoTime();
        StateStore.Header header = StateStore.header(file);
        int shoes = StateStore.shoesDone(file);
        RoundRules rules = StateRun.montreal();
        RoundPolicy chart = BasicStrategy.chart();
        String made = StateRun.describe(rules, chart);
        if (!header.rules.equals(made)) {
            throw new IllegalStateException(file + " was made under \"" + header.rules + "\"; this code plays \"" + made + "\"");
        }
        int widest = SystemScorer.CUTS[SystemScorer.CUTS.length - 1];
        if (header.cut < widest) {
            throw new IllegalStateException(file + " was dealt to cut " + header.cut + ", short of the " + widest + " scored");
        }
        List<StateStore.Record> records = StateStore.readAll(file);
        sc.st = new ScoringStates(records, shoes, rules);
        records = null;
        long t1 = System.nanoTime();
        sc.all = AllStates.replay(header, shoes, rules, chart, sc.st);
        long t2 = System.nanoTime();
        sc.header = header;
        sc.scorer = new SystemScorer(sc.st, new Bootstrap(shoes, o.resamples, Bootstrap.SEED), SystemScorer.M_PLAY);
        sc.counts = select(CountKeys.catalog(), o.systems);
        sc.threads = Math.max(1, o.threads);
        sc.results = scoreAll(sc.scorer, sc.counts, sc.threads);
        sc.nullCount = sc.results.get(0);
        sc.hiLo = sc.results.get(1);
        sc.references = sc.scorer.references();
        sc.scaling = scaling(sc.st, sc.results, sc.scorer.mPlay, sc.threads);
        long t3 = System.nanoTime();
        sc.readSeconds = (t1 - t0) / 1e9;
        sc.replaySeconds = (t2 - t1) / 1e9;
        sc.scoreSeconds = (t3 - t2) / 1e9;
        return sc;
    }

    /**
     * The counts to score: the null count, Hi-Lo, then the catalog entries that hold a system
     * named in systems (all of them when systems is null), in catalog order.
     */
    static List<CountKeys.Count> select(List<CountKeys.Count> catalog, List<String> systems) {
        List<CountKeys.Count> out = new ArrayList<>();
        out.add(CountKeys.nullCount());
        CountKeys.Count hiLo = null;
        for (CountKeys.Count c : catalog) {
            if (c.systems.contains(HI_LO)) {
                hiLo = c;
            }
        }
        if (hiLo == null) {
            throw new IllegalStateException("the catalog has no Hi-Lo to take differences from");
        }
        out.add(hiLo);
        if (systems != null) {
            for (String name : systems) {
                boolean found = false;
                for (CountKeys.Count c : catalog) {
                    for (String s : c.systems) {
                        found |= s.equalsIgnoreCase(name.trim());
                    }
                }
                if (!found) {
                    List<String> names = new ArrayList<>();
                    for (CountKeys.Count c : catalog) {
                        names.addAll(c.systems);
                    }
                    throw new IllegalArgumentException("no catalog system named \"" + name.trim() + "\"; the names are " + names);
                }
            }
        }
        for (CountKeys.Count c : catalog) {
            if (c == hiLo) {
                continue;
            }
            boolean wanted = systems == null;
            if (!wanted) {
                for (String name : systems) {
                    for (String s : c.systems) {
                        wanted |= s.equalsIgnoreCase(name.trim());
                    }
                }
            }
            if (wanted) {
                out.add(c);
            }
        }
        return out;
    }

    /** Scores each count, with its side-count variant where it has one, on threads threads, in order. */
    static List<SystemScorer.Result> scoreAll(SystemScorer scorer, List<CountKeys.Count> counts, int threads)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "comparison-scorer");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<SystemScorer.Result>> jobs = new ArrayList<>();
            for (CountKeys.Count c : counts) {
                CountKeys.Count side = c.systems.isEmpty() ? null : CountKeys.sideCountVariant(c);
                jobs.add(pool.submit(() -> scorer.score(c, side)));
            }
            List<SystemScorer.Result> out = new ArrayList<>();
            for (Future<SystemScorer.Result> f : jobs) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ the figures

    /** The Hi-Lo entry a row is compared with: the same way of playing, its own fit or the chart. */
    static SystemScorer.Entry baseline(SystemScorer.Result hiLo, SystemScorer.Entry e) {
        return hiLo.entries.get(e.bettingOnly ? 1 : 0);
    }

    /** The rows of the comparison: every count's entries, the null count's betting-only one left out. */
    static List<Row> rows(Scored sc) {
        List<Row> out = new ArrayList<>();
        for (SystemScorer.Result r : sc.results) {
            for (SystemScorer.Entry e : r.entries) {
                if (r == sc.nullCount && e.bettingOnly) {
                    continue;
                }
                out.add(new Row(e, baseline(sc.hiLo, e), r == sc.nullCount));
            }
        }
        return out;
    }

    /** estimate and resamples of entry a less entry b, metric m, penetration p. */
    static double[] difference(SystemScorer.Entry a, SystemScorer.Entry b, int p, int m, double[] boot) {
        for (int r = 0; r < boot.length; r++) {
            boot[r] = a.boot[p][m][r] - b.boot[p][m][r];
        }
        return boot;
    }

    /**
     * The standard error of a pooled ratio sum(a) / sum(b) from per-shoe sums, shoes as
     * clusters within their fold, as the bootstrap resamples them: the variance of
     * sum(a - R b) is the sum over folds of n/(n-1) times the sum of squared deviations of
     * the per-shoe residuals from their fold's mean.
     */
    static double shoeSE(double[] a, double[] b) {
        double sa = 0;
        double sb = 0;
        for (int i = 0; i < a.length; i++) {
            sa += a[i];
            sb += b[i];
        }
        if (sb == 0) {
            return Double.NaN;
        }
        double ratio = sa / sb;
        double var = 0;
        for (int f = 0; f < 2; f++) {
            int n = 0;
            double mean = 0;
            for (int i = f; i < a.length; i += 2) {
                n++;
                mean += a[i] - ratio * b[i];
            }
            if (n < 2) {
                continue;
            }
            mean /= n;
            double ss = 0;
            for (int i = f; i < a.length; i += 2) {
                double e = a[i] - ratio * b[i] - mean;
                ss += e * e;
            }
            var += (double) n / (n - 1) * ss;
        }
        return Math.sqrt(var) / sb;
    }

    /**
     * The learning curve's change: the flat EV with play and insurance fitted on half of each
     * training fold less the full fit's, on the same test states, with its interval taken
     * resample by resample, so the shoe luck the two share cancels.
     */
    static Bootstrap.Interval learningCurve(SystemScorer.Result r, int p) {
        SystemScorer.Entry e = r.entries.get(0);
        double[] full = e.boot[p][SystemScorer.FLAT];
        double[] change = new double[full.length];
        for (int k = 0; k < change.length; k++) {
            change[k] = r.halfFitBoot[p][k] - full[k];
        }
        return Bootstrap.interval(r.halfFitFlat[p] - e.estimate[p][SystemScorer.FLAT], change);
    }

    /**
     * The learning curve holds when the change is shown smaller than the decisions are sized
     * to: its whole 95% interval inside +-BOUND_TARGET. Halving the data then moves the flat
     * EV by less than the decision target, so doubling it would move it less still.
     */
    static boolean learningCurveHolds(Bootstrap.Interval change) {
        return change.lo > -BOUND_TARGET && change.hi < BOUND_TARGET;
    }

    /** The valued states a run needs for a 95% interval of +-target, from an SE at the states it has. */
    static double statesFor(double se, double target, int states) {
        double f = Z95 * se / target;
        return states * f * f;
    }

    /** A measured exponent above this is taken as this: a bound summed over close calls is not relied on to fall faster than one over the data. */
    static final double MAX_EXPONENT = 1;
    /** A measured exponent below this is taken as a bound that a longer run does not bring down. */
    static final double MIN_EXPONENT = 0.1;

    /**
     * How the decision bounds were measured to fall, in words. An exponent under MIN_EXPONENT,
     * a negative one included, is a bound that does not come down with more states, and the
     * projection treats it so.
     */
    static String fallText(double exponent) {
        if (Double.isNaN(exponent)) {
            return "Their rate of fall cannot be fitted here.";
        }
        if (exponent < MIN_EXPONENT) {
            return String.format("They do not fall measurably here: fitted over the counts, bound ~ states^%s%.2f, "
                    + "so running longer is not expected to bring them down.", exponent <= 0 ? "+" : "-",
                    Math.abs(exponent));
        }
        return String.format("They fall at states^-%.2f here, fitted over the counts.", exponent);
    }

    /**
     * The exponent b of bound ~ states^-b, one rate shared by several bounds each measured at
     * the same sizes, each with a level of its own: least squares on the logarithms, each
     * bound's points taken about its own means. A size whose bound is 0, every decision
     * settled, has no logarithm and is left out, and a bound with fewer than two sizes left
     * adds nothing. NaN when nothing is left.
     */
    static double boundExponent(double[] states, double[]... bounds) {
        double sxx = 0;
        double sxy = 0;
        for (double[] bound : bounds) {
            int n = 0;
            double sx = 0;
            double sy = 0;
            for (int k = 0; k < states.length; k++) {
                if (bound[k] > 0 && states[k] > 0) {
                    n++;
                    sx += Math.log(states[k]);
                    sy += Math.log(bound[k]);
                }
            }
            if (n < 2) {
                continue;
            }
            for (int k = 0; k < states.length; k++) {
                if (bound[k] > 0 && states[k] > 0) {
                    double dx = Math.log(states[k]) - sx / n;
                    sxx += dx * dx;
                    sxy += dx * (Math.log(bound[k]) - sy / n);
                }
            }
        }
        return sxx == 0 ? Double.NaN : -sxy / sxx;
    }

    /**
     * The valued states at which a bound measured at the states held, falling as
     * states^-exponent, reaches target: states x (bound / target)^(1 / exponent), the
     * exponent capped at MAX_EXPONENT. A bound already under target needs no more than is
     * held, and less when it is falling. One above target that is not falling, with an
     * exponent under MIN_EXPONENT or none, is not reached by running longer: infinite.
     */
    static double statesForBound(double bound, double exponent, double target, double states) {
        if (exponent >= MIN_EXPONENT) {
            return states * Math.pow(bound / target, 1 / Math.min(exponent, MAX_EXPONENT));
        }
        return bound <= target ? states : Double.POSITIVE_INFINITY;
    }

    /**
     * An efficiency, (a - n) / (d - n): how much of the way from the null count n to the
     * perfect row d a count a gets. The resamples go into out, taken resample by resample.
     */
    static double efficiency(double a, double n, double d, double[] ab, double[] nb, double[] db, double[] out) {
        for (int r = 0; r < out.length; r++) {
            out[r] = (ab[r] - nb[r]) / (db[r] - nb[r]);
        }
        return (a - n) / (d - n);
    }

    /**
     * A row's play and insurance efficiencies at one penetration, [0] PE and [1] IE, each
     * with its interval taken resample by resample. Section 6 measures PE flat and without
     * insurance, so on the fitted first moves alone (PLAY), and IE on what the fitted
     * insurance adds (INSURANCE); the flat EV with insurance would mix the two. Both run
     * from the null count's fit toward the perfect row's.
     */
    static Bootstrap.Interval[] efficiencies(SystemScorer.Entry e, SystemScorer.Entry none, SystemScorer.Entry perfect,
                                             int p) {
        int[] metric = {SystemScorer.PLAY, SystemScorer.INSURANCE};
        Bootstrap.Interval[] out = new Bootstrap.Interval[metric.length];
        for (int k = 0; k < metric.length; k++) {
            int m = metric[k];
            double[] boot = new double[e.boot[p][m].length];
            double est = efficiency(e.estimate[p][m], none.estimate[p][m], perfect.estimate[p][m], e.boot[p][m],
                    none.boot[p][m], perfect.boot[p][m], boot);
            out[k] = Bootstrap.interval(est, boot);
        }
        return out;
    }

    // --------------------------------------------------------------------- printing

    static void print(Path file, Scored sc, PrintStream out) {
        ScoringStates st = sc.st;
        SystemScorer scorer = sc.scorer;
        List<Row> rows = rows(sc);
        int resamples = scorer.boot.resamples;
        out.println("# Counting systems compared on shared shoes");
        out.println();
        out.printf("State file `%s`: seed %d, q0 %s, u0 %s, cut %d. %d shoes, %d rounds dealt, %d of them valued. "
                        + "%d bootstrap resamples (seed %d). M_play is %.0f x sqrt(n), n the valued states a fit is "
                        + "formed from.%n", file, sc.header.seed, sc.header.q0, sc.header.u0, sc.header.cut, st.shoes,
                sc.all.size, st.size, resamples, Bootstrap.SEED, scorer.mPlay);
        out.println();
        out.println("Every figure is in units of the bet, as a percentage. Play varies the first decision only "
                + "(first-decision indices, section 4); later decisions follow the chart. Differences are paired "
                + "with Hi-Lo on the same states and resamples: a row playing its own fit against Hi-Lo playing "
                + "its own, a betting-only row against Hi-Lo betting only. Intervals are 95%; one marked b is the "
                + "basic interval, used where the bootstrap's bias is not small next to its SE.");
        out.println();
        out.println("| Penetration | Cut | Rounds dealt | Valued | Sum of 1/q over valued |");
        out.println("|---|---|---|---|---|");
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            SystemScorer.Pen pen = scorer.pen(p);
            double w = 0;
            for (double x : pen.weight) {
                w += x;
            }
            out.printf("| %s | %d | %d | %d | %.0f |%n", SystemScorer.PENETRATIONS[p], pen.cut,
                    sc.all.states(pen.cut), pen.states.length, w);
        }
        out.println();

        references(sc, out);
        headline(sc, rows, out);
        sameSchedule(sc, rows, out);
        ownRamp(sc, rows, out);
        efficiencyTable(sc, out);
        correlations(sc, out);
        resolution(sc, out);
        sizing(sc, out);
        timing(sc, out);
        keyTables(sc, out);
    }

    private static void references(Scored sc, PrintStream out) {
        out.println("## Reference rows");
        out.println();
        out.println("Fitted on nothing and scored on every state of the penetration. The chart flat is the house edge "
                + "for these rules. The perfect row plays each state's own best first move and insures exactly "
                + "when insurance pays: no first-move-only strategy does better. The last row bets the 1-8 same "
                + "schedule in order of each state's true chart advantage, the most any count can make of that "
                + "schedule with the chart's play and no insurance.");
        out.println();
        out.println("| Row | 70% | 75% | 80% |");
        out.println("|---|---|---|---|");
        SystemScorer.Entry chart = sc.references.get(0);
        SystemScorer.Entry perfect = sc.references.get(1);
        SystemScorer.Entry oracle = sc.references.get(2);
        referenceRow(out, "Chart, no count, flat", chart, SystemScorer.FLAT);
        referenceRow(out, "Perfect first move, flat, no insurance", perfect, SystemScorer.PLAY);
        referenceRow(out, "Perfect insurance adds", perfect, SystemScorer.INSURANCE);
        referenceRow(out, "Perfect first move and insurance, flat", perfect, SystemScorer.FLAT);
        referenceRow(out, "Null count, flat (fitted play and insurance)", sc.nullCount.entries.get(0), SystemScorer.FLAT);
        referenceRow(out, "Chart play, 1-8 schedule on the true advantage", oracle, SystemScorer.same(SystemScorer.SPREADS));
        out.println();
    }

    private static void referenceRow(PrintStream out, String name, SystemScorer.Entry e, int m) {
        StringBuilder b = new StringBuilder("| " + name + " |");
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            Bootstrap.Interval iv = Bootstrap.interval(e.estimate[p][m], e.boot[p][m]);
            b.append(' ').append(pct(iv.estimate)).append(' ').append(ci(iv)).append(" |");
        }
        out.println(b);
    }

    static void headline(Scored sc, List<Row> rows, PrintStream out) {
        int s = SystemScorer.SPREADS;
        out.println("## Headline: EV per round, same bet schedule, spread 1-" + s);
        out.println();
        out.println("Every row bets the same distribution of bets (section 6), so the average bet is the same for "
                + "all and EV per round compares count quality directly. The simultaneous interval widens each "
                + "difference by the max-|t| multiplier c over the systems compared at once at its penetration: "
                + "one c for the systems and variants playing their own fits, and another for the betting-only "
                + "rows, so all the intervals of a family hold together 95% of the time. The null count is a "
                + "baseline, not a system compared, and gets none.");
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            int m = SystemScorer.same(s);
            double[][] diffs = new double[rows.size()][];
            double[] est = new double[rows.size()];
            double[] c = maxT(rows, p, m, est, diffs);
            out.println();
            out.printf("### %s penetration (average bet %.3f units; c = %.2f playing their own fits, %.2f betting "
                            + "only)%n%n", SystemScorer.PENETRATIONS[p],
                    sc.hiLo.entries.get(0).estimate[p][SystemScorer.sameBet(s)], c[OWN_FIT], c[BETTING_ONLY]);
            out.println("| Row | EV | SE | Bias | 95% CI | Diff from Hi-Lo | 95% CI | Simultaneous |");
            out.println("|---|---|---|---|---|---|---|---|");
            for (int k = 0; k < rows.size(); k++) {
                Row row = rows.get(k);
                Bootstrap.Interval iv = Bootstrap.interval(row.entry.estimate[p][m], row.entry.boot[p][m]);
                String d = "|  |  |  |";
                if (row.entry != row.baseline) {
                    Bootstrap.Interval dv = Bootstrap.interval(est[k], diffs[k]);
                    int f = row.family();
                    d = "| " + pct(dv.estimate) + " | " + ci(dv) + " | " + (f < 0 ? ""
                            : "[" + pct(dv.estimate - c[f] * dv.se) + ", " + pct(dv.estimate + c[f] * dv.se) + "]")
                            + " |";
                }
                out.printf("| %s | %s | %s | %s | %s %s%n", row.entry.name, pct(iv.estimate), abs(iv.se), pct(iv.bias),
                        ci(iv), d);
            }
        }
        out.println();
    }

    /**
     * The max-|t| multiplier of each family (Row.family) over its rows' differences from
     * Hi-Lo, at [OWN_FIT] and [BETTING_ONLY]; fills est and diffs for every row with a
     * difference, the null count's included. Section 6 takes the simultaneous intervals over
     * the systems compared at once; the null count is a baseline, and a betting-only row
     * answers another question than a system playing its own fit, so neither widens the
     * other's intervals.
     */
    static double[] maxT(List<Row> rows, int p, int m, double[] est, double[][] diffs) {
        List<List<double[]>> boots = new ArrayList<>();
        List<List<Double>> ests = new ArrayList<>();
        for (int f = 0; f < 2; f++) {
            boots.add(new ArrayList<>());
            ests.add(new ArrayList<>());
        }
        for (int k = 0; k < rows.size(); k++) {
            Row row = rows.get(k);
            if (row.entry == row.baseline) {
                continue;
            }
            diffs[k] = difference(row.entry, row.baseline, p, m, new double[row.entry.boot[p][m].length]);
            est[k] = row.entry.estimate[p][m] - row.baseline.estimate[p][m];
            int f = row.family();
            if (f >= 0) {
                boots.get(f).add(diffs[k]);
                ests.get(f).add(est[k]);
            }
        }
        double[] c = new double[2];
        for (int f = 0; f < 2; f++) {
            double[] e = new double[ests.get(f).size()];
            for (int k = 0; k < e.length; k++) {
                e[k] = ests.get(f).get(k);
            }
            c[f] = e.length == 0 ? Double.NaN : Bootstrap.maxT(e, boots.get(f).toArray(new double[0][]));
        }
        return c;
    }

    private static void sameSchedule(Scored sc, List<Row> rows, PrintStream out) {
        out.println("## Same schedule at every spread");
        out.println();
        out.println("EV per round with its 95% interval, then the difference from Hi-Lo with its own, at spreads 1-1 "
                + "(flat) to 1-8. The last two lines of each difference table are the max-|t| multipliers for that "
                + "spread, over the systems playing their own fits and over the betting-only rows.");
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            out.println();
            out.printf("### %s penetration: EV per round%n%n", SystemScorer.PENETRATIONS[p]);
            spreadHeader(out);
            StringBuilder bet = new StringBuilder("| Average bet (every row) |");
            for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                bet.append(String.format(" %.3f |", sc.hiLo.entries.get(0).estimate[p][SystemScorer.sameBet(s)]));
            }
            out.println(bet);
            for (Row row : rows) {
                StringBuilder b = new StringBuilder("| " + row.entry.name + " |");
                for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                    int m = SystemScorer.same(s);
                    Bootstrap.Interval iv = Bootstrap.interval(row.entry.estimate[p][m], row.entry.boot[p][m]);
                    b.append(' ').append(pct(iv.estimate)).append(' ').append(ci(iv)).append(" |");
                }
                out.println(b);
            }
            out.println();
            out.printf("### %s penetration: difference from Hi-Lo%n%n", SystemScorer.PENETRATIONS[p]);
            spreadHeader(out);
            double[][][] diffs = new double[SystemScorer.SPREADS + 1][rows.size()][];
            double[][] est = new double[SystemScorer.SPREADS + 1][rows.size()];
            double[][] c = new double[SystemScorer.SPREADS + 1][];
            for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                c[s] = maxT(rows, p, SystemScorer.same(s), est[s], diffs[s]);
            }
            for (int k = 0; k < rows.size(); k++) {
                Row row = rows.get(k);
                if (row.entry == row.baseline) {
                    continue;
                }
                StringBuilder b = new StringBuilder("| " + row.entry.name + " |");
                for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                    Bootstrap.Interval dv = Bootstrap.interval(est[s][k], diffs[s][k]);
                    b.append(' ').append(pct(dv.estimate)).append(' ').append(ci(dv)).append(" |");
                }
                out.println(b);
            }
            String[] family = {"playing their own fits", "betting only"};
            for (int f = 0; f < 2; f++) {
                StringBuilder cs = new StringBuilder("| max-abs-t multiplier c, " + family[f] + " |");
                for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                    cs.append(String.format(" %.2f |", c[s][f]));
                }
                out.println(cs);
            }
        }
        out.println();
    }

    private static void spreadHeader(PrintStream out) {
        StringBuilder h = new StringBuilder("| Row |");
        StringBuilder r = new StringBuilder("|---|");
        for (int s = 1; s <= SystemScorer.SPREADS; s++) {
            h.append(" 1-").append(s).append(" |");
            r.append("---|");
        }
        out.println(h);
        out.println(r);
    }

    private static void ownRamp(Scored sc, List<Row> rows, PrintStream out) {
        out.println("## Own proportional ramp");
        out.println();
        out.println("Each count bets clamp(its fitted advantage / 0.25%, 1, S) units: first unrounded, then rounded "
                + "half up to whole units, as played. Each cell is EV per round (its SE), average bet, and EV per "
                + "unit bet.");
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            for (int way = 0; way < 2; way++) {
                out.println();
                out.printf("### %s penetration, %s%n%n", SystemScorer.PENETRATIONS[p], way == 0 ? "unrounded" : "as played");
                spreadHeader(out);
                for (Row row : rows) {
                    StringBuilder b = new StringBuilder("| " + row.entry.name + " |");
                    for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                        int m = way == 0 ? SystemScorer.own(s) : SystemScorer.played(s);
                        int mb = way == 0 ? SystemScorer.ownBet(s) : SystemScorer.playedBet(s);
                        double ev = row.entry.estimate[p][m];
                        double bet = row.entry.estimate[p][mb];
                        double se = Bootstrap.interval(ev, row.entry.boot[p][m]).se;
                        b.append(String.format(" %s (%s) / %.2f / %s |", pct(ev), abs(se), bet, pct(ev / bet)));
                    }
                    out.println(b);
                }
            }
        }
        out.println();
    }

    private static void efficiencyTable(Scored sc, PrintStream out) {
        out.println("## Flat play, insurance and efficiencies");
        out.println();
        out.println("Flat bets. Play is each count's fitted first moves without insurance; insurance is what its "
                + "fitted insurance adds. PE = (count - null) / (perfect - null) on play, IE the same on insurance, "
                + "with the null count's fit as the baseline; their intervals come resample by resample.");
        SystemScorer.Entry perfect = sc.references.get(1);
        SystemScorer.Entry none = sc.nullCount.entries.get(0);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            out.println();
            out.printf("### %s penetration%n%n", SystemScorer.PENETRATIONS[p]);
            out.println("| Row | Flat EV | 95% CI | Play | Insurance adds | PE | 95% CI | IE | 95% CI |");
            out.println("|---|---|---|---|---|---|---|---|---|");
            for (SystemScorer.Result r : sc.results) {
                for (SystemScorer.Entry e : r.entries) {
                    if (e.bettingOnly || (r == sc.nullCount && e != none)) {
                        continue;
                    }
                    Bootstrap.Interval flat = Bootstrap.interval(e.estimate[p][SystemScorer.FLAT], e.boot[p][SystemScorer.FLAT]);
                    Bootstrap.Interval[] eff = efficiencies(e, none, perfect, p);
                    out.printf("| %s | %s | %s | %s | %s | %s | %s | %s | %s |%n", e.name, pct(flat.estimate), ci(flat),
                            pct(e.estimate[p][SystemScorer.PLAY]), pct(e.estimate[p][SystemScorer.INSURANCE]),
                            ratio(eff[0].estimate), ratioCi(eff[0]), ratio(eff[1].estimate), ratioCi(eff[1]));
                }
            }
            out.printf("| Perfect first move and insurance | %s |  | %s | %s | 1 |  | 1 |  |%n",
                    pct(perfect.estimate[p][SystemScorer.FLAT]), pct(perfect.estimate[p][SystemScorer.PLAY]),
                    pct(perfect.estimate[p][SystemScorer.INSURANCE]));
        }
        out.println();
    }

    private static void correlations(Scored sc, PrintStream out) {
        out.println("## Empirical betting and insurance correlations");
        out.println();
        out.printf("On each count's unrounded, drift-free true count, running counts included. BC is its correlation "
                + "with the state's chart advantage without insurance, over the %d valued states weighted 1/q; IC "
                + "with the chance the next card is a ten, over all %d rounds dealt. A side-count variant's BC uses "
                + "its adjusted count, and its IC is its base's, since it insures on the plain count.%n%n",
                sc.st.size, sc.all.size);
        out.println("| Count | BC | IC | Published BC, PE, IC |");
        out.println("|---|---|---|---|");
        for (SystemScorer.Result r : sc.results) {
            for (SystemScorer.Entry e : r.entries) {
                if (e.bettingOnly || r == sc.nullCount) {
                    continue;
                }
                out.printf("| %s | %.3f | %.3f | %s |%n", e.name, AllStates.bettingCorrelation(e.count, sc.st),
                        sc.all.insuranceCorrelation(e.count), e.count.published.replace("|", "/"));
            }
        }
        out.println();
    }

    static void resolution(Scored sc, PrintStream out) {
        out.println("## Decision resolution");
        out.println();
        out.printf("A (group, deal) decision is settled when the fitted move is ahead of every other legal move by "
                + "more than twice the standard error of that gap, from per-shoe sums on the fold it was fitted on. "
                + "The true gap is then at least gap - 2 SE, so keeping the fitted move of an unsettled one costs at "
                + "most its frequency x (2 SE - gap) per round, against the move where that is largest; the bound "
                + "is that summed, averaged over the two folds' fits by test weight. Insurance is checked the same "
                + "way, insuring against not, and the decision bound is the two bounds added. The run is big "
                + "enough when the decision bound is under %s%% for every count and the learning curve holds: play "
                + "and insurance fitted on half of each training fold change the flat EV, on the same test states, "
                + "by an amount whose whole 95%% interval, taken resample by resample, lies inside +-%s%%.%n",
                abs(BOUND_TARGET), abs(BOUND_TARGET));
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            out.println();
            out.printf("### %s penetration%n%n", SystemScorer.PENETRATIONS[p]);
            out.println("| Count | Groups | Decisions | Unsettled | Bound | Insurance decisions | Unsettled | Bound "
                    + "| Decision bound | Half-fit flat EV | Change | 95% CI of change | Learning curve |");
            out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|");
            for (SystemScorer.Result r : sc.results) {
                SystemScorer.Resolution res = r.resolution[p];
                Bootstrap.Interval change = learningCurve(r, p);
                out.printf("| %s | %d, %d | %d | %d | %s | %d | %d | %s | %s | %s | %s | [%s, %s]%s | %s |%n",
                        r.count.name, r.groups[p][0].count(), r.groups[p][1].count(), res.decisions, res.unsettled,
                        bound(res.bound), res.insuranceDecisions, res.insuranceUnsettled, bound(res.insuranceBound),
                        bound(res.bound + res.insuranceBound), pct(r.halfFitFlat[p]), signedBound(change.estimate),
                        signedBound(change.lo), signedBound(change.hi), change.basic ? " b" : "",
                        learningCurveHolds(change) ? "holds" : "fails");
            }
        }
        SystemScorer.Result worst = null;
        int last = SystemScorer.CUTS.length - 1;
        for (SystemScorer.Result r : sc.results) {
            if (worst == null || r.resolution[last].bound > worst.resolution[last].bound) {
                worst = r;
            }
        }
        out.println();
        out.printf("### The closest calls of %s at %s, the largest play bound%n%n", worst.count.name,
                SystemScorer.PENETRATIONS[last]);
        out.println("| Fitted on shoes of parity | Play keys | Hand | Fitted | Against | Gap | SE | Frequency | Bound |");
        out.println("|---|---|---|---|---|---|---|---|---|");
        for (SystemScorer.CloseCall c : worst.resolution[last].closest) {
            out.printf("| %d | %d to %d | %d,%d vs %s | %s | %s | %s | %s | %.5f | %s |%n", 1 - c.fold, c.loKey,
                    c.hiKey, Deals.P1[c.deal], Deals.P2[c.deal], Deals.UP[c.deal] == 1 ? "A" : Deals.UP[c.deal],
                    StateValuer.MOVES[c.best], StateValuer.MOVES[c.against], pct(c.gap), abs(c.se), c.frequency,
                    bound(c.bound()));
        }
        out.println();
    }

    /** One entry's figures for the first two criteria at one penetration. */
    static final class EvRow {
        final SystemScorer.Result result;
        final SystemScorer.Entry entry;
        final int pen;
        double seBoot;
        double seShoe;
        double forEv;
        /** NaN for Hi-Lo itself. */
        double diffSeBoot = Double.NaN;
        double diffSeShoe = Double.NaN;
        double forDiff = Double.NaN;

        EvRow(SystemScorer.Result result, SystemScorer.Entry entry, int pen) {
            this.result = result;
            this.entry = entry;
            this.pen = pen;
        }
    }

    /** One count's decision bound at one penetration, play and insurance, for the third criterion. */
    static final class BoundRow {
        final SystemScorer.Result result;
        final int pen;
        final double play;
        final double insurance;
        /** The decision bound at each size of Scaling.PARTS, the last the whole file's. */
        double[] bySize;
        /** b in bound ~ states^-b, fitted at this penetration over the counts. */
        double exponent;
        double forBound;

        BoundRow(SystemScorer.Result result, int pen) {
            this.result = result;
            this.pen = pen;
            this.play = result.resolution[pen].bound;
            this.insurance = result.resolution[pen].insuranceBound;
        }

        /** Every fitted decision's bound: the play's first moves and insurance alike. */
        double total() {
            return play + insurance;
        }
    }

    /**
     * How each count's decision bound falls as the run grows, measured rather than assumed:
     * on the whole file and on each half and each quarter of its shoes (ScoringStates.subRun),
     * every part fitted and resolved alone as a run of its own, the parts of a size averaged.
     */
    static final class Scaling {
        /** The sizes: the file in 4 parts, in 2, whole. */
        static final int[] PARTS = {4, 2, 1};
        /** The mean valued states of a part at each size. */
        final double[] states = new double[PARTS.length];
        /** [result][pen][size]: the mean decision bound. */
        final double[][][] bound;

        Scaling(int results) {
            bound = new double[results][SystemScorer.CUTS.length][PARTS.length];
        }
    }

    /** The scaling of the results, scored on st with M_play coefficient mPlay, which the parts are fitted with too. */
    static Scaling scaling(ScoringStates st, List<SystemScorer.Result> results, double mPlay, int threads)
            throws Exception {
        Scaling out = new Scaling(results.size());
        for (int z = 0; z < Scaling.PARTS.length; z++) {
            int parts = Scaling.PARTS[z];
            if (parts == 1) {
                out.states[z] = st.size;
                for (int c = 0; c < results.size(); c++) {
                    for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                        out.bound[c][p][z] = results.get(c).resolution[p].total();
                    }
                }
                continue;
            }
            List<SystemScorer> subs = new ArrayList<>();
            for (int k = 0; k < parts; k++) {
                ScoringStates sub = st.subRun(k, parts);
                out.states[z] += (double) sub.size / parts;
                subs.add(new SystemScorer(sub, new Bootstrap(sub.shoes, 0, Bootstrap.SEED), mPlay));
            }
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
                Thread t = new Thread(r, "comparison-scaling");
                t.setDaemon(true);
                return t;
            });
            try {
                List<Future<double[]>> jobs = new ArrayList<>();
                for (SystemScorer.Result r : results) {
                    jobs.add(pool.submit(() -> {
                        double[] mean = new double[SystemScorer.CUTS.length];
                        for (SystemScorer sub : subs) {
                            SystemScorer.Resolution[] res = sub.resolution(r.count);
                            for (int p = 0; p < mean.length; p++) {
                                mean[p] += res[p].total() / subs.size();
                            }
                        }
                        return mean;
                    }));
                }
                for (int c = 0; c < jobs.size(); c++) {
                    double[] mean = jobs.get(c).get();
                    for (int p = 0; p < mean.length; p++) {
                        out.bound[c][p][z] = mean[p];
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        }
        return out;
    }

    /** The figures section 8 sizes the run by, and the valued states each criterion asks for. */
    static final class Sizing {
        final List<EvRow> ev = new ArrayList<>();
        final List<BoundRow> bounds = new ArrayList<>();
        /** The valued states each criterion asks for: EV, difference from Hi-Lo, decision bound. */
        final double[] need = new double[3];
        final String[] who = new String[3];
        /** [pen]: the exponent the decision bounds were measured to fall with, over every count but the null count. */
        final double[] exponent = new double[SystemScorer.CUTS.length];
        boolean learningCurve = true;
        /** Whether every system's decision bound, the null count's aside, is under the target on the file itself. */
        boolean boundMet = true;

        void raise(int criterion, double states, String name, int pen) {
            if (states > need[criterion]) {
                need[criterion] = states;
                who[criterion] = name + " at " + SystemScorer.PENETRATIONS[pen];
            }
        }
    }

    /**
     * Works out section 8's criteria at the 1-8 same schedule: each row's SEs and the valued
     * states it needs, each count's decision bound, play and insurance, and the most any row
     * asks for. The null count, a baseline rather than a system, sets none of the three.
     *
     * The decision bound's states come from the rate it was measured to fall at. One count's
     * bounds at three sizes are too noisy at a pilot's size to fit a rate each; the rate is
     * set by how M_play grows, the same for every count, so one exponent is fitted at each
     * penetration over every count, each keeping its own level. The null count is left out
     * of that fit: its one group grows with the whole run, so its bound falls faster.
     */
    static Sizing size(Scored sc) {
        Sizing z = new Sizing();
        int states = sc.st.size;
        int m = SystemScorer.same(SystemScorer.SPREADS);
        SystemScorer.Entry hiLo = sc.hiLo.entries.get(0);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            SystemScorer.Pen pen = sc.scorer.pen(p);
            List<double[]> counted = new ArrayList<>();
            for (int c = 0; c < sc.results.size(); c++) {
                if (sc.results.get(c) != sc.nullCount) {
                    counted.add(sc.scaling.bound[c][p]);
                }
            }
            z.exponent[p] = boundExponent(sc.scaling.states, counted.toArray(new double[0][]));
            for (SystemScorer.Result r : sc.results) {
                SystemScorer.Entry flat = r.entries.get(0);
                z.learningCurve &= learningCurveHolds(learningCurve(r, p));
                for (SystemScorer.Entry e : r.entries) {
                    if (e.bettingOnly || (r == sc.nullCount && e != flat)) {
                        continue;
                    }
                    EvRow row = new EvRow(r, e, p);
                    row.seShoe = shoeSE(e.shoeSums[p], pen.shoeWeight);
                    row.seBoot = Bootstrap.interval(e.estimate[p][m], e.boot[p][m]).se;
                    row.forEv = statesFor(row.seBoot, EV_TARGET, states);
                    if (e != hiLo) {
                        double[] a = new double[sc.st.shoes];
                        for (int i = 0; i < a.length; i++) {
                            a[i] = e.shoeSums[p][i] - hiLo.shoeSums[p][i];
                        }
                        row.diffSeShoe = shoeSE(a, pen.shoeWeight);
                        double[] db = difference(e, hiLo, p, m, new double[e.boot[p][m].length]);
                        row.diffSeBoot = Bootstrap.interval(e.estimate[p][m] - hiLo.estimate[p][m], db).se;
                        row.forDiff = statesFor(row.diffSeBoot, EV_TARGET, states);
                    }
                    z.ev.add(row);
                    if (r != sc.nullCount) {
                        z.raise(0, row.forEv, e.name, p);
                        z.raise(1, row.forDiff, e.name, p);
                    }
                }
                BoundRow b = new BoundRow(r, p);
                b.bySize = sc.scaling.bound[sc.results.indexOf(r)][p];
                b.exponent = z.exponent[p];
                b.forBound = statesForBound(b.total(), b.exponent, BOUND_TARGET, states);
                z.bounds.add(b);
                if (r != sc.nullCount) {
                    z.raise(2, b.forBound, r.count.name, p);
                    z.boundMet &= b.total() < BOUND_TARGET;
                }
            }
        }
        if (z.boundMet) {
            // Every bound is under the target on the file itself, which is the criterion. The
            // largest projection is then no more than the file holds, and bounds that are not
            // measured to fall all project to exactly that, so naming the first of them would
            // point at a row that sets nothing.
            z.who[2] = MET;
        }
        return z;
    }

    /** What the sizing table says sets the decision criterion when every system's bound is already under the target. */
    static final String MET = "met on the file";

    private static void sizing(Scored sc, PrintStream out) {
        Sizing z = size(sc);
        int states = sc.st.size;
        out.println("## Size of the run (section 8)");
        out.println();
        out.printf("Each criterion at the %d valued states in the file, and the valued states the run would need to "
                + "meet its target: the 1-%d same-schedule EV and its difference from Hi-Lo to +-%s%% at 95%%, and "
                + "the decision bound, play and insurance, under %s%%. The first two shrink as one over the root of "
                + "the states. The states they need come from the bootstrap's SE, which refits everything as the "
                + "estimate does. The SE from per-shoe sums of the per-state residuals, which holds every fit fixed, "
                + "is shown beside it; the calibration section, when run, sets both beside the spread of estimates "
                + "made on disjoint shoes (COUNTING_COMPARISON.md, section 8). The null count, a baseline rather "
                + "than a system, is shown but sets none of the three.%n%n"
                + "How the decision bound falls is measured, not assumed: on each quarter and each half of the "
                + "file's shoes, every part fitted alone as a run of its own and the parts averaged, and on the "
                + "whole file. The exponent b of bound ~ states^-b is fitted to the three at each penetration, "
                + "one rate over every count but the null count, each keeping its own level, since one count's "
                + "three bounds are too noisy to fit alone. It is taken as at most %.0f and projects the states "
                + "each bound needs; below %.1f the bounds are taken as not falling. The run is big enough when "
                + "the bound measured on the file itself is under the target, and the projection is only a guide "
                + "to how far off that is.%n",
                states, SystemScorer.SPREADS, abs(EV_TARGET), abs(BOUND_TARGET), MAX_EXPONENT, MIN_EXPONENT);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            out.println();
            out.printf("### %s penetration%n%n", SystemScorer.PENETRATIONS[p]);
            out.println("| Row | EV SE, bootstrap | EV SE, shoes | States for EV | Diff SE, bootstrap | Diff SE, shoes "
                    + "| States for diff |");
            out.println("|---|---|---|---|---|---|---|");
            for (EvRow row : z.ev) {
                if (row.pen != p) {
                    continue;
                }
                boolean diff = !Double.isNaN(row.diffSeBoot);
                out.printf("| %s | %s | %s | %.0f | %s | %s | %s |%n", row.entry.name, abs(row.seBoot), abs(row.seShoe),
                        row.forEv, diff ? abs(row.diffSeBoot) : "", diff ? abs(row.diffSeShoe) : "",
                        diff ? String.format("%.0f", row.forDiff) : "");
            }
            out.println();
            out.printf("Decision bounds, measured on the file's quarters, halves and the whole. %s%n%n",
                    fallText(z.exponent[p]));
            out.printf("| Count | Play bound | Insurance bound | Decision bound | At %.0f states | At %.0f "
                    + "| States for bound |%n", sc.scaling.states[0], sc.scaling.states[1]);
            out.println("|---|---|---|---|---|---|---|");
            for (BoundRow b : z.bounds) {
                if (b.pen == p) {
                    out.printf("| %s | %s | %s | %s | %s | %s | %s |%n", b.result.count.name, bound(b.play),
                            bound(b.insurance), bound(b.total()), bound(b.bySize[0]), bound(b.bySize[1]),
                            states(b.forBound));
                }
            }
        }
        out.println();
        out.println("| Criterion | Valued states needed | Set by |");
        out.println("|---|---|---|");
        String[] names = {"EV to +-" + abs(EV_TARGET) + "%", "Difference from Hi-Lo to +-" + abs(EV_TARGET) + "%",
                "Decision bound, play and insurance, under " + abs(BOUND_TARGET) + "%"};
        for (int k = 0; k < 3; k++) {
            out.printf("| %s | %s | %s |%n", names[k], states(z.need[k]), z.who[k] == null ? "" : z.who[k]);
        }
        int under = 0;
        int systems = 0;
        for (BoundRow b : z.bounds) {
            if (b.result != sc.nullCount) {
                systems++;
                under += b.total() < BOUND_TARGET ? 1 : 0;
            }
        }
        out.printf("%nThe file holds %d valued states from %d shoes, %.3f per shoe. Measured on it, the decision bound "
                        + "is under %s%% for %d of the %d systems and penetrations, the null count aside. The learning "
                        + "curve %s for every count at every penetration.%n%n", states, sc.st.shoes,
                (double) states / sc.st.shoes, abs(BOUND_TARGET), under, systems,
                z.learningCurve ? "holds" : "does not hold");
    }

    /**
     * The calibration of the SEs: the shoes are split into parts disjoint sub-runs of equal
     * size (ScoringStates.subRun), each scored alone with its own bootstrap, and the spread
     * of each row's 1-8 same-schedule EV and difference from Hi-Lo across the sub-runs,
     * which is their sampling spread at that size, is set beside the mean over the sub-runs
     * of the bootstrap SE and of the per-shoe SE. With parts sub-runs the spread itself is
     * known to about 1 / sqrt(2 (parts - 1)) of its size.
     */
    static void calibration(Scored sc, List<CountKeys.Count> counts, int parts, int resamples, int threads,
                            PrintStream out) throws Exception {
        int s = SystemScorer.SPREADS;
        int p = SystemScorer.CUTS.length - 1;
        List<ScoringStates> subs = new ArrayList<>();
        List<List<SystemScorer.Result>> runs = new ArrayList<>();
        int subStates = 0;
        for (int k = 0; k < parts; k++) {
            ScoringStates sub = sc.st.subRun(k, parts);
            subStates += sub.size;
            SystemScorer scorer = new SystemScorer(sub, new Bootstrap(sub.shoes, resamples, Bootstrap.SEED),
                    SystemScorer.M_PLAY);
            subs.add(sub);
            runs.add(scoreAll(scorer, counts, threads));
        }
        out.println("## Calibration of the standard errors");
        out.println();
        out.printf("The shoes split into %d disjoint sub-runs of %d shoes, about %d valued states each, every one "
                        + "scored alone with %d resamples. For the 1-%d same-schedule EV at %s and its difference from "
                        + "Hi-Lo: the standard deviation of the estimates across the sub-runs, which is their "
                        + "sampling spread at that size and is itself known to about +-%.0f%%, beside the mean over the "
                        + "sub-runs of the bootstrap SE and of the per-shoe SE.%n%n", parts,
                subs.get(0).shoes, subStates / parts, resamples, s, SystemScorer.PENETRATIONS[p],
                100 / Math.sqrt(2.0 * (parts - 1)));
        out.println("| Row | EV spread | EV SE, bootstrap | EV SE, shoes | Diff spread | Diff SE, bootstrap | Diff SE, shoes |");
        out.println("|---|---|---|---|---|---|---|");
        List<Double> bootRatio = new ArrayList<>();
        List<Double> shoeRatio = new ArrayList<>();
        for (CalibrationRow row : calibrationRows(subs, runs)) {
            if (!row.isHiLo && row.diffSpread > 0) {
                bootRatio.add(row.diffBoot / row.diffSpread);
                shoeRatio.add(row.diffShoe / row.diffSpread);
            }
            out.printf("| %s | %s | %s | %s | %s | %s | %s |%n", row.name, abs(row.evSpread), abs(row.evBoot),
                    abs(row.evShoe), row.isHiLo ? "" : abs(row.diffSpread), row.isHiLo ? "" : abs(row.diffBoot),
                    row.isHiLo ? "" : abs(row.diffShoe));
        }
        out.println();
        out.printf("Over the differences, the median ratio of the mean SE to the spread is %.2f for the bootstrap and "
                + "%.2f for the per-shoe SE.%n%n", median(bootRatio), median(shoeRatio));
    }

    /** One row of the calibration, over the sub-runs. */
    static final class CalibrationRow {
        final String name;
        final boolean isHiLo;
        /** The standard deviation across the sub-runs of the 1-8 same-schedule EV at the deepest penetration. */
        double evSpread;
        /** The mean over the sub-runs of its bootstrap SE and of its per-shoe SE. */
        double evBoot;
        double evShoe;
        /** The same three for its difference from Hi-Lo, taken sub-run by sub-run; NaN for Hi-Lo. */
        double diffSpread = Double.NaN;
        double diffBoot = Double.NaN;
        double diffShoe = Double.NaN;

        CalibrationRow(String name, boolean isHiLo) {
            this.name = name;
            this.isHiLo = isHiLo;
        }
    }

    /**
     * The calibration's figures from sub-runs scored alone: subs.get(k) is a sub-run's states
     * and runs.get(k) its results, the null count first and Hi-Lo second, as scoreAll gives
     * them. A row's difference is taken from Hi-Lo in the same sub-run, playing the same way.
     * The null count's betting-only row is left out, as in the report's tables.
     */
    static List<CalibrationRow> calibrationRows(List<ScoringStates> subs, List<List<SystemScorer.Result>> runs) {
        int m = SystemScorer.same(SystemScorer.SPREADS);
        int p = SystemScorer.CUTS.length - 1;
        int parts = runs.size();
        List<CalibrationRow> out = new ArrayList<>();
        int rowsPerRun = 0;
        for (SystemScorer.Result r : runs.get(0)) {
            rowsPerRun += r.entries.size();
        }
        for (int row = 0; row < rowsPerRun; row++) {
            double[] ev = new double[parts];
            double[] evBoot = new double[parts];
            double[] evShoe = new double[parts];
            double[] d = new double[parts];
            double[] dBoot = new double[parts];
            double[] dShoe = new double[parts];
            SystemScorer.Entry first = null;
            for (int k = 0; k < parts; k++) {
                List<SystemScorer.Result> results = runs.get(k);
                SystemScorer.Entry e = entry(results, row);
                SystemScorer.Entry hiLo = baseline(results.get(1), e);
                first = first == null ? e : first;
                ev[k] = e.estimate[p][m];
                evBoot[k] = Bootstrap.interval(ev[k], e.boot[p][m]).se;
                double[] w = new double[e.shoeSums[p].length];
                ScoringStates sub = subs.get(k);
                for (int j = 0; j < sub.size; j++) {
                    if (sub.depth[j] < SystemScorer.CUTS[p]) {
                        w[sub.shoe[j]] += sub.weight[j];
                    }
                }
                evShoe[k] = shoeSE(e.shoeSums[p], w);
                d[k] = e.estimate[p][m] - hiLo.estimate[p][m];
                dBoot[k] = Bootstrap.interval(d[k], difference(e, hiLo, p, m, new double[e.boot[p][m].length])).se;
                double[] a = new double[w.length];
                for (int i = 0; i < a.length; i++) {
                    a[i] = e.shoeSums[p][i] - hiLo.shoeSums[p][i];
                }
                dShoe[k] = shoeSE(a, w);
            }
            if (runs.get(0).get(0).entries.contains(first) && first.bettingOnly) {
                continue;
            }
            CalibrationRow r = new CalibrationRow(first.name, first == baseline(runs.get(0).get(1), first));
            r.evSpread = sd(ev);
            r.evBoot = mean(evBoot);
            r.evShoe = mean(evShoe);
            if (!r.isHiLo) {
                r.diffSpread = sd(d);
                r.diffBoot = mean(dBoot);
                r.diffShoe = mean(dShoe);
            }
            out.add(r);
        }
        return out;
    }

    /** The row-th entry of a list of results, counting every entry of every result in order. */
    private static SystemScorer.Entry entry(List<SystemScorer.Result> results, int row) {
        for (SystemScorer.Result r : results) {
            if (row < r.entries.size()) {
                return r.entries.get(row);
            }
            row -= r.entries.size();
        }
        throw new IndexOutOfBoundsException();
    }

    static double mean(double[] x) {
        double sum = 0;
        for (double v : x) {
            sum += v;
        }
        return sum / x.length;
    }

    /** The sample standard deviation. */
    static double sd(double[] x) {
        double mean = mean(x);
        double ss = 0;
        for (double v : x) {
            ss += (v - mean) * (v - mean);
        }
        return x.length > 1 ? Math.sqrt(ss / (x.length - 1)) : Double.NaN;
    }

    private static double median(List<Double> x) {
        if (x.isEmpty()) {
            return Double.NaN;
        }
        double[] a = new double[x.size()];
        for (int k = 0; k < a.length; k++) {
            a[k] = x.get(k);
        }
        Arrays.sort(a);
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2;
    }

    private static void timing(Scored sc, PrintStream out) {
        out.println("## Time");
        out.println();
        out.printf("Reading and preparing the file took %.1f s, dealing its %d shoes again %.1f s, and scoring every "
                + "count with %d resamples %.1f s of wall time on %d thread%s. Each count's time to fit and score "
                + "(all three penetrations, its variants and the bootstrap) is its marginal cost:%n%n",
                sc.readSeconds, sc.st.shoes, sc.replaySeconds, sc.scorer.boot.resamples, sc.scoreSeconds, sc.threads,
                sc.threads == 1 ? "" : "s");
        out.println("| Count | Seconds |");
        out.println("|---|---|");
        double total = 0;
        for (SystemScorer.Result r : sc.results) {
            out.printf("| %s | %.1f |%n", r.count.name, r.seconds);
            total += r.seconds;
        }
        out.printf("| Mean per count | %.1f |%n%n", total / sc.results.size());
    }

    private static void keyTables(Scored sc, PrintStream out) {
        out.println("## Per key, from the test folds");
        out.println();
        out.println("For each bet key: its share of rounds, the mean round value there under the count's fitted play "
                + "and insurance, the advantage fitted on the training folds, the same-schedule bet at spreads 1-2, "
                + "1-4 and 1-8, and the own ramp's unrounded bet at 1-8; both folds pooled by test weight. Each "
                + "fold's fitted advantage rises with the key, but the two folds weigh a key differently, so the "
                + "pooled column can dip from one key to the next.");
        int s = SystemScorer.SPREADS;
        for (SystemScorer.Result r : sc.results) {
            for (SystemScorer.Entry e : r.entries) {
                if (e.bettingOnly || (r == sc.nullCount && e != r.entries.get(0))) {
                    continue;
                }
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    out.println();
                    out.printf("### %s, %s%n%n", e.name, SystemScorer.PENETRATIONS[p]);
                    out.println("| Key | Frequency | Mean RV | Fitted advantage | Same 1-2 | Same 1-4 | Same 1-8 | Own 1-8 |");
                    out.println("|---|---|---|---|---|---|---|---|");
                    for (double[] k : e.keys[p]) {
                        out.printf("| %d | %.4f | %s | %s | %.2f | %.2f | %.2f | %.2f |%n", (int) k[0], k[1], pct(k[2]),
                                pct(k[3]), k[3 + 2], k[3 + 4], k[3 + s], k[3 + s + s]);
                    }
                }
            }
        }
        out.println();
    }

    // -------------------------------------------------------------------- formatting

    static String pct(double x) {
        return Double.isNaN(x) ? "n/a" : String.format("%+.3f", 100 * x);
    }

    static String abs(double x) {
        return Double.isNaN(x) ? "n/a" : String.format("%.3f", 100 * x);
    }

    /** A number of valued states, or "not reached" for a bound that a longer run does not bring down. */
    static String states(double x) {
        return Double.isInfinite(x) ? "not reached" : Double.isNaN(x) ? "n/a" : String.format("%.0f", x);
    }

    /** A decision bound, a small positive per-round cost, as a percentage to four places. */
    static String bound(double x) {
        return Double.isNaN(x) ? "n/a" : String.format("%.4f", 100 * x);
    }

    /** A small change on the scale of the decision bound, signed, as a percentage to four places. */
    static String signedBound(double x) {
        return Double.isNaN(x) ? "n/a" : String.format("%+.4f", 100 * x);
    }

    static String ci(Bootstrap.Interval iv) {
        return "[" + pct(iv.lo) + ", " + pct(iv.hi) + "]" + (iv.basic ? " b" : "");
    }

    static String ratio(double x) {
        return Double.isNaN(x) || Double.isInfinite(x) ? "n/a" : String.format("%.3f", x);
    }

    static String ratioCi(Bootstrap.Interval iv) {
        return "[" + ratio(iv.lo) + ", " + ratio(iv.hi) + "]" + (iv.basic ? " b" : "");
    }
}
