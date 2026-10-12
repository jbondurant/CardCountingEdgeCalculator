package sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The catalog of published counts, counting-systems.json, read as SHOE_SPEC.md B9 says the engine
 * reads it, with the simulator's own JSON reader: for check D2, which holds the simulator's keys
 * for every count to keys-sample.tsv's. Each system's tags by kind (its rank's {@code tags},
 * overridden by any {@code cardTags} entry naming that kind), made whole at the least scale from 1
 * to 100; systems with the same whole tags and the same mode are one entry, named by their names
 * joined with " = "; then the null count; then an ace side-count betting variant of each entry
 * holding a system of {@link #SIDE_COUNTED}.
 */
public final class Catalog {

    /** One count as the keys use it (B2.1's parameters): divisor 1 throughout the catalog. */
    public record Count(String name, boolean trueCount, int[] tags, int scale, int[] betTags, int ace) {

        public Keys keys(boolean truncate) {
            return new Keys(tags, betTags, trueCount, 1, deckSum(tags), deckSum(betTags), ace, truncate);
        }

        /** Whether some rank's kinds are tagged differently, by colour or by face (A1). */
        public boolean byColourOrFace() {
            return Catalog.byColourOrFace(tags);
        }
    }

    /** SHOE_SPEC.md B9's block of side-counted systems (the engine's list, not the file's). */
    public static final List<String> SIDE_COUNTED = List.of("Hi-Opt I", "Hi-Opt II", "Omega II", "Canfield Expert",
            "Revere Advanced Plus-Minus", "Revere 14 Count (1973 Advanced Point Count)",
            "Uston Advanced Point Count (Uston APC)", "Victor Advanced Point Count (Victor APC)");

    public static final String NULL_COUNT = "Null count (no count)";
    public static final String ACE_SUFFIX = " + ace side count";

    public final List<Count> entries = new ArrayList<>();
    public final Count nullCount = new Count(NULL_COUNT, false, new int[Shoe.KINDS], 1, new int[Shoe.KINDS], 0);
    public final List<Count> variants = new ArrayList<>();

    static long deckSum(int[] tags) {
        long s = 0;
        for (int t : tags) {
            s += 2L * t;
        }
        return s;
    }

    static boolean byColourOrFace(int[] tags) {
        for (int k = 0; k < 18; k += 2) {
            if (tags[k] != tags[k + 1]) {
                return true;
            }
        }
        for (int k = 19; k < Shoe.KINDS; k++) {
            if (tags[k] != tags[18]) {
                return true;
            }
        }
        return false;
    }

    /** Every count keys-sample.tsv's header lists from the catalog: the entries, the null count, the variants. */
    public List<Count> all() {
        List<Count> out = new ArrayList<>(entries);
        out.add(nullCount);
        out.addAll(variants);
        return out;
    }

    public Count named(String name) {
        for (Count c : all()) {
            if (c.name.equals(name)) {
                return c;
            }
        }
        return null;
    }

    public static Catalog read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** The rank13 (A1: A, 2, ..., 10, J, Q, K from 0 to 12) of a cardTags rank name, or -1. */
    static int rank13(String r) {
        switch (r) {
            case "A":
                return 0;
            case "10":
                return 9;
            case "J":
                return 10;
            case "Q":
                return 11;
            case "K":
                return 12;
            default:
                return r.length() == 1 && r.charAt(0) >= '2' && r.charAt(0) <= '9' ? r.charAt(0) - '1' : -1;
        }
    }

    @SuppressWarnings("unchecked")
    public static Catalog parse(String json) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        Catalog c = new Catalog();
        List<List<String>> members = new ArrayList<>();
        for (Object o : (List<Object>) root.get("systems")) {
            Map<String, Object> sys = (Map<String, Object>) o;
            String name = (String) sys.get("name");
            String mode = (String) sys.get("count");
            if (!"true".equals(mode) && !"running".equals(mode)) {
                throw new IllegalArgumentException(name + ": count is '" + mode + "', not true or running");
            }
            double[] tags = byKind(name, (Map<String, Object>) sys.get("tags"), (Map<String, Object>) sys.get("cardTags"));
            int scale = scale(name, tags);
            int[] whole = new int[Shoe.KINDS];
            for (int k = 0; k < Shoe.KINDS; k++) {
                whole[k] = (int) Math.round(tags[k] * scale);
            }
            boolean trueCount = "true".equals(mode);
            int found = -1;
            for (int e = 0; e < c.entries.size(); e++) {
                Count x = c.entries.get(e);
                if (x.trueCount == trueCount && Arrays.equals(x.tags, whole)) {
                    found = e;
                }
            }
            if (found < 0) {
                c.entries.add(new Count(name, trueCount, whole, scale, whole, 0));
                members.add(new ArrayList<>(List.of(name)));
            } else {
                Count x = c.entries.get(found);
                c.entries.set(found, new Count(x.name + " = " + name, x.trueCount, x.tags, x.scale, x.betTags, 0));
                members.get(found).add(name);
            }
        }
        for (int e = 0; e < c.entries.size(); e++) {
            Count x = c.entries.get(e);
            if (members.get(e).stream().anyMatch(SIDE_COUNTED::contains)) {
                c.variants.add(new Count(x.name + ACE_SUFFIX, true, x.tags, x.scale, x.tags, Math.abs(x.tags[18])));
            }
        }
        return c;
    }

    private static double[] byKind(String name, Map<String, Object> tags, Map<String, Object> cardTags) {
        double[] t = new double[Shoe.KINDS];
        String[] ranks = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "T"};
        for (int k = 0; k < Shoe.KINDS; k++) {
            int r = Math.min(k / 2, 9);
            Object v = tags.get(ranks[r]);
            if (!(v instanceof Double)) {
                throw new IllegalArgumentException(name + ": no number for tag " + ranks[r]);
            }
            t[k] = (Double) v;
        }
        if (cardTags != null) {
            boolean[] named = new boolean[Shoe.KINDS];
            for (Map.Entry<String, Object> e : cardTags.entrySet()) {
                String key = e.getKey();
                String colour = key.endsWith("red") ? "red" : key.endsWith("black") ? "black" : "";
                int r13 = rank13(key.substring(0, key.length() - colour.length()));
                if (r13 < 0 || !(e.getValue() instanceof Double)) {
                    throw new IllegalArgumentException(name + ": cardTags entry " + key);
                }
                for (int col = 0; col < 2; col++) {
                    if (colour.isEmpty() || colour.equals(col == 1 ? "red" : "black")) {
                        int k = r13 * 2 + col;
                        if (named[k]) {
                            throw new IllegalArgumentException(name + ": two cardTags entries name kind " + k);
                        }
                        named[k] = true;
                        t[k] = (Double) e.getValue();
                    }
                }
            }
        }
        return t;
    }

    /** The least whole S from 1 to 100 that makes every tag whole within 1e-9. */
    static int scale(String name, double[] tags) {
        for (int s = 1; s <= 100; s++) {
            boolean whole = true;
            for (double t : tags) {
                whole &= Math.abs(t * s - Math.rint(t * s)) <= 1e-9;
            }
            if (whole) {
                return s;
            }
        }
        throw new IllegalArgumentException(name + ": no scale from 1 to 100 makes its tags whole");
    }
}
