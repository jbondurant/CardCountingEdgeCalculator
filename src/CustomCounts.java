import org.bson.Document;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Counts of our own, as a file lists them (COUNT_DESIGN.md, section 5), and the rules that keep
 * the main file to one look.
 *
 * Two kinds of file hold them, in one format:
 * - a candidates file, written as the search goes, for scoring on the twin file;
 * - custom-counts.json at the root of the repository, the registration: the counts chosen on the
 *   twin, fixed before any main-file run, with the twin file's header, the commit the search ran
 *   from, which count is reported in each cell and each headline row's comparators. It may name
 *   a second registration beside it, custom-counts-fullplay.json, the counts scored with full play
 *   (step 8), by its file name and its git blob, so the one blob fixes both files; that file names
 *   custom-counts.json back ("registeredWith"), and the two share the ledger.
 *
 * The format (JSON):
 * <pre>
 * { "kind": "candidates" or "registered", "K": 10000,
 *   "twin": "the twin file's header", "searchCommit": "...",      (registered)
 *   "cells": { "flat": ["F1 flat"], "same": ["F1", "F2"], "own": ["F1", "F2"] },
 *                                                                   (registered; optional for candidates)
 *   "comparators": {...},                                           (optional, kept as text)
 *   "fullPlay": { "file": "custom-counts-fullplay.json", "blob": "40 hex digits" },
 *                                                                   (optional, a registration's full-play one)
 *   "registeredWith": "custom-counts.json",                         (the full-play registration's)
 *   "counts": [
 *     { "name": "...", "family": "F1", "role": "headline", "comparators": "plain" or "all",
 *       "form": "true" or "running", "divisor": 500,
 *       "tags": [A, 2, 3, 4, 5, 6, 7, 8, 9, T],                     whole numbers at K
 *       "betting": [A, ..., T] },                                   optional: F2's betting vector
 *     { "name": "Hi-Opt II at grain 500/10000", "role": "control", "catalog": "Hi-Opt II", "divisor": 500 } ] }
 * </pre>
 * A "catalog" entry is that catalog count at grain divisor / K (CountKeys.atGrain), or as the
 * catalog has it when no divisor is given; it keeps its systems, so its side-count variant comes
 * with it. Its name is the one that gives it (CountKeys.atGrain's, or the catalog's), and a
 * registration that names it otherwise is refused, so the file reads as the report will. Tags are
 * read as whole numbers and nothing is normalised or rounded, so the count scored is the count
 * registered, to the bit. Fields the loader does not know are kept in the file and read by no
 * code: they record, before the look, what the counts were chosen on.
 *
 * "cells" names the count reported in each cell, at every penetration (section 5): "flat", the
 * flat cells; "same", the same schedule at every spread from 1-2 to 1-8; "own", the own ramp.
 * Each cell names exactly one headline count of one vector and at most one with a betting vector,
 * and none with a betting vector in the flat cells, whose bets do not vary; every headline count
 * is named in some cell. These are the only (count, cell) pairs a headline claim is made for, so
 * a cell's claims are at most two, the Bonferroni pair of section 5.
 *
 * The rules (section 5, "One look"): the search, calibration and theory-pass tools refuse the
 * main file (requireTwin); a main-file tool given counts refuses a file that is not the
 * registration, or is not committed as it stands, a ledger with lines not committed, and a ledger
 * holding a look at another registration, unless a second look is asked for (checkOnMain); and
 * once every refusal of its own is behind, it appends to the ledger the line that records its look.
 * The one look is every ledger line carrying the registration's blob or its full-play
 * registration's (registeredBlobs).
 */
final class CustomCounts {

    /** The main state file's seed, runs/montreal-80.states: nothing is chosen on it. */
    static final long MAIN_SEED = 20260928L;
    /** The twin file's seed (section 4). */
    static final long TWIN_SEED = 20261007L;
    /** The registration and the ledger, at the root of the repository. */
    static final String REGISTERED = "custom-counts.json";
    /** The full-play registration (step 8), beside it, named by it with its blob. */
    static final String FULL_PLAY = "custom-counts-fullplay.json";
    static final String LEDGER = "custom-count-ledger.tsv";
    static final String LEDGER_HEADER = "commit\tblob\ttime\twhat ran";

    /** One count of a file, with its family and role. */
    static final class Entry {
        final CountKeys.Count count;
        final String family;
        final String role;
        /**
         * A headline row's comparators: "plain" for the catalog's counts playing their own fits,
         * "all" for those and their side-count variants; null for the rule of section 5, plain for
         * a count with one vector and all for one with a betting vector.
         */
        final String comparators;

        Entry(CountKeys.Count count, String family, String role) {
            this(count, family, role, null);
        }

        Entry(CountKeys.Count count, String family, String role, String comparators) {
            if (comparators != null && !comparators.equals("plain") && !comparators.equals("all")) {
                throw new IllegalArgumentException(count.name + "'s comparators are \"plain\" or \"all\", not "
                        + comparators);
            }
            this.count = count;
            this.family = family;
            this.role = role;
            this.comparators = comparators;
        }

        /** Whether the headline row is compared with the side-count variants too. */
        boolean allComparators() {
            return comparators == null ? count.betVector != null : comparators.equals("all");
        }
    }

    /** A file's counts and what it says about them. */
    static final class Listing {
        final Path file;
        final boolean registered;
        final List<Entry> entries;
        final String twin;
        final String searchCommit;
        /** The counts reported in each cell, "flat", "same" and "own", by name; null where the file names none. */
        final Map<String, List<String>> cells;
        /** The comparators as the file holds them, for the report to print. */
        final String comparators;
        /** For a registration that names its full-play registration: that file's name, beside it, and its git blob. */
        String fullPlayFile;
        String fullPlayBlob;
        /** For a full-play registration: the registration beside it that names it. */
        String registeredWith;

        Listing(Path file, boolean registered, List<Entry> entries, String twin, String searchCommit,
                Map<String, List<String>> cells, String comparators) {
            this.file = file;
            this.registered = registered;
            this.entries = Collections.unmodifiableList(entries);
            this.twin = twin;
            this.searchCommit = searchCommit;
            this.cells = cells;
            this.comparators = comparators;
        }

        List<CountKeys.Count> counts() {
            List<CountKeys.Count> out = new ArrayList<>();
            for (Entry e : entries) {
                out.add(e.count);
            }
            return out;
        }

        /** The place in entries of the count of this name, or -1. */
        int indexOf(String name) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).count.name.equals(name)) {
                    return i;
                }
            }
            return -1;
        }
    }

    /** The cells a registration names a count for: flat, the same schedule at every spread, and the own ramp. */
    static final List<String> CELLS = Collections.unmodifiableList(java.util.Arrays.asList("flat", "same", "own"));

    private CustomCounts() {
    }

    /** Reads a candidates file or the registration. */
    static Listing read(Path file) throws IOException {
        Document d = Document.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        String kind = d.getString("kind");
        if (!"candidates".equals(kind) && !"registered".equals(kind)) {
            throw new IllegalArgumentException(file + ": \"kind\" must be \"candidates\" or \"registered\", not " + kind);
        }
        Number k = (Number) d.get("K");
        if (k == null || k.intValue() != CountKeys.K) {
            throw new IllegalArgumentException(file + ": tags are whole numbers at K = " + CountKeys.K + ", not " + k);
        }
        boolean registered = kind.equals("registered");
        List<Entry> entries = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Document c : d.getList("counts", Document.class)) {
            Entry e = entry(file, c, registered);
            if (names.contains(e.count.name)) {
                throw new IllegalArgumentException(file + " lists " + e.count.name + " twice");
            }
            names.add(e.count.name);
            entries.add(e);
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException(file + " lists no counts");
        }
        String twin = d.getString("twin");
        String commit = d.getString("searchCommit");
        if (registered && (twin == null || commit == null)) {
            throw new IllegalArgumentException(file + ": a registration names the twin file and the search's commit");
        }
        Object cells = d.get("cells");
        if (registered && cells == null) {
            throw new IllegalArgumentException(file + ": a registration names the count reported in each cell");
        }
        Object comparators = d.get("comparators");
        Listing out = new Listing(file, registered, entries, twin, commit, cells == null ? null : cells(file, cells, entries),
                comparators == null ? null : comparators.toString());
        Object fullPlay = d.get("fullPlay");
        Object with = d.get("registeredWith");
        if ((fullPlay != null || with != null) && !registered) {
            throw new IllegalArgumentException(file + ": only a registration names a full-play registration or the "
                    + "registration it belongs to");
        }
        if (fullPlay != null && with != null) {
            throw new IllegalArgumentException(file + " names a full-play registration and a registration it belongs to; "
                    + "one file is the registration, the other its full-play registration");
        }
        if (fullPlay != null) {
            if (!(fullPlay instanceof Document)) {
                throw new IllegalArgumentException(file + ": \"fullPlay\" gives the full-play registration's \"file\" and "
                        + "\"blob\"");
            }
            Document f = (Document) fullPlay;
            out.fullPlayFile = sibling(file, f.get("file"), "fullPlay's file");
            Object blob = f.get("blob");
            if (!(blob instanceof String) || !((String) blob).matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException(file + ": fullPlay's blob is the full-play registration's git blob hash, "
                        + "40 hex digits, not " + blob);
            }
            out.fullPlayBlob = (String) blob;
        }
        if (with != null) {
            out.registeredWith = sibling(file, with, "registeredWith");
        }
        return out;
    }

    /** A file name beside file, as a registration names another: one name, no directory, not file itself. */
    private static String sibling(Path file, Object name, String what) {
        if (!(name instanceof String) || ((String) name).isEmpty() || ((String) name).contains("/")
                || ((String) name).contains("\\") || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException(file + ": " + what + " is the name of a file beside it, not " + name);
        }
        if (name.equals(file.getFileName().toString())) {
            throw new IllegalArgumentException(file + ": " + what + " names the file itself");
        }
        return (String) name;
    }

    /** The cells as a file names them, each cell's counts checked against the rules above. */
    private static Map<String, List<String>> cells(Path file, Object o, List<Entry> entries) {
        if (!(o instanceof Document)) {
            throw new IllegalArgumentException(file + ": \"cells\" maps flat, same and own to the counts reported there");
        }
        Document d = (Document) o;
        for (String key : d.keySet()) {
            if (!CELLS.contains(key)) {
                throw new IllegalArgumentException(file + ": the cells are flat, same and own, not " + key);
            }
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<String> named = new ArrayList<>();
        for (String cell : CELLS) {
            Object v = d.get(cell);
            if (!(v instanceof List)) {
                throw new IllegalArgumentException(file + ": the cell " + cell + " lists the counts reported there");
            }
            List<String> names = new ArrayList<>();
            int plain = 0;
            int betting = 0;
            for (Object x : (List<?>) v) {
                Entry e = null;
                for (Entry y : entries) {
                    if (y.count.name.equals(x)) {
                        e = y;
                    }
                }
                if (e == null) {
                    throw new IllegalArgumentException(file + ": the cell " + cell + " names " + x + ", which it lists not");
                }
                if (!"headline".equals(e.role)) {
                    throw new IllegalArgumentException(file + ": " + x + " is reported in the cell " + cell
                            + ", but its role is " + e.role + ", not headline");
                }
                if (e.count.betVector != null) {
                    betting++;
                    if (cell.equals("flat")) {
                        throw new IllegalArgumentException(file + ": " + x + " has a betting vector, and the flat cells' "
                                + "bets do not vary");
                    }
                } else {
                    plain++;
                }
                names.add(e.count.name);
            }
            if (plain != 1 || betting > 1) {
                throw new IllegalArgumentException(file + ": the cell " + cell + " names " + names + "; a cell reports "
                        + "one count of one vector, and at most one with a betting vector beside it");
            }
            named.addAll(names);
            out.put(cell, Collections.unmodifiableList(names));
        }
        for (Entry e : entries) {
            if ("headline".equals(e.role) && !named.contains(e.count.name)) {
                throw new IllegalArgumentException(file + ": " + e.count.name + " is a headline count reported in no cell");
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static Entry entry(Path file, Document c, boolean registered) {
        String name = c.getString("name");
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(file + ": a count without a name");
        }
        String role = c.getString("role");
        String family = c.getString("family");
        Number divisor = (Number) c.get("divisor");
        String catalog = c.getString("catalog");
        if (catalog != null) {
            if (c.containsKey("tags") || c.containsKey("betting")) {
                throw new IllegalArgumentException(file + ": " + name + " is a catalog count and takes no tags");
            }
            CountKeys.Count found = null;
            for (CountKeys.Count x : CountKeys.catalog()) {
                if (x.systems.contains(catalog)) {
                    found = x;
                }
            }
            if (found == null) {
                throw new IllegalArgumentException(file + ": no catalog system named " + catalog);
            }
            CountKeys.Count count = divisor == null ? found : CountKeys.atGrain(found, divisor.intValue());
            if (registered && !name.equals(count.name)) {
                // The report names the row as the loader does; a registration names it the same way.
                throw new IllegalArgumentException(file + ": the catalog count " + catalog + " is scored as \"" + count.name
                        + "\", so a registration names it so, not \"" + name + "\"");
            }
            return new Entry(count, family == null ? "published" : family, role);
        }
        String form = c.getString("form");
        if (!"true".equals(form) && !"running".equals(form)) {
            throw new IllegalArgumentException(file + ": " + name + "'s form must be \"true\" or \"running\"");
        }
        if (divisor == null) {
            throw new IllegalArgumentException(file + ": " + name + " has no divisor");
        }
        int[] tags = tags(file, name, c.get("tags"));
        int[] betting = c.containsKey("betting") ? tags(file, name, c.get("betting")) : null;
        CountKeys.Count count = CountKeys.whole(name, tags, betting, divisor.intValue(), form.equals("true"));
        return new Entry(count, family, role, c.getString("comparators"));
    }

    /** Ten whole numbers, A and 2 to 9 and T, as tags by rank 1 to 10. */
    private static int[] tags(Path file, String name, Object o) {
        if (!(o instanceof List)) {
            throw new IllegalArgumentException(file + ": " + name + "'s tags are a list of ten whole numbers");
        }
        List<?> l = (List<?>) o;
        if (l.size() != 10) {
            throw new IllegalArgumentException(file + ": " + name + " has " + l.size() + " tags, not ten");
        }
        int[] out = new int[11];
        for (int r = 1; r <= 10; r++) {
            Object x = l.get(r - 1);
            if (!(x instanceof Integer) && !(x instanceof Long)) {
                throw new IllegalArgumentException(file + ": " + name + "'s tag " + x + " is not a whole number");
            }
            long v = ((Number) x).longValue();
            if (v != (int) v) {
                throw new IllegalArgumentException(file + ": " + name + "'s tag " + v + " is too large");
            }
            out[r] = (int) v;
        }
        return out;
    }

    // ----------------------------------------------------------------- the refusals

    /**
     * Refuses the main state file, by its header's seed: the search, calibration and theory-pass
     * tools work on the twin alone, so the shoes a count is scored on play no part in choosing it.
     */
    static void requireTwin(Path states) throws IOException {
        long seed = StateStore.header(states).seed;
        if (seed == MAIN_SEED) {
            throw new IllegalStateException(states + " is the main state file (seed " + MAIN_SEED + "); counts are "
                    + "searched and calibrated on the twin file only (COUNT_DESIGN.md, section 4)");
        }
    }

    /** Whether a state file is the main file, by its header's seed. */
    static boolean isMain(Path states) throws IOException {
        return StateStore.header(states).seed == MAIN_SEED;
    }

    /**
     * The seed-collision check (section 4, "Guards"): how many of the first shoes' generator seeds
     * of a file of seed b (ShoeRun.mix, the cards' stream and the valuation's) equal one of a file
     * of seed a over as many shoes. 0 for the twin against the main file.
     */
    static int seedCollisions(long a, long b, int shoes) {
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int s = 0; s < shoes; s++) {
            for (int stream = 0; stream < 2; stream++) {
                seen.add(ShoeRun.mix(a, s, stream));
            }
        }
        int n = 0;
        for (int s = 0; s < shoes; s++) {
            for (int stream = 0; stream < 2; stream++) {
                n += seen.contains(ShoeRun.mix(b, s, stream)) ? 1 : 0;
            }
        }
        return n;
    }

    /**
     * Before a main-file tool scores custom counts: the counts file must be the registration,
     * committed and unmodified, and the ledger committed with no lines added since. Then one
     * line is appended to the ledger, naming the commit of the classes, the registration's git
     * blob hash, the time and what ran; it must be committed before the next look. Gives the blob
     * hash, which the run's identity and report print.
     */
    static String lookOnMain(Listing counts, String classesCommit, String what) throws IOException {
        String blob = checkOnMain(counts);
        appendLedger(ledgerOf(counts), classesCommit, blob, what);
        return blob;
    }

    /** checkOnMain, a look at another registration refused. */
    static String checkOnMain(Listing counts) throws IOException {
        return checkOnMain(counts, false);
    }

    /**
     * lookOnMain's checks alone, writing nothing: the registration committed as it stands, the
     * ledger with no lines not committed, and no line of the ledger a look at another registration
     * (otherLooks) unless secondLook, which the tool's --second-look gives and its ledger line and
     * report then say. Gives the registration's blob hash. A tool that has further refusals of its
     * own checks here first and appends to the ledger only once every one of them has passed.
     */
    static String checkOnMain(Listing counts, boolean secondLook) throws IOException {
        if (!counts.registered) {
            throw new IllegalStateException(counts.file + " is a candidates file; the main file scores only the counts "
                    + "registered in " + REGISTERED + " (COUNT_DESIGN.md, section 5)");
        }
        String blob = committedBlob(counts.file);
        Path ledger = ledgerOf(counts);
        committedBlob(ledger);
        List<String> other = otherLooks(ledger, registeredBlobs(counts, blob));
        if (!other.isEmpty() && !secondLook) {
            throw new IllegalStateException(ledger + " already holds " + other.size() + " look" + (other.size() == 1 ? "" : "s")
                    + " at another registration than " + counts.file.getFileName() + " (blob " + blob + "), the first: "
                    + other.get(0) + ". A look at a changed registration is a second look, and its held-out claim needs a "
                    + "fresh file (COUNT_DESIGN.md, section 5); give --second-look to make it all the same, and the "
                    + "ledger and the report say so");
        }
        return blob;
    }

    /**
     * The blobs of one registration, whose ledger lines make up its one look: the file's own,
     * blob; for a registration, the full-play registration it names, whose committed blob must be
     * the one it names; for a full-play registration, the registration it belongs to, committed as
     * it stands, which must name this file and this blob. Refuses where they do not tie up.
     */
    static java.util.Set<String> registeredBlobs(Listing counts, String blob) throws IOException {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        out.add(blob);
        Path dir = counts.file.toAbsolutePath().normalize().getParent();
        if (counts.fullPlayFile != null) {
            String was = committedBlob(dir.resolve(counts.fullPlayFile));
            if (!was.equals(counts.fullPlayBlob)) {
                throw new IllegalStateException(counts.fullPlayFile + " is committed as blob " + was + ", not the blob "
                        + counts.fullPlayBlob + " that " + counts.file.getFileName() + " registers; a full-play registration "
                        + "changed after its registration is a second registration (COUNT_DESIGN.md, section 5)");
            }
            out.add(was);
        }
        if (counts.registeredWith != null) {
            Path with = dir.resolve(counts.registeredWith);
            String withBlob = committedBlob(with);
            Listing primary = read(with);
            String name = counts.file.getFileName().toString();
            if (!primary.registered || !name.equals(primary.fullPlayFile) || !blob.equals(primary.fullPlayBlob)) {
                throw new IllegalStateException(counts.file.getFileName() + " says it belongs to " + counts.registeredWith
                        + ", which does not register it as it stands (blob " + blob + "): it names "
                        + primary.fullPlayFile + ", blob " + primary.fullPlayBlob);
            }
            out.add(withBlob);
        }
        return out;
    }

    /** The ledger's lines, the header left out, whose blob is none of those given: looks at another registration. */
    static List<String> otherLooks(Path ledger, java.util.Set<String> blobs) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.exists(ledger)) {
            return out;
        }
        List<String> lines = Files.readAllLines(ledger, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.isEmpty() || (i == 0 && l.equals(LEDGER_HEADER))) {
                continue;
            }
            String[] f = l.split("\t", -1);
            if (f.length < 2 || !blobs.contains(f[1])) {
                out.add(l);
            }
        }
        return out;
    }

    /** The ledger beside a counts file. */
    static Path ledgerOf(Listing counts) {
        return counts.file.toAbsolutePath().normalize().resolveSibling(LEDGER);
    }

    /** The git blob hash of a file committed as it stands; refuses one not tracked, or changed since its commit. */
    static String committedBlob(Path file) throws IOException {
        Path abs = file.toAbsolutePath().normalize();
        Path dir = abs.getParent();
        String name = abs.getFileName().toString();
        if (!Files.isRegularFile(abs)) {
            throw new IllegalStateException(abs + " does not exist");
        }
        if (git(dir, "ls-files", "--error-unmatch", "--", name) == null) {
            throw new IllegalStateException(abs + " is not committed; commit it before the main file is looked at");
        }
        String status = git(dir, "status", "--porcelain", "--", name);
        if (status == null || !status.isEmpty()) {
            throw new IllegalStateException(abs + " has changes not committed; commit them first, and a change after a "
                    + "main-file look is a second look (COUNT_DESIGN.md, section 5)");
        }
        String head = git(dir, "rev-parse", "HEAD:./" + name);
        String now = git(dir, "hash-object", "--", name);
        if (head == null || !head.equals(now)) {
            throw new IllegalStateException(abs + " differs from its committed blob");
        }
        return now;
    }

    /** Appends one look to the ledger, writing its header first when the ledger is new. */
    static void appendLedger(Path ledger, String commit, String blob, String what) throws IOException {
        if (what.contains("\t") || what.contains("\n")) {
            throw new IllegalArgumentException("a ledger line's description is one line without tabs");
        }
        if (!Files.exists(ledger)) {
            Files.write(ledger, (LEDGER_HEADER + "\n").getBytes(StandardCharsets.UTF_8));
        }
        String line = commit + "\t" + blob + "\t" + java.time.OffsetDateTime.now().withNano(0) + "\t" + what + "\n";
        Files.write(ledger, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }

    /** git's output, trimmed, or null when it fails. Only reads. */
    static String git(Path dir, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(dir.toString());
        Collections.addAll(cmd, args);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] out;
            try (InputStream in = p.getInputStream()) {
                out = in.readAllBytes();
            }
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            throw new UncheckedIOException("running git", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running git", e);
        }
    }
}
