import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The page generator against the fixture's made-up inputs (SiteFixture): the page builds and
 * passes its own checks; every number on it traces to a field of an input file; a number typed
 * into the template or the page, a printed figure that differs from results.json, a failed or
 * point-estimate-only claim guard, an unwidened full-play interval and a search-file figure out
 * of its label are each refused; the page takes its colours from the site's tokens in light and
 * dark, with every pair it uses readable; and it stays within the plan's budgets at the largest
 * grid the plan foresees. Each refusal is tested by planting the fault it guards against.
 *
 * <p>Plants, each made in a scratch copy of src/ on 12 October 2026 and run against this class
 * (never kept on a branch). First run, eleven of twelve were caught:
 * <ul>
 *   <li>the page's digit check switched off: aNumberTypedIntoThePageIsCaught failed;</li>
 *   <li>a figure span's text no longer compared with results.json: aPrintedFigureThatDiffersFromResultsJsonIsCaught;</li>
 *   <li>a "&gt; 0" guard met by the point estimate alone: aGuardMetOnlyByPointEstimatesIsNotMet;</li>
 *   <li>guards no longer widened for full play: aGuardMetOnlyByPointEstimatesIsNotMet;</li>
 *   <li>unwidened full-play intervals accepted: anUnwidenedFullPlayIntervalIsRefused;</li>
 *   <li>search-file figures allowed outside their label: aSearchFileFigureIsPrintedOnlyInsideItsLabel;</li>
 *   <li>the grid rounded to six decimals with no print check: theCompactGridPrintsAsTheFullValueDoes;</li>
 *   <li>no fixture banner: six tests, aFixtureBuildIsMarkedEverywhereAndKeptOutOfTheWebsite among them;</li>
 *   <li>the template's typed-figure rule off: aNumberTypedIntoTheTemplateStopsTheBuild;</li>
 *   <li>one number, one value not enforced: oneNumberHasOneValue;</li>
 *   <li>a colour literal allowed in the CSS: aColourWrittenIntoTheStylesOrAChartIsRefused;</li>
 *   <li>the pairs' max-|t| multiplier replaced by 1.96: <b>passed every test</b>. It now fails
 *   pairsAndEachGuardsTakeOneMultiplierOverTheWholeFamily, written for it, as does the same plant
 *   in the "each" guards (which also fails aGuardThatFailsStopsTheBuild).</li>
 * </ul>
 * The rule that a data-file="search" element print the words "search file", added with that
 * test, was planted off too and fails aSearchFileFigureIsPrintedOnlyInsideItsLabel.
 */
public class SiteBuildTest {

    /**
     * Stand-in tokens for the build tests, deliberately not the site's (the page takes the real
     * ones from the website's index.html, and the contrast test reads that file itself).
     */
    static final String INDEX = "<style>\n:root {\n --bg: #ffffff;\n --fg: #111111;\n --mute: #555555;\n"
            + " --rule: #dddddd;\n --link: #0b3d91;\n}\n@media (prefers-color-scheme: dark) {\n :root {\n"
            + "  --bg: #000000;\n  --fg: #eeeeee;\n  --mute: #aaaaaa;\n  --rule: #333333;\n  --link: #99bbee;\n }\n}\n</style>";

    @TempDir
    static Path dir;
    static Path small;
    static String template;
    static String css;
    static String js;
    static SiteBuild.Output page;

    static String read(String p) throws IOException {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    @BeforeAll
    static void fixture() throws IOException {
        small = dir.resolve("small");
        SiteFixture.write(small, false);
        template = read("site/template.html");
        css = read("site/page.css");
        js = read("site/page.js");
        page = build(small, template);
    }

    static SiteBuild.Output build(Path in, String tpl) throws IOException {
        return build(in, tpl, css);
    }

    static SiteBuild.Output build(Path in, String tpl, String style) throws IOException {
        return SiteBuild.build(SiteData.read(in), tpl, style, js, SiteBuild.tokens(INDEX), "test-1", "2026-10-12",
                "0123456789abcdef0123456789abcdef01234567");
    }

    static String html() {
        return page.text("index.html");
    }

    /** The build's checks on the fixture page with something planted in it; the page itself is left as built. */
    static List<String> checkPlanted(String planted) {
        byte[] was = page.files.get("index.html");
        page.files.put("index.html", planted.getBytes(StandardCharsets.UTF_8));
        try {
            return SiteBuild.check(page, css);
        } finally {
            page.files.put("index.html", was);
        }
    }

    @Test
    public void theFixturePageBuildsAndPassesEveryCheck() {
        assertEquals(List.of(), page.problems);
        for (String f : List.of("index.html", "data/results.json", "data/results.csv", "data/manifest.json",
                "data/SHA256SUMS", "data/MANIFEST.md", "assets/page.js")) {
            assertTrue(page.files.containsKey(f), f);
        }
        assertFalse(html().contains("{{"));
    }

    /** A fresh copy of the small fixture's inputs, for a test that changes one of them. */
    static Path copy(String name) throws IOException {
        Path to = dir.resolve(name);
        Files.createDirectories(to);
        try (java.util.stream.Stream<Path> s = Files.list(small)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Files.copy(p, to.resolve(p.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return to;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(Path p) throws IOException {
        return (Map<String, Object>) Json.parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    static void write(Path p, Map<String, Object> m) throws IOException {
        Files.write(p, Json.write(m, 3).getBytes(StandardCharsets.UTF_8));
    }

    /** Every figure the inputs give, by key, read from the raw files: their figures and their grids. */
    @SuppressWarnings("unchecked")
    static Map<String, double[]> inputFields(Path in) throws IOException {
        Map<String, double[]> out = new HashMap<>();
        try (java.util.stream.Stream<Path> s = Files.list(in)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Map<String, Object> m = json(p);
                Map<String, Object> figs = (Map<String, Object>) m.getOrDefault("figures", Map.of());
                for (Map.Entry<String, Object> e : figs.entrySet()) {
                    Map<String, Object> f = (Map<String, Object>) e.getValue();
                    List<Object> ci = (List<Object>) f.get("ci");
                    out.put(e.getKey(), new double[]{((Number) f.get("value")).doubleValue(),
                            f.get("se") == null ? Double.NaN : ((Number) f.get("se")).doubleValue(),
                            ci == null ? Double.NaN : ((Number) ci.get(0)).doubleValue(),
                            ci == null ? Double.NaN : ((Number) ci.get(1)).doubleValue()});
                }
                Map<String, Object> grid = (Map<String, Object>) m.getOrDefault("grid", Map.of());
                for (Map.Entry<String, Object> row : grid.entrySet()) {
                    if (row.getKey().startsWith("_")) {
                        continue;
                    }
                    for (Map.Entry<String, Object> sc : ((Map<String, Object>) row.getValue()).entrySet()) {
                        for (Map.Entry<String, Object> pen : ((Map<String, Object>) sc.getValue()).entrySet()) {
                            for (Map.Entry<String, Object> c : ((Map<String, Object>) pen.getValue()).entrySet()) {
                                List<Object> v = (List<Object>) c.getValue();
                                double[] d = new double[4];
                                for (int k = 0; k < 4; k++) {
                                    d[k] = v.get(k) == null ? Double.NaN : ((Number) v.get(k)).doubleValue();
                                }
                                out.put(sc.getKey() + "." + pen.getKey() + "." + c.getKey() + "." + row.getKey(), d);
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    static final Pattern SPAN = Pattern.compile("<(span|code|text|title|td)\\b([^>]*)\\bdata-(k|name|s|tick|t|q)=\"([^\"]*)\"([^>]*)>(.*?)</\\1>",
            Pattern.DOTALL);

    /**
     * Every number on the page traces to a field of an input file, by a route written apart from
     * the generator: each figure span's text is the input's own value printed in the span's format
     * (a range figure, the only one the generator computes, is the highest less the lowest of the
     * input values it names); each name is an input's name; and once every sourced element is taken
     * out, no digit is left in the page's text except in the allowlist's few phrases and in code.
     */
    @Test
    public void everyNumberOnThePageTracesToAnInputField() throws Exception {
        assertTrue(trace(small, page) > 200, "the page prints over 200 figures");
    }

    /**
     * The route of everyNumberOnThePageTracesToAnInputField on any page and the inputs it was built
     * from; the number of figures traced. A run of a committed paragraph's words (data-q) must be
     * that run of the words the input's ceiling_paragraphs table holds for its cell, and those words
     * the paragraph of the table's letter in CEILING_RESULT_PARAGRAPHS.md as committed before the
     * look, read here from git at its commit.
     */
    @SuppressWarnings("unchecked")
    static int trace(Path inputs, SiteBuild.Output page) throws Exception {
        Map<String, double[]> in = inputFields(inputs);
        Map<String, Object> paragraphs = null;
        try (java.util.stream.Stream<Path> s = Files.list(inputs)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                Object t = ((Map<String, Object>) json(f).getOrDefault("tables", Map.of())).get("ceiling_paragraphs");
                if (t != null) {
                    paragraphs = (Map<String, Object>) t;
                }
            }
        }
        String p = page.text("index.html");
        Matcher m = SPAN.matcher(p);
        int figures = 0;
        StringBuffer rest = new StringBuffer();
        while (m.find()) {
            String attrs = m.group(2) + m.group(5);
            String kind = m.group(3);
            String key = m.group(4);
            String text = SiteText.decode(m.group(6));
            if (kind.equals("k")) {
                figures++;
                Matcher f = Pattern.compile("data-f=\"([^\"]*)\"").matcher(attrs);
                double[] v = in.get(key);
                if (key.startsWith("range.")) {
                    String at = page.data.resolve(key).at.replace("the highest less the lowest of ", "");
                    double lo = Double.POSITIVE_INFINITY;
                    double hi = Double.NEGATIVE_INFINITY;
                    for (String k : at.split(", ")) {
                        lo = Math.min(lo, in.get(k)[0]);
                        hi = Math.max(hi, in.get(k)[0]);
                    }
                    v = new double[]{hi - lo, Double.NaN, Double.NaN, Double.NaN};
                }
                assertNotNull(v, key + " is printed but no input gives it");
                String fmt = f.find() ? f.group(1) : page.data.resolve(key).fmt;
                SiteData.Figure fig = new SiteData.Figure(key, v[0], v[1], v[2], v[3], fmt, "", key, "", "main", 1, false, null);
                assertEquals(SiteText.format(fig, fmt), text, key + " in " + fmt);
            } else if (kind.equals("name")) {
                assertEquals(page.data.names.get(key), text);
            } else if (kind.equals("q")) {
                String[] at = key.split("/");
                assertNotNull(paragraphs, "a committed paragraph is printed but no input holds one");
                Map<String, Object> cell = (Map<String, Object>) paragraphs.get(at[1]);
                String words = (String) cell.get("words");
                CeilingParagraphs.Texts committed = CeilingParagraphs.parse(CeilingParagraphsTest.committed(),
                        CeilingParagraphs.BLOB);
                char letter = ((String) cell.get("letter")).charAt(0);
                assertEquals((at[1].equals("flat") ? committed.flat : committed.paragraphs).get(letter), words,
                        "the " + at[1] + " cell's words are paragraph " + letter + " as committed");
                assertEquals(words.split("\\[[A-Z_]+\\]", -1)[Integer.parseInt(at[2])], text, key);
            }
            m.appendReplacement(rest, " ");
        }
        m.appendTail(rest);
        String t = rest.toString().replaceAll("(?s)<script\\b.*?</script>|<style\\b.*?</style>", " ")
                .replaceAll("(?s)<code\\b[^>]*>.*?</code>", " ").replaceAll("<[^>]*>", " ");
        t = SiteText.decode(t);
        for (Pattern a : SiteText.ALLOWED) {
            t = a.matcher(t).replaceAll(" ");
        }
        Matcher d = Pattern.compile(".{0,40}\\d.{0,40}").matcher(t);
        assertFalse(d.find(), () -> "a digit with no source: " + d.group());
        return figures;
    }

    @Test
    public void aNumberTypedIntoTheTemplateStopsTheBuild() {
        String typed = template.replace("I have not measured where", "Hi-Lo wins 0.36% here. I have not measured where");
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class, () -> build(small, typed));
        assertTrue(x.getMessage().contains("0.36"), x.getMessage());
    }

    @Test
    public void aNumberTypedIntoThePageIsCaught() {
        Map<String, String> s = page.strings;
        String p = html();
        assertEquals(List.of(), SiteText.checkPage(p, page.data, s));
        for (String typed : new String[]{"<p>Hi-Lo wins 0.36%.</p>", "<p>We valued 60,743 states.</p>", "<p>Spread 1-12.</p>"}) {
            List<String> problems = SiteText.checkPage(p.replace("<main>", "<main>" + typed), page.data, s);
            assertEquals(1, problems.size(), typed + ": " + problems);
            assertTrue(problems.get(0).startsWith("a number with no source"), problems.get(0));
        }
        // A figure typed inside code is caught too; a command is not a figure.
        assertEquals(1, SiteText.checkPage(p.replace("<main>", "<main><code>edge 0.4717</code>"), page.data, s).size());
        assertEquals(0, SiteText.checkPage(p.replace("<main>", "<main><code>java -Xmx4g</code>"), page.data, s).size());
    }

    @Test
    public void aPrintedFigureThatDiffersFromResultsJsonIsCaught() {
        String p = html();
        Matcher m = Pattern.compile("<span data-k=\"full\\.80\\.s8\\.hi_lo\" data-f=\"a2\">([^<]*)</span>").matcher(p);
        assertTrue(m.find());
        String wrong = p.substring(0, m.start(1)) + "9.99" + p.substring(m.end(1));
        List<String> problems = SiteText.checkPage(wrong, page.data, page.strings);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("full.80.s8.hi_lo is printed \"9.99\""), problems.get(0));
        String unknown = p.replaceFirst("data-k=\"full\\.80\\.s8\\.hi_lo\"", "data-k=\"full.80.s8.no_such_count\"");
        assertTrue(SiteText.checkPage(unknown, page.data, page.strings).get(0).contains("not in results.json"));
        String renamed = p.replaceFirst("(<span data-name=\"hi_lo\">)Hi-Lo<", "$1Hi-Lo 2<");
        assertTrue(SiteText.checkPage(renamed, page.data, page.strings).get(0).contains("data-name=\"hi_lo\""));
    }

    /** The page is printed from results.json read back, so a value changed there changes the page. */
    @Test
    public void thePageIsPrintedFromResultsJsonReadBack() {
        String results = page.text("data/results.json");
        assertTrue(results.contains("\"avgbet.80.s8\": {\"value\":1.517"), "avgbet as written");
        SiteData changed = SiteData.fromJson(results.replace("\"avgbet.80.s8\": {\"value\":1.517", "\"avgbet.80.s8\": {\"value\":1.617"));
        // The {{#if}} blocks are chosen once, on the inputs, which hold the resamples the guarded ones need.
        String chosen;
        try {
            chosen = SiteBuild.conditionals(template, SiteData.read(small));
        } catch (IOException x) {
            throw new IllegalStateException(x);
        }
        String again = SiteBuild.render(chosen, changed, page.strings, SiteBuild.tokens(INDEX), css);
        assertTrue(again.contains("<span data-k=\"avgbet.80.s8\" data-f=\"d1\">1.6</span>"));
        assertTrue(html().contains("<span data-k=\"avgbet.80.s8\" data-f=\"d1\">1.5</span>"));
    }

    /** Sets one figure of the results fixture to a new value and resamples shifted with it. */
    @SuppressWarnings("unchecked")
    static Path withFigure(String name, String key, double value) throws IOException {
        Path p = copy(name);
        Path f = p.resolve("results-fixture.json");
        Map<String, Object> m = json(f);
        Map<String, Object> fig = (Map<String, Object>) ((Map<String, Object>) m.get("figures")).get(key);
        double was = ((Number) fig.get("value")).doubleValue();
        fig.put("value", value);
        List<Object> boot = (List<Object>) fig.get("boot");
        for (int r = 0; r < boot.size(); r++) {
            boot.set(r, ((Number) boot.get(r)).doubleValue() + value - was);
        }
        fig.remove("ci");
        fig.remove("se");
        String[] k = key.split("\\.");
        Map<String, Object> grid = (Map<String, Object>) m.get("grid");
        List<Object> cell = (List<Object>) ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                grid.get(k[3])).get(k[0])).get(k[1])).get(k[2]);
        cell.set(0, value);
        cell.set(1, null);
        cell.set(2, null);
        cell.set(3, null);
        write(f, m);
        return p;
    }

    /** "Each of the five beats Hi-Lo" is refused once Hi-Lo sits among them. */
    @Test
    public void aGuardThatFailsStopsTheBuild() throws IOException {
        Path p = withFigure("guard-fails", "full.80.s8.hi_lo", 0.47);
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class, () -> build(p, template));
        assertTrue(x.getMessage().contains("claim guards that fail"), x.getMessage());
        assertTrue(x.getMessage().contains("- full.80.s8.hi_lo"), x.getMessage());
    }

    /**
     * A difference whose point estimate is above zero but whose interval is not does not meet a
     * "> 0" guard: the order of point estimates is not enough (PLAN_PAGE.md 2.4).
     */
    @Test
    public void aGuardMetOnlyByPointEstimatesIsNotMet() throws IOException {
        SiteData d = SiteData.read(small);
        String a = "full.80.s8.hi_lo";
        SiteData.Figure f = d.resolve(a);
        String best = SiteBuild.one("full.80.s8.@best", d);
        // A row 0.001 above Hi-Lo with Hi-Lo's own resamples plus noise of 0.002: above on the point
        // estimate, not above with its interval.
        double[] boot = new double[f.boot.length];
        java.util.SplittableRandom r = new java.util.SplittableRandom(7);
        for (int k = 0; k < boot.length; k++) {
            boot[k] = f.boot[k] + 0.001 + 0.002 * (r.nextDouble() * 2 - 1) * 1.7;
        }
        d.figures.put("full.80.s8.close", new SiteData.Figure("full.80.s8.close", f.value + 0.001, Double.NaN, Double.NaN,
                Double.NaN, "s4", "", "", "", "main", 1.07, true, boot));
        assertEquals(false, SiteGuards.evaluate("full.80.s8.close - " + a + " > 0", d).get("holds"));
        assertEquals(true, SiteGuards.evaluate("full.80.s8.close - " + a + " ~ 0", d).get("holds"));
        assertEquals(true, SiteGuards.evaluate(best + " - " + a + " > 0", d).get("holds"));
        // Widening is applied: the largest ratio among the figures, here full play's 1.07.
        assertEquals(1.07, (Double) SiteGuards.evaluate(best + " - " + a + " > 0", d).get("widen"), 0);
        assertThrows(IllegalArgumentException.class, () -> SiteGuards.evaluate("game.decks - " + a + " > 0", d),
                "a figure with no resamples cannot support a sentence");
    }

    @Test
    public void cannotSayWhichIsBestNeedsAPairWithTheLeaderUnseparated() throws IOException {
        SiteData d = SiteData.read(small);
        String top = SiteBuild.expandGuard("leader-tied: full.80.s8.@top5", d);
        assertEquals(true, SiteGuards.evaluate(top, d).get("holds"));
        // Move the leader well clear of the rest: now every pair with it is separated.
        Path p = withFigure("leader-clear", SiteBuild.one("full.80.s8.@best", d), 0.60);
        SiteData far = SiteData.read(p);
        assertEquals(false, SiteGuards.evaluate(SiteBuild.expandGuard("leader-tied: full.80.s8.@top5", far), far).get("holds"));
        assertThrows(IllegalArgumentException.class, () -> build(p, template));
    }

    static byte[] input(String figures) {
        return ("{\"_source\":{\"what\":\"t\",\"commit\":\"abc\",\"fixture\":true},\"figures\":{" + figures + "}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void anUnwidenedFullPlayIntervalIsRefused() {
        SiteData d = new SiteData();
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class, () -> d.add("a.json",
                input("\"full.80.s8.zen\":{\"value\":0.4,\"ci\":[0.37,0.43]}")));
        assertTrue(x.getMessage().contains("not been widened"), x.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new SiteData().add("b.json",
                input("\"gain.80.s8.zen\":{\"value\":0.07,\"se\":0.002}")));
        new SiteData().add("c.json", input("\"full.80.s8.zen\":{\"value\":0.4,\"ci\":[0.37,0.43],\"widen\":1.07,\"widened\":true}"));
        new SiteData().add("d.json", input("\"fd.80.s8.zen\":{\"value\":0.4,\"ci\":[0.37,0.43]}"));
        new SiteData().add("e.json", input("\"full.80.s8.zen\":{\"value\":0.4}"));
        String grid = "{\"_source\":{\"what\":\"g\",\"commit\":\"abc\",\"fixture\":true},\"grid\":{\"zen\":{\"full\":{\"80\":{\"s8\":[0.4,0.01,0.38,0.42]}}}}}";
        assertThrows(IllegalArgumentException.class, () -> new SiteData().add("f.json", grid.getBytes(StandardCharsets.UTF_8)));
        new SiteData().add("g.json", grid.replace("\"grid\":{", "\"grid\":{\"_widened\":[\"full\"],").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void oneNumberHasOneValue() {
        SiteData d = new SiteData();
        d.add("a.json", input("\"fd.80.s8.zen\":{\"value\":0.38749}"));
        d.add("b.json", input("\"fd.80.s8.zen\":{\"value\":0.38751}"));
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class,
                () -> d.add("c.json", input("\"fd.80.s8.zen\":{\"value\":0.3876}")));
        assertTrue(x.getMessage().contains("one number must have one value"), x.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new SiteData().add("x.json",
                "{\"_source\":{\"what\":\"t\",\"commit\":\"abc\"},\"figures\":{}}".getBytes(StandardCharsets.UTF_8)),
                "an input that does not say whether it is a fixture");
        assertThrows(IllegalArgumentException.class, () -> new SiteData().add("y.json",
                "{\"_source\":{\"what\":\"t\",\"commit\":\"abc\",\"fixture\":false},\"rows\":[]}".getBytes(StandardCharsets.UTF_8)),
                "a block outside the contract");
    }

    @Test
    public void aSearchFileFigureIsPrintedOnlyInsideItsLabel() throws IOException {
        assertTrue(html().contains("<span data-file=\"search\">On that search file"));
        String out = template.replace("<p>The tags were searched on", "<p>{{name:f1}} scored {{search.f1.80.s8|s3}}. The tags were searched on");
        List<String> problems = build(small, out).problems;
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("search.f1.80.s8 comes from the search file"), problems.get(0));
        String unlabelled = html().replace("On that search file, ", "On it, ").replace(" (search file).</span>", ".</span>");
        assertNotEquals(html(), unlabelled);
        assertTrue(checkPlanted(unlabelled).contains("a data-file=\"search\" element that does not say \"search file\" in its text"));
    }

    @Test
    public void aSectionWithoutItsDataSaysNotYetComputed() throws IOException {
        String p = html();
        for (String what : new String[]{"swings, money per hour and risk of ruin", "the independent simulation",
                "the comparison with published figures", "the evidence table"}) {
            assertTrue(p.contains("Not yet computed: " + what), what);
        }
        Path withRisk = copy("with-risk");
        // The answer names the money of the row ranked first, by its own key (SiteRisk's keys).
        String best = SiteSvg.rowOf(SiteBuild.one("full.80.s8.@best", page.data));
        Files.write(withRisk.resolve("risk-fixture.json"), ("{\"_source\":{\"what\":\"risk\",\"commit\":\"fixture\",\"fixture\":true},"
                + "\"figures\":{\"risk.unit_usd\":{\"value\":25,\"fmt\":\"usd\"},\"risk.rounds\":{\"value\":100,\"fmt\":\"n\"},"
                + "\"risk.ev_per100_usd.full.80.s8." + best + "\":{\"value\":12,\"fmt\":\"usd\"},"
                + "\"risk.sd_per100_usd.full.80.s8." + best + "\":{\"value\":280,\"fmt\":\"usd\"}}}")
                .getBytes(StandardCharsets.UTF_8));
        SiteBuild.Output o = build(withRisk, template);
        assertEquals(List.of(), o.problems);
        String q = o.text("index.html");
        assertFalse(q.contains("Not yet computed: swings"));
        assertTrue(q.contains("<span data-k=\"risk.unit_usd\" data-f=\"usd\">$25</span> a unit"));
    }

    // ---- light and dark ----

    @Test
    public void theTokensComeFromTheSitesIndexAndStopWithoutAllTen() {
        SiteBuild.Tokens t = SiteBuild.tokens(INDEX);
        assertEquals("#ffffff", t.light.get("bg"));
        assertEquals("#000000", t.dark.get("bg"));
        assertEquals(SiteBuild.SITE_TOKENS, new ArrayList<>(t.light.keySet()));
        assertEquals(SiteBuild.SITE_TOKENS, new ArrayList<>(t.dark.keySet()));
        assertThrows(IllegalArgumentException.class, () -> SiteBuild.tokens(INDEX.replace("  --link: #99bbee;\n", "")));
        assertThrows(IllegalArgumentException.class, () -> SiteBuild.tokens(INDEX.replace(" --mute: #555555;\n", "")));
        assertThrows(IllegalArgumentException.class, () -> SiteBuild.tokens(INDEX.replace("prefers-color-scheme: dark", "print")));
    }

    /** Both modes define every token the page uses, and the page's rules use only tokens. */
    @Test
    public void thePageDefinesEveryColourInBothModes() {
        String p = html();
        Matcher light = Pattern.compile(":root\\{([^}]*)\\}").matcher(p);
        assertTrue(light.find());
        Matcher dark = Pattern.compile("@media \\(prefers-color-scheme: dark\\)\\{:root\\{([^}]*)\\}").matcher(p);
        assertTrue(dark.find());
        java.util.Set<String> used = new java.util.TreeSet<>();
        Matcher v = Pattern.compile("var\\(--([a-z0-9]+)\\)").matcher(p);
        while (v.find()) {
            used.add(v.group(1));
        }
        assertTrue(used.containsAll(List.of("bg", "fg", "mute", "rule", "link", "c1", "c2")), used.toString());
        used.remove("cards");   // the shoe's card count, set on its element, not a colour
        for (String t : used) {
            assertTrue(light.group(1).contains("--" + t + ":#"), "light --" + t);
            assertTrue(dark.group(1).contains("--" + t + ":#"), "dark --" + t);
        }
    }

    @Test
    public void aColourWrittenIntoTheStylesOrAChartIsRefused() throws IOException {
        assertEquals(List.of(), page.problems);
        List<String> p = build(small, template, css + "\n.x { border-color: #ff0000; }").problems;
        assertTrue(p.contains("page.css writes a colour; colours come only from the tokens, so dark mode switches them"), p.toString());
        p = build(small, template, css + "\n.x { color: var(--c2); }").problems;
        assertTrue(p.contains("page.css sets text in orange (--c2), under 4.5:1 on the light background"), p.toString());
        String svg = html().replaceFirst("<svg class=\"panel\"", "<svg fill=\"#000000\" class=\"panel\"");
        assertNotEquals(html(), svg);
        List<String> q = checkPlanted(svg);
        assertTrue(q.contains("an SVG with a colour written into it rather than taken from the page's tokens"), q.toString());
    }

    static double luminance(String hex) {
        double[] c = new double[3];
        for (int k = 0; k < 3; k++) {
            double s = Integer.parseInt(hex.substring(1 + 2 * k, 3 + 2 * k), 16) / 255.0;
            c[k] = s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /** The WCAG 2 contrast ratio of two colours. */
    static double contrast(String a, String b) {
        double x = luminance(a);
        double y = luminance(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    static Path siteIndex() {
        for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path i = p.resolveSibling("jbondurant.github.io").resolve("index.html");
            if (Files.isRegularFile(i)) {
                return i;
            }
        }
        return null;
    }

    /**
     * Every colour pair the page uses is readable in both modes, by the WCAG formula, with the
     * site's own tokens read from its index.html: text, muted text and links at 4.5:1 or better,
     * and the chart marks (blue and orange) at 3:1, the level for graphics. Orange is under 4.5:1
     * on the light background, which is why the build refuses it as a text colour.
     */
    @Test
    public void everyColourPairReadsInBothModes() throws IOException {
        Path index = siteIndex();
        org.junit.jupiter.api.Assumptions.assumeTrue(index != null, "the website's index.html is not beside this repo");
        SiteBuild.Tokens t = SiteBuild.tokens(new String(Files.readAllBytes(index), StandardCharsets.UTF_8));
        for (int mode = 0; mode < 2; mode++) {
            Map<String, String> m = new HashMap<>(mode == 0 ? t.light : t.dark);
            for (Map.Entry<String, String[]> e : SiteBuild.PAGE_TOKENS.entrySet()) {
                m.put(e.getKey(), e.getValue()[mode]);
            }
            String where = mode == 0 ? "light" : "dark";
            for (String text : List.of("fg", "mute", "link")) {
                assertTrue(contrast(m.get(text), m.get("bg")) >= 4.5, where + " --" + text + " on --bg: "
                        + contrast(m.get(text), m.get("bg")));
            }
            for (String mark : List.of("c1", "c2", "mute", "fg")) {
                assertTrue(contrast(m.get(mark), m.get("bg")) >= 3, where + " --" + mark + " marks on --bg");
            }
        }
        assertTrue(contrast(SiteBuild.PAGE_TOKENS.get("c2")[0], t.light.get("bg")) < 4.5);
    }

    @Test
    public void everyChartParsesAndCarriesItsTitleAndDescription() throws Exception {
        Matcher m = Pattern.compile("<svg\\b.*?</svg>", Pattern.DOTALL).matcher(html());
        int n = 0;
        while (m.find()) {
            n++;
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            org.w3c.dom.Document doc = f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                    m.group().getBytes(StandardCharsets.UTF_8)));
            org.w3c.dom.Element svg = doc.getDocumentElement();
            assertEquals("img", svg.getAttribute("role"));
            assertEquals(1, svg.getElementsByTagName("desc").getLength());
            assertTrue(svg.getElementsByTagName("title").getLength() >= 1);
            assertTrue(svg.getElementsByTagName("polyline").getLength() >= 1);
        }
        assertEquals(2, n);
        List<String> broken = checkPlanted(html().replaceFirst("</title><desc", "</titl><desc"));
        assertTrue(broken.stream().anyMatch(x -> x.startsWith("an SVG that does not parse")), broken.toString());
    }

    @Test
    public void everyFigureHasACaptionAndEveryLinkGoesSomewhere() {
        String p = html();
        assertTrue(checkPlanted(p.replaceFirst("<figcaption id=\"shoe-cap\">", "<p id=\"shoe-cap\">")
                .replaceFirst("(?s)(<p id=\"shoe-cap\">.*?)</figcaption>", "$1</p>")).contains("a figure with no caption"));
        assertTrue(checkPlanted(p.replace("<a href=\"#prior\">Prior work</a>", "<a href=\"#nowhere\">Prior work</a>"))
                .contains("a link to #nowhere, which is not on the page"));
        assertTrue(checkPlanted(p.replace("</footer>", "<a href=\"https://example.com/\">x</a></footer>"))
                .contains("an external link not on the allowlist: https://example.com/"));
        assertTrue(checkPlanted(p.replace("</footer>", "<a href=\"data/other.json\">x</a></footer>"))
                .contains("a link to data/other.json, which the build does not make"));
    }

    @Test
    public void twoBuildsGiveTheSameBytes() throws IOException {
        SiteBuild.Output again = build(small, template);
        assertEquals(page.files.keySet(), again.files.keySet());
        for (String f : page.files.keySet()) {
            assertArrayEquals(page.files.get(f), again.files.get(f), f);
        }
    }

    /**
     * At the largest page the plan foresees (about 80 rows × 3 penetrations × 10 spreads × 2 play
     * scopes, wonging on and off: PLAN_PAGE.md 2.5) the page, its script and its data files stay
     * within their budgets; and a page over its budget is refused.
     */
    @Test
    public void thePageStaysWithinItsBudgetsAtThePlansLargestSize() throws IOException {
        Path full = dir.resolve("full");
        SiteFixture.write(full, true);
        SiteBuild.Output o = build(full, template);
        assertEquals(List.of(), o.problems);
        assertTrue(SiteBuild.rows(o.data).size() >= 79, "rows: " + SiteBuild.rows(o.data).size());
        int cells = 0;
        for (Map<String, Map<String, Map<String, double[]>>> r : o.data.grid.values()) {
            for (Map<String, Map<String, double[]>> sc : r.values()) {
                for (Map<String, double[]> pen : sc.values()) {
                    cells += pen.size();
                }
            }
        }
        assertTrue(cells >= 76 * 3 * 10 * 4, "grid cells: " + cells);
        assertTrue(o.files.get("index.html").length <= 200 * 1024, "page " + o.files.get("index.html").length);
        assertTrue(o.files.get("assets/page.js").length <= 12 * 1024);
        assertTrue(o.files.get("data/results.json").length <= 500 * 1024, "results.json " + o.files.get("data/results.json").length);
        assertTrue(o.files.get("data/results.csv").length <= 300 * 1024);
        String filler = "<p>" + "padding words ".repeat(16 * 1024) + "</p>";
        List<String> over = build(small, template.replace("<main>", "<main>" + filler)).problems;
        assertTrue(over.stream().anyMatch(x -> x.startsWith("index.html is ") && x.endsWith("over its budget of 204800")), over.toString());
    }

    @Test
    public void theManifestHashesWhatItListsAndThePageShowsTheManifestsHash() {
        Map<String, byte[]> files = new HashMap<>();
        files.put("results.json", page.files.get("data/results.json"));
        files.put("results.csv", page.files.get("data/results.csv"));
        files.put("results.fake", "fixture artifact results".getBytes(StandardCharsets.UTF_8));
        files.put("ceiling.fake", "fixture artifact ceiling".getBytes(StandardCharsets.UTF_8));
        files.put("search.fake", "fixture artifact search".getBytes(StandardCharsets.UTF_8));
        String sums = page.text("data/SHA256SUMS");
        assertEquals(List.of(), SiteManifest.check(sums, files));
        files.put("results.csv", "changed".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("results.csv: FAILED"), SiteManifest.check(sums, files));
        String hash = SiteData.sha256(page.files.get("data/manifest.json"));
        assertTrue(html().contains("<code class=\"hash\"><span data-s=\"manifest\">" + hash + "</span></code>"));
        assertTrue(page.text("data/manifest.json").contains("\"sha256\":\"" + SiteData.sha256(page.files.get("data/results.json")) + "\""));
        assertTrue(page.text("data/MANIFEST.md").contains(SiteManifest.ABOUT));
    }

    @Test
    public void aFixtureBuildIsMarkedEverywhereAndKeptOutOfTheWebsite() {
        String p = html();
        assertTrue(p.contains(SiteBuild.FIXTURE_BANNER));
        assertTrue(p.contains("<title>" + SiteBuild.FIXTURE_TITLE));
        assertTrue(p.contains("\"name\": \"FIXTURE, NOT RESULTS: "));
        assertTrue(page.text("data/results.json").contains("\"fixture\": true"));
        assertTrue(page.text("data/results.json").contains("\"FIXTURE\": \"Every number in this file is made up"));
        assertTrue(page.text("data/manifest.json").contains("\"fixture\": true"));
        assertTrue(page.text("data/MANIFEST.md").startsWith("# Manifest\n\n**FIXTURE"));
        assertThrows(IllegalArgumentException.class, () -> SiteBuild.refuseFixtureIntoWebsite(page.data,
                Paths.get("/Users/x/Documents/jbondurant.github.io/card-counting")));
        SiteBuild.refuseFixtureIntoWebsite(page.data, Paths.get("/tmp/site-out"));
        assertTrue(checkPlanted(p.replace(SiteBuild.FIXTURE_BANNER, "")).contains("a fixture build without its banner and title mark"));
    }

    @Test
    public void theJsonLdSaysWhatTheAnswerSays() {
        Matcher m = Pattern.compile("(?s)<script type=\"application/ld\\+json\">\\n(.*?)</script>").matcher(html());
        assertTrue(m.find());
        @SuppressWarnings("unchecked")
        Map<String, Object> ld = (Map<String, Object>) Json.parse(m.group(1));
        assertEquals("Dataset", ld.get("@type"));
        assertEquals(SiteBuild.textOf(html(), "answer") + " " + SiteBuild.textOf(html(), "conditions"), ld.get("description"));
        assertTrue(((String) ld.get("description")).contains("% of the minimum bet per round"));
        @SuppressWarnings("unchecked")
        List<Object> dist = (List<Object>) ld.get("distribution");
        assertEquals(2, dist.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) dist.get(0);
        assertEquals(SiteBuild.BASE + "data/results.json", first.get("contentUrl"));
        assertEquals(SiteData.sha256(page.files.get("data/results.json")), first.get("sha256"));
        assertFalse(m.group(1).contains("http:"), "every URL is https");
    }

    // ---- the pieces ----

    @Test
    public void jsonReadsBackWhatItWrites() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("a", 0.1 + 0.2);
        m.put("b", List.of(1e-12, -0.0760, 3L, true));
        m.put("c \"quoted\" </script>", null);
        m.put("d", Map.of("x", List.of(Map.of("y", 2.5))));
        for (int flat : new int[]{0, 1, 2, 3, 99}) {
            Object back = Json.parse(Json.write(m, flat));
            assertEquals(m, back, "flat from " + flat);
        }
        assertEquals(Double.doubleToLongBits(0.1 + 0.2), Double.doubleToLongBits((Double) ((Map<?, ?>) Json.parse(Json.write(m))).get("a")));
        assertFalse(Json.write(m).contains("</script>"));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Map.of("x", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2] x"));
    }

    /** A grid value is stored at six decimals unless that would change how it prints at four or fewer. */
    @Test
    public void theCompactGridPrintsAsTheFullValueDoes() {
        assertEquals(0.123457, SiteData.compact(0.1234567891));
        double edge = 0.12344951;
        assertEquals(edge, SiteData.compact(edge), "0.123450 would print +0.1235 where the value prints +0.1234");
        java.util.SplittableRandom r = new java.util.SplittableRandom(3);
        for (int k = 0; k < 100000; k++) {
            double x = (r.nextDouble() - 0.5) * 2;
            double c = (Double) SiteData.compact(x);
            for (int p = 0; p <= SiteText.MAX_PLACES; p++) {
                assertEquals(String.format(java.util.Locale.ROOT, "%." + p + "f", x),
                        String.format(java.util.Locale.ROOT, "%." + p + "f", c), x + " at " + p);
            }
        }
        assertNull(SiteData.compact(Double.NaN));
    }

    static SiteData.Figure f(double v, double se, double lo, double hi) {
        return new SiteData.Figure("k", v, se, lo, hi, "s4", "", "k", "", "main", 1, false, null);
    }

    @Test
    public void figuresPrintAsTheReportsDo() {
        assertEquals("+0.4717", SiteText.format(f(0.47174, 0, 0, 0), "s4"));
        assertEquals("−0.0760", SiteText.format(f(-0.076, 0, 0, 0), "s4"));
        assertEquals("0.0000", SiteText.format(f(-0.00001, 0, 0, 0), "s4"), "no sign on a figure that rounds to zero");
        assertEquals("0.47", SiteText.format(f(-0.4717, 0, 0, 0), "a2"));
        assertEquals("[+0.089, +0.099]", SiteText.format(f(0.094, 0.002, 0.0891, 0.0994), "ci3"));
        assertEquals("60,743", SiteText.format(f(60743, 0, 0, 0), "n"));
        assertEquals("$1,250", SiteText.format(f(1250, 0, 0, 0), "usd"));
        assertEquals("−$12", SiteText.format(f(-12, 0, 0, 0), "usd"));
        assertEquals("1e-12", SiteText.format(f(1e-12, 0, 0, 0), "e"));
        assertEquals("loses", SiteText.format(f(-0.07, 0, 0, 0), "w:wins:loses"));
        assertEquals("33", SiteText.format(f(0.33, 0, 0, 0), "pct0"));
        assertEquals("0.0019", SiteText.format(f(0.4, 0.0019, 0, 0), "se4"));
        assertThrows(IllegalArgumentException.class, () -> SiteText.format(f(60743.5, 0, 0, 0), "n"));
        assertThrows(IllegalArgumentException.class, () -> SiteText.format(f(0, 0, 0, 0), "w:wins:loses"));
        assertThrows(IllegalArgumentException.class, () -> SiteText.format(f(0.4, Double.NaN, Double.NaN, Double.NaN), "ci3"));
        assertThrows(IllegalArgumentException.class, () -> SiteText.checkFormat("s5", "k"), "the page prints at most four decimals");
    }

    /** Aliases name rows by rank among the published counts; the same row can be named at another key. */
    @Test
    public void rowsNamedByRankAreThePublishedRowsInOrder() throws IOException {
        SiteData d = SiteData.read(small);
        List<String> top = SiteBuild.expand("full.80.s8.@top5", d);
        assertEquals(5, top.size());
        for (int k = 1; k < top.size(); k++) {
            assertTrue(d.resolve(top.get(k - 1)).value >= d.resolve(top.get(k)).value);
        }
        assertEquals(top.get(0), SiteBuild.one("full.80.s8.@best", d));
        for (String k : SiteBuild.expand("full.80.s8.@all", d)) {
            assertEquals("published", SiteBuild.row(d, SiteSvg.rowOf(k)).get("kind"));
            assertTrue(d.resolve(k).value <= d.resolve(top.get(0)).value);
        }
        assertTrue(SiteBuild.expand("fd.80.s8.@every", d).contains("fd.80.s8.perfect"));
        assertFalse(SiteBuild.expand("fd.80.s8.@all", d).contains("fd.80.s8.perfect"));
        assertEquals("full.70.s8." + SiteSvg.rowOf(top.get(0)), SiteBuild.one("full.70.s8.=full.80.s8.@best", d));
        String each = SiteBuild.expandGuard("each > 0: full.80.s8.@top5 - full.80.s8.hi_lo", d);
        assertEquals(5, each.split(";").length);
        assertTrue(each.startsWith("each > 0: " + top.get(0) + " - full.80.s8.hi_lo; "));
    }

    /** The family's max-|t| multiplier, computed here apart from SiteGuards: widened differences, their SEs, the 0.95 quantile of the largest |t|. */
    static double familyMultiplier(SiteData d, List<String[]> diffs) {
        int n = diffs.size();
        int r = d.resolve(diffs.get(0)[0]).boot.length;
        double[] est = new double[n];
        double[] se = new double[n];
        double[][] b = new double[n][r];
        for (int j = 0; j < n; j++) {
            SiteData.Figure x = d.resolve(diffs.get(j)[0]);
            SiteData.Figure y = d.resolve(diffs.get(j)[1]);
            est[j] = x.value - y.value;
            double ratio = Math.max(x.widen, y.widen);
            double mean = 0;
            for (int k = 0; k < r; k++) {
                b[j][k] = x.boot[k] - y.boot[k];
                mean += b[j][k] / r;
            }
            double ss = 0;
            for (int k = 0; k < r; k++) {
                b[j][k] = mean + ratio * (b[j][k] - mean);
                ss += (b[j][k] - mean) * (b[j][k] - mean);
            }
            se[j] = Math.sqrt(ss / (r - 1));
        }
        double[] t = new double[r];
        for (int k = 0; k < r; k++) {
            for (int j = 0; j < n; j++) {
                t[k] = Math.max(t[k], Math.abs(b[j][k] - est[j]) / se[j]);
            }
        }
        java.util.Arrays.sort(t);
        double h = (r - 1) * 0.95;
        int lo = (int) Math.floor(h);
        return t[lo] + (h - lo) * (t[Math.min(lo + 1, r - 1)] - t[lo]);
    }

    /**
     * The pair lists and the "each" guards take one max-|t| multiplier over the whole family, not
     * one interval's 1.96: with ten pairs, each pair's interval must be wide enough that all ten
     * hold together 95% of the time (PLAN_PAGE.md 5a).
     */
    @Test
    public void pairsAndEachGuardsTakeOneMultiplierOverTheWholeFamily() throws IOException {
        SiteData d = SiteData.read(small);
        List<String> top = SiteBuild.expand("full.80.s8.@top5", d);
        SiteGuards.Pairs p = SiteGuards.pairs(top, d);
        assertEquals(10, p.pairs.size());
        List<String[]> diffs = new ArrayList<>();
        for (SiteGuards.Pair x : p.pairs) {
            diffs.add(new String[]{x.a, x.b});
        }
        double c = familyMultiplier(d, diffs);
        assertEquals(c, p.multiplier, 1e-9);
        assertTrue(c > 2.2, "ten pairs need a wider multiplier than one interval's: " + c);
        for (SiteGuards.Pair x : p.pairs) {
            assertEquals(x.diff, (x.lo + x.hi) / 2, 1e-12);
        }
        List<String[]> vsHiLo = new ArrayList<>();
        for (String k : top) {
            vsHiLo.add(new String[]{k, "full.80.s8.hi_lo"});
        }
        Map<String, Object> each = SiteGuards.evaluate(SiteBuild.expandGuard("each > 0: full.80.s8.@top5 - full.80.s8.hi_lo", d), d);
        assertEquals(familyMultiplier(d, vsHiLo), (Double) each.get("multiplier"), 1e-9);
    }
}
