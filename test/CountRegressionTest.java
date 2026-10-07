import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The theory pass's anchors (COUNT_DESIGN.md, section 6): the insurance regression gives the Ten
 * Count with 1 - R squared below 1e-12; a betting-correlation objective searched by the same
 * optimiser matches the betting regression's direction, and the report's BC code gives R for it;
 * on hand-made states with values planted linear the regression recovers them to 1e-9. And the
 * effects of removal and the later decisions' shares hold to what they must be.
 */
public class CountRegressionTest {

    /** Least squares recovers a planted linear function of the shares to 1e-9, R squared 1. */
    @Test
    public void leastSquaresRecoversPlantedCoefficients() {
        Random rnd = new Random(1);
        double[] beta = new double[10];
        for (int k = 0; k < 10; k++) {
            beta[k] = rnd.nextGaussian();
        }
        CountRegression.Wls fit = new CountRegression.Wls(9);
        List<double[]> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Double> ws = new ArrayList<>();
        for (int n = 0; n < 500; n++) {
            int[] left = ShoeRun.ranksLeft(CountKeysTest.dealt(rnd, 50 + rnd.nextInt(280)));
            double[] x = CountRegression.shares(left);
            double y = beta[0];
            for (int k = 0; k < 9; k++) {
                y += beta[k + 1] * x[k];
            }
            double w = 0.5 + rnd.nextDouble();
            fit.add(x, y, w);
            xs.add(x);
            ys.add(y);
            ws.add(w);
        }
        double[] b = fit.solve();
        for (int k = 0; k < 10; k++) {
            assertEquals(beta[k], b[k], 1e-9);
        }
        for (int n = 0; n < xs.size(); n++) {
            fit.residual(xs.get(n), ys.get(n), ws.get(n));
        }
        assertTrue(fit.oneLessR2() < 1e-20, "1 - R squared " + fit.oneLessR2());
    }

    /**
     * On hand-made states whose every move's value is linear in the shares after the deal, each
     * first decision's regression recovers the difference from the chart's move to 1e-9, with R
     * squared 1, and a move's gain is what it gains where it is best.
     */
    @Test
    public void firstDecisionsRecoverPlantedValues() {
        Random rnd = new Random(2);
        int moves = ScoringStates.MOVES;
        double[][] gamma = new double[moves][11];
        for (int m = 0; m < moves; m++) {
            for (int r = 1; r <= 9; r++) {
                gamma[m][r] = 0.2 * rnd.nextGaussian();
            }
        }
        List<StateStore.Record> records = new ArrayList<>();
        int shoes = 60;
        for (int shoe = 0; shoe < shoes; shoe++) {
            int[] dealt = CountKeysTest.dealt(rnd, 60 + rnd.nextInt(250));
            int[] left = ShoeRun.ranksLeft(dealt);
            records.add(SystemScorerTest.state(shoe, 0, dealt, 0.5 + 0.5 * rnd.nextDouble(), (i, m) -> {
                double[] x = CountRegression.shares(CountRegression.afterDeal(left, i));
                double v = 0;
                for (int r = 1; r <= 9; r++) {
                    v += gamma[m][r] * x[r - 1];
                }
                return v;
            }));
        }
        ScoringStates st = SystemScorerTest.prepare(records, shoes);
        List<CountRegression.Index> idx = CountRegression.firstDecisions(st);
        assertFalse(idx.isEmpty());
        for (CountRegression.Index x : idx) {
            int c = st.chartMove[x.deal];
            assertEquals(1, x.r2, 1e-9);
            for (int r = 1; r <= 9; r++) {
                assertEquals(gamma[x.move][r] - gamma[c][r], x.beta[r], 1e-9, "deal " + x.deal + " move " + x.move);
            }
            assertTrue(x.gain >= 0);
        }
    }

    /** The insurance regression gives the Ten Count, with 1 - R squared below 1e-12. */
    @Test
    public void insuranceGivesTheTenCount() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        double[] oneLess = new double[1];
        double[] t = CountRegression.insurance(fx.st, oneLess);
        assertTrue(oneLess[0] < 1e-12, "1 - R squared " + oneLess[0]);
        double[] ten = CountSearch.rankTags(CountSearch.catalogCount("Ten Count (unbalanced; insurance-oriented)"));
        assertEquals(1, CountSearch.cosine(t, ten), 1e-12);
        // A true count of the Ten Count's tags rises with the chance of a ten: insuring pays where it is high.
        assertTrue(t[10] < 0);
    }

    /**
     * On the planted states of CountSearchTest, the betting-correlation objective searched by
     * CMA-ES on the sphere ends at the betting regression's direction (cosine 0.999 or more), and
     * the report's BC code (AllStates.bettingCorrelation) gives R for it.
     */
    @Test
    public void theBettingCorrelationPeaksAtTheRegressionAndEqualsR() {
        double[] bet = {0, -1, 0.6, 0.8, 1, 1.2, 0.9, 0.3, 0, -0.3, -0.9};
        double[] play = {0, 0.8, 1.0, 0.9, 0.4, -0.2, -0.6, -0.7, -0.3, 0.2, -0.2};
        ScoringStates st = CountSearchTest.planted(bet, play, 3);
        CountRegression.Betting b = CountRegression.betting(st);
        assertTrue(b.r > 0.5 && b.r < 1, "R " + b.r);
        CountKeys.Count regression = CountKeys.real("regression", b.tags, null, 1);
        assertEquals(b.r, AllStates.bettingCorrelation(regression, st), 1e-6);
        assertTrue(b.depth >= -1e-12);

        double[] sign = b.tags;
        double[] start = CountSearch.onSphere(CountSearch.coordinates(new double[]{0, -1, 1, 1, 1, 1, 1, 0, 0, 0, -1}));
        Cma cma = new Cma(9, 12, 5, start, 0.3 * CountSearch.RADIUS, CountSearch.RADIUS);
        while (cma.state.evaluations < 2_400 && cma.step() > 1e-4) {
            double[][] xs = cma.ask();
            double[] v = new double[xs.length];
            for (int k = 0; k < xs.length; k++) {
                v[k] = AllStates.bettingCorrelation(CountKeys.whole("x", CountSearch.whole(xs[k], sign), null, 1, true), st);
            }
            cma.tell(xs, v);
        }
        double cos = CountSearch.cosine(CountSearch.tags(cma.state.mean), b.tags);
        assertTrue(cos >= 0.999, "the searched direction is at cosine " + cos);
        double found = AllStates.bettingCorrelation(CountKeys.whole("x", CountSearch.whole(cma.state.mean, sign), null, 1,
                true), st);
        assertTrue(found <= b.r + 1e-6, "no linear count's BC exceeds R: " + found + " against " + b.r);
    }

    /**
     * At every node of a later decision, the ten indicator counts give the shares of the cards
     * not seen: they add up to one, and match the state's own shares before the round to within
     * the cards a hand can hold. Every contested spot reached gets a direction and a worth.
     */
    @Test
    public void laterDecisionsReadTheSharesAtEachNode() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        ScoringStates st = fx.st;
        CountKeys.Count[] indicator = new CountKeys.Count[11];
        for (int r = 1; r <= 10; r++) {
            int[] t = new int[11];
            t[r] = CountRegression.INDICATOR;
            indicator[r] = CountKeys.whole("rank " + r, t, null, 1, true);
        }
        ExactRound engine = new ExactRound(StateRun.montreal());
        for (int s = 0; s < 3; s++) {
            PlayerTree tree = fx.trees.of(s, engine);
            List<int[]> keys = new ArrayList<>();
            for (int r = 1; r <= 10; r++) {
                int rr = r;
                int[] at = {0};
                PlayerTree.Walk walk = tree.count(indicator[r], st.dealt[s]).walk(PlayerTree.CHART,
                        PlayerTree.Mode.CONTESTED);
                walk.reach((deal, part) -> st.chartMove[deal] == SystemScorer.HIT, 1);
                walk.decisions((spot, key, reach, gap) -> {
                            if (rr == 1) {
                                keys.add(new int[11]);
                            }
                            keys.get(at[0]++)[rr] = key;
                        });
            }
            assertFalse(keys.isEmpty());
            double[] before = CountRegression.shares(ShoeRun.ranksLeft(st.dealt[s]));
            int left = ShoeRun.CARDS - st.depth[s];
            for (int[] k : keys) {
                double sum = 0;
                for (int r = 1; r <= 10; r++) {
                    double share = CountKeys.P0[r] - k[r] / (52.0 * CountRegression.INDICATOR);
                    sum += share;
                    if (r <= 9) {
                        // A node has seen at most ten cards of the round.
                        assertEquals(before[r - 1] + CountKeys.P0[r], share, 10.0 / (left - 10));
                    }
                }
                assertEquals(1, sum, 1e-7);
            }
        }
        int[] states = {0, 1, 2, 3, 4, 5, 6, 7};
        List<CountRegression.Spot> later = CountRegression.laterDecisions(st, states, fx.trees, StateRun.montreal(), 2);
        assertFalse(later.isEmpty());
        for (CountRegression.Spot x : later) {
            assertTrue(x.worth >= 0);
            assertTrue(x.cell >= 0 && x.cell < FullIndexFit.CELLS);
        }
    }

    /**
     * The effects of removal off the top: the chart value of a shoe is the scorer's (checked on
     * FullIndexFitTest's states, whose values the same StateValuer gave), and the effects lean as
     * every count does: a five out helps the player most, a ten or an ace out hurts.
     */
    @Test
    public void theEffectsOfRemovalLeanAsEveryCountDoes() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        for (int s = 0; s < fx.st.size; s++) {
            StateStore.Record r = fx.records.get(s);
            assertEquals(fx.st.chart[s], EffectsOfRemoval.chartValue(StateRun.montreal(), r.ranksLeft(), r.values), 1e-15);
        }
        EffectsOfRemoval.Effects e = EffectsOfRemoval.effects(StateRun.montreal(), 4,
                Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2)));
        int best = 1;
        for (int r = 1; r <= 10; r++) {
            if (e.of(r) > e.of(best)) {
                best = r;
            }
        }
        assertEquals(5, best, "a five out helps most");
        assertTrue(e.of(10) < 0);
        assertTrue(e.of(1) < 0);
        double mean = 0;
        for (int r = 1; r <= 10; r++) {
            mean += CountKeys.P0[r] * e.of(r);
        }
        assertEquals(0, mean, 2e-5, "a card of the shoe's own mix out changes little");
    }
}
