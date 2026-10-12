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
 * Asked for (wide), the wider plans of PLAN_COMPUTE.md section 3 are scored beside all of this
 * the same two ways, with spreads to 1-20, a finer unit and sitting out (Plan, Wide), from the
 * same fitted advantage and blocks, after every existing sum is made, so no existing figure moves.
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
    /**
     * Whether score adds, after a count's other entries, the count playing its own fit and
     * betting on each state's own round value under it, at first decisions and with full play
     * (Entry.ownValue). Off unless asked for, so a published count's Result is as it was.
     */
    boolean ownValueRows;
    /**
     * The wider bet plans (PLAN_COMPUTE.md section 3) scored beside every existing figure, or
     * null for none, as every run before them scored. Their sums are taken apart from the
     * existing ones and touch none of them, so an entry's estimate, resamples, per-shoe sums and
     * key table are the same to the bit with them on or off (CeilingScoringTest).
     */
    Plan[] wide;
    /**
     * Each state's shoe dealt again, for the plans that leave the table (Plan.exit): where the
     * lowest bet key of the shoe so far comes from. Needed only when a plan in wide has an exit.
     */
    RoundStarts roundStarts;

    /**
     * What the point estimate values each test state at, handed out entry by entry and fold by
     * fold (PLAN_COMPUTE.md 5.3): KeyEdges builds the edge at each count from it. Null by
     * default, and then nothing is handed out. It is called only from the pass that fills the
     * per-key tables, never from a resample, so it costs one copy per entry and fold even in a
     * run with 1,000 resamples. It is handed copies, so nothing it does can change a figure the
     * scorer computes; SinkTest holds every figure of a Result the same to the bit with it on
     * and off.
     */
    StateSink stateSink;
    /**
     * Each resample's per-key sums under that resample's refitted play and its multiplicities,
     * handed out entry by entry and fold by fold (PLAN_COMPUTE.md 5.3, run A'), so the edge at
     * each count gets intervals with the plays refitted, as the published ones are. Null by
     * default. Resamples are worked out on several threads, so it is called from several at
     * once, each call with its own resample; it is handed arrays made for it alone.
     */
    ResampleSink resampleSink;

    /** Takes what the point estimate values each test state at (stateSink). */
    interface StateSink {
        /**
         * Entry entry at penetration p, fold f: its test states, each by its index in the
         * ScoringStates, with its weight 1/q, its round value before insurance under the entry's
         * play (the chart's for a row betting only), what the fold's fitted insurance adds to
         * it, and its bet key (the key itself, not its place among the keys). All five arrays
         * are in the order the scorer walks the fold's test states, and are the sink's to keep.
         */
        void states(int p, Entry entry, int f, int[] state, double[] weight, double[] play, double[] insurance, int[] key);
    }

    /** Takes each resample's per-key sums (resampleSink). */
    interface ResampleSink {
        /**
         * Resample r at penetration p, entry entry, fold f: for each of the entry's bet keys at
         * the penetration (key, in increasing order), the sum over the fold's test states with
         * that key of their resampled weight (1/q times the shoe's multiplicity), of weight times
         * round value under the resample's refitted play and insurance, and of weight times what
         * that insurance adds. Every array is new and the sink's to keep.
         */
        void sums(int p, int r, Entry entry, int f, int[] key, double[] weight, double[] value, double[] insurance);
    }

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
        private Plan[] planBetsFor;
        private double[][] planBets;

        /** The plans' reference bets on these states' chart advantages (referenceBets), worked out once per plans. */
        synchronized double[][] planBets(Plan[] plans) {
            if (planBetsFor != plans) {
                planBets = referenceBets(plans, chart);
                planBetsFor = plans;
            }
            return planBets;
        }

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

    RoundRules rules() {
        return st.rules;
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
    static final class Groups implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

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
        double valued = 0;
        for (int j : use) {
            valued += units == null ? 1 : units[j];
        }
        return grow(parts.minKey, massAll, roundsAll, held, threshold(valued));
    }

    /**
     * Groups grown over the keys from minKey up, key minKey + k holding mass[k] valued states
     * and rounds[k] rounds, held[k] saying whether any state has the key: from the key holding
     * the most rounds, walking outward each way, a group closes once it holds threshold of
     * mass, and a partial group at either end joins its neighbour. Keys no state holds at
     * either end are left out of the walk; the end groups take everything beyond them.
     */
    static Groups grow(int minKey, double[] massAll, double[] roundsAll, boolean[] held, double threshold) {
        int span = held.length;
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
        int min = minKey + first;
        double[] mass = Arrays.copyOfRange(massAll, first, last + 1);
        double[] rounds = Arrays.copyOfRange(roundsAll, first, last + 1);
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

    /** The move slots of Hit and Split, whose values a full-index fit replaces (StateValuer.MOVES). */
    static final int HIT = 1;
    static final int SPLIT = 3;

    /**
     * What Hit and Split are worth in each (state, deal) part of a penetration under one
     * full-index fit (FULL_INDEX_PLAYS.md, sections 2 to 4), in the units of
     * ScoringStates.move: P(i) x (-P(nat|i) + (1 - P(nat|i)) x V). Hit is valued by the
     * player tree under the fit's later plays, a value per tag-class part for a count that
     * tags by colour or face; Split by ExactRound under the fit's post-split play at the
     * part's key. Indexed like Parts.key; NaN where a part is not valued. Stand, Double and
     * Surrender keep their stored values, which no later play changes.
     *
     * A fit's values are its own: the states it is scored on and the states it is fitted on
     * both take them, and no fit reads another's.
     *
     * Split is held either for every part, indexed like Hit, or compactly, for the parts of the
     * deals that can be split alone: 100 of the 550 deals, so about a sixth of the parts. Then
     * splitAt[x] is part x's place in split, -1 for a part of any other deal, which nothing
     * asks the Split value of. The values are the same doubles either way.
     */
    static final class PlayValues {
        final double[] hit;
        final double[] split;
        /** Null when split is indexed like hit; otherwise each part's place in split, or -1. */
        final int[] splitAt;

        PlayValues(int parts) {
            hit = new double[parts];
            split = new double[parts];
            splitAt = null;
            Arrays.fill(hit, Double.NaN);
            Arrays.fill(split, Double.NaN);
        }

        PlayValues(double[] hit, double[] split) {
            if (hit.length != split.length) {
                throw new IllegalArgumentException("a Hit and a Split value for every part");
            }
            this.hit = hit;
            this.split = split;
            this.splitAt = null;
        }

        /** Values held compactly: a Hit value for every part, a Split value for the parts that slots places. */
        PlayValues(SplitSlots slots) {
            hit = new double[slots.at.length];
            split = new double[slots.count];
            splitAt = slots.at;
            Arrays.fill(hit, Double.NaN);
            Arrays.fill(split, Double.NaN);
        }

        private PlayValues(double[] hit, double[] split, int[] splitAt) {
            this.hit = hit;
            this.split = split;
            this.splitAt = splitAt;
        }

        /** Part x's Split value; a compact holder throws for a part of a deal that cannot be split. */
        double split(int x) {
            return splitAt == null ? split[x] : split[splitAt[x]];
        }

        void setSplit(int x, double v) {
            if (splitAt == null) {
                split[x] = v;
            } else {
                split[splitAt[x]] = v;
            }
        }

        PlayValues copy() {
            return new PlayValues(hit.clone(), split.clone(), splitAt);
        }
    }

    /** Where each part of a penetration's Parts keeps its Split value in a compact PlayValues: shared by every fit of the count there. */
    static final class SplitSlots {
        final int[] at;
        final int count;

        SplitSlots(int[] at, int count) {
            this.at = at;
            this.count = count;
        }
    }

    /** The slots of the parts of every deal that can be split, in order, -1 for every other part. */
    SplitSlots splitSlots(Parts parts, Pen pen) {
        int[] at = new int[parts.key.length];
        Arrays.fill(at, -1);
        int n = 0;
        for (int j = 0; j < pen.states.length; j++) {
            for (int i = 0; i < Deals.COUNT; i++) {
                if (!st.legal[i * ScoringStates.MOVES + SPLIT]) {
                    continue;
                }
                int cell = j * Deals.COUNT + i;
                for (int x = parts.from(cell); x < parts.to(cell); x++) {
                    at[x] = n++;
                }
            }
        }
        return new SplitSlots(at, n);
    }

    /**
     * What move m of deal i adds to a state's round value in part x: the stored entry, or
     * under a full-index fit's values the part's own Hit or Split value.
     */
    static double value(PlayValues pv, double[] stored, int i, int x, int m) {
        if (pv != null) {
            if (m == HIT) {
                return pv.hit[x];
            }
            if (m == SPLIT) {
                return pv.split(x);
            }
        }
        return stored[i * ScoringStates.MOVES + m];
    }

    /** A fitted play: the move for each (group, deal), and the sums it was chosen from. */
    static final class Fit implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

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
        return fit(parts, pen, use, groups, table, w, null);
    }

    /** The same, with Hit and Split valued under a full-index fit's values when pv is given. */
    Fit fit(Parts parts, Pen pen, int[] use, Groups groups, int[] table, double[] w, PlayValues pv) {
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
                            row[base + m] += ws * value(pv, mv, i, x, m);
                        }
                    }
                }
            }
        }
        return fitFrom(groups, sum, reach, weight);
    }

    /**
     * The fit made from sums already added up, as fit makes them: each (group, deal) takes its
     * best move by its sums, the chart's where nothing reached it or it ties for best. The
     * arrays are kept, not copied.
     */
    Fit fitFrom(Groups groups, double[][] sum, double[][] reach, double weight) {
        int g0 = groups.count();
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
        playValues(parts, pen, fit, table, which, out, null);
    }

    /** The same, with Hit and Split valued under a full-index fit's values when pv is given. */
    void playValues(Parts parts, Pen pen, Fit fit, int[] table, int[] which, double[] out, PlayValues pv) {
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
                    v += parts.share(x) * value(pv, mv, i, x, fit.move[g][i]);
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
        PlayValues pv = earlier.values;
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
                    int x = byKey.part[y];
                    out[j] += parts.share(x) * (value(pv, mv, i, x, now[i]) - value(pv, mv, i, x, was[i]));
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
        /** The Hit and Split values the play was fitted and valued with; null for the stored ones. */
        final PlayValues values;

        FoldPlay(Groups groups, Fit fit, int[] table, int[] insuranceGroup, double[] base) {
            this(groups, fit, table, insuranceGroup, base, null);
        }

        FoldPlay(Groups groups, Fit fit, int[] table, int[] insuranceGroup, double[] base, PlayValues values) {
            this.groups = groups;
            this.fit = fit;
            this.table = table;
            this.insuranceGroup = insuranceGroup;
            this.base = base;
            this.values = values;
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
        return foldPlay(parts, pen, ip, use, w, units, valueOn, byKey, earlier, earlier == null ? null : earlier.values);
    }

    /**
     * The same under a full-index fit's Hit and Split values when pv is given. An earlier
     * play whose values are given must have been fitted with the same ones.
     */
    FoldPlay foldPlay(Parts parts, Pen pen, InsuranceParts ip, int[] use, double[] w, double[] units, int[] valueOn,
                      ByKey byKey, FoldPlay earlier, PlayValues pv) {
        if (earlier != null && earlier.values != pv) {
            throw new IllegalArgumentException("an earlier play is valued from with the values it was fitted with");
        }
        Groups groups = groups(parts, pen, use, w, units);
        int[] table = groups.table(parts);
        Fit fit = fit(parts, pen, use, groups, table, w, pv);
        double[] base = new double[pen.states.length];
        if (earlier == null) {
            playValues(parts, pen, fit, table, valueOn, base, pv);
        } else {
            playValuesFrom(parts, pen, byKey, earlier, fit, table, base);
        }
        return new FoldPlay(groups, fit, table, ip.groups(parts, table), base, pv);
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
    static final class CloseCall implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

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

    /**
     * One later cell of a full-index fit (FULL_INDEX_PLAYS.md section 3) the data do not settle:
     * hit or stand at one contested spot for the count keys loKey to hiKey.
     */
    static final class LaterCall implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        final int fold;
        final int loKey;
        final int hiKey;
        /** PlayerTree.spot of the cell: its total, softness and up-card. */
        final int spot;
        final boolean stands;
        /** The one-flip gain of the fitted answer per time the cell comes up, and its standard error. */
        final double gain;
        final double se;
        /** How often the cell comes up per round of the training states. */
        final double frequency;

        LaterCall(int fold, int loKey, int hiKey, int spot, boolean stands, double gain, double se, double frequency) {
            this.fold = fold;
            this.loKey = loKey;
            this.hiKey = hiKey;
            this.spot = spot;
            this.stands = stands;
            this.gain = gain;
            this.se = se;
            this.frequency = frequency;
        }

        /** What keeping the fitted answer can cost per round, at most, as CloseCall.bound. */
        double bound() {
            return frequency * Math.max(0, 2 * se - gain);
        }
    }

    /** How well the data settle a count's decisions at one penetration, both folds' fits together. */
    static final class Resolution implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        int decisions;
        int unsettled;
        /** Per round: the test-weighted mean over folds of the summed bound on the play's close calls. */
        double bound;
        int insuranceDecisions;
        int insuranceUnsettled;
        /** The same for insurance. */
        double insuranceBound;
        /** The same for a full-index fit's later cells; nothing for first-decision-only play. */
        int laterDecisions;
        int laterUnsettled;
        double laterBound;
        /** The close calls with the largest bounds, largest first. */
        final List<CloseCall> closest = new ArrayList<>();
        final List<LaterCall> closestLater = new ArrayList<>();
        static final int KEEP = 12;

        void keep(CloseCall c) {
            closest.add(c);
            closest.sort((a, b) -> Double.compare(b.bound(), a.bound()));
            if (closest.size() > KEEP) {
                closest.remove(closest.size() - 1);
            }
        }

        void keepLater(LaterCall c) {
            closestLater.add(c);
            closestLater.sort((a, b) -> Double.compare(b.bound(), a.bound()));
            if (closestLater.size() > KEEP) {
                closestLater.remove(closestLater.size() - 1);
            }
        }

        /** The decision bound: every fitted decision's, the first move's, insurance's and the later cells' added. */
        double total() {
            return bound + insuranceBound + laterBound;
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
        PlayValues pv = play.values;
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
                        double top = value(pv, mv, i, x, best[c]);
                        for (int m = 0; m < moves; m++) {
                            if (m != best[c] && st.legal[i * moves + m]) {
                                a[c * moves + m] += ws * (top - value(pv, mv, i, x, m));
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
        bets(bk, train, test, testByChart, w, rv, chart, sums, detail, null);
    }

    /**
     * The same, and given acc, the wide plans' figures added into it from the same fitted advantage
     * and blocks (Wide.add), after every existing sum is made: with acc null this is the path every
     * run before the wide plans took, line for line.
     */
    static void bets(BetKeys bk, int[] train, int[] test, int[] testByChart, double[] w, double[] rv, double[] chart,
                     double[] sums, double[][] detail, Wide acc) {
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

        double[][] integral = scheduleIntegral(testByChart, w, chart, blockWeight, blocks);

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
        if (acc != null) {
            acc.add(bk, adv, vw, vs, blockOf, blockWeight, blocks, test, testByChart, w, rv, weight);
        }
    }

    static double clamp(double units, int spread) {
        return Math.max(1, Math.min(spread, units));
    }

    /**
     * The integral of the reference bets' quantile function at the boundaries of blocks of the
     * given weights, in order: [spread][boundary], the bets the proportional rule places on the
     * states of byChart (in order of chart advantage, each weighted w) at spreads 1 to 8. A block's
     * same-schedule bet is the integral across it over its weight.
     */
    static double[][] scheduleIntegral(int[] byChart, double[] w, double[] chart, double[] blockWeight, int blocks) {
        double[] boundary = new double[blocks + 1];
        for (int b = 0; b < blocks; b++) {
            boundary[b + 1] = boundary[b] + blockWeight[b];
        }
        double[][] integral = new double[SPREADS + 1][blocks + 1];
        double cw = 0;
        double[] cb = new double[SPREADS + 1];
        int next = 1;
        for (int j : byChart) {
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
        return integral;
    }

    /**
     * Bets on each state's own value (COUNT_DESIGN.md, section 2): the fold's states with weight,
     * in order of order[j], equal values forming one block, are handed the same schedule's bets,
     * the reference bets of byChart's states, as a count's keys are; each state's round value is
     * rv[j]. Adds the flat and same-schedule sums into sums, as bets does for a count; the own
     * ramp has no meaning here, and its sums are left alone.
     */
    static void ownValueBets(int[] test, int[] byChart, double[] w, double[] order, double[] rv, double[] chart,
                             double[] sums) {
        ownValueBets(test, byChart, w, order, rv, chart, sums, null);
    }

    /** The same, and given acc, the wide plans' same-schedule figures added into it from the same blocks (Wide.addBlocks). */
    static void ownValueBets(int[] test, int[] byChart, double[] w, double[] order, double[] rv, double[] chart,
                             double[] sums, Wide acc) {
        int present = 0;
        for (int j : test) {
            if (w[j] != 0) {
                present++;
            }
        }
        Integer[] sorted = new Integer[present];
        present = 0;
        for (int j : test) {
            if (w[j] != 0) {
                sorted[present++] = j;
            }
        }
        Arrays.sort(sorted, (x, y) -> Double.compare(order[x], order[y]));
        double[] blockWeight = new double[present + 1];
        double[] blockSum = new double[present + 1];
        int blocks = 0;
        for (int o = 0; o < present; o++) {
            if (o > 0 && order[sorted[o]] != order[sorted[o - 1]]) {
                blocks++;
            }
            blockWeight[blocks] += w[sorted[o]];
            blockSum[blocks] += w[sorted[o]] * rv[sorted[o]];
        }
        if (present > 0) {
            blocks++;
        }
        double[][] integral = scheduleIntegral(byChart, w, chart, blockWeight, blocks);
        for (int b = 0; b < blocks; b++) {
            sums[WEIGHT] += blockWeight[b];
            sums[FLAT] += blockSum[b];
            for (int s = 1; s <= SPREADS; s++) {
                double bet = (integral[s][b + 1] - integral[s][b]) / blockWeight[b];
                sums[same(s)] += bet * blockSum[b];
                sums[sameBet(s)] += bet * blockWeight[b];
            }
        }
        if (acc != null) {
            acc.addBlocks(blockWeight, blockSum, blocks, byChart, w);
        }
    }

    /**
     * The same-schedule bet at spread s of each bet key held by the states given, the bets handed
     * out over those states' own reference bets in order of the advantage adv fitted at each key
     * (as bets hands a test fold's), NaN at a key none of them holds: the bets the refit check
     * weights a training state by.
     */
    static double[] keyBets(BetKeys bk, double[] adv, int[] states, int[] byChart, double[] w, double[] chart, int s) {
        int keys = bk.value.length;
        double[] vw = new double[keys];
        for (int j : states) {
            vw[bk.index[j]] += w[j];
        }
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
        double[][] integral = scheduleIntegral(byChart, w, chart, blockWeight, blocks);
        double[] out = new double[keys];
        Arrays.fill(out, Double.NaN);
        for (int k = 0; k < keys; k++) {
            if (vw[k] > 0) {
                int b = blockOf[k];
                out[k] = (integral[s][b + 1] - integral[s][b]) / blockWeight[b];
            }
        }
        return out;
    }

    // ------------------------------------------------------- wide plans (PLAN_COMPUTE.md section 3)

    /**
     * A bet plan wider than the spreads 1 to 8 (PLAN_COMPUTE.md 3.1). On a round whose advantage is
     * a it bets g(a) = 0 when a < enter, else clamp(a / unit, 1, cap) units: a unit for each `unit`
     * of advantage, never less than one or more than cap, and nothing below the entry threshold. A
     * plan with an exit leaves the table for the rest of the shoe at the first round start whose
     * advantage is below exit, and is back at the shuffle.
     *
     * Bets are handed out two ways, as at spreads 1 to 8 (PLAN_COMPUTE.md 3.2):
     * - the same schedule (P, S and B plans): g of each test state's true chart advantage gives the
     *   reference bets, and every count hands them out in order of its own fitted advantage. g never
     *   decreases, so the hand-out is the one bets already makes, and in every fold and resample
     *   every count places the same bets, at the same average, in the same share of rounds;
     * - the own rule: each key bets g of its own fitted advantage. A plan with an exit has only
     *   this: a reference for it would need the true advantage at every earlier round start of the
     *   shoe, and only the valued states have one.
     *
     * P8 is the 1-8 same schedule and own ramp that same(8) and own(8) hold, and gives them to the
     * bit (SystemScorerTest), which checks that the plans read the existing figures as they are.
     */
    static final class Plan implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        final String label;
        /** The advantage a unit of bet stands for. */
        final double unit;
        final int cap;
        /** A round whose advantage is below this is sat out; minus infinity for none. */
        final double enter;
        /** The table is left until the shuffle at the first round start below this; minus infinity for none. */
        final double exit;

        Plan(String label, double unit, int cap, double enter, double exit) {
            if (!(unit > 0) || cap < 1 || Double.isNaN(enter) || Double.isNaN(exit)) {
                throw new IllegalArgumentException(label + ": unit " + unit + ", cap " + cap + ", enter " + enter
                        + ", exit " + exit);
            }
            if (enter != Double.NEGATIVE_INFINITY && exit != Double.NEGATIVE_INFINITY) {
                throw new IllegalArgumentException(label + ": a plan sits out by entering or by leaving, not both");
            }
            this.label = label;
            this.unit = unit;
            this.cap = cap;
            this.enter = enter;
            this.exit = exit;
        }

        /**
         * g(a), the bet on a round of advantage a, 0 when it is sat out. A round exactly at the
         * entry threshold is played: the threshold is the least advantage the plan plays at.
         */
        double bet(double a) {
            return a < enter ? 0 : clamp(a / unit, cap);
        }

        /** Whether its bets are also handed out as the same schedule, not only by the own rule. */
        boolean sameSchedule() {
            return exit == Double.NEGATIVE_INFINITY;
        }

        /** Whether it sits out some rounds, by entering at a threshold or by leaving. */
        boolean wonging() {
            return enter != Double.NEGATIVE_INFINITY || exit != Double.NEGATIVE_INFINITY;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * The 23 plans of PLAN_COMPUTE.md 3.1, in the order the report prints them: P8 to P20, S16 at
     * one unit per 0.125%, the back-counting plans B (enter at -0.5, 0, +0.5 or +1.0% with a cap of
     * 1, 8 or 16) and the plans that leave until the shuffle, X (exit at -1.0, -0.5 or 0% with a cap
     * of 8 or 16). Labels are those of SHOE_SPEC.md B2.5, such as B0,8 and X-1.0,8.
     */
    static final Plan[] WIDE_PLANS = widePlans();

    private static Plan[] widePlans() {
        double none = Double.NEGATIVE_INFINITY;
        List<Plan> out = new ArrayList<>();
        for (int cap : new int[]{8, 12, 16, 20}) {
            out.add(new Plan("P" + cap, UNIT, cap, none, none));
        }
        out.add(new Plan("S16", UNIT / 2, 16, none, none));
        for (int cap : new int[]{1, 8, 16}) {
            for (double tau : new double[]{-0.005, 0, 0.005, 0.01}) {
                out.add(new Plan("B" + tauLabel(tau) + "," + cap, UNIT, cap, tau, none));
            }
        }
        for (int cap : new int[]{8, 16}) {
            for (double tau : new double[]{-0.01, -0.005, 0}) {
                out.add(new Plan("X" + tauLabel(tau) + "," + cap, UNIT, cap, none, tau));
            }
        }
        return out.toArray(new Plan[0]);
    }

    /** A threshold as a label shows it, in percent: 0, or signed with one decimal. */
    static String tauLabel(double tau) {
        return tau == 0 ? "0" : String.format(java.util.Locale.ROOT, "%+.1f", 100 * tau);
    }

    /** The plan of this label among those given; refused if there is none. */
    static int planIndex(Plan[] plans, String label) {
        for (int i = 0; i < plans.length; i++) {
            if (plans[i].label.equals(label)) {
                return i;
            }
        }
        throw new IllegalArgumentException("no plan " + label);
    }

    // The figures kept for each plan, at wideAt(plan, m) of Entry.wide: each over the rounds watched,
    // the rounds dealt whether played or not, a round sat out betting 0.
    /** The same schedule's EV per round watched. */
    static final int W_SAME = 0;
    /** The same schedule's average bet per round watched: the same for every count. */
    static final int W_SAME_BET = 1;
    /** The share of rounds the same schedule plays: the same for every count. */
    static final int W_SAME_SHARE = 2;
    /** The mean square of the bets the same schedule places, per round watched (PLAN_COMPUTE.md 3.2). */
    static final int W_SAME_BET2 = 3;
    static final int W_OWN = 4;
    static final int W_OWN_BET = 5;
    static final int W_OWN_SHARE = 6;
    static final int W_OWN_BET2 = 7;
    static final int WIDE_METRICS = 8;
    static final String[] WIDE_NAMES = {"same EV", "same average bet", "same share played", "same mean square bet",
            "own EV", "own average bet", "own share played", "own mean square bet"};

    static int wideAt(int plan, int m) {
        return plan * WIDE_METRICS + m;
    }

    /**
     * The figures kept on every resample (Entry.wideBoot), as wideAt indices: for each plan in
     * order, the same schedule's EV where it has one, the own rule's EV and average bet, and for a
     * plan that sits out the own rule's share played: 81 for WIDE_PLANS. The same schedule's average
     * bet and share are the same for every count on every resample, so no difference needs them.
     */
    static int[] wideRows(Plan[] plans) {
        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < plans.length; i++) {
            if (plans[i].sameSchedule()) {
                rows.add(wideAt(i, W_SAME));
            }
            rows.add(wideAt(i, W_OWN));
            rows.add(wideAt(i, W_OWN_BET));
            if (plans[i].wonging()) {
                rows.add(wideAt(i, W_OWN_SHARE));
            }
        }
        return rows.stream().mapToInt(Integer::intValue).toArray();
    }

    // The columns of each plan in a row of Entry.wideKeys, at plan x KEY_COLS + c.
    /** The same schedule's expected bet at the key: the bet when played times the share played. */
    static final int K_SAME_BET = 0;
    /** The share of the key's rounds the same schedule plays: 1, 0, or between on a block at the threshold. */
    static final int K_SAME_PLAY = 1;
    /** The own rule's bet at the key, while seated for a plan with an exit. */
    static final int K_OWN_BET = 2;
    static final int KEY_COLS = 3;

    /**
     * Where the lowest bet key of each state's shoe so far comes from, for the plans that leave
     * the table: the shoes dealt again (AllStates.roundStarts), or in a test any other source.
     */
    interface RoundStarts {
        /**
         * For each state s of the scorer's states, the lowest bet key c gives over the round starts
         * of s's shoe up to s, s included.
         */
        int[] minKey(CountKeys.Count c);
    }

    private RoundStarts minKeysOf;
    private final java.util.Map<CountKeys.Count, int[]> minKeys = new java.util.IdentityHashMap<>();

    /** roundStarts' minKey for c, worked out once per count and kept. */
    synchronized int[] minKey(CountKeys.Count c) {
        if (roundStarts == null) {
            throw new IllegalStateException("a plan leaves the table, and no round starts were given to find where "
                    + "(SystemScorer.roundStarts)");
        }
        if (minKeysOf != roundStarts) {
            minKeys.clear();
            minKeysOf = roundStarts;
        }
        int[] k = minKeys.get(c);
        if (k == null) {
            k = roundStarts.minKey(c);
            if (k.length != st.size) {
                throw new IllegalStateException("a lowest key for " + k.length + " states, not " + st.size);
            }
            minKeys.put(c, k);
        }
        return k;
    }

    /** minKey for c at each state of a penetration, by local index; null when no plan in wide leaves. */
    int[] minKey(CountKeys.Count c, Pen pen) {
        if (wide == null || c == null || !anyExit(wide)) {
            return null;
        }
        int[] all = minKey(c);
        int[] out = new int[pen.states.length];
        for (int j = 0; j < out.length; j++) {
            out[j] = all[pen.states[j]];
        }
        return out;
    }

    /**
     * Check 1 of PLAN_COMPUTE.md 3.5 on one scored entry, in words, nothing when it holds: plan P8
     * gives the 1-8 same schedule and own ramp the entry already holds, with their average bets,
     * to the bit, at every penetration scored, on the estimate and on every resample kept. A run
     * scoring the wide plans calls it on every item before keeping it, so a P8 that strays from the
     * existing figures stops the run rather than reaching a report.
     */
    static List<String> p8Check(Entry e) {
        List<String> bad = new ArrayList<>();
        if (e.widePlans == null) {
            return bad;
        }
        int p8 = planIndex(e.widePlans, "P8");
        int[][] pairs = {{W_SAME, same(SPREADS)}, {W_SAME_BET, sameBet(SPREADS)}, {W_OWN, own(SPREADS)},
                {W_OWN_BET, ownBet(SPREADS)}};
        int[] rows = wideRows(e.widePlans);
        for (int p = 0; p < CUTS.length; p++) {
            if (e.estimate[p] == null || e.wide[p] == null) {
                continue;
            }
            for (int[] pair : pairs) {
                int at = wideAt(p8, pair[0]);
                if (Double.doubleToLongBits(e.wide[p][at]) != Double.doubleToLongBits(e.estimate[p][pair[1]])) {
                    bad.add(e.name + " at " + PENETRATIONS[p] + ": P8's " + WIDE_NAMES[pair[0]] + " " + e.wide[p][at]
                            + ", the 1-8 figure " + e.estimate[p][pair[1]]);
                }
                for (int k = 0; k < rows.length; k++) {
                    if (rows[k] != at || e.boot[p] == null) {
                        continue;
                    }
                    for (int r = 0; r < e.boot[p][pair[1]].length; r++) {
                        if (Double.doubleToLongBits(e.wideBoot[p][k][r]) != Double.doubleToLongBits(e.boot[p][pair[1]][r])) {
                            bad.add(e.name + " at " + PENETRATIONS[p] + ", resample " + r + ": P8's " + WIDE_NAMES[pair[0]]
                                    + " " + e.wideBoot[p][k][r] + ", the 1-8 figure " + e.boot[p][pair[1]][r]);
                            break;
                        }
                    }
                }
            }
        }
        return bad;
    }

    static boolean anyExit(Plan[] plans) {
        for (Plan p : plans) {
            if (!p.sameSchedule()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Each plan's reference bets, g on each state's chart advantage: [state][plan], the states
     * indexed as chart is, a state's plans side by side since planIntegral reads them so. They
     * depend on nothing a resample changes, so a penetration works them out once (Pen.planBets).
     */
    static double[][] referenceBets(Plan[] plans, double[] chart) {
        double[][] out = new double[chart.length][plans.length];
        for (int j = 0; j < chart.length; j++) {
            for (int i = 0; i < plans.length; i++) {
                out[j][i] = plans[i].bet(chart[j]);
            }
        }
        return out;
    }

    /**
     * scheduleIntegral for plans: at every block boundary, the integral of each plan's reference
     * bets g and of 1[g > 0] over the quantiles of byChart's states, [plan][0 for g, 1 for
     * 1[g > 0]][boundary], ref[j][plan] being g at state j. It walks the states as
     * scheduleIntegral does, with the same arithmetic, so a plan whose g is spread s's clamp gives
     * spread s's integral to the bit. A block's bets follow from its two integrals (Wide.add).
     */
    static double[][][] planIntegral(int[] byChart, double[] w, double[][] ref, int np, double[] blockWeight,
                                     int blocks) {
        double[] boundary = new double[blocks + 1];
        for (int b = 0; b < blocks; b++) {
            boundary[b + 1] = boundary[b] + blockWeight[b];
        }
        double[][][] integral = new double[np][2][blocks + 1];
        double cw = 0;
        double[] cg = new double[np];
        double[] cz = new double[np];
        int next = 1;
        for (int j : byChart) {
            double wj = w[j];
            if (wj == 0) {
                continue;
            }
            double[] gj = ref[j];
            while (next < blocks && boundary[next] <= cw + wj) {
                double part = boundary[next] - cw;
                for (int i = 0; i < np; i++) {
                    double g = gj[i];
                    integral[i][0][next] = cg[i] + part * g;
                    integral[i][1][next] = cz[i] + (g > 0 ? part : 0);
                }
                next++;
            }
            cw += wj;
            for (int i = 0; i < np; i++) {
                double g = gj[i];
                cg[i] += wj * g;
                cz[i] += g > 0 ? wj : 0;
            }
        }
        for (; next <= blocks; next++) {
            for (int i = 0; i < np; i++) {
                integral[i][0][next] = cg[i];
                integral[i][1][next] = cz[i];
            }
        }
        return integral;
    }

    /**
     * The share of a block's rounds a plan plays, given the block's integral of 1[g > 0] and its
     * weight: 1 for a plan that plays every round, otherwise the integral over the weight, at most 1.
     */
    static double playShare(Plan plan, double played, double weight) {
        return plan.wonging() ? Math.min(1, played / weight) : 1;
    }

    /**
     * The smallest bet key whose fitted advantage is at least tau, the advantage being
     * non-decreasing in the key; Integer.MAX_VALUE when no key's is. A round start is at or above
     * tau exactly when its key is at least this: the isotonic fit makes the advantage a step
     * function of the key, and a key between two fitted ones takes the lower's.
     */
    static int keyFrom(BetKeys bk, double[] adv, double tau) {
        for (int k = 0; k < adv.length; k++) {
            if (adv[k] >= tau) {
                return bk.value[k];
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * One entry's wide figures, added up as bets and ownValueBets go over the folds: what each plan
     * makes of the round values and fitted advantages the existing figures are worked from.
     *
     * The bets are those of PLAN_COMPUTE.md 3.2's one convention, the one the simulator plays:
     * - same schedule, P and S plans: every key of a block bets the block's average of the
     *   reference bets, as at spreads 1 to 8; its mean square bet is that average squared, the
     *   square of a bet placed, not the mean of g squared, which no player bets;
     * - same schedule, B plans: the same, but for the one block that straddles the entry threshold,
     *   whose average would be a bet below a unit, not a legal bet. Each of its rounds is played
     *   with probability pi = (integral of 1[g > 0]) / weight, at (integral of g) / (integral of
     *   1[g > 0]), at least a unit, and sat out otherwise. Its EV is the average's; its mean square
     *   bet is pi times that bet squared;
     * - own rule: each key bets g of its fitted advantage, whole; a plan with an exit bets it only
     *   while seated, that is while the lowest key of the shoe so far is at least the key where the
     *   fitted advantage reaches the exit (keyFrom).
     * The own rule has no meaning for a row bet on its own value: its figures stay NaN, as do the
     * same schedule's for a plan with an exit.
     */
    static final class Wide {
        final Plan[] plans;
        /** [state][plan]: each plan's reference bet on each state's chart advantage. */
        final double[][] ref;
        /** Per state, the lowest bet key of its shoe so far, itself included; null when no plan leaves. */
        final int[] minKey;
        /** At wideAt(plan, m): each figure's sum over the test states, weighted 1/q. */
        final double[] sums;
        double weight;
        /** Whether the own rule was scored, on a count's keys. */
        boolean own;
        /** Where add puts one fold's figures by key, [key][plan x KEY_COLS + c], when not null. */
        double[][] detail;

        Wide(Plan[] plans, double[][] ref, int[] minKey) {
            if (ref.length > 0 && ref[0].length != plans.length) {
                throw new IllegalArgumentException("reference bets for " + ref[0].length + " plans, not " + plans.length);
            }
            this.plans = plans;
            this.ref = ref;
            this.minKey = minKey;
            this.sums = new double[plans.length * WIDE_METRICS];
        }

        /** An accumulator whose reference bets are worked out from the chart advantages given. */
        static Wide of(Plan[] plans, double[] chart, int[] minKey) {
            return new Wide(plans, referenceBets(plans, chart), minKey);
        }

        /** The figures, each sum over the weight; NaN where a plan has no such figure here. */
        double[] figures() {
            double[] out = new double[sums.length];
            for (int m = 0; m < out.length; m++) {
                out[m] = weight == 0 ? Double.NaN : sums[m] / weight;
            }
            for (int i = 0; i < plans.length; i++) {
                for (int m = 0; m < WIDE_METRICS; m++) {
                    boolean same = m < W_OWN;
                    if (same ? !plans[i].sameSchedule() : !own) {
                        out[wideAt(i, m)] = Double.NaN;
                    }
                }
            }
            return out;
        }

        /**
         * One fold's figures for a count's keys, from what bets worked out: the fitted advantage
         * by key, each key's test weight and weighted sum of round values, the blocks of keys with
         * equal advantage and their weights, the fold's test states and its weight.
         */
        void add(BetKeys bk, double[] adv, double[] vw, double[] vs, int[] blockOf, double[] blockWeight, int blocks,
                 int[] test, int[] testByChart, double[] w, double[] rv, double foldWeight) {
            own = true;
            weight += foldWeight;
            int keys = vw.length;
            double[][][] integral = planIntegral(testByChart, w, ref, plans.length, blockWeight, blocks);
            for (int i = 0; i < plans.length; i++) {
                Plan plan = plans[i];
                int at = i * WIDE_METRICS;
                int col = i * KEY_COLS;
                if (plan.sameSchedule()) {
                    double[] g = integral[i][0];
                    double[] z = integral[i][1];
                    for (int k = 0; k < keys; k++) {
                        if (vw[k] == 0) {
                            continue;
                        }
                        int b = blockOf[k];
                        double bet = (g[b + 1] - g[b]) / blockWeight[b];
                        double play = playShare(plan, z[b + 1] - z[b], blockWeight[b]);
                        double placed = !plan.wonging() ? bet : play == 0 ? 0 : (g[b + 1] - g[b]) / (z[b + 1] - z[b]);
                        sums[at + W_SAME] += bet * vs[k];
                        sums[at + W_SAME_BET] += bet * vw[k];
                        sums[at + W_SAME_SHARE] += play * vw[k];
                        sums[at + W_SAME_BET2] += play * vw[k] * placed * placed;
                        if (detail != null) {
                            detail[k][col + K_SAME_BET] = bet;
                            detail[k][col + K_SAME_PLAY] = play;
                        }
                    }
                } else if (detail != null) {
                    for (int k = 0; k < keys; k++) {
                        detail[k][col + K_SAME_BET] = Double.NaN;
                        detail[k][col + K_SAME_PLAY] = Double.NaN;
                    }
                }
                if (detail != null) {
                    for (int k = 0; k < keys; k++) {
                        detail[k][col + K_OWN_BET] = plan.bet(adv[k]);
                    }
                }
                if (plan.sameSchedule()) {
                    for (int k = 0; k < keys; k++) {
                        if (vw[k] == 0) {
                            continue;
                        }
                        double bet = plan.bet(adv[k]);
                        sums[at + W_OWN] += bet * vs[k];
                        sums[at + W_OWN_BET] += bet * vw[k];
                        sums[at + W_OWN_SHARE] += bet > 0 ? vw[k] : 0;
                        sums[at + W_OWN_BET2] += bet * bet * vw[k];
                    }
                    continue;
                }
                if (minKey == null) {
                    throw new IllegalStateException(plan.label + " leaves the table, and no state's lowest key so far "
                            + "was given");
                }
                int from = keyFrom(bk, adv, plan.exit);
                for (int j : test) {
                    if (w[j] == 0 || minKey[j] < from) {
                        continue;
                    }
                    double bet = plan.bet(adv[bk.index[j]]);
                    sums[at + W_OWN] += bet * w[j] * rv[j];
                    sums[at + W_OWN_BET] += bet * w[j];
                    sums[at + W_OWN_SHARE] += w[j];
                    sums[at + W_OWN_BET2] += bet * bet * w[j];
                }
            }
        }

        /**
         * One fold's same-schedule figures for a row bet on each state's own value
         * (ownValueBets): its blocks of states with equal value, their weights and weighted sums of
         * round values, handed the fold's reference bets in that order.
         */
        void addBlocks(double[] blockWeight, double[] blockSum, int blocks, int[] byChart, double[] w) {
            double[][][] integral = planIntegral(byChart, w, ref, plans.length, blockWeight, blocks);
            for (int b = 0; b < blocks; b++) {
                weight += blockWeight[b];
            }
            for (int i = 0; i < plans.length; i++) {
                Plan plan = plans[i];
                if (!plan.sameSchedule()) {
                    continue;
                }
                int at = i * WIDE_METRICS;
                double[] g = integral[i][0];
                double[] z = integral[i][1];
                for (int b = 0; b < blocks; b++) {
                    double bet = (g[b + 1] - g[b]) / blockWeight[b];
                    double play = playShare(plan, z[b + 1] - z[b], blockWeight[b]);
                    double placed = !plan.wonging() ? bet : play == 0 ? 0 : (g[b + 1] - g[b]) / (z[b + 1] - z[b]);
                    sums[at + W_SAME] += bet * blockSum[b];
                    sums[at + W_SAME_BET] += bet * blockWeight[b];
                    sums[at + W_SAME_SHARE] += play * blockWeight[b];
                    sums[at + W_SAME_BET2] += play * blockWeight[b] * placed * placed;
                }
            }
        }
    }

    /**
     * One fold's per-key wide figures pooled over the folds by test weight, as keyTable pools the
     * existing ones, row for row: [key row][plan x KEY_COLS + c], the keys some test state holds.
     */
    private static double[][] wideKeyTable(BetKeys bk, double[][][] det, double[][][] wideDet) {
        List<double[]> rows = new ArrayList<>();
        for (int k = 0; k < bk.value.length; k++) {
            double vw = det[0][k][0] + det[1][k][0];
            if (vw == 0) {
                continue;
            }
            double[] row = new double[wideDet[0][k].length];
            for (int c = 0; c < row.length; c++) {
                row[c] = (det[0][k][0] * wideDet[0][k][c] + det[1][k][0] * wideDet[1][k][c]) / vw;
            }
            rows.add(row);
        }
        return rows.toArray(new double[0][]);
    }

    // ----------------------------------------------------------------- the scoring

    /** One row of the report: a count scored one way, at every penetration. */
    static final class Entry implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

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
        /** Whether it plays a full-index fit, count-based on every decision of the dealt hand. */
        final boolean fullPlay;

        // Added after c06c061 wrote its results; read from those files they are false and null.

        /**
         * Whether it bets on each state's own round value under its play and insurance rather
         * than on its bet key (COUNT_DESIGN.md, section 2): its same-schedule figures are the most
         * any ordering of the schedule makes of that play; it has no own ramp.
         */
        final boolean ownValue;
        /**
         * [pen]: the 1-8 same-schedule EV with play, insurance and the bets' advantage fitted on the
         * learning curve's half of each training fold and scored on the whole test fold; NaN for a
         * row betting only or on its own value. Null in a Result c06c061 wrote.
         */
        double[] halfFitSame;
        /** [pen][resample]: the same, as Result.halfFitBoot is made. */
        double[][] halfFitSameBoot;

        // Added after c06c061 and the ceiling run; null in Results written before, and in any scored
        // without the wide plans (SystemScorer.wide). Adding fields under the same serialVersionUID
        // reads an older file as before, as ownValue and halfFitSame showed (StoredResultsTest).

        /** The wide plans the figures below are for (PLAN_COMPUTE.md section 3), in their order. */
        Plan[] widePlans;
        /** [pen][wideAt(plan, m)]: the pooled estimate of each plan's figures, NaN where a plan has none. */
        double[][] wide;
        /** [pen][row][resample]: the figures wideRows(widePlans) names, on every resample. */
        double[][][] wideBoot;
        /**
         * [pen][key row][plan x KEY_COLS + c]: each plan's bets by key, row for row with keys[pen],
         * pooled over the folds by test weight as keys is; null for a row bet on its own value.
         */
        double[][][] wideKeys;

        Entry(String name, CountKeys.Count count, boolean bettingOnly) {
            this(name, count, bettingOnly, false);
        }

        Entry(String name, CountKeys.Count count, boolean bettingOnly, boolean fullPlay) {
            this(name, count, bettingOnly, fullPlay, false);
        }

        Entry(String name, CountKeys.Count count, boolean bettingOnly, boolean fullPlay, boolean ownValue) {
            this.name = name;
            this.count = count;
            this.bettingOnly = bettingOnly;
            this.fullPlay = fullPlay;
            this.ownValue = ownValue;
            if (ownValue && bettingOnly) {
                throw new IllegalArgumentException(name + ": a row on its own value plays its own fit");
            }
        }

        /** Makes room for the wide figures of the plans given, at every penetration, once. */
        void startWide(Plan[] plans) {
            if (widePlans == null) {
                widePlans = plans;
                wide = new double[CUTS.length][];
                wideBoot = new double[CUTS.length][][];
                wideKeys = new double[CUTS.length][][];
            } else if (widePlans != plans) {
                throw new IllegalStateException(name + " already holds wide figures for other plans");
            }
        }

        /** The wide figure m of the plan labelled so at penetration p, NaN when none. */
        double wide(int p, String plan, int m) {
            return wide == null || wide[p] == null ? Double.NaN : wide[p][wideAt(planIndex(widePlans, plan), m)];
        }

        /** The resamples of the wide figure m of a plan at penetration p, which wideRows must name. */
        double[] wideBoot(int p, String plan, int m) {
            int at = wideAt(planIndex(widePlans, plan), m);
            int[] rows = wideRows(widePlans);
            for (int k = 0; k < rows.length; k++) {
                if (rows[k] == at) {
                    return wideBoot[p][k];
                }
            }
            throw new IllegalArgumentException(WIDE_NAMES[m] + " of " + plan + " is not kept on the resamples");
        }
    }

    /**
     * How much a count's full-play intervals are widened (FULL_INDEX_PLAYS.md section 4): one
     * ratio for its full-play difference from another count, one for its gain from full play,
     * each the pilot's refitted SE over its held SE. measured says whether the pilot measured
     * this count; a count it did not measure takes the conservative default the caller states,
     * the largest ratio measured of the kind, so that no count's interval is left narrow by
     * default.
     */
    static final class Widening implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        final double difference;
        final double gain;
        final boolean measured;

        Widening(double difference, double gain, boolean measured) {
            if (!(difference >= 1) || !(gain >= 1)) {
                throw new IllegalArgumentException("a widening ratio below 1 would narrow an interval: " + difference
                        + ", " + gain);
            }
            this.difference = difference;
            this.gain = gain;
            this.measured = measured;
        }

        /**
         * A count's widening from the map given: its own, or when it has none the default given,
         * marked not measured; with no default, a count missing from the map is refused. No map
         * leaves the count unwidened, its Result.widen null.
         */
        static Widening of(java.util.Map<CountKeys.Count, Widening> measured, Widening missing, CountKeys.Count c) {
            if (measured == null) {
                return null;
            }
            Widening w = measured.get(c);
            if (w != null) {
                return w;
            }
            if (missing == null) {
                throw new IllegalArgumentException(c.name + " has no measured widening and no default was given");
            }
            return new Widening(missing.difference, missing.gain, false);
        }
    }

    /** Everything scored for one count: its entries, its decision resolution and learning curve. */
    static final class Result implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        final CountKeys.Count count;
        final List<Entry> entries = new ArrayList<>();
        final Resolution[] resolution = new Resolution[CUTS.length];
        /** [pen]: pooled flat EV with play and insurance fitted on half of each training fold. */
        final double[] halfFitFlat = new double[CUTS.length];
        /** [pen][resample]: the same, fitted on the half's drawn shoes and scored on the resampled test fold. */
        final double[][] halfFitBoot = new double[CUTS.length][];
        /** [pen][fold]: the play groups of each fold's fit. */
        final Groups[][] groups = new Groups[CUTS.length][2];
        /**
         * Full-index play (FULL_INDEX_PLAYS.md section 4), at the penetrations it was fitted at:
         * the count and its side-count variant playing their full-index fit, scored on the same
         * resamples as the entries above, so each one's gain over its first-decision-only entry is
         * paired; the decision resolution with the later cells; the learning curve with later plays.
         */
        final List<Entry> fullEntries = new ArrayList<>();
        final Resolution[] fullResolution = new Resolution[CUTS.length];
        final double[] fullHalfFitFlat = new double[CUTS.length];
        final double[][] fullHalfFitBoot = new double[CUTS.length][];
        /** [pen][fold]: each fold's first decisions under full play, fitted with its own full-index values. */
        final Fit[][] fullFirst = new Fit[CUTS.length][2];
        /** [pen][fold]: each fold's full-index fit and half fit, as FullIndexFit.Summary gives them. */
        final FullIndexFit.Summary[][] fullFit = new FullIndexFit.Summary[CUTS.length][2];
        final FullIndexFit.Summary[][] fullHalfFit = new FullIndexFit.Summary[CUTS.length][2];
        /**
         * [pen]: the in-sample check over both folds' half fits (FullIndexFit.Strategy.inSampleCheck):
         * cells compared, cells the other half plays the other way, and the same two among the
         * cells settled; null where there are no half fits.
         */
        final int[][] fullInSample = new int[CUTS.length][];
        /**
         * What the full-play intervals are widened by: the bootstrap holds the later plays as
         * fitted, and the pilot measures how much that narrows an SE (FULL_INDEX_PLAYS.md section
         * 4). Null until set, and a report must not give a full-play interval while it is null.
         */
        Widening widen;
        double seconds;

        Result(CountKeys.Count count) {
            this.count = count;
        }
    }

    /**
     * The difference a - b of two entries in metric m at penetration p, with its interval taken
     * resample by resample, which pairs the two since every entry is scored on the same
     * resamples. With widen other than 1 the differences are spread that many times as far from
     * their mean first (Bootstrap.widen): the hook by which a full-play difference's interval is
     * widened by the ratio the pilot measures per count, the bootstrap holding the later plays
     * as fitted (FULL_INDEX_PLAYS.md section 4).
     */
    static Bootstrap.Interval difference(Entry a, Entry b, int p, int m, double widen) {
        double[] da = a.boot[p][m];
        double[] db = b.boot[p][m];
        double[] d = new double[da.length];
        for (int r = 0; r < d.length; r++) {
            d[r] = da[r] - db[r];
        }
        if (widen != 1) {
            d = Bootstrap.widen(d, widen);
        }
        return Bootstrap.interval(a.estimate[p][m] - b.estimate[p][m], d);
    }

    /**
     * The gain from full play: a count's full-play entry less its first-decision-only entry,
     * scored in the same run on the same resamples, so the interval is paired.
     */
    static Bootstrap.Interval gain(Entry fullPlay, Entry firstOnly, int p, int m, double widen) {
        if (!fullPlay.fullPlay || firstOnly.fullPlay || firstOnly.bettingOnly || fullPlay.count != firstOnly.count) {
            throw new IllegalArgumentException(fullPlay.name + " is not the full play of " + firstOnly.name);
        }
        return difference(fullPlay, firstOnly, p, m, widen);
    }

    /**
     * Scores a count, as itself and betting only, and, given its ace side-count variant, that
     * too as itself and betting only, at every penetration, with the bootstrap.
     */
    Result score(CountKeys.Count count, CountKeys.Count sideCounted) {
        return score(count, sideCounted, null);
    }

    /**
     * The same, and given the count's full-index fits, the count and its variant playing them
     * too, at every penetration fitted, in the same run on the same resamples (Result.fullEntries).
     *
     * Each fold's full play is fitted as the first-decision play is, on the other fold's states,
     * with Hit and Split valued under the fold's own full-index fit: its test states take the
     * values of the play they are scored with, fitted on the other fold, and its training states
     * the same play's values, so no state is scored with later plays fitted on its own fold and no
     * fold's first decisions are fitted on another fold's later plays. The bootstrap refits the
     * first decisions, insurance and bets on every resample, as it does for first-decision-only
     * play, and holds the later plays and the values they give as fitted (FULL_INDEX_PLAYS.md
     * section 4); Result.widen carries what that is measured to leave out.
     */
    Result score(CountKeys.Count count, CountKeys.Count sideCounted, FullIndexFit.Fits fits) {
        return score(count, sideCounted, fits, null, 1, Part.WHOLE);
    }

    /**
     * What one call to score works out at each penetration it scores: all of it; everything
     * but the full-index half fits, which needs only the fold fits' values; or the full-index
     * half fits alone, which needs only the half fits' values. The full run holds one of the two
     * sets of values at a time and puts the parts together (merge).
     */
    enum Part {
        WHOLE, FOLDS, HALVES
    }

    /**
     * The same at the penetrations given alone (every one when null), the others left unscored
     * (null), and each penetration's bootstrap resamples worked out on threads threads. A
     * resample's figures depend on its own weights alone, and each is written to its own slot,
     * so they are the same on any number of threads. The full run scores a count a penetration
     * at a time, to hold one penetration's values at once, and puts the parts together (merge).
     */
    Result score(CountKeys.Count count, CountKeys.Count sideCounted, FullIndexFit.Fits fits, int[] only, int threads) {
        return score(count, sideCounted, fits, only, threads, Part.WHOLE);
    }

    /** The same, working out only the part given (Part). */
    Result score(CountKeys.Count count, CountKeys.Count sideCounted, FullIndexFit.Fits fits, int[] only, int threads,
                 Part part) {
        if (part == Part.HALVES && fits == null) {
            throw new IllegalArgumentException("the full-index half fits need the fits");
        }
        if (fits != null && fits.count != count) {
            throw new IllegalArgumentException("the fits of " + fits.count + " cannot score " + count);
        }
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
            if (fits != null) {
                result.fullEntries.add(new Entry(c.name + ", full play", c, false, true));
            }
        }
        if (ownValueRows) {
            // Last, so every other entry keeps its place; fullEntries[e] still pairs with entries[2e].
            result.entries.add(new Entry(count.name + ", bet on its own value", count, false, false, true));
            if (fits != null) {
                result.fullEntries.add(new Entry(count.name + ", full play, bet on its own value", count, false, true,
                        true));
            }
        }
        for (Entry e : result.entries) {
            e.halfFitSame = new double[CUTS.length];
            Arrays.fill(e.halfFitSame, Double.NaN);
            e.halfFitSameBoot = new double[CUTS.length][];
        }
        for (int p = 0; p < CUTS.length; p++) {
            if (only != null && !contains(only, p)) {
                continue;
            }
            if (part == Part.HALVES) {
                scoreHalves(result, count, fits, p, threads);
            } else {
                scorePen(result, count, fits, p, threads, part == Part.WHOLE);
            }
        }
        result.seconds = (System.nanoTime() - start) / 1e9;
        return result;
    }

    private static boolean contains(int[] a, int x) {
        for (int y : a) {
            if (y == x) {
                return true;
            }
        }
        return false;
    }

    /** Penetration p of score, the full-index half fits included when halves. */
    private void scorePen(Result result, CountKeys.Count count, FullIndexFit.Fits fits, int p, int threads,
                          boolean halves) {
        Pen pen = pens[p];
        Parts parts = partsFor(count, fits, p);
        InsuranceParts ip = insuranceParts(parts, pen);
        BetKeys[] bk = new BetKeys[result.entries.size()];
        for (int e = 0; e < bk.length; e++) {
            bk[e] = e % 2 == 1 ? bk[e - 1] : betKeys(result.entries.get(e).count, pen);
        }
        boolean full = fits != null && fits.has(p);
        BetKeys[] bkFull = new BetKeys[result.fullEntries.size()];
        for (int e = 0; e < bkFull.length; e++) {
            bkFull[e] = bk[2 * e];
        }
        FoldPlay[] fullPlays = null;
        FoldPlay[] fullHalf = null;
        PlayValues[] halfValues = null;
        int[][] mk = minKeys(result.entries, pen);
        int[][] mkFull = minKeys(result.fullEntries, pen);
        if (full) {
            PlayValues[] values = fits.values(p);
            fullPlays = new FoldPlay[2];
            for (int f = 0; f < 2; f++) {
                fullPlays[f] = foldPlay(parts, pen, ip, pen.train[f], pen.weight, null, null, null, null, values[f]);
                result.fullFirst[p][f] = fullPlays[f].fit;
                result.fullFit[p][f] = fits.fold[p][f].summary();
            }
            result.fullResolution[p] = resolution(parts, pen, ip, fullPlays, fits, p);
            halfValues = halves ? fits.halfValues(p) : null;
            if (halfValues != null) {
                fullHalf = halfPlays(parts, pen, ip, pen.weight, null, null, null, halfValues, null);
                result.fullHalfFitFlat[p] = halfFlat(pen, ip, fullHalf, pen.weight);
                result.fullHalfFitBoot[p] = new double[boot.resamples];
                int[] check = new int[4];
                for (int f = 0; f < 2; f++) {
                    result.fullHalfFit[p][f] = fits.half[p][f].summary();
                    int[] c = result.fullHalfFit[p][f].inSample;
                    for (int k = 0; k < 4; k++) {
                        check[k] += c[k];
                    }
                }
                result.fullInSample[p] = check;
            }
            double[][] wideFull = startWide(result.fullEntries);
            double[][] sums = scoreOnce(pen, ip, fullPlays, bkFull, result.fullEntries, pen.weight, p, true, mkFull,
                    wideFull);
            for (int e = 0; e < sums.length; e++) {
                result.fullEntries.get(e).estimate[p] = sums[e];
                result.fullEntries.get(e).boot[p] = new double[METRICS][boot.resamples];
                putWide(result.fullEntries.get(e), p, wideFull, e);
            }
        }
        FoldPlay[] plays = new FoldPlay[2];
        for (int f = 0; f < 2; f++) {
            plays[f] = foldPlay(parts, pen, ip, pen.train[f], pen.weight, null, null);
            result.groups[p][f] = plays[f].groups;
        }
        result.resolution[p] = resolution(parts, pen, ip, plays);
        // The half fits value every state, so the bets' advantage can be fitted on the half; the
        // flat EV reads the test states alone, whose values are those halfFit gives.
        FoldPlay[] half = halfPlays(parts, pen, ip, pen.weight, null, null, null, null, null);
        result.halfFitFlat[p] = halfFlat(pen, ip, half, pen.weight);
        result.halfFitBoot[p] = new double[boot.resamples];
        for (Entry e : result.entries) {
            e.halfFitSameBoot[p] = new double[boot.resamples];
        }
        halfSchedule(pen, ip, half, bk, result.entries, pen.weight, p, -1);

        double[][] wideOut = startWide(result.entries);
        double[][] sums = scoreOnce(pen, ip, plays, bk, result.entries, pen.weight, p, true, mk, wideOut);
        for (int e = 0; e < sums.length; e++) {
            result.entries.get(e).estimate[p] = sums[e];
            result.entries.get(e).boot[p] = new double[METRICS][boot.resamples];
            putWide(result.entries.get(e), p, wideOut, e);
        }
        ByKey byKey = boot.resamples > 0 ? byKey(parts, pen) : null;
        FoldPlay[] fp = fullPlays;
        FoldPlay[] fh = fullHalf;
        PlayValues[] hv = halfValues;
        onThreads(threads, (first, step) -> resamples(result, p, pen, parts, ip, bk, bkFull, plays, fp, fh, hv, byKey,
                full, mk, mkFull, first, step));
    }

    /**
     * Each entry's lowest bet key of its shoe so far at a penetration (minKey(c, pen)), none for a
     * row bet on its own value, which has no own rule; null when wide is off.
     */
    private int[][] minKeys(List<Entry> entries, Pen pen) {
        if (wide == null) {
            return null;
        }
        int[][] out = new int[entries.size()][];
        for (int e = 0; e < out.length; e++) {
            Entry x = entries.get(e);
            out[e] = x.ownValue ? null : minKey(x.count, pen);
        }
        return out;
    }

    /** With wide on, room for each entry's wide figures (Entry.startWide), and the array scoreOnce fills; null when off. */
    private double[][] startWide(List<Entry> entries) {
        if (wide == null) {
            return null;
        }
        for (Entry e : entries) {
            e.startWide(wide);
        }
        return new double[entries.size()][];
    }

    /** Entry e's wide estimate at p, from what scoreOnce filled (nothing when off), and room for its resamples. */
    private void putWide(Entry x, int p, double[][] wideOut, int e) {
        if (wideOut == null) {
            return;
        }
        x.wide[p] = wideOut[e];
        x.wideBoot[p] = new double[wideRows(wide).length][boot.resamples];
    }

    /** One resample's wide figures, from what scoreOnce filled, into each entry's wideBoot at the rows kept. */
    private static void wideResample(List<Entry> entries, double[][] wideOut, int[] rows, int p, int r) {
        for (int e = 0; e < wideOut.length; e++) {
            double[][] to = entries.get(e).wideBoot[p];
            for (int k = 0; k < rows.length; k++) {
                to[k][r] = wideOut[e][rows[k]];
            }
        }
    }

    /**
     * The full-index half fits of penetration p alone, as scorePen works them out: their flat EV
     * on the estimate's weights and on every resample, their summaries and the in-sample check.
     */
    private void scoreHalves(Result result, CountKeys.Count count, FullIndexFit.Fits fits, int p, int threads) {
        PlayValues[] halfValues = fits.halfValues(p);
        if (halfValues == null) {
            return;
        }
        Pen pen = pens[p];
        Parts parts = partsFor(count, fits, p);
        InsuranceParts ip = insuranceParts(parts, pen);
        FoldPlay[] fullHalf = halfPlays(parts, pen, ip, pen.weight, null, null, null, halfValues, null);
        result.fullHalfFitFlat[p] = halfFlat(pen, ip, fullHalf, pen.weight);
        result.fullHalfFitBoot[p] = new double[boot.resamples];
        int[] check = new int[4];
        for (int f = 0; f < 2; f++) {
            result.fullHalfFit[p][f] = fits.half[p][f].summary();
            int[] c = result.fullHalfFit[p][f].inSample;
            for (int k = 0; k < 4; k++) {
                check[k] += c[k];
            }
        }
        result.fullInSample[p] = check;
        ByKey byKey = boot.resamples > 0 ? byKey(parts, pen) : null;
        onThreads(threads, (first, step) -> {
            double[] wm = new double[pen.states.length];
            double[] um = new double[pen.states.length];
            for (int r = first; r < boot.resamples; r += step) {
                for (int j = 0; j < wm.length; j++) {
                    um[j] = boot.mult(r, st.shoe[pen.states[j]]);
                    wm[j] = pen.weight[j] * um[j];
                }
                result.fullHalfFitBoot[p][r] = halfFit(parts, pen, ip, wm, um, byKey, fullHalf, halfValues, null);
            }
        });
    }

    /** The count's parts at penetration p: those its fits hold, which are the same, or made afresh. */
    private Parts partsFor(CountKeys.Count count, FullIndexFit.Fits fits, int p) {
        Parts held = fits == null ? null : fits.partsAt(p);
        return held != null ? held : parts(count, pens[p]);
    }

    /** Work split over resamples: a share, first, first + step, ... */
    private interface Share {
        void run(int first, int step);
    }

    /** Runs a share of the resamples on each of threads threads (one when the resamples are fewer), and waits. */
    private void onThreads(int threads, Share share) {
        int rt = Math.max(1, Math.min(threads, boot.resamples));
        if (rt == 1) {
            share.run(0, 1);
            return;
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(rt, r -> {
            Thread t = new Thread(r, "score-resamples");
            t.setDaemon(true);
            return t;
        });
        try {
            List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
            for (int k = 0; k < rt; k++) {
                int first = k;
                jobs.add(pool.submit(() -> {
                    share.run(first, rt);
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> j : jobs) {
                j.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("scoring interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) {
                throw (RuntimeException) c;
            }
            if (c instanceof Error) {
                throw (Error) c;
            }
            throw new IllegalStateException(c);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Resamples first, first + step, ... of penetration p, each with its own weights and refits. */
    private void resamples(Result result, int p, Pen pen, Parts parts, InsuranceParts ip, BetKeys[] bk, BetKeys[] bkFull,
                           FoldPlay[] plays, FoldPlay[] fullPlays, FoldPlay[] fullHalf, PlayValues[] halfValues,
                           ByKey byKey, boolean full, int[][] mk, int[][] mkFull, int first, int step) {
        int[] rows = wide == null ? null : wideRows(wide);
        double[] wm = new double[pen.states.length];
        double[] um = new double[pen.states.length];
        FoldPlay[] refit = new FoldPlay[2];
        for (int r = first; r < boot.resamples; r += step) {
            for (int j = 0; j < wm.length; j++) {
                um[j] = boot.mult(r, st.shoe[pen.states[j]]);
                wm[j] = pen.weight[j] * um[j];
            }
            for (int f = 0; f < 2; f++) {
                refit[f] = foldPlay(parts, pen, ip, drawn(pen.train[f], wm), wm, um, null, byKey, plays[f]);
            }
            double[][] wo = rows == null ? null : new double[result.entries.size()][];
            double[][] rs = scoreOnce(pen, ip, refit, bk, result.entries, wm, p, false, mk, wo, r);
            for (int e = 0; e < rs.length; e++) {
                for (int m = 0; m < METRICS; m++) {
                    result.entries.get(e).boot[p][m][r] = rs[e][m];
                }
            }
            if (wo != null) {
                wideResample(result.entries, wo, rows, p, r);
            }
            FoldPlay[] hp = halfPlays(parts, pen, ip, wm, um, byKey, plays, null, pen.test);
            result.halfFitBoot[p][r] = halfFlat(pen, ip, hp, wm);
            halfSchedule(pen, ip, hp, bk, result.entries, wm, p, r);
            if (full) {
                for (int f = 0; f < 2; f++) {
                    refit[f] = foldPlay(parts, pen, ip, drawn(pen.train[f], wm), wm, um, null, byKey, fullPlays[f]);
                }
                wo = rows == null ? null : new double[result.fullEntries.size()][];
                rs = scoreOnce(pen, ip, refit, bkFull, result.fullEntries, wm, p, false, mkFull, wo, r);
                for (int e = 0; e < rs.length; e++) {
                    for (int m = 0; m < METRICS; m++) {
                        result.fullEntries.get(e).boot[p][m][r] = rs[e][m];
                    }
                }
                if (wo != null) {
                    wideResample(result.fullEntries, wo, rows, p, r);
                }
                if (fullHalf != null) {
                    result.fullHalfFitBoot[p][r] = halfFit(parts, pen, ip, wm, um, byKey, fullHalf, halfValues, null);
                }
            }
        }
    }

    /**
     * One count's Result put together from Results that each scored some of its penetrations,
     * or one part of them (score with only and a Part): every penetration's figures taken from
     * the part that worked them out. The parts must be of the same count, with the same entries,
     * and nothing worked out twice. A count is known by its name, since a part read back from a
     * checkpoint holds a copy of it.
     */
    static Result merge(List<Result> parts) {
        Result out = parts.get(0);
        for (int k = 1; k < parts.size(); k++) {
            Result r = parts.get(k);
            if (!r.count.name.equals(out.count.name) || r.entries.size() != out.entries.size()
                    || r.fullEntries.size() != out.fullEntries.size()) {
                throw new IllegalArgumentException("parts of different counts or entries");
            }
            for (int p = 0; p < CUTS.length; p++) {
                if (r.entries.get(0).estimate[p] != null) {
                    if (out.entries.get(0).estimate[p] != null) {
                        throw new IllegalArgumentException("penetration " + p + " scored twice");
                    }
                    for (int e = 0; e < r.entries.size(); e++) {
                        copyPen(r.entries.get(e), out.entries.get(e), p);
                    }
                    for (int e = 0; e < r.fullEntries.size(); e++) {
                        copyPen(r.fullEntries.get(e), out.fullEntries.get(e), p);
                    }
                    out.resolution[p] = r.resolution[p];
                    out.halfFitFlat[p] = r.halfFitFlat[p];
                    out.halfFitBoot[p] = r.halfFitBoot[p];
                    out.groups[p] = r.groups[p];
                    out.fullResolution[p] = r.fullResolution[p];
                    out.fullFirst[p] = r.fullFirst[p];
                    out.fullFit[p] = r.fullFit[p];
                }
                if (r.fullHalfFit[p][0] != null) {
                    if (out.fullHalfFit[p][0] != null) {
                        throw new IllegalArgumentException("penetration " + p + "'s half fits scored twice");
                    }
                    out.fullHalfFitFlat[p] = r.fullHalfFitFlat[p];
                    out.fullHalfFitBoot[p] = r.fullHalfFitBoot[p];
                    out.fullHalfFit[p] = r.fullHalfFit[p];
                    out.fullInSample[p] = r.fullInSample[p];
                }
            }
            out.seconds += r.seconds;
        }
        return out;
    }

    /** The labels of the plans given, in order, to tell two copies of the same plans apart from other plans. */
    static List<String> labels(Plan[] plans) {
        List<String> out = new ArrayList<>();
        for (Plan x : plans) {
            out.add(x.label);
        }
        return out;
    }

    private static void copyPen(Entry from, Entry to, int p) {
        if (!from.name.equals(to.name)) {
            throw new IllegalArgumentException(from.name + " is not " + to.name);
        }
        to.estimate[p] = from.estimate[p];
        to.boot[p] = from.boot[p];
        to.shoeSums[p] = from.shoeSums[p];
        to.keys[p] = from.keys[p];
        if (from.wide != null && from.wide[p] != null) {
            if (to.widePlans == null) {
                to.startWide(from.widePlans);
            } else if (!labels(to.widePlans).equals(labels(from.widePlans))) {
                throw new IllegalArgumentException(to.name + " holds wide figures for other plans");
            }
            to.wide[p] = from.wide[p];
            to.wideBoot[p] = from.wideBoot[p];
            to.wideKeys[p] = from.wideKeys[p];
        }
        if (from.halfFitSame != null) {
            if (to.halfFitSame == null) {
                to.halfFitSame = new double[CUTS.length];
                Arrays.fill(to.halfFitSame, Double.NaN);
                to.halfFitSameBoot = new double[CUTS.length][];
            }
            to.halfFitSame[p] = from.halfFitSame[p];
            to.halfFitSameBoot[p] = from.halfFitSameBoot[p];
        }
    }

    /**
     * One count's own-play figures at penetration p under the weights given, each fold's play
     * fitted afresh on its training states of weight not zero, with Hit and Split valued under
     * values[f] for fold f's play (the stored values when values is null), insurance and bets
     * fitted in its groups: what one bootstrap resample of score gives the count's entry when the
     * values are the ones it holds. FullIndexPilot scores resamples with their own refitted
     * values this way. With wide on, the plans' figures follow the METRICS existing ones, figure
     * wideAt(plan, m) at METRICS + wideAt(plan, m).
     */
    double[] scoreWith(CountKeys.Count count, int p, PlayValues[] values, double[] w, double[] units) {
        Pen pen = pens[p];
        Parts parts = parts(count, pen);
        InsuranceParts ip = insuranceParts(parts, pen);
        FoldPlay[] plays = new FoldPlay[2];
        for (int f = 0; f < 2; f++) {
            plays[f] = foldPlay(parts, pen, ip, drawn(pen.train[f], w), w, units, null, null, null,
                    values == null ? null : values[f]);
        }
        List<Entry> one = new ArrayList<>();
        one.add(new Entry(count.name, count, false, values != null));
        if (wide == null) {
            return scoreOnce(pen, ip, plays, new BetKeys[]{betKeys(count, pen)}, one, w, p, false)[0];
        }
        double[][] wo = new double[1][];
        double[] m = scoreOnce(pen, ip, plays, new BetKeys[]{betKeys(count, pen)}, one, w, p, false,
                new int[][]{minKey(count, pen)}, wo)[0];
        double[] out = Arrays.copyOf(m, METRICS + wo[0].length);
        System.arraycopy(wo[0], 0, out, METRICS, wo[0].length);
        return out;
    }

    // ------------------------------------------------------------- the search's evaluation

    /** One count's play held at one penetration: each fold's play and the insurance parts it is valued with. */
    static final class Held {
        final int p;
        final Pen pen;
        final InsuranceParts ip;
        final FoldPlay[] plays;

        Held(int p, Pen pen, InsuranceParts ip, FoldPlay[] plays) {
            this.p = p;
            this.pen = pen;
            this.ip = ip;
            this.plays = plays;
        }
    }

    /**
     * Fits a count's play at penetration p as score does for its estimate: each fold's groups and
     * moves on the other fold, every state valued under it.
     */
    Held hold(CountKeys.Count count, int p) {
        Pen pen = pens[p];
        Parts parts = parts(count, pen);
        InsuranceParts ip = insuranceParts(parts, pen);
        FoldPlay[] plays = new FoldPlay[2];
        for (int f = 0; f < 2; f++) {
            plays[f] = foldPlay(parts, pen, ip, pen.train[f], pen.weight, null, null);
        }
        return new Held(p, pen, ip, plays);
    }

    /**
     * One evaluation (COUNT_DESIGN.md, section 4): score's point estimate at penetration p alone,
     * with no resamples, no decision resolution and no half fits. [entry][metric], the entries as
     * score lists them: the count, then its betting variant when given, each as itself and
     * betting only. Every figure is score's estimate, to the bit.
     */
    double[][] estimate(CountKeys.Count count, CountKeys.Count variant, int p) {
        if (variant != null && variant.base != count) {
            throw new IllegalArgumentException(variant + " is not a variant of " + count);
        }
        Held h = hold(count, p);
        List<Entry> entries = new ArrayList<>();
        List<BetKeys> bk = new ArrayList<>();
        for (CountKeys.Count c : variant == null ? Arrays.asList(count) : Arrays.asList(count, variant)) {
            BetKeys k = betKeys(c, h.pen);
            entries.add(new Entry(c.name, c, false));
            entries.add(new Entry(c.name + ", betting only", c, true));
            bk.add(k);
            bk.add(k);
        }
        return scoreOnce(h.pen, h.ip, h.plays, bk.toArray(new BetKeys[0]), entries, h.pen.weight, p, false);
    }

    /**
     * A betting vector's figures with the play held (COUNT_DESIGN.md, section 4, F2): only its bet
     * keys, the isotonic advantage and the bets are worked out afresh. The count given bets on its
     * bet key and plays the held play, as its entry would in estimate when it is the held count's
     * betting variant, to the bit.
     */
    double[] betOn(Held h, CountKeys.Count betting) {
        List<Entry> one = new ArrayList<>();
        one.add(new Entry(betting.name, betting, false));
        return scoreOnce(h.pen, h.ip, h.plays, new BetKeys[]{betKeys(betting, h.pen)}, one, h.pen.weight, h.p, false)[0];
    }

    // ------------------------------------------------------------- the refit check

    /**
     * The refit check (COUNT_DESIGN.md, section 1): plays fitted for the bets. Each fold's play is
     * first fitted flat, as score fits it; each training state is then weighted by its 1/q times
     * the same-schedule bet at the spread given that its key gets on the training fold itself
     * (keyBets, the advantage fitted on the training fold under that play), and the moves of
     * each (group, deal) and insurance of each (group, ace-up deal) are fitted again under those
     * weights, the groups held. The test fold is then scored as score scores it, under the
     * refitted play and insurance, with the advantage fitted again under them. On every resample
     * all of it is refitted from the resample's weights.
     *
     * Entries, one per penetration filled: the count and, given, its betting variant, each
     * "plays fitted for the 1-S schedule", the variant's training bets from its own bet key.
     */
    List<Entry> scheduleRefit(CountKeys.Count count, CountKeys.Count variant, int spread, int threads) {
        if (variant != null && variant.base != count) {
            throw new IllegalArgumentException(variant + " is not a variant of " + count);
        }
        List<Entry> entries = new ArrayList<>();
        for (CountKeys.Count c : variant == null ? Arrays.asList(count) : Arrays.asList(count, variant)) {
            entries.add(new Entry(c.name + ", plays fitted for the 1-" + spread + " schedule", c, false));
        }
        for (int p = 0; p < CUTS.length; p++) {
            Pen pen = pens[p];
            Parts parts = parts(count, pen);
            InsuranceParts ip = insuranceParts(parts, pen);
            BetKeys[] bk = new BetKeys[entries.size()];
            for (int e = 0; e < bk.length; e++) {
                bk[e] = betKeys(entries.get(e).count, pen);
            }
            FoldPlay[] plays = new FoldPlay[2];
            for (int f = 0; f < 2; f++) {
                plays[f] = foldPlay(parts, pen, ip, pen.train[f], pen.weight, null, null);
            }
            double[][] est = refitScore(parts, pen, ip, plays, bk, pen.weight, null, spread);
            for (int e = 0; e < entries.size(); e++) {
                entries.get(e).estimate[p] = est[e];
                entries.get(e).boot[p] = new double[METRICS][boot.resamples];
            }
            ByKey byKey = boot.resamples > 0 ? byKey(parts, pen) : null;
            int pp = p;
            onThreads(threads, (first, step) -> {
                double[] wm = new double[pen.states.length];
                double[] um = new double[pen.states.length];
                FoldPlay[] refit = new FoldPlay[2];
                for (int r = first; r < boot.resamples; r += step) {
                    for (int j = 0; j < wm.length; j++) {
                        um[j] = boot.mult(r, st.shoe[pen.states[j]]);
                        wm[j] = pen.weight[j] * um[j];
                    }
                    for (int f = 0; f < 2; f++) {
                        refit[f] = foldPlay(parts, pen, ip, drawn(pen.train[f], wm), wm, um, null, byKey, plays[f]);
                    }
                    double[][] rs = refitScore(parts, pen, ip, refit, bk, wm, byKey, spread);
                    for (int e = 0; e < rs.length; e++) {
                        for (int m = 0; m < METRICS; m++) {
                            entries.get(e).boot[pp][m][r] = rs[e][m];
                        }
                    }
                }
            });
        }
        return entries;
    }

    /**
     * The refit check's figures under weights w from each fold's flat play: [entry][metric]. A
     * play refitted on a resample is valued from its flat play's values when byKey is given.
     */
    private double[][] refitScore(Parts parts, Pen pen, InsuranceParts ip, FoldPlay[] plays, BetKeys[] bk, double[] w,
                                  ByKey byKey, int spread) {
        int n = pen.states.length;
        double[][] sums = new double[bk.length][METRICS + 1];
        double[] rv = new double[n];
        double[] wb = new double[n];
        for (int f = 0; f < 2; f++) {
            FoldPlay play = plays[f];
            int[] train = pen.train[f];
            int[] use = drawn(train, w);
            double[] ins = insuranceValues(ip, play.insuranceGroup,
                    insuranceSums(ip, play.insuranceGroup, play.groups.count(), train, w));
            for (int j = 0; j < n; j++) {
                rv[j] = play.base[j] + ins[j];
            }
            for (int e = 0; e < bk.length; e++) {
                // The training states' bets: the advantage fitted on them under the flat play, and
                // the schedule handed out over their own reference bets.
                int keys = bk[e].value.length;
                double[] tw = new double[keys];
                double[] ts = new double[keys];
                for (int j : train) {
                    int k = bk[e].index[j];
                    tw[k] += w[j];
                    ts[k] += w[j] * rv[j];
                }
                double[] adv = isotonic(bk[e].value, tw, ts);
                double[] bet = keyBets(bk[e], adv, train, pen.testByChart[1 - f], w, pen.chart, spread);
                Arrays.fill(wb, 0);
                for (int j : train) {
                    if (w[j] != 0) {
                        wb[j] = w[j] * bet[bk[e].index[j]];
                    }
                }
                Fit fit = fit(parts, pen, use, play.groups, play.table, wb);
                double[] base = new double[n];
                if (byKey == null) {
                    playValues(parts, pen, fit, play.table, null, base);
                } else {
                    playValuesFrom(parts, pen, byKey, play, fit, play.table, base);
                }
                double[] ins2 = insuranceValues(ip, play.insuranceGroup,
                        insuranceSums(ip, play.insuranceGroup, play.groups.count(), train, wb));
                double[] rv2 = new double[n];
                for (int j = 0; j < n; j++) {
                    rv2[j] = base[j] + ins2[j];
                }
                double[] s = sums[e];
                for (int j : pen.test[f]) {
                    s[PLAY] += w[j] * base[j];
                    s[INSURANCE] += w[j] * ins2[j];
                }
                bets(bk[e], train, pen.test[f], pen.testByChart[f], w, rv2, pen.chart, s, null);
            }
        }
        double[][] out = new double[bk.length][METRICS];
        for (int e = 0; e < bk.length; e++) {
            for (int m = 0; m < METRICS; m++) {
                out[e][m] = sums[e][WEIGHT] == 0 ? Double.NaN : sums[e][m] / sums[e][WEIGHT];
            }
        }
        return out;
    }

    /** Both folds' decision resolution at one penetration, the bounds averaged over the folds by test weight. */
    private Resolution resolution(Parts parts, Pen pen, InsuranceParts ip, FoldPlay[] plays) {
        return resolution(parts, pen, ip, plays, null, -1);
    }

    /**
     * The same for full-index play: the first decisions and insurance resolved under each fold's
     * values, and each fold's later cells added (FullIndexFit.Strategy.resolveLater).
     */
    private Resolution resolution(Parts parts, Pen pen, InsuranceParts ip, FoldPlay[] plays, FullIndexFit.Fits fits,
                                  int p) {
        Resolution res = new Resolution();
        double[] bounds = new double[3];
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
            if (fits != null) {
                fits.fold[p][f].resolveLater(tw, bounds, res);
            }
        }
        res.bound = testWeight == 0 ? 0 : bounds[0] / testWeight;
        res.insuranceBound = testWeight == 0 ? 0 : bounds[1] / testWeight;
        res.laterBound = testWeight == 0 ? 0 : bounds[2] / testWeight;
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
        return scoreOnce(pen, ip, plays, bk, entries, w, p, detail, null, null, -1);
    }

    /**
     * The same, and given wideOut, the wide plans' figures of entry e put in wideOut[e], each
     * entry's own rule leaving the table where minKey[e] says its shoe's lowest key so far fell
     * (null when no plan leaves); with detail, also each entry's wide key table (Entry.wideKeys),
     * which startWide must have made room for. The existing figures are worked out as without.
     */
    private double[][] scoreOnce(Pen pen, InsuranceParts ip, FoldPlay[] plays, BetKeys[] bk,
                                 List<Entry> entries, double[] w, int p, boolean detail, int[][] minKey,
                                 double[][] wideOut) {
        return scoreOnce(pen, ip, plays, bk, entries, w, p, detail, minKey, wideOut, -1);
    }

    /**
     * The same, r the resample the weights are (-1 for the estimate): with detail the state sink
     * is handed each entry's test states, and on a resample the resample sink its per-key sums.
     * Both read what the scoring has worked out and write nothing it reads; the wide plans'
     * figures are worked out as without them.
     */
    private double[][] scoreOnce(Pen pen, InsuranceParts ip, FoldPlay[] plays, BetKeys[] bk,
                                 List<Entry> entries, double[] w, int p, boolean detail, int[][] minKey,
                                 double[][] wideOut, int r) {
        int n = pen.states.length;
        Wide[] acc = null;
        double[][][][] wideDet = null;
        if (wideOut != null) {
            acc = new Wide[entries.size()];
            for (int e = 0; e < acc.length; e++) {
                acc[e] = new Wide(wide, pen.planBets(wide), minKey == null ? null : minKey[e]);
            }
            wideDet = detail ? new double[entries.size()][2][][] : null;
        }
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
                if (detail && stateSink != null) {
                    handStates(pen, entries.get(e), bk[e], f, w, play, ins, p);
                }
                if (r >= 0 && resampleSink != null) {
                    handSums(pen, entries.get(e), bk[e], f, w, play, ins, p, r);
                }
                double[][] d = null;
                if (detail) {
                    d = new double[bk[e].value.length][DETAIL];
                    det[e][f] = d;
                }
                Wide a = acc == null ? null : acc[e];
                if (wideDet != null) {
                    a.detail = wideDet[e][f] = new double[bk[e].value.length][wide.length * KEY_COLS];
                }
                if (entries.get(e).ownValue) {
                    ownValueBets(pen.test[f], pen.testByChart[f], w, rv, rv, pen.chart, s, a);
                    continue;
                }
                bets(bk[e], pen.train[f], pen.test[f], pen.testByChart[f], w, rv, pen.chart, s, d, a);
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
            if (entries.get(e).ownValue) {
                noOwnRamp(out[e]);
            }
            if (detail) {
                entries.get(e).shoeSums[p] = shoe[e];
                entries.get(e).keys[p] = keyTable(bk[e], det[e], weight);
            }
            if (acc != null) {
                wideOut[e] = acc[e].figures();
                if (detail) {
                    entries.get(e).wideKeys[p] = entries.get(e).ownValue ? null : wideKeyTable(bk[e], det[e], wideDet[e]);
                }
            }
        }
        return out;
    }

    /** Hands the state sink copies of one entry's test states of fold f, as scoreOnce values them. */
    private void handStates(Pen pen, Entry entry, BetKeys bk, int f, double[] w, double[] play, double[] ins, int p) {
        int[] test = pen.test[f];
        int[] state = new int[test.length];
        double[] weight = new double[test.length];
        double[] pv = new double[test.length];
        double[] iv = new double[test.length];
        int[] key = new int[test.length];
        for (int t = 0; t < test.length; t++) {
            int j = test[t];
            state[t] = pen.states[j];
            weight[t] = w[j];
            pv[t] = play[j];
            iv[t] = ins[j];
            key[t] = bk.value[bk.index[j]];
        }
        stateSink.states(p, entry, f, state, weight, pv, iv, key);
    }

    /**
     * Hands the resample sink one entry's per-key sums over fold f's test states, each summed in
     * the order bets sums them, w x (play + insurance), so a key's value sum is the one its bets
     * were scored with, to the bit.
     */
    private void handSums(Pen pen, Entry entry, BetKeys bk, int f, double[] w, double[] play, double[] ins, int p,
                          int r) {
        int keys = bk.value.length;
        double[] weight = new double[keys];
        double[] value = new double[keys];
        double[] insurance = new double[keys];
        for (int j : pen.test[f]) {
            int k = bk.index[j];
            weight[k] += w[j];
            value[k] += w[j] * (play[j] + ins[j]);
            insurance[k] += w[j] * ins[j];
        }
        resampleSink.sums(p, r, entry, f, bk.value.clone(), weight, value, insurance);
    }

    /** NaN in the own ramp's figures, which a row betting on its own value does not have. */
    static void noOwnRamp(double[] m) {
        for (int sp = 1; sp <= SPREADS; sp++) {
            m[own(sp)] = m[ownBet(sp)] = m[played(sp)] = m[playedBet(sp)] = Double.NaN;
        }
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
        return halfFit(parts, pen, ip, w, units, byKey, full, null, pen.test);
    }

    /**
     * The same with each fold's half fit valued under its own full-index values, values[f]
     * (the stored ones when values is null). An earlier play given for a fold must have been
     * fitted with that fold's values; it is then valued from, and valueOn[f] says which states
     * a play made afresh values.
     */
    double halfFit(Parts parts, Pen pen, InsuranceParts ip, double[] w, double[] units, ByKey byKey,
                   FoldPlay[] earlier, PlayValues[] values, int[][] valueOn) {
        FoldPlay[] plays = halfPlays(parts, pen, ip, w, units, byKey, earlier, values, valueOn);
        return halfFlat(pen, ip, plays, w);
    }

    /** Each fold's half fit: play fitted on the half's drawn training states (see halfFit). */
    FoldPlay[] halfPlays(Parts parts, Pen pen, InsuranceParts ip, double[] w, double[] units, ByKey byKey,
                         FoldPlay[] earlier, PlayValues[] values, int[][] valueOn) {
        FoldPlay[] out = new FoldPlay[2];
        for (int f = 0; f < 2; f++) {
            int[] half = half(pen, f, w);
            out[f] = foldPlay(parts, pen, ip, half, w, units, valueOn == null ? null : valueOn[f], byKey,
                    earlier == null ? null : earlier[f], values == null ? null : values[f]);
        }
        return out;
    }

    /** The half of fold f's training states the learning curve fits on, those of weight w not zero. */
    int[] half(Pen pen, int f, double[] w) {
        return Arrays.stream(pen.train[f]).filter(j -> inHalf(st.shoe[pen.states[j]]) && w[j] != 0).toArray();
    }

    /** Whether a shoe is in the half of its fold the learning curve fits on: its index halved is even. */
    static boolean inHalf(int shoe) {
        return ((shoe >> 1) & 1) == 0;
    }

    /**
     * The learning curve's figure at the 1-8 same schedule (COUNT_DESIGN.md, section 1): for each
     * entry playing its own fit, the half fits' play and insurance, the bets' advantage fitted on
     * the same half, and the same schedule handed out on each whole test fold; the estimate's at
     * r = -1, resample r's otherwise. A row betting only or on its own value has none.
     */
    void halfSchedule(Pen pen, InsuranceParts ip, FoldPlay[] half, BetKeys[] bk, List<Entry> entries, double[] w,
                      int p, int r) {
        double[][] sums = new double[entries.size()][METRICS + 1];
        double[] rv = new double[pen.states.length];
        for (int f = 0; f < 2; f++) {
            FoldPlay play = half[f];
            int[] use = half(pen, f, w);
            double[] ins = insuranceValues(ip, play.insuranceGroup,
                    insuranceSums(ip, play.insuranceGroup, play.groups.count(), use, w));
            for (int j = 0; j < rv.length; j++) {
                rv[j] = play.base[j] + ins[j];
            }
            for (int e = 0; e < entries.size(); e++) {
                Entry x = entries.get(e);
                if (!x.bettingOnly && !x.ownValue) {
                    bets(bk[e], use, pen.test[f], pen.testByChart[f], w, rv, pen.chart, sums[e], null);
                }
            }
        }
        for (int e = 0; e < entries.size(); e++) {
            Entry x = entries.get(e);
            double v = x.bettingOnly || x.ownValue || sums[e][WEIGHT] == 0 ? Double.NaN
                    : sums[e][same(SPREADS)] / sums[e][WEIGHT];
            if (r < 0) {
                x.halfFitSame[p] = v;
            } else {
                x.halfFitSameBoot[p][r] = v;
            }
        }
    }

    /** The flat EV on each fold's test states of the half fits given, insurance fitted on the same half. */
    double halfFlat(Pen pen, InsuranceParts ip, FoldPlay[] plays, double[] w) {
        double num = 0;
        double den = 0;
        for (int f = 0; f < 2; f++) {
            FoldPlay play = plays[f];
            double[] ins = insuranceValues(ip, play.insuranceGroup,
                    insuranceSums(ip, play.insuranceGroup, play.groups.count(), half(pen, f, w), w));
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
     * the most any ordering of that schedule can make of the chart's play. With wide on, the last
     * also bets each wide plan's schedule on each state's own chart advantage (referenceWide).
     */
    List<Entry> references() {
        Entry chart = new Entry("Chart, no count, flat", null, false);
        Entry perfect = new Entry("Perfect first move and insurance, flat", null, false);
        Entry oracle = new Entry("Chart play, schedule on the true advantage", null, false);
        List<Entry> out = Arrays.asList(chart, perfect, oracle);
        int[] rows = wide == null ? null : wideRows(wide);
        if (wide != null) {
            oracle.startWide(wide);
        }
        for (int p = 0; p < CUTS.length; p++) {
            Pen pen = pens[p];
            for (Entry e : out) {
                e.boot[p] = new double[METRICS][boot.resamples];
            }
            if (wide != null) {
                oracle.wideBoot[p] = new double[rows.length][boot.resamples];
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
                if (wide != null) {
                    double[] x = referenceWide(pen, w);
                    if (r < 0) {
                        oracle.wide[p] = x;
                    } else {
                        for (int k = 0; k < rows.length; k++) {
                            oracle.wideBoot[p][k][r] = x[rows[k]];
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * The full-scope perfect row (FULL_INDEX_PLAYS.md section 4), flat, scored on every state of
     * each penetration with the bootstrap as the other reference rows are: every state plays
     * every decision of its dealt hand best from its own composition, its split hands at the
     * chart, and insures exactly when insurance pays. perfectPlay[s] is state s's round value
     * before insurance under that play (FullIndexFit.perfect). A penetration holding a state
     * without one is left out, its estimate and resamples null.
     */
    Entry perfectPlayRow(double[] perfectPlay) {
        if (perfectPlay.length != st.size) {
            throw new IllegalArgumentException("a value for each of the " + st.size + " states");
        }
        Entry row = new Entry("Perfect play of the dealt hand and insurance, flat", null, false);
        for (int p = 0; p < CUTS.length; p++) {
            Pen pen = pens[p];
            boolean every = true;
            for (int s : pen.states) {
                every &= !Double.isNaN(perfectPlay[s]);
            }
            if (!every) {
                continue;
            }
            row.boot[p] = new double[METRICS][boot.resamples];
            double[] w = pen.weight.clone();
            for (int r = -1; r < boot.resamples; r++) {
                if (r >= 0) {
                    for (int j = 0; j < w.length; j++) {
                        w[j] = pen.weight[j] * boot.mult(r, st.shoe[pen.states[j]]);
                    }
                }
                double weight = 0;
                double play = 0;
                double insurance = 0;
                for (int j = 0; j < w.length; j++) {
                    int s = pen.states[j];
                    weight += w[j];
                    play += w[j] * perfectPlay[s];
                    insurance += w[j] * st.perfectInsurance[s];
                }
                double[] m = new double[METRICS];
                Arrays.fill(m, Double.NaN);
                m[FLAT] = (play + insurance) / weight;
                m[PLAY] = play / weight;
                m[INSURANCE] = insurance / weight;
                if (r < 0) {
                    row.estimate[p] = m;
                } else {
                    for (int k = 0; k < METRICS; k++) {
                        row.boot[p][k][r] = m[k];
                    }
                }
            }
        }
        return row;
    }

    /**
     * The bound rows (COUNT_DESIGN.md, section 2), fitted on nothing and scored with the bootstrap:
     * within each test fold, the same schedule's bets (the fold's own reference bets, as every count
     * gets them) handed out in order of each state's own value (ownValueBets):
     * - perfect first move and insurance, each state bet on its value under them: no first-decision
     *   count betting the schedule can beat it, flat or at any spread;
     * - the chart's play with perfect insurance, bet on its value: the same bound for a count
     *   betting only;
     * - given perfectPlay, each state's value under perfect play of the dealt hand before
     *   insurance (FullIndexFit.perfect), that play and perfect insurance bet on its value: the
     *   full-play bound, but for what perfect play of split hands adds, which it plays by the chart.
     * The own ramp has no bound: those figures are NaN. A penetration where perfectPlay lacks a
     * state is left out of the third row, its estimate and resamples null. With wide on, each row
     * also hands out each wide plan's schedule in the same order (Wide.addBlocks): the bound for
     * that plan's same schedule.
     */
    List<Entry> bounds(double[] perfectPlay) {
        if (perfectPlay != null && perfectPlay.length != st.size) {
            throw new IllegalArgumentException("a value for each of the " + st.size + " states");
        }
        List<Entry> out = new ArrayList<>();
        out.add(new Entry("Perfect first move and insurance, bet on its own value", null, false, false, true));
        out.add(new Entry("Chart play and perfect insurance, bet on its own value", null, false, false, true));
        if (perfectPlay != null) {
            out.add(new Entry("Perfect play of the dealt hand and insurance, bet on its own value", null, false, true,
                    true));
        }
        int[] rows = wide == null ? null : wideRows(wide);
        if (wide != null) {
            for (Entry e : out) {
                e.startWide(wide);
            }
        }
        for (int p = 0; p < CUTS.length; p++) {
            Pen pen = pens[p];
            int n = pen.states.length;
            double[][] play = new double[out.size()][n];
            boolean[] every = new boolean[out.size()];
            for (int e = 0; e < out.size(); e++) {
                every[e] = true;
                for (int j = 0; j < n; j++) {
                    int s = pen.states[j];
                    play[e][j] = e == 0 ? st.perfect[s] : e == 1 ? st.chart[s] : perfectPlay[s];
                    every[e] &= !Double.isNaN(play[e][j]);
                }
            }
            double[] ins = new double[n];
            for (int j = 0; j < n; j++) {
                ins[j] = st.perfectInsurance[pen.states[j]];
            }
            for (int e = 0; e < out.size(); e++) {
                if (every[e]) {
                    out.get(e).boot[p] = new double[METRICS][boot.resamples];
                    if (wide != null) {
                        out.get(e).wideBoot[p] = new double[rows.length][boot.resamples];
                    }
                }
            }
            double[] w = pen.weight.clone();
            double[] v = new double[n];
            for (int r = -1; r < boot.resamples; r++) {
                if (r >= 0) {
                    for (int j = 0; j < n; j++) {
                        w[j] = pen.weight[j] * boot.mult(r, st.shoe[pen.states[j]]);
                    }
                }
                for (int e = 0; e < out.size(); e++) {
                    if (!every[e]) {
                        continue;
                    }
                    for (int j = 0; j < n; j++) {
                        v[j] = play[e][j] + ins[j];
                    }
                    double[] sums = new double[METRICS + 1];
                    Wide acc = wide == null ? null : new Wide(wide, pen.planBets(wide), null);
                    for (int f = 0; f < 2; f++) {
                        for (int j : pen.test[f]) {
                            sums[PLAY] += w[j] * play[e][j];
                            sums[INSURANCE] += w[j] * ins[j];
                        }
                        ownValueBets(pen.test[f], pen.testByChart[f], w, v, v, pen.chart, sums, acc);
                    }
                    if (acc != null) {
                        double[] x = acc.figures();
                        if (r < 0) {
                            out.get(e).wide[p] = x;
                        } else {
                            for (int k = 0; k < rows.length; k++) {
                                out.get(e).wideBoot[p][k][r] = x[rows[k]];
                            }
                        }
                    }
                    double[] m = new double[METRICS];
                    for (int k = 0; k < METRICS; k++) {
                        m[k] = sums[k] / sums[WEIGHT];
                    }
                    noOwnRamp(m);
                    if (r < 0) {
                        out.get(e).estimate[p] = m;
                    } else {
                        for (int k = 0; k < METRICS; k++) {
                            out.get(e).boot[p][k][r] = m[k];
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * The oracle row's wide figures: each state bets each plan's g on its own chart advantage and
     * plays the chart, so no block is shared and no threshold is played at random; it has no own
     * rule, whose figures are NaN.
     */
    private double[] referenceWide(Pen pen, double[] w) {
        Wide acc = new Wide(wide, pen.planBets(wide), null);
        double weight = 0;
        for (int j = 0; j < w.length; j++) {
            weight += w[j];
        }
        for (int i = 0; i < wide.length; i++) {
            if (!wide[i].sameSchedule()) {
                continue;
            }
            int at = i * WIDE_METRICS;
            for (int j = 0; j < w.length; j++) {
                int s = pen.states[j];
                double bet = acc.ref[j][i];
                acc.sums[at + W_SAME] += w[j] * bet * st.chart[s];
                acc.sums[at + W_SAME_BET] += w[j] * bet;
                acc.sums[at + W_SAME_SHARE] += bet > 0 ? w[j] : 0;
                acc.sums[at + W_SAME_BET2] += w[j] * bet * bet;
            }
        }
        acc.weight = weight;
        return acc.figures();
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
