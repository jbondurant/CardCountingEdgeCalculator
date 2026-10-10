import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The leakage guards of COUNT_DESIGN.md, sections 5 and 6, at the tools themselves rather than at
 * CustomCounts' helpers: each search, calibration and theory-pass tool refuses the main state file
 * before it reads a state or writes a file; each main-file tool refuses a candidates file, a
 * registration not committed as it stands, and a ledger with lines not committed, writing
 * nothing; and given all committed, a main-file run names the registration's blob in what it
 * writes and appends exactly one line to the ledger. So removing any tool's call to the guards
 * fails here.
 */
public class MainFileLookTest {

    /** A registration of one headline count, Hi-Lo's tags at K, reported in every cell. */
    static final String ONE = "{ \"kind\": \"registered\", \"K\": 10000, \"twin\": \"twin, seed 20261007\", "
            + "\"searchCommit\": \"abc\", \"cells\": {\"flat\": [\"F1\"], \"same\": [\"F1\"], \"own\": [\"F1\"]}, "
            + "\"counts\": [ { \"name\": \"F1\", \"family\": \"F1\", \"role\": \"headline\", \"form\": \"true\", "
            + "\"divisor\": 2500, \"tags\": [-10000, 10000, 10000, 10000, 10000, 10000, 0, 0, 0, -10000] } ] }";

    private static List<String> files(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    private static void refusesTheMainFile(Executable tool) {
        IllegalStateException e = assertThrows(IllegalStateException.class, tool::run);
        assertTrue(e.getMessage().contains("main state file"), e.getMessage());
    }

    interface Executable {
        void run() throws Exception;
    }

    /**
     * CountSearch in each form, CountRegression, SplitMargin and ComparisonReport with a candidates
     * file refuse a state file whose header seed is the main file's, and write nothing: the
     * directory holds what it held before, no search log.
     */
    @Test
    public void theSearchAndTheoryToolsRefuseTheMainFileThemselves(@TempDir Path dir) throws Exception {
        Path main = CustomCountsTest.stateFile(dir, CustomCounts.MAIN_SEED);
        Path candidates = CustomCountsTest.write(dir, "candidates.json", CustomCountsTest.CANDIDATES);
        Path log = dir.resolve("search.tsv");
        List<String> before = files(dir);
        for (String form : new String[]{"F1", "F2", "F1r"}) {
            refusesTheMainFile(() -> CountSearch.main(new String[]{"--states", main.toString(), "--log", log.toString(),
                    "--form", form, "--start-count", "Hi-Lo", "--divisor", "500", "--max-evals", "12"}));
        }
        refusesTheMainFile(() -> CountRegression.main(new String[]{main.toString(), "10", "1"}));
        refusesTheMainFile(() -> SplitMargin.main(new String[]{main.toString(), "1", "1"}));
        ComparisonReport.Options o = new ComparisonReport.Options();
        o.counts = candidates;
        o.resamples = 2;
        refusesTheMainFile(() -> ComparisonReport.run(main, o, new PrintStream(new ByteArrayOutputStream(), true, "UTF-8")));
        assertEquals(before, files(dir), "nothing written");
    }

    /**
     * In a git repository of its own: FullIndexRun, FullIndexDryRun and CeilingReport on a main-seed
     * file refuse a candidates file, a registration not committed, and a ledger with a line not
     * committed, each before writing anything; given the registration and the ledger committed,
     * FullIndexRun's results name the registration's blob and each run appends exactly one line to
     * the ledger, which must be committed before the next.
     */
    @Test
    public void aMainFileLookNeedsTheRegistrationAndTheLedgerCommitted(@TempDir Path repo) throws Exception {
        assertNotNull(CustomCounts.git(repo, "init", "-q"));
        CustomCounts.git(repo, "config", "user.email", "test@example.com");
        CustomCounts.git(repo, "config", "user.name", "test");
        Path data = Files.createDirectories(repo.resolve("runs"));
        Path main = ComparisonReportTest.syntheticRun(data, CustomCounts.MAIN_SEED, CustomCounts.MAIN_SEED, 6);
        ScoringStates st = FullIndexPilot.load(main, StateStore.shoesDone(main));
        int resamples = CeilingReportTest.RESAMPLES;
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, resamples, Bootstrap.SEED), SystemScorer.M_PLAY);
        Path ser = CeilingReportTest.results(data, main, st, CeilingReportTest.published(sc));
        Path printed = CeilingReportTest.printedReport(data, main);
        Path candidates = CustomCountsTest.write(repo, "candidates.json", CustomCountsTest.CANDIDATES);
        Path counts = CustomCountsTest.write(repo, CustomCounts.REGISTERED, ONE);
        Path ledger = repo.resolve(CustomCounts.LEDGER);
        Files.write(ledger, (CustomCounts.LEDGER_HEADER + "\n").getBytes(StandardCharsets.UTF_8));
        PrintStream nowhere = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        Path out = repo.resolve("run");
        String[] run = {"--states", main.toString(), "--out", out.toString(), "--counts", counts.toString(), "--pens", "2",
                "--resamples", "3", "--threads", "2", "--trees", "none"};
        Executable fullIndex = () -> FullIndexRun.run(run, "test", nowhere, 0);
        Executable dryRun = () -> FullIndexDryRun.main(new String[]{"--counts", counts.toString(), "--pens", "2",
                main.toString(), "6", "3", "2", repo.resolve("dry").toString()});
        Path save = repo.resolve("ceiling.ser");
        Executable report = () -> CeilingReport.run(main, ser, printed, counts, save, resamples, 2, "test", nowhere);

        // A candidates file is refused by every main-file tool.
        for (Executable tool : new Executable[]{
                () -> FullIndexRun.run(new String[]{"--states", main.toString(), "--out", out.toString(), "--counts",
                        candidates.toString(), "--pens", "2", "--resamples", "3"}, "test", nowhere, 0),
                () -> FullIndexDryRun.main(new String[]{"--counts", candidates.toString(), main.toString(), "6", "3", "2",
                        repo.resolve("dry").toString()}),
                () -> CeilingReport.run(main, ser, printed, candidates, save, resamples, 2, "test", nowhere)}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, tool::run);
            assertTrue(e.getMessage().contains("candidates file"), e.getMessage());
        }
        // The registration not committed, then the ledger not committed: refused, nothing written.
        for (String what : new String[]{"not committed", CustomCounts.LEDGER}) {
            if (what.equals(CustomCounts.LEDGER)) {
                CustomCounts.git(repo, "add", CustomCounts.REGISTERED);
                CustomCounts.git(repo, "commit", "-q", "-m", "register");
            }
            for (Executable tool : new Executable[]{fullIndex, dryRun, report}) {
                IllegalStateException e = assertThrows(IllegalStateException.class, tool::run);
                assertTrue(e.getMessage().contains(what), e.getMessage());
            }
            assertFalse(Files.exists(out));
            assertFalse(Files.exists(repo.resolve("dry")));
            assertFalse(Files.exists(save));
            assertEquals(1, Files.readAllLines(ledger).size());
        }
        CustomCounts.git(repo, "add", CustomCounts.LEDGER);
        CustomCounts.git(repo, "commit", "-q", "-m", "ledger");
        String blob = CustomCounts.git(repo, "rev-parse", "HEAD:" + CustomCounts.REGISTERED);

        // Committed: each look is one line, which must be committed before the next.
        fullIndex.run();
        assertTrue(CeilingReport.read(out.resolve("results.ser")).identity.contains("blob " + blob));
        List<String> lines = Files.readAllLines(ledger);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).startsWith("test\t" + blob + "\t") && lines.get(1).contains("FullIndexRun started"),
                lines.get(1));
        IllegalStateException e = assertThrows(IllegalStateException.class, report::run);
        assertTrue(e.getMessage().contains(CustomCounts.LEDGER), e.getMessage());
        CustomCounts.git(repo, "commit", "-q", "-am", "first look");
        // A main-file look that would save nothing is refused before it is made.
        e = assertThrows(IllegalStateException.class, () -> CeilingReport.run(main, ser, printed, counts, null, resamples,
                2, "test", nowhere));
        assertTrue(e.getMessage().contains("--save"), e.getMessage());
        // And one that would not first set the stored rows beside the published report's figures.
        e = assertThrows(IllegalStateException.class, () -> CeilingReport.run(main, ser, null, counts, save, resamples,
                2, "test", nowhere));
        assertTrue(e.getMessage().contains("--printed"), e.getMessage());
        assertEquals(2, Files.readAllLines(ledger).size());
        CeilingReport.Ceiling c = CeilingReport.run(main, ser, printed, counts, save, resamples, 2, "test", nowhere);
        assertTrue(CeilingReport.readSaved(save).identity.contains("blob " + blob));
        assertEquals(blob, c.blob);
        lines = Files.readAllLines(ledger);
        assertEquals(3, lines.size());
        assertTrue(lines.get(2).contains("\t" + blob + "\t") && lines.get(2).contains("ComparisonReport --published"),
                lines.get(2));
    }

    /** A git repository of its own with a main-seed state file, the stored rows, the printed report and a registration. */
    static final class Repo {
        Path dir;
        Path main;
        Path ser;
        Path printed;
        Path counts;
        Path ledger;
        final int resamples = CeilingReportTest.RESAMPLES;
        final PrintStream nowhere;

        Repo() throws Exception {
            nowhere = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        }

        int ledgerLines() throws Exception {
            return Files.readAllLines(ledger).size();
        }

        void commit(String message) {
            CustomCounts.git(dir, "add", "-A");
            CustomCounts.git(dir, "commit", "-q", "-m", message);
        }
    }

    /** The repository, its registration written (as REGISTERED) beside a ledger of its header alone, nothing committed. */
    static Repo repo(Path dir, String registration) throws Exception {
        Repo r = new Repo();
        r.dir = dir;
        assertNotNull(CustomCounts.git(dir, "init", "-q"));
        CustomCounts.git(dir, "config", "user.email", "test@example.com");
        CustomCounts.git(dir, "config", "user.name", "test");
        Path data = Files.createDirectories(dir.resolve("runs"));
        Files.write(dir.resolve(".gitignore"), "runs/\n*.ser\nout*/\ndry*/\n".getBytes(StandardCharsets.UTF_8));
        r.main = ComparisonReportTest.syntheticRun(data, CustomCounts.MAIN_SEED, CustomCounts.MAIN_SEED, 6);
        ScoringStates st = FullIndexPilot.load(r.main, StateStore.shoesDone(r.main));
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, r.resamples, Bootstrap.SEED), SystemScorer.M_PLAY);
        r.ser = CeilingReportTest.results(data, r.main, st, CeilingReportTest.published(sc));
        r.printed = CeilingReportTest.printedReport(data, r.main);
        r.counts = CustomCountsTest.write(dir, CustomCounts.REGISTERED, registration);
        r.ledger = CustomCountsTest.write(dir, CustomCounts.LEDGER, CustomCounts.LEDGER_HEADER + "\n");
        return r;
    }

    private static String[] with(String[] a, String... more) {
        String[] out = java.util.Arrays.copyOf(a, a.length + more.length);
        System.arraycopy(more, 0, out, a.length, more.length);
        return out;
    }

    /**
     * A run refused by a check of its own costs no look: each main-file tool writes its ledger line
     * only once every refusal is behind. FullIndexRun and FullIndexDryRun check the registration
     * and the ledger before reading a state, and refuse a store they cannot read, a disk without room
     * for a new store, a work directory they cannot make and a heap too small for the run, each
     * before the line; CeilingReport refuses a save file whose directory is missing, a full disk and
     * a stale FILE.tmp before it. The ledger is left as committed after every one.
     */
    @Test
    public void aRefusedRunCostsNoLook(@TempDir Path dir) throws Exception {
        Repo r = repo(dir, ONE);
        java.util.concurrent.Callable<String> commit = () -> "test";
        // The registration is checked before a state is read: for a run that could not read the states
        // (more shoes than the file holds) the refusal is still the registration's.
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> FullIndexRun.run(new String[]{
                "--states", r.main.toString(), "--out", dir.resolve("out0").toString(), "--counts", r.counts.toString(),
                "--shoes", "99", "--pens", "2", "--resamples", "3", "--trees", "none"}, "test", r.nowhere, 0));
        assertTrue(e.getMessage().contains("not committed"), e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> FullIndexDryRun.dryRun(new String[]{"--counts",
                r.counts.toString(), "--pens", "2", r.main.toString(), "99", "3", "2", dir.resolve("dry0").toString()},
                r.nowhere, commit, 0.8));
        assertTrue(e.getMessage().contains("not committed"), e.getMessage());
        r.commit("register");
        assertEquals(1, r.ledgerLines());

        String[] run = {"--states", r.main.toString(), "--counts", r.counts.toString(), "--pens", "2", "--resamples", "3",
                "--threads", "2"};
        // FullIndexRun: a read-only store that is not there, a new store without room on disk, a heap too small.
        IOException io = assertThrows(IOException.class, () -> FullIndexRun.run(with(run, "--out",
                dir.resolve("out1").toString(), "--trees-read-only", dir.resolve("no-store.bin").toString()), "test",
                r.nowhere, 0));
        assertTrue(io.getMessage().contains("no tree store"), io.getMessage());
        assertEquals(1, r.ledgerLines(), "a missing read-only store costs no look");
        io = assertThrows(IOException.class, () -> FullIndexRun.run(with(run, "--out", dir.resolve("out2").toString()),
                "test", r.nowhere, Long.MAX_VALUE / 4));
        assertTrue(io.getMessage().contains("more on disk"), io.getMessage());
        assertEquals(1, r.ledgerLines(), "a disk without room for the trees costs no look");
        e = assertThrows(IllegalStateException.class, () -> FullIndexRun.run(with(run, "--out",
                dir.resolve("out3").toString(), "--trees", "none"), "test", r.nowhere, 0, 1e-9));
        assertTrue(e.getMessage().contains("would not fit in the heap"), e.getMessage());
        assertEquals(1, r.ledgerLines(), "a heap too small costs no look");

        // FullIndexDryRun: states it cannot read, a work directory it cannot make, a heap too small.
        assertThrows(IllegalArgumentException.class, () -> FullIndexDryRun.dryRun(new String[]{"--counts",
                r.counts.toString(), "--pens", "2", r.main.toString(), "99", "3", "2", dir.resolve("dry1").toString()},
                r.nowhere, commit, 0.8));
        assertEquals(1, r.ledgerLines(), "states it cannot read cost no look");
        Path notADirectory = CustomCountsTest.write(dir, "runs/work-file", "not a directory");
        assertThrows(IOException.class, () -> FullIndexDryRun.dryRun(new String[]{"--counts", r.counts.toString(),
                "--pens", "2", r.main.toString(), "6", "3", "2", notADirectory.toString()}, r.nowhere, commit, 0.8));
        assertEquals(1, r.ledgerLines(), "a work directory it cannot make costs no look");
        e = assertThrows(IllegalStateException.class, () -> FullIndexDryRun.dryRun(new String[]{"--counts",
                r.counts.toString(), "--pens", "2", r.main.toString(), "6", "3", "2", dir.resolve("dry3").toString()},
                r.nowhere, commit, 1e-9));
        assertTrue(e.getMessage().contains("would not fit in the heap"), e.getMessage());
        assertEquals(1, r.ledgerLines(), "a heap too small costs the dry run no look");

        // CeilingReport: a save file it could not write at its end.
        Path lost = dir.resolve("no-such-directory").resolve("ceiling.ser");
        e = assertThrows(IllegalStateException.class, () -> CeilingReport.run(r.main, r.ser, r.printed, r.counts, lost,
                r.resamples, 2, "test", r.nowhere));
        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
        Path save = dir.resolve("ceiling.ser");
        e = assertThrows(IllegalStateException.class, () -> CeilingReport.run(r.main, r.ser, r.printed, r.counts, save,
                r.resamples, 2, "test", r.nowhere, false, Long.MAX_VALUE));
        assertTrue(e.getMessage().contains("free"), e.getMessage());
        Path tmp = Files.createFile(dir.resolve("ceiling.ser.tmp"));
        e = assertThrows(IllegalStateException.class, () -> CeilingReport.run(r.main, r.ser, r.printed, r.counts, save,
                r.resamples, 2, "test", r.nowhere));
        assertTrue(e.getMessage().contains("ceiling.ser.tmp"), e.getMessage());
        Files.delete(tmp);
        assertEquals(1, r.ledgerLines(), "a save file it could not write costs no look");
        assertEquals("", CustomCounts.git(dir, "status", "--porcelain", "--", CustomCounts.LEDGER));
        assertFalse(Files.exists(save));
        assertFalse(Files.exists(lost.getParent()));
    }

    /**
     * Once the ledger holds a look at the registration, a changed registration, committed, is a
     * second look: every main-file tool refuses it, writing nothing, unless a second look is asked
     * for (--second-look); then the ledger line and the report say so, marking the earlier lines.
     */
    @Test
    public void aLookAtAChangedRegistrationIsASecondLook(@TempDir Path dir) throws Exception {
        Repo r = repo(dir, ONE);
        r.commit("register");
        CeilingReport.run(r.main, r.ser, r.printed, r.counts, dir.resolve("first.ser"), r.resamples, 2, "test", r.nowhere);
        assertEquals(2, r.ledgerLines());
        r.commit("first look");
        Files.write(r.counts, ONE.replace("\"abc\"", "\"abd\"").getBytes(StandardCharsets.UTF_8));
        r.commit("a changed registration");

        Path save = dir.resolve("second.ser");
        for (Executable tool : new Executable[]{
                () -> FullIndexRun.run(new String[]{"--states", r.main.toString(), "--out", dir.resolve("out").toString(),
                        "--counts", r.counts.toString(), "--pens", "2", "--resamples", "3", "--trees", "none"}, "test",
                        r.nowhere, 0),
                () -> FullIndexDryRun.dryRun(new String[]{"--counts", r.counts.toString(), "--pens", "2",
                        r.main.toString(), "6", "3", "2", dir.resolve("dry").toString()}, r.nowhere, () -> "test", 0.8),
                () -> CeilingReport.run(r.main, r.ser, r.printed, r.counts, save, r.resamples, 2, "test", r.nowhere)}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, tool::run);
            assertTrue(e.getMessage().contains("second look") && e.getMessage().contains("--second-look"), e.getMessage());
        }
        assertEquals(2, r.ledgerLines());
        assertFalse(Files.exists(save));
        assertFalse(Files.exists(dir.resolve("out")));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CeilingReport.run(r.main, r.ser, r.printed, r.counts, save, r.resamples, 2, "test",
                new PrintStream(bytes, true, "UTF-8"), true, CeilingReport.SAVE_MARGIN);
        List<String> lines = Files.readAllLines(r.ledger);
        assertEquals(3, lines.size());
        assertTrue(lines.get(2).contains("A SECOND LOOK"), lines.get(2));
        String report = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(report.contains("A SECOND LOOK: 1 line is a look at another registration"), report);
        assertTrue(report.contains(lines.get(1) + "\t<- ANOTHER REGISTRATION: A SECOND LOOK"), report);
    }

    /**
     * Each registration goes to its own tool on the main file: the full-play registration is not the
     * ceiling report's, and a registration that names a full-play one is not the full-index run's;
     * each is refused before anything is written.
     */
    @Test
    public void eachRegistrationGoesToItsOwnTool(@TempDir Path dir) throws Exception {
        Repo r = repo(dir, ONE);
        Path full = CustomCountsTest.write(dir, CustomCounts.FULL_PLAY, CustomCountsTest.fullPlay("abc"));
        String fullBlob = CustomCounts.git(dir, "hash-object", "--", CustomCounts.FULL_PLAY);
        Files.write(r.counts, ONE.replace("\"cells\":", "\"fullPlay\": {\"file\": \"" + CustomCounts.FULL_PLAY
                + "\", \"blob\": \"" + fullBlob + "\"}, \"cells\":").getBytes(StandardCharsets.UTF_8));
        r.commit("register both");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CeilingReport.run(r.main, r.ser,
                r.printed, full, dir.resolve("c.ser"), r.resamples, 2, "test", r.nowhere));
        assertTrue(e.getMessage().contains("full-play registration"), e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> FullIndexRun.run(new String[]{"--states", r.main.toString(),
                "--out", dir.resolve("out").toString(), "--counts", r.counts.toString(), "--pens", "2", "--resamples", "3",
                "--trees", "none"}, "test", r.nowhere, 0));
        assertTrue(e.getMessage().contains("--counts " + CustomCounts.FULL_PLAY), e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> FullIndexDryRun.dryRun(new String[]{"--counts",
                r.counts.toString(), "--pens", "2", r.main.toString(), "6", "3", "2", dir.resolve("dry").toString()},
                r.nowhere, () -> "test", 0.8));
        assertTrue(e.getMessage().contains("--counts " + CustomCounts.FULL_PLAY), e.getMessage());
        assertEquals(1, r.ledgerLines());
        assertFalse(Files.exists(dir.resolve("out")));
        assertFalse(Files.exists(dir.resolve("c.ser")));
        assertEquals(fullBlob, CustomCounts.checkOnMain(CustomCounts.read(full)));
    }
}
