import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Choosing a move from measured payoffs, and recording what happened afterwards.
 *
 * Both halves feed the same running averages, so a bad answer here does not just
 * mislead one hand; it is written back into the table.
 */
public class MoveSelectionTest {

    /**
     * Asking for a payoff when nothing legal has been measured is an error.
     *
     * The search used to be seeded with Integer.MIN_VALUE and return it as though it were
     * an expected value. Returning 0.0 instead would be smaller but equally invented, and
     * the caller records whatever comes back.
     */
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

    /**
     * Nothing invented can reach a running average.
     *
     * The old sentinel turned one such lookup into -21,262,214 for a cell sitting at -0.1
     * over a hundred events. Now the lookup fails instead, so the average is untouched.
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

    /**
     * Surrender is fixed by the rules rather than measured, so an empty bucket can still
     * answer a question that allows it.
     */
    @Test
    public void surrenderIsAnswerableWithNothingMeasured() {
        MoveChoices mc = new MoveChoices();
        double best = mc.getPayoffOfActionWithBestPayoff(
                EnumSet.of(PlayerMove.Hit, PlayerMove.Surrender));
        assertEquals(-0.5, best, 1e-9, "surrendering forfeits half the bet by rule");
    }

    /**
     * Choosing a move is not the same as reporting a payoff, and null here is routine.
     *
     * A bucket that exists is never empty: DecisionCell.insertEvent puts a move in as it
     * creates one. What happens instead is that the caller narrows the legal set on
     * purpose. runSingleEvent asks for the best move, removes it, and asks again to get
     * the second best. A bucket that has only ever seen Stand -- the ordinary early
     * state, since Stand is played about 80% of the time while a cell is empty -- has no
     * answer to that second question, and the caller falls back to Stand.
     *
     * So this lookup is routinely asked something it cannot answer, by design. Only the
     * payoff lookup, whose result is recorded as an observation, refuses to invent one.
     */
    @Test
    public void thereIsNoSecondBestMoveWhenOnlyOneMoveHasBeenMeasured() {
        MoveChoices mc = new MoveChoices();
        ActionPayoff standPayoff = new ActionPayoff();
        standPayoff.insertEvent(-0.3);
        mc.actionPayoffs.put(PlayerMove.Stand, standPayoff);

        EnumSet<PlayerMove> legalMoves = EnumSet.of(PlayerMove.Stand, PlayerMove.Hit);
        PlayerMove best = mc.getActionWithBestPayoff(legalMoves);
        assertEquals(PlayerMove.Stand, best);

        legalMoves.remove(best);
        assertNull(mc.getActionWithBestPayoff(legalMoves),
                "nothing else has been measured, so there is no second best");
    }

    /**
     * The old default made that answer an illegal move.
     *
     * getActionWithBestPayoff started at Stand and returned it when the loop found
     * nothing, so the second-best lookup reported Stand even after Stand had just been
     * removed from the legal set.
     */
    @Test
    public void theSecondBestMoveIsNeverOneThatWasRuledOut() {
        MoveChoices mc = new MoveChoices();
        ActionPayoff standPayoff = new ActionPayoff();
        standPayoff.insertEvent(-0.3);
        mc.actionPayoffs.put(PlayerMove.Stand, standPayoff);

        PlayerMove chosen = mc.getActionWithBestPayoff(EnumSet.of(PlayerMove.Hit));
        assertNotEquals(PlayerMove.Stand, chosen, "Stand was not among the legal moves");
        assertNull(chosen);
    }

    /**
     * The rendered cell label uses the same remove-the-winner idiom.
     *
     * A bucket holding only Double used to render as "DoubleStand", asserting a Stand
     * fallback that had never been measured. With no second move measured it now says
     * only what it knows.
     */
    @Test
    public void aCellLabelDoesNotInventAFallbackMove() {
        MoveChoices mc = new MoveChoices();
        ActionPayoff doublePayoff = new ActionPayoff();
        doublePayoff.insertEvent(0.3);
        mc.actionPayoffs.put(PlayerMove.Double, doublePayoff);

        assertEquals("Double", mc.getCompoundBestMove(),
                "only Double has been measured, so there is no fallback to name");
    }

    /** When a fallback has been measured, the label names both. */
    @Test
    public void aCellLabelNamesTheFallbackWhenThereIsOne() {
        MoveChoices mc = new MoveChoices();
        ActionPayoff doublePayoff = new ActionPayoff();
        doublePayoff.insertEvent(0.3);
        ActionPayoff hitPayoff = new ActionPayoff();
        hitPayoff.insertEvent(0.1);
        mc.actionPayoffs.put(PlayerMove.Double, doublePayoff);
        mc.actionPayoffs.put(PlayerMove.Hit, hitPayoff);

        assertEquals("DoubleHit", mc.getCompoundBestMove());
    }

    /**
     * The move-picking twin never claims an illegal move.
     *
     * It used to default to Stand, so an unmeasured cell reported "stand" even when
     * standing was not among the legal moves. It now reports null, meaning "nothing
     * measured here", which callers can act on.
     */
    @Test
    public void bestMoveWithNoLegalMoveIsNeverIllegal() {
        MoveChoices mc = new MoveChoices();
        ActionPayoff splitPayoff = new ActionPayoff();
        splitPayoff.insertEvent(0.1);
        mc.actionPayoffs.put(PlayerMove.Split, splitPayoff);

        EnumSet<PlayerMove> legal = EnumSet.of(PlayerMove.Hit);
        PlayerMove chosen = mc.getActionWithBestPayoff(legal);

        assertNull(chosen, "no legal move was measured, so there is no move to report");
    }

    /** When a legal move has been measured, that move is the one reported. */
    @Test
    public void bestMoveAmongMeasuredLegalMovesIsChosen() {
        MoveChoices mc = new MoveChoices();
        ActionPayoff hitPayoff = new ActionPayoff();
        hitPayoff.insertEvent(-0.4);
        ActionPayoff standPayoff = new ActionPayoff();
        standPayoff.insertEvent(-0.1);
        mc.actionPayoffs.put(PlayerMove.Hit, hitPayoff);
        mc.actionPayoffs.put(PlayerMove.Stand, standPayoff);

        PlayerMove chosen =
                mc.getActionWithBestPayoff(EnumSet.of(PlayerMove.Hit, PlayerMove.Stand));

        assertEquals(PlayerMove.Stand, chosen, "-0.1 beats -0.4");
    }

    /** Surrender forfeits half the bet, so it floors the loss at -0.5. */
    @Test
    public void surrenderFloorsTheLossAtHalfTheBet() {
        MoveChoices mc = new MoveChoices();
        ActionPayoff hitPayoff = new ActionPayoff();
        hitPayoff.insertEvent(-0.9);
        mc.actionPayoffs.put(PlayerMove.Hit, hitPayoff);

        double best = mc.getPayoffOfActionWithBestPayoff(
                EnumSet.of(PlayerMove.Hit, PlayerMove.Surrender));

        assertEquals(-0.5, best, 1e-9, "surrender is better than a -0.9 hit");
    }

    /**
     * A bucket with nothing in it has no label, and says so.
     *
     * The set getCompoundBestMove searches is built from the moves the bucket has
     * measured, not from the rules, so it is empty exactly when the bucket is. That
     * cannot happen from a run -- DecisionCell.insertEvent puts a move in as it creates a
     * bucket -- so reaching it means something built a MoveChoices and recorded nothing.
     */
    @Test
    public void anEmptyBucketHasNoLabel() {
        MoveChoices mc = new MoveChoices();
        assertThrows(UnsolvedCellException.class, mc::getCompoundBestMove);
    }
}
