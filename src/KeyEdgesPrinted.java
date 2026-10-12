import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Check 9 of PLAN_COMPUTE.md 5.4, the first half: run A's per-key tables set beside the ones
 * report-final printed (runs/report-final-26f0724.md, "Per key, from the test folds"), at the
 * digits it printed: the key, the share of rounds to four decimals and the mean round value to
 * three decimals of a percent. It is reported, not passed or failed. Check 1 already holds run A's
 * tables to c06c061's results.ser to the bit; this shows a reader that the tables they can read
 * in the published report are the same ones, line by line, and names any line that is not.
 *
 * A line is printed here exactly as ComparisonReport.keyTables printed it (%d, %.4f, and
 * ComparisonReport.pct), so a match is a match of the text, not of a rounding of our own.
 */
final class KeyEdgesPrinted {

    private KeyEdgesPrinted() {
    }

    /** The heading of report-final's section of per-key tables. */
    static final String SECTION = "## Per key, from the test folds";

    /** One printed line of a per-key table: the key, the share of rounds and the mean round value, as text. */
    static final class Line {
        final String key;
        final String share;
        final String edge;

        Line(String key, String share, String edge) {
            this.key = key;
            this.share = share;
            this.edge = edge;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Line && key.equals(((Line) o).key) && share.equals(((Line) o).share)
                    && edge.equals(((Line) o).edge);
        }

        @Override
        public int hashCode() {
            return key.hashCode() * 31 * 31 + share.hashCode() * 31 + edge.hashCode();
        }

        @Override
        public String toString() {
            return key + " | " + share + " | " + edge;
        }
    }

    /**
     * The per-key tables of a report as ComparisonReport printed them, by "entry name|penetration"
     * ("Hi-Lo = Hi-Lo Lite|80%"), in the order printed. A heading is "### NAME, PEN"; a name may
     * hold commas, so the penetration is what follows the last one.
     */
    static Map<String, List<Line>> parse(List<String> lines) {
        Map<String, List<Line>> out = new LinkedHashMap<>();
        boolean in = false;
        List<Line> table = null;
        for (String l : lines) {
            if (l.equals(SECTION)) {
                in = true;
                continue;
            }
            if (!in) {
                continue;
            }
            if (l.startsWith("## ")) {
                break;
            }
            if (l.startsWith("### ")) {
                int c = l.lastIndexOf(", ");
                if (c < 0) {
                    throw new IllegalArgumentException("a per-key heading without a penetration: " + l);
                }
                table = new ArrayList<>();
                String id = l.substring(4, c) + "|" + l.substring(c + 2);
                if (out.put(id, table) != null) {
                    throw new IllegalArgumentException("two per-key tables of " + id);
                }
                continue;
            }
            if (table == null || !l.startsWith("| ") || l.startsWith("| Key ")) {
                continue;
            }
            String[] cell = l.split("\\|");
            // "| k | share | rv | ...": cell[0] is empty, before the first bar.
            table.add(new Line(cell[1].trim(), cell[2].trim(), cell[3].trim()));
        }
        return out;
    }

    static Map<String, List<Line>> parse(Path md) throws IOException {
        Map<String, List<Line>> t = parse(Files.readAllLines(md, StandardCharsets.UTF_8));
        if (t.isEmpty()) {
            throw new IllegalArgumentException(md + " holds no section \"" + SECTION + "\"");
        }
        return t;
    }

    /** A table of ours as ComparisonReport would print its first three columns. */
    static List<Line> printed(KeyEdges.Table t) {
        List<Line> out = new ArrayList<>();
        for (int k = 0; k < t.keys(); k++) {
            out.add(new Line(Integer.toString(t.sums.key[k]), String.format(Locale.ROOT, "%.4f", t.share(k)),
                    ComparisonReport.pct(t.edge(k))));
        }
        return out;
    }

    /** What the comparison found: counts of tables and lines, and each difference, named. */
    static final class Comparison {
        int tables;
        int lines;
        int same;
        /** Printed tables with no entry of that name and penetration among ours. */
        final List<String> notOurs = new ArrayList<>();
        /** Each line that differs, or is printed on one side only: "table: printed X, ours Y". */
        final List<String> differ = new ArrayList<>();

        Map<String, Object> json() {
            return KeyEdgesReport.map("source", "report-final's per-key tables, at the digits printed",
                    "tables", tables, "lines", lines, "same", same, "tablesNotScored", new ArrayList<>(notOurs),
                    "differences", new ArrayList<>(differ));
        }

        String summary() {
            return String.format(Locale.ROOT, "printed per-key tables: %d compared, %d of %d lines the same at the digits"
                    + " printed, %d lines differ, %d printed tables not among ours", tables, same, lines,
                    differ.size(), notOurs.size());
        }
    }

    /**
     * Each printed table set beside ours of the same entry name and penetration, from the first of
     * the collectors that holds it (run A's, then the further rows'), line by line in key order.
     */
    static Comparison compare(Map<String, List<Line>> printed, List<KeyEdges.Collector> ours) {
        Comparison c = new Comparison();
        for (Map.Entry<String, List<Line>> e : printed.entrySet()) {
            String[] id = e.getKey().split("\\|");
            int p = penetration(id[1]);
            KeyEdges.Collector col = p < 0 ? null : KeyEdgesReport.holding(ours, id[0], p);
            if (col == null) {
                c.notOurs.add(e.getKey());
                continue;
            }
            c.tables++;
            List<Line> was = e.getValue();
            List<Line> now = printed(KeyEdges.table(col.values(id[0], p)));
            int n = Math.max(was.size(), now.size());
            for (int k = 0; k < n; k++) {
                Line a = k < was.size() ? was.get(k) : null;
                Line b = k < now.size() ? now.get(k) : null;
                c.lines++;
                if (a != null && a.equals(b)) {
                    c.same++;
                } else {
                    c.differ.add(e.getKey() + ": printed " + a + ", ours " + b);
                }
            }
        }
        return c;
    }

    /** The index of a penetration as SystemScorer.PENETRATIONS names it, or -1. */
    static int penetration(String label) {
        for (int p = 0; p < SystemScorer.PENETRATIONS.length; p++) {
            if (SystemScorer.PENETRATIONS[p].equals(label)) {
                return p;
            }
        }
        return -1;
    }
}
