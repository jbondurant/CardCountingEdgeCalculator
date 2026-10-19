import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two programs meet on the wide plans (INTEGRATION.md open item 20; SHOE_SPEC.md B2.5 and B3;
 * SIM_CHECK.md section 5's D4). WidePlansExportTest's bundle, the replay's export of its tiny run
 * with --wide, carries P16, B0,8, B-0.5,8, X-1.0,8 and X0,8 on one row at 80%: a bet and a chance
 * played (PLAY) per key, the exit key of the plans that leave, and states.bin's bets 0 once the
 * shoe's lowest key so far is under the exit. WidePlansExportTest holds the engine's side of that,
 * and sim/test's P2Test and P3Test the simulator's, each against the spec alone. Here the compiled
 * simulator, as its own program on its own classes (SimReadsExportTest.sim), runs p2 on that
 * bundle, so D4 recomputes every bet key and every bet under every plan, the wide plans among
 * them, at every valued state at 80%, and holds each to states.bin's to the bit.
 *
 * <p>The replay is of two counts of the test's own, and its export samples the catalog's keys
 * beside theirs (StrategyExport.ReplayExport.finish), so D2 compares every column with no mismatch
 * and no catalog count missing, D1, D4 and D5 pass, and p2 goes on to P2. Until 19 October 2026 the
 * export sampled only the counts a replay scored: D2 here reported the catalog's counts missing,
 * so p2 counted its exact checks failed and did not run P2 (sim/QUESTIONS.md item 23), and so it
 * would have on the page look's full-play bundle, a replay of the registered counts alone
 * (SimReadsSplitLinesTest).
 *
 * <p>A copy of the bundle whose X0,8 bets after the exit are written as if seated, resealed, is
 * the planted check of the test itself: D4 then counts mismatches and names X0,8. And p3 on the
 * bundle shows the chart's walk compared with no exact side, F01 to F03, which SiteSim leaves out
 * of a part 2 run (open item 17).
 */
public class SimReadsWidePlansTest {

    @TempDir
    static Path dir;
    static Path bundle;
    static Path out;
    static int p2exit;
    static String p2log;

    @BeforeAll
    static void exportAndCheck() throws Exception {
        // WidePlansExportTest's own fixture, built into this class's directory.
        WidePlansExportTest.root = dir;
        WidePlansExportTest.replayWithTheWidePlans();
        bundle = WidePlansExportTest.bundle;
        assertTrue(Files.isDirectory(bundle), WidePlansExportTest.log);
        out = dir.resolve("sim");
        Path log = dir.resolve("p2.log");
        p2exit = SimReadsExportTest.sim(log, "p2", "--bundle", bundle.toString(), "--out", out.toString(), "--sim-commit",
                "fixture", "--k", "20", "--threads", "1", "--catalog", "counting-systems.json", "--registry", "SIM_CHECK.md");
        p2log = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
    }

    /** checks.txt's line for a check. */
    static String line(Path checks, String d) throws Exception {
        for (String l : Files.readAllLines(checks, StandardCharsets.UTF_8)) {
            if (l.startsWith(d + "\t")) {
                return l;
            }
        }
        throw new AssertionError("no " + d + " line in " + checks);
    }

    /** What D4 compares (sim.P2.d4): at every valued state of each row at 80%, the bet key and the bet under each plan. */
    static long d4Comparisons(Path b) throws Exception {
        ExportBundle.States bin = ExportBundle.readStates(b.resolve("states.bin"));
        long n = 0;
        for (ExportBundle.Section sec : bin.sections) {
            if (sec.pen == 80 && !sec.row.equals(StrategyExport.CHART_ROW)) {
                n += (long) sec.index.length * (1 + sec.labels.length);
            }
        }
        return n;
    }

    @Test
    public void d4HoldsEveryWidePlanBetKeyAndBetToStatesBin() throws Exception {
        Path checks = out.resolve("checks.txt");
        assertTrue(Files.isRegularFile(checks), p2log);
        String all = new String(Files.readAllBytes(checks), StandardCharsets.UTF_8);
        System.out.print("checks.txt of the simulator on the wide plans' bundle:\n" + all);
        ExportBundle.Section row = ExportBundle.readStates(bundle.resolve("states.bin")).section(WidePlansExportTest.ROW, 80);
        assertTrue(Arrays.asList(row.labels).containsAll(WidePlansExportTest.WIDE), Arrays.toString(row.labels));
        long want = d4Comparisons(bundle);
        assertTrue(want > (long) row.index.length * WidePlansExportTest.WIDE.size(), "the wide plans are among them");
        assertEquals("D4\t" + want + " of " + want + " bet keys and bets equal to states.bin's; 0 mismatches", line(checks, "D4"),
                all);
        assertTrue(line(checks, "D1").matches("D1\t(\\d+) of \\1 records card for card .*; 0 mismatches"), all);
        Matcher d5 = Pattern.compile("D5\t(\\d+) of (\\d+) lookups .*; 0 mismatches").matcher(line(checks, "D5"));
        assertTrue(d5.matches() && d5.group(1).equals(d5.group(2)) && Long.parseLong(d5.group(1)) > 0, all);
        // D2: the catalog's columns and the replay's own counts' compared with no mismatch, none missing, so P2 runs.
        String d2 = line(checks, "D2");
        Matcher m = Pattern.compile("D2\t(\\d+) of (\\d+) keys .*; 0 mismatches; catalog counts missing from its header: (.*)")
                .matcher(d2);
        assertTrue(m.matches() && m.group(1).equals(m.group(2)) && Long.parseLong(m.group(1)) > 0, d2);
        assertEquals("[]", m.group(3).trim(), "no catalog count missing: " + d2);
        assertEquals(0, p2exit, "every exact check passed, so P2 runs: " + p2log);
        assertTrue(p2log.contains("P2: ") && !p2log.contains("an exact check failed"), p2log);
    }

    /** The test's own check: X0,8's bets after the exit written as if seated, resealed; the simulator's D4 finds them. */
    @Test
    public void d4FindsALeavingPlanBetAsIfSeated() throws Exception {
        Path copy = dir.resolve("planted-seated");
        Files.createDirectories(copy);
        for (Map.Entry<String, byte[]> f : FullPlayExportTest.files(bundle).entrySet()) {
            Files.write(copy.resolve(f.getKey()), f.getValue());
        }
        ExportBundle.States s = ExportBundle.readStates(copy.resolve("states.bin"));
        ExportBundle.Section sec = s.section(WidePlansExportTest.ROW, 80);
        int x0 = Arrays.asList(sec.labels).indexOf("X0,8");
        ExportBundle.Strategy[] files = new ExportBundle.Strategy[2];
        for (int f = 0; f < 2; f++) {
            files[f] = ExportBundle.readStrategy(copy.resolve(ExportBundle.Strategy.fileName(WidePlansExportTest.ROW, 80, f)));
        }
        ScoringStates st = FullIndexPilot.load(WidePlansExportTest.states, StateStore.shoesDone(WidePlansExportTest.states));
        int changed = 0;
        for (int e = 0; e < sec.index.length; e++) {
            double seated = files[st.fold(sec.index[e])].plans.get(x0).bet(sec.betKey[e]);
            changed += sec.bet[e][x0] != seated ? 1 : 0;
            sec.bet[e][x0] = seated;
        }
        assertTrue(changed > 0, "some state is after X0,8's exit");
        Files.delete(copy.resolve("states.bin"));
        StrategyExport.writeStates(copy.resolve("states.bin"), StateStore.header(WidePlansExportTest.states), st, s.sections);
        StrategyExportTest.reseal(copy);
        Path o = dir.resolve("sim-planted");
        Path log = dir.resolve("planted.log");
        SimReadsExportTest.sim(log, "p2", "--bundle", copy.toString(), "--out", o.toString(), "--sim-commit", "fixture",
                "--k", "20", "--threads", "1", "--catalog", "counting-systems.json", "--registry", "SIM_CHECK.md");
        String all = new String(Files.readAllBytes(o.resolve("checks.txt")), StandardCharsets.UTF_8);
        long want = d4Comparisons(copy);
        assertEquals("D4\t" + (want - changed) + " of " + want + " bet keys and bets equal to states.bin's; " + changed
                + " mismatches", line(o.resolve("checks.txt"), "D4"), all);
        assertTrue(all.contains("plan X0,8: bet "), all);
    }

    /**
     * p3 on the bundle: the chart's walk at 70, 75 and 80%, which P3 plays on every bundle, compared
     * as F01 to F03 with no exact side, the bundle holding no chart row; SiteSim reads them as the
     * chart's walk (SiteSim.Test.chartWalk), which a part 2 run leaves out.
     */
    @Test
    public void p3ComparesTheChartsWalkWithNoExactSide() throws Exception {
        Path o = dir.resolve("sim-p3");
        Path log = dir.resolve("p3.log");
        int exit = SimReadsExportTest.sim(log, "p3", "--bundle", bundle.toString(), "--out", o.toString(), "--sim-commit",
                "fixture", "--chunks", "1", "--chunk-size", "40", "--threads", "1", "--registry", "SIM_CHECK.md");
        assertEquals(0, exit, new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        List<String> walk = new ArrayList<>();
        for (SiteSim.Test t : SiteSim.tests(new SiteSim.Summary(o.resolve("summary-p3.json")))) {
            if (t.chartWalk()) {
                walk.add(t.fig.id);
            } else {
                assertNull(t.fig, t.name + " is not a registered row");
            }
        }
        assertEquals(List.of("F01", "F02", "F03"), walk);
    }
}
