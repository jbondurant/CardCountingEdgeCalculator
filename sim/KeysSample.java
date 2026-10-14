package sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Check D2 (SIM_CHECK.md section 5): the simulator's bet and play keys, from its own reading of
 * counting-systems.json (B9) and B2.2's formulas, against keys-sample.tsv (B4), cell by cell. A
 * column names a count of the catalog (an entry, the null count or an ace side-count variant), or a
 * registered count, whose parameters come from the strategy file whose {@code betcount} or
 * {@code playcount} names it (B9's last paragraph). A colour or face count's play cell lists every
 * key the deal can have with its chance, the deal's three cards drawn without replacement from the
 * kinds left; D2 holds the keys equal and each chance within 1e-12 relative, whatever their order.
 */
public final class KeysSample {

    static final String HEADER = "line\tstate\tshoe\tround\tdepth\tp1\tp2\tup";

    /** One column's count: the keys its play cells and its bet cells are computed with. */
    record Column(String name, Keys play, Keys bet) {
        boolean byColourOrFace() {
            return Catalog.byColourOrFace(play.tags);
        }
    }

    /** What D2 found: lines and cells compared, cells that differ (the first few named), catalog counts the header lacks. */
    public record Result(long lines, long cells, long mismatches, List<String> first, List<String> missing) {
        public boolean passes() {
            return mismatches == 0 && missing.isEmpty() && cells > 0;
        }
    }

    private KeysSample() {
    }

    /** A column's count, from the catalog or from the bundle's strategy files; null if neither names it. */
    static Column column(String name, Catalog catalog, Iterable<Strategy> files, boolean truncate) {
        Catalog.Count c = catalog.named(name);
        if (c != null) {
            Keys k = c.keys(truncate);
            return new Column(name, k, k);
        }
        Keys play = null;
        Keys bet = null;
        for (Strategy s : files) {
            if (play == null && s.playCount.equals(name)) {
                play = s.keys;
            }
            if (bet == null && s.betCount.equals(name)) {
                bet = s.keys;
            }
        }
        if (play == null && bet == null) {
            return null;
        }
        if (play == null) {
            // a betting variant plays on its base: its play column repeats the base's key (B4)
            play = bet;
        }
        if (bet == null) {
            // a registered count no file bets with: its bet key on its own tags, no ace count
            Keys p = play;
            bet = new Keys(p.tags, p.tags, p.trueCount, p.divisor, p.deckSum, p.deckSum, 0, truncate);
        }
        return new Column(name, play, bet);
    }

    /** The kinds of a contract rank (A1): two for 1 to 9, eight for the ten-valued cards. */
    static int[] kindsOf(int rank) {
        return rank == 10 ? new int[]{18, 19, 20, 21, 22, 23, 24, 25} : new int[]{2 * (rank - 1), 2 * (rank - 1) + 1};
    }

    /**
     * Every play key the deal (p1, up, p2) can have at a state, with its chance given the deal's
     * ranks: the three cards drawn without replacement from the kinds left. Empty when the cards
     * left cannot make the deal.
     */
    static TreeMap<Integer, Double> playKeyParts(Keys keys, int[] dealt, int depth, int p1, int p2, int up) {
        int[] left = new int[Shoe.KINDS];
        for (int k = 0; k < Shoe.KINDS; k++) {
            left[k] = Shoe.PER_KIND - dealt[k];
        }
        long rc = keys.playRunning(dealt);
        TreeMap<Integer, Double> parts = new TreeMap<>();
        int[] ranks = {p1, up, p2};
        int[] seen = new int[3];
        enumerate(keys, rc, depth, left, ranks, seen, 0, 1.0, parts);
        return parts;
    }

    private static void enumerate(Keys keys, long rc, int depth, int[] left, int[] ranks, int[] seen, int at,
                                  double chance, TreeMap<Integer, Double> parts) {
        if (at == 3) {
            parts.merge(keys.playKey(rc, depth, seen, 3), chance, Double::sum);
            return;
        }
        int[] kinds = kindsOf(ranks[at]);
        int ofRank = 0;
        for (int k : kinds) {
            ofRank += left[k];
        }
        for (int k : kinds) {
            if (left[k] == 0) {
                continue;
            }
            double p = (double) left[k] / ofRank;
            left[k]--;
            seen[at] = k;
            enumerate(keys, rc, depth, left, ranks, seen, at + 1, chance * p, parts);
            left[k]++;
        }
    }

    /** A deal's play key for a count that tags every rank alike: any kind of each rank gives it. */
    static int playKey(Keys keys, int[] dealt, int depth, int p1, int p2, int up) {
        int[] seen = {kindsOf(p1)[0], kindsOf(up)[0], kindsOf(p2)[0]};
        return keys.playKey(keys.playRunning(dealt), depth, seen, 3);
    }

    /** Whether two chances agree within 1e-12 relative. */
    static boolean close(double a, double b) {
        return Math.abs(a - b) <= 1e-12 * Math.max(Math.abs(a), Math.abs(b));
    }

    /** A colour or face cell, "key:chance;key:chance;...", or null if it cannot be read. */
    static TreeMap<Integer, Double> readParts(String cell) {
        TreeMap<Integer, Double> m = new TreeMap<>();
        try {
            for (String part : cell.split(";", -1)) {
                String[] kc = part.split(":", -1);
                if (kc.length != 2 || m.put(Integer.parseInt(kc[0]), Double.parseDouble(kc[1])) != null) {
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return m;
    }

    static boolean sameParts(TreeMap<Integer, Double> mine, TreeMap<Integer, Double> theirs) {
        if (theirs == null || !mine.keySet().equals(theirs.keySet())) {
            return false;
        }
        for (Map.Entry<Integer, Double> e : mine.entrySet()) {
            if (!close(e.getValue(), theirs.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    public static Result check(Path file, Catalog catalog, Iterable<Strategy> files, States states, Plants plants)
            throws IOException {
        return check(Files.readAllLines(file, StandardCharsets.UTF_8), catalog, files, states, plants);
    }

    public static Result check(List<String> text, Catalog catalog, Iterable<Strategy> files, States states,
                               Plants plants) throws IOException {
        if (text.size() < 3 || !text.get(0).equals("# keys-sample.tsv: SHOE_SPEC.md B4, format 1")
                || !text.get(1).matches("# sample seed -?[0-9]+") || !text.get(2).startsWith(HEADER + "\t")) {
            throw new IOException("keys-sample.tsv: not B4's first three lines");
        }
        String[] header = text.get(2).split("\t", -1);
        List<Column> columns = new ArrayList<>();
        List<String> first = new ArrayList<>();
        long mismatches = 0;
        for (int c = 8; c < header.length; c++) {
            Column col = column(header[c], catalog, files, plants.on(Plants.TRUNCATE));
            if (col == null) {
                mismatches++;
                note(first, "column " + header[c] + ": no count of the catalog or of the bundle's files has this name");
            }
            columns.add(col);
        }
        List<String> missing = new ArrayList<>();
        for (Catalog.Count k : catalog.all()) {
            if (!List.of(header).contains(k.name())) {
                missing.add(k.name());
            }
        }
        long lines = 0;
        long cells = 0;
        for (int n = 3; n < text.size(); n++) {
            String[] f = text.get(n).split("\t", -1);
            String where = "line " + (n + 1);
            if (f.length != header.length || !(f[0].equals("bet") || f[0].equals("play"))) {
                throw new IOException("keys-sample.tsv " + where + ": not a bet or play line of " + header.length + " fields");
            }
            int i = Integer.parseInt(f[1]);
            if (i < 0 || i >= states.n || states.shoe[i] != Integer.parseInt(f[2]) || states.round[i] != Integer.parseInt(f[3])
                    || states.depth[i] != Integer.parseInt(f[4])) {
                throw new IOException("keys-sample.tsv " + where + ": state " + f[1] + " is not that record of states.bin");
            }
            int[] dealt = new int[Shoe.KINDS];
            for (int k = 0; k < Shoe.KINDS; k++) {
                dealt[k] = states.dealt[i * Shoe.KINDS + k];
            }
            int depth = states.depth[i];
            boolean bet = f[0].equals("bet");
            int p1 = bet ? 0 : Integer.parseInt(f[5]);
            int p2 = bet ? 0 : Integer.parseInt(f[6]);
            int up = bet ? 0 : Integer.parseInt(f[7]);
            if (!bet && (p1 < 1 || p1 > p2 || p2 > 10 || up < 1 || up > 10)) {
                throw new IOException("keys-sample.tsv " + where + ": not a deal p1 <= p2, up");
            }
            lines++;
            for (int c = 0; c < columns.size(); c++) {
                Column col = columns.get(c);
                if (col == null) {
                    continue;
                }
                String cell = f[c + 8];
                cells++;
                boolean ok;
                String mine;
                if (bet) {
                    mine = Integer.toString(col.bet.betKey(dealt, depth));
                    ok = mine.equals(cell);
                } else if (col.byColourOrFace()) {
                    TreeMap<Integer, Double> parts = playKeyParts(col.play, dealt, depth, p1, p2, up);
                    mine = parts.isEmpty() ? "-" : parts.toString();
                    ok = parts.isEmpty() ? cell.equals("-") : sameParts(parts, readParts(cell));
                } else {
                    mine = Integer.toString(playKey(col.play, dealt, depth, p1, p2, up));
                    ok = mine.equals(cell);
                }
                if (!ok) {
                    mismatches++;
                    note(first, where + " " + f[0] + " state " + i + (bet ? "" : " deal " + p1 + "," + p2 + " up " + up)
                            + ", " + col.name + ": the file says " + cell + ", the simulator " + mine);
                }
            }
        }
        return new Result(lines, cells, mismatches, first, missing);
    }

    static void note(List<String> first, String what) {
        if (first.size() < 10) {
            first.add(what);
        }
    }
}
