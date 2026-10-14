package sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The comparisons a run makes, and the files it writes for the engine-side reader: every figure
 * the simulator estimated, beside the engine's figure and SE from the bundle, with the difference
 * and z (SIM_CHECK.md section 6). The pass rule (|z| < 3.5, flagged from 2.5) is applied here only
 * for the reader's convenience; the reader recomputes every z from the sums and holds the run to
 * the registered list, which the simulator does not know.
 */
public final class Summary {

    /** One figure compared by one check. NaN where the bundle has no engine figure for it. */
    public record Comparison(String check, String row, int pen, String figure, double exact, double exactSe,
                             double simulated, double simulatedSe, double rounds, double shoes, double unseen, String fig) {
        public double difference() {
            return simulated - exact;
        }

        public double combinedSe() {
            return Math.sqrt(simulatedSe * simulatedSe + (Double.isNaN(exactSe) ? 0 : exactSe * exactSe));
        }

        public double z() {
            return difference() / combinedSe();
        }

        public boolean passes() {
            return Math.abs(z()) < 3.5;
        }

        public boolean flagged() {
            return Math.abs(z()) >= 2.5 && Math.abs(z()) < 3.5;
        }
    }

    /**
     * A paired difference, reported and never tested (SIM_CHECK.md section 9): {@code row}'s figure
     * less {@code less}'s on the same shoes or states, with its SE and σ_eff of the per-round
     * difference (P3), or the engine's difference on the same states (P2). {@code fig} and
     * {@code lessFig} are the two figures' registered ids ("" for one the registry does not list),
     * so a reader finds SIM_CHECK.md section 9's "F25 less F18" and "F29 less F27" by id, for the
     * rounds a simulation would need (INTEGRATION.md open item 10). For mode B and the moving count
     * both are the figure's own id, {@code less} saying which variant less mode A.
     */
    public record Difference(String check, String row, int pen, String figure, String less, double difference,
                             double se, double sigmaEff, double first, double second, double exact, double rounds,
                             double shoes, String fig, String lessFig) {
    }

    private Summary() {
    }

    /** The registered id of a figure, or "" when the registry has none for it (or there is no registry). */
    static String fig(Registry registry, String row, int pen, String figure) {
        Registry.Fig f = registry == null ? null : registry.of(row, pen, figure);
        return f == null ? "" : f.id();
    }

    /** P3's comparisons: every row and figure, the folds pooled by exact.tsv's test weights (swapped under the other-fold plant). */
    public static List<Comparison> p3(List<Row> rows, double[] sums, Exact exact, Plants plants) {
        return p3(rows, sums, exact, plants, null);
    }

    /** The same, each comparison named by its registered id where it has one. */
    public static List<Comparison> p3(List<Row> rows, double[] sums, Exact exact, Plants plants, Registry registry) {
        List<Comparison> out = new ArrayList<>();
        boolean swap = plants.on(Plants.OTHER_FOLD);
        for (Row row : rows) {
            double w0 = 1;
            double w1 = 0;
            if (row.instances() == 2) {
                w0 = exact.foldWeight(row.name, row.pen, swap ? 1 : 0);
                w1 = exact.foldWeight(row.name, row.pen, swap ? 0 : 1);
            }
            for (int j = 0; j < row.figures.size(); j++) {
                String fig = row.figures.get(j);
                double[] e = row.estimate(sums, j, w0, w1);
                double unseen = j == 0 ? 0 : row.unseen(sums, 0, j) + (row.instances() == 2 ? row.unseen(sums, 1, j) : 0);
                out.add(new Comparison("P3", row.name, row.pen, fig, exact.estimate(row.name, row.pen, fig),
                        exact.standardError(row.name, row.pen, fig), e[0], e[1], e[2], e[3], unseen,
                        fig(registry, row.name, row.pen, fig)));
            }
        }
        return out;
    }

    static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }

    static String num(double d) {
        return Double.isFinite(d) ? Double.toString(d) : "null";
    }

    /** summary.json: the run's facts, then every comparison with its sums' results. */
    public static void write(Path file, Map<String, String> facts, List<Comparison> comparisons) throws IOException {
        write(file, facts, comparisons, List.of());
    }

    /** summary.json with the paired differences after the comparisons. */
    public static void write(Path file, Map<String, String> facts, List<Comparison> comparisons,
                             List<Difference> differences) throws IOException {
        StringBuilder b = new StringBuilder("{\n");
        for (Map.Entry<String, String> f : facts.entrySet()) {
            b.append("  ").append(json(f.getKey())).append(": ").append(json(f.getValue())).append(",\n");
        }
        b.append("  \"comparisons\": [");
        for (int i = 0; i < comparisons.size(); i++) {
            Comparison c = comparisons.get(i);
            b.append(i == 0 ? "\n" : ",\n").append("    {\"check\": ").append(json(c.check()))
                    .append(", \"row\": ").append(json(c.row())).append(", \"pen\": ").append(c.pen())
                    .append(", \"figure\": ").append(json(c.figure())).append(", \"exact\": ").append(num(c.exact()))
                    .append(", \"exactSe\": ").append(num(c.exactSe())).append(", \"simulated\": ").append(num(c.simulated()))
                    .append(", \"simulatedSe\": ").append(num(c.simulatedSe())).append(", \"rounds\": ").append(num(c.rounds()))
                    .append(", \"shoes\": ").append(num(c.shoes())).append(", \"difference\": ").append(num(c.difference()))
                    .append(", \"z\": ").append(num(c.z())).append(", \"pass\": ").append(c.passes())
                    .append(", \"flagged\": ").append(c.flagged()).append(", \"unseenKeyRounds\": ").append(num(c.unseen()))
                    .append(", \"fig\": ").append(json(c.fig())).append("}");
        }
        b.append("\n  ],\n  \"differences\": [");
        for (int i = 0; i < differences.size(); i++) {
            Difference d = differences.get(i);
            b.append(i == 0 ? "\n" : ",\n").append("    {\"check\": ").append(json(d.check()))
                    .append(", \"row\": ").append(json(d.row())).append(", \"pen\": ").append(d.pen())
                    .append(", \"figure\": ").append(json(d.figure())).append(", \"less\": ").append(json(d.less()))
                    .append(", \"difference\": ").append(num(d.difference())).append(", \"se\": ").append(num(d.se()))
                    .append(", \"sigmaEff\": ").append(num(d.sigmaEff())).append(", \"first\": ").append(num(d.first()))
                    .append(", \"second\": ").append(num(d.second())).append(", \"exact\": ").append(num(d.exact()))
                    .append(", \"rounds\": ").append(num(d.rounds())).append(", \"shoes\": ").append(num(d.shoes()))
                    .append(", \"fig\": ").append(json(d.fig())).append(", \"lessFig\": ").append(json(d.lessFig()))
                    .append("}");
        }
        b.append(differences.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
        Files.writeString(file, b.toString(), StandardCharsets.UTF_8);
    }
}
