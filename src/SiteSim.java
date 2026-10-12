import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The clean-room simulator's summaries (summary-p3.json and summary-p2.json, sim.Summary.write)
 * as an input of the page generator: the second program's section (PLAN_PAGE.md 8) and its z
 * table.
 *
 * Every comparison is read as the simulator wrote it and its z worked out again here from the
 * sums' results it carries, (simulated - exact) / sqrt(simulatedSe^2 + exactSe^2); a file whose z
 * differs from that, or whose pass and flag disagree with SIM_CHECK.md's rule (fail at |z| of
 * SimCheckPlan.Z_FAIL or more, flagged from Z_FLAG), is refused. Each comparison is matched to
 * the registered figure of SIM_CHECK.md it is (SimCheckPlan.figures: the row as the report names
 * it, the play scope, the penetration and the bet plan), and the figures registered for the
 * checks a summary holds that it does not compare are listed, never filled; a comparison the
 * registry does not name is shown as such. Every one stays in the table, whatever its z: a row
 * past the rule is shown and explained, not dropped.
 *
 * Figures: sim.shoes (P3's fresh shoes), sim.rounds (the most rounds any one of P3's comparisons
 * played: the rounds dealt in those shoes at the deepest cut), sim.tests, sim.passed,
 * sim.flagged and sim.failed (the comparisons with an exact side), sim.z_max (the largest |z|),
 * and sim.registered and sim.missing (registered figures compared and not). The rounds a
 * simulation would need to tell two counts apart (the page's method section) need mode A's
 * paired differences, which the simulator does not write yet, so no sim.rounds_needed is given.
 */
final class SiteSim {

    private SiteSim() {
    }

    /** A registered figure's comparison label: the scope suffix of its row's name and its figure as the simulator names it. */
    static String[] label(SimCheckPlan.Figure f) {
        String entry = f.count.equals("chart") ? "Chart, no count, flat" : SimCheckPlan.ENTRY.get(f.count);
        if (entry == null) {
            throw new IllegalStateException("SIM_CHECK.md names no entry for " + f.count);
        }
        switch (f.play) {
            case FULL: entry += ", full play"; break;
            case BETTING: entry += ", betting only"; break;
            default: break;
        }
        String figure;
        if (f.bets.equals("flat")) {
            figure = "flat";
        } else if (f.bets.matches("1-\\d+")) {
            figure = "ev:same-" + f.bets.substring(2);
        } else if (f.bets.matches("own 1-\\d+")) {
            figure = "ev:own-" + f.bets.substring(6);
        } else {
            // The wide plans: SIM_CHECK.md writes X-1,8 for the plan the scorer labels X-1.0,8.
            figure = "ev:" + f.bets.replaceAll("^X-1,", "X-1.0,");
        }
        return new String[]{entry, figure};
    }

    /** A comparison's row as the page's {id, scope}. */
    static String[] row(String name) {
        if (name.equals("Chart, no count, flat")) {
            return new String[]{"chart", "bet"};
        }
        String scope = name.endsWith(", full play") ? "full" : name.endsWith(", betting only") ? "bet" : "fd";
        String id = SiteEdges.id(name);
        if (id == null) {
            throw new IllegalArgumentException("no page row for the simulator's row " + name);
        }
        return new String[]{id, scope};
    }

    static String spread(String figure) {
        if (figure.equals("flat")) {
            return "s1";
        }
        if (figure.startsWith("ev:same-")) {
            return "s" + figure.substring("ev:same-".length());
        }
        return SiteAdapt.part(figure.substring(figure.indexOf(':') + 1));
    }

    static SiteInput adapt(Path p3, Path p2) throws IOException {
        Map<String, Object> d3 = SiteAdapt.read(p3);
        if (!"P3".equals(d3.get("check"))) {
            throw new IllegalArgumentException(p3 + " is not the simulator's P3 summary");
        }
        Map<String, Object> d2 = p2 == null ? null : SiteAdapt.read(p2);
        if (d2 != null && (!"P2".equals(d2.get("check")) || !d3.get("bundleSha256").equals(d2.get("bundleSha256")))) {
            throw new IllegalArgumentException(p2 + " is not a P2 summary of the bundle P3 played");
        }
        SiteInput in = new SiteInput("sim", SiteAdapt.str(d3.get("simulatorCommit"), "simulatorCommit"), "main");
        in.artifact(p3, p3.toString());
        if (p2 != null) {
            in.artifact(p2, p2.toString());
        }
        for (Map<String, Object> d : d2 == null ? List.of(d3) : List.of(d3, d2)) {
            if (String.valueOf(d.get("plants")).contains("=on") || !String.valueOf(d.get("plants")).contains("=off")) {
                throw new IllegalArgumentException("a summary with a planted bug on is not a result: " + d.get("plants"));
            }
        }
        List<SimCheckPlan.Figure> registry = SimCheckPlan.figures();
        List<Object> table = new ArrayList<>();
        int tests = 0;
        int passed = 0;
        int flagged = 0;
        int failed = 0;
        int registered = 0;
        double zMax = 0;
        long rounds = 0;
        List<String> compared = new ArrayList<>();
        for (Map<String, Object> d : d2 == null ? List.of(d3) : List.of(d3, d2)) {
            String check = (String) d.get("check");
            for (Object o : SiteAdapt.list(d.get("comparisons"), "comparisons")) {
                Map<String, Object> c = SiteAdapt.obj(o, "a comparison");
                String name = SiteAdapt.str(c.get("row"), "row");
                String figure = SiteAdapt.str(c.get("figure"), "figure");
                String pen = SiteAdapt.pen(c.get("pen"));
                double exact = SiteAdapt.num(c.get("exact"), "exact");
                double z = Double.NaN;
                if (!Double.isNaN(exact)) {
                    double se = Math.sqrt(sq(SiteAdapt.num(c.get("simulatedSe"), "simulatedSe"))
                            + sq(SiteAdapt.num(c.get("exactSe"), "exactSe")));
                    z = (SiteAdapt.num(c.get("simulated"), "simulated") - exact) / se;
                    double given = SiteAdapt.num(c.get("z"), "z");
                    if (Math.abs(z - given) > 1e-9 * Math.max(1, Math.abs(z))) {
                        throw new IllegalArgumentException(check + " " + name + " " + figure + " at " + pen + "%: z is "
                                + given + " in the file, " + z + " from its own figures");
                    }
                    boolean pass = Math.abs(z) < SimCheckPlan.Z_FAIL;
                    boolean flag = pass && Math.abs(z) >= SimCheckPlan.Z_FLAG;
                    if (!Boolean.valueOf(pass).equals(c.get("pass")) || !Boolean.valueOf(flag).equals(c.get("flagged"))) {
                        throw new IllegalArgumentException(check + " " + name + " " + figure + ": its pass or flag is not "
                                + "SIM_CHECK.md's rule for z " + z);
                    }
                    tests++;
                    passed += pass ? 1 : 0;
                    flagged += flag ? 1 : 0;
                    failed += pass ? 0 : 1;
                    zMax = Math.max(zMax, Math.abs(z));
                }
                if (check.equals("P3")) {
                    double r = SiteAdapt.num(c.get("rounds"), "rounds");
                    if (r != Math.rint(r)) {
                        throw new IllegalArgumentException("P3 played " + r + " rounds of " + name + ", not a whole number");
                    }
                    rounds = Math.max(rounds, (long) r);
                }
                String id = null;
                for (SimCheckPlan.Figure f : registry) {
                    String[] l = label(f);
                    if (l[0].equals(name) && l[1].equals(figure) && f.pen == Integer.parseInt(pen)) {
                        id = f.id;
                    }
                }
                if (id != null) {
                    registered++;
                    compared.add(check + " " + id);
                }
                String[] row = row(name);
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("check", check);
                t.put("figure", id);
                t.put("key", row[1] + "." + pen + "." + spread(figure) + "." + row[0]);
                t.put("z", Double.isNaN(z) ? null : z);
                t.put("verdict", Double.isNaN(z) ? "no exact side" : Math.abs(z) >= SimCheckPlan.Z_FAIL ? "fails"
                        : Math.abs(z) >= SimCheckPlan.Z_FLAG ? "flagged" : "passes");
                table.add(t);
            }
        }
        List<Object> missing = new ArrayList<>();
        for (SimCheckPlan.Figure f : registry) {
            String[] checks = f.pen == 80 ? new String[]{"P3", "P2"} : new String[]{"P3"};
            for (String check : checks) {
                if ((check.equals("P2") && d2 == null) || compared.contains(check + " " + f.id)) {
                    continue;
                }
                missing.add(check + " " + f.id);
            }
        }
        long shoes = Long.parseLong(SiteAdapt.str(d3.get("shoes"), "shoes"));
        put(in, "sim.shoes", shoes, "n", "summary-p3.json shoes");
        put(in, "sim.rounds", rounds, "n", "the most rounds of P3's comparisons");
        put(in, "sim.tests", tests, "n", "comparisons with an exact side");
        put(in, "sim.passed", passed, "n", "|z| under " + SimCheckPlan.Z_FAIL);
        put(in, "sim.flagged", flagged, "n", "|z| from " + SimCheckPlan.Z_FLAG + " to under " + SimCheckPlan.Z_FAIL);
        put(in, "sim.failed", failed, "n", "|z| of " + SimCheckPlan.Z_FAIL + " or more");
        put(in, "sim.z_max", zMax, "d1", "the largest |z|");
        put(in, "sim.registered", registered, "n", "SIM_CHECK.md's figures compared");
        put(in, "sim.missing", missing.size(), "n", "SIM_CHECK.md's figures of these checks not compared");
        in.tables.put("sim_z", table);
        in.tables.put("sim_missing", missing);
        return in;
    }

    private static double sq(double x) {
        return Double.isNaN(x) ? 0 : x * x;
    }

    private static void put(SiteInput in, String key, double v, String fmt, String at) {
        Map<String, Object> m = in.figure(key, v, fmt);
        SiteInput.at(m, null, at);
    }
}
