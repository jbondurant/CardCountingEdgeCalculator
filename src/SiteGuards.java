import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The claim guards of PLAN_PAGE.md 2.4: a sentence that compares figures is printed only if the
 * data supports it with an interval, not just with the order of the point estimates.
 *
 * A guard is written in the template beside its sentence, in one of three forms:
 * <ul>
 *   <li>{@code {{guard: EXPR > 0}}} (or {@code < 0}): the 95% interval of EXPR lies above (below)
 *   zero. {@code {{guard: EXPR ~ 0}}}: it includes zero, for a sentence saying the data cannot
 *   separate two rows.</li>
 *   <li>{@code {{guard: each > 0: EXPR; EXPR; …}}}: every one of several quantities at once, with
 *   one max-|t| multiplier over them (Bootstrap.maxT), for a sentence that ranks several rows.</li>
 *   <li>{@code {{guard: leader-tied: KEY, KEY, …}}}: among these rows, at least one pair that
 *   involves the highest is not separated by the simultaneous intervals of all their pairs, for
 *   "this data cannot say which is best".</li>
 * </ul>
 * EXPR adds and subtracts figure keys, with brackets. It is computed resample by resample from the
 * inputs' {@code boot} arrays, which share the bootstrap's resamples, so a difference stays
 * paired. Its resamples are widened by the largest ratio among its figures (FULL_INDEX_PLAYS.md
 * section 4: a difference takes the larger of its two counts' ratios; first-decision figures have
 * ratio 1), and its interval is Bootstrap.interval, the same as every report's.
 *
 * Each guard's result is recorded in results.json's guards block, so a reader can see what each
 * sentence rested on; the page is rendered only if every guard holds.
 */
final class SiteGuards {

    private SiteGuards() {
    }

    /** A quantity computed resample by resample. */
    static final class Quantity {
        final String expr;
        final double estimate;
        final double[] boot;
        final double widen;

        Quantity(String expr, double estimate, double[] boot, double widen) {
            this.expr = expr;
            this.estimate = estimate;
            this.boot = boot;
            this.widen = widen;
        }

        double[] widened() {
            return widen == 1 ? boot : Bootstrap.widen(boot, widen);
        }
    }

    /** Parses and evaluates EXPR on the inputs. */
    static Quantity quantity(String expr, SiteData d) {
        Parser p = new Parser(expr.trim(), d);
        double[] v = p.sum();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("guard: cannot read \"" + expr + "\" past offset " + p.i);
        }
        int n = v.length - 1;
        double[] boot = new double[n];
        System.arraycopy(v, 1, boot, 0, n);
        double widen = 1;
        for (String k : p.keys) {
            widen = Math.max(widen, d.resolve(k).widen);
        }
        return new Quantity(expr.trim(), v[0], boot, widen);
    }

    /** A recursive-descent reader of EXPR; each value is {estimate, resample 1, …, resample R}. */
    private static final class Parser {
        final String s;
        final SiteData d;
        int i;
        int resamples = -1;
        final Set<String> keys = new LinkedHashSet<>();

        Parser(String s, SiteData d) {
            this.s = s;
            this.d = d;
        }

        void space() {
            while (i < s.length() && s.charAt(i) == ' ') {
                i++;
            }
        }

        double[] sum() {
            double[] v = term();
            while (true) {
                space();
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    char op = s.charAt(i++);
                    double[] w = term();
                    for (int r = 0; r < v.length; r++) {
                        v[r] = op == '+' ? v[r] + w[r] : v[r] - w[r];
                    }
                } else {
                    return v;
                }
            }
        }

        double[] term() {
            space();
            if (i < s.length() && s.charAt(i) == '(') {
                i++;
                double[] v = sum();
                space();
                if (i >= s.length() || s.charAt(i) != ')') {
                    throw new IllegalArgumentException("guard: a bracket is not closed in \"" + s + "\"");
                }
                i++;
                return v;
            }
            int start = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '_')) {
                i++;
            }
            String k = s.substring(start, i);
            if (k.isEmpty()) {
                throw new IllegalArgumentException("guard: expected a figure key at offset " + start + " of \"" + s + "\"");
            }
            SiteData.Figure f = d.resolve(k);
            if (f.boot == null) {
                throw new IllegalArgumentException("guard: " + k + " has no resamples in the inputs, so no interval "
                        + "can support a sentence about it");
            }
            if (resamples >= 0 && f.boot.length != resamples) {
                throw new IllegalArgumentException("guard: " + k + " has " + f.boot.length + " resamples, other figures "
                        + resamples + "; a paired difference needs the same resamples");
            }
            resamples = f.boot.length;
            keys.add(k);
            double[] v = new double[resamples + 1];
            v[0] = f.value;
            System.arraycopy(f.boot, 0, v, 1, resamples);
            return v;
        }
    }

    /**
     * Evaluates one guard's text (what follows "guard:") on the inputs and returns its record for
     * results.json, with "holds" true or false.
     */
    static Map<String, Object> evaluate(String text, SiteData d) {
        String t = text.trim();
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("guard", t);
        if (t.startsWith("leader-tied:")) {
            Pairs p = pairs(keys(t.substring("leader-tied:".length())), d);
            boolean tied = false;
            for (Pair x : p.pairs) {
                tied |= !x.separated && (x.a.equals(p.leader) || x.b.equals(p.leader));
            }
            rec.put("kind", "leader-tied");
            rec.put("leader", p.leader);
            rec.put("multiplier", p.multiplier);
            rec.put("holds", tied);
            return rec;
        }
        if (t.startsWith("each")) {
            int colon = t.indexOf(':');
            String head = t.substring(4, colon).trim();
            char op = head.charAt(0);
            if (!head.substring(1).trim().equals("0") || (op != '>' && op != '<')) {
                throw new IllegalArgumentException("guard: \"" + t + "\" must read each > 0: … or each < 0: …");
            }
            List<Quantity> qs = new ArrayList<>();
            for (String e : t.substring(colon + 1).split(";")) {
                qs.add(quantity(e, d));
            }
            double[] est = new double[qs.size()];
            double[][] boots = new double[qs.size()][];
            for (int j = 0; j < est.length; j++) {
                est[j] = qs.get(j).estimate;
                boots[j] = qs.get(j).widened();
            }
            double c = Bootstrap.maxT(est, boots);
            boolean holds = true;
            List<Object> each = new ArrayList<>();
            for (int j = 0; j < est.length; j++) {
                double se = Bootstrap.interval(est[j], boots[j]).se;
                double lo = est[j] - c * se;
                double hi = est[j] + c * se;
                holds &= op == '>' ? lo > 0 : hi < 0;
                each.add(List.of(qs.get(j).expr, est[j], lo, hi, qs.get(j).widen));
            }
            rec.put("kind", "simultaneous");
            rec.put("multiplier", c);
            rec.put("each", each);
            rec.put("holds", holds);
            return rec;
        }
        int at = Math.max(t.lastIndexOf('>'), Math.max(t.lastIndexOf('<'), t.lastIndexOf('~')));
        if (at < 0 || !t.substring(at + 1).trim().equals("0")) {
            throw new IllegalArgumentException("guard: \"" + t + "\" must end in > 0, < 0 or ~ 0");
        }
        char op = t.charAt(at);
        Quantity q = quantity(t.substring(0, at), d);
        Bootstrap.Interval iv = Bootstrap.interval(q.estimate, q.widened());
        boolean holds = op == '>' ? iv.lo > 0 : op == '<' ? iv.hi < 0 : iv.lo <= 0 && iv.hi >= 0;
        rec.put("kind", "single");
        rec.put("estimate", q.estimate);
        rec.put("ci", List.of(iv.lo, iv.hi));
        rec.put("widen", q.widen);
        rec.put("holds", holds);
        return rec;
    }

    static List<String> keys(String list) {
        List<String> out = new ArrayList<>();
        for (String k : list.split(",")) {
            if (!k.trim().isEmpty()) {
                out.add(k.trim());
            }
        }
        if (out.size() < 2) {
            throw new IllegalArgumentException("guard: a pair list needs at least two keys: " + list);
        }
        return out;
    }

    /** One pair of rows: a less b, with its simultaneous interval. */
    static final class Pair {
        final String a;
        final String b;
        final double diff;
        final double lo;
        final double hi;
        final boolean separated;

        Pair(String a, String b, double diff, double lo, double hi) {
            this.a = a;
            this.b = b;
            this.diff = diff;
            this.lo = lo;
            this.hi = hi;
            this.separated = lo > 0 || hi < 0;
        }
    }

    /** Every pair among several rows, with one max-|t| multiplier over all of them. */
    static final class Pairs {
        final List<String> keys;
        final String leader;
        final double multiplier;
        final List<Pair> pairs;

        Pairs(List<String> keys, String leader, double multiplier, List<Pair> pairs) {
            this.keys = keys;
            this.leader = leader;
            this.multiplier = multiplier;
            this.pairs = pairs;
        }
    }

    /**
     * The pairwise test of PLAN_PAGE.md 5a: each pair's difference taken resample by resample,
     * widened by the larger of its two ratios, and one max-|t| multiplier over every pair. Each pair
     * is written higher first, so a separated pair reads "a above b".
     */
    static Pairs pairs(List<String> keys, SiteData d) {
        String leader = keys.get(0);
        for (String k : keys) {
            if (d.resolve(k).value > d.resolve(leader).value) {
                leader = k;
            }
        }
        List<Quantity> qs = new ArrayList<>();
        List<String[]> names = new ArrayList<>();
        for (int x = 0; x < keys.size(); x++) {
            for (int y = x + 1; y < keys.size(); y++) {
                String a = keys.get(x);
                String b = keys.get(y);
                if (d.resolve(b).value > d.resolve(a).value) {
                    String s = a;
                    a = b;
                    b = s;
                }
                qs.add(quantity(a + " - " + b, d));
                names.add(new String[]{a, b});
            }
        }
        double[] est = new double[qs.size()];
        double[][] boots = new double[qs.size()][];
        for (int j = 0; j < est.length; j++) {
            est[j] = qs.get(j).estimate;
            boots[j] = qs.get(j).widened();
        }
        double c = Bootstrap.maxT(est, boots);
        List<Pair> out = new ArrayList<>();
        for (int j = 0; j < est.length; j++) {
            double se = Bootstrap.interval(est[j], boots[j]).se;
            out.add(new Pair(names.get(j)[0], names.get(j)[1], est[j], est[j] - c * se, est[j] + c * se));
        }
        return new Pairs(keys, leader, c, out);
    }

    /** The record of a pair family for results.json, which the page's pair lists are printed from. */
    static Map<String, Object> pairsRecord(Pairs p) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("guard", "pairs: " + String.join(", ", p.keys));
        rec.put("kind", "pairs");
        rec.put("leader", p.leader);
        rec.put("multiplier", p.multiplier);
        List<Object> list = new ArrayList<>();
        for (Pair x : p.pairs) {
            list.add(List.of(x.a, x.b, x.diff, x.lo, x.hi, x.separated));
        }
        rec.put("pairs", list);
        rec.put("holds", true);
        return rec;
    }
}
