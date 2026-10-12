package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** SHOE_SPEC.md B2: the strategy file's reader, its group and bet lookups, and B2.4's play. */
class StrategyTest {

    static void refused(StrategyFixture f, UnaryOperator<List<String>> change) {
        List<String> lines = change.apply(new java.util.ArrayList<>(f.lines()));
        assertThrows(IOException.class, () -> Strategy.parse(lines, "changed", Plants.NONE));
    }

    static StrategyFixture withPlans() {
        StrategyFixture f = new StrategyFixture();
        f.plans.add("same-8 same -");
        f.plans.add("X-1.0,8 own -2");
        f.bets.add("same-8 -2 1 1");
        f.bets.add("same-8 0 2 1");
        f.bets.add("same-8 3 8 0.25");
        f.bets.add("X-1.0,8 0 1 1");
        f.bets.add("X-1.0,8 2 4 1");
        f.insured.add("1 2 9");
        return f;
    }

    @Test
    void readsEveryScopeAndRefusesWhatIsNotB21() throws IOException {
        StrategyFixture f = withPlans();
        Strategy s = f.read();
        assertEquals("Hi-Lo = Hi-Lo Lite", s.row);
        assertEquals(332, s.cut);
        assertEquals(2, s.plans.size());
        assertEquals(-2, s.plans.get(1).exit);
        f.scope = "full";
        assertEquals("full", f.read().scope);
        f.scope = "betting";
        assertEquals("betting", f.read().scope);
        StrategyFixture g = withPlans();
        refused(g, l -> l.subList(0, l.size() - 1));
        refused(g, l -> {
            String ins = l.remove(l.indexOf("insure\t1\t2\t9"));
            l.add(l.indexOf("groups\t2") + 3, ins);
            return l;
        });
        refused(g, l -> {
            l.remove(l.indexOf("first\t1\t1\t1\t1\tS\tH"));
            return l;
        });
        refused(g, l -> {
            l.set(l.indexOf("first\t0\t2\t3\t1\tS\tH"), "first\t0\t2\t3\t1\tP\tS");
            return l;
        });
        refused(g, l -> {
            l.add(3, "row\tagain");
            return l;
        });
        refused(g, l -> {
            l.set(l.indexOf("bet\tsame-8\t3\t8\t0.25"), "bet\tsame-8\t-5\t8\t0.25");
            return l;
        });
        refused(g, l -> {
            l.removeIf(x -> x.startsWith("bet\tX-1.0,8"));
            return l;
        });
        refused(g, l -> {
            l.add(l.indexOf("end"), "colour\t1");
            return l;
        });
    }

    @Test
    void groupsAndBetsAreLookedUpAsB23AndB25Say() throws IOException {
        int[] hi = {-1, 2, 5};
        assertEquals(0, Strategy.group(hi, -9));
        assertEquals(0, Strategy.group(hi, -1));
        assertEquals(1, Strategy.group(hi, 0));
        assertEquals(1, Strategy.group(hi, 2));
        assertEquals(2, Strategy.group(hi, 3));
        assertEquals(2, Strategy.group(hi, 99));
        Strategy s = withPlans().read();
        Strategy.Plan same = s.plans.get(0);
        assertEquals(2, same.units(0));
        assertEquals(2, same.units(1), "1 is nearer 0 than 3");
        assertEquals(8, same.units(2), "2 is nearer 3 than 0");
        assertEquals(1, same.units(-9));
        assertEquals(8, same.units(40));
        assertEquals(0.25, same.play(3));
        assertEquals(2.0, s.expectedBet(same, 3));
        Strategy.Plan x = s.plans.get(1);
        assertEquals(1, x.units(1), "a tie between 0 and 2 takes the lower");
        assertTrue(s.betListed(same, 0));
        assertFalse(s.betListed(same, 1));
        Strategy plus = withPlans().read(Plants.of(Plants.BET_KEY_PLUS_ONE));
        assertEquals(8.0 * 0.25, plus.expectedBet(plus.plans.get(0), 2), "key 2 bet as 3");
    }

    /** 300 cards dealt with a Hi-Lo running count of 0: 105 low, 105 high, 90 neutral. */
    static int[] even300() {
        int[] dealt = new int[Shoe.KINDS];
        for (int k = 2; k <= 11; k++) {
            dealt[k] = k <= 6 ? 11 : 10;
        }
        for (int k : new int[]{0, 1, 18, 19, 20, 21, 22, 23, 24, 25}) {
            dealt[k] = k <= 1 || k <= 20 && k >= 18 ? 11 : 10;
        }
        for (int k = 12; k <= 17; k++) {
            dealt[k] = k == 14 || k == 15 ? 13 : 16;
        }
        return dealt;
    }

    /** 100 cards dealt with a running count of +30: sixteen 2s, fourteen 3s, seventy 7s to 9s. */
    static int[] plus30() {
        int[] dealt = new int[Shoe.KINDS];
        dealt[2] = 16;
        dealt[4] = 14;
        dealt[12] = 16;
        dealt[13] = 16;
        dealt[14] = 16;
        dealt[15] = 16;
        dealt[16] = 6;
        return dealt;
    }

    @Test
    void firstDecisionAndInsuranceComeFromTheDealsGroup() throws IOException {
        StrategyFixture f = withPlans();
        f.first = (g, p1, p2, up) -> g == 0 ? "S\tH" : "D\tS";
        Strategy s = f.read();
        Strategy.Play play = s.new Play(Chart.textbook(), false);
        Round r = new Round(Rules.montreal());
        // 9, 2 against a 6 on a full shoe: key 0, group 0, stand on 11; the dealer's 16 busts.
        play.start(new int[Shoe.KINDS], 0);
        r.play(RoundTest.deck(9, 6, 2, 10, 10, 10), 0, play, -1);
        assertEquals(1, r.main);
        // The same deal after +30 in 100 cards: key 5, group 1, double; 21 against a bust.
        play.start(plus30(), 100);
        r.play(RoundTest.deck(9, 6, 2, 10, 10, 10), 0, play, -1);
        assertEquals(2, r.main);
        // Insurance on 9, 2 against an ace is listed in group 1 only.
        play.start(new int[Shoe.KINDS], 0);
        r.play(RoundTest.deck(9, 1, 2, 10), 0, play, -1);
        assertEquals(0, r.insurance);
        play.start(plus30(), 100);
        r.play(RoundTest.deck(9, 1, 2, 10), 0, play, -1);
        assertEquals(1.0, r.insurance);
    }

    @Test
    void laterDecisionsKeyOnEveryCardSeen() throws IOException {
        StrategyFixture f = withPlans();
        f.scope = "full";
        f.first = (g, p1, p2, up) -> "H\tS";
        f.later = (l, total, soft, up) -> l == 0 ? "S" : "H";
        Strategy.Play play = f.read().new Play(Chart.textbook(), false);
        Round r = new Round(Rules.montreal());
        // 10, 2 against a 6 hits a 3: hard 15, contested. Full shoe: key 0, later group 0, stand.
        play.start(new int[Shoe.KINDS], 0);
        r.play(RoundTest.deck(10, 6, 2, 10, 3, 10), 0, play, -1);
        assertEquals(1, r.main);
        // After +30: later group 1, hit, and the 10 busts it.
        play.start(plus30(), 100);
        r.play(RoundTest.deck(10, 6, 2, 10, 3, 10), 0, play, -1);
        assertEquals(-1, r.main);
    }

    @Test
    void splitHandsAreFrozenAtTheDealsKeyUnlessTheCountMoves() throws IOException {
        StrategyFixture f = withPlans();
        f.scope = "full";
        f.first = (g, p1, p2, up) -> p1 == p2 ? "P\tS" : g == 0 ? "S\tH" : "D\tH\tS";
        Strategy s = f.read();
        Round r = new Round(Rules.montreal());
        // 8, 8 against a 6 at depth 300, running count 0: the deal's key is 52 / 113, so 0.
        // Split; the hands draw a 2 and a 3. Frozen at 0, both stand; the dealer busts: +2.
        int[] deck = {8, 6, 8, 10, 2, 3, 10, 10, 10};
        Strategy.Play frozen = s.new Play(Chart.textbook(), false);
        frozen.start(even300(), 300);
        r.play(RoundTest.deck(deck), 0, frozen, -1);
        assertEquals(2, r.main);
        // Moving, the 2 and the 3 make it 156 / 111, so 1: both double, 20 and 21: +4.
        Strategy.Play moving = s.new Play(Chart.textbook(), true);
        moving.start(even300(), 300);
        r.play(RoundTest.deck(deck), 0, moving, -1);
        assertEquals(4, r.main);
    }

    @Test
    void aSplitHandPlaysTheFirstListedMoveThatIsLegal() throws IOException {
        StrategyFixture f = withPlans();
        f.scope = "full";
        f.first = (g, p1, p2, up) -> p1 == p2 ? "P\tS" : "H\tD\tS";
        Strategy.Play play = f.read().new Play(Chart.textbook(), false);
        Round r = new Round(Rules.montreal());
        // Aces split against a 6 draw a 5 and a 4. Their lists start with Hit and Double, neither
        // legal on a split ace, so each stands; the dealer's 16 busts: +2.
        play.start(new int[Shoe.KINDS], 0);
        r.play(RoundTest.deck(1, 6, 1, 10, 5, 4, 10), 0, play, -1);
        assertEquals(2, r.main);
        // Tens split against a 6 that draw an ace are 21s and stand, whatever the list says.
        r.play(RoundTest.deck(10, 6, 10, 10, 1, 1, 10), 0, play, -1);
        assertEquals(2, r.main);
    }
}
