import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ceiling count's figures for the page (PLAN_COMPUTE.md 4.4 to 4.6), worked out from saved
 * files alone: c06c061's results.ser (the published rows, first decisions and full play, and its
 * perfect array), step 7's save (CeilingReport.save: the registered rows at first decisions and
 * the bound rows), step 8's results.ser (the registered rows at full play) and the two
 * registrations. No state file is read, so nothing here is a look at the main file; every file
 * is named by a path given at run time and recorded with its SHA-256.
 *
 * What it works out:
 * - the ladder in twelve views (70, 75 and 80% x flat and 1-8 x first decisions and full play):
 *   the null count, Hi-Lo, the best plain published row, the best published row of any kind, the
 *   registered rows of the cell, F1 bet on its own value, the perfect first move's bound, and at
 *   full play the full-play bound B; each rung with its interval and its paired gap from the rung
 *   below. Full-play intervals are widened by the larger of the two rows' difference ratios
 *   (FULL_INDEX_PLAYS.md section 4: 1.07 for a count the pilot did not measure, which every
 *   custom row is); bound rows are fitted on nothing and are not widened;
 * - the share of the room each row closes, (row - null) / (bound - null) on every resample, with
 *   the first move's bound at first decisions and the full-play bound at full play, B and B + M;
 * - where F1's gap to the bound goes: betting (F1 bet on its own value less F1) and play (the
 *   bound less that row), which add up to the gap;
 * - the headline claims at full play, as CeilingReport makes them at first decisions, each
 *   difference widened (COUNT_DESIGN.md section 5);
 * - the result paragraph of each cell (CeilingParagraphs).
 * And the checks of 4.6 that need no state: the ladder and claim lines it prints again equal the
 * report's (checkPrinted), c06c061's rows equal its peek as printed (checkPeek), step 8's perfect
 * array and Hi-Lo equal c06c061's (samePerfect, sameResult), the full-play bound is at or above the
 * first move's on every resample (boundOrder), and no full-play row is above B + M (aboveFullBound).
 */
final class CeilingResults {

    /** A full-play difference's widening for a count the pilot did not measure (FULL_INDEX_PLAYS.md section 4). */
    static final double DIFFERENCE = 1.07;
    /** The project's standard for a full-play result worked out again in another run: relative difference 1e-12. */
    static final double SAME_RUN = 1e-12;

    private CeilingResults() {
    }

    /** A file's SHA-256, in hex. */
    static String sha256(Path file) throws IOException {
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file), 1 << 20)) {
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 20];
            for (int n = in.read(buf); n > 0; n = in.read(buf)) {
                sha.update(buf, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A line of an identity that starts so, without the start; null if none. */
    static String line(String identity, String start) {
        for (String l : identity.split("\n")) {
            if (l.startsWith(start)) {
                return l.substring(start.length());
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the two contexts

    /**
     * The first-decision context, as CeilingReport held it when it printed: the stored rows, step 7's
     * save and the registration it scored. Refuses a save made beside other stored rows, or of
     * other counts, or of another version of the registration than the one given.
     */
    static CeilingReport.Ceiling firstDecisions(CeilingReport.Published stored, CeilingReport.Saved saved,
                                                CustomCounts.Listing counts) throws IOException {
        String of = line(saved.identity, "stored rows of ");
        if (of == null || !of.equals(stored.line("commit "))) {
            throw new IllegalStateException("the save was made beside the stored rows of " + of + ", not of "
                    + stored.line("commit "));
        }
        String file = line(saved.identity, "counts ");
        int b = file == null ? -1 : file.lastIndexOf(", blob ");
        if (b >= 0) {
            String blob = CeilingParagraphs.gitBlob(Files.readAllBytes(counts.file));
            if (!file.substring(b + ", blob ".length()).equals(blob)) {
                throw new IllegalStateException("the save scored a registration of blob " + file.substring(b + 7)
                        + "; " + counts.file + " has blob " + blob);
            }
        }
        if (saved.custom.size() != counts.entries.size()) {
            throw new IllegalStateException("the save holds " + saved.custom.size() + " counts, the registration "
                    + counts.entries.size());
        }
        for (int k = 0; k < saved.custom.size(); k++) {
            String a = saved.custom.get(k).count.name;
            String e = counts.entries.get(k).count.name;
            if (!a.equals(e)) {
                throw new IllegalStateException("the save's count " + k + " is " + a + ", the registration's " + e);
            }
        }
        CeilingReport.Ceiling c = new CeilingReport.Ceiling();
        c.published = stored;
        c.counts = counts;
        c.custom = saved.custom;
        c.bounds = saved.bounds;
        c.refit = saved.refit;
        c.checked = saved.checked;
        c.r = saved.r;
        c.references = saved.references;
        c.correlations = saved.correlations;
        return c;
    }

    /** The full-play context: the stored rows, step 8's run of the full-play registration, and step 7's bound rows. */
    static final class Full {
        CeilingReport.Published stored;
        CeilingReport.Published run;
        CustomCounts.Listing counts;
        List<SystemScorer.Entry> bounds;
        /** The difference ratios the pilot measured, by count name; every other count takes DIFFERENCE. */
        final Map<String, Double> measured = new LinkedHashMap<>();
    }

    /**
     * Refuses runs that do not pair resample by resample: another state file, other shoes or valued
     * states, other resamples or seed.
     */
    static void requireSameRun(CeilingReport.Published a, CeilingReport.Published b) {
        List<String> wrong = new ArrayList<>();
        for (String start : new String[]{"states ", "shoes ", "resamples "}) {
            String x = a.line(start);
            String y = b.line(start);
            if (x == null || !x.equals(y)) {
                wrong.add(start + x + " against " + y);
            }
        }
        if (!wrong.isEmpty()) {
            throw new IllegalStateException("the runs do not pair: " + String.join("; ", wrong));
        }
    }

    /**
     * The full-play context. Refuses a run of other counts than the registration's, runs that do not
     * pair, and bound rows without the full-play bound at every penetration: step 7 leaves it out
     * where c06c061's perfect array lacks a state (PLAN_COMPUTE.md 4.2), and then it comes from the
     * replay of c06c061, not from here.
     */
    static Full fullPlay(CeilingReport.Published stored, CeilingReport.Published run, CustomCounts.Listing counts,
                         List<SystemScorer.Entry> bounds) {
        requireSameRun(stored, run);
        if (run.results.size() != counts.entries.size()) {
            throw new IllegalStateException("the run holds " + run.results.size() + " counts, the registration "
                    + counts.entries.size());
        }
        for (int k = 0; k < counts.entries.size(); k++) {
            if (!run.results.get(k).count.name.equals(counts.entries.get(k).count.name)) {
                throw new IllegalStateException("the run's count " + k + " is " + run.results.get(k).count.name
                        + ", the registration's " + counts.entries.get(k).count.name);
            }
        }
        if (bounds.size() < 3) {
            throw new IllegalStateException("the bound rows hold no full-play bound: step 7 left it out, so c06c061's "
                    + "perfect array lacks a state; the replay of c06c061 works it out (PLAN_COMPUTE.md 4.2)");
        }
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            if (bounds.get(2).estimate[p] == null) {
                throw new IllegalStateException("the full-play bound is missing at " + SystemScorer.PENETRATIONS[p]
                        + ": c06c061's perfect array lacks a state there (PLAN_COMPUTE.md 4.2)");
            }
        }
        Full f = new Full();
        f.stored = stored;
        f.run = run;
        f.counts = counts;
        f.bounds = bounds;
        return f;
    }

    // ------------------------------------------------------------------ full-play rows

    /**
     * A registered count's full-play headline row: for a count with a betting vector, its row with
     * that vector (fullEntries pair with entries two by two, so it is fullEntries[1], beside
     * entries[2], CeilingReport.row's); its own otherwise. Never F2's play vector alone, the trap
     * FullIndexRun's summary once fell into.
     */
    static SystemScorer.Entry fullRow(SystemScorer.Result r) {
        SystemScorer.Entry e = r.fullEntries.get(r.count.betVector != null ? 1 : 0);
        SystemScorer.Entry first = CeilingReport.row(r);
        if (!e.fullPlay || e.ownValue || e.count != first.count) {
            throw new IllegalStateException(e.name + " is not the full play of " + first.name);
        }
        return e;
    }

    /** A count's own-value row at full play, or null where it was not scored. */
    static SystemScorer.Entry fullOwnValue(SystemScorer.Result r) {
        for (SystemScorer.Entry e : r.fullEntries) {
            if (e.ownValue) {
                return e;
            }
        }
        return null;
    }

    /** The published rows at full play, each playing its own full-play fit: the plain ones, or with their side-count variants. */
    static List<SystemScorer.Entry> fullPublished(CeilingReport.Published stored, boolean withSideCounts) {
        List<SystemScorer.Entry> out = new ArrayList<>();
        for (SystemScorer.Result r : stored.results) {
            if (r.count.systems.isEmpty()) {
                continue;
            }
            for (SystemScorer.Entry e : r.fullEntries) {
                if (!e.bettingOnly && !e.ownValue && (withSideCounts || e.count.base == null)) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** A row's full-play difference ratio: the pilot's for a count it measured, DIFFERENCE otherwise, 1 for a bound or a first-decision row. */
    static double ratio(Full f, SystemScorer.Entry e) {
        if (e.count == null || !e.fullPlay) {
            return 1;
        }
        return f.measured.getOrDefault(e.count.name, DIFFERENCE);
    }

    /** A pair's widening: the larger of the two ratios (COUNT_DESIGN.md section 5). */
    static double widen(Full f, SystemScorer.Entry a, SystemScorer.Entry b) {
        return Math.max(ratio(f, a), ratio(f, b));
    }

    /** The headline claim of a registered count at full play: its full-play row against its comparators' full play, widened. */
    static CeilingReport.Claim fullClaim(Full f, int k, int p, int m) {
        SystemScorer.Result r = f.run.results.get(k);
        boolean all = f.counts.entries.get(k).allComparators();
        return CeilingReport.claim(fullRow(r), fullPublished(f.stored, all), p, m, (a, b) -> widen(f, a, b));
    }

    // ------------------------------------------------------------------ the ladder

    /** One rung: what it is, its row, the ratio its level is widened by, its level and its gap from the rung below. */
    static final class Rung {
        final String role;
        final SystemScorer.Entry entry;
        final double ratio;
        Bootstrap.Interval level;
        Bootstrap.Interval gap;

        Rung(String role, SystemScorer.Entry entry, double ratio) {
            this.role = role;
            this.entry = entry;
            this.ratio = ratio;
        }
    }

    /** The views: play scope, penetration and cell, as keys such as "fd.80.s8" and "full.70.flat". */
    static String view(boolean full, int p, int m) {
        return (full ? "full." : "fd.") + SystemScorer.PENETRATIONS[p].replace("%", "") + "."
                + (m == SystemScorer.FLAT ? "flat" : "s" + SystemScorer.SPREADS);
    }

    /**
     * The ladder in one view, rungs lowest to highest as the page draws them: the null count,
     * Hi-Lo, the best plain published row and the best published row of any kind (each the best by
     * this view's estimate), the registered rows of the cell ("flat" in the flat views, "same"
     * otherwise) by their headline rows, F1 bet on its own value (spread views), the perfect first
     * move's bound, and at full play the full-play bound. Each level's interval is widened by its
     * row's ratio; each gap is paired with the rung below and widened by the larger ratio of the two.
     * f is null for first decisions.
     */
    static List<Rung> ladder(CeilingReport.Ceiling c, Full f, int p, int m) {
        boolean full = f != null;
        String cell = m == SystemScorer.FLAT ? "flat" : "same";
        List<Rung> rungs = new ArrayList<>();
        SystemScorer.Result none = c.published.nullCount();
        SystemScorer.Result hiLo = c.published.of("Hi-Lo");
        rungs.add(new Rung("null", full ? none.fullEntries.get(0) : none.entries.get(0), 1));
        rungs.add(new Rung("hilo", full ? hiLo.fullEntries.get(0) : hiLo.entries.get(0), 1));
        rungs.add(new Rung("best-plain", best(full ? fullPublished(c.published, false) : CeilingReport.published(c, false),
                p, m), 1));
        rungs.add(new Rung("best-any", best(full ? fullPublished(c.published, true) : CeilingReport.published(c, true),
                p, m), 1));
        CustomCounts.Listing counts = full ? f.counts : c.counts;
        List<SystemScorer.Result> results = full ? f.run.results : c.custom;
        SystemScorer.Result f1 = null;
        if (counts.cells != null) {
            for (String name : counts.cells.get(cell)) {
                int k = counts.indexOf(name);
                SystemScorer.Result r = results.get(k);
                rungs.add(new Rung(counts.entries.get(k).family, full ? fullRow(r) : CeilingReport.row(r), 1));
                if ("F1".equals(counts.entries.get(k).family)) {
                    f1 = r;
                }
            }
        }
        if (m != SystemScorer.FLAT && f1 != null) {
            SystemScorer.Entry own = full ? fullOwnValue(f1) : ownValue(f1);
            if (own != null) {
                rungs.add(new Rung("F1-own-value", own, 1));
            }
        }
        rungs.add(new Rung("bound-first", c.bounds.get(0), 1));
        if (full) {
            rungs.add(new Rung("bound-full", f.bounds.get(2), 1));
        }
        List<Rung> out = new ArrayList<>();
        for (Rung r : rungs) {
            Rung w = new Rung(r.role, r.entry, full ? ratio(f, r.entry) : 1);
            w.level = Bootstrap.interval(r.entry.estimate[p][m], w.ratio == 1 ? r.entry.boot[p][m]
                    : Bootstrap.widen(r.entry.boot[p][m], w.ratio));
            if (!out.isEmpty()) {
                Rung below = out.get(out.size() - 1);
                w.gap = SystemScorer.difference(r.entry, below.entry, p, m, Math.max(w.ratio, below.ratio));
            }
            out.add(w);
        }
        return out;
    }

    /** A first-decision count's own-value row, or null. */
    static SystemScorer.Entry ownValue(SystemScorer.Result r) {
        for (SystemScorer.Entry e : r.entries) {
            if (e.ownValue) {
                return e;
            }
        }
        return null;
    }

    private static SystemScorer.Entry best(List<SystemScorer.Entry> rows, int p, int m) {
        SystemScorer.Entry best = null;
        for (SystemScorer.Entry e : rows) {
            if (e.estimate[p] != null && (best == null || e.estimate[p][m] > best.estimate[p][m])) {
                best = e;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the room

    /**
     * The share of the room a row closes, (row - null) / (bound + add - null), on the estimates and
     * on every resample, with its 95% percentile interval; add is M, what better split play could
     * add to the full-play bound, 0 otherwise. At first decisions the bound is the perfect first
     * move's; at full play the full-play bound's, never the first move's, which a full-play row may
     * pass. {estimate, lo, hi}.
     */
    static double[] share(SystemScorer.Entry row, SystemScorer.Entry none, SystemScorer.Entry bound, int p, int m,
                          double add) {
        double[] r = row.boot[p][m];
        double[] s = new double[r.length];
        for (int k = 0; k < s.length; k++) {
            s[k] = (r[k] - none.boot[p][m][k]) / (bound.boot[p][m][k] + add - none.boot[p][m][k]);
        }
        double est = (row.estimate[p][m] - none.estimate[p][m]) / (bound.estimate[p][m] + add - none.estimate[p][m]);
        double[] sorted = s.clone();
        java.util.Arrays.sort(sorted);
        return new double[]{est, Bootstrap.quantile(sorted, 0.025), Bootstrap.quantile(sorted, 0.975)};
    }

    /** The bound a view's shares are taken against: the first move's at first decisions, the full-play bound at full play. */
    static SystemScorer.Entry roomBound(CeilingReport.Ceiling c, Full f) {
        return f == null ? c.bounds.get(0) : f.bounds.get(2);
    }

    /**
     * Where F1's gap to the bound goes, paired: {betting, play, the whole gap}, each an interval;
     * betting is F1 bet on its own value less F1, play the bound less that row, and the two
     * estimates add up to the whole.
     */
    static Bootstrap.Interval[] gapSplit(SystemScorer.Entry f1, SystemScorer.Entry own, SystemScorer.Entry bound, int p,
                                         int m, double widen) {
        return new Bootstrap.Interval[]{SystemScorer.difference(own, f1, p, m, widen),
                SystemScorer.difference(bound, own, p, m, widen), SystemScorer.difference(bound, f1, p, m, widen)};
    }

    // ------------------------------------------------------------------ checks (PLAN_COMPUTE.md 4.6)

    /** A report's sections whose headings start with one of the starts given: heading to heading, blank lines left out, sorted. */
    static Map<String, List<String>> sections(List<String> lines, String... starts) {
        Map<String, List<String>> out = new java.util.TreeMap<>();
        String at = null;
        for (String l : lines) {
            if (l.startsWith("## ")) {
                at = null;
                for (String s : starts) {
                    if (l.startsWith(s)) {
                        at = l;
                        out.put(at, new ArrayList<>());
                    }
                }
                continue;
            }
            if (at != null && !l.trim().isEmpty()) {
                out.get(at).add(l);
            }
        }
        for (List<String> v : out.values()) {
            java.util.Collections.sort(v);
        }
        return out;
    }

    /**
     * Check 1: the ladder and claim sections printed again from the saved figures by
     * CeilingReport's own code must read as the report printed them, line for line whatever their
     * order, as CeilingReport.checkPrinted does for report-final. Refuses, naming the lines that differ.
     */
    static void checkPrinted(CeilingReport.Ceiling c, Path report) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, "UTF-8");
        CeilingReport.printLadder(c, out);
        CeilingReport.printClaims(c, out);
        String[] starts = {"## The ladder", "## Headline claims"};
        Map<String, List<String>> again = sections(java.util.Arrays.asList(new String(bytes.toByteArray(),
                StandardCharsets.UTF_8).split("\n")), starts);
        Map<String, List<String>> printed = sections(Files.readAllLines(report, StandardCharsets.UTF_8), starts);
        List<String> differ = new ArrayList<>();
        if (!again.keySet().equals(printed.keySet())) {
            differ.add("its sections " + printed.keySet() + " against " + again.keySet());
        }
        for (String s : again.keySet()) {
            List<String> theirs = printed.getOrDefault(s, new ArrayList<>());
            if (!again.get(s).equals(theirs)) {
                for (String l : again.get(s)) {
                    if (!theirs.contains(l)) {
                        differ.add("printed again: " + l);
                    }
                }
                for (String l : theirs) {
                    if (!again.get(s).contains(l)) {
                        differ.add("in the report: " + l);
                    }
                }
            }
        }
        if (!differ.isEmpty()) {
            throw new IllegalStateException("the saved figures do not give " + report + "'s ladder and claims; "
                    + differ.size() + " lines differ, among them:\n" + String.join("\n", differ.subList(0,
                    Math.min(8, differ.size()))));
        }
    }

    /**
     * c06c061's rows set beside its peek (runs/full-index/peek-c06c061-final.txt): each row of the
     * peek's first table, 1-8 at 80%, must read as the stored figures print at four places, first
     * decisions and full play, and every stored count must be in it.
     */
    static void checkPeek(CeilingReport.Published stored, Path peek) throws IOException {
        int p = SystemScorer.CUTS.length - 1;
        int s8 = SystemScorer.same(SystemScorer.SPREADS);
        Map<String, String[]> rows = new LinkedHashMap<>();
        boolean table = false;
        for (String l : Files.readAllLines(peek, StandardCharsets.UTF_8)) {
            if (l.startsWith("| Count | First decisions | Full play |")) {
                table = true;
                continue;
            }
            if (table && l.startsWith("|---")) {
                continue;
            }
            if (table && !l.startsWith("| ")) {
                break;
            }
            if (table) {
                String[] f = l.split("\\|");
                rows.put(f[1].trim(), new String[]{f[2].trim(), f[3].trim()});
            }
        }
        List<String> differ = new ArrayList<>();
        for (SystemScorer.Result r : stored.results) {
            String name = r.count.systems.isEmpty() ? "Null count (no count)" : r.count.name;
            String[] printed = rows.get(name);
            String[] again = {String.format(java.util.Locale.ROOT, "%+.4f", 100 * r.entries.get(0).estimate[p][s8]),
                    String.format(java.util.Locale.ROOT, "%+.4f", 100 * r.fullEntries.get(0).estimate[p][s8])};
            if (printed == null || !java.util.Arrays.equals(printed, again)) {
                differ.add(name + ": printed " + (printed == null ? "nothing" : String.join(", ", printed)) + ", stored "
                        + String.join(", ", again));
            }
        }
        if (!differ.isEmpty()) {
            throw new IllegalStateException("the stored rows do not give " + peek + "'s figures: " + String.join("; ",
                    differ));
        }
    }

    /** Whether two figures agree to SAME_RUN, relative; two NaNs agree. */
    static boolean close(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return Double.isNaN(a) && Double.isNaN(b);
        }
        return a == b || Math.abs(a - b) <= SAME_RUN * Math.max(Math.abs(a), Math.abs(b));
    }

    /**
     * Check 2: the perfect array worked out twice, by c06c061 and by step 8 from other classes,
     * must agree to SAME_RUN at every state both hold. {states compared, NaN in a, NaN in b,
     * the largest relative difference}; refuses arrays of other lengths and any state beyond it.
     */
    static double[] samePerfect(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            throw new IllegalStateException("the perfect arrays are not of the same states");
        }
        int compared = 0;
        int nanA = 0;
        int nanB = 0;
        double largest = 0;
        List<String> differ = new ArrayList<>();
        for (int s = 0; s < a.length; s++) {
            nanA += Double.isNaN(a[s]) ? 1 : 0;
            nanB += Double.isNaN(b[s]) ? 1 : 0;
            if (Double.isNaN(a[s]) || Double.isNaN(b[s])) {
                continue;
            }
            compared++;
            double rel = a[s] == b[s] ? 0 : Math.abs(a[s] - b[s]) / Math.max(Math.abs(a[s]), Math.abs(b[s]));
            largest = Math.max(largest, rel);
            if (!close(a[s], b[s]) && differ.size() < 5) {
                differ.add("state " + s + ": " + a[s] + " against " + b[s]);
            }
        }
        if (largest > SAME_RUN) {
            throw new IllegalStateException("the perfect arrays differ beyond " + SAME_RUN + " (largest " + largest
                    + "): " + String.join("; ", differ));
        }
        return new double[]{compared, nanA, nanB, largest};
    }

    /**
     * Check 3: a count's full-play entries in two runs, every estimate and resample at every
     * penetration either holds, to SAME_RUN; the first difference, or null.
     */
    static String sameFullPlay(SystemScorer.Result a, SystemScorer.Result b) {
        if (a.fullEntries.size() < 1 || b.fullEntries.size() < 1) {
            return "no full-play entries";
        }
        for (int e = 0; e < Math.min(2, Math.min(a.fullEntries.size(), b.fullEntries.size())); e++) {
            SystemScorer.Entry x = a.fullEntries.get(e);
            SystemScorer.Entry y = b.fullEntries.get(e);
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                if ((x.estimate[p] == null) != (y.estimate[p] == null)) {
                    return x.name + " at " + SystemScorer.PENETRATIONS[p] + ": scored in one run only";
                }
                if (x.estimate[p] == null) {
                    continue;
                }
                for (int m = 0; m < SystemScorer.METRICS; m++) {
                    if (!close(x.estimate[p][m], y.estimate[p][m])) {
                        return x.name + "'s estimate at " + SystemScorer.PENETRATIONS[p] + ", metric " + m + ": "
                                + x.estimate[p][m] + " against " + y.estimate[p][m];
                    }
                    for (int r = 0; r < x.boot[p][m].length; r++) {
                        if (!close(x.boot[p][m][r], y.boot[p][m][r])) {
                            return x.name + "'s resample " + r + " at " + SystemScorer.PENETRATIONS[p] + ", metric " + m;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** The cells the bound checks cover: flat, then the same schedule at every spread. */
    static int[] boundCells() {
        int[] out = new int[SystemScorer.SPREADS + 1];
        out[0] = SystemScorer.FLAT;
        for (int s = 1; s <= SystemScorer.SPREADS; s++) {
            out[s] = SystemScorer.same(s);
        }
        return out;
    }

    /**
     * Check 4: the full-play bound at or above the first move's, on the estimate and every resample,
     * in every cell at every penetration; each place it is not, as "penetration, cell".
     */
    static List<String> boundOrder(SystemScorer.Entry firstMove, SystemScorer.Entry full) {
        List<String> out = new ArrayList<>();
        int[] cells = boundCells();
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            for (int s = 0; s < cells.length; s++) {
                int m = cells[s];
                boolean below = full.estimate[p][m] < firstMove.estimate[p][m] - 1e-12;
                for (int r = 0; r < full.boot[p][m].length && !below; r++) {
                    below = full.boot[p][m][r] < firstMove.boot[p][m][r] - 1e-12;
                }
                if (below) {
                    out.add(SystemScorer.PENETRATIONS[p] + ", " + (s == 0 ? "flat" : "1-" + s));
                }
            }
        }
        return out;
    }

    /** Check 4, state by state, given both arrays: the states where perfect play of the dealt hand is below the perfect first move. */
    static List<Integer> perStateOrder(double[] perfectPlay, double[] firstMove) {
        List<Integer> out = new ArrayList<>();
        for (int s = 0; s < perfectPlay.length; s++) {
            if (perfectPlay[s] < firstMove[s] - 1e-12) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Check 5: each full-play row at or under the full-play bound plus margin[p][s] (spread s, s 1 for
     * flat) on the estimate and every resample: margin is S x mean(delta), with the largest restart
     * gain added to delta (SplitMargin.margins). A row above it means a scoring bug or split gains
     * the ascent missed, and stops the page for that row: each as "row at penetration, cell".
     */
    static List<String> aboveFullBound(List<SystemScorer.Entry> rows, SystemScorer.Entry bound, double[][] margin) {
        List<String> out = new ArrayList<>();
        int[] cells = boundCells();
        for (SystemScorer.Entry e : rows) {
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                if (e.estimate[p] == null) {
                    continue;
                }
                for (int s = 0; s < cells.length; s++) {
                    int m = cells[s];
                    double add = margin[p][Math.max(1, s)];
                    boolean over = e.estimate[p][m] > bound.estimate[p][m] + add + 1e-12;
                    for (int r = 0; r < e.boot[p][m].length && !over; r++) {
                        over = e.boot[p][m][r] > bound.boot[p][m][r] + add + 1e-12;
                    }
                    if (over) {
                        out.add(e.name + " at " + SystemScorer.PENETRATIONS[p] + ", " + (s == 0 ? "flat" : "1-" + s));
                    }
                }
            }
        }
        return out;
    }

    /** The full-play rows whose estimate is above the full-play bound B in some cell: listed by name, between B and B + M. */
    static List<String> aboveB(List<SystemScorer.Entry> rows, SystemScorer.Entry bound) {
        List<String> out = new ArrayList<>();
        int[] cells = boundCells();
        for (SystemScorer.Entry e : rows) {
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                for (int s = 0; e.estimate[p] != null && s < cells.length; s++) {
                    if (e.estimate[p][cells[s]] > bound.estimate[p][cells[s]]) {
                        out.add(e.name + " at " + SystemScorer.PENETRATIONS[p] + ", " + (s == 0 ? "flat" : "1-" + s));
                    }
                }
            }
        }
        return out;
    }
}
