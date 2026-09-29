import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The report end to end on a state file of real shoes with made-up values, cheap to make:
 * the shoes are dealt by ShoeRun as a run deals them, and each valued state gets values
 * that follow the Hi-Lo true count instead of ExactRound's. Also the replay that checks a
 * file and gives its unvalued states, and the arithmetic of the sizing figures.
 */
public class ComparisonReportTest {

    private static final double EXACT = 1e-12;
    private static final RoundRules RULES = StateRun.montreal();
    private static final RoundPolicy CHART = BasicStrategy.chart();
    private static final long SEED = 77;
    private static final double Q0 = 0.05;
    private static final double U0 = 0.02;

    /**
     * A state file of shoes dealt from seed, under a header that says headerSeed: the chart's
     * first move is worth a, rising with the Hi-Lo true count, and every other move a less a
     * third of a unit.
     */
    static Path syntheticRun(Path dir, long seed, long headerSeed, int shoes) throws Exception {
        StateStore.Header h = new StateStore.Header(headerSeed, Q0, U0, StateRun.CUT_80, StateRun.describe(RULES, CHART));
        Path file = dir.resolve("synthetic-" + seed + "-" + headerSeed + ".states");
        assertEquals(0, StateStore.open(file, h));
        ShoeRun run = new ShoeRun(seed, Q0, U0, StateRun.CUT_80, RULES, CHART);
        int[] chart = ScoringStates.chartMoves(RULES);
        CountKeys.Count hiLo = CountKeys.of(CountingSystem.named("Hi-Lo"), "");
        for (int shoe = 0; shoe < shoes; shoe++) {
            List<StateStore.Record> records = new ArrayList<>();
            for (ShoeRun.State s : run.deal(shoe)) {
                if (s.valued) {
                    double a = -0.02 + 0.01 * hiLo.trueCount(s.dealt, s.depth);
                    records.add(SystemScorerTest.state(s.shoe, s.round, s.dealt, s.q,
                            (i, m) -> m == chart[i] ? a : a - 0.3));
                }
            }
            StateStore.appendShoe(file, shoe, records);
        }
        return file;
    }

    static ScoringStates prepare(Path file) throws Exception {
        return new ScoringStates(StateStore.readAll(file), StateStore.shoesDone(file), RULES);
    }

    @Test
    public void theReplayFindsEveryStateAndEveryValuedOneInTheFile(@TempDir Path dir) throws Exception {
        int shoes = 12;
        Path file = syntheticRun(dir, SEED, SEED, shoes);
        ScoringStates st = prepare(file);
        AllStates all = AllStates.replay(StateStore.header(file), shoes, RULES, CHART, st);
        ShoeRun run = new ShoeRun(SEED, Q0, U0, StateRun.CUT_80, RULES, CHART);
        List<ShoeRun.State> dealt = new ArrayList<>();
        for (int shoe = 0; shoe < shoes; shoe++) {
            dealt.addAll(run.deal(shoe));
        }
        assertEquals(dealt.size(), all.size);
        assertTrue(st.size > shoes);
        for (int s = 0; s < all.size; s++) {
            assertArrayEquals(dealt.get(s).dealt, all.dealt(s));
            assertEquals(dealt.get(s).depth, all.depth[s]);
        }
        for (int cut : SystemScorer.CUTS) {
            assertEquals(dealt.stream().filter(s -> s.depth < cut).count(), all.states(cut));
        }

        // The insurance correlation over every state, worked out again here.
        CountKeys.Count omega = CountKeys.of(CountingSystem.named("Omega II"), "");
        double[] x = new double[dealt.size()];
        double[] y = new double[dealt.size()];
        for (int s = 0; s < x.length; s++) {
            ShoeRun.State d = dealt.get(s);
            x[s] = omega.trueCount(d.dealt, d.depth);
            int tens = 0;
            for (int k = 0; k < ShoeRun.KINDS; k++) {
                tens += ShoeRun.rankOf(k) == 10 ? 2 * ShoeRun.DECKS - d.dealt[k] : 0;
            }
            y[s] = (double) tens / (ShoeRun.CARDS - d.depth);
        }
        assertEquals(pearson(x, y), all.insuranceCorrelation(omega), 1e-9);
        assertEquals(all.insuranceCorrelation(omega), all.insuranceCorrelation(omega.sideCounted()), 0);

        // The betting correlation over the valued states, each weighted 1/q.
        double[] tc = new double[st.size];
        for (int s = 0; s < st.size; s++) {
            tc[s] = omega.sideCounted().trueCount(st.dealt[s], st.depth[s]);
        }
        assertEquals(weightedPearson(tc, st.chart, st.weight),
                AllStates.bettingCorrelation(omega.sideCounted(), st), 1e-9);
    }

    /**
     * A file whose header names another seed than its shoes were dealt from is refused, and
     * so is one that holds the same states as a true file but one of them with another q.
     */
    @Test
    public void aFileThatDoesNotDealAgainIsRefused(@TempDir Path dir) throws Exception {
        int shoes = 6;
        Path file = syntheticRun(dir, SEED, SEED + 1, shoes);
        ScoringStates st = prepare(file);
        assertThrows(IllegalStateException.class,
                () -> AllStates.replay(StateStore.header(file), shoes, RULES, CHART, st));

        Path honest = syntheticRun(dir, SEED, SEED, shoes);
        StateStore.Header h = StateStore.header(honest);
        List<StateStore.Record> records = StateStore.readAll(honest);
        Path altered = dir.resolve("altered.states");
        StateStore.open(altered, h);
        for (int shoe = 0; shoe < shoes; shoe++) {
            List<StateStore.Record> own = new ArrayList<>();
            for (StateStore.Record r : records) {
                if (r.shoe == shoe) {
                    boolean first = r == records.get(0);
                    own.add(new StateStore.Record(r.shoe, r.round, r.depth, first ? r.q / 2 : r.q, r.dealt, r.values));
                }
            }
            StateStore.appendShoe(altered, shoe, own);
        }
        AllStates.replay(h, shoes, RULES, CHART, prepare(honest));
        ScoringStates changed = prepare(altered);
        assertThrows(IllegalStateException.class, () -> AllStates.replay(h, shoes, RULES, CHART, changed));
    }

    static double pearson(double[] x, double[] y) {
        double[] w = new double[x.length];
        Arrays.fill(w, 1);
        return weightedPearson(x, y, w);
    }

    /** The weighted correlation from sums of products, a second way from AllStates's. */
    static double weightedPearson(double[] x, double[] y, double[] w) {
        double sw = 0, sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        for (int i = 0; i < x.length; i++) {
            sw += w[i];
            sx += w[i] * x[i];
            sy += w[i] * y[i];
            sxx += w[i] * x[i] * x[i];
            syy += w[i] * y[i] * y[i];
            sxy += w[i] * x[i] * y[i];
        }
        double cov = sxy / sw - sx / sw * sy / sw;
        return cov / Math.sqrt((sxx / sw - sx / sw * sx / sw) * (syy / sw - sy / sw * sy / sw));
    }

    // -------------------------------------------------------------- the report

    /**
     * The whole report on a small synthetic file: it leaves the file as it was, prints
     * every section, puts the null count and Hi-Lo first, and its chart row is the mean
     * round value under the chart worked out from the records.
     */
    @Test
    public void theReportRunsEndToEndAndLeavesTheFileAlone(@TempDir Path dir) throws Exception {
        int shoes = 24;
        Path file = syntheticRun(dir, SEED, SEED, shoes);
        byte[] before = Files.readAllBytes(file);
        byte[] progressBefore = Files.readAllBytes(StateStore.progressFile(file));
        ComparisonReport.Options o = new ComparisonReport.Options();
        o.systems = Arrays.asList("Red Seven (Red 7)", "Omega II");
        o.resamples = 20;
        o.threads = 2;
        o.calibrate = 2;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ComparisonReport.run(file, o, new PrintStream(bytes, true, "UTF-8"));
        String report = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        assertArrayEquals(before, Files.readAllBytes(file));
        assertArrayEquals(progressBefore, Files.readAllBytes(StateStore.progressFile(file)));
        for (String section : new String[]{"## Reference rows", "## Headline", "## Same schedule at every spread",
                "## Own proportional ramp", "## Flat play, insurance and efficiencies",
                "## Empirical betting and insurance correlations", "## Decision resolution", "## Size of the run",
                "## Time", "## Per key, from the test folds", "## Calibration of the standard errors",
                "| Omega II = Canfield Master + ace side count |", "| Red Seven (Red 7), betting only |"}) {
            assertTrue(report.contains(section), section);
        }

        List<StateStore.Record> records = StateStore.readAll(file);
        int[] chart = ScoringStates.chartMoves(RULES);
        String printed = report.lines().filter(l -> l.startsWith("| Chart, no count, flat |")).findFirst().orElse("");
        String[] cells = printed.split("\\|");
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            double num = 0;
            double den = 0;
            for (StateStore.Record r : records) {
                if (r.depth < SystemScorer.CUTS[p]) {
                    num += RoundValue.of(r.ranksLeft(), r.values, 1.5, i -> chart[i], i -> false) / r.q;
                    den += 1 / r.q;
                }
            }
            assertTrue(cells[2 + p].trim().startsWith(ComparisonReport.pct(num / den) + " ["), printed);
        }

        List<CountKeys.Count> counts = ComparisonReport.select(CountKeys.catalog(), o.systems);
        assertEquals(4, counts.size());
        assertTrue(counts.get(0).systems.isEmpty());
        assertTrue(counts.get(1).systems.contains("Hi-Lo"));
        assertThrows(IllegalArgumentException.class,
                () -> ComparisonReport.select(CountKeys.catalog(), Arrays.asList("Hi-Lo II")));
    }

    /**
     * Differences are taken resample by resample on the shared vectors, so Hi-Lo scored twice
     * differs from itself by exactly zero in every resample; and a betting-only row is set
     * against Hi-Lo betting only, a row playing its own fit against Hi-Lo's own.
     */
    @Test
    public void differencesArePairedAndLikeWithLike() {
        java.util.Random rnd = new java.util.Random(12);
        int shoes = 40;
        List<StateStore.Record> records = SystemScorerTest.randomStates(rnd, shoes);
        ScoringStates st = new ScoringStates(new ArrayList<>(records), shoes, RULES);
        SystemScorer sc = new SystemScorer(st, new Bootstrap(shoes, 30, 4), 2);
        CountKeys.Count hiLo = CountKeys.of(CountingSystem.named("Hi-Lo"), "");
        SystemScorer.Result a = sc.score(hiLo, null);
        SystemScorer.Result b = sc.score(hiLo, null);
        SystemScorer.Result z = sc.score(CountKeys.of(CountingSystem.named("Zen Count"), ""), null);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            for (int m = 0; m < SystemScorer.METRICS; m++) {
                double[] d = ComparisonReport.difference(a.entries.get(0), b.entries.get(0), p, m, new double[30]);
                assertArrayEquals(new double[30], d, 0);
            }
            double[] d = ComparisonReport.difference(z.entries.get(0), a.entries.get(0), p, SystemScorer.same(8),
                    new double[30]);
            for (int r = 0; r < d.length; r++) {
                assertEquals(z.entries.get(0).boot[p][SystemScorer.same(8)][r]
                        - a.entries.get(0).boot[p][SystemScorer.same(8)][r], d[r], 0);
            }
        }
        assertSame(a.entries.get(1), ComparisonReport.baseline(a, z.entries.get(1)));
        assertSame(a.entries.get(0), ComparisonReport.baseline(a, z.entries.get(0)));
    }

    // --------------------------------------------------------------- the sizing

    /**
     * The per-shoe SE of a pooled ratio, worked out by hand: two shoes a fold, the residuals
     * a - R b centred within their fold, each fold's squares scaled by n / (n - 1).
     */
    @Test
    public void thePerShoeStandardErrorIsTheStratifiedRatioFormula() {
        double[] a = {1, 2, 3, 5};
        double[] b = {1, 1, 2, 2};
        double r = 11.0 / 6;
        double[] e = {1 - r, 2 - r, 3 - 2 * r, 5 - 2 * r};
        double m0 = (e[0] + e[2]) / 2;
        double m1 = (e[1] + e[3]) / 2;
        double var = 2 * (Math.pow(e[0] - m0, 2) + Math.pow(e[2] - m0, 2))
                + 2 * (Math.pow(e[1] - m1, 2) + Math.pow(e[3] - m1, 2));
        assertEquals(Math.sqrt(var) / 6, ComparisonReport.shoeSE(a, b), EXACT);
        // A shoe of the fold with no valued state still counts as one of its shoes.
        double[] a3 = {1, 2, 3, 5, 0};
        double[] b3 = {1, 1, 2, 2, 0};
        assertTrue(ComparisonReport.shoeSE(a3, b3) != ComparisonReport.shoeSE(a, b));
    }

    /**
     * The report's inputs for counts scored directly on states, without a state file: the
     * null count and Hi-Lo first, as the report scores them, then the others.
     */
    static ComparisonReport.Scored scored(ScoringStates st, SystemScorer scorer, String... others) {
        ComparisonReport.Scored sc = new ComparisonReport.Scored();
        sc.st = st;
        sc.scorer = scorer;
        sc.results = new ArrayList<>();
        sc.results.add(scorer.score(CountKeys.nullCount(), null));
        sc.results.add(scorer.score(CountKeys.of(CountingSystem.named("Hi-Lo"), ""), null));
        for (String name : others) {
            sc.results.add(scorer.score(CountKeys.of(CountingSystem.named(name), ""), null));
        }
        sc.nullCount = sc.results.get(0);
        sc.hiLo = sc.results.get(1);
        try {
            sc.scaling = ComparisonReport.scaling(st, sc.results, scorer.mPlay, 1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return sc;
    }

    /**
     * Section 8's third criterion covers every fitted decision, insurance as well as the first
     * move: a count's decision bound is its play bound and its insurance bound added, and the
     * valued states the criterion asks for come from that. With random cards out and small
     * groups, some groups sit near where insurance breaks even, so some insurance decisions
     * are close calls, and a criterion on the play alone would ask for less.
     */
    @Test
    public void theDecisionCriterionCountsInsuranceCloseCalls() {
        java.util.Random rnd = new java.util.Random(19);
        int shoes = 80;
        ScoringStates st = new ScoringStates(new ArrayList<>(SystemScorerTest.randomStates(rnd, shoes)), shoes, RULES);
        ComparisonReport.Scored sc = scored(st, new SystemScorer(st, new Bootstrap(shoes, 10, 3), 1), "Red Seven (Red 7)");
        ComparisonReport.Sizing z = ComparisonReport.size(sc);
        assertEquals(3 * SystemScorer.CUTS.length, z.bounds.size());
        double most = 0;
        boolean insured = false;
        for (ComparisonReport.BoundRow b : z.bounds) {
            SystemScorer.Resolution r = b.result.resolution[b.pen];
            assertEquals(r.bound + r.insuranceBound, b.total(), 0);
            assertEquals(ComparisonReport.statesForBound(r.bound + r.insuranceBound, b.exponent,
                    ComparisonReport.BOUND_TARGET, st.size), b.forBound, 0);
            most = Math.max(most, b.forBound);
            insured |= r.insuranceBound > 0;
        }
        assertTrue(insured, "no insurance close call to count");
        assertEquals(most, z.need[2], 0);
    }

    /**
     * The learning curve is judged on the change itself, paired on the same test states, and
     * against the decision target, not against the interval of the flat EV, which is mostly
     * shoe luck the two fits share. Here the half of each training fold prefers Double and
     * the rest, weighted ten times as much, Stand, so the half fit doubles and the full fit
     * stands: a change of several percent, far past the 0.005% target. Every move of a shoe
     * also carries the shoe's luck of +-0.3, which makes the flat EV's interval wider than
     * that change, so comparing with it passed the check. A play the half already fits, Stand
     * everywhere, holds.
     */
    @Test
    public void theLearningCurveIsJudgedOnItsPairedChange() {
        int shoes = 16;
        for (boolean halfLikesDouble : new boolean[]{true, false}) {
            ScoringStates st = new ScoringStates(new ArrayList<>(SystemScorerTest.luckyShoes(shoes, halfLikesDouble)),
                    shoes, RULES);
            SystemScorer sc = new SystemScorer(st, new Bootstrap(shoes, 200, 5), SystemScorer.M_PLAY);
            SystemScorer.Result res = sc.score(CountKeys.nullCount(), null);
            int p = SystemScorer.CUTS.length - 1;
            SystemScorer.Entry e = res.entries.get(0);
            Bootstrap.Interval flat = Bootstrap.interval(e.estimate[p][SystemScorer.FLAT], e.boot[p][SystemScorer.FLAT]);
            Bootstrap.Interval change = ComparisonReport.learningCurve(res, p);
            assertEquals(res.halfFitFlat[p] - flat.estimate, change.estimate, 0);
            if (halfLikesDouble) {
                assertTrue(Math.abs(change.estimate) > 100 * ComparisonReport.BOUND_TARGET);
                assertTrue(Math.abs(change.estimate) < flat.halfWidth(), "the flat EV's interval would have passed it");
                assertFalse(ComparisonReport.learningCurveHolds(change));
            } else {
                assertEquals(0, change.estimate, 1e-12);
                assertTrue(ComparisonReport.learningCurveHolds(change));
            }
        }
    }

    /**
     * The learning curve's verdict is reported in two places, the decision table's column
     * and the run-size criterion, and both must follow the paired change. Where the half fit
     * doubles and the full fit stands, the change is far past the target though inside the
     * flat EV's interval: both must say it fails. Where the two fits agree, both must say it
     * holds for that count, and the criterion must hold exactly when every row says so.
     */
    @Test
    public void theReportsLearningCurveVerdictsFollowThePairedChange() {
        int shoes = 16;
        for (boolean halfLikesDouble : new boolean[]{true, false}) {
            ScoringStates st = new ScoringStates(new ArrayList<>(SystemScorerTest.luckyShoes(shoes, halfLikesDouble)),
                    shoes, RULES);
            ComparisonReport.Scored sc = scored(st, new SystemScorer(st, new Bootstrap(shoes, 200, 5), SystemScorer.M_PLAY));
            ComparisonReport.Sizing z = ComparisonReport.size(sc);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            ComparisonReport.resolution(sc, new java.io.PrintStream(bytes, true));
            boolean allHold = true;
            int nullRows = 0;
            for (String line : bytes.toString().split("\n")) {
                if (!line.startsWith("| ") || line.startsWith("| Count") || !(line.endsWith("| holds |")
                        || line.endsWith("| fails |"))) {
                    continue;
                }
                boolean holds = line.endsWith("| holds |");
                allHold &= holds;
                if (line.startsWith("| " + sc.nullCount.count.name + " |")) {
                    nullRows++;
                    int p = nullRows - 1;
                    assertEquals(ComparisonReport.learningCurveHolds(ComparisonReport.learningCurve(sc.nullCount, p)),
                            holds, "null count at penetration " + p);
                    if (halfLikesDouble && p == SystemScorer.CUTS.length - 1) {
                        assertFalse(holds, "the table passed a change far past the target");
                    }
                }
            }
            assertEquals(SystemScorer.CUTS.length, nullRows);
            assertEquals(allHold, z.learningCurve);
            if (halfLikesDouble) {
                assertFalse(z.learningCurve, "the run-size criterion passed a change far past the target");
            }
        }
    }

    /**
     * How the decision bounds fall is put in words that match the projection: a fall, or no
     * measurable fall when the exponent is under MIN_EXPONENT, a rising bound included.
     */
    @Test
    public void theBoundsFallIsDescribedAsTheProjectionTreatsIt() {
        assertEquals("They fall at states^-0.55 here, fitted over the counts.", ComparisonReport.fallText(0.55));
        assertTrue(ComparisonReport.fallText(0.05).startsWith("They do not fall measurably here"));
        assertTrue(ComparisonReport.fallText(-0.22).contains("states^+0.22"));
        assertFalse(ComparisonReport.fallText(-0.22).contains("--"));
        assertTrue(ComparisonReport.fallText(Double.NaN).contains("cannot be fitted"));
    }

    /**
     * The decision bound's run size comes from how it was measured to fall, not from an
     * assumed one over the root of the states. A bound that halves each time the run
     * quadruples falls with exponent 1/2, and at that rate a bound twice the target needs four
     * times the states. One that falls with exponent 1/4 needs sixteen times. One that does
     * not fall is never brought under the target by running longer. A fall faster than one
     * over the data is not relied on, and a size where every decision settled, a bound of 0,
     * has no logarithm and is left out of the fit.
     */
    @Test
    public void theBoundIsProjectedAtTheRateItWasMeasuredToFall() {
        double t = ComparisonReport.BOUND_TARGET;
        double[] states = {250, 500, 1000};
        double[] half = {4 * t, 2 * Math.sqrt(2) * t, 2 * t};
        assertEquals(0.5, ComparisonReport.boundExponent(states, half), 1e-12);
        assertEquals(4000, ComparisonReport.statesForBound(2 * t, 0.5, t, 1000), 1e-9);
        double[] quarter = {2 * Math.sqrt(2) * t, 2 * Math.pow(2, 0.25) * t, 2 * t};
        assertEquals(0.25, ComparisonReport.boundExponent(states, quarter), 1e-12);
        assertEquals(16000, ComparisonReport.statesForBound(2 * t, 0.25, t, 1000), 1e-6);
        double[] flat = {2 * t, 2 * t, 2 * t};
        assertEquals(0, ComparisonReport.boundExponent(states, flat), 1e-12);
        assertEquals(Double.POSITIVE_INFINITY, ComparisonReport.statesForBound(2 * t, 0, t, 1000), 0);
        assertEquals(Double.POSITIVE_INFINITY, ComparisonReport.statesForBound(2 * t, Double.NaN, t, 1000), 0);
        // Already under the target: no more than is held, and less when it is falling.
        assertEquals(1000, ComparisonReport.statesForBound(t / 2, 0, t, 1000), 0);
        assertEquals(250, ComparisonReport.statesForBound(t / 2, 0.5, t, 1000), 1e-9);
        // Faster than one over the data is taken as one over the data.
        assertEquals(2000, ComparisonReport.statesForBound(2 * t, 2, t, 1000), 1e-9);
        assertEquals(0.5, ComparisonReport.boundExponent(states, new double[]{4 * t, 0, 2 * t}), 1e-12);
        assertTrue(Double.isNaN(ComparisonReport.boundExponent(states, new double[]{0, 0, 2 * t})));
        // One rate over several bounds, each at its own level: the rate they share.
        double[] higher = {40 * t, 20 * Math.sqrt(2) * t, 20 * t};
        assertEquals(0.5, ComparisonReport.boundExponent(states, half, higher), 1e-12);
        // Rates of 1/2 and 1/4 with equal spread in the logarithms pool to their mean.
        assertEquals(0.375, ComparisonReport.boundExponent(states, half, quarter), 1e-12);
        // A bound with one size left adds nothing.
        assertEquals(0.25, ComparisonReport.boundExponent(states, quarter, new double[]{0, 0, 3 * t}), 1e-12);
    }

    /**
     * The bound's fall is measured on the run's own parts: each quarter and each half of its
     * shoes (ScoringStates.subRun) fitted and resolved alone, as a run of its own, the parts
     * of a size averaged, and the whole run. Each count's exponent is fitted to those three,
     * and its states needed come from that exponent.
     */
    @Test
    public void theBoundsFallIsMeasuredOnHalvesAndQuartersOfTheRun() {
        java.util.Random rnd = new java.util.Random(19);
        int shoes = 80;
        ScoringStates st = new ScoringStates(new ArrayList<>(SystemScorerTest.randomStates(rnd, shoes)), shoes, RULES);
        ComparisonReport.Scored sc = scored(st, new SystemScorer(st, new Bootstrap(shoes, 4, 3), 1), "Red Seven (Red 7)");
        ComparisonReport.Scaling scaling = sc.scaling;
        ComparisonReport.Sizing z = ComparisonReport.size(sc);
        int[] parts = {4, 2};
        for (int size = 0; size < 2; size++) {
            double states = 0;
            List<SystemScorer> subs = new ArrayList<>();
            for (int k = 0; k < parts[size]; k++) {
                ScoringStates sub = st.subRun(k, parts[size]);
                states += (double) sub.size / parts[size];
                subs.add(new SystemScorer(sub, new Bootstrap(sub.shoes, 0, 1), 1));
            }
            assertEquals(states, scaling.states[size], 1e-12);
            for (int c = 0; c < sc.results.size(); c++) {
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    double mean = 0;
                    for (SystemScorer sub : subs) {
                        SystemScorer.Resolution r = sub.resolution(sc.results.get(c).count)[p];
                        mean += (r.bound + r.insuranceBound) / subs.size();
                    }
                    assertEquals(mean, scaling.bound[c][p][size], 1e-15);
                }
            }
        }
        assertEquals(st.size, scaling.states[2], 0);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            // One rate at each penetration, over Hi-Lo and Red Seven; the null count falls faster and is left out.
            assertEquals(ComparisonReport.boundExponent(scaling.states, scaling.bound[1][p], scaling.bound[2][p]),
                    z.exponent[p], 0);
        }
        for (ComparisonReport.BoundRow b : z.bounds) {
            SystemScorer.Resolution r = b.result.resolution[b.pen];
            double[] bySize = scaling.bound[sc.results.indexOf(b.result)][b.pen];
            assertEquals(r.bound + r.insuranceBound, bySize[2], 0);
            assertEquals(z.exponent[b.pen], b.exponent, 0);
            assertEquals(ComparisonReport.statesForBound(b.total(), b.exponent, ComparisonReport.BOUND_TARGET, st.size),
                    b.forBound, 0);
        }
    }

    /**
     * The report's inputs with each count's side-count variant scored beside it, where it has
     * one, and the reference rows.
     */
    static ComparisonReport.Scored scoredWithVariants(ScoringStates st, SystemScorer scorer, String... others) {
        ComparisonReport.Scored sc = new ComparisonReport.Scored();
        sc.st = st;
        sc.scorer = scorer;
        sc.results = new ArrayList<>();
        sc.results.add(scorer.score(CountKeys.nullCount(), null));
        sc.results.add(scorer.score(CountKeys.of(CountingSystem.named("Hi-Lo"), ""), null));
        for (String name : others) {
            CountKeys.Count c = CountKeys.of(CountingSystem.named(name), "");
            sc.results.add(scorer.score(c, CountKeys.sideCountVariant(c)));
        }
        sc.nullCount = sc.results.get(0);
        sc.hiLo = sc.results.get(1);
        sc.references = scorer.references();
        return sc;
    }

    /**
     * Section 6 takes the max-|t| intervals over the systems compared at once. So one
     * multiplier covers the systems and variants playing their own fits, another the
     * betting-only rows, and neither takes in the null count, a baseline, or Hi-Lo, whose
     * difference from itself is nothing. Each c is Bootstrap.maxT over its family's
     * differences, taken resample by resample, and here the two differ from one c over every
     * row, which the report used before. The headline prints each row's simultaneous interval
     * as its difference +- its family's c times the difference's SE, and none for the null count.
     */
    @Test
    public void simultaneousIntervalsAreTakenOverTheSystemsComparedAtOnce() {
        java.util.Random rnd = new java.util.Random(21);
        int shoes = 60;
        ScoringStates st = new ScoringStates(new ArrayList<>(SystemScorerTest.randomStates(rnd, shoes)), shoes, RULES);
        ComparisonReport.Scored sc = scoredWithVariants(st, new SystemScorer(st, new Bootstrap(shoes, 40, 7), 1),
                "Red Seven (Red 7)", "Omega II", "Zen Count");
        List<ComparisonReport.Row> rows = ComparisonReport.rows(sc);
        int m = SystemScorer.same(SystemScorer.SPREADS);
        int[] members = new int[2];
        for (ComparisonReport.Row row : rows) {
            boolean own = !row.entry.bettingOnly;
            boolean isNull = sc.nullCount.entries.contains(row.entry);
            boolean isHiLo = sc.hiLo.entries.contains(row.entry);
            int expected = isNull || isHiLo ? -1 : own ? ComparisonReport.OWN_FIT : ComparisonReport.BETTING_ONLY;
            assertEquals(expected, row.family(), row.entry.name);
            if (expected >= 0) {
                members[expected]++;
            }
        }
        // Red Seven and Zen playing their own fits, Omega II and its variant; and the same four betting only.
        assertArrayEquals(new int[]{4, 4}, members);

        String[] printed;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ComparisonReport.headline(sc, rows, new PrintStream(bytes, true, "UTF-8"));
            printed = new String(bytes.toByteArray(), StandardCharsets.UTF_8).split("\n");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            List<List<Double>> ests = Arrays.asList(new ArrayList<>(), new ArrayList<>());
            List<List<double[]>> boots = Arrays.asList(new ArrayList<>(), new ArrayList<>());
            List<Double> allEsts = new ArrayList<>();
            List<double[]> allBoots = new ArrayList<>();
            for (ComparisonReport.Row row : rows) {
                if (row.entry == row.baseline) {
                    continue;
                }
                double[] d = new double[40];
                for (int r = 0; r < d.length; r++) {
                    d[r] = row.entry.boot[p][m][r] - row.baseline.boot[p][m][r];
                }
                double e = row.entry.estimate[p][m] - row.baseline.estimate[p][m];
                allEsts.add(e);
                allBoots.add(d);
                if (row.family() >= 0) {
                    ests.get(row.family()).add(e);
                    boots.get(row.family()).add(d);
                }
            }
            double[] c = new double[2];
            for (int f = 0; f < 2; f++) {
                c[f] = Bootstrap.maxT(ests.get(f).stream().mapToDouble(Double::doubleValue).toArray(),
                        boots.get(f).toArray(new double[0][]));
            }
            double everyRow = Bootstrap.maxT(allEsts.stream().mapToDouble(Double::doubleValue).toArray(),
                    allBoots.toArray(new double[0][]));
            double[] est = new double[rows.size()];
            double[][] diffs = new double[rows.size()][];
            double[] got = ComparisonReport.maxT(rows, p, m, est, diffs);
            assertArrayEquals(c, got, 0);
            assertTrue(Math.abs(c[0] - everyRow) > 0.01 || Math.abs(c[1] - everyRow) > 0.01,
                    "one c over every row would give the same intervals here");

            String header = String.format("### %s penetration (average bet %.3f units; c = %.2f playing their own fits, "
                    + "%.2f betting only)", SystemScorer.PENETRATIONS[p],
                    sc.hiLo.entries.get(0).estimate[p][SystemScorer.sameBet(SystemScorer.SPREADS)], c[0], c[1]);
            int at = Arrays.asList(printed).indexOf(header);
            assertTrue(at >= 0, header);
            for (int k = 0; k < rows.size(); k++) {
                ComparisonReport.Row row = rows.get(k);
                String line = printed[at + 4 + k];
                assertTrue(line.startsWith("| " + row.entry.name + " |"), line);
                String[] cells = line.split("\\|");
                String simultaneous = cells[cells.length - 1].trim();
                if (row.entry == row.baseline || row.family() < 0) {
                    assertEquals("", simultaneous, line);
                    continue;
                }
                double se = Bootstrap.interval(est[k], diffs[k]).se;
                double cf = c[row.family()];
                assertEquals("[" + ComparisonReport.pct(est[k] - cf * se) + ", " + ComparisonReport.pct(est[k] + cf * se)
                        + "]", simultaneous, line);
            }
        }
    }

    /**
     * PE is measured flat and without insurance, on each count's fitted first moves (PLAY),
     * and IE on what its fitted insurance adds (INSURANCE), each from the null count's toward
     * the perfect row's, resample by resample. With random cards the counts' insurance is not
     * the null count's, so measuring PE on the flat EV with insurance would give another number.
     */
    @Test
    public void playAndInsuranceEfficienciesAreTakenOnTheirOwnFigures() {
        java.util.Random rnd = new java.util.Random(33);
        int shoes = 60;
        ScoringStates st = new ScoringStates(new ArrayList<>(SystemScorerTest.randomStates(rnd, shoes)), shoes, RULES);
        ComparisonReport.Scored sc = scoredWithVariants(st, new SystemScorer(st, new Bootstrap(shoes, 20, 7), 1),
                "Red Seven (Red 7)", "Omega II");
        SystemScorer.Entry none = sc.nullCount.entries.get(0);
        SystemScorer.Entry perfect = sc.references.get(1);
        double flatDiffers = 0;
        for (SystemScorer.Result r : sc.results.subList(1, sc.results.size())) {
            for (SystemScorer.Entry e : r.entries) {
                if (e.bettingOnly) {
                    continue;
                }
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    Bootstrap.Interval[] eff = ComparisonReport.efficiencies(e, none, perfect, p);
                    int[] metric = {SystemScorer.PLAY, SystemScorer.INSURANCE};
                    for (int k = 0; k < 2; k++) {
                        int mm = metric[k];
                        double expected = (e.estimate[p][mm] - none.estimate[p][mm])
                                / (perfect.estimate[p][mm] - none.estimate[p][mm]);
                        assertEquals(expected, eff[k].estimate, 0, e.name);
                        double[] boot = new double[20];
                        for (int x = 0; x < boot.length; x++) {
                            boot[x] = (e.boot[p][mm][x] - none.boot[p][mm][x])
                                    / (perfect.boot[p][mm][x] - none.boot[p][mm][x]);
                        }
                        Bootstrap.Interval iv = Bootstrap.interval(expected, boot);
                        assertEquals(iv.se, eff[k].se, 0);
                        assertEquals(iv.lo, eff[k].lo, 0);
                        assertEquals(iv.hi, eff[k].hi, 0);
                    }
                    int f = SystemScorer.FLAT;
                    double flat = (e.estimate[p][f] - none.estimate[p][f]) / (perfect.estimate[p][f] - none.estimate[p][f]);
                    flatDiffers = Math.max(flatDiffers, Math.abs(flat - eff[0].estimate));
                }
            }
        }
        assertTrue(flatDiffers > 1e-4, "PE on the flat EV with insurance differs by at most " + flatDiffers);
    }

    /**
     * Section 8 sizes the first two criteria by the bootstrap's SE, which refits everything,
     * and none of the three by the null count, a baseline rather than a system. Here the null
     * count's resamples are spread a hundred times wider and its decision bound set far over
     * the target, so it would set every criterion if it were let; and the per-shoe SE would
     * ask for other numbers. Every row's figures must come from the bootstrap, each criterion
     * must be the largest over the systems, and when every system's decision bound is under
     * the target on the file the criterion is met there, whatever the null count's.
     */
    @Test
    public void theRunIsSizedByTheSystemsBootstrapSesAndDecisionBounds() {
        java.util.Random rnd = new java.util.Random(19);
        int shoes = 80;
        ScoringStates st = new ScoringStates(new ArrayList<>(SystemScorerTest.randomStates(rnd, shoes)), shoes, RULES);
        ComparisonReport.Scored sc = scored(st, new SystemScorer(st, new Bootstrap(shoes, 10, 3), 1), "Red Seven (Red 7)",
                "Omega II");
        int m = SystemScorer.same(SystemScorer.SPREADS);
        SystemScorer.Entry none = sc.nullCount.entries.get(0);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            for (int r = 0; r < none.boot[p][m].length; r++) {
                none.boot[p][m][r] = none.estimate[p][m] + 100 * (none.boot[p][m][r] - none.estimate[p][m]);
            }
            sc.nullCount.resolution[p].bound = 1;
        }
        ComparisonReport.Sizing z = ComparisonReport.size(sc);
        SystemScorer.Entry hiLo = sc.hiLo.entries.get(0);
        double[] most = new double[3];
        double mostByShoe = 0;
        double[] nullNeeds = new double[3];
        for (ComparisonReport.EvRow row : z.ev) {
            SystemScorer.Entry e = row.entry;
            int p = row.pen;
            assertEquals(Bootstrap.interval(e.estimate[p][m], e.boot[p][m]).se, row.seBoot, 0, e.name);
            assertEquals(ComparisonReport.shoeSE(e.shoeSums[p], sc.scorer.pen(p).shoeWeight), row.seShoe, 0);
            assertEquals(ComparisonReport.statesFor(row.seBoot, ComparisonReport.EV_TARGET, st.size), row.forEv, 0);
            if (e != hiLo) {
                double[] d = new double[e.boot[p][m].length];
                for (int r = 0; r < d.length; r++) {
                    d[r] = e.boot[p][m][r] - hiLo.boot[p][m][r];
                }
                double diffSe = Bootstrap.interval(e.estimate[p][m] - hiLo.estimate[p][m], d).se;
                assertEquals(diffSe, row.diffSeBoot, 0, e.name);
                assertEquals(ComparisonReport.statesFor(diffSe, ComparisonReport.EV_TARGET, st.size), row.forDiff, 0);
            }
            if (row.result == sc.nullCount) {
                nullNeeds[0] = Math.max(nullNeeds[0], row.forEv);
                nullNeeds[1] = Math.max(nullNeeds[1], row.forDiff);
            } else {
                most[0] = Math.max(most[0], row.forEv);
                if (e != hiLo) {
                    most[1] = Math.max(most[1], row.forDiff);
                }
                mostByShoe = Math.max(mostByShoe, ComparisonReport.statesFor(row.seShoe, ComparisonReport.EV_TARGET,
                        st.size));
            }
        }
        for (ComparisonReport.BoundRow b : z.bounds) {
            if (b.result == sc.nullCount) {
                nullNeeds[2] = Math.max(nullNeeds[2], b.forBound);
            } else {
                most[2] = Math.max(most[2], b.forBound);
            }
        }
        for (int k = 0; k < 3; k++) {
            // A bound not measured to fall needs states without end; the null count's then ties and comes first.
            assertTrue(nullNeeds[k] > most[k] || Double.isInfinite(nullNeeds[k]),
                    "criterion " + k + ": the null count would not have set it");
            assertEquals(most[k], z.need[k], 0, "criterion " + k);
            assertFalse(z.who[k].startsWith(sc.nullCount.count.name), z.who[k]);
        }
        assertTrue(Math.abs(mostByShoe / most[0] - 1) > 0.05, "the per-shoe SE asks for the same here");

        // Every system's bound under the target on the file: met there, though the null count's is not.
        for (SystemScorer.Result r : sc.results) {
            if (r != sc.nullCount) {
                for (SystemScorer.Resolution res : r.resolution) {
                    res.bound = ComparisonReport.BOUND_TARGET / 4;
                    res.insuranceBound = ComparisonReport.BOUND_TARGET / 4;
                }
            }
        }
        z = ComparisonReport.size(sc);
        assertTrue(z.boundMet);
        assertEquals(ComparisonReport.MET, z.who[2]);
        assertTrue(z.need[2] <= st.size);
        sc.hiLo.resolution[1].insuranceBound = ComparisonReport.BOUND_TARGET;
        z = ComparisonReport.size(sc);
        assertFalse(z.boundMet);
        assertEquals(sc.hiLo.count.name + " at " + SystemScorer.PENETRATIONS[1], z.who[2]);
    }

    /**
     * The learning curve holds only when the whole 95% interval of its change lies inside
     * +-BOUND_TARGET: a change whose estimate is inside but whose interval reaches past either
     * end has not been shown small enough.
     */
    @Test
    public void theLearningCurveHoldsOnlyWhenItsWholeIntervalIsInsideTheTarget() {
        double t = ComparisonReport.BOUND_TARGET;
        assertTrue(ComparisonReport.learningCurveHolds(new Bootstrap.Interval(0.1 * t, 0.2 * t, 0, -0.9 * t, 0.9 * t,
                false)));
        assertFalse(ComparisonReport.learningCurveHolds(new Bootstrap.Interval(-0.7 * t, 0.3 * t, 0, -1.6 * t,
                -0.1 * t, false)));
        assertFalse(ComparisonReport.learningCurveHolds(new Bootstrap.Interval(0.5 * t, 0.3 * t, 0, -0.2 * t, 1.1 * t,
                false)));
        assertFalse(ComparisonReport.learningCurveHolds(new Bootstrap.Interval(2 * t, 0.1 * t, 0, 1.8 * t, 2.2 * t,
                false)));
    }

    /**
     * The calibration sets each row's SEs beside the spread of its own estimates across
     * sub-runs scored alone: the spread of the 1-8 same-schedule EV for the EV, and the
     * spread of the difference from Hi-Lo, taken sub-run by sub-run, for the difference.
     * Everything is worked out here again from the sub-runs' results. The two spreads differ
     * here, as they do whenever a row moves with Hi-Lo from one sub-run to the next.
     */
    @Test
    public void theCalibrationSetsEachSeBesideTheSpreadOfItsOwnEstimates() throws Exception {
        java.util.Random rnd = new java.util.Random(47);
        int shoes = 90;
        int parts = 3;
        ScoringStates st = new ScoringStates(new ArrayList<>(SystemScorerTest.randomStates(rnd, shoes)), shoes, RULES);
        List<CountKeys.Count> counts = Arrays.asList(CountKeys.nullCount(),
                CountKeys.of(CountingSystem.named("Hi-Lo"), ""), CountKeys.of(CountingSystem.named("Red Seven (Red 7)"), ""));
        List<ScoringStates> subs = new ArrayList<>();
        List<List<SystemScorer.Result>> runs = new ArrayList<>();
        for (int k = 0; k < parts; k++) {
            ScoringStates sub = st.subRun(k, parts);
            subs.add(sub);
            runs.add(ComparisonReport.scoreAll(new SystemScorer(sub, new Bootstrap(sub.shoes, 20, 3), 2), counts, 1));
        }
        List<ComparisonReport.CalibrationRow> rows = ComparisonReport.calibrationRows(subs, runs);
        String[] names = {"Null count (no count)", "Hi-Lo", "Hi-Lo, betting only", "Red Seven (Red 7)",
                "Red Seven (Red 7), betting only"};
        assertEquals(names.length, rows.size());
        int m = SystemScorer.same(SystemScorer.SPREADS);
        int p = SystemScorer.CUTS.length - 1;
        boolean spreadsDiffer = false;
        for (int row = 0; row < names.length; row++) {
            ComparisonReport.CalibrationRow r = rows.get(row);
            assertEquals(names[row], r.name);
            int count = row == 0 ? 0 : (row + 1) / 2;
            int e = row == 0 ? 0 : (row + 1) % 2;
            assertEquals(count == 1, r.isHiLo);
            double[] ev = new double[parts];
            double[] d = new double[parts];
            double evBoot = 0;
            double evShoe = 0;
            double dBoot = 0;
            double dShoe = 0;
            for (int k = 0; k < parts; k++) {
                SystemScorer.Entry x = runs.get(k).get(count).entries.get(e);
                SystemScorer.Entry h = runs.get(k).get(1).entries.get(e);
                ev[k] = x.estimate[p][m];
                d[k] = x.estimate[p][m] - h.estimate[p][m];
                double[] db = new double[x.boot[p][m].length];
                double[] ds = new double[x.shoeSums[p].length];
                double[] w = new double[ds.length];
                for (int r2 = 0; r2 < db.length; r2++) {
                    db[r2] = x.boot[p][m][r2] - h.boot[p][m][r2];
                }
                ScoringStates sub = subs.get(k);
                for (int j = 0; j < sub.size; j++) {
                    w[sub.shoe[j]] += sub.depth[j] < SystemScorer.CUTS[p] ? sub.weight[j] : 0;
                }
                for (int i = 0; i < ds.length; i++) {
                    ds[i] = x.shoeSums[p][i] - h.shoeSums[p][i];
                }
                evBoot += Bootstrap.interval(ev[k], x.boot[p][m]).se / parts;
                evShoe += ComparisonReport.shoeSE(x.shoeSums[p], w) / parts;
                dBoot += Bootstrap.interval(d[k], db).se / parts;
                dShoe += ComparisonReport.shoeSE(ds, w) / parts;
            }
            assertEquals(sampleSd(ev), r.evSpread, EXACT, names[row]);
            assertEquals(evBoot, r.evBoot, EXACT, names[row]);
            assertEquals(evShoe, r.evShoe, EXACT, names[row]);
            if (r.isHiLo) {
                assertTrue(Double.isNaN(r.diffSpread) && Double.isNaN(r.diffBoot) && Double.isNaN(r.diffShoe));
                continue;
            }
            assertEquals(sampleSd(d), r.diffSpread, EXACT, names[row]);
            assertEquals(dBoot, r.diffBoot, EXACT, names[row]);
            assertEquals(dShoe, r.diffShoe, EXACT, names[row]);
            spreadsDiffer |= Math.abs(r.diffSpread - r.evSpread) > 1e-4;
        }
        assertTrue(spreadsDiffer);
    }

    static double sampleSd(double[] x) {
        double mean = 0;
        for (double v : x) {
            mean += v / x.length;
        }
        double ss = 0;
        for (double v : x) {
            ss += (v - mean) * (v - mean);
        }
        return Math.sqrt(ss / (x.length - 1));
    }

    @Test
    public void statesNeededScaleAsTheSquareOfTheSE() {
        double se = 0.001;
        int states = 1000;
        double need = ComparisonReport.statesFor(se, ComparisonReport.EV_TARGET, states);
        assertEquals(states * Math.pow(ComparisonReport.Z95 * se / ComparisonReport.EV_TARGET, 2), need, 1e-6);
        // At that many states the SE is 1/sqrt(need/states) as large, and the 95% half-width is the target.
        assertEquals(ComparisonReport.EV_TARGET, ComparisonReport.Z95 * se / Math.sqrt(need / states), 1e-15);
        double[] out = new double[2];
        assertEquals(0.25, ComparisonReport.efficiency(1, 0, 4, new double[]{1, 2}, new double[]{0, 0},
                new double[]{4, 4}, out), EXACT);
        assertArrayEquals(new double[]{0.25, 0.5}, out, EXACT);
    }
}
