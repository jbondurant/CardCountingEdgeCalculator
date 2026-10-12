import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The report of the wider spreads and wonging (PLAN_COMPUTE.md section 3): every row's figures under
 * each wide plan, from a file of what a scoring with SystemScorer.wide kept (wide.ser, save), as
 * runs/report-wide-SHA.md in the house style and runs/site/spreads-SHA.json for the site generator.
 *
 * For each penetration and plan it gives, per row: EV per round watched with its SE, bias and 95%
 * interval (basic where the bias is not small next to the SE, as Bootstrap.interval decides), the
 * difference from Hi-Lo in the same scope with its interval and the max-|t| simultaneous interval
 * over the family of every count's difference in that scope, plan and penetration, the average
 * bet and share of rounds played, EV per round played and per unit bet, and the mean square of
 * the bets placed. The same schedule's figures rank counts: every count places the same bets in
 * the same share of rounds. The own rule's do not: counts play different shares at different
 * average bets, so its tables give those two beside the EV and rank nothing.
 *
 * Full-play intervals hold the later plays as fitted, so they are widened per plan as
 * FULL_INDEX_PLAYS.md section 4 widens them at 1-8 (widening): a count the wide pilot measured
 * takes its own ratio for the plan, every other count the largest measured for that plan, marked
 * as not its own; a plan the pilot did not measure takes the largest ratio measured for any plan,
 * marked as borrowed; a pilot run without --wide gives only the 1-8 and flat ratios, applied to
 * every plan and labelled an extrapolation. A full-play level (one row's EV) is widened by its
 * count's difference ratio, as PLAN_PAGE.md 2.4 sets. Without a pilot, no full-play interval is
 * printed or written.
 *
 * Before anything is written it checks, at every penetration of every row: P8's EV and average
 * bet are the 1-8 same schedule's and own ramp's that the row's existing figures hold, to the bit
 * (PLAN_COMPUTE.md 3.5, check 1); and the same schedule's average bet and share played are the
 * same for every count row, within 1e-12 relative (check 4). A failed check writes nothing.
 *
 * java -Xmx4g -cp build/classes WideReport WIDE_SER --commit SHA --md OUT.md --json OUT.json
 *      [--widening PILOT_OUTPUT]
 */
final class WideReport {

    private WideReport() {
    }

    /** One row of wide.ser: an entry's wide figures, what it is, and what the checks compare them with. */
    static final class Row implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        final String name;
        /** The count's name, null for a row fitted on nothing (bounds and references). */
        final String count;
        /** first decisions, betting only, full play, own value, full play on its own value, bound, or reference. */
        final String scope;
        /** Whether the count is Hi-Lo itself, from which every difference is taken. */
        final boolean hiLo;
        final double[][] wide;
        final double[][][] wideBoot;
        final double[][][] wideKeys;
        /** [pen][key row]: key, share of rounds, fitted advantage, from Entry.keys; null where it has none. */
        final double[][][] keys;
        /** [pen]: same(8), sameBet(8), own(8) and ownBet(8) as the row's existing figures hold them. */
        final double[][] spread8;

        Row(SystemScorer.Entry e) {
            this.name = e.name;
            this.count = e.count == null ? null : e.count.name;
            this.scope = scope(e);
            this.hiLo = e.count != null && e.count.base == null && e.count.systems.contains("Hi-Lo");
            if (e.wide == null) {
                throw new IllegalArgumentException(e.name + " was scored without the wide plans");
            }
            this.wide = e.wide;
            this.wideBoot = e.wideBoot;
            this.wideKeys = e.wideKeys;
            int pens = SystemScorer.CUTS.length;
            keys = new double[pens][][];
            spread8 = new double[pens][];
            int s = SystemScorer.SPREADS;
            for (int p = 0; p < pens; p++) {
                if (e.keys[p] != null) {
                    keys[p] = new double[e.keys[p].length][];
                    for (int k = 0; k < keys[p].length; k++) {
                        keys[p][k] = new double[]{e.keys[p][k][0], e.keys[p][k][1], e.keys[p][k][3]};
                    }
                }
                if (e.estimate[p] != null) {
                    double[] x = e.estimate[p];
                    spread8[p] = new double[]{x[SystemScorer.same(s)], x[SystemScorer.sameBet(s)], x[SystemScorer.own(s)],
                            x[SystemScorer.ownBet(s)]};
                }
            }
        }

        static String scope(SystemScorer.Entry e) {
            if (e.count == null) {
                return e.ownValue ? "bound" : "reference";
            }
            if (e.ownValue) {
                return e.fullPlay ? "full play on its own value" : "own value";
            }
            return e.fullPlay ? "full play" : e.bettingOnly ? "betting only" : "first decisions";
        }

        /** Whether its later plays are a fit held on every resample, so its intervals are widened. */
        boolean fullPlay() {
            return scope.startsWith("full play");
        }
    }

    /** What wide.ser holds: the scoring's identity, the plans and every row. */
    static final class Saved {
        String identity;
        SystemScorer.Plan[] plans;
        List<Row> rows;
    }

    /**
     * Every row with wide figures among the Results and other entries given (bounds and the oracle
     * reference), in order: each Result's entries then its full-play entries. Rows without wide
     * figures (the chart and perfect reference rows) are left out; a count row without them is
     * refused, since the report would otherwise drop it unseen.
     */
    static List<Row> rows(List<SystemScorer.Result> results, List<SystemScorer.Entry> others) {
        List<Row> out = new ArrayList<>();
        for (SystemScorer.Result r : results) {
            for (SystemScorer.Entry e : r.entries) {
                out.add(new Row(e));
            }
            for (SystemScorer.Entry e : r.fullEntries) {
                out.add(new Row(e));
            }
        }
        for (SystemScorer.Entry e : others) {
            if (e.wide != null) {
                out.add(new Row(e));
            }
        }
        return out;
    }

    /**
     * Writes wide.ser whole, then moves it into place: the identity of the scoring (its commit, the
     * states and the source of its fits, as the caller words it), the plans and the rows. The replay
     * of PLAN_COMPUTE.md 6.1 and the page look of 6.3 write their wide figures through this.
     */
    static void save(Path file, String identity, SystemScorer.Plan[] plans, List<Row> rows) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (ObjectOutputStream o = new ObjectOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp),
                1 << 20))) {
            o.writeUTF(identity);
            o.writeObject(plans);
            o.writeObject(new ArrayList<>(rows));
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    @SuppressWarnings("unchecked")
    static Saved read(Path file) throws IOException, ClassNotFoundException {
        try (ObjectInputStream o = new ObjectInputStream(new java.io.BufferedInputStream(Files.newInputStream(file),
                1 << 20))) {
            Saved s = new Saved();
            s.identity = o.readUTF();
            s.plans = (SystemScorer.Plan[]) o.readObject();
            s.rows = (List<Row>) o.readObject();
            return s;
        }
    }

    // ------------------------------------------------------------------ widening

    /** How a ratio was found: the count's own for the plan, the plan's largest, another plan's, or the 1-8 pilot's. */
    enum Basis {
        MEASURED("measured"), PLAN_LARGEST("the largest measured for this plan, not its own"),
        BORROWED("the largest measured for any plan: this plan was not measured"),
        EXTRAPOLATED("widened by the 1-8 ratios, an extrapolation");

        final String words;

        Basis(String words) {
            this.words = words;
        }
    }

    /** The ratios a pilot printed (FullIndexPilot.readRatios), and the rule that takes one for a plan and count. */
    static final class Widening {
        final Map<String, Map<String, double[]>> ratios;
        final String source;
        final boolean wide;

        Widening(Map<String, Map<String, double[]>> ratios, String source) {
            this.ratios = ratios;
            this.source = source;
            boolean any = false;
            for (String m : ratios.keySet()) {
                any |= !m.equals("1-8 same schedule") && !m.equals("flat");
            }
            this.wide = any;
            if (ratios.isEmpty()) {
                throw new IllegalArgumentException(source + " holds no ratios in a Results section");
            }
        }

        static Widening read(Path pilot) throws IOException {
            return new Widening(FullIndexPilot.readRatios(Files.readAllLines(pilot, StandardCharsets.UTF_8)),
                    pilot.toString());
        }

        /** The largest finite difference ratio in the metrics given. */
        private double largest(Iterable<String> metrics) {
            double most = Double.NaN;
            for (String m : metrics) {
                Map<String, double[]> by = ratios.get(m);
                if (by == null) {
                    continue;
                }
                for (double[] r : by.values()) {
                    if (!Double.isNaN(r[0]) && !(r[0] <= most)) {
                        most = r[0];
                    }
                }
            }
            return most;
        }

        /**
         * The ratio a full-play figure of this plan, rule and count is widened by, at least 1, and how
         * it was found (WideReport's class comment gives the rule).
         */
        Object[] of(SystemScorer.Plan plan, boolean same, String count) {
            if (!wide) {
                List<String> both = java.util.Arrays.asList("1-8 same schedule", "flat");
                double own = Double.NaN;
                for (String m : both) {
                    double[] r = ratios.containsKey(m) ? ratios.get(m).get(count) : null;
                    if (r != null && !Double.isNaN(r[0]) && !(r[0] <= own)) {
                        own = r[0];
                    }
                }
                return new Object[]{Math.max(1, Double.isNaN(own) ? largest(both) : own), Basis.EXTRAPOLATED};
            }
            String key = FullIndexPilot.wideName(plan.label, same ? "same" : "own");
            Map<String, double[]> by = ratios.get(key);
            if (by != null) {
                double[] r = by.get(count);
                if (r != null && !Double.isNaN(r[0])) {
                    return new Object[]{Math.max(1, r[0]), Basis.MEASURED};
                }
                return new Object[]{Math.max(1, largest(java.util.Collections.singletonList(key))), Basis.PLAN_LARGEST};
            }
            return new Object[]{Math.max(1, largest(ratios.keySet())), Basis.BORROWED};
        }
    }

    // ------------------------------------------------------------------ the figures

    /** One row's figures under one plan and rule at one penetration. */
    static final class Figure {
        Row row;
        /** EV per round watched, its interval (widened for full play), and the ratio it was widened by (1 when not). */
        double ev;
        Bootstrap.Interval iv;
        double widen = 1;
        Basis basis;
        /** The difference from Hi-Lo in the same scope and its intervals; null for Hi-Lo and rows with no Hi-Lo. */
        Bootstrap.Interval diff;
        double simLo = Double.NaN;
        double simHi = Double.NaN;
        double bet;
        double share;
        double bet2;

        double perPlayed() {
            return ev / share;
        }

        double perUnit() {
            return ev / bet;
        }
    }

    /** The Hi-Lo row of a scope, null if none. */
    static Row hiLo(List<Row> rows, String scope) {
        for (Row r : rows) {
            if (r.hiLo && r.scope.equals(scope)) {
                return r;
            }
        }
        return null;
    }

    /**
     * Every row's figures under plan i at penetration p, by the same schedule or the own rule,
     * those with an EV there; the differences from Hi-Lo with their max-|t| family per scope. A
     * full-play interval is given only with a widening; without one, iv and diff are null there.
     */
    static List<Figure> figures(Saved s, Widening wd, int p, int i, boolean same) {
        SystemScorer.Plan plan = s.plans[i];
        int base = same ? SystemScorer.W_SAME : SystemScorer.W_OWN;
        int at = SystemScorer.wideAt(i, base);
        int[] kept = SystemScorer.wideRows(s.plans);
        int row = -1;
        for (int k = 0; k < kept.length; k++) {
            row = kept[k] == at ? k : row;
        }
        List<Figure> out = new ArrayList<>();
        for (Row r : s.rows) {
            if (r.wide[p] == null || Double.isNaN(r.wide[p][at])) {
                continue;
            }
            Figure f = new Figure();
            f.row = r;
            f.ev = r.wide[p][at];
            f.bet = r.wide[p][at + 1];
            f.share = r.wide[p][at + 2];
            f.bet2 = r.wide[p][at + 3];
            boolean widened = r.fullPlay();
            if (widened && wd != null) {
                Object[] w = wd.of(plan, same, r.count);
                f.widen = (Double) w[0];
                f.basis = (Basis) w[1];
            }
            if (row >= 0 && (!widened || wd != null)) {
                double[] b = r.wideBoot[p][row];
                f.iv = Bootstrap.interval(f.ev, f.widen == 1 ? b : Bootstrap.widen(b, f.widen));
            }
            out.add(f);
        }
        // Differences from Hi-Lo in each scope, and each scope's family.
        Map<String, List<Figure>> byScope = new LinkedHashMap<>();
        for (Figure f : out) {
            if (f.row.count != null && !f.row.scope.contains("own value")) {
                byScope.computeIfAbsent(f.row.scope, x -> new ArrayList<>()).add(f);
            }
        }
        for (List<Figure> family : byScope.values()) {
            Row h = hiLo(s.rows, family.get(0).row.scope);
            if (h == null || row < 0) {
                continue;
            }
            List<double[]> boots = new ArrayList<>();
            List<Figure> members = new ArrayList<>();
            for (Figure f : family) {
                if (f.row == h || f.iv == null) {
                    continue;
                }
                double[] a = f.row.wideBoot[p][row];
                double[] b = h.wideBoot[p][row];
                double[] d = new double[a.length];
                for (int r = 0; r < d.length; r++) {
                    d[r] = a[r] - b[r];
                }
                if (f.widen != 1) {
                    d = Bootstrap.widen(d, f.widen);
                }
                f.diff = Bootstrap.interval(f.ev - h.wide[p][at], d);
                boots.add(d);
                members.add(f);
            }
            if (members.isEmpty()) {
                continue;
            }
            double[] est = new double[members.size()];
            for (int k = 0; k < est.length; k++) {
                est[k] = members.get(k).diff.estimate;
            }
            double c = Bootstrap.maxT(est, boots.toArray(new double[0][]));
            for (Figure f : members) {
                f.simLo = f.diff.estimate - c * f.diff.se;
                f.simHi = f.diff.estimate + c * f.diff.se;
            }
        }
        return out;
    }

    /**
     * The checks made before anything is written, each failure in words: P8 is the existing 1-8
     * figures to the bit, and the same schedule's average bet and share are the same for every
     * row within 1e-12 relative, the bound and oracle rows included, since each hands out the
     * same reference bets.
     */
    static List<String> check(Saved s) {
        List<String> bad = new ArrayList<>();
        int p8 = -1;
        for (int i = 0; i < s.plans.length; i++) {
            p8 = s.plans[i].label.equals("P8") ? i : p8;
        }
        if (p8 < 0) {
            bad.add("no plan P8 to check against the 1-8 figures");
        }
        int[] wm = {SystemScorer.W_SAME, SystemScorer.W_SAME_BET, SystemScorer.W_OWN, SystemScorer.W_OWN_BET};
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            double[] first = null;
            for (Row r : s.rows) {
                if (r.wide[p] == null) {
                    continue;
                }
                for (int k = 0; k < wm.length && p8 >= 0 && r.spread8[p] != null; k++) {
                    double a = r.spread8[p][k];
                    double b = r.wide[p][SystemScorer.wideAt(p8, wm[k])];
                    if (Double.doubleToLongBits(a) != Double.doubleToLongBits(b)) {
                        bad.add(String.format(Locale.ROOT, "%s at %s: P8's %s is %s, the 1-8 figure %s", r.name,
                                SystemScorer.PENETRATIONS[p], SystemScorer.WIDE_NAMES[wm[k]], b, a));
                    }
                }
                if (first == null) {
                    first = r.wide[p];
                    continue;
                }
                for (int i = 0; i < s.plans.length; i++) {
                    for (int m : new int[]{SystemScorer.W_SAME_BET, SystemScorer.W_SAME_SHARE}) {
                        int at = SystemScorer.wideAt(i, m);
                        double a = first[at];
                        double b = r.wide[p][at];
                        if (s.plans[i].sameSchedule() && !(Math.abs(a - b) <= 1e-12 * Math.max(1, Math.abs(a)))) {
                            bad.add(String.format(Locale.ROOT, "%s at %s: %s's %s is %s, another count's %s", r.name,
                                    SystemScorer.PENETRATIONS[p], s.plans[i], SystemScorer.WIDE_NAMES[m], b, a));
                        }
                    }
                }
            }
        }
        return bad;
    }

    // ------------------------------------------------------------------ the report

    static String pct(double x) {
        return ComparisonReport.pct(x);
    }

    static String num(double x) {
        return Double.isNaN(x) ? "n/a" : String.format(Locale.ROOT, "%.3f", x);
    }

    static String threshold(double tau) {
        return tau == Double.NEGATIVE_INFINITY ? "-" : SystemScorer.tauLabel(tau) + "%";
    }

    /** An interval, marked b when basic and, when widened, with its ratio and how it was found. */
    static String ci(Bootstrap.Interval iv, Figure f) {
        if (iv == null) {
            return f.row.fullPlay() ? "not widened: no pilot" : "n/a";
        }
        String w = f.widen == 1 && f.basis == null ? "" : String.format(Locale.ROOT, " w%.2f%s", f.widen,
                "mpox".charAt(f.basis.ordinal()));
        return ComparisonReport.ci(iv) + w;
    }

    /** The lowest key whose fitted advantage, pooled over the folds, reaches tau; "none" when none does. */
    static String keyFrom(double[][] keys, double tau) {
        if (keys == null) {
            return "n/a";
        }
        double k = thresholdKey(keys, tau);
        return Double.isNaN(k) ? "none" : String.valueOf((long) k);
    }

    /**
     * The same as a number, NaN when no key's pooled advantage reaches tau. It tells a reader where
     * a threshold falls; the figures themselves take each fold's own fitted advantage.
     */
    static double thresholdKey(double[][] keys, double tau) {
        for (double[] k : keys) {
            if (k[2] >= tau) {
                return k[0];
            }
        }
        return Double.NaN;
    }

    /** The entry and exit thresholds of the plans given, each once, in increasing order. */
    static double[] thresholds(SystemScorer.Plan[] plans) {
        java.util.TreeSet<Double> out = new java.util.TreeSet<>();
        for (SystemScorer.Plan p : plans) {
            for (double t : new double[]{p.enter, p.exit}) {
                if (t != Double.NEGATIVE_INFINITY) {
                    out.add(t);
                }
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * The columns of a row of a key table in the data file: the key, its share of rounds, its
     * fitted advantage pooled over the folds, then for each plan its same-schedule expected bet and
     * share played at the key and its own-rule bet (SystemScorer.KEY_COLS), each pooled over the
     * folds by test weight as the existing key tables are.
     */
    static List<String> keyColumns(SystemScorer.Plan[] plans) {
        List<String> out = new ArrayList<>(java.util.Arrays.asList("key", "share", "fittedAdvantage"));
        for (SystemScorer.Plan p : plans) {
            out.add(p.label + " sameBet");
            out.add(p.label + " samePlay");
            out.add(p.label + " ownBet");
        }
        return out;
    }

    static void print(Saved s, Widening wd, String commit, Map<String, String> inputs, PrintStream out) {
        out.printf("# Wider spreads and wonging%n%n");
        out.printf("WideReport at commit %s. Inputs, by SHA-256:", commit);
        for (Map.Entry<String, String> e : inputs.entrySet()) {
            out.printf(" `%s` %s;", e.getKey(), e.getValue());
        }
        out.printf("%nThe scoring's identity: %s.%n%n", s.identity.replace("\n", "; "));
        out.println("Every figure is per round watched, a round sat out betting 0, in units of the minimum bet; EV as a "
                + "percentage of a unit. Same schedule: each plan's bets on the true chart advantage of the fold's test "
                + "states, handed out by each count in order of its own fitted advantage, so every count places the "
                + "same bets in the same share of rounds and the order of EVs ranks the counts. A block of keys that "
                + "straddles a back-counting plan's entry is played at random in the share of its rounds above the "
                + "threshold, at the average of the bets there, at least a unit (PLAN_COMPUTE.md 3.2). Own rule: each "
                + "key bets the plan's bet on its own fitted advantage; counts then play different shares at "
                + "different average bets, so those two are given beside the EV and nothing is ranked on it. An X plan "
                + "leaves at the first round start of the shoe whose fitted advantage is below its exit, and has only "
                + "the own rule. Cards flow as heads-up chart rounds whether a round is played or not; the plays are "
                + "those fitted for flat bets, a slight underestimate for wide spreads and wonging.");
        out.println();
        out.println("Intervals: 95%, the percentile interval or, marked b, the basic one where the bias is not small "
                + "next to the SE. vs Hi-Lo: the difference from Hi-Lo in the same scope, paired resample by resample; "
                + "Simultaneous: the max-|t| interval over every count's difference in that scope, plan and "
                + "penetration. Full play holds the later plays as fitted on every resample, so its intervals are "
                + "widened, marked wR followed by m (the count's own ratio, measured), p (the largest measured for "
                + "the plan), o (the largest measured for any plan) or x (the 1-8 pilot's ratio, an extrapolation).");
        out.printf("%n## The plans%n%n| Plan | Unit | Cap | Enters at | Leaves at | Bets handed out |%n|---|---|---|---|---|---|%n");
        for (SystemScorer.Plan p : s.plans) {
            out.printf(Locale.ROOT, "| %s | %.3f%% | %d | %s | %s | %s |%n", p.label, 100 * p.unit, p.cap, threshold(p.enter),
                    threshold(p.exit), p.sameSchedule() ? "same schedule and own rule" : "own rule only");
        }
        double[] taus = {-0.01, -0.005, 0, 0.005, 0.01};
        int last = SystemScorer.CUTS.length - 1;
        out.printf("%n## Thresholds as keys at %s%n%n", SystemScorer.PENETRATIONS[last]);
        out.println("For each row playing its own fit at first decisions, the lowest bet key whose fitted advantage, "
                + "pooled over the two folds by test weight, reaches each threshold. For a count keyed on its true "
                + "count at divisor 1 the key is that rounded true count.");
        out.printf("%n| Count | -1.0%% | -0.5%% | 0%% | +0.5%% | +1.0%% |%n|---|---|---|---|---|---|%n");
        for (Row r : s.rows) {
            if (r.scope.equals("first decisions")) {
                StringBuilder b = new StringBuilder("| " + r.name + " |");
                for (double t : taus) {
                    b.append(' ').append(keyFrom(r.keys[last], t)).append(" |");
                }
                out.println(b);
            }
        }
        if (wd == null) {
            out.printf("%nNo pilot was given: no full-play interval is printed.%n");
        } else {
            out.printf("%nFull-play intervals widened from the ratios in `%s`%s.%n", wd.source, wd.wide ? ""
                    : ", a pilot without the wide plans: every plan takes the 1-8 ratios, an extrapolation");
        }
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            out.printf("%n## %s%n", SystemScorer.PENETRATIONS[p]);
            for (int i = 0; i < s.plans.length; i++) {
                for (boolean same : new boolean[]{true, false}) {
                    if (same && !s.plans[i].sameSchedule()) {
                        continue;
                    }
                    table(s, wd, p, i, same, out);
                }
            }
        }
    }

    static void table(Saved s, Widening wd, int p, int i, boolean same, PrintStream out) {
        List<Figure> fs = figures(s, wd, p, i, same);
        if (fs.isEmpty()) {
            return;
        }
        out.printf("%n### %s, %s, %s%n%n", s.plans[i].label, same ? "same schedule" : "own rule", SystemScorer.PENETRATIONS[p]);
        if (same) {
            out.println("| Row | EV | SE | Bias | 95% | vs Hi-Lo | its 95% | Simultaneous | Average bet | Share played | "
                    + "EV per round played | EV per unit bet | Mean square bet |");
            out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        } else {
            out.println("| Row | EV | SE | Bias | 95% | Average bet | Share played | EV per round played | "
                    + "EV per unit bet | Mean square bet |");
            out.println("|---|---|---|---|---|---|---|---|---|---|");
        }
        for (Figure f : fs) {
            String se = f.iv == null ? "n/a | n/a" : ComparisonReport.abs(f.iv.se) + " | " + pct(f.iv.bias);
            String tail = String.format("%s | %s | %s | %s | %s |", num(f.bet), num(f.share), pct(f.perPlayed()),
                    pct(f.perUnit()), num(f.bet2));
            if (same) {
                String d = f.diff == null ? "| | | |" : "| " + pct(f.diff.estimate) + " | " + ci(f.diff, f) + " | ["
                        + pct(f.simLo) + ", " + pct(f.simHi) + "] |";
                out.printf("| %s | %s | %s | %s %s %s%n", f.row.name, pct(f.ev), se, ci(f.iv, f), d, tail);
            } else {
                out.printf("| %s | %s | %s | %s | %s%n", f.row.name, pct(f.ev), se, ci(f.iv, f), tail);
            }
        }
    }

    // ------------------------------------------------------------------ the data file

    /** A JSON string, escaped. */
    static String str(String x) {
        if (x == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (char c : x.toCharArray()) {
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** A JSON number: the double's shortest exact form, null for NaN or an infinity. */
    static String n(double x) {
        return Double.isNaN(x) || Double.isInfinite(x) ? "null" : Double.toString(x);
    }

    static String interval(Bootstrap.Interval iv) {
        if (iv == null) {
            return "null";
        }
        return "{\"est\":" + n(iv.estimate) + ",\"se\":" + n(iv.se) + ",\"bias\":" + n(iv.bias) + ",\"lo\":" + n(iv.lo)
                + ",\"hi\":" + n(iv.hi) + ",\"basic\":" + iv.basic + "}";
    }

    /**
     * runs/site/spreads-SHA.json (PLAN_COMPUTE.md 3.6): the commit and the inputs' SHA-256, the plans,
     * and per row, penetration, plan and rule the EV with its interval, the difference from Hi-Lo with
     * its interval and simultaneous interval, the average bet, share played, EV per round played and
     * per unit bet, the mean square bet, and the widening of a full-play row; each count row's
     * thresholds as keys, and Hi-Lo's key tables (keyTables). Every EV is a fraction of a unit, not
     * a percentage; a figure the row does not have is null.
     */
    static void json(Saved s, Widening wd, String commit, Map<String, String> inputs, PrintStream out) {
        out.print("{\"commit\":" + str(commit) + ",\"identity\":" + str(s.identity) + ",\"inputs\":{");
        int k = 0;
        for (Map.Entry<String, String> e : inputs.entrySet()) {
            out.print((k++ > 0 ? "," : "") + str(e.getKey()) + ":" + str(e.getValue()));
        }
        out.print("},\"widening\":" + (wd == null ? "null" : "{\"source\":" + str(wd.source) + ",\"widePilot\":" + wd.wide
                + "}"));
        out.print(",\"plans\":[");
        for (int i = 0; i < s.plans.length; i++) {
            SystemScorer.Plan p = s.plans[i];
            out.print((i > 0 ? "," : "") + "{\"label\":" + str(p.label) + ",\"unit\":" + n(p.unit) + ",\"cap\":" + p.cap
                    + ",\"enter\":" + n(p.enter) + ",\"exit\":" + n(p.exit) + ",\"sameSchedule\":" + p.sameSchedule()
                    + ",\"wonging\":" + p.wonging() + "}");
        }
        out.print("],\"keyColumns\":[");
        List<String> columns = keyColumns(s.plans);
        for (int c = 0; c < columns.size(); c++) {
            out.print((c > 0 ? "," : "") + str(columns.get(c)));
        }
        out.print("],\"rows\":[");
        Map<String, List<Figure>> cache = new java.util.HashMap<>();
        for (int r = 0; r < s.rows.size(); r++) {
            Row row = s.rows.get(r);
            out.print((r > 0 ? "," : "") + "{\"name\":" + str(row.name) + ",\"count\":" + str(row.count) + ",\"scope\":"
                    + str(row.scope) + ",\"hiLo\":" + row.hiLo + ",\"pens\":{");
            boolean firstPen = true;
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                if (row.wide[p] == null) {
                    continue;
                }
                out.print((firstPen ? "" : ",") + str(SystemScorer.PENETRATIONS[p]) + ":{");
                firstPen = false;
                for (int i = 0; i < s.plans.length; i++) {
                    out.print((i > 0 ? "," : "") + str(s.plans[i].label) + ":{");
                    boolean any = false;
                    for (boolean same : new boolean[]{true, false}) {
                        Figure f = figureOf(s, wd, p, i, same, row, cache);
                        if (f == null) {
                            continue;
                        }
                        out.print((any ? "," : "") + (same ? "\"same\":" : "\"own\":"));
                        any = true;
                        out.print("{\"ev\":" + interval(f.iv) + ",\"evPoint\":" + n(f.ev) + ",\"diff\":" + interval(f.diff)
                                + ",\"simLo\":" + n(f.simLo) + ",\"simHi\":" + n(f.simHi) + ",\"bet\":" + n(f.bet)
                                + ",\"share\":" + n(f.share) + ",\"evPerPlayed\":" + n(f.perPlayed()) + ",\"evPerUnit\":"
                                + n(f.perUnit()) + ",\"bet2\":" + n(f.bet2) + ",\"widen\":" + n(f.widen) + ",\"widenBasis\":"
                                + (f.basis == null ? "null" : str(f.basis.words)) + "}");
                    }
                    out.print("}");
                }
                out.print("}");
            }
            out.print("}");
            keyTables(s, row, out);
            out.print("}");
        }
        out.println("]}");
    }

    /**
     * A row's keys in the data file, for a row with a key table: "thresholdKeys", at each
     * penetration the lowest key whose pooled fitted advantage reaches each plan threshold (null
     * when none does), so the page can say a threshold as a true count; and for a Hi-Lo row only,
     * "keys", its key table under every plan in keyColumns' order (figure W5, Hi-Lo's bet by true
     * count). Every count's tables would make the file several megabytes for one figure that shows
     * Hi-Lo; they stay in wide.ser.
     */
    static void keyTables(Saved s, Row row, PrintStream out) {
        double[] taus = thresholds(s.plans);
        out.print(",\"thresholdKeys\":{");
        boolean first = true;
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            if (row.keys[p] == null || row.keys[p].length == 0) {
                continue;
            }
            out.print((first ? "" : ",") + str(SystemScorer.PENETRATIONS[p]) + ":{");
            first = false;
            for (int t = 0; t < taus.length; t++) {
                out.print((t > 0 ? "," : "") + str(n(taus[t])) + ":" + n(thresholdKey(row.keys[p], taus[t])));
            }
            out.print("}");
        }
        out.print("}");
        if (!row.hiLo) {
            return;
        }
        out.print(",\"keys\":{");
        first = true;
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            if (row.keys[p] == null || row.keys[p].length == 0 || row.wideKeys == null || row.wideKeys[p] == null) {
                continue;
            }
            if (row.wideKeys[p].length != row.keys[p].length) {
                throw new IllegalStateException(row.name + " at " + SystemScorer.PENETRATIONS[p] + ": "
                        + row.wideKeys[p].length + " wide key rows, " + row.keys[p].length + " key rows");
            }
            out.print((first ? "" : ",") + str(SystemScorer.PENETRATIONS[p]) + ":[");
            first = false;
            for (int k = 0; k < row.keys[p].length; k++) {
                StringBuilder b = new StringBuilder(k > 0 ? ",[" : "[");
                for (double x : row.keys[p][k]) {
                    b.append(n(x)).append(',');
                }
                for (int c = 0; c < row.wideKeys[p][k].length; c++) {
                    b.append(c > 0 ? "," : "").append(n(row.wideKeys[p][k][c]));
                }
                out.print(b.append(']'));
            }
            out.print("]");
        }
        out.print("}");
    }

    /** The figure of one row from figures(), worked out once per penetration, plan and rule into cache. */
    private static Figure figureOf(Saved s, Widening wd, int p, int i, boolean same, Row row,
                                   Map<String, List<Figure>> cache) {
        if (same && !s.plans[i].sameSchedule()) {
            return null;
        }
        List<Figure> fs = cache.computeIfAbsent(p + " " + i + " " + same, x -> figures(s, wd, p, i, same));
        for (Figure f : fs) {
            if (f.row == row) {
                return f;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the run

    /** A file's SHA-256, in hex. */
    static String sha256(Path file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 20];
                for (int n; (n = in.read(buf)) > 0; ) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) {
                b.append(String.format("%02x", x));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads wide.ser and the pilot, makes the checks, and only if they all pass writes the report and
     * the data file, each whole to a temporary file first and then moved into place, refusing to
     * replace a file already there.
     */
    static void run(Path wideSer, Path pilot, String commit, Path md, Path json) throws Exception {
        for (Path out : new Path[]{md, json}) {
            if (Files.exists(out)) {
                throw new IllegalStateException(out + " is already there; a report is never written over");
            }
        }
        Saved s = read(wideSer);
        List<String> bad = check(s);
        if (!bad.isEmpty()) {
            throw new IllegalStateException("the wide figures fail their checks, so nothing is written:\n"
                    + String.join("\n", bad));
        }
        Widening wd = pilot == null ? null : Widening.read(pilot);
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put(wideSer.toString(), sha256(wideSer));
        if (pilot != null) {
            inputs.put(pilot.toString(), sha256(pilot));
        }
        write(md, out -> print(s, wd, commit, inputs, out));
        write(json, out -> json(s, wd, commit, inputs, out));
    }

    private interface Writer {
        void to(PrintStream out) throws Exception;
    }

    private static void write(Path file, Writer w) throws Exception {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (PrintStream out = new PrintStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16), false,
                "UTF-8")) {
            w.to(out);
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path wideSer = null;
        Path pilot = null;
        Path md = null;
        Path json = null;
        String commit = null;
        for (int k = 0; k < args.length; k++) {
            switch (args[k]) {
                case "--widening":
                    pilot = Paths.get(args[++k]);
                    break;
                case "--md":
                    md = Paths.get(args[++k]);
                    break;
                case "--json":
                    json = Paths.get(args[++k]);
                    break;
                case "--commit":
                    commit = args[++k];
                    break;
                default:
                    wideSer = Paths.get(args[k]);
            }
        }
        if (wideSer == null || md == null || json == null || commit == null) {
            System.err.println("usage: WideReport WIDE_SER --commit SHA --md OUT.md --json OUT.json [--widening PILOT]");
            System.exit(2);
        }
        run(wideSer, pilot, commit, md, json);
    }
}
