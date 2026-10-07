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
}
