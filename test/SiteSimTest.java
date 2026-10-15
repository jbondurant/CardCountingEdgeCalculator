import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SiteSim on runs written as the simulator writes them (sim.Summary.write for the comparisons, the
 * sim-formats block of sim/QUESTIONS.md for the differences, checks.txt as sim.Sim p2 words it),
 * held to a copy of SIM_CHECK.md whose identity table and section 8 blocks are filled for the
 * fixture (INTEGRATION.md open items 6, 7 and 10).
 *
 * Every registered test of the parts covered is made once across the runs given; the counts
 * follow the rule; a comparison the registry does not name is reported, not counted. Refused, each
 * alone: a z or pass its own figures do not give, a comparison the simulator names as another
 * figure, a planted bug on in a main run, a P2 of another bundle, a registered test missing or made
 * twice, a test the registry does not have, checks.txt missing or with a mismatch, another chart, a
 * bundle or commit the identity table does not name, sizes other than section 8's, and an unfilled
 * section 8. Then the reported figures: {N}, mode B, depth and the edge at each count, and the
 * planted runs beside their control.
 */
public class SiteSimTest {

    @TempDir
    Path dir;

    static final String COMMIT = "5a1e0c0ffee5a1e0c0ffee5a1e0c0ffee5a1e0c0";
    static final String BUNDLE1 = "1111111111111111111111111111111111111111111111111111111111111111";
    static final String BUNDLE2 = "2222222222222222222222222222222222222222222222222222222222222222";
    /** Section 8's fixture sizes: P3 on 2 chunks, P2 at K = 1,200, for both parts. */
    static final long CHUNKS = 2;
    static final long K = 1_200;

    /**
     * SIM_CHECK.md with its identity table and section 8's blocks filled for the fixture. The fixture's
     * commit and bundles replace whatever the identity cells hold, recorded or not, so this world
     * names only its own (the real table names the merged simulator's commit since item 8).
     */
    static Path plan(Path dir, boolean filled) throws Exception {
        String doc = new String(Files.readAllBytes(Path.of("SIM_CHECK.md")), StandardCharsets.UTF_8);
        String[][] cells = {{"The simulator's commit \\(`sim/`\\)", COMMIT}, {"Part 1's bundle[^|]*", BUNDLE1},
                {"Part 2's bundle[^|]*", BUNDLE2}};
        for (String[] c : cells) {
            String was = doc;
            doc = doc.replaceFirst("(\\| " + c[0] + " \\| [^|]*\\| )[^|\n]*\\|", "$1" + c[1] + " |");
            assertNotEquals(was, doc, c[0]);
        }
        SiteSim.Plan read = SiteSim.Plan.read(Files.write(dir.resolve("SIM_CHECK-cells.md"), doc.getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of(COMMIT), read.commits);
        assertEquals(Map.of(1, List.of(BUNDLE1), 2, List.of(BUNDLE2)), read.bundles);
        if (filled) {
            for (int part = 1; part <= 2; part++) {
                doc = doc.replace("Not filled: part " + part + "'s pilot has not run.", "- f = 0.053: P3 " + CHUNKS
                        + " chunks (2,000,000 shoes), P2 K = 1,200 (72,891,600 re-deals); predicted wall time 100 s, 0.03 h.");
            }
        }
        return Files.write(dir.resolve("SIM_CHECK-fixture.md"), doc.getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, String> facts(String check, String plants, String bundle, long chunks, long k) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("check", check);
        f.put("simulatorCommit", COMMIT);
        f.put("bundleSha256", bundle);
        f.put("chartFingerprint", SiteSim.CHART_FINGERPRINT);
        f.put("seed", check.equals("P3") ? "20261008" : "20261009");
        if (check.equals("P3")) {
            f.put("shoes", Long.toString(chunks * SimCheckPlan.P3_CHUNK_SHOES));
        } else {
            f.put("k", Long.toString(k));
        }
        f.put("plants", plants);
        return f;
    }

    /** Every switch off, or the one named on. */
    static String plants(String on) {
        StringBuilder b = new StringBuilder();
        for (String s : SiteSim.SWITCHES) {
            b.append(b.length() == 0 ? "" : " ").append(s).append(s.equals(on) ? "=on" : "=off");
        }
        return b.toString();
    }

    /** A comparison of the registered figure f by check whose z is the one given: exact 0, simulated z x 0.001. */
    static sim.Summary.Comparison c(String check, SimCheckPlan.Figure f, double z) {
        String[] l = SiteSim.label(f);
        return new sim.Summary.Comparison(check, l[0], f.pen, l[1], 0, Double.NaN, z * 0.001, 0.001,
                check.equals("P3") ? 121_000_000 : 60_743, 2_000_000, 0, f.id);
    }

    static final String CHECKS = "D1\t408 of 408 records card for card and in q to the bit; 408 valued by the walk; 0 mismatches\n"
            + "D2\t596232 of 596232 keys equal to keys-sample.tsv's on 15288 lines; 0 mismatches; catalog counts missing "
            + "from its header: []\n"
            + "D4\t122400 of 122400 bet keys and bets equal to states.bin's; 0 mismatches\n"
            + "D5\t1071360 of 1071360 lookups equal to lookups.tsv's on 14880 lines; 0 mismatches\n"
            + "D3\tengine side: test/SimVsExactSmallShoeTest.java; D6: sim/test/P3Test, chunks on 1 and 6 threads\n";

    /**
     * The chart's walk as the simulator compares it on a bundle without the chart's row (part 2's):
     * registered as F01 to F03, with no exact side, so no z and no pass.
     */
    static sim.Summary.Comparison chartWalk(String check, SimCheckPlan.Figure f) {
        return new sim.Summary.Comparison(check, "Chart, no count, flat", f.pen, "flat", Double.NaN, Double.NaN, -0.006,
                0.0001, check.equals("P3") ? 121_000_000 : 60_743, 2_000_000, 0, f.id);
    }

    /**
     * A run directory of one part: summary-p3.json and summary-p2.json with every registered test of
     * the part (z 0.1 but those given), the figures kept by keep, one comparison the registry does
     * not name (Zen flat at 70%), the differences given, and checks.txt. A part 2 run also holds the
     * chart's walk, which P3 and P2 play on every bundle, with no exact side (chartWalk).
     */
    static Path run(Path dir, int part, String plants, String bundle, Map<String, Double> zs,
                    Predicate<SimCheckPlan.Figure> keep, String differences) throws Exception {
        return run(dir, part, plants, bundle, zs, keep, differences, !plants.equals(plants(null)));
    }

    /** The same, at a planted run's sizes (one chunk, K = 6,000) when planted, the clean control's too. */
    static Path run(Path dir, int part, String plants, String bundle, Map<String, Double> zs,
                    Predicate<SimCheckPlan.Figure> keep, String differences, boolean planted) throws Exception {
        return run(dir, part, plants, bundle, zs, keep, differences, planted ? 1 : CHUNKS, planted ? 6000 : K);
    }

    /** The same at the sizes given: P3 on chunks of a million shoes, P2 at K. */
    static Path run(Path dir, int part, String plants, String bundle, Map<String, Double> zs,
                    Predicate<SimCheckPlan.Figure> keep, String differences, long chunks, long k) throws Exception {
        Files.createDirectories(dir);
        List<sim.Summary.Comparison> p3 = new ArrayList<>();
        List<sim.Summary.Comparison> p2 = new ArrayList<>();
        for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
            if (f.part == part && keep.test(f)) {
                p3.add(c("P3", f, zs.getOrDefault(f.id + ".P3", 0.1)));
                if (f.p2()) {
                    p2.add(c("P2", f, zs.getOrDefault(f.id + ".P2", -0.1)));
                }
            }
        }
        if (part == 1) {
            p3.add(new sim.Summary.Comparison("P3", "Zen Count", 70, "flat", 0, Double.NaN, 0.001, 0.001, 106_000_000,
                    2_000_000, 0, ""));
        } else {
            // First, as the simulator lays out a bundle's rows: the chart's walk at 70, 75 and 80%.
            List<sim.Summary.Comparison> w3 = new ArrayList<>();
            List<sim.Summary.Comparison> w2 = new ArrayList<>();
            for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
                if (f.play == SimCheckPlan.Play.CHART) {
                    w3.add(chartWalk("P3", f));
                    if (f.p2()) {
                        w2.add(chartWalk("P2", f));
                    }
                }
            }
            p3.addAll(0, w3);
            p2.addAll(0, w2);
        }
        sim.Summary.write(dir.resolve("summary-p3.json"), facts("P3", plants, bundle, chunks, 0), p3);
        sim.Summary.write(dir.resolve("summary-p2.json"), facts("P2", plants, bundle, 0, k), p2);
        if (differences != null) {
            Path s = dir.resolve("summary-p3.json");
            String t = new String(Files.readAllBytes(s), StandardCharsets.UTF_8).replace("\"differences\": []",
                    "\"differences\": [" + differences + "]");
            Files.write(s, t.getBytes(StandardCharsets.UTF_8));
        }
        Files.write(dir.resolve("checks.txt"), (CHECKS + "plants\t" + plants + "\n").getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    /** A fixture part-1 run of every registered test, z 0.1 but F18 by P3 at 2.7 (flagged) and F25 by P3 at -4.0 (fails). */
    static Path part1(Path dir) throws Exception {
        return run(dir, 1, plants(null), BUNDLE1, Map.of("F18.P3", 2.7, "F25.P3", -4.0), f -> true, null);
    }

    static SiteSim.Options options(Path plan, Path... runs) {
        SiteSim.Options o = new SiteSim.Options();
        o.plan = plan;
        o.runs.addAll(List.of(runs));
        return o;
    }

    static double value(SiteInput in, String key) {
        return ((Number) ((Map<?, ?>) in.figures.get(key)).get("value")).doubleValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void everyRegisteredTestOfThePartIsMadeAndCountedByTheRule() throws Exception {
        Path plan = plan(dir, true);
        SiteInput in = SiteSim.adapt(options(plan, part1(dir.resolve("part1"))));
        int tests = 0;
        for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
            tests += f.part == 1 ? SiteSim.checks(f).size() : 0;
        }
        assertEquals(38, tests, "SIM_CHECK.md's totals: part 1 has 38 z tests");
        assertEquals(tests, value(in, "sim.tests"));
        assertEquals(tests - 1, value(in, "sim.passed"));
        assertEquals(1.0, value(in, "sim.flagged"));
        assertEquals(1.0, value(in, "sim.failed"));
        assertEquals(4.0, value(in, "sim.z_max"), 1e-12);
        assertEquals(2_000_000.0, value(in, "sim.shoes"));
        List<Object> table = (List<Object>) in.tables.get("sim_z");
        assertEquals(tests + 1, table.size(), "every comparison stays in the table, the unregistered one too");
        long reported = table.stream().filter(o -> "reported, not a registered test".equals(((Map<?, ?>) o).get("verdict")))
                .count();
        assertEquals(1, reported);
        assertTrue(table.stream().anyMatch(o -> "F25".equals(((Map<?, ?>) o).get("figure"))
                && "fails".equals(((Map<?, ?>) o).get("verdict"))));
        new SiteData().add("sim.json", in.text("sim.json").getBytes(StandardCharsets.UTF_8));
    }

    /** The runs, each with one thing wrong; what SiteSim's refusal says. */
    String refusal(SiteSim.Options o) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SiteSim.adapt(o));
        return e.getMessage();
    }

    static Path edit(Path file, String from, String to) throws Exception {
        String t = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String u = t.replaceFirst(from, to);
        assertNotEquals(t, u, from);
        return Files.write(file, u.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void whatSection12RefusesIsRefused() throws Exception {
        Path plan = plan(dir, true);
        Path run = part1(dir.resolve("a"));
        edit(run.resolve("summary-p3.json"), "\"z\": [^,]+,", "\"z\": 9.0,");
        assertTrue(refusal(options(plan, run)).contains("from its own figures"));
        run = part1(dir.resolve("b"));
        edit(run.resolve("summary-p3.json"), "\"pass\": false", "\"pass\": true");
        assertTrue(refusal(options(plan, run)).contains("pass or flag"));
        run = part1(dir.resolve("c"));
        edit(run.resolve("summary-p3.json"), "\"fig\": \"F18\"", "\"fig\": \"F19\"");
        assertTrue(refusal(options(plan, run)).contains("the simulator names it F19"));
        run = run(dir.resolve("d"), 1, plants("s17"), BUNDLE1, Map.of(), f -> true, null);
        assertTrue(refusal(options(plan, run)).contains("planted bug on"));
        run = part1(dir.resolve("e"));
        edit(run.resolve("summary-p2.json"), BUNDLE1, BUNDLE2);
        assertTrue(refusal(options(plan, run)).contains("identity table does not name for part 1"));
        run = run(dir.resolve("f"), 1, plants(null), BUNDLE1, Map.of(), f -> !f.id.equals("F12"), null);
        assertTrue(refusal(options(plan, run)).contains("the first F12.P3"));
        assertTrue(refusal(options(plan, part1(dir.resolve("g")), part1(dir.resolve("h")))).contains("twice"));
        run = part1(dir.resolve("i"));
        edit(run.resolve("summary-p2.json"), "\"check\": \"P2\", \"row\": \"Chart, no count, flat\", \"pen\": 80",
                "\"check\": \"P2\", \"row\": \"Chart, no count, flat\", \"pen\": 70");
        edit(run.resolve("summary-p2.json"), "\"fig\": \"F03\"", "\"fig\": \"F01\"");
        assertTrue(refusal(options(plan, run)).contains("is not a registered test"));
        run = part1(dir.resolve("j"));
        Files.delete(run.resolve("checks.txt"));
        assertTrue(refusal(options(plan, run)).contains("no checks.txt"));
        run = part1(dir.resolve("k"));
        edit(run.resolve("checks.txt"), "states.bin's; 0 mismatches", "states.bin's; 3 mismatches");
        assertTrue(refusal(options(plan, run)).contains("D4"));
        run = part1(dir.resolve("l"));
        edit(run.resolve("summary-p3.json"), SiteSim.CHART_FINGERPRINT, "0123456789abcdef");
        assertTrue(refusal(options(plan, run)).contains("chart of fingerprint"));
        run = part1(dir.resolve("m"));
        edit(run.resolve("summary-p3.json"), COMMIT, "deadbeefdeadbeef");
        assertTrue(refusal(options(plan, run)).contains("simulator at deadbeefdeadbeef"));
        run = part1(dir.resolve("n"));
        edit(run.resolve("summary-p3.json"), "\"shoes\": \"2000000\"", "\"shoes\": \"3000000\"");
        assertTrue(refusal(options(plan, run)).contains("section 8 fixes"));
        run = part1(dir.resolve("o"));
        edit(run.resolve("summary-p2.json"), "\"k\": \"1200\"", "\"k\": \"1300\"");
        assertTrue(refusal(options(plan, run)).contains("K = 1300"));
        Path unfilled = Files.createDirectories(dir.resolve("unfilled"));
        assertTrue(refusal(options(plan(unfilled, false), part1(dir.resolve("p")))).contains("is not filled"));
    }

    /**
     * SIM_CHECK.md's identity table (INTEGRATION.md open item 8) as the code reads it: SiteSim takes
     * the simulator's commit from it, and each of the three recorded files' last commits is one its
     * row names, so a change to sim/, SHOE_SPEC.md or D3's test after the record fails here before a
     * pilot runs on it. Skipped where the tree is not a git checkout (a clean archive of it).
     * Planted on 14 October 2026: a copy of the table whose simulator row names 4bf4830, the
     * simulator before site-fix-sim, failed it. The fixture's plan above, as it was before that day
     * (filling only cells that read "not yet written" or "not yet exported"), failed 5 of the
     * class's tests once the real table was filled.
     */
    @Test
    public void theIdentityTableNamesTheLastCommitOfEachFileItRecords() throws Exception {
        SiteSim.Plan plan = SiteSim.Plan.read(Path.of("SIM_CHECK.md"));
        assertFalse(plan.commits.isEmpty(), "SIM_CHECK.md's identity table names no simulator commit");
        String doc = new String(Files.readAllBytes(Path.of("SIM_CHECK.md")), StandardCharsets.UTF_8);
        String[][] rows = {{"The simulator's commit (`sim/`)", "sim"}, {"SHOE_SPEC.md's commit", "SHOE_SPEC.md"},
                {"`test/SimVsExactSmallShoeTest.java`'s commit (D3)", "test/SimVsExactSmallShoeTest.java"}};
        for (String[] r : rows) {
            String last;
            try {
                Process p = new ProcessBuilder("git", "log", "-1", "--format=%H", "--", r[1]).redirectErrorStream(true).start();
                last = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                org.junit.jupiter.api.Assumptions.assumeTrue(p.waitFor() == 0 && last.matches("[0-9a-f]{40}"),
                        "not a git checkout: " + last);
            } catch (java.io.IOException e) {
                org.junit.jupiter.api.Assumptions.abort("no git: " + e.getMessage());
                return;
            }
            List<String> named = null;
            for (String line : doc.split("\n")) {
                String[] cells = line.split("\\|");
                if (line.startsWith("| ") && cells.length > 3 && cells[1].trim().startsWith(r[0])) {
                    named = SiteSim.hex(cells[3], 7, 40);
                }
            }
            assertNotNull(named, "the identity table's row " + r[0]);
            String l = last;
            assertTrue(named.stream().anyMatch(c -> l.startsWith(c)), r[1] + "'s last commit is " + last
                    + ", which its row of the identity table does not name: " + named);
        }
    }

    /** Two parts, three runs: part 2's tests split over two bundles' runs (the replay's and the page look's), the rule over their union. */
    @Test
    public void thePartsAndBundlesAreTakenTogether() throws Exception {
        Path plan = plan(dir, true);
        String doc = new String(Files.readAllBytes(plan), StandardCharsets.UTF_8);
        String third = "3333333333333333333333333333333333333333333333333333333333333333";
        Files.write(plan, doc.replace(BUNDLE2, BUNDLE2 + " and " + third).getBytes(StandardCharsets.UTF_8));
        Predicate<SimCheckPlan.Figure> look = f -> f.source.equals("step 7") || f.source.equals("step 8");
        Path p1 = part1(dir.resolve("p1"));
        Path replay = run(dir.resolve("replay"), 2, plants(null), BUNDLE2, Map.of(), look.negate(), null);
        Path pageLook = run(dir.resolve("look"), 2, plants(null), third, Map.of(), look, null);
        SiteInput in = SiteSim.adapt(options(plan, p1, replay, pageLook));
        assertEquals(82.0, value(in, "sim.tests"), "SIM_CHECK.md's 82 z tests");
        assertEquals(2.0, value(in, "sim.parts"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SiteSim.adapt(options(plan, p1, replay)));
        assertTrue(e.getMessage().contains("lack 8 of its registered tests"), e.getMessage());
    }

    /** {N}, mode B, depth and the edge at each count, read beside the tests. */
    @Test
    @SuppressWarnings("unchecked")
    public void theReportedFiguresAreReadBesideTheTests() throws Exception {
        Path plan = plan(dir, true);
        String d = "{\"check\": \"P3\", \"row\": \"Victor Advanced Point Count (Victor APC) + ace side count\", \"pen\": 80, "
                + "\"figure\": \"ev:same-8\", \"less\": \"Hi-Lo = Hi-Lo Lite\", \"difference\": 0.0002, \"se\": 0.00001, "
                + "\"sigmaEff\": 0.9, \"first\": 0.01, \"second\": 0.0098, \"exact\": 0.0002, \"rounds\": 121000000, "
                + "\"shoes\": 2000000, \"fig\": \"F25\", \"lessFig\": \"F18\"}";
        Path run = run(dir.resolve("p1"), 1, plants(null), BUNDLE1, Map.of(), f -> true, d);
        Files.write(run.resolve("depth.tsv"), ("# depth.tsv\nband\tfrom\tto\trounds\tshoes\tsimulated\tse\texactWeight\texact\n"
                + "0\t0\t25\t1000\t10\t-0.005\t0.001\t50.0\t-0.0049\n1\t26\t51\t900\t10\t-0.004\t0.001\t40.0\t-0.0041\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(run.resolve("ev-by-key.tsv"), ("# ev-by-key.tsv: SHOE_SPEC.md C4, format 1\n"
                + "sim\tHi-Lo = Hi-Lo Lite\t80\t0\t-3\t4\t8\t20\t-1.0\t2.0\t-2.0\n"
                + "sim\tHi-Lo = Hi-Lo Lite\t80\t1\t-3\t4\t6\t12\t-0.5\t1.5\t-1.0\n"
                + "cross\tHi-Lo = Hi-Lo Lite\t80\t-3\t0.1\n"
                + "exact\tHi-Lo = Hi-Lo Lite\t80\t-3\t200.0\t-1.2\n"
                // A key fold 1 and the exact side never met (SHOE_SPEC.md C4: a side that never met it gives zeros).
                + "sim\tHi-Lo = Hi-Lo Lite\t80\t0\t9\t4\t2\t2\t0.5\t0.3\t0.5\n"
                + "sim\tHi-Lo = Hi-Lo Lite\t80\t1\t9\t4\t0\t0\t0\t0\t0\n"
                + "cross\tHi-Lo = Hi-Lo Lite\t80\t9\t0\n"
                + "exact\tHi-Lo = Hi-Lo Lite\t80\t9\t0\t0\n").getBytes(StandardCharsets.UTF_8));
        Path variants = dir.resolve("summary-variants.json");
        Map<String, String> vf = facts("P3", plants(null), BUNDLE2, SimCheckPlan.MODE_B_CHUNKS, 0);
        vf.put("check", "mode B and the moving count beside mode A (reported, not tested)");
        sim.Summary.write(variants, vf, List.of());
        edit(variants, "\"differences\": \\[\\]", "\"differences\": [{\"check\": \"P3\", \"row\": \"Hi-Lo = Hi-Lo Lite, full play\", "
                + "\"pen\": 80, \"figure\": \"ev:same-8\", \"less\": \"mode B less mode A\", \"difference\": -0.0001, "
                + "\"se\": 0.00004, \"sigmaEff\": 0.3, \"first\": 0, \"second\": 0, \"exact\": 0, \"rounds\": 240000000, "
                + "\"shoes\": 4000000, \"fig\": \"F27\", \"lessFig\": \"F27\"}]");
        SiteSim.Options o = options(plan, run);
        o.variants.add(variants);
        // c06c061's rows, as a fixture: every catalog count scored on a small synthetic file with resamples.
        Path states = ComparisonReportTest.syntheticRun(dir, 79, 79, 6);
        ScoringStates st = FullIndexPilot.load(states, StateStore.shoesDone(states));
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, 12, Bootstrap.SEED), SystemScorer.M_PLAY);
        List<SystemScorer.Result> results = new ArrayList<>();
        for (CountKeys.Count c : CountKeys.catalog()) {
            if (c.systems.contains("Hi-Lo") || c.systems.contains("Victor Advanced Point Count (Victor APC)")) {
                results.add(sc.score(c, CountKeys.bettingVariant(c)));
            }
        }
        o.published = CeilingReportTest.results(dir, states, st, results);
        SiteInput in = SiteSim.adapt(o);
        CeilingReport.Published p = CeilingReport.read(o.published);
        double se = SystemScorer.difference(SiteSimReported.entry(p, SiteSimReported.figure("F25")),
                SiteSimReported.entry(p, SiteSimReported.figure("F18")), 2, SystemScorer.same(8), 1).se;
        assertEquals(Math.ceil(0.81 / (se * se)), value(in, "sim.rounds_needed.fd"));
        assertEquals(value(in, "sim.rounds_needed.fd"), value(in, "sim.rounds_needed"), "first decisions' when full play has none");
        List<Object> v = (List<Object>) in.tables.get("sim_variants");
        assertEquals("mode B less mode A", ((Map<?, ?>) v.get(0)).get("what"));
        List<Object> depth = (List<Object>) in.tables.get("sim_depth");
        assertEquals(2, depth.size());
        // In the page's unit, percent of a unit a round, as every figure is: the file's fractions times 100.
        assertEquals(-0.41, (Double) ((Map<?, ?>) depth.get(1)).get("exact"), 1e-12);
        assertEquals(-0.4, (Double) ((Map<?, ?>) depth.get(1)).get("simulated"), 1e-12);
        assertEquals(-0.0001 * 100, (Double) ((Map<?, ?>) v.get(0)).get("difference"), 1e-15);
        List<Object> byKey = (List<Object>) in.tables.get("sim_ev_by_key");
        Map<?, ?> key = (Map<?, ?>) byKey.get(0);
        assertEquals(-3, key.get("key"));
        assertEquals(-100.0 / 8, (Double) key.get("sim0"), 1e-12);
        assertEquals(-100 * 1.2 / 200, (Double) key.get("exact"), 1e-12);
        // A key one fold never met has no mean there, and the exact side none either: null, not NaN,
        // so the input is written (results.json holds no NaN) and the page prints a dash.
        Map<?, ?> unmet = (Map<?, ?>) byKey.get(1);
        assertEquals(9, unmet.get("key"));
        assertEquals(25.0, (Double) unmet.get("sim0"), 1e-12);
        assertTrue(unmet.containsKey("sim1") && unmet.get("sim1") == null, unmet.toString());
        assertNull(unmet.get("se1"));
        assertNull(unmet.get("exact"));
        new SiteData().add("sim.json", in.text("sim.json").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The planted runs in two halves beside their control (SIM_CHECK.md section 10, open item 18):
     * each run on part 1's bundle, scoring F03, F15 and F18, and on part 2's, scoring F26 to F29.
     * s17 is caught by its D1 on part 1's half and by F27's z on part 2's; truncate by neither; the
     * control passes on both. Refused: a run with a half missing, two halves on one part's bundle, a
     * half lacking a planted test, a half at a main run's sizes, and a half with another switch on.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void thePlantedRunsAreReadInTwoHalvesBesideTheirControl() throws Exception {
        Path plan = plan(dir, true);
        Predicate<SimCheckPlan.Figure> planted = SimCheckPlan::plantedFigure;
        Map<String, Path[]> halves = new LinkedHashMap<>();
        for (String name : new String[]{"none", "s17", "truncate"}) {
            Path[] h = new Path[2];
            for (int part = 1; part <= 2; part++) {
                Map<String, Double> zs = name.equals("s17") && part == 2 ? Map.of("F27.P3", 6.0) : Map.of();
                h[part - 1] = run(dir.resolve(name + "-" + part), part, plants(name.equals("none") ? null : name),
                        part == 1 ? BUNDLE1 : BUNDLE2, zs, planted, null, true);
            }
            halves.put(name, h);
        }
        edit(halves.get("s17")[0].resolve("checks.txt"),
                "408 of 408 records card for card and in q to the bit; 408 valued by the walk; 0 mismatches",
                "400 of 408 records card for card and in q to the bit; 400 valued by the walk; 8 mismatches");
        SiteSim.Options o = options(plan, part1(dir.resolve("p1")));
        for (Map.Entry<String, Path[]> e : halves.entrySet()) {
            o.planted.put(e.getKey(), new ArrayList<>(List.of(e.getValue())));
        }
        SiteInput in = SiteSim.adapt(o);
        assertEquals(2.0, value(in, "sim.planted"));
        assertEquals(1.0, value(in, "sim.planted_caught"));
        assertEquals(1.0, value(in, "sim.planted_control_passes"));
        List<Object> runs = (List<Object>) in.tables.get("sim_planted");
        Map<?, ?> r = (Map<?, ?>) runs.get(1);
        assertEquals("s17", r.get("run"));
        assertEquals(List.of("D1"), r.get("d_failed"), "part 1's half's D1");
        assertEquals(true, r.get("caught_by_z"), "part 2's half's F27");
        assertEquals(false, ((Map<?, ?>) runs.get(2)).get("caught"), "truncate: nothing caught it at this fixture");
        List<Object> tests = (List<Object>) in.tables.get("sim_planted_tests");
        for (String name : halves.keySet()) {
            List<String> made = new ArrayList<>();
            for (Object x : tests) {
                if (name.equals(((Map<?, ?>) x).get("run"))) {
                    made.add((String) ((Map<?, ?>) x).get("test"));
                }
            }
            assertEquals(14, made.size(), name + ": the totals' 14 tests, F03, F15 and F18 with F26 to F29, by P3 and P2");
            assertTrue(made.containsAll(List.of("F03.P2", "F15.P3", "F18.P2", "F26.P3", "F29.P2")), made.toString());
        }
        o.planted.put("truncate", new ArrayList<>(List.of(halves.get("truncate")[1])));
        assertTrue(refusal(o).contains("no half on part 1's bundle"), refusal(o));
        o.planted.put("truncate", new ArrayList<>(List.of(halves.get("truncate")[1], halves.get("truncate")[1])));
        assertTrue(refusal(o).contains("two halves on part 2's bundle"), refusal(o));
        Path short1 = run(dir.resolve("short"), 1, plants("truncate"), BUNDLE1, Map.of(),
                planted.and(f -> !f.id.equals("F15")), null, true);
        o.planted.put("truncate", new ArrayList<>(List.of(short1, halves.get("truncate")[1])));
        assertTrue(refusal(o).contains("lacks 2 of its 14 tests, the first F15.P3"), refusal(o));
        Path big = run(dir.resolve("big"), 2, plants("truncate"), BUNDLE2, Map.of(), planted, null, true);
        edit(big.resolve("summary-p3.json"), "\"shoes\": \"1000000\"", "\"shoes\": \"2000000\"");
        o.planted.put("truncate", new ArrayList<>(List.of(halves.get("truncate")[0], big)));
        assertTrue(refusal(o).contains("a planted run plays"));
        o.planted.put("truncate", new ArrayList<>(List.of(halves.get("truncate")[0], halves.get("s17")[1])));
        assertTrue(refusal(o).contains("its own switch alone"));
    }

    /**
     * Part 2's runs across its three bundles (INTEGRATION.md open item 17), held to the one section 8
     * block SimCheckPlan fills from part 2's four pilot files (SimCheckPlanTest's: 26 chunks, K =
     * 84,000), each bundle named in the identity table. The chart's walk in each part 2 summary, with
     * no exact side, is not a test there: every registered test is made once, the chart's F03 by
     * part 1's run alone. A part 2 run at other sizes is refused, and so is a part 2 summary whose
     * chart walk has an exact side, as figures of both parts.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void partTwosRunsAcrossItsBundlesAreHeldToTheBlockItsPilotFilesFill() throws Exception {
        Predicate<SimCheckPlan.Figure> look = f -> f.count.equals("F1") || f.count.equals("F2");
        Predicate<SimCheckPlan.Figure> first = f -> look.test(f) && f.play == SimCheckPlan.Play.FIRST;
        Predicate<SimCheckPlan.Figure> full = f -> look.test(f) && f.play == SimCheckPlan.Play.FULL;
        Path pilots = Files.createDirectories(dir.resolve("pilots"));
        SimCheckPlanTest.pilotFile(pilots, "sim-pilot-part2-replay.tsv", 2, look.negate(), 40, 50, 40, 40);
        SimCheckPlanTest.pilotFile(pilots, "sim-pilot-part2-look-first.tsv", 2, first, 10, 10, 10, 9);
        SimCheckPlanTest.pilotFile(pilots, "sim-pilot-part2-look-full.tsv", 2, full, 11, 10, 10, 8);
        SimCheckPlanTest.pilotFile(pilots, "sim-pilot-part2-part1-bundle.tsv", 2, f -> false, 12, 20, 20, 30);
        String block = SimCheckPlan.fill(SimCheckPlan.Pilots.read(SimCheckPlan.pilotFiles(pilots, 2)));
        Path plan = plan(dir, false);
        String third = "3333333333333333333333333333333333333333333333333333333333333333";
        String fourth = "4444444444444444444444444444444444444444444444444444444444444444";
        String doc = new String(Files.readAllBytes(plan), StandardCharsets.UTF_8)
                .replace(BUNDLE2, BUNDLE2 + ", " + third + " and " + fourth)
                .replace(SimCheckPlan.unfilled(1), "- f = 0.053: P3 " + CHUNKS + " chunks (2,000,000 shoes), P2 K = 1,200 "
                        + "(72,891,600 re-deals); predicted wall time 100 s, 0.03 h.\n")
                .replace(SimCheckPlan.unfilled(2), block);
        Files.write(plan, doc.getBytes(StandardCharsets.UTF_8));
        Path p1 = part1(dir.resolve("p1"));
        Path replay = run(dir.resolve("replay"), 2, plants(null), BUNDLE2, Map.of(), look.negate(), null, 26, 84_000);
        Path lookFirst = run(dir.resolve("first"), 2, plants(null), third, Map.of(), first, null, 26, 84_000);
        Path lookFull = run(dir.resolve("full"), 2, plants(null), fourth, Map.of(), full, null, 26, 84_000);
        SiteInput in = SiteSim.adapt(options(plan, p1, replay, lookFirst, lookFull));
        assertEquals(82.0, value(in, "sim.tests"), "SIM_CHECK.md's 82 z tests, the chart's walk on part 2's bundles not among them");
        long f03 = ((List<Object>) in.tables.get("sim_z")).stream().filter(x -> "F03".equals(((Map<?, ?>) x).get("figure"))).count();
        assertEquals(2, f03, "F03 by P3 and P2 on part 1's bundle alone");
        Path small = run(dir.resolve("small"), 2, plants(null), fourth, Map.of(), full, null, CHUNKS, K);
        assertTrue(refusal(options(plan, p1, replay, lookFirst, small)).contains("section 8 fixes"));
        Path withExact = run(dir.resolve("exact"), 2, plants(null), fourth, Map.of(), full, null, 26, 84_000);
        edit(withExact.resolve("summary-p3.json"), "(\"row\": \"Chart, no count, flat\", \"pen\": 70, \"figure\": \"flat\", "
                + "\"exact\": )null", "$1-0.006");
        edit(withExact.resolve("summary-p3.json"), "\"z\": null, \"pass\": false", "\"z\": 0.0, \"pass\": true");
        assertTrue(refusal(options(plan, p1, replay, lookFirst, withExact)).contains("figures of both parts"));
    }
}
