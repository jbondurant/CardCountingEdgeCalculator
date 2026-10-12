package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** SHOE_SPEC.md B2.2's keys, on cases worked by hand. */
class KeysTest {

    /** Hi-Lo by kind: 2 to 6 count +1, 7 to 9 count 0, tens, faces and aces count -1. */
    static int[] hiLo() {
        int[] t = new int[Shoe.KINDS];
        for (int k = 0; k < Shoe.KINDS; k++) {
            int r = Shoe.rankOf(k);
            t[k] = r >= 2 && r <= 6 ? 1 : r >= 7 && r <= 9 ? 0 : -1;
        }
        return t;
    }

    /** KO by kind: 2 to 7 count +1, so a deck adds 4 and the count is unbalanced. */
    static int[] ko() {
        int[] t = hiLo();
        t[12] = 1;
        t[13] = 1;
        return t;
    }

    static Keys keys(int[] tags, boolean trueCount, int divisor, int ace, boolean truncate) {
        long sum = 0;
        for (int t : tags) {
            sum += 2L * t;
        }
        return new Keys(tags, tags, trueCount, divisor, sum, sum, ace, truncate);
    }

    @Test
    void roundingIsToNearestWithTiesTowardZero() {
        assertEquals(2, Keys.round(5, 2));
        assertEquals(-2, Keys.round(-5, 2));
        assertEquals(1, Keys.round(3, 2));
        assertEquals(-1, Keys.round(-3, 2));
        assertEquals(3, Keys.round(8, 3));
        assertEquals(-3, Keys.round(-8, 3));
        assertEquals(0, Keys.round(1, 3));
        assertEquals(0, Keys.round(0, 7));
    }

    @Test
    void hiLoTrueCountByHand() {
        // 52 cards dealt: ten low cards (five 5s and five 6s), two tens, forty 7s, 8s and 9s.
        int[] dealt = new int[Shoe.KINDS];
        dealt[8] = 5;
        dealt[10] = 5;
        dealt[18] = 2;
        dealt[12] = 14;
        dealt[14] = 13;
        dealt[16] = 13;
        Keys k = keys(hiLo(), true, 1, 0, false);
        // Running count 10 - 2 = 8 over 364 cards left: 52 * 8 / 364 = 1.14, so 1.
        assertEquals(1, k.betKey(dealt, 52));
        // The deal shows three 5s: 11 over 361 cards left, 572 / 361 = 1.58, so 2.
        int[] seen = {8, 9, 8};
        assertEquals(2, k.playKey(k.playRunning(dealt), 52, seen, 3));
        // Truncated (planted), 1.58 becomes 1.
        Keys t = keys(hiLo(), true, 1, 0, true);
        assertEquals(1, t.playKey(t.playRunning(dealt), 52, seen, 3));
        // Only the first s seen cards count: with two of them, 10 over 362 gives 1.44, so 1.
        assertEquals(1, k.playKey(k.playRunning(dealt), 52, seen, 2));
    }

    @Test
    void cardsSeenThisRoundLeaveTheCardsLeft() {
        // Depth 300: 107 low cards, 98 high, 95 neutral, so a running count of 9. The deal shows
        // three low cards: 12 over 416 - 300 - 3 = 113 cards left, 624 / 113 = 5.52, so 6.
        // Over the 116 left at the round's start it would be 5.38, so 5.
        int[] dealt = new int[Shoe.KINDS];
        for (int k = 2; k <= 11; k++) {
            dealt[k] = k <= 8 ? 11 : 10;
        }
        for (int k : new int[]{0, 1, 18, 19, 20, 21, 22, 23, 24, 25}) {
            dealt[k] = k >= 24 ? 9 : 10;
        }
        for (int k = 12; k <= 17; k++) {
            dealt[k] = k == 17 ? 15 : 16;
        }
        Keys k = keys(hiLo(), true, 1, 0, false);
        assertEquals(9, k.playRunning(dealt));
        assertEquals(6, k.playKey(k.playRunning(dealt), 300, new int[]{8, 10, 9}, 3));
    }

    @Test
    void unbalancedCountIsCorrectedByWhatTheDealtCardsAdd() {
        // One deck's worth dealt, two of each kind: KO's running count is 4, its deck sum is 4,
        // so the corrected count, 52 * 4 - 4 * 52, is 0.
        int[] dealt = new int[Shoe.KINDS];
        java.util.Arrays.fill(dealt, 2);
        Keys k = keys(ko(), true, 1, 0, false);
        assertEquals(4, k.deckSum);
        assertEquals(0, k.betKey(dealt, 52));
        // Running mode keys on the running count itself.
        Keys r = keys(ko(), false, 1, 0, false);
        assertEquals(4, r.betKey(dealt, 52));
        assertEquals(4 + 1, r.playKey(r.playRunning(dealt), 52, new int[]{2, 4, 20}, 3));
        // Running mode with divisor 2 rounds 5 / 2 toward zero.
        int[] five = new int[Shoe.KINDS];
        five[2] = 5;
        assertEquals(2, keys(ko(), false, 2, 0, false).betKey(five, 5));
    }

    @Test
    void aceSideCountIsZeroOnAFullShoeAndCountsAcesLeft() {
        Keys k = keys(hiLo(), true, 1, 1, false);
        assertEquals(0, k.betKey(new int[Shoe.KINDS], 0));
        // 26 cards dealt, none an ace, the rest balanced for Hi-Lo: 7s, 8s and 9s only.
        int[] dealt = new int[Shoe.KINDS];
        dealt[12] = 9;
        dealt[14] = 9;
        dealt[16] = 8;
        // 52 * 32 - 4 * 390 = 104, over 390: 0.27, so 0; with M = 4 it is 416 / 390 = 1.07, so 1.
        assertEquals(0, k.betKey(dealt, 26));
        assertEquals(1, keys(hiLo(), true, 1, 4, false).betKey(dealt, 26));
    }

    @Test
    void aDeckSumThatIsNotTheTagsIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new Keys(hiLo(), hiLo(), true, 1, 2, 0, 0, false));
    }
}
