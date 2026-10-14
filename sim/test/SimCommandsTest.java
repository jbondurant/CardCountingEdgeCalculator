package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The commands end to end on the fixture bundle, at tiny sizes, in a temporary directory: what
 * each writes, in the formats of SHOE_SPEC.md Part C, and that a summary reads back as JSON.
 */
class SimCommandsTest {

    @TempDir
    Path tmp;

    @SuppressWarnings("unchecked")
    @Test
    void p3P2AndVariantsWriteTheirFiles() throws Exception {
        Path bundle = BundleFixture.write(tmp.resolve("b"));
        Path registry = tmp.resolve("SIM_CHECK.md");
        Files.write(registry, List.of("# a registry for the test", "```sim-registry", Registry.HEADER,
                "F03\t1\tChart, no count, flat\t80\tflat\tP3,P2\tstep 7\t1\t0",
                "F26\t2\tHi-Lo = Hi-Lo Lite\t80\tflat\tP3,P2\tc06c061\t0\t0",
                "F18\t1\tHi-Lo = Hi-Lo Lite\t80\tev:same-8\tP3,P2\tc06c061\t1\t1", "```"));
        Path out = tmp.resolve("run");
        String[] common = {"--bundle", bundle.toString(), "--out", out.toString(), "--sim-commit", "test",
                "--registry", registry.toString()};
        Sim.p3(concat(common, "--chunks", "2", "--chunk-size", "60", "--fan-paths", "2", "--fan-rounds", "300",
                "--ruin-paths", "200"));
        Map<String, Object> summary = (Map<String, Object>) Json.parse(Files.readString(out.resolve("summary-p3.json")));
        assertEquals(Chart.textbook().fingerprint(), summary.get("chartFingerprint"));
        List<Object> comparisons = (List<Object>) summary.get("comparisons");
        assertTrue(comparisons.stream().anyMatch(c -> "F18".equals(((Map<String, Object>) c).get("fig"))));
        assertEquals(15, Files.readAllLines(out.resolve("depth.tsv")).size());
        assertEquals("# ev-by-key.tsv: SHOE_SPEC.md C4, format 1", Files.readAllLines(out.resolve("ev-by-key.tsv")).get(0));
        List<String> swings = Files.readAllLines(out.resolve("swings.tsv"));
        assertTrue(swings.get(0).startsWith("shoes\tF03\t120\t"), swings.get(0));
        assertTrue(swings.stream().anyMatch(l -> l.startsWith("z\tF18\tP3\t")));
        assertFalse(swings.stream().anyMatch(l -> l.startsWith("z\tF18\tP2\t")), "P2 has not run");
        assertEquals(120, Files.readAllLines(out.resolve("records-F18.tsv")).size());
        List<String> fan = Files.readAllLines(out.resolve("fan-F18.tsv"));
        assertEquals(2, fan.size());
        assertEquals(4, fan.get(1).split("\t").length);
        // Trip risk and being behind (open item 11): a behind line for each hours and pace, and,
        // when the records' mean is above 0, a trip line for each of B_25%, B_5% and B_1%.
        List<String> trip = Files.readAllLines(out.resolve("trip-F18.tsv"));
        assertEquals("kind\thours\tpace\trounds\tblocks\tmeanRounds\trisk\tbankroll\tsimulated\tse\tformula", trip.get(3));
        assertEquals(Files.exists(out.resolve("ruin-F18.tsv")) ? 4 + 9 * 4 : 4 + 9, trip.size());
        assertTrue(trip.get(4).startsWith("behind\t10\t50\t500\t"), trip.get(4));
        // P2 with a plant goes on past the failed exact checks (the fixture has no keys-sample.tsv)
        assertFalse(Sim.p2(concat(common, "--k", "20", "--plant", "s17")));
        assertTrue(Files.readString(out.resolve("checks.txt")).contains("D2\tnot run"));
        Map<String, Object> facts = (Map<String, Object>) Json.parse(Files.readString(out.resolve("summary-p2.json")));
        assertEquals("not run", facts.get("D2"), "each exact check's outcome is in the summary (open item 10)");
        assertEquals("not run", facts.get("D5"));
        assertEquals("failed", facts.get("D4"), "the fixture's bets are made up");
        swings = Files.readAllLines(out.resolve("swings.tsv"));
        int p3z = swings.indexOf(swings.stream().filter(l -> l.startsWith("z\tF18\tP3\t")).findFirst().orElseThrow());
        assertTrue(swings.get(p3z + 1).startsWith("z\tF18\tP2\t"), "P2's z follows P3's");
        Map<String, Object> p2 = (Map<String, Object>) Json.parse(Files.readString(out.resolve("summary-p2.json")));
        assertTrue(((String) p2.get("plants")).contains("s17=on"));
        Sim.variants(concat(common, "--chunks", "1", "--chunk-size", "30"));
        List<String> variants = Files.readAllLines(out.resolve("variants.tsv"));
        assertEquals(2 + 5, variants.size(), "F26 is the one figure of F26 to F29 this registry has");
        Map<String, Object> v = (Map<String, Object>) Json.parse(Files.readString(out.resolve("summary-variants.json")));
        assertEquals(2, ((List<Object>) v.get("differences")).size());
    }

    /**
     * INTEGRATION.md open item 2: on a bundle whose rows hold no registered figure with paths
     * (part 1's: no F27 or F29), p3 kept no per-shoe records and then asked writeRecords for a part
     * of every chunk, so it stopped after its chunks, before summary-p3.json. It must finish, with
     * or without --registry (without, it reads SIM_CHECK.md from the working directory, the
     * repository's root, where Hi-Lo's first-decision figures at 80% have swings but no paths).
     */
    @SuppressWarnings("unchecked")
    @Test
    void p3FinishesOnABundleWithNoFigureWithPaths() throws Exception {
        Path bundle = BundleFixture.write(tmp.resolve("b"));
        Path registry = tmp.resolve("SIM_CHECK.md");
        Files.write(registry, List.of("```sim-registry", Registry.HEADER,
                "F03\t1\tChart, no count, flat\t80\tflat\tP3,P2\tstep 7\t1\t0",
                "F18\t1\tHi-Lo = Hi-Lo Lite\t80\tev:same-8\tP3,P2\tc06c061\t1\t0", "```"));
        for (boolean given : new boolean[]{true, false}) {
            Path out = tmp.resolve(given ? "with" : "without");
            String[] args = {"--bundle", bundle.toString(), "--out", out.toString(), "--sim-commit", "test",
                    "--chunks", "3", "--chunk-size", "20"};
            Sim.p3(given ? concat(args, "--registry", registry.toString()) : args);
            Map<String, Object> summary = (Map<String, Object>) Json.parse(Files.readString(out.resolve("summary-p3.json")));
            List<Object> comparisons = (List<Object>) summary.get("comparisons");
            assertTrue(comparisons.stream().anyMatch(c -> "F18".equals(((Map<String, Object>) c).get("fig"))),
                    "F18 is registered in both registries");
            assertTrue(Files.readAllLines(out.resolve("swings.tsv")).get(0).startsWith("shoes\tF03\t60\t"));
            try (var files = Files.list(out)) {
                assertFalse(files.anyMatch(f -> f.getFileName().toString().matches("(records|fan|ruin|trip)-.*")),
                        "no figure here keeps paths");
            }
        }
    }

    static String[] concat(String[] a, String... b) {
        String[] c = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
}
