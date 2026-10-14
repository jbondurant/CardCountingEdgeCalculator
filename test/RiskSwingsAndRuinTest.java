import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RiskReport takes several swings files (INTEGRATION.md open item 6: each part's run writes one)
 * and the simulator's own ruin paths, ruin-FIG.tsv, beside its own from the same records (open
 * item 10), on RiskReportTest's fixture. Split over two files the swings give the report one file
 * gives, to the byte; a figure in both is refused; a ruin file whose mu or sigma_eff is not its
 * records' is refused; one that is goes into the data file beside the report's own paths.
 */
public class RiskSwingsAndRuinTest {

    @TempDir
    Path dir;

    Map<String, Path> sources() throws Exception {
        Path results = dir.resolve("results.ser");
        RiskFixture.writeResults(results, RiskFixture.identity("FullIndexRun joint run"), RiskFixture.results());
        Path step7 = dir.resolve("step7.ser");
        RiskFixture.writeStep7(step7, RiskFixture.identity("CeilingReport"), -0.0066);
        Map<String, Path> s = new LinkedHashMap<>();
        s.put("c06c061", results);
        s.put("step7", step7);
        return s;
    }

    RiskReport.Outcome run(String tag, List<Path> swings, Map<String, Path> ruin) throws Exception {
        Map<String, Path> rec = new LinkedHashMap<>();
        rec.put("F27", dir.resolve("records-F27.tsv"));
        return RiskReport.run(sources(), RiskFixture.write(dir, "pilot.txt", RiskFixture.pilot()), swings, rec, ruin, "F27",
                RiskFixture.write(dir, "fan-F27.tsv", RiskFixture.fan()), "fixture", dir.resolve(tag + ".md"),
                dir.resolve(tag + ".json"));
    }

    /** ruin-F27.tsv as the simulator writes it, its mu and sigma_eff the ones given. */
    Path ruin(String name, double mu, double sigma) throws Exception {
        List<String> l = new ArrayList<>();
        l.add("# ruin-F27.tsv: SIM_CHECK.md section 11's ruin paths from records-F27.tsv (20000 shoes), seed 20261010");
        l.add("# mu\t" + mu);
        l.add("# sigmaEff\t" + sigma);
        l.add("risk\tbankroll\tpaths\truined\tsimulated\tse\tcapped\tmeanShoes");
        l.add("0.25\t900.0\t20000\t5010\t0.2505\t0.003\t0\t40.5");
        l.add("0.05\t1950.0\t20000\t1002\t0.0501\t0.0015\t0\t90.1");
        l.add("0.01\t3000.0\t20000\t198\t0.0099\t7.0E-4\t0\t120.7");
        return RiskFixture.write(dir, name, l);
    }

    @Test
    public void theSwingsMayComeFromSeveralFilesAndTheRuinPathsSitBeside() throws Exception {
        List<String> all = RiskFixture.swings(RiskReportTest.computable(), 2000, new HashMap<>());
        List<String> part1 = new ArrayList<>();
        List<String> part2 = new ArrayList<>();
        for (String l : all) {
            if (l.startsWith("#")) {
                continue;
            }
            String fig = l.split("\t")[1];
            (fig.equals("F03") || fig.equals("F18") ? part1 : part2).add(l);
        }
        assertFalse(part1.isEmpty() || part2.isEmpty());
        RiskFixture.write(dir, "records-F27.tsv", RiskFixture.records(20_000, 27));
        Path one = RiskFixture.write(dir, "swings.tsv", all);
        run("one", List.of(one), new LinkedHashMap<>());
        Path a = RiskFixture.write(dir, "swings-part1.tsv", part1);
        Path b = RiskFixture.write(dir, "swings-part2.tsv", part2);
        run("two", List.of(a, b), new LinkedHashMap<>());
        String jOne = new String(Files.readAllBytes(dir.resolve("one.json")), StandardCharsets.UTF_8);
        String jTwo = new String(Files.readAllBytes(dir.resolve("two.json")), StandardCharsets.UTF_8);
        assertEquals(jOne.replaceAll("\"inputs\":\\{[^}]*\\}", ""), jTwo.replaceAll("\"inputs\":\\{[^}]*\\}", ""),
                "two files give what one gives");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> run("both", List.of(one, a), new LinkedHashMap<>()));
        assertTrue(e.getMessage().contains("has swings in both"), e.getMessage());

        RiskMath.ShoeSums sums = RiskReport.readRecords(dir.resolve("records-F27.tsv")).sums();
        double mu = sums.rate();
        double sigma = RiskMath.sigmaEff(sums);
        Map<String, Path> wrong = Map.of("F27", ruin("ruin-wrong.tsv", mu * (1 + 1e-6), sigma));
        IllegalStateException x = assertThrows(IllegalStateException.class, () -> run("wrong", List.of(one), wrong));
        assertTrue(x.getMessage().contains("not made from the same records"), x.getMessage());
        assertFalse(Files.exists(dir.resolve("wrong.json")));
        run("ruin", List.of(one), Map.of("F27", ruin("ruin-F27.tsv", mu, sigma)));
        String j = new String(Files.readAllBytes(dir.resolve("ruin.json")), StandardCharsets.UTF_8);
        assertTrue(j.contains("\"sim\":{\"mu\":"), "the simulator's paths beside the report's own");
        assertTrue(j.contains("[1.95E+3,0.05,0.0501,0.0015]"), j);
        String md = new String(Files.readAllBytes(dir.resolve("ruin.md")), StandardCharsets.UTF_8);
        assertTrue(md.contains("The simulator's own ruin paths from the same records (ruin-F27.tsv)"), md);
    }
}
