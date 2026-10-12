import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer for the site generator (PLAN_PAGE.md 2.4).
 *
 * The repo reads its catalog with bson's Document.parse, but Document cannot read an array at
 * the top level, and its writer prints numbers in its own extended form. The site's data files
 * must come out byte for byte the same on every build (two builds give the same bytes, PLAN_PAGE.md
 * 2.6) and must be read back exactly as written, so this class keeps both ends in the repo's own
 * code: objects become LinkedHashMaps in file order, arrays ArrayLists, numbers Doubles (or Longs
 * when they have no fraction or exponent), and a double is written in Java's shortest form that
 * reads back to the same double, so a write and a read give back the same bits.
 */
final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Parses one JSON value; anything after it but white space is refused. */
    static Object parse(String text) {
        Json p = new Json(text);
        p.space();
        Object v = p.value();
        p.space();
        if (p.i != p.s.length()) {
            throw p.error("text after the value");
        }
        return v;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " at offset " + i);
    }

    private void space() {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == '\t')) {
            i++;
        }
    }

    private Object value() {
        if (i >= s.length()) {
            throw error("end of text where a value was expected");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return word("true", Boolean.TRUE);
            case 'f': return word("false", Boolean.FALSE);
            case 'n': return word("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("unexpected character '" + c + "'");
        }
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) {
            throw error("expected " + w);
        }
        i += w.length();
        return v;
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        space();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            space();
            if (i >= s.length() || s.charAt(i) != '"') {
                throw error("expected a key");
            }
            String k = string();
            space();
            if (i >= s.length() || s.charAt(i) != ':') {
                throw error("expected ':'");
            }
            i++;
            space();
            if (m.containsKey(k)) {
                throw error("key \"" + k + "\" given twice");
            }
            m.put(k, value());
            space();
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            throw error("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++;
        space();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return a;
        }
        while (true) {
            space();
            a.add(value());
            space();
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return a;
            }
            throw error("expected ',' or ']'");
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) {
                throw error("unterminated string");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c < 0x20) {
                throw error("control character in a string");
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) {
                throw error("unterminated escape");
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) {
                        throw error("short \\u escape");
                    }
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default:
                    throw error("unknown escape \\" + e);
            }
        }
    }

    private Object number() {
        int start = i;
        if (s.charAt(i) == '-') {
            i++;
        }
        boolean whole = true;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                whole = false;
                i++;
            } else {
                break;
            }
        }
        String t = s.substring(start, i);
        try {
            if (whole) {
                return Long.parseLong(t);
            }
            return Double.parseDouble(t);
        } catch (NumberFormatException x) {
            throw error("bad number " + t);
        }
    }

    /**
     * Writes a value: objects one key to a line, arrays of plain values on one line. A double that
     * is NaN or infinite is refused, since JSON has no way to say it and a page figure never is one.
     */
    static String write(Object v) {
        return write(v, Integer.MAX_VALUE);
    }

    /**
     * Writes a value with everything at depth flatFrom or deeper on one line: results.json puts
     * each figure and each grid row on a line of its own, which keeps the file readable and within
     * its size budget.
     */
    static String write(Object v, int flatFrom) {
        StringBuilder b = new StringBuilder();
        write(b, v, flatFrom <= 0 ? null : "", 0, flatFrom);
        return b.append('\n').toString();
    }

    /** One value on one line, for a CSV cell or a short attribute. */
    static String compact(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, null, 0, 0);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v, String indent, int depth, int flatFrom) {
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append('{');
            String inner = indent == null || depth + 1 >= flatFrom ? null : indent + "  ";
            int k = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                b.append(k++ > 0 ? "," : "");
                if (inner != null) {
                    b.append('\n').append(inner);
                }
                str(b, e.getKey());
                b.append(inner == null ? ":" : ": ");
                write(b, e.getValue(), inner, depth + 1, flatFrom);
            }
            if (inner != null) {
                b.append('\n').append(indent);
            }
            b.append('}');
        } else if (v instanceof List) {
            List<Object> a = (List<Object>) v;
            boolean flat = indent == null;
            if (!flat) {
                flat = true;
                for (Object x : a) {
                    if (x instanceof Map || x instanceof List) {
                        flat = false;
                        break;
                    }
                }
            }
            b.append('[');
            String inner = flat || depth + 1 >= flatFrom ? null : indent + "  ";
            for (int k = 0; k < a.size(); k++) {
                b.append(k > 0 ? "," : "");
                if (inner != null) {
                    b.append('\n').append(inner);
                }
                write(b, a.get(k), inner, depth + 1, flatFrom);
            }
            if (inner != null) {
                b.append('\n').append(indent);
            }
            b.append(']');
        } else if (v instanceof String) {
            str(b, (String) v);
        } else if (v instanceof Double || v instanceof Float) {
            b.append(number(((Number) v).doubleValue()));
        } else if (v instanceof Number) {
            b.append(((Number) v).longValue());
        } else if (v instanceof Boolean) {
            b.append(v);
        } else if (v == null) {
            b.append("null");
        } else {
            throw new IllegalArgumentException("JSON cannot hold a " + v.getClass().getSimpleName());
        }
    }

    /** A double in Java's shortest form that reads back to the same bits. */
    static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("JSON cannot hold " + d);
        }
        return Double.toString(d);
    }

    private static void str(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '<': b.append("\\u003c"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }

    /** The double a JSON number holds, refusing anything else. */
    static double num(Object v, String what) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        throw new IllegalArgumentException(what + " is not a number: " + v);
    }

}
