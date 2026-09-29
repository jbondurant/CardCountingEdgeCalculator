import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bet and play keys each count gives a state and a deal, checked against the rules of
 * COUNTING_COMPARISON.md, section 5, computed a second way here.
 */
public class CountKeysTest {

    private static final double EXACT = 1e-12;

    /** A state dealt from a shuffled eight-deck shoe: depth cards of it, by kind. */
    static int[] dealt(Random rnd, int depth) {
        int[] cards = new int[ShoeRun.CARDS];
        int c = 0;
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            for (int n = 0; n < 2 * ShoeRun.DECKS; n++) {
                cards[c++] = k;
            }
        }
        for (int i = cards.length - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = cards[i];
            cards[i] = cards[j];
            cards[j] = t;
        }
        int[] dealt = new int[ShoeRun.KINDS];
        for (int i = 0; i < depth; i++) {
            dealt[cards[i]]++;
        }
        return dealt;
    }

    private static CountKeys.Count named(String name) {
        for (CountKeys.Count c : CountKeys.catalog()) {
            if (c.systems.contains(name)) {
                return c;
            }
        }
        throw new IllegalArgumentException(name);
    }

    @Test
    public void duplicatesAreTheFourGroupsSectionFiveLists() {
        List<CountKeys.Count> all = CountKeys.catalog();
        assertEquals(CountingSystem.all().size() - 5, all.size());
        List<List<String>> groups = new ArrayList<>();
        for (CountKeys.Count c : all) {
            if (c.systems.size() > 1) {
                groups.add(c.systems);
            }
        }
        assertEquals(Arrays.asList(
                Arrays.asList("Hi-Lo", "Hi-Lo Lite"),
                Arrays.asList("KO (Knock-Out)", "REKO (Ridiculously Easy Knock-Out)"),
                Arrays.asList("Omega II", "Canfield Master"),
                Arrays.asList("Revere Point Count (RPC)", "FELT (Fairly Easy Level Two)", "C-R (Chambliss-Roginski)")),
                groups);
        assertEquals("Hi-Lo = Hi-Lo Lite", all.get(0).name);
        assertTrue(all.get(0).published.startsWith("QFIT table: BC .97"));
    }

    /** Tags are scaled to whole numbers: Wong Halves doubled, C-R doubled into RPC's tags. */
    @Test
    public void tagsAreScaledToWholeNumbers() {
        CountKeys.Count halves = named("Wong Halves");
        assertEquals(2, halves.scale);
        assertEquals(3, halves.rankTag(5));
        assertEquals(1, halves.rankTag(2));
        assertEquals(-1, halves.rankTag(9));
        assertEquals(0, halves.deckSum);
        CountKeys.Count cr = CountKeys.of(CountingSystem.named("C-R (Chambliss-Roginski)"), "");
        CountKeys.Count rpc = CountKeys.of(CountingSystem.named("Revere Point Count (RPC)"), "");
        assertEquals(2, cr.scale);
        assertArrayEquals(rpc.tag, cr.tag);
        assertEquals(4, named("KO (Knock-Out)").deckSum);
        assertEquals(2, named("Red Seven (Red 7)").deckSum);
        assertEquals(4, named("BRH-I").deckSum);
    }

    /**
     * A true count is RC_b over decks left rounded to nearest with ties toward zero, and a
     * running count is RC, drift and all.
     */
    @Test
    public void betKeysFollowTheCountsRules() {
        CountKeys.Count hiLo = named("Hi-Lo");
        // 104 cards dealt, so 6 decks left: 20 fives, 17 tens and 67 sevens, eights and nines.
        int[] dealt = new int[ShoeRun.KINDS];
        dealt[8] = 10;
        dealt[9] = 10;
        for (int k = 18; k < 26; k++) {
            dealt[k] = k < 19 ? 3 : 2;
        }
        int neutral = 67;
        for (int k = 12; k < 18 && neutral > 0; k++) {
            int n = Math.min(16, neutral);
            dealt[k] = n;
            neutral -= n;
        }
        assertEquals(104, Arrays.stream(dealt).sum());
        assertEquals(3, hiLo.runningCount(dealt));
        // 3 / 6 = 0.5 exactly, which rounds toward zero.
        assertEquals(0, hiLo.betKey(dealt, 104));
        assertEquals(0.5, hiLo.trueCount(dealt, 104), EXACT);
        dealt[12] -= 6;
        dealt[4] += 6;
        assertEquals(9, hiLo.runningCount(dealt));
        assertEquals(1, hiLo.betKey(dealt, 104));
        dealt[4] -= 6;
        dealt[12] += 6;
        dealt[8] -= 6;
        dealt[17] += 6;
        assertEquals(-3, hiLo.runningCount(dealt));
        assertEquals(0, hiLo.betKey(dealt, 104));

        Random rnd = new Random(3);
        for (int t = 0; t < 300; t++) {
            int depth = rnd.nextInt(333);
            int[] d = dealt(rnd, depth);
            for (CountKeys.Count c : CountKeys.catalog()) {
                int rc = 0;
                for (int k = 0; k < ShoeRun.KINDS; k++) {
                    rc += d[k] * c.tag[k];
                }
                double decks = (416.0 - depth) / 52;
                double tc = (rc - c.deckSum * depth / 52.0) / decks;
                assertEquals(tc, c.trueCount(d, depth), 1e-9, c.name);
                if (c.trueCounted) {
                    if (Math.abs(Math.abs(tc) % 1 - 0.5) > 1e-9) {
                        assertEquals((int) GranularCount.roundToGrain(tc, 1.0), c.betKey(d, depth), c.name);
                    }
                } else {
                    assertEquals(rc, c.betKey(d, depth), c.name);
                }
            }
        }
    }

    /** The play key counts the deal's three cards and, for a true count, takes them out of the cards left. */
    @Test
    public void playKeysSeeTheDealsCards() {
        Random rnd = new Random(8);
        for (int t = 0; t < 100; t++) {
            int depth = rnd.nextInt(330);
            int[] d = dealt(rnd, depth);
            for (CountKeys.Count c : CountKeys.catalog()) {
                if (c.kindAware) {
                    continue;
                }
                int rc = c.runningCount(d);
                int i = rnd.nextInt(Deals.COUNT);
                int tags = c.rankTag(Deals.P1[i]) + c.rankTag(Deals.P2[i]) + c.rankTag(Deals.UP[i]);
                int key = c.playKey(rc, depth, tags);
                if (!c.trueCounted) {
                    assertEquals(rc + tags, key, c.name);
                } else {
                    double tc = (rc + tags - c.deckSum * (depth + 3) / 52.0) / ((413.0 - depth) / 52);
                    if (Math.abs(Math.abs(tc) % 1 - 0.5) > 1e-9) {
                        assertEquals((int) GranularCount.roundToGrain(tc, 1.0), key, c.name);
                    }
                }
                double[][] sums = c.tagSums(d, i);
                if (Deals.probability(ShoeRun.ranksLeft(d), i) > 0) {
                    assertEquals(1, sums.length);
                    assertEquals(tags, sums[0][0], 0);
                    assertEquals(1, sums[0][1], EXACT);
                }
            }
        }
    }

    /**
     * Red Seven and the KISS counts split a deal over the tag classes of its cards. The
     * chances add up to 1 and match dealing the three cards by kind, without replacement, in
     * every way that gives the deal's ranks.
     */
    @Test
    public void theColourAndFaceSplitMatchesEnumeration() {
        List<CountKeys.Count> aware = new ArrayList<>();
        for (CountKeys.Count c : CountKeys.catalog()) {
            if (c.kindAware) {
                aware.add(c);
            }
        }
        assertEquals(Arrays.asList("Red Seven (Red 7)", "KISS I", "KISS II", "KISS III"),
                aware.stream().map(c -> c.name).collect(java.util.stream.Collectors.toList()));
        Random rnd = new Random(12);
        for (int t = 0; t < 12; t++) {
            // Deep shoes too, where a class can run out.
            int depth = t < 4 ? 400 + rnd.nextInt(10) : rnd.nextInt(333);
            int[] d = dealt(rnd, depth);
            int[] ranks = ShoeRun.ranksLeft(d);
            for (CountKeys.Count c : aware) {
                for (int i = 0; i < Deals.COUNT; i++) {
                    double[][] sums = c.tagSums(d, i);
                    Map<Integer, Double> expected = enumerate(c, d, i);
                    if (Deals.probability(ranks, i) == 0) {
                        assertEquals(0, sums.length);
                        assertTrue(expected.isEmpty());
                        continue;
                    }
                    double total = 0;
                    for (double[] e : sums) {
                        total += e[1];
                        assertEquals(expected.get((int) e[0]), e[1], EXACT, c.name + " deal " + i + " tag sum " + e[0]);
                    }
                    assertEquals(1, total, EXACT);
                    assertEquals(expected.size(), sums.length);
                }
            }
        }
    }

    /** Deals the player's cards and the up-card by kind, in every order, and keeps those with the deal's ranks. */
    private static Map<Integer, Double> enumerate(CountKeys.Count c, int[] dealt, int deal) {
        int[] left = new int[ShoeRun.KINDS];
        int n = 0;
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            left[k] = 2 * ShoeRun.DECKS - dealt[k];
            n += left[k];
        }
        Map<Integer, Double> out = new HashMap<>();
        double total = 0;
        for (int a = 0; a < ShoeRun.KINDS; a++) {
            if (left[a] == 0 || ShoeRun.rankOf(a) != Deals.P1[deal]) {
                continue;
            }
            double pa = (double) left[a] / n;
            left[a]--;
            for (int b = 0; b < ShoeRun.KINDS; b++) {
                if (left[b] == 0 || ShoeRun.rankOf(b) != Deals.P2[deal]) {
                    continue;
                }
                double pb = pa * left[b] / (n - 1);
                left[b]--;
                for (int u = 0; u < ShoeRun.KINDS; u++) {
                    if (left[u] == 0 || ShoeRun.rankOf(u) != Deals.UP[deal]) {
                        continue;
                    }
                    double pu = pb * left[u] / (n - 2);
                    out.merge(c.tag[a] + c.tag[b] + c.tag[u], pu, Double::sum);
                    total += pu;
                }
                left[b]++;
            }
            left[a]++;
        }
        for (Map.Entry<Integer, Double> e : out.entrySet()) {
            e.setValue(e.getValue() / total);
        }
        return out;
    }

    /**
     * The side-count variants adjust the bet key by m x (aces left - cards left / 13) before
     * dividing, with m the ten's tag in absolute value, and leave the play key alone.
     */
    @Test
    public void sideCountVariantsMoveOnlyTheBetKey() {
        Map<String, Integer> weight = new HashMap<>();
        for (CountKeys.Count c : CountKeys.catalog()) {
            CountKeys.Count v = CountKeys.sideCountVariant(c);
            if (v != null) {
                weight.put(c.systems.get(0), v.aceSideCount);
                assertSame(c, v.base);
                assertArrayEquals(c.tag, v.tag);
            }
        }
        Map<String, Integer> expected = new HashMap<>();
        expected.put("Hi-Opt I", 1);
        expected.put("Hi-Opt II", 2);
        expected.put("Omega II", 2);
        expected.put("Canfield Expert", 1);
        expected.put("Revere Advanced Plus-Minus", 1);
        expected.put("Revere 14 Count (1973 Advanced Point Count)", 3);
        expected.put("Uston Advanced Point Count (Uston APC)", 3);
        expected.put("Victor Advanced Point Count (Victor APC)", 3);
        assertEquals(expected, weight);

        Random rnd = new Random(21);
        int moved = 0;
        for (int t = 0; t < 200; t++) {
            int depth = rnd.nextInt(333);
            int[] d = dealt(rnd, depth);
            for (CountKeys.Count c : CountKeys.catalog()) {
                CountKeys.Count v = CountKeys.sideCountVariant(c);
                if (v == null) {
                    continue;
                }
                int rc = c.runningCount(d);
                for (int i = 0; i < Deals.COUNT; i += 7) {
                    int tags = c.rankTag(Deals.P1[i]) + c.rankTag(Deals.P2[i]) + c.rankTag(Deals.UP[i]);
                    assertEquals(c.playKey(rc, depth, tags), v.playKey(v.runningCount(d), depth, tags));
                }
                int aces = 32 - d[0] - d[1];
                double left = 416 - depth;
                double adjusted = (rc + v.aceSideCount * (aces - left / 13)) / (left / 52);
                assertEquals(adjusted, v.trueCount(d, depth), 1e-9);
                if (Math.abs(Math.abs(adjusted) % 1 - 0.5) > 1e-9) {
                    assertEquals((int) GranularCount.roundToGrain(adjusted, 1.0), v.betKey(d, depth), v.name);
                }
                moved += v.betKey(d, depth) != c.betKey(d, depth) ? 1 : 0;
            }
        }
        assertTrue(moved > 100, "the side count moved the bet key only " + moved + " times");
    }

    @Test
    public void theNullCountKeysEveryStateAndDealAtZero() {
        CountKeys.Count none = CountKeys.nullCount();
        Random rnd = new Random(2);
        for (int t = 0; t < 20; t++) {
            int depth = rnd.nextInt(333);
            int[] d = dealt(rnd, depth);
            assertEquals(0, none.betKey(d, depth));
            assertEquals(0, none.playKey(none.runningCount(d), depth, 0));
        }
        assertFalse(none.kindAware);
        assertTrue(none.balanced());
    }
}
