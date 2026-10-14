package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The blind pilot (INTEGRATION.md open item 3, SIM_CHECK.md section 8) on the fixture bundle at
 * tiny sizes: its file in the format SimCheckPlan fill reads, each σ_eff and V equal to what a
 * run that is not blind gives on the same shoes and states, nothing written that holds a figure,
 * the stop on a failed exact check, and the figures it cannot measure named.
 */
class PilotTest {

    @TempDir
    Path tmp;

    static final String CHART = Row.CHART;
    static final String HILO = BundleFixture.HILO;
    static final String KO = "KO (Knock-Out) = REKO (Ridiculously Easy Knock-Out)";
    /** The full sizes the tests take 1/100 of: chunks of 6 shoes, K = 20, fans of 300 rounds, one ruin path. */
    static final String[] SIZES = {"--chunks", "2", "--chunk-size", "600", "--k", "2000", "--planted-k", "2000",
            "--fan-paths", "2", "--fan-rounds", "30000", "--ruin-paths", "100", "--scale", "100"};

    static Registry registry(String... lines) throws IOException {
        List<String> all = new ArrayList<>(List.of("```sim-registry", Registry.HEADER));
        all.addAll(List.of(lines));
        all.add("```");
        return Registry.parse(all);
    }

    static Registry part1() throws IOException {
        return registry("F01\t1\t" + CHART + "\t70\tflat\tP3\tstep 7\t0\t0",
                "F03\t1\t" + CHART + "\t80\tflat\tP3,P2\tstep 7\t1\t0",
                "F15\t1\t" + HILO + "\t80\tflat\tP3,P2\tc06c061\t0\t0",
                "F18\t1\t" + HILO + "\t80\tev:same-8\tP3,P2\tc06c061\t1\t0",
                "F21\t1\t" + KO + "\t80\tev:same-8\tP3,P2\tc06c061\t0\t0");
    }

    /** The fixed-cost files a blind run may leave in its scratch directory: chunks, records, checks. */
    static void assertBlind(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                assertTrue(name.matches("(p3|p2|variants)-\\d{5}\\.txt|records-.*|checks\\.txt|" + Pilot.MARK), "a blind run wrote " + p);
            }
        }
    }

    @Test
    void partOnesFileHoldsTimesSigmaEffVAndTheExactSidesSe() throws Exception {
        Path dir = BundleFixture.write(tmp.resolve("b"));
        Bundle bundle = new Bundle(dir, Plants.NONE);
        States states = bundle.states();
        Pilot.Sizes z = new Pilot.Sizes(SIZES);
        assertEquals(List.of(2, 6, 20, 20, 300, 1), List.of(z.chunks(), z.size(), z.k(), z.plantedK(), z.fanRounds(), z.ruinPaths()));
        Map<String, String> status = new LinkedHashMap<>(Map.of("D1", "passed"));
        Path work = Files.createDirectories(tmp.resolve("work"));
        List<String> missing = new ArrayList<>();
        Registry reg = part1();
        List<String> lines = Pilot.measure(1, dir, bundle, states, Sim.p2For(bundle, states, z.k(), Plants.NONE), status, 1.5,
                work, "test", reg, Path.of("counting-systems.json"), z, missing);
        assertBlind(work);
        assertEquals(List.of("part\t1", "setup\t1.5"), lines.subList(0, 2));
        assertTrue(lines.get(2).matches("time\tP3\t[0-9.E-]+") && lines.get(3).matches("time\tP2\t[0-9.E-]+"), lines.toString());
        assertEquals("time\textra\t0.0", lines.get(4), "part 1 has no extras");
        List<String> kinds = lines.subList(5, lines.size()).stream().map(l -> l.split("\t")[0] + " " + l.split("\t")[1]).toList();
        assertEquals(List.of("sd F01", "sd F03", "sd F15", "sd F18", "v F03", "v F15", "v F18",
                "exact F01", "exact F03", "exact F15", "exact F18"), kinds);
        assertEquals(List.of("F21 (no P3)", "F21 (no P2)", "F21 (no exact.tsv lines)"), missing);
        // The same sizes run with their figures written: σ_eff = SE·√ΣN by P3, V = SE²·K by P2.
        Path open = tmp.resolve("open");
        List<Summary.Comparison> p3 = Sim.p3(new Sim.P3Job(bundle, open, "test", 2, 6, 1, 2, 300, 1, Trip.HOURS, Trip.PACES,
                reg, Plants.NONE, false, false));
        List<Summary.Comparison> p2 = Sim.p2(bundle, states, Sim.p2For(bundle, states, 20, Plants.NONE), open, "test", 20, 1, reg,
                Plants.NONE, status, false);
        assertTrue(Files.exists(open.resolve("summary-p3.json")) && Files.exists(open.resolve("summary-p2.json")));
        for (String line : lines.subList(5, lines.size())) {
            String[] f = line.split("\t");
            Registry.Fig fig = reg.get(f[1]);
            double expected = switch (f[0]) {
                case "sd" -> Pilot.find(p3, f[1]).simulatedSe() * Math.sqrt(Pilot.find(p3, f[1]).rounds());
                case "v" -> Math.pow(Pilot.find(p2, f[1]).simulatedSe(), 2) * 20;
                default -> bundle.exact.standardError(fig.row(), fig.pen(), fig.figure());
            };
            assertEquals(Double.toString(expected), f[2], line);
            assertTrue(expected > 0, line);
        }
    }

    @Test
    void partTwoTimesItsExtras() throws Exception {
        Path dir = BundleFixture.write(tmp.resolve("b"));
        Bundle bundle = new Bundle(dir, Plants.NONE);
        States states = bundle.states();
        Pilot.Sizes z = new Pilot.Sizes(SIZES);
        Registry reg = registry("F03\t1\t" + CHART + "\t80\tflat\tP3,P2\tstep 7\t1\t0",
                "F26\t2\t" + HILO + "\t80\tflat\tP3,P2\tc06c061\t0\t0",
                "F27\t2\t" + HILO + "\t80\tev:same-8\tP3,P2\tc06c061\t1\t1",
                "F28\t2\tVictor Advanced Point Count (Victor APC) + ace side count, full play\t80\tflat\tP3,P2\tc06c061\t0\t0");
        Path work = Files.createDirectories(tmp.resolve("work"));
        List<String> missing = new ArrayList<>();
        List<String> lines = Pilot.measure(2, dir, bundle, states, Sim.p2For(bundle, states, z.k(), Plants.NONE),
                new LinkedHashMap<>(), 0.25, work, "test", reg, Path.of("counting-systems.json"), z, missing);
        assertBlind(work);
        assertTrue(Files.exists(work.resolve("variants").resolve("variants").resolve("variants-00003.txt")), "the variants ran on 4 chunks");
        for (String plant : Plants.ALL) {
            assertTrue(Files.exists(work.resolve("plant-" + plant).resolve("p2").resolve("p2-00000.txt")), plant);
        }
        assertEquals("part\t2", lines.get(0));
        assertTrue(Double.parseDouble(lines.get(4).split("\t")[2]) > 0, "the extras took time: " + lines.get(4));
        assertEquals(List.of("sd F26", "sd F27", "v F26", "v F27", "exact F26", "exact F27"),
                lines.subList(5, lines.size()).stream().map(l -> l.split("\t")[0] + " " + l.split("\t")[1]).toList());
        assertEquals(3, missing.size());
        assertTrue(missing.stream().allMatch(m -> m.startsWith("F28 ")));
    }

    @Test
    void aFailedExactCheckStopsThePartWithNoFile() throws Exception {
        Path dir = BundleFixture.write(tmp.resolve("b"));
        Path reg = tmp.resolve("SIM_CHECK.md");
        Files.write(reg, List.of("```sim-registry", Registry.HEADER, "F03\t1\t" + CHART + "\t80\tflat\tP3,P2\tstep 7\t1\t0", "```"));
        Path out = tmp.resolve("run");
        String[] args = SimCommandsTest.concat(new String[]{"--part", "1", "--bundle", dir.toString(), "--out", out.toString(),
                "--sim-commit", "test", "--registry", reg.toString()}, SIZES);
        // The fixture's states.bin is made up, so D1 and D4 fail, and it has no keys-sample.tsv or lookups.tsv.
        assertFalse(Pilot.run(args));
        assertTrue(Files.readString(out.resolve("pilot-part1-checks.txt")).contains("D2\tnot run"));
        assertFalse(Files.exists(out.resolve("sim-pilot-part1.tsv")));
        assertFalse(Files.exists(out.resolve("pilot-part1.work")), "the scratch directory is gone");
        // A directory of that name the pilot did not make is never deleted.
        Path other = Files.createDirectories(out.resolve("pilot-part1.work"));
        Files.writeString(other.resolve("keep.txt"), "not the pilot's");
        assertThrows(IOException.class, () -> Pilot.run(args));
        assertTrue(Files.exists(other.resolve("keep.txt")));
    }
}
