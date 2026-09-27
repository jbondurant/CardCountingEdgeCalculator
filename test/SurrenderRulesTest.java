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
 * split unless the house says so, which is a rule of its own. Late surrender happens after
 * the dealer peeks, so a dealer natural takes the full bet and the option never arises;
 * early surrender is offered before the peek, which is the only thing that separates the
 * two rules and is worth several times as much.
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
                "a house that offers surrender usually withdraws it once the hand is split");
    }

    /**
     * A hand situation is keyed on the total, the up-card and whether the hand is a pair,
     * but not on how it was reached. So the cell for a hard 14 against a ten is shared
     * between a 10,4 dealt straight up and an eight that was split and drew a six. Under
     * late surrender the first may surrender, so the cell holds one; where the house
     * withdraws surrender after a split, the second may not, and must not inherit it.
     *
     * Each 14 is worth standing, -0.54, which the table run prices from the dealer. It
     * used to be worth that because surrender was removed from every split hand whatever
     * the rules said; now it is because this house does not allow it.
     */
    @Test
    public void theTableRunDoesNotLetASplitHandInheritASurrenderFromItsCell() {
        HouseRules hr = rulesWithSurrender();
        Simulation sim = eightsSplitAgainstATen(hr);
        measureHardFourteen(sim, Rank.TEN, STAND_ON_14_AGAINST_A_TEN);

        assertEquals(ZERO, trueCountNow(sim),
                "the two split cards were meant to leave the count at zero");
        assertEquals(2 * STAND_ON_14_AGAINST_A_TEN, splitInTheTableRun(sim), 1e-9,
                "each split 14 has to stand for " + STAND_ON_14_AGAINST_A_TEN + " rather than"
                        + " take a surrender it is not allowed, though -0.5 is in its cell");
    }

    /**
     * The payoff run builds a split hand's moves from the rules rather than from its
     * cell, and where the rule is off it plays what it did before: the table's best
     * measured move, here Stand. Both 14s stand and lose to the dealer's 17, for -2.0.
     * Surrendering them would have lost 1.0.
     */
    @Test
    public void thePayoffRunDoesNotSurrenderASplitHandWhereTheHouseForbidsIt() {
        HouseRules hr = rulesWithSurrender();
        Simulation sim = eightsSplitAgainstATen(hr);
        measureHardFourteen(sim, Rank.TEN, STAND_ON_14_AGAINST_A_TEN);
        sim.table.dealer.hiddenCard.add(new Card(Rank.SEVEN, Suit.HEARTS));

        assertEquals(-2.0, splitInThePayoffRun(sim), 1e-9,
                "both 14s should stand and lose to 17, not surrender");
    }

    /** With the rule on, the same split 14 is priced at surrendering: -0.5 beats -0.54. */
    @Test
    public void theTableRunPricesASplitHandAtSurrenderWhereTheHouseAllowsIt() {
        HouseRules hr = rulesWithSurrender();
        hr.canSurrenderAfterSplit = true;
        Simulation sim = eightsSplitAgainstATen(hr);
        measureHardFourteen(sim, Rank.TEN, STAND_ON_14_AGAINST_A_TEN);

        assertEquals(2 * -0.5, splitInTheTableRun(sim), 1e-9,
                "standing is worth " + STAND_ON_14_AGAINST_A_TEN + " and hitting less, so a"
                        + " split hand that may surrender is worth -0.5");
    }

    /**
     * And the payoff run plays what the table run priced. The move it would otherwise
     * play is Stand, which the table run values from the dealer at -0.54, so it
     * surrenders both hands rather than standing them into the dealer's 17.
     */
    @Test
    public void thePayoffRunSurrendersASplitHandWhereTheHouseAllowsIt() {
        HouseRules hr = rulesWithSurrender();
        hr.canSurrenderAfterSplit = true;
        Simulation sim = eightsSplitAgainstATen(hr);
        measureHardFourteen(sim, Rank.TEN, STAND_ON_14_AGAINST_A_TEN);
        sim.table.dealer.hiddenCard.add(new Card(Rank.SEVEN, Suit.HEARTS));
        giveThePayoffRunItsDealer(sim);

        assertEquals(2 * -0.5, splitInThePayoffRun(sim), 1e-9,
                "each 14 should surrender for -0.5 rather than stand for -1.0");
    }

    /**
     * A split hand whose stand is worth more than -0.5 keeps standing, in both runs. The
     * 14s here are against a 6, where standing is worth -0.16. The dealer turns a ten under
     * the 6 and draws another, so in the payoff run both hands win.
     */
    @Test
    public void aSplitHandWhoseStandBeatsHalfTheBetDoesNotSurrender() {
        HouseRules hr = rulesWithSurrender();
        hr.canSurrenderAfterSplit = true;

        Simulation priced = seated(hr, Rank.SIX, Rank.EIGHT, Rank.EIGHT);
        stackTheShoe(priced, Rank.SIX, Rank.SIX);
        measureHardFourteen(priced, Rank.SIX, STAND_ON_14_AGAINST_A_SIX);
        assertEquals(2 * STAND_ON_14_AGAINST_A_SIX, splitInTheTableRun(priced), 1e-9,
                "standing on 14 against a 6 is worth " + STAND_ON_14_AGAINST_A_SIX
                        + ", better than surrendering");

        Simulation played = seated(hr, Rank.SIX, Rank.EIGHT, Rank.EIGHT);
        stackTheShoe(played, Rank.SIX, Rank.SIX, Rank.TEN);
        measureHardFourteen(played, Rank.SIX, STAND_ON_14_AGAINST_A_SIX);
        played.table.dealer.hiddenCard.add(new Card(Rank.TEN, Suit.HEARTS));
        giveThePayoffRunItsDealer(played);
        assertEquals(2.0, splitInThePayoffRun(played), 1e-9,
                "both 14s should stand, and the dealer busts on 26");
    }

    /**
     * The split hand whose choice cannot be read from its cell. Aces split once under
     * these rules and a split ace may not hit or double, so a split ace that draws another
     * ace is A,A with only Stand left, and Surrender where the house allows it. Its cell
     * is the A,A pair cell, which leans towards splitting and here has never measured
     * Stand. So the choice is made from the dealer, as the table run prices it: standing
     * on 12 against a ten is worth -0.54, and both hands surrender.
     */
    @Test
    public void aRePairedSplitAceSurrendersWhereStandingOnTwelveIsWorseThanHalfTheBet() {
        HouseRules hr = rulesWithSurrender();
        hr.canSurrenderAfterSplit = true;
        Simulation sim = acesSplitIntoAcesAgainst(hr, Rank.TEN);
        sim.table.dealer.hiddenCard.add(new Card(Rank.SEVEN, Suit.HEARTS));
        giveThePayoffRunItsDealer(sim);

        assertEquals(ZERO, trueCountNow(sim),
                "the two split cards were meant to leave the count at zero");
        assertEquals(2 * -0.5, splitInThePayoffRun(sim), 1e-9,
                "standing on 12 against a ten is worth " + STAND_ON_12_AGAINST_A_TEN
                        + ", so each A,A should surrender");
    }

    /** And the table run prices those two hands the same way. */
    @Test
    public void theTableRunPricesARePairedSplitAceAtTheBetterOfStandingAndSurrendering() {
        HouseRules hr = rulesWithSurrender();
        hr.canSurrenderAfterSplit = true;

        assertEquals(2 * -0.5, splitInTheTableRun(acesSplitIntoAcesAgainst(hr, Rank.TEN)),
                1e-9, "against a ten, surrendering beats standing on 12");
        assertEquals(2 * STAND_ON_12_AGAINST_A_SIX,
                splitInTheTableRun(acesSplitIntoAcesAgainst(hr, Rank.SIX)), 1e-9,
                "against a 6, standing on 12 beats surrendering");
    }

    /**
     * Against a 6 standing on 12 is worth -0.16, so the same two hands stand. The dealer
     * turns a ten and draws another, and both win.
     */
    @Test
    public void aRePairedSplitAceStandsWhereStandingOnTwelveBeatsHalfTheBet() {
        HouseRules hr = rulesWithSurrender();
        hr.canSurrenderAfterSplit = true;
        Simulation sim = acesSplitIntoAcesAgainst(hr, Rank.SIX, Rank.TEN);
        sim.table.dealer.hiddenCard.add(new Card(Rank.TEN, Suit.HEARTS));
        giveThePayoffRunItsDealer(sim);

        assertEquals(2.0, splitInThePayoffRun(sim), 1e-9,
                "standing on 12 against a 6 is worth " + STAND_ON_12_AGAINST_A_SIX
                        + ", so each A,A should stand, and the dealer busts on 26");
    }

    /**
     * Where a split hand may surrender, the payoff run weighs it against a stand priced
     * from the dealer the table was built from. runPayoffFinderSim loads that dealer; a
     * payoff run without one cannot price the stand and must say so rather than guess.
     */
    @Test
    public void thePayoffRunWillNotWeighASurrenderWithoutTheTablesDealer() {
        HouseRules hr = rulesWithSurrender();
        hr.canSurrenderAfterSplit = true;
        Simulation sim = eightsSplitAgainstATen(hr);
        measureHardFourteen(sim, Rank.TEN, STAND_ON_14_AGAINST_A_TEN);
        sim.table.dealer.hiddenCard.add(new Card(Rank.SEVEN, Suit.HEARTS));

        assertThrows(IllegalStateException.class, () -> splitInThePayoffRun(sim),
                "without the dealer there is nothing to weigh the surrender against");
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

    /**
     * For the same reason, rules without it keep the key their tables were stored under.
     * A Montreal table saved before the field existed means exactly what one saved now
     * means, so adding the field must not make it look built under other rules and refuse
     * to resume it. The key the older code wrote is the same document without the field.
     */
    @Test
    public void rulesWithoutItKeepTheKeyTheyHadBeforeItExisted() {
        SimulationParameters sp = new SimulationParameters(
                HouseRules.getMtlCasino25MinBlackjackParams(75), CountMethod.getHiLoValue(1),
                1.0, 50000, 100000, -5, 5);
        BasicDBObject meaning = sp.getDBObject();
        meaning.removeField("minHitsPerDecisionCellCount");
        meaning.removeField("minMetaDealer");
        ((BasicDBObject) meaning.get("houseRules")).removeField("canSurrenderAfterSplit");

        assertEquals(StoredState.keyOf(meaning), sp.getSemanticsKey(),
                "the Montreal key changed, so every stored Montreal table would be refused");
    }

    // ------------------------------------------------------------------- the fixtures

    private static final GranularCount ZERO = new GranularCount(0.0);

    /**
     * The dealer at a true count of zero, as the table run's MetaDealer records it. Against
     * a ten it ends on 17 twelve times in a hundred, 18 twelve, 19 twelve, 20 thirty-four,
     * 21 seven, and busts the other twenty-three. Against a 6 it is the distribution
     * SplitChildPricingTest uses: 14, 13, 13, 12, 6, and 42 busts.
     */
    private static MetaDealer dealerAtCountZero() {
        MetaDealer md = new MetaDealer("test");
        MetaDealerResult ten = new MetaDealerResult();
        ten.num17 = 12;
        ten.num18 = 12;
        ten.num19 = 12;
        ten.num20 = 34;
        ten.num21 = 7;
        ten.numBust = 23;
        md.dealerCountAndUpCardToResults.put(
                new GranularCountAndDealerUpCard(ZERO, Rank.TEN.getRankpoints()), ten);
        MetaDealerResult six = new MetaDealerResult();
        six.num17 = 14;
        six.num18 = 13;
        six.num19 = 13;
        six.num20 = 12;
        six.num21 = 6;
        six.numBust = 42;
        md.dealerCountAndUpCardToResults.put(
                new GranularCountAndDealerUpCard(ZERO, Rank.SIX.getRankpoints()), six);
        return md;
    }

    /** A 12 or a 14 wins only when the dealer busts. */
    private static final double STAND_ON_14_AGAINST_A_TEN = (23.0 - 77.0) / 100.0;
    private static final double STAND_ON_12_AGAINST_A_TEN = STAND_ON_14_AGAINST_A_TEN;
    private static final double STAND_ON_14_AGAINST_A_SIX = (42.0 - 58.0) / 100.0;
    private static final double STAND_ON_12_AGAINST_A_SIX = STAND_ON_14_AGAINST_A_SIX;

    /** Put these cards on top of the shoe, first one dealt first. */
    private static void stackTheShoe(Simulation sim, Rank... top) {
        ArrayList<Card> cards = hand(top);
        for (int i = cards.size() - 1; i >= 0; i--) {
            sim.table.gameDeck.cards.add(0, cards.get(i));
        }
    }

    private static GranularCount trueCountNow(Simulation sim) {
        HouseRules hr = sim.simulationTable.simulationParameters.houseRules;
        return sim.table.getGranularCount(
                sim.table.gameDeck.startingSize / hr.numDecks, 1.0, -5, 5);
    }

    /** A pair of eights against a ten, about to be split, each eight drawing a six. */
    private static Simulation eightsSplitAgainstATen(HouseRules hr) {
        Simulation sim = seated(hr, Rank.TEN, Rank.EIGHT, Rank.EIGHT);
        stackTheShoe(sim, Rank.SIX, Rank.SIX);
        return sim;
    }

    /**
     * Fill the hard 14 cell at count zero as the table run would: a stand priced from the
     * dealer, a hit that does worse, and the surrender a 10,4 dealt straight up records.
     */
    private static void measureHardFourteen(Simulation sim, Rank up, double standPayoff) {
        HandEncoding fourteen = new HandEncoding(false, false, 14);
        SimulationTable t = sim.simulationTable;
        t.insertEvent(new EventResult(standPayoff, fourteen, up, PlayerMove.Stand, ZERO));
        t.insertEvent(new EventResult(standPayoff - 0.04, fourteen, up, PlayerMove.Hit, ZERO));
        t.insertEvent(new EventResult(-0.5, fourteen, up, PlayerMove.Surrender, ZERO));
    }

    /**
     * A pair of aces against this up-card, about to be split, each ace drawing another ace,
     * with the dealer's own draws, if any, next in the shoe. The A,A cell has measured
     * splitting and hitting at count zero, but never standing.
     */
    private static Simulation acesSplitIntoAcesAgainst(HouseRules hr, Rank up,
                                                        Rank... dealerDraws) {
        Simulation sim = seated(hr, up, Rank.ACE, Rank.ACE);
        stackTheShoe(sim, dealerDraws);
        stackTheShoe(sim, Rank.ACE, Rank.ACE);
        HandEncoding aces = new HandEncoding(true, true, 2);
        sim.simulationTable.insertEvent(new EventResult(0.3, aces, up, PlayerMove.Split, ZERO));
        sim.simulationTable.insertEvent(new EventResult(0.1, aces, up, PlayerMove.Hit, ZERO));
        return sim;
    }

    /** What runPayoffFinderSim hands the payoff run: the table's dealer, and its prices. */
    private static void giveThePayoffRunItsDealer(Simulation sim) {
        sim.payoffRunDealer = dealerAtCountZero();
        sim.payoffRunOutcomeFinder = PlayerDealerBestScore.initializeOutcomeFinderForTable(
                sim.simulationTable.simulationParameters.houseRules);
    }

    private static double splitInTheTableRun(Simulation sim) {
        return sim.doPlayerMoveAndGetPayoff(PlayerMove.Split,
                sim.table.randomishPlayer.playerHands,
                PlayerDealerBestScore.initializeOutcomeFinderForTable(
                        sim.simulationTable.simulationParameters.houseRules),
                dealerAtCountZero());
    }

    private static double splitInThePayoffRun(Simulation sim) {
        return sim.doPlayerMoveSmartAndGetPayoff(PlayerMove.Split,
                sim.table.randomishPlayer.playerHands);
    }
}
