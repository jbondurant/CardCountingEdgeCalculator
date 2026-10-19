package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The simulator's own file formats, those SHOE_SPEC.md Part C does not fix, held to the
 * {@code sim-formats} block of sim/QUESTIONS.md (item 27), so the engine side can write its
 * readers from that block: each file's header line, the keys of a summary's comparison and
 * difference in order, and the pilot's line kinds; and checks.txt's second D2 line held to the
 * {@code sim-checks} block (item 33). Run from the repository's root.
 */
class FormatsTest {

    @TempDir
    Path tmp;

    /** The block's lines by their first field. */
    static Map<String, List<String>> block() throws IOException {
        List<String> doc = Files.readAllLines(Path.of("sim/QUESTIONS.md"), StandardCharsets.UTF_8);
        int start = doc.indexOf("```sim-formats");
        assertTrue(start >= 0, "sim/QUESTIONS.md has no sim-formats block");
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = start + 1; !doc.get(i).equals("```"); i++) {
            List<String> f = List.of(doc.get(i).split("\t"));
            out.put(f.get(0), f.subList(1, f.size()));
        }
        return out;
    }

    static List<String> header(List<String> lines) {
        for (String l : lines) {
            if (!l.startsWith("#")) {
                return List.of(l.split("\t"));
            }
        }
        throw new AssertionError("no header line");
    }

    @SuppressWarnings("unchecked")
    @Test
    void theWritersWriteTheDocumentedFormats() throws Exception {
        Map<String, List<String>> spec = block();
        Path bundle = BundleFixture.write(tmp.resolve("b"));
        Path registry = tmp.resolve("SIM_CHECK.md");
        Files.write(registry, List.of("```sim-registry", Registry.HEADER,
                "F03\t1\tChart, no count, flat\t80\tflat\tP3,P2\tstep 7\t1\t0",
                "F26\t2\tHi-Lo = Hi-Lo Lite\t80\tflat\tP3,P2\tc06c061\t0\t0", "```"));
        Path out = tmp.resolve("run");
        String[] common = {"--bundle", bundle.toString(), "--out", out.toString(), "--sim-commit", "test",
                "--registry", registry.toString()};
        Sim.p3(SimCommandsTest.concat(common, "--chunks", "1", "--chunk-size", "30"));
        Sim.variants(SimCommandsTest.concat(common, "--chunks", "1", "--chunk-size", "30"));
        assertEquals(spec.get("depth.tsv"), header(Files.readAllLines(out.resolve("depth.tsv"))));
        assertEquals(spec.get("variants.tsv"), header(Files.readAllLines(out.resolve("variants.tsv"))));
        Ruin ruin = TripTest.records();
        assertEquals(spec.get("ruin-FIG.tsv"), header(ruin.lines("F27", ruin.run(1))));
        assertEquals(spec.get("trip-FIG.tsv"), header(new Trip(ruin).lines("F27", new Trip(ruin).run(new int[]{2}, new int[]{2}))));
        Map<String, Object> p3 = (Map<String, Object>) Json.parse(Files.readString(out.resolve("summary-p3.json")));
        Map<String, Object> comparison = (Map<String, Object>) ((List<Object>) p3.get("comparisons")).get(0);
        assertEquals(spec.get("comparison"), new ArrayList<>(comparison.keySet()));
        Map<String, Object> v = (Map<String, Object>) Json.parse(Files.readString(out.resolve("summary-variants.json")));
        Map<String, Object> difference = (Map<String, Object>) ((List<Object>) v.get("differences")).get(0);
        assertEquals(spec.get("difference"), new ArrayList<>(difference.keySet()));
        // The pilot's lines: each opens with one of the kinds SIM_CHECK.md section 8 lists.
        Bundle b = new Bundle(bundle, Plants.NONE);
        States states = b.states();
        Pilot.Sizes z = new Pilot.Sizes(PilotTest.SIZES);
        Path work = Files.createDirectories(tmp.resolve("work"));
        List<String> pilot = Pilot.measure(1, bundle, b, states, Sim.p2For(b, states, z.k(), Plants.NONE),
                new LinkedHashMap<>(), 1.0, work, "test", PilotTest.part1(), Path.of("counting-systems.json"), z,
                new ArrayList<>());
        List<String> kinds = List.of(spec.get("sim-pilot-partN.tsv").get(0).split("\\|"));
        for (String line : pilot) {
            assertTrue(kinds.contains(line.split("\t")[0]), line);
        }
    }

    /**
     * checks.txt's second D2 line, the columns D2 left out (SHOE_SPEC.md B4; sim/QUESTIONS.md item
     * 33), held to the {@code sim-checks} block: its first two fields as the block gives them, N
     * the number of columns left out, then one field a name, as many as N, in header order; with
     * none left out the line ends after the number.
     */
    @Test
    void checksTxtsSecondD2LineIsTheDocumentedForm() throws Exception {
        List<String> doc = Files.readAllLines(Path.of("sim/QUESTIONS.md"), StandardCharsets.UTF_8);
        int start = doc.indexOf("```sim-checks");
        assertTrue(start >= 0, "sim/QUESTIONS.md has no sim-checks block");
        assertEquals("```", doc.get(start + 2), "the block holds one line");
        List<String> spec = List.of(doc.get(start + 1).split("\t", -1));
        assertEquals(List.of("D2", "NAME\u2026"), List.of(spec.get(0), spec.get(2)));
        assertEquals(3, spec.size());
        assertTrue(spec.get(1).endsWith(": N"), spec.get(1));
        String text = spec.get(1).substring(0, spec.get(1).length() - 1);
        Map<String, String> status = new LinkedHashMap<>();
        for (List<String> names : List.of(List.<String>of(), List.of("F1 flat (f1-flat-pub-uston-apc)",
                "Hi-Opt II at grain 500/10000", "F1r's tags as a true count (f1r-double-shift-half)"))) {
            Path dir = BundleFixture.write(tmp.resolve("b" + names.size()));
            SimCommandsTest.writeKeys(dir, names, null, null);
            List<String> d2 = SimCommandsTest.d2Lines(SimCommandsTest.checks(dir, tmp.resolve("run" + names.size()), status));
            assertEquals(2, d2.size(), d2.toString());
            List<String> f = List.of(d2.get(1).split("\t", -1));
            assertEquals(spec.get(0), f.get(0));
            assertEquals(text + names.size(), f.get(1));
            assertEquals(names, f.subList(2, f.size()));
        }
    }
}
