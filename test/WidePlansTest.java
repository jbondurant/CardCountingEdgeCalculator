import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The wide plans of PLAN_COMPUTE.md section 3 (SystemScorer.Plan, Wide, planIntegral): a naive
 * reference that splits each state's weight into the pieces of the quantile range each block
 * covers, in O(n squared), on random small folds whose advantages sit on a grid that holds every
 * threshold exactly; P8 against same(8), own(8) and their bets to the bit; every existing figure
 * the same to the bit with the plans on; the plans' properties; and the lowest key so far against
 * shoes dealt again.
 *
 * Planted by hand in SystemScorer on 9 October 2026, one at a time, each failing the test named:
 *
 * | Planted | What failed |
 * |---|---|
 * | (filled in below as each plant is run) | |
 */
public class WidePlansTest {

    private static final double CLOSE = 1e-12;

    // ------------------------------------------------------------------ the naive reference

    /** g(a), written apart from Plan.bet: nothing below enter, else a unit per `unit`, at least 1 and at most cap. */
    static double g(SystemScorer.Plan plan, double a) {
        if (a < plan.enter) {
            return 0;
        }
        double units = a / plan.unit;
        return units < 1 ? 1 : units > plan.cap ? plan.cap : units;
    }

    /** A block of the hand-out: the states or keys it holds, their weight and weighted sum of round values. */
    static final class Block {
        double weight;
        double sum;
        final List<Integer> keys = new ArrayList<>();
    }

    /**
     * What each plan's same schedule makes of blocks in order, handed the reference bets of the
     * test states given: [plan][block] of {integral of g, integral of 1[g > 0]}, each state's
     * weight cut into the pieces of the quantile range each block covers, one block and one state
     * at a time.
     */
    static double[][][] pieces(SystemScorer.Plan[] plans, List<Block> blocks, int[] test, double[] w, double[] chart) {
        Integer[] byChart = Arrays.stream(test).filter(j -> w[j] != 0).boxed().toArray(Integer[]::new);
        Arrays.sort(byChart, Comparator.comparingDouble(j -> chart[j]));
        double[][][] out = new double[plans.length][blocks.size()][2];
        double lo = 0;
        for (int b = 0; b < blocks.size(); b++) {
            double hi = lo + blocks.get(b).weight;
            double at = 0;
            for (int j : byChart) {
                double overlap = Math.max(0, Math.min(hi, at + w[j]) - Math.max(lo, at));
                for (int i = 0; i < plans.length; i++) {
                    double bet = g(plans[i], chart[j]);
                    out[i][b][0] += overlap * bet;
                    out[i][b][1] += bet > 0 ? overlap : 0;
                }
                at += w[j];
            }
            lo = hi;
        }
        return out;
    }

    /**
     * One fold's sums by the naive route, added into sums ([plan x WIDE_METRICS + m], then the
     * weight last) and, given, its per-key figures into keys ([key][plan x KEY_COLS + c]): the
     * advantage fitted on the training states as bets fits it, the test keys in order of it, keys
     * of equal advantage one block, and the bets of PLAN_COMPUTE.md 3.2 worked out from the pieces.
     */
    static void naiveFold(SystemScorer.Plan[] plans, int[] value, int[] keyOf, int[] train, int[] test, double[] w,
                          double[] rv, double[] chart, int[] minKey, double[] sums, double[][] keys) {
        int nk = value.length;
        double[] tw = new double[nk];
        double[] ts = new double[nk];
        double[] vw = new double[nk];
        double[] vs = new double[nk];
        for (int j : train) {
            tw[keyOf[j]] += w[j];
            ts[keyOf[j]] += w[j] * rv[j];
        }
        for (int j : test) {
            vw[keyOf[j]] += w[j];
            vs[keyOf[j]] += w[j] * rv[j];
        }
        double[] adv = SystemScorer.isotonic(value, tw, ts);
        List<Integer> present = new ArrayList<>();
        for (int k = 0; k < nk; k++) {
            if (vw[k] > 0) {
                present.add(k);
            }
        }
        present.sort(Comparator.comparingDouble(k -> adv[k]));
        List<Block> blocks = new ArrayList<>();
        for (int k : present) {
            if (blocks.isEmpty() || adv[blocks.get(blocks.size() - 1).keys.get(0)] != adv[k]) {
                blocks.add(new Block());
            }
            Block b = blocks.get(blocks.size() - 1);
            b.keys.add(k);
            b.weight += vw[k];
            b.sum += vs[k];
        }
        double[][][] pc = pieces(plans, blocks, test, w, chart);
        double weight = 0;
        for (int k = 0; k < nk; k++) {
            weight += vw[k];
        }
        sums[sums.length - 1] += weight;
        for (int i = 0; i < plans.length; i++) {
            SystemScorer.Plan plan = plans[i];
            int at = i * SystemScorer.WIDE_METRICS;
            int col = i * SystemScorer.KEY_COLS;
            boolean exits = plan.exit != Double.NEGATIVE_INFINITY;
            for (int b = 0; b < blocks.size() && !exits; b++) {
                Block x = blocks.get(b);
                double gi = pc[i][b][0];
                double zi = pc[i][b][1];
                double expected = gi / x.weight;
                boolean sits = plan.enter != Double.NEGATIVE_INFINITY;
                double play = sits ? zi / x.weight : 1;
                double placed = !sits ? expected : zi == 0 ? 0 : gi / zi;
                for (int k : x.keys) {
                    sums[at + SystemScorer.W_SAME] += expected * vs[k];
                    sums[at + SystemScorer.W_SAME_BET] += expected * vw[k];
                    sums[at + SystemScorer.W_SAME_SHARE] += play * vw[k];
                    sums[at + SystemScorer.W_SAME_BET2] += play * vw[k] * placed * placed;
                    if (keys != null) {
                        keys[k][col + SystemScorer.K_SAME_BET] = expected;
                        keys[k][col + SystemScorer.K_SAME_PLAY] = play;
                    }
                }
            }
            // The key from which the fitted advantage reaches the exit, found by its own walk.
            int from = Integer.MAX_VALUE;
            for (int k = nk - 1; k >= 0; k--) {
                if (exits && adv[k] >= plan.exit) {
                    from = value[k];
                }
            }
            for (int j : test) {
                if (w[j] == 0 || (exits && minKey[j] < from)) {
                    continue;
                }
                double bet = g(plan, adv[keyOf[j]]);
                sums[at + SystemScorer.W_OWN] += bet * w[j] * rv[j];
                sums[at + SystemScorer.W_OWN_BET] += bet * w[j];
                sums[at + SystemScorer.W_OWN_SHARE] += bet > 0 ? w[j] : 0;
                sums[at + SystemScorer.W_OWN_BET2] += bet * bet * w[j];
            }
            if (keys != null) {
                for (int k = 0; k < nk; k++) {
                    keys[k][col + SystemScorer.K_OWN_BET] = g(plan, adv[k]);
                }
            }
        }
    }

    /**
     * The 23 plans and four more whose thresholds and units make the grid's advantages land on
     * them in other ways: a back-counting plan at +0.25% with cap 4, one entering at -1.25%, one
     * leaving at +0.5%, and one at a unit of 0.5%.
     */
    static SystemScorer.Plan[] testPlans() {
        double none = Double.NEGATIVE_INFINITY;
        List<SystemScorer.Plan> out = new ArrayList<>(Arrays.asList(SystemScorer.WIDE_PLANS));
        out.add(new SystemScorer.Plan("B+0.25,4", SystemScorer.UNIT, 4, 0.0025, none));
        out.add(new SystemScorer.Plan("B-1.25,8", SystemScorer.UNIT, 8, -0.0125, none));
        out.add(new SystemScorer.Plan("X+0.5,8", SystemScorer.UNIT, 8, none, 0.005));
        out.add(new SystemScorer.Plan("U0.5,6", 2 * SystemScorer.UNIT, 6, none, none));
        return out.toArray(new SystemScorer.Plan[0]);
    }

    /** a and b agree within CLOSE, relative to the larger of 1 and |a|. */
    static void close(double a, double b, String what) {
        if (Double.isNaN(a) || Double.isNaN(b)) {
            assertEquals(Double.isNaN(a), Double.isNaN(b), what + ": " + a + " against " + b);
            return;
        }
        assertEquals(a, b, CLOSE * Math.max(1, Math.abs(a)), what);
    }

    /**
     * On 600 random small folds of up to 13 states, two folds each, every plan's figures from bets
     * and Wide, and every key's bets, are the naive reference's within 1e-12: EV, average bet,
     * share played and mean square bet, same schedule and own rule. Advantages and chart values
     * lie on a grid of 0.25% that holds every threshold, so rounds exactly at a threshold occur;
     * keys are few, so blocks span several states and straddle thresholds; a tenth of the weights
     * are 0, as a resample's are; and half the folds fit their training keys one state each with
     * rising round values, so the fitted advantage too sits on a threshold.
     */
    @Test
    public void everyPlanIsTheNaiveQuantileSplitOnRandomSmallFolds() {
        SystemScorer.Plan[] plans = testPlans();
        Random rnd = new Random(20261009);
        double[] weights = {0, 0.5, 1, 1, 2, 3.7, 1 / 0.3};
        int straddled = 0;
        int atThreshold = 0;
        for (int trial = 0; trial < 600; trial++) {
            boolean exact = trial % 2 == 0;
            int nKeys = 2 + rnd.nextInt(4);
            int n = 6 + rnd.nextInt(14);
            int[] raw = new int[n];
            double[] w = new double[n];
            double[] rv = new double[n];
            double[] chart = new double[n];
            int[] minKey = new int[n];
            for (int j = 0; j < n; j++) {
                raw[j] = rnd.nextInt(nKeys) * 3 - 2;
                w[j] = exact ? 1 : weights[rnd.nextInt(weights.length)];
                chart[j] = (rnd.nextInt(17) - 6) * SystemScorer.UNIT;
                rv[j] = exact ? (raw[j] + 2) / 3 * 2 * SystemScorer.UNIT - 0.005 : (rnd.nextInt(17) - 6) * SystemScorer.UNIT;
                minKey[j] = raw[j] - 3 * rnd.nextInt(3);
            }
            // Two folds: each state's fold at random, each fold fitted on the other.
            int[] fold = new int[n];
            for (int j = 0; j < n; j++) {
                fold[j] = rnd.nextInt(2);
            }
            SystemScorer.BetKeys bk = keys(raw);
            SystemScorer.Wide acc = SystemScorer.Wide.of(plans, chart, minKey);
            double[] sums = new double[SystemScorer.METRICS + 1];
            double[] naive = new double[plans.length * SystemScorer.WIDE_METRICS + 1];
            for (int f = 0; f < 2; f++) {
                int ff = f;
                int[] test = java.util.stream.IntStream.range(0, n).filter(j -> fold[j] == ff).toArray();
                int[] train = java.util.stream.IntStream.range(0, n).filter(j -> fold[j] != ff).toArray();
                Integer[] order = Arrays.stream(test).boxed().toArray(Integer[]::new);
                Arrays.sort(order, Comparator.comparingDouble(j -> chart[j]));
                int[] byChart = Arrays.stream(order).mapToInt(Integer::intValue).toArray();
                acc.detail = new double[bk.value.length][plans.length * SystemScorer.KEY_COLS];
                SystemScorer.bets(bk, train, test, byChart, w, rv, chart, sums, null, acc);
                double[][] keys = new double[bk.value.length][plans.length * SystemScorer.KEY_COLS];
                naiveFold(plans, bk.value, bk.index, train, test, w, rv, chart, minKey, naive, keys);
                for (int k = 0; k < bk.value.length; k++) {
                    for (int i = 0; i < plans.length; i++) {
                        for (int c = 0; c < SystemScorer.KEY_COLS; c++) {
                            int at = i * SystemScorer.KEY_COLS + c;
                            if (c == SystemScorer.K_OWN_BET || plans[i].sameSchedule()) {
                                close(keys[k][at], acc.detail[k][at], "trial " + trial + " fold " + f + " key "
                                        + bk.value[k] + " " + plans[i] + " column " + c);
                            }
                        }
                        double play = acc.detail[k][i * SystemScorer.KEY_COLS + SystemScorer.K_SAME_PLAY];
                        straddled += play > 0 && play < 1 ? 1 : 0;
                    }
                }
                for (int j : test) {
                    for (SystemScorer.Plan p : plans) {
                        atThreshold += chart[j] == p.enter || chart[j] == p.exit ? 1 : 0;
                    }
                }
            }
            double[] got = acc.figures();
            double weight = naive[naive.length - 1];
            for (int i = 0; i < plans.length; i++) {
                for (int m = 0; m < SystemScorer.WIDE_METRICS; m++) {
                    int at = SystemScorer.wideAt(i, m);
                    boolean none = m < SystemScorer.W_OWN && !plans[i].sameSchedule();
                    double want = none || weight == 0 ? Double.NaN : naive[at] / weight;
                    close(want, got[at], "trial " + trial + " " + plans[i] + " " + SystemScorer.WIDE_NAMES[m]);
                }
            }
        }
        assertTrue(straddled > 500, "blocks straddling a threshold: " + straddled);
        assertTrue(atThreshold > 500, "rounds exactly at a threshold: " + atThreshold);
    }

    /** The bet keys of raw keys, as SystemScorer.betKeys makes them. */
    static SystemScorer.BetKeys keys(int[] raw) {
        int[] value = Arrays.stream(raw).distinct().sorted().toArray();
        int[] index = new int[raw.length];
        for (int j = 0; j < raw.length; j++) {
            index[j] = Arrays.binarySearch(value, raw[j]);
        }
        return new SystemScorer.BetKeys(value, index);
    }

    // ------------------------------------------------------------------ on the scorer's states

    /**
     * Round starts for FullIndexFitTest's states, whose shoes were never dealt: each shoe's valued
     * states taken as its only round starts, the lowest key so far found by looking at every state.
     */
    static SystemScorer.RoundStarts valuedOnly(ScoringStates st) {
        return c -> {
            int[] out = new int[st.size];
            for (int s = 0; s < st.size; s++) {
                int lowest = Integer.MAX_VALUE;
                for (int t = 0; t < st.size; t++) {
                    if (st.shoe[t] == st.shoe[s] && st.round[t] <= st.round[s]) {
                        lowest = Math.min(lowest, c.betKey(st.dealt[t], st.depth[t]));
                    }
                }
                out[s] = lowest;
            }
            return out;
        };
    }

    /** A scorer of the fixture's states with the plans given on (none when null), and its rows bet on their own value. */
    static SystemScorer scorer(FullIndexFitTest.Fixture fx, SystemScorer.Plan[] plans, int resamples) {
        SystemScorer sc = new SystemScorer(fx.st, new Bootstrap(fx.st.shoes, resamples, 1), 1);
        sc.ownValueRows = true;
        sc.wide = plans;
        sc.roundStarts = valuedOnly(fx.st);
        return sc;
    }

    /** The counts scored here: plain, kind-aware, with an ace side count, and the null count. */
    static List<CountKeys.Count> counts() {
        return Arrays.asList(FullIndexFitTest.HI_LO, FullIndexFitTest.RED_SEVEN, PlayerTreeOrderTest.named("Omega II"),
                FullIndexFitTest.NULL);
    }

    private static void bits(double a, double b, String what) {
        assertEquals(Double.doubleToLongBits(a), Double.doubleToLongBits(b), what + ": " + a + " against " + b);
    }

    /** Every figure of P8 is spread 8's, to the bit: estimate, every resample and every key. */
    private static void p8IsSpreadEight(SystemScorer.Entry e, boolean own) {
        int p8 = SystemScorer.planIndex(e.widePlans, "P8");
        int s8 = SystemScorer.SPREADS;
        int[][] pairs = {{SystemScorer.W_SAME, SystemScorer.same(s8)}, {SystemScorer.W_SAME_BET, SystemScorer.sameBet(s8)},
                {SystemScorer.W_OWN, SystemScorer.own(s8)}, {SystemScorer.W_OWN_BET, SystemScorer.ownBet(s8)}};
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            if (e.estimate[p] == null) {
                continue;
            }
            for (int[] pair : pairs) {
                if (pair[0] >= SystemScorer.W_OWN && !own) {
                    assertTrue(Double.isNaN(e.wide[p][SystemScorer.wideAt(p8, pair[0])]), e.name);
                    continue;
                }
                bits(e.estimate[p][pair[1]], e.wide[p][SystemScorer.wideAt(p8, pair[0])],
                        e.name + " P8 " + SystemScorer.WIDE_NAMES[pair[0]] + " at " + p);
            }
            double[][] rows = {e.wideBoot(p, "P8", SystemScorer.W_SAME), e.wideBoot(p, "P8", SystemScorer.W_OWN),
                    e.wideBoot(p, "P8", SystemScorer.W_OWN_BET)};
            int[] metric = {SystemScorer.same(s8), SystemScorer.own(s8), SystemScorer.ownBet(s8)};
            for (int k = 0; k < rows.length; k++) {
                for (int r = 0; r < rows[k].length; r++) {
                    bits(e.boot[p][metric[k]][r], rows[k][r], e.name + " P8 resample " + r + " figure " + k);
                }
            }
            if (e.wideKeys != null && e.wideKeys[p] != null) {
                assertEquals(e.keys[p].length, e.wideKeys[p].length, e.name);
                for (int row = 0; row < e.keys[p].length; row++) {
                    int at = p8 * SystemScorer.KEY_COLS;
                    bits(e.keys[p][row][3 + s8], e.wideKeys[p][row][at + SystemScorer.K_SAME_BET], e.name + " key row " + row);
                    bits(e.keys[p][row][3 + SystemScorer.SPREADS + s8], e.wideKeys[p][row][at + SystemScorer.K_OWN_BET],
                            e.name + " own bet, key row " + row);
                    assertEquals(1.0, e.wideKeys[p][row][at + SystemScorer.K_SAME_PLAY], 0.0);
                }
            }
        }
    }

    /**
     * P8 gives spread 8's same schedule, own ramp and their average bets to the bit (PLAN_COMPUTE.md
     * 3.5, check 1): every entry of four counts, at first decisions and with full play, betting only,
     * on the count's own value and the side-count variant, the estimate, every resample and every
     * key; the bound rows and the oracle row too.
     */
    @Test
    public void p8IsTheSpreadOfOneToEightToTheBit() throws Exception {
        FullIndexFitTest.Run run = FullIndexFitTest.capped();
        SystemScorer sc = scorer(run.fx, SystemScorer.WIDE_PLANS, 5);
        for (CountKeys.Count c : counts()) {
            SystemScorer.Result r = sc.score(c, CountKeys.bettingVariant(c), run.fits.get(c));
            for (SystemScorer.Entry e : r.entries) {
                p8IsSpreadEight(e, !e.ownValue);
            }
            for (SystemScorer.Entry e : r.fullEntries) {
                p8IsSpreadEight(e, !e.ownValue);
            }
            assertEquals(run.fits.containsKey(c), !r.fullEntries.isEmpty(), c.name);
        }
        for (SystemScorer.Entry e : sc.bounds(null)) {
            p8IsSpreadEight(e, false);
        }
        SystemScorer.Entry oracle = sc.references().get(2);
        p8IsSpreadEight(oracle, false);
    }

    /** Two Results' existing figures the same to the bit, full play and the 1-8 half fit included. */
    static void sameExisting(SystemScorer.Result a, SystemScorer.Result b) {
        CeilingScoringTest.sameResult(a, b);
        assertEquals(a.fullEntries.size(), b.fullEntries.size());
        List<SystemScorer.Entry> x = new ArrayList<>(a.entries);
        List<SystemScorer.Entry> y = new ArrayList<>(b.entries);
        x.addAll(a.fullEntries);
        y.addAll(b.fullEntries);
        for (int e = 0; e < x.size(); e++) {
            String n = x.get(e).name;
            assertEquals(n, y.get(e).name);
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                arrays(x.get(e).estimate[p], y.get(e).estimate[p], n + " estimate " + p);
                if (x.get(e).boot[p] != null) {
                    for (int m = 0; m < SystemScorer.METRICS; m++) {
                        arrays(x.get(e).boot[p][m], y.get(e).boot[p][m], n + " resamples " + p + " " + m);
                    }
                }
                arrays(x.get(e).shoeSums[p], y.get(e).shoeSums[p], n + " shoe sums " + p);
                assertEquals(x.get(e).keys[p] == null, y.get(e).keys[p] == null, n);
                if (x.get(e).keys[p] != null) {
                    for (int k = 0; k < x.get(e).keys[p].length; k++) {
                        arrays(x.get(e).keys[p][k], y.get(e).keys[p][k], n + " key row " + k);
                    }
                }
            }
            if (x.get(e).halfFitSame != null) {
                arrays(x.get(e).halfFitSame, y.get(e).halfFitSame, n + " half fit 1-8");
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    arrays(x.get(e).halfFitSameBoot[p], y.get(e).halfFitSameBoot[p], n + " half fit 1-8 resamples");
                }
            }
        }
        arrays(a.fullHalfFitFlat, b.fullHalfFitFlat, "full-play half fit");
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            arrays(a.fullHalfFitBoot[p], b.fullHalfFitBoot[p], "full-play half fit resamples " + p);
        }
    }

    static void arrays(double[] a, double[] b, String what) {
        assertEquals(a == null, b == null, what);
        if (a != null) {
            assertEquals(a.length, b.length, what);
            for (int k = 0; k < a.length; k++) {
                bits(a[k], b[k], what + " at " + k);
            }
        }
    }

    /**
     * With the plans on, every existing figure is what it is with them off, to the bit
     * (PLAN_COMPUTE.md 3.5, check 2, on fixtures): estimates, resamples, per-shoe sums, key tables,
     * half fits and resolution, at first decisions and with full play, for every row of four
     * counts, the bound rows and the reference rows; and the plans are there only when on.
     */
    @Test
    public void everyExistingFigureIsTheSameWithThePlansOn() throws Exception {
        FullIndexFitTest.Run run = FullIndexFitTest.capped();
        SystemScorer off = scorer(run.fx, null, 6);
        SystemScorer on = scorer(run.fx, SystemScorer.WIDE_PLANS, 6);
        for (CountKeys.Count c : counts()) {
            SystemScorer.Result a = off.score(c, CountKeys.bettingVariant(c), run.fits.get(c));
            SystemScorer.Result b = on.score(c, CountKeys.bettingVariant(c), run.fits.get(c));
            sameExisting(a, b);
            for (SystemScorer.Entry e : a.entries) {
                assertNull(e.wide, e.name);
                assertNull(e.widePlans, e.name);
            }
            for (SystemScorer.Entry e : b.entries) {
                assertNotNull(e.wide[2], e.name);
            }
        }
        List<SystemScorer.Entry> ba = off.bounds(null);
        List<SystemScorer.Entry> bb = on.bounds(null);
        List<SystemScorer.Entry> ra = off.references();
        List<SystemScorer.Entry> rb = on.references();
        ba.addAll(ra);
        bb.addAll(rb);
        for (int e = 0; e < ba.size(); e++) {
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                arrays(ba.get(e).estimate[p], bb.get(e).estimate[p], ba.get(e).name);
                for (int m = 0; m < SystemScorer.METRICS; m++) {
                    arrays(ba.get(e).boot[p][m], bb.get(e).boot[p][m], ba.get(e).name + " resamples");
                }
            }
        }
    }

    /**
     * With the plans on, a published count still scores as c06c061 scored it, to the bit: the
     * reproduction CeilingScoringTest makes with them off, made with them on, for the null count and
     * the counts of every kind (plain, kind-aware, side-counted, unbalanced) with c06c061's own
     * classes scoring beside these.
     */
    @Test
    public void withThePlansOnACountStillScoresAsC06c061ScoredIt() throws Exception {
        ClassLoader old = C06c061.loader();
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        int shoes = fx.st.shoes;
        int resamples = 5;
        List<Object> records = new ArrayList<>();
        for (StateStore.Record r : fx.records) {
            records.add(C06c061.make(old, "StateStore$Record", new Class<?>[]{int.class, int.class, int.class,
                    double.class, int[].class, double[].class}, r.shoe, r.round, r.depth, r.q, r.dealt.clone(),
                    r.values.clone()));
        }
        Object rules = C06c061.call(old.loadClass("StateRun"), "montreal", new Class<?>[0]);
        Object oldSt = C06c061.make(old, "ScoringStates", new Class<?>[]{List.class, int.class,
                old.loadClass("RoundRules")}, records, shoes, rules);
        Object oldBoot = C06c061.make(old, "Bootstrap", new Class<?>[]{int.class, int.class, long.class}, shoes, resamples,
                1L);
        Object oldScorer = C06c061.make(old, "SystemScorer", new Class<?>[]{old.loadClass("ScoringStates"),
                old.loadClass("Bootstrap"), double.class}, oldSt, oldBoot, 1.0);
        Class<?> ck = old.loadClass("CountKeys");
        Class<?> count = old.loadClass("CountKeys$Count");
        SystemScorer sc = new SystemScorer(fx.st, new Bootstrap(shoes, resamples, 1), 1);
        sc.wide = SystemScorer.WIDE_PLANS;
        sc.roundStarts = valuedOnly(fx.st);
        String[] names = {null, "Hi-Lo", "Red Seven (Red 7)", "Omega II", "KO (Knock-Out)"};
        @SuppressWarnings("unchecked")
        List<Object> catalog = (List<Object>) C06c061.call(ck, "catalog", new Class<?>[0]);
        for (String name : names) {
            Object was = name == null ? C06c061.call(ck, "nullCount", new Class<?>[0]) : null;
            for (Object c : catalog) {
                if (name != null && C06c061.get(c, "name").equals(PlayerTreeOrderTest.named(name).name)) {
                    was = c;
                }
            }
            assertNotNull(was, name);
            CountKeys.Count now = name == null ? CountKeys.nullCount() : PlayerTreeOrderTest.named(name);
            Object side = C06c061.call(ck, "sideCountVariant", new Class<?>[]{count}, was);
            Object oldResult = C06c061.call(oldScorer, "score", new Class<?>[]{count, count}, was, side);
            SystemScorer.Result written = (SystemScorer.Result) read(write(oldResult));
            SystemScorer.Result scored = sc.score(now, CountKeys.bettingVariant(now));
            CeilingScoringTest.sameResult(written, scored);
            for (SystemScorer.Entry e : scored.entries) {
                assertNotNull(e.wide[2], e.name);
            }
            // Written by these classes and read back, the wide figures come whole.
            SystemScorer.Result back = (SystemScorer.Result) read(write(scored));
            sameExisting(scored, back);
            for (int e = 0; e < scored.entries.size(); e++) {
                sameWide(scored.entries.get(e), back.entries.get(e));
            }
        }
    }

    static void sameWide(SystemScorer.Entry a, SystemScorer.Entry b) {
        assertEquals(SystemScorer.labels(a.widePlans), SystemScorer.labels(b.widePlans), a.name);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            arrays(a.wide[p], b.wide[p], a.name + " wide " + p);
            assertEquals(a.wideBoot[p] == null, b.wideBoot[p] == null);
            if (a.wideBoot[p] != null) {
                for (int k = 0; k < a.wideBoot[p].length; k++) {
                    arrays(a.wideBoot[p][k], b.wideBoot[p][k], a.name + " wide resamples " + p + " row " + k);
                }
            }
            assertEquals(a.wideKeys[p] == null, b.wideKeys[p] == null);
            if (a.wideKeys[p] != null) {
                for (int k = 0; k < a.wideKeys[p].length; k++) {
                    arrays(a.wideKeys[p][k], b.wideKeys[p][k], a.name + " wide keys " + p + " row " + k);
                }
            }
        }
    }

    static Object read(byte[] bytes) throws Exception {
        try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes))) {
            return in.readObject();
        }
    }

    static byte[] write(Object o) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
            out.writeObject(o);
        }
        return bytes.toByteArray();
    }

    /**
     * An item checkpoint c06c061 wrote, kept beside the tests, reads with no wide figures at all:
     * the fields added since read as null, so nothing written before the plans pretends to hold them.
     */
    @Test
    public void aResultWrittenBeforeThePlansReadsWithoutThem() throws Exception {
        SystemScorer.Result was;
        try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(java.nio.file.Files.newInputStream(
                StoredResultsTest.PART))) {
            in.readUTF();
            was = (SystemScorer.Result) in.readObject();
        }
        List<SystemScorer.Entry> all = new ArrayList<>(was.entries);
        all.addAll(was.fullEntries);
        assertFalse(all.isEmpty());
        for (SystemScorer.Entry e : all) {
            assertNull(e.widePlans, e.name);
            assertNull(e.wide, e.name);
            assertNull(e.wideBoot, e.name);
            assertNull(e.wideKeys, e.name);
        }
    }

    /**
     * The plans' properties (PLAN_COMPUTE.md 3.5, check 4), on every row of three counts at every
     * penetration: a back-counting plan entering below every advantage is the plain plan, its EV
     * and average bet to the bit and its share and mean square bet within 1e-12; a cap of 1 with
     * no entry is the flat bet; the share played never rises as a threshold rises; and on every
     * resample every count's same schedule places bets of the same average, in the same share of
     * rounds, as the oracle row's, within 1e-12 relative.
     */
    @Test
    public void thePlansHoldTheirProperties() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        double none = Double.NEGATIVE_INFINITY;
        List<SystemScorer.Plan> list = new ArrayList<>(Arrays.asList(SystemScorer.WIDE_PLANS));
        list.add(new SystemScorer.Plan("B-100,8", SystemScorer.UNIT, 8, -1, none));
        list.add(new SystemScorer.Plan("B-100,16", SystemScorer.UNIT, 16, -1, none));
        list.add(new SystemScorer.Plan("P1", SystemScorer.UNIT, 1, none, none));
        SystemScorer.Plan[] plans = list.toArray(new SystemScorer.Plan[0]);
        SystemScorer sc = scorer(fx, plans, 6);
        List<SystemScorer.Entry> rows = new ArrayList<>();
        for (CountKeys.Count c : counts().subList(0, 3)) {
            rows.addAll(sc.score(c, CountKeys.bettingVariant(c)).entries);
        }
        for (SystemScorer.Entry e : rows) {
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                for (String[] pair : new String[][]{{"B-100,8", "P8"}, {"B-100,16", "P16"}}) {
                    for (int m = 0; m < SystemScorer.WIDE_METRICS; m++) {
                        double b = e.wide(p, pair[0], m);
                        double a = e.wide(p, pair[1], m);
                        if (m == SystemScorer.W_SAME || m == SystemScorer.W_SAME_BET || m == SystemScorer.W_OWN
                                || m == SystemScorer.W_OWN_BET || m == SystemScorer.W_OWN_SHARE) {
                            bits(a, b, e.name + " " + pair[0] + " " + SystemScorer.WIDE_NAMES[m]);
                        } else {
                            close(a, b, e.name + " " + pair[0] + " " + SystemScorer.WIDE_NAMES[m]);
                        }
                    }
                }
                close(e.estimate[p][SystemScorer.FLAT], e.wide(p, "P1", SystemScorer.W_SAME), e.name + " P1 same");
                close(1, e.wide(p, "P1", SystemScorer.W_SAME_BET), e.name + " P1 same bet");
                close(1, e.wide(p, "P1", SystemScorer.W_SAME_SHARE), e.name + " P1 same share");
                if (!e.ownValue) {
                    close(e.estimate[p][SystemScorer.FLAT], e.wide(p, "P1", SystemScorer.W_OWN), e.name + " P1 own");
                    close(1, e.wide(p, "P1", SystemScorer.W_OWN_BET2), e.name + " P1 own square");
                }
                for (int cap : new int[]{1, 8, 16}) {
                    String[] up = {"B-0.5," + cap, "B0," + cap, "B+0.5," + cap, "B+1.0," + cap};
                    for (int m : new int[]{SystemScorer.W_SAME_SHARE, SystemScorer.W_OWN_SHARE}) {
                        for (int k = 1; k < up.length; k++) {
                            if (m == SystemScorer.W_OWN_SHARE && e.ownValue) {
                                continue;
                            }
                            assertTrue(e.wide(p, up[k], m) <= e.wide(p, up[k - 1], m) + CLOSE,
                                    e.name + " " + up[k] + " plays more than " + up[k - 1]);
                        }
                    }
                }
                for (int cap : new int[]{8, 16}) {
                    String[] up = {"X-1.0," + cap, "X-0.5," + cap, "X0," + cap};
                    for (int k = 1; k < up.length && !e.ownValue; k++) {
                        assertTrue(e.wide(p, up[k], SystemScorer.W_OWN_SHARE) <= e.wide(p, up[k - 1], SystemScorer.W_OWN_SHARE),
                                e.name + " " + up[k]);
                    }
                }
            }
        }
        // The same schedule's bets on every resample: the same for every count, and the oracle's.
        SystemScorer.Entry oracle = sc.references().get(2);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            SystemScorer.Pen pen = sc.pen(p);
            for (int r = -1; r < sc.boot.resamples; r++) {
                double[] w = pen.weight.clone();
                double[] u = new double[w.length];
                Arrays.fill(u, 1);
                for (int j = 0; j < w.length && r >= 0; j++) {
                    u[j] = sc.boot.mult(r, fx.st.shoe[pen.states[j]]);
                    w[j] = pen.weight[j] * u[j];
                }
                double[] first = null;
                for (CountKeys.Count c : counts()) {
                    double[] all = sc.scoreWith(c, p, null, w, u);
                    assertEquals(SystemScorer.METRICS + plans.length * SystemScorer.WIDE_METRICS, all.length);
                    if (first == null) {
                        first = all;
                    }
                    for (int i = 0; i < plans.length; i++) {
                        if (!plans[i].sameSchedule()) {
                            continue;
                        }
                        for (int m : new int[]{SystemScorer.W_SAME_BET, SystemScorer.W_SAME_SHARE}) {
                            int at = SystemScorer.METRICS + SystemScorer.wideAt(i, m);
                            close(first[at], all[at], c.name + " " + plans[i] + " resample " + r);
                            if (r < 0) {
                                close(oracle.wide[p][SystemScorer.wideAt(i, m)], all[at], "the oracle's " + plans[i]);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The lowest key so far from the shoes dealt again (PLAN_COMPUTE.md 3.5, check 6, on a small
     * state file of real shoes): AllStates.minKey is, at every valued state, the least bet key over
     * its shoe's rounds dealt by ShoeRun up to and including it, for a balanced count, an unbalanced
     * one and a side-counted one. (A file whose stored tallies differ from the shoes dealt again is
     * refused by AllStates.replay, ComparisonReportTest.)
     */
    @Test
    public void theLowestKeySoFarIsTheShoeDealtAgain(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        int shoes = 10;
        java.nio.file.Path file = ComparisonReportTest.syntheticRun(dir, 77, 77, shoes);
        ScoringStates st = ComparisonReportTest.prepare(file);
        AllStates all = AllStates.replay(StateStore.header(file), shoes, StateRun.montreal(), BasicStrategy.chart(), st);
        ShoeRun run = new ShoeRun(77, 0.05, 0.02, StateRun.CUT_80, StateRun.montreal(), BasicStrategy.chart());
        List<CountKeys.Count> counts = Arrays.asList(PlayerTreeOrderTest.named("Hi-Lo"),
                PlayerTreeOrderTest.named("KO (Knock-Out)"), PlayerTreeOrderTest.named("Omega II").sideCounted());
        int below = 0;
        for (CountKeys.Count c : counts) {
            int[] got = all.minKey(c, st);
            int s = 0;
            for (int shoe = 0; shoe < shoes; shoe++) {
                int lowest = Integer.MAX_VALUE;
                for (ShoeRun.State x : run.deal(shoe)) {
                    int key = c.betKey(x.dealt, x.depth);
                    lowest = Math.min(lowest, key);
                    if (x.valued) {
                        assertEquals(lowest, got[s], c.name + " state " + s);
                        below += lowest < key ? 1 : 0;
                        s++;
                    }
                }
            }
            assertEquals(st.size, s);
        }
        assertTrue(below > st.size / 2, "the lowest key so far is often below the state's own: " + below);
    }

    /**
     * On real shoes, an X plan's own rule is played as the naive walk plays it: a state counts
     * while every round start of its shoe so far is at or above the key where the fold's fitted
     * advantage reaches the exit, and bets its own key's g then. The scorer's figure is that walk's
     * within 1e-12, and its share is below the B plan's at the same threshold.
     */
    @Test
    public void anExitPlanLeavesAtTheFirstRoundStartBelowItsThreshold(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        int shoes = 40;
        java.nio.file.Path file = ComparisonReportTest.syntheticRun(dir, 78, 78, shoes);
        ScoringStates st = ComparisonReportTest.prepare(file);
        AllStates all = AllStates.replay(StateStore.header(file), shoes, StateRun.montreal(), BasicStrategy.chart(), st);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(shoes, 2, 1), 1);
        sc.wide = SystemScorer.WIDE_PLANS;
        sc.roundStarts = all.roundStarts(st);
        CountKeys.Count hiLo = PlayerTreeOrderTest.named("Hi-Lo");
        SystemScorer.Result r = sc.score(hiLo, null);
        SystemScorer.Entry e = r.entries.get(0);
        int[] minKey = all.minKey(hiLo, st);
        int p = 2;
        SystemScorer.Pen pen = sc.pen(p);
        SystemScorer.BetKeys bk = sc.betKeys(hiLo, pen);
        double[] sums = new double[SystemScorer.WIDE_METRICS * SystemScorer.WIDE_PLANS.length + 1];
        SystemScorer.Held h = sc.hold(hiLo, p);
        int[] local = new int[pen.states.length];
        for (int j = 0; j < local.length; j++) {
            local[j] = minKey[pen.states[j]];
        }
        boolean left = false;
        for (int f = 0; f < 2; f++) {
            double[] ins = SystemScorer.insuranceValues(h.ip, h.plays[f].insuranceGroup, SystemScorer.insuranceSums(h.ip,
                    h.plays[f].insuranceGroup, h.plays[f].groups.count(), pen.train[f], pen.weight));
            double[] rv = new double[local.length];
            for (int j = 0; j < rv.length; j++) {
                rv[j] = h.plays[f].base[j] + ins[j];
            }
            naiveFold(SystemScorer.WIDE_PLANS, bk.value, bk.index, pen.train[f], pen.test[f], pen.weight, rv, pen.chart,
                    local, sums, null);
            for (int j : pen.test[f]) {
                left |= local[j] < bk.value[bk.index[j]];
            }
        }
        assertTrue(left, "some state's shoe fell below its own key earlier");
        for (int i = 0; i < SystemScorer.WIDE_PLANS.length; i++) {
            for (int m = SystemScorer.W_OWN; m < SystemScorer.WIDE_METRICS; m++) {
                close(sums[SystemScorer.wideAt(i, m)] / sums[sums.length - 1], e.wide[p][SystemScorer.wideAt(i, m)],
                        SystemScorer.WIDE_PLANS[i] + " " + SystemScorer.WIDE_NAMES[m]);
            }
        }
        for (String[] pair : new String[][]{{"X0,8", "B0,8"}, {"X-0.5,16", "B-0.5,16"}}) {
            assertTrue(e.wide(p, pair[0], SystemScorer.W_OWN_SHARE) <= 1, pair[0]);
            assertTrue(e.wide(p, pair[0], SystemScorer.W_OWN_SHARE) <= e.wide(p, pair[1], SystemScorer.W_OWN_SHARE),
                    pair[0] + " sits out at least what " + pair[1] + " does");
        }
    }

    /** A count scored a penetration at a time and merged holds the wide figures of one scored whole, to the bit. */
    @Test
    public void partsMergeWithTheirWideFigures() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        SystemScorer sc = scorer(fx, SystemScorer.WIDE_PLANS, 3);
        CountKeys.Count c = PlayerTreeOrderTest.named("Omega II");
        SystemScorer.Result whole = sc.score(c, CountKeys.bettingVariant(c), null, null, 1);
        List<SystemScorer.Result> parts = new ArrayList<>();
        parts.add(sc.score(c, CountKeys.bettingVariant(c), null, new int[]{2}, 1));
        parts.add(sc.score(c, CountKeys.bettingVariant(c), null, new int[]{0, 1}, 1));
        SystemScorer.Result merged = SystemScorer.merge(parts);
        for (int e = 0; e < whole.entries.size(); e++) {
            sameWide(whole.entries.get(e), merged.entries.get(e));
        }
    }
}
