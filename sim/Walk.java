package sim;

/**
 * The walk of a shoe (SHOE_SPEC.md A5): from depth 0, a round starts while fewer than the cut's
 * cards have been dealt; at each round start q is computed from the cards left and the valuation
 * stream makes its one draw, valued or not; then the chart plays the round (A4) and every card it
 * took enters the tallies. The 70% and 75% games are the same walk's round starts below 291 and
 * 312 cards. This is check D1's half that the simulator does alone: it chooses the valued set
 * itself, from its own shuffle and its own rounds.
 */
public final class Walk {

    /** A deck that is a fixed array of kinds. */
    public static final class ArrayDeck implements Round.Deck {
        private final int[] cards;

        public ArrayDeck(int[] cards) {
            this.cards = cards;
        }

        @Override
        public int kind(int i) {
            if (i >= cards.length) {
                throw new IllegalStateException("the shoe ran out at card " + i);
            }
            return cards[i];
        }
    }

    /** Told of every round start, in order. dealt is the walk's own array: copy it to keep it. */
    public interface Visitor {
        void start(int shoe, int round, int depth, int[] dealt, double q, boolean valued);
    }

    /** What the walk of many shoes adds up to, at each of the three cuts. */
    public static final class Totals {
        public final long[] rounds = new long[3];
        public final long[] valued = new long[3];
        public final double[] weight = new double[3];
        /** The fewest cards left at any round start, and the most cards any round took. */
        public int fewestLeft = Shoe.CARDS;
        public int longestRound;

        void add(int depth, double q, boolean valued) {
            for (int p = 0; p < 3; p++) {
                if (depth < Shoe.CUTS[p]) {
                    rounds[p]++;
                    if (valued) {
                        this.valued[p]++;
                        weight[p] += 1 / q;
                    }
                }
            }
            fewestLeft = Math.min(fewestLeft, Shoe.CARDS - depth);
        }
    }

    private Walk() {
    }

    /**
     * Walks shoe number {@code shoe} of the main file's kind (SplittableRandom from Shoe.mix) to
     * {@code cut}, the chart playing every round, and returns the depth at which it stopped.
     */
    public static int walkShoe(long seed, int shoe, int cut, double q0, double u0, Round round,
                               Round.Player chart, Visitor visitor, Totals totals) {
        int[] cards = Shoe.shuffled(seed, shoe);
        ArrayDeck deck = new ArrayDeck(cards);
        java.util.SplittableRandom valuation = new java.util.SplittableRandom(Shoe.mix(seed, shoe, 1));
        int[] dealt = new int[Shoe.KINDS];
        int depth = 0;
        int number = 0;
        while (depth < cut) {
            double q = Shoe.rate(Shoe.ranksLeft(dealt), q0, u0);
            boolean valued = valuation.nextDouble() < q;
            if (visitor != null) {
                visitor.start(shoe, number, depth, dealt, q, valued);
            }
            if (totals != null) {
                totals.add(depth, q, valued);
            }
            round.play(deck, depth, chart, -1);
            int next = round.next;
            if (totals != null) {
                totals.longestRound = Math.max(totals.longestRound, next - depth);
            }
            for (int i = depth; i < next; i++) {
                dealt[cards[i]]++;
            }
            depth = next;
            number++;
        }
        return depth;
    }

    /** Walks shoes {@code from} to {@code to - 1} of the main seed to the 80% cut and adds them up. */
    public static Totals mainTotals(int from, int to, Round round, Round.Player chart) {
        Totals totals = new Totals();
        for (int shoe = from; shoe < to; shoe++) {
            walkShoe(Shoe.SEED_MAIN, shoe, Shoe.CUTS[2], Shoe.Q0, Shoe.U0, round, chart, null, totals);
        }
        return totals;
    }
}
