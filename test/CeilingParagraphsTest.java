import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ceiling count's result paragraphs and the rule that picks one (CeilingParagraphs), on
 * fixture Results built by hand, so each outcome is set rather than drawn: the committed file
 * parses into three paragraphs and three flat sentences with the brackets it was written with,
 * and no other version of it is read; the rule picks A, B or C as COUNT_DESIGN.md section 5 says;
 * F2 is judged by its row with the betting vector, never its play vector alone; and no paragraph is
 * printed with a bracket unfilled.
 */
public class CeilingParagraphsTest {

    static final int R = 40;
    static final int P = 2;
    static final int S8 = SystemScorer.same(SystemScorer.SPREADS);

    /** The committed paragraphs, read from git at the commit that holds them. */
    static byte[] committed() throws Exception {
        Process git = new ProcessBuilder("git", "show", CeilingParagraphs.COMMIT + ":" + CeilingParagraphs.FILE)
                .directory(Paths.get("").toAbsolutePath().toFile()).start();
        byte[] out;
        try (InputStream in = git.getInputStream()) {
            out = in.readAllBytes();
        }
        assertEquals(0, git.waitFor(), "git show " + CeilingParagraphs.COMMIT);
        return out;
    }

    @Test
    public void theCommittedFileParsesAndNoOtherVersionIsRead() throws Exception {
        byte[] bytes = committed();
        CeilingParagraphs.Texts t = CeilingParagraphs.parse(bytes, CeilingParagraphs.BLOB);
        assertEquals(Arrays.asList("ROW", "EV", "GAIN", "BEST", "LOWER", "PERFECT"),
                CeilingParagraphs.placeholders(t.paragraphs.get('A')));
        assertEquals(Arrays.asList("ROW", "EV", "BEST", "BEST_EV", "LOWER", "OPTIMISM", "PERFECT"),
                CeilingParagraphs.placeholders(t.paragraphs.get('B')));
        assertEquals(Arrays.asList("ROW", "EV", "BEST_EV", "BEST", "TWIN_GAIN", "SHORTFALL", "PERFECT"),
                CeilingParagraphs.placeholders(t.paragraphs.get('C')));
        assertEquals(Arrays.asList("LOWER"), CeilingParagraphs.placeholders(t.flat.get('A')));
        assertEquals(Arrays.asList("GAIN"), CeilingParagraphs.placeholders(t.flat.get('B')));
        assertEquals(Arrays.asList("BEST_FLAT"), CeilingParagraphs.placeholders(t.flat.get('C')));
        assertTrue(t.paragraphs.get('A').startsWith("A computer search for the best count of this kind"));
        assertTrue(t.flat.get('A').endsWith("by at least [LOWER]."), t.flat.get('A'));
        for (String p : t.paragraphs.values()) {
            assertFalse(p.contains(">") || p.contains("\n"), p);
        }

        // One word changed after the look: another blob, refused.
        byte[] edited = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replace("a little above",
                "well above").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CeilingParagraphs.parse(edited, CeilingParagraphs.BLOB));
        assertTrue(e.getMessage().contains("not " + CeilingParagraphs.BLOB), e.getMessage());
    }

    // ------------------------------------------------------------------ fixture Results

    /** A registration: F1, F1 flat and F2 (with a betting vector) the headline counts, and the two half counts. */
    static final String REGISTRATION = "{ \"kind\": \"registered\", \"K\": 10000, \"twin\": \"twin, seed 20261007\", "
            + "\"searchCommit\": \"abc\", \"cells\": {\"flat\": [\"F1 flat\"], \"same\": [\"F1\", \"F2\"], "
            + "\"own\": [\"F1\", \"F2\"]}, \"counts\": [\n"
            + count("F1", "F1", "headline", "") + ",\n"
            + count("F1 flat", "F1 flat", "headline", "") + ",\n"
            + count("F2", "F2", "headline", ", \"betting\": [-20000, 10000, 10000, 20000, 20000, 10000, 10000, 0, 0, "
            + "-20000]") + ",\n"
            + count("F1 half 0 count", "F1 half 0", "check", "") + ",\n"
            + count("F1 half 1 count", "F1 half 1", "check", "") + " ] }";

    private static String count(String name, String family, String role, String more) {
        return "{ \"name\": \"" + name + "\", \"family\": \"" + family + "\", \"role\": \"" + role + "\", \"form\": "
                + "\"true\", \"divisor\": 500, \"tags\": [-10000, 10000, 10000, 10000, 10000, 10000, 0, 0, 0, -10000]"
                + more + " }";
    }

    /** The luck every row shares on resample r. */
    static double shared(int r) {
        return 0.002 * Math.sin(1.3 * r);
    }

    /**
     * An entry at level v in every cell at 80%, its resamples v plus the shared luck plus its own
     * noise, +a on even resamples and -a on odd: so its difference from a row of no noise has the
     * 95% interval [d - a, d + a] exactly.
     */
    static SystemScorer.Entry entry(String name, CountKeys.Count count, boolean bettingOnly, double v, double a) {
        SystemScorer.Entry e = new SystemScorer.Entry(name, count, bettingOnly);
        e.estimate[P] = new double[SystemScorer.METRICS];
        e.boot[P] = new double[SystemScorer.METRICS][R];
        Arrays.fill(e.estimate[P], v);
        for (int m = 0; m < SystemScorer.METRICS; m++) {
            for (int r = 0; r < R; r++) {
                e.boot[P][m][r] = v + shared(r) + (r % 2 == 0 ? a : -a);
            }
        }
        return e;
    }

    /** The levels a fixture sets: each registered row's and its noise, and the play vector of F2 alone. */
    static final class Levels {
        double f1 = 0.0036;
        double f1Noise;
        double f1Flat = 0.0036;
        double f1FlatNoise;
        double f2Bet = 0.0039;
        double f2BetNoise;
        double f2Play = 0.0030;
        double halves = 0.0036;
    }

    /** Published levels: Hi-Lo 0.0029, Zen 0.0036 and Hi-Opt II 0.0034 plain, Hi-Opt II with its ace side count 0.0040. */
    static final double HI_LO = 0.0029;
    static final double ZEN = 0.0036;
    static final double HI_OPT = 0.0034;
    static final double HI_OPT_ACE = 0.0040;
    static final double PERFECT = 0.0053;
    /** A Ceiling as CeilingResults builds one from the saved files: the registration, its rows, the stored rows, the bound. */
    static CeilingReport.Ceiling ceiling(Path dir, Levels l) throws Exception {
        CeilingReport.Ceiling c = new CeilingReport.Ceiling();
        c.counts = CustomCounts.read(CustomCountsTest.write(dir, "registration.json", REGISTRATION));
        c.published = new CeilingReport.Published();
        c.published.results = new ArrayList<>();
        SystemScorer.Result none = new SystemScorer.Result(CountKeys.nullCount());
        none.entries.add(entry("Null count (no count)", none.count, false, -0.0099, 0));
        CountKeys.Count zenCount = CountSearch.catalogCount("Zen Count");
        SystemScorer.Result zen = new SystemScorer.Result(zenCount);
        zen.entries.add(entry("Zen Count", zenCount, false, ZEN, 0));
        zen.entries.add(entry("Zen Count, betting only", zenCount, true, 0.0020, 0));
        CountKeys.Count hiOpt = CountSearch.catalogCount("Hi-Opt II");
        CountKeys.Count ace = CountKeys.bettingVariant(hiOpt);
        assertNotNull(ace.base, "the side-count variant is not a plain row");
        SystemScorer.Result hi = new SystemScorer.Result(hiOpt);
        hi.entries.add(entry("Hi-Opt II", hiOpt, false, HI_OPT, 0));
        hi.entries.add(entry("Hi-Opt II, betting only", hiOpt, true, 0.0019, 0));
        hi.entries.add(entry("Hi-Opt II + ace side count", ace, false, HI_OPT_ACE, 0));
        hi.entries.add(entry("Hi-Opt II + ace side count, betting only", ace, true, 0.0021, 0));
        CountKeys.Count hiLoCount = CountSearch.catalogCount("Hi-Lo");
        SystemScorer.Result hiLo = new SystemScorer.Result(hiLoCount);
        hiLo.entries.add(entry(hiLoCount.name, hiLoCount, false, HI_LO, 0));
        hiLo.entries.add(entry(hiLoCount.name + ", betting only", hiLoCount, true, 0.0018, 0));
        c.published.results.addAll(Arrays.asList(none, hiLo, zen, hi));
        double[][] own = {{l.f1, l.f1Noise}, {l.f1Flat, l.f1FlatNoise}, {l.f2Play, 0}, {l.halves, 0}, {l.halves, 0}};
        for (int k = 0; k < c.counts.entries.size(); k++) {
            CountKeys.Count x = c.counts.entries.get(k).count;
            SystemScorer.Result r = new SystemScorer.Result(x);
            r.entries.add(entry(x.name, x, false, own[k][0], own[k][1]));
            r.entries.add(entry(x.name + ", betting only", x, true, 0.0020, 0));
            if (x.betVector != null) {
                CountKeys.Count bet = CountKeys.bettingVariant(x);
                r.entries.add(entry(x.name + " + betting vector", bet, false, l.f2Bet, l.f2BetNoise));
            }
            c.custom.add(r);
        }
        c.bounds = new ArrayList<>(Arrays.asList(entry("Perfect first move and insurance, bet on its own value", null,
                false, PERFECT, 0)));
        return c;
    }

    @Test
    public void theClaimHoldingForOneRowPicksA(@TempDir Path dir) throws Exception {
        Levels l = new Levels();
        l.f1 = ZEN + 0.0005;
        l.f1Flat = ZEN + 0.0002;
        l.f1FlatNoise = 0.0005;
        CeilingReport.Ceiling c = ceiling(dir, l);
        CeilingParagraphs.Choice same = CeilingParagraphs.choose(c, "same", P);
        assertEquals('A', same.paragraph);
        assertEquals(Arrays.asList("F1"), same.holdsFor);
        assertEquals("F1", same.family);
        assertEquals("Zen Count", same.names.get("BEST"), "F1's best is the best plain row, not the side-counted one");
        assertEquals(0.0005, same.figures.get("LOWER"), 1e-12);
        assertEquals(0.0005, same.figures.get("GAIN"), 1e-12);
        assertEquals(PERFECT, same.figures.get("PERFECT"), 0);
        String a = CeilingParagraphs.render(CeilingParagraphs.parse(committed(), CeilingParagraphs.BLOB), same, null);
        assertTrue(a.contains("F1 scores +0.410% of a unit a round at 80% penetration with a 1-8 spread, +0.050% more "
                + "than the best published row, Zen Count, and every published count by at least +0.050%"), a);
        assertTrue(a.endsWith("would earn +0.530%. The count's tags are not meant for a person to keep; they show how "
                + "much better a count of ten numbers can do at this game, not a system to play."), a);
        assertFalse(a.contains("["), a);

        // The flat cell: F1 flat above Zen by 0.0002, but its interval reaches 0.0003 below: B.
        CeilingParagraphs.Choice flat = CeilingParagraphs.choose(c, "flat", P);
        assertEquals('B', flat.paragraph);
        assertEquals("F1 flat", flat.family);
        assertEquals(-0.0003, flat.figures.get("LOWER"), 1e-12);
        String b = CeilingParagraphs.render(CeilingParagraphs.parse(committed(), CeilingParagraphs.BLOB), flat, null);
        assertEquals("Flat-betting, the searched count scores +0.020% above the best published count, too little to "
                + "claim.", b);
    }

    @Test
    public void aPointGainWithoutTheClaimPicksBAndNamesTheHigherRow(@TempDir Path dir) throws Exception {
        Levels l = new Levels();
        l.f1 = ZEN + 0.0002;
        l.f1Noise = 0.0005;
        l.f2Bet = HI_OPT_ACE + 0.0001;
        l.f2BetNoise = 0.0005;
        l.f1Flat = ZEN - 0.0001;
        CeilingReport.Ceiling c = ceiling(dir, l);
        CeilingParagraphs.Choice same = CeilingParagraphs.choose(c, "same", P);
        assertEquals('B', same.paragraph);
        assertTrue(same.holdsFor.isEmpty());
        assertEquals(Arrays.asList("F1", "F2"), same.qualifying);
        assertEquals("F2", same.family, "both are above their best; F2 scores higher");
        assertEquals("Hi-Opt II + ace side count", same.names.get("BEST"));
        // F2's smallest lower end is against Hi-Opt II with its ace side count: 0.0001 - 0.0005.
        assertEquals(-0.0004, same.figures.get("LOWER"), 1e-12);
        CeilingReport.Claim f2 = CeilingReport.claim(CeilingReport.row(c.custom.get(2)), CeilingReport.comparators(c,
                c.custom.get(2)), P, S8);
        assertEquals(f2.gain, same.figures.get("LOWER"), 0);
        CeilingParagraphs.Texts t = CeilingParagraphs.parse(committed(), CeilingParagraphs.BLOB);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CeilingParagraphs.render(t, same, null));
        assertTrue(e.getMessage().contains("OPTIMISM"), e.getMessage());
        Map<String, Double> more = new HashMap<>();
        more.put("OPTIMISM", 0.0002);
        String b = CeilingParagraphs.render(t, same, more);
        assertTrue(b.contains("(F2 at +0.410% against Hi-Opt II + ace side count at +0.400%), but not by enough to "
                + "claim it: the 95% interval of the difference reaches down to -0.040%."), b);
        assertTrue(b.contains("The search found +0.020% more on the shoes it was tuned on"), b);

        CeilingParagraphs.Choice flat = CeilingParagraphs.choose(c, "flat", P);
        assertEquals('C', flat.paragraph);
        assertEquals("Flat-betting, the searched count does not beat the best published count, Zen Count.",
                CeilingParagraphs.render(t, flat, null));
    }

    /**
     * F2's play vector alone scores far above every published row, its row with the betting vector
     * below the best: the rule reads the betting row (CeilingReport.row), so no gain, C. Judging F2
     * by its play vector, the trap FullIndexRun's summary once fell into, would pick A.
     */
    @Test
    public void noGainPicksCAndF2IsJudgedByItsBettingRow(@TempDir Path dir) throws Exception {
        Levels l = new Levels();
        l.f1 = ZEN - 0.0001;
        l.f2Bet = HI_OPT_ACE - 0.0002;
        l.f2Play = 0.0100;
        l.halves = ZEN - 0.0001 - 0.00002;
        CeilingReport.Ceiling c = ceiling(dir, l);
        CeilingParagraphs.Choice same = CeilingParagraphs.choose(c, "same", P);
        assertEquals('C', same.paragraph, "F2 judged by its play vector alone");
        assertTrue(same.holdsFor.isEmpty());
        assertEquals("F2", same.family, "F2 at +0.380% scores above F1 at +0.350%");
        assertEquals(HI_OPT_ACE - 0.0002, same.figures.get("EV"), 1e-15);
        assertEquals(0.00002, same.figures.get("SHORTFALL"), 1e-12);
        CeilingParagraphs.Texts t = CeilingParagraphs.parse(committed(), CeilingParagraphs.BLOB);
        Map<String, Double> more = new HashMap<>();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CeilingParagraphs.render(t, same, more));
        assertTrue(e.getMessage().contains("TWIN_GAIN"), e.getMessage());
        more.put("TWIN_GAIN", 0.0001);
        String text = CeilingParagraphs.render(t, same, more);
        assertTrue(text.contains("its best, F2, scores +0.380% a round against +0.400% for Hi-Opt II + ace side count. "
                + "The gain it found on the shoes it was tuned on, +0.010%, did not hold up"), text);
        assertTrue(text.contains("(+0.002% here)"), text);
    }

    /** A point estimate equal to the best published row's is "at or below" it: C, not B. */
    @Test
    public void aTieWithTheBestIsNoGain(@TempDir Path dir) throws Exception {
        Levels l = new Levels();
        l.f1 = ZEN;
        l.f1Noise = 0.0003;
        l.f2Bet = HI_OPT_ACE;
        CeilingReport.Ceiling c = ceiling(dir, l);
        assertEquals('C', CeilingParagraphs.choose(c, "same", P).paragraph);
    }
}
