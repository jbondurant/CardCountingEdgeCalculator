package sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine's side of every comparison, from the bundle's exact.tsv (SHOE_SPEC.md B5): for each
 * strategy file and figure, the test fold's per-shoe sums. From them come the engine's figure,
 * pooled over the two folds, its standard error with the fit held fixed, and the folds' test
 * weights that pool the simulator's two fold instances in check P3.
 */
public final class Exact {

    static final String HEADER = "row\tpen\tfold\tfigure\tmetric\tshoes\tsumA\tsumB\tsumAA\tsumAB\tsumBB";

    /** One line: a fold's sums of a = Σ w·x and b = Σ w over its shoes. */
    public record Line(String row, int pen, int fold, String figure, int metric, long shoes,
                       double sumA, double sumB, double sumAA, double sumAB, double sumBB) {
    }

    private final Map<String, Line> lines = new LinkedHashMap<>();

    static String key(String row, int pen, int fold, String figure) {
        return row + "\t" + pen + "\t" + fold + "\t" + figure;
    }

    static Exact parse(List<String> text, String name) throws IOException {
        Exact e = new Exact();
        if (text.isEmpty() || !text.get(0).equals("# exact.tsv: SHOE_SPEC.md B5, format 1")) {
            throw new IOException(name + ": line 1 is not B5's");
        }
        int i = 1;
        while (i < text.size() && text.get(i).startsWith("#")) {
            i++;
        }
        if (i >= text.size() || !text.get(i).equals(HEADER)) {
            throw new IOException(name + ": no B5 header");
        }
        for (i++; i < text.size(); i++) {
            String[] f = text.get(i).split("\t", -1);
            try {
                if (f.length != 11) {
                    throw new NumberFormatException(f.length + " fields");
                }
                Line l = new Line(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3], Integer.parseInt(f[4]),
                        Long.parseLong(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7]),
                        Double.parseDouble(f[8]), Double.parseDouble(f[9]), Double.parseDouble(f[10]));
                if (e.lines.put(key(l.row, l.pen, l.fold, l.figure), l) != null) {
                    throw new IOException(name + " line " + (i + 1) + ": a second line for the same file and figure");
                }
            } catch (NumberFormatException ex) {
                throw new IOException(name + " line " + (i + 1) + ": cannot read '" + text.get(i) + "'", ex);
            }
        }
        return e;
    }

    public static Exact read(Path file) throws IOException {
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8), file.toString());
    }

    public Line line(String row, int pen, int fold, String figure) {
        return lines.get(key(row, pen, fold, figure));
    }

    /** The engine's figure, both folds pooled: R = (sumA0 + sumA1) / (sumB0 + sumB1); NaN if a fold is missing. */
    public double estimate(String row, int pen, String figure) {
        Line a = line(row, pen, 0, figure);
        Line b = line(row, pen, 1, figure);
        if (a == null || b == null) {
            return Double.NaN;
        }
        return (a.sumA + b.sumA) / (a.sumB + b.sumB);
    }

    /**
     * B5's standard error of R with the fit held fixed: the ratio estimator's, shoes as the
     * independent units, sqrt(n/(n − 1) · Σ(sumAA − 2R·sumAB + R²·sumBB)) / Σ sumB, the sums over
     * both folds and n their shoes together.
     */
    public double standardError(String row, int pen, String figure) {
        Line a = line(row, pen, 0, figure);
        Line b = line(row, pen, 1, figure);
        if (a == null || b == null) {
            return Double.NaN;
        }
        double r = estimate(row, pen, figure);
        long n = a.shoes + b.shoes;
        double s = 0;
        for (Line l : new Line[]{a, b}) {
            s += l.sumAA - 2 * r * l.sumAB + r * r * l.sumBB;
        }
        return Math.sqrt((double) n / (n - 1) * s) / (a.sumB + b.sumB);
    }

    /** P3's pooling weight of fold f: its test weight over both folds', from the flat figure's sumB. */
    public double foldWeight(String row, int pen, int fold) {
        Line a = line(row, pen, 0, "flat");
        Line b = line(row, pen, 1, "flat");
        if (a == null || b == null) {
            return Double.NaN;
        }
        return (fold == 0 ? a.sumB : b.sumB) / (a.sumB + b.sumB);
    }
}
