import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WideReport's data file (runs/site/spreads-SHA.json) as an input of the page generator: what the
 * page's spread and wonging controls switch to (PLAN_PAGE.md 5c, PLAN_COMPUTE.md 3.6).
 *
 * - Wider spreads: P12, P16 and P20 are the same schedule as 1-8 with a higher cap (P8 is 1-8 to
 *   the bit, WideReport's check 1), so their EVs are written as the spreads s12, s16 and s20 of the
 *   page's keys, SCOPE.PEN.sN.ROW, for every row and scope (s20 at 80% only, the one place the
 *   page draws it); S16, a unit per 0.125%, as the spread s16_steep.
 * - Wonging: the grid's scopes fdw and fullw, sitting out every round whose fitted advantage is
 *   under 0 (the B plans entering at 0): s1 from B0,1, s8 from B0,8 and s16 from B0,16, each the
 *   same schedule, so every count sits out the same share of rounds.
 * - Each same-schedule plan's average bet and share of rounds played at each penetration, the same
 *   for every row (WideReport's check 4; refused here too unless every row agrees to 1e-12): keys
 *   wide.bet.PEN.PLAN and wide.share.PEN.PLAN; the plans themselves in the table wide_plans; and
 *   Hi-Lo's key at each threshold, wide.threshold.PEN.TAU.hi_lo, for "enter at a true count of".
 * EVs are fractions in the file and percent here; a full-play interval carries WideReport's own
 * widening for its plan, and without a pilot the file has none, so neither has the page. The
 * own-rule figures, the other thresholds' and the key tables stay in the file for the views that
 * read it.
 */
final class SiteWide {

    private SiteWide() {
    }

    /** The same-schedule plans written as spreads of the page's keys: label to spread, and the penetrations kept. */
    static final String[][] SPREADS = {{"P12", "s12", "70,75,80"}, {"P16", "s16", "70,75,80"}, {"P20", "s20", "80"},
            {"S16", "s16_steep", "80"}};
    /** The wonging grid: the B plan entering at 0 for each spread. */
    static final String[][] WONGING = {{"B0,1", "s1"}, {"B0,8", "s8"}, {"B0,16", "s16"}};
    /** The bound rows by name, as the page's perfect row in each scope. */
    static final Map<String, String> BOUNDS = new LinkedHashMap<>();

    static {
        BOUNDS.put("Chart play and perfect insurance, bet on its own value", "bet");
        BOUNDS.put("Perfect first move and insurance, bet on its own value", "fd");
        BOUNDS.put("Perfect play of the dealt hand and insurance, bet on its own value", "full");
    }

    /** A row's {id, scope} on the page, or null for one the page does not show (a reference, a row on its own value). */
    static String[] row(Map<String, Object> r) {
        String name = SiteAdapt.str(r.get("name"), "name");
        String scope = SiteAdapt.str(r.get("scope"), name + ".scope");
        if (scope.equals("bound")) {
            return BOUNDS.containsKey(name) ? new String[]{"perfect", BOUNDS.get(name)} : null;
        }
        if (!scope.equals("first decisions") && !scope.equals("betting only") && !scope.equals("full play")) {
            return null;
        }
        String[] c = SiteInput.catalogRow(name);
        if (c == null) {
            throw new IllegalArgumentException("no page row for " + name);
        }
        return new String[]{c[0], SiteAdapt.scope(scope)};
    }

    static SiteInput adapt(Path file, String shown) throws IOException {
        Map<String, Object> d = SiteAdapt.read(file);
        if (!d.containsKey("plans") || !d.containsKey("keyColumns")) {
            throw new IllegalArgumentException(file + " is not WideReport's data file");
        }
        SiteInput in = new SiteInput("spreads", SiteAdapt.str(d.get("commit"), "commit"), "main");
        in.artifact(file, shown);
        List<Object> plans = new ArrayList<>();
        for (Object o : SiteAdapt.list(d.get("plans"), "plans")) {
            Map<String, Object> p = new LinkedHashMap<>(SiteAdapt.obj(o, "a plan"));
            p.put("key", SiteAdapt.part(SiteAdapt.str(p.get("label"), "label")));
            plans.add(p);
        }
        in.tables.put("wide_plans", plans);
        Map<String, Object> np = in.figure("wide.plans", plans.size(), "n");
        SiteInput.at(np, null, "plans");
        Map<String, double[]> perPlan = new LinkedHashMap<>();
        boolean widened = false;
        for (Object o : SiteAdapt.list(d.get("rows"), "rows")) {
            Map<String, Object> r = SiteAdapt.obj(o, "a row");
            String[] at = row(r);
            if (at == null) {
                continue;
            }
            Map<String, Object> pens = SiteAdapt.obj(r.get("pens"), r.get("name") + ".pens");
            for (Map.Entry<String, Object> pe : pens.entrySet()) {
                String pen = SiteAdapt.pen(pe.getKey());
                Map<String, Object> byPlan = SiteAdapt.obj(pe.getValue(), "pens." + pe.getKey());
                for (String[] s : SPREADS) {
                    Map<String, Object> f = same(byPlan, s[0]);
                    if (f != null && List.of(s[2].split(",")).contains(pen)) {
                        figure(in, at[1] + "." + pen + "." + s[1] + "." + at[0], f, r.get("name") + ", " + pe.getKey()
                                + ", " + s[0] + ", same schedule");
                    }
                }
                for (String[] w : WONGING) {
                    Map<String, Object> f = same(byPlan, w[0]);
                    if (f != null && !at[1].equals("bet")) {
                        double[] c = cell(f);
                        widened |= at[1].equals("full") && !Double.isNaN(c[2]);
                        in.cell(at[0], at[1] + "w", pen, w[1], c);
                    }
                }
                for (Map.Entry<String, Object> pl : byPlan.entrySet()) {
                    Map<String, Object> f = same(byPlan, pl.getKey());
                    if (f == null || at[0].equals("perfect")) {
                        continue;
                    }
                    String k = pen + "." + SiteAdapt.part(pl.getKey());
                    double[] bs = {SiteAdapt.num(f.get("bet"), k), SiteAdapt.num(f.get("share"), k)};
                    double[] was = perPlan.putIfAbsent(k, bs);
                    if (was != null && (Math.abs(was[0] - bs[0]) > 1e-12 * Math.abs(was[0])
                            || Math.abs(was[1] - bs[1]) > 1e-12 * Math.abs(was[1]))) {
                        throw new IllegalStateException(r.get("name") + " places other bets under " + pl.getKey() + " at "
                                + pe.getKey() + " than another row: the same schedule gives every row the same");
                    }
                }
            }
            thresholds(in, r, at);
        }
        if (!in.grid.isEmpty()) {
            in.grid.put("_widened", widened ? List.of("fullw") : List.of());
        }
        for (Map.Entry<String, double[]> e : perPlan.entrySet()) {
            Map<String, Object> b = in.figure("wide.bet." + e.getKey(), e.getValue()[0], "d2");
            SiteInput.at(b, null, "rows[].pens." + e.getKey() + ".same.bet");
            Map<String, Object> s = in.figure("wide.share." + e.getKey(), e.getValue()[1], "pct1");
            SiteInput.at(s, null, "rows[].pens." + e.getKey() + ".same.share");
        }
        return in;
    }

    static Map<String, Object> same(Map<String, Object> byPlan, String plan) {
        Object p = byPlan.get(plan);
        return p == null ? null : (Map<String, Object>) SiteAdapt.obj(p, plan).get("same");
    }

    /** A plan's EV as a cell: percent, with the SE and interval where the file has them, null otherwise. */
    static double[] cell(Map<String, Object> f) {
        Object ev = f.get("ev");
        if (ev == null) {
            return new double[]{SiteInput.pct(SiteAdapt.num(f.get("evPoint"), "evPoint")), Double.NaN, Double.NaN,
                    Double.NaN};
        }
        Map<String, Object> iv = SiteAdapt.obj(ev, "ev");
        return new double[]{SiteInput.pct(SiteAdapt.num(iv.get("est"), "est")), SiteInput.pct(SiteAdapt.num(iv.get("se"), "se")),
                SiteInput.pct(SiteAdapt.num(iv.get("lo"), "lo")), SiteInput.pct(SiteAdapt.num(iv.get("hi"), "hi"))};
    }

    static void figure(SiteInput in, String key, Map<String, Object> f, String at) {
        double[] c = cell(f);
        Map<String, Object> m = in.figure(key, c[0], "s4");
        if (!Double.isNaN(c[2])) {
            m.put("se", c[1]);
            m.put("ci", List.of(c[2], c[3]));
            SiteInput.widened(m, Math.max(1, SiteAdapt.num(f.get("widen"), key + ".widen")));
        }
        SiteInput.at(m, null, at);
    }

    /** Hi-Lo's key at each threshold, at each penetration, from its row's thresholdKeys. */
    static void thresholds(SiteInput in, Map<String, Object> r, String[] at) {
        if (!Boolean.TRUE.equals(r.get("hiLo")) || !at[1].equals("fd") || r.get("thresholdKeys") == null) {
            return;
        }
        for (Map.Entry<String, Object> pe : SiteAdapt.obj(r.get("thresholdKeys"), "thresholdKeys").entrySet()) {
            for (Map.Entry<String, Object> t : SiteAdapt.obj(pe.getValue(), "thresholdKeys." + pe.getKey()).entrySet()) {
                if (t.getValue() == null) {
                    continue;
                }
                String tau = SiteAdapt.part(new java.math.BigDecimal(t.getKey()).movePointRight(2).stripTrailingZeros()
                        .toPlainString());
                Map<String, Object> m = in.figure("wide.threshold." + SiteAdapt.pen(pe.getKey()) + "." + tau + "." + at[0],
                        SiteAdapt.num(t.getValue(), "a key"), "s0");
                SiteInput.at(m, null, r.get("name") + ".thresholdKeys." + pe.getKey() + "." + t.getKey());
            }
        }
    }
}
