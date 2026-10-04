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
     *
     * Pass 0's play, and a clean-up of it, has no rankings: it is the stored first-decision fit
     * with the chart after the first move, so a split hand's two-card decisions are the chart's
     * there, as the stored V(Split) has them. Every refit gives rankings, which split hands read
     * from then on.
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
    static final class PassRecord {
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
        final SystemScorer.Parts parts;
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
        /** Each deal's moves ranked as the chart plays them, which split hands read under a play without rankings. */
        private final PlayerMove[][] chartRanks;
        /** What the fitted insurance adds to each state's round value: insurance is fitted once, as no later play moves it. */
        final double[] insurance;
        /** The Hit and Split values of every state of the penetration, under the play in force (finally, the play kept). */
        final SystemScorer.PlayValues values;

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
            this.scorer = scorer;
            this.st = scorer.st;
            this.count = count;
            this.pen = pen;
            this.fold = fold;
            this.half = half;
            this.p = scorer.pen(pen);
            this.parts = parts;
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
            values = initial == null ? new SystemScorer.PlayValues(parts.key.length) : initial;
            if (values.hit.length != parts.key.length) {
                throw new IllegalArgumentException("values for " + values.hit.length + " parts, not " + parts.key.length);
            }
            SystemScorer.Fit f0 = scorer.fit(parts, p, use, groups, table, w);
            now = new Policy(f0, null, null, 0, 0);
            chartRanks = new PlayerMove[Deals.COUNT][];
            for (int i = 0; i < Deals.COUNT; i++) {
                if (!Deals.playerNatural(i)) {
                    chartRanks[i] = chartRanking(scorer.rules(), i);
                }
            }
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
                PlayerMove[][] rank = pol.ranking == null ? chartRanks : pol.ranking[g1];
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
            } else if (phase == Phase.FINAL) {
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
            return phase == Phase.FINAL ? new Acc(later.count() * CELLS, sign, centre) : new Acc(0, null, null);
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
        }

        /** Walks one state under the play in force, as its role asks. */
        void visit(int j, int s, PlayerTree.Counted counted, int role, Worker wk) {
            Policy pol = now;
            PlayerTree.Walk walk = counted.walk(wk.play(this), PlayerTree.Mode.CONTESTED);
            record(pol, j, s, counted, walk, wk);
            if (role == VALUE) {
                return;
            }
            int base = j * Deals.COUNT;
            walk.reach((deal, part) -> pol.first.move[table[parts.key[parts.from(base + deal) + part] - parts.minKey]][deal]
                    == HIT, w[j]);
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
                        values.split[from + q] = e.chart ? mv[i * MOVES + SPLIT] : wk.units(i, wk.split(i, e));
                    }
                }
            }
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
            double ev = trainingEv(now);
            if (passes == 0) {
                later = laterGroups();
                now = now.withStands(chartStands(later.count()));
            }
            double[][] gr = sums.byGroup(later);
            lastSums = gr;
            SystemScorer.Fit f = scorer.fit(parts, p, use, groups, table, w, values);
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
            double ev = trainingEv(now);
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
                        values.split[x] = mv[i * MOVES + SPLIT];
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
            PlayerMove[][] rank = pol.ranking == null ? chartRanks : pol.ranking[g];
            return SplitValues.fingerprint(rules, i, new SplitPlay(r, up,
                    (total, soft) -> PlayerTree.CHART.stands(total, soft, up, 0), d -> rank[d]));
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

    /**
     * A finished strategy as the report reads it: how its passes went, its later groups and
     * answers, each later cell's one-flip gain, standard error and frequency under the play
     * kept (NaN where no training state reaches it), the cells left to the chart, and for a
     * half fit the in-sample check. It holds none of the strategy's per-state values, so a
     * batch's strategies can be let go once their counts are scored.
     */
    static final class Summary {
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

        Fits(CountKeys.Count count) {
            this.count = count;
        }

        boolean has(int pen) {
            return fold[pen][0] != null && fold[pen][1] != null;
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
        List<Fits> out = new ArrayList<>();
        for (CountKeys.Count c : counts) {
            Fits fits = new Fits(c);
            for (int pen : pens) {
                SystemScorer.Pen p = scorer.pen(pen);
                SystemScorer.Parts parts = scorer.parts(c, p);
                SystemScorer.InsuranceParts ip = scorer.insuranceParts(parts, p);
                for (int f = 0; f < 2; f++) {
                    fits.fold[pen][f] = new Strategy(scorer, parts, ip, c, pen, f, false, p.weight, null, null);
                    if (halves) {
                        fits.half[pen][f] = new Strategy(scorer, parts, ip, c, pen, f, true, p.weight, null, null);
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
    static final class RoundStats {
        int round;
        int strategies;
        int states;
        long walks;
        long splitAsks;
        long splitChart;
        long splitKept;
        long splitValued;
        /** Pair deals of a state asked about in the round, and the distinct post-split plays among their asks. */
        long pairDeals;
        long distinctPlays;
        int mostPlays;
        long pairDealsWithMore;
        double treeCpu;
        double walkCpu;
        double splitCpu;
        double wall;

        synchronized void add(RoundStats o) {
            states += o.states;
            walks += o.walks;
            splitAsks += o.splitAsks;
            splitChart += o.splitChart;
            splitKept += o.splitKept;
            splitValued += o.splitValued;
            pairDeals += o.pairDeals;
            distinctPlays += o.distinctPlays;
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

        void beginState(int s) {
            state = s;
            dn = null;
            splitValues = null;
            prints.clear();
        }

        void endState() {
            for (Set<String> set : prints.values()) {
                stats.pairDeals++;
                stats.distinctPlays += set.size();
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
                Map<String, Double> kept = splitCache == null ? new HashMap<>()
                        : splitCache.computeIfAbsent(state, k -> new HashMap<>());
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
    final Trees trees;
    final int threads;
    /** Split values kept per state from round to round, or null to keep them for one state's visit only. */
    final Map<Integer, Map<String, Double>> splitCache;
    private final byte[][] chartPrints = new byte[Deals.COUNT][];
    /** Each state's round value with every decision of the dealt hand played best and split hands at the chart; NaN until worked out. */
    final double[] perfect;
    final List<RoundStats> rounds = new ArrayList<>();

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
        this.splitCache = keepSplits ? new ConcurrentHashMap<>() : null;
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

    /** Runs rounds until every strategy given is done. */
    void run(List<Strategy> strategies) throws Exception {
        if (strategies.isEmpty()) {
            return;
        }
        // Strategies by count, so a state's count tree is set up once for all of a count's.
        List<List<Strategy>> byCount = new ArrayList<>();
        Map<CountKeys.Count, List<Strategy>> index = new IdentityHashMap<>();
        for (Strategy t : strategies) {
            if (t.st != st) {
                throw new IllegalArgumentException(t + " is fitted on other states");
            }
            index.computeIfAbsent(t.count, k -> {
                List<Strategy> l = new ArrayList<>();
                byCount.add(l);
                return l;
            }).add(t);
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
                    if (!t.done()) {
                        active.add(t);
                    }
                }
                if (active.isEmpty()) {
                    break;
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
                        for (int x = first; x < shoes.size(); x += threads) {
                            int[] range = shoes.get(x);
                            for (int s = range[0]; s < range[1]; s++) {
                                state(s, byCount, local, wk);
                            }
                            wk.endShoe();
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

    /** What scoreAll gives. */
    static final class Scored {
        /** Each count's Result, in the order the counts were given. */
        final List<SystemScorer.Result> results = new ArrayList<>();
        /** The full-scope perfect row's value of each state before insurance, NaN where not worked out; null if not asked for. */
        double[] perfect;
        /** Each batch's rounds, in order. */
        final List<List<RoundStats>> rounds = new ArrayList<>();
    }

    /**
     * Scores every count given both ways in one run of the scorer each, playing its first
     * decisions only and playing its full-index fit, on the same resamples
     * (SystemScorer.score), with its ace side-count variant where it has one.
     *
     * The counts go in batches of the size given. A batch's strategies, both folds and the
     * learning curve's half fits at each penetration given, are fitted in one run of passes,
     * each state's trees built from the trees given (afresh each round, for the full run) and
     * set up once a round for every strategy of a count. Each count of the batch is then
     * scored and its strategies let go, so the memory the values take is one batch's
     * (FULL_INDEX_PLAYS.md section 3). A count's Result.widen is set from the map given, 1 where
     * it has none. The full-scope perfect row is worked out in the first batch's run when asked.
     * Split values are kept per state from round to round when keepSplits, which is worth it
     * when the trees are kept too.
     */
    static Scored scoreAll(SystemScorer scorer, Trees trees, List<CountKeys.Count> counts, int[] pens, int batch,
                           int threads, boolean keepSplits, boolean perfect, Map<CountKeys.Count, Double> widen)
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
                SystemScorer.Result r = scorer.score(c, CountKeys.sideCountVariant(c), fits.get(k));
                Double ratio = widen == null ? null : widen.get(c);
                r.widen = ratio == null ? 1 : ratio;
                out.results.add(r);
                fits.set(k, null);
            }
        }
        return out;
    }
}
