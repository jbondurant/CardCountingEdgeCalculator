import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the searches start from, the convergence evidence and the full-play candidates
 * (COUNT_DESIGN.md, section 4): the noise floor by angle, the side-count pairs F2 starts from, a
 * search's log read back as it was written, the regions runs end in, slices that peak where a
 * smooth objective does and fail where it does not, the full-play candidates where the design puts
 * them, a start not beaten named as a failure, and the full-play choice read from a full-index run.
 */
public class SearchReportTest {

    private static final double[] HI_LO = {0, -1, 1, 1, 1, 1, 1, 0, 0, 0, -1};
    private static final double[] ZEN = {0, -1, 1, 1, 2, 2, 2, 1, 0, 0, -2};
    private static final SearchReport.Floor FLOOR = new SearchReport.Floor(new double[]{1e-5, 2e-5, 3e-5, 4e-5, 8e-5});

    @Test
    public void theFloorIsTakenLinearlyBetweenItsAngles() {
        assertEquals(1e-5, FLOOR.at(0.3), 0);
        assertEquals(1e-5, FLOOR.at(1), 0);
        assertEquals(1.5e-5, FLOOR.at(1.5), 1e-18);
        assertEquals(2.5e-5, FLOOR.at(3.5), 1e-18);
        assertEquals(6e-5, FLOOR.at(20), 1e-18);
        assertEquals(8e-5, FLOOR.at(45), 0);
        assertEquals(FLOOR.at(7), FLOOR.at(-7), 0);
    }

    @Test
    public void theFloorReadsFromTheCalibrationsPart(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("04-noise-500.properties");
        Files.write(p, ("floor.1.0=1.2E-5\nfloor.2.0=1.3E-5\nfloor.5.0=1.5E-5\nfloor.10.0=1.7E-5\nfloor.30.0=4.2E-5\n"
                + "boot.1.0=9\n").getBytes(StandardCharsets.UTF_8));
        SearchReport.Floor f = SearchReport.Floor.read(p);
        assertArrayEquals(new double[]{1.2e-5, 1.3e-5, 1.5e-5, 1.7e-5, 4.2e-5}, f.at, 0);
        Files.write(p, "floor.1.0=1.2E-5\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> SearchReport.Floor.read(p));
    }

    /** A side-count pair bets on its count's tags with an ace tag of -m, m the ten's tag, as CountKeys.sideCounted sets it. */
    @Test
    public void aSideCountPairBetsWithAnAceTagOfMinusM() {
        CountKeys.Count hiOpt2 = CountSearch.catalogCount("Hi-Opt II");
        double[] t = CountSearch.rankTags(hiOpt2);
        double[] bet = SearchReport.withAceSideCount(t);
        assertEquals(-Math.abs(t[10]), bet[1], 0);
        assertEquals(-2, bet[1], 0);
        for (int r = 2; r <= 10; r++) {
            assertEquals(t[r], bet[r], 0);
        }
        assertEquals(CountKeys.sideCountVariant(hiOpt2).aceSideCount, (int) -bet[1]);
        assertThrows(IllegalArgumentException.class, () -> SearchReport.withAceSideCount(HI_LO));
        // Every catalog side-count pair is among F2's starts, with (u, u) first and the flat optimum's pair last.
        List<SearchReport.Pair> starts = SearchReport.f2Starts(HI_LO, ZEN, HI_LO);
        assertEquals(1 + CountKeys.SIDE_COUNTED.size(), starts.size() - 1);
        assertSame(starts.get(0).play, starts.get(0).bet);
        assertSame(ZEN, starts.get(starts.size() - 1).play);
    }

    /** F1r's starts from F1's optimum keep its balanced part and carry k times KO's shift relative to it. */
    @Test
    public void f1rsStartsCarryAShareOfKosShift() {
        double[] ko = CountSearch.rankTags(CountSearch.catalogCount("KO (Knock-Out)"));
        assertEquals(1 / 13.0 / Math.sqrt(CountKeys.spread(ko)), SearchReport.koShift(), 1e-15);
        double[] f1 = {0, -6694, 6353, 7604, 10986, 12732, 7640, 5972, 1195, -2264, -10881};
        double[][] r = SearchReport.f1rStarts(f1, 0.5, 1.5);
        for (int j = 0; j < 2; j++) {
            assertEquals(1, CountSearch.cosine(r[j], f1), 1e-12, "the same balanced part");
            assertEquals((j == 0 ? 0.5 : 1.5) * SearchReport.koShift(), CountKeys.mean(r[j]) / Math.sqrt(CountKeys.spread(r[j])),
                    1e-12);
        }
        // KO itself carries a shift of one KO's.
        assertEquals(SearchReport.koShift(), CountKeys.mean(ko) / Math.sqrt(CountKeys.spread(ko)), 0);
    }

    private static CountSearch.Settings settings(String form, int maxEvals, String run) {
        CountSearch.Settings s = new CountSearch.Settings();
        s.form = form;
        s.divisor = CountKeys.K / 10;
        s.maxEvals = maxEvals;
        s.minAngle = Math.toRadians(0.5);
        s.threads = 2;
        s.seed = 3;
        s.run = run;
        return s;
    }

    /** Runs a search as CountSearch's command does, its log headed as the command heads it, and reads the log back. */
    private static SearchReport.Run search(SystemScorer sc, Path dir, CountSearch.Settings s, int half, double mPlay,
                                           double[] start, double[] startBet) throws Exception {
        Path file = dir.resolve(s.run + ".log");
        String head = CountSearch.header("c", "states x", CustomCounts.TWIN_SEED, half, 1200, mPlay, s, start, startBet);
        CountKeys.Count found;
        try (CountSearch.SearchLog log = new CountSearch.SearchLog(file, head, false)) {
            found = CountSearch.search(sc, s, start, startBet, log, false, null);
        }
        SearchReport.Run r = SearchReport.read(file);
        assertTrue(r.done);
        assertEquals(s.run, r.name);
        assertArrayEquals(found.tag, CountKeys.byKind(r.play));
        return r;
    }

    @Test
    public void aSearchsLogReadsBackAsItWasWritten(@TempDir Path dir) throws Exception {
        double[] hidden = {0, -0.6, 0.5, 0.7, 0.9, 1.2, 0.8, 0.4, 0, -0.3, -0.9};
        ScoringStates st = CountSearchTest.planted(hidden, hidden, 1);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, 0, 1), 1);

        SearchReport.Run f1 = search(sc, dir, settings("F1", 96, "f1-hi-lo"), -1, SystemScorer.M_PLAY, HI_LO, null);
        assertEquals("F1", f1.family());
        assertEquals(-1, f1.half);
        assertEquals(SystemScorer.M_PLAY, f1.mPlay, 0);
        assertEquals(12, f1.lambda);
        assertEquals(96, f1.maxEvals);
        assertEquals(CountKeys.K / 10, f1.divisor);
        assertArrayEquals(HI_LO, f1.start, 0);
        assertNull(f1.startBet);
        assertNull(f1.bet);
        assertTrue(f1.evaluations > 0 && f1.evaluations <= 96);

        CountSearch.Settings flat = settings("F1", 48, "f1-flat");
        flat.metric = SystemScorer.FLAT;
        assertEquals("F1 flat", search(sc, dir, flat, -1, SystemScorer.M_PLAY, ZEN, null).family());

        SearchReport.Run h = search(sc, dir, settings("F1", 48, "f1-half"), 1, SearchReport.HALF_M_PLAY, ZEN, null);
        assertEquals("F1 half 1", h.family());
        assertEquals(SearchReport.HALF_M_PLAY, h.mPlay, 0, "5 sqrt 2 reads back to the bit");

        CountSearch.Settings f2s = settings("F2", 84, "f2-pair");
        SearchReport.Run f2 = search(sc, dir, f2s, -1, SystemScorer.M_PLAY, HI_LO, ZEN);
        assertEquals("F2", f2.family());
        assertArrayEquals(ZEN, f2.startBet, 0);
        assertNotNull(f2.bet);
        assertFalse(Double.isNaN(f2.startValue), "F2's start, as its log notes it");
        assertEquals(f2.startValue, SearchReport.startValue(f2, sc), 0);
        assertEquals(f2.startValue, SearchReport.f2Value(sc, f2s.divisor, HI_LO, ZEN), 0,
                "F2's start scored as the search scores it");

        CountSearch.Settings f1r = settings("F1r", 48, "f1r-ko");
        f1r.divisor = 3 * CountKeys.K / 10;
        assertEquals("F1r", search(sc, dir, f1r, -1, SystemScorer.M_PLAY,
                CountSearch.rankTags(CountSearch.catalogCount("KO (Knock-Out)")), null).family());

        // A start, scored as the search scores candidates, is the objective at the start's point.
        CountSearch.Settings s = SearchReport.settings("F1", SystemScorer.same(SystemScorer.SPREADS), CountKeys.K / 10);
        assertEquals(CountSearch.f1(sc, s).of(SearchReport.point(HI_LO)), SearchReport.startValue(f1, sc), 0);
        assertEquals(SearchReport.score(sc, HI_LO, CountKeys.K / 10)[0], SearchReport.startValue(f1, sc), 0);

        List<SearchReport.Run> all = SearchReport.runs(dir);
        assertEquals(5, all.size());
        assertEquals(1, SearchReport.family(all, "F1").size());
    }

    /** A run of tags by rank at an angle from Hi-Lo, toward a direction perpendicular to it. */
    private static SearchReport.Run at(String name, double degrees, double[] toward, double value) {
        double[] x = SearchReport.point(HI_LO);
        double[] u = Calibration.perpendicular(x, 1, Arrays.hashCode(toward))[0];
        double a = Math.toRadians(degrees);
        double[] z = new double[9];
        for (int k = 0; k < 9; k++) {
            z[k] = Math.cos(a) * x[k] + Math.sin(a) * u[k];
        }
        SearchReport.Run r = new SearchReport.Run();
        r.name = name;
        r.form = "F1";
        r.play = CountSearch.whole(z, Calibration.sign());
        r.value = value;
        r.done = true;
        return r;
    }

    @Test
    public void runsWithinAFewDegreesAreOneRegion() {
        double[] one = {1};
        double[] two = {2};
        SearchReport.Run best = at("best", 0, one, 0.004);
        SearchReport.Run near = at("near", 2, one, 0.004 - 1e-5);
        SearchReport.Run nearer = at("next", 4, one, 0.004 - 3e-5);
        SearchReport.Run far = at("far", 20, two, 0.004 - 2e-4);
        assertEquals(2, SearchReport.degrees(best.play, near.play), 0.01);
        assertTrue(SearchReport.agrees(near, best, FLOOR), "within the floor at 2 degrees, and within 5");
        assertFalse(SearchReport.agrees(nearer, best, FLOOR), "3e-5 below at 4 degrees, where the floor is 2.67e-5");
        assertFalse(SearchReport.agrees(far, best, FLOOR));

        List<List<SearchReport.Run>> regions = SearchReport.regions(Arrays.asList(far, nearer, best, near));
        assertEquals(2, regions.size());
        assertEquals(Arrays.asList(best, near, nearer), regions.get(0), "joined link by link, best first");
        assertEquals(Arrays.asList(far), regions.get(1));
        assertTrue(SearchReport.separate(regions, FLOOR), "2e-4 below at 20 degrees, beyond the floor");
        far.value = 0.004 - 5e-5;
        assertFalse(SearchReport.separate(SearchReport.regions(Arrays.asList(far, best)), FLOOR), "within the floor at 20");
    }

    /** A run that did not agree is credited when the run that followed it up, from its count at double the population and cap, agrees. */
    @Test
    public void aFollowUpIsTheRunFromThisOnesCountAtDoubleThePopulation() {
        SearchReport.Run best = at("best", 0, new double[]{1}, 0.004);
        SearchReport.Run stalled = at("stalled", 2, new double[]{1}, 0.004 - 3e-5);
        stalled.lambda = 12;
        stalled.maxEvals = 1500;
        SearchReport.Run up = at("up", 1, new double[]{1}, 0.004 - 1e-6);
        up.lambda = 24;
        up.maxEvals = 3000;
        up.start = new double[11];
        for (int r = 1; r <= 10; r++) {
            up.start[r] = stalled.play[r];
        }
        best.lambda = 12;
        best.maxEvals = 1500;
        best.start = new double[11];
        assertFalse(SearchReport.agrees(stalled, best, FLOOR));
        assertSame(up, SearchReport.followUp(stalled, Arrays.asList(best, stalled, up)));
        assertTrue(SearchReport.agrees(up, best, FLOOR));
        assertNull(SearchReport.followUp(best, Arrays.asList(best, stalled, up)));
        up.maxEvals = 1500;
        assertNull(SearchReport.followUp(stalled, Arrays.asList(best, stalled, up)), "the cap doubled too");
    }

    @Test
    public void theGrainCheckIsTwoSidedAtHalfAndDoubleAndOneSidedFiner() {
        double v = 0.0039;
        // 2D, D/2, D/5, D/10.
        assertTrue(SearchReport.grainTwoSided(new double[]{v - 5e-6, v + 5e-6, v, v}, v, FLOOR));
        assertFalse(SearchReport.grainTwoSided(new double[]{v - 3e-5, v, v, v}, v, FLOOR), "the coarser grain loses");
        assertTrue(SearchReport.grainFinerGains(new double[]{v - 3e-5, v - 3e-5, v - 3e-5, v}, v, FLOOR),
                "losing at a finer grain is not gaining");
        assertFalse(SearchReport.grainFinerGains(new double[]{v, v, v + 2e-5, v}, v, FLOOR));
        assertTrue(SearchReport.grainFinerGains(new double[]{v + 5e-5, v, v, v}, v, FLOOR), "a coarser grain is not finer");
    }

    /** An objective smooth on the sphere: c times the square of the angle to a hidden direction, in radians. */
    private static CountSearch.Objective bowl(double[] hidden, double c) {
        double[] h = SearchReport.point(hidden);
        return x -> {
            double xh = 0;
            double xx = 0;
            double hh = 0;
            for (int k = 0; k < 9; k++) {
                xh += x[k] * h[k];
                xx += x[k] * x[k];
                hh += h[k] * h[k];
            }
            double a = Math.acos(Math.max(-1, Math.min(1, xh / Math.sqrt(xx * hh))));
            return 0.004 + c * a * a;
        };
    }

    @Test
    public void slicesPeakAtTheTopOfASmoothObjectiveAndNotBesideIt() throws Exception {
        double c = -0.1;
        CountSearch.Objective f = bowl(ZEN, c);
        List<SearchReport.Direction> toward = Arrays.asList(new SearchReport.Direction("Hi-Lo", HI_LO));
        double[][] axes = {{1, 0, 0, 0, 0, 0, 0, 0, 0}};
        List<SearchReport.Slice> at = SearchReport.slices(f, SearchReport.point(ZEN), axes, toward, 2);
        assertEquals(9 + 1 + 1, at.size());
        for (SearchReport.Slice s : at) {
            assertTrue(s.peaks(FLOOR), s.direction);
            assertEquals(0, s.peakDegrees(), 0.05, s.direction);
            assertEquals(c, s.quadratic[2], 0.02 * -c, s.direction);
            // Within sqrt(floor / -c) the loss is under the floor: 0.57 degrees at the floor at 1.
            assertEquals(Math.toDegrees(Math.sqrt(1e-5 / -c)), s.indistinguishable(FLOOR), 0.05);
            assertEquals(0, s.bestPoint()[0], 1e-12, "no point beats the centre");
        }
        // Four degrees off the top, toward Hi-Lo: the slice toward Hi-Lo peaks four degrees away,
        // with headroom c (4 degrees)^2 = 4.9e-4, far over the floor, and is named.
        double[] off = new double[9];
        double[] z = SearchReport.point(ZEN);
        double[] u = SearchReport.point(HI_LO);
        double zu = 0;
        for (int k = 0; k < 9; k++) {
            zu += z[k] * u[k];
        }
        double[] d = new double[9];
        for (int k = 0; k < 9; k++) {
            d[k] = u[k] - zu / CountKeys.SPREAD * z[k];
        }
        d = CountSearch.onSphere(d);
        double a = Math.toRadians(-4);
        for (int k = 0; k < 9; k++) {
            off[k] = Math.cos(a) * z[k] + Math.sin(a) * d[k];
        }
        List<SearchReport.Slice> beside = SearchReport.slices(f, off, axes, toward, 2);
        SearchReport.Slice toHiLo = beside.get(beside.size() - 1);
        assertEquals("toward Hi-Lo", toHiLo.direction);
        assertFalse(toHiLo.peaks(FLOOR));
        assertEquals(4, toHiLo.peakDegrees(), 0.1);
        assertEquals(-c * Math.pow(Math.toRadians(4), 2), toHiLo.quadratic[4], 2e-5);

        SearchReport.Evidence e = new SearchReport.Evidence();
        e.floor = FLOOR;
        e.slices8 = beside;
        for (String fam : SearchReport.FAMILIES) {
            e.families.put(fam, new ArrayList<>());
        }
        SearchReport.verdicts(e);
        assertTrue(e.failures.stream().anyMatch(s -> s.contains("slice along toward Hi-Lo")), e.failures.toString());
    }

    @Test
    public void theFullPlayCandidatesLieWhereTheDesignPutsThem(@TempDir Path dir) throws Exception {
        int[] f1 = CountSearch.whole(SearchReport.point(ZEN), Calibration.sign());
        int[] flat = CountSearch.whole(SearchReport.point(HI_LO), Calibration.sign());
        double[] later = {0, 0.4, 0.3, 0.7, 1.2, 1.8, -0.5, 0.5, 0.1, -0.5, -1};
        int[] bet = CountSearch.whole(SearchReport.point(later), Calibration.sign());
        List<SearchReport.Candidate> cs = SearchReport.fullPlay(f1, flat, later, f1, bet, 500);
        assertEquals(8, cs.size());
        assertSame(f1, cs.get(0).play, "the 1-8 optimum as found");
        assertSame(flat, cs.get(4).play, "the flat optimum as found");
        double whole = SearchReport.degrees(f1, flat);
        for (int k = 1; k <= 3; k++) {
            double a = SearchReport.degrees(f1, cs.get(k).play);
            assertEquals(whole * k / 4, a, 0.25, "on the line, " + k + "/4 of the way");
            assertEquals(whole, a + SearchReport.degrees(cs.get(k).play, flat), 0.05, "between the ends");
        }
        assertEquals(10, SearchReport.degrees(f1, cs.get(5).play), 0.05);
        assertEquals(20, SearchReport.degrees(f1, cs.get(6).play), 0.05);
        assertTrue(SearchReport.degrees(cs.get(6).play, bet) < SearchReport.degrees(cs.get(5).play, bet)
                && SearchReport.degrees(cs.get(5).play, bet) < SearchReport.degrees(f1, bet), "turned toward later play");
        assertArrayEquals(bet, cs.get(7).bet);

        Path file = dir.resolve("fullplay.json");
        SearchReport.writeCandidates(file, cs, "a test");
        CustomCounts.Listing l = CustomCounts.read(file);
        assertEquals(8, l.entries.size());
        assertArrayEquals(CountKeys.byKind(cs.get(5).play), l.entries.get(5).count.tag);
        assertArrayEquals(CountKeys.byKind(bet), l.entries.get(7).count.betVector);
        assertEquals(500, l.entries.get(2).count.divisor());
        assertThrows(IllegalStateException.class, () -> SearchReport.writeCandidates(file, cs, "again"));
    }

    /** On planted states, the evidence of two F1 runs: every start beaten; a run made to end below its start is named. */
    @Test
    public void aStartNotBeatenIsAFailure(@TempDir Path dir) throws Exception {
        double[] hidden = {0, -0.6, 0.5, 0.7, 0.9, 1.2, 0.8, 0.4, 0, -0.3, -0.9};
        ScoringStates st = CountSearchTest.planted(hidden, hidden, 1);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, 0, 1), SystemScorer.M_PLAY);
        search(sc, dir, settings("F1", 240, "from-hi-lo"), -1, SystemScorer.M_PLAY, HI_LO, null);
        search(sc, dir, settings("F1", 240, "from-zen"), -1, SystemScorer.M_PLAY, ZEN, null);
        List<SearchReport.Run> runs = SearchReport.runs(dir);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        SearchReport.Evidence e = SearchReport.evidence(st, runs, FLOOR, CountKeys.K / 10, 3 * CountKeys.K / 10, HI_LO, 2,
                new PrintStream(bytes, true, "UTF-8"));
        List<SearchReport.Run> f1 = e.families.get("F1");
        assertEquals(2, f1.size());
        assertTrue(f1.get(0).value >= f1.get(1).value);
        for (SearchReport.Run r : f1) {
            assertTrue(r.value >= e.startValue.get(r), r.name + " beats its start");
        }
        assertEquals(9 + 9 + SearchReport.TOP, e.slices8.size(), "the basis, the run's axes and the top published");
        assertNull(e.slicesFlat);
        assertTrue(e.failures.stream().noneMatch(s -> s.contains("below the start")), e.failures.toString());
        e.eor = new double[]{0, -0.93, 0.71, 0.84, 1.12, 1.32, 0.87, 0.46, -0.08, -0.41, -0.97};
        String text = SearchReport.reportText(e, "c", Path.of("states"), dir, null, 0);
        assertTrue(text.contains("| Rank | F1 | Effects of removal | "), text);
        assertTrue(text.contains("| Angle from F1, degrees | 0.0 | "), text);
        for (String section : new String[]{"## Summary", "## The tags", "## Every published count's direction", "## 1. Restarts agree",
                "### F1\n", "## 2. Slices at F1's optimum", "## 3. Every start is beaten", "## 5. The grain does not decide it"}) {
            assertTrue(text.contains(section), section);
        }

        // The queue's clock and the run's own timer side by side: a run that slept through two
        // hours of the queue's clock but ran 7 s awake shows both, each summed for the family.
        Path queue = dir.resolve("queue.txt");
        Files.write(queue, Arrays.asList("10:00:00 start from-zen", "12:00:00 end from-zen exit 0"), StandardCharsets.UTF_8);
        Files.write(dir.resolve("from-zen.out"), Arrays.asList("# header", "Found from-zen: true count in 7 s."),
                StandardCharsets.UTF_8);
        String timed = SearchReport.reportText(e, "c", Path.of("states"), dir, queue, 0);
        assertTrue(timed.contains("| Evaluations | Wall s | Awake s | Agrees |"), timed);
        SearchReport.Run zen = f1.get(0).name.equals("from-zen") ? f1.get(0) : f1.get(1);
        assertTrue(timed.contains("| " + zen.evaluations + " | 7200 | 7 | "), timed);
        assertTrue(timed.contains("evaluations, 7200 s on the wall by the queue's clock, 7 s awake by the runs' own."),
                timed);

        // The best run made to end below its own start: a failure, named.
        SearchReport.Run best = f1.get(0);
        best.value = e.startValue.get(best) - 1e-6;
        for (SearchReport.Run r : f1) {
            r.value = Math.min(r.value, best.value);
        }
        e.failures.clear();
        SearchReport.verdicts(e);
        assertTrue(e.failures.stream().anyMatch(s -> s.startsWith("F1: the optimum") && s.contains(best.name)),
                e.failures.toString());
    }

    /** The full-play choice reads a full-index run's results: each count's full-play 1-8, F2 by its F2 row, the best F1 chosen. */
    @Test
    public void theFullPlayChoiceIsTheBestF1ByFullPlay(@TempDir Path dir) throws Exception {
        Path file = ComparisonReportTest.syntheticRun(dir, 79, 79, 12);
        Path counts = CustomCountsTest.write(dir, "fullplay.json", "{ \"kind\": \"candidates\", \"K\": 10000, \"counts\": [\n"
                + "  { \"name\": \"one\", \"family\": \"F1 full play\", \"form\": \"true\", \"divisor\": 2500,\n"
                + "    \"tags\": [-10000, 10000, 10000, 10000, 10000, 10000, 0, 0, 0, -10000] },\n"
                + "  { \"name\": \"two\", \"family\": \"F1 full play\", \"form\": \"true\", \"divisor\": 2500,\n"
                + "    \"tags\": [0, 10000, 10000, 20000, 20000, 10000, 10000, 0, 0, -20000] },\n"
                + "  { \"name\": \"pair\", \"family\": \"F2 full play\", \"form\": \"true\", \"divisor\": 2500,\n"
                + "    \"tags\": [0, 10000, 10000, 20000, 20000, 10000, 10000, 0, 0, -20000],\n"
                + "    \"betting\": [-20000, 10000, 10000, 20000, 20000, 10000, 10000, 0, 0, -20000] } ] }");
        PrintStream log = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        FullIndexRun.run(new String[]{"--states", file.toString(), "--out", dir.resolve("run").toString(), "--counts",
                counts.toString(), "--pens", "2", "--resamples", "0", "--threads", "2", "--trees", "none"}, "test", log, 0);
        assertFalse(Files.exists(dir.resolve("run").resolve("trees.bin")), "no tree store");
        List<SystemScorer.Result> rs = CeilingReport.read(dir.resolve("run").resolve("results.ser")).results;
        String text = SearchReport.fullplayText(rs, dir);
        int s8 = SystemScorer.same(SystemScorer.SPREADS);
        double one = rs.get(0).fullEntries.get(0).estimate[2][s8];
        double two = rs.get(1).fullEntries.get(0).estimate[2][s8];
        String chosen = one >= two ? "one" : "two";
        assertTrue(text.contains("The full-play F1, the best of the F1 counts by full-play 1-8 EV: " + chosen + ", "
                + SearchReport.pct(Math.max(one, two))), text);
        assertTrue(text.contains("| pair + betting vector | " + SearchReport.pct(rs.get(2).entries.get(2).estimate[2][s8])
                + " | " + SearchReport.pct(rs.get(2).fullEntries.get(1).estimate[2][s8])), text);
    }
}
