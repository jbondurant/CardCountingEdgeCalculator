import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A card the player draws during a round is not counted, so every lookup the round makes
 * reads the count the round was dealt at.
 *
 * That is the convention every cell is measured under. setCards and setCardsSmart deal the
 * player's hand and the dealer's up card without counting them, and the MetaDealer is
 * measured the same way. It was settled in June 2022, because counting the hand's own
 * cards made low counts look better for no reason except that a low count meant the hand
 * was more likely to be holding a ten or an ace.
 *
 * A move that draws a card goes on to look up the hand it made, and that hand's cell was
 * measured with none of its own cards counted. Counting the card that made it asks the
 * cell a question in a different convention from the one it answers. The table run's Hit
 * did that, and both runs counted the two cards dealt to split hands, while Double and the
 * payoff run's Hit did not. So the table priced a hit at a count the payoff run never
 * played it at, and hitting and doubling on the same card read different buckets.
 */
public class DrawnCardCountTest {

    private static final int MIN_COUNT = -5;
    private static final int MAX_COUNT = 5;

    /**
     * The count the round is dealt at. With one deck left the true count is the running
     * count, so each Hi-Lo low card counted by mistake moves the lookup a whole bucket.
     */
    private static final int DEALT_AT = 2;
    private static final GranularCount DEALT_AT_BUCKET = new GranularCount((double) DEALT_AT);

    /**
     * A simulation that notes the count behind every lookup a move makes once it has
     * drawn, and otherwise answers as plainly as it can: whatever comes next is worth
     * nothing, and every hand stands.
     */
    private static class CountRecordingSimulation extends Simulation {
        final List<Integer> runningCountAtLookup = new ArrayList<>();
        final List<GranularCount> bucketAtLookup = new ArrayList<>();

        CountRecordingSimulation(SimulationTable st) {
            super(st, "test");
        }

        private void note(GranularCount gc) {
            runningCountAtLookup.add(table.runningCount);
            bucketAtLookup.add(gc);
        }

        /** Where the table run prices whatever the move left behind. */
        @Override
        public double getBestPlayerMovePayoff(HandSituation hs, GranularCount gc,
                                              EnumSet<PlayerMove> legalMoves) {
            note(gc);
            return 0.0;
        }

        /** Where the payoff run chooses what to do with whatever the move left behind. */
        @Override
        public PlayerMove getBestPlayerMove(HandSituation hs, GranularCount gc,
                                            EnumSet<PlayerMove> legalMoves, int minC, int maxC) {
            note(gc);
            return PlayerMove.Stand;
        }
    }

    private static ArrayList<Card> hand(Rank... ranks) {
        ArrayList<Card> h = new ArrayList<>();
        Suit[] suits = Suit.values();
        for (int i = 0; i < ranks.length; i++) {
            h.add(new Card(ranks[i], suits[i % suits.length]));
        }
        return h;
    }

    /**
     * Sit a player on a hand against a dealer showing a ten over a seven, at running count
     * DEALT_AT, with one deck left in the shoe and the given cards on top of it.
     */
    private static CountRecordingSimulation seated(Rank[] playerCards, Rank... nextCards) {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        SimulationParameters sp = new SimulationParameters(
                hr, CountMethod.getHiLoValue(1), 1.0, 10, 10, MIN_COUNT, MAX_COUNT);
        CountRecordingSimulation sim = new CountRecordingSimulation(new SimulationTable(sp, "test"));

        sim.table.dealer.revealedCards.add(new Card(Rank.TEN, Suit.SPADES));
        sim.table.dealer.hiddenCard.add(new Card(Rank.SEVEN, Suit.SPADES));
        sim.table.randomishPlayer.playerHands.playerHand = new PlayerHand(hand(playerCards));

        ArrayList<Card> shoe = sim.table.gameDeck.cards;
        ArrayList<Card> oneDeck = new ArrayList<>();
        for (Rank r : nextCards) {
            for (int i = 0; i < shoe.size(); i++) {
                if (shoe.get(i).rank == r) {
                    oneDeck.add(shoe.remove(i));
                    break;
                }
            }
        }
        while (oneDeck.size() < 52) {
            oneDeck.add(shoe.remove(0));
        }
        shoe.clear();
        shoe.addAll(oneDeck);

        sim.table.runningCount = DEALT_AT;
        int deckSize = sim.table.gameDeck.startingSize / hr.numDecks;
        assertEquals(DEALT_AT_BUCKET,
                sim.table.getGranularCount(deckSize, 1.0, MIN_COUNT, MAX_COUNT),
                "the seated round should be dealt at a true count equal to its running count");
        return sim;
    }

    /**
     * The table run asks which moves a split hand has measured before it prices one, so
     * each hand the split can make needs a cell at every count for the lookup to reach.
     *
     * Hit is measured as well as Stand so that the split hands are always priced through
     * getBestPlayerMovePayoff, where the count is noted, whether a split hand's stand is
     * read from its cell or priced straight from the dealer's outcomes.
     */
    @SafeVarargs
    private static void standAndHitMeasuredAtEveryCount(Simulation sim, Rank dealerUpCard,
                                                        ArrayList<Card>... hands) {
        for (ArrayList<Card> h : hands) {
            for (int count = MIN_COUNT; count <= MAX_COUNT; count++) {
                for (PlayerMove move : new PlayerMove[]{PlayerMove.Stand, PlayerMove.Hit}) {
                    sim.simulationTable.insertEvent(new EventResult(0.0, new HandEncoding(h),
                            dealerUpCard, move, new GranularCount((double) count)));
                }
            }
        }
    }

    /**
     * A dealer who has finished on 17 and busted once each at every count against this
     * up-card, so that pricing a stand from the dealer's outcomes has something to read
     * whatever bucket it is asked for.
     */
    private static MetaDealer dealerAtEveryCount(Rank dealerUpCard) {
        MetaDealer md = new MetaDealer("test");
        for (int count = MIN_COUNT; count <= MAX_COUNT; count++) {
            GranularCount gc = new GranularCount((double) count);
            md.insertEvent(new MetaDealerEventResult(gc, 17, false, dealerUpCard.getRankpoints()), true);
            md.insertEvent(new MetaDealerEventResult(gc, 22, false, dealerUpCard.getRankpoints()), true);
        }
        return md;
    }

    private static double tableRun(Simulation sim, PlayerMove move) {
        HouseRules hr = sim.simulationTable.simulationParameters.houseRules;
        HashMap<PlayerDealerBestScore, Outcome> outcomeFinder =
                PlayerDealerBestScore.initializeOutcomeFinderForTable(hr);
        return sim.doPlayerMoveAndGetPayoff(move, sim.table.randomishPlayer.playerHands,
                outcomeFinder, dealerAtEveryCount(sim.table.dealer.revealedCards.get(0).rank));
    }

    private static double payoffRun(Simulation sim, PlayerMove move) {
        return sim.doPlayerMoveSmartAndGetPayoff(move, sim.table.randomishPlayer.playerHands);
    }

    /**
     * Hard 16 against a ten, hitting a five into 21.
     *
     * The five is a Hi-Lo low card, so counting it would read hard 21 one bucket higher
     * than the round was dealt at. The table run did; the payoff run did not, so the value
     * the table recorded for hitting was built from a continuation the payoff run never
     * plays.
     */
    @Test
    public void aHitIsPricedAndPlayedAtTheCountTheRoundWasDealtAt() {
        Rank[] hard16 = {Rank.TEN, Rank.SIX};
        CountRecordingSimulation priced = seated(hard16, Rank.FIVE);
        CountRecordingSimulation played = seated(hard16, Rank.FIVE);

        tableRun(priced, PlayerMove.Hit);
        payoffRun(played, PlayerMove.Hit);

        assertEquals(List.of(DEALT_AT), priced.runningCountAtLookup,
                "the table run looked up the hand the hit made at a running count that "
                        + "includes the card it drew");
        assertEquals(List.of(DEALT_AT), played.runningCountAtLookup,
                "the payoff run looked up the hand the hit made at a running count that "
                        + "includes the card it drew");
        assertEquals(List.of(DEALT_AT_BUCKET), priced.bucketAtLookup,
                "the table run priced the hit from a different count bucket than the round "
                        + "was dealt in");
        assertEquals(priced.bucketAtLookup, played.bucketAtLookup,
                "the table priced the hit from one count bucket and the payoff run played "
                        + "it from another");
    }

    /**
     * Eights against a ten, split, with a five and a three dealt to the two hands.
     *
     * Each of those cards belongs to the hand it was dealt to, and both are Hi-Lo low
     * cards, so counting them would read both hands two buckets higher than the round was
     * dealt at. Both runs did.
     */
    @Test
    public void splitHandsArePricedAndPlayedAtTheCountTheRoundWasDealtAt() {
        Rank[] eights = {Rank.EIGHT, Rank.EIGHT};
        CountRecordingSimulation priced = seated(eights, Rank.FIVE, Rank.THREE);
        CountRecordingSimulation played = seated(eights, Rank.FIVE, Rank.THREE);
        standAndHitMeasuredAtEveryCount(priced, Rank.TEN,
                hand(Rank.EIGHT, Rank.FIVE), hand(Rank.EIGHT, Rank.THREE));

        tableRun(priced, PlayerMove.Split);
        payoffRun(played, PlayerMove.Split);

        assertEquals(List.of(DEALT_AT, DEALT_AT), priced.runningCountAtLookup,
                "the table run looked up the split hands at a running count that includes "
                        + "their own second cards");
        assertEquals(List.of(DEALT_AT, DEALT_AT), played.runningCountAtLookup,
                "the payoff run looked up the split hands at a running count that includes "
                        + "their own second cards");
        assertEquals(List.of(DEALT_AT_BUCKET, DEALT_AT_BUCKET), priced.bucketAtLookup,
                "the table run priced the split hands from a different count bucket than the "
                        + "round was dealt in");
        assertEquals(priced.bucketAtLookup, played.bucketAtLookup,
                "the table priced the split hands from one count bucket and the payoff run "
                        + "played them from another");
    }

    /**
     * Hard 16 against a ten again, this time in the table run alone: hitting and doubling
     * take the same five and make the same 21, so they have to read the same cell.
     *
     * Double never counted its card. Hit did, so the two moves were compared using values
     * of hard 21 from different count buckets.
     */
    @Test
    public void hittingAndDoublingOnTheSameCardReadTheSameCount() {
        Rank[] hard16 = {Rank.TEN, Rank.SIX};
        CountRecordingSimulation hit = seated(hard16, Rank.FIVE);
        CountRecordingSimulation doubled = seated(hard16, Rank.FIVE);

        tableRun(hit, PlayerMove.Hit);
        tableRun(doubled, PlayerMove.Double);

        assertEquals(List.of(DEALT_AT_BUCKET), doubled.bucketAtLookup,
                "a double should read the hand it made at the count the round was dealt at");
        assertEquals(doubled.bucketAtLookup, hit.bucketAtLookup,
                "hitting and doubling on the same card read hard 21 from different count "
                        + "buckets");
    }
}
