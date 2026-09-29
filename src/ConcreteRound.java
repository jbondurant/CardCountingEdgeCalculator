import java.util.ArrayList;
import java.util.List;

/**
 * One round dealt from real cards in a fixed order, played as ROUND_CONTRACT.md plays it.
 *
 * ExactRound values a round over every card that could come; this plays the one round
 * that the cards in front of it make. The comparison of counting systems uses it to take
 * cards out of a shoe in the amounts real play takes them, and it is checked against
 * ExactRound: averaged over every order of a small shoe, its payoff is ExactRound's value.
 *
 * Cards are dealt as at the table: the player's first card, the dealer's up-card, the
 * player's second card, the dealer's hole card, then every draw in turn. Here the policy
 * makes every decision, the first included. A dealer natural ends the round before any
 * decision, and the rules of splitting, doubling, surrendering and the dealer's draw are
 * the contract's.
 */
final class ConcreteRound {

    /** What one round did. */
    static final class Result {
        /** Won or lost across every hand, in units of the original bet. */
        final double payoff;
        /** Index of the first card the round did not use. */
        final int next;
        final boolean dealerNatural;
        final boolean playerNatural;
        /** Hands the round ended with, a split counting both. */
        final int hands;

        Result(double payoff, int next, boolean dealerNatural, boolean playerNatural, int hands) {
            this.payoff = payoff;
            this.next = next;
            this.dealerNatural = dealerNatural;
            this.playerNatural = playerNatural;
            this.hands = hands;
        }
    }

    private final RoundRules rules;
    private final RoundPolicy policy;
    private final int[] ranks;
    private int next;
    private final int up;
    private int handsInRound = 1;
    private final List<Hand> finished = new ArrayList<>();

    private static final class Hand {
        final List<Integer> cards = new ArrayList<>();
        final int splitRank;
        double stake = 1;
        /** Stood or doubled without passing 21, so settled against the dealer. */
        boolean standing;
        /** Settled on its own: a bust, a surrender or a split blackjack. */
        double fixedPayoff;

        Hand(int splitRank) {
            this.splitRank = splitRank;
        }

        int total() {
            return ConcreteRound.total(cards);
        }

        boolean soft() {
            return ConcreteRound.soft(cards);
        }
    }

    private ConcreteRound(RoundRules rules, RoundPolicy policy, int[] ranks, int from) {
        this.rules = rules;
        this.policy = policy;
        this.ranks = ranks;
        this.next = from + 4;
        this.up = ranks[from + 1];
    }

    /**
     * Plays the round that starts at ranks[from]. Ranks are the contract's, 1 to 10. Throws
     * IllegalStateException if the round needs a card past the end, as the contract does
     * when a draw finds the shoe empty.
     */
    static Result play(int[] ranks, int from, RoundRules rules, RoundPolicy policy) {
        if (from < 0 || from + 4 > ranks.length) {
            throw new IllegalStateException("not enough cards to deal a round");
        }
        for (int i = from; i < ranks.length; i++) {
            if (ranks[i] < 1 || ranks[i] > 10) {
                throw new IllegalArgumentException("rank " + ranks[i] + " at " + i);
            }
        }
        ConcreteRound round = new ConcreteRound(rules, policy, ranks, from);
        return round.play(ranks[from], ranks[from + 2], ranks[from + 3]);
    }

    private Result play(int p1, int p2, int hole) {
        boolean playerNatural = (p1 == 1 && p2 == 10) || (p1 == 10 && p2 == 1);
        boolean dealerNatural = (up == 1 && hole == 10) || (up == 10 && hole == 1);
        if (dealerNatural) {
            return new Result(playerNatural ? 0 : -1, next, true, playerNatural, 1);
        }
        if (playerNatural) {
            return new Result(rules.blackjackPayout, next, false, true, 1);
        }
        Hand dealt = new Hand(0);
        dealt.cards.add(p1);
        dealt.cards.add(p2);
        playHand(dealt);

        double payoff = 0;
        boolean dealerNeeded = false;
        for (Hand h : finished) {
            dealerNeeded |= h.standing;
        }
        int dealerTotal = 0;
        if (dealerNeeded) {
            List<Integer> dealer = new ArrayList<>();
            dealer.add(up);
            dealer.add(hole);
            while (total(dealer) < 17 || (total(dealer) == 17 && soft(dealer) && rules.hitsSoft17)) {
                dealer.add(draw());
            }
            dealerTotal = total(dealer);
        }
        for (Hand h : finished) {
            if (!h.standing) {
                payoff += h.fixedPayoff;
            } else if (dealerTotal > 21 || h.total() > dealerTotal) {
                payoff += h.stake;
            } else if (h.total() < dealerTotal) {
                payoff -= h.stake;
            }
        }
        return new Result(payoff, next, false, false, finished.size());
    }

    private int draw() {
        if (next >= ranks.length) {
            throw new IllegalStateException("the round needs a card and none are left");
        }
        return ranks[next++];
    }

    /** Plays one hand that holds two cards to its end, and any hands it splits into. */
    private void playHand(Hand h) {
        boolean fromSplit = h.splitRank != 0;
        if (fromSplit && rules.blackjackOnSplitPairs && h.total() == 21) {
            h.fixedPayoff = rules.blackjackPayout;
            finished.add(h);
            return;
        }
        boolean mayHit = !(h.splitRank == 1 && !rules.canHitSplitAces);
        int pair = h.cards.get(0).equals(h.cards.get(1)) ? h.cards.get(0) : 0;
        boolean canHit = mayHit && h.total() < 21;
        boolean canDouble = !fromSplit || rules.doubleAfterSplit[h.splitRank];
        boolean canSplit = pair != 0 && handsInRound + 1 <= rules.maxHandsFor(pair);
        boolean canSurrender = rules.surrender && (!fromSplit || rules.surrenderAfterSplit);
        PlayerMove move = ask(h, true, canHit, canDouble, canSplit, canSurrender);
        switch (move) {
            case Stand:
                h.standing = true;
                finished.add(h);
                return;
            case Surrender:
                h.fixedPayoff = -0.5;
                finished.add(h);
                return;
            case Double:
                h.stake = 2;
                h.cards.add(draw());
                end(h);
                return;
            case Split: {
                handsInRound++;
                Hand a = new Hand(pair);
                Hand b = new Hand(pair);
                a.cards.add(pair);
                b.cards.add(pair);
                a.cards.add(draw());
                b.cards.add(draw());
                playHand(a);
                playHand(b);
                return;
            }
            case Hit:
                h.cards.add(draw());
                while (h.total() < 21) {
                    PlayerMove again = ask(h, false, true, false, false, false);
                    if (again == PlayerMove.Stand) {
                        break;
                    }
                    h.cards.add(draw());
                }
                if (h.total() == 21) {
                    // The contract asks the policy on 21 too, where only Stand is legal.
                    ask(h, false, false, false, false, false);
                }
                end(h);
                return;
            default:
                throw new IllegalStateException("unknown move " + move);
        }
    }

    /** A hand that has drawn its last card: bust, or standing on its total. */
    private void end(Hand h) {
        if (h.total() > 21) {
            h.fixedPayoff = -h.stake;
        } else {
            h.standing = true;
        }
        finished.add(h);
    }

    private PlayerMove ask(Hand h, boolean first, boolean canHit, boolean canDouble,
                           boolean canSplit, boolean canSurrender) {
        int pair = h.cards.size() == 2 && h.cards.get(0).equals(h.cards.get(1)) ? h.cards.get(0) : 0;
        RoundPolicy.Decision d = new RoundPolicy.Decision(h.total(), h.soft(), h.cards.size(), pair, up,
                h.splitRank != 0, first, canHit, canDouble, canSplit, canSurrender);
        PlayerMove m = policy.choose(d);
        if (m == null || !d.isLegal(m)) {
            throw new IllegalStateException("the policy chose " + m + " at " + d + ", which is not legal");
        }
        return m;
    }

    static int total(List<Integer> cards) {
        int hard = 0;
        boolean ace = false;
        for (int c : cards) {
            hard += c;
            ace |= c == 1;
        }
        return ace && hard + 10 <= 21 ? hard + 10 : hard;
    }

    static boolean soft(List<Integer> cards) {
        int hard = 0;
        boolean ace = false;
        for (int c : cards) {
            hard += c;
            ace |= c == 1;
        }
        return ace && hard + 10 <= 21;
    }
}
