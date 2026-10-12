import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Check 9 of PLAN_COMPUTE.md 5.4, run A against report-final's printed tables: the per-key
 * tables ComparisonReport prints, read back, are the collector's tables at the digits printed,
 * line for line; a value changed in one state shows up as the line it moves; a printed table of
 * an entry we did not score is named, not compared.
 */
public class KeyEdgesPrintedTest {

    /** The per-key section as ComparisonReport.keyTables prints it for these results. */
    static List<String> printedBy(List<SystemScorer.Result> results, SystemScorer.Result nullCount) {
        ComparisonReport.Scored sc = new ComparisonReport.Scored();
        sc.results = results;
        sc.nullCount = nullCount;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true);
        out.println("# a report");
        out.println();
        ComparisonReport.keyTables(sc, out);
        out.println("## The next section");
        out.println("### Not a per-key table, 80%");
        out.println("| 0 | 1.0000 | +9.999 |");
        return Arrays.asList(new String(bytes.toByteArray(), StandardCharsets.UTF_8).split("\n"));
    }

    @Test
    public void theTablesComparisonReportPrintsAreTheCollectorsAtTheDigitsPrinted() {
        KeyEdgesTest.Scored s = new KeyEdgesTest.Scored(97, 0, SinkTest.counts());
        List<String> lines = printedBy(s.results, s.results.get(0));
        Map<String, List<KeyEdgesPrinted.Line>> printed = KeyEdgesPrinted.parse(lines);
        // Every entry's own play at each penetration, the null count's first entry alone, and
        // nothing from after the section.
        int entries = 0;
        for (SystemScorer.Result r : s.results) {
            for (SystemScorer.Entry e : r.entries) {
                entries += e.bettingOnly || (r == s.results.get(0) && e != r.entries.get(0)) ? 0 : 1;
            }
        }
        assertEquals(entries * SystemScorer.CUTS.length, printed.size());
        assertFalse(printed.containsKey("Not a per-key table|80%"));

        KeyEdgesPrinted.Comparison c = KeyEdgesPrinted.compare(printed, Collections.singletonList(s.col));
        assertEquals(printed.size(), c.tables);
        assertEquals(new ArrayList<String>(), c.differ);
        assertEquals(new ArrayList<String>(), c.notOurs);
        assertEquals(c.lines, c.same);
        assertTrue(c.lines > 50, "lines compared: " + c.lines);

        // One state's value moved by a unit: its key's line differs, and no other line does.
        SystemScorer.Entry hiLo = s.results.get(1).entries.get(0);
        Object[] f0 = s.col.handed.get(hiLo.name)[2][0];
        ((double[]) f0[2])[0] += 1;
        int movedKey = ((int[]) f0[4])[0];
        KeyEdgesPrinted.Comparison moved = KeyEdgesPrinted.compare(printed, Collections.singletonList(s.col));
        assertEquals(1, moved.differ.size(), moved.differ.toString());
        assertTrue(moved.differ.get(0).startsWith(hiLo.name + "|80%: printed " + movedKey + " | "), moved.differ.get(0));
        assertEquals(moved.lines - 1, moved.same);
    }

    @Test
    public void aTableWeDidNotScoreIsNamedAndANameMayHoldCommas() {
        List<String> lines = Arrays.asList("## Per key, from the test folds", "", "### A, B count, 75%", "",
                "| Key | Frequency | Mean RV | Fitted advantage |", "|---|---|---|---|", "| -1 | 0.2500 | -1.000 | -1.000 |",
                "| 0 | 0.7500 | +0.500 | +0.500 |", "", "## Elsewhere");
        Map<String, List<KeyEdgesPrinted.Line>> printed = KeyEdgesPrinted.parse(lines);
        assertEquals(Collections.singleton("A, B count|75%"), printed.keySet());
        assertEquals(Arrays.asList(new KeyEdgesPrinted.Line("-1", "0.2500", "-1.000"),
                new KeyEdgesPrinted.Line("0", "0.7500", "+0.500")), printed.get("A, B count|75%"));
        KeyEdgesTest.Scored s = new KeyEdgesTest.Scored(101, 0, Collections.singletonList(CountKeys.nullCount()));
        KeyEdgesPrinted.Comparison c = KeyEdgesPrinted.compare(printed, Collections.singletonList(s.col));
        assertEquals(Collections.singletonList("A, B count|75%"), c.notOurs);
        assertEquals(0, c.tables);
        assertEquals(0, c.lines);
    }
}
