import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A count has to survive being stored, and the shoe has to remember how big it was.
 *
 * Every decision in the table is keyed on a GranularCount, so a count that does not
 * round-trip silently splits one bucket into two and starves both. And startingSize is
 * the denominator behind the true count, so a shoe that forgets it changes the scale of
 * every count that follows.
 */
public class CountEncodingTest {

    /** 0.3 is not exactly representable in binary, so truncating the tenths digit loses it. */
    @Test
    public void countOfThreeTenthsRoundTripsAsThreeTenths() {
        GranularCount gc = new GranularCount(0.3);
        assertEquals(0, gc.getUnits());
        assertEquals(3, gc.getFirstDecimal(), "0.3 must have a tenths digit of 3");
        assertEquals(0, gc.getSecondDecimal());
        assertEquals(30, gc.getHundredths());
    }

    /** The same digit loss, stated as a value error. */
    @Test
    public void countPreservesItsValue() {
        for (double d : new double[]{0.1, 0.2, 0.3, 0.7, 1.1, 2.9, -0.3, -1.7}) {
            GranularCount gc = new GranularCount(d);
            assertEquals(d, gc.getDoubleFromCount(), 1e-9,
                    "count " + d + " did not survive encoding");
        }
    }

    /** Two counts that are numerically equal must be the same hash-map key. */
    @Test
    public void equalCountsAreTheSameKey() {
        GranularCount fromDouble = new GranularCount(0.3);
        GranularCount fromDigits = new GranularCount(0, 3, 0);
        assertEquals(fromDigits, fromDouble, "0.3 must key the same cell however it is built");
        assertEquals(fromDigits.hashCode(), fromDouble.hashCode());
    }

    /** The string form is the database key, so it has to survive a round trip too. */
    @Test
    public void countRoundTripsThroughItsStringForm() {
        for (double d : new double[]{0.0, 0.3, 2.5, -3.0, -0.4}) {
            GranularCount original = new GranularCount(d);
            GranularCount reparsed = GranularCount.getCountFromString(original.getStringFromCount());
            assertEquals(original, reparsed, "count " + d + " did not survive the string form");
        }
    }

    /** Whole counts stay whole. */
    @Test
    public void wholeCountsHaveNoDecimals() {
        for (int i = -5; i <= 5; i++) {
            GranularCount gc = new GranularCount((double) i);
            assertEquals(i, gc.getUnits());
            assertEquals(0, gc.getFirstDecimal(), "count " + i + " picked up a tenths digit");
            assertEquals(0, gc.getSecondDecimal(), "count " + i + " picked up a hundredths digit");
        }
    }

    /**
     * The stored key layout, pinned exactly.
     *
     * These strings are the keys cells live under in the database, so a change here
     * orphans every table already saved. The negative cases carry the sign on each digit,
     * which is what the previous code wrote.
     */
    @Test
    public void storedKeyLayoutIsUnchanged() {
        assertEquals("0&0&0", new GranularCount(0.0).getStringFromCount());
        assertEquals("5&0&0", new GranularCount(5.0).getStringFromCount());
        assertEquals("-5&0&0", new GranularCount(-5.0).getStringFromCount());
        assertEquals("2&5&0", new GranularCount(2.5).getStringFromCount());
        assertEquals("-2&-5&0", new GranularCount(-2.5).getStringFromCount());
        assertEquals("0&0&-5", new GranularCount(-0.05).getStringFromCount());
    }

    /** Keys written by the previous code still read back to the value they meant. */
    @Test
    public void keysWrittenByTheOldCodeStillParse() {
        assertEquals(-2.5, GranularCount.getCountFromString("-2&-5&0").getDoubleFromCount(), 1e-9);
        assertEquals(5.0, GranularCount.getCountFromString("5&0&0").getDoubleFromCount(), 1e-9);
        // Including the ones the old truncation bug produced: 0.3 was written as 0&2&9.
        assertEquals(0.29, GranularCount.getCountFromString("0&2&9").getDoubleFromCount(), 1e-9);
    }

    /** Digits that disagree with each other used to be constructible and meaningless. */
    @Test
    public void overflowingDigitsCarryInsteadOfContradicting() {
        GranularCount gc = new GranularCount(0, 47, 3);
        assertEquals(4.73, gc.getDoubleFromCount(), 1e-9, "47 tenths and 3 hundredths is 4.73");
        assertEquals(4, gc.getUnits());
        assertEquals(7, gc.getFirstDecimal());
        assertEquals(3, gc.getSecondDecimal());
        assertEquals(new GranularCount(4.73), gc, "however it is built, 4.73 is one key");
    }

    /** Ordering follows the value, on both sides of zero. */
    @Test
    public void countsOrderByValue() {
        assertTrue(new GranularCount(-2.5).compareTo(new GranularCount(-2.0)) < 0);
        assertTrue(new GranularCount(-2.0).compareTo(new GranularCount(-1.5)) < 0);
        assertTrue(new GranularCount(-0.5).compareTo(new GranularCount(0.0)) < 0);
        assertTrue(new GranularCount(0.0).compareTo(new GranularCount(0.5)) < 0);
        assertTrue(new GranularCount(2.0).compareTo(new GranularCount(2.5)) < 0);
        assertEquals(0, new GranularCount(1.25).compareTo(new GranularCount(1.25)));
    }

    /** A negative fractional count renders as a number, not as two spliced digits. */
    @Test
    public void negativeFractionalCountsPrintReadably() {
        assertEquals("-2.50", new GranularCount(-2.5).countToCellString());
        assertEquals("2.50", new GranularCount(2.5).countToCellString());
        assertEquals("-5.00", new GranularCount(-5.0).countToCellString());
        assertEquals("0.00", new GranularCount(0.0).countToCellString());
    }

    /** The random count grid lands on the grain, including grains that drift in doubles. */
    @Test
    public void randomCountsLandOnTheGrid() {
        for (int i = 0; i < 200; i++) {
            GranularCount gc = GranularCount.getRandomCount(-5, 5, 0.1);
            assertEquals(0, gc.getHundredths() % 10,
                    gc + " is not a whole tenth");
            assertTrue(gc.isCountInBoundaries(-5, 5), gc + " is outside [-5, 5]");
        }
    }

    /** A shoe knows how big it started, and a copy of it is still the same shoe. */
    @Test
    public void deepCopyKeepsTheOriginalStartingSize() {
        CompositeCardSource shoe = CompositeCardSource.getMultiDeck(8);
        assertEquals(416, shoe.startingSize, "8 decks is 416 cards");

        // Burn a few cards, as dealing does.
        shoe.cards.remove(0);
        shoe.cards.remove(0);
        shoe.cards.remove(0);

        CompositeCardSource copy = shoe.deepCopy();
        assertEquals(416, copy.startingSize,
                "a copy of a partly dealt shoe still started life with 416 cards");
        assertEquals(413, copy.cards.size(), "the copy holds the cards that are left");
    }

    /**
     * startingSize feeds deckSize, which is the denominator of the true count.
     * If a copy resets it, the true count silently changes scale mid-run.
     */
    @Test
    public void trueCountDenominatorSurvivesAShoeCopy() {
        CountMethod hiLo = CountMethod.getHiLoValue(1);
        Table table = new Table(8, hiLo);

        int deckSizeBefore = table.gameDeck.startingSize / 8;
        table.gameDeck.cards.remove(0);
        table.gameDeck = table.gameDeck.deepCopy();
        int deckSizeAfter = table.gameDeck.startingSize / 8;

        assertEquals(52, deckSizeBefore, "one deck is 52 cards");
        assertEquals(deckSizeBefore, deckSizeAfter,
                "one deck is still 52 cards after copying the shoe");
    }

    /** Copying repeatedly, as the penetration retry loop does, must not drift. */
    @Test
    public void repeatedShoeCopiesDoNotDrift() {
        CompositeCardSource shoe = CompositeCardSource.getMultiDeck(8);
        for (int i = 0; i < 10; i++) {
            shoe.cards.remove(0);
            shoe = shoe.deepCopy();
        }
        assertEquals(416, shoe.startingSize,
                "ten rounds of deal-and-copy must not shrink the shoe's starting size");
    }
}
