import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The split margin's search (SplitMargin): the split play it starts from is the chart's, to the
 * bit; the play it finds is never worse; and a state's delta is what that adds to the perfect
 * row's first moves, never below zero.
 */
public class SplitMarginTest {

    private static final RoundRules RULES = StateRun.montreal();

    @Test
    public void theAscentStartsAtTheChartAndNeverLosesGround() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        StateStore.Record r = fx.records.get(0);
        int[] left = r.ranksLeft();
        ExactRound engine = new ExactRound(RULES);
        int split = SystemScorer.SPLIT;
        int moves = ScoringStates.MOVES;
        int[] deals = {Deals.index(8, 8, 10), Deals.index(9, 9, 7), Deals.index(2, 2, 3)};
        for (int i : deals) {
            assertTrue(Arrays.stream(SplitMargin.pairDeals(RULES)).anyMatch(x -> x == i));
            SplitValues sv = new SplitValues(RULES, left, null, new java.util.HashMap<>(), engine);
            boolean[] stands = new boolean[44];
            for (int total = 4; total <= 21; total++) {
                for (boolean soft : new boolean[]{false, true}) {
                    stands[(soft ? 22 : 0) + total] = PlayerTree.CHART.stands(total, soft, Deals.UP[i], 0);
                }
            }
            double chart = sv.value(i, SplitMargin.play(RULES, Deals.P1[i], Deals.UP[i], stands, new PlayerMove[11]));
            assertEquals(r.values[i * moves + split], chart, 1e-12, "the chart's tables play the chart's split");
        }
        SplitMargin.Delta d = SplitMargin.delta(RULES, r, engine, deals);
        assertEquals(deals.length, d.gains.size());
        for (double[] g : d.gains) {
            assertTrue(g[1] >= 0, "deal " + (int) g[0] + " lost " + g[1]);
        }
        assertTrue(d.delta >= 0);
        assertTrue(d.valued > 0);
        // A state's delta is the perfect row's first moves with Split at its best, less with Split at the chart's.
        double expected = 0;
        for (double[] g : d.gains) {
            int i = (int) g[0];
            double p = Deals.probability(left, i);
            double dn = Deals.dealerNatural(left, i);
            double other = Double.NEGATIVE_INFINITY;
            for (int m = 0; m < moves; m++) {
                if (m != split && StateValuer.legal(RULES, i, StateValuer.MOVES[m])) {
                    other = Math.max(other, r.values[i * moves + m]);
                }
            }
            double chart = r.values[i * moves + split];
            expected += p * (1 - dn) * (Math.max(other, chart + g[1]) - Math.max(other, chart));
        }
        assertEquals(expected, d.delta, 1e-15);
    }

    /**
     * The tool's summary: at each penetration the mean of delta weighted 1/q over the sample's
     * states of that penetration alone, and its lines call the mean and the margin a lower bound.
     */
    @Test
    public void theMarginIsGivenPerPenetrationAndCalledALowerBound() throws Exception {
        java.util.List<StateStore.Record> sample = new java.util.ArrayList<>();
        int[] depths = {SystemScorer.CUTS[0] - 1, SystemScorer.CUTS[1] - 1, SystemScorer.CUTS[2] - 1, 10};
        double[] q = {0.5, 0.25, 1, 0.1};
        double[] delta = {0.001, 0.003, 0.010, 0.0};
        for (int k = 0; k < depths.length; k++) {
            sample.add(new StateStore.Record(k, 0, depths[k], q[k], new int[ShoeRun.KINDS], new double[StateStore.VALUES]));
        }
        double[][] m = SplitMargin.summary(sample, delta);
        // 70%: the first and last states; 75%: and the second; 80%: all four.
        assertEquals((2 * 0.001 + 10 * 0.0) / 12, m[0][0], 1e-15);
        assertEquals((2 * 0.001 + 4 * 0.003 + 10 * 0.0) / 16, m[1][0], 1e-15);
        assertEquals((2 * 0.001 + 4 * 0.003 + 1 * 0.010 + 10 * 0.0) / 17, m[2][0], 1e-15);
        assertEquals(4, (int) m[2][2]);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        SplitMargin.print(new java.io.PrintStream(bytes, true, "UTF-8"), sample, delta);
        String text = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("a lower bound"), text);
        for (String pen : SystemScorer.PENETRATIONS) {
            assertTrue(text.contains("- " + pen + ": "), text);
        }
    }
}
