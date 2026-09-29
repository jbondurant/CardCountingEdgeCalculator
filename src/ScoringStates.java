import java.util.List;

/**
 * The valued states of a run, prepared once so that every count is scored from the same
 * numbers (COUNTING_COMPARISON.md, sections 3 and 6).
 *
 * For each state: its shoe, round and depth, its weight 1/q, the cards dealt by kind, and,
 * from its stored values, what each first move of each deal adds to the round's value:
 *
 *   move[i x MOVES + m] = P(i) x ( -P(nat|i) + (1 - P(nat|i)) x V_i(m) )
 *
 * so the round's value under a play is the sum of one entry per deal, plus the player's
 * naturals, plus the insurance taken:
 *
 *   naturals          = sum over player naturals i of P(i) x (1 - P(nat|i)) x payout
 *   insurance[i]      = P(i) x 0.5 x (3 x P(hole ten|i) - 1), for the 55 ace-up deals
 *
 * which is RoundValue's formula term by term. The -P(i) x P(nat|i) part is the same for
 * every move of a deal, so the move that maximises this entry is the move that maximises
 * P(i) x (1 - P(nat|i)) x V_i(m), as section 6 fits it.
 *
 * Also kept per state: the round's value under the chart's first moves without insurance
 * (the true basic-strategy advantage the same-schedule bets are drawn from), and under the
 * best first move of every deal, with and without perfect insurance.
 *
 * States must come in shoe order, as a state file holds them, since the per-shoe sums of
 * the standard errors walk them a shoe at a time.
 */
final class ScoringStates {

    static final int MOVES = StateValuer.MOVES.length;
    /** Deals are numbered up-card first, so deals 0 to 54 are the ones with the ace up. */
    static final int ACE_UP = 55;

    /** Shoes 0 to shoes - 1 were dealt, valued states or not; a shoe's fold is its parity. */
    final int shoes;
    final int size;
    final int[] shoe;
    final int[] round;
    final int[] depth;
    final double[] q;
    /** 1/q: how many states a valued state stands for. */
    final double[] weight;
    final int[][] dealt;
    /** P(i) for each deal. */
    final double[][] chance;
    final double[][] move;
    final double[] naturals;
    final double[][] insurance;
    /** The round's value under the chart's first moves, without insurance. */
    final double[] chart;
    /** The round's value under the best first move of every deal, without insurance. */
    final double[] perfect;
    /** What insuring exactly when insurance pays adds to the round's value. */
    final double[] perfectInsurance;

    /** The chart's first move for each deal, as a move slot, or -1 for a player natural. */
    final int[] chartMove;
    /** Whether move slot m has a value in deal i: legal[i x MOVES + m]. */
    final boolean[] legal;

    /**
     * Prepares the records given, dealt from shoes 0 to shoes - 1 under these rules. The
     * list is consumed: each record is dropped once prepared, so a large file is not held
     * twice.
     */
    ScoringStates(List<StateStore.Record> records, int shoes, RoundRules rules) {
        this.shoes = shoes;
        this.size = records.size();
        shoe = new int[size];
        round = new int[size];
        depth = new int[size];
        q = new double[size];
        weight = new double[size];
        dealt = new int[size][];
        chance = new double[size][];
        move = new double[size][];
        naturals = new double[size];
        insurance = new double[size][];
        chart = new double[size];
        perfect = new double[size];
        perfectInsurance = new double[size];
        chartMove = chartMoves(rules);
        legal = new boolean[Deals.COUNT * MOVES];
        for (int i = 0; i < Deals.COUNT; i++) {
            for (int m = 0; m < MOVES; m++) {
                legal[i * MOVES + m] = StateValuer.legal(rules, i, StateValuer.MOVES[m]);
            }
        }
        int previousShoe = -1;
        for (int s = 0; s < size; s++) {
            StateStore.Record r = records.get(s);
            records.set(s, null);
            if (r.shoe < previousShoe || r.shoe >= shoes) {
                throw new IllegalArgumentException("state " + s + " is of shoe " + r.shoe
                        + ": states must come in shoe order, from shoes 0 to " + (shoes - 1));
            }
            previousShoe = r.shoe;
            int cards = 0;
            for (int k : r.dealt) {
                cards += k;
            }
            if (cards != r.depth) {
                throw new IllegalArgumentException("shoe " + r.shoe + " round " + r.round + ": depth " + r.depth
                        + " but " + cards + " cards dealt");
            }
            if (!(r.q > 0 && r.q <= 1)) {
                throw new IllegalArgumentException("shoe " + r.shoe + " round " + r.round + ": q " + r.q);
            }
            shoe[s] = r.shoe;
            round[s] = r.round;
            depth[s] = r.depth;
            q[s] = r.q;
            weight[s] = 1 / r.q;
            dealt[s] = r.dealt;
            prepare(s, r, rules.blackjackPayout);
        }
    }

    /** The states given of another run, dealt from shoes 0 to shoes - 1 once renumbered; nothing is copied. */
    private ScoringStates(ScoringStates of, int[] states, int[] renumbered, int shoes) {
        this.shoes = shoes;
        this.size = states.length;
        shoe = renumbered;
        round = new int[size];
        depth = new int[size];
        q = new double[size];
        weight = new double[size];
        dealt = new int[size][];
        chance = new double[size][];
        move = new double[size][];
        naturals = new double[size];
        insurance = new double[size][];
        chart = new double[size];
        perfect = new double[size];
        perfectInsurance = new double[size];
        chartMove = of.chartMove;
        legal = of.legal;
        for (int k = 0; k < size; k++) {
            int s = states[k];
            round[k] = of.round[s];
            depth[k] = of.depth[s];
            q[k] = of.q[s];
            weight[k] = of.weight[s];
            dealt[k] = of.dealt[s];
            chance[k] = of.chance[s];
            move[k] = of.move[s];
            naturals[k] = of.naturals[s];
            insurance[k] = of.insurance[s];
            chart[k] = of.chart[s];
            perfect[k] = of.perfect[s];
            perfectInsurance[k] = of.perfectInsurance[s];
        }
    }

    /**
     * One of parts disjoint sub-runs of equal size, as if it had been dealt alone: the shoes
     * are taken in pairs, an even shoe and the odd one after it, pair t going to sub-run t
     * mod parts, and each sub-run's shoes are numbered again from 0 with their parity kept,
     * so its folds are the run's folds. Pairs past the last whole round of parts are left out.
     */
    ScoringStates subRun(int part, int parts) {
        int pairs = shoes / 2 / parts;
        int n = 0;
        for (int s = 0; s < size; s++) {
            int t = shoe[s] / 2;
            if (t % parts == part && t / parts < pairs) {
                n++;
            }
        }
        int[] states = new int[n];
        int[] renumbered = new int[n];
        n = 0;
        for (int s = 0; s < size; s++) {
            int t = shoe[s] / 2;
            if (t % parts == part && t / parts < pairs) {
                states[n] = s;
                renumbered[n] = 2 * (t / parts) + (shoe[s] & 1);
                n++;
            }
        }
        return new ScoringStates(this, states, renumbered, 2 * pairs);
    }

    private void prepare(int s, StateStore.Record r, double payout) {
        int[] left = r.ranksLeft();
        double[] p = new double[Deals.COUNT];
        double[] mv = new double[Deals.COUNT * MOVES];
        double[] ins = new double[ACE_UP];
        double nat = 0;
        double chartValue = 0;
        double best = 0;
        double bestInsurance = 0;
        for (int i = 0; i < Deals.COUNT; i++) {
            p[i] = Deals.probability(left, i);
            double dn = Deals.dealerNatural(left, i);
            if (i < ACE_UP) {
                ins[i] = p[i] * RoundValue.insurance(left, i);
                bestInsurance += Math.max(0, ins[i]);
            }
            for (int m = 0; m < MOVES; m++) {
                mv[i * MOVES + m] = Double.NaN;
            }
            if (p[i] == 0) {
                continue;
            }
            if (Deals.playerNatural(i)) {
                nat += p[i] * (1 - dn) * payout;
                continue;
            }
            double top = Double.NEGATIVE_INFINITY;
            for (int m = 0; m < MOVES; m++) {
                double v = r.values[i * MOVES + m];
                if (Double.isNaN(v) == legal[i * MOVES + m]) {
                    throw new IllegalArgumentException("shoe " + r.shoe + " round " + r.round + ": deal " + i + " move "
                            + StateValuer.MOVES[m] + (Double.isNaN(v) ? " is legal but has no value" : " is not legal but has a value"));
                }
                if (!Double.isNaN(v)) {
                    mv[i * MOVES + m] = p[i] * (-dn + (1 - dn) * v);
                    top = Math.max(top, mv[i * MOVES + m]);
                }
            }
            chartValue += mv[i * MOVES + chartMove[i]];
            best += top;
        }
        chance[s] = p;
        move[s] = mv;
        insurance[s] = ins;
        naturals[s] = nat;
        chart[s] = chartValue + nat;
        perfect[s] = best + nat;
        perfectInsurance[s] = bestInsurance;
    }

    /** The chart's first move for every deal under these rules, as a move slot; -1 for a natural. */
    static int[] chartMoves(RoundRules rules) {
        RoundPolicy chart = BasicStrategy.chart();
        int[] out = new int[Deals.COUNT];
        for (int i = 0; i < Deals.COUNT; i++) {
            if (Deals.playerNatural(i)) {
                out[i] = -1;
                continue;
            }
            int p1 = Deals.P1[i];
            int p2 = Deals.P2[i];
            List<Integer> cards = java.util.Arrays.asList(p1, p2);
            int pair = p1 == p2 ? p1 : 0;
            RoundPolicy.Decision d = new RoundPolicy.Decision(ConcreteRound.total(cards), ConcreteRound.soft(cards), 2,
                    pair, Deals.UP[i], false, true, true, true,
                    pair != 0 && rules.maxHandsFor(pair) >= 2, rules.surrender);
            out[i] = StateValuer.slot(0, chart.choose(d));
        }
        return out;
    }

    /** The fold a state is scored in: its shoe's parity. */
    int fold(int s) {
        return shoe[s] & 1;
    }

    /** How many of shoes 0 to shoes - 1 are in fold f. */
    int shoesIn(int f) {
        return (shoes + 1 - f) / 2;
    }
}
