package sim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pilot of SIM_CHECK.md section 8 (INTEGRATION.md open item 3): 1% of every component of a
 * part's main run, on the same seeds and threads, measuring only what the scaling rule needs, and
 * writing it to {@code OUT/sim-pilot-partN.tsv} in the format section 8 gives for
 * {@code java SimCheckPlan fill}: tab-separated lines {@code part N}; {@code setup SECONDS};
 * {@code time P3 SECONDS}, {@code time P2 SECONDS} and {@code time extra SECONDS}, each at 1%
 * (extra 0 in part 1); {@code sd FIG σ_eff} for every figure of the part, by P3; {@code v FIG V}
 * for every one with a P2 test (those at 80%), V = Σ w_s²·v_s / (Σ w_s)²; and {@code exact FIG
 * SE_exact} for every one, from exact.tsv. Every number is in units of a one-unit bet, as
 * exact.tsv's and the summaries' are (σ_eff of the chart's flat figure near 1.16, section 8's
 * assumed SD), and is written as {@code Double.toString} writes it.
 *
 * <p>Blind: the pilot runs P3, P2 and the extras with every output that holds a simulated figure,
 * a difference or a z turned off ({@code blind} in {@link Sim}), keeps its chunks and records in
 * {@code OUT/pilot-partN.work}, and deletes that directory when it has written its file. What it
 * prints are the exact checks (setup) and the figures it could not measure.
 *
 * <p>What is timed, and how the extras are counted (sim/QUESTIONS.md item 25): setup is reading
 * the bundle and states.bin and the exact checks D1, D2, D4 and D5, which stop the part if one
 * fails (section 5). P3 is the p3 command at 1%: 38 chunks of 10,000 shoes (P3's first 380,000),
 * the records of its first two chunks, the fans at 1% of their rounds and the ruin paths at 1% of
 * their number. P2 is K = 1,200 on every state. In part 2 the extras are the variants command on
 * 4 chunks of 10,000 shoes, and the eight planted runs and the clean control, each P3 on one chunk
 * of 10,000 shoes (fans and ruin paths at 1%, as above) and P2 at K = 60; each planted run also
 * reads the bundle with its switch on and runs the exact checks, a fixed cost that does not grow
 * with the run's size, so it enters {@code time extra} divided by 100, and the rule's ×100 gives
 * it back once a run.
 */
public final class Pilot {

    /** SIM_CHECK.md section 8's full sizes. */
    static final int CHUNKS = 38;
    static final int CHUNK = 1_000_000;
    static final int K = 120_000;
    static final int SCALE = 100;
    static final int VARIANT_CHUNKS = 4;
    static final int PLANTED_CHUNKS = 1;
    static final int PLANTED_K = 6_000;
    static final String MARK = "pilot-work";

    private Pilot() {
    }

    static double since(long t0) {
        return (System.nanoTime() - t0) / 1e9;
    }

    /**
     * {@code pilot --part N --bundle DIR --out DIR --sim-commit SHA [--registry SIM_CHECK.md]
     * [--catalog counting-systems.json] [--threads T]}, and for tests the full sizes it takes 1% of
     * ({@code --chunks 38 --chunk-size 1000000 --k 120000 --planted-k 6000 --fan-paths 20
     * --fan-rounds 100000 --ruin-paths 20000 --scale 100}). False when an exact check failed (no
     * file is written) or a figure of the part could not be measured (the file lacks it).
     */
    public static boolean run(String[] args) throws Exception {
        int part = Integer.parseInt(Sim.required(args, "--part"));
        if (part != 1 && part != 2) {
            throw new IllegalArgumentException("--part is 1 or 2");
        }
        Path dir = Path.of(Sim.required(args, "--bundle"));
        Path out = Path.of(Sim.required(args, "--out"));
        String commit = Sim.required(args, "--sim-commit");
        Registry registry = Sim.registry(args);
        Path catalog = Path.of(Sim.option(args, "--catalog", "counting-systems.json"));
        Sizes sizes = new Sizes(args);
        Path work = out.resolve("pilot-part" + part + ".work");
        clear(work);
        Files.createDirectories(work);
        Files.writeString(work.resolve(MARK), "the pilot's own scratch directory, deleted when it ends\n");
        long t0 = System.nanoTime();
        Bundle bundle = new Bundle(dir, Plants.NONE);
        States states = bundle.states();
        P2 p2 = Sim.p2For(bundle, states, sizes.k, Plants.NONE);
        Map<String, String> status = new LinkedHashMap<>();
        boolean exact = Sim.exactChecks(bundle, states, p2, catalog, Plants.NONE, work, status);
        double setup = since(t0);
        Files.createDirectories(out);
        Files.copy(work.resolve("checks.txt"), out.resolve("pilot-part" + part + "-checks.txt"),
                StandardCopyOption.REPLACE_EXISTING);
        if (!exact) {
            clear(work);
            System.out.println("pilot: an exact check failed, so the part stops here (SIM_CHECK.md section 5); no pilot file");
            return false;
        }
        List<String> missing = new ArrayList<>();
        List<String> lines = measure(part, dir, bundle, states, p2, status, setup, work, commit, registry, catalog, sizes,
                missing);
        Path file = out.resolve("sim-pilot-part" + part + ".tsv");
        Outputs.write(file, lines);
        clear(work);
        System.out.println("pilot: " + file);
        if (!missing.isEmpty()) {
            System.out.println("pilot: not measured, their rows are not in this bundle: " + String.join(", ", missing)
                    + ". SimCheckPlan fill refuses a file that misses a figure.");
        }
        return missing.isEmpty();
    }

    /** The sizes the pilot runs: 1/scale of the full sizes, which tests may make small. */
    record Sizes(int threads, int scale, int chunks, int size, int k, int plantedK, int fanPaths, int fanRounds,
                 int ruinPaths) {
        Sizes(String[] args) {
            this(Integer.parseInt(Sim.option(args, "--threads", "1")),
                    Integer.parseInt(Sim.option(args, "--scale", Integer.toString(SCALE))), args);
        }

        private Sizes(int threads, int scale, String[] args) {
            this(threads, scale, Integer.parseInt(Sim.option(args, "--chunks", Integer.toString(CHUNKS))),
                    Integer.parseInt(Sim.option(args, "--chunk-size", Integer.toString(CHUNK))) / scale,
                    Integer.parseInt(Sim.option(args, "--k", Integer.toString(K))) / scale,
                    Integer.parseInt(Sim.option(args, "--planted-k", Integer.toString(PLANTED_K))) / scale,
                    Integer.parseInt(Sim.option(args, "--fan-paths", "20")),
                    Integer.parseInt(Sim.option(args, "--fan-rounds", "100000")) / scale,
                    Integer.parseInt(Sim.option(args, "--ruin-paths", "20000")) / scale);
            if (size < 1 || k < 2 || plantedK < 2) {
                throw new IllegalArgumentException("the sizes over --scale leave a chunk under one shoe or K under 2");
            }
        }
    }

    /**
     * After the exact checks passed: P3, P2 and, in part 2, the extras, timed, then the file's
     * lines. Each registered figure of the part the bundle cannot give is added to missing.
     */
    static List<String> measure(int part, Path dir, Bundle bundle, States states, P2 p2, Map<String, String> status,
                                double setup, Path work, String commit, Registry registry, Path catalog, Sizes z,
                                List<String> missing) throws Exception {
        long t0 = System.nanoTime();
        List<Summary.Comparison> p3 = Sim.p3(new Sim.P3Job(bundle, work.resolve("p3"), commit, z.chunks, z.size, z.threads,
                z.fanPaths, z.fanRounds, z.ruinPaths, Trip.HOURS, Trip.PACES, registry, Plants.NONE, false, true));
        double timeP3 = since(t0);
        t0 = System.nanoTime();
        List<Summary.Comparison> p2c = Sim.p2(bundle, states, p2, work.resolve("p2"), commit, z.k, z.threads, registry,
                Plants.NONE, status, true);
        double timeP2 = since(t0);
        double extra = 0;
        if (part == 2) {
            t0 = System.nanoTime();
            Sim.variants(bundle, work.resolve("variants"), commit, VARIANT_CHUNKS, z.size, z.threads, registry, Plants.NONE,
                    true);
            extra += since(t0);
            double fixed = 0;
            List<String> runs = new ArrayList<>(List.of("none"));
            runs.addAll(Plants.ALL);
            for (String plant : runs) {
                Plants plants = plant.equals("none") ? Plants.NONE : Plants.of(plant);
                Path o = work.resolve("plant-" + plant);
                t0 = System.nanoTime();
                Bundle pb = new Bundle(dir, plants);
                States ps = pb.states();
                P2 pp = Sim.p2For(pb, ps, z.plantedK, plants);
                Map<String, String> st = new LinkedHashMap<>();
                Sim.exactChecks(pb, ps, pp, catalog, plants, o, st);
                fixed += since(t0);
                t0 = System.nanoTime();
                Sim.p3(new Sim.P3Job(pb, o, commit, PLANTED_CHUNKS, z.size, z.threads, z.fanPaths, z.fanRounds, z.ruinPaths,
                        Trip.HOURS, Trip.PACES, registry, plants, false, true));
                Sim.p2(pb, ps, pp, o, commit, z.plantedK, z.threads, registry, plants, st, true);
                extra += since(t0);
            }
            extra += fixed / z.scale;
        }
        List<String> lines = new ArrayList<>(List.of("part\t" + part, "setup\t" + setup, "time\tP3\t" + timeP3,
                "time\tP2\t" + timeP2, "time\textra\t" + extra));
        List<Registry.Fig> figs = registry.figures.stream().filter(f -> f.part() == part).toList();
        for (Registry.Fig fig : figs) {
            Summary.Comparison c = find(p3, fig.id());
            if (c == null || !(c.rounds() > 0)) {
                missing.add(fig.id() + " (no P3)");
            } else {
                lines.add("sd\t" + fig.id() + "\t" + c.simulatedSe() * Math.sqrt(c.rounds()));
            }
        }
        for (Registry.Fig fig : figs) {
            if (fig.tests().contains("P2")) {
                Summary.Comparison c = find(p2c, fig.id());
                if (c == null) {
                    missing.add(fig.id() + " (no P2)");
                } else {
                    lines.add("v\t" + fig.id() + "\t" + c.simulatedSe() * c.simulatedSe() * z.k);
                }
            }
        }
        for (Registry.Fig fig : figs) {
            double se = bundle.exact.standardError(fig.row(), fig.pen(), fig.figure());
            if (Double.isNaN(se)) {
                missing.add(fig.id() + " (no exact.tsv lines)");
            } else {
                lines.add("exact\t" + fig.id() + "\t" + se);
            }
        }
        return lines;
    }

    static Summary.Comparison find(List<Summary.Comparison> comparisons, String fig) {
        for (Summary.Comparison c : comparisons) {
            if (c.fig().equals(fig)) {
                return c;
            }
        }
        return null;
    }

    /** Deletes the pilot's own scratch directory, and only a directory holding its mark. */
    static void clear(Path work) throws IOException {
        if (!Files.exists(work)) {
            return;
        }
        if (!Files.exists(work.resolve(MARK))) {
            throw new IOException(work + " exists and is not a pilot's scratch directory; refusing to delete it");
        }
        try (var walk = Files.walk(work)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
