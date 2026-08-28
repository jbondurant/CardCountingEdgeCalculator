import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a raw true count is snapped onto the count grid.
 *
 * The true count is one integer over another and most reachable deck-counts are even, so
 * an exact .5 tie shows up in roughly 9% of hands. The tie rule is therefore load
 * bearing, and it has to get two separate things right.
 *
 * It has to be symmetric about zero, or a balanced count produces a lopsided histogram.
 * Math.round is not: it sends ties toward positive infinity, so Math.round(1.5) is 2 but
 * Math.round(-1.5) is -1.
 *
 * And among the symmetric rules it has to describe its own buckets honestly. The count is
 * densest near zero, so bucket k holds more mass at its low edge than its high edge.
 * Breaking ties away from zero pulls the dense low tie in and pushes the sparse high tie
 * out, leaving each bucket's mean about 0.11 below its label. Breaking ties toward zero
 * nearly cancels that.
 */
public class TrueCountRoundingTest {

    // ------------------------------------------------------------------ symmetry

    /** Rounding must not care which side of zero it is on. */
    @Test
    public void roundingIsSymmetricAboutZero() {
        for (double d : new double[]{0.5, 1.5, 2.5, 3.5, 4.5, 0.25, 1.75}) {
            double up = GranularCount.roundToGrain(d, 1.0);
            double down = GranularCount.roundToGrain(-d, 1.0);
            assertEquals(up, -down, 1e-9,
                    "rounding " + d + " gave " + up + " but rounding " + (-d)
                            + " gave " + down);
        }
    }

    /** The specific case called out in the write-up. */
    @Test
    public void negativeAndPositiveHalvesRoundTheSameDistance() {
        double positive = GranularCount.roundToGrain(1.5, 1.0);
        double negative = GranularCount.roundToGrain(-1.5, 1.0);
        assertEquals(Math.abs(positive), Math.abs(negative), 1e-9,
                "1.5 rounded to " + positive + " but -1.5 rounded to " + negative);
    }

    // ------------------------------------------------------------ tie direction

    /** Ties break toward zero, so a count is never rounded up into a bigger edge. */
    @Test
    public void tiesBreakTowardZero() {
        assertEquals(1.0, GranularCount.roundToGrain(1.5, 1.0), 1e-9);
        assertEquals(-1.0, GranularCount.roundToGrain(-1.5, 1.0), 1e-9);
        assertEquals(0.0, GranularCount.roundToGrain(0.5, 1.0), 1e-9);
        assertEquals(0.0, GranularCount.roundToGrain(-0.5, 1.0), 1e-9);
        assertEquals(2.0, GranularCount.roundToGrain(2.5, 1.0), 1e-9);
    }

    /** Everything that is not a tie still goes to the nearest grid point. */
    @Test
    public void nonTiesRoundToNearest() {
        assertEquals(2.0, GranularCount.roundToGrain(1.6, 1.0), 1e-9);
        assertEquals(1.0, GranularCount.roundToGrain(1.4, 1.0), 1e-9);
        assertEquals(-2.0, GranularCount.roundToGrain(-1.6, 1.0), 1e-9);
        assertEquals(-1.0, GranularCount.roundToGrain(-1.4, 1.0), 1e-9);
    }

    // ---------------------------------------------------------------- well-formed

    /** Rounding to a grain must land on the grain. */
    @Test
    public void roundingLandsOnTheGrain() {
        for (double grain : new double[]{1.0, 0.5, 0.25}) {
            for (double d = -5.0; d <= 5.0; d += 0.125) {
                double rounded = GranularCount.roundToGrain(d, grain);
                double offGrain = Math.abs(rounded / grain - Math.round(rounded / grain));
                assertTrue(offGrain < 1e-9,
                        "rounding " + d + " to grain " + grain + " gave " + rounded);
            }
        }
    }

    /** Rounding never moves a value by more than half a grain. */
    @Test
    public void roundingMovesAtMostHalfAGrain() {
        for (double grain : new double[]{1.0, 0.5, 0.25}) {
            for (double d = -5.0; d <= 5.0; d += 0.0625) {
                double rounded = GranularCount.roundToGrain(d, grain);
                assertTrue(Math.abs(rounded - d) <= grain / 2.0 + 1e-9,
                        "rounding " + d + " to grain " + grain + " moved it to " + rounded);
            }
        }
    }

    /** A count of exactly zero must stay exactly zero. */
    @Test
    public void zeroStaysZero() {
        GranularCount gc = new GranularCount(GranularCount.roundToGrain(0.0, 1.0));
        assertEquals(0, gc.units);
        assertEquals(0.0, gc.getDoubleFromCount(), 1e-9);
    }

    /** Clamping to the reported range must respect the sign on both ends. */
    @Test
    public void clampingIsSymmetric() {
        GranularCount high = new GranularCount(9.0);
        high.forceCountIntoBoundaries(-5, 5);
        assertEquals(5.0, high.getDoubleFromCount(), 1e-9);

        GranularCount low = new GranularCount(-9.0);
        low.forceCountIntoBoundaries(-5, 5);
        assertEquals(-5.0, low.getDoubleFromCount(), 1e-9);
    }

    /** A count past the edge of the range is out of bounds on both sides alike. */
    @Test
    public void boundaryCheckIsSymmetric() {
        assertEquals(
                new GranularCount(6.0).isCountInBoundaries(-5, 5),
                new GranularCount(-6.0).isCountInBoundaries(-5, 5),
                "+6 and -6 must both be out of a [-5, 5] range");
    }

    // ------------------------------------------------- the properties that matter

    /**
     * Sample shoes the way the payoff run does and check the two properties end to end:
     * each bucket is reached about as often as its mirror image, and each bucket's label
     * describes the counts actually in it.
     *
     * The bias check is the one that separates the symmetric rules. Breaking ties away
     * from zero leaves every bucket's mean roughly 0.11 short of its label; breaking them
     * toward zero brings that down to about 0.02.
     */
    @Test
    public void bucketsAreSymmetricAndDescribeTheirContents() {
        int numShoes = 200000;
        int numDecks = 8;
        int penetrationPercentage = 75;

        CountMethod hiLo = CountMethod.getHiLoValue(1);
        CompositeCardSource template = CompositeCardSource.getMultiDeck(numDecks);
        int startingSize = template.startingSize;
        int deckSize = startingSize / numDecks;
        int maxCardsRemoved =
                startingSize - (int) ((1.0 - penetrationPercentage / 100.0) * startingSize);

        int[] countValues = new int[startingSize];
        for (int i = 0; i < startingSize; i++) {
            countValues[i] = hiLo.rankToCount.get(template.cards.get(i).rank);
        }

        long[] population = new long[11];
        double[] trueCountSum = new double[11];

        Random random = new Random(20220608L);
        for (int shoe = 0; shoe < numShoes; shoe++) {
            for (int i = countValues.length - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int tmp = countValues[i];
                countValues[i] = countValues[j];
                countValues[j] = tmp;
            }
            int cardsRemoved = random.nextInt(maxCardsRemoved + 1);
            int runningCount = 0;
            for (int i = 0; i < cardsRemoved; i++) {
                runningCount += countValues[i];
            }
            int decksLeft = CountMethod.getNumDecksRoundedUp(
                    startingSize - cardsRemoved, 1, deckSize);
            if (decksLeft == 0) {
                continue;
            }
            double trueCount = (double) runningCount / (double) decksLeft;
            int bucket = (int) GranularCount.roundToGrain(trueCount, 1.0);
            if (bucket < -5 || bucket > 5) {
                continue;
            }
            population[bucket + 5]++;
            trueCountSum[bucket + 5] += trueCount;
        }

        // Aggregate first. Pooling the buckets gives enough hands that sampling noise is
        // well under the effect being tested: Math.round skews this by about 7%, and the
        // pooled ratio here is stable to a few tenths of a percent.
        long positiveHands = 0;
        long negativeHands = 0;
        for (int k = 1; k <= 5; k++) {
            positiveHands += population[k + 5];
            negativeHands += population[-k + 5];
        }
        assertEquals(1.0, (double) positiveHands / (double) negativeHands, 0.02,
                "counted " + positiveHands + " hands at positive counts against "
                        + negativeHands + " at negative ones; a balanced count must be"
                        + " symmetric");

        // Then per bucket, at a tolerance scaled to how many hands each one actually got.
        // Five standard deviations of Poisson noise, which still leaves Math.round's 7%
        // skew far outside for every bucket.
        for (int k = 1; k <= 5; k++) {
            long positive = population[k + 5];
            long negative = population[-k + 5];
            double tolerance = 5.0 * Math.sqrt(1.0 / positive + 1.0 / negative);
            assertEquals(1.0, (double) positive / (double) negative, tolerance,
                    "bucket +" + k + " was reached " + positive + " times but -"
                            + k + " only " + negative
                            + "; a balanced count must be symmetric");
        }

        for (int k = -5; k <= 5; k++) {
            double mean = trueCountSum[k + 5] / population[k + 5];
            assertEquals(k, mean, 0.06,
                    "bucket " + k + " holds counts averaging " + String.format("%.4f", mean)
                            + ", which is not what its label claims");
        }
    }
}
