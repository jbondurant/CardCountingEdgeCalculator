package sim;

/**
 * A count's keys, as SHOE_SPEC.md B2.2 defines them: whole numbers from integers, a ratio rounded
 * once, from the double nearest the exact ratio, to the nearest whole number with ties toward
 * zero. One object holds one row's two counts: the tags its play keys count and the tags its bet
 * key counts, each at its whole-number scale, with the mode, divisor, deck sums and ace weight.
 *
 * <p>Why each formula looks as it does. A true count is the running count per deck left; here it
 * is kept as one integer ratio, 52 × (running count) over (cards left × divisor), so no rounding
 * happens before the last step. An unbalanced count's running count is first corrected by what
 * the cards dealt would add on average ({@code deckSum × dealt / 52}, folded into the numerator
 * as {@code − deckSum × dealt}). The ace side count adds {@code M × (52 × aces left − 4 × cards
 * left)}: M times the aces left beyond a full shoe's share, per deck left.
 */
public final class Keys {

    final int[] tags;
    final int[] betTags;
    final boolean trueCount;
    final int divisor;
    final long deckSum;
    final long betDeckSum;
    final int ace;
    /** Planted bug {@code truncate}: ratios truncated toward zero instead of rounded. */
    final boolean truncate;

    Keys(int[] tags, int[] betTags, boolean trueCount, int divisor, long deckSum, long betDeckSum, int ace,
         boolean truncate) {
        if (tags.length != Shoe.KINDS || betTags.length != Shoe.KINDS || divisor < 1) {
            throw new IllegalArgumentException("26 tags each, divisor at least 1");
        }
        this.tags = tags.clone();
        this.betTags = betTags.clone();
        this.trueCount = trueCount;
        this.divisor = divisor;
        this.deckSum = deckSum;
        this.betDeckSum = betDeckSum;
        this.ace = ace;
        this.truncate = truncate;
        if (deckSum != sumTimesTwo(tags) || betDeckSum != sumTimesTwo(betTags)) {
            throw new IllegalArgumentException("a deck sum is not twice the sum of its tags (B2.1)");
        }
    }

    private static long sumTimesTwo(int[] tags) {
        long s = 0;
        for (int t : tags) {
            s += 2L * t;
        }
        return s;
    }

    /** B2.2's round(n, d): (int) (signum(x) · ceil(|x| − 0.5)), x = (double) n / (double) d. */
    static int round(long n, long d) {
        double x = (double) n / (double) d;
        return (int) (Math.signum(x) * Math.ceil(Math.abs(x) - 0.5));
    }

    private int ratio(long n, long d) {
        if (truncate) {
            return (int) ((double) n / (double) d);
        }
        return round(n, d);
    }

    /** The running count of the cards dealt, by kind, under these tags. */
    static long running(int[] tags, int[] dealt) {
        long rc = 0;
        for (int k = 0; k < Shoe.KINDS; k++) {
            rc += (long) tags[k] * dealt[k];
        }
        return rc;
    }

    /** The bet key at a round start, from the tallies dealt and the depth. */
    public int betKey(int[] dealt, int depth) {
        long rcb = running(betTags, dealt);
        if (!trueCount) {
            return divisor == 1 ? (int) rcb : ratio(rcb, divisor);
        }
        long left = Shoe.CARDS - depth;
        long aces = 2L * Shoe.PER_KIND - dealt[0] - dealt[1];
        long n = 52 * rcb - betDeckSum * depth + (long) ace * (52 * aces - 4 * left);
        return ratio(n, left * divisor);
    }

    /** The play count's running count of the tallies, computed once a round start. */
    public long playRunning(int[] dealt) {
        return running(tags, dealt);
    }

    /**
     * The key at a decision: rc the play count's running count at the round start, seen the kinds
     * of this round's cards seen so far, the first s of them counted, depth the round start's.
     */
    public int playKey(long rc, int depth, int[] seen, int s) {
        long t = rc;
        for (int i = 0; i < s; i++) {
            t += tags[seen[i]];
        }
        if (!trueCount) {
            return divisor == 1 ? (int) t : ratio(t, divisor);
        }
        return ratio(52 * t - deckSum * (depth + s), (long) (Shoe.CARDS - depth - s) * divisor);
    }
}
