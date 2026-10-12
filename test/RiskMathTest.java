import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RiskMath: the normal distribution and the chi-square tail against published values and closed
 * forms, every formula over time against what it must reduce to and against simulated play, the
 * moments and sigma_eff against data built so the answer is known, and the percentile intervals
 * on resamples that reach +infinity.
 *
 * PLAN_COMPUTE.md 2.4 names the planted bugs these tests must catch: sigma for sigma^2, r for
 * sqrt(r), a dropped mu^2, a percentile taken before the transform, an infinite resample
 * mishandled. Each test that guards one says so. On 12 October each was planted in a scratch
 * copy of RiskMath and the class run against it; every one failed it:
 * - sigma for sigma^2 in the risk of ruin: 3 tests fail (the worked value, T to infinity, George C.);
 * - sigma for sigma^2 in B_p: the worked value of B_5% fails;
 * - r for sqrt(r) in the SD over r rounds: 20 expected, 200 given;
 * - mu^2 dropped from sigma: sqrt(0.96) expected, 1 given;
 * - the percentiles of mu taken first, then transformed (even with the ends put back in order):
 *   both interval tests fail, at 8e-8 and 3e-8 relative;
 * - B_p taken from the formula for a resample with mu below 0: a negative bankroll, and an
 *   interval with an upper bound where it has none (2 fail);
 * - Bootstrap.quantile's interpolation used across +infinity: NaN for an end (2 fail).
 */
public class RiskMathTest {

    private static void close(double expected, double actual, double relative, String what) {
        assertTrue(Math.abs(actual - expected) <= relative * Math.abs(expected),
                what + ": expected " + expected + ", got " + actual + " (relative " + (actual - expected) / expected + ")");
    }

    // ------------------------------------------------------------------ the normal distribution

    /** Published values of Phi (as tabulated to 16+ digits, e.g. by Marsaglia 2004 and NIST's DLMF 7.2). */
    @Test
    void phiAtPublishedValues() {
        assertEquals(0.5, RiskMath.cdf(0), 0);
        close(0.15865525393145705, RiskMath.cdf(-1), 1e-13, "Phi(-1)");
        close(0.022750131948179207, RiskMath.cdf(-2), 1e-13, "Phi(-2)");
        close(0.0013498980316300946, RiskMath.cdf(-3), 1e-13, "Phi(-3)");
        close(2.8665157187919391e-7, RiskMath.cdf(-5), 1e-13, "Phi(-5)");
        close(0.9772498680518208, RiskMath.cdf(2), 1e-15, "Phi(2)");
        close(0.15729920705028513, RiskMath.erfc(1), 1e-13, "erfc(1)");
        close(0.4795001221869535, RiskMath.erfc(0.5), 1e-13, "erfc(0.5)");
        close(2.209049699858544e-5, RiskMath.erfc(3), 1e-13, "erfc(3)");
        close(1.8427007929497148, RiskMath.erfc(-1), 1e-15, "erfc(-1)");
    }

    /**
     * Phi against the integral of the density by Simpson's rule, which shares nothing with
     * RiskMath's series and continued fraction but the density itself, across both of their
     * ranges and the switch at erfc's argument 2 (x = -2 sqrt 2).
     */
    @Test
    void phiIsTheIntegralOfTheDensity() {
        for (double x : new double[]{-9, -6, -4.5, -2 * Math.sqrt(2) - 1e-9, -2 * Math.sqrt(2) + 1e-9, -1.3, -0.2}) {
            int steps = 400_000;
            double a = -40;
            double h = (x - a) / steps;
            double s = RiskMath.density(a) + RiskMath.density(x);
            for (int i = 1; i < steps; i++) {
                s += (i % 2 == 1 ? 4 : 2) * RiskMath.density(a + i * h);
            }
            close(s * h / 3, RiskMath.cdf(x), 1e-11, "Phi(" + x + ")");
        }
    }

    /** Deep in the tail, against the asymptotic series phi(x)/x (1 - 1/x^2 + 3/x^4 - 15/x^6 + 105/x^8). */
    @Test
    void phiFarInTheTail() {
        for (double x : new double[]{20, 30, 37}) {
            double x2 = x * x;
            double series = RiskMath.density(x) / x * (1 - 1 / x2 + 3 / (x2 * x2) - 15 / (x2 * x2 * x2) + 105 / (x2 * x2 * x2 * x2));
            close(series, RiskMath.cdf(-x), 1e-10, "Phi(-" + x + ")");
        }
    }

    @Test
    void expTimesCdfStaysFiniteWhereTheProductWouldOverflow() {
        // Where both factors are moderate it is the product.
        close(Math.exp(3) * RiskMath.cdf(-2.5), RiskMath.expTimesCdf(3, -2.5), 1e-14, "e^3 Phi(-2.5)");
        close(Math.exp(-1) * RiskMath.cdf(0.7), RiskMath.expTimesCdf(-1, 0.7), 1e-15, "e^-1 Phi(0.7)");
        // e^2000 overflows a double; e^2000 Phi(-sqrt(4000)) does not: the exponents cancel to 0
        // and what is left is phi's tail factor, 1/(sqrt(2 pi) x) (1 - 1/x^2 + 3/x^4).
        double x = Math.sqrt(4000);
        double expected = 1 / (Math.sqrt(2 * Math.PI) * x) * (1 - 1 / (x * x) + 3 / Math.pow(x, 4));
        close(expected, RiskMath.expTimesCdf(2000, -x), 1e-9, "e^2000 Phi(-sqrt 4000)");
        assertTrue(Double.isNaN(Math.exp(2000) * RiskMath.cdf(-x)), "the plain product is what overflows");
    }

    // ------------------------------------------------------------------ the chi-square tail

    /**
     * Q(df/2, x/2) against its closed forms: df 1, 2 Phi(-sqrt x); df 2, e^(-x/2); df 3,
     * 2 Phi(-sqrt x) + sqrt(2x/pi) e^(-x/2); df 4, e^(-x/2) (1 + x/2). These share Phi with the
     * code, which the tests above hold to published values.
     */
    @Test
    void chiSquareTailAgainstClosedForms() {
        for (double x : new double[]{0.01, 0.5, 1, 2.5, 3.841458820694124, 10, 30, 100, 400}) {
            double q1 = 2 * RiskMath.cdf(-Math.sqrt(x));
            close(q1, RiskMath.chiSquareUpperTail(x, 1), 1e-11, "df 1 at " + x);
            close(Math.exp(-x / 2), RiskMath.chiSquareUpperTail(x, 2), 1e-11, "df 2 at " + x);
            close(q1 + Math.sqrt(2 * x / Math.PI) * Math.exp(-x / 2), RiskMath.chiSquareUpperTail(x, 3), 1e-11,
                    "df 3 at " + x);
            close(Math.exp(-x / 2) * (1 + x / 2), RiskMath.chiSquareUpperTail(x, 4), 1e-11, "df 4 at " + x);
        }
        // The 5% point of one degree of freedom.
        close(0.05, RiskMath.chiSquareUpperTail(3.841458820694124, 1), 1e-12, "the 5% point");
        assertEquals(1, RiskMath.chiSquareUpperTail(0, 5), 0);
        assertEquals(0, RiskMath.chiSquareUpperTail(Double.POSITIVE_INFINITY, 5), 0);
    }

    @Test
    void chiSquareTestPoolsSmallCellsAndFailsAnImpossiblePayoff() {
        // Counts exactly as expected: statistic 0, p 1.
        RiskMath.ChiSquare ok = RiskMath.chiSquare(new long[]{500, 300, 200}, new double[]{0.5, 0.3, 0.2}, 5);
        assertEquals(0, ok.statistic, 1e-12);
        assertEquals(2, ok.df);
        assertEquals(1, ok.p, 1e-12);
        // Two cells expected to hold 1 and 2 of 1,000 are pooled into one of 3 and, still short of
        // 5, join the smallest cell kept: three cells, two degrees of freedom.
        RiskMath.ChiSquare pooled = RiskMath.chiSquare(new long[]{497, 300, 200, 1, 2},
                new double[]{0.497, 0.3, 0.2, 0.001, 0.002}, 5);
        assertEquals(2, pooled.pooled);
        assertEquals(3, pooled.cells);
        assertEquals(0, pooled.statistic, 1e-12);
        // A payoff the exact distribution gives probability 0 cannot be explained by chance.
        RiskMath.ChiSquare impossible = RiskMath.chiSquare(new long[]{499, 300, 200, 1}, new double[]{0.5, 0.3, 0.2, 0}, 5);
        assertEquals(0, impossible.p, 0);
        // A real misfit: 55% where 50% is expected, at 10,000 rounds, is about 10 SDs out.
        RiskMath.ChiSquare off = RiskMath.chiSquare(new long[]{5500, 4500}, new double[]{0.5, 0.5}, 5);
        assertEquals(100, off.statistic, 1e-9);
        assertTrue(off.p < 1e-20);
    }

    // ------------------------------------------------------------------ the formulas over time

    /** Planted bug: sigma for sigma^2 in the risk of ruin, or in B_p, or in N0. */
    @Test
    void ruinBankrollAndN0AtWorkedValues() {
        // mu 0.01, sigma 2: RoR(100) = exp(-2 x 0.01 x 100 / 4) = e^-0.5.
        close(Math.exp(-0.5), RiskMath.ruin(0.01, 2, 100), 1e-15, "RoR(100)");
        // B_5% = -ln(0.05) x 4 / 0.02 = 200 ln 20.
        close(200 * Math.log(20), RiskMath.bankrollFor(0.05, 0.01, 2), 1e-15, "B_5%");
        // N0 = 4 / 1e-4 = 40,000 rounds.
        close(40_000, RiskMath.n0(0.01, 2), 1e-15, "N0");
        for (double p : new double[]{0.01, 0.05, 0.25}) {
            close(p, RiskMath.ruin(0.0036, 2.4, RiskMath.bankrollFor(p, 0.0036, 2.4)), 1e-12, "RoR(B_p) at " + p);
        }
        // A win rate at or below 0: ruin is certain and the win never comes.
        assertEquals(1, RiskMath.ruin(0, 2, 100), 0);
        assertEquals(1, RiskMath.ruin(-0.001, 2, 1e6), 0);
        assertEquals(Double.POSITIVE_INFINITY, RiskMath.bankrollFor(0.05, -0.001, 2), 0);
        assertEquals(Double.POSITIVE_INFINITY, RiskMath.n0(0, 2), 0);
        assertEquals(Double.POSITIVE_INFINITY, RiskMath.n0(-0.002, 2), 0);
        // After N0 rounds the expected win is one SD of the total, so a player is behind Phi(-1).
        close(RiskMath.cdf(-1), RiskMath.behind(0.0036, 2.4, RiskMath.n0(0.0036, 2.4)), 1e-12, "behind after N0");
    }

    /** George C.'s form agrees with the exponential to many digits when mu / sigma is about 0.0015. */
    @Test
    void georgeCAgreesWithTheExponential() {
        double mu = 0.0036;
        double sigma = 2.4;
        for (double p : new double[]{0.25, 0.05, 0.01}) {
            double b = RiskMath.bankrollFor(p, mu, sigma);
            close(RiskMath.ruin(mu, sigma, b), RiskMath.ruinGeorgeC(mu, sigma, b), 1e-5, "George C. at B_" + p);
        }
        // Where mu / sigma is not small they part: (1/3)^10 against e^-10, a factor of about 2.7.
        close(Math.pow(1.0 / 3, 10), RiskMath.ruinGeorgeC(0.5, 1, 10), 1e-12, "George C. at mu/sigma 0.5");
        assertTrue(RiskMath.ruin(0.5, 1, 10) > 2 * RiskMath.ruinGeorgeC(0.5, 1, 10));
    }

    /** Planted bug: r for sqrt(r), the SD over r rounds as sigma r. */
    @Test
    void perHundredRoundsAndPerHour() {
        assertEquals(20, RiskMath.sdOver(2, 100), 1e-12);
        assertEquals(0.36, RiskMath.evOver(0.0036, 100), 1e-15);
        close(2.4 * Math.sqrt(80), RiskMath.sdOver(2.4, 80), 1e-15, "SD over 80 rounds");
    }

    @Test
    void tripRiskReducesToItsLimits() {
        // Zero drift: 2 Phi(-B / (sigma sqrt T)), the reflection principle.
        for (double t : new double[]{10, 1_000, 1e6}) {
            close(2 * RiskMath.cdf(-100 / (2.4 * Math.sqrt(t))), RiskMath.tripRuin(0, 2.4, 100, t), 1e-14,
                    "zero drift over " + t);
        }
        // Played long enough, the trip risk is the risk of ruin for ever.
        close(RiskMath.ruin(0.0036, 2.4, 1000), RiskMath.tripRuin(0.0036, 2.4, 1000, 1e12), 1e-9, "T to infinity");
        // With a losing game it tends to 1.
        assertEquals(1, RiskMath.tripRuin(-0.005, 1.2, 1000, 1e9), 1e-12);
        // It grows with T and falls with B.
        assertTrue(RiskMath.tripRuin(0.0036, 2.4, 500, 1e4) < RiskMath.tripRuin(0.0036, 2.4, 500, 1e5));
        assertTrue(RiskMath.tripRuin(0.0036, 2.4, 1000, 1e5) < RiskMath.tripRuin(0.0036, 2.4, 500, 1e5));
        // Losing game, huge bankroll: e^(-2 mu B / sigma^2) is e^2000, which a plain product
        // turns into infinity times 0. B + mu T = 0 here, so the second term's exponents cancel
        // and it is phi's tail at x = -2B / (sigma sqrt T), about 0.0063; the first term is 1/2.
        double mu = -0.01;
        double b = 1e5;
        double t = 1e7;
        double x = -2 * b / Math.sqrt(t);
        double tail = 1 / (Math.sqrt(2 * Math.PI) * -x) * (1 - 1 / (x * x) + 3 / Math.pow(x, 4));
        close(0.5 + tail, RiskMath.tripRuin(mu, 1, b, t), 1e-9, "a losing game with a huge bankroll");
    }

    /**
     * The trip formula against a seeded Gaussian walk. Each round adds mu + sigma Z; between two
     * rounds the path is a Brownian bridge, which crosses -B with probability
     * exp(-2 (x0 + B)(x1 + B) / sigma^2) given both ends above it, so drawing that crossing makes
     * the walk's ruin exactly the continuous first passage the formula gives, with no
     * discretization bias. 40,000 paths: the binomial SE is about 0.002.
     */
    @Test
    void tripRiskAgainstASeededGaussianWalk() {
        double mu = 0.05;
        double sigma = 1;
        double b = 10;
        int t = 200;
        int paths = 40_000;
        Random rnd = new Random(20261012L);
        int ruined = 0;
        for (int j = 0; j < paths; j++) {
            double x = 0;
            for (int i = 0; i < t; i++) {
                double next = x + mu + sigma * rnd.nextGaussian();
                double u = rnd.nextDouble();
                if (next <= -b || u < Math.exp(-2 * (x + b) * (next + b) / (sigma * sigma))) {
                    ruined++;
                    break;
                }
                x = next;
            }
        }
        double sim = (double) ruined / paths;
        double formula = RiskMath.tripRuin(mu, sigma, b, t);
        double se = RiskMath.binomialSe(formula, paths);
        assertTrue(Math.abs(sim - formula) < 4 * se, "simulated " + sim + ", formula " + formula + ", SE " + se);
    }

    // ------------------------------------------------------------------ one round's moments

    @Test
    void theRangeOfTwiceAResultComesFromTheRules() {
        // Montreal: 4 hands, 3:2. Lowest: four doubles lost and insurance, -8.5; highest +8.
        assertArrayEquals(new int[]{-17, 16},
                RiskMath.twiceResultRange(RoundRules.from(HouseRules.getMtlCasino25MinBlackjackParams(80))));
        boolean[] none = new boolean[11];
        // Two hands at most: -4.5 to +4.
        assertArrayEquals(new int[]{-9, 8},
                RiskMath.twiceResultRange(new RoundRules(false, 1.5, 2, 2, false, none, false, false, false)));
        // A natural paying 5:1 on one hand outdoes a single doubled win.
        assertArrayEquals(new int[]{-5, 10},
                RiskMath.twiceResultRange(new RoundRules(false, 5, 1, 1, false, none, false, false, false)));
        // A 6:5 payout is not a whole number of halves, so 2X could not hold it.
        assertThrows(IllegalArgumentException.class,
                () -> RiskMath.twiceResultRange(new RoundRules(true, 1.2, 4, 4, false, none, false, false, false)));
    }

    /** Planted bug: a dropped mu^2, sigma as the root of the mean square. */
    @Test
    void momentsOfAHistogram() {
        // Six rounds win 1 and four lose 1 at one unit: E[X] 0.2, E[X^2] 1, sigma sqrt(0.96).
        RiskMath.Moments m = RiskMath.moments(new double[]{1, 1}, new int[]{2, -2}, new long[]{6, 4});
        assertEquals(10, m.rounds);
        assertEquals(0.2, m.mean, 1e-15);
        assertEquals(1, m.meanSquare, 1e-15);
        assertEquals(Math.sqrt(0.96), m.sd(), 1e-15);
        // Bets of 1 and 2.5 and a sat-out round: E[b] = (3 x 1 + 2 x 2.5 + 0) / 6 = 4/3,
        // E[b^2] = (3 + 2 x 6.25) / 6 = 15.5/6; results 3 x (+1), 2 x 2.5 x (-1.5), the sat-out 0.
        m = RiskMath.moments(new double[]{1, 2.5, 0}, new int[]{2, -3, 5}, new long[]{3, 2, 1});
        assertEquals(4.0 / 3, m.meanBet, 1e-15);
        assertEquals(15.5 / 6, m.meanBetSquared, 1e-15);
        double mean = (3 - 2 * 3.75) / 6;
        double square = (3 + 2 * 3.75 * 3.75) / 6;
        assertEquals(mean, m.mean, 1e-15);
        assertEquals(square, m.meanSquare, 1e-15);
        assertEquals((3 - 2 * Math.pow(3.75, 3)) / 6, m.meanCube, 1e-13);
        assertEquals((3 + 2 * Math.pow(3.75, 4)) / 6, m.meanFourth, 1e-12);
        assertEquals(Math.sqrt(square - mean * mean), m.sd(), 1e-15);
        // The order of the cells does not matter: the sums are exact.
        RiskMath.Moments again = RiskMath.moments(new double[]{0, 2.5, 1}, new int[]{5, -3, 2}, new long[]{1, 2, 3});
        assertEquals(m.meanSquare, again.meanSquare, 0);
        assertEquals(m.mean, again.mean, 0);
        assertThrows(IllegalArgumentException.class, () -> RiskMath.sd(1, 1.5));
    }

    /**
     * sigma_eff on shoes built so it is known. Shoe h has 60 rounds, each a_h + e_i with a_h = +1
     * on even shoes and -1 on odd ones, e_i = +2 on even rounds and -2 on odd: a shoe's total is
     * 60 a_h, every round's square averages 1 + 4 = 5, and mu is 0. So sigma = sqrt 5 and
     * sigma_eff^2 = S/(S-1) x 60^2 / 60 = S/(S-1) x 60: a correlation within the shoe that sigma
     * cannot see and sigma_eff measures.
     */
    @Test
    void sigmaEffOnAShoeBuiltToBeCorrelated() {
        int shoes = 1000;
        int n = 60;
        double[] sums = new double[7];
        long[] count = new long[4];
        for (int h = 0; h < shoes; h++) {
            double a = h % 2 == 0 ? 1 : -1;
            double y = 0;
            for (int i = 0; i < n; i++) {
                double x = a + (i % 2 == 0 ? 2 : -2);
                y += x;
                count[(h % 2) * 2 + i % 2]++;
            }
            sums[0] += y;
            sums[1] += y * y;
            sums[2] += y * y * y;
            sums[3] += y * y * y * y;
            sums[4] += n;
            sums[5] += n * n;
            sums[6] += y * n;
        }
        RiskMath.ShoeSums s = new RiskMath.ShoeSums(shoes, sums[0], sums[1], sums[2], sums[3], sums[4], sums[5], sums[6]);
        close(Math.sqrt((double) shoes / (shoes - 1) * n), RiskMath.sigmaEff(s), 1e-14, "sigma_eff");
        RiskMath.Moments m = RiskMath.moments(new double[]{1, 1, 1, 1}, new int[]{6, -2, 2, -6}, count);
        close(Math.sqrt(5), m.sd(), 1e-15, "sigma");
        // Every shoe's total is +60 or -60, so its variance has no spread to estimate: SE 0.
        assertEquals(0, RiskMath.sigmaEffSe(s), 1e-9);
    }

    /**
     * On rounds independent of each other, sigma_eff is sigma: 20,000 shoes of 50 to 70 rounds
     * each, every round a normal draw with mean 0.01 and SD 1.3 times a bet of 1 or 3. The ratio's
     * sampling SE is about 1 / sqrt(2 S) = 0.005; the test allows 4 of them. sigmaEffSe gives
     * nearly that SE, since a shoe's total is near normal.
     */
    @Test
    void sigmaEffIsSigmaOnIndependentRounds() {
        Random rnd = new Random(20261013L);
        int shoes = 20_000;
        double[] sums = new double[7];
        double sx = 0;
        double sxx = 0;
        long rounds = 0;
        for (int h = 0; h < shoes; h++) {
            int n = 50 + rnd.nextInt(21);
            double y = 0;
            for (int i = 0; i < n; i++) {
                double bet = rnd.nextInt(4) == 0 ? 3 : 1;
                double x = bet * (0.01 + 1.3 * rnd.nextGaussian());
                y += x;
                sx += x;
                sxx += x * x;
            }
            rounds += n;
            sums[0] += y;
            sums[1] += y * y;
            sums[2] += y * y * y;
            sums[3] += y * y * y * y;
            sums[4] += n;
            sums[5] += (double) n * n;
            sums[6] += y * n;
        }
        RiskMath.ShoeSums s = new RiskMath.ShoeSums(shoes, sums[0], sums[1], sums[2], sums[3], sums[4], sums[5], sums[6]);
        double sigma = RiskMath.sd(sxx / rounds, sx / rounds);
        double ratio = RiskMath.sigmaEff(s) / sigma;
        double se = 1 / Math.sqrt(2.0 * shoes);
        assertTrue(Math.abs(ratio - 1) < 4 * se, "sigma_eff / sigma = " + ratio);
        double seRatio = RiskMath.sigmaEffSe(s) / RiskMath.sigmaEff(s);
        assertTrue(seRatio > 0.7 * se && seRatio < 1.3 * se, "SE of sigma_eff, relative: " + seRatio + " against " + se);
    }

    // ------------------------------------------------------------------ percentile intervals

    @Test
    void quantileIsTheReportsOnFiniteValuesAndInfiniteBeyondThem() {
        double[] v = new double[1000];
        Random rnd = new Random(7);
        for (int i = 0; i < v.length; i++) {
            v[i] = rnd.nextGaussian();
        }
        Arrays.sort(v);
        for (double p : new double[]{0.025, 0.5, 0.975, 0, 1}) {
            assertEquals(Bootstrap.quantile(v, p), RiskMath.quantile(v, p), 0, "p = " + p);
        }
        double inf = Double.POSITIVE_INFINITY;
        // Between a finite value and +infinity, the quantile is +infinity; at a finite order
        // statistic exactly, it is that value.
        assertEquals(inf, RiskMath.quantile(new double[]{1, 2, inf, inf}, 0.5), 0);
        assertEquals(2, RiskMath.quantile(new double[]{1, 2, 3, inf}, 1.0 / 3), 1e-15);
        assertEquals(inf, RiskMath.quantile(new double[]{1, 2, inf, inf}, 0.975), 0);
        // What Bootstrap.quantile gives there, and why it is not used for these figures.
        assertTrue(Double.isNaN(Bootstrap.quantile(new double[]{1, 2, inf, inf}, 0.975)));
    }

    /**
     * Planted bug: a percentile taken before the transform. N0 = sigma^2 / mu^2 falls as mu
     * rises, so its 2.5th percentile comes from mu's top, and with interpolation it is not even
     * N0 of mu's 97.5th percentile. The expected ends are worked from the sorted N0 values.
     */
    @Test
    void theTransformComesBeforeThePercentiles() {
        double sigma = 2.4;
        double[] mu = new double[1000];
        for (int r = 0; r < mu.length; r++) {
            mu[(r * 7919) % 1000] = 0.001 * (r + 1);
        }
        double[] ci = RiskMath.percentileInterval(mu, x -> RiskMath.n0(x, sigma));
        // Sorted ascending, N0's i-th value is N0 at mu = 0.001 (1000 - i). The 2.5th
        // percentile sits at 999 x 0.025 = 24.975: between i = 24 and 25, 0.975 of the way.
        double n24 = RiskMath.n0(0.001 * 976, sigma);
        double n25 = RiskMath.n0(0.001 * 975, sigma);
        close(n24 + 0.975 * (n25 - n24), ci[0], 1e-14, "the lower end");
        double n974 = RiskMath.n0(0.001 * 26, sigma);
        double n975 = RiskMath.n0(0.001 * 25, sigma);
        close(n974 + 0.025 * (n975 - n974), ci[1], 1e-14, "the upper end");
        assertTrue(ci[0] < RiskMath.n0(0.0005 * 1001, sigma) && RiskMath.n0(0.0005 * 1001, sigma) < ci[1]);
        // The percentiles of mu, then the transform: a different number at either end.
        double[] sorted = mu.clone();
        Arrays.sort(sorted);
        double wrongLow = RiskMath.n0(RiskMath.quantile(sorted, 0.975), sigma);
        assertTrue(Math.abs(wrongLow - ci[0]) > 1e-9 * ci[0], "a percentile before the transform would pass");
    }

    /**
     * Planted bug: an infinite resample mishandled. 30 of 1,000 resamples have mu at or below
     * 0: their B_5% is +infinity, which sorts above every finite one, so the interval has no
     * upper bound and its lower end is unchanged. Taken naively, -ln(p) sigma^2 / (2 mu) is
     * negative for mu below 0 and would become the lower end.
     */
    @Test
    void resamplesWithNoWinGiveNoUpperBound() {
        double sigma = 2.4;
        double[] mu = new double[1000];
        for (int r = 0; r < mu.length; r++) {
            mu[r] = r < 30 ? -0.0001 * r : 0.00001 * r;
        }
        double[] ci = RiskMath.percentileInterval(mu, x -> RiskMath.bankrollFor(0.05, x, sigma));
        assertEquals(Double.POSITIVE_INFINITY, ci[1], 0);
        // The lower end: B_5% falls as mu rises, so ascending B_5% is mu descending from 0.00999.
        double b24 = RiskMath.bankrollFor(0.05, 0.00001 * 975, sigma);
        double b25 = RiskMath.bankrollFor(0.05, 0.00001 * 974, sigma);
        close(b24 + 0.975 * (b25 - b24), ci[0], 1e-14, "the lower end");
        assertTrue(ci[0] > 0);
        // N0 the same way; and a transform that gives NaN is refused, not sorted.
        assertEquals(Double.POSITIVE_INFINITY, RiskMath.percentileInterval(mu, x -> RiskMath.n0(x, sigma))[1], 0);
        assertThrows(IllegalArgumentException.class, () -> RiskMath.percentileInterval(mu, x -> Math.sqrt(x)));
    }

    // ------------------------------------------------------------------ the formulas against simulated play

    /**
     * The bankroll paths on records whose answer is the classical gambler's ruin: 600 shoes of
     * one round winning 1 and 400 losing 1, so each drawn shoe is +1 with probability 0.6. With
     * mu 0.2, sigma 1 and a stop risk of e^-4, a path stops once its bankroll passes
     * -ln(e^-4) / (2 x 0.2) = 10, that is on reaching 11; ruin is reaching 0. From B the chance of
     * 0 before 11 is (q^B - q^11) / (1 - q^11) with q = 0.4/0.6 (Feller vol. 1, XIV.2).
     */
    @Test
    void ruinPathsAreTheGamblersRuinOnOneStepShoes() {
        double[] y = new double[1000];
        int[] n = new int[1000];
        double[] m = new double[1000];
        for (int h = 0; h < 1000; h++) {
            y[h] = h < 600 ? 1 : -1;
            n[h] = 1;
            m[h] = Math.min(0, y[h]);
        }
        RiskMath.Records rec = new RiskMath.Records(y, n, m);
        double[] bankrolls = {1, 3, 6};
        int paths = 20_000;
        RiskMath.RuinPaths r = RiskMath.ruinPaths(rec, 0.2, 1, bankrolls, paths, 20261010L, Math.exp(-4), 1_000_000);
        double q = 0.4 / 0.6;
        for (int k = 0; k < bankrolls.length; k++) {
            double exact = (Math.pow(q, bankrolls[k]) - Math.pow(q, 11)) / (1 - Math.pow(q, 11));
            double se = RiskMath.binomialSe(exact, paths);
            assertTrue(Math.abs(r.simulated(k) - exact) < 4 * se,
                    "from " + bankrolls[k] + ": simulated " + r.simulated(k) + ", exact " + exact);
        }
        // Every bankroll plays the same draws, so a larger one is never ruined more often.
        assertTrue(r.ruined[0] >= r.ruined[1] && r.ruined[1] >= r.ruined[2]);
        // The same seed draws the same shoes.
        RiskMath.RuinPaths again = RiskMath.ruinPaths(rec, 0.2, 1, bankrolls, paths, 20261010L, Math.exp(-4), 1_000_000);
        assertArrayEquals(r.ruined, again.ruined);
        assertEquals(r.shoesDrawn, again.shoesDrawn);
        // A game that does not win has no level at which a path stops.
        assertThrows(IllegalArgumentException.class,
                () -> RiskMath.ruinPaths(rec, 0, 1, bankrolls, 10, 1L, 1e-4, 1000));
    }

    /**
     * Ruin within a shoe, not only at its end: a shoe that ends up 5 but fell to -4 on the way
     * ruins a bankroll of 4, and one of 4.5 survives it.
     */
    @Test
    void ruinCountsTheLowWithinAShoe() {
        RiskMath.Records rec = new RiskMath.Records(new double[]{5, 5}, new int[]{10, 10}, new double[]{-4, -4});
        RiskMath.RuinPaths r = RiskMath.ruinPaths(rec, 0.5, 1, new double[]{4, 4.5}, 100, 3L, 0.01, 1000);
        assertEquals(100, r.ruined[0]);
        assertEquals(0, r.ruined[1]);
        assertThrows(IllegalArgumentException.class,
                () -> new RiskMath.Records(new double[]{5, 1}, new int[]{10, 10}, new double[]{-4, 2}));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskMath.Records(new double[]{-5, 1}, new int[]{10, 10}, new double[]{-4, 0}));
    }

    /**
     * Blocks worked by hand. Shoes (y, n, m): (+2, 3, -1), (-3, 2, -3), (+1, 4, 0), (-1, 3, -2),
     * (+4, 2, 0). Blocks of at least 5 rounds: shoes 0-1 (5 rounds, total -1, low min(-1, 2 - 3)
     * = -1), shoes 2-3 (7 rounds, total 0, low min(0, 1 - 2) = -1); shoe 4 alone has 2 rounds and
     * is dropped. Behind (total below 0): the first only. A bankroll of 1 is reached in both, one
     * of 1.5 in neither.
     */
    @Test
    void blocksWorkedByHand() {
        RiskMath.Records rec = new RiskMath.Records(new double[]{2, -3, 1, -1, 4}, new int[]{3, 2, 4, 3, 2},
                new double[]{-1, -3, 0, -2, 0});
        RiskMath.Blocks b = RiskMath.blocks(rec, 5, new double[]{1, 1.5}, 0.1, 1);
        assertEquals(2, b.blocks);
        assertEquals(6, b.meanRounds, 0);
        assertEquals(1, b.behind);
        assertArrayEquals(new long[]{2, 0}, b.ruined);
        close((RiskMath.behind(0.1, 1, 5) + RiskMath.behind(0.1, 1, 7)) / 2, b.formulaBehind, 1e-15, "formula behind");
        close((RiskMath.tripRuin(0.1, 1, 1.5, 5) + RiskMath.tripRuin(0.1, 1, 1.5, 7)) / 2, b.formulaTrip[1], 1e-15,
                "formula trip");
        assertThrows(IllegalArgumentException.class, () -> RiskMath.blocks(rec, 100, new double[]{1}, 0.1, 1));
    }

    /**
     * The whole check of PLAN_COMPUTE.md 2.4, item 4, on records whose rounds are independent:
     * 200,000 shoes of 60 normal rounds, mean 0.05 and SD 1, so the Brownian formulas should hold
     * but for the discreteness of play. The paths' risk at B_25% and the blocks' trip risk and
     * chance of being behind each land within 4 SEs of the formula.
     */
    @Test
    void theFormulasHoldOnIndependentRounds() {
        int shoes = 200_000;
        Random rnd = new Random(20261014L);
        double[] y = new double[shoes];
        int[] n = new int[shoes];
        double[] m = new double[shoes];
        for (int h = 0; h < shoes; h++) {
            double x = 0;
            double low = 0;
            for (int i = 0; i < 60; i++) {
                x += 0.05 + rnd.nextGaussian();
                low = Math.min(low, x);
            }
            y[h] = x;
            n[h] = 60;
            m[h] = low;
        }
        RiskMath.Records rec = new RiskMath.Records(y, n, m);
        RiskMath.ShoeSums s = rec.sums();
        double mu = s.rate();
        double sigma = RiskMath.sigmaEff(s);
        double b25 = RiskMath.bankrollFor(0.25, mu, sigma);
        RiskMath.RuinPaths r = RiskMath.ruinPaths(rec, mu, sigma, new double[]{b25}, 4_000, 20261010L, 5e-5, 10_000_000);
        // Ruin is checked at every round, the formula's Brownian motion continuously: the walk
        // overshoots the barrier, so it is ruined a little less often (Siegmund's correction
        // would move B by about 0.58 sigma); at B_25% of about 14 that is within the SE here.
        assertTrue(Math.abs(r.simulated(0) - 0.25) < 4 * r.se(0), "ruin from B_25%: " + r.simulated(0));
        RiskMath.Blocks b = RiskMath.blocks(rec, 1000, new double[]{20}, mu, sigma);
        double behind = (double) b.behind / b.blocks;
        assertTrue(Math.abs(behind - b.formulaBehind) < 4 * RiskMath.binomialSe(b.formulaBehind, b.blocks),
                "behind: " + behind + " against " + b.formulaBehind);
        double trip = (double) b.ruined[0] / b.blocks;
        assertTrue(Math.abs(trip - b.formulaTrip[0]) < 4 * RiskMath.binomialSe(b.formulaTrip[0], b.blocks),
                "trip: " + trip + " against " + b.formulaTrip[0]);
    }
}
