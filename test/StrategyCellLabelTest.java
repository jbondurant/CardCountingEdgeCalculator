import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;

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
}
