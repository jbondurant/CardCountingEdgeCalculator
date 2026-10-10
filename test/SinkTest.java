import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scorer's two sinks (PLAN_COMPUTE.md 5.3) only read: every figure of a Result is the same
 * to the bit with them on and off, even when the sinks write over every array they are handed;
 * the state sink is handed every test state once, from the point estimate alone; and the
 * resample sink's sums are, to the bit, the sums each resample's figures were made from, under
 * the play refitted on that resample.
 */
public class SinkTest {

    private static final int SHOES = 60;

    static List<CountKeys.Count> counts() {
        return Arrays.asList(CountKeys.nullCount(), PlayerTreeOrderTest.named("Hi-Lo"),
                PlayerTreeOrderTest.named("Omega II"), PlayerTreeOrderTest.named("Red Seven (Red 7)"),
                PlayerTreeOrderTest.named("KO (Knock-Out)"));
    }

    /** A state sink that writes NaN over every array it is handed, after counting the states. */
    static final class Vandal implements SystemScorer.StateSink, SystemScorer.ResampleSink {
        int states;
        int calls;
        int sums;

        @Override
        public void states(int p, SystemScorer.Entry entry, int f, int[] state, double[] weight, double[] play,
                           double[] insurance, int[] key) {
            states += state.length;
            calls++;
            Arrays.fill(state, -1);
            Arrays.fill(weight, Double.NaN);
            Arrays.fill(play, Double.NaN);
            Arrays.fill(insurance, Double.NaN);
            Arrays.fill(key, Integer.MIN_VALUE);
        }

        @Override
        public synchronized void sums(int p, int r, SystemScorer.Entry entry, int f, int[] key, double[] weight,
                                      double[] value, double[] insurance) {
            sums++;
            Arrays.fill(key, Integer.MIN_VALUE);
            Arrays.fill(weight, Double.NaN);
            Arrays.fill(value, Double.NaN);
            Arrays.fill(insurance, Double.NaN);
        }
    }

    private static void same(double[] a, double[] b, String what) {
        assertEquals(a == null, b == null, what);
        if (a != null) {
            assertEquals(a.length, b.length, what);
            for (int k = 0; k < a.length; k++) {
                assertEquals(Double.doubleToLongBits(a[k]), Double.doubleToLongBits(b[k]), what + " at " + k);
            }
        }
    }

    private static void same(double[][] a, double[][] b, String what) {
        assertEquals(a == null, b == null, what);
        if (a != null) {
            assertEquals(a.length, b.length, what);
            for (int k = 0; k < a.length; k++) {
                same(a[k], b[k], what + " [" + k + "]");
            }
        }
    }

    /** Every figure of two Results the same to the bit: CeilingScoringTest's, and the full-play and 1-8 half-fit ones. */
    static void sameEverything(SystemScorer.Result a, SystemScorer.Result b) {
        CeilingScoringTest.sameResult(a, b);
        String n = a.count.name;
        for (int e = 0; e < a.entries.size(); e++) {
            same(a.entries.get(e).halfFitSame, b.entries.get(e).halfFitSame, n + " half fit 1-8");
            same(a.entries.get(e).halfFitSameBoot, b.entries.get(e).halfFitSameBoot, n + " half fit 1-8 resamples");
        }
        assertEquals(a.fullEntries.size(), b.fullEntries.size(), n);
        for (int e = 0; e < a.fullEntries.size(); e++) {
            SystemScorer.Entry x = a.fullEntries.get(e);
            SystemScorer.Entry y = b.fullEntries.get(e);
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                same(x.estimate[p], y.estimate[p], x.name + " estimate " + p);
                same(x.boot[p], y.boot[p], x.name + " resamples " + p);
                same(x.shoeSums[p], y.shoeSums[p], x.name + " shoe sums " + p);
                same(x.keys[p], y.keys[p], x.name + " keys " + p);
            }
        }
        same(a.fullHalfFitFlat, b.fullHalfFitFlat, n + " full half fit");
        same(a.fullHalfFitBoot, b.fullHalfFitBoot, n + " full half fit resamples");
    }

    /**
     * Each count, its betting variant, betting only and the rows bet on their own value, scored
     * with both sinks off and then on, the sinks writing NaN over all they are handed: every
     * estimate, resample, per-shoe sum, key table, learning-curve figure and decision resolution
     * is the same to the bit. The state sink is handed each penetration's states once per entry,
     * and the resample sink one call per entry, fold and resample.
     */
    @Test
    public void everyFigureIsTheSameWithTheSinksOnAndOff() {
        List<StateStore.Record> records = SystemScorerTest.randomStates(new Random(61), SHOES);
        ScoringStates st = SystemScorerTest.prepare(records, SHOES);
        int resamples = 6;
        for (CountKeys.Count c : counts()) {
            SystemScorer off = new SystemScorer(st, new Bootstrap(SHOES, resamples, 5), 2);
            SystemScorer on = new SystemScorer(st, new Bootstrap(SHOES, resamples, 5), 2);
            off.ownValueRows = true;
            on.ownValueRows = true;
            Vandal v = new Vandal();
            on.stateSink = v;
            on.resampleSink = v;
            CountKeys.Count variant = CountKeys.bettingVariant(c);
            SystemScorer.Result a = off.score(c, variant);
            SystemScorer.Result b = on.score(c, variant);
            sameEverything(a, b);
            int entries = a.entries.size();
            int valued = 0;
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                valued += on.pen(p).states.length;
            }
            assertEquals(entries * valued, v.states, c.name);
            assertEquals(entries * 2 * SystemScorer.CUTS.length, v.calls, c.name);
            assertEquals(entries * 2 * SystemScorer.CUTS.length * resamples, v.sums, c.name);
        }
    }

    /** What the sinks were handed, by entry name and penetration, kept as handed. */
    static final class Recorder implements SystemScorer.StateSink, SystemScorer.ResampleSink {
        final java.util.Map<String, List<Object[]>> states = new java.util.HashMap<>();
        final java.util.Map<String, Object[]> sums = new java.util.HashMap<>();

        @Override
        public synchronized void states(int p, SystemScorer.Entry entry, int f, int[] state, double[] weight,
                                        double[] play, double[] insurance, int[] key) {
            states.computeIfAbsent(entry.name + "@" + p, x -> new ArrayList<>())
                    .add(new Object[]{f, state, weight, play, insurance, key, entry});
        }

        @Override
        public synchronized void sums(int p, int r, SystemScorer.Entry entry, int f, int[] key, double[] weight,
                                      double[] value, double[] insurance) {
            assertNull(sums.put(entry.name + "@" + p + "@" + r + "@" + f, new Object[]{key, weight, value, insurance}),
                    "handed twice");
        }
    }

    /**
     * The resample sink's sums are the ones each resample's figures were made from: added up
     * key by key and fold by fold, in the order the scorer adds them, they give the resample's
     * flat EV to the bit, and its insurance figure to rounding.
     */
    @Test
    public void theResampleSinkHandsTheSumsEachResampleWasScoredWith() {
        List<StateStore.Record> records = SystemScorerTest.randomStates(new Random(67), SHOES);
        ScoringStates st = SystemScorerTest.prepare(records, SHOES);
        int resamples = 5;
        SystemScorer sc = new SystemScorer(st, new Bootstrap(SHOES, resamples, 7), 2);
        Recorder rec = new Recorder();
        sc.resampleSink = rec;
        for (CountKeys.Count c : counts()) {
            SystemScorer.Result res = sc.score(c, CountKeys.bettingVariant(c));
            for (SystemScorer.Entry e : res.entries) {
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    for (int r = 0; r < resamples; r++) {
                        double weight = 0;
                        double flat = 0;
                        double ins = 0;
                        for (int f = 0; f < 2; f++) {
                            Object[] s = rec.sums.get(e.name + "@" + p + "@" + r + "@" + f);
                            double[] w = (double[]) s[1];
                            double[] v = (double[]) s[2];
                            double[] i = (double[]) s[3];
                            double fw = 0;
                            double fv = 0;
                            for (int k = 0; k < w.length; k++) {
                                fw += w[k];
                                fv += v[k];
                                ins += i[k];
                            }
                            weight += fw;
                            flat += fv;
                        }
                        String what = e.name + " pen " + p + " resample " + r;
                        assertEquals(Double.doubleToLongBits(e.boot[p][SystemScorer.FLAT][r]),
                                Double.doubleToLongBits(flat / weight), what);
                        assertEquals(e.boot[p][SystemScorer.INSURANCE][r], ins / weight, 1e-12, what);
                    }
                }
            }
        }
    }

    /**
     * A resample's sums are made under the play refitted on that resample: they are, key by key,
     * the state sink's sums on the run the resample stands for (every drawn shoe dealt again as
     * a shoe of its own, SystemScorerTest.resampled), whose own play is fitted on its own shoes.
     */
    @Test
    public void aResamplesSumsAreThoseOfTheRunItStandsFor() {
        List<StateStore.Record> records = SystemScorerTest.randomStates(new Random(71), SHOES);
        ScoringStates st = SystemScorerTest.prepare(records, SHOES);
        Bootstrap boot = new Bootstrap(SHOES, 3, 13);
        double mPlay = 1;
        SystemScorer sc = new SystemScorer(st, boot, mPlay);
        Recorder rec = new Recorder();
        sc.resampleSink = rec;
        CountKeys.Count omega = PlayerTreeOrderTest.named("Omega II");
        SystemScorer.Result res = sc.score(omega, omega.sideCounted());
        for (int r = 0; r < boot.resamples; r++) {
            ScoringStates copied = SystemScorerTest.prepare(SystemScorerTest.resampled(records, boot, r, SHOES), SHOES);
            SystemScorer one = new SystemScorer(copied, new Bootstrap(SHOES, 1, 1), mPlay);
            Recorder run = new Recorder();
            one.stateSink = run;
            one.score(omega, omega.sideCounted());
            for (SystemScorer.Entry e : res.entries) {
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    java.util.TreeMap<Integer, double[]> want = new java.util.TreeMap<>();
                    for (Object[] h : run.states.get(e.name + "@" + p)) {
                        double[] w = (double[]) h[2];
                        double[] play = (double[]) h[3];
                        double[] ins = (double[]) h[4];
                        int[] key = (int[]) h[5];
                        for (int t = 0; t < key.length; t++) {
                            double[] a = want.computeIfAbsent(key[t], x -> new double[3]);
                            a[0] += w[t];
                            a[1] += w[t] * (play[t] + ins[t]);
                            a[2] += w[t] * ins[t];
                        }
                    }
                    java.util.TreeMap<Integer, double[]> got = new java.util.TreeMap<>();
                    for (int f = 0; f < 2; f++) {
                        Object[] s = rec.sums.get(e.name + "@" + p + "@" + r + "@" + f);
                        int[] key = (int[]) s[0];
                        for (int k = 0; k < key.length; k++) {
                            double[] a = got.computeIfAbsent(key[k], x -> new double[3]);
                            for (int c = 0; c < 3; c++) {
                                a[c] += ((double[]) s[1 + c])[k];
                            }
                        }
                    }
                    got.values().removeIf(a -> a[0] == 0);
                    assertEquals(want.keySet(), got.keySet(), e.name + " pen " + p + " resample " + r);
                    for (int k : want.keySet()) {
                        for (int c = 0; c < 3; c++) {
                            assertEquals(want.get(k)[c], got.get(k)[c], 1e-12 * Math.max(1, Math.abs(want.get(k)[c])),
                                    e.name + " pen " + p + " resample " + r + " key " + k + " sum " + c);
                        }
                    }
                }
            }
        }
    }

    /**
     * The state sink is handed each state of a penetration once per entry, in the fold its shoe
     * is in, with its weight 1/q and the entry's bet key; a row betting only is valued at the
     * chart, and a betting variant at its base's play and insurance, only its keys its own.
     */
    @Test
    public void theStateSinkSeesEachStateOnceAsItsEntryValuesIt() {
        List<StateStore.Record> records = SystemScorerTest.randomStates(new Random(73), SHOES);
        ScoringStates st = SystemScorerTest.prepare(records, SHOES);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(SHOES, 2, 3), 2);
        Recorder rec = new Recorder();
        sc.stateSink = rec;
        CountKeys.Count omega = PlayerTreeOrderTest.named("Omega II");
        SystemScorer.Result res = sc.score(omega, omega.sideCounted());
        boolean keysDiffer = false;
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            double[][] base = null;
            for (SystemScorer.Entry e : res.entries) {
                double[][] seen = new double[st.size][];
                for (Object[] h : rec.states.get(e.name + "@" + p)) {
                    int f = (Integer) h[0];
                    int[] state = (int[]) h[1];
                    for (int t = 0; t < state.length; t++) {
                        int s = state[t];
                        assertNull(seen[s], "handed twice");
                        assertEquals(f, st.fold(s));
                        assertEquals(st.weight[s], ((double[]) h[2])[t], 0);
                        assertEquals(e.count.betKey(st.dealt[s], st.depth[s]), ((int[]) h[5])[t]);
                        seen[s] = new double[]{((double[]) h[3])[t], ((double[]) h[4])[t], ((int[]) h[5])[t]};
                        if (e.bettingOnly) {
                            assertEquals(st.chart[s], seen[s][0], 0);
                        }
                    }
                }
                for (int s = 0; s < st.size; s++) {
                    assertEquals(st.depth[s] < SystemScorer.CUTS[p], seen[s] != null);
                }
                if (base == null) {
                    base = seen;
                } else if (!e.bettingOnly) {
                    for (int s = 0; s < st.size; s++) {
                        if (seen[s] != null) {
                            assertEquals(base[s][0], seen[s][0], 0);
                            assertEquals(base[s][1], seen[s][1], 0);
                            keysDiffer |= base[s][2] != seen[s][2];
                        }
                    }
                }
            }
        }
        assertTrue(keysDiffer, "the side count moves some bet key");
    }
}
