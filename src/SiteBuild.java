import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the card-counting results page (PLAN_PAGE.md 2.4) from the JSON files the computations
 * write into runs/site/: it merges them (SiteData), checks every comparative sentence's guard on
 * the inputs' resamples (SiteGuards), writes data/results.json and results.csv, reads
 * results.json back and renders the page from that copy, so the page shows exactly what the data
 * file says; then it writes the verification files (SiteManifest) and checks the page: no number
 * without a source (SiteText), every SVG well-formed, the budgets of PLAN_PAGE.md 2.5 held.
 *
 * <h2>The template</h2>
 * site/template.html holds all of the page's prose; numbers are never typed in it, only named:
 * <ul>
 *   <li>{@code {{KEY}}} or {@code {{KEY|FMT}}}: a figure, printed in a data-k span (formats in
 *   SiteText). KEY may name a row by rank instead of by id: {@code full.80.s8.@best} is the
 *   published count with the highest full.80.s8 figure.</li>
 *   <li>{@code {{name:KEY}}}, {@code {{names:KEY}}}: the display name of a key's row, or the names
 *   of a set of rows ({@code @topN}, {@code @all} for every published row, {@code @every} for every
 *   row) as a list.</li>
 *   <li>{@code {{s:NAME}}}: a string the build computed (version, date, the manifest's hash, a
 *   commit), in a data-s span.</li>
 *   <li>{@code {{guard: …}}}: a claim guard (SiteGuards); it prints nothing, and the build stops if
 *   it fails. {@code {{pairs:ranked|KEYS}}} and {@code {{pairs:unseparated|KEYS}}} print the pair
 *   lists of PLAN_PAGE.md 5a.</li>
 *   <li>{@code {{#if has:PREFIX}} … {{else}} … {{/if}}}: a section shown only when its data exists;
 *   the else branch says "Not yet computed".</li>
 *   <li>Blocks: {@code {{dots:ID …}}} a chart-table, {@code {{lines:ID …}}} an SVG panel,
 *   {@code {{rows:ID …}}} a sortable table of every count (see the methods below).</li>
 *   <li>{@code {{style}}}, {@code {{jsonld}}}: the style sheet with the site's colour tokens, and the
 *   JSON-LD block.</li>
 * </ul>
 * A build from any fixture input is marked by the code, not the template: a banner opens the
 * page, the title, the JSON-LD's name, results.json and the manifest all say FIXTURE, and the
 * build refuses to write into the website's tree. A template cannot leave the mark out.
 */
final class SiteBuild {

    private SiteBuild() {
    }

    /** Where the page is served, for the JSON-LD's URLs. Every one is https (PLAN_PAGE.md 3.6). */
    static final String BASE = "https://justinbondurantdavid.com/card-counting/";
    static final String REPO = "https://github.com/jbondurant/CardCountingEdgeCalculator";

    /** The page's budgets in bytes (PLAN_PAGE.md 2.5, PLAN_COMPUTE.md section 0). */
    static final int PAGE_BUDGET = 200 * 1024;
    static final int JS_BUDGET = 12 * 1024;
    static final int RESULTS_BUDGET = 500 * 1024;
    static final int CSV_BUDGET = 300 * 1024;
    static final int MANIFEST_BUDGET = 60 * 1024;

    /** The site's five colour tokens, whose one home is the website's index.html. */
    static final List<String> SITE_TOKENS = List.of("bg", "fg", "mute", "rule", "link");

    /**
     * The tokens this page adds (PLAN_PAGE.md 2.2), light then dark: blue for Hi-Lo and the
     * published counts picked out, orange for this project's rows. Orange is under 4.5:1 on the
     * light background, so it is never a text colour; SiteBuildTest holds the page to that.
     */
    static final Map<String, String[]> PAGE_TOKENS = new LinkedHashMap<>();

    static {
        PAGE_TOKENS.put("c1", new String[]{"#2a6cb0", "#4f8fd6"});
        PAGE_TOKENS.put("c2", new String[]{"#c2611f", "#d9762f"});
    }

    /** The site's tokens read from index.html: light then dark, each name to its value. */
    static final class Tokens {
        final Map<String, String> light = new LinkedHashMap<>();
        final Map<String, String> dark = new LinkedHashMap<>();

        /** The tokens as CSS: the site's and the page's, in both modes. */
        String css() {
            StringBuilder b = new StringBuilder(":root{");
            for (Map.Entry<String, String> e : light.entrySet()) {
                b.append("--").append(e.getKey()).append(':').append(e.getValue()).append(';');
            }
            for (Map.Entry<String, String[]> e : PAGE_TOKENS.entrySet()) {
                b.append("--").append(e.getKey()).append(':').append(e.getValue()[0]).append(';');
            }
            b.append("}\n@media (prefers-color-scheme: dark){:root{");
            for (Map.Entry<String, String> e : dark.entrySet()) {
                b.append("--").append(e.getKey()).append(':').append(e.getValue()).append(';');
            }
            for (Map.Entry<String, String[]> e : PAGE_TOKENS.entrySet()) {
                b.append("--").append(e.getKey()).append(':').append(e.getValue()[1]).append(';');
            }
            return b.append("}}\n").toString();
        }
    }

    private static final Pattern TOKEN = Pattern.compile("--([a-z]+)\\s*:\\s*(#[0-9a-fA-F]{6})\\s*;");

    /**
     * Reads the five tokens and their dark-mode values from the website's index.html, so the two
     * pages cannot drift apart and nobody keeps a copy in step (PLAN_PAGE.md 2.2). The first :root
     * block is light; the :root inside the prefers-color-scheme: dark block is dark. Stops unless
     * all ten values are found.
     */
    static Tokens tokens(String indexHtml) {
        Tokens t = new Tokens();
        int dark = indexHtml.indexOf("prefers-color-scheme: dark");
        int root = indexHtml.indexOf(":root");
        if (dark < 0 || root < 0 || root > dark) {
            throw new IllegalArgumentException("index.html has no light :root block followed by a dark one");
        }
        read(indexHtml.substring(root, indexHtml.indexOf('}', root)), t.light);
        int darkRoot = indexHtml.indexOf(":root", dark);
        read(indexHtml.substring(darkRoot, indexHtml.indexOf('}', darkRoot)), t.dark);
        for (String k : SITE_TOKENS) {
            if (!t.light.containsKey(k) || !t.dark.containsKey(k)) {
                throw new IllegalArgumentException("index.html does not give --" + k + " in both its light and dark "
                        + "blocks; the page takes all ten values from there and stops without them");
            }
        }
        t.light.keySet().retainAll(SITE_TOKENS);
        t.dark.keySet().retainAll(SITE_TOKENS);
        return t;
    }

    private static void read(String block, Map<String, String> into) {
        Matcher m = TOKEN.matcher(block);
        while (m.find()) {
            into.put(m.group(1), m.group(2).toLowerCase());
        }
    }

    // ---- rows and aliases ----

    /** The rows table: one entry per count, with its id, kind, level, balance and side count. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(SiteData d) {
        Object t = d.tables.get("rows");
        if (!(t instanceof List)) {
            throw new IllegalArgumentException("results.json has no rows table");
        }
        return (List<Map<String, Object>>) t;
    }

    static Map<String, Object> row(SiteData d, String id) {
        for (Map<String, Object> r : rows(d)) {
            if (id.equals(r.get("id"))) {
                return r;
            }
        }
        throw new IllegalArgumentException("no row " + id + " in the rows table");
    }

    private static final Pattern ALIAS = Pattern.compile("@(best|least|top([0-9]+)|all|every)");

    /**
     * The keys a key pattern names. A pattern's last part may be an alias in place of a row id:
     * {@code @best} (the published row with the highest figure under the rest of the key),
     * {@code @least} (the lowest),
     * {@code @topN} (the N highest, highest first), {@code @all} (every published row that has the
     * figure, highest first) or {@code @every} (every row, of any kind, that has it). Ranking is by
     * point estimate only; a sentence that calls one row better than another needs a guard besides.
     */
    static List<String> expand(String pattern, SiteData d) {
        int same = pattern.indexOf(".=");
        if (same >= 0) {
            // PREFIX.=OTHER: the figure under PREFIX for the row OTHER names, for "the same count at
            // another penetration" (full.70.s8.=full.80.s8.@best).
            return List.of(pattern.substring(0, same + 1) + SiteSvg.rowOf(one(pattern.substring(same + 2), d)));
        }
        int dot = pattern.lastIndexOf('.');
        String last = pattern.substring(dot + 1);
        Matcher m = ALIAS.matcher(last);
        if (!m.matches()) {
            return List.of(pattern);
        }
        String prefix = pattern.substring(0, dot + 1);
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> r : rows(d)) {
            String id = (String) r.get("id");
            if ((m.group(1).equals("every") || "published".equals(r.get("kind"))) && d.has(prefix + id)) {
                ids.add(id);
            }
        }
        ids.sort((a, b) -> {
            int c = Double.compare(d.resolve(prefix + b).value, d.resolve(prefix + a).value);
            return c != 0 ? c : a.compareTo(b);
        });
        if (m.group(1).equals("least")) {
            java.util.Collections.reverse(ids);
        }
        int n = m.group(1).equals("best") || m.group(1).equals("least") ? 1
                : m.group(2) != null ? Integer.parseInt(m.group(2)) : ids.size();
        if (ids.size() < n) {
            throw new IllegalArgumentException(pattern + " needs " + n + " rows with " + prefix + "ROW; there are " + ids.size());
        }
        List<String> out = new ArrayList<>();
        for (String id : ids.subList(0, n)) {
            out.add(prefix + id);
        }
        return out;
    }

    static String one(String pattern, SiteData d) {
        List<String> k = expand(pattern, d);
        if (k.size() != 1) {
            throw new IllegalArgumentException(pattern + " names " + k.size() + " figures where one is printed");
        }
        return k.get(0);
    }

    private static final Pattern KEY_TOKEN = Pattern.compile("[a-z0-9_.@]+");

    /**
     * A guard's text with its aliases replaced by row ids. An "each" guard whose expression holds
     * an alias naming several rows becomes one expression per row, all aliases in it taking the
     * same row in turn (the rows of its first alias, in their order).
     */
    static String expandGuard(String text, SiteData d) {
        String t = text.trim();
        if (t.startsWith("leader-tied:")) {
            return "leader-tied: " + String.join(", ", expandList(t.substring("leader-tied:".length()), d));
        }
        if (t.startsWith("each")) {
            int colon = t.indexOf(':');
            List<String> out = new ArrayList<>();
            for (String e : t.substring(colon + 1).split(";")) {
                out.addAll(expandEach(e.trim(), d));
            }
            return t.substring(0, colon + 1) + " " + String.join("; ", out);
        }
        List<String> one = expandEach(t, d);
        if (one.size() != 1) {
            throw new IllegalArgumentException("guard \"" + t + "\" names several rows; write it as each > 0: …");
        }
        return one.get(0);
    }

    static List<String> expandList(String list, SiteData d) {
        List<String> out = new ArrayList<>();
        for (String k : list.split(",")) {
            if (!k.trim().isEmpty()) {
                out.addAll(expand(k.trim(), d));
            }
        }
        return out;
    }

    private static List<String> expandEach(String expr, SiteData d) {
        Matcher m = KEY_TOKEN.matcher(expr);
        List<String> rowsOf = null;
        while (m.find()) {
            if (m.group().contains("@")) {
                rowsOf = new ArrayList<>();
                for (String k : expand(m.group(), d)) {
                    rowsOf.add(SiteSvg.rowOf(k));
                }
                break;
            }
        }
        if (rowsOf == null) {
            return List.of(expr);
        }
        List<String> out = new ArrayList<>();
        for (String id : rowsOf) {
            Matcher x = KEY_TOKEN.matcher(expr);
            StringBuffer b = new StringBuffer();
            while (x.find()) {
                String tok = x.group();
                String rep = tok.contains("@") ? tok.substring(0, tok.lastIndexOf('.') + 1) + id : tok;
                x.appendReplacement(b, Matcher.quoteReplacement(rep));
            }
            x.appendTail(b);
            out.add(b.toString());
        }
        return out;
    }

    // ---- blocks ----

    /** A block's lines, each split once at its first space: the word, then the rest. */
    private static List<String[]> lines(String body) {
        List<String[]> out = new ArrayList<>();
        for (String l : body.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty()) {
                int sp = t.indexOf(' ');
                out.add(sp < 0 ? new String[]{t, ""} : new String[]{t.substring(0, sp), t.substring(sp + 1).trim()});
            }
        }
        return out;
    }

    private static List<String> bars(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split("\\|")) {
            out.add(p.trim());
        }
        return out;
    }

    /** A row's role from the rows table: its own if given, else by its kind. */
    static String roleOf(SiteData d, String id) {
        Map<String, Object> r = row(d, id);
        if (r.get("role") instanceof String) {
            return (String) r.get("role");
        }
        String kind = (String) r.get("kind");
        return "searched".equals(kind) ? "c2" : "perfect".equals(kind) || "reference".equals(kind) ? "ref" : "fg";
    }

    /** {{dots:ID …}}: a chart-table (SiteSvg.dots). */
    static String dots(String id, String body, SiteData d) {
        List<String> head = null;
        List<SiteSvg.Column> cols = new ArrayList<>();
        List<SiteSvg.Lane> lanes = new ArrayList<>();
        String project = "";
        for (String[] l : lines(body)) {
            switch (l[0]) {
                case "head": head = bars(l[1]); break;
                case "project": project = l[1]; break;
                case "cols":
                    for (String c : bars(l[1])) {
                        String[] p = c.split(":");
                        cols.add(new SiteSvg.Column(Integer.parseInt(p[0]), p[1]));
                    }
                    break;
                case "lane": lanes.add(new SiteSvg.Lane(l[1])); break;
                case "row":
                case "each": {
                    if (lanes.isEmpty()) {
                        lanes.add(new SiteSvg.Lane(null));
                    }
                    List<String> parts = bars(l[1]);
                    String[] keys = parts.get(0).split("\\+");
                    String role = parts.size() > 1 ? parts.get(1).split(" ")[0] : null;
                    boolean bound = parts.size() > 1 && parts.get(1).contains("bound");
                    List<String> first = l[0].equals("each") ? expand(keys[0].trim(), d) : List.of(one(keys[0].trim(), d));
                    for (String k : first) {
                        String rid = SiteSvg.rowOf(k);
                        List<String> ks = new ArrayList<>();
                        ks.add(k);
                        for (int j = 1; j < keys.length; j++) {
                            String p = keys[j].trim();
                            ks.add(p.substring(0, p.lastIndexOf('.') + 1) + rid);
                        }
                        Map<String, Object> r = row(d, rid);
                        boolean b = bound || "perfect".equals(r.get("kind"));
                        lanes.get(lanes.size() - 1).rows.add(new SiteSvg.Row(rid, ks, role != null ? role : roleOf(d, rid), b));
                    }
                    break;
                }
                default:
                    throw new IllegalArgumentException("dots:" + id + ": unknown line \"" + l[0] + "\"");
            }
        }
        if (head == null) {
            throw new IllegalArgumentException("dots:" + id + " has no head line");
        }
        return SiteSvg.dots(id, head, cols, lanes, project, d);
    }

    /** {{lines:ID …}}: an SVG panel (SiteSvg.lines). */
    static String linesBlock(String id, String body, SiteData d) {
        String title = null;
        String desc = null;
        String fmt = "s3";
        String project = "";
        List<String> xs = new ArrayList<>();
        List<SiteSvg.Series> series = new ArrayList<>();
        List<String[]> ls = lines(body);
        for (String[] l : ls) {
            if (l[0].equals("x")) {
                xs.addAll(List.of(l[1].split("\\s+")));
            }
        }
        for (String[] l : ls) {
            switch (l[0]) {
                case "title": title = l[1]; break;
                case "desc": desc = l[1]; break;
                case "fmt": fmt = l[1]; break;
                case "project": project = l[1]; break;
                case "x": break;
                case "series": {
                    List<String> parts = bars(l[1]);
                    String[] kb = parts.get(0).split(" by ");
                    String pattern = kb[0].trim();
                    String by = kb.length > 1 ? kb[1].trim() : xs.get(xs.size() - 1);
                    String rid = SiteSvg.rowOf(one(pattern.replace("{x}", by), d));
                    String concrete = pattern.substring(0, pattern.lastIndexOf('.') + 1) + rid;
                    String role = parts.size() > 1 ? parts.get(1).split(" ")[0] : roleOf(d, rid);
                    boolean bound = (parts.size() > 1 && parts.get(1).contains("bound")) || "perfect".equals(row(d, rid).get("kind"));
                    series.add(new SiteSvg.Series(rid, concrete, role, bound));
                    break;
                }
                default:
                    throw new IllegalArgumentException("lines:" + id + ": unknown line \"" + l[0] + "\"");
            }
        }
        if (title == null || desc == null || xs.isEmpty() || series.isEmpty()) {
            throw new IllegalArgumentException("lines:" + id + " needs title, desc, x and at least one series");
        }
        return SiteSvg.lines(id, title, desc, xs, series, fmt, project, d);
    }

    /** A rows-table field as page text, held by the check to the rows table (data-t). */
    static String field(SiteData d, String id, String field, String words) {
        Object v = row(d, id).get(field);
        String text = SiteText.field(v, words);
        return "<span data-t=\"rows/" + SiteSvg.esc(id) + "/" + SiteSvg.esc(field) + "\""
                + (words == null ? "" : " data-w=\"" + SiteSvg.esc(words) + "\"") + ">" + SiteSvg.esc(text) + "</span>";
    }

    /**
     * {{rows:ID …}}: every count as a table a reader can sort (PLAN_PAGE.md 5c), one row each with a
     * stable anchor (#count-zen) so a reader or a program can link to one count. Lines: head (the
     * headings), kinds (which rows), order (the key, with ROW for the row, that sets the static
     * order, highest first), and one col per column: {@code @FIELD} or {@code @FIELD|YES|NO} for a
     * field of the rows table, {@code KEY|FMT} with ROW in the key for a figure. A row with no such
     * figure shows a dash.
     */
    static String rowsBlock(String id, String body, SiteData d) {
        List<String> head = null;
        List<String> kinds = new ArrayList<>();
        String order = null;
        List<String> cols = new ArrayList<>();
        for (String[] l : lines(body)) {
            switch (l[0]) {
                case "head": head = bars(l[1]); break;
                case "kinds": kinds.addAll(List.of(l[1].split("\\s+"))); break;
                case "order": order = l[1]; break;
                case "col": cols.add(l[1]); break;
                default: throw new IllegalArgumentException("rows:" + id + ": unknown line \"" + l[0] + "\"");
            }
        }
        if (head == null || order == null || head.size() != cols.size() + 1) {
            throw new IllegalArgumentException("rows:" + id + " needs a head with one heading per column and the name, and an order");
        }
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> r : rows(d)) {
            if (kinds.contains((String) r.get("kind")) && d.has(order.replace("ROW", (String) r.get("id")))) {
                ids.add((String) r.get("id"));
            }
        }
        final String ord = order;
        ids.sort((a, b) -> Double.compare(d.resolve(ord.replace("ROW", b)).value, d.resolve(ord.replace("ROW", a)).value));
        StringBuilder b = new StringBuilder("<div class=\"tw\"><table class=\"rows sortable\" id=\"").append(SiteSvg.esc(id))
                .append("\">\n<thead><tr>");
        for (String h : head) {
            b.append("<th scope=\"col\"><button type=\"button\">").append(h).append("</button></th>");
        }
        b.append("</tr></thead>\n<tbody>\n");
        for (String rid : ids) {
            b.append("<tr id=\"count-").append(SiteSvg.esc(rid)).append("\"><th scope=\"row\" data-v=\"")
                    .append(SiteSvg.esc(d.names.getOrDefault(rid, rid))).append("\">").append(SiteSvg.name(d, rid)).append("</th>");
            for (String c : cols) {
                List<String> p = bars(c);
                if (p.get(0).startsWith("@")) {
                    String f = p.get(0).substring(1);
                    String words = p.size() > 2 ? p.get(1) + "|" + p.get(2) : null;
                    Object v = row(d, rid).get(f);
                    b.append("<td data-v=\"").append(SiteSvg.esc(String.valueOf(v))).append("\">")
                            .append(field(d, rid, f, words)).append("</td>");
                } else {
                    String key = p.get(0).replace("ROW", rid);
                    if (d.has(key)) {
                        b.append("<td class=\"num\" data-v=\"").append(Json.number(d.resolve(key).value)).append("\">")
                                .append(SiteSvg.fig(d, key, p.size() > 1 ? p.get(1) : null)).append("</td>");
                    } else {
                        b.append("<td class=\"num\" data-v=\"\">–</td>");
                    }
                }
            }
            b.append("</tr>\n");
        }
        return b.append("</tbody></table></div>").toString();
    }

    // ---- the template ----

    private static final Pattern IF = Pattern.compile("\\{\\{#if ([^}]*)\\}\\}(.*?)(?:\\{\\{else\\}\\}(.*?))?\\{\\{/if\\}\\}",
            Pattern.DOTALL);
    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(.*?)\\}\\}", Pattern.DOTALL);

    /**
     * Resolves {{#if …}} blocks: "has:P1,P2" holds when the data has a figure or table under every
     * prefix, "fixture" when the build is a fixture, and "guard: …" when that claim guard holds on
     * the inputs (it is evaluated here, on the inputs' resamples, and recorded). Blocks do not
     * nest. This runs on the inputs, before results.json is written, since only they hold the
     * resamples.
     */
    static String conditionals(String template, SiteData d) {
        Matcher m = IF.matcher(template);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            if (m.group(2).contains("{{#if")) {
                throw new IllegalArgumentException("{{#if}} blocks do not nest: " + m.group(1));
            }
            String c = m.group(1).trim();
            boolean holds;
            if (c.equals("fixture")) {
                holds = d.fixture();
            } else if (c.startsWith("guard:")) {
                // A sentence chosen by the data: the branch whose guard holds is printed, and the
                // guard is recorded in results.json like every other.
                String text = expandGuard(c.substring(6), d);
                Map<String, Object> rec = SiteGuards.evaluate(text, d);
                d.guards.add(rec);
                holds = Boolean.TRUE.equals(rec.get("holds"));
            } else if (c.startsWith("has:")) {
                holds = true;
                for (String p : c.substring(4).split(",")) {
                    holds &= d.hasPrefix(p.trim());
                }
            } else {
                throw new IllegalArgumentException("unknown condition {{#if " + c + "}}");
            }
            String chosen = holds ? m.group(2) : m.group(3) == null ? "" : m.group(3);
            m.appendReplacement(b, Matcher.quoteReplacement(chosen));
        }
        m.appendTail(b);
        String out = b.toString();
        if (out.contains("{{#if") || out.contains("{{else}}") || out.contains("{{/if}}")) {
            throw new IllegalArgumentException("an {{#if}} block is not closed");
        }
        return out;
    }

    /**
     * Evaluates every guard and pair list in the template on the inputs' resamples, records each in
     * the data, and returns the guards that fail, each with its expanded text.
     */
    static List<String> guards(String template, SiteData in, String generator) {
        List<String> failed = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            String p = m.group(1).trim();
            if (p.startsWith("guard:")) {
                String text = expandGuard(p.substring(6), in);
                Map<String, Object> rec = SiteGuards.evaluate(text, in);
                in.guards.add(rec);
                if (!Boolean.TRUE.equals(rec.get("holds"))) {
                    failed.add(text + "  " + Json.compact(rec));
                }
            } else if (p.startsWith("range:")) {
                String pat = p.substring(6, p.indexOf('|') < 0 ? p.length() : p.indexOf('|')).trim();
                List<String> keys = expandList(pat, in);
                double lo = Double.POSITIVE_INFINITY;
                double hi = Double.NEGATIVE_INFINITY;
                for (String k : keys) {
                    lo = Math.min(lo, in.resolve(k).value);
                    hi = Math.max(hi, in.resolve(k).value);
                }
                String key = rangeKey(pat);
                if (!in.figures.containsKey(key)) {
                    in.figures.put(key, new SiteData.Figure(key, hi - lo, Double.NaN, Double.NaN, Double.NaN, "s4",
                            "computed by SiteBuild", "the highest less the lowest of " + String.join(", ", keys), generator, "main",
                            1, false, null));
                }
            } else if (p.startsWith("pairs:")) {
                List<String> keys = expandList(p.substring(p.indexOf('|') + 1), in);
                String g = "pairs: " + String.join(", ", keys);
                boolean seen = false;
                for (Map<String, Object> r : in.guards) {
                    seen |= g.equals(r.get("guard"));
                }
                if (!seen) {
                    in.guards.add(SiteGuards.pairsRecord(SiteGuards.pairs(keys, in)));
                }
            }
        }
        return failed;
    }

    /**
     * The key of a range figure: the highest less the lowest of a set of rows, a figure no run
     * prints, computed here on the inputs and written to results.json like any other, so the page
     * prints it from there. Its "at" names the keys it was taken over.
     */
    static String rangeKey(String pattern) {
        return "range." + pattern.replace("@", "").replace(".", "_");
    }

    static Map<String, Object> guardRecord(SiteData d, String text) {
        for (Map<String, Object> r : d.guards) {
            if (text.equals(r.get("guard"))) {
                return r;
            }
        }
        throw new IllegalArgumentException("guard \"" + text + "\" is not in results.json: the rows it names "
                + "differ between the inputs and the data file read back");
    }

    /** "A, B and C" from rendered names. */
    static String list(List<String> items) {
        if (items.isEmpty()) {
            return "none";
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    @SuppressWarnings("unchecked")
    static String pairsText(String p, SiteData d) {
        String kind = p.substring("pairs:".length(), p.indexOf('|')).trim();
        List<String> keys = expandList(p.substring(p.indexOf('|') + 1), d);
        Map<String, Object> rec = guardRecord(d, "pairs: " + String.join(", ", keys));
        List<String> out = new ArrayList<>();
        for (Object o : (List<Object>) rec.get("pairs")) {
            List<Object> x = (List<Object>) o;
            boolean sep = (Boolean) x.get(5);
            String a = SiteSvg.name(d, SiteSvg.rowOf((String) x.get(0)));
            String b = SiteSvg.name(d, SiteSvg.rowOf((String) x.get(1)));
            if (kind.equals("ranked") && sep) {
                out.add(a + " ahead of " + b);
            } else if (kind.equals("unseparated") && !sep) {
                out.add(a + " and " + b);
            } else if (!kind.equals("ranked") && !kind.equals("unseparated")) {
                throw new IllegalArgumentException("pairs:" + kind + " is neither ranked nor unseparated");
            }
        }
        return out.isEmpty() ? "none" : String.join("; ", out);
    }

    static final String JSONLD_MARK = "\u0000jsonld\u0000";

    /** Renders the template (conditionals already resolved) from the data read back from results.json. */
    static String render(String template, SiteData d, Map<String, String> strings, Tokens tokens, String css) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String p = m.group(1).trim();
            String out;
            try {
                out = placeholder(p, d, strings, tokens, css);
            } catch (RuntimeException x) {
                throw new IllegalArgumentException("{{" + (p.length() > 60 ? p.substring(0, 60) + "…" : p) + "}}: "
                        + x.getMessage(), x);
            }
            m.appendReplacement(b, Matcher.quoteReplacement(out));
        }
        m.appendTail(b);
        return b.toString();
    }

    private static String placeholder(String p, SiteData d, Map<String, String> strings, Tokens tokens, String css) {
        int nl = p.indexOf('\n');
        String head = nl < 0 ? p : p.substring(0, nl).trim();
        String body = nl < 0 ? "" : p.substring(nl + 1);
        if (head.startsWith("dots:")) {
            return dots(head.substring(5).trim(), body, d);
        }
        if (head.startsWith("lines:")) {
            return linesBlock(head.substring(6).trim(), body, d);
        }
        if (head.startsWith("rows:")) {
            return rowsBlock(head.substring(5).trim(), body, d);
        }
        if (p.startsWith("guard:")) {
            Map<String, Object> rec = guardRecord(d, expandGuard(p.substring(6), d));
            if (!Boolean.TRUE.equals(rec.get("holds"))) {
                throw new IllegalArgumentException("its guard does not hold; the sentence must be rewritten");
            }
            return "";
        }
        if (p.startsWith("pairs:")) {
            return pairsText(p, d);
        }
        if (p.startsWith("range:")) {
            int bar = p.indexOf('|');
            String pat = p.substring(6, bar < 0 ? p.length() : bar).trim();
            String at = String.join(", ", expandList(pat, d));
            SiteData.Figure f = d.resolve(rangeKey(pat));
            if (!f.at.equals("the highest less the lowest of " + at)) {
                throw new IllegalArgumentException("the rows of " + pat + " differ between the inputs and results.json");
            }
            return SiteSvg.fig(d, rangeKey(pat), bar < 0 ? null : p.substring(bar + 1).trim());
        }
        if (p.startsWith("names:")) {
            List<String> out = new ArrayList<>();
            for (String k : expandList(p.substring(6), d)) {
                out.add(SiteSvg.name(d, SiteSvg.rowOf(k)));
            }
            return list(out);
        }
        if (p.startsWith("name:")) {
            String a = p.substring(5).trim();
            return SiteSvg.name(d, a.contains(".") ? SiteSvg.rowOf(one(a, d)) : a);
        }
        if (p.startsWith("s:")) {
            String k = p.substring(2).trim();
            if (!strings.containsKey(k)) {
                throw new IllegalArgumentException("the build has no string " + k);
            }
            return "<span data-s=\"" + SiteSvg.esc(k) + "\">" + SiteSvg.esc(strings.get(k)) + "</span>";
        }
        if (p.startsWith("link:")) {
            String k = p.substring(5).trim();
            if (!strings.containsKey("commit." + k)) {
                throw new IllegalArgumentException("no input named " + k + " to link the commit of");
            }
            return "<a href=\"" + REPO + "/commit/" + SiteSvg.esc(strings.get("commit." + k)) + "\"><code data-s=\"short."
                    + SiteSvg.esc(k) + "\">" + SiteSvg.esc(strings.get("short." + k)) + "</code></a>";
        }
        if (p.startsWith("tags:")) {
            return tags(SiteGuards.keys(p.substring(5) + ","), d);
        }
        if (p.startsWith("files:")) {
            return files(bars(p.substring(6)), strings);
        }
        switch (p) {
            case "shoe":
                return shoe(d);
            case "style":
                return "<style>\n" + tokens.css() + css + "</style>";
            case "jsonld":
                return JSONLD_MARK;
            default:
        }
        int bar = p.indexOf('|');
        String key = (bar < 0 ? p : p.substring(0, bar)).trim();
        String fmt = bar < 0 ? null : p.substring(bar + 1).trim();
        if (!SiteData.KEY.matcher(key.replace("@", "").replace(".=", ".")).matches()) {
            throw new IllegalArgumentException("not a placeholder this generator knows");
        }
        return SiteSvg.fig(d, one(key, d), fmt);
    }

    /**
     * The shoe (PLAN_PAGE.md section 2 of the page): a bar of the shoe's cards as thin ticks, drawn
     * by CSS from the card count, with each cut-card place marked and labelled from the game block.
     */
    static String shoe(SiteData d) {
        StringBuilder b = new StringBuilder("<div class=\"shoe\" role=\"img\" aria-labelledby=\"shoe-cap\" style=\"--cards:")
                .append(Math.round(d.resolve("game.cards").value)).append("\">");
        for (String k : d.figures.keySet()) {
            if (k.startsWith("game.pen.")) {
                b.append("<span class=\"cut\" style=\"left:").append(SiteSvg.pct(d.resolve(k).value)).append("\">")
                        .append(SiteSvg.fig(d, k, "n")).append("%</span>");
            }
        }
        return b.append("</div>").toString();
    }

    /**
     * A tag table: one column per rank, named from the names block (r1 is the ace, r10 the ten-valued
     * cards), one row per count, each cell the figure tags.ID.rN, signed.
     */
    static String tags(List<String> ids, SiteData d) {
        StringBuilder b = new StringBuilder("<div class=\"tw\"><table class=\"tags\"><thead><tr><th scope=\"col\"></th>");
        for (int r = 1; r <= 10; r++) {
            b.append("<th scope=\"col\">").append(SiteSvg.name(d, "r" + r)).append("</th>");
        }
        b.append("</tr></thead><tbody>");
        for (String id : ids) {
            b.append("<tr><th scope=\"row\">").append(SiteSvg.name(d, id)).append("</th>");
            for (int r = 1; r <= 10; r++) {
                String k = "tags." + id + ".r" + r;
                double v = d.resolve(k).value;
                b.append("<td class=\"num ").append(v > 0 ? "tp" : v < 0 ? "tn" : "t0").append("\">")
                        .append(SiteSvg.fig(d, k, null)).append("</td>");
            }
            b.append("</tr>");
        }
        return b.append("</tbody></table></div>").toString();
    }

    /**
     * The files table of the downloads section, from the manifest the build just wrote: each file's
     * name, size and SHA-256, all as data-s strings. head gives the four headings.
     */
    static String files(List<String> head, Map<String, String> strings) {
        if (head.size() != 4) {
            throw new IllegalArgumentException("files: needs four headings");
        }
        StringBuilder b = new StringBuilder("<div class=\"tw\"><table class=\"files\"><thead><tr>");
        for (String h : head) {
            b.append("<th scope=\"col\">").append(h).append("</th>");
        }
        b.append("</tr></thead><tbody>");
        for (int k = 0; strings.containsKey("file." + k + ".path"); k++) {
            String f = "file." + k;
            b.append("<tr><td><code data-s=\"").append(f).append(".path\">").append(SiteSvg.esc(strings.get(f + ".path")))
                    .append("</code></td><td class=\"num\"><span data-s=\"").append(f).append(".bytes\">")
                    .append(strings.get(f + ".bytes")).append("</span></td><td><code class=\"hash\" data-s=\"").append(f)
                    .append(".sha\">").append(strings.get(f + ".sha")).append("</code></td><td><code data-s=\"").append(f)
                    .append(".by\">").append(SiteSvg.esc(strings.get(f + ".by"))).append("</code></td></tr>");
        }
        return b.append("</tbody></table></div>").toString();
    }

    /** The plain text of the element with this id, tags taken out and spaces closed up. */
    static String textOf(String page, String id) {
        Matcher m = Pattern.compile("<(\\w+)[^>]*\\bid=\"" + Pattern.quote(id) + "\"[^>]*>(.*?)</\\1>", Pattern.DOTALL)
                .matcher(page);
        if (!m.find()) {
            throw new IllegalArgumentException("the page has no element with id " + id);
        }
        return SiteText.decode(m.group(2).replaceAll("<[^>]*>", "")).replaceAll("\\s+", " ").trim();
    }

    /**
     * The JSON-LD Dataset (PLAN_PAGE.md 3.6). Its description is the opening answer and its line
     * of conditions, taken from the rendered page, so it says exactly what the page says.
     */
    static String jsonld(String page, SiteData d, Map<String, String> strings, Map<String, byte[]> served) {
        Map<String, Object> ld = new LinkedHashMap<>();
        ld.put("@context", "https://schema.org");
        ld.put("@type", "Dataset");
        ld.put("name", textOf(page, "title"));
        ld.put("description", textOf(page, "answer") + " " + textOf(page, "conditions"));
        ld.put("url", BASE);
        Map<String, Object> creator = new LinkedHashMap<>();
        creator.put("@type", "Person");
        creator.put("name", "Justin Bondurant-David");
        creator.put("url", "https://justinbondurantdavid.com/");
        ld.put("creator", creator);
        ld.put("version", strings.get("version"));
        ld.put("dateModified", strings.get("date"));
        Map<String, Object> code = new LinkedHashMap<>();
        code.put("@type", "SoftwareSourceCode");
        code.put("codeRepository", REPO);
        code.put("version", strings.getOrDefault("commit.generator", ""));
        ld.put("isBasedOn", code);
        List<Object> dist = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : served.entrySet()) {
            Map<String, Object> dd = new LinkedHashMap<>();
            dd.put("@type", "DataDownload");
            dd.put("contentUrl", BASE + e.getKey());
            dd.put("contentSize", e.getValue().length + " B");
            dd.put("encodingFormat", e.getKey().endsWith(".json") ? "application/json"
                    : e.getKey().endsWith(".csv") ? "text/csv" : "text/plain");
            dd.put("sha256", SiteData.sha256(e.getValue()));
            dist.add(dd);
        }
        ld.put("distribution", dist);
        ld.put("measurementTechnique", "Exact expected values of every first move from sampled shoe states, "
                + "with plays and bets fitted on half of the shoes and scored on the other half, and bootstrap intervals.");
        ld.put("variableMeasured", "Expected value per round, in percent of the minimum bet, for each card-counting system.");
        if (d.fixture()) {
            ld.put("name", "FIXTURE, NOT RESULTS: " + ld.get("name"));
        }
        return "<script type=\"application/ld+json\">\n" + Json.write(ld, 3).replace("</", "<\\/") + "</script>";
    }

    // ---- the build ----

    static final String FIXTURE_TITLE = "FIXTURE, NOT RESULTS: ";
    static final String FIXTURE_BANNER = "<div class=\"fixture\" role=\"note\"><strong>Fixture build, not results.</strong> "
            + "Every number on this page is made up by a test fixture to exercise the page generator. None of it is "
            + "a finding about card counting.</div>";

    /** What a build makes: every file under card-counting/, the data it rendered from, and its problems. */
    static final class Output {
        final Map<String, byte[]> files = new TreeMap<>();
        final List<String> problems = new ArrayList<>();
        SiteData data;
        Map<String, String> strings;

        String text(String path) {
            return new String(files.get(path), StandardCharsets.UTF_8);
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * One build. Stops (throws) on a typed figure in the template, a failed guard, or anything the
     * inputs do not support; returns the files and the problems the finished page's checks found,
     * which main refuses to write while any remain.
     */
    static Output build(SiteData in, String template, String css, String js, Tokens tokens, String version, String date,
                        String generator) {
        List<String> typed = SiteText.checkTemplate(template);
        if (!typed.isEmpty()) {
            throw new IllegalArgumentException("the template has typed figures:\n  " + String.join("\n  ", typed));
        }
        String t = conditionals(template, in);
        List<String> failed = guards(t, in, generator);
        if (!failed.isEmpty()) {
            throw new IllegalArgumentException("claim guards that fail; rewrite these sentences by hand:\n  "
                    + String.join("\n  ", failed));
        }
        String results = in.write(version, date);
        SiteData d = SiteData.fromJson(results);
        Output o = new Output();
        o.data = d;
        // The CSV holds the figures the page prints, so the page is rendered once to learn which
        // they are; the manifest's hash, which the page shows, does not change which they are.
        Map<String, String> draft = new TreeMap<>();
        draft.put("version", version);
        draft.put("date", date);
        draft.put("manifest", "");
        draft.put("commit.generator", generator);
        for (SiteData.Input i : d.inputs) {
            draft.put("commit." + i.what(), i.commit());
            draft.put("short." + i.what(), i.commit());
        }
        for (int k = 0; k < 64; k++) {
            fileStrings(draft, k, "", 0, "", "");
        }
        java.util.Set<String> cited = new java.util.TreeSet<>();
        Matcher dk = Pattern.compile("data-k=\"([^\"]+)\"").matcher(render(t, d, draft, tokens, css));
        while (dk.find()) {
            cited.add(dk.group(1));
        }
        Map<String, byte[]> served = new LinkedHashMap<>();
        served.put("data/results.json", utf8(results));
        served.put("data/results.csv", utf8(d.csv(cited)));
        SiteManifest man = SiteManifest.of(in, served, version, date);
        Map<String, String> strings = new TreeMap<>();
        strings.put("version", version);
        strings.put("date", date);
        strings.put("manifest", man.sha256());
        strings.put("commit.generator", generator);
        for (SiteData.Input i : d.inputs) {
            strings.put("commit." + i.what(), i.commit());
            strings.put("short." + i.what(), i.commit().length() > 7 ? i.commit().substring(0, 7) : i.commit());
        }
        int fk = 0;
        for (Map.Entry<String, byte[]> e : served.entrySet()) {
            fileStrings(strings, fk++, e.getKey(), e.getValue().length, SiteData.sha256(e.getValue()), "SiteBuild " + generator);
        }
        for (Map<String, Object> art : man.artifacts) {
            fileStrings(strings, fk++, (String) art.get("path"), art.get("bytes") == null ? -1 : ((Number) art.get("bytes")).longValue(),
                    String.valueOf(art.get("sha256")), String.valueOf(art.get("commit")));
        }
        o.strings = strings;
        String page = render(t, d, strings, tokens, css);
        if (d.fixture()) {
            page = page.replaceFirst("<title>", "<title>" + FIXTURE_TITLE).replaceFirst("<body>", "<body>\n" + FIXTURE_BANNER);
        }
        page = page.replace(JSONLD_MARK, jsonld(page, d, strings, served));
        o.files.put("index.html", utf8(page));
        o.files.putAll(served);
        o.files.put("data/manifest.json", utf8(man.json));
        o.files.put("data/SHA256SUMS", utf8(man.sums));
        o.files.put("data/MANIFEST.md", utf8(man.md));
        o.files.put("assets/page.js", utf8(js));
        o.problems.addAll(check(o, css));
        return o;
    }

    private static void fileStrings(Map<String, String> s, int k, String path, long bytes, String sha, String by) {
        s.put("file." + k + ".path", path);
        s.put("file." + k + ".bytes", bytes < 0 ? "not recorded" : SiteText.grouped(bytes));
        s.put("file." + k + ".sha", sha);
        s.put("file." + k + ".by", by);
    }

    /** External links the page may carry (PLAN_PAGE.md 2.6): the site, its repos, and the cited sources. */
    static final List<String> LINK_HOSTS = List.of("https://justinbondurantdavid.com/", "https://github.com/jbondurant/",
            "https://wizardofodds.com/", "https://www.qfit.com/", "https://www.blackjackincolor.com/",
            "https://schema.org/", "https://llmstxt.org/", "https://opentimestamps.org/");

    private static final Pattern COLOUR = Pattern.compile("#[0-9a-fA-F]{3,8}\\b|\\brgba?\\(|\\bhsla?\\(|\\bcolor-mix\\(");

    /** The finished page's checks, each a rule of PLAN_PAGE.md 2.4 to 2.6. */
    static List<String> check(Output o, String css) {
        List<String> p = new ArrayList<>();
        String page = o.text("index.html");
        p.addAll(SiteText.checkPage(page, o.data, o.strings));
        if (o.data.fixture() && (!page.contains(FIXTURE_BANNER) || !page.contains("<title>" + FIXTURE_TITLE))) {
            p.add("a fixture build without its banner and title mark");
        }
        if (page.contains("{{")) {
            p.add("a placeholder is left unresolved");
        }
        Matcher svg = Pattern.compile("<svg\\b.*?</svg>", Pattern.DOTALL).matcher(page);
        while (svg.find()) {
            String s = svg.group();
            try {
                javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                f.setNamespaceAware(true);
                f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(utf8(s)));
            } catch (Exception x) {
                p.add("an SVG that does not parse as XML: " + x.getMessage());
            }
            if (COLOUR.matcher(s).find()) {
                p.add("an SVG with a colour written into it rather than taken from the page's tokens");
            }
        }
        if (COLOUR.matcher(css).find()) {
            p.add("page.css writes a colour; colours come only from the tokens, so dark mode switches them");
        }
        if (Pattern.compile("(^|[;{\\s])color\\s*:\\s*var\\(--c2\\)").matcher(css).find()) {
            p.add("page.css sets text in orange (--c2), under 4.5:1 on the light background");
        }
        Matcher fig = Pattern.compile("<figure\\b(.*?)</figure>", Pattern.DOTALL).matcher(page);
        while (fig.find()) {
            if (!fig.group(1).contains("<figcaption")) {
                p.add("a figure with no caption");
            }
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        Matcher id = Pattern.compile("\\sid=\"([^\"]+)\"").matcher(page);
        while (id.find()) {
            if (!ids.add(id.group(1))) {
                p.add("the id " + id.group(1) + " is used twice");
            }
        }
        Matcher href = Pattern.compile("\\s(?:href|src)=\"([^\"]+)\"").matcher(page);
        while (href.find()) {
            String h = href.group(1);
            if (h.startsWith("#")) {
                if (!ids.contains(h.substring(1))) {
                    p.add("a link to #" + h.substring(1) + ", which is not on the page");
                }
            } else if (h.startsWith("http")) {
                boolean ok = false;
                for (String host : LINK_HOSTS) {
                    ok |= h.startsWith(host);
                }
                if (!ok) {
                    p.add("an external link not on the allowlist: " + h);
                }
            } else if (!o.files.containsKey(h.replaceAll("[?#].*", ""))) {
                p.add("a link to " + h + ", which the build does not make");
            }
        }
        budget(p, o, "index.html", PAGE_BUDGET);
        budget(p, o, "assets/page.js", JS_BUDGET);
        budget(p, o, "data/results.json", RESULTS_BUDGET);
        budget(p, o, "data/results.csv", CSV_BUDGET);
        int man = o.files.get("data/manifest.json").length + o.files.get("data/SHA256SUMS").length
                + o.files.get("data/MANIFEST.md").length;
        if (man > MANIFEST_BUDGET) {
            p.add("the manifest files are " + man + " bytes, over their budget of " + MANIFEST_BUDGET);
        }
        return p;
    }

    private static void budget(List<String> p, Output o, String path, int limit) {
        int n = o.files.get(path).length;
        if (n > limit) {
            p.add(path + " is " + n + " bytes, over its budget of " + limit);
        }
    }

    /** A fixture build is never written into the website's working tree, so made-up numbers cannot reach it. */
    static void refuseFixtureIntoWebsite(SiteData in, Path out) {
        for (Path part : out) {
            if (in.fixture() && part.toString().equals("jbondurant.github.io")) {
                throw new IllegalArgumentException("a fixture build is never written into the website's tree: " + out);
            }
        }
    }

    static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /**
     * java -cp build/classes SiteBuild --inputs runs/site --site site --index ../jbondurant.github.io/index.html
     *     --version V --date YYYY-MM-DD --commit SHA --out DIR [--check] [--draft DIR]
     *
     * --site is the directory holding template.html, page.css and page.js. --out receives
     * card-counting/'s files; with --check nothing is written, and the build is compared byte for
     * byte with what --out already holds (PLAN_PAGE.md 3.4, depth 1, step 6). A fixture build is
     * refused anywhere inside the website's working tree, so made-up numbers cannot be copied there.
     * --draft DIR writes a page that fails its checks to DIR, for a person to look at, and still
     * exits with failure; --out is written only by a build that passes.
     */
    public static void main(String[] args) throws IOException {
        Map<String, String> a = new TreeMap<>();
        boolean checkOnly = false;
        for (int k = 0; k < args.length; k++) {
            if (args[k].equals("--check")) {
                checkOnly = true;
            } else if (args[k].startsWith("--") && k + 1 < args.length) {
                a.put(args[k].substring(2), args[++k]);
            } else {
                throw new IllegalArgumentException("unknown argument " + args[k]);
            }
        }
        for (String k : List.of("inputs", "site", "index", "version", "date", "commit", "out")) {
            if (!a.containsKey(k)) {
                System.err.println("usage: SiteBuild --inputs DIR --site DIR --index INDEX.html --version V "
                        + "--date YYYY-MM-DD --commit SHA --out DIR [--check]");
                System.exit(2);
            }
        }
        Path site = Paths.get(a.get("site"));
        SiteData in = SiteData.read(Paths.get(a.get("inputs")));
        Output o = build(in, read(site.resolve("template.html")), read(site.resolve("page.css")),
                read(site.resolve("page.js")), tokens(read(Paths.get(a.get("index")))), a.get("version"), a.get("date"),
                a.get("commit"));
        Path out = Paths.get(a.get("out")).toAbsolutePath().normalize();
        refuseFixtureIntoWebsite(in, out);
        if (a.containsKey("draft")) {
            refuseFixtureIntoWebsite(in, Paths.get(a.get("draft")).toAbsolutePath().normalize());
        }
        if (checkOnly) {
            List<String> diff = new ArrayList<>();
            for (Map.Entry<String, byte[]> e : o.files.entrySet()) {
                Path f = out.resolve(e.getKey());
                if (!Files.exists(f) || !java.util.Arrays.equals(Files.readAllBytes(f), e.getValue())) {
                    diff.add(e.getKey());
                }
            }
            System.out.println(diff.isEmpty() ? "SiteBuild --check: every file matches a fresh build"
                    : "SiteBuild --check: differs from a fresh build: " + diff);
            System.exit(diff.isEmpty() && o.problems.isEmpty() ? 0 : 1);
        }
        if (!o.problems.isEmpty() && a.containsKey("draft")) {
            System.err.println("SiteBuild: DRAFT written to " + a.get("draft") + " for inspection; it fails its checks:");
            for (String p : o.problems) {
                System.err.println("  " + p);
            }
            for (Map.Entry<String, byte[]> e : o.files.entrySet()) {
                Path f = Paths.get(a.get("draft")).resolve(e.getKey());
                Files.createDirectories(f.getParent());
                Files.write(f, e.getValue());
            }
            System.exit(1);
        }
        if (!o.problems.isEmpty()) {
            System.err.println("SiteBuild: the page fails its checks; nothing written:");
            for (String p : o.problems) {
                System.err.println("  " + p);
            }
            System.exit(1);
        }
        for (Map.Entry<String, byte[]> e : o.files.entrySet()) {
            Path f = out.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.write(f, e.getValue());
        }
        System.out.printf("SiteBuild: wrote %d files to %s (page %,d bytes, results.json %,d)%s%n", o.files.size(), out,
                o.files.get("index.html").length, o.files.get("data/results.json").length,
                in.fixture() ? "; FIXTURE: every figure is made up" : "");
    }
}
