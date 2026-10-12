package sim;

import java.util.SplittableRandom;

/**
 * The 416-card shoe of SHOE_SPEC.md part A: its 26 kinds, its layout, the main file's shuffle and
 * the valuation rate q. The methods marked "A3" are SHOE_SPEC.md A3's reference code, copied as
 * it stands (the spec allows it), because check D1 asks for the engine's shoes card for card and
 * q to the bit, which only the same operations in the same order give.
 *
 * <p>A card is a kind 0 to 25: {@code rank13 * 2 + colour}, rank13 running A, 2, ..., 10, J, Q,
 * K from 0 to 12, colour 1 for red. Its rank, as ROUND_CONTRACT.md counts it, is 1 for the ace,
 * 2 to 9, and 10 for the ten and the faces.
 */
public final class Shoe {

    public static final int DECKS = 8;
    public static final int KINDS = 26;
    public static final int CARDS = 52 * DECKS;
    /** Cards of one kind in a full shoe: two a deck. */
    public static final int PER_KIND = 2 * DECKS;
    /** The main file's seed and shoes, and the valuation's q0 and u0 (SHOE_SPEC.md A2). */
    public static final long SEED_MAIN = 20260928L;
    public static final int SHOES_MAIN = 60_000;
    public static final double Q0 = 0.008;
    public static final double U0 = 0.02;
    /** The cut cards of 70, 75 and 80% penetration: a round starts while fewer have been dealt. */
    public static final int[] CUTS = {291, 312, 332};
    public static final int[] PENS = {70, 75, 80};

    private static final int[] RANK = new int[KINDS];

    static {
        for (int k = 0; k < KINDS; k++) {
            RANK[k] = rankOf(k);
        }
    }

    private Shoe() {
    }

    /** A3: the contract's rank of a kind. */
    public static int rankOf(int kind) {
        int rank13 = kind / 2;
        return rank13 >= 9 ? 10 : rank13 + 1;
    }

    /** The same, from a table, for the inner loops. */
    static int rank(int kind) {
        return RANK[kind];
    }

    /** A3: hearts and diamonds. */
    public static boolean red(int kind) {
        return kind % 2 == 1;
    }

    /** A3: the seed of a shoe's generator on one stream (0 the cards, 1 the valuation draw). */
    public static long mix(long seed, long shoe, long stream) {
        long z = seed * 0x9E3779B97F4A7C15L + shoe * 0xBF58476D1CE4E5B9L + stream * 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A3: the shoe before shuffling, deck by deck, kinds 0 to 25, two of each side by side. */
    public static int[] layout() {
        int[] cards = new int[CARDS];
        int c = 0;
        for (int d = 0; d < DECKS; d++) {
            for (int k = 0; k < KINDS; k++) {
                cards[c++] = k;
                cards[c++] = k;
            }
        }
        return cards;
    }

    /** A3: shoe number {@code shoe} of the main file's kind of run, as kinds in dealt order. */
    public static int[] shuffled(long seed, int shoe) {
        int[] cards = layout();
        SplittableRandom rnd = new SplittableRandom(mix(seed, shoe, 0));
        for (int i = CARDS - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = cards[i];
            cards[i] = cards[j];
            cards[j] = t;
        }
        return cards;
    }

    /** The same layout shuffled by another generator, the same way (fresh shoes, check P3). */
    public static int[] shuffled(Xoshiro rnd) {
        int[] cards = layout();
        for (int i = CARDS - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = cards[i];
            cards[i] = cards[j];
            cards[j] = t;
        }
        return cards;
    }

    /** A3: the cards left by rank 1 to 10 (index 0 unused), from the cards dealt by kind. */
    public static int[] ranksLeft(int[] dealt) {
        int[] left = new int[11];
        for (int k = 0; k < KINDS; k++) {
            left[rankOf(k)] += PER_KIND - dealt[k];
        }
        return left;
    }

    /** A3: u, the chi-square distance of the cards left from a full shoe's proportions, per card. */
    public static double unusualness(int[] left) {
        int n = 0;
        for (int r = 1; r <= 10; r++) {
            n += left[r];
        }
        if (n == 0) {
            return 0;
        }
        double u = 0;
        for (int r = 1; r <= 10; r++) {
            double expected = n * (r == 10 ? 4.0 : 1.0) / 13;
            double d = left[r] - expected;
            u += d * d / expected;
        }
        return u / n;
    }

    /** A3: q, the chance a state with these cards left is valued. */
    public static double rate(int[] left, double q0, double u0) {
        if (u0 == 0) {
            return q0;
        }
        return Math.min(1, q0 * (1 + unusualness(left) / u0));
    }
}
