package sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SIM_CHECK.md's registry of figures, read from its fenced {@code sim-registry} block (section 2,
 * printed by the engine side's SimCheckPlan), which SHOE_SPEC.md's answer 9 has the simulator read
 * for the ids that key swings.tsv, records-FIG.tsv and fan-FIG.tsv (Part C): each figure's id, part,
 * row and penetration as the bundle names them, its figure as exact.tsv names it, its tests, and
 * whether its swings (SIM_CHECK.md section 9) and its paths (section 11) are kept.
 */
public final class Registry {

    static final String OPENING = "```sim-registry";
    static final String HEADER = "fig\tpart\trow\tpen\tfigure\ttests\tengine\tswings\tpaths";

    public record Fig(String id, int part, String row, int pen, String figure, List<String> tests, String engine,
                      boolean swings, boolean paths) {
    }

    public final List<Fig> figures = new ArrayList<>();

    public static Registry read(Path simCheck) throws IOException {
        return parse(Files.readAllLines(simCheck, StandardCharsets.UTF_8));
    }

    public static Registry parse(List<String> lines) throws IOException {
        int start = lines.indexOf(OPENING);
        if (start < 0 || start + 1 >= lines.size() || !lines.get(start + 1).equals(HEADER)) {
            throw new IOException("no sim-registry block with its header");
        }
        Registry r = new Registry();
        for (int i = start + 2; ; i++) {
            if (i >= lines.size()) {
                throw new IOException("the sim-registry block does not end");
            }
            String line = lines.get(i);
            if (line.equals("```")) {
                break;
            }
            String[] f = line.split("\t", -1);
            if (f.length != 9 || !f[0].matches("F[0-9]+") || !f[7].matches("[01]") || !f[8].matches("[01]")) {
                throw new IOException("sim-registry line " + (i - start) + ": " + line);
            }
            for (Fig g : r.figures) {
                if (g.id.equals(f[0])) {
                    throw new IOException("sim-registry: " + f[0] + " twice");
                }
            }
            r.figures.add(new Fig(f[0], Integer.parseInt(f[1]), f[2], Integer.parseInt(f[3]), f[4],
                    List.of(f[5].split(",")), f[6], f[7].equals("1"), f[8].equals("1")));
        }
        return r;
    }

    public Fig get(String id) {
        for (Fig g : figures) {
            if (g.id.equals(id)) {
                return g;
            }
        }
        return null;
    }

    /** The figure registered for this row, penetration and exact.tsv figure, or null. */
    public Fig of(String row, int pen, String figure) {
        for (Fig g : figures) {
            if (g.row.equals(row) && g.pen == pen && g.figure.equals(figure)) {
                return g;
            }
        }
        return null;
    }
}
