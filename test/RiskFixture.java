import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Files for RiskReport's tests, written as the real ones are: a results.ser of the five counts
 * (FullIndexRun's format), step 7's save with the chart row (CeilingReport's), a pilot's Results
 * section, and the simulator's swings, records and fan in the formats RiskReport's class comment
 * fixes. Every number in them is made up here, from seeds, so that the tests know what each figure
 * must come to; none is a result.
 */
final class RiskFixture {

    static final String STATES = "states /fixture/montreal-80.states, 1 bytes, modified 0";
    static final String RESAMPLES = "resamples 1000, seed 20260929, M_play 5.0";

    /** The win rate a round each fixture count has at full play, 1-8, by penetration. */
    static final Map<String, double[]> FULL_MU = new LinkedHashMap<>();

    static {
        FULL_MU.put(RiskReport.HI_LO, new double[]{-0.0005, 0.0012, 0.0036});
        FULL_MU.put(RiskReport.KO, new double[]{-0.0002, 0.0015, 0.0038});
        FULL_MU.put(RiskReport.ZEN, new double[]{0.0004, 0.0021, 0.0044});
        FULL_MU.put(RiskReport.HI_OPT, new double[]{0.0005, 0.0023, 0.0047});
        FULL_MU.put(RiskReport.VICTOR, new double[]{0.0006, 0.0024, 0.0047});
    }

    /** 1,000 resamples around mu, with SD sd, drawn from a seed. */
    static double[] boot(double mu, double sd, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] b = new double[Bootstrap.RESAMPLES];
        for (int r = 0; r < b.length; r++) {
            // A sum of uniforms: near normal, and the same on every machine.
            double u = 0;
            for (int k = 0; k < 12; k++) {
                u += rnd.nextDouble();
            }
            b[r] = mu + sd * (u - 6);
        }
        return b;
    }

    /** An entry whose every penetration has the figures given for FLAT and same(8), and a small key table. */
    static SystemScorer.Entry entry(String name, CountKeys.Count c, boolean full, double[] same8, long seed) {
        SystemScorer.Entry e = new SystemScorer.Entry(name, c, false, full);
        for (int p = 0; p < 3; p++) {
            e.estimate[p] = new double[SystemScorer.METRICS];
            e.boot[p] = new double[SystemScorer.METRICS][];
            for (int m = 0; m < SystemScorer.METRICS; m++) {
                e.boot[p][m] = boot(0, 0, 1);
            }
            e.estimate[p][SystemScorer.FLAT] = -0.004 + 0.001 * p;
            e.boot[p][SystemScorer.FLAT] = boot(e.estimate[p][SystemScorer.FLAT], 0.0002, seed + 10 * p);
            e.estimate[p][SystemScorer.same(8)] = same8[p];
            e.boot[p][SystemScorer.same(8)] = boot(same8[p], 0.00018, seed + 10 * p + 1);
            e.estimate[p][SystemScorer.sameBet(8)] = 1.5;
            // Keys: shares 0.8, 0.15, 0.05 at bets 1, 2 and 8 of the 1-8 same schedule (column 3 + 8):
            // E[b] = 0.8 + 0.3 + 0.4 = 1.5, E[b^2] = 0.8 + 0.6 + 3.2 = 4.6.
            double[][] keys = new double[3][1 + SystemScorer.DETAIL];
            double[] share = {0.8, 0.15, 0.05};
            double[] bet = {1, 2, 8};
            for (int k = 0; k < 3; k++) {
                keys[k][0] = k;
                keys[k][1] = share[k];
                keys[k][3 + 8] = bet[k];
            }
            e.keys[p] = keys;
        }
        return e;
    }

    static CountKeys.Count count(String name) {
        for (CountKeys.Count c : CountKeys.catalog()) {
            if (c.name.equals(name)) {
                return c;
            }
        }
        throw new IllegalArgumentException("the catalog has no " + name);
    }

    /** The five counts' Results, each with its first-decision entry and its full-play entries. */
    static List<SystemScorer.Result> results() {
        List<SystemScorer.Result> out = new ArrayList<>();
        String[][] counts = {{RiskReport.HI_LO, null}, {RiskReport.KO, null}, {RiskReport.ZEN, null},
                {"Hi-Opt II", RiskReport.HI_OPT}, {"Victor Advanced Point Count (Victor APC)", RiskReport.VICTOR}};
        long seed = 100;
        for (String[] c : counts) {
            CountKeys.Count base = count(c[0]);
            SystemScorer.Result r = new SystemScorer.Result(base);
            double[] first = {-0.0008, 0.0009, 0.0029};
            r.entries.add(entry(base.name, base, false, first, seed++ * 100));
            String row = c[1] == null ? c[0] : c[1];
            CountKeys.Count played = c[1] == null ? base : CountKeys.sideCountVariant(base);
            if (c[1] != null) {
                r.fullEntries.add(entry(base.name + ", full play", base, true, new double[]{0, 0, 0.001}, seed++ * 100));
            }
            r.fullEntries.add(entry(row + ", full play", played, true, FULL_MU.get(row), seed++ * 100));
            out.add(r);
        }
        return out;
    }

    static void writeResults(Path file, String identity, List<SystemScorer.Result> results) throws IOException {
        try (ObjectOutputStream o = new ObjectOutputStream(Files.newOutputStream(file))) {
            o.writeUTF(identity);
            o.writeObject(new ArrayList<>(results));
            o.writeObject(null);
            o.writeObject(new ArrayList<>());
        }
    }

    static String identity(String what) {
        return what + "\ncommit fixture\n" + STATES + "\nshoes 60000, valued states 60743\n" + RESAMPLES + "\n";
    }

    /** Step 7's save, with the chart's reference row and nothing else. */
    static void writeStep7(Path file, String identity, double chartFlat) throws IOException {
        SystemScorer.Entry chart = entry(RiskReport.CHART, null, false, new double[]{0, 0, 0}, 7);
        chart.estimate[2][SystemScorer.FLAT] = chartFlat;
        chart.boot[2][SystemScorer.FLAT] = boot(chartFlat, 0.00006, 77);
        try (ObjectOutputStream o = new ObjectOutputStream(Files.newOutputStream(file))) {
            o.writeUTF(identity);
            o.writeObject(new ArrayList<SystemScorer.Result>());
            o.writeObject(new ArrayList<SystemScorer.Entry>());
            o.writeObject(new ArrayList<SystemScorer.Entry>());
            o.writeObject(new ArrayList<String>());
            o.writeDouble(0);
            o.writeObject(new ArrayList<>(List.of(chart, entry("Perfect first move and insurance, flat", null, false,
                    new double[]{0, 0, 0}, 8))));
            o.writeObject(new LinkedHashMap<String, double[]>());
        }
    }

    /** A pilot's output: a table before its Results that must be ignored, then the Results. */
    static List<String> pilot() {
        String head = "| Count | Estimate, full play | SE, first decisions only | SE, later plays held | "
                + "SE, later plays refitted | Refitted / held | its jackknife SE |";
        return List.of("# Full-index pilot", "", "## The resamples", "",
                "Difference from Hi-Lo at 80%, 1-8 same schedule, over 25 resamples (SEs in % of a unit per round):", "",
                head, "|---|---|---|---|---|---|---|",
                "| " + RiskReport.KO + " | +0.0231% | 0.0230% | 0.0211% | 0.0219% | 1.90 | 0.04 |", "",
                "## Results", "",
                "Difference from Hi-Lo at 80%, 1-8 same schedule, over 100 resamples (SEs in % of a unit per round):", "",
                head, "|---|---|---|---|---|---|---|",
                "| " + RiskReport.KO + " | +0.0231% | 0.0293% | 0.0279% | 0.0299% | 1.04 | 0.02 |",
                "| Red Seven (Red 7) | +0.0699% | 0.0313% | 0.0294% | 0.0307% | 0.98 | 0.02 |", "",
                "Gain from full play at 80%, 1-8 same schedule: full play less first decisions only, paired:", "",
                "| Count | Estimate | SE, later plays held | SE, later plays refitted | Refitted / held | its jackknife SE |",
                "|---|---|---|---|---|---|",
                "| " + RiskReport.HI_LO + " | +0.0554% | 0.0049% | 0.0070% | 1.43 | 0.10 |", "",
                "Difference from Hi-Lo at 80%, flat, over 100 resamples (SEs in % of a unit per round):", "",
                head, "|---|---|---|---|---|---|---|",
                "| " + RiskReport.KO + " | -0.0153% | 0.0041% | 0.0041% | 0.0044% | 1.06 | 0.02 |", "",
                "## The full run, projected");
    }

    // ------------------------------------------------------------------ the simulator's files

    /** Twice a round's result for a unit bet, and its chance: a made-up game winning 0.05 a unit. */
    static final int[] TWO_X = {-2, 2, 3, 4, -4, -1};
    static final double[] P = {0.475, 0.43, 0.045, 0.03, 0.015, 0.005};

    /** One shoe of play: rounds, each a bet and a result; the bet follows a count that walks within the shoe. */
    static double[][] shoe(SplittableRandom rnd, boolean flat) {
        int n = 55 + rnd.nextInt(11);
        double[][] rounds = new double[n][];
        int c = 0;
        for (int i = 0; i < n; i++) {
            c += rnd.nextBoolean() ? 1 : -1;
            double bet = flat ? 1 : Math.max(1, Math.min(8, 1 + c));
            double u = rnd.nextDouble();
            int k = 0;
            for (double acc = P[0]; u >= acc && k < P.length - 1; acc += P[++k]) {
            }
            boolean split = Math.abs(TWO_X[k]) == 4 && rnd.nextBoolean();
            rounds[i] = new double[]{bet, TWO_X[k], split ? 1 : 0};
        }
        return rounds;
    }

    /**
     * The swings of each figure given, played on shoes from its own seed: the histogram and the
     * shoe sums of the same rounds, and a P3 z (and a P2 z at 80%) of 0.4, or the z given.
     */
    static List<String> swings(List<String> figures, int shoes, Map<String, Double> z) {
        List<String> out = new ArrayList<>();
        out.add("# swings, a fixture");
        for (String f : figures) {
            RiskReport.Spec s = RiskReport.spec(f);
            SplittableRandom rnd = new SplittableRandom(f.hashCode());
            Map<String, long[]> hist = new java.util.TreeMap<>();
            double[] sum = new double[7];
            for (int h = 0; h < shoes; h++) {
                double y = 0;
                double[][] rounds = shoe(rnd, s.bets.equals("flat"));
                for (double[] r : rounds) {
                    y += r[0] * r[1] / 2;
                    long[] cell = hist.computeIfAbsent(r[0] + "\t" + (int) r[1], x -> new long[2]);
                    cell[0]++;
                    cell[1] += (long) r[2];
                }
                int n = rounds.length;
                sum[0] += y;
                sum[1] += y * y;
                sum[2] += y * y * y;
                sum[3] += y * y * y * y;
                sum[4] += n;
                sum[5] += (double) n * n;
                sum[6] += y * n;
            }
            out.add("shoes\t" + f + "\t" + shoes + "\t" + sum[0] + "\t" + sum[1] + "\t" + sum[2] + "\t" + sum[3] + "\t"
                    + sum[4] + "\t" + sum[5] + "\t" + sum[6]);
            for (Map.Entry<String, long[]> c : hist.entrySet()) {
                out.add("hist\t" + f + "\t" + c.getKey() + "\t" + c.getValue()[0] + "\t" + c.getValue()[1]);
            }
            out.add("z\t" + f + "\tP3\t" + z.getOrDefault(f, 0.4));
            if (s.pen == 2) {
                out.add("z\t" + f + "\tP2\t" + -0.3);
            }
        }
        return out;
    }

    /** Per-shoe records of the same made-up game at 1-8: Y N M a line. */
    static List<String> records(int shoes, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        List<String> out = new ArrayList<>();
        for (int h = 0; h < shoes; h++) {
            double y = 0;
            double low = 0;
            double[][] rounds = shoe(rnd, false);
            for (double[] r : rounds) {
                y += r[0] * r[1] / 2;
                low = Math.min(low, y);
            }
            out.add(y + "\t" + rounds.length + "\t" + low);
        }
        return out;
    }

    /** 20 paths of 1,000 points, the cumulative win every 100 rounds. */
    static List<String> fan() {
        List<String> out = new ArrayList<>();
        for (int j = 0; j < 20; j++) {
            StringBuilder b = new StringBuilder().append(j);
            for (int i = 0; i < 1000; i++) {
                b.append('\t').append(0.36 * (i + 1) + 40 * Math.sin(0.013 * i + j));
            }
            out.add(b.toString());
        }
        return out;
    }

    static Path write(Path dir, String name, List<String> lines) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, lines, StandardCharsets.UTF_8);
        return p;
    }
}
