/**
 * What a round is worth in one state, for a player who picks a first move for each deal and
 * decides whether to insure, from the state's stored values (COUNTING_COMPARISON.md,
 * section 3).
 *
 *   EV = sum over deals of P(deal) x [ P(nat) x (natural ? 0 : -1)
 *                                      + (1 - P(nat)) x (natural ? payout : V(move)) ]
 *        + sum over insured ace-up deals of P(deal) x 0.5 x (3 x P(hole ten) - 1)
 *
 * A dealer natural takes only the original bet, since the dealer peeks before any double
 * or split, and pushes a player natural. Insurance is half a bet at 2 to 1, settled on its
 * own; even money on a natural is the same bet. With an ace up, P(hole ten) is P(nat).
 */
final class RoundValue {

    /** The first move for a deal, as a slot of StateValuer.MOVES, or -1 for a natural. */
    interface Play {
        int move(int deal);
    }

    /** Whether to insure when dealt this hand against an ace. */
    interface Insure {
        boolean insure(int deal);
    }

    private RoundValue() {
    }

    /** The round's value in units of the bet. */
    static double of(int[] shoe, double[] values, double payout, Play play, Insure insure) {
        double ev = 0;
        for (int i = 0; i < Deals.COUNT; i++) {
            double p = Deals.probability(shoe, i);
            if (p == 0) {
                continue;
            }
            ev += p * dealValue(shoe, values, payout, i, play.move(i));
            if (Deals.UP[i] == 1 && insure.insure(i)) {
                ev += p * insurance(shoe, i);
            }
        }
        return ev;
    }

    /** What deal i is worth, played with move slot m, before insurance. */
    static double dealValue(int[] shoe, double[] values, double payout, int i, int m) {
        double nat = Deals.dealerNatural(shoe, i);
        if (Deals.playerNatural(i)) {
            return (1 - nat) * payout;
        }
        double v = values[i * StateValuer.MOVES.length + m];
        if (Double.isNaN(v)) {
            throw new IllegalArgumentException("move " + StateValuer.MOVES[m] + " has no value in deal " + i);
        }
        return -nat + (1 - nat) * v;
    }

    /** The insurance bet's value per unit of the original bet, dealt hand i against an ace. */
    static double insurance(int[] shoe, int i) {
        return 0.5 * (3 * Deals.dealerNatural(shoe, i) - 1);
    }
}
