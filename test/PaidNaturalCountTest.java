import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The payoff run counts the rounds in which the player was paid for a natural.
 *
 * It used to work that out from the payoff: a round worth exactly 1.5 was taken to be a
 * natural. That only holds while naturals pay 3:2. At 6:5 a natural is worth 1.2, so the
 * count read zero however many were dealt, and a round that came to 1.5 some other way
 * would have been counted as one. The round now carries the fact itself, taken from the
 * cards when it is dealt.
 *
 * A natural is paid when the dealer has none to push it and the player stands on it. A
 * natural pushed by a dealer natural was never counted, and still is not.
 *
 * The rounds here go through runSingleSmartEvent with the deal fixed, so what is tested is
 * the run itself rather than a copy of its logic.
 */
public class PaidNaturalCountTest {

    private static final GranularCount EVEN = new GranularCount(0.0);

    private static ArrayList<Card> hand(Rank... ranks) {
        ArrayList<Card> h = new ArrayList<>();
        Suit[] suits = Suit.values();
        for (int i = 0; i < ranks.length; i++) {
            h.add(new Card(ranks[i], suits[i % suits.length]));
        }
        return h;
    }

    private static HandEncoding encoding(Rank... ranks) {
        return new HandEncoding(hand(ranks));
    }

    private static SimulationTable emptyTable(HouseRules hr) {
        SimulationParameters sp = new SimulationParameters(
                hr, CountMethod.getHiLoValue(1), 1.0, 10, 10, -5, 5);
        return new SimulationTable(sp, "test");
    }

    /** Give the finished table a measured move, so the payoff run has something to play. */
    private static void measure(SimulationTable st, PlayerMove move, double payoff,
                                Rank upCard, Rank... playerCards) {
        st.insertEvent(new EventResult(payoff, encoding(playerCards), upCard, move, EVEN));
    }

    /**
     * A payoff run whose next round is dealt from fixed cards instead of from the shoe.
     *
     * Nothing is taken off the shoe, so the running count stays at zero and the round lands
     * in the even-count bucket. Any cards named as next are put on top of the shoe, for a
     * round that hits.
     */
    private static class RiggedSimulation extends Simulation {
        private final Rank upCard;
        private final Rank holeCard;
        private final Rank[] playerCards;
        private final Rank[] nextCards;

        RiggedSimulation(SimulationTable st, Rank upCard, Rank holeCard, Rank[] playerCards,
                         Rank... nextCards) {
            super(st, "test");
            this.upCard = upCard;
            this.holeCard = holeCard;
            this.playerCards = playerCards;
            this.nextCards = nextCards;
        }

        @Override
        public void setCardsSmart() {
            table.dealer.revealedCards.add(new Card(upCard, Suit.SPADES));
            table.dealer.hiddenCard.add(new Card(holeCard, Suit.HEARTS));
            table.randomishPlayer.playerHands.playerHand = new PlayerHand(hand(playerCards));
            for (int i = nextCards.length - 1; i >= 0; i--) {
                table.gameDeck.cards.add(0, new Card(nextCards[i], Suit.CLUBS));
            }
        }

        EventResult play() {
            return runSingleSmartEvent(-5, 5, 1.0);
        }
    }

    private static Rank[] cards(Rank... ranks) {
        return ranks;
    }

    private static PayoffTable payoffTable() {
        return new PayoffTable(-5, 5, 1.0, "test");
    }

    private static int roundsCounted(PayoffTable pt) {
        int n = 0;
        for (CountPayoff cp : pt.countPayoffs) {
            n += cp.actionPayoff.numTimes;
        }
        return n;
    }

    private static int blackjacksCounted(PayoffTable pt) {
        int n = 0;
        for (CountPayoff cp : pt.countPayoffs) {
            n += cp.actionPayoff.numPlayerBlackjacks;
        }
        return n;
    }

    /**
     * At 6:5 a paid natural is worth 1.2, and it is still a paid natural.
     *
     * This is the case that read zero: the payoff was never 1.5, so nothing was counted.
     */
    @Test
    public void aNaturalPaidAtSixToFiveIsCounted() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        hr.blackjackPayout = 1.2;
        SimulationTable st = emptyTable(hr);
        measure(st, PlayerMove.Stand, 1.0, Rank.TEN, Rank.ACE, Rank.KING);

        EventResult er = new RiggedSimulation(
                st, Rank.TEN, Rank.SEVEN, cards(Rank.ACE, Rank.KING)).play();
        PayoffTable pt = payoffTable();
        pt.insertEventSmart(er);

        assertEquals(1.2, er.payoff, 1e-9, "the rigged natural should have been paid 6:5");
        assertEquals(1, roundsCounted(pt), "the round should have been recorded");
        assertEquals(1, blackjacksCounted(pt),
                "a natural paid 6:5 was not counted as a blackjack");
    }

    /**
     * A payoff of 1.5 is not a natural unless the cards were one.
     *
     * Nothing else comes to exactly 1.5 under the configured rules, but that is a fact
     * about these rules, and the count should not rest on it. EventResult's original
     * constructor records a round that was not a paid natural.
     */
    @Test
    public void aRoundWorthOneAndAHalfThatWasNotANaturalIsNotCounted() {
        PayoffTable pt = payoffTable();
        pt.insertEventSmart(new EventResult(
                1.5, encoding(Rank.TEN, Rank.NINE), Rank.TEN, PlayerMove.Stand, EVEN));

        assertEquals(1, roundsCounted(pt), "the round should have been recorded");
        assertEquals(0, blackjacksCounted(pt),
                "a hard 19 worth 1.5 was counted as a blackjack because of its payoff");
    }

    /**
     * A natural against a dealer natural is not paid as one. The dealer peeks, as the
     * engine requires, and settles the pair of naturals as a push before anyone acts.
     */
    @Test
    public void aNaturalAgainstADealerNaturalIsNotCounted() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        hr.blackjackPayout = 1.2;
        SimulationTable st = emptyTable(hr);
        measure(st, PlayerMove.Stand, 1.0, Rank.ACE, Rank.ACE, Rank.QUEEN);

        EventResult er = new RiggedSimulation(
                st, Rank.ACE, Rank.KING, cards(Rank.ACE, Rank.QUEEN)).play();
        PayoffTable pt = payoffTable();
        pt.insertEventSmart(er);

        assertEquals(0.0, er.payoff, 1e-9, "two naturals push");
        assertEquals(1, roundsCounted(pt), "the round should have been recorded");
        assertEquals(0, blackjacksCounted(pt),
                "a natural pushed by the dealer's was counted as paid");
    }

    /**
     * A natural the player hits is an ordinary three-card hand by the time it is settled,
     * and is paid as one. The table's soft-21 cell always says stand in practice; this
     * rigs one that says hit, to pin down that being dealt a natural is not the same as
     * being paid for it.
     */
    @Test
    public void aNaturalThePlayerHitsIsNotCounted() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        hr.blackjackPayout = 1.2;
        SimulationTable st = emptyTable(hr);
        measure(st, PlayerMove.Stand, 0.1, Rank.TEN, Rank.ACE, Rank.KING);
        measure(st, PlayerMove.Hit, 0.9, Rank.TEN, Rank.ACE, Rank.KING);
        measure(st, PlayerMove.Stand, 0.9, Rank.TEN, Rank.ACE, Rank.KING, Rank.TEN);

        EventResult er = new RiggedSimulation(
                st, Rank.TEN, Rank.SEVEN, cards(Rank.ACE, Rank.KING), Rank.TEN).play();
        PayoffTable pt = payoffTable();
        pt.insertEventSmart(er);

        assertEquals(PlayerMove.Hit, er.playedFirstMove, "the rigged cell should have said hit");
        assertEquals(1.0, er.payoff, 1e-9, "a hard 21 against 17 wins even money");
        assertEquals(0, blackjacksCounted(pt),
                "a natural that was hit, and so not paid as one, was counted");
    }

    /** At 3:2 nothing changes: the paid natural counts, the ordinary win does not. */
    @Test
    public void atThreeToTwoTheCountIsUnchanged() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        SimulationTable st = emptyTable(hr);
        measure(st, PlayerMove.Stand, 1.0, Rank.TEN, Rank.ACE, Rank.KING);
        measure(st, PlayerMove.Stand, 0.5, Rank.TEN, Rank.TEN, Rank.NINE);

        EventResult natural = new RiggedSimulation(
                st, Rank.TEN, Rank.SEVEN, cards(Rank.ACE, Rank.KING)).play();
        EventResult nineteen = new RiggedSimulation(
                st, Rank.TEN, Rank.SEVEN, cards(Rank.TEN, Rank.NINE)).play();
        PayoffTable pt = payoffTable();
        pt.insertEventSmart(natural);
        pt.insertEventSmart(nineteen);

        assertEquals(1.5, natural.payoff, 1e-9, "a natural pays 3:2 under the configured rules");
        assertEquals(1.0, nineteen.payoff, 1e-9, "19 against 17 wins even money");
        assertEquals(2, roundsCounted(pt), "both rounds should have been recorded");
        assertEquals(1, blackjacksCounted(pt), "only the natural is a blackjack");
        CountPayoff even = null;
        for (CountPayoff cp : pt.countPayoffs) {
            if (cp.granularCount.equals(EVEN)) {
                even = cp;
            }
        }
        assertNotNull(even, "the payoff table should have an even-count cell");
        assertEquals(50.0, even.actionPayoff.playerBlackjackPercentage, 1e-9,
                "one natural in two rounds is 50%");
    }
}
