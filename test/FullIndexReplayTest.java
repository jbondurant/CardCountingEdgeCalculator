import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.io.Serializable;
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
 * The replay of a finished full-index run (FullIndexReplay, PLAN_COMPUTE.md 6.1) on a tiny run of
 * its own: two counts of our own on a 12-shoe synthetic state file at 75% and 80%, three resamples,
 * one thread, as FullIndexRunTest runs them. The replay, from classes of another commit, gives back
 * every item the run scored to the bit, and every state's perfect value; leaves the run's directory
 * and its tree store as they were; and killed after an item, or in a batch after its last round, and
 * resumed, writes what an uninterrupted replay writes, to the bit. It refuses a run that is not the
 * one its settings make, a fit not finished, an item with nothing to compare with, an output in the
 * source, a tree store it could write, and registered counts on the main file. Parts and perfect
 * values planted beyond 1e-12 stop it, naming the item or the state; within 1e-12, it reports them
 * and goes on. With the plan's three hooks on (ReplayHooks' wide plans and sink, and
 * StrategyExport.ReplayExport for the full-play export, which --export builds since the
 * integration), every item is still the run's to the bit, each hook's file holds what the replay scored,
 * and a killed and resumed replay writes every one of them to the bit as an uninterrupted one does.
 * It is never pointed at runs/full-index/full-c06c061.
 *
 * Planted in a scratch copy of the code on 12 October 2026, one at a time, each failed the test
 * named, which passes without it: the comparison skipping nested arrays of doubles, the replayed
 * item set beside itself instead of the run's part, an item beyond 1e-12 only logged, and a perfect
 * value beyond 1e-12 not refused (each plantedDifferences...); the run's perfect values left in
 * place instead of worked out again (aReplayGivesBack...); a resumed replay scoring every item again,
 * and the hooks left out of the replay's identity (aReplayKilledAndResumed...); c06c061's
 * names-only count lines not taken by name (c06c061sRunId...); and in ResultDiffTest, a count
 * compared field by field, and the timing compared.
 *
 * Planted for the hooks on 12 October 2026, the same way, each failing the test named: the sink's
 * records handed to the collector at the wrong penetration; P8 not checked; the bound rows left out
 * of wide.ser (theHooksOutputs...); and the hooks' rows and plans left out of the replay's identity
 * (aReplayKilledAndResumed...). The export plants of that day (later cells read on the other
 * softness, written as the chart or turned over; a bet from the other fold; first decisions refitted
 * on the stored values; export.txt carrying a time) were against ReplayHooks.Export, retired at the
 * integration for StrategyExport.ReplayExport; this test's checks of the bundle's first moves, later
 * cells and states.bin now hold that hook, and its own plants are in FullPlayExportTest.
 * Planted at the integration on 13 October 2026, in scratch copies of StrategyExport, each failing
 * the test named: ReplayExport's rows and plans left out of the replay's identity
 * (aReplayKilledAndResumed...), and its refusal of an existing bundle at the start left out, so
 * the replay scored every item before the export refused (aReplayRefuses...).
 * Planted for the split lines on 19 October 2026, the same way, each failing
 * theHooksOutputsHoldWhatTheReplayScored: the replay's log line of how each strategy's fit ended
 * left out; and lookups.tsv written without its split lines, which the export's own round trip
 * refuses at the replay's end (0 split lines, all 1,000 due). After the review of 134da21, the
 * splits the last round valued not played again from the files failed it too (0 played).
 */
public class FullIndexReplayTest {

    static final String COUNTS = "{ \"kind\": \"candidates\", \"K\": 10000, \"counts\": [\n"
            + "  { \"name\": \"F1 start\", \"family\": \"F1\", \"role\": \"candidate\", \"form\": \"true\", \"divisor\": 2500,\n"
            + "    \"tags\": [-10000, 10000, 10000, 10000, 10000, 10000, 0, 0, 0, -10000] },\n"
            + "  { \"name\": \"F2 start\", \"family\": \"F2\", \"form\": \"true\", \"divisor\": 2500,\n"
            + "    \"tags\": [0, 10000, 10000, 20000, 20000, 10000, 10000, 0, 0, -20000],\n"
            + "    \"betting\": [-20000, 10000, 10000, 20000, 20000, 10000, 10000, 0, 0, -20000] } ] }";

    /** The fixture: a state file, its counts, the run's directory and its tree store, made once, in a directory JUnit removes. */
    @TempDir
    static Path root;
    static Path states;
    static Path counts;
    static Path source;
    static Path trees;
    static final PrintStream QUIET = quiet();

    private static PrintStream quiet() {
        try {
            return new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The run's settings, as FullIndexRun takes them; the replay is given the same. */
    static String[] settings(String... more) {
        String[] base = {"--states", states.toString(), "--counts", counts.toString(), "--pens", "1,2", "--resamples",
                "3", "--threads", "1"};
        String[] all = Arrays.copyOf(base, base.length + more.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    @BeforeAll
    static void theRun() throws Exception {
        states = ComparisonReportTest.syntheticRun(root, 79, 79, 12);
        counts = CustomCountsTest.write(root, "candidates.json", COUNTS);
        source = root.resolve("run");
        trees = root.resolve("trees.bin");
        FullIndexRun.run(settings("--out", source.toString(), "--trees", trees.toString()), "the-run's-commit", QUIET, 0);
        assertTrue(Files.isRegularFile(source.resolve("fit.ckpt")));
        assertTrue(Files.isRegularFile(source.resolve("part-1-2-halves.ckpt")));
    }

    /** The options of a replay of the fixture's run into out, its trees read only. */
    static FullIndexReplay.Options options(Path from, Path out) {
        FullIndexReplay.Options o = new FullIndexReplay.Options();
        o.fitFrom = from;
        o.states = states;
        o.out = out;
        o.trees = trees;
        o.readOnlyTrees = true;
        o.countsFile = counts;
        o.pens = new int[]{1, 2};
        o.resamples = 3;
        o.threads = 1;
        return o;
    }

    /** Every file under dir with its bytes' SHA-256 and its time, for showing a directory was left as it was. */
    static Map<String, String> snapshot(Path dir) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (Files.isRegularFile(p)) {
                    out.put(dir.relativize(p).toString(), FullIndexReplay.sha256(p) + " "
                            + Files.getLastModifiedTime(p).toMillis());
                }
            }
        }
        return out;
    }

    /** A copy of the fixture's run, to plant something in. */
    static Path copyOfRun(String name) throws Exception {
        Path to = root.resolve(name);
        Files.createDirectories(to);
        try (Stream<Path> files = Files.list(source)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Files.copy(p, to.resolve(p.getFileName()));
            }
        }
        return to;
    }

    /** What a checkpoint file holds after its identity, changed as given and written back under the same identity. */
    static void rewrite(Path file, java.util.function.UnaryOperator<Object> change) throws Exception {
        String id;
        Object o;
        try (InputStream in = Files.newInputStream(file); ObjectInputStream r = new ObjectInputStream(in)) {
            id = r.readUTF();
            o = r.readObject();
        }
        Object now = change.apply(o);
        try (ObjectOutputStream w = new ObjectOutputStream(Files.newOutputStream(file))) {
            w.writeUTF(id);
            w.writeObject(now);
        }
    }

    /**
     * The replay, from classes of another commit, gives back every item of the run to the bit, the
     * fold fits' and the half fits', with every state's perfect value; its parts put together give
     * the run's figures; and the run's directory and tree store are left exactly as they were.
     */
    @Test
    public void aReplayGivesBackEveryItemOfItsRunToTheBit() throws Exception {
        Map<String, String> before = snapshot(source);
        String store = FullIndexReplay.sha256(trees);
        Path out = root.resolve("whole-replay");
        FullIndexReplay.Assembled all = FullIndexReplay.run(options(source, out), "the-replay's-commit", QUIET);
        assertEquals(8, all.items.size(), "two counts at two penetrations, folds and halves");
        for (int k = 0; k < all.items.size(); k++) {
            FullIndexReplay.Record r = all.record(k);
            assertTrue(r.diff.identical(), r.item + ": " + r.diff.describe());
            assertTrue(r.diff.numbers > 300, r.diff.describe());
            assertEquals(0, r.diff.added, "nothing added since the run: " + r.diff.describe());
        }
        assertEquals(before, snapshot(source), "the run's directory is left as it was");
        assertEquals(store, FullIndexReplay.sha256(trees), "the tree store is left as it was");

        // Every state's perfect value, worked out again, is the run's to the bit.
        int valued = 0;
        for (int s = 0; s < all.perfectSource.length; s++) {
            assertFalse(Double.isNaN(all.perfectAgain[s]), "state " + s + " was walked");
            assertEquals(Double.doubleToLongBits(all.perfectSource[s]), Double.doubleToLongBits(all.perfectAgain[s]));
            valued++;
        }
        assertTrue(valued > 50, valued + " states");
        String txt = new String(Files.readAllBytes(out.resolve("replay.txt")), "UTF-8");
        assertTrue(txt.contains("Items replayed: 8; the same to the bit as the source: 8; within 1.0E-12 relative but "
                + "not to the bit: 0."), txt);
        assertTrue(txt.contains("the same to the bit as the source's: " + valued + ";"), txt);
        assertTrue(txt.contains("\ncommit the-replay's-commit\n") && txt.contains("\ncommit the-run's-commit\n"), txt);

        // The parts put together are the run's Results, figure for figure.
        CeilingReport.Published run = CeilingReport.read(source.resolve("results.ser"));
        List<SystemScorer.Result> merged = all.results();
        assertEquals(run.results.size(), merged.size());
        for (int k = 0; k < merged.size(); k++) {
            ResultDiff d = ResultDiff.of(run.results.get(k), merged.get(k));
            assertTrue(d.identical(), d.describe());
        }
    }

    /** A planted interruption, as a kill or a crash leaves a replay. */
    private static final class Interrupted extends RuntimeException {
        Interrupted() {
            super("interrupted on purpose");
        }
    }

    /**
     * A hook as the export's will be: it keeps, for each item, each fold strategy's values after
     * its last round and the item's flat figure, and writes them all to hook.bin at the end, from
     * the records. It counts the items it was called on, and throws where it is told to.
     */
    static final class ValuesHook implements FullIndexReplay.Hook {
        int called;
        int throwAt = -1;

        @Override
        public String name() {
            return "values";
        }

        @Override
        public void before(FullIndexReplay.Item item, SystemScorer scorer, List<FullIndexFit.Strategy> two) {
            if (called++ == throwAt) {
                throw new Interrupted();
            }
        }

        @Override
        public Serializable after(FullIndexReplay.Item item, SystemScorer scorer, SystemScorer.Result scored,
                                  List<FullIndexFit.Strategy> two) {
            ArrayList<double[]> kept = new ArrayList<>();
            for (FullIndexFit.Strategy t : two) {
                kept.add(t.values.hit.clone());
                kept.add(t.values.split.clone());
            }
            SystemScorer.Entry e = scored.entries.get(0);
            kept.add(e.estimate[item.pen] == null ? new double[0] : e.estimate[item.pen].clone());
            return kept;
        }

        @Override
        public void finish(FullIndexReplay.Assembled all) throws Exception {
            ArrayList<Object> out = new ArrayList<>();
            for (int k = 0; k < all.items.size(); k++) {
                out.add(all.record(k).kept.get(name()));
            }
            FullIndexReplay.writeWhole(all.out.resolve("hook.bin"), o -> {
                ObjectOutputStream w = new ObjectOutputStream(o);
                w.writeObject(out);
                w.flush();
            });
        }
    }

    /** The full-play rows of the fixture's run that a bundle can hold: every full-play entry not bet on its own value. */
    static List<String> fullPlayRows() throws Exception {
        List<String> out = new ArrayList<>();
        for (SystemScorer.Result r : CeilingReport.read(source.resolve("results.ser")).results) {
            for (SystemScorer.Entry e : r.fullEntries) {
                if (!e.ownValue) {
                    out.add(e.name);
                }
            }
        }
        return out;
    }

    /**
     * A folds-only replay into out with the plan's three hooks on, as 6.1 runs it: the wide plans,
     * the sink into out/sink and the export of every full-play row into out/bundle, at four of the
     * report's plans; and the test's own hook.
     */
    static FullIndexReplay.Options withHooks(Path out) throws Exception {
        FullIndexReplay.Options o = options(source, out);
        o.foldsOnly = true;
        o.wide = true;
        o.sink = out.resolve("sink");
        o.export = out.resolve("bundle");
        o.exportRows = fullPlayRows();
        o.exportPlans = new String[]{"same-1", "same-8", "own-8", "played-8"};
        o.hooks.add(new ValuesHook());
        return o;
    }

    /** The files a replay with every hook writes, by their place in its directory: its own, the hooks', and the bundle's. */
    static List<String> written(Path out) throws Exception {
        List<String> files = new ArrayList<>(Arrays.asList("replay.txt", "perfect.ser", "hook.bin", "wide.ser",
                "sink/" + ReplayHooks.Sink.FILE));
        try (Stream<Path> bundle = Files.list(out.resolve("bundle"))) {
            bundle.map(p -> "bundle/" + p.getFileName()).sorted().forEach(files::add);
        }
        return files;
    }

    /** Two replays wrote the same files to the bit, and kept every item's Result the same to the bit. */
    static void sameReplay(FullIndexReplay.Assembled a, FullIndexReplay.Assembled b) throws Exception {
        List<String> files = written(a.out);
        assertEquals(files, written(b.out), "the same files");
        assertTrue(files.size() > 10, files.toString());
        for (String f : files) {
            assertArrayEquals(Files.readAllBytes(a.out.resolve(f)), Files.readAllBytes(b.out.resolve(f)), f);
        }
        assertEquals(a.items.size(), b.items.size());
        for (int k = 0; k < a.items.size(); k++) {
            ResultDiff d = ResultDiff.of(a.record(k).result, b.record(k).result);
            assertTrue(d.identical(), d.describe());
        }
    }

    /**
     * A replay killed after an item, one item a batch, or within a batch after its last round and
     * before its items are kept, and then resumed, scores only the items with no record and writes
     * what an uninterrupted replay writes, to the bit: its account, its perfect values and what its
     * hook kept. Resumed with other settings, or started afresh over its records, it is refused.
     */
    @Test
    public void aReplayKilledAndResumedWritesWhatAnUninterruptedOneWrites() throws Exception {
        FullIndexReplay.Assembled w = FullIndexReplay.run(withHooks(root.resolve("uninterrupted")), "replay", QUIET);
        assertEquals(4, w.items.size(), "the fold fits' items");
        assertTrue(Files.size(w.out.resolve("hook.bin")) > 1000);
        assertEquals(written(w.out), written(w.out), "the bundle is listed in a fixed order");

        // Killed after its first item, one item a batch.
        Path dir = root.resolve("killed-after-an-item");
        FullIndexReplay.Options a = withHooks(dir);
        a.batchBytes = 1;
        a.afterItem = n -> {
            if (n == 1) {
                throw new Interrupted();
            }
        };
        assertThrows(Interrupted.class, () -> FullIndexReplay.run(a, "replay", QUIET));
        assertFalse(Files.exists(dir.resolve("wide.ser")) || Files.exists(dir.resolve("bundle"))
                || Files.exists(dir.resolve("bundle.partial")), "nothing put together before every item is kept");
        FullIndexReplay.Options again = withHooks(dir);
        again.batchBytes = 1;
        again.resume = true;
        ValuesHook counted = new ValuesHook();
        again.hooks.set(0, counted);
        sameReplay(w, FullIndexReplay.run(again, "replay", QUIET));
        assertEquals(3, counted.called, "only the items with no record are scored again");

        // Killed in its one batch, after the last round, as its third item is about to be scored.
        Path batch = root.resolve("killed-in-a-batch");
        FullIndexReplay.Options b = withHooks(batch);
        b.batchBytes = 1e15;
        ValuesHook thrower = new ValuesHook();
        thrower.throwAt = 2;
        b.hooks.set(0, thrower);
        assertThrows(Interrupted.class, () -> FullIndexReplay.run(b, "replay", QUIET));
        FullIndexReplay.Options more = withHooks(batch);
        more.batchBytes = 1e15;
        more.resume = true;
        sameReplay(w, FullIndexReplay.run(more, "replay", QUIET));

        // Other hooks, or other rows exported, make another replay: refused, as is a fresh start over its records.
        Path third = root.resolve("killed-again");
        FullIndexReplay.Options c = withHooks(third);
        c.afterItem = n -> {
            throw new Interrupted();
        };
        assertThrows(Interrupted.class, () -> FullIndexReplay.run(c, "replay", QUIET));
        FullIndexReplay.Options other = options(source, third);
        other.foldsOnly = true;
        other.resume = true;
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> FullIndexReplay.run(other, "replay", QUIET));
        assertTrue(e.getMessage().contains("hooks: wide sink export (rows [F1 start, full play@75, F1 start, full play@80"),
                e.getMessage());
        FullIndexReplay.Options fewer = withHooks(third);
        fewer.resume = true;
        fewer.exportRows = fewer.exportRows.subList(0, 1);
        assertThrows(IllegalStateException.class, () -> FullIndexReplay.run(fewer, "replay", QUIET));
        FullIndexReplay.Options fresh = withHooks(third);
        assertThrows(IllegalStateException.class, () -> FullIndexReplay.run(fresh, "replay", QUIET));
    }

    /**
     * What each fold strategy plays at every later cell, as the replay holds it: for every later
     * group, the play in force at the group's highest key, through Strategy.laterPlay, which reads
     * cells by the engine's own map; set beside the export's later lines, which go by Part B's order.
     */
    static final class LaterHook implements FullIndexReplay.Hook {
        @Override
        public String name() {
            return "later";
        }

        @Override
        public Serializable after(FullIndexReplay.Item item, SystemScorer scorer, SystemScorer.Result scored,
                                  List<FullIndexFit.Strategy> two) {
            int[][] cells = ExportBundle.cells();
            ArrayList<boolean[][]> out = new ArrayList<>();
            for (FullIndexFit.Strategy t : two) {
                int groups = t.later == null ? 1 : t.later.count();
                boolean[][] st = new boolean[groups][cells.length];
                PlayerTree.LaterPlay play = t.laterPlay();
                for (int l = 0; l < groups; l++) {
                    int key = t.later == null ? 0 : t.later.hi[l];
                    for (int c = 0; c < cells.length; c++) {
                        st[l][c] = play.stands(cells[c][1], cells[c][2] == 1, cells[c][0], key);
                    }
                }
                out.add(st);
            }
            return out;
        }
    }

    /**
     * Each fold strategy's walked ranking as the replay holds it after the last round (now.ranking),
     * the ranking its splits were valued under, which a full-play file's split hands must read.
     */
    static final class RankingHook implements FullIndexReplay.Hook {
        @Override
        public String name() {
            return "ranking";
        }

        @Override
        public Serializable after(FullIndexReplay.Item item, SystemScorer scorer, SystemScorer.Result scored,
                                  List<FullIndexFit.Strategy> two) {
            ArrayList<PlayerMove[][][]> out = new ArrayList<>();
            for (FullIndexFit.Strategy t : two) {
                out.add(t.now.ranking);
            }
            return out;
        }
    }

    /**
     * The three hooks' outputs hold what the replay scored. Every item, scored with the wide plans
     * on, is still the run's part to the bit, the wide arrays only added; wide.ser holds every
     * replayed row with the oracle and bound rows under the replay's identity and passes
     * WideReport's checks. The sink's run-b.ser hands back each entry's states at each penetration,
     * whose weighted mean is the entry's flat figure. The bundle's strategy files read back as
     * Part B says, play their first moves as the replayed fit and their later cells as the play in
     * force, and its states.bin gives the replayed figures. Each file's first lines are the replayed
     * fit's ranking whole, and the ranking its split hands read (B2.4) is the strategy's walked one,
     * with a split line wherever the two differ, none on this fixture; every split the last round
     * valued, played again from the files, is played as valued; lookups.tsv holds the 1,000
     * split lines. The log says, for every strategy replayed, how its fit ended and its
     * refitDiffers, and for every fold exported its split lines. Planted: a P8 one unit in its last
     * place off, an entry without its wide figures, a round value or a bet moved in the bundle's
     * states.bin: each stops.
     */
    @Test
    public void theHooksOutputsHoldWhatTheReplayScored() throws Exception {
        FullIndexReplay.Options o = withHooks(root.resolve("hooks"));
        o.hooks.add(new LaterHook());
        o.hooks.add(new RankingHook());
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        FullIndexReplay.Assembled h = FullIndexReplay.run(o, "hooks", new PrintStream(text, true, "UTF-8"));
        String log = text.toString("UTF-8");
        List<SystemScorer.Result> merged = h.results();
        int rows = 0;
        for (int k = 0; k < h.items.size(); k++) {
            FullIndexReplay.Record r = h.record(k);
            assertTrue(r.diff.identical() && r.diff.added > 0, r.item + ": " + r.diff.describe());
        }
        for (SystemScorer.Result r : merged) {
            rows += r.entries.size() + r.fullEntries.size();
        }

        WideReport.Saved wide = WideReport.read(h.out.resolve("wide.ser"));
        assertEquals(h.identity, wide.identity);
        assertEquals(SystemScorer.WIDE_PLANS.length, wide.plans.length);
        assertEquals(rows + 1 + 3, wide.rows.size(), "every row, the oracle and the three bound rows");
        assertTrue(WideReport.check(wide).isEmpty(), String.valueOf(WideReport.check(wide)));

        KeyEdges.Collector c = KeyEdges.Collector.read(h.out.resolve("sink").resolve(ReplayHooks.Sink.FILE));
        assertEquals(h.identity, c.identity);
        for (SystemScorer.Result r : merged) {
            List<SystemScorer.Entry> es = new ArrayList<>(r.entries);
            es.addAll(r.fullEntries);
            for (SystemScorer.Entry e : es) {
                for (int p = 1; p <= 2; p++) {
                    KeyEdges.Values v = c.values(e.name, p);
                    double wv = 0;
                    double ww = 0;
                    double scale = 0;
                    for (int j = 0; j < v.state.length; j++) {
                        wv += v.weight[j] * v.value[j];
                        ww += v.weight[j];
                        scale += v.weight[j] * Math.abs(v.value[j]);
                    }
                    // Summed in another order than the scorer's: held to the size of its terms (StrategyExport.Report).
                    double flat = e.estimate[p][SystemScorer.FLAT];
                    assertEquals(flat, wv / ww, 1e-12 * scale / ww, e.name + " at " + p);
                }
            }
        }
        Path bundle = h.out.resolve("bundle");
        assertTrue(Files.isDirectory(bundle) && !Files.exists(h.out.resolve("bundle.partial")),
                "the bundle passed its round trip and was renamed");
        for (String f : new String[]{"chart.tsv", "states.bin", "keys-sample.tsv", "lookups.tsv", "exact.tsv",
                "index.tsv", "manifest.sha256"}) {
            assertTrue(Files.isRegularFile(bundle.resolve(f)), f);
        }
        ExportBundle.States bin = ExportBundle.readStates(bundle.resolve("states.bin"));
        assertEquals(2 * o.exportRows.size(), bin.sections.size(), "every row at 75% and 80%");
        int checkedFiles = 0;
        ScoringStates states12 = FullIndexPilot.load(states, 12);
        for (int k = 0; k < h.items.size(); k++) {
            FullIndexReplay.Record rec = h.record(k);
            int p = rec.item.pen;
            @SuppressWarnings("unchecked")
            List<boolean[][]> later = (List<boolean[][]>) rec.kept.get("later");
            @SuppressWarnings("unchecked")
            List<PlayerMove[][][]> walked = (List<PlayerMove[][][]>) rec.kept.get("ranking");
            for (SystemScorer.Entry e : rec.result.fullEntries) {
                if (e.ownValue) {
                    continue;
                }
                for (int f = 0; f < 2; f++) {
                    ExportBundle.Strategy s = ExportBundle.readStrategy(bundle.resolve(
                            ExportBundle.Strategy.fileName(e.name, ExportBundle.PENS[p], f)));
                    SystemScorer.Fit fit = rec.result.fullFirst[p][f];
                    assertArrayEquals(fit.groups.hi, s.hi);
                    PlayerMove[][][] ranked = FullIndexFit.rankings(states12, StateRun.montreal(), fit);
                    int differ = 0;
                    for (int g = 0; g < s.hi.length; g++) {
                        for (int i = 0; i < Deals.COUNT; i++) {
                            if (Deals.playerNatural(i)) {
                                continue;
                            }
                            if (fit.reach[g][i] > 0) {
                                assertEquals(StateValuer.MOVES[fit.move[g][i]], s.first[g][i][0], g + " " + i);
                            }
                            assertArrayEquals(ranked[g][i], s.first[g][i], "the dealt hand's ranking, " + g + " " + i);
                            assertArrayEquals(walked.get(f)[g][i], s.splitRanking(g, i), "split hands', " + g + " " + i);
                            differ += Arrays.equals(ranked[g][i], walked.get(f)[g][i]) ? 0 : 1;
                        }
                    }
                    assertEquals(differ, s.splitLines());
                    assertEquals(0, differ, "this fixture's fits rank every deal one way");
                    assertEquals(later.get(f).length, s.stands.length);
                    for (int l = 0; l < s.stands.length; l++) {
                        assertArrayEquals(later.get(f)[l], s.stands[l], "later group " + l);
                    }
                    assertTrue(log.contains("export: " + rec.result.count.name + " at " + SystemScorer.PENETRATIONS[p]
                            + ", fold " + f + ": 0 split lines\n"), log);
                    checkedFiles++;
                }
            }
            for (FullIndexFit.Summary t : rec.result.fullFit[p]) {
                String line = "  " + t.name + ": " + t.passes + " passes, " + (t.capped ? "stopped at the pass cap" : "converged");
                int at = log.indexOf(line);
                assertTrue(at >= 0 && log.substring(at, log.indexOf('\n', at)).endsWith("; refitDiffers " + t.refitDiffers),
                        line + "\n" + log);
            }
        }
        assertEquals(4 * o.exportRows.size(), checkedFiles);
        // Each row's check on its values played every split its strategies' last round valued again
        // from its files (StrategyExport.playedAsValued), so the split hands are held to what the
        // Split values were worked out under, not only to the walked ranking Refit took.
        java.util.regex.Matcher played = java.util.regex.Pattern.compile("checked on its values, \\d+ values compared, "
                + "(\\d+) splits the last round valued played again from its files").matcher(log);
        int rowsPlayed = 0;
        while (played.find()) {
            assertTrue(Integer.parseInt(played.group(1)) > 0, played.group());
            rowsPlayed++;
        }
        assertEquals(2 * o.exportRows.size(), rowsPlayed, log);
        ExportBundle.Lookups lk = ExportBundle.readLookups(bundle.resolve("lookups.tsv"), true);
        int splitLines = 0;
        for (ExportBundle.LookupLine l : lk.lines) {
            if (l.split) {
                splitLines++;
                for (String cell : l.cells) {
                    assertTrue(cell.matches("[SHDP]+(:[SHDP]+)?"), cell);
                }
            }
        }
        assertEquals(1000, splitLines);

        // Planted, one at a time.
        FullIndexReplay.Record rec = h.record(h.items.size() - 1);
        SystemScorer.Entry first = rec.result.entries.get(0);
        int p = rec.item.pen;
        int p8 = SystemScorer.wideAt(SystemScorer.planIndex(SystemScorer.WIDE_PLANS, "P8"), SystemScorer.W_SAME);
        double was = first.wide[p][p8];
        first.wide[p][p8] = Math.nextUp(was);
        IllegalStateException x = assertThrows(IllegalStateException.class,
                () -> new ReplayHooks.WidePlans().after(rec.item, null, rec.result, null));
        assertTrue(x.getMessage().contains("check 1 of PLAN_COMPUTE.md 3.5"), x.getMessage());
        first.wide[p][p8] = was;
        new ReplayHooks.WidePlans().after(rec.item, null, rec.result, null);
        first.widePlans = null;
        x = assertThrows(IllegalStateException.class, () -> new ReplayHooks.WidePlans().after(rec.item, null, rec.result,
                null));
        assertTrue(x.getMessage().contains("holds no wide figures"), x.getMessage());

        // The bundle's round trip, as the export's finish ran it: each full section held to the one
        // checked on the strategies' values as its item was replayed. Then a round value, and a bet,
        // moved in a copy's states.bin and resealed as a writer with that bug would seal it: refused.
        StrategyExport.FullSource verified = new StrategyExport.FullSource();
        List<StrategyExport.Expect> expected = new ArrayList<>();
        for (int k = 0; k < h.items.size(); k++) {
            @SuppressWarnings("unchecked")
            List<StrategyExport.RowExport> kept = (List<StrategyExport.RowExport>) h.record(k).kept.get("export");
            for (StrategyExport.RowExport r : kept == null ? List.<StrategyExport.RowExport>of() : kept) {
                verified.verified.put(StrategyExport.FullSource.key(r.expect.row, r.expect.pen), r.section);
                expected.add(r.expect);
            }
        }
        // keys-sample.tsv's counts: the whole catalog, then the run's counts outside it.
        List<CountKeys.Count> catalog = CountKeys.catalog();
        List<CountKeys.Count> own = new ArrayList<>();
        List<String> catalogNames = new ArrayList<>();
        for (CountKeys.Count k : catalog) {
            catalogNames.add(k.name);
        }
        for (CountKeys.Count k : h.counts) {
            if (!k.name.equals(CountKeys.nullCount().name) && !catalogNames.contains(k.name)) {
                own.add(k);
            }
        }
        List<CountKeys.Count> keyCounts = StrategyExport.keyCounts(catalog, own);
        ScoringStates st = FullIndexPilot.load(states, 12);
        StateStore.Header header = StateStore.header(states);
        assertNotNull(StrategyExport.roundTrip(bundle, st, header, expected, keyCounts, verified));
        for (String plant : new String[]{"a round value moved", "a bet moved"}) {
            Path dir = root.resolve("planted " + plant);
            Files.createDirectories(dir);
            try (Stream<Path> list = Files.list(bundle)) {
                for (Path f : (Iterable<Path>) list::iterator) {
                    Files.copy(f, dir.resolve(f.getFileName()));
                }
            }
            ExportBundle.States moved = ExportBundle.readStates(dir.resolve("states.bin"));
            ExportBundle.Section sec = moved.sections.get(0);
            if (plant.startsWith("a round value")) {
                sec.rvPlay[3] += 1e-6;
            } else {
                sec.bet[3][1] += 0.001;
            }
            Files.delete(dir.resolve("states.bin"));
            StrategyExport.writeStates(dir.resolve("states.bin"), header, st, moved.sections);
            StrategyExportTest.reseal(dir);
            assertThrows(RuntimeException.class, () -> StrategyExport.roundTrip(dir, st, header, expected, keyCounts,
                    verified), plant);
        }
    }

    /**
     * What a replay cannot make true is refused before anything is written: settings other than
     * the run's (the line named), an output in the run's directory, a tree store it could write,
     * a hook not in these classes, an item with no part to set it beside, a fit not finished, and
     * counts of our own on the main state file, whose replay is the page look's.
     */
    @Test
    public void aReplayRefusesWhatItCannotReplayBeforeWritingAnything() throws Exception {
        Path out = root.resolve("refused");
        FullIndexReplay.Options more = options(source, out);
        more.resamples = 4;
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> FullIndexReplay.run(more, "r", QUIET));
        assertTrue(e.getMessage().contains("now: resamples 4"), e.getMessage());
        FullIndexReplay.Options threads = options(source, out);
        threads.threads = 2;
        e = assertThrows(IllegalStateException.class, () -> FullIndexReplay.run(threads, "r", QUIET));
        assertTrue(e.getMessage().contains("now: threads 2"), e.getMessage());

        FullIndexReplay.Options inside = options(source, source.resolve("replay"));
        IllegalArgumentException a = assertThrows(IllegalArgumentException.class,
                () -> FullIndexReplay.run(inside, "r", QUIET));
        assertTrue(a.getMessage().contains("where the replay writes nothing"), a.getMessage());
        FullIndexReplay.Options writable = options(source, out);
        writable.readOnlyTrees = false;
        a = assertThrows(IllegalArgumentException.class, () -> FullIndexReplay.run(writable, "r", QUIET));
        assertTrue(a.getMessage().contains("--trees-read-only"), a.getMessage());
        // The hooks' own outputs: none in the run's directory, an export bundle written once, rows only with a bundle.
        for (String hook : new String[]{"sink", "export"}) {
            FullIndexReplay.Options h = options(source, out);
            h.sink = hook.equals("sink") ? source.resolve("sink") : null;
            h.export = hook.equals("export") ? source.resolve("bundle") : null;
            a = assertThrows(IllegalArgumentException.class, () -> FullIndexReplay.run(h, "r", QUIET));
            assertTrue(a.getMessage().contains("where the replay writes nothing"), a.getMessage());
        }
        Path made = root.resolve("a-bundle-already");
        Files.createDirectories(made);
        FullIndexReplay.Options twice = options(source, out);
        twice.export = made;
        e = assertThrows(IllegalStateException.class, () -> FullIndexReplay.run(twice, "r", QUIET));
        assertTrue(e.getMessage().contains("a bundle is written once"), e.getMessage());
        if (Files.exists(out)) {
            try (Stream<Path> left = Files.walk(out)) {
                assertEquals(1, left.count(), "refused before any item was replayed, not when the export finished");
            }
        }
        FullIndexReplay.Options rowsOnly = options(source, out);
        rowsOnly.exportRows = Arrays.asList("F1 start, full play");
        a = assertThrows(IllegalArgumentException.class, () -> FullIndexReplay.run(rowsOnly, "r", QUIET));
        assertTrue(a.getMessage().contains("no --export directory"), a.getMessage());
        // Some items only, with the rows SIM_CHECK.md registers: refused now, not when the export finds them missing.
        FullIndexReplay.Options onlySome = options(source, out);
        onlySome.export = root.resolve("only-some-bundle");
        onlySome.only = Arrays.asList("F1 start@80");
        a = assertThrows(IllegalArgumentException.class, () -> FullIndexReplay.run(onlySome, "r", QUIET));
        assertTrue(a.getMessage().contains("--only with --export"), a.getMessage());
        assertFalse(Files.exists(root.resolve("only-some-bundle")));

        Path missing = copyOfRun("missing-part");
        Files.delete(missing.resolve("part-1-1-folds.ckpt"));
        e = assertThrows(IllegalStateException.class, () -> FullIndexReplay.run(options(missing, out), "r", QUIET));
        assertTrue(e.getMessage().contains("no part-1-1-folds.ckpt"), e.getMessage());
        Path unfinished = copyOfRun("unfinished");
        rewrite(unfinished.resolve("fit.ckpt"), o -> {
            ((Object[]) o)[0] = false;
            return o;
        });
        e = assertThrows(IllegalStateException.class, () -> FullIndexReplay.run(options(unfinished, out), "r", QUIET));
        assertTrue(e.getMessage().contains("not marked fitted"), e.getMessage());

        Path main = CustomCountsTest.stateFile(root, CustomCounts.MAIN_SEED);
        FullIndexReplay.Options onMain = options(source, out);
        onMain.states = main;
        e = assertThrows(IllegalStateException.class, () -> FullIndexReplay.run(onMain, "r", QUIET));
        assertTrue(e.getMessage().contains("page look"), e.getMessage());
        assertFalse(Files.exists(out), "nothing written");

        // The command line: the replay's options without --fit-from, and a look's option with it, are refused.
        a = assertThrows(IllegalArgumentException.class, () -> FullIndexRun.run(settings("--out", out.toString(),
                "--folds-only"), "r", QUIET, 0));
        assertTrue(a.getMessage().contains("--fit-from"), a.getMessage());
        a = assertThrows(IllegalArgumentException.class, () -> FullIndexRun.run(settings("--out", out.toString(),
                "--fit-from", source.toString(), "--second-look"), "r", QUIET, 0));
        assertTrue(a.getMessage().contains("--second-look"), a.getMessage());
        assertFalse(Files.exists(out), "nothing written");
    }

    /**
     * c06c061's own run.id, as the item checkpoint kept beside the tests begins with it, against
     * what this build writes for the same settings: its count lines name the counts only, as the
     * classes before the betting vectors wrote them, and are taken by name, so only its state
     * file's line differs from this test's. A name, a count or a setting that differs is named.
     */
    @Test
    public void c06c061sRunIdIsTakenApartFromItsCommitAndItsStateFile() throws Exception {
        String id;
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(StoredResultsTest.PART))) {
            id = in.readUTF();
        }
        List<CountKeys.Count> catalog = new ArrayList<>();
        catalog.add(CountKeys.nullCount());
        catalog.addAll(CountKeys.catalog());
        String now = FullIndexRun.identity("this build", states, 60000, 60743, 1000, 6, catalog, new int[]{0, 1, 2}, null);
        assertTrue(now.contains("\n  with "), "this build names the betting variants");
        String d = FullIndexReplay.differences(id, now, catalog);
        assertEquals(2, d.split("\n").length, d);
        assertTrue(d.startsWith("  was: states /Users/") && d.contains("montreal-80.states") && d.contains("\n  now: states "),
                d);
        String other = id.replace("count Hi-Opt II\n", "count Hi-Opt III\n");
        assertTrue(FullIndexReplay.differences(other, now, catalog).contains("was: count Hi-Opt III"));
        String fewer = id.replace("count Hi-Opt II\n", "");
        assertTrue(FullIndexReplay.differences(fewer, now, catalog).contains("30 counts"));
        String threads = id.replace("threads 6\n", "threads 7\n");
        assertTrue(FullIndexReplay.differences(threads, now, catalog).contains("was: threads 7"));
    }

    /**
     * Planted in a copy of the run. One figure of one part moved by a part in a thousand stops the
     * replay at that item, naming it and the figure, and nothing of it is kept; one resample moved by
     * one unit in its last place is reported, with where it is, and the replay goes on. A perfect
     * value moved stops it, naming the state; one the run left out is worked out, kept and counted.
     */
    @Test
    public void plantedDifferencesStopTheReplayBeyond1e12AndAreReportedWithin() throws Exception {
        Path far = copyOfRun("planted-far");
        rewrite(far.resolve("part-1-2-folds.ckpt"), o -> {
            ((SystemScorer.Result) o).fullEntries.get(0).estimate[2][SystemScorer.FLAT] *= 1.001;
            return o;
        });
        FullIndexReplay.Options f = options(far, root.resolve("far"));
        f.foldsOnly = true;
        f.batchBytes = 1;
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> FullIndexReplay.run(f, "r", QUIET));
        assertTrue(e.getMessage().startsWith("part-1-2-folds (F2 start at 80%"), e.getMessage());
        assertTrue(e.getMessage().contains("result.fullEntries[0].estimate[2][" + SystemScorer.FLAT + "]: "), e.getMessage());
        assertTrue(Files.exists(root.resolve("far").resolve("replayed-part-1-1-folds.ckpt")), "the items before are kept");
        assertFalse(Files.exists(root.resolve("far").resolve("replayed-part-1-2-folds.ckpt")), "nothing of it is kept");

        Path near = copyOfRun("planted-near");
        int m = SystemScorer.same(SystemScorer.SPREADS);
        rewrite(near.resolve("part-0-1-folds.ckpt"), o -> {
            double[] x = ((SystemScorer.Result) o).entries.get(1).boot[1][m];
            x[2] = Math.nextUp(x[2]);
            return o;
        });
        FullIndexReplay.Options n = options(near, root.resolve("near"));
        n.foldsOnly = true;
        FullIndexReplay.Assembled a = FullIndexReplay.run(n, "r", QUIET);
        FullIndexReplay.Record r = a.record(0);
        assertEquals("part-0-1-folds", r.item.key());
        assertTrue(r.diff.passes() && !r.diff.identical());
        assertEquals(1, r.diff.within);
        assertEquals("result.entries[1].boot[1][" + m + "][2]", r.diff.largestAt);
        assertTrue(r.diff.largest > 0 && r.diff.largest < 1e-15, String.valueOf(r.diff.largest));
        String txt = new String(Files.readAllBytes(a.out.resolve("replay.txt")), "UTF-8");
        assertTrue(txt.contains("within 1.0E-12 relative but not to the bit: 1 (the largest difference "), txt);
        assertTrue(txt.contains("at part-0-1-folds, result.entries[1].boot[1][" + m + "][2])"), txt);

        Path moved = copyOfRun("planted-perfect");
        rewrite(moved.resolve("fit.ckpt"), o -> {
            ((double[]) ((Object[]) o)[3])[5] += 0.01;
            return o;
        });
        FullIndexReplay.Options p = options(moved, root.resolve("perfect-moved"));
        p.foldsOnly = true;
        e = assertThrows(IllegalStateException.class, () -> FullIndexReplay.run(p, "r", QUIET));
        assertTrue(e.getMessage().startsWith("state 5's perfect value is "), e.getMessage());

        Path lacking = copyOfRun("perfect-left-out");
        rewrite(lacking.resolve("fit.ckpt"), o -> {
            ((double[]) ((Object[]) o)[3])[7] = Double.NaN;
            return o;
        });
        FullIndexReplay.Options l = options(lacking, root.resolve("perfect-left-out-replay"));
        l.foldsOnly = true;
        a = FullIndexReplay.run(l, "r", QUIET);
        assertTrue(Double.isNaN(a.perfectSource[7]));
        assertFalse(Double.isNaN(a.perfectAgain[7]));
        txt = new String(Files.readAllBytes(a.out.resolve("replay.txt")), "UTF-8");
        assertTrue(txt.contains("the source had none for 1 of them"), txt);
    }

    /**
     * From the command line, as gate 0 of PLAN_COMPUTE.md 6.1 runs it: one count's fold fits at one
     * penetration, its trees read only, give back the run's item to the bit. A name that picks no
     * item is refused.
     */
    @Test
    public void theCommandLineReplaysTheItemsItIsGiven() throws Exception {
        Path out = root.resolve("gate");
        FullIndexRun.run(settings("--out", out.toString(), "--fit-from", source.toString(), "--trees-read-only",
                trees.toString(), "--folds-only", "--only", "F2 start@2"), "gate", QUIET, 0);
        String txt = new String(Files.readAllBytes(out.resolve("replay.txt")), "UTF-8");
        assertTrue(txt.contains("items: the fold fits only, only F2 start@2\n"), txt);
        assertTrue(txt.contains("\npart-1-2-folds (F2 start at 80%, its fold fits): "), txt);
        assertTrue(txt.contains("Items replayed: 1; the same to the bit as the source: 1;"), txt);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> FullIndexRun.run(settings("--out",
                root.resolve("no-item").toString(), "--fit-from", source.toString(), "--trees-read-only",
                trees.toString(), "--only", "F2 start@0"), "gate", QUIET, 0));
        assertTrue(e.getMessage().contains("--only F2 start@0 names no item"), e.getMessage());
    }

    /**
     * From the command line, a finished replay lets its records go (FullIndexReplay.letGo), naming
     * each with its size in records-let-go.txt, and writes replay.txt and perfect.ser to the bit as a
     * replay that keeps them (--keep-records); a resume into its directory is then refused before
     * anything is written; and a replay without room in its runs folder's budget is refused before
     * anything is written (DiskBudget, 18 October 2026).
     */
    @Test
    public void theCommandLineLetsAFinishedReplaysRecordsGo() throws Exception {
        String[] only = {"--fit-from", source.toString(), "--trees-read-only", trees.toString(), "--folds-only", "--only",
                "F2 start@2,F1 start@1"};
        Path kept = root.resolve("records-kept");
        Path let = root.resolve("records-let-go");
        FullIndexRun.run(settings(concat(only, "--out", kept.toString(), "--keep-records")), "gate", QUIET, 0);
        FullIndexRun.run(settings(concat(only, "--out", let.toString())), "gate", QUIET, 0);
        List<String> records = new ArrayList<>();
        try (Stream<Path> files = Files.list(kept)) {
            files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("replayed-")).sorted().forEach(records::add);
        }
        assertEquals(2, records.size(), records.toString());
        try (Stream<Path> files = Files.list(let)) {
            assertEquals(0, files.filter(p -> p.getFileName().toString().startsWith("replayed-")).count());
        }
        String list = new String(Files.readAllBytes(let.resolve(FullIndexReplay.LET_GO)), "UTF-8");
        for (String r : records) {
            assertTrue(list.contains(r + "\t" + Files.size(kept.resolve(r)) + "\n"), list);
        }
        assertTrue(list.contains("Records: 2, "), list);
        for (String f : new String[]{"replay.txt", "perfect.ser"}) {
            assertArrayEquals(Files.readAllBytes(kept.resolve(f)), Files.readAllBytes(let.resolve(f)), f);
        }
        Map<String, String> before = snapshot(let);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> FullIndexRun.run(settings(concat(only,
                "--out", let.toString(), "--resume")), "gate", QUIET, 0));
        assertTrue(e.getMessage().contains("its records were let go"), e.getMessage());
        assertEquals(before, snapshot(let), "nothing written");

        Path runs = root.resolve("runs");
        Files.createDirectories(runs);
        Files.write(runs.resolve("held.bin"), new byte[1000]);
        Path refused = runs.resolve("replay");
        IllegalStateException b = assertThrows(IllegalStateException.class, () -> FullIndexRun.run(settings(concat(only,
                "--out", refused.toString(), "--disk-budget", "0.000002")), "gate", QUIET, 0));
        assertTrue(b.getMessage().contains("The replay of 2 items would write about") && b.getMessage().contains("held.bin"),
                b.getMessage());
        assertFalse(Files.exists(refused), "nothing written");
    }

    private static String[] concat(String[] a, String... b) {
        String[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }
}
