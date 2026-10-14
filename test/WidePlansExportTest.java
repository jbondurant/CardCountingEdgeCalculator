import org.junit.jupiter.api.BeforeAll;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The wide plans in the full-play export (INTEGRATION.md open item 5; SHOE_SPEC.md B2.5, B3, B5):
 * F43 to F48 are Hi-Lo's and Victor APC + ace's full play at 80% under P16, B0,8 and X-1.0,8, so
 * the replay's export carries those plans for those rows, with bets per key and the chance a
 * round is played (PLAY) from SystemScorer.Wide, the exit key of a plan that leaves the table
 * (SystemScorer.keyFrom) on its plan line, states.bin's bets 0 once the shoe's lowest bet key so
 * far has fallen below the exit (AllStates.minKey), and exact.tsv's sums held to the entry's wide
 * figures, the ones wide.ser holds.
 *
 * On the replay test's tiny run (two counts of our own, 12 shoes, 75% and 80%), replayed with
 * --wide and its export hook given P16, B0,8, B-0.5,8, X-1.0,8 and X0,8 for one row at 80%. Never pointed
 * at runs/.
 *
 * Planted in copies of the bundle, each refused by the round trip: an exit key moved, a straddling
 * key's chance played set to 1, and states.bin's bets under a leaving plan written as if seated.
 */
public class WidePlansExportTest {

    @TempDir
    static Path root;
    static Path states;
    static Path source;
    static Path bundle;
    static FullIndexReplay.Assembled replayed;
    static String log;
    static final String ROW = "F1 start, full play";
    static final List<String> WIDE = Arrays.asList("P16", "B0,8", "B-0.5,8", "X-1.0,8", "X0,8");

    static FullIndexReplay.Options options(Path out, Path export, boolean wide, PrintStream log) {
        FullIndexReplay.Options o = new FullIndexReplay.Options();
        o.fitFrom = source;
        o.states = states;
        o.out = out;
        o.trees = root.resolve("trees.bin");
        o.readOnlyTrees = true;
        o.countsFile = root.resolve("candidates.json");
        o.pens = new int[]{1, 2};
        o.resamples = 3;
        o.threads = 1;
        o.foldsOnly = true;
        o.wide = wide;
        o.hooks.add(new StrategyExport.ReplayExport(export.toAbsolutePath().normalize(), states, -1, new ArrayList<>(),
                StrategyExport.ReplayExport.PLANS, StrategyExport.SAMPLE_SEED, log).plans(ROW + "@80", WIDE));
        return o;
    }

    @BeforeAll
    static void replayWithTheWidePlans() throws Exception {
        states = ComparisonReportTest.syntheticRun(root, 79, 79, 12);
        Path counts = CustomCountsTest.write(root, "candidates.json", FullIndexReplayTest.COUNTS);
        source = root.resolve("run");
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        FullIndexRun.run(new String[]{"--states", states.toString(), "--counts", counts.toString(), "--pens", "1,2",
                "--resamples", "3", "--threads", "1", "--out", source.toString(), "--trees",
                root.resolve("trees.bin").toString()}, "the-run's-commit", quiet, 0);
        bundle = root.resolve("export");
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        PrintStream into = new PrintStream(text, true, "UTF-8");
        replayed = FullIndexReplay.run(options(root.resolve("replay"), bundle, true, into), "replay", into);
        log = text.toString("UTF-8");
    }

    static SystemScorer.Entry entry(String name) throws Exception {
        for (SystemScorer.Result r : replayed.results()) {
            for (SystemScorer.Entry e : r == null ? new ArrayList<SystemScorer.Entry>() : r.fullEntries) {
                if (e.name.equals(name)) {
                    return e;
                }
            }
        }
        throw new AssertionError("no entry " + name);
    }

    /** A plan's lines of one strategy file: its plan line's fields, then each bet line's. */
    static List<String[]> lines(Path file, String word, String label) throws Exception {
        List<String[]> out = new ArrayList<>();
        for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] f = l.split("\t");
            if (f[0].equals(word) && f[1].equals(label)) {
                out.add(f);
            }
        }
        return out;
    }

    /** The labels SIM_CHECK.md's registry asks of the replay's rows: F43 to F48's wide plans, on Hi-Lo's and Victor APC + ace's rows at 80%. */
    @Test
    public void theRegisteredRowsCarryTheirWidePlans() {
        Map<String, List<String>> m = StrategyExport.ReplayExport.registeredPlans(new int[]{0, 1, 2});
        assertEquals(2, m.size(), m.toString());
        List<String> three = Arrays.asList("P16", "B0,8", "X-1.0,8");
        assertEquals(three, m.get("Hi-Lo = Hi-Lo Lite, full play@80"));
        assertEquals(three, m.get("Victor Advanced Point Count (Victor APC) + ace side count, full play@80"));
        assertTrue(StrategyExport.ReplayExport.registeredPlans(new int[]{0, 1}).isEmpty(), "none at 70 or 75%");
        assertArrayEquals(new String[]{"same-8", "B0,8", "X-1.0,8", "P16"}, StrategyExport.planList("same-8,B0,8,X-1.0,8,P16"));
        assertThrows(IllegalArgumentException.class, () -> StrategyExport.planList("same-8,Q16"));
        assertEquals("same", StrategyExport.rule("B0,8"));
        assertEquals("same", StrategyExport.rule("S16"));
        assertEquals("own", StrategyExport.rule("X-1.0,8"));
    }

    /**
     * The bundle: the row's files carry the four wide plans, the other rows' only same-8; P16
     * plays every key, B0,8's chance is between 0 and 1 and a key sat out is 0 units, the
     * leaving plans carry a whole exit key and play every key; exact.tsv's ev: and bet: lines of
     * each wide plan, metric -1, pooled over the folds, equal the entry's wide figures, and the
     * round trip passed.
     */
    @Test
    public void theRowsFilesCarryTheWidePlansHeldToTheEntrysWideFigures() throws Exception {
        assertTrue(Files.isDirectory(bundle), log);
        assertTrue(log.contains("round trip passed"), log);
        assertTrue(log.contains("10 of the wide plans equal Entry.wide"), log);
        for (int f = 0; f < 2; f++) {
            Path file = bundle.resolve(ExportBundle.Strategy.fileName(ROW, 80, f));
            for (String label : WIDE) {
                assertEquals(1, lines(file, "plan", label).size(), label);
            }
            assertEquals("-", lines(file, "plan", "P16").get(0)[3]);
            assertEquals("-", lines(file, "plan", "B0,8").get(0)[3]);
            Integer.parseInt(lines(file, "plan", "X-1.0,8").get(0)[3]);
            for (String[] b : lines(file, "bet", "P16")) {
                assertEquals(1.0, Double.parseDouble(b[4]));
            }
            for (String label : new String[]{"B0,8", "B-0.5,8"}) {
                int between = 0;
                for (String[] b : lines(file, "bet", label)) {
                    double u = Double.parseDouble(b[3]);
                    double p = Double.parseDouble(b[4]);
                    assertTrue(p >= 0 && p <= 1 && (p == 0) == (u == 0) && (u == 0 || u >= 1), Arrays.toString(b));
                    between += p > 0 && p < 1 ? 1 : 0;
                }
                assertTrue(between <= 1, label + ": " + between + " keys played by chance; only the straddling block is");
            }
            for (String[] b : lines(file, "bet", "X0,8")) {
                assertEquals(1.0, Double.parseDouble(b[4]));
            }
            Path other = bundle.resolve(ExportBundle.Strategy.fileName(ROW, 75, f));
            assertTrue(lines(other, "plan", "P16").isEmpty(), "75% has same-8 alone");
        }
        SystemScorer.Entry e = entry(ROW);
        List<String> labels = new ArrayList<>();
        for (SystemScorer.Plan w : e.widePlans) {
            labels.add(w.label);
        }
        for (String label : WIDE) {
            SystemScorer.Plan w = StrategyExport.widePlan(label);
            int i = labels.indexOf(label);
            int ev = w.sameSchedule() ? SystemScorer.W_SAME : SystemScorer.W_OWN;
            int bet = w.sameSchedule() ? SystemScorer.W_SAME_BET : SystemScorer.W_OWN_BET;
            double[] a = new double[2];
            double[] b = new double[2];
            for (ExportBundle.Exact x : ExportBundle.readExact(bundle.resolve("exact.tsv"))) {
                if (x.row.equals(ROW) && x.pen == 80 && (x.figure.equals("ev:" + label) || x.figure.equals("bet:" + label))) {
                    assertEquals(-1, x.metric, x.figure);
                    int k = x.figure.startsWith("ev:") ? 0 : 1;
                    a[k] += x.sumA;
                    b[k] += x.sumB;
                }
            }
            assertEquals(e.wide[2][SystemScorer.wideAt(i, ev)], a[0] / b[0], 1e-12, label + " EV");
            assertEquals(e.wide[2][SystemScorer.wideAt(i, bet)], a[1] / b[1], 1e-12, label + " average bet");
        }
    }

    /** A plan that leaves the table: some state seated at a key the plan bets on is sat out in states.bin, its shoe's lowest key below the exit. */
    @Test
    public void aLeavingPlanBetsNothingOnceTheShoeFellBelowItsExit() throws Exception {
        ExportBundle.States bin = ExportBundle.readStates(bundle.resolve("states.bin"));
        ExportBundle.Section sec = bin.section(ROW, 80);
        int x0 = Arrays.asList(sec.labels).indexOf("X0,8");
        ExportBundle.Strategy[] files = new ExportBundle.Strategy[2];
        for (int f = 0; f < 2; f++) {
            files[f] = ExportBundle.readStrategy(bundle.resolve(ExportBundle.Strategy.fileName(ROW, 80, f)));
        }
        ScoringStates st = FullIndexPilot.load(states, StateStore.shoesDone(states));
        AllStates dealt = AllStates.replay(StateStore.header(states), st.shoes, st.rules, BasicStrategy.chart(), st);
        int left = 0;
        int seated = 0;
        for (int e = 0; e < sec.index.length; e++) {
            int s = sec.index[e];
            ExportBundle.Strategy file = files[st.fold(s)];
            ExportBundle.Plan plan = file.plans.get(x0);
            int lowest = StrategyExport.lowestSoFar(dealt, st, file)[s];
            if (lowest < plan.exit) {
                assertEquals(0.0, sec.bet[e][x0], "state " + s);
                left++;
            } else {
                assertEquals(plan.bet(sec.betKey[e]), sec.bet[e][x0], "state " + s);
                seated++;
            }
        }
        assertTrue(left > 0 && seated > 0, left + " states after the exit, " + seated + " seated");
    }

    /** A copy of the bundle with one text file changed and resealed. */
    static Path planted(String name, String file, java.util.function.UnaryOperator<String> change) throws Exception {
        Path dir = root.resolve("planted-" + name);
        Files.createDirectories(dir);
        for (Map.Entry<String, byte[]> f : FullPlayExportTest.files(bundle).entrySet()) {
            Files.write(dir.resolve(f.getKey()), f.getValue());
        }
        String text = new String(Files.readAllBytes(dir.resolve(file)), StandardCharsets.UTF_8);
        String changed = change.apply(text);
        assertNotEquals(text, changed, name + " changed nothing");
        Files.write(dir.resolve(file), changed.getBytes(StandardCharsets.UTF_8));
        StrategyExportTest.reseal(dir);
        return dir;
    }

    /** The round trip on a bundle, as the export ran it at its end: the checked sections and the shoes dealt again. */
    @SuppressWarnings("unchecked")
    static String refusal(Path dir) throws Exception {
        StrategyExport.FullSource full = new StrategyExport.FullSource();
        List<StrategyExport.Expect> expected = new ArrayList<>();
        for (int k = 0; k < replayed.items.size(); k++) {
            Object x = replayed.record(k).kept.get("export");
            for (StrategyExport.RowExport r : (List<StrategyExport.RowExport>) x) {
                full.verified.put(StrategyExport.FullSource.key(r.expect.row, r.expect.pen), r.section);
                expected.add(r.expect);
            }
        }
        ScoringStates st = FullIndexPilot.load(states, StateStore.shoesDone(states));
        full.dealt = AllStates.replay(StateStore.header(states), st.shoes, st.rules, BasicStrategy.chart(), st);
        List<CountKeys.Count> own = new ArrayList<>();
        for (CountKeys.Count c : replayed.counts) {
            if (!c.name.equals(CountKeys.nullCount().name)) {
                own.add(c);
            }
        }
        List<CountKeys.Count> keyCounts = StrategyExport.keyCounts(new ArrayList<>(), own);
        assertNotNull(StrategyExport.roundTrip(bundle, st, StateStore.header(states), expected, keyCounts, full),
                "the bundle as written passes");
        RuntimeException e = assertThrows(RuntimeException.class, () -> StrategyExport.roundTrip(dir, st,
                StateStore.header(states), expected, keyCounts, full));
        return e.getMessage();
    }

    @Test
    public void anExitKeyMovedIsCaught() throws Exception {
        String file = ExportBundle.Strategy.fileName(ROW, 80, 0);
        String msg = refusal(planted("exit", file, t -> StrategyExportTest.lines(t, l -> {
            String[] f = l.split("\t");
            if (f[0].equals("plan") && f[1].equals("X0,8")) {
                f[3] = Integer.toString(Integer.parseInt(f[3]) + 1000);
            }
            return String.join("\t", f);
        })));
        assertTrue(msg.contains("bet under X0,8"), msg);
    }

    /**
     * A back-counting plan's chance played (PLAY) changed on one key: half of the rounds sat out
     * where the engine plays them all. The fixture's few states put no block astride a threshold,
     * so a key played every round is changed; theStraddlingBlockIsPlayedByChance holds the chance
     * itself.
     */
    @Test
    public void aChancePlayedChangedIsCaught() throws Exception {
        String file = null;
        String key = null;
        String plan = null;
        for (String label : new String[]{"B-0.5,8", "B0,8"}) {
            for (int f = 0; f < 2 && key == null; f++) {
                Path p = bundle.resolve(ExportBundle.Strategy.fileName(ROW, 80, f));
                for (String[] b : lines(p, "bet", label)) {
                    if (key == null && b[4].equals("1.0") && Double.parseDouble(b[3]) > 0) {
                        file = p.getFileName().toString();
                        key = b[2];
                        plan = label;
                    }
                }
            }
        }
        assertNotNull(key, "a key a back-counting plan plays");
        String k = key;
        String l0 = plan;
        String msg = refusal(planted("play", file, t -> StrategyExportTest.lines(t, l -> {
            String[] f = l.split("\t");
            if (f[0].equals("bet") && f[1].equals(l0) && f[2].equals(k)) {
                f[4] = "0.5";
            }
            return String.join("\t", f);
        })));
        assertTrue(msg.contains("bet under " + plan), msg);
    }

    /**
     * B2.5's UNITS and PLAY from SystemScorer.Wide's detail row: the same schedule's expected bet
     * over its chance on the block astride the threshold, a key sat out 0 and 0, a block played
     * in full (its share 1 less rounding) PLAY 1 at the expected bet itself, and a leaving plan's
     * own bet played every round.
     */
    @Test
    public void theStraddlingBlockIsPlayedByChance() {
        SystemScorer.Plan b = StrategyExport.widePlan("B0,8");
        int c = SystemScorer.KEY_COLS;
        double[] row = new double[2 * c];
        row[c + SystemScorer.K_SAME_BET] = 0.75;
        row[c + SystemScorer.K_SAME_PLAY] = 0.25;
        assertArrayEquals(new double[]{3.0, 0.25}, StrategyExport.wideUnits(b, row, 1));
        row[c + SystemScorer.K_SAME_BET] = 0;
        row[c + SystemScorer.K_SAME_PLAY] = 0;
        assertArrayEquals(new double[]{0, 0}, StrategyExport.wideUnits(b, row, 1));
        row[c + SystemScorer.K_SAME_BET] = 4.5;
        row[c + SystemScorer.K_SAME_PLAY] = 1 - 2e-16;
        assertArrayEquals(new double[]{4.5, 1}, StrategyExport.wideUnits(b, row, 1));
        SystemScorer.Plan x = StrategyExport.widePlan("X-1.0,8");
        row[c + SystemScorer.K_OWN_BET] = 6.25;
        assertArrayEquals(new double[]{6.25, 1}, StrategyExport.wideUnits(x, row, 1));
    }

    @Test
    public void aLeavingPlanBetAsIfSeatedIsCaught() throws Exception {
        Path dir = root.resolve("planted-seated");
        Files.createDirectories(dir);
        for (Map.Entry<String, byte[]> f : FullPlayExportTest.files(bundle).entrySet()) {
            Files.write(dir.resolve(f.getKey()), f.getValue());
        }
        ExportBundle.States s = ExportBundle.readStates(dir.resolve("states.bin"));
        ExportBundle.Section sec = s.section(ROW, 80);
        int x0 = Arrays.asList(sec.labels).indexOf("X0,8");
        ExportBundle.Strategy[] files = new ExportBundle.Strategy[2];
        for (int f = 0; f < 2; f++) {
            files[f] = ExportBundle.readStrategy(dir.resolve(ExportBundle.Strategy.fileName(ROW, 80, f)));
        }
        ScoringStates st = FullIndexPilot.load(states, StateStore.shoesDone(states));
        int changed = 0;
        for (int e = 0; e < sec.index.length; e++) {
            double seated = files[st.fold(sec.index[e])].plans.get(x0).bet(sec.betKey[e]);
            changed += sec.bet[e][x0] != seated ? 1 : 0;
            sec.bet[e][x0] = seated;
        }
        assertTrue(changed > 0);
        Files.delete(dir.resolve("states.bin"));
        StrategyExport.writeStates(dir.resolve("states.bin"), StateStore.header(states), st, s.sections);
        StrategyExportTest.reseal(dir);
        String msg = refusal(dir);
        assertTrue(msg.contains("is not the one checked against the strategies' values") || msg.contains("bet under X0,8"),
                msg);
    }

    /** The export's wide plans are held to the entry's wide figures, which only --wide scores: without it the replay refuses before any item. */
    @Test
    public void aWidePlanWithoutWideIsRefusedAtTheStart() throws Exception {
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> FullIndexReplay.run(
                options(root.resolve("no-wide"), root.resolve("export-no-wide"), false, quiet), "replay", quiet));
        assertTrue(e.getMessage().contains("--wide"), e.getMessage());
        assertFalse(Files.exists(root.resolve("export-no-wide")));
    }
}
