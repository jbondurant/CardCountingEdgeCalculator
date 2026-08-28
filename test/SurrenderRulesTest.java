import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Surrender is a first action on the original two cards.
 *
 * You may forfeit half the bet before doing anything else, and that is the whole of it:
 * not after taking a card, not after doubling, and not on a hand that came out of a
 * split. Late surrender happens after the dealer peeks, so a dealer natural takes the
 * full bet and the option never arises; early surrender is offered before the peek, which
 * is the only thing that separates the two rules and is worth several times as much.
 *
 * The code offered it in five places and only one of them was a first action. It was also
 * missing from both move dispatchers, so choosing it fell through to the split branch.
 */
public class SurrenderRulesTest {

    private static ArrayList<Card> hand(Rank... ranks) {
        ArrayList<Card> h = new ArrayList<>();
        Suit[] suits = Suit.values();
        for (int i = 0; i < ranks.length; i++) {
            h.add(new Card(ranks[i], suits[i % suits.length]));
        }
        return h;
    }

    private static HouseRules rulesWithSurrender() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        hr.canLateSurrender = true;
        return hr;
    }

    private static Simulation simulationFor(HouseRules hr) {
        SimulationParameters sp = new SimulationParameters(
                hr, CountMethod.getHiLoValue(1), 1.0, 10, 10, -5, 5);
        return new Simulation(new SimulationTable(sp, "test"), "test");
    }

    /** Sit a player on a hand against a dealer up-card, with no cards yet dealt to either. */
    private static Simulation seated(HouseRules hr, Rank dealerUpCard, Rank... playerCards) {
        Simulation sim = simulationFor(hr);
        sim.table.dealer.revealedCards.add(new Card(dealerUpCard, Suit.SPADES));
        sim.table.randomishPlayer.playerHands.playerHand = new PlayerHand(hand(playerCards));
        return sim;
    }

    /**
     * Surrendering forfeits half the bet.
     *
     * Both dispatchers branch on Stand, Hit, Double and then fall through to a split, so
     * a chosen surrender used to be handled by the split code, which read the hand's
     * first rank and tried to split whatever it found.
     */
    @Test
    public void surrenderingCostsHalfTheBetOnTheTablePath() {
        HouseRules hr = rulesWithSurrender();
        Simulation sim = seated(hr, Rank.TEN, Rank.TEN, Rank.SIX);
        HashMap<PlayerDealerBestScore, Outcome> outcomeFinder =
                PlayerDealerBestScore.initializeOutcomeFinderForTable(hr);

        double payoff = sim.doPlayerMoveAndGetPayoff(
                PlayerMove.Surrender, sim.table.randomishPlayer.playerHands,
                outcomeFinder, new MetaDealer("test"));

        assertEquals(-0.5, payoff, 1e-9, "surrender forfeits half the bet");
    }

    /** And the same on the payoff path. */
    @Test
    public void surrenderingCostsHalfTheBetOnThePayoffPath() {
        HouseRules hr = rulesWithSurrender();
        Simulation sim = seated(hr, Rank.TEN, Rank.TEN, Rank.SIX);

        double payoff = sim.doPlayerMoveSmartAndGetPayoff(
                PlayerMove.Surrender, sim.table.randomishPlayer.playerHands);

        assertEquals(-0.5, payoff, 1e-9, "surrender forfeits half the bet");
    }

    /** Surrendering a pair must not be mistaken for splitting it. */
    @Test
    public void surrenderingAPairIsNotASplit() {
        HouseRules hr = rulesWithSurrender();
        Simulation sim = seated(hr, Rank.ACE, Rank.EIGHT, Rank.EIGHT);

        double payoff = sim.doPlayerMoveSmartAndGetPayoff(
                PlayerMove.Surrender, sim.table.randomishPlayer.playerHands);

        assertEquals(-0.5, payoff, 1e-9);
        assertNotNull(sim.table.randomishPlayer.playerHands.playerHand,
                "the hand should still be whole; splitting would have replaced it with children");
        assertEquals(1, sim.table.randomishPlayer.playerHands.getNumActualNodes(),
                "surrendering must not create a second hand");
    }

    /** The floor exists because surrender is worth -0.5 by rule, not by measurement. */
    @Test
    public void surrenderFloorsALookupAtHalfTheBet() {
        MoveChoices mc = new MoveChoices();
        ActionPayoff standPayoff = new ActionPayoff();
        standPayoff.insertEvent(-0.9);
        mc.actionPayoffs.put(PlayerMove.Stand, standPayoff);

        assertEquals(-0.5, mc.getPayoffOfActionWithBestPayoff(
                        EnumSet.of(PlayerMove.Stand, PlayerMove.Surrender)), 1e-9,
                "a -0.9 stand should be surrendered instead");
        assertEquals(-0.9, mc.getPayoffOfActionWithBestPayoff(
                        EnumSet.of(PlayerMove.Stand)), 1e-9,
                "without surrender the hand is worth what it is worth");
    }

    /**
     * That floor is why the legal sets matter.
     *
     * Hard 16 against a ten is worth about -0.54. Offering surrender where the rules do
     * not allow it would report -0.50 instead, an improvement conjured from a move that
     * cannot be made -- and the caller records it.
     */
    @Test
    public void anIllegalSurrenderWouldInflateAStiffHand() {
        MoveChoices mc = new MoveChoices();
        ActionPayoff standPayoff = new ActionPayoff();
        standPayoff.insertEvent(-0.5398);
        mc.actionPayoffs.put(PlayerMove.Stand, standPayoff);

        double honest = mc.getPayoffOfActionWithBestPayoff(EnumSet.of(PlayerMove.Stand));
        double withSurrender = mc.getPayoffOfActionWithBestPayoff(
                EnumSet.of(PlayerMove.Stand, PlayerMove.Surrender));

        assertEquals(-0.5398, honest, 1e-9);
        assertEquals(-0.5, withSurrender, 1e-9);
        assertTrue(withSurrender - honest > 0.03,
                "the difference is what an illegal surrender would invent");
    }

    /**
     * The payoff run reads a finished table, so a gap in it is an error rather than
     * something to paper over.
     *
     * This used to substitute Stand and carry on, which quietly plays a different
     * strategy than the one being measured while still reporting the hand's payoff as
     * though the table had chosen it.
     */
    @Test
    public void thePayoffRunWillNotSubstituteStandForAMissingCell() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        Simulation sim = seated(hr, Rank.SIX, Rank.FIVE, Rank.FIVE);   // hard 10, cannot bust

        assertThrows(UnsolvedCellException.class,
                () -> sim.doPlayerMoveSmartAndGetPayoff(
                        PlayerMove.Hit, sim.table.randomishPlayer.playerHands),
                "an empty table must not be papered over with Stand");
    }

    /** The configured ruleset has surrender off, which is why none of this was exercised. */
    @Test
    public void theMontrealRulesDoNotOfferSurrender() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        assertFalse(hr.canEarlySurrender);
        assertFalse(hr.canLateSurrender);
    }
}
