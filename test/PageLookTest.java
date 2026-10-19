import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The page look (PLAN_COMPUTE.md 6.3; INTEGRATION.md open item 4), on fixtures in a git repository
 * of its own standing for the count-design worktree, MainFileLookTest's way: a main-seed synthetic
 * state file, c06c061's stored rows, a registration of F1 and a control naming its full-play
 * registration, step 7 made by CeilingReport and step 8 by FullIndexRun, each with its ledger line
 * committed. Never the real count-design or the real main file.
 *
 * The first test, in one repository: the look refused, writing nothing and no ledger line, while
 * step 8 has no line; while step 8's line is not committed; with the registration changed and not
 * committed; with OUT already there. Then a look given a save whose one estimate is moved a bit:
 * it writes its one line and look.txt, then stops at run C's check against step 7, before any
 * output. Its line committed, the look resumed with the true save is refused before anything is
 * written, under its classes or others, look.txt naming the moved save in its step 7 line: a look
 * is resumed with the files it was made with. A further look without --resume is refused, the
 * ledger holding the page look already.
 *
 * The second, the real look's way (runs/page-look-0e0920d, INTEGRATION.md open item 1): a look
 * with the true save under commit "test", its replay interrupted after F1's item was kept, then
 * resumed under "test" as before, into replay, F1's record read back, writing no second line and
 * every output: run C's collector, the registered rows' wide figures, the first-decision and
 * full-play exports (their round trips passed), the replay's wide.ser and run D's collector, and
 * its resume record. Its export-full is deleted, standing for the one the real look's export
 * refused, and its replay's records are kept, as main kept the real one's. Under commit "other":
 * a resume with another step 7 save, and one with run D's collector not there, are refused with
 * nothing written; a resume interrupted in its own replay leaves replay-other holding its run.id;
 * resumed there, it writes export-full alone, from F1's item alone, with no wide plans and no sink:
 * every other file of OUT the same in bytes and time, no ledger line, the bundle the look's own
 * replay wrote file for file and byte for byte, and the record of both attempts beside look.txt.
 * Its records are let go in replay-other, not in replay; a further resume under "other" is refused,
 * export-full being there, and a second look too.
 *
 * The full-play export ranks deals two ways here (INTEGRATION.md open item 1): F1's fold 0 fit
 * stops at its pass cap, so the play its splits were valued under ranks deals otherwise than its
 * refit, and the files carry split lines (SHOE_SPEC.md B2.4), 31 in fold 0 and 1 in fold 1. They
 * are held to the replayed strategies apart from Refit: each file's first lines to the replay's
 * fit ranked, the ranking its split hands read to the strategy's walked one as a hook kept it.
 * On the values, kept by another hook, the row checks as the export checks it, and with fold 0's
 * split hands given the refit's ranking, as before the split lines, it is refused by the splits
 * the last round valued, played again from the file. Planted on 19 October 2026 in scratch
 * copies of the code, each failing the look's test: build refusing a fold whose two rankings
 * differ, as it did before the split lines (the full-play export writes no bundle); and after the
 * review of 134da21, Refit's walked ranking the refit's, the one ranking of before the split lines
 * (the export refused, fold 0: a split of 8s against an ace in group 0, Split offered, S by the
 * file and P as valued), the splits the last round valued not played again (0 played), and the
 * fingerprints played against worked out from Refit.walked (fold 0's split hands given the
 * refit's ranking, nothing refused). These checks are on the export of the resume under "other"
 * since the resume under other classes was written.
 *
 * Planted on 19 October 2026 for the resume, each in a scratch copy of the code outside the
 * worktree (/private/tmp/pagelook-plants-1019), each failing the test named (the second unless
 * said): look.txt not held to the files on --resume (the first: resumed with the true save, the
 * look went on and wrote every output); held to them in its commit line too (refused for that
 * line where the absent collector was due); a replay of other classes never seen as such (the
 * resume went on into replay, refused there by its run.id); the sink left on in the replay of
 * other classes (refused by refuseKept, run-d being the look's); the sink left on with refuseKept
 * and PageLook's check of the look's files both taken out (run-d/run-b.ser written again, its
 * SHA-256 and time not the snapshot's); a ledger line written by that resume (a line more after
 * its interrupted attempt); no resume record written (resume-test.txt not there); the records
 * let go nowhere on that path (no records-let-go.txt in replay-other); replay-other not resumed
 * after its interrupted attempt (refused there, holding a run already); export-full there not
 * refused (the further resume refused only by the replay's own records-let-go.txt); the look's
 * other outputs not required (the resume with run D's collector aside went on); every item
 * replayed, not the exported rows' alone (2 items, not 1); the wide plans on (wide among the
 * replay's hooks); and a resume under the replay's own classes taken as one under others
 * (refused, the look's replay.txt not there yet).
 */
public class PageLookTest {

    static final String COUNTS = "\"counts\": [ { \"name\": \"F1\", \"family\": \"F1\", \"role\": \"headline\", "
            + "\"form\": \"true\", \"divisor\": 2500, \"tags\": [-10000, 10000, 10000, 10000, 10000, 10000, 0, 0, 0, -10000] }, "
            + "{ \"name\": \"Hi-Lo = Hi-Lo Lite\", \"role\": \"control\", \"catalog\": \"Hi-Lo\" } ] }";
    static final String HEAD = "{ \"kind\": \"registered\", \"K\": 10000, \"twin\": \"twin, seed 20261007\", "
            + "\"searchCommit\": \"abc\", ";
    static final String CELLS = "\"cells\": {\"flat\": [\"F1\"], \"same\": [\"F1\"], \"own\": [\"F1\"]}, ";

    /**
     * The fixture's shoes. On these 12 F1's full play ranks a deal two ways (INTEGRATION.md open
     * item 1), so its export has split lines (SHOE_SPEC.md B2.4); FullPlayExportTest's 12 shoes of
     * another seed rank every deal one way.
     */
    static final int SHOES = 12;

    /**
     * Keeps, from the 80% fold item, F1's full-play row and two refits of its strategies made
     * while they hold their last round's values: for the checks of a file planted where the two
     * rankings differ, which need the values.
     */
    static final class Refits implements FullIndexReplay.Hook {
        SystemScorer scorer;
        SystemScorer.Entry row;
        final StrategyExport.Refit[] refit = new StrategyExport.Refit[2];

        @Override
        public String name() {
            return "refits";
        }

        @Override
        public Serializable after(FullIndexReplay.Item item, SystemScorer s, SystemScorer.Result scored,
                                  List<FullIndexFit.Strategy> two) {
            for (SystemScorer.Entry e : scored.fullEntries) {
                if (!item.halves && item.pen == 2 && e.name.equals("F1, full play")) {
                    scorer = s;
                    row = e;
                    for (int k = 0; k < refit.length; k++) {
                        refit[k] = new StrategyExport.Refit(s, 2, two.toArray(new FullIndexFit.Strategy[0]));
                    }
                }
            }
            return null;
        }
    }

    /** F1's full-play row at 80% built from a refit and checked on its values, as the replay's export checks it. */
    static StrategyExport.Report checked(Refits k, StrategyExport.Refit rf) {
        StrategyExport.Built b = StrategyExport.build(k.scorer, rf, k.row, new String[]{"same-8"});
        return StrategyExport.checkFull(k.scorer.st, StrategyExport.chartArray(), b, rf, StrategyExport.Expect.of(k.row, 2));
    }

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

    /**
     * Stops the replay at the item of the count given (its place among the run's counts), before
     * the item's record is kept: an interruption. Given -1, it stops nothing; a resume of the same
     * replay names the same hooks, since the replay's identity names them.
     */
    static final class Interrupt implements FullIndexReplay.Hook {
        final int at;

        Interrupt(int at) {
            this.at = at;
        }

        @Override
        public String name() {
            return "interrupt";
        }

        @Override
        public Serializable after(FullIndexReplay.Item item, SystemScorer s, SystemScorer.Result scored,
                                  List<FullIndexFit.Strategy> two) {
            if (item.count == at) {
                throw new IllegalStateException("interrupted at " + item);
            }
            return null;
        }
    }

    /** The fixture up to a step: its repository, the full-play registration, step 7's save, and where step 8 and the look go. */
    static final class Look {
        MainFileLookTest.Repo r;
        Path full;
        String registration;
        Path saved;
        Path step8;
        Path out;
    }

    /** Both registrations committed and step 7 made by CeilingReport, its line committed; step 8 not yet. */
    static Look stepSeven(Path dir) throws Exception {
        Look k = new Look();
        k.r = repo(dir, HEAD + CELLS + COUNTS, SHOES);
        k.full = CustomCountsTest.write(dir, CustomCounts.FULL_PLAY, HEAD + "\"registeredWith\": \""
                + CustomCounts.REGISTERED + "\", " + CELLS + COUNTS);
        String fullBlob = CustomCounts.git(dir, "hash-object", "--", CustomCounts.FULL_PLAY);
        k.registration = HEAD + "\"fullPlay\": {\"file\": \"" + CustomCounts.FULL_PLAY + "\", \"blob\": \"" + fullBlob
                + "\"}, " + CELLS + COUNTS;
        Files.write(k.r.counts, k.registration.getBytes(StandardCharsets.UTF_8));
        k.r.commit("register both");
        k.saved = dir.resolve("runs").resolve("step7.ser");
        CeilingReport.run(k.r.main, k.r.ser, k.r.printed, k.r.counts, k.saved, k.r.resamples, 1, "test", k.r.nowhere);
        k.r.commit("step 7");
        k.step8 = dir.resolve("runs").resolve("step8");
        k.out = dir.resolve("runs").resolve("look");
        return k;
    }

    /** Step 8: FullIndexRun of the full-play registration at 80%, its ledger line not yet committed. */
    static void stepEight(Look k) throws Exception {
        FullIndexRun.run(new String[]{"--states", k.r.main.toString(), "--out", k.step8.toString(), "--counts",
                k.full.toString(), "--pens", "2", "--resamples", "3", "--threads", "1"}, "test", k.r.nowhere, 0);
    }

    static PageLook.Options options(Look k, Path saved, Path out, boolean resume) {
        return options(k.r, saved, k.step8, out, resume);
    }

    /** The look's options on the fixture's files, F1's rows at 80% exported (SimReadsSplitLinesTest's look too). */
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

    static String refusal(PageLook.Options o, MainFileLookTest.Repo r, String commit) throws Exception {
        int before = r.ledgerLines();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PageLook.run(o, commit, r.nowhere));
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

    /** Step 7's save with its one estimate moved a bit: run C, scored again, differs from it. */
    static Path moved(Look k) throws Exception {
        return rewritten(k.saved, k.saved.resolveSibling("moved.ser"), s -> {
            SystemScorer.Entry e = s.custom.get(0).entries.get(0);
            e.estimate[2][0] = Math.nextUp(e.estimate[2][0]);
        });
    }

    static List<String> listing(Path dir) throws Exception {
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    public void theLookIsOneLineAfterStepsSevenAndEightAndChecksBeforeItWrites(@TempDir Path dir) throws Exception {
        Look k = stepSeven(dir);
        MainFileLookTest.Repo r = k.r;
        Path out = k.out;

        // No line of step 8 yet: refused, and nothing written.
        String m = refusal(options(k, k.saved, out, false), r, "test");
        assertTrue(m.contains("holds no line of step 8"), m);
        stepEight(k);
        // Step 8's line not committed.
        m = refusal(options(k, k.saved, out, false), r, "test");
        assertTrue(m.contains(CustomCounts.LEDGER) && m.contains("not committed") || m.contains("changes not committed"), m);
        r.commit("step 8");
        // The registration changed and not committed.
        Files.write(r.counts, k.registration.replace("\"abc\"", "\"abd\"").getBytes(StandardCharsets.UTF_8));
        m = refusal(options(k, k.saved, out, false), r, "test");
        assertTrue(m.contains("changes not committed"), m);
        Files.write(r.counts, k.registration.getBytes(StandardCharsets.UTF_8));
        // OUT already there.
        Files.createDirectories(out);
        m = refusal(options(k, k.saved, out, false), r, "test");
        assertTrue(m.contains("exists"), m);
        Files.delete(out);
        assertFalse(Files.exists(out));
        int lines = r.ledgerLines();

        // A save whose one estimate is moved a bit: the line is written, then run C's check stops it.
        Path moved = moved(k);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PageLook.run(options(k, moved, out, false), "test", r.nowhere));
        assertTrue(e.getMessage().contains("run C differs from step 7"), e.getMessage());
        List<String> ledger = Files.readAllLines(r.ledger);
        assertEquals(lines + 1, ledger.size(), "the look's one line, written before the states were read");
        String blob = CustomCounts.git(dir, "rev-parse", "HEAD:" + CustomCounts.REGISTERED);
        assertTrue(ledger.get(lines).startsWith("test\t" + blob + "\t") && ledger.get(lines).contains(PageLook.WHAT),
                ledger.get(lines));
        assertEquals(List.of(PageLook.LOOK), listing(out), "no output before the checks");
        r.commit("the page look");

        // Resumed with the true save, under these classes or others: look.txt names the moved save, so
        // it is refused before anything is written, naming the line. A look is resumed with its own files.
        for (String commit : new String[]{"test", "other"}) {
            m = refusal(options(k, k.saved, out, true), r, commit);
            assertTrue(m.contains("names another look than this resume") && m.contains("  was: step 7 "
                    + moved.toAbsolutePath().normalize() + ", sha256 " + KeyEdgesRun.sha256(moved) + "\n  now: step 7 "
                    + k.saved.toAbsolutePath().normalize() + ", sha256 " + KeyEdgesRun.sha256(k.saved) + "\n"), m);
            assertEquals(1, m.split("  was: ", -1).length - 1, "one line differs: " + m);
            assertEquals(List.of(PageLook.LOOK), listing(out), "nothing written, no resume record either");
        }

        // The ledger holds the page look: a second one is refused, before any line.
        Path again = dir.resolve("runs").resolve("again");
        m = refusal(options(k, k.saved, again, false), r, "test");
        assertTrue(m.contains("holds the page look already"), m);
        assertFalse(Files.exists(again));
    }

    /**
     * The look of row 7 as it stood on 19 October 2026: made under one commit, resumed under it
     * once, its full-play export refused (deleted here to stand for it), and every other output
     * written. Resumed under other classes, it replays step 8's fit into a directory of its own and
     * writes export-full alone, with its split lines; every byte and time of what it held is as it
     * was, no ledger line is added, and the resume is recorded beside look.txt.
     */
    @Test
    public void aLookResumedUnderOtherClassesWritesItsFullPlayExportAloneAndKeepsWhatItHolds(@TempDir Path dir)
            throws Exception {
        Look k = stepSeven(dir);
        MainFileLookTest.Repo r = k.r;
        Path out = k.out;
        stepEight(k);
        r.commit("step 8");

        // The look under commit "test", its replay interrupted at the control's item, after F1's was kept.
        PageLook.Options first = options(k, k.saved, out, false);
        first.hooks.add(new Interrupt(1));
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PageLook.run(first, "test", new PrintStream(text, true, "UTF-8")));
        assertTrue(e.getMessage().startsWith("interrupted at "), e.getMessage());
        String log = text.toString("UTF-8");
        assertTrue(log.contains("every figure step 7's to the bit"), log);
        r.commit("the page look");
        int lines = r.ledgerLines();
        assertTrue(Files.isRegularFile(out.resolve("replay").resolve("run.id")));
        assertTrue(Files.isRegularFile(out.resolve("replay").resolve("replayed-part-0-2-folds.ckpt")), "F1's record kept");
        assertFalse(Files.exists(out.resolve("export-full")));

        // Resumed under the same classes: as before, into replay, F1's record read back and the control's item
        // replayed, every output written, no second line.
        PageLook.Options same = options(k, k.saved, out, true);
        same.hooks.add(new Interrupt(-1));
        text.reset();
        PageLook.Made made = PageLook.run(same, "test", new PrintStream(text, true, "UTF-8"));
        log = text.toString("UTF-8");
        assertEquals(lines, r.ledgerLines(), "a resumed look writes no line");
        assertFalse(made.otherClasses);
        assertEquals(out.resolve("replay"), made.replayInto);
        assertTrue(log.contains("FullIndexRun replay test, resumed: ") && log.contains("1 of 2 items were replayed before"),
                log);
        KeyEdges.Collector c = KeyEdges.Collector.read(made.runC);
        assertTrue(c.has("F1", 2) && c.has("Hi-Lo = Hi-Lo Lite", 2), "run C holds the registered rows' values");
        WideReport.Saved w = WideReport.read(made.wideFirst);
        assertEquals(List.of(), WideReport.check(w));
        assertTrue(w.rows.stream().anyMatch(x -> x.name.equals("F1")), "the registered rows' wide figures");
        assertTrue(Files.isRegularFile(made.exportFirst.resolve("manifest.sha256")), "the first-decision export passed");
        assertTrue(Files.readAllLines(made.exportFirst.resolve("index.tsv")).stream().anyMatch(l -> l.contains("\tF1\t80\t")));
        assertTrue(Files.isRegularFile(made.replay.resolve("wide.ser")));
        KeyEdges.Collector d = KeyEdges.Collector.read(made.runD.resolve(ReplayHooks.Sink.FILE));
        assertTrue(d.has("F1, full play", 2), "run D holds F1's full-play values");
        assertNull(made.exportFullRefused, made.exportFullRefused);
        assertTrue(Files.isRegularFile(made.exportFull.resolve("manifest.sha256")), "the full-play export passed");
        String record = new String(Files.readAllBytes(out.resolve("resume-test.txt")), StandardCharsets.UTF_8);
        assertTrue(record.contains("\nresumed ") && record.contains(" under commit test (look.txt: commit test), no "
                + "ledger line: run C and export-first written before; step 8's fit replayed into " + out.resolve("replay")
                + " (resuming its replay)") && record.contains("\nfinished "), record);

        // As the real look stood: export-full refused. Deleted here to stand for it, its files' bytes kept
        // to compare; replay's records not let go, as main did not after a refused export.
        Map<String, String> refused = new TreeMap<>();
        for (Map.Entry<String, String> x : FullIndexReplayTest.snapshot(made.exportFull).entrySet()) {
            refused.put(x.getKey(), x.getValue().split(" ")[0]);
        }
        try (java.util.stream.Stream<Path> s = Files.list(made.exportFull)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Files.delete(p);
            }
        }
        Files.delete(made.exportFull);
        Map<String, String> held = FullIndexReplayTest.snapshot(out);
        assertTrue(held.containsKey("replay/wide.ser") && held.containsKey("run-d/" + ReplayHooks.Sink.FILE)
                && held.containsKey("replay/replayed-part-0-2-folds.ckpt"), held.keySet().toString());

        // Under other classes with another step 7 save than look.txt names: refused, nothing written.
        Path moved = moved(k);
        String m = refusal(options(k, moved, out, true), r, "other");
        assertTrue(m.contains("names another look than this resume") && m.contains("  was: step 7 ")
                && m.contains("  now: step 7 " + moved.toAbsolutePath().normalize()), m);
        assertEquals(held, FullIndexReplayTest.snapshot(out), "nothing written");

        // Under other classes with run D's collector not there: refused, since such a resume writes
        // export-full alone. (Renamed aside and back, its bytes and time unchanged.)
        Path sink = out.resolve("run-d").resolve(ReplayHooks.Sink.FILE);
        Path aside = sink.resolveSibling("aside");
        Files.move(sink, aside);
        m = refusal(options(k, k.saved, out, true), r, "other");
        assertTrue(m.contains(out.resolve("replay") + " holds the replay of test, not of these classes (other)")
                && m.contains("but the look holds no " + sink + ", which only a resume under test's classes writes"), m);
        Files.move(aside, sink);
        assertEquals(held, FullIndexReplayTest.snapshot(out), "nothing written");

        // Under other classes, interrupted in its own replay: replay-other holds its run.id, and nothing else
        // the look held is touched.
        PageLook.Options o1 = options(k, k.saved, out, true);
        o1.hooks.add(new FullIndexReplayTest.RankingHook());
        o1.hooks.add(new Refits());
        o1.hooks.add(new Interrupt(0));
        e = assertThrows(IllegalStateException.class, () -> PageLook.run(o1, "other", r.nowhere));
        assertTrue(e.getMessage().startsWith("interrupted at "), e.getMessage());
        Path own = out.resolve("replay-other");
        assertTrue(Files.isRegularFile(own.resolve("run.id")));
        assertFalse(Files.exists(out.resolve("export-full")));
        assertEquals(lines, r.ledgerLines(), "a resume writes no line, interrupted or not");
        Map<String, String> interrupted = FullIndexReplayTest.snapshot(out);
        for (Map.Entry<String, String> x : held.entrySet()) {
            assertEquals(x.getValue(), interrupted.get(x.getKey()), x.getKey());
        }

        // Resumed there, it writes export-full.
        PageLook.Options o2 = options(k, k.saved, out, true);
        o2.hooks.add(new FullIndexReplayTest.RankingHook());
        Refits refits = new Refits();
        o2.hooks.add(refits);
        o2.hooks.add(new Interrupt(-1));
        text.reset();
        PageLook.Made other = PageLook.run(o2, "other", new PrintStream(text, true, "UTF-8"));
        log = text.toString("UTF-8");
        // Every file the look held, byte for byte and time for time, as it was: only replay-other,
        // export-full and the record are new.
        Map<String, String> now = FullIndexReplayTest.snapshot(out);
        for (Map.Entry<String, String> x : held.entrySet()) {
            assertEquals(x.getValue(), now.get(x.getKey()), x.getKey());
        }
        for (String f : now.keySet()) {
            assertTrue(held.containsKey(f) || f.startsWith("replay-other/") || f.startsWith("export-full/")
                    || f.equals("resume-other.txt"), f);
        }
        assertEquals(lines, r.ledgerLines(), "a resume under other classes writes no line");
        assertTrue(other.otherClasses);
        assertEquals(own, other.replayInto);
        assertEquals(own, other.full.out);
        assertTrue(log.contains("FullIndexRun replay other, resumed: ") && log.contains("1 items, the fold fits only")
                && log.contains("hooks export, step 8 to the bit, ranking, refits, interrupt;"), log);
        assertTrue(new String(Files.readAllBytes(own.resolve("run.id")), StandardCharsets.UTF_8)
                .contains("\nitems: the fold fits only, only F1@2\n"));
        assertFalse(Files.exists(own.resolve("wide.ser")), "no wide plans in its replay");

        // export-full: the bundle the look's own replay wrote, file for file and byte for byte.
        Map<String, String> wrote = new TreeMap<>();
        for (Map.Entry<String, String> x : FullIndexReplayTest.snapshot(other.exportFull).entrySet()) {
            wrote.put(x.getKey(), x.getValue().split(" ")[0]);
        }
        assertEquals(refused, wrote, "export-full is the bundle the look's replay wrote");
        record = new String(Files.readAllBytes(out.resolve("resume-other.txt")), StandardCharsets.UTF_8);
        assertTrue(record.startsWith("PageLook's resumes under these classes"), record);
        assertEquals(2, record.split("\nresumed ", -1).length - 1, record);
        assertTrue(record.contains(" under commit other (look.txt: commit test), no ledger line: "
                + out.resolve("replay") + " holds the replay of test, kept as it is") && record.contains("into " + own
                + " (a new replay), items F1@2, hooks export and Step8") && record.contains("\nstopped ")
                && record.contains("interrupted at ") && record.contains("into " + own + " (resuming its interrupted "
                + "replay)") && record.contains("\nfinished ") && record.contains(": wrote " + other.exportFull
                + " (manifest.sha256's SHA-256 " + KeyEdgesRun.sha256(other.exportFull.resolve("manifest.sha256"))
                + "), from the replay in " + own + " (run.id, replay.txt, perfect.ser and 1 records); every other file "
                + "of " + out + " as it was"), record);

        // The full-play export passes. This fixture's F1 full play ranks deals two ways (INTEGRATION.md
        // open item 1): its split hands were valued under the walked play's ranking where the dealt
        // hand plays the refit's, so its files carry split lines (SHOE_SPEC.md B2.4), and lookups.tsv
        // carries B8's 1,000 split lines. The export's round trip played every split the last round
        // valued again from the files.
        assertNull(other.exportFullRefused, other.exportFullRefused);
        assertTrue(Files.isRegularFile(other.exportFull.resolve("manifest.sha256")), "the full-play export passed");
        java.util.regex.Matcher played = java.util.regex.Pattern.compile("export: F1, full play at 80% checked on its "
                + "values, \\d+ values compared, (\\d+) splits the last round valued played again").matcher(log);
        assertTrue(played.find() && Integer.parseInt(played.group(1)) > 0, log);
        // Each full file held to the strategies the replay scored, apart from Refit: its first lines
        // the replayed fit's ranking whole, the ranking its split hands read the strategy's walked
        // one as the ranking hook kept it, and a split line wherever the two differ: 31 in fold 0,
        // whose fit stopped at its pass cap, and 1 in fold 1, which converged.
        ScoringStates st = FullIndexPilot.load(r.main, SHOES);
        int[] differ = new int[2];
        int fullFiles = 0;
        for (int x = 0; x < other.full.items.size(); x++) {
            FullIndexReplay.Record rec = other.full.record(x);
            @SuppressWarnings("unchecked")
            List<PlayerMove[][][]> walked = (List<PlayerMove[][][]>) rec.kept.get("ranking");
            for (SystemScorer.Entry row : rec.result.fullEntries) {
                if (!row.name.equals("F1, full play")) {
                    continue;
                }
                assertEquals(2, rec.item.pen);
                for (int f = 0; f < 2; f++) {
                    ExportBundle.Strategy s = ExportBundle.readStrategy(other.exportFull.resolve(
                            ExportBundle.Strategy.fileName(row.name, 80, f)));
                    PlayerMove[][][] ranked = FullIndexFit.rankings(st, StateRun.montreal(), rec.result.fullFirst[2][f]);
                    assertEquals(ranked.length, s.lo.length);
                    for (int g = 0; g < s.lo.length; g++) {
                        for (int i = 0; i < Deals.COUNT; i++) {
                            if (Deals.playerNatural(i)) {
                                continue;
                            }
                            assertArrayEquals(ranked[g][i], s.first[g][i], "fold " + f + ", first " + g + " " + i);
                            assertArrayEquals(walked.get(f)[g][i], s.splitRanking(g, i), "fold " + f + ", split hands' "
                                    + g + " " + i);
                            differ[f] += Arrays.equals(ranked[g][i], walked.get(f)[g][i]) ? 0 : 1;
                        }
                    }
                    assertEquals(differ[f], s.splitLines(), "fold " + f);
                    fullFiles++;
                }
            }
        }
        assertEquals(2, fullFiles, "F1's full play at 80%, both folds");
        assertArrayEquals(new int[]{31, 1}, differ, "the cells F1's folds rank two ways");
        assertTrue(log.contains("export: F1 at 80%, fold 0: 31 split lines, the first group 0, deal 8 8 1: S H D P by the "
                + "refit (its first line), P S H D by the walked play (its split line)\n"), log);
        assertTrue(log.contains("export: F1 at 80%, fold 1: 1 split line, the first group 0, deal 10 10 10: S P H D by "
                + "the refit (its first line), S H D P by the walked play (its split line)\n"), log);
        assertTrue(log.contains("  F1 80% fold 0: 6 passes, stopped at the pass cap of 6, pass 0's play kept, 1 clean-ups "
                + "(1 later cells flipped); refitDiffers 29\n"), log);
        assertTrue(log.contains("  F1 80% fold 1: 4 passes, converged; refitDiffers 0\n"), log);
        long splitLines = ExportBundle.readLookups(other.exportFull.resolve("lookups.tsv"), true).lines.stream()
                .filter(l -> l.split).count();
        assertEquals(1000, splitLines);

        // On these values, as the export checks a row: as written it passes. With fold 0's split
        // hands given the refit's ranking, as before the split lines (its walked ranking set to the
        // refit's, so no split line is written and every check that reads Refit.walked agrees), it
        // is refused, by the splits the last round valued, played again from the file.
        assertTrue(checked(refits, refits.refit[0]).splits > 0);
        StrategyExport.Refit one = refits.refit[1];
        one.walked(0, one.ranking[0]);
        assertEquals(0, one.rankingDiffers[0]);
        IllegalStateException x = assertThrows(IllegalStateException.class, () -> checked(refits, one));
        assertTrue(x.getMessage().contains("F1, full play at 80%, fold 0: a split of ") && x.getMessage().contains(
                "and were valued playing"), x.getMessage());

        // The look done, its new replay's records are let go, there and not in replay.
        PageLook.letGo(other, r.nowhere);
        assertTrue(Files.isRegularFile(own.resolve(FullIndexReplay.LET_GO)));
        assertFalse(Files.exists(own.resolve("replayed-part-0-2-folds.ckpt")));
        now = FullIndexReplayTest.snapshot(out);
        for (Map.Entry<String, String> y : held.entrySet()) {
            assertEquals(y.getValue(), now.get(y.getKey()), y.getKey());
        }
        record = new String(Files.readAllBytes(out.resolve("resume-other.txt")), StandardCharsets.UTF_8);
        assertTrue(record.contains("\nrecords let go ") && record.contains(": 1 records of " + own + ", "), record);

        // Resumed again under those classes: export-full is there, nothing is left to write.
        held = FullIndexReplayTest.snapshot(out);
        m = refusal(options(k, k.saved, out, true), r, "other");
        assertTrue(m.contains("export-full is written already"), m);
        assertEquals(held, FullIndexReplayTest.snapshot(out), "nothing written");

        // The ledger holds the page look: a second one is refused, before any line.
        Path again = dir.resolve("runs").resolve("again");
        m = refusal(options(k, k.saved, again, false), r, "other");
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
