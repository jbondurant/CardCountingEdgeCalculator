import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The page's charts (PLAN_PAGE.md 2.3), drawn from results.json like every other figure.
 *
 * Two forms. A <em>chart-table</em> is a real HTML table whose labels and values are text and
 * whose mark cell holds spans placed by CSS on one shared scale, with a tick row at the top: it
 * reflows on a phone, a screen reader reads it, and a program reads its numbers as text. A
 * <em>panel</em> is a small inline SVG line chart, 320 units wide, for figures with a line through
 * them (EV against spread). Both take their colours only from CSS classes that use the page's
 * variables, never a colour literal, so they switch with the page between light and dark.
 *
 * Every number either is a data-k span the page check holds to results.json, or an axis label
 * marked data-tick, written here from the axis's own range. No meaning rests on colour alone: this
 * project's rows carry ◆ and a bound is dashed.
 */
final class SiteSvg {

    private SiteSvg() {
    }

    /** A linear axis: its range, its ticks, and how many decimals the ticks need. */
    static final class Axis {
        final double lo;
        final double hi;
        final double[] ticks;
        final int places;

        Axis(double lo, double hi, double[] ticks, int places) {
            this.lo = lo;
            this.hi = hi;
            this.ticks = ticks;
            this.places = places;
        }

        /** Where x sits, in percent of the axis from its low end, clamped to the axis. */
        double pos(double x) {
            return Math.max(0, Math.min(100, 100 * (x - lo) / (hi - lo)));
        }

        String label(double t) {
            return SiteText.tick(t, places);
        }
    }

    /**
     * An axis over [min, max] with about n ticks at 1, 2 or 5 times a power of ten, widened to the
     * ticks either side, so every mark and interval end sits on the drawn scale.
     */
    static Axis axis(double min, double max, int n) {
        if (Double.isNaN(min) || Double.isNaN(max)) {
            throw new IllegalArgumentException("an axis over no values");
        }
        if (min == max) {
            min -= 0.5;
            max += 0.5;
        }
        double raw = (max - min) / Math.max(1, n);
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double step = mag;
        for (double m : new double[]{1, 2, 5, 10}) {
            if (m * mag >= raw) {
                step = m * mag;
                break;
            }
        }
        double lo = Math.floor(min / step - 1e-9) * step;
        double hi = Math.ceil(max / step + 1e-9) * step;
        int count = (int) Math.round((hi - lo) / step) + 1;
        double[] ticks = new double[count];
        for (int k = 0; k < count; k++) {
            ticks[k] = lo + k * step;
            if (Math.abs(ticks[k]) < step * 1e-9) {
                ticks[k] = 0;
            }
        }
        int places = Math.max(0, (int) -Math.floor(Math.log10(step) + 1e-9));
        return new Axis(lo, hi, ticks, places);
    }

    static String pct(double p) {
        return String.format(Locale.ROOT, "%.2f%%", p);
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** A display name as page text, held by the check to results.json's names. */
    static String name(SiteData d, String id) {
        String n = d.names.get(id);
        if (n == null) {
            throw new IllegalArgumentException("no display name for " + id + " in results.json");
        }
        return "<span data-name=\"" + esc(id) + "\">" + esc(n) + "</span>";
    }

    /** A figure as page text: a data-k span in the given format. */
    static String fig(SiteData d, String key, String fmt) {
        SiteData.Figure f = d.resolve(key);
        String f2 = fmt == null ? f.fmt : fmt;
        String body = esc(SiteText.format(f, f2));
        return "<span data-k=\"" + esc(key) + "\"" + (fmt == null ? "" : " data-f=\"" + esc(fmt) + "\"") + ">" + body
                + "</span>";
    }

    /** The row id of a key: its last part. */
    static String rowOf(String key) {
        return key.substring(key.lastIndexOf('.') + 1);
    }

    /**
     * How a row or line is drawn (PLAN_PAGE.md 2.2): c1, blue, for Hi-Lo and published counts picked
     * out; fg, the text colour, for other published rows; c2, orange, for this project's rows, which
     * also carry ◆ and words; ref, grey, for reference rows such as the null count. Only two hues,
     * so that no pair fails for red-weak colour vision; a bound is dashed besides.
     */
    static final List<String> ROLES = List.of("c1", "fg", "c2", "ref");

    /** One row of a chart-table: up to two keys (hollow first decisions, filled full play) and its marks. */
    static final class Row {
        final String id;
        final List<String> keys;
        final String role;
        final boolean bound;

        Row(String id, List<String> keys, String role, boolean bound) {
            if (!ROLES.contains(role)) {
                throw new IllegalArgumentException("no role " + role + "; one of " + ROLES);
            }
            this.id = id;
            this.keys = keys;
            this.role = role;
            this.bound = bound;
        }

        boolean project() {
            return role.equals("c2");
        }
    }

    /** A group of rows under a heading the template writes. */
    static final class Lane {
        final String label;
        final List<Row> rows = new ArrayList<>();

        Lane(String label) {
            this.label = label;
        }
    }

    /** A value column of a chart-table: which of the row's keys, and in what format. */
    static final class Column {
        final int key;
        final String fmt;

        Column(int key, String fmt) {
            this.key = key;
            this.fmt = fmt;
        }
    }

    /**
     * A dot chart-table: one row per count, a dot per key on one shared scale with its interval
     * behind it, the first of two keys hollow (first decisions) and the second filled (full play),
     * and the columns' figures as text beside the marks. head gives the name column's heading and
     * then one heading per column; project is the words that follow ◆ on this project's rows.
     */
    static String dots(String id, List<String> head, List<Column> cols, List<Lane> lanes, String project, SiteData d) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Lane l : lanes) {
            for (Row r : l.rows) {
                for (String k : r.keys) {
                    SiteData.Figure f = d.resolve(k);
                    min = Math.min(min, f.hasInterval() ? f.lo : f.value);
                    max = Math.max(max, f.hasInterval() ? f.hi : f.value);
                }
            }
        }
        if (head.size() != cols.size() + 1) {
            throw new IllegalArgumentException(id + ": " + head.size() + " headings for " + cols.size() + " columns and the name");
        }
        Axis ax = axis(min, max, 5);
        StringBuilder b = new StringBuilder();
        b.append("<div class=\"ct-wrap\"><table class=\"ct\" id=\"").append(esc(id)).append("\">\n<thead><tr><th scope=\"col\">")
                .append(head.get(0)).append("</th><th scope=\"col\" class=\"ax\"><span class=\"tks\">");
        for (double t : ax.ticks) {
            String l = ax.label(t);
            b.append("<span class=\"tk\" style=\"left:").append(pct(ax.pos(t))).append("\"><span data-tick=\"").append(l)
                    .append("\">").append(l).append("</span></span>");
        }
        b.append("</span></th>");
        for (int c = 0; c < cols.size(); c++) {
            b.append("<th scope=\"col\">").append(head.get(c + 1)).append("</th>");
        }
        b.append("</tr></thead>\n<tbody>\n");
        String zero = ax.lo < 0 && ax.hi > 0 ? "<span class=\"zero\" style=\"left:" + pct(ax.pos(0)) + "\"></span>" : "";
        for (Lane l : lanes) {
            if (l.label != null) {
                b.append("<tr class=\"lane\"><th colspan=\"").append(cols.size() + 2).append("\" scope=\"rowgroup\">")
                        .append(l.label).append("</th></tr>\n");
            }
            for (Row r : l.rows) {
                // A row's id names its scope too, since a lane per scope may show the same count.
                String scope = r.keys.get(0).substring(0, r.keys.get(0).indexOf('.'));
                b.append("<tr id=\"").append(esc(id + "-" + scope + "-" + r.id)).append("\"").append(r.project() ? " class=\"proj\"" : "")
                        .append("><th scope=\"row\">").append(name(d, r.id));
                if (r.project()) {
                    b.append(" <span class=\"pm\">◆ ").append(project).append("</span>");
                }
                b.append("</th><td class=\"mk\">").append(zero);
                for (int j = 0; j < r.keys.size(); j++) {
                    SiteData.Figure f = d.resolve(r.keys.get(j));
                    String cls = "k" + (j + 1) + " " + r.role + (r.bound ? " bd" : "");
                    if (f.hasInterval()) {
                        double a = ax.pos(f.lo);
                        b.append("<span class=\"iv ").append(cls).append("\" style=\"left:").append(pct(a)).append(";width:")
                                .append(pct(ax.pos(f.hi) - a)).append("\"></span>");
                    }
                    boolean hollow = r.keys.size() > 1 && j == 0;
                    b.append("<span class=\"dot ").append(cls).append(hollow ? " ho" : "").append("\" style=\"left:")
                            .append(pct(ax.pos(f.value))).append("\" title=\"").append(esc(d.names.get(r.id))).append(": ")
                            .append(esc(SiteText.format(f, f.fmt))).append("\"></span>");
                }
                b.append("</td>");
                for (Column c : cols) {
                    b.append("<td class=\"num\">").append(fig(d, r.keys.get(c.key - 1), c.fmt)).append("</td>");
                }
                b.append("</tr>\n");
            }
        }
        return b.append("</tbody></table></div>").toString();
    }

    /** One line of a panel: a key with {x} where the x category goes. */
    static final class Series {
        final String row;
        final String pattern;
        final String role;
        final boolean bound;

        Series(String row, String pattern, String role, boolean bound) {
            if (!ROLES.contains(role)) {
                throw new IllegalArgumentException("no role " + role + "; one of " + ROLES);
            }
            this.row = row;
            this.pattern = pattern;
            this.role = role;
            this.bound = bound;
        }

        String key(String x) {
            return pattern.replace("{x}", x);
        }
    }

    static final int W = 320;
    static final int H = 200;
    private static final int LEFT = 44;
    private static final int RIGHT = 8;
    private static final int TOP = 10;
    private static final int BOTTOM = 26;

    private static String n1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /**
     * A line panel: one line per series over the x categories (each a key part such as s8, whose
     * display name labels the axis), with the values in a table under it. title and desc are
     * the template's words; the values are only in the table, which the page check holds to
     * results.json.
     */
    static String lines(String id, String title, String desc, List<String> xs, List<Series> series, String valueFmt,
                        String project, SiteData d) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Series s : series) {
            for (String x : xs) {
                double v = d.resolve(s.key(x)).value;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        Axis ay = axis(min, max, 4);
        double pw = W - LEFT - RIGHT;
        double ph = H - TOP - BOTTOM;
        StringBuilder b = new StringBuilder();
        b.append("<svg class=\"panel\" id=\"").append(esc(id)).append("\" viewBox=\"0 0 ").append(W).append(' ').append(H)
                .append("\" role=\"img\" aria-labelledby=\"").append(esc(id)).append("-t ").append(esc(id))
                .append("-d\" xmlns=\"http://www.w3.org/2000/svg\">");
        b.append("<title id=\"").append(esc(id)).append("-t\">").append(esc(title)).append("</title><desc id=\"")
                .append(esc(id)).append("-d\">").append(esc(desc)).append("</desc>");
        for (double t : ay.ticks) {
            double y = TOP + ph * (1 - ay.pos(t) / 100);
            b.append("<line class=\"").append(t == 0 && ay.lo < 0 ? "zl" : "gl").append("\" x1=\"").append(LEFT)
                    .append("\" x2=\"").append(W - RIGHT).append("\" y1=\"").append(n1(y)).append("\" y2=\"").append(n1(y))
                    .append("\"/>");
            String l = ay.label(t);
            b.append("<text class=\"tl\" x=\"").append(LEFT - 4).append("\" y=\"").append(n1(y + 4))
                    .append("\" text-anchor=\"end\" data-tick=\"").append(l).append("\">").append(l).append("</text>");
        }
        for (int i = 0; i < xs.size(); i++) {
            double x = LEFT + (xs.size() == 1 ? pw / 2 : pw * i / (xs.size() - 1));
            b.append("<text class=\"tl\" x=\"").append(n1(x)).append("\" y=\"").append(H - 8)
                    .append("\" text-anchor=\"middle\" data-name=\"").append(esc(xs.get(i))).append("\">")
                    .append(esc(d.names.get(xs.get(i)))).append("</text>");
        }
        for (Series s : series) {
            String cls = s.role + (s.bound ? " bd" : "");
            StringBuilder pts = new StringBuilder();
            for (int i = 0; i < xs.size(); i++) {
                double x = LEFT + (xs.size() == 1 ? pw / 2 : pw * i / (xs.size() - 1));
                double y = TOP + ph * (1 - ay.pos(d.resolve(s.key(xs.get(i))).value) / 100);
                pts.append(i > 0 ? " " : "").append(n1(x)).append(',').append(n1(y));
            }
            b.append("<polyline class=\"ln ").append(cls).append("\" points=\"").append(pts)
                    .append("\" vector-effect=\"non-scaling-stroke\"><title data-name=\"").append(esc(s.row)).append("\">")
                    .append(esc(d.names.get(s.row))).append("</title></polyline>");
        }
        b.append("</svg>");
        b.append("\n<ul class=\"legend\">");
        for (Series s : series) {
            b.append("<li><span class=\"sw ").append(s.role).append(s.bound ? " bd" : "").append("\"></span>")
                    .append(name(d, s.row)).append(s.role.equals("c2") ? " <span class=\"pm\">◆ " + project + "</span>" : "")
                    .append("</li>");
        }
        b.append("</ul>\n<div class=\"tw\"><table class=\"vals\"><thead><tr><th scope=\"col\"></th>");
        for (String x : xs) {
            b.append("<th scope=\"col\">").append(name(d, x)).append("</th>");
        }
        b.append("</tr></thead><tbody>");
        for (Series s : series) {
            b.append("<tr><th scope=\"row\">").append(name(d, s.row)).append("</th>");
            for (String x : xs) {
                b.append("<td class=\"num\">").append(fig(d, s.key(x), valueFmt)).append("</td>");
            }
            b.append("</tr>");
        }
        return b.append("</tbody></table></div>").toString();
    }
}
