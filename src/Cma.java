import java.util.Arrays;
import java.util.Random;

/**
 * CMA-ES, the covariance matrix adaptation evolution strategy, maximising (COUNT_DESIGN.md,
 * section 4): each generation draws lambda candidates from a normal distribution about the mean,
 * moves the mean to the weighted mean of the best half, and adapts the step size and the shape
 * of the distribution from the path the mean has taken, so it learns correlated directions and
 * does not depend on the choice of axes. The settings are Hansen's ("The CMA Evolution Strategy:
 * A Tutorial", 2016, table 1), the weights of the best half positive and the rest unused.
 *
 * On a sphere (radius > 0), the mean is put back on the sphere after every generation and the
 * step scaled with it, so a step keeps its angle: the search over directions the count search
 * makes, where the objective sees only a candidate's direction.
 *
 * It is deterministic: generation g draws from a generator seeded from the seed and g alone, so a
 * search stopped after any generation and resumed from its State goes on exactly as it would have.
 */
final class Cma {

    /** Everything a search needs to go on: the distribution, its paths and the counters. */
    static final class State {
        double[] mean;
        double sigma;
        double[][] c;
        double[] pc;
        double[] ps;
        int generation;
        int evaluations;

        State copy() {
            State s = new State();
            s.mean = mean.clone();
            s.sigma = sigma;
            s.c = new double[c.length][];
            for (int i = 0; i < c.length; i++) {
                s.c[i] = c[i].clone();
            }
            s.pc = pc.clone();
            s.ps = ps.clone();
            s.generation = generation;
            s.evaluations = evaluations;
            return s;
        }
    }

    final int n;
    final int lambda;
    final int mu;
    final long seed;
    /** The sphere the mean is kept on; 0 for none. */
    final double radius;
    private final double[] weights;
    private final double mueff;
    private final double cc;
    private final double cs;
    private final double c1;
    private final double cmu;
    private final double damps;
    private final double chiN;
    State state;
    /** The eigenvectors (columns) and the square roots of the eigenvalues of C. */
    private double[][] b;
    private double[] d;

    Cma(int n, int lambda, long seed, double[] mean, double sigma, double radius) {
        if (n < 1 || lambda < 2 || mean.length != n || !(sigma > 0)) {
            throw new IllegalArgumentException("a search needs dimensions, two candidates a generation and a step");
        }
        this.n = n;
        this.lambda = lambda;
        this.mu = lambda / 2;
        this.seed = seed;
        this.radius = radius;
        weights = new double[mu];
        double sum = 0;
        for (int i = 0; i < mu; i++) {
            weights[i] = Math.log(mu + 0.5) - Math.log(i + 1);
            sum += weights[i];
        }
        double sq = 0;
        for (int i = 0; i < mu; i++) {
            weights[i] /= sum;
            sq += weights[i] * weights[i];
        }
        mueff = 1 / sq;
        cc = (4 + mueff / n) / (n + 4 + 2 * mueff / n);
        cs = (mueff + 2) / (n + mueff + 5);
        c1 = 2 / ((n + 1.3) * (n + 1.3) + mueff);
        cmu = Math.min(1 - c1, 2 * (mueff - 2 + 1 / mueff) / ((n + 2) * (n + 2) + mueff));
        damps = 1 + 2 * Math.max(0, Math.sqrt((mueff - 1) / (n + 1)) - 1) + cs;
        chiN = Math.sqrt(n) * (1 - 1.0 / (4 * n) + 1.0 / (21.0 * n * n));
        State s = new State();
        s.mean = mean.clone();
        s.sigma = sigma;
        s.c = new double[n][n];
        for (int i = 0; i < n; i++) {
            s.c[i][i] = 1;
        }
        s.pc = new double[n];
        s.ps = new double[n];
        restore(s);
        if (radius > 0) {
            onSphere();
        }
    }

    /** Takes up a search where its state was saved. */
    void restore(State s) {
        if (s.mean.length != n) {
            throw new IllegalArgumentException("a state of " + s.mean.length + " dimensions, not " + n);
        }
        state = s.copy();
        decompose();
    }

    /** The step as an angle on the sphere, in radians; the step itself when there is no sphere. */
    double step() {
        return radius > 0 ? state.sigma / radius : state.sigma;
    }

    /** This generation's candidates, lambda of them. */
    double[][] ask() {
        Random rnd = new Random(seed * 0x9E3779B97F4A7C15L + state.generation * 0xBF58476D1CE4E5B9L + 1);
        double[][] xs = new double[lambda][n];
        for (int k = 0; k < lambda; k++) {
            double[] z = new double[n];
            for (int i = 0; i < n; i++) {
                z[i] = rnd.nextGaussian() * d[i];
            }
            for (int i = 0; i < n; i++) {
                double y = 0;
                for (int j = 0; j < n; j++) {
                    y += b[i][j] * z[j];
                }
                xs[k][i] = state.mean[i] + state.sigma * y;
            }
        }
        return xs;
    }

    /** Moves the distribution on from the candidates ask gave and their values, larger better. */
    void tell(double[][] xs, double[] f) {
        if (xs.length != lambda || f.length != lambda) {
            throw new IllegalArgumentException("a value for each of the generation's " + lambda + " candidates");
        }
        Integer[] order = new Integer[lambda];
        for (int k = 0; k < lambda; k++) {
            order[k] = k;
            if (Double.isNaN(f[k])) {
                throw new IllegalArgumentException("candidate " + k + " has no value");
            }
        }
        // Best first; equal values keep the order they were drawn in.
        Arrays.sort(order, (x, y) -> Double.compare(f[y], f[x]));
        State s = state;
        double[] old = s.mean.clone();
        double[] mean = new double[n];
        for (int i = 0; i < mu; i++) {
            for (int j = 0; j < n; j++) {
                mean[j] += weights[i] * xs[order[i]][j];
            }
        }
        double[] yw = new double[n];
        for (int j = 0; j < n; j++) {
            yw[j] = (mean[j] - old[j]) / s.sigma;
        }
        // C^-1/2 yw = B D^-1 B' yw.
        double[] t = new double[n];
        for (int i = 0; i < n; i++) {
            double v = 0;
            for (int j = 0; j < n; j++) {
                v += b[j][i] * yw[j];
            }
            t[i] = v / d[i];
        }
        double[] inv = new double[n];
        for (int i = 0; i < n; i++) {
            double v = 0;
            for (int j = 0; j < n; j++) {
                v += b[i][j] * t[j];
            }
            inv[i] = v;
        }
        double a = Math.sqrt(cs * (2 - cs) * mueff);
        double psNorm = 0;
        for (int i = 0; i < n; i++) {
            s.ps[i] = (1 - cs) * s.ps[i] + a * inv[i];
            psNorm += s.ps[i] * s.ps[i];
        }
        psNorm = Math.sqrt(psNorm);
        boolean hsig = psNorm / Math.sqrt(1 - Math.pow(1 - cs, 2.0 * (s.generation + 1))) / chiN < 1.4 + 2.0 / (n + 1);
        double ac = Math.sqrt(cc * (2 - cc) * mueff);
        for (int i = 0; i < n; i++) {
            s.pc[i] = (1 - cc) * s.pc[i] + (hsig ? ac * yw[i] : 0);
        }
        double[][] y = new double[mu][n];
        for (int k = 0; k < mu; k++) {
            for (int j = 0; j < n; j++) {
                y[k][j] = (xs[order[k]][j] - old[j]) / s.sigma;
            }
        }
        double keep = 1 - c1 - cmu + (hsig ? 0 : c1 * cc * (2 - cc));
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double rankMu = 0;
                for (int k = 0; k < mu; k++) {
                    rankMu += weights[k] * y[k][i] * y[k][j];
                }
                double v = keep * s.c[i][j] + c1 * s.pc[i] * s.pc[j] + cmu * rankMu;
                s.c[i][j] = v;
                s.c[j][i] = v;
            }
        }
        s.sigma *= Math.exp((cs / damps) * (psNorm / chiN - 1));
        s.mean = mean;
        s.generation++;
        s.evaluations += lambda;
        if (radius > 0) {
            onSphere();
        }
        decompose();
    }

    /** Puts the mean back on the sphere and scales the step with it, so the step keeps its angle. */
    private void onSphere() {
        double r = 0;
        for (double v : state.mean) {
            r += v * v;
        }
        r = Math.sqrt(r);
        if (!(r > 0)) {
            throw new IllegalStateException("the mean is at the centre of the sphere");
        }
        for (int i = 0; i < n; i++) {
            state.mean[i] *= radius / r;
        }
        state.sigma *= radius / r;
    }

    /** B and D from C, by Jacobi rotations; C is kept symmetric. */
    private void decompose() {
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) {
            a[i] = state.c[i].clone();
        }
        double[][] v = new double[n][n];
        for (int i = 0; i < n; i++) {
            v[i][i] = 1;
        }
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    off += a[i][j] * a[i][j];
                }
            }
            if (off < 1e-30) {
                break;
            }
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (a[p][q] == 0) {
                        continue;
                    }
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double tt = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) {
                        tt = 1;
                    }
                    double c = 1 / Math.sqrt(tt * tt + 1);
                    double sn = tt * c;
                    for (int k = 0; k < n; k++) {
                        double akp = a[k][p];
                        double akq = a[k][q];
                        a[k][p] = c * akp - sn * akq;
                        a[k][q] = sn * akp + c * akq;
                    }
                    for (int k = 0; k < n; k++) {
                        double apk = a[p][k];
                        double aqk = a[q][k];
                        a[p][k] = c * apk - sn * aqk;
                        a[q][k] = sn * apk + c * aqk;
                    }
                    for (int k = 0; k < n; k++) {
                        double vkp = v[k][p];
                        double vkq = v[k][q];
                        v[k][p] = c * vkp - sn * vkq;
                        v[k][q] = sn * vkp + c * vkq;
                    }
                }
            }
        }
        b = v;
        d = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = Math.sqrt(Math.max(a[i][i], 1e-300));
        }
    }

    /** The largest over the smallest axis of the distribution: how stretched C has grown. */
    double condition() {
        double lo = Double.POSITIVE_INFINITY;
        double hi = 0;
        for (double x : d) {
            lo = Math.min(lo, x);
            hi = Math.max(hi, x);
        }
        return hi / lo;
    }

    /** The principal axes of the distribution, as unit vectors, the longest first: for the slices. */
    double[][] axes() {
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (x, y) -> Double.compare(d[y], d[x]));
        double[][] out = new double[n][n];
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < n; i++) {
                out[k][i] = b[i][order[k]];
            }
        }
        return out;
    }
}
