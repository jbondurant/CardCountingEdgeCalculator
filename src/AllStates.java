/**
 * Every state of a run, valued or not, dealt again from the state file's seed
 * (COUNTING_COMPARISON.md, sections 2 and 6).
 *
 * A state file keeps only the valued states, about one in sixty. The empirical insurance
 * correlation is over all states, and the chance the next card is a ten needs no valuation,
 * only the tallies. So the shoes are dealt again: ShoeRun deals shoe i from the seed and i
 * alone, and draws whether each state is valued from a generator of the shoe's own, so the
 * same seed, rates, cut and play give the same states and the same choices.
 *
 * That also checks the file. Every valued state the replay finds must be the next record,
 * with the same round, depth, tallies and q, and every record must be found. A file made
 * under other settings, or by code that deals differently, is refused rather than scored.
 */
final class AllStates {

    final int shoes;
    final int size;
    /** Cards dealt before each state. */
    final short[] depth;
    /** Cards dealt of each kind before state s, at s x KINDS + k. */
    final byte[] dealt;

    private AllStates(int shoes, int size, short[] depth, byte[] dealt) {
        this.shoes = shoes;
        this.size = size;
        this.depth = depth;
        this.dealt = dealt;
    }

    /**
     * Deals shoes 0 to shoes - 1 again with the file's seed, rates and cut, and checks
     * every valued state against the prepared records.
     */
    static AllStates replay(StateStore.Header header, int shoes, RoundRules rules, RoundPolicy play,
                            ScoringStates st) {
        ShoeRun run = new ShoeRun(header.seed, header.q0, header.u0, header.cut, rules, play);
        int capacity = Math.max(16, shoes * 70);
        short[] depth = new short[capacity];
        byte[] dealt = new byte[capacity * ShoeRun.KINDS];
        int size = 0;
        int record = 0;
        for (int i = 0; i < shoes; i++) {
            for (ShoeRun.State s : run.deal(i)) {
                if (size == depth.length) {
                    depth = java.util.Arrays.copyOf(depth, size * 3 / 2);
                    dealt = java.util.Arrays.copyOf(dealt, depth.length * ShoeRun.KINDS);
                }
                depth[size] = (short) s.depth;
                for (int k = 0; k < ShoeRun.KINDS; k++) {
                    dealt[size * ShoeRun.KINDS + k] = (byte) s.dealt[k];
                }
                size++;
                if (s.valued) {
                    if (record >= st.size || !same(st, record, s)) {
                        throw new IllegalStateException("shoe " + s.shoe + " round " + s.round
                                + " is valued when dealt again but the file's next record is "
                                + (record >= st.size ? "missing" : "shoe " + st.shoe[record] + " round "
                                + st.round[record]) + ": the file was made with other settings or other code");
                    }
                    record++;
                }
            }
        }
        if (record != st.size) {
            throw new IllegalStateException("the file holds " + st.size + " valued states, dealing its shoes again finds "
                    + record + ": the file was made with other settings or other code");
        }
        return new AllStates(shoes, size, java.util.Arrays.copyOf(depth, size),
                java.util.Arrays.copyOf(dealt, size * ShoeRun.KINDS));
    }

    private static boolean same(ScoringStates st, int r, ShoeRun.State s) {
        return st.shoe[r] == s.shoe && st.round[r] == s.round && st.depth[r] == s.depth && st.q[r] == s.q
                && java.util.Arrays.equals(st.dealt[r], s.dealt);
    }

    /** The tallies of state s, by kind. */
    int[] dealt(int s) {
        int[] out = new int[ShoeRun.KINDS];
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            out[k] = dealt[s * ShoeRun.KINDS + k];
        }
        return out;
    }

    /** How many states are dealt before the cut card at cut. */
    int states(int cut) {
        int n = 0;
        for (int s = 0; s < size; s++) {
            if (depth[s] < cut) {
                n++;
            }
        }
        return n;
    }

    /** The chance the next card is a ten, from the tallies. */
    static double tenNext(int[] dealt, int depth) {
        int tens = 0;
        for (int k = 18; k < ShoeRun.KINDS; k++) {
            tens += 2 * ShoeRun.DECKS - dealt[k];
        }
        return (double) tens / (ShoeRun.CARDS - depth);
    }

    /**
     * The empirical insurance correlation: over every state dealt, the correlation between
     * the count's unrounded, drift-free true count and the chance the next card is a ten.
     * A side-count variant insures on its base's play key, so its figure is its base's.
     */
    double insuranceCorrelation(CountKeys.Count c) {
        CountKeys.Count count = c.base != null ? c.base : c;
        double[] x = new double[size];
        double[] y = new double[size];
        double[] w = new double[size];
        for (int s = 0; s < size; s++) {
            int[] d = dealt(s);
            x[s] = count.trueCount(d, depth[s]);
            y[s] = tenNext(d, depth[s]);
            w[s] = 1;
        }
        return correlation(x, y, w);
    }

    /**
     * The empirical betting correlation: over the valued states, each weighted 1/q, the
     * correlation between the count's unrounded, drift-free true count (with the side count's
     * adjustment for a variant) and the state's advantage under the chart without insurance,
     * which is known only where the state was valued.
     */
    static double bettingCorrelation(CountKeys.Count c, ScoringStates st) {
        double[] x = new double[st.size];
        for (int s = 0; s < st.size; s++) {
            x[s] = c.trueCount(st.dealt[s], st.depth[s]);
        }
        return correlation(x, st.chart, st.weight);
    }

    /** The weighted Pearson correlation; NaN where either side does not vary. */
    static double correlation(double[] x, double[] y, double[] w) {
        double sw = 0;
        double mx = 0;
        double my = 0;
        for (int i = 0; i < x.length; i++) {
            sw += w[i];
            mx += w[i] * x[i];
            my += w[i] * y[i];
        }
        if (sw == 0) {
            return Double.NaN;
        }
        mx /= sw;
        my /= sw;
        double sxx = 0;
        double syy = 0;
        double sxy = 0;
        for (int i = 0; i < x.length; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            sxx += w[i] * dx * dx;
            syy += w[i] * dy * dy;
            sxy += w[i] * dx * dy;
        }
        if (sxx == 0 || syy == 0) {
            return Double.NaN;
        }
        return sxy / Math.sqrt(sxx * syy);
    }
}
