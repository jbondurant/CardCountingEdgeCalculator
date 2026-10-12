import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs A and A' and the report of the edge at each count (PLAN_COMPUTE.md 5.3 to 5.5), on
 * synthetic states: run A passes its checks against Results scored as the published ones were and
 * refuses when one stored figure differs in its last bit; run A' passes against the same
 * resamples and refuses a figure moved by more than 1e-12; the report writes JSON that parses, with
 * every row, its refitted figures refitted, the rest widened, the chart not, and a CSV with a line
 * for every key.
 */
public class KeyEdgesRunTest {

    private static final int SHOES = 60;

    private static ScoringStates states(long seed) {
        return SystemScorerTest.prepare(SystemScorerTest.randomStates(new Random(seed), SHOES), SHOES);
    }

    /** What the published file would hold: each count scored as c06c061 scored it, here by these classes. */
    private static List<SystemScorer.Result> stored(ScoringStates st, Bootstrap boot, List<CountKeys.Count> counts) {
        SystemScorer sc = new SystemScorer(st, boot, SystemScorer.M_PLAY);
        List<SystemScorer.Result> out = new ArrayList<>();
        for (CountKeys.Count c : counts) {
            out.add(sc.score(c, CountKeys.bettingVariant(c)));
        }
        return out;
    }

    private static List<CountKeys.Count> some() {
        return Arrays.asList(CountKeys.nullCount(), PlayerTreeOrderTest.named("Hi-Lo"),
                PlayerTreeOrderTest.named("Hi-Opt II"), PlayerTreeOrderTest.named("Red Seven (Red 7)"));
    }

    /**
     * Run A against stored Results scored the same way: no failures, and every check of 1, 3 and
     * 4 made. One stored estimate moved by one bit, or one stored key table's share, and run A
     * refuses, naming the entry.
     */
    @Test
    public void runAChecksEveryEntryAgainstTheStoredOnesToTheBit() {
        ScoringStates st = states(109);
        List<SystemScorer.Result> stored = stored(st, new Bootstrap(SHOES, 2, Bootstrap.SEED), some());
        KeyEdgesRun.Collected ok = KeyEdgesRun.scoreA(st, some(), stored);
        assertEquals(new ArrayList<String>(), ok.failures);
        SystemScorer.Entry e = stored.get(2).entries.get(2);
        e.estimate[1][SystemScorer.same(4)] = Math.nextUp(e.estimate[1][SystemScorer.same(4)]);
        stored.get(1).entries.get(1).keys[2][0][1] *= 1 + 1e-15;
        KeyEdgesRun.Collected bad = KeyEdgesRun.scoreA(st, some(), stored);
        assertEquals(2, bad.failures.size(), bad.failures.toString());
        assertTrue(bad.failures.get(0).startsWith("Hi-Lo = Hi-Lo Lite, betting only at 80%: the key table"),
                bad.failures.get(0));
        assertTrue(bad.failures.get(1).startsWith("Hi-Opt II + ace side count at 75%: the estimate"), bad.failures.get(1));
    }

    /**
     * Run A' against stored Results scored on the same resamples: every figure the same to the
     * bit. A stored resample moved by 1e-11 relative is refused; one moved by 1e-13 is reported
     * as within the standard, with its size.
     */
    @Test
    public void runAPrimeHoldsEveryResampleToTheStoredOnes() {
        ScoringStates st = states(113);
        Bootstrap boot = new Bootstrap(SHOES, 5, Bootstrap.SEED);
        List<CountKeys.Count> five = KeyEdgesRun.refittedCounts();
        List<SystemScorer.Result> stored = stored(st, boot, five);
        int p = KeyEdgesRun.REFIT_PEN;
        KeyEdgesRun.Collected ok = KeyEdgesRun.scoreAPrime(st, boot, five, p, stored, 1);
        assertEquals(new ArrayList<String>(), ok.failures);
        assertEquals("every refitted figure is c06c061's to the bit", ok.notes.get(0));
        double[] r = stored.get(0).entries.get(0).boot[p][SystemScorer.FLAT];
        r[3] *= 1 + 1e-13;
        KeyEdgesRun.Collected near = KeyEdgesRun.scoreAPrime(st, boot, five, p, stored, 1);
        assertEquals(new ArrayList<String>(), near.failures);
        assertTrue(near.notes.get(0).startsWith("1 refitted figures differ"), near.notes.get(0));
        r[3] *= 1 + 1e-11;
        KeyEdgesRun.Collected bad = KeyEdgesRun.scoreAPrime(st, boot, five, p, stored, 1);
        assertEquals(1, bad.failures.size(), bad.failures.toString());
        assertTrue(bad.failures.get(0).startsWith("Hi-Lo = Hi-Lo Lite metric 0 resample 3"), bad.failures.get(0));
    }

    @SuppressWarnings("unchecked")
    private static List<Document> list(Document d, String key) {
        return (List<Document>) d.get(key);
    }

    private static Document layer(Document row, String pen, String layer) {
        for (Document p : list(row, "penetrations")) {
            if (p.getString("penetration").equals(pen)) {
                for (Document l : list(p, "layers")) {
                    if (l.getString("layer").equals(layer)) {
                        return l;
                    }
                }
            }
        }
        return null;
    }

    /**
     * The report end to end on runs A and A' of synthetic states: the JSON parses and holds every
     * row at every penetration; the refitted rows' figures are refitted at 80%, other rows'
     * widened, the chart's and the perfect layers' not; first decisions carry their difference
     * from Hi-Lo; a count at grain is pooled into whole steps and its quarter steps go to the CSV,
     * which has a line for every key; the oracle and the depth bands are there. With a NaN in the
     * perfect array (check 6) no perfect full-play layer is written.
     */
    @Test
    public void theReportWritesEveryRowLayerAndKey(@TempDir Path dir) throws Exception {
        ScoringStates st = states(127);
        int k = CountKeys.K;
        List<CountKeys.Count> rows = new ArrayList<>();
        rows.add(CountKeys.nullCount());
        rows.addAll(KeyEdgesRun.refittedCounts());
        rows.add(PlayerTreeOrderTest.named("Red Seven (Red 7)"));
        rows.add(PlayerTreeOrderTest.named("Wong Halves"));
        rows.add(CountKeys.real("Fine", new double[]{0, -1, 1, 1, 1.5, 1.5, 1, 0.5, 0, -0.5, -1}, null, k / 20));
        Bootstrap boot = new Bootstrap(SHOES, 6, Bootstrap.SEED);
        List<SystemScorer.Result> stored = stored(st, boot, rows);
        KeyEdgesRun.Collected a = KeyEdgesRun.scoreA(st, rows, stored);
        KeyEdgesRun.Collected ap = KeyEdgesRun.scoreAPrime(st, boot, KeyEdgesRun.refittedCounts(),
                KeyEdgesRun.REFIT_PEN, stored, 1);
        assertEquals(new ArrayList<String>(), a.failures);
        assertEquals(new ArrayList<String>(), ap.failures);
        KeyEdgesReport.Inputs in = new KeyEdgesReport.Inputs();
        in.a = a.collector;
        in.aPrime = ap.collector;
        in.boot = boot;
        in.perfect = new double[st.size];
        for (int s = 0; s < st.size; s++) {
            in.perfect[s] = st.perfect[s] + 0.001;
        }
        Path json = dir.resolve("k.json");
        Path csv = dir.resolve("k.csv");
        KeyEdgesReport.report(in, new ArrayList<>(), "test", json, csv, System.out);
        Document d = Document.parse(new String(Files.readAllBytes(json), StandardCharsets.UTF_8));
        assertEquals("test", d.getString("commit"));
        List<Document> out = list(d, "rows");
        assertEquals(11, out.size());
        Document hiLo = null;
        Document red = null;
        Document fine = null;
        for (Document r : out) {
            assertEquals(3, list(r, "penetrations").size(), r.getString("row"));
            hiLo = r.getString("row").startsWith("Hi-Lo") ? r : hiLo;
            red = r.getString("row").startsWith("Red Seven") ? r : red;
            fine = r.getString("row").equals("Fine") ? r : fine;
        }
        Document hl = layer(hiLo, "80%", "first decisions");
        assertEquals("refitted", ((Document) hl.get("flat")).getString("how"));
        assertNotNull(hl.get("line"));
        assertNotNull(hl.get("differenceFromHiLo"));
        Document rs = layer(red, "80%", "first decisions");
        assertTrue(((Document) rs.get("flat")).getString("how").startsWith("widened by a ratio measured on other counts"));
        assertNotNull(((Document) rs.get("pivot")));
        assertEquals(16, ((Document) rs.get("pivot")).getInteger("key"));
        assertEquals("held, not widened: fitted on nothing",
                ((Document) layer(red, "80%", "chart").get("flat")).getString("how"));
        assertNotNull(layer(red, "70%", "perfect full play"));
        assertTrue(fine.getBoolean("rebinned"));
        assertNull(layer(fine, "80%", "first decisions").get("failures"));
        for (Document key : list(layer(fine, "80%", "first decisions"), "keys")) {
            assertEquals(key.getInteger("key").doubleValue(), key.getDouble("bookKey"), 0);
        }
        List<String> lines = Files.readAllLines(csv);
        assertEquals(KeyEdgesCsv.HEADER.trim(), lines.get(0));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("\"Fine\",80%,\"first decisions\",quarter,")));
        int keys = 0;
        for (Document r : out) {
            for (Document p : list(r, "penetrations")) {
                for (Document l : list(p, "layers")) {
                    keys += list(l, "keys").size();
                }
            }
        }
        long quarters = lines.stream().filter(l -> l.contains(",quarter,")).count();
        assertEquals(1 + keys + quarters, lines.size());
        assertEquals(9, list(d, "oracle").size());
        // One simultaneous multiplier per penetration and figure at first decisions, each over the
        // rows other than Hi-Lo, at least the 1.96 of one interval alone.
        List<Document> sim = list(d, "simultaneous");
        assertEquals(3 * (KeyEdges.TOP.length + KeyEdges.BANDS.length + 1), sim.size());
        for (Document m : sim) {
            assertEquals(10, m.getInteger("rows"));
            assertTrue(m.get("multiplier") == null || m.getDouble("multiplier") > 1.5, m.toJson());
        }
        assertFalse(list(d, "depth").isEmpty());

        // A NaN in the perfect array at a state of every penetration: no perfect full-play layer anywhere.
        int s0 = 0;
        while (st.depth[s0] >= SystemScorer.CUTS[0]) {
            s0++;
        }
        in.perfect[s0] = Double.NaN;
        KeyEdgesReport.report(in, new ArrayList<>(), "test", json, csv, System.out);
        Document d2 = Document.parse(new String(Files.readAllBytes(json), StandardCharsets.UTF_8));
        for (Document r : list(d2, "rows")) {
            for (String pen : SystemScorer.PENETRATIONS) {
                assertNull(layer(r, pen, "perfect full play"), r.getString("row"));
            }
        }
        assertEquals(6, list(d2, "oracle").size());
    }

    /**
     * Full play in the report (runs B and D): a full-index fit scored with a collector attached,
     * as the replay will, gives Hi-Lo a full-play layer at the penetration it was fitted at, with
     * refitted first decisions on each resample and no failed check, since its first-decision
     * values are run A's; and a full-play run whose first decisions differ from run A's fails.
     */
    @Test
    public void aFullPlayRunGivesTheFullPlayLayer(@TempDir Path dir) throws Exception {
        FullIndexFitTest.Run run = FullIndexFitTest.capped();
        FullIndexFitTest.Fixture fx = run.fx;
        CountKeys.Count hiLo = FullIndexFitTest.HI_LO;
        Bootstrap boot = new Bootstrap(fx.st.shoes, fx.scorer.boot.resamples, 1);
        List<SystemScorer.Result> stored = new ArrayList<>(run.results.values());
        KeyEdgesRun.Collected a = KeyEdgesRun.scoreA(fx.st, Arrays.asList(hiLo), stored, fx.scorer.mPlay);
        assertEquals(new ArrayList<String>(), a.failures);
        SystemScorer on = new SystemScorer(fx.st, boot, 1);
        KeyEdges.Collector b = new KeyEdges.Collector(fx.st, boot.resamples);
        on.stateSink = b;
        on.resampleSink = b;
        on.score(hiLo, null, run.fits.get(hiLo));
        KeyEdgesReport.Inputs in = new KeyEdgesReport.Inputs();
        in.a = a.collector;
        in.full.add(b);
        in.boot = boot;
        in.perfect = fx.st.perfect.clone();
        Path json = dir.resolve("k.json");
        KeyEdgesReport.report(in, new ArrayList<>(), "test", json, dir.resolve("k.csv"), System.out);
        Document d = Document.parse(new String(Files.readAllBytes(json), StandardCharsets.UTF_8));
        Document row = list(d, "rows").get(0);
        Document full = layer(row, "80%", "full play");
        assertNotNull(full);
        assertNull(full.get("failures"));
        assertEquals(KeyEdges.FULL_REFITTED, ((Document) full.get("flat")).getString("how"),
                "a full-play figure says its later plays are held");
        Document fromHiLo = (Document) full.get("differenceFromHiLo");
        assertTrue(((List<Document>) fromHiLo.get("top")).get(0).getString("how").contains("later plays held"));
        assertNull(layer(row, "70%", "full play"), "fitted at 80% alone");

        // First decisions of the full-play run made otherwise than run A's: the layer fails check 1.
        in.a = KeyEdgesRun.scoreA(fx.st, Arrays.asList(hiLo), stored, fx.scorer.mPlay).collector;
        Object[] f0 = in.a.handed.get(hiLo.name)[2][0];
        ((double[]) f0[2])[0] += 0.5;
        KeyEdgesReport.report(in, new ArrayList<>(), "test", json, dir.resolve("k.csv"), System.out);
        Document d2 = Document.parse(new String(Files.readAllBytes(json), StandardCharsets.UTF_8));
        Document bad = layer(list(d2, "rows").get(0), "80%", "full play");
        assertTrue(bad.get("failures").toString().contains("not run A's"), bad.toJson());
    }
}
