import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The page built end to end from inputs every one of which the producing code wrote: SiteExport's
 * from the world's c06c061 rows, step 7's save and step 8's run (SiteWorld); the ceiling adapter's
 * from the data file CeilingResults wrote from the same files; the risk adapter's from the file
 * RiskReport wrote from the same rows; and the spreads, key edges, simulator and literature
 * adapters' from the files WideReport, KeyEdgesReport, the simulator's writer and LiteratureCheck
 * wrote on their own tests' fixtures. No input here is written by hand.
 *
 * The build passes every check of its own, the figures two inputs share are held to one value, the
 * template's guards hold on SiteExport's resamples, every section with its data is printed, and
 * every number on the page traces to a field of an input file by SiteBuildTest's route, written
 * apart from the generator, with the ceiling count's paragraph held word for word to the file
 * committed before the look.
 */
public class SitePageTest {

    @TempDir
    static Path dir;
    static SiteWorld world;
    static Path inputs;
    static SiteBuild.Output page;

    static String read(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    /** Runs a command line's main as build-site's steps run it, its line of output kept out of the test's. */
    interface Main {
        void run(String[] args) throws Exception;
    }

    static void run(Main main, String... args) throws Exception {
        PrintStream was = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"));
        try {
            main.run(args);
        } finally {
            System.setOut(was);
        }
    }

    /**
     * The inputs as the pipeline writes them, each by its command line (SiteExport, then SiteAdapt
     * once per computation) from the files the producing code wrote, then the page from them.
     */
    @BeforeAll
    static void built() throws Exception {
        world = SiteWorld.make(Files.createDirectories(dir.resolve("world")));
        String template = read("site/template.html");
        inputs = Files.createDirectories(dir.resolve("inputs"));
        run(SiteExport::main, "--stored", world.stored.toString(), "--widening", world.pilot.toString(), "--template",
                "site/template.html", "--states", world.states.toString(), "--saved", world.saved.toString(), "--counts",
                world.registration.toString(), "--fullplay-run", world.runFile.toString(), "--fullplay-counts",
                world.fullRegistration.toString(), "--out", inputs.resolve("results-world.json").toString());
        run(SiteAdapt::main, "ceiling", world.ceilingData().toString(), SiteCeilingTest.paragraphs(world.dir).toString(),
                world.registration.toString(), "--out", inputs.resolve("ceiling-world.json").toString());
        run(SiteAdapt::main, "risk", world.risk().toString(), "--out", inputs.resolve("risk-world.json").toString());
        // The other computations' files, each written on its own test's fixture: their keys are their own.
        run(SiteAdapt::main, "wide", SiteWideTest.spreads(Files.createDirectories(dir.resolve("wide"))).toString(),
                "--out", inputs.resolve("spreads-fixture.json").toString());
        run(SiteAdapt::main, "edges", SiteEdgesTest.keyEdges(Files.createDirectories(dir.resolve("edges"))).toString(),
                "--out", inputs.resolve("key-edges-fixture.json").toString());
        Path sim = Files.createDirectories(dir.resolve("sim"));
        run(SiteAdapt::main, "sim", "--plan", SiteSimTest.plan(sim, true).toString(), "--run",
                SiteSimTest.part1(sim.resolve("part1")).toString(), "--out", inputs.resolve("sim-fixture.json").toString());
        Path lit = Files.createDirectories(dir.resolve("literature"));
        run(SiteAdapt::main, "literature", SiteLiteratureTest.file(lit, SiteLiteratureTest.check(lit, false),
                "literature.json").toString(), "--out", inputs.resolve("literature-fixture.json").toString());
        page = SiteBuild.build(SiteData.read(inputs), template, read("site/page.css"), read("site/page.js"),
                SiteBuild.tokens(SiteBuildTest.INDEX), "world-1", "2026-10-13", "0123456789abcdef0123456789abcdef01234567");
    }

    @Test
    public void thePageBuildsAndPassesEveryCheck() {
        assertEquals(List.of(), page.problems);
    }

    /** SiteBuildTest's route on this page: every number from an input field, every paragraph word as committed. */
    @Test
    public void everyNumberOnThePageTracesToAnInputField() throws Exception {
        assertTrue(SiteBuildTest.trace(inputs, page) > 200, "the page prints over 200 figures");
        assertTrue(page.text("index.html").contains("data-q=\"ceiling_paragraphs/same/0\""), "the paragraph is printed");
    }

    /**
     * Every section an input gives data for is printed, not marked as not yet computed; the parts
     * no input here gives (the simulator's paired rounds, the ledger's box, the evidence table, the
     * search file's scores) still say so. The literature's verdicts are in their table, and the
     * risk sentence names the money of the row ranked first, by its key.
     */
    @Test
    public void everySectionWithItsDataIsPrinted() throws Exception {
        String p = page.text("index.html");
        for (String done : List.of("the searched count's result", "swings, money per hour", "the independent simulation",
                "the comparison with published figures", "spreads past one to")) {
            assertFalse(p.contains("Not yet computed: " + done), done);
        }
        for (String pending : List.of("how many simulated rounds would be needed", "the registration box",
                "the evidence table", "the searched counts' scores on the search file")) {
            assertTrue(p.contains("Not yet computed: " + pending), pending);
        }
        for (String k : List.of("lit.agree", "lit.direction", "lit.tested", "lit.untested", "lit.unexplained",
                "lit.not_compared", "lit.compared", "sim.rounds")) {
            assertTrue(p.contains("data-k=\"" + k + "\""), k);
        }
        String best = SiteSvg.rowOf(SiteBuild.one("full.80.s8.@best", page.data));
        assertEquals("victor_advanced_point_count_ace", best, "the world's highest row at full play");
        assertTrue(p.contains("data-k=\"risk.ev_per100_usd.full.80.s8." + best + "\""), "the money of the first-ranked row");
        assertTrue(p.contains("data-k=\"risk.sd_per100_usd.full.80.s8." + best + "\""));
    }

    /**
     * The ceiling count's paragraph in each cell is the one its claims pick, A naming F1 in the 1-8
     * cell and F1 flat in the flat cell, as the world's levels make it; the page's ladder and lines
     * draw F1 and the perfect bound from SiteExport's cells; F1 flat, which has no full play, is on
     * no chart that needs full play.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void theParagraphsAreTheClaimsPickAndTheRegisteredRowsAreDrawn() throws Exception {
        Map<String, Object> t = (Map<String, Object>) ((Map<String, Object>) SiteBuildTest.json(
                inputs.resolve("ceiling-world.json")).get("tables")).get("ceiling_paragraphs");
        assertEquals("A", ((Map<String, Object>) t.get("same")).get("letter"));
        assertEquals("F1", ((Map<String, Object>) t.get("same")).get("row"));
        assertEquals("A", ((Map<String, Object>) t.get("flat")).get("letter"));
        assertEquals("F1 flat", ((Map<String, Object>) t.get("flat")).get("row"));
        String p = page.text("index.html");
        for (String k : List.of("fd.80.s8.f1", "full.80.s8.f1", "full.80.s8.perfect", "fd.80.s1.perfect", "full.70.s3.f1")) {
            assertTrue(p.contains("data-k=\"" + k + "\""), k);
        }
        int every = p.indexOf("id=\"every-80\"");
        assertTrue(every > 0);
        String chart = p.substring(every, p.indexOf("</table>", every));
        assertTrue(chart.contains("full.80.s8.f1\""), "F1 has both dots");
        assertFalse(chart.contains(".f1_flat\""), "F1 flat has no full play to draw");
    }

    /**
     * The ceiling adapter's reading of c06c061's rows is read first (its file sorts before
     * SiteExport's) and carries no resamples; the merged figure keeps SiteExport's, so the guards
     * on Hi-Lo and the best row are met with interval support, and a change to one input's copy
     * stops the build.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void aFigureTwoInputsGiveIsOneValueWithItsResamples() throws Exception {
        Map<String, Object> ceiling = SiteBuildTest.json(inputs.resolve("ceiling-world.json"));
        Map<String, Object> mine = (Map<String, Object>) ((Map<String, Object>) ceiling.get("figures")).get("fd.80.s8.hi_lo");
        assertNotNull(mine, "the ceiling input gives Hi-Lo at first decisions");
        assertNull(mine.get("boot"));
        SiteData d = SiteData.read(inputs);
        assertEquals("ceiling-world.json", d.inputs.get(0).name, "the ceiling input is read first");
        assertNotNull(d.resolve("fd.80.s8.hi_lo").boot, "the merged figure keeps SiteExport's resamples");
        assertEquals(SiteBuild.guards(SiteBuild.conditionals(read("site/template.html"), d), d, "x"), List.of());

        Path changed = Files.createDirectories(dir.resolve("changed"));
        for (Path f : Files.newDirectoryStream(inputs)) {
            Files.copy(f, changed.resolve(f.getFileName()));
        }
        mine.put("value", ((Number) mine.get("value")).doubleValue() + 0.0001);
        SiteBuildTest.write(changed.resolve("ceiling-world.json"), ceiling);
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class, () -> SiteData.read(changed));
        assertTrue(x.getMessage().contains("one number must have one value"), x.getMessage());
    }
}
