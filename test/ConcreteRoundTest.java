import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConcreteRound plays the one round a fixed order of cards makes. Every order of the
 * cards left is equally likely, so its payoff averaged over every order of a small shoe,
 * leaving out the orders where the hole card gives the dealer a natural, is the round's
 * value as ROUND_CONTRACT.md defines it. That is what ExactRound computes, so the two must
 * agree to rounding, and must fail together: ExactRound throws when a round can run out
 * of cards, and then some order runs ConcreteRound out of cards too.
 */
public class ConcreteRoundTest {

    private static final double TOLERANCE = 1e-12;

    private static final RoundPolicy BASIC = ExactRoundTimingReport.basicStrategy(false);

    /** Stands on 17 or more, hits below, never doubles, splits or surrenders after the first move. */
    private static final RoundPolicy HIT_BELOW_17 =
            d -> d.canHit && d.total < 17 ? PlayerMove.Hit : PlayerMove.Stand;

    /** Splits whenever it may, otherwise doubles whenever it may, otherwise hits below 15. */
    private static final RoundPolicy SPLIT_THEN_DOUBLE = d -> d.canSplit ? PlayerMove.Split
            : d.canDouble ? PlayerMove.Double : d.canHit && d.total < 15 ? PlayerMove.Hit : PlayerMove.Stand;

    /** Surrenders whenever it may, otherwise basic strategy. */
    private static final RoundPolicy SURRENDER_FIRST = d -> d.canSurrender ? PlayerMove.Surrender : BASIC.choose(d);

    /** The weighted average of ConcreteRound over every order of the shoe, or null if an order runs out. */
    private static Double averageOverEveryOrder(int[] shoe, int p1, int p2, int up, PlayerMove first,
                                                RoundPolicy later, RoundRules rules) {
        int n = 0;
        for (int r = 1; r <= 10; r++) {
            n += shoe[r];
        }
        int[] cards = new int[3 + n];
        cards[0] = p1;
        cards[1] = up;
        cards[2] = p2;
        RoundPolicy policy = d -> d.firstDecision && !d.fromSplit ? first : later.choose(d);
        double[] sums = new double[2];
        boolean[] ranOut = new boolean[1];
        enumerate(shoe.clone(), cards, 3, 1.0, rules, policy, sums, ranOut);
        if (ranOut[0]) {
            return null;
        }
        return sums[0] / sums[1];
    }

    /**
     * Every distinct order of the ranks left, each weighted by how many orders of the
     * physical cards give it: the product of the counts at each choice.
     */
    private static void enumerate(int[] left, int[] cards, int at, double weight, RoundRules rules,
                                  RoundPolicy policy, double[] sums, boolean[] ranOut) {
        if (at == cards.length) {
            ConcreteRound.Result r;
            try {
                r = ConcreteRound.play(cards, 0, rules, policy);
            } catch (IllegalStateException e) {
                if (e.getMessage().contains("none are left")) {
                    int hole = cards[3];
                    boolean natural = (cards[1] == 1 && hole == 10) || (cards[1] == 10 && hole == 1);
                    if (!natural) {
                        ranOut[0] = true;
                    }
                    return;
                }
                throw e;
            }
            if (!r.dealerNatural) {
                sums[0] += weight * r.payoff;
                sums[1] += weight;
            }
            return;
        }
        for (int r = 1; r <= 10; r++) {
            if (left[r] > 0) {
                double w = weight * left[r];
                left[r]--;
                cards[at] = r;
                enumerate(left, cards, at + 1, w, rules, policy, sums, ranOut);
                left[r]++;
            }
        }
    }

    private static void agree(int[] shoe, int p1, int p2, int up, PlayerMove first, RoundPolicy later,
                              RoundRules rules, String what) {
        Double concrete = averageOverEveryOrder(shoe, p1, p2, up, first, later, rules);
        double exact;
        try {
            exact = new ExactRound(rules).valueOfFirstMove(shoe, p1, p2, up, first, later);
        } catch (IllegalStateException e) {
            assertNull(concrete, what + ": ExactRound ran out of cards but no order did");
            return;
        }
        assertNotNull(concrete, what + ": an order ran out of cards but ExactRound valued it at " + exact);
        assertEquals(exact, concrete, TOLERANCE, what);
    }

    private static int[] shoe(int... rankCountPairs) {
        int[] s = new int[11];
        for (int i = 0; i < rankCountPairs.length; i += 2) {
            s[rankCountPairs[i]] += rankCountPairs[i + 1];
        }
        return s;
    }

    @Test
    public void aHandWorkedRound() {
        // 16 against a 10, hitting, from two fives and a ten: the hole card is a five or the
        // ten (a natural is impossible without an ace). If it is the ten (1/3), the dealer
        // has 20, only fives are left, and the player draws one for 21 and wins: +1. If it
        // is a five (2/3), the dealer has 15; the player draws the other five (1/2) for 21
        // and the dealer draws the ten and busts, +1, or the player draws the ten and
        // busts, -1: 0 on average. The value is 1/3 x 1 + 2/3 x 0 = 1/3.
        RoundRules rules = ExactRoundTimingReport.montreal(2, 4);
        int[] s = shoe(5, 2, 10, 1);
        assertEquals(1.0 / 3, averageOverEveryOrder(s, 10, 6, 10, PlayerMove.Hit, HIT_BELOW_17, rules), TOLERANCE);
        agree(s, 10, 6, 10, PlayerMove.Hit, HIT_BELOW_17, rules, "16 vs 10");
    }

    @Test
    public void aDealerNaturalEndsTheRoundBeforeAnyDecision() {
        RoundRules rules = ExactRoundTimingReport.montreal(2, 4);
        int[] cards = {9, 1, 7, 10, 5, 5, 5};
        RoundPolicy never = d -> {
            throw new AssertionError("asked " + d);
        };
        ConcreteRound.Result r = ConcreteRound.play(cards, 0, rules, never);
        assertTrue(r.dealerNatural);
        assertEquals(-1, r.payoff);
        assertEquals(4, r.next);

        int[] both = {1, 10, 10, 1};
        ConcreteRound.Result push = ConcreteRound.play(both, 0, rules, never);
        assertEquals(0, push.payoff);
        assertTrue(push.playerNatural && push.dealerNatural);

        int[] playerOnly = {1, 9, 10, 9};
        ConcreteRound.Result paid = ConcreteRound.play(playerOnly, 0, rules, never);
        assertEquals(1.5, paid.payoff);
        assertEquals(4, paid.next);
    }

    @Test
    public void cardsComeOutInTableOrder() {
        // Player 8, up 6, player 8, hole 10: basic strategy splits the eights. Each split
        // hand takes its second card before either is played: the first takes the 3, the
        // second the 2.
        RoundRules rules = ExactRoundTimingReport.montreal(2, 4);
        int[] cards = {8, 6, 8, 10, 3, 2, 10, 10, 9};
        ConcreteRound.Result r = ConcreteRound.play(cards, 0, rules, BASIC);
        // 8,3 = 11 doubles on the next card, the ten: 21 at stake 2. 8,2 = 10 doubles
        // against a 6 on the next ten: 20 at stake 2. The dealer has 16 and draws the 9: 25.
        assertEquals(2, r.hands);
        assertEquals(9, r.next);
        assertEquals(4, r.payoff);
    }

    /**
     * Random small shoes, rules and policies, every legal first move: the average over
     * every order must be ExactRound's value.
     */
    @Test
    public void everyOrderAveragesToTheExactValue() {
        Random rnd = new Random(20260928L);
        RoundPolicy[] policies = {BASIC, HIT_BELOW_17, SPLIT_THEN_DOUBLE, SURRENDER_FIRST};
        int compared = 0;
        int ranOutInBoth = 0;
        for (int c = 0; c < 400; c++) {
            boolean[] das = new boolean[11];
            for (int r = 1; r <= 10; r++) {
                das[r] = rnd.nextBoolean();
            }
            boolean surrender = rnd.nextBoolean();
            RoundRules rules = new RoundRules(rnd.nextBoolean(), rnd.nextBoolean() ? 1.5 : 1.2,
                    1 + rnd.nextInt(3), 1 + rnd.nextInt(4), rnd.nextInt(4) == 0, das, rnd.nextBoolean(),
                    surrender, surrender && rnd.nextBoolean());
            int p1 = 1 + rnd.nextInt(10);
            int p2 = rnd.nextInt(3) == 0 ? p1 : 1 + rnd.nextInt(10);
            int up = 1 + rnd.nextInt(10);
            int[] s = new int[11];
            int n = 4 + rnd.nextInt(5);
            for (int i = 0; i < n; i++) {
                s[rnd.nextInt(3) == 0 ? p1 : 1 + rnd.nextInt(10)]++;
            }
            if (up == 1 && s[10] == n || up == 10 && s[1] == n) {
                continue;
            }
            boolean natural = (p1 == 1 && p2 == 10) || (p1 == 10 && p2 == 1);
            RoundPolicy later = policies[rnd.nextInt(policies.length)];
            for (PlayerMove first : PlayerMove.values()) {
                if (!legalFirst(first, p1, p2, rules, natural)) {
                    continue;
                }
                String what = "case " + c + ": " + p1 + "," + p2 + " vs " + up + " " + first + " on "
                        + java.util.Arrays.toString(s);
                Double concrete = averageOverEveryOrder(s, p1, p2, up, first, later, rules);
                agree(s, p1, p2, up, first, later, rules, what);
                if (concrete == null) {
                    ranOutInBoth++;
                } else {
                    compared++;
                }
            }
        }
        assertTrue(compared > 1000, "only " + compared + " rounds compared");
        assertTrue(ranOutInBoth > 20, "only " + ranOutInBoth + " rounds ran out of cards");
    }

    private static boolean legalFirst(PlayerMove m, int p1, int p2, RoundRules rules, boolean natural) {
        if (natural) {
            return m == PlayerMove.Stand;
        }
        switch (m) {
            case Stand:
            case Hit:
            case Double:
                return true;
            case Split:
                return p1 == p2 && rules.maxHandsFor(p1) >= 2;
            case Surrender:
                return rules.surrender;
            default:
                return false;
        }
    }

    /** A kind of each rank: the black one, ten-spots for the tens. */
    private static int kindOf(int rank) {
        return 2 * (rank - 1);
    }

    /**
     * The kind hook shows the player each card as it comes out, and the hole card only once
     * every hand has ended, before the dealer's draws. Player 8, up 6, player 8, hole 10:
     * the eights split, the first takes the 3 and the second the 2, both double on a ten,
     * and the dealer turns the ten and draws the 9. A red 3 and a red 2 are dealt, so the
     * kinds are told apart from the ranks.
     */
    @Test
    public void theKindHookShowsTheCardsInTheOrderThePlayerSeesThem() {
        RoundRules rules = ExactRoundTimingReport.montreal(2, 4);
        int[] cards = {8, 6, 8, 10, 3, 2, 10, 10, 9};
        int[] kinds = new int[cards.length];
        for (int i = 0; i < cards.length; i++) {
            kinds[i] = kindOf(cards[i]);
        }
        kinds[4] = kindOf(3) + 1;
        kinds[5] = kindOf(2) + 1;
        java.util.List<Integer> shown = new java.util.ArrayList<>();
        java.util.List<Integer> shownAtDecisions = new java.util.ArrayList<>();
        RoundPolicy watching = d -> {
            shownAtDecisions.add(shown.size());
            return BASIC.choose(d);
        };
        ConcreteRound.Result r = ConcreteRound.play(cards, kinds, 0, rules, watching, shown::add);
        ConcreteRound.Result plain = ConcreteRound.play(cards, 0, rules, BASIC);
        assertEquals(plain.payoff, r.payoff);
        assertEquals(plain.next, r.next);
        // Split with 3 shown; both split hands have their second card before either plays, so
        // the first doubles with 5 shown and the second, after the first's double card, with 6.
        assertEquals(java.util.Arrays.asList(3, 5, 6), shownAtDecisions);
        assertEquals(java.util.Arrays.asList(kinds[0], kinds[1], kinds[2], kinds[4], kinds[5], kinds[6], kinds[7],
                kinds[3], kinds[8]), shown);

        // A dealer natural ends the round before any decision, and then the hole card is shown.
        int[] natural = {9, 1, 7, 10, 5};
        int[] naturalKinds = {kindOf(9), kindOf(1) + 1, kindOf(7), kindOf(10) + 7, kindOf(5)};
        java.util.List<Integer> seen = new java.util.ArrayList<>();
        ConcreteRound.Result n = ConcreteRound.play(natural, naturalKinds, 0, rules, d -> {
            throw new AssertionError("asked " + d);
        }, seen::add);
        assertTrue(n.dealerNatural);
        assertEquals(java.util.Arrays.asList(naturalKinds[0], naturalKinds[1], naturalKinds[2], naturalKinds[3]), seen);

        // A kind of another rank is refused.
        int[] wrong = kinds.clone();
        wrong[2] = kindOf(7);
        assertThrows(IllegalArgumentException.class,
                () -> ConcreteRound.play(cards, wrong, 0, rules, BASIC, k -> { }));
    }

    /**
     * With the hook, a round plays and pays exactly as without it: random orders of small
     * shoes under every policy here, each card given a random kind of its rank, and the hole
     * card never among the kinds shown before the last decision.
     */
    @Test
    public void theKindHookChangesNothingAboutTheRound() {
        Random rnd = new Random(20261003L);
        RoundPolicy[] policies = {BASIC, HIT_BELOW_17, SPLIT_THEN_DOUBLE, SURRENDER_FIRST};
        int played = 0;
        for (int c = 0; c < 3000; c++) {
            RoundRules rules = ExactRoundTimingReport.montreal(1 + rnd.nextInt(2), 1 + rnd.nextInt(4));
            int[] cards = new int[6 + rnd.nextInt(10)];
            int[] kinds = new int[cards.length];
            for (int i = 0; i < cards.length; i++) {
                cards[i] = rnd.nextInt(3) == 0 ? 10 : 1 + rnd.nextInt(10);
                kinds[i] = cards[i] == 10 ? 18 + rnd.nextInt(8) : kindOf(cards[i]) + rnd.nextInt(2);
            }
            RoundPolicy policy = policies[rnd.nextInt(policies.length)];
            ConcreteRound.Result plain;
            try {
                plain = ConcreteRound.play(cards, 0, rules, policy);
            } catch (IllegalStateException e) {
                assertThrows(IllegalStateException.class,
                        () -> ConcreteRound.play(cards, kinds, 0, rules, policy, k -> { }));
                continue;
            }
            java.util.List<Integer> shown = new java.util.ArrayList<>();
            int[] shownAtLastDecision = {-1};
            RoundPolicy counting = d -> {
                shownAtLastDecision[0] = shown.size();
                return policy.choose(d);
            };
            ConcreteRound.Result hooked = ConcreteRound.play(cards, kinds, 0, rules, counting, shown::add);
            assertEquals(plain.payoff, hooked.payoff);
            assertEquals(plain.next, hooked.next);
            assertEquals(plain.hands, hooked.hands);
            // Shown: the deal's three cards, the player's draws (cards 4 to k - 1), the hole
            // card, then the dealer's draws (k onward), with every decision before the hole.
            boolean fits = false;
            for (int k = 4; k <= plain.next && !fits; k++) {
                java.util.List<Integer> expected = new java.util.ArrayList<>();
                expected.add(kinds[0]);
                expected.add(kinds[1]);
                expected.add(kinds[2]);
                for (int i = 4; i < k; i++) {
                    expected.add(kinds[i]);
                }
                int holeAt = expected.size();
                expected.add(kinds[3]);
                for (int i = k; i < plain.next; i++) {
                    expected.add(kinds[i]);
                }
                fits = expected.equals(shown) && shownAtLastDecision[0] <= holeAt;
            }
            assertTrue(fits, "shown " + shown + " of " + java.util.Arrays.toString(kinds) + ", last decision after "
                    + shownAtLastDecision[0]);
            played++;
        }
        assertTrue(played > 2000, played + " rounds played");
    }
}
