import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A count setting the engine cannot run is refused when it is made, not hours into a run.
 *
 * Any grid used to be accepted, and the ones below fail late, each in a way that costs work.
 *
 * The table run picks a target from the grid minC, minC + grain, ..., maxC and reshuffles
 * the shoe until the true count lands on it. That loop has no other way out. A true count
 * rounds to a multiple of the grain counted from zero, so when the grain does not divide
 * the bounds most of the grid sits between the values it can land on. And even when it
 * does divide them, the true count is a running count over two to eight decks, which
 * cannot land everywhere a fine grain asks it to. Either way the loop never returns, and
 * the session never reaches its save.
 *
 * The finished table is saved as a single MongoDB document, and MongoDB refuses one over
 * 16 MB. A grid fine enough to cross that fails at the save, after the session's work is
 * done, and the work goes with it.
 */
public class CountGridTest {

    private static SimulationParameters montreal(double grain, int minC, int maxC) {
        return withRules(HouseRules.getMtlCasino25MinBlackjackParams(75), grain, minC, maxC);
    }

    private static SimulationParameters withRules(HouseRules hr, double grain, int minC, int maxC) {
        return new SimulationParameters(hr, CountMethod.getHiLoValue(1), grain, 10, 10, minC, maxC);
    }

    // ------------------------------------------------------------- what is accepted

    /** The setting the Montreal tables are built at. */
    @Test
    public void theMontrealGridIsAccepted() {
        assertDoesNotThrow(() -> montreal(1.0, -5, 5),
                "whole counts over [-5, 5] are what every table so far was built at");
    }

    @Test
    public void aHalfCountGridIsAccepted() {
        assertDoesNotThrow(() -> montreal(0.5, -5, 5),
                "every half count from -5 to 5 is somewhere the true count can land");
    }

    /**
     * Acceptance has to mean the run can actually finish, so this drives the real loop the
     * table run uses at every bucket of each accepted grid, after dealing four cards the
     * way setCards does. If the check let through a bucket the shoe cannot reach, this
     * would sit in that loop until the timeout.
     */
    @Test
    public void theTableRunLandsOnEveryBucketOfAnAcceptedGrid() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        for (double grain : new double[]{1.0, 0.5}) {
            montreal(grain, -5, 5);
            int step = (int) Math.round(grain * 100);
            for (int h = -500; h <= 500; h += step) {
                GranularCount target = new GranularCount(0, 0, h);
                assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                    Table table = new Table(hr.numDecks, CountMethod.getHiLoValue(1));
                    table.givePlayer2RandomCardsAndRunCountMaybe(false);
                    table.giveDealerRandomHandAndRunCountMaybe(false);
                    int deckSize = table.gameDeck.startingSize / hr.numDecks;
                    table.removeRandomAmountCardsAndRunCount(
                            hr.penetrationPercentage, target, deckSize, grain, -5, 5);
                    assertEquals(target, table.getGranularCount(deckSize, grain, -5, 5));
                }, "grain " + grain + " was accepted, but the table run could not land on "
                        + target);
            }
        }
    }

    // --------------------------------------------------------- the grid has to line up

    /**
     * None of these divides 5. At 0.3 the run would ask for -4.70, -4.40 and so on while
     * the true count only ever rounds to -4.80, -4.50, ..., so 33 of its 34 targets are
     * places it can never land.
     */
    @Test
    public void aGrainThatDoesNotDivideTheBoundsIsRefused() {
        for (double grain : new double[]{0.3, 0.4, 0.75, 2.0}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> montreal(grain, -5, 5),
                    "grain " + grain + " over [-5, 5] was accepted, but the table run would"
                            + " target buckets the true count never rounds to");
            assertTrue(e.getMessage().contains("divide"),
                    "grain " + grain + " should be refused for not dividing the bounds: "
                            + e.getMessage());
        }
    }

    /** A count is held to two decimal places, so a grain has to be a whole number of them. */
    @Test
    public void aGrainThatIsNotAPositiveWholeNumberOfHundredthsIsRefused() {
        for (double grain : new double[]{0.125, 0.0, -1.0}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> montreal(grain, -5, 5),
                    "grain " + grain + " was accepted, but it is not a positive whole number"
                            + " of hundredths");
            assertTrue(e.getMessage().contains("hundredths"),
                    "the message should say what a grain has to be: " + e.getMessage());
        }
    }

    // ------------------------------------------------- every bucket has to be reachable

    /**
     * 0.05 divides both bounds, so the grid lines up, but the true count is too coarse for
     * it. With at most eight decks left no true count lies strictly between 0 and 1/8, and
     * 1/8 rounds to 0.10, so nothing ever lands on 0.05.
     */
    @Test
    public void aGrainFinerThanTheTrueCountCanResolveIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> montreal(0.05, -5, 5),
                "grain 0.05 was accepted, but some of its buckets can never be landed on");
        assertTrue(e.getMessage().contains("0.05"),
                "the message should name a bucket nothing lands on, and 0.05 is one: "
                        + e.getMessage());
    }

    /**
     * At 5% penetration of eight decks at most seventeen cards are burned and eight decks
     * are always left, so the true count never gets past 17/8, which rounds to 2. The
     * buckets from 3 to 5 are out of reach at any grain.
     */
    @Test
    public void aRangeWiderThanTheShoeCanReachIsRefused() {
        HouseRules shallow = HouseRules.getMtlCasino25MinBlackjackParams(5);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> withRules(shallow, 1.0, -5, 5),
                "[-5, 5] at 5% penetration was accepted, but the true count cannot get to 5");
        assertTrue(e.getMessage().contains("5.00"),
                "the message should name 5.00 as a bucket nothing lands on: " + e.getMessage());
    }

    // ------------------------------------------------------ the table has to be saveable

    /**
     * Every bucket of 0.1 over [-5, 5] is reachable, so the run would work, but 101 buckets
     * across all 360 situations make a table MongoDB will not store as one document.
     */
    @Test
    public void aGridTooLargeToSaveIsRefusedForThatReason() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> montreal(0.1, -5, 5),
                "grain 0.1 over [-5, 5] was accepted, but its finished table cannot be saved");
        assertTrue(e.getMessage().contains("16,777,216"),
                "the refusal should be about the document size limit: " + e.getMessage());
        assertFalse(e.getMessage().contains("land on"),
                "every bucket of this grid is reachable, so that is not the reason: "
                        + e.getMessage());
    }
}
