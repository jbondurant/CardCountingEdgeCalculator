/**
 * The expected value of one round, as ROUND_CONTRACT.md defines it.
 *
 * Two implementations exist so that each checks the other: ExactRound, built to be fast
 * on an eight-deck shoe, and BruteForceRound, built to be obviously right on a small
 * one.
 */
public interface RoundValuer {

    /**
     * The expected payoff, in units of the original bet and conditional on the dealer
     * not having a natural, of playing {@code first} as the dealt hand's first decision
     * and every later decision by {@code policy}.
     *
     * @param shoe the undealt cards, {@code shoe[r]} of rank r for r in 1..10, with the
     *             player's two cards and the up-card already removed. Not modified.
     */
    double valueOfFirstMove(int[] shoe, int p1, int p2, int up, PlayerMove first, RoundPolicy policy);
}
