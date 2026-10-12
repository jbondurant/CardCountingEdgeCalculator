package sim;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * The simulator's command line. {@code javac -d out sim/*.java}, then {@code java -cp out sim.Sim
 * COMMAND ...}:
 * <ul>
 * <li>{@code walk [--chart chart.tsv] [--shoes N]}: check D1's totals. Walks the main file's
 * first N shoes (all 60,000 by default) with the chart and prints the round starts, valued states
 * and weights at the three cuts; with all 60,000 it compares them with SHOE_SPEC.md A8. Without
 * {@code --chart} it plays {@link Chart#textbook()}, the stand-in.
 * <li>{@code d1 --states states.bin [--chart chart.tsv]}: check D1 in full. Walks every shoe the
 * file's header names and holds each state it values to the file's next record, card for card
 * and in q to the bit; for the main file, also the round starts to A8.
 * <li>{@code p3 --bundle DIR --out DIR --sim-commit SHA [--chunks N] [--chunk-size S] [--threads T]
 * [--plant a,b] [--moving] [--registry SIM_CHECK.md] [--fan-paths 20] [--fan-rounds 100000]
 * [--ruin-paths 20000]}: check P3 on fresh shoes of seed 20261008, every row of the bundle and the
 * chart's own walk, in chunks under {@code OUT/p3/} (a rerun resumes them), then
 * {@code OUT/summary-p3.json} (with every figure's paired difference from Hi-Lo's),
 * {@code depth.tsv}, {@code ev-by-key.tsv}, {@code swings.tsv}, and for each figure with paths
 * {@code records-FIG.tsv} (the first two chunks' shoes), {@code fan-FIG.tsv} and
 * {@code ruin-FIG.tsv}. With {@code --moving}, the whole run with the count moving through split
 * hands, under {@code p3-moving} (its summary and depth file only).
 * <li>{@code p2 --bundle DIR --out DIR --sim-commit SHA [--k K] [--threads T] [--plant a,b]
 * [--catalog counting-systems.json] [--registry SIM_CHECK.md]}: checks D1 and D4 on the bundle's
 * states.bin, D2 on keys-sample.tsv and D5 on lookups.tsv (OUT/checks.txt), then check P2 at K
 * re-deals a state (seed 20261009), in chunks of 1,000 states under {@code OUT/p2/}, then
 * {@code OUT/summary-p2.json} with P2's paired differences, and P2's z values into swings.tsv. It
 * stops before P2 if an exact check fails, unless a plant is on.
 * <li>{@code variants --bundle DIR --out DIR --sim-commit SHA [--chunks 4] [--chunk-size S]
 * [--threads T]}: mode B and the moving count beside mode A on P3's first chunks, for F26 to F29:
 * {@code OUT/variants.tsv} and {@code OUT/summary-variants.json}.
 * </ul>
 *
 * <p>Tests: {@code javac -d out-test -cp out:lib/junit-platform-console-standalone-1.10.2.jar
 * sim/test/*.java}, then one class at a time, from the repository's root: {@code java -jar
 * lib/junit-platform-console-standalone-1.10.2.jar --class-path out:out-test --select-class
 * sim.WalkTest}.
 */
public final class Sim {

    /** SHOE_SPEC.md A2's rounds.*, valued.* and weight.* lines: what the 60,000 main shoes must give. */
    public static final long[] A8_ROUNDS = {3_197_853L, 3_426_592L, 3_644_432L};
    public static final long[] A8_VALUED = {45_495L, 52_611L, 60_743L};
    public static final long[] A8_WEIGHT = {3_222_940L, 3_453_206L, 3_667_722L};

    private Sim() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: java -cp out sim.Sim walk [--chart chart.tsv] [--shoes N]");
            System.exit(2);
        }
        switch (args[0]) {
            case "walk":
                System.exit(walk(Arrays.copyOfRange(args, 1, args.length)) ? 0 : 1);
                break;
            case "d1":
                System.exit(d1(Arrays.copyOfRange(args, 1, args.length)) ? 0 : 1);
                break;
            case "p3":
                p3(Arrays.copyOfRange(args, 1, args.length));
                break;
            case "p2":
                System.exit(p2(Arrays.copyOfRange(args, 1, args.length)) ? 0 : 1);
                break;
            case "variants":
                variants(Arrays.copyOfRange(args, 1, args.length));
                break;
            default:
                System.err.println("unknown command " + args[0]);
                System.exit(2);
        }
    }

    static String option(String[] args, String name, String otherwise) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return otherwise;
    }

    static Plants plants(String[] args) {
        String list = option(args, "--plant", "");
        return list.isEmpty() ? Plants.NONE : Plants.of(list.split(","));
    }

    static String required(String[] args, String name) {
        String v = option(args, name, null);
        if (v == null) {
            throw new IllegalArgumentException("missing " + name);
        }
        return v;
    }

    /** The registry of SIM_CHECK.md section 2 (SHOE_SPEC.md answer 9): --registry, by default SIM_CHECK.md here. */
    static Registry registry(String[] args) throws java.io.IOException {
        return Registry.read(Path.of(option(args, "--registry", "SIM_CHECK.md")));
    }

    /**
     * Check P3: the run, then beside its chunks the summary (with the paired differences from
     * Hi-Lo), depth.tsv, ev-by-key.tsv, swings, the per-shoe records of the first two chunks and
     * the fans (SHOE_SPEC.md Part C). With --moving only the summary and depth file, as p3-moving.
     */
    static void p3(String[] args) throws Exception {
        Plants plants = plants(args);
        Bundle bundle = new Bundle(Path.of(required(args, "--bundle")), plants);
        Path out = Path.of(required(args, "--out"));
        String commit = required(args, "--sim-commit");
        int chunks = Integer.parseInt(option(args, "--chunks", "38"));
        int size = Integer.parseInt(option(args, "--chunk-size", "1000000"));
        int threads = Integer.parseInt(option(args, "--threads", "1"));
        int fanPaths = Integer.parseInt(option(args, "--fan-paths", "20"));
        int fanRounds = Integer.parseInt(option(args, "--fan-rounds", "100000"));
        int ruinPaths = Integer.parseInt(option(args, "--ruin-paths", "20000"));
        Registry registry = registry(args);
        java.util.List<Row> rows = bundle.rows(true);
        long t0 = System.nanoTime();
        boolean moving = Arrays.asList(args).contains("--moving");
        String name = moving ? "p3-moving" : "p3";
        long recordShoes = Math.min(2L * size, (long) chunks * size);
        Path parts = out.resolve(name + "-records");
        java.nio.file.Files.createDirectories(parts);
        P3 p3 = new P3(P3.SEED, rows, bundle.chart, plants).moving(moving).extras(registry, moving ? null : parts, recordShoes);
        double[] sums = P3.chunked(p3, bundle, out.resolve(name), commit, chunks, size, threads, -1);
        States states = bundle.states();
        java.util.List<Summary.Comparison> comparisons = Summary.p3(rows, sums, bundle.exact, plants, registry);
        Outputs.write(out.resolve(moving ? "p3-moving-depth.tsv" : "depth.tsv"), Outputs.depth(p3, sums, states));
        if (!moving) {
            Outputs.write(out.resolve("ev-by-key.tsv"), Outputs.evByKey(p3, sums, states, rows));
            Outputs.write(out.resolve("swings-p3.tsv"), Outputs.swingsP3(p3, sums, comparisons, rows));
            Outputs.assembleSwings(out);
            p3.writeRecords(out, size);
            for (Registry.Fig fig : registry.figures) {
                int r = p3.rowOf(fig.row(), fig.pen());
                if (fig.paths() && r >= 0) {
                    double[][] fan = P3.fan(P3.SEED, rows.get(r), rows.get(r).figures.indexOf(fig.figure()), bundle.chart,
                            plants, fanPaths, fanRounds, 100);
                    Outputs.write(out.resolve("fan-" + fig.id() + ".tsv"), Outputs.fan(fan));
                    Ruin ruin = Ruin.read(out.resolve("records-" + fig.id() + ".tsv"));
                    if (ruin.mu > 0) {
                        Outputs.write(out.resolve("ruin-" + fig.id() + ".tsv"), ruin.lines(fig.id(), ruin.run(ruinPaths)));
                    } else {
                        System.out.println(fig.id() + ": the records' mean result is " + ruin.mu + ", so no ruin paths");
                    }
                }
            }
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        java.util.Map<String, String> facts = new java.util.LinkedHashMap<>();
        facts.put("check", moving ? "P3, the count moving through split hands (reported, not tested)" : "P3");
        facts.put("simulatorCommit", commit);
        facts.put("bundleSha256", bundle.sha256);
        facts.put("chartFingerprint", bundle.chart.fingerprint());
        facts.put("seed", Long.toString(P3.SEED));
        facts.put("generator", "xoshiro256** seeded by SplitMix64: Xoshiro.of(seed, shoe, 0 cards / 1 coins, 0)");
        facts.put("shoes", Long.toString((long) chunks * size));
        facts.put("chunks", chunks + " of " + size + " shoes");
        facts.put("records", "shoes 0 to " + (recordShoes - 1));
        facts.put("fan", fanPaths + " paths of " + fanRounds + " rounds, the win every 100");
        facts.put("ruin", ruinPaths + " paths a bankroll, seed " + Ruin.SEED);
        facts.put("threads", Integer.toString(threads));
        facts.put("wallSeconds", String.format("%.1f", seconds));
        facts.put("loadAverage", Double.toString(java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                .getSystemLoadAverage()));
        facts.put("java", System.getProperty("java.version"));
        facts.put("plants", plants.describe());
        Summary.write(out.resolve("summary-" + name + ".json"), facts, comparisons,
                Outputs.differences(p3, rows, sums, bundle.exact, plants));
        System.out.println("P3: " + rows.size() + " rows; summary in " + out.resolve("summary-" + name + ".json"));
    }

    /**
     * Mode B and the moving count beside mode A (SIM_CHECK.md section 9), on P3's first 4 chunks by
     * default: variants.tsv and summary-variants.json, the paired differences shoe by shoe.
     */
    static void variants(String[] args) throws Exception {
        Plants plants = plants(args);
        Bundle bundle = new Bundle(Path.of(required(args, "--bundle")), plants);
        Path out = Path.of(required(args, "--out"));
        String commit = required(args, "--sim-commit");
        int chunks = Integer.parseInt(option(args, "--chunks", "4"));
        int size = Integer.parseInt(option(args, "--chunk-size", "1000000"));
        int threads = Integer.parseInt(option(args, "--threads", "1"));
        long t0 = System.nanoTime();
        Variants v = new Variants(P3.SEED, bundle.rows(false), registry(args), bundle.chart, plants);
        double[] sums = v.chunked(bundle, out.resolve("variants"), commit, chunks, size, threads, -1);
        java.util.List<Summary.Difference> differences = new java.util.ArrayList<>();
        Outputs.write(out.resolve("variants.tsv"), v.lines(sums, bundle.exact, differences));
        java.util.Map<String, String> facts = new java.util.LinkedHashMap<>();
        facts.put("check", "mode B and the moving count beside mode A (reported, not tested)");
        facts.put("simulatorCommit", commit);
        facts.put("bundleSha256", bundle.sha256);
        facts.put("chartFingerprint", bundle.chart.fingerprint());
        facts.put("seed", Long.toString(P3.SEED));
        facts.put("figures", String.join(",", v.ids));
        facts.put("shoes", Long.toString((long) chunks * size));
        facts.put("chunks", chunks + " of " + size + " shoes");
        facts.put("threads", Integer.toString(threads));
        facts.put("wallSeconds", String.format("%.1f", (System.nanoTime() - t0) / 1e9));
        facts.put("java", System.getProperty("java.version"));
        facts.put("plants", plants.describe());
        Summary.write(out.resolve("summary-variants.json"), facts, java.util.List.of(), differences);
        System.out.println("variants: " + v.ids + "; " + out.resolve("variants.tsv"));
    }

    /**
     * The exact checks a bundle allows (SIM_CHECK.md section 5): D1 and D4 on states.bin, D2 on
     * keys-sample.tsv from the catalog, D5 on lookups.tsv. Writes checks.txt; true if all passed.
     */
    static boolean exactChecks(Bundle bundle, States states, P2 p2, Path catalogFile, Plants plants, Path out)
            throws Exception {
        States.D1 d1 = states.d1(new Round(Rules.montreal(plants)), bundle.chart);
        java.util.List<String> d4first = new java.util.ArrayList<>();
        long[] d4 = p2.d4(d4first);
        java.util.List<String> checks = new java.util.ArrayList<>();
        checks.add("D1\t" + d1.matched + " of " + states.n + " records card for card and in q to the bit; "
                + d1.valued + " valued by the walk; " + d1.mismatches + " mismatches");
        checks.addAll(d1.first);
        boolean ok = d1.cardForCard();
        if (states.isMainFile()) {
            for (int p = 0; p < 3; p++) {
                ok &= d1.rounds[p] == A8_ROUNDS[p];
                checks.add("D1\t" + Shoe.PENS[p] + "%: " + d1.rounds[p] + " round starts, A8 says " + A8_ROUNDS[p]);
            }
        }
        Path keys = bundle.dir.resolve("keys-sample.tsv");
        if (java.nio.file.Files.exists(keys)) {
            KeysSample.Result d2 = KeysSample.check(keys, Catalog.read(catalogFile), bundle.strategies.values(), states, plants);
            checks.add("D2\t" + (d2.cells() - d2.mismatches()) + " of " + d2.cells() + " keys equal to keys-sample.tsv's on "
                    + d2.lines() + " lines; " + d2.mismatches() + " mismatches; catalog counts missing from its header: "
                    + d2.missing());
            checks.addAll(d2.first());
            ok &= d2.passes();
        } else {
            checks.add("D2\tnot run: the bundle has no keys-sample.tsv");
            ok = false;
        }
        checks.add("D4\t" + (d4[0] - d4[1]) + " of " + d4[0] + " bet keys and bets equal to states.bin's; " + d4[1] + " mismatches");
        checks.addAll(d4first);
        ok &= d4[1] == 0;
        Path lookups = bundle.dir.resolve("lookups.tsv");
        if (java.nio.file.Files.exists(lookups)) {
            Lookups.Result d5 = Lookups.check(lookups, bundle, states);
            checks.add("D5\t" + (d5.cells() - d5.mismatches()) + " of " + d5.cells() + " lookups equal to lookups.tsv's on "
                    + d5.lines() + " lines; " + d5.mismatches() + " mismatches");
            checks.addAll(d5.first());
            ok &= d5.passes();
        } else {
            checks.add("D5\tnot run: the bundle has no lookups.tsv");
            ok = false;
        }
        checks.add("D3\tengine side: test/SimVsExactSmallShoeTest.java; D6: sim/test/P3Test, chunks on 1 and 6 threads");
        checks.add("plants\t" + plants.describe());
        java.nio.file.Files.createDirectories(out);
        Outputs.write(out.resolve("checks.txt"), checks);
        checks.forEach(System.out::println);
        return ok;
    }

    /** Checks D1, D2, D4 and D5, then P2; false if an exact check failed (P2 is then not run, unless a plant is on). */
    static boolean p2(String[] args) throws Exception {
        Plants plants = plants(args);
        Bundle bundle = new Bundle(Path.of(required(args, "--bundle")), plants);
        Path out = Path.of(required(args, "--out"));
        String commit = required(args, "--sim-commit");
        int k = Integer.parseInt(option(args, "--k", "120000"));
        int threads = Integer.parseInt(option(args, "--threads", "1"));
        Registry registry = registry(args);
        States states = bundle.states();
        // SIM_CHECK.md section 2: P2 tests the figures at 80% only, where every valued state is in play.
        java.util.List<Row> at80 = new java.util.ArrayList<>();
        for (Row r : bundle.rows(true)) {
            if (r.pen == 80) {
                at80.add(r);
            }
        }
        P2 p2 = new P2(P2.SEED, k, states, at80, bundle.chart, plants);
        boolean exact = exactChecks(bundle, states, p2, Path.of(option(args, "--catalog", "counting-systems.json")), plants, out);
        if (!exact && !plants.any()) {
            System.out.println("an exact check failed: P2 is not run");
            return false;
        }
        // A planted run goes on to P2 whatever the exact checks found: SIM_CHECK.md section 10
        // reports whether the z tests alone would have caught the bug.
        long t0 = System.nanoTime();
        double[] sums = p2.chunked(out.resolve("p2"), commit, bundle.sha256, 1000, threads, -1);
        java.util.List<Summary.Comparison> comparisons = p2.comparisons(sums, registry);
        java.util.List<String> z = new java.util.ArrayList<>();
        for (Registry.Fig fig : registry.figures) {
            for (Summary.Comparison c : comparisons) {
                if (fig.swings() && fig.tests().contains("P2") && c.fig().equals(fig.id())) {
                    z.add("z\t" + fig.id() + "\tP2\t" + c.z());
                }
            }
        }
        Outputs.write(out.resolve("swings-p2.tsv"), z);
        Outputs.assembleSwings(out);
        java.util.Map<String, String> facts = new java.util.LinkedHashMap<>();
        facts.put("check", "P2");
        facts.put("simulatorCommit", commit);
        facts.put("bundleSha256", bundle.sha256);
        facts.put("chartFingerprint", bundle.chart.fingerprint());
        facts.put("seed", Long.toString(P2.SEED));
        facts.put("k", Integer.toString(k));
        facts.put("states", Integer.toString(states.n));
        facts.put("exactChecks", exact ? "all passed" : "failed (see checks.txt)");
        facts.put("threads", Integer.toString(threads));
        facts.put("wallSeconds", String.format("%.1f", (System.nanoTime() - t0) / 1e9));
        facts.put("java", System.getProperty("java.version"));
        facts.put("plants", plants.describe());
        Summary.write(out.resolve("summary-p2.json"), facts, comparisons, p2.differences(sums));
        System.out.println("P2: " + p2.rows().size() + " rows; summary in " + out.resolve("summary-p2.json"));
        return exact;
    }

    /** Check D1 against a states.bin: true when every state matches and, for the main file, the rounds are A8's. */
    static boolean d1(String[] args) throws Exception {
        String statesFile = option(args, "--states", null);
        if (statesFile == null) {
            throw new IllegalArgumentException("d1 needs --states states.bin");
        }
        String chartFile = option(args, "--chart", null);
        Chart chart = chartFile == null ? Chart.textbook() : Chart.load(Path.of(chartFile));
        States states = States.read(Path.of(statesFile));
        States.D1 d = states.d1(new Round(Rules.montreal()), chart);
        boolean ok = d.cardForCard();
        System.out.println("D1: " + d.matched + " of " + states.n + " records matched card for card and in q to the bit; "
                + d.valued + " states valued by the walk; " + d.mismatches + " mismatches");
        for (String m : d.first) {
            System.out.println("  " + m);
        }
        if (states.isMainFile()) {
            for (int p = 0; p < 3; p++) {
                boolean match = d.rounds[p] == A8_ROUNDS[p];
                ok &= match;
                System.out.println(Shoe.PENS[p] + "%: " + d.rounds[p] + " round starts" + (match ? " = A8" : ", A8 says " + A8_ROUNDS[p]));
            }
        } else {
            System.out.println("not the main file's header: the round starts are not held to A8");
        }
        return ok;
    }

    /** Walks the shoes and, for all 60,000, holds the totals to A8; true when they match or were not compared. */
    static boolean walk(String[] args) throws Exception {
        String chartFile = option(args, "--chart", null);
        Chart chart = chartFile == null ? Chart.textbook() : Chart.load(Path.of(chartFile));
        int shoes = Integer.parseInt(option(args, "--shoes", Integer.toString(Shoe.SHOES_MAIN)));
        long t0 = System.nanoTime();
        Walk.Totals totals = Walk.mainTotals(0, shoes, new Round(Rules.montreal()), chart);
        double seconds = (System.nanoTime() - t0) / 1e9;
        System.out.println("chart " + (chartFile == null ? "textbook stand-in" : chartFile)
                + ", fingerprint " + chart.fingerprint() + "; shoes 0 to " + (shoes - 1)
                + " of seed " + Shoe.SEED_MAIN + "; " + String.format("%.1f", seconds) + " s");
        boolean all = shoes == Shoe.SHOES_MAIN;
        boolean ok = true;
        for (int p = 0; p < 3; p++) {
            long w = Math.round(totals.weight[p]);
            String line = Shoe.PENS[p] + "% (cut " + Shoe.CUTS[p] + "): rounds " + totals.rounds[p]
                    + ", valued " + totals.valued[p] + ", weight " + w;
            if (all) {
                boolean match = totals.rounds[p] == A8_ROUNDS[p] && totals.valued[p] == A8_VALUED[p] && w == A8_WEIGHT[p];
                ok &= match;
                line += match ? "  = A8" : "  differs from A8: rounds " + A8_ROUNDS[p] + ", valued "
                        + A8_VALUED[p] + ", weight " + A8_WEIGHT[p];
            }
            System.out.println(line);
        }
        System.out.println("fewest cards left at a round start " + totals.fewestLeft
                + "; most cards one round took " + totals.longestRound);
        return ok;
    }
}
