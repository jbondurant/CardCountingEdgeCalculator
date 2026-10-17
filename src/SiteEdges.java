import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KeyEdgesReport's data file (runs/site/key-edges-SHA.json) as an input of the page generator:
 * the figures the page's text and its table of every row can print (PLAN_COMPUTE.md 5.5). The
 * per-key tables, bins, bands, depth and oracle stay in the file, which the page's switchable
 * views read.
 *
 * For each row at first decisions (fd), full play (full) and betting only with the count's
 * insurance (bet), at 80%: the edge in the rounds the count rates best, the top 1%, 5% and 25% of
 * rounds (edge.top1, edge.top5, edge.top25), its difference from Hi-Lo's in the top 1%
 * (edge.top1_vs_hi_lo), and the share of rounds at keys whose edge is positive (edge.positive);
 * and at every penetration Hi-Lo's fitted line, its slope a key, its intercept and the key where
 * it breaks even. Keys read edge.FIGURE.SCOPE.PEN.ROW. Edges are fractions in the file and percent
 * here; shares stay fractions, printed as percentages.
 *
 * Intervals are carried as the report made them, with the ratio it widened each by. Full play
 * holds its later plays as fitted on every resample; a full-play interval the report did not widen
 * is left out (FULL_INDEX_PLAYS.md section 4: the page shows no unwidened full-play interval), and
 * a row-layer that failed a check of 5.4 has none in the file.
 */
final class SiteEdges {

    private SiteEdges() {
    }

    /** The top shares of rounds carried, as the report's TOP list holds them, and their key parts. */
    static final double[] TOP = {0.01, 0.05, 0.25};
    static final String[] TOP_KEYS = {"top1", "top5", "top25"};

    /**
     * A row's page id: a catalog row as SiteExport names it; the two headline counts by their
     * family, as SiteCeiling names them (f1, F1 at 1-8; f2, F2 with its betting vector); every
     * other registered row (F1 flat, F2's play vector alone, F1r, the half-twin counts, the controls
     * at the search's grain) by its whole name, since its family is shared and the first such run's
     * report had six rows named f1 or f2 (17 October); null for none.
     */
    static String id(String entry) {
        // A control at the grain first: a name like "Hi-Lo = Hi-Lo Lite at grain 500/10000" would
        // otherwise be read as its catalog count, whose first system it starts with.
        if (entry.contains(" at grain ")) {
            return "reg_" + SiteInput.slug(entry);
        }
        String[] c = SiteInput.catalogRow(entry);
        if (c != null) {
            return c[0];
        }
        if (entry.startsWith("F1 at 1-8 (")) {
            return "f1";
        }
        if (entry.startsWith("F2 at 1-8, play vector (") && entry.endsWith(") + betting vector")) {
            return "f2";
        }
        if (entry.matches("^F[0-9]+.*") || entry.contains(" at grain ")) {
            return "reg_" + SiteInput.slug(entry);
        }
        return null;
    }

    static String layer(String label) {
        switch (label) {
            case "first decisions": return "fd";
            case "full play": return "full";
            case "chart + insurance": return "bet";
            default: return null;
        }
    }

    static SiteInput adapt(Path file, String shown) throws IOException {
        Map<String, Object> d = SiteAdapt.read(file);
        if (!d.containsKey("minNEff") || !d.containsKey("rows")) {
            throw new IllegalArgumentException(file + " is not KeyEdgesReport's data file");
        }
        SiteInput in = new SiteInput("key-edges", SiteAdapt.str(d.get("commit"), "commit"), "main");
        in.artifact(file, shown);
        List<Object> top = SiteAdapt.list(d.get("top"), "top");
        int[] at = new int[TOP.length];
        for (int k = 0; k < TOP.length; k++) {
            at[k] = -1;
            for (int j = 0; j < top.size(); j++) {
                if (Math.abs(SiteAdapt.num(top.get(j), "top") - TOP[k]) < 1e-12) {
                    at[k] = j;
                }
            }
            if (at[k] < 0) {
                throw new IllegalArgumentException(file + ": its top shares hold no " + TOP[k]);
            }
        }
        List<Object> failed = new ArrayList<>();
        for (Object o : SiteAdapt.list(d.get("rows"), "rows")) {
            Map<String, Object> r = SiteAdapt.obj(o, "a row");
            String name = SiteAdapt.str(r.get("row"), "row");
            String id = id(name);
            if (id == null) {
                throw new IllegalArgumentException(file + ": no page row for " + name);
            }
            for (Object po : SiteAdapt.list(r.get("penetrations"), name + ".penetrations")) {
                Map<String, Object> p = SiteAdapt.obj(po, name + " penetration");
                String pen = SiteAdapt.pen(p.get("penetration"));
                for (Object lo : SiteAdapt.list(p.get("layers"), name + " layers")) {
                    Map<String, Object> l = SiteAdapt.obj(lo, name + " layer");
                    String scope = layer(SiteAdapt.str(l.get("layer"), "layer"));
                    if (scope == null) {
                        continue;
                    }
                    String where = name + ", " + p.get("penetration") + ", " + l.get("layer");
                    if (l.get("failures") != null) {
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("row", id);
                        f.put("at", scope + "." + pen);
                        f.put("failures", l.get("failures"));
                        failed.add(f);
                    }
                    String tail = "." + scope + "." + pen + "." + id;
                    if (id.equals("hi_lo") && l.get("line") != null) {
                        Map<String, Object> line = SiteAdapt.obj(l.get("line"), "line");
                        figure(in, "edge.slope" + tail, line.get("slope"), true, "s3", scope, where + ", line.slope");
                        figure(in, "edge.intercept" + tail, line.get("intercept"), true, "s3", scope, where
                                + ", line.intercept");
                        figure(in, "edge.break_even" + tail, line.get("breakEven"), false, "s1", scope, where
                                + ", line.breakEven, a key");
                    }
                    if (!pen.equals("80")) {
                        continue;
                    }
                    List<Object> tops = SiteAdapt.list(l.get("top"), where + ".top");
                    for (int k = 0; k < TOP.length; k++) {
                        figure(in, "edge." + TOP_KEYS[k] + tail, tops.get(at[k]), true, "s2", scope, where + ", top["
                                + at[k] + "]");
                    }
                    figure(in, "edge.positive" + tail, l.get("positiveKeys"), false, "pct1", scope, where
                            + ", positiveKeys");
                    if (l.get("differenceFromHiLo") != null && !id.equals("hi_lo")) {
                        List<Object> dt = SiteAdapt.list(SiteAdapt.obj(l.get("differenceFromHiLo"), "differenceFromHiLo")
                                .get("top"), "differenceFromHiLo.top");
                        figure(in, "edge.top1_vs_hi_lo" + tail, dt.get(at[0]), true, "s2", scope, where
                                + ", differenceFromHiLo.top[" + at[0] + "]");
                    }
                }
            }
        }
        in.tables.put("key_edges_failed", failed);
        return in;
    }

    /**
     * One of the report's figures ({estimate, lo, hi, se, how, widenedBy}), in percent when it is
     * an edge; its interval left out at full play unless the report widened it.
     */
    static void figure(SiteInput in, String key, Object o, boolean edge, String fmt, String scope, String at) {
        if (o == null) {
            return;
        }
        Map<String, Object> f = SiteAdapt.obj(o, key);
        double k = edge ? 100 : 1;
        Map<String, Object> m = in.figure(key, k * SiteAdapt.num(f.get("estimate"), key), fmt);
        double widen = f.get("widenedBy") == null ? 1 : SiteAdapt.num(f.get("widenedBy"), key);
        boolean interval = f.get("lo") != null && f.get("hi") != null && (!scope.equals("full") || widen > 1);
        if (interval) {
            m.put("se", k * SiteAdapt.num(f.get("se"), key));
            m.put("ci", List.of(k * SiteAdapt.num(f.get("lo"), key), k * SiteAdapt.num(f.get("hi"), key)));
            SiteInput.widened(m, widen);
            at += "; " + f.get("how");
        } else if (f.get("lo") != null) {
            at += "; its interval holds the later plays as fitted and was not widened, so it is left out";
        }
        m.put("scope", scope);
        SiteInput.at(m, null, at);
    }
}
