import com.mongodb.BasicDBObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The true count is the running count over the number of decks left, and the deck
 * estimation precision decides what that number is.
 *
 * getNumDecksRoundedUp divides the cards left by precision times deck size and rounds up.
 * At 1 that is decks. At anything else it is blocks of that many decks, which the true
 * count then divides by as though they were decks: at 2 the divisor is about half what it
 * should be and every count roughly doubles, and at 0 the division by zero rounds up to
 * Integer.MAX_VALUE and every count reads as zero. Only 1 has ever been used, so it is the
 * only value accepted.
 */
public class DeckEstimationTest {

    /** Three hundred cards is 5.77 decks, which rounds up to 6. */
    @Test
    public void threeHundredCardsLeftIsSixDecks() {
        CountMethod hiLo = CountMethod.getHiLoValue(1);
        assertEquals(6, CountMethod.getNumDecksRoundedUp(300, hiLo.deckEstimationPrecision, 52),
                "300 cards left is 5.77 decks, and the true count divides by 6");
    }

    @Test
    public void aDeckEstimationPrecisionOtherThanOneIsRefused() {
        for (int precision : new int[]{2, 0}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> CountMethod.getHiLoValue(precision),
                    "deck precision " + precision + " was accepted, but it rescales every"
                            + " true count");
            assertTrue(e.getMessage().contains("must be 1"),
                    "the message should say the precision has to be 1: " + e.getMessage());
        }
    }

    /** A stored count method goes through the same constructor, so it is held to the same rule. */
    @Test
    public void aStoredCountMethodWithAnotherPrecisionIsRefusedOnLoad() {
        BasicDBObject stored = CountMethod.getHiLoValue(1).getDBObject();
        stored.put("deckEstimationPrecision", 2);
        assertThrows(IllegalArgumentException.class,
                () -> CountMethod.getCountMethodFromObject(stored),
                "a count method stored at precision 2 loaded, and every count it gives is"
                        + " doubled");
    }
}
