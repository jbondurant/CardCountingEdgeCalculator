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
 * records' is refused; one that is goes into the data file beside the report's own paths. And
 * the simulator's trip risk and chance of being behind, trip-FIG.tsv, as its own writer (sim.Trip)
 * makes it from the same records, beside the report's formula at its bankrolls and hours (open
 * item 19): in the report, with the agreement stated, and the data file the page reads unchanged.
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
        return run(tag, swings, ruin, new LinkedHashMap<>());
    }

    RiskReport.Outcome run(String tag, List<Path> swings, Map<String, Path> ruin, Map<String, Path> trip) throws Exception {
        Map<String, Path> rec = new LinkedHashMap<>();
        rec.put("F27", dir.resolve("records-F27.tsv"));
        return RiskReport.run(sources(), RiskFixture.write(dir, "pilot.txt", RiskFixture.pilot()), swings, rec, ruin, trip,
                "F27", RiskFixture.write(dir, "fan-F27.tsv", RiskFixture.fan()), "fixture", dir.resolve(tag + ".md"),
                dir.resolve(tag + ".json"));
    }

    /** trip-F27.tsv as the simulator's p3 writes it, from the records file, by the simulator's own classes. */
    Path trip(String name) throws Exception {
        sim.Ruin records = sim.Ruin.read(dir.resolve("records-F27.tsv"));
        sim.Trip t = new sim.Trip(records);
        return RiskFixture.write(dir, name, t.lines("F27", t.run(sim.Trip.HOURS, sim.Trip.PACES)));
    }

    /** A copy of a trip file with its first line of the kind given changed in one column. */
    Path changed(Path from, String name, String kind, int column, java.util.function.UnaryOperator<String> to) throws Exception {
        List<String> lines = new ArrayList<>(Files.readAllLines(from, StandardCharsets.UTF_8));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(kind + "\t")) {
                String[] f = lines.get(i).split("\t", -1);
                f[column] = to.apply(f[column]);
                lines.set(i, String.join("\t", f));
                return RiskFixture.write(dir, name, lines);
            }
        }
        throw new AssertionError("no " + kind + " line");
    }

    @Test
    public void theSimulatorsTripRiskSitsBesideThisReportsFormula() throws Exception {
        RiskFixture.write(dir, "records-F27.tsv", RiskFixture.records(20_000, 27));
        Path one = RiskFixture.write(dir, "swings.tsv", RiskFixture.swings(RiskReportTest.computable(), 2000, new HashMap<>()));
        Path trip = trip("trip-F27.tsv");
        List<String> lines = Files.readAllLines(trip, StandardCharsets.UTF_8);
        long trips = lines.stream().filter(l -> l.startsWith("trip\t")).count();
        assertEquals(9 * 3, trips, "the records win, so B_25%, B_5% and B_1% at each of 3 hours and 3 paces");
        RiskReport.Outcome o = run("trip", List.of(one), Map.of(), Map.of("F27", trip));
        RiskReport.RuinCheck c = o.ruin.get(0);
        assertEquals(36, c.trip.size());
        for (RiskReport.TripLine t : c.trip) {
            assertEquals(t.simulated, t.ownShare(), 0, "the same blocks and counts as the report's own");
            assertEquals(t.simFormula, t.formula, 1e-12, "the same formula at the simulator's bankroll and T");
        }
        String md = new String(Files.readAllBytes(dir.resolve("trip.md")), StandardCharsets.UTF_8);
        assertTrue(md.contains("The simulator's trip risk and chance of being behind (trip-F27.tsv), 36 lines, beside this "
                + "report's formula at the simulator's bankrolls (B_25%, B_5%, B_1%) and its hours and paces: its blocks and the "
                + "trips lost and behind in them are this report's own count from the same records, line for line"), md);
        assertTrue(md.contains("| trip | 1000 | 100 | 100000 |"), md);
        // The data file: every figure the report's own, as before; its list of checks passed one line longer.
        run("plain", List.of(one), Map.of());
        String strip = "\"inputs\":\\{[^}]*\\}";
        String plain = new String(Files.readAllBytes(dir.resolve("plain.json")), StandardCharsets.UTF_8).replaceAll(strip, "");
        String withTrip = new String(Files.readAllBytes(dir.resolve("trip.json")), StandardCharsets.UTF_8).replaceAll(strip, "");
        String checks = ",\"checks\":[";
        assertEquals(plain.substring(0, plain.indexOf(checks)), withTrip.substring(0, withTrip.indexOf(checks)),
                "the page's figures are the report's own, as before");
        assertEquals(plain.substring(0, plain.length() - 2) + ",\"F27: trip-F27.tsv's 36 lines are this report's own blocks "
                + "and counts from the same records, and its formula is this report's within ", withTrip.substring(0,
                withTrip.lastIndexOf("within ") + "within ".length()));

        RiskMath.ShoeSums sums = RiskReport.readRecords(dir.resolve("records-F27.tsv")).sums();
        String[][] refused = {
                {"not made from the same records", RiskFixture.write(dir, "t-mu.tsv", lines.stream().map(l -> l.startsWith("# mu\t")
                        ? "# mu\t" + sums.rate() * (1 + 1e-6) : l).collect(java.util.stream.Collectors.toList())).toString()},
                {"this report cuts the same records into", changed(trip, "t-share.tsv", "trip", 8,
                        x -> Double.toString(Double.parseDouble(x) + 0.01)).toString()},
                {"and the records' B_25% is", changed(trip, "t-bank.tsv", "trip", 7,
                        x -> Double.toString(Double.parseDouble(x) * 1.001)).toString()},
                {"the simulator's formula gives", changed(trip, "t-formula.tsv", "behind", 10,
                        x -> Double.toString(Double.parseDouble(x) + 1e-6)).toString()},
                {"not hours x pace", changed(trip, "t-rounds.tsv", "behind", 3, x -> "999").toString()}};
        int k = 0;
        for (String[] r : refused) {
            String tag = "refused-" + k++;
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> run(tag, List.of(one), Map.of(), Map.of("F27", java.nio.file.Paths.get(r[1]))));
            assertTrue(e.getMessage().contains(r[0]), r[0] + ": " + e.getMessage());
            assertFalse(Files.exists(dir.resolve(tag + ".json")));
        }
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
