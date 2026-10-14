package sim;

import java.util.ArrayList;
import java.util.List;

/**
 * The two variants SIM_CHECK.md section 9 reports beside mode A and never tests: mode B, where each
 * instance's own play takes the cards (PLAN_COMPUTE.md 1.5), and the count that keeps moving through
 * split hands instead of freezing at the deal's play key. Both run on P3's shoes (same seed, same
 * shoe numbers), for the instances of F26 to F29, with mode A's frozen count played on the same
 * shoes beside them, so each variant's difference from mode A is paired shoe by shoe.
 *
 * <p>Mode A's walk is the chart's, one coin a round start, as in P3. In mode B each instance walks
 * the shoe alone to its cut: its own rounds take the cards, its count is over the cards it has seen,
 * its exits over its own round starts, and it draws its own coin a round start from a fresh copy of
 * the shoe's coin stream (sim/QUESTIONS.md). Per shoe and figure the run keeps the moments of
 * (Y_A by fold, Y_moving by fold, Y_B by fold, N_A, N_B by fold), the folds pooled at the end by
 * exact.tsv's test weights, mode B's rounds pooled with the same weights.
 */
public final class Variants {

    /** SIM_CHECK.md section 9: Hi-Lo and Victor APC + ace at full play, 80%, flat and 1-8. */
    public static final List<String> FIGURES = List.of("F26", "F27", "F28", "F29");
    static final int K = 9;
    static final int BLOCK = Moments.size(K);

    private final long seed;
    /** Each target: its row and its figure's place in the row's figures. */
    final List<Row> rows = new ArrayList<>();
    final List<Integer> figures = new ArrayList<>();
    final List<String> ids = new ArrayList<>();
    private final Chart chart;
    private final Rules rules;
    private final Plants plants;

    public Variants(long seed, List<Row> candidates, Registry registry, Chart chart, Plants plants) {
        this.seed = seed;
        this.chart = chart;
        this.plants = plants;
        this.rules = Rules.montreal(plants);
        for (String id : FIGURES) {
            Registry.Fig fig = registry.get(id);
            for (Row r : candidates) {
                if (fig != null && r.folds != null && r.name.equals(fig.row()) && r.pen == fig.pen() && r.figures.contains(fig.figure())) {
                    rows.add(r);
                    figures.add(r.figures.indexOf(fig.figure()));
                    ids.add(id);
                }
            }
        }
    }

    public int size() {
        return rows.size() * BLOCK;
    }

    /** The bet a plan places at a key: 0 for flat's figure 0 aside, UNITS when seated and the coin is under PLAY. */
    static double bet(Strategy s, int figure, int key, int lowest, double coin) {
        if (figure == 0) {
            return 1;
        }
        Strategy.Plan plan = s.plans.get(figure - 1);
        if (plan.exit != null && lowest < plan.exit) {
            return 0;
        }
        int line = s.betLine(plan, key);
        return coin < plan.play[line] ? plan.units[line] : 0;
    }

    public double[] run(long first, int count) {
        double[] sums = new double[size()];
        int nt = rows.size();
        Table table = new Table(rules, plants, chart);
        Round own = new Round(rules, plants);
        Strategy.Play[][] frozen = new Strategy.Play[nt][2];
        Strategy.Play[][] moving = new Strategy.Play[nt][2];
        for (int t = 0; t < nt; t++) {
            for (int f = 0; f < 2; f++) {
                frozen[t][f] = rows.get(t).folds[f].new Play(chart, false);
                moving[t][f] = rows.get(t).folds[f].new Play(chart, true);
            }
        }
        double[][] v = new double[nt][K];
        int[][] low = new int[nt][2];
        int[] dealt = new int[Shoe.KINDS];
        for (long h = first; h < first + count; h++) {
            int[] cards = Shoe.shuffled(Xoshiro.of(seed, h, Xoshiro.STREAM_CARDS, 0));
            Walk.ArrayDeck deck = new Walk.ArrayDeck(cards);
            Xoshiro coins = Xoshiro.of(seed, h, Xoshiro.STREAM_COINS, 0);
            for (int t = 0; t < nt; t++) {
                java.util.Arrays.fill(v[t], 0);
                java.util.Arrays.fill(low[t], Integer.MAX_VALUE);
            }
            java.util.Arrays.fill(dealt, 0);
            int depth = 0;
            while (depth < Shoe.CUTS[2]) {
                table.begin(deck, depth);
                double coin = coins.nextDouble();
                for (int t = 0; t < nt; t++) {
                    Row row = rows.get(t);
                    if (depth >= row.cut) {
                        continue;
                    }
                    v[t][6]++;
                    for (int f = 0; f < 2; f++) {
                        Strategy s = row.folds[f];
                        int key = s.keys.betKey(dealt, depth);
                        low[t][f] = Math.min(low[t][f], key);
                        double b = bet(s, figures.get(t), key, low[t][f], coin);
                        frozen[t][f].start(dealt, depth);
                        v[t][f] += b * table.result(frozen[t][f]);
                        moving[t][f].start(dealt, depth);
                        v[t][2 + f] += b * table.result(moving[t][f]);
                    }
                }
                int next = table.chartNext();
                for (int i = depth; i < next; i++) {
                    dealt[cards[i]]++;
                }
                depth = next;
            }
            for (int t = 0; t < nt; t++) {
                for (int f = 0; f < 2; f++) {
                    double[] yn = modeB(rows.get(t).folds[f], figures.get(t), deck, cards, h, rows.get(t).cut, own);
                    v[t][4 + f] = yn[0];
                    v[t][7 + f] = yn[1];
                }
                Moments.add(sums, t * BLOCK, v[t]);
            }
        }
        return sums;
    }

    /** One instance walking shoe h alone in mode B: {Y, N}. */
    double[] modeB(Strategy s, int figure, Walk.ArrayDeck deck, int[] cards, long h, int cut, Round round) {
        Strategy.Play play = s.new Play(chart, false);
        Xoshiro coins = Xoshiro.of(seed, h, Xoshiro.STREAM_COINS, 0);
        int[] dealt = new int[Shoe.KINDS];
        int lowest = Integer.MAX_VALUE;
        double y = 0;
        long n = 0;
        int depth = 0;
        while (depth < cut) {
            double coin = coins.nextDouble();
            int key = s.keys.betKey(dealt, depth);
            lowest = Math.min(lowest, key);
            double b = bet(s, figure, key, lowest, coin);
            play.start(dealt, depth);
            round.play(deck, depth, play, -1);
            y += b * (round.main + round.insurance);
            n++;
            for (int i = depth; i < round.next; i++) {
                dealt[cards[i]]++;
            }
            depth = round.next;
        }
        return new double[]{y, n};
    }

    /** The run in chunks under dir, as P3's (the same seed and shoe numbers), resumable and added in index order. */
    public double[] chunked(Bundle bundle, java.nio.file.Path dir, String simCommit, int chunks, int chunkSize,
                            int threads, int stopAfter) throws Exception {
        List<String> header = new ArrayList<>(List.of("check\tvariants: mode B and the moving count beside mode A",
                "sim\t" + simCommit, "bundle\t" + bundle.sha256, "seed\t" + seed, "size\t" + chunkSize,
                "plants\t" + plants.describe(), "generator\txoshiro256** seeded by SplitMix64, Xoshiro.of(seed, shoe, stream, 0)"));
        for (int t = 0; t < rows.size(); t++) {
            header.add("figure\t" + ids.get(t) + "\t" + rows.get(t).name + "\t" + rows.get(t).pen + "\t"
                    + rows.get(t).figures.get(figures.get(t)));
        }
        return new Chunks(dir, "variants", header).run(chunks, size(), threads, i -> run((long) i * chunkSize, chunkSize),
                stopAfter);
    }

    static double[] unit(int i) {
        double[] c = new double[K];
        c[i] = 1;
        return c;
    }

    static double[] pick(double w0, double w1, int at) {
        double[] c = new double[K];
        c[at] = w0;
        c[at + 1] = w1;
        return c;
    }

    /**
     * For target t, the folds pooled with weights w0 and w1: the three estimates {μ, SE, σ_eff,
     * rounds, shoes} (mode A, the moving count, mode B), then the two paired differences from mode A
     * {difference, SE, σ_eff of the per-round difference}.
     */
    public double[][] results(double[] sums, int t, double w0, double w1) {
        int at = t * BLOCK;
        double[] na = unit(6);
        double[] nb = pick(w0, w1, 7);
        double[] ya = pick(w0, w1, 0);
        double[] ym = pick(w0, w1, 2);
        double[] yb = pick(w0, w1, 4);
        double[][] out = new double[5][];
        double[][] num = {ya, ym, yb};
        double[][] den = {na, na, nb};
        for (int i = 0; i < 3; i++) {
            double[] e = Moments.ratio(sums, at, num[i], den[i]);
            out[i] = new double[]{e[0], e[1], e[1] * Math.sqrt(e[2]), e[2], e[3]};
        }
        out[3] = Moments.difference(sums, at, ym, na, ya, na);
        out[4] = Moments.difference(sums, at, yb, nb, ya, na);
        return out;
    }

    /** variants.tsv, and the two differences of each target as the summary's differences. */
    public List<String> lines(double[] sums, Exact exact, List<Summary.Difference> differences) {
        List<String> out = new ArrayList<>(List.of(
                "# variants.tsv: mode B and the count moving through split hands, beside mode A on the same shoes"
                        + " (SIM_CHECK.md section 9; reported, not tested). The folds are pooled by exact.tsv's test weights;"
                        + " sigmaEff is the SE times the root of the rounds; a difference is paired shoe by shoe.",
                "kind\tfig\trow\tpen\tfigure\twhat\tvalue\tse\tsigmaEff\trounds\tshoes"));
        String[] what = {"mode A", "moving count", "mode B", "moving count less mode A", "mode B less mode A"};
        for (int t = 0; t < rows.size(); t++) {
            Row row = rows.get(t);
            double[] w = Outputs.weights(row, exact, plants);
            double[][] r = results(sums, t, w[0], w[1]);
            String head = ids.get(t) + "\t" + row.name + "\t" + row.pen + "\t" + row.figures.get(figures.get(t));
            for (int i = 0; i < 5; i++) {
                double rounds = i < 3 ? r[i][3] : r[i == 3 ? 0 : 2][3];
                out.add((i < 3 ? "estimate\t" : "difference\t") + head + "\t" + what[i] + "\t" + r[i][0] + "\t" + r[i][1]
                        + "\t" + r[i][2] + "\t" + rounds + "\t" + (long) sums[t * BLOCK]);
                if (i >= 3) {
                    differences.add(new Summary.Difference("variants", row.name, row.pen, row.figures.get(figures.get(t)),
                            what[i], r[i][0], r[i][1], r[i][2], r[i][3], r[i][4], Double.NaN, rounds, sums[t * BLOCK],
                            ids.get(t), ids.get(t)));
                }
            }
        }
        return out;
    }
}
