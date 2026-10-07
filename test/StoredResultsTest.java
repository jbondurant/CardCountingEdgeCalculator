import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * c06c061's files read with these classes (COUNT_DESIGN.md, section 6, "Stored results
 * readable"): one item checkpoint the whole-file run wrote, kept beside the tests, reads as
 * c06c061's own classes read it, figure for figure; and the run's results.ser, where it is on
 * this machine, reads with every count at divisor 1 and no betting vector, and gives the figures
 * the run's peek printed. The classes kept their serialVersionUIDs (1 throughout), so what they
 * gained since reads as nothing: a zero divisor, read as 1, and no betting vector.
 */
public class StoredResultsTest {

    /** part-0-0-halves.ckpt of runs/full-index/full-c06c061: the null count's half fits at 70%. */
    static final Path PART = Paths.get("test", "c06c061-part-0-0-halves.ckpt");

    /** An object stream that finds its classes with the loader given. */
    static final class LoaderInput extends ObjectInputStream {
        private final ClassLoader loader;

        LoaderInput(InputStream in, ClassLoader loader) throws IOException {
            super(in);
            this.loader = loader;
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass d) throws IOException, ClassNotFoundException {
            try {
                return Class.forName(d.getName(), false, loader);
            } catch (ClassNotFoundException e) {
                return super.resolveClass(d);
            }
        }
    }

    static Path results() {
        for (Path p : new Path[]{Paths.get("runs", "full-index", "full-c06c061", "results.ser"),
                Paths.get("..", "..", "runs", "full-index", "full-c06c061", "results.ser")}) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        assumeTrue(false, "no results.ser of c06c061 on this machine");
        return null;
    }

    private static void sameBits(double[] a, double[] b, String what) {
        assertEquals(a == null, b == null, what);
        if (a != null) {
            assertEquals(a.length, b.length, what);
            for (int k = 0; k < a.length; k++) {
                assertEquals(Double.doubleToLongBits(a[k]), Double.doubleToLongBits(b[k]), what + " at " + k);
            }
        }
    }

    /** A Count read from c06c061's files: divisor 1, no betting vector, bets on its play tags. */
    static void asWritten(CountKeys.Count c) {
        assertEquals(1, c.divisor(), c.name);
        assertNull(c.betTag, c.name);
        assertNull(c.betVector, c.name);
        assertSame(c.tag, c.betTags(), c.name);
        assertEquals(c.deckSum, c.betDeckSum(), c.name);
    }

    @Test
    public void anItemCheckpointOfC06c061ReadsAsItsOwnClassesReadIt() throws Exception {
        assertTrue(Files.isRegularFile(PART), PART + " is kept beside the tests");
        String identity;
        SystemScorer.Result now;
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(PART))) {
            identity = in.readUTF();
            now = (SystemScorer.Result) in.readObject();
        }
        assertTrue(identity.startsWith("FullIndexRun joint run\ncommit c06c061"), identity);
        assertEquals("Null count (no count)", now.count.name);
        asWritten(now.count);
        for (SystemScorer.Entry e : now.entries) {
            asWritten(e.count);
            assertFalse(e.ownValue);
            assertNull(e.halfFitSame);
        }
        for (SystemScorer.Entry e : now.fullEntries) {
            asWritten(e.count);
        }
        // A halves item holds the half fits of its penetration, 70%, and nothing else.
        assertNotNull(now.fullHalfFit[0][0]);
        assertNull(now.entries.get(0).estimate[0]);
        assertFalse(Double.isNaN(now.fullHalfFitFlat[0]));
        assertTrue(now.fullHalfFitBoot[0].length > 0);

        ClassLoader old = C06c061.loader();
        Object was;
        try (ObjectInputStream in = new LoaderInput(Files.newInputStream(PART), old)) {
            assertEquals(identity, in.readUTF());
            was = in.readObject();
        }
        assertSame(old, was.getClass().getClassLoader(), "read by c06c061's own classes");
        assertEquals(C06c061.get(C06c061.get(was, "count"), "name"), now.count.name);
        sameBits((double[]) C06c061.get(was, "fullHalfFitFlat"), now.fullHalfFitFlat, "half fit flat EV");
        double[][] boot = (double[][]) C06c061.get(was, "fullHalfFitBoot");
        for (int p = 0; p < boot.length; p++) {
            sameBits(boot[p], now.fullHalfFitBoot[p], "half fit resamples at " + p);
        }
        int[][] inSample = (int[][]) C06c061.get(was, "fullInSample");
        for (int p = 0; p < inSample.length; p++) {
            assertArrayEquals(inSample[p], now.fullInSample[p]);
        }
        Object[][] fits = (Object[][]) C06c061.get(was, "fullHalfFit");
        for (int f = 0; f < 2; f++) {
            assertArrayEquals((boolean[]) C06c061.get(fits[0][f], "stands"), now.fullHalfFit[0][f].stands);
        }
        @SuppressWarnings("unchecked")
        List<Object> entries = (List<Object>) C06c061.get(was, "entries");
        assertEquals(entries.size(), now.entries.size());
        for (int e = 0; e < entries.size(); e++) {
            assertEquals(C06c061.get(entries.get(e), "name"), now.entries.get(e).name);
        }
    }

    /**
     * The whole run's results.ser, where it is on this machine: every count reads at divisor 1 with
     * no betting vector, and Hi-Lo, Zen and the null count give the figures the run's peek printed
     * at 80% (runs/full-index/peek-c06c061-2026-10-06-2139.txt), first decisions and full play.
     */
    @Test
    public void theRunsResultsReadAndGiveThePeeksFigures() throws Exception {
        CeilingReport.Published p = CeilingReport.read(results());
        assertTrue(p.identity.startsWith("FullIndexRun joint run\ncommit c06c061"));
        assertEquals(31, p.results.size());
        for (SystemScorer.Result r : p.results) {
            asWritten(r.count);
            for (SystemScorer.Entry e : r.entries) {
                asWritten(e.count);
                assertNull(e.halfFitSame);
            }
        }
        int pen = 2;
        int s8 = SystemScorer.same(SystemScorer.SPREADS);
        String[][] peek = {
                {"Null count (no count)", "-0.9926", "-0.9917", "-0.6604", "-0.6598"},
                {"Hi-Lo", "+0.2937", "+0.3641", "-0.6006", "-0.5811"},
                {"Zen Count", "+0.3562", "+0.4405", null, null}};
        for (String[] row : peek) {
            SystemScorer.Result r = row[0].startsWith("Null") ? p.nullCount() : p.of(row[0]);
            assertEquals(row[1], String.format(java.util.Locale.ROOT, "%+.4f", 100 * r.entries.get(0).estimate[pen][s8]));
            assertEquals(row[2], String.format(java.util.Locale.ROOT, "%+.4f", 100 * r.fullEntries.get(0).estimate[pen][s8]));
            if (row[3] != null) {
                assertEquals(row[3], String.format(java.util.Locale.ROOT, "%+.4f",
                        100 * r.entries.get(0).estimate[pen][SystemScorer.FLAT]));
                assertEquals(row[4], String.format(java.util.Locale.ROOT, "%+.4f",
                        100 * r.fullEntries.get(0).estimate[pen][SystemScorer.FLAT]));
            }
        }
        assertEquals(60743, p.perfect.length);
        List<String> names = new ArrayList<>();
        for (SystemScorer.Result r : p.results) {
            names.add(r.count.name);
        }
        assertTrue(names.containsAll(Arrays.asList("Hi-Lo = Hi-Lo Lite", "Hi-Opt II", "Red Seven (Red 7)")), names.toString());
    }
}
