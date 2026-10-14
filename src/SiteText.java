import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the page prints a figure, and the check that no number on the page was typed by hand
 * (PLAN_PAGE.md 2.4, "No typed numbers").
 *
 * <h2>Formats</h2>
 * A figure is printed by its format: {@code sN} signed with N decimals (+0.4717, −0.0760);
 * {@code dN} N decimals, a minus only when negative; {@code aN} the absolute value, for a sentence
 * whose verb carries the sign ("wins about 0.47%"); {@code n} a whole number with thousands
 * separators (60,743); {@code ciN} the 95% interval, signed; {@code seN} the standard error;
 * {@code pctN} a proportion as a percentage; {@code usd} whole dollars; {@code e} a power of ten
 * (1e-12); and {@code w:YES:NO}, a word chosen by the sign ("wins" or "loses"). Negative numbers
 * use the true minus sign, U+2212, as the plan's text does. Rounding is Java's %f, the same as the
 * reports', so a figure prints the same digits here as in the report it came from.
 *
 * <h2>The check</h2>
 * After the page is rendered, every digit in its text must be accounted for. It may sit in an
 * element that says where it came from, and then the element's whole text must be exactly what
 * that source gives: data-k (a figure, printed in its format from results.json), data-name (a
 * display name from results.json's names, such as "Revere 14"), data-s (a string the build
 * computed, such as a commit or the manifest's hash), data-tick (an axis label SiteSvg wrote,
 * holding its own value) or data-q (a run of a paragraph's words committed before its figures were
 * known, which must be the table's run exactly: SiteBuild.paragraph). Otherwise it must match the
 * allowlist below, kept short and in code.
 * Text inside script and style is not page text and is skipped; text inside code is allowed
 * digits, since commands hold them, but never a decimal figure.
 */
final class SiteText {

    private SiteText() {
    }

    static final char MINUS = '−';

    /** The most decimals any format prints; results.json's compact grid relies on it (SiteData.compact). */
    static final int MAX_PLACES = 4;

    private static final Pattern FMT = Pattern.compile("(s|d|a|ci|se|pct)[0-" + MAX_PLACES + "]|n|usd|e|w:[^:|}]+:[^:|}]+");

    static void checkFormat(String fmt, String key) {
        if (fmt == null || !FMT.matcher(fmt).matches()) {
            throw new IllegalArgumentException(key + ": unknown format \"" + fmt + "\"");
        }
    }

    private static String fixed(double x, int places) {
        String s = String.format(Locale.ROOT, "%." + places + "f", x);
        if (s.startsWith("-")) {
            s = s.substring(1);
            // A negative value that rounds to zero prints as zero, with no sign.
            return s.matches("[0.]*") ? s : MINUS + s;
        }
        return s;
    }

    private static String signed(double x, int places) {
        String s = fixed(x, places);
        if (s.charAt(0) == MINUS || s.matches("[0.]*")) {
            return s;
        }
        return "+" + s;
    }

    static String grouped(long n) {
        String s = String.format(Locale.ROOT, "%,d", Math.abs(n));
        return n < 0 ? MINUS + s : s;
    }

    static String format(SiteData.Figure f, String fmt) {
        checkFormat(fmt, f.key);
        if (fmt.startsWith("w:")) {
            String[] w = fmt.split(":");
            if (f.value == 0) {
                throw new IllegalArgumentException(f.key + " is exactly zero, so neither \"" + w[1] + "\" nor \""
                        + w[2] + "\" is true of it");
            }
            return f.value > 0 ? w[1] : w[2];
        }
        if (fmt.equals("n") || fmt.equals("usd")) {
            long r = Math.round(f.value);
            if (fmt.equals("n") && Math.abs(f.value - r) > 1e-9) {
                throw new IllegalArgumentException(f.key + " = " + f.value + " is not a whole number, so format n is wrong");
            }
            if (fmt.equals("usd")) {
                String s = String.format(Locale.ROOT, "%,d", Math.abs(r));
                return r < 0 ? MINUS + "$" + s : "$" + s;
            }
            return grouped(r);
        }
        if (fmt.equals("e")) {
            double l = Math.log10(Math.abs(f.value));
            if (f.value <= 0 || Math.abs(l - Math.round(l)) > 1e-12) {
                throw new IllegalArgumentException(f.key + " = " + f.value + " is not a power of ten, so format e is wrong");
            }
            return "1e" + (Math.round(l) < 0 ? "-" : "") + Math.abs(Math.round(l));
        }
        int p = fmt.charAt(fmt.length() - 1) - '0';
        String kind = fmt.substring(0, fmt.length() - 1);
        switch (kind) {
            case "s": return signed(f.value, p);
            case "d": return fixed(f.value, p);
            case "a": return fixed(Math.abs(f.value), p);
            case "pct": return fixed(100 * f.value, p);
            case "se":
                if (Double.isNaN(f.se)) {
                    throw new IllegalArgumentException(f.key + " has no SE to print");
                }
                return fixed(f.se, p);
            case "ci":
                if (!f.hasInterval()) {
                    throw new IllegalArgumentException(f.key + " has no interval to print");
                }
                return "[" + signed(f.lo, p) + ", " + signed(f.hi, p) + "]";
            default:
                throw new IllegalArgumentException(f.key + ": unknown format " + fmt);
        }
    }

    /**
     * A field of the rows table as text: a whole number as is, a string as is, true or false as the
     * template's two words (words = "YES|NO").
     */
    static String field(Object v, String words) {
        if (v instanceof Boolean) {
            if (words == null) {
                throw new IllegalArgumentException("a true-or-false field needs two words to print");
            }
            String[] w = words.split("\\|");
            return (Boolean) v ? w[0] : w[1];
        }
        if (v instanceof Long || v instanceof Integer) {
            return String.valueOf(v);
        }
        if (v instanceof String) {
            return (String) v;
        }
        throw new IllegalArgumentException("a rows field that is " + v);
    }

    /** Entry i of an input's table. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> entry(SiteData d, String table, int i) {
        Object t = d.tables.get(table);
        if (!(t instanceof List) || i < 0 || i >= ((List<?>) t).size()) {
            throw new IllegalArgumentException("no entry " + i + " in table " + table);
        }
        return (Map<String, Object>) ((List<?>) t).get(i);
    }

    /**
     * A table cell as page text: a number in the format given (SiteText's formats, as a figure is
     * printed), a whole number without one, a true-or-false field by its two words, a string as it
     * is, a list its items joined by commas. A number that is not whole needs a format.
     */
    static String cell(Object v, String fmt, String words) {
        if (v instanceof Number && fmt != null) {
            SiteData.Figure f = new SiteData.Figure("table", ((Number) v).doubleValue(), Double.NaN, Double.NaN, Double.NaN,
                    fmt, "table", "", "", "main", 1, false, null);
            return format(f, fmt);
        }
        if (v instanceof Number) {
            double x = ((Number) v).doubleValue();
            if (x != Math.rint(x)) {
                throw new IllegalArgumentException("a table cell " + v + " that is not whole needs a format");
            }
            return grouped(Math.round(x));
        }
        if (v instanceof List) {
            List<String> out = new java.util.ArrayList<>();
            for (Object x : (List<?>) v) {
                out.add(String.valueOf(x));
            }
            return out.isEmpty() ? "none" : String.join(", ", out);
        }
        return field(v, words);
    }

    /** A number as an axis label: as few decimals as the step between ticks needs. */
    static String tick(double x, int places) {
        return fixed(x, places);
    }

    private static final String MONTHS = "(January|February|March|April|May|June|July|August|September|October|"
            + "November|December)";

    /**
     * The digits page text may hold without a source, each for a reason that is not a result:
     * the name of the hash (SHA-256, SHA256SUMS); a date in words (a correction's date, "since
     * August 2026" in the AI statement), which says when, not what was found; and the Java version a
     * reader needs to rebuild the classes; and "soft 17", the name of a rule, the same in every game
     * (whether the dealer hits it comes from the game block). Nothing else. A new entry needs a reason of the same
     * kind, written here; the list is never edited for one run.
     */
    static final List<Pattern> ALLOWED = List.of(
            Pattern.compile("SHA-?256(SUMS)?"),
            Pattern.compile("\\b\\d{1,2} " + MONTHS + " \\d{4}\\b"),
            Pattern.compile("\\b" + MONTHS + " \\d{4}\\b"),
            Pattern.compile("\\bJava \\d{2}\\b"),
            Pattern.compile("\\bsoft 17\\b"));

    /** A figure-like number: what may never sit in the template's prose outside a placeholder. */
    static final Pattern TYPED_FIGURE = Pattern.compile("[+\u2212-]?\\d+\\.\\d{2,}");

    private static final Set<String> VOID = Set.of("area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "source", "track", "wbr");
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'>]+))?");
    private static final Pattern DIGIT = Pattern.compile("\\d");

    /** An open element while the page is walked. */
    static final class Element {
        final String name;
        final Map<String, String> attrs;
        final StringBuilder text = new StringBuilder();
        /** All the text inside, for the search-file label. */
        final StringBuilder all = new StringBuilder();

        Element(String name, Map<String, String> attrs) {
            this.name = name;
            this.attrs = attrs;
        }

        boolean sourced() {
            return attrs.containsKey("data-k") || attrs.containsKey("data-name") || attrs.containsKey("data-s")
                    || attrs.containsKey("data-tick") || attrs.containsKey("data-t") || attrs.containsKey("data-q");
        }
    }

    static String decode(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder b = new StringBuilder();
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-z]+);").matcher(s);
        int last = 0;
        while (m.find()) {
            b.append(s, last, m.start());
            String e = m.group(1);
            if (e.startsWith("#x")) {
                b.appendCodePoint(Integer.parseInt(e.substring(2), 16));
            } else if (e.startsWith("#")) {
                b.appendCodePoint(Integer.parseInt(e.substring(1)));
            } else {
                switch (e) {
                    case "amp": b.append('&'); break;
                    case "lt": b.append('<'); break;
                    case "gt": b.append('>'); break;
                    case "quot": b.append('"'); break;
                    case "apos": b.append('\''); break;
                    case "nbsp": b.append('\u00a0'); break;
                    default: b.append(m.group());
                }
            }
            last = m.end();
        }
        return b.append(s.substring(last)).toString();
    }

    static Map<String, String> attributes(String tagBody) {
        Map<String, String> a = new HashMap<>();
        int sp = 0;
        while (sp < tagBody.length() && !Character.isWhitespace(tagBody.charAt(sp))) {
            sp++;
        }
        Matcher m = ATTR.matcher(tagBody.substring(sp));
        while (m.find()) {
            String v = m.group(2);
            if (v != null && (v.startsWith("\"") || v.startsWith("'"))) {
                v = v.substring(1, v.length() - 1);
            }
            a.put(m.group(1), v == null ? "" : decode(v));
        }
        return a;
    }

    /** The end of a tag that starts at lt, skipping '>' inside quoted attribute values. */
    static int tagEnd(String html, int lt) {
        char quote = 0;
        for (int k = lt + 1; k < html.length(); k++) {
            char c = html.charAt(k);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return k;
            }
        }
        throw new IllegalArgumentException("a tag at offset " + lt + " is never closed");
    }

    /**
     * Walks the rendered page and returns every number it cannot account for, every sourced
     * element whose text is not what its source gives, and every search-file figure printed
     * outside a data-file="search" element. An empty list means the page passes.
     */
    static List<String> checkPage(String html, SiteData d, Map<String, String> strings) {
        List<String> problems = new ArrayList<>();
        Deque<Element> stack = new ArrayDeque<>();
        int i = 0;
        while (i < html.length()) {
            int lt = html.indexOf('<', i);
            String raw = html.substring(i, lt < 0 ? html.length() : lt);
            if (!raw.isEmpty()) {
                text(decode(raw), stack, problems);
            }
            if (lt < 0) {
                break;
            }
            if (html.startsWith("<!--", lt)) {
                int end = html.indexOf("-->", lt);
                if (end < 0) {
                    problems.add("an HTML comment is never closed");
                    break;
                }
                i = end + 3;
                continue;
            }
            int gt = tagEnd(html, lt);
            String body = html.substring(lt + 1, gt).trim();
            i = gt + 1;
            if (body.startsWith("!")) {
                continue;
            }
            if (body.startsWith("/")) {
                String name = body.substring(1).trim().toLowerCase(Locale.ROOT);
                boolean found = false;
                for (Element e : stack) {
                    if (e.name.equals(name)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    problems.add("</" + name + "> closes nothing");
                    continue;
                }
                while (true) {
                    Element e = stack.pop();
                    if (!e.name.equals(name)) {
                        problems.add("<" + e.name + "> is not closed before </" + name + ">");
                    }
                    close(e, stack, d, strings, problems);
                    if (e.name.equals(name)) {
                        break;
                    }
                }
                continue;
            }
            String name = body.split("[\\s/]", 2)[0].toLowerCase(Locale.ROOT);
            if (name.equals("script") || name.equals("style")) {
                int end = html.indexOf("</" + name, i);
                if (end < 0) {
                    problems.add("<" + name + "> is never closed");
                    break;
                }
                i = tagEnd(html, end) + 1;
                continue;
            }
            if (VOID.contains(name) || body.endsWith("/")) {
                continue;
            }
            stack.push(new Element(name, attributes(body)));
        }
        if (!stack.isEmpty()) {
            problems.add("elements left open at the end: " + stack.size() + " (innermost <" + stack.peek().name + ">)");
        }
        return problems;
    }

    private static void text(String t, Deque<Element> stack, List<String> problems) {
        boolean sourced = false;
        boolean code = false;
        for (Element e : stack) {
            if (e.sourced()) {
                e.text.append(t);
                sourced = true;
            }
            if (e.attrs.containsKey("data-file")) {
                e.all.append(t);
            }
            if (e.name.equals("code") || e.name.equals("pre")) {
                code = true;
            }
        }
        if (sourced || !DIGIT.matcher(t).find()) {
            return;
        }
        if (code) {
            Matcher m = TYPED_FIGURE.matcher(t);
            if (m.find()) {
                problems.add("a figure typed inside code: \"" + m.group() + "\" in \"" + t.trim() + "\"");
            }
            return;
        }
        String rest = t;
        for (Pattern p : ALLOWED) {
            rest = p.matcher(rest).replaceAll(" ");
        }
        Matcher m = DIGIT.matcher(rest);
        if (m.find()) {
            String where = t.trim();
            problems.add("a number with no source: \"" + (where.length() > 80 ? where.substring(0, 80) + "…" : where)
                    + "\" in <" + (stack.isEmpty() ? "?" : stack.peek().name) + ">");
        }
    }

    /** The words a data-file="search" element must print, so the label is text a reader sees, not a style. */
    static final String SEARCH_LABEL = "search file";

    private static void close(Element e, Deque<Element> stack, SiteData d, Map<String, String> strings,
                              List<String> problems) {
        if ("search".equals(e.attrs.get("data-file")) && !e.all.toString().contains(SEARCH_LABEL)) {
            problems.add("a data-file=\"search\" element that does not say \"" + SEARCH_LABEL + "\" in its text");
        }
        String got = e.text.toString();
        if (e.attrs.containsKey("data-k")) {
            String k = e.attrs.get("data-k");
            SiteData.Figure f = d.resolveOrNull(k);
            if (f == null) {
                problems.add("data-k=\"" + k + "\" is not in results.json");
                return;
            }
            String fmt = e.attrs.getOrDefault("data-f", f.fmt);
            String want;
            try {
                want = format(f, fmt);
            } catch (IllegalArgumentException x) {
                problems.add(x.getMessage());
                return;
            }
            if (!got.equals(want)) {
                problems.add(k + " is printed \"" + got + "\" but results.json gives \"" + want + "\" (" + fmt + ")");
            }
            if (f.file.equals("search") && !inSearch(e, stack)) {
                problems.add(k + " comes from the search file but is printed outside a data-file=\"search\" element");
            }
        } else if (e.attrs.containsKey("data-name")) {
            String id = e.attrs.get("data-name");
            String want = d.names.get(id);
            if (want == null || !want.equals(got)) {
                problems.add("data-name=\"" + id + "\" prints \"" + got + "\" but results.json names it \"" + want + "\"");
            }
        } else if (e.attrs.containsKey("data-s")) {
            String k = e.attrs.get("data-s");
            String want = strings.get(k);
            if (want == null || !want.equals(got)) {
                problems.add("data-s=\"" + k + "\" prints \"" + got + "\" but the build gives \"" + want + "\"");
            }
        } else if (e.attrs.containsKey("data-t")) {
            String[] p = e.attrs.get("data-t").split("/");
            String want;
            try {
                want = p[0].equals("rows") ? field(SiteBuild.row(d, p[1]).get(p[2]), e.attrs.get("data-w"))
                        : cell(entry(d, p[0], Integer.parseInt(p[1])).get(p[2]), e.attrs.get("data-f"), e.attrs.get("data-w"));
            } catch (RuntimeException x) {
                problems.add("data-t=\"" + e.attrs.get("data-t") + "\": " + x.getMessage());
                return;
            }
            if (!want.equals(got)) {
                problems.add("data-t=\"" + e.attrs.get("data-t") + "\" prints \"" + got + "\" but the rows table gives \"" + want + "\"");
            }
        } else if (e.attrs.containsKey("data-q")) {
            String want;
            try {
                want = SiteBuild.quotedWords(d, e.attrs.get("data-q"));
            } catch (RuntimeException x) {
                problems.add("data-q=\"" + e.attrs.get("data-q") + "\": " + x.getMessage());
                return;
            }
            if (!want.equals(got)) {
                problems.add("data-q=\"" + e.attrs.get("data-q") + "\" prints \"" + got + "\" but the committed words are \""
                        + want + "\"");
            }
        } else if (e.attrs.containsKey("data-tick")) {
            if (!e.attrs.get("data-tick").equals(got) || !got.matches("[" + MINUS + "]?\\d+(\\.\\d+)?%?")) {
                problems.add("an axis label \"" + got + "\" that is not its own data-tick value");
            }
        }
    }

    private static boolean inSearch(Element e, Deque<Element> stack) {
        if ("search".equals(e.attrs.get("data-file"))) {
            return true;
        }
        for (Element a : stack) {
            if ("search".equals(a.attrs.get("data-file"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The plan's rule for the template's prose: no figure-like number outside a placeholder. The
     * placeholders are taken out first; what remains is prose and markup, and any match is a
     * number someone typed.
     */
    static List<String> checkTemplate(String template) {
        List<String> problems = new ArrayList<>();
        String prose = template.replaceAll("\\{\\{[^}]*\\}\\}", " ");
        Matcher m = TYPED_FIGURE.matcher(prose);
        while (m.find()) {
            int a = Math.max(0, m.start() - 30);
            int b = Math.min(prose.length(), m.end() + 30);
            problems.add("a typed figure \"" + m.group() + "\" in the template: …" + prose.substring(a, b).replace('\n', ' ') + "…");
        }
        return problems;
    }
}
