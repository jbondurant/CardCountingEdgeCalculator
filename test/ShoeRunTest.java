import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The shoes the comparison deals, and the states it records from them. */
public class ShoeRunTest {

    private static final RoundRules RULES = StateRun.montreal();

    private static ShoeRun run(double rate, int cut) {
        return new ShoeRun(99, rate, 0, cut, RULES, BasicStrategy.chart());
    }

    @Test
    public void aShoeIsEightFullDecks() {
        int[] cards = run(1, 332).shuffled(3);
        assertEquals(416, cards.length);
        int[] kinds = new int[ShoeRun.KINDS];
        for (int c : cards) {
            kinds[c]++;
        }
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            assertEquals(16, kinds[k], "kind " + k);
        }
        int[] ranks = ShoeRun.ranksLeft(new int[ShoeRun.KINDS]);
        assertArrayEquals(ExactRoundTimingReport.eightDecksLess(), ranks);
        assertEquals(Rank.QUEEN, ShoeRun.simulatorRank(22));
        assertEquals(10, ShoeRun.rankOf(22));
        assertEquals(1, ShoeRun.rankOf(1));
        assertTrue(ShoeRun.red(1));
        assertEquals(Suit.HEARTS, ShoeRun.suit(1));
    }

    @Test
    public void aShoeIsTheSameEveryTimeItIsDealt() {
        assertArrayEquals(run(1, 332).shuffled(5), run(0.1, 291).shuffled(5));
        assertFalse(java.util.Arrays.equals(run(1, 332).shuffled(5), run(1, 332).shuffled(6)));
    }

    @Test
    public void statesRecordTheCardsDealtBeforeEachRound() {
        ShoeRun r = run(1, 332);
        int[] cards = r.shuffled(11);
        List<ShoeRun.State> states = r.deal(11);
        assertEquals(0, states.get(0).depth);
        int previous = -1;
        for (ShoeRun.State s : states) {
            assertTrue(s.depth > previous && s.depth < 332);
            previous = s.depth;
            int[] dealt = new int[ShoeRun.KINDS];
            for (int i = 0; i < s.depth; i++) {
                dealt[cards[i]]++;
            }
            assertArrayEquals(dealt, s.dealt);
            assertTrue(s.valued);
        }
        // The round after the last one would start at or past the cut card.
        ShoeRun.State last = states.get(states.size() - 1);
        int[] ranks = new int[416];
        for (int i = 0; i < 416; i++) {
            ranks[i] = ShoeRun.rankOf(cards[i]);
        }
        assertTrue(ConcreteRound.play(ranks, last.depth, RULES, BasicStrategy.chart()).next >= 332);
    }

    /**
     * The 70% and 75% games are the 80% game stopped earlier, and choosing which states to
     * value never changes the cards.
     */
    @Test
    public void anEarlierCutDealsTheSameRoundsUpToIt() {
        for (int shoe = 0; shoe < 20; shoe++) {
            List<ShoeRun.State> full = run(1, 332).deal(shoe);
            List<ShoeRun.State> sampled = run(0.3, 332).deal(shoe);
            for (int cut : new int[]{291, 312}) {
                List<ShoeRun.State> shorter = run(0.3, cut).deal(shoe);
                int k = 0;
                for (ShoeRun.State s : full) {
                    if (s.depth < cut) {
                        assertEquals(s.depth, shorter.get(k).depth);
                        assertArrayEquals(s.dealt, shorter.get(k).dealt);
                        assertEquals(sampled.get(k).valued, shorter.get(k).valued);
                        k++;
                    }
                }
                assertEquals(k, shorter.size());
            }
            assertEquals(full.size(), sampled.size());
            for (int k = 0; k < full.size(); k++) {
                assertEquals(full.get(k).depth, sampled.get(k).depth);
            }
        }
    }

    /**
     * u is zero for a shoe in a full shoe's proportions and grows with any departure,
     * whichever ranks are rich; q grows with it, and a larger q0 values every state a
     * smaller one did.
     */
    @Test
    public void unusualShoesAreValuedMoreOften() {
        assertEquals(0, ShoeRun.unusualness(ExactRoundTimingReport.eightDecksLess()), 1e-15);
        int[] tenRich = ExactRoundTimingReport.eightDecksLess();
        int[] fiveRich = ExactRoundTimingReport.eightDecksLess();
        for (int k = 0; k < 20; k++) {
            tenRich[2 + k % 5]--;
            fiveRich[10]--;
        }
        assertTrue(ShoeRun.unusualness(tenRich) > 0 && ShoeRun.unusualness(fiveRich) > 0);
        assertEquals(0.01, ShoeRun.rate(ExactRoundTimingReport.eightDecksLess(), 0.01, 0.05), 1e-15);
        assertTrue(ShoeRun.rate(tenRich, 0.01, 0.05) > 0.01);
        assertEquals(1, ShoeRun.rate(fiveRich, 0.5, 1e-9), 0);

        ShoeRun small = new ShoeRun(99, 0.01, 0.05, 332, RULES, BasicStrategy.chart());
        ShoeRun large = new ShoeRun(99, 0.05, 0.05, 332, RULES, BasicStrategy.chart());
        int smallCount = 0;
        for (int shoe = 0; shoe < 200; shoe++) {
            List<ShoeRun.State> a = small.deal(shoe);
            List<ShoeRun.State> b = large.deal(shoe);
            for (int k = 0; k < a.size(); k++) {
                assertEquals(a.get(k).depth, b.get(k).depth);
                assertEquals(5 * a.get(k).q, b.get(k).q, 1e-12 + (b.get(k).q == 1 ? 1 : 0));
                if (a.get(k).valued) {
                    smallCount++;
                    assertTrue(b.get(k).valued, "a state valued at q0 0.01 is valued at 0.05");
                }
            }
        }
        assertTrue(smallCount > 0);
    }

    @Test
    public void theValuationRateIsTheShareOfStatesValued() {
        ShoeRun r = run(0.25, 332);
        int states = 0;
        int valued = 0;
        for (int shoe = 0; shoe < 300; shoe++) {
            for (ShoeRun.State s : r.deal(shoe)) {
                states++;
                valued += s.valued ? 1 : 0;
            }
        }
        double share = (double) valued / states;
        double se = Math.sqrt(0.25 * 0.75 / states);
        assertEquals(0.25, share, 4 * se, valued + " of " + states);
    }
}
