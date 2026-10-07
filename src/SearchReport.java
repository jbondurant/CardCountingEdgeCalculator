import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Steps 4 and 5 of COUNT_DESIGN.md (section 8) around the searches themselves, on the twin file
 * alone: what the searches start from, the evidence that they found the maximum (section 4, "How
 * we know it found the maximum, or how close"), the counts they found as a candidates file, the
 * full-play candidates, and the full-play choice. Every figure is CountSearch's objective,
 * SystemScorer.estimate at 80%, or read from the searches' own logs.
 *
 * Its modes:
 * - starts: every published count's direction (kind-aware counts by their rank averages, running
 *   ones by their balanced part), scored once as the search scores a candidate, at the 1-8 same
 *   schedule and flat; the eight best by 1-8 are F1's published starts and the best by flat is F1
 *   flat's. Given F1's optimum, the flat optimum and the theory pass's betting direction, F2's
 *   starts too: (u, u) at F1's optimum, each published side-count pair (its count's tags for play,
 *   the same with an ace tag of -m for bets), and the flat optimum with the betting direction, each
 *   scored as F2's search scores its start; the best three are F2's.
 * - report: reads every search log in a directory, sorts the searches into their families by
 *   their headers (F1 at 1-8, F1 flat, F2, F1r, F1 on each half of the twin), and writes the
 *   convergence evidence: restarts agree, slices peak at the optimum, every start is beaten, the
 *   grain does not decide it, and the half-twin counts; and the counts found, each family's best,
 *   as a candidates file read back to the bit. Given the later-play direction, it also writes the
 *   full-play candidates: five F1 counts on the line from the 1-8 optimum to the flat optimum at
 *   0, 1/4, 1/2, 3/4 and all of the way (renormalised), two turned from the 1-8 optimum toward the
 *   later-play direction by 10 and 20 degrees, and F2's 1-8 optimum.
 * - fullplay: reads a full-index run's results.ser of those candidates and gives each one's 80%
 *   figures at first decisions and with full play; the best of the F1 counts by full-play 1-8 EV
 *   is the full-play F1.
 *
 * java -Xmx8g -cp CLASSES:lib/mongo-java-driver-3.12.14.jar SearchReport starts --states TWIN
 *   --divisor 500 [--threads 6] [--f1 TAGS --flat TAGS --betting TAGS]
 * java ... SearchReport report --states TWIN --logs DIR --floor NOISE.properties --divisor 500
 *   --running-divisor 2423 --betting TAGS --out FILE.md --candidates FILE.json [--eor TAGS]
 *   [--later TAGS --fullplay FILE.json] [--queue-log FILE] [--threads 6]
 * java ... SearchReport fullplay --results RESULTS.ser
 */
final class SearchReport {

    static final int P80 = SystemScorer.CUTS.length - 1;
    static final int SAME8 = SystemScorer.same(SystemScorer.SPREADS);
    static final int FLAT = SystemScorer.FLAT;
    /** The M_play coefficient on a half of the twin, so its groups close at the full twin's size (section 4). */
    static final double HALF_M_PLAY = SystemScorer.M_PLAY * Math.sqrt(2);
    /** The angles the calibration measured the noise floor at, in degrees. */
    static final double[] FLOOR_ANGLES = {1, 2, 5, 10, 30};
    /** "Within a few degrees" (section 4): runs this close to each other are one region. */
    static final double FEW_DEGREES = 5;
    /** Slices run this far either side of the optimum, in degrees, at one point a degree. */
    static final double SLICE_DEGREES = 10;
    static final int SLICE_POINTS = 21;
    /** How many of the best published counts F1 starts from, and the slices run toward. */
    static final int TOP = 8;
    /** F2's search runs from this many of its starts. */
    static final int F2_STARTS = 3;
    /**
     * The grain check's divisors, as multiples of D: double the grain (2 D, g = 1/10 at D = 500),
     * half of it (g = 1/40), and the calibration ladder's finer grains, 1/100 and 1/200.
     */
    static final double[] GRAIN_LADDER = {2, 0.5, 0.2, 0.1};
    /** The half-twin counts are far apart below this cosine (section 6). */
    static final double HALF_COSINE = 0.95;

    private SearchReport() {
    }

    // ------------------------------------------------------------------ the noise floor

    /** The noise floor the calibration measured, by angle, as a fraction of a unit a round. */
    static final class Floor {
        final double[] at;

        Floor(double[] at) {
            if (at.length != FLOOR_ANGLES.length) {
                throw new IllegalArgumentException("a floor at each of " + Arrays.toString(FLOOR_ANGLES) + " degrees");
            }
            this.at = at.clone();
        }

        /** The floor at an angle in degrees: linear between the angles measured, the nearest one's outside them. */
        double at(double degrees) {
            double a = Math.abs(degrees);
            if (a <= FLOOR_ANGLES[0]) {
                return at[0];
            }
            for (int k = 1; k < FLOOR_ANGLES.length; k++) {
                if (a <= FLOOR_ANGLES[k]) {
                    double w = (a - FLOOR_ANGLES[k - 1]) / (FLOOR_ANGLES[k] - FLOOR_ANGLES[k - 1]);
                    return at[k - 1] + w * (at[k] - at[k - 1]);
                }
            }
            return at[at.length - 1];
        }

        /** The calibration's noise-floor part (04-noise-D.properties): floor.1.0, floor.2.0 and so on. */
        static Floor read(Path properties) throws IOException {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(properties, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            double[] at = new double[FLOOR_ANGLES.length];
            for (int k = 0; k < at.length; k++) {
                String v = p.getProperty("floor." + FLOOR_ANGLES[k]);
                if (v == null) {
                    throw new IllegalArgumentException(properties + " holds no floor." + FLOOR_ANGLES[k]);
                }
                at[k] = Double.parseDouble(v);
            }
            return new Floor(at);
        }
    }

    // ------------------------------------------------------------------ directions and objectives

    /** A direction of tags by rank and what it scores. */
    static final class Direction {
        final String name;
        final double[] tags;
        double same8 = Double.NaN;
        double flat = Double.NaN;

        Direction(String name, double[] tags) {
            this.name = name;
            this.tags = tags;
        }
    }

    /** The search's settings for an objective: the sign reference Hi-Lo, as the search takes it by default. */
    static CountSearch.Settings settings(String form, int metric, int divisor) {
        CountSearch.Settings s = new CountSearch.Settings();
        s.form = form;
        s.metric = metric;
        s.divisor = divisor;
        s.sign = Calibration.sign();
        return s;
    }

    /** A direction's coordinates on the sphere, as the search starts from it. */
    static double[] point(double[] tags) {
        return CountSearch.onSphere(CountSearch.coordinates(tags));
    }

    /**
     * What a direction scores as the search scores a candidate (CountSearch.whole: normalised,
     * signed by Hi-Lo, whole at K), at 80%: {1-8 same schedule, flat}, from one estimate.
     */
    static double[] score(SystemScorer sc, double[] tags, int divisor) {
        CountKeys.Count c = CountKeys.whole("direction", CountSearch.whole(point(tags), Calibration.sign()), null, divisor,
                true);
        double[] e = sc.estimate(c, null, P80)[0];
        return new double[]{e[SAME8], e[FLAT]};
    }

    /** Whole tags at K as they stand, a true count at divisor d: {1-8 same schedule, flat} at 80%. */
    static double[] scoreWhole(SystemScorer sc, int[] tags, int d, boolean trueCounted) {
        double[] e = sc.estimate(CountKeys.whole("count", tags, null, d, trueCounted), null, P80)[0];
        return new double[]{e[SAME8], e[FLAT]};
    }

    /** Every catalog entry's direction by rank (rank averages for a kind-aware count), in the catalog's order. */
    static List<Direction> published() {
        List<Direction> out = new ArrayList<>();
        for (CountKeys.Count c : CountKeys.catalog()) {
            out.add(new Direction(c.name, CountSearch.rankTags(c)));
        }
        return out;
    }

    /** Scores each direction, on threads threads. */
    static void scoreAll(SystemScorer sc, List<Direction> ds, int divisor, int threads) throws Exception {
        List<Callable<double[]>> jobs = new ArrayList<>();
        for (Direction d : ds) {
            jobs.add(() -> score(sc, d.tags, divisor));
        }
        List<double[]> v = Calibration.parallel(threads, jobs);
        for (int k = 0; k < ds.size(); k++) {
            ds.get(k).same8 = v.get(k)[0];
            ds.get(k).flat = v.get(k)[1];
        }
    }

    /** The directions sorted by a metric, best first: 0 for 1-8, 1 for flat. */
    static List<Direction> best(List<Direction> ds, int metric) {
        List<Direction> out = new ArrayList<>(ds);
        out.sort(Comparator.comparingDouble((Direction d) -> metric == 0 ? d.same8 : d.flat).reversed());
        return out;
    }

    // ------------------------------------------------------------------ F2's starts

    /** A play vector and a betting vector, and what F2 scores there. */
    static final class Pair {
        final String name;
        final double[] play;
        final double[] bet;
        double value = Double.NaN;

        Pair(String name, double[] play, double[] bet) {
            this.name = name;
            this.play = play;
            this.bet = bet;
        }
    }

    /**
     * A side-count pair's betting vector: the count's tags with an ace tag of -m, m the ten's tag
     * in absolute value, as CountKeys.sideCounted sets it, which is the side-count variant's bet key
     * to the bit (section 1, F2).
     */
    static double[] withAceSideCount(double[] rankTag) {
        if (rankTag[1] != 0) {
            throw new IllegalArgumentException("an ace side count goes with an ace-neutral count");
        }
        double[] out = rankTag.clone();
        out[1] = -Math.abs(rankTag[10]);
        return out;
    }

    /** F2's starts (section 4): (u, u) at F1's optimum, each published side-count pair, the flat optimum with the betting direction. */
    static List<Pair> f2Starts(double[] f1, double[] flat, double[] betting) {
        List<Pair> out = new ArrayList<>();
        out.add(new Pair("F1's optimum for both", f1, f1));
        for (CountKeys.Count c : CountKeys.catalog()) {
            if (CountKeys.sideCountVariant(c) != null) {
                double[] t = CountSearch.rankTags(c);
                out.add(new Pair(c.name + " with its ace side count", t, withAceSideCount(t)));
            }
        }
        out.add(new Pair("the flat optimum for play, the betting direction for bets", flat, betting));
        return out;
    }

    // ------------------------------------------------------------------ F1r's starts

    /** KO's shift relative to its balanced part: its p0-weighted mean tag over the root of its spread. */
    static double koShift() {
        double[] ko = CountSearch.rankTags(CountSearch.catalogCount("KO (Knock-Out)"));
        return CountKeys.mean(ko) / Math.sqrt(CountKeys.spread(ko));
    }

    /**
     * F1r's two starts from F1's optimum (section 4): its tags, centred, with a shift of k times
     * KO's relative to the balanced part's spread added to every tag, for each k given.
     */
    static double[][] f1rStarts(double[] f1, double... k) {
        double m = CountKeys.mean(f1);
        double root = Math.sqrt(CountKeys.spread(f1));
        double[][] out = new double[k.length][11];
        for (int j = 0; j < k.length; j++) {
            for (int r = 1; r <= 10; r++) {
                out[j][r] = f1[r] - m + k[j] * koShift() * root;
            }
        }
        return out;
    }

    /** What F2's search scores at a start: each vector normalised, signed and whole at K, as searchF2 scores it. */
    static double f2Value(SystemScorer sc, int divisor, double[] play, double[] bet) {
        CountSearch.Settings s = settings("F2", SAME8, divisor);
        int[] p = CountSearch.whole(point(play), s.sign);
        int[] b = CountSearch.whole(point(bet), s.sign);
        double[] x = new double[18];
        System.arraycopy(point(toDouble(p)), 0, x, 0, 9);
        System.arraycopy(point(toDouble(b)), 0, x, 9, 9);
        return CountSearch.f2Joint(sc, s).of(x);
    }

    static double[] toDouble(int[] t) {
        double[] out = new double[t.length];
        for (int k = 0; k < t.length; k++) {
            out[k] = t[k];
        }
        return out;
    }

    // ------------------------------------------------------------------ the logs

    /** One search, as its log has it. */
    static final class Run {
        Path log;
        String header;
        String name;
        String form;
        int metric;
        int pen;
        int divisor;
        int lambda;
        int maxEvals;
        int half = -1;
        double mPlay;
        double[] start;
        double[] startBet;
        /** The count found: its play tags and, for F2, its betting tags, whole at K by rank. */
        int[] play;
        int[] bet;
        double value = Double.NaN;
        /** Evaluations logged, every block's of an F2 search. */
        int evaluations;
        /** F2's start as its log notes it. */
        double startValue = Double.NaN;
        /** Whether the search has ended (its found line is written). */
        boolean done;

        /** Its family: F1, F1 flat, F2, F1r, F1 half 0, F1 half 1. */
        String family() {
            if (form.equals("F1")) {
                if (half >= 0) {
                    return "F1 half " + half;
                }
                return metric == FLAT ? "F1 flat" : "F1";
            }
            return form;
        }
    }

    static final Pattern HEADER = Pattern.compile("^# CountSearch (\\S+), .*?(, half (\\d+))?, (\\d+) valued states; form (\\S+), "
            + "metric (\\d+), penetration (\\d+), divisor (\\d+), M_play ([0-9.]+), lambda (\\d+), sigma ([0-9.Ee-]+), "
            + "max evaluations (\\d+), .*?, run (.+?), start \\[([^\\]]*)\\](, betting start \\[([^\\]]*)\\])?$");

    /** Reads one search's log. */
    static Run read(Path log) throws IOException {
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException(log + " is empty");
        }
        Matcher m = HEADER.matcher(lines.get(0));
        if (!m.matches()) {
            throw new IllegalArgumentException(log + " does not begin with a CountSearch header: " + lines.get(0));
        }
        Run r = new Run();
        r.log = log;
        r.header = lines.get(0);
        r.half = m.group(3) == null ? -1 : Integer.parseInt(m.group(3));
        r.form = m.group(5);
        r.metric = Integer.parseInt(m.group(6));
        r.pen = Integer.parseInt(m.group(7));
        r.divisor = Integer.parseInt(m.group(8));
        r.mPlay = Double.parseDouble(m.group(9));
        r.lambda = Integer.parseInt(m.group(10));
        r.maxEvals = Integer.parseInt(m.group(12));
        r.name = m.group(13);
        r.start = array(m.group(14));
        r.startBet = m.group(16) == null ? null : array(m.group(16));
        for (String l : lines) {
            String[] f = l.split("\t", -1);
            switch (f[0]) {
                case "eval":
                    r.evaluations++;
                    break;
                case "found":
                    if (f[1].equals(r.name)) {
                        String[] v = f[4].split("\\|");
                        r.play = byRank(v[0]);
                        r.bet = v.length > 1 ? byRank(v[1]) : null;
                        r.value = Double.parseDouble(f[5]);
                        r.done = true;
                    }
                    break;
                case "note":
                    if (f[1].equals(r.name) && f[2].startsWith("start ")) {
                        r.startValue = Double.parseDouble(f[2].split(" ")[1]);
                    }
                    break;
                default:
                    break;
            }
        }
        return r;
    }

    /** Every search log (*.log, a CountSearch header first) in a directory, by name. */
    static List<Run> runs(Path dir) throws IOException {
        List<Run> out = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".log")).sorted().forEach(files::add);
        }
        for (Path p : files) {
            List<String> first = Files.readAllLines(p, StandardCharsets.UTF_8);
            if (!first.isEmpty() && first.get(0).startsWith("# CountSearch ")) {
                out.add(read(p));
            }
        }
        return out;
    }

    static double[] array(String text) {
        String[] p = text.split(",");
        double[] out = new double[p.length];
        for (int k = 0; k < p.length; k++) {
            out[k] = Double.parseDouble(p[k].trim());
        }
        return out;
    }

    static int[] byRank(String text) {
        String[] p = text.split(",");
        if (p.length != 10) {
            throw new IllegalArgumentException("ten tags: " + text);
        }
        int[] out = new int[11];
        for (int r = 1; r <= 10; r++) {
            out[r] = Integer.parseInt(p[r - 1].trim());
        }
        return out;
    }

    /** The angle in degrees between two counts' tags by rank, each centred. */
    static double degrees(int[] a, int[] b) {
        return Calibration.degrees(toDouble(a), toDouble(b));
    }

    /** The runs of a family, done, best first. */
    static List<Run> family(List<Run> runs, String family) {
        List<Run> out = new ArrayList<>();
        for (Run r : runs) {
            if (r.done && r.family().equals(family)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingDouble((Run r) -> r.value).reversed());
        return out;
    }

    // ------------------------------------------------------------------ restarts and regions

    /**
     * The runs' regions: runs whose counts lie within FEW_DEGREES of each other, joined link by
     * link, each region best first, the regions in the order of their bests.
     */
    static List<List<Run>> regions(List<Run> runs) {
        int n = runs.size();
        int[] region = new int[n];
        Arrays.fill(region, -1);
        int next = 0;
        for (int i = 0; i < n; i++) {
            if (region[i] >= 0) {
                continue;
            }
            region[i] = next;
            List<Integer> todo = new ArrayList<>();
            todo.add(i);
            while (!todo.isEmpty()) {
                int a = todo.remove(todo.size() - 1);
                for (int b = 0; b < n; b++) {
                    if (region[b] < 0 && degrees(runs.get(a).play, runs.get(b).play) <= FEW_DEGREES) {
                        region[b] = next;
                        todo.add(b);
                    }
                }
            }
            next++;
        }
        List<List<Run>> out = new ArrayList<>();
        for (int g = 0; g < next; g++) {
            List<Run> members = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (region[i] == g) {
                    members.add(runs.get(i));
                }
            }
            members.sort(Comparator.comparingDouble((Run r) -> r.value).reversed());
            out.add(members);
        }
        out.sort(Comparator.comparingDouble((List<Run> g) -> g.get(0).value).reversed());
        return out;
    }

    /**
     * Whether the regions differ by more than noise: some region's best lies below the best of all
     * by more than the noise floor at the angle between them. Then each region's best gets one
     * more run at double the population and double the cap (section 4).
     */
    static boolean separate(List<List<Run>> regions, Floor floor) {
        Run best = regions.get(0).get(0);
        for (int g = 1; g < regions.size(); g++) {
            Run r = regions.get(g).get(0);
            if (best.value - r.value > floor.at(degrees(best.play, r.play))) {
                return true;
            }
        }
        return false;
    }

    /** Whether a run agrees with the best: within the noise floor at their angle, and within FEW_DEGREES. */
    static boolean agrees(Run r, Run best, Floor floor) {
        double a = degrees(r.play, best.play);
        return best.value - r.value <= floor.at(a) && a <= FEW_DEGREES;
    }

    // ------------------------------------------------------------------ slices

    /** A slice through the optimum and its quadratic: {a, b, c, peak (radians), headroom}. */
    static final class Slice {
        final String direction;
        final double[][] points;
        final double[] quadratic;

        Slice(String direction, double[][] points) {
            this.direction = direction;
            this.points = points;
            this.quadratic = CountSearch.quadratic(points);
        }

        double peakDegrees() {
            return Math.toDegrees(quadratic[3]);
        }

        /** Whether the slice peaks at the optimum within noise: it curves down, and the headroom its quadratic gives is under the floor at its peak. */
        boolean peaks(Floor floor) {
            return quadratic[2] < 0 && quadratic[4] <= floor.at(peakDegrees());
        }

        /** The most a sampled point gains over the slice's centre, and its angle in degrees. */
        double[] bestPoint() {
            double centre = Double.NaN;
            double nearest = Double.POSITIVE_INFINITY;
            for (double[] p : points) {
                if (Math.abs(p[0]) < nearest) {
                    nearest = Math.abs(p[0]);
                    centre = p[1];
                }
            }
            double gain = Double.NEGATIVE_INFINITY;
            double at = 0;
            for (double[] p : points) {
                if (p[1] - centre > gain) {
                    gain = p[1] - centre;
                    at = Math.toDegrees(p[0]);
                }
            }
            return new double[]{gain, at};
        }

        /**
         * The angle in degrees within which the quadratic loses less than the noise floor: the
         * floor taken at that angle, found by iterating from the floor at 1 degree.
         */
        double indistinguishable(Floor floor) {
            double c = quadratic[2];
            if (!(c < 0)) {
                return Double.NaN;
            }
            double a = Math.toDegrees(Math.sqrt(floor.at(1) / -c));
            for (int k = 0; k < 20; k++) {
                a = Math.toDegrees(Math.sqrt(floor.at(a) / -c));
            }
            return a;
        }
    }

    /** The slice's directions: the nine basis directions, the run's principal axes, and toward each of the top published counts. */
    static List<Slice> slices(CountSearch.Objective f, double[] centre, double[][] axes, List<Direction> toward, int threads)
            throws Exception {
        List<String> names = new ArrayList<>();
        List<double[]> dirs = new ArrayList<>();
        for (int k = 0; k < 9; k++) {
            double[] e = new double[9];
            e[k] = 1;
            names.add("basis " + (k + 1));
            dirs.add(e);
        }
        for (int k = 0; k < axes.length; k++) {
            names.add("principal axis " + (k + 1));
            dirs.add(axes[k]);
        }
        for (Direction d : toward) {
            names.add("toward " + d.name);
            dirs.add(point(d.tags));
        }
        List<Callable<Slice>> jobs = new ArrayList<>();
        for (int k = 0; k < dirs.size(); k++) {
            String name = names.get(k);
            double[] dir = dirs.get(k);
            jobs.add(() -> {
                double[][] pts = CountSearch.slice(f, centre, dir, Math.toRadians(SLICE_DEGREES), SLICE_POINTS);
                return pts.length == 0 ? null : new Slice(name, pts);
            });
        }
        List<Slice> out = new ArrayList<>();
        for (Slice s : Calibration.parallel(threads, jobs)) {
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    /** A run's principal axes at its end, from its last logged state (Cma.axes), longest first. */
    static double[][] axes(Run r) throws IOException {
        Cma.State s = CountSearch.SearchLog.lastState(r.log, r.name);
        if (s == null) {
            return new double[0][];
        }
        Cma cma = new Cma(s.mean.length, r.lambda, 1, s.mean, s.sigma, CountSearch.RADIUS);
        cma.restore(s);
        return cma.axes();
    }

    // ------------------------------------------------------------------ the full-play candidates

    /** Points on the sphere from a to b at a share t of the way, renormalised, as tags whole at K. */
    static int[] between(int[] a, int[] b, double t) {
        double[] x = point(toDouble(a));
        double[] y = point(toDouble(b));
        double[] z = new double[9];
        for (int k = 0; k < 9; k++) {
            z[k] = (1 - t) * x[k] + t * y[k];
        }
        return CountSearch.whole(CountSearch.onSphere(z), Calibration.sign());
    }

    /** The count a turned from c toward a direction by an angle in degrees, on the great circle through them, whole at K. */
    static int[] turned(int[] c, double[] toward, double degrees) {
        double[] x = point(toDouble(c));
        double[] u = point(toward);
        double xu = 0;
        double xx = 0;
        for (int k = 0; k < 9; k++) {
            xu += x[k] * u[k];
            xx += x[k] * x[k];
        }
        double[] d = new double[9];
        for (int k = 0; k < 9; k++) {
            d[k] = u[k] - xu / xx * x[k];
        }
        d = CountSearch.onSphere(d);
        double r = Math.toRadians(degrees);
        double[] z = new double[9];
        for (int k = 0; k < 9; k++) {
            z[k] = Math.cos(r) * x[k] + Math.sin(r) * d[k];
        }
        return CountSearch.whole(z, Calibration.sign());
    }

    /** One count of a candidates file. */
    static final class Candidate {
        final String name;
        final String family;
        final String form;
        final int divisor;
        final int[] play;
        final int[] bet;
        final double objective;

        Candidate(String name, String family, String form, int divisor, int[] play, int[] bet, double objective) {
            this.name = name;
            this.family = family;
            this.form = form;
            this.divisor = divisor;
            this.play = play;
            this.bet = bet;
            this.objective = objective;
        }
    }

    /**
     * The full-play candidates (section 4, "Full play: choosing on the twin"): five F1 counts on the
     * line from the 1-8 optimum to the flat optimum, at 0, 1/4, 1/2, 3/4 and all of the way, the
     * ends the optima as found and the rest renormalised; two turned from the 1-8 optimum toward
     * the later-play direction by 10 and 20 degrees; and F2's 1-8 optimum as it is.
     */
    static List<Candidate> fullPlay(int[] f1, int[] flat, double[] later, int[] f2Play, int[] f2Bet, int divisor) {
        List<Candidate> out = new ArrayList<>();
        String[] share = {"0", "1/4", "1/2", "3/4", "1"};
        double[] t = {0, 0.25, 0.5, 0.75, 1};
        for (int k = 0; k < t.length; k++) {
            int[] tags = k == 0 ? f1 : k == t.length - 1 ? flat : between(f1, flat, t[k]);
            String what = k == 0 ? " (the 1-8 optimum)" : k == t.length - 1 ? " (the flat optimum)" : "";
            out.add(new Candidate("F1 full-play choice: " + share[k] + " of the way to the flat optimum" + what,
                    "F1 full play", "true", divisor, tags, null, Double.NaN));
        }
        for (double a : new double[]{10, 20}) {
            out.add(new Candidate(String.format(Locale.ROOT, "F1 full-play choice: %.0f degrees toward later play", a),
                    "F1 full play", "true", divisor, turned(f1, later, a), null, Double.NaN));
        }
        out.add(new Candidate("F2 at 1-8, full play", "F2 full play", "true", divisor, f2Play, f2Bet, Double.NaN));
        return out;
    }

    /** A candidates file (CustomCounts' format) of these counts. */
    static String candidatesText(List<Candidate> cs, String note) {
        StringBuilder b = new StringBuilder("{ \"kind\": \"candidates\", \"K\": " + CountKeys.K + ",\n  \"search\": \""
                + note.replace("\"", "'") + "\",\n  \"counts\": [\n");
        for (int i = 0; i < cs.size(); i++) {
            Candidate c = cs.get(i);
            b.append("    { \"name\": \"").append(c.name.replace("\"", "'")).append("\", \"family\": \"").append(c.family)
                    .append("\", \"role\": \"candidate\", \"form\": \"").append(c.form).append("\", \"divisor\": ")
                    .append(c.divisor);
            if (!Double.isNaN(c.objective)) {
                b.append(", \"objective\": ").append(c.objective);
            }
            b.append(",\n      \"tags\": [").append(CountSearch.tagText(c.play)).append("]");
            if (c.bet != null) {
                b.append(",\n      \"betting\": [").append(CountSearch.tagText(c.bet)).append("]");
            }
            b.append(" }").append(i + 1 < cs.size() ? ",\n" : "\n");
        }
        return b.append("  ] }\n").toString();
    }

    /** Writes a candidates file, refusing one that exists, and reads it back: every count's tags to the bit. */
    static void writeCandidates(Path file, List<Candidate> cs, String note) throws IOException {
        if (Files.exists(file)) {
            throw new IllegalStateException(file + " exists; write the candidates to a new file");
        }
        Files.write(file, candidatesText(cs, note).getBytes(StandardCharsets.UTF_8));
        CustomCounts.Listing l = CustomCounts.read(file);
        if (l.entries.size() != cs.size()) {
            throw new IllegalStateException(file + " reads back " + l.entries.size() + " counts, not " + cs.size());
        }
        for (int i = 0; i < cs.size(); i++) {
            CountKeys.Count c = l.entries.get(i).count;
            Candidate want = cs.get(i);
            if (!Arrays.equals(c.tag, CountKeys.byKind(want.play)) || c.divisor() != want.divisor
                    || c.trueCounted != want.form.equals("true")
                    || !Arrays.equals(c.betVector, want.bet == null ? null : CountKeys.byKind(want.bet))) {
                throw new IllegalStateException(file + ": " + want.name + " does not read back as written");
            }
        }
    }

    // ------------------------------------------------------------------ text

    static String pct(double v) {
        return String.format(Locale.ROOT, "%+.4f%%", 100 * v);
    }

    static String pts(double v) {
        return String.format(Locale.ROOT, "%.4f%%", 100 * v);
    }

    static String deg(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** Whole tags at K as real tags at Hi-Lo's spread, A, 2 to 9, T. */
    static String tagsText(int[] t) {
        StringBuilder b = new StringBuilder();
        for (int r = 1; r <= 10; r++) {
            b.append(r == 1 ? "" : ", ").append(String.format(Locale.ROOT, "%+.4f", t[r] / (double) CountKeys.K));
        }
        return b.toString();
    }

    static String tagsText(double[] t) {
        double[] n = CountKeys.normalised(t);
        StringBuilder b = new StringBuilder();
        for (int r = 1; r <= 10; r++) {
            b.append(r == 1 ? "" : ", ").append(String.format(Locale.ROOT, "%+.4f", n[r]));
        }
        return b.toString();
    }

    static String verdict(boolean ok) {
        return ok ? "holds" : "FAILS";
    }

    // ------------------------------------------------------------------ the command

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length == 0) {
            throw new IllegalArgumentException("SearchReport starts | report | fullplay ...");
        }
        Map<String, String> o = new LinkedHashMap<>();
        for (int a = 1; a < args.length; a++) {
            if (!args[a].startsWith("--") || a + 1 >= args.length) {
                throw new IllegalArgumentException("options come as --name value: " + args[a]);
            }
            o.put(args[a].substring(2), args[++a]);
        }
        switch (args[0]) {
            case "starts":
                starts(o, System.out);
                break;
            case "report":
                report(o, System.out);
                break;
            case "fullplay":
                fullplayChoice(o, System.out);
                break;
            default:
                throw new IllegalArgumentException("the modes are starts, report and fullplay, not " + args[0]);
        }
    }

    static String need(Map<String, String> o, String name) {
        String v = o.get(name);
        if (v == null) {
            throw new IllegalArgumentException("--" + name + " is needed");
        }
        return v;
    }

    /** The twin's states, refusing any other file. */
    static ScoringStates twin(Path states) throws IOException {
        CustomCounts.requireTwin(states);
        long seed = StateStore.header(states).seed;
        if (seed != CustomCounts.TWIN_SEED) {
            throw new IllegalStateException(states + " has header seed " + seed + ", not the twin's, "
                    + CustomCounts.TWIN_SEED);
        }
        return FullIndexPilot.load(states, StateStore.shoesDone(states));
    }

    // ------------------------------------------------------------------ starts

    static void starts(Map<String, String> o, PrintStream out) throws Exception {
        ScoringStates st = twin(Paths.get(need(o, "states")));
        int d = Integer.parseInt(need(o, "divisor"));
        int threads = Integer.parseInt(o.getOrDefault("threads", "6"));
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, 0, Bootstrap.SEED), SystemScorer.M_PLAY);
        out.print(startsText(sc, d, threads, o.containsKey("f1") ? CountSearch.parseTags(o.get("f1")) : null,
                o.containsKey("flat") ? CountSearch.parseTags(o.get("flat")) : null,
                o.containsKey("betting") ? CountSearch.parseTags(o.get("betting")) : null));
    }

    /** The starts' text: every published direction scored, F1's and F1 flat's starts, and given the optima, F2's. */
    static String startsText(SystemScorer sc, int d, int threads, double[] f1, double[] flat, double[] betting)
            throws Exception {
        StringBuilder b = new StringBuilder();
        List<Direction> pub = published();
        scoreAll(sc, pub, d, threads);
        b.append(String.format(Locale.ROOT, "Every published count's direction (rank averages for a kind-aware count; "
                + "a running count's balanced part), scored as the search scores a candidate, true-counted at divisor "
                + "%d, 80%%:%n%n| Count | 1-8 same schedule | Flat |%n|---|---|---|%n", d));
        for (Direction x : best(pub, 0)) {
            b.append("| ").append(x.name).append(" | ").append(pct(x.same8)).append(" | ").append(pct(x.flat))
                    .append(" |\n");
        }
        b.append("\nF1's published starts, the ").append(TOP).append(" best by 1-8:\n");
        List<Direction> top = best(pub, 0).subList(0, TOP);
        for (Direction x : top) {
            b.append("start\tF1\t").append(x.name).append('\t').append(Calibration.tagsText(x.tags)).append('\t')
                    .append(x.same8).append('\n');
        }
        Direction flatBest = best(pub, 1).get(0);
        b.append("F1 flat's published start, the best by flat:\nstart\tF1 flat\t").append(flatBest.name).append('\t')
                .append(Calibration.tagsText(flatBest.tags)).append('\t').append(flatBest.flat).append('\n');
        if (f1 != null) {
            double[] k = {0.5, 1.5};
            double[][] r = f1rStarts(f1, k);
            b.append(String.format(Locale.ROOT, "%nF1r's starts from F1's optimum, shifted by %s and %s times KO's relative "
                    + "shift, %.6f, and from KO's own direction and balance:%n", k[0], k[1], koShift()));
            for (int j = 0; j < k.length; j++) {
                b.append("start\tF1r\tF1's optimum, shift ").append(k[j]).append(" KO's\t")
                        .append(Calibration.tagsText(r[j])).append('\n');
            }
            b.append("start\tF1r\tKO\t").append(Calibration.tagsText(CountSearch.rankTags(
                    CountSearch.catalogCount("KO (Knock-Out)")))).append('\n');
        }
        if (f1 != null && flat != null && betting != null) {
            List<Pair> pairs = f2Starts(f1, flat, betting);
            List<Callable<Double>> jobs = new ArrayList<>();
            for (Pair p : pairs) {
                jobs.add(() -> f2Value(sc, d, p.play, p.bet));
            }
            List<Double> v = Calibration.parallel(threads, jobs);
            for (int k = 0; k < pairs.size(); k++) {
                pairs.get(k).value = v.get(k);
            }
            pairs.sort(Comparator.comparingDouble((Pair p) -> p.value).reversed());
            b.append("\nF2's starts, each scored as F2's search scores its start, at 80%, 1-8:\n\n| Start | F2's objective |\n"
                    + "|---|---|\n");
            for (Pair p : pairs) {
                b.append("| ").append(p.name).append(" | ").append(pct(p.value)).append(" |\n");
            }
            b.append("\nF2's search runs from the best ").append(F2_STARTS).append(":\n");
            for (Pair p : pairs.subList(0, F2_STARTS)) {
                b.append("start\tF2\t").append(p.name).append('\t').append(Calibration.tagsText(p.play)).append('\t')
                        .append(Calibration.tagsText(p.bet)).append('\t').append(p.value).append('\n');
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ fullplay

    /** The full-play choice from a full-index run's results.ser of the full-play candidates. */
    static void fullplayChoice(Map<String, String> o, PrintStream out) throws Exception {
        CeilingReport.Published p = CeilingReport.read(Paths.get(need(o, "results")));
        out.print(fullplayText(p.results, Paths.get(need(o, "results"))));
    }

    /**
     * Each candidate's 80% figures, 1-8 same schedule and flat, at first decisions and with full
     * play (the count's own row; for a count with a betting vector, its F2 row), and the full-play
     * F1: the best F1 by full-play 1-8 EV.
     */
    static String fullplayText(List<SystemScorer.Result> results, Path file) {
        StringBuilder b = new StringBuilder();
        b.append("The full-play choice (COUNT_DESIGN.md, section 4), from ").append(file).append(": each count's 80% "
                + "figures, point estimates, as a full-index run scored them.\n\n| Count | 1-8, first decisions | 1-8, full play "
                + "| Gain | Flat, first decisions | Flat, full play |\n|---|---|---|---|---|---|\n");
        SystemScorer.Result best = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (SystemScorer.Result r : results) {
            boolean f2 = r.count.betVector != null;
            SystemScorer.Entry first = r.entries.get(f2 ? 2 : 0);
            SystemScorer.Entry full = r.fullEntries.get(f2 ? 1 : 0);
            if (full.estimate[P80] == null) {
                throw new IllegalStateException(r.count.name + " has no full-play figures at 80%");
            }
            double v = full.estimate[P80][SAME8];
            b.append("| ").append(first.name).append(" | ").append(pct(first.estimate[P80][SAME8])).append(" | ")
                    .append(pct(v)).append(" | ").append(pct(v - first.estimate[P80][SAME8])).append(" | ")
                    .append(pct(first.estimate[P80][FLAT])).append(" | ").append(pct(full.estimate[P80][FLAT]))
                    .append(" |\n");
            if (!f2 && v > bestValue) {
                bestValue = v;
                best = r;
            }
        }
        if (best != null) {
            b.append("\nThe full-play F1, the best of the F1 counts by full-play 1-8 EV: ").append(best.count.name)
                    .append(", ").append(pct(bestValue)).append(" (tags ").append(tagsText(rankTagsOf(best.count)))
                    .append(").\n");
        }
        return b.toString();
    }

    static int[] rankTagsOf(CountKeys.Count c) {
        int[] t = new int[11];
        for (int r = 1; r <= 10; r++) {
            t[r] = c.rankTag(r);
        }
        return t;
    }

    // ------------------------------------------------------------------ report

    /** What the report works out, for its text and its tests. */
    static final class Evidence {
        Floor floor;
        int divisor;
        int runningDivisor;
        List<Direction> published;
        Map<String, List<Run>> families = new LinkedHashMap<>();
        /** Each run's start, scored as its search would score it. */
        Map<Run, Double> startValue = new LinkedHashMap<>();
        List<Slice> slices8;
        List<Slice> slicesFlat;
        /** [F1 at 1-8, F1 flat at flat][each divisor of GRAIN_LADDER times D]: the optimum's tags keyed at another grain. */
        double[][] grain;
        /** F1r at half and double the running divisor, and its tags as a true count at D: {D_r/2, 2 D_r, true 1-8}. */
        double[] f1r;
        List<Pair> f2Starts;
        /** The published side-count rows at g: name and 1-8 EV. */
        Map<String, Double> sideCountAtG = new LinkedHashMap<>();
        /** The half counts scored on each half and the full twin: [h][0 own half, 1 other half, 2 full twin]. */
        double[][] halves;
        /** The effects of removal off the top as tags by rank, for the tags' table; null for none. */
        double[] eor;
        List<String> failures = new ArrayList<>();
    }

    static void report(Map<String, String> o, PrintStream out) throws Exception {
        Path states = Paths.get(need(o, "states"));
        Path logs = Paths.get(need(o, "logs"));
        Path file = Paths.get(need(o, "out"));
        Path candidates = Paths.get(need(o, "candidates"));
        if (Files.exists(file)) {
            throw new IllegalStateException(file + " exists; write the report to a new file");
        }
        if (Files.exists(candidates)) {
            throw new IllegalStateException(candidates + " exists; write the candidates to a new file");
        }
        Floor floor = Floor.read(Paths.get(need(o, "floor")));
        int d = Integer.parseInt(need(o, "divisor"));
        int dr = Integer.parseInt(need(o, "running-divisor"));
        int threads = Integer.parseInt(o.getOrDefault("threads", "6"));
        double[] later = o.containsKey("later") ? CountSearch.parseTags(o.get("later")) : null;
        double[] betting = CountSearch.parseTags(need(o, "betting"));
        double[] eor = o.containsKey("eor") ? CountSearch.parseTags(o.get("eor")) : null;
        List<Run> runs = runs(logs);
        ScoringStates st = twin(states);
        long t0 = System.nanoTime();
        Evidence e = evidence(st, runs, floor, d, dr, betting, threads, out);
        e.eor = eor;
        String commit = FullIndexRun.commit();
        String text = reportText(e, commit, states, logs, o.containsKey("queue-log") ? Paths.get(o.get("queue-log")) : null,
                (System.nanoTime() - t0) / 1e9);
        List<Candidate> found = foundCandidates(e);
        String note = "SearchReport " + commit + " from the logs in " + logs;
        writeCandidates(candidates, found, note);
        StringBuilder b = new StringBuilder(text);
        b.append("\n## The candidates file\n\n").append(candidates).append(": each family's count, its tags whole at K as ")
                .append("the search's found line gives them, read back to the bit.\n");
        if (later != null && o.containsKey("fullplay")) {
            Path fp = Paths.get(o.get("fullplay"));
            List<Run> f1 = e.families.get("F1");
            List<Run> flat = e.families.get("F1 flat");
            List<Run> f2 = e.families.get("F2");
            List<Candidate> cs = fullPlay(f1.get(0).play, flat.get(0).play, later, f2.get(0).play, f2.get(0).bet, d);
            writeCandidates(fp, cs, note + ", the full-play candidates");
            b.append("\n## The full-play candidates\n\n").append(fp).append(" (section 4, \"Full play: choosing on the twin\"): ")
                    .append("five F1 counts on the line from the 1-8 optimum to the flat optimum, two turned from the 1-8 ")
                    .append("optimum toward the later-play direction (").append(tagsText(later)).append("), and F2's 1-8 ")
                    .append("optimum. Each one's angle from the 1-8 optimum:\n\n| Count | Angle, degrees | Tags |\n|---|---|---|\n");
            for (Candidate c : cs) {
                b.append("| ").append(c.name).append(" | ").append(deg(degrees(c.play, f1.get(0).play))).append(" | ")
                        .append(tagsText(c.play)).append(c.bet == null ? "" : "; bets " + tagsText(c.bet)).append(" |\n");
            }
        }
        Calibration.write(file, b.toString());
        out.printf("Wrote %s and %s.%n", file, candidates);
    }

    /** The families, in the report's order. */
    static final List<String> FAMILIES = Arrays.asList("F1", "F1 flat", "F2", "F1r", "F1 half 0", "F1 half 1");

    /** Works out the evidence: every family's runs and starts, the slices, the grain check, F2's starts, F1r's checks and the halves. */
    static Evidence evidence(ScoringStates st, List<Run> runs, Floor floor, int d, int dr, double[] betting, int threads,
                             PrintStream log) throws Exception {
        Evidence e = new Evidence();
        e.floor = floor;
        e.divisor = d;
        e.runningDivisor = dr;
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, 0, Bootstrap.SEED), SystemScorer.M_PLAY);
        SystemScorer[] half = new SystemScorer[2];
        for (int h = 0; h < 2; h++) {
            ScoringStates sub = st.subRun(h, 2);
            half[h] = new SystemScorer(sub, new Bootstrap(sub.shoes, 0, Bootstrap.SEED), HALF_M_PLAY);
        }
        for (String f : FAMILIES) {
            e.families.put(f, family(runs, f));
        }
        for (Run r : runs) {
            double want = r.half >= 0 ? HALF_M_PLAY : SystemScorer.M_PLAY;
            if (r.mPlay != want) {
                e.failures.add(r.name + " ran at M_play coefficient " + r.mPlay + ", not " + want);
            }
            if (!r.done) {
                e.failures.add(r.name + " has not ended: its log has no found line");
            }
        }
        log.println("Scoring the published directions.");
        e.published = published();
        scoreAll(sc, e.published, d, threads);

        // Each run's start, scored as its search would.
        log.println("Scoring the starts.");
        List<Run> all = new ArrayList<>();
        List<Callable<Double>> jobs = new ArrayList<>();
        for (List<Run> fam : e.families.values()) {
            for (Run r : fam) {
                all.add(r);
                jobs.add(() -> startValue(r, r.half >= 0 ? half[r.half] : sc));
            }
        }
        List<Double> sv = Calibration.parallel(threads, jobs);
        for (int k = 0; k < all.size(); k++) {
            e.startValue.put(all.get(k), sv.get(k));
        }

        // Slices at the two searched optima.
        List<Run> f1 = e.families.get("F1");
        List<Run> flat = e.families.get("F1 flat");
        if (!f1.isEmpty()) {
            log.println("Slicing at F1's optimum.");
            Run best = f1.get(0);
            e.slices8 = slices(CountSearch.f1(sc, settings("F1", SAME8, d)), point(toDouble(best.play)), axes(best),
                    best(e.published, 0).subList(0, TOP), threads);
        }
        if (!flat.isEmpty()) {
            log.println("Slicing at F1 flat's optimum.");
            Run best = flat.get(0);
            e.slicesFlat = slices(CountSearch.f1(sc, settings("F1", FLAT, d)), point(toDouble(best.play)), axes(best),
                    best(e.published, 1).subList(0, TOP), threads);
        }

        // The grain check, and F1r's divisor and depth checks.
        log.println("The grain check.");
        e.grain = new double[2][GRAIN_LADDER.length];
        for (double[] row : e.grain) {
            Arrays.fill(row, Double.NaN);
        }
        for (int k = 0; k < GRAIN_LADDER.length; k++) {
            int dk = (int) Math.round(d * GRAIN_LADDER[k]);
            if (!f1.isEmpty()) {
                e.grain[0][k] = scoreWhole(sc, f1.get(0).play, dk, true)[0];
            }
            if (!flat.isEmpty()) {
                e.grain[1][k] = scoreWhole(sc, flat.get(0).play, dk, true)[1];
            }
        }
        List<Run> f1r = e.families.get("F1r");
        if (!f1r.isEmpty()) {
            int[] t = f1r.get(0).play;
            e.f1r = new double[]{scoreWhole(sc, t, dr / 2, false)[0], scoreWhole(sc, t, 2 * dr, false)[0],
                    scoreWhole(sc, balanced(t), d, true)[0]};
        }

        // F2's starts and the published side-count rows at g.
        List<Run> f2 = e.families.get("F2");
        if (!f2.isEmpty() && !f1.isEmpty() && !flat.isEmpty()) {
            log.println("Scoring F2's starts.");
            e.f2Starts = f2Starts(toDouble(f1.get(0).play), toDouble(flat.get(0).play), betting);
            List<Callable<Double>> pj = new ArrayList<>();
            for (Pair p : e.f2Starts) {
                pj.add(() -> f2Value(sc, d, p.play, p.bet));
            }
            List<Double> pv = Calibration.parallel(threads, pj);
            for (int k = 0; k < pv.size(); k++) {
                e.f2Starts.get(k).value = pv.get(k);
            }
            for (CountKeys.Count c : CountKeys.catalog()) {
                if (CountKeys.sideCountVariant(c) != null) {
                    CountKeys.Count g = CountKeys.atGrain(c, d);
                    e.sideCountAtG.put(c.name + " + ace side count at g",
                            sc.estimate(g, CountKeys.bettingVariant(g), P80)[2][SAME8]);
                }
            }
        }

        // The half counts, each scored on its own half, the other and the whole twin.
        List<Run> h0 = e.families.get("F1 half 0");
        List<Run> h1 = e.families.get("F1 half 1");
        if (!h0.isEmpty() && !h1.isEmpty()) {
            log.println("Scoring the half counts.");
            e.halves = new double[2][3];
            int[][] t = {h0.get(0).play, h1.get(0).play};
            for (int h = 0; h < 2; h++) {
                e.halves[h][0] = scoreWhole(half[h], t[h], d, true)[0];
                e.halves[h][1] = scoreWhole(half[1 - h], t[h], d, true)[0];
                e.halves[h][2] = scoreWhole(sc, t[h], d, true)[0];
            }
        }
        verdicts(e);
        return e;
    }

    /** A running count's tags with their p0-weighted mean taken off, whole: its tags as a true count. */
    static int[] balanced(int[] t) {
        double[] x = toDouble(t);
        double m = CountKeys.mean(x);
        double[] c = new double[11];
        for (int r = 1; r <= 10; r++) {
            c[r] = (x[r] - m) / CountKeys.K;
        }
        return CountKeys.rounded(c, CountKeys.K);
    }

    /** A run's start, scored as its search scores candidates; F2's as its log notes it. */
    static double startValue(Run r, SystemScorer sc) {
        switch (r.form) {
            case "F1":
                return CountSearch.f1(sc, settings("F1", r.metric, r.divisor)).of(point(r.start));
            case "F1r": {
                double[] x = Arrays.copyOf(CountSearch.coordinates(r.start), 10);
                x[9] = CountKeys.mean(r.start);
                double n = 0;
                for (int k = 0; k < 9; k++) {
                    n += x[k] * x[k];
                }
                double f = CountSearch.RADIUS / Math.sqrt(n);
                for (int k = 0; k < 10; k++) {
                    x[k] *= f;
                }
                return CountSearch.f1r(sc, settings("F1r", r.metric, r.divisor)).of(x);
            }
            case "F2":
                return r.startValue;
            default:
                throw new IllegalArgumentException("no start value for the form " + r.form);
        }
    }

    /** The checks of section 4 that are failures when they do not hold, each named in e.failures. */
    static void verdicts(Evidence e) {
        for (String fam : FAMILIES) {
            List<Run> runs = e.families.get(fam);
            if (runs.isEmpty()) {
                continue;
            }
            Run best = runs.get(0);
            for (Run r : runs) {
                double s = e.startValue.get(r);
                if (best.value < s) {
                    e.failures.add(fam + ": the optimum, " + pct(best.value) + ", is below the start of " + r.name + ", "
                            + pct(s));
                }
            }
            if (fam.equals("F1") || fam.equals("F1 flat")) {
                for (Direction d : e.published) {
                    double s = fam.equals("F1") ? d.same8 : d.flat;
                    if (best.value < s) {
                        e.failures.add(fam + ": the optimum, " + pct(best.value) + ", is below " + d.name + "'s direction, "
                                + pct(s));
                    }
                }
            }
        }
        List<Run> f1 = e.families.get("F1");
        List<Run> f2 = e.families.get("F2");
        if (!f2.isEmpty()) {
            double v = f2.get(0).value;
            if (!f1.isEmpty() && v < f1.get(0).value) {
                e.failures.add("F2, " + pct(v) + ", is below F1, " + pct(f1.get(0).value));
            }
            if (e.f2Starts != null) {
                for (Pair p : e.f2Starts) {
                    if (v < p.value) {
                        e.failures.add("F2, " + pct(v) + ", is below its start " + p.name + ", " + pct(p.value));
                    }
                }
            }
            for (Map.Entry<String, Double> s : e.sideCountAtG.entrySet()) {
                if (v < s.getValue()) {
                    e.failures.add("F2, " + pct(v) + ", is below the published row " + s.getKey() + ", " + pct(s.getValue()));
                }
            }
        }
        for (List<Slice> ss : Arrays.asList(e.slices8, e.slicesFlat)) {
            if (ss == null) {
                continue;
            }
            for (Slice s : ss) {
                if (!s.peaks(e.floor)) {
                    e.failures.add((ss == e.slices8 ? "F1" : "F1 flat") + "'s slice along " + s.direction
                            + " does not peak at the optimum within noise: " + (s.quadratic[2] < 0 ? "headroom "
                            + pts(s.quadratic[4]) + " at " + deg(s.peakDegrees()) + " degrees" : "it does not curve down"));
                }
            }
        }
    }

    /** Section 4's grain check: the optimum at half and at double the grain within the floor at 1 degree. */
    static boolean grainTwoSided(double[] g, double v, Floor fl) {
        return Math.abs(g[0] - v) < fl.at(1) && Math.abs(g[1] - v) < fl.at(1);
    }

    /** The calibration's rule at the optimum: no finer grain of the ladder gains more than the floor at 1 degree. */
    static boolean grainFinerGains(double[] g, double v, Floor fl) {
        for (int k = 1; k < g.length; k++) {
            if (g[k] - v > fl.at(1)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The run that followed this one up at double the population and cap (section 4), started
     * from this run's count; null if none.
     */
    static Run followUp(Run r, List<Run> runs) {
        for (Run o : runs) {
            if (o != r && o.lambda == 2 * r.lambda && o.maxEvals == 2 * r.maxEvals && o.start.length == 11) {
                boolean same = true;
                for (int k = 1; k <= 10; k++) {
                    same &= o.start[k] == r.play[k];
                }
                if (same) {
                    return o;
                }
            }
        }
        return null;
    }

    /** The counts found, each family's best, for the candidates file. */
    static List<Candidate> foundCandidates(Evidence e) {
        List<Candidate> out = new ArrayList<>();
        String[] names = {"F1 at 1-8", "F1 flat", "F2 at 1-8", "F1r at 1-8", "F1 at 1-8 on the twin's half 0",
                "F1 at 1-8 on the twin's half 1"};
        for (int k = 0; k < FAMILIES.size(); k++) {
            List<Run> runs = e.families.get(FAMILIES.get(k));
            if (runs.isEmpty()) {
                continue;
            }
            Run r = runs.get(0);
            out.add(new Candidate(names[k] + " (" + r.name + ")", FAMILIES.get(k), r.form.equals("F1r") ? "running" : "true",
                    r.divisor, r.play, r.bet, r.value));
        }
        return out;
    }

    static final Pattern FOUND_IN = Pattern.compile("^Found .* in (\\d+) s\\.$");

    /**
     * Seconds a search ran as its own output says ("Found ... in N s."), beside its log as NAME.out,
     * summed over its starts; null if none. Its timer is System.nanoTime, which on this machine does
     * not advance while the machine sleeps, so it is the time the search ran awake.
     */
    static Double outSeconds(Path log) throws IOException {
        Path out = log.resolveSibling(log.getFileName().toString().replaceAll("\\.log$", ".out"));
        if (!Files.exists(out)) {
            return null;
        }
        Double s = null;
        for (String l : Files.readAllLines(out, StandardCharsets.UTF_8)) {
            Matcher m = FOUND_IN.matcher(l);
            if (m.matches()) {
                s = (s == null ? 0 : s) + Double.parseDouble(m.group(1));
            }
        }
        return s;
    }

    /** Wall seconds a search ran, from the queue's log: each "HH:MM:SS start NAME" to its "HH:MM:SS end NAME"; a start with no end is not counted. */
    static Map<String, Double> wallSeconds(Path queueLog) throws IOException {
        Map<String, Double> out = new LinkedHashMap<>();
        if (queueLog == null || !Files.exists(queueLog)) {
            return out;
        }
        Map<String, Integer> open = new LinkedHashMap<>();
        for (String l : Files.readAllLines(queueLog, StandardCharsets.UTF_8)) {
            String[] f = l.split(" ");
            if (f.length < 3) {
                continue;
            }
            String[] hms = f[0].split(":");
            int t = Integer.parseInt(hms[0]) * 3600 + Integer.parseInt(hms[1]) * 60 + Integer.parseInt(hms[2]);
            if (f[1].equals("start")) {
                open.put(f[2], t);
            } else if (f[1].equals("end") && open.containsKey(f[2])) {
                int s = t - open.remove(f[2]);
                out.merge(f[2], (double) (s < 0 ? s + 86400 : s), Double::sum);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the report's text

    /**
     * Each family's tags beside the effects of removal off the top and the best published counts'
     * (the three best directions by 1-8, and Hi-Lo), every column centred and at Hi-Lo's spread,
     * with each one's angle from F1.
     */
    static String tagsTable(Evidence e) {
        List<String> names = new ArrayList<>();
        List<double[]> cols = new ArrayList<>();
        for (String fam : FAMILIES) {
            List<Run> runs = e.families.get(fam);
            if (runs == null || runs.isEmpty()) {
                continue;
            }
            Run r = runs.get(0);
            names.add(fam.equals("F2") ? "F2, play" : fam.equals("F1r") ? "F1r, balanced part" : fam);
            cols.add(toDouble(r.play));
            if (r.bet != null) {
                names.add("F2, bets");
                cols.add(toDouble(r.bet));
            }
        }
        if (cols.isEmpty()) {
            return "";
        }
        if (e.eor != null) {
            names.add("Effects of removal");
            cols.add(e.eor);
        }
        List<Direction> top = best(e.published, 0);
        for (int k = 0; k < 3 && k < top.size(); k++) {
            names.add(top.get(k).name);
            cols.add(top.get(k).tags);
        }
        names.add("Hi-Lo");
        cols.add(Calibration.sign());
        StringBuilder b = new StringBuilder("\n## The tags\n\nEach family's count, the effects of removal off the top "
                + "(EffectsOfRemoval, from the calibration) and the three best published directions by 1-8 with Hi-Lo, each "
                + "centred and at Hi-Lo's spread (an F1r count's shift left out), and each one's angle from F1.\n\n| Rank");
        for (String n : names) {
            b.append(" | ").append(n);
        }
        b.append(" |\n|---");
        for (int k = 0; k < names.size(); k++) {
            b.append("|---");
        }
        b.append("|\n");
        double[][] norm = new double[cols.size()][];
        for (int k = 0; k < cols.size(); k++) {
            norm[k] = CountKeys.normalised(cols.get(k));
        }
        for (int r = 1; r <= 10; r++) {
            b.append("| ").append(Calibration.rank(r));
            for (double[] c : norm) {
                b.append(String.format(Locale.ROOT, " | %+.4f", c[r]));
            }
            b.append(" |\n");
        }
        b.append("| Angle from F1, degrees");
        for (double[] c : norm) {
            b.append(" | ").append(deg(Calibration.degrees(c, norm[0])));
        }
        return b.append(" |\n").toString();
    }

    static String reportText(Evidence e, String commit, Path states, Path logs, Path queueLog, double seconds)
            throws IOException {
        StringBuilder b = new StringBuilder();
        Floor fl = e.floor;
        b.append("# The searches and their convergence\n\n");
        b.append("SearchReport ").append(commit).append(" on ").append(states).append(", from the search logs in ")
                .append(logs).append(": COUNT_DESIGN.md, section 4 and section 8, step 4. Every figure is CountSearch's ")
                .append("objective (SystemScorer.estimate at 80%, the 1-8 same schedule or flat, point estimates) on the ")
                .append("twin, or read from the logs. The noise floor is the calibration's, by angle: ");
        for (int k = 0; k < FLOOR_ANGLES.length; k++) {
            b.append(k == 0 ? "" : ", ").append(Calibration.degreesText(FLOOR_ANGLES[k])).append(" ").append(pts(fl.at[k]));
        }
        b.append(", taken linearly between them. Divisor ").append(e.divisor).append(", running divisor ")
                .append(e.runningDivisor).append(". The report's own evaluations took ")
                .append(String.format(Locale.ROOT, "%.0f", seconds)).append(" s.\n\n");

        // Summary.
        b.append("## Summary\n\n");
        for (String fam : FAMILIES) {
            List<Run> runs = e.families.get(fam);
            if (runs.isEmpty()) {
                b.append("- **").append(fam).append(":** no search has ended.\n");
                continue;
            }
            Run r = runs.get(0);
            b.append("- **").append(fam).append("** (").append(r.name).append(", best of ").append(runs.size())
                    .append("): ").append(pct(r.value)).append("; tags ").append(tagsText(r.play));
            if (r.bet != null) {
                b.append("; betting tags ").append(tagsText(r.bet));
            }
            b.append(".\n");
        }
        Direction topPub = best(e.published, 0).get(0);
        b.append("- **The best published direction** by 1-8: ").append(topPub.name).append(", ").append(pct(topPub.same8))
                .append("; by flat: ").append(best(e.published, 1).get(0).name).append(", ")
                .append(pct(best(e.published, 1).get(0).flat)).append(".\n");
        if (!e.sideCountAtG.isEmpty()) {
            Map.Entry<String, Double> top = null;
            for (Map.Entry<String, Double> s : e.sideCountAtG.entrySet()) {
                if (top == null || s.getValue() > top.getValue()) {
                    top = s;
                }
            }
            b.append("- **The best published row at g:** ").append(top.getKey()).append(", ").append(pct(top.getValue()))
                    .append(".\n");
        }
        b.append("- **Failures:** ").append(e.failures.isEmpty() ? "none." : e.failures.size() + ", listed below.")
                .append("\n");
        for (String f : e.failures) {
            b.append("  - ").append(f).append('\n');
        }

        b.append(tagsTable(e));

        // The published directions.
        b.append("\n## Every published count's direction\n\nScored as the search scores a candidate (normalised, signed ")
                .append("by Hi-Lo, whole at K, true-counted at divisor ").append(e.divisor).append("); a kind-aware count ")
                .append("by its rank averages, a running count by its balanced part.\n\n| Count | 1-8 | Flat |\n|---|---|---|\n");
        for (Direction d : best(e.published, 0)) {
            b.append("| ").append(d.name).append(" | ").append(pct(d.same8)).append(" | ").append(pct(d.flat)).append(" |\n");
        }

        // Restarts, by family.
        Map<String, Double> wall = wallSeconds(queueLog);
        b.append("\n## 1. Restarts agree\n\nEach run's start scored as its search scores candidates, its count's objective, ")
                .append("its gap below the family's best and its angle from it. A run agrees when it ends within the ")
                .append("noise floor at that angle and within ").append(deg(FEW_DEGREES)).append(" degrees. Runs within ")
                .append(deg(FEW_DEGREES)).append(" degrees of each other, joined link by link, are one region. ")
                .append("Wall s is the queue's clock from start to end; awake s the run's own timer, which stops while ")
                .append("the machine sleeps.\n");
        for (String fam : FAMILIES) {
            List<Run> runs = e.families.get(fam);
            if (runs.isEmpty()) {
                continue;
            }
            Run best = runs.get(0);
            b.append("\n### ").append(fam).append("\n\n| Run | Start | Objective | Below the best | Angle, degrees | ")
                    .append("Evaluations | Wall s | Awake s | Agrees |\n|---|---|---|---|---|---|---|---|---|\n");
            int agree = 0;
            int followed = 0;
            int evals = 0;
            double secs = 0;
            double awake = 0;
            for (Run r : runs) {
                boolean ok = agrees(r, best, fl);
                Run up = ok ? null : followUp(r, runs);
                boolean upOk = up != null && agrees(up, best, fl);
                agree += ok ? 1 : 0;
                followed += upOk ? 1 : 0;
                evals += r.evaluations;
                Double w = wall.get(r.name);
                Double a = outSeconds(r.log);
                secs += w == null ? 0 : w;
                awake += a == null ? 0 : a;
                b.append("| ").append(r.name).append(r.lambda != 12 ? " (population " + r.lambda + ", cap " + r.maxEvals
                        + ")" : "").append(" | ").append(pct(e.startValue.get(r))).append(" | ").append(pct(r.value))
                        .append(" | ").append(pts(best.value - r.value)).append(" | ")
                        .append(deg(degrees(r.play, best.play))).append(" | ").append(r.evaluations).append(" | ")
                        .append(w == null ? "" : String.format(Locale.ROOT, "%.0f", w)).append(" | ")
                        .append(a == null ? "" : String.format(Locale.ROOT, "%.0f", a)).append(" | ")
                        .append(ok ? "yes" : up == null ? "no" : "no; followed up by " + up.name + ", which "
                                + (upOk ? "agrees" : "does not")).append(" |\n");
            }
            List<List<Run>> regions = regions(runs);
            b.append("\n").append(agree).append(" of ").append(runs.size()).append(" runs agree with the best. ")
                    .append(evals).append(" evaluations, ").append(String.format(Locale.ROOT, "%.0f", secs))
                    .append(" s on the wall by the queue's clock, ").append(String.format(Locale.ROOT, "%.0f", awake))
                    .append(" s awake by the runs' own. ").append(regions.size()).append(regions.size() == 1 ? " region" : " regions");
            if (regions.size() > 1) {
                b.append(": ");
                for (int g = 0; g < regions.size(); g++) {
                    Run rb = regions.get(g).get(0);
                    b.append(g == 0 ? "" : "; ").append(regions.get(g).size()).append(" runs best ").append(rb.name)
                            .append(" at ").append(pct(rb.value)).append(g == 0 ? "" : ", " + pts(best.value - rb.value)
                            + " below at " + deg(degrees(rb.play, best.play)) + " degrees (floor " + pts(fl.at(degrees(
                            rb.play, best.play))) + ")");
                }
                b.append(". They ").append(separate(regions, fl) ? "differ by more than noise: each region's best gets one "
                        + "more run at double the population and the cap (section 4)." : "do not differ by more than noise.");
            }
            b.append(" Verdict: restarts ").append(agree == runs.size() ? "agree." : agree + followed == runs.size()
                    ? "agree once each run that did not is followed up at double the population and the cap, as section 4 "
                    + "does for a region whose best differs by more than noise: " + (runs.size() - agree) + " did not at "
                    + "first." : "do not all agree.").append("\n");
        }

        // Slices.
        for (int k = 0; k < 2; k++) {
            List<Slice> ss = k == 0 ? e.slices8 : e.slicesFlat;
            if (ss == null) {
                continue;
            }
            String fam = k == 0 ? "F1" : "F1 flat";
            b.append("\n## 2. Slices at ").append(fam).append("'s optimum\n\nThe objective along great circles through ")
                    .append(e.families.get(fam).get(0).name).append("'s count, ").append(SLICE_POINTS).append(" points from -")
                    .append(deg(SLICE_DEGREES)).append(" to +").append(deg(SLICE_DEGREES)).append(" degrees: along each ")
                    .append("basis direction, each of the run's principal axes at its end, and toward each of the ")
                    .append(TOP).append(" best published counts (by ").append(k == 0 ? "1-8" : "flat").append("). ")
                    .append("A least-squares quadratic through each gives its peak and the headroom there over the ")
                    .append("optimum; the slice peaks at the optimum within noise when the quadratic curves down and ")
                    .append("that headroom is under the floor at the peak's angle. The angle within which the ")
                    .append("quadratic loses less than the floor is what the data cannot tell apart.\n\n")
                    .append("| Direction | Curvature, %/deg^2 | Peak, degrees | Headroom | Best point, gain at degrees ")
                    .append("| Indistinguishable within, degrees | Peaks |\n|---|---|---|---|---|---|---|\n");
            int ok = 0;
            List<Double> within = new ArrayList<>();
            double most = Double.NEGATIVE_INFINITY;
            for (Slice s : ss) {
                double c = s.quadratic[2] * Math.pow(Math.PI / 180, 2);
                double[] bp = s.bestPoint();
                double w = s.indistinguishable(fl);
                if (!Double.isNaN(w)) {
                    within.add(w);
                }
                if (s.quadratic[2] < 0) {
                    most = Math.max(most, s.quadratic[4]);
                }
                boolean p = s.peaks(fl);
                ok += p ? 1 : 0;
                b.append("| ").append(s.direction).append(" | ").append(String.format(Locale.ROOT, "%.6f", 100 * c))
                        .append(" | ").append(s.quadratic[2] < 0 ? deg(s.peakDegrees()) : "none").append(" | ")
                        .append(s.quadratic[2] < 0 ? pts(s.quadratic[4]) : "").append(" | ").append(pct(bp[0]))
                        .append(" at ").append(deg(bp[1])).append(" | ").append(Double.isNaN(w) ? "" : deg(w))
                        .append(" | ").append(p ? "yes" : "NO").append(" |\n");
            }
            within.sort(Double::compare);
            b.append("\n").append(ok).append(" of ").append(ss.size()).append(" slices peak at the optimum within noise; ")
                    .append("the largest headroom a quadratic gives is ").append(pts(most)).append(". The angle within ")
                    .append("which the loss is under the floor runs from ")
                    .append(within.isEmpty() ? "-" : deg(within.get(0))).append(" to ")
                    .append(within.isEmpty() ? "-" : deg(within.get(within.size() - 1))).append(" degrees, median ")
                    .append(within.isEmpty() ? "-" : deg(within.get(within.size() / 2))).append(": every direction that ")
                    .append("close is as good as the data can tell apart. Verdict: slices ")
                    .append(ok == ss.size() ? "peak at the optimum." : "do NOT all peak at the optimum.").append("\n");
        }

        // Every start beaten.
        b.append("\n## 3. Every start is beaten\n\n");
        for (String fam : FAMILIES) {
            List<Run> runs = e.families.get(fam);
            if (runs.isEmpty()) {
                continue;
            }
            Run best = runs.get(0);
            double top = Double.NEGATIVE_INFINITY;
            String topName = "";
            for (Run r : runs) {
                if (e.startValue.get(r) > top) {
                    top = e.startValue.get(r);
                    topName = r.name + "'s start";
                }
            }
            if (fam.equals("F1") || fam.equals("F1 flat")) {
                Direction d = best(e.published, fam.equals("F1") ? 0 : 1).get(0);
                double v = fam.equals("F1") ? d.same8 : d.flat;
                if (v > top) {
                    top = v;
                    topName = d.name + "'s direction";
                }
            }
            b.append("- **").append(fam).append(":** the optimum ").append(pct(best.value)).append("; the best of its ")
                    .append(fam.startsWith("F1") && !fam.equals("F1r") && !fam.startsWith("F1 half") ? "starts and the "
                            + "published directions" : "starts").append(", ").append(topName).append(", ")
                    .append(pct(top)).append(": beaten by ").append(pts(best.value - top)).append(". ")
                    .append(best.value >= top ? "Holds." : "FAILS.").append("\n");
        }
        List<Run> f1 = e.families.get("F1");
        List<Run> f2 = e.families.get("F2");
        if (!f2.isEmpty()) {
            double v = f2.get(0).value;
            if (!f1.isEmpty()) {
                b.append("- **F2 against F1:** ").append(pct(v)).append(" against ").append(pct(f1.get(0).value))
                        .append(", ").append(pct(v - f1.get(0).value)).append(". ")
                        .append(v >= f1.get(0).value ? "Holds." : "FAILS.").append("\n");
            }
            if (e.f2Starts != null) {
                b.append("- **F2 against its starts,** each scored as F2's search scores its start:\n\n| Start | F2's objective "
                        + "there | F2 less it |\n|---|---|---|\n");
                List<Pair> ps = new ArrayList<>(e.f2Starts);
                ps.sort(Comparator.comparingDouble((Pair p) -> p.value).reversed());
                for (Pair p : ps) {
                    b.append("| ").append(p.name).append(" | ").append(pct(p.value)).append(" | ").append(pct(v - p.value))
                            .append(" |\n");
                }
                b.append("\n");
            }
            if (!e.sideCountAtG.isEmpty()) {
                b.append("- **F2 against the published side-count rows at g** (CountKeys.atGrain, the catalog's ")
                        .append("side-count variant):\n\n| Row | 1-8 | F2 less it |\n|---|---|---|\n");
                for (Map.Entry<String, Double> s : e.sideCountAtG.entrySet()) {
                    b.append("| ").append(s.getKey()).append(" | ").append(pct(s.getValue())).append(" | ")
                            .append(pct(v - s.getValue())).append(" |\n");
                }
                b.append("\n");
            }
        }
        b.append("4. **A planted optimum is recovered:** CountSearchTest, run with the suite at this commit (section 6).\n");

        // The grain check.
        b.append("\n## 5. The grain does not decide it\n\nThe optimum's tags as they stand, keyed at double the grain ")
                .append("(divisor 2D), at half of it (D/2), and at the calibration ladder's finer grains (D/5 and D/10, g = ")
                .append("1/100 and 1/200 at D = 500), each less the optimum at D, against the floor at 1 degree, ")
                .append(pts(fl.at(1))).append(". Section 4's check is two-sided at half and double the grain; the ")
                .append("calibration chose g by a one-sided rule, that no finer grain gains more than the floor, which is ")
                .append("given beside it.\n\n| Optimum | At D | 2D | D/2 | D/5 | D/10 | Half and double within the floor | ")
                .append("No finer grain gains more |\n|---|---|---|---|---|---|---|---|\n");
        for (int k = 0; k < 2; k++) {
            List<Run> runs = e.families.get(k == 0 ? "F1" : "F1 flat");
            if (runs.isEmpty()) {
                continue;
            }
            double v = runs.get(0).value;
            double[] g = e.grain[k];
            b.append("| ").append(k == 0 ? "F1, 1-8" : "F1 flat, flat").append(" | ").append(pct(v));
            for (double x : g) {
                b.append(" | ").append(pct(x - v));
            }
            b.append(" | ").append(grainTwoSided(g, v, fl) ? "yes" : "NO").append(" | ")
                    .append(grainFinerGains(g, v, fl) ? "yes" : "NO").append(" |\n");
        }

        // F1r.
        List<Run> f1r = e.families.get("F1r");
        if (!f1r.isEmpty() && e.f1r != null) {
            double v = f1r.get(0).value;
            b.append("\n## F1r, the running-count check\n\n- At D_r = ").append(e.runningDivisor).append(": ")
                    .append(pct(v)).append("; at D_r/2 ").append(pct(e.f1r[0])).append(" (").append(pct(e.f1r[0] - v))
                    .append("); at 2 D_r ").append(pct(e.f1r[1])).append(" (").append(pct(e.f1r[1] - v)).append(").\n")
                    .append("- Its tags as a true count at D = ").append(e.divisor).append(": ").append(pct(e.f1r[2]))
                    .append("; F1r less that, the depth effect on the twin: ").append(pct(v - e.f1r[2])).append(".\n");
            if (!f1.isEmpty()) {
                b.append("- F1r less F1: ").append(pct(v - f1.get(0).value)).append("; their balanced parts ")
                        .append(deg(degrees(balanced(f1r.get(0).play), f1.get(0).play))).append(" degrees apart.\n");
            }
        }

        // The halves.
        if (e.halves != null) {
            int[] t0 = e.families.get("F1 half 0").get(0).play;
            int[] t1 = e.families.get("F1 half 1").get(0).play;
            double cos = Math.cos(Math.toRadians(degrees(t0, t1)));
            b.append("\n## Is the twin big enough? The half-twin searches\n\nF1 at 1-8 searched on each half of the twin ")
                    .append("(the learning curve's split), M_play coefficient 5 sqrt 2. Each half's count scored on its own ")
                    .append("half, on the other half (both at 5 sqrt 2) and on the whole twin (at 5):\n\n| Count | Own half | ")
                    .append("Other half | Whole twin | Angle to F1, degrees |\n|---|---|---|---|---|\n");
            for (int h = 0; h < 2; h++) {
                int[] t = h == 0 ? t0 : t1;
                b.append("| half ").append(h).append(" | ").append(pct(e.halves[h][0])).append(" | ")
                        .append(pct(e.halves[h][1])).append(" | ").append(pct(e.halves[h][2])).append(" | ")
                        .append(f1.isEmpty() ? "" : deg(degrees(t, f1.get(0).play))).append(" |\n");
            }
            b.append("\nThe two half counts lie ").append(deg(degrees(t0, t1))).append(" degrees apart, cosine ")
                    .append(String.format(Locale.ROOT, "%.4f", cos)).append(cos >= HALF_COSINE ? ", not" : ", ")
                    .append(" far apart by section 6's mark of ").append(HALF_COSINE).append(".");
            if (!f1.isEmpty()) {
                b.append(" On the whole twin, F1 less their mean: ")
                        .append(pct(f1.get(0).value - (e.halves[0][2] + e.halves[1][2]) / 2))
                        .append(" (in-sample for F1, so not the learning curve, which is taken on the main file).");
            }
            b.append("\n");
        }
        return b.toString();
    }
}
