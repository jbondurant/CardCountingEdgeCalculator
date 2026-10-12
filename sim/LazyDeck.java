package sim;

/**
 * The cards left at a state, dealt in a fresh uniform order (check P2's re-deal), drawn only as
 * far as the round needs them: position i, when first asked for, takes a uniform pick of the
 * cards not yet placed, which is a Fisher–Yates shuffle from the front, stopped early. The order
 * depends only on the cards left and the generator, so every instance that plays the same
 * re-deal sees the same cards. {@link #redeal} restores the cards to kind order by undoing the
 * swaps, in time proportional to the cards used, not to the cards left.
 */
public final class LazyDeck implements Round.Deck {

    private final int[] pool = new int[Shoe.CARDS];
    private final int[] swappedWith = new int[Shoe.CARDS];
    private int n;
    private int filled;
    private Xoshiro rnd;

    /** The cards left after these tallies, in kind order. */
    public void state(int[] dealt) {
        n = 0;
        for (int k = 0; k < Shoe.KINDS; k++) {
            for (int c = dealt[k]; c < Shoe.PER_KIND; c++) {
                pool[n++] = k;
            }
        }
        filled = 0;
    }

    /** A fresh order of the same cards, from this generator. */
    public void redeal(Xoshiro generator) {
        for (int i = filled - 1; i >= 0; i--) {
            int j = swappedWith[i];
            int t = pool[i];
            pool[i] = pool[j];
            pool[j] = t;
        }
        filled = 0;
        rnd = generator;
    }

    public int left() {
        return n;
    }

    @Override
    public int kind(int i) {
        if (i >= n) {
            throw new IllegalStateException("the state's cards ran out at card " + i);
        }
        while (filled <= i) {
            int j = filled + rnd.nextInt(n - filled);
            swappedWith[filled] = j;
            int t = pool[filled];
            pool[filled] = pool[j];
            pool[j] = t;
            filled++;
        }
        return pool[i];
    }
}
