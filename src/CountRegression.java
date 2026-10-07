import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The theory pass (COUNT_DESIGN.md, section 4), on the twin file: regressions on the shares of
 * the ranks left, every sum weighted 1/q, for the search's starting points and for reading its
 * result.
 * - Betting: each valued state's chart advantage on the shares left. Its coefficients, negated
 *   and centred, are this game's measured effects of removal, the betting start; its multiple
 *   correlation R is the ceiling of any linear count's betting correlation (the report's BC).
 * - Depth: the R squared that 1 / (cards left) adds to that regression.
 * - Insurance: each ace-up deal's insurance value, 0.5 (3 P(hole ten) - 1), on the shares after
 *   the deal, weighted P(i) / q. The relation is exact, so it must give the Ten Count with
 *   1 - R squared below 1e-12: a check of the code.
 * - First decisions: for every deal and every legal move other than the chart's, V(m) - V(chart)
 *   on the shares after the deal, weighted P(i) (1 - P(nat | i)) / q: this game's index
 *   directions. Each deal's gain is what the perfect row gains over the chart there; their sum,
 *   each move's direction weighted by its gain and turned to lean the sign reference's way, is
 *   the playing start.
 * - Later decisions: at each of the 110 contested later spots (FullIndexFit.CELLS), V(stand) -
 *   V(hit) on the shares at the decision, weighted by its reach over q, from the player trees of
 *   the states given, walked under the chart's later play. The shares at a node are read from the
 *   keys of ten indicator counts, one rank each, whose true count is exact to 1e-8 of a share.
 *   Weighted by what each spot is worth (what deviating from the chart there gains), they give the
 *   later-play direction.
 *
 * Every direction is given as real tags by rank, A and 2 to 9 and T, centred and at Hi-Lo's
 * spread: tags of a true count that rises where the regressed value does.
 *
 * java -Xmx12g -cp CLASSES:lib/mongo-java-driver-3.12.14.jar CountRegression TWIN_STATES [later shoes] [threads]
 */
final class CountRegression {

    private CountRegression() {
    }

    // ------------------------------------------------------------------ least squares

    /**
     * Weighted least squares of y on an intercept and the shares of the ace and 2 to 9 less their
     * full-shoe shares (the ten's share is what the others leave). Added up in one pass, solved,
     * then given the data again for the residuals, so 1 - R squared is exact to the double's
     * precision rather than lost in a subtraction.
     */
    static final class Wls {
        final int p;
        private final double[][] xtx;
        private final double[] xty;
        private double sw;
        private double swy;
        private double[] beta;
        private double sse;
        private double sst;
        private double mean;
        long n;

        Wls(int p) {
            this.p = p;
            xtx = new double[p + 1][p + 1];
            xty = new double[p + 1];
        }

        void add(double[] x, double y, double w) {
            if (w == 0) {
                return;
            }
            n++;
            sw += w;
            swy += w * y;
            for (int i = 0; i <= p; i++) {
                double xi = i == 0 ? 1 : x[i - 1];
                xty[i] += w * xi * y;
                for (int j = 0; j <= i; j++) {
                    double xj = j == 0 ? 1 : x[j - 1];
                    xtx[i][j] += w * xi * xj;
                }
            }
        }

        /** Solves the normal equations, by Gaussian elimination with partial pivoting. */
        double[] solve() {
            int m = p + 1;
            double[][] a = new double[m][m + 1];
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < m; j++) {
                    a[i][j] = i >= j ? xtx[i][j] : xtx[j][i];
                }
                a[i][m] = xty[i];
            }
            for (int c = 0; c < m; c++) {
                int piv = c;
                for (int r = c + 1; r < m; r++) {
                    if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) {
                        piv = r;
                    }
                }
                double[] t = a[c];
                a[c] = a[piv];
                a[piv] = t;
                if (a[c][c] == 0) {
                    throw new IllegalStateException("the regression's data do not fix every coefficient");
                }
                for (int r = 0; r < m; r++) {
                    if (r != c) {
                        double f = a[r][c] / a[c][c];
                        for (int k = c; k <= m; k++) {
                            a[r][k] -= f * a[c][k];
                        }
                    }
                }
            }
            beta = new double[m];
            for (int i = 0; i < m; i++) {
                beta[i] = a[i][m] / a[i][i];
            }
            mean = swy / sw;
            return beta;
        }

        double fitted(double[] x) {
            double v = beta[0];
            for (int i = 0; i < p; i++) {
                v += beta[i + 1] * x[i];
            }
            return v;
        }

        /** The second pass: each datum again, for the residual and total sums of squares. */
        void residual(double[] x, double y, double w) {
            if (w == 0) {
                return;
            }
            double e = y - fitted(x);
            sse += w * e * e;
            sst += w * (y - mean) * (y - mean);
        }

        double oneLessR2() {
            return sse / sst;
        }

        double r2() {
            return 1 - sse / sst;
        }

        double[] beta() {
            return beta;
        }
    }

    /** The shares of the ace and 2 to 9 left, less their full-shoe shares: the regressors. */
    static double[] shares(int[] left) {
        int n = 0;
        for (int r = 1; r <= 10; r++) {
            n += left[r];
        }
        double[] x = new double[9];
        for (int r = 1; r <= 9; r++) {
            x[r - 1] = (double) left[r] / n - CountKeys.P0[r];
        }
        return x;
    }

    /** The ranks left once deal i's three cards are out. */
    static int[] afterDeal(int[] left, int i) {
        int[] out = left.clone();
        out[Deals.P1[i]]--;
        out[Deals.P2[i]]--;
        out[Deals.UP[i]]--;
        return out;
    }

    /**
     * The tags of a true count that rises where the regressed value does, from its coefficients on
     * the shares of the ace and 2 to 9 (the ten's taken as 0): the true count is -52 sum t (p - p0),
     * so t is minus the coefficients; centred, and at Hi-Lo's spread when it varies.
     */
    static double[] tags(double[] beta) {
        double[] t = new double[11];
        for (int r = 1; r <= 9; r++) {
            t[r] = -beta[r];
        }
        return CountKeys.spread(t) > 0 ? CountKeys.normalised(t) : t;
    }

    // ------------------------------------------------------------------ the regressions

    /** The betting regression: tags, R, and the depth figure. */
    static final class Betting {
        double[] tags;
        double r;
        double r2;
        /** The R squared 1 / (cards left) adds. */
        double depth;
    }

    static Betting betting(ScoringStates st) {
        Wls fit = new Wls(9);
        Wls deep = new Wls(10);
        double[][] x = new double[st.size][];
        double[][] xd = new double[st.size][];
        for (int s = 0; s < st.size; s++) {
            int[] left = ShoeRun.ranksLeft(st.dealt[s]);
            x[s] = shares(left);
            xd[s] = java.util.Arrays.copyOf(x[s], 10);
            xd[s][9] = 1.0 / (ShoeRun.CARDS - st.depth[s]);
            fit.add(x[s], st.chart[s], st.weight[s]);
            deep.add(xd[s], st.chart[s], st.weight[s]);
        }
        fit.solve();
        deep.solve();
        for (int s = 0; s < st.size; s++) {
            fit.residual(x[s], st.chart[s], st.weight[s]);
            deep.residual(xd[s], st.chart[s], st.weight[s]);
        }
        Betting b = new Betting();
        b.tags = tags(fit.beta());
        b.r2 = fit.r2();
        b.r = Math.sqrt(b.r2);
        b.depth = deep.r2() - fit.r2();
        return b;
    }

    /** The insurance regression: its tags and 1 - R squared. */
    static double[] insurance(ScoringStates st, double[] oneLessR2) {
        Wls fit = new Wls(9);
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) {
                fit.solve();
            }
            for (int s = 0; s < st.size; s++) {
                int[] left = ShoeRun.ranksLeft(st.dealt[s]);
                for (int i = 0; i < ScoringStates.ACE_UP; i++) {
                    double p = st.chance[s][i];
                    if (p == 0) {
                        continue;
                    }
                    double[] x = shares(afterDeal(left, i));
                    double y = st.insurance[s][i] / p;
                    if (pass == 0) {
                        fit.add(x, y, p * st.weight[s]);
                    } else {
                        fit.residual(x, y, p * st.weight[s]);
                    }
                }
            }
        }
        oneLessR2[0] = fit.oneLessR2();
        return tags(fit.beta());
    }

    /** One deal and move's index direction, and what the perfect row gains there over the chart. */
    static final class Index {
        final int deal;
        final int move;
        /** The coefficients: the intercept, then the shares of the ace and 2 to 9 less p0, at [1] to [9]. */
        final double[] beta;
        final double[] tags;
        final double r2;
        /** Sum over states, weighted 1/q, of P(i) (1 - P(nat|i)) x what this move gains over the chart where it is best. */
        final double gain;

        Index(int deal, int move, double[] beta, double r2, double gain) {
            this.deal = deal;
            this.move = move;
            this.beta = beta;
            this.tags = tags(beta);
            this.r2 = r2;
            this.gain = gain;
        }
    }

    /** Every first decision's index directions. */
    static List<Index> firstDecisions(ScoringStates st) {
        int moves = ScoringStates.MOVES;
        List<Index> out = new ArrayList<>();
        int[][] left = new int[st.size][];
        double[] noNat = new double[st.size * Deals.COUNT];
        for (int s = 0; s < st.size; s++) {
            left[s] = ShoeRun.ranksLeft(st.dealt[s]);
            for (int i = 0; i < Deals.COUNT; i++) {
                noNat[s * Deals.COUNT + i] = st.chance[s][i] == 0 ? 0 : 1 - Deals.dealerNatural(left[s], i);
            }
        }
        for (int i = 0; i < Deals.COUNT; i++) {
            int c = st.chartMove[i];
            if (c < 0) {
                continue;
            }
            for (int m = 0; m < moves; m++) {
                if (m == c || !st.legal[i * moves + m]) {
                    continue;
                }
                Wls fit = new Wls(9);
                double gain = 0;
                for (int pass = 0; pass < 2; pass++) {
                    if (pass == 1) {
                        if (fit.n < 11) {
                            break;
                        }
                        fit.solve();
                    }
                    for (int s = 0; s < st.size; s++) {
                        double p = st.chance[s][i];
                        double nn = noNat[s * Deals.COUNT + i];
                        if (p == 0 || nn == 0) {
                            continue;
                        }
                        double[] mv = st.move[s];
                        double d = mv[i * moves + m] - mv[i * moves + c];
                        double w = p * nn * st.weight[s];
                        double y = d / (p * nn);
                        double[] x = shares(afterDeal(left[s], i));
                        if (pass == 0) {
                            fit.add(x, y, w);
                            boolean best = true;
                            for (int o = 0; o < moves && best; o++) {
                                if (o != m && st.legal[i * moves + o] && mv[i * moves + o] > mv[i * moves + m]) {
                                    best = false;
                                }
                            }
                            if (best && d > 0) {
                                gain += st.weight[s] * d;
                            }
                        } else {
                            fit.residual(x, y, w);
                        }
                    }
                }
                if (fit.n >= 11) {
                    out.add(new Index(i, m, fit.beta().clone(), fit.r2(), gain));
                }
            }
        }
        return out;
    }

    /**
     * Directions combined, each weighted by its gain and turned to lean the sign reference's way
     * (a count and its negative key a decision alike): the playing start, or the later-play one.
     */
    static double[] combine(List<double[]> tags, List<Double> gains, double[] sign) {
        double[] sum = new double[11];
        for (int k = 0; k < tags.size(); k++) {
            double[] t = tags.get(k);
            double n = Math.sqrt(CountSearch.dot(t, t));
            if (!(n > 0) || !(gains.get(k) > 0)) {
                continue;
            }
            double f = gains.get(k) / n * (CountSearch.dot(t, sign) < 0 ? -1 : 1);
            for (int r = 1; r <= 10; r++) {
                sum[r] += f * t[r];
            }
        }
        return CountKeys.normalised(sum);
    }

    /** One later spot's direction and worth. */
    static final class Spot {
        final int cell;
        final double[] tags;
        final double r2;
        final double worth;

        Spot(int cell, double[] tags, double r2, double worth) {
            this.cell = cell;
            this.tags = tags;
            this.r2 = r2;
            this.worth = worth;
        }
    }

    /** The tag of an indicator count: big, so its true count gives the share to 1e-8. */
    static final int INDICATOR = 1_000_000;

    /**
     * The later decisions' directions, from the trees of the states given (the first states of the
     * twin), walked under the chart's later play, each node's shares read from ten indicator counts.
     */
    static List<Spot> laterDecisions(ScoringStates st, int[] states, FullIndexFit.Trees trees, RoundRules rules,
                                     int threads) throws Exception {
        CountKeys.Count[] indicator = new CountKeys.Count[11];
        for (int r = 1; r <= 10; r++) {
            int[] t = new int[11];
            t[r] = INDICATOR;
            indicator[r] = CountKeys.whole("rank " + r, t, null, 1, true);
        }
        int cells = FullIndexFit.CELLS;
        Wls[] fits = new Wls[cells];
        double[] worth = new double[cells];
        for (int c = 0; c < cells; c++) {
            fits[c] = new Wls(9);
        }
        // Each state's nodes: cell, shares, gap per reach, weight; added in state order so the sums do not depend on the threads.
        List<List<double[]>> nodes = new ArrayList<>(Collections.nCopies(states.length, null));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
            List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
            for (int t = 0; t < Math.max(1, threads); t++) {
                jobs.add(pool.submit(() -> {
                    ExactRound engine = new ExactRound(rules);
                    for (int k = next.getAndIncrement(); k < states.length; k = next.getAndIncrement()) {
                        int s = states[k];
                        PlayerTree tree = trees.of(s, engine);
                        List<double[]> mine = new ArrayList<>();
                        List<int[]> keys = new ArrayList<>();
                        for (int r = 1; r <= 10; r++) {
                            int rr = r;
                            int[] at = {0};
                            PlayerTree.Walk walk = tree.count(indicator[r], st.dealt[s]).walk(PlayerTree.CHART,
                                    PlayerTree.Mode.CONTESTED);
                            // Reach comes from the deals whose first move, the chart's, is Hit.
                            walk.reach((deal, part) -> st.chartMove[deal] == SystemScorer.HIT, 1);
                            walk.decisions((spot, key, reach, gap) -> {
                                        int cell = FullIndexFit.cellOf(spot);
                                        if (rr == 1) {
                                            mine.add(new double[]{cell, reach, gap});
                                            keys.add(new int[11]);
                                        }
                                        keys.get(at[0]++)[rr] = key;
                                    });
                        }
                        List<double[]> out = new ArrayList<>();
                        for (int j = 0; j < mine.size(); j++) {
                            double[] m = mine.get(j);
                            if ((int) m[0] < 0 || m[1] <= 0) {
                                continue;
                            }
                            double[] x = new double[9];
                            for (int r = 1; r <= 9; r++) {
                                // p - p0 = -TC / (52 x tag), the indicator's true count at the node.
                                x[r - 1] = -keys.get(j)[r] / (52.0 * INDICATOR);
                            }
                            double[] row = new double[12];
                            row[0] = m[0];
                            System.arraycopy(x, 0, row, 1, 9);
                            row[10] = m[2] / m[1];
                            row[11] = m[1] * st.weight[s];
                            out.add(row);
                        }
                        nodes.set(k, out);
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
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) {
                for (Wls f : fits) {
                    if (f.n >= 11) {
                        f.solve();
                    }
                }
            }
            for (List<double[]> list : nodes) {
                for (double[] row : list) {
                    int cell = (int) row[0];
                    double[] x = java.util.Arrays.copyOfRange(row, 1, 10);
                    if (pass == 0) {
                        fits[cell].add(x, row[10], row[11]);
                        // What deviating from the chart gains at this node.
                        double gap = row[10];
                        worth[cell] += row[11] * Math.max(0, FullIndexFit.chartStands(cell) ? -gap : gap);
                    } else if (fits[cell].n >= 11) {
                        fits[cell].residual(x, row[10], row[11]);
                    }
                }
            }
        }
        List<Spot> out = new ArrayList<>();
        for (int c = 0; c < cells; c++) {
            if (fits[c].n >= 11) {
                out.add(new Spot(c, tags(fits[c].beta()), fits[c].r2(), worth[c]));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the command

    static String text(double[] t) {
        StringBuilder b = new StringBuilder();
        for (int r = 1; r <= 10; r++) {
            b.append(r == 1 ? "" : ", ").append(String.format("%+.4f", t[r]));
        }
        return b.toString();
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path file = Paths.get(args[0]);
        int laterShoes = args.length > 1 ? Integer.parseInt(args[1]) : 2000;
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 6;
        CustomCounts.requireTwin(file);
        PrintStream out = System.out;
        long t0 = System.nanoTime();
        ScoringStates st = FullIndexPilot.load(file, StateStore.shoesDone(file));
        double[] hiLo = CountSearch.rankTags(CountSearch.catalogCount("Hi-Lo"));
        out.printf("# The theory pass%n%nCountRegression %s on %s: %d valued states of %d shoes, header seed %d. Tags are "
                        + "A, 2 to 9, T, centred and at Hi-Lo's spread.%n%n", FullIndexRun.commit(), file, st.size, st.shoes,
                StateStore.header(file).seed);
        Betting b = betting(st);
        out.printf("Betting: chart advantage on the shares left, R = %.5f (R squared %.5f): %s.%n", b.r, b.r2, text(b.tags));
        out.printf("Depth: 1 / (cards left) adds %.6f to R squared.%n", b.depth);
        double[] ins = new double[1];
        double[] insTags = insurance(st, ins);
        out.printf("Insurance: 1 - R squared = %.3e: %s.%n", ins[0], text(insTags));
        List<Index> first = firstDecisions(st);
        List<double[]> dirs = new ArrayList<>();
        List<Double> gains = new ArrayList<>();
        for (Index x : first) {
            dirs.add(x.tags);
            gains.add(x.gain);
        }
        out.printf("First decisions: %d index directions; the playing start: %s.%n", first.size(),
                text(combine(dirs, gains, hiLo)));
        int[] states = java.util.stream.IntStream.range(0, st.size).filter(s -> st.shoe[s] < laterShoes).toArray();
        List<Spot> later = laterDecisions(st, states, FullIndexFit.built(st), StateRun.montreal(), threads);
        dirs.clear();
        gains.clear();
        for (Spot x : later) {
            dirs.add(x.tags);
            gains.add(x.worth);
        }
        out.printf("Later decisions: %d of %d contested spots regressed on the trees of the %d states of the first %d "
                        + "shoes; the later-play direction: %s.%n", later.size(), FullIndexFit.CELLS, states.length,
                laterShoes, text(combine(dirs, gains, hiLo)));
        out.printf("%nIn %.0f s.%n", (System.nanoTime() - t0) / 1e9);
    }
}
