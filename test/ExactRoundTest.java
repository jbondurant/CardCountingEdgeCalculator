import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExactRound on rounds small enough to work out by hand.
 *
 * Most of these use a shoe of one or two ranks, so that every card is forced, or forced
 * once the dealer is known not to have a natural, and the value can be read off the
 * cards. Each test says how. The last few use the real eight-deck shoe and hold
 * ExactRound to a literal sum over the dealer's draws written here, and its two ways of
 * valuing a split to each other.
 */
public class ExactRoundTest {

    private static final double EXACT = 1e-12;

    // ------------------------------------------------------------------ helpers

    /** A shoe from rank, count pairs: shoe(10, 20) is twenty tens and nothing else. */
    private static int[] shoe(int... rankCounts) {
        int[] s = new int[11];
        for (int i = 0; i < rankCounts.length; i += 2) {
            s[rankCounts[i]] += rankCounts[i + 1];
        }
        return s;
    }

    private static boolean[] doubleAfterSplitOnAllBut(int... excluded) {
        boolean[] das = new boolean[11];
        for (int r = 1; r <= 10; r++) {
            das[r] = true;
        }
        for (int r : excluded) {
            das[r] = false;
        }
        return das;
    }

    /** Montreal's rules with these split limits: H17, 3:2, DAS on all but aces, no surrender. */
    private static RoundRules montreal(int maxHandsAces, int maxHandsNotAces) {
        return new RoundRules(true, 1.5, maxHandsAces, maxHandsNotAces, false,
                doubleAfterSplitOnAllBut(1), false, false, false);
    }

    private static RoundRules withSoft17(boolean hitsSoft17) {
        return new RoundRules(hitsSoft17, 1.5, 2, 4, false, doubleAfterSplitOnAllBut(1), false,
                false, false);
    }

    /**
     * The round's value, computed both ways a split can be valued: hand by hand where the
     * shoe is big enough for it, and coupled throughout. The two must give the same value or
     * throw the same exception, and neither may touch the shoe.
     */
    private static double value(RoundRules rules, int[] shoe, int p1, int p2, int up, PlayerMove first,
                                RoundPolicy policy) {
        int[] before = shoe.clone();
        Object usual = outcome(() -> new ExactRound(rules).valueOfFirstMove(shoe, p1, p2, up, first, policy));
        Object coupled = outcome(() -> new ExactRound(rules, true).valueOfFirstMove(shoe, p1, p2, up, first, policy));
        assertArrayEquals(before, shoe, "the shoe was modified");
        if (usual instanceof Double && coupled instanceof Double) {
            assertEquals((Double) coupled, (Double) usual, EXACT, "the two computations disagree");
            return (Double) usual;
        }
        assertEquals(coupled.getClass(), usual.getClass(), "the two computations end differently");
        throw (RuntimeException) usual;
    }

    private interface Valuing {
        double value();
    }

    private static Object outcome(Valuing valuing) {
        try {
            return valuing.value();
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static final RoundPolicy STAND = d -> PlayerMove.Stand;

    /** Hits while the total is under the given one, otherwise stands. Never doubles or splits. */
    private static RoundPolicy hitBelow(int total) {
        return d -> d.canHit && d.total < total ? PlayerMove.Hit : PlayerMove.Stand;
    }

    /** Splits whenever it may, otherwise stands. */
    private static final RoundPolicy RESPLIT = d -> d.canSplit ? PlayerMove.Split : PlayerMove.Stand;

    /** A policy for rounds that should never ask: any question fails the test. */
    private static final RoundPolicy NEVER_ASKED = d -> {
        throw new AssertionError("the policy was asked about " + d);
    };

    /** Wraps a policy and keeps every decision it was asked about. */
    private static final class Recording implements RoundPolicy {
        final List<Decision> asked = new ArrayList<>();
        private final RoundPolicy inner;

        Recording(RoundPolicy inner) {
            this.inner = inner;
        }

        @Override
        public PlayerMove choose(Decision d) {
            asked.add(d);
            return inner.choose(d);
        }
    }

    // ------------------------------------------------------------------ forced shoes

    /**
     * A shoe of nothing but tens. The hole card is a ten, so the dealer stands on 17 against
     * a 7, and every card the player draws is a ten: hard 16 loses standing, busts hitting,
     * loses two doubling, and gives up half surrendering.
     */
    @Test
    public void aShoeOfTensForcesEveryCard() {
        int[] tens = shoe(10, 20);
        RoundRules noSurrender = montreal(2, 4);
        assertEquals(-1, value(noSurrender, tens, 10, 6, 7, PlayerMove.Stand, STAND), EXACT);
        assertEquals(-1, value(noSurrender, tens, 10, 6, 7, PlayerMove.Hit, STAND), EXACT);
        assertEquals(-2, value(noSurrender, tens, 10, 6, 7, PlayerMove.Double, STAND), EXACT);

        RoundRules surrender = new RoundRules(true, 1.5, 2, 4, false, doubleAfterSplitOnAllBut(1),
                false, true, false);
        assertEquals(-0.5, value(surrender, tens, 10, 6, 7, PlayerMove.Surrender, STAND), EXACT);
        assertEquals(1, value(noSurrender, tens, 10, 10, 7, PlayerMove.Stand, STAND), EXACT);
    }

    /**
     * Split tens against a 7 from a shoe of tens: every split hand is 20 again and beats
     * the dealer's 17, so the round is worth one unit per hand it ends with. Splitting
     * whenever allowed, that is exactly the limit, because the limit counts every hand in
     * the round, finished or not.
     */
    @Test
    public void theHandLimitCountsEveryHandInTheRound() {
        for (int limit = 2; limit <= 5; limit++) {
            Recording policy = new Recording(RESPLIT);
            double v = value(montreal(2, limit), shoe(10, 20), 10, 10, 7, PlayerMove.Split, policy);
            assertEquals(limit, v, EXACT, "limit " + limit);
            // Every split hand is a pair of tens. At two hands none of them may split; above
            // that, the ones reached while the round has room may, and the rest may not.
            for (RoundPolicy.Decision d : policy.asked) {
                assertTrue(d.fromSplit && d.firstDecision && d.pairRank == 10);
            }
            assertEquals(limit > 2, policy.asked.stream().anyMatch(d -> d.canSplit), "limit " + limit);
            assertTrue(policy.asked.stream().anyMatch(d -> !d.canSplit), "limit " + limit);
        }
        assertThrows(IllegalArgumentException.class,
                () -> value(montreal(2, 1), shoe(10, 20), 10, 10, 7, PlayerMove.Split, RESPLIT),
                "a limit of 1 means the pair may not be split at all");
    }

    /**
     * A shoe of three fives against a ten: the hole card is a five (15), the player's hit on
     * 16 takes the second five for 21, and only then does the dealer draw the last five, for
     * 20. The dealer's draw comes from what the player left.
     */
    @Test
    public void theDealerDrawsAfterThePlayer() {
        int[] fives = shoe(5, 3);
        assertEquals(1, value(montreal(2, 4), fives, 10, 6, 10, PlayerMove.Hit, hitBelow(21)), EXACT);
        assertEquals(-1, value(montreal(2, 4), fives, 10, 6, 10, PlayerMove.Stand, STAND), EXACT);
    }

    /**
     * Sevens only, against a 7: the hole card makes 14 and the dealer draws a third seven
     * for 21. The player hits 14 to 21 as well. A dealer's 21 in three cards is an ordinary
     * 21 and pushes.
     */
    @Test
    public void aDealerTwentyOneInThreeCardsPushesAPlayerTwentyOne() {
        assertEquals(0, value(montreal(2, 4), shoe(7, 3), 10, 4, 7, PlayerMove.Hit, hitBelow(21)), EXACT);
    }

    // ------------------------------------------------------------------ the peek

    /**
     * An ace up with three tens and a nine behind it. Three hole cards in four would be a
     * natural, and those rounds are not counted: given no natural, the hole card is the
     * nine, the dealer has soft 20, and the player's double on 11 draws one of the tens for
     * 21. Counting the naturals would have given something else entirely.
     */
    @Test
    public void anAceUpIsConditionedOnTheHoleCardNotBeingATen() {
        int[] s = shoe(10, 3, 9, 1);
        assertEquals(2, value(montreal(2, 4), s, 6, 5, 1, PlayerMove.Double, STAND), EXACT);
        assertEquals(-1, value(montreal(2, 4), s, 6, 5, 1, PlayerMove.Stand, STAND), EXACT);
    }

    /**
     * A ten up with two aces and a seven behind it. Given no natural the hole card is the
     * seven and the dealer stands on 17, and the player's draws can only be the aces: 15
     * hits to 16 and again to 17, a push; 16 doubled takes one ace to 17, also a push.
     */
    @Test
    public void aTenUpIsConditionedOnTheHoleCardNotBeingAnAce() {
        int[] s = shoe(1, 2, 7, 1);
        assertEquals(0, value(montreal(2, 4), s, 10, 5, 10, PlayerMove.Hit, hitBelow(17)), EXACT);
        assertEquals(1, value(montreal(2, 4), s, 10, 8, 10, PlayerMove.Stand, STAND), EXACT);
        assertEquals(0, value(montreal(2, 4), s, 10, 6, 10, PlayerMove.Double, STAND), EXACT);
    }

    /**
     * An ace up over two tens, a six and a seven. Given no natural the hole card is the six
     * or the seven, one chance in two each: the tens are counted only as the naturals they
     * would have made, not as a third of the chance.
     *
     * Standing on 17: against soft 18 it loses. Against soft 17, a dealer who stands pushes
     * it, so the round is worth (0 - 1) / 2 = -1/2. A dealer who hits soft 17 draws from the
     * two tens and the seven that are left: a ten (2 in 3) makes hard 17, a push; the seven
     * makes hard 14, which draws a ten and busts. That is 1/3 against soft 17 and the round
     * is worth (1/3 - 1) / 2 = -1/3. Standing on 18 wins or pushes the same way under
     * either rule: (1 + 0) / 2.
     */
    @Test
    public void thePeekWeightsTheHoleCardsLeftAndTheSoft17RuleActsOnThem() {
        int[] s = shoe(10, 2, 6, 1, 7, 1);
        assertEquals(-0.5, value(withSoft17(false), s, 10, 7, 1, PlayerMove.Stand, STAND), EXACT);
        assertEquals(-1.0 / 3, value(withSoft17(true), s, 10, 7, 1, PlayerMove.Stand, STAND), EXACT);
        assertEquals(0.5, value(withSoft17(false), s, 10, 8, 1, PlayerMove.Stand, STAND), EXACT);
        assertEquals(0.5, value(withSoft17(true), s, 10, 8, 1, PlayerMove.Stand, STAND), EXACT);
    }

    /**
     * A 6 up over a shoe of aces: the dealer has soft 17. Standing, 18 wins and 17 pushes;
     * hitting soft 17, the dealer takes another ace for soft 18, and 18 pushes and 17 loses.
     */
    @Test
    public void hittingSoft17() {
        int[] aces = shoe(1, 3);
        assertEquals(1, value(withSoft17(false), aces, 10, 8, 6, PlayerMove.Stand, STAND), EXACT);
        assertEquals(0, value(withSoft17(true), aces, 10, 8, 6, PlayerMove.Stand, STAND), EXACT);
        assertEquals(0, value(withSoft17(false), aces, 10, 7, 6, PlayerMove.Stand, STAND), EXACT);
        assertEquals(-1, value(withSoft17(true), aces, 10, 7, 6, PlayerMove.Stand, STAND), EXACT);
    }

    @Test
    public void aShoeWhoseEveryCardIsANaturalHasNothingToConditionOn() {
        assertThrows(IllegalArgumentException.class,
                () -> value(montreal(2, 4), shoe(10, 5), 9, 7, 1, PlayerMove.Stand, STAND));
        assertThrows(IllegalArgumentException.class,
                () -> value(montreal(2, 4), shoe(1, 5), 9, 7, 10, PlayerMove.Stand, STAND));
        assertThrows(IllegalStateException.class,
                () -> value(montreal(2, 4), shoe(), 9, 7, 5, PlayerMove.Stand, STAND),
                "the hole card is drawn from the shoe, and there is none");
    }

    // ------------------------------------------------------------------ splits

    /**
     * Eights split against a 10 from a shoe of eleven threes. Every card is a three: the hole
     * card makes 13 and the dealer draws twice more to 19, both split hands are 11, and the
     * policy doubles 11 where it may and otherwise hits to 20.
     *
     * Doubling after splitting eights, each hand draws one three for 14 at a stake of 2 and
     * loses to 19: -4. Without it, each hand hits three times, 14, 17, 20, and beats 19: +2.
     * That uses every card: the hole card, two second cards, six hits and the dealer's two.
     */
    @Test
    public void splitHandsDoubleByTheirSplitRank() {
        RoundPolicy doubleElevenElseHitTo20 = d -> d.canDouble && d.total == 11
                ? PlayerMove.Double : d.canHit && d.total < 20 ? PlayerMove.Hit : PlayerMove.Stand;
        RoundRules dasOnEights = montreal(2, 2);
        RoundRules noDasOnEights = new RoundRules(true, 1.5, 2, 2, false,
                doubleAfterSplitOnAllBut(1, 8), false, false, false);
        int[] threes = shoe(3, 11);
        assertEquals(-4, value(dasOnEights, threes, 8, 8, 10, PlayerMove.Split, doubleElevenElseHitTo20), EXACT);
        assertEquals(2, value(noDasOnEights, threes, 8, 8, 10, PlayerMove.Split, doubleElevenElseHitTo20), EXACT);
    }

    /**
     * Sixes split against a 10 from a shoe of fives: the hole card makes 15, each split hand
     * gets a five for 11 and hits twice to 21, and the dealer then draws a five for 20. Two
     * hands beat 20: +2. That takes eight fives in all, one of them the dealer's last, so
     * the split hands really do draw from the same shoe as each other and as the dealer. One
     * five fewer and the dealer is left needing a card with none left; two fewer and the
     * second hand is.
     */
    @Test
    public void splitHandsShareOneShoeWithTheDealer() {
        RoundRules rules = montreal(2, 2);
        assertEquals(2, value(rules, shoe(5, 8), 6, 6, 10, PlayerMove.Split, hitBelow(21)), EXACT);
        assertThrows(IllegalStateException.class,
                () -> value(rules, shoe(5, 7), 6, 6, 10, PlayerMove.Split, hitBelow(21)));
        assertThrows(IllegalStateException.class,
                () -> value(rules, shoe(5, 6), 6, 6, 10, PlayerMove.Split, hitBelow(21)));
    }

    /**
     * Aces split against a 7 from a shoe of tens: the hole card makes 17, and each ace takes
     * one ten. Without blackjack on split pairs each is an ordinary 21 and wins 1; with it
     * each is paid the blackjack payout.
     *
     * Every split ace has a first decision, and the policy is asked at every decision, even
     * one where only Stand is legal, as it is here under Montreal's rules: no hitting split
     * aces, no double after splitting them, no surrender. Only the split blackjacks are never
     * asked, since they end at once.
     */
    @Test
    public void splitAcesTakeOneCardAndStand() {
        int[] tens = shoe(10, 5);
        Recording policy = new Recording(STAND);
        assertEquals(2, value(montreal(2, 4), tens, 1, 1, 7, PlayerMove.Split, policy), EXACT);
        assertFalse(policy.asked.isEmpty());
        for (RoundPolicy.Decision d : policy.asked) {
            assertEquals(21, d.total);
            assertTrue(d.soft && d.fromSplit && d.firstDecision);
            assertEquals(0, d.pairRank);
            assertFalse(d.canHit || d.canDouble || d.canSplit || d.canSurrender, "only Stand is legal");
        }
        RoundRules paysBlackjack = new RoundRules(true, 1.5, 2, 4, false, doubleAfterSplitOnAllBut(1),
                true, false, false);
        assertEquals(3, value(paysBlackjack, tens, 1, 1, 7, PlayerMove.Split, NEVER_ASKED), EXACT);
        RoundRules paysSixToFive = new RoundRules(true, 1.2, 2, 4, false, doubleAfterSplitOnAllBut(1),
                true, false, false);
        assertEquals(2.4, value(paysSixToFive, tens, 1, 1, 7, PlayerMove.Split, NEVER_ASKED), EXACT);
    }

    /**
     * Aces split against a 7 from a shoe of aces: the hole card makes soft 18 and every split
     * ace draws another ace. Each is asked, on soft 12: it may split while the limit allows
     * another hand, may double only if aces double after a split, and may not hit. Each hand
     * ends on soft 12 and loses, so the round is worth minus the number of hands.
     *
     * The policy is asked on every split ace, whether or not it may split again, so at two
     * hands both aces are asked with only Stand legal, and at three the aces that come after
     * the resplit are asked with Split no longer legal.
     */
    @Test
    public void aSplitAceThatDrawsAnotherAceIsAskedWhetherOrNotItMayResplit() {
        int[] aces = shoe(1, 10);
        Recording twoHands = new Recording(STAND);
        assertEquals(-2, value(montreal(2, 4), aces, 1, 1, 7, PlayerMove.Split, twoHands), EXACT);
        assertFalse(twoHands.asked.isEmpty());
        for (RoundPolicy.Decision d : twoHands.asked) {
            assertTrue(d.firstDecision && d.fromSplit && d.soft);
            assertEquals(1, d.pairRank);
            assertFalse(d.canHit || d.canDouble || d.canSplit || d.canSurrender, "only Stand is legal");
        }

        for (boolean acesDouble : new boolean[]{false, true}) {
            boolean[] das = acesDouble ? doubleAfterSplitOnAllBut() : doubleAfterSplitOnAllBut(1);
            RoundRules threeHands = new RoundRules(true, 1.5, 3, 4, false, das, false, false, false);
            Recording policy = new Recording(RESPLIT);
            assertEquals(-3, value(threeHands, aces, 1, 1, 7, PlayerMove.Split, policy), EXACT);
            // The first split ace is asked while the round has room for a third hand, and the
            // aces after it once it has none.
            assertTrue(policy.asked.stream().anyMatch(d -> d.canSplit));
            assertTrue(policy.asked.stream().anyMatch(d -> !d.canSplit));
            for (RoundPolicy.Decision d : policy.asked) {
                assertTrue(d.firstDecision && d.fromSplit);
                assertEquals(1, d.pairRank);
                assertFalse(d.canHit, "a split ace may not be hit");
                assertFalse(d.canSurrender, "no surrender under these rules");
                assertEquals(acesDouble, d.canDouble);
            }
        }
    }

    /**
     * Aces split against a 7 from a shoe of fives: the hole card makes 12, each ace draws a
     * five for soft 16, and the dealer, once it draws, takes a five for hard 17. A split ace
     * that drew a card other than an ace still has a first decision, and may surrender or
     * double where the rules let any split hand.
     *
     * Surrendering, with surrender and surrender after a split on: -0.5 each, -1. No hand
     * then needs the dealer, so the dealer does not draw, and three fives are enough: the
     * hole card and the two second cards. Standing on the same three fives, the dealer needs
     * a fourth. Standing on four: soft 16 against 17, -1 each, -2.
     *
     * Doubling, with aces doubling after a split: each A,5 takes a five for soft 21 at a
     * stake of 2 and beats 17, +4. That takes six fives: the hole card, two second cards, two
     * double cards and the dealer's one. Without double after splitting aces, as in
     * Montreal, the same policy stands: -2.
     */
    @Test
    public void aSplitAceThatDrewAFiveMaySurrenderOrDouble() {
        RoundRules surrenderAfterSplit = new RoundRules(true, 1.5, 2, 4, false, doubleAfterSplitOnAllBut(1),
                false, true, true);
        Recording surrenders = new Recording(d -> d.canSurrender ? PlayerMove.Surrender : PlayerMove.Stand);
        assertEquals(-1, value(surrenderAfterSplit, shoe(5, 3), 1, 1, 7, PlayerMove.Split, surrenders), EXACT);
        assertFalse(surrenders.asked.isEmpty());
        for (RoundPolicy.Decision d : surrenders.asked) {
            assertEquals(16, d.total);
            assertTrue(d.soft && d.fromSplit && d.firstDecision);
            assertEquals(0, d.pairRank);
            assertTrue(d.canSurrender);
            assertFalse(d.canHit || d.canDouble || d.canSplit);
        }
        assertThrows(IllegalStateException.class,
                () -> value(surrenderAfterSplit, shoe(5, 3), 1, 1, 7, PlayerMove.Split, STAND),
                "standing, the dealer needs a card the shoe does not have");
        assertEquals(-2, value(surrenderAfterSplit, shoe(5, 4), 1, 1, 7, PlayerMove.Split, STAND), EXACT);

        RoundRules acesDouble = new RoundRules(true, 1.5, 2, 4, false, doubleAfterSplitOnAllBut(),
                false, false, false);
        Recording doubles = new Recording(d -> d.canDouble ? PlayerMove.Double : PlayerMove.Stand);
        assertEquals(4, value(acesDouble, shoe(5, 6), 1, 1, 7, PlayerMove.Split, doubles), EXACT);
        for (RoundPolicy.Decision d : doubles.asked) {
            assertEquals(16, d.total);
            assertTrue(d.canDouble);
            assertFalse(d.canHit || d.canSplit || d.canSurrender);
        }
        assertThrows(IllegalStateException.class,
                () -> value(acesDouble, shoe(5, 5), 1, 1, 7, PlayerMove.Split, doubles),
                "five fives leave none for the dealer");
        assertEquals(-2, value(montreal(2, 4), shoe(5, 6), 1, 1, 7, PlayerMove.Split,
                d -> d.canDouble ? PlayerMove.Double : PlayerMove.Stand), EXACT);
    }

    /**
     * Tens split against a 7 from a shoe of aces: the hole card makes soft 18 and each ten
     * draws an ace. As a blackjack on split pairs, each is paid 1.5 with no decision. As an
     * ordinary 21, each is a first decision on 21 (no hit), stands, and beats 18.
     */
    @Test
    public void aSplitTenAndAceIsABlackjackOnlyWhereTheHouseSaysSo() {
        int[] aces = shoe(1, 5);
        RoundRules paysBlackjack = new RoundRules(true, 1.5, 2, 4, false, doubleAfterSplitOnAllBut(1),
                true, false, false);
        assertEquals(3, value(paysBlackjack, aces, 10, 10, 7, PlayerMove.Split, NEVER_ASKED), EXACT);

        Recording policy = new Recording(STAND);
        assertEquals(2, value(montreal(2, 4), aces, 10, 10, 7, PlayerMove.Split, policy), EXACT);
        for (RoundPolicy.Decision d : policy.asked) {
            assertEquals(21, d.total);
            assertTrue(d.soft && d.firstDecision && d.fromSplit);
            assertFalse(d.canHit);
        }
    }

    /**
     * Eights split against a 10 from a shoe of eights, two hands at most: the hole card
     * makes 18 and each split hand is another pair of eights that may not split. Surrender
     * after a split loses half on each; without it each hand stands on 16 and loses.
     */
    @Test
    public void surrenderAfterSplitIsItsOwnRule() {
        RoundPolicy surrenderIfAllowed = d -> d.canSurrender ? PlayerMove.Surrender : PlayerMove.Stand;
        int[] eights = shoe(8, 3);
        RoundRules afterSplit = new RoundRules(true, 1.5, 2, 2, false, doubleAfterSplitOnAllBut(1),
                false, true, true);
        RoundRules dealtHandOnly = new RoundRules(true, 1.5, 2, 2, false, doubleAfterSplitOnAllBut(1),
                false, true, false);
        assertEquals(-1, value(afterSplit, eights, 8, 8, 10, PlayerMove.Split, surrenderIfAllowed), EXACT);
        assertEquals(-2, value(dealtHandOnly, eights, 8, 8, 10, PlayerMove.Split, surrenderIfAllowed), EXACT);
        assertEquals(-0.5, value(dealtHandOnly, eights, 8, 8, 10, PlayerMove.Surrender, STAND), EXACT);
    }

    // ------------------------------------------------------------------ the rules of the call

    /**
     * A natural is paid at once, but its hole card is still dealt first: an empty shoe has
     * none to deal, and a shoe of nothing but natural hole cards has nothing to condition on.
     */
    @Test
    public void aPlayerNaturalIsPaidAtOnce() {
        int[] s = shoe(10, 3, 9, 1);
        assertEquals(1.5, value(montreal(2, 4), s, 1, 10, 1, PlayerMove.Stand, NEVER_ASKED), EXACT);
        assertEquals(1.5, value(montreal(2, 4), s, 10, 1, 5, PlayerMove.Stand, NEVER_ASKED), EXACT);
        assertThrows(IllegalArgumentException.class,
                () -> value(montreal(2, 4), s, 1, 10, 5, PlayerMove.Hit, STAND));
        assertThrows(IllegalStateException.class,
                () -> value(montreal(2, 4), shoe(), 1, 10, 5, PlayerMove.Stand, NEVER_ASKED));
        assertThrows(IllegalArgumentException.class,
                () -> value(montreal(2, 4), shoe(10, 3), 1, 10, 1, PlayerMove.Stand, NEVER_ASKED));
    }

    /**
     * An illegal first move is refused before anything is dealt, so it is refused even on a
     * shoe with no hole card to deal, where a legal one finds the shoe empty.
     */
    @Test
    public void anIllegalFirstMoveIsRefused() {
        int[] s = shoe(10, 20);
        assertThrows(IllegalArgumentException.class,
                () -> value(montreal(2, 4), s, 10, 9, 7, PlayerMove.Split, STAND), "not a pair");
        assertThrows(IllegalArgumentException.class,
                () -> value(montreal(1, 4), s, 1, 1, 7, PlayerMove.Split, STAND), "aces may not split");
        assertThrows(IllegalArgumentException.class,
                () -> value(montreal(2, 4), s, 10, 6, 7, PlayerMove.Surrender, STAND), "no surrender");

        assertThrows(IllegalArgumentException.class,
                () -> value(montreal(2, 4), shoe(), 10, 9, 7, PlayerMove.Split, STAND), "not a pair, no cards");
        assertThrows(IllegalArgumentException.class,
                () -> value(montreal(2, 4), shoe(), 1, 10, 7, PlayerMove.Double, STAND), "a natural, no cards");
        assertThrows(IllegalArgumentException.class,
                () -> value(montreal(2, 4), shoe(1, 4), 10, 9, 10, PlayerMove.Surrender, STAND),
                "no surrender, and every card a natural hole card");
        assertThrows(IllegalStateException.class,
                () -> value(montreal(2, 4), shoe(), 10, 9, 7, PlayerMove.Stand, STAND), "legal, no cards");
    }

    @Test
    public void anIllegalPolicyMoveThrows() {
        RoundPolicy alwaysDouble = d -> PlayerMove.Double;
        assertThrows(IllegalStateException.class,
                () -> value(montreal(2, 4), shoe(2, 30), 10, 2, 7, PlayerMove.Hit, alwaysDouble),
                "Double is not legal after a hit");
    }

    /**
     * A shoe of one ten: the hole card takes it, so a player's hit finds the shoe empty, and
     * against a 5 a player who stands leaves the dealer on 15 with nothing to draw. With two
     * tens against a 5 the dealer draws the second ten and busts. But if the player hits 16
     * and busts on that ten, no hand is left for the dealer to play against, the dealer's
     * draws are skipped, and the round is simply -1.
     */
    @Test
    public void runningOutOfCards() {
        assertThrows(IllegalStateException.class,
                () -> value(montreal(2, 4), shoe(10, 1), 10, 6, 7, PlayerMove.Hit, STAND));
        assertThrows(IllegalStateException.class,
                () -> value(montreal(2, 4), shoe(10, 1), 10, 8, 5, PlayerMove.Stand, STAND));
        assertEquals(1, value(montreal(2, 4), shoe(10, 2), 10, 8, 5, PlayerMove.Stand, STAND), EXACT);
        assertEquals(-1, value(montreal(2, 4), shoe(10, 2), 10, 6, 5, PlayerMove.Hit, STAND), EXACT);
    }

    @Test
    public void theShoeIsNotModified() {
        int[] s = eightDecksLess(8, 8, 10);
        int[] before = s.clone();
        new ExactRound(montreal(2, 4)).valueOfFirstMove(s, 8, 8, 10, PlayerMove.Split, RESPLIT);
        assertArrayEquals(before, s);
    }

    // ------------------------------------------------------------------ eight decks

    private static int[] eightDecksLess(int... dealt) {
        int[] s = new int[11];
        for (int r = 1; r <= 9; r++) {
            s[r] = 32;
        }
        s[10] = 128;
        for (int c : dealt) {
            s[c]--;
        }
        return s;
    }

    /**
     * The dealer's final totals written out literally: the hole card is drawn first,
     * skipping the natural, then one card at a time from what is left, each sequence
     * weighted by the product of its draws. Index 0..4 is 17..21 and 5 is a bust. Not
     * conditioned: the weights sum to the chance of no natural.
     */
    private static double[] dealerLiterally(int up, int[] shoe, boolean hitsSoft17) {
        double[] out = new double[6];
        int n = Arrays.stream(shoe).sum();
        for (int hole = 1; hole <= 10; hole++) {
            boolean natural = (up == 1 && hole == 10) || (up == 10 && hole == 1);
            if (natural || shoe[hole] == 0) {
                continue;
            }
            int[] rest = shoe.clone();
            rest[hole]--;
            dealerDraws(up + hole, up == 1 || hole == 1, rest, n - 1, shoe[hole] / (double) n,
                    hitsSoft17, out);
        }
        return out;
    }

    private static void dealerDraws(int hard, boolean ace, int[] shoe, int n, double p,
                                    boolean hitsSoft17, double[] out) {
        boolean soft = ace && hard <= 11;
        int total = soft ? hard + 10 : hard;
        if (total > 21) {
            out[5] += p;
            return;
        }
        if (total > 17 || (total == 17 && !(soft && hitsSoft17))) {
            out[total - 17] += p;
            return;
        }
        for (int x = 1; x <= 10; x++) {
            if (shoe[x] == 0) {
                continue;
            }
            double q = p * shoe[x] / n;
            shoe[x]--;
            dealerDraws(hard + x, ace || x == 1, shoe, n - 1, q, hitsSoft17, out);
            shoe[x]++;
        }
    }

    private static double settle(int total, double[] dealer) {
        double won = dealer[5];
        for (int d = 17; d <= 21; d++) {
            won += Integer.signum(total - d) * dealer[d - 17];
        }
        return won;
    }

    /**
     * 16 against a 10 on eight decks. Standing is the dealer's distribution and nothing else:
     * win on a bust, lose otherwise, given no natural. Hitting once and standing on whatever
     * it makes is, literally, a sum over the hole card and then the player's card, with the
     * dealer drawing from what both leave. ExactRound deals the hole card after the player's
     * card instead, so this is a check of that rearrangement on a real shoe.
     */
    @Test
    public void sixteenAgainstATenMatchesTheDealersLiteralDraws() {
        int[] s = eightDecksLess(10, 6, 10);
        int n = Arrays.stream(s).sum();
        double noNatural = 1 - s[1] / (double) n;

        double[] dealer = dealerLiterally(10, s, true);
        double stand = settle(16, dealer) / noNatural;
        assertEquals(stand, value(montreal(2, 4), s, 10, 6, 10, PlayerMove.Stand, STAND), EXACT);

        double hit = 0;
        for (int hole = 2; hole <= 10; hole++) {
            int[] afterHole = s.clone();
            afterHole[hole]--;
            double pHole = s[hole] / (double) n;
            for (int y = 1; y <= 10; y++) {
                double p = pHole * afterHole[y] / (n - 1);
                if (16 + y > 21) {
                    hit -= p;
                    continue;
                }
                int[] afterBoth = afterHole.clone();
                afterBoth[y]--;
                double[] d = new double[6];
                dealerDraws(10 + hole, hole == 1, afterBoth, n - 2, 1, true, d);
                hit += p * settle(16 + y, d);
            }
        }
        hit /= noNatural;
        assertEquals(hit, value(montreal(2, 4), s, 10, 6, 10, PlayerMove.Hit, hitBelow(17)), EXACT);
    }

    /**
     * On a shoe too big to run out, a split is valued hand by hand, every second card dealt
     * first; the coupled computation plays the hands in the contract's order with the
     * dealer's distribution carried along. Both are exact, and value() holds them to each
     * other, here on one deck and on eight with resplits up to three hands, where the
     * coupled one still finishes in a second or two. (The small shoes above go one way or
     * the other depending on how many cards they hold.)
     */
    @Test
    public void bothWaysOfValuingASplitAgreeOnRealShoes() {
        RoundPolicy resplitThenHitTo17 = d -> d.canSplit ? PlayerMove.Split
                : d.canDouble && d.total == 11 ? PlayerMove.Double
                : d.canHit && d.total < 17 ? PlayerMove.Hit : PlayerMove.Stand;
        int[] oneDeckLessEightsAndATen = shoe(1, 4, 2, 4, 3, 4, 4, 4, 5, 4, 6, 4, 7, 4, 8, 2, 9, 4, 10, 15);
        value(montreal(3, 3), oneDeckLessEightsAndATen, 8, 8, 10, PlayerMove.Split, resplitThenHitTo17);
        int[][] eightDeckCases = {{8, 10, 3}, {10, 6, 3}, {1, 6, 3}};
        for (int[] c : eightDeckCases) {
            int pair = c[0];
            int up = c[1];
            value(montreal(c[2], c[2]), eightDecksLess(pair, pair, up), pair, pair, up,
                    PlayerMove.Split, resplitThenHitTo17);
        }
    }
}
