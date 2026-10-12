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

    /**
     * Change 3: the ascent run again from random split plays. Each restart's ascent never ends below
     * its own start; each deal keeps the best of the chart-start optimum and every restart, so the
     * state's delta with restarts is never below its delta from the chart's start, which equals
     * delta's own; and the same seed gives the same figures.
     */
    @Test
    public void aRestartNeverEndsBelowTheChartsStart() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        StateStore.Record r = fx.records.get(0);
        ExactRound engine = new ExactRound(RULES);
        int[] deals = {Deals.index(8, 8, 10), Deals.index(9, 9, 7), Deals.index(2, 2, 3)};
        SplitMargin.Restart a = SplitMargin.restarts(RULES, r, engine, deals, 2, 7);
        assertEquals(SplitMargin.delta(RULES, r, engine, deals).delta, a.delta0, 1e-15);
        assertEquals(deals.length, a.deals.size());
        for (double[] d : a.deals) {
            double chart = r.values[(int) d[0] * ScoringStates.MOVES + SystemScorer.SPLIT];
            assertTrue(d[1] >= chart, "the chart-start optimum is not below the chart");
            assertTrue(d[2] >= d[1], "deal " + (int) d[0] + ": the restarts' best " + d[2] + " below " + d[1]);
            assertTrue(d[3] >= 0, "a restart's ascent ended " + d[3] + " below its own start");
            assertEquals(Math.max(d[1], d[4]), d[2], 0, "deal " + (int) d[0] + " keeps the best of the chart-start "
                    + "optimum " + d[1] + " and the restarts' highest end " + d[4]);
        }
        assertTrue(a.gain() >= 0, "gain " + a.gain());
        SplitMargin.Restart b = SplitMargin.restarts(RULES, r, engine, deals, 2, 7);
        assertEquals(a.delta, b.delta, 0, "the same seed, the same restarts");
    }

    /** Change 2: the rearranged pairing is the best of every pairing on a tiny set, by brute force over all of them. */
    @Test
    public void theRearrangedMarginIsABruteForceSort() {
        double[] a = {0.004, 0.0, 0.010, 0.001, 0.007};
        double[] b = {2, 8, 1, 5, 3};
        double[] one = {1, 1, 1, 1, 1};
        double best = Double.NEGATIVE_INFINITY;
        for (int[] perm : permutations(5)) {
            double sum = 0;
            for (int k = 0; k < 5; k++) {
                sum += a[k] * b[perm[k]];
            }
            best = Math.max(best, sum / 5);
        }
        assertEquals(best, SplitMargin.rearranged(a, one, b, one), 1e-15);
        // Whole-number weights are the same as that many copies of each value.
        double[] w = {2, 1, 1, 3, 1};
        double[] ea = new double[8];
        double[] eb = new double[8];
        int at = 0;
        for (int k = 0; k < 5; k++) {
            for (int c = 0; c < w[k]; c++) {
                ea[at] = a[k];
                eb[at++] = b[k];
            }
        }
        double[] eight = new double[8];
        Arrays.fill(eight, 1);
        assertEquals(SplitMargin.rearranged(ea, eight, eb, eight), SplitMargin.rearranged(a, w, b, w), 1e-15);
    }

    private static java.util.List<int[]> permutations(int n) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        permute(new int[n], new boolean[n], 0, out);
        return out;
    }

    private static void permute(int[] p, boolean[] used, int at, java.util.List<int[]> out) {
        if (at == p.length) {
            out.add(p.clone());
            return;
        }
        for (int v = 0; v < p.length; v++) {
            if (!used[v]) {
                used[v] = true;
                p[at] = v;
                permute(p, used, at + 1, out);
                used[v] = false;
            }
        }
    }

    /** Rows of a made-up sample: three penetrations' depths, q from 0.1 to 1, delta and chart values spread. */
    static java.util.List<SplitMargin.Row> rows(int n, long seed) {
        java.util.SplittableRandom rng = new java.util.SplittableRandom(seed);
        java.util.List<SplitMargin.Row> rows = new java.util.ArrayList<>();
        for (int k = 0; k < n; k++) {
            SplitMargin.Row r = new SplitMargin.Row();
            r.shoe = k;
            r.round = 1 + k % 5;
            r.depth = 20 + rng.nextInt(SystemScorer.CUTS[2] - 20);
            r.q = 0.1 + 0.9 * rng.nextDouble();
            r.chart = -0.02 + 0.04 * rng.nextDouble();
            r.delta = rng.nextDouble() < 0.3 ? 0 : 0.0003 * rng.nextDouble();
            r.valued = rng.nextInt(400);
            r.restartGain = k % 4 == 0 ? 0.00001 * rng.nextDouble() : Double.NaN;
            r.gains.add(new double[]{Deals.index(8, 8, 10), 0.001 * rng.nextDouble()});
            r.gains.add(new double[]{Deals.index(2, 2, 3), 0});
            rows.add(r);
        }
        return rows;
    }

    /**
     * The rearranged margin is never above S x mean(delta), equals it flat, where every bet is one
     * unit, and is at least mean(delta) x the schedule's mean bet (similarly ordered values); its
     * SE comes from draws of the states.
     */
    @Test
    public void theRearrangedMarginIsUnderTheCrudeOne() {
        java.util.List<SplitMargin.Row> rows = rows(60, 3);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            double[][] m = SplitMargin.margins(rows, p, null, 0);
            assertEquals(m[1][0], m[1][1], 1e-15, "flat, the two are one");
            for (int s = 2; s <= SystemScorer.SPREADS; s++) {
                assertTrue(m[s][1] <= m[s][0] + 1e-15, s + ": " + m[s][1] + " above " + m[s][0]);
                assertTrue(m[s][1] < m[s][0], "the schedule bets under S somewhere, so the margin is tighter");
            }
        }
        double[][] se = SplitMargin.marginSE(rows, 50, 1);
        assertTrue(se[2][8] > 0 && se[2][8] < SplitMargin.margins(rows, 2, null, 0)[8][1]);
    }

    /** Change 1: the out file reads back to the bit, and the rearranged report reads nothing else. */
    @Test
    public void theOutFileReadsBackToTheBit(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.util.List<SplitMargin.Row> rows = rows(25, 5);
        java.nio.file.Path file = dir.resolve("split.tsv");
        SplitMargin.writeOut(file, "SplitMargin test", rows);
        java.util.List<SplitMargin.Row> back = SplitMargin.readOut(file);
        assertEquals(rows.size(), back.size());
        for (int k = 0; k < rows.size(); k++) {
            SplitMargin.Row a = rows.get(k);
            SplitMargin.Row b = back.get(k);
            assertEquals(a.shoe, b.shoe);
            assertEquals(a.round, b.round);
            assertEquals(a.depth, b.depth);
            assertEquals(a.valued, b.valued);
            for (double[] pair : new double[][]{{a.q, b.q}, {a.chart, b.chart}, {a.delta, b.delta},
                    {a.restartGain, b.restartGain}}) {
                assertEquals(Double.doubleToLongBits(pair[0]), Double.doubleToLongBits(pair[1]));
            }
            assertEquals(a.gains.size(), b.gains.size());
            for (int g = 0; g < a.gains.size(); g++) {
                assertArrayEquals(a.gains.get(g), b.gains.get(g), 0);
            }
        }
        assertThrows(IllegalStateException.class, () -> SplitMargin.writeOut(file, "again", rows), "never written over");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        SplitMargin.rearrangedReport(back, 20, 1, new java.io.PrintStream(bytes, true, "UTF-8"));
        String text = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("measured from below") && text.contains("| 1-8 |") && text.contains("restart gain added"),
                text);
    }
}
