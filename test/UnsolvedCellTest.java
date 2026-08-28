import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Asking a cell for a payoff it never measured is an error, not a zero.
 *
 * Whatever these lookups return becomes the payoff of the hand being evaluated, and
 * insertEvent records it like any other observation. A neutral number would therefore be a
 * made-up data point inside a real average, which is the one failure a program whose whole
 * output is an average cannot absorb. The hand ordering exists to make this impossible, so
 * if it happens the run is building on a table that does not support it.
 */
public class UnsolvedCellTest {

    private static SimulationTable emptyTable() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        SimulationParameters sp = new SimulationParameters(
                hr, CountMethod.getHiLoValue(1), 1.0, 10, 10, -5, 5);
        return new SimulationTable(sp, "test");
    }

    /** A count bucket that holds nothing. */
    @Test
    public void readingAnUnfilledCellIsAnError() {
        DecisionCell dc = new DecisionCell();
        UnsolvedCellException e = assertThrows(UnsolvedCellException.class,
                () -> dc.getBestPlayerMovePayoff(
                        new GranularCount(0.0), EnumSet.of(PlayerMove.Hit, PlayerMove.Stand)));
        assertTrue(e.getMessage().contains("0.00"),
                "the message should name the count that was missing: " + e.getMessage());
    }

    /** A situation the table has never seen. */
    @Test
    public void readingAnUnknownSituationIsAnError() {
        HandSituation unseen = new HandSituation(new HandEncoding(false, false, 16), 10);
        UnsolvedCellException e = assertThrows(UnsolvedCellException.class,
                () -> emptyTable().getBestPlayerMovePayoff(
                        unseen, new GranularCount(0.0), EnumSet.of(PlayerMove.Hit)));
        assertTrue(e.getMessage().contains("16"),
                "the message should name the hand: " + e.getMessage());
    }

    /** A bucket that holds data, but none of it for a move that is currently legal. */
    @Test
    public void askingForAPayoffWithNothingMeasuredIsAnError() {
        MoveChoices mc = new MoveChoices();
        ActionPayoff hitPayoff = new ActionPayoff();
        hitPayoff.insertEvent(-0.2);
        mc.actionPayoffs.put(PlayerMove.Hit, hitPayoff);

        UnsolvedCellException e = assertThrows(UnsolvedCellException.class,
                () -> mc.getPayoffOfActionWithBestPayoff(EnumSet.of(PlayerMove.Stand)));
        assertTrue(e.getMessage().contains("Hit"),
                "the message should say what the bucket does hold: " + e.getMessage());
    }

    /** A cell that has been solved answers normally. */
    @Test
    public void readingASolvedCellReturnsItsMeasuredPayoff() {
        SimulationTable table = emptyTable();
        HandEncoding hard16 = new HandEncoding(false, false, 16);
        table.insertEvent(new EventResult(
                -0.4, hard16, Rank.TEN, PlayerMove.Stand, new GranularCount(0.0)));
        table.insertEvent(new EventResult(
                -0.4, hard16, Rank.TEN, PlayerMove.Stand, new GranularCount(0.0)));

        HandSituation hs = new HandSituation(hard16, Rank.TEN.getRankpoints());
        assertEquals(-0.4, table.getBestPlayerMovePayoff(
                hs, new GranularCount(0.0), EnumSet.of(PlayerMove.Stand)), 1e-9);
    }

    /**
     * Nothing invented can reach a running average.
     *
     * The old sentinel turned one such lookup into -21,262,214 for a cell sitting at -0.1
     * over a hundred events. The lookup fails now, so the average is untouched.
     */
    @Test
    public void nothingInventedCanReachARunningAverage() {
        MoveChoices source = new MoveChoices();
        ActionPayoff hitPayoff = new ActionPayoff();
        hitPayoff.insertEvent(-0.2);
        source.actionPayoffs.put(PlayerMove.Hit, hitPayoff);

        ActionPayoff cell = new ActionPayoff();
        for (int i = 0; i < 100; i++) {
            cell.insertEvent(-0.1);
        }

        assertThrows(UnsolvedCellException.class,
                () -> cell.insertEvent(
                        source.getPayoffOfActionWithBestPayoff(EnumSet.of(PlayerMove.Stand))));

        assertEquals(100, cell.numTimes, "no observation should have been recorded");
        assertEquals(-0.1, cell.avPayoff, 1e-9, "the average should be untouched");
    }

    /** Surrender is fixed by the rules, so an empty bucket can still price it. */
    @Test
    public void surrenderIsAnswerableWithNothingMeasured() {
        MoveChoices mc = new MoveChoices();
        assertEquals(-0.5, mc.getPayoffOfActionWithBestPayoff(
                        EnumSet.of(PlayerMove.Hit, PlayerMove.Surrender)), 1e-9,
                "surrendering forfeits half the bet by rule, measured or not");
    }
}
