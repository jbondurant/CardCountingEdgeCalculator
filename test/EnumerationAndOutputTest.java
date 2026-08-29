import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two small things that were quietly wrong: the enumeration used to deal a player a
 * specific starting hand, and the markup of the generated strategy tables.
 */
public class EnumerationAndOutputTest {

    // ---------------------------------------------------------------- enumeration

    /**
     * The enumeration of two-card hands making a given total is used to deal the player
     * a specific starting hand. Dropping the last card of the shoe biases it.
     */
    @Test
    public void twoCardEnumerationConsidersTheLastCardInTheShoe() {
        ArrayList<Card> cards = new ArrayList<>();
        cards.add(new Card(Rank.TWO, Suit.CLUBS));
        cards.add(new Card(Rank.THREE, Suit.SPADES));
        ArrayList<CardSource> sources = new ArrayList<>();
        sources.add(new CardSequence(cards));
        CompositeCardSource shoe = new CompositeCardSource(sources);

        HandEncoding hard5 = new HandEncoding(false, false, 5);
        ArrayList<DoubleRanks> pairs =
                HandEncoding.allPossibleDoubleRanksForHandEncoding(shoe, hard5);

        assertNotNull(pairs, "a full shoe must be enumerable");
        assertEquals(1, pairs.size(),
                "2+3 is a hard 5 and must be found even though the 3 is the last card");
    }

    /** The enumeration must only produce hands with the requested total. */
    @Test
    public void twoCardEnumerationOnlyReturnsMatchingHands() {
        CompositeCardSource shoe = CompositeCardSource.getMultiDeck(1);
        HandEncoding hard16 = new HandEncoding(false, false, 16);
        ArrayList<DoubleRanks> pairs =
                HandEncoding.allPossibleDoubleRanksForHandEncoding(shoe, hard16);

        assertNotNull(pairs);
        assertFalse(pairs.isEmpty(), "hard 16 is reachable with two cards");
        for (DoubleRanks dr : pairs) {
            ArrayList<Card> hand = new ArrayList<>();
            hand.add(new Card(dr.r1, Suit.CLUBS));
            hand.add(new Card(dr.r2, Suit.SPADES));
            assertEquals(hard16, new HandEncoding(hand),
                    dr.r1 + "+" + dr.r2 + " is not a hard 16");
        }
    }

    /** Every pair of positions in the shoe is considered exactly once. */
    @Test
    public void twoCardEnumerationCoversEveryPair() {
        ArrayList<Card> cards = new ArrayList<>();
        cards.add(new Card(Rank.FIVE, Suit.CLUBS));
        cards.add(new Card(Rank.FIVE, Suit.SPADES));
        cards.add(new Card(Rank.FIVE, Suit.HEARTS));
        ArrayList<CardSource> sources = new ArrayList<>();
        sources.add(new CardSequence(cards));
        CompositeCardSource shoe = new CompositeCardSource(sources);

        // 5+5 is a splittable ten, and three fives make three distinct pairs.
        HandEncoding pairOfFives = new HandEncoding(false, true, 10);
        ArrayList<DoubleRanks> pairs =
                HandEncoding.allPossibleDoubleRanksForHandEncoding(shoe, pairOfFives);

        assertNotNull(pairs);
        assertEquals(3, pairs.size(), "three fives give three distinct pairs");
    }

    // --------------------------------------------------------------- generated html

    /** The generated tables must open the body they later close. */
    @Test
    public void generatedTablesOpenTheirBody() throws Exception {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        CountMethod cm = CountMethod.getHiLoValue(1);
        SimulationParameters sp = new SimulationParameters(hr, cm, 1.0, 10, 10, -5, 5);
        SimulationTable table = new SimulationTable(sp, "test");

        // Fill every hard-total cell the renderer walks, at the count it colours by.
        for (int total = 5; total <= 21; total++) {
            for (int up = 2; up <= 11; up++) {
                Rank upRank = up == 11 ? Rank.ACE : rankWorth(up);
                table.insertEvent(new EventResult(
                        -0.1, new HandEncoding(false, false, total), upRank,
                        PlayerMove.Stand, new GranularCount(0.0)));
            }
        }

        ArrayList<String> lines = table.getHardCountTableStrings();
        assertTrue(lines.contains("<tbody>"),
                "the rendered table never opened <tbody>");
    }

    /**
     * A cell with no bucket at true count zero still renders.
     *
     * printTables is worth running against a partly built table, and colouring a cell by
     * the move at a count of zero used to dereference a bucket that need not exist yet.
     */
    @Test
    public void aCellWithNothingAtCountZeroStillRenders() {
        DecisionCell dc = new DecisionCell();
        dc.insertEvent(new EventResult(
                -0.2, new HandEncoding(false, false, 16), Rank.TEN,
                PlayerMove.Stand, new GranularCount(3.0)));

        String tag = assertDoesNotThrow(dc::getCellColorTag,
                "a cell that has nothing at count zero must still render");
        assertEquals("unmeasured", tag);
    }

    /** With a bucket at zero it is coloured by the move there. */
    @Test
    public void aCellWithACountZeroBucketIsColouredByIt() {
        DecisionCell dc = new DecisionCell();
        dc.insertEvent(new EventResult(
                -0.2, new HandEncoding(false, false, 16), Rank.TEN,
                PlayerMove.Stand, new GranularCount(0.0)));

        assertEquals("stand", dc.getCellColorTag());
    }

    private static Rank rankWorth(int points) {
        for (Rank r : Rank.values()) {
            if (r != Rank.ACE && r.getRankpoints() == points) {
                return r;
            }
        }
        throw new IllegalArgumentException("no rank worth " + points);
    }
}
