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
        ExportBundle.Lookups lk = ExportBundle.readLookups(bundle.resolve("lookups.tsv"));
        int later = 0;
        int stand = 0;
        int hit = 0;
        for (ExportBundle.LookupLine l : lk.lines) {
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
        Path dir = root.resolve("planted-" + name);
        Files.createDirectories(dir);
        for (Map.Entry<String, byte[]> f : files(direct).entrySet()) {
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
        RuntimeException e = assertThrows(RuntimeException.class, () -> StrategyExport.roundTrip(dir, scorer.st,
                StateStore.header(states), expected, keyCounts, full));
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
