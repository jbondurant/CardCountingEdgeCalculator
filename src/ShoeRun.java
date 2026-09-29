import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Deals eight-deck shoes to the cut card, round by round, and records the state of the
 * shoe at the start of every round.
 *
 * A card is one of 26 kinds: its rank, with J, Q and K apart from the ten, and its colour,
 * since Red Seven and the KISS counts depend on both. Kind k is rank13 * 2 + colour, where
 * rank13 runs A, 2, ..., 10, J, Q, K from 0 and colour is 1 for hearts and diamonds.
 *
 * Shoe i is shuffled by its own generator, seeded from the run's seed and i, so any shoe
 * can be dealt again alone and a stopped run resumes with the same cards. Rounds are played
 * by a fixed policy (basic strategy), so the cards each round takes do not depend on which
 * counting system is being scored.
 *
 * Valuing a state is expensive, so only some are valued, each with its own chance q, and a
 * valued state stands for 1/q states. Unusual shoes matter most once bets vary, so q grows
 * with how far the cards left are from a full shoe's proportions:
 *
 *   u = (1/N) x sum over ranks of (n_r - N p_r)^2 / (N p_r),   q = min(1, q0 x (1 + u / u0))
 *
 * where N is the cards left, n_r those of rank r, and p_r the rank's share of a full shoe.
 * u favours no counting system: it is the same for a shoe rich in tens and for one rich in
 * fives. Whether a state is valued is decided by a uniform draw from a second generator of
 * the shoe's own, one draw per state whether or not it is valued, so the cards never depend
 * on q, and a run with a larger q0 values every state a smaller one did.
 */
final class ShoeRun {

    static final int DECKS = 8;
    static final int CARDS = 52 * DECKS;
    static final int KINDS = 26;

    private final long seed;
    private final double q0;
    private final double u0;
    private final int cut;
    private final RoundRules rules;
    private final RoundPolicy play;

    /** The shoe at the start of one round. */
    static final class State {
        final int shoe;
        final int round;
        /** Cards dealt from the shoe before this round. */
        final int depth;
        /** How many cards of each kind have been dealt, and so seen, before this round. */
        final int[] dealt;
        /** The chance this state was valued. */
        final double q;
        final boolean valued;

        State(int shoe, int round, int depth, int[] dealt, double q, boolean valued) {
            this.shoe = shoe;
            this.round = round;
            this.depth = depth;
            this.dealt = dealt;
            this.q = q;
            this.valued = valued;
        }

        /** The cards left, by the contract's ranks 1 to 10. */
        int[] ranksLeft() {
            return ShoeRun.ranksLeft(dealt);
        }
    }

    /**
     * @param q0  the chance of valuing a state whose cards are in a full shoe's proportions
     * @param u0  how unusual a shoe must be to double that chance; 0 values every state at q0
     * @param cut rounds are dealt while fewer than this many cards have been dealt
     */
    ShoeRun(long seed, double q0, double u0, int cut, RoundRules rules, RoundPolicy play) {
        if (q0 <= 0 || q0 > 1 || u0 < 0) {
            throw new IllegalArgumentException("q0 must be in (0, 1] and u0 at least 0, got " + q0 + ", " + u0);
        }
        if (cut < 4 || cut > CARDS) {
            throw new IllegalArgumentException("cut " + cut);
        }
        this.seed = seed;
        this.q0 = q0;
        this.u0 = u0;
        this.cut = cut;
        this.rules = rules;
        this.play = play;
    }

    static int rankOf(int kind) {
        int rank13 = kind / 2;
        return rank13 >= 9 ? 10 : rank13 + 1;
    }

    static boolean red(int kind) {
        return kind % 2 == 1;
    }

    static Rank simulatorRank(int kind) {
        return Rank.values()[kind / 2];
    }

    static Suit suit(int kind) {
        return red(kind) ? Suit.HEARTS : Suit.SPADES;
    }

    static int[] ranksLeft(int[] dealt) {
        int[] left = new int[11];
        for (int k = 0; k < KINDS; k++) {
            left[rankOf(k)] += 2 * DECKS - dealt[k];
        }
        return left;
    }

    /** The shoe's cards in the order they are dealt, as kinds. */
    int[] shuffled(int shoe) {
        int[] cards = new int[CARDS];
        int c = 0;
        for (int d = 0; d < DECKS; d++) {
            for (int k = 0; k < KINDS; k++) {
                cards[c++] = k;
                cards[c++] = k;
            }
        }
        SplittableRandom rnd = new SplittableRandom(mix(seed, shoe, 0));
        for (int i = CARDS - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = cards[i];
            cards[i] = cards[j];
            cards[j] = t;
        }
        return cards;
    }

    /** Deals shoe i to the cut card and returns every round's starting state. */
    List<State> deal(int shoe) {
        int[] cards = shuffled(shoe);
        int[] ranks = new int[CARDS];
        for (int i = 0; i < CARDS; i++) {
            ranks[i] = rankOf(cards[i]);
        }
        SplittableRandom valuation = new SplittableRandom(mix(seed, shoe, 1));
        List<State> states = new ArrayList<>();
        int[] dealt = new int[KINDS];
        int depth = 0;
        int round = 0;
        while (depth < cut) {
            double q = rate(ranksLeft(dealt));
            boolean valued = valuation.nextDouble() < q;
            states.add(new State(shoe, round, depth, dealt.clone(), q, valued));
            ConcreteRound.Result r = ConcreteRound.play(ranks, depth, rules, play);
            for (int i = depth; i < r.next; i++) {
                dealt[cards[i]]++;
            }
            depth = r.next;
            round++;
        }
        return states;
    }

    /** The chance a state with these cards left is valued. */
    double rate(int[] left) {
        return rate(left, q0, u0);
    }

    static double rate(int[] left, double q0, double u0) {
        if (u0 == 0) {
            return q0;
        }
        return Math.min(1, q0 * (1 + unusualness(left) / u0));
    }

    /** u: the chi-square distance of the cards left from a full shoe's proportions, per card. */
    static double unusualness(int[] left) {
        int n = 0;
        for (int r = 1; r <= 10; r++) {
            n += left[r];
        }
        if (n == 0) {
            return 0;
        }
        double u = 0;
        for (int r = 1; r <= 10; r++) {
            double expected = n * (r == 10 ? 4.0 : 1.0) / 13;
            double d = left[r] - expected;
            u += d * d / expected;
        }
        return u / n;
    }

    static long mix(long seed, long shoe, long stream) {
        long z = seed * 0x9E3779B97F4A7C15L + shoe * 0xBF58476D1CE4E5B9L + stream * 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
