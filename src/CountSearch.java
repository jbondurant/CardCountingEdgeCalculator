import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The search for the ceiling count (COUNT_DESIGN.md, section 4), on the twin file alone.
 *
 * A count is a direction of ten real tags. The search works in coordinates on an orthonormal
 * basis of the balanced tags under the p0-weighted inner product (basis): nine numbers, whose
 * length is the tags' spread, so a candidate normalised to Hi-Lo's spread is a point on the
 * sphere of radius sqrt(10/13), and the angle between two counts is the angle between their
 * coordinates. Each candidate is normalised, rounded at K with the deck sum kept zero, and scored
 * by SystemScorer.estimate, so the objective sees only its direction; its sign is fixed so that
 * it leans the way of the sign reference (Hi-Lo unless given), as an isotonic bet only rises.
 * CMA-ES (Cma) keeps its mean on the sphere, and a run reports its final mean, scored, not its
 * best single evaluation.
 *
 * The forms:
 * - F1: one tag vector for the bet key, the play key and insurance; its objective the count's own
 *   row, at the 1-8 same schedule or flat, at 80%;
 * - F2: a play vector and a betting vector; its objective the betting variant's row. The search
 *   alternates two blocks, the betting vector with the play held (SystemScorer.hold and betOn,
 *   cheap), then the play vector, until a cycle moves the objective by no more than the noise
 *   floor or after three cycles, and ends with both together. Each block after the first of its
 *   kind, and the joint polish, start from the step the block before them ended at, and each
 *   block's end is taken only where it scores at least what its start did, so F2 ends at or
 *   above its start and every block's end;
 * - F1r: the ten tags keyed on the running count with a running divisor; the constant shift is a
 *   tenth coordinate, and the sphere is the balanced part's, the shift scaled with it.
 *
 * Every evaluation is logged with its run, generation, objective and whole-number tags, and after
 * every generation the search's state, so a search can be audited and a stopped one resumed: it
 * then goes on exactly as it would have (Cma's generations are seeded by number). The log is a
 * tab-separated file; its header names the state file, its header seed, and the settings, the sign
 * reference among them, so a resume with any of them changed is refused.
 *
 * java -Xmx12g -cp CLASSES:lib/mongo-java-driver-3.12.14.jar CountSearch --states TWIN --log FILE
 *   --form F1|F2|F1r [--metric same8|flat] [--pen 2] --divisor D [--start A,2,...,T | --start-count NAME
 *   | --start-random SEED] [--start-bet A,...,T] [--lambda 12] [--sigma 0.3] [--max-evals 1500]
 *   [--min-angle DEG] [--noise X] [--seed S] [--threads T] [--half 0|1] [--m-play C] [--run NAME]
 *   [--sign A,...,T] [--resume]
 */
final class CountSearch {

    /** The radius of the sphere of counts at Hi-Lo's spread. */
    static final double RADIUS = Math.sqrt(CountKeys.SPREAD);
    /** An orthonormal basis of the balanced tags, by rank 1 to 10, under the p0-weighted inner product. */
    static final double[][] BASIS = basis();

    private CountSearch() {
    }

    // ------------------------------------------------------------------ the coordinates

    /** p0-weighted inner product of tags by rank. */
    static double dot(double[] a, double[] b) {
        double s = 0;
        for (int r = 1; r <= 10; r++) {
            s += CountKeys.P0[r] * a[r] * b[r];
        }
        return s;
    }

    /** Gram-Schmidt on the unit tags of the ace and 2 to 9, each centred first: nine vectors. */
    private static double[][] basis() {
        double[][] out = new double[9][];
        for (int k = 0; k < 9; k++) {
            double[] v = new double[11];
            v[k + 1] = 1;
            double m = CountKeys.mean(v);
            for (int r = 1; r <= 10; r++) {
                v[r] -= m;
            }
            for (int j = 0; j < k; j++) {
                double c = dot(v, out[j]);
                for (int r = 1; r <= 10; r++) {
                    v[r] -= c * out[j][r];
                }
            }
            double n = Math.sqrt(dot(v, v));
            for (int r = 1; r <= 10; r++) {
                v[r] /= n;
            }
            out[k] = v;
        }
        return out;
    }

    /** Real tags by rank, centred, from nine coordinates. */
    static double[] tags(double[] x) {
        double[] t = new double[11];
        for (int k = 0; k < 9; k++) {
            for (int r = 1; r <= 10; r++) {
                t[r] += x[k] * BASIS[k][r];
            }
        }
        return t;
    }

    /** The coordinates of tags by rank, centred first. */
    static double[] coordinates(double[] rankTag) {
        double m = CountKeys.mean(rankTag);
        double[] t = new double[11];
        for (int r = 1; r <= 10; r++) {
            t[r] = rankTag[r] - m;
        }
        double[] x = new double[9];
        for (int k = 0; k < 9; k++) {
            x[k] = dot(t, BASIS[k]);
        }
        return x;
    }

    /** The cosine of the angle between two counts' directions, tags by rank, each centred. */
    static double cosine(double[] a, double[] b) {
        double[] x = coordinates(a);
        double[] y = coordinates(b);
        double xy = 0;
        double xx = 0;
        double yy = 0;
        for (int k = 0; k < 9; k++) {
            xy += x[k] * y[k];
            xx += x[k] * x[k];
            yy += y[k] * y[k];
        }
        return xy / Math.sqrt(xx * yy);
    }

    /** A point on the sphere: the coordinates scaled to its radius. */
    static double[] onSphere(double[] x) {
        double n = 0;
        for (double v : x) {
            n += v * v;
        }
        n = Math.sqrt(n);
        double[] out = new double[x.length];
        for (int k = 0; k < x.length; k++) {
            out[k] = x[k] * RADIUS / n;
        }
        return out;
    }

    /** A count's tags by rank as reals: a kind-aware count's rank averages. */
    static double[] rankTags(CountKeys.Count c) {
        double[] t = new double[11];
        int[] n = new int[11];
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            t[ShoeRun.rankOf(k)] += c.tag[k];
            n[ShoeRun.rankOf(k)]++;
        }
        for (int r = 1; r <= 10; r++) {
            t[r] /= n[r];
        }
        return t;
    }

    /** Whole-number tags by rank, as a candidate is rounded: normalised to Hi-Lo's spread, at K, its sign fixed. */
    static int[] whole(double[] x, double[] sign) {
        double[] t = tags(x);
        if (sign != null && dot(t, sign) < 0) {
            for (int r = 1; r <= 10; r++) {
                t[r] = -t[r];
            }
        }
        return CountKeys.rounded(CountKeys.normalised(t), CountKeys.K);
    }

    /**
     * F1r's tags from ten coordinates, nine balanced and a shift: the balanced part scaled to
     * Hi-Lo's spread, the shift scaled with it, all rounded at K.
     */
    static int[] wholeRunning(double[] x, double[] sign) {
        double[] bal = Arrays.copyOf(x, 9);
        double[] t = tags(bal);
        double n = Math.sqrt(dot(t, t));
        double f = RADIUS / n;
        if (sign != null && dot(t, sign) < 0) {
            f = -f;
        }
        int[] out = new int[11];
        for (int r = 1; r <= 10; r++) {
            out[r] = (int) Math.rint((t[r] + x[9]) * f * CountKeys.K);
        }
        return out;
    }

    // ------------------------------------------------------------------ the objectives

    /** What a candidate's coordinates score. */
    interface Objective {
        double of(double[] x);
    }

    /** The settings of one search. */
    static final class Settings {
        String form = "F1";
        /** SystemScorer's metric the objective is read from. */
        int metric = SystemScorer.same(SystemScorer.SPREADS);
        int pen = SystemScorer.CUTS.length - 1;
        int divisor;
        int lambda = 12;
        /** The starting step, in radians on the sphere. */
        double sigma = 0.3;
        int maxEvals = 1500;
        /** A run stops when its step falls below this angle, in radians. */
        double minAngle = Math.toRadians(0.25);
        /** What a change in the objective must exceed to be one, for F2's cycles. */
        double noise;
        long seed = 1;
        int threads = 1;
        double[] sign;
        String run = "run";
    }

    /** F1's objective: the count of these coordinates, its own row. */
    static Objective f1(SystemScorer sc, Settings s) {
        return x -> sc.estimate(CountKeys.whole("candidate", whole(x, s.sign), null, s.divisor, true), null, s.pen)[0][s.metric];
    }

    /** F1r's objective, ten coordinates. */
    static Objective f1r(SystemScorer sc, Settings s) {
        return x -> sc.estimate(CountKeys.whole("candidate", wholeRunning(x, s.sign), null, s.divisor, false), null,
                s.pen)[0][s.metric];
    }

    /** F2's objective with the betting vector held: the play vector's coordinates. */
    static Objective f2Play(SystemScorer sc, Settings s, int[] bet) {
        return x -> {
            CountKeys.Count c = CountKeys.whole("candidate", whole(x, s.sign), bet, s.divisor, true);
            return sc.estimate(c, CountKeys.bettingVariant(c), s.pen)[2][s.metric];
        };
    }

    /** F2's objective with the play held: the betting vector's coordinates, scored on the held play. */
    static Objective f2Bet(SystemScorer sc, Settings s, int[] play) {
        SystemScorer.Held h = sc.hold(CountKeys.whole("held", play, null, s.divisor, true), s.pen);
        return x -> sc.betOn(h, CountKeys.whole("candidate", play, whole(x, s.sign), s.divisor, true).withBetting())[s.metric];
    }

    /** F2's objective on both vectors at once: eighteen coordinates, the play's then the bet's. */
    static Objective f2Joint(SystemScorer sc, Settings s) {
        return x -> {
            CountKeys.Count c = CountKeys.whole("candidate", whole(Arrays.copyOf(x, 9), s.sign),
                    whole(Arrays.copyOfRange(x, 9, 18), s.sign), s.divisor, true);
            return sc.estimate(c, CountKeys.bettingVariant(c), s.pen)[2][s.metric];
        };
    }

    // ------------------------------------------------------------------ one run

    /** A run's end: its final mean, what that scores, and the step it ended at, as an angle. */
    static final class Final {
        final double[] mean;
        final double value;
        final int evaluations;
        final double step;

        Final(double[] mean, double value, int evaluations, double step) {
            this.mean = mean;
            this.value = value;
            this.evaluations = evaluations;
            this.step = step;
        }
    }

    /** How a candidate is written in the log: its whole-number tags. */
    interface Writer {
        String of(double[] x);
    }

    /**
     * Runs CMA-ES on the objective until the evaluations given are spent or the step falls under
     * the least angle, from the state given when resuming (null to start afresh), logging every
     * evaluation and every generation's state. The final mean is scored once more and logged.
     */
    static Final run(Cma cma, Objective f, int maxEvals, double minAngle, int threads, SearchLog log, String run,
                     Writer w) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "count-search");
            t.setDaemon(true);
            return t;
        });
        try {
            while (cma.state.evaluations + cma.lambda <= maxEvals && cma.step() >= minAngle) {
                double[][] xs = cma.ask();
                List<Future<Double>> jobs = new ArrayList<>();
                for (double[] x : xs) {
                    jobs.add(pool.submit(() -> f.of(x)));
                }
                double[] v = new double[xs.length];
                for (int k = 0; k < xs.length; k++) {
                    v[k] = jobs.get(k).get();
                    if (log != null) {
                        log.evaluation(run, cma.state.generation, k, v[k], w.of(xs[k]));
                    }
                }
                cma.tell(xs, v);
                if (log != null) {
                    log.state(run, cma.state);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        double[] mean = cma.state.mean.clone();
        double value = f.of(mean);
        if (log != null) {
            log.finalMean(run, value, w.of(mean), cma.state.evaluations);
        }
        return new Final(mean, value, cma.state.evaluations, cma.step());
    }

    // ------------------------------------------------------------------ the log

    /**
     * The evaluation log: a header of lines beginning #, then lines of four kinds, tab-separated:
     * eval (run, generation, candidate, objective, tags), state (run, then Cma's state), final (run,
     * objective, tags, evaluations) and note (anything else a search records). Opened to resume, it
     * is cut back to its last whole generation's state.
     */
    static final class SearchLog implements AutoCloseable {
        final Path file;
        private final BufferedWriter out;

        SearchLog(Path file, String header, boolean resume) throws IOException {
            this.file = file;
            if (!resume) {
                if (Files.exists(file)) {
                    throw new IllegalStateException(file + " holds a search already: resume it, or log to another file");
                }
                Files.write(file, (header.endsWith("\n") ? header : header + "\n").getBytes(StandardCharsets.UTF_8));
            } else {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                int keep = 0;
                for (int k = 0; k < lines.size(); k++) {
                    String l = lines.get(k);
                    if (l.startsWith("#") || l.startsWith("state\t") || l.startsWith("final\t") || l.startsWith("note\t")
                            || l.startsWith("found\t")) {
                        keep = k + 1;
                    }
                }
                List<String> kept = new ArrayList<>(lines.subList(0, keep));
                if (!kept.isEmpty() && !kept.get(0).equals(header.split("\n")[0])) {
                    throw new IllegalStateException(file + " is another search's log: it begins\n" + kept.get(0)
                            + "\nnot\n" + header.split("\n")[0]);
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(tmp, (String.join("\n", kept) + "\n").getBytes(StandardCharsets.UTF_8));
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }

        synchronized void evaluation(String run, int generation, int k, double value, String tags) throws IOException {
            out.write("eval\t" + run + "\t" + generation + "\t" + k + "\t" + value + "\t" + tags + "\n");
        }

        synchronized void state(String run, Cma.State s) throws IOException {
            StringBuilder b = new StringBuilder("state\t" + run + "\t" + s.generation + "\t" + s.evaluations + "\t"
                    + s.sigma + "\t" + join(s.mean) + "\t" + join(s.pc) + "\t" + join(s.ps));
            for (double[] row : s.c) {
                b.append('\t').append(join(row));
            }
            out.write(b.append('\n').toString());
            out.flush();
        }

        synchronized void finalMean(String run, double value, String tags, int evaluations) throws IOException {
            out.write("final\t" + run + "\t" + value + "\t" + tags + "\t" + evaluations + "\n");
            out.flush();
        }

        /** A search's count, as candidates writes it: its form, divisor, tags (F2's betting tags after "|") and objective. */
        synchronized void found(String run, String form, int divisor, String tags, double value) throws IOException {
            out.write("found\t" + run + "\t" + form + "\t" + divisor + "\t" + tags + "\t" + value + "\n");
            out.flush();
        }

        synchronized void note(String run, String text) throws IOException {
            out.write("note\t" + run + "\t" + text.replace('\t', ' ').replace('\n', ' ') + "\n");
            out.flush();
        }

        /** A note, unless the log holds it already, as a resumed search that records it again would. */
        synchronized void noteOnce(String run, String text) throws IOException {
            String line = "note\t" + run + "\t" + text.replace('\t', ' ').replace('\n', ' ');
            out.flush();
            if (!Files.readAllLines(file, StandardCharsets.UTF_8).contains(line)) {
                note(run, text);
            }
        }

        /** A run's last state in the log, or null. */
        static Cma.State lastState(Path file, String run) throws IOException {
            Cma.State s = null;
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = l.split("\t", -1);
                if (f[0].equals("state") && f[1].equals(run)) {
                    s = new Cma.State();
                    s.generation = Integer.parseInt(f[2]);
                    s.evaluations = Integer.parseInt(f[3]);
                    s.sigma = Double.parseDouble(f[4]);
                    s.mean = split(f[5]);
                    s.pc = split(f[6]);
                    s.ps = split(f[7]);
                    s.c = new double[s.mean.length][];
                    for (int i = 0; i < s.mean.length; i++) {
                        s.c[i] = split(f[8 + i]);
                    }
                }
            }
            return s;
        }

        /** A run's final line, or null if it has not ended: {objective, tags, evaluations}. */
        static String[] finalOf(Path file, String run) throws IOException {
            String[] out = null;
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = l.split("\t", -1);
                if (f[0].equals("final") && f[1].equals(run)) {
                    out = new String[]{f[2], f[3], f[4]};
                }
            }
            return out;
        }

        private static String join(double[] x) {
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < x.length; k++) {
                b.append(k == 0 ? "" : ",").append(x[k]);
            }
            return b.toString();
        }

        private static double[] split(String s) {
            String[] p = s.split(",");
            double[] out = new double[p.length];
            for (int k = 0; k < p.length; k++) {
                out[k] = Double.parseDouble(p[k]);
            }
            return out;
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    /** Whole-number tags by rank as the log and the candidates file write them: A, 2 to 9, T. */
    static String tagText(int[] rankTag) {
        StringBuilder b = new StringBuilder();
        for (int r = 1; r <= 10; r++) {
            b.append(r == 1 ? "" : ",").append(rankTag[r]);
        }
        return b.toString();
    }

    /** Tags by rank from text: ten numbers, A, 2 to 9, T. */
    static double[] parseTags(String text) {
        String[] p = text.split(",");
        if (p.length != 10) {
            throw new IllegalArgumentException("ten tags, A and 2 to 9 and T: not " + text);
        }
        double[] t = new double[11];
        for (int r = 1; r <= 10; r++) {
            t[r] = Double.parseDouble(p[r - 1].trim());
        }
        return t;
    }

    // ------------------------------------------------------------------ the forms

    /**
     * A search of one form from one start, logged and resumable: F1 and F1r one run of CMA-ES;
     * F2 its cycles of blocks and the joint polish, each a run of its own in the log, named after
     * the search's run with a suffix. Gives the final count.
     */
    static CountKeys.Count search(SystemScorer sc, Settings s, double[] start, double[] startBet, SearchLog log,
                                  boolean resume, PrintStream out) throws Exception {
        if (s.sign == null) {
            s.sign = rankTags(CountKeys.whole("Hi-Lo", new int[]{0, -1, 1, 1, 1, 1, 1, 0, 0, 0, -1}, null, 1, true));
        }
        CountKeys.Count found = searchForm(sc, s, start, startBet, log, resume, out);
        if (log != null) {
            int[] play = new int[11];
            int[] bet = null;
            for (int r = 1; r <= 10; r++) {
                play[r] = found.rankTag(r);
            }
            if (found.betVector != null) {
                bet = new int[11];
                for (int k = 0; k < ShoeRun.KINDS; k++) {
                    bet[ShoeRun.rankOf(k)] = found.betVector[k];
                }
            }
            CountKeys.Count v = CountKeys.bettingVariant(found);
            double value = sc.estimate(found, v, s.pen)[v == null ? 0 : 2][s.metric];
            log.found(s.run, s.form, s.divisor, tagText(play) + (bet == null ? "" : "|" + tagText(bet)), value);
        }
        return found;
    }

    private static CountKeys.Count searchForm(SystemScorer sc, Settings s, double[] start, double[] startBet,
                                              SearchLog log, boolean resume, PrintStream out) throws Exception {
        switch (s.form) {
            case "F1": {
                Final f = cmaRun(s, f1(sc, s), onSphere(coordinates(start)), RADIUS, 9, s.sigma, s.run, s.maxEvals, log,
                        resume, x -> tagText(whole(x, s.sign)));
                return CountKeys.whole(s.run, whole(f.mean, s.sign), null, s.divisor, true);
            }
            case "F1r": {
                double[] x = new double[10];
                double[] bal = coordinates(start);
                System.arraycopy(bal, 0, x, 0, 9);
                x[9] = CountKeys.mean(start);
                double n = 0;
                for (int k = 0; k < 9; k++) {
                    n += bal[k] * bal[k];
                }
                double f0 = RADIUS / Math.sqrt(n);
                for (int k = 0; k < 10; k++) {
                    x[k] *= f0;
                }
                // On the sphere of the balanced part, the shift scaled with it: the objective sees
                // only the ten coordinates' direction, and a step keeps its angle.
                Final f = cmaRun(s, f1r(sc, s), x, RADIUS, 9, s.sigma, s.run, s.maxEvals, log, resume,
                        y -> tagText(wholeRunning(y, s.sign)));
                return CountKeys.whole(s.run, wholeRunning(f.mean, s.sign), null, s.divisor, false);
            }
            case "F2":
                return searchF2(sc, s, start, startBet == null ? start : startBet, log, resume, out);
            default:
                throw new IllegalArgumentException("the forms are F1, F2 and F1r, not " + s.form);
        }
    }

    /**
     * One run of CMA-ES from the mean given, on the sphere of that radius over its first sphereDims
     * coordinates, starting at the step given as an angle in radians; resumed from the log when
     * asked, or given as the log has it when it had ended.
     */
    private static Final cmaRun(Settings s, Objective f, double[] mean, double radius, int sphereDims, double step,
                                String run, int maxEvals, SearchLog log, boolean resume, Writer w) throws Exception {
        Cma cma = new Cma(mean.length, s.lambda, s.seed + run.hashCode(), mean, step * radius, radius, sphereDims);
        if (resume && log != null) {
            Cma.State last = SearchLog.lastState(log.file, run);
            String[] done = SearchLog.finalOf(log.file, run);
            if (done != null) {
                // This run ended before the search stopped: its end, as logged.
                return new Final(last == null ? cma.state.mean.clone() : last.mean, Double.parseDouble(done[0]),
                        Integer.parseInt(done[2]), last == null ? cma.step() : last.sigma / radius);
            }
            if (last != null) {
                cma.restore(last);
            }
        }
        return run(cma, f, maxEvals, s.minAngle, s.threads, log, run, w);
    }

    /**
     * The step a block after the first of its kind starts from: the step the block before it ended
     * at, no more than the starting step and no less than twice the least angle, so it runs.
     */
    static double nextStep(Settings s, double ended) {
        return Math.min(s.sigma, Math.max(ended, 2 * s.minAngle));
    }

    /**
     * F2: from a play vector and a betting vector, the betting block (the play held), then the
     * play block (the bets held), each a run of CMA-ES with a seventh of the budget, over at most
     * three cycles, until a cycle moves the objective by no more than the noise floor; then both
     * together, a joint polish with a third of the budget, on the eighteen coordinates kept on the
     * sphere of radius sqrt(2) times Hi-Lo's, each vector on its own sphere at the start.
     *
     * The first block of each kind starts at the search's step; each later one, and the polish,
     * from the step the block before it of its kind ended at (nextStep), so a later block refines
     * where the one before left off rather than searching afresh. A block's (or the polish's) end
     * is taken only where it scores at least what its start does, otherwise its start is kept and
     * the log says so; so F2 ends at or above its start and every block's end. The start's score is
     * logged as a note.
     */
    private static CountKeys.Count searchF2(SystemScorer sc, Settings s, double[] play, double[] bet, SearchLog log,
                                            boolean resume, PrintStream out) throws Exception {
        int[] p = whole(onSphere(coordinates(play)), s.sign);
        int[] b = whole(onSphere(coordinates(bet)), s.sign);
        int block = Math.max(s.lambda, s.maxEvals / 7);
        double value = f2Joint(sc, s).of(join(onSphere(coordinates(toDouble(p))), onSphere(coordinates(toDouble(b)))));
        if (log != null) {
            log.noteOnce(s.run, "start " + value + " " + tagText(p) + "|" + tagText(b));
        }
        double betStep = s.sigma;
        double playStep = s.sigma;
        for (int cycle = 1; cycle <= 3; cycle++) {
            double before = value;
            String name = s.run + "/cycle" + cycle;
            Final fb = cmaRun(s, f2Bet(sc, s, p), onSphere(coordinates(toDouble(b))), RADIUS, 9, betStep, name + "/bet",
                    block, log, resume, x -> tagText(whole(x, s.sign)));
            betStep = nextStep(s, fb.step);
            if (fb.value >= value) {
                b = whole(fb.mean, s.sign);
                value = fb.value;
            } else if (log != null) {
                log.noteOnce(name + "/bet", "kept its start, " + value + ", above its end");
            }
            Final fp = cmaRun(s, f2Play(sc, s, b), onSphere(coordinates(toDouble(p))), RADIUS, 9, playStep, name + "/play",
                    block, log, resume, x -> tagText(whole(x, s.sign)));
            playStep = nextStep(s, fp.step);
            if (fp.value >= value) {
                p = whole(fp.mean, s.sign);
                value = fp.value;
            } else if (log != null) {
                log.noteOnce(name + "/play", "kept its start, " + value + ", above its end");
            }
            if (out != null) {
                out.printf(Locale.ROOT, "F2 cycle %d: %.6f%% after the betting block, %.6f%% after the play block, "
                        + "%.6f%% kept%n", cycle, 100 * fb.value, 100 * fp.value, 100 * value);
            }
            if (value - before <= s.noise) {
                break;
            }
        }
        double[] joint = join(onSphere(coordinates(toDouble(p))), onSphere(coordinates(toDouble(b))));
        double jointStep = Math.max(2 * s.minAngle, Math.max(betStep, playStep) / Math.sqrt(2));
        Final fj = cmaRun(s, f2Joint(sc, s), joint, RADIUS * Math.sqrt(2), 18, jointStep, s.run + "/joint",
                Math.max(s.lambda, s.maxEvals / 3), log, resume,
                x -> tagText(whole(Arrays.copyOf(x, 9), s.sign)) + "|" + tagText(whole(Arrays.copyOfRange(x, 9, 18), s.sign)));
        if (fj.value >= value) {
            p = whole(Arrays.copyOf(fj.mean, 9), s.sign);
            b = whole(Arrays.copyOfRange(fj.mean, 9, 18), s.sign);
        } else if (log != null) {
            log.noteOnce(s.run + "/joint", "kept its start, " + value + ", above its end");
        }
        return CountKeys.whole(s.run, p, b, s.divisor, true);
    }

    private static double[] toDouble(int[] t) {
        double[] out = new double[t.length];
        for (int k = 0; k < t.length; k++) {
            out[k] = t[k];
        }
        return out;
    }

    private static double[] join(double[] a, double[] b) {
        double[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ------------------------------------------------------------------ the command

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length > 0 && args[0].equals("candidates")) {
            // candidates OUT LOG...: the searches' final counts, as a candidates file for the twin.
            List<Path> logs = new ArrayList<>();
            for (int a = 2; a < args.length; a++) {
                logs.add(Paths.get(args[a]));
            }
            Path out = Paths.get(args[1]);
            if (Files.exists(out)) {
                throw new IllegalStateException(out + " exists; write the candidates to a new file");
            }
            Files.write(out, candidates(logs).getBytes(StandardCharsets.UTF_8));
            CustomCounts.read(out);
            return;
        }
        Path states = null;
        Path logFile = null;
        Settings s = new Settings();
        double[] start = null;
        double[] startBet = null;
        boolean resume = false;
        int half = -1;
        double mPlay = SystemScorer.M_PLAY;
        for (int a = 0; a < args.length; a++) {
            switch (args[a]) {
                case "--states": states = Paths.get(args[++a]); break;
                case "--log": logFile = Paths.get(args[++a]); break;
                case "--form": s.form = args[++a]; break;
                case "--metric": {
                    String m = args[++a];
                    s.metric = m.equals("flat") ? SystemScorer.FLAT : m.equals("same8")
                            ? SystemScorer.same(SystemScorer.SPREADS) : -1;
                    if (s.metric < 0) {
                        throw new IllegalArgumentException("the metrics are same8 and flat, not " + m);
                    }
                    break;
                }
                case "--pen": s.pen = Integer.parseInt(args[++a]); break;
                case "--divisor": s.divisor = Integer.parseInt(args[++a]); break;
                case "--start": start = parseTags(args[++a]); break;
                case "--start-count": start = rankTags(catalogCount(args[++a])); break;
                case "--start-random": {
                    Random rnd = new Random(Long.parseLong(args[++a]));
                    start = new double[11];
                    for (int r = 1; r <= 10; r++) {
                        start[r] = rnd.nextGaussian();
                    }
                    break;
                }
                case "--start-bet": startBet = parseTags(args[++a]); break;
                case "--lambda": s.lambda = Integer.parseInt(args[++a]); break;
                case "--sigma": s.sigma = Double.parseDouble(args[++a]); break;
                case "--max-evals": s.maxEvals = Integer.parseInt(args[++a]); break;
                case "--min-angle": s.minAngle = Math.toRadians(Double.parseDouble(args[++a])); break;
                case "--noise": s.noise = Double.parseDouble(args[++a]); break;
                case "--seed": s.seed = Long.parseLong(args[++a]); break;
                case "--threads": s.threads = Integer.parseInt(args[++a]); break;
                case "--half": half = Integer.parseInt(args[++a]); break;
                case "--m-play": mPlay = Double.parseDouble(args[++a]); break;
                case "--run": s.run = args[++a]; break;
                case "--sign": s.sign = parseTags(args[++a]); break;
                case "--resume": resume = true; break;
                default: throw new IllegalArgumentException("unknown option " + args[a]);
            }
        }
        if (states == null || logFile == null || start == null || s.divisor < 1) {
            throw new IllegalArgumentException("--states, --log, a start and --divisor are needed");
        }
        CustomCounts.requireTwin(states);
        StateStore.Header header = StateStore.header(states);
        int shoes = StateStore.shoesDone(states);
        ScoringStates st = FullIndexPilot.load(states, shoes);
        if (half >= 0) {
            st = st.subRun(half, 2);
        }
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, 0, Bootstrap.SEED), mPlay);
        String head = header(FullIndexRun.commit(), FullIndexRun.stateFile(states), header.seed, half, st.size, mPlay, s,
                start, startBet);
        PrintStream out = System.out;
        out.println(head);
        long t0 = System.nanoTime();
        CountKeys.Count found;
        try (SearchLog log = new SearchLog(logFile, head, resume)) {
            found = search(sc, s, start, startBet, log, resume, out);
            log.note(s.run, "found " + found.describe());
        }
        out.printf("Found %s in %.0f s.%n", found.describe(), (System.nanoTime() - t0) / 1e9);
    }

    /**
     * The log's header line: the commit, the state file and its seed, and every setting that sets
     * the search's course, the sign reference among them ("Hi-Lo" when none is given), so a resume
     * with any of them changed is refused (SearchLog).
     */
    static String header(String commit, String stateFile, long seed, int half, int size, double mPlay, Settings s,
                         double[] start, double[] startBet) {
        return "# CountSearch " + commit + ", " + stateFile + ", header seed " + seed + (half >= 0 ? ", half " + half : "")
                + ", " + size + " valued states; form " + s.form + ", metric " + s.metric + ", penetration " + s.pen
                + ", divisor " + s.divisor + ", M_play " + mPlay + ", lambda " + s.lambda + ", sigma " + s.sigma
                + ", max evaluations " + s.maxEvals + ", least angle " + Math.toDegrees(s.minAngle) + " degrees, noise "
                + s.noise + ", seed " + s.seed + ", sign " + (s.sign == null ? "Hi-Lo" : Arrays.toString(s.sign))
                + ", run " + s.run + ", start " + Arrays.toString(start)
                + (startBet == null ? "" : ", betting start " + Arrays.toString(startBet));
    }

    // ------------------------------------------------------------------ slices

    /**
     * A slice through a point on the sphere (COUNT_DESIGN.md, section 4, "Slices peak at the
     * optimum"): the objective along the great circle from the centre toward a direction, at
     * angles from -maxAngle to maxAngle in radians, points of them, as {angle, value} pairs. The
     * direction is first made perpendicular to the centre; a direction along the centre has no
     * slice and gives none.
     */
    static double[][] slice(Objective f, double[] centre, double[] direction, double maxAngle, int points) {
        double[] c = onSphere(centre);
        double cd = 0;
        double cc = 0;
        for (int k = 0; k < c.length; k++) {
            cd += c[k] * direction[k];
            cc += c[k] * c[k];
        }
        double[] d = new double[c.length];
        double dd = 0;
        for (int k = 0; k < c.length; k++) {
            d[k] = direction[k] - cd / cc * c[k];
            dd += d[k] * d[k];
        }
        if (!(dd > 1e-24 * cc)) {
            return new double[0][];
        }
        for (int k = 0; k < c.length; k++) {
            d[k] *= RADIUS / Math.sqrt(dd);
        }
        double[][] out = new double[points][];
        for (int i = 0; i < points; i++) {
            double a = points == 1 ? 0 : -maxAngle + 2 * maxAngle * i / (points - 1);
            double[] x = new double[c.length];
            for (int k = 0; k < c.length; k++) {
                x[k] = Math.cos(a) * c[k] + Math.sin(a) * d[k];
            }
            out[i] = new double[]{a, f.of(x)};
        }
        return out;
    }

    /**
     * The least-squares quadratic v = a + b t + c t^2 through a slice's points, and from it the peak:
     * {a, b, c, the angle of the peak, the headroom (the peak's value less the value at 0)}, the
     * peak NaN where the fit does not curve down. Near a maximum the loss grows as c t^2, so every
     * angle under sqrt(noise / -c) costs less than the noise floor.
     */
    static double[] quadratic(double[][] pts) {
        CountRegression.Wls w = new CountRegression.Wls(2);
        for (double[] p : pts) {
            w.add(new double[]{p[0], p[0] * p[0]}, p[1], 1);
        }
        double[] b = w.solve();
        double peak = b[2] < 0 ? -b[1] / (2 * b[2]) : Double.NaN;
        double headroom = b[2] < 0 ? b[1] * peak + b[2] * peak * peak : Double.NaN;
        return new double[]{b[0], b[1], b[2], peak, headroom};
    }

    // ------------------------------------------------------------------ candidates

    /**
     * A candidates file (CustomCounts) of every search's count in the logs given, as each search's
     * found line records it: named after its run, of its form and divisor, its whole-number tags
     * (an F2 count's betting tags after "|"), with the objective it scored on the twin.
     */
    static String candidates(List<Path> logs) throws IOException {
        StringBuilder b = new StringBuilder("{ \"kind\": \"candidates\", \"K\": " + CountKeys.K + ", \"counts\": [\n");
        boolean first = true;
        for (Path log : logs) {
            for (String l : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                String[] f = l.split("\t", -1);
                if (!f[0].equals("found")) {
                    continue;
                }
                String[] vectors = f[4].split("\\|");
                b.append(first ? "" : ",\n").append("  { \"name\": \"").append(f[1].replace("\"", "'"))
                        .append("\", \"family\": \"").append(f[2]).append("\", \"role\": \"candidate\", \"form\": \"")
                        .append(f[2].equals("F1r") ? "running" : "true").append("\", \"divisor\": ").append(f[3])
                        .append(", \"objective\": ").append(f[5]).append(",\n    \"tags\": [").append(vectors[0])
                        .append("]");
                if (vectors.length > 1) {
                    b.append(",\n    \"betting\": [").append(vectors[1]).append("]");
                }
                b.append(" }");
                first = false;
            }
        }
        return b.append(" ] }\n").toString();
    }

    static CountKeys.Count catalogCount(String system) {
        for (CountKeys.Count c : CountKeys.catalog()) {
            if (c.systems.contains(system)) {
                return c;
            }
        }
        throw new IllegalArgumentException("no catalog system named " + system);
    }
}
