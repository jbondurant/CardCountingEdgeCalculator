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
 * registrations. No main state file is read, so nothing here is a look at the main file; every
 * file is named by a path given at run time and recorded with its SHA-256.
 *
 * Three modes (main):
 * - full writes the full-play report, runs/report-ceiling-full-SHA.md;
 * - data writes runs/site/ceiling-SHA.json, the one file the site generator reads for these
 *   figures, and nothing else: no HTML, no SVG;
 * - optimism scores the registered rows on the twin, the one state file any mode reads
 *   (Optimism), and writes runs/custom-count/optimism-SHA.properties and .md.
 *
 * What it works out:
 * - the ladder in twelve views (70, 75 and 80% x flat and 1-8 x first decisions and full play):
 *   the null count, Hi-Lo, the best plain published row, the best published row of any kind, the
 *   registered rows of the cell, F1 bet on its own value, the perfect first move's bound, and at
 *   full play the full-play bound B; each rung with its interval and its paired gap from the rung
 *   below. Full-play intervals are widened by the larger of the two rows' difference ratios,
 *   read from the pilot given (--widening, pilot-1417-100.txt) by the rule SiteExport reads it
 *   with (SiteExport.Widening, FULL_INDEX_PLAYS.md section 4): a count the pilot measured takes
 *   its own, any other the largest ratio the pilot measured, 1.07 in pilot-1417-100.txt, which
 *   every custom row takes. So the two write the same full.P.S.f1 from the same pilot, and the
 *   page's build, which stops when they differ, needs no constant here to agree with a file
 *   there. Bound rows are fitted on nothing and are not widened;
 * - the share of the room each row closes, (row - null) / (bound - null) on every resample, with
 *   the first move's bound at first decisions and the full-play bound at full play, B and B + M;
 * - where F1's gap to the bound goes: betting (F1 bet on its own value less F1) and play (the
 *   bound less that row), which add up to the gap;
 * - the headline claims at full play, as CeilingReport makes them at first decisions, each
 *   difference widened (COUNT_DESIGN.md section 5);
 * - the result paragraph of each cell (CeilingParagraphs).
 * And the checks of 4.6 that need no state: the ladder and claim lines it prints again equal the
 * report's (checkPrinted, and checkFullPrinted for the full report), c06c061's rows equal its peek
 * as printed (checkPeek), step 8's perfect array and Hi-Lo equal c06c061's to 1e-12 (samePerfect,
 * sameFullPlay), the full-play bound is at or above the first move's on every resample
 * (boundOrder), and no full-play row is above B + M (aboveFullBound), the rows between B and B + M
 * named (aboveB). Check 4's state-by-state half (perStateOrder: perfect play of the dealt hand at or
 * above the perfect first move at every state) needs the main file's first-move values, which no
 * saved file holds; it is written and tested here but not called, since this class reads no main
 * state file. Check 6 is the optimism mode's.
 */
final class CeilingResults {

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
        /**
         * The pilot's ratios (--widening), read as SiteExport reads them: a count it measured takes
         * its own, every other count the largest it measured. Null until given; no full-play
         * interval is worked out without it.
         */
        SiteExport.Widening widening;

        /** The ratio for a count the pilot did not measure, every custom row's: the largest it measured. */
        double unmeasured() {
            if (widening == null) {
                throw new IllegalStateException("a full-play interval is widened by the pilot's ratios, and none was given: "
                        + "give --widening runs/full-index/pilot-1417-100.txt, the file SiteExport is given "
                        + "(FULL_INDEX_PLAYS.md section 4)");
            }
            return widening.difference;
        }
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

    /**
     * A row's full-play difference ratio: SiteExport.Widening's level at the 1-8 schedule, the
     * spread the ladder and the claims are made at (the pilot's own for a count it measured, the
     * largest it measured otherwise); 1 for a bound or a first-decision row.
     */
    static double ratio(Full f, SystemScorer.Entry e) {
        if (e.count == null || !e.fullPlay) {
            return 1;
        }
        f.unmeasured();
        return f.widening.level(e.count, SystemScorer.SPREADS);
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

    // ------------------------------------------------------------------ everything read, by path

    /** The files a mode reads, each with its SHA-256, and what they hold. */
    static final class Inputs {
        final Map<String, Path> paths = new LinkedHashMap<>();
        final Map<String, String> sha = new LinkedHashMap<>();
        CeilingReport.Ceiling first;
        Full full;
        /** SplitMargin --out's rows, or null. */
        List<SplitMargin.Row> split;
        /** The optimism run's figures, or null. */
        java.util.Properties optimism;
        String commit;

        void add(String key, Path p) throws IOException {
            paths.put(key, p);
            sha.put(key, sha256(p));
        }
    }

    /**
     * Reads every file given: c06c061's stored rows, step 7's save and report, the registration,
     * step 8's run and the full-play registration, the peek, and the split margin's and the
     * optimism run's files when given; builds both contexts, and runs checks 1 to 4 and the peek,
     * refusing at the first that fails. args: --stored, --saved, --report, --counts, --fullplay-run,
     * --fullplay-counts, --peek, then optionally --split-margin, --optimism, --paragraphs.
     */
    static Inputs read(Map<String, Path> args, String commit) throws Exception {
        Inputs in = new Inputs();
        in.commit = commit;
        for (String k : new String[]{"stored", "saved", "report", "counts", "fullplay-run", "fullplay-counts", "peek",
                "widening"}) {
            if (args.get(k) == null) {
                throw new IllegalArgumentException("give --" + k);
            }
        }
        for (Map.Entry<String, Path> e : args.entrySet()) {
            if (e.getValue() != null && !e.getKey().equals("out")) {
                in.add(e.getKey(), e.getValue());
            }
        }
        CeilingReport.Published stored = CeilingReport.read(args.get("stored"));
        CeilingReport.Saved saved = CeilingReport.readSaved(args.get("saved"));
        in.first = firstDecisions(stored, saved, CustomCounts.read(args.get("counts")));
        checkPrinted(in.first, args.get("report"));
        checkPeek(stored, args.get("peek"));
        CeilingReport.Published run = CeilingReport.read(args.get("fullplay-run"));
        in.full = fullPlay(stored, run, CustomCounts.read(args.get("fullplay-counts")), saved.bounds);
        in.full.widening = SiteExport.Widening.read(args.get("widening"));
        samePerfect(stored.perfect, run.perfect);
        String hiLo = sameFullPlay(stored.of("Hi-Lo"), run.of("Hi-Lo"));
        if (hiLo != null) {
            throw new IllegalStateException("Hi-Lo at full play differs between the runs beyond " + SAME_RUN + ": " + hiLo);
        }
        List<String> order = boundOrder(saved.bounds.get(0), saved.bounds.get(2));
        if (!order.isEmpty()) {
            throw new IllegalStateException("the full-play bound is below the first move's at " + order);
        }
        if (args.get("split-margin") != null) {
            in.split = SplitMargin.readOut(args.get("split-margin"));
        }
        if (args.get("optimism") != null) {
            in.optimism = new java.util.Properties();
            try (java.io.Reader r = Files.newBufferedReader(args.get("optimism"), StandardCharsets.UTF_8)) {
                in.optimism.load(r);
            }
        }
        return in;
    }

    /** The largest restart gain in the split margin's rows, 0 where none was run. */
    static double restartGain(List<SplitMargin.Row> rows) {
        double g = 0;
        for (SplitMargin.Row r : rows) {
            if (!Double.isNaN(r.restartGain)) {
                g = Math.max(g, r.restartGain);
            }
        }
        return g;
    }

    /**
     * M at each penetration and spread ([p][S], S 1 for flat): the rearranged margin with the largest
     * restart gain added to every state's delta when tight, else S x mean(delta) with it added,
     * the margin check 5 holds every row to. Zero everywhere without a split margin.
     */
    static double[][] margin(List<SplitMargin.Row> rows, boolean tight) {
        double[][] out = new double[SystemScorer.CUTS.length][SystemScorer.SPREADS + 1];
        if (rows == null) {
            return out;
        }
        double g = restartGain(rows);
        for (int p = 0; p < out.length; p++) {
            double[][] m = SplitMargin.margins(rows, p, null, g);
            for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                out[p][s] = m[s][tight ? 1 : 0];
            }
        }
        return out;
    }

    /** Every full-play row the page may show: c06c061's and step 8's, each count's own and its variant's. */
    static List<SystemScorer.Entry> fullRows(Full f) {
        List<SystemScorer.Entry> out = new ArrayList<>();
        for (CeilingReport.Published p : new CeilingReport.Published[]{f.stored, f.run}) {
            for (SystemScorer.Result r : p.results) {
                for (SystemScorer.Entry e : r.fullEntries) {
                    if (!e.bettingOnly) {
                        out.add(e);
                    }
                }
            }
        }
        return out;
    }

    /** An entry with metric m at penetration p alone, its resamples spread ratio times as far from their mean. */
    static SystemScorer.Entry widened(SystemScorer.Entry e, int p, int m, double ratio) {
        SystemScorer.Entry w = new SystemScorer.Entry(e.name, e.count, e.bettingOnly, e.fullPlay, e.ownValue);
        w.estimate[p] = e.estimate[p];
        w.boot[p] = new double[SystemScorer.METRICS][];
        w.boot[p][m] = ratio == 1 ? e.boot[p][m] : Bootstrap.widen(e.boot[p][m], ratio);
        return w;
    }

    /** The floor at full play (CeilingReport.floor), each row's resamples widened by its ratio first. */
    static double[] fullFloor(Full f, SystemScorer.Entry row, List<SystemScorer.Entry> family, int p, int m,
                              SystemScorer.Entry[] from) {
        List<SystemScorer.Entry> w = new ArrayList<>();
        for (SystemScorer.Entry e : family) {
            w.add(widened(e, p, m, ratio(f, e)));
        }
        return CeilingReport.floor(widened(row, p, m, ratio(f, row)), w, p, m, from);
    }

    // ------------------------------------------------------------------ the full-play report

    /** The cells of a ladder: 1-8 on the same schedule, then flat. */
    static final int[] LADDER_CELLS = {SystemScorer.same(SystemScorer.SPREADS), SystemScorer.FLAT};

    /**
     * The full-play report (runs/report-ceiling-full-SHA.md): the sources and checks, the ladder at
     * full play in each cell and penetration with the share of the room, the headline claims at full
     * play, the rows set against B + M, and where F1's gap goes. Each figure printed as the ceiling
     * report prints its own (ComparisonReport.pct, ci).
     */
    static void printFull(Inputs in, PrintStream out) {
        CeilingReport.Ceiling c = in.first;
        Full f = in.full;
        out.println("# The ceiling count at full play");
        out.println();
        out.printf("By CeilingResults at commit %s, from saved files alone; no state file is read.%n%n", in.commit);
        for (String k : in.paths.keySet()) {
            out.printf("- %s: `%s`, SHA-256 %s%n", k, in.paths.get(k), in.sha.get(k));
        }
        out.println();
        double[] perfect = samePerfect(f.stored.perfect, f.run.perfect);
        out.printf("Checks passed: the ladder and claim lines printed again from the save equal the report's; c06c061's "
                        + "rows equal its peek; the perfect array worked out by both runs agrees on %d states to %s relative "
                        + "(largest %.3g; %d and %d states without a value); Hi-Lo at full play agrees to %s; the full-play "
                        + "bound is at or above the first move's on every resample.%n%n", (int) perfect[0], SAME_RUN,
                perfect[3], (int) perfect[1], (int) perfect[2], SAME_RUN);
        out.printf("Full-play intervals are widened by each count's difference ratio, %s for a count the pilot did not "
                + "measure, every custom row among them; a pair by the larger of its two; the bounds are fitted on "
                + "nothing and not widened (FULL_INDEX_PLAYS.md section 4). The full-play F1 in the flat cells is F1 at "
                + "1-8 itself, a looser lower bound there than F1 flat is at first decisions. The ratios are those of the "
                + "pilot %s.%n%n", f.unmeasured(), java.nio.file.Paths.get(f.widening.from).getFileName());
        double[][] mTight = margin(in.split, true);
        double[][] mCrude = margin(in.split, false);
        out.println(in.split == null ? "No split margin was given: M is not added to the full-play bound here."
                : String.format("M, what better split play could add to the full-play bound, is the rearranged margin "
                + "with the largest restart gain (%s%%) added to every state's delta (SplitMargin), measured from below.",
                ComparisonReport.pct(restartGain(in.split))));
        for (int m : LADDER_CELLS) {
            String cell = m == SystemScorer.FLAT ? "flat" : "1-" + SystemScorer.SPREADS + " same schedule";
            out.printf("%n## The ladder at full play, %s%n", cell);
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                int s = m == SystemScorer.FLAT ? 1 : SystemScorer.SPREADS;
                out.printf("%n### %s penetration%n%n", SystemScorer.PENETRATIONS[p]);
                out.println("| Row | EV | 95% CI | Gap from the previous row | 95% CI | Share of the room | 95% interval |");
                out.println("|---|---|---|---|---|---|---|");
                SystemScorer.Entry none = c.published.nullCount().fullEntries.get(0);
                SystemScorer.Entry bound = roomBound(c, f);
                for (CeilingResults.Rung r : ladder(c, f, p, m)) {
                    double[] sh = share(r.entry, none, bound, p, m, 0);
                    out.printf("| %s | %s | %s | %s | %s | %s | [%s, %s] |%n", r.entry.name + (r.role.equals("bound-first")
                                    ? " (applies to first-decision play only)" : ""), ComparisonReport.pct(r.level.estimate),
                            ComparisonReport.ci(r.level), r.gap == null ? "" : ComparisonReport.pct(r.gap.estimate),
                            r.gap == null ? "" : ComparisonReport.ci(r.gap), ComparisonReport.ratio(sh[0]),
                            ComparisonReport.ratio(sh[1]), ComparisonReport.ratio(sh[2]));
                }
                if (in.split != null) {
                    out.printf("%nB + M: %s, M %s (S x mean delta: %s). Share of the room as a range, [with B + M, with "
                                    + "B], for the full-play F1: [%s, %s].%n", ComparisonReport.pct(bound.estimate[p][m]
                                    + mTight[p][s]), ComparisonReport.pct(mTight[p][s]), ComparisonReport.pct(mCrude[p][s]),
                            ComparisonReport.ratio(share(fullRow(f.run.results.get(0)), none, bound, p, m, mTight[p][s])[0]),
                            ComparisonReport.ratio(share(fullRow(f.run.results.get(0)), none, bound, p, m, 0)[0]));
                }
            }
        }
        printFullClaims(f, out);
        List<SystemScorer.Entry> rows = fullRows(f);
        List<String> over = aboveFullBound(rows, f.bounds.get(2), mCrude);
        out.printf("%n## Full-play rows and the full-play bound%n%n");
        out.println(over.isEmpty() ? "No full-play row is above B + S x mean(delta), with the largest restart gain added "
                + "to delta, on any resample." : "ROWS ABOVE B + M, A SCORING BUG OR SPLIT GAINS THE ASCENT MISSED; THE "
                + "PAGE STOPS FOR EACH: " + String.join("; ", over) + ".");
        List<String> between = aboveB(rows, f.bounds.get(2));
        out.println(between.isEmpty() ? "No full-play row's estimate is above B." : "Estimates above B, within B + M: "
                + String.join("; ", between) + ".");
        out.printf("%n## Where F1's gap to the bound goes, 80%%, 1-8%n%n| Scope | Betting | Play | Whole gap |%n"
                + "|---|---|---|---|%n");
        int p = SystemScorer.CUTS.length - 1;
        int s8 = SystemScorer.same(SystemScorer.SPREADS);
        int f1 = c.counts.indexOf(c.counts.cells.get("same").get(0));
        SystemScorer.Result f1Full = f.run.results.get(f.counts.indexOf(f.counts.cells.get("same").get(0)));
        SystemScorer.Entry own = ownValue(c.custom.get(f1));
        SystemScorer.Entry ownFull = fullOwnValue(f1Full);
        Bootstrap.Interval[][] splits = {own == null ? null : gapSplit(CeilingReport.row(c.custom.get(f1)), own,
                c.bounds.get(0), p, s8, 1), ownFull == null ? null : gapSplit(fullRow(f1Full), ownFull, f.bounds.get(2),
                p, s8, f.unmeasured())};
        for (int k = 0; k < 2; k++) {
            if (splits[k] != null) {
                out.printf("| %s | %s %s | %s %s | %s %s |%n", k == 0 ? "First decisions" : "Full play",
                        ComparisonReport.pct(splits[k][0].estimate), ComparisonReport.ci(splits[k][0]),
                        ComparisonReport.pct(splits[k][1].estimate), ComparisonReport.ci(splits[k][1]),
                        ComparisonReport.pct(splits[k][2].estimate), ComparisonReport.ci(splits[k][2]));
            }
        }
        if (!over.isEmpty()) {
            throw new IllegalStateException("full-play rows above B + M: " + String.join("; ", over));
        }
    }

    /** The headline claims at full play, in CeilingReport's words, each difference widened. */
    static void printFullClaims(Full f, PrintStream out) {
        out.printf("%n## Headline claims at full play%n%n");
        out.println("As at first decisions (CeilingReport), each difference widened by the larger of the two counts' "
                + "ratios; the comparators are the published rows playing their own full-play fits.");
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            for (String[] cell : CeilingReport.CLAIM_CELLS) {
                int m = CeilingReport.claimMetric(cell[0]);
                if (f.counts.cells == null) {
                    continue;
                }
                for (String name : f.counts.cells.get(cell[0])) {
                    int k = f.counts.indexOf(name);
                    CeilingReport.Claim cl = fullClaim(f, k, p, m);
                    SystemScorer.Entry[] from = new SystemScorer.Entry[1];
                    double[] fl = fullFloor(f, cl.row, cl.against, p, m, from);
                    out.printf("%n- %s at %s, %s: gain %s; best comparator %s, difference %s %s; %d comparators; claim %s. "
                                    + "Floor %s, from %s (max-|t| %.2f over %d rows).", cl.row.name,
                            SystemScorer.PENETRATIONS[p], cell[1], ComparisonReport.pct(cl.gain), cl.best.name,
                            ComparisonReport.pct(cl.fromBest.estimate), ComparisonReport.ci(cl.fromBest),
                            cl.against.size(), cl.holds() ? "holds" : "not made", ComparisonReport.pct(fl[0]),
                            from[0].name, fl[1], cl.against.size() + 1);
                }
            }
        }
        out.println();
    }

    // ------------------------------------------------------------------ the data file

    /** A small JSON writer: objects and arrays by hand, numbers as Java prints a double (full precision), NaN as null. */
    static final class Json {
        final StringBuilder b = new StringBuilder();
        private boolean first = true;

        Json open(String key, char bracket) {
            key(key);
            b.append(bracket);
            first = true;
            return this;
        }

        Json close(char bracket) {
            b.append(bracket);
            first = false;
            return this;
        }

        Json key(String key) {
            if (!first) {
                b.append(',');
            }
            first = false;
            if (key != null) {
                str(key);
                b.append(':');
            }
            return this;
        }

        Json num(String key, double v) {
            key(key);
            b.append(Double.isNaN(v) || Double.isInfinite(v) ? "null" : Double.toString(v));
            return this;
        }

        Json text(String key, String v) {
            key(key);
            if (v == null) {
                b.append("null");
            } else {
                str(v);
            }
            return this;
        }

        Json bool(String key, boolean v) {
            key(key);
            b.append(v);
            return this;
        }

        Json interval(String key, Bootstrap.Interval iv) {
            open(key, '{');
            num("v", iv.estimate).num("se", iv.se).num("lo", iv.lo).num("hi", iv.hi).bool("basic", iv.basic);
            return close('}');
        }

        private void str(String s) {
            b.append('"');
            for (char ch : s.toCharArray()) {
                if (ch == '"' || ch == '\\') {
                    b.append('\\').append(ch);
                } else if (ch < 0x20) {
                    b.append(String.format("\\u%04x", (int) ch));
                } else {
                    b.append(ch);
                }
            }
            b.append('"');
        }
    }

    /** The source keys a view's figures come from. */
    static String[] sources(boolean full) {
        return full ? new String[]{"stored", "saved", "fullplay-run"} : new String[]{"stored", "saved"};
    }

    /**
     * The data file (runs/site/ceiling-SHA.json), and nothing else for the page: every figure at full
     * precision, with the keys of the sources it comes from; the sources by path and SHA-256; the
     * classes' commit. The ladder in its twelve views with each rung's level, gap and share; M; the
     * claims at both play scopes in every cell and penetration; F1's gap split; R and each custom row's
     * BC and IC; the result paragraphs, picked and filled (CeilingParagraphs); and the checks passed.
     */
    static String data(Inputs in, CeilingParagraphs.Texts paragraphs) {
        CeilingReport.Ceiling c = in.first;
        Full f = in.full;
        Json j = new Json();
        j.b.append('{');
        j.text("about", "The ceiling count's figures for the results page, written by CeilingResults data from saved "
                + "files alone (PLAN_COMPUTE.md 4.4 to 4.6). Fractions of a unit a round; intervals are 95%, widened at "
                + "full play by each pair's larger difference ratio, bounds not widened.");
        j.text("commit", in.commit);
        j.open("sources", '{');
        for (String k : in.paths.keySet()) {
            j.open(k, '{').text("path", in.paths.get(k).toString()).text("sha256", in.sha.get(k)).close('}');
        }
        j.close('}');
        double[][] mTight = margin(in.split, true);
        j.open("ladder", '{');
        for (boolean full : new boolean[]{false, true}) {
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                for (int m : LADDER_CELLS) {
                    int s = m == SystemScorer.FLAT ? 1 : SystemScorer.SPREADS;
                    Full ff = full ? f : null;
                    SystemScorer.Entry none = full ? c.published.nullCount().fullEntries.get(0)
                            : c.published.nullCount().entries.get(0);
                    SystemScorer.Entry bound = roomBound(c, ff);
                    j.open(view(full, p, m), '[');
                    for (Rung r : ladder(c, ff, p, m)) {
                        j.open(null, '{').text("role", r.role).text("name", r.entry.name).num("ratio", r.ratio);
                        j.interval("level", r.level);
                        if (r.gap != null) {
                            j.interval("gap", r.gap);
                        }
                        double[] sh = share(r.entry, none, bound, p, m, 0);
                        j.open("share", '{').num("v", sh[0]).num("lo", sh[1]).num("hi", sh[2]).close('}');
                        if (full && in.split != null) {
                            double[] shM = share(r.entry, none, bound, p, m, mTight[p][s]);
                            j.open("shareWithM", '{').num("v", shM[0]).num("lo", shM[1]).num("hi", shM[2]).close('}');
                        }
                        j.open("src", '[');
                        for (String k : sources(full)) {
                            j.text(null, k);
                        }
                        j.close(']').close('}');
                    }
                    j.close(']');
                }
            }
        }
        j.close('}');
        if (in.split != null) {
            double[][] mCrude = margin(in.split, false);
            j.open("M", '{').num("restartGain", restartGain(in.split)).text("src", "split-margin");
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                j.open(SystemScorer.PENETRATIONS[p].replace("%", ""), '{');
                for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                    j.open(s == 1 ? "flat" : "s" + s, '{').num("rearranged", mTight[p][s]).num("crude", mCrude[p][s])
                            .close('}');
                }
                j.close('}');
            }
            j.close('}');
        }
        claims(j, in);
        gapSplits(j, in);
        j.open("correlations", '{').num("R", c.r).text("src", "saved").open("rows", '{');
        for (Map.Entry<String, double[]> e : c.correlations.entrySet()) {
            j.open(e.getKey(), '{').num("BC", e.getValue()[0]).num("IC", e.getValue()[1]).close('}');
        }
        j.close('}').close('}');
        if (paragraphs != null) {
            paragraphs(j, in, paragraphs);
        }
        j.close('}');
        return j.b.toString();
    }

    /** The claims at both play scopes, every penetration and cell the registrations name, into the data file. */
    static void claims(Json j, Inputs in) {
        CeilingReport.Ceiling c = in.first;
        Full f = in.full;
        j.open("claims", '{');
        for (boolean full : new boolean[]{false, true}) {
            CustomCounts.Listing counts = full ? f.counts : c.counts;
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                for (String[] cell : CeilingReport.CLAIM_CELLS) {
                    int m = CeilingReport.claimMetric(cell[0]);
                    for (String name : counts.cells == null ? new ArrayList<String>() : counts.cells.get(cell[0])) {
                        int k = counts.indexOf(name);
                        CeilingReport.Claim cl;
                        SystemScorer.Entry[] from = new SystemScorer.Entry[1];
                        double[] fl;
                        if (full) {
                            cl = fullClaim(f, k, p, m);
                            fl = fullFloor(f, cl.row, cl.against, p, m, from);
                        } else {
                            SystemScorer.Result r = c.custom.get(k);
                            List<SystemScorer.Entry> family = CeilingReport.comparators(c, r);
                            cl = CeilingReport.claim(CeilingReport.row(r), family, p, m);
                            fl = CeilingReport.floor(cl.row, family, p, m, from);
                        }
                        j.open((full ? "full." : "fd.") + SystemScorer.PENETRATIONS[p].replace("%", "") + "." + cell[0]
                                + "." + counts.entries.get(k).family, '{');
                        j.text("row", cl.row.name).num("ev", cl.row.estimate[p][m]).num("gain", cl.gain)
                                .bool("holds", cl.holds()).text("best", cl.best.name).num("bestEv", cl.best.estimate[p][m]);
                        j.interval("fromBest", cl.fromBest).num("comparators", cl.against.size());
                        j.open("floor", '{').num("v", fl[0]).text("from", from[0].name).num("maxT", fl[1]).close('}');
                        j.open("src", '[');
                        for (String s : sources(full)) {
                            j.text(null, s);
                        }
                        j.close(']').close('}');
                    }
                }
            }
        }
        j.close('}');
    }

    /** F1's gap split at 80%, 1-8, at first decisions and at full play, into the data file. */
    static void gapSplits(Json j, Inputs in) {
        CeilingReport.Ceiling c = in.first;
        Full f = in.full;
        int p = SystemScorer.CUTS.length - 1;
        int s8 = SystemScorer.same(SystemScorer.SPREADS);
        j.open("gapSplit", '{');
        int k = c.counts.indexOf(c.counts.cells.get("same").get(0));
        SystemScorer.Entry own = ownValue(c.custom.get(k));
        if (own != null) {
            Bootstrap.Interval[] g = gapSplit(CeilingReport.row(c.custom.get(k)), own, c.bounds.get(0), p, s8, 1);
            j.open(view(false, p, s8), '{').interval("betting", g[0]).interval("play", g[1]).interval("whole", g[2])
                    .close('}');
        }
        SystemScorer.Result r = f.run.results.get(f.counts.indexOf(f.counts.cells.get("same").get(0)));
        SystemScorer.Entry ownFull = fullOwnValue(r);
        if (ownFull != null) {
            Bootstrap.Interval[] g = gapSplit(fullRow(r), ownFull, f.bounds.get(2), p, s8, f.unmeasured());
            j.open(view(true, p, s8), '{').interval("betting", g[0]).interval("play", g[1]).interval("whole", g[2])
                    .close('}');
        }
        j.close('}');
    }

    /** The optimism run's figure for a bracket, by family and cell at 80%; NaN where it has none. */
    static double optimism(java.util.Properties o, String what, String family, String cell) {
        if (o == null) {
            return Double.NaN;
        }
        String v = o.getProperty(what + "." + family.replace(' ', '_') + ".80." + cell);
        return v == null ? Double.NaN : Double.parseDouble(v);
    }

    /**
     * The result paragraphs into the data file: per cell, the letter the rule picked, the rows the
     * claim holds for and those that qualify, the row named, and the text with its brackets filled;
     * where a bracket's figure is not yet worked out (the optimism run's), the text is left out and
     * what is missing is said, so the page prints the cell as pending, never a bracket.
     */
    static void paragraphs(Json j, Inputs in, CeilingParagraphs.Texts t) {
        j.open("paragraphs", '{').text("blob", t.blob).text("commit", CeilingParagraphs.COMMIT);
        for (String cell : new String[]{"same", "flat"}) {
            CeilingParagraphs.Choice ch = CeilingParagraphs.choose(in.first, cell, SystemScorer.CUTS.length - 1);
            String key = cell.equals("flat") ? "flat" : "s8";
            Map<String, Double> more = new LinkedHashMap<>();
            more.put("OPTIMISM", optimism(in.optimism, "optimism", ch.family, key));
            more.put("TWIN_GAIN", optimism(in.optimism, "twinGain", ch.family, key));
            j.open(cell, '{').text("letter", String.valueOf(ch.paragraph)).text("row", ch.family);
            j.open("holdsFor", '[');
            for (String h : ch.holdsFor) {
                j.text(null, h);
            }
            j.close(']').open("qualifying", '[');
            for (String h : ch.qualifying) {
                j.text(null, h);
            }
            j.close(']');
            j.open("figures", '{');
            for (Map.Entry<String, Double> e : ch.figures.entrySet()) {
                j.num(e.getKey(), e.getValue());
            }
            for (Map.Entry<String, Double> e : more.entrySet()) {
                j.num(e.getKey(), e.getValue());
            }
            j.close('}');
            try {
                j.text("text", CeilingParagraphs.render(t, ch, more));
            } catch (IllegalStateException e) {
                j.text("text", null).text("pending", e.getMessage());
            }
            j.close('}');
        }
        j.close('}');
    }

    /**
     * Check 1 for the full-play report: its ladder and claim sections printed again must read as the
     * report written by `full` has them, line for line.
     */
    static void checkFullPrinted(Inputs in, Path report) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        printFull(in, new PrintStream(bytes, true, "UTF-8"));
        String[] starts = {"## The ladder at full play", "## Headline claims at full play"};
        Map<String, List<String>> again = sections(java.util.Arrays.asList(new String(bytes.toByteArray(),
                StandardCharsets.UTF_8).split("\n")), starts);
        Map<String, List<String>> printed = sections(Files.readAllLines(report, StandardCharsets.UTF_8), starts);
        if (!again.equals(printed)) {
            throw new IllegalStateException("the figures do not give " + report + "'s full-play ladder and claims");
        }
    }

    /** Writes text to a new file, through FILE.tmp, refusing one that exists. */
    static void writeNew(Path file, String text) throws IOException {
        if (Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(file + " exists; write to a new file");
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE_NEW);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** The flags a mode takes, each a path. */
    static Map<String, Path> flags(String[] args, int from) {
        Map<String, Path> out = new LinkedHashMap<>();
        for (int a = from; a < args.length; a++) {
            if (!args[a].startsWith("--") || a + 1 >= args.length) {
                throw new IllegalArgumentException("flags are --name PATH; not " + args[a]);
            }
            out.put(args[a].substring(2), java.nio.file.Paths.get(args[++a]));
        }
        return out;
    }

    /**
     * java -Xmx4g -cp CLASSES:lib/mongo-java-driver-3.12.14.jar CeilingResults full|data
     *   --stored runs/full-index/full-c06c061/results.ser --saved runs/report-ceiling-97bf834.ser
     *   --report runs/report-ceiling-97bf834.md --peek runs/full-index/peek-c06c061-final.txt
     *   --counts REGISTRATION --fullplay-run STEP8/results.ser --fullplay-counts FULL-PLAY REGISTRATION
     *   --widening runs/full-index/pilot-1417-100.txt [--split-margin FILE] [--optimism FILE.properties] --out FILE
     *   data also: --paragraphs CEILING_RESULT_PARAGRAPHS.md [--full-report runs/report-ceiling-full-SHA.md]
     * The classes must carry commit.txt (FullIndexRun.commit), which every output names.
     */
    public static void main(String[] args) throws Exception {
        run(args, FullIndexRun.commit(), System.out);
    }

    static void run(String[] args, String commit, PrintStream log) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.ROOT);
        if (args.length < 1) {
            throw new IllegalArgumentException("usage: CeilingResults full|data|optimism --name PATH ...");
        }
        Map<String, Path> f = flags(args, 1);
        Path out = f.get("out");
        if (out == null) {
            throw new IllegalArgumentException("give --out FILE, a new file");
        }
        if (Files.exists(out, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(out + " exists; write to a new file");
        }
        switch (args[0]) {
            case "full": {
                Inputs in = read(f, commit);
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                printFull(in, new PrintStream(bytes, true, "UTF-8"));
                writeNew(out, new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                break;
            }
            case "data": {
                if (f.get("paragraphs") == null) {
                    throw new IllegalArgumentException("give --paragraphs CEILING_RESULT_PARAGRAPHS.md");
                }
                CeilingParagraphs.Texts t = CeilingParagraphs.read(f.get("paragraphs"));
                Path fullReport = f.remove("full-report");
                Inputs in = read(f, commit);
                if (fullReport != null) {
                    in.add("full-report", fullReport);
                    checkFullPrinted(in, fullReport);
                }
                writeNew(out, data(in, t));
                break;
            }
            case "optimism":
                Optimism.run(f, commit, log);
                break;
            default:
                throw new IllegalArgumentException("modes are full, data and optimism; not " + args[0]);
        }
        log.println("CeilingResults " + args[0] + ": wrote " + out);
    }

    // ------------------------------------------------------------------ optimism (on the twin)

    /**
     * optimism (COUNT_DESIGN.md section 5): each registered row scored on the twin at three
     * penetrations, point estimates only, as the search scored its candidates (SystemScorer.estimate,
     * no resamples, M_PLAY, every twin shoe), and its optimism, (row - Zen) on the twin less (row -
     * Zen) on the main file, the main-file figures read from step 7's save and c06c061's stored rows,
     * never from the main state file. The control is a published pair, Hi-Opt II with its side count
     * less Zen, which should show none. At 80% each headline row's twin gain over the best of its
     * comparators on the twin, for paragraph C. Check 6: each headline row's twin figure at its
     * searched cell equals the objective the registration records for it, to the bit.
     *
     * Flags: --twin TWIN_STATES --counts REGISTRATION --stored c06c061's results.ser --saved step 7's
     * save --out FILE.properties; the report goes beside it as FILE.md.
     */
    static final class Optimism {

        private Optimism() {
        }

        static String key(String what, String family, int p, int m) {
            return what + "." + family.replace(' ', '_') + "." + SystemScorer.PENETRATIONS[p].replace("%", "") + "."
                    + (m == SystemScorer.FLAT ? "flat" : "s" + SystemScorer.SPREADS);
        }

        /** The headline row's index in estimate's entries: the betting variant's for a count with a betting vector. */
        static int rowIndex(CountKeys.Count x) {
            return x.betVector != null ? 2 : 0;
        }

        /** The registration's recorded objective of each count, by name; fields the loader does not read. */
        static Map<String, Double> objectives(Path counts) throws IOException {
            Map<String, Double> out = new LinkedHashMap<>();
            org.bson.Document d = org.bson.Document.parse(new String(Files.readAllBytes(counts), StandardCharsets.UTF_8));
            for (Object o : (List<?>) d.get("counts")) {
                org.bson.Document c = (org.bson.Document) o;
                if (c.get("objective") != null) {
                    out.put(c.getString("name"), ((Number) c.get("objective")).doubleValue());
                }
            }
            return out;
        }

        static void run(Map<String, Path> f, String commit, PrintStream log) throws Exception {
            for (String k : new String[]{"twin", "counts", "stored", "saved"}) {
                if (f.get(k) == null) {
                    throw new IllegalArgumentException("give --" + k);
                }
            }
            CustomCounts.requireTwin(f.get("twin"));
            CustomCounts.Listing counts = CustomCounts.read(f.get("counts"));
            CeilingReport.Published stored = CeilingReport.read(f.get("stored"));
            CeilingReport.Saved saved = CeilingReport.readSaved(f.get("saved"));
            CeilingReport.Ceiling c = firstDecisions(stored, saved, counts);
            ScoringStates st = FullIndexPilot.load(f.get("twin"), StateStore.shoesDone(f.get("twin")));
            SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, 0, Bootstrap.SEED), SystemScorer.M_PLAY);
            java.util.TreeMap<String, String> props = new java.util.TreeMap<>();
            figures(c, sc, objectives(f.get("counts")), props);
            Path out = f.get("out");
            String base = out.getFileName().toString().replaceAll("\\.properties$", "");
            Path md = out.resolveSibling(base + ".md");
            StringBuilder p = new StringBuilder("# CeilingResults optimism, commit " + commit + "\n# twin " + st.size
                    + " valued states of " + st.shoes + " shoes: " + f.get("twin") + "\n");
            for (String k : new String[]{"twin", "counts", "stored", "saved"}) {
                p.append("# ").append(k).append(' ').append(f.get(k)).append(" sha256 ").append(sha256(f.get(k))).append('\n');
            }
            for (Map.Entry<String, String> e : props.entrySet()) {
                p.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            writeNew(md, report(props, commit));
            writeNew(out, p.toString());
            log.println("optimism: " + props.size() + " figures to " + out + " and " + md);
        }

        /** A row's key: a headline count's family (unique, and the paragraphs' name for it), else its name made a key. */
        static String id(CustomCounts.Entry e) {
            return "headline".equals(e.role) ? e.family : e.count.name.replaceAll("[^A-Za-z0-9]+", "_");
        }

        /**
         * The figures into props: twin.ID.PEN.CELL and main.ID... for each custom row (controls left
         * out), zen.twin and zen.main, optimism.ID..., the control's optimism, and at 80% twinBest and
         * twinGain for each headline row. Refuses a headline row whose twin figure at its searched cell
         * is not its recorded objective, to the bit (check 6).
         */
        static void figures(CeilingReport.Ceiling c, SystemScorer sc, Map<String, Double> objectives,
                            Map<String, String> props) {
            int s8 = SystemScorer.same(SystemScorer.SPREADS);
            int[] cells = {SystemScorer.FLAT, s8};
            int last = SystemScorer.CUTS.length - 1;
            CountKeys.Count zen = CeilingReport.catalogEntry("Zen Count");
            SystemScorer.Result zenMain = c.published.of("Zen Count");
            SystemScorer.Result hiOptMain = c.published.of("Hi-Opt II");
            CountKeys.Count hiOpt = hiOptMain.count;
            List<String> mismatched = new ArrayList<>();
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                double[] zenTwin = sc.estimate(zen, null, p)[0];
                double[] hiOptTwin = sc.estimate(hiOpt, hiOptMain.entries.get(2).count, p)[2];
                for (int m : cells) {
                    props.put(key("zen.twin", "Zen", p, m), Double.toString(zenTwin[m]));
                    props.put(key("zen.main", "Zen", p, m), Double.toString(zenMain.entries.get(0).estimate[p][m]));
                    double control = (hiOptTwin[m] - zenTwin[m]) - (hiOptMain.entries.get(2).estimate[p][m]
                            - zenMain.entries.get(0).estimate[p][m]);
                    props.put(key("control.optimism", "Hi-Opt_II_ace", p, m), Double.toString(control));
                }
                for (int k = 0; k < c.custom.size(); k++) {
                    CustomCounts.Entry e = c.counts.entries.get(k);
                    if ("control".equals(e.role)) {
                        continue;
                    }
                    CountKeys.Count x = e.count;
                    double[] twin = sc.estimate(x, x.betVector != null ? CountKeys.bettingVariant(x) : null, p)[rowIndex(x)];
                    double[] main = CeilingReport.row(c.custom.get(k)).estimate[p];
                    for (int m : cells) {
                        props.put(key("twin", id(e), p, m), Double.toString(twin[m]));
                        props.put(key("main", id(e), p, m), Double.toString(main[m]));
                        props.put(key("optimism", id(e), p, m), Double.toString((twin[m] - zenTwin[m])
                                - (main[m] - zenMain.entries.get(0).estimate[p][m])));
                    }
                    if (p == last && "headline".equals(e.role) && objectives.containsKey(x.name)) {
                        int m = "F1 flat".equals(e.family) ? SystemScorer.FLAT : s8;
                        double objective = objectives.get(x.name);
                        props.put("objective." + id(e), Double.toString(objective));
                        if (Double.doubleToLongBits(objective) != Double.doubleToLongBits(twin[m])) {
                            mismatched.add(x.name + ": the twin gives " + twin[m] + ", the registration records " + objective);
                        }
                    }
                }
            }
            if (!mismatched.isEmpty()) {
                throw new IllegalStateException("the twin does not give the searched objective to the bit (check 6): "
                        + String.join("; ", mismatched));
            }
            twinGains(c, sc, props);
        }

        /** At 80%, each headline row's gain on the twin over the best of its comparators there, in both cells. */
        static void twinGains(CeilingReport.Ceiling c, SystemScorer sc, Map<String, String> props) {
            int p = SystemScorer.CUTS.length - 1;
            int[] cells = {SystemScorer.FLAT, SystemScorer.same(SystemScorer.SPREADS)};
            double[][] best = new double[2][cells.length];
            String[][] name = new String[2][cells.length];
            for (double[] b : best) {
                java.util.Arrays.fill(b, Double.NEGATIVE_INFINITY);
            }
            for (SystemScorer.Result r : c.published.results) {
                if (r.count.systems.isEmpty()) {
                    continue;
                }
                CountKeys.Count variant = r.entries.size() > 2 ? r.entries.get(2).count : null;
                double[][] est = sc.estimate(r.count, variant, p);
                for (int e = 0; e < est.length; e += 2) {
                    boolean plain = e == 0;
                    for (int k = 0; k < cells.length; k++) {
                        for (int all = plain ? 0 : 1; all < 2; all++) {
                            if (est[e][cells[k]] > best[all][k]) {
                                best[all][k] = est[e][cells[k]];
                                name[all][k] = r.entries.get(e).name;
                            }
                        }
                    }
                }
            }
            for (int k = 0; k < c.custom.size(); k++) {
                CustomCounts.Entry e = c.counts.entries.get(k);
                if (!"headline".equals(e.role)) {
                    continue;
                }
                int all = e.allComparators() ? 1 : 0;
                for (int j = 0; j < cells.length; j++) {
                    double twin = Double.parseDouble(props.get(key("twin", id(e), p, cells[j])));
                    props.put(key("twinBest", id(e), p, cells[j]), Double.toString(best[all][j]));
                    props.put(key("twinBest.name", id(e), p, cells[j]), name[all][j]);
                    props.put(key("twinGain", id(e), p, cells[j]), Double.toString(twin - best[all][j]));
                }
            }
        }

        /** The optimism run's report: every figure in % of a unit a round, as the properties hold them. */
        static String report(Map<String, String> props, String commit) {
            StringBuilder b = new StringBuilder("# Optimism of the registered counts\n\nBy CeilingResults optimism at commit "
                    + commit + ". (row - Zen) on the twin less (row - Zen) on the main file, point estimates; the "
                    + "main-file figures are step 7's and c06c061's saved rows. The control, Hi-Opt II with its ace side "
                    + "count, should show none. Fractions of a unit a round below are printed in %.\n\n| Figure | Value |\n"
                    + "|---|---|\n");
            for (Map.Entry<String, String> e : props.entrySet()) {
                String v = e.getValue();
                boolean number = !e.getKey().startsWith("twinBest.name");
                b.append("| ").append(e.getKey()).append(" | ").append(number ? ComparisonReport.pct(Double.parseDouble(v))
                        + "%" : v).append(" |\n");
            }
            return b.toString();
        }
    }
}
