import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Fits and scores a count from stored states, as COUNTING_COMPARISON.md, section 6 sets out.
 *
 * Everything is done per penetration, on the states dealt before its cut card, and per
 * fold: a shoe's fold is its parity, each fold's strategy is fitted on the other fold and
 * scored on its own, and the two scores are pooled as ratios of summed totals. Every sum
 * over states weights a state by 1/q.
 *
 * Play. Play keys are gathered into groups on the training fold, grown outward from the
 * most-populated key until each holds M_play valued states, and the move for each (group,
 * deal) is the one with the largest weighted value there. M_play grows as the square root
 * of the valued states the fit is formed from, so a longer run gives both finer groups and
 * more data behind each decision. Insurance is taken for a (group, ace-up deal) when its
 * weighted value is positive. Every such decision is checked for whether the data settle
 * it: the gap from the fitted move to every other against twice its standard error from
 * per-shoe sums, and, where it is not settled, a bound on what keeping it can cost.
 *
 * Bets. A training state's round value RV under the fitted play and insurance is fitted,
 * weighted by 1/q, as a non-decreasing function of the bet key (pool-adjacent-violators).
 * Bets are then placed two ways, for spreads 1 to 8:
 * - the same schedule for every count: the bets the proportional rule would place on each
 *   test state's true basic-strategy advantage form a distribution, and the count hands
 *   those bets out in order of its own fitted advantage; a key value, or several with the
 *   same fitted advantage, that spans a range of the distribution gets that range's average
 *   bet. The fold's average bet is then the distribution's mean, the same for every count;
 * - its own proportional ramp: clamp(advantage / 0.25%, 1, S) units, unrounded, and the same
 *   rounded half up to whole units, "as played".
 * The ordering comes from the training fold alone; the bet sizes the schedule hands out are
 * the test fold's own distribution, which no count's outcomes enter.
 *
 * Each count is scored two or four ways from one fit: as itself; betting only, with the
 * chart playing and the count setting insurance and bets; and, for the ace-neutral systems,
 * with the ace side count moving the bet key, itself and betting only.
 *
 * The bootstrap refits everything on every resample of the shared multiplicity vectors:
 * the play groups and moves, insurance and bets, each fold's from its resampled training
 * shoes, as the estimate was made. So a resample carries how much each count's play fit moves
 * with the shoes it was fitted on, and a difference between two counts taken resample by
 * resample carries both counts' fits moving. Keeping the play as fitted leaves that out, and
 * for a difference from Hi-Lo it is most of the spread.
 */
final class SystemScorer {

    /**
     * A play group closes once it holds M_PLAY x sqrt(n) valued states, n the valued states
     * the fit is formed from: about 110 for a training fold of 500, as in the pilot's 80%
     * game, and 560 for one of 12,500. With a fixed threshold a longer run only makes more
     * groups of the same size, so no decision gets more data and the close calls never
     * settle; with one that grows as the whole, the run gives nothing to finer play. The
     * square root splits the gain between the two.
     */
    static final double M_PLAY = 5;
    static final int[] CUTS = {291, 312, 332};
    static final String[] PENETRATIONS = {"70%", "75%", "80%"};
    static final int SPREADS = 8;
    /** One unit of bet per 0.25% of advantage. */
    static final double UNIT = 0.0025;

    // The figures kept per entry and penetration, each a pooled EV per round or average bet.
    /** Flat EV per round under the fitted play and insurance. */
    static final int FLAT = 0;
    /** Flat EV per round under the fitted play, without insurance. */
    static final int PLAY = 1;
    /** What the fitted insurance adds per round, flat. */
    static final int INSURANCE = 2;
    static final int METRICS = 3 + 6 * SPREADS;

    /** EV per round betting the same schedule at spread 1 to s. */
    static int same(int s) {
        return 2 + s;
    }

    /** The average bet of the same schedule: the same for every count. */
    static int sameBet(int s) {
        return 2 + SPREADS + s;
    }

    /** EV per round of the count's own unrounded proportional ramp. */
    static int own(int s) {
        return 2 + 2 * SPREADS + s;
    }

    static int ownBet(int s) {
        return 2 + 3 * SPREADS + s;
    }

    /** EV per round of the own ramp rounded half up to whole units. */
    static int played(int s) {
        return 2 + 4 * SPREADS + s;
    }

    static int playedBet(int s) {
        return 2 + 5 * SPREADS + s;
    }

    private static final int WEIGHT = METRICS;

    final ScoringStates st;
    final Bootstrap boot;
    final double mPlay;
    private final Pen[] pens = new Pen[CUTS.length];

    SystemScorer(ScoringStates st, Bootstrap boot, double mPlay) {
        if (boot.shoes != st.shoes) {
            throw new IllegalArgumentException("the bootstrap is over " + boot.shoes + " shoes, the states " + st.shoes);
        }
        this.st = st;
        this.boot = boot;
        this.mPlay = mPlay;
        for (int p = 0; p < CUTS.length; p++) {
            pens[p] = new Pen(st, CUTS[p]);
        }
    }

    /** The states of one penetration, by fold. Local index j is a state's place in states. */
    static final class Pen {
        final int cut;
        final int[] states;
        final int[][] test = new int[2][];
        final int[][] train = new int[2][];
        /** A fold's test states in order of their chart advantage, lowest first. */
        final int[][] testByChart = new int[2][];
        final double[] weight;
        final double[] chart;
        /** Sum of 1/q over each shoe's states in the penetration. */
        final double[] shoeWeight;

        Pen(ScoringStates st, int cut) {
            this.cut = cut;
            int n = 0;
            for (int s = 0; s < st.size; s++) {
                if (st.depth[s] < cut) {
                    n++;
                }
            }
            states = new int[n];
            weight = new double[n];
            chart = new double[n];
            shoeWeight = new double[st.shoes];
            int j = 0;
            int[] inFold = new int[2];
            for (int s = 0; s < st.size; s++) {
                if (st.depth[s] < cut) {
                    states[j] = s;
                    weight[j] = st.weight[s];
                    chart[j] = st.chart[s];
                    shoeWeight[st.shoe[s]] += st.weight[s];
                    inFold[st.fold(s)]++;
                    j++;
                }
            }
            for (int f = 0; f < 2; f++) {
                test[f] = new int[inFold[f]];
                train[f] = new int[n - inFold[f]];
                int a = 0;
                int b = 0;
                for (j = 0; j < n; j++) {
                    if (st.fold(states[j]) == f) {
                        test[f][a++] = j;
                    } else {
                        train[f][b++] = j;
                    }
                }
                Integer[] order = new Integer[inFold[f]];
                for (int k = 0; k < order.length; k++) {
                    order[k] = test[f][k];
                }
                Arrays.sort(order, Comparator.comparingDouble(x -> chart[x]));
                testByChart[f] = new int[order.length];
                for (int k = 0; k < order.length; k++) {
                    testByChart[f][k] = order[k];
                }
            }
        }
    }

    Pen pen(int p) {
        return pens[p];
    }

    // ------------------------------------------------------------------ play keys

    /**
     * The play keys of every (state, deal) of a penetration: cell j x 550 + i holds the parts
     * from start[cell] to start[cell + 1], each a key and the share of the deal's weight on
     * it. A count that is not colour- or face-aware has one part per cell, with share 1, and
     * then start and share are null.
     */
    static final class Parts {
        final int[] start;
        final int[] key;
        final double[] share;
        /** The smallest and largest key of any part, for looking groups up by key. */
        final int minKey;
        final int maxKey;

        Parts(int[] start, int[] key, double[] share) {
            this.start = start;
            this.key = key;
            this.share = share;
            int lo = 0;
            int hi = 0;
            if (key.length > 0) {
                lo = Integer.MAX_VALUE;
                hi = Integer.MIN_VALUE;
                for (int k : key) {
                    lo = Math.min(lo, k);
                    hi = Math.max(hi, k);
                }
            }
            this.minKey = lo;
            this.maxKey = hi;
        }

        int from(int cell) {
            return start == null ? cell : start[cell];
        }

        int to(int cell) {
            return start == null ? cell + 1 : start[cell + 1];
        }

        double share(int part) {
            return share == null ? 1 : share[part];
        }
    }

    Parts parts(CountKeys.Count c, Pen pen) {
        int n = pen.states.length;
        int cells = n * Deals.COUNT;
        if (!c.kindAware) {
            int[] tagSum = new int[Deals.COUNT];
            for (int i = 0; i < Deals.COUNT; i++) {
                tagSum[i] = c.rankTag(Deals.P1[i]) + c.rankTag(Deals.P2[i]) + c.rankTag(Deals.UP[i]);
            }
            int[] key = new int[cells];
            for (int j = 0; j < n; j++) {
                int s = pen.states[j];
                int rc = c.runningCount(st.dealt[s]);
                for (int i = 0; i < Deals.COUNT; i++) {
                    key[j * Deals.COUNT + i] = c.playKey(rc, st.depth[s], tagSum[i]);
                }
            }
            return new Parts(null, key, null);
        }
        int[] start = new int[cells + 1];
        int[] key = new int[cells * 2];
        double[] share = new double[cells * 2];
        int at = 0;
        for (int j = 0; j < n; j++) {
            int s = pen.states[j];
            int rc = c.runningCount(st.dealt[s]);
            int[][] left = c.classesLeft(st.dealt[s]);
            for (int i = 0; i < Deals.COUNT; i++) {
                int cell = j * Deals.COUNT + i;
                start[cell] = at;
                if (st.chance[s][i] == 0) {
                    continue;
                }
                for (double[] e : c.tagSums(left, i)) {
                    if (at == key.length) {
                        key = Arrays.copyOf(key, key.length * 3 / 2);
                        share = Arrays.copyOf(share, share.length * 3 / 2);
                    }
                    key[at] = c.playKey(rc, st.depth[s], (int) e[0]);
                    share[at] = e[1];
                    at++;
                }
            }
        }
        start[cells] = at;
        return new Parts(start, Arrays.copyOf(key, at), Arrays.copyOf(share, at));
    }

    // --------------------------------------------------------------------- groups

    /**
     * Play-key groups: group g holds the keys above upper[g - 1] up to upper[g], the first
     * every key below and the last every key above, so a test key outside the training range
     * joins the end group.
     */
    static final class Groups {
        final int[] upper;
        /** The lowest and highest training key in each group, for the report. */
        final int[] lo;
        final int[] hi;

        Groups(int[] lo, int[] hi) {
            this.lo = lo;
            this.hi = hi;
            this.upper = hi.clone();
            upper[upper.length - 1] = Integer.MAX_VALUE;
        }

        int count() {
            return upper.length;
        }

        int of(int key) {
            int a = 0;
            int b = upper.length - 1;
            while (a < b) {
                int mid = (a + b) >>> 1;
                if (key <= upper[mid]) {
                    b = mid;
                } else {
                    a = mid + 1;
                }
            }
            return a;
        }

        /** The group of every key from parts.minKey to parts.maxKey, at [key - minKey]. */
        int[] table(Parts parts) {
            int[] t = new int[parts.maxKey - parts.minKey + 1];
            int g = 0;
            for (int k = 0; k < t.length; k++) {
                while (parts.minKey + k > upper[g]) {
                    g++;
                }
                t[k] = g;
            }
            return t;
        }
    }

    /** The valued states a play group must hold, when it is formed from this many. */
    double threshold(double valued) {
        return mPlay * Math.sqrt(valued);
    }

    /** Groups formed on the states given, each weighted 1/q and counted once. */
    Groups groups(Parts parts, Pen pen, int[] use) {
        return groups(parts, pen, use, pen.weight, null);
    }

    /**
     * Groups formed on the states given. Each state is spread over the play keys of its deals
     * by the deals' chances. The walk starts at the key holding the most states, each valued
     * state standing for w[j] of them (1/q, as every sum over states is weighted), since the
     * centre is where the rounds are. Walking outward each way, a group closes once it holds
     * mPlay x sqrt(n) valued states, each state counting units[j] (1 when null) both there and
     * in n: the threshold is about how much data a group's decisions rest on, which is what
     * was valued, not what it stands for. A partial group at either end joins its neighbour.
     */
    Groups groups(Parts parts, Pen pen, int[] use, double[] w, double[] units) {
        int span = parts.maxKey - parts.minKey + 1;
        double[] massAll = new double[span];
        double[] roundsAll = new double[span];
        boolean[] held = new boolean[span];
        for (int j : use) {
            double u = units == null ? 1 : units[j];
            double wj = w[j];
            double[] p = st.chance[pen.states[j]];
            for (int i = 0; i < Deals.COUNT; i++) {
                if (p[i] == 0) {
                    continue;
                }
                int cell = j * Deals.COUNT + i;
                for (int x = parts.from(cell); x < parts.to(cell); x++) {
                    int k = parts.key[x] - parts.minKey;
                    double share = p[i] * parts.share(x);
                    massAll[k] += u * share;
                    roundsAll[k] += wj * share;
                    held[k] = true;
                }
            }
        }
        int first = 0;
        while (first < span && !held[first]) {
            first++;
        }
        if (first == span) {
            return new Groups(new int[]{0}, new int[]{0});
        }
        int last = span - 1;
        while (!held[last]) {
            last--;
        }
        int min = parts.minKey + first;
        double[] mass = Arrays.copyOfRange(massAll, first, last + 1);
        double[] rounds = Arrays.copyOfRange(roundsAll, first, last + 1);
        double valued = 0;
        for (int j : use) {
            valued += units == null ? 1 : units[j];
        }
        double threshold = threshold(valued);
        int mode = 0;
        for (int k = 1; k < rounds.length; k++) {
            if (rounds[k] > rounds[mode]) {
                mode = k;
            }
        }
        List<int[]> up = new ArrayList<>();
        double acc = 0;
        int from = mode;
        for (int k = mode; k < mass.length; k++) {
            acc += mass[k];
            if (acc >= threshold) {
                up.add(new int[]{from, k});
                from = k + 1;
                acc = 0;
            }
        }
        boolean upPartial = from < mass.length;
        List<int[]> down = new ArrayList<>();
        acc = 0;
        int to = mode - 1;
        for (int k = mode - 1; k >= 0; k--) {
            acc += mass[k];
            if (acc >= threshold) {
                down.add(new int[]{k, to});
                to = k - 1;
                acc = 0;
            }
        }
        boolean downPartial = to >= 0;
        List<int[]> all = new ArrayList<>();
        if (up.isEmpty() && down.isEmpty()) {
            all.add(new int[]{0, mass.length - 1});
        } else {
            if (upPartial) {
                if (!up.isEmpty()) {
                    up.get(up.size() - 1)[1] = mass.length - 1;
                } else {
                    down.get(0)[1] = mass.length - 1;
                }
            }
            if (downPartial) {
                if (!down.isEmpty()) {
                    down.get(down.size() - 1)[0] = 0;
                } else {
                    up.get(0)[0] = 0;
                }
            }
            for (int g = down.size() - 1; g >= 0; g--) {
                all.add(down.get(g));
            }
            all.addAll(up);
        }
        int[] lo = new int[all.size()];
        int[] hi = new int[all.size()];
        for (int g = 0; g < all.size(); g++) {
            lo[g] = all.get(g)[0] + min;
            hi[g] = all.get(g)[1] + min;
        }
        return new Groups(lo, hi);
    }

    // ------------------------------------------------------------------------ play

    /** A fitted play: the move for each (group, deal), and the sums it was chosen from. */
    static final class Fit {
        final Groups groups;
        /** [g][deal]: the move slot, or -1 for a player natural. */
        final int[][] move;
        /** [g][deal x MOVES + m]: the weighted sum of P(i) x deal value for move m. */
        final double[][] sum;
        /** [g][deal]: the weighted chance of the deal landing in the group. */
        final double[][] reach;
        /** The weight of the states fitted on. */
        final double weight;

        Fit(Groups groups, int[][] move, double[][] sum, double[][] reach, double weight) {
            this.groups = groups;
            this.move = move;
            this.sum = sum;
            this.reach = reach;
            this.weight = weight;
        }
    }

    /** The move for each (group, deal) that maximises the value over the states given, each weighted w[j]. */
    Fit fit(Parts parts, Pen pen, int[] use, Groups groups, int[] table, double[] w) {
        int g0 = groups.count();
        double[][] sum = new double[g0][Deals.COUNT * ScoringStates.MOVES];
        double[][] reach = new double[g0][Deals.COUNT];
        double weight = 0;
        boolean[] legal = st.legal;
        for (int j : use) {
            int s = pen.states[j];
            double wj = w[j];
            weight += wj;
            double[] p = st.chance[s];
            double[] mv = st.move[s];
            for (int i = 0; i < Deals.COUNT; i++) {
                if (p[i] == 0) {
                    continue;
                }
                boolean natural = Deals.playerNatural(i);
                int cell = j * Deals.COUNT + i;
                int base = i * ScoringStates.MOVES;
                for (int x = parts.from(cell); x < parts.to(cell); x++) {
                    int g = table[parts.key[x] - parts.minKey];
                    double ws = wj * parts.share(x);
                    reach[g][i] += ws * p[i];
                    if (natural) {
                        continue;
                    }
                    double[] row = sum[g];
                    for (int m = 0; m < ScoringStates.MOVES; m++) {
                        if (legal[base + m]) {
                            row[base + m] += ws * mv[base + m];
                        }
                    }
                }
            }
        }
        int[][] move = new int[g0][Deals.COUNT];
        for (int g = 0; g < g0; g++) {
            for (int i = 0; i < Deals.COUNT; i++) {
                move[g][i] = choose(sum[g], reach[g][i], i);
            }
        }
        return new Fit(groups, move, sum, reach, weight);
    }

    /** The best move of a deal from its sums: the chart's where nothing reached it or it ties for best. */
    private int choose(double[] sum, double reach, int i) {
        int chart = st.chartMove[i];
        if (chart < 0 || reach == 0) {
            return chart;
        }
        int best = chart;
        for (int m = 0; m < ScoringStates.MOVES; m++) {
            if (st.legal[i * ScoringStates.MOVES + m] && sum[i * ScoringStates.MOVES + m] > sum[i * ScoringStates.MOVES + best]) {
                best = m;
            }
        }
        return best;
    }

    /**
     * The round value under the fitted play, before insurance, of each state in which (all of
     * them when null), at out[j]; the others are left as they are.
     */
    void playValues(Parts parts, Pen pen, Fit fit, int[] table, int[] which, double[] out) {
        int n = which == null ? pen.states.length : which.length;
        for (int k = 0; k < n; k++) {
            int j = which == null ? k : which[k];
            int s = pen.states[j];
            double[] p = st.chance[s];
            double[] mv = st.move[s];
            double v = st.naturals[s];
            for (int i = 0; i < Deals.COUNT; i++) {
                if (p[i] == 0 || Deals.playerNatural(i)) {
                    continue;
                }
                int cell = j * Deals.COUNT + i;
                for (int x = parts.from(cell); x < parts.to(cell); x++) {
                    int g = table[parts.key[x] - parts.minKey];
                    v += parts.share(x) * mv[i * ScoringStates.MOVES + fit.move[g][i]];
                }
            }
            out[j] = v;
        }
    }

    /**
     * A penetration's (state, deal) parts listed by (key, deal), so that a play can be valued
     * by what it changes: pair (key - minKey) x 550 + deal holds part[x] and its state's local
     * index state[x] for x from from[pair] up to from[pair + 1]. Player naturals are left
     * out, since no move is chosen for them.
     */
    static final class ByKey {
        final int[] from;
        final int[] part;
        final int[] state;

        ByKey(int[] from, int[] part, int[] state) {
            this.from = from;
            this.part = part;
            this.state = state;
        }
    }

    ByKey byKey(Parts parts, Pen pen) {
        int pairs = (parts.maxKey - parts.minKey + 1) * Deals.COUNT;
        int[] from = new int[pairs + 1];
        for (int pass = 0; pass < 2; pass++) {
            int[] at = pass == 0 ? null : from.clone();
            int[] part = pass == 0 ? null : new int[from[pairs]];
            int[] state = pass == 0 ? null : new int[from[pairs]];
            for (int j = 0; j < pen.states.length; j++) {
                double[] p = st.chance[pen.states[j]];
                for (int i = 0; i < Deals.COUNT; i++) {
                    if (p[i] == 0 || Deals.playerNatural(i)) {
                        continue;
                    }
                    int cell = j * Deals.COUNT + i;
                    for (int x = parts.from(cell); x < parts.to(cell); x++) {
                        int pair = (parts.key[x] - parts.minKey) * Deals.COUNT + i;
                        if (pass == 0) {
                            from[pair + 1]++;
                        } else {
                            part[at[pair]] = x;
                            state[at[pair]] = j;
                            at[pair]++;
                        }
                    }
                }
            }
            if (pass == 0) {
                for (int k = 0; k < pairs; k++) {
                    from[k + 1] += from[k];
                }
            } else {
                return new ByKey(from, part, state);
            }
        }
        throw new AssertionError();
    }

    /**
     * The round values before insurance under fit, from those under an earlier play that
     * valued every state: each part whose (key, deal) is played another way now changes its
     * state's value by share x (the new move's value - the old one's). A resample's play
     * differs from the estimate's in few (key, deal) pairs, so this is a small part of the
     * cost of valuing every state again, and it gives the same sums up to rounding.
     */
    void playValuesFrom(Parts parts, Pen pen, ByKey byKey, FoldPlay earlier, Fit fit, int[] table, double[] out) {
        System.arraycopy(earlier.base, 0, out, 0, out.length);
        int keys = parts.maxKey - parts.minKey + 1;
        for (int k = 0; k < keys; k++) {
            int[] now = fit.move[table[k]];
            int[] was = earlier.fit.move[earlier.table[k]];
            for (int i = 0; i < Deals.COUNT; i++) {
                if (now[i] == was[i]) {
                    continue;
                }
                int pair = k * Deals.COUNT + i;
                for (int y = byKey.from[pair]; y < byKey.from[pair + 1]; y++) {
                    int j = byKey.state[y];
                    double[] mv = st.move[pen.states[j]];
                    out[j] += parts.share(byKey.part[y])
                            * (mv[i * ScoringStates.MOVES + now[i]] - mv[i * ScoringStates.MOVES + was[i]]);
                }
            }
        }
    }

    /** Each state's round value under the fitted play, before insurance. */
    double[] playValues(Parts parts, Pen pen, Fit fit) {
        double[] out = new double[pen.states.length];
        playValues(parts, pen, fit, fit.groups.table(parts), null, out);
        return out;
    }

    // ------------------------------------------------------------------- insurance

    /**
     * The ace-up parts of a penetration's (state, deal) cells, kept apart because insurance is
     * refitted on every resample: each part's deal, key, insurance value share x P(i) x 0.5 x
     * (3 P(hole ten) - 1), and reach share x P(i).
     */
    static final class InsuranceParts {
        final int[] start;
        final byte[] deal;
        final int[] key;
        final double[] value;
        final double[] reach;

        InsuranceParts(int[] start, byte[] deal, int[] key, double[] value, double[] reach) {
            this.start = start;
            this.deal = deal;
            this.key = key;
            this.value = value;
            this.reach = reach;
        }

        /** Each part's group under a play fit's groups. */
        int[] groups(Parts parts, int[] table) {
            int[] g = new int[key.length];
            for (int x = 0; x < g.length; x++) {
                g[x] = table[key[x] - parts.minKey];
            }
            return g;
        }
    }

    InsuranceParts insuranceParts(Parts parts, Pen pen) {
        int n = pen.states.length;
        int total = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < ScoringStates.ACE_UP; i++) {
                int cell = j * Deals.COUNT + i;
                total += parts.to(cell) - parts.from(cell);
            }
        }
        int[] start = new int[n + 1];
        byte[] deal = new byte[total];
        int[] key = new int[total];
        double[] value = new double[total];
        double[] reach = new double[total];
        int at = 0;
        for (int j = 0; j < n; j++) {
            start[j] = at;
            int s = pen.states[j];
            for (int i = 0; i < ScoringStates.ACE_UP; i++) {
                int cell = j * Deals.COUNT + i;
                for (int x = parts.from(cell); x < parts.to(cell); x++) {
                    deal[at] = (byte) i;
                    key[at] = parts.key[x];
                    value[at] = parts.share(x) * st.insurance[s][i];
                    reach[at] = parts.share(x) * st.chance[s][i];
                    at++;
                }
            }
        }
        start[n] = at;
        return new InsuranceParts(start, deal, key, value, reach);
    }

    /** The weighted insurance value of each (group, ace-up deal), [g x 55 + i], over the states given. */
    static double[] insuranceSums(InsuranceParts ip, int[] group, int groups, int[] use, double[] w) {
        double[] sum = new double[groups * ScoringStates.ACE_UP];
        for (int j : use) {
            double wj = w[j];
            if (wj == 0) {
                continue;
            }
            for (int x = ip.start[j]; x < ip.start[j + 1]; x++) {
                sum[group[x] * ScoringStates.ACE_UP + ip.deal[x]] += wj * ip.value[x];
            }
        }
        return sum;
    }

    /** What insurance adds to each state's round value when (group, deal) is insured where sum > 0. */
    static double[] insuranceValues(InsuranceParts ip, int[] group, double[] sum) {
        int n = ip.start.length - 1;
        double[] out = new double[n];
        for (int j = 0; j < n; j++) {
            double v = 0;
            for (int x = ip.start[j]; x < ip.start[j + 1]; x++) {
                if (sum[group[x] * ScoringStates.ACE_UP + ip.deal[x]] > 0) {
                    v += ip.value[x];
                }
            }
            out[j] = v;
        }
        return out;
    }

    // ----------------------------------------------------------- one fold's play fit

    /**
     * One fold's play, fitted on some of its training states under some weights: the groups,
     * the moves, the group of every key and of every insurance part, and the round value
     * before insurance of the states asked for. The estimate makes one per fold from the
     * whole training fold, and so does every bootstrap resample, from its own weights, so the
     * resamples carry how much the play fit moves with the shoes it was fitted on.
     */
    static final class FoldPlay {
        final Groups groups;
        final Fit fit;
        final int[] table;
        final int[] insuranceGroup;
        final double[] base;

        FoldPlay(Groups groups, Fit fit, int[] table, int[] insuranceGroup, double[] base) {
            this.groups = groups;
            this.fit = fit;
            this.table = table;
            this.insuranceGroup = insuranceGroup;
            this.base = base;
        }
    }

    /**
     * Fits a fold's play on the states in use, state j weighted w[j] and counted units[j]
     * times toward M_play (once each when units is null), and values the states in valueOn
     * (every state of the penetration when null) under it.
     */
    FoldPlay foldPlay(Parts parts, Pen pen, InsuranceParts ip, int[] use, double[] w, double[] units, int[] valueOn) {
        return foldPlay(parts, pen, ip, use, w, units, valueOn, null, null);
    }

    /** The same, valuing every state from an earlier play's values (playValuesFrom) when earlier is given. */
    FoldPlay foldPlay(Parts parts, Pen pen, InsuranceParts ip, int[] use, double[] w, double[] units, int[] valueOn,
                      ByKey byKey, FoldPlay earlier) {
        Groups groups = groups(parts, pen, use, w, units);
        int[] table = groups.table(parts);
        Fit fit = fit(parts, pen, use, groups, table, w);
        double[] base = new double[pen.states.length];
        if (earlier == null) {
            playValues(parts, pen, fit, table, valueOn, base);
        } else {
            playValuesFrom(parts, pen, byKey, earlier, fit, table, base);
        }
        return new FoldPlay(groups, fit, table, ip.groups(parts, table), base);
    }

    /** The states of use whose weight in w is not zero: a resample leaves out the shoes it did not draw. */
    static int[] drawn(int[] use, double[] w) {
        int n = 0;
        for (int j : use) {
            n += w[j] != 0 ? 1 : 0;
        }
        int[] out = new int[n];
        n = 0;
        for (int j : use) {
            if (w[j] != 0) {
                out[n++] = j;
            }
        }
        return out;
    }

    // ------------------------------------------------------------ decision resolution

    /** One decision the data do not settle. */
    static final class CloseCall {
        final int fold;
        final int loKey;
        final int hiKey;
        final int deal;
        /** Move slots, or for insurance -1 for "insure" and -2 for "do not". */
        final int best;
        /** The move the fitted one could lose to most, by the bound. */
        final int against;
        /** How far the fitted move is ahead of that one, per time the (group, deal) comes up. */
        final double gap;
        final double se;
        final double frequency;

        CloseCall(int fold, int loKey, int hiKey, int deal, int best, int against, double gap, double se, double frequency) {
            this.fold = fold;
            this.loKey = loKey;
            this.hiKey = hiKey;
            this.deal = deal;
            this.best = best;
            this.against = against;
            this.gap = gap;
            this.se = se;
            this.frequency = frequency;
        }

        /**
         * What keeping the fitted move can cost per round, at most: the true gap is at least
         * gap - 2 SE, so the other move is better by at most 2 SE - gap.
         */
        double bound() {
            return frequency * Math.max(0, 2 * se - gap);
        }
    }

    /** How well the data settle a count's decisions at one penetration, both folds' fits together. */
    static final class Resolution {
        int decisions;
        int unsettled;
        /** Per round: the test-weighted mean over folds of the summed bound on the play's close calls. */
        double bound;
        int insuranceDecisions;
        int insuranceUnsettled;
        /** The same for insurance. */
        double insuranceBound;
        /** The close calls with the largest bounds, largest first. */
        final List<CloseCall> closest = new ArrayList<>();
        static final int KEEP = 12;

        void keep(CloseCall c) {
            closest.add(c);
            closest.sort((a, b) -> Double.compare(b.bound(), a.bound()));
            if (closest.size() > KEEP) {
                closest.remove(closest.size() - 1);
            }
        }

        /** The decision bound: every fitted decision's, the first move's and insurance's added. */
        double total() {
            return bound + insuranceBound;
        }
    }

    /**
     * Checks each decision of one fold's fit against the training data it came from. For the
     * fitted move of a (group, deal) and each other legal move, the gap between them per time
     * the (group, deal) comes up is a ratio of sums over shoes, so its standard error comes
     * from the per-shoe sums (the delta method, shoes as clusters, every shoe of the fold
     * counted). A decision is settled when every gap exceeds twice its SE; checking the
     * runner-up alone would miss a move whose values vary more. An unsettled decision costs
     * at most its frequency per round times the largest 2 SE - gap over the other moves.
     * Insurance is checked the same way, insuring against not.
     */
    void resolve(Parts parts, Pen pen, int fold, int[] use, FoldPlay play, InsuranceParts ip, double[] insSum,
                 double testWeight, double[] foldBound, Resolution out) {
        Fit fit = play.fit;
        int moves = ScoringStates.MOVES;
        int groups = fit.groups.count();
        int cells = groups * Deals.COUNT;
        int[] best = new int[cells];
        boolean[] open = new boolean[cells];
        double[] gap = new double[cells * moves];
        for (int g = 0; g < groups; g++) {
            for (int i = 0; i < Deals.COUNT; i++) {
                int c = g * Deals.COUNT + i;
                best[c] = fit.move[g][i];
                if (best[c] < 0 || fit.reach[g][i] == 0) {
                    continue;
                }
                for (int m = 0; m < moves; m++) {
                    if (m != best[c] && st.legal[i * moves + m]) {
                        open[c] = true;
                        gap[c * moves + m] = (fit.sum[g][i * moves + best[c]] - fit.sum[g][i * moves + m]) / fit.reach[g][i];
                    }
                }
            }
        }
        int insCells = groups * ScoringStates.ACE_UP;
        double[] insGap = new double[insCells];
        for (int c = 0; c < insCells; c++) {
            double r = fit.reach[c / ScoringStates.ACE_UP][c % ScoringStates.ACE_UP];
            insGap[c] = r == 0 ? 0 : Math.abs(insSum[c]) / r;
        }

        // Per-shoe sums of (gap contribution - gap x reach), squared and added up by cell and move.
        double[] var = new double[cells * moves];
        double[] a = new double[cells * moves];
        double[] b = new double[cells];
        double[] insVar = new double[insCells];
        double[] ia = new double[insCells];
        double[] ib = new double[insCells];
        int[] touched = new int[cells];
        int[] insTouched = new int[insCells];
        boolean[] on = new boolean[cells];
        boolean[] insOn = new boolean[insCells];
        int k = 0;
        while (k < use.length) {
            int shoe = st.shoe[pen.states[use[k]]];
            int nt = 0;
            int nit = 0;
            for (; k < use.length && st.shoe[pen.states[use[k]]] == shoe; k++) {
                int j = use[k];
                int s = pen.states[j];
                double w = pen.weight[j];
                double[] p = st.chance[s];
                double[] mv = st.move[s];
                for (int i = 0; i < Deals.COUNT; i++) {
                    if (p[i] == 0) {
                        continue;
                    }
                    int cell = j * Deals.COUNT + i;
                    for (int x = parts.from(cell); x < parts.to(cell); x++) {
                        int c = play.table[parts.key[x] - parts.minKey] * Deals.COUNT + i;
                        if (!open[c]) {
                            continue;
                        }
                        if (!on[c]) {
                            on[c] = true;
                            touched[nt++] = c;
                        }
                        double ws = w * parts.share(x);
                        double top = mv[i * moves + best[c]];
                        for (int m = 0; m < moves; m++) {
                            if (m != best[c] && st.legal[i * moves + m]) {
                                a[c * moves + m] += ws * (top - mv[i * moves + m]);
                            }
                        }
                        b[c] += ws * p[i];
                    }
                }
                for (int x = ip.start[j]; x < ip.start[j + 1]; x++) {
                    int c = play.insuranceGroup[x] * ScoringStates.ACE_UP + ip.deal[x];
                    if (!insOn[c]) {
                        insOn[c] = true;
                        insTouched[nit++] = c;
                    }
                    ia[c] += w * ip.value[x] * (insSum[c] > 0 ? 1 : -1);
                    ib[c] += w * ip.reach[x];
                }
            }
            for (int t = 0; t < nt; t++) {
                int c = touched[t];
                int i = c % Deals.COUNT;
                for (int m = 0; m < moves; m++) {
                    if (m != best[c] && st.legal[i * moves + m]) {
                        int cm = c * moves + m;
                        double e = a[cm] - gap[cm] * b[c];
                        var[cm] += e * e;
                        a[cm] = 0;
                    }
                }
                b[c] = 0;
                on[c] = false;
            }
            for (int t = 0; t < nit; t++) {
                int c = insTouched[t];
                double e = ia[c] - insGap[c] * ib[c];
                insVar[c] += e * e;
                ia[c] = 0;
                ib[c] = 0;
                insOn[c] = false;
            }
        }
        int n = st.shoesIn(1 - fold);
        double inflate = n > 1 ? (double) n / (n - 1) : 0;
        double bound = 0;
        double insBound = 0;
        for (int c = 0; c < cells; c++) {
            if (!open[c]) {
                continue;
            }
            int g = c / Deals.COUNT;
            int i = c % Deals.COUNT;
            double reach = fit.reach[g][i];
            out.decisions++;
            CloseCall worst = null;
            for (int m = 0; m < moves; m++) {
                if (m == best[c] || !st.legal[i * moves + m]) {
                    continue;
                }
                double se = Math.sqrt(inflate * var[c * moves + m]) / reach;
                double d = gap[c * moves + m];
                if (!(d > 2 * se)) {
                    CloseCall cc = new CloseCall(fold, fit.groups.lo[g], fit.groups.hi[g], i, best[c], m, d, se,
                            reach / fit.weight);
                    if (worst == null || cc.bound() > worst.bound()) {
                        worst = cc;
                    }
                }
            }
            if (worst != null) {
                out.unsettled++;
                bound += worst.bound();
                out.keep(worst);
            }
        }
        for (int c = 0; c < insCells; c++) {
            int g = c / ScoringStates.ACE_UP;
            int i = c % ScoringStates.ACE_UP;
            double reach = fit.reach[g][i];
            if (reach == 0) {
                continue;
            }
            double se = Math.sqrt(inflate * insVar[c]) / reach;
            out.insuranceDecisions++;
            if (!(insGap[c] > 2 * se)) {
                out.insuranceUnsettled++;
                CloseCall cc = new CloseCall(fold, fit.groups.lo[g], fit.groups.hi[g], i, insSum[c] > 0 ? -1 : -2,
                        insSum[c] > 0 ? -2 : -1, insGap[c], se, reach / fit.weight);
                insBound += cc.bound();
            }
        }
        foldBound[0] += testWeight * bound;
        foldBound[1] += testWeight * insBound;
    }

    // ------------------------------------------------------------------------ bets

    /** A penetration's bet keys under one count: the distinct values, and each state's place among them. */
    static final class BetKeys {
        final int[] value;
        final int[] index;

        BetKeys(int[] value, int[] index) {
            this.value = value;
            this.index = index;
        }
    }

    BetKeys betKeys(CountKeys.Count c, Pen pen) {
        int n = pen.states.length;
        int[] raw = new int[n];
        for (int j = 0; j < n; j++) {
            int s = pen.states[j];
            raw[j] = c.betKey(st.dealt[s], st.depth[s]);
        }
        int[] value = Arrays.stream(raw).distinct().sorted().toArray();
        int[] index = new int[n];
        for (int j = 0; j < n; j++) {
            index[j] = Arrays.binarySearch(value, raw[j]);
        }
        return new BetKeys(value, index);
    }

    /**
     * The isotonic (non-decreasing) fit of the mean at each key, weighted, by
     * pool-adjacent-violators over the keys with weight, in order of key. A key with no
     * weight takes the straight line between its nearest neighbours that have some, or the
     * nearer end's value beyond them; with no weight anywhere, every value is 0.
     */
    static double[] isotonic(int[] key, double[] weight, double[] sum) {
        int n = key.length;
        double[] out = new double[n];
        double[] bm = new double[n];
        double[] bw = new double[n];
        int[] bFirst = new int[n];
        int[] at = new int[n];
        int blocks = 0;
        int used = 0;
        for (int k = 0; k < n; k++) {
            if (weight[k] <= 0) {
                continue;
            }
            at[used++] = k;
            bm[blocks] = sum[k] / weight[k];
            bw[blocks] = weight[k];
            bFirst[blocks] = used - 1;
            blocks++;
            while (blocks > 1 && bm[blocks - 2] > bm[blocks - 1]) {
                double w = bw[blocks - 2] + bw[blocks - 1];
                bm[blocks - 2] = (bm[blocks - 2] * bw[blocks - 2] + bm[blocks - 1] * bw[blocks - 1]) / w;
                bw[blocks - 2] = w;
                blocks--;
            }
        }
        if (used == 0) {
            return out;
        }
        for (int b = 0; b < blocks; b++) {
            int end = b + 1 < blocks ? bFirst[b + 1] : used;
            for (int u = bFirst[b]; u < end; u++) {
                out[at[u]] = bm[b];
            }
        }
        int prev = -1;
        for (int u = 0; u <= used; u++) {
            int next = u < used ? at[u] : n;
            for (int k = prev + 1; k < next; k++) {
                if (prev < 0) {
                    out[k] = out[at[0]];
                } else if (next == n) {
                    out[k] = out[prev];
                } else {
                    double t = (double) (key[k] - key[prev]) / (key[next] - key[prev]);
                    out[k] = out[prev] + t * (out[next] - out[prev]);
                }
            }
            prev = next;
        }
        return out;
    }

    /**
     * Per-key detail of one fold's bets, for the report and the per-shoe sums: [key index]
     * then test weight, test sum of weight x RV, fitted advantage, the same-schedule bet at
     * spreads 1 to 8, and the own ramp's bet at spreads 1 to 8.
     */
    static final int DETAIL = 3 + 2 * SPREADS;

    /**
     * One fold's bets for one way of scoring: the advantage fitted on the training states,
     * both ways of betting on the test states, and their sums added into sums.
     */
    static void bets(BetKeys bk, int[] train, int[] test, int[] testByChart, double[] w, double[] rv, double[] chart,
                     double[] sums, double[][] detail) {
        int keys = bk.value.length;
        double[] tw = new double[keys];
        double[] ts = new double[keys];
        for (int j : train) {
            int k = bk.index[j];
            tw[k] += w[j];
            ts[k] += w[j] * rv[j];
        }
        double[] adv = isotonic(bk.value, tw, ts);
        double[] vw = new double[keys];
        double[] vs = new double[keys];
        for (int j : test) {
            int k = bk.index[j];
            vw[k] += w[j];
            vs[k] += w[j] * rv[j];
        }

        // Test keys in order of fitted advantage; keys with equal advantage form one block.
        int present = 0;
        for (int k = 0; k < keys; k++) {
            if (vw[k] > 0) {
                present++;
            }
        }
        Integer[] order = new Integer[present];
        present = 0;
        for (int k = 0; k < keys; k++) {
            if (vw[k] > 0) {
                order[present++] = k;
            }
        }
        Arrays.sort(order, (x, y) -> Double.compare(adv[x], adv[y]));
        int[] blockOf = new int[keys];
        double[] blockWeight = new double[present + 1];
        int blocks = 0;
        for (int o = 0; o < present; o++) {
            if (o > 0 && adv[order[o]] != adv[order[o - 1]]) {
                blocks++;
            }
            blockOf[order[o]] = blocks;
            blockWeight[blocks] += vw[order[o]];
        }
        if (present > 0) {
            blocks++;
        }

        // The integral of the reference bets' quantile function at the block boundaries.
        double[] boundary = new double[blocks + 1];
        for (int b = 0; b < blocks; b++) {
            boundary[b + 1] = boundary[b] + blockWeight[b];
        }
        double[][] integral = new double[SPREADS + 1][blocks + 1];
        double cw = 0;
        double[] cb = new double[SPREADS + 1];
        int next = 1;
        for (int j : testByChart) {
            double wj = w[j];
            if (wj == 0) {
                continue;
            }
            double units = chart[j] / UNIT;
            while (next < blocks && boundary[next] <= cw + wj) {
                double part = boundary[next] - cw;
                for (int s = 1; s <= SPREADS; s++) {
                    integral[s][next] = cb[s] + part * clamp(units, s);
                }
                next++;
            }
            cw += wj;
            for (int s = 1; s <= SPREADS; s++) {
                cb[s] += wj * clamp(units, s);
            }
        }
        for (; next <= blocks; next++) {
            for (int s = 1; s <= SPREADS; s++) {
                integral[s][next] = cb[s];
            }
        }

        double weight = 0;
        double flat = 0;
        for (int k = 0; k < keys; k++) {
            weight += vw[k];
            flat += vs[k];
        }
        sums[WEIGHT] += weight;
        sums[FLAT] += flat;
        for (int s = 1; s <= SPREADS; s++) {
            for (int k = 0; k < keys; k++) {
                if (vw[k] == 0) {
                    continue;
                }
                int b = blockOf[k];
                double sameBet = (integral[s][b + 1] - integral[s][b]) / blockWeight[b];
                double ownBet = clamp(adv[k] / UNIT, s);
                double playedBet = Math.floor(ownBet + 0.5);
                sums[same(s)] += sameBet * vs[k];
                sums[sameBet(s)] += sameBet * vw[k];
                sums[own(s)] += ownBet * vs[k];
                sums[ownBet(s)] += ownBet * vw[k];
                sums[played(s)] += playedBet * vs[k];
                sums[playedBet(s)] += playedBet * vw[k];
                if (detail != null) {
                    detail[k][2 + s] = sameBet;
                    detail[k][2 + SPREADS + s] = ownBet;
                }
            }
        }
        if (detail != null) {
            for (int k = 0; k < keys; k++) {
                detail[k][0] = vw[k];
                detail[k][1] = vs[k];
                detail[k][2] = adv[k];
            }
        }
    }

    static double clamp(double units, int spread) {
        return Math.max(1, Math.min(spread, units));
    }

    // ----------------------------------------------------------------- the scoring

    /** One row of the report: a count scored one way, at every penetration. */
    static final class Entry {
        final String name;
        /** The count whose bet key it bets on. */
        final CountKeys.Count count;
        final boolean bettingOnly;
        /** [pen][metric]: the pooled estimate. */
        final double[][] estimate = new double[CUTS.length][];
        /** [pen][metric][resample]. */
        final double[][][] boot = new double[CUTS.length][][];
        /** [pen][shoe]: the sum of 1/q x bet x RV over the shoe's states at the widest same schedule. */
        final double[][] shoeSums = new double[CUTS.length][];
        /**
         * [pen][row]: per bet key over both test folds: key, frequency, mean test RV, fitted
         * advantage, same-schedule bet at 1 to 8, own-ramp bet at 1 to 8; the last three pooled
         * over folds by test weight.
         */
        final double[][][] keys = new double[CUTS.length][][];

        Entry(String name, CountKeys.Count count, boolean bettingOnly) {
            this.name = name;
            this.count = count;
            this.bettingOnly = bettingOnly;
        }
    }

    /** Everything scored for one count: its entries, its decision resolution and learning curve. */
    static final class Result {
        final CountKeys.Count count;
        final List<Entry> entries = new ArrayList<>();
        final Resolution[] resolution = new Resolution[CUTS.length];
        /** [pen]: pooled flat EV with play and insurance fitted on half of each training fold. */
        final double[] halfFitFlat = new double[CUTS.length];
        /** [pen][resample]: the same, fitted on the half's drawn shoes and scored on the resampled test fold. */
        final double[][] halfFitBoot = new double[CUTS.length][];
        /** [pen][fold]: the play groups of each fold's fit. */
        final Groups[][] groups = new Groups[CUTS.length][2];
        double seconds;

        Result(CountKeys.Count count) {
            this.count = count;
        }
    }

    /**
     * Scores a count, as itself and betting only, and, given its ace side-count variant, that
     * too as itself and betting only, at every penetration, with the bootstrap.
     */
    Result score(CountKeys.Count count, CountKeys.Count sideCounted) {
        long start = System.nanoTime();
        Result result = new Result(count);
        List<CountKeys.Count> betting = new ArrayList<>();
        betting.add(count);
        if (sideCounted != null) {
            if (sideCounted.base != count) {
                throw new IllegalArgumentException(sideCounted + " is not a variant of " + count);
            }
            betting.add(sideCounted);
        }
        for (CountKeys.Count c : betting) {
            result.entries.add(new Entry(c.name, c, false));
            result.entries.add(new Entry(c.name + ", betting only", c, true));
        }
        for (int p = 0; p < CUTS.length; p++) {
            Pen pen = pens[p];
            Parts parts = parts(count, pen);
            InsuranceParts ip = insuranceParts(parts, pen);
            BetKeys[] bk = new BetKeys[result.entries.size()];
            for (int e = 0; e < bk.length; e++) {
                bk[e] = e % 2 == 1 ? bk[e - 1] : betKeys(result.entries.get(e).count, pen);
            }
            FoldPlay[] plays = new FoldPlay[2];
            for (int f = 0; f < 2; f++) {
                plays[f] = foldPlay(parts, pen, ip, pen.train[f], pen.weight, null, null);
                result.groups[p][f] = plays[f].groups;
            }
            result.resolution[p] = resolution(parts, pen, ip, plays);
            result.halfFitFlat[p] = halfFit(parts, pen, ip, pen.weight, null, null, null);
            result.halfFitBoot[p] = new double[boot.resamples];

            double[][] sums = scoreOnce(pen, ip, plays, bk, result.entries, pen.weight, p, true);
            for (int e = 0; e < sums.length; e++) {
                result.entries.get(e).estimate[p] = sums[e];
                result.entries.get(e).boot[p] = new double[METRICS][boot.resamples];
            }
            double[] wm = new double[pen.states.length];
            double[] um = new double[pen.states.length];
            FoldPlay[] refit = new FoldPlay[2];
            ByKey byKey = boot.resamples > 0 ? byKey(parts, pen) : null;
            for (int r = 0; r < boot.resamples; r++) {
                for (int j = 0; j < wm.length; j++) {
                    um[j] = boot.mult(r, st.shoe[pen.states[j]]);
                    wm[j] = pen.weight[j] * um[j];
                }
                for (int f = 0; f < 2; f++) {
                    refit[f] = foldPlay(parts, pen, ip, drawn(pen.train[f], wm), wm, um, null, byKey, plays[f]);
                }
                double[][] rs = scoreOnce(pen, ip, refit, bk, result.entries, wm, p, false);
                for (int e = 0; e < rs.length; e++) {
                    for (int m = 0; m < METRICS; m++) {
                        result.entries.get(e).boot[p][m][r] = rs[e][m];
                    }
                }
                result.halfFitBoot[p][r] = halfFit(parts, pen, ip, wm, um, byKey, plays);
            }
        }
        result.seconds = (System.nanoTime() - start) / 1e9;
        return result;
    }

    /** Both folds' decision resolution at one penetration, the bounds averaged over the folds by test weight. */
    private Resolution resolution(Parts parts, Pen pen, InsuranceParts ip, FoldPlay[] plays) {
        Resolution res = new Resolution();
        double[] bounds = new double[2];
        double testWeight = 0;
        for (int f = 0; f < 2; f++) {
            double[] insSum = insuranceSums(ip, plays[f].insuranceGroup, plays[f].groups.count(), pen.train[f],
                    pen.weight);
            double tw = 0;
            for (int j : pen.test[f]) {
                tw += pen.weight[j];
            }
            testWeight += tw;
            resolve(parts, pen, f, pen.train[f], plays[f], ip, insSum, tw, bounds, res);
        }
        res.bound = testWeight == 0 ? 0 : bounds[0] / testWeight;
        res.insuranceBound = testWeight == 0 ? 0 : bounds[1] / testWeight;
        return res;
    }

    /**
     * A count's decision resolution at every penetration, from each fold's play fitted on its
     * whole training fold, without the scoring or the bootstrap: what the report measures on
     * parts of a run to see how the bounds fall as the run grows.
     */
    Resolution[] resolution(CountKeys.Count count) {
        Resolution[] out = new Resolution[CUTS.length];
        for (int p = 0; p < CUTS.length; p++) {
            Pen pen = pens[p];
            Parts parts = parts(count, pen);
            InsuranceParts ip = insuranceParts(parts, pen);
            FoldPlay[] plays = new FoldPlay[2];
            for (int f = 0; f < 2; f++) {
                plays[f] = foldPlay(parts, pen, ip, pen.train[f], pen.weight, null, null);
            }
            out[p] = resolution(parts, pen, ip, plays);
        }
        return out;
    }

    /**
     * Scores every entry once under the weights given, with each fold's play as fitted under
     * them: fits insurance per fold in that play's groups, then bets. With detail, also fills
     * each entry's per-key table and per-shoe sums for penetration p.
     */
    private double[][] scoreOnce(Pen pen, InsuranceParts ip, FoldPlay[] plays, BetKeys[] bk,
                                 List<Entry> entries, double[] w, int p, boolean detail) {
        int n = pen.states.length;
        double[][] sums = new double[entries.size()][METRICS + 1];
        double[][][][] det = detail ? new double[entries.size()][2][][] : null;
        double[] rv = new double[n];
        double[][] shoe = detail ? new double[entries.size()][st.shoes] : null;
        for (int f = 0; f < 2; f++) {
            double[] insSum = insuranceSums(ip, plays[f].insuranceGroup, plays[f].groups.count(), pen.train[f], w);
            double[] ins = insuranceValues(ip, plays[f].insuranceGroup, insSum);
            for (int e = 0; e < entries.size(); e++) {
                boolean chartPlay = entries.get(e).bettingOnly;
                double[] play = chartPlay ? pen.chart : plays[f].base;
                for (int j = 0; j < n; j++) {
                    rv[j] = play[j] + ins[j];
                }
                double[] s = sums[e];
                for (int j : pen.test[f]) {
                    s[PLAY] += w[j] * play[j];
                    s[INSURANCE] += w[j] * ins[j];
                }
                double[][] d = null;
                if (detail) {
                    d = new double[bk[e].value.length][DETAIL];
                    det[e][f] = d;
                }
                bets(bk[e], pen.train[f], pen.test[f], pen.testByChart[f], w, rv, pen.chart, s, d);
                if (detail) {
                    for (int j : pen.test[f]) {
                        shoe[e][st.shoe[pen.states[j]]] += w[j] * d[bk[e].index[j]][2 + SPREADS] * rv[j];
                    }
                }
            }
        }
        double[][] out = new double[entries.size()][METRICS];
        for (int e = 0; e < entries.size(); e++) {
            double weight = sums[e][WEIGHT];
            for (int m = 0; m < METRICS; m++) {
                out[e][m] = weight == 0 ? Double.NaN : sums[e][m] / weight;
            }
            if (detail) {
                entries.get(e).shoeSums[p] = shoe[e];
                entries.get(e).keys[p] = keyTable(bk[e], det[e], weight);
            }
        }
        return out;
    }

    private static double[][] keyTable(BetKeys bk, double[][][] det, double totalWeight) {
        List<double[]> rows = new ArrayList<>();
        for (int k = 0; k < bk.value.length; k++) {
            double vw = det[0][k][0] + det[1][k][0];
            if (vw == 0) {
                continue;
            }
            double[] row = new double[1 + DETAIL];
            row[0] = bk.value[k];
            row[1] = vw / totalWeight;
            row[2] = (det[0][k][1] + det[1][k][1]) / vw;
            for (int c = 2; c < DETAIL; c++) {
                row[1 + c] = (det[0][k][0] * det[0][k][c] + det[1][k][0] * det[1][k][c]) / vw;
            }
            rows.add(row);
        }
        return rows.toArray(new double[0][]);
    }

    /**
     * The learning-curve check's flat EV: play and insurance fitted on half of each training
     * fold, the shoes whose index halved is even, and scored on the whole test fold, all
     * under the weights w with each state counted units[j] times toward M_play (once when
     * units is null). On a resample the half is the half's drawn shoes and the test fold the
     * resampled one, so the change from the full fit is taken resample by resample on the
     * same states. Given the full fit's plays, a half fit's values are found from theirs.
     */
    double halfFit(Parts parts, Pen pen, InsuranceParts ip, double[] w, double[] units, ByKey byKey,
                   FoldPlay[] full) {
        double num = 0;
        double den = 0;
        for (int f = 0; f < 2; f++) {
            int[] half = Arrays.stream(pen.train[f])
                    .filter(j -> ((st.shoe[pen.states[j]] >> 1) & 1) == 0 && w[j] != 0).toArray();
            FoldPlay play = foldPlay(parts, pen, ip, half, w, units, pen.test[f], byKey, full == null ? null : full[f]);
            double[] ins = insuranceValues(ip, play.insuranceGroup,
                    insuranceSums(ip, play.insuranceGroup, play.groups.count(), half, w));
            for (int j : pen.test[f]) {
                num += w[j] * (play.base[j] + ins[j]);
                den += w[j];
            }
        }
        return den == 0 ? Double.NaN : num / den;
    }

    // ----------------------------------------------------------------- references

    /**
     * The rows fitted on nothing, scored on every state of the penetration, with the
     * bootstrap: the chart flat; the perfect first move with perfect insurance, flat; and the
     * chart playing with the same-schedule bets placed on each state's own true advantage,
     * the most any ordering of that schedule can make of the chart's play.
     */
    List<Entry> references() {
        Entry chart = new Entry("Chart, no count, flat", null, false);
        Entry perfect = new Entry("Perfect first move and insurance, flat", null, false);
        Entry oracle = new Entry("Chart play, schedule on the true advantage", null, false);
        List<Entry> out = Arrays.asList(chart, perfect, oracle);
        for (int p = 0; p < CUTS.length; p++) {
            Pen pen = pens[p];
            for (Entry e : out) {
                e.boot[p] = new double[METRICS][boot.resamples];
            }
            double[] w = pen.weight.clone();
            for (int r = -1; r < boot.resamples; r++) {
                if (r >= 0) {
                    for (int j = 0; j < w.length; j++) {
                        w[j] = pen.weight[j] * boot.mult(r, st.shoe[pen.states[j]]);
                    }
                }
                double[][] m = referenceMetrics(pen, w);
                for (int e = 0; e < 3; e++) {
                    if (r < 0) {
                        out.get(e).estimate[p] = m[e];
                    } else {
                        for (int k = 0; k < METRICS; k++) {
                            out.get(e).boot[p][k][r] = m[e][k];
                        }
                    }
                }
            }
        }
        return out;
    }

    private double[][] referenceMetrics(Pen pen, double[] w) {
        double[][] m = new double[3][METRICS];
        for (double[] row : m) {
            Arrays.fill(row, Double.NaN);
        }
        double weight = 0;
        double chart = 0;
        double perfect = 0;
        double insurance = 0;
        double[] oracle = new double[SPREADS + 1];
        double[] oracleBet = new double[SPREADS + 1];
        for (int j = 0; j < w.length; j++) {
            int s = pen.states[j];
            weight += w[j];
            chart += w[j] * st.chart[s];
            perfect += w[j] * st.perfect[s];
            insurance += w[j] * st.perfectInsurance[s];
            for (int sp = 1; sp <= SPREADS; sp++) {
                double bet = clamp(st.chart[s] / UNIT, sp);
                oracle[sp] += w[j] * bet * st.chart[s];
                oracleBet[sp] += w[j] * bet;
            }
        }
        m[0][FLAT] = m[0][PLAY] = chart / weight;
        m[0][INSURANCE] = 0;
        m[1][FLAT] = (perfect + insurance) / weight;
        m[1][PLAY] = perfect / weight;
        m[1][INSURANCE] = insurance / weight;
        m[2][FLAT] = m[2][PLAY] = chart / weight;
        m[2][INSURANCE] = 0;
        for (int sp = 1; sp <= SPREADS; sp++) {
            m[2][same(sp)] = oracle[sp] / weight;
            m[2][sameBet(sp)] = oracleBet[sp] / weight;
        }
        return m;
    }
}
