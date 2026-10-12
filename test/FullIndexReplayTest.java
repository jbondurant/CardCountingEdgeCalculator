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
 * and goes on. It is never pointed at runs/full-index/full-c06c061.
 *
 * Planted in a scratch copy of the code on 12 October 2026, one at a time, each failed the test
 * named, which passes without it: the comparison skipping nested arrays of doubles, the replayed
 * item set beside itself instead of the run's part, an item beyond 1e-12 only logged, and a perfect
 * value beyond 1e-12 not refused (each plantedDifferences...); the run's perfect values left in
 * place instead of worked out again (aReplayGivesBack...); a resumed replay scoring every item again,
 * and the hooks left out of the replay's identity (aReplayKilledAndResumed...); c06c061's
 * names-only count lines not taken by name (c06c061sRunId...); and in ResultDiffTest, a count
 * compared field by field, and the timing compared.
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

    /** Two replays wrote the same files to the bit, and kept every item's Result the same to the bit. */
    static void sameReplay(FullIndexReplay.Assembled a, FullIndexReplay.Assembled b) throws Exception {
        for (String f : new String[]{"replay.txt", "perfect.ser", "hook.bin"}) {
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
        FullIndexReplay.Options whole = options(source, root.resolve("uninterrupted"));
        whole.foldsOnly = true;
        whole.hooks.add(new ValuesHook());
        FullIndexReplay.Assembled w = FullIndexReplay.run(whole, "replay", QUIET);
        assertEquals(4, w.items.size(), "the fold fits' items");
        assertTrue(Files.size(w.out.resolve("hook.bin")) > 1000);

        // Killed after its first item, one item a batch.
        Path dir = root.resolve("killed-after-an-item");
        FullIndexReplay.Options a = options(source, dir);
        a.foldsOnly = true;
        a.batchBytes = 1;
        a.hooks.add(new ValuesHook());
        a.afterItem = n -> {
            if (n == 1) {
                throw new Interrupted();
            }
        };
        assertThrows(Interrupted.class, () -> FullIndexReplay.run(a, "replay", QUIET));
        FullIndexReplay.Options again = options(source, dir);
        again.foldsOnly = true;
        again.batchBytes = 1;
        again.resume = true;
        ValuesHook counted = new ValuesHook();
        again.hooks.add(counted);
        sameReplay(w, FullIndexReplay.run(again, "replay", QUIET));
        assertEquals(3, counted.called, "only the items with no record are scored again");

        // Killed in its one batch, after the last round, as its third item is about to be scored.
        Path batch = root.resolve("killed-in-a-batch");
        FullIndexReplay.Options b = options(source, batch);
        b.foldsOnly = true;
        b.batchBytes = 1e15;
        ValuesHook thrower = new ValuesHook();
        thrower.throwAt = 2;
        b.hooks.add(thrower);
        assertThrows(Interrupted.class, () -> FullIndexReplay.run(b, "replay", QUIET));
        FullIndexReplay.Options more = options(source, batch);
        more.foldsOnly = true;
        more.batchBytes = 1e15;
        more.resume = true;
        more.hooks.add(new ValuesHook());
        sameReplay(w, FullIndexReplay.run(more, "replay", QUIET));

        // Another commit, or no hook, is another replay: refused, as is a fresh start over its records.
        FullIndexReplay.Options other = options(source, batch);
        other.foldsOnly = true;
        other.resume = true;
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> FullIndexReplay.run(other, "replay", QUIET));
        assertTrue(e.getMessage().contains("hooks: values"), e.getMessage());
        FullIndexReplay.Options fresh = options(source, batch);
        fresh.foldsOnly = true;
        fresh.hooks.add(new ValuesHook());
        assertThrows(IllegalStateException.class, () -> FullIndexReplay.run(fresh, "replay", QUIET));
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
        for (String hook : new String[]{"wide", "sink", "export"}) {
            FullIndexReplay.Options h = options(source, out);
            h.wide = hook.equals("wide");
            h.sink = hook.equals("sink") ? root.resolve("sink") : null;
            h.export = hook.equals("export") ? root.resolve("export") : null;
            a = assertThrows(IllegalArgumentException.class, () -> FullIndexReplay.run(h, "r", QUIET));
            assertTrue(a.getMessage().startsWith("--" + hook + " needs"), a.getMessage());
        }

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
}
