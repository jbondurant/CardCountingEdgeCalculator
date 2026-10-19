import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The replay of a finished full-index run (PLAN_COMPUTE.md 6.1): its strategies put back from its
 * fit.ckpt, given their last round again and scored again, item by item, with whatever the page
 * needs from them that the run did not keep (the wide plans' figures, each state's values for the
 * edge at each count, the simulator's full-play export), and every item checked against the part
 * the run wrote.
 *
 * Why replay rather than refit: the new figures must belong to the very strategies the published
 * ones came from. The run's fit.ckpt holds every strategy as fitted, so the replay skips the passes
 * that fitted the later plays and makes only the last rounds and the scoring again. The published
 * figures keep coming from the run's own results.ser; the replay only adds.
 *
 * Why it checks itself against the source: a replay that put back a strategy wrongly, or scored
 * with classes that drifted, would add figures that do not belong to the published rows. So every
 * item scored is set beside the source's part-COUNT-PEN-PART.ckpt, every number of it (ResultDiff):
 * the same to the bit first; else within 1e-12 relative, the project's standard for a figure worked
 * out again in another order, which is reported with its largest difference and lets the run go on;
 * anything further stops the run, naming the item, before it is kept. And each state's perfect-play
 * value is worked out again as its last rounds walk it and set beside the source's (PLAN_COMPUTE.md
 * 4.2), the same way, and kept whole, where the source left a state out among them.
 *
 * What it refuses before any work: a source directory whose run.id differs from what these
 * settings and these classes would write in anything but its commit line (a count line written by
 * classes before the betting vectors, which named counts only, is taken by name); a fit.ckpt not
 * marked fitted; an item with no part to compare with; an output directory in the source; a tree
 * store it could write (only --trees-read-only or --trees none); and registered counts on the main
 * state file, whose replay is the page look of PLAN_COMPUTE.md 6.3 with its own ledger line.
 *
 * Nothing in the source directory is written: it is read through FullIndexCheckpoint.source, which
 * refuses every write. The replay keeps its own checkpoints in its own directory under its own
 * run.id, which names the source, the SHA-256 of its fit.ckpt and the source's whole identity: one
 * record an item, written whole, forced to disk and moved into place, holding everything the item
 * produced (its Result, its comparison, what each hook kept, and the perfect values first worked
 * out in its batch). So a crash or a kill loses at most the batch in hand, and resumed (--resume)
 * the replay scores only the items with no record. Everything it writes at the end is put together
 * from those records, never from what is in memory, so an interrupted and resumed replay writes
 * what an uninterrupted one writes (FullIndexReplayTest).
 *
 * What each item adds is a hook's: --wide scores the wide plans and writes wide.ser in DIR
 * (ReplayHooks.WidePlans); --sink SINK_DIR keeps each state's values for the edge at each count
 * (SINK_DIR/run-b.ser, ReplayHooks.Sink); --export EXPORT_DIR writes the full-play rows of the
 * simulator's bundle (StrategyExport.ReplayExport: the rows SIM_CHECK.md registers at the
 * penetrations replayed unless --export-row names others, at the 1-8 same schedule unless
 * --export-plans names others), each checked on its strategies' values as its item is replayed,
 * written as EXPORT_DIR.partial at the end with keys-sample.tsv and lookups.tsv, round-tripped and
 * renamed EXPORT_DIR. Where a fold's refit ranks a deal otherwise than the play its splits were
 * valued under, the file gives split hands that play's ranking (SHOE_SPEC.md B2.4's split lines), and
 * the log names each fold's count of them; the log also says, for every strategy replayed, whether
 * its fit converged or stopped at the pass cap, and its refitDiffers. A row that cannot be exported
 * (a fold whose strategy groups are not the refit's, or whose files fail their check on the values)
 * is named in the log as its item is replayed and refused at the end, after the other hooks have
 * written their files.
 *
 * The runs folder's budget (DiskBudget) is checked before anything is written, DiskBudget.REPLAY_ITEM
 * an item for the records and the hooks' files. Once the replay has returned, its caller lets the
 * records go (letGo: FullIndexRun unless --keep-records, PageLook): only a resume reads them, and
 * replay.txt, perfect.ser and the hooks' files are written from them by then. A directory whose
 * records were let go takes no resume.
 *
 * java -Xmx10g -cp CLASSES:lib/mongo-java-driver-3.12.14.jar FullIndexRun --fit-from SOURCE_DIR
 * --states FILE --out DIR (--trees-read-only PATH | --trees none) [--folds-only] [--only COUNT@P,...]
 * [--wide] [--sink SINK_DIR] [--export EXPORT_DIR [--export-row NAME[@P] ...] [--export-plans LABEL,...]]
 * [--resume] [--disk-budget GB] [--keep-records], with the source's --shoes, --resamples, --threads and --pens.
 */
final class FullIndexReplay {

    private FullIndexReplay() {
    }

    /** What a replay is asked to do, as FullIndexRun reads it from the command line. */
    static final class Options {
        Path states;
        Path out;
        Path fitFrom;
        Path trees;
        boolean noTrees;
        boolean readOnlyTrees;
        Path countsFile;
        int[] pens = FullIndexRun.PENS;
        int shoes = -1;
        int resamples = 1000;
        int threads = 6;
        boolean resume;
        /** Only the fold fits' items, not the learning curve's half fits. */
        boolean foldsOnly;
        /** Only these items, each COUNT@P, COUNT a count's name or one of its systems' and P a penetration as --pens takes it; null for all. */
        List<String> only;
        /** Score the wide plans beside every figure and write wide.ser (ReplayHooks.WidePlans). */
        boolean wide;
        /** Where the state sink's per-state values go, run B of PLAN_COMPUTE.md 5.3 (ReplayHooks.Sink); null for none. */
        Path sink;
        /**
         * Where the full-play export bundle goes (StrategyExport.ReplayExport): written as DIR.partial
         * at the end, round-tripped, then renamed DIR; null for none.
         */
        Path export;
        /** The full-play rows exported, by entry name; null for those SIM_CHECK.md registers (StrategyExport.ReplayExport.registered). */
        List<String> exportRows;
        /** The report plans the exported rows' bets are written for (B2.5 labels). */
        String[] exportPlans = StrategyExport.ReplayExport.PLANS.clone();
        /** Hooks given in code, by a test or a tool that drives the replay (the page look of 6.3), after those the options make. */
        final List<Hook> hooks = new ArrayList<>();
        /**
         * The full-play registration's git blob when the page look (PageLook, PLAN_COMPUTE.md 6.3)
         * drives this replay of step 8's run on the main file, after its one ledger line is
         * written; null otherwise. Set in code only, never from the command line: with it the
         * replay takes registered counts on the main file, and the source's identity is held to
         * the one step 8 wrote (its counts line naming that blob).
         */
        String look;
        /** What a scoring batch may hold, in bytes; when 0 or less, heapFill of the heap less what the replay holds. */
        double batchBytes;
        double heapFill = 0.8;
        /** Called after each item's record is written, with the items replayed so far; the tests interrupt here. */
        java.util.function.IntConsumer afterItem;
        /** The runs folder's budget in bytes (DiskBudget); PageLook, which counts the replay in its own check, gives none. */
        long budget = DiskBudget.budget(null);
        /** Whether FullIndexRun keeps the records once the replay has returned (--keep-records) rather than let them go. */
        boolean keepRecords;
    }

    /** The file left where a finished replay's records were let go (letGo). */
    static final String LET_GO = "records-let-go.txt";

    /** One scoring item: a count's fold fits, or half fits, at a penetration. */
    static final class Item implements Serializable {
        private static final long serialVersionUID = 1L;

        /** The count's place among the run's counts, and its name. */
        final int count;
        final String name;
        final int pen;
        final boolean halves;

        Item(int count, String name, int pen, boolean halves) {
            this.count = count;
            this.name = name;
            this.pen = pen;
            this.halves = halves;
        }

        /** Its name in the source's checkpoint, part-COUNT-PEN-PART (FullIndexFit.JointRun.key). */
        String key() {
            return FullIndexFit.JointRun.key(new int[]{count, pen, halves ? 1 : 0});
        }

        /** Its record's name in the replay's checkpoint. */
        String record() {
            return "replayed-" + key();
        }

        @Override
        public String toString() {
            return key() + " (" + name + " at " + SystemScorer.PENETRATIONS[pen] + ", its " + (halves ? "half" : "fold")
                    + " fits)";
        }
    }

    /**
     * Something the replay makes of each item besides its Result, kept with the item and put
     * together at the end: the wide plans' file, the edge at each count's per-state arrays, the
     * simulator's full-play export. Items are scored one after another, so a hook may attach itself
     * to the scorer in before and take itself off in after.
     */
    interface Hook {
        /** Its name, in its records and its log. */
        String name();

        /** What it does, in the replay's identity: its name, and what it was asked for that changes what it keeps. */
        default String describe() {
            return name();
        }

        /**
         * Once the source is found to be the run these settings make and before its strategies are
         * put back: the scorer every item is scored with, to set up what the hook needs of it.
         */
        default void start(SystemScorer scorer, Options o) throws Exception {
        }

        /**
         * Before the item is scored, its two strategies holding their plays and, after their last
         * round, their values.
         */
        default void before(Item item, SystemScorer scorer, List<FullIndexFit.Strategy> two) throws Exception {
        }

        /**
         * After it is scored and checked against the source, before it is kept: what to keep for
         * the item (null for nothing). It must not change the Result.
         */
        default Serializable after(Item item, SystemScorer scorer, SystemScorer.Result scored,
                                   List<FullIndexFit.Strategy> two) throws Exception {
            return null;
        }

        /** Once every item is kept: its output, from the records alone. */
        default void finish(Assembled all) throws Exception {
        }
    }

    /** Everything one item produced, as its record keeps it. */
    static final class Record implements Serializable {
        private static final long serialVersionUID = 1L;

        final Item item;
        final SystemScorer.Result result;
        /** The item set beside the source's part. */
        final ResultDiff diff;
        /** What each hook kept, by its name, in the order the hooks run. */
        final LinkedHashMap<String, Serializable> kept;
        /** The states whose perfect value was first worked out in the item's batch, and those values; on a batch's first item only. */
        final int[] perfectStates;
        final double[] perfectValues;

        Record(Item item, SystemScorer.Result result, ResultDiff diff, LinkedHashMap<String, Serializable> kept,
               int[] perfectStates, double[] perfectValues) {
            this.item = item;
            this.result = result;
            this.diff = diff;
            this.kept = kept;
            this.perfectStates = perfectStates;
            this.perfectValues = perfectValues;
        }
    }

    /**
     * The replay at its end, read back from its records: what the hooks build their outputs from.
     * Each call reads the records afresh, so nothing one hook does to a Result reaches another.
     */
    static final class Assembled {
        /** The replay's identity, and the source's, its directory and its fit.ckpt's SHA-256. */
        final String identity;
        final String sourceIdentity;
        final Path source;
        final String fitSha256;
        /** The items, in the order they were scored. */
        final List<Item> items;
        /** The run's counts, in order. */
        final List<CountKeys.Count> counts;
        final Path out;
        /** Each state's perfect value as the source holds it and as the replay worked it out; NaN where not. */
        final double[] perfectSource;
        final double[] perfectAgain;
        private final FullIndexCheckpoint own;

        Assembled(String identity, String sourceIdentity, Path source, String fitSha256, List<Item> items,
                  List<CountKeys.Count> counts, Path out, double[] perfectSource, double[] perfectAgain,
                  FullIndexCheckpoint own) {
            this.identity = identity;
            this.sourceIdentity = sourceIdentity;
            this.source = source;
            this.fitSha256 = fitSha256;
            this.items = items;
            this.counts = counts;
            this.out = out;
            this.perfectSource = perfectSource;
            this.perfectAgain = perfectAgain;
            this.own = own;
        }

        /** Item k's record, read from the replay's checkpoint. */
        Record record(int k) throws IOException, ClassNotFoundException {
            return readRecord(own, items.get(k));
        }

        /**
         * Each count's Result with the parts its items replayed put together (SystemScorer.merge),
         * in the order of the run's counts; null for a count none of whose items was replayed. A
         * folds-only replay leaves the half fits' figures empty.
         */
        List<SystemScorer.Result> results() throws IOException, ClassNotFoundException {
            List<List<SystemScorer.Result>> parts = new ArrayList<>();
            for (int k = 0; k < counts.size(); k++) {
                parts.add(new ArrayList<>());
            }
            for (int i = 0; i < items.size(); i++) {
                parts.get(items.get(i).count).add(record(i).result);
            }
            List<SystemScorer.Result> out = new ArrayList<>();
            for (List<SystemScorer.Result> p : parts) {
                out.add(p.isEmpty() ? null : SystemScorer.merge(p));
            }
            return out;
        }
    }

    static Record readRecord(FullIndexCheckpoint own, Item item) throws IOException, ClassNotFoundException {
        Record r = (Record) own.readRecord(item.record());
        if (r == null) {
            throw new IllegalStateException("the replay holds no record of " + item);
        }
        if (!r.item.key().equals(item.key())) {
            throw new IllegalStateException(item.record() + " holds " + r.item + ", not " + item);
        }
        return r;
    }

    // ------------------------------------------------------------------ the source and the items

    /**
     * How the source's run.id differs from the identity these settings and classes would write
     * (FullIndexRun.identity), its commit line aside; empty when it does not. Every other line must
     * be equal, but a count line may be in either of two forms: this build's (CountKeys.Count.describe,
     * with its betting variant's "  with" line), or the count's name alone with no "  with" line,
     * as classes written before the betting vectors named their counts (c06c061's run.id does).
     * The names-only form says nothing of a count's tags; that the replayed strategies are the
     * source's is shown by every item's comparison with its part, not by this line.
     */
    static String differences(String source, String expected, List<CountKeys.Count> counts) {
        List<String> head = new ArrayList<>();
        List<List<String>> was = blocks(source, head);
        List<String> headNow = new ArrayList<>();
        List<List<String>> now = blocks(expected, headNow);
        StringBuilder out = new StringBuilder(FullIndexCheckpoint.differences(String.join("\n", head),
                String.join("\n", headNow)));
        if (was.size() != now.size()) {
            out.append("  was: ").append(was.size()).append(" counts\n  now: ").append(now.size()).append(" counts\n");
            return out.toString();
        }
        for (int k = 0; k < now.size(); k++) {
            List<String> a = was.get(k);
            List<String> b = now.get(k);
            boolean byName = a.size() == 1 && a.get(0).equals("count " + counts.get(k).name);
            if (!a.equals(b) && !byName) {
                out.append("  was: ").append(String.join(" / ", a)).append("\n  now: ").append(String.join(" / ", b))
                        .append('\n');
            }
        }
        return out.toString();
    }

    /** An identity's count lines, a block a count with its "  with" lines; every other line but the commit's into head. */
    private static List<List<String>> blocks(String identity, List<String> head) {
        List<List<String>> out = new ArrayList<>();
        for (String line : identity.split("\n")) {
            if (line.startsWith("count ")) {
                out.add(new ArrayList<>(Arrays.asList(line)));
            } else if (line.startsWith("  with ") && !out.isEmpty()) {
                out.get(out.size() - 1).add(line);
            } else if (!line.startsWith("commit ")) {
                head.add(line);
            }
        }
        return out;
    }

    /**
     * The items to replay, in the order the run scored them (each count's penetrations in turn,
     * at each its fold fits and then its half fits), less the half fits when foldsOnly, and only
     * those named when only is given; a name that picks no item is refused.
     */
    static List<Item> items(List<CountKeys.Count> counts, int[] pens, boolean foldsOnly, List<String> only) {
        boolean[] used = only == null ? null : new boolean[only.size()];
        List<Item> out = new ArrayList<>();
        for (int k = 0; k < counts.size(); k++) {
            CountKeys.Count c = counts.get(k);
            for (int p : pens) {
                for (int part = 0; part < (foldsOnly ? 1 : 2); part++) {
                    boolean picked = only == null;
                    for (int x = 0; only != null && x < only.size(); x++) {
                        if (names(only.get(x), c, p)) {
                            picked = true;
                            used[x] = true;
                        }
                    }
                    if (picked) {
                        out.add(new Item(k, c.name, p, part == 1));
                    }
                }
            }
        }
        for (int x = 0; only != null && x < only.size(); x++) {
            if (!used[x]) {
                throw new IllegalArgumentException("--only " + only.get(x) + " names no item of the run: give COUNT@P, "
                        + "COUNT a count's name or one of its systems' and P a penetration of the run as --pens takes it");
            }
        }
        return out;
    }

    /** Whether COUNT@P names count c at penetration p. */
    private static boolean names(String spec, CountKeys.Count c, int p) {
        int at = spec.lastIndexOf('@');
        if (at < 0) {
            throw new IllegalArgumentException("--only takes COUNT@P, not " + spec);
        }
        String name = spec.substring(0, at).trim();
        int pen = Integer.parseInt(spec.substring(at + 1).trim());
        return pen == p && (c.name.equals(name) || c.systems.contains(name));
    }

    /**
     * The hooks the options ask for, the wide plans, the sink and the export in that order, then
     * those given in code; two of one name are refused.
     */
    static List<Hook> hooks(Options o, PrintStream log) {
        List<Hook> out = new ArrayList<>();
        if (o.wide) {
            out.add(new ReplayHooks.WidePlans());
        }
        if (o.sink != null) {
            out.add(new ReplayHooks.Sink(o.sink));
        }
        if (o.export != null) {
            // StrategyExport's own full-play builder (site-int-spec), not a second one here: it also writes
            // keys-sample.tsv and lookups.tsv, which the simulator's D2 and D5 read, and round-trips the
            // bundle before renaming it DIR.
            if (o.only != null && o.exportRows == null) {
                throw new IllegalArgumentException("--only with --export: name the rows to export with --export-row "
                        + "NAME@P, since the rows SIM_CHECK.md registers are not all among the items replayed, and the "
                        + "export refuses a row it was asked for that no item replayed");
            }
            List<String> rows = o.exportRows == null ? StrategyExport.ReplayExport.registered(o.pens)
                    : StrategyExport.ReplayExport.named(o.exportRows, o.pens);
            StrategyExport.ReplayExport export = new StrategyExport.ReplayExport(o.export.toAbsolutePath().normalize(),
                    o.states, o.shoes, rows, o.exportPlans, StrategyExport.SAMPLE_SEED, log);
            if (o.exportRows == null) {
                // The registered rows carry the wide plans SIM_CHECK.md registers for them (F43 to F48),
                // which the replay scores with --wide; without it they are left out, and the log says so.
                java.util.Map<String, List<String>> wide = StrategyExport.ReplayExport.registeredPlans(o.pens);
                if (o.wide) {
                    for (java.util.Map.Entry<String, List<String>> e : wide.entrySet()) {
                        export.plans(e.getKey(), e.getValue());
                    }
                } else if (!wide.isEmpty()) {
                    log.println("export: the wide plans SIM_CHECK.md registers (" + wide + ") are not exported: the "
                            + "replay scores them only with --wide");
                }
            }
            out.add(export);
        } else if (o.exportRows != null) {
            throw new IllegalArgumentException("export rows were named but no --export directory was given");
        }
        out.addAll(o.hooks);
        List<String> names = new ArrayList<>();
        for (Hook h : out) {
            if (names.contains(h.name())) {
                throw new IllegalArgumentException("two hooks named " + h.name());
            }
            names.add(h.name());
        }
        return out;
    }

    /** The replay's identity: its commit, its source and what it replays, then the source's own identity whole. */
    static String identity(String commit, Path source, String fitSha256, Options o, List<Hook> hooks, String sourceId) {
        StringBuilder b = new StringBuilder();
        b.append("FullIndexRun replay\n");
        b.append("commit ").append(commit).append('\n');
        b.append("of the run in ").append(source).append(", its fit.ckpt SHA-256 ").append(fitSha256).append('\n');
        b.append("items: ").append(o.foldsOnly ? "the fold fits only" : "the fold fits and the half fits");
        b.append(o.only == null ? "" : ", only " + String.join(", ", o.only)).append('\n');
        b.append("hooks:");
        for (Hook h : hooks) {
            b.append(' ').append(h.describe());
        }
        b.append("\nthe source's identity:\n").append(sourceId);
        return b.toString();
    }

    static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 20];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte x : md.digest()) {
            hex.append(String.format("%02x", x));
        }
        return hex.toString();
    }

    // ------------------------------------------------------------------ the run

    /** The replay the options ask for, its classes' commit given: refusals first, then the items, then what is put together. */
    static Assembled run(Options o, String commit, PrintStream log) throws Exception {
        long t0 = System.nanoTime();
        if (o.states == null || o.out == null || o.fitFrom == null) {
            throw new IllegalArgumentException("a replay needs --fit-from, --states and --out");
        }
        if (!o.readOnlyTrees && !o.noTrees) {
            throw new IllegalArgumentException("a replay reads its trees with --trees-read-only PATH, or builds them each "
                    + "round with --trees none: a store of its own would take every state's trees again (14.9 GB for the "
                    + "main file), and the source's is never to be opened writable (COUNT_DESIGN.md section 7, \"A hazard\")");
        }
        Path source = o.fitFrom.toAbsolutePath().normalize();
        Path out = o.out.toAbsolutePath().normalize();
        if (out.startsWith(source)) {
            throw new IllegalArgumentException(out + " is in the source's directory, " + source
                    + ", where the replay writes nothing");
        }
        for (Path dir : new Path[]{o.sink, o.export}) {
            if (dir != null && dir.toAbsolutePath().normalize().startsWith(source)) {
                throw new IllegalArgumentException(dir + " is in the source's directory, " + source
                        + ", where the replay writes nothing");
            }
        }
        if (Files.exists(out.resolve(LET_GO))) {
            throw new IllegalStateException("the replay in " + out + " finished and its records were let go (" + LET_GO
                    + "); what it wrote is there. Replay into a new directory");
        }
        List<Hook> hooks = hooks(o, log);
        FullIndexCheckpoint src = FullIndexCheckpoint.source(source);
        Path fitFile = source.resolve("fit.ckpt");
        if (!Files.isRegularFile(fitFile)) {
            throw new IllegalStateException(source + " holds no fit.ckpt to replay");
        }
        CustomCounts.Listing listing = o.countsFile == null ? null : CustomCounts.read(o.countsFile);
        if (listing != null && CustomCounts.isMain(o.states) && o.look == null) {
            throw new IllegalStateException("counts from " + o.countsFile + " on the main state file: replaying them is "
                    + "the page look of PLAN_COMPUTE.md 6.3, which goes through CustomCounts.lookOnMain with its one "
                    + "ledger line, not this mode");
        }
        int shoes = o.shoes < 0 ? StateStore.shoesDone(o.states) : o.shoes;
        ScoringStates st = FullIndexPilot.load(o.states, shoes);
        SystemScorer scorer = new SystemScorer(st, new Bootstrap(shoes, o.resamples, Bootstrap.SEED), SystemScorer.M_PLAY);
        // As FullIndexRun scores them: counts of our own are also bet on their own value.
        scorer.ownValueRows = listing != null;
        List<CountKeys.Count> counts = new ArrayList<>();
        if (listing == null) {
            counts.add(CountKeys.nullCount());
            counts.addAll(CountKeys.catalog());
        } else {
            counts.addAll(listing.counts());
        }
        String expected = FullIndexRun.identity(commit, o.states, shoes, st.size, o.resamples, o.threads, counts, o.pens,
                listing == null ? null : listing.file + (o.look != null ? ", blob " + o.look : ", a file not registered"));
        String differ = differences(src.identity, expected, counts);
        if (!differ.isEmpty()) {
            throw new IllegalStateException("the run in " + source + " is not the one these settings and classes run, "
                    + "apart from its commit; it differs in:\n" + differ);
        }
        List<Item> items = items(counts, o.pens, o.foldsOnly, o.only);
        for (Item it : items) {
            if (!src.hasPart(it.key())) {
                throw new IllegalStateException(source + " holds no " + it.key() + ".ckpt to set " + it + " beside");
            }
        }
        long more = DiskBudget.REPLAY_ITEM * items.size() - DiskBudget.bytes(out)
                - (o.sink == null ? 0 : DiskBudget.bytes(o.sink)) - (o.export == null ? 0 : DiskBudget.bytes(o.export));
        DiskBudget.require(out, Math.max(0, more), o.budget, "The replay of " + items.size() + " items");
        String fitSha = sha256(fitFile);
        String identity = identity(commit, source, fitSha, o, hooks, src.identity);
        log.printf("FullIndexRun replay %s%s: the run in %s (fit.ckpt SHA-256 %s), %d items%s; %d shoes, %d valued "
                        + "states of %s, read and never written; %d resamples; %d threads; max heap %.0f MB; trees %s; "
                        + "hooks %s; records in %s.%n", commit, o.resume ? ", resumed" : "", source, fitSha, items.size(),
                o.foldsOnly ? ", the fold fits only" : "", shoes, st.size, o.states, o.resamples, o.threads,
                Runtime.getRuntime().maxMemory() / 1e6, o.noTrees ? "built every round" : "read, and never written, from "
                        + o.trees, names(hooks), out);
        log.flush();
        TreeStore store = o.noTrees ? null
                : TreeStore.readOnly(st, o.trees, FullIndexRun.stateFile(o.states) + "\nstates " + st.size);
        FullIndexCheckpoint own = new FullIndexCheckpoint(out, identity);
        double[] perfectSource;
        try {
            // The fit put back and found finished before the replay's directory is opened, so a
            // refusal writes nothing anywhere.
            for (Hook h : hooks) {
                h.start(scorer, o);
            }
            Restored back = restore(o, scorer, counts, src, store != null ? store : FullIndexFit.built(st), log);
            own.open(o.resume);
            perfectSource = replay(o, scorer, counts, items, src, own, back, hooks, log);
        } finally {
            if (store != null) {
                store.close();
            }
        }
        Assembled all = assemble(identity, src, fitSha, items, counts, out, perfectSource, own);
        write(all, log);
        for (Hook h : hooks) {
            h.finish(all);
        }
        log.printf("Finished %s, %.2f h on the wall this start.%n", java.time.LocalDateTime.now(),
                (System.nanoTime() - t0) / 3.6e12);
        return all;
    }

    private static String names(List<Hook> hooks) {
        List<String> n = new ArrayList<>();
        for (Hook h : hooks) {
            n.add(h.name());
        }
        return n.isEmpty() ? "none" : String.join(", ", n);
    }

    /** The source's strategies put back, waiting for their last rounds, and its perfect values set aside. */
    private static final class Restored {
        final List<FullIndexFit.Fits> fits;
        final FullIndexFit fit;
        final double[] perfectSource;

        Restored(List<FullIndexFit.Fits> fits, FullIndexFit fit, double[] perfectSource) {
            this.fits = fits;
            this.fit = fit;
            this.perfectSource = perfectSource;
        }
    }

    /**
     * Every strategy the run made, the half fits too, made afresh in its order and put back from
     * its fit.ckpt (FullIndexCheckpoint.readFit), which must be marked fitted; trimmed as the joint
     * run trims them once fitted. The source's perfect values are set aside, and the fit's made NaN,
     * so the last rounds work out every state's again as they walk it.
     */
    private static Restored restore(Options o, SystemScorer scorer, List<CountKeys.Count> counts, FullIndexCheckpoint src,
                                    FullIndexFit.Trees trees, PrintStream log) throws Exception {
        List<FullIndexFit.Fits> fits = FullIndexFit.plan(scorer, counts, o.pens, true, true);
        List<FullIndexFit.Strategy> all = new ArrayList<>();
        for (FullIndexFit.Fits f : fits) {
            all.addAll(f.all());
        }
        FullIndexFit fit = new FullIndexFit(scorer.st, scorer.rules(), trees, o.threads, true, true);
        fit.log = log;
        Boolean fitted = src.readFit(fit, all);
        if (fitted == null || !fitted) {
            throw new IllegalStateException(src.dir.resolve("fit.ckpt") + " is not marked fitted: its passes had not "
                    + "finished, so its strategies are not the ones its parts were scored with");
        }
        for (FullIndexFit.Strategy t : all) {
            t.trim();
        }
        double[] perfectSource = fit.perfect.clone();
        Arrays.fill(fit.perfect, Double.NaN);
        return new Restored(fits, fit, perfectSource);
    }

    /** The bytes a state's split values kept are allowed beside a batch, as FullIndexFit.JointRun.splitAllowance. */
    static final double SPLIT_ALLOWANCE = 32_000;

    /**
     * Puts the source's strategies back, gives the items with no record their last rounds in
     * batches and scores them one after another, each checked and kept as it is scored; gives the
     * source's perfect values. A batch is formed as the joint run forms one (FullIndexFit.Prepared),
     * so a batch's last round walks each state once for all its items.
     */
    private static double[] replay(Options o, SystemScorer scorer, List<CountKeys.Count> counts, List<Item> items,
                                   FullIndexCheckpoint src, FullIndexCheckpoint own, Restored back,
                                   List<Hook> hooks, PrintStream log) throws Exception {
        ScoringStates st = scorer.st;
        List<FullIndexFit.Fits> fits = back.fits;
        FullIndexFit fit = back.fit;
        double[] perfectSource = back.perfectSource;

        List<Item> todo = new ArrayList<>();
        int replayed = 0;
        for (Item it : items) {
            Record r = o.resume ? (Record) own.readRecord(it.record()) : null;
            if (r == null) {
                todo.add(it);
                continue;
            }
            replayed++;
            if (r.perfectStates != null) {
                for (int x = 0; x < r.perfectStates.length; x++) {
                    fit.perfect[r.perfectStates[x]] = r.perfectValues[x];
                }
            }
        }
        if (replayed > 0) {
            log.printf("%d of %d items were replayed before%n", replayed, items.size());
        }
        // The strategies no item left needs are let go.
        for (int k = 0; k < counts.size(); k++) {
            FullIndexFit.Fits f = fits.get(k);
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                boolean folds = false;
                boolean halves = false;
                for (Item it : todo) {
                    if (it.count == k && it.pen == p) {
                        folds |= !it.halves;
                        halves |= it.halves;
                    }
                }
                if (!folds) {
                    Arrays.fill(f.fold[p], null);
                }
                if (!halves) {
                    Arrays.fill(f.half[p], null);
                }
            }
        }

        double room = room(o, fits, todo, fit, st.size, log);
        int next = 0;
        while (next < todo.size()) {
            List<Item> batch = new ArrayList<>();
            double bytes = 0;
            while (next < todo.size()) {
                Item it = todo.get(next);
                double b = fits.get(it.count).itemBytes[it.pen];
                if (!batch.isEmpty() && bytes + b > room) {
                    break;
                }
                batch.add(it);
                bytes += b;
                next++;
            }
            List<FullIndexFit.Strategy> finals = new ArrayList<>();
            for (Item it : batch) {
                FullIndexFit.Fits f = fits.get(it.count);
                finals.addAll(new FullIndexFit.Prepared(scorer, f, it.pen, it.halves).give(f));
            }
            double[] before = fit.perfect.clone();
            long f0 = System.nanoTime();
            fit.run(finals, false);
            double lastRound = (System.nanoTime() - f0) / 1e9;
            int[][] fresh = new int[1][];
            double[][] values = new double[1][];
            perfectAgain(before, fit.perfect, perfectSource, fresh, values);

            long s0 = System.nanoTime();
            boolean first = true;
            for (Item it : batch) {
                Record r = item(o, scorer, fits.get(it.count), it, src, hooks, first ? fresh[0] : null,
                        first ? values[0] : null);
                own.writeRecord(it.record(), r);
                first = false;
                List<String> fitted = new ArrayList<>();
                for (FullIndexFit.Strategy t : FullIndexFit.strategiesOf(fits.get(it.count), it.pen, it.halves)) {
                    fitted.add(howFitted(t));
                    t.release();
                }
                replayed++;
                log.printf("%s: %s%n", it, r.diff.describe());
                for (String f : fitted) {
                    log.printf("  %s%n", f);
                }
                log.flush();
                if (o.afterItem != null) {
                    o.afterItem.accept(replayed);
                }
            }
            log.printf("a batch of %d items: last round %.1f s, %d states' perfect values worked out again; scored in "
                            + "%.1f s; %d of %d items replayed%n", batch.size(), lastRound, fresh[0].length,
                    (System.nanoTime() - s0) / 1e9, replayed, items.size());
            log.flush();
        }
        return perfectSource;
    }

    /**
     * One item scored, set beside the source's part, and what the hooks keep of it: its record.
     * An item beyond 1e-12 anywhere stops the replay here, naming it, and nothing of it is kept.
     */
    private static Record item(Options o, SystemScorer scorer, FullIndexFit.Fits f, Item it, FullIndexCheckpoint src,
                               List<Hook> hooks, int[] perfectStates, double[] perfectValues) throws Exception {
        List<FullIndexFit.Strategy> two = FullIndexFit.strategiesOf(f, it.pen, it.halves);
        for (Hook h : hooks) {
            h.before(it, scorer, two);
        }
        SystemScorer.Result r = scorer.score(f.count, CountKeys.bettingVariant(f.count), f, new int[]{it.pen},
                o.threads, it.halves ? SystemScorer.Part.HALVES : SystemScorer.Part.FOLDS);
        SystemScorer.Result was = src.readPart(it.key());
        if (was == null) {
            throw new IllegalStateException(src.dir + " holds no " + it.key() + ".ckpt to set " + it + " beside");
        }
        ResultDiff d = ResultDiff.of(was, r);
        if (!d.passes()) {
            throw new IllegalStateException(it + " differs from the source's " + it.key() + ".ckpt beyond "
                    + ResultDiff.RELATIVE + " relative, so the replay stops and keeps nothing of it: " + d.describe());
        }
        LinkedHashMap<String, Serializable> kept = new LinkedHashMap<>();
        for (Hook h : hooks) {
            Serializable x = h.after(it, scorer, r, two);
            if (x != null) {
                kept.put(h.name(), x);
            }
        }
        return new Record(it, r, d, kept, perfectStates, perfectValues);
    }

    /**
     * How a strategy's fit ended, for the log: its passes, whether it converged or stopped at the
     * pass cap (then the pass whose play it kept and its clean-ups), and refitDiffers, the
     * (group, deal) cells whose first move the refit of its last round's values plays otherwise
     * than the play kept. Where that refit ranks a deal otherwise, the export writes a split line
     * (SHOE_SPEC.md B2.4), so each split line's cause is on record beside it.
     */
    static String howFitted(FullIndexFit.Strategy t) {
        String end = t.capped ? "stopped at the pass cap of " + t.maxPasses + ", pass " + t.bestPass + "'s play kept, "
                + t.cleanUps + " clean-ups (" + t.flipped + " later cells flipped)"
                : t.converged ? "converged" : "neither converged nor capped";
        return t + ": " + t.passes + " passes, " + end + "; refitDiffers " + t.refitDiffers;
    }

    /**
     * What a batch may hold: the room given, or the heap's share less what the replay holds and the
     * split values it will keep; when the largest item does not fit, the split values kept are let
     * go, and if it still does not, the replay is refused, naming the item, before a last round.
     */
    private static double room(Options o, List<FullIndexFit.Fits> fits, List<Item> todo, FullIndexFit fit, int states,
                               PrintStream log) {
        Item big = null;
        double largest = 0;
        for (Item it : todo) {
            double b = fits.get(it.count).itemBytes[it.pen];
            if (b > largest) {
                largest = b;
                big = it;
            }
        }
        if (o.batchBytes > 0) {
            return o.batchBytes;
        }
        double heap = o.heapFill * Runtime.getRuntime().maxMemory();
        double held = FullIndexPilot.usedAfterGc();
        double room = heap - held - SPLIT_ALLOWANCE * states;
        if (largest > room) {
            log.printf("the largest item, %s, holds %.2f GB, more than the %.2f GB left; the split values kept are let go, "
                    + "and each last round values its own afresh%n", big, largest / 1e9, room / 1e9);
            fit.dropSplitCache();
            room = heap - FullIndexPilot.usedAfterGc();
        }
        if (largest > room) {
            throw new IllegalStateException(String.format("the largest item, %s, holds %.2f GB, more than the %.2f GB left "
                    + "of %.0f%% of the heap; give the replay a larger heap", big, largest / 1e9, room / 1e9,
                    100 * o.heapFill));
        }
        log.printf("every strategy put back; a batch holds up to %.0f MB%n", room / 1e6);
        return room;
    }

    /**
     * The states whose perfect value a batch worked out (NaN before, a value after), into
     * states[0] and values[0], each set beside the source's value where it has one: the same to
     * the bit, or within 1e-12 relative; further apart stops the replay, naming the state. A state
     * the source left out (NaN there) is kept without a comparison, which is how 4.2 of the plan
     * fills a perfect array the source left incomplete.
     */
    static void perfectAgain(double[] before, double[] after, double[] source, int[][] states, double[][] values) {
        int n = 0;
        for (int s = 0; s < after.length; s++) {
            if (Double.isNaN(before[s]) && !Double.isNaN(after[s])) {
                n++;
            }
        }
        states[0] = new int[n];
        values[0] = new double[n];
        n = 0;
        for (int s = 0; s < after.length; s++) {
            if (Double.isNaN(before[s]) && !Double.isNaN(after[s])) {
                double d = relative(source[s], after[s]);
                if (!Double.isNaN(source[s]) && !(d <= ResultDiff.RELATIVE)) {
                    throw new IllegalStateException("state " + s + "'s perfect value is " + source[s] + " in the source and "
                            + after[s] + " worked out again, " + d + " apart relative: beyond " + ResultDiff.RELATIVE);
                }
                states[0][n] = s;
                values[0][n] = after[s];
                n++;
            }
        }
    }

    /** |a - b| over the larger of |a| and |b|: 0 when they are equal, NaN when either is. */
    static double relative(double a, double b) {
        return a == b ? 0 : Math.abs(a - b) / Math.max(Math.abs(a), Math.abs(b));
    }

    // ------------------------------------------------------------------ the end, from the records

    /** The replay put together from its records: every item's must be there, and the perfect values from all of them. */
    static Assembled assemble(String identity, FullIndexCheckpoint src, String fitSha, List<Item> items,
                              List<CountKeys.Count> counts, Path out, double[] perfectSource, FullIndexCheckpoint own)
            throws IOException, ClassNotFoundException {
        double[] again = new double[perfectSource.length];
        Arrays.fill(again, Double.NaN);
        for (Item it : items) {
            Record r = readRecord(own, it);
            if (r.perfectStates == null) {
                continue;
            }
            for (int x = 0; x < r.perfectStates.length; x++) {
                int s = r.perfectStates[x];
                if (!Double.isNaN(again[s]) && Double.doubleToLongBits(again[s]) != Double.doubleToLongBits(r.perfectValues[x])) {
                    throw new IllegalStateException("two records give state " + s + " two perfect values");
                }
                again[s] = r.perfectValues[x];
            }
        }
        return new Assembled(identity, src.identity, src.dir, fitSha, items, counts, out, perfectSource, again, own);
    }

    /**
     * Writes, from the records, replay.txt (each item's comparison, the totals and the perfect
     * values' comparison, in words, with nothing timed in it, so an interrupted replay writes the
     * same file) and perfect.ser (the replay's identity, the source fit.ckpt's SHA-256, and each
     * state's perfect value as the source holds it and as the replay worked it out, NaN where not).
     */
    static void write(Assembled all, PrintStream log) throws Exception {
        StringBuilder t = new StringBuilder();
        t.append(all.identity).append("\n\nEvery item set beside the source's part (ResultDiff), number by number: the same "
                + "to the bit, else within ").append(ResultDiff.RELATIVE).append(" relative; an item further apart stops "
                + "the replay before it is kept.\n\n");
        long identical = 0;
        long within = 0;
        double largest = 0;
        String largestAt = null;
        for (int k = 0; k < all.items.size(); k++) {
            Record r = all.record(k);
            t.append(r.item).append(": ").append(r.diff.describe()).append('\n');
            if (r.diff.identical()) {
                identical++;
            } else {
                within++;
                if (largestAt == null || r.diff.largest > largest) {
                    largest = r.diff.largest;
                    largestAt = r.item.key() + ", " + r.diff.largestAt;
                }
            }
        }
        t.append(String.format("%nItems replayed: %d; the same to the bit as the source: %d; within %s relative but not "
                        + "to the bit: %d%s.%n", all.items.size(), identical, ResultDiff.RELATIVE, within,
                within == 0 ? "" : " (the largest difference " + largest + ", at " + largestAt + ")"));
        t.append(perfectWords(all.perfectSource, all.perfectAgain));
        Path txt = all.out.resolve("replay.txt");
        writeWhole(txt, o -> o.write(t.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path ser = all.out.resolve("perfect.ser");
        writeWhole(ser, o -> {
            ObjectOutputStream w = new ObjectOutputStream(o);
            w.writeUTF(all.identity);
            w.writeUTF(all.fitSha256);
            w.writeObject(all.perfectSource);
            w.writeObject(all.perfectAgain);
            w.flush();
        });
        log.print(t.substring(t.indexOf("\n\nEvery item") + 2));
        log.printf("Wrote %s and %s.%n", txt, ser);
        log.flush();
    }

    /** The perfect values worked out again set beside the source's, in words. */
    static String perfectWords(double[] source, double[] again) {
        int valued = 0;
        int same = 0;
        int within = 0;
        int sourceLacked = 0;
        int notAgain = 0;
        int neither = 0;
        double largest = 0;
        int largestAt = -1;
        for (int s = 0; s < source.length; s++) {
            boolean a = !Double.isNaN(source[s]);
            boolean b = !Double.isNaN(again[s]);
            if (b) {
                valued++;
            }
            if (a && b) {
                if (Double.doubleToLongBits(source[s]) == Double.doubleToLongBits(again[s])) {
                    same++;
                } else {
                    within++;
                    double d = relative(source[s], again[s]);
                    if (largestAt < 0 || d > largest) {
                        largest = d;
                        largestAt = s;
                    }
                }
            } else if (b) {
                sourceLacked++;
            } else if (a) {
                notAgain++;
            } else {
                neither++;
            }
        }
        return String.format("Perfect values (PLAN_COMPUTE.md 4.2): worked out again for %d of %d states; the same to the "
                        + "bit as the source's: %d; within %s relative but not to the bit: %d%s; the source had none for "
                        + "%d of them; the source had one the replay did not work out again for %d states; neither had one "
                        + "for %d.%n", valued, source.length, same, ResultDiff.RELATIVE, within,
                within == 0 ? "" : " (the largest " + largest + ", state " + largestAt + ")", sourceLacked, notAgain, neither);
    }

    /**
     * Lets go of a finished replay's records, which only a resume reads: the list of them, each with
     * its size, written first as LET_GO, then each deleted. Called once the replay has returned,
     * never inside run, whose Assembled reads the records (record) and whose hooks write their files
     * from them. Gives the bytes let go.
     */
    static long letGo(Assembled all, PrintStream log) throws IOException {
        StringBuilder t = new StringBuilder("The records of the replay in " + all.out + ", one an item, let go once "
                + "replay.txt, perfect.ser and every hook's file were written from them (FullIndexReplay.letGo). Only "
                + "a resume read them.\n\n");
        List<Path> files = new ArrayList<>();
        long total = 0;
        for (Item it : all.items) {
            Path f = all.out.resolve(it.record() + ".ckpt");
            long b = Files.size(f);
            files.add(f);
            total += b;
            t.append(f.getFileName()).append('\t').append(b).append('\n');
        }
        t.append(String.format("%nRecords: %d, %d bytes.%n", files.size(), total));
        writeWhole(all.out.resolve(LET_GO), o -> o.write(t.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for (Path f : files) {
            Files.delete(f);
        }
        log.printf("Let go of the replay's %d records, %.2f GB (%s).%n", files.size(), total / 1e9,
                all.out.resolve(LET_GO));
        return total;
    }

    /** What writes a file's bytes. */
    interface Bytes {
        void write(java.io.OutputStream o) throws IOException;
    }

    /** A file written whole to a temporary file, forced to disk and moved into place, as FullIndexCheckpoint writes. */
    static void writeWhole(Path file, Bytes bytes) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp.toFile());
             java.io.BufferedOutputStream b = new java.io.BufferedOutputStream(fo, 1 << 20)) {
            bytes.write(b);
            b.flush();
            fo.getFD().sync();
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
