import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The report of the edge at each count (PLAN_COMPUTE.md 5.5): reads what runs A and A' (and,
 * when given, the full-play runs) kept, computes every row's figures at every penetration under
 * every layer, widens the held intervals by the ratios measured on the five counts refitted
 * outright, and writes key-edges-COMMIT.json, with every row, layer and key and the SHA-256 of
 * each input, and key-edges-COMMIT.csv, with every key flagged by its bin. It writes data only;
 * the site generator renders it (PLAN_COMPUTE.md section 0, "Components write data").
 *
 * A row that failed a check of 5.4 is written with its failures and without intervals, so the
 * page shows its point table from the saved Results and says why.
 */
final class KeyEdgesReport {

    private KeyEdgesReport() {
    }

    // ------------------------------------------------------------------------ JSON

    /** Writes a tree of maps, lists, strings, numbers and booleans as JSON; NaN and infinities as null. */
    static void json(Object o, StringBuilder out) {
        if (o == null) {
            out.append("null");
        } else if (o instanceof String) {
            out.append('"');
            for (char c : ((String) o).toCharArray()) {
                if (c == '"' || c == '\\') {
                    out.append('\\').append(c);
                } else if (c == '\n') {
                    out.append("\\n");
                } else if (c < 0x20) {
                    out.append(String.format("\\u%04x", (int) c));
                } else {
                    out.append(c);
                }
            }
            out.append('"');
        } else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            out.append(Double.isFinite(d) ? Double.toString(d) : "null");
        } else if (o instanceof Number || o instanceof Boolean) {
            out.append(o);
        } else if (o instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                json(String.valueOf(e.getKey()), out);
                out.append(':');
                json(e.getValue(), out);
            }
            out.append('}');
        } else if (o instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object x : (List<?>) o) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                json(x, out);
            }
            out.append(']');
        } else if (o instanceof double[]) {
            List<Object> l = new ArrayList<>();
            for (double d : (double[]) o) {
                l.add(d);
            }
            json(l, out);
        } else {
            throw new IllegalArgumentException("no JSON for " + o.getClass());
        }
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k < kv.length; k += 2) {
            m.put((String) kv[k], kv[k + 1]);
        }
        return m;
    }

    /** A figure as the page reads it: the estimate, and its interval and how it was made, unless the row failed a check. */
    static Map<String, Object> figure(KeyEdges.Figure f, boolean intervals) {
        if (f == null) {
            return null;
        }
        Map<String, Object> m = map("estimate", f.estimate);
        Bootstrap.Interval iv = intervals ? f.interval() : null;
        if (iv != null) {
            m.put("lo", iv.lo);
            m.put("hi", iv.hi);
            m.put("se", iv.se);
            m.put("basic", iv.basic);
            m.put("how", f.how);
            if (f.widen != 1) {
                m.put("widenedBy", f.widen);
            }
        }
        return m;
    }

    static List<Object> figures(KeyEdges.Figure[] f, int from, int to, boolean intervals) {
        List<Object> out = new ArrayList<>();
        for (int k = from; k < to; k++) {
            out.add(figure(f[k], intervals));
        }
        return out;
    }

    /** One row's figures at one penetration under one layer, as the JSON holds them. */
    static Map<String, Object> edges(KeyEdges.Edges e, List<KeyEdges.Figure> fromHiLo) {
        boolean iv = e.failures.isEmpty();
        Map<String, Object> m = map("layer", e.layer.label, "penetration", SystemScorer.PENETRATIONS[e.p]);
        if (!iv) {
            m.put("failures", new ArrayList<>(e.failures));
        }
        m.put("flat", figure(e.flat, iv));
        List<Object> bins = new ArrayList<>();
        for (int b = 0; b < e.bins.size(); b++) {
            int key = e.binKey(b);
            bins.add(map("kind", e.bins.kind[b].name(), "key", key, "bookKey", e.axis.book(key),
                    "hiLoPosition", e.axis.hiLoPosition(key), "share", figure(e.share[b], iv),
                    "edge", figure(e.edge[b], iv), "insurance", figure(e.insurance[b], iv), "nEff", e.binNEff[b]));
        }
        m.put("bins", bins);
        List<Object> keys = new ArrayList<>();
        KeyEdges.Table t = e.table;
        for (int k = 0; k < t.keys(); k++) {
            int bin = 0;
            while (e.bins.to[bin] <= k) {
                bin++;
            }
            keys.add(map("key", t.sums.key[k], "bookKey", e.axis.book(t.sums.key[k]),
                    "hiLoPosition", e.axis.hiLoPosition(t.sums.key[k]), "share", t.share(k), "edge", t.edge(k),
                    "insurance", t.insurance(k), "valued", t.valued[k], "nEff", t.nEff(k), "p10", t.p10[k],
                    "p90", t.p90[k], "bin", bin, "binKind", e.bins.kind[bin].name()));
        }
        m.put("keys", keys);
        int nt = KeyEdges.TOP.length;
        int nb = KeyEdges.BANDS.length;
        m.put("top", figures(e.byShare, 0, nt, iv));
        m.put("bands", figures(e.byShare, nt, nt + nb, iv));
        m.put("positiveKeys", figure(e.byShare[nt + nb], iv));
        if (e.line != null) {
            m.put("line", map("from", KeyEdges.LINE_FROM, "to", KeyEdges.LINE_TO, "slope", figure(e.line[0], iv),
                    "intercept", figure(e.line[1], iv), "breakEven", figure(e.line[2], iv)));
        }
        if (e.pivot != null) {
            m.put("pivot", map("key", e.axis.pivot, "bookKey", e.axis.book(e.axis.pivot), "edge", figure(e.pivot, iv)));
        }
        if (fromHiLo != null) {
            List<Object> d = new ArrayList<>();
            for (KeyEdges.Figure f : fromHiLo) {
                d.add(figure(f, iv));
            }
            m.put("differenceFromHiLo", map("top", d.subList(0, nt), "bands", d.subList(nt, nt + nb),
                    "positiveKeys", d.get(nt + nb)));
        }
        return m;
    }

    /**
     * Each figure by share of rounds of row less Hi-Lo's, resample by resample (they pair: the
     * same draws). Refitted where both rows were; otherwise held and widened by the largest ratio
     * the five refitted counts' own differences from Hi-Lo give (Widening.difference).
     */
    static List<KeyEdges.Figure> fromHiLo(KeyEdges.Edges row, KeyEdges.Edges hiLo, KeyEdges.Widening w) {
        List<KeyEdges.Figure> out = new ArrayList<>();
        for (int k = 0; k < row.byShare.length; k++) {
            KeyEdges.Figure d = KeyEdges.difference(row.byShare[k], hiLo.byShare[k]);
            if (d.refit == null && d.held != null && w != null) {
                d.widen = Math.max(1, w.difference[k]);
                d.how = "difference held, widened by the largest ratio measured on the differences of "
                        + String.join(", ", w.measuredOn) + String.format(java.util.Locale.ROOT, ", x%.3f", d.widen);
            }
            if (row.layer == KeyEdges.Layer.FULL) {
                // Both rows hold their later plays on every resample, whichever way the rest was made.
                d.how = d.refit != null ? KeyEdges.FULL_REFITTED
                        : d.how + "; later plays held fixed, their fitting uncertainty not included";
            }
            out.add(d);
        }
        return out;
    }

    /** The oracle's figures at one penetration under one layer: TOP's edges, BANDS', the share of states of positive value. */
    static Map<String, Object> oracle(KeyEdges.Values v, KeyEdges.Layer layer, KeyEdges.Facts facts, Bootstrap boot) {
        double[][] o = KeyEdges.oracle(v, facts, boot);
        int n = o[0].length;
        KeyEdges.Figure[] f = new KeyEdges.Figure[n];
        for (int k = 0; k < n; k++) {
            double[] held = new double[boot.resamples];
            for (int r = 0; r < held.length; r++) {
                held[r] = o[1 + r][k];
            }
            f[k] = new KeyEdges.Figure(o[0][k], held, null);
            f[k].how = "held, not widened: fitted on nothing";
        }
        int nt = KeyEdges.TOP.length;
        int nb = KeyEdges.BANDS.length;
        return map("layer", layer.label, "penetration", SystemScorer.PENETRATIONS[v.p], "top", figures(f, 0, nt, true),
                "bands", figures(f, nt, nt + nb, true), "positiveStates", figure(f[nt + nb], true));
    }

    // ------------------------------------------------------------------- assembly

    /** The decks-left bands of the depth figure, by cards dealt: 8 to 6 decks left, 6 to 4, 4 to 2, 2 to 1.6. */
    static final int[][] DEPTHS = {{0, 104}, {104, 208}, {208, 312}, {312, 416}};
    static final String[] DEPTH_LABELS = {"8 to 6 decks left", "6 to 4", "4 to 2", "2 to 1.6"};

    /** Everything the report reads: run A's states, run A''s refitted rows, and full-play runs, with c06c061's perfect array. */
    static final class Inputs {
        KeyEdges.Collector a;
        /** Collectors of further rows at first decisions: the registered counts, run C in the page look. */
        List<KeyEdges.Collector> more = new ArrayList<>();
        KeyEdges.Collector aPrime;
        List<KeyEdges.Collector> full = new ArrayList<>();
        /** c06c061's perfect full-play values by state; NaN where its fit did not value a state. */
        double[] perfect;
        Bootstrap boot;
        /** report-final's per-key tables as printed, for check 9 of 5.4 (reported, not pass/fail); null to skip it. */
        Map<String, List<KeyEdgesPrinted.Line>> printed;
    }

    /** Check 6 of 5.4: whether the perfect array has a value at every state of penetration p. */
    static boolean perfectWhole(double[] perfect, KeyEdges.Facts facts, int p) {
        if (perfect == null || perfect.length != facts.depth.length) {
            return false;
        }
        for (int s = 0; s < perfect.length; s++) {
            if (facts.depth[s] < SystemScorer.CUTS[p] && Double.isNaN(perfect[s])) {
                return false;
            }
        }
        return true;
    }

    /** The collector of the given ones holding entry name at penetration p, or null. */
    static KeyEdges.Collector holding(List<KeyEdges.Collector> cs, String name, int p) {
        for (KeyEdges.Collector c : cs) {
            if (c != null && c.has(name, p)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Row count's figures under each layer at penetration p that the inputs hold. The five counts
     * of run A' at its penetration take its refitted resamples at first decisions; every other
     * figure is held.
     */
    static List<KeyEdges.Edges> rowAt(Inputs in, SystemScorer.Entry first, int p) {
        List<KeyEdges.Edges> out = new ArrayList<>();
        String name = first.name;
        KeyEdges.Axis axis = new KeyEdges.Axis(first.count);
        KeyEdges.Facts facts = in.a.facts;
        List<KeyEdges.Collector> sources = new ArrayList<>();
        sources.add(in.a);
        sources.addAll(in.more);
        KeyEdges.Collector src = holding(sources, name, p);
        if (src == null) {
            return out;
        }
        KeyEdges.Values v = src.values(name, p);
        KeyEdges.Refitted rf = in.aPrime != null && in.aPrime.has(name, p) ? in.aPrime.refitted(name, p) : null;
        out.add(KeyEdges.edges(name, KeyEdges.Layer.FIRST, axis, v, facts, in.boot, rf,
                first.estimate[p][SystemScorer.FLAT]));
        SystemScorer.Entry betting = src.entries.get(name + ", betting only");
        if (betting != null && src.has(betting.name, p)) {
            out.add(KeyEdges.edges(name, KeyEdges.Layer.CHART, axis, v.withValues(facts.chart, null), facts, in.boot,
                    null, betting.estimate[p][SystemScorer.PLAY]));
            out.add(KeyEdges.edges(name, KeyEdges.Layer.CHART_INSURANCE, axis, src.values(betting.name, p), facts,
                    in.boot, null, betting.estimate[p][SystemScorer.FLAT]));
        }
        KeyEdges.Collector fc = holding(in.full, name + ", full play", p);
        if (fc != null) {
            SystemScorer.Entry fe = fc.entries.get(name + ", full play");
            KeyEdges.Edges full = KeyEdges.edges(name, KeyEdges.Layer.FULL, axis, fc.values(fe.name, p), facts, in.boot,
                    fc.refitted(fe.name, p), fe.estimate[p][SystemScorer.FLAT]);
            // Check 1 of 5.4: a full-play run scores the first decisions beside full play, and its
            // first-decision values must be run A's, state for state.
            if (fc.has(name, p)) {
                KeyEdges.Values b = fc.values(name, p);
                if (!Arrays.equals(b.state, v.state) || !Arrays.equals(b.value, v.value) || !Arrays.equals(b.key, v.key)) {
                    full.failures.add("the full-play run's first-decision values are not run A's");
                }
            }
            out.add(full);
        }
        out.add(KeyEdges.edges(name, KeyEdges.Layer.PERFECT_FIRST, axis, v.withValues(facts.perfect,
                facts.perfectInsurance), facts, in.boot, null, Double.NaN));
        if (perfectWhole(in.perfect, facts, p)) {
            out.add(KeyEdges.edges(name, KeyEdges.Layer.PERFECT_FULL, axis, v.withValues(in.perfect,
                    facts.perfectInsurance), facts, in.boot, null, Double.NaN));
        }
        return out;
    }

    /** The rows of run A: each entry playing its own fit at first decisions, in the order scored. */
    static List<SystemScorer.Entry> rows(KeyEdges.Collector a) {
        List<SystemScorer.Entry> out = new ArrayList<>();
        for (SystemScorer.Entry e : a.entries.values()) {
            if (!e.bettingOnly && !e.fullPlay && !e.ownValue) {
                out.add(e);
            }
        }
        return out;
    }

    /** Run A's rows, then the further collectors' rows not among them, each collector's in the order scored. */
    static List<SystemScorer.Entry> rows(Inputs in) {
        List<SystemScorer.Entry> out = rows(in.a);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SystemScorer.Entry e : out) {
            seen.add(e.name);
        }
        for (KeyEdges.Collector c : in.more) {
            if (!Arrays.equals(c.facts.shoe, in.a.facts.shoe) || !Arrays.equals(c.facts.chart, in.a.facts.chart)) {
                throw new IllegalStateException("a collector of further rows is of other states than run A's");
            }
            for (SystemScorer.Entry e : rows(c)) {
                if (seen.add(e.name)) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** The row of Hi-Lo among run A's. */
    static SystemScorer.Entry hiLo(List<SystemScorer.Entry> rows) {
        for (SystemScorer.Entry e : rows) {
            if (e.count.systems.contains("Hi-Lo") && e.count.base == null && e.count.scale != CountKeys.K) {
                return e;
            }
        }
        throw new IllegalStateException("no Hi-Lo row");
    }

    /**
     * The band ratios, measured on the rows run A' refitted, at its penetration, first decisions:
     * each with its held resamples (run A's values, the bootstrap's draws) and its refitted ones.
     */
    static KeyEdges.Widening widening(Inputs in, List<SystemScorer.Entry> rows) {
        if (in.aPrime == null) {
            return null;
        }
        int p = KeyEdgesRun.REFIT_PEN;
        List<KeyEdges.Edges> measured = new ArrayList<>();
        KeyEdges.Edges hl = null;
        String hiLoName = hiLo(rows).name;
        for (SystemScorer.Entry e : rows) {
            if (!in.aPrime.has(e.name, p)) {
                continue;
            }
            KeyEdges.Values va = in.a.values(e.name, p);
            KeyEdges.Values vp = in.aPrime.values(e.name, p);
            if (!Arrays.equals(va.value, vp.value) || !Arrays.equals(va.key, vp.key) || !Arrays.equals(va.state, vp.state)) {
                throw new IllegalStateException(e.name + ": run A' valued its states otherwise than run A");
            }
            KeyEdges.Edges ed = KeyEdges.edges(e.name, KeyEdges.Layer.FIRST, new KeyEdges.Axis(e.count), va,
                    in.a.facts, in.boot, in.aPrime.refitted(e.name, p), e.estimate[p][SystemScorer.FLAT]);
            measured.add(ed);
            hl = e.name.equals(hiLoName) ? ed : hl;
        }
        if (hl == null) {
            throw new IllegalStateException("run A' holds no Hi-Lo row, which the differences are measured from");
        }
        return KeyEdges.Widening.measure(measured, hl);
    }

    // ------------------------------------------------------------------------ write

    /**
     * The report from the runs' files: run A's collector (--a), further rows (--rows), run A''s
     * (--a-prime), full-play runs (--full), c06c061's results.ser for its perfect array, and
     * report-final for check 9 (--printed); held resamples of the number given, drawn as every
     * published interval was (Bootstrap.RESAMPLES and Bootstrap.SEED on the real run).
     */
    static void write(Path a, List<Path> more, Path aPrime, List<Path> full, Path published, Path printed, Path out,
                      String commit, int resamples, PrintStream log) throws Exception {
        long t0 = System.nanoTime();
        if (a == null) {
            throw new IllegalArgumentException("the report needs run A's file (--a)");
        }
        Inputs in = new Inputs();
        in.a = KeyEdges.Collector.read(a);
        in.aPrime = aPrime == null ? null : KeyEdges.Collector.read(aPrime);
        for (Path f : full) {
            in.full.add(KeyEdges.Collector.read(f));
        }
        for (Path f : more) {
            in.more.add(KeyEdges.Collector.read(f));
        }
        in.perfect = CeilingReport.read(published).perfect;
        in.printed = printed == null ? null : KeyEdgesPrinted.parse(printed);
        in.boot = new Bootstrap(in.a.facts.shoes, resamples, Bootstrap.SEED);
        // A refitted interval and the held one it is set beside must come from the same draws.
        if (in.aPrime != null && in.aPrime.resamples != resamples) {
            throw new IllegalStateException("run A' was made with " + in.aPrime.resamples + " resamples, the report draws "
                    + resamples);
        }
        List<Map<String, Object>> inputs = new ArrayList<>();
        inputs.add(map("file", a.toAbsolutePath().normalize().toString(), "sha256", KeyEdgesRun.sha256(a)));
        if (aPrime != null) {
            inputs.add(map("file", aPrime.toAbsolutePath().normalize().toString(), "sha256", KeyEdgesRun.sha256(aPrime)));
        }
        for (Path f : more) {
            inputs.add(map("file", f.toAbsolutePath().normalize().toString(), "sha256", KeyEdgesRun.sha256(f)));
        }
        for (Path f : full) {
            inputs.add(map("file", f.toAbsolutePath().normalize().toString(), "sha256", KeyEdgesRun.sha256(f)));
        }
        inputs.add(map("file", published.toAbsolutePath().normalize().toString(), "sha256",
                KeyEdgesRun.sha256(published)));
        if (printed != null) {
            inputs.add(map("file", printed.toAbsolutePath().normalize().toString(), "sha256",
                    KeyEdgesRun.sha256(printed)));
        }
        Path json = out.resolve("key-edges-" + commit + ".json");
        Path csv = out.resolve("key-edges-" + commit + ".csv");
        report(in, inputs, commit, json, csv, log);
        log.printf("wrote %s (%d bytes) and %s (%d bytes) in %.1f s%n", json, Files.size(json), csv, Files.size(csv),
                (System.nanoTime() - t0) / 1e9);
    }

    /** Computes every figure from the inputs and writes the JSON and the CSV, each whole (temporary file, then renamed). */
    static void report(Inputs in, List<Map<String, Object>> inputs, String commit, Path json, Path csv, PrintStream log)
            throws IOException {
        List<SystemScorer.Entry> rows = rows(in);
        KeyEdges.Widening w = widening(in, rows);
        SystemScorer.Entry hl = hiLo(rows);
        List<List<KeyEdges.Edges>> hiLoAt = new ArrayList<>();
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            List<KeyEdges.Edges> e = rowAt(in, hl, p);
            for (KeyEdges.Edges x : e) {
                if (w != null) {
                    w.apply(x);
                }
            }
            hiLoAt.add(e);
        }
        Path jtmp = json.resolveSibling(json.getFileName() + ".tmp");
        Path ctmp = csv.resolveSibling(csv.getFileName() + ".tmp");
        int failed = 0;
        Map<String, List<KeyEdges.Figure>> simultaneous = new LinkedHashMap<>();
        try (Writer jw = Files.newBufferedWriter(jtmp, StandardCharsets.UTF_8);
             Writer cw = Files.newBufferedWriter(ctmp, StandardCharsets.UTF_8)) {
            StringBuilder head = new StringBuilder();
            Map<String, Object> about = map("about", "The edge at each count (PLAN_COMPUTE.md section 5): per row, "
                            + "penetration and layer, each key's share of rounds and mean round value with insurance, "
                            + "in % of a unit when multiplied by 100; written by KeyEdgesReport, rendered by the site "
                            + "generator.", "commit", commit, "inputs", inputs, "resamples", in.boot.resamples,
                    "seed", Bootstrap.SEED, "minNEff", KeyEdges.MIN_N_EFF, "top", KeyEdges.TOP,
                    "bands", bandList(), "widening", widening(w));
            json(about, head);
            // The rows go in one at a time, so no more than one row's resamples are held at once.
            jw.write(head.substring(0, head.length() - 1));
            jw.write(",\"rows\":[");
            cw.write(KeyEdgesCsv.HEADER);
            boolean firstRow = true;
            for (SystemScorer.Entry e : rows) {
                List<Object> pens = new ArrayList<>();
                for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                    List<KeyEdges.Edges> at = e == hl ? hiLoAt.get(p) : rowAt(in, e, p);
                    List<Object> layers = new ArrayList<>();
                    for (KeyEdges.Edges x : at) {
                        if (w != null && e != hl) {
                            w.apply(x);
                        }
                        KeyEdges.Edges ref = same(hiLoAt.get(p), x.layer);
                        boolean differs = ref != null && (x.layer == KeyEdges.Layer.FIRST || x.layer == KeyEdges.Layer.FULL);
                        List<KeyEdges.Figure> d = differs ? fromHiLo(x, ref, w) : null;
                        layers.add(edges(x, d));
                        if (d != null && e != hl && x.failures.isEmpty()) {
                            for (int k = 0; k < d.size(); k++) {
                                simultaneous.computeIfAbsent(p + "|" + x.layer.label + "|" + k, z -> new ArrayList<>())
                                        .add(d.get(k));
                            }
                        }
                        failed += x.failures.isEmpty() ? 0 : 1;
                        KeyEdgesCsv.write(x, cw);
                    }
                    pens.add(map("penetration", SystemScorer.PENETRATIONS[p], "layers", layers));
                }
                StringBuilder r = new StringBuilder();
                json(map("row", e.name, "systems", new ArrayList<>(e.count.systems), "trueCounted", e.count.trueCounted,
                        "scale", e.count.scale, "divisor", e.count.divisor(), "aceSideCount", e.count.aceSideCount,
                        "hiLoStep", new KeyEdges.Axis(e.count).hiLo, "rebinned", new KeyEdges.Axis(e.count).rebinned,
                        "penetrations", pens), r);
                jw.write(firstRow ? "" : ",");
                jw.write(r.toString());
                firstRow = false;
            }
            jw.write("],");
            StringBuilder tail = new StringBuilder();
            Map<String, Object> last = map("simultaneous", simultaneous(simultaneous), "oracle", oracles(in),
                    "depth", depth(in, hl, w));
            if (in.printed != null) {
                List<KeyEdges.Collector> ours = new ArrayList<>();
                ours.add(in.a);
                ours.addAll(in.more);
                KeyEdgesPrinted.Comparison c = KeyEdgesPrinted.compare(in.printed, ours);
                log.println(c.summary());
                last.put("printedTables", c.json());
            }
            json(last, tail);
            jw.write(tail.substring(1));
        }
        java.nio.file.Files.move(jtmp, json, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        java.nio.file.Files.move(ctmp, csv, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        log.println(rows.size() + " rows; " + failed + " row-layers failed a check and are written without intervals");
    }

    private static KeyEdges.Edges same(List<KeyEdges.Edges> at, KeyEdges.Layer layer) {
        for (KeyEdges.Edges e : at) {
            if (e.layer == layer) {
                return e;
            }
        }
        return null;
    }

    private static List<Object> bandList() {
        List<Object> out = new ArrayList<>();
        for (double[] b : KeyEdges.BANDS) {
            out.add(b);
        }
        return out;
    }

    private static Map<String, Object> widening(KeyEdges.Widening w) {
        if (w == null) {
            return null;
        }
        return map("measuredOn", new ArrayList<>(w.measuredOn), "positionBands", Arrays.asList(KeyEdges.POSITION_BANDS),
                "band", w.band, "byShare", w.byShare, "difference", w.difference);
    }

    /** The oracle at each penetration, under the chart and the two perfect layers, on run A's states. */
    static List<Object> oracles(Inputs in) {
        List<Object> out = new ArrayList<>();
        SystemScorer.Entry any = rows(in.a).get(0);
        KeyEdges.Facts f = in.a.facts;
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            KeyEdges.Values v = in.a.values(any.name, p);
            out.add(oracle(v.withValues(f.chart, null), KeyEdges.Layer.CHART, f, in.boot));
            out.add(oracle(v.withValues(f.perfect, f.perfectInsurance), KeyEdges.Layer.PERFECT_FIRST, f, in.boot));
            if (perfectWhole(in.perfect, f, p)) {
                out.add(oracle(v.withValues(in.perfect, f.perfectInsurance), KeyEdges.Layer.PERFECT_FULL, f, in.boot));
            }
        }
        return out;
    }

    /** Hi-Lo's first-decision edge per key in each decks-left band, at each penetration: the floating advantage. */
    static List<Object> depth(Inputs in, SystemScorer.Entry hl, KeyEdges.Widening w) {
        List<Object> out = new ArrayList<>();
        KeyEdges.Facts f = in.a.facts;
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            KeyEdges.Values v = in.a.values(hl.name, p);
            for (int d = 0; d < DEPTHS.length; d++) {
                int lo = DEPTHS[d][0];
                int hi = Math.min(DEPTHS[d][1], SystemScorer.CUTS[p]);
                if (lo >= hi) {
                    continue;
                }
                KeyEdges.Values in2 = v.only(s -> f.depth[s] >= lo && f.depth[s] < hi);
                if (in2.state.length == 0) {
                    // No state was dealt this deep (a small fixture; on the real file every band
                    // below the cut holds states), so the band has no table and is left out.
                    continue;
                }
                KeyEdges.Edges e = KeyEdges.edges(hl.name, KeyEdges.Layer.FIRST, new KeyEdges.Axis(hl.count), in2, f,
                        in.boot, null, Double.NaN);
                if (w != null) {
                    w.apply(e);
                }
                Map<String, Object> m = edges(e, null);
                m.put("decksLeft", DEPTH_LABELS[d]);
                m.put("cardsDealt", new double[]{lo, hi});
                out.add(m);
            }
        }
        return out;
    }

    /**
     * The maximum-|t| multipliers of the differences from Hi-Lo (PLAN_COMPUTE.md 5.3, the T2
     * table): for each penetration, layer and figure by share of rounds, over every row that
     * passed its checks, the multiplier c that makes estimate +- c x SE simultaneous 95% intervals
     * (Bootstrap.maxT), on the resamples each interval is made from (refitted, or held and
     * widened). A per-key interval is not simultaneous across keys, and the page says so.
     */
    static List<Object> simultaneous(Map<String, List<KeyEdges.Figure>> byFigure) {
        String[] names = new String[KeyEdges.TOP.length + KeyEdges.BANDS.length + 1];
        for (int k = 0; k < KeyEdges.TOP.length; k++) {
            names[k] = "top " + KeyEdges.TOP[k];
        }
        for (int b = 0; b < KeyEdges.BANDS.length; b++) {
            names[KeyEdges.TOP.length + b] = "band " + KeyEdges.BANDS[b][0] + "-" + KeyEdges.BANDS[b][1];
        }
        names[names.length - 1] = "positive keys";
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, List<KeyEdges.Figure>> e : byFigure.entrySet()) {
            String[] at = e.getKey().split("\\|");
            List<KeyEdges.Figure> fs = e.getValue();
            double[] est = new double[fs.size()];
            double[][] boots = new double[fs.size()][];
            boolean all = true;
            for (int k = 0; k < est.length; k++) {
                KeyEdges.Figure f = fs.get(k);
                est[k] = f.estimate;
                boots[k] = f.refit != null ? f.refit : f.held == null ? null
                        : f.widen == 1 ? f.held : Bootstrap.widen(f.held, f.widen);
                all &= boots[k] != null;
            }
            if (!all) {
                continue;
            }
            out.add(map("penetration", SystemScorer.PENETRATIONS[Integer.parseInt(at[0])], "layer", at[1],
                    "figure", names[Integer.parseInt(at[2])], "rows", est.length, "multiplier", Bootstrap.maxT(est, boots)));
        }
        return out;
    }
}
