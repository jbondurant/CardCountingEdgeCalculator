package sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The files a P3 run writes beside its summary, from its sums: depth.tsv, the paired differences
 * from Hi-Lo, swings (SHOE_SPEC.md C1, assembled with P2's z values), ev-by-key.tsv (C4) and the
 * fans (C3). Formats of SHOE_SPEC.md Part C are written exactly as given there; depth.tsv's is the
 * simulator's own (sim/QUESTIONS.md).
 */
public final class Outputs {

    private Outputs() {
    }

    /** Writes the lines, each ending in \n, to a temporary file and renames it into place. */
    static void write(Path file, List<String> lines) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        StringBuilder b = new StringBuilder();
        for (String l : lines) {
            b.append(l).append('\n');
        }
        Files.writeString(tmp, b.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** P3's pooling weights of a row, swapped under the other-fold plant; {1, 0} for the chart. */
    static double[] weights(Row row, Exact exact, Plants plants) {
        if (row.instances() == 1) {
            return new double[]{1, 0};
        }
        boolean swap = plants.on(Plants.OTHER_FOLD);
        return new double[]{exact.foldWeight(row.name, row.pen, swap ? 1 : 0), exact.foldWeight(row.name, row.pen, swap ? 0 : 1)};
    }

    /** depth.tsv: the chart's result by band of 26 cards dealt, simulated with its SE, and exact from states.bin. */
    static List<String> depth(P3 p3, double[] sums, States states) {
        double[] w = new double[P3.BANDS];
        double[] wx = new double[P3.BANDS];
        States.Section chart = null;
        for (States.Section s : states == null ? List.<States.Section>of() : states.sections) {
            if (s.row.equals(Row.CHART) && s.pen == 80) {
                chart = s;
            }
        }
        for (int e = 0; chart != null && e < chart.index.length; e++) {
            int i = chart.index[e];
            w[states.depth[i] / 26] += 1 / states.q[i];
            wx[states.depth[i] / 26] += (chart.rvPlay[e] + chart.rvInsurance[e]) / states.q[i];
        }
        List<String> out = new ArrayList<>(List.of(
                "# depth.tsv: the chart's flat result a round by band of 26 cards dealt, simulated (P3, every round start of"
                        + " the chart's walk; SE the ratio estimator's over shoes) and exact (states.bin's chart section at 80%,"
                        + " weighted by 1/q; empty without one)",
                "band\tfrom\tto\trounds\tshoes\tsimulated\tse\texactWeight\texact"));
        for (int b = 0; b < P3.BANDS; b++) {
            double[] e = p3.band(sums, b);
            out.add(b + "\t" + 26 * b + "\t" + Math.min(26 * b + 25, Shoe.CUTS[2] - 1) + "\t" + (long) e[2] + "\t"
                    + (long) e[3] + "\t" + e[0] + "\t" + e[1] + "\t" + (chart == null ? "" : w[b]) + "\t"
                    + (chart == null ? "" : wx[b] / w[b]));
        }
        return out;
    }

    /** Every figure less Hi-Lo's at the same play, penetration and bets, shoe by shoe (SIM_CHECK.md section 9). */
    static List<Summary.Difference> differences(P3 p3, List<Row> rows, double[] sums, Exact exact, Plants plants) {
        List<Summary.Difference> out = new ArrayList<>();
        for (int i = 0; i < p3.pairs().size(); i++) {
            int[] p = p3.pairs().get(i);
            Row row = rows.get(p[0]);
            Row hl = rows.get(p[2]);
            double[] w = weights(row, exact, plants);
            double[] h = weights(hl, exact, plants);
            double[] d = p3.pair(sums, i, w[0], w[1], h[0], h[1]);
            double rounds = Moments.sum(sums, p[4], new double[]{0, 0, 0, 0, 1});
            out.add(new Summary.Difference("P3", row.name, row.pen, row.figures.get(p[1]), hl.name, d[0], d[1], d[2],
                    d[3], d[4], exact.estimate(row.name, row.pen, row.figures.get(p[1]))
                    - exact.estimate(hl.name, hl.pen, hl.figures.get(p[3])), rounds, Moments.shoes(sums, p[4])));
        }
        return out;
    }

    /** swings-p3.tsv: each kept figure's shoes and hist lines (C1) and its P3 z, from the comparisons. */
    static List<String> swingsP3(P3 p3, double[] sums, List<Summary.Comparison> comparisons, List<Row> rows) {
        List<String> out = new ArrayList<>();
        for (Swings s : p3.swings()) {
            out.addAll(s.lines(sums));
            Row row = rows.get(s.row);
            for (Summary.Comparison c : comparisons) {
                if (c.row().equals(row.name) && c.pen() == row.pen && c.figure().equals(row.figures.get(s.figure))) {
                    out.add("z\t" + s.fig.id() + "\tP3\t" + c.z());
                }
            }
        }
        return out;
    }

    /**
     * swings.tsv (C1), assembled from what is on disk: swings-p3.tsv's lines, each figure's P3 z
     * followed by its P2 z from swings-p2.tsv when P2 has run. Nothing is written before P3 has.
     */
    static void assembleSwings(Path out) throws IOException {
        Path p3 = out.resolve("swings-p3.tsv");
        if (!Files.exists(p3)) {
            return;
        }
        Path p2 = out.resolve("swings-p2.tsv");
        List<String> p2lines = Files.exists(p2) ? Files.readAllLines(p2, StandardCharsets.UTF_8) : List.of();
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(p3, StandardCharsets.UTF_8)) {
            lines.add(line);
            String[] f = line.split("\t", -1);
            if (f[0].equals("z") && f[2].equals("P3")) {
                for (String q : p2lines) {
                    if (q.startsWith("z\t" + f[1] + "\tP2\t")) {
                        lines.add(q);
                    }
                }
            }
        }
        write(out.resolve("swings.tsv"), lines);
    }

    /** ev-by-key.tsv (C4): its comment line, then each row's lines in C4's order. */
    static List<String> evByKey(P3 p3, double[] sums, States states, List<Row> rows) {
        List<String> out = new ArrayList<>(List.of("# ev-by-key.tsv: SHOE_SPEC.md C4, format 1"));
        for (EvByKey e : p3.evs()) {
            States.Section section = null;
            for (States.Section s : states == null ? List.<States.Section>of() : states.sections) {
                if (s.row.equals(e.name) && s.pen == 80) {
                    section = s;
                }
            }
            out.addAll(e.lines(sums, (long) sums[rows.get(e.row).offset], section, states));
        }
        return out;
    }

    /** fan-FIG.tsv (C3): a line a path, J then its cumulative wins. */
    static List<String> fan(double[][] paths) {
        List<String> out = new ArrayList<>();
        for (int j = 0; j < paths.length; j++) {
            StringBuilder b = new StringBuilder(Integer.toString(j));
            for (double w : paths[j]) {
                b.append('\t').append(w);
            }
            out.add(b.toString());
        }
        return out;
    }
}
