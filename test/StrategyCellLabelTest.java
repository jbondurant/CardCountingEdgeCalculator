import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every cell of a strategy table says what to do, and a table that is only partly built
 * still renders.
 *
 * A cell's label is a list of count intervals, each with the move to make across it. The
 * last interval was only ever closed from inside a loop over the second bucket onwards,
 * so a cell with exactly one bucket came out with no label at all. That is every cell of
 * a table run at a single count, which is how testTable1 was run, so all 360 move cells
 * of its three published tables went out blank.
 *
 * The table builders also looked each situation up and used what came back, so a
 * situation the run had not reached yet stopped the whole render with a
 * NullPointerException. The table fills in dependency order, hard 21 first, which makes a
 * partly built table the ordinary state of one that is still running. A cell with nothing
 * in it is rendered empty and marked unmeasured, the way a cell with nothing at count
 * zero already was.
 */
public class StrategyCellLabelTest {

    private static final Pattern CELL =
            Pattern.compile("<td class=\"(tg-[A-Za-z0-9]+)\">(.*?)</td>");
    private static final Pattern INTERVAL =
            Pattern.compile("\\[(-?\\d+\\.\\d\\d), (-?\\d+\\.\\d\\d)\\] do ([A-Za-z]+)");

    // ------------------------------------------------------------------- one cell

    @Test
    public void aCellWithOneCountBucketIsLabelledWithItsMove() {
        DecisionCell dc = new DecisionCell();
        record(dc, 0.0, PlayerMove.Stand, -0.2);

        assertEquals("[0.00, 0.00] do Stand", dc.createStringCell(),
                "a cell measured at one count has one interval, and it has to be written");
    }

    @Test
    public void aOneBucketCellWithACompoundMoveIsLabelledWithBothParts() {
        DecisionCell dc = new DecisionCell();
        record(dc, 0.0, PlayerMove.Double, 0.3);
        record(dc, 0.0, PlayerMove.Hit, 0.1);
        record(dc, 0.0, PlayerMove.Stand, -0.4);

        assertEquals("[0.00, 0.00] do DoubleHit", dc.createStringCell(),
                "doubling first and hitting second should read DoubleHit, as the colour does");
    }

    /** The intervals of a cell with several buckets are unchanged by the fix. */
    @Test
    public void aCellWithSeveralBucketsClosesEveryInterval() {
        DecisionCell changesAtTheEnd = new DecisionCell();
        record(changesAtTheEnd, 0.0, PlayerMove.Stand, -0.2);
        record(changesAtTheEnd, 1.0, PlayerMove.Stand, -0.1);
        record(changesAtTheEnd, 2.0, PlayerMove.Hit, 0.1);
        assertEquals("[0.00, 1.00] do Stand<br>[2.00, 2.00] do Hit",
                changesAtTheEnd.createStringCell(),
                "a move that changes at the last bucket should get an interval of its own");

        DecisionCell changesAtTheStart = new DecisionCell();
        record(changesAtTheStart, -1.0, PlayerMove.Stand, -0.2);
        record(changesAtTheStart, 0.0, PlayerMove.Hit, -0.1);
        record(changesAtTheStart, 1.0, PlayerMove.Hit, 0.1);
        assertEquals("[-1.00, -1.00] do Stand<br>[0.00, 1.00] do Hit",
                changesAtTheStart.createStringCell(),
                "the last interval should run to the last bucket");

        DecisionCell neverChanges = new DecisionCell();
        record(neverChanges, -1.0, PlayerMove.Hit, -0.2);
        record(neverChanges, 0.0, PlayerMove.Hit, -0.1);
        record(neverChanges, 1.0, PlayerMove.Hit, 0.1);
        assertEquals("[-1.00, 1.00] do Hit", neverChanges.createStringCell(),
                "one move across every bucket should be one interval");
    }

    @Test
    public void aCellWithNothingInItHasAnEmptyLabel() {
        String label = assertDoesNotThrow(() -> new DecisionCell().createStringCell(),
                "a cell with no buckets has nothing to say, which is not an error");
        assertEquals("", label);
    }

    // --------------------------------------------------------- a partly built table

    /**
     * The state a run is in shortly after it starts: hard 21 solved against every up
     * card, and nothing else reached.
     */
    @Test
    public void aPartlyBuiltTableRendersWithoutThrowing() throws Exception {
        SimulationTable table = emptyTable();
        HandEncoding hard21 = new HandEncoding(false, false, 21);
        for (int up = 2; up <= 11; up++) {
            table.insertEvent(new EventResult(
                    0.6, hard21, upCard(up), PlayerMove.Stand, new GranularCount(0.0)));
        }

        ArrayList<String> hard = assertDoesNotThrow(table::getHardCountTableStrings,
                "a table with situations still missing should render the ones it has");
        ArrayList<String> soft = assertDoesNotThrow(table::getSoftTableStrings,
                "a table with no soft hands yet should still render");
        ArrayList<String> split = assertDoesNotThrow(table::getSplitTableStrings,
                "a table with no pairs yet should still render");

        String unmeasured = "<td class=\"tg-unmeasured\"></td>";
        assertEquals(10, Collections.frequency(hard,
                        "<td class=\"tg-stand\">[0.00, 0.00] do Stand</td>"),
                "each of the ten hard 21 cells should render its measured move");
        assertEquals(16 * 10, Collections.frequency(hard, unmeasured),
                "the sixteen hard rows from 5 to 20 have not been reached, so every one of "
                        + "their cells should be empty and marked unmeasured");
        assertEquals(9 * 10, Collections.frequency(soft, unmeasured),
                "all nine soft rows should be marked unmeasured");
        assertEquals(10 * 10, Collections.frequency(split, unmeasured),
                "all ten pair rows should be marked unmeasured");
    }

    /** A situation whose cell exists but holds nothing, as an empty stored cell would. */
    @Test
    public void aSituationWithAnEmptyCellRendersAsUnmeasured() throws Exception {
        SimulationTable table = emptyTable();
        for (int total = 5; total <= 21; total++) {
            for (int up = 2; up <= 11; up++) {
                table.actionMap.put(
                        new HandSituation(new HandEncoding(false, false, total), up),
                        new DecisionCell());
            }
        }

        ArrayList<String> hard = assertDoesNotThrow(table::getHardCountTableStrings,
                "an empty cell should render, not throw");
        assertEquals(17 * 10, Collections.frequency(hard, "<td class=\"tg-unmeasured\"></td>"),
                "every empty cell should render empty and marked unmeasured");
    }

    // ------------------------------------------------------- the committed tables

    /**
     * Every cell the published tables colour by a move also says which move, at which
     * counts. A coloured cell with no text tells a reader nothing unless they already
     * know the colour key.
     */
    @Test
    public void everyMoveCellOfEveryCommittedTableHasALabel() throws IOException {
        int checked = 0;
        for (File table : committedTables()) {
            Matcher m = CELL.matcher(read(table));
            int blank = 0;
            while (m.find()) {
                if (m.group(1).equals("tg-0pky")) {
                    continue;
                }
                checked++;
                if (m.group(2).isEmpty()) {
                    blank++;
                }
            }
            assertEquals(0, blank, table.getName() + " has " + blank
                    + " move cells with a colour and no text");
        }
        assertTrue(checked > 0, "found no move cells to check");
    }

    /**
     * The colour of a cell is the move at a true count of zero, and so is the interval of
     * its label that covers zero. They are two renderings of one measurement, so in a
     * committed table they have to agree. The testTable1 labels were filled in on the
     * strength of this: a cell with one bucket, at zero, says to do what its colour says.
     */
    @Test
    public void theLabelOfEveryCommittedCellAgreesWithItsColour() throws IOException {
        for (File table : committedTables()) {
            Matcher m = CELL.matcher(read(table));
            while (m.find()) {
                String cssClass = m.group(1);
                String label = m.group(2);
                if (cssClass.equals("tg-0pky") || label.isEmpty()) {
                    continue;
                }
                String moveAtZero = null;
                for (String line : label.split("<br>")) {
                    Matcher interval = INTERVAL.matcher(line);
                    assertTrue(interval.matches(),
                            table.getName() + " has a label line that is not an interval: " + line);
                    double from = Double.parseDouble(interval.group(1));
                    double to = Double.parseDouble(interval.group(2));
                    if (from <= 0.0 && 0.0 <= to) {
                        moveAtZero = interval.group(3);
                    }
                }
                assertNotNull(moveAtZero, table.getName() + " has a " + cssClass
                        + " cell whose label says nothing about a count of zero: " + label);
                assertEquals(cssClass, "tg-" + moveAtZero.toLowerCase(Locale.ROOT),
                        table.getName() + " has a cell coloured " + cssClass
                                + " whose label says to " + moveAtZero + " at a count of zero");
            }
        }
    }

    // --------------------------------------------------------------------- helpers

    private static void record(DecisionCell dc, double count, PlayerMove move, double payoff) {
        dc.insertEvent(new EventResult(payoff, new HandEncoding(false, false, 16), Rank.TEN,
                move, new GranularCount(count)));
    }

    private static SimulationTable emptyTable() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        SimulationParameters sp = new SimulationParameters(
                hr, CountMethod.getHiLoValue(1), 1.0, 10, 10, 0, 0);
        return new SimulationTable(sp, "test");
    }

    private static Rank upCard(int points) {
        if (points == 11) {
            return Rank.ACE;
        }
        for (Rank r : Rank.values()) {
            if (r != Rank.ACE && r.getRankpoints() == points) {
                return r;
            }
        }
        throw new IllegalArgumentException("no rank worth " + points);
    }

    private static File[] committedTables() {
        File[] tables = new File("stuffForHTML").listFiles((d, name) -> name.endsWith(".html"));
        assertNotNull(tables, "no stuffForHTML directory to check");
        assertTrue(tables.length > 0, "no generated tables to check");
        Arrays.sort(tables);
        return tables;
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
