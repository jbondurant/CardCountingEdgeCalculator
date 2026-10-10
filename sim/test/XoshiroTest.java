package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The fresh shoes' generator: xoshiro256** and its SplitMix64 seeding (SIM_CHECK.md section 4). */
class XoshiroTest {

    /**
     * The first three outputs from the state {1, 2, 3, 4}, worked by hand from the algorithm
     * (result = rotl(s1 * 5, 7) * 9, then the state update): s1 = 2 gives rotl(10, 7) * 9 =
     * 11520; the update leaves s1 = 0, so the second is 0; it then leaves s1 = 262149, and
     * 262149 * 5 = 1310745, rotated 7 is 167775360, times 9 is 1509978240.
     */
    @Test
    void firstOutputsByHand() {
        Xoshiro x = new Xoshiro(1, 2, 3, 4);
        assertEquals(11520L, x.nextLong());
        assertEquals(0L, x.nextLong());
        assertEquals(1509978240L, x.nextLong());
    }

    /** SplitMix64 started at a seed is java.util.SplittableRandom(seed)'s nextLong sequence (SHOE_SPEC.md A9). */
    @Test
    void splitMixIsSplittableRandomsSequence() {
        for (long seed : new long[]{20261008L, 20261009L, 0L, -1L}) {
            SplittableRandom jdk = new SplittableRandom(seed);
            for (int i = 0; i < 100; i++) {
                assertEquals(jdk.nextLong(), Xoshiro.splitMix64(seed, i));
            }
        }
        assertEquals(new SplittableRandom(7).nextLong(), Xoshiro.mix64(7 + Xoshiro.GOLDEN));
    }

    @Test
    void everyShoeStreamAndRedealHasItsOwnGenerator() {
        Set<Long> firsts = new HashSet<>();
        for (int index = 0; index < 200; index++) {
            for (int stream = 0; stream < 2; stream++) {
                for (int sub = 0; sub < 5; sub++) {
                    assertTrue(firsts.add(Xoshiro.of(20261008L, index, stream, sub).nextLong()));
                }
            }
        }
        assertEquals(Xoshiro.of(20261008L, 12, 0, 0).nextLong(), Xoshiro.of(20261008L, 12, 0, 0).nextLong());
        assertNotEquals(Xoshiro.of(20261008L, 12, 0, 0).nextLong(), Xoshiro.of(20261009L, 12, 0, 0).nextLong());
        assertThrows(IllegalArgumentException.class, () -> Xoshiro.of(1, 0, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> Xoshiro.of(1, 0, 0, 1 << 20));
    }

    /** nextInt stays in range and is flat: a chi-square over 13 cells under 50 (12 degrees of freedom, whose 0.001 point is 32.9; the draws are seeded, so the test is fixed). */
    @Test
    void nextIntIsInRangeAndFlat() {
        Xoshiro x = Xoshiro.of(3, 0, 0, 0);
        for (int bound = 1; bound <= 416; bound++) {
            for (int i = 0; i < 50; i++) {
                int v = x.nextInt(bound);
                assertTrue(v >= 0 && v < bound);
            }
        }
        int n = 1_300_000;
        long[] cells = new long[13];
        for (int i = 0; i < n; i++) {
            cells[x.nextInt(13)]++;
        }
        double chi = 0;
        for (long c : cells) {
            chi += (c - n / 13.0) * (c - n / 13.0) / (n / 13.0);
        }
        assertTrue(chi < 50, "chi-square " + chi + " on 12 degrees of freedom");
        double d = x.nextDouble();
        assertTrue(d >= 0 && d < 1);
    }

    /** A fresh shoe is a permutation of the layout, and different shoes differ. */
    @Test
    void freshShoesArePermutations() {
        int[] a = Shoe.shuffled(Xoshiro.of(20261008L, 0, 0, 0));
        int[] b = Shoe.shuffled(Xoshiro.of(20261008L, 1, 0, 0));
        int[] per = new int[Shoe.KINDS];
        for (int k : a) {
            per[k]++;
        }
        for (int c : per) {
            assertEquals(16, c);
        }
        assertNotEquals(java.util.Arrays.toString(a), java.util.Arrays.toString(b));
    }
}
