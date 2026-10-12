package sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * A small export bundle in SHOE_SPEC.md B0's layout, for the tests: the stand-in chart written as
 * chart.tsv, Hi-Lo's two fold files at 80% from {@link StrategyFixture}, an exact.tsv whose sums
 * are made up (they test the arithmetic, not the engine), a states.bin from {@link StatesFixture},
 * index.tsv and manifest.sha256.
 */
final class BundleFixture {

    static final String HILO = "Hi-Lo = Hi-Lo Lite";

    static Path write(Path dir) throws IOException {
        Files.createDirectories(dir);
        Chart chart = Chart.textbook();
        List<String> c = new ArrayList<>();
        c.add("# chart.tsv: SHOE_SPEC.md B1, format 1");
        c.add("# fingerprint " + chart.fingerprint());
        c.add("up\ttotal\tsoft\tpair\tfromSplit\tfirst\tlegal\tmove");
        int[][] decisions = ShoeSpec.chartDecisions();
        for (int i = 0; i < decisions.length; i++) {
            int[] d = decisions[i];
            c.add(d[0] + "\t" + d[1] + "\t" + d[2] + "\t" + d[3] + "\t" + d[4] + "\t" + d[5] + "\t" + d[6] + "\t"
                    + Round.MOVES.charAt(chart.move(i)));
        }
        write(dir.resolve("chart.tsv"), c);
        TreeMap<String, String[]> index = new TreeMap<>();
        for (int fold = 0; fold < 2; fold++) {
            StrategyFixture f = hiLo("first", fold);
            String path = "strategy-hi-lo-hi-lo-lite-p80-f" + fold + ".txt";
            write(dir.resolve(path), f.lines());
            index.put(path, new String[]{HILO, "80", Integer.toString(fold), "first"});
        }
        List<String> e = new ArrayList<>(List.of("# exact.tsv: SHOE_SPEC.md B5, format 1", Exact.HEADER));
        for (int fold = 0; fold < 2; fold++) {
            double b = fold == 0 ? 1_800_000 : 1_867_722;
            e.add(HILO + "\t80\t" + fold + "\tflat\t0\t30000\t" + (-0.002 * b) + "\t" + b + "\t9000\t-120\t1.2e8");
            e.add(HILO + "\t80\t" + fold + "\tev:same-8\t26\t30000\t" + (0.004 * b) + "\t" + b + "\t40000\t300\t1.2e8");
            e.add(HILO + "\t80\t" + fold + "\tev:X-1,8\t-1\t30000\t" + (0.001 * b) + "\t" + b + "\t20000\t100\t1.2e8");
            for (int pen : Shoe.PENS) {
                e.add(Row.CHART + "\t" + pen + "\t" + fold + "\tflat\t0\t30000\t" + (-0.0066 * b) + "\t" + b + "\t8000\t-150\t1.2e8");
            }
        }
        write(dir.resolve("exact.tsv"), e);
        StatesFixture states = new StatesFixture(40);
        states.plans = new ArrayList<>(List.of("same-8", "X-1,8"));
        Files.write(dir.resolve("states.bin"), states.bytes());
        index.put("chart.tsv", null);
        index.put("exact.tsv", null);
        index.put("states.bin", null);
        List<String> idx = new ArrayList<>(List.of("path\tbytes\trow\tpen\tfold\tscope"));
        for (var en : index.entrySet()) {
            String[] v = en.getValue();
            idx.add(en.getKey() + "\t" + Files.size(dir.resolve(en.getKey())) + "\t"
                    + (v == null ? "-\t-\t-\t-" : String.join("\t", v)));
        }
        write(dir.resolve("index.tsv"), idx);
        index.put("index.tsv", null);
        List<String> manifest = new ArrayList<>();
        for (String path : index.keySet()) {
            manifest.add(Bundle.sha256(dir.resolve(path)) + "  " + path);
        }
        write(dir.resolve("manifest.sha256"), manifest);
        return dir;
    }

    /** Hi-Lo's fold file of the fixture bundle, in a scope: the two folds group keys differently and bet differently. */
    static StrategyFixture hiLo(String scope, int fold) {
        StrategyFixture f = TableTest.varied(scope);
        f.fold = fold;
        if (fold == 1) {
            // The other fold's fit groups the keys differently, so the folds play differently.
            f.groupHi = new int[]{-1, 1, 3, 99};
        }
        f.insured.clear();
        f.insured.add("2 10 10");
        f.plans.add("same-8 same -");
        f.plans.add("X-1,8 own -1");
        for (int k = -4; k <= 4; k++) {
            f.bets.add("same-8 " + k + " " + Math.max(1, Math.min(8, 2 * k + (fold == 0 ? 1 : 2))) + " 1");
        }
        for (int k = -2; k <= 2; k++) {
            f.bets.add("X-1,8 " + k + " " + (k <= 0 ? 1 : 4) + " " + (k == 0 ? "0.5" : "1"));
        }
        return f;
    }

    static void write(Path file, List<String> lines) throws IOException {
        StringBuilder b = new StringBuilder();
        for (String l : lines) {
            b.append(l).append('\n');
        }
        Files.writeString(file, b.toString(), StandardCharsets.UTF_8);
    }
}
