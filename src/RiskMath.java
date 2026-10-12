import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.function.DoubleUnaryOperator;

/**
 * The arithmetic of the swings, money per hour and risk of ruin (PLAN_COMPUTE.md section 2): every
 * formula the risk figures use, each with where it comes from and what it assumes. RiskReport
 * applies them to the engine's win rates and the simulator's swings; nothing here reads a file.
 *
 * Units throughout: one unit is the minimum bet; mu is the expected win a round in units (the
 * engine's Entry.estimate, so 0.0036 is the report's +0.36%); sigma is a standard deviation a
 * round in units; a bankroll B is in units; T is a number of rounds.
 *
 * The formulas, and what each assumes.
 * - Risk of ruin, playing for ever with a fixed unit: RoR(B) = exp(-2 mu B / sigma^2). This is the
 *   chance that a Brownian motion with drift mu and variance sigma^2 a round, started at B, ever
 *   reaches 0: the standard first-passage result for Brownian motion with drift, which follows
 *   from the reflection principle (the trip risk below tends to it as T grows, and RiskMathTest
 *   holds it to that limit and to simulated play). The blackjack literature's form of it, with
 *   its page in Blackjack Attack, is for the literature stage to quote; nothing here cites that
 *   book as checked. It assumes increments independent with a fixed mean and variance, no end to play,
 *   steps small next to B, and the unit never resized. For mu at most 0 ruin is certain.
 * - The bankroll for a risk p: B_p = -ln(p) sigma^2 / (2 mu), the inverse of the above.
 * - George C.'s form, ((1 - mu/sigma) / (1 + mu/sigma))^(B/sigma), the classical gambler's ruin
 *   (Feller, vol. 1, XIV.2) for a walk of steps +sigma and -sigma with mean mu. It agrees with the exponential to many digits when
 *   mu/sigma is small (here about 0.0015), and is kept only to show that.
 * - Trip risk, losing B within T rounds: P = Phi((-B - mu T)/(sigma sqrt T))
 *   + exp(-2 mu B / sigma^2) Phi((-B + mu T)/(sigma sqrt T)), the first-passage probability of
 *   the same Brownian motion by time T (the reflection principle with drift). Same assumptions,
 *   with an end to play at T.
 * - Behind after T rounds: Phi(-mu sqrt(T) / sigma), the normal approximation to a sum of T
 *   rounds; with mu > 0 it falls as T grows.
 * - N0 = sigma^2 / mu^2, the rounds until the expected win equals one standard deviation of the
 *   total (after which a player is still behind about 16% of the time, Phi(-1)). Credited to
 *   Brett Harris in Wattenberger's Modern Blackjack; the literature stage quotes the source.
 * - Per 100 rounds: EV 100 mu, SD 10 sigma. Per hour at r rounds an hour: EV mu r, SD sigma sqrt(r).
 *   The SD grows with the square root of the rounds because independent rounds add variances.
 *
 * Which sigma: every formula over time takes sigma_eff, the SD that scales with time
 * (sigmaEff), not the SD of one round. Shoes are independent, a new shuffle resetting the count,
 * so a session is a sum of shoe totals, and sigma_eff^2 is the variance of a shoe's total about
 * its expected win, per round. Rounds within a shoe are correlated through the count; how far is
 * measured by sigma_eff / sigma, not assumed.
 *
 * Uncertainty. A figure that is a function of mu (N0, B_p, a trip risk) is computed on each of
 * mu's bootstrap resamples with sigma held, and its interval is the 2.5 and 97.5 percentiles of
 * those values (percentileInterval): the transform first, the percentiles after. A resample with
 * mu at most 0 gives N0 and B_p of +infinity (the win never comes), which sorts above every
 * finite value, so an interval that reaches it has no upper bound.
 */
final class RiskMath {

    private RiskMath() {
    }

    // ------------------------------------------------------------------ the normal distribution

    private static final double SQRT2 = Math.sqrt(2);
    private static final double SQRT_PI = Math.sqrt(Math.PI);

    /** The standard normal density. */
    static double density(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
    }

    /** Phi(x), the standard normal distribution function, to about 1e-14 relative in each tail. */
    static double cdf(double x) {
        if (Double.isNaN(x)) {
            return Double.NaN;
        }
        if (x < 0) {
            return 0.5 * erfc(-x / SQRT2);
        }
        return 1 - 0.5 * erfc(x / SQRT2);
    }

    /**
     * e^a Phi(x), without overflow when a is large and Phi(x) tiny: for x below 0, Phi(x) is
     * 0.5 e^(-x^2/2) erfcx(-x / sqrt 2), so the exponents are added before exp is taken. The trip
     * risk needs it: with mu below 0 its second term is a huge e^a times a tiny Phi, and the
     * exponents' sum is -(B + mu T)^2 / (2 sigma^2 T), never above 0.
     */
    static double expTimesCdf(double a, double x) {
        if (x >= 0) {
            return Math.exp(a) * cdf(x);
        }
        double z = -x / SQRT2;
        return 0.5 * Math.exp(a - z * z) * erfcx(z);
    }

    /**
     * The complementary error function. Below 2, 1 - erf(z) from erf's series of positive terms,
     * erf(z) = (2/sqrt pi) e^(-z^2) sum over n of 2^n z^(2n+1) / (1 3 5 ... (2n+1)) (Abramowitz
     * and Stegun 7.1.6), which loses at most about 2e-14 relative where erfc is smallest (z = 2,
     * erfc 0.0047). From 2 up, e^(-z^2) erfcx(z) by the continued fraction, which keeps full
     * relative precision far into the tail. Below 0, 2 - erfc(-z).
     */
    static double erfc(double z) {
        if (z < 0) {
            return 2 - erfc(-z);
        }
        if (z < 2) {
            return 1 - erfSeries(z);
        }
        return Math.exp(-z * z) * erfcx(z);
    }

    private static double erfSeries(double z) {
        if (z == 0) {
            return 0;
        }
        double term = z;
        double sum = z;
        double twoZ2 = 2 * z * z;
        for (int n = 1; n < 500; n++) {
            term *= twoZ2 / (2 * n + 1);
            sum += term;
            if (term < 1e-17 * sum) {
                return 2 / SQRT_PI * Math.exp(-z * z) * sum;
            }
        }
        throw new IllegalStateException("erf's series did not converge at " + z);
    }

    /**
     * erfcx(z) = e^(z^2) erfc(z), for z at least 0. From 2 up by the continued fraction
     * erfc(z) = e^(-z^2)/sqrt(pi) / (z + (1/2)/(z + 1/(z + (3/2)/(z + 2/(z + ...)))))
     * (Abramowitz and Stegun 7.1.14), evaluated by the modified Lentz method; below 2 from erfc.
     */
    static double erfcx(double z) {
        if (z < 0) {
            throw new IllegalArgumentException("erfcx is used here only from 0 up, not at " + z);
        }
        if (z < 2) {
            return Math.exp(z * z) * (1 - erfSeries(z));
        }
        double tiny = 1e-300;
        double f = z;
        double c = f;
        double d = 0;
        for (int n = 1; n < 10_000; n++) {
            double a = n / 2.0;
            d = z + a * d;
            d = Math.abs(d) < tiny ? tiny : d;
            d = 1 / d;
            c = z + a / c;
            c = Math.abs(c) < tiny ? tiny : c;
            double delta = c * d;
            f *= delta;
            if (Math.abs(delta - 1) < 1e-16) {
                return 1 / (SQRT_PI * f);
            }
        }
        throw new IllegalStateException("erfc's continued fraction did not converge at " + z);
    }

    // ------------------------------------------------------------------ the chi-square test

    /**
     * The chance that a chi-square variable with df degrees of freedom is at least x: the
     * regularized upper incomplete gamma function Q(df/2, x/2), by its series below a + 1 and
     * its continued fraction above (the standard pair of methods, as in Press et al., Numerical
     * Recipes, section 6.2). For the check that the simulator's payoff histogram on small shoes
     * fits BruteForceRound's distribution (PLAN_COMPUTE.md 2.4, check 1).
     */
    static double chiSquareUpperTail(double x, int df) {
        if (df < 1) {
            throw new IllegalArgumentException("degrees of freedom " + df);
        }
        if (Double.isNaN(x)) {
            throw new IllegalArgumentException("a chi-square statistic of NaN");
        }
        if (x <= 0) {
            return 1;
        }
        if (x == Double.POSITIVE_INFINITY) {
            return 0;
        }
        double a = df / 2.0;
        double h = x / 2;
        double front = Math.exp(-h + a * Math.log(h) - logGammaHalf(df));
        if (h < a + 1) {
            double ap = a;
            double del = 1 / a;
            double sum = del;
            for (int n = 0; n < 100_000; n++) {
                ap += 1;
                del *= h / ap;
                sum += del;
                if (Math.abs(del) < Math.abs(sum) * 1e-16) {
                    return Math.max(0, 1 - sum * front);
                }
            }
            throw new IllegalStateException("the gamma series did not converge at " + x + ", " + df);
        }
        double tiny = 1e-300;
        double b = h + 1 - a;
        double c = 1 / tiny;
        double d = 1 / b;
        double f = d;
        for (int i = 1; i < 100_000; i++) {
            double an = -i * (i - a);
            b += 2;
            d = an * d + b;
            d = Math.abs(d) < tiny ? tiny : d;
            c = b + an / c;
            c = Math.abs(c) < tiny ? tiny : c;
            d = 1 / d;
            double delta = d * c;
            f *= delta;
            if (Math.abs(delta - 1) < 1e-16) {
                return f * front;
            }
        }
        throw new IllegalStateException("the gamma continued fraction did not converge at " + x + ", " + df);
    }

    /** ln Gamma(df / 2), exactly as a sum of logs: Gamma(n) = (n-1)!, Gamma(n + 1/2) = (n - 1/2) ... (1/2) sqrt(pi). */
    static double logGammaHalf(int df) {
        double s = df % 2 == 0 ? 0 : 0.5 * Math.log(Math.PI);
        for (double k = df % 2 == 0 ? 1 : 0.5; k < df / 2.0 - 1e-9; k += 1) {
            s += Math.log(k);
        }
        return s;
    }

    /** A chi-square goodness-of-fit test: the statistic, its degrees of freedom and its p-value. */
    static final class ChiSquare {
        final double statistic;
        final int df;
        final double p;
        /** The cells tested after pooling, and how many cells were pooled into one. */
        final int cells;
        final int pooled;

        ChiSquare(double statistic, int df, double p, int cells, int pooled) {
            this.statistic = statistic;
            this.df = df;
            this.p = p;
            this.cells = cells;
            this.pooled = pooled;
        }
    }

    /**
     * Pearson's test of observed counts against cell probabilities. Cells expected to hold fewer
     * than minExpected are pooled into one, the usual condition for the chi-square approximation
     * (and that pooled cell, if still short, joins the smallest cell kept). A count in a cell of
     * probability 0 is a payoff that cannot happen: the statistic is then infinite and p is 0.
     */
    static ChiSquare chiSquare(long[] observed, double[] probability, double minExpected) {
        if (observed.length != probability.length) {
            throw new IllegalArgumentException("observed and probability differ in length");
        }
        long n = 0;
        double total = 0;
        for (int i = 0; i < observed.length; i++) {
            if (observed[i] < 0 || !(probability[i] >= 0)) {
                throw new IllegalArgumentException("cell " + i + ": count " + observed[i] + ", probability " + probability[i]);
            }
            n += observed[i];
            total += probability[i];
        }
        if (Math.abs(total - 1) > 1e-9) {
            throw new IllegalArgumentException("the probabilities add up to " + total);
        }
        double[] expected = new double[0];
        long[] obs = new long[0];
        double restE = 0;
        long restO = 0;
        int pooled = 0;
        for (int i = 0; i < observed.length; i++) {
            if (probability[i] == 0) {
                if (observed[i] > 0) {
                    return new ChiSquare(Double.POSITIVE_INFINITY, Math.max(1, observed.length - 1), 0, observed.length, 0);
                }
                continue;
            }
            double e = n * probability[i];
            if (e < minExpected) {
                restE += e;
                restO += observed[i];
                pooled++;
            } else {
                expected = Arrays.copyOf(expected, expected.length + 1);
                obs = Arrays.copyOf(obs, obs.length + 1);
                expected[expected.length - 1] = e;
                obs[obs.length - 1] = observed[i];
            }
        }
        if (pooled > 0) {
            if (restE >= minExpected || expected.length == 0) {
                expected = Arrays.copyOf(expected, expected.length + 1);
                obs = Arrays.copyOf(obs, obs.length + 1);
                expected[expected.length - 1] = restE;
                obs[obs.length - 1] = restO;
            } else {
                int smallest = 0;
                for (int i = 1; i < expected.length; i++) {
                    smallest = expected[i] < expected[smallest] ? i : smallest;
                }
                expected[smallest] += restE;
                obs[smallest] += restO;
            }
        }
        if (expected.length < 2) {
            throw new IllegalArgumentException("fewer than two cells left to test");
        }
        double x = 0;
        for (int i = 0; i < expected.length; i++) {
            double d = obs[i] - expected[i];
            x += d * d / expected[i];
        }
        int df = expected.length - 1;
        return new ChiSquare(x, df, chiSquareUpperTail(x, df), expected.length, pooled);
    }

    // ------------------------------------------------------------------ one round's moments

    /**
     * The range of 2X, twice a round's result for a one-unit bet with insurance, as the rules
     * allow it: {lowest, highest}. The most a round loses is every hand doubled and lost, with
     * insurance lost too: X = -2 h - 1/2, h the most hands a split can make. The most it wins is
     * every hand doubled and won, 2 h, or a natural's payout if that is more (or h payouts where
     * a split ace and a ten is a blackjack); insurance only wins on a dealer natural, which ends
     * the round before any hand is doubled. At the Casino de Montreal's rules (4 hands, 3:2) this
     * is {-17, 16}, PLAN_COMPUTE.md 2.4's range. Every result the simulator counts is asserted to
     * fall inside it, so a wrong bound fails at once instead of clamping.
     */
    static int[] twiceResultRange(RoundRules rules) {
        int hands = Math.max(rules.maxHandsAces, rules.maxHandsNotAces);
        double most = Math.max(2.0 * hands, rules.blackjackPayout);
        if (rules.blackjackOnSplitPairs) {
            most = Math.max(most, hands * rules.blackjackPayout);
        }
        double least = -2.0 * hands - 0.5;
        if (2 * rules.blackjackPayout != Math.rint(2 * rules.blackjackPayout)) {
            throw new IllegalArgumentException("a payout of " + rules.blackjackPayout + " is not a whole number of halves");
        }
        return new int[]{(int) (2 * least), (int) (2 * most)};
    }

    /**
     * The moments of b X over a histogram of rounds: cell i holds count[i] rounds bet bet[i]
     * units with result twoX[i] / 2 a unit. The sums are taken exactly (each term is a double bet
     * times a whole number of halves, exact in BigDecimal) and only the final means are rounded,
     * so the moments are those of the histogram itself, whatever order its cells come in.
     */
    static final class Moments {
        final long rounds;
        /** E[b], E[b^2]: the bets placed, sat-out rounds counting 0. */
        final double meanBet;
        final double meanBetSquared;
        /** E[bX], E[(bX)^2], E[(bX)^3], E[(bX)^4], raw. */
        final double mean;
        final double meanSquare;
        final double meanCube;
        final double meanFourth;

        Moments(long rounds, double[] m) {
            this.rounds = rounds;
            this.meanBet = m[0];
            this.meanBetSquared = m[1];
            this.mean = m[2];
            this.meanSquare = m[3];
            this.meanCube = m[4];
            this.meanFourth = m[5];
        }

        /** sigma, the SD of one round's result: sqrt(E[(bX)^2] - E[bX]^2). */
        double sd() {
            return RiskMath.sd(meanSquare, mean);
        }
    }

    static Moments moments(double[] bet, int[] twoX, long[] count) {
        if (bet.length != twoX.length || bet.length != count.length) {
            throw new IllegalArgumentException("the histogram's columns differ in length");
        }
        BigDecimal[] s = new BigDecimal[6];
        Arrays.fill(s, BigDecimal.ZERO);
        long rounds = 0;
        for (int i = 0; i < bet.length; i++) {
            if (count[i] < 0 || !(bet[i] >= 0) || Double.isInfinite(bet[i])) {
                throw new IllegalArgumentException("cell " + i + ": bet " + bet[i] + ", count " + count[i]);
            }
            rounds = Math.addExact(rounds, count[i]);
            BigDecimal c = BigDecimal.valueOf(count[i]);
            BigDecimal b = new BigDecimal(bet[i]);
            BigDecimal z = b.multiply(BigDecimal.valueOf(twoX[i])).divide(BigDecimal.valueOf(2));
            s[0] = s[0].add(c.multiply(b));
            s[1] = s[1].add(c.multiply(b.pow(2)));
            for (int k = 1; k <= 4; k++) {
                s[1 + k] = s[1 + k].add(c.multiply(z.pow(k)));
            }
        }
        if (rounds == 0) {
            throw new IllegalArgumentException("an empty histogram");
        }
        double[] m = new double[6];
        BigDecimal n = BigDecimal.valueOf(rounds);
        for (int k = 0; k < 6; k++) {
            m[k] = s[k].divide(n, MathContext.DECIMAL128).doubleValue();
        }
        return new Moments(rounds, m);
    }

    /**
     * sqrt(E[Z^2] - E[Z]^2), the SD from the mean square and the mean. The mean's square is
     * subtracted even where it is tiny next to the mean square (at a 0.36% edge it moves sigma by
     * about 1e-6 relative): it is the definition, and a test holds it there. A variance below 0
     * by more than rounding is refused, since it means the moments do not belong together.
     */
    static double sd(double meanSquare, double mean) {
        double v = meanSquare - mean * mean;
        if (v < -1e-12 * meanSquare) {
            throw new IllegalArgumentException("a mean square of " + meanSquare + " is below the square of the mean " + mean);
        }
        return Math.sqrt(Math.max(0, v));
    }

    /**
     * What the simulator adds up over whole shoes for one figure (PLAN_COMPUTE.md 2.4): the
     * number of shoes, and over them the sums of Y, Y^2, Y^3, Y^4, N, N^2 and Y N, Y a shoe's
     * total won in units and N its rounds, every round dealt.
     */
    static final class ShoeSums {
        final long shoes;
        final double sumY;
        final double sumY2;
        final double sumY3;
        final double sumY4;
        final double sumN;
        final double sumN2;
        final double sumYN;

        ShoeSums(long shoes, double sumY, double sumY2, double sumY3, double sumY4, double sumN, double sumN2,
                 double sumYN) {
            if (shoes < 2 || !(sumN > 0) || !(sumY2 >= 0) || !(sumY4 >= 0) || !(sumN2 >= 0)) {
                throw new IllegalArgumentException("shoe sums of " + shoes + " shoes, " + sumN + " rounds");
            }
            this.shoes = shoes;
            this.sumY = sumY;
            this.sumY2 = sumY2;
            this.sumY3 = sumY3;
            this.sumY4 = sumY4;
            this.sumN = sumN;
            this.sumN2 = sumN2;
            this.sumYN = sumYN;
        }

        /** The win a round over these shoes, sum Y / sum N: the ratio estimator. */
        double rate() {
            return sumY / sumN;
        }
    }

    /**
     * sigma_eff, the SD a round that scales with time: sigma_eff^2 = Var(Y - mu N) / E[N] with
     * mu = sum Y / sum N, estimated as S/(S-1) sum (Y - mu N)^2 / sum N over S shoes, the sum
     * expanded as sum Y^2 - 2 mu sum Y N + mu^2 sum N^2. Since shoes are independent, the total
     * of many shoes has variance about sigma_eff^2 times its rounds; SIM_CHECK.md section 6
     * defines the simulator's SE the same way, SE^2 = sigma_eff^2 / sum N.
     */
    static double sigmaEff(ShoeSums s) {
        double mu = s.rate();
        double ss = s.sumY2 - 2 * mu * s.sumYN + mu * mu * s.sumN2;
        if (ss < -1e-12 * s.sumY2) {
            throw new IllegalArgumentException("the shoe sums give a negative sum of squares, " + ss);
        }
        return Math.sqrt((double) s.shoes / (s.shoes - 1) * Math.max(0, ss) / s.sumN);
    }

    /**
     * An approximate SE of sigma_eff, by the delta method from the fourth moment of a shoe's
     * total: Var(s^2) is about (m4 - m2^2) / S for a sample variance, m2 and m4 the central
     * moments of Y, so with N taken at its mean, SE(sigma_eff^2) = sqrt((m4 - m2^2) / S) / mean N
     * and SE(sigma_eff) = SE(sigma_eff^2) / (2 sigma_eff). Taking N at its mean ignores how a
     * shoe's rounds vary (a few in 60 at a cut card), so this is for a footnote, not a test.
     */
    static double sigmaEffSe(ShoeSums s) {
        double n = s.shoes;
        double ybar = s.sumY / n;
        double ey2 = s.sumY2 / n;
        double ey3 = s.sumY3 / n;
        double ey4 = s.sumY4 / n;
        double m2 = ey2 - ybar * ybar;
        double m4 = ey4 - 4 * ybar * ey3 + 6 * ybar * ybar * ey2 - 3 * ybar * ybar * ybar * ybar;
        double nbar = s.sumN / n;
        double seVar = Math.sqrt(Math.max(0, m4 - m2 * m2) / n) / nbar;
        return seVar / (2 * sigmaEff(s));
    }

    // ------------------------------------------------------------------ the formulas over time

    private static void requireSigma(double sigma) {
        if (!(sigma > 0) || Double.isInfinite(sigma)) {
            throw new IllegalArgumentException("a standard deviation of " + sigma);
        }
    }

    /** N0 = sigma^2 / mu^2, the rounds until the expected win is one SD; +infinity when mu is at most 0. */
    static double n0(double mu, double sigma) {
        requireSigma(sigma);
        return mu > 0 ? sigma * sigma / (mu * mu) : Double.POSITIVE_INFINITY;
    }

    /** RoR(B) = exp(-2 mu B / sigma^2), playing for ever; 1 when mu is at most 0 or B at most 0. */
    static double ruin(double mu, double sigma, double bankroll) {
        requireSigma(sigma);
        if (!(mu > 0) || !(bankroll > 0)) {
            return 1;
        }
        return Math.exp(-2 * mu * bankroll / (sigma * sigma));
    }

    /** B_p = -ln(p) sigma^2 / (2 mu), the bankroll whose risk of ruin is p; +infinity when mu is at most 0. */
    static double bankrollFor(double risk, double mu, double sigma) {
        requireSigma(sigma);
        if (!(risk > 0 && risk < 1)) {
            throw new IllegalArgumentException("a risk of " + risk);
        }
        return mu > 0 ? -Math.log(risk) * sigma * sigma / (2 * mu) : Double.POSITIVE_INFINITY;
    }

    /** George C.'s ((1 - mu/sigma) / (1 + mu/sigma))^(B/sigma), in logs for precision; 1 when mu is at most 0. */
    static double ruinGeorgeC(double mu, double sigma, double bankroll) {
        requireSigma(sigma);
        if (!(mu > 0) || !(bankroll > 0)) {
            return 1;
        }
        double e = mu / sigma;
        if (e >= 1) {
            return 0;
        }
        return Math.exp(bankroll / sigma * (Math.log1p(-e) - Math.log1p(e)));
    }

    /**
     * Trip risk, the chance of losing B within T rounds: Phi((-B - mu T)/(sigma sqrt T)) +
     * exp(-2 mu B / sigma^2) Phi((-B + mu T)/(sigma sqrt T)). With mu = 0 it is 2 Phi(-B/(sigma
     * sqrt T)); as T grows it tends to ruin(mu, sigma, B) for mu above 0 and to 1 for mu below.
     * The second term is taken through expTimesCdf, so it stays finite when mu is below 0.
     */
    static double tripRuin(double mu, double sigma, double bankroll, double rounds) {
        requireSigma(sigma);
        if (!(bankroll > 0)) {
            return 1;
        }
        if (!(rounds > 0)) {
            return 0;
        }
        double s = sigma * Math.sqrt(rounds);
        double first = cdf((-bankroll - mu * rounds) / s);
        double second = expTimesCdf(-2 * mu * bankroll / (sigma * sigma), (-bankroll + mu * rounds) / s);
        return Math.min(1, first + second);
    }

    /** The chance of being behind after T rounds, Phi(-mu sqrt(T) / sigma). */
    static double behind(double mu, double sigma, double rounds) {
        requireSigma(sigma);
        if (!(rounds > 0)) {
            throw new IllegalArgumentException(rounds + " rounds");
        }
        return cdf(-mu * Math.sqrt(rounds) / sigma);
    }

    /** The expected win over T rounds, mu T. */
    static double evOver(double mu, double rounds) {
        return mu * rounds;
    }

    /** The SD of the total over T rounds, sigma sqrt(T): independent stretches add variances, not SDs. */
    static double sdOver(double sigma, double rounds) {
        requireSigma(sigma);
        return sigma * Math.sqrt(rounds);
    }

    // ------------------------------------------------------------------ percentile intervals

    /**
     * The p quantile of sorted values, interpolating linearly between order statistics as
     * Bootstrap.quantile does, so a finite interval here matches the report's convention; but
     * where either neighbour is +infinity the quantile is +infinity (Bootstrap.quantile would give
     * NaN from infinity minus infinity, or from 0 times infinity). NaN is refused.
     */
    static double quantile(double[] sorted, double p) {
        if (sorted.length == 0) {
            throw new IllegalArgumentException("no values");
        }
        double h = (sorted.length - 1) * p;
        int lo = (int) Math.floor(h);
        int hi = Math.min(lo + 1, sorted.length - 1);
        double frac = h - lo;
        if (sorted[lo] == Double.POSITIVE_INFINITY || (frac > 0 && sorted[hi] == Double.POSITIVE_INFINITY)) {
            return Double.POSITIVE_INFINITY;
        }
        if (sorted[lo] == Double.NEGATIVE_INFINITY || (frac > 0 && sorted[hi] == Double.NEGATIVE_INFINITY)) {
            return Double.NEGATIVE_INFINITY;
        }
        return sorted[lo] + frac * (sorted[hi] - sorted[lo]);
    }

    /**
     * The 95% percentile interval {2.5th, 97.5th} of f over the resamples: f is applied to each
     * resample first and the percentiles taken after. Taken the other way, a percentile of mu and
     * then f, a decreasing f would put mu's upper end at the figure's lower end, and the
     * interpolation would no longer be between values the figure takes. A NaN from f is refused.
     */
    static double[] percentileInterval(double[] resamples, DoubleUnaryOperator f) {
        double[] v = new double[resamples.length];
        for (int r = 0; r < v.length; r++) {
            v[r] = f.applyAsDouble(resamples[r]);
            if (Double.isNaN(v[r])) {
                throw new IllegalArgumentException("resample " + r + " (" + resamples[r] + ") gives NaN");
            }
        }
        Arrays.sort(v);
        return new double[]{quantile(v, 0.025), quantile(v, 0.975)};
    }

    // ------------------------------------------------------------------ the formulas against simulated play

    /**
     * One figure's per-shoe records from the simulator (SIM_CHECK.md section 11): shoe h's total
     * won y[h] in units, its rounds n[h], and m[h], the lowest its running total fell to during
     * the shoe, counting the start, so m[h] is at most 0 and at most y[h].
     */
    static final class Records {
        final double[] y;
        final int[] n;
        final double[] m;

        Records(double[] y, int[] n, double[] m) {
            if (y.length != n.length || y.length != m.length || y.length < 2) {
                throw new IllegalArgumentException("records of " + y.length + ", " + n.length + " and " + m.length + " shoes");
            }
            for (int h = 0; h < y.length; h++) {
                if (n[h] < 1 || !(m[h] <= 0) || !(m[h] <= y[h]) || Double.isInfinite(y[h]) || Double.isInfinite(m[h])) {
                    throw new IllegalArgumentException("shoe " + h + ": y " + y[h] + ", n " + n[h] + ", m " + m[h]);
                }
            }
            this.y = y;
            this.n = n;
            this.m = m;
        }

        /** The shoe sums of these records, for the formulas at the simulator's own mu and sigma_eff. */
        ShoeSums sums() {
            double[] s = new double[7];
            for (int h = 0; h < y.length; h++) {
                double v = y[h];
                s[0] += v;
                s[1] += v * v;
                s[2] += v * v * v;
                s[3] += v * v * v * v;
                s[4] += n[h];
                s[5] += (double) n[h] * n[h];
                s[6] += v * n[h];
            }
            return new ShoeSums(y.length, s[0], s[1], s[2], s[3], s[4], s[5], s[6]);
        }
    }

    /** A binomial proportion's SE, sqrt(p (1 - p) / n). */
    static double binomialSe(double p, long n) {
        return Math.sqrt(p * (1 - p) / n);
    }

    /** What the bankroll paths found (PLAN_COMPUTE.md 2.4, check 4), beside the formula. */
    static final class RuinPaths {
        final double mu;
        final double sigma;
        final double[] bankrolls;
        final int paths;
        final long[] ruined;
        /** Shoes drawn over every path, for the report's timing line. */
        final long shoesDrawn;
        final double stopRisk;

        RuinPaths(double mu, double sigma, double[] bankrolls, int paths, long[] ruined, long shoesDrawn, double stopRisk) {
            this.mu = mu;
            this.sigma = sigma;
            this.bankrolls = bankrolls;
            this.paths = paths;
            this.ruined = ruined;
            this.shoesDrawn = shoesDrawn;
            this.stopRisk = stopRisk;
        }

        double simulated(int k) {
            return (double) ruined[k] / paths;
        }

        double se(int k) {
            return binomialSe(simulated(k), paths);
        }

        double formula(int k) {
            return ruin(mu, sigma, bankrolls[k]);
        }
    }

    /**
     * Bankroll paths that play whole shoes drawn with replacement from the records: path j draws
     * with a SplittableRandom seeded ShoeRun.mix(seed, j, 0), and every starting bankroll in
     * bankrolls is run on the same draws. A path is ruined at bankroll b when b + m[h] is at most
     * 0 for the shoe h it plays, that is if its running total ever reaches 0 within the shoe;
     * otherwise b grows by y[h]. A path stops, not ruined, once the formula's remaining risk
     * exp(-2 mu b / sigma^2) is under stopRisk, so the simulated risk is low by at most stopRisk.
     * Drawing shoes whole keeps the count's correlation within a shoe, which the formula folds
     * into sigma_eff; the gap between the two is the measurement.
     */
    static RuinPaths ruinPaths(Records rec, double mu, double sigma, double[] bankrolls, int paths, long seed,
                               double stopRisk, long maxShoesAPath) {
        requireSigma(sigma);
        if (!(mu > 0)) {
            throw new IllegalArgumentException("with a win rate of " + mu + " no path ever stops; ruin is certain");
        }
        double stop = -Math.log(stopRisk) * sigma * sigma / (2 * mu);
        long[] ruined = new long[bankrolls.length];
        long drawn = 0;
        int shoes = rec.y.length;
        double[] b = new double[bankrolls.length];
        for (int j = 0; j < paths; j++) {
            SplittableRandom rnd = new SplittableRandom(ShoeRun.mix(seed, j, 0));
            int alive = 0;
            boolean[] on = new boolean[bankrolls.length];
            for (int k = 0; k < b.length; k++) {
                b[k] = bankrolls[k];
                on[k] = b[k] <= stop;
                alive += on[k] ? 1 : 0;
            }
            for (long t = 0; alive > 0; t++) {
                if (t == maxShoesAPath) {
                    throw new IllegalStateException("path " + j + " played " + t + " shoes without stopping");
                }
                int h = rnd.nextInt(shoes);
                drawn++;
                for (int k = 0; k < b.length; k++) {
                    if (!on[k]) {
                        continue;
                    }
                    if (b[k] + rec.m[h] <= 0) {
                        ruined[k]++;
                        on[k] = false;
                        alive--;
                    } else {
                        b[k] += rec.y[h];
                        if (b[k] > stop) {
                            on[k] = false;
                            alive--;
                        }
                    }
                }
            }
        }
        return new RuinPaths(mu, sigma, bankrolls.clone(), paths, ruined, drawn, stopRisk);
    }

    /** Trip risk and the chance of being behind over consecutive blocks of shoes, beside the formulas. */
    static final class Blocks {
        final long roundsPerBlock;
        final int blocks;
        /** The blocks' mean rounds: each block is the fewest whole shoes reaching roundsPerBlock. */
        final double meanRounds;
        /** Blocks that ended behind, total below 0. */
        final long behind;
        /** The mean over blocks of behind(mu, sigma, its rounds). */
        final double formulaBehind;
        final double[] bankrolls;
        /** Blocks whose running total reached -B, per bankroll. */
        final long[] ruined;
        /** The mean over blocks of tripRuin(mu, sigma, B, its rounds), per bankroll. */
        final double[] formulaTrip;

        Blocks(long roundsPerBlock, int blocks, double meanRounds, long behind, double formulaBehind, double[] bankrolls,
               long[] ruined, double[] formulaTrip) {
            this.roundsPerBlock = roundsPerBlock;
            this.blocks = blocks;
            this.meanRounds = meanRounds;
            this.behind = behind;
            this.formulaBehind = formulaBehind;
            this.bankrolls = bankrolls;
            this.ruined = ruined;
            this.formulaTrip = formulaTrip;
        }
    }

    /**
     * Cuts the records, in shoe order from shoe 0, into blocks of the fewest whole shoes that
     * reach roundsPerBlock rounds, dropping a last block that falls short (SIM_CHECK.md section
     * 11), and counts the blocks that end behind and those whose running total reaches -B for
     * each bankroll B. Each block's running low is the lowest of its total before each shoe plus
     * that shoe's m. The formulas are taken at each block's own rounds and averaged, so the two
     * sides describe the same blocks.
     */
    static Blocks blocks(Records rec, long roundsPerBlock, double[] bankrolls, double mu, double sigma) {
        requireSigma(sigma);
        if (roundsPerBlock < 1) {
            throw new IllegalArgumentException("blocks of " + roundsPerBlock + " rounds");
        }
        long[] ruined = new long[bankrolls.length];
        double[] trip = new double[bankrolls.length];
        int blocks = 0;
        long behind = 0;
        double behindSum = 0;
        double roundsSum = 0;
        int h = 0;
        while (true) {
            long rounds = 0;
            double total = 0;
            double low = 0;
            while (rounds < roundsPerBlock && h < rec.y.length) {
                low = Math.min(low, total + rec.m[h]);
                total += rec.y[h];
                rounds += rec.n[h];
                h++;
            }
            if (rounds < roundsPerBlock) {
                break;
            }
            blocks++;
            roundsSum += rounds;
            behind += total < 0 ? 1 : 0;
            behindSum += behind(mu, sigma, rounds);
            for (int k = 0; k < bankrolls.length; k++) {
                ruined[k] += bankrolls[k] + low <= 0 ? 1 : 0;
                trip[k] += tripRuin(mu, sigma, bankrolls[k], rounds);
            }
        }
        if (blocks == 0) {
            throw new IllegalArgumentException("the records hold fewer than " + roundsPerBlock + " rounds");
        }
        for (int k = 0; k < trip.length; k++) {
            trip[k] /= blocks;
        }
        return new Blocks(roundsPerBlock, blocks, roundsSum / blocks, behind, behindSum / blocks, bankrolls.clone(),
                ruined, trip);
    }
}
