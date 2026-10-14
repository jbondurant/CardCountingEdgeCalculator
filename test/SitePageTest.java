import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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

    @BeforeAll
    static void built() throws Exception {
        world = SiteWorld.make(Files.createDirectories(dir.resolve("world")));
        String template = read("site/template.html");
        inputs = Files.createDirectories(dir.resolve("inputs"));
        SiteExport.export(world.export(template)).write(inputs.resolve("results-world.json"));
        SiteCeiling.adapt(world.ceilingData(), "world/ceiling.json", SiteCeilingTest.paragraphs(world.dir),
                world.registration).write(inputs.resolve("ceiling-world.json"));
        SiteRisk.adapt(world.risk(), "world/risk.json").write(inputs.resolve("risk-world.json"));
        page = SiteBuild.build(SiteData.read(inputs), template, read("site/page.css"), read("site/page.js"),
                SiteBuild.tokens(SiteBuildTest.INDEX), "world-1", "2026-10-13", "0123456789abcdef0123456789abcdef01234567");
    }

    @Test
    public void thePageBuildsAndPassesEveryCheck() {
        assertEquals(List.of(), page.problems);
    }
}
