import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The figures the results page shows, merged from the JSON files the computations write into
 * runs/site/ (PLAN_PAGE.md 2.4, PLAN_COMPUTE.md section 0), and written out as the page's
 * data/results.json.
 *
 * <h2>The input contract</h2>
 * Every input is one JSON object:
 * <pre>
 * { "_source": { "what": "results", "commit": "c06c061…", "fixture": false, "file": "main",
 *                "artifacts": [ {"path": "runs/full-index/full-c06c061/results.ser", "sha256": "…"} ] },
 *   "figures": { "KEY": {"value": 0.36, "se": 0.0019, "ci": [0.36, 0.37], "fmt": "s4",
 *                        "src": "…", "at": "…", "widen": 1.03, "widened": true, "file": "main",
 *                        "boot": [ … ] } },
 *   "grid":    { "ROW": { "SCOPE": { "PEN": { "SPREAD": [value, se, lo, hi] } } } },
 *   "names":   { "ID": "display name" },
 *   "tables":  { "NAME": [ … ] } }
 * </pre>
 * Only {@code _source} is required. {@code fixture} must be given either way, so that a file of
 * made-up numbers can never be taken for a real one: a single fixture input marks the whole build
 * as a fixture, and the page says so at the top of every screen.
 *
 * A grid cell is the figure whose key is SCOPE.PEN.SPREAD.ROW (for example
 * {@code full.80.s8.hilo}). The grid exists for size: the page can switch among about 80 rows ×
 * 3 penetrations × 10 spreads × 2 play scopes, wonging on and off (PLAN_PAGE.md 2.5), and that
 * many records with their sources spelled out would not fit the 500 KB budget of results.json.
 * A grid cell's source is its input file's, and its "at" is its key.
 *
 * <h2>Rules held here</h2>
 * <ul>
 *   <li>One number, one value: a key given by two inputs must print the same at its format, or the
 *   build stops.</li>
 *   <li>A full-play interval (a key whose first part is full, fullw or gain, or whose figure says
 *   scope full) must say it was widened (FULL_INDEX_PLAYS.md section 4), or the build stops.</li>
 *   <li>A figure from the search file (the twin) carries file "search", and the page may print it
 *   only inside an element marked data-file="search" (SiteBuild checks that).</li>
 * </ul>
 * Resamples ({@code boot}) are read for the claim guards (SiteGuards) and are not written to
 * results.json: they are in runs/site, which the manifest lists.
 */
final class SiteData {

    /** A figure: its value and interval, where it came from, and how the page prints it. */
    static final class Figure {
        final String key;
        final double value;
        final double se;
        final double lo;
        final double hi;
        final String fmt;
        final String src;
        final String at;
        final String commit;
        final String file;
        final double widen;
        final boolean widened;
        final double[] boot;

        Figure(String key, double value, double se, double lo, double hi, String fmt, String src, String at,
               String commit, String file, double widen, boolean widened, double[] boot) {
            this.key = key;
            this.value = value;
            this.se = se;
            this.lo = lo;
            this.hi = hi;
            this.fmt = fmt;
            this.src = src;
            this.at = at;
            this.commit = commit;
            this.file = file;
            this.widen = widen;
            this.widened = widened;
            this.boot = boot;
        }

        boolean hasInterval() {
            return !Double.isNaN(lo) && !Double.isNaN(hi);
        }
    }

    /** One input file as read: its name, its SHA-256 and its _source block. */
    static final class Input {
        final String name;
        final String sha256;
        final Map<String, Object> source;

        Input(String name, String sha256, Map<String, Object> source) {
            this.name = name;
            this.sha256 = sha256;
            this.source = source;
        }

        String what() {
            return (String) source.get("what");
        }

        String commit() {
            return (String) source.get("commit");
        }

        boolean fixture() {
            return Boolean.TRUE.equals(source.get("fixture"));
        }
    }

    /** A figure key: lower-case words joined by dots, with underscores inside a word. */
    static final Pattern KEY = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)*");

    /** The scopes whose intervals rest on later plays held as fitted, and so must be widened. */
    static final List<String> WIDENED_SCOPES = List.of("full", "fullw", "gain");

    /** The grid's scopes, in the order the page's controls list them. */
    static final List<String> SCOPES = List.of("bet", "fd", "full", "fdw", "fullw");

    final List<Input> inputs = new ArrayList<>();
    final Map<String, Figure> figures = new TreeMap<>();
    /** row -> scope -> pen -> spread -> {value, se, lo, hi}, each map in key order. */
    final Map<String, Map<String, Map<String, Map<String, double[]>>>> grid = new TreeMap<>();
    /** row -> the input that gave its grid cells, for their source. */
    final Map<String, Input> gridSource = new TreeMap<>();
    final Map<String, String> names = new TreeMap<>();
    final Map<String, Object> tables = new TreeMap<>();
    final List<Map<String, Object>> guards = new ArrayList<>();

    boolean fixture() {
        for (Input in : inputs) {
            if (in.fixture()) {
                return true;
            }
        }
        return false;
    }

    /** Reads every *.json in dir, in name order, so the merge is the same on every build. */
    static SiteData read(Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().collect(Collectors.toList());
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("no input files (*.json) in " + dir);
        }
        SiteData d = new SiteData();
        for (Path p : files) {
            d.add(p.getFileName().toString(), Files.readAllBytes(p));
        }
        return d;
    }

    static final List<String> BLOCKS = List.of("_source", "figures", "grid", "names", "tables");

    @SuppressWarnings("unchecked")
    void add(String name, byte[] bytes) {
        Object o = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException(name + ": an input must be a JSON object");
        }
        Map<String, Object> m = (Map<String, Object>) o;
        for (String k : m.keySet()) {
            if (!BLOCKS.contains(k)) {
                throw new IllegalArgumentException(name + ": unknown block \"" + k + "\"; an input in another format "
                        + "needs an adapter that writes the blocks " + BLOCKS);
            }
        }
        if (!(m.get("_source") instanceof Map)) {
            throw new IllegalArgumentException(name + ": no _source block");
        }
        Map<String, Object> source = (Map<String, Object>) m.get("_source");
        for (String k : new String[]{"what", "commit"}) {
            if (!(source.get(k) instanceof String) || ((String) source.get(k)).isEmpty()) {
                throw new IllegalArgumentException(name + ": _source." + k + " must be a string");
            }
        }
        if (!(source.get("fixture") instanceof Boolean)) {
            throw new IllegalArgumentException(name + ": _source.fixture must be true or false, never left out");
        }
        String file = source.containsKey("file") ? (String) source.get("file") : "main";
        if (!file.equals("main") && !file.equals("search")) {
            throw new IllegalArgumentException(name + ": _source.file must be main or search, not " + file);
        }
        Input in = new Input(name, sha256(bytes), source);
        inputs.add(in);
        if (m.containsKey("figures")) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("figures")).entrySet()) {
                merge(figure(in, e.getKey(), (Map<String, Object>) e.getValue()), name);
            }
        }
        if (m.containsKey("grid")) {
            addGrid(in, (Map<String, Object>) m.get("grid"));
        }
        if (m.containsKey("names")) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("names")).entrySet()) {
                String was = names.putIfAbsent(e.getKey(), (String) e.getValue());
                if (was != null && !was.equals(e.getValue())) {
                    throw new IllegalArgumentException(name + ": the name of " + e.getKey() + " is \"" + e.getValue()
                            + "\" here and \"" + was + "\" in an earlier input");
                }
            }
        }
        if (m.containsKey("tables")) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("tables")).entrySet()) {
                Object was = tables.get(e.getKey());
                if (was == null) {
                    tables.put(e.getKey(), e.getValue());
                } else if (e.getKey().equals("rows") && was instanceof List && e.getValue() instanceof List) {
                    // The rows table is the one table several inputs add to: the catalog's rows from
                    // one run, the searched and perfect rows from another. An id may come only once.
                    List<Object> all = new ArrayList<>((List<Object>) was);
                    for (Object add : (List<Object>) e.getValue()) {
                        Object id = ((Map<String, Object>) add).get("id");
                        for (Object p : all) {
                            if (id.equals(((Map<String, Object>) p).get("id"))) {
                                throw new IllegalArgumentException(name + ": row " + id + " is given by two inputs");
                            }
                        }
                        all.add(add);
                    }
                    tables.put("rows", all);
                } else {
                    throw new IllegalArgumentException(name + ": table " + e.getKey() + " is given by two inputs");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String firstArtifact(Input in) {
        Object a = in.source.get("artifacts");
        if (a instanceof List && !((List<Object>) a).isEmpty()) {
            return (String) ((Map<String, Object>) ((List<Object>) a).get(0)).get("path");
        }
        return "runs/site/" + in.name;
    }

    private static double optional(Map<String, Object> m, String k, String key) {
        Object v = m.get(k);
        return v == null ? Double.NaN : Json.num(v, key + "." + k);
    }

    @SuppressWarnings("unchecked")
    static Figure figure(Input in, String key, Map<String, Object> m) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(in.name + ": \"" + key + "\" is not a figure key (" + KEY + ")");
        }
        if (!m.containsKey("value")) {
            throw new IllegalArgumentException(in.name + ": " + key + " has no value");
        }
        double value = Json.num(m.get("value"), key + ".value");
        double lo = Double.NaN;
        double hi = Double.NaN;
        if (m.get("ci") != null) {
            List<Object> ci = (List<Object>) m.get("ci");
            if (ci.size() != 2) {
                throw new IllegalArgumentException(in.name + ": " + key + ".ci must be [lo, hi]");
            }
            lo = Json.num(ci.get(0), key + ".ci");
            hi = Json.num(ci.get(1), key + ".ci");
            if (lo > hi) {
                throw new IllegalArgumentException(in.name + ": " + key + ".ci runs backwards");
            }
        }
        double[] boot = null;
        if (m.get("boot") != null) {
            List<Object> b = (List<Object>) m.get("boot");
            boot = new double[b.size()];
            for (int r = 0; r < boot.length; r++) {
                boot[r] = Json.num(b.get(r), key + ".boot");
            }
        }
        String file = m.containsKey("file") ? (String) m.get("file")
                : in.source.containsKey("file") ? (String) in.source.get("file") : "main";
        boolean widened = Boolean.TRUE.equals(m.get("widened"));
        Figure f = new Figure(key, value, optional(m, "se", key), lo, hi,
                m.containsKey("fmt") ? (String) m.get("fmt") : "s4",
                m.containsKey("src") ? (String) m.get("src") : firstArtifact(in),
                m.containsKey("at") ? (String) m.get("at") : key,
                m.containsKey("commit") ? (String) m.get("commit") : in.commit(),
                file, m.containsKey("widen") ? Json.num(m.get("widen"), key + ".widen") : 1, widened, boot);
        SiteText.checkFormat(f.fmt, key);
        if (needsWidening(key, m) && (f.hasInterval() || !Double.isNaN(f.se)) && !widened) {
            throw new IllegalArgumentException(in.name + ": " + key + " is a full-play figure whose interval has not "
                    + "been widened (FULL_INDEX_PLAYS.md section 4); the page shows no unwidened full-play interval");
        }
        return f;
    }

    static boolean needsWidening(String key, Map<String, Object> m) {
        String scope = m != null && m.get("scope") instanceof String ? (String) m.get("scope") : key.split("\\.")[0];
        return WIDENED_SCOPES.contains(scope);
    }

    /** One number, one value: the same key from two inputs must print the same. */
    void merge(Figure f, String from) {
        Figure was = resolveOrNull(f.key);
        if (was == null) {
            figures.put(f.key, f);
            return;
        }
        String a = SiteText.format(was, was.fmt) + " " + (was.hasInterval() ? SiteText.format(was, "ci4") : "");
        String b = SiteText.format(f, was.fmt) + " " + (f.hasInterval() ? SiteText.format(f, "ci4") : "");
        if (!a.equals(b)) {
            throw new IllegalArgumentException(from + ": " + f.key + " prints as " + b.trim() + " here but as "
                    + a.trim() + " from " + was.src + "; one number must have one value");
        }
        Figure kept = figures.get(f.key);
        if (kept == null || (kept.boot == null && f.boot != null)) {
            // The two print as one value; the one with resamples is kept, whichever input came first,
            // so a guard can be met on a figure another input also gives (the ceiling adapter's
            // reading of c06c061's rows beside SiteExport's).
            figures.put(f.key, f);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder b = new StringBuilder();
            for (byte x : h) {
                b.append(String.format("%02x", x & 0xff));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Which scopes of a row's grid each input widened, row/scope -> input. */
    final Map<String, Boolean> gridWidened = new TreeMap<>();

    @SuppressWarnings("unchecked")
    private void addGrid(Input in, Map<String, Object> g) {
        List<Object> widened = g.containsKey("_widened") ? (List<Object>) g.get("_widened") : List.of();
        for (Map.Entry<String, Object> row : g.entrySet()) {
            if (row.getKey().equals("_widened")) {
                continue;
            }
            for (Map.Entry<String, Object> sc : ((Map<String, Object>) row.getValue()).entrySet()) {
                String scope = sc.getKey();
                if (!SCOPES.contains(scope)) {
                    throw new IllegalArgumentException(in.name + ": grid scope " + scope + " is not one of " + SCOPES);
                }
                String rs = row.getKey() + "/" + scope;
                if (gridSource.containsKey(rs)) {
                    throw new IllegalArgumentException(in.name + ": the grid cells " + rs + " are given by two inputs");
                }
                boolean w = widened.contains(scope);
                for (Map.Entry<String, Object> pen : ((Map<String, Object>) sc.getValue()).entrySet()) {
                    for (Map.Entry<String, Object> sp : ((Map<String, Object>) pen.getValue()).entrySet()) {
                        List<Object> c = (List<Object>) sp.getValue();
                        if (c.size() != 4) {
                            throw new IllegalArgumentException(in.name + ": grid cell " + rs + "/" + pen.getKey() + "/"
                                    + sp.getKey() + " must be [value, se, lo, hi]");
                        }
                        double[] v = new double[4];
                        for (int k = 0; k < 4; k++) {
                            v[k] = c.get(k) == null ? Double.NaN : Json.num(c.get(k), rs);
                        }
                        if (WIDENED_SCOPES.contains(scope) && !w && !Double.isNaN(v[2])) {
                            throw new IllegalArgumentException(in.name + ": grid " + rs + " holds full-play intervals not "
                                    + "listed in _widened; the page shows no unwidened full-play interval");
                        }
                        String key = scope + "." + pen.getKey() + "." + sp.getKey() + "." + row.getKey();
                        if (!KEY.matcher(key).matches()) {
                            throw new IllegalArgumentException(in.name + ": grid key " + key + " is not a figure key");
                        }
                        if (figures.containsKey(key)) {
                            merge(cell(key, v, in, w), in.name);
                        }
                        grid.computeIfAbsent(row.getKey(), x -> new TreeMap<>())
                                .computeIfAbsent(scope, x -> new TreeMap<>())
                                .computeIfAbsent(pen.getKey(), x -> new TreeMap<>()).put(sp.getKey(), v);
                    }
                }
                gridSource.put(rs, in);
                gridWidened.put(rs, w);
            }
        }
    }

    private static Figure cell(String key, double[] v, Input in, boolean widened) {
        String file = in.source.containsKey("file") ? (String) in.source.get("file") : "main";
        return new Figure(key, v[0], v[1], v[2], v[3], "s4", firstArtifact(in), key, in.commit(), file, 1, widened, null);
    }

    /** The figure under a key, from the figures or the grid, or null. */
    Figure resolveOrNull(String key) {
        Figure f = figures.get(key);
        if (f != null) {
            return f;
        }
        String[] p = key.split("\\.");
        if (p.length != 4 || !SCOPES.contains(p[0])) {
            return null;
        }
        Map<String, Map<String, Map<String, double[]>>> row = grid.get(p[3]);
        Map<String, Map<String, double[]>> sc = row == null ? null : row.get(p[0]);
        Map<String, double[]> pen = sc == null ? null : sc.get(p[1]);
        double[] v = pen == null ? null : pen.get(p[2]);
        if (v == null) {
            return null;
        }
        String rs = p[3] + "/" + p[0];
        return cell(key, v, gridSource.get(rs), Boolean.TRUE.equals(gridWidened.get(rs)));
    }

    Figure resolve(String key) {
        Figure f = resolveOrNull(key);
        if (f == null) {
            throw new IllegalArgumentException("no figure " + key + " in the inputs");
        }
        return f;
    }

    boolean has(String key) {
        return resolveOrNull(key) != null;
    }

    /** Whether any figure key, grid key or table name starts with prefix: a section's data is present. */
    boolean hasPrefix(String prefix) {
        for (String k : figures.keySet()) {
            if (k.startsWith(prefix)) {
                return true;
            }
        }
        for (String t : tables.keySet()) {
            if (t.startsWith(prefix)) {
                return true;
            }
        }
        for (Map.Entry<String, Map<String, Map<String, Map<String, double[]>>>> row : grid.entrySet()) {
            for (String scope : row.getValue().keySet()) {
                if ((scope + ".").startsWith(prefix) || prefix.startsWith(scope + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A value as results.json stores it in the grid: rounded to 6 decimals, two more than the page
     * ever prints (SiteText's formats stop at 4), which keeps the file within its budget at the
     * plan's largest grid; unless the rounding would change how the value prints at 0 to 4
     * decimals (a value whose dropped digits sit just under a half), in which case it is kept
     * whole. So a figure prints the same from results.json as from the run that made it. Full
     * precision stays in runs/site and in the saved results.
     */
    static Object compact(double x) {
        if (Double.isNaN(x)) {
            return null;
        }
        double r = new java.math.BigDecimal(x).setScale(6, java.math.RoundingMode.HALF_EVEN).doubleValue();
        for (int p = 0; p <= SiteText.MAX_PLACES; p++) {
            String f = "%." + p + "f";
            if (!String.format(java.util.Locale.ROOT, f, r).equals(String.format(java.util.Locale.ROOT, f, x))) {
                return x;
            }
        }
        return r;
    }

    /**
     * What results.json says about itself, for a reader or a program that opens it cold
     * (PLAN_PAGE.md 3.6). It holds no figure: every number is in the blocks below it.
     */
    static Map<String, Object> about(boolean fixture) {
        Map<String, Object> a = new LinkedHashMap<>();
        if (fixture) {
            a.put("FIXTURE", "Every number in this file is made up, written by a test fixture to exercise the page "
                    + "generator. None is a result.");
        }
        a.put("what", "Every figure on the card-counting results page, with its interval and its source.");
        a.put("unit", "Expected value in percent of the minimum bet (one unit) per round dealt, unless a figure's key "
                + "says otherwise. Keys beginning perbet are per amount bet: the per-round figure divided by the "
                + "average bet of the same schedule (keys beginning avgbet).");
        a.put("keys", "SCOPE.PEN.SPREAD.ROW. SCOPE: bet = the count used for bets and insurance only, the chart "
                + "for play; fd = also for the first decision on the two cards dealt; full = for every decision in "
                + "the round; fdw and fullw = the same with wonging. PEN = where the cut card sits, in percent of "
                + "the shoe. SPREAD = s1 for flat bets, sN for the same bet schedule from 1 to N units. ROW = the "
                + "count, named in the names block.");
        a.put("plays", "Every play is taken from index tables fitted for this game from the data, not from the "
                + "authors' published index lists.");
        a.put("schedule", "The same bet schedule for every count: the same bet sizes, handed out in the order of "
                + "each count's own estimate of the advantage.");
        a.put("side_counts", "A row whose id ends in _ace keeps a side count of aces; the tables block says where "
                + "each row's rule comes from.");
        a.put("intervals", "se is the bootstrap standard error over resampled shoes; ci is the 95% interval. "
                + "Full-play intervals are widened as FULL_INDEX_PLAYS.md section 4 says (widen gives the ratio).");
        a.put("figures", "Each figure: value, se, ci, fmt (how the page prints it), src (the artifact it was read "
                + "from), at (where in that artifact; the key itself when left out), commit (the commit that made the "
                + "artifact), file (main, the main state file, when left out; search, the second file the searched "
                + "count was chosen on). A figure that is also a grid cell is given once, in the grid.");
        a.put("grid", "The figures the page's controls switch among, one row per count: _layout names the four "
                + "numbers of a cell; _sources gives each row and scope's input (an index into inputs) and "
                + "whether its intervals were widened. A cell's key is SCOPE.PEN.SPREAD.ROW and its at is that key.");
        return a;
    }

    Map<String, Object> toJson(String version, String date) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("_about", about(fixture()));
        root.put("version", version);
        root.put("date", date);
        root.put("fixture", fixture());
        List<Object> ins = new ArrayList<>();
        for (Input in : inputs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", "runs/site/" + in.name);
            m.put("sha256", in.sha256);
            m.put("source", in.source);
            ins.add(m);
        }
        root.put("inputs", ins);
        root.put("names", new LinkedHashMap<>(names));
        Map<String, Object> figs = new LinkedHashMap<>();
        for (Figure f : figures.values()) {
            if (inGrid(f)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", f.value);
            if (!Double.isNaN(f.se)) {
                m.put("se", f.se);
            }
            if (f.hasInterval()) {
                m.put("ci", List.of(f.lo, f.hi));
            }
            m.put("fmt", f.fmt);
            m.put("src", f.src);
            if (!f.at.equals(f.key)) {
                m.put("at", f.at);
            }
            m.put("commit", f.commit);
            if (!f.file.equals("main")) {
                m.put("file", f.file);
            }
            if (f.widen != 1) {
                m.put("widen", f.widen);
            }
            if (f.widened) {
                m.put("widened", true);
            }
            figs.put(f.key, m);
        }
        root.put("figures", figs);
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("_layout", List.of("value", "se", "lo", "hi"));
        Map<String, Object> srcs = new LinkedHashMap<>();
        for (Map.Entry<String, Input> e : gridSource.entrySet()) {
            srcs.put(e.getKey(), List.of((long) inputs.indexOf(e.getValue()), gridWidened.get(e.getKey())));
        }
        g.put("_sources", srcs);
        for (Map.Entry<String, Map<String, Map<String, Map<String, double[]>>>> row : grid.entrySet()) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Map<String, double[]>>> sc : row.getValue().entrySet()) {
                Map<String, Object> s = new LinkedHashMap<>();
                for (Map.Entry<String, Map<String, double[]>> pen : sc.getValue().entrySet()) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    for (Map.Entry<String, double[]> c : pen.getValue().entrySet()) {
                        List<Object> cell = new ArrayList<>();
                        for (double x : c.getValue()) {
                            cell.add(compact(x));
                        }
                        p.put(c.getKey(), cell);
                    }
                    s.put(pen.getKey(), p);
                }
                r.put(sc.getKey(), s);
            }
            g.put(row.getKey(), r);
        }
        root.put("grid", g);
        root.put("tables", new LinkedHashMap<>(tables));
        root.put("guards", new ArrayList<Object>(guards));
        return root;
    }

    /**
     * Whether a figure is also a grid cell that prints the same in its own format, so results.json
     * need not hold it twice: the merge has already held the two to one value, and the page prints
     * it from the grid. Its resamples and widening ratio stay in runs/site, where the guards use them.
     */
    boolean inGrid(Figure f) {
        String[] p = f.key.split("\\.");
        if (p.length != 4 || !SCOPES.contains(p[0]) || !grid.containsKey(p[3])) {
            return false;
        }
        Map<String, Map<String, double[]>> sc = grid.get(p[3]).get(p[0]);
        double[] v = sc == null || sc.get(p[1]) == null ? null : sc.get(p[1]).get(p[2]);
        if (v == null || !f.fmt.equals("s4")) {
            return false;
        }
        String file = gridSource.get(p[3] + "/" + p[0]).source.getOrDefault("file", "main").toString();
        return file.equals(f.file);
    }

    /** results.json's text: each figure, grid row and table on a line of its own. */
    String write(String version, String date) {
        return Json.write(toJson(version, date), 3);
    }

    /**
     * results.json read back. SiteBuild renders the page from this copy, not from the inputs, so the
     * page shows exactly what the data file says (PLAN_PAGE.md 2.4). Resamples are not in it, so the
     * guards are evaluated on the inputs before the file is written and recorded in it.
     */
    @SuppressWarnings("unchecked")
    static SiteData fromJson(String text) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(text);
        SiteData d = new SiteData();
        for (Object o : (List<Object>) root.get("inputs")) {
            Map<String, Object> m = (Map<String, Object>) o;
            String file = (String) m.get("file");
            d.inputs.add(new Input(file.substring(file.lastIndexOf('/') + 1), (String) m.get("sha256"),
                    (Map<String, Object>) m.get("source")));
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("names")).entrySet()) {
            d.names.put(e.getKey(), (String) e.getValue());
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("figures")).entrySet()) {
            Map<String, Object> m = (Map<String, Object>) e.getValue();
            List<Object> ci = (List<Object>) m.get("ci");
            d.figures.put(e.getKey(), new Figure(e.getKey(), Json.num(m.get("value"), e.getKey()),
                    optional(m, "se", e.getKey()),
                    ci == null ? Double.NaN : Json.num(ci.get(0), e.getKey()),
                    ci == null ? Double.NaN : Json.num(ci.get(1), e.getKey()),
                    (String) m.get("fmt"), (String) m.get("src"), m.containsKey("at") ? (String) m.get("at") : e.getKey(),
                    (String) m.get("commit"), m.containsKey("file") ? (String) m.get("file") : "main",
                    m.containsKey("widen") ? Json.num(m.get("widen"), e.getKey()) : 1,
                    Boolean.TRUE.equals(m.get("widened")), null));
        }
        Map<String, Object> g = (Map<String, Object>) root.get("grid");
        for (Map.Entry<String, Object> e : ((Map<String, Object>) g.get("_sources")).entrySet()) {
            List<Object> s = (List<Object>) e.getValue();
            d.gridSource.put(e.getKey(), d.inputs.get(((Number) s.get(0)).intValue()));
            d.gridWidened.put(e.getKey(), (Boolean) s.get(1));
        }
        for (Map.Entry<String, Object> row : g.entrySet()) {
            if (row.getKey().startsWith("_")) {
                continue;
            }
            for (Map.Entry<String, Object> sc : ((Map<String, Object>) row.getValue()).entrySet()) {
                for (Map.Entry<String, Object> pen : ((Map<String, Object>) sc.getValue()).entrySet()) {
                    for (Map.Entry<String, Object> c : ((Map<String, Object>) pen.getValue()).entrySet()) {
                        List<Object> cell = (List<Object>) c.getValue();
                        double[] v = new double[4];
                        for (int k = 0; k < 4; k++) {
                            v[k] = cell.get(k) == null ? Double.NaN : Json.num(cell.get(k), row.getKey());
                        }
                        d.grid.computeIfAbsent(row.getKey(), x -> new TreeMap<>())
                                .computeIfAbsent(sc.getKey(), x -> new TreeMap<>())
                                .computeIfAbsent(pen.getKey(), x -> new TreeMap<>()).put(c.getKey(), v);
                    }
                }
            }
        }
        d.tables.putAll((Map<String, Object>) root.get("tables"));
        for (Object o : (List<Object>) root.get("guards")) {
            d.guards.add((Map<String, Object>) o);
        }
        d.version = (String) root.get("version");
        d.date = (String) root.get("date");
        return d;
    }

    /** Set on a copy read back from results.json. */
    String version;
    String date;

    /**
     * The results.csv text: one line for each figure the page prints, with its source. The grid's
     * other cells, which only the controls switch to, are in results.json; written out here they
     * would put the CSV over its budget (PLAN_COMPUTE.md section 0) to repeat what the JSON holds.
     */
    String csv(java.util.Collection<String> cited) {
        StringBuilder b = new StringBuilder("key,value,se,lo,hi,src,at,commit,file\n");
        List<String> keys = new ArrayList<>(new java.util.TreeSet<>(cited));
        Collections.sort(keys);
        for (String k : keys) {
            Figure f = resolve(k);
            b.append(k).append(',').append(csvNum(f.value)).append(',').append(csvNum(f.se)).append(',')
                    .append(csvNum(f.lo)).append(',').append(csvNum(f.hi)).append(',').append(csvText(f.src))
                    .append(',').append(f.at.equals(k) ? "" : csvText(f.at)).append(',').append(f.commit).append(',')
                    .append(f.file).append('\n');
        }
        return b.toString();
    }

    private static String csvNum(double x) {
        Object c = compact(x);
        return c == null ? "" : Json.number((Double) c);
    }

    private static String csvText(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
