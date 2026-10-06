import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The dry run's projection fits each figure's growth as a x n^b; it must find a and b where they are exact. */
public class FullIndexDryRunTest {

    @Test
    public void aPowerLawIsFittedOnItsLogarithms() {
        double[] x = {293, 2071, 6130};
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            y[i] = 3.5 * Math.pow(x[i], 0.44);
        }
        double[] ab = FullIndexDryRun.Growth.powerFit(x, y);
        assertEquals(3.5, ab[0], 1e-9);
        assertEquals(0.44, ab[1], 1e-12);
        // Noise off the line: the fit goes between, its exponent between the pairs' slopes.
        y[1] *= 1.2;
        ab = FullIndexDryRun.Growth.powerFit(x, y);
        double low = Math.log(y[2] / y[1]) / Math.log(x[2] / x[1]);
        double high = Math.log(y[1] / y[0]) / Math.log(x[1] / x[0]);
        assertTrue(ab[1] > low && ab[1] < high, ab[1] + " between " + low + " and " + high);
        // One run: no growth.
        assertEquals(0, FullIndexDryRun.Growth.powerFit(new double[]{100}, new double[]{7})[1], 0);
        assertEquals(7, FullIndexDryRun.Growth.powerFit(new double[]{100}, new double[]{7})[0], 1e-12);
    }
}
