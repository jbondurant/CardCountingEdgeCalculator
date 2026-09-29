/**
 * The rules one round is played under, in the form ROUND_CONTRACT.md uses.
 *
 * Built from HouseRules for real runs, or directly for tests that need rule
 * combinations no preset has. Ranks are 1 to 10, with 1 the ace and 10 every
 * ten-valued card.
 */
public final class RoundRules {

    public final boolean hitsSoft17;
    public final double blackjackPayout;
    /** Most hands the round may hold when the pair split is aces, counting all of them. */
    public final int maxHandsAces;
    /** Most hands the round may hold when the pair split is not aces. */
    public final int maxHandsNotAces;
    public final boolean canHitSplitAces;
    /** doubleAfterSplit[r]: a hand split from rank r may double on its first decision. Index 0 unused. */
    public final boolean[] doubleAfterSplit;
    public final boolean blackjackOnSplitPairs;
    /** Late surrender is offered on the dealt hand's first decision. */
    public final boolean surrender;
    /** A split hand may also surrender on its first decision. Only counts if surrender does. */
    public final boolean surrenderAfterSplit;

    public RoundRules(boolean hitsSoft17, double blackjackPayout, int maxHandsAces,
                      int maxHandsNotAces, boolean canHitSplitAces, boolean[] doubleAfterSplit,
                      boolean blackjackOnSplitPairs, boolean surrender,
                      boolean surrenderAfterSplit) {
        if (doubleAfterSplit.length != 11) {
            throw new IllegalArgumentException("doubleAfterSplit is indexed by rank 1 to 10");
        }
        if (maxHandsAces < 1 || maxHandsNotAces < 1) {
            throw new IllegalArgumentException("a round always has at least one hand");
        }
        this.hitsSoft17 = hitsSoft17;
        this.blackjackPayout = blackjackPayout;
        this.maxHandsAces = maxHandsAces;
        this.maxHandsNotAces = maxHandsNotAces;
        this.canHitSplitAces = canHitSplitAces;
        this.doubleAfterSplit = doubleAfterSplit.clone();
        this.blackjackOnSplitPairs = blackjackOnSplitPairs;
        this.surrender = surrender;
        this.surrenderAfterSplit = surrenderAfterSplit;
    }

    /**
     * The same rules HouseRules describes. Refuses a ruleset the simulator itself
     * refuses, since the contract models the same game: with a peek, one hand dealt,
     * and so on.
     */
    public static RoundRules from(HouseRules hr) {
        hr.requirePlayable();
        boolean[] das = new boolean[11];
        for (Rank rank : hr.ranksThatCanBeDoubledDownAfterSplit) {
            das[rankIndex(rank)] = true;
        }
        return new RoundRules(hr.hitsOnSoft17, hr.blackjackPayout, 1 + hr.numSplitsAces,
                1 + hr.numSplitsNotAces, hr.canHitAfterSplittingAces, das,
                hr.blackjackOnSplitPairs, hr.offersSurrender(), hr.offersSurrenderAfterSplit());
    }

    /** The contract's rank for a card: 1 for an ace, 10 for any ten-valued card. */
    public static int rankIndex(Rank rank) {
        return rank == Rank.ACE ? 1 : rank.getRankpoints();
    }

    public int maxHandsFor(int pairRank) {
        return pairRank == 1 ? maxHandsAces : maxHandsNotAces;
    }
}
