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
 * plays, F2 must find both and F1 land between them; and F1r keeps its balanced part on the
 * sphere, as F1 does.
 */
public class CountSearchTest {

    private static final int STAND = 0;
    private static final int DOUBLE = 2;
    private static final int SHOES = 1200;
    private static final double[] HI_LO = {0, -1, 1, 1, 1, 1, 1, 0, 0, 0, -1};
    /** The Ten Count's tags: a betting start some 43 degrees from F2's planted betting direction. */
    private static final double[] TEN_COUNT = {0, 1, 1, 1, 1, 1, 1, 1, 1, 1, -2.25};

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
     * each (cosine at least 0.98), the bets from the Ten Count, 43 degrees off, and F1, one vector
     * for both, lands between them. F2's joint polish runs (it spends evaluations), F2 ends at or
     * above its start and every block's end in its log, and at or above the planted pair less a
     * hundredth of a point.
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
        Path f2Log = dir.resolve("f2.tsv");
        try (CountSearch.SearchLog log = new CountSearch.SearchLog(f2Log, "# test", false)) {
            f2 = CountSearch.search(sc, s2, HI_LO, TEN_COUNT, log, false, null);
        }
        assertTrue(CountSearch.cosine(TEN_COUNT, bet) < 0.75, "the betting start is far from the planted bets");
        double cp = CountSearch.cosine(playTags(f2), play);
        double cb = CountSearch.cosine(betTags(f2), bet);
        assertTrue(cp >= 0.98, "the play vector is at cosine " + cp);
        assertTrue(cb >= 0.98, "the betting vector is at cosine " + cb);

        double found = sc.estimate(f2, CountKeys.bettingVariant(f2), s2.pen)[2][s2.metric];
        List<String> lines = Files.readAllLines(f2Log);
        long jointEvals = lines.stream().filter(l -> l.startsWith("eval\tf2/joint\t")).count();
        assertTrue(jointEvals > 0, "the joint polish spent no evaluations");
        int finals = 0;
        for (String l : lines) {
            String[] f = l.split("\t", -1);
            if (f[0].equals("final") && f[1].startsWith("f2/")) {
                finals++;
                assertTrue(found >= Double.parseDouble(f[2]), "F2 ends at " + found + ", below " + f[1] + "'s end " + f[2]);
            }
            if (f[0].equals("note") && f[1].equals("f2") && f[2].startsWith("start ")) {
                double start = Double.parseDouble(f[2].split(" ")[1]);
                assertTrue(found >= start, "F2 ends at " + found + ", below its start " + start);
                finals++;
            }
        }
        assertTrue(finals >= 1 + 2 + 1, "the start, a cycle's two blocks at least, and the joint polish: " + finals);
        CountKeys.Count planted = CountKeys.whole("planted", CountSearch.whole(CountSearch.onSphere(
                CountSearch.coordinates(play)), s2.sign), CountSearch.whole(CountSearch.onSphere(
                CountSearch.coordinates(bet)), s2.sign), s2.divisor, true);
        double atPlanted = sc.estimate(planted, CountKeys.bettingVariant(planted), s2.pen)[2][s2.metric];
        assertTrue(found >= atPlanted - 1e-4, "F2 ends at " + found + ", the planted pair scores " + atPlanted);

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

    /**
     * F1r, the running form, from KO's direction and balance: every generation's mean has its
     * balanced part on the sphere of Hi-Lo's spread, the shift scaled with it, so its step is an
     * angle and the least angle stops it as it stops F1; and it ends above KO.
     */
    @Test
    public void f1rKeepsItsBalancedPartOnTheSphere() throws Exception {
        double[] hidden = {0, -0.6, 0.5, 0.7, 0.9, 1.2, 0.8, 0.4, 0, -0.3, -0.9};
        ScoringStates st = planted(hidden, hidden, 1);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(SHOES, 0, 1), 1);
        Path dir = Files.createTempDirectory("search");
        CountSearch.Settings s = settings("F1r", 600);
        s.divisor = 3 * CountKeys.K / 10;
        s.minAngle = Math.toRadians(1);
        s.run = "f1r";
        double[] ko = CountSearch.rankTags(CountSearch.catalogCount("KO (Knock-Out)"));
        Path file = dir.resolve("f1r.tsv");
        CountKeys.Count found;
        try (CountSearch.SearchLog log = new CountSearch.SearchLog(file, "# test", false)) {
            found = CountSearch.search(sc, s, ko, null, log, false, null);
        }
        assertFalse(found.trueCounted);
        int states = 0;
        double lastStep = Double.NaN;
        for (String l : Files.readAllLines(file)) {
            String[] f = l.split("\t", -1);
            if (!f[0].equals("state")) {
                continue;
            }
            states++;
            String[] x = f[5].split(",");
            assertEquals(10, x.length);
            double n = 0;
            for (int k = 0; k < 9; k++) {
                n += Math.pow(Double.parseDouble(x[k]), 2);
            }
            assertEquals(CountSearch.RADIUS, Math.sqrt(n), 1e-9, "generation " + f[2] + "'s balanced part on the sphere");
            lastStep = Double.parseDouble(f[4]) / CountSearch.RADIUS;
        }
        assertTrue(states > 0);
        int evals = Integer.parseInt(CountSearch.SearchLog.finalOf(file, "f1r")[2]);
        assertTrue(evals + s.lambda > s.maxEvals || lastStep < s.minAngle, "it stopped by its budget or its angle");
        CountKeys.Count start = CountKeys.whole("KO", CountSearch.wholeRunning(startCoordinates(ko), s.sign), null,
                s.divisor, false);
        double v = sc.estimate(found, null, s.pen)[0][s.metric];
        double v0 = sc.estimate(start, null, s.pen)[0][s.metric];
        assertTrue(v > v0, "F1r " + v + " beats its start " + v0);
    }

    /** F1r's ten coordinates of a start's tags, as the search makes them: its balanced part's nine and its shift. */
    private static double[] startCoordinates(double[] tags) {
        double[] x = java.util.Arrays.copyOf(CountSearch.coordinates(tags), 10);
        x[9] = CountKeys.mean(tags);
        return x;
    }

    /** A search's log names its sign reference, so a resume with another one is refused. */
    @Test
    public void aResumeWithAnotherSignIsRefused() throws Exception {
        CountSearch.Settings s = settings("F1", 720);
        String hiLo = CountSearch.header("c", "states x", 20261007L, -1, 100, 1, s, HI_LO, null);
        s.sign = new double[]{0, -2, 1, 1, 2, 2, 2, 1, 0, 0, -2};
        String zen = CountSearch.header("c", "states x", 20261007L, -1, 100, 1, s, HI_LO, null);
        assertTrue(hiLo.contains("sign Hi-Lo"), hiLo);
        assertNotEquals(hiLo, zen);
        Path file = Files.createTempDirectory("sign").resolve("log.tsv");
        try (CountSearch.SearchLog log = new CountSearch.SearchLog(file, zen, false)) {
            log.note("r", "begun");
        }
        assertThrows(IllegalStateException.class, () -> new CountSearch.SearchLog(file, hiLo, true));
        new CountSearch.SearchLog(file, zen, true).close();
    }
}
