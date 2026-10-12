package sim;

/**
 * One round of blackjack dealt from a fixed order of cards, as ROUND_CONTRACT.md defines a round
 * and SHOE_SPEC.md A4 says which cards it takes: the player's first card, the up-card, the
 * player's second card, the hole card, then every draw in order, nothing burned. The dealer peeks;
 * insurance is decided before the peek; split hands get both second cards before either is
 * played, and the first is played out (resplits included) before the second; the dealer draws
 * only if some hand stood or doubled without passing 21.
 *
 * <p>Unlike the contract's question, which values a round given no dealer natural, this plays a
 * real round, dealer naturals included, and reports what it paid ({@link #main},
 * {@link #insurance}) and where the next round starts ({@link #next}).
 *
 * <p>A round object is reused from round to round to keep allocation out of the inner loop; it
 * is not thread-safe, and each thread has its own.
 */
public final class Round {

    public static final int STAND = 0;
    public static final int HIT = 1;
    public static final int DOUBLE = 2;
    public static final int SPLIT = 3;
    public static final int SURRENDER = 4;
    /** The bits of a decision's legal moves, as chart.tsv numbers them (SHOE_SPEC.md B1); Stand is always legal. */
    public static final int L_HIT = 1;
    public static final int L_DOUBLE = 2;
    public static final int L_SPLIT = 4;
    public static final int L_SURRENDER = 8;
    public static final String MOVES = "SHDPR";

    /** The cards a round is dealt from: the kind at each position of the order. */
    public interface Deck {
        /** The kind at position i; throws IllegalStateException past the last card. */
        int kind(int i);
    }

    /** What a player is asked. One object, refilled before every question. */
    public static final class Decision {
        /** The dealer's up-card, by rank. */
        public int up;
        public int total;
        public boolean soft;
        /** Cards in the hand. */
        public int cards;
        /** The rank of a hand of exactly two equal-rank cards, else 0. */
        public int pair;
        public boolean fromSplit;
        /** The hand's first decision, on exactly two cards. */
        public boolean first;
        /** Legal moves besides Stand, as L_ bits. */
        public int legal;
        /** The hand's first two cards by rank (valid at a first decision), and the rank it was split from (0 if none). */
        public int c1;
        public int c2;
        public int splitRank;
        /** The deal: the player's two cards and the up-card, by rank and by kind. */
        public int p1;
        public int p2;
        public int p1Kind;
        public int p2Kind;
        public int upKind;
        /**
         * The cards the player has seen this round, by kind, in the order seen: the deal's three
         * (four with the hole-card plant), then every card the player drew, split hands'
         * included. seenAll is how many so far; dealSeen how many the deal showed.
         */
        public int[] seen;
        public int seenAll;
        public int dealSeen;
    }

    /** How a player answers. A player must be deterministic and cannot see the hole card or the cards to come. */
    public interface Player {
        /** Insure? Asked only when the up-card is an ace, before the peek. */
        boolean insure(Decision d);

        /** The move at a decision; it must be legal. */
        int choose(Decision d);
    }

    private final Rules rules;
    private final boolean holeCardSeen;
    private final Decision d = new Decision();

    // The hands of the round, by index; at most four.
    private final int[] hard = new int[4];
    private final int[] aces = new int[4];
    private final int[] count = new int[4];
    private final int[] first2 = new int[8];
    private final int[] splitFrom = new int[4];
    private final double[] stake = new double[4];
    private final int[] state = new int[4];
    private static final int OPEN = 0;
    private static final int STOOD = 1;
    private static final int BUST = 2;
    private static final int SURRENDERED = 3;

    private Deck deck;
    private int pos;
    private int hands;
    private Player player;

    /** After play: the next card's position, the hands' result and the insurance's, per unit bet. */
    public int next;
    public double main;
    public double insurance;
    public boolean insured;
    public boolean dealerNatural;
    public boolean playerNatural;
    public int handsPlayed;
    /** The hole card's rank. */
    public int hole;
    /** The dealer's final total, or 0 when the dealer did not draw. */
    public int dealerTotal;

    public Round(Rules rules, Plants plants) {
        this.rules = rules;
        this.holeCardSeen = plants.on(Plants.HOLE_CARD);
        d.seen = new int[Shoe.CARDS];
    }

    public Round(Rules rules) {
        this(rules, Plants.NONE);
    }

    public Rules rules() {
        return rules;
    }

    /**
     * Plays the round that starts at position {@code from} of the deck. If {@code firstMove} is
     * 0 or more, it is the dealt hand's first decision (it must be legal there) and the player
     * answers every other; -1 lets the player answer them all.
     */
    public void play(Deck deck, int from, Player player, int firstMove) {
        this.deck = deck;
        this.player = player;
        this.pos = from;
        int p1Kind = draw();
        int upKind = draw();
        int p2Kind = draw();
        int holeKind = draw();
        int p1 = Shoe.rank(p1Kind);
        int up = Shoe.rank(upKind);
        int p2 = Shoe.rank(p2Kind);
        hole = Shoe.rank(holeKind);
        d.up = up;
        d.upKind = upKind;
        d.p1 = p1;
        d.p2 = p2;
        d.p1Kind = p1Kind;
        d.p2Kind = p2Kind;
        d.seen[0] = p1Kind;
        d.seen[1] = upKind;
        d.seen[2] = p2Kind;
        d.seenAll = 3;
        if (holeCardSeen) {
            // planted: the hole card counted as soon as it is dealt
            d.seen[d.seenAll++] = holeKind;
        }
        d.dealSeen = d.seenAll;
        main = 0;
        insurance = 0;
        insured = false;
        dealerTotal = 0;
        hands = 1;
        playerNatural = isNatural(p1, p2);
        dealerNatural = isNatural(up, hole);
        // Insurance is decided on the deal, before the peek, whatever the player holds.
        if (up == 1) {
            setHand(0, p1, p2, 0);
            fillDecision(0, true, legalAt(0, true));
            insured = player.insure(d);
            if (insured) {
                insurance = hole == 10 ? 2 * rules.insuranceStake : -rules.insuranceStake;
            }
        }
        if (dealerNatural) {
            main = playerNatural ? 0 : -1;
            finish(1);
            return;
        }
        if (playerNatural) {
            if (firstMove > STAND) {
                throw new IllegalArgumentException("a natural's only move is Stand");
            }
            main = rules.blackjackPayout;
            finish(1);
            return;
        }
        setHand(0, p1, p2, 0);
        playHand(0, firstMove);
        boolean dealerDraws = false;
        for (int h = 0; h < hands; h++) {
            dealerDraws |= state[h] == STOOD;
        }
        if (dealerDraws) {
            dealerTotal = dealerPlays(up, hole);
        }
        for (int h = 0; h < hands; h++) {
            main += settle(h, dealerTotal);
        }
        finish(hands);
    }

    private void finish(int handsPlayed) {
        this.handsPlayed = handsPlayed;
        next = pos;
        deck = null;
        player = null;
    }

    private static boolean isNatural(int a, int b) {
        return (a == 1 && b == 10) || (a == 10 && b == 1);
    }

    private int draw() {
        return deck.kind(pos++);
    }

    /** A hand of two cards, by rank; split is the rank it was split from, 0 for the dealt hand. */
    private void setHand(int h, int c1, int c2, int split) {
        hard[h] = c1 + c2;
        aces[h] = (c1 == 1 ? 1 : 0) + (c2 == 1 ? 1 : 0);
        count[h] = 2;
        first2[2 * h] = c1;
        first2[2 * h + 1] = c2;
        splitFrom[h] = split;
        stake[h] = 1;
        state[h] = OPEN;
    }

    private int total(int h) {
        return aces[h] > 0 && hard[h] + 10 <= 21 ? hard[h] + 10 : hard[h];
    }

    private boolean soft(int h) {
        return aces[h] > 0 && hard[h] + 10 <= 21;
    }

    /** ROUND_CONTRACT.md's legal moves for hand h; Surrender is never legal at this table. */
    private int legalAt(int h, boolean first) {
        int legal = 0;
        boolean splitAce = splitFrom[h] == 1;
        if (total(h) < 21 && !(splitAce && !rules.hitSplitAces)) {
            legal |= L_HIT;
        }
        if (first) {
            if (splitFrom[h] == 0 || rules.doubleAfterSplit[splitFrom[h]]) {
                legal |= L_DOUBLE;
            }
            int c1 = first2[2 * h];
            if (c1 == first2[2 * h + 1] && hands + 1 <= rules.maxHands(c1)) {
                legal |= L_SPLIT;
            }
        }
        return legal;
    }

    private void fillDecision(int h, boolean first, int legal) {
        d.total = total(h);
        d.soft = soft(h);
        d.cards = count[h];
        d.first = first;
        d.c1 = first2[2 * h];
        d.c2 = first2[2 * h + 1];
        d.pair = first && d.c1 == d.c2 ? d.c1 : 0;
        d.fromSplit = splitFrom[h] != 0;
        d.splitRank = splitFrom[h];
        d.legal = legal;
    }

    private static boolean isLegal(int move, int legal) {
        switch (move) {
            case STAND:
                return true;
            case HIT:
                return (legal & L_HIT) != 0;
            case DOUBLE:
                return (legal & L_DOUBLE) != 0;
            case SPLIT:
                return (legal & L_SPLIT) != 0;
            case SURRENDER:
                return (legal & L_SURRENDER) != 0;
            default:
                return false;
        }
    }

    /** Plays hand h to its end; a split plays both new hands, the first to its end before the second. */
    private void playHand(int h, int forcedFirst) {
        boolean first = true;
        while (true) {
            int legal = legalAt(h, first);
            fillDecision(h, first, legal);
            int move = forcedFirst >= 0 && first ? forcedFirst : player.choose(d);
            if (!isLegal(move, legal)) {
                if (forcedFirst >= 0 && first) {
                    throw new IllegalArgumentException("first move " + move + " is not legal here");
                }
                throw new IllegalStateException("move " + move + " is not legal: total " + d.total
                        + (d.soft ? " soft" : "") + " against " + d.up + ", legal bits " + legal);
            }
            forcedFirst = -1;
            switch (move) {
                case STAND:
                    state[h] = STOOD;
                    return;
                case SURRENDER:
                    state[h] = SURRENDERED;
                    return;
                case DOUBLE:
                    stake[h] = 2;
                    hit(h);
                    state[h] = hard[h] > 21 ? BUST : STOOD;
                    return;
                case HIT:
                    hit(h);
                    if (hard[h] > 21) {
                        state[h] = BUST;
                        return;
                    }
                    first = false;
                    break;
                case SPLIT:
                    split(h);
                    return;
                default:
                    throw new IllegalStateException("move " + move);
            }
        }
    }

    private void hit(int h) {
        int kind = draw();
        d.seen[d.seenAll++] = kind;
        int r = Shoe.rank(kind);
        hard[h] += r;
        if (r == 1) {
            aces[h]++;
        }
        count[h]++;
    }

    private void split(int h) {
        int r = first2[2 * h];
        int other = hands++;
        int kind1 = draw();
        d.seen[d.seenAll++] = kind1;
        int kind2 = draw();
        d.seen[d.seenAll++] = kind2;
        setHand(h, r, Shoe.rank(kind1), r);
        setHand(other, r, Shoe.rank(kind2), r);
        playHand(h, -1);
        playHand(other, -1);
    }

    /** The dealer's total after drawing: to 17 or more, hitting a soft 17 when the rules say so. */
    private int dealerPlays(int up, int holeRank) {
        int sum = up + holeRank;
        int acesHeld = (up == 1 ? 1 : 0) + (holeRank == 1 ? 1 : 0);
        while (true) {
            boolean soft = acesHeld > 0 && sum + 10 <= 21;
            int total = soft ? sum + 10 : sum;
            if (total > 17 || (total == 17 && !(soft && rules.hitsSoft17))) {
                return total;
            }
            int r = Shoe.rank(draw());
            sum += r;
            if (r == 1) {
                acesHeld++;
            }
        }
    }

    /** A hand's result in units: ROUND_CONTRACT.md's settlement. */
    private double settle(int h, int dealer) {
        switch (state[h]) {
            case BUST:
                return -stake[h];
            case SURRENDERED:
                return -0.5 * stake[h];
            case STOOD:
                int t = total(h);
                if (dealer > 21 || t > dealer) {
                    return stake[h];
                }
                return t == dealer ? 0 : -stake[h];
            default:
                throw new IllegalStateException("hand " + h + " never ended");
        }
    }
}
