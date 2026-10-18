import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The verification files of PLAN_PAGE.md 3.2: manifest.json, SHA256SUMS in the coreutils format
 * and MANIFEST.md, the same as two tables.
 *
 * SHA256SUMS lists only the files that ship with the page, each by its path under data/, where
 * SHA256SUMS itself sits, so {@code shasum -a 256 -c SHA256SUMS} run there reads every line. The
 * run files the figures were read from do not ship with the page, and may not be kept at all
 * (DISK_RESULTS_ONLY.md), so their hashes are listed apart, as recorded values: manifest.json's
 * "recorded" and MANIFEST.md's second table. A reader cannot check those from the page alone.
 * Until 18 October 2026 they were in SHA256SUMS too, by file name: in runs/page-613255a/data, 14
 * of its 16 lines named files the page did not have, two of them both "results.ser".
 *
 * What a hash proves is narrow, and the manifest says so in its own words: that a file is, byte
 * for byte, the file listed. That the listed commit made it is the publisher's statement. Running
 * the code again tests the figures, not the hashes of the saved results: each .ser records its
 * own run times (SystemScorer's Result.seconds, FullIndexFit's PassRecord.wallSeconds), so a new
 * run writes other bytes even when every figure comes back the same. Whether the code is right is
 * a different question, answered by the checks on the page, not by any hash. The page's own HTML
 * is not listed, since it shows the manifest's hash.
 *
 * Recorded files are listed as the inputs describe them: every entry of each input's
 * _source.artifacts, with whatever it records (path, bytes, sha256, commit, produced_by). Two
 * inputs that give the same path two different hashes stop the build.
 */
final class SiteManifest {

    final String json;
    final String sums;
    final String md;
    /** The recorded files, for the page's second files table. */
    final List<Map<String, Object>> recorded;

    private SiteManifest(String json, String sums, String md, List<Map<String, Object>> recorded) {
        this.json = json;
        this.sums = sums;
        this.md = md;
        this.recorded = recorded;
    }

    /** The SHA-256 of manifest.json, which the page shows. */
    String sha256() {
        return SiteData.sha256(json.getBytes(StandardCharsets.UTF_8));
    }

    static final String ABOUT = "A SHA-256 hash proves that a file is, byte for byte, the file it was taken from. "
            + "The files under served ship with the page, and SHA256SUMS lists them, so shasum -a 256 -c SHA256SUMS "
            + "checks them. The hashes under recorded were taken of the run files the figures were read from. "
            + "Those files do not ship with the page, so their hashes cannot be checked from it alone. "
            + "That the listed commits produced these files is the publisher's statement. "
            + "A hash says nothing about whether the code is right; a bug is hashed just as faithfully.";

    /** The folder SHA256SUMS sits in, under card-counting/. Every served file is under it. */
    static final String DATA = "data/";

    /** A served file's name in SHA256SUMS: its path under data/, so no two files share one. */
    static String listed(String path) {
        if (!path.startsWith(DATA) || path.length() == DATA.length()) {
            throw new IllegalArgumentException(path + " is served outside " + DATA + ", where SHA256SUMS cannot name it");
        }
        return path.substring(DATA.length());
    }

    /**
     * Builds the three files. served maps each data file's path under card-counting/ to its bytes;
     * every one is listed in SHA256SUMS by its path under data/. The inputs' artifacts are listed
     * as recorded, in manifest.json and MANIFEST.md only.
     */
    @SuppressWarnings("unchecked")
    static SiteManifest of(SiteData in, Map<String, byte[]> served, String version, String date) {
        Map<String, Object> root = new LinkedHashMap<>();
        boolean fixture = in.fixture();
        if (fixture) {
            root.put("FIXTURE", "Built from made-up inputs to test the generator. The recorded files and hashes "
                    + "below are not real files.");
        }
        root.put("_about", ABOUT);
        root.put("version", version);
        root.put("date", date);
        root.put("fixture", fixture);
        root.put("java", Runtime.version().toString());
        List<Object> sv = new ArrayList<>();
        StringBuilder sums = new StringBuilder();
        for (Map.Entry<String, byte[]> e : served.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", e.getKey());
            m.put("bytes", (long) e.getValue().length);
            m.put("sha256", SiteData.sha256(e.getValue()));
            sv.add(m);
            sums.append(m.get("sha256")).append("  ").append(listed(e.getKey())).append('\n');
        }
        root.put("served", sv);
        List<Object> ins = new ArrayList<>();
        Map<String, Map<String, Object>> recorded = new TreeMap<>();
        for (SiteData.Input i : in.inputs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", "runs/site/" + i.name);
            m.put("sha256", i.sha256);
            m.put("what", i.what());
            m.put("commit", i.commit());
            ins.add(m);
            Object a = i.source.get("artifacts");
            if (a instanceof List) {
                for (Object o : (List<Object>) a) {
                    Map<String, Object> art = (Map<String, Object>) o;
                    String path = (String) art.get("path");
                    Map<String, Object> was = recorded.get(path);
                    if (was != null && !String.valueOf(was.get("sha256")).equals(String.valueOf(art.get("sha256")))) {
                        throw new IllegalArgumentException(path + " has two different SHA-256s in the inputs");
                    }
                    if (was == null) {
                        Map<String, Object> copy = new LinkedHashMap<>(art);
                        copy.putIfAbsent("commit", i.commit());
                        recorded.put(path, copy);
                    }
                }
            }
        }
        root.put("inputs", ins);
        root.put("recorded", new ArrayList<Object>(recorded.values()));
        String json = Json.write(root, 3);
        StringBuilder md = new StringBuilder("# Manifest\n\n");
        if (fixture) {
            md.append("**FIXTURE: built from made-up inputs to test the generator. Nothing here is a result.**\n\n");
        }
        md.append(ABOUT).append("\n\n## Served\n\n| File | Bytes | SHA-256 | Made by |\n|---|---|---|---|\n");
        for (Object o : sv) {
            Map<String, Object> m = (Map<String, Object>) o;
            md.append("| ").append(m.get("path")).append(" | ").append(m.get("bytes")).append(" | `").append(m.get("sha256"))
                    .append("` | SiteBuild |\n");
        }
        md.append("\n## Recorded\n\n| File | Bytes | SHA-256 | Commit |\n|---|---|---|---|\n");
        for (Map<String, Object> a : recorded.values()) {
            md.append("| ").append(a.get("path")).append(" | ").append(a.getOrDefault("bytes", "")).append(" | `")
                    .append(a.getOrDefault("sha256", "")).append("` | ").append(a.get("commit")).append(" |\n");
        }
        return new SiteManifest(json, sums.toString(), md.toString(), new ArrayList<>(recorded.values()));
    }

    /** One line of SHA256SUMS as shasum reads it: the hash, a space, a space or a star, the name. */
    private static final Pattern LINE = Pattern.compile("([0-9a-f]{64}) [ *](.+)");

    /**
     * What a reader's {@code shasum -a 256 -c SHA256SUMS} does in the folder SHA256SUMS sits in:
     * each line's file is opened by its name and hashed. open gives the bytes of the file of that
     * name, or null when it cannot be read. Returns every line that does not pass, in shasum's
     * words where it has them: a file that could not be read, a hash that differs, a line not in
     * the format, and a name listed twice. shasum allows the last; the page does not, since two
     * files of one name cannot sit in one folder.
     */
    static List<String> check(String sums, Function<String, byte[]> open) {
        List<String> bad = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[] lines = sums.split("\n");
        for (int n = 0; n < lines.length; n++) {
            if (lines[n].isEmpty() && n == lines.length - 1) {
                continue;
            }
            Matcher m = LINE.matcher(lines[n]);
            if (!m.matches()) {
                bad.add("line " + (n + 1) + ": not a SHA-256 line");
                continue;
            }
            String name = m.group(2);
            if (!seen.add(name)) {
                bad.add(name + ": listed twice");
            }
            byte[] f = open.apply(name);
            if (f == null) {
                bad.add(name + ": FAILED open or read");
            } else if (!SiteData.sha256(f).equals(m.group(1))) {
                bad.add(name + ": FAILED");
            }
        }
        return bad;
    }

    /** The same check, on the SHA256SUMS in folder and the files beside it, as they sit on disk. */
    static List<String> check(Path folder) throws IOException {
        String sums = new String(Files.readAllBytes(folder.resolve("SHA256SUMS")), StandardCharsets.UTF_8);
        return check(sums, name -> {
            try {
                return Files.readAllBytes(folder.resolve(name));
            } catch (IOException e) {
                return null;
            }
        });
    }
}
