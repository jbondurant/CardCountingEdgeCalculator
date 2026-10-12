import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two scored Results set side by side, every number of the first against the second: how a
 * replay (FullIndexReplay) shows that the item it scored again is the item the source run scored.
 *
 * Why every field and not a list of the arrays that matter: the plan names estimates, resamples,
 * per-shoe sums, key tables, the half fits and the resolution (PLAN_COMPUTE.md 6.1), but a Result
 * also holds each fold's groups, first-decision fits and full-index summaries, and a list kept by
 * hand would miss whatever is added next. So the comparison walks the whole object, field by
 * field, and only three things are set apart, each for a stated reason:
 * - Result.seconds, a timing, which no rerun repeats;
 * - a CountKeys.Count is compared by its name alone, since a Count read from c06c061's files lacks
 *   the divisor and betting vector added since (StoredResultsTest) while the replay's carries them;
 * - a field the source holds as null and the replay fills is an array added after the source was
 *   written (the wide plans' arrays, halfFitSame): it is listed as added, and not compared.
 *
 * Numbers are compared to the bit first. Where the bits differ, the project's standard for a
 * figure worked out again in another order applies, 1e-12 relative to the larger of the two
 * (FULL_INDEX_PLAYS.md section 3); such a figure is counted, the largest difference kept with
 * where it is, and the item passes. Anything else (a number further apart, a NaN against a
 * number, a count, flag, name or length that differs, an array the replay lacks) is beyond, and
 * the first few are kept with their places.
 */
final class ResultDiff implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    /** The project's standard for a figure worked out again in another order. */
    static final double RELATIVE = 1e-12;
    /** How many places beyond, and added, are kept in words. */
    static final int KEEP = 12;

    /** Numbers compared, those the same to the bit, and those within RELATIVE but not the same to the bit. */
    long numbers;
    long sameBits;
    long within;
    /** The largest relative difference among those within, and where it is; 0 and null when none. */
    double largest;
    String largestAt;
    /** Other values compared: counts, flags, names, lengths. */
    long others;
    /** Every difference beyond RELATIVE, the first KEEP of them in words. */
    long beyond;
    final List<String> beyondAt = new ArrayList<>();
    /** Fields null in the source and set in the replay, the first KEEP in words. */
    long added;
    final List<String> addedAt = new ArrayList<>();

    private transient IdentityHashMap<Object, Object> seen;

    /** The source's x and the replay's y, compared whole. */
    static ResultDiff of(Object x, Object y) {
        ResultDiff d = new ResultDiff();
        d.seen = new IdentityHashMap<>();
        d.compare(new Where(null, "result"), x, y);
        d.seen = null;
        return d;
    }

    /** Whether nothing is beyond RELATIVE. */
    boolean passes() {
        return beyond == 0;
    }

    /** Whether every number is the same to the bit and nothing else differs. */
    boolean identical() {
        return beyond == 0 && within == 0;
    }

    /** One line: what was compared and how it came out. */
    String describe() {
        StringBuilder b = new StringBuilder();
        b.append(numbers).append(" numbers, ").append(sameBits).append(" the same to the bit");
        if (within > 0) {
            b.append(", ").append(within).append(" within ").append(RELATIVE).append(" relative (the largest ")
                    .append(largest).append(", at ").append(largestAt).append(')');
        }
        b.append("; ").append(others).append(" other values");
        if (beyond > 0) {
            b.append("; ").append(beyond).append(" BEYOND ").append(RELATIVE).append(", the first: ")
                    .append(String.join("; ", beyondAt));
        }
        if (added > 0) {
            b.append("; ").append(added).append(" fields added since the source was written, as ")
                    .append(String.join(", ", addedAt));
        }
        return b.toString();
    }

    /** A place in the object, built into words only when a difference is kept. */
    private static final class Where {
        final Where up;
        final String name;
        final int index;

        Where(Where up, String name) {
            this.up = up;
            this.name = name;
            this.index = -1;
        }

        Where(Where up, int index) {
            this.up = up;
            this.name = null;
            this.index = index;
        }

        @Override
        public String toString() {
            String here = name != null ? (up == null ? name : "." + name) : "[" + index + "]";
            return up == null ? here : up + here;
        }
    }

    private void beyond(Where at, String what) {
        beyond++;
        if (beyondAt.size() < KEEP) {
            beyondAt.add(at + ": " + what);
        }
    }

    private void number(Where at, int index, double x, double y) {
        numbers++;
        if (Double.doubleToLongBits(x) == Double.doubleToLongBits(y)) {
            sameBits++;
            return;
        }
        // Equal but not in bits: 0 and -0.
        double d = x == y ? 0 : Math.abs(x - y) / Math.max(Math.abs(x), Math.abs(y));
        if (d <= RELATIVE) {
            within++;
            if (d > largest || largestAt == null) {
                largest = d;
                largestAt = (index < 0 ? at : new Where(at, index)).toString();
            }
            return;
        }
        beyond(index < 0 ? at : new Where(at, index), x + " in the source, " + y + " replayed"
                + (d == d ? ", " + d + " apart relative" : ""));
    }

    private void same(Where at, Object x, Object y) {
        others++;
        if (!x.equals(y)) {
            beyond(at, x + " in the source, " + y + " replayed");
        }
    }

    private void compare(Where at, Object x, Object y) {
        if (x == null) {
            if (y != null) {
                added++;
                if (addedAt.size() < KEEP) {
                    addedAt.add(at.toString());
                }
            }
            return;
        }
        if (y == null) {
            beyond(at, "set in the source, missing in the replay");
            return;
        }
        Class<?> c = x.getClass();
        if (c != y.getClass()) {
            beyond(at, c.getName() + " in the source, " + y.getClass().getName() + " replayed");
            return;
        }
        if (x instanceof Double || x instanceof Float) {
            number(at, -1, ((Number) x).doubleValue(), ((Number) y).doubleValue());
            return;
        }
        if (x instanceof Number || x instanceof Boolean || x instanceof Character || x instanceof String
                || x instanceof Enum) {
            same(at, x, y);
            return;
        }
        // An object two places share (a fit's groups and the Result's) is compared once.
        if (seen.get(x) == y) {
            return;
        }
        seen.put(x, y);
        if (c == double[].class) {
            double[] a = (double[]) x;
            double[] b = (double[]) y;
            if (length(at, a.length, b.length)) {
                for (int k = 0; k < a.length; k++) {
                    number(at, k, a[k], b[k]);
                }
            }
            return;
        }
        if (c == float[].class) {
            float[] a = (float[]) x;
            float[] b = (float[]) y;
            if (length(at, a.length, b.length)) {
                for (int k = 0; k < a.length; k++) {
                    number(at, k, a[k], b[k]);
                }
            }
            return;
        }
        if (c.isArray() && c.getComponentType().isPrimitive()) {
            int n = java.lang.reflect.Array.getLength(x);
            if (length(at, n, java.lang.reflect.Array.getLength(y))) {
                for (int k = 0; k < n; k++) {
                    Object u = java.lang.reflect.Array.get(x, k);
                    Object v = java.lang.reflect.Array.get(y, k);
                    if (!u.equals(v)) {
                        beyond(new Where(at, k), u + " in the source, " + v + " replayed");
                        return;
                    }
                }
                others++;
            }
            return;
        }
        if (x instanceof Object[]) {
            Object[] a = (Object[]) x;
            Object[] b = (Object[]) y;
            if (length(at, a.length, b.length)) {
                for (int k = 0; k < a.length; k++) {
                    compare(new Where(at, k), a[k], b[k]);
                }
            }
            return;
        }
        if (x instanceof List) {
            List<?> a = (List<?>) x;
            List<?> b = (List<?>) y;
            if (length(at, a.size(), b.size())) {
                for (int k = 0; k < a.size(); k++) {
                    compare(new Where(at, k), a.get(k), b.get(k));
                }
            }
            return;
        }
        if (x instanceof Map) {
            Map<?, ?> a = (Map<?, ?>) x;
            Map<?, ?> b = (Map<?, ?>) y;
            if (length(at, a.size(), b.size())) {
                for (Map.Entry<?, ?> e : a.entrySet()) {
                    if (!b.containsKey(e.getKey())) {
                        beyond(new Where(at, String.valueOf(e.getKey())), "in the source, not replayed");
                    } else {
                        compare(new Where(at, String.valueOf(e.getKey())), e.getValue(), b.get(e.getKey()));
                    }
                }
            }
            return;
        }
        if (x instanceof CountKeys.Count) {
            same(new Where(at, "name"), ((CountKeys.Count) x).name, ((CountKeys.Count) y).name);
            return;
        }
        if (c.getName().startsWith("java.")) {
            // A library class whose fields are closed to reflection: its own equals.
            same(at, x, y);
            return;
        }
        fields(at, c, x, y);
    }

    /** Whether two lengths agree; a difference is beyond. */
    private boolean length(Where at, int a, int b) {
        others++;
        if (a != b) {
            beyond(at, "length " + a + " in the source, " + b + " replayed");
            return false;
        }
        return true;
    }

    /** Every field of x's class and its superclasses but statics, transients and Result.seconds. */
    private void fields(Where at, Class<?> c, Object x, Object y) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || f.isSynthetic()) {
                    continue;
                }
                if (k == SystemScorer.Result.class && f.getName().equals("seconds")) {
                    continue;
                }
                f.setAccessible(true);
                Where w = new Where(at, f.getName());
                try {
                    Class<?> t = f.getType();
                    if (t == double.class || t == float.class) {
                        number(w, -1, f.getDouble(x), f.getDouble(y));
                    } else if (t.isPrimitive()) {
                        same(w, f.get(x), f.get(y));
                    } else {
                        compare(w, f.get(x), f.get(y));
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("cannot read " + w, e);
                }
            }
        }
    }
}
