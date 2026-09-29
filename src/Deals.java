/**
 * The 550 initial deals of a round: the player's two cards as an unordered pair of ranks
 * p1 <= p2, and the dealer's up-card, ranks 1 to 10 as ROUND_CONTRACT.md has them. Deals are
 * numbered up-card first, then p1, then p2, so deal i is always the same hand.
 *
 * This is the one place the chance of a deal from a shoe is worked out, so the valuation of
 * a state and the scoring of a counting system read the same numbers.
 */
public final class Deals {

    public static final int COUNT = 550;
    public static final int[] P1 = new int[COUNT];
    public static final int[] P2 = new int[COUNT];
    public static final int[] UP = new int[COUNT];

    static {
        int i = 0;
        for (int up = 1; up <= 10; up++) {
            for (int p1 = 1; p1 <= 10; p1++) {
                for (int p2 = p1; p2 <= 10; p2++) {
                    P1[i] = p1;
                    P2[i] = p2;
                    UP[i] = up;
                    i++;
                }
            }
        }
    }

    private Deals() {
    }

    public static int index(int p1, int p2, int up) {
        int lo = Math.min(p1, p2);
        int hi = Math.max(p1, p2);
        int before = 0;
        for (int r = 1; r < lo; r++) {
            before += 11 - r;
        }
        return (up - 1) * 55 + before + (hi - lo);
    }

    /** The player holds an ace and a ten. */
    public static boolean playerNatural(int i) {
        return P1[i] == 1 && P2[i] == 10;
    }

    /**
     * The chance that a round dealt from this shoe gives the player p1 and p2, in either
     * order, and the dealer the up-card: the cards come without replacement, and the order
     * they are dealt in does not change the chance of the three together.
     */
    public static double probability(int[] shoe, int i) {
        int n = 0;
        for (int r = 1; r <= 10; r++) {
            n += shoe[r];
        }
        if (n < 3) {
            return 0;
        }
        int[] left = shoe.clone();
        double p = P1[i] == P2[i] ? 1 : 2;
        int[] ranks = {P1[i], P2[i], UP[i]};
        for (int k = 0; k < 3; k++) {
            if (left[ranks[k]] <= 0) {
                return 0;
            }
            p *= (double) left[ranks[k]] / (n - k);
            left[ranks[k]]--;
        }
        return p;
    }

    /** The shoe the hole card and every draw come from once deal i is out. */
    public static int[] after(int[] shoe, int i) {
        int[] left = shoe.clone();
        left[P1[i]]--;
        left[P2[i]]--;
        left[UP[i]]--;
        return left;
    }

    /**
     * The chance that the hole card gives the dealer a natural, once deal i is out: the
     * tens left when the up-card is an ace, the aces left when it is a ten. With an ace up
     * this is also the chance the insurance bet wins.
     */
    public static double dealerNatural(int[] shoe, int i) {
        int up = UP[i];
        if (up != 1 && up != 10) {
            return 0;
        }
        int[] left = after(shoe, i);
        int n = 0;
        for (int r = 1; r <= 10; r++) {
            n += left[r];
        }
        if (n <= 0) {
            return 0;
        }
        return (double) left[up == 1 ? 10 : 1] / n;
    }
}
