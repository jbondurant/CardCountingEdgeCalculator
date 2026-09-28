import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The catalog in counting-systems.json, checked for the arithmetic its tags must satisfy.
 *
 * The tags were copied from published tables by a research agent, so a slip in copying
 * is the likeliest error, and most slips break something checkable here: a balanced count
 * stops summing to zero over a deck, a colour- or face-aware system's card tags stop
 * averaging to the per-rank value the tables print, or the level stops matching the
 * largest tag.
 */
public class CountingSystemTest {

    private static final double EXACT = 1e-12;

    @Test
    public void everySystemLoadsUnderItsOwnName() {
        List<CountingSystem> all = CountingSystem.all();
        assertEquals(35, all.size());
        Set<String> names = new HashSet<>();
        for (CountingSystem s : all) {
            assertTrue(names.add(s.name), "two systems called " + s.name);
            assertTrue(s.confidence.equals("primary-source") || s.confidence.equals("two-sources"), s.name);
        }
    }

    @Test
    public void noSystemIsBothListedAndExcluded() throws Exception {
        Document catalog = Document.parse(new String(Files.readAllBytes(CountingSystem.FILE), StandardCharsets.UTF_8));
        Set<String> listed = new HashSet<>();
        for (CountingSystem s : CountingSystem.all()) {
            listed.add(s.name);
        }
        List<Document> excluded = catalog.getList("excluded", Document.class);
        assertFalse(excluded.isEmpty());
        for (Document e : excluded) {
            assertFalse(listed.contains(e.getString("name")), e.getString("name"));
            assertFalse(e.getString("reason").isEmpty(), e.getString("name"));
        }
    }

    /**
     * A balanced count sums to zero over a deck, and so over any whole shoe, so its running
     * count ends every shoe where it started. An unbalanced one gains the same amount with
     * every deck: +4 for KO, UBZ2, Uston SS, the BRH counts, the ten count and the five
     * count, +2 for Red Seven and the KISS counts.
     */
    @Test
    public void aBalancedCountSumsToZeroOverADeck() {
        for (CountingSystem s : CountingSystem.all()) {
            if (s.balanced) {
                assertEquals(0, s.deckSum(), EXACT, s.name);
            } else {
                double sum = s.deckSum();
                assertTrue(Math.abs(sum - 4) < EXACT || Math.abs(sum - 2) < EXACT, s.name + " sums to " + sum);
            }
        }
    }

    /**
     * The tables print one tag per rank, so a count that depends on colour or face is
     * printed as its average over the rank's cards: Red Seven's 7 as .5, the KISS counts'
     * 2 as .5, KISS I's ten-valued cards as -.75. The card tags must average to exactly
     * that, over the four suits of a rank and over the sixteen ten-valued cards.
     */
    @Test
    public void cardTagsAverageToThePrintedRankTags() throws Exception {
        Document catalog = Document.parse(new String(Files.readAllBytes(CountingSystem.FILE), StandardCharsets.UTF_8));
        List<CountingSystem> all = CountingSystem.all();
        List<Document> docs = catalog.getList("systems", Document.class);
        for (int i = 0; i < all.size(); i++) {
            CountingSystem s = all.get(i);
            Document printed = (Document) docs.get(i).get("tags");
            for (Map.Entry<String, Object> e : printed.entrySet()) {
                List<Rank> ranks = e.getKey().equals("T")
                        ? Arrays.asList(Rank.TEN, Rank.JACK, Rank.QUEEN, Rank.KING)
                        : Arrays.asList(rankNamed(e.getKey()));
                double sum = 0;
                for (Rank r : ranks) {
                    for (Suit suit : Suit.values()) {
                        sum += s.tag(r, suit);
                    }
                }
                assertEquals(((Number) e.getValue()).doubleValue(), sum / (4 * ranks.size()), EXACT,
                        s.name + " " + e.getKey());
            }
        }
    }

    private static Rank rankNamed(String key) {
        for (Rank r : Rank.values()) {
            if (key.equals(r == Rank.ACE ? "A" : Integer.toString(r.getRankpoints()))) {
                return r;
            }
        }
        throw new IllegalArgumentException(key);
    }

    /**
     * A system's level is its largest tag once the tags are whole numbers: Wong Halves,
     * whose tags run in halves, is level 3 because doubled they reach 3, and Thorp's
     * Ultimate is level 11.
     */
    @Test
    public void theLevelIsTheLargestWholeTag() {
        for (CountingSystem s : CountingSystem.all()) {
            int scale = 1;
            while (!allWhole(s, scale)) {
                scale++;
                assertTrue(scale <= 4, s.name + " has a tag that is not a quarter");
            }
            double largest = 0;
            for (Rank r : Rank.values()) {
                for (Suit suit : Suit.values()) {
                    largest = Math.max(largest, Math.abs(scale * s.tag(r, suit)));
                }
            }
            assertEquals(s.level, largest, EXACT, s.name);
        }
    }

    private static boolean allWhole(CountingSystem s, int scale) {
        for (Rank r : Rank.values()) {
            for (Suit suit : Suit.values()) {
                double t = scale * s.tag(r, suit);
                if (Math.abs(t - Math.rint(t)) > EXACT) {
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    public void hiLoIsTheCountTheSimulatorUses() {
        CountingSystem hiLo = CountingSystem.named("Hi-Lo");
        CountMethod simulator = CountMethod.getHiLoValue(1);
        for (Rank r : Rank.values()) {
            for (Suit suit : Suit.values()) {
                assertEquals(simulator.rankToCount.get(r), hiLo.tag(r, suit), EXACT, r + " of " + suit);
            }
        }
        assertTrue(hiLo.balanced);
        assertTrue(hiLo.trueCounted);
    }

    @Test
    public void colourAndFaceAwareSystemsTagTheCardNotTheRank() {
        CountingSystem red7 = CountingSystem.named("Red Seven (Red 7)");
        assertEquals(1, red7.tag(Rank.SEVEN, Suit.HEARTS), EXACT);
        assertEquals(1, red7.tag(Rank.SEVEN, Suit.DIAMONDS), EXACT);
        assertEquals(0, red7.tag(Rank.SEVEN, Suit.CLUBS), EXACT);
        assertEquals(0, red7.tag(Rank.SEVEN, Suit.SPADES), EXACT);
        assertFalse(red7.trueCounted);

        CountingSystem kiss1 = CountingSystem.named("KISS I");
        assertEquals(1, kiss1.tag(Rank.TWO, Suit.SPADES), EXACT);
        assertEquals(0, kiss1.tag(Rank.TWO, Suit.HEARTS), EXACT);
        assertEquals(0, kiss1.tag(Rank.TEN, Suit.CLUBS), EXACT);
        assertEquals(-1, kiss1.tag(Rank.KING, Suit.DIAMONDS), EXACT);
        assertEquals(0, kiss1.tag(Rank.ACE, Suit.SPADES), EXACT);

        CountingSystem kiss3 = CountingSystem.named("KISS III");
        assertEquals(1, kiss3.tag(Rank.TWO, Suit.CLUBS), EXACT);
        assertEquals(0, kiss3.tag(Rank.TWO, Suit.DIAMONDS), EXACT);
        assertEquals(-1, kiss3.tag(Rank.TEN, Suit.HEARTS), EXACT);
    }

    /**
     * Systems whose tags are a positive multiple of one another's carry the same
     * information, and differ only in their starting count, their divisor or their index
     * plays. These are the only such groups in the catalog. Scaling still matters once the
     * true count is rounded, so each stays its own entry.
     */
    @Test
    public void theSameCountUnderOtherNames() {
        List<CountingSystem> all = CountingSystem.all();
        List<Set<String>> groups = new ArrayList<>();
        boolean[] placed = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            if (placed[i]) {
                continue;
            }
            Set<String> group = new TreeSet<>();
            group.add(all.get(i).name);
            for (int j = i + 1; j < all.size(); j++) {
                if (!placed[j] && proportional(all.get(i), all.get(j))) {
                    group.add(all.get(j).name);
                    placed[j] = true;
                }
            }
            if (group.size() > 1) {
                groups.add(group);
            }
        }
        assertEquals(Arrays.asList(
                new TreeSet<>(Arrays.asList("Hi-Lo", "Hi-Lo Lite")),
                new TreeSet<>(Arrays.asList("KO (Knock-Out)", "REKO (Ridiculously Easy Knock-Out)")),
                new TreeSet<>(Arrays.asList("Omega II", "Canfield Master")),
                new TreeSet<>(Arrays.asList("Revere Point Count (RPC)", "FELT (Fairly Easy Level Two)",
                        "C-R (Chambliss-Roginski)"))), groups);
    }

    private static boolean proportional(CountingSystem a, CountingSystem b) {
        double k = 0;
        for (Rank r : Rank.values()) {
            for (Suit suit : Suit.values()) {
                double x = a.tag(r, suit);
                double y = b.tag(r, suit);
                if (x == 0 || y == 0) {
                    if (x != y) {
                        return false;
                    }
                } else if (k == 0) {
                    k = y / x;
                } else if (Math.abs(y - k * x) > EXACT) {
                    return false;
                }
            }
        }
        return k > 0;
    }
}
