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
 * CeilingResults on fixtures: hand-built Results for the full-play ladder, the room, the claims
 * and the checks, so each figure is known; and a real CeilingReport run on a synthetic state file,
 * saved and printed, for the check that the saved figures give the report's lines.
 */
public class CeilingResultsTest {

    static final int P = CeilingParagraphsTest.P;
    static final int S8 = CeilingParagraphsTest.S8;
    static final String IDENTITY = "FullIndexRun joint run\ncommit c06c061\nstates /x.states, 1 bytes, modified 1\n"
            + "shoes 10, valued states 40\nresamples 40, seed 20260929, M_play 5.0\n";

    /** The full-play registration: F1 and F2 its headline counts, F1 alone flat. */
    static final String FULL_REGISTRATION = "{ \"kind\": \"registered\", \"K\": 10000, \"twin\": \"twin, seed 20261007\", "
            + "\"searchCommit\": \"abc\", \"registeredWith\": \"custom-counts.json\", \"cells\": {\"flat\": [\"F1\"], "
            + "\"same\": [\"F1\", \"F2\"], \"own\": [\"F1\", \"F2\"]}, \"counts\": [\n"
            + "{ \"name\": \"F1\", \"family\": \"F1\", \"role\": \"headline\", \"form\": \"true\", \"divisor\": 500, "
            + "\"tags\": [-10000, 10000, 10000, 10000, 10000, 10000, 0, 0, 0, -10000] },\n"
            + "{ \"name\": \"F2\", \"family\": \"F2\", \"role\": \"headline\", \"form\": \"true\", \"divisor\": 500, "
            + "\"tags\": [-10000, 10000, 10000, 10000, 10000, 10000, 0, 0, 0, -10000], \"betting\": [-20000, 10000, "
            + "10000, 20000, 20000, 10000, 10000, 0, 0, -20000] },\n"
            + "{ \"name\": \"Hi-Lo = Hi-Lo Lite\", \"role\": \"check\", \"catalog\": \"Hi-Lo\" } ] }";

    /** Full-play levels: each published row's first-decision level plus this; F1, F2's betting row and F2's play vector. */
    static final double FULL_GAIN = 0.0008;
    static final double F1_FULL = 0.0045;
    static final double F2_FULL = 0.0047;
    static final double F2_PLAY_FULL = 0.0200;
    static final double F1_OWN_FULL = 0.0050;
    static final double BOUND_FULL = 0.0061;

    static SystemScorer.Entry full(String name, CountKeys.Count count, double v, double a) {
        SystemScorer.Entry first = CeilingParagraphsTest.entry(name, count, false, v, a);
        SystemScorer.Entry e = new SystemScorer.Entry(name, count, false, true);
        copy(first, e);
        return e;
    }

    static void copy(SystemScorer.Entry from, SystemScorer.Entry to) {
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            to.estimate[p] = from.estimate[p];
            to.boot[p] = from.boot[p];
        }
    }

    /** The first-decision fixture of CeilingParagraphsTest, each published row given a full-play entry. */
    static CeilingReport.Ceiling withFullPlay(Path dir) throws Exception {
        CeilingParagraphsTest.Levels l = new CeilingParagraphsTest.Levels();
        CeilingReport.Ceiling c = CeilingParagraphsTest.ceiling(dir, l);
        c.published.identity = IDENTITY;
        for (SystemScorer.Result r : c.published.results) {
            for (SystemScorer.Entry e : r.entries) {
                if (!e.bettingOnly) {
                    r.fullEntries.add(full(e.name + ", full play", e.count, e.estimate[P][S8] + FULL_GAIN, 0));
                }
            }
        }
        SystemScorer.Entry bound = c.bounds.get(0);
        c.bounds.add(CeilingParagraphsTest.entry("Chart play and perfect insurance, bet on its own value", null, false,
                0.0020, 0));
        SystemScorer.Entry b = new SystemScorer.Entry("Perfect play of the dealt hand and insurance, bet on its own value",
                null, false, true, true);
        copy(CeilingParagraphsTest.entry("x", null, false, BOUND_FULL, 0), b);
        for (int p = 0; p < P; p++) {
            b.estimate[p] = b.estimate[P];
            b.boot[p] = b.boot[P];
        }
        c.bounds.add(b);
        assertSame(bound, c.bounds.get(0));
        return c;
    }

    /** Step 8's run of the full-play registration, as the fixture sets it. */
    static CeilingResults.Full fullContext(Path dir, CeilingReport.Ceiling c) throws Exception {
        CustomCounts.Listing counts = CustomCounts.read(CustomCountsTest.write(dir, "fullplay.json", FULL_REGISTRATION));
        CeilingReport.Published run = new CeilingReport.Published();
        run.identity = IDENTITY.replace("c06c061", "step8");
        run.results = new ArrayList<>();
        for (CustomCounts.Entry e : counts.entries) {
            CountKeys.Count x = e.count;
            if (!x.systems.isEmpty()) {
                run.results.add(c.published.of(x.systems.get(0)));
                continue;
            }
            SystemScorer.Result r = new SystemScorer.Result(x);
            r.entries.add(CeilingParagraphsTest.entry(x.name, x, false, 0.0036, 0));
            r.entries.add(CeilingParagraphsTest.entry(x.name + ", betting only", x, true, 0.0020, 0));
            boolean f2 = x.betVector != null;
            r.fullEntries.add(full(x.name + ", full play", x, f2 ? F2_PLAY_FULL : F1_FULL, f2 ? 0 : 0.0002));
            if (f2) {
                CountKeys.Count bet = CountKeys.bettingVariant(x);
                r.entries.add(CeilingParagraphsTest.entry(bet.name, bet, false, 0.0039, 0));
                r.entries.add(CeilingParagraphsTest.entry(bet.name + ", betting only", bet, true, 0.0020, 0));
                r.fullEntries.add(full(bet.name + ", full play", bet, F2_FULL, 0.0002));
            }
            SystemScorer.Entry own = new SystemScorer.Entry(x.name + ", full play, bet on its own value", x, false, true,
                    true);
            copy(CeilingParagraphsTest.entry("x", null, false, F1_OWN_FULL, 0.0001), own);
            r.fullEntries.add(own);
            run.results.add(r);
        }
        return CeilingResults.fullPlay(c.published, run, counts, c.bounds);
    }

    /**
     * The full-play ladder at 80%, 1-8: the rungs in the page's order, each full-play level widened
     * by 1.07 (no count here was measured by the pilot) and each bound by nothing; F2 by its row with
     * the betting vector, though its play vector alone scores far above; each gap paired with the
     * rung below.
     */
    @Test
    public void theFullPlayLadderWidensRowsAndNotBounds(@TempDir Path dir) throws Exception {
        CeilingReport.Ceiling c = withFullPlay(dir);
        CeilingResults.Full f = fullContext(dir, c);
        List<CeilingResults.Rung> ladder = CeilingResults.ladder(c, f, P, S8);
        List<String> roles = new ArrayList<>();
        for (CeilingResults.Rung r : ladder) {
            roles.add(r.role);
        }
        assertEquals(Arrays.asList("null", "hilo", "best-plain", "best-any", "F1", "F2", "F1-own-value", "bound-first",
                "bound-full"), roles);
        assertEquals("Zen Count, full play", ladder.get(2).entry.name);
        assertEquals("Hi-Opt II + ace side count, full play", ladder.get(3).entry.name);
        assertEquals(F2_FULL, ladder.get(5).entry.estimate[P][S8], 0, "F2's row with its betting vector, not its play vector");
        CeilingResults.Rung f1 = ladder.get(4);
        assertEquals(CeilingResults.DIFFERENCE, f1.ratio);
        double se = Bootstrap.interval(F1_FULL, f1.entry.boot[P][S8]).se;
        assertEquals(CeilingResults.DIFFERENCE * se, f1.level.se, 1e-15);
        assertEquals(1, ladder.get(8).ratio, "a bound is fitted on nothing and not widened");
        Bootstrap.Interval gap = SystemScorer.difference(f1.entry, ladder.get(3).entry, P, S8, CeilingResults.DIFFERENCE);
        assertEquals(gap.lo, f1.gap.lo, 0);
        // First decisions: no full-play bound, and nothing widened.
        List<CeilingResults.Rung> fd = CeilingResults.ladder(c, null, P, S8);
        assertEquals("bound-first", fd.get(fd.size() - 1).role);
        for (CeilingResults.Rung r : fd) {
            assertEquals(1, r.ratio, r.role);
        }
        // The flat view names F1 flat at first decisions and F1 at full play, with no own-value rung.
        List<String> flat = new ArrayList<>();
        for (CeilingResults.Rung r : CeilingResults.ladder(c, f, P, SystemScorer.FLAT)) {
            flat.add(r.role);
        }
        assertEquals(Arrays.asList("null", "hilo", "best-plain", "best-any", "F1", "bound-first", "bound-full"), flat);
        assertEquals("F1 flat", CeilingResults.ladder(c, null, P, SystemScorer.FLAT).get(4).role);
    }

    /**
     * The room at full play is taken against the full-play bound, never the first move's: a
     * full-play row may pass the first move's bound, and its share would read as more than all of
     * the room. With M added the share is smaller. F1's gap splits into betting and play, adding up.
     */
    @Test
    public void theFullPlayRoomIsTakenAgainstTheFullPlayBound(@TempDir Path dir) throws Exception {
        CeilingReport.Ceiling c = withFullPlay(dir);
        CeilingResults.Full f = fullContext(dir, c);
        assertSame(f.bounds.get(2), CeilingResults.roomBound(c, f), "the full-play bound, not the first move's");
        assertSame(c.bounds.get(0), CeilingResults.roomBound(c, null));
        SystemScorer.Entry none = c.published.nullCount().fullEntries.get(0);
        SystemScorer.Entry f1 = CeilingResults.fullRow(f.run.results.get(0));
        double[] share = CeilingResults.share(f1, none, CeilingResults.roomBound(c, f), P, S8, 0);
        double none80 = none.estimate[P][S8];
        assertEquals((F1_FULL - none80) / (BOUND_FULL - none80), share[0], 1e-15);
        assertTrue(share[1] <= share[0] && share[0] <= share[2]);
        double[] withM = CeilingResults.share(f1, none, CeilingResults.roomBound(c, f), P, S8, 0.0007);
        assertTrue(withM[0] < share[0]);
        Bootstrap.Interval[] split = CeilingResults.gapSplit(f1, CeilingResults.fullOwnValue(f.run.results.get(0)),
                f.bounds.get(2), P, S8, CeilingResults.DIFFERENCE);
        assertEquals(split[2].estimate, split[0].estimate + split[1].estimate, 1e-15);
        assertEquals(F1_OWN_FULL - F1_FULL, split[0].estimate, 1e-15);
    }

    /**
     * The full-play claim: F2's row with its betting vector against every published full-play row,
     * each difference widened by 1.07; judged by its play vector alone (fullEntries[0]) it would hold.
     */
    @Test
    public void theFullPlayClaimIsWidenedAndReadsF2sBettingRow(@TempDir Path dir) throws Exception {
        CeilingReport.Ceiling c = withFullPlay(dir);
        CeilingResults.Full f = fullContext(dir, c);
        CeilingReport.Claim f2 = CeilingResults.fullClaim(f, 1, P, S8);
        assertEquals(F2_FULL, f2.row.estimate[P][S8], 0);
        assertEquals(CeilingResults.fullPublished(c.published, true).size(), f2.against.size());
        assertEquals("Hi-Opt II + ace side count, full play", f2.best.name);
        assertFalse(f2.holds(), "below Hi-Opt II with its ace side count at full play");
        double[] d = new double[CeilingParagraphsTest.R];
        for (int r = 0; r < d.length; r++) {
            d[r] = f2.row.boot[P][S8][r] - f2.best.boot[P][S8][r];
        }
        Bootstrap.Interval widened = Bootstrap.interval(f2.row.estimate[P][S8] - f2.best.estimate[P][S8],
                Bootstrap.widen(d, 1.07));
        assertEquals(widened.lo, f2.fromBest.lo, 1e-15);
        CeilingReport.Claim play = CeilingReport.claim(f.run.results.get(1).fullEntries.get(0),
                CeilingResults.fullPublished(c.published, true), P, S8);
        assertTrue(play.holds(), "the trap: the play vector alone would be claimed");
        f.measured.put(f2.best.count.name, 1.30);
        assertTrue(CeilingResults.fullClaim(f, 1, P, S8).fromBest.lo < f2.fromBest.lo,
                "a comparator measured wider widens the pair by its ratio, the larger");
    }

    /** Checks 2 to 5 on the fixture, each made to fail by one planted figure. */
    @Test
    public void theChecksCatchAPlantedFigure(@TempDir Path dir) throws Exception {
        CeilingReport.Ceiling c = withFullPlay(dir);
        CeilingResults.Full f = fullContext(dir, c);
        // 2. The perfect array twice: equal, then one state moved by a part in a billion.
        double[] a = {-0.5, 0.1, Double.NaN, 0.02};
        double[] b = a.clone();
        double[] same = CeilingResults.samePerfect(a, b);
        assertEquals(3, same[0]);
        assertEquals(1, same[1]);
        b[1] *= 1 + 1e-9;
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CeilingResults.samePerfect(a, b));
        assertTrue(e.getMessage().contains("state 1"), e.getMessage());
        b[1] = a[1] * (1 + 1e-14);
        CeilingResults.samePerfect(a, b);
        // 3. Hi-Lo at full play in two runs: equal, then one resample moved.
        SystemScorer.Result hiLo = c.published.of("Hi-Lo");
        assertNull(CeilingResults.sameFullPlay(hiLo, hiLo));
        SystemScorer.Result moved = new SystemScorer.Result(hiLo.count);
        SystemScorer.Entry copy = full("Hi-Lo, full play", hiLo.count, hiLo.fullEntries.get(0).estimate[P][S8], 0);
        moved.fullEntries.add(copy);
        assertNull(CeilingResults.sameFullPlay(hiLo, moved));
        copy.boot[P] = copy.boot[P].clone();
        copy.boot[P][S8] = copy.boot[P][S8].clone();
        copy.boot[P][S8][7] += 1e-9;
        assertTrue(CeilingResults.sameFullPlay(hiLo, moved).contains("resample 7"));
        // 4. The full-play bound under the first move's on one resample of 75%, flat.
        SystemScorer.Entry first = c.bounds.get(0);
        for (int p = 0; p < 2; p++) {
            first.estimate[p] = first.estimate[P];
            first.boot[p] = first.boot[P];
            f.bounds.get(2).estimate[p] = f.bounds.get(2).estimate[P];
            f.bounds.get(2).boot[p] = f.bounds.get(2).boot[P];
        }
        assertTrue(CeilingResults.boundOrder(first, f.bounds.get(2)).isEmpty());
        SystemScorer.Entry low = new SystemScorer.Entry("full", null, false, true, true);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            low.estimate[p] = f.bounds.get(2).estimate[P];
            low.boot[p] = new double[SystemScorer.METRICS][];
            for (int m = 0; m < SystemScorer.METRICS; m++) {
                low.boot[p][m] = f.bounds.get(2).boot[P][m].clone();
            }
        }
        low.boot[1][SystemScorer.FLAT][3] = first.boot[1][SystemScorer.FLAT][3] - 0.001;
        assertEquals(Arrays.asList("75%, flat"), CeilingResults.boundOrder(first, low));
        assertEquals(Arrays.asList(2), CeilingResults.perStateOrder(new double[]{0.1, 0.2, 0.29}, new double[]{0.1,
                0.1, 0.3}));
        // 5. Rows under B + M: F2's play vector alone (+0.0200) is above B (+0.0061) plus any margin here.
        double[][] margin = new double[SystemScorer.CUTS.length][SystemScorer.SPREADS + 1];
        for (double[] row : margin) {
            Arrays.fill(row, 0.0005);
        }
        List<SystemScorer.Entry> rows = new ArrayList<>(Arrays.asList(CeilingResults.fullRow(f.run.results.get(0)),
                CeilingResults.fullRow(f.run.results.get(1))));
        assertTrue(CeilingResults.aboveFullBound(rows, f.bounds.get(2), margin).isEmpty());
        rows.add(f.run.results.get(1).fullEntries.get(0));
        List<String> over = CeilingResults.aboveFullBound(rows, f.bounds.get(2), margin);
        assertFalse(over.isEmpty());
        assertTrue(over.contains("F2, full play at 80%, 1-8"), over.toString());
        assertEquals(over, CeilingResults.aboveB(rows.subList(2, 3), f.bounds.get(2)));
    }

    /** Runs that do not pair, a run of other counts, and bound rows without the full-play bound are refused. */
    @Test
    public void runsThatDoNotPairAreRefused(@TempDir Path dir) throws Exception {
        CeilingReport.Ceiling c = withFullPlay(dir);
        CeilingResults.Full f = fullContext(dir, c);
        CeilingReport.Published other = new CeilingReport.Published();
        other.identity = IDENTITY.replace("resamples 40", "resamples 41");
        other.results = f.run.results;
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CeilingResults.fullPlay(c.published,
                other, f.counts, c.bounds));
        assertTrue(e.getMessage().contains("do not pair"), e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> CeilingResults.fullPlay(c.published, f.run, f.counts,
                c.bounds.subList(0, 2)));
        assertTrue(e.getMessage().contains("no full-play bound"), e.getMessage());
        f.run.results.add(f.run.results.remove(0));
        e = assertThrows(IllegalStateException.class, () -> CeilingResults.fullPlay(c.published, f.run, f.counts,
                c.bounds));
        assertTrue(e.getMessage().contains("the registration's F1"), e.getMessage());
    }

    /**
     * Check 1 on a real run: CeilingReport scores a synthetic file, saves and prints; the context
     * built from the stored rows, the save and the counts file alone prints the ladder and the
     * claims again as the report has them. A saved figure moved, F1's 1-8 resamples spread wider at
     * 80%, gives other printed lines, and the check refuses.
     */
    @Test
    public void theSavedFiguresGiveTheReportsLines(@TempDir Path dir) throws Exception {
        Path file = ComparisonReportTest.syntheticRun(dir, 74, 74, 16);
        ScoringStates st = FullIndexPilot.load(file, StateStore.shoesDone(file));
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, CeilingReportTest.RESAMPLES, Bootstrap.SEED),
                SystemScorer.M_PLAY);
        Path ser = CeilingReportTest.results(dir, file, st, CeilingReportTest.published(sc));
        Path counts = CustomCountsTest.write(dir, "candidates.json", CeilingReportTest.withCells(CustomCountsTest.CELLS));
        Path save = dir.resolve("ceiling.ser");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CeilingReport.run(file, ser, null, counts, save, CeilingReportTest.RESAMPLES, 1, "test",
                new PrintStream(bytes, true, "UTF-8"));
        Path report = dir.resolve("report.md");
        Files.write(report, bytes.toByteArray());

        CeilingReport.Published stored = CeilingReport.read(ser);
        CeilingReport.Saved saved = CeilingReport.readSaved(save);
        CeilingReport.Ceiling c = CeilingResults.firstDecisions(stored, saved, CustomCounts.read(counts));
        CeilingResults.checkPrinted(c, report);
        assertEquals(2, CeilingResults.sections(Files.readAllLines(report), "## The ladder").size());

        SystemScorer.Entry f1 = c.custom.get(0).entries.get(0);
        double[] b = f1.boot[P][S8];
        double mean = ComparisonReport.mean(b);
        for (int r = 0; r < b.length; r++) {
            b[r] = mean + 1.5 * (b[r] - mean);
        }
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CeilingResults.checkPrinted(c, report));
        assertTrue(e.getMessage().contains("| F1 start |"), e.getMessage());

        // A save made beside other stored rows, or of other counts, is refused.
        stored.identity = stored.identity.replace("commit test", "commit other");
        e = assertThrows(IllegalStateException.class, () -> CeilingResults.firstDecisions(stored, saved,
                CustomCounts.read(counts)));
        assertTrue(e.getMessage().contains("stored rows of test"), e.getMessage());
    }

    /** The stored rows set beside a peek: as printed, they pass; one digit changed, refused, naming the row. */
    @Test
    public void theStoredRowsGiveThePeek(@TempDir Path dir) throws Exception {
        CeilingReport.Ceiling c = withFullPlay(dir);
        StringBuilder peek = new StringBuilder("FullIndexPeek: fixture\n\n| Count | First decisions | Full play | Gain | its "
                + "held SE |\n|---|---|---|---|---|\n");
        for (SystemScorer.Result r : c.published.results) {
            String name = r.count.systems.isEmpty() ? "Null count (no count)" : r.count.name;
            peek.append(String.format(java.util.Locale.ROOT, "| %s | %+.4f | %+.4f | +0.0000 | 0.0001 |%n", name,
                    100 * r.entries.get(0).estimate[P][S8], 100 * r.fullEntries.get(0).estimate[P][S8]));
        }
        peek.append("\nflat table follows\n| Count | x |\n");
        Path file = dir.resolve("peek.txt");
        Files.write(file, peek.toString().getBytes(StandardCharsets.UTF_8));
        CeilingResults.checkPeek(c.published, file);
        String zen = String.format(java.util.Locale.ROOT, "%+.4f", 100 * (CeilingParagraphsTest.ZEN + FULL_GAIN));
        Files.write(file, peek.toString().replace("| Zen Count | +0.3600 | " + zen, "| Zen Count | +0.3600 | +0.4401")
                .getBytes(StandardCharsets.UTF_8));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CeilingResults.checkPeek(c.published,
                file));
        assertTrue(e.getMessage().contains("Zen Count: printed +0.3600, +0.4401"), e.getMessage());
    }

    /** A results.ser as FullIndexRun writes one: identity, Results, the perfect array, the rounds. */
    static Path published(Path file, CeilingReport.Published p) throws Exception {
        try (java.io.ObjectOutputStream o = new java.io.ObjectOutputStream(Files.newOutputStream(file))) {
            o.writeUTF(p.identity);
            o.writeObject(new ArrayList<>(p.results));
            o.writeObject(p.perfect);
            o.writeObject(new ArrayList<>());
        }
        return file;
    }

    /** A save as CeilingReport.save writes one, of the fixture's first-decision context, beside stored rows of c06c061. */
    static Path saved(Path file, CeilingReport.Ceiling c) throws Exception {
        try (java.io.ObjectOutputStream o = new java.io.ObjectOutputStream(Files.newOutputStream(file))) {
            o.writeUTF("CeilingReport\ncommit test\nstates /x.states\ncounts " + c.counts.file + "\nresamples 40, seed "
                    + Bootstrap.SEED + "\nstored rows of c06c061");
            o.writeObject(new ArrayList<>(c.custom));
            o.writeObject(new ArrayList<>(c.bounds));
            o.writeObject(new ArrayList<SystemScorer.Entry>());
            o.writeObject(new ArrayList<>(Arrays.asList("Hi-Lo")));
            o.writeDouble(0.9886);
            o.writeObject(new ArrayList<SystemScorer.Entry>());
            java.util.LinkedHashMap<String, double[]> corr = new java.util.LinkedHashMap<>();
            corr.put("F1", new double[]{0.981, 0.88});
            o.writeObject(corr);
        }
        return file;
    }

    /**
     * The modes end to end on fixture files: full writes the full-play report; data, given that
     * report, checks it, and writes the data file with every source's SHA-256, the twelve views, the
     * claims at both scopes, and each cell's paragraph picked and filled, or pending where the
     * optimism run's figure is missing. A full report changed by one figure is refused, as is an
     * output file that exists.
     */
    @Test
    public void fullAndDataEndToEnd(@TempDir Path dir) throws Exception {
        CeilingReport.Ceiling c = withFullPlay(dir);
        CeilingResults.Full f = fullContext(dir, c);
        // F2's play vector alone under B here, so no row stops the report (check 5 is tested above).
        SystemScorer.Result f2 = f.run.results.get(1);
        f2.fullEntries.set(0, full("F2, full play", f2.count, 0.0046, 0));
        c.published.perfect = new double[]{0.1, -0.2, 0.3};
        f.run.perfect = c.published.perfect.clone();
        Path stored = published(dir.resolve("stored.ser"), c.published);
        Path run = published(dir.resolve("run.ser"), f.run);
        Path save = saved(dir.resolve("ceiling.ser"), c);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(bytes, true, "UTF-8");
        CeilingReport.printLadder(c, ps);
        CeilingReport.printClaims(c, ps);
        Path report = Files.write(dir.resolve("report.md"), bytes.toByteArray());
        StringBuilder peek = new StringBuilder("| Count | First decisions | Full play | Gain | its held SE |\n|---|\n");
        for (SystemScorer.Result r : c.published.results) {
            peek.append(String.format(java.util.Locale.ROOT, "| %s | %+.4f | %+.4f | 0 | 0 |%n", r.count.systems.isEmpty()
                    ? "Null count (no count)" : r.count.name, 100 * r.entries.get(0).estimate[P][S8],
                    100 * r.fullEntries.get(0).estimate[P][S8]));
        }
        Path peekFile = Files.write(dir.resolve("peek.txt"), peek.toString().getBytes(StandardCharsets.UTF_8));
        Path split = dir.resolve("split.tsv");
        SplitMargin.writeOut(split, "fixture", SplitMarginTest.rows(30, 1));
        Path paragraphs = Files.write(dir.resolve(CeilingParagraphs.FILE), CeilingParagraphsTest.committed());
        String[] common = {"--stored", stored.toString(), "--saved", save.toString(), "--report", report.toString(),
                "--counts", c.counts.file.toString(), "--fullplay-run", run.toString(), "--fullplay-counts",
                f.counts.file.toString(), "--peek", peekFile.toString(), "--split-margin", split.toString()};
        PrintStream nowhere = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        Path full = dir.resolve("full.md");
        CeilingResults.run(with(common, "full", "--out", full.toString()), "test", nowhere);
        String text = new String(Files.readAllBytes(full), StandardCharsets.UTF_8);
        for (String s : new String[]{"## The ladder at full play, 1-8 same schedule", "## Headline claims at full play",
                "- F2 + betting vector, full play at 80%, 1-8: gain", "B + M: ", "## Where F1's gap to the bound goes",
                "(applies to first-decision play only)", "No full-play row is above B + S x mean(delta)"}) {
            assertTrue(text.contains(s), s + "\n" + text);
        }
        Path data = dir.resolve("ceiling.json");
        CeilingResults.run(with(common, "data", "--paragraphs", paragraphs.toString(), "--full-report", full.toString(),
                "--out", data.toString()), "test", nowhere);
        assertTrue(Files.size(data) < 60_000, Files.size(data) + " bytes, over the page's budget");
        org.bson.Document d = org.bson.Document.parse(new String(Files.readAllBytes(data), StandardCharsets.UTF_8));
        assertEquals("test", d.getString("commit"));
        org.bson.Document sources = (org.bson.Document) d.get("sources");
        assertEquals(CeilingResults.sha256(save), ((org.bson.Document) sources.get("saved")).getString("sha256"));
        assertTrue(sources.containsKey("full-report") && sources.containsKey("paragraphs"));
        org.bson.Document ladder = (org.bson.Document) d.get("ladder");
        assertEquals(12, ladder.size());
        List<?> fd80 = (List<?>) ladder.get("fd.80.s8");
        assertEquals("bound-first", ((org.bson.Document) fd80.get(fd80.size() - 1)).getString("role"));
        org.bson.Document claims = (org.bson.Document) d.get("claims");
        assertTrue(claims.containsKey("fd.80.same.F2") && claims.containsKey("full.80.same.F2")
                && claims.containsKey("fd.80.flat.F1 flat") && claims.containsKey("full.70.flat.F1"), claims.keySet()
                .toString());
        assertEquals(CeilingResults.fullClaim(f, 1, P, S8).gain, ((org.bson.Document) claims.get("full.80.same.F2"))
                .getDouble("gain"), 0);
        org.bson.Document same = (org.bson.Document) ((org.bson.Document) d.get("paragraphs")).get("same");
        assertEquals(String.valueOf(CeilingParagraphs.choose(c, "same", P).paragraph), same.getString("letter"));
        assertEquals("C", same.getString("letter"), "F1 ties Zen and F2 is under Hi-Opt II with its ace side count");
        assertNull(same.getString("text"), "C needs the optimism run's twin gain");
        assertTrue(same.getString("pending").contains("TWIN_GAIN"), same.getString("pending"));
        Path optimism = Files.write(dir.resolve("optimism.properties"), "twinGain.F2.80.s8=0.0001\n".getBytes(
                StandardCharsets.UTF_8));
        Path data2 = dir.resolve("ceiling-2.json");
        CeilingResults.run(with(common, "data", "--paragraphs", paragraphs.toString(), "--optimism",
                optimism.toString(), "--out", data2.toString()), "test", nowhere);
        org.bson.Document filled = (org.bson.Document) ((org.bson.Document) org.bson.Document.parse(new String(
                Files.readAllBytes(data2), StandardCharsets.UTF_8)).get("paragraphs")).get("same");
        assertTrue(filled.getString("text").contains("its best, F2, scores +0.390% a round against +0.400% for Hi-Opt "
                + "II + ace side count. The gain it found on the shoes it was tuned on, +0.010%,"), filled.getString("text"));
        org.bson.Document flat = (org.bson.Document) ((org.bson.Document) d.get("paragraphs")).get("flat");
        assertEquals("C", flat.getString("letter"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CeilingResults.run(with(common, "data",
                "--paragraphs", paragraphs.toString(), "--full-report", full.toString(), "--out", data.toString()),
                "test", nowhere));
        assertTrue(e.getMessage().contains("exists"), e.getMessage());
        String line = text.lines().filter(l -> l.startsWith("- F1, full play at 80%, 1-8: gain")).findFirst().orElse("");
        Path changed = Files.write(dir.resolve("full-changed.md"), text.replace(line, line.replace("gain ", "gain +"))
                .getBytes(StandardCharsets.UTF_8));
        e = assertThrows(IllegalStateException.class, () -> CeilingResults.run(with(common, "data", "--paragraphs",
                paragraphs.toString(), "--full-report", changed.toString(), "--out", dir.resolve("d2.json").toString()),
                "test", nowhere));
        assertTrue(e.getMessage().contains("full-play ladder and claims"), e.getMessage());
    }

    private static String[] with(String[] common, String mode, String... more) {
        List<String> out = new ArrayList<>();
        out.add(mode);
        out.addAll(Arrays.asList(common));
        out.addAll(Arrays.asList(more));
        return out.toArray(new String[0]);
    }
}
