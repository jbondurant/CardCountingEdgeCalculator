import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Count-based play on every decision of the dealt hand, fitted jointly with the first decisions
 * by passes over the training states' player trees (FULL_INDEX_PLAYS.md, section 3).
 *
 * A strategy is one count's play at one penetration for one fold, fitted on the other fold's
 * states, or the learning curve's half fit of it, fitted on half of them. Its play is three
 * tables, each a function of the count key at the decision:
 * - the first move of each deal, per first-decision group, as the scorer fits it (SystemScorer);
 * - insurance, per first-decision group, which no later play changes;
 * - hit or stand at each contested spot, hard 12 to 16 and soft 13 to 18 against each up-card,
 *   on a hand of three or more cards, per later-play group. Everywhere else a hand after its
 *   first move plays the chart: the contested walk (PlayerTree.Mode.CONTESTED) plays it there
 *   whatever it is told.
 * A split hand plays the same tables at the split deal's play key (SplitPlay).
 *
 * The later-play groups are grown as the first-decision groups are, from the key holding the
 * most rounds at a later decision, closing at M_play = 5 sqrt(n) valued states. Each training
 * state counts as mass 1 (its multiplicity, on a resample), spread over the keys of its later
 * decisions by its reach there, normalised to 1. They are formed once, from pass 0's reach, and
 * held fixed through the passes.
 *
 * A pass walks every training state's trees under the play in force. Each (state, deal) part
 * gets its Hit value from the tree and its Split value from ExactRound under the post-split play
 * at its key; each later (group, spot) cell gets the sum of weight x reach x (V_stand - V_hit)
 * over its decisions. At the end of the pass every cell is refitted from those sums: a later cell
 * stands if its sum is positive, hits if negative, and every first-decision cell takes its best
 * move, the moves ranked best first for the split hands that read them. A cell no training
 * state reaches, or whose sum is exactly zero, plays the chart, and each such cell is counted.
 * Pass 0 plays the first-decision fit from the stored values and the chart after the first move.
 * A split hand plays section 1's play there too, its two-card decisions from that fit's ranked
 * moves and hit or stand by the chart, so a Split value is the stored one only where those
 * answers are the chart's.
 *
 * Passes repeat until no cell changes, at most MAX_PASSES. Every pass records the training
 * states' weighted EV under the play it walked; at the cap the best of those plays is kept and
 * the strategy flagged. A later cell's one-flip gain is its sum if it stands and minus its sum
 * if it hits: no path meets a spot twice, so flipping a cell changes the hands at it alone
 * (PlayerTreeTest). Once the passes converge every gain is positive or zero; at the cap, a cell
 * whose gain under the kept play is negative is flipped and the training states walked again, up
 * to CLEAN_UPS times, and what is left is charged to the decision bound.
 *
 * A last round walks every state of the penetration under the play kept: it fills the Hit and
 * Split values every state is scored with, and gives each later cell's sum with the per-shoe
 * sums its standard error comes from. A half fit is also walked on the other half of its
 * training fold there, for the in-sample check.
 *
 * Strategies of many counts, penetrations and folds run in the same rounds, so a state's trees
 * are built once a round for all of them. Each thread takes whole shoes, so the per-shoe sums
 * need no sharing.
 */
final class FullIndexFit {

    static final int MAX_PASSES = 6;
    static final int CLEAN_UPS = 2;

    // ------------------------------------------------------------------ cells

    /** The contested spots, each a later cell within a group: up-card first, then hard 12 to 16, then soft 13 to 18. */
    static final int CELLS = 110;
    private static final int[] CELL = new int[PlayerTree.SPOTS];
    private static final int[] SPOT = new int[CELLS];
    /** Whether the chart stands at each cell. */
    private static final boolean[] CHART = new boolean[CELLS];

    static {
        Arrays.fill(CELL, -1);
        int c = 0;
        for (int up = 1; up <= 10; up++) {
            for (boolean soft : new boolean[]{false, true}) {
                for (int total = 4; total <= 21; total++) {
                    if (PlayerTree.contested(total, soft)) {
                        int spot = PlayerTree.spot(total, soft, up);
                        CELL[spot] = c;
                        SPOT[c] = spot;
                        CHART[c] = PlayerTree.CHART.stands(total, soft, up, 0);
                        c++;
                    }
                }
            }
        }
        if (c != CELLS) {
            throw new AssertionError(c + " contested spots");
        }
    }

    /** The cell of a spot, or -1 if the spot is not contested. */
    static int cellOf(int spot) {
        return CELL[spot];
    }

    static int spotOf(int cell) {
        return SPOT[cell];
    }

    static boolean chartStands(int cell) {
        return CHART[cell];
    }

    private static final int HIT = SystemScorer.HIT;
    private static final int SPLIT = SystemScorer.SPLIT;
    private static final int MOVES = ScoringStates.MOVES;

    /**
     * A deal's first moves ranked as the chart plays them, for a cell left to the chart: what
     * it plays with every move offered, then with Split taken away, then Stand.
     */
    static PlayerMove[] chartRanking(RoundRules rules, int deal) {
        int p1 = Deals.P1[deal];
        int p2 = Deals.P2[deal];
        boolean ace = p1 == 1 || p2 == 1;
        int hard = p1 + p2;
        int total = ace && hard <= 11 ? hard + 10 : hard;
        boolean soft = ace && hard <= 11;
        int pair = p1 == p2 ? p1 : 0;
        RoundPolicy chart = BasicStrategy.chart();
        return new PlayerMove[]{
                chart.choose(new RoundPolicy.Decision(total, soft, 2, pair, Deals.UP[deal], false, true, total < 21,
                        true, pair != 0 && rules.maxHandsFor(pair) >= 2, rules.surrender)),
                chart.choose(new RoundPolicy.Decision(total, soft, 2, pair, Deals.UP[deal], false, true, total < 21,
                        true, false, rules.surrender)),
                PlayerMove.Stand};
    }

    // ------------------------------------------------------------------ a play

    /**
     * One play of a strategy: its first moves (with the sums they were fitted from) and their
     * rankings, and its later cells, stands[g x CELLS + cell] over the strategy's later groups,
     * or null for the chart before those groups are formed. Never changed once made.
     */
    static final class Policy {
        final SystemScorer.Fit first;
        final PlayerMove[][][] ranking;
        final boolean[] stands;
        /** Later cells the refit that made this play left to the chart: no reach, and a sum of exactly zero. */
        final int laterNoReach;
        final int laterTies;
        /** The post-split play of a pair deal at (first-decision group, later group), worked out once. */
        private final Map<Long, SplitEntry> splits = new ConcurrentHashMap<>();

        Policy(SystemScorer.Fit first, PlayerMove[][][] ranking, boolean[] stands, int laterNoReach, int laterTies) {
            this.first = first;
            this.ranking = ranking;
            this.stands = stands;
            this.laterNoReach = laterNoReach;
            this.laterTies = laterTies;
        }

        Policy withStands(boolean[] s) {
            return new Policy(first, ranking, s, laterNoReach, laterTies);
        }
    }

    /** A post-split play with its fingerprint, and whether that is the chart's. */
    private static final class SplitEntry {
        final SplitPlay play;
        final byte[] print;
        final String key;
        final boolean chart;

        SplitEntry(SplitPlay play, byte[] print, boolean chart) {
            this.play = play;
            this.print = print;
            this.key = new String(print, java.nio.charset.StandardCharsets.ISO_8859_1);
            this.chart = chart;
        }
    }

    /** Each deal's legal first moves in a group, best first, from a fit's sums; the chart's ranking where nothing reached it. */
    static PlayerMove[][][] rankings(ScoringStates st, RoundRules rules, SystemScorer.Fit fit) {
        int groups = fit.groups.count();
        PlayerMove[][][] out = new PlayerMove[groups][Deals.COUNT][];
        for (int g = 0; g < groups; g++) {
            for (int i = 0; i < Deals.COUNT; i++) {
                if (Deals.playerNatural(i)) {
                    continue;
                }
                if (fit.reach[g][i] == 0) {
                    out[g][i] = chartRanking(rules, i);
                    continue;
                }
                int best = fit.move[g][i];
                List<Integer> rest = new ArrayList<>();
                for (int m = 0; m < MOVES; m++) {
                    if (m != best && st.legal[i * MOVES + m]) {
                        rest.add(m);
                    }
                }
                double[] sum = fit.sum[g];
                int base = i * MOVES;
                rest.sort((a, b) -> Double.compare(sum[base + b], sum[base + a]));
                PlayerMove[] r = new PlayerMove[1 + rest.size()];
                r[0] = StateValuer.MOVES[best];
                for (int k = 0; k < rest.size(); k++) {
                    r[1 + k] = StateValuer.MOVES[rest.get(k)];
                }
                out[g][i] = r;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ sums by key

    /** Sums of reach and gap by (count key, cell), over whatever range of keys arrives. */
    static final class KeyCells {
        int lo;
        int hi = -1;
        double[] gap = new double[0];
        double[] reach = new double[0];

        void add(int key, int cell, double r, double g) {
            if (key < lo || key > hi) {
                grow(key);
            }
            int at = (key - lo) * CELLS + cell;
            gap[at] += g;
            reach[at] += r;
        }

        private void grow(int key) {
            int nlo = hi < lo ? key - 8 : Math.min(lo, key - 8);
            int nhi = hi < lo ? key + 8 : Math.max(hi, key + 8);
            double[] g = new double[(nhi - nlo + 1) * CELLS];
            double[] r = new double[g.length];
            if (hi >= lo) {
                System.arraycopy(gap, 0, g, (lo - nlo) * CELLS, gap.length);
                System.arraycopy(reach, 0, r, (lo - nlo) * CELLS, reach.length);
            }
            gap = g;
            reach = r;
            lo = nlo;
            hi = nhi;
        }

        void addAll(KeyCells o) {
            for (int k = o.lo; k <= o.hi; k++) {
                int from = (k - o.lo) * CELLS;
                for (int c = 0; c < CELLS; c++) {
                    if (o.reach[from + c] != 0 || o.gap[from + c] != 0) {
                        add(k, c, o.reach[from + c], o.gap[from + c]);
                    }
                }
            }
        }

        /** The sums by (group, cell) under these groups: [0] gap, [1] reach, each at g x CELLS + cell. */
        double[][] byGroup(SystemScorer.Groups groups) {
            double[][] out = new double[2][groups.count() * CELLS];
            for (int k = lo; k <= hi; k++) {
                int g = groups.of(k);
                int from = (k - lo) * CELLS;
                for (int c = 0; c < CELLS; c++) {
                    out[0][g * CELLS + c] += gap[from + c];
                    out[1][g * CELLS + c] += reach[from + c];
                }
            }
            return out;
        }
    }

    /**
     * What the later-play groups are grown from, per count key over one range of keys: the
     * valued states' mass at the key, each state's mass 1 spread over the keys of its later
     * decisions by its reach there, and the rounds that reach the key. One range holds both, so
     * the two always line up key for key.
     */
    static final class KeyMass {
        int lo;
        int hi = -1;
        double[] mass = new double[0];
        double[] rounds = new double[0];

        void add(int key, double m, double r) {
            if (key < lo || key > hi) {
                int nlo = hi < lo ? key - 8 : Math.min(lo, key - 8);
                int nhi = hi < lo ? key + 8 : Math.max(hi, key + 8);
                double[] nm = new double[nhi - nlo + 1];
                double[] nr = new double[nm.length];
                if (hi >= lo) {
                    System.arraycopy(mass, 0, nm, lo - nlo, mass.length);
                    System.arraycopy(rounds, 0, nr, lo - nlo, rounds.length);
                }
                mass = nm;
                rounds = nr;
                lo = nlo;
                hi = nhi;
            }
            mass[key - lo] += m;
            rounds[key - lo] += r;
        }

        void addAll(KeyMass o) {
            for (int k = o.lo; k <= o.hi; k++) {
                if (o.mass[k - o.lo] != 0 || o.rounds[k - o.lo] != 0) {
                    add(k, o.mass[k - o.lo], o.rounds[k - o.lo]);
                }
            }
        }
    }

    /**
     * One thread's sums for one strategy in one round, added into the strategy's once the round
     * is over, thread by thread in order: each thread takes a fixed share of the shoes, so a run
     * adds every sum in the same order and gives the same play every time on as many threads.
     */
    static final class Acc {
        final KeyCells sums = new KeyCells();
        final KeyMass mass = new KeyMass();
        final double[] gap;
        final double[] reach;
        /**
         * Per (group, cell), over shoes: the sums of c^2, c x b and b^2, where a and b are a
         * shoe's gap and reach and c = sign x a - centre x b its residual about the gain the
         * last pass found under the same play (Strategy.gainAndSe).
         */
        final double[] scc;
        final double[] scb;
        final double[] sbb;
        final double[] checkGap;
        final double[] checkReach;
        /**
         * For a strategy that holds no values while it fits (Strategy.lean): the sums of weight
         * x share x V(Hit) and of weight x share x V(Split) by (first-decision group x 550 +
         * deal), and of weight x (round value under the play walked + insurance), over this
         * thread's training states. Null otherwise.
         */
        double[] hit;
        double[] split;
        double ev;
        private final double[] sign;
        private final double[] centre;
        // One shoe's sums by (group, cell), for the standard errors.
        private final double[] a;
        private final double[] b;
        private final int[] touched;
        private final boolean[] on;
        private int n;

        Acc(int cells, double[] sign, double[] centre) {
            this.sign = sign;
            this.centre = centre;
            gap = new double[cells];
            reach = new double[cells];
            scc = new double[cells];
            scb = new double[cells];
            sbb = new double[cells];
            checkGap = new double[cells];
            checkReach = new double[cells];
            a = new double[cells];
            b = new double[cells];
            touched = new int[cells];
            on = new boolean[cells];
        }

        void shoeAdd(int at, double r, double g) {
            if (!on[at]) {
                on[at] = true;
                touched[n++] = at;
            }
            a[at] += g;
            b[at] += r;
        }

        /** Takes the shoe's sums into the totals and their squares. */
        void endShoe() {
            for (int k = 0; k < n; k++) {
                int at = touched[k];
                gap[at] += a[at];
                reach[at] += b[at];
                double c = sign[at] * a[at] - centre[at] * b[at];
                scc[at] += c * c;
                scb[at] += c * b[at];
                sbb[at] += b[at] * b[at];
                a[at] = 0;
                b[at] = 0;
                on[at] = false;
            }
            n = 0;
        }
    }

    // ------------------------------------------------------------------ a strategy

    enum Phase {
        /** Joint passes: walk the training states, refit every cell. */
        FITTING,
        /** At the cap: walk the training states under the kept play with its losing later cells flipped. */
        CLEANUP,
        /** Walk every state under the play kept, for its values and the later cells' standard errors. */
        FINAL,
        DONE
    }

    /** What one pass found, for the report. */
    static final class PassRecord implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        final boolean cleanUp;
        /** The training states' weighted EV per round under the play walked, insurance included. */
        final double ev;
        /**
         * For a fitting pass, the first-decision and later cells the refit after it changed; for
         * a clean-up pass, none and the later cells flipped to make the play it walked.
         */
        final int firstChanges;
        final int laterChanges;
        final double wallSeconds;

        PassRecord(boolean cleanUp, double ev, int firstChanges, int laterChanges, double wallSeconds) {
            this.cleanUp = cleanUp;
            this.ev = ev;
            this.firstChanges = firstChanges;
            this.laterChanges = laterChanges;
            this.wallSeconds = wallSeconds;
        }
    }

    /** What a state is to a strategy in a round. */
    static final int NONE = 0;
    /** A training state: values, reach and sums. */
    static final int TRAIN = 1;
    /** The other half of a half fit's training fold, in the last round: values, and sums for the check. */
    static final int CHECK = 2;
    /** Any other state of the penetration in the last round: values only. */
    static final int VALUE = 3;

    /**
     * One count's play at one penetration, fitted on the training states of one fold under one
     * set of weights, or the learning curve's half fit of it.
     */
    static final class Strategy {
        final CountKeys.Count count;
        final int pen;
        /** The fold this play is scored on; it is fitted on the other's states. */
        final int fold;
        final boolean half;
        final SystemScorer scorer;
        final ScoringStates st;
        final SystemScorer.Pen p;
        /**
         * Every part's key, as the scorer has them. A lean strategy lets go of them once made and
         * reads each part's key from its state's count tree instead, until its last round, when
         * it is given them again with the values it is scored with (prepareFinal).
         */
        SystemScorer.Parts parts;
        final int minKey;
        final double[] w;
        final double[] units;
        /** The training states, those of weight not zero, local to the penetration. */
        final int[] use;
        final boolean[] trains;
        /** For a half fit: the other half's states of weight not zero. */
        final boolean[] checks;
        /** The weight of the training states, and the shoes of the training fold (or half) for the standard errors. */
        final double weight;
        final int shoes;
        final SystemScorer.Groups groups;
        final int[] table;
        /** What the fitted insurance adds to each state's round value: insurance is fitted once, as no later play moves it. */
        final double[] insurance;
        /**
         * The Hit and Split values of every state of the penetration, under the play in force
         * (finally, the play kept). A lean strategy has none until its last round.
         */
        SystemScorer.PlayValues values;
        /**
         * Whether the strategy holds no values while it fits. Each pass then adds up, as it
         * walks, what the refit and the training EV need of them: per (group, deal) the sums of
         * the Hit and Split values, the other moves' sums being the stored ones and fixed. The
         * values themselves are worked out in the last round alone, for the scoring.
         */
        final boolean lean;
        /** The first decisions fitted from the stored values: pass 0's, and the stored moves' sums of every refit. */
        private SystemScorer.Fit stored;
        // A lean strategy's sums of the round in progress (Acc.hit, split and ev), added up from its threads'.
        private double[] hitSum;
        private double[] splitSum;
        private double evSum;

        /** The most fitting passes and clean-up passes; tests lower them to reach the cap. */
        int maxPasses = MAX_PASSES;
        int cleanUpLimit = CLEAN_UPS;

        SystemScorer.Groups later;
        Policy now;
        Phase phase = Phase.FITTING;
        int passes;
        int cleanUps;
        boolean converged;
        boolean capped;
        final List<PassRecord> history = new ArrayList<>();
        /** Later cells flipped in clean-up, in all. */
        int flipped;
        /** The fitting pass whose play was kept at the cap, counting from 0. */
        int bestPass = -1;

        private double bestEv;
        /** The play of the fitting pass with the best training EV so far; at the cap, the play kept. */
        Policy best;
        private double[][] bestSums;
        private double[][] lastSums;
        private long roundStart;
        private int flipping;

        // The round in progress, added up from its threads' sums.
        private KeyCells sums;
        private KeyMass mass;
        private boolean firstPass;

        // The last round: per (group, cell), and per shoe for the standard errors.
        double[] finalGap;
        double[] finalReach;
        double[] scc;
        double[] scb;
        double[] sbb;
        double[] checkGap;
        double[] checkReach;
        /** Each later cell's sign under the play kept, +1 standing, and the gain the last pass's sums give it there. */
        private double[] sign;
        private double[] centre;
        /** The largest difference between the last round's later sums and those of the pass that kept its play. */
        double finalAgreement;
        /** First decisions refitted from the last round's values that differ from the play kept (only at the cap). */
        int refitDiffers;
        /**
         * Under the play kept, by the last round's sums over the training states: the
         * first-decision cells (group, deal) and later cells no training state reaches, and
         * those whose best move ties with the chart's, or whose sum is exactly zero. Each plays
         * the chart's move.
         */
        int firstNoReach;
        int firstTies;
        int laterNoReach;
        int laterTies;

        Strategy(SystemScorer scorer, SystemScorer.Parts parts, SystemScorer.InsuranceParts ip, CountKeys.Count count,
                 int pen, int fold, boolean half, double[] w, double[] units, SystemScorer.PlayValues initial) {
            this(scorer, parts, ip, count, pen, fold, half, w, units, initial, false);
        }

        /** The same, holding no values while it fits when lean. */
        Strategy(SystemScorer scorer, SystemScorer.Parts parts, SystemScorer.InsuranceParts ip, CountKeys.Count count,
                 int pen, int fold, boolean half, double[] w, double[] units, SystemScorer.PlayValues initial,
                 boolean lean) {
            if (lean && initial != null) {
                throw new IllegalArgumentException("a lean strategy holds no values while it fits");
            }
            this.scorer = scorer;
            this.lean = lean;
            this.st = scorer.st;
            this.count = count;
            this.pen = pen;
            this.fold = fold;
            this.half = half;
            this.p = scorer.pen(pen);
            this.parts = parts;
            this.minKey = parts.minKey;
            this.w = w;
            this.units = units;
            int n = p.states.length;
            if (w.length != n || (units != null && units.length != n)) {
                throw new IllegalArgumentException("a weight for every state of the penetration");
            }
            this.use = half ? scorer.half(p, fold, w) : SystemScorer.drawn(p.train[fold], w);
            trains = new boolean[n];
            checks = new boolean[n];
            double wt = 0;
            for (int j : use) {
                trains[j] = true;
                wt += w[j];
            }
            if (half) {
                for (int j : p.train[fold]) {
                    if (!trains[j] && w[j] != 0) {
                        checks[j] = true;
                    }
                }
            }
            weight = wt;
            int sh = 0;
            for (int k = 0; k < st.shoes; k++) {
                if ((k & 1) != fold && (!half || SystemScorer.inHalf(k))) {
                    sh++;
                }
            }
            shoes = sh;
            groups = scorer.groups(parts, p, use, w, units);
            table = groups.table(parts);
            int[] insGroup = ip.groups(parts, table);
            insurance = SystemScorer.insuranceValues(ip, insGroup,
                    SystemScorer.insuranceSums(ip, insGroup, groups.count(), use, w));
            if (!lean) {
                values = initial == null ? new SystemScorer.PlayValues(parts.key.length) : initial;
                if (values.hit.length != parts.key.length) {
                    throw new IllegalArgumentException("values for " + values.hit.length + " parts, not " + parts.key.length);
                }
            }
            SystemScorer.Fit f0 = scorer.fit(parts, p, use, groups, table, w);
            stored = f0;
            now = new Policy(f0, rankings(st, scorer.rules(), f0), null, 0, 0);
            if (lean) {
                this.parts = null;
            }
        }

        /**
         * Gives a lean strategy, done fitting, the parts and the place for the values its last
         * round fills: Parts as the scorer makes them for its count and penetration, and values
         * for every one of them (compact or not).
         */
        void prepareFinal(SystemScorer.Parts parts, SystemScorer.PlayValues values) {
            if (!lean || phase != Phase.FINAL) {
                throw new IllegalStateException(this + " is not a lean strategy waiting for its last round");
            }
            if (parts.minKey != minKey || values.hit.length != parts.key.length) {
                throw new IllegalArgumentException("parts and values not of " + this);
            }
            this.parts = parts;
            this.values = values;
        }

        /** Lets a lean strategy's values and parts go once its count is scored, and the post-split plays its last round made. */
        void release() {
            if (!lean || phase != Phase.DONE) {
                throw new IllegalStateException(this + " is not a lean strategy done");
            }
            parts = null;
            values = null;
            now.splits.clear();
        }

        /**
         * Lets go what a lean strategy fitted and waiting for its last round no longer needs: the
         * best pass's play and sums, the stored fit, and the post-split plays worked out so far,
         * which its last round works out again for the states it visits.
         */
        void trim() {
            if (!lean || phase != Phase.FINAL) {
                throw new IllegalStateException(this + " is not a lean strategy waiting for its last round");
            }
            best = null;
            bestSums = null;
            stored = null;
            now.splits.clear();
        }

        @Override
        public String toString() {
            return count.name + " " + SystemScorer.PENETRATIONS[pen] + " fold " + fold + (half ? " half" : "");
        }

        int role(int j) {
            switch (phase) {
                case FITTING:
                case CLEANUP:
                    return trains[j] ? TRAIN : NONE;
                case FINAL:
                    return trains[j] ? TRAIN : checks[j] ? CHECK : w[j] != 0 ? VALUE : NONE;
                default:
                    return NONE;
            }
        }

        boolean done() {
            return phase == Phase.DONE;
        }

        /** The later play in force, for a walk. */
        PlayerTree.LaterPlay laterPlay() {
            Policy pol = now;
            if (pol.stands == null) {
                return PlayerTree.CHART;
            }
            SystemScorer.Groups lg = later;
            boolean[] s = pol.stands;
            return (total, soft, up, key) -> {
                int c = CELL[PlayerTree.spot(total, soft, up)];
                return c < 0 ? PlayerTree.CHART.stands(total, soft, up, key) : s[lg.of(key) * CELLS + c];
            };
        }

        /** Whether the play in force stands at a later cell, in later group g (-1 before the groups are formed). */
        private static boolean stands(Policy pol, int g, int total, boolean soft, int up) {
            int c = CELL[PlayerTree.spot(total, soft, up)];
            if (c < 0 || pol.stands == null || g < 0) {
                return PlayerTree.CHART.stands(total, soft, up, 0);
            }
            return pol.stands[g * CELLS + c];
        }

        /** The post-split play of pair deal i at a part whose key falls in first-decision group g1 and later group g2. */
        private SplitEntry split(Policy pol, int i, int g1, int g2, byte[] chartPrint) {
            long k = ((long) i * 65536 + g1) * 65536 + g2 + 1;
            SplitEntry e = pol.splits.get(k);
            if (e == null) {
                int r = Deals.P1[i];
                int up = Deals.UP[i];
                PlayerMove[][] rank = pol.ranking[g1];
                SplitPlay play = new SplitPlay(r, up, (total, soft) -> stands(pol, g2, total, soft, up), d -> rank[d]);
                byte[] print = SplitValues.fingerprint(scorer.rules(), i, play);
                e = new SplitEntry(play, print, Arrays.equals(print, chartPrint));
                pol.splits.put(k, e);
            }
            return e;
        }

        void beginRound() {
            roundStart = System.nanoTime();
            firstPass = phase == Phase.FITTING && passes == 0;
            if (phase == Phase.FITTING || phase == Phase.CLEANUP) {
                sums = new KeyCells();
                if (firstPass) {
                    mass = new KeyMass();
                }
                if (lean) {
                    hitSum = new double[groups.count() * Deals.COUNT];
                    splitSum = new double[hitSum.length];
                    evSum = 0;
                }
            } else if (phase == Phase.FINAL) {
                if (values == null) {
                    throw new IllegalStateException(this + " has no values for its last round (prepareFinal)");
                }
                int cells = later.count() * CELLS;
                finalGap = new double[cells];
                finalReach = new double[cells];
                scc = new double[cells];
                scb = new double[cells];
                sbb = new double[cells];
                checkGap = new double[cells];
                checkReach = new double[cells];
                sign = new double[cells];
                centre = new double[cells];
                for (int at = 0; at < cells; at++) {
                    sign[at] = now.stands[at] ? 1 : -1;
                    centre[at] = lastSums == null || lastSums[1][at] == 0 ? 0 : sign[at] * lastSums[0][at] / lastSums[1][at];
                }
            }
        }

        /** A new thread's sums for this round. */
        Acc acc() {
            if (phase == Phase.FINAL) {
                return new Acc(later.count() * CELLS, sign, centre);
            }
            Acc a = new Acc(0, null, null);
            if (lean) {
                a.hit = new double[groups.count() * Deals.COUNT];
                a.split = new double[a.hit.length];
            }
            return a;
        }

        /** Adds one thread's sums of the round. */
        void merge(Acc acc) {
            if (phase == Phase.FINAL) {
                for (int at = 0; at < finalGap.length; at++) {
                    finalGap[at] += acc.gap[at];
                    finalReach[at] += acc.reach[at];
                    scc[at] += acc.scc[at];
                    scb[at] += acc.scb[at];
                    sbb[at] += acc.sbb[at];
                    checkGap[at] += acc.checkGap[at];
                    checkReach[at] += acc.checkReach[at];
                }
                return;
            }
            sums.addAll(acc.sums);
            if (firstPass) {
                mass.addAll(acc.mass);
            }
            if (lean) {
                for (int k = 0; k < hitSum.length; k++) {
                    hitSum[k] += acc.hit[k];
                    splitSum[k] += acc.split[k];
                }
                evSum += acc.ev;
            }
        }

        /** Walks one state under the play in force, as its role asks. */
        void visit(int j, int s, PlayerTree.Counted counted, int role, Worker wk) {
            Policy pol = now;
            PlayerTree.Walk walk = wk.walk(counted, wk.play(this));
            if (values != null) {
                record(pol, j, s, counted, walk, wk);
            } else {
                accumulate(pol, j, s, counted, walk, wk);
            }
            if (role == VALUE) {
                return;
            }
            int[] key = counted.partKey;
            int[] from = counted.partFrom;
            walk.reach((deal, part) -> pol.first.move[table[key[from[deal] + part] - minKey]][deal] == HIT, w[j]);
            wk.n = 0;
            walk.decisions(wk);
            Acc acc = wk.acc(this);
            if (role == CHECK) {
                for (int k = 0; k < wk.n; k++) {
                    int at = later.of(wk.keys[k]) * CELLS + wk.cells[k];
                    acc.checkGap[at] += wk.gap[k];
                    acc.checkReach[at] += wk.reach[k];
                }
                return;
            }
            if (phase == Phase.FINAL) {
                for (int k = 0; k < wk.n; k++) {
                    acc.shoeAdd(later.of(wk.keys[k]) * CELLS + wk.cells[k], wk.reach[k], wk.gap[k]);
                }
                return;
            }
            for (int k = 0; k < wk.n; k++) {
                acc.sums.add(wk.keys[k], wk.cells[k], wk.reach[k], wk.gap[k]);
            }
            if (firstPass) {
                double total = 0;
                for (int k = 0; k < wk.n; k++) {
                    total += wk.reach[k];
                }
                if (total > 0) {
                    double u = units == null ? 1 : units[j];
                    for (int k = 0; k < wk.n; k++) {
                        acc.mass.add(wk.keys[k], u * wk.reach[k] / total, wk.reach[k]);
                    }
                }
            }
        }

        /** Writes a state's Hit and Split values under the play walked. */
        private void record(Policy pol, int j, int s, PlayerTree.Counted counted, PlayerTree.Walk walk, Worker wk) {
            int base = j * Deals.COUNT;
            double[] mv = st.move[s];
            for (int i = 0; i < Deals.COUNT; i++) {
                if (counted.chance[i] == 0 || Deals.playerNatural(i)) {
                    continue;
                }
                int from = parts.from(base + i);
                int n = counted.parts(i);
                if (parts.to(base + i) - from != n) {
                    throw new IllegalStateException(count.name + ": deal " + i + " of state " + s + " has "
                            + (parts.to(base + i) - from) + " parts in the scorer and " + n + " in the tree");
                }
                int pf = counted.partFrom[i];
                for (int q = 0; q < n; q++) {
                    if (parts.key[from + q] != counted.partKey[pf + q]) {
                        throw new IllegalStateException(count.name + ": deal " + i + " part " + q + " of state " + s
                                + " has key " + parts.key[from + q] + " in the scorer and " + counted.partKey[pf + q]
                                + " in the tree");
                    }
                    values.hit[from + q] = walk.partDeparts[pf + q] ? wk.units(i, walk.partHit[pf + q])
                            : mv[i * MOVES + HIT];
                }
                if (st.legal[i * MOVES + SPLIT]) {
                    for (int q = 0; q < n; q++) {
                        int key = parts.key[from + q];
                        int g1 = table[key - parts.minKey];
                        int g2 = pol.stands == null ? -1 : later.of(key);
                        SplitEntry e = split(pol, i, g1, g2, wk.chartPrint(i));
                        wk.printMet(i, e);
                        values.setSplit(from + q, e.chart ? mv[i * MOVES + SPLIT] : wk.units(i, wk.split(i, e)));
                    }
                }
            }
        }

        /**
         * What a lean strategy's refit and training EV take from a training state's values
         * under the play walked, added to this thread's sums: per (group, deal), weight x share
         * x V(Hit) and x V(Split), and the state's weight x (round value + insurance). Each
         * value is worked out as record works it out, each part keyed by its count tree, and
         * none is kept.
         */
        private void accumulate(Policy pol, int j, int s, PlayerTree.Counted counted, PlayerTree.Walk walk, Worker wk) {
            if (phase != Phase.FITTING && phase != Phase.CLEANUP) {
                throw new IllegalStateException(this + " holds no values in its last round");
            }
            Acc acc = wk.acc(this);
            double[] mv = st.move[s];
            double wj = w[j];
            double v = st.naturals[s];
            for (int i = 0; i < Deals.COUNT; i++) {
                if (counted.chance[i] == 0 || Deals.playerNatural(i)) {
                    continue;
                }
                boolean canSplit = st.legal[i * MOVES + SPLIT];
                int pf = counted.partFrom[i];
                for (int q = pf; q < counted.partFrom[i + 1]; q++) {
                    int key = counted.partKey[q];
                    int g1 = table[key - minKey];
                    double share = counted.partShare[q];
                    double ws = wj * share;
                    int at = g1 * Deals.COUNT + i;
                    double hit = walk.partDeparts[q] ? wk.units(i, walk.partHit[q]) : mv[i * MOVES + HIT];
                    acc.hit[at] += ws * hit;
                    double split = Double.NaN;
                    if (canSplit) {
                        int g2 = pol.stands == null ? -1 : later.of(key);
                        SplitEntry e = split(pol, i, g1, g2, wk.chartPrint(i));
                        wk.printMet(i, e);
                        split = e.chart ? mv[i * MOVES + SPLIT] : wk.units(i, wk.split(i, e));
                        acc.split[at] += ws * split;
                    }
                    int m = pol.first.move[g1][i];
                    v += share * (m == HIT ? hit : m == SPLIT ? split : mv[i * MOVES + m]);
                }
            }
            acc.ev += wj * (v + insurance[j]);
        }

        /**
         * A lean strategy's refit of its first decisions: the stored fit's sums, with Hit and
         * Split summed under the play this round walked. The other moves' sums and the reach do
         * not depend on the play, so they are the stored fit's own.
         */
        private SystemScorer.Fit leanFit() {
            int g0 = groups.count();
            double[][] sum = new double[g0][];
            for (int g = 0; g < g0; g++) {
                sum[g] = stored.sum[g].clone();
                for (int i = 0; i < Deals.COUNT; i++) {
                    if (Deals.playerNatural(i)) {
                        continue;
                    }
                    int at = g * Deals.COUNT + i;
                    if (st.legal[i * MOVES + HIT]) {
                        sum[g][i * MOVES + HIT] = hitSum[at];
                    }
                    if (st.legal[i * MOVES + SPLIT]) {
                        sum[g][i * MOVES + SPLIT] = splitSum[at];
                    }
                }
            }
            return scorer.fitFrom(groups, sum, stored.reach, stored.weight);
        }

        /** The training EV of the play the round walked: a lean strategy's own sum, or from the values held. */
        private double roundEv() {
            if (!lean) {
                return trainingEv(now);
            }
            return weight == 0 ? Double.NaN : evSum / weight;
        }

        /** Refits after a round, and moves on to the next phase. */
        void endRound() {
            double seconds = (System.nanoTime() - roundStart) / 1e9;
            switch (phase) {
                case FITTING:
                    endPass(seconds);
                    break;
                case CLEANUP:
                    endCleanUp(seconds);
                    break;
                case FINAL:
                    endFinal();
                    break;
                default:
                    break;
            }
        }

        private void endPass(double seconds) {
            double ev = roundEv();
            if (passes == 0) {
                later = laterGroups();
                now = now.withStands(chartStands(later.count()));
            }
            double[][] gr = sums.byGroup(later);
            lastSums = gr;
            SystemScorer.Fit f = lean ? leanFit() : scorer.fit(parts, p, use, groups, table, w, values);
            hitSum = null;
            splitSum = null;
            Policy next = refitLater(f, rankings(st, scorer.rules(), f), gr);
            int[] changes = changes(now, next);
            history.add(new PassRecord(false, ev, changes[0], changes[1], seconds));
            if (bestPass < 0 || ev > bestEv) {
                bestPass = passes;
                bestEv = ev;
                best = now;
                bestSums = gr;
            }
            passes++;
            sums = null;
            mass = null;
            if (changes[0] == 0 && changes[1] == 0) {
                converged = true;
                phase = Phase.FINAL;
            } else if (passes == maxPasses) {
                capped = true;
                now = best;
                lastSums = bestSums;
                cleanUpOrFinish(bestSums);
            } else {
                now = next;
            }
        }

        private void endCleanUp(double seconds) {
            double ev = roundEv();
            hitSum = null;
            splitSum = null;
            double[][] gr = sums.byGroup(later);
            lastSums = gr;
            sums = null;
            history.add(new PassRecord(true, ev, 0, flipping, seconds));
            cleanUpOrFinish(gr);
        }

        /**
         * Flips the later cells that lose under the play in force, by the sums of the pass that
         * walked it, and walks again, unless none loses or the clean-ups are spent. Flipping one
         * cell gains its loss exactly; flipping several at once can interact, which is why the
         * play is walked again rather than taken as fixed.
         */
        private void cleanUpOrFinish(double[][] gr) {
            int losing = losing(now, gr);
            if (losing == 0 || cleanUps == cleanUpLimit) {
                phase = Phase.FINAL;
                return;
            }
            boolean[] s = now.stands.clone();
            for (int at = 0; at < s.length; at++) {
                if (gr[1][at] > 0 && (s[at] ? gr[0][at] < 0 : gr[0][at] > 0)) {
                    s[at] = !s[at];
                }
            }
            flipped += losing;
            flipping = losing;
            now = now.withStands(s);
            cleanUps++;
            phase = Phase.CLEANUP;
        }

        private void endFinal() {
            double most = 0;
            if (lastSums != null) {
                for (int at = 0; at < finalGap.length; at++) {
                    most = Math.max(most, Math.abs(finalGap[at] - lastSums[0][at]));
                    most = Math.max(most, Math.abs(finalReach[at] - lastSums[1][at]));
                }
            }
            finalAgreement = most;
            // A state of weight zero, which a resample leaves out, is never walked; it takes the
            // stored values, which count for nothing beside its weight but keep every sum a number.
            for (int j = 0; j < w.length; j++) {
                if (w[j] != 0) {
                    continue;
                }
                double[] mv = st.move[p.states[j]];
                for (int i = 0; i < Deals.COUNT; i++) {
                    int cell = j * Deals.COUNT + i;
                    for (int x = parts.from(cell); x < parts.to(cell); x++) {
                        values.hit[x] = mv[i * MOVES + HIT];
                        if (values.splitAt == null || st.legal[i * MOVES + SPLIT]) {
                            values.setSplit(x, mv[i * MOVES + SPLIT]);
                        }
                    }
                }
            }
            SystemScorer.Fit f = scorer.fit(parts, p, use, groups, table, w, values);
            int differ = 0;
            int noReach = 0;
            int ties = 0;
            for (int g = 0; g < groups.count(); g++) {
                for (int i = 0; i < Deals.COUNT; i++) {
                    if (Deals.playerNatural(i)) {
                        continue;
                    }
                    differ += f.move[g][i] != now.first.move[g][i] ? 1 : 0;
                    if (f.reach[g][i] == 0) {
                        noReach++;
                    } else if (tied(f, g, i)) {
                        ties++;
                    }
                }
            }
            refitDiffers = differ;
            firstNoReach = noReach;
            firstTies = ties;
            laterNoReach = 0;
            laterTies = 0;
            for (int at = 0; at < finalReach.length; at++) {
                if (finalReach[at] == 0) {
                    laterNoReach++;
                } else if (finalGap[at] == 0) {
                    laterTies++;
                }
            }
            phase = Phase.DONE;
        }

        /** Whether the chart's move ties for the best of a (group, deal) another move also reaches. */
        private boolean tied(SystemScorer.Fit f, int g, int i) {
            int chart = st.chartMove[i];
            double top = f.sum[g][i * MOVES + chart];
            for (int m = 0; m < MOVES; m++) {
                if (m != chart && st.legal[i * MOVES + m]) {
                    if (f.sum[g][i * MOVES + m] > top) {
                        return false;
                    }
                }
            }
            for (int m = 0; m < MOVES; m++) {
                if (m != chart && st.legal[i * MOVES + m] && f.sum[g][i * MOVES + m] == top) {
                    return true;
                }
            }
            return false;
        }

        /** The training states' weighted EV per round under a play's first moves and the values in force, insurance included. */
        private double trainingEv(Policy pol) {
            double[] out = new double[p.states.length];
            scorer.playValues(parts, p, pol.first, table, use, out, values);
            double num = 0;
            for (int j : use) {
                num += w[j] * (out[j] + insurance[j]);
            }
            return weight == 0 ? Double.NaN : num / weight;
        }

        /**
         * The later groups from pass 0's reach: each training state's mass spread over its later
         * keys by its reach, the walk starting at the key the most rounds reach, and a group
         * closing at M_play = 5 sqrt(n), n the valued states of the training fold (each counted
         * units[j] times), as the first-decision groups close.
         */
        private SystemScorer.Groups laterGroups() {
            if (mass.hi < mass.lo) {
                return new SystemScorer.Groups(new int[]{0}, new int[]{0});
            }
            int span = mass.hi - mass.lo + 1;
            boolean[] held = new boolean[span];
            for (int k = 0; k < span; k++) {
                held[k] = mass.rounds[k] > 0;
            }
            double valued = 0;
            for (int j : use) {
                valued += units == null ? 1 : units[j];
            }
            return SystemScorer.grow(mass.lo, mass.mass.clone(), mass.rounds.clone(), held, scorer.threshold(valued));
        }

        private static boolean[] chartStands(int groups) {
            boolean[] s = new boolean[groups * CELLS];
            for (int g = 0; g < groups; g++) {
                System.arraycopy(CHART, 0, s, g * CELLS, CELLS);
            }
            return s;
        }

        /** The play the refit gives: these first moves, and each later cell standing if its sum is positive. */
        private Policy refitLater(SystemScorer.Fit f, PlayerMove[][][] ranking, double[][] gr) {
            boolean[] s = new boolean[later.count() * CELLS];
            int noReach = 0;
            int ties = 0;
            for (int at = 0; at < s.length; at++) {
                if (gr[1][at] == 0) {
                    s[at] = CHART[at % CELLS];
                    noReach++;
                } else if (gr[0][at] == 0) {
                    s[at] = CHART[at % CELLS];
                    ties++;
                } else {
                    s[at] = gr[0][at] > 0;
                }
            }
            return new Policy(f, ranking, s, noReach, ties);
        }

        /** Later cells whose one-flip gain under the play is negative, by the sums given. */
        private static int losing(Policy pol, double[][] gr) {
            int n = 0;
            for (int at = 0; at < pol.stands.length; at++) {
                if (gr[1][at] > 0 && (pol.stands[at] ? gr[0][at] < 0 : gr[0][at] > 0)) {
                    n++;
                }
            }
            return n;
        }

        /**
         * How two plays differ: the first-decision cells, counting a (group, deal)'s move and,
         * for the pairs, every answer a split hand reads from the rankings; and the later cells.
         */
        private int[] changes(Policy a, Policy b) {
            int first = 0;
            RoundRules rules = scorer.rules();
            for (int g = 0; g < groups.count(); g++) {
                for (int i = 0; i < Deals.COUNT; i++) {
                    if (a.first.move[g][i] != b.first.move[g][i]) {
                        first++;
                    } else if (st.legal[i * MOVES + SPLIT]) {
                        if (!Arrays.equals(firstPrint(rules, a, g, i), firstPrint(rules, b, g, i))) {
                            first++;
                        }
                    }
                }
            }
            int lat = 0;
            for (int at = 0; at < b.stands.length; at++) {
                lat += a.stands[at] != b.stands[at] ? 1 : 0;
            }
            return new int[]{first, lat};
        }

        /** A split's answers to the two-card decisions of its split hands, read from a play's rankings at group g. */
        private byte[] firstPrint(RoundRules rules, Policy pol, int g, int i) {
            int r = Deals.P1[i];
            int up = Deals.UP[i];
            PlayerMove[][] rank = pol.ranking[g];
            return SplitValues.fingerprint(rules, i, new SplitPlay(r, up,
                    (total, soft) -> PlayerTree.CHART.stands(total, soft, up, 0), d -> rank[d]));
        }

        // ------------------------------------------------------------- checkpoints

        /**
         * What a checkpoint keeps of this strategy between rounds: whatever a later round reads
         * that making the strategy again does not give, its plays, passes and sums. Only between
         * rounds of the passes, or once it waits for its last round.
         */
        State state() {
            if (phase == Phase.DONE || sums != null || finalGap != null) {
                throw new IllegalStateException(this + " is not between rounds of its passes");
            }
            State x = new State();
            x.name = toString();
            x.phase = phase;
            x.passes = passes;
            x.cleanUps = cleanUps;
            x.converged = converged;
            x.capped = capped;
            x.history = new ArrayList<>(history);
            x.flipped = flipped;
            x.bestPass = bestPass;
            x.bestEv = bestEv;
            x.bestSums = bestSums;
            x.lastSums = lastSums;
            x.flipping = flipping;
            x.later = later;
            x.now = new PolicyState(now);
            x.best = best == null ? null : best == now ? x.now : new PolicyState(best);
            return x;
        }

        /** Puts back what state() kept, on a strategy made afresh for the same count, penetration, fold and weights. */
        void restore(State x) {
            if (!x.name.equals(toString()) || passes != 0 || phase != Phase.FITTING) {
                throw new IllegalStateException("a checkpoint of " + x.name + " cannot restore " + this);
            }
            phase = x.phase;
            passes = x.passes;
            cleanUps = x.cleanUps;
            converged = x.converged;
            capped = x.capped;
            history.addAll(x.history);
            flipped = x.flipped;
            bestPass = x.bestPass;
            bestEv = x.bestEv;
            bestSums = x.bestSums;
            lastSums = x.lastSums;
            flipping = x.flipping;
            later = x.later;
            now = x.now.policy(this);
            best = x.best == null ? null : x.best == x.now ? now : x.best.policy(this);
        }

        // ------------------------------------------------------------- after the fit

        /** Whether the play kept stands at later cell (g, cell). */
        boolean standsAt(int g, int cell) {
            return now.stands[g * CELLS + cell];
        }

        /**
         * A later cell's one-flip gain per time it comes up, under the play kept, and its
         * standard error: the gain is d = sign x G / R, with G and R the training states' summed
         * gap and reach and sign +1 where the play stands; the SE is sqrt(n / (n - 1) x the sum
         * over shoes of (sign x a - d x b)^2) / R, by the delta method with shoes as clusters,
         * every shoe of the training fold counted, as SystemScorer.resolve takes a first
         * decision's. The sum is kept about the gain the last pass found, which is d but for
         * rounding, so the residuals are added up as they are and not as the difference of large
         * squares, which loses every digit for a cell one shoe reaches.
         */
        double[] gainAndSe(int at) {
            double r = finalReach[at];
            if (r == 0) {
                return null;
            }
            double d = sign[at] * finalGap[at] / r;
            double off = d - centre[at];
            double e2 = scc[at] - 2 * off * scb[at] + off * off * sbb[at];
            double inflate = shoes > 1 ? (double) shoes / (shoes - 1) : 0;
            return new double[]{d, Math.sqrt(inflate * Math.max(0, e2)) / r};
        }

        /**
         * Adds this play's later cells to a decision resolution, as SystemScorer.resolve does the
         * first decisions: a cell is settled when its one-flip gain per time it comes up exceeds
         * twice its standard error, from per-shoe sums with every shoe of the training fold
         * counted; otherwise keeping it can cost at most its frequency per round times 2 SE less
         * the gain, which charges a negative gain in full.
         */
        void resolveLater(double testWeight, double[] foldBound, SystemScorer.Resolution out) {
            double bound = 0;
            for (int at = 0; at < finalReach.length; at++) {
                double[] ds = gainAndSe(at);
                if (ds == null) {
                    continue;
                }
                out.laterDecisions++;
                if (!(ds[0] > 2 * ds[1])) {
                    out.laterUnsettled++;
                    SystemScorer.LaterCall call = new SystemScorer.LaterCall(fold, later.lo[at / CELLS],
                            later.hi[at / CELLS], spotOf(at % CELLS), now.stands[at], ds[0], ds[1], finalReach[at] / weight);
                    bound += call.bound();
                    out.keepLater(call);
                }
            }
            foldBound[2] += testWeight * bound;
        }

        /**
         * The in-sample check of a half fit: its later cells' signs worked out again on the other
         * half of its training fold, where it was not fitted. Gives the cells compared (reached in
         * both halves, with a sum not zero on the other), how many of those the other half would
         * play the other way, and the same two among the cells settled on the half fitted.
         */
        int[] inSampleCheck() {
            if (!half) {
                throw new IllegalStateException("only a half fit has another half to check on");
            }
            int compared = 0;
            int disagree = 0;
            int settled = 0;
            int settledDisagree = 0;
            for (int at = 0; at < finalReach.length; at++) {
                double[] ds = gainAndSe(at);
                if (ds == null || checkReach[at] == 0 || checkGap[at] == 0) {
                    continue;
                }
                compared++;
                boolean other = checkGap[at] > 0;
                boolean differs = other != now.stands[at];
                disagree += differs ? 1 : 0;
                if (ds[0] > 2 * ds[1]) {
                    settled++;
                    settledDisagree += differs ? 1 : 0;
                }
            }
            return new int[]{compared, disagree, settled, settledDisagree};
        }

        /** What the report needs of this strategy once it is done, without its values. */
        Summary summary() {
            if (phase != Phase.DONE) {
                throw new IllegalStateException(this + " is not done");
            }
            return new Summary(this);
        }
    }

    /** A strategy between rounds, as a checkpoint keeps it (Strategy.state). */
    static final class State implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        String name;
        Phase phase;
        int passes;
        int cleanUps;
        boolean converged;
        boolean capped;
        List<PassRecord> history;
        int flipped;
        int bestPass;
        double bestEv;
        double[][] bestSums;
        double[][] lastSums;
        int flipping;
        SystemScorer.Groups later;
        PolicyState now;
        PolicyState best;
    }

    /** A play as a checkpoint keeps it: its first moves and sums and its later cells; its rankings are made again from the sums. */
    static final class PolicyState implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        final int[][] move;
        final double[][] sum;
        final double[][] reach;
        final double weight;
        final boolean[] stands;
        final int laterNoReach;
        final int laterTies;

        PolicyState(Policy p) {
            move = p.first.move;
            sum = p.first.sum;
            reach = p.first.reach;
            weight = p.first.weight;
            stands = p.stands;
            laterNoReach = p.laterNoReach;
            laterTies = p.laterTies;
        }

        Policy policy(Strategy t) {
            SystemScorer.Fit f = new SystemScorer.Fit(t.groups, move, sum, reach, weight);
            return new Policy(f, rankings(t.st, t.scorer.rules(), f), stands, laterNoReach, laterTies);
        }
    }

    /**
     * A finished strategy as the report reads it: how its passes went, its later groups and
     * answers, each later cell's one-flip gain, standard error and frequency under the play
     * kept (NaN where no training state reaches it), the cells left to the chart, and for a
     * half fit the in-sample check. It holds none of the strategy's per-state values, so a
     * batch's strategies can be let go once their counts are scored.
     */
    static final class Summary implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        final String name;
        final int pen;
        final int fold;
        final boolean half;
        final int passes;
        final boolean converged;
        final boolean capped;
        final int bestPass;
        final int cleanUps;
        final int flipped;
        final List<PassRecord> history;
        final SystemScorer.Groups later;
        final boolean[] stands;
        final double[] gain;
        final double[] se;
        final double[] frequency;
        final int laterNoReach;
        final int laterTies;
        final int firstNoReach;
        final int firstTies;
        final int refitDiffers;
        final double finalAgreement;
        /** For a half fit, inSampleCheck's four counts; null otherwise. */
        final int[] inSample;

        private Summary(Strategy t) {
            name = t.toString();
            pen = t.pen;
            fold = t.fold;
            half = t.half;
            passes = t.passes;
            converged = t.converged;
            capped = t.capped;
            bestPass = t.bestPass;
            cleanUps = t.cleanUps;
            flipped = t.flipped;
            history = new ArrayList<>(t.history);
            later = t.later;
            stands = t.now.stands.clone();
            int cells = stands.length;
            gain = new double[cells];
            se = new double[cells];
            frequency = new double[cells];
            for (int at = 0; at < cells; at++) {
                double[] ds = t.gainAndSe(at);
                gain[at] = ds == null ? Double.NaN : ds[0];
                se[at] = ds == null ? Double.NaN : ds[1];
                frequency[at] = ds == null ? Double.NaN : t.finalReach[at] / t.weight;
            }
            laterNoReach = t.laterNoReach;
            laterTies = t.laterTies;
            firstNoReach = t.firstNoReach;
            firstTies = t.firstTies;
            refitDiffers = t.refitDiffers;
            finalAgreement = t.finalAgreement;
            inSample = t.half ? t.inSampleCheck() : null;
        }
    }

    // ------------------------------------------------------------------ one count's fits

    /** One count's strategies, as the scorer takes them: per penetration, each fold's and each fold's half fit. */
    static final class Fits {
        final CountKeys.Count count;
        final Strategy[][] fold = new Strategy[SystemScorer.CUTS.length][2];
        final Strategy[][] half = new Strategy[SystemScorer.CUTS.length][2];
        /**
         * What a scoring item of this count at each penetration holds, worked out from the parts
         * when they are planned, so a batch is formed without making any: Prepared.bytes.
         */
        final double[] itemBytes = new double[SystemScorer.CUTS.length];

        Fits(CountKeys.Count count) {
            this.count = count;
        }

        boolean has(int pen) {
            return fold[pen][0] != null && fold[pen][1] != null;
        }

        /** The parts a strategy at this penetration holds (the same for all four), or null if none holds them. */
        SystemScorer.Parts partsAt(int pen) {
            for (Strategy[] two : new Strategy[][]{fold[pen], half[pen]}) {
                for (Strategy t : two) {
                    if (t != null && t.parts != null) {
                        return t.parts;
                    }
                }
            }
            return null;
        }

        SystemScorer.PlayValues[] values(int pen) {
            return has(pen) ? new SystemScorer.PlayValues[]{fold[pen][0].values, fold[pen][1].values} : null;
        }

        SystemScorer.PlayValues[] halfValues(int pen) {
            return half[pen][0] != null && half[pen][1] != null
                    ? new SystemScorer.PlayValues[]{half[pen][0].values, half[pen][1].values} : null;
        }

        List<Strategy> all() {
            List<Strategy> out = new ArrayList<>();
            for (Strategy[][] by : new Strategy[][][]{fold, half}) {
                for (Strategy[] pf : by) {
                    for (Strategy s : pf) {
                        if (s != null) {
                            out.add(s);
                        }
                    }
                }
            }
            return out;
        }
    }

    /**
     * The strategies of the estimate for each count at each penetration given: both folds,
     * with the half fits when asked, every state weighted 1/q and counted once.
     */
    static List<Fits> plan(SystemScorer scorer, List<CountKeys.Count> counts, int[] pens, boolean halves) {
        return plan(scorer, counts, pens, halves, false);
    }

    /** The same, every strategy lean (holding no values while it fits) when asked. */
    static List<Fits> plan(SystemScorer scorer, List<CountKeys.Count> counts, int[] pens, boolean halves, boolean lean) {
        List<Fits> out = new ArrayList<>();
        for (CountKeys.Count c : counts) {
            Fits fits = new Fits(c);
            for (int pen : pens) {
                SystemScorer.Pen p = scorer.pen(pen);
                SystemScorer.Parts parts = scorer.parts(c, p);
                SystemScorer.InsuranceParts ip = scorer.insuranceParts(parts, p);
                fits.itemBytes[pen] = Prepared.bytes(parts, scorer.splitSlots(parts, p).count);
                for (int f = 0; f < 2; f++) {
                    fits.fold[pen][f] = new Strategy(scorer, parts, ip, c, pen, f, false, p.weight, null, null, lean);
                    if (halves) {
                        fits.half[pen][f] = new Strategy(scorer, parts, ip, c, pen, f, true, p.weight, null, null, lean);
                    }
                }
            }
            out.add(fits);
        }
        return out;
    }

    // ------------------------------------------------------------------ where trees come from

    /** A state's player tree. */
    interface Trees {
        PlayerTree of(int state, ExactRound engine);
    }

    /** Builds each tree when asked, as the full run must, its states being too many to hold. */
    static Trees built(ScoringStates st) {
        return (s, engine) -> new PlayerTree(engine, ShoeRun.ranksLeft(st.dealt[s]));
    }

    /** Builds every state's tree once, on several threads, and keeps them. */
    static Trees kept(ScoringStates st, RoundRules rules, int threads) throws Exception {
        PlayerTree[] trees = new PlayerTree[st.size];
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            AtomicInteger next = new AtomicInteger();
            List<Future<?>> jobs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                jobs.add(pool.submit(() -> {
                    ExactRound engine = new ExactRound(rules);
                    for (int s = next.getAndIncrement(); s < st.size; s = next.getAndIncrement()) {
                        trees[s] = new PlayerTree(engine, ShoeRun.ranksLeft(st.dealt[s]));
                    }
                    return null;
                }));
            }
            for (Future<?> f : jobs) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        return (s, engine) -> trees[s];
    }

    // ------------------------------------------------------------------ the rounds

    /** What one round did, summed over its threads. */
    static final class RoundStats implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        int round;
        int strategies;
        int states;
        long walks;
        /** Walks worked out: a walk is shared by the strategies at a state that play the same later play (Worker.walk). */
        long walksMade;
        long splitAsks;
        long splitChart;
        long splitKept;
        long splitValued;
        /** Pair deals of a state asked about in the round, and the distinct post-split plays among their asks. */
        long pairDeals;
        long distinctPlays;
        /** The distinct plays that are not the chart's: what a cache kept for one state's visit values. */
        long distinctOther;
        int mostPlays;
        long pairDealsWithMore;
        double treeCpu;
        double walkCpu;
        double splitCpu;
        double wall;

        synchronized void add(RoundStats o) {
            states += o.states;
            walks += o.walks;
            walksMade += o.walksMade;
            splitAsks += o.splitAsks;
            splitChart += o.splitChart;
            splitKept += o.splitKept;
            splitValued += o.splitValued;
            pairDeals += o.pairDeals;
            distinctPlays += o.distinctPlays;
            distinctOther += o.distinctOther;
            mostPlays = Math.max(mostPlays, o.mostPlays);
            pairDealsWithMore += o.pairDealsWithMore;
            treeCpu += o.treeCpu;
            walkCpu += o.walkCpu;
            splitCpu += o.splitCpu;
        }
    }

    /** One thread's working state: its engine, the state it is on, and scratch for a walk's decisions. */
    final class Worker implements PlayerTree.DecisionSink {
        final ExactRound engine = new ExactRound(rules);
        final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        final RoundStats stats = new RoundStats();
        private int state = -1;
        private double[] dn;
        private SplitValues splitValues;
        private final Map<Integer, Set<String>> prints = new HashMap<>();
        private final IdentityHashMap<Strategy, PlayerTree.LaterPlay> plays = new IdentityHashMap<>();
        private final IdentityHashMap<PlayerTree.LaterPlay, PlayerTree.Walk> walks = new IdentityHashMap<>();
        private PlayerTree.Counted walked;
        /** This thread's sums of the round, per strategy, in the order first met. */
        private final IdentityHashMap<Strategy, Acc> accs = new IdentityHashMap<>();
        int n;
        int[] keys = new int[256];
        int[] cells = new int[256];
        double[] reach = new double[256];
        double[] gap = new double[256];

        @Override
        public void add(int spot, int key, double r, double g) {
            int c = CELL[spot];
            if (c < 0) {
                throw new IllegalStateException("a contested walk asked about spot " + spot);
            }
            if (n == keys.length) {
                keys = Arrays.copyOf(keys, 2 * n);
                cells = Arrays.copyOf(cells, 2 * n);
                reach = Arrays.copyOf(reach, 2 * n);
                gap = Arrays.copyOf(gap, 2 * n);
            }
            keys[n] = key;
            cells[n] = c;
            reach[n] = r;
            gap[n] = g;
            n++;
        }

        PlayerTree.LaterPlay play(Strategy t) {
            return plays.computeIfAbsent(t, Strategy::laterPlay);
        }

        /**
         * The contested walk of a count's tree under a later play, worked out once for every
         * strategy at the state that plays the same LaterPlay: in pass 0 every strategy of a
         * count plays the chart (PlayerTree.CHART), and walks the same tree the same way. A walk's
         * values do not depend on who asks; its reach is spread again by each strategy, which
         * reads it before the next one does.
         */
        PlayerTree.Walk walk(PlayerTree.Counted counted, PlayerTree.LaterPlay play) {
            if (counted != walked) {
                walks.clear();
                walked = counted;
            }
            PlayerTree.Walk w = walks.get(play);
            if (w == null) {
                w = counted.walk(play, PlayerTree.Mode.CONTESTED);
                walks.put(play, w);
                stats.walksMade++;
            }
            return w;
        }

        void beginState(int s) {
            state = s;
            dn = null;
            splitValues = null;
            prints.clear();
            walks.clear();
            walked = null;
        }

        void endState() {
            for (Set<String> set : prints.values()) {
                stats.pairDeals++;
                stats.distinctPlays += set.size();
                stats.distinctOther += set.size() - (set.contains("chart") ? 1 : 0);
                stats.mostPlays = Math.max(stats.mostPlays, set.size());
                stats.pairDealsWithMore += set.size() > 1 ? 1 : 0;
            }
        }

        /** A conditional value of deal i of the current state in the units of ScoringStates.move. */
        double units(int i, double v) {
            if (dn == null) {
                int[] left = ShoeRun.ranksLeft(st.dealt[state]);
                dn = new double[Deals.COUNT];
                for (int k = 0; k < Deals.COUNT; k++) {
                    dn[k] = Deals.dealerNatural(left, k);
                }
            }
            return st.chance[state][i] * (-dn[i] + (1 - dn[i]) * v);
        }

        byte[] chartPrint(int i) {
            return chartPrints[i];
        }

        void printMet(int i, SplitEntry e) {
            stats.splitAsks++;
            if (e.chart) {
                stats.splitChart++;
            }
            prints.computeIfAbsent(i, k -> new HashSet<>()).add(e.chart ? "chart" : e.key);
        }

        /** V(Split) of deal i of the current state under a play that is not the chart's, conditional on no natural. */
        double split(int i, SplitEntry e) {
            if (splitValues == null) {
                SplitValues.Kept kept;
                if (splitCache == null || !keeping) {
                    kept = new SplitValues.Packed();
                } else {
                    if (splitCache[state] == null) {
                        splitCache[state] = new SplitValues.Packed();
                    }
                    kept = splitCache[state];
                }
                splitValues = new SplitValues(rules, ShoeRun.ranksLeft(st.dealt[state]), null, kept, engine);
            }
            int valued = splitValues.valued;
            int kept = splitValues.fromKept;
            long c0 = bean.getCurrentThreadCpuTime();
            double v = splitValues.value(i, e.play, e.print);
            stats.splitCpu += (bean.getCurrentThreadCpuTime() - c0) / 1e9;
            stats.splitValued += splitValues.valued - valued;
            stats.splitKept += splitValues.fromKept - kept;
            return v;
        }

        Acc acc(Strategy t) {
            return accs.computeIfAbsent(t, Strategy::acc);
        }

        void endShoe() {
            for (Acc a : accs.values()) {
                a.endShoe();
            }
        }
    }

    final ScoringStates st;
    final RoundRules rules;
    /** Where each state's trees come from; the joint run walks kept trees in its passes and built ones in its last rounds. */
    Trees trees;
    final int threads;
    /**
     * Split values kept per state from round to round, or null to keep them for one state's
     * visit only. A state is visited by one thread at a time, and a round's visits finish before
     * the next round's begin.
     */
    final SplitValues.Packed[] splitCache;
    /** Whether split values still go into splitCache; dropSplitCache stops it. */
    private volatile boolean keeping = true;
    private final byte[][] chartPrints = new byte[Deals.COUNT][];
    /** Each state's round value with every decision of the dealt hand played best and split hands at the chart; NaN until worked out. */
    final double[] perfect;
    final List<RoundStats> rounds = new ArrayList<>();
    /** Where a line goes after each round, and as the first thread gets through each tenth of its shoes; null for none. */
    PrintStream log;
    /** Called after each round, once every strategy has refitted; the joint run writes its checkpoint here. */
    AfterRound afterRound;

    /** Work done after a round, which may fail. */
    interface AfterRound {
        void ended() throws Exception;
    }

    /**
     * @param keepSplits keep every split value worked out, per state, for later rounds and later
     *                   runs on the same states: worth it when the trees are kept too
     * @param perfect    work out the full-scope perfect row's value of every state visited
     */
    FullIndexFit(ScoringStates st, RoundRules rules, Trees trees, int threads, boolean keepSplits, boolean perfect) {
        this.st = st;
        this.rules = rules;
        this.trees = trees;
        this.threads = threads;
        this.splitCache = keepSplits ? new SplitValues.Packed[st.size] : null;
        RoundPolicy chart = BasicStrategy.chart();
        for (int i = 0; i < Deals.COUNT; i++) {
            if (Deals.P1[i] == Deals.P2[i] && rules.maxHandsFor(Deals.P1[i]) >= 2) {
                chartPrints[i] = SplitValues.fingerprint(rules, i, chart);
            }
        }
        if (perfect) {
            this.perfect = new double[st.size];
            Arrays.fill(this.perfect, Double.NaN);
        } else {
            this.perfect = null;
        }
    }

    /** Lets the split values kept from round to round go, and keeps none from now on, to make room. */
    void dropSplitCache() {
        keeping = false;
        if (splitCache != null) {
            Arrays.fill(splitCache, null);
        }
    }

    /** Runs rounds until every strategy given is done. */
    void run(List<Strategy> strategies) throws Exception {
        run(strategies, false);
    }

    /**
     * Runs rounds until every strategy given is done or, when holdFinal, waits for its last
     * round: the joint run fits every strategy first and gives each its last round later, a
     * batch of counts at a time (scoreJoint).
     */
    void run(List<Strategy> strategies, boolean holdFinal) throws Exception {
        if (strategies.isEmpty()) {
            return;
        }
        for (Strategy t : strategies) {
            if (t.st != st) {
                throw new IllegalArgumentException(t + " is fitted on other states");
            }
        }
        // Each penetration's local index of every state, -1 outside it.
        int[][] local = new int[SystemScorer.CUTS.length][];
        SystemScorer scorer = strategies.get(0).scorer;
        for (int p = 0; p < local.length; p++) {
            local[p] = new int[st.size];
            Arrays.fill(local[p], -1);
            int[] states = scorer.pen(p).states;
            for (int j = 0; j < states.length; j++) {
                local[p][states[j]] = j;
            }
        }
        List<int[]> shoes = new ArrayList<>();
        for (int s = 0; s < st.size; ) {
            int e = s;
            while (e < st.size && st.shoe[e] == st.shoe[s]) {
                e++;
            }
            shoes.add(new int[]{s, e});
            s = e;
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "full-index-fit");
            t.setDaemon(true);
            return t;
        });
        try {
            while (true) {
                List<Strategy> active = new ArrayList<>();
                for (Strategy t : strategies) {
                    if (!t.done() && !(holdFinal && t.phase == Phase.FINAL)) {
                        active.add(t);
                    }
                }
                if (active.isEmpty()) {
                    break;
                }
                // The round's strategies by count, so a state's count tree is set up once for all of a count's.
                List<List<Strategy>> byCount = new ArrayList<>();
                Map<CountKeys.Count, List<Strategy>> index = new IdentityHashMap<>();
                for (Strategy t : active) {
                    index.computeIfAbsent(t.count, k -> {
                        List<Strategy> l = new ArrayList<>();
                        byCount.add(l);
                        return l;
                    }).add(t);
                }
                for (Strategy t : active) {
                    t.beginRound();
                }
                RoundStats total = new RoundStats();
                total.round = rounds.size();
                total.strategies = active.size();
                long start = System.nanoTime();
                List<Future<Worker>> jobs = new ArrayList<>();
                for (int k = 0; k < threads; k++) {
                    int first = k;
                    jobs.add(pool.submit(() -> {
                        Worker wk = new Worker();
                        int mine = (shoes.size() - first + threads - 1) / threads;
                        int done = 0;
                        for (int x = first; x < shoes.size(); x += threads) {
                            int[] range = shoes.get(x);
                            for (int s = range[0]; s < range[1]; s++) {
                                state(s, byCount, local, wk);
                            }
                            wk.endShoe();
                            done++;
                            if (log != null && first == 0 && done * 10 / mine > (done - 1) * 10 / mine) {
                                log.printf("  round %d, %d strategies: the first thread is %d%% through its shoes after %.0f s%n",
                                        total.round, total.strategies, 100 * done / mine, (System.nanoTime() - start) / 1e9);
                                log.flush();
                            }
                        }
                        return wk;
                    }));
                }
                List<Worker> workers = new ArrayList<>();
                for (Future<Worker> f : jobs) {
                    workers.add(f.get());
                }
                total.wall = (System.nanoTime() - start) / 1e9;
                for (Worker wk : workers) {
                    total.add(wk.stats);
                    for (Strategy t : active) {
                        Acc a = wk.accs.get(t);
                        if (a != null) {
                            t.merge(a);
                        }
                    }
                }
                for (Strategy t : active) {
                    t.endRound();
                }
                rounds.add(total);
                if (log != null) {
                    int left = 0;
                    for (Strategy t : active) {
                        left += t.done() || (holdFinal && t.phase == Phase.FINAL) ? 0 : 1;
                    }
                    log.printf("round %d done in %.1f s: %d strategies walked %d states; %d splits valued, %d of %d walks "
                                    + "worked out; %d strategies go on%n", total.round, total.wall, total.strategies,
                            total.states, total.splitValued, total.walksMade, total.walks, left);
                    log.flush();
                }
                if (afterRound != null) {
                    afterRound.ended();
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Does every strategy's work on one state for this round. */
    private void state(int s, List<List<Strategy>> byCount, int[][] local, Worker wk) {
        wk.beginState(s);
        PlayerTree tree = null;
        boolean visited = false;
        for (List<Strategy> group : byCount) {
            PlayerTree.Counted counted = null;
            for (Strategy t : group) {
                int j = local[t.pen][s];
                if (j < 0) {
                    continue;
                }
                int role = t.role(j);
                if (role == NONE) {
                    continue;
                }
                if (tree == null) {
                    long c0 = wk.bean.getCurrentThreadCpuTime();
                    tree = trees.of(s, wk.engine);
                    wk.stats.treeCpu += (wk.bean.getCurrentThreadCpuTime() - c0) / 1e9;
                }
                long c0 = wk.bean.getCurrentThreadCpuTime();
                double split0 = wk.stats.splitCpu;
                if (counted == null) {
                    counted = tree.count(t.count, st.dealt[s]);
                }
                t.visit(j, s, counted, role, wk);
                wk.stats.walks++;
                wk.stats.walkCpu += (wk.bean.getCurrentThreadCpuTime() - c0) / 1e9 - (wk.stats.splitCpu - split0);
                visited = true;
            }
        }
        if (visited) {
            wk.stats.states++;
            if (perfect != null && Double.isNaN(perfect[s])) {
                perfect[s] = perfectValue(s, tree, wk);
            }
        }
        wk.endState();
    }

    /**
     * A state's round value, before insurance, with every decision of the dealt hand played
     * best from the state's own composition: each deal's best first move, Hit valued by the
     * maximising walk, the other moves at their stored values, so split hands play the chart.
     */
    private double perfectValue(int s, PlayerTree tree, Worker wk) {
        PlayerTree.Counted counted = tree.count(CountKeys.nullCount(), st.dealt[s]);
        PlayerTree.Walk walk = counted.perfect();
        double[] mv = st.move[s];
        double v = st.naturals[s];
        for (int i = 0; i < Deals.COUNT; i++) {
            if (st.chance[s][i] == 0 || Deals.playerNatural(i)) {
                continue;
            }
            int pf = counted.partFrom[i];
            double top = Double.NEGATIVE_INFINITY;
            for (int m = 0; m < MOVES; m++) {
                if (!st.legal[i * MOVES + m]) {
                    continue;
                }
                double x = m == HIT && walk.partDeparts[pf] ? wk.units(i, walk.partHit[pf]) : mv[i * MOVES + m];
                top = Math.max(top, x);
            }
            v += top;
        }
        return v;
    }

    // ------------------------------------------------------------------ batches

    /** What scoreAll and scoreJoint give. */
    static final class Scored {
        /** Each count's Result, in the order the counts were given. */
        final List<SystemScorer.Result> results = new ArrayList<>();
        /** The full-scope perfect row's value of each state before insurance, NaN where not worked out; null if not asked for. */
        double[] perfect;
        /**
         * Each batch's rounds, in order. For scoreJoint, the joint passes' rounds first, then
         * each scoring batch's last round.
         */
        final List<List<RoundStats>> rounds = new ArrayList<>();
        /** For scoreJoint: the counts of each scoring batch, and seconds on the wall of the passes and of each batch's last round and scoring. */
        final List<List<CountKeys.Count>> batches = new ArrayList<>();
        double fitWall;
        final List<Double> finalWall = new ArrayList<>();
        final List<Double> scoreWall = new ArrayList<>();
        /** For scoreJoint: what each scoring batch was estimated to hold, in bytes. */
        final List<Double> batchBytes = new ArrayList<>();
        /** For scoreJoint: the CPU seconds of each count's scoring, in the order the counts were given. */
        double[] scoreCpu;
    }

    /** Sees scoreJoint as it goes; the dry run and the tests listen here. */
    interface Watch {
        /** Every strategy is fitted and waits for its last round. */
        default void fitted(List<Fits> fits, FullIndexFit fit) {
        }

        /** A batch's strategies have had their last round and hold their values, not yet scored or let go. */
        default void finalRound(List<Fits> batch) {
        }
    }

    /**
     * Scores every count given both ways in one run of the scorer each, playing its first
     * decisions only and playing its full-index fit, on the same resamples
     * (SystemScorer.score), with its betting variant where it has one (CountKeys.bettingVariant).
     *
     * The counts go in batches of the size given. A batch's strategies, both folds and the
     * learning curve's half fits at each penetration given, are fitted in one run of passes,
     * each state's trees built from the trees given (afresh each round, for the full run) and
     * set up once a round for every strategy of a count. Each count of the batch is then
     * scored and its strategies let go, so the memory the values take is one batch's
     * (FULL_INDEX_PLAYS.md section 3). A count's Result.widen is set from the map given
     * (SystemScorer.Widening.of), the default given where it has none, and a count missing with
     * no default is refused; no map leaves it null. The full-scope perfect row is worked out in
     * the first batch's run when asked.
     * Split values are kept per state from round to round when keepSplits, which is worth it
     * when the trees are kept too.
     */
    static Scored scoreAll(SystemScorer scorer, Trees trees, List<CountKeys.Count> counts, int[] pens, int batch,
                           int threads, boolean keepSplits, boolean perfect,
                           Map<CountKeys.Count, SystemScorer.Widening> widen, SystemScorer.Widening missing)
            throws Exception {
        if (batch < 1) {
            throw new IllegalArgumentException("a batch holds at least one count");
        }
        Scored out = new Scored();
        for (int from = 0; from < counts.size(); from += batch) {
            List<CountKeys.Count> some = counts.subList(from, Math.min(counts.size(), from + batch));
            List<Fits> fits = plan(scorer, some, pens, true);
            List<Strategy> all = new ArrayList<>();
            for (Fits f : fits) {
                all.addAll(f.all());
            }
            boolean first = from == 0;
            FullIndexFit fit = new FullIndexFit(scorer.st, scorer.rules(), trees, threads, keepSplits, perfect && first);
            fit.run(all);
            if (perfect && first) {
                out.perfect = fit.perfect;
            }
            out.rounds.add(fit.rounds);
            for (int k = 0; k < fits.size(); k++) {
                CountKeys.Count c = some.get(k);
                SystemScorer.Result r = scorer.score(c, CountKeys.bettingVariant(c), fits.get(k));
                r.widen = SystemScorer.Widening.of(widen, missing, c);
                out.results.add(r);
                fits.set(k, null);
            }
        }
        return out;
    }

    /**
     * Scores every count given both ways, as scoreAll does, with every count's strategies fitted
     * together (FULL_INDEX_PLAYS.md section 6); see JointRun. Trees from trees in every round,
     * split values kept for each state's visit only unless keepSplits, no checkpoints.
     */
    static Scored scoreJoint(SystemScorer scorer, Trees trees, List<CountKeys.Count> counts, int[] pens,
                             double batchBytes, int threads, boolean keepSplits, boolean perfect,
                             Map<CountKeys.Count, SystemScorer.Widening> widen, Watch watch) throws Exception {
        return scoreJoint(scorer, null, trees, counts, pens, batchBytes, threads, keepSplits, perfect, widen, watch, null);
    }

    /** The same, the passes walking passTrees (trees when null), and a line to log after each round and batch. */
    static Scored scoreJoint(SystemScorer scorer, Trees passTrees, Trees trees, List<CountKeys.Count> counts,
                             int[] pens, double batchBytes, int threads, boolean keepSplits, boolean perfect,
                             Map<CountKeys.Count, SystemScorer.Widening> widen, Watch watch, PrintStream log)
            throws Exception {
        JointRun run = new JointRun(scorer, counts, pens, threads);
        run.passTrees = passTrees;
        run.trees = trees;
        run.batchBytes = batchBytes;
        run.keepSplits = keepSplits;
        run.perfect = perfect;
        run.widen = widen;
        run.watch = watch;
        run.log = log;
        return run.run();
    }

    /**
     * The joint run (FULL_INDEX_PLAYS.md section 6). Every count's strategies, both folds and the
     * half fits at each penetration given, run their passes together: each round visits each
     * state once, gets its trees once, sets each count's tree up once and walks every strategy
     * still fitting, and the split values worked out at a state are shared by every strategy
     * there, by the post-split play's fingerprint (SplitValues), so a play several counts or
     * penetrations share is valued once. While they fit, the strategies are lean: they hold their
     * policies and the sums each pass adds up, not a Hit and a Split value for every state.
     *
     * Once every strategy is fitted, the scoring goes in items, in order: each count's
     * penetrations in turn, at each its two fold fits and then its two half fits (the two parts
     * SystemScorer.Part scores apart). As many items go to a batch as batchBytes holds by
     * Prepared's estimate, always at least one. A batch's strategies get their parts and their
     * values, Split held compactly for the deals that can be split, walk every state in one last
     * round, and its items are scored at once on the threads, each item's resamples shared among
     * the threads left to it; then their values are let go. A count's parts are put together
     * (SystemScorer.merge) once all are scored. The Results are those scoreAll gives the same
     * counts: the same plays, refitted in the same order, and the same values for scoring, but
     * for sums added in another order.
     *
     * With a checkpoint (FullIndexCheckpoint), the run writes after every round of the passes
     * what the next round needs, the strategies' plays and sums and the run's rounds, after the
     * passes the same marked fitted, and every item's scored part as it is scored. Resumed, it
     * starts from the last round written, or from the fitted strategies, and scores only the
     * items with no part written; on the same number of threads it gives the same Results to the
     * bit, since every sum is added in the same order (FullIndexFitTest).
     */
    static final class JointRun {
        final SystemScorer scorer;
        final List<CountKeys.Count> counts;
        final int[] pens;
        final int threads;
        /** Trees for the passes (trees when null) and for the last rounds. */
        Trees passTrees;
        Trees trees;
        /** What a scoring batch may hold, in bytes; when 0 or less, heapFill of the heap less what the run holds once fitted. */
        double batchBytes;
        double heapFill = 0.8;
        /** The bytes a state the split values kept are allowed before the passes, in the check that the run fits. */
        double splitAllowance = 32_000;
        boolean keepSplits = true;
        boolean perfect = true;
        /** Each count's widening (SystemScorer.Widening.of), and the default for a count the map lacks. */
        Map<CountKeys.Count, SystemScorer.Widening> widen;
        SystemScorer.Widening missingWiden;
        Watch watch;
        PrintStream log;
        FullIndexCheckpoint checkpoint;
        boolean resume;
        /** Called after each round of the passes is written to the checkpoint, with the round; the tests interrupt here. */
        java.util.function.IntConsumer afterRound;
        /** Called after each item's part is scored and written, with the items scored so far. */
        java.util.function.IntConsumer afterItem;
        /** Called before a round's checkpoint is written, so the trees it read are on disk first (TreeStore.sync). */
        AfterRound beforeCheckpoint;
        /** Whether the check that the run fits in the heap (preflight) runs even when batchBytes is set, as in a dry run. */
        boolean preflightAlways;
        /**
         * Called once, when every refusal before the passes is behind (the checkpoint opened, the
         * strategies made and the heap checked) and before the passes start: a main-file run writes
         * its look to the ledger here (COUNT_DESIGN.md, section 5), so a run refused before costs no look.
         */
        AfterRound beforePasses;

        JointRun(SystemScorer scorer, List<CountKeys.Count> counts, int[] pens, int threads) {
            this.scorer = scorer;
            this.counts = counts;
            this.pens = pens.clone();
            this.threads = threads;
        }

        private void log(String format, Object... args) {
            if (log != null) {
                log.printf(format + "%n", args);
                log.flush();
            }
        }

        Scored run() throws Exception {
            Watch see = watch == null ? new Watch() {
            } : watch;
            if (checkpoint != null) {
                checkpoint.open(resume);
            } else if (resume) {
                throw new IllegalArgumentException("nothing to resume from without a checkpoint");
            }
            Scored out = new Scored();
            long t0 = System.nanoTime();
            List<Fits> fits = plan(scorer, counts, pens, true, true);
            List<Strategy> all = new ArrayList<>();
            for (Fits f : fits) {
                all.addAll(f.all());
            }
            if (batchBytes <= 0 || preflightAlways) {
                preflight(fits, all);
            }
            if (beforePasses != null) {
                beforePasses.ended();
            }
            FullIndexFit fit = new FullIndexFit(scorer.st, scorer.rules(), passTrees == null ? trees : passTrees, threads,
                    keepSplits, perfect);
            passTrees = null;
            fit.log = log;
            boolean fitted = false;
            if (resume) {
                Boolean f = checkpoint.readFit(fit, all);
                if (f != null) {
                    fitted = f;
                    log("resumed from the checkpoint after round %d%s", fit.rounds.size() - 1,
                            fitted ? ", every strategy fitted" : "");
                }
            }
            if (!fitted) {
                if (checkpoint != null) {
                    fit.afterRound = () -> {
                        if (beforeCheckpoint != null) {
                            beforeCheckpoint.ended();
                        }
                        checkpoint.writeFit(fit, all, false);
                        if (afterRound != null) {
                            afterRound.accept(fit.rounds.size() - 1);
                        }
                    };
                }
                fit.run(all, true);
                fit.afterRound = null;
                if (checkpoint != null) {
                    if (beforeCheckpoint != null) {
                        beforeCheckpoint.ended();
                    }
                    checkpoint.writeFit(fit, all, true);
                }
            }
            fit.trees = trees;
            out.fitWall = (System.nanoTime() - t0) / 1e9;
            out.rounds.add(new ArrayList<>(fit.rounds));
            if (perfect) {
                out.perfect = fit.perfect;
            }
            for (Strategy t : all) {
                t.trim();
            }

            // The items, in order, and the parts already scored.
            List<int[]> items = new ArrayList<>();
            for (int k = 0; k < counts.size(); k++) {
                for (int p : pens) {
                    for (int part = 0; part < 2; part++) {
                        items.add(new int[]{k, p, part});
                    }
                }
            }
            SystemScorer.Result[][] parts = new SystemScorer.Result[counts.size()][items.size() / counts.size()];
            List<int[]> todo = new ArrayList<>();
            for (int x = 0; x < items.size(); x++) {
                int[] it = items.get(x);
                SystemScorer.Result r = resume ? checkpoint.readPart(key(it)) : null;
                if (r != null) {
                    parts[it[0]][x % parts[0].length] = r;
                } else {
                    todo.add(new int[]{it[0], it[1], it[2], x % parts[0].length});
                }
            }
            if (todo.size() < items.size()) {
                log("%d of %d scoring items were scored before", items.size() - todo.size(), items.size());
            }
            // A count whose every item was scored before needs its strategies no more.
            boolean[] waits = new boolean[counts.size()];
            for (int[] it : todo) {
                waits[it[0]] = true;
            }
            for (int k = 0; k < counts.size(); k++) {
                if (!waits[k]) {
                    fits.set(k, null);
                }
            }
            see.fitted(fits, fit);

            double room = batchBytes;
            if (room <= 0) {
                double heap = heapFill * Runtime.getRuntime().maxMemory();
                double held = FullIndexPilot.usedAfterGc();
                // A run resumed past its passes starts with no split values kept, and its last rounds
                // keep them again; the room is what is left once they are back, as for a run never stopped.
                double refill = fitted && keepSplits ? splitAllowance * scorer.st.size : 0;
                room = heap - held - refill;
                Item big = largest(fits, items(fits));
                if (big.bytes > room && keepSplits) {
                    log("the largest scoring item, %s, holds %.2f GB, more than the %.2f GB left beside the %.2f GB the run "
                            + "holds; the split values kept are let go to make room, and the last rounds value theirs "
                            + "afresh", big.name, big.bytes / 1e9, room / 1e9, (held + refill) / 1e9);
                    fit.dropSplitCache();
                    held = FullIndexPilot.usedAfterGc();
                    room = heap - held;
                }
                if (big.bytes > room) {
                    throw new IllegalStateException(String.format("the largest scoring item, %s, holds %.2f GB, more than "
                            + "the %.2f GB left of %.0f%% of the heap beside the %.2f GB the run holds; the fit is "
                            + "checkpointed, so resume it with a larger heap", big.name, big.bytes / 1e9, room / 1e9,
                            100 * heapFill, held / 1e9));
                }
            }
            log("every strategy fitted; scoring batches hold up to %.0f MB", room / 1e6);
            out.scoreCpu = new double[counts.size()];
            SystemScorer.Result[] results = new SystemScorer.Result[counts.size()];
            for (int k = 0; k < counts.size(); k++) {
                results[k] = complete(parts[k], k);
            }
            ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "full-index-score");
                t.setDaemon(true);
                return t;
            });
            try {
                int next = 0;
                int scored = items.size() - todo.size();
                while (next < todo.size()) {
                    // A batch is formed from the items' bytes worked out when they were planned; its
                    // parts are made only once it is formed, so nothing is held outside its estimate.
                    List<int[]> batch = new ArrayList<>();
                    double bytes = 0;
                    while (next < todo.size()) {
                        int[] it = todo.get(next);
                        double b = fits.get(it[0]).itemBytes[it[1]];
                        if (!batch.isEmpty() && bytes + b > room) {
                            break;
                        }
                        batch.add(it);
                        bytes += b;
                        next++;
                    }
                    List<Prepared> prepared = new ArrayList<>();
                    for (int[] it : batch) {
                        prepared.add(new Prepared(scorer, fits.get(it[0]), it[1], it[2] == 1));
                    }
                    List<Strategy> finals = new ArrayList<>();
                    List<Fits> batchFits = new ArrayList<>();
                    List<CountKeys.Count> batchCounts = new ArrayList<>();
                    for (int b = 0; b < batch.size(); b++) {
                        Fits f = fits.get(batch.get(b)[0]);
                        finals.addAll(prepared.get(b).give(f));
                        if (!batchFits.contains(f)) {
                            batchFits.add(f);
                            batchCounts.add(f.count);
                        }
                    }
                    prepared.clear();
                    int before = fit.rounds.size();
                    long f0 = System.nanoTime();
                    fit.run(finals, false);
                    out.finalWall.add((System.nanoTime() - f0) / 1e9);
                    out.rounds.add(new ArrayList<>(fit.rounds.subList(before, fit.rounds.size())));
                    out.batches.add(batchCounts);
                    out.batchBytes.add(bytes);
                    see.finalRound(batchFits);

                    long s0 = System.nanoTime();
                    // A batch's items are scored one after another, each on every thread. Scored at
                    // once on a share of the threads each, a batch waited for its slowest item, a
                    // fold fit costing about five times a half fit in the 6,130-state dry run, while
                    // the others' threads sat idle.
                    for (int[] it : batch) {
                        Fits f = fits.get(it[0]);
                        int[] only = {it[1]};
                        SystemScorer.Part part = it[2] == 0 ? SystemScorer.Part.FOLDS : SystemScorer.Part.HALVES;
                        SystemScorer.Result r = pool.submit(() -> {
                            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
                            long c0 = bean.getCurrentThreadCpuTime();
                            SystemScorer.Result one = scorer.score(f.count, CountKeys.bettingVariant(f.count), f, only,
                                    threads, part);
                            synchronized (out) {
                                out.scoreCpu[it[0]] += (bean.getCurrentThreadCpuTime() - c0) / 1e9;
                            }
                            return one;
                        }).get();
                        if (checkpoint != null) {
                            checkpoint.writePart(key(it), r);
                        }
                        parts[it[0]][it[3]] = r;
                        for (Strategy t : strategiesOf(fits.get(it[0]), it[1], it[2] == 1)) {
                            t.release();
                        }
                        results[it[0]] = complete(parts[it[0]], it[0]);
                        scored++;
                        if (afterItem != null) {
                            afterItem.accept(scored);
                        }
                    }
                    out.scoreWall.add((System.nanoTime() - s0) / 1e9);
                    log("batch %d of %d items: last round %.1f s, scored in %.1f s; %d of %d items scored",
                            out.batches.size() - 1, batch.size(), out.finalWall.get(out.finalWall.size() - 1),
                            out.scoreWall.get(out.scoreWall.size() - 1), scored, items.size());
                    for (int k = 0; k < counts.size(); k++) {
                        if (results[k] != null) {
                            fits.set(k, null);
                        }
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            out.results.addAll(Arrays.asList(results));
            return out;
        }

        /** A scoring item's name and bytes, for the checks. */
        static final class Item {
            final String name;
            final double bytes;

            Item(String name, double bytes) {
                this.name = name;
                this.bytes = bytes;
            }
        }

        /** Every scoring item still to be scored, by its count's fits, penetration and part. */
        private List<Item> items(List<Fits> fits) {
            List<Item> out = new ArrayList<>();
            for (Fits f : fits) {
                if (f == null) {
                    continue;
                }
                for (int p : pens) {
                    out.add(new Item(f.count.name + " at " + SystemScorer.PENETRATIONS[p] + " (its fold fits, or its half "
                            + "fits)", f.itemBytes[p]));
                }
            }
            return out;
        }

        private static Item largest(List<Fits> fits, List<Item> items) {
            Item big = new Item("none", 0);
            for (Item it : items) {
                if (it.bytes > big.bytes) {
                    big = it;
                }
            }
            return big;
        }

        /**
         * Before the passes: refuses a run that could not score its largest item, naming it. What
         * the run will hold once fitted is what it holds now, with every strategy made, less the
         * parts the strategies let go, plus the plays they will hold and the split values kept,
         * at splitAllowance bytes a state (the dry runs keep 10 to 30 KB a state); the largest
         * item must fit beside that in heapFill of the heap. The passes themselves hold less: no
         * item, and the sums of a round.
         */
        private void preflight(List<Fits> fits, List<Strategy> all) {
            double heap = heapFill * Runtime.getRuntime().maxMemory();
            double now = FullIndexPilot.usedAfterGc();
            double plays = 0;
            for (Strategy t : all) {
                // The play kept: its first-decision sums, reach and moves, its rankings and later
                // cells, and the post-split plays of one item's last round.
                plays += t.groups.count() * (Deals.COUNT * (ScoringStates.MOVES + 1) * 8.0 + Deals.COUNT * 40.0)
                        + 100.0 * 2 * t.groups.count() * 350;
            }
            double cache = keepSplits ? splitAllowance * scorer.st.size : 0;
            Item big = largest(fits, items(fits));
            double need = now + plays + cache + big.bytes;
            log("before the passes: %.2f GB held, about %.2f GB of plays and %.2f GB of split values to come, and the "
                    + "largest scoring item, %s, %.2f GB: %.2f GB of the %.2f GB allowed", now / 1e9, plays / 1e9,
                    cache / 1e9, big.name, big.bytes / 1e9, need / 1e9, heap / 1e9);
            if (need > heap) {
                throw new IllegalStateException(String.format("the run would not fit in the heap: the largest scoring "
                        + "item, %s, holds %.2f GB, beside %.2f GB held now, about %.2f GB of plays and %.2f GB of split "
                        + "values kept; %.2f GB in all, more than the %.2f GB of %.0f%% of the heap", big.name,
                        big.bytes / 1e9, now / 1e9, plays / 1e9, cache / 1e9, need / 1e9, heap / 1e9, 100 * heapFill));
            }
        }

        /**
         * An item's name in the checkpoint: its count's place, penetration and part. A replay
         * (FullIndexReplay) reads another run's parts by these names.
         */
        static String key(int[] it) {
            return "part-" + it[0] + "-" + it[1] + "-" + (it[2] == 0 ? "folds" : "halves");
        }

        /** A count's Result once every part is scored, its widening ratio set; null before. */
        private SystemScorer.Result complete(SystemScorer.Result[] parts, int k) {
            for (SystemScorer.Result r : parts) {
                if (r == null) {
                    return null;
                }
            }
            SystemScorer.Result r = SystemScorer.merge(Arrays.asList(parts));
            r.widen = SystemScorer.Widening.of(widen, missingWiden, counts.get(k));
            return r;
        }
    }

    /** The two fold fits, or the two half fits, of a count at a penetration. */
    static List<Strategy> strategiesOf(Fits fits, int pen, boolean halves) {
        Strategy[] two = halves ? fits.half[pen] : fits.fold[pen];
        return Arrays.asList(two[0], two[1]);
    }

    /**
     * One scoring item's parts, made for its last round, and what its batch is estimated to
     * hold for it: the parts' keys (and shares and starts for a count that tags by colour or
     * face), the compact Split slots, and its two strategies' values, a Hit value a part and a
     * Split value a part of a deal that can be split; and while it is scored, which reads the same
     * parts, an index by key (SystemScorer.ByKey) and the insurance parts. The rest of what scoring
     * holds is per state and small beside these.
     */
    static final class Prepared {
        final SystemScorer.Parts parts;
        final SystemScorer.SplitSlots slots;
        final int pen;
        final boolean halves;
        final double bytes;

        Prepared(SystemScorer scorer, Fits fits, int pen, boolean halves) {
            this.pen = pen;
            this.halves = halves;
            SystemScorer.Pen p = scorer.pen(pen);
            parts = scorer.parts(fits.count, p);
            slots = scorer.splitSlots(parts, p);
            bytes = bytes(parts, slots.count);
        }

        /**
         * An item's bytes from its parts and its parts of deals that can be split: the parts'
         * arrays, the slots (an int a part), two strategies' values (a double a part for Hit and
         * one a part split for Split), the scoring's index by key (two ints a part) and its
         * insurance parts (21 bytes a part of the 55 ace-up deals, about a tenth).
         */
        static double bytes(SystemScorer.Parts parts, int pairParts) {
            double n = parts.key.length;
            return partsBytes(parts) + 4.0 * n + 2 * (8.0 * n + 8.0 * pairParts) + 8.0 * n + 2.1 * n;
        }

        /** Gives the item's two strategies its parts and compact values; returns them. */
        List<Strategy> give(Fits fits) {
            List<Strategy> two = strategiesOf(fits, pen, halves);
            for (Strategy t : two) {
                t.prepareFinal(parts, new SystemScorer.PlayValues(slots));
            }
            return two;
        }

        static double partsBytes(SystemScorer.Parts parts) {
            return 4.0 * parts.key.length + (parts.start == null ? 0 : 4.0 * parts.start.length + 8.0 * parts.share.length);
        }
    }

    // ------------------------------------------------------------- the splits a last round valued

    /**
     * A split a strategy's last round valued (valuedSplits): its pair deal, the groups its parts'
     * keys fall in, first-decision and later (-1 before the later groups are formed), and its
     * post-split play's SplitValues fingerprint, the answer its split hands gave to every decision
     * they could reach. The print is the strategy's own array; it is not to be changed.
     */
    static final class ValuedSplit {
        final int deal;
        final int group;
        final int later;
        final byte[] print;

        ValuedSplit(int deal, int group, int later, byte[] print) {
            this.deal = deal;
            this.group = group;
            this.later = later;
            this.print = print;
        }
    }

    /**
     * The post-split plays a strategy's last round valued, until release lets them go: the play
     * in force's splits (Strategy.split, whose key is ((deal x 65536 + g1) x 65536 + g2 + 1)).
     * Every Split value the strategy is scored with was worked out under one of them. In deal,
     * group and later group order; refused before the last round is done.
     */
    static List<ValuedSplit> valuedSplits(Strategy t) {
        if (t.phase != Phase.DONE) {
            throw new IllegalStateException(t + " has not finished its last round");
        }
        List<ValuedSplit> out = new ArrayList<>();
        for (Map.Entry<Long, SplitEntry> e : t.now.splits.entrySet()) {
            long k = e.getKey();
            int g2 = (int) (k % 65536) - 1;
            int g1 = (int) (k / 65536 % 65536);
            int i = (int) (k / 65536 / 65536);
            if (((long) i * 65536 + g1) * 65536 + g2 + 1 != k) {
                throw new AssertionError("split key " + k);
            }
            out.add(new ValuedSplit(i, g1, g2, e.getValue().print));
        }
        out.sort((a, b) -> a.deal != b.deal ? Integer.compare(a.deal, b.deal)
                : a.group != b.group ? Integer.compare(a.group, b.group) : Integer.compare(a.later, b.later));
        return out;
    }
}
