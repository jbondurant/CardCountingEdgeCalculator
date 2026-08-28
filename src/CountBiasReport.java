import java.util.Random;

/**
 * How the true count is snapped onto the count grid, and what it costs to get it wrong.
 *
 * The published run collected noticeably more hands at positive counts than negative
 * ones: 94.5M at +1 against 88.4M at -1, and 7.2M at +5 against 6.7M at -5. Hi-Lo is a
 * balanced count, so a symmetric sampling procedure should give a symmetric histogram.
 *
 * The true count is one integer over another, and four of the seven reachable
 * deck-counts are even, so an exact .5 tie turns up in roughly 9% of hands. How those
 * ties are broken therefore matters twice over:
 *
 *   - Math.round breaks them toward positive infinity, which is not symmetric and is
 *     what produced the skew;
 *   - among the rules that are symmetric, the choice still shifts how well each bucket's
 *     label matches the true counts actually in it, because the count is densest near
 *     zero and a bucket holds more mass at its low edge than its high edge.
 *
 * This reproduces the sampling procedure and reports, for each rule, the bucket
 * populations and the mean true count that landed in each bucket. The "bias" column is
 * that mean minus the bucket's own label, so a rule that describes its buckets honestly
 * has a bias near zero and a skew near 1.000.
 *
 * Run: java CountBiasReport [numShoes] [seed]
 */
public class CountBiasReport {

    /** A way of snapping a raw true count onto the grid. */
    private interface TieRule {
        double round(double trueCount);
        String name();
    }

    /** What the code used to do. Ties go toward positive infinity. */
    private static final TieRule MATH_ROUND = new TieRule() {
        public double round(double d) { return Math.round(d); }
        public String name() { return "Math.round (was)"; }
    };

    /** Symmetric, but drags every bucket's mean below its label. */
    private static final TieRule HALF_AWAY = new TieRule() {
        public double round(double d) { return Math.signum(d) * Math.floor(Math.abs(d) + 0.5); }
        public String name() { return "half away from zero"; }
    };

    /** Symmetric, and nearly cancels the density asymmetry. What roundToGrain does. */
    private static final TieRule HALF_TOWARD = new TieRule() {
        public double round(double d) { return Math.signum(d) * Math.ceil(Math.abs(d) - 0.5); }
        public String name() { return "half toward zero (now)"; }
    };

    /** Symmetric, but odd buckets shed ties on both edges while even buckets gain them. */
    private static final TieRule HALF_EVEN = new TieRule() {
        public double round(double d) { return Math.rint(d); }
        public String name() { return "half to even"; }
    };

    private static final int MIN_COUNT = -5;
    private static final int MAX_COUNT = 5;

    public static void main(String[] args) {
        int numShoes = args.length > 0 ? Integer.parseInt(args[0]) : 1000000;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 20220608L;

        int numDecks = 8;
        int penetrationPercentage = 75;

        CountMethod hiLo = CountMethod.getHiLoValue(1);
        CompositeCardSource template = CompositeCardSource.getMultiDeck(numDecks);
        int startingSize = template.startingSize;
        int deckSize = startingSize / numDecks;
        int minDeckSize = (int) ((1.0 - penetrationPercentage / 100.0) * startingSize);
        int maxCardsRemoved = startingSize - minDeckSize;

        // Pre-resolve each card to its Hi-Lo value so the sweep is just integer work.
        int[] countValues = new int[startingSize];
        for (int i = 0; i < startingSize; i++) {
            countValues[i] = hiLo.rankToCount.get(template.cards.get(i).rank);
        }

        TieRule[] rules = {MATH_ROUND, HALF_AWAY, HALF_TOWARD, HALF_EVEN};
        int numBuckets = MAX_COUNT - MIN_COUNT + 1;
        long[][] population = new long[rules.length][numBuckets];
        double[][] trueCountSum = new double[rules.length][numBuckets];

        long ties = 0;
        Random random = new Random(seed);
        for (int shoe = 0; shoe < numShoes; shoe++) {
            shuffle(countValues, random);
            int cardsRemoved = random.nextInt(maxCardsRemoved + 1);

            int runningCount = 0;
            for (int i = 0; i < cardsRemoved; i++) {
                runningCount += countValues[i];
            }
            int cardsLeft = startingSize - cardsRemoved;
            int decksLeft = CountMethod.getNumDecksRoundedUp(cardsLeft, 1, deckSize);
            if (decksLeft == 0) {
                continue;
            }
            double trueCount = (double) runningCount / (double) decksLeft;
            if (isExactHalf(trueCount)) {
                ties++;
            }

            for (int r = 0; r < rules.length; r++) {
                int bucket = (int) rules[r].round(trueCount);
                if (bucket < MIN_COUNT || bucket > MAX_COUNT) {
                    continue;
                }
                population[r][bucket - MIN_COUNT]++;
                trueCountSum[r][bucket - MIN_COUNT] += trueCount;
            }
        }

        System.out.printf("Shoes sampled: %,d   decks: %d   penetration: %d%%   seed: %d%n",
                numShoes, numDecks, penetrationPercentage, seed);
        System.out.printf("Exact .5 ties: %.1f%% of hands%n", 100.0 * ties / numShoes);

        for (int r = 0; r < rules.length; r++) {
            System.out.println();
            System.out.println("--- " + rules[r].name());
            System.out.printf("%7s %14s %11s %9s %8s%n",
                    "bucket", "hands", "mean count", "bias", "skew");
            for (int c = MAX_COUNT; c >= MIN_COUNT; c--) {
                long hands = population[r][c - MIN_COUNT];
                if (hands == 0) {
                    continue;
                }
                double mean = trueCountSum[r][c - MIN_COUNT] / hands;
                System.out.printf("%7d %14d %11.4f %+9.4f %8s%n",
                        c, hands, mean, mean - c, skew(population[r], c));
            }
        }

        System.out.println();
        System.out.println("bias = mean true count in the bucket, minus the bucket's label");
        System.out.println("skew = hands(+k) / hands(-k); a balanced count should give 1.000");
    }

    /** Whether this count sits exactly on a boundary between two buckets. */
    private static boolean isExactHalf(double trueCount) {
        double doubled = trueCount * 2.0;
        return Math.abs(doubled - Math.rint(doubled)) < 1e-12
                && Math.abs(trueCount - Math.rint(trueCount)) > 1e-12;
    }

    private static String skew(long[] population, int c) {
        if (c <= 0) {
            return "-";
        }
        long positive = population[c - MIN_COUNT];
        long negative = population[-c - MIN_COUNT];
        if (negative == 0) {
            return "n/a";
        }
        return String.format("%.3f", (double) positive / (double) negative);
    }

    private static void shuffle(int[] values, Random random) {
        for (int i = values.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = values[i];
            values[i] = values[j];
            values[j] = tmp;
        }
    }
}
