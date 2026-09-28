import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BruteForceRound on shoes small enough to value by hand.
 *
 * BruteForceRound is the check on ExactRound, so it has to be right on its own terms,
 * against ROUND_CONTRACT.md and nothing else. Each test here uses a shoe of a few cards
 * whose every branch can be written out, and the comment above each assertion is that
 * working. Most shoes hold one rank only, so every card is forced and the value is a
 * single line of arithmetic; the others hold two ranks and the branches are listed.
 *
 * Unless a test says otherwise the rules are: dealer stands on soft 17, blackjack pays
 * 3:2, split aces make at most 2 hands and other pairs at most 4, split aces take one
 * card, no double after split, no blackjack on split pairs, no surrender.
 */
public class BruteForceRoundTest {

    private static final double EPS = 1e-12;

    // ------------------------------------------------------------------ helpers

    /** A rules builder, so each test names only the rules it changes. */
    private static final class Rules {
        boolean hitsSoft17;
        double payout = 1.5;
        int maxHandsAces = 2;
        int maxHandsNotAces = 4;
        boolean canHitSplitAces;
        final boolean[] das = new boolean[11];
        boolean blackjackOnSplitPairs;
        boolean surrender;
        boolean surrenderAfterSplit;

        Rules h17() { hitsSoft17 = true; return this; }
        Rules payout(double p) { payout = p; return this; }
        Rules maxHandsAces(int n) { maxHandsAces = n; return this; }
        Rules maxHandsNotAces(int n) { maxHandsNotAces = n; return this; }
        Rules canHitSplitAces() { canHitSplitAces = true; return this; }
        Rules das(int rank) { das[rank] = true; return this; }
        Rules blackjackOnSplitPairs() { blackjackOnSplitPairs = true; return this; }
        Rules surrender() { surrender = true; return this; }
        Rules surrenderAfterSplit() { surrenderAfterSplit = true; return this; }

        BruteForceRound valuer() {
            return new BruteForceRound(new RoundRules(hitsSoft17, payout, maxHandsAces,
                    maxHandsNotAces, canHitSplitAces, das, blackjackOnSplitPairs, surrender,
                    surrenderAfterSplit));
        }
    }

    private static Rules rules() {
        return new Rules();
    }

    /** shoe(10, 3, 5, 1) is three tens and a five. */
    private static int[] shoe(int... rankThenCount) {
        int[] s = new int[11];
        for (int i = 0; i < rankThenCount.length; i += 2) {
            s[rankThenCount[i]] += rankThenCount[i + 1];
        }
        return s;
    }

    private static final RoundPolicy STAND = d -> PlayerMove.Stand;

    private static final RoundPolicy SPLIT_WHEN_LEGAL =
            d -> d.canSplit ? PlayerMove.Split : PlayerMove.Stand;

    private static final RoundPolicy DOUBLE_WHEN_LEGAL =
            d -> d.canDouble ? PlayerMove.Double : PlayerMove.Stand;

    private static final RoundPolicy SURRENDER_WHEN_LEGAL =
            d -> d.canSurrender ? PlayerMove.Surrender : PlayerMove.Stand;

    /** For rounds where the contract gives the policy no decision at all. */
    private static final RoundPolicy NEVER_ASKED = d -> {
        throw new AssertionError("no decision was expected, but the policy was asked " + d);
    };

    private static RoundPolicy hitBelow(int total) {
        return d -> d.canHit && d.total < total ? PlayerMove.Hit : PlayerMove.Stand;
    }

    /** Remembers every decision it is asked, in order, and answers as another policy would. */
    private static final class Recording implements RoundPolicy {
        final List<Decision> asked = new ArrayList<>();
        final RoundPolicy answer;

        Recording(RoundPolicy answer) {
            this.answer = answer;
        }

        @Override
        public PlayerMove choose(Decision d) {
            asked.add(d);
            return answer.choose(d);
        }
    }

    // ------------------------------------------------------------------ forced shoes

    /**
     * A shoe of nothing but tens, up-card 10. The hole card is a ten, so the dealer has 20
     * (not a natural) and stands, and every card the player draws is a ten.
     */
    @Test
    void aShoeOfOnlyTens() {
        BruteForceRound v = rules().valuer();
        int[] tens = shoe(10, 8);

        // 20 against 20 pushes.
        assertEquals(0.0, v.valueOfFirstMove(tens, 10, 10, 10, PlayerMove.Stand, NEVER_ASKED), EPS);
        // 16 against 20 loses.
        assertEquals(-1.0, v.valueOfFirstMove(tens, 9, 7, 10, PlayerMove.Stand, NEVER_ASKED), EPS);
        // 20 takes a ten and busts; no decision follows a bust.
        assertEquals(-1.0, v.valueOfFirstMove(tens, 10, 10, 10, PlayerMove.Hit, NEVER_ASKED), EPS);
        // 11 doubles, takes a ten, 21 beats 20 at stake 2.
        assertEquals(2.0, v.valueOfFirstMove(tens, 6, 5, 10, PlayerMove.Double, NEVER_ASKED), EPS);

        // 11 hits to 21 and the policy decides again: not a first decision, and on 21 only
        // Stand is legal. 21 beats 20.
        Recording after = new Recording(STAND);
        assertEquals(1.0, v.valueOfFirstMove(tens, 6, 5, 10, PlayerMove.Hit, after), EPS);
        assertEquals(1, after.asked.size());
        RoundPolicy.Decision d = after.asked.get(0);
        assertEquals(21, d.total);
        assertEquals(3, d.numCards);
        assertFalse(d.firstDecision);
        assertFalse(d.canHit || d.canDouble || d.canSplit || d.canSurrender);

        // 10,10 splits; each ten draws a ten and stands on 20, and both push.
        assertEquals(0.0, v.valueOfFirstMove(tens, 10, 10, 10, PlayerMove.Split, STAND), EPS);
        // Splitting whenever legal makes four hands of 20 (7 of the 8 tens are used), all push.
        assertEquals(0.0, v.valueOfFirstMove(tens, 10, 10, 10, PlayerMove.Split, SPLIT_WHEN_LEGAL), EPS);
    }

    /** A shoe of sevens, up-card 10: the hole is a 7 and the dealer stands on hard 17. */
    @Test
    void aForcedDealer17() {
        BruteForceRound v = rules().valuer();
        int[] sevens = shoe(7, 6);

        assertEquals(1.0, v.valueOfFirstMove(sevens, 10, 8, 10, PlayerMove.Stand, NEVER_ASKED), EPS);
        assertEquals(0.0, v.valueOfFirstMove(sevens, 10, 7, 10, PlayerMove.Stand, NEVER_ASKED), EPS);
        assertEquals(-1.0, v.valueOfFirstMove(sevens, 10, 6, 10, PlayerMove.Stand, NEVER_ASKED), EPS);
        // 16 takes a 7: 23, bust.
        assertEquals(-1.0, v.valueOfFirstMove(sevens, 10, 6, 10, PlayerMove.Hit, NEVER_ASKED), EPS);
        // 2,3 is 5; hitting below 17 goes 12, 19 and stands. 19 beats 17.
        assertEquals(1.0, v.valueOfFirstMove(sevens, 2, 3, 10, PlayerMove.Hit, hitBelow(17)), EPS);
    }

    /**
     * H17 against S17. Up-card 6 and a shoe of aces: the hole is an ace, soft 17. Under S17
     * the dealer stands on 17; under H17 the dealer draws another ace and stands on soft 18.
     * A hard 17 (up 10, hole 7) stands under both.
     */
    @Test
    void theDealerHitsSoft17OnlyUnderH17() {
        BruteForceRound s17 = rules().valuer();
        BruteForceRound h17 = rules().h17().valuer();
        int[] aces = shoe(1, 3);

        // 18 against 17 wins; against 18 pushes.
        assertEquals(1.0, s17.valueOfFirstMove(aces, 10, 8, 6, PlayerMove.Stand, NEVER_ASKED), EPS);
        assertEquals(0.0, h17.valueOfFirstMove(aces, 10, 8, 6, PlayerMove.Stand, NEVER_ASKED), EPS);
        // 17 against 17 pushes; against 18 loses.
        assertEquals(0.0, s17.valueOfFirstMove(aces, 10, 7, 6, PlayerMove.Stand, NEVER_ASKED), EPS);
        assertEquals(-1.0, h17.valueOfFirstMove(aces, 10, 7, 6, PlayerMove.Stand, NEVER_ASKED), EPS);

        // Hard 17: 18 wins under both.
        int[] sevens = shoe(7, 3);
        assertEquals(1.0, s17.valueOfFirstMove(sevens, 10, 8, 10, PlayerMove.Stand, NEVER_ASKED), EPS);
        assertEquals(1.0, h17.valueOfFirstMove(sevens, 10, 8, 10, PlayerMove.Stand, NEVER_ASKED), EPS);
    }

    // ------------------------------------------------------------------ the peek

    /**
     * With an ace up, rounds where the hole card is a ten are excluded. Shoe: two tens and
     * two sevens. The hole is a ten with probability 2/4 (a natural, excluded) or a seven
     * with probability 2/4, so given no natural the hole is a seven: the dealer has soft
     * 18 and stands, and the player draws from what is left, two tens and one seven.
     *
     * 5,6 doubles: a ten (2/3) makes 21 and wins 2; a seven (1/3) makes 18 and pushes.
     * 2/3 x 2 = 4/3. Drawing from the whole shoe, as if the hole card were still in it,
     * would give 2/4 x 2 = 1 instead.
     *
     * With a ten up the same holds for aces. Shoe: two aces and two eights. The hole is an
     * eight, the dealer has 18, and 6,4 doubles into two aces and one eight: an ace (2/3)
     * makes soft 21 and wins 2, an eight (1/3) makes 18 and pushes. Again 4/3.
     */
    @Test
    void thePeekRemovesNaturalsBeforeThePlayerDraws() {
        BruteForceRound v = rules().valuer();

        assertEquals(4.0 / 3.0,
                v.valueOfFirstMove(shoe(10, 2, 7, 2), 5, 6, 1, PlayerMove.Double, NEVER_ASKED), EPS);
        // 11 standing against 18 loses whatever the shoe holds.
        assertEquals(-1.0,
                v.valueOfFirstMove(shoe(10, 2, 7, 2), 5, 6, 1, PlayerMove.Stand, NEVER_ASKED), EPS);
        assertEquals(4.0 / 3.0,
                v.valueOfFirstMove(shoe(1, 2, 8, 2), 6, 4, 10, PlayerMove.Double, NEVER_ASKED), EPS);
    }

    /** If every card left would give the dealer a natural, there is no round to value. */
    @Test
    void aShoeWhereEveryHoleIsANaturalIsRefused() {
        BruteForceRound v = rules().valuer();
        assertThrows(IllegalArgumentException.class,
                () -> v.valueOfFirstMove(shoe(10, 3), 10, 9, 1, PlayerMove.Stand, STAND));
        assertThrows(IllegalArgumentException.class,
                () -> v.valueOfFirstMove(shoe(1, 2), 10, 9, 10, PlayerMove.Stand, STAND));
    }

    /**
     * The dealer draws from the shoe as the player's hands left it. Up 6, shoe: one five
     * and two tens. The player has 6,5 and hits once, then stands.
     *
     * Hole five (1/3): the dealer has 11. The player draws a ten, 21; the dealer draws the
     * other ten, 21. Push, 0.
     * Hole ten (2/3): the dealer has 16 and one five and one ten are left. The player draws
     * the ten (1/2), 21, which leaves the five for the dealer: 21, push, 0. Or the player
     * draws the five (1/2), 16, which leaves the ten: the dealer busts, +1. So 1/2.
     *
     * Value: 1/3 x 0 + 2/3 x 1/2 = 1/3. A dealer drawing as though the player's card were
     * still in the shoe would give 1/4 in the second case and 1/6 overall.
     */
    @Test
    void theDealerDrawsFromTheShoeThePlayerLeft() {
        BruteForceRound v = rules().valuer();
        assertEquals(1.0 / 3.0,
                v.valueOfFirstMove(shoe(5, 1, 10, 2), 6, 5, 6, PlayerMove.Hit, STAND), EPS);
    }

    // ------------------------------------------------------------------ the player's natural

    /**
     * Ace and ten is paid blackjackPayout. With an ace up and a shoe of two tens and a
     * five, the hole given no natural is the five, and the value is still the payout.
     */
    @Test
    void thePlayersNaturalIsPaidAtOnce() {
        assertEquals(1.5, rules().valuer()
                .valueOfFirstMove(shoe(10, 2), 1, 10, 9, PlayerMove.Stand, NEVER_ASKED), EPS);
        assertEquals(1.2, rules().payout(1.2).valuer()
                .valueOfFirstMove(shoe(10, 2), 10, 1, 9, PlayerMove.Stand, NEVER_ASKED), EPS);
        assertEquals(1.5, rules().valuer()
                .valueOfFirstMove(shoe(10, 2, 5, 1), 1, 10, 1, PlayerMove.Stand, NEVER_ASKED), EPS);
        // Both hands natural is excluded by the peek, and here nothing else is possible.
        assertThrows(IllegalArgumentException.class, () -> rules().valuer()
                .valueOfFirstMove(shoe(10, 2), 1, 10, 1, PlayerMove.Stand, NEVER_ASKED));
    }

    // ------------------------------------------------------------------ splits and the hand limit

    /**
     * Up 10 and a shoe of eights: the hole is an eight, the dealer has 18. The player
     * splits 8,8 and splits again whenever the limit allows, else stands on 16. Every hand
     * loses 1, so the value is minus the number of hands the limit allows.
     *
     * With a limit of 4: the dealt hand splits into A and B (2 hands), A draws an eight and
     * splits into A1 and A2 (3), A1 draws an eight and splits into A1a and A1b (4). Then
     * A1a, A1b, A2 and B are each asked and may not split, since a fifth hand would pass
     * the limit. Seven eights are drawn: the hole and two per split.
     *
     * In the contract's order the policy is asked A, A1, A1a, A1b, A2, B, and only the
     * first two may split. B is refused although A1a, A1b and A2 have all finished by the
     * time it is asked: the limit counts every hand, finished or not.
     *
     * What this does not check is the order itself. Every hand here is 8,8, so any order
     * of play asks the same six questions; and because the cards are exchangeable and a
     * decision sees only its own hand and the hand count, no order of play changes a
     * round's value either. The order fixes which questions the policy is asked when, and
     * which draw finds an empty shoe, not what the round is worth.
     */
    @Test
    void splitsFollowTheCanonicalOrderAndTheHandLimit() {
        int[] eights = shoe(8, 7);

        Recording four = new Recording(SPLIT_WHEN_LEGAL);
        assertEquals(-4.0, rules().maxHandsNotAces(4).valuer()
                .valueOfFirstMove(eights, 8, 8, 10, PlayerMove.Split, four), EPS);
        List<Boolean> canSplit = new ArrayList<>();
        for (RoundPolicy.Decision d : four.asked) {
            canSplit.add(d.canSplit);
            assertEquals(16, d.total);
            assertEquals(8, d.pairRank);
            assertTrue(d.fromSplit);
            assertTrue(d.firstDecision);
        }
        assertEquals(List.of(true, true, false, false, false, false), canSplit);

        // A limit of 3: A splits once more; A1, A2 and B stand.
        Recording three = new Recording(SPLIT_WHEN_LEGAL);
        assertEquals(-3.0, rules().maxHandsNotAces(3).valuer()
                .valueOfFirstMove(eights, 8, 8, 10, PlayerMove.Split, three), EPS);
        assertEquals(4, three.asked.size());
        // A limit of 2: the dealt hand splits and nothing splits again.
        assertEquals(-2.0, rules().maxHandsNotAces(2).valuer()
                .valueOfFirstMove(eights, 8, 8, 10, PlayerMove.Split, SPLIT_WHEN_LEGAL), EPS);
        // A limit of 1: the pair may not be split at all.
        assertThrows(IllegalArgumentException.class, () -> rules().maxHandsNotAces(1).valuer()
                .valueOfFirstMove(eights, 8, 8, 10, PlayerMove.Split, SPLIT_WHEN_LEGAL));

        // Six eights are one short of the seven the four-hand round draws.
        assertThrows(IllegalStateException.class, () -> rules().maxHandsNotAces(4).valuer()
                .valueOfFirstMove(shoe(8, 6), 8, 8, 10, PlayerMove.Split, SPLIT_WHEN_LEGAL));
    }

    // ------------------------------------------------------------------ split aces

    /**
     * Split aces take one card each and stand. Up 9, shoe: one ace and three tens, at most
     * 2 hands for aces.
     *
     * Hole ace (1/4): the dealer has soft 20. Both aces draw tens: two 21s, +2.
     * Hole ten (3/4): the dealer has 19; one ace and two tens are left.
     *   The first ace draws the ace (1/3): A,A is soft 12 and may not split again (a third
     *   hand passes the limit), so it stands and loses; the second draws a ten and wins. 0.
     *   The first ace draws a ten (2/3) and wins. The second draws the ace (1/2) and loses,
     *   0 in all, or the last ten (1/2) and wins, +2 in all. So 1.
     *   1/3 x 0 + 2/3 x 1 = 2/3.
     * Value: 1/4 x 2 + 3/4 x 2/3 = 1.
     *
     * With blackjack on split pairs at 3:2 each ace and ten is paid 1.5 instead of 1. Each
     * split ace draws a ten with probability 3/4 (1/4 x 1 + 3/4 x 2/3), so the value rises
     * by 2 x 3/4 x 0.5 to 1.75.
     *
     * Every split ace has a first decision, and the policy is asked even where only Stand
     * is legal, as it is on each of these hands: no hit, no double after split, no
     * surrender, and no room to split. So each hand is asked once, on every path, except an
     * ace and ten paid as a blackjack, which ends with no decision.
     */
    @Test
    void splitAcesTakeOneCard() {
        int[] shoe = shoe(1, 1, 10, 3);
        Recording asked = new Recording(STAND);
        assertEquals(1.0, rules().valuer()
                .valueOfFirstMove(shoe, 1, 1, 9, PlayerMove.Split, asked), EPS);
        // Two hands on each of four paths: hole ace, then ten and ten; hole ten, then ace
        // and ten, ten and ace, ten and ten.
        assertEquals(8, asked.asked.size());
        for (RoundPolicy.Decision d : asked.asked) {
            assertTrue(d.fromSplit && d.firstDecision);
            assertFalse(d.canHit || d.canDouble || d.canSplit || d.canSurrender);
        }
        // With the rule on, only the A,A hands are asked: one on each of the two paths
        // where an ace draws the ace.
        Recording blackjacks = new Recording(STAND);
        assertEquals(1.75, rules().blackjackOnSplitPairs().valuer()
                .valueOfFirstMove(shoe, 1, 1, 9, PlayerMove.Split, blackjacks), EPS);
        assertEquals(2, blackjacks.asked.size());
        for (RoundPolicy.Decision d : blackjacks.asked) {
            assertEquals(1, d.pairRank);
        }
        // A limit of 1 for aces: A,A may not be split.
        assertThrows(IllegalArgumentException.class, () -> rules().maxHandsAces(1).valuer()
                .valueOfFirstMove(shoe, 1, 1, 9, PlayerMove.Split, NEVER_ASKED));
    }

    /**
     * A split ace that draws another ace, with room for another hand, may split again, and
     * like any split hand may double if aces may double after a split and surrender if
     * surrender after a split is on. Up 9 and a shoe of aces: the hole is an ace, the dealer
     * has soft 20, and every hand of the player's ends on soft 12 or soft 13, so every hand
     * loses its stake.
     *
     * Limit 3, splitting when legal: A and B draw aces. A is asked (3 hands would be
     * allowed) and splits into A1 and A2, which draw aces. Now 3 hands: A1, A2 and B hold
     * A,A but a fourth hand passes the limit, so each is asked without Split and stands.
     * -3, with four decisions asked. Five aces are drawn. Surrender is on for split hands
     * here, so every one of the four is offered it.
     *
     * The same rules, surrendering when legal: A surrenders instead of splitting, and so
     * does B. -1.
     *
     * Limit 2: neither ace may split again, and each is asked with only Stand legal. -2.
     *
     * Limit 3, aces may double after a split, doubling when legal: A doubles on A,A and
     * draws an ace, soft 13 at stake 2, -2; B is asked too (the round holds 2 hands) and
     * does the same, -2. -4 in all. Without double after split the same policy stands: -2.
     *
     * A split ace that may not be hit is asked whether or not it may split again, and is
     * offered surrender by the same rule as any split hand.
     */
    @Test
    void aSplitAceThatDrawsAnAceMayBeSplitAgain() {
        int[] aces = shoe(1, 5);

        Recording asked = new Recording(SPLIT_WHEN_LEGAL);
        assertEquals(-3.0, rules().maxHandsAces(3).surrender().surrenderAfterSplit().valuer()
                .valueOfFirstMove(aces, 1, 1, 9, PlayerMove.Split, asked), EPS);
        assertEquals(4, asked.asked.size());
        List<Boolean> canSplit = new ArrayList<>();
        for (RoundPolicy.Decision d : asked.asked) {
            canSplit.add(d.canSplit);
            assertEquals(12, d.total);
            assertTrue(d.soft);
            assertEquals(1, d.pairRank);
            assertTrue(d.fromSplit && d.firstDecision && d.canSurrender);
            assertFalse(d.canHit || d.canDouble);
        }
        assertEquals(List.of(true, false, false, false), canSplit);

        assertEquals(-1.0, rules().maxHandsAces(3).surrender().surrenderAfterSplit().valuer()
                .valueOfFirstMove(aces, 1, 1, 9, PlayerMove.Split, SURRENDER_WHEN_LEGAL), EPS);

        Recording onlyStand = new Recording(STAND);
        assertEquals(-2.0, rules().maxHandsAces(2).valuer()
                .valueOfFirstMove(aces, 1, 1, 9, PlayerMove.Split, onlyStand), EPS);
        assertEquals(2, onlyStand.asked.size());
        for (RoundPolicy.Decision d : onlyStand.asked) {
            assertFalse(d.canHit || d.canDouble || d.canSplit || d.canSurrender);
        }

        assertEquals(-4.0, rules().maxHandsAces(3).das(1).valuer()
                .valueOfFirstMove(aces, 1, 1, 9, PlayerMove.Split, DOUBLE_WHEN_LEGAL), EPS);
        assertEquals(-2.0, rules().maxHandsAces(3).valuer()
                .valueOfFirstMove(aces, 1, 1, 9, PlayerMove.Split, DOUBLE_WHEN_LEGAL), EPS);
    }

    /**
     * A split ace that draws a card other than an ace has a first decision like any split
     * hand, and may surrender or double where the rules let a split hand. Up 10, shoe:
     * three tens and a six. Each ace draws a ten, 21 (an ordinary one, blackjack on split
     * pairs being off), or the six, soft 17. The policy stands on 21, and on soft 17 it
     * surrenders, or doubles, whenever that is legal.
     *
     * Hole six (1/4): the dealer has 16 and three tens are left. Both aces draw tens, stand
     * on 21, and the dealer draws the last ten and busts. +2 whatever the rules.
     * Hole ten (3/4): the dealer has 20; two tens and the six are left.
     *
     * Surrender and surrender after a split on:
     *   The first ace draws the six (1/3): it surrenders, -0.5; the second draws a ten and
     *   beats 20, +1. +0.5.
     *   The first draws a ten (2/3), +1. The second draws the six (1/2) and surrenders,
     *   +0.5 in all, or the last ten (1/2), +2 in all. So 1.25.
     *   1/3 x 0.5 + 2/3 x 1.25 = 1.
     * Value: 1/4 x 2 + 3/4 x 1 = 1.25.
     * Without surrender after a split the six is stood on and loses to 20: the branches are
     * 0, then 0 or +2, so 1/3 x 0 + 2/3 x 1 = 2/3, and the value is 1/4 x 2 + 3/4 x 2/3 = 1.
     *
     * Aces doubling after a split: the hand holding the six doubles, and the one card left
     * for it is a ten: A,6,10 is hard 17 at stake 2 against 20, -2.
     *   The first ace draws the six (1/3): -2, and the second's 21 wins, +1. -1.
     *   The first draws a ten (2/3), +1. The second draws the six (1/2) and doubles into
     *   the last ten, -2, -1 in all; or it draws the last ten (1/2), +2 in all. So 0.5.
     *   1/3 x -1 + 2/3 x 0.5 = 0.
     * Value: 1/4 x 2 + 3/4 x 0 = 0.5. Without double after splitting aces the six stands,
     * and the value is 1, as above.
     */
    @Test
    void aSplitAceThatDrawsANonAceMaySurrenderOrDouble() {
        int[] shoe = shoe(10, 3, 6, 1);
        RoundPolicy surrenderSoft17 = d -> d.canSurrender && d.total == 17 ? PlayerMove.Surrender : PlayerMove.Stand;
        RoundPolicy doubleSoft17 = d -> d.canDouble && d.total == 17 ? PlayerMove.Double : PlayerMove.Stand;

        Recording asked = new Recording(surrenderSoft17);
        assertEquals(1.25, rules().surrender().surrenderAfterSplit().valuer()
                .valueOfFirstMove(shoe, 1, 1, 10, PlayerMove.Split, asked), EPS);
        boolean sawSoft17 = false;
        for (RoundPolicy.Decision d : asked.asked) {
            assertTrue(d.fromSplit && d.firstDecision && d.soft && d.canSurrender);
            assertFalse(d.canHit || d.canDouble || d.canSplit);
            assertEquals(0, d.pairRank);
            sawSoft17 |= d.total == 17;
        }
        assertTrue(sawSoft17);
        assertEquals(1.0, rules().surrender().valuer()
                .valueOfFirstMove(shoe, 1, 1, 10, PlayerMove.Split, surrenderSoft17), EPS);

        assertEquals(0.5, rules().das(1).valuer()
                .valueOfFirstMove(shoe, 1, 1, 10, PlayerMove.Split, doubleSoft17), EPS);
        assertEquals(1.0, rules().valuer()
                .valueOfFirstMove(shoe, 1, 1, 10, PlayerMove.Split, doubleSoft17), EPS);
    }

    /**
     * Where split aces may be hit they are ordinary split hands. Up 9 and a shoe of six
     * fives: the hole is a five, the dealer has 14. Each ace draws a five, soft 16.
     *
     * canHitSplitAces, hitting below 17: each hand hits to A,5,5, soft 21, and stands. The
     * dealer then draws the last five, 19. Both win: +2 (all six fives drawn).
     * Otherwise: each is asked with only Stand legal and stands on soft 16; the dealer
     * draws a five, 19. Both lose: -2.
     */
    @Test
    void splitAcesThatMayBeHitAreOrdinarySplitHands() {
        int[] fives = shoe(5, 6);
        assertEquals(2.0, rules().canHitSplitAces().valuer()
                .valueOfFirstMove(fives, 1, 1, 9, PlayerMove.Split, hitBelow(17)), EPS);
        Recording asked = new Recording(STAND);
        assertEquals(-2.0, rules().valuer()
                .valueOfFirstMove(fives, 1, 1, 9, PlayerMove.Split, asked), EPS);
        assertEquals(2, asked.asked.size());
        for (RoundPolicy.Decision d : asked.asked) {
            assertFalse(d.canHit || d.canDouble || d.canSplit || d.canSurrender);
        }
    }

    // ------------------------------------------------------------------ blackjack on split pairs

    /**
     * A split ace and ten against a dealer who makes 21 with three cards. Up 6, shoe:
     * three tens and one five. The player splits aces; each takes one card and, unless it
     * is paid as a blackjack, is asked and stands, Stand being its only legal move.
     *
     * Hole five (1/4): the dealer has 11. Both aces draw tens, and the dealer draws the
     * last ten: 6,5,10, a three-card 21.
     * Hole ten (3/4): the dealer has 16; two tens and a five are left.
     *   First ace ten (2/3), second ace ten (1/2): the dealer draws the five, 6,10,5 = 21.
     *   First ace ten (2/3), second ace five (1/2): A,5 soft 16; the dealer draws a ten, busts.
     *   First ace five (1/3), second ace ten: the dealer draws a ten, busts.
     *
     * Ordinary 21s (the rule off): against the dealer's 21 both push; against a bust both
     * win. Hole five: 0. Hole ten: 2/3 x (1/2 x 0 + 1/2 x 2) + 1/3 x 2 = 4/3.
     * Value 3/4 x 4/3 = 1.
     *
     * Blackjacks paid b (the rule on), whatever the dealer makes. Hole five: 2b. Hole ten:
     * 2/3 x (1/2 x 2b + 1/2 x (b + 1)) + 1/3 x (b + 1) = (4b + 2)/3.
     * Value 1/4 x 2b + 3/4 x (4b + 2)/3 = 1.5b + 0.5: 2.75 at 3:2, 2.3 at 6:5.
     */
    @Test
    void blackjackOnSplitPairsBeatsADealerThreeCard21() {
        int[] shoe = shoe(10, 3, 5, 1);
        // These used a policy that must never be asked, from when a split ace took its card
        // and stood without a decision; now each is asked, with only Stand legal.
        assertEquals(1.0, rules().valuer()
                .valueOfFirstMove(shoe, 1, 1, 6, PlayerMove.Split, STAND), EPS);
        assertEquals(2.75, rules().blackjackOnSplitPairs().valuer()
                .valueOfFirstMove(shoe, 1, 1, 6, PlayerMove.Split, STAND), EPS);
        assertEquals(2.3, rules().blackjackOnSplitPairs().payout(1.2).valuer()
                .valueOfFirstMove(shoe, 1, 1, 6, PlayerMove.Split, STAND), EPS);
    }

    /**
     * The same from split tens that draw aces. Up 9 and a shoe of aces: the dealer has soft
     * 20, and each ten draws an ace.
     *
     * Rule on: two blackjacks, 3.0, with no decision asked.
     * Rule off: each 10,A is an ordinary split hand on 21. It is asked, may not hit, and
     * stands: two 21s beat 20, +2. With double after split on tens it may double, and
     * doubling draws another ace: 10,A,A is hard 12 at stake 2 against 20, -4 for the two.
     */
    @Test
    void aSplitTenAndAceIsAnOrdinary21WithoutTheRule() {
        int[] aces = shoe(1, 5);
        assertEquals(3.0, rules().blackjackOnSplitPairs().valuer()
                .valueOfFirstMove(aces, 10, 10, 9, PlayerMove.Split, NEVER_ASKED), EPS);

        Recording asked = new Recording(STAND);
        assertEquals(2.0, rules().valuer()
                .valueOfFirstMove(aces, 10, 10, 9, PlayerMove.Split, asked), EPS);
        assertEquals(2, asked.asked.size());
        RoundPolicy.Decision d = asked.asked.get(0);
        assertEquals(21, d.total);
        assertTrue(d.soft && d.fromSplit && d.firstDecision);
        assertEquals(0, d.pairRank);
        assertFalse(d.canHit || d.canDouble || d.canSplit || d.canSurrender);

        assertEquals(-4.0, rules().das(10).valuer()
                .valueOfFirstMove(aces, 10, 10, 9, PlayerMove.Split, DOUBLE_WHEN_LEGAL), EPS);
    }

    // ------------------------------------------------------------------ surrender

    /**
     * Up 10 and a shoe of tens: the dealer has 20. 8,8 splits and each hand draws a ten,
     * 18. Surrendering when legal: with surrender after split each hand loses 0.5, -1;
     * without it, or without surrender at all, each stands on 18 and loses, -2. The dealt
     * hand's own surrender is -0.5.
     */
    @Test
    void surrenderAfterSplitOnlyWhereTheRulesAllowIt() {
        int[] tens = shoe(10, 6);
        assertEquals(-1.0, rules().surrender().surrenderAfterSplit().valuer()
                .valueOfFirstMove(tens, 8, 8, 10, PlayerMove.Split, SURRENDER_WHEN_LEGAL), EPS);
        assertEquals(-2.0, rules().surrender().valuer()
                .valueOfFirstMove(tens, 8, 8, 10, PlayerMove.Split, SURRENDER_WHEN_LEGAL), EPS);
        assertEquals(-2.0, rules().surrenderAfterSplit().valuer()
                .valueOfFirstMove(tens, 8, 8, 10, PlayerMove.Split, SURRENDER_WHEN_LEGAL), EPS);
        assertEquals(-0.5, rules().surrender().valuer()
                .valueOfFirstMove(tens, 10, 6, 10, PlayerMove.Surrender, NEVER_ASKED), EPS);
    }

    /**
     * Surrender and double are first decisions only. Up 10, shoe of tens: 2,3 hits to 15
     * and is asked again, with only Hit and Stand legal. Standing loses to 20.
     */
    @Test
    void afterAHitOnlyHitAndStandAreLegal() {
        Recording asked = new Recording(STAND);
        assertEquals(-1.0, rules().surrender().valuer()
                .valueOfFirstMove(shoe(10, 6), 2, 3, 10, PlayerMove.Hit, asked), EPS);
        assertEquals(1, asked.asked.size());
        RoundPolicy.Decision d = asked.asked.get(0);
        assertEquals(15, d.total);
        assertFalse(d.firstDecision || d.fromSplit);
        assertTrue(d.canHit);
        assertFalse(d.canDouble || d.canSplit || d.canSurrender);
    }

    // ------------------------------------------------------------------ errors

    /**
     * A first move that is not legal on the dealt hand is refused before any card is dealt,
     * as the contract's order of checks says: before the hole card, so before the shoe can
     * be found empty or full of naturals.
     */
    @Test
    void anIllegalFirstMoveIsRefused() {
        BruteForceRound v = rules().valuer();
        int[] tens = shoe(10, 6);
        // Not a pair.
        assertThrows(IllegalArgumentException.class,
                () -> v.valueOfFirstMove(tens, 10, 9, 10, PlayerMove.Split, STAND));
        // Surrender is off.
        assertThrows(IllegalArgumentException.class,
                () -> v.valueOfFirstMove(tens, 10, 6, 10, PlayerMove.Surrender, STAND));
        // A natural only stands.
        for (PlayerMove m : new PlayerMove[]{PlayerMove.Hit, PlayerMove.Double, PlayerMove.Surrender}) {
            assertThrows(IllegalArgumentException.class, () -> rules().surrender().valuer()
                    .valueOfFirstMove(tens, 1, 10, 9, m, STAND));
        }
        assertThrows(IllegalArgumentException.class,
                () -> v.valueOfFirstMove(tens, 10, 6, 10, null, STAND));
        // Even on an empty shoe, or one of nothing but natural hole cards, the move is
        // checked first; a natural's too.
        assertThrows(IllegalArgumentException.class,
                () -> v.valueOfFirstMove(shoe(), 10, 9, 10, PlayerMove.Split, STAND));
        assertThrows(IllegalArgumentException.class,
                () -> v.valueOfFirstMove(shoe(), 1, 10, 9, PlayerMove.Hit, STAND));
        assertThrows(IllegalArgumentException.class,
                () -> v.valueOfFirstMove(shoe(1, 3), 10, 9, 10, PlayerMove.Surrender, STAND));
        // A legal move on the empty shoe gets as far as the hole card.
        assertThrows(IllegalStateException.class,
                () -> v.valueOfFirstMove(shoe(), 10, 9, 10, PlayerMove.Stand, STAND));
        // The dealt hand may always double, even on 20: it takes a ten and loses 2.
        assertEquals(-2.0, v.valueOfFirstMove(tens, 10, 10, 10, PlayerMove.Double, STAND), EPS);
    }

    /** A policy that answers with an illegal move is an error in the policy. */
    @Test
    void anIllegalPolicyMoveIsAnError() {
        BruteForceRound v = rules().valuer();
        // After a hit, Double is not legal.
        assertThrows(IllegalStateException.class, () -> v.valueOfFirstMove(shoe(2, 5), 2, 3, 10,
                PlayerMove.Hit, d -> PlayerMove.Double));
        // A split 8 that draws a ten is not a pair.
        assertThrows(IllegalStateException.class, () -> v.valueOfFirstMove(shoe(10, 5), 8, 8, 10,
                PlayerMove.Split, d -> PlayerMove.Split));
        assertThrows(IllegalStateException.class, () -> v.valueOfFirstMove(shoe(10, 5), 2, 3, 10,
                PlayerMove.Hit, d -> null));
    }

    /**
     * Every draw that the shoe cannot supply is an error, the hole card included, and the
     * player's natural still has its hole card dealt. The dealer does not draw when no hand
     * needs its total, so a round whose every hand busted needs no dealer card.
     */
    @Test
    void aShoeThatRunsOut() {
        BruteForceRound v = rules().valuer();
        // No hole card.
        assertThrows(IllegalStateException.class,
                () -> v.valueOfFirstMove(shoe(), 10, 6, 10, PlayerMove.Stand, STAND));
        assertThrows(IllegalStateException.class,
                () -> v.valueOfFirstMove(shoe(), 1, 10, 9, PlayerMove.Stand, STAND));
        // The hole takes the only card, and the player hits.
        assertThrows(IllegalStateException.class,
                () -> v.valueOfFirstMove(shoe(10, 1), 10, 6, 10, PlayerMove.Hit, STAND));
        // The hole is a five, the dealer has 11 and must draw from an empty shoe.
        assertThrows(IllegalStateException.class,
                () -> v.valueOfFirstMove(shoe(5, 1), 10, 8, 6, PlayerMove.Stand, STAND));
        // Hole ten, dealer 16; the player takes the other ten and busts. The dealer would
        // need a card that is not there, but nothing is left to settle against the dealer.
        assertEquals(-1.0, v.valueOfFirstMove(shoe(10, 2), 10, 6, 6, PlayerMove.Hit, STAND), EPS);
    }

    @Test
    void theShoeIsNotModified() {
        int[] shoe = shoe(1, 2, 5, 3, 10, 8);
        int[] before = shoe.clone();
        rules().valuer().valueOfFirstMove(shoe, 8, 8, 6, PlayerMove.Split, hitBelow(17));
        assertArrayEquals(before, shoe);
    }
}
