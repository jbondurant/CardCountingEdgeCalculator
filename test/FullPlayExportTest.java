import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The full-play export (PLAN_COMPUTE.md 1.2 and 6.1) on the replay test's tiny run: two counts
 * of our own on a 12-shoe synthetic state file at 75% and 80%, fitted at full play by FullIndexRun
 * and replayed from its fit.ckpt with StrategyExport.ReplayExport given as a hook (the replay's
 * --export option is wired on its own branch, which is to construct this hook). Never pointed at
 * runs/.
 *
 * Two paths write the same bundle. The replay's hook (StrategyExport.ReplayExport) checks each
 * item's full-play rows on its strategies' Hit and Split values while it holds them, keeps the
 * files, sections and lookups in the item's record, and writes the bundle from the records at the
 * end, whose round trip holds each full section to the one checked. And StrategyExport.export,
 * given the same rows with their strategies (a test hook keeps them), round-trips every state on
 * the values themselves. The two bundles must be equal byte for byte, and a replay killed after an
 * item and resumed must write the same bundle again.
 *
 * Planted, each refused by the round trip: a later line flipped in a full-play file, a full-play
 * first move ranked second, a full-play round value in states.bin a billionth off where only the
 * checked section stands for the values, and a full-play row with neither values nor a checked
 * section.
 *
 * Planted in scratch copies of StrategyExport on 12 October 2026, one at a time, each compiled
 * and this class run against it:
 *
 * | Planted | What failed |
 * |---|---|
 * | fold 1's full play fitted on fold 0's Hit and Split values | every test: the replay's check, F1 start at 75%, state 9's round value 1.3e-4 off |
 * | the writer flips later cell 7 of group 0 | every test: fold 0's later cell 0 7 is not the play the last round walked |
 * | the round trip's check of the later lines against the walked play left out | aLaterLineFlippedIsCaught: the flipped cell is refused by lookups.tsv's later lines instead, not by the check it names |
 *
 * The split lines (SHOE_SPEC.md B2.1, B2.4 and B8; INTEGRATION.md open item 1). This fixture's
 * fits rank every deal one way, so its files have no split line and both bundles stay equal byte
 * for byte, killed and resumed or not; lookups.tsv has B8's 1,000 split lines all the same. A
 * fold whose rankings differ is made by hand (byHand): a second refit of the first 80% item's
 * strategies, its fold-0 walked ranking the strategy's with one pair deal's Split-first list
 * put second, exported alone with its one row. Planted in bundles, each refused by the round
 * trip: a split line where the two rankings agree, a first line's tail reordered, a split cell of
 * lookups.tsv flipped, lookups.tsv without its split lines or with two of them out of order; and
 * on the hand-made bundle, its first and split lines swapped, its split line dropped (on the
 * values, and without them by lookups.tsv's split cells alone), and its split cell answered by
 * the first line. A fold whose strategy's table of groups is not the refit's is kept as a failed
 * row and refused at the replay's end.
 *
 * Planted on 19 October 2026 in scratch copies of the code, one at a time, each compiled and
 * this class run against it:
 *
 * | Planted | What failed |
 * |---|---|
 * | the round trip's check of the ranking split hands read left out | aSplitLineWhereTheRankingsAgreeIsCaught (nothing refused: the added line is a pair of aces', whose split cells are all S), aSplitLineDroppedIsCaught (refused by lookups.tsv's split 2 2 2 instead) |
 * | its check of every first line whole left out | aFirstLinesTailReorderedIsCaught (refused by the split hands' check instead, deal 1 1 1) |
 * | lookups.tsv's split cells not compared | aLookupSplitCellFlippedIsCaught, aLookupSplitCellReadFromTheFirstLineIsCaught and aSplitLineDroppedIsCaught: nothing refused |
 * | readLookups's count of split lines left out | lookupsWithoutTheirSplitLinesAreCaught (refused by the line count instead: 12,020 lines, 13,020 due) |
 * | the value replay's dealt hand reading the split line | every test: the hand-made bundle's export refuses it, state 0's round value 0.023 relative off |
 * | build writing no split line | every test: the hand-made export, group 0, deal 2 2 2's split hands read P S H D, valued under S P H D |
 * | Lookup's split cells from the refit's ranking, not the walked | every test: the hand-made export, lookups.tsv's split 2 2 2 S:P, the file's S:S |
 * | Refit's check of the strategy's groups and table left out | theExportRefusesAtTheEndRatherThanStopTheReplay: the bundle with a table changed is written |
 * | the refit built before the row's try, as before (ReplayExport.after) | theExportRefusesAtTheEndRatherThanStopTheReplay: the replay stops at its item, F1 start 75% fold 1's groups |
 */
public class FullPlayExportTest {

    @TempDir
    static Path root;
    static Path states;
    static Path counts;
    static Path source;
    static Path trees;
    static String log;
    static FullIndexReplay.Assembled replayed;
    static Path bundle;
    static Path direct;
    static StrategyExport.Report directReport;
    static SystemScorer scorer;
    static final List<StrategyExport.Wanted> rows = new ArrayList<>();
    static final List<StrategyExport.Expect> expected = new ArrayList<>();
    static final StrategyExport.FullSource values = new StrategyExport.FullSource();
    static List<CountKeys.Count> keyCounts;
    static final String[] LABELS = {"same-1", "same-8", "own-8", "played-8"};
    /**
     * This fixture's fits rank every deal one way, so a fold whose rankings differ is made by hand:
     * a second refit of the first 80% item's strategies (hand), whose fold-0 walked ranking is the
     * strategy's with one pair deal's first two moves swapped where its list starts with Split
     * (handCell, {group, deal}), exported alone as handBundle with its one row and that refit.
     */
    static StrategyExport.Refit hand;
    static StrategyExport.Wanted handRow;
    static final List<StrategyExport.Expect> handExpected = new ArrayList<>();
    static final StrategyExport.FullSource handValues = new StrategyExport.FullSource();
    static int[] handCell;
    static Path handBundle;
    static String handLog;

    static final class Interrupted extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** A hook given in code that keeps each fold item's Result and full play, so export() can be given them. */
    static final class Capture implements FullIndexReplay.Hook {
        @Override
        public String name() {
            return "capture";
        }

        @Override
        public Serializable after(FullIndexReplay.Item item, SystemScorer s, SystemScorer.Result scored,
                                  List<FullIndexFit.Strategy> two) {
            if (!item.halves) {
                scorer = s;
                StrategyExport.Refit[] byPen = new StrategyExport.Refit[SystemScorer.CUTS.length];
                byPen[item.pen] = new StrategyExport.Refit(s, item.pen, two.toArray(new FullIndexFit.Strategy[0]));
                for (SystemScorer.Entry e : scored.fullEntries) {
                    if (!e.ownValue) {
                        rows.add(new StrategyExport.Wanted(scored, e, byPen, item.pen));
                        expected.add(StrategyExport.Expect.of(e, item.pen));
                        values.values.put(StrategyExport.FullSource.key(e.name, ExportBundle.PENS[item.pen]), byPen[item.pen]);
                    }
                }
                if (item.pen == 2 && hand == null) {
                    StrategyExport.Refit[] own = new StrategyExport.Refit[SystemScorer.CUTS.length];
                    hand = own[2] = new StrategyExport.Refit(s, item.pen, two.toArray(new FullIndexFit.Strategy[0]));
                    SystemScorer.Entry e = scored.fullEntries.get(0);
                    assertFalse(e.ownValue);
                    handRow = new StrategyExport.Wanted(scored, e, own, 2);
                    handExpected.add(StrategyExport.Expect.of(e, 2));
                    handValues.values.put(StrategyExport.FullSource.key(e.name, 80), hand);
                }
            }
            return null;
        }
    }

    static String[] settings(String... more) {
        String[] base = {"--states", states.toString(), "--counts", counts.toString(), "--pens", "1,2", "--resamples",
                "3", "--threads", "1"};
        String[] all = Arrays.copyOf(base, base.length + more.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    static FullIndexReplay.Options options(Path out, Path export, PrintStream log) {
        FullIndexReplay.Options o = new FullIndexReplay.Options();
        o.fitFrom = source;
        o.states = states;
        o.out = out;
        o.trees = trees;
        o.readOnlyTrees = true;
        o.countsFile = counts;
        o.pens = new int[]{1, 2};
        o.resamples = 3;
        o.threads = 1;
        o.foldsOnly = true;
        o.hooks.add(new StrategyExport.ReplayExport(export.toAbsolutePath().normalize(), states, -1, new ArrayList<>(),
                LABELS, StrategyExport.SAMPLE_SEED, log));
        return o;
    }

    @BeforeAll
    static void replayAndExport() throws Exception {
        states = ComparisonReportTest.syntheticRun(root, 79, 79, 12);
        counts = CustomCountsTest.write(root, "candidates.json", FullIndexReplayTest.COUNTS);
        source = root.resolve("run");
        trees = root.resolve("trees.bin");
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        FullIndexRun.run(settings("--out", source.toString(), "--trees", trees.toString()), "the-run's-commit", quiet, 0);
        bundle = root.resolve("export");
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        PrintStream into = new PrintStream(text, true, "UTF-8");
        FullIndexReplay.Options o = options(root.resolve("replay"), bundle, into);
        o.hooks.add(new Capture());
        replayed = FullIndexReplay.run(o, "replay", into);
        log = text.toString("UTF-8");
        List<CountKeys.Count> own = new ArrayList<>();
        for (CountKeys.Count c : replayed.counts) {
            if (!c.name.equals(CountKeys.nullCount().name)) {
                own.add(c);
            }
        }
        keyCounts = StrategyExport.keyCounts(new ArrayList<>(), own);
        direct = root.resolve("direct");
        directReport = StrategyExport.export(direct, scorer, StateStore.header(states), rows, null, LABELS, keyCounts,
                StrategyExport.SAMPLE_SEED, quiet);
        handCell = byHand(hand, 0);
        handBundle = root.resolve("hand");
        ByteArrayOutputStream handText = new ByteArrayOutputStream();
        StrategyExport.export(handBundle, scorer, StateStore.header(states), List.of(handRow), null, LABELS, keyCounts,
                StrategyExport.SAMPLE_SEED, new PrintStream(handText, true, "UTF-8"));
        handLog = handText.toString("UTF-8");
    }

    /**
     * Fold f's walked ranking given a difference made by hand: the first pair deal of rank 2 to 10,
     * in the first group that has one, whose list starts with Split and goes on with another move,
     * those two moves swapped, as a strategy whose splits were valued with split hands standing,
     * hitting or doubling where its refit splits again. The strategy's own table is not changed.
     */
    static int[] byHand(StrategyExport.Refit rf, int f) {
        PlayerMove[][][] w = new PlayerMove[rf.walked[f].length][][];
        for (int g = 0; g < w.length; g++) {
            w[g] = rf.walked[f][g].clone();
        }
        for (int g = 0; g < w.length; g++) {
            for (int i = 0; i < Deals.COUNT; i++) {
                PlayerMove[] l = w[g][i];
                if (Deals.P1[i] == Deals.P2[i] && Deals.P1[i] >= 2 && l.length >= 2 && l[0] == PlayerMove.Split
                        && l[1] != PlayerMove.Split) {
                    PlayerMove[] m = l.clone();
                    m[0] = l[1];
                    m[1] = l[0];
                    w[g][i] = m;
                    rf.walked(f, w);
                    return new int[]{g, i};
                }
            }
        }
        throw new AssertionError("no group's pair deal is ranked Split first");
    }

    /** Every file of a directory, name to bytes. */
    static Map<String, byte[]> files(Path dir) throws Exception {
        Map<String, byte[]> out = new TreeMap<>();
        try (Stream<Path> list = Files.list(dir)) {
            for (Path p : (Iterable<Path>) list::iterator) {
                out.put(p.getFileName().toString(), Files.readAllBytes(p));
            }
        }
        return out;
    }

    static void sameBundle(Path a, Path b) throws Exception {
        Map<String, byte[]> x = files(a);
        Map<String, byte[]> y = files(b);
        assertEquals(x.keySet(), y.keySet());
        for (String f : x.keySet()) {
            assertArrayEquals(x.get(f), y.get(f), f);
        }
    }

    /**
     * The replay's bundle: a full-play file for each fold of every full-play row of the run's
     * items (not those bet on their own value), each checked on its values when replayed and its
     * round trip passed on the checked sections; lookups.tsv has later lines, at contested spots
     * that stand and that hit.
     */
    @Test
    public void theReplaysExportPassesItsRoundTrip() throws Exception {
        assertTrue(Files.isDirectory(bundle), log);
        assertFalse(Files.exists(root.resolve("export.partial")));
        assertTrue(log.contains("round trip passed"), log);
        Map<String, String[]> index = ExportBundle.readIndex(bundle.resolve("index.tsv"));
        int full = 0;
        for (String[] f : index.values()) {
            if (!f[1].equals("-")) {
                assertEquals("full", f[4], Arrays.toString(f));
                assertTrue(f[1].endsWith(", full play"), f[1]);
                full++;
            }
        }
        assertEquals(rows.size() * 2, full);
        assertEquals(6, rows.size(), "three full-play rows (F2 also bets on its vector) at 75% and 80%");
        assertEquals(6, log.split("checked on its values", -1).length - 1, log);
        assertTrue(log.contains("round values as checked on its strategies' values when made"), log);
        // This fixture's fits rank every deal one way: no split line, and the log says so fold by fold.
        for (Map.Entry<String, String[]> f : index.entrySet()) {
            if (!f.getValue()[1].equals("-")) {
                assertEquals(0, ExportBundle.readStrategy(bundle.resolve(f.getKey())).splitLines(), f.getKey());
            }
        }
        for (String count : new String[]{"F1 start", "F2 start"}) {
            for (String pen : new String[]{"75%", "80%"}) {
                for (int f = 0; f < 2; f++) {
                    assertTrue(log.contains("export: " + count + " at " + pen + ", fold " + f + ": 0 split lines\n"), log);
                }
            }
        }
        ExportBundle.Lookups lk = ExportBundle.readLookups(bundle.resolve("lookups.tsv"), true);
        int later = 0;
        int stand = 0;
        int hit = 0;
        int split = 0;
        for (ExportBundle.LookupLine l : lk.lines) {
            if (l.split) {
                split++;
                for (String c : l.cells) {
                    assertTrue(c.matches("[SHDP]+(:[SHDP]+)?"), c);
                }
            }
            if (!l.later) {
                continue;
            }
            later++;
            for (String c : l.cells) {
                String[] x = c.split(":");
                if (x.length == 4 && !x[2].equals("-")) {
                    stand += x[3].equals("S") ? 1 : 0;
                    hit += x[3].equals("H") ? 1 : 0;
                }
            }
        }
        assertTrue(later > 100, later + " later lines");
        assertTrue(stand > 0 && hit > 0, stand + " contested cells stand, " + hit + " hit");
        assertEquals(1000, split, "B8's split lines");
    }

    /**
     * A fold whose rankings differ (made by hand, byHand) is exported, not refused: its file has
     * one split line, the walked list, beside a first line that is the refit's; the other fold's
     * file has none; the round trip held both to the refit and the walked play; and lookups.tsv's
     * split cell for that pair gives the walked play's answer where a resplit is allowed, not the
     * first line's. The export's log names the split line.
     */
    @Test
    public void aFoldWhoseRankingsDifferIsExportedWithASplitLine() throws Exception {
        assertTrue(Files.isRegularFile(handBundle.resolve("manifest.sha256")), handLog);
        int g = handCell[0];
        int i = handCell[1];
        ExportBundle.Strategy s = ExportBundle.readStrategy(handBundle.resolve(handFile(0)));
        assertEquals(1, s.splitLines());
        assertArrayEquals(hand.walked[0][g][i], s.split[g][i]);
        assertArrayEquals(hand.ranking[0][g][i], s.first[g][i]);
        assertEquals(PlayerMove.Split, s.dealtFirst(g, i), "the dealt pair splits");
        assertNotEquals(PlayerMove.Split, s.splitRanking(g, i)[0], "a split hand of the pair does not split first");
        assertEquals(0, ExportBundle.readStrategy(handBundle.resolve(handFile(1))).splitLines());
        assertTrue(handLog.contains(handRow.entry.name + " at 80%, fold 0: 1 split line, the first " + StrategyExport.cell(g, i)
                + ": " + StrategyExport.words(hand.ranking[0][g][i]) + " by the refit (its first line), "
                + StrategyExport.words(hand.walked[0][g][i]) + " by the walked play (its split line)\n"), handLog);
        assertTrue(handLog.contains(", fold 1: 0 split lines\n"), handLog);
        String cell = splitCell(handBundle, handFile(0), Deals.P1[i], Deals.UP[i]);
        ExportBundle.Strategy byFirst = ExportBundle.readStrategy(handBundle.resolve(handFile(0)));
        byFirst.split = null;
        String first = StrategyExport.fileSplit(byFirst, Deals.P1[i], Deals.P1[i], Deals.UP[i]);
        assertEquals(StrategyExport.fileSplit(s, Deals.P1[i], Deals.P1[i], Deals.UP[i]), cell);
        assertNotEquals(first, cell, "the split cell is the walked play's, not the first line's");
        assertEquals('P', first.charAt(first.indexOf(':') + 1 + g), first);
    }

    static String handFile(int fold) {
        return ExportBundle.Strategy.fileName(handRow.entry.name, 80, fold);
    }

    /** lookups.tsv's split cell of a file for a split r drawing its own rank against up. */
    static String splitCell(Path dir, String file, int r, int up) throws Exception {
        List<String> l = Files.readAllLines(dir.resolve("lookups.tsv"), StandardCharsets.UTF_8);
        int col = Arrays.asList(l.get(2).split("\t", -1)).indexOf(file);
        for (String line : l) {
            String[] x = line.split("\t", -1);
            if (x[0].equals("split") && x[2].equals(Integer.toString(r)) && x[3].equals(Integer.toString(r))
                    && x[4].equals(Integer.toString(up))) {
                return x[col];
            }
        }
        throw new AssertionError("no split line " + r + " " + r + " " + up);
    }

    /** The same rows exported with their strategies at hand: every state valued again on the values, and the same bundle. */
    @Test
    public void theFullRowsRoundTripOnTheirValuesAndMakeTheSameBundle() throws Exception {
        int onValues = 0;
        for (String l : directReport.lines) {
            onValues += l.contains("full play, on its strategies' Hit and Split values") ? 1 : 0;
        }
        assertEquals(rows.size(), onValues, String.join("\n", directReport.lines));
        assertTrue(directReport.worst <= StrategyExport.RELATIVE);
        sameBundle(bundle, direct);
    }

    /** A copy of the direct bundle with one text file changed, resealed as a writer with the bug would seal it. */
    static Path planted(String name, String file, java.util.function.UnaryOperator<String> change) throws Exception {
        return planted(direct, name, file, change);
    }

    /** The same, of another bundle. */
    static Path planted(Path from, String name, String file, java.util.function.UnaryOperator<String> change)
            throws Exception {
        Path dir = root.resolve("planted-" + name);
        Files.createDirectories(dir);
        for (Map.Entry<String, byte[]> f : files(from).entrySet()) {
            Files.write(dir.resolve(f.getKey()), f.getValue());
        }
        String text = new String(Files.readAllBytes(dir.resolve(file)), StandardCharsets.UTF_8);
        String changed = change.apply(text);
        assertNotEquals(text, changed, name + " changed nothing");
        Files.write(dir.resolve(file), changed.getBytes(StandardCharsets.UTF_8));
        StrategyExportTest.reseal(dir);
        return dir;
    }

    static String refusal(Path dir, StrategyExport.FullSource full) {
        return refusal(dir, expected, full);
    }

    static String refusal(Path dir, List<StrategyExport.Expect> rows, StrategyExport.FullSource full) {
        RuntimeException e = assertThrows(RuntimeException.class, () -> StrategyExport.roundTrip(dir, scorer.st,
                StateStore.header(states), rows, keyCounts, full));
        return e.getMessage();
    }

    static String firstFullFile() {
        StrategyExport.Wanted w = rows.get(0);
        return ExportBundle.Strategy.fileName(w.entry.name, ExportBundle.PENS[w.pens[0]], 1);
    }

    /** One later line of a full-play file flipped: not the later play the strategy's Hit values were worked out under. */
    @Test
    public void aLaterLineFlippedIsCaught() throws Exception {
        boolean[] done = {false};
        String msg = refusal(planted("later", firstFullFile(), t -> StrategyExportTest.lines(t, l -> {
            if (done[0] || !l.startsWith("later\t")) {
                return l;
            }
            done[0] = true;
            return l.endsWith("\tS") ? l.substring(0, l.length() - 1) + "H" : l.substring(0, l.length() - 1) + "S";
        })), values);
        assertTrue(msg.contains("is not the play the strategy's last round walked"), msg);
    }

    /** Every full-play first move ranked second: the round values the files give move off the engine's. */
    @Test
    public void aFullPlayMoveRankedWrongIsCaught() throws Exception {
        String msg = refusal(planted("ranked", firstFullFile(), t -> StrategyExportTest.lines(t, l -> {
            String[] f = l.split("\t");
            if (!f[0].equals("first") || f.length < 7) {
                return l;
            }
            String x = f[5];
            f[5] = f[6];
            f[6] = x;
            return String.join("\t", f);
        })), values);
        assertTrue(msg.contains("round value under its fold's play"), msg);
    }

    /**
     * A split line written where the two rankings agree, the deal's first line with its first two
     * moves swapped: the reader takes it (B2.1 allows it), and the round trip refuses it, since the
     * strategy's split hands were valued under the first line's ranking.
     */
    @Test
    public void aSplitLineWhereTheRankingsAgreeIsCaught() throws Exception {
        String[] added = {null};
        String msg = refusal(planted("split-added", firstFullFile(), t -> {
            String last = null;
            for (String l : t.split("\n")) {
                String[] f = l.split("\t");
                if (f[0].equals("first")) {
                    last = l;
                    if (added[0] == null && f[1].equals("0") && f.length >= 7 && !f[5].equals(f[6])) {
                        String x = f[5];
                        f[5] = f[6];
                        f[6] = x;
                        f[0] = "split";
                        added[0] = String.join("\t", f);
                    }
                }
            }
            return t.replace(last + "\n", last + "\n" + added[0] + "\n");
        }), values);
        assertTrue(msg.contains("'s split hands read") && msg.contains("were valued under the walked play's"), msg);
    }

    /** A first line's tail put in another order, its first move kept: no state's value moves, and the round trip refuses it. */
    @Test
    public void aFirstLinesTailReorderedIsCaught() throws Exception {
        boolean[] done = {false};
        String msg = refusal(planted("tail", firstFullFile(), t -> StrategyExportTest.lines(t, l -> {
            String[] f = l.split("\t");
            if (done[0] || !f[0].equals("first") || f.length < 8 || f[6].equals(f[7])) {
                return l;
            }
            done[0] = true;
            String x = f[6];
            f[6] = f[7];
            f[7] = x;
            return String.join("\t", f);
        })), values);
        assertTrue(msg.contains("'s first line is") && msg.contains("the refit ranks it"), msg);
    }

    /** One split cell of lookups.tsv with its first letter changed: not what the file's split hands play. */
    @Test
    public void aLookupSplitCellFlippedIsCaught() throws Exception {
        boolean[] done = {false};
        String msg = refusal(planted("split-cell", "lookups.tsv", t -> StrategyExportTest.lines(t, l -> {
            String[] x = l.split("\t", -1);
            if (done[0] || !x[0].equals("split") || x[2].equals("1")) {
                return l;
            }
            done[0] = true;
            x[6] = (x[6].charAt(0) == 'S' ? "H" : "S") + x[6].substring(1);
            return String.join("\t", x);
        })), values);
        assertTrue(msg.contains("lookups.tsv line") && msg.contains("(split 2 1 1)"), msg);
    }

    /**
     * lookups.tsv without its split lines, as a writer of the words before 18 October writes it:
     * refused, never read another way; and with two of them out of B8's order, refused too.
     */
    @Test
    public void lookupsWithoutTheirSplitLinesAreCaught() throws Exception {
        String msg = refusal(planted("split-lines", "lookups.tsv", t -> StrategyExportTest.without(t, "split\t")), values);
        assertTrue(msg.contains("0 split lines; the bundle holds a file of scope full, so all 1000 are due"), msg);
        msg = refusal(planted("split-order", "lookups.tsv", t -> {
            int a = t.indexOf("\nsplit\t-\t1\t1\t1\t");
            int b = t.indexOf("\nsplit\t-\t1\t2\t1\t");
            int c = t.indexOf('\n', b + 1);
            return t.substring(0, a) + t.substring(b, c) + t.substring(a, b) + t.substring(c);
        }), values);
        assertTrue(msg.contains("lookups.tsv line") && msg.contains("split 1 1 1 is due"), msg);
    }

    /**
     * The hand-made bundle's fold-0 file with its lines changed by f, which returns a line
     * unchanged where it does not apply and null for a line taken out; the bundle resealed.
     */
    static Path handPlanted(String name, java.util.function.UnaryOperator<String> f) throws Exception {
        return planted(handBundle, name, handFile(0), t -> {
            StringBuilder b = new StringBuilder();
            boolean any = false;
            for (String l : t.split("\n", -1)) {
                String x = f.apply(l);
                if (x != null) {
                    b.append(any ? "\n" : "").append(x);
                    any = true;
                }
            }
            return b.toString();
        });
    }

    /** The hand-made split line, and its deal's first line, as the file writes them. */
    static String[] handLines() {
        int g = handCell[0];
        int i = handCell[1];
        String deal = g + "\t" + Deals.P1[i] + "\t" + Deals.P2[i] + "\t" + Deals.UP[i] + "\t";
        return new String[]{"first\t" + deal + StrategyExport.words(hand.ranking[0][g][i]).replace(' ', '\t'),
                "split\t" + deal + StrategyExport.words(hand.walked[0][g][i]).replace(' ', '\t')};
    }

    /**
     * The first and split lines swapped where they differ: the dealt pair then plays the walked
     * play's first move, not Split, and takes that move's value, not the held value of Split, so
     * the round values move; and split hands read the refit's ranking.
     */
    @Test
    public void firstAndSplitSwappedAreCaught() throws Exception {
        String[] two = handLines();
        String first = two[0];
        String split = two[1];
        String msg = refusal(handPlanted("swapped", l -> l.equals(first) ? "first" + split.substring(5)
                : l.equals(split) ? "split" + first.substring(5) : l), handExpected, handValues);
        assertTrue(msg.contains("round value under its fold's play"), msg);
    }

    /**
     * The split line dropped: the file then says the two rankings agree. On the strategy's values
     * the round trip refuses it, as the split hands read the first line; without them, only
     * lookups.tsv's split cells can show it, and they do: the engine's split hands resplit no
     * more where the file's would.
     */
    @Test
    public void aSplitLineDroppedIsCaught() throws Exception {
        String split = handLines()[1];
        Path dir = handPlanted("dropped", l -> l.equals(split) ? null : l);
        String msg = refusal(dir, handExpected, handValues);
        assertTrue(msg.contains("'s split hands read") && msg.contains("were valued under the walked play's"), msg);
        StrategyExport.FullSource checked = new StrategyExport.FullSource();
        for (ExportBundle.Section sec : ExportBundle.readStates(handBundle.resolve("states.bin")).sections) {
            checked.verified.put(StrategyExport.FullSource.key(sec.row, sec.pen), sec);
        }
        assertNotNull(StrategyExport.roundTrip(handBundle, scorer.st, StateStore.header(states), handExpected, keyCounts,
                checked), "the bundle as written passes on its checked sections");
        int i = handCell[1];
        msg = refusal(dir, handExpected, checked);
        assertTrue(msg.contains("lookups.tsv line") && msg.contains("(split " + Deals.P1[i] + " " + Deals.P1[i] + " "
                + Deals.UP[i] + ")") && msg.contains(handFile(0)), msg);
    }

    /** lookups.tsv's split cell answered by the first line where the file has a split line: not what its split hands play. */
    @Test
    public void aLookupSplitCellReadFromTheFirstLineIsCaught() throws Exception {
        int i = handCell[1];
        int r = Deals.P1[i];
        int up = Deals.UP[i];
        ExportBundle.Strategy byFirst = ExportBundle.readStrategy(handBundle.resolve(handFile(0)));
        byFirst.split = null;
        String first = StrategyExport.fileSplit(byFirst, r, r, up);
        String[] col = {null};
        String msg = refusal(planted(handBundle, "split-by-first", "lookups.tsv", t -> StrategyExportTest.lines(t, l -> {
            String[] x = l.split("\t", -1);
            if (x[0].equals("line")) {
                col[0] = Integer.toString(Arrays.asList(x).indexOf(handFile(0)));
            }
            if (!x[0].equals("split") || !x[2].equals(Integer.toString(r)) || !x[3].equals(Integer.toString(r))
                    || !x[4].equals(Integer.toString(up))) {
                return l;
            }
            x[Integer.parseInt(col[0])] = first;
            return String.join("\t", x);
        })), handExpected, handValues);
        assertTrue(msg.contains("lookups.tsv line") && msg.contains("(split " + r + " " + r + " " + up + ")"), msg);
    }

    /** The checked sections, as the replay's records keep them. */
    @SuppressWarnings("unchecked")
    static StrategyExport.FullSource verified() throws Exception {
        StrategyExport.FullSource v = new StrategyExport.FullSource();
        for (int k = 0; k < replayed.items.size(); k++) {
            Object x = replayed.record(k).kept.get("export");
            for (StrategyExport.RowExport r : (List<StrategyExport.RowExport>) x) {
                v.verified.put(StrategyExport.FullSource.key(r.expect.row, r.expect.pen), r.section);
            }
        }
        return v;
    }

    /**
     * Without the values, a full-play section is held to the one checked on them: a round value
     * of states.bin a billionth off is refused, and so is a full-play row with neither.
     */
    @Test
    public void aFullSectionMovedIsCaughtWithoutTheValues() throws Exception {
        assertNotNull(StrategyExport.roundTrip(direct, scorer.st, StateStore.header(states), expected, keyCounts, verified()));
        Path dir = root.resolve("planted-rv");
        Files.createDirectories(dir);
        for (Map.Entry<String, byte[]> f : files(direct).entrySet()) {
            Files.write(dir.resolve(f.getKey()), f.getValue());
        }
        ExportBundle.States s = ExportBundle.readStates(dir.resolve("states.bin"));
        ExportBundle.Section sec = s.sections.get(0);
        sec.rvPlay[sec.rvPlay.length / 2] *= 1 + 1e-9;
        Files.delete(dir.resolve("states.bin"));
        StrategyExport.writeStates(dir.resolve("states.bin"), StateStore.header(states), scorer.st, s.sections);
        StrategyExportTest.reseal(dir);
        String msg = refusal(dir, verified());
        assertTrue(msg.contains("is not the one checked against the strategies' values"), msg);
        msg = refusal(direct, new StrategyExport.FullSource());
        assertTrue(msg.contains("plays full and neither its values nor a section checked with them is given"), msg);
    }

    /**
     * A row that could not be exported is kept, not thrown, so the replay goes on; at its end the
     * export writes no bundle and names the row. A row asked for that no item replayed is refused too.
     */
    @Test
    public void theExportRefusesAtTheEndRatherThanStopTheReplay() throws Exception {
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        Path export = root.resolve("export-failed");
        FullIndexReplay.Options o = options(root.resolve("failed"), export, quiet);
        o.hooks.clear();
        o.hooks.add(new StrategyExport.ReplayExport(export, states, -1, new ArrayList<>(), LABELS,
                StrategyExport.SAMPLE_SEED, quiet) {
            @Override
            public Serializable after(FullIndexReplay.Item item, SystemScorer s, SystemScorer.Result scored,
                                      List<FullIndexFit.Strategy> two) {
                Serializable kept = super.after(item, s, scored, two);
                if (kept != null && item.pen == 2) {
                    @SuppressWarnings("unchecked")
                    List<StrategyExport.RowExport> rs = (List<StrategyExport.RowExport>) kept;
                    rs.get(0).failure = "planted";
                }
                return kept;
            }
        });
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> FullIndexReplay.run(o, "replay", quiet));
        assertTrue(e.getMessage().contains("rows that could not be exported") && e.getMessage().contains("at 80%: planted"),
                e.getMessage());
        assertTrue(Files.isRegularFile(root.resolve("failed").resolve("replay.txt")), "the replay's own outputs are written");
        assertFalse(Files.exists(export));
        assertFalse(Files.exists(root.resolve("export-failed.partial")));

        // A fold whose strategy's table of groups is not the refit's: its split hands were not played
        // in the groups the file gives, so the item's rows are refused, kept, and named at the end.
        Path tables = root.resolve("export-tables");
        FullIndexReplay.Options t = options(root.resolve("tables"), tables, quiet);
        t.hooks.clear();
        t.hooks.add(new StrategyExport.ReplayExport(tables, states, -1, new ArrayList<>(), LABELS,
                StrategyExport.SAMPLE_SEED, quiet) {
            @Override
            public Serializable after(FullIndexReplay.Item item, SystemScorer s, SystemScorer.Result scored,
                                      List<FullIndexFit.Strategy> two) {
                int[] table = two.get(1).table;
                int was = table[0];
                if (item.pen == 1) {
                    table[0] = was == 0 ? 1 : 0;
                }
                try {
                    return super.after(item, s, scored, two);
                } finally {
                    table[0] = was;
                }
            }
        });
        e = assertThrows(IllegalStateException.class, () -> FullIndexReplay.run(t, "replay", quiet));
        assertTrue(e.getMessage().contains("F1 start, full play at 75%: F1 start 75% fold 1: the strategy's groups")
                && e.getMessage().contains("or their table are not the refit's") && !e.getMessage().contains("at 80%"),
                e.getMessage());
        assertFalse(Files.exists(tables));

        Path none = root.resolve("export-none");
        FullIndexReplay.Options n = options(root.resolve("none"), none, quiet);
        n.hooks.clear();
        n.hooks.add(new StrategyExport.ReplayExport(none, states, -1, Arrays.asList("No such row, full play@80"), LABELS,
                StrategyExport.SAMPLE_SEED, quiet));
        e = assertThrows(IllegalStateException.class, () -> FullIndexReplay.run(n, "replay", quiet));
        assertTrue(e.getMessage().contains("is not a full-play row of any item replayed"), e.getMessage());
    }

    /** A replay killed after its first item and resumed writes the same bundle as one that ran through. */
    @Test
    public void aReplayKilledAndResumedExportsTheSameBundle() throws Exception {
        Path out = root.resolve("killed");
        Path export = root.resolve("export-killed");
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        FullIndexReplay.Options a = options(out, export, quiet);
        a.batchBytes = 1;
        a.afterItem = n -> {
            if (n == 1) {
                throw new Interrupted();
            }
        };
        assertThrows(Interrupted.class, () -> FullIndexReplay.run(a, "replay", quiet));
        assertFalse(Files.exists(export));
        FullIndexReplay.Options again = options(out, export, quiet);
        again.batchBytes = 1;
        again.resume = true;
        FullIndexReplay.run(again, "replay", quiet);
        sameBundle(bundle, export);
    }
}
