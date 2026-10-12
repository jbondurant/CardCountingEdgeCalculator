package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Moments' ratio and paired difference against the formulas computed directly from the shoes, and
 * the registry block read from SIM_CHECK.md (run from the repository's root).
 */
class MomentsTest {

    @Test
    void theRatioAndTheDifferenceAreTheDirectFormulas() {
        SplittableRandom r = new SplittableRandom(3);
        int shoes = 500;
        double[][] v = new double[shoes][];
        double[] sums = new double[Moments.size(4)];
        for (int h = 0; h < shoes; h++) {
            double n1 = 40 + r.nextInt(30);
            double n2 = 40 + r.nextInt(30);
            // two correlated results a shoe, so the paired difference's SE is well under the two SEs combined
            double common = r.nextGaussian() * Math.sqrt(n1);
            v[h] = new double[]{common - 0.01 * n1, n1, common + 0.3 * r.nextGaussian() * Math.sqrt(n2) + 0.02 * n2, n2};
            Moments.add(sums, 0, v[h]);
        }
        double[] e1 = Moments.ratio(sums, 0, new double[]{1, 0, 0, 0}, new double[]{0, 1, 0, 0});
        double sy = 0, sn = 0;
        for (double[] x : v) {
            sy += x[0];
            sn += x[1];
        }
        double mu = sy / sn;
        double ss = 0;
        for (double[] x : v) {
            ss += (x[0] - mu * x[1]) * (x[0] - mu * x[1]);
        }
        assertEquals(mu, e1[0], 1e-15);
        assertEquals(Math.sqrt(shoes / (shoes - 1.0) * ss) / sn, e1[1], 1e-12 * e1[1]);
        assertEquals(sn, e1[2]);
        assertEquals(shoes, e1[3]);
        double[] d = Moments.difference(sums, 0, new double[]{0, 0, 1, 0}, new double[]{0, 0, 0, 1},
                new double[]{1, 0, 0, 0}, new double[]{0, 1, 0, 0});
        double sy2 = 0, sn2 = 0;
        for (double[] x : v) {
            sy2 += x[2];
            sn2 += x[3];
        }
        double mu2 = sy2 / sn2;
        double sd = 0;
        for (double[] x : v) {
            double e = (x[2] - mu2 * x[3]) / sn2 - (x[0] - mu * x[1]) / sn;
            sd += e * e;
        }
        assertEquals(mu2 - mu, d[0], 1e-15);
        assertEquals(Math.sqrt(shoes / (shoes - 1.0) * sd), d[1], 1e-12 * d[1]);
        assertEquals(d[1] * Math.sqrt(sn2), d[2], 1e-15);
        double se2 = Moments.ratio(sums, 0, new double[]{0, 0, 1, 0}, new double[]{0, 0, 0, 1})[1];
        assertTrue(d[1] < 0.5 * Math.hypot(e1[1], se2), "pairing removes the shared part");
    }

    @Test
    void theRegistryIsSimChecksBlock() throws IOException {
        Registry r = Registry.read(Path.of("SIM_CHECK.md"));
        assertEquals(52, r.figures.size());
        Registry.Fig f03 = r.get("F03");
        assertEquals(Row.CHART, f03.row());
        assertEquals(80, f03.pen());
        assertEquals("flat", f03.figure());
        assertEquals(List.of("P3", "P2"), f03.tests());
        assertTrue(f03.swings());
        Registry.Fig f45 = r.get("F45");
        assertEquals("ev:X-1.0,8", f45.figure());
        assertEquals("Hi-Lo = Hi-Lo Lite, full play", f45.row());
        List<String> paths = new ArrayList<>();
        List<String> swings = new ArrayList<>();
        for (Registry.Fig g : r.figures) {
            if (g.paths()) {
                paths.add(g.id());
            }
            if (g.swings()) {
                swings.add(g.id());
            }
        }
        assertEquals(List.of("F27", "F29"), paths);
        // SIM_CHECK.md section 3's totals: swings for 25 figures
        assertEquals(25, swings.size());
        assertEquals(r.get("F27"), r.of("Hi-Lo = Hi-Lo Lite, full play", 80, "ev:same-8"));
    }
}
