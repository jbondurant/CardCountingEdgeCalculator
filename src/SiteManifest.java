import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The verification files of PLAN_PAGE.md 3.2: manifest.json, SHA256SUMS in the coreutils format
 * (so {@code shasum -a 256 -c SHA256SUMS} checks every download in one line) and MANIFEST.md, the
 * same as a table.
 *
 * What a hash proves is narrow, and the manifest says so in its own words: that a file is, byte
 * for byte, the file listed. That the listed commit made it is the publisher's statement, which a
 * reader tests by reproducing it; whether the code is right is a different question, answered by
 * the checks on the page, not by any hash. The page's own HTML is not listed, since it shows the
 * manifest's hash.
 *
 * Artifacts are listed as the inputs describe them: every entry of each input's
 * _source.artifacts, with whatever it records (path, bytes, sha256, commit, produced_by). Two
 * inputs that give the same path two different hashes stop the build.
 */
final class SiteManifest {

    final String json;
    final String sums;
    final String md;
    /** The artifacts as listed, for the page's files table. */
    final List<Map<String, Object>> artifacts;

    private SiteManifest(String json, String sums, String md, List<Map<String, Object>> artifacts) {
        this.json = json;
        this.sums = sums;
        this.md = md;
        this.artifacts = artifacts;
    }

    /** The SHA-256 of manifest.json, which the page shows. */
    String sha256() {
        return SiteData.sha256(json.getBytes(StandardCharsets.UTF_8));
    }

    static final String ABOUT = "A SHA-256 hash proves that a file is, byte for byte, the file listed here. That the "
            + "listed commits produced it is the publisher's statement, which a reader can test by reproducing it. "
            + "A hash says nothing about whether the code is right; a bug is hashed just as faithfully.";

    static String base(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Builds the three files. served maps each data file's path under card-counting/ to its bytes;
     * every one is listed in SHA256SUMS by its name in data/, beside the artifacts by their file
     * names, as they sit in the Release.
     */
    @SuppressWarnings("unchecked")
    static SiteManifest of(SiteData in, Map<String, byte[]> served, String version, String date) {
        Map<String, Object> root = new LinkedHashMap<>();
        boolean fixture = in.fixture();
        if (fixture) {
            root.put("FIXTURE", "Built from made-up inputs to test the generator. The artifacts and hashes "
                    + "below are not real files.");
        }
        root.put("_about", ABOUT);
        root.put("version", version);
        root.put("date", date);
        root.put("fixture", fixture);
        root.put("java", Runtime.version().toString());
        List<Object> sv = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : served.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", e.getKey());
            m.put("bytes", (long) e.getValue().length);
            m.put("sha256", SiteData.sha256(e.getValue()));
            sv.add(m);
        }
        root.put("served", sv);
        List<Object> ins = new ArrayList<>();
        Map<String, Map<String, Object>> artifacts = new TreeMap<>();
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
                    Map<String, Object> was = artifacts.get(path);
                    if (was != null && !String.valueOf(was.get("sha256")).equals(String.valueOf(art.get("sha256")))) {
                        throw new IllegalArgumentException(path + " has two different SHA-256s in the inputs");
                    }
                    if (was == null) {
                        Map<String, Object> copy = new LinkedHashMap<>(art);
                        copy.putIfAbsent("commit", i.commit());
                        artifacts.put(path, copy);
                    }
                }
            }
        }
        root.put("inputs", ins);
        root.put("artifacts", new ArrayList<Object>(artifacts.values()));
        String json = Json.write(root, 3);
        StringBuilder sums = new StringBuilder();
        StringBuilder md = new StringBuilder("# Manifest\n\n");
        if (fixture) {
            md.append("**FIXTURE: built from made-up inputs to test the generator. Nothing here is a result.**\n\n");
        }
        md.append(ABOUT).append("\n\n| File | Bytes | SHA-256 | Made by |\n|---|---|---|---|\n");
        for (Object o : sv) {
            Map<String, Object> m = (Map<String, Object>) o;
            sums.append(m.get("sha256")).append("  ").append(base((String) m.get("path"))).append('\n');
            md.append("| ").append(m.get("path")).append(" | ").append(m.get("bytes")).append(" | `").append(m.get("sha256"))
                    .append("` | SiteBuild |\n");
        }
        for (Map<String, Object> a : artifacts.values()) {
            if (a.get("sha256") instanceof String) {
                sums.append(a.get("sha256")).append("  ").append(base((String) a.get("path"))).append('\n');
            }
            md.append("| ").append(a.get("path")).append(" | ").append(a.getOrDefault("bytes", "")).append(" | `")
                    .append(a.getOrDefault("sha256", "")).append("` | ").append(a.get("commit")).append(" |\n");
        }
        return new SiteManifest(json, sums.toString(), md.toString(), new ArrayList<>(artifacts.values()));
    }

    /**
     * Checks SHA256SUMS against files: each line's hash must be the SHA-256 of the file of that name.
     * Returns the lines that fail or name a file not given. This is what a reader's shasum -c does.
     */
    static List<String> check(String sums, Map<String, byte[]> files) {
        List<String> bad = new ArrayList<>();
        for (String line : sums.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String hash = line.substring(0, 64);
            String name = line.substring(66);
            byte[] f = files.get(name);
            if (f == null) {
                bad.add(name + ": not given");
            } else if (!SiteData.sha256(f).equals(hash)) {
                bad.add(name + ": FAILED");
            }
        }
        return bad;
    }
}
