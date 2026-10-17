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
 *   published count with the highest full.80.s8 figure, {@code @least} the lowest; and
 *   {@code full.70.s8.=full.80.s8.@best} is the same count's figure at another key.</li>
 *   <li>{@code {{name:KEY}}}, {@code {{names:KEY}}}: the display name of a key's row, or the names
 *   of a set of rows ({@code @topN}, {@code @all} for every published row, {@code @every} for every
 *   row) as a list.</li>
 *   <li>{@code {{s:NAME}}}: a string the build computed (version, date, the manifest's hash, a
 *   commit), in a data-s span.</li>
 *   <li>{@code {{guard: …}}}: a claim guard (SiteGuards); it prints nothing, and the build stops if
 *   it fails. {@code {{pairs:ranked|KEYS}}} and {@code {{pairs:unseparated|KEYS}}} print the pair
 *   lists of PLAN_PAGE.md 5a.</li>
 *   <li>{@code {{#if has:PREFIX}} … {{else}} … {{/if}}}: a section shown only when its data exists;
 *   the else branch says "Not yet computed". Clauses join with " and ": has:, lacks:, and
 *   any:TABLE|FILTER or none:TABLE|FILTER for an input table's entries (meets reads a filter).</li>
 *   <li>Blocks: {@code {{dots:ID …}}} a chart-table, {@code {{lines:ID …}}} an SVG panel,
 *   {@code {{rows:ID …}}} a sortable table of every count, {@code {{table:NAME …}}} an input's
 *   table, {@code {{fan:TABLE …}}} the risk fan, {@code {{scatter:ID …}}} a table's entries as points
 *   against the line y = x (see the methods below).</li>
 *   <li>{@code {{count:TABLE|FILTER}}}: how many entries of an input's table meet a filter, a figure
 *   computed here on the inputs, as a range is; {@code {{cell:TABLE|FILTER|FIELD}}}: a field of the
 *   first entry that meets it.</li>
 *   <li>{@code {{#view NAME}} … {{#body}} … {{/view}}}: a figure the reader switches among cut cards,
 *   spreads and wonging, every view rendered into assets/views/ and the default held in the page
 *   (views).</li>
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

    /**
     * The page's budgets in bytes (PLAN_PAGE.md 2.5, PLAN_COMPUTE.md section 0). The page's and
     * results.json's were raised on 17 October, when the first build on the real inputs measured
     * 213,078 and 1,581,573 bytes against the plan's estimates of 200 KB and 500 KB.
     */
    static final int PAGE_BUDGET = 256 * 1024;
    static final int JS_BUDGET = 12 * 1024;
    static final int RESULTS_BUDGET = 2560 * 1024;
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

    /**
     * {{dots:ID …}}: a chart-table (SiteSvg.dots). A row line draws one row; an each line one row
     * for every row its first key's alias names that has every figure the line names.
     */
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
                        if (l[0].equals("each") && !ks.stream().allMatch(d::has)) {
                            // An each line draws the rows that have every figure it names, as a rows
                            // table lists those that have its order figure: a registered count scored
                            // at first decisions but not at full play (F1 flat) is not drawn half.
                            continue;
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

    /**
     * {{scatter:ID …}}: an input table's entries as points, one field against another on one shared
     * scale with the line y = x (SiteSvg.scatter), for the published figures set beside this study's
     * (PLAN_PAGE.md section 9). Lines: table, where (a filter, as meets reads it), x and y (the
     * fields, each a number or a number as a source printed it, such as ".76"), label (the field that
     * names a point), title, desc, xname and yname. An entry without a number in both is not drawn.
     */
    static String scatter(String id, String body, SiteData d) {
        Map<String, String> a = new LinkedHashMap<>();
        for (String[] l : lines(body)) {
            if (!List.of("table", "where", "x", "y", "label", "title", "desc", "xname", "yname").contains(l[0])) {
                throw new IllegalArgumentException("scatter:" + id + ": unknown line \"" + l[0] + "\"");
            }
            a.put(l[0], l[1]);
        }
        for (String k : List.of("table", "x", "y", "label", "title", "desc", "xname", "yname")) {
            if (!a.containsKey(k)) {
                throw new IllegalArgumentException("scatter:" + id + " has no " + k + " line");
            }
        }
        List<Integer> at = matching(d, a.get("table"), a.get("where"));
        return SiteSvg.scatter(id, a, at, d);
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

    /**
     * {{table:NAME …}}: an input's table (SiteData.tables, such as sim_z, sim_planted, risk_ruin,
     * literature_claims, ledger) as an HTML table, every cell a data-t span the page check holds to
     * that table's entry (TABLE/INDEX/FIELD; SiteText.cell). Lines: head (the headings), one col
     * per column, {@code FIELD}, {@code FIELD|FMT} for a number in one of SiteText's formats, or
     * {@code FIELD|YES|NO} for a true-or-false field; and optionally where FIELD=VALUE to keep the
     * entries whose field reads so. A field the entry lacks, or null, shows a dash.
     */
    static String tableBlock(String name, String body, SiteData d) {
        List<String> head = null;
        List<List<String>> cols = new ArrayList<>();
        String where = null;
        String id = name;
        for (String[] l : lines(body)) {
            switch (l[0]) {
                case "head": head = bars(l[1]); break;
                case "col": cols.add(bars(l[1])); break;
                case "where": where = l[1]; break;
                case "id": id = l[1]; break;
                default: throw new IllegalArgumentException("table:" + name + ": unknown line \"" + l[0] + "\"");
            }
        }
        Object t = d.tables.get(name);
        if (!(t instanceof List)) {
            throw new IllegalArgumentException("table:" + name + ": the inputs hold no such table");
        }
        if (head == null || head.size() != cols.size()) {
            throw new IllegalArgumentException("table:" + name + " needs a head with one heading per col");
        }
        List<?> entries = (List<?>) t;
        StringBuilder b = new StringBuilder("<div class=\"tw\"><table class=\"data\" id=\"").append(SiteSvg.esc(id))
                .append("\">\n<thead><tr>");
        for (String h : head) {
            b.append("<th scope=\"col\">").append(h).append("</th>");
        }
        b.append("</tr></thead>\n<tbody>\n");
        for (int i = 0; i < entries.size(); i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> e = (Map<String, Object>) entries.get(i);
            if (!meets(e, where)) {
                continue;
            }
            b.append("<tr>");
            for (List<String> c : cols) {
                Object v = e.get(c.get(0));
                String fmt = c.size() == 2 ? c.get(1) : null;
                String words = c.size() == 3 ? c.get(1) + "|" + c.get(2) : null;
                boolean num = v instanceof Number;
                b.append(num ? "<td class=\"num\">" : "<td>");
                if (v == null) {
                    b.append("–");
                } else {
                    b.append("<span data-t=\"").append(SiteSvg.esc(name + "/" + i + "/" + c.get(0))).append('"');
                    if (fmt != null) {
                        b.append(" data-f=\"").append(SiteSvg.esc(fmt)).append('"');
                    }
                    if (words != null) {
                        b.append(" data-w=\"").append(SiteSvg.esc(words)).append('"');
                    }
                    b.append('>').append(SiteSvg.esc(SiteText.cell(v, fmt, words))).append("</span>");
                }
                b.append("</td>");
            }
            b.append("</tr>\n");
        }
        return b.append("</tbody></table></div>").toString();
    }

    /**
     * Whether a table entry meets a filter: conditions joined by ";", each FIELD=VALUE (the field
     * prints as VALUE), FIELD!=VALUE, FIELD^=PREFIX (it starts so) or FIELD!^=PREFIX; VALUE null
     * stands for a field that is null or absent. A filter left out is met by every entry.
     */
    static boolean meets(Map<String, Object> e, String filter) {
        if (filter == null) {
            return true;
        }
        for (String c : filter.split(";")) {
            String t = c.trim();
            if (t.isEmpty()) {
                continue;
            }
            String op = t.contains("!^=") ? "!^=" : t.contains("^=") ? "^=" : t.contains("!=") ? "!=" : "=";
            int at = t.indexOf(op);
            if (at <= 0) {
                throw new IllegalArgumentException("\"" + t + "\" is not a condition FIELD=VALUE, FIELD!=VALUE, FIELD^=PREFIX "
                        + "or FIELD!^=PREFIX");
            }
            Object v = e.get(t.substring(0, at).trim());
            String want = t.substring(at + op.length()).trim();
            String got = v == null ? "null" : String.valueOf(v);
            boolean ok = op.endsWith("^=") ? (v != null && got.startsWith(want)) == op.equals("^=")
                    : op.equals("=") == got.equals(want);
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** The entries of an input's table that meet a filter, by index; a table the inputs lack has none. */
    @SuppressWarnings("unchecked")
    static List<Integer> matching(SiteData d, String table, String filter) {
        List<Integer> out = new ArrayList<>();
        Object t = d.tables.get(table);
        if (t instanceof List) {
            List<?> l = (List<?>) t;
            for (int i = 0; i < l.size(); i++) {
                if (meets((Map<String, Object>) l.get(i), filter)) {
                    out.add(i);
                }
            }
        }
        return out;
    }

    /**
     * The key of a count figure: how many entries of a table meet a filter (count:TABLE|FILTER), a
     * figure no run prints, computed here on the inputs and written to results.json like a range, so
     * the page prints it from there. Its "at" names the table and the filter.
     */
    static String countKey(String spec) {
        String s = spec.replace("!^=", " not starts ").replace("^=", " starts ").replace("!=", " not ").replace("=", " is ").replace("|", " where ")
                .toLowerCase(java.util.Locale.ROOT);
        return "count." + s.replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    static String countAt(String spec) {
        int bar = spec.indexOf('|');
        return "the entries of " + (bar < 0 ? spec : spec.substring(0, bar) + " where " + spec.substring(bar + 1));
    }

    static String[] countSpec(String spec) {
        int bar = spec.indexOf('|');
        return new String[]{(bar < 0 ? spec : spec.substring(0, bar)).trim(), bar < 0 ? null : spec.substring(bar + 1).trim()};
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
            } else if (c.startsWith("has:") || c.startsWith("lacks:") || c.startsWith("any:") || c.startsWith("none:")) {
                // Clauses joined by " and ", each has:P1,P2 or lacks:P1,P2; a prefix may name a row by
                // rank or by another key's row (expand), and one that names no row is not had.
                holds = true;
                for (String clause : c.split(" and ")) {
                    String t = clause.trim();
                    if (t.startsWith("any:") || t.startsWith("none:")) {
                        // any:TABLE|FILTER holds when some entry of the table meets the filter; none: when none does.
                        String[] spec = countSpec(t.substring(t.indexOf(':') + 1));
                        holds &= matching(d, spec[0], spec[1]).isEmpty() == t.startsWith("none:");
                        continue;
                    }
                    boolean has = t.startsWith("has:");
                    if (!has && !t.startsWith("lacks:")) {
                        throw new IllegalArgumentException("unknown condition {{#if " + c + "}}");
                    }
                    for (String p : t.substring(has ? 4 : 6).split(",")) {
                        holds &= hasPattern(d, p.trim()) == has;
                    }
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

    // ---- views: one figure the reader switches among settings (PLAN_PAGE.md 5c) ----

    static final Pattern VIEW = Pattern.compile("\\{\\{#view ([a-z0-9]+)\\}\\}(.*?)\\{\\{#body\\}\\}(.*?)\\{\\{/view\\}\\}",
            Pattern.DOTALL);
    static final String VIEW_DIR = "assets/views/";
    static final String VIEW_MARK = "\u0000views:";
    static final List<String> AXES = List.of("pen", "spread", "wong");

    /** The page's template with its view blocks resolved, and the body each other view is rendered from. */
    static final class Views {
        String page;
        /** File (assets/views/NAME-VIEW.html) to the body it is rendered from, variables filled in. */
        final Map<String, String> bodies = new TreeMap<>();
        /** Block name to the file of its default view, which the page also holds. */
        final Map<String, String> defaults = new TreeMap<>();
    }

    /**
     * {{#view NAME}} HEADER {{#body}} BODY {{/view}}: one figure the reader can switch among the
     * cut card's places, the spreads and wonging off or on (PLAN_PAGE.md 5c). The page holds the
     * default view as static HTML, complete without JavaScript, under radio controls that only the
     * script shows; every other view the data has is rendered from the same body into
     * assets/views/NAME-PEN-SPREAD-WONG.html, a fragment page.js fetches when a control asks for it
     * and puts in place of the view shown. The script never computes or formats a figure: each
     * fragment is rendered here from results.json and passes the page's own checks (checkView).
     *
     * Header lines: pens and spreads (the choices offered, in order); default PEN SPREAD WONG; need
     * KEY… (the keys a view must have to be made) and need on KEY… (those a view with wonging on
     * must have besides); legend pen|spread|wong TEXT; label pen|spread|off|on TEXT, each option's
     * label (placeholders allowed); fail TEXT, what the script says if a view cannot be fetched.
     * In the body and the labels: {pen}, {spread}, {n} (s8's 8), {fd} and {full} (fdw and fullw
     * with wonging on), {wong} (off or on); {{#on}}…{{/on}} and {{#off}}…{{/off}} keep their words
     * only with wonging on or off. A body holds no guard, pair list, range, count or {{#if}}: those
     * are evaluated once on the inputs, for the page alone.
     */
    static Views views(String template, SiteData in) {
        Views v = new Views();
        Matcher m = VIEW.matcher(template);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            String body = m.group(3);
            for (String no : new String[]{"{{guard:", "{{pairs:", "{{range:", "{{count:", "{{#if", "{{#view"}) {
                if (body.contains(no)) {
                    throw new IllegalArgumentException("view " + name + ": its body holds " + no + "…}}, which a view cannot");
                }
            }
            Map<String, List<String>> h = new LinkedHashMap<>();
            for (String l : m.group(2).split("\n")) {
                String t = l.trim();
                if (t.isEmpty()) {
                    continue;
                }
                String[] w = t.split("\\s+", 3);
                String key = w[0].equals("legend") || w[0].equals("label") || (w[0].equals("need") && w.length > 1
                        && w[1].equals("on")) ? w[0] + " " + w[1] : w[0];
                String rest = key.contains(" ") ? (w.length > 2 ? w[2] : "") : t.substring(w[0].length()).trim();
                if (h.put(key, List.of(rest.split(key.startsWith("label") || key.startsWith("legend") || key.equals("fail")
                        ? "\n" : "\\s+"))) != null) {
                    throw new IllegalArgumentException("view " + name + ": two " + key + " lines");
                }
            }
            for (String k : List.of("pens", "spreads", "default", "need", "legend pen", "legend spread", "legend wong",
                    "label pen", "label spread", "label off", "label on", "fail")) {
                if (!h.containsKey(k)) {
                    throw new IllegalArgumentException("view " + name + " has no " + k + " line");
                }
            }
            List<String> made = new ArrayList<>();
            for (String pen : h.get("pens")) {
                for (String spread : h.get("spreads")) {
                    for (String wong : List.of("off", "on")) {
                        boolean ok = true;
                        List<String> need = new ArrayList<>(h.get("need"));
                        if (wong.equals("on")) {
                            need.addAll(h.getOrDefault("need on", List.of()));
                        }
                        for (String k : need) {
                            // A key itself, not a prefix (hasPrefix takes fd.80.s20.x as had once any fd cell is).
                            String key = fill(k, pen, spread, wong);
                            ok &= key.contains("@") || key.contains(".=") ? hasPattern(in, key) : in.has(key);
                        }
                        if (ok) {
                            String id = pen + "-" + spread + "-" + wong;
                            made.add(id);
                            v.bodies.put(VIEW_DIR + name + "-" + id + ".html", fill(body, pen, spread, wong));
                        }
                    }
                }
            }
            List<String> def = h.get("default");
            String defId = String.join("-", def);
            if (!made.contains(defId)) {
                throw new IllegalArgumentException("view " + name + ": the data has no default view " + defId);
            }
            v.defaults.put(name, VIEW_DIR + name + "-" + defId + ".html");
            m.appendReplacement(b, Matcher.quoteReplacement(viewBox(name, h, made, def)
                    + fill(body, def.get(0), def.get(1), def.get(2)) + "</div></div>"));
        }
        m.appendTail(b);
        v.page = b.toString();
        if (v.page.contains("{{#view") || v.page.contains("{{#body}}") || v.page.contains("{{/view}}")) {
            throw new IllegalArgumentException("a {{#view}} block is not closed");
        }
        return v;
    }

    /** A view's variables filled in, and its {{#on}} or {{#off}} words kept or dropped. */
    static String fill(String s, String pen, String spread, String wong) {
        boolean on = wong.equals("on");
        String t = s.replaceAll("(?s)\\{\\{#" + (on ? "off" : "on") + "\\}\\}.*?\\{\\{/" + (on ? "off" : "on") + "\\}\\}", "")
                .replace("{{#" + wong + "}}", "").replace("{{/" + wong + "}}", "");
        return t.replace("{pen}", pen).replace("{spread}", spread).replace("{n}", spread.substring(1))
                .replace("{fd}", on ? "fdw" : "fd").replace("{full}", on ? "fullw" : "full").replace("{wong}", wong);
    }

    /**
     * The opening of a view's box: the list of views made, where their fragments are, and the
     * controls, one radio group in a fieldset with its legend per axis, each option one the views
     * made use, the default's checked. The view shown follows in a div the script replaces.
     */
    static String viewBox(String name, Map<String, List<String>> h, List<String> made, List<String> def) {
        StringBuilder b = new StringBuilder("<div class=\"views\" id=\"views-").append(name).append("\" data-src=\"")
                .append(VIEW_DIR).append(name).append("-\" data-views=\"").append(String.join(" ", made)).append("\" data-v=\"")
                .append(VIEW_MARK).append(name).append("\u0000\" data-fail=\"").append(SiteSvg.esc(h.get("fail").get(0)))
                .append("\">\n<div class=\"controls\">");
        for (int a = 0; a < AXES.size(); a++) {
            String axis = AXES.get(a);
            List<String> options = axis.equals("pen") ? h.get("pens") : axis.equals("spread") ? h.get("spreads")
                    : List.of("off", "on");
            b.append("<fieldset><legend>").append(h.get("legend " + axis).get(0)).append("</legend>");
            for (String o : options) {
                boolean used = false;
                for (String id : made) {
                    used |= id.split("-")[a].equals(o);
                }
                if (!used) {
                    continue;
                }
                String label = axis.equals("wong") ? h.get("label " + o).get(0)
                        : fill(h.get("label " + axis).get(0), axis.equals("pen") ? o : def.get(0),
                        axis.equals("spread") ? o : def.get(1), def.get(2));
                b.append("<label><input type=\"radio\" name=\"").append(name).append('-').append(axis).append("\" data-axis=\"")
                        .append(axis).append("\" value=\"").append(o).append('"').append(o.equals(def.get(a)) ? " checked" : "")
                        .append("> ").append(label).append("</label>");
            }
            b.append("</fieldset>");
        }
        return b.append("<p class=\"vstatus\" role=\"status\"></p></div>\n<div class=\"view\" id=\"view-").append(name)
                .append("\" data-view=\"").append(String.join("-", def)).append("\">\n").toString();
    }

    /**
     * Whether the data has a figure or table under a prefix that may name its row by rank or by
     * another key's row (full.80.s8.@best, risk.ev_per100_usd.full.80.s8.=full.80.s8.@best): each
     * key it expands to is had; a pattern that names no row (no row has the figure ranked) is not.
     */
    static boolean hasPattern(SiteData d, String p) {
        if (!p.contains("@") && !p.contains(".=")) {
            return d.hasPrefix(p);
        }
        List<String> keys;
        try {
            keys = expand(p, d);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (String k : keys) {
            // The key itself, not a prefix of it: hi_opt_ii is not had because hi_opt_ii_ace is.
            if (!d.has(k)) {
                return false;
            }
        }
        return !keys.isEmpty();
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
            } else if (p.startsWith("count:")) {
                String spec = p.substring(6).trim();
                String key = countKey(spec);
                String[] s = countSpec(spec);
                if (!(in.tables.get(s[0]) instanceof List)) {
                    throw new IllegalArgumentException("{{count:" + spec + "}}: the inputs hold no table " + s[0]);
                }
                SiteData.Figure f = new SiteData.Figure(key, matching(in, s[0], s[1]).size(), Double.NaN, Double.NaN,
                        Double.NaN, "n", "computed by SiteBuild", countAt(spec), generator, "main", 1, false, null);
                SiteData.Figure was = in.figures.putIfAbsent(key, f);
                if (was != null && (!was.at.equals(f.at) || was.value != f.value)) {
                    throw new IllegalArgumentException("{{count:" + spec + "}} and another count share the key " + key);
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
        if (head.startsWith("table:")) {
            return tableBlock(head.substring(6).trim(), body, d);
        }
        if (head.startsWith("fan:")) {
            return SiteSvg.fan(head.substring(4).trim(), body, d);
        }
        if (head.startsWith("scatter:")) {
            return scatter(head.substring(8).trim(), body, d);
        }
        if (p.startsWith("count:")) {
            String spec = p.substring(6).trim();
            SiteData.Figure f = d.resolve(countKey(spec));
            if (!f.at.equals(countAt(spec))) {
                throw new IllegalArgumentException("the count " + spec + " in results.json is " + f.at);
            }
            return SiteSvg.fig(d, countKey(spec), "n");
        }
        if (p.startsWith("cell:")) {
            // cell:TABLE|FILTER|FIELD: that field of the first entry meeting the filter, as a data-t span.
            String[] q = p.substring(5).split("\\|");
            if (q.length != 3) {
                throw new IllegalArgumentException("cell: needs TABLE|FILTER|FIELD");
            }
            List<Integer> at = matching(d, q[0].trim(), q[1].trim());
            if (at.isEmpty()) {
                throw new IllegalArgumentException("no entry of " + q[0] + " meets " + q[1]);
            }
            String ref = q[0].trim() + "/" + at.get(0) + "/" + q[2].trim();
            Object v = SiteText.entry(d, q[0].trim(), at.get(0)).get(q[2].trim());
            return "<span data-t=\"" + SiteSvg.esc(ref) + "\">" + SiteSvg.esc(SiteText.cell(v, null, null)) + "</span>";
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
        if (p.startsWith("para:")) {
            return paragraph(p.substring(5).trim(), d);
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

    /** The table of committed paragraphs SiteCeiling writes, and the source of every data-q span. */
    static final String PARAGRAPHS = "ceiling_paragraphs";

    /**
     * A paragraph whose words were committed before its figures were known (the ceiling count's
     * result, CEILING_RESULT_PARAGRAPHS.md, picked by SiteCeiling): its words printed as they are,
     * each run between two brackets in a data-q span that the page check holds to the table's
     * words (SiteText), each bracket as the figure or name the table fills it with, a "%" after a
     * figure the paragraphs file prints one after. A paragraph a figure is missing for says it is
     * not yet computed and prints no bracket.
     */
    @SuppressWarnings("unchecked")
    static String paragraph(String cell, SiteData d) {
        Object all = d.tables.get(PARAGRAPHS);
        Map<String, Object> t = all == null ? null : (Map<String, Object>) ((Map<String, Object>) all).get(cell);
        if (t == null) {
            throw new IllegalArgumentException("no paragraph " + cell + " in the table " + PARAGRAPHS);
        }
        if (t.get("pending") != null) {
            return "<span class=\"pending\">Not yet computed: " + SiteSvg.esc((String) t.get("pending")) + ".</span>";
        }
        String words = (String) t.get("words");
        Map<String, Object> fill = (Map<String, Object>) t.get("fill");
        List<Object> percent = (List<Object>) t.get("percent_after");
        Matcher m = CeilingParagraphs.PLACEHOLDER.matcher(words);
        StringBuilder b = new StringBuilder();
        int at = 0;
        int n = 0;
        while (m.find()) {
            b.append(quoted(cell, n++, words.substring(at, m.start())));
            String f = (String) fill.get(m.group(1));
            if (f == null) {
                throw new IllegalArgumentException("paragraph " + cell + " has nothing to fill [" + m.group(1) + "] with");
            }
            b.append(f.startsWith("name:") ? SiteSvg.name(d, f.substring(5)) : SiteSvg.fig(d, f, null));
            if (percent.contains(m.group(1))) {
                b.append('%');
            }
            at = m.end();
        }
        b.append(quoted(cell, n, words.substring(at)));
        return b.toString();
    }

    private static String quoted(String cell, int n, String words) {
        return words.isEmpty() ? "" : "<span data-q=\"" + PARAGRAPHS + "/" + SiteSvg.esc(cell) + "/" + n + "\">"
                + SiteSvg.esc(words) + "</span>";
    }

    /** The n-th run of words between brackets of a committed paragraph, as the table holds it (data-q's source). */
    @SuppressWarnings("unchecked")
    static String quotedWords(SiteData d, String ref) {
        String[] p = ref.split("/");
        if (p.length != 3 || !p[0].equals(PARAGRAPHS)) {
            throw new IllegalArgumentException("data-q=\"" + ref + "\" names no committed paragraph");
        }
        Map<String, Object> t = (Map<String, Object>) ((Map<String, Object>) d.tables.get(PARAGRAPHS)).get(p[1]);
        String[] runs = CeilingParagraphs.PLACEHOLDER.split((String) t.get("words"), -1);
        return runs[Integer.parseInt(p[2])];
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
        Views views = new Views();

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
        Views views = views(conditionals(template, in), in);
        String t = views.page;
        List<String> failed = guards(t, in, generator);
        if (!failed.isEmpty()) {
            throw new IllegalArgumentException("claim guards that fail; rewrite these sentences by hand:\n  "
                    + String.join("\n  ", failed));
        }
        String results = in.write(version, date);
        SiteData d = SiteData.fromJson(results);
        Output o = new Output();
        o.data = d;
        o.views = views;
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
        Map<String, String> fragments = new TreeMap<>();
        for (Map.Entry<String, String> e : views.bodies.entrySet()) {
            fragments.put(e.getKey(), render(e.getValue(), d, strings, tokens, css));
        }
        for (String name : views.defaults.keySet()) {
            // The box's data-v, which the script puts on every fetch (?v=): the SHA-256 of its
            // fragments, so a fragment cached from another build is never shown beside this page.
            StringBuilder all = new StringBuilder();
            for (Map.Entry<String, String> f : fragments.entrySet()) {
                if (f.getKey().startsWith(VIEW_DIR + name + "-")) {
                    all.append(f.getKey()).append('\n').append(f.getValue());
                }
            }
            page = page.replace(VIEW_MARK + name + "\u0000", SiteData.sha256(utf8(all.toString())).substring(0, 16));
        }
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
        for (Map.Entry<String, String> f : fragments.entrySet()) {
            o.files.put(f.getKey(), utf8(f.getValue()));
        }
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

    /** A view fragment's budget: one figure, fetched when a control asks for it. */
    static final int VIEW_BUDGET = 120 * 1024;

    /** The finished page's checks, each a rule of PLAN_PAGE.md 2.4 to 2.6, and the same on every view fragment. */
    static List<String> check(Output o, String css) {
        List<String> p = new ArrayList<>();
        String page = o.text("index.html");
        if (o.data.fixture() && (!page.contains(FIXTURE_BANNER) || !page.contains("<title>" + FIXTURE_TITLE))) {
            p.add("a fixture build without its banner and title mark");
        }
        java.util.Set<String> pageIds = new java.util.HashSet<>();
        p.addAll(checkHtml(o, "", page, pageIds, java.util.Set.of()));
        if (COLOUR.matcher(css).find()) {
            p.add("page.css writes a colour; colours come only from the tokens, so dark mode switches them");
        }
        if (Pattern.compile("(^|[;{\\s])color\\s*:\\s*var\\(--c2\\)").matcher(css).find()) {
            p.add("page.css sets text in orange (--c2), under 4.5:1 on the light background");
        }
        p.addAll(checkViews(o, page, pageIds));
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

    /**
     * The checks a page or a view fragment must pass, each problem prefixed with where: every number
     * sourced (SiteText), no placeholder left, every SVG well-formed and coloured only by tokens,
     * every figure captioned, no id twice, every link going somewhere. ids receives the html's ids;
     * a fragment's ids must not be any of outside's (the page's own, outside the view it replaces),
     * and its links may go to those.
     */
    static List<String> checkHtml(Output o, String where, String html, java.util.Set<String> ids, java.util.Set<String> outside) {
        List<String> p = new ArrayList<>();
        for (String x : SiteText.checkPage(html, o.data, o.strings)) {
            p.add(where + x);
        }
        if (html.contains("{{")) {
            p.add(where + "a placeholder is left unresolved");
        }
        Matcher svg = Pattern.compile("<svg\\b.*?</svg>", Pattern.DOTALL).matcher(html);
        while (svg.find()) {
            String s = svg.group();
            try {
                javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                f.setNamespaceAware(true);
                f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(utf8(s)));
            } catch (Exception x) {
                p.add(where + "an SVG that does not parse as XML: " + x.getMessage());
            }
            if (COLOUR.matcher(s).find()) {
                p.add(where + "an SVG with a colour written into it rather than taken from the page's tokens");
            }
        }
        Matcher fig = Pattern.compile("<figure\\b(.*?)</figure>", Pattern.DOTALL).matcher(html);
        while (fig.find()) {
            if (!fig.group(1).contains("<figcaption")) {
                p.add(where + "a figure with no caption");
            }
        }
        Matcher id = Pattern.compile("\\sid=\"([^\"]+)\"").matcher(html);
        while (id.find()) {
            if (!ids.add(id.group(1))) {
                p.add(where + "the id " + id.group(1) + " is used twice");
            }
            if (outside.contains(id.group(1))) {
                p.add(where + "the id " + id.group(1) + " is also the page's, outside the view this fragment replaces");
            }
        }
        Matcher href = Pattern.compile("\\s(?:href|src)=\"([^\"]+)\"").matcher(html);
        while (href.find()) {
            String h = href.group(1);
            if (h.startsWith("#")) {
                if (!ids.contains(h.substring(1)) && !outside.contains(h.substring(1))) {
                    p.add(where + "a link to #" + h.substring(1) + ", which is not on the page");
                }
            } else if (h.startsWith("http")) {
                boolean ok = false;
                for (String host : LINK_HOSTS) {
                    ok |= h.startsWith(host);
                }
                if (!ok) {
                    p.add(where + "an external link not on the allowlist: " + h);
                }
            } else if (!o.files.containsKey(h.replaceAll("[?#].*", ""))) {
                p.add(where + "a link to " + h + ", which the build does not make");
            }
        }
        return p;
    }

    /**
     * Each view box (views): its list of views is its fragments, every one of which is built; the
     * view the page holds is its default fragment byte for byte, so the script can put it back; and
     * every fragment passes checkHtml with the page's ids outside the view as outside, and its budget.
     */
    static List<String> checkViews(Output o, String page, java.util.Set<String> pageIds) {
        List<String> p = new ArrayList<>();
        java.util.Set<String> listed = new java.util.TreeSet<>();
        for (String name : o.views.defaults.keySet()) {
            Matcher box = Pattern.compile("<div class=\"views\" id=\"views-" + name + "\" data-src=\"([^\"]*)\" data-views=\"([^\"]*)\"")
                    .matcher(page);
            if (!box.find()) {
                p.add("view " + name + ": the page has no box for it");
                continue;
            }
            for (String v : box.group(2).split(" ")) {
                listed.add(box.group(1) + v + ".html");
            }
            String def = o.text(o.views.defaults.get(name));
            Matcher shown = Pattern.compile("<div class=\"view\" id=\"view-" + name + "\" data-view=\"[^\"]*\">\n").matcher(page);
            if (!shown.find() || !page.startsWith(def + "</div></div>", shown.end())) {
                p.add("view " + name + ": the page does not hold its default view as " + o.views.defaults.get(name) + " has it");
                continue;
            }
            java.util.Set<String> inside = new java.util.HashSet<>();
            checkHtml(o, "", def, inside, java.util.Set.of());
            java.util.Set<String> outside = new java.util.HashSet<>(pageIds);
            outside.removeAll(inside);
            for (String f : o.files.keySet()) {
                if (f.startsWith(VIEW_DIR + name + "-")) {
                    p.addAll(checkHtml(o, f + ": ", o.text(f), new java.util.HashSet<>(), outside));
                    budget(p, o, f, VIEW_BUDGET);
                }
            }
        }
        java.util.Set<String> built = new java.util.TreeSet<>();
        for (String f : o.files.keySet()) {
            if (f.startsWith(VIEW_DIR)) {
                built.add(f);
            }
        }
        if (!built.equals(listed)) {
            p.add("the views the page lists are not the fragments built: listed " + listed.size() + ", built " + built.size());
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
