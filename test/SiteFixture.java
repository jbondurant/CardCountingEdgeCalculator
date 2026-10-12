import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Made-up inputs for the page generator, in the format of SiteData's input contract, so the
 * generator and its checks can be built and tested before any computation has written runs/site/.
 *
 * Every number here is fake. Each file says {@code "fixture": true}, its commit is the word
 * "fixture", and its artifacts' paths begin fixture/, so the build marks every page it makes from
 * them. The row names are the catalog's (counting-systems.json), so long names and digits in names
 * are exercised; the figures attached to them are drawn from a seeded generator and say nothing
 * about those counts. The rules (decks, payouts) are the engine's, read from HouseRules, since the
 * game block is not a result.
 *
 * The shape is chosen so that the template's claim guards hold, as real data must for the page to
 * build: the top two published rows are close (so "cannot say which is best" holds), the top five
 * sit well above Hi-Lo, and penetration moves Hi-Lo more than the best row's lead. Resamples share
 * a common draw per resample, as the real bootstrap's shared shoes do, so differences are paired.
 *
 * {@code full} sizes the grid at the plan's largest page: about 80 rows × 3 penetrations × 10
 * spreads × 5 scopes (PLAN_PAGE.md 2.5), to test the budgets; otherwise the catalog's rows only.
 */
final class SiteFixture {

    static final String COMMIT = "fixture";
    static final int RESAMPLES = 1000;
    static final String[] PENS = {"70", "75", "80"};
    static final String[] SPREADS = {"s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s12", "s16"};

    final SplittableRandom rnd = new SplittableRandom(20261012L);
    final double[] common = new double[RESAMPLES];
    final List<Map<String, Object>> rows = new ArrayList<>();
    final Map<String, Object> names = new LinkedHashMap<>();
    final Map<String, Double> skill = new LinkedHashMap<>();
    /** The catalog's tags, A then 2 to 9 then T, for the rows the page's tag tables show. */
    final Map<String, double[]> tags = new LinkedHashMap<>();

    private SiteFixture() {
        for (int r = 0; r < RESAMPLES; r++) {
            common[r] = 0.018 * gauss();
        }
    }

    double gauss() {
        double u = rnd.nextDouble();
        double v = rnd.nextDouble();
        return Math.sqrt(-2 * Math.log(1 - u)) * Math.cos(2 * Math.PI * v);
    }

    static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    /** A catalog name without its parenthesis, as short-names.tsv would give it. */
    static String shortName(String name) {
        return name.replaceAll("\\s*\\(.*\\)", "").trim();
    }

    private void row(String id, String name, String kind, long level, boolean balanced, String side, String rule,
                     double sk) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("kind", kind);
        r.put("level", level);
        r.put("balanced", balanced);
        r.put("side", side);
        r.put("side_rule", rule);
        if (id.equals("hi_lo")) {
            r.put("role", "c1");
        }
        rows.add(r);
        names.put(id, name);
        skill.put(id, sk);
    }

    /** The fake headline of a row: its first-decision figure at 80% and 1-8, from which the rest follow. */
    @SuppressWarnings("unchecked")
    private void catalogRows(boolean full) throws IOException {
        Map<String, Object> cat = (Map<String, Object>) Json.parse(new String(
                Files.readAllBytes(Paths.get("counting-systems.json")), StandardCharsets.UTF_8));
        List<String> side = new ArrayList<>();
        for (Object o : (List<Object>) cat.get("systems")) {
            Map<String, Object> s = (Map<String, Object>) o;
            String name = shortName((String) s.get("name"));
            String id = slug(name);
            long level = ((Number) s.get("level")).longValue();
            boolean bal = Boolean.TRUE.equals(s.get("balanced"));
            double sk = id.equals("hi_lo") ? 0.29 : id.contains("five") ? 0.10 + 0.02 * rnd.nextDouble()
                    : 0.05 + 0.31 * rnd.nextDouble();
            row(id, name, "published", level, bal, "none", "none", sk);
            Map<String, Object> t = (Map<String, Object>) s.get("tags");
            if (t != null && (id.equals("hi_lo") || id.equals("zen_count"))) {
                double[] v = new double[10];
                String[] ranks = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "T"};
                for (int r = 0; r < 10; r++) {
                    v[r] = ((Number) t.get(ranks[r])).doubleValue();
                }
                tags.put(id, v);
            }
            String rule = String.valueOf(s.get("aceSideCount")).toLowerCase();
            if (rule.contains("side count") || rule.contains("counted separately") || rule.contains("excess aces")) {
                side.add(id);
                row(id + "_ace", name + " + ace", "published", level, bal, "ace",
                        rule.contains("counted separately") || name.startsWith("Victor") ? "the author's rule"
                                : "a side count its sources recommend or allow, with QFIT's multiplier", 0);
            }
        }
        // Five side-count rows close together at the top, the two highest closer than their noise.
        double[] top = {0.392, 0.391, 0.386, 0.383, 0.379};
        for (int k = 0; k < side.size(); k++) {
            skill.put(side.get(k) + "_ace", k < top.length ? top[k] : 0.2 + 0.1 * rnd.nextDouble());
        }
        row("null", "Null count", "reference", 0, true, "none", "none", -0.70);
        if (full) {
            for (int k = 1; rows.size() < 76; k++) {
                row("fixture_row_" + k, "Fixture row " + k, "published", 1 + k % 3, k % 2 == 0, "none", "none",
                        0.05 + 0.25 * rnd.nextDouble());
            }
        }
    }

    static final Map<String, Double> P = Map.of("70", 0.5, "75", 0.75, "80", 1.0);

    static double spreadFactor(String sp) {
        int n = Integer.parseInt(sp.substring(1));
        return n == 1 ? 0 : Math.log(n) / Math.log(8);
    }

    /** The fake figure of a row: flat play near the chart's, rising with spread, penetration and the row's headline. */
    double value(String scope, String pen, String sp, String id) {
        double h = skill.get(id);
        double f = spreadFactor(sp);
        double fd = -0.66 + 0.02 * Math.max(0, h) + (h + 0.66 - 0.02 * Math.max(0, h)) * f * P.get(pen);
        boolean counted = !id.equals("null");
        double gain = !counted ? 0.001 : id.equals("perfect") ? 0.15 : 0.06 + 0.04 * Math.min(1, Math.max(0, (h - 0.2) / 0.2));
        double wong = counted ? 0.05 + 0.1 * Math.max(0, h) : 0;
        switch (scope) {
            case "bet": return counted ? fd - 0.09 : fd;
            case "fd": return fd;
            case "full": return fd + gain;
            case "fdw": return fd + wong;
            case "fullw": return fd + gain + wong;
            default: throw new IllegalArgumentException(scope);
        }
    }

    static double se(String sp, String pen) {
        return Math.max(0.004, 0.018 * spreadFactor(sp) * P.get(pen));
    }

    static double widen(String scope) {
        return scope.startsWith("full") ? 1.07 : 1;
    }

    static double six(double x) {
        return Math.round(x * 1e6) / 1e6;
    }

    double[] boot(String pen, String sp, double v) {
        double[] b = new double[RESAMPLES];
        double scale = Math.max(0.2, spreadFactor(sp) * P.get(pen));
        for (int r = 0; r < RESAMPLES; r++) {
            b[r] = six(v + common[r] * scale + 0.0015 * gauss());
        }
        return b;
    }

    static List<Object> list(double... v) {
        List<Object> out = new ArrayList<>();
        for (double x : v) {
            out.add(x);
        }
        return out;
    }

    static Map<String, Object> source(String what, String file) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("what", what);
        s.put("commit", COMMIT);
        s.put("fixture", true);
        s.put("file", file);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("path", "fixture/" + what + ".fake");
        a.put("bytes", 1234L);
        a.put("sha256", SiteData.sha256(("fixture artifact " + what).getBytes(StandardCharsets.UTF_8)));
        s.put("artifacts", List.of(a));
        return s;
    }

    static Map<String, Object> fig(double value, String fmt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("fmt", fmt);
        return m;
    }

    Map<String, Object> cell(String scope, String pen, String sp, String id) {
        double v = six(value(scope, pen, sp, id));
        double s = se(sp, pen);
        double w = 1.96 * s * widen(scope);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", v);
        m.put("cell", list(v, six(s), six(v - w), six(v + w)));
        return m;
    }

    /** A figure with resamples, for the guards, equal to its grid cell. */
    Map<String, Object> guarded(String scope, String pen, String sp, String id) {
        Map<String, Object> c = cell(scope, pen, sp, id);
        List<Object> cl = (List<Object>) c.get("cell");
        Map<String, Object> m = fig((Double) c.get("v"), "s4");
        m.put("se", cl.get(1));
        m.put("ci", List.of(cl.get(2), cl.get(3)));
        if (widen(scope) != 1) {
            m.put("widen", widen(scope));
            m.put("widened", true);
        }
        List<Object> b = new ArrayList<>();
        for (double x : boot(pen, sp, (Double) c.get("v"))) {
            b.add(x);
        }
        m.put("boot", b);
        return m;
    }

    Map<String, Object> grid(List<String> ids, List<String> scopes) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("_widened", List.of("full", "fullw"));
        for (String id : ids) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (String scope : scopes) {
                Map<String, Object> s = new LinkedHashMap<>();
                for (String pen : PENS) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    for (String sp : SPREADS) {
                        p.put(sp, cell(scope, pen, sp, id).get("cell"));
                    }
                    s.put(pen, p);
                }
                r.put(scope, s);
            }
            g.put(id, r);
        }
        return g;
    }

    /** The game block: the engine's rules, which are not results, so the fixture uses the real ones. */
    static Map<String, Object> game(Map<String, Object> f) {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(80);
        f.put("game.decks", fig(hr.numDecks, "n"));
        f.put("game.cards", fig(hr.numDecks * 52, "n"));
        f.put("game.h17", fig(hr.hitsOnSoft17 ? 1 : -1, "w:hits:stands"));
        f.put("game.bj_num", fig(hr.blackjackPayout * 2, "n"));
        f.put("game.bj_den", fig(2, "n"));
        f.put("game.split_hands", fig(hr.numSplitsNotAces + 1, "n"));
        f.put("game.peek", fig(hr.dealerPeeksBlackjack ? 1 : -1, "w:checks:does not check"));
        f.put("game.surrender", fig(hr.canLateSurrender || hr.canEarlySurrender ? 1 : -1, "w:is:is not"));
        f.put("game.das", fig(hr.ranksThatCanBeDoubledDownAfterSplit.isEmpty() ? -1 : 1, "w:may:may not"));
        f.put("game.hit_split_aces", fig(hr.canHitAfterSplittingAces ? 1 : -1, "w:may:may not"));
        for (String pen : PENS) {
            f.put("game.pen." + pen, fig(Integer.parseInt(pen), "n"));
        }
        f.put("game.spread_max", fig(8, "n"));
        return f;
    }

    /** Writes the fixture's three inputs into dir: the catalog's rows, the searched and perfect rows, the search file's figures. */
    static void write(Path dir, boolean full) throws IOException {
        SiteFixture x = new SiteFixture();
        x.catalogRows(full);
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> r : x.rows) {
            ids.add((String) r.get("id"));
        }
        Map<String, Object> names = new LinkedHashMap<>(x.names);
        for (String sp : SPREADS) {
            names.put(sp, sp.equals("s1") ? "flat" : "1-" + sp.substring(1));
        }
        Map<String, Object> figs = new LinkedHashMap<>();
        for (Map<String, Object> r : x.rows) {
            String id = (String) r.get("id");
            if (!"published".equals(r.get("kind"))) {
                continue;
            }
            figs.put("fd.80.s8." + id, x.guarded("fd", "80", "s8", id));
            figs.put("fd.70.s8." + id, x.guarded("fd", "70", "s8", id));
            figs.put("full.80.s8." + id, x.guarded("full", "80", "s8", id));
            double gain = six(x.value("full", "80", "s8", id) - x.value("fd", "80", "s8", id));
            Map<String, Object> g = fig(gain, "s4");
            g.put("se", 0.0021);
            g.put("ci", list(six(gain - 1.96 * 0.0021 * 1.8), six(gain + 1.96 * 0.0021 * 1.8)));
            g.put("widen", 1.8);
            g.put("widened", true);
            figs.put("gain.80.s8." + id, g);
            figs.put("perbet.full.80.s8." + id, fig(six(x.value("full", "80", "s8", id) / 1.517), "s4"));
        }
        String[] rankNames = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "T"};
        for (int r = 1; r <= 10; r++) {
            names.put("r" + r, rankNames[r - 1]);
        }
        for (Map.Entry<String, double[]> t : x.tags.entrySet()) {
            for (int r = 1; r <= 10; r++) {
                figs.put("tags." + t.getKey() + ".r" + r, fig(t.getValue()[r - 1], "s0"));
            }
        }
        figs.put("example.total", fig(16, "n"));
        figs.put("example.up", fig(10, "n"));
        figs.put("example.stand", fig(-0.5123, "s4"));
        figs.put("example.hit", fig(-0.5234, "s4"));
        figs.put("method.deals", fig(550, "n"));
        figs.put("method.values_per_state", fig(1720, "n"));
        figs.put("repro.ser_tolerance", fig(1e-12, "e"));
        for (String id : ids) {
            List<Object> c = (List<Object>) x.cell("bet", "80", "s8", id).get("cell");
            Map<String, Object> m = fig((Double) c.get(0), "s4");
            m.put("se", c.get(1));
            m.put("ci", List.of(c.get(2), c.get(3)));
            figs.put("bet.80.s8." + id, m);
        }
        figs.put("bet.80.s1.null", fig(six(x.value("bet", "80", "s1", "null")), "s4"));
        figs.put("avgbet.80.s8", fig(1.517, "d3"));
        figs.put("data.shoes", fig(61000, "n"));
        figs.put("data.states", fig(61234, "n"));
        figs.put("data.rounds.80", fig(3512345, "n"));
        figs.put("method.resamples", fig(1000, "n"));
        figs.put("method.ci", fig(95, "n"));
        game(figs);
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("_source", source("results", "main"));
        results.put("figures", figs);
        // The toggle grid holds the two play scopes with wonging on and off, as PLAN_PAGE.md 2.5 sizes
        // it; betting-only figures appear only in the ladder, so they are plain figures.
        results.put("grid", x.grid(ids, List.of("fd", "full", "fdw", "fullw")));
        results.put("names", names);
        results.put("tables", Map.of("rows", x.rows));
        Files.createDirectories(dir);
        Files.write(dir.resolve("results-fixture.json"), Json.write(results, 3).getBytes(StandardCharsets.UTF_8));

        SiteFixture c = x;
        int before = c.rows.size();
        c.row("f1", "F1", "searched", 0, true, "none", "none", 0.41);
        c.row("f2", "F2", "searched", 0, true, "none", "none", 0.43);
        c.row("perfect", "Perfect knowledge", "perfect", 0, true, "none", "none", 0.80);
        List<Map<String, Object>> crow = new ArrayList<>(c.rows.subList(before, c.rows.size()));
        Map<String, Object> cfig = new LinkedHashMap<>();
        cfig.put("fd.80.s8.f1", c.guarded("fd", "80", "s8", "f1"));
        cfig.put("full.80.s8.f1", c.guarded("full", "80", "s8", "f1"));
        // F1's and F2's tags are made up here; the real ones are in custom-counts.json.
        double[][] fakeTags = {{-1.1, 0.6, 0.8, 1.0, 1.4, 1.0, 0.5, 0.1, -0.3, -1.0}, {-0.9, 0.5, 0.7, 1.1, 1.3, 0.9, 0.4, 0.0, -0.2, -1.0}};
        for (int k = 0; k < 2; k++) {
            for (int r = 1; r <= 10; r++) {
                cfig.put("tags.f" + (k + 1) + ".r" + r, fig(fakeTags[k][r - 1], "s1"));
            }
        }
        Map<String, Object> ceiling = new LinkedHashMap<>();
        ceiling.put("_source", source("ceiling", "main"));
        ceiling.put("figures", cfig);
        ceiling.put("grid", c.grid(List.of("f1", "f2", "perfect"), List.of("bet", "fd", "full")));
        ceiling.put("names", Map.of("f1", "F1", "f2", "F2", "perfect", "Perfect knowledge"));
        ceiling.put("tables", Map.of("rows", crow));
        Files.write(dir.resolve("ceiling-fixture.json"), Json.write(ceiling, 3).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> sfig = new LinkedHashMap<>();
        sfig.put("search.f1.80.s8", fig(0.401, "s3"));
        sfig.put("search.f2.80.s8", fig(0.427, "s3"));
        sfig.put("search.ref.80.s8", fig(0.405, "s3"));
        Map<String, Object> search = new LinkedHashMap<>();
        search.put("_source", source("search", "search"));
        search.put("figures", sfig);
        Files.write(dir.resolve("search-fixture.json"), Json.write(search, 3).getBytes(StandardCharsets.UTF_8));
    }

    /** java -cp … SiteFixture DIR [--full]: writes the fixture's inputs into DIR. */
    public static void main(String[] args) throws IOException {
        write(Paths.get(args[0]), args.length > 1 && args[1].equals("--full"));
    }
}
