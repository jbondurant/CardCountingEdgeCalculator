import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
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
        Path main = ComparisonReportTest.syntheticRun(data, 81, CustomCounts.MAIN_SEED, 6);
        ScoringStates st = FullIndexPilot.load(main, StateStore.shoesDone(main));
        int resamples = CeilingReportTest.RESAMPLES;
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, resamples, Bootstrap.SEED), SystemScorer.M_PLAY);
        Path ser = CeilingReportTest.results(data, main, st, CeilingReportTest.published(sc));
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
        Executable report = () -> CeilingReport.run(main, ser, counts, save, resamples, 2, "test", nowhere);

        // A candidates file is refused by every main-file tool.
        for (Executable tool : new Executable[]{
                () -> FullIndexRun.run(new String[]{"--states", main.toString(), "--out", out.toString(), "--counts",
                        candidates.toString(), "--pens", "2", "--resamples", "3"}, "test", nowhere, 0),
                () -> FullIndexDryRun.main(new String[]{"--counts", candidates.toString(), main.toString(), "6", "3", "2",
                        repo.resolve("dry").toString()}),
                () -> CeilingReport.run(main, ser, candidates, save, resamples, 2, "test", nowhere)}) {
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
        e = assertThrows(IllegalStateException.class, () -> CeilingReport.run(main, ser, counts, null, resamples, 2,
                "test", nowhere));
        assertTrue(e.getMessage().contains("--save"), e.getMessage());
        assertEquals(2, Files.readAllLines(ledger).size());
        CeilingReport.Ceiling c = CeilingReport.run(main, ser, counts, save, resamples, 2, "test", nowhere);
        assertTrue(CeilingReport.readSaved(save).identity.contains("blob " + blob));
        assertEquals(blob, c.blob);
        lines = Files.readAllLines(ledger);
        assertEquals(3, lines.size());
        assertTrue(lines.get(2).contains("\t" + blob + "\t") && lines.get(2).contains("ComparisonReport --published"),
                lines.get(2));
    }
}
