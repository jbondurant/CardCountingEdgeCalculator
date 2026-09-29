import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scorer on synthetic states whose right answers are known by construction: values
 * where the best move flips at a known key, weights that decide a fit, folds that disagree,
 * advantages that rise or fall with the bet key, and a decision whose gap and standard
 * error are worked out by hand. Every expected value is computed here from RoundValue, the
 * formula RoundValueTest holds to dealing every order of a small shoe, not from the
 * scorer's own sums.
 */
public class SystemScorerTest {

    private static final double EXACT = 1e-12;
    private static final RoundRules RULES = StateRun.montreal();
    private static final int STAND = 0;
    private static final int HIT = 1;
    private static final int DOUBLE = 2;

    interface Values {
        double of(int deal, int move);
    }

    /** 40 tens out, so insurance never pays, and some sevens out, which the sevens count sees. */
    static int[] tenPoor(int sevens) {
        int[] d = new int[ShoeRun.KINDS];
        for (int k = 18; k < 26; k++) {
            d[k] = 5;
        }
        d[12] = (sevens + 1) / 2;
        d[13] = sevens / 2;
        return d;
    }

    /** A state's record, with a value for every legal move of every deal the cards left can make. */
    static StateStore.Record state(int shoe, int round, int[] dealt, double q, Values v) {
        int depth = Arrays.stream(dealt).sum();
        int[] left = ShoeRun.ranksLeft(dealt);
        double[] values = new double[StateStore.VALUES];
        Arrays.fill(values, Double.NaN);
        for (int i = 0; i < Deals.COUNT; i++) {
            if (Deals.probability(left, i) == 0 || Deals.playerNatural(i)) {
                continue;
            }
            for (int m = 0; m < ScoringStates.MOVES; m++) {
                if (StateValuer.legal(RULES, i, StateValuer.MOVES[m])) {
                    values[i * ScoringStates.MOVES + m] = v.of(i, m);
                }
            }
        }
        return new StateStore.Record(shoe, round, depth, q, dealt, values);
    }

    static ScoringStates prepare(List<StateStore.Record> records, int shoes) {
        return new ScoringStates(new ArrayList<>(records), shoes, RULES);
    }

    /** A running count that tags only the sevens, +1 each (or sign each). */
    static CountKeys.Count sevens(int sign) {
        int[] tag = new int[ShoeRun.KINDS];
        tag[12] = sign;
        tag[13] = sign;
        return new CountKeys.Count(sign > 0 ? "Sevens" : "Minus sevens", Collections.emptyList(), tag, 1, false, 0,
                null, "");
    }

    static int sevensIn(int deal) {
        return (Deals.P1[deal] == 7 ? 1 : 0) + (Deals.P2[deal] == 7 ? 1 : 0) + (Deals.UP[deal] == 7 ? 1 : 0);
    }

    /** The weighted mean over records of RoundValue under a play chosen per record. */
    interface PlayOf {
        RoundValue.Play of(StateStore.Record r);
    }

    static double expectedFlat(List<StateStore.Record> records, PlayOf play) {
        double num = 0;
        double den = 0;
        for (StateStore.Record r : records) {
            double rv = RoundValue.of(r.ranksLeft(), r.values, RULES.blackjackPayout, play.of(r), i -> false);
            num += rv / r.q;
            den += 1 / r.q;
        }
        return num / den;
    }

    // --------------------------------------------------------------------- play

    /**
     * The best move is Double exactly when the play key, the sevens seen including the
     * deal's own, is at least 10. With every key its own group, the fit must find that for
     * every deal, and the flat EV is the round value under that play.
     */
    @Test
    public void theMoveFlipsWhereTheValuesSayItDoes() {
        int k0 = 10;
        List<StateStore.Record> records = new ArrayList<>();
        for (int k = 0; k <= 20; k++) {
            for (int f = 0; f < 2; f++) {
                int seen = k;
                records.add(state(2 * k + f, 0, tenPoor(k), 0.5, (i, m) -> m == DOUBLE
                        ? (seen + sevensIn(i) >= k0 ? 0.02 : -0.02) : m == STAND ? 0 : -0.3));
            }
        }
        SystemScorer sc = new SystemScorer(prepare(records, 42), new Bootstrap(42, 5, 1), 1e-9);
        SystemScorer.Result res = sc.score(sevens(1), null);
        double expected = expectedFlat(records, r -> {
            int seen = r.dealt[12] + r.dealt[13];
            return i -> seen + sevensIn(i) >= k0 ? DOUBLE : STAND;
        });
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            double[] est = res.entries.get(0).estimate[p];
            assertEquals(expected, est[SystemScorer.FLAT], EXACT);
            assertEquals(expected, est[SystemScorer.PLAY], EXACT);
            assertEquals(0, est[SystemScorer.INSURANCE], 0);
            assertEquals(24, res.groups[p][0].count());
        }
    }

    /**
     * Two states of one fold, the same cards, one valued with q = 1 preferring Double by 0.05
     * and one with q = 0.1 preferring Stand by 0.01. Weighted by 1/q, Stand wins: 0.05 x 1 <
     * 0.01 x 10. Unweighted, or weighted by q, Double would.
     */
    @Test
    public void everySumWeightsAStateByOneOverQ() {
        List<StateStore.Record> records = new ArrayList<>();
        for (int shoe = 0; shoe < 4; shoe++) {
            boolean likesDouble = shoe < 2;
            records.add(state(shoe, 0, tenPoor(0), likesDouble ? 1 : 0.1, (i, m) -> m == DOUBLE
                    ? (likesDouble ? 0.05 : -0.01) : m == STAND ? 0 : -0.4));
        }
        SystemScorer sc = new SystemScorer(prepare(records, 4), new Bootstrap(4, 5, 1), SystemScorer.M_PLAY);
        SystemScorer.Result res = sc.score(CountKeys.nullCount(), null);
        double expected = expectedFlat(records, r -> i -> STAND);
        assertEquals(expected, res.entries.get(0).estimate[2][SystemScorer.FLAT], EXACT);
        // The pooled score is the ratio of summed totals: the q = 0.1 states count ten times.
        double stand = RoundValue.of(records.get(0).ranksLeft(), records.get(0).values, 1.5, i -> STAND, i -> false);
        assertEquals(stand, expected, EXACT);
    }

    /**
     * Even shoes prefer Double, odd shoes Stand. Each fold is played with the other fold's
     * fit, so even shoes stand and odd shoes double; a fit graded on its own fold would do
     * better than the data allow.
     */
    @Test
    public void eachFoldIsPlayedWithTheOtherFoldsFit() {
        List<StateStore.Record> records = new ArrayList<>();
        for (int shoe = 0; shoe < 6; shoe++) {
            boolean even = shoe % 2 == 0;
            records.add(state(shoe, 0, tenPoor(shoe), 0.25 + 0.1 * shoe, (i, m) -> m == DOUBLE
                    ? (even ? 0.05 : -0.05) : m == STAND ? 0 : -0.4));
        }
        SystemScorer sc = new SystemScorer(prepare(records, 6), new Bootstrap(6, 5, 1), SystemScorer.M_PLAY);
        SystemScorer.Result res = sc.score(CountKeys.nullCount(), null);
        double expected = expectedFlat(records, r -> i -> r.shoe % 2 == 0 ? STAND : DOUBLE);
        assertEquals(expected, res.entries.get(0).estimate[1][SystemScorer.FLAT], EXACT);
        double leaked = expectedFlat(records, r -> i -> r.shoe % 2 == 0 ? DOUBLE : STAND);
        assertTrue(leaked > expected + 0.01);
    }

    /**
     * Insurance is fitted on the other fold too. Even shoes are ten-rich, where insurance
     * pays in every ace-up deal, and odd shoes ten-poor, where it pays in none. So the odd
     * shoes insure on the even shoes' fit and lose, and the even shoes, on the odd shoes' fit,
     * do not insure. A fit taken on the test fold itself would insure exactly where it pays.
     */
    @Test
    public void eachFoldInsuresWithTheOtherFoldsFit() {
        int[] chart = ScoringStates.chartMoves(RULES);
        List<StateStore.Record> records = new ArrayList<>();
        for (int shoe = 0; shoe < 6; shoe++) {
            records.add(state(shoe, 0, shoe % 2 == 0 ? tenRich() : tenPoor(0), 0.5,
                    (i, m) -> m == chart[i] ? 0 : -1));
        }
        SystemScorer sc = new SystemScorer(prepare(records, 6), new Bootstrap(6, 3, 1), SystemScorer.M_PLAY);
        SystemScorer.Result res = sc.score(CountKeys.nullCount(), null);
        // Each parity's summed insurance value per ace-up deal: what the other parity's fit sees.
        double[][] sum = new double[2][ScoringStates.ACE_UP];
        for (StateStore.Record r : records) {
            int[] left = r.ranksLeft();
            for (int i = 0; i < ScoringStates.ACE_UP; i++) {
                sum[r.shoe % 2][i] += Deals.probability(left, i) * RoundValue.insurance(left, i) / r.q;
            }
        }
        double expected = 0;
        double leaked = 0;
        double weight = 0;
        for (StateStore.Record r : records) {
            int[] left = r.ranksLeft();
            int parity = r.shoe % 2;
            for (int i = 0; i < ScoringStates.ACE_UP; i++) {
                double value = Deals.probability(left, i) * RoundValue.insurance(left, i) / r.q;
                expected += sum[1 - parity][i] > 0 ? value : 0;
                leaked += sum[parity][i] > 0 ? value : 0;
            }
            weight += 1 / r.q;
        }
        assertTrue(expected < 0 && leaked > 0, expected + " and " + leaked);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            assertEquals(expected / weight, res.entries.get(0).estimate[p][SystemScorer.INSURANCE], EXACT);
        }
    }

    /** States of the sevens count: copies[k] states with k sevens out, each valued with chance q[k]. */
    static List<StateStore.Record> sevensLayout(int[] copies, double[] q) {
        List<StateStore.Record> records = new ArrayList<>();
        Random rnd = new Random(4);
        int shoe = 0;
        for (int k = 0; k < copies.length; k++) {
            for (int c = 0; c < copies[k]; c++) {
                records.add(state(shoe++, 0, tenPoor(k), q[k], (i, m) -> rnd.nextGaussian()));
            }
        }
        return records;
    }

    /**
     * Each sevens-count play key's valued states [0] and the states they stand for [1], worked
     * out directly: each state's deals, by their chances, the second weighted 1/q.
     */
    static double[][] keyMass(ScoringStates st, SystemScorer.Pen pen) {
        double[][] mass = new double[2][40];
        for (int j = 0; j < pen.states.length; j++) {
            int s = pen.states[j];
            int seen = st.dealt[s][12] + st.dealt[s][13];
            for (int i = 0; i < Deals.COUNT; i++) {
                mass[0][seen + sevensIn(i)] += st.chance[s][i];
                mass[1][seen + sevensIn(i)] += st.chance[s][i] * st.weight[s];
            }
        }
        return mass;
    }

    /**
     * The groups section 6 describes, walked here from the key masses as [lo, hi] pairs: start
     * at the key holding the most states, close a group walking up, and then one walking
     * down, each once it holds mPlay valued states, and join a partial end group to its
     * neighbour.
     */
    static List<int[]> walk(double[] units, double[] rounds, double mPlay) {
        int lo = 0;
        while (units[lo] == 0) {
            lo++;
        }
        int hi = units.length - 1;
        while (units[hi] == 0) {
            hi--;
        }
        int mode = lo;
        for (int k = lo; k <= hi; k++) {
            mode = rounds[k] > rounds[mode] ? k : mode;
        }
        List<int[]> up = new ArrayList<>();
        int start = mode;
        double held = 0;
        for (int k = mode; k <= hi; k++) {
            held += units[k];
            if (held >= mPlay) {
                up.add(new int[]{start, k});
                start = k + 1;
                held = 0;
            }
        }
        List<int[]> down = new ArrayList<>();
        int end = mode - 1;
        held = 0;
        for (int k = mode - 1; k >= lo; k--) {
            held += units[k];
            if (held >= mPlay) {
                down.add(0, new int[]{k, end});
                end = k - 1;
                held = 0;
            }
        }
        List<int[]> all = new ArrayList<>(down);
        all.addAll(up);
        if (all.isEmpty()) {
            all.add(new int[]{lo, hi});
            return all;
        }
        all.get(all.size() - 1)[1] = hi;
        all.get(0)[0] = lo;
        return all;
    }

    static boolean sameGroups(List<int[]> a, List<int[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int g = 0; g < a.size(); g++) {
            if (!Arrays.equals(a.get(g), b.get(g))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Groups grow outward from the most populated key and close once they hold M_play
     * states' worth; a partial group at either end joins its neighbour. Three layouts take
     * every way that joining can go: the most populated key in the middle; at the top, with
     * less than M_play at or above it, so no group closes walking up and the partial top joins
     * the first group walking down; and near the bottom, with less than M_play below it, so the
     * partial bottom joins the first group walking up. In each, every group holds at least
     * M_play, every group but the two end ones would fall short without its outermost key,
     * and the groups are the ones walked here.
     */
    @Test
    public void playGroupsCloseAtMPlayWalkingOutFromTheMode() {
        int[][] layouts = new int[3][];
        double[] mPlays = {3, 12, 12};
        layouts[0] = new int[25];
        for (int k = 0; k <= 24; k++) {
            layouts[0][k] = 1 + (int) (6 * Math.exp(-Math.pow(k - 8, 2) / 30.0));
        }
        layouts[1] = new int[21];
        for (int k = 0; k < 20; k++) {
            layouts[1][k] = 3;
        }
        layouts[1][20] = 10;
        layouts[2] = new int[18];
        layouts[2][0] = 1;
        layouts[2][1] = 1;
        layouts[2][2] = 10;
        for (int k = 3; k < 18; k++) {
            layouts[2][k] = 3;
        }
        for (int layout = 0; layout < 3; layout++) {
            double[] q = new double[layouts[layout].length];
            Arrays.fill(q, 1);
            List<StateStore.Record> records = sevensLayout(layouts[layout], q);
            int shoes = records.size();
            ScoringStates st = prepare(records, shoes);
            SystemScorer sc = new SystemScorer(st, new Bootstrap(shoes, 2, 1), mPlays[layout] / Math.sqrt(shoes));
            SystemScorer.Pen pen = sc.pen(2);
            SystemScorer.Parts parts = sc.parts(sevens(1), pen);
            int[] all = new int[pen.states.length];
            for (int j = 0; j < all.length; j++) {
                all[j] = j;
            }
            double mPlay = sc.threshold(all.length);
            assertEquals(mPlays[layout], mPlay, 1e-9);
            SystemScorer.Groups groups = sc.groups(parts, pen, all);
            double[] mass = keyMass(st, pen)[0];
            int mode = 0;
            for (int k = 1; k < mass.length; k++) {
                if (mass[k] > mass[mode]) {
                    mode = k;
                }
            }
            int n = groups.count();
            assertTrue(n > 3, "layout " + layout);
            int covered = groups.lo[0];
            assertTrue(mass[covered] > 0 && (covered == 0 || mass[covered - 1] == 0));
            double above = 0;
            double below = 0;
            for (int k = 0; k < mass.length; k++) {
                above += k >= mode ? mass[k] : 0;
                below += k < mode ? mass[k] : 0;
            }
            assertEquals(layout == 1, above < mPlay, "layout " + layout);
            assertEquals(layout == 2, below > 0 && below < mPlay, "layout " + layout);
            for (int g = 0; g < n; g++) {
                assertEquals(covered, groups.lo[g]);
                covered = groups.hi[g] + 1;
                double m = 0;
                for (int k = groups.lo[g]; k <= groups.hi[g]; k++) {
                    m += mass[k];
                }
                assertTrue(m >= mPlay, "layout " + layout + ": group " + g + " holds " + m);
                if (groups.lo[g] >= mode && g < n - 1) {
                    assertTrue(m - mass[groups.hi[g]] < mPlay, "an upward group closed late");
                }
                if (groups.hi[g] < mode && g > 0) {
                    assertTrue(m - mass[groups.lo[g]] < mPlay, "a downward group closed late");
                }
            }
            assertTrue(covered == mass.length || mass[covered] == 0);
            List<int[]> walked = walk(mass, keyMass(st, pen)[1], mPlay);
            assertEquals(walked.size(), n, "layout " + layout);
            for (int g = 0; g < n; g++) {
                assertArrayEquals(walked.get(g), new int[]{groups.lo[g], groups.hi[g]}, "layout " + layout);
            }
            assertEquals(Integer.MAX_VALUE, groups.upper[n - 1]);
            assertEquals(0, groups.of(-50));
            assertEquals(n - 1, groups.of(500));
        }
    }

    /**
     * M_play grows as the square root of the valued states a fit is formed from. The same
     * states four times over, each copy a shoe of its own, hold four times the mass at every
     * key but close groups at twice the threshold. So they make finer groups than the states
     * once, which a threshold growing with the data would not, and coarser ones than the
     * threshold of the states once would make of them, which a fixed threshold would.
     */
    @Test
    public void playGroupsGrowAsTheRootOfTheValuedStates() {
        int[] copies = new int[25];
        for (int k = 0; k <= 24; k++) {
            copies[k] = 1 + (int) (6 * Math.exp(-Math.pow(k - 8, 2) / 30.0));
        }
        double[] q = new double[25];
        Arrays.fill(q, 1);
        List<StateStore.Record> once = sevensLayout(copies, q);
        List<StateStore.Record> four = new ArrayList<>();
        for (int c = 0; c < 4; c++) {
            for (StateStore.Record r : once) {
                four.add(new StateStore.Record(four.size(), 0, r.depth, r.q, r.dealt, r.values));
            }
        }
        double coefficient = 0.5;
        int[] groupCount = new int[2];
        double thresholdOnce = 0;
        for (int run = 0; run < 2; run++) {
            List<StateStore.Record> records = run == 0 ? once : four;
            ScoringStates st = prepare(records, records.size());
            SystemScorer sc = new SystemScorer(st, new Bootstrap(records.size(), 2, 1), coefficient);
            SystemScorer.Pen pen = sc.pen(2);
            int[] all = new int[pen.states.length];
            for (int j = 0; j < all.length; j++) {
                all[j] = j;
            }
            assertEquals(coefficient * Math.sqrt(records.size()), sc.threshold(all.length), 1e-12);
            SystemScorer.Groups groups = sc.groups(sc.parts(sevens(1), pen), pen, all);
            double[][] mass = keyMass(st, pen);
            List<int[]> walked = walk(mass[0], mass[1], sc.threshold(all.length));
            assertEquals(walked.size(), groups.count());
            for (int g = 0; g < groups.count(); g++) {
                assertArrayEquals(walked.get(g), new int[]{groups.lo[g], groups.hi[g]});
            }
            groupCount[run] = groups.count();
            if (run == 0) {
                thresholdOnce = sc.threshold(all.length);
            } else {
                assertTrue(walk(mass[0], mass[1], thresholdOnce).size() > groups.count());
            }
        }
        assertTrue(groupCount[1] > groupCount[0], groupCount[0] + " groups once, " + groupCount[1] + " four times over");
    }

    /**
     * The walk starts at the key holding the most states, each valued state standing for 1/q
     * of them, as every sum over states is weighted; the groups still close on valued states.
     * Here the most valued states have 12 sevens out, valued with q = 1, but the most states
     * have 4 out, valued with q = 0.1, so each stands for ten. Starting from the key with the
     * most valued states would give other groups.
     */
    @Test
    public void theWalkStartsWhereTheRoundsAreNotWhereTheValuedStatesAre() {
        int[] copies = new int[17];
        double[] q = new double[17];
        Arrays.fill(copies, 2);
        Arrays.fill(q, 1);
        copies[4] = 3;
        q[4] = 0.1;
        copies[12] = 9;
        List<StateStore.Record> records = sevensLayout(copies, q);
        int shoes = records.size();
        ScoringStates st = prepare(records, shoes);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(shoes, 2, 1), 6 / Math.sqrt(shoes));
        SystemScorer.Pen pen = sc.pen(2);
        SystemScorer.Parts parts = sc.parts(sevens(1), pen);
        int[] all = new int[pen.states.length];
        for (int j = 0; j < all.length; j++) {
            all[j] = j;
        }
        double mPlay = sc.threshold(all.length);
        SystemScorer.Groups groups = sc.groups(parts, pen, all);
        double[][] mass = keyMass(st, pen);
        List<int[]> weighted = walk(mass[0], mass[1], mPlay);
        List<int[]> unweighted = walk(mass[0], mass[0], mPlay);
        assertFalse(sameGroups(weighted, unweighted));
        assertEquals(weighted.size(), groups.count());
        boolean startsAt4 = false;
        for (int g = 0; g < groups.count(); g++) {
            assertArrayEquals(weighted.get(g), new int[]{groups.lo[g], groups.hi[g]});
            startsAt4 |= groups.lo[g] == 4;
        }
        assertTrue(startsAt4);
    }

    /**
     * M_play's n is the valued states of the fit's own training fold, not of the whole
     * penetration. Three states in four sit in even shoes, so the fold fitted on the odd
     * shoes rests on a quarter of the states and the one fitted on the even shoes on three
     * quarters. Each fold's groups, as scored, must be the ones walked from its training
     * states alone at M_PLAY x sqrt(their number), and here those differ from the groups the
     * threshold of every state in the penetration would give.
     */
    @Test
    public void eachFoldsGroupsCloseAtTheRootOfItsOwnTrainingStates() {
        int[] copies = new int[25];
        for (int k = 0; k <= 24; k++) {
            copies[k] = 2 + (int) (10 * Math.exp(-Math.pow(k - 9, 2) / 30.0));
        }
        double[] q = new double[25];
        for (int k = 0; k <= 24; k++) {
            q[k] = 0.2 + 0.8 * ((k * 7) % 5) / 4.0;
        }
        List<StateStore.Record> laid = sevensLayout(copies, q);
        List<StateStore.Record> records = new ArrayList<>();
        for (int t = 0; t < laid.size(); t++) {
            StateStore.Record x = laid.get(t);
            records.add(new StateStore.Record(t % 4 == 3 ? 2 * t + 1 : 2 * t, 0, x.depth, x.q, x.dealt, x.values));
        }
        int shoes = 2 * records.size() + 2;
        ScoringStates st = prepare(records, shoes);
        double coefficient = 0.8;
        SystemScorer sc = new SystemScorer(st, new Bootstrap(shoes, 2, 1), coefficient);
        SystemScorer.Result res = sc.score(sevens(1), null);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            SystemScorer.Pen pen = sc.pen(p);
            boolean wholeWouldDiffer = false;
            for (int f = 0; f < 2; f++) {
                int[] train = pen.train[f];
                assertTrue(train.length > 0 && train.length < pen.states.length);
                double[][] mass = new double[2][40];
                for (int j : train) {
                    int s = pen.states[j];
                    int seen = st.dealt[s][12] + st.dealt[s][13];
                    for (int i = 0; i < Deals.COUNT; i++) {
                        mass[0][seen + sevensIn(i)] += st.chance[s][i];
                        mass[1][seen + sevensIn(i)] += st.chance[s][i] * st.weight[s];
                    }
                }
                double threshold = coefficient * Math.sqrt(train.length);
                List<int[]> walked = walk(mass[0], mass[1], threshold);
                SystemScorer.Groups groups = res.groups[p][f];
                assertEquals(walked.size(), groups.count(), "pen " + p + ", fold " + f);
                for (int g = 0; g < groups.count(); g++) {
                    assertArrayEquals(walked.get(g), new int[]{groups.lo[g], groups.hi[g]}, "pen " + p + ", fold " + f);
                }
                wholeWouldDiffer |= !sameGroups(walked, walk(mass[0], mass[1],
                        coefficient * Math.sqrt(pen.states.length)));
            }
            assertTrue(wholeWouldDiffer, "pen " + p);
        }
    }

    // --------------------------------------------------------------------- bets

    @Test
    public void theAdvantageIsFittedNonDecreasing() {
        double[] out = SystemScorer.isotonic(new int[]{-2, -1, 0, 1, 3}, new double[]{1, 1, 1, 1, 0},
                new double[]{1, 3, 2, 4, 99});
        assertArrayEquals(new double[]{1, 2.5, 2.5, 4, 4}, out, EXACT);
        out = SystemScorer.isotonic(new int[]{0, 1, 2}, new double[]{1, 1, 1}, new double[]{3, 2, 1});
        assertArrayEquals(new double[]{2, 2, 2}, out, EXACT);
        // Pooling can cascade: 0 pools with 3 into 1.5, which is then under 2 and pools again.
        // Stopping after one pooling would leave 2 above 1.5.
        out = SystemScorer.isotonic(new int[]{0, 1, 2}, new double[]{1, 1, 1}, new double[]{2, 3, 0});
        assertArrayEquals(new double[]{5.0 / 3, 5.0 / 3, 5.0 / 3}, out, EXACT);
        // Weighted: the heavier point pulls the pooled mean toward it.
        out = SystemScorer.isotonic(new int[]{0, 1}, new double[]{3, 1}, new double[]{6, 1});
        assertArrayEquals(new double[]{7.0 / 4, 7.0 / 4}, out, EXACT);
        // A key with no weight lies on the line between its neighbours.
        out = SystemScorer.isotonic(new int[]{0, 1, 4}, new double[]{1, 0, 1}, new double[]{0, 0, 2});
        assertArrayEquals(new double[]{0, 0.5, 2}, out, EXACT);
    }

    /**
     * States whose advantage rises with the sevens seen, the chart the best play everywhere
     * by a unit. The sevens count orders the states perfectly, so the same schedule hands
     * each test state exactly the bet the proportional rule puts on its own true advantage,
     * and so does its own ramp. A count that reads the sevens backwards sees the advantage
     * fall with its key, so its fitted advantage is flat and every state gets the schedule's
     * mean bet. Either way the average bet is the schedule's.
     */
    @Test
    public void theSameScheduleHandsOutTheReferenceBetsInTheCountsOrder() {
        List<StateStore.Record> records = new ArrayList<>();
        int[] chart = ScoringStates.chartMoves(RULES);
        // The player's naturals add about 2% in these ten-poor shoes, so the advantages run
        // from about -1.4% to +2.8%: from the smallest bet past the largest at every spread.
        for (int k = 0; k < 12; k++) {
            double a = -0.035 + 0.004 * k;
            for (int f = 0; f < 2; f++) {
                records.add(state(2 * k + f, 0, tenPoor(2 * k), 0.2 + 0.05 * k, (i, m) -> m == chart[i] ? a : a - 1));
            }
        }
        int shoes = 24;
        SystemScorer sc = new SystemScorer(prepare(records, shoes), new Bootstrap(shoes, 5, 1), 1e-9);
        double[] adv = new double[records.size()];
        double weight = 0;
        for (int s = 0; s < adv.length; s++) {
            StateStore.Record r = records.get(s);
            adv[s] = RoundValue.of(r.ranksLeft(), r.values, 1.5, i -> chart[i], i -> false);
            weight += 1 / r.q;
            if (s >= 2) {
                assertTrue(adv[s] > adv[s - 2]);
            }
        }
        assertTrue(adv[0] < 0 && adv[adv.length - 1] > SystemScorer.SPREADS * SystemScorer.UNIT);
        SystemScorer.Result up = sc.score(sevens(1), null);
        SystemScorer.Result down = sc.score(sevens(-1), null);
        double meanAdv = 0;
        for (int s = 0; s < adv.length; s++) {
            meanAdv += adv[s] / records.get(s).q / weight;
        }
        for (int spread = 1; spread <= SystemScorer.SPREADS; spread++) {
            double ev = 0;
            double bet = 0;
            double played = 0;
            for (int s = 0; s < adv.length; s++) {
                double w = 1 / records.get(s).q / weight;
                double b = Math.max(1, Math.min(spread, adv[s] / SystemScorer.UNIT));
                ev += w * b * adv[s];
                bet += w * b;
                played += w * Math.floor(b + 0.5) * adv[s];
            }
            double[] est = up.entries.get(0).estimate[2];
            assertEquals(ev, est[SystemScorer.same(spread)], EXACT, "spread " + spread);
            assertEquals(bet, est[SystemScorer.sameBet(spread)], EXACT);
            assertEquals(ev, est[SystemScorer.own(spread)], EXACT);
            assertEquals(bet, est[SystemScorer.ownBet(spread)], EXACT);
            assertEquals(played, est[SystemScorer.played(spread)], EXACT);
            double[] rev = down.entries.get(0).estimate[2];
            assertEquals(bet * meanAdv, rev[SystemScorer.same(spread)], EXACT, "spread " + spread);
            assertEquals(bet, rev[SystemScorer.sameBet(spread)], EXACT);
        }
        assertTrue(up.entries.get(0).estimate[2][SystemScorer.same(8)]
                > down.entries.get(0).estimate[2][SystemScorer.same(8)] + 0.01);
    }

    /**
     * The reference bets are the ones the proportional rule puts on each state's chart
     * advantage, whatever the count plays. Here a move other than the chart's is worth
     * 0.002 more in every deal, so the count's fitted play beats the chart by about a unit
     * in every state. It still orders the states perfectly, so each test state gets the
     * reference bet of its own chart advantage, and wins its own round value on it. Bets
     * taken from the count's round value instead would be higher by about a unit.
     */
    @Test
    public void theReferenceBetsAreOnTheChartAdvantageWhateverTheCountPlays() {
        List<StateStore.Record> records = new ArrayList<>();
        int[] chart = ScoringStates.chartMoves(RULES);
        int[] other = new int[Deals.COUNT];
        for (int i = 0; i < Deals.COUNT; i++) {
            other[i] = chart[i] == STAND ? HIT : STAND;
        }
        for (int k = 0; k < 12; k++) {
            double a = -0.035 + 0.004 * k;
            for (int f = 0; f < 2; f++) {
                records.add(state(2 * k + f, 0, tenPoor(2 * k), 0.2 + 0.05 * k, (i, m) -> m == chart[i] ? a
                        : m == other[i] ? a + 0.002 : a - 1));
            }
        }
        int shoes = 24;
        SystemScorer sc = new SystemScorer(prepare(records, shoes), new Bootstrap(shoes, 5, 1), 1e-9);
        SystemScorer.Result res = sc.score(sevens(1), null);
        double[] est = res.entries.get(0).estimate[2];
        double weight = 0;
        for (StateStore.Record r : records) {
            weight += 1 / r.q;
        }
        boolean differs = false;
        for (int spread = 1; spread <= SystemScorer.SPREADS; spread++) {
            double ev = 0;
            double fromOwn = 0;
            for (StateStore.Record r : records) {
                double w = 1 / r.q / weight;
                double adv = RoundValue.of(r.ranksLeft(), r.values, 1.5, i -> chart[i], i -> false);
                double rv = RoundValue.of(r.ranksLeft(), r.values, 1.5, i -> other[i], i -> false);
                ev += w * SystemScorer.clamp(adv / SystemScorer.UNIT, spread) * rv;
                fromOwn += w * SystemScorer.clamp(rv / SystemScorer.UNIT, spread) * rv;
            }
            assertEquals(ev, est[SystemScorer.same(spread)], EXACT, "spread " + spread);
            differs |= Math.abs(fromOwn - ev) > 1e-4;
        }
        assertTrue(differs);
    }

    // ------------------------------------------------------------ whole counts

    /** Random states and values: nothing about them is special. */
    static List<StateStore.Record> randomStates(Random rnd, int shoes) {
        List<StateStore.Record> out = new ArrayList<>();
        for (int shoe = 0; shoe < shoes; shoe++) {
            int n = rnd.nextInt(3);
            for (int round = 0; round < n; round++) {
                int depth = rnd.nextInt(332);
                double base = 0.03 * rnd.nextGaussian();
                out.add(state(shoe, round, CountKeysTest.dealt(rnd, depth), 0.1 + 0.9 * rnd.nextDouble(),
                        (i, m) -> base + 0.2 * rnd.nextGaussian() - 0.1 * m));
            }
        }
        return out;
    }

    /**
     * Duplicate systems give identical results, resample by resample, so a system compared
     * with its duplicate has a difference of exactly zero: the bootstrap vectors are shared.
     */
    @Test
    public void duplicatesScoreIdenticallyOnSharedResamples() {
        Random rnd = new Random(17);
        int shoes = 60;
        List<StateStore.Record> records = randomStates(rnd, shoes);
        SystemScorer sc = new SystemScorer(prepare(records, shoes), new Bootstrap(shoes, 40, 3), 3);
        String[][] pairs = {{"Hi-Lo", "Hi-Lo Lite"}, {"Revere Point Count (RPC)", "C-R (Chambliss-Roginski)"},
                {"KO (Knock-Out)", "REKO (Ridiculously Easy Knock-Out)"}};
        for (String[] pair : pairs) {
            SystemScorer.Result a = sc.score(CountKeys.of(CountingSystem.named(pair[0]), ""), null);
            SystemScorer.Result b = sc.score(CountKeys.of(CountingSystem.named(pair[1]), ""), null);
            for (int e = 0; e < a.entries.size(); e++) {
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    assertArrayEquals(a.entries.get(e).estimate[p], b.entries.get(e).estimate[p], 0, pair[1]);
                    for (int m = 0; m < SystemScorer.METRICS; m++) {
                        double[] diff = new double[a.entries.get(e).boot[p][m].length];
                        for (int r = 0; r < diff.length; r++) {
                            diff[r] = a.entries.get(e).boot[p][m][r] - b.entries.get(e).boot[p][m][r];
                        }
                        Bootstrap.Interval iv = Bootstrap.interval(0, diff);
                        assertEquals(0, iv.se, 0);
                        assertEquals(0, iv.lo, 0);
                        assertEquals(0, iv.hi, 0);
                    }
                }
            }
        }
    }

    /**
     * The null count has one group, so its play is one per deal; a side-count variant keeps
     * its base's play and insurance and moves only the bets; betting only plays the chart.
     */
    @Test
    public void theNullCountSideCountsAndBettingOnlyAreWhatTheySay() {
        Random rnd = new Random(23);
        int shoes = 50;
        List<StateStore.Record> records = randomStates(rnd, shoes);
        ScoringStates st = prepare(records, shoes);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(shoes, 10, 3), 2);
        SystemScorer.Result none = sc.score(CountKeys.nullCount(), null);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            assertEquals(1, none.groups[p][0].count());
            assertEquals(1, none.groups[p][1].count());
        }
        CountKeys.Count omega = CountKeys.of(CountingSystem.named("Omega II"), "");
        SystemScorer.Result res = sc.score(omega, omega.sideCounted());
        assertEquals(4, res.entries.size());
        SystemScorer.Entry plain = res.entries.get(0);
        SystemScorer.Entry side = res.entries.get(2);
        assertEquals("Omega II + ace side count", side.name);
        // Scoring the variant beside the base leaves the base's play where it is alone: the
        // same groups and the same figures, resample by resample.
        SystemScorer.Result alone = sc.score(omega, null);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            for (int f = 0; f < 2; f++) {
                assertArrayEquals(alone.groups[p][f].lo, res.groups[p][f].lo);
                assertArrayEquals(alone.groups[p][f].hi, res.groups[p][f].hi);
            }
            for (int e = 0; e < 2; e++) {
                assertArrayEquals(alone.entries.get(e).estimate[p], res.entries.get(e).estimate[p], 0);
                for (int m = 0; m < SystemScorer.METRICS; m++) {
                    assertArrayEquals(alone.entries.get(e).boot[p][m], res.entries.get(e).boot[p][m], 0);
                }
            }
        }
        boolean betsDiffer = false;
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            for (int m : new int[]{SystemScorer.FLAT, SystemScorer.PLAY, SystemScorer.INSURANCE}) {
                assertEquals(plain.estimate[p][m], side.estimate[p][m], EXACT);
                assertArrayEquals(plain.boot[p][m], side.boot[p][m], EXACT);
            }
            betsDiffer |= plain.estimate[p][SystemScorer.same(8)] != side.estimate[p][SystemScorer.same(8)];
            // Betting only plays the chart, so its flat EV before insurance is the chart's.
            double chart = 0;
            double w = 0;
            for (int s = 0; s < st.size; s++) {
                if (st.depth[s] < SystemScorer.CUTS[p]) {
                    chart += st.weight[s] * st.chart[s];
                    w += st.weight[s];
                }
            }
            assertEquals(chart / w, res.entries.get(1).estimate[p][SystemScorer.PLAY], EXACT);
            assertEquals(res.entries.get(0).estimate[p][SystemScorer.INSURANCE],
                    res.entries.get(1).estimate[p][SystemScorer.INSURANCE], EXACT);
        }
        assertTrue(betsDiffer);
    }

    /**
     * Each entry's per-shoe sums, which the per-shoe SE of the headline is taken from, are
     * the headline's own sums split by shoe: added up over the shoes and divided by the
     * penetration's weight they give the 1-8 same-schedule EV. Here the own ramp's EV differs
     * from it, so sums taken at the own ramp's bets would not add up.
     */
    @Test
    public void thePerShoeSumsAddUpToTheHeadlineEstimate() {
        Random rnd = new Random(43);
        int shoes = 60;
        List<StateStore.Record> records = randomStates(rnd, shoes);
        ScoringStates st = prepare(records, shoes);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(shoes, 3, 1), 2);
        CountKeys.Count omega = CountKeys.of(CountingSystem.named("Omega II"), "");
        SystemScorer.Result res = sc.score(omega, omega.sideCounted());
        int same = SystemScorer.same(SystemScorer.SPREADS);
        int own = SystemScorer.own(SystemScorer.SPREADS);
        boolean differ = false;
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            double w = 0;
            for (int s = 0; s < st.size; s++) {
                w += st.depth[s] < SystemScorer.CUTS[p] ? st.weight[s] : 0;
            }
            for (SystemScorer.Entry e : res.entries) {
                assertEquals(shoes, e.shoeSums[p].length);
                double sum = 0;
                for (double x : e.shoeSums[p]) {
                    sum += x;
                }
                assertEquals(e.estimate[p][same], sum / w, EXACT, e.name);
                differ |= Math.abs(e.estimate[p][own] - e.estimate[p][same]) > 1e-4;
            }
        }
        assertTrue(differ);
    }

    // --------------------------------------------------------- decision resolution

    /**
     * Every state has the same cards, so every deal has the same chance everywhere. In 16
     * against a ten, the even shoes give Double over Stand gaps of +0.02, -0.01, +0.03 and
     * -0.02: Double is best by a mean 0.005 (times 1 - P(dealer natural)), but the per-shoe
     * spread makes its standard error larger than that, so the decision is unsettled. Stand
     * could be better by as much as 2 SE - gap, so keeping Double costs at most frequency x
     * (2 SE - gap), worked out here by hand. Hit trails by a whole unit and is settled. Every
     * other decision, and every decision fitted on the odd shoes, is the same in every shoe
     * and settled.
     */
    @Test
    public void aCloseCallIsFoundAndBounded() {
        int hard16 = Deals.index(10, 6, 10);
        double[] gaps = {0.02, -0.01, 0.03, -0.02};
        List<StateStore.Record> records = new ArrayList<>();
        for (int shoe = 0; shoe < 8; shoe++) {
            boolean even = shoe % 2 == 0;
            double g = even ? gaps[shoe / 2] : 0.1;
            records.add(state(shoe, 0, tenPoor(0), 1, (i, m) -> i == hard16
                    ? (m == DOUBLE ? g : m == STAND ? 0 : -1)
                    : (m == STAND ? 0 : -0.5)));
        }
        ScoringStates st = prepare(records, 8);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(8, 5, 1), SystemScorer.M_PLAY);
        SystemScorer.Result res = sc.score(CountKeys.nullCount(), null);
        SystemScorer.Resolution r = res.resolution[2];
        int decisions = 0;
        for (int i = 0; i < Deals.COUNT; i++) {
            if (!Deals.playerNatural(i) && st.chance[0][i] > 0) {
                decisions++;
            }
        }
        assertEquals(2 * decisions, r.decisions);
        assertEquals(1, r.unsettled);
        SystemScorer.CloseCall cc = r.closest.get(0);
        assertEquals(hard16, cc.deal);
        assertEquals(DOUBLE, cc.best);
        assertEquals(STAND, cc.against);
        assertEquals(1, cc.fold);
        int[] left = ShoeRun.ranksLeft(tenPoor(0));
        double nat = Deals.dealerNatural(left, hard16);
        double mean = 0.005;
        double ss = 0;
        for (double g : gaps) {
            ss += (g - mean) * (g - mean);
        }
        double gap = (1 - nat) * mean;
        double se = (1 - nat) * Math.sqrt(4.0 / 3 * ss) / 4;
        double frequency = Deals.probability(left, hard16);
        assertEquals(gap, cc.gap, EXACT);
        assertEquals(se, cc.se, EXACT);
        assertEquals(frequency, cc.frequency, EXACT);
        assertTrue(gap < 2 * se);
        // One fold's fit carries the close call; the folds have equal test weight.
        assertEquals(frequency * (2 * se - gap) / 2, r.bound, EXACT);
        assertEquals(0, r.insuranceUnsettled);
        assertEquals(2 * ScoringStates.ACE_UP, r.insuranceDecisions);
    }

    /**
     * A decision is settled only when the fitted move is shown ahead of every other move, not
     * just the runner-up. In 16 against a ten, Stand is worth 0 in every shoe and Hit -0.05,
     * so Stand beats the runner-up Hit with no spread at all. Double is worth a mean -0.06
     * but swings from shoe to shoe, so Stand is not shown ahead of it: the decision is a close
     * call against Double, bounded by frequency x (2 SE - gap) for that gap.
     */
    @Test
    public void aDecisionIsSettledOnlyAgainstEveryOtherMove() {
        int hard16 = Deals.index(10, 6, 10);
        double[] doubles = {0.3, -0.4, 0.2, -0.34};
        List<StateStore.Record> records = new ArrayList<>();
        for (int shoe = 0; shoe < 8; shoe++) {
            boolean even = shoe % 2 == 0;
            double d = even ? doubles[shoe / 2] : -0.5;
            records.add(state(shoe, 0, tenPoor(0), 1, (i, m) -> i == hard16
                    ? (m == STAND ? 0 : m == HIT ? -0.05 : d)
                    : (m == STAND ? 0 : -0.5)));
        }
        ScoringStates st = prepare(records, 8);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(8, 5, 1), SystemScorer.M_PLAY);
        SystemScorer.Resolution r = sc.score(CountKeys.nullCount(), null).resolution[2];
        assertEquals(1, r.unsettled);
        SystemScorer.CloseCall cc = r.closest.get(0);
        assertEquals(hard16, cc.deal);
        assertEquals(STAND, cc.best);
        assertEquals(DOUBLE, cc.against);
        assertEquals(1, cc.fold);
        int[] left = ShoeRun.ranksLeft(tenPoor(0));
        double nat = Deals.dealerNatural(left, hard16);
        double mean = -0.06;
        double ss = 0;
        for (double v : doubles) {
            ss += (v - mean) * (v - mean);
        }
        double gap = (1 - nat) * -mean;
        double se = (1 - nat) * Math.sqrt(4.0 / 3 * ss) / 4;
        assertEquals(gap, cc.gap, EXACT);
        assertEquals(se, cc.se, EXACT);
        assertTrue(gap < 2 * se);
        assertTrue(-0.05 > mean, "Hit, not Double, is the runner-up");
        assertEquals(Deals.probability(left, hard16) * (2 * se - gap) / 2, r.bound, EXACT);
    }

    /**
     * The standard error of a gap comes from per-shoe sums, each state weighted 1/q, and a
     * decision is settled only past twice it. Every state has the same cards. In 16 against a
     * ten, each even shoe holds two states, one valued with q = 1 and one with q = 0.5, whose
     * Double over Stand gaps are d1 and d2. So shoe k adds x = d1 + 2 d2 to the weighted sum
     * and 3 to the weight, and over the four shoes x = 0.12, -0.03, 0.09 and 0: a mean gap of
     * 0.18 / 12 = 0.015 per unit of weight, and residuals x - 3 x 0.015 of 0.075, -0.075,
     * 0.045 and -0.045, so SE = sqrt(4/3 x 0.0153) / 12 = 0.0119, all times 1 - P(dealer
     * natural). The gap is 1.26 SE: more than one SE, less than two, so the decision is
     * unsettled, and it costs at most frequency x (2 SE - gap). Weighting the states alike, or
     * taking each state as its own cluster, gives another SE. The odd shoes, one state each,
     * settle everything; their fold has a third of the test weight, so the bound averaged over
     * the folds by test weight is a quarter of the one fold's.
     */
    @Test
    public void aGapIsSettledPastTwoStandardErrorsFromWeightedPerShoeSums() {
        int hard16 = Deals.index(10, 6, 10);
        double[][] d = {{0.04, 0.04}, {0.05, -0.04}, {-0.01, 0.05}, {0.02, -0.01}};
        double[] qs = {1, 0.5};
        List<StateStore.Record> records = new ArrayList<>();
        for (int shoe = 0; shoe < 8; shoe++) {
            boolean even = shoe % 2 == 0;
            for (int round = 0; round < (even ? 2 : 1); round++) {
                double g = even ? d[shoe / 2][round] : -0.5;
                records.add(state(shoe, round, tenPoor(0), even ? qs[round] : 1, (i, m) -> i == hard16
                        ? (m == DOUBLE ? g : m == STAND ? 0 : -1)
                        : (m == STAND ? 0 : -0.5)));
            }
        }
        ScoringStates st = prepare(records, 8);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(8, 3, 1), SystemScorer.M_PLAY);
        SystemScorer.Resolution r = sc.score(CountKeys.nullCount(), null).resolution[2];
        assertEquals(1, r.unsettled);
        SystemScorer.CloseCall cc = r.closest.get(0);
        assertEquals(hard16, cc.deal);
        assertEquals(DOUBLE, cc.best);
        assertEquals(STAND, cc.against);
        assertEquals(1, cc.fold);
        int[] left = ShoeRun.ranksLeft(tenPoor(0));
        double nat = Deals.dealerNatural(left, hard16);
        double[] x = new double[4];
        double sum = 0;
        for (int k = 0; k < 4; k++) {
            x[k] = d[k][0] / qs[0] + d[k][1] / qs[1];
            sum += x[k];
        }
        double weight = 4 * (1 / qs[0] + 1 / qs[1]);
        double mean = sum / weight;
        double ss = 0;
        for (int k = 0; k < 4; k++) {
            double e = x[k] - (1 / qs[0] + 1 / qs[1]) * mean;
            ss += e * e;
        }
        double gap = (1 - nat) * mean;
        double se = (1 - nat) * Math.sqrt(4.0 / 3 * ss) / weight;
        assertEquals(0.015, mean, 1e-15);
        assertEquals(0.0153, ss, 1e-15);
        assertEquals(gap, cc.gap, EXACT);
        assertEquals(se, cc.se, EXACT);
        assertTrue(se < gap && gap < 2 * se, "the gap must lie between one and two SE: " + gap / se);
        double frequency = Deals.probability(left, hard16);
        assertEquals(frequency, cc.frequency, EXACT);
        // Fold 1's test weight is the four odd states, q = 1; fold 0's the even ones, 12 in all.
        assertEquals(frequency * (2 * se - gap) * 4 / 16, r.bound, EXACT);
        assertEquals(0, r.insuranceUnsettled);
    }

    /**
     * Insurance close calls are bounded as the play's are, and their bound enters the
     * decision bound, so its value is worked out here in full. The even shoes mix ten-rich
     * states, where insurance pays in every ace-up deal, with ten-poor ones, where it pays in
     * none, valued with q from 0.25 to 1 and up to two to a shoe. So in each ace-up deal the
     * weighted insurance value changes sign from shoe to shoe. For each, with A and B the
     * weighted sums over the training fold of the insurance value and of the deal's chance,
     * and W the fold's weight: gap = |A| / B, per-shoe residuals a - gap x b with a signed as
     * the fitted choice, SE = sqrt(n / (n - 1) x their squares) / B over the fold's n shoes,
     * unsettled unless gap > 2 SE, and bound (B / W) x (2 SE - gap). The fold fitted on the odd
     * shoes, all ten-poor alike, settles every one. The two folds' bounds are averaged by
     * their test weights.
     */
    @Test
    public void anInsuranceCloseCallIsBoundedByItsFrequencyTimesTwoSeLessTheGap() {
        int[] chart = ScoringStates.chartMoves(RULES);
        // Even shoes: {rich?, q} per state.
        Object[][][] even = {
                {{true, 1.0}, {false, 0.5}},
                {{false, 1.0}},
                {{true, 0.25}},
                {{false, 1.0}, {true, 0.5}},
        };
        List<StateStore.Record> records = new ArrayList<>();
        for (int shoe = 0; shoe < 8; shoe++) {
            Object[][] states = shoe % 2 == 0 ? even[shoe / 2] : new Object[][]{{false, 1.0}};
            for (int round = 0; round < states.length; round++) {
                boolean rich = (Boolean) states[round][0];
                records.add(state(shoe, round, rich ? tenRich() : tenPoor(0), (Double) states[round][1],
                        (i, m) -> m == chart[i] ? 0 : -1));
            }
        }
        ScoringStates st = prepare(records, 8);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(8, 3, 1), SystemScorer.M_PLAY);
        SystemScorer.Resolution r = sc.score(CountKeys.nullCount(), null).resolution[2];

        int[] richLeft = ShoeRun.ranksLeft(tenRich());
        int[] poorLeft = ShoeRun.ranksLeft(tenPoor(0));
        double trainWeight = 0;
        double testWeight = 0;
        for (Object[][] shoe : even) {
            for (Object[] s : shoe) {
                trainWeight += 1 / (Double) s[1];
            }
        }
        testWeight = 4;
        int unsettled = 0;
        int decisions = 0;
        double bound = 0;
        boolean someSettled = false;
        for (int i = 0; i < ScoringStates.ACE_UP; i++) {
            double[] a = new double[4];
            double[] b = new double[4];
            double sa = 0;
            double sb = 0;
            for (int k = 0; k < 4; k++) {
                for (Object[] s : even[k]) {
                    int[] left = (Boolean) s[0] ? richLeft : poorLeft;
                    double w = 1 / (Double) s[1];
                    double p = Deals.probability(left, i);
                    a[k] += w * p * RoundValue.insurance(left, i);
                    b[k] += w * p;
                }
                sa += a[k];
                sb += b[k];
            }
            if (sb == 0) {
                continue;
            }
            decisions++;
            double gap = Math.abs(sa) / sb;
            double ss = 0;
            for (int k = 0; k < 4; k++) {
                double e = Math.signum(sa) * a[k] - gap * b[k];
                ss += e * e;
            }
            double se = Math.sqrt(4.0 / 3 * ss) / sb;
            if (gap > 2 * se) {
                someSettled = true;
            } else {
                unsettled++;
                bound += sb / trainWeight * (2 * se - gap);
            }
        }
        assertTrue(unsettled > 0 && someSettled, unsettled + " of " + decisions + " unsettled");
        assertEquals(2 * decisions, r.insuranceDecisions);
        assertEquals(unsettled, r.insuranceUnsettled);
        // Fold 1, fitted on the even shoes, has the odd shoes' test weight, 4; fold 0 has the even shoes'.
        assertEquals(testWeight * bound / (testWeight + trainWeight), r.insuranceBound, 1e-15);
        assertTrue(r.insuranceBound > 1e-4, "a bound too small to tell a factor from rounding: " + r.insuranceBound);
        assertEquals(r.bound + r.insuranceBound, r.total(), 0);
    }

    /**
     * The run resample r stands for: every shoe as many times as r drew it, each copy a shoe
     * of its own in the same fold, numbered again from 0 in order.
     */
    static List<StateStore.Record> resampled(List<StateStore.Record> records, Bootstrap boot, int r, int shoes) {
        List<List<Integer>> drawn = new ArrayList<>();
        drawn.add(new ArrayList<>());
        drawn.add(new ArrayList<>());
        for (int shoe = 0; shoe < shoes; shoe++) {
            for (int k = 0; k < boot.mult(r, shoe); k++) {
                drawn.get(shoe % 2).add(shoe);
            }
        }
        List<StateStore.Record> out = new ArrayList<>();
        for (int t = 0; t < shoes; t++) {
            int from = drawn.get(t % 2).get(t / 2);
            for (StateStore.Record x : records) {
                if (x.shoe == from) {
                    out.add(new StateStore.Record(t, x.round, x.depth, x.q, x.dealt, x.values));
                }
            }
        }
        return out;
    }

    /**
     * A bootstrap resample scores exactly as the run it stands for would be scored: every
     * figure of every entry at every penetration, from the play's groups and moves through
     * insurance to the bets, is fitted again from the resampled training shoes, as the
     * estimate was fitted from the real ones. A bootstrap that kept the estimate's play would
     * score the copied run with a play that run's own fit does not make, and here, with
     * values that are mostly noise and small groups, the fit moves from resample to resample.
     */
    @Test
    public void aResampleScoresAsTheRunItStandsFor() {
        Random rnd = new Random(29);
        int shoes = 60;
        List<StateStore.Record> records = randomStates(rnd, shoes);
        ScoringStates st = prepare(records, shoes);
        Bootstrap boot = new Bootstrap(shoes, 4, 11);
        double mPlay = 1;
        SystemScorer sc = new SystemScorer(st, boot, mPlay);
        for (String name : new String[]{"Omega II", "Red Seven (Red 7)"}) {
            CountKeys.Count c = CountKeys.of(CountingSystem.named(name), "");
            CountKeys.Count side = CountKeys.sideCountVariant(c);
            SystemScorer.Result res = sc.score(c, side);
            boolean moved = false;
            for (int r = 0; r < boot.resamples; r++) {
                ScoringStates copied = prepare(resampled(records, boot, r, shoes), shoes);
                SystemScorer.Result one = new SystemScorer(copied, new Bootstrap(shoes, 1, 1), mPlay).score(c, side);
                assertEquals(res.entries.size(), one.entries.size());
                for (int e = 0; e < res.entries.size(); e++) {
                    for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                        for (int m = 0; m < SystemScorer.METRICS; m++) {
                            assertEquals(one.entries.get(e).estimate[p][m], res.entries.get(e).boot[p][m][r], 1e-12,
                                    name + ", " + res.entries.get(e).name + ", resample " + r + ", pen " + p + ", metric " + m);
                        }
                    }
                }
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    for (int f = 0; f < 2; f++) {
                        moved |= !Arrays.equals(one.groups[p][f].upper, res.groups[p][f].upper);
                    }
                }
            }
            assertTrue(moved, name);
        }
    }

    @Test
    public void theBootstrapResamplesShoesWithinEachFold() {
        Bootstrap b = new Bootstrap(11, 200, 9);
        for (int r = 0; r < 200; r++) {
            int[] total = new int[2];
            for (int shoe = 0; shoe < 11; shoe++) {
                total[shoe % 2] += b.mult(r, shoe);
            }
            assertEquals(6, total[0]);
            assertEquals(5, total[1]);
        }
        Bootstrap again = new Bootstrap(11, 200, 9);
        for (int r = 0; r < 200; r++) {
            for (int shoe = 0; shoe < 11; shoe++) {
                assertEquals(b.mult(r, shoe), again.mult(r, shoe));
            }
        }
        // A skewed statistic: the interval turns basic once the bias is not small.
        double[] boot = new double[1000];
        for (int r = 0; r < boot.length; r++) {
            boot[r] = 1 + r / 1000.0;
        }
        Bootstrap.Interval iv = Bootstrap.interval(1.5, boot);
        assertFalse(iv.basic);
        assertEquals(Bootstrap.quantile(boot, 0.025), iv.lo, EXACT);
        iv = Bootstrap.interval(1.0, boot);
        assertTrue(iv.basic);
        assertEquals(2 - Bootstrap.quantile(boot, 0.975), iv.lo, EXACT);
    }

    /**
     * The max-|t| multiplier is the 0.95 quantile over resamples of the largest |t| among the
     * estimates. Estimates that move together, here the same resamples three times, act as
     * one, so c is the 95th percentile of one column's |t|, which for these resamples, spaced
     * evenly from -2 to 2 about an estimate of 0, is worked out here directly. Estimates that move
     * independently need a wider c than any one of them, since some one of them is far out
     * more often. An estimate with no spread cannot be wrong and is left out.
     */
    @Test
    public void theMaxTMultiplierIsThe95thPercentileOfTheLargestT() {
        int n = 401;
        double[] col = new double[n];
        for (int r = 0; r < n; r++) {
            col[r] = -2 + 4.0 * r / (n - 1);
        }
        double se = Bootstrap.interval(0, col).se;
        double[] t = new double[n];
        for (int r = 0; r < n; r++) {
            t[r] = Math.abs(col[r]) / se;
        }
        Arrays.sort(t);
        // |t| takes each value twice but 0 once, so the 0.95 quantile at position 380 is the 190th step up.
        assertEquals(190 * 0.01 / se, Bootstrap.quantile(t, 0.95), 1e-12);
        double c = Bootstrap.maxT(new double[]{0, 0, 0}, new double[][]{col, col, col});
        assertEquals(Bootstrap.quantile(t, 0.95), c, 1e-12);
        assertEquals(c, Bootstrap.maxT(new double[]{0, 5}, new double[][]{col, new double[n]}), 0);

        Random rnd = new Random(3);
        double[][] independent = new double[5][n];
        for (double[] x : independent) {
            for (int r = 0; r < n; r++) {
                x[r] = rnd.nextGaussian();
            }
        }
        double one = Bootstrap.maxT(new double[]{0}, new double[][]{independent[0]});
        double five = Bootstrap.maxT(new double[5], independent);
        assertTrue(one > 1.8 && one < 2.2, "one normal column: " + one);
        assertTrue(five > one + 0.3, one + " for one column, " + five + " for five");
    }

    // ------------------------------------------------------------- more on bets

    /**
     * Two test states share each key of the sevens count and differ in advantage by a
     * little, so the count cannot tell them apart. Its keys still order the states rightly
     * across keys, so each key's range of the reference distribution is exactly its own two
     * states' reference bets, and it bets their average, weighted by 1/q, on both. The own
     * ramp bets the isotonic advantage fitted on the other fold, the training states' mean
     * round value at that key weighted by 1/q.
     */
    @Test
    public void aKeyThatSpansSeveralStatesBetsTheirAverageReferenceBet() {
        int[] chart = ScoringStates.chartMoves(RULES);
        List<StateStore.Record> records = new ArrayList<>();
        int keys = 6;
        double[] qs = {0.2, 0.5, 0.25, 0.8};
        for (int k = 0; k < keys; k++) {
            for (int c = 0; c < 4; c++) {
                double a = -0.035 + 0.008 * k + (c < 2 ? -0.001 : 0.001) * (1 + k % 2);
                records.add(state(4 * k + c, 0, tenPoor(2 * k), qs[c], (i, m) -> m == chart[i] ? a : a - 1));
            }
        }
        int shoes = 4 * keys;
        SystemScorer sc = new SystemScorer(prepare(records, shoes), new Bootstrap(shoes, 3, 1), 1e-9);
        SystemScorer.Result res = sc.score(sevens(1), null);
        double[] adv = new double[records.size()];
        double weight = 0;
        for (int j = 0; j < adv.length; j++) {
            StateStore.Record r = records.get(j);
            adv[j] = RoundValue.of(r.ranksLeft(), r.values, 1.5, i -> chart[i], i -> false);
            weight += 1 / r.q;
        }
        for (int spread = 1; spread <= SystemScorer.SPREADS; spread++) {
            double same = 0;
            double own = 0;
            for (int k = 0; k < keys; k++) {
                for (int f = 0; f < 2; f++) {
                    // The key's two test states are 4k + f and 4k + f + 2; its training states the other two.
                    double w = 0;
                    double bet = 0;
                    double sum = 0;
                    double tw = 0;
                    double ts = 0;
                    for (int c = 0; c < 4; c++) {
                        int j = 4 * k + c;
                        double wj = 1 / records.get(j).q;
                        if (c % 2 == f) {
                            w += wj;
                            bet += wj * Math.max(1, Math.min(spread, adv[j] / SystemScorer.UNIT));
                            sum += wj * adv[j];
                        } else {
                            tw += wj;
                            ts += wj * adv[j];
                        }
                    }
                    same += bet / w * sum;
                    own += Math.max(1, Math.min(spread, ts / tw / SystemScorer.UNIT)) * sum;
                }
            }
            double[] est = res.entries.get(0).estimate[2];
            assertEquals(same / weight, est[SystemScorer.same(spread)], EXACT, "spread " + spread);
            assertEquals(own / weight, est[SystemScorer.own(spread)], EXACT, "spread " + spread);
        }
    }

    /**
     * The two folds' scores are pooled as ratios of summed totals, not averaged: one fold holds
     * one state valued with q = 1, the other three valued with q = 0.1, so the pooled flat EV
     * weights the first by 1 and the others by 10 each.
     */
    @Test
    public void theFoldsArePooledAsRatiosOfSummedTotals() {
        int[] chart = ScoringStates.chartMoves(RULES);
        List<StateStore.Record> records = new ArrayList<>();
        records.add(state(0, 0, tenPoor(0), 1, (i, m) -> m == chart[i] ? 0.2 : -0.8));
        for (int shoe = 1; shoe < 6; shoe += 2) {
            records.add(state(shoe, 0, tenPoor(0), 0.1, (i, m) -> m == chart[i] ? -0.1 : -1.1));
        }
        SystemScorer sc = new SystemScorer(prepare(records, 6), new Bootstrap(6, 3, 1), SystemScorer.M_PLAY);
        SystemScorer.Result res = sc.score(CountKeys.nullCount(), null);
        double[] rv = new double[records.size()];
        for (int j = 0; j < rv.length; j++) {
            StateStore.Record r = records.get(j);
            rv[j] = RoundValue.of(r.ranksLeft(), r.values, 1.5, i -> chart[i], i -> false);
        }
        double pooled = (rv[0] + 10 * (rv[1] + rv[2] + rv[3])) / 31;
        double averaged = (rv[0] + (rv[1] + rv[2] + rv[3]) / 3) / 2;
        assertEquals(pooled, res.entries.get(0).estimate[2][SystemScorer.FLAT], EXACT);
        assertTrue(Math.abs(pooled - averaged) > 0.1);
    }

    /**
     * The last reference row bets each state its own reference bet, which is the schedule in
     * order of the true chart advantage: by the rearrangement inequality no ordering of the
     * same bets does better with the chart's play. So a count betting only, and not insuring,
     * beats it at no spread, and the perfect count, one that saw each state's advantage,
     * would only match it.
     *
     * Insurance must stay out for that to hold, and in these shoes it does only because of
     * pooling: at least five of each ten-valued kind are out, which makes insuring rare, but
     * about one state in ten still has an ace-up deal where it pays. No group's pooled
     * insurance value is positive, so no count insures, and the test checks that first.
     */
    @Test
    public void noCountBettingOnlyBeatsTheScheduleOnTheTrueAdvantage() {
        Random rnd = new Random(41);
        int shoes = 60;
        List<StateStore.Record> records = new ArrayList<>();
        for (int shoe = 0; shoe < shoes; shoe++) {
            for (int round = 0; round < 2; round++) {
                int[] dealt = CountKeysTest.dealt(rnd, 40 + rnd.nextInt(250));
                for (int k = 18; k < ShoeRun.KINDS; k++) {
                    dealt[k] = Math.max(dealt[k], 5);
                }
                double base = 0.03 * rnd.nextGaussian();
                records.add(state(shoe, round, dealt, 0.1 + 0.9 * rnd.nextDouble(),
                        (i, m) -> base + 0.2 * rnd.nextGaussian() - 0.1 * m));
            }
        }
        records.removeIf(r -> r.depth >= SystemScorer.CUTS[2]);
        SystemScorer sc = new SystemScorer(prepare(records, shoes), new Bootstrap(shoes, 3, 1), 2);
        List<SystemScorer.Entry> refs = sc.references();
        for (String name : new String[]{"Hi-Lo", "Omega II", "Red Seven (Red 7)", "Wong Halves"}) {
            CountKeys.Count c = CountKeys.of(CountingSystem.named(name), "");
            SystemScorer.Result res = sc.score(c, CountKeys.sideCountVariant(c));
            for (SystemScorer.Entry e : res.entries) {
                if (!e.bettingOnly) {
                    continue;
                }
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    assertEquals(0, e.estimate[p][SystemScorer.INSURANCE], 0, e.name);
                    for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                        double oracle = refs.get(2).estimate[p][SystemScorer.same(s)];
                        assertTrue(e.estimate[p][SystemScorer.same(s)] <= oracle + 1e-15, e.name + " at 1-" + s);
                        assertEquals(e.estimate[p][SystemScorer.sameBet(s)],
                                refs.get(2).estimate[p][SystemScorer.sameBet(s)], 1e-12);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ insurance

    /** Many small cards out: tens are 128 of the 316 cards left, so insurance pays in every deal. */
    static int[] tenRich() {
        int[] d = new int[ShoeRun.KINDS];
        for (int k = 2; k < 12; k++) {
            d[k] = 10;
        }
        return d;
    }

    /**
     * A running count of the tens seen, each -1, puts the ten-rich and the ten-poor states
     * in different groups. Both folds hold the same states, so each fold's fit insures a
     * (group, ace-up deal) exactly when insurance pays in the states it holds: every ace-up
     * deal of the ten-rich states, none of the ten-poor. The insurance the fit adds is that,
     * computed here from RoundValue.insurance.
     */
    @Test
    public void insuranceIsTakenInTheGroupsWhereItPays() {
        int[] chart = ScoringStates.chartMoves(RULES);
        int[] tag = new int[ShoeRun.KINDS];
        for (int k = 18; k < ShoeRun.KINDS; k++) {
            tag[k] = -1;
        }
        CountKeys.Count tens = new CountKeys.Count("Tens", Collections.emptyList(), tag, 1, false, 0, null, "");
        List<StateStore.Record> records = new ArrayList<>();
        for (int shoe = 0; shoe < 8; shoe++) {
            int[] dealt = shoe % 4 < 2 ? tenRich() : tenPoor(0);
            records.add(state(shoe, 0, dealt, 0.5, (i, m) -> m == chart[i] ? 0 : -1));
        }
        SystemScorer sc = new SystemScorer(prepare(records, 8), new Bootstrap(8, 3, 1), 1e-9);
        SystemScorer.Result res = sc.score(tens, null);
        double expected = 0;
        double rich = 0;
        for (StateStore.Record r : records) {
            int[] left = r.ranksLeft();
            for (int i = 0; i < ScoringStates.ACE_UP; i++) {
                double value = Deals.probability(left, i) * RoundValue.insurance(left, i);
                if (value > 0) {
                    expected += value;
                    rich += r.dealt[2] == 10 ? value : 0;
                }
            }
        }
        assertEquals(expected, rich, EXACT);
        assertTrue(expected > 0);
        double[] est = res.entries.get(0).estimate[2];
        assertEquals(expected / records.size(), est[SystemScorer.INSURANCE], EXACT);
        assertEquals(est[SystemScorer.PLAY] + est[SystemScorer.INSURANCE], est[SystemScorer.FLAT], EXACT);

        // Betting only, the chart plays and the count still sets insurance and the bets: the
        // flat EV is the chart's round value plus the same insurance, and the bets are placed
        // on that. The ten-rich states' chart advantage is the higher and their key too, so
        // the same schedule hands each state exactly its own reference bet.
        double[] only = res.entries.get(1).estimate[2];
        double chartSum = 0;
        double[] rv = new double[records.size()];
        double[] adv = new double[records.size()];
        for (int j = 0; j < records.size(); j++) {
            StateStore.Record r = records.get(j);
            int[] left = r.ranksLeft();
            adv[j] = RoundValue.of(left, r.values, 1.5, i -> chart[i], i -> false);
            rv[j] = RoundValue.of(left, r.values, 1.5, i -> chart[i], i -> RoundValue.insurance(left, i) > 0);
            chartSum += adv[j];
        }
        assertTrue(adv[0] > adv[2] && rv[0] > rv[2], "the ten-rich states must be ahead");
        assertEquals(chartSum / records.size(), only[SystemScorer.PLAY], EXACT);
        assertEquals(expected / records.size(), only[SystemScorer.INSURANCE], EXACT);
        assertEquals(chartSum / records.size() + expected / records.size(), only[SystemScorer.FLAT], EXACT);
        for (int s = 1; s <= SystemScorer.SPREADS; s++) {
            double sum = 0;
            for (int j = 0; j < records.size(); j++) {
                sum += SystemScorer.clamp(adv[j] / SystemScorer.UNIT, s) * rv[j];
            }
            assertEquals(sum / records.size(), only[SystemScorer.same(s)], EXACT, "spread " + s);
        }
    }

    // ---------------------------------------------------------------- references

    /**
     * The reference rows are their definitions: the chart flat is the mean round value
     * under the chart, the perfect row plays each deal's best stored move and adds every
     * insurance bet that pays, and the last row bets each state its own reference bet.
     */
    @Test
    public void theReferenceRowsAreWhatTheySay() {
        Random rnd = new Random(31);
        int shoes = 30;
        List<StateStore.Record> records = randomStates(rnd, shoes);
        SystemScorer sc = new SystemScorer(prepare(records, shoes), new Bootstrap(shoes, 3, 1), SystemScorer.M_PLAY);
        List<SystemScorer.Entry> refs = sc.references();
        int[] chart = ScoringStates.chartMoves(RULES);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            double w = 0;
            double chartSum = 0;
            double best = 0;
            double insurance = 0;
            double[] oracle = new double[SystemScorer.SPREADS + 1];
            for (StateStore.Record r : records) {
                if (r.depth >= SystemScorer.CUTS[p]) {
                    continue;
                }
                double wj = 1 / r.q;
                int[] left = r.ranksLeft();
                double a = RoundValue.of(left, r.values, 1.5, i -> chart[i], i -> false);
                double b = RoundValue.of(left, r.values, 1.5, i -> {
                    int top = -1;
                    for (int m = 0; m < ScoringStates.MOVES; m++) {
                        double v = r.values[i * ScoringStates.MOVES + m];
                        if (!Double.isNaN(v) && (top < 0 || v > r.values[i * ScoringStates.MOVES + top])) {
                            top = m;
                        }
                    }
                    return top;
                }, i -> false);
                double ins = RoundValue.of(left, r.values, 1.5, i -> chart[i], i -> RoundValue.insurance(left, i) > 0) - a;
                w += wj;
                chartSum += wj * a;
                best += wj * b;
                insurance += wj * ins;
                for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                    oracle[s] += wj * Math.max(1, Math.min(s, a / SystemScorer.UNIT)) * a;
                }
            }
            assertEquals(chartSum / w, refs.get(0).estimate[p][SystemScorer.FLAT], EXACT);
            assertEquals(best / w, refs.get(1).estimate[p][SystemScorer.PLAY], EXACT);
            assertEquals(insurance / w, refs.get(1).estimate[p][SystemScorer.INSURANCE], EXACT);
            for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                assertEquals(oracle[s] / w, refs.get(2).estimate[p][SystemScorer.same(s)], EXACT);
            }
        }
    }

    /**
     * The reference rows are resampled on the same shoes as the counts, so a count's
     * difference from them is paired. Betting only, the null count plays the chart on every
     * state of the penetration, both folds' test states together, which is the chart row: the
     * two must agree on every resample, while the resamples themselves move.
     */
    @Test
    public void theReferenceRowsShareEveryResampleWithTheCounts() {
        Random rnd = new Random(37);
        int shoes = 30;
        List<StateStore.Record> records = randomStates(rnd, shoes);
        SystemScorer sc = new SystemScorer(prepare(records, shoes), new Bootstrap(shoes, 20, 1), SystemScorer.M_PLAY);
        SystemScorer.Entry chart = sc.references().get(0);
        SystemScorer.Entry onlyChart = sc.score(CountKeys.nullCount(), null).entries.get(1);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            assertEquals(chart.estimate[p][SystemScorer.PLAY], onlyChart.estimate[p][SystemScorer.PLAY], EXACT);
            double[] a = chart.boot[p][SystemScorer.PLAY];
            double[] b = onlyChart.boot[p][SystemScorer.PLAY];
            assertArrayEquals(a, b, EXACT);
            assertTrue(Bootstrap.interval(chart.estimate[p][SystemScorer.PLAY], a).se > 1e-3);
        }
    }

    // --------------------------------------------------------- colour and faces

    /**
     * Red Seven's play key is a spread over the colours of the deal's sevens: each (state,
     * deal) the cards can make splits into parts whose shares add up to 1, keyed by the
     * running count plus each total tag CountKeys finds, and a deal the cards cannot make
     * has no parts.
     */
    @Test
    public void aColourCountSplitsEachDealIntoPartsThatAddUpToOne() {
        Random rnd = new Random(5);
        int shoes = 10;
        List<StateStore.Record> records = randomStates(rnd, shoes);
        ScoringStates st = prepare(records, shoes);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(shoes, 2, 1), SystemScorer.M_PLAY);
        CountKeys.Count red7 = CountKeys.of(CountingSystem.named("Red Seven (Red 7)"), "");
        assertTrue(red7.kindAware);
        SystemScorer.Pen pen = sc.pen(2);
        SystemScorer.Parts parts = sc.parts(red7, pen);
        boolean split = false;
        for (int j = 0; j < pen.states.length; j++) {
            int s = pen.states[j];
            int rc = red7.runningCount(st.dealt[s]);
            for (int i = 0; i < Deals.COUNT; i++) {
                int cell = j * Deals.COUNT + i;
                if (st.chance[s][i] == 0) {
                    assertEquals(parts.from(cell), parts.to(cell));
                    continue;
                }
                double total = 0;
                double[][] sums = red7.tagSums(st.dealt[s], i);
                assertEquals(sums.length, parts.to(cell) - parts.from(cell));
                for (int x = parts.from(cell); x < parts.to(cell); x++) {
                    total += parts.share(x);
                    double[] e = sums[x - parts.from(cell)];
                    assertEquals(rc + (int) e[0], parts.key[x]);
                    assertEquals(e[1], parts.share(x), 0);
                }
                split |= parts.to(cell) - parts.from(cell) > 1;
                assertEquals(1, total, 1e-12);
            }
        }
        assertTrue(split);
    }

    /**
     * A colour count's parts carry their shares into every sum: the play's values, insurance's
     * value and reach, and the per-shoe sums that settle each decision. With M_play so large
     * that each fold's fit is one group, every part of a (state, deal) falls in that group, so
     * the shares add up to 1 there and Red Seven must play, insure and settle its decisions
     * exactly as the null count does. A share left out anywhere counts a split deal once per
     * part. Every third shoe starts ten-rich, where insurance pays, and the rest are random
     * cards, where it mostly does not, so some insurance decisions are close calls too.
     */
    @Test
    public void aColourCountInOneGroupDecidesAsTheNullCount() {
        Random rnd = new Random(41);
        int shoes = 24;
        List<StateStore.Record> records = new ArrayList<>();
        for (int shoe = 0; shoe < shoes; shoe++) {
            int n = 1 + rnd.nextInt(2);
            for (int round = 0; round < n; round++) {
                int[] dealt = round == 0 && shoe % 3 == 0 ? tenRich() : CountKeysTest.dealt(rnd, rnd.nextInt(332));
                double base = 0.03 * rnd.nextGaussian();
                records.add(state(shoe, round, dealt, 0.1 + 0.9 * rnd.nextDouble(),
                        (i, m) -> base + 0.2 * rnd.nextGaussian() - 0.1 * m));
            }
        }
        SystemScorer sc = new SystemScorer(prepare(records, shoes), new Bootstrap(shoes, 20, 1), 100);
        SystemScorer.Result none = sc.score(CountKeys.nullCount(), null);
        SystemScorer.Result red7 = sc.score(CountKeys.of(CountingSystem.named("Red Seven (Red 7)"), ""), null);
        boolean playOpen = false;
        boolean insuranceOpen = false;
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            for (int f = 0; f < 2; f++) {
                assertEquals(1, red7.groups[p][f].count());
            }
            for (int e = 0; e < 2; e++) {
                for (int m : new int[]{SystemScorer.FLAT, SystemScorer.PLAY, SystemScorer.INSURANCE}) {
                    assertEquals(none.entries.get(e).estimate[p][m], red7.entries.get(e).estimate[p][m], EXACT);
                    assertArrayEquals(none.entries.get(e).boot[p][m], red7.entries.get(e).boot[p][m], EXACT);
                }
            }
            SystemScorer.Resolution a = none.resolution[p];
            SystemScorer.Resolution b = red7.resolution[p];
            assertEquals(a.decisions, b.decisions);
            assertEquals(a.unsettled, b.unsettled);
            assertEquals(a.bound, b.bound, EXACT);
            assertEquals(a.insuranceDecisions, b.insuranceDecisions);
            assertEquals(a.insuranceUnsettled, b.insuranceUnsettled);
            assertEquals(a.insuranceBound, b.insuranceBound, EXACT);
            playOpen |= a.bound > 1e-4;
            insuranceOpen |= a.insuranceBound > 1e-4;
        }
        assertTrue(playOpen && insuranceOpen, "the check needs close calls of both kinds to compare");
    }

    // ------------------------------------------------------- the learning curve

    /**
     * The learning-curve check fits on half of each training fold: the shoes whose index
     * halved is even. Here those shoes prefer Double, and the rest, weighted ten times as
     * much, prefer Stand, so the full fit stands and the half fit doubles, on every state.
     */
    @Test
    public void theLearningCurveFitsOnHalfOfEachTrainingFold() {
        List<StateStore.Record> records = new ArrayList<>();
        for (int shoe = 0; shoe < 8; shoe++) {
            boolean half = ((shoe >> 1) & 1) == 0;
            records.add(state(shoe, 0, tenPoor(0), half ? 1 : 0.1, (i, m) -> m == DOUBLE
                    ? (half ? 0.05 : -0.05) : m == STAND ? 0 : -0.4));
        }
        SystemScorer sc = new SystemScorer(prepare(records, 8), new Bootstrap(8, 3, 1), SystemScorer.M_PLAY);
        SystemScorer.Result res = sc.score(CountKeys.nullCount(), null);
        assertEquals(expectedFlat(records, r -> i -> STAND), res.entries.get(0).estimate[2][SystemScorer.FLAT], EXACT);
        assertEquals(expectedFlat(records, r -> i -> DOUBLE), res.halfFitFlat[2], EXACT);
    }

    /**
     * Shoes whose every move carries the shoe's own luck, with Stand best by 0.4 in every
     * state or, when halfLikesDouble, Double best by 0.05 in the half of each training fold
     * and Stand by 0.05 in the rest, which is weighted ten times as much.
     */
    static List<StateStore.Record> luckyShoes(int shoes, boolean halfLikesDouble) {
        List<StateStore.Record> records = new ArrayList<>();
        for (int shoe = 0; shoe < shoes; shoe++) {
            boolean half = ((shoe >> 1) & 1) == 0;
            double luck = ((shoe >> 2) & 1) == 0 ? 0.3 : -0.3;
            double dbl = halfLikesDouble ? (half ? 0.05 : -0.05) : -0.4;
            records.add(state(shoe, 0, tenPoor(shoe % 5), halfLikesDouble && !half ? 0.1 : 1,
                    (i, m) -> luck + (m == DOUBLE ? dbl : m == STAND ? 0 : -0.4)));
        }
        return records;
    }

    /**
     * The learning curve's resamples are paired with the full fit's: fitted on the half's
     * drawn shoes and scored on the same resampled test states. Where the half fits the same
     * play as the whole, Stand everywhere and insurance nowhere, each resample of the half
     * fit's flat EV is the full fit's, though the shoes' luck moves both a lot.
     */
    @Test
    public void theLearningCurveIsPairedResampleByResample() {
        int shoes = 16;
        SystemScorer sc = new SystemScorer(prepare(luckyShoes(shoes, false), shoes), new Bootstrap(shoes, 30, 5),
                SystemScorer.M_PLAY);
        SystemScorer.Result res = sc.score(CountKeys.nullCount(), null);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            double[] full = res.entries.get(0).boot[p][SystemScorer.FLAT];
            assertEquals(30, res.halfFitBoot[p].length);
            assertEquals(res.entries.get(0).estimate[p][SystemScorer.FLAT], res.halfFitFlat[p], 1e-12);
            for (int r = 0; r < full.length; r++) {
                assertEquals(full[r], res.halfFitBoot[p][r], 1e-12);
            }
            assertTrue(Bootstrap.interval(0, full).se > 0.01);
        }
    }

    /**
     * What the learning curve's half fit is on each resample, written out: for each fold, the
     * play fitted on the drawn shoes of the half of its training fold, each state counted
     * toward M_play as often as its shoe was drawn and weighted 1/q times that, every state
     * valued under it directly; insurance fitted on those same states in its groups; and the
     * resampled test fold scored with both. Red Seven with small groups on random cards has
     * groups that move with the draw and insurance near break-even in some of them, so each
     * other wiring gives another figure here, and the test checks that it does: counting a
     * drawn state once toward M_play, taking the half from the test fold, and fitting
     * insurance on the whole training fold.
     */
    @Test
    public void theHalfFitOnAResampleIsFittedOnTheHalfsDrawnShoes() {
        Random rnd = new Random(37);
        int shoes = 64;
        List<StateStore.Record> records = randomStates(rnd, shoes);
        ScoringStates st = prepare(records, shoes);
        Bootstrap boot = new Bootstrap(shoes, 4, 13);
        SystemScorer sc = new SystemScorer(st, boot, 0.7);
        CountKeys.Count red7 = CountKeys.of(CountingSystem.named("Red Seven (Red 7)"), "");
        SystemScorer.Result res = sc.score(red7, null);
        boolean[] differs = new boolean[3];
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            SystemScorer.Pen pen = sc.pen(p);
            SystemScorer.Parts parts = sc.parts(red7, pen);
            SystemScorer.InsuranceParts ip = sc.insuranceParts(parts, pen);
            for (int r = -1; r < boot.resamples; r++) {
                double[] w = pen.weight.clone();
                double[] units = null;
                if (r >= 0) {
                    units = new double[w.length];
                    for (int j = 0; j < w.length; j++) {
                        units[j] = boot.mult(r, st.shoe[pen.states[j]]);
                        w[j] *= units[j];
                    }
                }
                double expected = halfFitWrittenOut(sc, st, pen, parts, ip, w, units, false, false, false);
                double got = r < 0 ? res.halfFitFlat[p] : res.halfFitBoot[p][r];
                assertEquals(expected, got, 1e-12, "pen " + p + ", resample " + r);
                for (int k = 0; k < 3; k++) {
                    if (r >= 0) {
                        double other = halfFitWrittenOut(sc, st, pen, parts, ip, w, units, k == 0, k == 1, k == 2);
                        differs[k] |= Math.abs(other - expected) > 1e-9;
                    }
                }
            }
        }
        assertTrue(differs[0], "counting drawn states once toward M_play changes nothing here");
        assertTrue(differs[1], "taking the half from the test fold changes nothing here");
        assertTrue(differs[2], "fitting insurance on the whole training fold changes nothing here");
    }

    /** The half fit's flat EV under weights w and units, or under one of three other wirings. */
    private static double halfFitWrittenOut(SystemScorer sc, ScoringStates st, SystemScorer.Pen pen,
                                            SystemScorer.Parts parts, SystemScorer.InsuranceParts ip, double[] w,
                                            double[] units, boolean unitsOnce, boolean fromTest, boolean insuranceWhole) {
        double num = 0;
        double den = 0;
        for (int f = 0; f < 2; f++) {
            List<Integer> half = new ArrayList<>();
            for (int j : fromTest ? pen.test[f] : pen.train[f]) {
                if (((st.shoe[pen.states[j]] >> 1) & 1) == 0 && w[j] != 0) {
                    half.add(j);
                }
            }
            int[] use = half.stream().mapToInt(Integer::intValue).toArray();
            SystemScorer.FoldPlay play = sc.foldPlay(parts, pen, ip, use, w, unitsOnce ? null : units, null);
            int[] insuredOn = insuranceWhole ? SystemScorer.drawn(pen.train[f], w) : use;
            double[] ins = SystemScorer.insuranceValues(ip, play.insuranceGroup,
                    SystemScorer.insuranceSums(ip, play.insuranceGroup, play.groups.count(), insuredOn, w));
            for (int j : pen.test[f]) {
                num += w[j] * (play.base[j] + ins[j]);
                den += w[j];
            }
        }
        return num / den;
    }

    // ------------------------------------------------------------------ sub-runs

    /**
     * A sub-run takes whole pairs of shoes, pair t to sub-run t mod parts, and numbers its
     * shoes again from 0 keeping their parity, so the sub-runs are disjoint, keep every
     * state's fold, and each is scored as a run of its own.
     */
    @Test
    public void subRunsAreDisjointAndKeepTheirFolds() {
        Random rnd = new Random(8);
        int shoes = 27;
        List<StateStore.Record> records = randomStates(rnd, shoes);
        ScoringStates st = prepare(records, shoes);
        int parts = 3;
        int pairs = shoes / 2 / parts;
        int seen = 0;
        for (int k = 0; k < parts; k++) {
            ScoringStates sub = st.subRun(k, parts);
            assertEquals(2 * pairs, sub.shoes);
            for (int j = 0; j < sub.size; j++) {
                int original = 2 * (parts * (sub.shoe[j] / 2) + k) + (sub.shoe[j] & 1);
                int s = -1;
                for (int x = 0; x < st.size; x++) {
                    if (st.shoe[x] == original && st.round[x] == sub.round[j]) {
                        s = x;
                    }
                }
                assertTrue(s >= 0);
                assertEquals(st.fold(s), sub.fold(j));
                assertSame(st.move[s], sub.move[j]);
                assertEquals(st.weight[s], sub.weight[j], 0);
                if (j > 0) {
                    assertTrue(sub.shoe[j] >= sub.shoe[j - 1]);
                }
            }
            seen += sub.size;
            new SystemScorer(sub, new Bootstrap(sub.shoes, 2, 1), SystemScorer.M_PLAY).score(CountKeys.nullCount(), null);
        }
        int whole = 0;
        for (int x = 0; x < st.size; x++) {
            if (st.shoe[x] / 2 < parts * pairs) {
                whole++;
            }
        }
        assertEquals(whole, seen);
    }
}
