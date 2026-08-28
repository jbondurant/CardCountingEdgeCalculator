import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pieces everything else is built on: reading a hand, and the Hi-Lo tags.
 *
 * These describe behaviour that is already correct. They are here to pin it down
 * before anything gets changed around them.
 */
public class HandReadingTest {

    private static ArrayList<Card> hand(Card... cards) {
        ArrayList<Card> h = new ArrayList<>();
        for (Card c : cards) {
            h.add(c);
        }
        return h;
    }

    private static Card card(Rank r) {
        return new Card(r, Suit.SPADES);
    }

    /** An ace plus a ten is a soft 21. */
    @Test
    public void aceTenIsSoftTwentyOne() {
        HandEncoding he = new HandEncoding(hand(card(Rank.ACE), card(Rank.KING)));
        assertTrue(he.isSoft21(), "A+K is a natural");
        assertEquals(21, he.getBestScore());
    }

    /** A three card 21 is a hard 21, and must not be read as a natural. */
    @Test
    public void sevenSevenSevenIsHardTwentyOne() {
        HandEncoding he = new HandEncoding(
                hand(card(Rank.SEVEN), card(Rank.SEVEN), card(Rank.SEVEN)));
        assertFalse(he.isSoft21(), "7-7-7 is not a natural blackjack");
        assertEquals(21, he.getBestScore());
    }

    /** An ace counts as 1 once the hand would otherwise bust. */
    @Test
    public void softHandHardensPastTwentyOne() {
        HandEncoding he = new HandEncoding(
                hand(card(Rank.ACE), card(Rank.SIX), card(Rank.KING)));
        assertFalse(he.isSoft, "A+6+10 has to count the ace as 1");
        assertEquals(17, he.getBestScore());
    }

    /** Two cards of equal value are a pair, whatever their ranks are named. */
    @Test
    public void tenValuedCardsFormAPair() {
        HandEncoding he = new HandEncoding(hand(card(Rank.JACK), card(Rank.QUEEN)));
        assertTrue(he.canSplit, "J+Q are both worth ten, so they are splittable");
    }

    /** A drawn card leaves the shoe. */
    @Test
    public void drawingRemovesTheCard() {
        Deck d = new Deck();
        assertEquals(52, d.cards.size());
        d.draw();
        assertEquals(51, d.cards.size(), "a drawn card must leave the deck");
    }

    /** Hi-Lo is a balanced count: one full deck sums to zero. */
    @Test
    public void hiLoIsBalancedOverAFullDeck() {
        CountMethod hiLo = CountMethod.getHiLoValue(1);
        int total = 0;
        for (Card c : new Deck().cards) {
            total += hiLo.rankToCount.get(c.rank);
        }
        assertEquals(0, total, "Hi-Lo over one full deck must sum to zero");
    }

    /** Every rank needs a count value, or running the count throws. */
    @Test
    public void everyRankHasAHiLoValue() {
        CountMethod hiLo = CountMethod.getHiLoValue(1);
        for (Rank r : Rank.values()) {
            assertNotNull(hiLo.rankToCount.get(r), "no Hi-Lo value for " + r);
        }
    }

    /** Eight decks is 416 cards. */
    @Test
    public void eightDeckShoeIsFourHundredSixteenCards() {
        CompositeCardSource shoe = CompositeCardSource.getMultiDeck(8);
        assertEquals(416, shoe.cards.size());
        assertEquals(416, shoe.startingSize);
    }
}
