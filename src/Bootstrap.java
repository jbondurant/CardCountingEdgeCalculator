import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * The bootstrap of COUNTING_COMPARISON.md, section 6: shoes resampled within each fold, as
 * multiplicity vectors drawn once from a fixed seed and shared by every count, variant,
 * penetration and spread. Because every estimate sees the same resamples, a difference
 * between two counts is taken resample by resample and stays paired.
 *
 * Resample r gives shoe j the multiplicity mult(r, j): the number of times j was drawn when
 * as many shoes as its fold holds were drawn from the fold with replacement. Shoes with no
 * valued state are drawn like any other, since they are part of how many rounds the run
 * dealt.
 */
final class Bootstrap {

    static final int RESAMPLES = 1000;
    /**
     * The seed of the multiplicity vectors. Any fixed number would do; it is fixed so that a
     * report run twice on the same file prints the same intervals.
     */
    static final long SEED = 20260929L;

    final int shoes;
    final int resamples;
    private final byte[][] mult;

    Bootstrap(int shoes, int resamples, long seed) {
        this.shoes = shoes;
        this.resamples = resamples;
        this.mult = new byte[resamples][shoes];
        SplittableRandom rnd = new SplittableRandom(seed);
        for (int r = 0; r < resamples; r++) {
            for (int f = 0; f < 2; f++) {
                int n = (shoes + 1 - f) / 2;
                for (int k = 0; k < n; k++) {
                    int j = f + 2 * rnd.nextInt(n);
                    if (mult[r][j] == Byte.MAX_VALUE) {
                        throw new IllegalStateException("a shoe drawn more than " + Byte.MAX_VALUE + " times");
                    }
                    mult[r][j]++;
                }
            }
        }
    }

    int mult(int r, int shoe) {
        return mult[r][shoe];
    }

    /** What the bootstrap says about one estimate. */
    static final class Interval {
        final double estimate;
        final double se;
        /** The mean of the resamples less the estimate. */
        final double bias;
        final double lo;
        final double hi;
        /** Whether the interval is the basic one, used because the bias is not small next to the SE. */
        final boolean basic;

        Interval(double estimate, double se, double bias, double lo, double hi, boolean basic) {
            this.estimate = estimate;
            this.se = se;
            this.bias = bias;
            this.lo = lo;
            this.hi = hi;
            this.basic = basic;
        }

        double halfWidth() {
            return (hi - lo) / 2;
        }
    }

    /**
     * A bias is taken as small when it is under a quarter of the SE: below that, shifting the
     * interval by it moves each end by less than an eighth of its half-width.
     */
    static final double SMALL_BIAS = 0.25;

    /**
     * The 95% interval: the percentile interval of the resamples, or the basic interval, 2 x
     * estimate less the percentiles, when the bias is not small next to the SE.
     */
    static Interval interval(double estimate, double[] boot) {
        double mean = 0;
        for (double b : boot) {
            mean += b;
        }
        mean /= boot.length;
        double ss = 0;
        for (double b : boot) {
            ss += (b - mean) * (b - mean);
        }
        double se = boot.length > 1 ? Math.sqrt(ss / (boot.length - 1)) : 0;
        double bias = mean - estimate;
        double[] sorted = boot.clone();
        Arrays.sort(sorted);
        double q025 = quantile(sorted, 0.025);
        double q975 = quantile(sorted, 0.975);
        boolean basic = Math.abs(bias) > SMALL_BIAS * se;
        if (basic) {
            return new Interval(estimate, se, bias, 2 * estimate - q975, 2 * estimate - q025, true);
        }
        return new Interval(estimate, se, bias, q025, q975, false);
    }

    /** The p quantile of sorted values, interpolating linearly between order statistics. */
    static double quantile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        double h = (sorted.length - 1) * p;
        int lo = (int) Math.floor(h);
        int hi = Math.min(lo + 1, sorted.length - 1);
        return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo]);
    }

    /**
     * The multiplier c of the maximum-|t| simultaneous 95% intervals, estimate +- c x SE,
     * over several estimates at once: the 0.95 quantile over resamples of the largest
     * |resample - estimate| / SE among them. Estimates with no spread are left out, as they
     * cannot be wrong.
     */
    static double maxT(double[] estimates, double[][] boots) {
        int resamples = -1;
        double[] se = new double[estimates.length];
        for (int j = 0; j < estimates.length; j++) {
            se[j] = interval(estimates[j], boots[j]).se;
            resamples = boots[j].length;
        }
        if (resamples <= 0) {
            return Double.NaN;
        }
        double[] t = new double[resamples];
        boolean any = false;
        for (int r = 0; r < resamples; r++) {
            double most = 0;
            for (int j = 0; j < estimates.length; j++) {
                if (se[j] > 0) {
                    most = Math.max(most, Math.abs(boots[j][r] - estimates[j]) / se[j]);
                    any = true;
                }
            }
            t[r] = most;
        }
        if (!any) {
            return Double.NaN;
        }
        Arrays.sort(t);
        return quantile(t, 0.95);
    }
}
