import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BoundRows on a small twin-seed file (PLAN_COMPUTE.md 4.3, the preview): without --perfect it
 * prints the two bound rows as before; with --perfect and --shoes it scores the full-play bound
 * from the run's perfect array on the run's own shoes and prints that run's full-play rows beside
 * it; it refuses results of other states, a perfect array lacking a state, and the main file.
 */
public class BoundRowsTest {

    private static String run(String... args) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BoundRows.run(args, new PrintStream(bytes, true, "UTF-8"));
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String line(String report, String start, String pen) {
        String at = report.substring(report.indexOf(pen + " penetration"));
        return at.lines().filter(l -> l.startsWith(start)).findFirst().orElse("");
    }

    @Test
    public void theFullPlayBoundComesFromTheRunsPerfectArrayOnItsOwnShoes(@TempDir Path dir) throws Exception {
        Path twin = ComparisonReportTest.syntheticRun(dir, CustomCounts.TWIN_SEED, CustomCounts.TWIN_SEED, 16);
        int shoes = StateStore.shoesDone(twin) - 4;
        ScoringStates st = FullIndexPilot.load(twin, shoes);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, 0, Bootstrap.SEED), SystemScorer.M_PLAY);
        double[] full = new double[st.size];
        for (int s = 0; s < st.size; s++) {
            full[s] = st.perfect[s] + 0.001 * (s % 3);
        }
        // A full-play row as a full-index run at 80% alone stores one: estimates at 80%, none elsewhere.
        SystemScorer.Result hiLo = sc.score(CountSearch.catalogCount("Hi-Lo"), null);
        SystemScorer.Entry fullRow = new SystemScorer.Entry("Hi-Lo, full play", hiLo.count, false, true);
        fullRow.estimate[2] = hiLo.entries.get(0).estimate[2].clone();
        fullRow.estimate[2][SystemScorer.same(8)] += 0.0123;
        hiLo.fullEntries.add(fullRow);
        Path ser = CeilingReportTest.results(dir.resolve("run"), twin, st, List.of(hiLo), full);

        String plain = run(twin.toString());
        assertFalse(plain.contains("Perfect play of the dealt hand"), plain);
        assertTrue(plain.contains("| Perfect first move and insurance, bet on its own value | "), plain);

        String report = run(twin.toString(), "--perfect", ser.toString(), "--shoes", Integer.toString(shoes));
        assertTrue(report.contains("(the first " + shoes + " of " + (shoes + 4) + ")"), report);
        List<SystemScorer.Entry> bounds = sc.bounds(full);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            String pen = SystemScorer.PENETRATIONS[p];
            String row = line(report, "| Perfect play of the dealt hand and insurance, bet on its own value |", pen);
            assertTrue(row.contains(String.format(Locale.ROOT, " %+.4f |", 100 * bounds.get(2).estimate[p][SystemScorer
                    .same(8)])), pen + ": " + row);
        }
        String at80 = line(report, "| Hi-Lo, full play |", "80%");
        assertTrue(at80.endsWith(String.format(Locale.ROOT, " %+.4f |", 100 * fullRow.estimate[2][SystemScorer.same(8)])),
                at80);
        assertEquals("", line(report.substring(0, report.indexOf("80% penetration")), "| Hi-Lo, full play |", "70%"),
                "a row not scored at 70% is left out there");
    }

    @Test
    public void otherStatesAnIncompleteArrayAndTheMainFileAreRefused(@TempDir Path dir) throws Exception {
        Path twin = ComparisonReportTest.syntheticRun(dir, CustomCounts.TWIN_SEED, CustomCounts.TWIN_SEED, 12);
        ScoringStates st = FullIndexPilot.load(twin, StateStore.shoesDone(twin));
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, 0, Bootstrap.SEED), SystemScorer.M_PLAY);
        SystemScorer.Result hiLo = sc.score(CountSearch.catalogCount("Hi-Lo"), null);
        Path whole = CeilingReportTest.results(dir.resolve("whole"), twin, st, List.of(hiLo), st.perfect.clone());

        // Results of every shoe read beside the first few alone: other states.
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(twin.toString(), "--perfect",
                whole.toString(), "--shoes", "6"));
        assertTrue(e.getMessage().contains("not of these states"), e.getMessage());

        double[] lacking = st.perfect.clone();
        lacking[CeilingReportTest.onlyAt80(sc)] = Double.NaN;
        Path nan = CeilingReportTest.results(dir.resolve("nan"), twin, st, List.of(hiLo), lacking);
        e = assertThrows(IllegalStateException.class, () -> run(twin.toString(), "--perfect", nan.toString()));
        assertTrue(e.getMessage().contains("lacks a value at 80%: 1 of"), e.getMessage());

        Path main = ComparisonReportTest.syntheticRun(dir, 81, CustomCounts.MAIN_SEED, 4);
        e = assertThrows(IllegalStateException.class, () -> run(main.toString()));
        assertTrue(e.getMessage().contains("main state file"), e.getMessage());
    }
}
