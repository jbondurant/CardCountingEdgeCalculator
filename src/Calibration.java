import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step 3 of COUNT_DESIGN.md (section 8): the calibration and the theory pass, on the twin file
 * alone, as one command that writes one report. Every figure in it comes from the committed tools
 * it calls: SystemScorer.estimate and score, CountKeys.atGrain, CountSearch's objectives,
 * CountRegression, EffectsOfRemoval and SplitMargin.
 *
 * In order, each a section of the report:
 * - the twin file: its header seed, that it reads back whole, the committed seed-collision check
 *   against the main file (CustomCounts.seedCollisions), and from its log the rate over the first
 *   hour;
 * - the proxy check: every published row's 80%, 1-8 point estimate on the twin, as a difference
 *   from Hi-Lo playing the same way, beside the same difference stored by the full-index run
 *   (results.ser, whose rows print report-final's headline to the digit, CeilingReport.checkPrinted)
 *   in units of sqrt(2) times its paired SE there; about 95% should lie within 2;
 * - the grain ladder: Hi-Lo and Zen at each grain of LADDER;
 * - the noise floor at the design's default grain, 1/20: Zen's direction against directions 1, 2,
 *   5, 10 and 30 degrees away, the paired bootstrap SE and the spread over disjoint sub-runs scaled
 *   to the full size (as ComparisonReport --calibrate scores its sub-runs), and the smaller;
 * - g: the coarsest grain of the ladder past which neither Hi-Lo nor Zen improves by more than the
 *   noise floor at 1 degree (grain); the running divisor D_r, one key step g Hi-Lo true-count units
 *   at the median depth of the 80% states; and the noise floor again at g if g is not 1/20;
 * - one evaluation timed single-threaded, as the search makes it (CountSearch's objectives), F2's
 *   and the betting block's too, and the speed of a generation on the search's threads; from them
 *   the search budget of section 7 in machine hours;
 * - every published row at g, each catalog count at g (a running one at D_r) with its side-count
 *   variant, beside its own grain on the twin; the counts behind the five best rows at g, and
 *   Hi-Lo, are the controls;
 * - the colour check: Red Seven at g against its rank average, the seven at the average of its two
 *   kinds, both running at D_r, paired over the resamples;
 * - the theory pass (CountRegression): R, the betting start, the depth figure, the insurance check,
 *   the playing start, the later-play direction;
 * - the effects of removal off the top (EffectsOfRemoval);
 * - the split margin (SplitMargin) on a sample of the twin's states sized to take under an hour: a
 *   lower bound, since its ascent stops at a local optimum.
 * A summary of the figures the search and the design take comes first.
 *
 * Each section is kept, once done, in OUT.parts beside the report with the figures later sections
 * read, so a calibration that is stopped resumes where it stopped when run again with the same
 * classes, state file and settings (the parts' identity), and refuses parts of any other. The
 * custom counts it scores (the noise floor's directions, the colour check's rank average), the
 * theory pass's starts and the controls are written to OUT.candidates.json and read back, so the
 * counts that file lists are the counts scored, to the bit. It refuses the main state file
 * (CustomCounts.requireTwin), and any file whose header seed is not the twin's, and writes nothing over
 * an existing report.
 *
 * Run from classes frozen from one commit with commit.txt and counting-systems.json beside them:
 * java -Xmx10g -cp CLASSES:lib/mongo-java-driver-3.12.14.jar Calibration --states TWIN --out REPORT.md
 *   --published runs/full-index/full-c06c061/results.ser [--printed runs/report-final-26f0724.md]
 *   [--twin-log runs/twin-80.log] [--threads 7] [--search-threads 6] [--resamples 100] [--sub-runs 8]
 *   [--directions 6] [--split-states N] [--split-minutes 50] [--later-shoes 2000] [--eor-k 4] [--repeats 5]
 */
final class Calibration {

    /** The grains tried for g, coarse to fine, as key divisors D = g K: g from 1 to 1/200. */
    static final int[] LADDER = {10_000, 5_000, 2_500, 1_000, 500, 250, 100, 50};
    /** The design's default grain, g = 1/20 (section 1): the noise floor is measured there first. */
    static final int DEFAULT_DIVISOR = 500;
    /** The angles the noise floor is measured at, in degrees (section 4). */
    static final double[] ANGLES = {1, 2, 5, 10, 30};
    static final int P80 = SystemScorer.CUTS.length - 1;
    static final int SAME8 = SystemScorer.same(SystemScorer.SPREADS);
    static final int FLAT = SystemScorer.FLAT;
    /** P(|Z| > 2) for a standard normal: the share of the proxy check's rows expected outside 2. */
    static final double OUTSIDE_TWO = 0.04550026389635842;
    /** The proxy check fails when so few rows lie within 2 that chance gives it under this. */
    static final double PROXY_ALPHA = 0.01;
    static final String HI_LO = "Hi-Lo";
    static final String ZEN = "Zen Count";
    static final String RED_SEVEN = "Red Seven (Red 7)";

    /** The settings of one calibration. */
    static final class Settings {
        Path states;
        Path out;
        /** A full-index run's results.ser on the main file, for the proxy check. */
        Path published;
        /** The published report its rows must print (CeilingReport.checkPrinted); none to skip the tie. */
        Path printed;
        /** The twin's StateRun log, for the rate over its first hour; none to leave it out. */
        Path twinLog;
        int threads = 7;
        /** The threads a search will run its generations on, for the speed of a generation. */
        int searchThreads = 6;
        int resamples = 100;
        int subRuns = 8;
        /** Directions at each angle of the noise floor. */
        int directions = 6;
        long directionSeed = CustomCounts.TWIN_SEED;
        /** States in the split margin's sample; -1 to size it from a pilot to take splitMinutes. */
        int splitStates = -1;
        double splitMinutes = 50;
        /** The pair deals the split margin looks at; null for every one (a test's shortcut). */
        int[] splitDeals;
        int laterShoes = 2000;
        int eorK = 4;
        /** Timed evaluations of each kind, after two untimed. */
        int repeats = 5;

        /** Every setting that sets a figure of the report, for the parts' identity. */
        String text() {
            return "published " + published + ", printed " + printed + ", twin log " + twinLog + ", resamples "
                    + resamples + ", sub-runs " + subRuns + ", directions " + directions + " from seed " + directionSeed
                    + ", split states " + (splitStates < 0 ? "sized to " + splitMinutes + " min" : splitStates)
                    + ", split deals " + (splitDeals == null ? "all" : Arrays.toString(splitDeals)) + ", later shoes "
                    + laterShoes + ", effects of removal k " + eorK + ", threads " + threads + ", search threads "
                    + searchThreads + ", repeats " + repeats;
        }
    }

    /** A finished section: its text, and the figures later sections and the summary read. */
    static final class Part {
        final String text;
        final Properties values;

        Part(String text, Properties values) {
            this.text = text;
            this.values = values;
        }

        double d(String key) {
            String v = values.getProperty(key);
            if (v == null) {
                throw new IllegalStateException("no figure " + key);
            }
            return Double.parseDouble(v);
        }

        int i(String key) {
            return (int) Math.rint(d(key));
        }

        String s(String key) {
            return values.getProperty(key);
        }
    }

    /** A section's work. */
    interface Work {
        Part run() throws Exception;
    }

    /** Text written as a report section is, with its figures. */
    static final class Text {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final PrintStream out;
        final Properties values = new Properties();

        Text() {
            try {
                out = new PrintStream(bytes, true, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }

        Text printf(String format, Object... args) {
            out.printf(Locale.ROOT, format, args);
            return this;
        }

        Text println(String line) {
            out.println(line);
            return this;
        }

        void put(String key, double v) {
            values.setProperty(key, Double.toString(v));
        }

        void put(String key, String v) {
            values.setProperty(key, v);
        }

        Part part() {
            return new Part(new String(bytes.toByteArray(), StandardCharsets.UTF_8), values);
        }
    }

    final Settings s;
    final String commit;
    final PrintStream log;
    final Path parts;
    final String identity;
    private ScoringStates st;
    private SystemScorer estimates;
    private SystemScorer resampled;

    Calibration(Settings s, String commit, PrintStream log) throws IOException {
        this.s = s;
        this.commit = commit;
        this.log = log;
        this.parts = s.out.resolveSibling(s.out.getFileName() + ".parts");
        this.identity = "Calibration " + commit + "\n" + FullIndexRun.stateFile(s.states) + "\n" + s.text() + "\n";
    }

    // ------------------------------------------------------------------ the command

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        run(settings(args), FullIndexRun.commit(), System.err);
    }

    static Settings settings(String[] args) {
        Settings s = new Settings();
        for (int a = 0; a < args.length; a++) {
            switch (args[a]) {
                case "--states": s.states = Paths.get(args[++a]); break;
                case "--out": s.out = Paths.get(args[++a]); break;
                case "--published": s.published = Paths.get(args[++a]); break;
                case "--printed": s.printed = Paths.get(args[++a]); break;
                case "--twin-log": s.twinLog = Paths.get(args[++a]); break;
                case "--threads": s.threads = Integer.parseInt(args[++a]); break;
                case "--search-threads": s.searchThreads = Integer.parseInt(args[++a]); break;
                case "--resamples": s.resamples = Integer.parseInt(args[++a]); break;
                case "--sub-runs": s.subRuns = Integer.parseInt(args[++a]); break;
                case "--directions": s.directions = Integer.parseInt(args[++a]); break;
                case "--split-states": s.splitStates = Integer.parseInt(args[++a]); break;
                case "--split-minutes": s.splitMinutes = Double.parseDouble(args[++a]); break;
                case "--later-shoes": s.laterShoes = Integer.parseInt(args[++a]); break;
                case "--eor-k": s.eorK = Integer.parseInt(args[++a]); break;
                case "--repeats": s.repeats = Integer.parseInt(args[++a]); break;
                default: throw new IllegalArgumentException("unknown option " + args[a]);
            }
        }
        if (s.states == null || s.out == null || s.published == null) {
            throw new IllegalArgumentException("--states, --out and --published are needed");
        }
        if (s.threads < 1 || s.searchThreads < 1 || s.resamples < 2 || s.subRuns < 2 || s.directions < 1
                || s.repeats < 1) {
            throw new IllegalArgumentException("threads, search threads, directions and repeats at least 1; resamples "
                    + "and sub-runs at least 2");
        }
        return s;
    }

    /** The calibration, from the parts already done if it was stopped. Writes the report and the candidates file. */
    static void run(Settings s, String commit, PrintStream log) throws Exception {
        CustomCounts.requireTwin(s.states);
        long seed = StateStore.header(s.states).seed;
        if (seed != CustomCounts.TWIN_SEED) {
            throw new IllegalStateException(s.states + " has header seed " + seed + ", not the twin's, "
                    + CustomCounts.TWIN_SEED + " (COUNT_DESIGN.md, section 4)");
        }
        if (Files.exists(s.out)) {
            throw new IllegalStateException(s.out + " exists; write the calibration to a new file");
        }
        Calibration c = new Calibration(s, commit, log);
        c.openParts();
        c.all();
    }

    /** The parts directory: made with the identity, or, holding parts already, checked against it. */
    void openParts() throws IOException {
        Path id = parts.resolve("identity.txt");
        if (Files.isDirectory(parts)) {
            String was = Files.exists(id) ? new String(Files.readAllBytes(id), StandardCharsets.UTF_8) : "";
            if (!was.equals(identity)) {
                throw new IllegalStateException(parts + " holds parts of another calibration: it was\n" + was
                        + "this is\n" + identity + "Name another --out, or move those parts aside.");
            }
            log("resuming from %s", parts);
            return;
        }
        Files.createDirectories(parts);
        write(id, identity);
    }

    void all() throws Exception {
        Part twin = part("01-twin", this::twin);
        Part proxy = part("02-proxy", this::proxy);
        Part ladder = part("03-ladder", this::ladder);
        Part noise0 = part("04-noise-" + DEFAULT_DIVISOR, () -> noise(DEFAULT_DIVISOR));
        Part grain = part("05-grain", () -> grain(ladder, noise0));
        int d = grain.i("divisor");
        int dr = grain.i("runningDivisor");
        Part noise = d == DEFAULT_DIVISOR ? noise0 : part("06-noise-" + d, () -> noise(d));
        Part timing = part("07-timing", () -> timing(d, dr));
        Part atG = part("08-published-at-g", () -> publishedAtGrain(d, dr, proxy));
        Part colour = part("09-colour", () -> colour(dr, noise));
        Part theory = part("10-theory", this::theory);
        Part eor = part("11-effects-of-removal", () -> effectsOfRemoval(theory));
        Part split = part("12-split-margin", this::splitMargin);

        Path candidates = candidatesFile(s.out);
        String listing = candidates(d, dr, noise0, noise, atG, theory, eor);
        if (!Files.exists(candidates)) {
            write(candidates, listing);
        } else if (!new String(Files.readAllBytes(candidates), StandardCharsets.UTF_8).equals(listing)) {
            throw new IllegalStateException(candidates + " exists and lists other counts");
        }
        checkCandidates(candidates, d, dr);

        StringBuilder b = new StringBuilder();
        b.append(header(twin));
        b.append(summary(twin, proxy, grain, noise0, noise, timing, atG, colour, theory, eor, split, candidates));
        for (Part p : Arrays.asList(twin, proxy, ladder, noise0, grain)) {
            b.append(p.text);
        }
        if (noise != noise0) {
            b.append(noise.text);
        }
        for (Part p : Arrays.asList(timing, atG, colour, theory, eor, split)) {
            b.append(p.text);
        }
        write(s.out, b.toString());
        log("wrote %s and %s", s.out, candidates);
    }

    /** A section, read back from the parts if done, otherwise worked out and kept there. */
    Part part(String name, Work w) throws Exception {
        Path md = parts.resolve(name + ".md");
        Path pr = parts.resolve(name + ".properties");
        if (Files.exists(md) && Files.exists(pr)) {
            Properties v = new Properties();
            try (Reader r = Files.newBufferedReader(pr, StandardCharsets.UTF_8)) {
                v.load(r);
            }
            log("%s: done before, read back", name);
            return new Part(new String(Files.readAllBytes(md), StandardCharsets.UTF_8), v);
        }
        log("%s: started", name);
        long t0 = System.nanoTime();
        Part p = w.run();
        p.values.setProperty("seconds", Double.toString((System.nanoTime() - t0) / 1e9));
        Path tmp = parts.resolve(name + ".properties.tmp");
        try (Writer o = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.values.store(o, name);
        }
        Files.move(tmp, pr, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        write(md, p.text);
        log("%s: done in %.0f s", name, (System.nanoTime() - t0) / 1e9);
        return p;
    }

    /** Writes a file whole: to a sibling first, then moved into place. */
    static void write(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    void log(String format, Object... args) {
        log.printf(Locale.ROOT, "%s %s%n", java.time.LocalDateTime.now().withNano(0), String.format(Locale.ROOT, format,
                args));
        log.flush();
    }

    static Path candidatesFile(Path out) {
        String name = out.getFileName().toString();
        return out.resolveSibling((name.endsWith(".md") ? name.substring(0, name.length() - 3) : name)
                + ".candidates.json");
    }

    // ------------------------------------------------------------------ the states and scorers

    synchronized ScoringStates st() throws IOException {
        if (st == null) {
            long t0 = System.nanoTime();
            st = FullIndexPilot.load(s.states, StateStore.shoesDone(s.states));
            log("read %d valued states of %d shoes in %.0f s", st.size, st.shoes, (System.nanoTime() - t0) / 1e9);
        }
        return st;
    }

    /** The scorer of one evaluation: no resamples. */
    synchronized SystemScorer estimates() throws IOException {
        if (estimates == null) {
            estimates = new SystemScorer(st(), new Bootstrap(st().shoes, 0, Bootstrap.SEED), SystemScorer.M_PLAY);
        }
        return estimates;
    }

    /** The scorer of the paired bootstrap: the settings' resamples from the comparison's seed. */
    synchronized SystemScorer resampled() throws IOException {
        if (resampled == null) {
            resampled = new SystemScorer(st(), new Bootstrap(st().shoes, s.resamples, Bootstrap.SEED),
                    SystemScorer.M_PLAY);
        }
        return resampled;
    }

    /** The jobs on threads threads, their results in order. */
    static <T> List<T> parallel(int threads, List<Callable<T>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "calibration");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> j : jobs) {
                futures.add(pool.submit(j));
            }
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Each count's estimate at 80% with its betting variant (CountKeys.bettingVariant): [count][entry][metric]. */
    List<double[][]> estimateAll(SystemScorer sc, List<CountKeys.Count> counts) throws Exception {
        List<Callable<double[][]>> jobs = new ArrayList<>();
        for (CountKeys.Count c : counts) {
            jobs.add(() -> sc.estimate(c, CountKeys.bettingVariant(c), P80));
        }
        return parallel(s.threads, jobs);
    }

    /** Each count's 1-8 same-schedule figure at 80% playing its own fit: its estimate, then its resamples. */
    List<double[]> resampleAll(List<CountKeys.Count> counts) throws Exception {
        SystemScorer sc = resampled();
        List<Callable<double[]>> jobs = new ArrayList<>();
        for (CountKeys.Count c : counts) {
            jobs.add(() -> {
                SystemScorer.Entry e = sc.score(c, null, null, new int[]{P80}, 1).entries.get(0);
                double[] out = new double[1 + s.resamples];
                out[0] = e.estimate[P80][SAME8];
                System.arraycopy(e.boot[P80][SAME8], 0, out, 1, s.resamples);
                log("  resampled %s", c.name);
                return out;
            });
        }
        return parallel(s.threads, jobs);
    }

    // ------------------------------------------------------------------ the twin file

    /** "shoe N done: S states valued this run, of R rounds dealt; T s, X s per valued state", as StateRun logs it. */
    static final Pattern SHOE_LINE = Pattern.compile(
            "shoe (\\d+) done: (\\d+) states valued this run, of (\\d+) rounds dealt; (\\d+) s, ([0-9.]+) s per valued state");

    /**
     * From a StateRun log, the first run's (a resumed run starts its clock again): {shoes, states
     * valued, seconds} at its last line within the first hour, and at its last line.
     */
    static long[][] logRate(List<String> lines) {
        long[] hour = null;
        long[] last = null;
        boolean started = false;
        for (String l : lines) {
            if (l.contains(": shoes ") && l.contains(" threads")) {
                if (started) {
                    break;
                }
                started = true;
                continue;
            }
            Matcher m = SHOE_LINE.matcher(l);
            if (m.matches()) {
                long[] x = {Long.parseLong(m.group(1)) + 1, Long.parseLong(m.group(2)), Long.parseLong(m.group(4))};
                if (x[2] <= 3600) {
                    hour = x;
                }
                last = x;
            }
        }
        return new long[][]{hour, last};
    }

    Part twin() throws Exception {
        Text t = new Text();
        StateStore.Header h = StateStore.header(s.states);
        int shoes = StateStore.shoesDone(s.states);
        int records = StateStore.records(s.states);
        ScoringStates states = st();
        int collisions = CustomCounts.seedCollisions(CustomCounts.MAIN_SEED, h.seed, shoes);
        t.println("## The twin file");
        t.println("");
        t.printf("- %s.%n", FullIndexRun.stateFile(s.states));
        t.printf("- Header seed %d (%s), q0 %s, u0 %s, cut %d; dealt under the rules and chart this code plays.%n", h.seed,
                h.seed == CustomCounts.TWIN_SEED ? "the twin's, as section 4 sets it" : "NOT the twin's seed, "
                        + CustomCounts.TWIN_SEED, h.q0, h.u0, h.cut);
        t.printf("- %d shoes done, %d records; all %d read back as valued states (FullIndexPilot.load)%s.%n", shoes,
                records, states.size, states.size == records ? "" : " -- FEWER THAN THE RECORDS");
        t.printf("- The seed-collision check (CustomCounts.seedCollisions): of the twin's %d shoes' generator seeds "
                + "(ShoeRun.mix, the cards' and the valuation's streams), %d equal one of the main file's (seed %d) "
                + "over as many shoes.%n", shoes, collisions, CustomCounts.MAIN_SEED);
        t.put("seed", h.seed);
        t.put("shoes", shoes);
        t.put("records", records);
        t.put("valued", states.size);
        t.put("collisions", collisions);
        if (s.twinLog != null) {
            long[][] r = logRate(Files.readAllLines(s.twinLog, StandardCharsets.UTF_8));
            if (r[0] != null) {
                t.printf("- Over its first hour (%s): %d shoes, %d states valued in %d s, %.3f s a valued state, "
                                + "about %.0f shoes an hour.%n", s.twinLog, r[0][0], r[0][1], r[0][2],
                        (double) r[0][2] / r[0][1], 3600.0 * r[0][0] / r[0][2]);
                t.put("hourShoes", r[0][0]);
                t.put("hourStates", r[0][1]);
                t.put("hourSeconds", r[0][2]);
            }
            if (r[1] != null) {
                t.printf("- The whole run, as logged: %d shoes, %d states valued in %d s (%.2f h), %.3f s a valued "
                        + "state.%n", r[1][0], r[1][1], r[1][2], r[1][2] / 3600.0, (double) r[1][2] / r[1][1]);
                t.put("runShoes", r[1][0]);
                t.put("runSeconds", r[1][2]);
            }
        }
        t.println("");
        return t.part();
    }

    // ------------------------------------------------------------------ the proxy check

    /** The upper tail of the binomial: P(X >= k) for X ~ Binomial(n, p). */
    static double binomialTail(int n, double p, int k) {
        double sum = 0;
        for (int x = Math.max(0, k); x <= n; x++) {
            double lc = lgamma(n + 1) - lgamma(x + 1) - lgamma(n - x + 1);
            sum += Math.exp(lc + x * Math.log(p) + (n - x) * Math.log1p(-p));
        }
        return Math.min(1, sum);
    }

    /** log Gamma(x) for x >= 1, by Lanczos (g = 7, nine terms). */
    static double lgamma(double x) {
        double[] c = {0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
                -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
                1.5056327351493116e-7};
        x -= 1;
        double a = c[0];
        double t = x + 7.5;
        for (int i = 1; i < 9; i++) {
            a += c[i] / (x + i);
        }
        return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
    }

    /** One row of the proxy check. */
    static final class ProxyRow {
        final String name;
        final double twin;
        final double main;
        final double mainSe;

        ProxyRow(String name, double twin, double main, double mainSe) {
            this.name = name;
            this.twin = twin;
            this.main = main;
            this.mainSe = mainSe;
        }

        /** The twin's difference less the main file's, in units of sqrt(2) times the main file's paired SE. */
        double z() {
            return (twin - main) / (Math.sqrt(2) * mainSe);
        }
    }

    /** The rows within 2 of the proxy check, and whether that many is within chance at PROXY_ALPHA: {within, p}. */
    static double[] proxyVerdict(List<ProxyRow> rows) {
        int within = 0;
        for (ProxyRow r : rows) {
            within += Math.abs(r.z()) <= 2 ? 1 : 0;
        }
        return new double[]{within, binomialTail(rows.size(), OUTSIDE_TWO, rows.size() - within)};
    }

    Part proxy() throws Exception {
        Text t = new Text();
        CeilingReport.Published p = CeilingReport.read(s.published);
        String tie = s.printed == null ? null : tie(p, s.printed);
        List<CountKeys.Count> catalog = CountKeys.catalog();
        List<double[][]> twin = estimateAll(estimates(), catalog);
        int hiLo = indexOf(catalog, HI_LO);
        SystemScorer.Result mainHiLo = p.of(HI_LO);
        List<ProxyRow> rows = new ArrayList<>();
        for (int c = 0; c < catalog.size(); c++) {
            CountKeys.Count count = catalog.get(c);
            CountKeys.Count variant = CountKeys.bettingVariant(count);
            String[] names = variant == null ? new String[]{count.name, count.name + ", betting only"}
                    : new String[]{count.name, count.name + ", betting only", variant.name, variant.name + ", betting only"};
            for (int e = 0; e < names.length; e++) {
                double[] est = twin.get(c)[e];
                t.put("native." + SAME8 + "." + names[e], est[SAME8]);
                t.put("native." + FLAT + "." + names[e], est[FLAT]);
                if (c == hiLo) {
                    continue;
                }
                SystemScorer.Entry main = entryNamed(p, names[e]);
                SystemScorer.Entry base = mainHiLo.entries.get(e % 2);
                double d = main.estimate[P80][SAME8] - base.estimate[P80][SAME8];
                double[] diff = ComparisonReport.difference(main, base, P80, SAME8, new double[main.boot[P80][SAME8].length]);
                rows.add(new ProxyRow(names[e], est[SAME8] - twin.get(hiLo)[e % 2][SAME8], d,
                        Bootstrap.interval(d, diff).se));
            }
        }
        double[] v = proxyVerdict(rows);
        boolean ok = v[1] >= PROXY_ALPHA;
        t.println("## The proxy check");
        t.println("");
        t.printf("Every published row's 80%%, 1-%d same-schedule EV on the twin, as a difference from Hi-Lo playing the "
                        + "same way, beside the same difference on the main file as the full-index run stored it (%s, "
                        + "%d resamples)%s. The files are independent and the same size, so the twin's difference less "
                        + "the main file's has an SE of about sqrt(2) times the main file's paired SE, and z is that "
                        + "difference over it. About 95%% should lie within 2.%n%n", SystemScorer.SPREADS, s.published,
                mainHiLo.entries.get(0).boot[P80][SAME8].length, s.printed == null ? ""
                        : tie == null ? ", whose stored rows print " + s.printed + "'s headline to the digit "
                        + "(CeilingReport.checkPrinted)" : "");
        if (s.printed != null && tie != null) {
            t.printf("Its stored rows do NOT print %s's headline to the digit (CeilingReport.checkPrinted), so the "
                    + "main-file report of step 7 refuses a look until the reason is found; the check below reads the "
                    + "stored rows as they are. What it said:%n%n```%n%s%n```%n%n", s.printed, tie);
        }
        t.println("| Row | Twin, less Hi-Lo (%) | Main file, less Hi-Lo (%) | Its paired SE (%) | z |");
        t.println("|---|---|---|---|---|");
        for (ProxyRow r : rows) {
            t.printf("| %s | %+.4f | %+.4f | %.4f | %+.2f |%n", r.name, 100 * r.twin, 100 * r.main, 100 * r.mainSe, r.z());
        }
        t.println("");
        t.printf("%d of %d rows lie within 2 (%.1f%%), against the %.1f%% expected; under independence, chance gives so "
                        + "few or fewer with probability %.3g. The rows share Hi-Lo and their shoes, so they are not "
                        + "independent and that figure is only a guide. Verdict: %s.%n%n", (int) v[0], rows.size(),
                100 * v[0] / rows.size(), 100 * (1 - OUTSIDE_TWO), v[1], ok
                        ? "the twin's published differences agree with the main file's within chance"
                        : "the twin's published differences DISAGREE with the main file's beyond chance (section 6: "
                        + "fix it before the search)");
        t.put("rows", rows.size());
        t.put("within", v[0]);
        t.put("chance", v[1]);
        t.put("ok", ok ? "yes" : "no");
        t.put("tie", s.printed == null ? "not made" : tie == null ? "yes" : "no");
        return t.part();
    }

    /**
     * The tie of the stored rows to the published report's printed headline (CeilingReport.checkPrinted):
     * null when every line reads as printed, else what the check said.
     */
    static String tie(CeilingReport.Published p, Path printed) throws IOException {
        try {
            CeilingReport.checkPrinted(p, printed);
            return null;
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }

    static SystemScorer.Entry entryNamed(CeilingReport.Published p, String name) {
        for (SystemScorer.Result r : p.results) {
            for (SystemScorer.Entry e : r.entries) {
                if (e.name.equals(name)) {
                    return e;
                }
            }
        }
        throw new IllegalStateException("the stored rows hold no " + name);
    }

    static int indexOf(List<CountKeys.Count> catalog, String system) {
        for (int c = 0; c < catalog.size(); c++) {
            if (catalog.get(c).systems.contains(system)) {
                return c;
            }
        }
        throw new IllegalStateException("the catalog holds no " + system);
    }

    // ------------------------------------------------------------------ the grain

    /** "1/20" for D = 500: the grain g = D / K. */
    static String grainText(int d) {
        if (CountKeys.K % d == 0) {
            return CountKeys.K / d == 1 ? "1" : "1/" + (CountKeys.K / d);
        }
        return d + "/" + CountKeys.K;
    }

    Part ladder() throws Exception {
        Text t = new Text();
        CountKeys.Count hiLo = CountSearch.catalogCount(HI_LO);
        CountKeys.Count zen = CountSearch.catalogCount(ZEN);
        List<CountKeys.Count> counts = new ArrayList<>(Arrays.asList(hiLo, zen));
        for (int d : LADDER) {
            counts.add(CountKeys.atGrain(hiLo, d));
            counts.add(CountKeys.atGrain(zen, d));
        }
        SystemScorer sc = estimates();
        List<Callable<double[]>> jobs = new ArrayList<>();
        for (CountKeys.Count c : counts) {
            jobs.add(() -> sc.estimate(c, null, P80)[0]);
        }
        List<double[]> v = parallel(s.threads, jobs);
        t.println("## The grain ladder");
        t.println("");
        t.printf("Hi-Lo and Zen at each grain g (CountKeys.atGrain: tags at Hi-Lo's spread, whole at K = %d, key divisor "
                + "D = g K), 80%%, playing their own fits, point estimates.%n%n", CountKeys.K);
        t.println("| g | D | Hi-Lo, 1-8 | Zen, 1-8 | Hi-Lo, flat | Zen, flat |");
        t.println("|---|---|---|---|---|---|");
        t.printf("| own grain | 1 | %+.4f | %+.4f | %+.4f | %+.4f |%n", 100 * v.get(0)[SAME8], 100 * v.get(1)[SAME8],
                100 * v.get(0)[FLAT], 100 * v.get(1)[FLAT]);
        t.put("hilo.native", v.get(0)[SAME8]);
        t.put("zen.native", v.get(1)[SAME8]);
        for (int k = 0; k < LADDER.length; k++) {
            double[] h = v.get(2 + 2 * k);
            double[] z = v.get(3 + 2 * k);
            t.printf("| %s | %d | %+.4f | %+.4f | %+.4f | %+.4f |%n", grainText(LADDER[k]), LADDER[k], 100 * h[SAME8],
                    100 * z[SAME8], 100 * h[FLAT], 100 * z[FLAT]);
            t.put("hilo." + LADDER[k], h[SAME8]);
            t.put("zen." + LADDER[k], z[SAME8]);
        }
        boolean same = Arrays.equals(v.get(0), v.get(2));
        t.println("");
        t.printf("Hi-Lo at g = 1 keys as Hi-Lo does, so its figures must equal Hi-Lo's own to the bit: %s.%n%n",
                same ? "they do" : "THEY DO NOT");
        t.put("hiloAtOneSame", same ? "yes" : "no");
        return t.part();
    }

    /**
     * The coarsest grain of the ladder past which no finer grain improves on it by more than the
     * floor, for every count: values[count][grain], coarse to fine. The finest always qualifies.
     */
    static int grain(double[][] values, double floor) {
        int grains = values[0].length;
        for (int k = 0; k < grains; k++) {
            boolean stops = true;
            for (double[] v : values) {
                for (int j = k + 1; j < grains; j++) {
                    stops &= v[j] - v[k] <= floor;
                }
            }
            if (stops) {
                return k;
            }
        }
        return grains - 1;
    }

    /** The median depth of the 80% states, each weighted 1/q, as the rounds they stand for. */
    static int medianDepth(ScoringStates st, int cut) {
        List<int[]> in = new ArrayList<>();
        double total = 0;
        for (int x = 0; x < st.size; x++) {
            if (st.depth[x] < cut) {
                in.add(new int[]{st.depth[x], x});
                total += st.weight[x];
            }
        }
        in.sort(Comparator.comparingInt(a -> a[0]));
        double sum = 0;
        for (int[] a : in) {
            sum += st.weight[a[1]];
            if (sum >= total / 2) {
                return a[0];
            }
        }
        throw new IllegalStateException("no states under the cut " + cut);
    }

    /** D_r: a running key step of g Hi-Lo true-count units at that depth, the cards left being what a true count divides by. */
    static int runningDivisor(int d, int depth) {
        return (int) Math.rint((double) d * (ShoeRun.CARDS - depth) / 52);
    }

    Part grain(Part ladder, Part noise0) throws Exception {
        Text t = new Text();
        double floor = noise0.d("floor." + ANGLES[0]);
        double[][] v = new double[2][LADDER.length];
        for (int k = 0; k < LADDER.length; k++) {
            v[0][k] = ladder.d("hilo." + LADDER[k]);
            v[1][k] = ladder.d("zen." + LADDER[k]);
        }
        int k = grain(v, floor);
        int d = LADDER[k];
        int depth = medianDepth(st(), SystemScorer.CUTS[P80]);
        int dr = runningDivisor(d, depth);
        t.println("## g");
        t.println("");
        t.printf("The coarsest grain on the ladder past which neither Hi-Lo nor Zen improves by more than the noise floor "
                        + "at %.0f degree, %.4f%% a round (the smaller of its two measures at g = %s, section 4): g = %s, D = %d.%s%n%n",
                ANGLES[0], 100 * floor, grainText(DEFAULT_DIVISOR), grainText(d), d, k == LADDER.length - 1
                        ? " It is the finest grain tried: the counts may still improve finer, and the report says so." : "");
        t.println("| g | Hi-Lo: most a finer grain gains | Zen: most a finer grain gains |");
        t.println("|---|---|---|");
        for (int j = 0; j < LADDER.length; j++) {
            t.printf("| %s | %s | %s |%n", grainText(LADDER[j]), gainText(v[0], j), gainText(v[1], j));
        }
        t.println("");
        t.printf("A running count at g (F1r, and the running catalog counts at g) keys on its running count over D_r, "
                        + "set so that one key step is g Hi-Lo true-count units at the median depth of the 80%% states, "
                        + "%d cards dealt (each state weighted 1/q): D_r = D x (%d - %d) / 52 = %d.%n%n", depth,
                ShoeRun.CARDS, depth, dr);
        t.put("divisor", d);
        t.put("index", k);
        t.put("finest", k == LADDER.length - 1 ? "yes" : "no");
        t.put("floor", floor);
        t.put("medianDepth", depth);
        t.put("runningDivisor", dr);
        return t.part();
    }

    private static String gainText(double[] v, int k) {
        double most = Double.NEGATIVE_INFINITY;
        for (int j = k + 1; j < v.length; j++) {
            most = Math.max(most, v[j] - v[k]);
        }
        return k == v.length - 1 ? "(finest)" : String.format(Locale.ROOT, "%+.4f%%", 100 * most);
    }

    // ------------------------------------------------------------------ the noise floor

    /** Hi-Lo's tags by rank: the sign reference the search uses by default. */
    static double[] sign() {
        return CountSearch.rankTags(CountSearch.catalogCount(HI_LO));
    }

    /** Zen's direction, on the sphere, in the search's coordinates. */
    static double[] zenCentre() {
        return CountSearch.onSphere(CountSearch.coordinates(CountSearch.rankTags(CountSearch.catalogCount(ZEN))));
    }

    /** n directions perpendicular to the centre, each on the sphere, drawn from the seed. */
    static double[][] perpendicular(double[] centre, int n, long seed) {
        Random rnd = new Random(seed);
        double cc = 0;
        for (double x : centre) {
            cc += x * x;
        }
        double[][] out = new double[n][];
        for (int j = 0; j < n; j++) {
            double[] g = new double[centre.length];
            double gc = 0;
            for (int k = 0; k < g.length; k++) {
                g[k] = rnd.nextGaussian();
                gc += g[k] * centre[k];
            }
            for (int k = 0; k < g.length; k++) {
                g[k] -= gc / cc * centre[k];
            }
            out[j] = CountSearch.onSphere(g);
        }
        return out;
    }

    /**
     * The noise floor's counts at divisor d: Zen's direction, then for each angle each direction
     * at that angle from it, as the search would score them (CountSearch.whole: normalised, signed
     * by Hi-Lo, whole at K).
     */
    static List<CountKeys.Count> noiseCounts(int d, int directions, long seed) {
        double[] sign = sign();
        double[] x0 = zenCentre();
        double[][] u = perpendicular(x0, directions, seed);
        List<CountKeys.Count> out = new ArrayList<>();
        out.add(CountKeys.whole("Zen's direction at " + grainText(d), CountSearch.whole(x0, sign), null, d, true));
        for (double a : ANGLES) {
            double r = Math.toRadians(a);
            for (int j = 0; j < directions; j++) {
                double[] x = new double[x0.length];
                for (int k = 0; k < x.length; k++) {
                    x[k] = Math.cos(r) * x0[k] + Math.sin(r) * u[j][k];
                }
                out.add(CountKeys.whole(String.format(Locale.ROOT, "Zen + %s, direction %d, at %s", degreesText(a),
                        j + 1, grainText(d)), CountSearch.whole(x, sign), null, d, true));
            }
        }
        return out;
    }

    static String angleText(double a) {
        return a == Math.rint(a) ? Integer.toString((int) a) : Double.toString(a);
    }

    /** "1 degree", "5 degrees". */
    static String degreesText(double a) {
        return angleText(a) + (a == 1 ? " degree" : " degrees");
    }

    /** The angle in degrees between two counts' whole tags by rank. */
    static double angle(CountKeys.Count a, CountKeys.Count b) {
        double cos = CountSearch.cosine(CountSearch.rankTags(a), CountSearch.rankTags(b));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    /** Root mean square. */
    static double rms(double[] x) {
        double ss = 0;
        for (double v : x) {
            ss += v * v;
        }
        return Math.sqrt(ss / x.length);
    }

    Part noise(int d) throws Exception {
        Text t = new Text();
        List<CountKeys.Count> counts = noiseCounts(d, s.directions, s.directionSeed);
        List<double[]> boot = resampleAll(counts);
        int n = s.directions;
        // The sub-runs: each scored alone, point estimates, as ComparisonReport --calibrate splits them.
        double[][] sub = new double[s.subRuns][];
        int subShoes = 0;
        int subStates = 0;
        for (int k = 0; k < s.subRuns; k++) {
            ScoringStates part = st().subRun(k, s.subRuns);
            subShoes = part.shoes;
            subStates += part.size;
            SystemScorer sc = new SystemScorer(part, new Bootstrap(part.shoes, 0, Bootstrap.SEED), SystemScorer.M_PLAY);
            List<Callable<Double>> jobs = new ArrayList<>();
            for (CountKeys.Count c : counts) {
                jobs.add(() -> sc.estimate(c, null, P80)[0][SAME8]);
            }
            List<Double> v = parallel(s.threads, jobs);
            sub[k] = v.stream().mapToDouble(Double::doubleValue).toArray();
            log("  sub-run %d of %d scored", k + 1, s.subRuns);
        }
        double[] centre = boot.get(0);
        t.printf("## The noise floor at g = %s%n%n", grainText(d));
        t.printf("Zen's direction against %d directions at each angle from it (drawn from seed %d, the same %d at every "
                        + "angle), every count whole at K and keyed at D = %d as the search scores it (CountSearch.whole), "
                        + "80%%, 1-%d same schedule. Two measures of the SE of a difference from Zen: the paired bootstrap "
                        + "(%d resamples from seed %d, everything refitted on each); and the spread of the difference over "
                        + "%d disjoint sub-runs of %d shoes (about %d valued states each), each scored alone as "
                        + "ComparisonReport --calibrate scores them, divided by sqrt(%d) to scale it to the full file. Each "
                        + "is the root mean square over the directions at that angle. The noise floor is the smaller "
                        + "(section 4). Zen's direction scores %+.4f%%.%n%n", n, s.directionSeed, n, d,
                SystemScorer.SPREADS, s.resamples, Bootstrap.SEED, s.subRuns, subShoes, subStates / s.subRuns, s.subRuns,
                100 * centre[0]);
        t.println("| Angle | Mean difference from Zen | SE, bootstrap | SE, sub-runs scaled | Noise floor |");
        t.println("|---|---|---|---|---|");
        StringBuilder detail = new StringBuilder();
        List<Double> ratios = new ArrayList<>();
        for (int a = 0; a < ANGLES.length; a++) {
            double[] bootSe = new double[n];
            double[] subSe = new double[n];
            double mean = 0;
            for (int j = 0; j < n; j++) {
                int c = 1 + a * n + j;
                double[] x = boot.get(c);
                double diff = x[0] - centre[0];
                double[] db = new double[s.resamples];
                for (int r = 0; r < s.resamples; r++) {
                    db[r] = x[1 + r] - centre[1 + r];
                }
                bootSe[j] = Bootstrap.interval(diff, db).se;
                double[] ds = new double[s.subRuns];
                for (int k = 0; k < s.subRuns; k++) {
                    ds[k] = sub[k][c] - sub[k][0];
                }
                subSe[j] = ComparisonReport.sd(ds) / Math.sqrt(s.subRuns);
                mean += diff / n;
                if (subSe[j] > 0) {
                    ratios.add(bootSe[j] / subSe[j]);
                }
                detail.append(String.format(Locale.ROOT, "| %s | %d | %.3f | %+.4f | %.4f | %.4f |%n", angleText(ANGLES[a]),
                        j + 1, angle(counts.get(0), counts.get(c)), 100 * diff, 100 * bootSe[j], 100 * subSe[j]));
            }
            double b = rms(bootSe);
            double sr = rms(subSe);
            double floor = Math.min(b, sr);
            t.printf("| %s | %+.4f%% | %.4f%% | %.4f%% | %.4f%% |%n", degreesText(ANGLES[a]), 100 * mean, 100 * b,
                    100 * sr, 100 * floor);
            t.put("mean." + ANGLES[a], mean);
            t.put("boot." + ANGLES[a], b);
            t.put("sub." + ANGLES[a], sr);
            t.put("floor." + ANGLES[a], floor);
        }
        double ratio = median(ratios);
        t.println("");
        t.printf("Over the directions, the median ratio of the bootstrap SE to the sub-runs' is %.2f. Each direction:%n%n",
                ratio);
        t.println("| Angle | Direction | Angle as rounded, degrees | Difference from Zen | SE, bootstrap | SE, sub-runs scaled |");
        t.println("|---|---|---|---|---|---|");
        t.out.print(detail);
        t.println("");
        t.put("divisor", d);
        t.put("centre", centre[0]);
        t.put("ratio", ratio);
        return t.part();
    }

    static double median(List<Double> x) {
        if (x.isEmpty()) {
            return Double.NaN;
        }
        double[] a = x.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2;
    }

    // ------------------------------------------------------------------ one evaluation, timed

    /** The searches of section 7, in evaluations, and which evaluation each spends. */
    static final class Search {
        final String what;
        final int evaluations;
        /** "F1", "F2", "bet", "F1r" or "half". */
        final String kind;
        /** Whether the budget spends it only if the runs end in separate regions. */
        final boolean ifNeeded;

        Search(String what, int evaluations, String kind, boolean ifNeeded) {
            this.what = what;
            this.evaluations = evaluations;
            this.kind = kind;
            this.ifNeeded = ifNeeded;
        }
    }

    /** Section 7's budget. */
    static final List<Search> BUDGET = Arrays.asList(
            new Search("F1 at 1-8: 16 runs of 1,500", 24_000, "F1", false),
            new Search("F1 at 1-8: a run at double the population and cap for each separate region", 6_000, "F1", true),
            new Search("F1 flat: 5 runs of 1,500", 7_500, "F1", false),
            new Search("F2: its play blocks and polish, both vectors scored", 6_000, "F2", false),
            new Search("F2: its betting blocks, the play held", 30_000, "bet", false),
            new Search("F1r: 3 runs of 1,500", 4_500, "F1r", false),
            new Search("F1 on the twin's halves: 16 runs of 1,500", 24_000, "half", false),
            new Search("slices, the grain check and the noise floor", 1_000, "F1", false));

    /** The median of a run of single-threaded timings of f, after two untimed: seconds. */
    static double timed(CountSearch.Objective f, double[] x, int repeats) {
        f.of(x);
        f.of(x);
        List<Double> times = new ArrayList<>();
        for (int r = 0; r < repeats; r++) {
            long t0 = System.nanoTime();
            f.of(x);
            times.add((System.nanoTime() - t0) / 1e9);
        }
        return median(times);
    }

    Part timing(int d, int dr) throws Exception {
        Text t = new Text();
        SystemScorer sc = estimates();
        CountSearch.Settings f1 = new CountSearch.Settings();
        f1.divisor = d;
        f1.sign = sign();
        CountSearch.Settings f1r = new CountSearch.Settings();
        f1r.divisor = dr;
        f1r.sign = sign();
        double[] zen = zenCentre();
        double[] hiLo = CountSearch.onSphere(CountSearch.coordinates(sign()));
        double[] zenRunning = Arrays.copyOf(zen, 10);
        zenRunning[9] = 0.05 * CountSearch.RADIUS;
        double[] joint = Arrays.copyOf(zen, 18);
        System.arraycopy(hiLo, 0, joint, 9, 9);
        // Single-threaded, here and nothing else of the calibration running.
        double tZen = timed(CountSearch.f1(sc, f1), zen, s.repeats);
        double tHiLo = timed(CountSearch.f1(sc, f1), hiLo, s.repeats);
        double tF1 = (tZen + tHiLo) / 2;
        double tF2 = timed(CountSearch.f2Joint(sc, f1), joint, s.repeats);
        double tBet = timed(CountSearch.f2Bet(sc, f1, CountSearch.whole(zen, f1.sign)), hiLo, 4 * s.repeats);
        double tF1r = timed(CountSearch.f1r(sc, f1r), zenRunning, s.repeats);
        ScoringStates half = st().subRun(0, 2);
        SystemScorer hsc = new SystemScorer(half, new Bootstrap(half.shoes, 0, Bootstrap.SEED),
                SystemScorer.M_PLAY * Math.sqrt(2));
        double tHalf = timed(CountSearch.f1(hsc, f1), zen, s.repeats);
        // A generation: lambda = 12 directions, one after another, then on the search's threads at once.
        double[][] gen = new double[12][];
        double[][] u = perpendicular(zen, 12, s.directionSeed + 1);
        for (int j = 0; j < 12; j++) {
            gen[j] = new double[9];
            for (int k = 0; k < 9; k++) {
                gen[j][k] = Math.cos(0.1) * zen[k] + Math.sin(0.1) * u[j][k];
            }
        }
        CountSearch.Objective f = CountSearch.f1(sc, f1);
        List<Callable<Double>> jobs = new ArrayList<>();
        for (double[] x : gen) {
            jobs.add(() -> f.of(x));
        }
        // Once each way untimed, then each way in turn, the median of each.
        for (double[] x : gen) {
            f.of(x);
        }
        parallel(s.searchThreads, jobs);
        List<Double> serialTimes = new ArrayList<>();
        List<Double> togetherTimes = new ArrayList<>();
        for (int r = 0; r < s.repeats; r++) {
            long t0 = System.nanoTime();
            for (double[] x : gen) {
                f.of(x);
            }
            serialTimes.add((System.nanoTime() - t0) / 1e9);
            t0 = System.nanoTime();
            parallel(s.searchThreads, jobs);
            togetherTimes.add((System.nanoTime() - t0) / 1e9);
        }
        double serial = median(serialTimes);
        double together = median(togetherTimes);
        double speedup = serial / together;

        t.println("## One evaluation, timed");
        t.println("");
        t.printf("Each the median of %d timings on one thread, after two untimed, with nothing else of the calibration "
                        + "running; every one is the search's own objective (CountSearch), at 80%%, 1-%d, g = %s:%n%n",
                s.repeats, SystemScorer.SPREADS, grainText(d));
        t.println("| Evaluation | Seconds |");
        t.println("|---|---|");
        t.printf("| F1, Zen's direction (CountSearch.f1) | %.3f |%n", tZen);
        t.printf("| F1, Hi-Lo's direction | %.3f |%n", tHiLo);
        t.printf("| F2, both vectors (f2Joint) | %.3f |%n", tF2);
        t.printf("| F2's betting block, the play held (f2Bet; %d timings) | %.4f |%n", 4 * s.repeats, tBet);
        t.printf("| F1r, running at D_r = %d (f1r) | %.3f |%n", dr, tF1r);
        t.printf("| F1 on half the twin, M_play coefficient 5 sqrt 2 | %.3f |%n", tHalf);
        t.println("");
        t.printf("One evaluation, F1's, takes %.3f s (the mean of the two directions). A generation of 12 F1 evaluations "
                        + "(directions about 6 degrees from Zen's) took %.2f s one after another and %.2f s on %d threads "
                        + "at once, each the median of %d: %d threads are worth %.2f.%n%n", tF1, serial, together,
                s.searchThreads, s.repeats, s.searchThreads, speedup);
        double[] cost = {tF1, tF2, tBet, tF1r, tHalf};
        double low = 0;
        double high = 0;
        t.printf("The search budget of section 7, at these times and %d threads worth %.2f:%n%n", s.searchThreads, speedup);
        t.println("| Search | Evaluations | Seconds each | Machine hours |");
        t.println("|---|---|---|---|");
        for (Search x : BUDGET) {
            double each = cost(x.kind, cost);
            double hours = x.evaluations * each / speedup / 3600;
            t.printf("| %s%s | %,d | %.4f | %.2f |%n", x.what, x.ifNeeded ? " (if needed)" : "", x.evaluations, each, hours);
            high += hours;
            low += x.ifNeeded ? 0 : hours;
        }
        t.println("");
        t.printf("In all, %.1f to %.1f machine hours on %d threads, while the machine is awake.%n%n", low, high,
                s.searchThreads);
        t.put("f1", tF1);
        t.put("f1Zen", tZen);
        t.put("f1HiLo", tHiLo);
        t.put("f2", tF2);
        t.put("bet", tBet);
        t.put("f1r", tF1r);
        t.put("half", tHalf);
        t.put("serial", serial);
        t.put("together", together);
        t.put("speedup", speedup);
        t.put("hoursLow", low);
        t.put("hoursHigh", high);
        return t.part();
    }

    private static double cost(String kind, double[] c) {
        switch (kind) {
            case "F1": return c[0];
            case "F2": return c[1];
            case "bet": return c[2];
            case "F1r": return c[3];
            case "half": return c[4];
            default: throw new IllegalArgumentException(kind);
        }
    }

    // ------------------------------------------------------------------ every published row at g

    /** A catalog count at g: a true count at D, a running one at D_r. */
    static CountKeys.Count atGrain(CountKeys.Count c, int d, int dr) {
        return CountKeys.atGrain(c, c.trueCounted ? d : dr);
    }

    Part publishedAtGrain(int d, int dr, Part proxy) throws Exception {
        Text t = new Text();
        List<CountKeys.Count> catalog = CountKeys.catalog();
        List<CountKeys.Count> atG = new ArrayList<>();
        for (CountKeys.Count c : catalog) {
            atG.add(atGrain(c, d, dr));
        }
        List<double[][]> v = estimateAll(estimates(), atG);
        // Rows: {catalog index, entry}, entry 0 the count, 1 betting only, 2 its side-count variant, 3 that betting only.
        List<int[]> rows = new ArrayList<>();
        for (int c = 0; c < catalog.size(); c++) {
            for (int e = 0; e < v.get(c).length; e++) {
                rows.add(new int[]{c, e});
            }
        }
        t.printf("## Every published row at g = %s%n%n", grainText(d));
        t.printf("Each catalog count at g (CountKeys.atGrain), a true count at D = %d and a running one at D_r = %d, with "
                        + "its side-count variant; 80%%, point estimates, beside the same row at its own grain on the twin "
                        + "(the proxy check's). The grain part is the row at g less the row at its own grain.%n%n", d, dr);
        t.println("| Row | Form | 1-8 at g | 1-8, own grain | Grain part | Flat at g | Flat, own grain |");
        t.println("|---|---|---|---|---|---|---|");
        List<int[]> own = new ArrayList<>();
        for (int[] r : rows) {
            String name = rowName(catalog.get(r[0]), r[1]);
            double g8 = v.get(r[0])[r[1]][SAME8];
            double n8 = proxy.d("native." + SAME8 + "." + name);
            double gf = v.get(r[0])[r[1]][FLAT];
            double nf = proxy.d("native." + FLAT + "." + name);
            t.printf("| %s | %s | %+.4f | %+.4f | %+.4f | %+.4f | %+.4f |%n", name, catalog.get(r[0]).trueCounted
                    ? "true, D " + d : "running, D_r " + dr, 100 * g8, 100 * n8, 100 * (g8 - n8), 100 * gf, 100 * nf);
            t.put("g." + SAME8 + "." + name, g8);
            t.put("g." + FLAT + "." + name, gf);
            if (r[1] % 2 == 0) {
                own.add(r);
            }
        }
        own.sort((a, b) -> Double.compare(v.get(b[0])[b[1]][SAME8], v.get(a[0])[a[1]][SAME8]));
        List<Integer> controls = new ArrayList<>();
        t.println("");
        t.println("The five best rows at g playing their own fits, by 1-8 EV at 80%:");
        t.println("");
        for (int k = 0; k < Math.min(5, own.size()); k++) {
            int[] r = own.get(k);
            String name = rowName(catalog.get(r[0]), r[1]);
            double g8 = v.get(r[0])[r[1]][SAME8];
            double n8 = proxy.d("native." + SAME8 + "." + name);
            t.printf("%d. %s: %+.4f%% at g, %+.4f%% at its own grain (grain part %+.4f%%).%n", k + 1, name, 100 * g8,
                    100 * n8, 100 * (g8 - n8));
            t.put("top." + k, name);
            t.put("topAtG." + k, g8);
            t.put("topOwn." + k, n8);
            if (!controls.contains(r[0])) {
                controls.add(r[0]);
            }
        }
        int hiLo = indexOf(catalog, HI_LO);
        if (!controls.contains(hiLo)) {
            controls.add(hiLo);
        }
        t.println("");
        t.printf("The controls, the counts behind those rows and Hi-Lo, each at g with its side-count variant: %s.%n%n",
                names(catalog, controls));
        for (int k = 0; k < controls.size(); k++) {
            CountKeys.Count c = catalog.get(controls.get(k));
            t.put("control." + k, c.systems.get(0));
            t.put("controlDivisor." + k, c.trueCounted ? d : dr);
        }
        t.put("controls", controls.size());
        return t.part();
    }

    /** The name a row of a count goes by, as score names its entries: 0 the count, 1 betting only, 2 its variant, 3 that betting only. */
    static String rowName(CountKeys.Count c, int entry) {
        String base = entry < 2 ? c.name : CountKeys.bettingVariant(c).name;
        return entry % 2 == 0 ? base : base + ", betting only";
    }

    private static String names(List<CountKeys.Count> catalog, List<Integer> which) {
        List<String> out = new ArrayList<>();
        for (int c : which) {
            out.add(catalog.get(c).name);
        }
        return String.join("; ", out);
    }

    // ------------------------------------------------------------------ the colour check

    /** A count's rank averages, whole: each rank's kinds' tags averaged, rounded to the nearest whole number. */
    static int[] rankAverages(CountKeys.Count c) {
        double[] t = CountSearch.rankTags(c);
        int[] out = new int[11];
        for (int r = 1; r <= 10; r++) {
            out[r] = (int) Math.rint(t[r]);
        }
        return out;
    }

    /** The colour check's count: Red Seven at g with the seven's two kinds averaged, running at D_r. */
    static CountKeys.Count rankAverage(int dr) {
        CountKeys.Count rs = CountKeys.atGrain(CountSearch.catalogCount(RED_SEVEN), dr);
        return CountKeys.whole("Red Seven's rank average at D_r " + dr, rankAverages(rs), null, dr, false);
    }

    Part colour(int dr, Part noise) throws Exception {
        Text t = new Text();
        CountKeys.Count rs = CountKeys.atGrain(CountSearch.catalogCount(RED_SEVEN), dr);
        CountKeys.Count avg = rankAverage(dr);
        double[] seven = CountSearch.rankTags(rs);
        List<double[]> v = resampleAll(Arrays.asList(rs, avg));
        double diff = v.get(0)[0] - v.get(1)[0];
        double[] db = new double[s.resamples];
        for (int r = 0; r < s.resamples; r++) {
            db[r] = v.get(0)[1 + r] - v.get(1)[1 + r];
        }
        Bootstrap.Interval iv = Bootstrap.interval(diff, db);
        boolean better = iv.lo > 0;
        double floor = noise.d("floor." + ANGLES[0]);
        t.println("## The colour check");
        t.println("");
        t.printf("Red Seven at g as a catalog entry (CountKeys.atGrain, running, D_r = %d) against its rank average, a "
                        + "running count of our own with the same tags but the seven at the average of its two kinds "
                        + "(red %d, black %d: %d), at the same divisor; 80%%, 1-%d, paired over %d resamples.%n%n", dr,
                rs.tag[kindOf(7, true)], rs.tag[kindOf(7, false)], avg.rankTag(7), SystemScorer.SPREADS, s.resamples);
        t.printf("- Red Seven at g: %+.4f%%; its rank average: %+.4f%%.%n", 100 * v.get(0)[0], 100 * v.get(1)[0]);
        t.printf("- Red Seven less its rank average: %+.4f%% (SE %.4f%%, 95%% interval [%+.4f%%, %+.4f%%]); the noise "
                + "floor at %s is %.4f%%.%n", 100 * diff, 100 * iv.se, 100 * iv.lo, 100 * iv.hi,
                degreesText(ANGLES[0]), 100 * floor);
        t.printf("- %s%n%n", better ? "Red Seven is better beyond noise: section 1's argument that colour carries "
                + "nothing about the cards to come is NOT borne out here, and the report says so."
                : "Red Seven is not better beyond noise, as section 1 argues (the seven's colour says nothing about the "
                + "cards to come).");
        if (seven[7] != avg.rankTag(7)) {
            t.printf("The seven's two kinds average %.1f, rounded to %d; that moves the rank average's deck sum by %d "
                    + "of %d.%n%n", seven[7], avg.rankTag(7), CountKeys.deckSum(avg.tag) - rs.deckSum, rs.deckSum);
        }
        t.put("diff", diff);
        t.put("se", iv.se);
        t.put("lo", iv.lo);
        t.put("hi", iv.hi);
        t.put("better", better ? "yes" : "no");
        return t.part();
    }

    /** The first kind of a rank of one colour. */
    static int kindOf(int rank, boolean red) {
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            if (ShoeRun.rankOf(k) == rank && ShoeRun.red(k) == red) {
                return k;
            }
        }
        throw new IllegalArgumentException("no kind of rank " + rank);
    }

    // ------------------------------------------------------------------ the theory pass

    static String tagsText(double[] t) {
        StringBuilder b = new StringBuilder();
        for (int r = 1; r <= 10; r++) {
            b.append(r == 1 ? "" : ",").append(t[r]);
        }
        return b.toString();
    }

    static double[] parseTags(String text) {
        return CountSearch.parseTags(text);
    }

    static String rank(int r) {
        return r == 1 ? "A" : r == 10 ? "T" : Integer.toString(r);
    }

    /** A contested later spot's name, as FullIndexFit numbers the cells. */
    static String cellName(int cell) {
        int c = 0;
        for (int up = 1; up <= 10; up++) {
            for (boolean soft : new boolean[]{false, true}) {
                for (int total = 4; total <= 21; total++) {
                    if (PlayerTree.contested(total, soft)) {
                        if (c == cell) {
                            return (soft ? "soft " : "hard ") + total + " against " + rank(up);
                        }
                        c++;
                    }
                }
            }
        }
        throw new IllegalArgumentException("no cell " + cell);
    }

    Part theory() throws Exception {
        Text t = new Text();
        ScoringStates states = st();
        double[] hiLo = sign();
        double[] zen = CountSearch.rankTags(CountSearch.catalogCount(ZEN));
        CountRegression.Betting b = CountRegression.betting(states);
        double[] ins = new double[1];
        double[] insTags = CountRegression.insurance(states, ins);
        double[] ten = CountSearch.rankTags(CountSearch.catalogCount("Ten Count (unbalanced; insurance-oriented)"));
        List<CountRegression.Index> first = CountRegression.firstDecisions(states);
        List<double[]> dirs = new ArrayList<>();
        List<Double> gains = new ArrayList<>();
        double totalGain = 0;
        for (CountRegression.Index x : first) {
            dirs.add(x.tags);
            gains.add(x.gain);
            totalGain += Math.max(0, x.gain);
        }
        double[] playing = combined(dirs, gains, hiLo);
        int[] later = java.util.stream.IntStream.range(0, states.size).filter(x -> states.shoe[x] < s.laterShoes).toArray();
        List<CountRegression.Spot> spots = CountRegression.laterDecisions(states, later, FullIndexFit.built(states),
                StateRun.montreal(), s.threads);
        dirs.clear();
        gains.clear();
        double totalWorth = 0;
        for (CountRegression.Spot x : spots) {
            dirs.add(x.tags);
            gains.add(x.worth);
            totalWorth += Math.max(0, x.worth);
        }
        double[] laterDir = combined(dirs, gains, hiLo);

        t.println("## The theory pass");
        t.println("");
        t.printf("CountRegression on the twin's %d valued states, every sum weighted 1/q. Tags are A, 2 to 9, T, centred "
                + "and at Hi-Lo's spread.%n%n", states.size);
        t.printf("- **Betting.** The chart advantage regressed on the shares left: R = %.5f (R squared %.5f), the "
                        + "ceiling of any linear count's betting correlation. The betting start, this game's effects of "
                        + "removal as measured on its states: %s. Its angle to Hi-Lo %.1f degrees, to Zen %.1f.%n",
                b.r, b.r2, CountRegression.text(b.tags), degrees(b.tags, hiLo), degrees(b.tags, zen));
        t.printf("- **Depth.** 1 / (cards left) adds %.6f to that R squared: how much the advantage moves with depth at "
                + "an equal composition, which no true count sees.%n", b.depth);
        t.printf("- **Insurance.** The insurance value on the shares after the deal: 1 - R squared = %.3e (%s 1e-12), "
                        + "tags %s, at %.2e degrees from the Ten Count.%n", ins[0], ins[0] < 1e-12 ? "under" : "NOT under",
                CountRegression.text(insTags), degrees(insTags, ten));
        if (playing != null) {
            t.printf("- **First decisions.** %d index directions (deal and move against the chart's). The playing start, "
                            + "each direction weighted by what the perfect row gains there and leaning Hi-Lo's way: %s. "
                            + "Its angle to Hi-Lo %.1f degrees, to Zen %.1f, to the betting start %.1f.%n", first.size(),
                    CountRegression.text(playing), degrees(playing, hiLo), degrees(playing, zen), degrees(playing, b.tags));
        } else {
            t.printf("- **First decisions.** %d index directions, and no move gains over the chart's on these states, so "
                    + "there is no playing start.%n", first.size());
        }
        if (laterDir != null) {
            t.printf("- **Later decisions.** %d of the %d contested spots regressed, on the trees of the %d states of the "
                            + "first %d shoes under the chart's later play. The later-play direction, each spot weighted "
                            + "by what deviating from the chart there is worth: %s. Its angle to Hi-Lo %.1f degrees%s.%n",
                    spots.size(), FullIndexFit.CELLS, later.length, s.laterShoes, CountRegression.text(laterDir),
                    degrees(laterDir, hiLo), playing == null ? "" : String.format(Locale.ROOT,
                            ", to the playing start %.1f", degrees(laterDir, playing)));
        } else {
            t.printf("- **Later decisions.** %d of the %d contested spots regressed on the %d states of the first %d "
                    + "shoes, and none is worth deviating from the chart at, so there is no later-play direction.%n",
                    spots.size(), FullIndexFit.CELLS, later.length, s.laterShoes);
        }
        t.println("");
        List<CountRegression.Index> byGain = new ArrayList<>(first);
        byGain.sort((x, y) -> Double.compare(y.gain, x.gain));
        if (totalGain > 0) {
            t.println("The ten first decisions worth most, with their share of what the perfect row gains over the chart:");
            t.println("");
            t.println("| Deal | Move | Share of the gain | R squared | Direction |");
            t.println("|---|---|---|---|---|");
        }
        for (int k = 0; k < Math.min(10, totalGain > 0 ? byGain.size() : 0); k++) {
            CountRegression.Index x = byGain.get(k);
            t.printf("| %s,%s against %s | %s | %.1f%% | %.3f | %s |%n", rank(Deals.P1[x.deal]), rank(Deals.P2[x.deal]),
                    rank(Deals.UP[x.deal]), StateValuer.MOVES[x.move], totalGain > 0 ? 100 * x.gain / totalGain : 0, x.r2,
                    CountRegression.text(x.tags));
        }
        t.println("");
        if (totalWorth > 0) {
            List<CountRegression.Spot> byWorth = new ArrayList<>(spots);
            byWorth.sort((x, y) -> Double.compare(y.worth, x.worth));
            t.println("The ten later spots worth most, with their share of the worth of all:");
            t.println("");
            t.println("| Spot | Chart | Share of the worth | R squared | Direction |");
            t.println("|---|---|---|---|---|");
            for (int k = 0; k < Math.min(10, byWorth.size()); k++) {
                CountRegression.Spot x = byWorth.get(k);
                t.printf("| %s | %s | %.1f%% | %.3f | %s |%n", cellName(x.cell), FullIndexFit.chartStands(x.cell) ? "stand"
                        : "hit", totalWorth > 0 ? 100 * x.worth / totalWorth : 0, x.r2, CountRegression.text(x.tags));
            }
            t.println("");
        }
        t.put("r", b.r);
        t.put("r2", b.r2);
        t.put("depth", b.depth);
        t.put("insurance", ins[0]);
        t.put("betting", tagsText(b.tags));
        if (playing != null) {
            t.put("playing", tagsText(playing));
        }
        if (laterDir != null) {
            t.put("later", tagsText(laterDir));
        }
        t.put("firstDecisions", first.size());
        t.put("laterSpots", spots.size());
        return t.part();
    }

    /**
     * CountRegression.combine's direction, or null where no direction has a gain above zero to
     * weight it by (no move or spot gains over the chart's).
     */
    static double[] combined(List<double[]> tags, List<Double> gains, double[] sign) {
        for (int k = 0; k < tags.size(); k++) {
            double n = Math.sqrt(CountSearch.dot(tags.get(k), tags.get(k)));
            if (n > 0 && gains.get(k) > 0) {
                return CountRegression.combine(tags, gains, sign);
            }
        }
        return null;
    }

    /** The angle in degrees between two directions of tags by rank. */
    static double degrees(double[] a, double[] b) {
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, CountSearch.cosine(a, b)))));
    }

    // ------------------------------------------------------------------ the effects of removal

    Part effectsOfRemoval(Part theory) throws Exception {
        Text t = new Text();
        EffectsOfRemoval.Effects e = EffectsOfRemoval.effects(StateRun.montreal(), s.eorK, s.threads);
        double[] tags = CountKeys.normalised(e.tags());
        double[] betting = parseTags(theory.s("betting"));
        t.println("## The effects of removal off the top");
        t.println("");
        t.printf("EffectsOfRemoval: the full 8-deck shoe valued under the chart, then with %d cards of each rank removed "
                + "and added, central differences.%n%n", s.eorK);
        EffectsOfRemoval.print(t.out, e);
        t.println("");
        t.printf("Off the top and on the states (the betting start) side by side, each centred and at Hi-Lo's spread; "
                + "their angle is %.1f degrees:%n%n", degrees(tags, betting));
        t.println("| Rank | Off the top | The betting start | Hi-Lo | Zen |");
        t.println("|---|---|---|---|---|");
        double[] hiLo = CountKeys.normalised(sign());
        double[] zen = CountKeys.normalised(CountSearch.rankTags(CountSearch.catalogCount(ZEN)));
        for (int r = 1; r <= 10; r++) {
            t.printf("| %s | %+.4f | %+.4f | %+.4f | %+.4f |%n", rank(r), tags[r], betting[r], hiLo[r], zen[r]);
        }
        t.println("");
        t.put("tags", tagsText(tags));
        t.put("full", e.full);
        return t.part();
    }

    // ------------------------------------------------------------------ the split margin

    /** n records spread evenly through the file, under the 80% cut. */
    static List<StateStore.Record> sample(Path file, int n) throws IOException {
        int records = StateStore.records(file);
        List<StateStore.Record> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int at = (int) ((long) k * records / n);
            StateStore.Record r = StateStore.read(file, at, 1).get(0);
            if (r.depth < SystemScorer.CUTS[P80]) {
                out.add(r);
            }
        }
        return out;
    }

    /** Each record's delta (SplitMargin.delta) on threads threads. */
    double[] deltas(List<StateStore.Record> sample) throws Exception {
        RoundRules rules = StateRun.montreal();
        double[] out = new double[sample.size()];
        ThreadLocal<ExactRound> engine = ThreadLocal.withInitial(() -> new ExactRound(rules));
        List<Callable<Double>> jobs = new ArrayList<>();
        for (StateStore.Record r : sample) {
            jobs.add(() -> SplitMargin.delta(rules, r, engine.get(), s.splitDeals).delta);
        }
        List<Double> v = parallel(s.threads, jobs);
        for (int k = 0; k < out.length; k++) {
            out[k] = v.get(k);
        }
        return out;
    }

    Part splitMargin() throws Exception {
        Text t = new Text();
        int n = s.splitStates;
        String sizing;
        if (n < 0) {
            int pilot = 2 * s.threads;
            long t0 = System.nanoTime();
            deltas(sample(s.states, pilot));
            double each = (System.nanoTime() - t0) / 1e9 / pilot;
            n = Math.max(pilot, (int) (s.splitMinutes * 60 / each));
            sizing = String.format(Locale.ROOT, "A pilot of %d states took %.1f s a state on %d threads, so the sample was "
                    + "sized to %d states, about %.0f minutes", pilot, each, s.threads, n, s.splitMinutes);
        } else {
            sizing = "The sample's size was given";
        }
        long t0 = System.nanoTime();
        List<StateStore.Record> sample = sample(s.states, n);
        double[] delta = deltas(sample);
        double seconds = (System.nanoTime() - t0) / 1e9;
        double[][] m = SplitMargin.summary(sample, delta);
        t.println("## The split margin");
        t.println("");
        t.printf("SplitMargin.delta on %d of the twin's states spread evenly through the file, %s. %s; it took %.0f s on "
                + "%d threads.%n%n", sample.size(), s.splitDeals == null ? "every pair deal of each"
                : "the pair deals " + Arrays.toString(s.splitDeals) + " alone", sizing, seconds, s.threads);
        SplitMargin.print(t.out, sample, delta);
        t.println("");
        t.printf("So a count with full play can beat the full-play perfect row by at least %.4f%% a round at 80%%, 1-%d "
                + "(the margin is a LOWER bound on what the bound needs: delta is measured from below), and any "
                + "full-play bound plus this margin is conditional on it.%n%n", 100 * SystemScorer.SPREADS * m[P80][0],
                SystemScorer.SPREADS);
        for (int p = 0; p < m.length; p++) {
            t.put("mean." + p, m[p][0]);
            t.put("se." + p, m[p][1]);
            t.put("states." + p, m[p][2]);
        }
        t.put("sample", sample.size());
        t.put("splitSeconds", seconds);
        return t.part();
    }

    // ------------------------------------------------------------------ the candidates file

    /**
     * The candidates file: the noise floor's counts at each grain they were scored at, the colour
     * check's rank average, the theory pass's starts at g and the controls, in CustomCounts' format.
     */
    String candidates(int d, int dr, Part noise0, Part noise, Part atG, Part theory, Part eor) {
        StringBuilder b = new StringBuilder("{ \"kind\": \"candidates\", \"K\": " + CountKeys.K + ",\n");
        b.append("  \"calibration\": \"").append(commit).append(", g ").append(grainText(d)).append(", D ").append(d)
                .append(", D_r ").append(dr).append("\",\n  \"counts\": [\n");
        List<String> entries = new ArrayList<>();
        List<Integer> grains = new ArrayList<>(Arrays.asList(noise0.i("divisor")));
        if (noise != noise0) {
            grains.add(noise.i("divisor"));
        }
        for (int g : grains) {
            for (CountKeys.Count c : noiseCounts(g, s.directions, s.directionSeed)) {
                entries.add(entry(c, "noise floor", "check"));
            }
        }
        entries.add(entry(rankAverage(dr), "colour check", "check"));
        String[][] starts = {{"betting start", theory.s("betting")}, {"playing start", theory.s("playing")},
                {"later-play direction", theory.s("later")}, {"effects of removal off the top", eor.s("tags")}};
        for (String[] x : starts) {
            if (x[1] != null) {
                int[] tags = CountKeys.rounded(CountKeys.normalised(parseTags(x[1])), CountKeys.K);
                entries.add(entry(CountKeys.whole(x[0], tags, null, d, true), "F1", "start"));
            }
        }
        for (int k = 0; k < atG.i("controls"); k++) {
            String system = atG.s("control." + k);
            int div = atG.i("controlDivisor." + k);
            entries.add("    { \"name\": \"" + system.replace("\"", "'") + " at g\", \"role\": \"control\", \"catalog\": \""
                    + system.replace("\"", "\\\"") + "\", \"divisor\": " + div + " }");
        }
        return b.append(String.join(",\n", entries)).append(" ] }\n").toString();
    }

    private static String entry(CountKeys.Count c, String family, String role) {
        int[] tags = new int[11];
        for (int r = 1; r <= 10; r++) {
            tags[r] = c.rankTag(r);
        }
        return "    { \"name\": \"" + c.name + "\", \"family\": \"" + family + "\", \"role\": \"" + role
                + "\", \"form\": \"" + (c.trueCounted ? "true" : "running") + "\", \"divisor\": " + c.divisor()
                + ", \"tags\": [" + CountSearch.tagText(tags) + "] }";
    }

    /** The candidates file read back: every count of our own scored here, its tags and divisor to the bit. */
    void checkCandidates(Path file, int d, int dr) throws IOException {
        CustomCounts.Listing l = CustomCounts.read(file);
        List<CountKeys.Count> scored = new ArrayList<>(noiseCounts(DEFAULT_DIVISOR, s.directions, s.directionSeed));
        if (d != DEFAULT_DIVISOR) {
            scored.addAll(noiseCounts(d, s.directions, s.directionSeed));
        }
        scored.add(rankAverage(dr));
        for (CountKeys.Count c : scored) {
            int i = l.indexOf(c.name);
            if (i < 0) {
                throw new IllegalStateException(file + " does not list " + c.name);
            }
            CountKeys.Count x = l.entries.get(i).count;
            if (!Arrays.equals(x.tag, c.tag) || x.divisor() != c.divisor() || x.trueCounted != c.trueCounted) {
                throw new IllegalStateException(file + " lists " + c.name + " other than it was scored");
            }
        }
    }

    // ------------------------------------------------------------------ the report

    String header(Part twin) throws IOException {
        return String.format(Locale.ROOT, "# Calibration and the theory pass%n%nCalibration %s on %s (header seed %d, %d "
                        + "shoes, %d valued states): COUNT_DESIGN.md, section 4 and section 8, step 3. Every figure below "
                        + "comes from the committed tools named beside it, run from these classes. %s.%n%n", commit,
                s.states, (long) twin.d("seed"), twin.i("shoes"), twin.i("valued"), s.text());
    }

    String summary(Part twin, Part proxy, Part grain, Part noise0, Part noise, Part timing, Part atG, Part colour,
                   Part theory, Part eor, Part split, Path candidates) {
        Text t = new Text();
        int d = grain.i("divisor");
        t.println("## Summary");
        t.println("");
        t.printf("- **The twin:** seed %d, %d shoes, %d valued states read back; %d shoe seeds shared with the main "
                + "file.%n", (long) twin.d("seed"), twin.i("shoes"), twin.i("valued"), twin.i("collisions"));
        t.printf("- **One evaluation** (F1, single-threaded): %.3f s; F2's %.3f s; a betting block's %.4f s; %d threads "
                        + "worth %.2f. The search budget of section 7: %.1f to %.1f machine hours.%n", timing.d("f1"),
                timing.d("f2"), timing.d("bet"), s.searchThreads, timing.d("speedup"), timing.d("hoursLow"),
                timing.d("hoursHigh"));
        t.printf("- **The proxy check:** %d of %d published differences from Hi-Lo within 2 (chance of so few under "
                        + "independence %.3g): %s.%n", proxy.i("within"), proxy.i("rows"), proxy.d("chance"),
                proxy.s("ok").equals("yes") ? "they agree within chance" : "THEY DISAGREE beyond chance");
        if (proxy.s("tie").equals("no")) {
            t.printf("- **The stored rows do NOT print report-final's headline to the digit** (the proxy check's "
                    + "section says where): step 7 refuses a look until the reason is found.%n");
        }
        t.printf("- **The noise floor** at g = %s, the smaller of the bootstrap SE and the sub-runs' scaled spread "
                + "(the two measures beside it):", grainText(noise.i("divisor")));
        for (double a : ANGLES) {
            t.printf(" %s %.4f%% (%.4f%%, %.4f%%);", degreesText(a), 100 * noise.d("floor." + a),
                    100 * noise.d("boot." + a), 100 * noise.d("sub." + a));
        }
        t.println("");
        if (noise != noise0) {
            t.printf("  At the default g = %s, where g was chosen: 1 degree %.4f%%.%n", grainText(DEFAULT_DIVISOR),
                    100 * noise0.d("floor." + ANGLES[0]));
        }
        t.printf("- **g = %s** (D = %d)%s; the running divisor D_r = %d at the median depth %d.%n", grainText(d), d,
                grain.s("finest").equals("yes") ? ", the finest tried" : "", grain.i("runningDivisor"),
                grain.i("medianDepth"));
        t.printf("- **The best published rows at g:** ");
        for (int k = 0; k < 5 && atG.s("top." + k) != null; k++) {
            t.printf("%s%s %+.4f%% (own grain %+.4f%%)", k == 0 ? "" : "; ", atG.s("top." + k), 100 * atG.d("topAtG." + k),
                    100 * atG.d("topOwn." + k));
        }
        List<String> controls = new ArrayList<>();
        for (int k = 0; k < atG.i("controls"); k++) {
            controls.add(atG.s("control." + k));
        }
        t.printf(". The controls: %s.%n", String.join("; ", controls));
        t.printf("- **The colour check:** Red Seven less its rank average %+.4f%% [%+.4f%%, %+.4f%%]: %s.%n",
                100 * colour.d("diff"), 100 * colour.d("lo"), 100 * colour.d("hi"), colour.s("better").equals("yes")
                        ? "better beyond noise, so section 1's argument is NOT borne out" : "not better beyond noise");
        t.printf("- **R** = %.5f, the ceiling of any linear count's BC; the depth figure %.6f; the insurance check 1 - R "
                + "squared = %.2e.%n", theory.d("r"), theory.d("depth"), theory.d("insurance"));
        t.printf("- **The effects of removal off the top**, as tags: %s.%n", CountRegression.text(parseTags(eor.s("tags"))));
        t.printf("- **The starts:** betting %s; playing %s%s.%n", CountRegression.text(parseTags(theory.s("betting"))),
                theory.s("playing") == null ? "none" : CountRegression.text(parseTags(theory.s("playing"))),
                theory.s("later") == null ? ""
                        : "; the later-play direction " + CountRegression.text(parseTags(theory.s("later"))));
        t.printf("- **The split margin** at 80%%, 1-%d: at least %.4f%% a round (mean delta %.5f%%, SE %.5f%%, over %d "
                        + "states): a LOWER bound.%n", SystemScorer.SPREADS, 100 * SystemScorer.SPREADS * split.d("mean." + P80),
                100 * split.d("mean." + P80), 100 * split.d("se." + P80), split.i("states." + P80));
        t.printf("- **The candidates file:** %s.%n%n", candidates);
        return t.part().text;
    }
}
