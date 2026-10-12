package sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader (RFC 8259), enough for counting-systems.json (SHOE_SPEC.md B9) and the
 * simulator's own summaries: objects become LinkedHashMaps in file order, arrays Lists, strings
 * Strings, numbers Doubles (read by Double.parseDouble, so a decimal comes back as the nearest
 * double), and true, false and null themselves. Anything else, or text after the value, is refused.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.space();
        Object v = j.value();
        j.space();
        if (j.i != text.length()) {
            throw j.error("text after the value");
        }
        return v;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON at character " + i + ": " + what);
    }

    private void space() {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r')) {
            i++;
        }
    }

    private Object value() {
        if (i >= s.length()) {
            throw error("a value expected");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return word("true", Boolean.TRUE);
            case 'f':
                return word("false", Boolean.FALSE);
            case 'n':
                return word("null", null);
            default:
                return number();
        }
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) {
            throw error("unknown word");
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
                throw error("a member name expected");
            }
            String k = string();
            space();
            expect(':');
            space();
            if (m.put(k, value()) != null) {
                throw error("member " + k + " twice");
            }
            space();
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            expect('}');
            return m;
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
            expect(']');
            return a;
        }
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) {
            throw error("'" + c + "' expected");
        }
        i++;
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
                throw error("a control character in a string");
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
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) {
                        throw error("short \\u escape");
                    }
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> throw error("unknown escape \\" + e);
            }
        }
    }

    private Double number() {
        int start = i;
        if (i < s.length() && s.charAt(i) == '-') {
            i++;
        }
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String t = s.substring(start, i);
        if (!t.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
            throw error("not a JSON number: '" + t + "'");
        }
        return Double.parseDouble(t);
    }
}
