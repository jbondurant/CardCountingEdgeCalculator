import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The split margin (COUNT_DESIGN.md, sections 2 and 4): what playing split hands better than
 * the chart adds to a state, delta, whose flat mean over the twin's states, times the widest
 * spread S, is the margin by which a count with full play could beat the full-play perfect row,
 * which plays split hands by the chart.
 *
 * A count with full play plays a state's split hands by a SplitPlay drawn from its tables at the
 * deal's group: a hit or stand for each later total, soft or hard, against the up-card, and a
 * first move for each second card. So the most any count's split play can make of a pair deal at
 * a state is the best SplitPlay for that state alone. This finds it by coordinate ascent from the
 * chart's: each contested later total in turn (hard 12 to 16, soft 13 to 18), then each second
 * card's first move (each move legal on a split hand put first, the chart's ranking after it),
 * keeping any change that raises V(Split), until a whole pass changes nothing. A local optimum:
 * delta is measured from below as far as the ascent stops short of the best SplitPlay, and the
 * tool's own lines say so, as must anything that uses them. The mean is given at each penetration,
 * over the sample's states of that penetration (summary), since each penetration's full-play bound
 * takes its own margin.
 *
 * A state's delta, in the units of its round value, is the sum over the pair deals it can deal
 * of P(i) (1 - P(nat|i)) x (the best first move with Split at its best play, less the best first
 * move with Split at the chart's), as the perfect row takes its first moves (FullIndexFit.perfect).
 *
 * java -Xmx8g -cp CLASSES:lib/mongo-java-driver-3.12.14.jar SplitMargin TWIN_STATES [every k-th state] [threads]
 */
final class SplitMargin {

    private SplitMargin() {
    }

    /** One state's delta, and how many splits were valued afresh to find it. */
    static final class Delta {
        double delta;
        int valued;
        /** Per pair deal looked at, what its best split play adds to V(Split) over the chart's. */
        final List<double[]> gains = new ArrayList<>();
    }

    /** The pair deals a state can be dealt and split, in deal order. */
    static int[] pairDeals(RoundRules rules) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < Deals.COUNT; i++) {
            if (Deals.P1[i] == Deals.P2[i] && rules.maxHandsFor(Deals.P1[i]) >= 2) {
                out.add(i);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** A state's delta over the pair deals given (every one when null). */
    static Delta delta(RoundRules rules, StateStore.Record r, ExactRound engine, int[] deals) {
        int[] left = r.ranksLeft();
        SplitValues sv = new SplitValues(rules, left, r.values, new java.util.HashMap<>(), engine);
        int moves = ScoringStates.MOVES;
        int split = SystemScorer.SPLIT;
        Delta out = new Delta();
        for (int i : deals == null ? pairDeals(rules) : deals) {
            double p = Deals.probability(left, i);
            if (p == 0 || !StateValuer.legal(rules, i, PlayerMove.Split)) {
                continue;
            }
            double dn = Deals.dealerNatural(left, i);
            double other = Double.NEGATIVE_INFINITY;
            for (int m = 0; m < moves; m++) {
                if (m != split && StateValuer.legal(rules, i, StateValuer.MOVES[m])) {
                    other = Math.max(other, r.values[i * moves + m]);
                }
            }
            double chart = r.values[i * moves + split];
            double best = bestSplit(rules, sv, i, chart);
            out.gains.add(new double[]{i, best - chart});
            out.delta += p * (1 - dn) * (Math.max(other, best) - Math.max(other, chart));
        }
        out.valued = sv.valued;
        return out;
    }

    /** The best V(Split) the coordinate ascent finds for pair deal i, from the chart's play, worth chart. */
    static double bestSplit(RoundRules rules, SplitValues sv, int deal, double chart) {
        int rank = Deals.P1[deal];
        int up = Deals.UP[deal];
        // The later table: contested totals, hard 12 to 16 at [total], soft 13 to 18 at [22 + total].
        boolean[] stands = new boolean[44];
        for (int total = 4; total <= 21; total++) {
            for (boolean soft : new boolean[]{false, true}) {
                stands[(soft ? 22 : 0) + total] = PlayerTree.CHART.stands(total, soft, up, 0);
            }
        }
        // Each second card's first move put first, or null for the chart's ranking alone.
        PlayerMove[] first = new PlayerMove[11];
        double best = chart;
        boolean changed = true;
        PlayerMove[] options = {PlayerMove.Stand, PlayerMove.Hit, PlayerMove.Double, PlayerMove.Split};
        while (changed) {
            changed = false;
            for (int total = 4; total <= 21; total++) {
                for (boolean soft : new boolean[]{false, true}) {
                    if (!PlayerTree.contested(total, soft)) {
                        continue;
                    }
                    int at = (soft ? 22 : 0) + total;
                    stands[at] = !stands[at];
                    double v = sv.value(deal, play(rules, rank, up, stands, first));
                    if (v > best + 1e-12) {
                        best = v;
                        changed = true;
                    } else {
                        stands[at] = !stands[at];
                    }
                }
            }
            for (int second = 1; second <= 10; second++) {
                PlayerMove was = first[second];
                for (PlayerMove m : options) {
                    if (m == was) {
                        continue;
                    }
                    first[second] = m;
                    double v = sv.value(deal, play(rules, rank, up, stands, first));
                    if (v > best + 1e-12) {
                        best = v;
                        was = m;
                        changed = true;
                    } else {
                        first[second] = was;
                    }
                }
            }
        }
        return best;
    }

    /** The SplitPlay of these tables: a contested total's answer from stands, the chart's elsewhere. */
    static SplitPlay play(RoundRules rules, int rank, int up, boolean[] stands, PlayerMove[] first) {
        boolean[] s = stands.clone();
        PlayerMove[] f = first.clone();
        return new SplitPlay(rank, up, (total, soft) -> PlayerTree.contested(total, soft) ? s[(soft ? 22 : 0) + total]
                : PlayerTree.CHART.stands(total, soft, up, 0), deal -> {
            PlayerMove[] chart = FullIndexFit.chartRanking(rules, deal);
            int second = Deals.P1[deal] == rank ? Deals.P2[deal] : Deals.P1[deal];
            if (f[second] == null) {
                return chart;
            }
            PlayerMove[] out = new PlayerMove[chart.length + 1];
            out[0] = f[second];
            System.arraycopy(chart, 0, out, 1, chart.length);
            return out;
        });
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path file = Paths.get(args[0]);
        int every = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 6;
        CustomCounts.requireTwin(file);
        RoundRules rules = StateRun.montreal();
        int records = StateStore.records(file);
        List<StateStore.Record> sample = new ArrayList<>();
        for (int at = 0; at < records; at += every) {
            StateStore.Record r = StateStore.read(file, at, 1).get(0);
            if (r.depth < SystemScorer.CUTS[SystemScorer.CUTS.length - 1]) {
                sample.add(r);
            }
        }
        PrintStream out = System.out;
        long t0 = System.nanoTime();
        double[] delta = new double[sample.size()];
        int[] valued = new int[sample.size()];
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
            List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                jobs.add(pool.submit(() -> {
                    ExactRound engine = new ExactRound(rules);
                    for (int k = next.getAndIncrement(); k < sample.size(); k = next.getAndIncrement()) {
                        Delta d = delta(rules, sample.get(k), engine, null);
                        delta[k] = d.delta;
                        valued[k] = d.valued;
                    }
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> j : jobs) {
                j.get();
            }
        } finally {
            pool.shutdown();
        }
        long splits = 0;
        for (int v : valued) {
            splits += v;
        }
        out.printf("SplitMargin %s on %s: every %d-th record at 80%%, %d states, %d splits valued, %.0f s.%n",
                FullIndexRun.commit(), file, every, sample.size(), splits, (System.nanoTime() - t0) / 1e9);
        print(out, sample, delta);
    }

    /**
     * The flat mean of delta at each penetration, weighted 1/q, over the sample's states of that
     * penetration (depth under its cut): [penetration] {mean, its SE, states}.
     */
    static double[][] summary(List<StateStore.Record> sample, double[] delta) {
        double[][] out = new double[SystemScorer.CUTS.length][];
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            double num = 0;
            double den = 0;
            int n = 0;
            for (int k = 0; k < delta.length; k++) {
                if (sample.get(k).depth < SystemScorer.CUTS[p]) {
                    double w = 1 / sample.get(k).q;
                    num += w * delta[k];
                    den += w;
                    n++;
                }
            }
            double mean = num / den;
            double ss = 0;
            for (int k = 0; k < delta.length; k++) {
                if (sample.get(k).depth < SystemScorer.CUTS[p]) {
                    double w = 1 / sample.get(k).q;
                    ss += w * w * (delta[k] - mean) * (delta[k] - mean);
                }
            }
            out[p] = new double[]{mean, Math.sqrt(ss) / den, n};
        }
        return out;
    }

    /** summary's lines, each saying the mean is measured from below. */
    static void print(PrintStream out, List<StateStore.Record> sample, double[] delta) {
        out.println("Flat mean of delta, weighted 1/q, and the margin at S = " + SystemScorer.SPREADS + ": a lower bound, "
                + "since the coordinate ascent stops at a local optimum of each state's split play; a full-play bound "
                + "plus this margin is conditional on it.");
        double[][] m = summary(sample, delta);
        for (int p = 0; p < m.length; p++) {
            out.printf(Locale.ROOT, "- %s: %.5f%% a round (SE %.5f%%) over %d states; the margin at S = %d, at least "
                            + "%.4f%%.%n", SystemScorer.PENETRATIONS[p], 100 * m[p][0], 100 * m[p][1], (int) m[p][2],
                    SystemScorer.SPREADS, 100 * SystemScorer.SPREADS * m[p][0]);
        }
    }
}
