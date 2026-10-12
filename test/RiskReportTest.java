import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RiskReport on files written as the real ones are (RiskFixture): the figures it writes are the
 * formulas at the engine's mu and the simulator's sigma_eff, with mu's resamples widened as the
 * pilot says; every check refuses what it must, and then writes nothing; a failed simulator test
 * holds its row back; the rows are SIM_CHECK.md's; the data file keeps to its budget.
 *
 * Bugs planted in a scratch copy of RiskReport on 12 October, each run against this class and
 * each caught: sigma in place of sigma_eff in the figures (N0 fails); full-play resamples not
 * widened (N0's lower end fails); the rules' range not checked; a failed simulator test not
 * holding its row back; engine files not checked to pair; the pilot's gain rows read as
 * difference rows (8 tests fail); the histogram's win a round not held to the shoe sums'; the
 * SD an hour as sigma_eff r. One was not caught by the run's test alone: the percentiles of mu
 * taken first and then transformed, which the data file's five digits hide (N0's lower end moved
 * by 1.1e-7 of itself); everyFigureIsTheFormulaOnEachResampleThenItsPercentiles was added for
 * it, and catches it.
 */
public class RiskReportTest {

    @TempDir
    Path dir;

    /** SIM_CHECK.md section 3: "Swings are kept (section 9) for 25 figures". */
    static final List<String> SIM_CHECK_SWINGS = Arrays.asList("F03", "F18", "F27", "F29", "F30", "F31", "F32", "F33",
            "F34", "F35", "F36", "F37", "F38", "F39", "F40", "F41", "F42", "F43", "F44", "F45", "F46", "F47", "F48", "F50",
            "F52");

    @Test
    void theRowsAreTheFiguresSimCheckKeepsSwingsFor() {
        List<String> ids = new ArrayList<>();
        for (RiskReport.Spec s : RiskReport.ROWS) {
            ids.add(s.figure);
        }
        assertEquals(SIM_CHECK_SWINGS, ids);
        assertEquals("Hi-Lo, full play, 1-8, 80%", RiskReport.spec("F27").label());
        assertEquals("Victor APC + ace, full play, 1-8, 70%", RiskReport.spec("F34").label());
        assertEquals("Hi-Opt II + ace, full play, 1-8, 80%", RiskReport.spec("F42").label());
        assertEquals("X-1,8", RiskReport.spec("F48").bets);
    }

    /** The figures the fixture's engine files can give: every row but the replay's and step 8's. */
    static List<String> computable() {
        List<String> f = new ArrayList<>();
        for (RiskReport.Spec s : RiskReport.ROWS) {
            if (s.source.equals("c06c061") || s.source.equals("step7")) {
                f.add(s.figure);
            }
        }
        return f;
    }

    /** What a run reads, written into dir; any of it may be changed by a test before the run. */
    final class Inputs {
        List<SystemScorer.Result> results = RiskFixture.results();
        String resultsIdentity = RiskFixture.identity("FullIndexRun joint run");
        String step7Identity = RiskFixture.identity("CeilingReport");
        List<String> swings = RiskFixture.swings(computable(), 2000, new HashMap<>());
        boolean records = true;
        boolean fan = true;

        RiskReport.Outcome run() throws Exception {
            Path results = dir.resolve("results.ser");
            RiskFixture.writeResults(results, resultsIdentity, this.results);
            Path step7 = dir.resolve("step7.ser");
            RiskFixture.writeStep7(step7, step7Identity, -0.0066);
            Map<String, Path> sources = new LinkedHashMap<>();
            sources.put("c06c061", results);
            sources.put("step7", step7);
            Map<String, Path> rec = new LinkedHashMap<>();
            if (records) {
                rec.put("F27", RiskFixture.write(dir, "records-F27.tsv", RiskFixture.records(20_000, 27)));
            }
            return RiskReport.run(sources, RiskFixture.write(dir, "pilot.txt", RiskFixture.pilot()),
                    RiskFixture.write(dir, "swings.tsv", swings), rec, fan ? "F27" : null,
                    fan ? RiskFixture.write(dir, "fan-F27.tsv", RiskFixture.fan()) : null, "fixture", md(), json());
        }

        Path md() {
            return dir.resolve("report-risk.md");
        }

        Path json() {
            return dir.resolve("risk.json");
        }
    }

    /** sigma_eff written out from the swings file's shoes line, apart from RiskMath. */
    static double sigmaEff(List<String> swings, String figure) {
        for (String l : swings) {
            String[] f = l.split("\t");
            if (f[0].equals("shoes") && f[1].equals(figure)) {
                double s = Double.parseDouble(f[2]);
                double y = Double.parseDouble(f[3]);
                double y2 = Double.parseDouble(f[4]);
                double n = Double.parseDouble(f[7]);
                double n2 = Double.parseDouble(f[8]);
                double yn = Double.parseDouble(f[9]);
                double mu = y / n;
                return Math.sqrt(s / (s - 1) * (y2 - 2 * mu * yn + mu * mu * n2) / n);
            }
        }
        throw new AssertionError("no shoes line for " + figure);
    }

    static Document row(Document json, String figure) {
        for (Document r : json.getList("rows", Document.class)) {
            if (r.getString("figure").equals(figure)) {
                return r;
            }
        }
        throw new AssertionError("no row " + figure);
    }

    /** A figure of a row, by key: [value, lower, upper] (null for no upper bound), or a lone value. */
    static List<Object> fig(Document json, Document row, String key) {
        int k = json.getList("layout", String.class).indexOf(key);
        Object f = row.getList("f", Object.class).get(k);
        return f instanceof List ? (List<Object>) f : Arrays.asList(f, null, null);
    }

    static double d(Object o) {
        return ((Number) o).doubleValue();
    }

    static void five(double expected, Object actual, String what) {
        // The data file keeps five significant digits.
        assertEquals(expected, d(actual), 5e-5 * Math.abs(expected), what);
    }

    /** A probability, which the data file keeps to four decimal places. */
    static void chance(double expected, Object actual, String what) {
        assertEquals(expected, d(actual), 5e-5 + 1e-12, what);
    }

    /** The 2.5 and 97.5 percentiles of f over mu's resamples widened by ratio, as RiskReport must take them. */
    static double[] interval(double[] boot, double ratio, java.util.function.DoubleUnaryOperator f) {
        double[] w = Bootstrap.widen(boot, ratio);
        for (int r = 0; r < w.length; r++) {
            w[r] = f.applyAsDouble(w[r]);
        }
        Arrays.sort(w);
        return new double[]{Bootstrap.quantile(w, 0.025), Bootstrap.quantile(w, 0.975)};
    }

    static SystemScorer.Entry fullEntry(List<SystemScorer.Result> results, String row) {
        for (SystemScorer.Result r : results) {
            for (SystemScorer.Entry e : r.fullEntries) {
                if (e.name.equals(row + ", full play")) {
                    return e;
                }
            }
        }
        throw new AssertionError(row);
    }

    /**
     * A whole run: the figures are the formulas at the engine's mu and the simulator's sigma_eff,
     * the intervals the percentiles over mu's resamples widened by the pilot's ratio, and the
     * rows that cannot be computed say why.
     */
    @Test
    void aRunGivesTheFormulasAtTheEnginesMuAndTheSimulatorsSigmaEff() throws Exception {
        Inputs in = new Inputs();
        RiskReport.Outcome o = in.run();
        assertTrue(o.failures.isEmpty(), o.failures.toString());
        Document json = Document.parse(new String(Files.readAllBytes(in.json()), StandardCharsets.UTF_8));
        assertEquals(25, json.getList("rows", Document.class).size());
        assertTrue(Files.size(in.json()) <= RiskReport.JSON_BUDGET);

        // Hi-Lo, full play, 1-8, 80%: mu 0.0036 from the engine; Hi-Lo has no difference ratio of
        // its own, so its resamples are widened by the largest the pilot measured, 1.06.
        Document f27 = row(json, "F27");
        assertEquals("computed", f27.getString("status"));
        double mu = 0.0036;
        double se = sigmaEff(in.swings, "F27");
        five(se, f27.getList("sigma_eff", Object.class).get(0), "sigma_eff");
        five(1.06, f27.get("widen"), "the widening");
        double[] boot = fullEntry(in.results, RiskReport.HI_LO).boot[2][SystemScorer.same(8)];
        five(se * se / (mu * mu), fig(json, f27, "n0").get(0), "N0");
        double[] n0 = interval(boot, 1.06, x -> x > 0 ? se * se / (x * x) : Double.POSITIVE_INFINITY);
        five(n0[0], fig(json, f27, "n0").get(1), "N0's lower end");
        five(n0[1], fig(json, f27, "n0").get(2), "N0's upper end");
        double b5 = -Math.log(0.05) * se * se / (2 * mu);
        five(b5, fig(json, f27, "bankroll.5%").get(0), "B_5%");
        five(25 * b5, fig(json, f27, "bankroll.5%.usd").get(0), "B_5% in dollars");
        chance(Math.exp(-2 * mu * 1000 / (se * se)), fig(json, f27, "ror.1000").get(0), "RoR(1000)");
        five(10 * se, fig(json, f27, "sd.per100").get(0), "SD per 100 rounds");
        five(100 * mu, fig(json, f27, "ev.per100").get(0), "EV per 100 rounds");
        five(se * Math.sqrt(80), fig(json, f27, "sd.hour.80").get(0), "SD an hour at 80");
        five(25 * se * Math.sqrt(80), fig(json, f27, "sd.hour.80.usd").get(0), "SD an hour at 80, dollars");
        chance(RiskMath.cdf(-mu * Math.sqrt(100 * 50) / se), fig(json, f27, "behind.100h.50").get(0), "behind");
        chance(RiskMath.tripRuin(mu, se, 500, 1000 * 100), fig(json, f27, "trip.500.1000h.100").get(0), "trip");
        double[] ev = interval(boot, 1.06, x -> 100 * x);
        five(ev[0], fig(json, f27, "ev.per100").get(1), "EV per 100's lower end");
        // The scorer's bets beside the simulator's: E[b] 1.5, and E[b^2] 4.6 from the pooled keys.
        five(1.5, f27.getList("mean_bet", Object.class).get(1), "the scorer's E[b]");
        five(4.6, f27.getList("mean_bet_sq", Object.class).get(1), "the scorer's E[b^2]");

        // KO has its own ratio at 1-8, 1.04; first decisions and the chart are not widened.
        five(1.04, row(json, "F40").get("widen"), "KO's widening");
        five(1, row(json, "F18").get("widen"), "first decisions");
        five(-0.0066, row(json, "F03").getList("mu", Object.class).get(0), "the chart's flat EV, from step 7");

        // Hi-Lo at 70% loses in the fixture: no N0, no bankroll, ruin certain.
        Document f30 = row(json, "F30");
        assertEquals(true, f30.getBoolean("loses"));
        assertNull(fig(json, f30, "n0").get(0));
        assertNull(fig(json, f30, "bankroll.1%").get(0));
        chance(1, fig(json, f30, "ror.5000").get(0), "a losing game's risk of ruin");

        // The rows no file was given for, and why.
        assertTrue(row(json, "F44").getString("status").startsWith("not computed: its engine file (replay)"));
        assertTrue(row(json, "F52").getString("status").startsWith("not computed: its engine file (step8)"));
        assertNull(row(json, "F52").get("f"));

        // Check 4, the fan and the flat round's payoffs.
        Document ruin = json.getList("ruin", Document.class).get(0);
        assertEquals("F27", ruin.getString("figure"));
        assertEquals(3, ruin.getList("points", Object.class).size());
        assertEquals(9, ruin.getList("blocks", Object.class).size());
        Document fan = json.get("fan", Document.class);
        assertEquals(20, fan.getList("paths", Object.class).size());
        assertEquals(100, ((List<?>) fan.getList("paths", Object.class).get(0)).size());
        five(mu * 1000, fan.getList("mean", Object.class).get(0), "the fan's centre after 1,000 rounds");
        five(se * Math.sqrt(1000), fan.getList("sd", Object.class).get(0), "the fan's SD after 1,000 rounds");
        Document flat = json.get("flat_payoffs", Document.class);
        assertEquals(Arrays.asList(-2.0, -1.0, -0.5, 1.0, 1.5, 2.0), doubles(flat.getList("result", Object.class)));
        double total = 0;
        for (Object p : flat.getList("p", Object.class)) {
            total += d(p);
        }
        assertEquals(1, total, 1e-5);
        assertNotNull(json.get("same_swings", Document.class).get("holds"));

        String md = new String(Files.readAllBytes(in.md()), StandardCharsets.UTF_8);
        for (String section : new String[]{"## Rows", "## Per 100 rounds and per hour", "## Bankrolls and risk of ruin",
                "## Behind, and trip risk", "## The formulas beside simulated play", "## The expected finding", "## Checks"}) {
            assertTrue(md.contains(section), section);
        }
        assertFalse(md.contains("FAILED"));
    }

    static List<Double> doubles(List<Object> v) {
        List<Double> out = new ArrayList<>();
        for (Object o : v) {
            out.add(d(o));
        }
        return out;
    }

    /**
     * The data file within its 60 KB with every one of the 25 rows computed, the fan and two
     * ruin checks: the rows the fixture cannot compute are filled from F27's, and the inputs are
     * named as long as the real ones will be.
     */
    @Test
    void theDataFileKeepsToItsBudgetWithEveryRow() throws Exception {
        Inputs in = new Inputs();
        RiskReport.Outcome o = in.run();
        RiskReport.Row f27 = null;
        for (RiskReport.Row r : o.rows) {
            f27 = r.spec.figure.equals("F27") ? r : f27;
        }
        for (RiskReport.Row r : o.rows) {
            if (!r.computed()) {
                r.status = "computed";
                r.mu = f27.mu;
                r.swing = f27.swing;
                r.moments = f27.moments;
                r.sigma = f27.sigma;
                r.sigmaEff = f27.sigmaEff;
                r.figures = f27.figures;
            }
        }
        o.ruin.add(o.ruin.get(0));
        Map<String, String> inputs = new LinkedHashMap<>();
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        for (String p : new String[]{"runs/full-index/full-c06c061/results.ser", "runs/report-ceiling-97bf834.ser",
                "runs/custom-count/step8-XXXXXXX/results.ser", "runs/sim/replay-XXXXXXX/wide.ser",
                "runs/full-index/pilot-1417-100.txt", "runs/sim/run-XXXXXXX/swings.tsv", "runs/sim/run-XXXXXXX/records-F27.tsv",
                "runs/sim/run-XXXXXXX/records-F29.tsv", "runs/sim/run-XXXXXXX/fan-F27.tsv"}) {
            inputs.put("/Users/justinbondurant/Documents/CardCountingEdgeCalculator/" + p, sha);
        }
        int bytes = RiskReport.json(o, "0123456789abcdef0123456789abcdef01234567", inputs)
                .getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= RiskReport.JSON_BUDGET, bytes + " bytes");
    }

    // ------------------------------------------------------------------ what it refuses

    private void refused(Inputs in, String says) {
        Exception e = assertThrows(Exception.class, in::run);
        assertTrue(e.getMessage().contains(says), e.getMessage());
        assertFalse(Files.exists(in.md()), "the report was written");
        assertFalse(Files.exists(in.json()), "the data file was written");
    }

    @Test
    void anEngineFileWithoutItsResamplesIsRefused() {
        Inputs in = new Inputs();
        fullEntry(in.results, RiskReport.HI_LO).boot[2] = null;
        refused(in, "a file without boot is refused");
    }

    @Test
    void engineFilesThatDoNotPairAreRefused() {
        Inputs in = new Inputs();
        in.step7Identity = in.step7Identity.replace("montreal-80.states", "twin-80.states");
        refused(in, "do not pair");
        in = new Inputs();
        in.resultsIdentity = in.resultsIdentity.replace("resamples 1000, seed 20260929", "resamples 200, seed 20260929");
        refused(in, "do not pair");
    }

    @Test
    void aResultOutsideTheRulesRangeFailsTheRun() {
        Inputs in = new Inputs();
        in.swings = new ArrayList<>(in.swings);
        in.swings.add("hist\tF27\t1.0\t18\t1\t0");
        refused(in, "outside the rules' range [-17, 16]");
    }

    @Test
    void theHistogramMustHoldTheShoeSumsRounds() {
        Inputs in = new Inputs();
        in.swings = new ArrayList<>(in.swings);
        // One more round of a push at a bet of 1: the histogram's rounds no longer match.
        in.swings.add("hist\tF41\t1.0\t0\t1\t0");
        refused(in, "F41: the histogram holds");
        // A cell's result moved: the same rounds, but not the same win.
        in = new Inputs();
        List<String> moved = new ArrayList<>();
        boolean done = false;
        for (String l : in.swings) {
            String[] f = l.split("\t");
            if (!done && f[0].equals("hist") && f[1].equals("F29") && f[3].equals("2")) {
                l = f[0] + "\t" + f[1] + "\t" + f[2] + "\t1\t" + f[4] + "\t" + f[5];
                done = true;
            }
            moved.add(l);
        }
        in.swings = moved;
        refused(in, "F29: the histogram's win a round");
    }

    @Test
    void everyFigureMustCarryItsTestsZ() {
        Inputs in = new Inputs();
        in.swings = new ArrayList<>(in.swings);
        in.swings.removeIf(l -> l.startsWith("z\tF27\tP2"));
        refused(in, "F27: the swings file lacks its P2 test's z");
    }

    @Test
    void theReplayIsRefusedUntilWideReportsReaderIsHere() {
        Map<String, Path> sources = new LinkedHashMap<>();
        sources.put("replay", dir.resolve("wide.ser"));
        Exception e = assertThrows(IllegalArgumentException.class, () -> RiskReport.readEngine(sources, null));
        assertTrue(e.getMessage().contains("WideReport"));
    }

    @Test
    void aReportIsNeverWrittenOver() throws Exception {
        Inputs in = new Inputs();
        Files.write(in.md(), List.of("an earlier report"));
        Exception e = assertThrows(IllegalStateException.class, in::run);
        assertTrue(e.getMessage().contains("never written over"));
        assertEquals(List.of("an earlier report"), Files.readAllLines(in.md()));
    }

    // ------------------------------------------------------------------ what it holds back

    /**
     * A figure whose simulator test failed is held back, as SIM_CHECK.md section 13 says: no
     * figures, its z in the report. The rest are computed, and the expected finding, which needs
     * all five counts at 80%, is not tested.
     */
    @Test
    void aFailedSimulatorTestHoldsItsRowBack() throws Exception {
        Inputs in = new Inputs();
        Map<String, Double> z = new HashMap<>();
        z.put("F40", 3.6);
        z.put("F35", -2.9);
        in.swings = RiskFixture.swings(computable(), 2000, z);
        in.run();
        Document json = Document.parse(new String(Files.readAllBytes(in.json()), StandardCharsets.UTF_8));
        assertTrue(row(json, "F40").getString("status").startsWith("held back: its P3 test gave z = +3.60"));
        assertNull(row(json, "F40").get("f"));
        assertEquals("computed", row(json, "F35").getString("status"), "|z| under 3.5 passes");
        assertNull(json.get("same_swings", Document.class).get("holds"));
        String md = new String(Files.readAllBytes(in.md()), StandardCharsets.UTF_8);
        assertTrue(md.contains("held back: its P3 test gave z = +3.60"));
        assertTrue(md.contains("not tested"));
    }

    /** A held-back figure draws no fan and gives no ruin check, and the run says so rather than failing. */
    @Test
    void aHeldBackFigureHasNoFanAndNoRuinCheck() throws Exception {
        Inputs in = new Inputs();
        Map<String, Double> z = new HashMap<>();
        z.put("F27", -3.5);
        in.swings = RiskFixture.swings(computable(), 2000, z);
        in.run();
        Document json = Document.parse(new String(Files.readAllBytes(in.json()), StandardCharsets.UTF_8));
        assertTrue(row(json, "F27").getString("status").startsWith("held back: its P3 test gave z = -3.50"));
        assertNull(json.get("fan"));
        assertTrue(json.getList("ruin", Object.class).isEmpty());
        String md = new String(Files.readAllBytes(in.md()), StandardCharsets.UTF_8);
        assertTrue(md.contains("No fan: its row, F27, is held back"));
        assertTrue(md.contains("No ruin check for F27"));
    }

    // ------------------------------------------------------------------ the pilot's ratios

    /** Only the Results section's difference tables: not the earlier tables, not the gains. */
    @Test
    void levelRatiosReadTheResultsDifferencesOnly() {
        Map<String, Map<String, Double>> r = RiskReport.levelRatios(RiskFixture.pilot());
        assertEquals(2, r.size());
        assertEquals(1.04, r.get("1-8 same schedule").get(RiskReport.KO), 0);
        assertEquals(0.98, r.get("1-8 same schedule").get("Red Seven (Red 7)"), 0);
        assertEquals(1.06, r.get("flat").get(RiskReport.KO), 0);
        assertFalse(r.get("1-8 same schedule").containsKey(RiskReport.HI_LO), "a gain row was read as a difference");
        assertThrows(IllegalArgumentException.class, () -> RiskReport.levelRatios(List.of("# nothing", "## Results")));
    }

    // ------------------------------------------------------------------ the figures, unrounded

    /**
     * Figures of every kind against the formula taken on each resample and then its percentiles,
     * to 1e-12. The data file's five digits would hide a percentile taken before the transform,
     * which moves N0's lower end here by about 1e-7 of itself.
     */
    @Test
    void everyFigureIsTheFormulaOnEachResampleThenItsPercentiles() {
        double est = 0.0036;
        double[] boot = RiskFixture.boot(est, 0.00018, 5);
        RiskReport.Mu mu = new RiskReport.Mu(est, boot, 1, "a test", 1.5, 4.6);
        double se = 4.15;
        double[][] f = RiskReport.figures(mu, se);
        List<String> layout = RiskReport.LAYOUT;
        assertEquals(layout.size(), f.length);
        exactly(f[layout.indexOf("n0")], boot, est, x -> se * se / (x * x));
        exactly(f[layout.indexOf("n0.hours.80")], boot, est, x -> se * se / (x * x) / 80);
        exactly(f[layout.indexOf("bankroll.5%")], boot, est, x -> -Math.log(0.05) * se * se / (2 * x));
        exactly(f[layout.indexOf("bankroll.1%.usd")], boot, est, x -> 25 * -Math.log(0.01) * se * se / (2 * x));
        exactly(f[layout.indexOf("ror.1000")], boot, est, x -> Math.exp(-2 * x * 1000 / (se * se)));
        exactly(f[layout.indexOf("behind.100h.80")], boot, est, x -> RiskMath.cdf(-x * Math.sqrt(8000) / se));
        exactly(f[layout.indexOf("trip.500.10h.50")], boot, est, x -> RiskMath.tripRuin(x, se, 500, 500));
        exactly(f[layout.indexOf("ev.hour.100.usd")], boot, est, x -> 25 * 100 * x);
        double[] sd = f[layout.indexOf("sd.hour.50")];
        assertEquals(se * Math.sqrt(50), sd[0], 1e-12);
        assertTrue(Double.isNaN(sd[1]) && Double.isNaN(sd[2]), "an SD with sigma_eff held has no interval here");
    }

    private static void exactly(double[] fig, double[] boot, double est, java.util.function.DoubleUnaryOperator f) {
        double[] v = new double[boot.length];
        for (int r = 0; r < v.length; r++) {
            v[r] = f.applyAsDouble(boot[r]);
        }
        Arrays.sort(v);
        double[] expected = {f.applyAsDouble(est), Bootstrap.quantile(v, 0.025), Bootstrap.quantile(v, 0.975)};
        for (int k = 0; k < 3; k++) {
            assertEquals(expected[k], fig[k], 1e-12 * Math.abs(expected[k]), "part " + k);
        }
    }
}
