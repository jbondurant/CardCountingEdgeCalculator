import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The export bundle of SHOE_SPEC.md Part B, written by StrategyExport on a state file made here
 * in a temporary directory, never the run's: real shoes dealt by ShoeRun, each valued state given
 * made-up move values that depend on the Hi-Lo true count, so the fitted first moves change from
 * group to group and some groups insure. Every row of six counts is exported (a true count, one
 * with an ace side count, a running count, a colour count, the null count and a count of our own
 * with a divisor and a betting vector of its own) and the round trip must give back the report's
 * figures, those SystemScorer.score gives on the same states.
 *
 * The planted bugs. Each test that tampers with a written bundle below is a bug the exporter could
 * have, written into its output: a move ranked wrong, a fold's file swapped for the other's, an
 * insurance line dropped, a bet off by a little, a tag or a group bound changed, a round value or
 * a sum in the wrong place. The manifest and index are rewritten after each tampering, as a writer
 * with that bug would write them, so the round trip has to catch the bug by what it computes, not
 * by a hash. Planted by hand in StrategyExport's source on 9 October 2026, one at a time, each
 * compiled and this class run against it, the source restored after:
 *
 * | Planted in StrategyExport | What refused it |
 * |---|---|
 * | fold f's bets fitted on round values carrying the other fold's insurance | the export's own round trip: Hi-Lo at 80%, ev:own-6 6.7e-6 off the report's |
 * | fold f's file given the other fold's ranked first moves | the export: the other fold's groups do not fit (index out of bounds) |
 * | the same schedule's bets taken from the own ramp's column | the export: Hi-Lo at 70%, ev:same-2 2.8% off the report's |
 * | bet lines for every key of the penetration, not only the fold's test keys | the export: same-1 of fold 0 lists keys its test states do not hold |
 * | a betting variant's bet column of keys-sample.tsv keyed on its base | the export: Hi-Opt II + ace side count's bet key at state 0 |
 * | states.bin's two round values each written in the other's place | the export: state 0's round value |
 * | keys-sample.tsv's play keys after two cards seen, not three | the export: Own test count, state 0, deal 16 |
 * | a fold's training weight summed over its test states | the export: Hi-Lo at 70%, fold 0's training weight |
 * | exact.tsv's sumAB written as the sum of a squared | at first nothing: the round trip summed exact.tsv with the writer's own function. It now works B5 out apart (b5Sums), and the export refuses: Hi-Lo at 70%, fold 0, flat, sumAB |
 * | the first 19 records, not 20, given every deal | at first only theKeySampleCoversTheStatesAndDeals (15,288 lines expected, 14,748 written), since the round trip took the sample's shape from the writer's own sample(). It now also counts B4's first 20 records from the file, and the export refuses: record 19 has 10 play lines, not 550 |
 *
 * A plant the export refuses fails every test here, since the bundle is written once for the
 * class; the line above names the first refusal. The fold plants are the exporter's side of a bug
 * PLAN_COMPUTE.md 1.6 plants in the simulator, "the other fold's strategy scored".
 */
public class StrategyExportTest {

    private static final RoundRules RULES = StateRun.montreal();
    private static final RoundPolicy CHART = BasicStrategy.chart();
    private static final long SEED = 91;
    private static final double Q0 = 0.05;
    private static final double U0 = 0.02;
    private static final int SHOES = 60;

    @TempDir
    static Path shared;

    static Path states;
    static StateStore.Header header;
    static ScoringStates st;
    static SystemScorer scorer;
    static List<CountKeys.Count> counts;
    static List<SystemScorer.Result> results;
    static Path bundle;
    static StrategyExport.ChartRow chartRow;
    static StrategyExport.Report report;
    static String log;

    /**
     * A state file of SHOES shoes dealt from SEED, as a run deals them, each valued state given
     * values that follow the Hi-Lo true count tc: the chart's first move is worth a = -0.02 +
     * 0.01 tc; Stand, where the chart does not stand, a - 0.05 + 0.02 tc, so it is the better move
     * above tc 2.5; Double, where the chart does not double, a - 0.06 - 0.02 tc, better below -3;
     * every other move a - 0.3; and each a small amount by deal and move, so ties are rare.
     */
    static Path syntheticRun(Path dir) throws Exception {
        StateStore.Header h = new StateStore.Header(SEED, Q0, U0, StateRun.CUT_80, StateRun.describe(RULES, CHART));
        Path file = dir.resolve("export-fixture.states");
        assertEquals(0, StateStore.open(file, h));
        ShoeRun run = new ShoeRun(SEED, Q0, U0, StateRun.CUT_80, RULES, CHART);
        int[] chart = ScoringStates.chartMoves(RULES);
        CountKeys.Count hiLo = CountKeys.of(CountingSystem.named("Hi-Lo"), "");
        for (int shoe = 0; shoe < SHOES; shoe++) {
            List<StateStore.Record> records = new ArrayList<>();
            for (ShoeRun.State s : run.deal(shoe)) {
                if (!s.valued) {
                    continue;
                }
                double tc = hiLo.trueCount(s.dealt, s.depth);
                double a = -0.02 + 0.01 * tc;
                records.add(SystemScorerTest.state(s.shoe, s.round, s.dealt, s.q, (i, m) -> {
                    double jitter = 0.001 * ((7 * i + 3 * m) % 5);
                    if (m == chart[i]) {
                        return a + jitter;
                    }
                    if (m == 0) {
                        return a - 0.05 + 0.02 * tc + jitter;
                    }
                    if (m == 2) {
                        return a - 0.06 - 0.02 * tc + jitter;
                    }
                    return a - 0.3 + jitter;
                }));
            }
            StateStore.appendShoe(file, shoe, records);
        }
        return file;
    }

    static CountKeys.Count catalog(String system) {
        for (CountKeys.Count c : CountKeys.catalog()) {
            if (c.systems.contains(system)) {
                return c;
            }
        }
        throw new IllegalArgumentException(system);
    }

    /** A true count of our own at K, Hi-Lo's shape with a twist, key divisor 2,500, betting on a vector of its own. */
    static CountKeys.Count own() {
        int[] tag = {0, -9_000, 5_000, 6_000, 8_000, 10_000, 7_000, 4_000, 0, -2_000, -9_750};
        int[] bet = {0, -12_000, 5_000, 6_000, 8_000, 10_000, 7_000, 4_000, 0, -2_000, -9_000};
        return CountKeys.whole("Own test count", tag, bet, 2_500, true);
    }

    /** The counts scored and exported, each with its betting variant when it has one. */
    static List<CountKeys.Count> testCounts() {
        return Arrays.asList(catalog("Hi-Lo"), catalog("Hi-Opt II"), catalog("KO (Knock-Out)"),
                catalog("Red Seven (Red 7)"), CountKeys.nullCount(), own());
    }

    /** keys-sample.tsv's columns here: the counts above, then their betting variants. */
    static List<CountKeys.Count> keyColumns() {
        List<CountKeys.Count> out = new ArrayList<>(counts);
        for (CountKeys.Count c : counts) {
            CountKeys.Count v = CountKeys.bettingVariant(c);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    /** Every first-decision and betting-only row of every result, at every penetration. */
    static List<StrategyExport.Wanted> everyRow() {
        List<StrategyExport.Wanted> rows = new ArrayList<>();
        for (SystemScorer.Result r : results) {
            for (SystemScorer.Entry e : r.entries) {
                rows.add(new StrategyExport.Wanted(r, e, 0, 1, 2));
            }
        }
        return rows;
    }

    @BeforeAll
    static void exportOnce() throws Exception {
        states = syntheticRun(shared);
        header = StateStore.header(states);
        st = new ScoringStates(StateStore.readAll(states), StateStore.shoesDone(states), RULES);
        // A small M_play, so that the few hundred states here make many play groups.
        scorer = new SystemScorer(st, new Bootstrap(st.shoes, 0, Bootstrap.SEED), 1);
        counts = testCounts();
        results = new ArrayList<>();
        for (CountKeys.Count c : counts) {
            results.add(scorer.score(c, CountKeys.bettingVariant(c)));
        }
        bundle = shared.resolve("export-test");
        chartRow = new StrategyExport.ChartRow(scorer.references().get(0), 0, 1, 2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        report = StrategyExport.export(bundle, scorer, header, everyRow(), chartRow,
                StrategyExport.reportPlans().toArray(new String[0]), keyColumns(), StrategyExport.SAMPLE_SEED,
                new PrintStream(out, true, "UTF-8"));
        log = out.toString("UTF-8");
    }

    /**
     * The bundle is written whole, the round trip passes on every row, and what it compared is
     * what it says: every exported figure against the report's, every per-shoe sum, every state.
     */
    @Test
    public void theRoundTripGivesBackTheReportsFigures() throws Exception {
        System.out.print(log);
        assertTrue(Files.isDirectory(bundle), log);
        assertFalse(Files.exists(shared.resolve("export-test.partial")));
        int rows = 0;
        for (SystemScorer.Result r : results) {
            rows += r.entries.size();
        }
        assertEquals(16, rows, "two counts with a betting variant and four without, each also betting only");
        // Every row at three penetrations: two files each, and seven other files (the chart's row has none).
        assertEquals(3 * rows * 2 + 7, Files.list(bundle).count());
        assertTrue(report.worst <= StrategyExport.RELATIVE, report.worst + "");
        assertTrue(st.size > 200, st.size + " states");
        int figures = 0;
        for (String l : report.lines) {
            if (l.contains("figures pooled from exact.tsv equal Entry.estimate")) {
                assertTrue(l.contains("51 figures"), l);
                assertTrue(l.contains(st.shoes + " per-shoe sums equal Entry.shoeSums"), l);
                figures++;
            }
        }
        assertEquals(3 * rows, figures, log);
        for (String pen : new String[]{"70", "75", "80"}) {
            assertTrue(log.contains("Chart, no count, flat at " + pen + "%: "), log);
        }
        assertTrue(log.contains("round trip passed"), log);
        assertTrue(log.matches("(?s).*bundle: \\d+ files, \\d+ bytes.*"), log);
    }

    /** The fixture is one the export can get wrong: many groups, moves other than the chart's, and insurance taken. */
    @Test
    public void theFixturePlaysOffTheChartAndInsures() throws Exception {
        ExportBundle.Strategy s = ExportBundle.readStrategy(bundle.resolve(
                ExportBundle.Strategy.fileName(results.get(0).entries.get(0).name, 80, 0)));
        assertTrue(s.lo.length >= 5, s.lo.length + " groups");
        int offChart = 0;
        int insured = 0;
        int[] chart = ScoringStates.chartMoves(RULES);
        for (int g = 0; g < s.lo.length; g++) {
            for (int i = 0; i < Deals.COUNT; i++) {
                if (!Deals.playerNatural(i) && s.first[g][i][0].ordinal() != chart[i]) {
                    offChart++;
                }
                if (i < ScoringStates.ACE_UP && s.insure[g][i]) {
                    insured++;
                }
            }
        }
        assertTrue(offChart > 50, offChart + " (group, deal) cells off the chart");
        assertTrue(insured > 0, "no insurance taken");
        assertTrue(insured < s.lo.length * ScoringStates.ACE_UP, "insurance taken everywhere");
    }

    // ------------------------------------------------------------------ planted bugs, written into a bundle

    static List<StrategyExport.Expect> expected() {
        List<StrategyExport.Expect> out = new ArrayList<>();
        for (StrategyExport.Wanted w : everyRow()) {
            for (int p : w.pens) {
                out.add(StrategyExport.Expect.of(w.entry, p));
            }
        }
        for (int p : chartRow.pens) {
            out.add(StrategyExport.Expect.of(chartRow.entry, p));
        }
        return out;
    }

    /** A copy of the bundle under a new name. */
    static Path copy(String name) throws IOException {
        Path dir = shared.resolve("planted-" + name);
        Files.createDirectories(dir);
        try (java.util.stream.Stream<Path> files = Files.list(bundle)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Files.copy(f, dir.resolve(f.getFileName()));
            }
        }
        return dir;
    }

    /** index.tsv's sizes and manifest.sha256's hashes made right again, as a writer with the bug would make them. */
    static void reseal(Path dir) throws IOException {
        Map<String, String[]> index = ExportBundle.readIndex(dir.resolve("index.tsv"));
        StringBuilder b = new StringBuilder(ExportBundle.INDEX_HEADER).append('\n');
        for (Map.Entry<String, String[]> e : index.entrySet()) {
            String[] f = e.getValue();
            b.append(String.join("\t", e.getKey(), Long.toString(Files.size(dir.resolve(e.getKey()))), f[1], f[2], f[3], f[4]))
                    .append('\n');
        }
        Files.write(dir.resolve("index.tsv"), b.toString().getBytes(StandardCharsets.UTF_8));
        List<String> files = new ArrayList<>(index.keySet());
        files.add("index.tsv");
        files.sort(null);
        StringBuilder m = new StringBuilder();
        for (String f : files) {
            m.append(ExportBundle.sha256(dir.resolve(f))).append("  ").append(f).append('\n');
        }
        Files.write(dir.resolve("manifest.sha256"), m.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A copy of the bundle with one text file changed and the bundle resealed. */
    static Path planted(String name, String file, UnaryOperator<String> change) throws IOException {
        Path dir = copy(name);
        String text = new String(Files.readAllBytes(dir.resolve(file)), StandardCharsets.UTF_8);
        String changed = change.apply(text);
        assertNotEquals(text, changed, name + " changed nothing");
        Files.write(dir.resolve(file), changed.getBytes(StandardCharsets.UTF_8));
        reseal(dir);
        return dir;
    }

    /** The round trip's refusal of a bundle, which must name what it found. */
    static String refusal(Path dir) {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> StrategyExport.roundTrip(dir, st, header, expected(), keyColumns()));
        return e.getMessage();
    }

    /** Each line of a text changed by f, which returns the line unchanged where it does not apply. */
    static String lines(String text, UnaryOperator<String> f) {
        StringBuilder b = new StringBuilder();
        for (String l : text.split("\n", -1)) {
            if (b.length() > 0) {
                b.append('\n');
            }
            b.append(f.apply(l));
        }
        return b.toString();
    }

    /** A text without its lines that start so. */
    static String without(String text, String start) {
        StringBuilder b = new StringBuilder();
        for (String l : text.split("\n", -1)) {
            if (!l.startsWith(start)) {
                b.append(b.length() > 0 ? "\n" : "").append(l);
            }
        }
        return b.toString();
    }

    static String hiLoFile(int pen, int fold) {
        return ExportBundle.Strategy.fileName(results.get(0).entries.get(0).name, pen, fold);
    }

    /** The unchanged bundle passes the round trip read from disk alone, which the tests below change one thing in. */
    @Test
    public void theBundleAsWrittenPassesAgain() throws Exception {
        StrategyExport.Report r = StrategyExport.roundTrip(copy("none"), st, header, expected(), keyColumns());
        assertEquals(report.compared, r.compared);
    }

    /** Every first move ranked second: the round values of the fold's states move. */
    @Test
    public void aMoveRankedWrongIsCaught() throws Exception {
        String msg = refusal(planted("ranked", hiLoFile(80, 0), t -> lines(t, l -> {
            String[] f = l.split("\t");
            if (!f[0].equals("first") || f.length < 7) {
                return l;
            }
            String x = f[5];
            f[5] = f[6];
            f[6] = x;
            return String.join("\t", f);
        })));
        assertTrue(msg.contains("round value under its fold's play"), msg);
    }

    /** The other fold's strategy written under this fold's name (fold line set to match). */
    @Test
    public void theOtherFoldsStrategyIsCaught() throws Exception {
        String other = new String(Files.readAllBytes(bundle.resolve(hiLoFile(75, 1))), StandardCharsets.UTF_8);
        String msg = refusal(planted("fold", hiLoFile(75, 0), t -> lines(other, l -> l.startsWith("fold\t1\t")
                ? "fold\t0\t" + l.substring("fold\t1\t".length()) : l)));
        assertTrue(msg.contains("Hi-Lo = Hi-Lo Lite at 75%"), msg);
    }

    /** No insurance written: the insured states' insurance values are missed. */
    @Test
    public void insuranceLeftOutIsCaught() throws Exception {
        String msg = refusal(planted("insure", hiLoFile(80, 0), t -> without(t, "insure\t")));
        assertTrue(msg.contains("insurance value"), msg);
    }

    /** A bet a billionth off at the first key of the widest same schedule. */
    @Test
    public void aBetOffALittleIsCaught() throws Exception {
        boolean[] done = {false};
        String msg = refusal(planted("bet", hiLoFile(70, 1), t -> lines(t, l -> {
            String[] f = l.split("\t");
            if (done[0] || !f[0].equals("bet") || !f[1].equals("same-8")) {
                return l;
            }
            done[0] = true;
            f[3] = Double.toString(Double.parseDouble(f[3]) * (1 + 1e-9));
            return String.join("\t", f);
        })));
        assertTrue(msg.contains("bet under same-8"), msg);
    }

    /** Two bet tags changed, the ace's up and the two's down, so the deck sum still adds up: the bet keys move. */
    @Test
    public void aBetTagChangedIsCaught() throws Exception {
        String msg = refusal(planted("tags", hiLoFile(80, 1), t -> lines(t, l -> {
            String[] f = l.split("\t");
            if (!f[0].equals("bettags")) {
                return l;
            }
            assertEquals("-1", f[1]);
            assertEquals("1", f[3]);
            f[1] = "0";
            f[3] = "0";
            return String.join("\t", f);
        })));
        assertTrue(msg.contains("bet key"), msg);
    }

    /** Every group's top raised by one: the keys at a group's top are played as the next group plays. */
    @Test
    public void aGroupBoundMovedIsCaught() throws Exception {
        String msg = refusal(planted("group", hiLoFile(80, 0), t -> lines(t, l -> {
            String[] f = l.split("\t");
            if (!f[0].equals("group")) {
                return l;
            }
            f[3] = Integer.toString(Integer.parseInt(f[3]) + 1);
            return String.join("\t", f);
        })));
        assertTrue(msg.contains("Hi-Lo = Hi-Lo Lite at 80%"), msg);
    }

    /** A colour count's play keys worked out on ranks alone: Red Seven's sevens all tagged as the red ones are. */
    @Test
    public void aColourCountKeyedOnRanksIsCaught() throws Exception {
        String red = ExportBundle.Strategy.fileName(results.get(3).entries.get(0).name, 80, 0);
        String msg = refusal(planted("colour", red, t -> lines(t, l -> {
            String[] f = l.split("\t");
            if (!f[0].equals("tags")) {
                return l;
            }
            assertNotEquals(f[1 + 12], f[1 + 13], "Red Seven tags black and red sevens apart");
            f[1 + 12] = f[1 + 13];
            int sum = 0;
            for (int k = 1; k < f.length; k++) {
                sum += 2 * Integer.parseInt(f[k]);
            }
            decksum[0] = sum;
            return String.join("\t", f);
        }).replaceFirst("\ndecksum\t-?\\d+\n", "\ndecksum\t" + decksum[0] + "\n")));
        assertTrue(msg.contains("Red Seven"), msg);
    }

    private static final int[] decksum = new int[1];

    /** One round value in states.bin a billionth off, in a counted row's section or in the chart's. */
    @Test
    public void aRoundValueInStatesBinIsCaught() throws Exception {
        for (boolean chart : new boolean[]{false, true}) {
            Path dir = copy(chart ? "rv-chart" : "rv");
            ExportBundle.States s = ExportBundle.readStates(dir.resolve("states.bin"));
            ExportBundle.Section sec = null;
            for (ExportBundle.Section x : s.sections) {
                if (sec == null && x.row.equals(StrategyExport.CHART_ROW) == chart) {
                    sec = x;
                }
            }
            sec.rvPlay[sec.rvPlay.length / 2] *= 1 + 1e-9;
            Files.delete(dir.resolve("states.bin"));
            StrategyExport.writeStates(dir.resolve("states.bin"), header, st, s.sections);
            reseal(dir);
            String msg = refusal(dir);
            assertTrue(msg.contains(chart ? "round value under chart.tsv" : "round value under its fold's play"), msg);
        }
    }

    /** One of exact.tsv's sums a billionth off. */
    @Test
    public void anExactSumMovedIsCaught() throws Exception {
        boolean[] done = {false};
        String msg = refusal(planted("exact", "exact.tsv", t -> lines(t, l -> {
            String[] f = l.split("\t");
            if (done[0] || f.length != 11 || !f[3].equals("ev:own-4")) {
                return l;
            }
            done[0] = true;
            f[8] = Double.toString(Double.parseDouble(f[8]) * (1 + 1e-9));
            return String.join("\t", f);
        })));
        assertTrue(msg.contains("ev:own-4: sumAA"), msg);
    }

    /** One bet key of keys-sample.tsv one too high. */
    @Test
    public void aSampleKeyMovedIsCaught() throws Exception {
        boolean[] done = {false};
        String msg = refusal(planted("keys", "keys-sample.tsv", t -> lines(t, l -> {
            String[] f = l.split("\t");
            if (done[0] || !f[0].equals("bet") || !f[1].equals("5")) {
                return l;
            }
            done[0] = true;
            f[9] = Integer.toString(Integer.parseInt(f[9]) + 1);
            return String.join("\t", f);
        })));
        assertTrue(msg.contains("keys-sample.tsv, state 5, bet line, " + keyColumns().get(1).name), msg);
    }

    /** A file changed and the bundle not resealed: the manifest's hash refuses it before anything is read. */
    @Test
    public void aFileChangedAfterItsHashIsCaught() throws Exception {
        Path dir = copy("hash");
        Files.write(dir.resolve("exact.tsv"), "\n".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        String msg = refusal(dir);
        assertTrue(msg.contains("exact.tsv's SHA-256 is not the manifest's"), msg);
    }

    /** One of the chart's answers changed and its fingerprint line made to match: not the chart the states were dealt with. */
    @Test
    public void anotherChartIsCaught() throws Exception {
        String msg = refusal(planted("chart", "chart.tsv", t -> {
            List<String> l = new ArrayList<>(Arrays.asList(t.split("\n", -1)));
            int n = StrategyExport.chartIndex(6, 16, 0, 0, 0, 1, 3);
            String line = l.get(3 + n);
            assertTrue(line.endsWith("\tS"), line);
            l.set(3 + n, line.substring(0, line.length() - 1) + "H");
            int[] moves = new int[ExportBundle.CHART_DECISIONS];
            for (int k = 0; k < moves.length; k++) {
                String x = l.get(3 + k);
                moves[k] = ExportBundle.MOVE_LETTERS.indexOf(x.charAt(x.length() - 1));
            }
            l.set(1, "# fingerprint " + ExportBundle.fingerprint(moves));
            return String.join("\n", l);
        }));
        assertTrue(msg.contains("chart.tsv is not the chart the states were dealt with"), msg);
    }

    // ------------------------------------------------------------------ the files themselves

    /** chart.tsv's fingerprint is A2's, and its dealt hand on hard 16 against a 6 stands, as the chart does. */
    @Test
    public void theChartIsTheOneTheStatesWereDealtWith() throws Exception {
        List<String> l = Files.readAllLines(bundle.resolve("chart.tsv"), StandardCharsets.UTF_8);
        assertEquals("# fingerprint 1af534aac9abcb39", l.get(1));
        assertEquals(3 + 253_440, l.size());
        assertEquals("6\t16\t0\t0\t0\t1\t3\tS", l.get(3 + StrategyExport.chartIndex(6, 16, 0, 0, 0, 1, 3)));
        PlayerMove[] chart = ExportBundle.readChart(bundle.resolve("chart.tsv"));
        for (int i = 0; i < Deals.COUNT; i++) {
            if (!Deals.playerNatural(i)) {
                assertEquals(ScoringStates.chartMoves(RULES)[i], StrategyExport.chartFirst(chart, i), "deal " + i);
            }
        }
    }

    /** Every strategy file reads back to a model that writes the same text again, byte for byte. */
    @Test
    public void everyStrategyFileReadsBackToItself() throws Exception {
        int n = 0;
        try (java.util.stream.Stream<Path> files = Files.list(bundle)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (!f.getFileName().toString().startsWith("strategy-")) {
                    continue;
                }
                String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                ExportBundle.Strategy s = ExportBundle.readStrategy(f);
                assertEquals(text, StrategyExport.format(s), f.getFileName().toString());
                assertEquals(f.getFileName().toString(), s.fileName());
                n++;
            }
        }
        assertEquals(16 * 3 * 2, n);
    }

    /**
     * keys-sample.tsv: every state of the fixture drawn (it has fewer than 2,000), each with 10
     * deals, the first 20 with all 550; Red Seven's play cells are spreads whose chances add to 1;
     * a betting variant's play column repeats its base's.
     */
    @Test
    public void theKeySampleCoversTheStatesAndDeals() throws Exception {
        ExportBundle.KeysSample ks = ExportBundle.readKeysSample(bundle.resolve("keys-sample.tsv"));
        assertEquals(StrategyExport.SAMPLE_SEED, ks.seed);
        long bets = ks.lines.stream().filter(l -> l.bet).count();
        assertEquals(st.size, bets);
        assertEquals(st.size + 10L * (st.size - 20) + 550L * 20, ks.lines.size());
        List<String> names = Arrays.asList(ks.counts);
        int red = names.indexOf(catalog("Red Seven (Red 7)").name);
        int hiOpt = names.indexOf(catalog("Hi-Opt II").name);
        int side = names.indexOf(catalog("Hi-Opt II").name + " + ace side count");
        assertTrue(red >= 0 && hiOpt >= 0 && side >= 0, names.toString());
        int spreads = 0;
        int differ = 0;
        for (ExportBundle.KeyLine l : ks.lines) {
            if (l.bet) {
                differ += l.keys[hiOpt][0] != l.keys[side][0] ? 1 : 0;
                continue;
            }
            assertArrayEquals(l.keys[hiOpt], l.keys[side]);
            if (l.keys[red] != null) {
                assertNotNull(l.chances[red]);
                assertEquals(1, Arrays.stream(l.chances[red]).sum(), 1e-12);
                spreads += l.keys[red].length > 1 ? 1 : 0;
            }
        }
        assertTrue(spreads > 100, spreads + " Red Seven cells with more than one key");
        assertTrue(differ > 10, differ + " states where the ace side count moves Hi-Opt II's bet key");
    }

    /**
     * lookups.tsv (SHOE_SPEC.md B8): a column for every strategy file in path order, a first line
     * for every play line of keys-sample.tsv in its order, no later line without a full-play file;
     * moves off the chart, insurance taken, a betting row playing the chart, Red Seven's cells
     * listing every key its deal can have, and '-' for the states a 70% file does not play.
     */
    @Test
    public void theLookupsAreTheEnginesAnswers() throws Exception {
        ExportBundle.Lookups lk = ExportBundle.readLookups(bundle.resolve("lookups.tsv"));
        ExportBundle.KeysSample ks = ExportBundle.readKeysSample(bundle.resolve("keys-sample.tsv"));
        assertEquals(16 * 3 * 2, lk.files.length);
        List<String> sorted = new ArrayList<>(Arrays.asList(lk.files));
        sorted.sort(null);
        assertEquals(sorted, Arrays.asList(lk.files));
        List<ExportBundle.KeyLine> plays = new ArrayList<>();
        for (ExportBundle.KeyLine l : ks.lines) {
            if (!l.bet) {
                plays.add(l);
            }
        }
        assertEquals(plays.size(), lk.lines.size());
        List<String> names = Arrays.asList(lk.files);
        int hiLo = names.indexOf(hiLoFile(80, 0));
        int hiLo70 = names.indexOf(hiLoFile(70, 0));
        int betting = names.indexOf(ExportBundle.Strategy.fileName(results.get(0).entries.get(1).name, 80, 0));
        int red = names.indexOf(ExportBundle.Strategy.fileName(results.get(3).entries.get(0).name, 80, 0));
        int[] chart = ScoringStates.chartMoves(RULES);
        int offChart = 0;
        int insured = 0;
        int spreads = 0;
        int outside = 0;
        for (int n = 0; n < plays.size(); n++) {
            ExportBundle.LookupLine l = lk.lines.get(n);
            assertFalse(l.later);
            assertEquals(plays.get(n).state, l.state);
            assertEquals(plays.get(n).p1 + " " + plays.get(n).p2 + " " + plays.get(n).up, l.p1 + " " + l.p2 + " " + l.up);
            int i = Deals.index(l.p1, l.p2, l.up);
            String[] cell = l.cells[hiLo].split(":");
            assertEquals(4, cell.length, l.cells[hiLo]);
            if (!Deals.playerNatural(i)) {
                offChart += ExportBundle.MOVE_LETTERS.indexOf(cell[2]) != chart[i] ? 1 : 0;
                String[] b = l.cells[betting].split(":");
                assertEquals(String.valueOf(ExportBundle.MOVE_LETTERS.charAt(chart[i])), b[2], l.cells[betting]);
            }
            insured += cell[3].equals("1") ? 1 : 0;
            spreads += l.cells[red].contains(";") ? 1 : 0;
            outside += l.cells[hiLo70].equals("-") ? 1 : 0;
        }
        assertTrue(offChart > 100, offChart + " first moves off the chart");
        assertTrue(insured > 0, "no insurance taken");
        assertTrue(spreads > 100, spreads + " Red Seven cells with more than one key");
        assertTrue(outside > 0, "every state of the sample is played at 70%");
    }

    /** One cell of lookups.tsv as text, changed by f; the file's column found by name. */
    static Path plantedLookup(String name, String file, int skip, UnaryOperator<String> f) throws IOException {
        int[] col = {-1};
        int[] seen = {0};
        boolean[] done = {false};
        return planted(name, "lookups.tsv", t -> lines(t, l -> {
            String[] x = l.split("\t", -1);
            if (x[0].equals("line")) {
                col[0] = Arrays.asList(x).indexOf(file);
                return l;
            }
            if (done[0] || !x[0].equals("first") || col[0] < 0 || x[col[0]].equals("-")) {
                return l;
            }
            String changed = f.apply(x[col[0]]);
            if (changed.equals(x[col[0]]) || seen[0]++ < skip) {
                return l;
            }
            done[0] = true;
            x[col[0]] = changed;
            return String.join("\t", x);
        }));
    }

    /** The engine's first move written as the deal's next legal one: not what the file plays there. */
    @Test
    public void aLookupMoveChangedIsCaught() throws Exception {
        String msg = refusal(plantedLookup("lookup-move", hiLoFile(80, 1), 3, c -> c.contains(":-:") ? c
                : c.replaceFirst(":([SHDP]):", c.contains(":S:") ? ":H:" : ":S:")));
        assertTrue(msg.contains("lookups.tsv line") && msg.contains(hiLoFile(80, 1)), msg);
    }

    /** Insurance turned on where the engine's fit does not insure. */
    @Test
    public void aLookupInsuranceFlippedIsCaught() throws Exception {
        String msg = refusal(plantedLookup("lookup-insure", hiLoFile(75, 0), 0, c -> c.endsWith(":0") ? c.substring(0,
                c.length() - 1) + "1" : c));
        assertTrue(msg.contains("lookups.tsv line") && msg.contains(hiLoFile(75, 0)), msg);
    }

    /** The other fold's answers in a fold's column: the engine looked up the strategy the state is not played by. */
    @Test
    public void theOtherFoldsLookupsAreCaught() throws Exception {
        String mine = hiLoFile(80, 0);
        String other = hiLoFile(80, 1);
        String msg = refusal(planted("lookup-fold", "lookups.tsv", t -> {
            String[] l = t.split("\n", -1);
            List<String> head = Arrays.asList(l[2].split("\t", -1));
            int a = head.indexOf(mine);
            int b = head.indexOf(other);
            for (int n = 3; n < l.length; n++) {
                String[] x = l[n].split("\t", -1);
                if (x.length == head.size()) {
                    x[a] = x[b];
                    l[n] = String.join("\t", x);
                }
            }
            return String.join("\n", l);
        }));
        assertTrue(msg.contains(mine), msg);
    }

    /** A full-play row and a row betting on its own value are refused, and so is a bundle directory that exists. */
    @Test
    public void whatCannotBeExportedIsRefused() throws Exception {
        SystemScorer.Result r = results.get(0);
        StrategyExport.Refit rf = new StrategyExport.Refit(scorer, r.count, 2);
        String[] labels = {"same-8"};
        SystemScorer.Entry full = new SystemScorer.Entry(r.count.name + ", full play", r.count, false, true);
        assertThrows(IllegalArgumentException.class, () -> StrategyExport.build(scorer, rf, full, labels));
        SystemScorer.Entry own = new SystemScorer.Entry(r.count.name + ", bet on its own value", r.count, false, false, true);
        assertThrows(IllegalArgumentException.class, () -> StrategyExport.build(scorer, rf, own, labels));
        assertThrows(IllegalStateException.class, () -> StrategyExport.export(bundle, scorer, header, everyRow(), labels,
                keyColumns(), 1, new PrintStream(new ByteArrayOutputStream(), true, "UTF-8")));
    }
    /**
     * The command line, as the real export will be run: rows named in a results file written as
     * FullIndexRun writes results.ser, scored at the report's M_play, the catalog's columns in
     * keys-sample.tsv; and a full-play row named on it is refused.
     */
    @Test
    public void theCommandLineExportsRowsNamedInAResultsFile() throws Exception {
        SystemScorer published = new SystemScorer(st, new Bootstrap(st.shoes, 0, Bootstrap.SEED), SystemScorer.M_PLAY);
        List<CountKeys.Count> two = Arrays.asList(catalog("Hi-Lo"), catalog("Hi-Opt II"));
        ArrayList<SystemScorer.Result> scored = new ArrayList<>();
        for (CountKeys.Count c : two) {
            scored.add(published.score(c, CountKeys.bettingVariant(c)));
        }
        Path ser = shared.resolve("results.ser");
        try (java.io.ObjectOutputStream o = new java.io.ObjectOutputStream(Files.newOutputStream(ser))) {
            o.writeUTF(FullIndexRun.identity("test", states, st.shoes, st.size, 0, 1, two, new int[]{0, 1, 2}, null));
            o.writeObject(scored);
            o.writeObject(new double[0]);
        }
        Path saved = shared.resolve("ceiling.ser");
        writeSaved(saved, "CeilingReport\ncommit test\n" + FullIndexRun.stateFile(states) + "\ncounts none\nresamples 0",
                published.references());
        Path elsewhere = shared.resolve("ceiling-elsewhere.ser");
        writeSaved(elsewhere, "CeilingReport\ncommit test\nstates /another/file, 1 bytes, modified 0\ncounts none",
                published.references());
        Path out = shared.resolve("export-cli");
        PrintStream was = System.out;
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        System.setOut(new PrintStream(text, true, "UTF-8"));
        try {
            StrategyExport.main(new String[]{states.toString(), "--published", ser.toString(), "--out", out.toString(),
                    "--row", catalog("Hi-Opt II").name + " + ace side count@80", "--row", "Hi-Lo = Hi-Lo Lite, betting only@70,80",
                    "--chart-from", saved + "@70,80", "--plans", "same-2,same-8,own-8,played-8"});
        } finally {
            System.setOut(was);
        }
        String log = text.toString("UTF-8");
        assertTrue(log.contains("round trip passed"), log);
        assertTrue(log.contains("Chart, no count, flat at 70%: ") && log.contains("Chart, no count, flat at 80%: ")
                && !log.contains("Chart, no count, flat at 75%"), log);
        assertEquals(3 * 2 + 7, Files.list(out).count());
        IllegalStateException other = assertThrows(IllegalStateException.class, () -> StrategyExport.chartRow(
                CeilingReport.readSaved(elsewhere), states, new int[]{2}));
        assertTrue(other.getMessage().contains("were not scored on"), other.getMessage());
        ExportBundle.KeysSample ks = ExportBundle.readKeysSample(out.resolve("keys-sample.tsv"));
        assertEquals(CountKeys.catalog().size() + 1 + 8, ks.counts.length, Arrays.toString(ks.counts));
        ExportBundle.Strategy s = ExportBundle.readStrategy(out.resolve(ExportBundle.Strategy.fileName(
                catalog("Hi-Opt II").name + " + ace side count", 80, 1)));
        assertEquals(catalog("Hi-Opt II").name, s.playCount);
        assertTrue(s.ace > 0);
        assertEquals(4, s.plans.size());
        assertThrows(IllegalArgumentException.class, () -> StrategyExport.wanted(scored, "Hi-Lo = Hi-Lo Lite, full play",
                new int[]{2}));
    }
    /**
     * A full-play file, which no fixture here can fit (that takes the player trees), written from a
     * model made by hand: it reads back to the same model and the same text, its later lines in
     * B2.1's order, and a later line out of order is refused.
     */
    @Test
    public void aFullPlayFileReadsBackToItself() {
        ExportBundle.Strategy s = ExportBundle.readStrategyText(StrategyExport.format(handMadeFull()));
        String text = StrategyExport.format(s);
        assertEquals(StrategyExport.format(handMadeFull()), text);
        assertEquals(2, s.laterLo.length);
        assertTrue(s.stands[1][5]);
        assertFalse(s.stands[0][5]);
        assertTrue(text.contains("\nlater\t0\t12\t0\t1\tH\n"), text);
        assertTrue(text.contains("\nlater\t1\t18\t1\t10\t"), text);
        String swapped = text.replace("later\t0\t12\t0\t1\tH\nlater\t0\t13\t0\t1\t",
                "later\t0\t13\t0\t1\tH\nlater\t0\t12\t0\t1\t");
        assertNotEquals(text, swapped);
        assertThrows(IllegalArgumentException.class, () -> ExportBundle.readStrategyText(swapped));
    }

    static ExportBundle.Strategy handMadeFull() {
        ExportBundle.Strategy s = new ExportBundle.Strategy();
        CountKeys.Count c = catalog("Hi-Lo");
        s.row = c.name + ", full play";
        s.scope = ExportBundle.Scope.FULL;
        s.pen = 80;
        s.cut = 332;
        s.fold = 1;
        s.trained = 1234.5;
        s.playCount = c.name;
        s.tags = c.tag.clone();
        s.scale = 1;
        s.trueCounted = true;
        s.divisor = 1;
        s.deckSum = 0;
        s.betCount = c.name;
        s.betTags = c.tag.clone();
        s.betDeckSum = 0;
        s.ace = 0;
        s.lo = new int[]{-9, 1};
        s.hi = new int[]{0, 7};
        s.first = new PlayerMove[2][Deals.COUNT][];
        s.insure = new boolean[2][ScoringStates.ACE_UP];
        s.insure[1][Deals.index(10, 10, 1)] = true;
        for (int g = 0; g < 2; g++) {
            for (int i = 0; i < Deals.COUNT; i++) {
                if (!Deals.playerNatural(i)) {
                    s.first[g][i] = FullIndexFit.chartRanking(RULES, i);
                }
            }
        }
        s.laterLo = new int[]{-12, 2};
        s.laterHi = new int[]{1, 9};
        s.stands = new boolean[2][ExportBundle.CELLS];
        s.stands[1][5] = true;
        s.plans.add(new ExportBundle.Plan("same-8", "same", null, new int[]{-3, 0, 4}, new double[]{1, 1.5, 8},
                new double[]{1, 1, 1}));
        s.plans.add(new ExportBundle.Plan("X-1.0,8", "own", -1, new int[]{-3, 0, 4}, new double[]{0, 1, 8},
                new double[]{0, 0.25, 1}));
        return s;
    }

    /** A CeilingReport save as CeilingReport.save writes one, holding only these reference rows. */
    static void writeSaved(Path file, String identity, List<SystemScorer.Entry> references) throws IOException {
        try (java.io.ObjectOutputStream o = new java.io.ObjectOutputStream(Files.newOutputStream(file))) {
            o.writeUTF(identity + "\n");
            o.writeObject(new ArrayList<>());
            o.writeObject(new ArrayList<>());
            o.writeObject(new ArrayList<>());
            o.writeObject(new ArrayList<>());
            o.writeDouble(0);
            o.writeObject(new ArrayList<>(references));
            o.writeObject(new java.util.LinkedHashMap<>());
        }
    }
}
