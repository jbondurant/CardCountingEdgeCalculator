import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * Writes the export bundle the independent simulator reads (PLAN_COMPUTE.md 1.2), in exactly the
 * format SHOE_SPEC.md Part B fixes, and then reads it back and scores it to show that the files
 * hold the strategies the report scored: the round trip.
 *
 * Why the strategies are fitted again rather than read from a results file: a Result keeps only
 * the play groups of each fold, not the moves ranked, the insurance fit or each fold's bets, and
 * its per-key table pools the two folds. So each exported count is refitted here through the
 * same package-private path the estimate takes (SystemScorer.foldPlay, insuranceSums and
 * insuranceValues, bets with its per-key detail), which is SystemScorer.scoreOnce step for step,
 * without the resamples: minutes a count on the main file.
 *
 * Why the round trip: a simulator can check its own dealing, play and payoffs, but not that the
 * files it was handed hold the strategy the report scored. So the bundle is parsed back with
 * ExportBundle, a reader written from Part B's words that never calls this writer, every state's
 * round value is worked out again from what the strategy files say (their groups, first moves,
 * insurance and bets, with keys from their own tags by B2.2) on the engine's stored values, and
 * the figures are pooled over the two folds. They must equal the report's Entry.estimate and
 * Entry.shoeSums within 1e-12 relative, the project's standard for a figure computed again in
 * another order. states.bin and exact.tsv must agree with the strategy files the same way. The
 * bundle is written into DIR.partial and renamed to DIR only once the round trip has passed, so a
 * directory without that suffix is a bundle that passed.
 *
 * Full-play rows come from the replay of PLAN_COMPUTE.md 6.1, which holds each fold's strategy
 * after its last round with the Hit and Split values it is scored with (ReplayExport, its
 * --export hook): the play is fitted again from those values as SystemScorer.score fits it
 * (Refit's full-play constructor), each item's rows are checked on the values while they are
 * held (checkFull), and the bundle is written from the replay's records, its round trip holding
 * each full-play section to the one checked. Given the strategies themselves (Wanted with a full
 * play), export() round-trips every state on the values. A full-play entry given without its
 * strategies is refused rather than exported with first-decision play under its name. Where a
 * fold's refit ranks a deal otherwise than the play its last round walked, whose ranking its
 * splits were valued under, the file gives split hands that ranking in a split line (SHOE_SPEC.md
 * B2.1, B2.4), and the round trip holds both rankings to the engine's.
 *
 * Usage, from the repository's root (one thread; about the memory ComparisonReport needs to hold
 * the state file):
 *
 *   java -Xmx8g -cp build/classes:lib/mongo-java-driver-3.12.14.jar StrategyExport STATES
 *       --published RESULTS.ser --out runs/sim/export-SHA --row "NAME@70,75,80" [--row ...]
 *       [--chart-from SAVED.ser@70,75,80] [--plans same-1,...] [--sample-seed N]
 *
 * --chart-from names a CeilingReport save (step 7's report-ceiling .ser) whose reference rows
 * were scored on the same state file: the chart's row is exported at the penetrations given and
 * held to its Chart, no count, flat entry.
 */
final class StrategyExport {

    /** Every comparison of the round trip: the project's standard for a figure computed again in another order. */
    static final double RELATIVE = 1e-12;
    /** B4's sample: its seed, the states drawn, the deals drawn a state, and the first records that get every deal. */
    static final long SAMPLE_SEED = 20261009L;
    static final int SAMPLE_STATES = 2_000;
    static final int SAMPLE_DEALS = 10;
    static final int EVERY_DEAL_STATES = 20;

    private static final int SPREADS = SystemScorer.SPREADS;
    private static final int MOVES = ScoringStates.MOVES;
    private static final int ACE_UP = ScoringStates.ACE_UP;

    private StrategyExport() {
    }

    // ------------------------------------------------------------------ plans and figures

    /** The report's plans (B2.5), in the order of its metrics: same-1 to same-8, own-1 to own-8, played-1 to played-8. */
    static List<String> reportPlans() {
        List<String> out = new ArrayList<>();
        for (String rule : new String[]{"same", "own", "played"}) {
            for (int s = 1; s <= SPREADS; s++) {
                out.add(rule + "-" + s);
            }
        }
        return out;
    }

    /** A report plan's spread, 1 to 8, from its label. */
    static int spread(String label) {
        return Integer.parseInt(label.substring(label.indexOf('-') + 1));
    }

    /**
     * B2.5's RULE of a plan: for a report plan, own for the own ramp, rounded or not, and same for
     * the same schedule; for a wide plan (PLAN_COMPUTE.md 3.1), same when its bets are handed out
     * as the same schedule, own for a plan that leaves the table, which has only the own rule.
     */
    static String rule(String label) {
        SystemScorer.Plan w = widePlan(label);
        if (w != null) {
            return w.sameSchedule() ? "same" : "own";
        }
        return label.startsWith("same-") ? "same" : "own";
    }

    /** The wide plan of this label (SystemScorer.WIDE_PLANS, B2.5's labels such as P16, B0,8, X-1.0,8), or null. */
    static SystemScorer.Plan widePlan(String label) {
        for (SystemScorer.Plan p : SystemScorer.WIDE_PLANS) {
            if (p.label.equals(label)) {
                return p;
            }
        }
        return null;
    }

    /** A label the exporter writes: one of the report's plans or one of the wide plans; any other is refused. */
    static void checkLabel(String label) {
        if (widePlan(label) == null) {
            metrics(label);
        }
    }

    /**
     * A list of plan labels as a command line gives it, comma separated. A wide plan's label holds
     * a comma itself (B0,8, X-1.0,8), so a part that is only digits after a part that starts a B
     * or X label is put back on it: "same-8,B0,8,X-1.0,8" is same-8, B0,8 and X-1.0,8.
     */
    static String[] planList(String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split(",")) {
            String t = part.trim();
            int last = out.size() - 1;
            if (last >= 0 && t.matches("\\d+") && out.get(last).matches("[BX][-+]?[0-9.]+")) {
                out.set(last, out.get(last) + "," + t);
            } else {
                out.add(t);
            }
        }
        for (String l : out) {
            checkLabel(l);
        }
        return out.toArray(new String[0]);
    }

    /** {EV metric, average-bet metric} of a report plan in SystemScorer.METRICS. */
    static int[] metrics(String label) {
        int s = spread(label);
        if (label.startsWith("same-")) {
            return new int[]{SystemScorer.same(s), SystemScorer.sameBet(s)};
        }
        if (label.startsWith("own-")) {
            return new int[]{SystemScorer.own(s), SystemScorer.ownBet(s)};
        }
        if (label.startsWith("played-")) {
            return new int[]{SystemScorer.played(s), SystemScorer.playedBet(s)};
        }
        throw new IllegalArgumentException("no report plan " + label);
    }

    /** B5's figures of a file with these plans, in order: flat, play, insurance, then ev: and bet: of each plan. */
    static List<String> figures(String[] labels) {
        List<String> out = new ArrayList<>(Arrays.asList("flat", "play", "insurance"));
        for (String l : labels) {
            out.add("ev:" + l);
            out.add("bet:" + l);
        }
        return out;
    }

    /** B5's metric of figure number fi of a file with these plans: its index in SystemScorer.METRICS, or -1. */
    static int metric(String[] labels, int fi) {
        if (fi < 3) {
            return fi;
        }
        String label = labels[(fi - 3) / 2];
        if (!reportPlans().contains(label)) {
            return -1;
        }
        return metrics(label)[(fi - 3) % 2];
    }

    /** B5's x of a state for figure number fi: what the figure averages, weighted 1/q. */
    static double x(int fi, double rvPlay, double rvInsurance, double[] bet) {
        if (fi == 0) {
            return rvPlay + rvInsurance;
        }
        if (fi == 1) {
            return rvPlay;
        }
        if (fi == 2) {
            return rvInsurance;
        }
        double b = bet[(fi - 3) / 2];
        return (fi - 3) % 2 == 0 ? b * (rvPlay + rvInsurance) : b;
    }

    // ------------------------------------------------------------------ the strategies, refitted

    /**
     * One count's play at one penetration, fitted again as SystemScorer.scoreOnce fits it for the
     * estimate: each fold's groups and moves on the other fold's states (foldPlay), its insurance
     * in the same groups (insuranceSums, then insuranceValues for every state), and its moves
     * ranked for the file's first lines (FullIndexFit.rankings).
     */
    static final class Refit {
        final CountKeys.Count count;
        final int p;
        final SystemScorer.Pen pen;
        final SystemScorer.FoldPlay[] plays = new SystemScorer.FoldPlay[2];
        /** [fold][g x 55 + ace-up deal]: the training sums insurance is taken on where positive. */
        final double[][] insuranceSum = new double[2][];
        /** [fold][j]: what that fold's insurance adds to state j's round value. */
        final double[][] insurance = new double[2][];
        final PlayerMove[][][][] ranking = new PlayerMove[2][][][];
        /**
         * Full play only, null otherwise: the parts the values are indexed by, each fold's Hit and
         * Split values from the strategy's last round, its later groups and stands[l x CELLS + cell].
         */
        final SystemScorer.Parts parts;
        final SystemScorer.PlayValues[] values;
        final SystemScorer.Groups[] later;
        final boolean[][] stands;
        /**
         * Full play only: per fold, [g][deal], the ranking of the play the strategy's last round
         * walked (now, SHOE_SPEC.md B2.4's kept play), which every split it valued was played by.
         */
        final PlayerMove[][][][] walked = new PlayerMove[2][][][];
        /**
         * Full play only: per fold, every split the strategy's last round valued, with its
         * fingerprint (FullIndexFit.valuedSplits), taken from the strategy as it is, not
         * from walked: what the files' split hands must play, wherever a Split value was worked out.
         */
        final List<List<FullIndexFit.ValuedSplit>> valued = new ArrayList<>();
        /**
         * Full play: per fold, the (group, deal) cells the refit ranks otherwise than the walked
         * play, each a split line of the file (B2.1), and the first of them in words.
         */
        final int[] rankingDiffers = new int[2];
        final String[] firstDifference = new String[2];

        Refit(SystemScorer scorer, CountKeys.Count count, int p) {
            this.count = count;
            this.p = p;
            parts = null;
            values = null;
            later = null;
            stands = null;
            pen = scorer.pen(p);
            SystemScorer.Parts parts = scorer.parts(count, pen);
            SystemScorer.InsuranceParts ip = scorer.insuranceParts(parts, pen);
            for (int f = 0; f < 2; f++) {
                plays[f] = scorer.foldPlay(parts, pen, ip, pen.train[f], pen.weight, null, null);
                insuranceSum[f] = SystemScorer.insuranceSums(ip, plays[f].insuranceGroup, plays[f].groups.count(),
                        pen.train[f], pen.weight);
                insurance[f] = SystemScorer.insuranceValues(ip, plays[f].insuranceGroup, insuranceSum[f]);
                ranking[f] = FullIndexFit.rankings(scorer.st, scorer.rules(), plays[f].fit);
            }
        }

        /**
         * A count's full play at penetration p from its two fold strategies after their last round
         * (FullIndexFit.Strategy, as the replay of PLAN_COMPUTE.md 6.1 holds them): each fold's
         * play fitted again from the strategy's Hit and Split values, as SystemScorer.score does
         * for the full-play entries, with the insurance in the same groups, the moves ranked from
         * that fit, and the strategy's later groups, the later play its last round walked (now)
         * and that play's ranking (walked). The dealt hand plays the refit's ranking; the Split
         * values were worked out with split hands playing the walked one (B2.4), and the cells
         * where the two differ are the file's split lines (rankingDiffers).
         *
         * The walked SplitPlay of a part took its group from the Strategy's own groups and table
         * (FullIndexFit's record), while the file's group lines are the refit's and the round trip
         * reads both rankings at the group the file gives; so a fold whose Strategy groups or table
         * are not the refit's is refused here (Refused), as a same-count pair of groups with other
         * bounds would pass every other check (SHOE_SPEC.md B7). Each split the last round valued
         * is kept with its fingerprint (valued), for the round trip to play again from the files.
         * Anything else wrong with the strategies given is a mistake of the caller's and throws as
         * such.
         */
        Refit(SystemScorer scorer, int p, FullIndexFit.Strategy[] two) {
            if (two.length != 2 || two[0] == null || two[1] == null) {
                throw new IllegalArgumentException("full play is exported from both fold strategies");
            }
            count = two[0].count;
            this.p = p;
            pen = scorer.pen(p);
            SystemScorer.Parts held = two[0].parts != null ? two[0].parts : two[1].parts;
            parts = held != null ? held : scorer.parts(count, pen);
            SystemScorer.InsuranceParts ip = scorer.insuranceParts(parts, pen);
            values = new SystemScorer.PlayValues[2];
            later = new SystemScorer.Groups[2];
            stands = new boolean[2][];
            for (int f = 0; f < 2; f++) {
                FullIndexFit.Strategy t = two[f];
                if (t.fold != f || t.pen != p || t.half || t.count != count || t.values == null || t.later == null
                        || t.now == null || t.now.stands == null) {
                    throw new IllegalArgumentException(t + " is not fold " + f + "'s finished full-play strategy of " + count
                            + " at " + SystemScorer.PENETRATIONS[p]);
                }
                values[f] = t.values;
                plays[f] = scorer.foldPlay(parts, pen, ip, pen.train[f], pen.weight, null, null, null, null, values[f]);
                insuranceSum[f] = SystemScorer.insuranceSums(ip, plays[f].insuranceGroup, plays[f].groups.count(),
                        pen.train[f], pen.weight);
                insurance[f] = SystemScorer.insuranceValues(ip, plays[f].insuranceGroup, insuranceSum[f]);
                ranking[f] = FullIndexFit.rankings(scorer.st, scorer.rules(), plays[f].fit);
                later[f] = t.later;
                stands[f] = t.now.stands.clone();
                if (stands[f].length != later[f].count() * FullIndexFit.CELLS) {
                    throw new IllegalStateException(t + ": " + stands[f].length + " later cells for " + later[f].count() + " groups");
                }
                if (!Arrays.equals(t.groups.lo, plays[f].groups.lo) || !Arrays.equals(t.groups.hi, plays[f].groups.hi)
                        || !Arrays.equals(t.table, plays[f].table)) {
                    throw new Refused(t + ": the strategy's groups " + Arrays.toString(t.groups.hi)
                            + " or their table are not the refit's " + Arrays.toString(plays[f].groups.hi)
                            + ", so its split hands were not played in the groups the file gives (B7)");
                }
                walked(f, t.now.ranking);
                valued.add(FullIndexFit.valuedSplits(t));
            }
        }

        /**
         * A fold B7 refuses to export, its strategy being as it should: groups or a table not the
         * refit's, or a walked ranking of another number of groups. The replay's export keeps it
         * as the failure of each row of the item and goes on (ReplayExport.after).
         */
        static final class Refused extends IllegalStateException {
            private static final long serialVersionUID = 1L;

            Refused(String message) {
                super(message);
            }
        }

        /**
         * Fold f's walked ranking, [g][deal], and the cells where the refit ranks otherwise counted
         * again; refused with another number of groups than the refit's. The constructor gives the
         * strategy's own; a test gives one made by hand, to export a fold whose rankings differ.
         */
        void walked(int f, PlayerMove[][][] table) {
            if (table.length != ranking[f].length) {
                throw new Refused(count.name + " at " + SystemScorer.PENETRATIONS[p] + ", fold " + f
                        + ": the walked play ranks " + table.length + " groups, the refit " + ranking[f].length);
            }
            walked[f] = table;
            rankingDiffers[f] = 0;
            firstDifference[f] = null;
            for (int g = 0; g < table.length; g++) {
                for (int i = 0; i < Deals.COUNT; i++) {
                    if (!Deals.playerNatural(i) && !Arrays.equals(ranking[f][g][i], table[g][i])) {
                        if (rankingDiffers[f]++ == 0) {
                            firstDifference[f] = cell(g, i) + ": " + words(ranking[f][g][i]) + " by the refit (its "
                                    + "first line), " + words(table[g][i]) + " by the walked play (its split line)";
                        }
                    }
                }
            }
        }

        /**
         * Fold f's split lines (B2.1), [g][deal]: the walked ranking where it is not the refit's,
         * null elsewhere (B7).
         */
        PlayerMove[][][] splitLines(int f) {
            PlayerMove[][][] out = new PlayerMove[ranking[f].length][Deals.COUNT][];
            for (int g = 0; g < out.length; g++) {
                for (int i = 0; i < Deals.COUNT; i++) {
                    if (!Deals.playerNatural(i) && !Arrays.equals(ranking[f][g][i], walked[f][g][i])) {
                        out[g][i] = walked[f][g][i].clone();
                    }
                }
            }
            return out;
        }

        /** The log's line of fold f's split lines: how many, and the first of them. */
        String splitWords(int f) {
            return "fold " + f + ": " + rankingDiffers[f] + " split line" + (rankingDiffers[f] == 1 ? "" : "s")
                    + (rankingDiffers[f] == 0 ? "" : ", the first " + firstDifference[f]);
        }

        boolean full() {
            return values != null;
        }
    }

    /** A (group, deal) cell in words. */
    static String cell(int g, int deal) {
        return "group " + g + ", deal " + Deals.P1[deal] + " " + Deals.P2[deal] + " " + Deals.UP[deal];
    }

    /** A ranked list of moves as a file writes them: B1's letters, a space between. */
    static String words(PlayerMove[] moves) {
        StringBuilder b = new StringBuilder();
        for (PlayerMove m : moves) {
            b.append(b.length() > 0 ? " " : "").append(ExportBundle.letter(m));
        }
        return b.toString();
    }

    /** One exported row at one penetration: its two fold files, its states.bin section and the figures it must give. */
    static final class Built {
        final ExportBundle.Strategy[] fold = new ExportBundle.Strategy[2];
        /** Each fold file's answers as the engine's fit gives them, for lookups.tsv (B8). */
        final Lookup[] lookup = new Lookup[2];
        ExportBundle.Section section;
        /** [fold][plan]: the exit key of a wide plan that leaves the table (B2.5's EXIT), null for the others. */
        Integer[][] exit;
    }

    /**
     * A row's two strategy files and its section of states.bin at penetration p, from the count's
     * refitted play. Each fold's bets are those SystemScorer.bets hands out on that fold's test
     * states (B7), and every state of the penetration takes its own fold's play, insurance and
     * bets, as the estimate scores it.
     */
    static Built build(SystemScorer scorer, Refit rf, SystemScorer.Entry e, String[] labels) {
        if (e.ownValue) {
            throw new IllegalArgumentException(e.name + ": a row betting on its own value has no bet keys");
        }
        if (e.fullPlay != rf.full()) {
            throw new IllegalArgumentException(e.name + (e.fullPlay ? " plays full: it is exported from its fold "
                    + "strategies' values (the replay's export hook)" : " plays first decisions, not the full play given"));
        }
        CountKeys.Count play = rf.count;
        CountKeys.Count bet = e.count;
        // By name: a Result read back from a file holds copies of its counts.
        if (!bet.name.equals(play.name) && (bet.base == null || !bet.base.name.equals(play.name))) {
            throw new IllegalArgumentException(e.name + " does not bet on " + play.name + " or a variant of it");
        }
        if (bet.trueCounted != play.trueCounted || bet.divisor() != play.divisor()) {
            throw new IllegalArgumentException(e.name + ": a strategy file has one mode and one divisor for both keys");
        }
        SystemScorer.Pen pen = rf.pen;
        ScoringStates st = scorer.st;
        SystemScorer.BetKeys bk = scorer.betKeys(bet, pen);
        Built out = new Built();
        double[][] units = new double[2][];
        // The wide plans among the labels (PLAN_COMPUTE.md 3.1), their bets per key worked out by
        // SystemScorer.Wide from the same fitted advantage and blocks as the report's plans; a plan
        // that leaves the table needs each state's lowest bet key of its shoe so far.
        SystemScorer.Plan[] wide = widePlans(labels);
        int[] lowest = lowestKeys(scorer, bet, pen, wide);
        out.exit = new Integer[2][labels.length];
        for (int f = 0; f < 2; f++) {
            SystemScorer.FoldPlay fp = rf.plays[f];
            double[] value = e.bettingOnly ? pen.chart : fp.base;
            double[] rv = new double[pen.states.length];
            for (int j = 0; j < rv.length; j++) {
                rv[j] = value[j] + rf.insurance[f][j];
            }
            double[][] detail = new double[bk.value.length][SystemScorer.DETAIL];
            SystemScorer.Wide acc = null;
            if (wide.length > 0) {
                acc = new SystemScorer.Wide(wide, SystemScorer.referenceBets(wide, pen.chart), lowest);
                acc.detail = new double[bk.value.length][wide.length * SystemScorer.KEY_COLS];
            }
            SystemScorer.bets(bk, pen.train[f], pen.test[f], pen.testByChart[f], pen.weight, rv, pen.chart,
                    new double[SystemScorer.METRICS + 1], detail, acc);

            ExportBundle.Strategy s = new ExportBundle.Strategy();
            s.row = e.name;
            s.scope = e.fullPlay ? ExportBundle.Scope.FULL : e.bettingOnly ? ExportBundle.Scope.BETTING
                    : ExportBundle.Scope.FIRST;
            s.pen = ExportBundle.PENS[rf.p];
            s.cut = pen.cut;
            s.fold = f;
            s.trained = fp.fit.weight;
            s.playCount = play.name;
            s.tags = play.tag.clone();
            s.scale = play.scale;
            s.trueCounted = play.trueCounted;
            s.divisor = play.divisor();
            s.deckSum = play.deckSum;
            s.betCount = bet.name;
            s.betTags = bet.betTags().clone();
            s.betDeckSum = bet.betDeckSum();
            s.ace = bet.aceSideCount;
            s.lo = fp.groups.lo.clone();
            s.hi = fp.groups.hi.clone();
            s.first = e.bettingOnly ? null : rf.ranking[f];
            if (e.fullPlay) {
                // Split hands were valued under the walked play's ranking: its own lines where the
                // refit's, which the dealt hand plays, ranks a deal otherwise (B2.1, B2.4).
                s.split = rf.splitLines(f);
                s.laterLo = rf.later[f].lo.clone();
                s.laterHi = rf.later[f].hi.clone();
                s.stands = new boolean[s.laterLo.length][ExportBundle.CELLS];
                for (int l = 0; l < s.laterLo.length; l++) {
                    System.arraycopy(rf.stands[f], l * FullIndexFit.CELLS, s.stands[l], 0, FullIndexFit.CELLS);
                }
            }
            int groups = fp.groups.count();
            s.insure = new boolean[groups][ACE_UP];
            for (int g = 0; g < groups; g++) {
                for (int i = 0; i < ACE_UP; i++) {
                    s.insure[g][i] = rf.insuranceSum[f][g * ACE_UP + i] > 0;
                }
            }
            int present = 0;
            for (double[] d : detail) {
                present += d[0] > 0 ? 1 : 0;
            }
            int[] key = new int[present];
            int[] at = new int[present];
            present = 0;
            for (int k = 0; k < detail.length; k++) {
                if (detail[k][0] > 0) {
                    key[present] = bk.value[k];
                    at[present++] = k;
                }
            }
            units[f] = new double[bk.value.length * labels.length];
            Arrays.fill(units[f], Double.NaN);
            for (int l = 0; l < labels.length; l++) {
                double[] u = new double[present];
                double[] chance = new double[present];
                SystemScorer.Plan w = widePlan(labels[l]);
                int i = w == null ? -1 : Arrays.asList(wide).indexOf(w);
                for (int x = 0; x < present; x++) {
                    if (w == null) {
                        u[x] = units(detail[at[x]], labels[l]);
                        chance[x] = 1;
                    } else {
                        double[] u2 = wideUnits(w, acc.detail[at[x]], i);
                        u[x] = u2[0];
                        chance[x] = u2[1];
                    }
                    // B3: states.bin holds the expected bet, UNITS x PLAY, as the round trip reads it back.
                    units[f][at[x] * labels.length + l] = u[x] * chance[x];
                }
                if (w != null && !w.sameSchedule()) {
                    double[] adv = new double[detail.length];
                    for (int k = 0; k < adv.length; k++) {
                        adv[k] = detail[k][2];
                    }
                    out.exit[f][l] = SystemScorer.keyFrom(bk, adv, w.exit);
                }
                s.plans.add(new ExportBundle.Plan(labels[l], rule(labels[l]), out.exit[f][l], key, u, chance));
            }
            out.fold[f] = s;
            out.lookup[f] = new Lookup(s.fileName(), play, pen.cut, fp.groups, e.bettingOnly ? null : fp.fit.move,
                    rf.insuranceSum[f], e.fullPlay ? rf.later[f] : null, e.fullPlay ? rf.stands[f] : null,
                    e.fullPlay ? rf.walked[f] : null, scorer.rules());
        }
        out.section = section(st, pen, rf, e, bk, units, labels, out.exit, lowest);
        return out;
    }

    /** The wide plans among labels, in their order; none for the report's plans alone. */
    static SystemScorer.Plan[] widePlans(String[] labels) {
        List<SystemScorer.Plan> out = new ArrayList<>();
        for (String l : labels) {
            SystemScorer.Plan w = widePlan(l);
            if (w != null) {
                out.add(w);
            }
        }
        return out.toArray(new SystemScorer.Plan[0]);
    }

    /**
     * Each state's lowest bet key of its shoe so far, by its place in the penetration, for the
     * wide plans that leave the table (AllStates.minKey through the scorer's round starts, which
     * the replay's --wide sets); null when no plan given leaves.
     */
    static int[] lowestKeys(SystemScorer scorer, CountKeys.Count bet, SystemScorer.Pen pen, SystemScorer.Plan[] wide) {
        if (!SystemScorer.anyExit(wide)) {
            return null;
        }
        if (scorer.roundStarts == null) {
            throw new IllegalArgumentException("a plan that leaves the table is exported only where the scorer knows every "
                    + "round start of each shoe (the replay's --wide, SystemScorer.roundStarts)");
        }
        int[] all = scorer.minKey(bet);
        int[] out = new int[pen.states.length];
        for (int j = 0; j < out.length; j++) {
            out[j] = all[pen.states[j]];
        }
        return out;
    }

    /**
     * A wide plan's UNITS and PLAY at a key (B2.5), from SystemScorer.Wide's detail row for it,
     * the plan the i-th of those the accumulator scored. The same schedule: the expected bet
     * K_SAME_BET played with chance K_SAME_PLAY, so UNITS is the bet when played, the expected bet
     * over the chance, and a key the plan sits out is UNITS 0 and PLAY 0. A plan that leaves the
     * table: the own rule's bet while seated, played every round; the exit key goes on the plan line.
     */
    static double[] wideUnits(SystemScorer.Plan w, double[] wideDetail, int i) {
        int col = i * SystemScorer.KEY_COLS;
        if (!w.sameSchedule()) {
            return new double[]{wideDetail[col + SystemScorer.K_OWN_BET], 1};
        }
        double bet = wideDetail[col + SystemScorer.K_SAME_BET];
        double play = wideDetail[col + SystemScorer.K_SAME_PLAY];
        if (play == 0) {
            return new double[]{0, 0};
        }
        // A block whose every round is played has a share of 1 less rounding (its integral of
        // 1[g > 0] and its weight are summed in two orders); B2.5 writes PLAY 1 there, so only the
        // block that straddles the entry threshold carries a chance.
        if (play == 1 || Math.abs(play - 1) <= PLAY_ONE) {
            return new double[]{bet, 1};
        }
        return new double[]{bet / play, play};
    }

    /** How near 1 a block's share played is taken as 1, its rounding: far below any share a straddling block of real weight has. */
    static final double PLAY_ONE = 1e-12;

    /** The bet of a report plan at a key, from SystemScorer.bets' detail row for it (B7). */
    static double units(double[] detail, String label) {
        int s = spread(label);
        if (label.startsWith("same-")) {
            return detail[2 + s];
        }
        double own = detail[2 + SPREADS + s];
        if (label.startsWith("own-")) {
            return own;
        }
        if (label.startsWith("played-")) {
            return Math.floor(own + 0.5);
        }
        throw new IllegalArgumentException("no report plan " + label);
    }

    /**
     * A row's section of states.bin (B3): every state of the penetration in record order, each with
     * its bet key and its own fold's round value, insurance and bet under each plan.
     */
    static ExportBundle.Section section(ScoringStates st, SystemScorer.Pen pen, Refit rf, SystemScorer.Entry e,
                                        SystemScorer.BetKeys bk, double[][] units, String[] labels) {
        return section(st, pen, rf, e, bk, units, labels, null, null);
    }

    /**
     * The same, a plan with an exit key (exit[fold][plan], null for none) betting 0 at a state
     * whose shoe's lowest bet key so far (lowest, by the state's place in the penetration) is
     * below its fold's exit: the player has left the table until the shuffle (B2.5, B3).
     */
    static ExportBundle.Section section(ScoringStates st, SystemScorer.Pen pen, Refit rf, SystemScorer.Entry e,
                                        SystemScorer.BetKeys bk, double[][] units, String[] labels, Integer[][] exit,
                                        int[] lowest) {
        ExportBundle.Section sec = new ExportBundle.Section();
        int m = pen.states.length;
        sec.row = e.name;
        sec.pen = ExportBundle.PENS[rf.p];
        sec.cut = pen.cut;
        sec.labels = labels.clone();
        sec.index = new int[m];
        sec.betKey = new int[m];
        sec.rvPlay = new double[m];
        sec.rvInsurance = new double[m];
        sec.bet = new double[m][labels.length];
        for (int j = 0; j < m; j++) {
            int s = pen.states[j];
            int f = st.fold(s);
            sec.index[j] = s;
            sec.betKey[j] = bk.value[bk.index[j]];
            sec.rvPlay[j] = e.bettingOnly ? pen.chart[j] : rf.plays[f].base[j];
            sec.rvInsurance[j] = rf.insurance[f][j];
            for (int l = 0; l < labels.length; l++) {
                double u = units[f][bk.index[j] * labels.length + l];
                if (Double.isNaN(u)) {
                    throw new IllegalStateException(e.name + ": state " + s + "'s key " + sec.betKey[j]
                            + " has no bet in its own fold, whose test states it is one of");
                }
                // B3: the expected bet, UNITS x PLAY (units holds the product), 0 once a plan with an
                // exit has left the table.
                boolean left = exit != null && exit[f][l] != null && lowest[j] < exit[f][l];
                sec.bet[j][l] = left ? 0 : u;
            }
        }
        return sec;
    }

    /** The chart's reference row, as SystemScorer.references names it: the walk's own play, no count (B3, B5). */
    static final String CHART_ROW = "Chart, no count, flat";

    /** The chart's row to export: its Entry, SystemScorer.references' first, and the penetrations (0, 1, 2). */
    static final class ChartRow {
        final SystemScorer.Entry entry;
        final int[] pens;

        ChartRow(SystemScorer.Entry entry, int... pens) {
            if (!entry.name.equals(CHART_ROW) || entry.count != null) {
                throw new IllegalArgumentException(entry.name + " is not the chart's reference row");
            }
            this.entry = entry;
            this.pens = pens.clone();
        }
    }

    /**
     * The chart's section of states.bin at a penetration (B3): every state of it, bet key 0 (no
     * count), the chart's round value, no insurance (the chart's flat row has none) and no plan.
     * It has no strategy file: the simulator plays the chart's walk itself (B1).
     */
    static ExportBundle.Section chartSection(SystemScorer.Pen pen, int p) {
        ExportBundle.Section sec = new ExportBundle.Section();
        int m = pen.states.length;
        sec.row = CHART_ROW;
        sec.pen = ExportBundle.PENS[p];
        sec.cut = pen.cut;
        sec.labels = new String[0];
        sec.index = pen.states.clone();
        sec.betKey = new int[m];
        sec.rvPlay = pen.chart.clone();
        sec.rvInsurance = new double[m];
        sec.bet = new double[m][0];
        return sec;
    }

    // ------------------------------------------------------------------ B2: strategy files

    /** A line of tab-separated fields, refused if a field is empty or holds a tab or a line break (B0). */
    private static void line(StringBuilder b, Object... fields) {
        for (int k = 0; k < fields.length; k++) {
            String f = String.valueOf(fields[k]);
            if (f.isEmpty() || f.indexOf('\t') >= 0 || f.indexOf('\n') >= 0 || f.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("a field B0 does not allow: \"" + f + "\"");
            }
            if (k > 0) {
                b.append('\t');
            }
            b.append(f);
        }
        b.append('\n');
    }

    /** B0: a double as Double.toString writes it; NaN and the infinities are refused. */
    static String real(double x) {
        if (!Double.isFinite(x)) {
            throw new IllegalArgumentException(x + " where B0 allows only finite doubles");
        }
        return Double.toString(x);
    }

    private static Object[] keyword(String word, int[] values) {
        Object[] f = new Object[1 + values.length];
        f[0] = word;
        for (int k = 0; k < values.length; k++) {
            f[1 + k] = values[k];
        }
        return f;
    }

    /** A strategy file's text, line by line in B2.1's order. */
    static String format(ExportBundle.Strategy s) {
        StringBuilder b = new StringBuilder();
        line(b, "format", "strategy", 1);
        line(b, "row", s.row);
        line(b, "scope", s.scope.word());
        line(b, "pen", s.pen, s.cut);
        line(b, "fold", s.fold, real(s.trained));
        line(b, "playcount", s.playCount);
        line(b, keyword("tags", s.tags));
        line(b, "scale", s.scale);
        line(b, "mode", s.trueCounted ? "true" : "running");
        line(b, "divisor", s.divisor);
        line(b, "decksum", s.deckSum);
        line(b, "betcount", s.betCount);
        line(b, keyword("bettags", s.betTags));
        line(b, "betdecksum", s.betDeckSum);
        line(b, "ace", s.ace);
        line(b, "groups", s.lo.length);
        for (int g = 0; g < s.lo.length; g++) {
            line(b, "group", g, s.lo[g], s.hi[g]);
        }
        for (String word : new String[]{"first", "split"}) {
            PlayerMove[][][] table = word.equals("first") ? s.first : s.split;
            if (s.scope == ExportBundle.Scope.BETTING || table == null) {
                continue;
            }
            for (int g = 0; g < s.lo.length; g++) {
                for (int i = 0; i < Deals.COUNT; i++) {
                    PlayerMove[] moves = table[g][i];
                    if (Deals.playerNatural(i) || moves == null && word.equals("split")) {
                        continue;
                    }
                    Object[] f = new Object[5 + moves.length];
                    f[0] = word;
                    f[1] = g;
                    f[2] = Deals.P1[i];
                    f[3] = Deals.P2[i];
                    f[4] = Deals.UP[i];
                    for (int k = 0; k < moves.length; k++) {
                        f[5 + k] = ExportBundle.letter(moves[k]);
                    }
                    line(b, f);
                }
            }
        }
        for (int g = 0; g < s.insure.length; g++) {
            for (int i = 0; i < ACE_UP; i++) {
                if (s.insure[g][i]) {
                    line(b, "insure", g, Deals.P1[i], Deals.P2[i]);
                }
            }
        }
        if (s.scope == ExportBundle.Scope.FULL) {
            line(b, "lgroups", s.laterLo.length);
            for (int l = 0; l < s.laterLo.length; l++) {
                line(b, "lgroup", l, s.laterLo[l], s.laterHi[l]);
            }
            int[][] cells = ExportBundle.cells();
            for (int l = 0; l < s.laterLo.length; l++) {
                for (int c = 0; c < ExportBundle.CELLS; c++) {
                    line(b, "later", l, cells[c][1], cells[c][2], cells[c][0], s.stands[l][c] ? "S" : "H");
                }
            }
        }
        for (ExportBundle.Plan p : s.plans) {
            line(b, "plan", p.label, p.rule, p.exit == null ? "-" : p.exit);
        }
        for (ExportBundle.Plan p : s.plans) {
            for (int k = 0; k < p.key.length; k++) {
                line(b, "bet", p.label, p.key[k], real(p.units[k]), real(p.play[k]));
            }
        }
        line(b, "unseen", "nearest", "lower");
        line(b, "end");
        return b.toString();
    }

    // ------------------------------------------------------------------ B3: states.bin

    private static void str(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    /** B3's order of sections: by row name, then penetration. */
    static final Comparator<ExportBundle.Section> SECTION_ORDER =
            Comparator.comparing((ExportBundle.Section s) -> s.row).thenComparingInt(s -> s.pen);

    /** states.bin: the state file's header and every valued state, then the sections in B3's order, then the trailer. */
    static void writeStates(Path file, StateStore.Header h, ScoringStates st, List<ExportBundle.Section> sections)
            throws IOException {
        List<ExportBundle.Section> sorted = new ArrayList<>(sections);
        sorted.sort(SECTION_ORDER);
        for (int k = 1; k < sorted.size(); k++) {
            if (SECTION_ORDER.compare(sorted.get(k - 1), sorted.get(k)) == 0) {
                throw new IllegalArgumentException(sorted.get(k).row + " at " + sorted.get(k).pen + "% twice");
            }
        }
        try (OutputStream raw = Files.newOutputStream(file);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw, 1 << 20))) {
            out.write(ExportBundle.STATES_MAGIC.getBytes(StandardCharsets.US_ASCII));
            out.writeInt(1);
            out.writeLong(h.seed);
            out.writeDouble(h.q0);
            out.writeDouble(h.u0);
            out.writeInt(h.cut);
            out.writeInt(st.shoes);
            out.writeInt(st.size);
            for (int s = 0; s < st.size; s++) {
                out.writeInt(st.shoe[s]);
                out.writeInt(st.round[s]);
                out.writeInt(st.depth[s]);
                out.writeDouble(st.q[s]);
                out.writeByte(st.fold(s));
                for (int k = 0; k < ShoeRun.KINDS; k++) {
                    out.writeByte(st.dealt[s][k]);
                }
            }
            out.writeInt(sorted.size());
            for (ExportBundle.Section sec : sorted) {
                str(out, sec.row);
                out.writeInt(sec.pen);
                out.writeInt(sec.cut);
                out.writeInt(sec.labels.length);
                for (String l : sec.labels) {
                    str(out, l);
                }
                out.writeInt(sec.index.length);
                for (int e = 0; e < sec.index.length; e++) {
                    out.writeInt(sec.index[e]);
                    out.writeInt(sec.betKey[e]);
                    out.writeDouble(sec.rvPlay[e]);
                    out.writeDouble(sec.rvInsurance[e]);
                    for (double b : sec.bet[e]) {
                        out.writeDouble(b);
                    }
                }
            }
            out.write(ExportBundle.STATES_END.getBytes(StandardCharsets.US_ASCII));
        }
    }

    // ------------------------------------------------------------------ B1: chart.tsv

    /** The chart's answer to every decision of B1, in its order, as move numbers 0 Stand to 4 Surrender. */
    static int[] chartMoves(RoundPolicy chart) {
        int[][] order = ExportBundle.chartDecisions();
        int[] out = new int[order.length];
        for (int n = 0; n < order.length; n++) {
            int[] d = order[n];
            int legal = d[6];
            boolean first = d[5] == 1;
            out[n] = chart.choose(new RoundPolicy.Decision(d[1], d[2] == 1, first ? 2 : 3, d[3], d[0], d[4] == 1, first,
                    (legal & 1) != 0, (legal & 2) != 0, (legal & 4) != 0, (legal & 8) != 0)).ordinal();
        }
        return out;
    }

    /**
     * chart.tsv: the chart's 253,440 answers, under its fingerprint. Refused unless the fingerprint
     * of the answers written is BasicStrategy.fingerprint of the chart, the one a state file records.
     */
    static void writeChart(Path file, RoundPolicy chart) throws IOException {
        int[] moves = chartMoves(chart);
        String print = ExportBundle.fingerprint(moves);
        if (!print.equals(BasicStrategy.fingerprint(chart))) {
            throw new IllegalStateException("chart.tsv's answers hash to " + print + ", the chart to "
                    + BasicStrategy.fingerprint(chart));
        }
        int[][] order = ExportBundle.chartDecisions();
        StringBuilder b = new StringBuilder(order.length * 24);
        b.append(ExportBundle.CHART_FIRST).append('\n');
        b.append("# fingerprint ").append(print).append('\n');
        b.append(ExportBundle.CHART_HEADER).append('\n');
        for (int n = 0; n < order.length; n++) {
            for (int x : order[n]) {
                b.append(x).append('\t');
            }
            b.append(ExportBundle.MOVE_LETTERS.charAt(moves[n])).append('\n');
        }
        Files.write(file, b.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ B4: keys-sample.tsv

    /**
     * The columns of keys-sample.tsv for the comparison (B4): every catalog entry, duplicates made
     * one, in catalog order; the null count; the counts of our own given (none, or the registered
     * ones); then every betting variant, the catalog's ace side-count variants first, then those of
     * the counts of our own.
     */
    static List<CountKeys.Count> keyCounts(List<CountKeys.Count> catalog, List<CountKeys.Count> own) {
        List<CountKeys.Count> out = new ArrayList<>(catalog);
        out.add(CountKeys.nullCount());
        out.addAll(own);
        for (CountKeys.Count c : catalog) {
            CountKeys.Count v = CountKeys.bettingVariant(c);
            if (v != null) {
                out.add(v);
            }
        }
        for (CountKeys.Count c : own) {
            CountKeys.Count v = CountKeys.bettingVariant(c);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    /**
     * The sample's lines (B4): the states drawn without replacement with the seed, each with the
     * deals drawn for it, as {record, deal, deal, ...}, in record order. Every state drawn has 10
     * deals drawn with the same generator, in record order, so the draw does not depend on which
     * states also get every deal; the first 20 records are added whether drawn or not, and they
     * list all 550 deals.
     */
    static List<int[]> sample(int states, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        int pick = Math.min(SAMPLE_STATES, states);
        int[] idx = new int[states];
        for (int k = 0; k < states; k++) {
            idx[k] = k;
        }
        for (int t = 0; t < pick; t++) {
            int j = t + rnd.nextInt(states - t);
            int x = idx[t];
            idx[t] = idx[j];
            idx[j] = x;
        }
        int[] chosen = Arrays.copyOf(idx, pick);
        Arrays.sort(chosen);
        Map<Integer, int[]> deals = new TreeMap<>();
        int[] all = new int[Deals.COUNT];
        for (int i = 0; i < all.length; i++) {
            all[i] = i;
        }
        for (int s : chosen) {
            int[] d = all.clone();
            for (int t = 0; t < SAMPLE_DEALS; t++) {
                int j = t + rnd.nextInt(Deals.COUNT - t);
                int x = d[t];
                d[t] = d[j];
                d[j] = x;
            }
            int[] drawn = Arrays.copyOf(d, SAMPLE_DEALS);
            Arrays.sort(drawn);
            deals.put(s, drawn);
        }
        for (int s = 0; s < Math.min(EVERY_DEAL_STATES, states); s++) {
            deals.put(s, all);
        }
        List<int[]> out = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : deals.entrySet()) {
            int[] line = new int[1 + e.getValue().length];
            line[0] = e.getKey();
            System.arraycopy(e.getValue(), 0, line, 1, e.getValue().length);
            out.add(line);
        }
        return out;
    }

    /** The count that plays a column's row: a betting variant plays on its base (B4). */
    static CountKeys.Count playing(CountKeys.Count c) {
        return c.base != null ? c.base : c;
    }

    /** One play cell of B4: the deal's key, or for a colour or face count every key with its chance, '-' if none. */
    static String playCell(CountKeys.Count c, int[] dealt, int depth, int deal) {
        CountKeys.Count p = playing(c);
        int rc = p.runningCount(dealt);
        if (!p.kindAware) {
            int t = p.rankTag(Deals.P1[deal]) + p.rankTag(Deals.P2[deal]) + p.rankTag(Deals.UP[deal]);
            return Integer.toString(p.playKey(rc, depth, t));
        }
        double[][] sums = p.tagSums(dealt, deal);
        if (sums.length == 0) {
            return "-";
        }
        StringBuilder b = new StringBuilder();
        for (double[] e : sums) {
            if (b.length() > 0) {
                b.append(';');
            }
            b.append(p.playKey(rc, depth, (int) e[0])).append(':').append(real(e[1]));
        }
        return b.toString();
    }

    /** keys-sample.tsv: a bet line for every state of the sample, then its play lines (B4). */
    static void writeKeysSample(Path file, ScoringStates st, List<CountKeys.Count> counts, long seed) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append(ExportBundle.KEYS_FIRST).append('\n');
        b.append("# sample seed ").append(seed).append('\n');
        List<Object> header = new ArrayList<>(Arrays.asList((Object[]) ExportBundle.KEYS_FIXED.split("\t")));
        for (CountKeys.Count c : counts) {
            header.add(c.name);
        }
        line(b, header.toArray());
        for (int[] l : sample(st.size, seed)) {
            int s = l[0];
            Object[] f = new Object[8 + counts.size()];
            f[0] = "bet";
            f[1] = s;
            f[2] = st.shoe[s];
            f[3] = st.round[s];
            f[4] = st.depth[s];
            f[5] = f[6] = f[7] = "-";
            for (int c = 0; c < counts.size(); c++) {
                f[8 + c] = counts.get(c).betKey(st.dealt[s], st.depth[s]);
            }
            line(b, f);
            for (int k = 1; k < l.length; k++) {
                int i = l[k];
                f[0] = "play";
                f[5] = Deals.P1[i];
                f[6] = Deals.P2[i];
                f[7] = Deals.UP[i];
                for (int c = 0; c < counts.size(); c++) {
                    f[8 + c] = playCell(counts.get(c), st.dealt[s], st.depth[s], i);
                }
                line(b, f);
            }
        }
        Files.write(file, b.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ B8: lookups.tsv

    /** B8: the states that get later lines, the first this many of the sample, in record order. */
    static final int LATER_STATES = 200;
    /** B8: the later lines' decision, on the deal's three cards and one hit card. */
    static final int LATER_SEEN = 4;

    /**
     * One strategy file's answers as the engine gives them (B8), from the fit the estimate scored
     * with, never from the file: the play key's parts from CountKeys, the group from Groups.of, the
     * dealt hand's first move from the fit's moves (the chart's for a betting row), insurance from
     * the insurance sums, and for full play the later group and the stand-or-hit of the later
     * play kept, and each split hand's first decision in every group as the walked play's
     * SplitPlay answers it. lookups.tsv holds these, and the round trip holds them to what the
     * file says.
     */
    static final class Lookup {
        final String file;
        final CountKeys.Count play;
        final int cut;
        final SystemScorer.Groups groups;
        /** fit.move, [g][deal]; null for a betting row, which plays the chart's first move. */
        final int[][] move;
        /** [g x 55 + ace-up deal]: the deal is insured in group g where this is positive. */
        final double[] insuranceSum;
        /** Full play only: the later groups, and stands[l x CELLS + cell]; null otherwise. */
        final SystemScorer.Groups later;
        final boolean[] stands;
        /** Full play only: the walked play's ranking, [g][deal], which split hands were valued under; null otherwise. */
        final PlayerMove[][][] walked;
        /** The rules the engine valued splits under, Montreal's (B8's legal moves). */
        final RoundRules rules;

        Lookup(String file, CountKeys.Count play, int cut, SystemScorer.Groups groups, int[][] move, double[] insuranceSum,
               SystemScorer.Groups later, boolean[] stands, PlayerMove[][][] walked, RoundRules rules) {
            this.file = file;
            this.play = play;
            this.cut = cut;
            this.groups = groups;
            this.move = move;
            this.insuranceSum = insuranceSum;
            this.later = later;
            this.stands = stands;
            this.walked = walked;
            this.rules = rules;
        }

        /**
         * B8's split cells of full play, in B8's order (ExportBundle.splitLine), '-' for every one
         * otherwise: in each group, the answer of the walked play's SplitPlay (FullIndexFit's
         * record builds it over walked[g]; the later play does not reach a first decision) to the
         * split hand's first decision on (r, x), read from the FIRST slots of
         * SplitValues.fingerprint, the answers the last round's fingerprints held; then, where a
         * resplit is allowed (x = r, r 2 to 10), ':' and the same with Split offered.
         */
        String[] split() {
            String[] out = new String[ExportBundle.SPLIT_LINES];
            if (walked == null) {
                Arrays.fill(out, "-");
                return out;
            }
            byte[][] prints = new byte[walked.length][];
            for (int n = 0; n < out.length; n++) {
                int[] l = ExportBundle.splitLine(n);
                int r = l[0];
                int x = l[1];
                int up = l[2];
                int deal = Deals.index(r, r, up);
                if (x == 1) {
                    for (int g = 0; g < walked.length; g++) {
                        PlayerMove[][] rank = walked[g];
                        prints[g] = SplitValues.fingerprint(rules, deal, new SplitPlay(r, up, (total, soft) -> true,
                                d -> rank[d]));
                    }
                }
                StringBuilder b = new StringBuilder();
                boolean resplit = x == r && rules.maxHandsFor(r) >= 3;
                for (int offered = 0; offered <= (resplit ? 1 : 0); offered++) {
                    b.append(offered == 1 ? ":" : "");
                    for (byte[] print : prints) {
                        int m = print[SplitValues.firstSlot(x, offered == 1)];
                        if (m == 0) {
                            throw new IllegalStateException(file + ": a split " + r + " drawing a " + x + " against "
                                    + up + " has no first decision in the engine's fingerprint");
                        }
                        b.append(ExportBundle.MOVE_LETTERS.charAt(m - 1));
                    }
                }
                out[n] = b.toString();
            }
            return out;
        }

        /** B8's first cell at state s and a deal: KEY:G:M:I for every part of the play key, '-' where none. */
        String first(ScoringStates st, int s, int deal) {
            if (st.depth[s] >= cut) {
                return "-";
            }
            int rc = play.runningCount(st.dealt[s]);
            int[] keys;
            if (!play.kindAware) {
                keys = new int[]{play.playKey(rc, st.depth[s],
                        play.rankTag(Deals.P1[deal]) + play.rankTag(Deals.P2[deal]) + play.rankTag(Deals.UP[deal]))};
            } else {
                double[][] sums = play.tagSums(st.dealt[s], deal);
                if (sums.length == 0) {
                    return "-";
                }
                keys = new int[sums.length];
                for (int k = 0; k < sums.length; k++) {
                    keys[k] = play.playKey(rc, st.depth[s], (int) sums[k][0]);
                }
            }
            StringBuilder b = new StringBuilder();
            for (int key : keys) {
                int g = groups.of(key);
                String m = Deals.playerNatural(deal) ? "-" : String.valueOf(ExportBundle.letter(
                        StateValuer.MOVES[move == null ? st.chartMove[deal] : move[g][deal]]));
                String ins = deal < ACE_UP ? (insuranceSum[g * ACE_UP + deal] > 0 ? "1" : "0") : "-";
                b.append(b.length() > 0 ? ";" : "").append(key).append(':').append(g).append(':').append(m).append(':')
                        .append(ins);
            }
            return b.toString();
        }

        /** B8's later cell: KEY:L:C:M after the deal and one hit card of rank hit; '-' unless full play on ranks. */
        String later(ScoringStates st, int s, int deal, int hit) {
            if (later == null || st.depth[s] >= cut || play.kindAware) {
                return "-";
            }
            int p1 = Deals.P1[deal];
            int p2 = Deals.P2[deal];
            int up = Deals.UP[deal];
            int t = play.rankTag(p1) + play.rankTag(p2) + play.rankTag(up) + play.rankTag(hit);
            int key = play.keyAfter(play.runningCount(st.dealt[s]), st.depth[s], t, LATER_SEEN);
            int l = later.of(key);
            int hard = p1 + p2 + hit;
            boolean soft = (p1 == 1 || p2 == 1 || hit == 1) && hard <= 11;
            int total = soft ? hard + 10 : hard;
            if (total > 21) {
                return key + ":" + l + ":-:-";
            }
            int c = FullIndexFit.cellOf(PlayerTree.spot(total, soft, up));
            String m = c >= 0 ? (stands[l * FullIndexFit.CELLS + c] ? "S" : "H")
                    : total == 21 || PlayerTree.CHART.stands(total, soft, up, key) ? "S" : "H";
            return key + ":" + l + ":" + (c < 0 ? "-" : Integer.toString(c)) + ":" + m;
        }
    }

    /**
     * B8's later lines, each {state, deal, hit}: for the first LATER_STATES states of the sample,
     * the first deal listed for it, and each rank of which a card is left once the deal's three
     * cards are taken from the state's cards left; none for a state whose cards left cannot make
     * that deal.
     */
    static List<int[]> laterLines(ScoringStates st, List<int[]> sample) {
        List<int[]> out = new ArrayList<>();
        for (int k = 0; k < Math.min(LATER_STATES, sample.size()); k++) {
            int s = sample.get(k)[0];
            int deal = sample.get(k)[1];
            int[] left = ShoeRun.ranksLeft(st.dealt[s]);
            left[Deals.P1[deal]]--;
            left[Deals.P2[deal]]--;
            left[Deals.UP[deal]]--;
            if (left[Deals.P1[deal]] < 0 || left[Deals.P2[deal]] < 0 || left[Deals.UP[deal]] < 0) {
                continue;
            }
            for (int r = 1; r <= 10; r++) {
                if (left[r] > 0) {
                    out.add(new int[]{s, deal, r});
                }
            }
        }
        return out;
    }

    /** One column of lookups.tsv: a strategy file's name and its cells, line by line. */
    static final class LookupColumn implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        final String file;
        final String[] first;
        /** Null for a file with no later play: every later cell '-'. */
        final String[] later;
        /** B8's 1,000 split cells in their order; null for a file with no later play, every split cell '-'. */
        final String[] split;

        LookupColumn(String file, String[] first, String[] later, String[] split) {
            this.file = file;
            this.first = first;
            this.later = later;
            this.split = split;
        }

        static LookupColumn of(Lookup l, ScoringStates st, List<int[]> sample, List<int[]> later) {
            List<String> first = new ArrayList<>();
            for (int[] line : sample) {
                for (int k = 1; k < line.length; k++) {
                    first.add(l.first(st, line[0], line[k]));
                }
            }
            String[] lt = null;
            if (l.later != null) {
                lt = new String[later.size()];
                for (int k = 0; k < lt.length; k++) {
                    int[] x = later.get(k);
                    lt[k] = l.later(st, x[0], x[1], x[2]);
                }
            }
            return new LookupColumn(l.file, first.toArray(new String[0]), lt, l.walked == null ? null : l.split());
        }
    }

    /**
     * lookups.tsv (B8): a first line for each play line of keys-sample.tsv, then, if any file
     * plays full, the later lines and the 1,000 split lines.
     */
    static void writeLookups(Path file, ScoringStates st, long seed, List<int[]> sample, List<int[]> later,
                             List<LookupColumn> columns) throws IOException {
        List<LookupColumn> cols = new ArrayList<>(columns);
        cols.sort(Comparator.comparing((LookupColumn c) -> c.file));
        boolean full = false;
        List<Object> header = new ArrayList<>(Arrays.asList((Object[]) ExportBundle.LOOKUPS_FIXED.split("\t")));
        for (LookupColumn c : cols) {
            header.add(c.file);
            full |= c.later != null;
        }
        StringBuilder b = new StringBuilder();
        b.append(ExportBundle.LOOKUPS_FIRST).append('\n');
        b.append("# sample seed ").append(seed).append('\n');
        line(b, header.toArray());
        Object[] f = new Object[6 + cols.size()];
        int n = 0;
        for (int[] l : sample) {
            for (int k = 1; k < l.length; k++, n++) {
                int i = l[k];
                fixed(f, "first", l[0], i, "-");
                for (int c = 0; c < cols.size(); c++) {
                    f[6 + c] = cols.get(c).first[n];
                }
                line(b, f);
            }
        }
        for (int k = 0; full && k < later.size(); k++) {
            int[] x = later.get(k);
            fixed(f, "later", x[0], x[1], x[2]);
            for (int c = 0; c < cols.size(); c++) {
                f[6 + c] = cols.get(c).later == null ? "-" : cols.get(c).later[k];
            }
            line(b, f);
        }
        for (int k = 0; full && k < ExportBundle.SPLIT_LINES; k++) {
            int[] x = ExportBundle.splitLine(k);
            f[0] = "split";
            f[1] = "-";
            f[2] = x[0];
            f[3] = x[1];
            f[4] = x[2];
            f[5] = "-";
            for (int c = 0; c < cols.size(); c++) {
                LookupColumn col = cols.get(c);
                if ((col.later == null) != (col.split == null)) {
                    throw new IllegalStateException(col.file + " has later cells and no split cells, or split and no later");
                }
                f[6 + c] = col.split == null ? "-" : col.split[k];
            }
            line(b, f);
        }
        Files.write(file, b.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void fixed(Object[] f, String word, int state, int deal, Object hit) {
        f[0] = word;
        f[1] = state;
        f[2] = Deals.P1[deal];
        f[3] = Deals.P2[deal];
        f[4] = Deals.UP[deal];
        f[5] = hit;
    }

    // ------------------------------------------------------------------ B5: exact.tsv

    /**
     * B5's sums of one fold of a section, [figure][sumA, sumB, sumAA, sumAB, sumBB], the figures
     * as figures(labels) lists them: for each shoe of the fold, a = sum of w x and b = sum of w
     * over its states, w = 1/q, and the sums and moments of a and b over the fold's shoes. The
     * states of a section come in record order, which is shoe order.
     */
    static double[][] exactSums(ScoringStates st, ExportBundle.Section sec, int fold) {
        int figures = 3 + 2 * sec.labels.length;
        double[][] out = new double[figures][5];
        double[] a = new double[figures];
        double b = 0;
        int shoe = -1;
        for (int e = 0; e <= sec.index.length; e++) {
            int s = e < sec.index.length ? sec.index[e] : -1;
            if (s >= 0 && st.fold(s) != fold) {
                continue;
            }
            if (s < 0 || st.shoe[s] != shoe) {
                if (shoe >= 0) {
                    for (int fi = 0; fi < figures; fi++) {
                        out[fi][0] += a[fi];
                        out[fi][1] += b;
                        out[fi][2] += a[fi] * a[fi];
                        out[fi][3] += a[fi] * b;
                        out[fi][4] += b * b;
                    }
                }
                Arrays.fill(a, 0);
                b = 0;
                shoe = s < 0 ? -1 : st.shoe[s];
            }
            if (s < 0) {
                break;
            }
            double w = st.weight[s];
            double rv = sec.rvPlay[e] + sec.rvInsurance[e];
            for (int fi = 0; fi < figures; fi++) {
                // An EV figure is summed as (w x bet) x RV, the order SystemScorer.scoreOnce sums
                // Entry.shoeSums in, so a shoe's sum is the engine's to the bit when the values are.
                a[fi] += fi >= 3 && (fi - 3) % 2 == 0 ? w * sec.bet[e][(fi - 3) / 2] * rv
                        : w * x(fi, sec.rvPlay[e], sec.rvInsurance[e], sec.bet[e]);
            }
            b += w;
        }
        return out;
    }

    /** exact.tsv: per strategy file, in the order of its sections' rows and penetrations, then fold, each figure's sums (B5). */
    static void writeExact(Path file, ScoringStates st, List<ExportBundle.Section> sections) throws IOException {
        List<ExportBundle.Section> sorted = new ArrayList<>(sections);
        sorted.sort(SECTION_ORDER);
        StringBuilder b = new StringBuilder();
        b.append(ExportBundle.EXACT_FIRST).append('\n');
        b.append(ExportBundle.EXACT_HEADER).append('\n');
        for (ExportBundle.Section sec : sorted) {
            List<String> figures = figures(sec.labels);
            for (int f = 0; f < 2; f++) {
                double[][] sums = exactSums(st, sec, f);
                for (int fi = 0; fi < figures.size(); fi++) {
                    line(b, sec.row, sec.pen, f, figures.get(fi), metric(sec.labels, fi), st.shoesIn(f),
                            real(sums[fi][0]), real(sums[fi][1]), real(sums[fi][2]), real(sums[fi][3]), real(sums[fi][4]));
                }
            }
        }
        Files.write(file, b.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ B0: index.tsv and manifest.sha256

    /**
     * index.tsv, then manifest.sha256, over every file already in the directory: sizes measured on
     * disk, each strategy file's row, penetration, fold and scope from what was written. Returns
     * the bundle's total size in bytes, the two files included.
     */
    static long writeIndexAndManifest(Path dir, List<ExportBundle.Strategy> strategies) throws IOException {
        Map<String, ExportBundle.Strategy> byFile = new TreeMap<>();
        for (ExportBundle.Strategy s : strategies) {
            if (byFile.put(s.fileName(), s) != null) {
                throw new IllegalArgumentException("two strategy files named " + s.fileName()
                        + "; B0 refuses two rows with the same slug");
            }
        }
        List<String> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> list = Files.list(dir)) {
            list.forEach(p -> files.add(p.getFileName().toString()));
        }
        files.remove("index.tsv");
        files.remove("manifest.sha256");
        files.sort(null);
        StringBuilder b = new StringBuilder();
        b.append(ExportBundle.INDEX_HEADER).append('\n');
        for (String f : files) {
            if (!Files.isRegularFile(dir.resolve(f))) {
                throw new IllegalStateException(dir.resolve(f) + " is not a file; a bundle is one directory of files");
            }
            ExportBundle.Strategy s = byFile.get(f);
            if (s == null) {
                line(b, f, Files.size(dir.resolve(f)), "-", "-", "-", "-");
            } else {
                line(b, f, Files.size(dir.resolve(f)), s.row, s.pen, s.fold, s.scope.word());
            }
        }
        Files.write(dir.resolve("index.tsv"), b.toString().getBytes(StandardCharsets.UTF_8));
        files.add("index.tsv");
        files.sort(null);
        StringBuilder m = new StringBuilder();
        long total = 0;
        for (String f : files) {
            m.append(ExportBundle.sha256(dir.resolve(f))).append("  ").append(f).append('\n');
            total += Files.size(dir.resolve(f));
        }
        Files.write(dir.resolve("manifest.sha256"), m.toString().getBytes(StandardCharsets.UTF_8));
        return total + Files.size(dir.resolve("manifest.sha256"));
    }

    // ------------------------------------------------------------------ the round trip

    /** What the report gave for one row at one penetration: the figures the bundle must give back. */
    static final class Expect implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        final String row;
        /** 70, 75 or 80. */
        final int pen;
        /** Entry.estimate[p]: every metric. */
        final double[] estimate;
        /** Entry.shoeSums[p]: per shoe, 1/q x bet x RV at the 1-8 same schedule. */
        final double[] shoeSums;

        Expect(String row, int pen, double[] estimate, double[] shoeSums) {
            this.row = row;
            this.pen = pen;
            this.estimate = estimate;
            this.shoeSums = shoeSums;
        }

        /** The wide plans' labels the entry was scored with, and Entry.wide[p], what wide.ser holds; null when not scored with them. */
        String[] widePlans;
        double[] wide;

        static Expect of(SystemScorer.Entry e, int p) {
            if (e.estimate[p] == null) {
                throw new IllegalArgumentException(e.name + " was not scored at " + SystemScorer.PENETRATIONS[p]);
            }
            Expect x = new Expect(e.name, ExportBundle.PENS[p], e.estimate[p], e.shoeSums[p]);
            if (e.widePlans != null && e.wide != null && e.wide[p] != null) {
                x.widePlans = new String[e.widePlans.length];
                for (int i = 0; i < e.widePlans.length; i++) {
                    x.widePlans[i] = e.widePlans[i].label;
                }
                x.wide = e.wide[p].clone();
            }
            return x;
        }

        /**
         * What figure number fi of a file with these labels must give: Entry.estimate's metric for
         * the report's figures; for a wide plan's ev: and bet:, its EV and average bet per round
         * watched as the entry's wide figures hold them (the same schedule's, or for a plan that
         * leaves the table the own rule's). NaN for a figure neither has; a wide plan exported for
         * an entry not scored with it is refused, since nothing would hold its bets to the engine's.
         */
        double expected(String[] labels, int fi) {
            int m = metric(labels, fi);
            if (m >= 0) {
                return estimate[m];
            }
            String label = labels[(fi - 3) / 2];
            SystemScorer.Plan w = widePlan(label);
            if (w == null) {
                return Double.NaN;
            }
            int i = widePlans == null ? -1 : Arrays.asList(widePlans).indexOf(label);
            if (i < 0) {
                throw new IllegalStateException(row + " at " + pen + "%: " + label + " is exported, and the entry holds no "
                        + "wide figures for it to be held to (the replay's --wide scores them)");
            }
            boolean ev = (fi - 3) % 2 == 0;
            int at = w.sameSchedule() ? (ev ? SystemScorer.W_SAME : SystemScorer.W_SAME_BET)
                    : (ev ? SystemScorer.W_OWN : SystemScorer.W_OWN_BET);
            return wide[SystemScorer.wideAt(i, at)];
        }
    }

    /** The round trip's account: one line per thing compared, and the largest relative difference met. */
    static final class Report {
        final List<String> lines = new ArrayList<>();
        double worst;
        long compared;
        /** The splits a strategy's last round valued, each played again from the files (playedAsValued). */
        long splits;

        /** a and b equal within RELATIVE of the larger, or refused, naming what. */
        void same(double a, double b, String what) {
            same(a, b, 0, what);
        }

        /**
         * a and b equal within RELATIVE of the larger of them and scale, or refused, naming what.
         * A sum taken in another order differs by rounding in proportion to the sizes of its terms,
         * not of its result, so a figure near zero made of large terms of both signs is compared
         * against the mean size of its terms (scale) rather than against itself.
         */
        void same(double a, double b, double scale, String what) {
            compared++;
            if (a == b) {
                return;
            }
            double d = Math.abs(a - b) / Math.max(scale, Math.max(Math.abs(a), Math.abs(b)));
            if (!(d <= RELATIVE)) {
                throw new IllegalStateException("round trip: " + what + " is " + a + " in the bundle and " + b
                        + (d == d ? ", " + d + " apart relative" : ""));
            }
            worst = Math.max(worst, d);
        }

        void equal(long a, long b, String what) {
            compared++;
            if (a != b) {
                throw new IllegalStateException("round trip: " + what + " is " + a + " in the bundle and " + b);
            }
        }

        void require(boolean ok, String what) {
            compared++;
            if (!ok) {
                throw new IllegalStateException("round trip: " + what);
            }
        }
    }

    /** chart.tsv's place of a decision (B1's order, A3's chartDecisions). */
    static int chartIndex(int up, int total, int soft, int pair, int fromSplit, int first, int legal) {
        return ((((((up - 1) * 18 + (total - 4)) * 2 + soft) * 11 + pair) * 2 + fromSplit) * 2 + first) * 16 + legal;
    }

    /** The dealt hand's first move in a deal as chart.tsv answers it (A4: Hit, Double and Split on a pair legal). */
    static int chartFirst(PlayerMove[] chart, int deal) {
        int p1 = Deals.P1[deal];
        int p2 = Deals.P2[deal];
        boolean ace = p1 == 1 || p2 == 1;
        int hard = p1 + p2;
        boolean soft = ace && hard <= 11;
        int pair = p1 == p2 ? p1 : 0;
        int legal = 1 | 2 | (pair != 0 ? 4 : 0);
        return chart[chartIndex(Deals.UP[deal], soft ? hard + 10 : hard, soft ? 1 : 0, pair, 0, 1, legal)].ordinal();
    }

    /**
     * A deal's play-key parts under a strategy file, from its own tags (B2.2): {key, chance}
     * pairs, one with chance 1 for a count that tags every rank alike, none when the cards left
     * cannot make the deal (then only a colour or face count is asked).
     */
    static double[][] parts(ExportBundle.Strategy s, int[][] classes, boolean aware, int[] dealt, int depth, int deal) {
        long rc = ExportBundle.tally(s.tags, dealt);
        if (!aware) {
            int t = classes[Deals.P1[deal]][0] + classes[Deals.P2[deal]][0] + classes[Deals.UP[deal]][0];
            return new double[][]{{ExportBundle.keyAt(s, rc, depth, t, 3), 1}};
        }
        double[][] sums = ExportBundle.tagSums(s.tags, classes, dealt, Deals.P1[deal], Deals.P2[deal], Deals.UP[deal]);
        for (double[] e : sums) {
            e[0] = ExportBundle.keyAt(s, rc, depth, (int) e[0], 3);
        }
        return sums;
    }

    /**
     * A row's section worked out again from its two strategy files alone, on the engine's stored
     * values: each state of the section is played by its own fold's file. Its bet key comes from
     * the file's bet tags by B2.2; its round value from the file's first moves in the groups its
     * play-key parts fall in (or, betting only, from chart.tsv's first moves), each move's value
     * the stored one (ScoringStates.move), plus the naturals; its insurance from the file's insure
     * lines; its bet from the file's bet lines at its key, which must be listed. Then every field is
     * held to states.bin's.
     */
    static ExportBundle.Section replay(ScoringStates st, PlayerMove[] chart, ExportBundle.Strategy[] files,
                                       ExportBundle.Section bin, Report r) {
        return replay(st, chart, files, bin, r, null, null, null);
    }

    /**
     * Where a full-play row's Hit and Split values come from in the round trip: the source's two
     * fold strategies' values (values, with the parts they are indexed by), so every state's round
     * value is worked out again from the files' moves; or, once those are gone, the section each
     * item checked so when it held them (verified), which states.bin's must equal to the bit.
     */
    static final class FullSource {
        final Map<String, Refit> values = new TreeMap<>();
        final Map<String, ExportBundle.Section> verified = new TreeMap<>();
        /**
         * Every round start of the state file's shoes, dealt again (AllStates), for a plan that
         * leaves the table: its bets need each state's lowest bet key of its shoe so far, which
         * the valued states alone do not give; null when no plan exported leaves.
         */
        AllStates dealt;

        static String key(String row, int pen) {
            return row + "\t" + pen;
        }
    }

    /**
     * The same for any scope: a full-play file's first moves are valued from its fold's Hit and
     * Split values when rf holds them (each part's key held to the engine's part key), its later
     * lines, groups, first lines and the rankings its split hands read held to rf's, and every
     * split the strategy's last round valued played again from the file (playedAsValued);
     * otherwise its round values are taken from verified, which states.bin's section must equal
     * to the bit. Its insurance and bets are worked out from the file either way.
     */
    static ExportBundle.Section replay(ScoringStates st, PlayerMove[] chart, ExportBundle.Strategy[] files,
                                       ExportBundle.Section bin, Report r, Refit rf, ExportBundle.Section verified,
                                       AllStates starts) {
        String where = bin.row + " at " + bin.pen + "%";
        // A plan that leaves the table bets 0 at a state whose shoe's lowest bet key so far is below
        // its exit: that key is worked out here from the file's own bet tags (B2.2) over every round
        // start of the shoe, dealt again, apart from the engine's CountKeys.
        int[][] lowest = new int[2][];
        for (int f = 0; f < 2; f++) {
            for (ExportBundle.Plan plan : files[f].plans) {
                if (plan.exit != null && lowest[f] == null) {
                    r.require(starts != null, where + ": " + plan.label + " leaves the table, and no round starts were "
                            + "dealt again to find each state's lowest bet key so far");
                    lowest[f] = lowestSoFar(starts, st, files[f]);
                }
            }
        }
        boolean full = files[0].scope == ExportBundle.Scope.FULL;
        r.require(files[1].scope == files[0].scope, where + ": its two files are of scopes " + files[0].scope + " and "
                + files[1].scope);
        r.require(!full || rf != null && rf.full() || verified != null, where + " plays full and neither its values nor "
                + "a section checked with them is given");
        if (full && rf == null) {
            sameSection(bin, verified, where, r);
        }
        if (full && rf != null) {
            // The Hit values were worked out under the later play the last round walked; the files'
            // later lines must be that play, cell by cell, or the simulator plays another.
            for (int f = 0; f < 2; f++) {
                r.equal(files[f].laterLo.length, rf.later[f].count(), where + ", fold " + f + ": later groups");
                for (int l = 0; l < files[f].laterLo.length; l++) {
                    r.require(files[f].laterLo[l] == rf.later[f].lo[l] && files[f].laterHi[l] == rf.later[f].hi[l],
                            where + ", fold " + f + ": later group " + l + "'s bounds");
                    for (int c = 0; c < FullIndexFit.CELLS; c++) {
                        r.require(files[f].stands[l][c] == rf.stands[f][l * FullIndexFit.CELLS + c], where + ", fold " + f
                                + ": later cell " + l + " " + c + " is not the play the strategy's last round walked");
                    }
                }
            }
            // The Split values were worked out with split hands playing the walked play's ranking in
            // the Strategy's groups, which Refit holds to the refit's; so the file's groups must be
            // the refit's too (and its rankings, below the states, held to both).
            for (int f = 0; f < 2; f++) {
                r.require(Arrays.equals(files[f].lo, rf.plays[f].groups.lo)
                        && Arrays.equals(files[f].hi, rf.plays[f].groups.hi), where + ", fold " + f
                        + ": its groups are not the refit's");
            }
        }
        int[][][] classes = new int[2][][];
        boolean[] aware = new boolean[2];
        for (int f = 0; f < 2; f++) {
            ExportBundle.Strategy s = files[f];
            r.require(s.row.equals(bin.row) && s.pen == bin.pen && s.cut == bin.cut && s.fold == f,
                    where + ": the fold " + f + " file is " + s.row + " at " + s.pen + "%, fold " + s.fold);
            r.require(s.plans.size() == bin.labels.length, where + ": fold " + f + "'s plans are not states.bin's");
            for (int l = 0; l < bin.labels.length; l++) {
                r.require(s.plans.get(l).label.equals(bin.labels[l]), where + ": plan " + l + " of fold " + f
                        + " is " + s.plans.get(l).label + ", states.bin's " + bin.labels[l]);
            }
            classes[f] = ExportBundle.classes(s.tags);
            aware[f] = ExportBundle.kindAware(s.tags);
        }
        int m = bin.index.length;
        ExportBundle.Section out = new ExportBundle.Section();
        out.row = bin.row;
        out.pen = bin.pen;
        out.cut = bin.cut;
        out.labels = bin.labels;
        out.index = bin.index.clone();
        out.betKey = new int[m];
        out.rvPlay = new double[m];
        out.rvInsurance = new double[m];
        out.bet = new double[m][bin.labels.length];
        double[] trained = new double[2];
        List<java.util.Set<Integer>> testKeys = Arrays.asList(new java.util.TreeSet<>(), new java.util.TreeSet<>());
        for (int e = 0; e < m; e++) {
            int s = bin.index[e];
            int f = st.fold(s);
            ExportBundle.Strategy file = files[f];
            trained[1 - f] += st.weight[s];
            int[] dealt = st.dealt[s];
            int depth = st.depth[s];
            out.betKey[e] = ExportBundle.betKey(file, dealt, depth);
            testKeys.get(f).add(out.betKey[e]);
            double[] p = st.chance[s];
            double[] mv = st.move[s];
            double play = 0;
            double insurance = 0;
            if (file.scope == ExportBundle.Scope.BETTING) {
                for (int i = 0; i < Deals.COUNT; i++) {
                    if (p[i] != 0 && !Deals.playerNatural(i)) {
                        play += mv[i * MOVES + chartFirst(chart, i)];
                    }
                }
                play += st.naturals[s];
            } else {
                play = st.naturals[s];
            }
            boolean valued = full && rf != null;
            if (valued) {
                r.require(rf.pen.states[e] == s, where + ": entry " + e + " is not state " + rf.pen.states[e]);
            }
            for (int i = 0; i < Deals.COUNT; i++) {
                boolean firstMove = file.scope != ExportBundle.Scope.BETTING && !Deals.playerNatural(i);
                if (p[i] == 0 || !firstMove && i >= ACE_UP) {
                    continue;
                }
                double[][] parts = parts(file, classes[f], aware[f], dealt, depth, i);
                int x0 = valued ? rf.parts.from(e * Deals.COUNT + i) : 0;
                if (valued) {
                    r.equal(rf.parts.to(e * Deals.COUNT + i) - x0, parts.length, where + ": state " + s + " deal " + i
                            + "'s parts");
                }
                for (int y = 0; y < parts.length; y++) {
                    double[] part = parts[y];
                    int g = ExportBundle.groupOf(file.hi, (int) part[0]);
                    if (firstMove && (!full || valued)) {
                        // The dealt hand's first move (B2.4), never a split line's.
                        int move = file.dealtFirst(g, i).ordinal();
                        double v = mv[i * MOVES + move];
                        if (valued) {
                            r.equal(rf.parts.key[x0 + y], (long) part[0], where + ": state " + s + " deal " + i + " part "
                                    + y + "'s key");
                            v = SystemScorer.value(rf.values[f], mv, i, x0 + y, move);
                        }
                        r.require(!Double.isNaN(v), where + ": state " + s + " deal " + i + " is played "
                                + file.dealtFirst(g, i) + ", which the deal does not allow");
                        play += part[1] * v;
                    }
                    if (i < ACE_UP && file.insure[g][i]) {
                        insurance += part[1] * st.insurance[s][i];
                    }
                }
            }
            out.rvPlay[e] = full && !valued ? bin.rvPlay[e] : play;
            out.rvInsurance[e] = insurance;
            for (int l = 0; l < bin.labels.length; l++) {
                ExportBundle.Plan plan = file.plans.get(l);
                int at = Arrays.binarySearch(plan.key, out.betKey[e]);
                r.require(at >= 0, where + ": state " + s + "'s bet key " + out.betKey[e] + " has no bet line in "
                        + plan.label + " of fold " + f + ", whose test states it is one of");
                boolean left = plan.exit != null && lowest[f][s] < plan.exit;
                out.bet[e][l] = left ? 0 : plan.units[at] * plan.play[at];
            }
            r.equal(bin.betKey[e], out.betKey[e], where + ": state " + s + "'s bet key");
            r.same(bin.rvPlay[e], out.rvPlay[e], where + ": state " + s + "'s round value under its fold's play");
            r.same(bin.rvInsurance[e], out.rvInsurance[e], where + ": state " + s + "'s insurance value");
            for (int l = 0; l < bin.labels.length; l++) {
                r.same(bin.bet[e][l], out.bet[e][l], where + ": state " + s + "'s bet under " + bin.labels[l]);
            }
        }
        if (full && rf != null) {
            // The rankings no state's round value reads: every first line, whole, against the
            // refit's, whose tail no hand reads where a split line stands; and every ranking split
            // hands read (B2.4, the split line, else the first line) against the walked play's.
            // Then every split the last round valued, played again from the files alone and held
            // to the fingerprint the strategy kept (playedAsValued): that holds the split hands to
            // what the Split values were worked out under, not to walked, which is Refit's own.
            for (int f = 0; f < 2; f++) {
                ExportBundle.Strategy s = files[f];
                for (int g = 0; g < s.lo.length; g++) {
                    for (int i = 0; i < Deals.COUNT; i++) {
                        if (Deals.playerNatural(i)) {
                            continue;
                        }
                        boolean same = Arrays.equals(s.first[g][i], rf.ranking[f][g][i]);
                        r.require(same, same ? null : where + ", fold " + f + ": " + cell(g, i) + "'s first line is "
                                + words(s.first[g][i]) + ", the refit ranks it " + words(rf.ranking[f][g][i]));
                        same = Arrays.equals(s.splitRanking(g, i), rf.walked[f][g][i]);
                        r.require(same, same ? null : where + ", fold " + f + ": " + cell(g, i) + "'s split hands read "
                                + words(s.splitRanking(g, i)) + ", and were valued under the walked play's "
                                + words(rf.walked[f][g][i]));
                    }
                }
            }
            for (int f = 0; f < 2; f++) {
                playedAsValued(st.rules, files[f], rf.valued.get(f), where + ", fold " + f, r);
            }
        }
        for (int f = 0; f < 2; f++) {
            r.same(files[f].trained, trained[f], where + ": fold " + f + "'s training weight");
            for (ExportBundle.Plan plan : files[f].plans) {
                java.util.Set<Integer> listed = new java.util.TreeSet<>();
                for (int k : plan.key) {
                    listed.add(k);
                }
                r.require(listed.equals(testKeys.get(f)), where + ": " + plan.label + " of fold " + f
                        + " lists bets at keys " + listed + ", its test states hold " + testKeys.get(f));
            }
        }
        return out;
    }

    /**
     * Each split a strategy's last round valued (FullIndexFit.valuedSplits) played again
     * from its full-play file alone: the SplitPlay B2.4 describes for the pair deal in group g and
     * later group l (filePlay), whose SplitValues fingerprint must be the one the strategy kept,
     * answer for answer. A Split value is worked out from its play's fingerprint (equal prints,
     * equal values), so the file's split hands then play every split as it was valued. Neither
     * Refit.walked nor the refit is read. Refused where the strategy valued no split, which would
     * leave nothing to hold.
     */
    static void playedAsValued(RoundRules rules, ExportBundle.Strategy file, List<FullIndexFit.ValuedSplit> valued,
                               String where, Report r) {
        r.require(valued != null && !valued.isEmpty(), where + ": the strategy's last round valued no split");
        for (FullIndexFit.ValuedSplit v : valued) {
            String split = where + ": a split of " + Deals.P1[v.deal] + "s against " + Deals.UP[v.deal] + " in group "
                    + v.group + ", later group " + v.later;
            r.require(v.group < file.lo.length && v.later >= 0 && v.later < file.laterLo.length, split
                    + ", which the file's groups do not have");
            byte[] print = SplitValues.fingerprint(rules, v.deal, filePlay(file, v.deal, v.group, v.later));
            int at = Arrays.mismatch(print, v.print);
            r.require(at < 0, at < 0 ? null : split + ": its split hands play " + answer(print, at) + " by the file at "
                    + SplitValues.slotWords(at) + ", and were valued playing " + answer(v.print, at));
            r.splits++;
        }
    }

    /** A fingerprint's answer in a refusal: the move's letter, or none where the decision is not reached. */
    private static String answer(byte[] print, int at) {
        return print[at] == 0 ? "nothing" : String.valueOf(ExportBundle.letter(PlayerMove.values()[print[at] - 1]));
    }

    /**
     * B2.4's split hands of a full-play file for pair deal i, the deal's key in group g and later
     * group l, read from the file alone: each two-card decision from splitRanking(g, ·), each
     * later decision from `later l` at a contested spot and the chart elsewhere, as the engine's
     * later play falls back on it (FullIndexFit.Strategy.stands).
     */
    static SplitPlay filePlay(ExportBundle.Strategy file, int i, int g, int l) {
        int up = Deals.UP[i];
        boolean[] stands = file.stands[l];
        return new SplitPlay(Deals.P1[i], up, (total, soft) -> {
            int c = total >= 0 && total < 22 ? LATER_CELL[up - 1][soft ? 1 : 0][total] : -1;
            return c < 0 ? PlayerTree.CHART.stands(total, soft, up, 0) : stands[c];
        }, d -> file.splitRanking(g, d));
    }

    /** ExportBundle.cells()'s place of a contested spot, [up - 1][soft][total]; -1 at every other spot. */
    private static final int[][][] LATER_CELL = laterCells();

    private static int[][][] laterCells() {
        int[][][] out = new int[10][2][22];
        for (int[][] u : out) {
            for (int[] s : u) {
                Arrays.fill(s, -1);
            }
        }
        int[][] cells = ExportBundle.cells();
        for (int c = 0; c < cells.length; c++) {
            out[cells[c][0] - 1][cells[c][2]][cells[c][1]] = c;
        }
        return out;
    }

    /**
     * For each valued state of st, the lowest bet key a strategy file's bet count gives (B2.2, from
     * the file's bet tags by ExportBundle.betKey) over the round starts of its shoe up to it, it
     * included, the shoes dealt again in dealt: B2.5's test of whether a plan with an exit is
     * still seated.
     */
    static int[] lowestSoFar(AllStates dealt, ScoringStates st, ExportBundle.Strategy file) {
        if (dealt.valuedAt.length != st.size) {
            throw new IllegalArgumentException(st.size + " states, " + dealt.valuedAt.length + " found when dealt again");
        }
        int[] out = new int[st.size];
        int record = 0;
        int lowest = Integer.MAX_VALUE;
        for (int s = 0; s < dealt.size && record < out.length; s++) {
            if (dealt.depth[s] == 0) {
                lowest = Integer.MAX_VALUE;
            }
            lowest = Math.min(lowest, ExportBundle.betKey(file, dealt.dealt(s), dealt.depth[s]));
            if (dealt.valuedAt[record] == s) {
                out[record++] = lowest;
            }
        }
        if (record != out.length) {
            throw new IllegalStateException("only " + record + " of " + out.length + " valued states found dealt again");
        }
        return out;
    }

    /** A section equal to the one checked when it was made, field by field and to the bit. */
    static void sameSection(ExportBundle.Section bin, ExportBundle.Section x, String where, Report r) {
        r.require(x.row.equals(bin.row) && x.pen == bin.pen && x.cut == bin.cut && Arrays.equals(x.labels, bin.labels)
                && Arrays.equals(x.index, bin.index) && Arrays.equals(x.betKey, bin.betKey)
                && Arrays.equals(x.rvPlay, bin.rvPlay) && Arrays.equals(x.rvInsurance, bin.rvInsurance)
                && Arrays.deepEquals(x.bet, bin.bet), where + ": states.bin's section is not the one checked against "
                + "the strategies' values when it was made");
    }

    /**
     * The round trip over a whole bundle: its manifest and index against the files, chart.tsv
     * against the chart, states.bin's records against the state file's, every strategy file
     * replayed against its section (replay), exact.tsv against the sums the replayed values give,
     * and every exported row's figures, pooled over the folds, against the report's. keyCounts are
     * the counts keys-sample.tsv was written for. Returns the account; throws, naming the first
     * difference, if anything differs.
     */
    static Report roundTrip(Path dir, ScoringStates st, StateStore.Header h, List<Expect> expected,
                            List<CountKeys.Count> keyCounts) throws IOException {
        return roundTrip(dir, st, h, expected, keyCounts, new FullSource());
    }

    /** The same, full-play rows valued from full (FullSource). */
    static Report roundTrip(Path dir, ScoringStates st, StateStore.Header h, List<Expect> expected,
                            List<CountKeys.Count> keyCounts, FullSource full) throws IOException {
        Report r = new Report();
        Map<String, String> manifest = ExportBundle.readManifest(dir.resolve("manifest.sha256"));
        Map<String, String[]> index = ExportBundle.readIndex(dir.resolve("index.tsv"));
        List<String> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> list = Files.list(dir)) {
            list.forEach(p -> files.add(p.getFileName().toString()));
        }
        files.remove("manifest.sha256");
        files.sort(null);
        r.require(new ArrayList<>(manifest.keySet()).equals(files), "manifest.sha256 lists " + manifest.keySet()
                + ", the directory holds " + files);
        for (String f : files) {
            r.require(manifest.get(f).equals(ExportBundle.sha256(dir.resolve(f))), f + "'s SHA-256 is not the manifest's");
        }
        files.remove("index.tsv");
        r.require(new ArrayList<>(index.keySet()).equals(files), "index.tsv lists " + index.keySet());
        for (String f : files) {
            r.equal(Long.parseLong(index.get(f)[0]), Files.size(dir.resolve(f)), f + "'s size in index.tsv");
        }
        r.lines.add("manifest.sha256 and index.tsv: " + files.size() + " files, every hash and size as listed");

        PlayerMove[] chart = ExportBundle.readChart(dir.resolve("chart.tsv"));
        int[] moves = new int[chart.length];
        for (int n = 0; n < chart.length; n++) {
            moves[n] = chart[n].ordinal();
        }
        r.require(ExportBundle.fingerprint(moves).equals(BasicStrategy.fingerprint(BasicStrategy.chart())),
                "chart.tsv is not the chart the states were dealt with");
        r.lines.add("chart.tsv: " + chart.length + " decisions, fingerprint " + ExportBundle.fingerprint(moves));

        ExportBundle.States bin = ExportBundle.readStates(dir.resolve("states.bin"));
        r.equal(bin.seed, h.seed, "states.bin's seed");
        r.equal(Double.doubleToRawLongBits(bin.q0), Double.doubleToRawLongBits(h.q0), "states.bin's q0");
        r.equal(Double.doubleToRawLongBits(bin.u0), Double.doubleToRawLongBits(h.u0), "states.bin's u0");
        r.equal(bin.cut, h.cut, "states.bin's cut");
        r.equal(bin.shoes, st.shoes, "states.bin's shoes");
        r.equal(bin.size(), st.size, "states.bin's states");
        for (int s = 0; s < st.size; s++) {
            r.require(bin.shoe[s] == st.shoe[s] && bin.round[s] == st.round[s] && bin.depth[s] == st.depth[s]
                    && Double.doubleToRawLongBits(bin.q[s]) == Double.doubleToRawLongBits(st.q[s])
                    && Arrays.equals(bin.dealt[s], st.dealt[s]), "states.bin's record " + s + " is not the state file's");
        }
        r.lines.add("states.bin: " + st.size + " records equal to the state file's, q to the bit");

        Map<String, ExportBundle.Strategy[]> strategies = new TreeMap<>();
        Map<String, ExportBundle.Strategy> byFile = new TreeMap<>();
        for (String f : files) {
            String[] x = index.get(f);
            if (x[1].equals("-")) {
                continue;
            }
            ExportBundle.Strategy s = ExportBundle.readStrategy(dir.resolve(f));
            r.require(f.equals(s.fileName()) && x[1].equals(s.row) && x[2].equals(Integer.toString(s.pen))
                    && x[3].equals(Integer.toString(s.fold)) && x[4].equals(s.scope.word()),
                    f + " is not what index.tsv says it is");
            strategies.computeIfAbsent(s.row + "\t" + s.pen, k -> new ExportBundle.Strategy[2])[s.fold] = s;
            byFile.put(f, s);
        }
        Map<String, List<ExportBundle.Exact>> exact = new TreeMap<>();
        for (ExportBundle.Exact e : ExportBundle.readExact(dir.resolve("exact.tsv"))) {
            exact.computeIfAbsent(e.row + "\t" + e.pen + "\t" + e.fold, k -> new ArrayList<>()).add(e);
        }
        r.equal(bin.sections.size(), expected.size(), "the sections of states.bin, against the rows expected,");
        int chartRows = 0;
        for (Expect x : expected) {
            chartRows += x.row.equals(CHART_ROW) ? 1 : 0;
        }
        r.equal(strategies.size(), expected.size() - chartRows, "the rows with strategy files, against the rows expected,");
        r.equal(exact.size(), 2L * expected.size(), "the files exact.tsv has lines for, against the rows expected,");
        for (Expect x : expected) {
            if (x.row.equals(CHART_ROW)) {
                chartFigures(st, chart, bin, strategies, exact, x, r);
            } else {
                figures(st, chart, bin, strategies, exact, x, r, full);
            }
        }
        if (keyCounts != null) {
            keys(dir.resolve("keys-sample.tsv"), st, keyCounts, r);
        }
        lookups(dir.resolve("lookups.tsv"), st, chart, byFile, r);
        r.lines.add("the round trip compared " + r.compared + " values" + (r.splits == 0 ? ""
                : " and played again from the files " + r.splits + " splits the last round valued")
                + "; largest relative difference " + r.worst);
        return r;
    }

    /**
     * One row at one penetration: its files replayed against states.bin, exact.tsv's sums against
     * the replayed values', the pooled figures against Entry.estimate, and the per-shoe sums at the
     * 1-8 same schedule against Entry.shoeSums.
     */
    static void figures(ScoringStates st, PlayerMove[] chart, ExportBundle.States bin,
                        Map<String, ExportBundle.Strategy[]> strategies, Map<String, List<ExportBundle.Exact>> exact,
                        Expect x, Report r, FullSource full) {
        String where = x.row + " at " + x.pen + "%";
        ExportBundle.Section sec = bin.section(x.row, x.pen);
        r.require(sec != null, where + " has no section in states.bin");
        ExportBundle.Strategy[] files = strategies.get(x.row + "\t" + x.pen);
        r.require(files != null && files[0] != null && files[1] != null, where + " lacks a fold's strategy file");
        String key = FullSource.key(x.row, x.pen);
        ExportBundle.Section again = replay(st, chart, files, sec, r, full.values.get(key), full.verified.get(key),
                full.dealt);
        List<String> figures = figures(sec.labels);
        double[] pooledA = new double[figures.size()];
        double[] pooledB = new double[figures.size()];
        for (int f = 0; f < 2; f++) {
            double[][] sums = b5Sums(st, again, f);
            List<ExportBundle.Exact> lines = exact.get(x.row + "\t" + x.pen + "\t" + f);
            r.require(lines != null && lines.size() == figures.size(), where + ", fold " + f + ": exact.tsv has "
                    + (lines == null ? 0 : lines.size()) + " lines, not " + figures.size());
            for (int fi = 0; fi < figures.size(); fi++) {
                ExportBundle.Exact e = lines.get(fi);
                String what = where + ", fold " + f + ", " + figures.get(fi);
                r.require(e.figure.equals(figures.get(fi)) && e.metric == metric(sec.labels, fi),
                        what + ": exact.tsv's line is " + e.figure + " metric " + e.metric);
                r.equal(e.shoes, st.shoesIn(f), what + ": exact.tsv's shoes");
                r.same(e.sumA, sums[fi][0], what + ": sumA");
                r.same(e.sumB, sums[fi][1], what + ": sumB");
                r.same(e.sumAA, sums[fi][2], what + ": sumAA");
                r.same(e.sumAB, sums[fi][3], what + ": sumAB");
                r.same(e.sumBB, sums[fi][4], what + ": sumBB");
                pooledA[fi] += e.sumA;
                pooledB[fi] += e.sumB;
            }
        }
        // Each figure twice against the report: summed as SystemScorer.scoreOnce sums it (by fold,
        // then bet key), which should give Entry.estimate to the bit or nearly; and pooled from
        // exact.tsv's per-shoe sums, as the simulator will pool them, whose order differs, so it
        // is held to the mean size of the figure's terms (Report.same).
        double[] engine = engineOrder(st, again, files);
        double[] scale = termScale(st, again);
        int checked = 0;
        int wideChecked = 0;
        for (int fi = 0; fi < figures.size(); fi++) {
            if (held(sec.labels, fi)) {
                double want = x.expected(sec.labels, fi);
                boolean wide = metric(sec.labels, fi) < 0;
                String against = wide ? "the entry's wide figure (wide.ser's)" : "the report's estimate";
                r.same(engine[fi], want, where + ": " + figures.get(fi) + " summed in the engine's order, against "
                        + against);
                r.same(pooledA[fi] / pooledB[fi], want, scale[fi], where + ": " + figures.get(fi)
                        + " pooled over the folds from exact.tsv, against " + against);
                checked++;
                wideChecked += wide ? 1 : 0;
            }
        }
        int same8 = Arrays.asList(sec.labels).indexOf("same-" + SPREADS);
        String shoes = "per-shoe sums not compared (no same-" + SPREADS + " plan exported)";
        if (same8 >= 0 && x.shoeSums != null) {
            double[] shoe = new double[st.shoes];
            for (int e = 0; e < again.index.length; e++) {
                int s = again.index[e];
                shoe[st.shoe[s]] += st.weight[s] * again.bet[e][same8] * (again.rvPlay[e] + again.rvInsurance[e]);
            }
            r.equal(x.shoeSums.length, st.shoes, where + ": the report's per-shoe sums");
            for (int i = 0; i < st.shoes; i++) {
                r.same(shoe[i], x.shoeSums[i], where + ": shoe " + i + "'s sum at same-" + SPREADS);
            }
            shoes = st.shoes + " per-shoe sums equal Entry.shoeSums";
        }
        String how = files[0].scope != ExportBundle.Scope.FULL ? "" : full.values.containsKey(key)
                ? " (full play, on its strategies' Hit and Split values)"
                : " (full play: round values as checked on its strategies' values when made)";
        r.lines.add(where + ": " + sec.index.length + " states replayed from the strategy files equal states.bin" + how
                + "; " + (checked - wideChecked) + " figures pooled from exact.tsv equal Entry.estimate"
                + (wideChecked == 0 ? "" : ", " + wideChecked + " of the wide plans equal Entry.wide") + "; " + shoes);
    }

    /** Whether figure fi of a file with these labels is held to the engine: a report metric, or a wide plan's ev: or bet:. */
    static boolean held(String[] labels, int fi) {
        return metric(labels, fi) >= 0 || fi >= 3 && widePlan(labels[(fi - 3) / 2]) != null;
    }

    /**
     * The chart's row at one penetration (B3, B5): no strategy file; its section's every state
     * valued again from chart.tsv's first moves on the engine's stored values, bet key 0 and no
     * insurance; exact.tsv's three figures (flat, play, insurance) for both folds against the
     * section; and the pooled flat figure against the reference row's estimate, summed as
     * SystemScorer.references sums it (every state in record order) and pooled from exact.tsv.
     */
    static void chartFigures(ScoringStates st, PlayerMove[] chart, ExportBundle.States bin,
                             Map<String, ExportBundle.Strategy[]> strategies, Map<String, List<ExportBundle.Exact>> exact,
                             Expect x, Report r) {
        String where = x.row + " at " + x.pen + "%";
        ExportBundle.Section sec = bin.section(x.row, x.pen);
        r.require(sec != null, where + " has no section in states.bin");
        r.require(!strategies.containsKey(x.row + "\t" + x.pen), where + " has a strategy file; the chart's row has none");
        r.require(sec.labels.length == 0, where + " has plans; the chart's row has none");
        r.equal(sec.index.length, st.size == 0 ? 0 : countBelow(st, sec.cut), where + ": the states below the cut");
        double num = 0;
        double den = 0;
        for (int e = 0; e < sec.index.length; e++) {
            int s = sec.index[e];
            r.require(st.depth[s] < sec.cut && (e == 0 || s > sec.index[e - 1]), where + ": entry " + e + " is not the next state below the cut");
            double[] p = st.chance[s];
            double[] mv = st.move[s];
            // Summed as ScoringStates sums the chart's value: the deals in order, then the naturals.
            double v = 0;
            for (int i = 0; i < Deals.COUNT; i++) {
                if (p[i] != 0 && !Deals.playerNatural(i)) {
                    v += mv[i * MOVES + chartFirst(chart, i)];
                }
            }
            v += st.naturals[s];
            r.equal(sec.betKey[e], 0, where + ": state " + s + "'s bet key");
            r.require(sec.rvInsurance[e] == 0, where + ": state " + s + " has insurance; the chart's row has none");
            r.same(sec.rvPlay[e], v, where + ": state " + s + "'s round value under chart.tsv");
            num += st.weight[s] * sec.rvPlay[e];
            den += st.weight[s];
        }
        List<String> figures = figures(new String[0]);
        double[] pooledA = new double[figures.size()];
        double[] pooledB = new double[figures.size()];
        for (int f = 0; f < 2; f++) {
            double[][] sums = b5Sums(st, sec, f);
            List<ExportBundle.Exact> lines = exact.get(x.row + "\t" + x.pen + "\t" + f);
            r.require(lines != null && lines.size() == figures.size(), where + ", fold " + f + ": exact.tsv has "
                    + (lines == null ? 0 : lines.size()) + " lines, not " + figures.size());
            for (int fi = 0; fi < figures.size(); fi++) {
                ExportBundle.Exact e = lines.get(fi);
                String what = where + ", fold " + f + ", " + figures.get(fi);
                r.require(e.figure.equals(figures.get(fi)) && e.metric == fi, what + ": exact.tsv's line is " + e.figure);
                r.equal(e.shoes, st.shoesIn(f), what + ": exact.tsv's shoes");
                r.same(e.sumA, sums[fi][0], what + ": sumA");
                r.same(e.sumB, sums[fi][1], what + ": sumB");
                r.same(e.sumAA, sums[fi][2], what + ": sumAA");
                r.same(e.sumAB, sums[fi][3], what + ": sumAB");
                r.same(e.sumBB, sums[fi][4], what + ": sumBB");
                pooledA[fi] += e.sumA;
                pooledB[fi] += e.sumB;
            }
        }
        double[] scale = termScale(st, sec);
        r.same(num / den, x.estimate[SystemScorer.FLAT], where + ": flat summed as the reference rows sum it");
        r.same(num / den, x.estimate[SystemScorer.PLAY], where + ": play summed as the reference rows sum it");
        r.require(x.estimate[SystemScorer.INSURANCE] == 0 && pooledA[2] == 0, where + ": insurance is not 0");
        for (int fi = 0; fi < 2; fi++) {
            r.same(pooledA[fi] / pooledB[fi], x.estimate[fi], scale[fi], where + ": " + figures.get(fi)
                    + " pooled over the folds from exact.tsv, against the reference row");
        }
        r.lines.add(where + ": " + sec.index.length + " states valued from chart.tsv equal states.bin; flat and play "
                + "pooled from exact.tsv equal the reference row's estimate");
    }

    /** The valued states dealt below a cut. */
    static int countBelow(ScoringStates st, int cut) {
        int n = 0;
        for (int s = 0; s < st.size; s++) {
            n += st.depth[s] < cut ? 1 : 0;
        }
        return n;
    }

    /**
     * B5's sums of one fold, worked out again from B5's words for the round trip, apart from
     * exactSums, which wrote exact.tsv: a and b per shoe first, then the sums and moments over the
     * fold's shoes in shoe order. Kept apart so that a mistake in the writer's sums is not made
     * again here and passed; an EV figure's terms are (w x bet) x RV, as Entry.shoeSums sums them.
     */
    static double[][] b5Sums(ScoringStates st, ExportBundle.Section sec, int fold) {
        int figures = 3 + 2 * sec.labels.length;
        double[][] a = new double[figures][st.shoes];
        double[] b = new double[st.shoes];
        for (int e = 0; e < sec.index.length; e++) {
            int s = sec.index[e];
            if ((st.shoe[s] & 1) != fold) {
                continue;
            }
            int i = st.shoe[s];
            double w = 1 / st.q[s];
            double rv = sec.rvPlay[e] + sec.rvInsurance[e];
            b[i] += w;
            a[0][i] += w * rv;
            a[1][i] += w * sec.rvPlay[e];
            a[2][i] += w * sec.rvInsurance[e];
            for (int l = 0; l < sec.labels.length; l++) {
                a[3 + 2 * l][i] += w * sec.bet[e][l] * rv;
                a[4 + 2 * l][i] += w * sec.bet[e][l];
            }
        }
        double[][] out = new double[figures][5];
        for (int fi = 0; fi < figures; fi++) {
            for (int i = fold; i < st.shoes; i += 2) {
                out[fi][0] += a[fi][i];
                out[fi][1] += b[i];
                out[fi][2] += a[fi][i] * a[fi][i];
                out[fi][3] += a[fi][i] * b[i];
                out[fi][4] += b[i] * b[i];
            }
        }
        return out;
    }

    /**
     * A row's figures from a section's values, summed in SystemScorer.scoreOnce's order: fold 0,
     * then fold 1; within a fold, PLAY and INSURANCE state by state, and FLAT, the bets' EV and
     * their average bet key by key, upward, each key's sums taken over its states in record order
     * and multiplied by the key's bet from the fold's file (SystemScorer.bets). Then each over the
     * pooled weight. In figures(labels)'s order.
     */
    static double[] engineOrder(ScoringStates st, ExportBundle.Section sec, ExportBundle.Strategy[] files) {
        int plans = sec.labels.length;
        double[] sum = new double[3 + 2 * plans];
        double weight = 0;
        for (int f = 0; f < 2; f++) {
            java.util.TreeMap<Integer, double[]> byKey = new java.util.TreeMap<>();
            for (int e = 0; e < sec.index.length; e++) {
                int s = sec.index[e];
                if (st.fold(s) != f) {
                    continue;
                }
                double w = st.weight[s];
                sum[1] += w * sec.rvPlay[e];
                sum[2] += w * sec.rvInsurance[e];
                double[] k = byKey.computeIfAbsent(sec.betKey[e], x -> new double[2]);
                k[0] += w;
                k[1] += w * (sec.rvPlay[e] + sec.rvInsurance[e]);
            }
            double vw = 0;
            double flat = 0;
            for (double[] k : byKey.values()) {
                vw += k[0];
                flat += k[1];
            }
            weight += vw;
            sum[0] += flat;
            for (int l = 0; l < plans; l++) {
                ExportBundle.Plan plan = files[f].plans.get(l);
                if (plan.exit != null) {
                    // A plan that leaves the table: SystemScorer.Wide sums its own rule state by
                    // state, in the fold's order, each at its bet while seated and 0 once it has left.
                    for (int e = 0; e < sec.index.length; e++) {
                        int s = sec.index[e];
                        if (st.fold(s) == f) {
                            double w = st.weight[s];
                            sum[3 + 2 * l] += sec.bet[e][l] * w * (sec.rvPlay[e] + sec.rvInsurance[e]);
                            sum[4 + 2 * l] += sec.bet[e][l] * w;
                        }
                    }
                    continue;
                }
                for (Map.Entry<Integer, double[]> k : byKey.entrySet()) {
                    double bet = plan.bet(k.getKey());
                    sum[3 + 2 * l] += bet * k.getValue()[1];
                    sum[4 + 2 * l] += bet * k.getValue()[0];
                }
            }
        }
        for (int fi = 0; fi < sum.length; fi++) {
            sum[fi] /= weight;
        }
        return sum;
    }

    /** Each figure's mean term size over both folds, sum of |w x| over sum of w: the scale its rounding goes by. */
    static double[] termScale(ScoringStates st, ExportBundle.Section sec) {
        int figures = 3 + 2 * sec.labels.length;
        double[] out = new double[figures];
        double weight = 0;
        for (int e = 0; e < sec.index.length; e++) {
            double w = st.weight[sec.index[e]];
            weight += w;
            for (int fi = 0; fi < figures; fi++) {
                out[fi] += Math.abs(w * x(fi, sec.rvPlay[e], sec.rvInsurance[e], sec.bet[e]));
            }
        }
        for (int fi = 0; fi < figures; fi++) {
            out[fi] /= weight;
        }
        return out;
    }

    /** The B2.2 parameters of one column of keys-sample.tsv: its bet count's, and the play count's that keys its play. */
    static ExportBundle.Strategy keyRule(CountKeys.Count c) {
        CountKeys.Count p = playing(c);
        ExportBundle.Strategy k = new ExportBundle.Strategy();
        k.tags = p.tag.clone();
        k.deckSum = ExportBundle.deckSum(k.tags);
        k.trueCounted = p.trueCounted;
        k.divisor = p.divisor();
        k.betTags = c.betTags().clone();
        k.betDeckSum = ExportBundle.deckSum(k.betTags);
        k.ace = c.aceSideCount;
        if (c.trueCounted != p.trueCounted || c.divisor() != p.divisor()) {
            throw new IllegalArgumentException(c.name + " keys its bets another way than its play");
        }
        return k;
    }

    /**
     * keys-sample.tsv against B2.2 worked out from each count's parameters: its columns the counts
     * it was written for, its lines the sample's states and deals in order, and every key and
     * chance in it.
     */
    static void keys(Path file, ScoringStates st, List<CountKeys.Count> counts, Report r) throws IOException {
        ExportBundle.KeysSample ks = ExportBundle.readKeysSample(file);
        List<String> names = new ArrayList<>();
        ExportBundle.Strategy[] rule = new ExportBundle.Strategy[counts.size()];
        int[][][] classes = new int[counts.size()][][];
        boolean[] aware = new boolean[counts.size()];
        for (int c = 0; c < counts.size(); c++) {
            names.add(counts.get(c).name);
            rule[c] = keyRule(counts.get(c));
            classes[c] = ExportBundle.classes(rule[c].tags);
            aware[c] = ExportBundle.kindAware(rule[c].tags);
        }
        r.require(Arrays.asList(ks.counts).equals(names), "keys-sample.tsv's columns are " + Arrays.toString(ks.counts));
        int at = 0;
        int keys = 0;
        for (int[] want : sample(st.size, ks.seed)) {
            int s = want[0];
            for (int k = 0; k < want.length; k++) {
                r.require(at < ks.lines.size(), "keys-sample.tsv ends before state " + s + "'s lines");
                ExportBundle.KeyLine l = ks.lines.get(at++);
                String where = "keys-sample.tsv, state " + s + (k == 0 ? ", bet line" : ", deal " + want[k]);
                r.require(l.bet == (k == 0) && l.state == s && l.shoe == st.shoe[s] && l.round == st.round[s]
                        && l.depth == st.depth[s], where + " is due");
                if (k > 0) {
                    int i = want[k];
                    r.require(l.p1 == Deals.P1[i] && l.p2 == Deals.P2[i] && l.up == Deals.UP[i], where + ": its deal");
                }
                for (int c = 0; c < counts.size(); c++) {
                    String cell = where + ", " + names.get(c);
                    if (k == 0) {
                        r.require(l.chances[c] == null && l.keys[c] != null && l.keys[c].length == 1, cell + ": one key");
                        r.equal(l.keys[c][0], ExportBundle.betKey(rule[c], st.dealt[s], st.depth[s]), cell);
                        keys++;
                        continue;
                    }
                    double[][] parts = parts(rule[c], classes[c], aware[c], st.dealt[s], st.depth[s], want[k]);
                    if (parts.length == 0) {
                        r.require(l.keys[c] == null, cell + ": the cards left cannot make the deal, so '-'");
                        continue;
                    }
                    r.require(l.keys[c] != null && l.keys[c].length == parts.length
                            && (l.chances[c] != null) == aware[c], cell + ": " + parts.length + " keys");
                    for (int y = 0; y < parts.length; y++) {
                        r.equal(l.keys[c][y], (long) parts[y][0], cell + ", key " + y);
                        if (aware[c]) {
                            r.same(l.chances[c][y], parts[y][1], cell + ", chance " + y);
                        }
                        keys++;
                    }
                }
            }
        }
        r.equal(ks.lines.size(), at, "keys-sample.tsv's lines");
        // B4's words, counted from the file rather than from sample(), which wrote it: each of the
        // first 20 records has a bet line and a play line for every deal.
        int[] bets = new int[Math.min(EVERY_DEAL_STATES, st.size)];
        int[] plays = new int[bets.length];
        for (ExportBundle.KeyLine l : ks.lines) {
            if (l.state < bets.length) {
                if (l.bet) {
                    bets[l.state]++;
                } else {
                    plays[l.state]++;
                }
            }
        }
        for (int s = 0; s < bets.length; s++) {
            r.require(bets[s] == 1 && plays[s] == Deals.COUNT, "keys-sample.tsv: record " + s + " has " + bets[s]
                    + " bet lines and " + plays[s] + " play lines, not 1 and " + Deals.COUNT);
        }
        r.lines.add("keys-sample.tsv: " + at + " lines, " + keys + " keys of " + counts.size()
                + " counts equal to B2.2 worked out from each count's tags");
    }

    /** B8's first cell as a strategy file answers it by B2.2 to B2.4: what the simulator must find. */
    static String fileFirst(ExportBundle.Strategy f, int[][] classes, boolean aware, PlayerMove[] chart, ScoringStates st,
                            int s, int deal) {
        if (st.depth[s] >= f.cut) {
            return "-";
        }
        double[][] parts = parts(f, classes, aware, st.dealt[s], st.depth[s], deal);
        if (parts.length == 0) {
            return "-";
        }
        StringBuilder b = new StringBuilder();
        for (double[] part : parts) {
            int key = (int) part[0];
            int g = f.group(key);
            char m = Deals.playerNatural(deal) ? '-' : f.scope == ExportBundle.Scope.BETTING
                    ? ExportBundle.MOVE_LETTERS.charAt(chartFirst(chart, deal)) : ExportBundle.letter(f.dealtFirst(g, deal));
            String ins = deal < ACE_UP ? (f.insure[g][deal] ? "1" : "0") : "-";
            b.append(b.length() > 0 ? ";" : "").append(key).append(':').append(g).append(':').append(m).append(':').append(ins);
        }
        return b.toString();
    }

    /** B8's later cell as a strategy file answers it: the key after four cards seen, its later group, the spot's cell and move. */
    static String fileLater(ExportBundle.Strategy f, int[][] classes, boolean aware, PlayerMove[] chart, ScoringStates st,
                            int s, int deal, int hit, Map<String, Integer> cellAt) {
        if (f.scope != ExportBundle.Scope.FULL || st.depth[s] >= f.cut || aware) {
            return "-";
        }
        int p1 = Deals.P1[deal];
        int p2 = Deals.P2[deal];
        int up = Deals.UP[deal];
        int t = classes[p1][0] + classes[p2][0] + classes[up][0] + classes[hit][0];
        int key = ExportBundle.keyAt(f, ExportBundle.tally(f.tags, st.dealt[s]), st.depth[s], t, LATER_SEEN);
        int l = f.laterGroup(key);
        int hard = p1 + p2 + hit;
        boolean soft = (p1 == 1 || p2 == 1 || hit == 1) && hard + 10 <= 21;
        int total = soft ? hard + 10 : hard;
        if (total > 21) {
            return key + ":" + l + ":-:-";
        }
        Integer c = cellAt.get(up + " " + total + " " + (soft ? 1 : 0));
        char m = c != null ? (f.stands[l][c] ? 'S' : 'H')
                : ExportBundle.MOVE_LETTERS.charAt(chart[chartIndex(up, total, soft ? 1 : 0, 0, 0, 0, 1)].ordinal());
        return key + ":" + l + ":" + (c == null ? "-" : c.toString()) + ":" + m;
    }

    /**
     * B8's split cell as a strategy file answers it by B2.4, worked out from B8's words: '-' for a
     * file not of scope full; otherwise, in each group in order, the split r that has drawn x
     * against up stands on an ace and a ten, and else plays the first move of the ranking split
     * hands read (ExportBundle's splitRanking: the split line, else the first line) that B8's
     * legal moves allow, Stand if none: for a split ace Stand only, for r from 2 to 10 Stand, Hit
     * and Double, with Split too in the part after ':', which only x = r, r from 2 to 10, has.
     */
    static String fileSplit(ExportBundle.Strategy f, int r, int x, int up) {
        if (f.scope != ExportBundle.Scope.FULL) {
            return "-";
        }
        boolean aceTen = r == 1 && x == 10 || r == 10 && x == 1;
        int deal = Deals.index(Math.min(r, x), Math.max(r, x), up);
        StringBuilder b = new StringBuilder();
        for (int offered = 0; offered <= (x == r && r >= 2 ? 1 : 0); offered++) {
            b.append(offered == 1 ? ":" : "");
            for (int g = 0; g < f.lo.length; g++) {
                PlayerMove play = PlayerMove.Stand;
                for (PlayerMove m : aceTen ? new PlayerMove[0] : f.splitRanking(g, deal)) {
                    boolean legal = m == PlayerMove.Stand || r >= 2 && (m == PlayerMove.Hit || m == PlayerMove.Double
                            || m == PlayerMove.Split && offered == 1);
                    if (legal) {
                        play = m;
                        break;
                    }
                }
                b.append(ExportBundle.letter(play));
            }
        }
        return b.toString();
    }

    /**
     * lookups.tsv against the strategy files (B8): its columns every strategy file in path order,
     * its first lines keys-sample.tsv's play lines in order, its later lines the first states'
     * first deals and hit ranks worked out here from B8's words, its split lines B8's 1,000 when
     * a file plays full (readLookups refuses one missing, out of place or not due), and every
     * cell what the file it names answers there. lookups.tsv is written from the engine's own fit
     * (Lookup), so this ties the files the simulator plays to the answers the engine scored with,
     * decision by decision.
     */
    static void lookups(Path file, ScoringStates st, PlayerMove[] chart, Map<String, ExportBundle.Strategy> byFile,
                        Report r) throws IOException {
        ExportBundle.Strategy[] f = byFile.values().toArray(new ExportBundle.Strategy[0]);
        int[][][] classes = new int[f.length][][];
        boolean[] aware = new boolean[f.length];
        boolean full = false;
        for (int c = 0; c < f.length; c++) {
            classes[c] = ExportBundle.classes(f[c].tags);
            aware[c] = ExportBundle.kindAware(f[c].tags);
            full |= f[c].scope == ExportBundle.Scope.FULL;
        }
        ExportBundle.Lookups lk = ExportBundle.readLookups(file, full);
        r.require(Arrays.asList(lk.files).equals(new ArrayList<>(byFile.keySet())), "lookups.tsv's columns are "
                + Arrays.toString(lk.files) + ", the strategy files " + byFile.keySet());
        Map<String, Integer> cellAt = new java.util.HashMap<>();
        int[][] cells = ExportBundle.cells();
        for (int c = 0; c < cells.length; c++) {
            cellAt.put(cells[c][0] + " " + cells[c][1] + " " + cells[c][2], c);
        }
        // Each line due, {state, deal, hit} for a first (hit 0) or later line, {-1, r, x, up} for a split line.
        List<int[]> want = new ArrayList<>();
        List<int[]> sample = sample(st.size, lk.seed);
        for (int[] l : sample) {
            for (int k = 1; k < l.length; k++) {
                want.add(new int[]{l[0], l[k], 0});
            }
        }
        // B8's later lines from its words: the first 200 states of the sample, each one's first deal, every rank left.
        for (int k = 0; full && k < Math.min(LATER_STATES, sample.size()); k++) {
            int s = sample.get(k)[0];
            int i = sample.get(k)[1];
            int[] left = ShoeRun.ranksLeft(st.dealt[s]);
            int[] three = {Deals.P1[i], Deals.P2[i], Deals.UP[i]};
            boolean made = true;
            for (int x : three) {
                made &= --left[x] >= 0;
            }
            for (int h = 1; made && h <= 10; h++) {
                if (left[h] > 0) {
                    want.add(new int[]{s, i, h});
                }
            }
        }
        // And its split lines: by up-card, then split rank, then second card.
        for (int up = 1; full && up <= 10; up++) {
            for (int rank = 1; rank <= 10; rank++) {
                for (int x = 1; x <= 10; x++) {
                    want.add(new int[]{-1, rank, x, up});
                }
            }
        }
        r.equal(lk.lines.size(), want.size(), "lookups.tsv's lines");
        long compared = 0;
        long splitCells = 0;
        int laterLines = 0;
        int splitLines = 0;
        for (int n = 0; n < want.size(); n++) {
            ExportBundle.LookupLine l = lk.lines.get(n);
            int[] w = want.get(n);
            if (w[0] < 0) {
                String where = "lookups.tsv line " + (n + 4) + " (split " + w[1] + " " + w[2] + " " + w[3] + ")";
                r.require(l.split && l.p1 == w[1] && l.p2 == w[2] && l.up == w[3], where + " is due");
                splitLines++;
                for (int c = 0; c < f.length; c++) {
                    String expect = fileSplit(f[c], w[1], w[2], w[3]);
                    r.require(expect.equals(l.cells[c]), where + ", " + lk.files[c] + ": the engine's answer is "
                            + l.cells[c] + ", the file's " + expect);
                    compared++;
                    splitCells += f[c].scope == ExportBundle.Scope.FULL ? 1 : 0;
                }
                continue;
            }
            int i = w[1];
            String where = "lookups.tsv line " + (n + 4) + " (state " + w[0] + ", deal " + Deals.P1[i] + " " + Deals.P2[i]
                    + " " + Deals.UP[i] + (w[2] > 0 ? ", hit " + w[2] : "") + ")";
            r.require(!l.split && l.state == w[0] && l.p1 == Deals.P1[i] && l.p2 == Deals.P2[i] && l.up == Deals.UP[i]
                    && l.later == (w[2] > 0) && l.hit == w[2], where + " is due");
            laterLines += w[2] > 0 ? 1 : 0;
            for (int c = 0; c < f.length; c++) {
                String expect = w[2] == 0 ? fileFirst(f[c], classes[c], aware[c], chart, st, w[0], i)
                        : fileLater(f[c], classes[c], aware[c], chart, st, w[0], i, w[2], cellAt);
                r.require(expect.equals(l.cells[c]), where + ", " + lk.files[c] + ": the engine's answer is "
                        + l.cells[c] + ", the file's " + expect);
                compared++;
            }
        }
        r.require(!full || splitCells > 0, "lookups.tsv: a file plays full and no split cell was compared");
        r.lines.add("lookups.tsv: " + (want.size() - laterLines - splitLines) + " first lines, " + laterLines
                + " later lines and " + splitLines + " split lines, " + compared + " cells (" + splitCells
                + " of full-play files' split hands), each the engine's answer equal to its strategy file's");
    }

    // ------------------------------------------------------------------ the export

    /** A row to export: an entry of a scored count, at the penetrations given (0, 1, 2 for 70, 75, 80%). */
    static final class Wanted {
        final SystemScorer.Result result;
        final SystemScorer.Entry entry;
        final int[] pens;
        /** A full-play row's play at each penetration (index 0 to 2), from its fold strategies; null for a first-decision row. */
        final Refit[] full;

        Wanted(SystemScorer.Result result, SystemScorer.Entry entry, int... pens) {
            this(result, entry, null, pens);
        }

        Wanted(SystemScorer.Result result, SystemScorer.Entry entry, Refit[] full, int... pens) {
            if (!(full == null ? result.entries : result.fullEntries).contains(entry)) {
                throw new IllegalArgumentException(entry.name + " is not a " + (full == null ? "first-decision" : "full-play")
                        + " entry of " + result.count.name);
            }
            for (int p : pens) {
                if (full != null && (full[p] == null || !full[p].full() || full[p].count != result.count)) {
                    throw new IllegalArgumentException(entry.name + " has no full play of " + result.count.name + " at "
                            + SystemScorer.PENETRATIONS[p]);
                }
            }
            this.result = result;
            this.entry = entry;
            this.full = full;
            this.pens = pens.clone();
        }
    }

    /**
     * Writes the bundle into out (which must not exist yet), through out.partial, and renames it
     * once the round trip has passed: the rows given at their penetrations with the plans labels,
     * chart.tsv, states.bin of every state of st, keys-sample.tsv for keyCounts, exact.tsv, and the
     * index and manifest. h is the state file's header, which states.bin repeats. Returns the round
     * trip's account.
     */
    static Report export(Path out, SystemScorer scorer, StateStore.Header h, List<Wanted> rows, String[] labels,
                         List<CountKeys.Count> keyCounts, long sampleSeed, PrintStream log) throws IOException {
        return export(out, scorer, h, rows, null, labels, keyCounts, sampleSeed, log);
    }

    /** The same, with the chart's reference row at the penetrations it names when chartRow is given (B3, B5). */
    static Report export(Path out, SystemScorer scorer, StateStore.Header h, List<Wanted> rows, ChartRow chartRow,
                         String[] labels, List<CountKeys.Count> keyCounts, long sampleSeed, PrintStream log)
            throws IOException {
        Path partial = out.resolveSibling(out.getFileName() + ".partial");
        if (Files.exists(out) || Files.exists(partial)) {
            throw new IllegalStateException(out + " or " + partial + " exists already; a bundle is written once");
        }
        for (String l : labels) {
            checkLabel(l);
        }
        ScoringStates st = scorer.st;
        Files.createDirectories(partial);
        writeChart(partial.resolve("chart.tsv"), BasicStrategy.chart());
        List<ExportBundle.Strategy> strategies = new ArrayList<>();
        List<ExportBundle.Section> sections = new ArrayList<>();
        List<Expect> expected = new ArrayList<>();
        List<Lookup> lookups = new ArrayList<>();
        FullSource full = new FullSource();
        java.util.IdentityHashMap<SystemScorer.Result, Refit[]> refits = new java.util.IdentityHashMap<>();
        for (Wanted w : rows) {
            Refit[] byPen = refits.computeIfAbsent(w.result, k -> new Refit[SystemScorer.CUTS.length]);
            for (int p : w.pens) {
                long t0 = System.nanoTime();
                if (w.full == null && byPen[p] == null) {
                    byPen[p] = new Refit(scorer, w.result.count, p);
                }
                Refit rf = w.full != null ? w.full[p] : byPen[p];
                if (w.full != null) {
                    full.values.put(FullSource.key(w.entry.name, ExportBundle.PENS[p]), rf);
                    for (int f = 0; f < 2; f++) {
                        log.printf("%s at %s, %s%n", w.entry.name, SystemScorer.PENETRATIONS[p], rf.splitWords(f));
                    }
                }
                Built b = build(scorer, rf, w.entry, labels);
                for (ExportBundle.Strategy s : b.fold) {
                    if (Files.exists(partial.resolve(s.fileName()))) {
                        throw new IllegalArgumentException(s.row + " at " + s.pen + "%, fold " + s.fold + ": "
                                + s.fileName() + " is written already; B0 refuses two rows with one slug, or a row twice");
                    }
                    Files.write(partial.resolve(s.fileName()), format(s).getBytes(StandardCharsets.UTF_8),
                            java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
                    strategies.add(s);
                }
                lookups.addAll(Arrays.asList(b.lookup));
                sections.add(b.section);
                expected.add(Expect.of(w.entry, p));
                log.printf("%s at %s: %d states, %.1f s%n", w.entry.name, SystemScorer.PENETRATIONS[p],
                        b.section.index.length, (System.nanoTime() - t0) / 1e9);
            }
        }
        if (chartRow != null) {
            for (int p : chartRow.pens) {
                sections.add(chartSection(scorer.pen(p), p));
                expected.add(Expect.of(chartRow.entry, p));
            }
        }
        List<int[]> sample = sample(st.size, sampleSeed);
        List<int[]> later = laterLines(st, sample);
        List<LookupColumn> columns = new ArrayList<>();
        for (Lookup l : lookups) {
            columns.add(LookupColumn.of(l, st, sample, later));
        }
        return finishBundle(out, partial, st, h, strategies, sections, expected, columns, keyCounts, sampleSeed, log, full);
    }

    /**
     * The rest of a bundle whose chart.tsv and strategy files are in partial already: states.bin,
     * keys-sample.tsv, lookups.tsv, exact.tsv, the index and manifest; then the round trip, and
     * partial renamed to out once it has passed.
     */
    static Report finishBundle(Path out, Path partial, ScoringStates st, StateStore.Header h,
                               List<ExportBundle.Strategy> strategies, List<ExportBundle.Section> sections,
                               List<Expect> expected, List<LookupColumn> columns, List<CountKeys.Count> keyCounts,
                               long sampleSeed, PrintStream log, FullSource full) throws IOException {
        List<int[]> sample = sample(st.size, sampleSeed);
        writeStates(partial.resolve("states.bin"), h, st, sections);
        writeKeysSample(partial.resolve("keys-sample.tsv"), st, keyCounts, sampleSeed);
        writeLookups(partial.resolve("lookups.tsv"), st, sampleSeed, sample, laterLines(st, sample), columns);
        writeExact(partial.resolve("exact.tsv"), st, sections);
        long total = writeIndexAndManifest(partial, strategies);
        log.printf("bundle: %d files, %d bytes (%.1f MB)%n", strategies.size() + 7, total, total / 1e6);
        Report r = roundTrip(partial, st, h, expected, keyCounts, full);
        for (String l : r.lines) {
            log.println(l);
        }
        Files.move(partial, out, StandardCopyOption.ATOMIC_MOVE);
        log.println("round trip passed; the bundle is " + out);
        return r;
    }

    // ------------------------------------------------------------------ full play, from the replay

    /** The chart as chart.tsv holds it (B1), from BasicStrategy, for checking files before any bundle is written. */
    static PlayerMove[] chartArray() {
        int[] m = chartMoves(BasicStrategy.chart());
        PlayerMove[] out = new PlayerMove[m.length];
        for (int n = 0; n < m.length; n++) {
            out[n] = PlayerMove.values()[m[n]];
        }
        return out;
    }

    /**
     * One full-play row at one penetration, checked while its strategies' values are at hand:
     * both files written and read back, every state replayed from them on those values against
     * the section (B2.4, PlayValues for Hit and Split, the dealt hand's first move from the first
     * lines), the later lines against the play the last round walked, every first line whole
     * against the refit's ranking and every ranking split hands read (the split line, else the
     * first) against the walked play's, and the row's figures summed in the engine's order
     * against its estimate.
     */
    static Report checkFull(ScoringStates st, PlayerMove[] chart, Built b, Refit rf, Expect x) {
        return checkFull(st, chart, b, rf, x, null);
    }

    /** The same, dealt the shoes' every round start for a plan that leaves the table (null when none does). */
    static Report checkFull(ScoringStates st, PlayerMove[] chart, Built b, Refit rf, Expect x, AllStates dealt) {
        Report r = new Report();
        ExportBundle.Strategy[] files = new ExportBundle.Strategy[2];
        for (int f = 0; f < 2; f++) {
            files[f] = ExportBundle.readStrategyText(format(b.fold[f]));
        }
        ExportBundle.Section again = replay(st, chart, files, b.section, r, rf, null, dealt);
        double[] engine = engineOrder(st, again, files);
        for (int fi = 0; fi < engine.length; fi++) {
            if (held(again.labels, fi)) {
                r.same(engine[fi], x.expected(again.labels, fi), x.row + " at " + x.pen + "%: "
                        + figures(again.labels).get(fi) + " summed in the engine's order, against the "
                        + (metric(again.labels, fi) >= 0 ? "report's estimate" : "entry's wide figure"));
            }
        }
        return r;
    }

    /** What the replay's export keeps of one row at one penetration: its files' text, its section, its figures and lookups. */
    static final class RowExport implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        final String[] text = new String[2];
        ExportBundle.Section section;
        Expect expect;
        final LookupColumn[] columns = new LookupColumn[2];
        /** Why the row could not be exported, or null: kept rather than thrown, so the replay goes on. */
        String failure;
    }

    /**
     * The replay's --export (PLAN_COMPUTE.md 1.2 and 6.1): after each fold item, while its two
     * strategies hold their last round's values, it builds the item's full-play rows (every
     * full-play entry not bet on its own value, or those named), checks each against the values
     * (checkFull) and keeps what the bundle needs; once every item is kept, it writes the bundle
     * from the records alone, chart.tsv, states.bin, keys-sample.tsv for the catalog and the run's
     * counts with their variants, lookups.tsv and exact.tsv, and runs the round trip, which holds
     * each full section to the one checked on the values.
     */
    static class ReplayExport implements FullIndexReplay.Hook {
        final Path out;
        final Path states;
        /** The replay's --shoes, or -1 for every shoe the state file holds. */
        final int shoes;
        /** Rows to export as NAME@P (P 70, 75 or 80); empty for every full-play row of every item replayed. */
        final List<String> rows;
        final String[] labels;
        /**
         * Further plans for some rows, by NAME@P, after labels: the wide plans SIM_CHECK.md registers
         * for a row (F43 to F48), which only those rows' files carry.
         */
        final Map<String, List<String>> more = new TreeMap<>();
        final long seed;
        final PrintStream log;
        private ScoringStates st;
        /** Every round start dealt again, for a plan that leaves the table; from the scorer's round starts when it has them. */
        private transient AllStates dealt;

        ReplayExport(Path out, Path states, int shoes, List<String> rows, String[] labels, long seed, PrintStream log) {
            for (String l : labels) {
                checkLabel(l);
            }
            this.out = out;
            this.states = states;
            this.shoes = shoes;
            this.rows = new ArrayList<>(rows);
            this.labels = labels.clone();
            this.seed = seed;
            this.log = log;
        }

        /** The same, the rows as entry names to penetrations 0 to 2 (as the replay's --export-row and SIM_CHECK.md's registry give them). */
        ReplayExport(Path out, Path states, int shoes, Map<String, ? extends java.util.Set<Integer>> rows, String[] labels,
                     long seed, PrintStream log) {
            this(out, states, shoes, atPens(rows), labels, seed, log);
        }

        static List<String> atPens(Map<String, ? extends java.util.Set<Integer>> rows) {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, ? extends java.util.Set<Integer>> e : rows.entrySet()) {
                for (int p : e.getValue()) {
                    out.add(e.getKey() + "@" + ExportBundle.PENS[p]);
                }
            }
            return out;
        }

        /**
         * The report plans exported for every row by default: SIM_CHECK.md's full-play figures are
         * flat and the 1-8 same schedule, and the wide plans of F43 to F48 go to their rows alone
         * (registeredPlans).
         */
        static final String[] PLANS = {"same-8"};

        /**
         * The wide plans SIM_CHECK.md registers on the replay's strategies (source replay, F43 to
         * F48), by NAME@P among the penetrations replayed: each row's ev: labels that are not report
         * plans.
         */
        static Map<String, List<String>> registeredPlans(int[] pens) {
            Map<String, List<String>> out = new TreeMap<>();
            for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
                String fig = f.exportFigure();
                if (f.play == SimCheckPlan.Play.FULL && f.source.equals("replay") && contains(pens, penIndex(f.pen))
                        && fig.startsWith("ev:") && widePlan(fig.substring(3)) != null) {
                    List<String> l = out.computeIfAbsent(f.entry() + "@" + f.pen, k -> new ArrayList<>());
                    if (!l.contains(fig.substring(3))) {
                        l.add(fig.substring(3));
                    }
                }
            }
            return out;
        }

        /** Adds plans to one row's files, NAME@P, after the plans every row has. */
        ReplayExport plans(String row, List<String> labels) {
            for (String l : labels) {
                checkLabel(l);
            }
            more.computeIfAbsent(row, k -> new ArrayList<>()).addAll(labels);
            return this;
        }

        /** The plans of a row's files at penetration p (0 to 2): every row's, then its own. */
        String[] labelsFor(String row, int p) {
            List<String> out = new ArrayList<>(Arrays.asList(labels));
            for (String l : more.getOrDefault(row + "@" + ExportBundle.PENS[p], new ArrayList<>())) {
                if (!out.contains(l)) {
                    out.add(l);
                }
            }
            return out.toArray(new String[0]);
        }

        /** Whether any row's files carry a wide plan, and whether one leaves the table. */
        boolean anyWide(boolean leaving) {
            List<String> all = new ArrayList<>(Arrays.asList(labels));
            for (List<String> l : more.values()) {
                all.addAll(l);
            }
            for (String l : all) {
                SystemScorer.Plan w = widePlan(l);
                if (w != null && (!leaving || !w.sameSchedule())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The replay's --export rows when no --export-row is given: the full-play figures SIM_CHECK.md
         * registers on c06c061's strategies (SimCheckPlan.figures, source c06c061 or the replay), as
         * NAME@P at each figure's penetration among those the replay scores (pens, 0 to 2).
         */
        static List<String> registered(int[] pens) {
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
                if (f.play == SimCheckPlan.Play.FULL && (f.source.equals("c06c061") || f.source.equals("replay"))
                        && contains(pens, penIndex(f.pen))) {
                    out.add(f.entry() + "@" + f.pen);
                }
            }
            return new ArrayList<>(out);
        }

        /**
         * Rows named by --export-row: NAME@P (P 70, 75 or 80, one the replay scores) as given, and a
         * bare NAME at every penetration the replay scores (pens, 0 to 2). With --only, name each row
         * at the penetration its item is replayed at, since a row asked for that no item replayed is
         * refused when the export finishes.
         */
        static List<String> named(List<String> names, int[] pens) {
            List<String> out = new ArrayList<>();
            for (String n : names) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*)@(\\d+)$").matcher(n);
                if (m.matches()) {
                    int p = penIndex(Integer.parseInt(m.group(2)));
                    if (!contains(pens, p)) {
                        throw new IllegalArgumentException("--export-row " + n + ": the replay scores no "
                                + m.group(2) + "% items");
                    }
                    out.add(n);
                    continue;
                }
                for (int p : pens) {
                    out.add(n + "@" + ExportBundle.PENS[p]);
                }
            }
            return out;
        }

        private static boolean contains(int[] a, int x) {
            for (int y : a) {
                if (y == x) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String name() {
            return "export";
        }

        /** In the replay's identity, so a resume asked for other rows, plans or sample is refused. */
        @Override
        public String describe() {
            return "export (rows " + (rows.isEmpty() ? "every full-play row" : rows.toString()) + "; plans "
                    + String.join(",", labels) + (more.isEmpty() ? "" : "; and " + more) + "; sample seed " + seed + ")";
        }

        /** Before anything is replayed: a bundle is written once, so one already there is refused now, not after hours. */
        @Override
        public void start(SystemScorer scorer, FullIndexReplay.Options o) {
            Path partial = out.resolveSibling(out.getFileName() + ".partial");
            if (Files.exists(out) || Files.exists(partial)) {
                throw new IllegalStateException(out + " or " + partial + " exists already; a bundle is written once");
            }
            // A wide plan's files are held to the entry's wide figures, which only the replay's --wide
            // scores (it comes before the export among the hooks and sets the scorer's plans).
            if (anyWide(false)) {
                List<String> scored = new ArrayList<>();
                for (SystemScorer.Plan w : scorer.wide == null ? new SystemScorer.Plan[0] : scorer.wide) {
                    scored.add(w.label);
                }
                List<String> all = new ArrayList<>(Arrays.asList(labels));
                for (List<String> l : more.values()) {
                    all.addAll(l);
                }
                for (String l : all) {
                    if (widePlan(l) != null && !scored.contains(l)) {
                        throw new IllegalStateException("the export's plan " + l + " is a wide plan, which the replay "
                                + "scores only with --wide; give --wide, or leave it out of the export");
                    }
                }
            }
        }

        /** Every round start dealt again, once, for the plans that leave the table. */
        AllStates dealt(SystemScorer scorer) {
            if (dealt == null && anyWide(true)) {
                try {
                    dealt = scorer != null && scorer.roundStarts instanceof AllStates.Starts
                            ? ((AllStates.Starts) scorer.roundStarts).all
                            : AllStates.replay(StateStore.header(states), st.shoes, st.rules, BasicStrategy.chart(), st);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            return dealt;
        }

        boolean wanted(String row, int p) {
            return rows.isEmpty() || rows.contains(row + "@" + ExportBundle.PENS[p]);
        }

        @Override
        public java.io.Serializable after(FullIndexReplay.Item item, SystemScorer scorer, SystemScorer.Result scored,
                                          List<FullIndexFit.Strategy> two) {
            if (item.halves) {
                return null;
            }
            st = scorer.st;
            ArrayList<RowExport> kept = new ArrayList<>();
            Refit rf = null;
            Refit.Refused refused = null;
            List<int[]> sample = sample(st.size, seed);
            List<int[]> later = laterLines(st, sample);
            for (SystemScorer.Entry e : scored.fullEntries) {
                if (e.ownValue || !wanted(e.name, item.pen)) {
                    continue;
                }
                // One refit serves the item's rows. One B7 refuses (Refit.Refused: groups or a table
                // not the refit's) refuses each of them, kept so the replay goes on; anything else
                // Refit throws is a mistake in what the replay hands the hook, and stops it here.
                if (rf == null && refused == null) {
                    try {
                        rf = new Refit(scorer, item.pen, two.toArray(new FullIndexFit.Strategy[0]));
                    } catch (Refit.Refused ex) {
                        refused = ex;
                    }
                    for (int f = 0; rf != null && f < 2; f++) {
                        log.printf("export: %s at %s, %s%n", rf.count.name, SystemScorer.PENETRATIONS[item.pen],
                                rf.splitWords(f));
                    }
                }
                RowExport x = new RowExport();
                x.expect = Expect.of(e, item.pen);
                try {
                    if (refused != null) {
                        throw refused;
                    }
                    Built b = build(scorer, rf, e, labelsFor(e.name, item.pen));
                    Report r = checkFull(st, chartArray(), b, rf, x.expect, dealt(scorer));
                    x.section = b.section;
                    for (int f = 0; f < 2; f++) {
                        x.text[f] = format(b.fold[f]);
                        x.columns[f] = LookupColumn.of(b.lookup[f], st, sample, later);
                    }
                    log.printf("export: %s at %s checked on its values, %d values compared, %d splits the last round "
                                    + "valued played again from its files, largest relative difference %s%n", e.name,
                            SystemScorer.PENETRATIONS[item.pen], r.compared, r.splits, r.worst);
                } catch (RuntimeException ex) {
                    // Kept, not thrown: one row that cannot be exported must not stop a replay of hours.
                    // An exception without a message is kept by its class, so finish still refuses the row.
                    x.failure = ex.getMessage() != null ? ex.getMessage() : String.valueOf(ex);
                    log.printf("export: %s at %s is not exported: %s%n", e.name, SystemScorer.PENETRATIONS[item.pen],
                            x.failure);
                }
                kept.add(x);
            }
            return kept.isEmpty() ? null : kept;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void finish(FullIndexReplay.Assembled all) throws Exception {
            List<RowExport> kept = new ArrayList<>();
            for (int k = 0; k < all.items.size(); k++) {
                Object x = all.record(k).kept.get(name());
                if (x != null) {
                    kept.addAll((List<RowExport>) x);
                }
            }
            for (String w : rows) {
                boolean found = false;
                for (RowExport x : kept) {
                    found |= (x.expect.row + "@" + x.expect.pen).equals(w);
                }
                if (!found) {
                    throw new IllegalStateException("--export-row " + w + " is not a full-play row of any item replayed");
                }
            }
            if (kept.isEmpty()) {
                throw new IllegalStateException("--export: no full-play row was replayed");
            }
            StringBuilder failed = new StringBuilder();
            for (RowExport x : kept) {
                if (x.failure != null) {
                    failed.append("\n- ").append(x.expect.row).append(" at ").append(x.expect.pen).append("%: ").append(x.failure);
                }
            }
            if (failed.length() > 0) {
                throw new IllegalStateException("--export writes no bundle: rows that could not be exported" + failed);
            }
            if (st == null) {
                // Every item was kept by an earlier start: the states are read here, as the replay read them.
                st = FullIndexPilot.load(states, shoes < 0 ? StateStore.shoesDone(states) : shoes);
            }
            Path partial = out.resolveSibling(out.getFileName() + ".partial");
            if (Files.exists(out) || Files.exists(partial)) {
                throw new IllegalStateException(out + " or " + partial + " exists already; a bundle is written once");
            }
            Files.createDirectories(partial);
            writeChart(partial.resolve("chart.tsv"), BasicStrategy.chart());
            List<ExportBundle.Strategy> strategies = new ArrayList<>();
            List<ExportBundle.Section> sections = new ArrayList<>();
            List<Expect> expected = new ArrayList<>();
            List<LookupColumn> columns = new ArrayList<>();
            FullSource full = new FullSource();
            full.dealt = dealt(null);
            for (RowExport x : kept) {
                for (int f = 0; f < 2; f++) {
                    ExportBundle.Strategy s = ExportBundle.readStrategyText(x.text[f]);
                    Files.write(partial.resolve(s.fileName()), x.text[f].getBytes(StandardCharsets.UTF_8),
                            java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
                    strategies.add(s);
                    columns.add(x.columns[f]);
                }
                sections.add(x.section);
                expected.add(x.expect);
                full.verified.put(FullSource.key(x.expect.row, x.expect.pen), x.section);
            }
            // The whole catalog, whichever counts the run scored: D2 holds the simulator's keys to all
            // 31 counts and 8 variants and fails a bundle without them (SIM_CHECK.md section 5), so a
            // replay of the registered counts alone, the page look's of step 8, carries them too, as
            // its first-decision export does. The run's own counts follow.
            List<CountKeys.Count> catalog = CountKeys.catalog();
            List<CountKeys.Count> own = new ArrayList<>();
            List<String> catalogNames = new ArrayList<>();
            for (CountKeys.Count c : catalog) {
                catalogNames.add(c.name);
            }
            for (CountKeys.Count c : all.counts) {
                if (!c.name.equals(CountKeys.nullCount().name) && !catalogNames.contains(c.name)) {
                    own.add(c);
                }
            }
            finishBundle(out, partial, st, StateStore.header(states), strategies, sections, expected, columns,
                    keyCounts(catalog, own), seed, log, full);
        }
    }

    /** Reads the arguments and writes the bundle (see the class comment). */
    public static void main(String[] args) throws Exception {
        Path file = null;
        Path published = null;
        Path out = null;
        List<String> rowArgs = new ArrayList<>();
        String chartFrom = null;
        String[] labels = reportPlans().toArray(new String[0]);
        long seed = SAMPLE_SEED;
        for (int k = 0; k < args.length; k++) {
            switch (args[k]) {
                case "--published":
                    published = Paths.get(args[++k]);
                    break;
                case "--out":
                    out = Paths.get(args[++k]);
                    break;
                case "--row":
                    rowArgs.add(args[++k]);
                    break;
                case "--chart-from":
                    chartFrom = args[++k];
                    break;
                case "--plans":
                    labels = planList(args[++k]);
                    break;
                case "--sample-seed":
                    seed = Long.parseLong(args[++k]);
                    break;
                default:
                    if (file != null || args[k].startsWith("--")) {
                        throw new IllegalArgumentException("unknown argument " + args[k]);
                    }
                    file = Paths.get(args[k]);
            }
        }
        if (file == null || published == null || out == null || rowArgs.isEmpty()) {
            throw new IllegalArgumentException("usage: StrategyExport STATES --published RESULTS.ser --out DIR "
                    + "--row \"NAME@70,75,80\" [--row ...] [--chart-from SAVED.ser@70,75,80] [--plans same-1,...] "
                    + "[--sample-seed N]");
        }
        StateStore.Header header = StateStore.header(file);
        int shoes = StateStore.shoesDone(file);
        RoundRules rules = StateRun.montreal();
        String made = StateRun.describe(rules, BasicStrategy.chart());
        if (!header.rules.equals(made)) {
            throw new IllegalStateException(file + " was made under \"" + header.rules + "\"; this code plays \"" + made + "\"");
        }
        ScoringStates st = new ScoringStates(StateStore.readAll(file), shoes, rules);
        CeilingReport.Published p = CeilingReport.read(published);
        String resamples = p.line("resamples ");
        CeilingReport.requirePaired(p, file, st, Integer.parseInt(resamples.substring(0, resamples.indexOf(','))));
        SystemScorer scorer = new SystemScorer(st, new Bootstrap(shoes, 0, Bootstrap.SEED), SystemScorer.M_PLAY);
        List<Wanted> rows = new ArrayList<>();
        for (String a : rowArgs) {
            int at = a.lastIndexOf('@');
            String name = at < 0 ? a : a.substring(0, at);
            int[] pens = at < 0 ? new int[]{0, 1, 2}
                    : Arrays.stream(a.substring(at + 1).split(",")).mapToInt(x -> penIndex(Integer.parseInt(x.trim()))).toArray();
            rows.add(wanted(p.results, name, pens));
        }
        ChartRow chart = null;
        if (chartFrom != null) {
            int at = chartFrom.lastIndexOf('@');
            int[] pens = at < 0 ? new int[]{0, 1, 2}
                    : Arrays.stream(chartFrom.substring(at + 1).split(",")).mapToInt(x -> penIndex(Integer.parseInt(x.trim()))).toArray();
            chart = chartRow(CeilingReport.readSaved(Paths.get(at < 0 ? chartFrom : chartFrom.substring(0, at))), file, pens);
        }
        export(out, scorer, header, rows, chart, labels, keyCounts(CountKeys.catalog(), new ArrayList<>()), seed, System.out);
    }

    /** The chart's reference row of a CeilingReport save, refused unless the save was scored on this state file. */
    static ChartRow chartRow(CeilingReport.Saved saved, Path states, int[] pens) throws IOException {
        if (!saved.identity.contains("\n" + FullIndexRun.stateFile(states) + "\n")) {
            throw new IllegalStateException("the saved reference rows were not scored on " + states + " as it is now:\n"
                    + saved.identity);
        }
        for (SystemScorer.Entry e : saved.references) {
            if (e.name.equals(CHART_ROW)) {
                return new ChartRow(e, pens);
            }
        }
        throw new IllegalArgumentException("the saved reference rows hold no " + CHART_ROW);
    }

    /** 0, 1 or 2 for 70, 75 or 80. */
    static int penIndex(int pen) {
        int p = Arrays.binarySearch(ExportBundle.PENS, pen);
        if (p < 0) {
            throw new IllegalArgumentException("no penetration " + pen + "; they are " + Arrays.toString(ExportBundle.PENS));
        }
        return p;
    }

    /** The first-decision or betting-only entry named so among the results; a full-play row is refused here. */
    static Wanted wanted(List<SystemScorer.Result> results, String name, int[] pens) {
        for (SystemScorer.Result r : results) {
            for (SystemScorer.Entry e : r.entries) {
                if (e.name.equals(name)) {
                    return new Wanted(r, e, pens);
                }
            }
            for (SystemScorer.Entry e : r.fullEntries) {
                if (e.name.equals(name)) {
                    throw new IllegalArgumentException(name + " is a full-play row; it is exported by the replay");
                }
            }
        }
        throw new IllegalArgumentException("no row named \"" + name + "\" in the results");
    }
}
