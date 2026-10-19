import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The clean-room simulator's runs as an input of the page generator: the second program's section
 * (PLAN_PAGE.md 8), its z table, and what SIM_CHECK.md section 9 reports beside it. Several runs
 * are taken at once, since part 1 and part 2 are separate bundles and part 2's rows come from two
 * (the replay's and the page look's), and SIM_CHECK.md's pass rule applies to their union.
 *
 * SiteAdapt sim --plan SIM_CHECK.md [--published RESULTS.ser] --run DIR [--run DIR ...]
 *     [--variants summary-variants.json ...] [--planted NAME=DIR ...] --out runs/site/sim-SHA.json
 *
 * Each --run DIR holds what sim.Sim p2 and p3 write there: summary-p2.json, summary-p3.json, and
 * checks.txt, with depth.tsv and ev-by-key.tsv beside them when P3 wrote them. What it refuses,
 * as SIM_CHECK.md section 12 says, naming the first thing wrong:
 * - a run without checks.txt, or whose checks.txt lacks D1, D2, D4 or D5 or shows one failed
 *   (any mismatch, a check not run, D1's round starts other than A8's), or does not point to D3;
 * - a summary with a planted bug on (planted runs go through --planted only), a chart fingerprint
 *   other than SHOE_SPEC.md A2's 1af534aac9abcb39, a bundle or simulator commit SIM_CHECK.md's
 *   identity table does not name for its part, or sizes other than section 8's filled block for
 *   its part (P3's shoes the block's chunks of a million, P2's K the block's K);
 * - a z its own figures do not give within 1e-9 relative, a pass or flag that is not the rule's;
 * - a comparison the simulator named as another figure than SIM_CHECK.md's registry gives it, a
 *   registered test met twice, or a registered test of a part the runs cover not met at all.
 * A comparison the registry does not name is reported in the table and is not a test.
 *
 * Part 2's runs across its bundles (INTEGRATION.md open item 17). Part 2's figures are in three
 * bundles, each run on its own and held to the one section 8 block its pilot files fill
 * (SimCheckPlan.Pilots), every bundle one the identity table names for part 2, and the union of
 * the runs making each registered test once. P3 and P2 play the chart's walk on every bundle, so
 * a part 2 summary also compares F01 to F03 (F03 alone in P2), with no exact side, since only part
 * 1's bundle exports the chart's row. Those comparisons are the chart's walk and not tests of the
 * run; they are left out (chartWalk). A comparison of a part 1 figure with an exact side in a part
 * 2 summary, or the chart's without one in a part 1 summary, is still refused or failed.
 *
 * Planted runs (--planted NAME=DIR, given twice for each NAME, NAME a switch of SIM_CHECK.md
 * section 10 or none for the clean control): each half must have its switch on and every other
 * off and the sizes of SHOE_SPEC.md answer 12 (P3 on one chunk, P2 at K = 6,000), one half on the
 * bundle the identity table names for part 1 and one on part 2's (section 10, open item 18), and
 * the two must make the run's 14 tests once; the page block gives which D checks failed in either
 * half, every test's z on the planted figures, the largest, whether the run was caught and
 * whether by the z tests alone, and the bias each test catches 90% and 95% of the time.
 *
 * Reported beside the tests (section 9): mode B and the moving count (summary-variants.json), the
 * rounds a simulation would need to tell Victor APC + ace from Hi-Lo ({N} of PLAN_PAGE.md 3: σ_eff of
 * the per-round difference over the engine's paired SE of the same difference in c06c061's
 * results.ser, squared), the house edge by depth (depth.tsv) and the edge at each count
 * (ev-by-key.tsv, SHOE_SPEC.md C4), each fold apart.
 */
final class SiteSim {

    /** SHOE_SPEC.md A2: the chart every reported run plays (answer 4). */
    static final String CHART_FINGERPRINT = "1af534aac9abcb39";
    /** The exact checks a run's checks.txt must show passed (D3 is the engine's test, D6 the simulator's). */
    static final String[] D_CHECKS = {"D1", "D2", "D4", "D5"};
    /** The planted-bug switches of SIM_CHECK.md section 10, as the simulator names them. */
    static final List<String> SWITCHES = List.of("s17", "no-das", "split-aces-hit", "hole-card", "truncate",
            "insure-whole-bet", "other-fold", "bet-key-plus-one");

    private SiteSim() {
    }

    /** What the adapter is given. */
    static final class Options {
        Path plan;
        Path published;
        final List<Path> runs = new ArrayList<>();
        final List<Path> variants = new ArrayList<>();
        /** Each planted run's halves, one a bundle (SIM_CHECK.md section 10). */
        final Map<String, List<Path>> planted = new LinkedHashMap<>();
    }

    /** SiteAdapt sim's arguments, --out left to SiteAdapt. */
    static Options parse(List<String> args) {
        Options o = new Options();
        for (int k = 0; k < args.size(); k++) {
            String a = args.get(k);
            switch (a) {
                case "--plan": o.plan = Paths.get(args.get(++k)); break;
                case "--published": o.published = Paths.get(args.get(++k)); break;
                case "--run": o.runs.add(Paths.get(args.get(++k))); break;
                case "--variants": o.variants.add(Paths.get(args.get(++k))); break;
                case "--planted": {
                    String v = args.get(++k);
                    int eq = v.indexOf('=');
                    if (eq < 0) {
                        throw new IllegalArgumentException("--planted takes NAME=DIR, not " + v);
                    }
                    o.planted.computeIfAbsent(v.substring(0, eq), x -> new ArrayList<>()).add(Paths.get(v.substring(eq + 1)));
                    break;
                }
                default:
                    throw new IllegalArgumentException("usage: SiteAdapt sim --plan SIM_CHECK.md [--published RESULTS.ser] "
                            + "--run DIR [--run DIR ...] [--variants FILE ...] [--planted NAME=DIR ...] --out OUT.json; not "
                            + a);
            }
        }
        if (o.plan == null || o.runs.isEmpty()) {
            throw new IllegalArgumentException("SiteAdapt sim needs --plan SIM_CHECK.md and at least one --run DIR");
        }
        return o;
    }

    /** What SIM_CHECK.md fixes for the runs: the identity table's commits and bundles, section 8's sizes by part. */
    static final class Plan {
        final List<String> commits = new ArrayList<>();
        final Map<Integer, List<String>> bundles = new LinkedHashMap<>();
        /** {P3 chunks, P2 K} by part, from its filled block; absent while the block is not filled. */
        final Map<Integer, long[]> sizes = new LinkedHashMap<>();

        static Plan read(Path file) throws IOException {
            String doc = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Plan p = new Plan();
            for (String line : doc.split("\n")) {
                if (!line.startsWith("| ")) {
                    continue;
                }
                String[] cells = line.split("\\|");
                String first = cells.length > 1 ? cells[1].trim() : "";
                String value = cells.length > 3 ? cells[3] : "";
                if (first.startsWith("The simulator's commit")) {
                    p.commits.addAll(hex(value, 7, 40));
                } else if (first.startsWith("Part 1's bundle")) {
                    p.bundles.put(1, hex(value, 64, 64));
                } else if (first.startsWith("Part 2's bundle")) {
                    p.bundles.put(2, hex(value, 64, 64));
                }
            }
            Pattern size = Pattern.compile("P3 (\\d+) chunks \\([0-9,]+ shoes\\), P2 K = ([0-9,]+) ");
            for (int part = 1; part <= 2; part++) {
                Matcher m = size.matcher(SimCheckPlan.docBlock(doc, "fill-" + part));
                if (m.find()) {
                    p.sizes.put(part, new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2).replace(",", ""))});
                }
            }
            return p;
        }

        /** Whether a commit is one the table names: the same, or one the prefix of the other, at least 7 digits. */
        boolean commit(String c) {
            for (String x : commits) {
                if (c.length() >= 7 && (c.startsWith(x) || x.startsWith(c))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The hexadecimal tokens of a cell, of the lengths given. */
    static List<String> hex(String cell, int min, int max) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\b[0-9a-f]{" + min + "," + max + "}\\b").matcher(cell);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /**
     * checks.txt's second D2 line, as sim.Sim p2 writes it since 0f087c4 (sim/QUESTIONS.md item 33's
     * sim-checks block): the key-sample columns D2 left out, whose counts neither the catalog nor
     * the bundle's files give (SHOE_SPEC.md B4). It reports; it does not fail D2.
     */
    static final String D2_LEFT_OUT = "columns left out, since no count of the catalog or of the bundle's files has the name: ";
    /**
     * The note sim.Sim p2 writes since 0f087c4 for a count a strategy file of the bundle plays or
     * bets with that the key sample has no column for: D2 then holds none of its keys and fails,
     * though its first line may show no mismatch (sim/QUESTIONS.md item 33).
     */
    static final Pattern D2_FILE_COUNT_MISSING = Pattern.compile("count (.+), which a strategy file of the bundle plays or "
            + "bets with: no column of keys-sample\\.tsv's header has this name, so D2 holds none of its keys");

    /** checks.txt's second D5 line, as sim.Sim p2 writes it since ae283e4. */
    static final Pattern SPLIT_CELLS = Pattern.compile("split cells, the full-play files' columns of (\\d+) split lines: "
            + "(\\d+) of (\\d+) equal to lookups\\.tsv's; (\\d+) mismatches; the other files' columns there, each -: "
            + "(\\d+) of (\\d+) equal; the bundle holds (a|no) file of scope full");

    /**
     * checks.txt as sim.Sim p2 writes it: the D checks that failed, each with its line (nothing when
     * all of D1, D2, D4 and D5 passed); a check missing, not run, with a mismatch or a count short
     * of its total, D1's round starts other than A8's, D2's catalog counts missing, or no line for
     * D3 is a failure. D2's second line, the columns it left out (SHOE_SPEC.md B4), passes when
     * its count of names is right; a note that a count of the bundle's files has no column fails
     * D2. D5's line for the split cells (SHOE_SPEC.md B8, counted apart as sim/QUESTIONS.md
     * item 28 words them) passes with none compared on a bundle with no full-play file, and fails
     * with none on a bundle with one (SIM_CHECK.md section 5).
     */
    static List<String> failedChecks(Path checks) throws IOException {
        if (!Files.isRegularFile(checks)) {
            return List.of("no checks.txt at " + checks);
        }
        List<String> lines = Files.readAllLines(checks, StandardCharsets.UTF_8);
        List<String> failed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Pattern of = Pattern.compile("(\\d+) of (\\d+) ");
        Pattern mismatches = Pattern.compile("; (\\d+) mismatches");
        Pattern a8 = Pattern.compile("(\\d+) round starts, A8 says (\\d+)");
        for (String l : lines) {
            if (D2_FILE_COUNT_MISSING.matcher(l).matches()) {
                failed.add("D2: " + l);
                continue;
            }
            String[] f = l.split("\t", 2);
            if (f.length < 2 || !f[0].matches("D\\d")) {
                continue;
            }
            seen.add(f[0]);
            if (f[0].equals("D3")) {
                continue;
            }
            Matcher m = of.matcher(f[1]);
            Matcher x = mismatches.matcher(f[1]);
            Matcher y = a8.matcher(f[1]);
            boolean ok;
            Matcher s = SPLIT_CELLS.matcher(f[1]);
            if (f[1].startsWith("not run")) {
                ok = false;
            } else if (f[0].equals("D2") && f[1].startsWith(D2_LEFT_OUT)) {
                // The columns D2 left out: N, then N names, tab-separated.
                String[] g = f[1].substring(D2_LEFT_OUT.length()).split("\t", -1);
                ok = g[0].matches("\\d+") && Integer.parseInt(g[0]) == g.length - 1;
            } else if (f[0].equals("D5") && s.matches()) {
                long compared = Long.parseLong(s.group(3));
                ok = s.group(2).equals(s.group(3)) && s.group(4).equals("0") && s.group(5).equals(s.group(6))
                        && (s.group(7).equals("a") ? compared > 0 : compared == 0);
            } else if (y.find()) {
                ok = y.group(1).equals(y.group(2));
            } else if (m.find() && x.find()) {
                ok = m.group(1).equals(m.group(2)) && Long.parseLong(m.group(2)) > 0 && x.group(1).equals("0");
                int missing = f[1].indexOf("missing from its header: ");
                if (missing >= 0) {
                    String rest = f[1].substring(missing + "missing from its header: ".length()).trim();
                    ok &= rest.isEmpty() || rest.equals("[]") || rest.equals("none");
                }
            } else {
                // A line that only continues a check (its first mismatches, listed under it).
                ok = false;
            }
            if (!ok) {
                failed.add(f[0] + ": " + f[1]);
            }
        }
        for (String d : D_CHECKS) {
            if (!seen.contains(d)) {
                failed.add(d + ": no line in " + checks);
            }
        }
        if (!seen.contains("D3")) {
            failed.add("D3: checks.txt does not point to the engine's test/SimVsExactSmallShoeTest.java");
        }
        return failed;
    }

    /** The D checks of a failure list, by name, in order (D1, D2, D3, D4, D5). */
    static List<String> checkNames(List<String> failed) {
        Set<String> out = new java.util.TreeSet<>();
        for (String f : failed) {
            out.add(f.substring(0, 2));
        }
        return new ArrayList<>(out);
    }

    /** One summary read, with its check, its facts and its directory. */
    static final class Summary {
        final Path file;
        final Map<String, Object> d;
        final String check;

        Summary(Path file) throws IOException {
            this.file = file;
            this.d = SiteAdapt.read(file);
            this.check = SiteAdapt.str(d.get("check"), "check");
        }

        String fact(String key) {
            Object v = d.get(key);
            if (!(v instanceof String)) {
                throw new IllegalArgumentException(file + " gives no " + key);
            }
            return (String) v;
        }

        List<Object> list(String key) {
            Object v = d.get(key);
            return v == null ? List.of() : SiteAdapt.list(v, key);
        }
    }

    /** The switches a summary's plants fact has on; refused unless it names every switch. */
    static List<String> on(Summary s) {
        String plants = s.fact("plants");
        List<String> out = new ArrayList<>();
        for (String w : plants.trim().split("\\s+")) {
            if (w.endsWith("=on")) {
                out.add(w.substring(0, w.length() - 3));
            } else if (!w.endsWith("=off")) {
                throw new IllegalArgumentException(s.file + ": a plants fact of switches NAME=on or NAME=off, not " + plants);
            }
        }
        return out;
    }

    /**
     * The facts of a summary held to SIM_CHECK.md: the chart's fingerprint, the bundle of its part,
     * the simulator's commit, and its sizes: shoes for P3, K for P2 (the planted runs' sizes when
     * planted, mode B's for variants).
     */
    static void holdFacts(Summary s, Plan plan, int part, boolean planted) {
        String fp = s.fact("chartFingerprint");
        if (!fp.equals(CHART_FINGERPRINT)) {
            throw new IllegalArgumentException(s.file + " played the chart of fingerprint " + fp + ", not SHOE_SPEC.md A2's "
                    + CHART_FINGERPRINT + " (answer 4)");
        }
        List<String> bundles = plan.bundles.getOrDefault(part, List.of());
        if (!bundles.contains(s.fact("bundleSha256"))) {
            throw new IllegalArgumentException(s.file + " played bundle " + s.fact("bundleSha256") + ", which SIM_CHECK.md's "
                    + "identity table does not name for part " + part + " (" + (bundles.isEmpty() ? "it names none yet"
                    : String.join(", ", bundles)) + ")");
        }
        if (!plan.commit(s.fact("simulatorCommit"))) {
            throw new IllegalArgumentException(s.file + " was run by the simulator at " + s.fact("simulatorCommit")
                    + ", which SIM_CHECK.md's identity table does not name (" + (plan.commits.isEmpty() ? "it names none yet"
                    : String.join(", ", plan.commits)) + ")");
        }
        long[] size = plan.sizes.get(part);
        if (size == null && !planted) {
            throw new IllegalArgumentException(s.file + ": SIM_CHECK.md section 8's block for part " + part
                    + " is not filled, so no main run of it is held to sizes yet");
        }
        long chunks = planted ? SimCheckPlan.PLANTED_CHUNKS : size[0];
        long k = planted ? SimCheckPlan.PLANTED_K : size[1];
        if (s.check.equals("P3") && Long.parseLong(s.fact("shoes")) != chunks * SimCheckPlan.P3_CHUNK_SHOES) {
            throw new IllegalArgumentException(s.file + " played " + s.fact("shoes") + " shoes, not the " + chunks
                    + " chunks of " + SimCheckPlan.P3_CHUNK_SHOES + (planted ? " a planted run plays" : " section 8 fixes"));
        }
        if (s.check.equals("P2") && Long.parseLong(s.fact("k")) != k) {
            throw new IllegalArgumentException(s.file + " re-dealt each state K = " + s.fact("k") + " times, not the "
                    + k + (planted ? " a planted run plays" : " section 8 fixes"));
        }
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
        return new String[]{entry, f.exportFigure()};
    }

    /** The registered figure a comparison is, by its row, penetration and figure; null for none. */
    static SimCheckPlan.Figure registered(String row, int pen, String figure) {
        for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
            String[] l = label(f);
            if (l[0].equals(row) && l[1].equals(figure) && f.pen == pen) {
                return f;
            }
        }
        return null;
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

    /** One comparison read and its z worked out again from its own figures, the file's z and pass held to it. */
    static final class Test {
        final String check;
        final String name;
        final String figure;
        final int pen;
        final double z;
        final double se;
        final SimCheckPlan.Figure fig;
        final double rounds;
        final double exact;

        Test(Summary s, Map<String, Object> c) {
            check = s.check;
            name = SiteAdapt.str(c.get("row"), "row");
            figure = SiteAdapt.str(c.get("figure"), "figure");
            pen = Integer.parseInt(SiteAdapt.pen(c.get("pen")));
            exact = SiteAdapt.num(c.get("exact"), "exact");
            rounds = c.get("rounds") == null ? Double.NaN : SiteAdapt.num(c.get("rounds"), "rounds");
            double sd = Math.sqrt(sq(SiteAdapt.num(c.get("simulatedSe"), "simulatedSe"))
                    + sq(SiteAdapt.num(c.get("exactSe"), "exactSe")));
            se = sd;
            double zz = Double.NaN;
            if (!Double.isNaN(exact)) {
                zz = (SiteAdapt.num(c.get("simulated"), "simulated") - exact) / sd;
                double given = SiteAdapt.num(c.get("z"), "z");
                if (Math.abs(zz - given) > 1e-9 * Math.max(1, Math.abs(zz))) {
                    throw new IllegalArgumentException(s.file + ": " + check + " " + name + " " + figure + " at " + pen
                            + "%: z is " + given + " in the file, " + zz + " from its own figures");
                }
                boolean pass = Math.abs(zz) < SimCheckPlan.Z_FAIL;
                boolean flag = pass && Math.abs(zz) >= SimCheckPlan.Z_FLAG;
                if (!Boolean.valueOf(pass).equals(c.get("pass")) || !Boolean.valueOf(flag).equals(c.get("flagged"))) {
                    throw new IllegalArgumentException(s.file + ": " + check + " " + name + " " + figure
                            + ": its pass or flag is not SIM_CHECK.md's rule for z " + zz);
                }
            }
            z = zz;
            fig = registered(name, pen, figure);
            // The simulator names each comparison by the id it read from SIM_CHECK.md's sim-registry
            // block ("" when it ran without one); a name that is not the figure matched here means the
            // two sides read the registry differently.
            Object named = c.get("fig");
            String id = fig == null ? null : fig.id;
            if (named != null && !"".equals(named) && !named.equals(id)) {
                throw new IllegalArgumentException(s.file + ": " + check + " " + name + " " + figure + " at " + pen
                        + "%: the simulator names it " + named + ", SIM_CHECK.md's registry " + id);
            }
            if (fig != null && !checks(fig).contains(check)) {
                throw new IllegalArgumentException(s.file + ": " + check + " of " + fig.id + " is not a registered test (its "
                        + "tests are " + String.join(", ", checks(fig)) + ")");
            }
        }

        String test() {
            return fig.id + "." + check;
        }

        /** The chart's walk with no exact side: what P3 and P2 compare on a bundle without the chart's row. */
        boolean chartWalk() {
            return fig != null && fig.play == SimCheckPlan.Play.CHART && Double.isNaN(exact);
        }

        String verdict() {
            return Double.isNaN(z) ? "no exact side" : Math.abs(z) >= SimCheckPlan.Z_FAIL ? "fails"
                    : Math.abs(z) >= SimCheckPlan.Z_FLAG ? "flagged" : "passes";
        }
    }

    private static double sq(double x) {
        return Double.isNaN(x) ? 0 : x * x;
    }

    private static void put(SiteInput in, String key, double v, String fmt, String at) {
        Map<String, Object> m = in.figure(key, v, fmt);
        SiteInput.at(m, null, at);
    }

    /** A registered figure's tests: P3, and P2 at 80%. */
    static List<String> checks(SimCheckPlan.Figure f) {
        return f.p2() ? List.of("P3", "P2") : List.of("P3");
    }

    /** The summaries of a run directory: summary-p3.json and summary-p2.json, whichever it holds. */
    static List<Summary> summaries(Path dir) throws IOException {
        List<Summary> out = new ArrayList<>();
        for (String f : new String[]{"summary-p3.json", "summary-p2.json"}) {
            if (Files.isRegularFile(dir.resolve(f))) {
                out.add(new Summary(dir.resolve(f)));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException(dir + " holds no summary-p3.json or summary-p2.json");
        }
        return out;
    }

    /**
     * The part a summary's registered comparisons are of, the chart's walk without an exact side
     * aside (it is compared on every bundle); refused when they are of both parts, or of none.
     */
    static int part(Summary s, List<Test> tests) {
        Set<Integer> parts = new LinkedHashSet<>();
        for (Test t : tests) {
            if (t.fig != null && !t.chartWalk()) {
                parts.add(t.fig.part);
            }
        }
        if (parts.size() != 1) {
            throw new IllegalArgumentException(s.file + " compares " + (parts.isEmpty() ? "no registered figure"
                    : "figures of both parts") + "; a run is of one part's bundle (SIM_CHECK.md section 3)");
        }
        return parts.iterator().next();
    }

    /** A summary's comparisons of its part: the chart's walk on another part's bundle, with no exact side, left out. */
    static List<Test> ofPart(List<Test> tests, int part) {
        List<Test> out = new ArrayList<>();
        for (Test t : tests) {
            if (!(t.chartWalk() && t.fig.part != part)) {
                out.add(t);
            }
        }
        return out;
    }

    static List<Test> tests(Summary s) {
        List<Test> out = new ArrayList<>();
        for (Object o : s.list("comparisons")) {
            out.add(new Test(s, SiteAdapt.obj(o, "a comparison")));
        }
        return out;
    }

    /** The adapter: every refusal of the class comment, then the figures and tables. */
    static SiteInput adapt(Options o) throws IOException {
        Plan plan = Plan.read(o.plan);
        List<Summary> all = new ArrayList<>();
        Map<Summary, List<Test>> tested = new LinkedHashMap<>();
        Set<Integer> covered = new java.util.TreeSet<>();
        String commit = null;
        for (Path dir : o.runs) {
            List<String> failed = failedChecks(dir.resolve("checks.txt"));
            if (!failed.isEmpty()) {
                throw new IllegalArgumentException(dir + ": an exact check is missing or failed, so no figure of this run is "
                        + "reported (SIM_CHECK.md section 5): " + failed.get(0));
            }
            for (Summary s : summaries(dir)) {
                if (!on(s).isEmpty()) {
                    throw new IllegalArgumentException(s.file + ": a summary with a planted bug on (" + on(s) + ") is not a "
                            + "result; planted runs are given with --planted");
                }
                List<Test> t = tests(s);
                int part = part(s, t);
                t = ofPart(t, part);
                holdFacts(s, plan, part, false);
                covered.add(part);
                all.add(s);
                tested.put(s, t);
                commit = commit == null ? s.fact("simulatorCommit") : commit;
            }
            String bundle = null;
            for (Summary s : summaries(dir)) {
                if (bundle != null && !bundle.equals(s.fact("bundleSha256"))) {
                    throw new IllegalArgumentException(dir + "'s P3 and P2 summaries played other bundles");
                }
                bundle = s.fact("bundleSha256");
            }
        }
        SiteInput in = new SiteInput("sim", commit, "main");
        for (Summary s : all) {
            in.artifact(s.file, s.file.toString());
        }
        Map<String, Test> byTest = new LinkedHashMap<>();
        List<Object> table = new ArrayList<>();
        long shoes = 0;
        long rounds = 0;
        for (Map.Entry<Summary, List<Test>> e : tested.entrySet()) {
            if (e.getKey().check.equals("P3")) {
                shoes = Math.max(shoes, Long.parseLong(e.getKey().fact("shoes")));
            }
            for (Test t : e.getValue()) {
                if (t.fig != null && byTest.put(t.test(), t) != null) {
                    throw new IllegalArgumentException("the runs give " + t.test() + " twice; each registered test is made once");
                }
                if (t.check.equals("P3") && !Double.isNaN(t.rounds)) {
                    if (t.rounds != Math.rint(t.rounds)) {
                        throw new IllegalArgumentException("P3 played " + t.rounds + " rounds of " + t.name + ", not a whole number");
                    }
                    rounds = Math.max(rounds, (long) t.rounds);
                }
                String[] row = row(t.name);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("check", t.check);
                m.put("figure", t.fig == null ? null : t.fig.id);
                m.put("key", row[1] + "." + t.pen + "." + spread(t.figure) + "." + row[0]);
                m.put("z", Double.isNaN(t.z) ? null : t.z);
                m.put("verdict", t.fig == null ? "reported, not a registered test" : t.verdict());
                table.add(m);
            }
        }
        List<String> missing = new ArrayList<>();
        for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
            for (String check : covered.contains(f.part) ? checks(f) : List.<String>of()) {
                if (!byTest.containsKey(f.id + "." + check)) {
                    missing.add(f.id + "." + check);
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("the runs cover part" + (covered.size() > 1 ? "s " : " ") + covered + " and lack "
                    + missing.size() + " of its registered tests, the first " + missing.get(0) + " (SIM_CHECK.md section 12)");
        }
        int passed = 0, flagged = 0, failed = 0, tests = 0;
        double zMax = 0;
        for (Test t : byTest.values()) {
            if (Double.isNaN(t.z)) {
                throw new IllegalArgumentException(t.test() + " has no exact side; a registered test needs one");
            }
            tests++;
            boolean pass = Math.abs(t.z) < SimCheckPlan.Z_FAIL;
            passed += pass ? 1 : 0;
            flagged += pass && Math.abs(t.z) >= SimCheckPlan.Z_FLAG ? 1 : 0;
            failed += pass ? 0 : 1;
            zMax = Math.max(zMax, Math.abs(t.z));
        }
        put(in, "sim.shoes", shoes, "n", "the most shoes of the P3 summaries");
        put(in, "sim.rounds", rounds, "n", "the most rounds of P3's comparisons");
        put(in, "sim.tests", tests, "n", "SIM_CHECK.md's registered tests of the parts covered");
        put(in, "sim.passed", passed, "n", "|z| under " + SimCheckPlan.Z_FAIL);
        put(in, "sim.flagged", flagged, "n", "|z| from " + SimCheckPlan.Z_FLAG + " to under " + SimCheckPlan.Z_FAIL);
        put(in, "sim.failed", failed, "n", "|z| of " + SimCheckPlan.Z_FAIL + " or more");
        put(in, "sim.z_max", zMax, "d1", "the largest |z| of the registered tests");
        put(in, "sim.registered", tests, "n", "SIM_CHECK.md's tests made");
        put(in, "sim.parts", covered.size(), "n", "the parts covered: " + covered);
        in.tables.put("sim_z", table);
        SiteSimReported.add(in, o, tested);
        return in;
    }
}
