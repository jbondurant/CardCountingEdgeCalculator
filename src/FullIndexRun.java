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
 * java -Xmx10g -cp CLASSES:lib/mongo-java-driver-3.12.14.jar FullIndexRun --states FILE --out DIR
 * [--shoes N] [--resamples R] [--threads T] [--trees PATH | --trees none] [--resume]
 */
public final class FullIndexRun {

    static final int[] PENS = {0, 1, 2};
    /** What must be left free on disk beside the trees. */
    static final long DISK_MARGIN = 3_000_000_000L;

    public static void main(String[] args) throws Exception {
        Path states = null;
        Path out = null;
        Path treeFile = null;
        boolean noTrees = false;
        int shoes = -1;
        int resamples = 1000;
        int threads = 6;
        boolean resume = false;
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
                case "--resume":
                    resume = true;
                    break;
                default:
                    throw new IllegalArgumentException("usage: FullIndexRun --states FILE --out DIR [--shoes N] "
                            + "[--resamples R] [--threads T] [--trees PATH | --trees none] [--resume]; not " + args[a]);
            }
        }
        if (states == null || out == null) {
            throw new IllegalArgumentException("--states and --out are needed");
        }
        if (treeFile == null && !noTrees) {
            treeFile = out.resolve("trees.bin");
        }
        String commit = commit();
        PrintStream log = System.out;
        long t0 = System.nanoTime();
        int fileShoes = StateStore.shoesDone(states);
        if (shoes < 0) {
            shoes = fileShoes;
        }
        ScoringStates st = FullIndexPilot.load(states, shoes);
        SystemScorer scorer = new SystemScorer(st, new Bootstrap(shoes, resamples, Bootstrap.SEED), SystemScorer.M_PLAY);
        List<CountKeys.Count> counts = new ArrayList<>();
        counts.add(CountKeys.nullCount());
        counts.addAll(CountKeys.catalog());
        String identity = identity(commit, states, shoes, st.size, resamples, threads, counts);
        log.printf("FullIndexRun %s%s: %d shoes, %d valued states of %s, read and never written; %d counts at %d "
                        + "penetrations; %d resamples; %d threads; max heap %.0f MB; trees %s; checkpoints in %s.%n",
                commit, resume ? ", resumed" : "", shoes, st.size, states, counts.size(), PENS.length, resamples, threads,
                Runtime.getRuntime().maxMemory() / 1e6, noTrees ? "built every round" : "kept in " + treeFile, out);
        log.printf("Started %s, load average %.2f; states read in %.1f s.%n", java.time.LocalDateTime.now(),
                FullIndexTimingReport.loadAverage(), (System.nanoTime() - t0) / 1e9);
        log.flush();

        Files.createDirectories(out);
        FullIndexCheckpoint ck = new FullIndexCheckpoint(out, identity);
        // Checked first, so a refused resume leaves the trees as they are.
        ck.open(resume);
        TreeStore store = noTrees ? null
                : new TreeStore(st, treeFile, commit + "\n" + stateFile(states) + "\nstates " + st.size, DISK_MARGIN);
        FullIndexFit.JointRun run = new FullIndexFit.JointRun(scorer, counts, PENS, threads);
        run.trees = store != null ? store : FullIndexFit.built(st);
        run.checkpoint = ck;
        run.resume = resume;
        run.log = log;
        if (store != null) {
            run.beforeCheckpoint = store::sync;
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
        summary(log, sc, scorer);
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

    /** Everything that makes one run's results what they are, a line each. */
    static String identity(String commit, Path states, int shoes, int size, int resamples, int threads,
                           List<CountKeys.Count> counts) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("FullIndexRun joint run\n");
        b.append("commit ").append(commit).append('\n');
        b.append(stateFile(states)).append('\n');
        b.append("shoes ").append(shoes).append(", valued states ").append(size).append('\n');
        b.append("resamples ").append(resamples).append(", seed ").append(Bootstrap.SEED).append(", M_play ")
                .append(SystemScorer.M_PLAY).append('\n');
        b.append("threads ").append(threads).append('\n');
        b.append("penetrations ").append(java.util.Arrays.toString(PENS)).append('\n');
        for (CountKeys.Count c : counts) {
            b.append("count ").append(c.name).append('\n');
        }
        return b.toString();
    }

    /** Each count's EV at 80% both ways and its gain from full play, flat and on the 1-8 same schedule. */
    static void summary(PrintStream out, FullIndexFit.Scored sc, SystemScorer scorer) {
        int p = 2;
        int[] metrics = {SystemScorer.same(SystemScorer.SPREADS), SystemScorer.FLAT};
        String[] names = {"1-8 same schedule", "flat"};
        for (int k = 0; k < metrics.length; k++) {
            int m = metrics[k];
            out.printf("%n%s at 80%%, %% of a unit per round; the gain's SE with the later plays held, not yet "
                    + "widened (FULL_INDEX_PLAYS.md section 4):%n%n", names[k]);
            out.println("| Count | First decisions | Full play | Gain | its held SE |");
            out.println("|---|---|---|---|---|");
            for (SystemScorer.Result r : sc.results) {
                SystemScorer.Entry first = r.entries.get(0);
                SystemScorer.Entry full = r.fullEntries.get(0);
                Bootstrap.Interval g = SystemScorer.gain(full, first, p, m, 1);
                out.printf("| %s | %+.4f | %+.4f | %+.4f | %.4f |%n", r.count.name, 100 * first.estimate[p][m],
                        100 * full.estimate[p][m], 100 * g.estimate, 100 * g.se);
            }
        }
    }
}
