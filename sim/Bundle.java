package sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An export bundle, {@code runs/sim/export-<sha>/} (SHOE_SPEC.md B0), opened and checked: every
 * file listed in manifest.sha256 must have that SHA-256, every file of index.tsv must be listed
 * there with the size index.tsv gives, and the chart must rehash to its fingerprint. The bundle's
 * own identity, written into every chunk and summary, is the SHA-256 of its manifest.sha256.
 */
public final class Bundle {

    public final Path dir;
    public final String sha256;
    public final Chart chart;
    public final Exact exact;
    /** Strategy files by row, penetration and fold, in index.tsv's order. */
    public final Map<String, Strategy> strategies = new LinkedHashMap<>();
    private final Map<String, String[]> index = new LinkedHashMap<>();

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** B0's slug: the row's name in lower case, each run of other characters than a-z and 0-9 one '-', none at the ends. */
    static String slug(String row) {
        return row.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    static String key(String row, int pen, int fold) {
        return row + "\t" + pen + "\t" + fold;
    }

    public Bundle(Path dir, Plants plants) throws IOException {
        this.dir = dir;
        Path manifest = dir.resolve("manifest.sha256");
        this.sha256 = sha256(manifest);
        Map<String, String> listed = new LinkedHashMap<>();
        String previous = "";
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            int gap = line.indexOf("  ");
            if (gap != 64 || !line.substring(0, 64).matches("[0-9a-f]{64}")) {
                throw new IOException("manifest.sha256: not '<sha256>  <path>': " + line);
            }
            String path = line.substring(66);
            if (path.compareTo(previous) <= 0) {
                throw new IOException("manifest.sha256 not sorted by path at " + path);
            }
            previous = path;
            if (path.contains("..") || path.startsWith("/")) {
                throw new IOException("manifest.sha256: a path outside the bundle, " + path);
            }
            String actual = sha256(dir.resolve(path));
            if (!actual.equals(line.substring(0, 64))) {
                throw new IOException(path + " has SHA-256 " + actual + ", the manifest says " + line.substring(0, 64));
            }
            listed.put(path, actual);
        }
        List<String> rows = Files.readAllLines(dir.resolve("index.tsv"), StandardCharsets.UTF_8);
        if (rows.isEmpty() || !rows.get(0).equals("path\tbytes\trow\tpen\tfold\tscope")) {
            throw new IOException("index.tsv: no header");
        }
        if (!listed.containsKey("index.tsv")) {
            throw new IOException("index.tsv is not in the manifest");
        }
        for (String line : rows.subList(1, rows.size())) {
            String[] f = line.split("\t", -1);
            if (f.length != 6 || !listed.containsKey(f[0]) || Files.size(dir.resolve(f[0])) != Long.parseLong(f[1])) {
                throw new IOException("index.tsv: '" + line + "' is not a listed file with its size");
            }
            index.put(f[0], f);
        }
        java.util.Set<String> expected = new java.util.TreeSet<>(listed.keySet());
        expected.remove("index.tsv");
        if (!expected.equals(new java.util.TreeSet<>(index.keySet()))
                || !new ArrayList<>(index.keySet()).equals(new ArrayList<>(expected))) {
            throw new IOException("index.tsv must list every file of the manifest but itself, sorted by path");
        }
        this.chart = Chart.load(dir.resolve("chart.tsv"));
        this.exact = Exact.read(dir.resolve("exact.tsv"));
        for (String[] f : index.values()) {
            if (!f[2].equals("-")) {
                Strategy s = Strategy.load(dir.resolve(f[0]), plants);
                if (!s.row.equals(f[2]) || s.pen != Integer.parseInt(f[3]) || s.fold != Integer.parseInt(f[4])
                        || !s.scope.equals(f[5]) || !f[0].equals("strategy-" + slug(s.row) + "-p" + s.pen + "-f" + s.fold + ".txt")) {
                    throw new IOException(f[0] + " is not what index.tsv says it is");
                }
                if (strategies.put(key(s.row, s.pen, s.fold), s) != null) {
                    throw new IOException("two files for " + s.row + " at " + s.pen + "%, fold " + s.fold);
                }
            }
        }
    }

    /** Every row with both fold files, at every penetration it has, laid out in order; the chart's walk first. */
    public List<Row> rows(boolean withChartWalk) {
        List<Row> out = new ArrayList<>();
        int offset = 0;
        if (withChartWalk) {
            for (int pen : Shoe.PENS) {
                Row r = Row.chart(pen, offset);
                out.add(r);
                offset += r.size();
            }
        }
        for (Strategy s : strategies.values()) {
            if (withChartWalk && s.row.equals(Row.CHART)) {
                continue;
            }
            if (s.fold == 0 && strategies.containsKey(key(s.row, s.pen, 1))) {
                Row r = Row.of(s, strategies.get(key(s.row, s.pen, 1)), offset);
                out.add(r);
                offset += r.size();
            }
        }
        return out;
    }

    public States states() throws IOException {
        return States.read(dir.resolve("states.bin"));
    }
}
