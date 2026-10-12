import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs A and A' and the report from files, as they will run after step 8 (PLAN_COMPUTE.md 7.2,
 * rows 2 and 10), on a small synthetic states file and a results.ser written as FullIndexRun
 * writes one: each run reads the states and the stored rows, pairs them, checks, and keeps its
 * collector; the report reads those back with report-final's printed tables and writes JSON that
 * names every input by its SHA-256. The states file is only read. A stored figure one bit off
 * stops run A before it keeps anything, so the real run's gate is the code tested here.
 */
public class KeyEdgesFilesTest {

    @SuppressWarnings("unchecked")
    private static List<Document> list(Document d, String key) {
        return (List<Document>) d.get(key);
    }

    @Test
    public void runsAAndAPrimeAndTheReportFromFiles(@TempDir Path dir) throws Exception {
        Path file = ComparisonReportTest.syntheticRun(dir, 131, 131, 24);
        byte[] before = Files.readAllBytes(file);
        ScoringStates st = KeyEdgesRun.load(file);
        int resamples = CeilingReportTest.RESAMPLES;
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, resamples, Bootstrap.SEED), SystemScorer.M_PLAY);
        List<SystemScorer.Result> stored = new ArrayList<>();
        for (CountKeys.Count c : KeyEdgesRun.catalogRows()) {
            stored.add(sc.score(c, CountKeys.bettingVariant(c)));
        }
        Path ser = CeilingReportTest.results(dir, file, st, stored);
        Path out = Files.createDirectories(dir.resolve("out"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream log = new PrintStream(bytes, true, "UTF-8");

        KeyEdgesRun.runA(file, ser, out, "test", resamples, log);
        KeyEdgesRun.runAPrime(file, ser, out, "test", resamples, 1, log);
        Path a = out.resolve("a-test.ser");
        Path aPrime = out.resolve("a-prime-test.ser");
        assertTrue(KeyEdges.Collector.read(a).identity.contains("\nresamples " + resamples + ", seed " + Bootstrap.SEED));

        // report-final's per-key section, printed from the stored rows as ComparisonReport prints it.
        ComparisonReport.Scored printed = new ComparisonReport.Scored();
        printed.results = stored;
        printed.nullCount = stored.get(0);
        Path md = dir.resolve("report.md");
        try (PrintStream p = new PrintStream(Files.newOutputStream(md), true, "UTF-8")) {
            ComparisonReport.keyTables(printed, p);
        }
        KeyEdgesReport.write(a, new ArrayList<>(), aPrime, new ArrayList<>(), ser, md, out, "test", resamples, log);
        String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        assertArrayEquals(before, Files.readAllBytes(file), "the state file is only read");
        assertTrue(text.contains("every check passed; kept " + a), text);
        assertTrue(text.contains("every check passed; kept " + aPrime), text);
        assertTrue(text.contains("every refitted figure is c06c061's to the bit"), text);

        Document d = Document.parse(new String(Files.readAllBytes(out.resolve("key-edges-test.json")),
                StandardCharsets.UTF_8));
        List<Document> inputs = list(d, "inputs");
        assertEquals(4, inputs.size());
        for (Path f : new Path[]{a, aPrime, ser, md}) {
            boolean named = false;
            for (Document i : inputs) {
                named |= i.getString("file").equals(f.toAbsolutePath().normalize().toString())
                        && i.getString("sha256").equals(KeyEdgesRun.sha256(f));
            }
            assertTrue(named, f + " is named with its SHA-256");
        }
        assertEquals(KeyEdgesReport.rows(KeyEdges.Collector.read(a)).size(), list(d, "rows").size());
        Document check9 = (Document) d.get("printedTables");
        assertEquals(new ArrayList<String>(), check9.get("differences"));
        assertEquals(new ArrayList<String>(), check9.get("tablesNotScored"));
        assertEquals(check9.getInteger("lines"), check9.getInteger("same"));
        assertEquals(list(d, "rows").size() * SystemScorer.CUTS.length, (int) check9.getInteger("tables"));
        assertTrue(Files.exists(out.resolve("key-edges-test.csv")));

        // One stored estimate a bit off: run A refuses and keeps nothing.
        SystemScorer.Entry e = stored.get(1).entries.get(0);
        e.estimate[2][SystemScorer.FLAT] = Math.nextUp(e.estimate[2][SystemScorer.FLAT]);
        Path bad = CeilingReportTest.results(Files.createDirectories(dir.resolve("bad")), file, st, stored);
        Path out2 = Files.createDirectories(dir.resolve("out2"));
        IllegalStateException x = assertThrows(IllegalStateException.class,
                () -> KeyEdgesRun.runA(file, bad, out2, "test", resamples, log));
        assertTrue(x.getMessage().contains(e.name + " at 80%: the estimate differs"), x.getMessage());
        assertFalse(Files.exists(out2.resolve("a-test.ser")));
        // Stored rows of another number of resamples do not pair.
        assertThrows(IllegalStateException.class, () -> KeyEdgesRun.runA(file, ser, out2, "test", resamples + 1, log));
        assertEquals(Collections.emptyList(), listing(out2));
    }

    private static List<Path> listing(Path dir) throws Exception {
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            return s.collect(java.util.stream.Collectors.toList());
        }
    }
}
