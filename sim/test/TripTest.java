package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Trip risk and the chance of being behind (INTEGRATION.md open item 11): the blocks worked by hand
 * on six shoes, the normal distribution against published values, and the formulas at their
 * limits (PLAN_COMPUTE.md 2.4 check 6: zero drift gives 2Φ(−B/σ√T), T → ∞ gives exp(−2μB/σ²)).
 */
class TripTest {

    /**
     * Six shoes (Y, N, M). With T = 4 rounds the blocks are shoes 0 to 2 (2 + 1 + 3 = 6 rounds) and
     * shoes 3 and 4 (4 rounds); shoe 5 (1 round) is an incomplete last block, dropped.
     * Block 0: total 1 − 3 + 2 = 0; lowest min(0 + 0, 1 − 30, −2 − 1) = −29; not behind.
     * Block 1: total −1 + 0 = −1; lowest min(0 − 9, −1 − 1) = −9; behind.
     */
    static Ruin records() {
        return new Ruin(new double[]{1, -3, 2, -1, 0, 5}, new long[]{2, 1, 3, 2, 2, 1},
                new double[]{0, -30, -1, -9, -1, 0});
    }

    @Test
    void theBlocksAreWholeShoesFromShoeZeroAndTheLastIncompleteOneIsDropped() {
        List<Trip.Block> blocks = new Trip(records()).blocks(4);
        assertEquals(List.of(new Trip.Block(6, 0, -29), new Trip.Block(4, -1, -9)), blocks);
    }

    @Test
    void tripAndBehindCountTheBlocks() {
        Ruin r = records();
        // μ = 4/11; σ_eff² = 6/5 · Σ(Y − μN)² / ΣN = 6/5 · (4504/121) / 11.
        assertEquals(4.0 / 11, r.mu, 1e-15);
        assertEquals(Math.sqrt(1.2 * 4504.0 / 121 / 11), r.sigmaEff, 1e-12);
        double unit = r.sigmaEff * r.sigmaEff / (2 * r.mu);
        List<double[]> lines = new Trip(r).run(new int[]{2}, new int[]{2});
        assertEquals(4, lines.size(), "one behind line and a trip line for each of B_25%, B_5% and B_1%");
        double[] behind = lines.get(0);
        assertEquals(List.of(2.0, 2.0, 4.0, 2.0, 5.0, -1.0), List.of(behind[0], behind[1], behind[2], behind[3], behind[4], behind[5]));
        assertEquals(0.5, behind[7], 0);
        assertEquals(Math.sqrt(0.5 * 0.5 / 2), behind[8], 1e-15, "the binomial SE over 2 blocks");
        assertEquals(Trip.phi(-r.mu * 2 / r.sigmaEff), behind[9], 0);
        // B_25% ≈ 7.74 loses both blocks (lowest −29 and −9); B_5% ≈ 16.7 and B_1% ≈ 25.7 only block 0.
        double[] expected = {1.0, 0.5, 0.5};
        for (int k = 0; k < 3; k++) {
            double[] t = lines.get(1 + k);
            assertEquals(Ruin.RISKS[k], t[5], 0);
            assertEquals(unit * Math.log(1 / Ruin.RISKS[k]), t[6], 1e-12);
            assertEquals(expected[k], t[7], 0, "trip risk from B_" + Ruin.RISKS[k]);
            assertEquals(Trip.tripRisk(r.mu, r.sigmaEff, t[6], 4), t[9], 0);
        }
        List<String> text = new Trip(r).lines("F27", lines);
        assertEquals(4 + 4, text.size());
        assertEquals("kind\thours\tpace\trounds\tblocks\tmeanRounds\trisk\tbankroll\tsimulated\tse\tformula", text.get(3));
        assertTrue(text.get(4).startsWith("behind\t2\t2\t4\t2\t5.0\t-\t-\t0.5\t"), text.get(4));
        assertTrue(text.get(5).startsWith("trip\t2\t2\t4\t2\t5.0\t0.25\t"), text.get(5));
    }

    @Test
    void aLosingRecordHasBehindLinesOnly() {
        Ruin losing = new Ruin(new double[]{-1, -1, 1}, new long[]{1, 1, 1}, new double[]{-1, -1, 0});
        List<double[]> lines = new Trip(losing).run(new int[]{1}, new int[]{1});
        assertEquals(1, lines.size());
        assertEquals(2.0 / 3, lines.get(0)[7], 1e-15);
    }

    @Test
    void theNormalDistributionMatchesPublishedValues() {
        assertEquals(0.5, Trip.phi(0), 0);
        assertEquals(0.8413447460685429, Trip.phi(1), 1e-15);
        assertEquals(0.024997895148220435, Trip.phi(-1.96), 1e-15);
        assertEquals(0.9986501019683699, Trip.phi(3), 1e-15);
        assertEquals(2.326290790355250e-4, Trip.phi(-3.5), 1e-17);
        assertEquals(2.866515718791939e-7, Trip.phi(-5), 1e-20);
        assertEquals(7.619853024160527e-24, Trip.phi(-10), 1e-37);
        for (double x = -6; x < 6; x += 0.37) {
            assertEquals(1, Trip.phi(x) + Trip.phi(-x), 1e-15, "symmetry at " + x);
        }
        for (double x = 2.9; x < 3.1; x += 0.01) {
            assertEquals(Trip.phi(x - 1e-9), Trip.phi(x), 1e-9, "the two methods meet at 3");
        }
    }

    @Test
    void theFormulasAtTheirLimits() {
        double sigma = 1.1;
        double b = 50;
        for (double t : new double[]{100, 2500, 40000}) {
            assertEquals(2 * Trip.phi(-b / (sigma * Math.sqrt(t))), Trip.tripRisk(0, sigma, b, t), 1e-15);
            assertEquals(2 * Trip.phi(-b / (sigma * Math.sqrt(t))), Trip.tripRisk(1e-13, sigma, b, t), 1e-9);
            assertEquals(0.5, Trip.behind(0, sigma, t), 0);
        }
        assertEquals(Math.exp(-2 * 0.01 * b / (sigma * sigma)), Trip.tripRisk(0.01, sigma, b, 1e12), 1e-15);
        assertTrue(Trip.tripRisk(0.01, sigma, b, 1000) < Trip.tripRisk(0.01, sigma, b, 100000));
        // Behind after T = 10,000 rounds at μ = 0.01, σ = 1.1: Φ(−0.01 · 100 / 1.1).
        assertEquals(Trip.phi(-1 / 1.1), Trip.behind(0.01, sigma, 10000), 0);
        assertEquals(0.18165107044, Trip.behind(0.01, sigma, 10000), 1e-10);
    }
}
