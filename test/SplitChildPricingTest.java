import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a hand that came out of a split is worth, and which move it plays.
 *
 * The table run prices each split hand from the measured moves in that hand's own cell.
 * That is sound for an ordinary child, whose cell was solved before the pair was
 * started. It is not sound for a child that is still a pair and cannot be split again --
 * A,A after splitting aces once, or x,x at four hands -- because its own cell is the pair
 * cell being built at that moment, often at a count it has not reached yet. Split aces
 * make it worse: they may not hit or double, so standing is the only move left, and a
 * pair cell that has been leaning towards splitting has seldom measured it. The lookup
 * came back empty and the whole run stopped with UnsolvedCellException.
 *
 * Standing needs no lookup. The dealer's outcomes for the count and up-card are already
 * known before the table run starts, so a stand is priced from them directly, exactly as
 * a hand that stands as its first move is. The other legal moves still come from what has
 * been measured.
 *
 * The payoff run chooses moves rather than pricing them. When a split hand has exactly
 * one legal move there is nothing for the table to choose, so it plays that move.
 *
 * Every test here sits against a 6 at a true count of zero. For the table run the dealer
 * there ends on 17 fourteen times in a hundred, 18 thirteen times, 19 thirteen, 20
 * twelve, 21 six, and busts the other forty-two; the payoff run deals the dealer's cards
 * instead.
 */
public class SplitChildPricingTest {

    /** Standing on 12 wins only when the dealer busts: 42 wins against 58 losses. */
    private static final double STAND_ON_12 = (42.0 - 58.0) / 100.0;

    /** Standing on 18 beats a 17 or a bust (56), pushes an 18, loses to 19, 20 or 21 (31). */
    private static final double STAND_ON_18 = (56.0 - 31.0) / 100.0;

    /** Standing on 21 loses to nothing and pushes a dealer 21 (6). */
    private static final double STAND_ON_21 = 94.0 / 100.0;

    private static final GranularCount ZERO = new GranularCount(0.0);

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

    /** Sit a player on a hand against a 6, on a full shoe with a running count of zero. */
    private static Simulation seated(SimulationTable table, Rank... playerCards) {
        Simulation sim = new Simulation(table, "test");
        sim.table.dealer.revealedCards.add(new Card(Rank.SIX, Suit.SPADES));
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

    private static void record(SimulationTable table, HandEncoding he, PlayerMove pm,
                               double payoff) {
        table.insertEvent(new EventResult(payoff, he, Rank.SIX, pm, ZERO));
    }

    private static GranularCount trueCountNow(Simulation sim) {
        HouseRules hr = sim.simulationTable.simulationParameters.houseRules;
        return sim.table.getGranularCount(
                sim.table.gameDeck.startingSize / hr.numDecks, 1.0, -5, 5);
    }

    /**
     * The reported crash. The A,A row is being built, and at a count of zero its cell has
     * only measured splitting and hitting. Aces are split, and the first one draws another
     * ace. Aces split once under these rules, so that hand is A,A with nothing left but
     * to stand, and it used to be priced from the A,A cell's measured Stand, which was not
     * there. It is worth standing on 12, and the other hand, an ace and a ten, standing on
     * 21; the split returns the sum for runSimulation to record.
     */
    @Test
    public void aSplitAceThatDrawsAnotherAceIsPricedInsteadOfStoppingTheRun() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        SimulationTable table = emptyTable(hr);
        HandEncoding aces = new HandEncoding(true, true, 2);
        record(table, aces, PlayerMove.Split, 0.3);
        record(table, aces, PlayerMove.Hit, 0.1);

        Simulation sim = seated(table, Rank.ACE, Rank.ACE);
        stackTheShoe(sim, Rank.ACE, Rank.TEN);

        double payoff = sim.doPlayerMoveAndGetPayoff(
                PlayerMove.Split, sim.table.randomishPlayer.playerHands,
                PlayerDealerBestScore.initializeOutcomeFinderForTable(hr),
                dealerAgainstASixAtCountZero());

        assertEquals(ZERO, trueCountNow(sim),
                "the two split cards were meant to leave the count at zero");
        assertEquals(STAND_ON_12 + STAND_ON_21, payoff, 1e-9,
                "the A,A hand should stand on 12 and the A,10 hand on 21");
    }

    /** With both split aces drawing an ace, each hand is worth exactly standing on 12. */
    @Test
    public void anAceHandAtTheSplitLimitIsWorthStandingOnTwelve() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        SimulationTable table = emptyTable(hr);
        HandEncoding aces = new HandEncoding(true, true, 2);
        record(table, aces, PlayerMove.Split, 0.3);
        record(table, aces, PlayerMove.Hit, 0.1);

        Simulation sim = seated(table, Rank.ACE, Rank.ACE);
        stackTheShoe(sim, Rank.ACE, Rank.ACE);

        double payoff = sim.doPlayerMoveAndGetPayoff(
                PlayerMove.Split, sim.table.randomishPlayer.playerHands,
                PlayerDealerBestScore.initializeOutcomeFinderForTable(hr),
                dealerAgainstASixAtCountZero());

        assertEquals(2 * STAND_ON_12, payoff, 1e-9,
                "each A,A hand has only a stand left, and a stand on 12 against a 6 "
                        + "at count zero is worth " + STAND_ON_12);
    }

    /**
     * Nothing changes for an ordinary split hand whose cell has measured Stand.
     *
     * A stand recorded by the table run is priced from the same dealer outcomes, so every
     * observation of it at one count, up-card and total is the same number, and so is
     * their average. Pricing it directly gives that number back. Here 8,8 is split and
     * both hands draw a ten, landing on a hard 18 whose cell holds three stands recorded
     * through the table run's own Stand path, plus a hit and a double that lose to it.
     */
    @Test
    public void aSplitHandWhoseCellMeasuredStandIsPricedAsBefore() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        HashMap<PlayerDealerBestScore, Outcome> outcomeFinder =
                PlayerDealerBestScore.initializeOutcomeFinderForTable(hr);
        MetaDealer md = dealerAgainstASixAtCountZero();
        SimulationTable table = emptyTable(hr);
        HandEncoding hard18 = new HandEncoding(false, false, 18);

        for (int i = 0; i < 3; i++) {
            Simulation standing = seated(table, Rank.TEN, Rank.EIGHT);
            double stand = standing.doPlayerMoveAndGetPayoff(
                    PlayerMove.Stand, standing.table.randomishPlayer.playerHands,
                    outcomeFinder, md);
            record(table, hard18, PlayerMove.Stand, stand);
        }
        record(table, hard18, PlayerMove.Hit, -0.6);
        record(table, hard18, PlayerMove.Double, -1.3);

        HandSituation hs = new HandSituation(hard18, Rank.SIX.getRankpoints());
        double measuredStand = table.actionMap.get(hs).countToMoveChoice.get(ZERO)
                .actionPayoffs.get(PlayerMove.Stand).avPayoff;
        assertEquals(STAND_ON_18, measuredStand, 0.0,
                "every recorded stand on 18 should be the same number");
        double whatTheCellSays = table.getBestPlayerMovePayoff(
                hs, ZERO, EnumSet.of(PlayerMove.Stand, PlayerMove.Hit, PlayerMove.Double));

        Simulation sim = seated(table, Rank.EIGHT, Rank.EIGHT);
        stackTheShoe(sim, Rank.TEN, Rank.TEN);
        double payoff = sim.doPlayerMoveAndGetPayoff(
                PlayerMove.Split, sim.table.randomishPlayer.playerHands, outcomeFinder, md);

        assertEquals(ZERO, trueCountNow(sim),
                "the two split cards were meant to leave the count at zero");
        assertEquals(2 * whatTheCellSays, payoff, 0.0,
                "each hard 18 should be worth what its cell already said");
    }

    /** A measured move that beats standing is still the one a split hand is priced by. */
    @Test
    public void aMeasuredMoveThatBeatsStandingStillPricesTheSplitHand() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        SimulationTable table = emptyTable(hr);
        HandEncoding hard18 = new HandEncoding(false, false, 18);
        record(table, hard18, PlayerMove.Stand, STAND_ON_18);
        record(table, hard18, PlayerMove.Double, 0.9);

        Simulation sim = seated(table, Rank.EIGHT, Rank.EIGHT);
        stackTheShoe(sim, Rank.TEN, Rank.TEN);
        double payoff = sim.doPlayerMoveAndGetPayoff(
                PlayerMove.Split, sim.table.randomishPlayer.playerHands,
                PlayerDealerBestScore.initializeOutcomeFinderForTable(hr),
                dealerAgainstASixAtCountZero());

        assertEquals(2 * 0.9, payoff, 1e-9,
                "the measured double at 0.9 beats a stand worth " + STAND_ON_18);
    }

    /**
     * Standing is always a legal move for a split hand, so it is always a candidate, even
     * when the cell has only measured something worse. This used to price each hard 18
     * as the hit, the only move its cell held.
     */
    @Test
    public void standingIsACandidateEvenWhenTheCellOnlyMeasuredAHit() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        SimulationTable table = emptyTable(hr);
        HandEncoding hard18 = new HandEncoding(false, false, 18);
        record(table, hard18, PlayerMove.Hit, -0.6);

        Simulation sim = seated(table, Rank.EIGHT, Rank.EIGHT);
        stackTheShoe(sim, Rank.TEN, Rank.TEN);
        double payoff = sim.doPlayerMoveAndGetPayoff(
                PlayerMove.Split, sim.table.randomishPlayer.playerHands,
                PlayerDealerBestScore.initializeOutcomeFinderForTable(hr),
                dealerAgainstASixAtCountZero());

        assertEquals(2 * STAND_ON_18, payoff, 1e-9,
                "standing on 18 is worth " + STAND_ON_18 + ", better than the -0.6 hit");
    }

    /**
     * The payoff run on a finished table whose A,A cell, at the count a split lands on,
     * measured splitting and hitting but never standing. A split ace that draws another
     * ace can only stand, so there is nothing to look up; it used to ask the A,A cell
     * which of {Stand} was best, find nothing, and stop the run.
     *
     * The dealer turns a ten under the 6 and draws another ten, so both hands win.
     */
    @Test
    public void thePayoffRunPlaysASplitHandsOnlyLegalMoveWithoutALookup() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        SimulationTable table = emptyTable(hr);
        HandEncoding aces = new HandEncoding(true, true, 2);
        record(table, aces, PlayerMove.Split, 0.3);
        record(table, aces, PlayerMove.Hit, 0.1);
        record(table, new HandEncoding(true, false, 11), PlayerMove.Stand, STAND_ON_21);

        Simulation sim = seated(table, Rank.ACE, Rank.ACE);
        sim.table.dealer.hiddenCard.add(new Card(Rank.TEN, Suit.HEARTS));
        stackTheShoe(sim, Rank.ACE, Rank.TEN, Rank.TEN);

        double payoff = sim.doPlayerMoveSmartAndGetPayoff(
                PlayerMove.Split, sim.table.randomishPlayer.playerHands);

        assertEquals(ZERO, trueCountNow(sim),
                "the two split cards were meant to leave the count at zero");
        assertEquals(2.0, payoff, 1e-9,
                "both hands stand and the dealer busts on 26, so both win");
    }
}
