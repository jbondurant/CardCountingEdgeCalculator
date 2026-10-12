import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The runs of the edge at each count (PLAN_COMPUTE.md 5.3) and its report (5.5).
 *
 *   KeyEdgesRun a STATES --published RESULTS --out DIR
 *   KeyEdgesRun a-prime STATES --published RESULTS --out DIR [--threads T]
 *   KeyEdgesRun report --a FILE --a-prime FILE --published RESULTS --out DIR [--rows FILE ...] [--full FILE ...]
 *
 * Run A scores the null count and every catalog count, with its betting variant, at first
 * decisions with no resamples and the state sink on, and keeps what the sink is handed. Before it
 * keeps anything it checks its figures against the full-index run c06c061's results.ser, the file
 * every published figure comes from: each first-decision and betting-only entry's estimate and
 * per-key table must be the stored one to the bit (check 1 of 5.4). It is the sink's first test on
 * the real file and comes before the long replay as a gate.
 *
 * Run A' scores the five counts the band ratios are measured on (Hi-Lo, KO, Zen, Hi-Opt II and
 * Victor APC with their ace side counts) at 80%, with the plays refitted on each of the 1,000
 * resamples and the resample sink on, and requires every figure of every resample to be
 * c06c061's within 1e-12 relative, so its refitted intervals come from the published bootstrap.
 *
 * The report reads what the runs kept and writes the JSON and CSV the site generator reads.
 * Nothing here reads a state file twice: the runs keep each state's facts with what they collect.
 * The registered counts (runs C and D) are scored in the page look (PLAN_COMPUTE.md 6.3), which
 * attaches a KeyEdges.Collector to its scorer as these runs do and saves it; and the catalog's
 * full play (run B) in the replay (6.1). Their files go to the report: further rows at first
 * decisions with --rows, full play with --full.
 */
final class KeyEdgesRun {

    private KeyEdgesRun() {
    }

    /** The five counts run A' refits outright, by a system each holds; the side-counted two are scored with their variants. */
    static final String[] REFITTED = {"Hi-Lo", "KO (Knock-Out)", "Zen Count", "Hi-Opt II",
            "Victor Advanced Point Count (Victor APC)"};
    /** The penetration run A' is made at: 80%. */
    static final int REFIT_PEN = 2;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 1) {
            usage();
        }
        String mode = args[0];
        Path states = null;
        Path published = null;
        Path out = null;
        Path a = null;
        Path aPrime = null;
        List<Path> full = new ArrayList<>();
        List<Path> more = new ArrayList<>();
        int threads = 1;
        int from = 1;
        if (mode.equals("a") || mode.equals("a-prime")) {
            if (args.length < 2) {
                usage();
            }
            states = Paths.get(args[1]);
            from = 2;
        }
        for (int k = from; k < args.length; k++) {
            switch (args[k]) {
                case "--published":
                    published = Paths.get(args[++k]);
                    break;
                case "--out":
                    out = Paths.get(args[++k]);
                    break;
                case "--threads":
                    threads = Integer.parseInt(args[++k]);
                    break;
                case "--a":
                    a = Paths.get(args[++k]);
                    break;
                case "--a-prime":
                    aPrime = Paths.get(args[++k]);
                    break;
                case "--full":
                    full.add(Paths.get(args[++k]));
                    break;
                case "--rows":
                    more.add(Paths.get(args[++k]));
                    break;
                default:
                    throw new IllegalArgumentException("unknown option " + args[k]);
            }
        }
        if (published == null || out == null) {
            usage();
        }
        Files.createDirectories(out);
        String commit = FullIndexRun.commit();
        switch (mode) {
            case "a":
                runA(states, published, out, commit, System.out);
                break;
            case "a-prime":
                runAPrime(states, published, out, commit, Math.max(1, threads), System.out);
                break;
            case "report":
                KeyEdgesReport.write(a, more, aPrime, full, published, out, commit, System.out);
                break;
            default:
                usage();
        }
    }

    private static void usage() {
        System.err.println("usage: KeyEdgesRun a STATES --published RESULTS --out DIR\n"
                + "       KeyEdgesRun a-prime STATES --published RESULTS --out DIR [--threads T]\n"
                + "       KeyEdgesRun report --a FILE --a-prime FILE --published RESULTS --out DIR [--rows FILE ...]"
                + " [--full FILE ...]");
        System.exit(2);
    }

    /** The states of a file dealt as this code deals, checked as ComparisonReport checks them. */
    static ScoringStates load(Path file) throws IOException {
        StateStore.Header header = StateStore.header(file);
        int shoes = StateStore.shoesDone(file);
        RoundRules rules = StateRun.montreal();
        String made = StateRun.describe(rules, BasicStrategy.chart());
        if (!header.rules.equals(made)) {
            throw new IllegalStateException(file + " was made under \"" + header.rules + "\"; this code plays \"" + made + "\"");
        }
        int widest = SystemScorer.CUTS[SystemScorer.CUTS.length - 1];
        if (header.cut < widest) {
            throw new IllegalStateException(file + " was dealt to cut " + header.cut + ", short of the " + widest + " scored");
        }
        return new ScoringStates(StateStore.readAll(file), shoes, rules);
    }

    /** The null count and every catalog count, in the order report-final lists them. */
    static List<CountKeys.Count> catalogRows() {
        List<CountKeys.Count> out = new ArrayList<>();
        out.add(CountKeys.nullCount());
        out.addAll(CountKeys.catalog());
        return out;
    }

    /** The stored Result of the count of this name in c06c061's file. */
    static SystemScorer.Result stored(CeilingReport.Published pub, String name) {
        for (SystemScorer.Result r : pub.results) {
            if (r.count.name.equals(name)) {
                return r;
            }
        }
        throw new IllegalStateException("c06c061's results hold no " + name);
    }

    static boolean sameBits(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            return a == b;
        }
        for (int k = 0; k < a.length; k++) {
            if (Double.doubleToLongBits(a[k]) != Double.doubleToLongBits(b[k])) {
                return false;
            }
        }
        return true;
    }

    static boolean sameBits(double[][] a, double[][] b) {
        if (a == null || b == null || a.length != b.length) {
            return a == b;
        }
        for (int k = 0; k < a.length; k++) {
            if (!sameBits(a[k], b[k])) {
                return false;
            }
        }
        return true;
    }

    /** The run's identity: the commit, the states, the stored rows, what ran. */
    static String identity(String commit, Path states, ScoringStates st, Path published, String what) throws IOException {
        return "KeyEdgesRun " + what + "\ncommit " + commit + "\n" + FullIndexRun.stateFile(states) + "\nshoes " + st.shoes
                + ", valued states " + st.size + "\npublished " + published.toAbsolutePath().normalize() + ", sha256 "
                + sha256(published) + "\nresamples " + Bootstrap.RESAMPLES + ", seed " + Bootstrap.SEED + "\n";
    }

    static String sha256(Path file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 20];
                for (int n; (n = in.read(buf)) > 0; ) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------- run A

    /** What a run collected, and every check it failed (none, or nothing is kept). */
    static final class Collected {
        final KeyEdges.Collector collector;
        final List<String> failures = new ArrayList<>();
        final List<String> notes = new ArrayList<>();

        Collected(KeyEdges.Collector collector) {
            this.collector = collector;
        }
    }

    /**
     * Run A on states st: every row of rows with its betting variant at every penetration, first
     * decisions, no resamples, the state sink on; then checks 1, 3 and 4 of 5.4 against the
     * stored Results (by count name).
     */
    static Collected scoreA(ScoringStates st, List<CountKeys.Count> rows, List<SystemScorer.Result> stored) {
        return scoreA(st, rows, stored, SystemScorer.M_PLAY);
    }

    /** The same with the play groups' M_play given, as a fixture's stored Results were scored with. */
    static Collected scoreA(ScoringStates st, List<CountKeys.Count> rows, List<SystemScorer.Result> stored,
                            double mPlay) {
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, 0, Bootstrap.SEED), mPlay);
        Collected c = new Collected(new KeyEdges.Collector(st, 0));
        sc.stateSink = c.collector;
        for (CountKeys.Count count : rows) {
            SystemScorer.Result r = sc.score(count, CountKeys.bettingVariant(count), null, null, 1);
            SystemScorer.Result was = null;
            for (SystemScorer.Result x : stored) {
                was = x.count.name.equals(count.name) ? x : was;
            }
            if (was == null) {
                c.failures.add(count.name + ": no stored Result to check it against");
                continue;
            }
            checkAgainst(r.entries, was.entries, c.failures);
        }
        checkVariants(c, rows);
        checkNull(c);
        return c;
    }

    /** Check 1: each entry's estimate and per-key table the stored one's to the bit, at every penetration. */
    static void checkAgainst(List<SystemScorer.Entry> now, List<SystemScorer.Entry> was, List<String> failures) {
        for (SystemScorer.Entry e : now) {
            SystemScorer.Entry w = null;
            for (SystemScorer.Entry x : was) {
                w = x.name.equals(e.name) ? x : w;
            }
            if (w == null) {
                failures.add(e.name + ": not among the stored entries");
                continue;
            }
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                if (!sameBits(e.estimate[p], w.estimate[p])) {
                    failures.add(e.name + " at " + SystemScorer.PENETRATIONS[p] + ": the estimate differs from the stored one");
                }
                if (!sameBits(e.keys[p], w.keys[p])) {
                    failures.add(e.name + " at " + SystemScorer.PENETRATIONS[p] + ": the key table differs from the stored one");
                }
            }
        }
    }

    /** Check 3: a betting variant's per-state values are its base count's; only its keys differ. */
    static void checkVariants(Collected c, List<CountKeys.Count> rows) {
        for (CountKeys.Count count : rows) {
            CountKeys.Count v = CountKeys.bettingVariant(count);
            if (v == null) {
                continue;
            }
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                if (!c.collector.has(count.name, p) || !c.collector.has(v.name, p)) {
                    continue;
                }
                KeyEdges.Values a = c.collector.values(count.name, p);
                KeyEdges.Values b = c.collector.values(v.name, p);
                if (!Arrays.equals(a.state, b.state) || !Arrays.equals(a.value, b.value)
                        || !Arrays.equals(a.insurance, b.insurance)) {
                    c.failures.add(v.name + " at " + SystemScorer.PENETRATIONS[p] + ": its values are not its base count's");
                }
            }
        }
    }

    /** Check 4: the null count has one key, whose edge is its flat figure. */
    static void checkNull(Collected c) {
        String name = CountKeys.nullCount().name;
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            if (!c.collector.has(name, p)) {
                continue;
            }
            KeyEdges.Table t = KeyEdges.table(c.collector.values(name, p));
            if (t.keys() != 1 || t.edge(0) != c.collector.entries.get(name).estimate[p][SystemScorer.FLAT]) {
                c.failures.add("the null count at " + SystemScorer.PENETRATIONS[p] + " has " + t.keys()
                        + " keys, or an edge other than its flat figure");
            }
        }
    }

    static void runA(Path states, Path published, Path out, String commit, PrintStream log) throws Exception {
        long t0 = System.nanoTime();
        CeilingReport.Published pub = CeilingReport.read(published);
        ScoringStates st = load(states);
        CeilingReport.requirePaired(pub, states, st, Bootstrap.RESAMPLES);
        log.printf("states read in %.1f s%n", (System.nanoTime() - t0) / 1e9);
        Collected c = scoreA(st, catalogRows(), pub.results);
        finish(c, identity(commit, states, st, published, "a"), out.resolve("a-" + commit + ".ser"), t0, log);
    }

    /** Writes what a run collected, only if it failed no check, and says so either way. */
    static void finish(Collected c, String identity, Path file, long t0, PrintStream log) throws IOException {
        for (String n : c.notes) {
            log.println(n);
        }
        if (!c.failures.isEmpty()) {
            log.println(c.failures.size() + " checks failed; nothing is kept:");
            for (String f : c.failures) {
                log.println("  " + f);
            }
            throw new IllegalStateException(c.failures.size() + " checks failed, the first: " + c.failures.get(0));
        }
        c.collector.save(file, identity);
        log.printf("every check passed; kept %s (%d bytes) in %.1f s%n", file, Files.size(file),
                (System.nanoTime() - t0) / 1e9);
    }

    // ------------------------------------------------------------------------ run A'

    /** The relative tolerance a resample's figure may differ from c06c061's by: the project's standard for a rerun. */
    static final double RERUN = 1e-12;

    /**
     * Run A' on states st: each of counts with its betting variant at penetration p alone, the
     * plays refitted on every resample of boot, both sinks on; then every figure of every
     * resample, and every estimate, checked against the stored Results: the same bits, or within
     * RERUN relative (reported, with the largest difference).
     */
    static Collected scoreAPrime(ScoringStates st, Bootstrap boot, List<CountKeys.Count> counts, int p,
                                 List<SystemScorer.Result> stored, int threads) {
        SystemScorer sc = new SystemScorer(st, boot, SystemScorer.M_PLAY);
        Collected c = new Collected(new KeyEdges.Collector(st, boot.resamples));
        sc.stateSink = c.collector;
        sc.resampleSink = c.collector;
        double largest = 0;
        int inexact = 0;
        for (CountKeys.Count count : counts) {
            SystemScorer.Result r = sc.score(count, CountKeys.bettingVariant(count), null, new int[]{p}, threads);
            SystemScorer.Result was = null;
            for (SystemScorer.Result x : stored) {
                was = x.count.name.equals(count.name) ? x : was;
            }
            if (was == null) {
                c.failures.add(count.name + ": no stored Result to check it against");
                continue;
            }
            for (SystemScorer.Entry e : r.entries) {
                SystemScorer.Entry w = null;
                for (SystemScorer.Entry x : was.entries) {
                    w = x.name.equals(e.name) ? x : w;
                }
                if (w == null || w.boot[p] == null || w.boot[p][0].length != boot.resamples) {
                    c.failures.add(e.name + ": no stored resamples of the same number to check against");
                    continue;
                }
                for (int m = 0; m < SystemScorer.METRICS; m++) {
                    for (int k = -1; k < boot.resamples; k++) {
                        double a = k < 0 ? e.estimate[p][m] : e.boot[p][m][k];
                        double b = k < 0 ? w.estimate[p][m] : w.boot[p][m][k];
                        if (Double.doubleToLongBits(a) == Double.doubleToLongBits(b) || a == b) {
                            continue;
                        }
                        double d = Math.abs(a - b) / Math.abs(b);
                        if (!(d <= RERUN)) {
                            c.failures.add(e.name + " metric " + m + (k < 0 ? " estimate" : " resample " + k) + ": " + a
                                    + ", stored " + b);
                            break;
                        }
                        inexact++;
                        largest = Math.max(largest, d);
                    }
                }
            }
        }
        c.notes.add(inexact == 0 ? "every refitted figure is c06c061's to the bit"
                : inexact + " refitted figures differ from c06c061's in the last bits, the largest by " + largest
                + " relative, within " + RERUN);
        return c;
    }

    /** The five counts of run A', as the catalog holds them. */
    static List<CountKeys.Count> refittedCounts() {
        List<CountKeys.Count> out = new ArrayList<>();
        for (String system : REFITTED) {
            CountKeys.Count found = null;
            for (CountKeys.Count c : CountKeys.catalog()) {
                found = c.systems.contains(system) ? c : found;
            }
            if (found == null) {
                throw new IllegalStateException("the catalog holds no " + system);
            }
            out.add(found);
        }
        return out;
    }

    static void runAPrime(Path states, Path published, Path out, String commit, int threads, PrintStream log)
            throws Exception {
        long t0 = System.nanoTime();
        CeilingReport.Published pub = CeilingReport.read(published);
        ScoringStates st = load(states);
        CeilingReport.requirePaired(pub, states, st, Bootstrap.RESAMPLES);
        log.printf("states read in %.1f s%n", (System.nanoTime() - t0) / 1e9);
        Bootstrap boot = new Bootstrap(st.shoes, Bootstrap.RESAMPLES, Bootstrap.SEED);
        Collected c = scoreAPrime(st, boot, refittedCounts(), REFIT_PEN, pub.results, threads);
        finish(c, identity(commit, states, st, published, "a-prime"), out.resolve("a-prime-" + commit + ".ser"), t0, log);
    }
}
