import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two programs meet on split lines (SHOE_SPEC.md B2.1, B2.4 and B8; SIM_CHECK.md section 5's
 * D5 and deviation 1). PageLookTest's fixture, made here the same way: on its 12 shoes F1's full
 * play at 80% ranks deals two ways, so the page look's full-play export, written by the replay's
 * hook (StrategyExport.ReplayExport), has 31 split lines in fold 0's file and 1 in fold 1's, and
 * lookups.tsv B8's 1,000 split lines. The compiled simulator, as its own program on its own
 * classes (SimReadsExportTest.sim), runs p2 on that bundle: D1, D2, D4 and D5 with 0 mismatches,
 * D5's split cells compared in both full-play columns, and with every exact check passed, P2.
 *
 * <p>D2 passes because the replay's export samples the whole catalog's keys. Until 19 October 2026
 * it sampled only the counts the replay scored, here F1 and the Hi-Lo control: D2 reported the
 * other catalog counts missing, p2 counted its exact checks failed and ran no P2, and the part 2
 * pilot on the real page look's full-play bundle (F50, F52) would have stopped there with no file.
 *
 * <p>A copy of the bundle with one split line taken out, resealed, is the planted check of the test
 * itself: fold 0's split line for 8s against an ace, whose split hands resplit where its first line
 * stands. The simulator's split hands then read the first line, and D5 counts the cell a mismatch.
 */
public class SimReadsSplitLinesTest {

    @TempDir
    static Path dir;
    static Path bundle;
    static Path out;
    static int p2exit;
    static String p2log;
    static String lookLog;

    /** PageLookTest's look, its steps 7 and 8 committed, with the true save: what it makes. */
    static PageLook.Made look() throws Exception {
        MainFileLookTest.Repo r = PageLookTest.repo(dir, PageLookTest.HEAD + PageLookTest.CELLS + PageLookTest.COUNTS,
                PageLookTest.SHOES);
        Path full = CustomCountsTest.write(dir, CustomCounts.FULL_PLAY, PageLookTest.HEAD + "\"registeredWith\": \""
                + CustomCounts.REGISTERED + "\", " + PageLookTest.CELLS + PageLookTest.COUNTS);
        String fullBlob = CustomCounts.git(dir, "hash-object", "--", CustomCounts.FULL_PLAY);
        Files.write(r.counts, (PageLookTest.HEAD + "\"fullPlay\": {\"file\": \"" + CustomCounts.FULL_PLAY + "\", \"blob\": \""
                + fullBlob + "\"}, " + PageLookTest.CELLS + PageLookTest.COUNTS).getBytes(StandardCharsets.UTF_8));
        r.commit("register both");
        Path saved = dir.resolve("runs").resolve("step7.ser");
        CeilingReport.run(r.main, r.ser, r.printed, r.counts, saved, r.resamples, 1, "test", r.nowhere);
        r.commit("step 7");
        Path step8 = dir.resolve("runs").resolve("step8");
        FullIndexRun.run(new String[]{"--states", r.main.toString(), "--out", step8.toString(), "--counts", full.toString(),
                "--pens", "2", "--resamples", "3", "--threads", "1"}, "test", r.nowhere, 0);
        r.commit("step 8");
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        PageLook.Made made = PageLook.run(PageLookTest.options(r, saved, step8, dir.resolve("runs").resolve("look"), false),
                "test", new PrintStream(text, true, "UTF-8"));
        lookLog = text.toString("UTF-8");
        return made;
    }

    @BeforeAll
    static void lookAndCheck() throws Exception {
        PageLook.Made made = look();
        assertNull(made.exportFullRefused, made.exportFullRefused);
        bundle = made.exportFull;
        out = dir.resolve("sim");
        Path log = dir.resolve("p2.log");
        p2exit = SimReadsExportTest.sim(log, "p2", "--bundle", bundle.toString(), "--out", out.toString(), "--sim-commit",
                "fixture", "--k", "20", "--threads", "1", "--catalog", "counting-systems.json", "--registry", "SIM_CHECK.md");
        p2log = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
    }

    static String file(int fold) {
        return ExportBundle.Strategy.fileName("F1, full play", 80, fold);
    }

    /** lookups.tsv's full-play columns: one a full-play file of the bundle. */
    static int fullColumns(Path b) throws Exception {
        int n = 0;
        for (String[] f : ExportBundle.readIndex(b.resolve("index.tsv")).values()) {
            n += f[4].equals("full") ? 1 : 0;
        }
        return n;
    }

    /** checks.txt's D5 line for the split cells, as the simulator prints it, k of them mismatched. */
    static String splitLine(int columns, int k) {
        return "D5\tsplit cells, the full-play files' columns of 1000 split lines: " + (1000 * columns - k) + " of "
                + 1000 * columns + " equal to lookups.tsv's; " + k + " mismatches; the other files' columns there, each -: "
                + "0 of 0 equal; the bundle holds a file of scope full";
    }

    /**
     * Every exact check the simulator makes passes on a bundle whose files have split lines: 0
     * mismatches in D1, D2 (no catalog count missing), D4 and D5, D5's split cells compared, 1,000
     * in each full-play column, and P2 then runs.
     */
    @Test
    public void theSimulatorsExactChecksPassOnTheLooksSplitLines() throws Exception {
        assertEquals(31, ExportBundle.readStrategy(bundle.resolve(file(0))).splitLines(), lookLog);
        assertEquals(1, ExportBundle.readStrategy(bundle.resolve(file(1))).splitLines(), lookLog);
        Path checks = out.resolve("checks.txt");
        assertTrue(Files.isRegularFile(checks), p2log);
        String all = new String(Files.readAllBytes(checks), StandardCharsets.UTF_8);
        System.out.print("checks.txt of the simulator on the page look's full-play bundle:\n" + all);
        assertTrue(SimReadsWidePlansTest.line(checks, "D1").matches("D1\t(\\d+) of \\1 records card for card .*; 0 mismatches"),
                all);
        Matcher d2 = Pattern.compile("D2\t(\\d+) of \\1 keys .*; 0 mismatches; catalog counts missing from its header: \\[]")
                .matcher(SimReadsWidePlansTest.line(checks, "D2"));
        assertTrue(d2.matches() && Long.parseLong(d2.group(1)) > 0, all);
        long d4 = SimReadsWidePlansTest.d4Comparisons(bundle);
        assertEquals("D4\t" + d4 + " of " + d4 + " bet keys and bets equal to states.bin's; 0 mismatches",
                SimReadsWidePlansTest.line(checks, "D4"), all);
        Matcher d5 = Pattern.compile("D5\t(\\d+) of \\1 lookups .*; 0 mismatches").matcher(SimReadsWidePlansTest.line(checks, "D5"));
        assertTrue(d5.matches() && Long.parseLong(d5.group(1)) > 0, all);
        int columns = fullColumns(bundle);
        assertEquals(2, columns);
        assertTrue(all.contains("\n" + splitLine(columns, 0) + "\n"), all);
        assertEquals(List.of(), SiteSim.failedChecks(checks), "the engine's reader of checks.txt takes it as passed");
        assertEquals(0, p2exit, "every exact check passed, so P2 runs: " + p2log);
        assertTrue(p2log.contains("P2: ") && !p2log.contains("an exact check failed"), p2log);
    }

    /**
     * The test's own check: fold 0's split line for 8s against an ace taken out, resealed. Its split
     * hands resplit by it and stand by the first line, so the simulator's D5 finds that split cell
     * wrong, and p2 stops before P2.
     */
    @Test
    public void aSplitLineTakenOutFailsTheSimulatorsChecks() throws Exception {
        Path copy = dir.resolve("planted-no-split-line");
        Files.createDirectories(copy);
        for (Map.Entry<String, byte[]> f : FullPlayExportTest.files(bundle).entrySet()) {
            Files.write(copy.resolve(f.getKey()), f.getValue());
        }
        String split = "split\t0\t8\t8\t1\tP\tS\tH\tD";
        List<String> lines = Files.readAllLines(copy.resolve(file(0)), StandardCharsets.UTF_8);
        assertTrue(lines.contains("first\t0\t8\t8\t1\tS\tH\tD\tP") && lines.remove(split), "fold 0's split line " + split);
        Files.write(copy.resolve(file(0)), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        StrategyExportTest.reseal(copy);
        assertEquals(30, ExportBundle.readStrategy(copy.resolve(file(0))).splitLines());
        Path o = dir.resolve("sim-planted");
        Path log = dir.resolve("planted.log");
        int exit = SimReadsExportTest.sim(log, "p2", "--bundle", copy.toString(), "--out", o.toString(), "--sim-commit",
                "fixture", "--k", "20", "--threads", "1", "--catalog", "counting-systems.json", "--registry", "SIM_CHECK.md");
        String said = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        Path checks = o.resolve("checks.txt");
        assertTrue(Files.isRegularFile(checks), said);
        String all = new String(Files.readAllBytes(checks), StandardCharsets.UTF_8);
        System.out.print("checks.txt with fold 0's split line for 8s against an ace taken out:\n" + all);
        assertTrue(all.contains("\n" + splitLine(2, 1) + "\n"), all);
        for (String d : new String[]{"D1", "D2", "D4"}) {
            assertTrue(SimReadsWidePlansTest.line(checks, d).contains("; 0 mismatches"), all);
        }
        assertEquals(List.of("D5"), SiteSim.checkNames(SiteSim.failedChecks(checks)), all);
        assertEquals(1, exit, said);
        assertTrue(said.contains("an exact check failed: P2 is not run"), said);
    }
}
