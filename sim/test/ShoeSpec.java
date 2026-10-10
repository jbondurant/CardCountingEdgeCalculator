package sim;

// SHOE_SPEC.md A3's reference code, copied verbatim below this header (SpecCopyTest holds the copy
// equal to the spec's block). Test-only: the simulator's own code is in sim/*.java.

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.ToIntBiFunction;

/** SHOE_SPEC.md's reference code: the shoe, its shuffle, its round starts and its valuation draw. */
final class ShoeSpec {

    static final int DECKS = 8;
    static final int KINDS = 26;
    static final int CARDS = 52 * DECKS;

    private ShoeSpec() {
    }

    /** The contract's rank of a kind: 1 for the ace, 2 to 9, and 10 for the ten, J, Q and K. */
    static int rankOf(int kind) {
        int rank13 = kind / 2;
        return rank13 >= 9 ? 10 : rank13 + 1;
    }

    /** Hearts and diamonds. */
    static boolean red(int kind) {
        return kind % 2 == 1;
    }

    /** The seed of shoe's generator on one stream: 0 shuffles the cards, 1 draws the valuation. */
    static long mix(long seed, long shoe, long stream) {
        long z = seed * 0x9E3779B97F4A7C15L + shoe * 0xBF58476D1CE4E5B9L + stream * 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The shoe before shuffling: deck by deck, kinds 0 to 25, two of each side by side. */
    static int[] layout() {
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

    /** Shoe number shoe of the run with this seed, as kinds in the order they are dealt. */
    static int[] shuffled(long seed, int shoe) {
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

    /** The cards left by rank 1 to 10 (index 0 unused), from the cards dealt by kind. */
    static int[] ranksLeft(int[] dealt) {
        int[] left = new int[11];
        for (int k = 0; k < KINDS; k++) {
            left[rankOf(k)] += 2 * DECKS - dealt[k];
        }
        return left;
    }

    /** u: the chi-square distance of the cards left from a full shoe's proportions, per card. */
    static double unusualness(int[] left) {
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

    /** q: the chance a state with these cards left is valued. */
    static double rate(int[] left, double q0, double u0) {
        if (u0 == 0) {
            return q0;
        }
        return Math.min(1, q0 * (1 + unusualness(left) / u0));
    }

    /** A round start: the cards dealt before it, by kind, and whether it was valued. */
    record Start(int shoe, int round, int depth, int[] dealt, double q, boolean valued) {
    }

    /**
     * Deals one shoe to the cut card: a round starts while fewer than cut cards have been
     * dealt. roundEnd.applyAsInt(ranks, from) must return the index of the first card that the
     * round starting at ranks[from] does not use, the round played by the chart (A4); ranks
     * holds the contract's rank of each card in dealt order.
     */
    static List<Start> walk(long seed, int shoe, int cut, double q0, double u0,
                            ToIntBiFunction<int[], Integer> roundEnd) {
        int[] cards = shuffled(seed, shoe);
        int[] ranks = new int[CARDS];
        for (int i = 0; i < CARDS; i++) {
            ranks[i] = rankOf(cards[i]);
        }
        SplittableRandom valuation = new SplittableRandom(mix(seed, shoe, 1));
        List<Start> starts = new ArrayList<>();
        int[] dealt = new int[KINDS];
        int depth = 0;
        int round = 0;
        while (depth < cut) {
            double q = rate(ranksLeft(dealt), q0, u0);
            boolean valued = valuation.nextDouble() < q;
            starts.add(new Start(shoe, round, depth, dealt.clone(), q, valued));
            int next = roundEnd.applyAsInt(ranks, depth);
            for (int i = depth; i < next; i++) {
                dealt[cards[i]]++;
            }
            depth = next;
            round++;
        }
        return starts;
    }

    /**
     * chart.tsv's order (B1): decision n of the 253,440, as {up, total, soft, pair, fromSplit,
     * first, legal}, booleans as 0 or 1. legal's bits: 1 Hit, 2 Double, 4 Split, 8 Surrender.
     */
    static int[][] chartDecisions() {
        int[][] out = new int[253_440][];
        int n = 0;
        for (int up = 1; up <= 10; up++) {
            for (int total = 4; total <= 21; total++) {
                for (int soft = 0; soft <= 1; soft++) {
                    for (int pair = 0; pair <= 10; pair++) {
                        for (int fromSplit = 0; fromSplit <= 1; fromSplit++) {
                            for (int first = 0; first <= 1; first++) {
                                for (int legal = 0; legal < 16; legal++) {
                                    out[n++] = new int[]{up, total, soft, pair, fromSplit, first, legal};
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The chart's fingerprint from its answers in chart.tsv's order: 0 Stand, 1 Hit, 2 Double, 3 Split, 4 Surrender. */
    static String fingerprint(int[] moves) {
        long h = 1125899906842597L;
        for (int m : moves) {
            h = 31 * h + m;
        }
        return Long.toHexString(h);
    }
}
