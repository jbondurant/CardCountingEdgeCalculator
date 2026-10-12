import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The simulator check's pre-registration, SIM_CHECK.md, in code (PLAN_COMPUTE.md sections 1.3
 * to 1.7 and 6.2).
 *
 * Why this is code and not only prose. SIM_CHECK.md fixes, before the first pilot, which
 * figures the second program is compared on, which strategies each part plays, how many z
 * tests there are, the chance that all of them pass by luck failing, the bias each check
 * would catch 90% and 95% of the time, and the rule that turns the pilot's speeds and SDs
 * into run sizes. Every one of those numbers is printed here and pasted into SIM_CHECK.md
 * between markers, and SimCheckPlanTest holds the file equal to this output. So the file
 * cannot drift from the list the simulator's reader checks a run against, and no number in
 * it was typed by hand.
 *
 * It reads no state file and no result. It holds the registry and does arithmetic; the only
 * file it reads is a pilot's measurements, in fill mode.
 */
final class SimCheckPlan {

    /** |z| at or past this fails a test (PLAN_COMPUTE.md 1.6). */
    static final double Z_FAIL = 3.5;
    /** |z| at or past this, and under Z_FAIL, is flagged on the page but does not fail. */
    static final double Z_FLAG = 2.5;
    /** The cap PLAN_COMPUTE.md 1.3 puts on the number of z tests. */
    static final int MAX_TESTS = 100;

    /** P3's shoes (PLAN_COMPUTE.md 1.6). P2, the coins and the ruin paths take their own seeds. */
    static final long P3_SEED = 20261008L;
    static final long P2_SEED = 20261009L;
    static final long RUIN_SEED = 20261010L;

    /** P3 runs in chunks of a million shoes; at full size, 38 of them (about 2.3e9 rounds at 80%). */
    static final long P3_CHUNK_SHOES = 1_000_000L;
    static final int P3_TARGET_CHUNKS = 38;
    /** P2 re-deals each valued state K times, in chunks of 1,000 states; at full size K is 120,000. */
    static final int P2_CHUNK_STATES = 1_000;
    static final int P2_TARGET_K = 120_000;
    /** K is set in whole thousands. */
    static final int P2_K_STEP = 1_000;
    /** A factor under this stops the part for Justin's decision instead of running smaller. */
    static final double F_MIN = 0.5;
    /** The pilot is this share of every component at full size. */
    static final double PILOT_SHARE = 0.01;
    /** Machine-hour budgets of part 1 and part 2 (PLAN_COMPUTE.md 1.7 and 6.2). */
    static final double[] BUDGET_HOURS = {1.5, 4.5};
    /** The simulator's main runs take the machine alone on this many threads (PLAN_COMPUTE.md 7.2). */
    static final int THREADS = 6;

    /** Fixed sizes, not scaled: mode B and the moving count, each on the first 4 chunks of P3's shoes. */
    static final int MODE_B_CHUNKS = 4;
    /** Each planted bug: P3 on the first chunk of shoes, P2 at this K on every valued state. */
    static final int PLANTED_CHUNKS = 1;
    static final int PLANTED_K = 6_000;
    /** The per-shoe records (Y, N, m) for the ruin check are kept on the first 2 chunks. */
    static final int RECORD_CHUNKS = 2;
    /** Fan paths: the first 20, each 1,000 hours at the fastest pace offered, 100 rounds an hour. */
    static final int FAN_PATHS = 20;
    static final int FAN_ROUNDS = 1_000 * 100;
    static final int FAN_STEP = 100;
    /** Ruin check: bankroll paths resampled from the per-shoe records. */
    static final int RUIN_PATHS = 20_000;

    /** The main state file as report-final-26f0724.md's header gives it. */
    static final int SHOES = 60_000;
    static final int[] PENS = {70, 75, 80};
    static final long[] ROUNDS = {3_197_853L, 3_426_592L, 3_644_432L};
    static final int VALUED_80 = 60_743;

    /**
     * Assumed values, used only for the table of SEs the scaling rule implies before any pilot
     * has run. Never on the page: the pilot measures the SDs, and exact.tsv gives the exact side.
     * SD a round: flat 1.16 (the scratch prototype of PLAN_COMPUTE.md 1.1), 1-8 2.4 (its estimate,
     * 1.16 x sqrt(E[bet^2]) with E[bet^2] about 4.27). Exact side: the SEs report-final prints for
     * Hi-Lo at 1-8 (0.014 at 70%, 0.018 at 80%), and the chart's flat interval at 80%,
     * [-0.671, -0.649], as a half-width over 1.96.
     */
    static final double ASSUMED_SD_FLAT = 1.16;
    static final double ASSUMED_SD_18 = 2.4;
    static final double ASSUMED_EXACT_SE_18_70 = 0.014e-2;
    static final double ASSUMED_EXACT_SE_18_80 = 0.018e-2;
    static final double ASSUMED_EXACT_SE_CHART_80 = (0.671e-2 - 0.649e-2) / (2 * 1.959963984540054);
    /** A pilot SD of the chart flat or a same-schedule 1-8 figure at 80% beyond this ratio of the assumed one stops the part for a look. */
    static final double SD_RATIO_STOP = 2.0;

    private SimCheckPlan() {
    }

    /** How a strategy plays: the chart alone, the count's first decision, the count's bets only, or every decision. */
    enum Play {
        CHART("chart"), FIRST("first decision"), BETTING("betting only"), FULL("full play");

        final String label;

        Play(String label) {
            this.label = label;
        }
    }

    /** The counts, short name to the name their Entry carries in the results files. */
    static final Map<String, String> ENTRY = new LinkedHashMap<>();

    static {
        ENTRY.put("null count", "Null count (no count)");
        ENTRY.put("Hi-Lo", "Hi-Lo = Hi-Lo Lite");
        ENTRY.put("KO", "KO (Knock-Out) = REKO (Ridiculously Easy Knock-Out)");
        ENTRY.put("Red Seven", "Red Seven (Red 7)");
        ENTRY.put("Zen", "Zen Count");
        ENTRY.put("Hi-Opt II + ace", "Hi-Opt II + ace side count");
        ENTRY.put("Victor APC + ace", "Victor Advanced Point Count (Victor APC) + ace side count");
        ENTRY.put("F1", "F1 at 1-8 (f1-8-vidami-ar)");
        ENTRY.put("F2", "F2 at 1-8, play vector (f2-pub-victor-apc) + betting vector");
    }

    /**
     * One figure both programs estimate: a row's strategy at one penetration under one bet plan.
     * At 80% it carries two z tests, P3 on fresh shoes and P2 on the main file's own states; at
     * 70% and 75% only P3, since P2 re-deals the valued states and plays each with the one fold
     * strategy whose test set holds it, which the plan does at 80% only.
     */
    static final class Figure {
        final String id;
        /** The row of PLAN_COMPUTE.md 1.3's table, 1 to 10. */
        final int row;
        final String count;
        final Play play;
        final int pen;
        /** flat, 1-2, 1-4, 1-8, own 1-8, P16, "B0,8" or "X-1,8". */
        final String bets;
        final int part;
        /** Which results file holds the engine's figure: c06c061, replay (wide.ser), step 7 or step 8. */
        final String source;

        Figure(String id, int row, String count, Play play, int pen, String bets, int part, String source) {
            this.id = id;
            this.row = row;
            this.count = count;
            this.play = play;
            this.pen = pen;
            this.bets = bets;
            this.part = part;
            this.source = source;
        }

        boolean p2() {
            return pen == 80;
        }

        int tests() {
            return p2() ? 2 : 1;
        }

        /** The Entry the engine's figure is read from, as the results file names it. */
        String entry() {
            switch (play) {
                case CHART:
                    return "Chart, no count, flat";
                case BETTING:
                    return ENTRY.get(count) + ", betting only";
                case FULL:
                    return ENTRY.get(count) + ", full play";
                default:
                    return ENTRY.get(count);
            }
        }

        /** The figure within the entry: an index of SystemScorer's 51, or a wide plan's (PLAN_COMPUTE.md 3.1). */
        String metric() {
            switch (bets) {
                case "flat":
                    return "FLAT";
                case "1-2":
                    return "same(2)";
                case "1-4":
                    return "same(4)";
                case "1-8":
                    return "same(8)";
                case "own 1-8":
                    return "own(8)";
                case "P16":
                    return "P16, same schedule";
                case "B0,8":
                    return "B0,8, same schedule, per round watched";
                case "X-1,8":
                    return "X-1,8, own rule, per round watched";
                default:
                    throw new IllegalStateException(bets);
            }
        }

        /**
         * The figure's name in the bundle's exact.tsv and in the simulator's summaries (SHOE_SPEC.md
         * B5): flat, or ev: and the plan's label as a strategy file names it (B2.5).
         */
        String exportFigure() {
            switch (bets) {
                case "flat":
                    return "flat";
                case "1-2":
                    return "ev:same-2";
                case "1-4":
                    return "ev:same-4";
                case "1-8":
                    return "ev:same-8";
                case "own 1-8":
                    return "ev:own-8";
                case "P16":
                    return "ev:P16";
                case "B0,8":
                    return "ev:B0,8";
                case "X-1,8":
                    return "ev:X-1.0,8";
                default:
                    throw new IllegalStateException(bets);
            }
        }

        /** Whether the fan and the per-shoe records are kept for it (section 11): full play, 1-8, 80%, Hi-Lo and Victor APC + ace. */
        boolean paths() {
            return play == Play.FULL && pen == 80 && bets.equals("1-8")
                    && (count.equals("Hi-Lo") || count.equals("Victor APC + ace"));
        }

        /** Whether the pilot's SD of this figure is held against an assumed one (SD_RATIO_STOP). */
        double assumedSd() {
            if (pen != 80) {
                return Double.NaN;
            }
            if (play == Play.CHART) {
                return ASSUMED_SD_FLAT;
            }
            return bets.equals("1-8") ? ASSUMED_SD_18 : Double.NaN;
        }
    }

    /** The figures, in the order of PLAN_COMPUTE.md 1.3's rows, then penetration, then bets. */
    static List<Figure> figures() {
        List<Figure> out = new ArrayList<>();
        String[] firstBets = {"flat", "1-2", "1-4", "1-8", "own 1-8"};
        // The chart's row is a reference row, fitted on nothing: c06c061's results.ser holds only the
        // counts' Results, so its figure is step 7's, scored on the same states and resamples.
        for (int pen : PENS) {
            add(out, 1, "chart", Play.CHART, pen, "flat", 1, "step 7");
        }
        add(out, 2, "null count", Play.FIRST, 80, "flat", 1, "c06c061");
        for (int pen : PENS) {
            for (String b : firstBets) {
                add(out, 3, "Hi-Lo", Play.FIRST, pen, b, 1, "c06c061");
            }
        }
        add(out, 4, "Hi-Lo", Play.BETTING, 80, "1-8", 1, "c06c061");
        for (String c : new String[]{"KO", "Red Seven", "Hi-Opt II + ace"}) {
            add(out, 5, c, Play.FIRST, 80, "1-8", 1, "c06c061");
        }
        add(out, 6, "Victor APC + ace", Play.FIRST, 80, "flat", 1, "c06c061");
        add(out, 6, "Victor APC + ace", Play.FIRST, 80, "1-8", 1, "c06c061");
        for (String c : new String[]{"Hi-Lo", "Victor APC + ace"}) {
            add(out, 7, c, Play.FULL, 80, "flat", 2, "c06c061");
            add(out, 7, c, Play.FULL, 80, "1-8", 2, "c06c061");
        }
        String[] five = {"Hi-Lo", "KO", "Zen", "Hi-Opt II + ace", "Victor APC + ace"};
        for (int pen : PENS) {
            for (String c : five) {
                boolean inRow7 = pen == 80 && (c.equals("Hi-Lo") || c.equals("Victor APC + ace"));
                if (!inRow7) {
                    add(out, 8, c, Play.FULL, pen, "1-8", 2, "c06c061");
                }
            }
        }
        for (String c : new String[]{"Hi-Lo", "Victor APC + ace"}) {
            for (String plan : new String[]{"P16", "B0,8", "X-1,8"}) {
                add(out, 9, c, Play.FULL, 80, plan, 2, "replay");
            }
        }
        for (String c : new String[]{"F1", "F2"}) {
            add(out, 10, c, Play.FIRST, 80, "1-8", 2, "step 7");
            add(out, 10, c, Play.FULL, 80, "1-8", 2, "step 8");
        }
        return Collections.unmodifiableList(out);
    }

    private static void add(List<Figure> out, int row, String count, Play play, int pen, String bets, int part,
                            String source) {
        String id = String.format(Locale.ROOT, "F%02d", out.size() + 1);
        out.add(new Figure(id, row, count, play, pen, bets, part, source));
    }

    /**
     * One strategy at one penetration, played by both of its fold strategies (the chart, fitted
     * on nothing, is one strategy for every penetration). Each fold strategy is an instance. A
     * first-decision instance shares its playout with every other first-decision instance that
     * makes the same first move on the same cards; a full-play instance plays its own; a
     * betting-only one plays the chart's, so it adds insurance and bets but no playout. Mode A
     * plays every instance on the chart's card flow, so the instances set a part's cost.
     */
    static final class Strategy {
        final String count;
        final Play play;
        final int pen;
        final int part;

        Strategy(String count, Play play, int pen, int part) {
            this.count = count;
            this.play = play;
            this.pen = pen;
            this.part = part;
        }

        int instances() {
            return play == Play.CHART ? 1 : 2;
        }

        boolean ownPlayout() {
            return play != Play.BETTING;
        }

        String playout() {
            switch (play) {
                case CHART:
                    return "the chart's walk itself";
                case FIRST:
                    return "shared by first move";
                case BETTING:
                    return "the chart's";
                default:
                    return "its own";
            }
        }

        /** Mode B and the moving count play these two at 80%, full play (PLAN_COMPUTE.md 1.5). */
        boolean modeB() {
            return play == Play.FULL && pen == 80 && (count.equals("Hi-Lo") || count.equals("Victor APC + ace"));
        }

        /** The planted bugs are run on these (section 10 of SIM_CHECK.md). */
        boolean planted() {
            return play == Play.CHART || modeB() || (play == Play.FIRST && pen == 80 && count.equals("Hi-Lo"));
        }

        boolean same(Figure f) {
            return f.count.equals(count) && f.play == play && (play == Play.CHART || f.pen == pen);
        }
    }

    /** The strategies the figures need, each once, in the order the figures first name them. */
    static List<Strategy> strategies() {
        List<Strategy> out = new ArrayList<>();
        for (Figure f : figures()) {
            boolean seen = false;
            for (Strategy s : out) {
                seen |= s.same(f);
            }
            if (!seen) {
                out.add(new Strategy(f.count, f.play, f.play == Play.CHART ? 0 : f.pen, f.part));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** The figures the planted-bug runs are scored on: each planted strategy's flat and 1-8, at 80%. */
    static boolean plantedFigure(Figure f) {
        if (f.pen != 80 || !(f.bets.equals("flat") || f.bets.equals("1-8"))) {
            return false;
        }
        for (Strategy s : strategies()) {
            if (s.same(f) && s.planted()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The figures whose swings PLAN_COMPUTE.md 2.2 publishes, so whose SD, sigma_eff, bet moments
     * and result histogram the simulator keeps: the chart flat and Hi-Lo first decisions at 1-8,
     * both at 80%; full play at 1-8 for the five counts at every penetration and for F1 and F2; the
     * wide plans.
     */
    static boolean swings(Figure f) {
        if (f.play == Play.CHART) {
            return f.pen == 80;
        }
        if (f.play == Play.FIRST) {
            return f.pen == 80 && f.count.equals("Hi-Lo") && f.bets.equals("1-8");
        }
        return f.play == Play.FULL && !f.bets.equals("flat");
    }

    /** Instances with a playout of their own (the 55 of PLAN_COMPUTE.md 1.3), in the part given, or both for 0. */
    static int instances(int part, Play... plays) {
        int n = 0;
        for (Strategy s : strategies()) {
            if (s.ownPlayout() && (part == 0 || s.part == part) && in(s.play, plays)) {
                n += s.instances();
            }
        }
        return n;
    }

    private static boolean in(Play p, Play... plays) {
        if (plays.length == 0) {
            return true;
        }
        for (Play q : plays) {
            if (p == q) {
                return true;
            }
        }
        return false;
    }

    /** The z tests in the part given, or both for 0; P2 only, or P3 only, or all. */
    static int tests(int part, String check) {
        int n = 0;
        for (Figure f : figures()) {
            if (part != 0 && f.part != part) {
                continue;
            }
            boolean p3 = !check.equals("P2");
            boolean p2 = !check.equals("P3") && f.p2();
            n += (p3 ? 1 : 0) + (p2 ? 1 : 0);
        }
        return n;
    }

    // ---- The normal arithmetic behind the pass rule and the detection limits.

    /**
     * P(Z >= x) for a standard normal Z, by Simpson's rule on the density over [x, x + 14] in
     * steps of 1/2000. Java has no erfc; this is short enough to check by eye, and its error is
     * under 1e-13, far below any digit printed. SimCheckPlanTest holds it to published values.
     */
    static double upperTail(double x) {
        if (x < 0) {
            return 1 - upperTail(-x);
        }
        int n = 28_000;
        double h = 14.0 / n;
        double sum = density(x) + density(x + 14);
        for (int i = 1; i < n; i++) {
            sum += (i % 2 == 1 ? 4 : 2) * density(x + i * h);
        }
        return sum * h / 3;
    }

    private static double density(double t) {
        return Math.exp(-0.5 * t * t) / Math.sqrt(2 * Math.PI);
    }

    /** The two-sided p of |z|: the chance a test with no bias reaches it. */
    static double twoSidedP(double z) {
        return 2 * upperTail(Math.abs(z));
    }

    /** The z with P(Z <= z) = p, by bisection. */
    static double quantile(double p) {
        if (!(p > 0 && p < 1)) {
            throw new IllegalArgumentException("p must be in (0, 1): " + p);
        }
        double lo = -10, hi = 10;
        for (int i = 0; i < 80; i++) {
            double mid = (lo + hi) / 2;
            if (1 - upperTail(mid) < p) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    /**
     * The bias, in combined SEs, that the test catches with the probability given: the d with
     * P(|Z + d| >= Z_FAIL) = power. A bias of exactly Z_FAIL SEs is caught only half the time,
     * so Z_FAIL is not a detection limit; at 90% this is 4.78 SEs, at 95% 5.14.
     */
    static double detectionMultiple(double power) {
        double lo = 0, hi = 20;
        for (int i = 0; i < 80; i++) {
            double mid = (lo + hi) / 2;
            double caught = upperTail(Z_FAIL - mid) + upperTail(Z_FAIL + mid);
            if (caught < power) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    /** Bonferroni: the chance that at least one of n unbiased tests fails, at most n times one test's. */
    static double falseFailBound(int n) {
        return n * twoSidedP(Z_FAIL);
    }

    /** The number of flags (Z_FLAG <= |z| < Z_FAIL) expected among n unbiased tests. */
    static double expectedFlags(int n) {
        return n * (twoSidedP(Z_FLAG) - twoSidedP(Z_FAIL));
    }

    // ---- Sizes and standard errors.

    static double roundsPerShoe(int pen) {
        for (int p = 0; p < PENS.length; p++) {
            if (PENS[p] == pen) {
                return ROUNDS[p] / (double) SHOES;
            }
        }
        throw new IllegalArgumentException("no penetration " + pen);
    }

    /** P3's simulated SE: sigma_eff over the root of the rounds the chunks deal at that penetration. */
    static double seP3(double sigmaEff, int chunks, int pen) {
        return sigmaEff / Math.sqrt(chunks * (double) P3_CHUNK_SHOES * roundsPerShoe(pen));
    }

    /** P2's SE at K re-deals a state, from V = sum w^2 s^2 / (sum w)^2 for one re-deal a state. */
    static double seP2(double v, int k) {
        return Math.sqrt(v / k);
    }

    /** V when every state's SD a round is sd and the weights are equal: sd^2 / n. */
    static double vEqual(double sd) {
        return sd * sd / VALUED_80;
    }

    static int chunksAt(double f) {
        return (int) Math.floor(f * P3_TARGET_CHUNKS + 1e-9);
    }

    static int kAt(double f) {
        return P2_K_STEP * (int) Math.floor(f * P2_TARGET_K / P2_K_STEP + 1e-9);
    }

    /**
     * What the budget rule gives one part from its pilot (SIM_CHECK.md section 7). The pilot
     * runs 1% of every component at full size, so each component's full-size time is 100 times
     * its pilot's; setup (reading the bundle, the D checks) happens once. P3's chunks and P2's K
     * are then scaled by one factor f, the largest at most 1 that keeps the predicted wall time
     * within the budget, each rounded down to its step. The fixed extras (mode B, the moving count
     * and the planted bugs, in part 2) are not scaled: they are paid first.
     */
    static final class Sizes {
        final int part;
        final double setup;
        final double p3Full;
        final double p2Full;
        final double extraFull;
        final double f;
        final int chunks;
        final int k;
        final List<String> stops = new ArrayList<>();

        Sizes(int part, double setup, double p3Pilot, double p2Pilot, double extraPilot) {
            if (part != 1 && part != 2) {
                throw new IllegalArgumentException("part 1 or 2: " + part);
            }
            this.part = part;
            this.setup = setup;
            this.p3Full = p3Pilot / PILOT_SHARE;
            this.p2Full = p2Pilot / PILOT_SHARE;
            this.extraFull = extraPilot / PILOT_SHARE;
            double room = BUDGET_HOURS[part - 1] * 3600 - setup - extraFull;
            double fit = room / (p3Full + p2Full);
            this.f = fit > 0 ? Math.min(1, fit) : 0;
            this.chunks = chunksAt(f);
            this.k = kAt(f);
            if (f < F_MIN) {
                stops.add(String.format(Locale.ROOT, "f = %.3f is under %.1f: the part stops for Justin's decision",
                        f, F_MIN));
            }
        }

        /** The wall time the sizes chosen predict, in seconds. */
        double predicted() {
            return setup + extraFull + p3Full * chunks / P3_TARGET_CHUNKS + p2Full * k / (double) P2_TARGET_K;
        }

        double budget() {
            return BUDGET_HOURS[part - 1] * 3600;
        }
    }

    /** A pilot's measurements, read from its TSV (SIM_CHECK.md section 8 gives the format). */
    static final class Pilot {
        int part;
        double setup = Double.NaN;
        double p3 = Double.NaN;
        double p2 = Double.NaN;
        double extra = Double.NaN;
        final Map<String, Double> sd = new LinkedHashMap<>();
        final Map<String, Double> v = new LinkedHashMap<>();
        final Map<String, Double> exact = new LinkedHashMap<>();

        static Pilot read(Path file) throws IOException {
            Pilot p = new Pilot();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] t = line.split("\t", -1);
                switch (t[0]) {
                    case "part":
                        p.part = Integer.parseInt(t[1]);
                        break;
                    case "setup":
                        p.setup = Double.parseDouble(t[1]);
                        break;
                    case "time":
                        double s = Double.parseDouble(t[2]);
                        if (t[1].equals("P3")) {
                            p.p3 = s;
                        } else if (t[1].equals("P2")) {
                            p.p2 = s;
                        } else if (t[1].equals("extra")) {
                            p.extra = s;
                        } else {
                            throw new IllegalArgumentException("unknown time: " + line);
                        }
                        break;
                    case "sd":
                        put(p.sd, t, line);
                        break;
                    case "v":
                        put(p.v, t, line);
                        break;
                    case "exact":
                        put(p.exact, t, line);
                        break;
                    default:
                        throw new IllegalArgumentException("unknown line: " + line);
                }
            }
            p.check();
            return p;
        }

        private static void put(Map<String, Double> m, String[] t, String line) {
            if (t.length != 3 || m.put(t[1], Double.parseDouble(t[2])) != null) {
                throw new IllegalArgumentException("malformed or repeated: " + line);
            }
        }

        /** Every figure of the part has its SD and exact SE, every 80% one its V, and nothing else is there. */
        void check() {
            if (Double.isNaN(setup) || Double.isNaN(p3) || Double.isNaN(p2) || Double.isNaN(extra)) {
                throw new IllegalArgumentException("the pilot file lacks a time or the setup");
            }
            List<String> want = new ArrayList<>();
            List<String> want80 = new ArrayList<>();
            for (Figure f : figures()) {
                if (f.part == part) {
                    want.add(f.id);
                    if (f.p2()) {
                        want80.add(f.id);
                    }
                }
            }
            same("sd", sd, want);
            same("exact", exact, want);
            same("v", v, want80);
        }

        private static void same(String what, Map<String, Double> m, List<String> want) {
            if (!new ArrayList<>(m.keySet()).equals(want)) {
                throw new IllegalArgumentException("the pilot's " + what + " lines must be, in order, " + want
                        + "; they are " + m.keySet());
            }
            for (Map.Entry<String, Double> e : m.entrySet()) {
                if (!(e.getValue() > 0) || Double.isInfinite(e.getValue())) {
                    throw new IllegalArgumentException(what + " of " + e.getKey() + " must be positive and finite");
                }
            }
        }
    }

    // ---- The blocks SIM_CHECK.md holds between markers, printed here.

    /** The names of the blocks, in the order SIM_CHECK.md holds them. */
    static final String[] BLOCKS = {"counts", "figures", "registry", "strategies", "totals", "sizes", "pass", "implied"};

    static String block(String name) {
        switch (name) {
            case "counts":
                return counts();
            case "figures":
                return figureTable();
            case "registry":
                return registry();
            case "strategies":
                return strategyTable();
            case "totals":
                return totals();
            case "sizes":
                return sizesText();
            case "pass":
                return passText();
            case "implied":
                return impliedTable();
            default:
                throw new IllegalArgumentException("no block " + name);
        }
    }

    private static String row(String... cells) {
        return "| " + String.join(" | ", cells) + " |\n";
    }

    private static String rule(int n) {
        StringBuilder b = new StringBuilder("|");
        for (int i = 0; i < n; i++) {
            b.append("---|");
        }
        return b.append('\n').toString();
    }

    static String pct(double x, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f%%", 100 * x);
    }

    static String grouped(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String counts() {
        StringBuilder b = new StringBuilder(row("Count here", "Its entry in the results files"));
        b.append(rule(2));
        b.append(row("chart", "Chart, no count, flat (a reference row, fitted on nothing)"));
        for (Map.Entry<String, String> e : ENTRY.entrySet()) {
            b.append(row(e.getKey(), e.getValue()));
        }
        return b.toString();
    }

    private static String figureTable() {
        StringBuilder b = new StringBuilder(row("Figure", "Row", "Count", "Play", "Pen.", "Bets", "Part", "Tests",
                "Engine's figure"));
        b.append(rule(9));
        for (Figure f : figures()) {
            b.append(row(f.id, Integer.toString(f.row), f.count, f.play.label, f.pen + "%", f.bets,
                    Integer.toString(f.part), f.p2() ? "P3, P2" : "P3", f.source + ": " + f.metric()));
        }
        return b.toString();
    }

    /**
     * The figures as the simulator's files name them (SHOE_SPEC.md Part C), one tab-separated line
     * each in a fenced block the simulator may read: the id its swings, records and fan files are
     * keyed by, the part, the row and penetration of the bundle, the exact.tsv figure, the tests,
     * the engine file, and what else is kept for it.
     */
    private static String registry() {
        StringBuilder b = new StringBuilder("```sim-registry\n");
        b.append("fig\tpart\trow\tpen\tfigure\ttests\tengine\tswings\tpaths\n");
        for (Figure f : figures()) {
            b.append(String.join("\t", f.id, Integer.toString(f.part), f.entry(), Integer.toString(f.pen),
                    f.exportFigure(), f.p2() ? "P3,P2" : "P3", f.source, swings(f) ? "1" : "0", f.paths() ? "1" : "0"))
                    .append('\n');
        }
        return b.append("```\n").toString();
    }

    private static String strategyTable() {
        StringBuilder b = new StringBuilder(row("Count", "Play", "Pen.", "Instances", "Part", "Playout", "Figures",
                "Also played by"));
        b.append(rule(8));
        for (Strategy s : strategies()) {
            List<String> ids = new ArrayList<>();
            for (Figure f : figures()) {
                if (s.same(f)) {
                    ids.add(f.id);
                }
            }
            List<String> also = new ArrayList<>();
            if (s.modeB()) {
                also.add("mode B, moving count");
            }
            if (s.planted()) {
                also.add("planted bugs");
            }
            b.append(row(s.count, s.play.label, s.play == Play.CHART ? "70/75/80%" : s.pen + "%",
                    s.ownPlayout() ? Integer.toString(s.instances()) : "2, no playout",
                    Integer.toString(s.part), s.playout(), String.join(", ", ids),
                    also.isEmpty() ? "" : String.join("; ", also)));
        }
        return b.toString();
    }

    private static String totals() {
        int figures = figures().size();
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "- %d figures; %d z tests: %d by P3 (every figure), %d by P2 (the "
                        + "figures at 80%%). The cap is %d.%n", figures, tests(0, "all"), tests(0, "P3"),
                tests(0, "P2"), MAX_TESTS));
        for (int part = 1; part <= 2; part++) {
            int n = 0;
            for (Figure f : figures()) {
                n += f.part == part ? 1 : 0;
            }
            b.append(String.format(Locale.ROOT, "- Part %d: %d figures, %d z tests (%d P3, %d P2); %d instances "
                            + "with a playout (%d at first decisions or the chart, %d at full play)%s.%n", part, n,
                    tests(part, "all"), tests(part, "P3"), tests(part, "P2"), instances(part),
                    instances(part, Play.CHART, Play.FIRST), instances(part, Play.FULL),
                    part == 1 ? ", and Hi-Lo betting only's 2 fold strategies on the chart's playout" : ""));
        }
        b.append(String.format(Locale.ROOT, "- In all: %d instances with a playout, %d at first decisions or the "
                        + "chart and %d at full play.%n", instances(0), instances(0, Play.CHART, Play.FIRST),
                instances(0, Play.FULL)));
        List<String> planted = new ArrayList<>();
        for (Figure f : figures()) {
            if (plantedFigure(f)) {
                planted.add(f.id);
            }
        }
        b.append(String.format(Locale.ROOT, "- Each planted-bug run is scored on %d figures (%s), by P3 and P2: "
                + "%d z tests a run.%n", planted.size(), String.join(", ", planted), 2 * planted.size()));
        List<String> swings = new ArrayList<>();
        for (Figure f : figures()) {
            if (swings(f)) {
                swings.add(f.id);
            }
        }
        b.append(String.format(Locale.ROOT, "- Swings are kept (section 9) for %d figures: %s.%n", swings.size(),
                String.join(", ", swings)));
        return b.toString();
    }

    private static String sizesText() {
        StringBuilder b = new StringBuilder();
        long shoes = P3_TARGET_CHUNKS * P3_CHUNK_SHOES;
        b.append(String.format(Locale.ROOT, "- P3 at full size: seed %d, %d chunks of %s shoes, %s shoes; about %.2f, "
                        + "%.2f and %.2f billion rounds at 70, 75 and 80%% (report-final's rounds a shoe, %.2f, %.2f "
                        + "and %.2f).%n",
                P3_SEED, P3_TARGET_CHUNKS, grouped(P3_CHUNK_SHOES), grouped(shoes),
                shoes * roundsPerShoe(70) / 1e9, shoes * roundsPerShoe(75) / 1e9, shoes * roundsPerShoe(80) / 1e9,
                roundsPerShoe(70), roundsPerShoe(75),
                roundsPerShoe(80)));
        int p2Chunks = (VALUED_80 + P2_CHUNK_STATES - 1) / P2_CHUNK_STATES;
        b.append(String.format(Locale.ROOT, "- P2 at full size: seed %d, K = %s re-deals of each of the %s valued "
                        + "states, %s re-deals, in %d chunks of %s states (the last one short).%n", P2_SEED,
                grouped(P2_TARGET_K), grouped(VALUED_80), grouped((long) P2_TARGET_K * VALUED_80), p2Chunks,
                grouped(P2_CHUNK_STATES)));
        b.append(String.format(Locale.ROOT, "- Not scaled (part 2): mode B and the moving count each on P3's first "
                        + "%d chunks (%s shoes); each planted-bug run, and the clean control beside them, P3 on the "
                        + "first %d chunk and P2 at K = %s.%n", MODE_B_CHUNKS,
                grouped(MODE_B_CHUNKS * P3_CHUNK_SHOES), PLANTED_CHUNKS, grouped(PLANTED_K)));
        b.append(String.format(Locale.ROOT, "- Kept on the way, not extra runs: the per-shoe records (Y, N, m) on "
                        + "P3's first %d chunks; the %d fan paths, each %s rounds, the cumulative win written every "
                        + "%d rounds.%n", RECORD_CHUNKS, FAN_PATHS, grouped(FAN_ROUNDS), FAN_STEP));
        b.append(String.format(Locale.ROOT, "- Budgets: part 1 %.1f machine hours, part 2 %.1f, each on %d threads "
                + "with the machine otherwise free.%n", BUDGET_HOURS[0], BUDGET_HOURS[1], THREADS));
        b.append(String.format(Locale.ROOT, "- The pilot: %.0f%% of every component at full size, so P3 on the "
                        + "first %s shoes and P2 at K = %s on every state, and each fixed extra at %.0f%% of its "
                        + "size.%n", 100 * PILOT_SHARE, grouped(Math.round(PILOT_SHARE * shoes)),
                grouped(Math.round(PILOT_SHARE * P2_TARGET_K)), 100 * PILOT_SHARE));
        b.append(String.format(Locale.ROOT, "- The smallest run the rule allows, f = %.1f: P3 %d chunks, P2 K = %s; "
                        + "every simulated SE then at most %.3f times its full-size value.%n", F_MIN,
                chunksAt(F_MIN), grouped(kAt(F_MIN)), Math.sqrt(1 / F_MIN)));
        return b.toString();
    }

    private static String passText() {
        int n = tests(0, "all");
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "- One unbiased test reaches |z| >= %.1f with chance %.3e "
                + "(two-sided).%n", Z_FAIL, twoSidedP(Z_FAIL)));
        b.append(String.format(Locale.ROOT, "- With the %d registered tests, the chance that at least one fails "
                        + "with no bias anywhere is at most %.2f%% (Bonferroni, whatever the tests' dependence); "
                        + "at the cap of %d it would be %.2f%%.%n", n, 100 * falseFailBound(n), MAX_TESTS,
                100 * falseFailBound(MAX_TESTS)));
        b.append(String.format(Locale.ROOT, "- Flags (%.1f <= |z| < %.1f) expected among %d unbiased tests: "
                + "%.2f.%n", Z_FLAG, Z_FAIL, n, expectedFlags(n)));
        b.append(String.format(Locale.ROOT, "- A bias of %.2f combined SEs is caught 90%% of the time, %.2f SEs "
                        + "95%% of the time, %.2f SEs half the time.%n", detectionMultiple(0.90),
                detectionMultiple(0.95), detectionMultiple(0.50)));
        return b.toString();
    }

    private static String impliedTable() {
        StringBuilder b = new StringBuilder(row("f", "P3 chunks, P2 K", "Check", "Assumed SD a round",
                "Assumed exact-side SE", "Simulated SE", "Combined SE", "Caught 90%", "Caught 95%"));
        b.append(rule(9));
        double m90 = detectionMultiple(0.90);
        double m95 = detectionMultiple(0.95);
        for (double f : new double[]{1, 0.75, F_MIN}) {
            int c = chunksAt(f);
            int k = kAt(f);
            String size = c + ", " + grouped(k);
            Object[][] lines = {
                    {"P3, chart flat, 80%", ASSUMED_SD_FLAT, ASSUMED_EXACT_SE_CHART_80, seP3(ASSUMED_SD_FLAT, c, 80)},
                    {"P3, 1-8, 80%", ASSUMED_SD_18, ASSUMED_EXACT_SE_18_80, seP3(ASSUMED_SD_18, c, 80)},
                    {"P3, 1-8, 70%", ASSUMED_SD_18, ASSUMED_EXACT_SE_18_70, seP3(ASSUMED_SD_18, c, 70)},
                    {"P2, chart flat, 80%, D = 1", ASSUMED_SD_FLAT, 0.0, seP2(vEqual(ASSUMED_SD_FLAT), k)},
                    {"P2, 1-8, 80%, D = 1", ASSUMED_SD_18, 0.0, seP2(vEqual(ASSUMED_SD_18), k)},
            };
            for (Object[] l : lines) {
                double exact = (Double) l[2];
                double sim = (Double) l[3];
                double comb = Math.hypot(sim, exact);
                b.append(row(String.format(Locale.ROOT, "%.2f", f), size, (String) l[0],
                        String.format(Locale.ROOT, "%.2f", (Double) l[1]), exact == 0 ? "none" : pct(exact, 4),
                        pct(sim, 4), pct(comb, 4), pct(m90 * comb, 4), pct(m95 * comb, 4)));
            }
        }
        return b.toString();
    }

    /**
     * The block a pilot fills in: the factor, the sizes, any stop, and for every test of the part
     * the SE the sizes give, the exact side's, the combined SE and the bias caught 90% and 95% of
     * the time. Pasted into SIM_CHECK.md section 8 under the pilot file's name, which is
     * committed beside it, so SimCheckPlanTest can print it again.
     */
    static String fill(Pilot p) {
        Sizes s = new Sizes(p.part, p.setup, p.p3, p.p2, p.extra);
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "- Part %d. Setup %.0f s; at full size P3 %.0f s, P2 %.0f s, fixed "
                + "extras %.0f s; budget %.0f s.%n", s.part, s.setup, s.p3Full, s.p2Full, s.extraFull, s.budget()));
        b.append(String.format(Locale.ROOT, "- f = %.3f: P3 %d chunks (%s shoes), P2 K = %s (%s re-deals); "
                        + "predicted wall time %.0f s, %.2f h.%n", s.f, s.chunks,
                grouped(s.chunks * P3_CHUNK_SHOES), grouped(s.k), grouped((long) s.k * VALUED_80), s.predicted(),
                s.predicted() / 3600));
        List<String> stops = new ArrayList<>(s.stops);
        for (Figure f : figures()) {
            double assumed = f.assumedSd();
            if (f.part == p.part && !Double.isNaN(assumed)) {
                double ratio = p.sd.get(f.id) / assumed;
                if (ratio > SD_RATIO_STOP || ratio < 1 / SD_RATIO_STOP) {
                    stops.add(String.format(Locale.ROOT, "%s's pilot SD %.3f is %.2f times the assumed %.2f: a look "
                            + "before the main run", f.id, p.sd.get(f.id), ratio, assumed));
                }
            }
        }
        for (String stop : stops) {
            b.append("- STOP: ").append(stop).append(".\n");
        }
        if (stops.isEmpty()) {
            b.append("- No stop: the main run goes ahead at these sizes.\n");
        }
        b.append('\n');
        b.append(row("Test", "Pilot SD a round", "Simulated SE", "Exact-side SE", "Combined SE", "Caught 90%",
                "Caught 95%"));
        b.append(rule(7));
        double m90 = detectionMultiple(0.90);
        double m95 = detectionMultiple(0.95);
        for (Figure f : figures()) {
            if (f.part != p.part) {
                continue;
            }
            double sd = p.sd.get(f.id);
            double exact = p.exact.get(f.id);
            double sim = seP3(sd, s.chunks, f.pen);
            double comb = Math.hypot(sim, exact);
            b.append(row(f.id + ".P3", String.format(Locale.ROOT, "%.4f", sd), pct(sim, 4), pct(exact, 4),
                    pct(comb, 4), pct(m90 * comb, 4), pct(m95 * comb, 4)));
            if (f.p2()) {
                double v = p.v.get(f.id);
                double sim2 = seP2(v, s.k);
                b.append(row(f.id + ".P2", String.format(Locale.ROOT, "%.4f (sqrt(nV))", Math.sqrt(v * VALUED_80)),
                        pct(sim2, 4), "none", pct(sim2, 4), pct(m90 * sim2, 4), pct(m95 * sim2, 4)));
            }
        }
        return b.toString();
    }

    /** What section 8 holds for a part whose pilot has not run. */
    static String unfilled(int part) {
        return "Not filled: part " + part + "'s pilot has not run.\n";
    }

    /** A part's fill block: printed from its committed pilot file if there is one, else unfilled. */
    static String fillBlock(int part) throws IOException {
        Path pilot = Paths.get("sim-pilot-part" + part + ".tsv");
        return Files.exists(pilot) ? fill(Pilot.read(pilot)) : unfilled(part);
    }

    static String open(String name) {
        return "<!-- SimCheckPlan " + name + " -->";
    }

    static String close(String name) {
        return "<!-- /SimCheckPlan " + name + " -->";
    }

    /** The text between a block's markers in a document, without the newline after the opening marker. */
    static String docBlock(String doc, String name) {
        int a = doc.indexOf(open(name));
        int z = doc.indexOf(close(name));
        if (a < 0 || z < 0 || z < a || doc.indexOf(open(name), a + 1) >= 0) {
            throw new IllegalArgumentException("SIM_CHECK.md must hold block " + name + " once, opened then closed");
        }
        String inner = doc.substring(a + open(name).length(), z);
        return inner.startsWith("\n") ? inner.substring(1) : inner;
    }

    /**
     * No argument: every block, between its markers, to paste into SIM_CHECK.md. "block NAME": one.
     * "fill PILOT.tsv": the block a pilot fills in.
     */
    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            for (String name : BLOCKS) {
                System.out.print(open(name) + "\n" + block(name) + close(name) + "\n\n");
            }
            for (int part = 1; part <= 2; part++) {
                String name = "fill-" + part;
                System.out.print(open(name) + "\n" + fillBlock(part) + close(name) + "\n\n");
            }
        } else if (args.length == 2 && args[0].equals("block")) {
            System.out.print(block(args[1]));
        } else if (args.length == 2 && args[0].equals("fill")) {
            System.out.print(fill(Pilot.read(Paths.get(args[1]))));
        } else {
            System.err.println("usage: SimCheckPlan [block NAME | fill PILOT.tsv]");
            System.exit(2);
        }
    }
}
