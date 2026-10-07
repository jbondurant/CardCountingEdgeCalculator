import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FullIndexRun with counts from a file, on a small synthetic state file: it runs only those
 * counts at the penetrations given, each with its betting variant and its own-value rows, names
 * every count's tags in its identity, refuses a tree store another run kept and reads one
 * read-only to the same results, writing nothing to it; and on the main state file it refuses a
 * candidates file (COUNT_DESIGN.md, sections 5 to 7).
 */
public class FullIndexRunTest {

    private static String[] args(Path states, Path out, Path counts, String... more) {
        String[] base = {"--states", states.toString(), "--out", out.toString(), "--counts", counts.toString(), "--pens",
                "2", "--resamples", "3", "--threads", "2"};
        String[] all = new String[base.length + more.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    @Test
    public void aRunOfCountsFromAFile(@TempDir Path dir) throws Exception {
        Path file = ComparisonReportTest.syntheticRun(dir, 79, 79, 12);
        Path counts = CustomCountsTest.write(dir, "candidates.json", "{ \"kind\": \"candidates\", \"K\": 10000, "
                + "\"counts\": [ { \"name\": \"F2 start\", \"family\": \"F2\", \"form\": \"true\", \"divisor\": 2500,\n"
                + "    \"tags\": [0, 10000, 10000, 20000, 20000, 10000, 10000, 0, 0, -20000],\n"
                + "    \"betting\": [-20000, 10000, 10000, 20000, 20000, 10000, 10000, 0, 0, -20000] } ] }");
        PrintStream log = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        Path trees = dir.resolve("trees.bin");
        FullIndexRun.run(args(file, dir.resolve("a"), counts, "--trees", trees.toString()), "test", log, 0);
        CeilingReport.Published a = CeilingReport.read(dir.resolve("a").resolve("results.ser"));
        assertTrue(a.identity.contains("count F2 start: true count, tags ["), a.identity);
        assertTrue(a.identity.contains("divisor 2500, bet tags the play tags, ace side count 0, betting vector ["));
        assertTrue(a.identity.contains("  with F2 start + betting vector: true count"));
        assertTrue(a.identity.contains("penetrations [2]"));
        assertTrue(a.identity.contains("each also bet on its own value"));
        assertEquals(1, a.results.size());
        SystemScorer.Result r = a.results.get(0);
        assertEquals(5, r.entries.size(), "the count, its betting variant, each betting only, and its own-value row");
        assertEquals("F2 start + betting vector", r.entries.get(2).name);
        assertTrue(r.entries.get(4).ownValue);
        assertEquals(3, r.fullEntries.size());
        assertTrue(r.fullEntries.get(2).ownValue && r.fullEntries.get(2).fullPlay);
        assertNotNull(r.fullEntries.get(2).estimate[2]);
        assertNull(r.entries.get(0).estimate[0], "70% was not run");
        byte[] kept = Files.readAllBytes(trees);
        byte[] index = Files.readAllBytes(dir.resolve("trees.bin.index"));

        // Another run, other classes, pointed at that store: refused, and the store left as it is.
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> FullIndexRun.run(args(file,
                dir.resolve("b"), counts, "--trees", trees.toString()), "other", log, 0));
        assertTrue(e.getMessage().contains("kept by another run"), e.getMessage());
        // Read-only, it gives the same results, and nothing is written to the store.
        FullIndexRun.run(args(file, dir.resolve("c"), counts, "--trees-read-only", trees.toString()), "other", log, 0);
        assertArrayEquals(kept, Files.readAllBytes(trees));
        assertArrayEquals(index, Files.readAllBytes(dir.resolve("trees.bin.index")));
        SystemScorer.Result c = CeilingReport.read(dir.resolve("c").resolve("results.ser")).results.get(0);
        for (int k = 0; k < r.entries.size(); k++) {
            assertArrayEquals(r.entries.get(k).estimate[2], c.entries.get(k).estimate[2], 0);
        }
        for (int k = 0; k < r.fullEntries.size(); k++) {
            assertArrayEquals(r.fullEntries.get(k).estimate[2], c.fullEntries.get(k).estimate[2], 0);
            assertArrayEquals(r.fullEntries.get(k).boot[2][SystemScorer.same(8)],
                    c.fullEntries.get(k).boot[2][SystemScorer.same(8)], 0);
        }
    }

    @Test
    public void theMainFileRefusesACandidatesFile(@TempDir Path dir) throws Exception {
        Path main = CustomCountsTest.stateFile(dir, CustomCounts.MAIN_SEED);
        Path counts = CustomCountsTest.write(dir, "candidates.json", CustomCountsTest.CANDIDATES);
        PrintStream log = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> FullIndexRun.run(args(main, dir.resolve("run"), counts), "test", log, 0));
        assertTrue(e.getMessage().contains("candidates file"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("run")));
    }
}
