import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ceiling report (CeilingReport, ComparisonReport --published) on a small synthetic file:
 * it reads the published rows from a results.ser written as FullIndexRun writes one, scores only
 * the counts given, and refuses stored rows that do not pair with it: another number of
 * resamples, or a stored figure that the same count scored again does not give to the bit.
 */
public class CeilingReportTest {

    private static final int RESAMPLES = 12;

    /** A results.ser as FullIndexRun writes it, of the counts given scored at first decisions. */
    static Path results(Path dir, Path file, ScoringStates st, List<SystemScorer.Result> results) throws Exception {
        List<CountKeys.Count> counts = new ArrayList<>();
        for (SystemScorer.Result r : results) {
            counts.add(r.count);
        }
        String id = FullIndexRun.identity("test", file, st.shoes, st.size, RESAMPLES, 2, counts, FullIndexRun.PENS, null);
        Path out = dir.resolve("results.ser");
        try (ObjectOutputStream o = new ObjectOutputStream(Files.newOutputStream(out))) {
            o.writeUTF(id);
            o.writeObject(new ArrayList<>(results));
            o.writeObject(st.perfect.clone());
            o.writeObject(new ArrayList<>());
        }
        return out;
    }

    static List<SystemScorer.Result> published(SystemScorer sc) throws Exception {
        List<CountKeys.Count> counts = new ArrayList<>();
        counts.add(CountKeys.nullCount());
        for (String s : new String[]{"Hi-Lo", "Hi-Opt II", "Red Seven (Red 7)", "Zen Count", "Omega II"}) {
            counts.add(CountSearch.catalogCount(s));
        }
        return ComparisonReport.scoreAll(sc, counts, 2);
    }

    @Test
    public void theReportReadsThePublishedRowsAndScoresOnlyTheCountsGiven(@TempDir Path dir) throws Exception {
        Path file = ComparisonReportTest.syntheticRun(dir, 77, 77, 24);
        ScoringStates st = FullIndexPilot.load(file, StateStore.shoesDone(file));
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, RESAMPLES, Bootstrap.SEED), SystemScorer.M_PLAY);
        List<SystemScorer.Result> stored = published(sc);
        Path ser = results(dir, file, st, stored);
        Path counts = CustomCountsTest.write(dir, "candidates.json", CustomCountsTest.CANDIDATES.replace(
                "\"family\": \"F2\",", "\"family\": \"F2\", \"role\": \"headline\","));
        byte[] before = Files.readAllBytes(file);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CeilingReport.Ceiling c = CeilingReport.run(file, ser, counts, RESAMPLES, 2, new PrintStream(bytes, true, "UTF-8"));
        String report = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        assertArrayEquals(before, Files.readAllBytes(file), "the state file is only read");
        assertEquals(Arrays.asList("Hi-Lo = Hi-Lo Lite", "Hi-Opt II", "Red Seven (Red 7)"), c.checked);
        assertEquals(5, c.custom.size(), "only the counts the file lists are scored");
        for (String section : new String[]{"# The ceiling count", "## Bounds", "No row is above its bound",
                "## The ladder, 1-8 same schedule", "## The ladder, flat", "## Headline claims",
                "- F2 start + betting vector at 80%, 1-8: gain", "## Every cell of the counts scored here",
                "### F1 start, bet on its own value", "## Plays fitted for the bets (the refit check)",
                "| Zen Count, plays fitted for the 1-8 schedule |", "| Perfect first move and insurance, bet on its own value |"}) {
            assertTrue(report.contains(section), section + "\n" + report);
        }
        // The published rows printed are the stored ones: the null count's first line, 1-8 at 70%, as stored.
        String nullLine = report.lines().filter(l -> l.startsWith("| Null count (no count) |")).findFirst().orElse("");
        assertTrue(nullLine.startsWith("| Null count (no count) | "
                + ComparisonReport.pct(stored.get(0).entries.get(0).estimate[0][SystemScorer.same(8)]) + " |"), nullLine);
        int p = SystemScorer.CUTS.length - 1;

        // The headline claim: every comparator's difference, the gain the smallest lower end.
        SystemScorer.Result f2 = c.custom.get(1);
        SystemScorer.Entry row = CeilingReport.row(f2);
        assertSame(f2.entries.get(2), row);
        List<SystemScorer.Entry> against = CeilingReport.comparators(c, f2);
        assertEquals(5 + 2, against.size(), "five catalog entries and the two side-count variants");
        CeilingReport.Claim claim = CeilingReport.claim(row, against, p, SystemScorer.same(8));
        double least = Double.POSITIVE_INFINITY;
        for (SystemScorer.Entry j : against) {
            least = Math.min(least, SystemScorer.difference(row, j, p, SystemScorer.same(8), 1).lo);
        }
        assertEquals(least, claim.gain, 0);
        assertEquals(5, CeilingReport.comparators(c, c.custom.get(0)).size(), "F1's are the plain rows");
    }

    /** On the twin, counts of our own are scored beside the catalog in every table; the main file refuses them so. */
    @Test
    public void onTheTwinCandidatesJoinTheCatalogsTables(@TempDir Path dir) throws Exception {
        Path counts = CustomCountsTest.write(dir, "candidates.json", CustomCountsTest.CANDIDATES);
        ComparisonReport.Options o = new ComparisonReport.Options();
        o.systems = Arrays.asList("Zen Count");
        o.resamples = 5;
        o.threads = 2;
        o.counts = counts;
        Path twin = ComparisonReportTest.syntheticRun(dir, CustomCounts.TWIN_SEED, CustomCounts.TWIN_SEED, 12);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ComparisonReport.run(twin, o, new PrintStream(bytes, true, "UTF-8"));
        String report = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        for (String row : new String[]{"| F1 start |", "| F2 start + betting vector |", "| F1r start |",
                "| Hi-Opt II at grain 500/10000 + ace side count |", "| Zen Count |"}) {
            assertTrue(report.contains(row), row);
        }
        Path main = ComparisonReportTest.syntheticRun(dir, 81, CustomCounts.MAIN_SEED, 4);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ComparisonReport.run(main, o,
                new PrintStream(new ByteArrayOutputStream(), true, "UTF-8")));
        assertTrue(e.getMessage().contains("main state file"), e.getMessage());
    }

    @Test
    public void storedRowsThatDoNotPairAreRefused(@TempDir Path dir) throws Exception {
        Path file = ComparisonReportTest.syntheticRun(dir, 78, 78, 16);
        ScoringStates st = FullIndexPilot.load(file, StateStore.shoesDone(file));
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, RESAMPLES, Bootstrap.SEED), SystemScorer.M_PLAY);
        List<SystemScorer.Result> stored = published(sc);
        Path counts = CustomCountsTest.write(dir, "candidates.json", CustomCountsTest.CANDIDATES);
        Path ser = results(dir, file, st, stored);
        PrintStream nowhere = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CeilingReport.run(file, ser, counts, RESAMPLES + 1, 2, nowhere));
        assertTrue(e.getMessage().contains("do not pair"), e.getMessage());

        // One resample of Red Seven's stored row moved by the last bit: scored again, it differs.
        SystemScorer.Entry red = stored.get(3).entries.get(0);
        red.boot[1][SystemScorer.FLAT][4] = Math.nextUp(red.boot[1][SystemScorer.FLAT][4]);
        Path tampered = results(dir, file, st, stored);
        e = assertThrows(IllegalStateException.class, () -> CeilingReport.run(file, tampered, counts, RESAMPLES, 2,
                nowhere));
        assertTrue(e.getMessage().contains("scored again, Red Seven"), e.getMessage());
    }
}
