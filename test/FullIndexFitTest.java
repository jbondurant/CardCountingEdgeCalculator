import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The full-index fit (FULL_INDEX_PLAYS.md sections 3 and 4) on hand-made states whose right
 * plays the test works out for itself. Even shoes are ten-rich, most of their 2s to 6s dealt,
 * and odd shoes ten-poor, most of their tens dealt, so the two folds want opposite later plays:
 * the fold fitted on ten-poor shoes hits stiff hands the chart stands on, and the one fitted on
 * ten-rich shoes stands where the chart hits. The states are valued as the run values its
 * states, by StateValuer with the chart after the first move.
 *
 * Every check works the play out again from walks of the test's own: its own later play read
 * from the fit's groups and cells, its own first moves for reach, its own Hit values from the
 * player tree, its own Split values from SplitValues under its own post-split play, and its own
 * sums by group. The player tree itself is held to every card order by PlayerTreeOrderTest.
 */
public class FullIndexFitTest {

    private static final RoundRules RULES = StateRun.montreal();
    private static final double EXACT = 1e-12;
    private static final int MOVES = ScoringStates.MOVES;
    private static final int HIT = SystemScorer.HIT;
    private static final int SPLIT = SystemScorer.SPLIT;
    /** The full-index fits are made at 80% only; every state is in it. */
    private static final int PEN = 2;
    private static final int SHOES = 16;
    private static final int THREADS = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 1));

    static final CountKeys.Count HI_LO = PlayerTreeOrderTest.named("Hi-Lo");
    static final CountKeys.Count RED_SEVEN = PlayerTreeOrderTest.named("Red Seven (Red 7)");
    static final CountKeys.Count NULL = CountKeys.nullCount();

    // ------------------------------------------------------------------ the states

    /**
     * Cards dealt by kind: a ten-rich shoe has 9 to 16 of each kind of 2 to 6 dealt, a
     * ten-poor one 8 to 16 of each kind of ten, and up to two more of every kind go at random,
     * so every state has keys of its own.
     */
    static int[] dealt(Random rnd, boolean tenRich) {
        int[] d = new int[ShoeRun.KINDS];
        if (tenRich) {
            for (int k = 2; k < 12; k++) {
                d[k] = 9 + rnd.nextInt(8);
            }
        } else {
            for (int k = 18; k < 26; k++) {
                d[k] = 8 + rnd.nextInt(9);
            }
        }
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            d[k] = Math.min(2 * ShoeRun.DECKS, d[k] + rnd.nextInt(3));
        }
        return d;
    }

    /** Records for these cards dealt, valued by StateValuer with the chart after the first move. */
    static List<StateStore.Record> valued(List<int[]> dealt, int[] shoe, int[] round, double[] q) throws Exception {
        List<int[]> shoes = new ArrayList<>();
        for (int[] d : dealt) {
            shoes.add(ShoeRun.ranksLeft(d));
        }
        List<double[]> values;
        try (StateValuer v = new StateValuer(RULES, BasicStrategy.chart(), THREADS)) {
            values = v.value(shoes);
        }
        List<StateStore.Record> out = new ArrayList<>();
        for (int k = 0; k < dealt.size(); k++) {
            int depth = Arrays.stream(dealt.get(k)).sum();
            out.add(new StateStore.Record(shoe[k], round[k], depth, q[k], dealt.get(k), values.get(k)));
        }
        return out;
    }

    /** States, their scorer and their kept trees. */
    static final class Fixture {
        final List<StateStore.Record> records;
        final ScoringStates st;
        final SystemScorer scorer;
        final FullIndexFit.Trees trees;

        Fixture(List<StateStore.Record> records, int shoes, int resamples, double mPlay) throws Exception {
            this.records = records;
            this.st = new ScoringStates(new ArrayList<>(records), shoes, RULES);
            this.scorer = new SystemScorer(st, new Bootstrap(shoes, resamples, 1), mPlay);
            this.trees = FullIndexFit.kept(st, RULES, THREADS);
        }
    }

    private static Fixture standard;

    /**
     * Sixteen shoes, ten-rich and ten-poor in turn. Shoes 12 to 15 hold a second state, a few
     * more cards dealt, so a shoe's states are summed together for the standard errors; the
     * chances of valuing a state run from 0.25 to 1, so every sum is weighted.
     */
    static synchronized Fixture standard() throws Exception {
        if (standard == null) {
            Random rnd = new Random(7);
            List<int[]> dealt = new ArrayList<>();
            List<Integer> shoe = new ArrayList<>();
            List<Integer> round = new ArrayList<>();
            List<Double> q = new ArrayList<>();
            double[] chances = {1, 0.5, 0.25, 0.8};
            for (int s = 0; s < SHOES; s++) {
                int[] d = dealt(rnd, s % 2 == 0);
                dealt.add(d);
                shoe.add(s);
                round.add(0);
                q.add(chances[s % 4]);
                if (s >= 12) {
                    int[] more = d.clone();
                    for (int k = 0; k < 12; k++) {
                        int kind = rnd.nextInt(ShoeRun.KINDS);
                        more[kind] = Math.min(2 * ShoeRun.DECKS, more[kind] + 1);
                    }
                    dealt.add(more);
                    shoe.add(s);
                    round.add(1);
                    q.add(chances[(s + 1) % 4]);
                }
            }
            standard = new Fixture(valued(dealt, shoe.stream().mapToInt(Integer::intValue).toArray(),
                    round.stream().mapToInt(Integer::intValue).toArray(),
                    q.stream().mapToDouble(Double::doubleValue).toArray()), SHOES, 20, 1);
        }
        return standard;
    }

    /** Counts fitted on a fixture's states at 80%, with or without their half fits, under caps on the passes, and scored. */
    static final class Run {
        final Fixture fx;
        final List<CountKeys.Count> counts;
        final Map<CountKeys.Count, FullIndexFit.Fits> fits = new HashMap<>();
        final Map<CountKeys.Count, SystemScorer.Result> results = new HashMap<>();
        final FullIndexFit fit;

        Run(Fixture fx, List<CountKeys.Count> counts, int maxPasses, int cleanUps, boolean halves) throws Exception {
            this(fx, fx.scorer, counts, maxPasses, cleanUps, halves);
        }

        Run(Fixture fx, SystemScorer scorer, List<CountKeys.Count> counts, int maxPasses, int cleanUps, boolean halves)
                throws Exception {
            this.fx = fx;
            this.counts = counts;
            List<FullIndexFit.Fits> planned = FullIndexFit.plan(scorer, counts, new int[]{PEN}, halves);
            List<FullIndexFit.Strategy> all = new ArrayList<>();
            for (FullIndexFit.Fits f : planned) {
                for (FullIndexFit.Strategy t : f.all()) {
                    t.maxPasses = maxPasses;
                    t.cleanUpLimit = cleanUps;
                    all.add(t);
                }
                fits.put(f.count, f);
            }
            fit = new FullIndexFit(fx.st, RULES, fx.trees, THREADS, true, true);
            fit.run(all);
            for (FullIndexFit.Fits f : planned) {
                results.put(f.count, scorer.score(f.count, null, f));
            }
        }

        List<FullIndexFit.Strategy> strategies() {
            List<FullIndexFit.Strategy> out = new ArrayList<>();
            for (CountKeys.Count c : counts) {
                out.addAll(fits.get(c).all());
            }
            return out;
        }
    }

    private static Run run;
    private static Run capped;

    /** The null count, Hi-Lo and Red Seven on the standard states, with their half fits, as the run fits them. */
    static synchronized Run run() throws Exception {
        if (run == null) {
            run = new Run(standard(), Arrays.asList(NULL, HI_LO, RED_SEVEN), FullIndexFit.MAX_PASSES,
                    FullIndexFit.CLEAN_UPS, true);
        }
        return run;
    }

    /** The null count and Hi-Lo stopped at the cap after pass 0 with no clean-up, so the play kept is pass 0's. */
    static synchronized Run capped() throws Exception {
        if (capped == null) {
            capped = new Run(standard(), Arrays.asList(NULL, HI_LO), 1, 0, true);
        }
        return capped;
    }

    // ------------------------------------------------------------------ the test's own walks

    /** P(i) x (-P(nat|i) + (1 - P(nat|i)) x v): a conditional value in the units of ScoringStates.move. */
    static double units(int[] left, int deal, double v) {
        double p = Deals.probability(left, deal);
        double dn = Deals.dealerNatural(left, deal);
        return p * (-dn + (1 - dn) * v);
    }

    /** Whether the later play kept stands on a hand of three or more cards, as the test reads the fit's cells. */
    static boolean stands(SystemScorer.Groups later, boolean[] cells, int total, boolean soft, int up, int key) {
        if (cells == null || !PlayerTree.contested(total, soft)) {
            return PlayerTree.CHART.stands(total, soft, up, key);
        }
        return cells[later.of(key) * FullIndexFit.CELLS + FullIndexFit.cellOf(PlayerTree.spot(total, soft, up))];
    }

    /** The first-decision group of a part's key under a strategy's groups. */
    static int group(FullIndexFit.Strategy t, int x) {
        return t.table[t.parts.key[x] - t.parts.minKey];
    }

    /**
     * Each part's Hit and Split value, in the units of ScoringStates.move, on the states given
     * (local to the penetration), under a play: the later cells given in the later groups
     * given, and the first-decision rankings given in t's first-decision groups for split hands,
     * the chart's where none are given. NaN where not worked out.
     */
    static double[][] values(Fixture fx, FullIndexFit.Strategy t, int[] states, SystemScorer.Groups later,
                             boolean[] cells, PlayerMove[][][] ranking) throws Exception {
        double[] hit = new double[t.parts.key.length];
        double[] split = new double[t.parts.key.length];
        Arrays.fill(hit, Double.NaN);
        Arrays.fill(split, Double.NaN);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(THREADS);
        try {
            List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
            for (int j : states) {
                jobs.add(pool.submit(() -> {
                    values(fx, t, j, later, cells, ranking, hit, split);
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> job : jobs) {
                job.get();
            }
        } finally {
            pool.shutdown();
        }
        return new double[][]{hit, split};
    }

    /**
     * Split values valued once per state and play, kept by SplitValues' own fingerprint of the
     * play's answers (SplitValuesTest holds it), since the tests value the same plays many times.
     */
    private static final Map<Integer, Map<String, Double>> SPLITS = new java.util.concurrent.ConcurrentHashMap<>();

    private static void values(Fixture fx, FullIndexFit.Strategy t, int j, SystemScorer.Groups later, boolean[] cells,
                               PlayerMove[][][] ranking, double[] hit, double[] split) {
        if (fx != standard) {
            throw new IllegalArgumentException("the split values kept are the standard states'");
        }
        {
            ExactRound engine = new ExactRound(RULES);
            int s = t.p.states[j];
            int[] left = ShoeRun.ranksLeft(fx.st.dealt[s]);
            PlayerTree.Counted counted = fx.trees.of(s, engine).count(t.count, fx.st.dealt[s]);
            PlayerTree.Walk walk = counted.walk((total, soft, up, key) -> stands(later, cells, total, soft, up, key),
                    PlayerTree.Mode.CONTESTED);
            Map<String, Double> kept = SPLITS.computeIfAbsent(s, k -> new java.util.concurrent.ConcurrentHashMap<>());
            SplitValues sv = new SplitValues(RULES, left, fx.records.get(s).values, kept, engine);
            for (int i = 0; i < Deals.COUNT; i++) {
                if (Deals.probability(left, i) == 0 || Deals.playerNatural(i)) {
                    continue;
                }
                int cell = j * Deals.COUNT + i;
                int from = t.parts.from(cell);
                assertEquals(counted.parts(i), t.parts.to(cell) - from);
                for (int q = 0; q < counted.parts(i); q++) {
                    hit[from + q] = units(left, i, walk.hit(i, q));
                }
                if (!fx.st.legal[i * MOVES + SPLIT]) {
                    continue;
                }
                int r = Deals.P1[i];
                int up = Deals.UP[i];
                for (int x = from; x < t.parts.to(cell); x++) {
                    int key = t.parts.key[x];
                    PlayerMove[][] rank = ranking == null ? null : ranking[group(t, x)];
                    SplitPlay play = new SplitPlay(r, up, (total, soft) -> stands(later, cells, total, soft, up, key),
                            d -> rank == null ? SplitValuesTest.chartRanking(d) : rank[d]);
                    split[x] = units(left, i, sv.value(i, play));
                }
            }
        }
    }

    /** The value of move m of deal i in part x: the stored entry, or the Hit and Split values given. */
    static double value(Fixture fx, int s, int i, int x, int m, double[][] hs) {
        if (m == HIT) {
            return hs[0][x];
        }
        if (m == SPLIT) {
            return hs[1][x];
        }
        return fx.st.move[s][i * MOVES + m];
    }

    /** First-decision sums by (group, deal x MOVES + move) in t's groups, [groups] of reach by deal at the end. */
    static double[][][] firstSums(Fixture fx, FullIndexFit.Strategy t, int[] states, double[][] hs) {
        int groups = t.groups.count();
        double[][] sum = new double[groups][Deals.COUNT * MOVES];
        double[][] reach = new double[groups][Deals.COUNT];
        for (int j : states) {
            int s = t.p.states[j];
            double wj = t.w[j];
            for (int i = 0; i < Deals.COUNT; i++) {
                double p = fx.st.chance[s][i];
                if (p == 0) {
                    continue;
                }
                int cell = j * Deals.COUNT + i;
                for (int x = t.parts.from(cell); x < t.parts.to(cell); x++) {
                    int g = group(t, x);
                    double ws = wj * t.parts.share(x);
                    reach[g][i] += ws * p;
                    if (Deals.playerNatural(i)) {
                        continue;
                    }
                    for (int m = 0; m < MOVES; m++) {
                        if (fx.st.legal[i * MOVES + m]) {
                            sum[g][i * MOVES + m] += ws * value(fx, s, i, x, m, hs);
                        }
                    }
                }
            }
        }
        return new double[][][]{sum, reach};
    }

    /** The best move of a deal from sums: the chart's where nothing reaches it or it ties for best. */
    static int best(Fixture fx, double[] sum, double reach, int i) {
        int chart = fx.st.chartMove[i];
        if (chart < 0 || reach == 0) {
            return chart;
        }
        int best = chart;
        for (int m = 0; m < MOVES; m++) {
            if (fx.st.legal[i * MOVES + m] && sum[i * MOVES + m] > sum[i * MOVES + best]) {
                best = m;
            }
        }
        return best;
    }

    /** The later sums of a play on some states, by (later group x CELLS + cell). */
    static final class LaterSums {
        final double[] gap;
        final double[] reach;
        /** Per shoe, its gap and reach sums by (group, cell). */
        final Map<Integer, double[][]> shoe = new HashMap<>();
        /** Per node a play is asked at: [cell index into gap, reach, gap], for the unanimity checks. */
        final List<double[]> nodes = new ArrayList<>();

        LaterSums(int cells) {
            gap = new double[cells];
            reach = new double[cells];
        }
    }

    /**
     * Walks the states given under a play, the first moves given in t's first-decision groups
     * for reach, and adds up weight x reach x (V_stand - V_hit) and weight x reach by the later
     * group of the decision's key and its cell.
     */
    static LaterSums laterSums(Fixture fx, FullIndexFit.Strategy t, int[] states, SystemScorer.Groups later,
                               boolean[] cells, int[][] first) {
        return laterSums(fx, t, states, later, cells, first, false);
    }

    /** The same, the per-shoe sums kept per state instead when byState, as if each state were a shoe of its own. */
    static LaterSums laterSums(Fixture fx, FullIndexFit.Strategy t, int[] states, SystemScorer.Groups later,
                               boolean[] cells, int[][] first, boolean byState) {
        LaterSums out = new LaterSums(later.count() * FullIndexFit.CELLS);
        ExactRound engine = new ExactRound(RULES);
        for (int j : states) {
            int s = t.p.states[j];
            PlayerTree.Counted counted = fx.trees.of(s, engine).count(t.count, fx.st.dealt[s]);
            PlayerTree.Walk walk = counted.walk((total, soft, up, key) -> stands(later, cells, total, soft, up, key),
                    PlayerTree.Mode.CONTESTED);
            int base = j * Deals.COUNT;
            walk.reach((deal, part) -> first[group(t, t.parts.from(base + deal) + part)][deal] == HIT, t.w[j]);
            double[][] perShoe = out.shoe.computeIfAbsent(byState ? s : fx.st.shoe[s], k -> new double[2][out.gap.length]);
            walk.decisions((spot, key, r, g) -> {
                int at = later.of(key) * FullIndexFit.CELLS + FullIndexFit.cellOf(spot);
                out.gap[at] += g;
                out.reach[at] += r;
                perShoe[0][at] += g;
                perShoe[1][at] += r;
                out.nodes.add(new double[]{at, r, g});
            });
        }
        return out;
    }

    /** The training EV per round of a play: first moves and Hit and Split values given, t's insurance. */
    static double ev(Fixture fx, FullIndexFit.Strategy t, int[] states, int[][] first, double[][] hs) {
        double num = 0;
        double den = 0;
        for (int j : states) {
            int s = t.p.states[j];
            double v = fx.st.naturals[s];
            for (int i = 0; i < Deals.COUNT; i++) {
                if (fx.st.chance[s][i] == 0 || Deals.playerNatural(i)) {
                    continue;
                }
                int cell = j * Deals.COUNT + i;
                for (int x = t.parts.from(cell); x < t.parts.to(cell); x++) {
                    v += t.parts.share(x) * value(fx, s, i, x, first[group(t, x)][i], hs);
                }
            }
            num += t.w[j] * (v + t.insurance[j]);
            den += t.w[j];
        }
        return num / den;
    }

    /** Every (group, deal)'s best move from first sums. */
    static int[][] moves(Fixture fx, double[][][] fs) {
        int[][] out = new int[fs[0].length][Deals.COUNT];
        for (int g = 0; g < out.length; g++) {
            for (int i = 0; i < Deals.COUNT; i++) {
                out[g][i] = best(fx, fs[0][g], fs[1][g][i], i);
            }
        }
        return out;
    }

    /** The local indices of every state of the penetration. */
    static int[] every(FullIndexFit.Strategy t) {
        int[] out = new int[t.p.states.length];
        for (int j = 0; j < out.length; j++) {
            out[j] = j;
        }
        return out;
    }

    // ------------------------------------------------------------------ the fitted play

    /**
     * Every strategy's kept play, worked out again from the test's own walks. On every state of
     * the penetration each part's Hit and Split values are the ones the strategy holds, and the
     * training EV of the last pass that walked the play is the test's. Where the passes
     * converged, the play is the best joint play on its training states:
     * - each later cell stands exactly where its summed reach x (V_stand - V_hit) is positive,
     *   and plays the chart where no training state reaches it;
     * - each first decision is the best move by its sums, Hit valued by the tree under the later
     *   play and Split under the group's own post-split play, the chart's where nothing reaches
     *   it, and its moves are ranked best first by the same sums, as split hands read them.
     * And the joint fit matters here: later cells leave the chart, and first decisions leave
     * the fit from the stored values.
     */
    @Test
    public void theFittedPlayIsTheBestJointPlayOnItsTrainingStates() throws Exception {
        Run r = run();
        Fixture fx = r.fx;
        int converged = 0;
        int offChart = 0;
        int firstMoved = 0;
        int noReach = 0;
        int valuesChecked = 0;
        for (FullIndexFit.Strategy t : r.strategies()) {
            FullIndexFit.Policy pol = t.now;
            double[][] hs = values(fx, t, every(t), t.later, pol.stands, pol.ranking);
            for (int x = 0; x < hs[0].length; x++) {
                if (!Double.isNaN(hs[0][x])) {
                    assertEquals(hs[0][x], t.values.hit[x], 1e-15, t + " Hit, part " + x);
                    valuesChecked++;
                }
                if (!Double.isNaN(hs[1][x])) {
                    assertEquals(hs[1][x], t.values.split[x], 1e-15, t + " Split, part " + x);
                }
            }
            FullIndexFit.PassRecord last = t.history.get(t.history.size() - 1);
            LaterSums ls = laterSums(fx, t, t.use, t.later, pol.stands, pol.first.move);
            int unreached = 0;
            for (double reach : ls.reach) {
                unreached += reach == 0 ? 1 : 0;
            }
            assertEquals(unreached, t.laterNoReach, t + " later cells with no reach");
            if (!t.converged) {
                continue;
            }
            assertEquals(ev(fx, t, t.use, pol.first.move, hs), last.ev, EXACT, t + " training EV");
            converged++;
            for (int at = 0; at < pol.stands.length; at++) {
                boolean chart = FullIndexFit.chartStands(at % FullIndexFit.CELLS);
                if (ls.reach[at] == 0) {
                    assertEquals(chart, pol.stands[at], t + " later cell " + at + " has no reach");
                    noReach++;
                } else if (ls.gap[at] != 0) {
                    assertEquals(ls.gap[at] > 0, pol.stands[at], t + " later cell " + at + ": sum " + ls.gap[at]);
                }
                offChart += pol.stands[at] != chart ? 1 : 0;
            }
            double[][][] fs = firstSums(fx, t, t.use, hs);
            SystemScorer.Fit stored = fx.scorer.fit(t.parts, t.p, t.use, t.groups, t.table, t.w);
            int firstUnreached = 0;
            for (int g = 0; g < t.groups.count(); g++) {
                for (int i = 0; i < Deals.COUNT; i++) {
                    if (Deals.playerNatural(i)) {
                        continue;
                    }
                    firstUnreached += fs[1][g][i] == 0 ? 1 : 0;
                    int m = best(fx, fs[0][g], fs[1][g][i], i);
                    assertEquals(m, pol.first.move[g][i], t + " group " + g + " deal " + i);
                    firstMoved += fs[1][g][i] > 0 && m != stored.move[g][i] ? 1 : 0;
                    assertArrayEquals(ranking(fx, fs[0][g], fs[1][g][i], i), pol.ranking[g][i],
                            t + " group " + g + " deal " + i);
                }
            }
            assertEquals(firstUnreached, t.firstNoReach, t + " first decisions with no reach");
        }
        System.out.printf("FullIndexFitTest: %d strategies converged; %d later cells off the chart, %d with no reach;"
                + " %d first decisions moved from the stored fit; %d Hit values checked%n", converged, offChart, noReach,
                firstMoved, valuesChecked);
        assertTrue(converged >= 4, converged + " strategies converged");
        assertTrue(offChart >= 50 && firstMoved >= 10 && noReach >= 10, offChart + " " + firstMoved + " " + noReach);
    }

    /**
     * The later-play groups are grown from pass 0's reach on the training states, as the
     * first-decision groups are from the deals' chances: each training state is mass 1, spread
     * over the keys of its later decisions by its reach there normalised to 1; the walk starts at
     * the key the most rounds reach, reach weighted 1/q, and a group closes at M_play = mPlay x
     * sqrt(n) valued states, n the training states. The test works the masses out from its own
     * walks under pass 0's play, the stored first-decision fit with the chart after, and walks
     * the groups as SystemScorerTest walks the first-decision ones.
     */
    @Test
    public void laterGroupsAreGrownFromPassZerosReach() throws Exception {
        Run r = run();
        Fixture fx = r.fx;
        ExactRound engine = new ExactRound(RULES);
        int checked = 0;
        int most = 0;
        for (FullIndexFit.Strategy t : r.strategies()) {
            SystemScorer.Fit f0 = fx.scorer.fit(t.parts, t.p, t.use, t.groups, t.table, t.w);
            java.util.TreeMap<Integer, double[]> byKey = new java.util.TreeMap<>();
            for (int j : t.use) {
                int s = t.p.states[j];
                PlayerTree.Walk walk = fx.trees.of(s, engine).count(t.count, fx.st.dealt[s])
                        .walk(PlayerTree.CHART, PlayerTree.Mode.CONTESTED);
                int base = j * Deals.COUNT;
                walk.reach((deal, part) -> f0.move[group(t, t.parts.from(base + deal) + part)][deal] == HIT, t.w[j]);
                List<double[]> nodes = new ArrayList<>();
                double[] total = {0};
                walk.decisions((spot, key, reach, gap) -> {
                    nodes.add(new double[]{key, reach});
                    total[0] += reach;
                });
                for (double[] node : nodes) {
                    double[] e = byKey.computeIfAbsent((int) node[0], k -> new double[2]);
                    e[0] += node[1] / total[0];
                    e[1] += node[1];
                }
            }
            int lo = byKey.firstKey();
            int hi = byKey.lastKey();
            double[] units = new double[hi - lo + 1];
            double[] rounds = new double[hi - lo + 1];
            for (Map.Entry<Integer, double[]> e : byKey.entrySet()) {
                units[e.getKey() - lo] = e.getValue()[0];
                rounds[e.getKey() - lo] = e.getValue()[1];
            }
            List<int[]> want = SystemScorerTest.walk(units, rounds, fx.scorer.mPlay * Math.sqrt(t.use.length));
            assertEquals(want.size(), t.later.count(), t.toString());
            for (int g = 0; g < want.size(); g++) {
                assertEquals(want.get(g)[0] + lo, t.later.lo[g], t + " group " + g);
                assertEquals(want.get(g)[1] + lo, t.later.hi[g], t + " group " + g);
            }
            most = Math.max(most, want.size());
            checked++;
        }
        assertTrue(checked == r.strategies().size() && most >= 2, most + " groups at most");
    }

    /** A deal's legal moves best first by its sums, the chart's ranking where nothing reaches it. */
    static PlayerMove[] ranking(Fixture fx, double[] sum, double reach, int i) {
        if (reach == 0) {
            return FullIndexFit.chartRanking(RULES, i);
        }
        int top = best(fx, sum, reach, i);
        List<Integer> rest = new ArrayList<>();
        for (int m = 0; m < MOVES; m++) {
            if (m != top && fx.st.legal[i * MOVES + m]) {
                rest.add(m);
            }
        }
        rest.sort((a, b) -> Double.compare(sum[i * MOVES + b], sum[i * MOVES + a]));
        PlayerMove[] out = new PlayerMove[rest.size() + 1];
        out[0] = StateValuer.MOVES[top];
        for (int k = 0; k < rest.size(); k++) {
            out[k + 1] = StateValuer.MOVES[rest.get(k)];
        }
        return out;
    }

    /**
     * Plays known without pooling. Under the null count every hand of a spot is in its one
     * later cell, so where every node the play is asked at, in every training state, would
     * rather stand (or every one rather hit), the cell must stand (or hit) whatever the reach.
     * The same for a first decision every training state would rather make one way. On these
     * shoes such cells are many and some of them are where the chart plays the other way: the
     * fold fitted on ten-poor shoes hits stiff hands the chart stands on, and the fold fitted
     * on ten-rich shoes stands on hands the chart hits.
     */
    @Test
    public void playsEveryTrainingStateAgreesOnAreFitted() throws Exception {
        Run r = run();
        Fixture fx = r.fx;
        for (int f = 0; f < 2; f++) {
            FullIndexFit.Strategy t = r.fits.get(NULL).fold[PEN][f];
            FullIndexFit.Policy pol = t.now;
            LaterSums ls = laterSums(fx, t, t.use, t.later, pol.stands, pol.first.move);
            int cells = FullIndexFit.CELLS;
            int[] sign = new int[cells];
            boolean[] mixed = new boolean[cells];
            for (double[] node : ls.nodes) {
                int at = (int) node[0];
                if (node[1] == 0 || node[2] == 0) {
                    continue;
                }
                int s = node[2] > 0 ? 1 : -1;
                mixed[at] |= sign[at] != 0 && sign[at] != s;
                sign[at] = s;
            }
            int known = 0;
            int knownAgainstChart = 0;
            for (int at = 0; at < cells; at++) {
                if (sign[at] == 0 || mixed[at]) {
                    continue;
                }
                assertEquals(sign[at] > 0, pol.stands[at], t + " cell " + at);
                known++;
                knownAgainstChart += pol.stands[at] != FullIndexFit.chartStands(at) ? 1 : 0;
            }
            // First decisions: each training state's own best move per deal, and the fit's where all agree.
            double[][] hs = values(fx, t, t.use, t.later, pol.stands, pol.ranking);
            int[] agreed = new int[Deals.COUNT];
            Arrays.fill(agreed, -2);
            for (int j : t.use) {
                int s = t.p.states[j];
                for (int i = 0; i < Deals.COUNT; i++) {
                    if (fx.st.chance[s][i] == 0 || Deals.playerNatural(i)) {
                        continue;
                    }
                    int x = t.parts.from(j * Deals.COUNT + i);
                    int top = -1;
                    for (int m = 0; m < MOVES; m++) {
                        if (fx.st.legal[i * MOVES + m] && (top < 0 || value(fx, s, i, x, m, hs) > value(fx, s, i, x, top, hs))) {
                            top = m;
                        }
                    }
                    agreed[i] = agreed[i] == -2 || agreed[i] == top ? top : -1;
                }
            }
            int knownFirst = 0;
            for (int i = 0; i < Deals.COUNT; i++) {
                if (agreed[i] >= 0) {
                    assertEquals(agreed[i], pol.first.move[0][i], t + " deal " + i);
                    knownFirst++;
                }
            }
            System.out.printf("FullIndexFitTest: %s: %d later cells every node agrees on, %d of them against the chart;"
                    + " %d first decisions every state agrees on%n", t, known, knownAgainstChart, knownFirst);
            assertTrue(known >= 40 && knownAgainstChart >= 3 && knownFirst >= 100,
                    t + ": " + known + " " + knownAgainstChart + " " + knownFirst);
        }
    }

    // ------------------------------------------------------------------ never on its own fold

    /**
     * Each fold's test states are scored with the later plays fitted on the other fold: the
     * null count's full-play estimate is the test's own, with fold f's first decisions fitted on
     * its training states under fold f's fit and its test states valued under the same fit. The
     * folds want opposite later plays here, so scoring a test state with the later plays fitted
     * on its own fold would move the estimate by far more than rounding.
     */
    @Test
    public void aTestStateIsScoredWithTheLaterPlaysFittedOnTheOtherFold() throws Exception {
        Run r = run();
        Fixture fx = r.fx;
        FullIndexFit.Fits fits = r.fits.get(NULL);
        SystemScorer.Pen pen = fx.scorer.pen(PEN);
        double num = 0;
        double leaked = 0;
        double den = 0;
        for (int f = 0; f < 2; f++) {
            FullIndexFit.Strategy t = fits.fold[PEN][f];
            FullIndexFit.Strategy other = fits.fold[PEN][1 - f];
            double[][] train = values(fx, t, pen.train[f], t.later, t.now.stands, t.now.ranking);
            int[][] first = moves(fx, firstSums(fx, t, pen.train[f], train));
            double[][] own = values(fx, t, pen.test[f], t.later, t.now.stands, t.now.ranking);
            double[][] wrong = values(fx, other, pen.test[f], other.later, other.now.stands, other.now.ranking);
            for (int j : pen.test[f]) {
                int s = pen.states[j];
                double a = fx.st.naturals[s];
                double b = a;
                for (int i = 0; i < Deals.COUNT; i++) {
                    if (fx.st.chance[s][i] == 0 || Deals.playerNatural(i)) {
                        continue;
                    }
                    int x = t.parts.from(j * Deals.COUNT + i);
                    a += value(fx, s, i, x, first[0][i], own);
                    b += value(fx, s, i, x, first[0][i], wrong);
                }
                num += pen.weight[j] * a;
                leaked += pen.weight[j] * b;
                den += pen.weight[j];
            }
        }
        double estimate = r.results.get(NULL).fullEntries.get(0).estimate[PEN][SystemScorer.PLAY];
        assertEquals(num / den, estimate, EXACT);
        assertTrue(Math.abs(leaked / den - estimate) > 1e-3, "own fold " + leaked / den + ", other " + estimate);
    }

    /**
     * Each fold's first decisions under full play are fitted on its training states with the
     * Hit and Split values of its own fit's later plays, never the other fold's: the scorer's
     * fitted moves are the test's, and fitting them with the other fold's values would change
     * some of them.
     */
    @Test
    public void aFoldsFirstDecisionsAreFittedOnItsOwnFitsLaterPlays() throws Exception {
        Run r = run();
        Fixture fx = r.fx;
        SystemScorer.Pen pen = fx.scorer.pen(PEN);
        int differ = 0;
        for (CountKeys.Count c : Arrays.asList(NULL, HI_LO)) {
            FullIndexFit.Fits fits = r.fits.get(c);
            SystemScorer.Result res = r.results.get(c);
            for (int f = 0; f < 2; f++) {
                FullIndexFit.Strategy t = fits.fold[PEN][f];
                FullIndexFit.Strategy other = fits.fold[PEN][1 - f];
                int[][] own = moves(fx, firstSums(fx, t, pen.train[f],
                        values(fx, t, pen.train[f], t.later, t.now.stands, t.now.ranking)));
                double[][] wrong = values(fx, other, pen.train[f], other.later, other.now.stands, other.now.ranking);
                int[][] crossed = moves(fx, firstSums(fx, t, pen.train[f], wrong));
                SystemScorer.Fit fit = res.fullFirst[PEN][f];
                for (int g = 0; g < own.length; g++) {
                    assertArrayEquals(own[g], fit.move[g], c + " fold " + f + " group " + g);
                    for (int i = 0; i < Deals.COUNT; i++) {
                        differ += own[g][i] != crossed[g][i] ? 1 : 0;
                    }
                }
            }
        }
        assertTrue(differ >= 5, differ + " first decisions differ under the other fold's later plays");
    }

    // ------------------------------------------------------------------ colour counts

    /**
     * A colour count's per-part Hit and Split values enter every sum the scorer makes from them:
     * the fit, the round values, the round values worked out from an earlier play's, and the
     * decision resolution. On random states, in one group, Red Seven with values of its own in
     * each part (much better in the last part of a deal, worse in the first) must fit, value and
     * resolve exactly as the null count does with each deal's value the share-weighted mean of
     * the parts', every one of those sums being linear in the values; and the null count with
     * those values must fit, value and resolve otherwise than with the stored values, so the
     * values given are the ones used. Reading one part's value for the whole deal, or the stored
     * value, breaks the first; ignoring the values the second.
     */
    @Test
    public void aColourCountUsesEachPartsOwnValuesInEverySum() {
        Random rnd = new Random(23);
        int shoes = 16;
        ScoringStates st = SystemScorerTest.prepare(SystemScorerTest.randomStates(rnd, shoes), shoes);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(shoes, 6, 1), 1e6);
        SystemScorer.Pen pen = sc.pen(PEN);
        SystemScorer.Parts p7 = sc.parts(RED_SEVEN, pen);
        SystemScorer.Parts p0 = sc.parts(NULL, pen);
        SystemScorer.PlayValues v7 = new SystemScorer.PlayValues(p7.key.length);
        SystemScorer.PlayValues v0 = new SystemScorer.PlayValues(p0.key.length);
        int spread = 0;
        for (int j = 0; j < pen.states.length; j++) {
            int s = pen.states[j];
            double[] mv = st.move[s];
            for (int i = 0; i < Deals.COUNT; i++) {
                int cell = j * Deals.COUNT + i;
                if (st.chance[s][i] == 0) {
                    continue;
                }
                int n = p7.to(cell) - p7.from(cell);
                double hit = 0;
                double split = 0;
                for (int x = p7.from(cell); x < p7.to(cell); x++) {
                    int q = x - p7.from(cell);
                    double shift = n == 1 ? 0.01 : q == n - 1 ? 0.5 : -0.3;
                    v7.hit[x] = mv[i * MOVES + HIT] + st.chance[s][i] * shift;
                    v7.split[x] = mv[i * MOVES + SPLIT] + st.chance[s][i] * 0.2 * q;
                    hit += p7.share(x) * v7.hit[x];
                    split += p7.share(x) * v7.split[x];
                }
                spread += n > 1 ? 1 : 0;
                v0.hit[cell] = hit;
                v0.split[cell] = split;
            }
        }
        assertTrue(spread > 1000, spread + " (state, deal) cells of more than one part");
        SystemScorer.InsuranceParts ip7 = sc.insuranceParts(p7, pen);
        SystemScorer.InsuranceParts ip0 = sc.insuranceParts(p0, pen);
        SystemScorer.ByKey byKey7 = sc.byKey(p7, pen);
        int movesUsed = 0;
        int resampleMoves = 0;
        boolean resolutionUsed = false;
        for (int f = 0; f < 2; f++) {
            SystemScorer.FoldPlay a = sc.foldPlay(p7, pen, ip7, pen.train[f], pen.weight, null, null, null, null, v7);
            SystemScorer.FoldPlay b = sc.foldPlay(p0, pen, ip0, pen.train[f], pen.weight, null, null, null, null, v0);
            SystemScorer.FoldPlay c = sc.foldPlay(p0, pen, ip0, pen.train[f], pen.weight, null, null);
            assertEquals(1, a.groups.count());
            assertEquals(1, b.groups.count());
            for (int k = 0; k < Deals.COUNT * MOVES; k++) {
                assertEquals(b.fit.sum[0][k], a.fit.sum[0][k], 1e-13 * (1 + Math.abs(b.fit.sum[0][k])), "sum " + k);
            }
            assertArrayEquals(b.fit.move[0], a.fit.move[0]);
            for (int i = 0; i < Deals.COUNT; i++) {
                movesUsed += b.fit.move[0][i] != c.fit.move[0][i] ? 1 : 0;
            }
            for (int j = 0; j < pen.states.length; j++) {
                assertEquals(b.base[j], a.base[j], 1e-13, "state " + j);
            }
            // A resample's play valued from the estimate's is the play valued afresh.
            for (int r = 0; r < sc.boot.resamples; r++) {
                double[] wm = new double[pen.states.length];
                double[] um = new double[pen.states.length];
                for (int j = 0; j < wm.length; j++) {
                    um[j] = sc.boot.mult(r, st.shoe[pen.states[j]]);
                    wm[j] = pen.weight[j] * um[j];
                }
                int[] drawn = SystemScorer.drawn(pen.train[f], wm);
                SystemScorer.FoldPlay from = sc.foldPlay(p7, pen, ip7, drawn, wm, um, null, byKey7, a);
                SystemScorer.FoldPlay fresh = sc.foldPlay(p7, pen, ip7, drawn, wm, um, null, null, null, v7);
                for (int j = 0; j < pen.states.length; j++) {
                    assertEquals(fresh.base[j], from.base[j], 1e-13, "resample " + r + " state " + j);
                }
                for (int i = 0; i < Deals.COUNT; i++) {
                    resampleMoves += fresh.fit.move[0][i] != a.fit.move[0][i] ? 1 : 0;
                }
            }
            SystemScorer.Resolution ra = resolve(sc, p7, pen, f, a, ip7);
            SystemScorer.Resolution rb = resolve(sc, p0, pen, f, b, ip0);
            SystemScorer.Resolution rc = resolve(sc, p0, pen, f, c, ip0);
            assertEquals(rb.decisions, ra.decisions);
            assertEquals(rb.unsettled, ra.unsettled);
            assertEquals(rb.bound, ra.bound, 1e-13);
            assertEquals(rb.closest.size(), ra.closest.size());
            for (int k = 0; k < rb.closest.size(); k++) {
                assertEquals(rb.closest.get(k).deal, ra.closest.get(k).deal);
                assertEquals(rb.closest.get(k).gap, ra.closest.get(k).gap, 1e-12);
                assertEquals(rb.closest.get(k).se, ra.closest.get(k).se, 1e-12);
            }
            resolutionUsed |= rb.unsettled != rc.unsettled || Math.abs(rb.bound - rc.bound) > 1e-9;
        }
        assertTrue(movesUsed > 0 && resampleMoves > 0 && resolutionUsed,
                movesUsed + " moves moved by the values, " + resampleMoves + " by resampling, resolution moved "
                        + resolutionUsed);
    }

    /** One fold's decision resolution of a play, its bound per unit of test weight. */
    private static SystemScorer.Resolution resolve(SystemScorer sc, SystemScorer.Parts parts, SystemScorer.Pen pen, int f,
                                                   SystemScorer.FoldPlay play, SystemScorer.InsuranceParts ip) {
        SystemScorer.Resolution res = new SystemScorer.Resolution();
        double[] bounds = new double[3];
        double[] insSum = SystemScorer.insuranceSums(ip, play.insuranceGroup, play.groups.count(), pen.train[f],
                pen.weight);
        sc.resolve(parts, pen, f, pen.train[f], play, ip, insSum, 1, bounds, res);
        res.bound = bounds[0];
        res.insuranceBound = bounds[1];
        return res;
    }

    /**
     * On the run's states, Red Seven's fit holds a Hit value per part, each the tree's for that
     * part (theFittedPlayIsTheBestJointPlayOnItsTrainingStates checks every one), and the parts
     * of many deals are worth different amounts, since their keys, and so the later plays their
     * hands meet, differ.
     */
    @Test
    public void aColourCountsPartsHaveHitValuesOfTheirOwn() throws Exception {
        Run r = run();
        int differ = 0;
        for (FullIndexFit.Strategy t : r.fits.get(RED_SEVEN).all()) {
            for (int j = 0; j < t.p.states.length; j++) {
                for (int i = 0; i < Deals.COUNT; i++) {
                    int cell = j * Deals.COUNT + i;
                    int from = t.parts.from(cell);
                    for (int x = from + 1; x < t.parts.to(cell); x++) {
                        if (Math.abs(t.values.hit[x] - t.values.hit[from]) > 1e-12) {
                            differ++;
                            break;
                        }
                    }
                }
            }
        }
        assertTrue(differ >= 20, differ + " (state, deal) cells whose parts differ in Hit value");
    }

    // ------------------------------------------------------------------ the cap and the clean-up

    /**
     * Stopped at the cap after pass 0, a strategy keeps pass 0's play, the stored first-decision
     * fit with the chart after the first move, and is flagged. Its training EV is the stored
     * values'. Its later cells that lose by its own sums are flipped and the training states
     * walked again, and the same once more from that pass's sums, up to two clean-ups; the test
     * walks each step itself and finds the same cells, EVs and final play. Where the passes reach
     * the cap after several passes, the play kept is that of the pass with the best training EV.
     */
    @Test
    public void atTheCapTheBestPassIsKeptAndItsLosingCellsAreFlipped() throws Exception {
        Fixture fx = standard();
        Run r = new Run(fx, Arrays.asList(NULL, HI_LO), 1, FullIndexFit.CLEAN_UPS, false);
        int flips = 0;
        int cleanUps = 0;
        for (FullIndexFit.Strategy t : r.strategies()) {
            assertTrue(t.capped && !t.converged, t.toString());
            assertEquals(1, t.passes);
            assertEquals(0, t.bestPass);
            assertNull(t.now.ranking);
            SystemScorer.Fit f0 = fx.scorer.fit(t.parts, t.p, t.use, t.groups, t.table, t.w);
            for (int g = 0; g < t.groups.count(); g++) {
                assertArrayEquals(f0.move[g], t.now.first.move[g]);
            }
            double[][] stored = new double[2][t.parts.key.length];
            for (int j = 0; j < t.p.states.length; j++) {
                double[] mv = fx.st.move[t.p.states[j]];
                for (int i = 0; i < Deals.COUNT; i++) {
                    int cell = j * Deals.COUNT + i;
                    for (int x = t.parts.from(cell); x < t.parts.to(cell); x++) {
                        stored[0][x] = mv[i * MOVES + HIT];
                        stored[1][x] = mv[i * MOVES + SPLIT];
                    }
                }
            }
            assertEquals(ev(fx, t, t.use, f0.move, stored), t.history.get(0).ev, EXACT);
            boolean[] play = new boolean[t.later.count() * FullIndexFit.CELLS];
            for (int at = 0; at < play.length; at++) {
                play[at] = FullIndexFit.chartStands(at % FullIndexFit.CELLS);
            }
            int k = 1;
            while (true) {
                LaterSums ls = laterSums(fx, t, t.use, t.later, play, f0.move);
                boolean[] next = play.clone();
                int losing = 0;
                for (int at = 0; at < play.length; at++) {
                    if (ls.reach[at] > 0 && (play[at] ? ls.gap[at] < 0 : ls.gap[at] > 0)) {
                        next[at] = !play[at];
                        losing++;
                    }
                }
                if (losing == 0 || k > FullIndexFit.CLEAN_UPS) {
                    break;
                }
                FullIndexFit.PassRecord pr = t.history.get(k);
                assertTrue(pr.cleanUp);
                assertEquals(losing, pr.laterChanges, t + " clean-up " + k);
                assertEquals(ev(fx, t, t.use, f0.move, values(fx, t, t.use, t.later, next, null)), pr.ev, EXACT);
                flips += losing;
                play = next;
                k++;
            }
            assertEquals(k, t.history.size());
            assertEquals(k - 1, t.cleanUps);
            assertArrayEquals(play, t.now.stands, t.toString());
            cleanUps += k - 1;
        }
        assertTrue(flips >= 20 && cleanUps >= 4, flips + " cells flipped in " + cleanUps + " clean-ups");
    }

    /**
     * Where the passes reach the cap after several passes, the play kept is that of the pass
     * with the best training EV, which need not be the last. With every key its own group,
     * Hi-Lo's and Red Seven's fits on the ten-rich shoes oscillate, and capped at five passes
     * some fall back from their best. The test works out the kept play's training EV itself, and
     * walks the clean-ups from that play as the fit should: the cells that lose by its sums
     * flipped, the training states walked again, and once more.
     */
    @Test
    public void atTheCapThePlayOfTheBestPassIsKept() throws Exception {
        Fixture fx = standard();
        SystemScorer fine = new SystemScorer(fx.st, new Bootstrap(SHOES, 2, 1), 1e-9);
        Run r = new Run(fx, fine, Arrays.asList(HI_LO, RED_SEVEN), 5, FullIndexFit.CLEAN_UPS, false);
        int fellBack = 0;
        for (FullIndexFit.Strategy t : r.strategies()) {
            if (!t.capped) {
                continue;
            }
            assertEquals(5, t.passes);
            int best = 0;
            for (int p = 1; p < t.passes; p++) {
                best = t.history.get(p).ev > t.history.get(best).ev ? p : best;
            }
            assertEquals(best, t.bestPass, t.toString());
            FullIndexFit.Policy kept = t.best;
            assertSame(kept.first, t.now.first);
            assertSame(kept.ranking, t.now.ranking);
            double[][] hs = values(fx, t, t.use, t.later, kept.stands, kept.ranking);
            assertEquals(t.history.get(best).ev, ev(fx, t, t.use, kept.first.move, hs), EXACT, t.toString());
            fellBack += t.history.get(t.passes - 1).ev < t.history.get(best).ev ? 1 : 0;
            boolean[] play = kept.stands.clone();
            int k = t.passes;
            while (true) {
                LaterSums ls = laterSums(fx, t, t.use, t.later, play, kept.first.move);
                boolean[] next = play.clone();
                int losing = 0;
                for (int at = 0; at < play.length; at++) {
                    if (ls.reach[at] > 0 && (play[at] ? ls.gap[at] < 0 : ls.gap[at] > 0)) {
                        next[at] = !play[at];
                        losing++;
                    }
                }
                if (losing == 0 || k - t.passes == FullIndexFit.CLEAN_UPS) {
                    break;
                }
                FullIndexFit.PassRecord pr = t.history.get(k);
                assertTrue(pr.cleanUp);
                assertEquals(losing, pr.laterChanges, t + " clean-up " + (k - t.passes + 1));
                assertEquals(ev(fx, t, t.use, kept.first.move, values(fx, t, t.use, t.later, next, kept.ranking)), pr.ev,
                        EXACT);
                play = next;
                k++;
            }
            assertEquals(k, t.history.size(), t.toString());
            assertArrayEquals(play, t.now.stands, t.toString());
        }
        assertTrue(fellBack >= 1, "no strategy capped at five passes fell back from its best");
    }

    // ------------------------------------------------------------------ decision resolution

    /** sqrt(n / (n - 1) x the sum over shoes of (sign x a - gain x b)^2) / reach. */
    private static double se(LaterSums ls, int at, double sign, double gain, int n, double reach) {
        double e2 = 0;
        for (double[][] shoe : ls.shoe.values()) {
            double e = sign * shoe[0][at] - gain * shoe[1][at];
            e2 += e * e;
        }
        return Math.sqrt((double) n / (n - 1) * e2) / reach;
    }

    /**
     * A later cell's one-flip gain per time it comes up is its summed gap over its summed reach,
     * signed by the answer kept, and its standard error comes from per-shoe sums by the delta
     * method, every shoe of the training fold counted: SE = sqrt(n / (n - 1) x the sum over shoes
     * of (a - gain x b)^2) / B, with a and b a shoe's signed gap and reach and B the fold's reach.
     * A cell is settled when its gain is more than 2 SE; otherwise it is charged its frequency x
     * (2 SE - gain), so a cell whose answer loses is charged its whole loss and more. Some shoes
     * hold two states, so taking each state as a shoe of its own gives other SEs. Checked on the
     * standard run's fits and on the plays kept at the cap after pass 0 with no clean-up, where
     * the chart's answers lose in some cells.
     */
    @Test
    public void aLaterCellIsSettledPastTwoStandardErrorsFromPerShoeSums() throws Exception {
        int checked = 0;
        int open = 0;
        int losing = 0;
        boolean clustered = false;
        double losingBound = 0;
        for (Run r : Arrays.asList(run(), capped())) {
            Fixture fx = r.fx;
            for (FullIndexFit.Strategy t : r.strategies()) {
                if (t.half) {
                    continue;
                }
                FullIndexFit.Summary summary = t.summary();
                LaterSums ls = laterSums(fx, t, t.use, t.later, t.now.stands, t.now.first.move);
                LaterSums each = laterSums(fx, t, t.use, t.later, t.now.stands, t.now.first.move, true);
                int n = fx.st.shoesIn(1 - t.fold);
                double weight = 0;
                for (int j : t.use) {
                    weight += t.w[j];
                }
                double bound = 0;
                int decisions = 0;
                int unsettled = 0;
                for (int at = 0; at < ls.gap.length; at++) {
                    double reach = ls.reach[at];
                    if (reach == 0) {
                        assertTrue(Double.isNaN(summary.gain[at]));
                        continue;
                    }
                    double sign = t.now.stands[at] ? 1 : -1;
                    double gain = sign * ls.gap[at] / reach;
                    double se = se(ls, at, sign, gain, n, reach);
                    assertEquals(gain, summary.gain[at], 1e-12 * (1 + Math.abs(gain)), t + " cell " + at);
                    assertEquals(se, summary.se[at], 1e-12 * (1 + se), t + " cell " + at);
                    assertEquals(reach / weight, summary.frequency[at], 1e-15);
                    clustered |= Math.abs(se(each, at, sign, gain, n, reach) - se) > 1e-6 * se;
                    decisions++;
                    if (!(gain > 2 * se)) {
                        unsettled++;
                        bound += reach / weight * (2 * se - gain);
                    }
                    if (gain < 0) {
                        losing++;
                        losingBound += reach / weight * (2 * se - gain);
                    }
                    checked++;
                }
                SystemScorer.Resolution res = new SystemScorer.Resolution();
                double[] bounds = new double[3];
                t.resolveLater(1, bounds, res);
                assertEquals(decisions, res.laterDecisions, t.toString());
                assertEquals(unsettled, res.laterUnsettled, t.toString());
                assertEquals(bound, bounds[2], 1e-15 + 1e-12 * bound, t.toString());
                open += unsettled;
            }
        }
        System.out.printf("FullIndexFitTest: %d later cells resolved, %d unsettled, %d losing, charged %.3e%n", checked,
                open, losing, losingBound);
        assertTrue(open >= 1 && losing >= 1 && clustered, open + " unsettled, " + losing + " losing, clustered "
                + clustered);
    }

    // ------------------------------------------------------------------ scoring both ways

    /**
     * Full play and first-decision-only play are scored in one run on the same resamples, so the
     * gain from full play has a paired interval. Where the play kept is pass 0's, the stored
     * first-decision fit with the chart after, full play is first-decision-only play: on every
     * resample the two agree to the bit, the gain is 0 with an SE of 0, and the decision
     * resolution and the learning curve are the first-decision ones. Where the full fit moves
     * the play, the gain's interval is the interval of the resample-by-resample differences, and
     * widening it by a ratio spreads those differences by the ratio about their mean.
     */
    @Test
    public void theGainFromFullPlayIsPairedResampleByResample() throws Exception {
        for (CountKeys.Count c : Arrays.asList(NULL, HI_LO)) {
            SystemScorer.Result res = capped().results.get(c);
            SystemScorer.Entry full = res.fullEntries.get(0);
            SystemScorer.Entry first = res.entries.get(0);
            assertTrue(full.fullPlay && !first.fullPlay);
            for (int m = 0; m < SystemScorer.METRICS; m++) {
                assertEquals(first.estimate[PEN][m], full.estimate[PEN][m], 0.0, c + " metric " + m);
                assertArrayEquals(first.boot[PEN][m], full.boot[PEN][m], 0.0, c + " metric " + m);
            }
            Bootstrap.Interval g = SystemScorer.gain(full, first, PEN, SystemScorer.FLAT, 1);
            assertEquals(0, g.estimate, 0);
            assertEquals(0, g.se, 0);
            SystemScorer.Resolution a = res.resolution[PEN];
            SystemScorer.Resolution b = res.fullResolution[PEN];
            assertEquals(a.decisions, b.decisions);
            assertEquals(a.unsettled, b.unsettled);
            assertEquals(a.bound, b.bound, 0);
            assertEquals(a.insuranceBound, b.insuranceBound, 0);
            assertTrue(b.laterDecisions > 0);
            assertEquals(a.bound + a.insuranceBound + b.laterBound, b.total(), 0);
            assertEquals(res.halfFitFlat[PEN], res.fullHalfFitFlat[PEN], 0);
            assertArrayEquals(res.halfFitBoot[PEN], res.fullHalfFitBoot[PEN], 1e-15);
        }
        SystemScorer.Result res = run().results.get(HI_LO);
        SystemScorer.Entry full = res.fullEntries.get(0);
        SystemScorer.Entry first = res.entries.get(0);
        double[] d = new double[full.boot[PEN][SystemScorer.FLAT].length];
        for (int r = 0; r < d.length; r++) {
            d[r] = full.boot[PEN][SystemScorer.FLAT][r] - first.boot[PEN][SystemScorer.FLAT][r];
        }
        double estimate = full.estimate[PEN][SystemScorer.FLAT] - first.estimate[PEN][SystemScorer.FLAT];
        Bootstrap.Interval want = Bootstrap.interval(estimate, d);
        Bootstrap.Interval got = SystemScorer.gain(full, first, PEN, SystemScorer.FLAT, 1);
        assertTrue(Math.abs(estimate) > 1e-4 && got.se > 0, estimate + " " + got.se);
        assertEquals(want.estimate, got.estimate, 0);
        assertEquals(want.se, got.se, 0);
        assertEquals(want.lo, got.lo, 0);
        assertEquals(want.hi, got.hi, 0);
        Bootstrap.Interval wide = SystemScorer.gain(full, first, PEN, SystemScorer.FLAT, 2);
        assertEquals(2 * got.se, wide.se, 1e-12 * got.se);
        assertEquals(got.bias, wide.bias, 1e-15);
        assertThrows(IllegalArgumentException.class, () -> SystemScorer.gain(first, full, PEN, SystemScorer.FLAT, 1));
    }

    // ------------------------------------------------------------------ the half fits

    /**
     * The in-sample check: each half fit's later cells worked out again on the other half of its
     * training fold, where it was not fitted, by the test's own walks under the half fit's play.
     * The cells compared are those reached in both halves with a sum not zero on the other; the
     * check counts how many the other half would play the other way, among all and among those
     * settled on the half fitted. The Result adds both folds' counts.
     */
    @Test
    public void theInSampleCheckCountsTheCellsTheOtherHalfPlaysTheOtherWay() throws Exception {
        Run r = run();
        Fixture fx = r.fx;
        SystemScorer.Pen pen = fx.scorer.pen(PEN);
        int compared = 0;
        int disagree = 0;
        for (CountKeys.Count c : r.counts) {
            int[] both = new int[4];
            for (int f = 0; f < 2; f++) {
                FullIndexFit.Strategy t = r.fits.get(c).half[PEN][f];
                List<Integer> rest = new ArrayList<>();
                for (int j : pen.train[f]) {
                    if (!SystemScorer.inHalf(fx.st.shoe[pen.states[j]])) {
                        rest.add(j);
                    }
                }
                int[] other = rest.stream().mapToInt(Integer::intValue).toArray();
                LaterSums mine = laterSums(fx, t, t.use, t.later, t.now.stands, t.now.first.move);
                LaterSums theirs = laterSums(fx, t, other, t.later, t.now.stands, t.now.first.move);
                FullIndexFit.Summary summary = t.summary();
                int[] want = new int[4];
                for (int at = 0; at < mine.gap.length; at++) {
                    if (mine.reach[at] == 0 || theirs.reach[at] == 0 || theirs.gap[at] == 0) {
                        continue;
                    }
                    boolean differs = theirs.gap[at] > 0 != t.now.stands[at];
                    want[0]++;
                    want[1] += differs ? 1 : 0;
                    if (summary.gain[at] > 2 * summary.se[at]) {
                        want[2]++;
                        want[3] += differs ? 1 : 0;
                    }
                }
                assertArrayEquals(want, t.inSampleCheck(), t.toString());
                assertArrayEquals(want, summary.inSample, t.toString());
                for (int k = 0; k < 4; k++) {
                    both[k] += want[k];
                }
            }
            assertArrayEquals(both, r.results.get(c).fullInSample[PEN], c.name);
            compared += both[0];
            disagree += both[1];
        }
        assertTrue(compared >= 100 && disagree >= 1, compared + " compared, " + disagree + " disagree");
    }

    /**
     * The learning curve's half fits play their own later plays: the full-play half-fit EV is
     * the test's own, each fold's test states played with the half fit's first decisions, fitted
     * on the half under the half fit's values, valued under the half fit's later plays, with
     * insurance fitted on the half. Valued under the whole fold's later plays instead, it would
     * come out otherwise.
     */
    @Test
    public void theLearningCurvesHalfFitsPlayTheirOwnLaterPlays() throws Exception {
        Run r = run();
        Fixture fx = r.fx;
        SystemScorer.Pen pen = fx.scorer.pen(PEN);
        for (CountKeys.Count c : Arrays.asList(NULL, HI_LO)) {
            double num = 0;
            double crossed = 0;
            double den = 0;
            for (int f = 0; f < 2; f++) {
                FullIndexFit.Strategy t = r.fits.get(c).half[PEN][f];
                FullIndexFit.Strategy whole = r.fits.get(c).fold[PEN][f];
                double[][] hs = values(fx, t, every(t), t.later, t.now.stands, t.now.ranking);
                double[][] wholeValues = values(fx, whole, every(whole), whole.later, whole.now.stands, whole.now.ranking);
                int[][] first = moves(fx, firstSums(fx, t, t.use, hs));
                int[][] firstCrossed = moves(fx, firstSums(fx, t, t.use, wholeValues));
                for (int j : pen.test[f]) {
                    int s = pen.states[j];
                    double a = fx.st.naturals[s] + t.insurance[j];
                    double b = a;
                    for (int i = 0; i < Deals.COUNT; i++) {
                        if (fx.st.chance[s][i] == 0 || Deals.playerNatural(i)) {
                            continue;
                        }
                        for (int x = t.parts.from(j * Deals.COUNT + i); x < t.parts.to(j * Deals.COUNT + i); x++) {
                            a += t.parts.share(x) * value(fx, s, i, x, first[group(t, x)][i], hs);
                            b += t.parts.share(x) * value(fx, s, i, x, firstCrossed[group(t, x)][i], wholeValues);
                        }
                    }
                    num += pen.weight[j] * a;
                    crossed += pen.weight[j] * b;
                    den += pen.weight[j];
                }
            }
            SystemScorer.Result res = r.results.get(c);
            assertEquals(num / den, res.fullHalfFitFlat[PEN], EXACT, c.name);
            assertTrue(Math.abs(crossed / den - num / den) > 1e-5, c.name + ": " + crossed / den + " and " + num / den);
        }
    }

    // ------------------------------------------------------------------ the perfect row

    /**
     * The full-scope perfect row plays every decision of the dealt hand best from the state's
     * own composition, its split hands at the chart. Each state's value is the sum over deals of
     * the best first move, Stand, Double and Split at their stored values and Hit by the walk
     * that takes the better of standing and hitting at every node; that walk's Hit values are at
     * least those of the chart and of random plays, deal by deal, and on these shoes many are
     * more; the value is at least the perfect first move's. The row scores those values flat
     * with perfect insurance, on every resample.
     */
    @Test
    public void thePerfectRowPlaysEveryDecisionOfTheDealtHandBest() throws Exception {
        Run r = run();
        Fixture fx = r.fx;
        double[] perfect = r.fit.perfect;
        Random rnd = new Random(3);
        ExactRound engine = new ExactRound(RULES);
        int more = 0;
        for (int s = 0; s < fx.st.size; s++) {
            int[] left = ShoeRun.ranksLeft(fx.st.dealt[s]);
            PlayerTree.Counted counted = fx.trees.of(s, engine).count(NULL, fx.st.dealt[s]);
            PlayerTree.Walk best = counted.perfect();
            List<PlayerTree.Walk> others = new ArrayList<>();
            others.add(counted.walk(PlayerTree.CHART, PlayerTree.Mode.EVERY_NODE));
            for (int k = 0; k < 3; k++) {
                boolean[] stand = new boolean[PlayerTree.SPOTS];
                for (int x = 0; x < stand.length; x++) {
                    stand[x] = rnd.nextBoolean();
                }
                others.add(counted.walk((total, soft, up, key) -> stand[PlayerTree.spot(total, soft, up)],
                        PlayerTree.Mode.EVERY_NODE));
            }
            double v = fx.st.naturals[s];
            for (int i = 0; i < Deals.COUNT; i++) {
                if (fx.st.chance[s][i] == 0 || Deals.playerNatural(i)) {
                    continue;
                }
                double h = best.hit(i, 0);
                for (PlayerTree.Walk o : others) {
                    assertTrue(h >= o.hit(i, 0) - 1e-15, "state " + s + " deal " + i);
                }
                more += h > others.get(0).hit(i, 0) + 1e-12 ? 1 : 0;
                double top = Double.NEGATIVE_INFINITY;
                for (int m = 0; m < MOVES; m++) {
                    if (fx.st.legal[i * MOVES + m]) {
                        top = Math.max(top, m == HIT ? units(left, i, h) : fx.st.move[s][i * MOVES + m]);
                    }
                }
                v += top;
            }
            assertEquals(v, perfect[s], 1e-15, "state " + s);
            assertTrue(perfect[s] >= fx.st.perfect[s] - 1e-15);
        }
        assertTrue(more >= 100, more + " deals where perfect play after the first move beats the chart's");
        SystemScorer.Entry row = fx.scorer.perfectPlayRow(perfect);
        SystemScorer.Pen pen = fx.scorer.pen(PEN);
        for (int rs = -1; rs < fx.scorer.boot.resamples; rs++) {
            double num = 0;
            double ins = 0;
            double den = 0;
            for (int j = 0; j < pen.states.length; j++) {
                int s = pen.states[j];
                double w = pen.weight[j] * (rs < 0 ? 1 : fx.scorer.boot.mult(rs, fx.st.shoe[s]));
                num += w * perfect[s];
                ins += w * fx.st.perfectInsurance[s];
                den += w;
            }
            double flat = rs < 0 ? row.estimate[PEN][SystemScorer.FLAT] : row.boot[PEN][SystemScorer.FLAT][rs];
            double play = rs < 0 ? row.estimate[PEN][SystemScorer.PLAY] : row.boot[PEN][SystemScorer.PLAY][rs];
            assertEquals((num + ins) / den, flat, EXACT);
            assertEquals(num / den, play, EXACT);
        }
    }

    // ------------------------------------------------------------------ batches

    /**
     * Scoring in batches changes nothing: the null count and Hi-Lo scored one batch at a time,
     * every state's trees built afresh each round, give the estimates, resamples and fits they
     * give in one batch from kept trees. Each count's widening ratio is the one the map gives it,
     * 1 where it gives none, and the perfect row is worked out in the first batch.
     */
    @Test
    public void scoringInBatchesChangesNothing() throws Exception {
        Fixture fx = standard();
        // Half the shoes, the even and odd shoe of every other pair, to keep the test short.
        ScoringStates sub = fx.st.subRun(0, 2);
        SystemScorer scorer = new SystemScorer(sub, new Bootstrap(sub.shoes, 5, 1), 1);
        Map<CountKeys.Count, Double> widen = new HashMap<>();
        widen.put(HI_LO, 1.5);
        List<CountKeys.Count> counts = Arrays.asList(NULL, HI_LO);
        int[] pens = {PEN};
        FullIndexFit.Scored apart = FullIndexFit.scoreAll(scorer, FullIndexFit.built(sub), counts, pens, 1, THREADS,
                false, true, widen);
        FullIndexFit.Scored together = FullIndexFit.scoreAll(scorer, FullIndexFit.kept(sub, RULES, THREADS), counts, pens,
                2, THREADS, true, false, null);
        assertEquals(2, apart.rounds.size());
        assertEquals(1, together.rounds.size());
        assertNull(together.perfect);
        for (int s = 0; s < sub.size; s++) {
            assertFalse(Double.isNaN(apart.perfect[s]));
        }
        assertEquals(1, apart.results.get(0).widen, 0);
        assertEquals(1.5, apart.results.get(1).widen, 0);
        for (int k = 0; k < counts.size(); k++) {
            SystemScorer.Result a = apart.results.get(k);
            SystemScorer.Result b = together.results.get(k);
            assertEquals(counts.get(k), a.count);
            for (int m = 0; m < SystemScorer.METRICS; m++) {
                assertEquals(b.fullEntries.get(0).estimate[PEN][m], a.fullEntries.get(0).estimate[PEN][m], 1e-15);
                assertArrayEquals(b.fullEntries.get(0).boot[PEN][m], a.fullEntries.get(0).boot[PEN][m], 1e-15);
            }
            for (int f = 0; f < 2; f++) {
                assertArrayEquals(b.fullFit[PEN][f].stands, a.fullFit[PEN][f].stands);
                assertArrayEquals(b.fullHalfFit[PEN][f].stands, a.fullHalfFit[PEN][f].stands);
                for (int g = 0; g < b.fullFirst[PEN][f].move.length; g++) {
                    assertArrayEquals(b.fullFirst[PEN][f].move[g], a.fullFirst[PEN][f].move[g]);
                }
            }
            assertEquals(b.fullResolution[PEN].total(), a.fullResolution[PEN].total(), 1e-15);
        }
    }
}
