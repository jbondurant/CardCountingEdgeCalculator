import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The cap on what the runs folder holds, everything in it counted (Justin, 18 October 2026: "can we
 * change code so that it generates perhaps like 5gb max of content at any time?"). By then runs/
 * held 23 GB, 14.9 GB of it one tree store kept only to save time.
 *
 * The cap is a budget for the folder named runs that holds a run's output (root): every regular
 * file under it, links not followed, at its size. Each run that writes much checks it before it
 * writes anything (require), with what it expects to write; a run that would take the folder past
 * the budget is refused, naming the folder's largest items, and writes nothing. A full-index run's
 * tree store, the one item that only saves time, takes whatever room is left once the run's own
 * files are counted (FullIndexRun.treesToKeep); with none left the trees are built every round, as
 * --trees none builds them, to the same bits (TreeStoreTest). Output outside any runs folder is
 * not counted: a test's temporary directory, or a path given on purpose.
 *
 * The budget is BUDGET_GB unless a run is given --disk-budget GB, or the environment variable
 * CCEC_DISK_BUDGET_GB is set. BUDGET_GB is 9, not the 5 asked for, because what the page is built
 * and reproduced from holds 6.38 GB on its own, the two state files 2.68 GB of it (main --without,
 * 18 October 2026, with the tree store, the replays' records and the superseded files left out),
 * and the largest run made, a replay of every item of the main run with its three hooks, writes
 * 1.88 GB while it runs (estimated 2.33 GB below). With the room 9 GB leaves, a rerun of the main
 * run keeps few trees, and builds the others in every round: about half an hour more a round on
 * six threads (TreeStore).
 *
 * Planted on 18 October 2026 in a scratch copy, one at a time, each failed the test named, which
 * passes without it: the store writing every tree, and not cutting a store opened with a smaller
 * keep (TreeStoreTest#aStoreKeeps...); the run keeping every tree, and not checking the budget
 * (FullIndexRunTest#theRunsFoldersBudget...); the records kept, a resume taken into a directory
 * whose records were let go, and the replay not checking the budget
 * (FullIndexReplayTest#theCommandLineLets...); a link's target counted, and the store counted
 * though excluded (DiskBudgetTest).
 *
 * What the runs write is estimated from what they wrote: a full-index run 5.6 MB a scoring item
 * (c06c061: 1.05 GB for 186 items) and a custom-count run 8.9 MB (97bf834: 0.27 GB for 30), so
 * ITEM is 10 MB; a replay's record 10.0 MB an item, its wide plans 2.7 MB and its sink 7.6 MB
 * (c06c061-0e0920d: 0.93, 0.25 and 0.71 GB for 93 items), so REPLAY_ITEM is 25 MB. Each measured
 * with main --without.
 */
final class DiskBudget {

    private DiskBudget() {
    }

    static final double BUDGET_GB = 9;
    static final String ENV = "CCEC_DISK_BUDGET_GB";
    /** What a full-index run writes for each scoring item (a count at a penetration, its folds or its halves). */
    static final long ITEM = 10_000_000L;
    /** What a replay writes for each item, its record and every hook's share. */
    static final long REPLAY_ITEM = 25_000_000L;

    /** The budget in bytes: given (GB, or null), else the environment's, else BUDGET_GB. */
    static long budget(String given) {
        String gb = given != null ? given : System.getenv(ENV);
        double v = gb == null || gb.isEmpty() ? BUDGET_GB : Double.parseDouble(gb);
        if (!(v >= 0) || Double.isInfinite(v)) {
            throw new IllegalArgumentException("a disk budget is a number of GB, 0 or more: not " + gb);
        }
        return (long) (v * 1e9);
    }

    /** The folder named runs that holds p, p itself if so named, or null if none does. */
    static Path root(Path p) {
        for (Path q = p.toAbsolutePath().normalize(); q != null; q = q.getParent()) {
            if (q.getFileName() != null && q.getFileName().toString().equals("runs")) {
                return q;
            }
        }
        return null;
    }

    /** The bytes of every regular file under p (p itself if a file), links not followed; 0 if nothing is there. */
    static long bytes(Path p) {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(p)) {
            return s.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)).mapToLong(f -> {
                try {
                    return Files.size(f);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).sum();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What the budget leaves for out's runs folder beyond what it holds; Long.MAX_VALUE outside any. */
    static long room(Path out, long budget) {
        Path root = root(out);
        return root == null ? Long.MAX_VALUE : budget - bytes(root);
    }

    /**
     * Refuses, before anything is written, a run that would write about more bytes into out's runs
     * folder and take it past the budget, naming the folder's largest items; does nothing outside
     * any runs folder.
     */
    static void require(Path out, long more, long budget, String what) {
        require(out, more, budget, what, null);
    }

    /**
     * The same, not counting what is at excluded, when it is in the same runs folder: a tree store
     * the run is about to cut to the room it has (FullIndexRun.treesToKeep).
     */
    static void require(Path out, long more, long budget, String what, Path excluded) {
        Path root = root(out);
        if (root == null) {
            return;
        }
        long held = bytes(root) - inside(root, excluded);
        if (held + more > budget) {
            throw new IllegalStateException(String.format("%s would write about %.2f GB into %s, which holds %.2f GB of "
                    + "its %.2f GB budget (DiskBudget). Nothing is written. Its largest items:%n%s%nDelete what is no "
                    + "longer needed, or give this run more with --disk-budget GB or %s.", what, more / 1e9, root,
                    held / 1e9, budget / 1e9, largest(root, 8), ENV));
        }
    }

    /** The bytes at p if p is under root, else 0. */
    static long inside(Path root, Path p) {
        return p != null && root != null && p.toAbsolutePath().normalize().startsWith(root) ? bytes(p) : 0;
    }

    /** A folder holding more than this is listed by what is in it (largest). */
    static final long OPEN = 1_000_000_000L;

    /** The n largest items under root: a folder by what it holds, or by its items where it holds more than OPEN. */
    static String largest(Path root, int n) {
        List<Object[]> items = new ArrayList<>();
        try (Stream<Path> top = Files.list(root)) {
            for (Path p : (Iterable<Path>) top::iterator) {
                items(p, items);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        items.sort((a, b) -> Long.compare((Long) b[1], (Long) a[1]));
        StringBuilder s = new StringBuilder();
        for (int k = 0; k < Math.min(n, items.size()); k++) {
            s.append(String.format("  %8.2f GB  %s%n", (Long) items.get(k)[1] / 1e9, root.relativize((Path) items.get(k)[0])));
        }
        return s.toString();
    }

    private static void items(Path p, List<Object[]> into) throws IOException {
        long b = bytes(p);
        if (b > OPEN && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> inner = Files.list(p)) {
                for (Path q : (Iterable<Path>) inner::iterator) {
                    items(q, into);
                }
            }
        } else {
            into.add(new Object[]{p, b});
        }
    }

    /**
     * java DiskBudget [RUNS_DIR] [--without LIST]: what the runs folder holds against the budget, and
     * its largest items; with LIST, a file naming paths one a line (absolute, or relative to the
     * folder; a tab and anything after it ignored, as a listing of sizes and paths gives them the
     * other way round), what those hold and what the folder would hold without them. Only reads.
     */
    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args.length > 0 && !args[0].startsWith("--") ? args[0] : "runs").toAbsolutePath().normalize();
        Path list = null;
        for (int a = 0; a < args.length; a++) {
            if (args[a].equals("--without")) {
                list = Paths.get(args[++a]);
            }
        }
        long budget = budget(null);
        long held = bytes(root);
        System.out.printf("%s holds %.2f GB; the budget is %.2f GB (%s), %s.%n%s", root, held / 1e9, budget / 1e9,
                System.getenv(ENV) != null ? ENV : "BUDGET_GB", held > budget
                        ? String.format("%.2f GB over: every run that writes much is refused", (held - budget) / 1e9)
                        : String.format("%.2f GB left", (budget - held) / 1e9), largest(root, 15));
        if (list != null) {
            java.util.Set<Path> paths = new java.util.LinkedHashSet<>();
            for (String line : Files.readAllLines(list)) {
                String[] cols = line.split("\t");
                String name = cols[cols.length - 1].trim();
                if (!name.isEmpty()) {
                    paths.add(root.resolve(name).normalize());
                }
            }
            long without = 0;
            int missing = 0;
            for (Path p : paths) {
                if (!p.startsWith(root)) {
                    throw new IllegalArgumentException(p + " is not in " + root);
                }
                if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                    missing++;
                }
                without += bytes(p);
            }
            System.out.printf("%nThe %d paths of %s hold %.3f GB (%d not there; a path inside another listed is counted "
                    + "twice). Without them %s would hold %.2f GB, %s.%n", paths.size(), list, without / 1e9, missing,
                    root.getFileName(), (held - without) / 1e9, held - without > budget
                            ? String.format("%.2f GB over the budget", (held - without - budget) / 1e9)
                            : String.format("%.2f GB under the budget", (budget - held + without) / 1e9));
        }
    }
}
