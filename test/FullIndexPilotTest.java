import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pilot's pairing (FullIndexPilot), on FullIndexFitTest's hand-made states. The SE ratio it
 * measures means something only if, on each resample, the three ways a count is scored and the
 * refit of its later plays all see one set of weights: otherwise the refitted and held SEs would
 * differ by the resamples and not by the refit. So every variant's weights are checked against
 * the bootstrap's own multiplicities, the held variant against the full run's bootstrap
 * (SystemScorer.score), and the refit against the estimate on a resample that draws every shoe
 * once, where it must give the estimate's values to the bit.
 */
public class FullIndexPilotTest {

    private static final int PEN = FullIndexPilot.PEN;
    private static final int RESAMPLES = 3;
    private static final int THREADS = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 1));

    @Test
    public void everyWayOfScoringAResampleSeesTheSameWeights() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        List<CountKeys.Count> counts = Arrays.asList(FullIndexFitTest.HI_LO, FullIndexFitTest.RED_SEVEN);
        FullIndexPilot pilot = new FullIndexPilot(fx.scorer, counts, fx.trees, true, THREADS, RESAMPLES);
        pilot.refitOn(fx.trees);
        Map<String, double[][]> seen = new HashMap<>();
        List<String> order = new ArrayList<>();
        pilot.witness = (variant, r, c, w, u) -> {
            String key = r + " " + c.name + " " + variant;
            assertNull(seen.put(key, new double[][]{w.clone(), u.clone()}), key + " seen twice");
            order.add(key);
        };
        for (int r = 0; r < RESAMPLES; r++) {
            pilot.resample(r);
        }
        assertEquals(RESAMPLES * counts.size() * 5, order.size());

        SystemScorer.Pen pen = fx.scorer.pen(PEN);
        int n = pen.states.length;
        boolean moved = false;
        for (int r = 0; r < RESAMPLES; r++) {
            double[] w = new double[n];
            double[] u = new double[n];
            boolean someLeftOut = false;
            for (int j = 0; j < n; j++) {
                u[j] = fx.scorer.boot.mult(r, fx.st.shoe[pen.states[j]]);
                w[j] = pen.weight[j] * u[j];
                someLeftOut |= u[j] == 0;
            }
            assertTrue(someLeftOut, "resample " + r + " leaves some shoe out, so the weights are the resample's");
            for (CountKeys.Count c : counts) {
                for (int variant = -2; variant <= 2; variant++) {
                    double[][] got = seen.get(r + " " + c.name + " " + variant);
                    assertNotNull(got, "resample " + r + " " + c + " variant " + variant);
                    assertArrayEquals(w, got[0], 0.0, "resample " + r + " " + c + " variant " + variant);
                    assertArrayEquals(u, got[1], 0.0, "resample " + r + " " + c + " variant " + variant);
                }
            }
            for (int c = 0; c < counts.size(); c++) {
                for (int k = 0; k < FullIndexPilot.METRICS.length; k++) {
                    moved |= pilot.boot[FullIndexPilot.REFIT][c][k][r] != pilot.boot[FullIndexPilot.HELD][c][k][r];
                }
            }
        }
        assertTrue(moved, "on some resample the refitted later plays score otherwise than the held ones");

        // The held variant and first decisions only are what the full run's bootstrap gives.
        for (int c = 0; c < counts.size(); c++) {
            SystemScorer.Result res = fx.scorer.score(counts.get(c), null, pilot.estimate.get(c));
            for (int k = 0; k < FullIndexPilot.METRICS.length; k++) {
                int m = FullIndexPilot.METRICS[k];
                assertEquals(res.fullEntries.get(0).estimate[PEN][m], pilot.point[FullIndexPilot.HELD][c][k], 1e-12);
                assertEquals(res.entries.get(0).estimate[PEN][m], pilot.point[FullIndexPilot.FIRST][c][k], 1e-12);
                for (int r = 0; r < RESAMPLES; r++) {
                    assertEquals(res.fullEntries.get(0).boot[PEN][m][r], pilot.boot[FullIndexPilot.HELD][c][k][r], 1e-12,
                            counts.get(c) + " resample " + r);
                    assertEquals(res.entries.get(0).boot[PEN][m][r], pilot.boot[FullIndexPilot.FIRST][c][k][r], 1e-12,
                            counts.get(c) + " resample " + r);
                }
            }
        }

        // A resample drawing every shoe once refits the estimate's later plays exactly.
        pilot.witness = null;
        double[] w = pen.weight.clone();
        double[] ones = new double[n];
        Arrays.fill(ones, 1);
        FullIndexFit.Strategy[][] by = pilot.refit(-1, w, ones);
        for (int c = 0; c < counts.size(); c++) {
            for (int f = 0; f < 2; f++) {
                assertArrayEquals(pilot.held[c][f].hit, by[c][f].values.hit, 0.0, counts.get(c) + " fold " + f);
                assertArrayEquals(pilot.held[c][f].split, by[c][f].values.split, 0.0, counts.get(c) + " fold " + f);
            }
            double[] all = fx.scorer.scoreWith(counts.get(c), PEN,
                    new SystemScorer.PlayValues[]{by[c][0].values, by[c][1].values}, w, ones);
            for (int k = 0; k < FullIndexPilot.METRICS.length; k++) {
                assertEquals(pilot.point[FullIndexPilot.HELD][c][k], all[FullIndexPilot.METRICS[k]], 0.0);
            }
        }
    }

    /**
     * With --wide the pilot measures, at P16, S16, B0,8 and X-1.0,8, what the full run's bootstrap
     * scores (PLAN_COMPUTE.md 3.4): its first-decision and held figures are the wide figures
     * SystemScorer.score gives, estimate and resamples, within 1e-12; its P8 passed the check
     * against the 1-8 same schedule on every scoring; and the ratios it prints are the ones
     * readRatios reads back, for the wide plans as for the 1-8 schedule and flat.
     */
    @Test
    public void withTheWidePlansThePilotMeasuresWhatTheFullRunScores() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        SystemScorer sc = new SystemScorer(fx.st, new Bootstrap(fx.st.shoes, RESAMPLES, 1), 1);
        sc.wide = FullIndexPilot.widePlans();
        sc.roundStarts = WidePlansTest.valuedOnly(fx.st);
        List<CountKeys.Count> counts = Arrays.asList(FullIndexFitTest.HI_LO, FullIndexFitTest.RED_SEVEN);
        FullIndexPilot pilot = new FullIndexPilot(sc, counts, fx.trees, true, THREADS, RESAMPLES);
        pilot.refitOn(fx.trees);
        for (int r = 0; r < RESAMPLES; r++) {
            pilot.resample(r);
        }
        assertEquals(FullIndexPilot.METRICS.length + FullIndexPilot.WIDE_MEASURED.length, pilot.metrics.length);
        for (int c = 0; c < counts.size(); c++) {
            SystemScorer.Result res = sc.score(counts.get(c), null, pilot.estimate.get(c));
            for (int k = 0; k < FullIndexPilot.WIDE_MEASURED.length; k++) {
                String plan = FullIndexPilot.WIDE_MEASURED[k][0];
                int m = FullIndexPilot.WIDE_MEASURED[k][1].equals("same") ? SystemScorer.W_SAME : SystemScorer.W_OWN;
                int slot = FullIndexPilot.METRICS.length + k;
                SystemScorer.Entry full = res.fullEntries.get(0);
                SystemScorer.Entry first = res.entries.get(0);
                assertEquals(full.wide(PEN, plan, m), pilot.point[FullIndexPilot.HELD][c][slot], 1e-12, plan);
                assertEquals(first.wide(PEN, plan, m), pilot.point[FullIndexPilot.FIRST][c][slot], 1e-12, plan);
                for (int r = 0; r < RESAMPLES; r++) {
                    assertEquals(full.wideBoot(PEN, plan, m)[r], pilot.boot[FullIndexPilot.HELD][c][slot][r], 1e-12,
                            plan + " resample " + r);
                    assertEquals(first.wideBoot(PEN, plan, m)[r], pilot.boot[FullIndexPilot.FIRST][c][slot][r], 1e-12,
                            plan + " resample " + r);
                }
            }
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.PrintStream out = new java.io.PrintStream(bytes, true, "UTF-8");
        pilot.printSes(out);
        out.println("## Results");
        pilot.printSes(out);
        Map<String, Map<String, double[]>> read = FullIndexPilot.readRatios(Arrays.asList(bytes.toString("UTF-8").split("\n")));
        assertEquals(Arrays.asList(pilot.metricNames), new ArrayList<>(read.keySet()));
        for (int k = 0; k < pilot.metrics.length; k++) {
            for (int c = 0; c < counts.size(); c++) {
                double[] got = read.get(pilot.metricNames[k]).get(counts.get(c).name);
                double gain = FullIndexPilot.ratio(pilot.differences(FullIndexPilot.REFIT, c, k, true),
                        pilot.differences(FullIndexPilot.HELD, c, k, true))[0];
                assertEquals(gain, got[2], 0.005, pilot.metricNames[k] + " " + counts.get(c));
                if (c == 0) {
                    assertTrue(Double.isNaN(got[0]), "no difference of Hi-Lo from itself");
                } else {
                    double diff = FullIndexPilot.ratio(pilot.differences(FullIndexPilot.REFIT, c, k, false),
                            pilot.differences(FullIndexPilot.HELD, c, k, false))[0];
                    assertEquals(diff, got[0], 0.005, pilot.metricNames[k] + " " + counts.get(c));
                }
            }
        }
    }
}
