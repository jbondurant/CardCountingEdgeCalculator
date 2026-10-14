import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * One consistent set of the saved files the page's computations read, for the page built end to
 * end (SitePageTest): c06c061's results.ser, step 7's save and its registration, step 8's
 * results.ser and its registration, the full-index pilot and a header-only state file, each in its
 * producing code's format, from Results made up here. From these files the producing code itself
 * then writes CeilingResults' data file and RiskReport's risk file, and SiteExport reads them all.
 *
 * The Results are made up, with 1,000 resamples from shared luck (the same shoes for every row) and
 * each row's own noise, so differences are paired as the real bootstrap's are. They are shaped so
 * the template's claim guards hold, as real data must for the page to build, and so RiskReport,
 * CeilingResults and SiteExport each find what they check:
 * - full play at 80%, 1-8: Victor APC + ace highest, Hi-Opt II + ace a hair below it (the two
 *   unseparated), Zen, Hi-Opt II and Victor APC next, then KO and Hi-Lo well below the five;
 * - first decisions: each row its full-play level less a gain; every level falls with a shallower
 *   cut card, by more than the best row's lead over Hi-Lo;
 * - every row's same-schedule bets are the same (1 to 1.5 units), and its bet keys are those
 *   RiskFixture gives (an average bet of 1.5 at 1-8);
 * - F1 and F1 flat clear of every published row at first decisions (paragraph A in both cells);
 *   the bounds above every row, the full-play bound above the first move's on every resample.
 */
final class SiteWorld {

    static final int R = Bootstrap.RESAMPLES;
    static final int P = 2;
    static final int SHOES = 16;
    static final int S8 = SystemScorer.same(SystemScorer.SPREADS);
    /** What each penetration takes off a row's 80% level, the same for every row. */
    static final double[] SHALLOWER = {0.0024, 0.0012, 0};
    /** The luck every row shares on each resample, by penetration (the shoes drawn are the same for all). */
    static final double[][] SHARED = new double[3][R];

    static {
        SplittableRandom rnd = new SplittableRandom(20261013L);
        for (double[] s : SHARED) {
            for (int r = 0; r < R; r++) {
                s[r] = 0.0002 * gauss(rnd);
            }
        }
    }

    static double gauss(SplittableRandom rnd) {
        double u = 0;
        for (int k = 0; k < 12; k++) {
            u += rnd.nextDouble();
        }
        return u - 6;
    }

    /** How much of the luck a spread carries: a third flat, all of it at 1-8. */
    static double luck(int s) {
        return s == 1 ? 1.0 / 3 : 1.0 / 3 + 2.0 / 3 * Math.log(s) / Math.log(SystemScorer.SPREADS);
    }

    /** A row's level at spread s from its flat and 1-8 levels, on a log scale between them. */
    static double level(double flat, double s8, int s) {
        return s == 1 ? flat : flat + (s8 - flat) * Math.log(s) / Math.log(SystemScorer.SPREADS);
    }

    /** The same schedule's average bet at 1 to s: the same for every row, 1.5 units at 1-8. */
    static double bet(int s) {
        return 1 + (s - 1) / 14.0;
    }

    /**
     * An entry at 1-8 level s8 and flat level flat at 80%, each penetration that much lower by
     * SHALLOWER, its resamples the level plus the shared luck plus noise × its own draw (z, one
     * draw per resample: entries given the same z move together, as one count's scopes do).
     */
    static SystemScorer.Entry entry(SystemScorer.Entry e, double flat, double s8, double noise, double[] z) {
        SystemScorer.Entry keys = RiskFixture.entry("keys", null, false, new double[3], 1);
        for (int p = 0; p < 3; p++) {
            e.estimate[p] = new double[SystemScorer.METRICS];
            e.boot[p] = new double[SystemScorer.METRICS][R];
            double f = flat - SHALLOWER[p] / 4;
            double v = s8 - SHALLOWER[p];
            set(e, p, SystemScorer.FLAT, f, 1, noise, z);
            set(e, p, SystemScorer.PLAY, f - 0.0001, 1, noise, z);
            set(e, p, SystemScorer.INSURANCE, 0.0001, 0, 0, z);
            for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                double at = level(f, v, s);
                for (int m : new int[]{SystemScorer.same(s), SystemScorer.own(s), SystemScorer.played(s)}) {
                    set(e, p, m, at, luck(s), noise, z);
                }
                for (int m : new int[]{SystemScorer.sameBet(s), SystemScorer.ownBet(s), SystemScorer.playedBet(s)}) {
                    set(e, p, m, bet(s), 0, 0, z);
                }
            }
            e.keys[p] = keys.keys[p];
        }
        return e;
    }

    private static void set(SystemScorer.Entry e, int p, int m, double v, double luck, double noise, double[] z) {
        e.estimate[p][m] = v;
        for (int r = 0; r < R; r++) {
            e.boot[p][m][r] = v + luck * (SHARED[p][r] + noise * z[r]);
        }
    }

    /** One draw per resample, from a seed: a row's own noise. */
    static double[] draws(long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] z = new double[R];
        for (int r = 0; r < R; r++) {
            z[r] = gauss(rnd);
        }
        return z;
    }

    /** Each row's own noise, the SD of its draw's part of a resample. */
    static final double NOISE = 0.00004;

    final Path dir;
    Path states;
    String identity;
    CeilingReport.Ceiling c;
    CeilingReport.Published run;
    Path stored;
    Path saved;
    Path runFile;
    Path registration;
    Path fullRegistration;
    Path pilot;
    private long seed = 1;

    SiteWorld(Path dir) {
        this.dir = dir;
    }

    /**
     * A count's row in its scopes, named as the scorer names them: first decisions at fd (1-8, 80%)
     * and flat, betting only a little lower, full play a gain higher, each with one draw per
     * resample (full play's mostly the same draw, so its gain is paired).
     */
    void row(SystemScorer.Result r, CountKeys.Count c, String name, double flat, double fd, double gain) {
        double[] z = draws(seed++);
        double[] more = draws(seed++);
        double[] zf = new double[R];
        for (int k = 0; k < R; k++) {
            zf[k] = z[k] + 0.3 * more[k];
        }
        r.entries.add(entry(new SystemScorer.Entry(name, c, false), flat, fd, NOISE, z));
        r.entries.add(entry(new SystemScorer.Entry(name + ", betting only", c, true), -0.0064, fd - 0.0009, NOISE, z));
        if (!Double.isNaN(gain)) {
            r.fullEntries.add(entry(new SystemScorer.Entry(name + ", full play", c, false, true), flat + 0.0002, fd + gain,
                    NOISE, zf));
        }
    }

    /** A published count, its first-decision level fd, and its ace side-count variant's if ace is a number. */
    SystemScorer.Result count(String name, double flat, double fd, double ace) {
        CountKeys.Count c = name == null ? CountKeys.nullCount() : RiskFixture.count(name);
        SystemScorer.Result r = new SystemScorer.Result(c);
        row(r, c, c.name, flat, fd, name == null ? 0.0001 : 0.0007);
        if (!Double.isNaN(ace)) {
            CountKeys.Count v = CountKeys.sideCountVariant(c);
            row(r, v, v.name, flat + 0.0001, ace, 0.0007);
        }
        return r;
    }

    /**
     * c06c061's rows: the null count; Hi-Lo, KO and Zen plain; Hi-Opt II and Victor APC with their
     * ace side-count variants. Full play at 80%, 1-8 is first decisions plus 0.07 points.
     */
    List<SystemScorer.Result> published() {
        List<SystemScorer.Result> out = new ArrayList<>();
        out.add(count(null, -0.0066, -0.0095, Double.NaN));
        out.add(count(RiskReport.HI_LO, -0.0060, 0.0029, Double.NaN));
        out.add(count(RiskReport.KO, -0.0060, 0.0031, Double.NaN));
        out.add(count(RiskReport.ZEN, -0.0059, 0.0037, Double.NaN));
        out.add(count("Hi-Opt II", -0.0059, 0.0036, 0.00395));
        out.add(count("Victor Advanced Point Count (Victor APC)", -0.0059, 0.00355, 0.0040));
        return out;
    }

    /** A registered count's Result as step 7 scores it: its own row and, with a betting vector, that variant's. */
    SystemScorer.Result registered(CountKeys.Count x, double flat, double fd, double withVector) {
        SystemScorer.Result r = new SystemScorer.Result(x);
        row(r, x, x.name, flat, fd, Double.NaN);
        if (x.betVector != null) {
            CountKeys.Count bet = CountKeys.bettingVariant(x);
            r.entries.add(entry(new SystemScorer.Entry(bet.name, bet, false), flat + 0.0001, withVector, NOISE,
                    draws(seed++)));
        }
        return r;
    }

    /** A bound row as SystemScorer makes one: fitted on nothing, bet on its own value; the full-play one marked full play. */
    SystemScorer.Entry bound(String name, boolean full, double flat, double s8) {
        return entry(new SystemScorer.Entry(name, null, false, full, true), flat, s8, NOISE, draws(seed++));
    }

    /** Builds the world's Results and writes its saved files into dir. */
    static SiteWorld make(Path dir) throws Exception {
        SiteWorld w = new SiteWorld(dir);
        w.states = dir.resolve("main.states");
        StateStore.open(w.states, new StateStore.Header(20260928L, 0.008, 0.02, SystemScorer.CUTS[P], "world"));
        w.identity = "FullIndexRun joint run\ncommit c06c061\n" + FullIndexRun.stateFile(w.states) + "\nshoes " + SHOES
                + ", valued states 400\nresamples " + R + ", seed " + Bootstrap.SEED + ", M_play 5.0\n";
        CeilingReport.Ceiling c = new CeilingReport.Ceiling();
        w.c = c;
        w.registration = CustomCountsTest.write(dir, "registration.json", CeilingParagraphsTest.REGISTRATION);
        c.counts = CustomCounts.read(w.registration);
        c.published = new CeilingReport.Published();
        c.published.identity = w.identity;
        c.published.results = w.published();
        c.published.perfect = new double[]{0.1, -0.2, 0.3};
        // F1, F1 flat, F2 (its play vector, then with its betting vector) and the two half counts:
        // F1 and F1 flat clear of every published row, at 1-8 and flat.
        double n = Double.NaN;
        double[][] levels = {{-0.0056, 0.0046, n}, {-0.0050, 0.0044, n}, {-0.0058, 0.0039, 0.0045}, {-0.0058, 0.0041, n},
                {-0.0058, 0.0041, n}};
        for (int k = 0; k < levels.length; k++) {
            c.custom.add(w.registered(c.counts.entries.get(k).count, levels[k][0], levels[k][1], levels[k][2]));
        }
        c.bounds = new ArrayList<>(Arrays.asList(
                w.bound("Perfect first move and insurance, bet on its own value", false, -0.0030, 0.0060),
                w.bound("Chart play and perfect insurance, bet on its own value", false, -0.0062, 0.0025),
                w.bound("Perfect play of the dealt hand and insurance, bet on its own value", true, -0.0020, 0.0080)));
        w.stored = CeilingResultsTest.published(dir.resolve("results.ser"), c.published);
        w.saved = w.save(dir.resolve("ceiling.ser"));
        w.stepEight();
        // The pilot's largest difference ratio, 1.12, is what both CeilingResults and SiteExport widen a
        // registered count by, each reading this file (INTEGRATION.md open item 13): not 1.07, the
        // real pilot's, so a constant in either would show here as two different full.P.S.f1.
        w.pilot = Files.write(dir.resolve("pilot.txt"), WideReportTest.pilot(WideReportTest.ratios("1-8 same schedule",
                RiskReport.KO, 1.04, "flat", RiskReport.KO, SiteExportCeilingTest.NOT_MEASURED))
                .getBytes(StandardCharsets.UTF_8));
        return w;
    }

    /** Step 7's save, as CeilingReport.save writes one: beside the stored rows of c06c061, on the same states and resamples. */
    Path save(Path file) throws Exception {
        try (ObjectOutputStream o = new ObjectOutputStream(Files.newOutputStream(file))) {
            o.writeUTF("CeilingReport\ncommit world\n" + FullIndexRun.stateFile(states) + "\ncounts "
                    + registration.toAbsolutePath().normalize() + "\nresamples " + R + ", seed " + Bootstrap.SEED
                    + "\nstored rows of " + c.published.line("commit "));
            o.writeObject(new ArrayList<>(c.custom));
            o.writeObject(new ArrayList<>(c.bounds));
            o.writeObject(new ArrayList<>(c.refit));
            o.writeObject(new ArrayList<>(c.checked));
            o.writeDouble(c.r);
            o.writeObject(new ArrayList<>(c.references));
            o.writeObject(new java.util.LinkedHashMap<>(c.correlations));
        }
        return file;
    }

    /**
     * Step 8's run of the full-play registration (F1, F2 and the Hi-Lo check), on the same states
     * and resamples: F1 and F2 with their full-play rows and their own-value rows, Hi-Lo c06c061's own.
     */
    void stepEight() throws Exception {
        fullRegistration = CustomCountsTest.write(dir, "fullplay.json", CeilingResultsTest.FULL_REGISTRATION);
        run = new CeilingReport.Published();
        run.identity = identity.replace("commit c06c061", "commit step8");
        run.results = new ArrayList<>();
        run.perfect = c.published.perfect.clone();
        for (CustomCounts.Entry e : CustomCounts.read(fullRegistration).entries) {
            CountKeys.Count x = e.count;
            if (!x.systems.isEmpty()) {
                run.results.add(c.published.of(x.systems.get(0)));
                continue;
            }
            SystemScorer.Result r = new SystemScorer.Result(x);
            boolean f2 = x.betVector != null;
            row(r, x, x.name, -0.0058, f2 ? 0.0039 : 0.0046, f2 ? 0.0006 : 0.0007);
            if (f2) {
                CountKeys.Count bet = CountKeys.bettingVariant(x);
                row(r, bet, bet.name, -0.0057, 0.0045, 0.0007);
            }
            r.fullEntries.add(entry(new SystemScorer.Entry(x.name + ", full play, bet on its own value", x, false, true,
                    true), -0.0050, 0.0056, NOISE, draws(seed++)));
            run.results.add(r);
        }
        runFile = CeilingResultsTest.published(dir.resolve("step8.ser"), run);
    }

    /**
     * CeilingResults' data file, by its data mode from the world's files: the step 7 report and
     * c06c061's peek printed from the same Results, as the real ones were, the split margin
     * SplitMarginTest makes and the paragraphs committed before the look.
     */
    Path ceilingData() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(bytes, true, "UTF-8");
        CeilingReport.printLadder(c, ps);
        CeilingReport.printClaims(c, ps);
        Path report = Files.write(dir.resolve("report-ceiling.md"), bytes.toByteArray());
        StringBuilder peek = new StringBuilder("| Count | First decisions | Full play | Gain | its held SE |\n|---|\n");
        for (SystemScorer.Result r : c.published.results) {
            peek.append(String.format(java.util.Locale.ROOT, "| %s | %+.4f | %+.4f | 0 | 0 |%n", r.count.systems.isEmpty()
                    ? "Null count (no count)" : r.count.name, 100 * r.entries.get(0).estimate[P][S8],
                    100 * r.fullEntries.get(0).estimate[P][S8]));
        }
        Path peekFile = Files.write(dir.resolve("peek.txt"), peek.toString().getBytes(StandardCharsets.UTF_8));
        Path split = dir.resolve("split.tsv");
        SplitMargin.writeOut(split, "fixture", SplitMarginTest.rows(30, 1));
        Path out = dir.resolve("ceiling.json");
        CeilingResults.run(new String[]{"data", "--stored", stored.toString(), "--saved", saved.toString(), "--report",
                report.toString(), "--counts", registration.toString(), "--fullplay-run", runFile.toString(),
                "--fullplay-counts", fullRegistration.toString(), "--peek", peekFile.toString(), "--split-margin",
                split.toString(), "--paragraphs", SiteCeilingTest.paragraphs(dir).toString(), "--widening", pilot.toString(),
                "--out", out.toString()},
                "ceiling-world", new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"));
        return out;
    }

    /**
     * report-final for the world: the stored rows' headline section printed by ComparisonReport, as
     * report-final-26f0724.md was printed from c06c061's, so CeilingReport.checkPrinted holds the one
     * to the other (PLAN_PAGE.md 2.4).
     */
    Path reportFinal() throws Exception {
        CeilingReport.Published p = CeilingReport.read(stored);
        ComparisonReport.Scored sc = new ComparisonReport.Scored();
        sc.results = p.results;
        sc.nullCount = p.nullCount();
        sc.hiLo = p.of("Hi-Lo");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ComparisonReport.headline(sc, ComparisonReport.rows(sc), new PrintStream(bytes, true, "UTF-8"));
        return Files.write(dir.resolve("report-final.md"), bytes.toByteArray());
    }

    /** RiskReport's data file from c06c061's rows, with swings for its figures as RiskFixture makes the simulator's. */
    Path risk() throws Exception {
        java.util.Map<String, Path> sources = new java.util.LinkedHashMap<>();
        sources.put("c06c061", stored);
        List<String> figures = new ArrayList<>();
        for (RiskReport.Spec s : RiskReport.ROWS) {
            if (s.source.equals("c06c061")) {
                figures.add(s.figure);
            }
        }
        Path swings = RiskFixture.write(dir, "swings.tsv", RiskFixture.swings(figures, 2000, new java.util.HashMap<>()));
        Path json = dir.resolve("risk.json");
        RiskReport.Outcome o = RiskReport.run(sources, pilot, swings, new java.util.LinkedHashMap<>(), null, null,
                "risk-world", dir.resolve("report-risk.md"), json);
        if (!o.failures.isEmpty()) {
            throw new IllegalStateException("RiskReport refused the world: " + o.failures);
        }
        return json;
    }

    /** SiteExport's options for the world: every file it reads, the step 7 and step 8 rows among them. */
    SiteExport.Options export(String template) throws Exception {
        SiteExport.Options o = new SiteExport.Options();
        o.stored = stored;
        o.storedShown = "world/results.ser";
        o.widening = SiteExport.Widening.read(pilot);
        o.template = template;
        o.catalog = SiteExport.catalog(java.nio.file.Paths.get("counting-systems.json"));
        o.states = states;
        o.saved = saved;
        o.savedShown = "world/ceiling.ser";
        o.counts = registration;
        o.fullplay = runFile;
        o.fullplayShown = "world/step8.ser";
        o.fullplayCounts = fullRegistration;
        return o;
    }
}
