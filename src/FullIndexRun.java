import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The full-index run (FULL_INDEX_PLAYS.md section 6): every count, the null count and the
 * catalog, fitted with full play on every valued state of the state file, at every
 * penetration, both folds and the half fits, and scored both ways with the bootstrap, by
 * FullIndexFit.JointRun. The state file is only read.
 *
 * It runs for many hours, so it checkpoints into its output directory (FullIndexCheckpoint)
 * after every round of the passes and every item scored, and keeps every state's trees on disk
 * (TreeStore) once built, so that later rounds read them. Started again with --resume on the
 * same classes, state file and settings it goes on from the last checkpoint, and gives the
 * Results an uninterrupted run gives, to the bit; with anything else it refuses, saying what
 * differs. The classes must carry the commit they were built from, as commit.txt on the class
 * path, which the run's identity names.
 *
 * At the end it writes results.ser (every count's SystemScorer.Result, the perfect row's
 * values and the run's rounds, for the report to read) and prints each count's figures at 80%.
 * Its Results are not widened (Result.widen is null): the report sets each count's widening
 * from the pilot before it prints a full-play interval (FULL_INDEX_PLAYS.md section 4).
 *
 * Before the passes it checks that its largest scoring item will fit in the heap beside what
 * the run will hold, and refuses to start if not, naming the item; after the passes it checks
 * again with what the run holds, letting the split values kept go if that makes the room.
 *
 * --counts FILE runs the counts a candidates file or a registration lists (CustomCounts), each
 * with its betting variant, in place of the null count and the catalog; on the main state file
 * only a registration is taken, committed as it stands, and only the full-play one where the
 * registration names one (custom-counts-fullplay.json); the ledger must hold no look at another
 * registration unless --second-look is given; and the run's start is written to the ledger once
 * every refusal is behind, as its passes start. --pens 2 (or 0,1,2) runs those penetrations alone.
 * The identity names every count's tags, divisor, betting vector and form, so a resume with any of
 * them changed is refused, saying what differs.
 *
 * The trees go in a store of the run's own (--trees PATH, by default trees.bin in the output
 * directory), and a store there of another run is refused, never replaced, as is a file there
 * that is not a tree store (TreeStore.refuseNonStore). --trees-read-only PATH
 * reads another run's store instead, keyed on its states alone, and writes nothing to it
 * (TreeStore.readOnly): the one way a run of other classes may open a kept store such as
 * runs/full-index/full-c06c061/trees.bin.
 *
 * --fit-from DIR replays the finished run in DIR instead (FullIndexReplay, PLAN_COMPUTE.md 6.1): its
 * strategies put back from its fit.ckpt, the passes skipped, each item given its last round and
 * scored again and set beside the part DIR holds, with --folds-only, --only, --wide, --sink and
 * --export saying which items and what more each gives; DIR is only read.
 *
 * java -Xmx10g -cp CLASSES:lib/mongo-java-driver-3.12.14.jar FullIndexRun --states FILE --out DIR
 * [--shoes N] [--resamples R] [--threads T] [--trees PATH | --trees none | --trees-read-only PATH]
 * [--counts FILE] [--pens P,P] [--resume] [--second-look]
 * [--fit-from DIR [--folds-only] [--only COUNT@P,...] [--wide] [--sink DIR] [--export DIR [--export-row NAME[@P] ...]
 * [--export-plans LABEL,...]]]
 */
public final class FullIndexRun {

    static final int[] PENS = {0, 1, 2};
    /** What must be left free on disk beside the trees. */
    static final long DISK_MARGIN = 3_000_000_000L;

    public static void main(String[] args) throws Exception {
        run(args, commit(), System.out, DISK_MARGIN);
    }

    /**
     * The run, its classes' commit and the disk margin given rather than read and fixed, so a test
     * can run it on classes built without a commit.txt and a disk of any size.
     */
    static void run(String[] args, String commit, PrintStream log, long diskMargin) throws Exception {
        run(args, commit, log, diskMargin, 0.8);
    }

    /**
     * The same with the share of the heap a run may fill (JointRun.heapFill) given, so a test can
     * make the heap check refuse.
     *
     * On the main file with counts, every refusal comes before the look is written to the ledger:
     * the registration and the ledger (CustomCounts.checkOnMain) before a state is read; then the
     * states, the tree store (a read-only one's identity, or a new one's room on disk), the
     * checkpoint, the strategies and the heap; the ledger line is written as the passes start
     * (JointRun.beforePasses).
     */
    static void run(String[] args, String commit, PrintStream log, long diskMargin, double heapFill) throws Exception {
        Path states = null;
        Path out = null;
        Path treeFile = null;
        boolean noTrees = false;
        boolean readOnlyTrees = false;
        Path countsFile = null;
        int[] pens = PENS;
        int shoes = -1;
        int resamples = 1000;
        int threads = 6;
        boolean resume = false;
        boolean secondLook = false;
        FullIndexReplay.Options replay = new FullIndexReplay.Options();
        boolean replayOnly = false;
        for (int a = 0; a < args.length; a++) {
            switch (args[a]) {
                case "--states":
                    states = Paths.get(args[++a]);
                    break;
                case "--out":
                    out = Paths.get(args[++a]);
                    break;
                case "--shoes":
                    shoes = Integer.parseInt(args[++a]);
                    break;
                case "--resamples":
                    resamples = Integer.parseInt(args[++a]);
                    break;
                case "--threads":
                    threads = Integer.parseInt(args[++a]);
                    break;
                case "--trees":
                    String t = args[++a];
                    noTrees = t.equals("none");
                    treeFile = noTrees ? null : Paths.get(t);
                    break;
                case "--trees-read-only":
                    treeFile = Paths.get(args[++a]);
                    readOnlyTrees = true;
                    break;
                case "--counts":
                    countsFile = Paths.get(args[++a]);
                    break;
                case "--pens":
                    pens = pens(args[++a]);
                    break;
                case "--resume":
                    resume = true;
                    break;
                case "--second-look":
                    secondLook = true;
                    break;
                case "--fit-from":
                    replay.fitFrom = Paths.get(args[++a]);
                    break;
                case "--folds-only":
                    replay.foldsOnly = replayOnly = true;
                    break;
                case "--only":
                    replay.only = java.util.Arrays.asList(args[++a].split(","));
                    replayOnly = true;
                    break;
                case "--wide":
                    replay.wide = replayOnly = true;
                    break;
                case "--sink":
                    replay.sink = Paths.get(args[++a]);
                    replayOnly = true;
                    break;
                case "--export":
                    replay.export = Paths.get(args[++a]);
                    replayOnly = true;
                    break;
                case "--export-row":
                    if (replay.exportRows == null) {
                        replay.exportRows = new java.util.ArrayList<>();
                    }
                    replay.exportRows.add(args[++a]);
                    replayOnly = true;
                    break;
                case "--export-plans":
                    replay.exportPlans = args[++a].split(",");
                    replayOnly = true;
                    break;
                default:
                    throw new IllegalArgumentException("usage: FullIndexRun --states FILE --out DIR [--shoes N] "
                            + "[--resamples R] [--threads T] [--trees PATH | --trees none | --trees-read-only PATH] "
                            + "[--counts FILE] [--pens P,P] [--resume] [--second-look], or a replay, --fit-from DIR "
                            + "[--folds-only] [--only COUNT@P,...] [--wide] [--sink DIR] [--export DIR [--export-row NAME[@P] ...] "
                            + "[--export-plans LABEL,...]]; not " + args[a]);
            }
        }
        if (states == null || out == null) {
            throw new IllegalArgumentException("--states and --out are needed");
        }
        if (replay.fitFrom != null) {
            if (secondLook) {
                throw new IllegalArgumentException("a replay makes no look at registered counts, so takes no --second-look");
            }
            replay.states = states;
            replay.out = out;
            replay.trees = treeFile;
            replay.noTrees = noTrees;
            replay.readOnlyTrees = readOnlyTrees;
            replay.countsFile = countsFile;
            replay.pens = pens;
            replay.shoes = shoes;
            replay.resamples = resamples;
            replay.threads = threads;
            replay.resume = resume;
            replay.heapFill = heapFill;
            FullIndexReplay.run(replay, commit, log);
            return;
        }
        if (replayOnly) {
            throw new IllegalArgumentException("--folds-only, --only, --wide, --sink and --export are for a replay, "
                    + "which --fit-from DIR asks for");
        }
        if (treeFile == null && !noTrees) {
            treeFile = out.resolve("trees.bin");
        }
        long t0 = System.nanoTime();
        int fileShoes = StateStore.shoesDone(states);
        if (shoes < 0) {
            shoes = fileShoes;
        }
        CustomCounts.Listing listing = countsFile == null ? null : CustomCounts.read(countsFile);
        boolean main = CustomCounts.isMain(states);
        if (listing != null && main && !listing.registered) {
            throw new IllegalStateException(countsFile + " is a candidates file; the main file scores only the counts "
                    + "registered in " + CustomCounts.REGISTERED);
        }
        if (listing != null && main && listing.fullPlayFile != null) {
            throw new IllegalStateException(countsFile + " names its full-play registration, " + listing.fullPlayFile
                    + ": the main file is scored with full play on the counts that file lists (COUNT_DESIGN.md, section 8, "
                    + "step 8); give --counts " + listing.fullPlayFile);
        }
        // The registration and the ledger before a state is read.
        String blob = listing != null && main ? CustomCounts.checkOnMain(listing, secondLook) : null;
        List<String> otherLooks = blob == null ? new ArrayList<>()
                : CustomCounts.otherLooks(CustomCounts.ledgerOf(listing), CustomCounts.registeredBlobs(listing, blob));
        String treeIdentity = commit + "\n" + stateFile(states) + "\nstates ";
        ScoringStates st = FullIndexPilot.load(states, shoes);
        treeIdentity += st.size;
        if (treeFile != null && !readOnlyTrees) {
            // A file there that is not a tree store, a mistyped path to a state file or a run's
            // results, is refused and left as it is, before anything is written.
            TreeStore.refuseNonStore(treeFile);
            String was = TreeStore.identityOf(treeFile);
            if (was != null && !was.equals(treeIdentity)) {
                throw new IllegalStateException("the tree store " + treeFile + " was kept by another run; it is left as it "
                        + "is. Read it with --trees-read-only, or give this run a store of its own. It differs in:\n"
                        + FullIndexCheckpoint.differences(was, treeIdentity));
            }
        }
        SystemScorer scorer = new SystemScorer(st, new Bootstrap(shoes, resamples, Bootstrap.SEED), SystemScorer.M_PLAY);
        // Counts of our own are also scored betting on each state's own value under their play
        // (COUNT_DESIGN.md, section 2), which results.ser keeps for no published count.
        scorer.ownValueRows = listing != null;
        List<CountKeys.Count> counts = new ArrayList<>();
        if (listing == null) {
            counts.add(CountKeys.nullCount());
            counts.addAll(CountKeys.catalog());
        } else {
            counts.addAll(listing.counts());
        }
        String identity = identity(commit, states, shoes, st.size, resamples, threads, counts, pens,
                listing == null ? null : listing.file + (blob == null ? ", a file not registered" : ", blob " + blob));
        log.printf("FullIndexRun %s%s: %d shoes, %d valued states of %s, read and never written; %d counts at %d "
                        + "penetrations; %d resamples; %d threads; max heap %.0f MB; trees %s; checkpoints in %s.%n",
                commit, resume ? ", resumed" : "", shoes, st.size, states, counts.size(), pens.length, resamples, threads,
                Runtime.getRuntime().maxMemory() / 1e6, noTrees ? "built every round"
                        : (readOnlyTrees ? "read, and never written, from " : "kept in ") + treeFile, out);
        if (listing != null) {
            log.printf("Counts from %s%s.%n", listing.file, blob == null ? "" : ", git blob " + blob);
        }
        if (!otherLooks.isEmpty()) {
            log.printf("A SECOND LOOK (--second-look): the ledger holds %d look%s at another registration, the first: %s%n",
                    otherLooks.size(), otherLooks.size() == 1 ? "" : "s", otherLooks.get(0));
        }
        log.printf("Started %s, load average %.2f; states read in %.1f s.%n", java.time.LocalDateTime.now(),
                FullIndexTimingReport.loadAverage(), (System.nanoTime() - t0) / 1e9);
        log.flush();

        Files.createDirectories(out);
        FullIndexCheckpoint ck = new FullIndexCheckpoint(out, identity);
        // Checked first, so a refused resume leaves the trees as they are.
        ck.open(resume);
        TreeStore store = noTrees ? null
                : readOnlyTrees ? TreeStore.readOnly(st, treeFile, stateFile(states) + "\nstates " + st.size)
                : new TreeStore(st, treeFile, treeIdentity, diskMargin);
        FullIndexFit.JointRun run = new FullIndexFit.JointRun(scorer, counts, pens, threads);
        run.trees = store != null ? store : FullIndexFit.built(st);
        run.checkpoint = ck;
        run.resume = resume;
        run.log = log;
        run.heapFill = heapFill;
        if (blob != null) {
            // Every refusal is behind once the strategies are made and the heap checked: the look is
            // written to the ledger as the passes start, to be committed before the next.
            String what = "FullIndexRun " + (resume ? "resumed" : "started") + " into " + out + ", " + resamples
                    + " resamples, penetrations " + java.util.Arrays.toString(pens)
                    + (otherLooks.isEmpty() ? "" : ", A SECOND LOOK (--second-look): the ledger held " + otherLooks.size()
                    + " look" + (otherLooks.size() == 1 ? "" : "s") + " at another registration");
            Path ledger = CustomCounts.ledgerOf(listing);
            run.beforePasses = () -> CustomCounts.appendLedger(ledger, commit, blob, what);
        }
        if (store != null) {
            if (!store.readOnly) {
                run.beforeCheckpoint = store::sync;
            }
            log.printf("The tree store holds %d of %d states' trees.%n", store.holds(), st.size);
        }
        FullIndexFit.Scored sc;
        try {
            sc = run.run();
        } finally {
            if (store != null) {
                store.close();
            }
        }
        Path results = out.resolve("results.ser");
        Path tmp = out.resolve("results.ser.tmp");
        try (ObjectOutputStream o = new ObjectOutputStream(Files.newOutputStream(tmp))) {
            o.writeUTF(identity);
            o.writeObject(new ArrayList<>(sc.results));
            o.writeObject(sc.perfect);
            o.writeObject(new ArrayList<>(sc.rounds));
        }
        Files.move(tmp, results, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        summary(log, sc, scorer, pens[pens.length - 1]);
        log.printf("%nWrote %s. Finished %s, %.1f h on the wall this start, load average %.2f.%n", results,
                java.time.LocalDateTime.now(), (System.nanoTime() - t0) / 3.6e12, FullIndexTimingReport.loadAverage());
    }

    /** The commit the classes were built from, as the build wrote it beside them. */
    static String commit() throws IOException {
        try (InputStream in = ClassLoader.getSystemResourceAsStream("commit.txt")) {
            if (in == null) {
                throw new IllegalStateException("no commit.txt on the class path: build the classes from a commit, "
                        + "with the commit written beside them, so a checkpoint can name the code that wrote it");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    static String stateFile(Path states) throws IOException {
        return "states " + states.toAbsolutePath().normalize() + ", " + Files.size(states) + " bytes, modified "
                + Files.getLastModifiedTime(states).toMillis();
    }

    /** Penetrations as given on the command line: 0, 1 and 2 for 70, 75 and 80%, comma-separated, in order. */
    static int[] pens(String text) {
        String[] parts = text.split(",");
        int[] out = new int[parts.length];
        for (int k = 0; k < parts.length; k++) {
            out[k] = Integer.parseInt(parts[k].trim());
            if (out[k] < 0 || out[k] >= SystemScorer.CUTS.length || (k > 0 && out[k] <= out[k - 1])) {
                throw new IllegalArgumentException("penetrations are 0 to " + (SystemScorer.CUTS.length - 1)
                        + ", each once, in order: not " + text);
            }
        }
        return out;
    }

    /**
     * Everything that makes one run's results what they are, a line each: every count's tags,
     * divisor, betting vector and form among them (CountKeys.Count.describe), with its betting
     * variant's, and the counts file it came from.
     */
    static String identity(String commit, Path states, int shoes, int size, int resamples, int threads,
                           List<CountKeys.Count> counts, int[] pens, String countsFile) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("FullIndexRun joint run\n");
        b.append("commit ").append(commit).append('\n');
        b.append(stateFile(states)).append('\n');
        b.append("shoes ").append(shoes).append(", valued states ").append(size).append('\n');
        b.append("resamples ").append(resamples).append(", seed ").append(Bootstrap.SEED).append(", M_play ")
                .append(SystemScorer.M_PLAY).append('\n');
        b.append("threads ").append(threads).append('\n');
        b.append("penetrations ").append(java.util.Arrays.toString(pens)).append('\n');
        if (countsFile != null) {
            b.append("counts from ").append(countsFile).append(", each also bet on its own value\n");
        }
        for (CountKeys.Count c : counts) {
            b.append("count ").append(c.describe()).append('\n');
            CountKeys.Count v = CountKeys.bettingVariant(c);
            if (v != null) {
                b.append("  with ").append(v.describe()).append('\n');
            }
        }
        return b.toString();
    }

    /**
     * Each row that plays, a count's and its betting variant's (a side count's or a betting vector's,
     * by its own name), at penetration p both ways and its gain from full play, flat and on the 1-8
     * same schedule. A row bet on its own value is a bound, not a count, and is left out.
     */
    static void summary(PrintStream out, FullIndexFit.Scored sc, SystemScorer scorer, int p) {
        int[] metrics = {SystemScorer.same(SystemScorer.SPREADS), SystemScorer.FLAT};
        String[] names = {"1-8 same schedule", "flat"};
        for (int k = 0; k < metrics.length; k++) {
            int m = metrics[k];
            out.printf("%n%s at %s, %% of a unit per round; the gain's SE with the later plays held, not yet "
                    + "widened (FULL_INDEX_PLAYS.md section 4):%n%n", names[k], SystemScorer.PENETRATIONS[p]);
            out.println("| Count | First decisions | Full play | Gain | its held SE |");
            out.println("|---|---|---|---|---|");
            for (SystemScorer.Result r : sc.results) {
                for (SystemScorer.Entry full : r.fullEntries) {
                    if (full.ownValue) {
                        continue;
                    }
                    SystemScorer.Entry first = null;
                    for (SystemScorer.Entry e : r.entries) {
                        if (!e.bettingOnly && !e.ownValue && e.count == full.count) {
                            first = e;
                        }
                    }
                    Bootstrap.Interval g = SystemScorer.gain(full, first, p, m, 1);
                    out.printf("| %s | %+.4f | %+.4f | %+.4f | %.4f |%n", first.name, 100 * first.estimate[p][m],
                            100 * full.estimate[p][m], 100 * g.estimate, 100 * g.se);
                }
            }
        }
    }
}
