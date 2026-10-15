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
 * summaries, RiskReport.readSwings on swings.tsv). Until site-fix-sim's 78cd388, merged into
 * site-integration on 14 October 2026, the simulator's p3 stopped at its end on any bundle without
 * a figure that keeps paths (F27, F29), part 1's among them, because P3.writeRecords asked for a
 * records part of every chunk although none is written when no figure keeps records
 * ("records-000000000000.part is missing", 13 October 2026), and this half was disabled. Enabled
 * with the merge; with writeRecords's early return taken out of a scratch copy of the merged
 * simulator (never committed), it fails again with that message.
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
     * Row 5's gate across the two programs (SIM_CHECK.md section 8): the simulator's blind pilot of
     * part 1 on the engine's bundle, at 1% of tiny sizes (sim/test/PilotTest's), writes
     * sim-pilot-part1.tsv, and the reader `SimCheckPlan fill` uses takes every line of it, in the
     * units fill prints (a one-unit bet: the chart flat's SD a round near section 8's assumed 1.16).
     * The bundle holds only some of part 1's figures, so the pilot names the others and exits 1, and
     * the reader then refuses the file for those alone; the lines it does hold come in the
     * registry's order.
     */
    @Test
    public void fillReadsThePilotsFile() throws Exception {
        Path pilot = dir.resolve("pilot");
        Path log = dir.resolve("pilot.log");
        int exit = sim(log, "pilot", "--part", "1", "--bundle", bundle.toString(), "--out", pilot.toString(),
                "--sim-commit", "fixture", "--registry", "SIM_CHECK.md", "--catalog", "counting-systems.json",
                "--threads", "1", "--chunks", "2", "--chunk-size", "600", "--k", "2000", "--planted-k", "2000",
                "--fan-paths", "2", "--fan-rounds", "30000", "--ruin-paths", "100", "--scale", "100");
        String said = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        Path file = pilot.resolve("sim-pilot-part1.tsv");
        assertTrue(Files.isRegularFile(file), said);
        assertEquals(1, exit, "the bundle lacks most of part 1's figures: " + said);
        List<String> want = new ArrayList<>();
        for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
            if (f.part == 1) {
                want.add(f.id);
            }
        }
        List<String> sd = new ArrayList<>();
        for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] t = l.split("\t", -1);
            if (t[0].equals("sd")) {
                sd.add(t[1]);
                if (t[1].equals("F03")) {
                    double v = Double.parseDouble(t[2]);
                    assertTrue(v > 1.16 / 2 && v < 1.16 * 2, "F03's SD a round " + v + ", in a one-unit bet");
                }
            }
        }
        assertTrue(sd.containsAll(List.of("F03", "F18")), sd.toString());
        List<String> inOrder = new ArrayList<>(want);
        inOrder.retainAll(sd);
        assertEquals(inOrder, sd, "the pilot's sd lines in the registry's order");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> SimCheckPlan.Pilot.read(file));
        assertTrue(refused.getMessage().startsWith("the pilot's sd lines must be, in order, " + want), refused.getMessage());
    }

    /**
     * What the simulator wrote, read by the engine side: SiteSim takes the two summaries, the
     * chart flat at 80% and Hi-Lo's 1-8 at 80% among the registered figures compared, and
     * RiskReport's reader takes swings.tsv with those two figures' swings.
     */
    @Test
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
        // The reported figures' readers (SiteSimReported) on the simulator's own depth.tsv and
        // ev-by-key.tsv, as sim/QUESTIONS.md item 27's sim-formats block and SHOE_SPEC.md C4 give them:
        // depth.tsv's header as the reader expects it, its 13 bands of 26 cards with the exact side from
        // the chart's section, and a line of ev-by-key.tsv read for every row and key, each fold's mean.
        SiteInput reported = new SiteInput("sim", "fixture", "main");
        SiteSimReported.depth(reported, o);
        SiteSimReported.evByKey(reported, o);
        List<Object> depth = (List<Object>) reported.tables.get("sim_depth");
        assertNotNull(depth, "depth.tsv read with its exact side");
        assertEquals(13, depth.size());
        List<Object> byKey = (List<Object>) reported.tables.get("sim_ev_by_key");
        assertNotNull(byKey, "ev-by-key.tsv read");
        assertFalse(byKey.isEmpty());
        for (Object r : byKey) {
            assertTrue(((Map<String, Object>) r).keySet().stream().anyMatch(k -> k.startsWith("sim")), r.toString());
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
