package sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Check D5 (SIM_CHECK.md section 5): the engine's own answers at sampled states and deals, the
 * bundle's lookups.tsv (SHOE_SPEC.md B8), against what the simulator reads from each strategy file
 * by B2.2 to B2.4. A {@code first} cell is the deal's play key, its group, the dealt hand's first
 * move and the insurance decision, one part for each key the deal can have; a {@code later} cell,
 * for a full-play file, the key after one hit card, its later group, the contested cell and the
 * move. A colour or face count's parts are compared by key, whatever their order in the cell.
 *
 * <p>A {@code split} cell, for a full-play file, is a split hand's first decision in each of the
 * file's groups, answered by {@link Strategy#splitFirstMove} (the method the round's split hands
 * use) under Montreal's legal moves, whatever the simulator's own rules are set to. The 1,000
 * {@code split} lines, by up-card, then split rank, then the card drawn, come after every
 * {@code later} line when the bundle holds a file of scope {@code full}, and never otherwise; the
 * file is refused if it has any other number, or any out of order (B8). Their cells are counted
 * apart, and D5 fails when the bundle holds a full-play file and no split cell was compared.
 */
public final class Lookups {

    static final String HEADER = "line\tstate\tp1\tp2\tup\thit";

    /** B8: split lines, by up-card, then r, then x, each 1 to 10. */
    static final int SPLIT_LINES = 1000;

    /**
     * D5's counts: every line and cell compared (split lines and cells included), and the split
     * cells apart, with whether the bundle holds a file of scope full.
     */
    public record Result(long lines, long cells, long mismatches, List<String> first, long splitCells,
                         long splitMismatches, boolean fullPlay) {
        public boolean passes() {
            return mismatches == 0 && cells > 0 && (!fullPlay || splitCells > 0);
        }
    }

    private Lookups() {
    }

    static int[] dealt(States states, int i) {
        int[] d = new int[Shoe.KINDS];
        for (int k = 0; k < Shoe.KINDS; k++) {
            d[k] = states.dealt[i * Shoe.KINDS + k];
        }
        return d;
    }

    /** The chart's first decision on the dealt hand (p1, p2) against up: Hit, Double, and Split on a pair, legal. */
    static int chartFirst(Chart chart, int p1, int p2, int up) {
        int hard = p1 + p2;
        boolean soft = (p1 == 1 || p2 == 1) && hard + 10 <= 21;
        int legal = Round.L_HIT | Round.L_DOUBLE | (p1 == p2 ? Round.L_SPLIT : 0);
        return chart.move(Chart.index(up, soft ? hard + 10 : hard, soft, p1 == p2 ? p1 : 0, false, true, legal));
    }

    /** The simulator's first-line answer for one file: each key's "G:M:I" by key, or empty for "-". */
    static TreeMap<Integer, String> first(Strategy s, Chart chart, int[] dealt, int depth, int p1, int p2, int up) {
        TreeMap<Integer, String> out = new TreeMap<>();
        if (depth >= s.cut) {
            return out;
        }
        List<Integer> keys = new ArrayList<>();
        if (Catalog.byColourOrFace(s.keys.tags)) {
            keys.addAll(KeysSample.playKeyParts(s.keys, dealt, depth, p1, p2, up).keySet());
        } else {
            keys.add(KeysSample.playKey(s.keys, dealt, depth, p1, p2, up));
        }
        boolean natural = p1 == 1 && p2 == 10;
        for (int key : keys) {
            int g = Strategy.group(s.groupHi, key);
            String m = natural ? "-"
                    : String.valueOf(Round.MOVES.charAt("betting".equals(s.scope) ? chartFirst(chart, p1, p2, up)
                    : s.firstMoves[g][Strategy.deal(p1, p2, up)][0]));
            String in = up != 1 ? "-" : s.insure[g][Strategy.deal(p1, p2, 1)] ? "1" : "0";
            out.put(key, g + ":" + m + ":" + in);
        }
        return out;
    }

    /** The simulator's later-line cell for one file, or "-". */
    static String later(Strategy s, Chart chart, int[] dealt, int depth, int p1, int p2, int up, int hit) {
        if (!"full".equals(s.scope) || depth >= s.cut || Catalog.byColourOrFace(s.keys.tags)) {
            return "-";
        }
        int[] seen = {KeysSample.kindsOf(p1)[0], KeysSample.kindsOf(up)[0], KeysSample.kindsOf(p2)[0],
                KeysSample.kindsOf(hit)[0]};
        int key = s.keys.playKey(s.keys.playRunning(dealt), depth, seen, 4);
        int l = Strategy.group(s.laterHi, key);
        int hard = p1 + p2 + hit;
        boolean soft = (p1 == 1 || p2 == 1 || hit == 1) && hard + 10 <= 21;
        int total = soft ? hard + 10 : hard;
        String cm;
        if (total > 21) {
            cm = "-:-";
        } else if (Strategy.contested(total, soft)) {
            int spot = Strategy.spot(total, soft, up);
            cm = spot + ":" + (s.laterStand[l][spot] ? "S" : "H");
        } else if (total < 21) {
            cm = "-:" + Round.MOVES.charAt(chart.move(Chart.index(up, total, soft, 0, false, false, Round.L_HIT)));
        } else {
            cm = "-:S";
        }
        return key + ":" + l + ":" + cm;
    }

    /**
     * The simulator's split cell for one file: a split r that has drawn x against up, at its first
     * decision, in each group g = 0 to G − 1, Split not legal; for a pair 2 to 10, ":" and the same
     * with Split legal. "-" for a file not of scope full. The legal moves are Montreal's (SHOE_SPEC.md
     * A4's step 5): Stand alone on a split ace; Stand, Hit and Double on a split 2 to 10.
     */
    static String split(Strategy s, int r, int x, int up) {
        if (!"full".equals(s.scope)) {
            return "-";
        }
        int legal = r == 1 ? 0 : Round.L_HIT | Round.L_DOUBLE;
        StringBuilder b = new StringBuilder();
        for (int g = 0; g < s.groupHi.length; g++) {
            b.append(Round.MOVES.charAt(s.splitFirstMove(g, r, x, up, legal)));
        }
        if (x == r && r >= 2) {
            b.append(':');
            for (int g = 0; g < s.groupHi.length; g++) {
                b.append(Round.MOVES.charAt(s.splitFirstMove(g, r, x, up, legal | Round.L_SPLIT)));
            }
        }
        return b.toString();
    }

    /** A first answer written as B8 writes a cell, parts by key. */
    static String text(TreeMap<Integer, String> m) {
        StringBuilder b = new StringBuilder();
        m.forEach((k, v) -> b.append(b.length() == 0 ? "" : ";").append(k).append(':').append(v));
        return m.isEmpty() ? "-" : b.toString();
    }

    /** A first cell as the file gives it: "G:M:I" by key, empty for "-", null if it cannot be read. */
    static TreeMap<Integer, String> readFirst(String cell) {
        TreeMap<Integer, String> m = new TreeMap<>();
        if (cell.equals("-")) {
            return m;
        }
        for (String part : cell.split(";", -1)) {
            int colon = part.indexOf(':');
            try {
                if (colon < 0 || m.put(Integer.parseInt(part.substring(0, colon)), part.substring(colon + 1)) != null) {
                    return null;
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return m;
    }

    public static Result check(Path file, Bundle bundle, States states) throws IOException {
        return check(Files.readAllLines(file, StandardCharsets.UTF_8), bundle.strategyPaths(), bundle::strategyAt,
                bundle.chart, states);
    }

    public static Result check(List<String> text, List<String> paths, java.util.function.Function<String, Strategy> files,
                               Chart chart, States states) throws IOException {
        StringBuilder want = new StringBuilder(HEADER);
        for (String p : paths) {
            want.append('\t').append(p);
        }
        if (text.size() < 3 || !text.get(0).equals("# lookups.tsv: SHOE_SPEC.md B8, format 1")
                || !text.get(1).matches("# sample seed -?[0-9]+") || !text.get(2).equals(want.toString())) {
            throw new IOException("lookups.tsv: not B8's first three lines, with a column for each strategy file in path order");
        }
        List<Strategy> cols = new ArrayList<>();
        boolean full = false;
        for (String p : paths) {
            Strategy s = files.apply(p);
            cols.add(s);
            full |= "full".equals(s.scope);
        }
        long lines = 0;
        long cells = 0;
        long bad = 0;
        long splitCells = 0;
        long splitBad = 0;
        int splits = 0;
        List<String> first = new ArrayList<>();
        boolean laterSeen = false;
        for (int n = 3; n < text.size(); n++) {
            String[] f = text.get(n).split("\t", -1);
            String where = "lookups.tsv line " + (n + 1);
            boolean isFirst = f.length == 6 + cols.size() && f[0].equals("first") && f[5].equals("-");
            boolean isLater = f.length == 6 + cols.size() && f[0].equals("later") && f[5].matches("[1-9]|10");
            boolean isSplit = f.length == 6 + cols.size() && f[0].equals("split") && f[1].equals("-") && f[5].equals("-");
            if (!isFirst && !isLater && !isSplit || (isFirst || isLater) && splits > 0 || isFirst && laterSeen) {
                throw new IOException(where + ": not a first line (hit -), a later line (hit 1 to 10) or a split line"
                        + " (state and hit -), in that order");
            }
            if (isSplit) {
                if (!full) {
                    throw new IOException(where + ": a split line, but the bundle holds no file of scope full");
                }
                // B8's order: by up-card, then the split rank r (column p1), then the card x it drew (column p2).
                int up = splits / 100 + 1;
                int r = splits / 10 % 10 + 1;
                int x = splits % 10 + 1;
                if (splits >= SPLIT_LINES || !f[2].equals(Integer.toString(r)) || !f[3].equals(Integer.toString(x))
                        || !f[4].equals(Integer.toString(up))) {
                    throw new IOException(where + ": split line " + (splits + 1) + " of " + SPLIT_LINES + " is to be r "
                            + r + ", x " + x + ", up " + up + " (by up-card, then r, then x)");
                }
                splits++;
                lines++;
                for (int c = 0; c < cols.size(); c++) {
                    String mine = split(cols.get(c), r, x, up);
                    String cell = f[6 + c];
                    cells++;
                    splitCells++;
                    if (!mine.equals(cell)) {
                        bad++;
                        splitBad++;
                        if (first.size() < 10) {
                            first.add(where + " split " + r + " drawing " + x + " up " + up + ", " + paths.get(c)
                                    + ": the file says " + cell + ", the simulator " + mine);
                        }
                    }
                }
                continue;
            }
            laterSeen |= isLater;
            int i = Integer.parseInt(f[1]);
            int p1 = Integer.parseInt(f[2]);
            int p2 = Integer.parseInt(f[3]);
            int up = Integer.parseInt(f[4]);
            if (i < 0 || i >= states.n || p1 < 1 || p1 > p2 || p2 > 10 || up < 1 || up > 10) {
                throw new IOException(where + ": no such state or deal");
            }
            int[] dealt = dealt(states, i);
            int depth = states.depth[i];
            lines++;
            for (int c = 0; c < cols.size(); c++) {
                Strategy s = cols.get(c);
                String cell = f[6 + c];
                String mine;
                boolean ok;
                if (isFirst) {
                    TreeMap<Integer, String> m = first(s, chart, dealt, depth, p1, p2, up);
                    mine = text(m);
                    ok = m.equals(readFirst(cell));
                } else {
                    mine = later(s, chart, dealt, depth, p1, p2, up, Integer.parseInt(f[5]));
                    ok = mine.equals(cell);
                }
                cells++;
                if (!ok) {
                    bad++;
                    if (first.size() < 10) {
                        first.add(where + " state " + i + " deal " + p1 + "," + p2 + " up " + up
                                + (isLater ? " hit " + f[5] : "") + ", " + paths.get(c) + ": the file says " + cell
                                + ", the simulator " + mine);
                    }
                }
            }
        }
        if (full && splits != SPLIT_LINES) {
            throw new IOException("lookups.tsv: " + splits + " split lines, not " + SPLIT_LINES
                    + ", though the bundle holds a file of scope full (B8)");
        }
        return new Result(lines, cells, bad, first, splitCells, splitBad, full);
    }
}
