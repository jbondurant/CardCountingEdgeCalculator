import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The ceiling count's report (COUNT_DESIGN.md, section 5), as ComparisonReport --published
 * makes it: the published rows read from a full-index run's results.ser, which scored every
 * catalog count and side-count variant at first decisions on the same resamples from the same
 * seed, and only the counts given scored here, with the bound rows and the refit check.
 *
 * Before anything is printed it checks that the stored rows pair with the new ones: the run's
 * state file, shoes, valued states, resamples and seed are this report's; and Hi-Lo, Hi-Opt II
 * with its side count and Red Seven, a kind-aware count, scored again by these classes, equal the
 * stored entries to the bit, every estimate and resample at every penetration. On the main state
 * file the counts must be the registration, committed as it stands, and the ledger clean
 * (CustomCounts), and the look is written to the ledger.
 *
 * The published families' max-|t| multipliers are not touched: the custom rows do not join them.
 * A headline claim is made only for the (count, cell) pairs the file's "cells" name (CustomCounts):
 * each such row is compared with each of its comparators, paired, and the claim "better than the
 * best published count" is made where every difference's lower end is above zero; the smallest
 * lower end is the gain (section 5). A cell names at most two rows, so its claims, each at the
 * 95% interval's lower end, hold together at 95% (Bonferroni); a file naming no cells has no
 * claims. Every first-decision row, the counts', the stored rows' and the refit check's, is
 * checked against its bound on every resample, in flat and same-schedule cells; a row above it is
 * a scoring bug, and the report says so and fails. Full-play rows are not checked, since their
 * bound needs the split margin (section 2).
 */
final class CeilingReport {

    /** The stored rows' counts scored again for the check to the bit. */
    static final String[] CHECKED = {"Hi-Lo", "Hi-Opt II", "Red Seven (Red 7)"};

    private CeilingReport() {
    }

    /** What a full-index run's results.ser holds. */
    static final class Published {
        String identity;
        List<SystemScorer.Result> results;
        double[] perfect;

        /** A line of the identity that starts so, without the start; null if none. */
        String line(String start) {
            for (String l : identity.split("\n")) {
                if (l.startsWith(start)) {
                    return l.substring(start.length());
                }
            }
            return null;
        }

        /** The stored Result of the catalog entry holding this system. */
        SystemScorer.Result of(String system) {
            for (SystemScorer.Result r : results) {
                if (r.count.systems.contains(system)) {
                    return r;
                }
            }
            throw new IllegalStateException("the stored rows hold no " + system);
        }

        SystemScorer.Result nullCount() {
            for (SystemScorer.Result r : results) {
                if (r.count.systems.isEmpty()) {
                    return r;
                }
            }
            throw new IllegalStateException("the stored rows hold no null count");
        }
    }

    /** Reads results.ser as FullIndexRun wrote it, with these classes: it only reads. */
    @SuppressWarnings("unchecked")
    static Published read(Path file) throws IOException, ClassNotFoundException {
        try (InputStream in = Files.newInputStream(file);
             ObjectInputStream o = new ObjectInputStream(new java.io.BufferedInputStream(in, 1 << 20))) {
            Published p = new Published();
            p.identity = o.readUTF();
            p.results = (List<SystemScorer.Result>) o.readObject();
            p.perfect = (double[]) o.readObject();
            return p;
        }
    }

    /**
     * Refuses stored rows that do not pair with this run's: another state file, number of shoes or
     * valued states, number of resamples or seed.
     */
    static void requirePaired(Published p, Path states, ScoringStates st, int resamples) throws IOException {
        String file = FullIndexRun.stateFile(states);
        List<String> wrong = new ArrayList<>();
        if (!p.identity.contains("\n" + file + "\n")) {
            wrong.add("its state file is not " + file);
        }
        String shoes = p.line("shoes ");
        if (!(st.shoes + ", valued states " + st.size).equals(shoes)) {
            wrong.add("it has shoes " + shoes + ", not " + st.shoes + ", valued states " + st.size);
        }
        String r = p.line("resamples ");
        if (r == null || !r.startsWith(resamples + ", seed " + Bootstrap.SEED + ",")) {
            wrong.add("it has resamples " + r + ", not " + resamples + ", seed " + Bootstrap.SEED);
        }
        if (!wrong.isEmpty()) {
            throw new IllegalStateException("the stored rows do not pair with this run: " + String.join("; ", wrong));
        }
    }

    /** Whether two entries hold the same figures, every estimate and resample at every penetration, to the bit. */
    static String sameToTheBit(SystemScorer.Entry a, SystemScorer.Entry b) {
        if (!a.name.equals(b.name)) {
            return a.name + " is not " + b.name;
        }
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            for (int m = 0; m < SystemScorer.METRICS; m++) {
                if (Double.doubleToLongBits(a.estimate[p][m]) != Double.doubleToLongBits(b.estimate[p][m])) {
                    return a.name + "'s estimate at " + SystemScorer.PENETRATIONS[p] + ", metric " + m + ": "
                            + a.estimate[p][m] + " against " + b.estimate[p][m];
                }
                if (!Arrays.equals(a.boot[p][m], b.boot[p][m])) {
                    return a.name + "'s resamples at " + SystemScorer.PENETRATIONS[p] + ", metric " + m;
                }
            }
        }
        return null;
    }

    /** What the report scored, beside the stored rows. */
    static final class Ceiling {
        Published published;
        ScoringStates st;
        SystemScorer scorer;
        CustomCounts.Listing counts;
        String blob;
        List<SystemScorer.Result> custom = new ArrayList<>();
        List<SystemScorer.Entry> bounds;
        List<SystemScorer.Entry> refit = new ArrayList<>();
        List<String> checked = new ArrayList<>();
    }

    /**
     * Scores the counts given beside the stored rows: the check to the bit first, then each count
     * with its betting variant and its own-value row, the bound rows, and the refit check for the
     * headline counts and Zen.
     */
    static Ceiling score(Path states, ScoringStates st, SystemScorer scorer, Published p, CustomCounts.Listing counts,
                         int threads) throws Exception {
        Ceiling c = check(states, st, scorer, p, counts, threads);
        scoreCustom(c, threads);
        return c;
    }

    /** The checks that the stored rows pair with this run and this code, which refuse to go on if not. */
    static Ceiling check(Path states, ScoringStates st, SystemScorer scorer, Published p, CustomCounts.Listing counts,
                         int threads) throws Exception {
        requirePaired(p, states, st, scorer.boot.resamples);
        Ceiling c = new Ceiling();
        c.published = p;
        c.st = st;
        c.scorer = scorer;
        c.counts = counts;
        for (String system : CHECKED) {
            SystemScorer.Result stored = p.of(system);
            CountKeys.Count fresh = catalogEntry(system);
            SystemScorer.Result again = scorer.score(fresh, CountKeys.bettingVariant(fresh), null, null, threads);
            if (stored.entries.size() != again.entries.size()) {
                throw new IllegalStateException(system + ": the stored Result has " + stored.entries.size()
                        + " entries, scored again " + again.entries.size());
            }
            for (int e = 0; e < stored.entries.size(); e++) {
                String differs = sameToTheBit(stored.entries.get(e), again.entries.get(e));
                if (differs != null) {
                    throw new IllegalStateException("scored again, " + differs + ": the stored rows do not pair with "
                            + "this code, and the run stops until the reason is found (COUNT_DESIGN.md, section 5)");
                }
            }
            c.checked.add(stored.count.name);
        }
        return c;
    }

    /** The counts given, each with its betting variant and own-value row, the bound rows and the refit check. */
    static void scoreCustom(Ceiling c, int threads) {
        SystemScorer scorer = c.scorer;
        CustomCounts.Listing counts = c.counts;
        Published p = c.published;
        ScoringStates st = c.st;
        scorer.ownValueRows = true;
        try {
            for (CountKeys.Count x : counts.counts()) {
                c.custom.add(scorer.score(x, CountKeys.bettingVariant(x), null, null, threads));
            }
        } finally {
            scorer.ownValueRows = false;
        }
        c.bounds = scorer.bounds(p.perfect != null && p.perfect.length == st.size ? p.perfect : null);
        List<CountKeys.Count> refit = new ArrayList<>();
        for (CustomCounts.Entry e : counts.entries) {
            if ("headline".equals(e.role)) {
                refit.add(e.count);
            }
        }
        refit.add(catalogEntry("Zen Count"));
        for (CountKeys.Count x : refit) {
            c.refit.addAll(scorer.scheduleRefit(x, CountKeys.bettingVariant(x), SystemScorer.SPREADS, threads));
        }
    }

    /**
     * The report from a state file, the stored rows and the counts given: the counts file and the
     * ledger checked on the main file, the stored rows checked, the look written to the ledger,
     * then the counts scored and the report printed.
     */
    static Ceiling run(Path states, Path results, Path countsFile, int resamples, int threads, PrintStream out)
            throws Exception {
        CustomCounts.Listing counts = CustomCounts.read(countsFile);
        boolean main = CustomCounts.isMain(states);
        String blob = main ? CustomCounts.checkOnMain(counts) : null;
        Published p = read(results);
        ScoringStates st = FullIndexPilot.load(states, StateStore.shoesDone(states));
        SystemScorer scorer = new SystemScorer(st, new Bootstrap(st.shoes, resamples, Bootstrap.SEED), SystemScorer.M_PLAY);
        Ceiling c = check(states, st, scorer, p, counts, threads);
        c.blob = blob;
        if (main) {
            CustomCounts.appendLedger(CustomCounts.ledgerOf(counts), FullIndexRun.commit(), blob, "ComparisonReport "
                    + "--published " + results + ", " + resamples + " resamples");
        }
        scoreCustom(c, threads);
        print(c, states, out);
        return c;
    }

    static CountKeys.Count catalogEntry(String system) {
        return CountSearch.catalogCount(system);
    }

    // ------------------------------------------------------------------ the figures

    /** The published rows playing their own fits: every catalog entry and side-count variant, the null count left out. */
    static List<SystemScorer.Entry> published(Ceiling c, boolean withSideCounts) {
        List<SystemScorer.Entry> out = new ArrayList<>();
        for (SystemScorer.Result r : c.published.results) {
            if (r.count.systems.isEmpty()) {
                continue;
            }
            for (SystemScorer.Entry e : r.entries) {
                if (!e.bettingOnly && (withSideCounts || e.count.base == null)) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** The headline row of a registered count: its betting variant's for an F2 count, its own otherwise. */
    static SystemScorer.Entry row(SystemScorer.Result r) {
        return r.count.betVector != null ? r.entries.get(2) : r.entries.get(0);
    }

    /**
     * A headline row's comparators, as its registration says (CustomCounts.Entry.allComparators):
     * by default every published row for a count with a betting vector, the plain ones otherwise.
     */
    static List<SystemScorer.Entry> comparators(Ceiling c, SystemScorer.Result r) {
        int i = c.custom.indexOf(r);
        boolean all = i >= 0 ? c.counts.entries.get(i).allComparators() : r.count.betVector != null;
        return published(c, all);
    }

    /** The headline claim at one cell: each difference from a comparator, paired, and the smallest lower end. */
    static final class Claim {
        final SystemScorer.Entry row;
        final List<SystemScorer.Entry> against = new ArrayList<>();
        final List<Bootstrap.Interval> differences = new ArrayList<>();
        SystemScorer.Entry best;
        Bootstrap.Interval fromBest;
        double gain = Double.POSITIVE_INFINITY;

        Claim(SystemScorer.Entry row) {
            this.row = row;
        }

        boolean holds() {
            return gain > 0;
        }
    }

    static Claim claim(SystemScorer.Entry row, List<SystemScorer.Entry> comparators, int p, int m) {
        Claim out = new Claim(row);
        for (SystemScorer.Entry j : comparators) {
            Bootstrap.Interval d = SystemScorer.difference(row, j, p, m, 1);
            out.against.add(j);
            out.differences.add(d);
            out.gain = Math.min(out.gain, d.lo);
            if (out.best == null || j.estimate[p][m] > out.best.estimate[p][m]) {
                out.best = j;
                out.fromBest = d;
            }
        }
        return out;
    }

    /**
     * Rows above their bound on some resample, in flat and same-schedule cells: each "row at cell",
     * empty when none. The rows are every first-decision row: the counts scored here, each with its
     * own-value row, the stored rows, and the refit check, which plays count-based first decisions
     * and so is under the perfect first move's bound.
     */
    static List<String> aboveBound(Ceiling c) {
        List<String> out = new ArrayList<>();
        for (SystemScorer.Entry e : boundChecked(c)) {
            SystemScorer.Entry bound = c.bounds.get(e.bettingOnly ? 1 : 0);
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                if (e.estimate[p] == null) {
                    continue;
                }
                for (int s = 0; s <= SystemScorer.SPREADS; s++) {
                    int m = s == 0 ? SystemScorer.FLAT : SystemScorer.same(s);
                    boolean over = e.estimate[p][m] > bound.estimate[p][m] + 1e-12;
                    for (int k = 0; k < e.boot[p][m].length && !over; k++) {
                        over = e.boot[p][m][k] > bound.boot[p][m][k] + 1e-12;
                    }
                    if (over) {
                        out.add(e.name + " at " + SystemScorer.PENETRATIONS[p] + ", " + (s == 0 ? "flat" : "1-" + s));
                    }
                }
            }
        }
        return out;
    }

    /** The rows aboveBound checks: every first-decision row, the counts', the stored rows' and the refit check's. */
    static List<SystemScorer.Entry> boundChecked(Ceiling c) {
        List<SystemScorer.Entry> rows = new ArrayList<>();
        for (SystemScorer.Result r : c.custom) {
            rows.addAll(r.entries);
        }
        for (SystemScorer.Result r : c.published.results) {
            rows.addAll(r.entries);
        }
        rows.addAll(c.refit);
        return rows;
    }

    /** The cells a claim is made in at each penetration, as {their key in the file's "cells", their label}. */
    static final String[][] CLAIM_CELLS = {{"same", "1-8"}, {"flat", "flat"}, {"own", "own ramp 1-8"}};

    static int claimMetric(String cell) {
        switch (cell) {
            case "flat":
                return SystemScorer.FLAT;
            case "same":
                return SystemScorer.same(SystemScorer.SPREADS);
            default:
                return SystemScorer.own(SystemScorer.SPREADS);
        }
    }

    /** The headline rows the file names for a cell, in its order; empty where it names no cells. */
    static List<SystemScorer.Result> claimed(Ceiling c, String cell) {
        List<SystemScorer.Result> out = new ArrayList<>();
        if (c.counts.cells == null) {
            return out;
        }
        for (String name : c.counts.cells.get(cell)) {
            out.add(c.custom.get(c.counts.indexOf(name)));
        }
        return out;
    }

    // ------------------------------------------------------------------ printing

    static void print(Ceiling c, Path states, PrintStream out) throws IOException {
        int s8 = SystemScorer.same(SystemScorer.SPREADS);
        out.println("# The ceiling count");
        out.println();
        out.printf("State file `%s`, %d valued states of %d shoes, %d resamples of seed %d. The published rows are read "
                        + "from a full-index run's results.ser (%s), not scored again; the counts of our own come from "
                        + "%s%s.%n%n", states, c.st.size, c.st.shoes, c.scorer.boot.resamples, Bootstrap.SEED,
                c.published.line("commit "), c.counts.file, c.blob == null ? "" : ", git blob " + c.blob);
        out.printf("Scored again with these classes and equal to the stored rows to the bit: %s.%n%n",
                String.join(", ", c.checked));
        if (c.counts.registered) {
            out.printf("Registered from the twin %s at commit %s. The counts reported in each cell, at every penetration: "
                    + "flat %s; the same schedule, every spread, %s; the own ramp %s.%n%n", c.counts.twin,
                    c.counts.searchCommit, c.counts.cells.get("flat"), c.counts.cells.get("same"), c.counts.cells.get("own"));
        }
        List<String> over = aboveBound(c);
        out.println("## Bounds");
        out.println();
        out.printf("Checked on every resample, flat and on the same schedule at every spread: every first-decision row, "
                + "%d in all (the counts scored here with their own-value rows, the stored rows and the refit check), "
                + "each playing its own fit against the perfect first move's bound and each betting only against the "
                + "chart's with perfect insurance. Full-play rows are not checked here: their bound needs the split "
                + "margin (COUNT_DESIGN.md, section 2).%n%n", boundChecked(c).size());
        out.println(over.isEmpty() ? "No row checked is above its bound on any resample."
                : "ROWS ABOVE THEIR BOUND, A SCORING BUG: " + String.join("; ", over) + ".");
        out.println();

        SystemScorer.Entry none = c.published.nullCount().entries.get(0);
        SystemScorer.Entry hiLo = c.published.of("Hi-Lo").entries.get(0);
        for (int metric : new int[]{s8, SystemScorer.FLAT}) {
            String cell = metric == SystemScorer.FLAT ? "flat" : "1-" + SystemScorer.SPREADS + " same schedule";
            out.printf("## The ladder, %s%n", cell);
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                out.println();
                out.printf("### %s penetration%n%n", SystemScorer.PENETRATIONS[p]);
                out.println("| Row | EV | 95% CI | Diff from Hi-Lo | 95% CI | Efficiency |");
                out.println("|---|---|---|---|---|---|");
                SystemScorer.Entry bound = c.bounds.get(0);
                List<SystemScorer.Entry> ladder = new ArrayList<>();
                ladder.add(none);
                ladder.add(hiLo);
                ladder.add(bestOf(published(c, false), p, metric));
                ladder.add(bestOf(published(c, true), p, metric));
                for (SystemScorer.Result r : c.custom) {
                    for (SystemScorer.Entry e : r.entries) {
                        if (!e.bettingOnly) {
                            ladder.add(e);
                        }
                    }
                }
                ladder.addAll(c.bounds);
                for (SystemScorer.Entry e : ladder) {
                    if (e.estimate[p] == null) {
                        continue;
                    }
                    Bootstrap.Interval iv = Bootstrap.interval(e.estimate[p][metric], e.boot[p][metric]);
                    Bootstrap.Interval d = SystemScorer.difference(e, hiLo, p, metric, 1);
                    double eff = (e.estimate[p][metric] - none.estimate[p][metric])
                            / (bound.estimate[p][metric] - none.estimate[p][metric]);
                    out.printf("| %s | %s | %s | %s | %s | %s |%n", e.name, ComparisonReport.pct(iv.estimate),
                            ComparisonReport.ci(iv), ComparisonReport.pct(d.estimate), ComparisonReport.ci(d),
                            ComparisonReport.ratio(eff));
                }
            }
            out.println();
        }

        out.println("## Headline claims");
        out.println();
        if (c.counts.cells == null) {
            out.printf("%s names no cells, so no claim is made.%n%n", c.counts.file);
        } else {
            out.println("Only the counts the file names for each cell. Each such row less each of its comparators, paired "
                    + "resample by resample, at the 95% interval's lower end (a one-sided 97.5% bound). The claim holds "
                    + "where every lower end is above zero; the smallest is the gain, a lower bound on the row less the "
                    + "best published row, whichever that is. A cell names at most two rows, so its claims hold together "
                    + "at 95% (Bonferroni); each cell is a statement of its own, not covered jointly with the others, and "
                    + "the headline is 80%, 1-8.");
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                for (String[] cell : CLAIM_CELLS) {
                    int metric = claimMetric(cell[0]);
                    for (SystemScorer.Result r : claimed(c, cell[0])) {
                        SystemScorer.Entry k = row(r);
                        Claim cl = claim(k, comparators(c, r), p, metric);
                        out.printf("%n- %s at %s, %s: gain %s; best comparator %s, difference %s %s; %d comparators; "
                                        + "claim %s.", k.name, SystemScorer.PENETRATIONS[p], cell[1],
                                ComparisonReport.pct(cl.gain), cl.best.name, ComparisonReport.pct(cl.fromBest.estimate),
                                ComparisonReport.ci(cl.fromBest), cl.against.size(), cl.holds() ? "holds" : "not made");
                    }
                }
            }
            out.println();
            out.println();
        }

        out.println("## Every cell of the counts scored here");
        for (SystemScorer.Result r : c.custom) {
            for (SystemScorer.Entry e : r.entries) {
                out.println();
                out.printf("### %s%n%n", e.name);
                out.println("| Penetration | Flat | 1-2 | 1-4 | 1-8 | Own ramp 1-8 | Half fit change, flat | Half fit change, 1-8 "
                        + "| Decision bound |");
                out.println("|---|---|---|---|---|---|---|---|---|");
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    StringBuilder b = new StringBuilder("| " + SystemScorer.PENETRATIONS[p] + " |");
                    for (int m : new int[]{SystemScorer.FLAT, SystemScorer.same(2), SystemScorer.same(4), s8,
                            SystemScorer.own(SystemScorer.SPREADS)}) {
                        Bootstrap.Interval iv = Bootstrap.interval(e.estimate[p][m], e.boot[p][m]);
                        b.append(' ').append(ComparisonReport.pct(iv.estimate)).append(' ').append(ComparisonReport.ci(iv))
                                .append(" |");
                    }
                    if (e == r.entries.get(0)) {
                        b.append(' ').append(ComparisonReport.ci(ComparisonReport.learningCurve(r, p))).append(" |");
                    } else {
                        b.append("  |");
                    }
                    b.append(' ').append(halfChange(e, p)).append(" |");
                    if (e == r.entries.get(0)) {
                        double bound = r.resolution[p].total();
                        b.append(' ').append(ComparisonReport.bound(bound))
                                .append(bound < ComparisonReport.BOUND_TARGET ? " |" : " over the target |");
                    } else {
                        b.append("  |");
                    }
                    out.println(b);
                }
            }
        }
        out.println();

        out.println("## Plays fitted for the bets (the refit check)");
        out.println();
        out.println("| Row | 80%, 1-8 | 95% CI | Less the row fitted flat | 95% CI |");
        out.println("|---|---|---|---|---|");
        int p = SystemScorer.CUTS.length - 1;
        for (SystemScorer.Entry e : c.refit) {
            SystemScorer.Entry flatFit = flatFitOf(c, e);
            Bootstrap.Interval iv = Bootstrap.interval(e.estimate[p][s8], e.boot[p][s8]);
            String d = "";
            if (flatFit != null) {
                Bootstrap.Interval dv = SystemScorer.difference(e, flatFit, p, s8, 1);
                d = ComparisonReport.pct(dv.estimate) + " | " + ComparisonReport.ci(dv);
            } else {
                d = " | ";
            }
            out.printf("| %s | %s | %s | %s |%n", e.name, ComparisonReport.pct(iv.estimate), ComparisonReport.ci(iv), d);
        }
        out.println();

        Path ledger = c.counts.file.toAbsolutePath().normalize().resolveSibling(CustomCounts.LEDGER);
        if (Files.exists(ledger)) {
            out.println("## The ledger");
            out.println();
            out.println("```");
            for (String l : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
                out.println(l);
            }
            out.println("```");
        }
        if (!over.isEmpty()) {
            throw new IllegalStateException("rows above their bound: " + String.join("; ", over));
        }
    }

    /** The learning curve's change at 1-8 for an entry that has it: the half fit's figure less the whole fit's, paired. */
    static String halfChange(SystemScorer.Entry e, int p) {
        int s8 = SystemScorer.same(SystemScorer.SPREADS);
        if (e.halfFitSame == null || Double.isNaN(e.halfFitSame[p])) {
            return "";
        }
        double[] change = new double[e.boot[p][s8].length];
        for (int k = 0; k < change.length; k++) {
            change[k] = e.halfFitSameBoot[p][k] - e.boot[p][s8][k];
        }
        return ComparisonReport.ci(Bootstrap.interval(e.halfFitSame[p] - e.estimate[p][s8], change));
    }

    private static SystemScorer.Entry bestOf(List<SystemScorer.Entry> rows, int p, int m) {
        SystemScorer.Entry best = null;
        for (SystemScorer.Entry e : rows) {
            if (best == null || e.estimate[p][m] > best.estimate[p][m]) {
                best = e;
            }
        }
        return best;
    }

    /** The row fitted flat that a refit row refits: the custom count's, or the stored one for a published count. */
    private static SystemScorer.Entry flatFitOf(Ceiling c, SystemScorer.Entry refit) {
        List<SystemScorer.Result> all = new ArrayList<>(c.custom);
        all.addAll(c.published.results);
        for (SystemScorer.Result r : all) {
            for (SystemScorer.Entry e : r.entries) {
                if (!e.bettingOnly && !e.ownValue && e.count.name.equals(refit.count.name)) {
                    return e;
                }
            }
        }
        return null;
    }
}
