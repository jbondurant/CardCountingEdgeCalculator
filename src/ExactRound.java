import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The exact value of one round, as ROUND_CONTRACT.md defines it, fast enough to run on a
 * real eight-deck shoe.
 *
 * Nothing here is approximated. Every card is drawn from the shoe as it stands at that
 * moment, the hole card is dealt before the player acts, split hands share one shoe with
 * each other and with the dealer, and the value is conditional on the dealer not having
 * a natural. What makes it fast is a few rearrangements that change the order of the
 * arithmetic and not its result. All of them rest on one fact about drawing without
 * replacement: the chance of a particular sequence of cards depends only on which cards
 * it holds, not on their order. It is shoe[r] x (shoe[r] - 1) x ... over each rank drawn,
 * divided by N x (N - 1) x ... over the number of cards drawn.
 *
 * The hole card is dealt last. A round whose hole card is h, whose player cards are
 * p1..pk and whose dealer draws are d1..dm has exactly the chance of the round where
 * p1..pk come first and h after them. No decision sees the hole card, so the player plays
 * the same way in both. The value can therefore be computed with the player drawing first
 * and the hole card drawn from what the player left, skipping the natural, and divided at
 * the end by the chance of no natural in the shoe as dealt. The dealer becomes one
 * distribution per shoe instead of one pass over the whole player tree per hole card.
 *
 * Dealing it last could visit a spot the contract never reaches, and the contract's
 * exceptions depend on what is reached. A spot where every card left would give the
 * dealer a natural is worth nothing, since the hole card cannot avoid a natural there,
 * and the contract never gets there: it is skipped without asking the policy anything. A
 * player draw with one card left throws, because in the contract's order the hole card
 * has already taken that card and the shoe is empty.
 *
 * The dealer is counted by which cards it takes. The dealer's chance of finishing on a
 * total, from a shoe C, is a sum over dealer hands: for each multiset X of hole card and
 * draws, the number of orders of X that are legal dealer sequences, times the chance of
 * drawing any one of them from C. That list of multisets depends only on the up-card and
 * the soft-17 rule, so it is built once, and each shoe costs one pass over it.
 *
 * The dealer's distribution is carried backwards. Every hand's payoff is linear in the
 * dealer's distribution: a hand that stood on t with stake s is worth s times (chance the
 * dealer busts or finishes under t, minus chance the dealer finishes over t), and a bust,
 * surrender or split blackjack is a fixed amount times the chance there is no natural. So
 * every spot in the round keeps two things: V, what the hands yet to end are worth, and
 * M, the dealer's distribution averaged over every way the rest of the round can go. A
 * hand ending into a spot adds its payoff against that spot's M. Spots are memoized on
 * the shoe, the hand being played and the hands waiting behind it, which is where
 * different orders of the same cards meet. This is the coupled computation, and it is
 * exact for any shoe, down to one that runs out.
 *
 * On a big shoe a split is valued hand by hand. Which hands a split ends up with depends
 * only on which of the second cards dealt to split hands are of the split rank, and on the
 * policy's one answer for a pair that may split; a finished hand's own play depends only
 * on its own cards. So the same fact lets any one hand's second card, its draws and the
 * dealer's cards be dealt first, and every other second card after them, where all that
 * matters is the chance of their pattern of split-rank and other cards in the shoe the
 * hand and the dealer left. Each kind of finished hand is then played alone from the shoe
 * the split starts from, against the dealer's distribution with each dealer hand weighted
 * by that chance, and the round is worth the sum over its hands. That needs a shoe that
 * cannot run out: reordering does not preserve which draw finds the shoe empty. It is
 * used only when the shoe holds more cards, and more cards that are not the natural hole
 * card, than the round could possibly draw. Then the decisions it asks the policy about
 * are exactly the ones the contract reaches, so it throws where the contract throws. On
 * eight decks, 8,8 against a 10 resplit to three hands takes the coupled computation 2.9
 * million spots and over a second; at four hands the coupled one had not finished after
 * two minutes, and this one takes about two milliseconds.
 */
public final class ExactRound implements RoundValuer {

    /**
     * The dealer's outcomes, in the order M holds them: 17, 18, 19, 20, 21, bust, and last
     * the dealer needing a card from an empty shoe. That last one only happens on a nearly
     * empty shoe. It is kept so that a bust or surrender still counts in those rounds, when
     * no hand needs the dealer and the dealer does not draw; a hand that does need
     * the dealer there is the contract's empty-shoe exception.
     */
    private static final int OUTCOMES = 7;
    private static final int BUST = 5;
    private static final int RAN_OUT = 6;

    /** A coupled spot's record: V, then M. */
    private static final int WIDTH = 1 + OUTCOMES;

    private static final PlayerMove[] MOVES = PlayerMove.values();

    /**
     * SETTLE[t][o]: what a stake of 1 standing on total t wins against dealer outcome o.
     * Totals under 17 lose to every total the dealer stands on.
     */
    private static final double[][] SETTLE = new double[22][OUTCOMES];

    static {
        for (int t = 0; t <= 21; t++) {
            for (int d = 17; d <= 21; d++) {
                SETTLE[t][d - 17] = Integer.signum(t - d);
            }
            SETTLE[t][BUST] = 1;
            // RAN_OUT stays 0: a hand that stood never meets it, because that round throws.
        }
    }

    private final RoundRules rules;
    /** Always use the coupled computation, so tests can hold the hand-by-hand one to it. */
    private final boolean coupledOnly;
    /** The dealer's hands for each up-card, built the first time that up-card is valued. */
    private final DealerHands[] dealerHands = new DealerHands[11];
    /** The dealer's distributions one call leaves the next, or null while a call holds them. */
    private StateFinals kept = new StateFinals();
    /**
     * How many splits this engine has valued hand by hand. Both ways give the same value
     * wherever the hand-by-hand one is used, so a value cannot show which was taken; this
     * lets a test pin where a split switches from one to the other.
     */
    private final AtomicLong splitsByHand = new AtomicLong();

    public ExactRound(RoundRules rules) {
        this(rules, false);
    }

    ExactRound(RoundRules rules, boolean coupledOnly) {
        if (rules == null) {
            throw new IllegalArgumentException("rules are required");
        }
        this.rules = rules;
        this.coupledOnly = coupledOnly;
    }

    @Override
    public double valueOfFirstMove(int[] shoe, int p1, int p2, int up, PlayerMove first,
                                   RoundPolicy policy) {
        if (shoe == null || shoe.length != 11) {
            throw new IllegalArgumentException("a shoe is an int[11] indexed by rank 1 to 10");
        }
        for (int r = 1; r <= 10; r++) {
            if (shoe[r] < 0) {
                throw new IllegalArgumentException("the shoe holds " + shoe[r] + " cards of rank " + r);
            }
        }
        if (!isRank(p1) || !isRank(p2) || !isRank(up)) {
            throw new IllegalArgumentException("cards are ranks 1 to 10: " + p1 + ", " + p2 + " vs " + up);
        }
        if (first == null) {
            throw new IllegalArgumentException("the first move is required");
        }
        if (policy == null) {
            throw new IllegalArgumentException("a policy is required");
        }

        // The first move is judged on the two cards alone, before anything is dealt, so an
        // illegal one is refused whatever the shoe holds.
        boolean playerNatural = (p1 == 1 && p2 == 10) || (p1 == 10 && p2 == 1);
        boolean legal;
        if (playerNatural) {
            legal = first == PlayerMove.Stand;   // a natural is settled at once
        } else {
            switch (first) {
                case Stand:
                case Hit:      // two cards that are not a natural total 20 or less
                case Double:
                    legal = true;
                    break;
                case Split:
                    legal = p1 == p2 && rules.maxHandsFor(p1) >= 2;
                    break;
                case Surrender:
                    legal = rules.surrender;
                    break;
                default:
                    legal = false;
            }
        }
        if (!legal) {
            throw new IllegalArgumentException(first + " is not legal on " + p1 + "," + p2
                    + (playerNatural ? ", a natural, whose only move is Stand" : ""));
        }

        // Then the hole card, the first card dealt from the shoe, natural or not: an empty
        // shoe fails there, and a shoe whose every card would give the dealer a natural
        // leaves nothing to condition on.
        long cards = 0;
        for (int r = 1; r <= 10; r++) {
            cards += shoe[r];
        }
        if (cards == 0) {
            throw new IllegalStateException("the hole card is drawn from an empty shoe");
        }
        int natural = naturalHoleRank(up);
        long naturals = natural == 0 ? 0 : shoe[natural];
        if (naturals == cards) {
            throw new IllegalArgumentException("every card in the shoe gives the dealer a natural");
        }
        if (playerNatural) {
            return rules.blackjackPayout;
        }

        // A call on another thread may hold the kept distributions; this one then works
        // without them.
        StateFinals finals = takeKept();
        try {
            return new Round(shoe, p1, p2, up, policy, dealerHandsFor(up), finals)
                    .value(p1, p2, first, naturals, cards);
        } finally {
            if (finals != null) {
                giveBackKept(finals);
            }
        }
    }

    /** How many splits this engine has valued hand by hand rather than coupled. */
    long splitsValuedHandByHand() {
        return splitsByHand.get();
    }

    private synchronized StateFinals takeKept() {
        StateFinals f = kept;
        kept = null;
        return f;
    }

    private synchronized void giveBackKept(StateFinals f) {
        kept = f;
    }

    private static boolean isRank(int r) {
        return r >= 1 && r <= 10;
    }

    /** The hole card that would give the dealer a natural with this up-card, or 0 if none can. */
    private static int naturalHoleRank(int up) {
        return up == 1 ? 10 : up == 10 ? 1 : 0;
    }

    private static int total(int hard, boolean ace) {
        return ace && hard <= 11 ? hard + 10 : hard;
    }

    private static boolean isSoft(int hard, boolean ace) {
        return ace && hard <= 11;
    }

    private synchronized DealerHands dealerHandsFor(int up) {
        if (dealerHands[up] == null) {
            dealerHands[up] = new DealerHands(up, rules.hitsSoft17);
        }
        return dealerHands[up];
    }

    // ------------------------------------------------------------------ the dealer

    /**
     * Every way the dealer can finish from one up-card, grouped by the cards it takes.
     *
     * Each entry is a multiset X of hole card and draws with the number of its orders that
     * are legal dealer hands: the hole card is not the natural one, every total before the
     * last card is one the dealer hits, and the last card makes one it stands on or busts.
     * Every such order has the same chance from a given shoe, so the entry's share of the
     * dealer's distribution is that count times the chance of one of them.
     *
     * It also keeps the multisets after which the dealer must still draw, with their legal
     * orders counted the same way. A shoe equal to one of those, card for card, is a shoe
     * the dealer runs out of.
     */
    private static final class DealerHands {
        /** Entry i holds ranks[start[i]] .. ranks[start[i+1] - 1], each counts[j] times. */
        final int[] start;
        final byte[] ranks;
        final byte[] counts;
        final int[] size;
        final double[] orders;
        final int[] outcome;
        final int entries;
        /** maxCount[r]: the most cards of rank r any dealer hand takes. */
        final int[] maxCount = new int[11];
        /** countOf[r][i]: how many cards of rank r entry i holds. */
        final byte[][] countOf = new byte[11][];
        final int maxSize;
        /** Keyed like found: the legal orders of each multiset after which the dealer still draws. */
        final Map<Long, Long> unfinished = new HashMap<>();
        int maxUnfinishedSize;

        private final boolean hitsSoft17;
        /** Keyed by the multiset, four bits per rank: its orders and its outcome. */
        private final Map<Long, long[]> found = new LinkedHashMap<>();

        DealerHands(int up, boolean hitsSoft17) {
            this.hitsSoft17 = hitsSoft17;
            int natural = naturalHoleRank(up);
            int[] held = new int[11];
            for (int hole = 1; hole <= 10; hole++) {
                if (hole == natural) {
                    continue;
                }
                held[hole]++;
                walk(up + hole, up == 1 || hole == 1, held, 1);
                held[hole]--;
            }

            entries = found.size();
            start = new int[entries + 1];
            size = new int[entries];
            orders = new double[entries];
            outcome = new int[entries];
            int distinct = 0;
            for (long key : found.keySet()) {
                for (int r = 0; r < 10; r++) {
                    distinct += ((key >>> (4 * r)) & 15) != 0 ? 1 : 0;
                }
            }
            ranks = new byte[distinct];
            counts = new byte[distinct];
            int i = 0;
            int j = 0;
            int largest = 0;
            for (Map.Entry<Long, long[]> e : found.entrySet()) {
                long key = e.getKey();
                start[i] = j;
                int cards = 0;
                for (int r = 1; r <= 10; r++) {
                    int k = (int) ((key >>> (4 * (r - 1))) & 15);
                    if (k > 0) {
                        ranks[j] = (byte) r;
                        counts[j] = (byte) k;
                        j++;
                        cards += k;
                        maxCount[r] = Math.max(maxCount[r], k);
                    }
                }
                size[i] = cards;
                largest = Math.max(largest, cards);
                orders[i] = e.getValue()[0];
                outcome[i] = (int) e.getValue()[1];
                i++;
            }
            start[entries] = j;
            maxSize = largest;
            found.clear();
            for (int r = 1; r <= 10; r++) {
                countOf[r] = new byte[entries];
            }
            for (int e = 0; e < entries; e++) {
                for (int k = start[e]; k < start[e + 1]; k++) {
                    countOf[ranks[k]][e] = counts[k];
                }
            }
        }

        private void walk(int hard, boolean ace, int[] held, int cards) {
            int total = total(hard, ace);
            int result;
            if (total > 21) {
                result = BUST;
            } else if (total > 17 || (total == 17 && !(hitsSoft17 && isSoft(hard, ace)))) {
                result = total - 17;
            } else {
                unfinished.merge(pack(held), 1L, Long::sum);
                maxUnfinishedSize = Math.max(maxUnfinishedSize, cards);
                for (int x = 1; x <= 10; x++) {
                    held[x]++;
                    walk(hard + x, ace || x == 1, held, cards + 1);
                    held[x]--;
                }
                return;
            }
            long[] entry = found.computeIfAbsent(pack(held), k -> new long[]{0, result});
            entry[0]++;
        }

        /**
         * Four bits per rank. A dealer hand never holds sixteen of one rank: it stops drawing
         * once its hard total passes 16.
         */
        static long pack(int[] held) {
            long key = 0;
            for (int r = 1; r <= 10; r++) {
                if (held[r] > 15) {
                    throw new AssertionError("a dealer hand holding " + held[r] + " of rank " + r);
                }
                key |= (long) held[r] << (4 * (r - 1));
            }
            return key;
        }
    }

    // ------------------------------------------------------------------ between calls

    /**
     * The dealer's distributions from the shoes of one state, kept from one call to the next.
     *
     * StateValuer values every first move of all 550 deals of a state, one call each, and
     * every call's shoe is the state's shoe less three cards. So the shoes the dealer draws
     * from recur from call to call: 10,6 against a 7 hitting a 2 leaves the dealer the shoe
     * that 10,2 against a 7 hitting a 6 does. A dealer's distribution depends only on the
     * shoe, the up-card and the rules, and is worked out the same way whichever call asks for
     * it, so reusing one changes no value, not even in the last bit. They are kept while the
     * calls come from one state, the shoe with the deal's three cards put back, and dropped
     * when another state begins, so an engine holds one state's worth at most.
     *
     * To be the same key in every call, a shoe is packed in one fixed layout here: six bits
     * for each rank from ace to nine and eight for the tens, 62 in all, which holds any shoe
     * of up to fifteen decks. A shoe that does not fit is valued without them.
     */
    private static final class StateFinals {
        final int[] state = new int[11];
        Memo finals = new Memo(OUTCOMES);

        /**
         * The distributions for a call from this state, the ones kept if it is the state
         * they came from, or none if it is not.
         */
        Memo finalsFor(int[] shoe, int p1, int p2, int up) {
            int[] from = shoe.clone();
            from[p1]++;
            from[p2]++;
            from[up]++;
            if (!Arrays.equals(from, state)) {
                System.arraycopy(from, 0, state, 0, 11);
                finals = new Memo(OUTCOMES);
            }
            return finals;
        }
    }

    /**
     * Whether a shoe fits the fixed layout the kept distributions are keyed in. A shoe that
     * fits is packed in it for every call, so a check one card too loose would pack a shoe
     * wrongly; ExactRoundTest holds shoes on both sides of each edge to BruteForceRound.
     */
    static boolean fitsSharedLayout(int[] shoe) {
        for (int r = 1; r <= 9; r++) {
            if (shoe[r] > 63) {
                return false;
            }
        }
        return shoe[10] <= 255;
    }

    // ------------------------------------------------------------------ one call

    /**
     * Everything one call to valueOfFirstMove works with: the shoe's packing, the tables of
     * falling factorials, the memos and the policy's answers. Built per call, since the
     * policy and the shoe change between calls, except for the dealer's distributions an
     * engine keeps for one state.
     *
     * A shoe is packed into one long. With the kept distributions it is their fixed layout;
     * without, each rank has a field just wide enough for the count it starts with, which
     * lets a shoe of more than fifteen decks fit if it is short of some ranks.
     *
     * The rest of a spot is packed into a second long, laid out below: the hand being
     * played, if one is mid-way, the number of hands in the round, and the second cards of
     * the split hands still waiting, four bits each, the next one lowest. A hand stops being
     * packed once it ends, so every spot between two hands has the hand bits clear.
     */
    private final class Round {
        // The hand being played: its total counting aces as 1, whether it holds an ace, its
        // cards, whether it came from a split, and whether there is one at all.
        private static final int HARD_BITS = 5;
        private static final int ACE_SHIFT = 5;
        private static final int CARDS_SHIFT = 6;
        private static final int SPLIT_SHIFT = 11;
        private static final int LIVE_SHIFT = 12;
        private static final long HAND_MASK = (1L << 13) - 1;
        // Hands in the round, kept only while another split can still happen, 0 otherwise,
        // so spots that differ only in a count nothing will read again are one spot.
        private static final int HANDS_SHIFT = 13;
        private static final long HANDS_MASK = 63;
        private static final int QUEUE_SHIFT = 19;
        private static final int QUEUE_CAPACITY = (64 - QUEUE_SHIFT) / 4;
        /** No hand being played and none waiting: the dealer's turn. */
        private static final long DONE = 0;
        /**
         * The classes of finished split hand the hand-by-hand computation weighs apart: one
         * whose second card is not the split rank; one whose second card is, with no room
         * left to split it; and one whose second card is, that could have split and that the
         * policy played some other way. They are finished in different patterns of the other
         * second cards.
         */
        private static final int OTHER_CARD = 0;
        private static final int PAIR_AT_LIMIT = 1;
        private static final int PAIR_KEPT = 2;
        private static final int CLASSES = 3;
        /** Where a hand played alone keeps its class, above the bits of a live hand. */
        private static final int CLASS_SHIFT = 16;

        private final int up;
        private final int natural;
        private final RoundPolicy policy;
        private final DealerHands dealer;

        private final int[] shift = new int[11];
        private final long[] fieldMask = new long[11];
        private final long[] one = new long[11];
        private final int[] dealt;

        /** fall[r][c][k] = c (c - 1) ... (c - k + 1), zero when k > c. */
        private final double[][][] fall = new double[11][][];
        /** inverseFall[n][k] = 1 / (n (n - 1) ... (n - k + 1)), zero when k > n. */
        private final double[][] inverseFall;

        /** The coupled computation's spots. */
        private final Memo spots = new Memo(WIDTH);
        /** The dealer's distribution for each shoe it has been asked about, keyed by the shoe and the up-card. */
        private final Memo finals;
        /**
         * The hand-by-hand computation: hands played alone, keyed by their class, and the
         * dealer's distribution weighted for each class.
         */
        private final Memo aloneHands = new Memo(1);
        private final Memo weightedFinals = new Memo(OUTCOMES);
        private final double[] dealerScratch = new double[OUTCOMES];
        /** The policy's answer for each distinct decision, as ordinal + 1, or 0 before it is asked. */
        private final byte[] answers = new byte[1 << 21];

        private int splitRank;
        private int maxHands;

        /** The shoe a split valued hand by hand starts from: its cards of the split rank, and all its cards. */
        private int rankLeft;
        private int cardsLeft;
        /**
         * For each class of finished hand, the patterns of the other second cards that finish
         * a hand of it, as parallel lists: a pattern's cards of the split rank, its other
         * cards, and how many hands of the class it finishes, summed over every way the round
         * can go.
         */
        private final int[][] patternRanks = new int[CLASSES][];
        private final int[][] patternOthers = new int[CLASSES][];
        private final double[][] patternHands = new double[CLASSES][];
        /** While the patterns are found: tally[class][a * tallyWidth + b], as patternHands. */
        private double[][] tally;
        private int tallyWidth;
        /**
         * weights[class][dN][dR]: what patternWeight gives once a hand and the dealer have
         * taken dN cards, dR of them of the split rank, from the shoe the split starts from.
         * Rows are made as they are needed, and NaN marks a value not yet worked out.
         */
        private double[][][] weights;
        private double[] weightGrid = new double[0];

        Round(int[] shoe, int p1, int p2, int up, RoundPolicy policy, DealerHands dealer, StateFinals state) {
            this.up = up;
            this.natural = naturalHoleRank(up);
            this.policy = policy;
            this.dealer = dealer;
            this.dealt = shoe.clone();
            boolean shared = state != null && fitsSharedLayout(shoe);
            finals = shared ? state.finalsFor(shoe, p1, p2, up) : new Memo(OUTCOMES);

            int bits = 0;
            for (int r = 1; r <= 10; r++) {
                int width = shared ? (r == 10 ? 8 : 6) : 32 - Integer.numberOfLeadingZeros(shoe[r]);
                shift[r] = bits;
                fieldMask[r] = width == 0 ? 0 : (1L << width) - 1;
                one[r] = width == 0 ? 0 : 1L << bits;
                bits += width;
            }
            if (bits > 64) {
                throw new UnsupportedOperationException("this shoe needs " + bits + " bits to pack, "
                        + "more than the 64 a shoe key holds; fifteen decks is the most that fits");
            }

            int cards = 0;
            for (int r = 1; r <= 10; r++) {
                cards += shoe[r];
                int maxK = dealer.maxCount[r];
                fall[r] = new double[shoe[r] + 1][maxK + 1];
                for (int c = 0; c <= shoe[r]; c++) {
                    double f = 1;
                    for (int k = 0; k <= maxK; k++) {
                        fall[r][c][k] = f;
                        f *= Math.max(c - k, 0);
                    }
                }
            }
            inverseFall = new double[cards + 1][dealer.maxSize + 1];
            for (int n = 0; n <= cards; n++) {
                double f = 1;
                for (int k = 0; k <= dealer.maxSize; k++) {
                    inverseFall[n][k] = f == 0 ? 0 : 1 / f;
                    f *= Math.max(n - k, 0);
                }
            }
        }

        private long pack(int[] shoe) {
            long comp = 0;
            for (int r = 1; r <= 10; r++) {
                comp |= (long) shoe[r] << shift[r];
            }
            return comp;
        }

        private int count(long comp, int r) {
            return (int) ((comp >>> shift[r]) & fieldMask[r]);
        }

        /**
         * Whether the contract can be here at all: whether some card left could be the hole
         * card without giving the dealer a natural.
         */
        private boolean holeCardPossible(long comp, int n) {
            return n > (natural == 0 ? 0 : count(comp, natural));
        }

        /** A draw from a spot the contract reaches, where the hole card has already been taken. */
        private void requireDraw(int n) {
            if (n < 2) {
                throw new IllegalStateException("a card must be drawn and the shoe is empty");
            }
        }

        double value(int p1, int p2, PlayerMove first, long naturals, long cards) {
            long comp = pack(dealt);
            int n = (int) cards;
            int hard = p1 + p2;
            boolean ace = p1 == 1 || p2 == 1;
            // The payoff summed over rounds without a dealer natural, each weighted by its
            // chance; dividing by the chance of no natural makes it conditional.
            double unconditional;
            if (first == PlayerMove.Split) {
                startSplitting(p1);
                long mostDrawn = mostCardsASplitCanDraw(p1);
                if (!coupledOnly && cards >= mostDrawn && cards - naturals >= mostDrawn) {
                    splitsByHand.incrementAndGet();
                    unconditional = splitByHand(comp, n);
                } else {
                    double[] out = new double[WIDTH];
                    split(comp, n, 0, 2, out);
                    unconditional = out[0];
                }
            } else {
                double[] out = new double[WIDTH];
                switch (first) {
                    case Stand:
                        endStood(comp, n, DONE, total(hard, ace), 1, out);
                        break;
                    case Surrender:
                        endFixed(comp, n, DONE, -0.5, out);
                        break;
                    case Hit:
                        hit(comp, n, hard, ace, 2, false, DONE, out);
                        break;
                    case Double:
                        doubleDown(comp, n, hard, ace, DONE, out);
                        break;
                    default:
                        throw new AssertionError(first);
                }
                unconditional = out[0];
            }
            return unconditional / ((cards - naturals) / (double) cards);
        }

        /**
         * The most cards a round that splits this pair can take from the shoe, hole card
         * included, whatever the policy does. A shoe with at least this many cannot run out.
         *
         * The round ends with at most `reachable` hands, and splitting to them deals two
         * second cards per split. A hand hits only while its hard total is 20 or less, and it
         * starts at 2 or more, so every card it takes but its last adds up to 18 at most; the
         * dealer draws only while its hard total is 16 or less, from at least 2 with the hole
         * card, so every draw but its last adds up to 14 at most. Those cards together add up
         * to no more than 18 per hand and 14, and no set of cards from this shoe with that
         * total holds more than the smallest ones do. Add one last card per hand, the
         * dealer's last draw, and the hole card.
         */
        private long mostCardsASplitCanDraw(int rank) {
            long reachable = Math.min(rules.maxHandsFor(rank), 2L + dealt[rank]);
            long budget = 18 * reachable + 14;
            long smallest = 0;
            for (int r = 1; r <= 10 && budget >= r; r++) {
                long take = Math.min(dealt[r], budget / r);
                smallest += take;
                budget -= take * r;
            }
            return 1 + 2 * (reachable - 1) + reachable + 1 + smallest;
        }

        private void startSplitting(int rank) {
            splitRank = rank;
            maxHands = rules.maxHandsFor(rank);
            // Every resplit takes another card of the rank from the shoe, so the shoe caps
            // the hands a round can reach whatever the limit says.
            long reachable = Math.min(maxHands, 2L + dealt[rank]);
            if (reachable > QUEUE_CAPACITY) {
                throw new UnsupportedOperationException("a round of up to " + reachable
                        + " split hands is more than this implementation packs (" + QUEUE_CAPACITY + ")");
            }
        }

        // ---------------------------------------------------------- the dealer

        /**
         * Every hand has ended: out[1..7] is the dealer's distribution from this shoe,
         * counting only hole cards that are not a natural, and out[0] is 0. Returns whether
         * the dealer can run out of cards here.
         */
        private boolean dealerTurn(long comp, int n, double[] out) {
            out[0] = 0;
            int slot = finals.find(comp, up);
            if (slot >= 0) {
                finals.read(slot, out, 1);
                return finals.flag(slot);
            }
            int[] c = new int[11];
            for (int r = 1; r <= 10; r++) {
                c[r] = count(comp, r);
            }
            Arrays.fill(out, 1, WIDTH, 0);
            DealerHands d = dealer;
            for (int i = 0; i < d.entries; i++) {
                int size = d.size[i];
                if (size > n) {
                    continue;
                }
                double p = d.orders[i];
                for (int j = d.start[i]; j < d.start[i + 1]; j++) {
                    int r = d.ranks[j];
                    int k = d.counts[j];
                    if (k > c[r]) {
                        p = 0;
                        break;
                    }
                    p *= fall[r][c[r]][k];
                }
                if (p != 0) {
                    out[1 + d.outcome[i]] += p * inverseFall[n][size];
                }
            }
            // The dealer runs out only by taking every card left and still needing one. Every
            // multiset it can be holding then extends to one of its finished hands, so the
            // tables above reach far enough to price it.
            boolean runsOut = false;
            if (n <= d.maxUnfinishedSize) {
                boolean fits = true;
                for (int r = 1; r <= 10; r++) {
                    fits &= c[r] <= 15;
                }
                Long orders = fits ? d.unfinished.get(DealerHands.pack(c)) : null;
                if (orders != null) {
                    double p = orders;
                    for (int r = 1; r <= 10; r++) {
                        p *= fall[r][c[r]][c[r]];
                    }
                    out[1 + RAN_OUT] = p * inverseFall[n][n];
                    runsOut = true;
                }
            }
            finals.put(comp, up, out, 1, runsOut);
            return runsOut;
        }

        // ---------------------------------------------------------- coupled spots

        /**
         * What the rest of the round is worth from this spot: out[0] is V, the payoff of every
         * hand still to end, and out[1..7] is M, the dealer's distribution at the end. Both
         * are weighted by the chance of each continuation, including the hole card not being
         * a natural. Returns whether some continuation leaves the dealer drawing from an
         * empty shoe.
         */
        private boolean play(long comp, int n, long spot, double[] out) {
            if (!holeCardPossible(comp, n)) {
                Arrays.fill(out, 0, WIDTH, 0);
                return false;
            }
            if (spot == DONE) {
                return dealerTurn(comp, n, out);
            }
            int slot = spots.find(comp, spot);
            if (slot >= 0) {
                spots.read(slot, out, 0);
                return spots.flag(slot);
            }
            boolean runsOut = (spot & (1L << LIVE_SHIFT)) != 0
                    ? continueHand(comp, n, spot, out)
                    : startSplitHand(comp, n, spot, out);
            spots.put(comp, spot, out, 0, runsOut);
            return runsOut;
        }

        /**
         * A hand stood, or doubled, on a total of 21 or less: the rest of the round from next,
         * plus this hand settled against the dealer's distribution there. The dealer has to
         * play for this hand, so a dealer who would run out of cards is the contract's
         * empty-shoe exception.
         */
        private void endStood(long comp, int n, long next, int total, double stake, double[] out) {
            if (play(comp, n, next, out)) {
                throw new IllegalStateException("the dealer must draw and the shoe is empty");
            }
            double[] settle = SETTLE[total];
            double won = 0;
            for (int o = 0; o < OUTCOMES; o++) {
                won += settle[o] * out[1 + o];
            }
            out[0] += stake * won;
        }

        /**
         * A hand that busted, surrendered or was paid as a blackjack: a fixed payoff, counted
         * in every continuation without a dealer natural. It does not need the dealer.
         */
        private boolean endFixed(long comp, int n, long next, double payoff, double[] out) {
            boolean runsOut = play(comp, n, next, out);
            double noNatural = 0;
            for (int o = 0; o < OUTCOMES; o++) {
                noNatural += out[1 + o];
            }
            out[0] += payoff * noNatural;
            return runsOut;
        }

        private boolean hit(long comp, int n, int hard, boolean ace, int cards, boolean fromSplit,
                            long next, double[] out) {
            requireDraw(n);
            Arrays.fill(out, 0, WIDTH, 0);
            double[] after = new double[WIDTH];
            boolean runsOut = false;
            for (int y = 1; y <= 10; y++) {
                int c = count(comp, y);
                if (c == 0) {
                    continue;
                }
                long drawn = comp - one[y];
                int hardAfter = hard + y;
                if (hardAfter > 21) {
                    runsOut |= endFixed(drawn, n - 1, next, -1, after);
                } else {
                    runsOut |= play(drawn, n - 1, live(hardAfter, ace || y == 1, cards + 1, fromSplit, next), after);
                }
                accumulate(out, after, c / (double) n);
            }
            return runsOut;
        }

        private boolean doubleDown(long comp, int n, int hard, boolean ace, long next, double[] out) {
            requireDraw(n);
            Arrays.fill(out, 0, WIDTH, 0);
            double[] after = new double[WIDTH];
            boolean runsOut = false;
            for (int y = 1; y <= 10; y++) {
                int c = count(comp, y);
                if (c == 0) {
                    continue;
                }
                long drawn = comp - one[y];
                int hardAfter = hard + y;
                if (hardAfter > 21) {
                    runsOut |= endFixed(drawn, n - 1, next, -2, after);
                } else {
                    endStood(drawn, n - 1, next, total(hardAfter, ace || y == 1), 2, after);
                }
                accumulate(out, after, c / (double) n);
            }
            return runsOut;
        }

        /**
         * A pair becomes two hands. Both second cards are drawn now, the first new hand's
         * then the second's, and both hands go to the front of the waiting line with the
         * first new hand next to play.
         *
         * @param waiting the hands already waiting, not counting the one being split
         * @param hands   hands in the round once this split is made
         */
        private boolean split(long comp, int n, long waiting, int hands, double[] out) {
            requireDraw(n);
            Arrays.fill(out, 0, WIDTH, 0);
            double[] after = new double[WIDTH];
            boolean runsOut = false;
            for (int y1 = 1; y1 <= 10; y1++) {
                int c1 = count(comp, y1);
                if (c1 == 0) {
                    continue;
                }
                long once = comp - one[y1];
                if (!holeCardPossible(once, n - 1)) {
                    continue;
                }
                requireDraw(n - 1);
                for (int y2 = 1; y2 <= 10; y2++) {
                    int c2 = count(once, y2);
                    if (c2 == 0) {
                        continue;
                    }
                    long queue = (waiting << 8) | ((long) y2 << 4) | y1;
                    runsOut |= play(once - one[y2], n - 2, between(queue, hands), after);
                    accumulate(out, after, (c1 / (double) n) * (c2 / (double) (n - 1)));
                }
            }
            return runsOut;
        }

        /**
         * The next waiting split hand takes its turn. It already holds its second card, so it
         * goes straight to its first decision, or is paid at once as a split blackjack.
         */
        private boolean startSplitHand(long comp, int n, long spot, double[] out) {
            long queue = spot >>> QUEUE_SHIFT;
            int hands = (int) ((spot >>> HANDS_SHIFT) & HANDS_MASK);
            int second = (int) (queue & 15);
            long waiting = queue >>> 4;
            long next = between(waiting, hands);

            int hard = splitRank + second;
            boolean ace = splitRank == 1 || second == 1;
            int total = total(hard, ace);
            if (isSplitBlackjack(second)) {
                return endFixed(comp, n, next, rules.blackjackPayout, out);
            }
            // hands is 0 once no further split can happen, which is also when this hand
            // cannot split whatever it holds.
            boolean canSplit = second == splitRank && hands != 0 && hands < maxHands;
            switch (splitHandMove(second, canSplit)) {
                case Stand:
                    endStood(comp, n, next, total, 1, out);
                    return false;
                case Surrender:
                    return endFixed(comp, n, next, -0.5, out);
                case Hit:
                    return hit(comp, n, hard, ace, 2, true, next, out);
                case Double:
                    return doubleDown(comp, n, hard, ace, next, out);
                case Split:
                    return split(comp, n, waiting, hands + 1, out);
                default:
                    throw new AssertionError();
            }
        }

        /** A hand that has hit and not busted: the policy decides again, Hit or Stand only. */
        private boolean continueHand(long comp, int n, long spot, double[] out) {
            int hard = (int) (spot & ((1 << HARD_BITS) - 1));
            boolean ace = ((spot >>> ACE_SHIFT) & 1) != 0;
            int cards = (int) ((spot >>> CARDS_SHIFT) & 31);
            boolean fromSplit = ((spot >>> SPLIT_SHIFT) & 1) != 0;
            long next = spot & ~HAND_MASK;
            int total = total(hard, ace);
            PlayerMove move = ask(total, isSoft(hard, ace), cards, 0, fromSplit, false,
                    total < 21, false, false, false);
            if (move == PlayerMove.Stand) {
                endStood(comp, n, next, total, 1, out);
                return false;
            }
            return hit(comp, n, hard, ace, cards, fromSplit, next, out);
        }

        // ---------------------------------------------------------- split hands' first moves

        /** A split hand of an ace and a ten, paid as a blackjack where the house says so. */
        private boolean isSplitBlackjack(int second) {
            return rules.blackjackOnSplitPairs && splitRank + second == 11
                    && (splitRank == 1 || second == 1);
        }

        /**
         * What a split hand holding the split card and this second card does first, when it
         * is not a blackjack: always a first decision for the policy, even where Stand is the
         * only move it has. A split ace differs from other split hands only in that it may
         * not hit unless the house lets it; it may still double, split or surrender by the
         * same rules as any split hand, whatever its second card.
         */
        private PlayerMove splitHandMove(int second, boolean canSplit) {
            int hard = splitRank + second;
            boolean ace = splitRank == 1 || second == 1;
            int total = total(hard, ace);
            boolean mayHit = total < 21 && (splitRank != 1 || rules.canHitSplitAces);
            return ask(total, isSoft(hard, ace), 2, second == splitRank ? splitRank : 0, true, true,
                    mayHit, rules.doubleAfterSplit[splitRank], canSplit,
                    rules.surrender && rules.surrenderAfterSplit);
        }

        // ---------------------------------------------------------- hand by hand

        /*
         * A split valued hand by hand. The round is worth the sum of what its finished hands
         * are worth, and a finished hand's payoff depends only on its own cards and the
         * dealer's. The other split hands' second cards reach it only through which hands the
         * round has: a hand whose second card is the split rank may split again, room
         * permitting, and any other hand is finished. So, by the fact every rearrangement here
         * rests on, a hand's second card, its draws and the dealer's cards can be dealt first
         * and the other second cards after them, and of those only the pattern of split-rank
         * and other cards matters. From a shoe of N cards holding R of the split rank, a
         * pattern of a split-rank cards and b others, in any order, has the chance
         * R (R - 1) ... (R - a + 1) x (N - R) ... (N - R - b + 1) / N (N - 1) ... (N - a - b + 1).
         *
         * The patterns that finish a hand of each class are found first, with the policy
         * asked whether a pair that may split does, exactly where the round can deal one.
         * Then each class of hand is played once for each second card, alone, the dealer
         * after it, and every payoff is weighted by the chance of those patterns in the shoe
         * the hand and the dealer leave. A pattern needs cards the hand may have taken, so a
         * decision is put to the policy only where some pattern still has a chance: where the
         * contract can reach it.
         */

        /** The value of a split, summed over every finished hand, from the shoe it starts from. */
        private double splitByHand(long comp, int n) {
            rankLeft = count(comp, splitRank);
            cardsLeft = n;
            findPatterns();
            weights = new double[CLASSES][n + 1][];
            double v = 0;
            for (int y = 1; y <= 10; y++) {
                int c = count(comp, y);
                if (c == 0) {
                    continue;
                }
                long drawn = comp - one[y];
                double p = c / (double) n;
                if (y != splitRank) {
                    v += p * firstMoveValue(drawn, n - 1, y, OTHER_CARD);
                } else {
                    v += p * (firstMoveValue(drawn, n - 1, y, PAIR_AT_LIMIT)
                            + firstMoveValue(drawn, n - 1, y, PAIR_KEPT));
                }
            }
            return v;
        }

        /** Every pattern of second cards the split can deal, and the finished hands of each. */
        private void findPatterns() {
            long reachable = Math.min(maxHands, 2L + rankLeft);
            tallyWidth = (int) (2 * (reachable - 1)) + 1;
            tally = new double[CLASSES][tallyWidth * tallyWidth];
            dealPatterns(0, 0, 2, 0, 0, 0, 0, 0);
            for (int cls = 0; cls < CLASSES; cls++) {
                int kinds = 0;
                for (double t : tally[cls]) {
                    kinds += t > 0 ? 1 : 0;
                }
                patternRanks[cls] = new int[kinds];
                patternOthers[cls] = new int[kinds];
                patternHands[cls] = new double[kinds];
                int k = 0;
                for (int i = 0; i < tally[cls].length; i++) {
                    if (tally[cls][i] > 0) {
                        patternRanks[cls][k] = i / tallyWidth;
                        patternOthers[cls][k] = i % tallyWidth;
                        patternHands[cls][k] = tally[cls][i];
                        k++;
                    }
                }
            }
            tally = null;
        }

        /**
         * A split deals two second cards, the first new hand's and then the second's, each of
         * the split rank or not, as far as the shoe holds such cards; then the first new hand
         * plays. The waiting hands are one bit each, set for a split-rank second card, the
         * next to play lowest.
         *
         * @param ranks  split-rank second cards dealt so far
         * @param others other second cards dealt so far
         * @param other  hands finished so far holding another card; atLimit and kept count
         *               the other two classes
         */
        private void dealPatterns(long queue, int waiting, int hands, int ranks, int others,
                                  int other, int atLimit, int kept) {
            for (int first = 0; first <= 1; first++) {
                for (int second = 0; second <= 1; second++) {
                    int a = ranks + first + second;
                    int b = others + 2 - first - second;
                    if (a <= rankLeft && b <= cardsLeft - rankLeft) {
                        playPatterns((queue << 2) | (second << 1) | first, waiting + 2, hands, a, b,
                                other, atLimit, kept);
                    }
                }
            }
        }

        /** The next waiting hand's first decision, as far as the pattern goes. */
        private void playPatterns(long queue, int waiting, int hands, int ranks, int others,
                                  int other, int atLimit, int kept) {
            if (waiting == 0) {
                // The round's hands are all finished. Each is counted with the pattern of the
                // other second cards: all of them less its own.
                tallyHands(OTHER_CARD, ranks, others - 1, other);
                tallyHands(PAIR_AT_LIMIT, ranks - 1, others, atLimit);
                tallyHands(PAIR_KEPT, ranks - 1, others, kept);
                return;
            }
            long rest = queue >>> 1;
            if ((queue & 1) == 0) {
                playPatterns(rest, waiting - 1, hands, ranks, others, other + 1, atLimit, kept);
            } else if (hands >= maxHands) {
                playPatterns(rest, waiting - 1, hands, ranks, others, other, atLimit + 1, kept);
            } else if (splitHandMove(splitRank, true) == PlayerMove.Split) {
                dealPatterns(rest, waiting - 1, hands + 1, ranks, others, other, atLimit, kept);
            } else {
                playPatterns(rest, waiting - 1, hands, ranks, others, other, atLimit, kept + 1);
            }
        }

        private void tallyHands(int cls, int ranks, int others, int hands) {
            if (hands > 0) {
                tally[cls][ranks * tallyWidth + others] += hands;
            }
        }

        /**
         * The chance of the other second cards falling in a pattern that finishes a hand of
         * this class, times the number of hands of the class each pattern finishes, from a
         * shoe of this many cards holding this many of the split rank.
         */
        private double patternWeight(int cls, int rank, int cards) {
            int[] as = patternRanks[cls];
            int[] bs = patternOthers[cls];
            int others = cards - rank;
            double w = 0;
            for (int k = 0; k < as.length; k++) {
                int a = as[k];
                int b = bs[k];
                if (a > rank || b > others) {
                    continue;
                }
                double p = patternHands[cls][k];
                for (int i = 0; i < a; i++) {
                    p *= (rank - i) / (double) (cards - i);
                }
                for (int j = 0; j < b; j++) {
                    p *= (others - j) / (double) (cards - a - j);
                }
                w += p;
            }
            return w;
        }

        /** patternWeight once dN cards are gone from the split's shoe, dR of them of the split rank. */
        private double weightAt(int cls, int dR, int dN) {
            double[] row = weights[cls][dN];
            if (row == null) {
                row = new double[rankLeft + 1];
                Arrays.fill(row, Double.NaN);
                weights[cls][dN] = row;
            }
            double w = row[dR];
            if (Double.isNaN(w)) {
                w = patternWeight(cls, rankLeft - dR, cardsLeft - dN);
                row[dR] = w;
            }
            return w;
        }

        /**
         * Whether a hand of this class can be holding the cards that left this shoe: whether
         * some pattern that finishes it still has the cards it needs.
         */
        private boolean reachable(long comp, int n, int cls) {
            return weightAt(cls, rankLeft - count(comp, splitRank), cardsLeft - n) > 0;
        }

        /**
         * A finished split hand of this class, holding the split card and this second card,
         * played alone from this shoe with the dealer after it.
         */
        private double firstMoveValue(long comp, int n, int second, int cls) {
            if (!reachable(comp, n, cls)) {
                return 0;
            }
            if (isSplitBlackjack(second)) {
                return fixedValue(comp, n, rules.blackjackPayout, cls);
            }
            int hard = splitRank + second;
            boolean ace = splitRank == 1 || second == 1;
            switch (splitHandMove(second, cls == PAIR_KEPT)) {
                case Stand:
                    return stoodValue(comp, n, total(hard, ace), 1, cls);
                case Surrender:
                    return fixedValue(comp, n, -0.5, cls);
                case Hit:
                    return hitValue(comp, n, hard, ace, 2, cls);
                case Double:
                    return doubleValue(comp, n, hard, ace, cls);
                default:
                    throw new AssertionError("a pair that splits is not a finished hand");
            }
        }

        /** A split hand of this class that has hit and not busted, played alone from here. */
        private double liveValue(long comp, int n, int hard, boolean ace, int cards, int cls) {
            long key = live(hard, ace, cards, true, DONE) | (long) cls << CLASS_SHIFT;
            int slot = aloneHands.find(comp, key);
            if (slot >= 0) {
                return aloneHands.value(slot);
            }
            double v = 0;
            if (reachable(comp, n, cls)) {
                int total = total(hard, ace);
                PlayerMove move = ask(total, isSoft(hard, ace), cards, 0, true, false, total < 21,
                        false, false, false);
                v = move == PlayerMove.Stand
                        ? stoodValue(comp, n, total, 1, cls)
                        : hitValue(comp, n, hard, ace, cards, cls);
            }
            aloneHands.put(comp, key, v);
            return v;
        }

        private double hitValue(long comp, int n, int hard, boolean ace, int cards, int cls) {
            requireDraw(n);
            double v = 0;
            for (int y = 1; y <= 10; y++) {
                int c = count(comp, y);
                if (c == 0) {
                    continue;
                }
                long drawn = comp - one[y];
                int hardAfter = hard + y;
                v += c / (double) n * (hardAfter > 21
                        ? fixedValue(drawn, n - 1, -1, cls)
                        : liveValue(drawn, n - 1, hardAfter, ace || y == 1, cards + 1, cls));
            }
            return v;
        }

        private double doubleValue(long comp, int n, int hard, boolean ace, int cls) {
            requireDraw(n);
            double v = 0;
            for (int y = 1; y <= 10; y++) {
                int c = count(comp, y);
                if (c == 0) {
                    continue;
                }
                long drawn = comp - one[y];
                int hardAfter = hard + y;
                v += c / (double) n * (hardAfter > 21
                        ? fixedValue(drawn, n - 1, -2, cls)
                        : stoodValue(drawn, n - 1, total(hardAfter, ace || y == 1), 2, cls));
            }
            return v;
        }

        /** A hand of this class standing on total with this stake, against the dealer drawing from this shoe. */
        private double stoodValue(long comp, int n, int total, double stake, int cls) {
            weightedDealer(comp, n, cls, dealerScratch);
            double won = 0;
            for (int o = 0; o < OUTCOMES; o++) {
                won += SETTLE[total][o] * dealerScratch[o];
            }
            return stake * won;
        }

        /**
         * A fixed payoff to a hand of this class, counted when the hole card drawn from this
         * shoe is not a natural. The dealer's draws after the hole card can be dealt after
         * the other second cards, where they are certain to finish, so only the hole card is
         * needed before the patterns.
         */
        private double fixedValue(long comp, int n, double payoff, int cls) {
            int dR = rankLeft - count(comp, splitRank);
            int dN = cardsLeft - n + 1;
            double w = 0;
            for (int h = 1; h <= 10; h++) {
                int c = count(comp, h);
                if (c == 0 || h == natural) {
                    continue;
                }
                w += c / (double) n * weightAt(cls, dR + (h == splitRank ? 1 : 0), dN);
            }
            return payoff * w;
        }

        /**
         * The dealer's distribution from this shoe for a hand of this class: each dealer hand
         * weighted by the chance of the patterns that finish such a hand in the shoe the
         * dealer leaves. out[0..6] is in M's order; the dealer cannot run out here.
         */
        private void weightedDealer(long comp, int n, int cls, double[] out) {
            int slot = weightedFinals.find(comp, cls);
            if (slot >= 0) {
                weightedFinals.read(slot, out, 0);
                return;
            }
            int[] c = new int[11];
            for (int r = 1; r <= 10; r++) {
                c[r] = count(comp, r);
            }
            DealerHands d = dealer;
            // The weight of a dealer hand depends only on how many cards it takes and how
            // many of them are of the split rank, so it is looked up once per pair of those.
            int dR = rankLeft - c[splitRank];
            int dN = cardsLeft - n;
            int sizes = d.maxSize + 1;
            int kinds = Math.min(d.maxCount[splitRank], c[splitRank]) + 1;
            if (weightGrid.length < (d.maxCount[splitRank] + 1) * sizes) {
                weightGrid = new double[(d.maxCount[splitRank] + 1) * sizes];
            }
            double[] grid = weightGrid;
            for (int k = 0; k < kinds; k++) {
                for (int s = 1; s < sizes; s++) {
                    grid[k * sizes + s] = s < k || s > n ? 0 : weightAt(cls, dR + k, dN + s) * inverseFall[n][s];
                }
            }
            Arrays.fill(out, 0, OUTCOMES, 0);
            byte[] held = d.countOf[splitRank];
            for (int i = 0; i < d.entries; i++) {
                int size = d.size[i];
                if (size > n) {
                    continue;
                }
                double p = d.orders[i];
                for (int j = d.start[i]; j < d.start[i + 1]; j++) {
                    int r = d.ranks[j];
                    int k = d.counts[j];
                    if (k > c[r]) {
                        p = 0;
                        break;
                    }
                    p *= fall[r][c[r]][k];
                }
                if (p != 0) {
                    out[d.outcome[i]] += p * grid[held[i] * sizes + size];
                }
            }
            weightedFinals.put(comp, cls, out, 0, false);
        }

        // ---------------------------------------------------------- spot keys

        /**
         * The spot between two hands, with these hands waiting and this many in the round.
         * The count is dropped when no waiting hand holds a pair or the limit is reached,
         * since nothing reads it after that.
         */
        private long between(long queue, int hands) {
            boolean pairWaits = false;
            for (long q = queue; q != 0; q >>>= 4) {
                pairWaits |= (q & 15) == splitRank;
            }
            long kept = pairWaits && hands < maxHands ? hands : 0;
            return (queue << QUEUE_SHIFT) | (kept << HANDS_SHIFT);
        }

        private long live(int hard, boolean ace, int cards, boolean fromSplit, long next) {
            return next | hard | (ace ? 1L : 0) << ACE_SHIFT | (long) cards << CARDS_SHIFT
                    | (fromSplit ? 1L : 0) << SPLIT_SHIFT | 1L << LIVE_SHIFT;
        }

        // ---------------------------------------------------------- the policy

        /**
         * The policy's move for one decision. It is deterministic and sees only these fields,
         * so each distinct decision is asked once and its answer reused.
         */
        private PlayerMove ask(int total, boolean soft, int cards, int pairRank, boolean fromSplit,
                               boolean firstDecision, boolean canHit, boolean canDouble,
                               boolean canSplit, boolean canSurrender) {
            int key = total | (soft ? 1 : 0) << 5 | cards << 6 | pairRank << 11
                    | (fromSplit ? 1 : 0) << 15 | (firstDecision ? 1 : 0) << 16
                    | (canHit ? 1 : 0) << 17 | (canDouble ? 1 : 0) << 18
                    | (canSplit ? 1 : 0) << 19 | (canSurrender ? 1 : 0) << 20;
            byte known = answers[key];
            if (known != 0) {
                return MOVES[known - 1];
            }
            RoundPolicy.Decision d = new RoundPolicy.Decision(total, soft, cards, pairRank, up,
                    fromSplit, firstDecision, canHit, canDouble, canSplit, canSurrender);
            PlayerMove move = policy.choose(d);
            if (move == null || !d.isLegal(move)) {
                throw new IllegalStateException("the policy chose " + move + " at " + d + ", where it is not legal");
            }
            answers[key] = (byte) (move.ordinal() + 1);
            return move;
        }
    }

    private static void accumulate(double[] into, double[] from, double p) {
        for (int i = 0; i < WIDTH; i++) {
            into[i] += p * from[i];
        }
    }

    // ------------------------------------------------------------------ the memo

    /**
     * An open-addressing hash table from a pair of longs to a fixed number of doubles and one
     * flag. A round on an eight-deck shoe can visit millions of spots, and boxed keys and
     * per-entry arrays would cost several times the memory of these parallel arrays.
     */
    private static final class Memo {
        private final int stride;
        private long[] keyA;
        private long[] keyB;
        private double[] values;
        /** 0 for an empty slot, 1 for a filled one, 2 for a filled one whose flag is set. */
        private byte[] tags;
        private int size;

        Memo(int stride) {
            this.stride = stride;
            allocate(1 << 10);
        }

        private void allocate(int slots) {
            keyA = new long[slots];
            keyB = new long[slots];
            values = new double[slots * stride];
            tags = new byte[slots];
        }

        private static int hash(long a, long b) {
            long h = a * 0x9E3779B97F4A7C15L + b * 0xC2B2AE3D27D4EB4FL;
            h ^= h >>> 32;
            h *= 0xD6E8FEB86659FD93L;
            h ^= h >>> 32;
            return (int) h;
        }

        int find(long a, long b) {
            int mask = tags.length - 1;
            int i = hash(a, b) & mask;
            while (tags[i] != 0) {
                if (keyA[i] == a && keyB[i] == b) {
                    return i;
                }
                i = (i + 1) & mask;
            }
            return -1;
        }

        void read(int slot, double[] into, int offset) {
            System.arraycopy(values, slot * stride, into, offset, stride);
        }

        double value(int slot) {
            return values[slot * stride];
        }

        boolean flag(int slot) {
            return tags[slot] == 2;
        }

        /** Adds a key that is not already present. */
        void put(long a, long b, double[] from, int offset, boolean flag) {
            if ((size + 1) * 4L > tags.length * 3L) {
                grow();
            }
            int slot = emptySlot(a, b);
            keyA[slot] = a;
            keyB[slot] = b;
            tags[slot] = flag ? (byte) 2 : (byte) 1;
            System.arraycopy(from, offset, values, slot * stride, stride);
            size++;
        }

        /** Adds a key that is not already present, with one value and no flag. */
        void put(long a, long b, double value) {
            if ((size + 1) * 4L > tags.length * 3L) {
                grow();
            }
            int slot = emptySlot(a, b);
            keyA[slot] = a;
            keyB[slot] = b;
            tags[slot] = 1;
            values[slot * stride] = value;
            size++;
        }

        private int emptySlot(long a, long b) {
            int mask = tags.length - 1;
            int i = hash(a, b) & mask;
            while (tags[i] != 0) {
                i = (i + 1) & mask;
            }
            return i;
        }

        private void grow() {
            long[] oldA = keyA;
            long[] oldB = keyB;
            double[] oldValues = values;
            byte[] oldTags = tags;
            if (oldTags.length >= 1 << 30) {
                throw new UnsupportedOperationException("more spots than one table can hold");
            }
            allocate(oldTags.length * 2);
            for (int i = 0; i < oldTags.length; i++) {
                if (oldTags[i] != 0) {
                    int slot = emptySlot(oldA[i], oldB[i]);
                    keyA[slot] = oldA[i];
                    keyB[slot] = oldB[i];
                    tags[slot] = oldTags[i];
                    System.arraycopy(oldValues, i * stride, values, slot * stride, stride);
                }
            }
        }
    }
}
