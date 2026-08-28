import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The memoization order: every hand a hand can turn into is solved before it is played.
 *
 * This is what lets the simulator evaluate hitting a hard 12 by looking up whatever the
 * card produced, and it is the invariant UnsolvedCellException exists to police. If a
 * hand could reach a cell that is filled later, the lookup would land on nothing.
 *
 * The order is hard 21 down to 12, then the soft hands, then hard 11 down to 5, then the
 * pairs, then A,A. The split of the hard totals around the soft ones is the part that is
 * easy to get wrong: a low hard total that draws an ace becomes a soft hand, so hard 5
 * through 11 have to come after every soft hand, while hard 12 through 21 have to come
 * before them.
 */
public class HandOrderingTest {

    private static ArrayList<Card> cards(Rank... ranks) {
        ArrayList<Card> h = new ArrayList<>();
        Suit[] suits = Suit.values();
        for (int i = 0; i < ranks.length; i++) {
            h.add(new Card(ranks[i], suits[i % suits.length]));
        }
        return h;
    }

    private static String name(HandEncoding he) {
        if (he.canSplit) {
            return he.isSoft ? "A,A" : "pair of " + (he.hardCount / 2) + "s";
        }
        return (he.isSoft ? "soft " : "hard ") + (he.isSoft ? he.hardCount + 10 : he.hardCount);
    }

    /** Concrete two- and three-card hands for every encoding the order contains. */
    private static Map<HandEncoding, List<ArrayList<Card>>> representatives() {
        Map<HandEncoding, List<ArrayList<Card>>> reps = new HashMap<>();
        for (Rank a : Rank.values()) {
            for (Rank b : Rank.values()) {
                ArrayList<Card> two = cards(a, b);
                reps.computeIfAbsent(new HandEncoding(two), k -> new ArrayList<>()).add(two);
                for (Rank c : Rank.values()) {
                    ArrayList<Card> three = cards(a, b, c);
                    if (new HandEncoding(three).hardCount <= 21) {
                        reps.computeIfAbsent(new HandEncoding(three), k -> new ArrayList<>()).add(three);
                    }
                }
            }
        }
        return reps;
    }

    /** Every encoding in the order is a hand that can actually be held. */
    @Test
    public void everyOrderedEncodingIsReachable() {
        Map<HandEncoding, List<ArrayList<Card>>> reps = representatives();
        for (HandEncoding he : HandEncoding.getOrderedEncodings()) {
            assertNotNull(reps.get(he), name(he) + " is in the order but no hand produces it");
        }
    }

    /** The order lists each hand once. */
    @Test
    public void theOrderHasNoDuplicates() {
        List<HandEncoding> order = HandEncoding.getOrderedEncodings();
        assertEquals(order.size(), new HashSet<>(order).size(), "an encoding appears twice");
    }

    /**
     * Hitting any hand lands on a hand that was solved earlier, or busts.
     *
     * This is the whole invariant, checked over every encoding against every rank.
     */
    @Test
    public void hittingAlwaysLandsOnAnAlreadySolvedHand() {
        List<HandEncoding> order = HandEncoding.getOrderedEncodings();
        Map<HandEncoding, Integer> position = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            position.put(order.get(i), i);
        }
        Map<HandEncoding, List<ArrayList<Card>>> reps = representatives();

        int checked = 0;
        for (int i = 0; i < order.size(); i++) {
            HandEncoding he = order.get(i);
            for (Rank drawn : Rank.values()) {
                for (ArrayList<Card> rep : reps.get(he)) {
                    ArrayList<Card> next = new ArrayList<>(rep);
                    next.add(new Card(drawn, Suit.CLUBS));
                    HandEncoding after = new HandEncoding(next);
                    if (after.hardCount > 21) {
                        continue;   // busting resolves the hand, nothing is looked up
                    }
                    Integer j = position.get(after);
                    assertNotNull(j, name(he) + " can turn into " + name(after)
                            + ", which the order does not contain");
                    assertTrue(j < i, name(he) + " is played at position " + i
                            + " but hitting a " + drawn + " needs " + name(after)
                            + " from position " + j);
                    checked++;
                }
            }
        }
        assertTrue(checked > 400, "expected the sweep to cover the whole order, saw " + checked);
    }

    /** Splitting a pair lands on hands solved earlier, or on the same pair again. */
    @Test
    public void splittingAlwaysLandsOnAnAlreadySolvedHand() {
        List<HandEncoding> order = HandEncoding.getOrderedEncodings();
        Map<HandEncoding, Integer> position = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            position.put(order.get(i), i);
        }

        for (int i = 0; i < order.size(); i++) {
            HandEncoding he = order.get(i);
            if (!he.canSplit) {
                continue;
            }
            Rank pairRank = pairRankOf(he);
            for (Rank drawn : Rank.values()) {
                HandEncoding after = new HandEncoding(cards(pairRank, drawn));
                Integer j = position.get(after);
                assertNotNull(j, name(he) + " splits into " + name(after)
                        + ", which the order does not contain");
                assertTrue(j <= i, name(he) + " is played at position " + i
                        + " but splitting into " + name(after) + " needs position " + j);
            }
        }
    }

    private static Rank pairRankOf(HandEncoding he) {
        if (he.isSoft) {
            return Rank.ACE;
        }
        for (Rank r : Rank.values()) {
            if (!r.equals(Rank.ACE) && r.getRankpoints() * 2 == he.hardCount) {
                return r;
            }
        }
        throw new IllegalArgumentException("no rank makes " + name(he));
    }

    /**
     * The specific thing the split of the hard group buys.
     *
     * A low hard total that draws an ace becomes a soft hand. An earlier version of this
     * order put every hard total before every soft one, which broke exactly here: hard 5
     * through 10 each reached a soft hand that had not been solved yet.
     */
    @Test
    public void lowHardTotalsComeAfterTheSoftHandsTheyCanReach() {
        List<HandEncoding> order = HandEncoding.getOrderedEncodings();
        Map<HandEncoding, Integer> position = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            position.put(order.get(i), i);
        }

        for (int total = 5; total <= 10; total++) {
            HandEncoding hard = new HandEncoding(false, false, total);
            HandEncoding soft = new HandEncoding(true, false, total + 1);
            assertTrue(position.get(soft) < position.get(hard),
                    "hard " + total + " draws an ace into soft " + (total + 11)
                            + ", so that soft hand must be solved first");
        }
    }

    /** And high hard totals come before the soft hands that reach them. */
    @Test
    public void highHardTotalsComeBeforeTheSoftHandsThatReachThem() {
        List<HandEncoding> order = HandEncoding.getOrderedEncodings();
        Map<HandEncoding, Integer> position = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            position.put(order.get(i), i);
        }

        Set<HandEncoding> softHands = new HashSet<>();
        for (int hardCount = 3; hardCount <= 11; hardCount++) {
            softHands.add(new HandEncoding(true, false, hardCount));
        }
        for (int total = 12; total <= 21; total++) {
            HandEncoding hard = new HandEncoding(false, false, total);
            for (HandEncoding soft : softHands) {
                assertTrue(position.get(hard) < position.get(soft),
                        "a soft hand can harden into hard " + total + ", so it must come first");
            }
        }
    }
}
