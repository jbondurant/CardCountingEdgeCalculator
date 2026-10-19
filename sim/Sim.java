package sim;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * [--ruin-paths 20000] [--trip-hours 10,100,1000] [--trip-paces 50,80,100]}: check P3 on fresh
 * shoes of seed 20261008, every row of the bundle and the chart's own walk, in chunks under
 * {@code OUT/p3/} (a rerun resumes them), then {@code OUT/summary-p3.json} (with every figure's
 * paired difference from Hi-Lo's), {@code depth.tsv}, {@code ev-by-key.tsv}, {@code swings.tsv},
 * and for each registered figure with paths whose row the bundle has {@code records-FIG.tsv} (the
 * first two chunks' shoes), {@code fan-FIG.tsv}, {@code ruin-FIG.tsv} and {@code trip-FIG.tsv}.
 * With {@code --moving}, the whole run with the count moving through split hands, under
 * {@code p3-moving} (its summary and depth file only).
 * <li>{@code p2 --bundle DIR --out DIR --sim-commit SHA [--k K] [--threads T] [--plant a,b]
 * [--catalog counting-systems.json] [--registry SIM_CHECK.md]}: checks D1 and D4 on the bundle's
 * states.bin, D2 on keys-sample.tsv and D5 on lookups.tsv (OUT/checks.txt), then check P2 at K
 * re-deals a state (seed 20261009), in chunks of 1,000 states under {@code OUT/p2/}, then
 * {@code OUT/summary-p2.json} with P2's paired differences, and P2's z values into swings.tsv. It
 * stops before P2 if an exact check fails, unless a plant is on.
 * <li>{@code variants --bundle DIR --out DIR --sim-commit SHA [--chunks 4] [--chunk-size S]
 * [--threads T]}: mode B and the moving count beside mode A on P3's first chunks, for F26 to F29:
 * {@code OUT/variants.tsv} and {@code OUT/summary-variants.json}.
 * <li>{@code pilot --part N ...}: the blind pilot of SIM_CHECK.md section 8, which writes
 * {@code OUT/sim-pilot-partN.tsv} and nothing that holds a figure ({@link Pilot}).
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
            System.err.println("usage: java -cp out sim.Sim walk|d1|p3|p2|variants|pilot ...");
            System.exit(2);
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "walk" -> System.exit(walk(rest) ? 0 : 1);
            case "d1" -> System.exit(d1(rest) ? 0 : 1);
            case "p3" -> p3(rest);
            case "p2" -> System.exit(p2(rest) ? 0 : 1);
            case "variants" -> variants(rest);
            case "pilot" -> System.exit(Pilot.run(rest) ? 0 : 1);
            default -> {
                System.err.println("unknown command " + args[0]);
                System.exit(2);
            }
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

    static int[] ints(String list) {
        return Arrays.stream(list.split(",")).mapToInt(Integer::parseInt).toArray();
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

    static String loadAverage() {
        return Double.toString(java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
    }

    /**
     * What one P3 run is asked. A blind run (the pilot's) does all of a run's work and writes only
     * its chunks and the per-shoe records its ruin and trip paths read, never a summary, a swing,
     * a fan or anything else that holds a simulated figure, a difference or a z.
     */
    record P3Job(Bundle bundle, Path out, String commit, int chunks, int size, int threads, int fanPaths, int fanRounds,
                 int ruinPaths, int[] tripHours, int[] tripPaces, Registry registry, Plants plants, boolean moving,
                 boolean blind) {
    }

    static void p3(String[] args) throws Exception {
        Plants plants = plants(args);
        p3(new P3Job(new Bundle(Path.of(required(args, "--bundle")), plants), Path.of(required(args, "--out")),
                required(args, "--sim-commit"), Integer.parseInt(option(args, "--chunks", "38")),
                Integer.parseInt(option(args, "--chunk-size", "1000000")), Integer.parseInt(option(args, "--threads", "1")),
                Integer.parseInt(option(args, "--fan-paths", "20")), Integer.parseInt(option(args, "--fan-rounds", "100000")),
                Integer.parseInt(option(args, "--ruin-paths", "20000")),
                ints(option(args, "--trip-hours", "10,100,1000")), ints(option(args, "--trip-paces", "50,80,100")),
                registry(args), plants, Arrays.asList(args).contains("--moving"), false));
    }

    /**
     * Check P3: the run, then beside its chunks the summary (with the paired differences from
     * Hi-Lo), depth.tsv, ev-by-key.tsv, swings, and for each registered figure with paths whose row
     * the bundle has, the per-shoe records of the first two chunks, the fan, the ruin paths and the
     * trip blocks (SHOE_SPEC.md Part C, SIM_CHECK.md section 11). A bundle with no such figure, as
     * part 1's, writes none of those four (INTEGRATION.md open item 2). With --moving only the
     * summary and depth file, as p3-moving. Returns the comparisons.
     */
    static List<Summary.Comparison> p3(P3Job job) throws Exception {
        Bundle bundle = job.bundle;
        Path out = job.out;
        Plants plants = job.plants;
        Registry registry = job.registry;
        List<Row> rows = bundle.rows(true);
        long t0 = System.nanoTime();
        String name = job.moving ? "p3-moving" : "p3";
        long recordShoes = Math.min(2L * job.size, (long) job.chunks * job.size);
        Path parts = out.resolve(name + "-records");
        java.nio.file.Files.createDirectories(parts);
        P3 p3 = new P3(P3.SEED, rows, bundle.chart, plants).moving(job.moving)
                .extras(registry, job.moving ? null : parts, recordShoes);
        double[] sums = P3.chunked(p3, bundle, out.resolve(name), job.commit, job.chunks, job.size, job.threads, -1);
        States states = bundle.states();
        List<Summary.Comparison> comparisons = Summary.p3(rows, sums, bundle.exact, plants, registry);
        List<Summary.Difference> differences = Outputs.differences(p3, rows, sums, bundle.exact, plants, registry);
        Map<Path, List<String>> files = new LinkedHashMap<>();
        files.put(out.resolve(job.moving ? "p3-moving-depth.tsv" : "depth.tsv"), Outputs.depth(p3, sums, states));
        List<String> notes = new ArrayList<>();
        if (!job.moving) {
            files.put(out.resolve("ev-by-key.tsv"), Outputs.evByKey(p3, sums, states, rows));
            files.put(out.resolve("swings-p3.tsv"), Outputs.swingsP3(p3, sums, comparisons, rows));
            p3.writeRecords(out, job.size);
            for (Registry.Fig fig : registry.figures) {
                int r = p3.rowOf(fig.row(), fig.pen());
                if (!fig.paths() || r < 0) {
                    continue;
                }
                double[][] fan = P3.fan(P3.SEED, rows.get(r), rows.get(r).figures.indexOf(fig.figure()), bundle.chart,
                        plants, job.fanPaths, job.fanRounds, 100);
                files.put(out.resolve("fan-" + fig.id() + ".tsv"), Outputs.fan(fan));
                Ruin ruin = Ruin.read(out.resolve("records-" + fig.id() + ".tsv"));
                if (ruin.mu > 0) {
                    files.put(out.resolve("ruin-" + fig.id() + ".tsv"), ruin.lines(fig.id(), ruin.run(job.ruinPaths)));
                } else {
                    notes.add(fig.id() + ": the records' mean result is " + ruin.mu + ", so no ruin paths and no trip risk");
                }
                Trip trip = new Trip(ruin);
                files.put(out.resolve("trip-" + fig.id() + ".tsv"), trip.lines(fig.id(), trip.run(job.tripHours, job.tripPaces)));
            }
        }
        if (job.blind) {
            return comparisons;
        }
        for (Map.Entry<Path, List<String>> f : files.entrySet()) {
            Outputs.write(f.getKey(), f.getValue());
        }
        if (!job.moving) {
            Outputs.assembleSwings(out);
        }
        notes.forEach(System.out::println);
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("check", job.moving ? "P3, the count moving through split hands (reported, not tested)" : "P3");
        facts.put("simulatorCommit", job.commit);
        facts.put("bundleSha256", bundle.sha256);
        facts.put("chartFingerprint", bundle.chart.fingerprint());
        facts.put("seed", Long.toString(P3.SEED));
        facts.put("generator", "xoshiro256** seeded by SplitMix64: Xoshiro.of(seed, shoe, 0 cards / 1 coins, 0)");
        facts.put("shoes", Long.toString((long) job.chunks * job.size));
        facts.put("chunks", job.chunks + " of " + job.size + " shoes");
        facts.put("records", "shoes 0 to " + (recordShoes - 1) + (p3.recordIds().isEmpty() ? ", none kept: no registered"
                + " figure with paths among the bundle's rows" : ", for " + String.join(",", p3.recordIds())));
        facts.put("fan", job.fanPaths + " paths of " + job.fanRounds + " rounds, the win every 100");
        facts.put("ruin", job.ruinPaths + " paths a bankroll, seed " + Ruin.SEED);
        facts.put("trip", "hours " + Arrays.toString(job.tripHours) + " at paces " + Arrays.toString(job.tripPaces)
                + " rounds an hour, bankrolls B_25%, B_5% and B_1%");
        facts.put("threads", Integer.toString(job.threads));
        facts.put("wallSeconds", String.format("%.1f", (System.nanoTime() - t0) / 1e9));
        facts.put("loadAverage", loadAverage());
        facts.put("java", System.getProperty("java.version"));
        facts.put("plants", plants.describe());
        Summary.write(out.resolve("summary-" + name + ".json"), facts, comparisons, differences);
        System.out.println("P3: " + rows.size() + " rows; summary in " + out.resolve("summary-" + name + ".json"));
        return comparisons;
    }

    /**
     * Mode B and the moving count beside mode A (SIM_CHECK.md section 9), on P3's first 4 chunks by
     * default: variants.tsv and summary-variants.json, the paired differences shoe by shoe.
     */
    static void variants(String[] args) throws Exception {
        Plants plants = plants(args);
        variants(new Bundle(Path.of(required(args, "--bundle")), plants), Path.of(required(args, "--out")),
                required(args, "--sim-commit"), Integer.parseInt(option(args, "--chunks", "4")),
                Integer.parseInt(option(args, "--chunk-size", "1000000")), Integer.parseInt(option(args, "--threads", "1")),
                registry(args), plants, false);
    }

    /** The same on a bundle already read; a blind run (the pilot's) writes only its chunks. */
    static void variants(Bundle bundle, Path out, String commit, int chunks, int size, int threads, Registry registry,
                         Plants plants, boolean blind) throws Exception {
        long t0 = System.nanoTime();
        Variants v = new Variants(P3.SEED, bundle.rows(false), registry, bundle.chart, plants);
        double[] sums = v.chunked(bundle, out.resolve("variants"), commit, chunks, size, threads, -1);
        List<Summary.Difference> differences = new ArrayList<>();
        List<String> lines = v.lines(sums, bundle.exact, differences);
        if (blind) {
            return;
        }
        Outputs.write(out.resolve("variants.tsv"), lines);
        Map<String, String> facts = new LinkedHashMap<>();
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
        Summary.write(out.resolve("summary-variants.json"), facts, List.of(), differences);
        System.out.println("variants: " + v.ids + "; " + out.resolve("variants.tsv"));
    }

    /**
     * The exact checks a bundle allows (SIM_CHECK.md section 5): D1 and D4 on states.bin, D2 on
     * keys-sample.tsv from the catalog, D5 on lookups.tsv. Writes checks.txt and puts each check's
     * outcome ("passed", "failed" or "not run") into status under its name; true if all passed.
     */
    static boolean exactChecks(Bundle bundle, States states, P2 p2, Path catalogFile, Plants plants, Path out,
                               Map<String, String> status) throws Exception {
        States.D1 d1 = states.d1(new Round(Rules.montreal(plants)), bundle.chart);
        List<String> d4first = new ArrayList<>();
        long[] d4 = p2.d4(d4first);
        List<String> checks = new ArrayList<>();
        checks.add("D1\t" + d1.matched + " of " + states.n + " records card for card and in q to the bit; "
                + d1.valued + " valued by the walk; " + d1.mismatches + " mismatches");
        checks.addAll(d1.first);
        boolean one = d1.cardForCard();
        if (states.isMainFile()) {
            for (int p = 0; p < 3; p++) {
                one &= d1.rounds[p] == A8_ROUNDS[p];
                checks.add("D1\t" + Shoe.PENS[p] + "%: " + d1.rounds[p] + " round starts, A8 says " + A8_ROUNDS[p]);
            }
        }
        status.put("D1", one ? "passed" : "failed");
        Path keys = bundle.dir.resolve("keys-sample.tsv");
        if (java.nio.file.Files.exists(keys)) {
            KeysSample.Result d2 = KeysSample.check(keys, Catalog.read(catalogFile), bundle.strategies.values(), states, plants);
            checks.add("D2\t" + (d2.cells() - d2.mismatches()) + " of " + d2.cells() + " keys equal to keys-sample.tsv's on "
                    + d2.lines() + " lines; " + d2.mismatches() + " mismatches; catalog counts missing from its header: "
                    + d2.missing());
            // The columns left out (SHOE_SPEC.md B4), on a line of their own, always written, so that the
            // line above keeps its form: their number, then each name (sim/QUESTIONS.md item 33).
            checks.add(KeysSample.leftOutLine(d2.leftOut()));
            checks.addAll(d2.first());
            status.put("D2", d2.passes() ? "passed" : "failed");
        } else {
            checks.add("D2\tnot run: the bundle has no keys-sample.tsv");
            status.put("D2", "not run");
        }
        checks.add("D4\t" + (d4[0] - d4[1]) + " of " + d4[0] + " bet keys and bets equal to states.bin's; " + d4[1] + " mismatches");
        checks.addAll(d4first);
        status.put("D4", d4[1] == 0 ? "passed" : "failed");
        Path lookups = bundle.dir.resolve("lookups.tsv");
        if (java.nio.file.Files.exists(lookups)) {
            Lookups.Result d5 = Lookups.check(lookups, bundle, states);
            checks.add("D5\t" + (d5.cells() - d5.mismatches()) + " of " + d5.cells() + " lookups equal to lookups.tsv's on "
                    + d5.lines() + " lines; " + d5.mismatches() + " mismatches");
            // The split cells, counted apart (SHOE_SPEC.md B8, SIM_CHECK.md section 5), on a line of their
            // own so that the line above reads as it did before split lines existed: the full-play files'
            // columns of the split lines, then the other files' columns there, each "-".
            checks.add("D5\tsplit cells, the full-play files' columns of " + d5.splitLines() + " split lines: "
                    + (d5.splitCells() - d5.splitMismatches()) + " of " + d5.splitCells() + " equal to lookups.tsv's; "
                    + d5.splitMismatches() + " mismatches; the other files' columns there, each -: "
                    + (d5.splitOtherCells() - d5.splitOtherMismatches()) + " of " + d5.splitOtherCells()
                    + " equal; the bundle holds " + (d5.fullPlay() ? "a" : "no") + " file of scope full");
            checks.addAll(d5.first());
            status.put("D5", d5.passes() ? "passed" : "failed");
        } else {
            checks.add("D5\tnot run: the bundle has no lookups.tsv");
            status.put("D5", "not run");
        }
        checks.add("D3\tengine side: test/SimVsExactSmallShoeTest.java; D6: sim/test/P3Test, chunks on 1 and 6 threads");
        checks.add("plants\t" + plants.describe());
        java.nio.file.Files.createDirectories(out);
        Outputs.write(out.resolve("checks.txt"), checks);
        checks.forEach(System.out::println);
        return status.values().stream().allMatch("passed"::equals);
    }

    /** P2 on the bundle's rows at 80% that states.bin has (SIM_CHECK.md section 2: P2 tests the figures at 80% only). */
    static P2 p2For(Bundle bundle, States states, int k, Plants plants) {
        List<Row> at80 = new ArrayList<>();
        for (Row r : bundle.rows(true)) {
            if (r.pen == 80) {
                at80.add(r);
            }
        }
        return new P2(P2.SEED, k, states, at80, bundle.chart, plants);
    }

    /** Checks D1, D2, D4 and D5, then P2; false if an exact check failed (P2 is then not run, unless a plant is on). */
    static boolean p2(String[] args) throws Exception {
        Plants plants = plants(args);
        Bundle bundle = new Bundle(Path.of(required(args, "--bundle")), plants);
        Path out = Path.of(required(args, "--out"));
        int k = Integer.parseInt(option(args, "--k", "120000"));
        Registry registry = registry(args);
        States states = bundle.states();
        P2 p2 = p2For(bundle, states, k, plants);
        Map<String, String> status = new LinkedHashMap<>();
        boolean exact = exactChecks(bundle, states, p2, Path.of(option(args, "--catalog", "counting-systems.json")), plants,
                out, status);
        if (!exact && !plants.any()) {
            System.out.println("an exact check failed: P2 is not run");
            return false;
        }
        // A planted run goes on to P2 whatever the exact checks found: SIM_CHECK.md section 10
        // reports whether the z tests alone would have caught the bug.
        p2(bundle, states, p2, out, required(args, "--sim-commit"), k, Integer.parseInt(option(args, "--threads", "1")),
                registry, plants, status, false);
        return exact;
    }

    /** P2 itself on a bundle whose exact checks have run (status); a blind run (the pilot's) writes only its chunks. */
    static List<Summary.Comparison> p2(Bundle bundle, States states, P2 p2, Path out, String commit, int k, int threads,
                                       Registry registry, Plants plants, Map<String, String> status, boolean blind)
            throws Exception {
        long t0 = System.nanoTime();
        double[] sums = p2.chunked(out.resolve("p2"), commit, bundle.sha256, 1000, threads, -1);
        List<Summary.Comparison> comparisons = p2.comparisons(sums, registry);
        List<Summary.Difference> differences = p2.differences(sums, registry);
        if (blind) {
            return comparisons;
        }
        List<String> z = new ArrayList<>();
        for (Registry.Fig fig : registry.figures) {
            for (Summary.Comparison c : comparisons) {
                if (fig.swings() && fig.tests().contains("P2") && c.fig().equals(fig.id())) {
                    z.add("z\t" + fig.id() + "\tP2\t" + c.z());
                }
            }
        }
        Outputs.write(out.resolve("swings-p2.tsv"), z);
        Outputs.assembleSwings(out);
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("check", "P2");
        facts.put("simulatorCommit", commit);
        facts.put("bundleSha256", bundle.sha256);
        facts.put("chartFingerprint", bundle.chart.fingerprint());
        facts.put("seed", Long.toString(P2.SEED));
        facts.put("k", Integer.toString(k));
        facts.put("states", Integer.toString(states.n));
        boolean all = status.values().stream().allMatch("passed"::equals);
        facts.put("exactChecks", all ? "all passed" : "failed (see checks.txt)");
        status.forEach(facts::put);
        facts.put("threads", Integer.toString(threads));
        facts.put("wallSeconds", String.format("%.1f", (System.nanoTime() - t0) / 1e9));
        facts.put("loadAverage", loadAverage());
        facts.put("java", System.getProperty("java.version"));
        facts.put("plants", plants.describe());
        Summary.write(out.resolve("summary-p2.json"), facts, comparisons, differences);
        System.out.println("P2: " + p2.rows().size() + " rows; summary in " + out.resolve("summary-p2.json"));
        return comparisons;
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
