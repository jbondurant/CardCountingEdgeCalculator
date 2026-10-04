import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The pilot of FULL_INDEX_PLAYS.md sections 4 and 6, run on the first shoes of the run's state
 * file before the full run is sized.
 *
 * Section 4. The full run's bootstrap refits the first decisions, insurance and bets on every
 * resample but holds the later plays as fitted, and with them every Hit and Split value, since
 * refitting them would mean passes of player trees per resample. COUNTING_COMPARISON.md section 6
 * found that holding a play fit fixed understated a difference's SE two to three times for the
 * first decisions, so this measures what holding the later plays does. The trees of every state
 * are kept in memory, and on each resample every count's two folds are fitted again from that
 * resample's weights by walks alone, passes, clean-ups and all, as the estimate was fitted. Each
 * count is then scored three ways on the same resample with the same weights (Witness lets the
 * test check this): first decisions only; full play with the later plays held as the estimate
 * fitted them, as the full run will score it; and full play with the resample's own later plays.
 * The SE of each count's difference from Hi-Lo is taken resample by resample in each, and the
 * ratio of the refitted SE to the held one is how much the full run's intervals must be widened
 * (SystemScorer.difference). The gain from full play, full less first decisions only, is given
 * the same way. Each ratio carries a jackknife SE over the resamples, so a ratio near 1 can be
 * told from one that is not.
 *
 * Section 6. The estimate's fit runs as the full run will: each state's trees built afresh every
 * round and split values kept for one state's visit only. Each round's figures are printed:
 * seconds on the wall, CPU seconds a state for the trees, a walk, and a split valued, and the
 * distinct post-split plays per (state, pair deal) over every strategy of the round (one per
 * count in a fitting round, two in the last), with how often a split was read from the stored
 * values and how often it had to be valued. The resamples then walk kept trees and keep every
 * split valued, which only the pilot can afford. The heap's peak is shown for both, and the
 * full run is projected from these figures.
 *
 * The state file is only read. Run it after ./run-tests.sh has compiled the sources, with the
 * mongo jar on the class path for the catalog:
 * java -Xmx8g -cp build/classes:lib/mongo-java-driver-3.12.14.jar FullIndexPilot [state file]
 * [shoes] [resamples] [threads]
 */
public final class FullIndexPilot {

    /** The counts compared, by their catalog names, Hi-Lo first: the differences are taken from it. */
    static final String[] SYSTEMS = {"Hi-Lo", "KO (Knock-Out)", "Red Seven (Red 7)", "Omega II", "Wong Halves"};
    /** 80%, the penetration of the first-decision measurement in COUNTING_COMPARISON.md section 6. */
    static final int PEN = 2;
    /** The headline's metric, the same schedule at spread 1 to 8, and flat. */
    static final int SAME_8 = SystemScorer.same(SystemScorer.SPREADS);
    static final int[] METRICS = {SAME_8, SystemScorer.FLAT};
    static final String[] METRIC_NAMES = {"1-8 same schedule", "flat"};

    /** The three ways each count is scored on every resample. */
    static final int FIRST = 0;
    static final int HELD = 1;
    static final int REFIT = 2;
    static final String[] VARIANTS = {"first decisions only", "later plays held", "later plays refitted"};

    /**
     * Sees the weights of each resample as each variant uses them: the three scorings as
     * variants 0 to 2, and the refit's strategies as variant -1 - fold, with the arrays each
     * strategy holds. The test of the pairing listens here.
     */
    interface Witness {
        void saw(int variant, int resample, CountKeys.Count count, double[] w, double[] units);
    }

    final SystemScorer scorer;
    final ScoringStates st;
    final SystemScorer.Pen pen;
    final List<CountKeys.Count> counts;
    final int threads;
    private final SystemScorer.Parts[] parts;
    private final SystemScorer.InsuranceParts[] ins;
    /** Each count's fits on its whole training folds: the later plays the full run holds on every resample. */
    final List<FullIndexFit.Fits> estimate;
    final SystemScorer.PlayValues[][] held;
    /** The estimate's rounds, run as the full run runs them. */
    final List<FullIndexFit.RoundStats> estimateRounds;
    final double estimateWall;
    /** [variant][count][metric slot]: the estimate, first decisions only and full play (held and refitted alike). */
    final double[][][] point = new double[3][][];
    /** [variant][count][metric slot][resample]. */
    final double[][][][] boot;
    /** Per resample: wall seconds of the refit and of the scoring, rounds, and strategies capped. */
    final double[] refitWall;
    final double[] scoreWall;
    final int[] refitRounds;
    final int[] capped;
    /** How many rounds each strategy fitted was active (passes, clean-ups and the last), the estimate's first. */
    final List<Integer> strategyRounds = new ArrayList<>();
    /** Every resample's rounds, in order. */
    final List<FullIndexFit.RoundStats> resampleRounds = new ArrayList<>();
    int done;
    Witness witness;
    private FullIndexFit refits;

    /**
     * Fits every count's two folds on their whole training folds, with trees from estimateTrees,
     * and scores the estimate. The rounds keep split values between rounds only when keepSplits.
     */
    FullIndexPilot(SystemScorer scorer, List<CountKeys.Count> counts, FullIndexFit.Trees estimateTrees,
                   boolean keepSplits, int threads, int resamples) throws Exception {
        if (!counts.get(0).systems.contains("Hi-Lo")) {
            throw new IllegalArgumentException("Hi-Lo comes first: the differences are taken from it");
        }
        if (resamples > scorer.boot.resamples) {
            throw new IllegalArgumentException(resamples + " resamples asked of a bootstrap of " + scorer.boot.resamples);
        }
        this.scorer = scorer;
        this.st = scorer.st;
        this.pen = scorer.pen(PEN);
        this.counts = counts;
        this.threads = threads;
        int nc = counts.size();
        parts = new SystemScorer.Parts[nc];
        ins = new SystemScorer.InsuranceParts[nc];
        estimate = FullIndexFit.plan(scorer, counts, new int[]{PEN}, false);
        for (int c = 0; c < nc; c++) {
            parts[c] = estimate.get(c).fold[PEN][0].parts;
            ins[c] = scorer.insuranceParts(parts[c], pen);
        }
        List<FullIndexFit.Strategy> all = new ArrayList<>();
        for (FullIndexFit.Fits f : estimate) {
            all.addAll(f.all());
        }
        FullIndexFit fit = new FullIndexFit(st, scorer.rules(), estimateTrees, threads, keepSplits, false);
        long start = System.nanoTime();
        fit.run(all);
        estimateWall = (System.nanoTime() - start) / 1e9;
        estimateRounds = fit.rounds;
        for (FullIndexFit.Strategy t : all) {
            strategyRounds.add(t.passes + t.cleanUps + 1);
        }
        held = new SystemScorer.PlayValues[nc][];
        for (int c = 0; c < nc; c++) {
            held[c] = estimate.get(c).values(PEN);
        }
        point[FIRST] = new double[nc][];
        point[HELD] = new double[nc][];
        point[REFIT] = point[HELD];
        for (int c = 0; c < nc; c++) {
            point[FIRST][c] = pick(scorer.scoreWith(counts.get(c), PEN, null, pen.weight, null));
            point[HELD][c] = pick(scorer.scoreWith(counts.get(c), PEN, held[c], pen.weight, null));
        }
        boot = new double[3][nc][METRICS.length][resamples];
        refitWall = new double[resamples];
        scoreWall = new double[resamples];
        refitRounds = new int[resamples];
        capped = new int[resamples];
    }

    private static double[] pick(double[] all) {
        double[] out = new double[METRICS.length];
        for (int k = 0; k < out.length; k++) {
            out[k] = all[METRICS[k]];
        }
        return out;
    }

    /** The resamples walk these trees, keeping every split valued from resample to resample. */
    void refitOn(FullIndexFit.Trees trees) {
        refits = new FullIndexFit(st, scorer.rules(), trees, threads, true, false);
    }

    /** Resample r's weights of the penetration's states: 1/q times the shoe's multiplicity, and the multiplicity. */
    double[][] weights(int r) {
        int n = pen.states.length;
        double[] w = new double[n];
        double[] u = new double[n];
        for (int j = 0; j < n; j++) {
            u[j] = scorer.boot.mult(r, st.shoe[pen.states[j]]);
            w[j] = pen.weight[j] * u[j];
        }
        return new double[][]{w, u};
    }

    /** Every count's two folds fitted afresh under the weights given, in one run of passes over the trees. */
    FullIndexFit.Strategy[][] refit(int r, double[] w, double[] u) throws Exception {
        int nc = counts.size();
        FullIndexFit.Strategy[][] by = new FullIndexFit.Strategy[nc][2];
        List<FullIndexFit.Strategy> all = new ArrayList<>();
        for (int c = 0; c < nc; c++) {
            for (int f = 0; f < 2; f++) {
                FullIndexFit.Strategy t = new FullIndexFit.Strategy(scorer, parts[c], ins[c], counts.get(c), PEN, f,
                        false, w, u, null);
                if (witness != null) {
                    witness.saw(-1 - f, r, counts.get(c), t.w, t.units);
                }
                by[c][f] = t;
                all.add(t);
            }
        }
        refits.run(all);
        return by;
    }

    /** Resample r: the later plays refitted, then every count scored the three ways under the same weights. */
    void resample(int r) throws Exception {
        if (refits == null) {
            throw new IllegalStateException("no trees to refit on (refitOn)");
        }
        double[][] wu = weights(r);
        double[] w = wu[0];
        double[] u = wu[1];
        int before = refits.rounds.size();
        long t0 = System.nanoTime();
        FullIndexFit.Strategy[][] by = refit(r, w, u);
        long t1 = System.nanoTime();
        int cap = 0;
        for (FullIndexFit.Strategy[] pair : by) {
            for (FullIndexFit.Strategy t : pair) {
                strategyRounds.add(t.passes + t.cleanUps + 1);
                cap += t.capped ? 1 : 0;
            }
        }
        for (int c = 0; c < counts.size(); c++) {
            SystemScorer.PlayValues[] mine = {by[c][0].values, by[c][1].values};
            SystemScorer.PlayValues[][] values = {null, held[c], mine};
            for (int v = 0; v < 3; v++) {
                if (witness != null) {
                    witness.saw(v, r, counts.get(c), w, u);
                }
                double[] m = pick(scorer.scoreWith(counts.get(c), PEN, values[v], w, u));
                for (int k = 0; k < METRICS.length; k++) {
                    boot[v][c][k][r] = m[k];
                }
            }
        }
        long t2 = System.nanoTime();
        refitWall[r] = (t1 - t0) / 1e9;
        scoreWall[r] = (t2 - t1) / 1e9;
        refitRounds[r] = refits.rounds.size() - before;
        capped[r] = cap;
        resampleRounds.addAll(refits.rounds.subList(before, refits.rounds.size()));
        done = Math.max(done, r + 1);
    }

    // ------------------------------------------------------------------ the figures

    /** Count c's difference from Hi-Lo, or with gain its gain from full play, in metric slot k, over the resamples done. */
    double[] differences(int variant, int c, int k, boolean gain) {
        double[] d = new double[done];
        for (int r = 0; r < done; r++) {
            d[r] = boot[variant][c][k][r] - (gain ? boot[FIRST][c][k][r] : boot[variant][0][k][r]);
        }
        return d;
    }

    double estimateDifference(int variant, int c, int k, boolean gain) {
        return point[variant][c][k] - (gain ? point[FIRST][c][k] : point[variant][0][k]);
    }

    /**
     * sd(a) / sd(b) over the resamples, and its delete-one jackknife SE: the ratio is itself
     * estimated from a few hundred resamples, so it is given with how much it would move with
     * other ones.
     */
    static double[] ratio(double[] a, double[] b) {
        int n = a.length;
        double r = sd(a, -1) / sd(b, -1);
        double[] loo = new double[n];
        double mean = 0;
        for (int i = 0; i < n; i++) {
            loo[i] = sd(a, i) / sd(b, i);
            mean += loo[i];
        }
        mean /= n;
        double ss = 0;
        for (double x : loo) {
            ss += (x - mean) * (x - mean);
        }
        return new double[]{r, Math.sqrt((n - 1.0) / n * ss)};
    }

    /** The sample SD of x, leaving out element skip (none when -1). */
    static double sd(double[] x, int skip) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < x.length; i++) {
            if (i != skip) {
                sum += x[i];
                n++;
            }
        }
        double mean = sum / n;
        double ss = 0;
        for (int i = 0; i < x.length; i++) {
            if (i != skip) {
                ss += (x[i] - mean) * (x[i] - mean);
            }
        }
        return Math.sqrt(ss / (n - 1));
    }

    /** The SE table: differences from Hi-Lo, then the gain from full play, in each metric. */
    void printSes(PrintStream out) {
        for (int k = 0; k < METRICS.length; k++) {
            out.printf("%nDifference from Hi-Lo at 80%%, %s, over %d resamples (SEs in %% of a unit per round):%n%n",
                    METRIC_NAMES[k], done);
            out.println("| Count | Estimate, full play | SE, first decisions only | SE, later plays held | "
                    + "SE, later plays refitted | Refitted / held | its jackknife SE |");
            out.println("|---|---|---|---|---|---|---|");
            for (int c = 1; c < counts.size(); c++) {
                row(out, c, k, false);
            }
            out.printf("%nGain from full play at 80%%, %s: full play less first decisions only, paired:%n%n",
                    METRIC_NAMES[k]);
            out.println("| Count | Estimate | SE, later plays held | SE, later plays refitted | Refitted / held | "
                    + "its jackknife SE |");
            out.println("|---|---|---|---|---|---|");
            for (int c = 0; c < counts.size(); c++) {
                row(out, c, k, true);
            }
        }
    }

    private void row(PrintStream out, int c, int k, boolean gain) {
        double[] heldD = differences(HELD, c, k, gain);
        double[] refitD = differences(REFIT, c, k, gain);
        Bootstrap.Interval h = Bootstrap.interval(estimateDifference(HELD, c, k, gain), heldD);
        Bootstrap.Interval f = Bootstrap.interval(estimateDifference(REFIT, c, k, gain), refitD);
        double[] q = ratio(refitD, heldD);
        String name = counts.get(c).name;
        if (gain) {
            out.printf("| %s | %+.4f%% | %.4f%% | %.4f%% | %.2f | %.2f |%n", name, 100 * h.estimate, 100 * h.se,
                    100 * f.se, q[0], q[1]);
        } else {
            Bootstrap.Interval first = Bootstrap.interval(estimateDifference(FIRST, c, k, false),
                    differences(FIRST, c, k, false));
            out.printf("| %s | %+.4f%% | %.4f%% | %.4f%% | %.4f%% | %.2f | %.2f |%n", name, 100 * h.estimate,
                    100 * first.se, 100 * h.se, 100 * f.se, q[0], q[1]);
        }
    }

    /** A round's figures, as the full run will meet them. */
    static void printRound(PrintStream out, FullIndexFit.RoundStats r) {
        double splitCpu = r.splitValued == 0 ? 0 : 1000 * r.splitCpu / r.splitValued;
        out.printf("| %d | %d | %d | %d | %.1f | %.4f | %.4f | %.2f | %d | %d | %d | %d | %.1f | %d | %d | %.2f | %d |%n",
                r.round, r.strategies, r.states, r.walks, r.wall, r.wall / Math.max(1, r.states),
                r.treeCpu / Math.max(1, r.states), 1000 * r.walkCpu / Math.max(1, r.walks), r.splitAsks, r.splitChart,
                r.splitKept, r.splitValued, splitCpu, r.pairDeals, r.distinctPlays,
                r.pairDeals == 0 ? 0 : (double) r.distinctPlays / r.pairDeals, r.mostPlays);
    }

    static void roundHeader(PrintStream out) {
        out.println("| Round | Strategies | States | Walks | Wall s | Wall s a state | Tree CPU s a state | "
                + "Walk CPU ms | Split asks | Chart's (stored) | Already valued | Valued | CPU ms a split valued | "
                + "Pair deals | Distinct plays | Per pair deal | Most |");
        out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
    }

    static FullIndexFit.RoundStats total(List<FullIndexFit.RoundStats> rounds) {
        FullIndexFit.RoundStats t = new FullIndexFit.RoundStats();
        for (FullIndexFit.RoundStats r : rounds) {
            t.add(r);
            t.wall += r.wall;
            t.strategies += r.strategies;
        }
        t.round = -1;
        return t;
    }

    // ------------------------------------------------------------------ the run

    public static void main(String[] args) throws Exception {
        Path data = Paths.get(args.length > 0 ? args[0] : "runs/montreal-80.states");
        int shoes = args.length > 1 ? Integer.parseInt(args[1]) : 1417;
        int resamples = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : 6;
        PrintStream out = System.out;
        long t0 = System.nanoTime();
        ScoringStates st = load(data, shoes);
        RoundRules rules = st.rules;
        SystemScorer scorer = new SystemScorer(st, new Bootstrap(shoes, resamples, Bootstrap.SEED), SystemScorer.M_PLAY);
        List<CountKeys.Count> counts = new ArrayList<>();
        for (String s : SYSTEMS) {
            counts.add(named(s));
        }
        out.printf("# Full-index pilot%n%n");
        out.printf("FullIndexPilot: the first %d shoes of %s, %d valued states (%d at 80%%), read and never written; "
                        + "%d resamples of seed %d; %d threads of %d processors; max heap %.0f MB; java %s.%n", shoes,
                data, st.size, scorer.pen(PEN).states.length, resamples, Bootstrap.SEED, threads,
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() / 1e6,
                System.getProperty("java.vm.version"));
        out.printf("Started %s, load average %.2f; states read in %.1f s.%n", java.time.LocalDateTime.now(),
                FullIndexTimingReport.loadAverage(), (System.nanoTime() - t0) / 1e9);
        out.flush();

        FullIndexTimingReport.resetPeaks();
        FullIndexPilot pilot = new FullIndexPilot(scorer, counts, FullIndexFit.built(st), false, threads, resamples);
        long estimatePeak = FullIndexTimingReport.peakHeap();
        long estimateHeld = usedAfterGc();
        out.printf("%n## The estimate's fit, as the full run runs it%n%n");
        out.printf("Every count's two folds at 80%%, fitted on the whole training folds in %.1f s on the wall: each "
                + "state's trees built afresh every round and split values kept for one state's visit only. Peak heap "
                + "%.0f MB (each heap pool's peak added, so at most this); %.0f MB held after it, with the fits.%n%n",
                pilot.estimateWall, estimatePeak / 1e6, estimateHeld / 1e6);
        for (FullIndexFit.Fits f : pilot.estimate) {
            for (FullIndexFit.Strategy t : f.all()) {
                out.printf("- %s: %d passes, %s, %d clean-ups (%d later cells flipped), %d later groups, %d first-decision "
                                + "groups, later cells left to the chart for no reach %d and ties %d%n", t, t.passes,
                        t.converged ? "converged" : "capped", t.cleanUps, t.flipped, t.later.count(), t.groups.count(),
                        t.laterNoReach, t.laterTies);
            }
        }
        out.println();
        roundHeader(out);
        for (FullIndexFit.RoundStats r : pilot.estimateRounds) {
            printRound(out, r);
        }
        FullIndexFit.RoundStats et = total(pilot.estimateRounds);
        out.printf("%nIn all: %.1f s on the wall, %d walks, %.4f s CPU a state for the trees, %.3f ms CPU a walk, "
                        + "%.1f ms CPU a split valued, %.3f splits valued a walk; CPU %.1f s over %.1f s wall, %.2f "
                        + "threads busy.%n", et.wall, et.walks, et.treeCpu / et.states, 1000 * et.walkCpu / et.walks,
                1000 * et.splitCpu / Math.max(1, et.splitValued), (double) et.splitValued / et.walks,
                et.treeCpu + et.walkCpu + et.splitCpu, et.wall, (et.treeCpu + et.walkCpu + et.splitCpu) / et.wall);
        out.flush();

        long k0 = System.nanoTime();
        FullIndexFit.Trees kept = FullIndexFit.kept(st, rules, threads);
        double keptWall = (System.nanoTime() - k0) / 1e9;
        long withTrees = usedAfterGc();
        out.printf("%n## The resamples%n%nEvery state's trees built once and kept, in %.1f s on the wall: %.0f MB held "
                + "with them, %.0f KB a state.%n%n", keptWall, withTrees / 1e6, (withTrees - estimateHeld) / 1e3 / st.size);
        out.flush();
        FullIndexTimingReport.resetPeaks();
        pilot.refitOn(kept);
        long r0 = System.nanoTime();
        for (int r = 0; r < resamples; r++) {
            pilot.resample(r);
            out.printf("resample %d: refit %.1f s in %d rounds, %d of %d strategies capped; scored %.1f s; %s:",
                    r, pilot.refitWall[r], pilot.refitRounds[r], pilot.capped[r], 2 * counts.size(), pilot.scoreWall[r],
                    METRIC_NAMES[0]);
            for (int c = 0; c < counts.size(); c++) {
                out.printf(" %s %+.5f/%+.5f/%+.5f", counts.get(c).name, 100 * pilot.boot[FIRST][c][0][r],
                        100 * pilot.boot[HELD][c][0][r], 100 * pilot.boot[REFIT][c][0][r]);
            }
            out.println();
            if ((r + 1) % 25 == 0 && r + 1 < resamples) {
                double each = (System.nanoTime() - r0) / 1e9 / (r + 1);
                out.printf("after %d resamples, %.1f s each, %.0f min to go; load average %.2f%n", r + 1, each,
                        each * (resamples - r - 1) / 60, FullIndexTimingReport.loadAverage());
                pilot.printSes(out);
                out.println();
            }
            out.flush();
        }
        double resampleWall = (System.nanoTime() - r0) / 1e9;
        long resamplePeak = FullIndexTimingReport.peakHeap();

        out.printf("%n## Results%n");
        pilot.printSes(out);
        FullIndexFit.RoundStats rt = total(pilot.resampleRounds);
        int strategies = 0;
        int cappedAll = 0;
        double rounds = 0;
        for (int r = 0; r < resamples; r++) {
            strategies += 2 * counts.size();
            cappedAll += pilot.capped[r];
            rounds += pilot.refitRounds[r];
        }
        out.printf("%nThe resamples took %.1f s on the wall, %.1f s each: %.1f s refitting in %.2f rounds on average and "
                        + "%.1f s scoring. %d of %d strategies refitted were capped. Over every resample: %d walks at %.3f ms "
                        + "CPU each; %d split asks, %d the chart's, %d valued before (kept from round to round and "
                        + "resample to resample), %d valued afresh at %.1f ms CPU each; %.2f distinct post-split plays per "
                        + "(state, pair deal, round), at most %d. Peak heap over the resamples %.0f MB.%n",
                resampleWall, resampleWall / resamples, mean(pilot.refitWall), rounds / resamples, mean(pilot.scoreWall),
                cappedAll, strategies, rt.walks, 1000 * rt.walkCpu / Math.max(1, rt.walks), rt.splitAsks, rt.splitChart,
                rt.splitKept, rt.splitValued, 1000 * rt.splitCpu / Math.max(1, rt.splitValued),
                rt.pairDeals == 0 ? 0 : (double) rt.distinctPlays / rt.pairDeals, rt.mostPlays, resamplePeak / 1e6);
        out.printf("Rounds a strategy was active (passes, clean-ups and the last), over the estimate's %d and every "
                + "resample's: %s.%n", 2 * counts.size(), histogram(pilot.strategyRounds));

        Projection.print(out, data, pilot, estimateHeld);
        out.printf("%nFinished %s, load average %.2f.%n", java.time.LocalDateTime.now(),
                FullIndexTimingReport.loadAverage());
    }

    static double mean(double[] x) {
        double s = 0;
        for (double v : x) {
            s += v;
        }
        return s / x.length;
    }

    /** How many strategies were active for each number of rounds, as "rounds: strategies". */
    static String histogram(List<Integer> rounds) {
        int[] h = new int[FullIndexFit.MAX_PASSES + FullIndexFit.CLEAN_UPS + 2];
        for (int r : rounds) {
            h[r]++;
        }
        StringBuilder b = new StringBuilder();
        for (int r = 1; r < h.length; r++) {
            if (h[r] > 0) {
                b.append(b.length() == 0 ? "" : ", ").append(r).append(": ").append(h[r]);
            }
        }
        return b.toString();
    }

    /**
     * The states of shoes 0 to shoes - 1 of a state file, read without reading the rest of it,
     * after the checks ComparisonReport makes: the file was dealt under this code's rules and
     * chart, to the widest cut scored, and holds every one of those shoes.
     */
    static ScoringStates load(Path data, int shoes) throws IOException {
        StateStore.Header header = StateStore.header(data);
        RoundRules rules = StateRun.montreal();
        String made = StateRun.describe(rules, BasicStrategy.chart());
        if (!header.rules.equals(made)) {
            throw new IllegalStateException(data + " was made under \"" + header.rules + "\"; this code plays \"" + made + "\"");
        }
        int widest = SystemScorer.CUTS[SystemScorer.CUTS.length - 1];
        if (header.cut < widest) {
            throw new IllegalStateException(data + " was dealt to cut " + header.cut + ", short of the " + widest + " scored");
        }
        if (StateStore.shoesDone(data) < shoes) {
            throw new IllegalArgumentException(data + " holds " + StateStore.shoesDone(data) + " shoes, not " + shoes);
        }
        int records = StateStore.records(data);
        List<StateStore.Record> out = new ArrayList<>();
        for (int from = 0; from < records; from += 256) {
            for (StateStore.Record r : StateStore.read(data, from, Math.min(256, records - from))) {
                if (r.shoe >= shoes) {
                    return new ScoringStates(out, shoes, rules);
                }
                out.add(r);
            }
        }
        return new ScoringStates(out, shoes, rules);
    }

    static CountKeys.Count named(String system) {
        for (CountKeys.Count c : CountKeys.catalog()) {
            if (c.systems.contains(system)) {
                return c;
            }
        }
        throw new IllegalArgumentException("no catalog system named \"" + system + "\"");
    }

    static long usedAfterGc() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    // ------------------------------------------------------------------ the full run

    /**
     * The full run projected from the pilot's figures (FULL_INDEX_PLAYS.md section 6), with what
     * each figure assumes said beside it.
     *
     * Walks. A strategy is active for some rounds: its fitting passes and clean-ups, which walk
     * its training states, and the last, which walks every state of its penetration of weight not
     * zero. A fold's training states are half of the penetration's, a half fit's a quarter, and
     * in the last round a fold's strategy walks every state once and a half fit walks every state
     * once too (its half, the other half of its fold for the check, and the test fold). So a count
     * walks each state of a penetration 1.5 times a round while fitting, over its four strategies
     * there, and 4 times in the last round. The rounds a strategy is active are taken two ways:
     * the mean of the pilot's strategies, and the cap, every strategy running its six passes, two
     * clean-ups and the last. The full run's groups are finer than the pilot's, which can take more
     * passes, so the truth may lie between.
     *
     * Trees. A batch builds every state's trees afresh each round it runs, for as many rounds as
     * its slowest strategy; the rounds of the slowest of k strategies are worked out from the
     * pilot's distribution as if they were independent draws.
     *
     * Costs. The CPU of a tree, a walk and a split valued are the estimate's (built trees, splits
     * kept for a state's visit only), as are the splits valued a walk. A walk's CPU includes
     * setting up the count's tree, which the pilot does once a walk in a fitting round and the full
     * run shares among a count's strategies at a state, and a batch of more counts shares more
     * post-split plays at a state, so both overstate the full run's cost a little. Wall time is CPU
     * over the threads the estimate kept busy, on this machine at the pilot's load.
     *
     * Memory. Every strategy holds a Hit and a Split value for every part of its penetration
     * (SystemScorer.PlayValues), and every count a Parts and InsuranceParts per penetration,
     * shared by its strategies there; their sizes are worked out from the pilot's own arrays for
     * every count of the catalog, scaled by the file's states over the pilot's. Only pair deals
     * have a Split value, so the same is also worked out with Split held for their parts alone,
     * with an int a part to find them. ScoringStates holds every state's stored values, and the
     * scoring of a count after its batch makes its Parts again with an index by key, one
     * penetration at a time. Batches take the counts in order, as many as fit in FILL of the heap
     * beside all that, the rest of the heap being the collector's room. A batch holds either every
     * penetration of its counts, as FullIndexFit.scoreAll batches now, or one penetration of them,
     * which would need scoreAll changed and builds each penetration's trees in rounds of their own.
     */
    static final class Projection {

        static final double FILL = 0.8;
        static final double HEAP = 12e9;
        static final String[] WAYS = {"every part", "pair deals only"};

        static void print(PrintStream out, Path data, FullIndexPilot pilot, long estimateHeld) throws IOException {
            ScoringStates st = pilot.st;
            SystemScorer scorer = pilot.scorer;
            int fullStates = StateStore.records(data);
            int fullShoes = StateStore.shoesDone(data);
            double scale = (double) fullStates / st.size;
            List<CountKeys.Count> catalog = new ArrayList<>();
            catalog.add(CountKeys.nullCount());
            catalog.addAll(CountKeys.catalog());
            int fits = catalog.size();
            out.printf("%n## The full run, projected%n%n");
            out.printf("The file: %,d states of %,d shoes, %.2f times the pilot's states a shoe; %d counts fitted (the "
                    + "null count and %d catalog entries), each at 3 penetrations, each fold and each half fit: %d "
                    + "strategies a count.%n", fullStates, fullShoes, (double) fullStates / fullShoes * st.shoes / st.size,
                    fits, fits - 1, 3 * 4);

            double[] share = new double[SystemScorer.CUTS.length];
            double sumShare = 0;
            for (int p = 0; p < share.length; p++) {
                share[p] = (double) scorer.pen(p).states.length / st.size;
                sumShare += share[p];
            }
            out.printf("States in each penetration, as the pilot holds them: 70%% %.3f, 75%% %.3f, 80%% %.3f of all.%n",
                    share[0], share[1], share[2]);

            List<Integer> rounds = pilot.strategyRounds;
            int cap = FullIndexFit.MAX_PASSES + FullIndexFit.CLEAN_UPS + 1;
            double meanRounds = 0;
            int[] h = new int[cap + 1];
            for (int r : rounds) {
                meanRounds += r;
                h[r]++;
            }
            meanRounds /= rounds.size();
            double[] walks = new double[2];
            for (int b = 0; b < 2; b++) {
                double r = b == 0 ? meanRounds : cap;
                walks[b] = fits * sumShare * (1.5 * (r - 1) + 4) * fullStates;
            }
            out.printf("Rounds a strategy is active: %.2f on average over the pilot's %d strategies (%s), %d at the cap. "
                            + "Walks in all: %.3g at the pilot's mean, %.3g at the cap.%n", meanRounds, rounds.size(),
                    histogram(rounds), cap, walks[0], walks[1]);

            FullIndexFit.RoundStats et = total(pilot.estimateRounds);
            double tTree = et.treeCpu / et.states;
            double tWalk = et.walkCpu / et.walks;
            double tSplit = et.splitValued == 0 ? 0 : et.splitCpu / et.splitValued;
            double splitsAWalk = (double) et.splitValued / et.walks;
            double busy = (et.treeCpu + et.walkCpu + et.splitCpu) / et.wall;
            out.printf("Costs, from the estimate's rounds: trees %.4f s CPU a state a round; a walk %.3f ms CPU; %.3f "
                    + "splits valued a walk at %.1f ms CPU each; %.2f threads busy of %d.%n", tTree, 1000 * tWalk,
                    splitsAWalk, 1000 * tSplit, busy, pilot.threads);

            double statesBytes = statesBytes(st) * scale;
            int pens = SystemScorer.CUTS.length;
            // [count][pen][way]: what a count's four strategies at a penetration hold, Split for every part or pairs only.
            double[][][] bytes = new double[fits][pens][2];
            double scoring = 0;
            List<String> aware = new ArrayList<>();
            for (int c = 0; c < fits; c++) {
                CountKeys.Count count = catalog.get(c);
                if (count.kindAware) {
                    aware.add(count.name);
                }
                for (int p = 0; p < pens; p++) {
                    SystemScorer.Pen pp = scorer.pen(p);
                    SystemScorer.Parts parts = scorer.parts(count, pp);
                    SystemScorer.InsuranceParts ip = scorer.insuranceParts(parts, pp);
                    long n = parts.key.length;
                    long pairs = 0;
                    for (int j = 0; j < pp.states.length; j++) {
                        for (int i = 0; i < Deals.COUNT; i++) {
                            if (Deals.P1[i] == Deals.P2[i]) {
                                pairs += parts.to(j * Deals.COUNT + i) - parts.from(j * Deals.COUNT + i);
                            }
                        }
                    }
                    double partsBytes = 4.0 * n + (parts.start == null ? 0 : 4.0 * parts.start.length + 8.0 * n);
                    double insBytes = 4.0 * ip.start.length + 21.0 * ip.key.length;
                    double common = partsBytes + insBytes + 4 * (8.0 + 2) * pp.states.length;
                    bytes[c][p][0] = (common + 4 * 16.0 * n) * scale;
                    bytes[c][p][1] = (common + 4 * (8.0 * n + 12.0 * pairs)) * scale;
                    scoring = Math.max(scoring, (partsBytes + insBytes + 8.0 * n) * scale);
                }
            }
            double perThread = 1.5e6;
            double fixed = statesBytes + scoring + pilot.threads * perThread;
            out.printf("Memory: the states %.2f GB; scoring a count after its batch %.2f GB more at most; about %.1f MB a "
                    + "thread for the trees in hand; so %.2f GB beside the fits.%n", statesBytes / 1e9, scoring / 1e9,
                    perThread / 1e6, fixed / 1e9);
            for (int way = 0; way < 2; way++) {
                double[] mean = new double[pens + 1];
                double[] most = new double[pens + 1];
                String[] mostName = new String[pens + 1];
                for (int c = 0; c < fits; c++) {
                    double all = 0;
                    for (int p = 0; p <= pens; p++) {
                        double b = p < pens ? bytes[c][p][way] : all;
                        all += p < pens ? b : 0;
                        mean[p] += b / fits;
                        if (b > most[p]) {
                            most[p] = b;
                            mostName[p] = catalog.get(c).name;
                        }
                    }
                }
                out.printf("A count's fits, Split values held for %s: at every penetration %.2f GB on average and %.2f GB "
                                + "at most (%s); at 80%% alone %.2f GB and %.2f GB.%n", WAYS[way], mean[pens] / 1e9,
                        most[pens] / 1e9, mostName[pens], mean[pens - 1] / 1e9, most[pens - 1] / 1e9);
            }
            out.printf("Counts that tag a rank by colour or face, with more parts: %s.%n", String.join("; ", aware));
            out.printf("Check of the memory model at the pilot's size: it gives %.0f MB for the states and the "
                    + "estimate's fits held; the heap held %.0f MB after the estimate, all else the JVM holds "
                    + "included.%n", pilotModel(pilot) / 1e6, estimateHeld / 1e6);

            out.printf("%nBatches fill %.0f%% of a %.0f GB heap, the most this 16 GB machine can give a run beside the "
                    + "system and one other job. A batch holds every penetration of its counts, as FullIndexFit.scoreAll "
                    + "does now, or one penetration of its counts, a change to scoreAll that builds each penetration's "
                    + "trees on their own.%n%n", 100 * FILL, HEAP / 1e9);
            out.printf("| A batch holds | Split values held for | Least heap | Rounds a strategy | Batches | Most counts a "
                    + "batch | Rounds a batch | Trees built | CPU hours: trees | walks | splits | Wall hours at %.2f "
                    + "threads busy |%n", busy);
            out.println("|---|---|---|---|---|---|---|---|---|---|---|---|");
            double budget = FILL * HEAP - fixed;
            for (int perPen = 0; perPen < 2; perPen++) {
                for (int way = 0; way < 2; way++) {
                    // Items to batch, in order, each with its bytes, the states its trees are built for, and its strategies.
                    List<double[]> items = new ArrayList<>();
                    for (int p = perPen == 0 ? pens - 1 : 0; p < pens; p++) {
                        for (int c = 0; c < fits; c++) {
                            double b = 0;
                            for (int q = perPen == 0 ? 0 : p; q <= p; q++) {
                                b += bytes[c][q][way];
                            }
                            items.add(new double[]{b, share[p] * fullStates, perPen == 0 ? 12 : 4, p});
                        }
                    }
                    double least = 0;
                    for (double[] it : items) {
                        least = Math.max(least, (it[0] + fixed) / FILL);
                    }
                    List<double[]> batches = batches(items, budget);
                    for (int b = 0; b < 2; b++) {
                        String basis = b == 0 ? String.format("%.2f, the pilot's", meanRounds) : cap + ", the cap";
                        String what = perPen == 0 ? "every penetration" : "one penetration";
                        if (batches == null) {
                            out.printf("| %s | %s | %.1f GB | %s | does not fit | | | | | | | |%n", what, WAYS[way],
                                    least / 1e9, basis);
                            continue;
                        }
                        double trees = 0;
                        int biggest = 0;
                        double roundsAll = 0;
                        for (double[] batch : batches) {
                            double rb = b == 0 ? slowest(h, rounds.size(), (int) batch[2]) : cap;
                            roundsAll += rb;
                            trees += rb * batch[1];
                            biggest = Math.max(biggest, (int) batch[0]);
                        }
                        double treeHours = trees * tTree / 3600;
                        double walkHours = walks[b] * tWalk / 3600;
                        double splitHours = walks[b] * splitsAWalk * tSplit / 3600;
                        out.printf("| %s | %s | %.1f GB | %s | %d | %d | %.2f | %.3g | %.0f | %.0f | %.0f | %.0f |%n", what,
                                WAYS[way], least / 1e9, basis, batches.size(), biggest, roundsAll / batches.size(), trees,
                                treeHours, walkHours, splitHours, (treeHours + walkHours + splitHours) / busy);
                    }
                }
            }
        }

        /**
         * Items in order, each {bytes, states its trees are built for, strategies, penetration}, as
         * many to a batch as fit in budget bytes, a batch never mixing penetrations unless every item
         * holds them all: each batch as {items, states, strategies}. Null if one item does not fit alone.
         */
        static List<double[]> batches(List<double[]> items, double budget) {
            List<double[]> out = new ArrayList<>();
            double used = 0;
            double[] open = null;
            double pen = -1;
            for (double[] it : items) {
                if (it[0] > budget) {
                    return null;
                }
                if (open == null || used + it[0] > budget || it[3] != pen) {
                    open = new double[]{0, it[1], 0};
                    out.add(open);
                    used = 0;
                    pen = it[3];
                }
                used += it[0];
                open[0]++;
                open[2] += it[2];
            }
            return out;
        }

        /** What ScoringStates holds of each state: its stored values by move, chances, insurance and cards. */
        static double statesBytes(ScoringStates st) {
            double bytes = 0;
            for (int s = 0; s < st.size; s++) {
                bytes += 8.0 * (st.move[s].length + st.chance[s].length + st.insurance[s].length)
                        + 4.0 * st.dealt[s].length + 4 * 4 + 6 * 8 + 4 * 16;
            }
            return bytes;
        }

        /** The memory model at the pilot's size: its states and the estimate's strategies, each count's Parts held. */
        static double pilotModel(FullIndexPilot pilot) {
            double bytes = statesBytes(pilot.st);
            for (int c = 0; c < pilot.counts.size(); c++) {
                long n = pilot.parts[c].key.length;
                bytes += 2 * (16.0 * n + 10.0 * pilot.pen.states.length);
                bytes += 4.0 * n + (pilot.parts[c].start == null ? 0 : 4.0 * pilot.parts[c].start.length + 8.0 * n);
                bytes += 4.0 * pilot.ins[c].start.length + 21.0 * pilot.ins[c].key.length;
            }
            return bytes;
        }

        /** The expected most rounds among k strategies, each drawn from the histogram h of n. */
        static double slowest(int[] h, int n, int k) {
            double e = 0;
            double below = 0;
            for (int r = 1; r < h.length; r++) {
                double upTo = below + (double) h[r] / n;
                e += r * (Math.pow(upTo, k) - Math.pow(below, k));
                below = upTo;
            }
            return e;
        }
    }
}
