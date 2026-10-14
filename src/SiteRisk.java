import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RiskReport's data file (runs/site/risk-SHA.json) as an input of the page generator.
 *
 * Each computed row's figures are written under risk.FIGURE.SCOPE.PEN.SPREAD.ROW, FIGURE the
 * layout's key as a key's part (ev.per100.usd is ev_per100_usd, bankroll.1% is bankroll_1), the
 * row named as SiteExport names it (RiskReport.spec gives each figure id's entry), so the page can
 * name a row's money by the row it ranks first: risk.ev_per100_usd.full.80.s8.=full.80.s8.@best.
 * Only the figures the page's text can print are carried (SHOWN); the whole layout, the ruin
 * checks and the fan stay in the risk file, which the page's switchable views read. Units are the
 * report's: units of the minimum bet or dollars at its unit_usd, rounds for n0. An interval with
 * no upper end (a resample whose win rate is at most 0) is left out, and said so in the figure's
 * at. A full-play row's figures carry the ratio its win rate's resamples were widened by.
 * A row RiskReport did not compute, or held back, is listed with its status and gets no figure; a
 * figure the file gives as null (N0 of a row that loses) is left out.
 */
final class SiteRisk {

    private SiteRisk() {
    }

    /** The layout's figures carried to the page, with the format each prints in. */
    static final Map<String, String> SHOWN = new LinkedHashMap<>();

    static {
        SHOWN.put("ev.per100", "s2");
        SHOWN.put("sd.per100", "d1");
        SHOWN.put("ev.per100.usd", "usd");
        SHOWN.put("sd.per100.usd", "usd");
        SHOWN.put("n0", "d0");
        SHOWN.put("bankroll.1%", "d0");
        SHOWN.put("bankroll.5%", "d0");
        SHOWN.put("bankroll.25%", "d0");
        SHOWN.put("bankroll.1%.usd", "usd");
        SHOWN.put("bankroll.5%.usd", "usd");
        SHOWN.put("bankroll.25%.usd", "usd");
    }

    /** A row's page id: a catalog row as SiteExport names it, the chart, or a searched count by its family. */
    static String id(RiskReport.Spec s) {
        if (s.entry.equals(RiskReport.CHART)) {
            return "chart";
        }
        if (s.count.equals("F1") || s.count.equals("F2")) {
            return s.count.toLowerCase(java.util.Locale.ROOT);
        }
        String[] r = SiteInput.catalogRow(s.entry);
        if (r == null) {
            throw new IllegalArgumentException(s.figure + ": no page row for " + s.entry);
        }
        return r[0];
    }

    static SiteInput adapt(Path file, String shown) throws IOException {
        Map<String, Object> d = SiteAdapt.read(file);
        if (!"risk".equals(d.get("what"))) {
            throw new IllegalArgumentException(file + " is not RiskReport's data file");
        }
        SiteInput in = new SiteInput("risk", SiteAdapt.str(d.get("commit"), "commit"), "main");
        in.artifact(file, shown);
        List<Object> layout = SiteAdapt.list(d.get("layout"), "layout");
        long rounds = -1;
        for (Object k : layout) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("ev\\.per(\\d+)").matcher(String.valueOf(k));
            if (m.matches()) {
                rounds = Long.parseLong(m.group(1));
            }
        }
        if (rounds < 0 || !layout.contains("ev.per" + rounds)) {
            throw new IllegalArgumentException(file + ": the layout has no ev.perN figure");
        }
        Map<String, Object> u = in.figure("risk.unit_usd", SiteAdapt.num(d.get("unit_usd"), "unit_usd"), "usd");
        SiteInput.at(u, null, "unit_usd");
        Map<String, Object> per = in.figure("risk.rounds", rounds, "n");
        SiteInput.at(per, null, "layout, the N of ev.perN");
        List<Object> status = new ArrayList<>();
        for (Object o : SiteAdapt.list(d.get("rows"), "rows")) {
            Map<String, Object> r = SiteAdapt.obj(o, "a row");
            String fig = SiteAdapt.str(r.get("figure"), "figure");
            RiskReport.Spec spec = RiskReport.spec(fig);
            if (!spec.count.equals(r.get("count")) || !spec.play.equals(r.get("play")) || !spec.bets.equals(r.get("bets"))
                    || !RiskReport.PEN_NAMES[spec.pen].equals(r.get("pen"))) {
                throw new IllegalArgumentException(file + ": figure " + fig + " is not the row RiskReport.ROWS registers");
            }
            String id = id(spec);
            String at = SiteAdapt.scope(spec.play) + "." + SiteAdapt.pen(r.get("pen")) + "." + SiteAdapt.spread(spec.bets)
                    + "." + id;
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("figure", fig);
            st.put("key", at);
            st.put("status", r.get("status"));
            status.add(st);
            if (!"computed".equals(r.get("status")) || r.get("f") == null) {
                continue;
            }
            List<Object> f = SiteAdapt.list(r.get("f"), fig + ".f");
            if (f.size() != layout.size()) {
                throw new IllegalArgumentException(file + ": " + fig + " has " + f.size() + " figures, the layout "
                        + layout.size());
            }
            double widen = SiteAdapt.num(r.get("widen"), fig + ".widen");
            for (Map.Entry<String, String> e : SHOWN.entrySet()) {
                int k = layout.indexOf(e.getKey());
                if (k < 0) {
                    throw new IllegalArgumentException(file + ": the layout has no " + e.getKey());
                }
                figure(in, "risk." + SiteAdapt.part(e.getKey()) + "." + at, f.get(k), e.getValue(), widen,
                        "rows[" + fig + "].f[" + k + "], " + e.getKey());
            }
        }
        in.tables.put("risk_rows", status);
        panels(in, d);
        Object same = SiteAdapt.obj(d.get("same_swings"), "same_swings").get("holds");
        if (same instanceof Boolean) {
            Map<String, Object> m = in.figure("risk.same_swings", (Boolean) same ? 1 : -1, "w:holds:does not hold");
            SiteInput.at(m, null, "same_swings.holds");
        }
        return in;
    }

    /** One figure: a value alone, or [value, lower, upper] with null for no upper end. */
    static void figure(SiteInput in, String key, Object v, String fmt, double widen, String at) {
        if (v == null || (v instanceof List && ((List<?>) v).get(0) == null)) {
            // A figure the row does not have (N0 of a row that loses): left out, as the file leaves it.
            return;
        }
        if (v instanceof List) {
            List<?> t = (List<?>) v;
            Map<String, Object> m = in.figure(key, SiteAdapt.num(t.get(0), key), fmt);
            if (t.get(1) != null && t.get(2) != null) {
                m.put("ci", List.of(SiteAdapt.num(t.get(1), key), SiteAdapt.num(t.get(2), key)));
            } else {
                at += "; its 95% interval has no upper end";
            }
            if (widen > 1) {
                SiteInput.widened(m, widen);
            }
            SiteInput.at(m, null, at);
        } else {
            Map<String, Object> m = in.figure(key, SiteAdapt.num(v, key), fmt);
            SiteInput.at(m, null, at);
        }
    }

    /**
     * The fan and ruin panels (INTEGRATION.md open item 12): risk_fan, each fan path's cumulative
     * win every step rounds, as RiskReport keeps them (FAN_STEP); risk_ruin, each ruin check's
     * bankrolls with the formula's risk, the report's simulated risk and its SE, and the
     * simulator's own from ruin-FIG.tsv where it was given, in the same order of bankrolls.
     */
    static void panels(SiteInput in, Map<String, Object> d) {
        Object fan = d.get("fan");
        if (fan != null) {
            Map<String, Object> f = SiteAdapt.obj(fan, "fan");
            double step = SiteAdapt.num(f.get("step"), "fan.step");
            List<Object> paths = new ArrayList<>();
            List<Object> raw = SiteAdapt.list(f.get("paths"), "fan.paths");
            for (int j = 0; j < raw.size(); j++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", j);
                m.put("figure", f.get("figure"));
                m.put("step", step);
                m.put("points", SiteAdapt.list(raw.get(j), "fan.paths[" + j + "]"));
                paths.add(m);
            }
            in.tables.put("risk_fan", paths);
        }
        List<Object> ruin = new ArrayList<>();
        for (Object o : d.get("ruin") == null ? List.of() : SiteAdapt.list(d.get("ruin"), "ruin")) {
            Map<String, Object> c = SiteAdapt.obj(o, "a ruin check");
            if (c.get("points") == null) {
                continue;
            }
            List<Object> pts = SiteAdapt.list(c.get("points"), "ruin points");
            List<Object> sim = null;
            if (c.get("sim") != null) {
                sim = SiteAdapt.list(SiteAdapt.obj(c.get("sim"), "ruin sim").get("points"), "ruin sim points");
            }
            for (int k = 0; k < pts.size(); k++) {
                List<Object> p = SiteAdapt.list(pts.get(k), "a ruin point");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("figure", c.get("figure"));
                m.put("bankroll", SiteAdapt.num(p.get(0), "bankroll"));
                m.put("formula", SiteAdapt.num(p.get(1), "formula"));
                m.put("simulated", SiteAdapt.num(p.get(2), "simulated"));
                m.put("se", SiteAdapt.num(p.get(3), "se"));
                if (sim != null && k < sim.size()) {
                    List<Object> q = SiteAdapt.list(sim.get(k), "a simulator ruin point");
                    m.put("sim_bankroll", SiteAdapt.num(q.get(0), "bankroll"));
                    m.put("sim_simulated", SiteAdapt.num(q.get(2), "simulated"));
                    m.put("sim_se", SiteAdapt.num(q.get(3), "se"));
                }
                ruin.add(m);
            }
        }
        if (!ruin.isEmpty()) {
            in.tables.put("risk_ruin", ruin);
        }
    }
}
