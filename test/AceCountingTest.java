import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a hand's total and softness are kept as cards arrive.
 *
 * "Soft" means one ace in the hand is being counted as 11. A hand never holds two aces at
 * 11, because that is 22 before anything else is dealt, so soft means exactly one of them
 * is -- and that is the whole subtlety. Adding an ace to a soft hand does not make it
 * hard: one of the two aces goes down to 1 and the other stays at 11. A,A is soft 12 and
 * A,6,A is soft 18.
 *
 * Both solvers used to add an ace as 11 and then demote it if the hand busted, clearing
 * the soft flag as they went, which turned every multi-ace hand hard. Under H17 that is a
 * behaviour change and not just a label: the dealer must hit a soft 17 and stands on a
 * hard one.
 *
 * The two solvers carry their own copy of this arithmetic, so it is checked here against a
 * count made from scratch, and against each other.
 */
public class AceCountingTest {

    // ------------------------------------------------------------------ the reference

    /**
     * Total and softness worked out from the whole hand at once: count every ace as one,
     * then promote a single ace to eleven if the hand can afford it.
     *
     * This is deliberately not how either solver does it. The solvers fold one card at a
     * time and have to carry the softness forward, which is where the bug lived.
     */
    private static int[] countFromScratch(List<Integer> ranks) {
        int hard = 0;
        boolean holdsAce = false;
        for (int rank : ranks) {
            hard += rank == 1 ? 1 : rank;
            holdsAce = holdsAce || rank == 1;
        }
        if (holdsAce && hard + 10 <= 21) {
            return new int[]{hard + 10, 1};
        }
        return new int[]{hard, 0};
    }

    /** The same hand folded one card at a time, the way the solver does it. */
    private static int[] foldWithSolver(List<Integer> ranks) {
        int total = 0;
        boolean soft = false;
        for (int rank : ranks) {
            RandomVsOptimalReport.Hand h = RandomVsOptimalReport.addCard(total, soft, rank);
            total = h.total;
            soft = h.soft;
        }
        return new int[]{total, soft ? 1 : 0};
    }

    private static String name(List<Integer> ranks) {
        StringBuilder sb = new StringBuilder();
        for (int rank : ranks) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(rank == 1 ? "A" : String.valueOf(rank));
        }
        return sb.toString();
    }

    private static void assertHand(int expectedTotal, boolean expectedSoft,
                                   int total, boolean soft, String what) {
        assertEquals(expectedTotal, total, what + " came to the wrong total");
        assertEquals(expectedSoft, soft, what + " came out "
                + (soft ? "soft" : "hard") + " and should be "
                + (expectedSoft ? "soft" : "hard"));
    }

    // ------------------------------------------------------------- the case that broke

    /** Two aces are eleven and one, so the hand is soft twelve rather than hard twelve. */
    @Test
    public void twoAcesMakeASoftTwelve() {
        RandomVsOptimalReport.Hand h = RandomVsOptimalReport.addCard(11, true, 1);
        assertHand(12, true, h.total, h.soft, "A,A");
    }

    /**
     * The same thing one card later, where it changes what the dealer must do. A,A,5 is a
     * soft 17, and a dealer hits a soft 17 under this ruleset.
     */
    @Test
    public void aTwoAceSeventeenIsSoft() {
        int[] hand = foldWithSolver(List.of(1, 1, 5));
        assertHand(17, true, hand[0], hand[1] == 1, "A,A,5");
    }

    /** And with the ace arriving last instead of first. */
    @Test
    public void addingAnAceToASoftHandLeavesItSoft() {
        RandomVsOptimalReport.Hand h = RandomVsOptimalReport.addCard(17, true, 1);
        assertHand(18, true, h.total, h.soft, "A,6 plus an ace");
    }

    // ------------------------------------------------------------ the ordinary cases

    /** A soft hand that can no longer afford eleven gives the ace up and goes hard. */
    @Test
    public void aSoftHandGoesHardWhenElevenNoLongerFits() {
        RandomVsOptimalReport.Hand h = RandomVsOptimalReport.addCard(17, true, 10);
        assertHand(17, false, h.total, h.soft, "A,6 plus a ten");
    }

    /** A hard hand can turn soft again if an ace arrives with room for it. */
    @Test
    public void aHardHandTurnsSoftWhenAnAceFits() {
        RandomVsOptimalReport.Hand h = RandomVsOptimalReport.addCard(10, false, 1);
        assertHand(21, true, h.total, h.soft, "a hard ten plus an ace");
    }

    /** And stays hard when it does not. */
    @Test
    public void aHardHandStaysHardWhenAnAceDoesNotFit() {
        RandomVsOptimalReport.Hand h = RandomVsOptimalReport.addCard(12, false, 1);
        assertHand(13, false, h.total, h.soft, "a hard twelve plus an ace");
    }

    /** Two demotions in a row: A,10 is a soft 21, and another ace takes it to a hard 12. */
    @Test
    public void aSoftTwentyOnePlusAnAceIsAHardTwelve() {
        int[] hand = foldWithSolver(List.of(1, 10, 1));
        assertHand(12, false, hand[0], hand[1] == 1, "A,10,A");
    }

    /** Busting is reported as the hard total, so callers can see how far over it went. */
    @Test
    public void aBustedHandIsHard() {
        RandomVsOptimalReport.Hand h = RandomVsOptimalReport.addCard(16, false, 10);
        assertHand(26, false, h.total, h.soft, "a hard sixteen plus a ten");
    }

    // ------------------------------------------------------------------- the properties

    /** A soft hand counts an ace as eleven, so it cannot be over twenty-one. */
    @Test
    public void aSoftHandIsNeverBust() {
        for (int total = 2; total <= 30; total++) {
            for (boolean soft : new boolean[]{false, true}) {
                if (soft && (total < 11 || total > 21)) {
                    continue;       // not a hand that can arise
                }
                for (int rank = 1; rank <= 10; rank++) {
                    RandomVsOptimalReport.Hand h =
                            RandomVsOptimalReport.addCard(total, soft, rank);
                    if (h.soft) {
                        assertTrue(h.total <= 21,
                                "a soft " + h.total + " is impossible: it came from "
                                        + (soft ? "soft " : "hard ") + total
                                        + " plus a " + rank);
                        assertTrue(h.total >= 11,
                                "a soft " + h.total + " has no ace counted as eleven");
                    }
                }
            }
        }
    }

    /**
     * Folding cards one at a time has to agree with counting the finished hand, for every
     * hand of up to five cards. This is the check that fails outright on the old version:
     * A,A alone comes out hard.
     */
    @Test
    public void foldingOneCardAtATimeMatchesCountingTheWholeHand() {
        for (List<Integer> ranks : everyHandUpTo(5)) {
            int[] expected = countFromScratch(ranks);
            int[] actual = foldWithSolver(ranks);
            assertHand(expected[0], expected[1] == 1, actual[0], actual[1] == 1,
                    name(ranks));
        }
    }

    /** The two solvers keep separate copies of this, and they have to agree. */
    @Test
    public void bothSolversCountTheSameWay() {
        for (int total = 2; total <= 21; total++) {
            for (boolean soft : new boolean[]{false, true}) {
                if (soft && total < 11) {
                    continue;
                }
                for (int rank = 1; rank <= 10; rank++) {
                    RandomVsOptimalReport.Hand a =
                            RandomVsOptimalReport.addCard(total, soft, rank);
                    int[] b = PerRoundVersusPerHandReport.addCard(total, soft ? 1 : 0, rank);
                    assertHand(a.total, a.soft, b[0], b[1] == 1,
                            (soft ? "soft " : "hard ") + total + " plus a " + rank
                                    + ", where the two solvers disagree");
                }
            }
        }
    }

    /** Every hand of two to n cards, as rank lists. Ten stands for all ten-valued cards. */
    private static List<List<Integer>> everyHandUpTo(int maxCards) {
        List<List<Integer>> hands = new ArrayList<>();
        List<List<Integer>> frontier = new ArrayList<>();
        frontier.add(new ArrayList<>());
        for (int length = 1; length <= maxCards; length++) {
            List<List<Integer>> next = new ArrayList<>();
            for (List<Integer> prefix : frontier) {
                for (int rank = 1; rank <= 10; rank++) {
                    List<Integer> extended = new ArrayList<>(prefix);
                    extended.add(rank);
                    next.add(extended);
                    if (length >= 2) {
                        hands.add(extended);
                    }
                }
            }
            frontier = next;
        }
        return hands;
    }
}
