import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One input file of the page generator in SiteData's contract (_source, figures, grid, names,
 * tables), as the adapters build it from a computation's own output: SiteExport from results.ser,
 * and SiteAdapt from the risk, spreads, key-edges, ceiling, literature and simulator files. Each
 * artifact read is listed in _source with its size and SHA-256, so results.json can say what every
 * figure was read from; an adapter never writes a figure it did not read or compute from those.
 *
 * Every figure here is in the page's unit, percent of a unit a round, unless its key says
 * otherwise: the computations keep fractions of a unit, and the adapters multiply by 100 once, in
 * pct(), never in the page.
 */
final class SiteInput {

    final Map<String, Object> root = new LinkedHashMap<>();
    final Map<String, Object> source = new LinkedHashMap<>();
    final Map<String, Object> figures = new LinkedHashMap<>();
    final Map<String, Object> grid = new LinkedHashMap<>();
    final Map<String, Object> names = new LinkedHashMap<>();
    final Map<String, Object> tables = new LinkedHashMap<>();
    final List<Object> artifacts = new ArrayList<>();

    /** An input from the computation "what" at commit, on the main file or the search file. */
    SiteInput(String what, String commit, String file) {
        if (commit == null || commit.isEmpty()) {
            throw new IllegalArgumentException(what + ": an input needs the commit of the code that made it");
        }
        source.put("what", what);
        source.put("commit", commit);
        source.put("fixture", false);
        source.put("file", file);
        source.put("artifacts", artifacts);
    }

    /** Lists a file read, with its size and SHA-256, under the path the page names it by. */
    SiteInput artifact(Path file, String shown) throws IOException {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("path", shown);
        a.put("bytes", Files.size(file));
        a.put("sha256", sha256(file));
        artifacts.add(a);
        return this;
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file), 1 << 20)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 20];
            for (int n = in.read(buf); n > 0; n = in.read(buf)) {
                md.update(buf, 0, n);
            }
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) {
                b.append(String.format("%02x", x & 0xff));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A fraction of a unit as the page's percent. */
    static double pct(double fraction) {
        return 100 * fraction;
    }

    /** A figure: its value and the format the page prints it in; more fields are put on the map returned. */
    Map<String, Object> figure(String key, double value, String fmt) {
        if (!SiteData.KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("\"" + key + "\" is not a figure key");
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(key + " has no value (" + value + "); a figure not worked out is left out");
        }
        SiteText.checkFormat(fmt, key);
        if (figures.containsKey(key)) {
            throw new IllegalArgumentException(key + " is written twice");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("fmt", fmt);
        figures.put(key, m);
        return m;
    }

    /** A figure with its SE and 95% interval, in percent from fractions; widen above 1 marks it widened. */
    Map<String, Object> figure(String key, Bootstrap.Interval iv, String fmt, double widen) {
        Map<String, Object> m = figure(key, pct(iv.estimate), fmt);
        m.put("se", pct(iv.se));
        m.put("ci", List.of(pct(iv.lo), pct(iv.hi)));
        widened(m, widen);
        return m;
    }

    /** Marks a figure's interval as widened by ratio (FULL_INDEX_PLAYS.md section 4), or as needing none. */
    static void widened(Map<String, Object> m, double ratio) {
        if (!(ratio >= 1)) {
            throw new IllegalArgumentException("a widening ratio below 1 would narrow an interval: " + ratio);
        }
        m.put("widen", ratio);
        m.put("widened", true);
    }

    /** Resamples in percent, for the claim guards (SiteGuards); they are not written to results.json. */
    static List<Object> boot(double[] fractions) {
        List<Object> out = new ArrayList<>(fractions.length);
        for (double x : fractions) {
            out.add(pct(x));
        }
        return out;
    }

    /** Where a figure was read, when not the first artifact: an object path in it. */
    static void at(Map<String, Object> m, String src, String at) {
        if (src != null) {
            m.put("src", src);
        }
        m.put("at", at);
    }

    @SuppressWarnings("unchecked")
    void cell(String row, String scope, String pen, String spread, double[] v) {
        Map<String, Object> r = (Map<String, Object>) grid.computeIfAbsent(row, x -> new LinkedHashMap<>());
        Map<String, Object> s = (Map<String, Object>) r.computeIfAbsent(scope, x -> new LinkedHashMap<>());
        Map<String, Object> p = (Map<String, Object>) s.computeIfAbsent(pen, x -> new LinkedHashMap<>());
        List<Object> c = new ArrayList<>();
        for (double x : v) {
            c.add(Double.isNaN(x) ? null : x);
        }
        if (p.put(spread, c) != null) {
            throw new IllegalArgumentException("grid cell " + row + "/" + scope + "/" + pen + "/" + spread + " written twice");
        }
    }

    /** The input as SiteData reads it, its empty blocks left out. */
    Map<String, Object> json() {
        root.clear();
        root.put("_source", source);
        if (!figures.isEmpty()) {
            root.put("figures", figures);
        }
        if (!grid.isEmpty()) {
            root.put("grid", grid);
        }
        if (!names.isEmpty()) {
            root.put("names", names);
        }
        if (!tables.isEmpty()) {
            root.put("tables", tables);
        }
        return root;
    }

    /** The input's text, read back through SiteData's own checks before it is written, so a file SiteData refuses is never written. */
    String text(String name) {
        String t = Json.write(json(), 3);
        new SiteData().add(name, t.getBytes(StandardCharsets.UTF_8));
        return t;
    }

    /** Writes the file whole, never over one already there. */
    void write(Path file) throws IOException {
        String t = text(file.getFileName().toString());
        if (Files.exists(file)) {
            throw new IllegalStateException(file + " exists; an input is never written over");
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, t.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** A catalog name as a figure key's row: lower case, words joined by underscores. */
    static String slug(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    /** A catalog name without its parenthesis, as the page names a count. */
    static String shortName(String name) {
        return name.replaceAll("\\s*\\(.*\\)", "").trim();
    }

    /** The play scopes' suffixes an entry's name carries after its count's name (SystemScorer.score). */
    static final String[] SUFFIXES = {", full play, bet on its own value", ", bet on its own value", ", full play",
            ", betting only"};

    /**
     * A row's page id and name from the name its entry carries in the results files, by the rule
     * SiteExport names the rows by: a catalog entry by its first system without the parenthesis
     * ("Hi-Lo = Hi-Lo Lite" is hi_lo, Hi-Lo), its ace side-count variant with _ace and " + ace",
     * the null count null; the play scope's suffix left off. {id, name}; null for a name of no
     * catalog row (a reference, bound or registered row, which each adapter names itself).
     */
    static String[] catalogRow(String entry) {
        String n = entry;
        for (String s : SUFFIXES) {
            if (n.endsWith(s)) {
                n = n.substring(0, n.length() - s.length());
                break;
            }
        }
        if (n.equals("Null count (no count)")) {
            return new String[]{"null", "Null count"};
        }
        boolean ace = n.endsWith(" + ace side count");
        if (ace) {
            n = n.substring(0, n.length() - " + ace side count".length());
        }
        String first = n.split(" = ")[0];
        if (!CountKeys.SIDE_COUNTED.contains(first) && ace) {
            return null;
        }
        for (CountingSystem c : CountingSystem.all()) {
            if (c.name.equals(first)) {
                return new String[]{slug(shortName(first)) + (ace ? "_ace" : ""), shortName(first) + (ace ? " + ace" : "")};
            }
        }
        return null;
    }
}
