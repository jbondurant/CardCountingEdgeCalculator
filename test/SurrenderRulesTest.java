import com.mongodb.BasicDBObject;
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
        assertFalse(hr.canSurrenderAfterSplit);
        assertFalse(hr.offersSurrender());
        assertFalse(hr.offersSurrenderAfterSplit());
    }

    // ------------------------------------------------- surrendering after a split

    /**
     * Surrendering after a split is a narrowing of surrender, so it cannot be on when
     * surrender itself is off. A house that does not offer the move at all does not offer
     * it on a split hand either, whatever the second flag happens to say.
     */
    @Test
    public void surrenderAfterSplitCannotOutliveSurrenderItself() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        hr.canSurrenderAfterSplit = true;

        assertFalse(hr.offersSurrenderAfterSplit(),
                "surrender is off, so there is nothing to carry into a split hand");

        hr.canLateSurrender = true;
        assertTrue(hr.offersSurrenderAfterSplit(),
                "with surrender offered and the split rule on, a split hand may take it");
    }

    /** And offering surrender does not on its own carry it past a split. */
    @Test
    public void offeringSurrenderDoesNotImplyOfferingItAfterASplit() {
        HouseRules hr = rulesWithSurrender();

        assertTrue(hr.offersSurrender());
        assertFalse(hr.offersSurrenderAfterSplit(),
                "Montreal withdraws surrender once the hand is split");
    }

    /**
     * A hand situation is keyed on the total, the up-card and whether the hand is a pair,
     * but not on how it was reached. So the bucket for a hard 14 is shared between a 10,4
     * dealt straight up and an eight that was split and drew a six. The first of those may
     * surrender and the second may not, and the two are indistinguishable once they are in
     * the table, so a surrender measured there must not be inherited.
     */
    @Test
    public void aSplitHandDoesNotInheritASurrenderMeasuredForTheSameTotal() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        Simulation sim = seatedAfterSplit(hr);
        measureHardFourteenVsTen(sim, -0.9);

        assertEquals(-0.9, sim.playBestNotSplit(sim.table.randomishPlayer.playerHands), 1e-9,
                "the split hand has to stand for -0.9 rather than take a surrender it is"
                        + " not allowed, even though -0.5 is in the bucket");
    }

    /** With the rule on, the same hand may take it, and it is priced at half the bet. */
    @Test
    public void aSplitHandMaySurrenderWhereTheHouseAllowsIt() {
        HouseRules hr = rulesWithSurrender();
        hr.canSurrenderAfterSplit = true;
        Simulation sim = seatedAfterSplit(hr);
        measureHardFourteenVsTen(sim, -0.9);

        assertEquals(-0.5, sim.playBestNotSplit(sim.table.randomishPlayer.playerHands), 1e-9,
                "standing is worth -0.9, so a split hand that may surrender should");
    }

    /**
     * The payoff run builds its legal moves from the rules rather than from what was
     * measured, so it needs the same gate. Standing on a hard 14 settles at a whole bet
     * won, lost or pushed, so it can never be mistaken for a surrender.
     */
    @Test
    public void thePayoffRunAlsoRefusesSurrenderOnASplitHand() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        Simulation sim = seatedAfterSplit(hr);
        measureHardFourteenVsTen(sim, -0.9);

        assertNotEquals(-0.5,
                sim.playBestSmartNotSplit(sim.table.randomishPlayer.playerHands),
                "the payoff run must play the split hand out, not surrender it");
    }

    /** And takes it where the house allows it. */
    @Test
    public void thePayoffRunSurrendersASplitHandWhereTheHouseAllowsIt() {
        HouseRules hr = rulesWithSurrender();
        hr.canSurrenderAfterSplit = true;
        Simulation sim = seatedAfterSplit(hr);
        measureHardFourteenVsTen(sim, -0.9);

        assertEquals(-0.5,
                sim.playBestSmartNotSplit(sim.table.randomishPlayer.playerHands), 1e-9,
                "every measured move is worse than half the bet, so it should surrender");
    }

    // --------------------------------------------------------------- storing the rule

    /** The rule has to survive being written out and read back. */
    @Test
    public void theRuleSurvivesTheRoundTrip() {
        HouseRules hr = rulesWithSurrender();
        hr.canSurrenderAfterSplit = true;

        HouseRules back = HouseRules.getHouseRulesFromObject(hr.getDBOject());
        assertTrue(back.canSurrenderAfterSplit);
        assertTrue(back.offersSurrenderAfterSplit());
    }

    /**
     * A ruleset stored before this field existed has to keep loading. It was written by
     * code that could not surrender after a split, so false is the true reading of it and
     * not merely a safe one.
     */
    @Test
    public void aRulesetStoredBeforeTheRuleExistedReadsAsNotAllowing() {
        HouseRules hr = rulesWithSurrender();
        hr.canSurrenderAfterSplit = true;

        BasicDBObject stored = hr.getDBOject();
        stored.remove("canSurrenderAfterSplit");

        HouseRules back = assertDoesNotThrow(() -> HouseRules.getHouseRulesFromObject(stored),
                "an older stored ruleset must still load");
        assertFalse(back.canSurrenderAfterSplit);
    }

    // ------------------------------------------------------------------- the fixtures

    /**
     * A player sitting on a hard 14 that came out of a split, against a dealer ten: an
     * eight that was split off a pair and then drew a six. Not a pair itself, so it is
     * indistinguishable in the table from a 10,4 dealt straight up.
     */
    private static Simulation seatedAfterSplit(HouseRules hr) {
        return seated(hr, Rank.TEN, Rank.EIGHT, Rank.SIX);
    }

    /** Fill the hard 14 against a ten bucket with a stand and a surrender at count zero. */
    private static void measureHardFourteenVsTen(Simulation sim, double standPayoff) {
        HandEncoding fourteen = new HandEncoding(false, false, 14);
        sim.simulationTable.insertEvent(new EventResult(
                standPayoff, fourteen, Rank.TEN, PlayerMove.Stand, new GranularCount(0.0)));
        sim.simulationTable.insertEvent(new EventResult(
                -0.5, fourteen, Rank.TEN, PlayerMove.Surrender, new GranularCount(0.0)));
    }
}
