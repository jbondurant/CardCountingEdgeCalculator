package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

/**
 * One round on decks laid out by hand, each built so that a misreading of ROUND_CONTRACT.md or
 * SHOE_SPEC.md A4 changes the payoff or the cards taken: the deal order, the peek, insurance,
 * naturals, the split order, split aces, resplits to four hands, double after split, the dealer's
 * soft 17 and when the dealer draws, and the planted bugs that change a rule.
 */
class RoundTest {

    static Round.Deck deck(int... ranks) {
        return i -> {
            if (i >= ranks.length) {
                throw new IllegalStateException("the deck ran out at card " + i);
            }
            return WalkTest.kindOfRank(ranks[i]);
        };
    }

    /** A player answering by a rule, insuring or not, and keeping every decision it was asked. */
    static final class Scripted implements Round.Player {
        final ToIntFunction<Round.Decision> rule;
        final boolean insures;
        final List<String> asked = new ArrayList<>();
        int insuranceAsked;

        Scripted(boolean insures, ToIntFunction<Round.Decision> rule) {
            this.insures = insures;
            this.rule = rule;
        }

        @Override
        public boolean insure(Round.Decision d) {
            insuranceAsked++;
            return insures;
        }

        @Override
        public int choose(Round.Decision d) {
            asked.add(d.total + (d.soft ? "s" : "") + (d.first ? " first" : "") + (d.fromSplit ? " split" : "")
                    + " legal " + d.legal);
            return rule.applyAsInt(d);
        }
    }

    static Scripted standing() {
        return new Scripted(false, d -> Round.STAND);
    }

    static Round round() {
        return new Round(Rules.montreal());
    }

    @Test
    void dealOrderIsPlayerUpPlayerHole() {
        // Player 10 and 9 (19) against a 6 up and a 10 in the hole; the dealer draws a 5: 21.
        Round r = round();
        r.play(deck(10, 6, 9, 10, 5, 2), 0, standing(), -1);
        assertEquals(-1, r.main);
        assertEquals(10, r.hole);
        assertEquals(21, r.dealerTotal);
        assertEquals(5, r.next);
        // Read as player, player, up, hole it would be 10 and 6 against a 9: another round.
    }

    @Test
    void dealerNaturalEndsTheRoundBeforeAnyDecision() {
        Scripted p = new Scripted(true, d -> {
            throw new AssertionError("no decision after a dealer natural");
        });
        Round r = round();
        r.play(deck(9, 1, 7, 10, 5), 0, p, -1);
        assertEquals(-1, r.main);
        assertEquals(1, p.insuranceAsked);
        assertEquals(1.0, r.insurance, "half a unit at 2 to 1");
        assertEquals(4, r.next);
        // A ten up and an ace in the hole is a natural too, and insurance is not offered.
        Scripted q = new Scripted(true, d -> Round.STAND);
        r.play(deck(9, 10, 7, 1, 5), 0, q, -1);
        assertEquals(-1, r.main);
        assertEquals(0, q.insuranceAsked);
        assertEquals(0, r.insurance);
        assertEquals(4, r.next);
    }

    @Test
    void naturalsAndEvenMoney() {
        Round r = round();
        r.play(deck(1, 9, 10, 10, 5), 0, standing(), -1);
        assertEquals(1.5, r.main);
        assertEquals(4, r.next);
        assertTrue(r.playerNatural);
        // Against a dealer natural it pushes; insured, it is even money: +1 in all.
        r.play(deck(1, 1, 10, 10), 0, new Scripted(true, d -> Round.STAND), -1);
        assertEquals(0, r.main);
        assertEquals(1.0, r.insurance);
        // Insured without a dealer natural: the insurance loses half a unit, the natural wins 1.5.
        r.play(deck(10, 1, 1, 9), 0, new Scripted(true, d -> Round.STAND), -1);
        assertEquals(1.5, r.main);
        assertEquals(-0.5, r.insurance);
        assertThrows(IllegalArgumentException.class, () -> r.play(deck(1, 9, 10, 10), 0, standing(), Round.HIT));
    }

    @Test
    void splitDealsBothSecondCardsFirst() {
        // 8,8 against a 6 (hole 10). Split; the 3 goes to the first hand and the 2 to the second;
        // each doubles: the first draws the 10 (21), the second the 9 (19). The dealer's 16 draws
        // the 10 and busts: +2 +2. Dealt hand by hand, the first would double 8,3 on the 2.
        Scripted p = new Scripted(false, d -> d.pair == 8 ? Round.SPLIT : d.first ? Round.DOUBLE : Round.STAND);
        Round r = round();
        r.play(deck(8, 6, 8, 10, 3, 2, 10, 9, 10), 0, p, -1);
        assertEquals(4, r.main);
        assertEquals(9, r.next);
        assertEquals(2, r.handsPlayed);
        assertEquals(List.of("16 first legal 7", "11 first split legal 3", "10 first split legal 3"), p.asked);
    }

    @Test
    void splitAcesTakeOneCardEachAndMakeNoBlackjack() {
        // A,A against a 6: the first ace draws a 10 (an ordinary 21), the second a 9. Neither
        // may hit, double or resplit, so each is asked with only Stand legal. The dealer's 16
        // draws a 5: 21 pushes the 21 and beats the 20.
        Scripted p = new Scripted(false, d -> d.pair == 1 && (d.legal & Round.L_SPLIT) != 0 ? Round.SPLIT : Round.STAND);
        Round r = round();
        r.play(deck(1, 6, 1, 10, 10, 9, 5), 0, p, -1);
        assertEquals(List.of("12s first legal 7", "21s first split legal 0", "20s first split legal 0"), p.asked);
        assertEquals(-1, r.main);
        assertEquals(7, r.next);
        // A third ace may not be split again: the round would hold three hands.
        r.play(deck(1, 6, 1, 10, 1, 1, 5), 0, p, -1);
        assertEquals(2, r.handsPlayed);
    }

    @Test
    void pairsResplitToFourHands() {
        Scripted p = new Scripted(false, d -> (d.legal & Round.L_SPLIT) != 0 ? Round.SPLIT : Round.STAND);
        Round r = round();
        r.play(deck(8, 6, 8, 10, 8, 8, 8, 8, 8, 8, 8, 8), 0, p, -1);
        assertEquals(4, r.handsPlayed);
        assertEquals(4, r.main, "four 16s against a bust 24");
        assertEquals(4 + 6 + 1, r.next);
    }

    @Test
    void doubleAfterSplitExceptAces() {
        Scripted p = new Scripted(false, d -> d.pair == 9 ? Round.SPLIT : Round.STAND);
        Round r = round();
        r.play(deck(9, 6, 9, 10, 2, 2, 10), 0, p, -1);
        assertEquals("11 first split legal 3", p.asked.get(1));
        Round planted = new Round(Rules.montreal(Plants.of(Plants.NO_DAS)));
        Scripted q = new Scripted(false, d -> d.pair == 9 ? Round.SPLIT : Round.STAND);
        planted.play(deck(9, 6, 9, 10, 2, 2, 10), 0, q, -1);
        assertEquals("11 first split legal 1", q.asked.get(1));
    }

    @Test
    void dealerHitsSoft17AndDrawsOnlyForAStandingHand() {
        // Player 20 against 6 up, ace in the hole: soft 17. The dealer hits it, draws a 3: 20, a push.
        Round r = round();
        r.play(deck(10, 6, 10, 1, 3, 10), 0, standing(), -1);
        assertEquals(0, r.main);
        assertEquals(5, r.next);
        // With the s17 plant the dealer stands on it, and the 20 wins.
        Round s17 = new Round(Rules.montreal(Plants.of(Plants.S17)));
        s17.play(deck(10, 6, 10, 1, 3, 10), 0, standing(), -1);
        assertEquals(1, s17.main);
        assertEquals(4, s17.next);
        // A player who busts leaves the dealer's 16 undrawn: the round ends on the bust card.
        r.play(deck(10, 6, 6, 10, 10, 5), 0, new Scripted(false, d -> d.total < 17 ? Round.HIT : Round.STAND), -1);
        assertEquals(-1, r.main);
        assertEquals(5, r.next);
        assertEquals(0, r.dealerTotal);
    }

    @Test
    void twentyOneIsAskedOnceWithOnlyStand() {
        Scripted p = new Scripted(false, d -> d.total < 21 ? Round.HIT : Round.STAND);
        Round r = round();
        r.play(deck(5, 6, 6, 10, 10, 9), 0, p, -1);
        assertEquals(List.of("11 first legal 3", "21 legal 0"), p.asked);
        assertEquals(1, r.main);
    }

    @Test
    void illegalMovesAndShortDecksThrow() {
        Round r = round();
        assertThrows(IllegalStateException.class, () -> r.play(deck(10, 6, 9, 10, 5), 0, new Scripted(false, d -> Round.SPLIT), -1));
        assertThrows(IllegalArgumentException.class, () -> r.play(deck(10, 6, 9, 10, 5), 0, standing(), Round.SURRENDER));
        assertThrows(IllegalStateException.class, () -> r.play(deck(10, 6, 2, 10), 0, new Scripted(false, d -> Round.HIT), -1));
    }

    @Test
    void holeCardPlantPutsTheHoleCardAmongTheSeen() {
        Round clean = round();
        Scripted p = standing();
        clean.play(deck(10, 6, 9, 10, 5), 0, p, -1);
        Round planted = new Round(Rules.montreal(), Plants.of(Plants.HOLE_CARD));
        Round.Decision[] seen = new Round.Decision[1];
        planted.play(deck(10, 6, 9, 10, 5), 0, new Scripted(false, d -> {
            seen[0] = d;
            return Round.STAND;
        }), -1);
        assertEquals(4, seen[0].dealSeen);
        assertFalse(clean.insured);
    }
}
