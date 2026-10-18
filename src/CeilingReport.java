import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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
 * stored entries to the bit, every estimate and resample at every penetration. Given the published
 * report (report-final), it also sets the stored rows once beside its printed figures
 * (checkPrinted): its headline section, printed again from the stored rows by ComparisonReport's
 * own code, must read as published, every estimate, SE, bias and interval and each penetration's
 * max-|t| multipliers, to the printed digit. The stored perfect array must hold a value for every
 * state of every penetration, or the run is refused (requireCompletePerfect), since the full-play
 * bound row would otherwise be left out without a word. On the main state file both checks are required; the
 * counts must be the registration, committed as it stands, and the ledger clean (CustomCounts),
 * and the look is written to the ledger.
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
 *
 * Beside each claim it gives the tags part, the same bound against the registered controls at g,
 * and the floor, the largest lower end among the row and its family's published rows under the
 * max-|t| multiplier over them; then the grain part, each control at g less the same count at its
 * own grain, paired; and R, the betting regression's multiple correlation on these states, beside
 * every row's BC. Everything scored is saved (save, readSaved) before anything is printed, so a
 * figure the report does not print is worked out from the file, never by looking at the main file
 * again; on the main file a look without a file to save to is refused before it is made.
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

    /** The headline section of a report: its lines from "## Headline" to the next section, by penetration. */
    static java.util.Map<String, List<String>> headlineLines(List<String> report) {
        java.util.Map<String, List<String>> out = new java.util.TreeMap<>();
        boolean in = false;
        String at = null;
        for (String l : report) {
            if (l.startsWith("## ")) {
                in = l.startsWith("## Headline:");
                continue;
            }
            if (!in) {
                continue;
            }
            if (l.startsWith("### ")) {
                int cut = l.indexOf(" (");
                at = cut < 0 ? l : l.substring(0, cut);
                out.put(at, new ArrayList<>());
            }
            if (at != null && (l.startsWith("### ") || l.startsWith("| "))) {
                out.get(at).add(l);
            }
        }
        for (List<String> lines : out.values()) {
            java.util.Collections.sort(lines);
        }
        return out;
    }

    /**
     * The second tie of the stored rows to the published report (COUNT_DESIGN.md, section 5): its
     * headline section, the 1-8 same schedule at each penetration, printed again from the stored
     * rows by ComparisonReport.headline, must read as the published report prints it: every row's
     * EV, SE, bias and 95% interval, its difference from Hi-Lo with its interval and simultaneous
     * interval, and each penetration's average bet and max-|t| multipliers of both families, to the
     * printed digit, row by row whatever their order. Refuses, naming the lines that differ.
     */
    static void checkPrinted(Published p, Path report) throws IOException {
        ComparisonReport.Scored sc = new ComparisonReport.Scored();
        sc.results = p.results;
        sc.nullCount = p.nullCount();
        sc.hiLo = p.of("Hi-Lo");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        ComparisonReport.headline(sc, ComparisonReport.rows(sc), new PrintStream(bytes, true, "UTF-8"));
        java.util.Map<String, List<String>> again = headlineLines(java.util.Arrays.asList(
                new String(bytes.toByteArray(), StandardCharsets.UTF_8).split("\n")));
        java.util.Map<String, List<String>> published = headlineLines(Files.readAllLines(report, StandardCharsets.UTF_8));
        List<String> differ = new ArrayList<>();
        if (!again.keySet().equals(published.keySet())) {
            differ.add("its penetrations " + published.keySet() + " against " + again.keySet());
        }
        for (String pen : again.keySet()) {
            List<String> mine = again.get(pen);
            List<String> theirs = published.getOrDefault(pen, new ArrayList<>());
            for (String l : mine) {
                if (!theirs.contains(l)) {
                    differ.add("printed again: " + l);
                }
            }
            for (String l : theirs) {
                if (!mine.contains(l)) {
                    differ.add("published: " + l);
                }
            }
        }
        if (!differ.isEmpty()) {
            throw new IllegalStateException("the stored rows do not give " + report + "'s printed headline figures, and the "
                    + "run stops until the reason is found (COUNT_DESIGN.md, section 5); " + differ.size()
                    + " lines differ, among them:\n" + String.join("\n", differ.subList(0, Math.min(8, differ.size()))));
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
        /** R: the betting regression's multiple correlation on these states, the ceiling of any linear count's BC. */
        double r = Double.NaN;
        /** The reference rows (SystemScorer.references), the perfect flat row among them for PE and IE. */
        List<SystemScorer.Entry> references = new ArrayList<>();
        /** Each custom row playing its own fit, by name: {BC, IC}, as ComparisonReport computes them. */
        java.util.Map<String, double[]> correlations = new java.util.LinkedHashMap<>();
        /** On the main file, the blobs whose ledger lines are this registration's one look (CustomCounts.registeredBlobs). */
        java.util.Set<String> registered;
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

    /**
     * Refuses a perfect array that cannot give the full-play bound row at every penetration
     * (PLAN_COMPUTE.md 4.2): one missing, one of another length than the states, or one holding a
     * NaN at any state of any penetration. SystemScorer.bounds leaves the full-play bound out,
     * without a word, at a penetration where the array lacks a state, and FullIndexFit fills the
     * array only for the states its run visits; until this check the report also dropped the row
     * when the array was missing or the wrong length. A report without its full-play bound reads as
     * complete and is not, so each case stops the run here, naming how many states of each
     * penetration lack a value. The way on is a complete array: the replay of c06c061
     * (PLAN_COMPUTE.md 6.1) works out every state's value again. source names the array in the
     * message.
     */
    static void requireCompletePerfect(double[] perfect, SystemScorer scorer, String source) {
        int size = scorer.st.size;
        if (perfect == null) {
            throw new IllegalStateException(source + " holds no perfect array, so the full-play bound row cannot be "
                    + "scored; a report without it is refused (PLAN_COMPUTE.md 4.2)");
        }
        if (perfect.length != size) {
            throw new IllegalStateException(source + "'s perfect array holds " + perfect.length + " values, not one for "
                    + "each of the " + size + " states (PLAN_COMPUTE.md 4.2)");
        }
        List<String> missing = new ArrayList<>();
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            int[] states = scorer.pen(p).states;
            int nan = 0;
            for (int s : states) {
                if (Double.isNaN(perfect[s])) {
                    nan++;
                }
            }
            if (nan > 0) {
                missing.add(SystemScorer.PENETRATIONS[p] + ": " + nan + " of " + states.length + " states");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(source + "'s perfect array lacks a value at " + String.join("; ", missing)
                    + ". The full-play bound row would be left out there without a word, so the run stops; the "
                    + "replay of c06c061 (PLAN_COMPUTE.md 6.1) works out every state's value again (4.2)");
        }
    }

    /** The checks that the stored rows pair with this run and this code, which refuse to go on if not. */
    static Ceiling check(Path states, ScoringStates st, SystemScorer scorer, Published p, CustomCounts.Listing counts,
                         int threads) throws Exception {
        requirePaired(p, states, st, scorer.boot.resamples);
        requireCompletePerfect(p.perfect, scorer, "the stored rows");
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
        // check has refused an array that lacks any state, so the full-play bound is scored at every penetration.
        c.bounds = scorer.bounds(p.perfect);
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

    /** What must be left free on disk, beside the save file's directory, before a main-file look is made. */
    static final long SAVE_MARGIN = 3_000_000_000L;

    /**
     * The report from a state file, the stored rows and the counts given: the counts file and the
     * ledger checked on the main file, and a file to save to given there and new, the stored rows
     * checked, the look written to the ledger, then the counts scored, everything scored saved to
     * save when given, and the report printed. commit names the classes in the ledger and the file.
     */
    static Ceiling run(Path states, Path results, Path printed, Path countsFile, Path save, int resamples, int threads,
                       String commit, PrintStream out) throws Exception {
        return run(states, results, printed, countsFile, save, resamples, threads, commit, out, false, SAVE_MARGIN);
    }

    /**
     * The same, a look at another registration made only when secondLook (--second-look), and
     * diskMargin bytes required free where the save file goes. Every refusal comes before the look
     * is written to the ledger: the registration and the ledger (CustomCounts.checkOnMain), the save
     * file new and its directory writable with the margin free, the stored rows set beside the
     * published report, the states read and replayed, R and the reference rows worked out, and the
     * stored rows checked to the bit against these classes. Only the counts of our own, their
     * correlations, the save and the print come after.
     */
    static Ceiling run(Path states, Path results, Path printed, Path countsFile, Path save, int resamples, int threads,
                       String commit, PrintStream out, boolean secondLook, long diskMargin) throws Exception {
        CustomCounts.Listing counts = CustomCounts.read(countsFile);
        boolean main = CustomCounts.isMain(states);
        if (main && counts.registeredWith != null) {
            throw new IllegalStateException(countsFile + " is the full-play registration, scored with full play by "
                    + "FullIndexRun (step 8); this report scores " + counts.registeredWith + " (COUNT_DESIGN.md, section 8)");
        }
        String blob = main ? CustomCounts.checkOnMain(counts, secondLook) : null;
        java.util.Set<String> registered = main ? CustomCounts.registeredBlobs(counts, blob) : null;
        List<String> otherLooks = main ? CustomCounts.otherLooks(CustomCounts.ledgerOf(counts), registered)
                : new ArrayList<>();
        if (main && save == null) {
            throw new IllegalStateException("a main-file look saves everything it scores, so no figure needs a second "
                    + "look: give --save FILE (COUNT_DESIGN.md, section 5)");
        }
        if (main && printed == null) {
            throw new IllegalStateException("on the main file the stored rows are first set beside the published report's "
                    + "printed figures: give --printed REPORT (COUNT_DESIGN.md, section 5)");
        }
        if (save != null) {
            requireSavable(save, main ? diskMargin : 0);
        }
        Published p = read(results);
        if (printed != null) {
            checkPrinted(p, printed);
        }
        ScoringStates st = FullIndexPilot.load(states, StateStore.shoesDone(states));
        SystemScorer scorer = new SystemScorer(st, new Bootstrap(st.shoes, resamples, Bootstrap.SEED), SystemScorer.M_PLAY);
        // What does not involve the counts of our own, worked out before the look, so a failure here costs none.
        AllStates all = AllStates.replay(StateStore.header(states), st.shoes, StateRun.montreal(), BasicStrategy.chart(), st);
        double bettingR = CountRegression.betting(st).r;
        List<SystemScorer.Entry> references = scorer.references();
        Ceiling c = check(states, st, scorer, p, counts, threads);
        c.blob = blob;
        c.registered = registered;
        c.r = bettingR;
        c.references = references;
        if (main) {
            CustomCounts.appendLedger(CustomCounts.ledgerOf(counts), commit, blob, "ComparisonReport "
                    + "--published " + results + ", " + resamples + " resamples, saved to " + save
                    + (otherLooks.isEmpty() ? "" : ", A SECOND LOOK (--second-look): the ledger held " + otherLooks.size()
                    + " look" + (otherLooks.size() == 1 ? "" : "s") + " at another registration"));
        }
        scoreCustom(c, threads);
        for (SystemScorer.Result r : c.custom) {
            for (SystemScorer.Entry e : r.entries) {
                if (!e.bettingOnly && !e.ownValue) {
                    c.correlations.put(e.name, new double[]{AllStates.bettingCorrelation(e.count, st),
                            all.insuranceCorrelation(e.count)});
                }
            }
        }
        if (save != null) {
            save(c, states, commit, save);
        }
        print(c, states, out);
        return c;
    }

    /** What a save writes, about: 97bf834's held 0.20 GB (DiskBudget). */
    static final long SAVE_BYTES = 300_000_000L;

    /**
     * Refuses a save file the report could not write at its end: one that exists, or whose
     * FILE.tmp, which save writes first, exists; a directory that is missing or where a file cannot
     * be made (FILE.tmp is made there and deleted); less than margin bytes free there; or a runs
     * folder whose budget has no room for SAVE_BYTES (DiskBudget).
     */
    static void requireSavable(Path save, long margin) throws IOException {
        if (Files.exists(save, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(save + " exists; save the report's figures to a new file");
        }
        Path dir = save.toAbsolutePath().normalize().getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IllegalStateException("the save file's directory " + dir + " does not exist; the report would score "
                    + "everything and then could not save it");
        }
        Path tmp = save.resolveSibling(save.getFileName() + ".tmp");
        try {
            Files.createFile(tmp);
            Files.delete(tmp);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + tmp + " (" + e + "): the report would score everything and "
                    + "then could not save it", e);
        }
        long free = Files.getFileStore(dir).getUsableSpace();
        if (free < margin) {
            throw new IllegalStateException(String.format("%.1f GB is free where %s goes, less than the %.1f GB a look "
                    + "keeps free to save what it scores", free / 1e9, save, margin / 1e9));
        }
        DiskBudget.require(save, SAVE_BYTES, DiskBudget.budget(null), "CeilingReport --save");
    }

    // ------------------------------------------------------------------ what is saved

    /** What save writes: the run's identity, and everything scored beside the stored rows. */
    static final class Saved {
        String identity;
        List<SystemScorer.Result> custom;
        List<SystemScorer.Entry> bounds;
        List<SystemScorer.Entry> refit;
        List<String> checked;
        double r;
        List<SystemScorer.Entry> references;
        java.util.Map<String, double[]> correlations;
    }

    /**
     * Writes everything the report scored, estimates and resamples, to a new file, in one move: its
     * identity (the classes' commit, the state file, the counts file and its blob, the resamples and
     * the stored rows' run), the counts' Results, the bound rows, the refit check, the counts checked
     * to the bit, R, the reference rows, and each custom row's BC and IC.
     */
    static void save(Ceiling c, Path states, String commit, Path file) throws IOException {
        String identity = "CeilingReport\ncommit " + commit + "\n" + FullIndexRun.stateFile(states) + "\ncounts "
                + c.counts.file.toAbsolutePath().normalize() + (c.blob == null ? "" : ", blob " + c.blob) + "\nresamples "
                + c.scorer.boot.resamples + ", seed " + Bootstrap.SEED + "\nstored rows of " + c.published.line("commit ");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (ObjectOutputStream o = new ObjectOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp),
                1 << 20))) {
            o.writeUTF(identity);
            o.writeObject(new ArrayList<>(c.custom));
            o.writeObject(new ArrayList<>(c.bounds));
            o.writeObject(new ArrayList<>(c.refit));
            o.writeObject(new ArrayList<>(c.checked));
            o.writeDouble(c.r);
            o.writeObject(new ArrayList<>(c.references));
            o.writeObject(new java.util.LinkedHashMap<>(c.correlations));
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** Reads what save wrote. */
    @SuppressWarnings("unchecked")
    static Saved readSaved(Path file) throws IOException, ClassNotFoundException {
        try (ObjectInputStream o = new ObjectInputStream(new java.io.BufferedInputStream(Files.newInputStream(file),
                1 << 20))) {
            Saved s = new Saved();
            s.identity = o.readUTF();
            s.custom = (List<SystemScorer.Result>) o.readObject();
            s.bounds = (List<SystemScorer.Entry>) o.readObject();
            s.refit = (List<SystemScorer.Entry>) o.readObject();
            s.checked = (List<String>) o.readObject();
            s.r = o.readDouble();
            s.references = (List<SystemScorer.Entry>) o.readObject();
            s.correlations = (java.util.Map<String, double[]>) o.readObject();
            return s;
        }
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

    /** The headline rows a registration names for a cell, by their row names (row), in its order. */
    static List<String> rowNames(Ceiling c, String cell) {
        List<String> out = new ArrayList<>();
        for (SystemScorer.Result r : claimed(c, cell)) {
            out.add(row(r).name);
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

    /**
     * The registered controls' rows a headline row's tags part is bounded against: each control at
     * g playing its own fit, its side-count variant too for a count with a betting vector (as its
     * comparators are chosen); empty where none is registered.
     */
    static List<SystemScorer.Entry> controls(Ceiling c, SystemScorer.Result r) {
        int i = c.custom.indexOf(r);
        boolean all = i >= 0 ? c.counts.entries.get(i).allComparators() : r.count.betVector != null;
        List<SystemScorer.Entry> out = new ArrayList<>();
        for (int k = 0; k < c.custom.size(); k++) {
            if (!"control".equals(c.counts.entries.get(k).role)) {
                continue;
            }
            for (SystemScorer.Entry e : c.custom.get(k).entries) {
                if (!e.bettingOnly && !e.ownValue && (all || e.count.base == null)) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /**
     * The floor (section 5) at one cell: among the row and the published rows of its family, each
     * estimate less the max-|t| multiplier over their EVs times its SE, the largest; {floor, the
     * multiplier}, and the row it comes from in floorRow[0].
     */
    static double[] floor(SystemScorer.Entry row, List<SystemScorer.Entry> family, int p, int m,
                          SystemScorer.Entry[] floorRow) {
        List<SystemScorer.Entry> rows = new ArrayList<>();
        rows.add(row);
        rows.addAll(family);
        double[] est = new double[rows.size()];
        double[][] boots = new double[rows.size()][];
        for (int k = 0; k < est.length; k++) {
            est[k] = rows.get(k).estimate[p][m];
            boots[k] = rows.get(k).boot[p][m];
        }
        double mult = Bootstrap.maxT(est, boots);
        double best = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < est.length; k++) {
            double lo = est[k] - mult * Bootstrap.interval(est[k], boots[k]).se;
            if (lo > best) {
                best = lo;
                floorRow[0] = rows.get(k);
            }
        }
        return new double[]{best, mult};
    }

    static Claim claim(SystemScorer.Entry row, List<SystemScorer.Entry> comparators, int p, int m) {
        return claim(row, comparators, p, m, (a, b) -> 1);
    }

    /**
     * The same with each difference widened by the ratio given for its pair (SystemScorer.difference):
     * at full play the larger of the two counts' difference ratios (COUNT_DESIGN.md section 5). At
     * first decisions the ratio is 1 and nothing is widened, so the figures are the report's.
     */
    static Claim claim(SystemScorer.Entry row, List<SystemScorer.Entry> comparators, int p, int m,
                       java.util.function.ToDoubleBiFunction<SystemScorer.Entry, SystemScorer.Entry> widen) {
        Claim out = new Claim(row);
        for (SystemScorer.Entry j : comparators) {
            Bootstrap.Interval d = SystemScorer.difference(row, j, p, m, widen.applyAsDouble(row, j));
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
            out.printf("Registered from the twin %s at commit %s. The rows reported in each cell, at every penetration, "
                    + "each a count's headline row (a count with a betting vector reported by its row with that vector): "
                    + "flat %s; the same schedule, every spread, %s; the own ramp %s.%n%n", c.counts.twin,
                    c.counts.searchCommit, rowNames(c, "flat"), rowNames(c, "same"), rowNames(c, "own"));
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

        printLadder(c, out);
        printClaims(c, out);
        grainPart(c, out);
        bettingCorrelations(c, out);
        efficiencies(c, out);

        out.println("## Every cell of the counts scored here");
        for (SystemScorer.Result r : c.custom) {
            for (SystemScorer.Entry e : r.entries) {
                out.println();
                out.printf("### %s%n%n", e.name);
                out.println("| Penetration | Flat | 1-2 | 1-3 | 1-4 | 1-5 | 1-6 | 1-7 | 1-8 | Own ramp 1-8 "
                        + "| Half fit change, flat | Half fit change, 1-8 | Decision bound |");
                out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|");
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    StringBuilder b = new StringBuilder("| " + SystemScorer.PENETRATIONS[p] + " |");
                    int[] cells = new int[SystemScorer.SPREADS + 1];
                    cells[0] = SystemScorer.FLAT;
                    for (int sp = 2; sp <= SystemScorer.SPREADS; sp++) {
                        cells[sp - 1] = SystemScorer.same(sp);
                    }
                    cells[SystemScorer.SPREADS] = SystemScorer.own(SystemScorer.SPREADS);
                    for (int m : cells) {
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
            List<String> other = c.registered == null ? new ArrayList<>() : CustomCounts.otherLooks(ledger, c.registered);
            if (c.registered != null) {
                out.printf("The one look is every line carrying the registration's blob or its full-play registration's: %s."
                        + "%n%n", String.join(", ", c.registered));
                out.println(other.isEmpty() ? "No line is a look at another registration."
                        : "A SECOND LOOK: " + other.size() + " line" + (other.size() == 1 ? " is a look" : "s are looks")
                        + " at another registration, marked below. A count changed after a main-file look is a second "
                        + "look, and its held-out claim needs a fresh file (COUNT_DESIGN.md, section 5).");
                out.println();
            }
            out.println("```");
            for (String l : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
                out.println(other.contains(l) ? l + "\t<- ANOTHER REGISTRATION: A SECOND LOOK" : l);
            }
            out.println("```");
        }
        if (!over.isEmpty()) {
            throw new IllegalStateException("rows above their bound: " + String.join("; ", over));
        }
    }

    /**
     * The ladder sections of the report, 1-8 and flat, from what was scored alone (no state is
     * needed), so CeilingResults can print them again from the saved file and set them beside the
     * report as printed.
     */
    static void printLadder(Ceiling c, PrintStream out) {
        int s8 = SystemScorer.same(SystemScorer.SPREADS);
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
    }

    /** The headline claims section of the report, from what was scored alone, as printLadder. */
    static void printClaims(Ceiling c, PrintStream out) {
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
                        List<SystemScorer.Entry> family = comparators(c, r);
                        Claim cl = claim(k, family, p, metric);
                        List<SystemScorer.Entry> controls = controls(c, r);
                        String tags = "no controls registered";
                        if (!controls.isEmpty()) {
                            Claim tc = claim(k, controls, p, metric);
                            tags = ComparisonReport.pct(tc.gain) + " against " + controls.size() + " controls at g";
                        }
                        SystemScorer.Entry[] from = new SystemScorer.Entry[1];
                        double[] fl = floor(k, family, p, metric, from);
                        out.printf("%n- %s at %s, %s: gain %s; best comparator %s, difference %s %s; %d comparators; "
                                        + "claim %s. Tags part: %s. Floor %s, from %s (max-|t| %.2f over %d rows).",
                                k.name, SystemScorer.PENETRATIONS[p], cell[1],
                                ComparisonReport.pct(cl.gain), cl.best.name, ComparisonReport.pct(cl.fromBest.estimate),
                                ComparisonReport.ci(cl.fromBest), cl.against.size(), cl.holds() ? "holds" : "not made",
                                tags, ComparisonReport.pct(fl[0]), from[0].name, fl[1], family.size() + 1);
                    }
                }
            }
            out.println();
            out.println();
        }
    }

    /**
     * The grain part (section 1): each registered control at a grain of its own, less the same
     * catalog count at its own grain as the stored rows hold it, paired, at 1-8 and flat.
     */
    static void grainPart(Ceiling c, PrintStream out) {
        int s8 = SystemScorer.same(SystemScorer.SPREADS);
        out.println("## The grain part");
        out.println();
        out.println("Each control at g less the same count at its own grain, the stored row, paired resample by resample.");
        out.println();
        out.println("| Control at g | Penetration | 1-8 | 95% CI | Flat | 95% CI |");
        out.println("|---|---|---|---|---|---|");
        for (int i = 0; i < c.custom.size(); i++) {
            SystemScorer.Result r = c.custom.get(i);
            if (!"control".equals(c.counts.entries.get(i).role) || r.count.systems.isEmpty() || r.count.divisor() == 1) {
                continue;
            }
            SystemScorer.Result stored = c.published.of(r.count.systems.get(0));
            for (int e = 0; e < Math.min(r.entries.size(), stored.entries.size()); e++) {
                SystemScorer.Entry g = r.entries.get(e);
                if (g.bettingOnly || g.ownValue) {
                    continue;
                }
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    Bootstrap.Interval d8 = SystemScorer.difference(g, stored.entries.get(e), p, s8, 1);
                    Bootstrap.Interval df = SystemScorer.difference(g, stored.entries.get(e), p, SystemScorer.FLAT, 1);
                    out.printf("| %s | %s | %s | %s | %s | %s |%n", g.name, SystemScorer.PENETRATIONS[p],
                            ComparisonReport.pct(d8.estimate), ComparisonReport.ci(d8), ComparisonReport.pct(df.estimate),
                            ComparisonReport.ci(df));
                }
            }
        }
        out.println();
    }

    /** R beside every row's BC (section 2): no linear count's BC on these states can exceed it; and each row's IC. */
    static void bettingCorrelations(Ceiling c, PrintStream out) {
        out.println("## The betting correlation and its ceiling");
        out.println();
        out.printf("R, the multiple correlation of the chart advantage regressed on the shares left over these %d valued "
                + "states weighted 1/q, is %.4f: no linear count's BC on these states can exceed it. BC and IC as the "
                + "published report computes them (ComparisonReport, its correlations table).%n%n", c.st.size, c.r);
        out.println("| Row | BC | IC |");
        out.println("|---|---|---|");
        SystemScorer.Entry best = null;
        double bestBc = Double.NEGATIVE_INFINITY;
        for (SystemScorer.Result r : c.published.results) {
            for (SystemScorer.Entry e : r.entries) {
                if (e.bettingOnly || r.count.systems.isEmpty()) {
                    continue;
                }
                double bc = AllStates.bettingCorrelation(e.count, c.st);
                if (bc > bestBc) {
                    bestBc = bc;
                    best = e;
                }
            }
        }
        if (best != null) {
            out.printf("| Best published: %s | %.4f |  |%n", best.name, bestBc);
        }
        for (java.util.Map.Entry<String, double[]> e : c.correlations.entrySet()) {
            out.printf("| %s | %.4f | %.4f |%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
        out.println();
    }

    /** PE and IE of each custom row, flat, as the published report gives them (ComparisonReport.efficiencies). */
    static void efficiencies(Ceiling c, PrintStream out) {
        out.println("## Flat play, insurance and efficiencies of the counts scored here");
        out.println();
        out.println("PE = (count - null) / (perfect - null) on play, IE the same on insurance, flat, the null count's "
                + "stored row the baseline; intervals resample by resample, as in the published report.");
        SystemScorer.Entry perfect = c.references.get(1);
        SystemScorer.Entry none = c.published.nullCount().entries.get(0);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            out.println();
            out.printf("### %s penetration%n%n", SystemScorer.PENETRATIONS[p]);
            out.println("| Row | Play | Insurance adds | PE | 95% CI | IE | 95% CI |");
            out.println("|---|---|---|---|---|---|---|");
            for (SystemScorer.Result r : c.custom) {
                for (SystemScorer.Entry e : r.entries) {
                    if (e.bettingOnly || e.ownValue) {
                        continue;
                    }
                    Bootstrap.Interval[] eff = ComparisonReport.efficiencies(e, none, perfect, p);
                    out.printf("| %s | %s | %s | %s | %s | %s | %s |%n", e.name, ComparisonReport.pct(e.estimate[p][
                                    SystemScorer.PLAY]), ComparisonReport.pct(e.estimate[p][SystemScorer.INSURANCE]),
                            ComparisonReport.ratio(eff[0].estimate), ComparisonReport.ratioCi(eff[0]),
                            ComparisonReport.ratio(eff[1].estimate), ComparisonReport.ratioCi(eff[1]));
                }
            }
        }
        out.println();
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
