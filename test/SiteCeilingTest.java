import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SiteCeiling on ceiling files CeilingResults' data mode wrote from CeilingResultsTest's fixture
 * files (stored rows, step 7's save, step 8's run, the report, the split margin), at two outcomes
 * fixed by the fixture's levels: F1 clear of every comparator (paragraph A) and the fixture's own
 * levels (paragraph C). The paragraph and its row are picked from the file's claims, never told;
 * a file whose letter disagrees with its claims, or a paragraphs file that is not the committed
 * one, is refused; the ladder's rows land under their keys; and the page prints the paragraph's
 * committed words, every run of them held to the table.
 */
public class SiteCeilingTest {

    static final int P = CeilingParagraphsTest.P;
    static final int S8 = CeilingParagraphsTest.S8;

    /** The data file of a fixture whose first-decision levels are l; with optimism, the twin's figures the pending brackets need. */
    static Path data(Path dir, CeilingParagraphsTest.Levels l, boolean optimism) throws Exception {
        CeilingReport.Ceiling c = CeilingParagraphsTest.ceiling(dir, l);
        c.published.identity = CeilingResultsTest.IDENTITY;
        for (SystemScorer.Result r : c.published.results) {
            for (SystemScorer.Entry e : r.entries) {
                if (!e.bettingOnly) {
                    r.fullEntries.add(CeilingResultsTest.full(e.name + ", full play", e.count,
                            e.estimate[P][S8] + CeilingResultsTest.FULL_GAIN, 0));
                }
            }
        }
        c.bounds.add(CeilingParagraphsTest.entry("Chart play and perfect insurance, bet on its own value", null, false,
                0.0020, 0));
        SystemScorer.Entry b = new SystemScorer.Entry("Perfect play of the dealt hand and insurance, bet on its own value",
                null, false, true, true);
        CeilingResultsTest.copy(CeilingParagraphsTest.entry("x", null, false, CeilingResultsTest.BOUND_FULL, 0), b);
        for (int p = 0; p < P; p++) {
            b.estimate[p] = b.estimate[P];
            b.boot[p] = b.boot[P];
        }
        c.bounds.add(b);
        CeilingResults.Full f = CeilingResultsTest.fullContext(dir, c);
        SystemScorer.Result f2 = f.run.results.get(1);
        f2.fullEntries.set(0, CeilingResultsTest.full("F2, full play", f2.count, 0.0046, 0));
        c.published.perfect = new double[]{0.1, -0.2, 0.3};
        f.run.perfect = c.published.perfect.clone();
        Path stored = CeilingResultsTest.published(dir.resolve("stored.ser"), c.published);
        Path run = CeilingResultsTest.published(dir.resolve("run.ser"), f.run);
        Path save = CeilingResultsTest.saved(dir.resolve("ceiling.ser"), c);
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
        Path paragraphs = paragraphs(dir);
        List<String> args = new ArrayList<>(List.of("data", "--stored", stored.toString(), "--saved", save.toString(),
                "--report", report.toString(), "--counts", c.counts.file.toString(), "--fullplay-run", run.toString(),
                "--fullplay-counts", f.counts.file.toString(), "--peek", peekFile.toString(), "--split-margin",
                split.toString(), "--paragraphs", paragraphs.toString(), "--widening",
                CeilingResultsTest.pilotFile(dir).toString()));
        if (optimism) {
            Path o = Files.write(dir.resolve("optimism.properties"), ("twinGain.F2.80.s8=0.0001\ntwinGain.F1.80.flat=0.0001\n"
                    + "optimism.F1.80.s8=0.0002\noptimism.F2.80.s8=0.0002\n").getBytes(StandardCharsets.UTF_8));
            args.addAll(List.of("--optimism", o.toString()));
        }
        Path out = dir.resolve("ceiling.json");
        args.addAll(List.of("--out", out.toString()));
        CeilingResults.run(args.toArray(new String[0]), "ceiling-fixture",
                new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"));
        return out;
    }

    static Path paragraphs(Path dir) throws Exception {
        Path p = dir.resolve(CeilingParagraphs.FILE);
        return Files.exists(p) ? p : Files.write(p, CeilingParagraphsTest.committed());
    }

    /** F1 well above every comparator, with no noise: the claim holds, so A, naming F1 at its EV. */
    static CeilingParagraphsTest.Levels clear() {
        CeilingParagraphsTest.Levels l = new CeilingParagraphsTest.Levels();
        l.f1 = 0.0046;
        l.f1Flat = 0.0046;
        return l;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> table(SiteInput in, String cell) {
        return (Map<String, Object>) ((Map<String, Object>) in.tables.get("ceiling_paragraphs")).get(cell);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aClearGainPicksParagraphAFromTheClaims(@TempDir Path dir) throws Exception {
        Path file = data(dir, clear(), true);
        Map<String, Object> d = SiteAdapt.read(file);
        SiteInput in = SiteCeiling.adapt(file, "runs/site/ceiling-fixture.json", paragraphs(dir));
        Map<String, Object> same = table(in, "same");
        assertEquals("A", same.get("letter"));
        assertEquals("F1", same.get("row"));
        CeilingParagraphs.Texts t = CeilingParagraphs.parse(CeilingParagraphsTest.committed(), CeilingParagraphs.BLOB);
        assertEquals(t.paragraphs.get('A'), same.get("words"), "the committed words, untouched");
        assertNull(same.get("pending"));
        Map<String, Object> fill = (Map<String, Object>) same.get("fill");
        assertEquals("name:f1", fill.get("ROW"));
        Map<String, Object> claim = (Map<String, Object>) ((Map<String, Object>) d.get("claims")).get("fd.80.same.F1");
        assertEquals("name:" + SiteInput.catalogRow((String) claim.get("best"))[0], fill.get("BEST"));
        assertEquals(100 * ((Number) claim.get("ev")).doubleValue(),
                ((Map<String, Object>) in.figures.get((String) fill.get("EV"))).get("value"));
        assertEquals(100 * ((Number) claim.get("gain")).doubleValue(),
                ((Map<String, Object>) in.figures.get((String) fill.get("LOWER"))).get("value"));
        assertTrue(((List<Object>) same.get("percent_after")).contains("GAIN"));
        assertFalse(((List<Object>) same.get("percent_after")).contains("EV"), "the words print EV's % themselves");
        assertEquals("A", table(in, "flat").get("letter"));
        // The ladder's rows under their keys: F1 at first decisions, the bounds as perfect.
        Map<String, Object> fd = (Map<String, Object>) in.figures.get("fd.80.s8.f1");
        assertEquals(100 * 0.0046, (Double) fd.get("value"), 1e-12);
        assertNotNull(in.figures.get("fd.80.s8.perfect"));
        Map<String, Object> full = (Map<String, Object>) in.figures.get("full.80.s8.perfect");
        assertEquals(100 * CeilingResultsTest.BOUND_FULL, (Double) full.get("value"), 1e-12);
        assertEquals(1.0, full.get("widen"), "a bound is fitted on nothing");
        assertEquals(CeilingResultsTest.PILOT_LARGEST, ((Map<String, Object>) in.figures.get("full.80.s8.f1")).get("widen"));
        assertNotNull(in.figures.get("fd.80.s8.hi_lo"), "a published rung at first decisions, for one number, one value");
        assertNull(in.figures.get("full.80.s8.hi_lo"), "and not at full play, where the two widen differently");
        new SiteData().add("ceiling.json", in.text("ceiling.json").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * F1 a little above the best plain row (Zen) at 1-8 and flat, but noisy enough that its
     * interval reaches below: no claim holds, its estimate is above, so B in both cells, from the
     * file's own claims.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void aGainTheIntervalDoesNotSupportPicksB(@TempDir Path dir) throws Exception {
        CeilingParagraphsTest.Levels l = new CeilingParagraphsTest.Levels();
        l.f1 = CeilingParagraphsTest.ZEN + 0.0002;
        l.f1Noise = 0.0005;
        l.f1Flat = CeilingParagraphsTest.ZEN + 0.0002;
        l.f1FlatNoise = 0.0005;
        Path file = data(dir, l, true);
        Map<String, Object> claim = (Map<String, Object>) ((Map<String, Object>) SiteAdapt.read(file).get("claims"))
                .get("fd.80.same.F1");
        assertEquals(Boolean.FALSE, claim.get("holds"), "the claim does not hold");
        assertTrue(((Number) claim.get("ev")).doubleValue() > ((Number) claim.get("bestEv")).doubleValue());
        SiteInput in = SiteCeiling.adapt(file, "x", paragraphs(dir));
        assertEquals("B", table(in, "same").get("letter"));
        assertEquals("F1", table(in, "same").get("row"));
        assertEquals("B", table(in, "flat").get("letter"));
        assertEquals("F1 flat", table(in, "flat").get("row"));
        CeilingParagraphs.Texts t = CeilingParagraphs.parse(CeilingParagraphsTest.committed(), CeilingParagraphs.BLOB);
        assertEquals(t.paragraphs.get('B'), table(in, "same").get("words"));
        assertEquals(t.flat.get('B'), table(in, "flat").get("words"));
    }

    @Test
    public void theFixturesOwnLevelsPickC(@TempDir Path dir) throws Exception {
        Path file = data(dir, new CeilingParagraphsTest.Levels(), true);
        SiteInput in = SiteCeiling.adapt(file, "x", paragraphs(dir));
        assertEquals("C", table(in, "same").get("letter"));
        assertEquals("F2", table(in, "same").get("row"), "every row qualifies for C; F2 is the higher");
        Path noOptimism = data(Files.createDirectories(dir.resolve("no")), new CeilingParagraphsTest.Levels(), false);
        SiteInput pending = SiteCeiling.adapt(noOptimism, "x", paragraphs(dir));
        assertTrue(String.valueOf(table(pending, "same").get("pending")).contains("TWIN_GAIN"));
    }

    /** A file whose letter is not what its claims pick is refused, and so is any paragraphs file but the committed one. */
    @Test
    public void aLetterTheClaimsDoNotPickAndOtherWordsAreRefused(@TempDir Path dir) throws Exception {
        Path file = data(dir, clear(), true);
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Path told = Files.write(dir.resolve("told.json"), text.replace("\"letter\":\"A\"", "\"letter\":\"B\"")
                .getBytes(StandardCharsets.UTF_8));
        IllegalStateException x = assertThrows(IllegalStateException.class, () -> SiteCeiling.adapt(told, "x", paragraphs(dir)));
        assertTrue(x.getMessage().contains("pick paragraph A"), x.getMessage());
        Path edited = Files.write(dir.resolve("edited.md"), new String(CeilingParagraphsTest.committed(),
                StandardCharsets.UTF_8).replace("beats every", "beats all").getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> SiteCeiling.adapt(file, "x", edited));
    }
}
