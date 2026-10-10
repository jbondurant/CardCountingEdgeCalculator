import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The edge at each count (PLAN_COMPUTE.md section 5): at each value of a count's bet key, the
 * player's average result a round, with its share of rounds, how much of it insurance gives,
 * how many valued states stand behind it and an interval. It is the figure that explains why
 * counting works, and the one a reader can turn into bets of their own.
 *
 * Nothing here scores a count. The scorer hands its sinks what it valued each test state at
 * (SystemScorer.stateSink) and, on a run that refits the plays on every resample, each
 * resample's per-key sums (SystemScorer.resampleSink); a Collector keeps both, and every figure
 * here is computed from them, so a table here is the scorer's own key table to the bit
 * (KeyEdgesTest). A layer is one way of playing the rounds (Layer): the chart, the chart with the
 * count's insurance, the count's fitted first decisions, its full play, and the two perfect plays.
 *
 * Intervals. A per-key interval needs per-state values, which no saved Result holds, so they are
 * made here. With the plays held, each of the bootstrap's 1,000 resamples moves only the weights,
 * by the same multiplicities every published interval was drawn with, so resample r pairs with
 * Entry.boot[...][r]. With the plays refitted, the resample sink's sums are used as they are.
 * Holding the plays understates an interval where the play groups are thin, so a held interval
 * is widened by a ratio measured on counts refitted outright (Widening below).
 *
 * Keys stand alone when their effective number of valued states, (sum of w)^2 / sum of w^2, is
 * at least 100; the thin keys at either end are pooled into "at most k" and "at least k"
 * (Bins). The rule looks at weights alone and was fixed before any run.
 */
final class KeyEdges {

    private KeyEdges() {
    }

    /** One way of playing the rounds, whose per-state value the edge is the mean of. */
    enum Layer {
        /** Basic strategy, no insurance: the same per-state values for every count. */
        CHART("chart"),
        /** The chart, with the count's fitted insurance: its row betting only. */
        CHART_INSURANCE("chart + insurance"),
        /** The count's fitted first move and insurance: its own row. */
        FIRST("first decisions"),
        /** The count's full-index play and insurance: its full-play row. */
        FULL("full play"),
        /** The best first move of every deal and perfect insurance (ScoringStates.perfect). */
        PERFECT_FIRST("perfect first move"),
        /** Perfect play of the dealt hand and perfect insurance (FullIndexFit's perfect array). */
        PERFECT_FULL("perfect full play");

        final String label;

        Layer(String label) {
            this.label = label;
        }
    }

    /**
     * One row's per-state values at one penetration under one layer, in the order the scorer
     * walks them: fold 0's test states, then fold 1's. value is the round value with insurance,
     * insurance what insurance adds to it, key the row's bet key.
     */
    static final class Values {
        final int p;
        final int[] state;
        final int[] fold;
        final double[] weight;
        final double[] value;
        final double[] insurance;
        final int[] key;

        Values(int p, int[] state, int[] fold, double[] weight, double[] value, double[] insurance, int[] key) {
            int n = state.length;
            if (fold.length != n || weight.length != n || value.length != n || insurance.length != n || key.length != n) {
                throw new IllegalArgumentException("per-state arrays of different lengths");
            }
            this.p = p;
            this.state = state;
            this.fold = fold;
            this.weight = weight;
            this.value = value;
            this.insurance = insurance;
            this.key = key;
        }

        int size() {
            return state.length;
        }

        /** The same states and values with each key mapped to another, which pools keys exactly. */
        Values rekeyed(java.util.function.IntUnaryOperator map) {
            int[] k = new int[key.length];
            for (int t = 0; t < k.length; t++) {
                k[t] = map.applyAsInt(key[t]);
            }
            return new Values(p, state, fold, weight, value, insurance, k);
        }

        /** The same keys and weights with other values per state, by state index: another layer of the same row. */
        Values withValues(double[] valueOf, double[] insuranceOf) {
            double[] v = new double[state.length];
            double[] i = new double[state.length];
            for (int t = 0; t < v.length; t++) {
                v[t] = valueOf[state[t]] + (insuranceOf == null ? 0 : insuranceOf[state[t]]);
                i[t] = insuranceOf == null ? 0 : insuranceOf[state[t]];
            }
            return new Values(p, state, fold, weight, v, i, key);
        }

        /** The states whose index passes the test given, in the same order. */
        Values only(java.util.function.IntPredicate keep) {
            int n = 0;
            for (int s : state) {
                n += keep.test(s) ? 1 : 0;
            }
            int[] st = new int[n];
            int[] fo = new int[n];
            double[] w = new double[n];
            double[] v = new double[n];
            double[] i = new double[n];
            int[] k = new int[n];
            n = 0;
            for (int t = 0; t < state.length; t++) {
                if (keep.test(state[t])) {
                    st[n] = state[t];
                    fo[n] = fold[t];
                    w[n] = weight[t];
                    v[n] = value[t];
                    i[n] = insurance[t];
                    k[n] = key[t];
                    n++;
                }
            }
            return new Values(p, st, fo, w, v, i, k);
        }
    }

    /**
     * Both sinks: keeps what the scorer hands out, by entry name and penetration. A name is
     * unique within one run's entries ("Hi-Lo", "Hi-Lo, betting only", "Hi-Lo, full play"), and
     * a second hand-out under the same name, penetration and fold is refused, so two runs cannot
     * be mixed by mistake. The resample sink is called from several threads; each call writes
     * only its own resample's slot.
     */
    static final class Collector implements SystemScorer.StateSink, SystemScorer.ResampleSink {
        final ScoringStates st;
        final int resamples;
        /** By entry name: the entry, as the scorer listed it. */
        final Map<String, SystemScorer.Entry> entries = new LinkedHashMap<>();
        /** By entry name, [pen][fold]: what the state sink was handed, {state, weight, play, insurance, key}. */
        private final Map<String, Object[][][]> handed = new LinkedHashMap<>();
        /** By entry name, [pen]: the resample sums. */
        private final Map<String, Refitted[]> refitted = new LinkedHashMap<>();

        /** A collector of states from st; resamples is how many resamples the resample sink may hand (0 for none). */
        Collector(ScoringStates st, int resamples) {
            this.st = st;
            this.resamples = resamples;
        }

        @Override
        public synchronized void states(int p, SystemScorer.Entry entry, int f, int[] state, double[] weight,
                                        double[] play, double[] insurance, int[] key) {
            remember(entry);
            Object[][][] h = handed.computeIfAbsent(entry.name, x -> new Object[SystemScorer.CUTS.length][2][]);
            if (h[p][f] != null) {
                throw new IllegalStateException(entry.name + " handed twice at penetration " + p + ", fold " + f);
            }
            h[p][f] = new Object[]{state, weight, play, insurance, key};
        }

        @Override
        public void sums(int p, int r, SystemScorer.Entry entry, int f, int[] key, double[] weight, double[] value,
                         double[] insurance) {
            if (r >= resamples) {
                throw new IllegalStateException("resample " + r + " of a collector made for " + resamples);
            }
            Refitted rf;
            synchronized (this) {
                remember(entry);
                Refitted[] byPen = refitted.computeIfAbsent(entry.name, x -> new Refitted[SystemScorer.CUTS.length]);
                if (byPen[p] == null) {
                    byPen[p] = new Refitted(key, resamples);
                }
                rf = byPen[p];
            }
            rf.put(r, f, key, weight, value, insurance);
        }

        private void remember(SystemScorer.Entry entry) {
            SystemScorer.Entry had = entries.putIfAbsent(entry.name, entry);
            if (had != null && had != entry) {
                throw new IllegalStateException("two entries named " + entry.name + ": a collector keeps one run");
            }
        }

        /** Whether entry name was handed at penetration p, both folds. */
        synchronized boolean has(String name, int p) {
            Object[][][] h = handed.get(name);
            return h != null && h[p][0] != null && h[p][1] != null;
        }

        /** The values the state sink was handed for entry name at penetration p: fold 0's, then fold 1's. */
        synchronized Values values(String name, int p) {
            Object[][][] h = handed.get(name);
            if (h == null || h[p][0] == null || h[p][1] == null) {
                throw new IllegalStateException("no states of " + name + " at penetration " + p);
            }
            int n = ((int[]) h[p][0][0]).length + ((int[]) h[p][1][0]).length;
            int[] state = new int[n];
            int[] fold = new int[n];
            double[] weight = new double[n];
            double[] value = new double[n];
            double[] ins = new double[n];
            int[] key = new int[n];
            int t = 0;
            for (int f = 0; f < 2; f++) {
                Object[] a = h[p][f];
                int[] s = (int[]) a[0];
                for (int u = 0; u < s.length; u++, t++) {
                    state[t] = s[u];
                    fold[t] = f;
                    weight[t] = ((double[]) a[1])[u];
                    // As the scorer forms a state's round value: play, then insurance added.
                    value[t] = ((double[]) a[2])[u] + ((double[]) a[3])[u];
                    ins[t] = ((double[]) a[3])[u];
                    key[t] = ((int[]) a[4])[u];
                }
            }
            return new Values(p, state, fold, weight, value, ins, key);
        }

        /** The resample sums of entry name at penetration p, every resample handed both folds; null if none were. */
        synchronized Refitted refitted(String name, int p) {
            Refitted[] r = refitted.get(name);
            if (r == null || r[p] == null) {
                return null;
            }
            r[p].requireWhole(name);
            return r[p];
        }

        /** Forgets every penetration's states but p's, to hold one penetration's at a time. */
        synchronized void keepOnly(int p) {
            for (Object[][][] h : handed.values()) {
                for (int q = 0; q < h.length; q++) {
                    if (q != p) {
                        h[q] = new Object[2][];
                    }
                }
            }
        }
    }

    /**
     * One entry's resample sums at one penetration, from the resample sink: [r][fold][key] of
     * the weight, weight x value and weight x insurance, the keys the entry's bet keys at the
     * penetration (the same on every resample, since a resample moves weights, not cards).
     */
    static final class Refitted {
        final int[] key;
        final double[][][] weight;
        final double[][][] value;
        final double[][][] insurance;

        Refitted(int[] key, int resamples) {
            this.key = key.clone();
            weight = new double[resamples][2][];
            value = new double[resamples][2][];
            insurance = new double[resamples][2][];
        }

        void put(int r, int f, int[] k, double[] w, double[] v, double[] i) {
            if (!Arrays.equals(k, key)) {
                throw new IllegalStateException("a resample with other keys");
            }
            synchronized (this) {
                if (weight[r][f] != null) {
                    throw new IllegalStateException("resample " + r + " fold " + f + " handed twice");
                }
                weight[r][f] = w;
                value[r][f] = v;
                insurance[r][f] = i;
            }
        }

        void requireWhole(String name) {
            for (int r = 0; r < weight.length; r++) {
                if (weight[r][0] == null || weight[r][1] == null) {
                    throw new IllegalStateException(name + ": resample " + r + " was not handed both folds");
                }
            }
        }
    }

    // ------------------------------------------------------------------ per-key sums

    /**
     * Per-key sums over some states under some weights, keys in increasing order: the weight,
     * weight x value and weight x insurance. Every per-key figure, on the estimate or on a
     * resample, is a ratio of these, so pooling keys is adding their sums, exactly.
     */
    static final class Sums {
        final int[] key;
        final double[] weight;
        final double[] value;
        final double[] insurance;
        /** The sum of the weight, added fold by fold and key by key as the scorer adds it. */
        final double total;

        Sums(int[] key, double[] weight, double[] value, double[] insurance, double total) {
            this.key = key;
            this.weight = weight;
            this.value = value;
            this.insurance = insurance;
            this.total = total;
        }

        /** The weighted mean value over every key: the row's flat figure under its layer. */
        double flat() {
            double v = 0;
            for (double x : value) {
                v += x;
            }
            return v / total;
        }
    }

    /** The distinct keys of some values, in increasing order. */
    static int[] keysOf(Values v) {
        return Arrays.stream(v.key).distinct().sorted().toArray();
    }

    /**
     * The point table of some values: each key's sums, its number of valued states, the sum of
     * the squares of its weights (for n_eff), and the 10th and 90th weighted percentiles of its
     * states' values. Summed fold by fold in the scorer's order and then pooled, as
     * SystemScorer.keyTable pools them, so the share and the mean are the key table's to the bit.
     */
    static final class Table {
        final Sums sums;
        final int[] valued;
        final double[] squares;
        final double[] p10;
        final double[] p90;

        Table(Sums sums, int[] valued, double[] squares, double[] p10, double[] p90) {
            this.sums = sums;
            this.valued = valued;
            this.squares = squares;
            this.p10 = p10;
            this.p90 = p90;
        }

        int keys() {
            return sums.key.length;
        }

        double share(int k) {
            return sums.weight[k] / sums.total;
        }

        double edge(int k) {
            return sums.value[k] / sums.weight[k];
        }

        double insurance(int k) {
            return sums.insurance[k] / sums.weight[k];
        }

        /** (sum of w)^2 / sum of w^2: how many equally weighted states the key's mean is worth. */
        double nEff(int k) {
            return sums.weight[k] * sums.weight[k] / squares[k];
        }
    }

    static Table table(Values v) {
        int[] keys = keysOf(v);
        int n = keys.length;
        double[][] w = new double[2][n];
        double[][] val = new double[2][n];
        double[][] ins = new double[2][n];
        int[] valued = new int[n];
        double[] sq = new double[n];
        int[] at = new int[v.size()];
        for (int t = 0; t < v.size(); t++) {
            int k = Arrays.binarySearch(keys, v.key[t]);
            at[t] = k;
            int f = v.fold[t];
            w[f][k] += v.weight[t];
            val[f][k] += v.weight[t] * v.value[t];
            ins[f][k] += v.weight[t] * v.insurance[t];
            valued[k]++;
            sq[k] += v.weight[t] * v.weight[t];
        }
        double total = 0;
        for (int f = 0; f < 2; f++) {
            double fw = 0;
            for (int k = 0; k < n; k++) {
                fw += w[f][k];
            }
            total += fw;
        }
        double[] weight = new double[n];
        double[] value = new double[n];
        double[] insurance = new double[n];
        for (int k = 0; k < n; k++) {
            weight[k] = w[0][k] + w[1][k];
            value[k] = val[0][k] + val[1][k];
            insurance[k] = ins[0][k] + ins[1][k];
        }
        double[] p10 = new double[n];
        double[] p90 = new double[n];
        Integer[] order = new Integer[v.size()];
        for (int t = 0; t < order.length; t++) {
            order[t] = t;
        }
        Arrays.sort(order, (a, b) -> at[a] != at[b] ? Integer.compare(at[a], at[b]) : Double.compare(v.value[a], v.value[b]));
        int from = 0;
        for (int k = 0; k < n; k++) {
            int to = from + valued[k];
            p10[k] = weightedQuantile(v, order, from, to, 0.10);
            p90[k] = weightedQuantile(v, order, from, to, 0.90);
            from = to;
        }
        return new Table(new Sums(keys, weight, value, insurance, total), valued, sq, p10, p90);
    }

    /**
     * The q weighted quantile of the values of order[from..to), which are in increasing value:
     * the smallest value with at least q of the weight at or below it.
     */
    static double weightedQuantile(Values v, Integer[] order, int from, int to, double q) {
        double all = 0;
        for (int u = from; u < to; u++) {
            all += v.weight[order[u]];
        }
        double cum = 0;
        for (int u = from; u < to; u++) {
            cum += v.weight[order[u]];
            if (cum >= q * all) {
                return v.value[order[u]];
            }
        }
        return to > from ? v.value[order[to - 1]] : Double.NaN;
    }

    // ------------------------------------------------------------------- resamples

    /**
     * Each resample's per-key sums with the values held: every state's weight times its shoe's
     * multiplicity on that resample, the bootstrap's own draws (PLAN_COMPUTE.md 5.3, item 2). A
     * state's value stays what the estimate's play gave it; only the weights move. The states
     * are first summed by (shoe, key), which leaves each resample one pass over those cells.
     */
    static Sums[] held(Values v, int[] keys, ScoringStates st, Bootstrap boot) {
        int n = v.size();
        int[] shoe = new int[n];
        int[] at = new int[n];
        Integer[] order = new Integer[n];
        for (int t = 0; t < n; t++) {
            shoe[t] = st.shoe[v.state[t]];
            at[t] = Arrays.binarySearch(keys, v.key[t]);
            if (at[t] < 0) {
                throw new IllegalArgumentException("a state's key " + v.key[t] + " is not among the keys given");
            }
            order[t] = t;
        }
        Arrays.sort(order, (a, b) -> shoe[a] != shoe[b] ? Integer.compare(shoe[a], shoe[b]) : Integer.compare(at[a], at[b]));
        int cells = 0;
        int[] cShoe = new int[n];
        int[] cKey = new int[n];
        double[] cw = new double[n];
        double[] cv = new double[n];
        double[] ci = new double[n];
        for (int u = 0; u < n; u++) {
            int t = order[u];
            if (cells == 0 || cShoe[cells - 1] != shoe[t] || cKey[cells - 1] != at[t]) {
                cShoe[cells] = shoe[t];
                cKey[cells] = at[t];
                cells++;
            }
            cw[cells - 1] += v.weight[t];
            cv[cells - 1] += v.weight[t] * v.value[t];
            ci[cells - 1] += v.weight[t] * v.insurance[t];
        }
        Sums[] out = new Sums[boot.resamples];
        for (int r = 0; r < boot.resamples; r++) {
            double[] w = new double[keys.length];
            double[] val = new double[keys.length];
            double[] ins = new double[keys.length];
            for (int c = 0; c < cells; c++) {
                int m = boot.mult(r, cShoe[c]);
                if (m != 0) {
                    w[cKey[c]] += m * cw[c];
                    val[cKey[c]] += m * cv[c];
                    ins[cKey[c]] += m * ci[c];
                }
            }
            double total = 0;
            for (double x : w) {
                total += x;
            }
            out[r] = new Sums(keys, w, val, ins, total);
        }
        return out;
    }

    /**
     * Each resample's per-key sums with the plays refitted, from the resample sink: both folds
     * added, on the keys given (the point table's), a key the resample's entry did not have
     * left at zero. The keys of the sink are the entry's bet keys at the penetration, which are
     * the point table's keys exactly when every key of the penetration was handed in a test
     * fold; anything else is refused.
     */
    static Sums[] refitted(Refitted rf, int[] keys) {
        return refitted(rf, keys, null);
    }

    /** The same with the sink's keys mapped first, as the point values were rekeyed (Axis.rebin); null for none. */
    static Sums[] refitted(Refitted rf, int[] keys, java.util.function.IntUnaryOperator map) {
        int[] at = new int[rf.key.length];
        for (int k = 0; k < rf.key.length; k++) {
            at[k] = Arrays.binarySearch(keys, map == null ? rf.key[k] : map.applyAsInt(rf.key[k]));
            if (at[k] < 0) {
                throw new IllegalArgumentException("the resample sink's key " + rf.key[k] + " is not in the point table");
            }
        }
        Sums[] out = new Sums[rf.weight.length];
        for (int r = 0; r < out.length; r++) {
            double[] w = new double[keys.length];
            double[] val = new double[keys.length];
            double[] ins = new double[keys.length];
            double total = 0;
            for (int f = 0; f < 2; f++) {
                double fw = 0;
                for (int k = 0; k < rf.key.length; k++) {
                    w[at[k]] += rf.weight[r][f][k];
                    val[at[k]] += rf.value[r][f][k];
                    ins[at[k]] += rf.insurance[r][f][k];
                    fw += rf.weight[r][f][k];
                }
                total += fw;
            }
            out[r] = new Sums(keys, w, val, ins, total);
        }
        return out;
    }

    // ------------------------------------------------------------------- tail pooling

    /** A key stands alone when its effective number of valued states is at least this. */
    static final double MIN_N_EFF = 100;

    /** How a bin of keys is shown. */
    enum Bin {
        /** One key, with n_eff of at least MIN_N_EFF. */
        ALONE,
        /** One key between keys that stand, thinner than MIN_N_EFF: shown, and flagged thin. */
        THIN,
        /** The low tail, "at most k", k its highest key. */
        AT_MOST,
        /** The high tail, "at least k", k its lowest key. */
        AT_LEAST,
        /** Every key, when too few stand to leave anything else. */
        ALL
    }

    /**
     * The keys of a table in bins (PLAN_COMPUTE.md 5.2, "Tails"): keys [from[b], to[b]) of the
     * table, of kind kind[b]. The rule looks at the weights alone. Each key with n_eff of at least
     * MIN_N_EFF stands alone; the keys below the lowest that stands pool into the low tail, those
     * above the highest into the high tail. A tail whose own n_eff is short of MIN_N_EFF takes in
     * the outermost key that stands, and the next, until it is not, so that every bin shown at
     * either end has MIN_N_EFF behind it and weight on nearly every resample (check 5); with too
     * little weight for that, every key is one bin. A key between two that stand keeps its own bin,
     * flagged thin; a unimodal count has none.
     */
    static final class Bins {
        final int[] from;
        final int[] to;
        final Bin[] kind;

        Bins(int[] from, int[] to, Bin[] kind) {
            this.from = from;
            this.to = to;
            this.kind = kind;
        }

        int size() {
            return from.length;
        }
    }

    static double nEff(Table t, int from, int to) {
        double w = 0;
        double sq = 0;
        for (int k = from; k < to; k++) {
            w += t.sums.weight[k];
            sq += t.squares[k];
        }
        return sq == 0 ? 0 : w * w / sq;
    }

    static Bins bins(Table t) {
        int n = t.keys();
        int lo = 0;
        while (lo < n && t.nEff(lo) < MIN_N_EFF) {
            lo++;
        }
        int hi = n - 1;
        while (hi >= 0 && t.nEff(hi) < MIN_N_EFF) {
            hi--;
        }
        // lo..hi are the keys between the lowest and the highest that stand.
        while (lo > 0 && lo <= hi && nEff(t, 0, lo) < MIN_N_EFF) {
            lo++;
        }
        while (hi < n - 1 && hi >= lo && nEff(t, hi + 1, n) < MIN_N_EFF) {
            hi--;
        }
        if (lo > hi || (lo > 0 && nEff(t, 0, lo) < MIN_N_EFF) || (hi < n - 1 && nEff(t, hi + 1, n) < MIN_N_EFF)) {
            return new Bins(new int[]{0}, new int[]{n}, new Bin[]{Bin.ALL});
        }
        List<int[]> b = new ArrayList<>();
        List<Bin> kind = new ArrayList<>();
        if (lo > 0) {
            b.add(new int[]{0, lo});
            kind.add(Bin.AT_MOST);
        }
        for (int k = lo; k <= hi; k++) {
            b.add(new int[]{k, k + 1});
            kind.add(t.nEff(k) >= MIN_N_EFF ? Bin.ALONE : Bin.THIN);
        }
        if (hi < n - 1) {
            b.add(new int[]{hi + 1, n});
            kind.add(Bin.AT_LEAST);
        }
        int[] from = new int[b.size()];
        int[] to = new int[b.size()];
        for (int k = 0; k < from.length; k++) {
            from[k] = b.get(k)[0];
            to[k] = b.get(k)[1];
        }
        return new Bins(from, to, kind.toArray(new Bin[0]));
    }

    /** Bin b's sums: weight, weight x value, weight x insurance, its keys' added. */
    static double[] binSums(Sums s, Bins bins, int b) {
        double[] out = new double[3];
        for (int k = bins.from[b]; k < bins.to[b]; k++) {
            out[0] += s.weight[k];
            out[1] += s.value[k];
            out[2] += s.insurance[k];
        }
        return out;
    }

    // ---------------------------------------------------------- comparing counts by share

    /** The shares of rounds the top-q edge is taken over (PLAN_COMPUTE.md 5.2). */
    static final double[] TOP = {0.005, 0.01, 0.02, 0.05, 0.10, 0.25, 0.50, 1.0};
    /** The percentile bands of rounds, from the lowest rated, as [from, to). */
    static final double[][] BANDS = {{0, 0.50}, {0.50, 0.75}, {0.75, 0.90}, {0.90, 0.95}, {0.95, 0.98},
            {0.98, 0.99}, {0.99, 0.995}, {0.995, 1}};

    /**
     * The weight x value of the share q of rounds a count rates highest: keys taken from the
     * highest down, each whole while it fits, and the key that straddles the boundary contributing
     * its mean times the part of its weight inside, as scheduleIntegral splits a block of the same
     * schedule. A count's key order is its rating, the order the same schedule hands bets out in.
     */
    static double topSum(Sums s, double q) {
        double target = q * s.total;
        double cum = 0;
        double sum = 0;
        for (int k = s.key.length - 1; k >= 0; k--) {
            double w = s.weight[k];
            if (w == 0) {
                continue;
            }
            if (cum + w <= target) {
                cum += w;
                sum += s.value[k];
            } else {
                sum += (target - cum) / w * s.value[k];
                break;
            }
        }
        return sum;
    }

    /** The mean edge over the share q of rounds the count rates highest; at q = 1 it is the flat figure. */
    static double topEdge(Sums s, double q) {
        return topSum(s, q) / (q * s.total);
    }

    /** The mean edge over the rounds between percentiles a and b of the count's rating, from the lowest. */
    static double bandEdge(Sums s, double a, double b) {
        return (topSum(s, 1 - a) - topSum(s, 1 - b)) / ((b - a) * s.total);
    }

    /** The share of rounds whose key's mean value is above zero (PLAN_COMPUTE.md 5.2, (b) under the chart). */
    static double positiveKeys(Sums s) {
        double w = 0;
        for (int k = 0; k < s.key.length; k++) {
            if (s.weight[k] > 0 && s.value[k] > 0) {
                w += s.weight[k];
            }
        }
        return w / s.total;
    }

    /**
     * The oracle (PLAN_COMPUTE.md 5.2): the states themselves ranked by their own value, the most
     * any ranking of the rounds can make of these values, as top-q edges, band edges and the share
     * of rounds of positive value; on the estimate (weights as they are) and on each held
     * resample. [0] is the estimate's, [1 + r] resample r's; each row TOP's edges, then BANDS',
     * then the positive share. Equal values tie, and a tie is one block whose order inside does not
     * change any of these.
     */
    static double[][] oracle(Values v, ScoringStates st, Bootstrap boot) {
        int n = v.size();
        Integer[] order = new Integer[n];
        for (int t = 0; t < n; t++) {
            order[t] = t;
        }
        Arrays.sort(order, (a, b) -> Double.compare(v.value[b], v.value[a]));
        double[] value = new double[n];
        double[] weight = new double[n];
        int[] shoe = new int[n];
        for (int u = 0; u < n; u++) {
            value[u] = v.value[order[u]];
            weight[u] = v.weight[order[u]];
            shoe[u] = st.shoe[v.state[order[u]]];
        }
        double[][] out = new double[1 + boot.resamples][];
        double[] w = new double[n];
        for (int r = -1; r < boot.resamples; r++) {
            for (int u = 0; u < n; u++) {
                w[u] = r < 0 ? weight[u] : weight[u] * boot.mult(r, shoe[u]);
            }
            out[1 + r] = rankedFigures(value, w);
        }
        return out;
    }

    /** TOP's edges, BANDS' edges and the positive share of rounds already ranked highest first, each its own key. */
    static double[] rankedFigures(double[] value, double[] w) {
        double total = 0;
        double positive = 0;
        for (int u = 0; u < w.length; u++) {
            total += w[u];
            positive += value[u] > 0 ? w[u] : 0;
        }
        double[] q = new double[TOP.length + 2 * BANDS.length];
        for (int k = 0; k < TOP.length; k++) {
            q[k] = TOP[k];
        }
        for (int b = 0; b < BANDS.length; b++) {
            q[TOP.length + 2 * b] = 1 - BANDS[b][0];
            q[TOP.length + 2 * b + 1] = 1 - BANDS[b][1];
        }
        // One walk down the ranking serves every boundary: they are visited in increasing order.
        Integer[] byTarget = new Integer[q.length];
        for (int k = 0; k < q.length; k++) {
            byTarget[k] = k;
        }
        Arrays.sort(byTarget, (a, b) -> Double.compare(q[a], q[b]));
        double[] top = new double[q.length];
        int next = 0;
        double cum = 0;
        double sum = 0;
        for (int u = 0; u < w.length && next < q.length; u++) {
            if (w[u] == 0) {
                continue;
            }
            while (next < q.length && q[byTarget[next]] * total < cum + w[u]) {
                top[byTarget[next]] = sum + (q[byTarget[next]] * total - cum) * value[u];
                next++;
            }
            cum += w[u];
            sum += w[u] * value[u];
        }
        for (; next < q.length; next++) {
            top[byTarget[next]] = sum;
        }
        double[] out = new double[TOP.length + BANDS.length + 1];
        for (int k = 0; k < TOP.length; k++) {
            out[k] = top[k] / (TOP[k] * total);
        }
        for (int b = 0; b < BANDS.length; b++) {
            out[TOP.length + b] = (top[TOP.length + 2 * b] - top[TOP.length + 2 * b + 1])
                    / ((BANDS[b][1] - BANDS[b][0]) * total);
        }
        out[TOP.length + BANDS.length] = positive / total;
        return out;
    }

    /** A count's figures by share of rounds on one set of sums, in the oracle's order: TOP's, BANDS', positive keys. */
    static double[] shareFigures(Sums s) {
        double[] out = new double[TOP.length + BANDS.length + 1];
        for (int k = 0; k < TOP.length; k++) {
            out[k] = topEdge(s, TOP[k]);
        }
        for (int b = 0; b < BANDS.length; b++) {
            out[TOP.length + b] = bandEdge(s, BANDS[b][0], BANDS[b][1]);
        }
        out[TOP.length + BANDS.length] = positiveKeys(s);
        return out;
    }

    // ------------------------------------------------------------------- the key axis

    /** No pivot: a true count, or a running count whose pivot falls between two keys. */
    static final int NO_PIVOT = Integer.MIN_VALUE;

    /**
     * How a row's keys are shown (PLAN_COMPUTE.md 5.2, "Key axis").
     * - A catalog count keys as its book does, once its whole-number scale is taken off: the key
     *   over its scale, so Wong Halves' keys go in halves.
     * - A count at scale K (our own, or a catalog count at grain) keys in steps of D / K of a tag
     *   unit, which is 1/20 of Hi-Lo's step for the registered counts; its keys are pooled into
     *   whole steps first, k x D / K rounded as true counts are rounded (to nearest, ties toward
     *   zero), by adding their sums, which is exact. Step 0 then holds 21 keys of 1/20 and every
     *   other 20. A quarter-step version goes to the CSV only.
     * - A true count's key is also placed on Hi-Lo's steps: its book key times
     *   sqrt(SPREAD / its bet tags' spread in book units) (CountKeys.betSpread), so Hi-Opt II's +4
     *   sits near Hi-Lo's +2.4. A running count has no such place: its true count moves with depth.
     * - A running count's pivot is the running count from the shuffle whose drift-free true count
     *   is the same at every depth: RC = DECKS x deck sum, a true count of the deck sum per deck.
     *   KO's is 32, a true count of +4 at every depth, and Red Seven's 16.
     */
    static final class Axis {
        final CountKeys.Count count;
        final boolean rebinned;
        /** The book key of key k is k x unit: 1 / scale, or 1 once pooled into whole steps. */
        final double unit;
        /** Hi-Lo's steps per book step of a true count; NaN for a running count. */
        final double hiLo;
        /** A running count's pivot as a key (after pooling, if pooled), or NO_PIVOT. */
        final int pivot;

        Axis(CountKeys.Count c) {
            this.count = c;
            this.rebinned = c.scale == CountKeys.K;
            this.unit = rebinned ? 1 : 1.0 / c.scale;
            double bookSpread = CountKeys.betSpread(c) / ((double) c.scale * c.scale);
            this.hiLo = c.trueCounted && bookSpread > 0 ? Math.sqrt(CountKeys.SPREAD / bookSpread) : Double.NaN;
            int p = NO_PIVOT;
            if (!c.trueCounted) {
                long rc = (long) ShoeRun.DECKS * c.betDeckSum();
                if (!rebinned && rc % c.divisor() == 0) {
                    p = (int) (rc / c.divisor());
                } else if (rebinned && rc % CountKeys.K == 0) {
                    p = (int) (rc / CountKeys.K);
                }
            }
            this.pivot = p;
        }

        /** Key k pooled into whole steps of a tag unit: k x D / K, to nearest, ties toward zero. */
        int whole(int k) {
            return (int) GranularCount.roundToGrain((double) k * count.divisor() / CountKeys.K, 1);
        }

        /** Key k pooled into quarter steps, in quarters: the CSV's finer version. */
        int quarter(int k) {
            return (int) Math.round(4 * GranularCount.roundToGrain((double) k * count.divisor() / CountKeys.K, 0.25));
        }

        /** The map the point values and the resample sums are rekeyed by: whole steps, or null to keep the keys. */
        java.util.function.IntUnaryOperator rebin() {
            return rebinned ? this::whole : null;
        }

        double book(int key) {
            return key * unit;
        }

        /** The key's place on Hi-Lo's steps; NaN for a running count. */
        double hiLoPosition(int key) {
            return book(key) * hiLo;
        }
    }
}
