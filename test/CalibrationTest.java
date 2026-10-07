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
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The calibration (Calibration, COUNT_DESIGN.md section 8, step 3): end to end on a small synthetic
 * twin, every section written, the candidates file reading back as the counts scored, a stopped
 * calibration resuming to the same report, and parts of another refused; it refuses the main file
 * before writing anything. And its rules on hand-made figures: the grain chosen, the running
 * divisor, the noise floor's directions at their angles, the colour check's count, the proxy
 * check's chance, the seed-collision check and the log's rate.
 */
public class CalibrationTest {

    private static final PrintStream NOWHERE = nowhere();

    private static PrintStream nowhere() {
        try {
            return new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A full-index run's results.ser of every catalog count on a synthetic main file, and the report printed from it. */
    private static Path[] published(Path dir) throws Exception {
        Path main = ComparisonReportTest.syntheticRun(dir, 81, 81, 16);
        ComparisonReport.Options o = new ComparisonReport.Options();
        o.resamples = CeilingReportTest.RESAMPLES;
        o.threads = 2;
        ComparisonReport.Scored sc = ComparisonReport.score(main, o);
        Path ser = CeilingReportTest.results(dir, main, sc.st, sc.results);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ComparisonReport.print(main, sc, new PrintStream(bytes, true, "UTF-8"));
        Path printed = dir.resolve("report-main.md");
        Files.write(printed, bytes.toByteArray());
        return new Path[]{ser, printed};
    }

    private static Calibration.Settings small(Path twin, Path out, Path ser, Path printed, Path log) {
        Calibration.Settings s = new Calibration.Settings();
        s.states = twin;
        s.out = out;
        s.published = ser;
        s.printed = printed;
        s.twinLog = log;
        s.threads = 2;
        s.searchThreads = 2;
        s.resamples = 4;
        s.subRuns = 2;
        s.directions = 1;
        s.splitStates = 2;
        s.splitDeals = new int[]{Deals.index(8, 8, 10)};
        s.laterShoes = 2;
        s.repeats = 1;
        return s;
    }

    @Test
    public void theCalibrationEndToEndResumesAndRefusesAnothersParts(@TempDir Path dir) throws Exception {
        Path[] pub = published(dir);
        Path twin = ComparisonReportTest.syntheticRun(dir, 82, CustomCounts.TWIN_SEED, 16);
        Path log = dir.resolve("twin.log");
        Files.write(log, Arrays.asList(twin + ": shoes 0 to 15, q0 0.0500, u0 0.020, 2 threads",
                "shoe 7 done: 9 states valued this run, of 600 rounds dealt; 3000 s, 333.33 s per valued state",
                "shoe 15 done: 20 states valued this run, of 1200 rounds dealt; 7200 s, 360.00 s per valued state"));
        Path out = dir.resolve("calibration-test.md");
        Calibration.run(small(twin, out, pub[0], pub[1], log), "test", NOWHERE);

        String report = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
        for (String section : new String[]{"# Calibration and the theory pass", "## Summary", "## The twin file",
                "## The proxy check", "## The grain ladder", "## The noise floor at g = 1/20", "## g",
                "## One evaluation, timed", "## Every published row at g", "## The colour check", "## The theory pass",
                "## The effects of removal off the top", "## The split margin"}) {
            assertTrue(report.contains("\n" + section) || report.startsWith(section), section);
        }
        assertTrue(report.contains("whose stored rows print"), "the tie to the printed report is made");
        assertTrue(report.contains("Over its first hour (" + log + "): 8 shoes, 9 states valued in 3000 s"), report);
        assertTrue(report.contains("0 equal one of the main file's"), "no seed shared with the main file");
        assertTrue(report.contains("a LOWER bound"), "the split margin is called a lower bound");
        assertTrue(report.contains("Hi-Lo at g = 1 keys as Hi-Lo does, so its figures must equal Hi-Lo's own to the "
                + "bit: they do."), "Hi-Lo at g = 1 is Hi-Lo");

        // The candidates file lists the counts scored, to the bit, and the starts and controls.
        Path candidates = dir.resolve("calibration-test.candidates.json");
        CustomCounts.Listing l = CustomCounts.read(candidates);
        int d = (int) Double.parseDouble(value(out, "05-grain", "divisor"));
        int dr = (int) Double.parseDouble(value(out, "05-grain", "runningDivisor"));
        for (CountKeys.Count c : Calibration.noiseCounts(Calibration.DEFAULT_DIVISOR, 1, CustomCounts.TWIN_SEED)) {
            CountKeys.Count x = l.entries.get(l.indexOf(c.name)).count;
            assertArrayEquals(c.tag, x.tag, c.name);
            assertEquals(c.divisor(), x.divisor());
        }
        CountKeys.Count avg = l.entries.get(l.indexOf(Calibration.rankAverage(dr).name)).count;
        assertFalse(avg.trueCounted);
        assertEquals(dr, avg.divisor());
        assertTrue(l.indexOf("betting start") >= 0 && l.indexOf("effects of removal off the top") >= 0);
        // The synthetic states give no move a gain over the chart's, so there is no playing start, and the report says so.
        assertEquals(value(out, "10-theory", "playing") == null, l.indexOf("playing start") < 0);
        assertEquals(d, l.entries.get(l.indexOf("betting start")).count.divisor());
        assertTrue(l.entries.stream().anyMatch(e -> "control".equals(e.role) && e.count.systems.contains("Hi-Lo")),
                "Hi-Lo is a control");

        // Stopped after its parts were all kept, it resumes to the same report, scoring nothing again.
        Path moved = dir.resolve("calibration-first.md");
        Files.move(out, moved);
        Files.move(candidates, dir.resolve("first.candidates.json"));
        ByteArrayOutputStream progress = new ByteArrayOutputStream();
        Calibration.run(small(twin, out, pub[0], pub[1], log), "test", new PrintStream(progress, true, "UTF-8"));
        assertArrayEquals(Files.readAllBytes(moved), Files.readAllBytes(out));
        String said = new String(progress.toByteArray(), StandardCharsets.UTF_8);
        assertFalse(said.contains("started"), said);
        assertTrue(said.contains("done before, read back"), said);

        // A report is never written over, and another calibration's parts are refused.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Calibration.run(small(twin, out, pub[0], pub[1], log), "test", NOWHERE));
        assertTrue(e.getMessage().contains("exists"), e.getMessage());
        Files.move(out, dir.resolve("calibration-second.md"));
        Calibration.Settings other = small(twin, out, pub[0], pub[1], log);
        other.resamples = 5;
        e = assertThrows(IllegalStateException.class, () -> Calibration.run(other, "test", NOWHERE));
        assertTrue(e.getMessage().contains("parts of another calibration"), e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> Calibration.run(small(twin, out, pub[0], pub[1], log),
                "another commit", NOWHERE));
        assertTrue(e.getMessage().contains("parts of another calibration"), e.getMessage());
    }

    private static String value(Path out, String part, String key) throws Exception {
        java.util.Properties p = new java.util.Properties();
        try (java.io.Reader r = Files.newBufferedReader(out.resolveSibling(out.getFileName() + ".parts")
                .resolve(part + ".properties"))) {
            p.load(r);
        }
        return p.getProperty(key);
    }

    @Test
    public void theMainFileAndAnyOtherButTheTwinAreRefusedBeforeAnythingIsWritten(@TempDir Path dir) throws Exception {
        Path main = CustomCountsTest.stateFile(dir, CustomCounts.MAIN_SEED);
        List<String> before = list(dir);
        Calibration.Settings s = small(main, dir.resolve("c.md"), dir.resolve("results.ser"), null, null);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Calibration.run(s, "test", NOWHERE));
        assertTrue(e.getMessage().contains("main state file"), e.getMessage());
        Path other = CustomCountsTest.stateFile(dir, 77);
        List<String> withOther = list(dir);
        Calibration.Settings o = small(other, dir.resolve("c.md"), dir.resolve("results.ser"), null, null);
        e = assertThrows(IllegalStateException.class, () -> Calibration.run(o, "test", NOWHERE));
        assertTrue(e.getMessage().contains("not the twin's"), e.getMessage());
        assertEquals(before.size() + 2, withOther.size(), "the other file and its progress");
        assertEquals(withOther, list(dir));
    }

    private static List<String> list(Path dir) throws Exception {
        List<String> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> out.add(p.getFileName().toString()));
        }
        out.sort(null);
        return out;
    }

    /**
     * g is the coarsest grain past which no finer grain gains more than the floor, for every count:
     * a count still gaining further down holds g finer, and the finest is taken when nothing stops.
     */
    @Test
    public void theGrainIsTheCoarsestPastWhichNeitherCountImproves() {
        double floor = 0.001;
        double[] early = {0.10, 0.12, 0.125, 0.1255, 0.1252, 0.1258};
        double[] late = {0.20, 0.21, 0.22, 0.23, 0.2305, 0.2301};
        assertEquals(2, Calibration.grain(new double[][]{early}, floor));
        assertEquals(3, Calibration.grain(new double[][]{late}, floor));
        assertEquals(3, Calibration.grain(new double[][]{early, late}, floor));
        double[] rising = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6};
        assertEquals(5, Calibration.grain(new double[][]{early, rising}, floor));
        // A rung past which a finer one still gains more than the floor is not where the count stops,
        // though the next rung down is worse: 0.12 is passed by 0.1225.
        double[] dip = {0.10, 0.12, 0.1195, 0.121, 0.1225, 0.122};
        assertEquals(4, Calibration.grain(new double[][]{dip}, floor));
        assertEquals(Calibration.LADDER.length, 8);
        assertEquals("1/20", Calibration.grainText(500));
        assertEquals("1", Calibration.grainText(10_000));
    }

    /**
     * The running divisor makes one running key step g Hi-Lo true-count units at the median depth:
     * there, a balanced count at Hi-Lo's spread keyed running at D_r and true at D gives keys that
     * differ by at most one, and the same key for most states. The median depth is the 1/q-weighted
     * median, worked out here by brute force.
     */
    @Test
    public void theRunningDivisorStepsAsTheTrueCountDoesAtTheMedianDepth() throws Exception {
        assertEquals(2404, Calibration.runningDivisor(500, 166));
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        ScoringStates st = fx.st;
        int cut = SystemScorer.CUTS[Calibration.P80];
        int median = Calibration.medianDepth(st, cut);
        double below = 0;
        double at = 0;
        double total = 0;
        for (int s = 0; s < st.size; s++) {
            if (st.depth[s] < cut) {
                total += st.weight[s];
                below += st.depth[s] < median ? st.weight[s] : 0;
                at += st.depth[s] <= median ? st.weight[s] : 0;
            }
        }
        assertTrue(below < total / 2 && at >= total / 2, "the weighted median");

        int d = 500;
        int depth = 166;
        int dr = Calibration.runningDivisor(d, depth);
        int[] tags = CountKeys.rounded(CountKeys.normalised(CountSearch.rankTags(CountSearch.catalogCount("Zen Count"))),
                CountKeys.K);
        CountKeys.Count running = CountKeys.whole("running", tags, null, dr, false);
        CountKeys.Count trueCount = CountKeys.whole("true", tags, null, d, true);
        Random rnd = new Random(3);
        int same = 0;
        int n = 400;
        for (int k = 0; k < n; k++) {
            int[] dealt = new int[ShoeRun.KINDS];
            for (int c = 0; c < depth; c++) {
                int kind;
                do {
                    kind = rnd.nextInt(ShoeRun.KINDS);
                } while (dealt[kind] >= 2 * ShoeRun.DECKS);
                dealt[kind]++;
            }
            int a = running.betKey(dealt, depth);
            int b = trueCount.betKey(dealt, depth);
            assertTrue(Math.abs(a - b) <= 1, a + " against " + b);
            same += a == b ? 1 : 0;
        }
        assertTrue(same > 0.9 * n, same + " of " + n);
    }

    /** The noise floor's counts: Zen's direction first, then each direction at its angle, as rounded, to within 0.05 degree. */
    @Test
    public void theNoiseFloorsDirectionsLieAtTheirAngles() {
        int n = 3;
        List<CountKeys.Count> c = Calibration.noiseCounts(500, n, 11);
        assertEquals(1 + Calibration.ANGLES.length * n, c.size());
        double zen = CountSearch.cosine(CountSearch.rankTags(c.get(0)), CountSearch.rankTags(CountSearch.catalogCount(
                "Zen Count")));
        assertEquals(1, zen, 1e-6, "the centre is Zen's direction");
        List<String> names = new ArrayList<>();
        for (int a = 0; a < Calibration.ANGLES.length; a++) {
            for (int j = 0; j < n; j++) {
                CountKeys.Count x = c.get(1 + a * n + j);
                assertEquals(Calibration.ANGLES[a], Calibration.angle(c.get(0), x), 0.05, x.name);
                assertTrue(x.trueCounted);
                assertEquals(500, x.divisor());
                assertEquals(0, x.deckSum, "balanced");
                assertFalse(names.contains(x.name));
                names.add(x.name);
            }
        }
        // The same directions at every angle: the 1 and 30 degree counts of a direction lie on one great circle.
        for (int j = 0; j < n; j++) {
            double[] centre = CountSearch.coordinates(CountSearch.rankTags(c.get(0)));
            double[] one = CountSearch.coordinates(CountSearch.rankTags(c.get(1 + j)));
            double[] thirty = CountSearch.coordinates(CountSearch.rankTags(c.get(1 + (Calibration.ANGLES.length - 1) * n + j)));
            double[] u1 = perpendicularPart(one, centre);
            double[] u30 = perpendicularPart(thirty, centre);
            assertEquals(1, cos(u1, u30), 1e-3);
        }
    }

    private static double[] perpendicularPart(double[] x, double[] c) {
        double xc = 0;
        double cc = 0;
        for (int k = 0; k < x.length; k++) {
            xc += x[k] * c[k];
            cc += c[k] * c[k];
        }
        double[] out = new double[x.length];
        for (int k = 0; k < x.length; k++) {
            out[k] = x[k] - xc / cc * c[k];
        }
        return out;
    }

    private static double cos(double[] a, double[] b) {
        double ab = 0;
        double aa = 0;
        double bb = 0;
        for (int k = 0; k < a.length; k++) {
            ab += a[k] * b[k];
            aa += a[k] * a[k];
            bb += b[k] * b[k];
        }
        return ab / Math.sqrt(aa * bb);
    }

    /** The colour check's count is Red Seven at g with the seven's two kinds averaged: colour-blind, running, at D_r. */
    @Test
    public void theColourChecksCountAveragesTheSevenAlone() {
        int dr = 2404;
        CountKeys.Count rs = CountKeys.atGrain(CountSearch.catalogCount("Red Seven (Red 7)"), dr);
        CountKeys.Count avg = Calibration.rankAverage(dr);
        assertTrue(rs.kindAware);
        assertFalse(avg.kindAware);
        assertFalse(avg.trueCounted);
        assertEquals(dr, avg.divisor());
        for (int r = 1; r <= 10; r++) {
            if (r != 7) {
                assertEquals(rs.rankTag(r), avg.rankTag(r), "rank " + r);
            }
        }
        int red = rs.tag[Calibration.kindOf(7, true)];
        int black = rs.tag[Calibration.kindOf(7, false)];
        assertEquals(0, black, "Red Seven tags a black seven 0");
        assertTrue(red > 0);
        assertEquals(Math.rint((red + black) / 2.0), avg.rankTag(7), 0);
    }

    /** The proxy check: z in units of sqrt(2) times the main file's SE, and the binomial chance of so few within 2. */
    @Test
    public void theProxyChecksChanceIsTheBinomialTail() {
        assertEquals(1.0, Calibration.binomialTail(40, 0.05, 0), 1e-12);
        // P(X >= 2) for n = 3, p = 0.1: 3 x 0.01 x 0.9 + 0.001.
        assertEquals(0.028, Calibration.binomialTail(3, 0.1, 2), 1e-12);
        assertEquals(0, Calibration.binomialTail(5, 0.1, 6), 0);
        assertEquals(Math.log(120), Calibration.lgamma(6), 1e-12);
        Calibration.ProxyRow r = new Calibration.ProxyRow("x", 0.003, 0.001, 0.001);
        assertEquals(2 / Math.sqrt(2), r.z(), 1e-12);
        List<Calibration.ProxyRow> rows = new ArrayList<>();
        for (int k = 0; k < 40; k++) {
            rows.add(new Calibration.ProxyRow("r" + k, 0.001 * (k < 2 ? 4 : 1), 0.001, 0.001));
        }
        double[] v = Calibration.proxyVerdict(rows);
        assertEquals(38, v[0], 0);
        assertEquals(Calibration.binomialTail(40, Calibration.OUTSIDE_TWO, 2), v[1], 1e-15);
        assertTrue(v[1] > Calibration.PROXY_ALPHA, "two of forty outside is within chance");
        for (int k = 0; k < 12; k++) {
            rows.set(k, new Calibration.ProxyRow("r" + k, 0.005, 0.001, 0.001));
        }
        assertTrue(Calibration.proxyVerdict(rows)[1] < Calibration.PROXY_ALPHA, "twelve of forty outside is not");
    }

    /** The seed-collision check finds every seed of a file against itself, and none of the twin's among the main file's. */
    @Test
    public void theSeedCollisionCheck() {
        assertEquals(200, CustomCounts.seedCollisions(CustomCounts.MAIN_SEED, CustomCounts.MAIN_SEED, 100));
        assertEquals(0, CustomCounts.seedCollisions(CustomCounts.MAIN_SEED, CustomCounts.TWIN_SEED, 60_000));
    }

    /** The log's rate: the first run's last line within the hour, and its last line; a resumed run's lines are not its. */
    @Test
    public void theRateOverTheFirstHourIsReadFromTheLog() {
        List<String> lines = Arrays.asList("runs/twin-80.states: shoes 0 to 59999, q0 0.0080, u0 0.020, 7 threads",
                "shoe 24 done: 25 states valued this run, of 2550 rounds dealt; 4 s, 0.15 s per valued state",
                "shoe 17699 done: 17958 states valued this run, of 1076148 rounds dealt; 3596 s, 0.20 s per valued state",
                "shoe 17724 done: 17984 states valued this run, of 1077748 rounds dealt; 3601 s, 0.20 s per valued state",
                "shoe 59999 done: 60900 states valued this run, of 3600000 rounds dealt; 12000 s, 0.20 s per valued state",
                "runs/twin-80.states: shoes 60000 to 60999, q0 0.0080, u0 0.020, 7 threads",
                "shoe 60024 done: 25 states valued this run, of 2550 rounds dealt; 4 s, 0.15 s per valued state");
        long[][] r = Calibration.logRate(lines);
        assertArrayEquals(new long[]{17700, 17958, 3596}, r[0]);
        assertArrayEquals(new long[]{60000, 60900, 12000}, r[1]);
    }
}
