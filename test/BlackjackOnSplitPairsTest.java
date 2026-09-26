import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a hand that came out of a split is paid when it is two cards making 21.
 *
 * Split aces that draw a ten, or split tens that draw an ace, end on an ace and a ten.
 * Most houses call that an ordinary 21: it wins even money and pushes a dealer 21 made
 * with three or more cards. A few pay it as a blackjack instead, at the table's own
 * blackjack payout, and then it beats a dealer's three-card 21 as well. That is what
 * HouseRules.blackjackOnSplitPairs chooses between. It used to be stored and never read,
 * so every split hand was an ordinary 21 whatever it said.
 *
 * Either way it loses to a dealer natural, which pushes only a natural the player was
 * dealt. That comes up only where the dealer does not peek, since a peeking dealer's
 * natural ends the round before anyone can split.
 *
 * Except where the dealer does not peek, every hand here sits against a 6 at a true
 * count of zero. For the table run the dealer there ends on 17 fourteen times in a
 * hundred, 18 thirteen times, 19 thirteen, 20 twelve, 21 six, and busts the other
 * forty-two; the payoff run deals the dealer's cards instead, from the shoe stacked for
 * each test.
 */
public class BlackjackOnSplitPairsTest {

    /** An ordinary 21 standing against a 6: it loses to nothing and pushes a dealer 21 (6). */
    private static final double STAND_ON_21 = 94.0 / 100.0;

    private static final GranularCount ZERO = new GranularCount(0.0);

    private static HouseRules rules(boolean blackjackOnSplitPairs, double blackjackPayout) {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        hr.blackjackOnSplitPairs = blackjackOnSplitPairs;
        hr.blackjackPayout = blackjackPayout;
        return hr;
    }

    private static ArrayList<Card> hand(Rank... ranks) {
        ArrayList<Card> h = new ArrayList<>();
        Suit[] suits = Suit.values();
        for (int i = 0; i < ranks.length; i++) {
            h.add(new Card(ranks[i], suits[i % suits.length]));
        }
        return h;
    }

    private static SimulationTable emptyTable(HouseRules hr) {
        SimulationParameters sp = new SimulationParameters(
                hr, CountMethod.getHiLoValue(1), 1.0, 10, 10, -5, 5);
        return new SimulationTable(sp, "test");
    }

    /** Sit a player on a hand against an up-card, on a full shoe with a running count of zero. */
    private static Simulation seated(SimulationTable table, Rank upCard, Rank... playerCards) {
        Simulation sim = new Simulation(table, "test");
        sim.table.dealer.revealedCards.add(new Card(upCard, Suit.SPADES));
        sim.table.randomishPlayer.playerHands.playerHand = new PlayerHand(hand(playerCards));
        return sim;
    }

    /** Put these cards on top of the shoe, first one dealt first. */
    private static void stackTheShoe(Simulation sim, Rank... top) {
        ArrayList<Card> cards = hand(top);
        for (int i = cards.size() - 1; i >= 0; i--) {
            sim.table.gameDeck.cards.add(0, cards.get(i));
        }
    }

    private static void holeCard(Simulation sim, Rank rank) {
        sim.table.dealer.hiddenCard.add(new Card(rank, Suit.HEARTS));
    }

    private static MetaDealer dealerAgainstASixAtCountZero() {
        MetaDealerResult mdr = new MetaDealerResult();
        mdr.num17 = 14;
        mdr.num18 = 13;
        mdr.num19 = 13;
        mdr.num20 = 12;
        mdr.num21 = 6;
        mdr.numBust = 42;
        MetaDealer md = new MetaDealer("test");
        md.dealerCountAndUpCardToResults.put(
                new GranularCountAndDealerUpCard(ZERO, Rank.SIX.getRankpoints()), mdr);
        return md;
    }

    private static GranularCount trueCountNow(Simulation sim) {
        HouseRules hr = sim.simulationTable.simulationParameters.houseRules;
        return sim.table.getGranularCount(
                sim.table.gameDeck.startingSize / hr.numDecks, 1.0, -5, 5);
    }

    /**
     * The payoff run: split aces against a 6, each drawing a ten, then the dealer turns
     * the hole card and draws a ten. A hole ten busts the dealer on 26; a hole five makes
     * a three-card 21.
     */
    private static double splitAcesDrawingTensInThePayoffRun(HouseRules hr, Rank hole) {
        Simulation sim = seated(emptyTable(hr), Rank.SIX, Rank.ACE, Rank.ACE);
        holeCard(sim, hole);
        stackTheShoe(sim, Rank.TEN, Rank.TEN, Rank.TEN);
        return sim.doPlayerMoveSmartAndGetPayoff(
                PlayerMove.Split, sim.table.randomishPlayer.playerHands);
    }

    /** The same split in the table run, priced from the dealer's outcomes against a 6. */
    private static double splitAcesDrawingTensInTheTableRun(HouseRules hr) {
        Simulation sim = seated(emptyTable(hr), Rank.SIX, Rank.ACE, Rank.ACE);
        stackTheShoe(sim, Rank.TEN, Rank.TEN);
        double payoff = sim.doPlayerMoveAndGetPayoff(
                PlayerMove.Split, sim.table.randomishPlayer.playerHands,
                PlayerDealerBestScore.initializeOutcomeFinderForTable(hr),
                dealerAgainstASixAtCountZero());
        assertEquals(ZERO, trueCountNow(sim),
                "the two split cards were meant to leave the count at zero");
        return payoff;
    }

    // ------------------------------------------------------------- split aces, rule on

    /** Under the rule each split ace that draws a ten is paid as a blackjack, 3:2. */
    @Test
    public void underTheRuleASplitAceDrawingATenIsPaidAsABlackjack() {
        HouseRules hr = rules(true, 1.5);
        assertEquals(2 * 1.5, splitAcesDrawingTensInThePayoffRun(hr, Rank.TEN), 1e-9,
                "the dealer busts, and each hand is paid 3:2 rather than even money");
    }

    /** And it beats a dealer's three-card 21 at 3:2 rather than pushing it. */
    @Test
    public void underTheRuleASplitAceDrawingATenBeatsADealersThreeCardTwentyOne() {
        HouseRules hr = rules(true, 1.5);
        assertEquals(2 * 1.5, splitAcesDrawingTensInThePayoffRun(hr, Rank.FIVE), 1e-9,
                "the dealer makes 6,5,10; a blackjack beats a drawn 21");
    }

    /**
     * The table run prices standing on it at the same value. A blackjack beats every
     * total the dealer can end on, and a dealer natural never reaches the table run, so
     * each hand is worth the payout outright. It used to be worth standing on an
     * ordinary 21.
     */
    @Test
    public void underTheRuleTheTableRunPricesASplitAceDrawingATenAsABlackjack() {
        HouseRules hr = rules(true, 1.5);
        assertEquals(2 * 1.5, splitAcesDrawingTensInTheTableRun(hr), 1e-9,
                "each hand should be worth 1.5, not the " + STAND_ON_21 + " of a plain 21");
    }

    /** On a 6:5 table it is paid what a natural is paid there, in both runs. */
    @Test
    public void onASixToFiveTableItIsPaidSixToFive() {
        HouseRules hr = rules(true, 1.2);
        assertEquals(2 * 1.2, splitAcesDrawingTensInThePayoffRun(hr, Rank.TEN), 1e-9);
        assertEquals(2 * 1.2, splitAcesDrawingTensInThePayoffRun(hr, Rank.FIVE), 1e-9);
        assertEquals(2 * 1.2, splitAcesDrawingTensInTheTableRun(hr), 1e-9);
    }

    // ------------------------------------------------------------ split aces, rule off

    /** Without the rule it is an ordinary 21: even money against a bust. */
    @Test
    public void withoutTheRuleASplitAceDrawingATenPaysEvenMoney() {
        HouseRules hr = rules(false, 1.5);
        assertEquals(2 * 1.0, splitAcesDrawingTensInThePayoffRun(hr, Rank.TEN), 1e-9);
    }

    /** And it pushes a dealer's three-card 21. */
    @Test
    public void withoutTheRuleASplitAceDrawingATenPushesADealerTwentyOne() {
        HouseRules hr = rules(false, 1.5);
        assertEquals(0.0, splitAcesDrawingTensInThePayoffRun(hr, Rank.FIVE), 1e-9);
    }

    /** The table run prices it as standing on an ordinary 21. */
    @Test
    public void withoutTheRuleTheTableRunPricesItAsAnOrdinaryTwentyOne() {
        HouseRules hr = rules(false, 1.5);
        assertEquals(2 * STAND_ON_21, splitAcesDrawingTensInTheTableRun(hr), 1e-9);
    }

    // ------------------------------------------------------------------- split tens

    /**
     * Split tens that draw an ace qualify too: the rule is blackjack on split pairs, and
     * a ten is as much a pair as an ace. A ten may hit and double after a split, so the
     * payoff run asks the soft-21 cell what to do, and it says stand.
     */
    @Test
    public void aSplitTenDrawingAnAceIsPaidByTheRule() {
        for (boolean rule : new boolean[] {true, false}) {
            HouseRules hr = rules(rule, 1.5);
            SimulationTable table = emptyTable(hr);
            HandEncoding soft21 = new HandEncoding(true, false, 11);
            table.insertEvent(new EventResult(STAND_ON_21, soft21, Rank.SIX, PlayerMove.Stand, ZERO));
            table.insertEvent(new EventResult(-0.2, soft21, Rank.SIX, PlayerMove.Hit, ZERO));

            Simulation sim = seated(table, Rank.SIX, Rank.TEN, Rank.TEN);
            holeCard(sim, Rank.FIVE);
            stackTheShoe(sim, Rank.ACE, Rank.ACE, Rank.TEN);
            double payoff = sim.doPlayerMoveSmartAndGetPayoff(
                    PlayerMove.Split, sim.table.randomishPlayer.playerHands);

            assertEquals(ZERO, trueCountNow(sim),
                    "the two split cards were meant to leave the count at zero");
            assertEquals(rule ? 2 * 1.5 : 0.0, payoff, 1e-9,
                    "against the dealer's 6,5,10 with blackjackOnSplitPairs " + rule);
        }
    }

    /**
     * In the table run too. The soft-21 cell holds what an ordinary three-card soft 21
     * is worth; under the rule the split hand is worth more than that, and standing is
     * priced from the dealer rather than read from the cell.
     */
    @Test
    public void theTableRunPricesASplitTenDrawingAnAceByTheRule() {
        for (boolean rule : new boolean[] {true, false}) {
            HouseRules hr = rules(rule, 1.5);
            SimulationTable table = emptyTable(hr);
            HandEncoding soft21 = new HandEncoding(true, false, 11);
            table.insertEvent(new EventResult(STAND_ON_21, soft21, Rank.SIX, PlayerMove.Stand, ZERO));
            table.insertEvent(new EventResult(-0.2, soft21, Rank.SIX, PlayerMove.Hit, ZERO));

            Simulation sim = seated(table, Rank.SIX, Rank.TEN, Rank.TEN);
            stackTheShoe(sim, Rank.ACE, Rank.ACE);
            double payoff = sim.doPlayerMoveAndGetPayoff(
                    PlayerMove.Split, sim.table.randomishPlayer.playerHands,
                    PlayerDealerBestScore.initializeOutcomeFinderForTable(hr),
                    dealerAgainstASixAtCountZero());

            assertEquals(rule ? 2 * 1.5 : 2 * STAND_ON_21, payoff, 1e-9,
                    "with blackjackOnSplitPairs " + rule);
        }
    }

    // ------------------------------------------------------------- dealt naturals

    /** A natural the player was dealt is paid 3:2 whatever the rule says about splits. */
    @Test
    public void aDealtNaturalIsPaidTheSameEitherWay() {
        for (boolean rule : new boolean[] {true, false}) {
            for (Rank hole : new Rank[] {Rank.TEN, Rank.FIVE}) {
                HouseRules hr = rules(rule, 1.5);
                Simulation sim = seated(emptyTable(hr), Rank.SIX, Rank.ACE, Rank.KING);
                holeCard(sim, hole);
                stackTheShoe(sim, Rank.TEN);
                double payoff = sim.doPlayerMoveSmartAndGetPayoff(
                        PlayerMove.Stand, sim.table.randomishPlayer.playerHands);
                assertEquals(1.5, payoff, 1e-9,
                        "hole " + hole + ", blackjackOnSplitPairs " + rule);
            }
        }
    }
}
