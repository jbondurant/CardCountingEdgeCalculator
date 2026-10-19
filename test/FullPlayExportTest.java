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
 * The split lines (SHOE_SPEC.md B2.1, B2.4, B7 and B8; INTEGRATION.md open item 1). This
 * fixture's fits rank every deal one way, and each of its files has one group, so its files have
 * no split line and both bundles stay equal byte for byte, killed and resumed or not; lookups.tsv
 * has B8's 1,000 split lines all the same. A fold whose rankings differ is made by hand (byHand):
 * a second refit of the first 80% item's strategies whose fold-0 first lines are the refit's with
 * one pair deal's moves after Split reordered, at a split the last round valued, so its split
 * line is the walked play its split was valued under; exported alone with its one row. Planted in
 * bundles, each refused by the round trip: a split line where the two rankings agree, a first
 * line's tail reordered, a split cell of lookups.tsv flipped, lookups.tsv without its split lines
 * or with two of them out of order; and on the hand-made bundle, its first and split lines
 * swapped, its split line dropped (on the values, and without them by lookups.tsv's split cells
 * alone), and its split cell answered by the first line. A third refit's walked ranking changed
 * by hand instead (walkedByHand), its values not: refused, since its split line is not the play
 * the split was valued under, which only the splits the last round valued, played again from the
 * file, can show. A fold whose strategy's table of groups is not the refit's is kept as a failed
 * row and refused at the replay's end, and so is a row whose build throws without a message; the
 * hook handed its strategies in the wrong order throws. Where a fold must rank deals two ways in
 * more than one group, FullIndexFitTest's capped run gives one (Capped): its files are held to its
 * strategies and fit apart from Refit, and planted wrong in a group past the first, refused.
 *
 * Planted on 19 October 2026 in scratch copies of the code, one at a time, each compiled and
 * this class run against it. The first eight were planted first on 134da21 and again on the code
 * after its review, whose outcomes these are:
 *
 * | Planted | What failed |
 * |---|---|
 * | the round trip's check of the ranking split hands read left out | aSplitLineWhereTheRankingsAgreeIsCaught (nothing refused: the added line is a pair of aces', whose split cells are all S); aSplitLineDroppedIsCaught (refused by the splits played again instead: 2s against a 2, H by the file, S as valued); aCappedFitsFilesWrongInAnyGroupAreCaught (the split line dropped in fold 1's last group, whose split hands play the same either way: nothing refused) |
 * | its check of every first line whole left out | aFirstLinesTailReorderedIsCaught, firstAndSplitSwappedAreCaught and aCappedFitsFilesWrongInAnyGroupAreCaught (refused by the split hands' check instead: deal 1 1 1, deal 2 2 2, and group 2's deal 2 3 1) |
 * | lookups.tsv's split cells not compared | aLookupSplitCellFlippedIsCaught, aLookupSplitCellReadFromTheFirstLineIsCaught and aSplitLineDroppedIsCaught: nothing refused |
 * | readLookups's count of split lines left out | lookupsWithoutTheirSplitLinesAreCaught (refused by the line count instead: 12,020 lines, 13,020 due) |
 * | the value replay's dealt hand reading the split line | aSplitLineWhereTheRankingsAgreeIsCaught, aSplitLineNotThePlayItsSplitWasValuedUnderIsCaught and both capped tests: a round value off (the capped row's state 0 by 0.030 relative) |
 * | build writing no split line | every test: the hand-made export, group 0, deal 2 2 2's split hands read P H S D, valued under P S H D |
 * | Lookup's split cells from the refit's ranking, not the walked | every test: the hand-made export, lookups.tsv's split 2 2 2 H:P, the file's S:P |
 * | Refit's check of the strategy's groups and table left out | theExportRefusesAtTheEndRatherThanStopTheReplay: the bundle with a table changed is written |
 * | Refit's walked ranking the refit's, the one ranking of before the split lines (the review's plant) | aCappedFitsSplitLinesAreItsWalkedPlayInEveryGroup (fold 0's split hands read the refit's ranking, not the strategy's) and aCappedFitsFilesWrongInAnyGroupAreCaught (no split line left to drop); this fixture's 12 shoes rank every deal one way and cannot show it |
 * | the splits the last round valued not played again (playedAsValued left out) | aSplitLineNotThePlayItsSplitWasValuedUnderIsCaught and aCappedFitsFilesWrongInAnyGroupAreCaught (nothing refused), aCappedFitsSplitLinesAreItsWalkedPlayInEveryGroup and aFoldWhoseRankingsDifferIsExportedWithASplitLine (0 splits played) |
 * | the fingerprints played against worked out again from Refit.walked, not taken from the strategy | aSplitLineNotThePlayItsSplitWasValuedUnderIsCaught and aCappedFitsFilesWrongInAnyGroupAreCaught: nothing refused |
 * | a valued split's group and later group swapped (FullIndexFit.valuedSplits) | aCappedFitsSplitLinesAreItsWalkedPlayInEveryGroup: a split of aces against an ace in group 1, later group 2, which the file's groups do not have (one group and one later group here cannot show it) |
 * | the file's split hands' later decisions taken from the chart, not from its later lines (filePlay) | every test: the replay's export refuses F1 start at 75% and 80%, fold 0, a split of 2s against a 4, the later decision on hard 12: S by the file, H as valued |
 * | every exception of Refit kept as the rows' failure, as 134da21 kept it | aMistakeInTheStrategiesHandedStopsTheReplay: the strategies handed in the wrong order, nothing thrown |
 * | B7's refusal of a fold not caught, thrown out of the hook (as the refit built before the row's try did before 134da21) | theExportRefusesAtTheEndRatherThanStopTheReplay: the replay stops at its item, F1 start 75% fold 1's groups |
 * | a row's failure its exception's message alone, null where it has none | theExportRefusesAtTheEndRatherThanStopTheReplay: finish takes the row for written and throws a NullPointerException |
 * | the round trip's checks of every first line and split ranking in group 0 alone (the review's plant A) | aCappedFitsFilesWrongInAnyGroupAreCaught: the tail reordered in fold 1's last group, nothing refused |
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
     * a second refit of the first 80% item's strategies (hand), whose fold-0 ranking, the first
     * lines, is the refit's with one pair deal's moves after Split put in another order, at a
     * (group, deal) whose split the last round valued (handCell, {group, deal}; byHand). The dealt
     * pair still splits, at the value it had; the walked ranking and the values are the
     * strategy's own, so the file's split line is the play its split was valued under. It is
     * exported alone as handBundle with its one row and that refit.
     */
    static StrategyExport.Refit hand;
    static StrategyExport.Wanted handRow;
    static final List<StrategyExport.Expect> handExpected = new ArrayList<>();
    static final StrategyExport.FullSource handValues = new StrategyExport.FullSource();
    static int[] handCell;
    static Path handBundle;
    static String handLog;
    /**
     * A third refit of the same strategies whose fold-0 walked ranking is changed by hand instead,
     * a pair's Split put second where the walked play splits first (walkedCell): a split line
     * that is not the play the split was valued under, which only the valued splits' fingerprints
     * can show.
     */
    static StrategyExport.Refit walkedByHand;
    static int[] walkedCell;
    /** What ReplayExport.after threw when handed the first 75% item's strategies in the wrong order. */
    static Throwable wiring;

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
                    walkedByHand = new StrategyExport.Refit(s, item.pen, two.toArray(new FullIndexFit.Strategy[0]));
                }
                if (item.pen == 1 && wiring == null) {
                    // The hook handed its strategies in the wrong order: a mistake, which must stop the replay.
                    StrategyExport.ReplayExport x = new StrategyExport.ReplayExport(root.resolve("export-wiring"), states,
                            -1, new ArrayList<>(), LABELS, StrategyExport.SAMPLE_SEED,
                            new PrintStream(new ByteArrayOutputStream()));
                    try {
                        x.after(item, s, scored, List.of(two.get(1), two.get(0)));
                        wiring = new AssertionError("nothing was thrown");
                    } catch (RuntimeException ex) {
                        wiring = ex;
                    }
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
        // The whole catalog and the replay's own counts, as the replay's export samples their keys.
        keyCounts = StrategyExport.keyCounts(CountKeys.catalog(), own);
        direct = root.resolve("direct");
        directReport = StrategyExport.export(direct, scorer, StateStore.header(states), rows, null, LABELS, keyCounts,
                StrategyExport.SAMPLE_SEED, quiet);
        handCell = byHand(hand, 0);
        walkedCell = walkedByHand(walkedByHand, 0);
        handBundle = root.resolve("hand");
        ByteArrayOutputStream handText = new ByteArrayOutputStream();
        StrategyExport.export(handBundle, scorer, StateStore.header(states), List.of(handRow), null, LABELS, keyCounts,
                StrategyExport.SAMPLE_SEED, new PrintStream(handText, true, "UTF-8"));
        handLog = handText.toString("UTF-8");
    }

    /**
     * The first split fold f's last round valued of a pair of rank 2 to 10 whose ranking, the
     * refit's and the walked play's alike on this fixture, starts with Split and goes on with two
     * other moves that differ: {group, deal}, from the strategy's valued splits.
     */
    static int[] splitFirstCell(StrategyExport.Refit rf, int f) {
        for (FullIndexFit.ValuedSplit v : rf.valued.get(f)) {
            PlayerMove[] l = rf.ranking[f][v.group][v.deal];
            if (Deals.P1[v.deal] >= 2 && l.length >= 3 && l[0] == PlayerMove.Split && l[1] != PlayerMove.Split
                    && l[2] != PlayerMove.Split && l[1] != l[2]) {
                assertArrayEquals(l, rf.walked[f][v.group][v.deal]);
                return new int[]{v.group, v.deal};
            }
        }
        throw new AssertionError("no valued split's pair is ranked Split first");
    }

    /** A ranking table, [g][deal], with one list given anew. */
    static PlayerMove[][][] with(PlayerMove[][][] table, int[] cell, PlayerMove[] moves) {
        PlayerMove[][][] out = new PlayerMove[table.length][][];
        for (int g = 0; g < out.length; g++) {
            out[g] = table[g].clone();
        }
        out[cell[0]][cell[1]] = moves;
        return out;
    }

    /**
     * Fold f's refit ranking given a difference made by hand (splitFirstCell): the second and
     * third moves swapped, so the dealt pair still splits first, and a split hand of the pair
     * drawing its own rank without Split offered plays the third move by the first line, the
     * second by the walked play its split was valued under. The cells counted again; the values,
     * the fit's moves and the walked ranking are the strategy's.
     */
    static int[] byHand(StrategyExport.Refit rf, int f) {
        int[] cell = splitFirstCell(rf, f);
        PlayerMove[] m = rf.ranking[f][cell[0]][cell[1]].clone();
        PlayerMove x = m[1];
        m[1] = m[2];
        m[2] = x;
        rf.ranking[f] = with(rf.ranking[f], cell, m);
        rf.walked(f, rf.walked[f]);
        return cell;
    }

    /**
     * Fold f's walked ranking given a difference made by hand instead, the first two moves swapped
     * at splitFirstCell: a split hand of the pair drawing its own rank with Split offered stands,
     * hits or doubles by it, where the strategy's split was valued resplitting. The values are not
     * changed, so the split line is not the play they were worked out under.
     */
    static int[] walkedByHand(StrategyExport.Refit rf, int f) {
        int[] cell = splitFirstCell(rf, f);
        PlayerMove[] m = rf.walked[f][cell[0]][cell[1]].clone();
        PlayerMove x = m[0];
        m[0] = m[1];
        m[1] = x;
        rf.walked(f, with(rf.walked[f], cell, m));
        return cell;
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
        // keys-sample.tsv has the whole catalog's columns, though the replay scored two counts of its
        // own: the simulator's D2 fails a bundle without them (SimReadsSplitLinesTest).
        List<String> sampled = Arrays.asList(ExportBundle.readKeysSample(bundle.resolve("keys-sample.tsv")).counts);
        for (CountKeys.Count c : CountKeys.catalog()) {
            assertTrue(sampled.contains(c.name), c.name + " in " + sampled);
            CountKeys.Count v = CountKeys.bettingVariant(c);
            assertTrue(v == null || sampled.contains(v.name), c.name + "'s variant in " + sampled);
        }
        assertTrue(sampled.containsAll(List.of(CountKeys.nullCount().name, "F1 start", "F2 start")), sampled.toString());
    }

    /**
     * A fold whose rankings differ (made by hand, byHand) is exported, not refused: its file has
     * one split line, the walked list, beside a first line that is the refit's; the other fold's
     * file has none; the round trip held both to the refit and the walked play, and played every
     * split the last round valued again from the files; and lookups.tsv's split cell for that pair
     * gives the walked play's answer where Split is not offered, not the first line's. The
     * export's log names the split line.
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
        assertEquals(hand.walked[0][g][i][1], s.splitRanking(g, i)[1]);
        assertNotEquals(s.first[g][i][1], s.splitRanking(g, i)[1], "a split hand of the pair reads the walked list");
        assertEquals(0, ExportBundle.readStrategy(handBundle.resolve(handFile(1))).splitLines());
        assertTrue(handLog.contains(handRow.entry.name + " at 80%, fold 0: 1 split line, the first " + StrategyExport.cell(g, i)
                + ": " + StrategyExport.words(hand.ranking[0][g][i]) + " by the refit (its first line), "
                + StrategyExport.words(hand.walked[0][g][i]) + " by the walked play (its split line)\n"), handLog);
        assertTrue(handLog.contains(", fold 1: 0 split lines\n"), handLog);
        java.util.regex.Matcher played = java.util.regex.Pattern.compile("played again from the files (\\d+) splits the "
                + "last round valued").matcher(handLog);
        assertTrue(played.find() && Integer.parseInt(played.group(1)) > 0, handLog);
        String cell = splitCell(handBundle, handFile(0), Deals.P1[i], Deals.UP[i]);
        ExportBundle.Strategy byFirst = ExportBundle.readStrategy(handBundle.resolve(handFile(0)));
        byFirst.split = null;
        String first = StrategyExport.fileSplit(byFirst, Deals.P1[i], Deals.P1[i], Deals.UP[i]);
        assertEquals(StrategyExport.fileSplit(s, Deals.P1[i], Deals.P1[i], Deals.UP[i]), cell);
        assertNotEquals(first, cell, "the split cell is the walked play's, not the first line's");
        assertEquals(ExportBundle.letter(hand.walked[0][g][i][1]), cell.charAt(g), cell);
        assertEquals(ExportBundle.letter(hand.ranking[0][g][i][1]), first.charAt(g), first);
        assertEquals('P', cell.charAt(cell.indexOf(':') + 1 + g), cell);
    }

    /**
     * A split line that is not the play the split was valued under (walkedByHand: the walked
     * ranking changed, the values not) is refused on the values: every other check holds the file
     * to Refit.walked, which is wrong here, and only the splits the last round valued, played
     * again from the file, show it.
     */
    @Test
    public void aSplitLineNotThePlayItsSplitWasValuedUnderIsCaught() {
        int g = walkedCell[0];
        int i = walkedCell[1];
        assertEquals(1, walkedByHand.rankingDiffers[0]);
        StrategyExport.Built b = StrategyExport.build(scorer, walkedByHand, handRow.entry, LABELS);
        assertArrayEquals(walkedByHand.walked[0][g][i], b.fold[0].split[g][i]);
        RuntimeException e = assertThrows(RuntimeException.class, () -> StrategyExport.checkFull(scorer.st,
                StrategyExport.chartArray(), b, walkedByHand, StrategyExport.Expect.of(handRow.entry, 2)));
        assertTrue(e.getMessage().contains("fold 0: a split of " + Deals.P1[i] + "s against " + Deals.UP[i] + " in group "
                + g + ", later group ") && e.getMessage().contains("by the file at the two-card decision on a second card "
                + Deals.P1[i] + ", Split offered, and were valued playing P"), e.getMessage());
    }

    /**
     * Only B7's refusal of a fold is kept as a failed row: ReplayExport handed its item's two
     * strategies in the wrong order, a mistake in what the replay gives it, throws, which stops a
     * replay at that item rather than at its end.
     */
    @Test
    public void aMistakeInTheStrategiesHandedStopsTheReplay() {
        assertTrue(wiring instanceof IllegalArgumentException, String.valueOf(wiring));
        assertTrue(wiring.getMessage().contains("is not fold 0's finished full-play strategy"), wiring.getMessage());
    }

    /**
     * FullIndexFitTest's capped run (its 16 shoes, play groups formed with mPlay 1, the fit
     * stopped at its pass cap after pass 0): Hi-Lo's two 80% fold strategies after their last
     * round, its full-play row, and the Result the scorer made of them. This class's own files
     * have one group each, so a fold whose rankings differ in more than one group is had there.
     */
    static final class Capped {
        final SystemScorer scorer;
        final List<FullIndexFit.Strategy> two;
        final SystemScorer.Result result;
        final SystemScorer.Entry row;

        Capped(FullIndexFitTest.Run run) {
            scorer = run.fx.scorer;
            two = FullIndexFit.strategiesOf(run.fits.get(FullIndexFitTest.HI_LO), 2, false);
            result = run.results.get(FullIndexFitTest.HI_LO);
            SystemScorer.Entry e = null;
            for (SystemScorer.Entry x : result.fullEntries) {
                if (!x.ownValue && e == null) {
                    e = x;
                }
            }
            row = e;
        }

        StrategyExport.Refit refit() {
            return new StrategyExport.Refit(scorer, 2, two.toArray(new FullIndexFit.Strategy[0]));
        }

        /** The row built from rf, its fold files changed by change, and checked on its values as the replay's export checks it. */
        StrategyExport.Report check(StrategyExport.Refit rf, java.util.function.Consumer<StrategyExport.Built> change) {
            StrategyExport.Built b = StrategyExport.build(scorer, rf, row, new String[]{"same-8"});
            change.accept(b);
            return StrategyExport.checkFull(scorer.st, StrategyExport.chartArray(), b, rf, StrategyExport.Expect.of(row, 2));
        }
    }

    private static Capped capped;

    static synchronized Capped capped() throws Exception {
        if (capped == null) {
            capped = new Capped(FullIndexFitTest.capped());
        }
        return capped;
    }

    /**
     * A fit stopped at its pass cap: the capped run's Hi-Lo at 80%, whose refit ranks deals
     * otherwise than the play its splits were valued under in every group, 2 groups in fold 0 and
     * 3 in fold 1. Its files, as built and read back, hold first lines that are the scorer's own
     * fit's ranking whole (Result.fullFirst) and split hands that read the strategy's walked
     * ranking (now.ranking), each read from the fit and the strategy, not from Refit, with a split
     * line wherever the two differ: 109 in fold 0 and 22 in fold 1, in each group. Checked on its
     * values as the replay's export checks a row, it passes, playing all 500 splits the last round
     * valued again from the files.
     */
    @Test
    public void aCappedFitsSplitLinesAreItsWalkedPlayInEveryGroup() throws Exception {
        Capped c = capped();
        StrategyExport.Refit rf = c.refit();
        StrategyExport.Built b = StrategyExport.build(c.scorer, rf, c.row, new String[]{"same-8"});
        int[] differ = new int[2];
        for (int f = 0; f < 2; f++) {
            ExportBundle.Strategy s = ExportBundle.readStrategyText(StrategyExport.format(b.fold[f]));
            PlayerMove[][][] ranked = FullIndexFit.rankings(c.scorer.st, c.scorer.rules(), c.result.fullFirst[2][f]);
            PlayerMove[][][] walked = c.two.get(f).now.ranking;
            assertEquals(f == 0 ? 2 : 3, s.lo.length, "fold " + f + "'s groups");
            for (int g = 0; g < s.lo.length; g++) {
                int inGroup = 0;
                for (int i = 0; i < Deals.COUNT; i++) {
                    if (Deals.playerNatural(i)) {
                        continue;
                    }
                    assertArrayEquals(ranked[g][i], s.first[g][i], "fold " + f + ", first " + g + " " + i);
                    assertArrayEquals(walked[g][i], s.splitRanking(g, i), "fold " + f + ", split hands' " + g + " " + i);
                    inGroup += Arrays.equals(ranked[g][i], walked[g][i]) ? 0 : 1;
                }
                assertTrue(inGroup > 0, "fold " + f + ", group " + g + " ranks a deal two ways");
                differ[f] += inGroup;
            }
            assertEquals(differ[f], s.splitLines(), "fold " + f);
        }
        assertArrayEquals(new int[]{109, 22}, differ);
        StrategyExport.Report r = StrategyExport.checkFull(c.scorer.st, StrategyExport.chartArray(), b, rf,
                StrategyExport.Expect.of(c.row, 2));
        assertEquals(rf.valued.get(0).size() + rf.valued.get(1).size(), r.splits);
        assertEquals(500, r.splits);
    }

    /**
     * On the capped fit, each refused by the check on its values. Its split hands given the
     * refit's ranking, as before the split lines (walked set to the refit's, so no split line is
     * written and every check that reads Refit.walked agrees): by the splits its last round
     * valued, played again from the files. In fold 1's last group, a first line's tail reordered,
     * at a deal of two ranks 2 to 10 with no split line whose first move every split hand may play,
     * so no round value and no split moves: by the check of every group's first lines. And a split
     * line of that group dropped: by the check of the ranking split hands read.
     */
    @Test
    public void aCappedFitsFilesWrongInAnyGroupAreCaught() throws Exception {
        Capped c = capped();
        StrategyExport.Refit before = c.refit();
        for (int f = 0; f < 2; f++) {
            before.walked(f, before.ranking[f]);
            assertEquals(0, before.rankingDiffers[f]);
        }
        String msg = assertThrows(IllegalStateException.class, () -> c.check(before, b -> { })).getMessage();
        assertTrue(msg.contains("full play at 80%, fold 0: a split of ") && msg.contains("and were valued playing"), msg);

        StrategyExport.Refit rf = c.refit();
        int last = rf.ranking[1].length - 1;
        int tail = -1;
        int split = -1;
        for (int i = 0; i < Deals.COUNT; i++) {
            if (Deals.playerNatural(i)) {
                continue;
            }
            PlayerMove[] l = rf.ranking[1][last][i];
            boolean agree = Arrays.equals(l, rf.walked[1][last][i]);
            if (tail < 0 && agree && Deals.P1[i] >= 2 && Deals.P1[i] != Deals.P2[i] && l.length >= 3 && l[1] != l[2]) {
                tail = i;
            }
            if (split < 0 && !agree) {
                split = i;
            }
        }
        assertTrue(last > 0 && tail >= 0 && split >= 0, last + " " + tail + " " + split);
        int at = tail;
        msg = assertThrows(IllegalStateException.class, () -> c.check(rf, b -> {
            PlayerMove[][][] first = b.fold[1].first.clone();
            first[last] = first[last].clone();
            PlayerMove[] m = first[last][at].clone();
            PlayerMove x = m[1];
            m[1] = m[2];
            m[2] = x;
            first[last][at] = m;
            b.fold[1].first = first;
        })).getMessage();
        assertTrue(msg.contains("fold 1: " + StrategyExport.cell(last, at) + "'s first line is "), msg);
        int dropped = split;
        msg = assertThrows(IllegalStateException.class, () -> c.check(rf, b -> {
            PlayerMove[][][] lines = b.fold[1].split.clone();
            lines[last] = lines[last].clone();
            lines[last][dropped] = null;
            b.fold[1].split = lines;
        })).getMessage();
        assertTrue(msg.contains("fold 1: " + StrategyExport.cell(last, dropped) + "'s split hands read "), msg);
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
     * The first and split lines swapped where they differ: both start with Split, so the dealt
     * pair still splits and no round value moves; the first line is then not the refit's ranking
     * and split hands read the refit's, and the round trip refuses it.
     */
    @Test
    public void firstAndSplitSwappedAreCaught() throws Exception {
        String[] two = handLines();
        String first = two[0];
        String split = two[1];
        String msg = refusal(handPlanted("swapped", l -> l.equals(first) ? "first" + split.substring(5)
                : l.equals(split) ? "split" + first.substring(5) : l), handExpected, handValues);
        assertTrue(msg.contains(StrategyExport.cell(handCell[0], handCell[1]) + "'s first line is "
                + StrategyExport.words(hand.walked[0][handCell[0]][handCell[1]]) + ", the refit ranks it"), msg);
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
     * export writes no bundle and names the row, by its exception's class where the exception has
     * no message. A fold B7 refuses (its strategy's table not the refit's) is kept the same way. A
     * row asked for that no item replayed is refused too.
     */
    @Test
    public void theExportRefusesAtTheEndRatherThanStopTheReplay() throws Exception {
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        Path export = root.resolve("export-failed");
        FullIndexReplay.Options o = options(root.resolve("failed"), export, quiet);
        o.hooks.clear();
        // At 80% every row's build throws an exception that has no message: kept by its class.
        o.hooks.add(new StrategyExport.ReplayExport(export, states, -1, new ArrayList<>(), LABELS,
                StrategyExport.SAMPLE_SEED, quiet) {
            @Override
            String[] labelsFor(String row, int p) {
                if (p == 2) {
                    throw new UnsupportedOperationException();
                }
                return super.labelsFor(row, p);
            }
        });
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> FullIndexReplay.run(o, "replay", quiet));
        assertTrue(e.getMessage().contains("rows that could not be exported")
                && e.getMessage().contains("at 80%: java.lang.UnsupportedOperationException"), e.getMessage());
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
