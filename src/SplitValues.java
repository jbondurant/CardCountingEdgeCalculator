import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The value of splitting each pair of one state under a post-split play (SplitPlay), exactly,
 * as FULL_INDEX_PLAYS.md section 2 sets it out: ExactRound values the split with that play,
 * conditional on no dealer natural, as a stored value is.
 *
 * Many systems and count groups play a split of a given deal the same way, so values are kept
 * by a fingerprint of the play's answers to every decision a split of that deal can reach:
 * - the two-card decision of a split hand on (r, x) for every second card x, with Split
 *   offered and not where a resplit can be, each with the moves legal after a split;
 * - every later decision of a split hand that a hit there leads to, by total and softness,
 *   following the play's own hits. A later decision the play's answers never lead to cannot
 *   change the round, so it is left out, and two plays that agree on all the rest play every
 *   split of the deal the same way. This relies on the later decisions depending on the total,
 *   softness and up-card alone, as a SplitPlay's do.
 * Where the fingerprint is the chart's, the play splits as the stored values were made, and the
 * stored V(Split) is returned without valuing anything.
 *
 * One SplitValues serves one state on one thread: it keeps an ExactRound, which keeps the
 * dealer's distributions from the state's shoes between calls.
 */
final class SplitValues {

    /** Where a first decision sits in a fingerprint: by second card and whether Split is offered. */
    private static final int FIRST = 0;
    /** Where a later decision sits: after the first decisions, by softness and total. */
    private static final int LATER = 20;
    private static final int PRINT = LATER + 2 * 22;

    private final RoundRules rules;
    private final int[] state;
    private final double[] stored;
    private final ExactRound engine;
    private final RoundPolicy chart = BasicStrategy.chart();
    private final byte[][] chartPrint = new byte[Deals.COUNT][];
    private final Map<String, Double> kept;

    /** Splits valued with ExactRound, taken from the stored values, and found already valued. */
    int valued;
    int fromStore;
    int fromKept;

    /**
     * @param state  the cards left at the round's start, by rank
     * @param stored the state's stored values (StateStore.Record.values), or null to value every
     *               split, the chart's included
     */
    SplitValues(RoundRules rules, int[] state, double[] stored) {
        this(rules, state, stored, new HashMap<>(), new ExactRound(rules));
    }

    /**
     * The same, keeping values in the map given, which may come from an earlier SplitValues of
     * the same state and rules so that a play valued once is not valued again, and valuing with
     * the engine given, which a thread may reuse from state to state.
     */
    SplitValues(RoundRules rules, int[] state, double[] stored, Map<String, Double> kept, ExactRound engine) {
        if (stored != null && stored.length != StateStore.VALUES) {
            throw new IllegalArgumentException("a state stores " + StateStore.VALUES + " values");
        }
        this.rules = rules;
        this.state = state.clone();
        this.stored = stored;
        this.kept = kept;
        this.engine = engine;
    }

    /**
     * V(Split) for this pair under this play, conditional on no dealer natural; NaN if the state
     * cannot deal it.
     */
    double value(int deal, SplitPlay play) {
        int r = Deals.P1[deal];
        int up = Deals.UP[deal];
        if (r != Deals.P2[deal] || rules.maxHandsFor(r) < 2) {
            throw new IllegalArgumentException("deal " + deal + " cannot be split");
        }
        if (play.rank != r || play.up != up) {
            throw new IllegalArgumentException("a play for " + play.rank + "s against " + play.up
                    + " cannot value deal " + deal);
        }
        if (Deals.probability(state, deal) == 0) {
            return Double.NaN;
        }
        return value(deal, play, fingerprint(rules, deal, play));
    }

    /**
     * The same, given the play's fingerprint for the deal, which the caller has worked out
     * already; the caller has checked the deal can be split with this play and dealt.
     */
    double value(int deal, SplitPlay play, byte[] print) {
        int r = Deals.P1[deal];
        int up = Deals.UP[deal];
        if (stored != null) {
            if (chartPrint[deal] == null) {
                chartPrint[deal] = fingerprint(rules, deal, chart);
            }
            if (java.util.Arrays.equals(print, chartPrint[deal])) {
                fromStore++;
                return stored[StateValuer.slot(deal, PlayerMove.Split)];
            }
        }
        String key = deal + ":" + new String(print, StandardCharsets.ISO_8859_1);
        Double v = kept.get(key);
        if (v != null) {
            fromKept++;
            return v;
        }
        double value = engine.valueOfFirstMove(Deals.after(state, deal), r, r, up, PlayerMove.Split, play);
        valued++;
        kept.put(key, value);
        return value;
    }

    /**
     * The play's answers to every decision a split of this deal can reach, as ordinal + 1, and
     * 0 where a decision is not reached (see the class comment).
     */
    static byte[] fingerprint(RoundRules rules, int deal, RoundPolicy play) {
        int r = Deals.P1[deal];
        int up = Deals.UP[deal];
        byte[] print = new byte[PRINT];
        boolean[] followed = new boolean[2 * 22];
        boolean mayHit = r != 1 || rules.canHitSplitAces;
        boolean surrender = rules.surrender && rules.surrenderAfterSplit;
        for (int x = 1; x <= 10; x++) {
            boolean ace = r == 1 || x == 1;
            if (rules.blackjackOnSplitPairs && r + x == 11 && ace) {
                continue;   // paid at once, with no decision
            }
            int hard = r + x;
            int total = ace && hard <= 11 ? hard + 10 : hard;
            boolean soft = ace && hard <= 11;
            for (int offered = 0; offered <= 1; offered++) {
                boolean canSplit = offered == 1;
                if (canSplit && (x != r || rules.maxHandsFor(r) < 3)) {
                    continue;
                }
                RoundPolicy.Decision d = new RoundPolicy.Decision(total, soft, 2, x == r ? r : 0, up, true, true,
                        mayHit && total < 21, rules.doubleAfterSplit[r], canSplit, surrender);
                PlayerMove m = ask(play, d);
                print[FIRST + (x - 1) * 2 + offered] = (byte) (m.ordinal() + 1);
                if (m == PlayerMove.Hit) {
                    follow(play, up, hard, ace, print, followed);
                }
            }
        }
        return print;
    }

    /** Every later decision a hit on this hard total, with or without an ace, can lead to, and what the play answers. */
    private static void follow(RoundPolicy play, int up, int hard, boolean ace, byte[] print, boolean[] followed) {
        for (int y = 1; y <= 10; y++) {
            int h = hard + y;
            boolean a = ace || y == 1;
            if (h > 21) {
                continue;
            }
            int total = a && h <= 11 ? h + 10 : h;
            boolean soft = a && h <= 11;
            if (total >= 21 || followed[(a ? 22 : 0) + h]) {
                continue;   // on 21 only Stand is legal
            }
            followed[(a ? 22 : 0) + h] = true;
            RoundPolicy.Decision d = new RoundPolicy.Decision(total, soft, 3, 0, up, true, false, true, false, false,
                    false);
            PlayerMove m = ask(play, d);
            print[LATER + (soft ? 22 : 0) + total] = (byte) (m.ordinal() + 1);
            if (m == PlayerMove.Hit) {
                follow(play, up, h, a, print, followed);
            }
        }
    }

    private static PlayerMove ask(RoundPolicy play, RoundPolicy.Decision d) {
        PlayerMove m = play.choose(d);
        if (m == null || !d.isLegal(m)) {
            throw new IllegalStateException("the play chose " + m + " at " + d + ", where it is not legal");
        }
        return m;
    }
}
