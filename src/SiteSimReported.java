import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What SIM_CHECK.md sections 9 and 10 report beside the tests, read from the simulator's own files
 * for SiteSim (INTEGRATION.md open item 10, the engine's side): the rounds a simulation would
 * need ({N}), mode B and the moving count, the house edge by depth, the edge at each count, and the
 * planted-bug runs. None of it enters the pass family. The formats are SHOE_SPEC.md Part C's
 * (ev-by-key.tsv) and the simulator's own, as sim/QUESTIONS.md item 27's sim-formats block gives
 * them (depth.tsv, the summaries' differences with fig and lessFig).
 */
final class SiteSimReported {

    private SiteSimReported() {
    }

    /** {N}'s pairs (SIM_CHECK.md section 9): Victor APC + ace less Hi-Lo at 1-8, 80%, at first decisions and at full play. */
    static final String[][] PAIRS = {{"fd", "F25", "F18"}, {"full", "F29", "F27"}};

    static void add(SiteInput in, SiteSim.Options o, Map<SiteSim.Summary, List<SiteSim.Test>> tested) throws IOException {
        roundsNeeded(in, o, tested);
        variants(in, o);
        depth(in, o);
        evByKey(in, o);
        planted(in, o);
    }

    static SimCheckPlan.Figure figure(String id) {
        for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
            if (f.id.equals(id)) {
                return f;
            }
        }
        throw new IllegalStateException("SIM_CHECK.md registers no " + id);
    }

    /** The engine's entry of a registered figure in c06c061's results. */
    static SystemScorer.Entry entry(CeilingReport.Published p, SimCheckPlan.Figure f) {
        for (SystemScorer.Result r : p.results) {
            for (SystemScorer.Entry e : f.play == SimCheckPlan.Play.FULL ? r.fullEntries : r.entries) {
                if (e.name.equals(f.entry())) {
                    return e;
                }
            }
        }
        throw new IllegalArgumentException("the published results hold no " + f.entry());
    }

    /**
     * {N} of PLAN_PAGE.md section 3: (σ_eff / SE)², σ_eff the simulator's SD of the per-round
     * difference of the pair on the same rounds (mode A, its P3 summary's paired difference) and SE
     * the engine's paired standard error of the same difference over c06c061's 1,000 resamples,
     * unwidened. At first decisions and at full play; sim.rounds_needed is full play's when it is
     * there, else first decisions'.
     */
    static void roundsNeeded(SiteInput in, SiteSim.Options o, Map<SiteSim.Summary, List<SiteSim.Test>> tested)
            throws IOException {
        if (o.published == null) {
            return;
        }
        CeilingReport.Published p;
        try {
            p = CeilingReport.read(o.published);
        } catch (ClassNotFoundException e) {
            throw new IOException(e);
        }
        Long chosen = null;
        String chosenAt = null;
        for (String[] pair : PAIRS) {
            Double sigma = null;
            for (SiteSim.Summary s : tested.keySet()) {
                for (Object x : s.list("differences")) {
                    Map<String, Object> d = SiteAdapt.obj(x, "a difference");
                    if (s.check.equals("P3") && pair[1].equals(d.get("fig")) && pair[2].equals(d.get("lessFig"))) {
                        sigma = SiteAdapt.num(d.get("sigmaEff"), "sigmaEff");
                    }
                }
            }
            if (sigma == null) {
                continue;
            }
            SimCheckPlan.Figure a = figure(pair[1]);
            SimCheckPlan.Figure b = figure(pair[2]);
            int pen = StrategyExport.penIndex(a.pen);
            int m = SystemScorer.same(SystemScorer.SPREADS);
            double se = SystemScorer.difference(entry(p, a), entry(p, b), pen, m, 1).se;
            long n = (long) Math.ceil(sigma * sigma / (se * se));
            String at = "(" + sigma + " / " + se + ")^2: the simulator's σ_eff of " + pair[1] + " less " + pair[2]
                    + " a round over the engine's paired SE of the same difference";
            Map<String, Object> f = in.figure("sim.rounds_needed." + pair[0], n, "n");
            SiteInput.at(f, null, at);
            chosen = n;
            chosenAt = at;
        }
        if (chosen != null) {
            SiteInput.at(in.figure("sim.rounds_needed", chosen, "n"), null, chosenAt);
        }
    }

    /** Mode B and the moving count (summary-variants.json): each paired difference, reported, never tested. */
    static void variants(SiteInput in, SiteSim.Options o) throws IOException {
        if (o.variants.isEmpty()) {
            return;
        }
        SiteSim.Plan plan = SiteSim.Plan.read(o.plan);
        List<Object> table = new ArrayList<>();
        for (Path file : o.variants) {
            SiteSim.Summary s = new SiteSim.Summary(file);
            if (!SiteSim.on(s).isEmpty()) {
                throw new IllegalArgumentException(file + ": mode B with a planted bug on is not a result");
            }
            if (!s.fact("chartFingerprint").equals(SiteSim.CHART_FINGERPRINT)) {
                throw new IllegalArgumentException(file + " played another chart than " + SiteSim.CHART_FINGERPRINT);
            }
            if (!plan.bundles.getOrDefault(2, List.of()).contains(s.fact("bundleSha256")) || !plan.commit(s.fact("simulatorCommit"))) {
                throw new IllegalArgumentException(file + ": a bundle or simulator commit SIM_CHECK.md's identity table does "
                        + "not name for part 2");
            }
            long shoes = SimCheckPlan.MODE_B_CHUNKS * SimCheckPlan.P3_CHUNK_SHOES;
            if (Long.parseLong(s.fact("shoes")) != shoes) {
                throw new IllegalArgumentException(file + " played " + s.fact("shoes") + " shoes; mode B plays " + shoes);
            }
            in.artifact(file, file.toString());
            for (Object x : s.list("differences")) {
                Map<String, Object> d = SiteAdapt.obj(x, "a difference");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("fig", d.get("fig"));
                row.put("what", d.get("less"));
                row.put("difference", SiteAdapt.num(d.get("difference"), "difference"));
                row.put("se", SiteAdapt.num(d.get("se"), "se"));
                row.put("sigma_eff", SiteAdapt.num(d.get("sigmaEff"), "sigmaEff"));
                row.put("rounds", SiteAdapt.num(d.get("rounds"), "rounds"));
                table.add(row);
            }
        }
        in.tables.put("sim_variants", table);
        SiteInput.at(in.figure("sim.variants", table.size(), "n"), null, "mode B and moving-count differences reported");
    }

    /** The lines of a tab-separated file, '#' lines and its header left out; the header checked. */
    static List<String[]> tsv(Path file, String header) throws IOException {
        List<String[]> out = new ArrayList<>();
        boolean head = header == null;
        for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            if (!head) {
                if (!l.equals(header)) {
                    throw new IllegalArgumentException(file + "'s header is \"" + l + "\", not \"" + header + "\"");
                }
                head = true;
                continue;
            }
            out.add(l.split("\t", -1));
        }
        return out;
    }

    /** depth.tsv of the first run that wrote one with the exact side: the chart's flat result by band of 26 cards dealt. */
    static void depth(SiteInput in, SiteSim.Options o) throws IOException {
        for (Path dir : o.runs) {
            Path file = dir.resolve("depth.tsv");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            List<String[]> lines = tsv(file, "band\tfrom\tto\trounds\tshoes\tsimulated\tse\texactWeight\texact");
            if (lines.isEmpty() || lines.get(0)[8].isEmpty()) {
                continue;
            }
            List<Object> table = new ArrayList<>();
            for (String[] f : lines) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("from", Integer.parseInt(f[1]));
                row.put("to", Integer.parseInt(f[2]));
                row.put("rounds", Double.parseDouble(f[3]));
                row.put("simulated", Double.parseDouble(f[5]));
                row.put("se", Double.parseDouble(f[6]));
                row.put("exact", Double.parseDouble(f[8]));
                table.add(row);
            }
            in.artifact(file, file.toString());
            in.tables.put("sim_depth", table);
            return;
        }
    }

    /**
     * ev-by-key.tsv (SHOE_SPEC.md C4): for each row and key, the exact edge (Σ (1/q)·x over Σ 1/q of
     * states.bin's states at that key) and the simulator's mean result a round at that key for each
     * fold, Σ Y over Σ N, with its ratio estimator's SE over shoes. The folds are given apart, not
     * pooled: the pooling weights are exact.tsv's, which this reader does not take.
     */
    static void evByKey(SiteInput in, SiteSim.Options o) throws IOException {
        List<Object> table = new ArrayList<>();
        for (Path dir : o.runs) {
            Path file = dir.resolve("ev-by-key.tsv");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
            for (String[] f : tsv(file, null)) {
                String row = f[1] + "\t" + f[2] + "\t" + (f[0].equals("sim") ? f[4] : f[3]);
                Map<String, Object> r = rows.computeIfAbsent(row, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("row", f[1]);
                    m.put("pen", Integer.parseInt(f[2]));
                    m.put("key", Integer.parseInt(f[0].equals("sim") ? f[4] : f[3]));
                    return m;
                });
                if (f[0].equals("sim")) {
                    double s = Double.parseDouble(f[5]);
                    double n = Double.parseDouble(f[6]);
                    double n2 = Double.parseDouble(f[7]);
                    double y = Double.parseDouble(f[8]);
                    double y2 = Double.parseDouble(f[9]);
                    double yn = Double.parseDouble(f[10]);
                    double mean = n == 0 ? Double.NaN : y / n;
                    double se = n == 0 || s < 2 ? Double.NaN
                            : Math.sqrt(s / (s - 1) * Math.max(0, y2 - 2 * mean * yn + mean * mean * n2)) / n;
                    r.put("sim" + f[3], mean);
                    r.put("se" + f[3], se);
                } else if (f[0].equals("exact")) {
                    double w = Double.parseDouble(f[4]);
                    r.put("exact", w == 0 ? null : Double.parseDouble(f[5]) / w);
                }
            }
            table.addAll(rows.values());
            in.artifact(file, file.toString());
        }
        if (!table.isEmpty()) {
            in.tables.put("sim_ev_by_key", table);
        }
    }

    /**
     * The planted-bug runs (SIM_CHECK.md section 10), each in two halves (open item 18): one on the
     * bundle the identity table names for part 1, scoring F03, F15 and F18, one on part 2's,
     * scoring F26 to F29, each half with the run's switch on and every other off, at one chunk and
     * K = 6,000. Refused: a run with a half missing or two halves on one part's bundle, and a run
     * whose halves do not make its 14 tests once. Given for each: which D checks failed in either
     * half, each planted figure's z by P3 and P2, the largest, whether it was caught (a D check
     * failed or |z| reached 3.5) and by the z tests alone, and the bias each test would catch 90%
     * and 95% of the time at this size. The clean control (none) must pass on both halves, or the
     * planted runs say nothing about the z tests.
     */
    static void planted(SiteInput in, SiteSim.Options o) throws IOException {
        if (o.planted.isEmpty()) {
            return;
        }
        if (!o.planted.containsKey("none")) {
            throw new IllegalArgumentException("the planted runs come with their clean control, --planted none=DIR");
        }
        SiteSim.Plan plan = SiteSim.Plan.read(o.plan);
        double m90 = SimCheckPlan.detectionMultiple(0.90);
        double m95 = SimCheckPlan.detectionMultiple(0.95);
        List<String> want = new ArrayList<>();
        for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
            if (SimCheckPlan.plantedFigure(f)) {
                for (String check : SiteSim.checks(f)) {
                    want.add(f.id + "." + check);
                }
            }
        }
        List<Object> runs = new ArrayList<>();
        List<Object> tests = new ArrayList<>();
        int caught = 0;
        boolean controlPasses = false;
        for (Map.Entry<String, List<Path>> e : o.planted.entrySet()) {
            String name = e.getKey();
            if (!name.equals("none") && !SiteSim.SWITCHES.contains(name)) {
                throw new IllegalArgumentException("no planted bug " + name + "; SIM_CHECK.md section 10 names "
                        + SiteSim.SWITCHES);
            }
            java.util.Set<String> failedD = new java.util.TreeSet<>();
            Map<Integer, Path> halves = new java.util.TreeMap<>();
            java.util.Set<String> made = new java.util.LinkedHashSet<>();
            double zMax = 0;
            for (Path dir : e.getValue()) {
                failedD.addAll(SiteSim.checkNames(SiteSim.failedChecks(dir.resolve("checks.txt"))));
                Integer half = null;
                String bundle = null;
                for (SiteSim.Summary s : SiteSim.summaries(dir)) {
                    List<String> on = SiteSim.on(s);
                    if (!on.equals(name.equals("none") ? List.of() : List.of(name))) {
                        throw new IllegalArgumentException(s.file + " has " + on + " on; the run " + name + " has "
                                + (name.equals("none") ? "none" : "its own switch alone"));
                    }
                    List<SiteSim.Test> all = SiteSim.tests(s);
                    int part = SiteSim.part(s, all);
                    SiteSim.holdFacts(s, plan, part, true);
                    if (half != null && half != part || bundle != null && !bundle.equals(s.fact("bundleSha256"))) {
                        throw new IllegalArgumentException(dir + ": the planted run " + name + "'s P3 and P2 played other bundles");
                    }
                    if (half == null && halves.containsKey(part)) {
                        throw new IllegalArgumentException("the planted run " + name + " has two halves on part " + part
                                + "'s bundle, " + halves.get(part) + " and " + dir + "; SIM_CHECK.md section 10 makes one on "
                                + "each part's");
                    }
                    halves.put(part, dir);
                    half = part;
                    bundle = s.fact("bundleSha256");
                    in.artifact(s.file, s.file.toString());
                    for (SiteSim.Test t : SiteSim.ofPart(all, part)) {
                        if (t.fig == null || !SimCheckPlan.plantedFigure(t.fig) || Double.isNaN(t.z)) {
                            continue;
                        }
                        if (!made.add(t.test())) {
                            throw new IllegalArgumentException("the planted run " + name + " makes " + t.test() + " twice");
                        }
                        zMax = Math.max(zMax, Math.abs(t.z));
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("run", name);
                        row.put("part", part);
                        row.put("test", t.test());
                        row.put("z", t.z);
                        row.put("caught90", m90 * t.se);
                        row.put("caught95", m95 * t.se);
                        tests.add(row);
                    }
                }
            }
            for (int part = 1; part <= 2; part++) {
                if (!halves.containsKey(part)) {
                    throw new IllegalArgumentException("the planted run " + name + " has no half on part " + part + "'s bundle; "
                            + "SIM_CHECK.md section 10 makes each planted run, and the control, on both parts' bundles");
                }
            }
            List<String> lack = new ArrayList<>(want);
            lack.removeAll(made);
            if (!lack.isEmpty()) {
                throw new IllegalArgumentException("the planted run " + name + " lacks " + lack.size() + " of its " + want.size()
                        + " tests, the first " + lack.get(0) + " (SIM_CHECK.md section 10)");
            }
            boolean byZ = zMax >= SimCheckPlan.Z_FAIL;
            boolean isCaught = byZ || !failedD.isEmpty();
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("run", name);
            run.put("d_failed", new ArrayList<>(failedD));
            run.put("z_max", zMax);
            run.put("caught", isCaught);
            run.put("caught_by_z", byZ);
            runs.add(run);
            if (name.equals("none")) {
                controlPasses = !isCaught;
            } else {
                caught += isCaught ? 1 : 0;
            }
        }
        in.tables.put("sim_planted", runs);
        in.tables.put("sim_planted_tests", tests);
        SiteInput.at(in.figure("sim.planted", o.planted.size() - 1, "n"), null, "planted-bug runs");
        SiteInput.at(in.figure("sim.planted_caught", caught, "n"), null, "caught by a D check or |z| of 3.5 or more");
        SiteInput.at(in.figure("sim.planted_control_passes", controlPasses ? 1 : 0, "n"), null,
                "1 when the clean control passed; else the planted runs say nothing about the z tests");
    }
}
