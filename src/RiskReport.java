import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The swings, money per hour and risk of ruin of the results page (PLAN_COMPUTE.md section 2), as
 * runs/report-risk-SHA.md (every figure and every check) and runs/site/risk-SHA.json (what the
 * site generator renders, nothing else).
 *
 * Where each number comes from. The win rate a round, mu, is always the exact engine's: the same
 * number as the headline table, Entry.estimate[p][metric], with its 1,000 resamples
 * Entry.boot[p][metric][r] (PLAN_COMPUTE.md 2.1). The swings come from the independent
 * simulator: sigma, the SD of one round, from its exact histogram of rounds by bet and result,
 * and sigma_eff, the SD that scales with time, from its sums over whole shoes. The page never
 * sets a simulated win rate beside an exact one; the simulator's own mu is used only in its
 * checks. Every formula is RiskMath's, which says what each assumes.
 *
 * Rows: the 25 figures SIM_CHECK.md section 3 keeps swings for (ROWS), each read from the file
 * that holds its engine figure: c06c061's results.ser, step 7's report-ceiling-97bf834.ser (the
 * chart, a reference row), step 8's results.ser (F1 and F2 at full play), or the replay's wide.ser
 * (the wide plans; its reader comes with WideReport, so until that is merged those rows are
 * reported as not computed). A row whose source is not given is reported as not computed, never
 * filled from elsewhere.
 *
 * Uncertainty. N0, B_p, the risk of ruin at a bankroll, the trip risk and the chance of being
 * behind are computed on each of mu's resamples with sigma_eff held, and given as percentile
 * intervals (RiskMath.percentileInterval). A full-play mu holds its later plays as fitted on every
 * resample, so its resamples are first widened by its count's difference ratio from the pilot
 * (PLAN_PAGE.md 2.4's rule for a full-play level; levelRatios), and a full-play row is refused
 * without the pilot. A resample with mu at most 0 gives no upper bound; a row whose estimate is
 * at most 0 loses, and its ruin is certain for any bankroll.
 *
 * Checks, any failure of which writes nothing:
 * - every engine file holds its resamples (boot) and pairs with the others: the same states file,
 *   1,000 resamples from Bootstrap.SEED;
 * - every result in the simulator's histogram is inside RiskMath.twiceResultRange of the house
 *   rules, every bet is finite and at least 0;
 * - the histogram and the shoe sums describe the same rounds: its count equals the rounds dealt,
 *   and its total of b X equals the shoes' total within 1e-9 relative;
 * - the risk data file is at most 60 KB (PLAN_COMPUTE.md section 0).
 * A figure whose simulator test failed (|z| at least 3.5, SIM_CHECK.md section 7) is held back,
 * listed with its z, as SIM_CHECK.md section 13 says: its swings rest on rounds the check says
 * the simulator may not play as the engine does.
 *
 * Reported, not gates: the bets' moments beside the scorer's (D4 of SIM_CHECK.md holds the bets
 * equal at every state already); sigma_eff / sigma; the split rounds' share of rounds and of
 * E[(bX)^2]; and PLAN_COMPUTE.md 2.4's check 4, the formulas beside simulated play from the
 * per-shoe records: bankroll paths from B_25%, B_5% and B_1% (20,000 paths, seed 20261010,
 * stopped where the formula's remaining risk is under 5e-5) and consecutive blocks for trip risk
 * and the chance of being behind, each with the measured gap and its binomial SE.
 *
 * The simulator's files (formats fixed here, for the simulator's writer):
 * - swings.tsv, tab-separated, lines by kind, # for a comment:
 *   {@code shoes FIG S sumY sumY2 sumY3 sumY4 sumN sumN2 sumYN} (N every round dealt);
 *   {@code hist FIG BET TWOX COUNT SPLITS} one line a cell, BET as Java prints a double (a sat-out
 *   round is a cell of bet 0), TWOX twice the result for a unit bet, SPLITS how many of COUNT were
 *   rounds the player split in;
 *   {@code z FIG CHECK Z} for each of the figure's tests (P3, and P2 at 80%).
 * - records-FIG.tsv, one shoe a line in shoe order: {@code Y N M}, M the shoe's lowest running total.
 * - fan-FIG.tsv, one path a line: {@code J W100 W200 ... W100000}, the cumulative win every 100 rounds.
 *
 * java -Xmx3g -cp build/classes:lib/mongo-java-driver-3.12.14.jar RiskReport --commit SHA
 *      --source c06c061=runs/full-index/full-c06c061/results.ser --source step7=runs/report-ceiling-97bf834.ser
 *      [--source step8=PATH] [--source replay=PATH] --widening runs/full-index/pilot-1417-100.txt
 *      --swings DIR/swings.tsv [--records FIG=PATH ...] [--fan FIG=PATH] --md OUT.md --json OUT.json
 */
final class RiskReport {

    private RiskReport() {
    }

    /** The dollars a unit: the $25 table minimum the engine's rules come from (PLAN_COMPUTE.md 2.3). */
    static final double DOLLARS_A_UNIT = 25;
    /** Rounds an hour: PLAN_PAGE.md section 7's three precomputed choices, an assumption, none the norm. */
    static final int[] RATES = {50, 80, 100};
    /** Hours of play for trip risk and the chance of being behind. */
    static final int[] HOURS = {10, 100, 1000};
    /** The risks of ruin a bankroll is given for. */
    static final double[] RISKS = {0.01, 0.05, 0.25};
    /** Bankrolls in units for the risk-of-ruin table. */
    static final double[] BANKROLLS = {100, 200, 500, 1000, 2000, 5000};
    /** Bankrolls in units for the trip risk. */
    static final double[] TRIP_BANKROLLS = {200, 500, 1000};
    /** A simulator test fails at |z| of this or more (SIM_CHECK.md section 7). */
    static final double Z_FAIL = 3.5;
    /** The ruin paths of check 4 (SIM_CHECK.md section 11). */
    static final int RUIN_PATHS = 20_000;
    static final long RUIN_SEED = 20261010L;
    static final double STOP_RISK = 5e-5;
    /** The data file's budget, PLAN_COMPUTE.md section 0. */
    static final int JSON_BUDGET = 60 * 1024;
    /**
     * The expected finding of PLAN_COMPUTE.md 2.3: under the same schedule the counts swing alike.
     * The data file says it holds when the five counts' sigma_eff at full play, 1-8, 80% lie within
     * this share of their mean of each other; fixed here before any swing was measured.
     */
    static final double SAME_SWINGS = 0.05;
    /** The fan: points kept every this many rounds, of the simulator's every 100, each to the nearest unit. */
    static final int FAN_STEP = 1000;

    // ------------------------------------------------------------------ the rows

    /** One figure the page gives swings for: what it is, and where its engine figure is read. */
    static final class Spec {
        /** SIM_CHECK.md's figure id, which the simulator's files are keyed by. */
        final String figure;
        /** The count as the page names it. */
        final String count;
        /** The row's name in the results files (a count's name, or a reference row's). */
        final String entry;
        /** chart, first decisions, or full play. */
        final String play;
        /** 0, 1, 2 for 70, 75 and 80%. */
        final int pen;
        /** flat, 1-8, or a wide plan's label. */
        final String bets;
        /** c06c061, step7, step8 or replay: the engine file that holds the figure. */
        final String source;

        Spec(String figure, String count, String entry, String play, int pen, String bets, String source) {
            this.figure = figure;
            this.count = count;
            this.entry = entry;
            this.play = play;
            this.pen = pen;
            this.bets = bets;
            this.source = source;
        }

        boolean fullPlay() {
            return play.equals("full play");
        }

        /** The metric of SystemScorer's figures, for a row not on a wide plan. */
        int metric() {
            if (bets.equals("flat")) {
                return SystemScorer.FLAT;
            }
            if (bets.equals("1-8")) {
                return SystemScorer.same(8);
            }
            throw new IllegalStateException(figure + " is on a wide plan, " + bets);
        }

        String label() {
            return count + ", " + play + ", " + bets + ", " + PEN_NAMES[pen];
        }
    }

    static final String[] PEN_NAMES = {"70%", "75%", "80%"};

    static final String HI_LO = "Hi-Lo = Hi-Lo Lite";
    static final String KO = "KO (Knock-Out) = REKO (Ridiculously Easy Knock-Out)";
    static final String ZEN = "Zen Count";
    static final String HI_OPT = "Hi-Opt II + ace side count";
    static final String VICTOR = "Victor Advanced Point Count (Victor APC) + ace side count";
    static final String F1 = "F1 at 1-8 (f1-8-vidami-ar)";
    static final String F2 = "F2 at 1-8, play vector (f2-pub-victor-apc) + betting vector";
    static final String CHART = "Chart, no count, flat";

    /**
     * The 25 figures, in SIM_CHECK.md's order. The chart's flat figure is a reference row, which
     * c06c061's results.ser does not hold (FullIndexRun writes only the counts' Results); step 7
     * scored it on the same states and resamples, so it is read from there.
     */
    static final List<Spec> ROWS = rows();

    private static List<Spec> rows() {
        List<Spec> r = new ArrayList<>();
        r.add(new Spec("F03", "chart", CHART, "chart", 2, "flat", "step7"));
        r.add(new Spec("F18", "Hi-Lo", HI_LO, "first decisions", 2, "1-8", "c06c061"));
        r.add(new Spec("F27", "Hi-Lo", HI_LO, "full play", 2, "1-8", "c06c061"));
        r.add(new Spec("F29", "Victor APC + ace", VICTOR, "full play", 2, "1-8", "c06c061"));
        String[][] five = {{"Hi-Lo", HI_LO}, {"KO", KO}, {"Zen", ZEN}, {"Hi-Opt II + ace", HI_OPT},
                {"Victor APC + ace", VICTOR}};
        int f = 30;
        for (int p = 0; p < 2; p++) {
            for (String[] c : five) {
                r.add(new Spec(String.format("F%02d", f++), c[0], c[1], "full play", p, "1-8", "c06c061"));
            }
        }
        for (int c = 1; c <= 3; c++) {
            r.add(new Spec(String.format("F%02d", f++), five[c][0], five[c][1], "full play", 2, "1-8", "c06c061"));
        }
        for (String[] c : new String[][]{five[0], five[4]}) {
            for (String plan : new String[]{"P16", "B0,8", "X-1,8"}) {
                r.add(new Spec(String.format("F%02d", f++), c[0], c[1], "full play", 2, plan, "replay"));
            }
        }
        r.add(new Spec("F50", "F1", F1, "full play", 2, "1-8", "step8"));
        r.add(new Spec("F52", "F2", F2, "full play", 2, "1-8", "step8"));
        return java.util.Collections.unmodifiableList(r);
    }

    static Spec spec(String figure) {
        for (Spec s : ROWS) {
            if (s.figure.equals(figure)) {
                return s;
            }
        }
        throw new IllegalArgumentException("no risk row is figure " + figure);
    }

    // ------------------------------------------------------------------ the engine's side

    /** A row's win rate from the engine: the estimate, its resamples as used, and the bets' moments. */
    static final class Mu {
        final double estimate;
        /** The resamples, widened where the row is full play. */
        final double[] boot;
        /** The widening ratio (1 for a row that is not full play) and how it was found. */
        final double widen;
        final String widenBasis;
        /** The scorer's average bet, Entry.estimate[p][sameBet(8)] (1 when flat). */
        final double meanBet;
        /**
         * The mean square of the bets from the per-key table, sum of share x bet^2 with each key's
         * bet pooled over the folds by test weight (1 when flat). Pooling averages the two folds'
         * bets before squaring, so this is at most the mean square of the bets actually placed;
         * the replay's figures carry that one exactly (PLAN_COMPUTE.md 3.2).
         */
        final double meanBetSquaredPooled;

        Mu(double estimate, double[] boot, double widen, String widenBasis, double meanBet, double meanBetSquaredPooled) {
            this.estimate = estimate;
            this.boot = boot;
            this.widen = widen;
            this.widenBasis = widenBasis;
            this.meanBet = meanBet;
            this.meanBetSquaredPooled = meanBetSquaredPooled;
        }
    }

    /** The engine's files, read once each: their identities and the rows they hold. */
    static final class Engine {
        final Map<String, String> identity = new LinkedHashMap<>();
        final Map<String, Path> files = new LinkedHashMap<>();
        /** For c06c061 and step8: every count's Result. */
        final Map<String, List<SystemScorer.Result>> results = new LinkedHashMap<>();
        /** For step7: the reference rows. */
        List<SystemScorer.Entry> references;
        /** Each count's difference ratio by metric name, from the pilot; null without one. */
        Map<String, Map<String, Double>> ratios;
        String pilot;

        /** A line of a source's identity that starts so, without the start; null if none. */
        String line(String source, String start) {
            for (String l : identity.get(source).split("\n")) {
                if (l.startsWith(start)) {
                    return l.substring(start.length());
                }
            }
            return null;
        }
    }

    /**
     * Reads the sources given, by name: c06c061 and step8 through FullIndexPeek.read, step7
     * through CeilingReport.readSaved (its reference rows). Each must pair with the others: the
     * same states file, 1,000 resamples from Bootstrap.SEED. The replay's wide.ser is read by
     * WideReport, which is not on this branch, so it is refused here rather than read some other
     * way.
     */
    static Engine readEngine(Map<String, Path> sources, Path pilot) throws IOException, ClassNotFoundException {
        Engine e = new Engine();
        for (Map.Entry<String, Path> s : sources.entrySet()) {
            String name = s.getKey();
            Path file = s.getValue();
            switch (name) {
                case "c06c061":
                case "step8": {
                    FullIndexPeek.Stored st = FullIndexPeek.read(file);
                    e.identity.put(name, st.identity);
                    e.results.put(name, st.results);
                    break;
                }
                case "step7": {
                    CeilingReport.Saved saved = CeilingReport.readSaved(file);
                    e.identity.put(name, saved.identity);
                    e.references = saved.references;
                    break;
                }
                case "replay":
                    throw new IllegalArgumentException("the replay's wide.ser is read by WideReport (branch site-wide); "
                            + "merge it before giving --source replay");
                default:
                    throw new IllegalArgumentException("no source is called " + name);
            }
            e.files.put(name, file);
        }
        requirePaired(e);
        if (pilot != null) {
            e.ratios = levelRatios(Files.readAllLines(pilot, StandardCharsets.UTF_8));
            e.pilot = pilot.toString();
        }
        return e;
    }

    /** Refuses sources that were not scored on the same states with the same resamples. */
    static void requirePaired(Engine e) {
        String states = null;
        List<String> wrong = new ArrayList<>();
        for (String source : e.identity.keySet()) {
            String s = e.line(source, "states ");
            String r = e.line(source, "resamples ");
            if (s == null) {
                wrong.add(source + " names no states file");
            } else if (states == null) {
                states = s;
            } else if (!states.equals(s)) {
                wrong.add(source + " was scored on " + s + ", not " + states);
            }
            if (r == null || !r.startsWith(Bootstrap.RESAMPLES + ", seed " + Bootstrap.SEED)) {
                wrong.add(source + " has resamples " + r + ", not " + Bootstrap.RESAMPLES + ", seed " + Bootstrap.SEED);
            }
        }
        if (!wrong.isEmpty()) {
            throw new IllegalStateException("the engine's files do not pair: " + String.join("; ", wrong));
        }
    }

    /**
     * Each count's difference ratio, refitted SE over held SE, from the "## Results" section of
     * a full-index pilot's output, by metric ("1-8 same schedule", "flat") and count name: the
     * tables FullIndexPilot.printSes writes. The same tables WideReport reads with
     * FullIndexPilot.readRatios on branch site-wide; this reads only the difference tables.
     */
    static Map<String, Map<String, Double>> levelRatios(List<String> lines) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        java.util.regex.Pattern diff = java.util.regex.Pattern.compile(
                "^Difference from Hi-Lo at 80%, (.+), over \\d+ resamples .*:$");
        boolean results = false;
        String metric = null;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                results = line.equals("## Results");
                metric = null;
                continue;
            }
            if (!results) {
                continue;
            }
            java.util.regex.Matcher m = diff.matcher(line);
            if (m.matches()) {
                metric = m.group(1);
                continue;
            }
            if (!line.startsWith("|")) {
                // A line of words between tables ends the one being read; a blank line does not.
                if (!line.isEmpty()) {
                    metric = null;
                }
                continue;
            }
            if (metric == null || line.startsWith("| Count") || line.startsWith("|---")) {
                continue;
            }
            String[] cells = line.substring(1, line.lastIndexOf('|')).split("\\|");
            if (cells.length != 7) {
                throw new IllegalArgumentException("a difference row of " + cells.length + " cells: " + line);
            }
            out.computeIfAbsent(metric, x -> new LinkedHashMap<>()).put(cells[0].trim(), Double.parseDouble(cells[5].trim()));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("the pilot's output holds no difference ratios in a Results section");
        }
        return out;
    }

    /**
     * The ratio a full-play level of this count is widened by, at least 1, and how it was found:
     * its own difference ratio in the row's metric, or, for a count the pilot did not measure,
     * the largest difference ratio it measured in either metric (PLAN_PAGE.md 2.4; FULL_INDEX_PLAYS.md
     * section 4's default for a count without its own).
     */
    static Object[] widening(Engine e, String count, String metric) {
        Map<String, Double> by = e.ratios.get(metric);
        Double own = by == null ? null : by.get(count);
        if (own != null && !Double.isNaN(own)) {
            return new Object[]{Math.max(1, own), "its own, " + metric + ", from " + e.pilot};
        }
        double most = 1;
        for (Map<String, Double> m : e.ratios.values()) {
            for (double r : m.values()) {
                most = Math.max(most, r);
            }
        }
        return new Object[]{most, "not measured: the largest difference ratio the pilot measured, from " + e.pilot};
    }

    /** The row's entry in its source: exactly one, playing as the row says, holding its resamples. */
    static Object[] find(Engine e, Spec s) {
        List<Object[]> found = new ArrayList<>();
        if (s.source.equals("step7")) {
            for (SystemScorer.Entry x : e.references) {
                if (x.name.equals(s.entry)) {
                    found.add(new Object[]{x, null});
                }
            }
        } else {
            String name = s.fullPlay() ? s.entry + ", full play" : s.entry;
            for (SystemScorer.Result r : e.results.get(s.source)) {
                for (SystemScorer.Entry x : s.fullPlay() ? r.fullEntries : r.entries) {
                    if (x.name.equals(name) && !x.bettingOnly && !x.ownValue) {
                        found.add(new Object[]{x, r});
                    }
                }
            }
        }
        if (found.size() != 1) {
            throw new IllegalStateException(s.figure + ": " + found.size() + " rows named " + s.entry + " (" + s.play
                    + ") in " + s.source + ", not one");
        }
        return found.get(0);
    }

    /** The row's win rate: estimate, resamples (widened if full play), and the scorer's bets. */
    static Mu mu(Engine e, Spec s) {
        Object[] f = find(e, s);
        SystemScorer.Entry x = (SystemScorer.Entry) f[0];
        int p = s.pen;
        int m = s.metric();
        if (x.boot == null || x.boot[p] == null || x.boot[p][m] == null || x.boot[p][m].length != Bootstrap.RESAMPLES) {
            throw new IllegalStateException(s.figure + ": " + s.source + " holds no resamples for " + x.name
                    + " at " + PEN_NAMES[p] + "; a file without boot is refused");
        }
        double[] boot = x.boot[p][m];
        double widen = 1;
        String basis = "not widened: " + s.play + " refits nothing per resample that it plays";
        if (s.fullPlay()) {
            if (e.ratios == null) {
                throw new IllegalStateException(s.figure + " is full play: its interval needs the pilot's widening (--widening)");
            }
            SystemScorer.Result r = (SystemScorer.Result) f[1];
            Object[] w = widening(e, r.count.name, s.bets.equals("flat") ? "flat" : "1-8 same schedule");
            widen = (Double) w[0];
            basis = (String) w[1];
            boot = Bootstrap.widen(boot, widen);
        }
        double meanBet = 1;
        double meanBetSquared = 1;
        if (!s.bets.equals("flat")) {
            meanBet = x.estimate[p][SystemScorer.sameBet(8)];
            meanBetSquared = 0;
            for (double[] k : x.keys[p]) {
                meanBetSquared += k[1] * k[3 + 8] * k[3 + 8];
            }
        }
        return new Mu(x.estimate[p][m], boot, widen, basis, meanBet, meanBetSquared);
    }

    // ------------------------------------------------------------------ the simulator's side

    /** One figure's swings from the simulator: its shoe sums, its histogram and its tests' z. */
    static final class Swing {
        final String figure;
        RiskMath.ShoeSums sums;
        final List<Double> bet = new ArrayList<>();
        final List<Integer> twoX = new ArrayList<>();
        final List<Long> count = new ArrayList<>();
        final List<Long> splits = new ArrayList<>();
        final Map<String, Double> z = new LinkedHashMap<>();

        Swing(String figure) {
            this.figure = figure;
        }

        double[] bets() {
            return bet.stream().mapToDouble(Double::doubleValue).toArray();
        }

        int[] twoXs() {
            return twoX.stream().mapToInt(Integer::intValue).toArray();
        }

        long[] counts() {
            return count.stream().mapToLong(Long::longValue).toArray();
        }
    }

    /** Reads swings.tsv in the format the class comment fixes; refuses anything else in it. */
    static Map<String, Swing> readSwings(List<String> lines) {
        Map<String, Swing> out = new TreeMap<>();
        java.util.Set<String> cells = new java.util.HashSet<>();
        int at = 0;
        for (String line : lines) {
            at++;
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            String where = "swings line " + at + ": ";
            if (f.length < 2) {
                throw new IllegalArgumentException(where + line);
            }
            Swing s = out.computeIfAbsent(f[1], Swing::new);
            switch (f[0]) {
                case "shoes":
                    if (f.length != 10 || s.sums != null) {
                        throw new IllegalArgumentException(where + (s.sums != null ? "a second shoes line" : line));
                    }
                    s.sums = new RiskMath.ShoeSums(Long.parseLong(f[2]), Double.parseDouble(f[3]), Double.parseDouble(f[4]),
                            Double.parseDouble(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7]),
                            Double.parseDouble(f[8]), Double.parseDouble(f[9]));
                    break;
                case "hist": {
                    if (f.length != 6) {
                        throw new IllegalArgumentException(where + line);
                    }
                    long n = Long.parseLong(f[4]);
                    long sp = Long.parseLong(f[5]);
                    if (sp < 0 || sp > n || !cells.add(f[1] + " " + f[2] + " " + f[3])) {
                        throw new IllegalArgumentException(where + (sp < 0 || sp > n ? "splits outside 0 to the count" : "a cell twice"));
                    }
                    s.bet.add(Double.parseDouble(f[2]));
                    s.twoX.add(Integer.parseInt(f[3]));
                    s.count.add(n);
                    s.splits.add(sp);
                    break;
                }
                case "z":
                    if (f.length != 4 || !(f[2].equals("P3") || f[2].equals("P2")) || s.z.containsKey(f[2])) {
                        throw new IllegalArgumentException(where + line);
                    }
                    s.z.put(f[2], Double.parseDouble(f[3]));
                    break;
                default:
                    throw new IllegalArgumentException(where + "no line is of kind " + f[0]);
            }
        }
        return out;
    }

    /** Reads a records file, Y N M a shoe a line in shoe order. */
    static RiskMath.Records readRecords(Path file) throws IOException {
        double[] y = new double[1 << 16];
        int[] n = new int[1 << 16];
        double[] m = new double[1 << 16];
        int k = 0;
        try (java.io.BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line; (line = in.readLine()) != null; ) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t");
                if (f.length != 3) {
                    throw new IllegalArgumentException(file + ", shoe " + k + ": " + line);
                }
                if (k == y.length) {
                    y = java.util.Arrays.copyOf(y, 2 * k);
                    n = java.util.Arrays.copyOf(n, 2 * k);
                    m = java.util.Arrays.copyOf(m, 2 * k);
                }
                y[k] = Double.parseDouble(f[0]);
                n[k] = Integer.parseInt(f[1]);
                m[k] = Double.parseDouble(f[2]);
                k++;
            }
        }
        return new RiskMath.Records(java.util.Arrays.copyOf(y, k), java.util.Arrays.copyOf(n, k), java.util.Arrays.copyOf(m, k));
    }

    /** The fan's paths: 20 lines, J then the cumulative win every 100 rounds to 100,000. */
    static double[][] readFan(Path file) throws IOException {
        List<double[]> paths = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t");
            if (f.length != 1001 || Integer.parseInt(f[0]) != paths.size()) {
                throw new IllegalArgumentException(file + ": path " + paths.size() + " is not J and 1,000 points");
            }
            double[] p = new double[1000];
            for (int i = 0; i < p.length; i++) {
                p[i] = Double.parseDouble(f[i + 1]);
            }
            paths.add(p);
        }
        if (paths.size() != 20) {
            throw new IllegalArgumentException(file + " holds " + paths.size() + " paths, not 20");
        }
        return paths.toArray(new double[0][]);
    }

    // ------------------------------------------------------------------ the figures

    /**
     * The figures every computed row carries, in this order, by key; the data file gives the keys
     * once (layout) and each row's figures in the same order. ".usd" is the same figure at
     * DOLLARS_A_UNIT dollars a unit.
     */
    static List<String> layout() {
        List<String> k = new ArrayList<>();
        k.add("ev.per100");
        k.add("sd.per100");
        k.add("ev.per100.usd");
        k.add("sd.per100.usd");
        for (int r : RATES) {
            k.add("ev.hour." + r);
            k.add("sd.hour." + r);
            k.add("ev.hour." + r + ".usd");
            k.add("sd.hour." + r + ".usd");
        }
        k.add("n0");
        for (int r : RATES) {
            k.add("n0.hours." + r);
        }
        for (double p : RISKS) {
            k.add("bankroll." + pct(p));
            k.add("bankroll." + pct(p) + ".usd");
        }
        for (double b : BANKROLLS) {
            k.add("ror." + (int) b);
        }
        for (int h : HOURS) {
            for (int r : RATES) {
                k.add("behind." + h + "h." + r);
            }
        }
        for (double b : TRIP_BANKROLLS) {
            for (int h : HOURS) {
                for (int r : RATES) {
                    k.add("trip." + (int) b + "." + h + "h." + r);
                }
            }
        }
        return k;
    }

    static String pct(double p) {
        return (int) Math.round(100 * p) + "%";
    }

    /** One row as computed: what it is, its status, and its figures. */
    static final class Row {
        final Spec spec;
        /** "computed", or why the row has no figures. */
        String status = "computed";
        Mu mu;
        Swing swing;
        RiskMath.Moments moments;
        double sigma;
        double sigmaEff;
        double sigmaEffSe;
        double simMu;
        double splitRounds;
        double splitSquare;
        /** [figure in layout order]: value, and its 95% percentile interval (NaN where none). */
        double[][] figures;

        Row(Spec spec) {
            this.spec = spec;
        }

        boolean computed() {
            return status.equals("computed");
        }

        boolean loses() {
            return computed() && !(mu.estimate > 0);
        }
    }

    /** value and its percentile interval over mu's resamples, sigma_eff held. */
    private static double[] fig(Mu mu, java.util.function.DoubleUnaryOperator f) {
        double[] ci = RiskMath.percentileInterval(mu.boot, f);
        return new double[]{f.applyAsDouble(mu.estimate), ci[0], ci[1]};
    }

    private static double[] fixed(double v) {
        return new double[]{v, Double.NaN, Double.NaN};
    }

    private static double[] times(double[] f, double k) {
        return new double[]{f[0] * k, f[1] * k, f[2] * k};
    }

    /** Every figure of the layout for a row whose mu and sigma_eff are known. */
    static double[][] figures(Mu mu, double sigmaEff) {
        List<double[]> out = new ArrayList<>();
        double[] ev100 = fig(mu, x -> RiskMath.evOver(x, 100));
        double[] sd100 = fixed(RiskMath.sdOver(sigmaEff, 100));
        out.add(ev100);
        out.add(sd100);
        out.add(times(ev100, DOLLARS_A_UNIT));
        out.add(times(sd100, DOLLARS_A_UNIT));
        for (int r : RATES) {
            double[] ev = fig(mu, x -> RiskMath.evOver(x, r));
            double[] sd = fixed(RiskMath.sdOver(sigmaEff, r));
            out.add(ev);
            out.add(sd);
            out.add(times(ev, DOLLARS_A_UNIT));
            out.add(times(sd, DOLLARS_A_UNIT));
        }
        double[] n0 = fig(mu, x -> RiskMath.n0(x, sigmaEff));
        out.add(n0);
        for (int r : RATES) {
            out.add(times(n0, 1.0 / r));
        }
        for (double p : RISKS) {
            double[] b = fig(mu, x -> RiskMath.bankrollFor(p, x, sigmaEff));
            out.add(b);
            out.add(times(b, DOLLARS_A_UNIT));
        }
        for (double b : BANKROLLS) {
            out.add(fig(mu, x -> RiskMath.ruin(x, sigmaEff, b)));
        }
        for (int h : HOURS) {
            for (int r : RATES) {
                out.add(fig(mu, x -> RiskMath.behind(x, sigmaEff, (double) h * r)));
            }
        }
        for (double b : TRIP_BANKROLLS) {
            for (int h : HOURS) {
                for (int r : RATES) {
                    out.add(fig(mu, x -> RiskMath.tripRuin(x, sigmaEff, b, (double) h * r)));
                }
            }
        }
        return out.toArray(new double[0][]);
    }

    // ------------------------------------------------------------------ the run's arithmetic and checks

    /** PLAN_COMPUTE.md 2.4's check 4 on one figure's per-shoe records. */
    static final class RuinCheck {
        final String figure;
        final RiskMath.ShoeSums sums;
        final double mu;
        final double sigma;
        /** B_25%, B_5% and B_1% at the records' own mu and sigma_eff, or null when the records lose. */
        double[] bankrolls;
        RiskMath.RuinPaths paths;
        /** Blocks of H r rounds, by HOURS then RATES. */
        final List<RiskMath.Blocks> blocks = new ArrayList<>();
        double seconds;

        RuinCheck(String figure, RiskMath.ShoeSums sums) {
            this.figure = figure;
            this.sums = sums;
            this.mu = sums.rate();
            this.sigma = RiskMath.sigmaEff(sums);
        }
    }

    /** Everything the report and the data file give. */
    static final class Outcome {
        final List<Row> rows = new ArrayList<>();
        /** Failed checks: any one and nothing is written. */
        final List<String> failures = new ArrayList<>();
        /** What was checked and passed, a line each, for the report. */
        final List<String> passed = new ArrayList<>();
        final List<RuinCheck> ruin = new ArrayList<>();
        int[] range;
        String fanFigure;
        /** The fan's paths at every FAN_STEP rounds, and the bands' centre and one SD at the same points. */
        double[][] fan;
        double[] fanMean;
        double[] fanSd;
        /** sigma_eff's and mu's spread over the five counts at full play, 1-8, 80%, over their means; NaN until all five are in. */
        double sigmaSpread = Double.NaN;
        double muSpread = Double.NaN;
    }

    /** The five counts at full play, 1-8, 80%, whose swings the expected finding compares. */
    static final String[] FIVE_AT_80 = {"F27", "F40", "F41", "F42", "F29"};

    static Outcome compute(Engine e, Map<String, Swing> swings, Map<String, RiskMath.Records> records, String fanFigure,
                           double[][] fan) {
        Outcome o = new Outcome();
        o.range = RiskMath.twiceResultRange(RoundRules.from(HouseRules.getMtlCasino25MinBlackjackParams(80)));
        for (String f : swings.keySet()) {
            spec(f);
        }
        for (Spec s : ROWS) {
            Row row = new Row(s);
            o.rows.add(row);
            Swing sw = swings.get(s.figure);
            if (!e.identity.containsKey(s.source)) {
                row.status = "not computed: its engine file (" + s.source + ") was not given";
                continue;
            }
            if (sw == null || sw.sums == null || sw.count.isEmpty()) {
                row.status = "not computed: the simulator's swings hold " + (sw == null ? "nothing" : sw.sums == null
                        ? "no shoe sums" : "no histogram") + " for " + s.figure;
                continue;
            }
            row.mu = mu(e, s);
            row.swing = sw;
            if (!sw.z.containsKey("P3") || (s.pen == 2 && !sw.z.containsKey("P2"))) {
                o.failures.add(s.figure + ": the swings file lacks its " + (sw.z.containsKey("P3") ? "P2" : "P3") + " test's z");
                row.status = "failed its checks";
                continue;
            }
            if (checkHistogram(o, s, sw)) {
                row.status = "failed its checks";
                continue;
            }
            for (Map.Entry<String, Double> z : sw.z.entrySet()) {
                if (!(Math.abs(z.getValue()) < Z_FAIL)) {
                    row.status = "held back: its " + z.getKey() + " test gave z = " + String.format("%+.2f", z.getValue())
                            + " (SIM_CHECK.md section 13)";
                }
            }
            row.moments = RiskMath.moments(sw.bets(), sw.twoXs(), sw.counts());
            row.sigma = row.moments.sd();
            row.sigmaEff = RiskMath.sigmaEff(sw.sums);
            row.sigmaEffSe = RiskMath.sigmaEffSe(sw.sums);
            row.simMu = sw.sums.rate();
            double splitRounds = 0;
            double square = 0;
            double splitSquare = 0;
            for (int i = 0; i < sw.count.size(); i++) {
                double z = sw.bet.get(i) * sw.twoX.get(i) / 2;
                splitRounds += sw.splits.get(i);
                square += sw.count.get(i) * z * z;
                splitSquare += sw.splits.get(i) * z * z;
            }
            row.splitRounds = splitRounds / row.moments.rounds;
            row.splitSquare = square == 0 ? 0 : splitSquare / square;
            if (row.computed()) {
                row.figures = figures(row.mu, row.sigmaEff);
            }
        }
        spreads(o);
        for (Map.Entry<String, RiskMath.Records> r : records.entrySet()) {
            spec(r.getKey());
            o.ruin.add(ruinCheck(r.getKey(), r.getValue()));
        }
        if (fan != null) {
            fan(o, fanFigure, fan);
        }
        return o;
    }

    /** The histogram's checks for one figure; true if one failed. */
    private static boolean checkHistogram(Outcome o, Spec s, Swing sw) {
        int before = o.failures.size();
        long rounds = 0;
        for (int i = 0; i < sw.count.size(); i++) {
            int x = sw.twoX.get(i);
            double b = sw.bet.get(i);
            if (x < o.range[0] || x > o.range[1]) {
                o.failures.add(s.figure + ": a result of 2X = " + x + " is outside the rules' range [" + o.range[0] + ", "
                        + o.range[1] + "]");
            }
            if (!(b >= 0) || Double.isInfinite(b)) {
                o.failures.add(s.figure + ": a bet of " + b);
            }
            rounds += sw.count.get(i);
        }
        if (o.failures.size() > before) {
            return true;
        }
        if (rounds != sw.sums.sumN) {
            o.failures.add(s.figure + ": the histogram holds " + rounds + " rounds, the shoe sums " + sw.sums.sumN);
            return true;
        }
        RiskMath.Moments m = RiskMath.moments(sw.bets(), sw.twoXs(), sw.counts());
        if (!(Math.abs(m.mean - sw.sums.rate()) <= 1e-9 * Math.sqrt(m.meanSquare))) {
            o.failures.add(s.figure + ": the histogram's win a round, " + m.mean + ", is not the shoe sums', "
                    + sw.sums.rate());
            return true;
        }
        o.passed.add(s.figure + ": " + rounds + " rounds, every result inside [" + o.range[0] + ", " + o.range[1]
                + "] halves and every bet at least 0; the histogram's rounds and win a round are the shoe sums'");
        return false;
    }

    /** The expected finding's figures: how far the five counts' sigma_eff and mu spread at 80%. */
    private static void spreads(Outcome o) {
        double[] sig = new double[FIVE_AT_80.length];
        double[] mu = new double[FIVE_AT_80.length];
        for (int k = 0; k < FIVE_AT_80.length; k++) {
            Row r = null;
            for (Row x : o.rows) {
                if (x.spec.figure.equals(FIVE_AT_80[k])) {
                    r = x;
                }
            }
            if (r == null || !r.computed()) {
                return;
            }
            sig[k] = r.sigmaEff;
            mu[k] = r.mu.estimate;
        }
        o.sigmaSpread = spread(sig);
        o.muSpread = spread(mu);
    }

    /** (largest - smallest) / mean. */
    static double spread(double[] v) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        double sum = 0;
        for (double x : v) {
            lo = Math.min(lo, x);
            hi = Math.max(hi, x);
            sum += x;
        }
        return (hi - lo) / (sum / v.length);
    }

    /** Check 4 on one figure's records: the bankroll paths and the blocks, timed. */
    static RuinCheck ruinCheck(String figure, RiskMath.Records rec) {
        long t0 = System.nanoTime();
        RuinCheck c = new RuinCheck(figure, rec.sums());
        if (c.mu > 0) {
            double[] b = new double[RISKS.length];
            for (int k = 0; k < b.length; k++) {
                b[k] = RiskMath.bankrollFor(RISKS[RISKS.length - 1 - k], c.mu, c.sigma);
            }
            c.bankrolls = b;
            c.paths = RiskMath.ruinPaths(rec, c.mu, c.sigma, b, RUIN_PATHS, RUIN_SEED, STOP_RISK, 100_000_000L);
        }
        for (int h : HOURS) {
            for (int r : RATES) {
                c.blocks.add(RiskMath.blocks(rec, (long) h * r, TRIP_BANKROLLS, c.mu, c.sigma));
            }
        }
        c.seconds = (System.nanoTime() - t0) / 1e9;
        return c;
    }

    /** The fan: every FAN_STEP rounds of the 20 paths, and the bands at the row's exact mu and sigma_eff. */
    private static void fan(Outcome o, String figure, double[][] fan) {
        Row row = null;
        for (Row r : o.rows) {
            if (r.spec.figure.equals(figure)) {
                row = r;
            }
        }
        if (row == null || !row.computed()) {
            o.failures.add("the fan's figure " + figure + " has no computed row to draw its bands from");
            return;
        }
        int every = FAN_STEP / 100;
        int points = fan[0].length / every;
        o.fanFigure = figure;
        o.fan = new double[fan.length][points];
        o.fanMean = new double[points];
        o.fanSd = new double[points];
        for (int i = 0; i < points; i++) {
            double t = (double) FAN_STEP * (i + 1);
            for (int j = 0; j < fan.length; j++) {
                o.fan[j][i] = fan[j][(i + 1) * every - 1];
            }
            o.fanMean[i] = RiskMath.evOver(row.mu.estimate, t);
            o.fanSd[i] = RiskMath.sdOver(row.sigmaEff, t);
        }
    }

    // ------------------------------------------------------------------ the report

    private static String ci(double[] f, String fmt) {
        String v = String.format(fmt, f[0]);
        if (Double.isNaN(f[1])) {
            return v;
        }
        String hi = f[2] == Double.POSITIVE_INFINITY ? "no upper bound" : String.format(fmt, f[2]);
        return v + " (" + String.format(fmt, f[1]) + " to " + hi + ")";
    }

    private static double[] at(Row r, String key) {
        return r.figures[LAYOUT.indexOf(key)];
    }

    static final List<String> LAYOUT = java.util.Collections.unmodifiableList(layout());

    static void print(PrintStream out, Outcome o, Engine e, String commit, Map<String, String> inputs) {
        out.println("# Swings, money per hour and risk of ruin");
        out.println();
        out.printf("RiskReport at commit %s, written %s. PLAN_COMPUTE.md section 2; every formula is RiskMath's.%n", commit,
                java.time.LocalDate.now());
        out.println();
        out.println("Inputs and their SHA-256:");
        out.println();
        for (Map.Entry<String, String> i : inputs.entrySet()) {
            out.printf("- `%s` %s%n", i.getKey(), i.getValue());
        }
        out.println();
        out.println("mu, the win a round, is the exact engine's (the headline table's figure); sigma and sigma_eff come from "
                + "the simulator. Units are the minimum bet; money is at $" + (int) DOLLARS_A_UNIT + " a unit. Intervals are "
                + "the 2.5 and 97.5 percentiles over mu's 1,000 resamples with sigma_eff held; a full-play mu's resamples are "
                + "first widened by its count's difference ratio. Assumptions of every formula over time: rounds add like "
                + "independent shoes with a fixed mean and variance, play has no end (or ends at T for a trip), steps are "
                + "small next to the bankroll, the unit is never resized, every fitted play is made without a mistake, and "
                + "the rounds an hour are the reader's choice (" + RATES[0] + ", " + RATES[1] + " or " + RATES[2]
                + "), none presented as the norm.");
        out.println();
        out.println("## Rows");
        out.println();
        out.println("| Figure | Row | mu, % a round (95% percentile) | widened | sigma | sigma_eff (SE) | sigma_eff / sigma | E[b] sim, scorer | "
                + "E[b^2] sim, scorer pooled | splits: rounds, E[(bX)^2] | z |");
        out.println("|---|---|---|---|---|---|---|---|---|---|---|");
        for (Row r : o.rows) {
            if (r.moments == null) {
                out.printf("| %s | %s | %s |  |  |  |  |  |  |  |  |%n", r.spec.figure, r.spec.label(), r.status);
                continue;
            }
            double[] pc = RiskMath.percentileInterval(r.mu.boot, x -> 100 * x);
            double[] mu = {100 * r.mu.estimate, pc[0], pc[1]};
            out.printf("| %s | %s%s | %s | %.2f | %.4f | %.4f (%.4f) | %.4f | %.4f, %.4f | %.4f, %.4f | %.4f, %.4f | %s |%n",
                    r.spec.figure, r.spec.label(), r.computed() ? (r.loses() ? " (loses)" : "") : " (" + r.status + ")",
                    ci(mu, "%+.4f"), r.mu.widen, r.sigma, r.sigmaEff, r.sigmaEffSe, r.sigmaEff / r.sigma, r.moments.meanBet,
                    r.mu.meanBet, r.moments.meanBetSquared, r.mu.meanBetSquaredPooled, r.splitRounds, r.splitSquare,
                    r.swing.z.toString());
        }
        out.println();
        out.println("Widening, by row: " + widenings(o));
        out.println();
        out.println("## Per 100 rounds and per hour");
        out.println();
        out.println("| Figure | Row | EV / 100 rounds | SD / 100 rounds | " + String.join(" | ", hourHeads()) + " |");
        out.println("|---|---|---|---|" + "---|".repeat(2 * RATES.length));
        for (Row r : o.rows) {
            if (!r.computed()) {
                continue;
            }
            StringBuilder b = new StringBuilder();
            b.append(String.format("| %s | %s | %s | %.2f", r.spec.figure, r.spec.label(), ci(at(r, "ev.per100"), "%+.3f"),
                    at(r, "sd.per100")[0]));
            for (int h : RATES) {
                b.append(String.format(" | %s ($%s) | %.2f ($%.0f)", ci(at(r, "ev.hour." + h), "%+.3f"),
                        ci(at(r, "ev.hour." + h + ".usd"), "%+.2f"), at(r, "sd.hour." + h)[0], at(r, "sd.hour." + h + ".usd")[0]));
            }
            out.println(b.append(" |"));
        }
        out.println();
        printRuinTables(out, o);
        printChecks(out, o);
    }

    private static List<String> hourHeads() {
        List<String> h = new ArrayList<>();
        for (int r : RATES) {
            h.add("EV / hour at " + r);
            h.add("SD / hour at " + r);
        }
        return h;
    }

    private static String widenings(Outcome o) {
        List<String> w = new ArrayList<>();
        for (Row r : o.rows) {
            if (r.mu != null && r.spec.fullPlay()) {
                w.add(r.spec.figure + " " + String.format("%.2f", r.mu.widen) + " (" + r.mu.widenBasis + ")");
            }
        }
        return w.isEmpty() ? "none." : String.join("; ", w) + ".";
    }

    private static void printRuinTables(PrintStream out, Outcome o) {
        out.println("## Bankrolls and risk of ruin");
        out.println();
        out.println("N0 is the rounds until the expected win is one SD of the total; B_p the bankroll in units whose risk of "
                + "ruin, played for ever, is p. A row that loses has no N0 and ruin is certain for any bankroll.");
        out.println();
        StringBuilder head = new StringBuilder("| Figure | Row | N0, rounds");
        for (double p : RISKS) {
            head.append(" | B_").append(pct(p));
        }
        for (double b : BANKROLLS) {
            head.append(" | RoR(").append((int) b).append(')');
        }
        out.println(head.append(" |"));
        out.println("|---|---|---|" + "---|".repeat(RISKS.length + BANKROLLS.length));
        for (Row r : o.rows) {
            if (!r.computed()) {
                continue;
            }
            if (r.loses()) {
                out.printf("| %s | %s | loses: ruin is certain for any bankroll |%n", r.spec.figure, r.spec.label());
                continue;
            }
            StringBuilder b = new StringBuilder(String.format("| %s | %s | %s", r.spec.figure, r.spec.label(),
                    ci(at(r, "n0"), "%.0f")));
            for (double p : RISKS) {
                b.append(" | ").append(ci(at(r, "bankroll." + pct(p)), "%.0f"));
            }
            for (double x : BANKROLLS) {
                b.append(" | ").append(ci(at(r, "ror." + (int) x), "%.4f"));
            }
            out.println(b.append(" |"));
        }
        out.println();
        out.println("## Behind, and trip risk");
        out.println();
        for (int rate : RATES) {
            StringBuilder h = new StringBuilder("| Figure | Row");
            for (int hr : HOURS) {
                h.append(" | behind after ").append(hr).append(" h");
            }
            for (double b : TRIP_BANKROLLS) {
                for (int hr : HOURS) {
                    h.append(" | lose ").append((int) b).append(" in ").append(hr).append(" h");
                }
            }
            out.printf("At %d rounds an hour:%n%n%s |%n", rate, h);
            out.println("|---|---|" + "---|".repeat(HOURS.length * (1 + TRIP_BANKROLLS.length)));
            for (Row r : o.rows) {
                if (!r.computed()) {
                    continue;
                }
                StringBuilder b = new StringBuilder(String.format("| %s | %s", r.spec.figure, r.spec.label()));
                for (int hr : HOURS) {
                    b.append(" | ").append(ci(at(r, "behind." + hr + "h." + rate), "%.3f"));
                }
                for (double x : TRIP_BANKROLLS) {
                    for (int hr : HOURS) {
                        b.append(" | ").append(ci(at(r, "trip." + (int) x + "." + hr + "h." + rate), "%.3f"));
                    }
                }
                out.println(b.append(" |"));
            }
            out.println();
        }
    }

    private static void printChecks(PrintStream out, Outcome o) {
        out.println("## The formulas beside simulated play (PLAN_COMPUTE.md 2.4, check 4)");
        out.println();
        out.println("From the per-shoe records, at the records' own mu and sigma_eff. Paths: " + RUIN_PATHS + " bankroll paths of "
                + "whole shoes drawn with replacement, seed " + RUIN_SEED + ", ruined when the bankroll plus the shoe's low "
                + "is at most 0, stopped where the formula's remaining risk is under " + STOP_RISK + " (so the simulated risk "
                + "is low by at most that). Blocks: consecutive whole shoes from shoe 0, each the fewest reaching H r rounds. "
                + "Reported, not a gate; the gap is simulated less formula, with its binomial SE.");
        out.println();
        for (RuinCheck c : o.ruin) {
            out.printf("%s: %d shoes, %.0f rounds; mu %+.6f, sigma_eff %.4f; %.1f s.%n%n", c.figure, c.sums.shoes,
                    c.sums.sumN, c.mu, c.sigma, c.seconds);
            if (c.paths == null) {
                out.println("The records lose, so no path stops: no ruin paths.");
            } else {
                out.println("| Bankroll | Formula RoR | Simulated | SE | Gap / SE |");
                out.println("|---|---|---|---|---|");
                for (int k = 0; k < c.bankrolls.length; k++) {
                    out.printf("| %.0f | %.4f | %.4f | %.4f | %+.2f |%n", c.bankrolls[k], c.paths.formula(k),
                            c.paths.simulated(k), c.paths.se(k), (c.paths.simulated(k) - c.paths.formula(k)) / c.paths.se(k));
                }
            }
            out.println();
            out.println("| Block | Blocks | Behind: formula, simulated, gap / SE | " + tripHeads() + " |");
            out.println("|---|---|---|" + "---|".repeat(TRIP_BANKROLLS.length));
            for (RiskMath.Blocks b : c.blocks) {
                StringBuilder s = new StringBuilder(String.format("| %d rounds | %d | %s", b.roundsPerBlock, b.blocks,
                        gap(b.formulaBehind, b.behind, b.blocks)));
                for (int k = 0; k < TRIP_BANKROLLS.length; k++) {
                    s.append(" | ").append(gap(b.formulaTrip[k], b.ruined[k], b.blocks));
                }
                out.println(s.append(" |"));
            }
            out.println();
        }
        out.println("## The expected finding");
        out.println();
        if (Double.isNaN(o.sigmaSpread)) {
            out.println("Not all five counts at full play, 1-8, 80% are computed, so it is not tested.");
        } else {
            out.printf("Over the five counts at full play, 1-8, 80%%, sigma_eff spreads %.2f%% of its mean and mu %.1f%%. "
                    + "%s%n", 100 * o.sigmaSpread, 100 * o.muSpread, o.sigmaSpread < SAME_SWINGS
                    ? "Under " + pct(SAME_SWINGS) + ", so the data file says it holds: the bet schedule sets the swings; "
                    + "the count sets the win rate." : "Not under " + pct(SAME_SWINGS) + ", so the data file says it does "
                    + "not hold, and the page does not say it.");
        }
        out.println();
        out.println("## Checks");
        out.println();
        for (String p : o.passed) {
            out.println("- " + p);
        }
        for (String f : o.failures) {
            out.println("- FAILED: " + f);
        }
    }

    private static String tripHeads() {
        List<String> h = new ArrayList<>();
        for (double b : TRIP_BANKROLLS) {
            h.add("Lose " + (int) b + ": formula, simulated, gap / SE");
        }
        return String.join(" | ", h);
    }

    private static String gap(double formula, long count, int n) {
        double sim = (double) count / n;
        double se = RiskMath.binomialSe(formula, n);
        return String.format("%.4f, %.4f, %s", formula, sim, se == 0 ? "no spread" : String.format("%+.2f", (sim - formula) / se));
    }

    // ------------------------------------------------------------------ the data file

    /**
     * A number for the data file: five significant digits, more than the page prints; null for NaN
     * or an infinity (an open end).
     */
    static String num(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            return "null";
        }
        if (x == 0) {
            return "0";
        }
        return new java.math.BigDecimal(x).round(new java.math.MathContext(5)).stripTrailingZeros().toString();
    }

    /**
     * A probability for the data file, to four decimal places: the page prints a chance to at most
     * three, and smaller ones as under 0.001. Keeps the file within its budget.
     */
    static String prob(double x) {
        if (Double.isNaN(x)) {
            return "null";
        }
        java.math.BigDecimal d = new java.math.BigDecimal(x).setScale(4, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros();
        return d.signum() == 0 ? "0" : d.toPlainString();
    }

    /** Whether a figure of the layout is a probability. */
    static boolean isProbability(String key) {
        return key.startsWith("ror.") || key.startsWith("behind.") || key.startsWith("trip.");
    }

    static String str(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static String arr(double... v) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            b.append(i > 0 ? "," : "").append(num(v[i]));
        }
        return b.append(']').toString();
    }

    /** A figure: its value alone when it has no interval, else [value, lower, upper] with null for no upper bound. */
    private static String figure(double[] f, boolean probability) {
        if (!probability) {
            return Double.isNaN(f[1]) ? num(f[0]) : arr(f);
        }
        return Double.isNaN(f[1]) ? prob(f[0]) : "[" + prob(f[0]) + "," + prob(f[1]) + "," + prob(f[2]) + "]";
    }

    /**
     * The data file, for the site generator: the settings, the layout of each row's figures, the
     * rows, the expected finding, check 4's points and blocks, the ruin curve beside them, the
     * fan and one flat round's payoff distribution. Every number on the page's risk section is in
     * it; the browser does no arithmetic.
     */
    static String json(Outcome o, String commit, Map<String, String> inputs) {
        StringBuilder b = new StringBuilder("{\"what\":\"risk\",\"commit\":").append(str(commit));
        b.append(",\"written\":").append(str(java.time.LocalDate.now().toString())).append(",\"inputs\":{");
        int i = 0;
        for (Map.Entry<String, String> x : inputs.entrySet()) {
            b.append(i++ > 0 ? "," : "").append(str(x.getKey())).append(':').append(str(x.getValue()));
        }
        b.append("},\"unit_usd\":").append(num(DOLLARS_A_UNIT)).append(",\"rates\":").append(arr(toD(RATES)));
        b.append(",\"hours\":").append(arr(toD(HOURS))).append(",\"risks\":").append(arr(RISKS));
        b.append(",\"bankrolls\":").append(arr(BANKROLLS)).append(",\"trip_bankrolls\":").append(arr(TRIP_BANKROLLS));
        b.append(",\"layout\":[");
        for (int k = 0; k < LAYOUT.size(); k++) {
            b.append(k > 0 ? "," : "").append(str(LAYOUT.get(k)));
        }
        b.append("],\"rows\":[");
        i = 0;
        for (Row r : o.rows) {
            Spec s = r.spec;
            b.append(i++ > 0 ? "," : "").append("{\"figure\":").append(str(s.figure)).append(",\"count\":")
                    .append(str(s.count)).append(",\"play\":").append(str(s.play)).append(",\"pen\":")
                    .append(str(PEN_NAMES[s.pen])).append(",\"bets\":").append(str(s.bets)).append(",\"status\":")
                    .append(str(r.status));
            if (r.moments != null) {
                double[] pc = RiskMath.percentileInterval(r.mu.boot, x -> x);
                b.append(",\"loses\":").append(r.loses()).append(",\"mu\":").append(arr(r.mu.estimate, pc[0], pc[1]))
                        .append(",\"widen\":").append(num(r.mu.widen)).append(",\"sigma\":").append(num(r.sigma))
                        .append(",\"sigma_eff\":").append(arr(r.sigmaEff, r.sigmaEffSe)).append(",\"sim_mu\":")
                        .append(num(r.simMu)).append(",\"mean_bet\":").append(arr(r.moments.meanBet, r.mu.meanBet))
                        .append(",\"mean_bet_sq\":").append(arr(r.moments.meanBetSquared, r.mu.meanBetSquaredPooled))
                        .append(",\"splits\":").append(arr(r.splitRounds, r.splitSquare)).append(",\"z\":{");
                int j = 0;
                for (Map.Entry<String, Double> z : r.swing.z.entrySet()) {
                    b.append(j++ > 0 ? "," : "").append(str(z.getKey())).append(':').append(num(z.getValue()));
                }
                b.append('}');
            }
            if (r.figures != null) {
                b.append(",\"f\":[");
                for (int k = 0; k < r.figures.length; k++) {
                    b.append(k > 0 ? "," : "").append(figure(r.figures[k], isProbability(LAYOUT.get(k))));
                }
                b.append(']');
            }
            b.append('}');
        }
        b.append("],\"same_swings\":{\"sigma_spread\":").append(num(o.sigmaSpread)).append(",\"mu_spread\":")
                .append(num(o.muSpread)).append(",\"threshold\":").append(num(SAME_SWINGS)).append(",\"holds\":")
                .append(Double.isNaN(o.sigmaSpread) ? "null" : String.valueOf(o.sigmaSpread < SAME_SWINGS)).append('}');
        b.append(",\"ruin\":[");
        i = 0;
        for (RuinCheck c : o.ruin) {
            b.append(i++ > 0 ? "," : "").append("{\"figure\":").append(str(c.figure)).append(",\"mu\":").append(num(c.mu))
                    .append(",\"sigma_eff\":").append(num(c.sigma)).append(",\"shoes\":").append(c.sums.shoes);
            if (c.paths != null) {
                b.append(",\"paths\":").append(c.paths.paths).append(",\"seed\":").append(RUIN_SEED).append(",\"points\":[");
                for (int k = 0; k < c.bankrolls.length; k++) {
                    b.append(k > 0 ? "," : "").append(arr(c.bankrolls[k], c.paths.formula(k), c.paths.simulated(k), c.paths.se(k)));
                }
                b.append("],\"curve\":[");
                double top = RiskMath.bankrollFor(0.001, c.mu, c.sigma);
                for (int k = 0; k <= 24; k++) {
                    double x = top * k / 24;
                    b.append(k > 0 ? "," : "").append(arr(x, RiskMath.ruin(c.mu, c.sigma, x)));
                }
                b.append(']');
            }
            b.append(",\"blocks\":[");
            for (int k = 0; k < c.blocks.size(); k++) {
                RiskMath.Blocks x = c.blocks.get(k);
                double[] row = new double[4 + 2 * TRIP_BANKROLLS.length];
                row[0] = x.roundsPerBlock;
                row[1] = x.blocks;
                row[2] = x.formulaBehind;
                row[3] = (double) x.behind / x.blocks;
                for (int t = 0; t < TRIP_BANKROLLS.length; t++) {
                    row[4 + 2 * t] = x.formulaTrip[t];
                    row[5 + 2 * t] = (double) x.ruined[t] / x.blocks;
                }
                b.append(k > 0 ? "," : "").append(arr(row));
            }
            b.append("]}");
        }
        b.append(']');
        jsonTail(b, o);
        return b.append('}').toString();
    }

    private static double[] toD(int[] v) {
        double[] d = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            d[i] = v[i];
        }
        return d;
    }

    /** The fan, one flat round's payoff distribution and the checks passed. */
    private static void jsonTail(StringBuilder b, Outcome o) {
        if (o.fan != null) {
            b.append(",\"fan\":{\"figure\":").append(str(o.fanFigure)).append(",\"step\":").append(FAN_STEP)
                    .append(",\"mean\":").append(arr(o.fanMean)).append(",\"sd\":").append(arr(o.fanSd)).append(",\"paths\":[");
            for (int j = 0; j < o.fan.length; j++) {
                b.append(j > 0 ? ",[" : "[");
                for (int k = 0; k < o.fan[j].length; k++) {
                    b.append(k > 0 ? "," : "").append(Math.round(o.fan[j][k]));
                }
                b.append(']');
            }
            b.append("]}");
        }
        for (Row r : o.rows) {
            if (r.spec.figure.equals("F03") && r.moments != null) {
                List<double[]> cells = new ArrayList<>();
                for (int i = 0; i < r.swing.count.size(); i++) {
                    if (r.swing.bet.get(i) == 1) {
                        cells.add(new double[]{r.swing.twoX.get(i), (double) r.swing.count.get(i) / r.moments.rounds});
                    }
                }
                cells.sort((x, y) -> Double.compare(x[0], y[0]));
                b.append(",\"flat_payoffs\":{\"figure\":\"F03\",\"result\":[");
                for (int i = 0; i < cells.size(); i++) {
                    b.append(i > 0 ? "," : "").append(num(cells.get(i)[0] / 2));
                }
                b.append("],\"p\":[");
                for (int i = 0; i < cells.size(); i++) {
                    b.append(i > 0 ? "," : "").append(num(cells.get(i)[1]));
                }
                b.append("]}");
            }
        }
        b.append(",\"checks\":[");
        for (int i = 0; i < o.passed.size(); i++) {
            b.append(i > 0 ? "," : "").append(str(o.passed.get(i)));
        }
        b.append(']');
    }

    // ------------------------------------------------------------------ the run

    /** A file's SHA-256, in hex. */
    static String sha256(Path file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 20];
                for (int n; (n = in.read(buf)) > 0; ) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) {
                b.append(String.format("%02x", x));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads everything, makes the checks, and only if they all pass writes the report and the data
     * file, each whole to a temporary file and then moved into place, never over a file already
     * there.
     */
    static Outcome run(Map<String, Path> sources, Path pilot, Path swingsFile, Map<String, Path> recordFiles,
                       String fanFigure, Path fanFile, String commit, Path md, Path json) throws Exception {
        for (Path out : new Path[]{md, json}) {
            if (Files.exists(out)) {
                throw new IllegalStateException(out + " is already there; a report is never written over");
            }
        }
        Engine e = readEngine(sources, pilot);
        Map<String, Swing> swings = readSwings(Files.readAllLines(swingsFile, StandardCharsets.UTF_8));
        Map<String, RiskMath.Records> records = new LinkedHashMap<>();
        for (Map.Entry<String, Path> r : recordFiles.entrySet()) {
            records.put(r.getKey(), readRecords(r.getValue()));
        }
        double[][] fan = fanFile == null ? null : readFan(fanFile);
        Outcome o = compute(e, swings, records, fanFigure, fan);
        if (!o.failures.isEmpty()) {
            throw new IllegalStateException("the risk figures fail their checks, so nothing is written:\n"
                    + String.join("\n", o.failures));
        }
        Map<String, String> inputs = new LinkedHashMap<>();
        for (Path p : sources.values()) {
            inputs.put(p.toString(), sha256(p));
        }
        if (pilot != null) {
            inputs.put(pilot.toString(), sha256(pilot));
        }
        inputs.put(swingsFile.toString(), sha256(swingsFile));
        for (Path p : recordFiles.values()) {
            inputs.put(p.toString(), sha256(p));
        }
        if (fanFile != null) {
            inputs.put(fanFile.toString(), sha256(fanFile));
        }
        String data = json(o, commit, inputs);
        int bytes = data.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > JSON_BUDGET) {
            throw new IllegalStateException("the data file would be " + bytes + " bytes, over its budget of " + JSON_BUDGET);
        }
        write(md, out -> print(out, o, e, commit, inputs));
        write(json, out -> out.print(data));
        return o;
    }

    private interface Writer {
        void to(PrintStream out) throws Exception;
    }

    private static void write(Path file, Writer w) throws Exception {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (PrintStream out = new PrintStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16), false,
                "UTF-8")) {
            w.to(out);
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static void put(Map<String, Path> into, String arg) {
        int eq = arg.indexOf('=');
        if (eq < 1) {
            throw new IllegalArgumentException("expected NAME=PATH, not " + arg);
        }
        into.put(arg.substring(0, eq), Paths.get(arg.substring(eq + 1)));
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Map<String, Path> sources = new LinkedHashMap<>();
        Map<String, Path> records = new LinkedHashMap<>();
        Map<String, Path> fan = new LinkedHashMap<>();
        Path pilot = null;
        Path swings = null;
        Path md = null;
        Path json = null;
        String commit = null;
        for (int k = 0; k < args.length; k++) {
            switch (args[k]) {
                case "--source": put(sources, args[++k]); break;
                case "--records": put(records, args[++k]); break;
                case "--fan": put(fan, args[++k]); break;
                case "--widening": pilot = Paths.get(args[++k]); break;
                case "--swings": swings = Paths.get(args[++k]); break;
                case "--md": md = Paths.get(args[++k]); break;
                case "--json": json = Paths.get(args[++k]); break;
                case "--commit": commit = args[++k]; break;
                default: throw new IllegalArgumentException("unknown argument " + args[k]);
            }
        }
        if (sources.isEmpty() || swings == null || md == null || json == null || commit == null || fan.size() > 1) {
            System.err.println("usage: RiskReport --commit SHA --source NAME=PATH ... [--widening PILOT] --swings FILE "
                    + "[--records FIG=PATH ...] [--fan FIG=PATH] --md OUT.md --json OUT.json");
            System.exit(2);
        }
        String fanFigure = fan.isEmpty() ? null : fan.keySet().iterator().next();
        Outcome o = run(sources, pilot, swings, records, fanFigure, fan.isEmpty() ? null : fan.get(fanFigure), commit, md, json);
        int computed = 0;
        for (Row r : o.rows) {
            computed += r.computed() ? 1 : 0;
        }
        System.out.printf("RiskReport: %d of %d rows computed; wrote %s and %s.%n", computed, o.rows.size(), md, json);
    }
}
