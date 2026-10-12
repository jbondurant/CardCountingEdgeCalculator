import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BruteForceRound.distributionOfFirstMove: the whole distribution of a round's payoff, on the
 * same walk as valueOfFirstMove.
 *
 * The swings on the results page rest on a round's second moment, and the simulator's histogram
 * of payoffs is held to this distribution on small shoes (PLAN_COMPUTE.md 2.4, check 1). So the
 * distribution has to be right on its own terms: its probabilities add up to 1, its mean is the
 * value BruteForceRound has always given, and on shoes small enough to follow by hand it is the
 * distribution the comment above each assertion works out. Writing the walk over what it adds
 * up must also leave valueOfFirstMove exactly as it was, since ExactRound is held to it.
 *
 * Bugs planted in a scratch copy of BruteForceRound on 12 October, each caught here: the
 * distribution not renormalized over the hole cards that are not a dealer natural (2 tests
 * fail); a branch's probabilities added without its weight p (5 fail); a branch's payoffs put
 * over the sum's instead of added to them (4 fail); the value's sum rounded another way,
 * (3 sum + 3 p v) / 3, which moves no value by 1e-12 but changes bits (the fingerprint fails).
 * One plant changed nothing: keeping -0.0 as a key of its own, since no payoff sums to -0.0
 * (a sum starts at +0.0 and adds nonzero amounts); the mean test would catch one if it came.
 */
public class BruteForceDistributionTest {

    private static final double EPS = 1e-12;

    /**
     * BruteForceCases.fingerprint of the first 2,000 cases, computed with BruteForceRound as it
     * was at acb6fee, before its walk was written over a Tally: 1,943 values to the bit and 57
     * throws, the same classes.
     */
    private static final String FINGERPRINT_AT_ACB6FEE =
            "13b73cbd8e7eb08358ed1e0decdfc206e7773f46d09e2b4e618f56145fff69d0";

    @Test
    void valueOfFirstMoveIsWhatItWasToTheBit() {
        assertEquals(FINGERPRINT_AT_ACB6FEE, BruteForceCases.fingerprint(BruteForceCases.cases(2000)),
                "valueOfFirstMove no longer gives, to the bit, what it gave before the walk was written over a Tally");
    }

    @Test
    void theMeanIsTheValueAndTheProbabilitiesAddUpToOneOnEveryCase() {
        int valued = 0;
        int split = 0;
        for (BruteForceCases.Case c : BruteForceCases.cases(2000)) {
            BruteForceRound v = new BruteForceRound(c.rules);
            double value;
            try {
                value = v.valueOfFirstMove(c.shoe, c.p1, c.p2, c.up, c.first, c.policy);
            } catch (RuntimeException e) {
                RuntimeException d = assertThrows(RuntimeException.class,
                        () -> v.distributionOfFirstMove(c.shoe, c.p1, c.p2, c.up, c.first, c.policy), c::toString);
                assertEquals(e.getClass(), d.getClass(), c.toString());
                continue;
            }
            SortedMap<Double, Double> dist = v.distributionOfFirstMove(c.shoe, c.p1, c.p2, c.up, c.first, c.policy);
            double total = 0;
            double mean = 0;
            int most = 2 * Math.max(c.rules.maxHandsAces, c.rules.maxHandsNotAces);
            for (Map.Entry<Double, Double> e : dist.entrySet()) {
                assertTrue(e.getValue() > 0, c + ": payoff " + e.getKey() + " has probability " + e.getValue());
                assertTrue(Math.abs(e.getKey()) <= most, c + ": payoff " + e.getKey() + " is beyond " + most);
                assertFalse(e.getKey() == 0 && 1 / e.getKey() < 0, c + ": -0.0 is a key of its own");
                total += e.getValue();
                mean += e.getKey() * e.getValue();
            }
            assertEquals(1, total, EPS, c.toString());
            assertEquals(value, mean, EPS, c.toString());
            valued++;
            if (c.first == PlayerMove.Split) {
                split++;
            }
        }
        assertEquals(1943, valued, "the cases valued");
        assertTrue(split >= 100, "only " + split + " cases split on the first move");
    }

    private static SortedMap<Double, Double> dist(double... payoffThenProbability) {
        SortedMap<Double, Double> m = new TreeMap<>();
        for (int i = 0; i < payoffThenProbability.length; i += 2) {
            m.put(payoffThenProbability[i], payoffThenProbability[i + 1]);
        }
        return m;
    }

    private static void assertDistribution(SortedMap<Double, Double> expected, SortedMap<Double, Double> actual) {
        assertEquals(expected.keySet(), actual.keySet(), "the payoffs");
        for (Map.Entry<Double, Double> e : expected.entrySet()) {
            assertEquals(e.getValue(), actual.get(e.getKey()), EPS, "the probability of " + e.getKey());
        }
    }

    private static int[] shoe(int... rankThenCount) {
        int[] s = new int[11];
        for (int i = 0; i < rankThenCount.length; i += 2) {
            s[rankThenCount[i]] += rankThenCount[i + 1];
        }
        return s;
    }

    private static final RoundPolicy STAND = d -> PlayerMove.Stand;

    /** S17, no double after split, 4 hands, no surrender (BruteForceCases rules 0). */
    private static BruteForceRound s17() {
        return new BruteForceRound(BruteForceCases.rules(0));
    }

    @Test
    void aForcedShoeIsOnePayoff() {
        // Tens only, 16 against 10: the hole card is a ten, the dealer stands on 20, and 16 loses.
        assertDistribution(dist(-1, 1), s17().distributionOfFirstMove(shoe(10, 8), 10, 6, 10, PlayerMove.Stand, STAND));
        // The player's natural against a 9 with a ten in the hole: 3:2.
        assertDistribution(dist(1.5, 1), s17().distributionOfFirstMove(shoe(10, 3), 1, 10, 9, PlayerMove.Stand, STAND));
        // Surrender (rules 1 offer it): half the stake, whatever comes.
        assertDistribution(dist(-0.5, 1), new BruteForceRound(BruteForceCases.rules(1))
                .distributionOfFirstMove(shoe(10, 4), 10, 6, 10, PlayerMove.Surrender, STAND));
    }

    @Test
    void theDealersDrawSplitsTheOutcome() {
        // 16 stands against a 10; the shoe is two tens and a five. Hole ten (2/3): 20, and 16
        // loses. Hole five (1/3): 15, the dealer draws a ten and busts, and 16 wins.
        assertDistribution(dist(-1, 2.0 / 3, 1, 1.0 / 3),
                s17().distributionOfFirstMove(shoe(10, 2, 5, 1), 10, 6, 10, PlayerMove.Stand, STAND));
    }

    @Test
    void aDoubleWinsOrLosesTwo() {
        // 6,5 doubles against a 10 from two tens and a five. Hole ten (2/3): dealer 20, and the
        // double draws a ten (1/2, 21 wins 2) or the five (1/2, 16 loses 2). Hole five (1/3):
        // the double draws a ten for 21, the dealer 15 draws the last ten and busts: wins 2.
        // So +2 with 2/3 x 1/2 + 1/3 = 2/3, and -2 with 1/3.
        assertDistribution(dist(-2, 1.0 / 3, 2, 2.0 / 3),
                s17().distributionOfFirstMove(shoe(10, 2, 5, 1), 6, 5, 10, PlayerMove.Double, STAND));
    }

    @Test
    void splitHandsAreSettledTogether() {
        // 8,8 splits against a 9, both hands stand; the shoe is three tens and an ace.
        // Hole ten (3/4), dealer 19, left 10,10,A. The first hand draws a ten (2/3): the second
        // draws a ten (1/2, 18 and 18 both lose: -2) or the ace (1/2, 18 loses, soft 19
        // pushes: -1). The first draws the ace (1/3): the second draws a ten (soft 19 pushes,
        // 18 loses: -1). Hole ace (1/4), dealer soft 20, both hands draw tens and lose: -2.
        // -2 with 3/4 x 2/3 x 1/2 + 1/4 = 1/2; -1 with 3/4 x (2/3 x 1/2 + 1/3) = 1/2.
        assertDistribution(dist(-2, 0.5, -1, 0.5),
                s17().distributionOfFirstMove(shoe(10, 3, 1, 1), 8, 8, 9, PlayerMove.Split, STAND));
    }

    @Test
    void aDealerNaturalIsLeftOutAndTheRestRenormalized() {
        // 19 stands against a 10 from an ace, a ten and a six. The ace in the hole (1/3) is a
        // dealer natural and is not played. Ten (1/3): 20 beats 19. Six (1/3): 16 draws the ace
        // (hard 17) or the ten (bust), and 19 wins either way. Given no natural: -1 and +1, a
        // half each.
        assertDistribution(dist(-1, 0.5, 1, 0.5),
                s17().distributionOfFirstMove(shoe(1, 1, 10, 1, 6, 1), 10, 9, 10, PlayerMove.Stand, STAND));
    }

    @Test
    void itRefusesWhatValueOfFirstMoveRefuses() {
        BruteForceRound v = s17();
        // An illegal first move: a natural can only stand.
        assertThrows(IllegalArgumentException.class,
                () -> v.distributionOfFirstMove(shoe(10, 3), 1, 10, 9, PlayerMove.Hit, STAND));
        // No hole card can be dealt from an empty shoe.
        assertThrows(IllegalStateException.class,
                () -> v.distributionOfFirstMove(new int[11], 10, 6, 9, PlayerMove.Stand, STAND));
        // Every hole card gives the dealer a natural.
        assertThrows(IllegalArgumentException.class,
                () -> v.distributionOfFirstMove(shoe(1, 2), 10, 6, 10, PlayerMove.Stand, STAND));
    }

    /** The cases cover what the distribution must handle: splits, doubles, surrenders, naturals, throws. */
    @Test
    void theCasesCoverEveryKindOfFirstMove() {
        List<BruteForceCases.Case> cases = BruteForceCases.cases(2000);
        int[] moves = new int[PlayerMove.values().length];
        for (BruteForceCases.Case c : cases) {
            moves[c.first.ordinal()]++;
        }
        for (PlayerMove m : PlayerMove.values()) {
            assertTrue(moves[m.ordinal()] >= 30, m + " is the first move of only " + moves[m.ordinal()] + " cases");
        }
    }
}
