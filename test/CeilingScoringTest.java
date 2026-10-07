import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scorer's parts the ceiling count adds (COUNT_DESIGN.md, sections 1, 2 and 4): every
 * published count scores as c06c061 scored it, to the bit, and a Result c06c061 wrote reads with
 * these classes; one evaluation is score's estimate to the bit, and a betting vector scored on a
 * held play is its variant's entry; the half fit's 1-8 figure, the refit check, the rows bet on
 * their own value and the bound rows hold to what they must be.
 */
public class CeilingScoringTest {

    private static final int RESAMPLES = 7;

    /** The doubles of two arrays are the same to the bit. */
    private static void same(double[] a, double[] b, String what) {
        assertEquals(a == null, b == null, what);
        if (a == null) {
            return;
        }
        assertEquals(a.length, b.length, what);
        for (int k = 0; k < a.length; k++) {
            assertEquals(Double.doubleToLongBits(a[k]), Double.doubleToLongBits(b[k]), what + " at " + k);
        }
    }

    private static void same(double[][] a, double[][] b, String what) {
        assertEquals(a == null, b == null, what);
        if (a == null) {
            return;
        }
        assertEquals(a.length, b.length, what);
        for (int k = 0; k < a.length; k++) {
            same(a[k], b[k], what + " [" + k + "]");
        }
    }

    /** Two Results' figures, entry by entry, the same to the bit: what c06c061's results.ser holds. */
    static void sameResult(SystemScorer.Result a, SystemScorer.Result b) {
        String n = a.count.name;
        assertEquals(n, b.count.name);
        assertEquals(a.entries.size(), b.entries.size(), n);
        for (int e = 0; e < a.entries.size(); e++) {
            SystemScorer.Entry x = a.entries.get(e);
            SystemScorer.Entry y = b.entries.get(e);
            assertEquals(x.name, y.name);
            assertEquals(x.bettingOnly, y.bettingOnly);
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                same(x.estimate[p], y.estimate[p], x.name + " estimate " + p);
                same(x.boot[p], y.boot[p], x.name + " resamples " + p);
                same(x.shoeSums[p], y.shoeSums[p], x.name + " shoe sums " + p);
                same(x.keys[p], y.keys[p], x.name + " keys " + p);
            }
        }
        same(a.halfFitFlat, b.halfFitFlat, n + " half fit");
        same(a.halfFitBoot, b.halfFitBoot, n + " half fit resamples");
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            SystemScorer.Resolution x = a.resolution[p];
            SystemScorer.Resolution y = b.resolution[p];
            assertEquals(x.decisions, y.decisions);
            assertEquals(x.unsettled, y.unsettled);
            assertEquals(Double.doubleToLongBits(x.bound), Double.doubleToLongBits(y.bound), n);
            assertEquals(x.insuranceDecisions, y.insuranceDecisions);
            assertEquals(x.insuranceUnsettled, y.insuranceUnsettled);
            assertEquals(Double.doubleToLongBits(x.insuranceBound), Double.doubleToLongBits(y.insuranceBound), n);
            assertEquals(x.closest.size(), y.closest.size());
            for (int f = 0; f < 2; f++) {
                assertArrayEquals(a.groups[p][f].upper, b.groups[p][f].upper, n);
                assertArrayEquals(a.groups[p][f].lo, b.groups[p][f].lo, n);
            }
        }
    }

    private static Object oldRecords(ClassLoader old, List<StateStore.Record> records) throws Exception {
        List<Object> out = new ArrayList<>();
        for (StateStore.Record r : records) {
            out.add(C06c061.make(old, "StateStore$Record", new Class<?>[]{int.class, int.class, int.class, double.class,
                    int[].class, double[].class}, r.shoe, r.round, r.depth, r.q, r.dealt.clone(), r.values.clone()));
        }
        return out;
    }

    private static Object read(byte[] bytes) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return in.readObject();
        }
    }

    private static byte[] write(Object o) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(o);
        }
        return bytes.toByteArray();
    }

    // ------------------------------------------------------------ the published rows unchanged

    /**
     * Every catalog count and side-count variant, scored with the bootstrap on FullIndexFitTest's
     * states by c06c061's classes and by these, gives the same figures to the bit: every entry's
     * estimates, resamples, per-shoe sums and per-key tables, the learning curve and the decision
     * resolution. The Result c06c061's classes wrote, read with these, is that Result: the classes
     * kept their serialVersionUIDs, and what they gained reads as nothing.
     */
    @Test
    public void everyPublishedCountScoresAsC06c061ScoredIt() throws Exception {
        ClassLoader old = C06c061.loader();
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        int shoes = fx.st.shoes;
        Object rules = C06c061.call(old.loadClass("StateRun"), "montreal", new Class<?>[0]);
        Object oldSt = C06c061.make(old, "ScoringStates", new Class<?>[]{List.class, int.class,
                old.loadClass("RoundRules")}, oldRecords(old, fx.records), shoes, rules);
        Object oldBoot = C06c061.make(old, "Bootstrap", new Class<?>[]{int.class, int.class, long.class}, shoes, RESAMPLES,
                1L);
        Object oldScorer = C06c061.make(old, "SystemScorer", new Class<?>[]{old.loadClass("ScoringStates"),
                old.loadClass("Bootstrap"), double.class}, oldSt, oldBoot, 1.0);
        Class<?> ck = old.loadClass("CountKeys");
        Class<?> count = old.loadClass("CountKeys$Count");
        @SuppressWarnings("unchecked")
        List<Object> was = (List<Object>) C06c061.call(ck, "catalog", new Class<?>[0]);
        List<CountKeys.Count> now = new ArrayList<>(CountKeys.catalog());
        was = new ArrayList<>(was);
        was.add(0, C06c061.call(ck, "nullCount", new Class<?>[0]));
        now.add(0, CountKeys.nullCount());
        SystemScorer sc = new SystemScorer(fx.st, new Bootstrap(shoes, RESAMPLES, 1), 1);
        for (int k = 0; k < now.size(); k++) {
            Object side = C06c061.call(ck, "sideCountVariant", new Class<?>[]{count}, was.get(k));
            Object oldResult = C06c061.call(oldScorer, "score", new Class<?>[]{count, count}, was.get(k), side);
            SystemScorer.Result written = (SystemScorer.Result) read(write(oldResult));
            SystemScorer.Result scored = sc.score(now.get(k), CountKeys.bettingVariant(now.get(k)));
            sameResult(written, scored);
            for (SystemScorer.Entry e : written.entries) {
                assertFalse(e.ownValue);
                assertNull(e.halfFitSame, "a Result c06c061 wrote has no 1-8 half fit");
                assertEquals(1, e.count.divisor());
                assertNull(e.count.betTag);
            }
            // Written again by these classes, it reads back whole, new figures and all.
            sameResult(scored, (SystemScorer.Result) read(write(scored)));
        }
    }

    // ------------------------------------------------------------------- the evaluation

    private static CountKeys.Count f2() {
        int k = CountKeys.K;
        int[] play = {0, -k, k, k, 2 * k, 2 * k, k, k, 0, -k, -2 * k};
        int[] bet = {0, -4 * k, k, 2 * k, 2 * k, 3 * k, 2 * k, k, 0, -k, -2 * k};
        return CountKeys.whole("F2", play, bet, k / 3, true);
    }

    /**
     * One evaluation is score's estimate to the bit, for a plain count, a kind-aware one, a
     * side-counted one and a count with a betting vector at a finer grain; and a betting vector
     * scored on the held play is its variant's entry, to the bit.
     */
    @Test
    public void oneEvaluationIsScoresEstimateToTheBit() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        SystemScorer sc = new SystemScorer(fx.st, new Bootstrap(fx.st.shoes, 3, 1), 1);
        List<CountKeys.Count> counts = Arrays.asList(FullIndexFitTest.HI_LO, FullIndexFitTest.RED_SEVEN,
                PlayerTreeOrderTest.named("Omega II"), f2(), CountKeys.atGrain(PlayerTreeOrderTest.named("Zen Count"), 700));
        for (CountKeys.Count c : counts) {
            CountKeys.Count v = CountKeys.bettingVariant(c);
            SystemScorer.Result r = sc.score(c, v);
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                double[][] est = sc.estimate(c, v, p);
                assertEquals(r.entries.size(), est.length, c.name);
                for (int e = 0; e < est.length; e++) {
                    same(r.entries.get(e).estimate[p], est[e], c.name + " entry " + e);
                }
                if (v != null) {
                    SystemScorer.Held h = sc.hold(c, p);
                    same(r.entries.get(2).estimate[p], sc.betOn(h, v), c.name + " betting on the held play");
                    same(r.entries.get(0).estimate[p], sc.betOn(h, c), c.name + " itself on the held play");
                }
            }
        }
    }

    // ------------------------------------------------------------- the half fit at 1-8

    /**
     * On states whose training shoes all lie in the learning curve's half, the half fit is the
     * whole fit: its 1-8 figure is the estimate's, to the bit, and so on every resample.
     * Elsewhere it is a figure of its own, and none for rows betting only.
     */
    @Test
    public void theHalfFitAtOneToEightIsTheFitWhenTheHalfIsTheWhole() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        List<StateStore.Record> inHalf = new ArrayList<>();
        for (StateStore.Record r : fx.records) {
            if (SystemScorer.inHalf(r.shoe)) {
                inHalf.add(r);
            }
        }
        ScoringStates st = new ScoringStates(inHalf, fx.st.shoes, StateRun.montreal());
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, 5, 1), 1);
        int m = SystemScorer.same(SystemScorer.SPREADS);
        for (CountKeys.Count c : Arrays.asList(FullIndexFitTest.HI_LO, f2())) {
            SystemScorer.Result r = sc.score(c, CountKeys.bettingVariant(c));
            for (SystemScorer.Entry e : r.entries) {
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    if (e.bettingOnly) {
                        assertTrue(Double.isNaN(e.halfFitSame[p]));
                        continue;
                    }
                    assertEquals(Double.doubleToLongBits(e.estimate[p][m]), Double.doubleToLongBits(e.halfFitSame[p]),
                            e.name);
                    same(e.boot[p][m], e.halfFitSameBoot[p], e.name + " resamples");
                    // The flat figure is summed in another order, by state rather than by key.
                    assertEquals(e.estimate[p][SystemScorer.FLAT], r.halfFitFlat[p], 1e-15);
                }
            }
        }
        SystemScorer whole = new SystemScorer(fx.st, new Bootstrap(fx.st.shoes, 3, 1), 1);
        SystemScorer.Entry hiLo = whole.score(FullIndexFitTest.HI_LO, null).entries.get(0);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            assertFalse(Double.isNaN(hiLo.halfFitSame[p]));
            assertEquals(3, hiLo.halfFitSameBoot[p].length);
        }
    }

    /**
     * On states whose training folds hold shoes in the learning curve's half and out of it, the
     * half fit's 1-8 figure is worked out here from its parts, each on the half: the half fits'
     * play, insurance fitted on the half, the bets' advantage fitted on the half, and the schedule
     * handed out over the whole test fold; on FullIndexFitTest's states, and on states made so the
     * half and the rest disagree. There a count of the sevens finds the advantage falling with its
     * key in the half and rising steeply in the rest, so the advantage fitted on the half pools
     * every key and bets them alike, and fitted on the whole training fold it bets them apart: a
     * half fit that fitted its bets on the whole fold would show.
     */
    @Test
    public void theHalfFitAtOneToEightFitsItsBetsOnTheHalf() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        SystemScorer sc = new SystemScorer(fx.st, new Bootstrap(fx.st.shoes, 2, 1), 1);
        for (CountKeys.Count c : Arrays.asList(FullIndexFitTest.HI_LO, f2())) {
            SystemScorer.Result r = sc.score(c, CountKeys.bettingVariant(c));
            for (SystemScorer.Entry e : r.entries) {
                for (int p = 0; p < SystemScorer.CUTS.length && !e.bettingOnly; p++) {
                    assertEquals(halfFitSame(sc, c, e.count, p, true), e.halfFitSame[p], 1e-14, e.name + " at " + p);
                }
            }
        }

        List<StateStore.Record> records = new ArrayList<>();
        int shoes = 64;
        for (int shoe = 0; shoe < shoes; shoe++) {
            int key = (shoe >> 2) % 4;
            double a = SystemScorer.inHalf(shoe) ? 0.03 - 0.01 * key : -0.05 + 0.04 * key;
            records.add(SystemScorerTest.state(shoe, 0, SystemScorerTest.tenPoor(key), 1, (i, m) -> a));
        }
        SystemScorer made = new SystemScorer(SystemScorerTest.prepare(records, shoes), new Bootstrap(shoes, 2, 1), 1e9);
        CountKeys.Count sevens = SystemScorerTest.sevens(1);
        SystemScorer.Entry e = made.score(sevens, null).entries.get(0);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            double onHalf = halfFitSame(made, sevens, sevens, p, true);
            assertEquals(onHalf, e.halfFitSame[p], 1e-14, "the made states at " + p);
            double onWhole = halfFitSame(made, sevens, sevens, p, false);
            assertTrue(Math.abs(onWhole - onHalf) > 1e-3, "the half's bets " + onHalf + " against the whole fold's "
                    + onWhole);
        }
    }

    /**
     * The 1-8 figure of a count's half fit, from its parts: play from the scorer's half fits; then,
     * on the half of each training fold (or the whole fold, when half is false), insurance and the
     * advantage of the bet key of betOn; the schedule handed out on the whole test fold.
     */
    private static double halfFitSame(SystemScorer sc, CountKeys.Count c, CountKeys.Count betOn, int p, boolean half) {
        SystemScorer.Pen pen = sc.pen(p);
        SystemScorer.Parts parts = sc.parts(c, pen);
        SystemScorer.InsuranceParts ip = sc.insuranceParts(parts, pen);
        SystemScorer.FoldPlay[] hp = sc.halfPlays(parts, pen, ip, pen.weight, null, null, null, null, null);
        SystemScorer.BetKeys bk = sc.betKeys(betOn, pen);
        double[] sums = new double[SystemScorer.METRICS + 1];
        double[] rv = new double[pen.states.length];
        for (int f = 0; f < 2; f++) {
            List<Integer> use = new ArrayList<>();
            for (int j : pen.train[f]) {
                if (!half || (SystemScorer.inHalf(sc.st.shoe[pen.states[j]]) && pen.weight[j] != 0)) {
                    use.add(j);
                }
            }
            int[] on = use.stream().mapToInt(Integer::intValue).toArray();
            assertTrue(!half || on.length < pen.train[f].length, "the half is not the whole fold");
            double[] ins = SystemScorer.insuranceValues(ip, hp[f].insuranceGroup, SystemScorer.insuranceSums(ip,
                    hp[f].insuranceGroup, hp[f].groups.count(), on, pen.weight));
            for (int j = 0; j < rv.length; j++) {
                rv[j] = hp[f].base[j] + ins[j];
            }
            SystemScorer.bets(bk, on, pen.test[f], pen.testByChart[f], pen.weight, rv, pen.chart, sums, null);
        }
        return sums[SystemScorer.same(SystemScorer.SPREADS)] / sums[SystemScorer.METRICS];
    }

    // ------------------------------------------------------------------ the refit check

    /**
     * At spread 1 every bet is 1, so plays fitted for the schedule are the flat plays: the refit
     * check gives score's figures to the bit, estimate and resamples, for a count and its
     * betting variant.
     */
    @Test
    public void theRefitAtSpreadOneIsTheFlatFit() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        SystemScorer sc = new SystemScorer(fx.st, new Bootstrap(fx.st.shoes, 4, 1), 1);
        for (CountKeys.Count c : Arrays.asList(FullIndexFitTest.HI_LO, f2())) {
            CountKeys.Count v = CountKeys.bettingVariant(c);
            SystemScorer.Result r = sc.score(c, v);
            List<SystemScorer.Entry> refit = sc.scheduleRefit(c, v, 1, 2);
            assertEquals(v == null ? 1 : 2, refit.size());
            for (int e = 0; e < refit.size(); e++) {
                SystemScorer.Entry a = r.entries.get(2 * e);
                SystemScorer.Entry b = refit.get(e);
                assertSame(a.count, b.count);
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    same(a.estimate[p], b.estimate[p], b.name);
                    same(a.boot[p], b.boot[p], b.name);
                }
            }
        }
    }

    /**
     * Plays fitted for the bets, on states made for it: half the shoes are rich (their chart
     * advantage +5%, so the 1-8 schedule bets them 8) and there doubling gains 0.05 on every deal;
     * in the poor half (advantage -5%, bet 1) doubling loses 0.06. A count that plays one group
     * stands everywhere when fitted flat, since doubling loses on the whole; its variant, betting on
     * the sevens, which tell the halves apart, doubles once its plays are fitted on its bets, and
     * that scores better at 1-8 and worse flat.
     */
    @Test
    public void theRefitFollowsTheBets() {
        List<StateStore.Record> records = new ArrayList<>();
        int shoes = 40;
        for (int shoe = 0; shoe < shoes; shoe++) {
            boolean rich = shoe % 4 < 2;
            double a = rich ? 0.05 : -0.05;
            double d = rich ? 0.05 : -0.06;
            records.add(SystemScorerTest.state(shoe, 0, SystemScorerTest.tenPoor(rich ? 12 : 0), 1,
                    (i, m) -> m == 2 ? a + d : a));
        }
        ScoringStates st = SystemScorerTest.prepare(records, shoes);
        int[] none = new int[ShoeRun.KINDS];
        int[] sevensBet = new int[ShoeRun.KINDS];
        sevensBet[12] = 1;
        sevensBet[13] = 1;
        CountKeys.Count c = new CountKeys.Count("Blind play, sevens bet", Collections.emptyList(), none, 1, false, 0,
                null, "", 1, null, sevensBet);
        CountKeys.Count v = CountKeys.bettingVariant(c);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(shoes, 0, 1), 1e9);
        int p = SystemScorer.CUTS.length - 1;
        double[][] flat = sc.estimate(c, v, p);
        List<SystemScorer.Entry> refit = sc.scheduleRefit(c, v, SystemScorer.SPREADS, 1);
        int m = SystemScorer.same(SystemScorer.SPREADS);
        assertTrue(refit.get(1).estimate[p][m] > flat[2][m] + 0.01, refit.get(1).estimate[p][m] + " against " + flat[2][m]);
        assertTrue(refit.get(1).estimate[p][SystemScorer.FLAT] < flat[2][SystemScorer.FLAT] - 0.001);
        // The play count bets on nothing, every state alike, so its plays stay as fitted flat.
        assertEquals(flat[0][m], refit.get(0).estimate[p][m], 1e-15);
    }

    // ------------------------------------------------------------------- the bound rows

    /**
     * On a tiny state set each bound row is a brute-force sort: within each test fold the states
     * in order of their own value, each handed the average, over its share of the fold's weight,
     * of the reference bets laid out in order of chart advantage, worked out here from the step
     * function directly.
     */
    @Test
    public void eachBoundRowIsABruteForceSort() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        ScoringStates st = fx.st;
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, 0, 1), 1);
        double[] full = new double[st.size];
        for (int s = 0; s < st.size; s++) {
            full[s] = st.perfect[s] + 0.001 * (s % 3);
        }
        List<SystemScorer.Entry> bounds = sc.bounds(full);
        assertEquals(3, bounds.size());
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            SystemScorer.Pen pen = sc.pen(p);
            for (int e = 0; e < 3; e++) {
                double[] v = new double[pen.states.length];
                for (int j = 0; j < v.length; j++) {
                    int s = pen.states[j];
                    v[j] = (e == 0 ? st.perfect[s] : e == 1 ? st.chart[s] : full[s]) + st.perfectInsurance[s];
                }
                for (int spread = 1; spread <= SystemScorer.SPREADS; spread++) {
                    double num = 0;
                    double den = 0;
                    double bets = 0;
                    for (int f = 0; f < 2; f++) {
                        double[] b = bruteBets(pen, f, v, spread);
                        for (int j : pen.test[f]) {
                            num += pen.weight[j] * b[j] * v[j];
                            bets += pen.weight[j] * b[j];
                            den += pen.weight[j];
                        }
                    }
                    assertEquals(num / den, bounds.get(e).estimate[p][SystemScorer.same(spread)], 1e-14, "row " + e);
                    assertEquals(bets / den, bounds.get(e).estimate[p][SystemScorer.sameBet(spread)], 1e-12);
                }
                assertTrue(Double.isNaN(bounds.get(e).estimate[p][SystemScorer.own(8)]));
            }
        }
    }

    /**
     * The bets of fold f's test states handed out in order of v: the reference bets, clamp(chart /
     * 0.25%, 1, S), laid out in order of chart advantage, each over its weight, and each state's
     * share of that line, in order of v, averaged; states of equal v share their average.
     */
    private static double[] bruteBets(SystemScorer.Pen pen, int f, double[] v, int spread) {
        int[] test = pen.test[f];
        Integer[] byChart = new Integer[test.length];
        Integer[] byValue = new Integer[test.length];
        for (int k = 0; k < test.length; k++) {
            byChart[k] = test[k];
            byValue[k] = test[k];
        }
        Arrays.sort(byChart, Comparator.comparingDouble(j -> pen.chart[j]));
        Arrays.sort(byValue, Comparator.comparingDouble(j -> v[j]));
        double[] edge = new double[test.length + 1];
        for (int k = 0; k < test.length; k++) {
            edge[k + 1] = edge[k] + pen.weight[byChart[k]];
        }
        double[] out = new double[v.length];
        double at = 0;
        int k = 0;
        while (k < test.length) {
            int end = k;
            double w = 0;
            while (end < test.length && v[byValue[end]] == v[byValue[k]]) {
                w += pen.weight[byValue[end]];
                end++;
            }
            // The integral of the reference bet over [at, at + w].
            double integral = 0;
            for (int c = 0; c < test.length; c++) {
                double lo = Math.max(at, edge[c]);
                double hi = Math.min(at + w, edge[c + 1]);
                if (hi > lo) {
                    integral += (hi - lo) * SystemScorer.clamp(pen.chart[byChart[c]] / SystemScorer.UNIT, spread);
                }
            }
            for (int x = k; x < end; x++) {
                out[byValue[x]] = integral / w;
            }
            at += w;
            k = end;
        }
        return out;
    }

    /**
     * A count's own-value row is a brute-force sort of its own round values: within each test fold
     * the states in order of their value under the count's play and fitted insurance (not of their
     * chart advantage), each handed its share of the reference bets laid out in order of chart
     * advantage, worked out here from the step function directly (bruteBets), at every spread. On
     * FullIndexFitTest's states, and on states made so the two orders disagree: half the shoes
     * have the better chart advantage, where doubling loses a little, and the other half the worse
     * one, where doubling gains 0.10; a count of the sevens, which tell them apart, doubles there,
     * so its own values put the second half first, and the bets handed out in order of chart
     * advantage would differ.
     */
    @Test
    public void theOwnValueRowIsABruteForceSortOfTheCountsOwnValues() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        SystemScorer standard = new SystemScorer(fx.st, new Bootstrap(fx.st.shoes, 2, 1), 1);
        List<StateStore.Record> records = new ArrayList<>();
        int shoes = 40;
        for (int shoe = 0; shoe < shoes; shoe++) {
            boolean better = shoe % 4 < 2;
            // Chart advantages of about 0.3% to 1%, where the reference bets differ.
            double a = (better ? -0.01 : -0.03) + 0.001 * (shoe % 5);
            double d = better ? -0.01 : 0.10;
            records.add(SystemScorerTest.state(shoe, 0, SystemScorerTest.tenPoor(better ? 0 : 2), 1,
                    (i, m) -> m == 2 ? a + d : a));
        }
        SystemScorer made = new SystemScorer(SystemScorerTest.prepare(records, shoes), new Bootstrap(shoes, 2, 1), 1);
        int disagree = 0;
        for (Object[] run : new Object[][]{{standard, FullIndexFitTest.HI_LO}, {standard, FullIndexFitTest.RED_SEVEN},
                {standard, f2()}, {made, SystemScorerTest.sevens(1)}}) {
            SystemScorer sc = (SystemScorer) run[0];
            CountKeys.Count c = (CountKeys.Count) run[1];
            sc.ownValueRows = true;
            SystemScorer.Result r = sc.score(c, CountKeys.bettingVariant(c));
            SystemScorer.Entry own = r.entries.get(r.entries.size() - 1);
            assertTrue(own.ownValue);
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                SystemScorer.Pen pen = sc.pen(p);
                SystemScorer.Held h = sc.hold(c, p);
                double[][] v = new double[2][pen.states.length];
                for (int f = 0; f < 2; f++) {
                    SystemScorer.FoldPlay play = h.plays[f];
                    double[] ins = SystemScorer.insuranceValues(h.ip, play.insuranceGroup, SystemScorer.insuranceSums(h.ip,
                            play.insuranceGroup, play.groups.count(), pen.train[f], pen.weight));
                    for (int j = 0; j < v[f].length; j++) {
                        v[f][j] = play.base[j] + ins[j];
                    }
                }
                for (int spread = 1; spread <= SystemScorer.SPREADS; spread++) {
                    double num = 0;
                    double den = 0;
                    double byChart = 0;
                    for (int f = 0; f < 2; f++) {
                        double[] b = bruteBets(pen, f, v[f], spread);
                        double[] bc = bruteBets(pen, f, pen.chart, spread);
                        for (int j : pen.test[f]) {
                            num += pen.weight[j] * b[j] * v[f][j];
                            byChart += pen.weight[j] * bc[j] * v[f][j];
                            den += pen.weight[j];
                        }
                    }
                    assertEquals(num / den, own.estimate[p][SystemScorer.same(spread)], 1e-13, c.name + " at " + p
                            + ", 1-" + spread);
                    if (Math.abs(byChart - num) / den > 1e-4) {
                        disagree++;
                    }
                }
            }
        }
        assertTrue(disagree > 0, "on some states the own values' order is not the chart's");
    }

    /**
     * No row exceeds its bound on any resample, flat or on the same schedule at any spread: every
     * count playing its own fit is under the perfect first move's bound, every count betting only
     * under the chart's with perfect insurance, and a count bet on its own value is at least what
     * its bet key makes of the same play, and no more than the bound.
     */
    @Test
    public void noRowExceedsItsBoundOnAnyResample() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        SystemScorer sc = new SystemScorer(fx.st, new Bootstrap(fx.st.shoes, 25, 1), 1);
        sc.ownValueRows = true;
        List<SystemScorer.Entry> bounds = sc.bounds(null);
        List<CountKeys.Count> counts = new ArrayList<>(Arrays.asList(CountKeys.nullCount(), FullIndexFitTest.HI_LO,
                FullIndexFitTest.RED_SEVEN, PlayerTreeOrderTest.named("Omega II"), f2()));
        double tol = 1e-12;
        for (CountKeys.Count c : counts) {
            SystemScorer.Result r = sc.score(c, CountKeys.bettingVariant(c));
            SystemScorer.Entry own = r.entries.get(r.entries.size() - 1);
            assertTrue(own.ownValue);
            assertSame(c, own.count);
            for (SystemScorer.Entry e : r.entries) {
                SystemScorer.Entry bound = bounds.get(e.bettingOnly ? 1 : 0);
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    for (int rr = -1; rr < 25; rr++) {
                        int[] metrics = new int[1 + SystemScorer.SPREADS];
                        metrics[0] = SystemScorer.FLAT;
                        for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                            metrics[s] = SystemScorer.same(s);
                        }
                        for (int m : metrics) {
                            double x = rr < 0 ? e.estimate[p][m] : e.boot[p][m][rr];
                            double b = rr < 0 ? bound.estimate[p][m] : bound.boot[p][m][rr];
                            assertTrue(x <= b + tol, e.name + " " + x + " over its bound " + b + " at metric " + m);
                            if (!e.bettingOnly && !e.ownValue && r.entries.indexOf(e) == 0) {
                                double o = rr < 0 ? own.estimate[p][m] : own.boot[p][m][rr];
                                assertTrue(o >= x - tol, own.name + " " + o + " under " + x);
                                if (m == SystemScorer.FLAT) {
                                    assertEquals(x, o, 1e-15, "the same play, flat");
                                }
                            }
                        }
                    }
                    assertTrue(Double.isNaN(own.estimate[p][SystemScorer.own(8)]));
                }
            }
        }
    }
}
