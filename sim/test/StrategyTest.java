package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * SHOE_SPEC.md B2: the strategy file's reader, its group and bet lookups, and B2.4's play, split
 * lines included.
 *
 * <p>Planted on 19 October 2026 in a scratch copy of sim/ outside the worktree, one at a time;
 * each failed the test named. In the reader, splitLinesAreReadAndRefusedWhereB21RefusesThem
 * failed for each: {@code split} after {@code insure} in B2.1's order (the fixture's own file
 * refused); and each refusal of a split line left out in turn: scope full, the group, the ranks,
 * the natural, the order, a deal given twice ({@code <} for {@code <=}), the same moves, another
 * order (the group's line then refused only as an array index, the natural's only as a deal with
 * no first line, neither of which the test accepts as the reason). In the play: split hands
 * reading the first line alone (b24sExample..., aNonPairSplitLine..., theMovingCountReads... and
 * the reader test, with three of LookupsTest's); the dealt hand reading a split line where there is
 * one (b24sExample..., aNonPairSplitLine..., splitAcesAndAnAceWithATenStillStand); the moving
 * variant's split hand reading the deal's group (theMovingCountReads... and
 * splitHandsAreFrozenAtTheDealsKeyUnlessTheCountMoves); an ace and a ten no longer stood, and a
 * split hand's list read without regard to what is legal (eleven tests each, among them splitAces...
 * and aSplitHandPlaysTheFirstListedMoveThatIsLegal here, and tests of LookupsTest, TableTest,
 * P2Test, P3Test and VariantsTest). After the clean-room review, also in the reader: a split line
 * whose first move is its first line's refused (splitLinesAreRead..., the fixture's own file
 * refused, and all five of LookupsTest's tests, whose full-play file holds such a line too), or
 * read and then dropped (splitLinesAreRead..., and LookupsTest's theSplitCellsAreB24sWorkedByHand
 * and splitHandsThatReadTheFirstLineAreCaught); and the refusal of a p1 below 1, an up-card below 1 or
 * above 10, left out all at once, the p1 alone and the up-card alone (splitLinesAreRead..., where
 * a p1 of 0 was then read as (1, 6)'s line and an up-card of 0 refused only as an array index).
 */
class StrategyTest {

    static void refused(StrategyFixture f, UnaryOperator<List<String>> change) {
        List<String> lines = change.apply(new java.util.ArrayList<>(f.lines()));
        assertThrows(IOException.class, () -> Strategy.parse(lines, "changed", Plants.NONE));
    }

    /** Refused, and for this reason: the reader's message names it. */
    static void refusedFor(StrategyFixture f, String why, UnaryOperator<List<String>> change) {
        List<String> lines = change.apply(new java.util.ArrayList<>(f.lines()));
        IOException e = assertThrows(IOException.class, () -> Strategy.parse(lines, "changed", Plants.NONE));
        assertTrue(e.getMessage().contains(why), e.getMessage());
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

    /**
     * A full-play file with three split lines: group 0's (3, 3) against an ace, whose list starts with
     * Hit as its first line's does and differs after it (SHOE_SPEC.md B6 item 12's case), group 0's
     * (3, 8) against a 6, and group 1's (10, 10) against a 6.
     */
    static StrategyFixture withSplitLines() {
        StrategyFixture f = withPlans();
        f.scope = "full";
        f.first = (g, p1, p2, up) -> p1 == 3 && p2 == 3 && up == 1 ? "H\tP\tS\tD" : p1 == p2 ? "P\tS\tH\tD" : "S\tH\tD";
        f.split = (g, p1, p2, up) -> g == 0 && p1 == 3 && p2 == 3 && up == 1 ? "H\tS\tP\tD"
                : g == 0 && p1 == 3 && p2 == 8 && up == 6 ? "D\tS\tH"
                : g == 1 && p1 == 10 && p2 == 10 && up == 6 ? "S\tP\tH\tD" : null;
        return f;
    }

    /** Inserts a line before (offset 0) or after (offset 1) the first line equal to at. */
    static UnaryOperator<List<String>> insert(String at, int offset, String line) {
        return l -> {
            l.add(l.indexOf(at) + offset, line);
            return l;
        };
    }

    static UnaryOperator<List<String>> replace(String line, String with) {
        return l -> {
            l.set(l.indexOf(line), with);
            return l;
        };
    }

    @Test
    void splitLinesAreReadAndRefusedWhereB21RefusesThem() throws IOException {
        StrategyFixture f = withSplitLines();
        String threes = "split\t0\t3\t3\t1\tH\tS\tP\tD";
        String g0 = "split\t0\t3\t8\t6\tD\tS\tH";
        String g1 = "split\t1\t10\t10\t6\tS\tP\tH\tD";
        assertTrue(f.lines().containsAll(List.of("first\t0\t3\t3\t1\tH\tP\tS\tD", threes, g0, g1)));
        Strategy s = f.read();
        int hd = Round.L_HIT | Round.L_DOUBLE;
        assertEquals(Round.DOUBLE, s.splitFirstMove(0, 3, 8, 6, hd), "a split 3 that draws an 8 reads the split line");
        assertEquals(Round.DOUBLE, s.splitFirstMove(0, 8, 3, 6, hd), "and so does a split 8 that draws a 3");
        assertEquals(Round.STAND, s.splitFirstMove(1, 3, 8, 6, hd), "group 1 has no split line for it: its first line");
        assertEquals(Round.STAND, s.splitFirstMove(1, 10, 10, 6, hd | Round.L_SPLIT));
        assertEquals(Round.SPLIT, s.splitFirstMove(0, 10, 10, 6, hd | Round.L_SPLIT));
        // A split line that starts with its first line's move is read too. Under Montreal's rules a
        // split 3 that draws a 3 may always hit, so both lists give Hit; with Hit taken away (no
        // such hand, but it shows which list is read) the split line's next move is Stand, where
        // the first line's, as group 1 reads it, is Split.
        assertEquals(Round.HIT, s.splitFirstMove(0, 3, 3, 1, hd | Round.L_SPLIT));
        assertEquals(Round.STAND, s.splitFirstMove(0, 3, 3, 1, Round.L_DOUBLE | Round.L_SPLIT), "the split line read");
        assertEquals(Round.SPLIT, s.splitFirstMove(1, 3, 3, 1, Round.L_DOUBLE | Round.L_SPLIT), "the first line read");
        // a file not of scope full
        StrategyFixture first = withSplitLines();
        first.scope = "first";
        refusedFor(first, "only in scope full", l -> l);
        first.scope = "betting";
        refusedFor(first, "only in scope full", l -> l);
        // a group outside 0 to G - 1, after the last line
        refusedFor(f, "group, 0 to G - 1", insert(g1, 1, "split\t2\t3\t8\t6\tD\tS\tH"));
        // p1 above p2, where (9, 10) would come in order
        refusedFor(f, "p1 <= p2", insert(g1, 0, "split\t1\t10\t9\t6\tH\tS\tD"));
        // a rank outside 1 to 10, after the last line (read as a place, it would be (1, 1) against a 7)
        refusedFor(f, "ranks 1 to 10", insert(g1, 1, "split\t1\t10\t11\t6\tS\tP\tH\tD"));
        // p1 of 0, where (1, 6) against a 6 would come in order: read as a place, deal(0, 5, 6) is
        // deal(1, 6, 6), and these are that deal's moves in another order
        refusedFor(f, "ranks 1 to 10", insert(g0, 0, "split\t0\t0\t5\t6\tH\tS\tD"));
        // an up-card of 0 or 11
        refusedFor(f, "ranks 1 to 10", insert(g1, 0, "split\t1\t3\t8\t0\tD\tS\tH"));
        refusedFor(f, "ranks 1 to 10", insert(g1, 1, "split\t1\t3\t8\t11\tD\tS\tH"));
        // the natural, which has no first line
        refusedFor(f, "natural", insert(g0, 0, "split\t0\t1\t10\t6\tS"));
        // out of first-line order, and a deal given twice
        refusedFor(f, "first-line order", l -> {
            l.remove(g0);
            l.add(l.indexOf(g1) + 1, g0);
            return l;
        });
        refusedFor(f, "first-line order", insert(g0, 1, g0));
        // moves other than its first line's: one dropped, one added twice, one changed
        refusedFor(f, "each as many times", replace(g0, "split\t0\t3\t8\t6\tD\tS"));
        refusedFor(f, "each as many times", replace(g0, "split\t0\t3\t8\t6\tD\tS\tH\tH"));
        refusedFor(f, "each as many times", replace(g0, "split\t0\t3\t8\t6\tD\tS\tR"));
        // its first line's own order
        refusedFor(f, "other than its first line's", replace(g0, "split\t0\t3\t8\t6\tS\tH\tD"));
        // split lines come after every first line and before the insure lines (B2.1's order)
        refusedFor(f, "out of B2.1's order", l -> {
            l.remove(g1);
            l.add(l.indexOf("insure\t1\t2\t9") + 1, g1);
            return l;
        });
        refusedFor(f, "out of B2.1's order", l -> {
            l.remove(g0);
            l.add(l.indexOf("first\t1\t1\t1\t1\tP\tS\tH\tD"), g0);
            return l;
        });
    }

    @Test
    void b24sExampleTheDealtTensSplitAndASplitTenThatDrawsATenStands() throws IOException {
        StrategyFixture f = new StrategyFixture();
        f.scope = "full";
        f.groupHi = new int[]{-9, -8, -7, -6, -5, -4, -3, -2, -1, 99};
        f.first = (g, p1, p2, up) -> p1 == p2 ? "P\tS\tH\tD" : "S\tH\tD";
        f.split = (g, p1, p2, up) -> g == 9 && p1 == 10 && p2 == 10 && up == 6 ? "S\tP\tH\tD" : null;
        assertTrue(f.lines().containsAll(List.of("first\t9\t10\t10\t6\tP\tS\tH\tD", "split\t9\t10\t10\t6\tS\tP\tH\tD")));
        Round r = new Round(Rules.montreal());
        int[] tens = {10, 6, 10, 10, 10, 10, 10, 10, 10, 10, 10};
        // A full shoe, 10, 6 and 10 seen: key round(-52 / 413) = 0, group 9. The dealt pair splits by
        // its first line; each split ten draws a ten and stands by the split line; the dealer's 16
        // draws a ten: two hands of 20 against a bust, +2.
        Strategy.Play play = f.read().new Play(Chart.textbook(), false);
        play.start(new int[Shoe.KINDS], 0);
        r.play(RoundTest.deck(tens), 0, play, -1);
        assertEquals(2, r.handsPlayed);
        assertEquals(2, r.main);
        // Without the split line the split tens read the first line and resplit, to four hands of 20: +4.
        f.split = (g, p1, p2, up) -> null;
        Strategy.Play without = f.read().new Play(Chart.textbook(), false);
        without.start(new int[Shoe.KINDS], 0);
        r.play(RoundTest.deck(tens), 0, without, -1);
        assertEquals(4, r.handsPlayed);
        assertEquals(4, r.main);
    }

    @Test
    void aNonPairSplitLineIsReadByBothSplitHandsThatReachItAndNeverByTheDealtHand() throws IOException {
        StrategyFixture f = new StrategyFixture();
        f.scope = "full";
        f.first = (g, p1, p2, up) -> p1 == p2 ? "P\tS\tH\tD" : "S\tH\tD";
        f.split = (g, p1, p2, up) -> g == 0 && p1 == 3 && p2 == 8 && up == 6 ? "D\tS\tH" : null;
        Strategy.Play play = f.read().new Play(Chart.textbook(), false);
        Round r = new Round(Rules.montreal());
        // 3, 3 against a 6 on a full shoe (key 0, group 0) splits. The first 3 draws an 8: 11, which
        // the split line for (3, 8) doubles, to 21; the second draws a ten, 13, and stands by its
        // first line. The dealer's 16 draws a ten: +2 + 1.
        play.start(new int[Shoe.KINDS], 0);
        r.play(RoundTest.deck(3, 6, 3, 10, 8, 10, 10, 10), 0, play, -1);
        assertEquals(3, r.main);
        // 8, 8: the first 8 draws a 3 and reads the same line: doubles to 21; the second, 18, stands: +3.
        play.start(new int[Shoe.KINDS], 0);
        r.play(RoundTest.deck(8, 6, 8, 10, 3, 10, 10, 10), 0, play, -1);
        assertEquals(3, r.main);
        // The dealt 3, 8 against a 6 stands by its first line, never doubling by the split line: +1.
        play.start(new int[Shoe.KINDS], 0);
        r.play(RoundTest.deck(3, 6, 8, 10, 10, 10), 0, play, -1);
        assertEquals(1, r.main);
        assertEquals(1, r.handsPlayed);
    }

    @Test
    void splitAcesAndAnAceWithATenStillStand() throws IOException {
        StrategyFixture f = new StrategyFixture();
        f.scope = "full";
        f.first = (g, p1, p2, up) -> p1 == p2 ? "P\tS\tH\tD" : "H\tD\tS";
        f.split = (g, p1, p2, up) -> up != 6 ? null : p1 == 1 && p2 == 5 ? "D\tH\tS"
                : p1 == 10 && p2 == 10 ? "S\tP\tH\tD" : null;
        Strategy.Play play = f.read().new Play(Chart.textbook(), false);
        Round r = new Round(Rules.montreal());
        // Aces split against a 6 draw a 5 and a 4: the 5's split line starts with Double, the 4's
        // first line with Hit, neither legal on a split ace, so both stand; the dealer busts: +2.
        play.start(new int[Shoe.KINDS], 0);
        r.play(RoundTest.deck(1, 6, 1, 10, 5, 4, 10), 0, play, -1);
        assertEquals(2, r.main);
        // Split aces that draw tens, and split tens that draw aces, are 21s and stand.
        play.start(new int[Shoe.KINDS], 0);
        r.play(RoundTest.deck(1, 6, 1, 10, 10, 10, 10), 0, play, -1);
        assertEquals(2, r.main);
        play.start(new int[Shoe.KINDS], 0);
        r.play(RoundTest.deck(10, 6, 10, 10, 1, 1, 10), 0, play, -1);
        assertEquals(2, r.main);
        // The dealt ace and ten is a natural, paid 3 to 2 with no decision.
        play.start(new int[Shoe.KINDS], 0);
        r.play(RoundTest.deck(1, 6, 10, 10), 0, play, -1);
        assertEquals(1.5, r.main);
    }

    @Test
    void theMovingCountReadsTheSplitLineOfItsMovingGroup() throws IOException {
        StrategyFixture f = new StrategyFixture();
        f.scope = "full";
        f.first = (g, p1, p2, up) -> p1 == p2 ? "P\tS\tH\tD" : "S\tH\tD";
        f.split = (g, p1, p2, up) -> p1 == 2 && p2 == 8 && up == 6 ? (g == 0 ? "H\tS\tD" : "D\tS\tH") : null;
        Strategy s = f.read();
        Round r = new Round(Rules.montreal());
        int[] deck = {8, 6, 8, 10, 2, 3, 10, 2};
        // 8, 8 against a 6 at depth 300, running count 0: the deal's key is round(52 / 113) = 0,
        // group 0. The hands draw a 2 and a 3. Frozen, the 8, 2 reads group 0's split line, Hit, a
        // ten: 20; the 8, 3 has no split line and stands on 11 by its first line. The dealer's 16
        // draws a 2: 18. +1 - 1.
        Strategy.Play frozen = s.new Play(Chart.textbook(), false);
        frozen.start(even300(), 300);
        r.play(RoundTest.deck(deck), 0, frozen, -1);
        assertEquals(0, r.main);
        // Moving, the 2 and the 3 make the key round(156 / 111) = 1, group 1: the 8, 2 reads group
        // 1's split line, Double, to 20 for 2 units; the 8, 3, at round(104 / 110) = 1, has no split
        // line in group 1 and stands. +2 - 1.
        Strategy.Play moving = s.new Play(Chart.textbook(), true);
        moving.start(even300(), 300);
        r.play(RoundTest.deck(deck), 0, moving, -1);
        assertEquals(1, r.main);
    }
}
