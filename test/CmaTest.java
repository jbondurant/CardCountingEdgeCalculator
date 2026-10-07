import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CMA-ES (Cma) reaches the known optima of standard test functions in 8 dimensions, and of a
 * scale-invariant one scored through the counts' own normalisation, the same way for the same
 * seed; and a search stopped and resumed from its log goes on exactly as it would have
 * (COUNT_DESIGN.md, section 6).
 */
public class CmaTest {

    private static final int N = 8;

    interface F {
        double of(double[] x);
    }

    /** Runs until the step is under tol or the evaluations are spent; gives the final mean. */
    private static double[] maximise(Cma cma, F f, int maxEvals, double tol) {
        while (cma.state.evaluations < maxEvals && cma.step() > tol) {
            double[][] xs = cma.ask();
            double[] v = new double[xs.length];
            for (int k = 0; k < xs.length; k++) {
                v[k] = f.of(xs[k]);
            }
            cma.tell(xs, v);
        }
        return cma.state.mean;
    }

    private static double[] optimum(long seed) {
        Random rnd = new Random(seed);
        double[] x = new double[N];
        for (int i = 0; i < N; i++) {
            x[i] = 2 * rnd.nextDouble() - 1;
        }
        return x;
    }

    private static double distance(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += (a[i] - b[i]) * (a[i] - b[i]);
        }
        return Math.sqrt(s);
    }

    /** A random rotation: Gram-Schmidt on Gaussian vectors. */
    private static double[][] rotation(long seed) {
        Random rnd = new Random(seed);
        double[][] q = new double[N][N];
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                q[i][j] = rnd.nextGaussian();
            }
            for (int k = 0; k < i; k++) {
                double d = 0;
                for (int j = 0; j < N; j++) {
                    d += q[i][j] * q[k][j];
                }
                for (int j = 0; j < N; j++) {
                    q[i][j] -= d * q[k][j];
                }
            }
            double n = 0;
            for (int j = 0; j < N; j++) {
                n += q[i][j] * q[i][j];
            }
            for (int j = 0; j < N; j++) {
                q[i][j] /= Math.sqrt(n);
            }
        }
        return q;
    }

    @Test
    public void theSphere() {
        double[] opt = optimum(1);
        F f = x -> -Math.pow(distance(x, opt), 2);
        double[] found = maximise(new Cma(N, 10, 1, new double[N], 1, 0), f, 20_000, 1e-9);
        assertTrue(distance(found, opt) < 1e-6, "off by " + distance(found, opt));
    }

    @Test
    public void aRotatedEllipsoid() {
        double[] opt = optimum(2);
        double[][] q = rotation(3);
        F f = x -> {
            double s = 0;
            for (int i = 0; i < N; i++) {
                double y = 0;
                for (int j = 0; j < N; j++) {
                    y += q[i][j] * (x[j] - opt[j]);
                }
                s += Math.pow(1e6, i / (N - 1.0)) * y * y;
            }
            return -s;
        };
        Cma cma = new Cma(N, 10, 2, new double[N], 1, 0);
        double[] found = maximise(cma, f, 40_000, 1e-10);
        assertTrue(distance(found, opt) < 1e-5, "off by " + distance(found, opt));
        assertTrue(cma.condition() > 100, "it learned the ellipsoid's shape: " + cma.condition());
    }

    @Test
    public void rosenbrock() {
        F f = x -> {
            double s = 0;
            for (int i = 0; i < N - 1; i++) {
                s += 100 * Math.pow(x[i + 1] - x[i] * x[i], 2) + Math.pow(1 - x[i], 2);
            }
            return -s;
        };
        double[] ones = new double[N];
        java.util.Arrays.fill(ones, 1);
        double[] found = maximise(new Cma(N, 10, 4, new double[N], 0.5, 0), f, 60_000, 1e-11);
        assertTrue(distance(found, ones) < 1e-5, "off by " + distance(found, ones));
    }

    /**
     * Scale-invariant: the cosine between a hidden direction and a candidate as the count search
     * scores it (normalised, rounded at K, its sign fixed), with noise added. On the sphere it
     * finds the direction to within a degree, and two searches of the same seed go the same way.
     */
    @Test
    public void aHiddenDirectionThroughTheCountsNormalisation() {
        double[] hidden = CountKeys.normalised(new double[]{0, -0.9, 0.4, 0.6, 0.8, 1.1, 0.7, 0.3, -0.1, -0.4, -0.85});
        double[] sign = hidden.clone();
        Random noise = new Random(5);
        double[][] runs = new double[2][];
        for (int run = 0; run < 2; run++) {
            Random rnd = new Random(6);
            F f = x -> {
                int[] t = CountSearch.whole(x, sign);
                double[] d = new double[11];
                for (int r = 1; r <= 10; r++) {
                    d[r] = t[r];
                }
                return CountSearch.cosine(d, hidden) + 1e-5 * rnd.nextGaussian();
            };
            double[] start = CountSearch.onSphere(CountSearch.coordinates(
                    new double[]{0, -1, 1, 1, 1, 1, 1, 0, 0, 0, -1}));
            Cma cma = new Cma(9, 12, 7, start, 0.3 * CountSearch.RADIUS, CountSearch.RADIUS);
            runs[run] = maximise(cma, f, 3_000, Math.toRadians(0.05));
            double r = 0;
            for (double v : runs[run]) {
                r += v * v;
            }
            assertEquals(CountSearch.RADIUS, Math.sqrt(r), 1e-12, "the mean stays on the sphere");
            double cos = CountSearch.cosine(CountSearch.tags(runs[run]), hidden);
            assertTrue(cos > Math.cos(Math.toRadians(1)), "the direction found is " + Math.toDegrees(Math.acos(cos))
                    + " degrees off");
        }
        assertArrayEquals(runs[0], runs[1], 0, "the same seed goes the same way");
        noise.nextInt();
    }

    /**
     * A slice through a point five degrees from a hidden direction, toward it, peaks five degrees
     * along; its quadratic fit finds the peak and the headroom left; and a slice through the
     * hidden direction itself peaks at it, with nothing left.
     */
    @Test
    public void slicesPeakWhereTheObjectiveDoes() {
        double[] hidden = CountSearch.coordinates(new double[]{0, -0.9, 0.4, 0.6, 0.8, 1.1, 0.7, 0.3, -0.1, -0.4, -0.85});
        double[] h = CountSearch.onSphere(hidden);
        CountSearch.Objective f = x -> {
            double xy = 0;
            double xx = 0;
            for (int k = 0; k < 9; k++) {
                xy += x[k] * h[k];
                xx += x[k] * x[k];
            }
            return xy / Math.sqrt(xx) / CountSearch.RADIUS;
        };
        double[] other = CountSearch.onSphere(CountSearch.coordinates(new double[]{0, 0.2, 0.9, -0.3, 0.6, 0.1, 0.7, -0.5,
                0.4, 0.3, -0.6}));
        // The unit vector in the plane of the hidden direction and another, perpendicular to the first.
        double hd = 0;
        for (int k = 0; k < 9; k++) {
            hd += h[k] * other[k];
        }
        double[] d = new double[9];
        double dd = 0;
        for (int k = 0; k < 9; k++) {
            d[k] = other[k] - hd / (CountSearch.RADIUS * CountSearch.RADIUS) * h[k];
            dd += d[k] * d[k];
        }
        double a = Math.toRadians(5);
        double[] centre = new double[9];
        for (int k = 0; k < 9; k++) {
            centre[k] = Math.cos(a) * h[k] - Math.sin(a) * d[k] * CountSearch.RADIUS / Math.sqrt(dd);
        }
        double[] q = CountSearch.quadratic(CountSearch.slice(f, centre, d, Math.toRadians(4), 9));
        assertEquals(5, Math.toDegrees(q[3]), 0.05, "the peak five degrees along");
        assertEquals(1 - Math.cos(a), q[4], 1e-4, "the headroom is what the peak gains");
        double[] at = CountSearch.quadratic(CountSearch.slice(f, h, d, Math.toRadians(4), 9));
        assertEquals(0, Math.toDegrees(at[3]), 1e-6);
        assertEquals(0, at[4], 1e-12);
        assertEquals(0, CountSearch.slice(f, h, h, 0.1, 5).length, "no slice along the centre");
    }

    /**
     * A start given off the sphere, at any scale, is put on it with the step taken as given, so a
     * run starts at the angle asked for (F2's joint polish starts from whole-number tags at K); and
     * on the sphere of the first coordinates alone (F1r's balanced part), the mean is scaled whole,
     * the rest with them, every generation, the step keeping its angle.
     */
    @Test
    public void aStartIsPutOnTheSphereWithTheStepAsGiven() {
        double[] start = CountSearch.onSphere(CountSearch.coordinates(new double[]{0, -1, 1, 1, 1, 1, 1, 0, 0, 0, -1}));
        double r = CountSearch.RADIUS;
        for (double scale : new double[]{1, 1e4, 1e-3}) {
            double[] x = start.clone();
            for (int i = 0; i < x.length; i++) {
                x[i] *= scale;
            }
            Cma cma = new Cma(9, 12, 3, x, 0.3 * r, r);
            assertEquals(0.3, cma.step(), 1e-15, "at scale " + scale);
            assertArrayEquals(start, cma.state.mean, 1e-12);
        }
        double[] ten = java.util.Arrays.copyOf(start, 10);
        ten[9] = 0.4;
        for (int i = 0; i < 10; i++) {
            ten[i] *= 7;
        }
        Cma cma = new Cma(10, 12, 4, ten, 0.2 * r, r, 9);
        assertEquals(0.2, cma.step(), 1e-15);
        assertEquals(0.4, cma.state.mean[9], 1e-12, "the shift scaled with the balanced part");
        Random rnd = new Random(5);
        for (int g = 0; g < 20; g++) {
            double[][] xs = cma.ask();
            double[] v = new double[xs.length];
            for (int k = 0; k < xs.length; k++) {
                v[k] = rnd.nextGaussian();
            }
            double before = cma.state.sigma;
            double[] mean = cma.state.mean.clone();
            cma.tell(xs, v);
            double n = 0;
            for (int i = 0; i < 9; i++) {
                n += cma.state.mean[i] * cma.state.mean[i];
            }
            assertEquals(r, Math.sqrt(n), 1e-12, "generation " + g + ": the balanced part on the sphere");
            assertNotEquals(mean[9], cma.state.mean[9]);
            assertTrue(cma.state.sigma != before);
        }
        assertThrows(IllegalArgumentException.class, () -> new Cma(10, 12, 4, ten, 1, r, 11));
    }

    /** A search stopped after any generation and resumed from its logged state ends where one never stopped ends. */
    @Test
    public void aResumedSearchGoesOnAsIfNeverStopped() throws Exception {
        double[] opt = optimum(8);
        F f = x -> -Math.pow(distance(x, opt), 2) - 0.1 * Math.abs(x[0]);
        Cma whole = new Cma(N, 10, 9, new double[N], 1, 0);
        double[] a = maximise(whole, f, 3_000, 0).clone();
        Path dir = Files.createTempDirectory("cma");
        Path file = dir.resolve("log.tsv");
        Cma first = new Cma(N, 10, 9, new double[N], 1, 0);
        try (CountSearch.SearchLog log = new CountSearch.SearchLog(file, "# test", false)) {
            maximise(first, f, 1_230, 0);
            log.state("r", first.state);
        }
        // Lines of a generation not finished, as an interruption leaves them, are cut on resuming.
        Files.write(file, "eval\tr\t123\t0\t1.0\t0,0\n".getBytes(), java.nio.file.StandardOpenOption.APPEND);
        Cma.State saved;
        try (CountSearch.SearchLog log = new CountSearch.SearchLog(file, "# test", true)) {
            saved = CountSearch.SearchLog.lastState(file, "r");
        }
        List<String> lines = Files.readAllLines(file);
        assertTrue(lines.get(lines.size() - 1).startsWith("state\t"), "cut back to the last state");
        Cma resumed = new Cma(N, 10, 9, new double[N], 1, 0);
        resumed.restore(saved);
        double[] b = maximise(resumed, f, 3_000, 0);
        assertArrayEquals(a, b, 0);
        assertEquals(whole.state.sigma, resumed.state.sigma, 0);
        assertThrows(IllegalStateException.class, () -> new CountSearch.SearchLog(file, "# test", false));
        assertThrows(IllegalStateException.class, () -> new CountSearch.SearchLog(file, "# another", true));
    }
}
