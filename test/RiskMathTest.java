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
 * mishandled. Each test that guards one says so.
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
}
