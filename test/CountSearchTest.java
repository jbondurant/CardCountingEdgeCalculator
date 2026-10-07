import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A planted optimum (COUNT_DESIGN.md, section 6): SystemScorerTest's synthetic states, their move
 * values linear in the composition along a hidden direction, and a chart advantage that rises
 * along it. The count search, scoring candidates by SystemScorer.estimate as on the twin, must
 * find the hidden direction from Hi-Lo; with one direction for the bets and another for the
 * plays, F2 must find both and F1 land between them.
 */
public class CountSearchTest {

    private static final int STAND = 0;
    private static final int DOUBLE = 2;
    private static final int SHOES = 1200;
    private static final double[] HI_LO = {0, -1, 1, 1, 1, 1, 1, 0, 0, 0, -1};

    /** The true count of real tags by rank, per 52 cards, over the cards left: -52 sum t (p - p0). */
    static double trueCount(double[] t, int[] dealt) {
        int[] left = ShoeRun.ranksLeft(dealt);
        int n = 0;
        for (int r = 1; r <= 10; r++) {
            n += left[r];
        }
        double z = 0;
        for (int r = 1; r <= 10; r++) {
            z -= 52 * t[r] * ((double) left[r] / n - CountKeys.P0[r]);
        }
        return z;
    }

    /**
     * One state a shoe, dealt at random to 80 to 300 cards. Every move of every deal is worth 0.1
     * x the bet direction's true count, so the chart's advantage rises along it; doubling gains or
     * loses 0.1 x the play direction's true count, the sign alternating by deal, so the best first
     * move flips where that count crosses zero; the other moves lose 0.05. What the best play adds
     * grows with the play count either way, which no linear count bets on; the bets' term is kept
     * the larger, so that the best betting vector is the bets' direction.
     */
    static ScoringStates planted(double[] bet, double[] play, long seed) {
        Random rnd = new Random(seed);
        double[] ub = CountKeys.normalised(bet);
        double[] up = CountKeys.normalised(play);
        List<StateStore.Record> records = new ArrayList<>();
        for (int shoe = 0; shoe < SHOES; shoe++) {
            int[] dealt = CountKeysTest.dealt(rnd, 80 + rnd.nextInt(221));
            double zb = trueCount(ub, dealt);
            double zp = trueCount(up, dealt);
            records.add(SystemScorerTest.state(shoe, 0, dealt, 1, (i, m) -> {
                double v = 0.1 * zb;
                if (m == DOUBLE) {
                    return v + (i % 2 == 0 ? 0.1 : -0.1) * zp;
                }
                return m == STAND ? v : v - 0.05;
            }));
        }
        return SystemScorerTest.prepare(records, SHOES);
    }

    private static double[] real(int[] t) {
        double[] out = new double[11];
        for (int r = 1; r <= 10; r++) {
            out[r] = t[r];
        }
        return out;
    }

    private static double[] playTags(CountKeys.Count c) {
        return CountSearch.rankTags(c);
    }

    private static double[] betTags(CountKeys.Count c) {
        double[] t = new double[11];
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            t[ShoeRun.rankOf(k)] += c.betVector[k] / (double) (ShoeRun.rankOf(k) == 10 ? 8 : 2);
        }
        return t;
    }

    private static CountSearch.Settings settings(String form, int maxEvals) {
        CountSearch.Settings s = new CountSearch.Settings();
        s.form = form;
        s.divisor = CountKeys.K / 10;
        s.maxEvals = maxEvals;
        s.minAngle = Math.toRadians(0.2);
        s.threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
        s.seed = 11;
        return s;
    }

    private static double f1Value(SystemScorer sc, CountSearch.Settings s, double[] tags) {
        return sc.estimate(CountKeys.whole("x", CountSearch.whole(CountSearch.onSphere(CountSearch.coordinates(tags)),
                s.sign), null, s.divisor, true), null, s.pen)[0][s.metric];
    }

    /** From Hi-Lo and from a random start, F1 finds the planted direction (cosine at least 0.99) and beats both starts. */
    @Test
    public void f1FindsThePlantedDirection() throws Exception {
        double[] hidden = {0, -0.6, 0.5, 0.7, 0.9, 1.2, 0.8, 0.4, 0, -0.3, -0.9};
        ScoringStates st = planted(hidden, hidden, 1);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(SHOES, 0, 1), 1);
        Path dir = Files.createTempDirectory("search");
        double[][] starts = {HI_LO, {0, 0.2, 0.9, -0.3, 0.6, 0.1, 0.7, -0.5, 0.4, 0.3, -0.6}};
        for (int k = 0; k < starts.length; k++) {
            CountSearch.Settings s = settings("F1", 720);
            s.run = "start " + k;
            CountKeys.Count found;
            try (CountSearch.SearchLog log = new CountSearch.SearchLog(dir.resolve("f1-" + k + ".tsv"), "# test", false)) {
                found = CountSearch.search(sc, s, starts[k], null, log, false, null);
            }
            double cos = CountSearch.cosine(playTags(found), hidden);
            assertTrue(cos >= 0.99, "start " + k + ": the direction found is at cosine " + cos);
            double v = sc.estimate(found, null, s.pen)[0][s.metric];
            assertTrue(v >= f1Value(sc, s, starts[k]), "start " + k + " beaten");
            assertTrue(v >= f1Value(sc, s, hidden) - 0.002, "start " + k + ": " + v + " against the planted " + f1Value(sc, s, hidden));
        }
    }

    /**
     * With the bets rising along one direction and the plays flipping along another, F2 finds
     * each (cosine at least 0.98), and F1, one vector for both, lands between them.
     */
    @Test
    public void f2FindsBothDirectionsAndF1LandsBetween() throws Exception {
        double[] bet = {0, -1, 0.6, 0.8, 1, 1.2, 0.9, 0.3, 0, -0.3, -0.9};
        double[] play = {0, 0.8, 1.0, 0.9, 0.4, -0.2, -0.6, -0.7, -0.3, 0.2, -0.2};
        ScoringStates st = planted(bet, play, 2);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(SHOES, 0, 1), 1);
        Path dir = Files.createTempDirectory("search");
        CountSearch.Settings s2 = settings("F2", 1200);
        s2.run = "f2";
        CountKeys.Count f2;
        try (CountSearch.SearchLog log = new CountSearch.SearchLog(dir.resolve("f2.tsv"), "# test", false)) {
            f2 = CountSearch.search(sc, s2, HI_LO, HI_LO, log, false, null);
        }
        double cp = CountSearch.cosine(playTags(f2), play);
        double cb = CountSearch.cosine(betTags(f2), bet);
        assertTrue(cp >= 0.98, "the play vector is at cosine " + cp);
        assertTrue(cb >= 0.98, "the betting vector is at cosine " + cb);

        CountSearch.Settings s1 = settings("F1", 720);
        s1.run = "f1";
        CountKeys.Count f1;
        try (CountSearch.SearchLog log = new CountSearch.SearchLog(dir.resolve("f1.tsv"), "# test", false)) {
            f1 = CountSearch.search(sc, s1, HI_LO, null, log, false, null);
        }
        double apart = CountSearch.cosine(bet, play);
        double toBet = CountSearch.cosine(playTags(f1), bet);
        double toPlay = CountSearch.cosine(playTags(f1), play);
        assertTrue(toBet > apart && toPlay > apart, "F1 at cosine " + toBet + " to the bets and " + toPlay
                + " to the plays, which are at " + apart + " to each other");
        double v2 = sc.estimate(f2, CountKeys.bettingVariant(f2), s2.pen)[2][s2.metric];
        double v1 = sc.estimate(f1, null, s1.pen)[0][s1.metric];
        assertTrue(v2 > v1, "F2 " + v2 + " beats F1 " + v1);

        // The searches' finals, written as a candidates file, read back as the counts found, to the bit.
        Path file = dir.resolve("candidates.json");
        Files.write(file, CountSearch.candidates(java.util.Arrays.asList(dir.resolve("f2.tsv"), dir.resolve("f1.tsv")))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CustomCounts.Listing l = CustomCounts.read(file);
        assertEquals(2, l.entries.size());
        CountKeys.Count r2 = l.entries.get(0).count;
        CountKeys.Count r1 = l.entries.get(1).count;
        assertEquals("f2", r2.name);
        assertArrayEquals(f2.tag, r2.tag);
        assertArrayEquals(f2.betVector, r2.betVector);
        assertEquals(f2.divisor(), r2.divisor());
        assertArrayEquals(f1.tag, r1.tag);
        assertNull(r1.betVector);
    }
}
