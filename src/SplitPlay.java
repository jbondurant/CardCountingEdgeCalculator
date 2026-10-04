/**
 * The play of split hands for one count group, as FULL_INDEX_PLAYS.md section 1 sets it: the
 * count does not move with the cards drawn to split hands, so every decision after a split of
 * (r, r) against an up-card is read from the system's tables at the group of the deal's play
 * key:
 * - a split hand's later decisions, hit or stand, from the later-play table at its total,
 *   softness and up-card;
 * - a split hand's two-card decision on (r, x), from the first-decision fit of deal (r, x, up),
 *   restricted to the moves legal after a split;
 * - a resplit, from the fit of (r, r, up), restricted the same way.
 *
 * A first-decision fit is given as the deal's legal first moves in order of fitted value, best
 * first, and the restriction plays the first of them that is legal on the split hand: the
 * best legal move by the same fit. A split hand of an ace and a ten is a 21, which no deal's
 * fit covers since dealt it is a natural, so it stands.
 *
 * It is then a deterministic function of the decision, as ROUND_CONTRACT.md requires, and
 * ExactRound values a split under it exactly (SplitValues). It is asked only about split
 * hands; anything else is a mistake and throws.
 */
final class SplitPlay implements RoundPolicy {

    /** The later-play table at one group and up-card: whether to stand on a total, soft or not. */
    interface HitStand {
        boolean stands(int total, boolean soft);
    }

    /** The first-decision fit at one group: each deal's legal first moves, best first. */
    interface FirstMoves {
        PlayerMove[] ranked(int deal);
    }

    final int rank;
    final int up;
    private final HitStand later;
    private final FirstMoves first;

    SplitPlay(int rank, int up, HitStand later, FirstMoves first) {
        if (rank < 1 || rank > 10 || up < 1 || up > 10 || later == null || first == null) {
            throw new IllegalArgumentException("a split play needs a rank, an up-card and both tables");
        }
        this.rank = rank;
        this.up = up;
        this.later = later;
        this.first = first;
    }

    @Override
    public PlayerMove choose(Decision d) {
        if (!d.fromSplit || d.upCard != up) {
            throw new IllegalStateException("a play for splitting " + rank + "s against " + up + " was asked " + d);
        }
        if (!d.firstDecision) {
            return d.canHit && !later.stands(d.total, d.soft) ? PlayerMove.Hit : PlayerMove.Stand;
        }
        int second = secondCard(d.total, d.soft);
        if (rank + second == 11 && (rank == 1 || second == 1)) {
            return PlayerMove.Stand;
        }
        for (PlayerMove m : first.ranked(Deals.index(rank, second, up))) {
            if (d.isLegal(m)) {
                return m;
            }
        }
        return PlayerMove.Stand;
    }

    /** The second card of a split hand of this rank with this two-card total. */
    private int secondCard(int total, boolean soft) {
        int second;
        if (rank == 1) {
            second = total - 11;
        } else {
            second = soft ? 1 : total - rank;
        }
        if (second < 1 || second > 10) {
            throw new IllegalStateException("no second card makes " + (soft ? "soft " : "hard ") + total
                    + " with a split " + rank);
        }
        return second;
    }
}
