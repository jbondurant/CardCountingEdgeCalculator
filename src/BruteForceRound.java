import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The value of one round, as ROUND_CONTRACT.md defines it, by following every card.
 *
 * This is the check on ExactRound, so it is written to be read against the contract
 * rather than to be fast. The round is played in the order it is played at the table:
 * the hole card comes off the shoe, then the player's hands are played one after
 * another, then the dealer draws, then every hand is settled. Each time a card is
 * needed, every rank still in the shoe is tried, weighted by its count over the cards
 * left, and the rest of the round is followed from there with that card gone. Nothing
 * is remembered between branches and no two branches are combined, so the value is the
 * plain sum over every way the round can go, each weighted by its probability.
 *
 * The state of the round is copied at each draw, so a branch cannot disturb its
 * siblings. That makes it slow: it is meant for shoes of a couple of dozen cards.
 *
 * The walk is written once, over what it adds up (Tally): the value of the round for
 * valueOfFirstMove, or, for the tests of the risk figures, the probability of each payoff
 * (distributionOfFirstMove). Both follow the same branches in the same order and weight
 * them the same way, so the distribution is the value's own walk, not a second one.
 *
 * Comments quote the contract where a sentence of it is implemented.
 */
public final class BruteForceRound implements RoundValuer {

    private final RoundRules rules;

    public BruteForceRound(RoundRules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules are required");
        }
        this.rules = rules;
    }

    @Override
    public double valueOfFirstMove(int[] shoe, int p1, int p2, int up, PlayerMove first,
                                   RoundPolicy policy) {
        requireArguments(shoe, p1, p2, up, policy);
        return new Play<>(VALUE, up, first, policy).value(shoe, p1, p2);
    }

    /**
     * The whole distribution of the round's payoff, which valueOfFirstMove gives the mean of:
     * payoff to probability, on the same terms (conditional on the dealer not having a natural,
     * in units of the dealt hand's stake, no insurance). Its probabilities add up to 1 and its
     * mean is valueOfFirstMove's value, each up to rounding. It throws exactly what
     * valueOfFirstMove throws on the same arguments.
     *
     * Only the tests use it: the swings on the results page rest on a round's second moment, so
     * the simulator's histogram of payoffs is held to this one on small shoes (PLAN_COMPUTE.md
     * 2.4, check 1). It is as slow as the value's walk, and builds a map at every branch.
     */
    SortedMap<Double, Double> distributionOfFirstMove(int[] shoe, int p1, int p2, int up, PlayerMove first,
                                                      RoundPolicy policy) {
        requireArguments(shoe, p1, p2, up, policy);
        return new Play<>(DISTRIBUTION, up, first, policy).value(shoe, p1, p2);
    }

    private static void requireArguments(int[] shoe, int p1, int p2, int up, RoundPolicy policy) {
        // "A shoe is an int[11]: shoe[r] is the number of cards of rank r left."
        if (shoe == null || shoe.length != 11) {
            throw new IllegalArgumentException("a shoe is an int[11] indexed by rank 1 to 10");
        }
        for (int r = 1; r <= 10; r++) {
            if (shoe[r] < 0) {
                throw new IllegalArgumentException("shoe[" + r + "] is negative");
            }
        }
        // "Ranks are 1 to 10."
        for (int card : new int[]{p1, p2, up}) {
            if (card < 1 || card > 10) {
                throw new IllegalArgumentException("a card's rank is 1 to 10, not " + card);
            }
        }
        if (policy == null) {
            throw new IllegalArgumentException("a policy is required");
        }
    }

    // ------------------------------------------------------------------ the round

    /** How a hand ended. Null on a hand while it is still being played. */
    private enum End {
        /** Stood on a total of 21 or less, compared with the dealer at stake 1. */
        STOOD,
        /** Doubled without passing 21, compared with the dealer at stake 2. */
        DOUBLED,
        /** Passed 21: loses its stake whatever the dealer does. */
        BUST,
        /** Loses 0.5. */
        SURRENDERED,
        /** The player's natural, or a split ace and ten under blackjackOnSplitPairs. */
        BLACKJACK
    }

    /** One player hand. */
    private static final class Hand {
        final List<Integer> cards = new ArrayList<>();
        /** 0 for the dealt hand; for a hand a split made, the rank of the pair it came from. */
        final int splitRank;
        double stake = 1;
        /** A move has been made on it, so its next decision is not a first decision. */
        boolean acted;
        End end;

        Hand(int splitRank) {
            this.splitRank = splitRank;
        }

        Hand copy() {
            Hand h = new Hand(splitRank);
            h.cards.addAll(cards);
            h.stake = stake;
            h.acted = acted;
            h.end = end;
            return h;
        }

        boolean fromSplit() {
            return splitRank != 0;
        }
    }

    /** Everything about the round at one moment. Copied whole at every draw. */
    private static final class State {
        /** The cards left, shoe[r] of rank r. */
        final int[] shoe;
        /** The player's hands, in the order they are played. */
        final List<Hand> hands = new ArrayList<>();
        /** The hand whose turn it is: hands.size() once every hand has been played. */
        int current;
        /** Hands a split has made that are still owed their second card, in the order they get it. */
        final List<Integer> owed = new ArrayList<>();
        /** The up-card, the hole card, then whatever the dealer draws. */
        final List<Integer> dealer = new ArrayList<>();

        State(int[] shoe) {
            this.shoe = shoe;
        }

        State copy() {
            State s = new State(shoe.clone());
            for (Hand h : hands) {
                s.hands.add(h.copy());
            }
            s.current = current;
            s.owed.addAll(owed);
            s.dealer.addAll(dealer);
            return s;
        }
    }

    /**
     * What the walk adds up over the ways the round can go. Every weighted sum of the walk is
     * built as zero(), then plus(sum, p, v) once per branch in the order the branches are
     * tried, so an instance sees exactly the arithmetic the walk does.
     */
    private interface Tally<V> {
        V zero();

        /** A settled round: what it paid. */
        V payoff(double x);

        /** sum plus p times v, where v is what one branch gave and p its probability. */
        V plus(V sum, double p, V v);

        /** v over d: the renormalization over hole cards that do not give the dealer a natural. */
        V over(V v, double d);
    }

    /**
     * The value of the round. Each step is the double arithmetic the walk did before it was
     * written over a Tally (sum += p * v, then sum / notNatural), with the same operands in the
     * same order; Java never fuses or reorders double arithmetic, so the values are the same to
     * the bit, and BruteForceDistributionTest holds them to what the walk gave before.
     */
    private static final Tally<Double> VALUE = new Tally<Double>() {
        @Override
        public Double zero() {
            return 0.0;
        }

        @Override
        public Double payoff(double x) {
            return x;
        }

        @Override
        public Double plus(Double sum, double p, Double v) {
            return sum + p * v;
        }

        @Override
        public Double over(Double v, double d) {
            return v / d;
        }
    };

    /**
     * The probability of each payoff. A payoff is a sum of stakes, halves and blackjackPayout,
     * so equal payoffs are equal doubles and meet at one key; 0 is kept as +0.0, since a
     * TreeMap of Doubles tells -0.0 from 0.0. A sum is built only from maps its own branches
     * made, so plus may add into it in place.
     */
    private static final Tally<SortedMap<Double, Double>> DISTRIBUTION = new Tally<SortedMap<Double, Double>>() {
        @Override
        public SortedMap<Double, Double> zero() {
            return new TreeMap<>();
        }

        @Override
        public SortedMap<Double, Double> payoff(double x) {
            SortedMap<Double, Double> m = new TreeMap<>();
            m.put(x == 0 ? 0.0 : x, 1.0);
            return m;
        }

        @Override
        public SortedMap<Double, Double> plus(SortedMap<Double, Double> sum, double p, SortedMap<Double, Double> v) {
            for (Map.Entry<Double, Double> e : v.entrySet()) {
                sum.merge(e.getKey(), p * e.getValue(), Double::sum);
            }
            return sum;
        }

        @Override
        public SortedMap<Double, Double> over(SortedMap<Double, Double> v, double d) {
            for (Map.Entry<Double, Double> e : v.entrySet()) {
                e.setValue(e.getValue() / d);
            }
            return v;
        }
    };

    /** What happens after a card is drawn, given the round with that card gone and its rank. */
    private interface AfterCard<V> {
        V value(State next, int card);
    }

    /** One call to valueOfFirstMove or distributionOfFirstMove, adding up what tally adds. */
    private final class Play<V> {
        final Tally<V> tally;
        final int up;
        final PlayerMove first;
        final RoundPolicy policy;

        Play(Tally<V> tally, int up, PlayerMove first, RoundPolicy policy) {
            this.tally = tally;
            this.up = up;
            this.first = first;
            this.policy = policy;
        }

        V value(int[] shoe, int p1, int p2) {
            // "Order of checks. Before anything is dealt: a malformed argument ... and an
            // illegal first throw IllegalArgumentException. Then the hole card is dealt".
            // The malformed arguments were refused by requireArguments; first is checked
            // here, and only then is the hole card dealt.
            // "first is played as the dealt hand's first decision and must be legal there
            // (otherwise throw IllegalArgumentException)."
            boolean natural = isAceAndTen(p1, p2);
            if (natural) {
                // "If p1 and p2 are an ace and a ten ... first must be Stand."
                if (first != PlayerMove.Stand) {
                    throw new IllegalArgumentException("the player's natural can only stand, not " + first);
                }
            } else {
                Hand dealt = new Hand(0);
                dealt.cards.add(p1);
                dealt.cards.add(p2);
                RoundPolicy.Decision d = decision(dealt, 1);
                if (first == null || !d.isLegal(first)) {
                    throw new IllegalArgumentException(first + " is not legal as the first move on " + d);
                }
            }

            // "The dealer's hole card has also been dealt, face down, before the player
            // acts: it is one uniformly random card of shoe, removed before any player
            // draw." "shoe ... Not modified": work on a copy. The player's natural too:
            // "The hole card is still dealt first, so the checks above on the shoe still
            // apply".
            int[] undealt = shoe.clone();
            int left = cardsLeft(undealt);
            // "Then the hole card is dealt: an empty shoe throws IllegalStateException"
            if (left == 0) {
                throw new IllegalStateException("the shoe is empty, so no hole card can be dealt");
            }
            V sum = tally.zero();
            double notNatural = 0;
            for (int hole = 1; hole <= 10; hole++) {
                if (undealt[hole] == 0) {
                    continue;
                }
                // "rank r with probability shoe[r] / (shoe[1] + ... + shoe[10])"
                double p = (double) undealt[hole] / left;
                // "Peek. If up is 1 and the hole card is a 10, or up is 10 and the hole
                // card is a 1, the dealer has a natural and the round is excluded." It is
                // not played at all, so "a decision reachable only when the hole card makes
                // a natural is never put to the policy."
                if (isAceAndTen(up, hole)) {
                    continue;
                }
                notNatural += p;

                State s = new State(undealt.clone());
                s.shoe[hole]--;
                s.dealer.add(up);
                s.dealer.add(hole);
                Hand dealt = new Hand(0);
                dealt.cards.add(p1);
                dealt.cards.add(p2);
                if (natural) {
                    // "the round is settled at once: the player is paid blackjackPayout
                    // (the dealer has no natural, by the conditioning above)."
                    dealt.end = End.BLACKJACK;
                }
                s.hands.add(dealt);
                sum = tally.plus(sum, p, play(s));
            }
            // "a shoe whose every card would give the dealer a natural throws
            // IllegalArgumentException."
            if (notNatural == 0) {
                throw new IllegalArgumentException("every hole card left gives the dealer a natural");
            }
            // "The value returned is the expected payoff conditional on the dealer not
            // having a natural": renormalize over the hole cards that are not naturals.
            return tally.over(sum, notNatural);
        }

        /**
         * "Every draw takes one card uniformly at random from the cards left: rank r with
         * probability shoe[r] / (shoe[1] + ... + shoe[10]), and removes it." Tries each
         * rank in turn on its own copy of the round.
         */
        V overNextCard(State s, AfterCard<V> then) {
            int left = cardsLeft(s.shoe);
            // "If a draw is needed and the shoe is empty, throw IllegalStateException."
            if (left == 0) {
                throw new IllegalStateException("a card is needed and the shoe is empty");
            }
            V sum = tally.zero();
            for (int r = 1; r <= 10; r++) {
                if (s.shoe[r] == 0) {
                    continue;
                }
                double p = (double) s.shoe[r] / left;
                State next = s.copy();
                next.shoe[r]--;
                sum = tally.plus(sum, p, then.value(next, r));
            }
            return sum;
        }

        /** The value of the round from state s on. s belongs to this call and may be changed. */
        V play(State s) {
            // Splitting: "Draw the second card of the first new hand, then the second card
            // of the second new hand, both at once." Owed cards come before anything else.
            if (!s.owed.isEmpty()) {
                return overNextCard(s, (next, card) -> {
                    int owedHand = next.owed.remove(0);
                    next.hands.get(owedHand).cards.add(card);
                    return play(next);
                });
            }
            // The dealer: "Once every player hand has ended".
            if (s.current == s.hands.size()) {
                return dealerThenSettle(s);
            }
            Hand h = s.hands.get(s.current);
            if (h.end != null) {
                s.current++;
                return play(s);
            }

            // "A split hand of an ace and a ten. If blackjackOnSplitPairs, it is a
            // blackjack: it ends at once, with no decision, and is paid blackjackPayout
            // whatever the dealer makes." Otherwise it goes on as an ordinary split hand on
            // 21. A split ace is no different here from any other split hand: "A split ace
            // is a split hand like any other", and its first decision comes next.
            if (h.fromSplit() && !h.acted && rules.blackjackOnSplitPairs
                    && isAceAndTen(h.cards.get(0), h.cards.get(1))) {
                h.end = End.BLACKJACK;
                return play(s);
            }

            RoundPolicy.Decision d = decision(h, s.hands.size());
            PlayerMove move;
            if (!h.fromSplit() && !h.acted) {
                // "first is played as the dealt hand's first decision" (checked legal above).
                move = first;
            } else {
                // "Every later decision, of the dealt hand or of any hand a split creates,
                // comes from policy, which is asked at every decision, even one where only
                // Stand is legal." "If the policy returns a move that is not legal, throw
                // IllegalStateException."
                move = policy.choose(d);
                if (move == null || !d.isLegal(move)) {
                    throw new IllegalStateException("the policy chose " + move + ", not legal on " + d);
                }
            }
            return apply(s, move);
        }

        /** Plays move on the current hand. */
        V apply(State s, PlayerMove move) {
            Hand h = s.hands.get(s.current);
            switch (move) {
                case Stand:
                    // "Stand: the hand ends on its total, with its stake."
                    h.end = End.STOOD;
                    return play(s);
                case Surrender:
                    // "Surrender: the hand ends and loses half its stake, 0.5."
                    h.end = End.SURRENDERED;
                    return play(s);
                case Hit:
                    // "Hit: draw one card. Over 21 loses the stake and ends the hand.
                    // Otherwise the policy decides again; that decision is not a first
                    // decision".
                    return overNextCard(s, (next, card) -> {
                        Hand nh = next.hands.get(next.current);
                        nh.acted = true;
                        nh.cards.add(card);
                        if (total(nh.cards) > 21) {
                            nh.end = End.BUST;
                        }
                        return play(next);
                    });
                case Double:
                    // "Double: the stake becomes 2, draw exactly one card, and the hand
                    // ends. Over 21 loses 2."
                    return overNextCard(s, (next, card) -> {
                        Hand nh = next.hands.get(next.current);
                        nh.acted = true;
                        nh.stake = 2;
                        nh.cards.add(card);
                        nh.end = total(nh.cards) > 21 ? End.BUST : End.DOUBLED;
                        return play(next);
                    });
                case Split: {
                    // "A hand holding a pair (r, r) that splits becomes two hands, each
                    // holding one r, each with a stake of 1." The first new hand takes the
                    // split hand's place and the second goes right after it, so "play the
                    // first new hand to completion, including any split of its own, and
                    // only then play the second" is simply playing the list in order.
                    int r = h.cards.get(0);
                    Hand firstNew = new Hand(r);
                    firstNew.cards.add(r);
                    Hand secondNew = new Hand(r);
                    secondNew.cards.add(r);
                    s.hands.set(s.current, firstNew);
                    s.hands.add(s.current + 1, secondNew);
                    s.owed.add(s.current);
                    s.owed.add(s.current + 1);
                    return play(s);
                }
                default:
                    throw new IllegalStateException("unknown move " + move);
            }
        }

        /** The dealer's turn, then settlement. */
        V dealerThenSettle(State s) {
            // "The dealer's outcome only matters for hands that ended by standing or
            // doubling without passing 21. When there are none, the dealer does not draw,
            // so a shoe too short for the dealer does not matter in that case."
            boolean dealerMatters = false;
            for (Hand h : s.hands) {
                if (h.end == End.STOOD || h.end == End.DOUBLED) {
                    dealerMatters = true;
                }
            }
            if (!dealerMatters) {
                return settle(s);
            }
            return dealerDraws(s);
        }

        /**
         * "The dealer turns the hole card and draws from the shoe as the player hands left
         * it, until the total is 17 or more. The dealer hits a soft 17 if and only if
         * hitsSoft17."
         */
        V dealerDraws(State s) {
            int t = total(s.dealer);
            boolean mustHit = t < 17 || (t == 17 && isSoft(s.dealer) && rules.hitsSoft17);
            if (!mustHit) {
                return settle(s);
            }
            return overNextCard(s, (next, card) -> {
                next.dealer.add(card);
                return dealerDraws(next);
            });
        }

        /** "Settling a hand", summed over every hand the round ended with. */
        V settle(State s) {
            double sum = 0;
            for (Hand h : s.hands) {
                switch (h.end) {
                    case BLACKJACK:
                        // A player's natural, or a split hand "paid blackjackPayout
                        // whatever the dealer makes". Its stake is 1.
                        sum += rules.blackjackPayout;
                        break;
                    case SURRENDERED:
                        sum -= 0.5;
                        break;
                    case BUST:
                        // "Over 21 loses the stake" (2 for a double).
                        sum -= h.stake;
                        break;
                    case STOOD:
                    case DOUBLED: {
                        // "For a hand that stood or doubled on a total t of 21 or less,
                        // with stake s". Only reached when the dealer has played.
                        int t = total(h.cards);
                        int dealerTotal = total(s.dealer);
                        if (dealerTotal > 21) {
                            sum += h.stake;           // "the dealer passes 21: +s"
                        } else if (t > dealerTotal) {
                            sum += h.stake;           // "t beats the dealer's total: +s"
                        } else if (t == dealerTotal) {
                            sum += 0;                 // "equal totals: 0"
                        } else {
                            sum -= h.stake;           // "the dealer's total beats t: -s"
                        }
                        break;
                    }
                    default:
                        throw new IllegalStateException("a hand was settled before it ended");
                }
            }
            return tally.payoff(sum);
        }

        /**
         * The decision on hand h in a round that now holds handsInRound hands, with the
         * moves the "Legal moves" table allows.
         */
        RoundPolicy.Decision decision(Hand h, int handsInRound) {
            int total = total(h.cards);
            boolean soft = isSoft(h.cards);
            int numCards = h.cards.size();
            // "A pair is exactly two cards of the same rank."
            int pairRank = numCards == 2 && h.cards.get(0).equals(h.cards.get(1)) ? h.cards.get(0) : 0;
            boolean fromSplit = h.fromSplit();
            // "A first decision is a hand's first decision on exactly two cards: the dealt
            // hand before anything is done to it, or a split hand as soon as it has its
            // second card."
            boolean firstDecision = numCards == 2 && !h.acted;
            // "Split aces. A split ace is a split hand like any other, with one
            // difference: unless canHitSplitAces, it may not be hit." That difference is
            // the Hit row below and nothing else: its first decision offers "Stand; Double
            // if 1 is in doubleAfterSplit; Split if it drew another ace and the limit
            // allows; and Surrender if surrender and surrenderAfterSplit", which is what the
            // other rows give any split hand of rank 1.
            boolean splitAceThatMayNotBeHit = h.splitRank == 1 && !rules.canHitSplitAces;

            // "Hit: the total is under 21, and the hand is not a split ace while
            // canHitSplitAces is false"
            boolean canHit = total < 21 && !splitAceThatMayNotBeHit;
            // "Double: first decision only. The dealt hand always; a split hand only if its
            // split rank is in doubleAfterSplit"
            boolean canDouble = firstDecision && (!fromSplit || rules.doubleAfterSplit[h.splitRank]);
            // "Split: first decision only, on a pair, and only if the round would then have
            // no more hands than the limit for that rank". "Hands in the round counts every
            // hand, finished or not."
            boolean canSplit = firstDecision && pairRank != 0
                    && handsInRound + 1 <= rules.maxHandsFor(pairRank);
            // "Surrender: first decision only. The dealt hand if surrender; a split hand if
            // surrender and surrenderAfterSplit"
            boolean canSurrender = firstDecision && rules.surrender
                    && (!fromSplit || rules.surrenderAfterSplit);
            return new RoundPolicy.Decision(total, soft, numCards, pairRank, up, fromSplit,
                    firstDecision, canHit, canDouble, canSplit, canSurrender);
        }
    }

    // ------------------------------------------------------------------ hand totals

    /**
     * "A hand's total counts each ace as 1, then adds 10 once if the result stays at 21 or
     * under."
     */
    private static int total(List<Integer> cards) {
        int sum = 0;
        for (int c : cards) {
            sum += c;
        }
        return cards.contains(1) && sum + 10 <= 21 ? sum + 10 : sum;
    }

    /** "The hand is soft when that 10 was added." */
    private static boolean isSoft(List<Integer> cards) {
        int sum = 0;
        for (int c : cards) {
            sum += c;
        }
        return cards.contains(1) && sum + 10 <= 21;
    }

    private static boolean isAceAndTen(int a, int b) {
        return (a == 1 && b == 10) || (a == 10 && b == 1);
    }

    private static int cardsLeft(int[] shoe) {
        int left = 0;
        for (int r = 1; r <= 10; r++) {
            left += shoe[r];
        }
        return left;
    }
}
