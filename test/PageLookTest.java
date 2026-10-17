import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The page look (PLAN_COMPUTE.md 6.3; INTEGRATION.md open item 4), on fixtures in a git repository
 * of its own standing for the count-design worktree, MainFileLookTest's way: a main-seed synthetic
 * state file, c06c061's stored rows, a registration of F1 and a control naming its full-play
 * registration, step 7 made by CeilingReport and step 8 by FullIndexRun, each with its ledger line
 * committed. Never the real count-design or the real main file.
 *
 * In order, in one repository: the look refused, writing nothing and no ledger line, while step 8
 * has no line; while step 8's line is not committed; with the registration changed and not
 * committed; with OUT already there. Then a look given a save whose one estimate is moved a bit:
 * it writes its one line, then stops at run C's check against step 7, before any output. Its line
 * committed, the look resumed with the true save writes no second line and makes every output:
 * run C's collector, the registered rows' wide figures, the first-decision and full-play exports
 * (their round trips passed), the replay's wide.ser and run D's collector. A further look without
 * --resume is refused, the ledger holding the page look already.
 */
public class PageLookTest {

    static final String COUNTS = "\"counts\": [ { \"name\": \"F1\", \"family\": \"F1\", \"role\": \"headline\", "
            + "\"form\": \"true\", \"divisor\": 2500, \"tags\": [-10000, 10000, 10000, 10000, 10000, 10000, 0, 0, 0, -10000] }, "
            + "{ \"name\": \"Hi-Lo = Hi-Lo Lite\", \"role\": \"control\", \"catalog\": \"Hi-Lo\" } ] }";
    static final String HEAD = "{ \"kind\": \"registered\", \"K\": 10000, \"twin\": \"twin, seed 20261007\", "
            + "\"searchCommit\": \"abc\", ";
    static final String CELLS = "\"cells\": {\"flat\": [\"F1\"], \"same\": [\"F1\"], \"own\": [\"F1\"]}, ";

    /** The fixture's shoes: enough that the full-play fit ranks every deal one way (open item 1), as FullPlayExportTest's does. */
    static final int SHOES = 12;

    /** MainFileLookTest's repository, on a main-seed synthetic file of the shoes given. */
    static MainFileLookTest.Repo repo(Path dir, String registration, int shoes) throws Exception {
        MainFileLookTest.Repo r = new MainFileLookTest.Repo();
        r.dir = dir;
        assertNotNull(CustomCounts.git(dir, "init", "-q"));
        CustomCounts.git(dir, "config", "user.email", "test@example.com");
        CustomCounts.git(dir, "config", "user.name", "test");
        Path data = Files.createDirectories(dir.resolve("runs"));
        Files.write(dir.resolve(".gitignore"), "runs/\n*.ser\n".getBytes(StandardCharsets.UTF_8));
        r.main = ComparisonReportTest.syntheticRun(data, CustomCounts.MAIN_SEED, CustomCounts.MAIN_SEED, shoes);
        ScoringStates st = FullIndexPilot.load(r.main, StateStore.shoesDone(r.main));
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, r.resamples, Bootstrap.SEED), SystemScorer.M_PLAY);
        r.ser = CeilingReportTest.results(data, r.main, st, CeilingReportTest.published(sc));
        r.printed = CeilingReportTest.printedReport(data, r.main);
        r.counts = CustomCountsTest.write(dir, CustomCounts.REGISTERED, registration);
        r.ledger = CustomCountsTest.write(dir, CustomCounts.LEDGER, CustomCounts.LEDGER_HEADER + "\n");
        return r;
    }

    static PageLook.Options options(MainFileLookTest.Repo r, Path saved, Path step8, Path out, boolean resume) {
        PageLook.Options o = new PageLook.Options();
        o.states = r.main;
        o.countDesign = r.dir;
        o.saved = saved;
        o.fullplayRun = step8;
        o.trees = step8.resolve("trees.bin");
        o.out = out;
        o.threads = 1;
        o.resume = resume;
        o.firstRows = List.of("F1@80");
        o.fullRows = List.of("F1, full play@80");
        return o;
    }

    static String refusal(PageLook.Options o, MainFileLookTest.Repo r) throws Exception {
        int before = r.ledgerLines();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PageLook.run(o, "test", r.nowhere));
        assertEquals(before, r.ledgerLines(), "a refused look writes no ledger line: " + e.getMessage());
        return e.getMessage();
    }

    /** Step 7's save written again with one change, as CeilingReport.save writes it. */
    static Path rewritten(Path from, Path to, java.util.function.Consumer<CeilingReport.Saved> change) throws Exception {
        CeilingReport.Saved s = CeilingReport.readSaved(from);
        change.accept(s);
        try (ObjectOutputStream o = new ObjectOutputStream(Files.newOutputStream(to))) {
            o.writeUTF(s.identity);
            o.writeObject(new ArrayList<>(s.custom));
            o.writeObject(new ArrayList<>(s.bounds));
            o.writeObject(new ArrayList<>(s.refit));
            o.writeObject(new ArrayList<>(s.checked));
            o.writeDouble(s.r);
            o.writeObject(new ArrayList<>(s.references));
            o.writeObject(new java.util.LinkedHashMap<>(s.correlations));
        }
        return to;
    }

    @Test
    public void theLookIsOneLineAfterStepsSevenAndEightAndChecksBeforeItWrites(@TempDir Path dir) throws Exception {
        MainFileLookTest.Repo r = repo(dir, HEAD + CELLS + COUNTS, SHOES);
        Path full = CustomCountsTest.write(dir, CustomCounts.FULL_PLAY, HEAD + "\"registeredWith\": \""
                + CustomCounts.REGISTERED + "\", " + CELLS + COUNTS);
        String fullBlob = CustomCounts.git(dir, "hash-object", "--", CustomCounts.FULL_PLAY);
        String registration = HEAD + "\"fullPlay\": {\"file\": \"" + CustomCounts.FULL_PLAY + "\", \"blob\": \"" + fullBlob
                + "\"}, " + CELLS + COUNTS;
        Files.write(r.counts, registration.getBytes(StandardCharsets.UTF_8));
        r.commit("register both");
        Path saved = dir.resolve("runs").resolve("step7.ser");
        CeilingReport.run(r.main, r.ser, r.printed, r.counts, saved, r.resamples, 1, "test", r.nowhere);
        r.commit("step 7");
        Path step8 = dir.resolve("runs").resolve("step8");
        Path out = dir.resolve("runs").resolve("look");

        // No line of step 8 yet: refused, and nothing written.
        String m = refusal(options(r, saved, step8, out, false), r);
        assertTrue(m.contains("holds no line of step 8"), m);
        FullIndexRun.run(new String[]{"--states", r.main.toString(), "--out", step8.toString(), "--counts", full.toString(),
                "--pens", "2", "--resamples", "3", "--threads", "1"}, "test", r.nowhere, 0);
        // Step 8's line not committed.
        m = refusal(options(r, saved, step8, out, false), r);
        assertTrue(m.contains(CustomCounts.LEDGER) && m.contains("not committed") || m.contains("changes not committed"), m);
        r.commit("step 8");
        // The registration changed and not committed.
        Files.write(r.counts, registration.replace("\"abc\"", "\"abd\"").getBytes(StandardCharsets.UTF_8));
        m = refusal(options(r, saved, step8, out, false), r);
        assertTrue(m.contains("changes not committed"), m);
        Files.write(r.counts, registration.getBytes(StandardCharsets.UTF_8));
        // OUT already there.
        Files.createDirectories(out);
        m = refusal(options(r, saved, step8, out, false), r);
        assertTrue(m.contains("exists"), m);
        Files.delete(out);
        assertFalse(Files.exists(out));
        int lines = r.ledgerLines();

        // A save whose one estimate is moved a bit: the line is written, then run C's check stops it.
        Path moved = rewritten(saved, dir.resolve("runs").resolve("moved.ser"), s -> {
            SystemScorer.Entry e = s.custom.get(0).entries.get(0);
            e.estimate[2][0] = Math.nextUp(e.estimate[2][0]);
        });
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PageLook.run(options(r, moved, step8, out, false), "test", r.nowhere));
        assertTrue(e.getMessage().contains("run C differs from step 7"), e.getMessage());
        List<String> ledger = Files.readAllLines(r.ledger);
        assertEquals(lines + 1, ledger.size(), "the look's one line, written before the states were read");
        String blob = CustomCounts.git(dir, "rev-parse", "HEAD:" + CustomCounts.REGISTERED);
        assertTrue(ledger.get(lines).startsWith("test\t" + blob + "\t") && ledger.get(lines).contains(PageLook.WHAT),
                ledger.get(lines));
        try (java.util.stream.Stream<Path> s = Files.list(out)) {
            assertEquals(List.of("look.txt"), s.map(p -> p.getFileName().toString()).toList(), "no output before the checks");
        }
        r.commit("the page look");

        // Resumed with the true save: no second line, and every output made.
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        PageLook.Made made = PageLook.run(options(r, saved, step8, out, true), "test",
                new PrintStream(text, true, "UTF-8"));
        assertEquals(lines + 1, r.ledgerLines(), "a resumed look writes no line");
        String log = text.toString("UTF-8");
        assertTrue(log.contains("every figure step 7's to the bit"), log);
        KeyEdges.Collector c = KeyEdges.Collector.read(made.runC);
        assertTrue(c.has("F1", 2) && c.has("Hi-Lo = Hi-Lo Lite", 2), "run C holds the registered rows' values");
        WideReport.Saved w = WideReport.read(made.wideFirst);
        assertEquals(List.of(), WideReport.check(w));
        assertTrue(w.rows.stream().anyMatch(x -> x.name.equals("F1")), "the registered rows' wide figures");
        assertTrue(Files.isRegularFile(made.exportFirst.resolve("manifest.sha256")), "the first-decision export passed");
        // The full-play export passes, or is refused for a row that ranks a deal two ways (open item 1,
        // which this fixture's F1 meets), and then only the bundle is missing.
        if (made.exportFullRefused == null) {
            assertTrue(Files.isRegularFile(made.exportFull.resolve("manifest.sha256")), "the full-play export passed");
        } else {
            assertTrue(made.exportFullRefused.contains("a file has one ranking (B2.4)"), made.exportFullRefused);
            assertFalse(Files.exists(made.exportFull));
            assertTrue(log.contains("open item 1"), log);
        }
        assertTrue(Files.isRegularFile(made.replay.resolve("wide.ser")));
        KeyEdges.Collector d = KeyEdges.Collector.read(made.runD.resolve(ReplayHooks.Sink.FILE));
        assertTrue(d.has("F1, full play", 2), "run D holds F1's full-play values");
        assertTrue(Files.readAllLines(made.exportFirst.resolve("index.tsv")).stream().anyMatch(l -> l.contains("\tF1\t80\t")));

        // The ledger holds the page look: a second one is refused, before any line.
        Path again = dir.resolve("runs").resolve("again");
        m = refusal(options(r, saved, step8, again, false), r);
        assertTrue(m.contains("holds the page look already"), m);
        assertFalse(Files.exists(again));
    }

    /**
     * The rows the page look exports by default are the registered counts' (SIM_CHECK.md's part 2
     * figures from step 7 and step 8), never the chart's: its figures name step 7 too, but they are
     * part 1's, and step 7's Results hold no chart row, so asking for it stops the look after its
     * ledger line (the look of 17 October did). The other test names its rows itself, so it never
     * met the default.
     */
    @Test
    public void theDefaultRowsAreTheRegisteredCountsAloneNeverTheChart() {
        for (String source : new String[]{"step 7", "step 8"}) {
            List<String> rows = PageLook.registeredRows(source);
            assertFalse(rows.isEmpty(), source);
            for (String row : rows) {
                assertFalse(row.startsWith(StrategyExport.CHART_ROW), source + ": " + row);
            }
            List<String> part2 = new ArrayList<>();
            for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
                String row = f.entry() + "@" + f.pen;
                if (f.part == 2 && f.source.equals(source) && !part2.contains(row)) {
                    part2.add(row);
                }
            }
            assertEquals(part2, rows, source);
        }
    }
}
