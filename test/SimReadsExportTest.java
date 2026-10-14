import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two programs meet on a bundle (PLAN_COMPUTE.md 1.2; SIM_CHECK.md section 5). StrategyExport
 * writes one from StrategyExportTest's fixture: the states of a run dealt as runs deal them, with
 * made-up values, scored for the null count and four catalog counts with their betting variants,
 * every first-decision and betting-only row at 70, 75 and 80%, the chart's row, keys-sample.tsv
 * for the whole catalog as the command line writes it. The clean-room simulator, run as its own
 * program on its own classes alone, reads it: p2's exact checks, which hold nothing that depends
 * on the made-up values (D1 the deal and the valued set, D2 the keys, D4 the keys and bets at
 * every state, D5 the lookups), must all pass, and P2 then runs at a small K and writes its
 * summary with each registered figure named. The z values mean nothing here, the values being
 * made up; part 1 of the real check is what tests them.
 *
 * Then p3 on one small chunk, and the engine side's readers on what it wrote (SiteSim on both
 * summaries, RiskReport.readSwings on swings.tsv). Disabled: the simulator's p3 stops at its end
 * on any bundle without a figure that keeps paths (F27, F29), part 1's among them, because
 * P3.writeRecords asks for a records part of every chunk although none is written when no figure
 * keeps records ("records-000000000000.part is missing", 13 October 2026). The fix is the
 * clean-room session's (sim/); enable this test with it. With it mended in a scratch copy of the
 * simulator (never committed), both halves passed.
 *
 * Planted on 13 October 2026, in scratch copies, never committed. On the engine side, each was
 * refused by the export's own round trip before the simulator ran, so this class failed in its
 * setup: states.bin's header seed one off; play keys over the decks left before the round's seen
 * cards; and Omega II's bet key one up, a count no row exports, so only keys-sample.tsv carries
 * it. On the simulator side, its key rounding moved from a half to 0.4: D2, D4 and D5 failed
 * (46,019, 2,694 and 53,196 mismatches), and theSimulatorsExactChecksPass... with them.
 */
public class SimReadsExportTest {

    @TempDir
    static Path dir;
    static Path bundle;
    static Path out;
    static String p2log;
    static int p2exit;

    /** The simulator as its own program: a JVM of its own, its classes alone on the class path. */
    static int sim(Path log, String... args) throws Exception {
        Path classes = Paths.get(sim.Sim.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> cmd = new ArrayList<>(Arrays.asList(Paths.get(System.getProperty("java.home"), "bin", "java")
                .toString(), "-Xmx512m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "-cp", classes.toString(),
                "sim.Sim"));
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).directory(new File(".")).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        assertTrue(p.waitFor(10, TimeUnit.MINUTES), "the simulator did not finish: " + String.join(" ", cmd));
        return p.exitValue();
    }

    @BeforeAll
    static void exportAndSimulate() throws Exception {
        Path states = StrategyExportTest.syntheticRun(dir);
        StateStore.Header header = StateStore.header(states);
        ScoringStates st = new ScoringStates(StateStore.readAll(states), StateStore.shoesDone(states),
                StateRun.montreal());
        SystemScorer scorer = new SystemScorer(st, new Bootstrap(st.shoes, 0, Bootstrap.SEED), 1);
        List<StrategyExport.Wanted> rows = new ArrayList<>();
        for (CountKeys.Count c : Arrays.asList(StrategyExportTest.catalog("Hi-Lo"), StrategyExportTest.catalog("Hi-Opt II"),
                StrategyExportTest.catalog("KO (Knock-Out)"), StrategyExportTest.catalog("Red Seven (Red 7)"),
                CountKeys.nullCount())) {
            SystemScorer.Result r = scorer.score(c, CountKeys.bettingVariant(c));
            for (SystemScorer.Entry e : r.entries) {
                rows.add(new StrategyExport.Wanted(r, e, 0, 1, 2));
            }
        }
        bundle = dir.resolve("bundle");
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        StrategyExport.export(bundle, scorer, header, rows, new StrategyExport.ChartRow(scorer.references().get(0), 0, 1, 2),
                StrategyExport.reportPlans().toArray(new String[0]),
                StrategyExport.keyCounts(CountKeys.catalog(), new ArrayList<>()), StrategyExport.SAMPLE_SEED, quiet);
        out = dir.resolve("sim");
        Path l2 = dir.resolve("p2.log");
        p2exit = sim(l2, "p2", "--bundle", bundle.toString(), "--out", out.toString(), "--sim-commit", "fixture",
                "--k", "20", "--threads", "1", "--catalog", "counting-systems.json", "--registry", "SIM_CHECK.md");
        p2log = new String(Files.readAllBytes(l2), StandardCharsets.UTF_8);
    }

    /**
     * Every exact check the simulator makes on the engine's bundle passes, and P2 then runs: its
     * summary names the chart flat at 80% and Hi-Lo's 1-8 at 80% by their registered ids.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void theSimulatorsExactChecksPassOnTheEnginesBundle() throws Exception {
        Path file = out.resolve("checks.txt");
        String checks = Files.isRegularFile(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "(none)";
        System.out.print("checks.txt of the simulator on the engine's bundle:\n" + checks);
        assertEquals(0, p2exit, p2log + "\nchecks.txt:\n" + checks);
        for (String d : new String[]{"D1\t", "D2\t", "D4\t", "D5\t"}) {
            assertTrue(checks.contains(d), d.trim() + " in\n" + checks);
            assertFalse(checks.contains(d + "not run"), d.trim() + " not run:\n" + checks);
        }
        Map<String, Object> p2 = SiteAdapt.read(out.resolve("summary-p2.json"));
        assertEquals("all passed", p2.get("exactChecks"));
        List<String> named = new ArrayList<>();
        for (Object c : (List<Object>) p2.get("comparisons")) {
            named.add(String.valueOf(((Map<String, Object>) c).get("fig")));
        }
        assertTrue(named.containsAll(Arrays.asList("F03", "F18")), named.toString());
    }

    /**
     * What the simulator wrote, read by the engine side: SiteSim takes the two summaries, the
     * chart flat at 80% and Hi-Lo's 1-8 at 80% among the registered figures compared, and
     * RiskReport's reader takes swings.tsv with those two figures' swings.
     */
    @Test
    @org.junit.jupiter.api.Disabled("sim.P3.writeRecords stops p3 on a bundle without F27 or F29 (see the class comment)")
    @SuppressWarnings("unchecked")
    public void theEnginesReadersTakeWhatTheSimulatorWrote() throws Exception {
        Path l3 = dir.resolve("p3.log");
        int p3exit = sim(l3, "p3", "--bundle", bundle.toString(), "--out", out.toString(), "--sim-commit", "fixture",
                "--chunks", "1", "--chunk-size", "40", "--threads", "1", "--registry", "SIM_CHECK.md");
        assertEquals(0, p3exit, new String(Files.readAllBytes(l3), StandardCharsets.UTF_8));
        // SiteSim's readers on what the simulator wrote: every z and pass held to its own figures, each
        // comparison matched to the registry, and checks.txt's D checks read as passed. The whole adapter
        // then refuses the run, as SIM_CHECK.md section 12 asks of one whose bundle the identity table
        // does not name and whose part's registered tests it does not all make.
        assertEquals(List.of(), SiteSim.failedChecks(out.resolve("checks.txt")));
        List<String> figures = new ArrayList<>();
        for (String f : new String[]{"summary-p3.json", "summary-p2.json"}) {
            for (SiteSim.Test t : SiteSim.tests(new SiteSim.Summary(out.resolve(f)))) {
                figures.add(t.check + " " + (t.fig == null ? null : t.fig.id));
            }
        }
        SiteSim.Options o = new SiteSim.Options();
        o.plan = Path.of("SIM_CHECK.md");
        o.runs.add(out);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> SiteSim.adapt(o));
        assertTrue(refused.getMessage().contains("identity table"), refused.getMessage());
        for (String f : new String[]{"P3 F03", "P2 F03", "P3 F18", "P2 F18"}) {
            assertTrue(figures.contains(f), f + " in " + figures);
        }
        Map<String, RiskReport.Swing> swings = RiskReport.readSwings(Files.readAllLines(out.resolve("swings.tsv"),
                StandardCharsets.UTF_8));
        assertTrue(swings.keySet().containsAll(Arrays.asList("F03", "F18")), swings.keySet().toString());
        for (String f : new String[]{"F03", "F18"}) {
            assertNotNull(swings.get(f).sums, f);
            assertFalse(swings.get(f).count.isEmpty(), f);
        }
    }
}
