import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The edge at each count (PLAN_COMPUTE.md 5.3 and 5.4): tables built from what the scorer's sink
 * hands out are its own key tables to the bit; a held resample is the run with its drawn shoes
 * dealt again; the top-q edge on a toy worked by hand; the tail pooling rule; pooling a fine
 * count into whole steps, ties toward zero; each count's place on Hi-Lo's steps and its pivot.
 */
public class KeyEdgesTest {

    private static final int SHOES = 60;

    /** A scored run's states, collected: the scorer, its states and what its sink was handed. */
    static final class Scored {
        final ScoringStates st;
        final SystemScorer sc;
        final KeyEdges.Collector col;
        final List<SystemScorer.Result> results = new ArrayList<>();

        Scored(long seed, int resamples, List<CountKeys.Count> counts) {
            List<StateStore.Record> records = SystemScorerTest.randomStates(new Random(seed), SHOES);
            st = SystemScorerTest.prepare(records, SHOES);
            sc = new SystemScorer(st, new Bootstrap(SHOES, resamples, 5), 2);
            col = new KeyEdges.Collector(st, resamples);
            sc.stateSink = col;
            sc.resampleSink = resamples > 0 ? col : null;
            for (CountKeys.Count c : counts) {
                results.add(sc.score(c, CountKeys.bettingVariant(c)));
            }
        }
    }

    /**
     * The table built from the sink's states, for every entry and penetration, is the scorer's own
     * key table to the bit: the same keys, each with the same share of rounds and the same mean
     * round value (insurance in). So the edge at each count is the figure report-final prints in
     * its "Mean RV" column, and not a figure of its own.
     */
    @Test
    public void tablesFromTheSinkAreTheScorersKeyTablesToTheBit() {
        Scored s = new Scored(83, 0, SinkTest.counts());
        int rows = 0;
        for (SystemScorer.Result res : s.results) {
            for (SystemScorer.Entry e : res.entries) {
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    KeyEdges.Table t = KeyEdges.table(s.col.values(e.name, p));
                    double[][] keys = e.keys[p];
                    assertEquals(keys.length, t.keys(), e.name);
                    for (int k = 0; k < keys.length; k++) {
                        String what = e.name + " pen " + p + " key " + t.sums.key[k];
                        assertEquals(keys[k][0], t.sums.key[k], 0, what);
                        assertEquals(Double.doubleToLongBits(keys[k][1]), Double.doubleToLongBits(t.share(k)), what);
                        assertEquals(Double.doubleToLongBits(keys[k][2]), Double.doubleToLongBits(t.edge(k)), what);
                        rows++;
                    }
                    // Check 2 of 5.4: the shares add to one, and the keys' edges weighted by their
                    // shares, and the top-q edge at 100%, are the entry's flat figure.
                    double shares = 0;
                    double weighted = 0;
                    for (int k = 0; k < t.keys(); k++) {
                        shares += t.share(k);
                        weighted += t.share(k) * t.edge(k);
                    }
                    double flat = e.estimate[p][SystemScorer.FLAT];
                    assertEquals(1, shares, 1e-12, e.name);
                    assertEquals(flat, weighted, 1e-12 * Math.abs(flat), e.name);
                    assertEquals(flat, KeyEdges.topEdge(t.sums, 1.0), 1e-12 * Math.abs(flat), e.name);
                    assertEquals(flat, t.sums.flat(), 1e-12 * Math.abs(flat), e.name);
                }
            }
        }
        assertTrue(rows > 100, "rows compared: " + rows);
    }

    /**
     * A resample with the values held moves only the weights, by the bootstrap's multiplicities:
     * each key's mean on resample r is the mean over the states with every drawn shoe's states
     * written out as many times as it was drawn.
     */
    @Test
    public void aHeldResampleIsTheRunWithItsDrawnShoesWrittenOut() {
        Scored s = new Scored(89, 0, Arrays.asList(PlayerTreeOrderTest.named("Hi-Lo")));
        Bootstrap boot = new Bootstrap(SHOES, 4, 31);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            KeyEdges.Values v = s.col.values("Hi-Lo = Hi-Lo Lite", p);
            int[] keys = KeyEdges.keysOf(v);
            KeyEdges.Sums[] held = KeyEdges.held(v, keys, s.col.facts, boot);
            for (int r = 0; r < boot.resamples; r++) {
                List<Integer> copies = new ArrayList<>();
                for (int t = 0; t < v.size(); t++) {
                    for (int m = 0; m < boot.mult(r, s.st.shoe[v.state[t]]); m++) {
                        copies.add(t);
                    }
                }
                double[] w = new double[keys.length];
                double[] val = new double[keys.length];
                double[] ins = new double[keys.length];
                for (int t : copies) {
                    int k = Arrays.binarySearch(keys, v.key[t]);
                    w[k] += v.weight[t];
                    val[k] += v.weight[t] * v.value[t];
                    ins[k] += v.weight[t] * v.insurance[t];
                }
                for (int k = 0; k < keys.length; k++) {
                    String what = "pen " + p + " resample " + r + " key " + keys[k];
                    assertEquals(w[k], held[r].weight[k], 1e-12 * Math.max(1, w[k]), what);
                    assertEquals(val[k], held[r].value[k], 1e-12 * Math.max(1, Math.abs(val[k])), what);
                    assertEquals(ins[k], held[r].insurance[k], 1e-12 * Math.max(1, Math.abs(ins[k])), what);
                }
            }
        }
    }

    /**
     * Top-q on three keys worked by hand. Keys -1, 0 and +2 hold weights 50, 30 and 20 (shares
     * 50%, 30%, 20%) with means -0.02, 0.00 and +0.03. The top 10% is half of key +2: +0.03. The
     * top 25% is key +2 whole (20, sum 0.6) and 5 of key 0's 30 at 0.00: 0.6 / 25 = +0.024. The
     * top 60% adds key 0 whole and 10 of key -1's 50 at -0.02: (0.6 + 0 - 0.2) / 60 = +0.00667.
     * The top 100% is the flat mean, (0.6 - 1.0) / 100 = -0.004. The band 50-75% from the bottom
     * is the top 50% less the top 25%: (0.6 - (0.6 + 0)) / 25 = 0, all of it key 0.
     */
    @Test
    public void theTopQEdgeOnThreeKeysWorkedByHand() {
        KeyEdges.Sums s = new KeyEdges.Sums(new int[]{-1, 0, 2}, new double[]{50, 30, 20},
                new double[]{50 * -0.02, 0, 20 * 0.03}, new double[3], 100);
        assertEquals(0.03, KeyEdges.topEdge(s, 0.10), 1e-15);
        assertEquals(0.03, KeyEdges.topEdge(s, 0.20), 1e-15);
        assertEquals(0.024, KeyEdges.topEdge(s, 0.25), 1e-15);
        assertEquals(0.4 / 60, KeyEdges.topEdge(s, 0.60), 1e-15);
        assertEquals(-0.004, KeyEdges.topEdge(s, 1.0), 1e-15);
        assertEquals(0, KeyEdges.bandEdge(s, 0.50, 0.75), 1e-15);
        assertEquals(-0.02, KeyEdges.bandEdge(s, 0, 0.50), 1e-15);
        assertEquals(0.03, KeyEdges.bandEdge(s, 0.995, 1), 1e-15);
        assertEquals(0.20, KeyEdges.positiveKeys(s), 1e-15);
        // The oracle's walk over states ranked by their own value gives the same on the same three
        // blocks, each written out as states of one value.
        double[] value = {0.03, 0.03, 0, 0, 0, -0.02, -0.02, -0.02, -0.02, -0.02};
        double[] w = {10, 10, 10, 10, 10, 10, 10, 10, 10, 10};
        double[] f = KeyEdges.rankedFigures(value, w);
        double[] g = KeyEdges.shareFigures(s);
        for (int k = 0; k < f.length; k++) {
            assertEquals(g[k], f[k], 1e-15, "figure " + k);
        }
    }

    /** A table of given per-key weights, each key's states of equal weight so that its n_eff is its count. */
    private static KeyEdges.Table toy(int[] perKey) {
        int n = perKey.length;
        int[] key = new int[n];
        double[] w = new double[n];
        double[] sq = new double[n];
        int[] valued = new int[n];
        for (int k = 0; k < n; k++) {
            key[k] = k - n / 2;
            w[k] = perKey[k];
            sq[k] = perKey[k];
            valued[k] = perKey[k];
        }
        return new KeyEdges.Table(new KeyEdges.Sums(key, w, new double[n], new double[n], Arrays.stream(w).sum()),
                valued, sq, new double[n], new double[n]);
    }

    private static String bins(KeyEdges.Bins b) {
        StringBuilder out = new StringBuilder();
        for (int k = 0; k < b.size(); k++) {
            out.append(b.kind[k]).append(' ').append(b.from[k]).append('-').append(b.to[k]).append(';');
        }
        return out.toString();
    }

    /**
     * Tails pool by n_eff alone. Keys of 3, 40, 150, 900, 300, 120, 60, 50 and 5 states: the
     * first two pool into "at most" (43, short of 100, so it takes in the 150 too: 193); the
     * last three hold 115 and pool into "at least"; the keys between stand. A thin key between
     * two that stand keeps its own bin, flagged. With 99 in the lowest key, the low tail takes in the
     * 500 beside it, and the thin key after that keeps its own bin. With 99 states in all,
     * everything is one bin.
     */
    @Test
    public void tailsPoolUntilTheyHoldOneHundredEffectiveStates() {
        assertEquals("AT_MOST 0-3;ALONE 3-4;ALONE 4-5;ALONE 5-6;AT_LEAST 6-9;",
                bins(KeyEdges.bins(toy(new int[]{3, 40, 150, 900, 300, 120, 60, 50, 5}))));
        assertEquals("ALONE 0-1;ALONE 1-2;THIN 2-3;ALONE 3-4;ALONE 4-5;",
                bins(KeyEdges.bins(toy(new int[]{100, 500, 20, 400, 120}))));
        assertEquals("AT_MOST 0-2;THIN 2-3;ALONE 3-4;ALONE 4-5;",
                bins(KeyEdges.bins(toy(new int[]{99, 500, 20, 400, 120}))));
        assertEquals("ALONE 0-1;ALONE 1-2;", bins(KeyEdges.bins(toy(new int[]{100, 100}))));
        assertEquals("ALL 0-3;", bins(KeyEdges.bins(toy(new int[]{30, 39, 30}))));
        assertEquals("ALL 0-3;", bins(KeyEdges.bins(toy(new int[]{60, 100, 60}))));
    }

    /**
     * A count at grain 1/20 pools into whole steps with ties toward zero: step 0 holds keys -10
     * to +10, 21 of them, and every other step 20 (step +1 holds +11 to +30, since +30 is +1.5 and
     * goes toward zero). Quarter steps hold 5, and 0 holds 5 too (-2 to +2, since 2.5/20 = 0.125
     * goes to 0).
     */
    @Test
    public void aFineCountPoolsIntoWholeStepsTiesTowardZero() {
        int k = CountKeys.K;
        CountKeys.Count fine = CountKeys.whole("Fine", new int[]{0, -k, k, k, k, k, k, 0, 0, 0, -k}, null, k / 20, true);
        KeyEdges.Axis a = new KeyEdges.Axis(fine);
        assertTrue(a.rebinned);
        java.util.Map<Integer, Integer> held = new java.util.TreeMap<>();
        for (int key = -200; key <= 200; key++) {
            held.merge(a.whole(key), 1, Integer::sum);
        }
        assertEquals(21, held.get(0));
        for (int step = -9; step <= 9; step++) {
            if (step != 0) {
                assertEquals(20, held.get(step), "step " + step);
            }
        }
        assertEquals(0, a.whole(10));
        assertEquals(1, a.whole(11));
        assertEquals(1, a.whole(30));
        assertEquals(2, a.whole(31));
        assertEquals(-1, a.whole(-30));
        assertEquals(0, a.quarter(2));
        assertEquals(1, a.quarter(3));
        assertEquals(1, a.quarter(7));
        assertEquals(2, a.quarter(8));
    }

    /**
     * Each count's place on Hi-Lo's steps, by arithmetic from its tags: Hi-Lo 1, Hi-Opt II
     * sqrt(10/28), Zen sqrt(10/32), Wong Halves' book keys in halves at sqrt(10/13 / 1.4038...);
     * the pivots of KO (32) and Red Seven (16); no place for a running count, no pivot for a
     * true one.
     */
    @Test
    public void eachCountsPlaceOnHiLosStepsAndItsPivot() {
        KeyEdges.Axis hiLo = new KeyEdges.Axis(PlayerTreeOrderTest.named("Hi-Lo"));
        assertEquals(1, hiLo.hiLo, 1e-15);
        assertEquals(10 / 13.0, CountKeys.betSpread(PlayerTreeOrderTest.named("Hi-Lo")), 1e-15);
        assertEquals(28 / 13.0, CountKeys.betSpread(PlayerTreeOrderTest.named("Hi-Opt II")), 1e-15);
        assertEquals(32 / 13.0, CountKeys.betSpread(PlayerTreeOrderTest.named("Zen Count")), 1e-15);
        assertEquals(Math.sqrt(10 / 28.0), new KeyEdges.Axis(PlayerTreeOrderTest.named("Hi-Opt II")).hiLo, 1e-15);
        assertEquals(Math.sqrt(10 / 32.0), new KeyEdges.Axis(PlayerTreeOrderTest.named("Zen Count")).hiLo, 1e-15);
        KeyEdges.Axis halves = new KeyEdges.Axis(PlayerTreeOrderTest.named("Wong Halves"));
        assertEquals(0.5, halves.unit, 0);
        assertEquals(1.5, halves.book(3), 0);
        KeyEdges.Axis ko = new KeyEdges.Axis(PlayerTreeOrderTest.named("KO (Knock-Out)"));
        assertEquals(32, ko.pivot);
        assertTrue(Double.isNaN(ko.hiLo));
        assertEquals(16, new KeyEdges.Axis(PlayerTreeOrderTest.named("Red Seven (Red 7)")).pivot);
        assertEquals(KeyEdges.NO_PIVOT, hiLo.pivot);
        // KO's key 32 is a drift-free true count of exactly +4 at every depth.
        CountKeys.Count c = PlayerTreeOrderTest.named("KO (Knock-Out)");
        for (int depth = 0; depth < 400; depth += 13) {
            assertEquals(4.0, c.driftFree52(32, depth) / (double) (ShoeRun.CARDS - depth), 1e-12);
        }
        // An ace side count's bet tags take m off the aces: Hi-Opt II + ace side count's spread
        // is that of A -2, 2 and 3 +1, 4 and 5 +2, 6 and 7 +1, ten -2, 412/169.
        assertEquals(412 / 169.0, CountKeys.betSpread(PlayerTreeOrderTest.named("Hi-Opt II").sideCounted()), 1e-14);
    }

    /**
     * A row's figures with the plays refitted are the scorer's own resamples: the flat figure
     * rebuilt from the resample sink's per-key sums is Entry.boot's flat figure on every
     * resample (the check 5.4 makes of run A' against c06c061). Held, the same figure moves only
     * with the weights. Every check of the row passes.
     */
    @Test
    public void refittedFiguresAreTheScorersResamples() {
        int resamples = 8;
        Scored s = new Scored(97, resamples, Arrays.asList(PlayerTreeOrderTest.named("Hi-Lo"),
                PlayerTreeOrderTest.named("Omega II")));
        for (SystemScorer.Result res : s.results) {
            for (SystemScorer.Entry e : res.entries) {
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    KeyEdges.Edges ed = KeyEdges.edges(e.name, KeyEdges.Layer.FIRST, new KeyEdges.Axis(e.count),
                            s.col.values(e.name, p), s.col.facts, s.sc.boot, s.col.refitted(e.name, p),
                            e.estimate[p][SystemScorer.FLAT]);
                    assertEquals(new ArrayList<String>(), ed.failures, e.name);
                    for (int r = 0; r < resamples; r++) {
                        double want = e.boot[p][SystemScorer.FLAT][r];
                        assertEquals(want, ed.flat.refit[r], 1e-12 * Math.abs(want), e.name + " resample " + r);
                    }
                    assertEquals("refitted", ed.flat.how);
                    assertEquals(resamples, ed.flat.held.length);
                    assertNotNull(ed.line, "a true count has a line");
                    assertNull(ed.pivot);
                }
            }
        }
    }

    /**
     * Widening applies a band's ratio to the bins whose Hi-Lo-equivalent position is in it, never
     * below 1, and leaves the chart and the perfect layers alone: a bin up to 0 under a ratio of
     * 0.5 is not narrowed, a bin of +1 to +3 under 2 has its interval twice as wide about its mean.
     */
    /** Synthetic figures with held resamples drawn at random about their estimates. */
    private static KeyEdges.Figure[] figures(Random rnd, int n, int resamples) {
        KeyEdges.Figure[] out = new KeyEdges.Figure[n];
        for (int k = 0; k < n; k++) {
            double[] h = new double[resamples];
            for (int r = 0; r < resamples; r++) {
                h[r] = 0.01 * k + 0.001 * rnd.nextGaussian();
            }
            out[k] = new KeyEdges.Figure(0.01 * k, h, null);
        }
        return out;
    }

    /** A row of Hi-Lo with keys -5 to +4, each standing alone, under the layer given, with random held resamples. */
    private static KeyEdges.Edges synthetic(KeyEdges.Layer layer) {
        Random rnd = new Random(103);
        KeyEdges.Table t = toy(new int[]{200, 200, 200, 200, 200, 200, 200, 200, 200, 200});
        KeyEdges.Bins bins = KeyEdges.bins(t);
        assertEquals(10, bins.size());
        int nf = KeyEdges.TOP.length + KeyEdges.BANDS.length + 1;
        return new KeyEdges.Edges("Hi-Lo", layer, 2, new KeyEdges.Axis(PlayerTreeOrderTest.named("Hi-Lo")), t, bins,
                figures(rnd, 10, 400), figures(rnd, 10, 400), figures(rnd, 10, 400), new double[10], figures(rnd, nf, 400),
                figures(rnd, 1, 400)[0], figures(rnd, 3, 400), null);
    }

    /**
     * Widening applies a band's ratio to the bins whose Hi-Lo-equivalent position is in it, never
     * below 1, and leaves the chart and the perfect layers alone: keys -5 to 0 under a ratio of
     * 0.5 are not narrowed, +1 to +3 under 2 have intervals twice as wide about their mean, +4
     * under 1.25 a quarter wider; full play says its later plays are held.
     */
    @Test
    public void widenAppliesEachBandsRatioAndNeverNarrows() {
        KeyEdges.Widening w = new KeyEdges.Widening();
        w.band[0] = 0.5;
        w.band[1] = 2;
        w.band[2] = 1.25;
        Arrays.fill(w.byShare, 1.5);
        w.measuredOn.add("a test");
        KeyEdges.Edges first = synthetic(KeyEdges.Layer.FIRST);
        KeyEdges.Edges full = synthetic(KeyEdges.Layer.FULL);
        KeyEdges.Edges chart = synthetic(KeyEdges.Layer.CHART);
        KeyEdges.Edges perfect = synthetic(KeyEdges.Layer.PERFECT_FIRST);
        for (KeyEdges.Edges e : Arrays.asList(first, full, chart, perfect)) {
            w.apply(e);
        }
        double[] want = {1, 1, 1, 1, 1, 1, 2, 2, 2, 1.25};
        for (int b = 0; b < 10; b++) {
            KeyEdges.Figure f = first.edge[b];
            assertEquals(want[b], f.widen, 0, "bin " + b);
            assertEquals(want[b], full.share[b].widen, 0, "bin " + b);
            Bootstrap.Interval h = f.heldInterval();
            Bootstrap.Interval x = f.interval();
            assertEquals(f.widen * h.se, x.se, 1e-12, "bin " + b);
            assertEquals(1, chart.edge[b].widen, 0);
            assertEquals(1, perfect.insurance[b].widen, 0);
            assertEquals("held, not widened: fitted on nothing", chart.edge[b].how);
        }
        assertTrue(full.edge[0].how.contains("later plays held fixed"), full.edge[0].how);
        assertFalse(first.edge[0].how.contains("later plays"), first.edge[0].how);
        assertEquals(1.5, first.byShare[0].widen, 0);
        assertEquals(1.5, first.flat.widen, 0);
        assertEquals(2, first.line[0].widen, 0, "the line takes the largest band's");
        assertEquals(1, chart.byShare[0].widen, 0);
    }

    /**
     * A collector saved and read back gives the same values and resample sums to the bit, with
     * its identity, and its file holds each distinct array once.
     */
    @Test
    public void aSavedCollectorReadsBackWhole(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        Scored s = new Scored(107, 3, Arrays.asList(PlayerTreeOrderTest.named("Omega II")));
        java.nio.file.Path file = dir.resolve("states.ser");
        s.col.save(file, "a test\nline two");
        KeyEdges.Collector back = KeyEdges.Collector.read(file);
        assertEquals("a test\nline two", back.identity);
        assertEquals(s.col.entries.keySet(), back.entries.keySet());
        for (String name : s.col.entries.keySet()) {
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                KeyEdges.Values a = s.col.values(name, p);
                KeyEdges.Values b = back.values(name, p);
                assertArrayEquals(a.state, b.state);
                assertArrayEquals(a.fold, b.fold);
                assertArrayEquals(a.key, b.key);
                assertArrayEquals(a.weight, b.weight, 0);
                assertArrayEquals(a.value, b.value, 0);
                assertArrayEquals(a.insurance, b.insurance, 0);
                KeyEdges.Refitted ra = s.col.refitted(name, p);
                KeyEdges.Refitted rb = back.refitted(name, p);
                assertArrayEquals(ra.key, rb.key);
                for (int r = 0; r < 3; r++) {
                    for (int f = 0; f < 2; f++) {
                        assertArrayEquals(ra.value[r][f], rb.value[r][f], 0);
                        assertArrayEquals(ra.weight[r][f], rb.weight[r][f], 0);
                    }
                }
            }
        }
        assertArrayEquals(s.col.facts.chart, back.facts.chart, 0);
        assertArrayEquals(s.col.facts.shoe, back.facts.shoe);
        // Read back, the plain entry and its row betting only share one array of states and one of
        // insurance values, and the betting variant shares the plain entry's play: each was written once.
        Object[] plain = back.handed.get("Omega II = Canfield Master")[2][0];
        Object[] betting = back.handed.get("Omega II = Canfield Master, betting only")[2][0];
        Object[] variant = back.handed.get("Omega II = Canfield Master + ace side count")[2][0];
        assertSame(plain[0], betting[0]);
        assertSame(plain[3], betting[3]);
        assertSame(plain[2], variant[2]);
        assertNotSame(plain[4], variant[4]);
    }

    /**
     * Check 7 of 5.4: a count of our own normalised to Hi-Lo's spread and rounded at K passes;
     * one whose whole tags were never normalised (Hi-Opt II's times K, spread 28/13 x K^2) fails, and its rows
     * say so instead of placing its keys on Hi-Lo's steps.
     */
    @Test
    public void aFineCountMustBetOnTagsOfHiLosSpread() {
        int k = CountKeys.K;
        CountKeys.Count normalised = CountKeys.real("F", new double[]{0, -1, 1, 1, 1.5, 1.5, 1, 0.5, 0, -0.5, -1}, null, k / 20);
        CountKeys.Count raw = CountKeys.whole("Raw", new int[]{0, 0, k, k, 2 * k, 2 * k, k, k, 0, 0, -2 * k}, null, k / 20,
                true);
        assertTrue(KeyEdges.spreadIsHiLos(normalised));
        assertFalse(KeyEdges.spreadIsHiLos(raw));
        Scored s = new Scored(131, 0, Arrays.asList(raw, normalised));
        for (CountKeys.Count c : Arrays.asList(raw, normalised)) {
            KeyEdges.Edges e = KeyEdges.edges(c.name, KeyEdges.Layer.FIRST, new KeyEdges.Axis(c), s.col.values(c.name, 2),
                    s.col.facts, null, null, Double.NaN);
            assertEquals(c == raw, !e.failures.isEmpty(), c.name + " " + e.failures);
            assertNotNull(e.quarter);
        }
    }
}
