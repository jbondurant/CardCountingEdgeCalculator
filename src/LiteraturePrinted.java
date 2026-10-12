import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads this repository's own printed reports as LiteratureCheck needs them: a markdown table
 * under a heading, its rows by their first cell (LITERATURE.md, "Where ours comes from").
 *
 * report-final-26f0724 kept no saved results beside its report, so the figures "ours" means in
 * LITERATURE.md (the house edge with a cut card, each count's BC, IC and PE, the differences from
 * Hi-Lo, the per-key means) exist only as printed, at the digits printed. LiteratureCheck takes
 * them as printed and says so beside each; it never prints more digits than the report did. The
 * full-play figures come the same way from FullIndexPeek's print of c06c061, which says its own
 * intervals are not yet widened, so none of its standard errors is used here.
 *
 * A table is found by the exact text of its heading line without the leading #s, so a report
 * whose headings change makes the check stop instead of reading another table.
 */
final class LiteraturePrinted {

    private LiteraturePrinted() {
    }

    static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    /**
     * One markdown table: its header's cells and its rows' cells, in order. Rows are kept as lists,
     * not maps, because report-final's headline tables name two columns "95% CI" (the estimate's
     * and the difference's), so a column is found by its name and which occurrence of that name.
     */
    static final class Table {
        final String heading;
        final List<String> header = new ArrayList<>();
        final List<List<String>> rows = new ArrayList<>();

        Table(String heading) {
            this.heading = heading;
        }

        /** The row whose first cell is exactly first, refusing none or two. */
        List<String> row(String first) {
            List<String> found = null;
            for (List<String> r : rows) {
                if (r.get(0).equals(first)) {
                    if (found != null) {
                        throw new IllegalArgumentException("\"" + heading + "\" has two rows " + first);
                    }
                    found = r;
                }
            }
            if (found == null) {
                throw new IllegalArgumentException("\"" + heading + "\" has no row " + first);
            }
            return found;
        }

        boolean has(String first) {
            for (List<String> r : rows) {
                if (r.get(0).equals(first)) {
                    return true;
                }
            }
            return false;
        }

        /** The index of the occurrence-th column (from 1) named column. */
        int column(String column, int occurrence) {
            int seen = 0;
            for (int k = 0; k < header.size(); k++) {
                if (header.get(k).equals(column) && ++seen == occurrence) {
                    return k;
                }
            }
            throw new IllegalArgumentException("\"" + heading + "\" has no column " + column
                    + (occurrence > 1 ? " (occurrence " + occurrence + ")" : "") + "; it has " + header);
        }

        String cell(String first, String column) {
            return cell(first, column, 1);
        }

        String cell(String first, String column, int occurrence) {
            return row(first).get(column(column, occurrence));
        }

        /** The first cell of every row, in order. */
        List<String> firsts() {
            List<String> out = new ArrayList<>();
            for (List<String> r : rows) {
                out.add(r.get(0));
            }
            return out;
        }
    }

    /**
     * The first table after the line whose text, with its leading #s and spaces removed, is exactly
     * heading (or starts with it, when heading ends in *: report-final's subheadings carry figures). A table is a run of lines starting "|", the second its separator. Refuses a heading
     * found twice or not at all, or no table before the next heading.
     */
    static Table table(List<String> lines, String heading) {
        return table(lines, heading, 0, lines.size());
    }

    /**
     * The first table under subheading inside the section headed section: from that section's
     * heading to the next heading of its level or above. The reports repeat subheadings such as
     * "80% penetration" in several sections, so a subheading alone would be ambiguous.
     */
    static Table table(List<String> lines, String section, String subheading) {
        int at = headingLine(lines, section, 0, lines.size());
        int level = level(lines.get(at));
        int end = lines.size();
        for (int i = at + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("#") && level(lines.get(i)) <= level) {
                end = i;
                break;
            }
        }
        return table(lines, subheading, at + 1, end);
    }

    private static int level(String line) {
        int k = 0;
        while (k < line.length() && line.charAt(k) == '#') {
            k++;
        }
        return k;
    }

    private static int headingLine(List<String> lines, String heading, int from, int to) {
        int at = -1;
        for (int i = from; i < to; i++) {
            String l = lines.get(i);
            String text = l.replaceFirst("^#+\\s*", "");
            if (l.startsWith("#") && (heading.endsWith("*") ? text.startsWith(heading.substring(0, heading.length() - 1))
                    : text.equals(heading))) {
                if (at >= 0) {
                    throw new IllegalArgumentException("the heading \"" + heading + "\" is in the report twice");
                }
                at = i;
            }
        }
        if (at < 0) {
            throw new IllegalArgumentException("no heading \"" + heading + "\" in the report");
        }
        return at;
    }

    private static Table table(List<String> lines, String heading, int from, int to) {
        int at = headingLine(lines, heading, from, to);
        Table t = new Table(heading);
        int i = at + 1;
        while (i < lines.size() && !lines.get(i).startsWith("|")) {
            if (lines.get(i).startsWith("#")) {
                throw new IllegalArgumentException("no table under \"" + heading + "\" before the next heading");
            }
            i++;
        }
        if (i >= lines.size()) {
            throw new IllegalArgumentException("no table under \"" + heading + "\"");
        }
        t.header.addAll(cells(lines.get(i)));
        i += 2;
        for (; i < lines.size() && lines.get(i).startsWith("|"); i++) {
            List<String> c = cells(lines.get(i));
            if (c.size() != t.header.size()) {
                throw new IllegalArgumentException("a row of \"" + heading + "\" has " + c.size() + " cells, not "
                        + t.header.size() + ": " + lines.get(i));
            }
            t.rows.add(c);
        }
        return t;
    }

    static List<String> cells(String line) {
        String s = line.trim();
        if (!s.startsWith("|") || !s.endsWith("|")) {
            throw new IllegalArgumentException("not a table row: " + line);
        }
        List<String> out = new ArrayList<>();
        for (String c : s.substring(1, s.length() - 1).split("\\|", -1)) {
            out.add(c.trim());
        }
        return out;
    }

    /** A printed estimate: "+0.294", "-0.660", "0.957". */
    static double number(String printed) {
        return LiteratureSources.number(printed);
    }

    /** A printed interval "[-0.671, -0.649]", with or without the trailing " b" of a basic interval. */
    static double[] interval(String printed) {
        String s = printed.trim();
        if (s.endsWith(" b")) {
            s = s.substring(0, s.length() - 2).trim();
        }
        if (!s.startsWith("[") || !s.endsWith("]")) {
            throw new IllegalArgumentException("not an interval as printed: " + printed);
        }
        String[] p = s.substring(1, s.length() - 1).split(",");
        if (p.length != 2) {
            throw new IllegalArgumentException("not an interval as printed: " + printed);
        }
        return new double[]{number(p[0]), number(p[1])};
    }
}
