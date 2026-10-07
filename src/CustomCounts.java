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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Counts of our own, as a file lists them (COUNT_DESIGN.md, section 5), and the rules that keep
 * the main file to one look.
 *
 * Two kinds of file hold them, in one format:
 * - a candidates file, written as the search goes, for scoring on the twin file;
 * - custom-counts.json at the root of the repository, the registration: the counts chosen on the
 *   twin, fixed before any main-file run, with the twin file's header, the commit the search ran
 *   from, which count is reported in each cell and each headline row's comparators.
 *
 * The format (JSON):
 * <pre>
 * { "kind": "candidates" or "registered", "K": 10000,
 *   "twin": "the twin file's header", "searchCommit": "...",      (registered)
 *   "cells": {...}, "comparators": {...},                           (registered, kept as text)
 *   "counts": [
 *     { "name": "...", "family": "F1", "role": "headline", "comparators": "plain" or "all",
 *       "form": "true" or "running", "divisor": 500,
 *       "tags": [A, 2, 3, 4, 5, 6, 7, 8, 9, T],                     whole numbers at K
 *       "betting": [A, ..., T] },                                   optional: F2's betting vector
 *     { "name": "...", "role": "control", "catalog": "Hi-Opt II", "divisor": 500 } ] }
 * </pre>
 * A "catalog" entry is that catalog count at grain divisor / K (CountKeys.atGrain), or as the
 * catalog has it when no divisor is given; it keeps its systems, so its side-count variant comes
 * with it. Tags are read as whole numbers and nothing is normalised or rounded, so the count
 * scored is the count registered, to the bit.
 *
 * The rules (section 5, "One look"): the search, calibration and theory-pass tools refuse the
 * main file (requireTwin); a main-file tool given counts refuses a file that is not the
 * registration, or is not committed as it stands, and a ledger with lines not committed
 * (lookOnMain), and appends to the ledger the line that records its look.
 */
final class CustomCounts {

    /** The main state file's seed, runs/montreal-80.states: nothing is chosen on it. */
    static final long MAIN_SEED = 20260928L;
    /** The twin file's seed (section 4). */
    static final long TWIN_SEED = 20261007L;
    /** The registration and the ledger, at the root of the repository. */
    static final String REGISTERED = "custom-counts.json";
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
        /** The cells and comparators as the file holds them, for the report to print. */
        final String cells;
        final String comparators;

        Listing(Path file, boolean registered, List<Entry> entries, String twin, String searchCommit, String cells,
                String comparators) {
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
    }

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
            Entry e = entry(file, c);
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
        Object comparators = d.get("comparators");
        return new Listing(file, registered, entries, twin, commit, cells == null ? null : cells.toString(),
                comparators == null ? null : comparators.toString());
    }

    private static Entry entry(Path file, Document c) {
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

    /**
     * lookOnMain's checks alone, writing nothing: the registration committed as it stands and the
     * ledger with no lines not committed. Gives the registration's blob hash. A tool that has
     * further refusals of its own checks here first and appends to the ledger once they pass.
     */
    static String checkOnMain(Listing counts) throws IOException {
        if (!counts.registered) {
            throw new IllegalStateException(counts.file + " is a candidates file; the main file scores only the counts "
                    + "registered in " + REGISTERED + " (COUNT_DESIGN.md, section 5)");
        }
        String blob = committedBlob(counts.file);
        committedBlob(ledgerOf(counts));
        return blob;
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
