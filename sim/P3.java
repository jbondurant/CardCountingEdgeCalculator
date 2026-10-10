package sim;

import java.nio.file.Path;
import java.util.List;

/**
 * Check P3, fresh shoes (PLAN_COMPUTE.md 1.6, SIM_CHECK.md sections 4 and 6), in mode A. Shoe h
 * of a run with seed s is the layout shuffled by xoshiro256** from {@code Xoshiro.of(s, h, 0, 0)},
 * Fisher–Yates from the top down as the main file's shoes are, and its coins come from
 * {@code Xoshiro.of(s, h, 1, 0)}, one a round start. Each shoe is walked to the 80% cut with the
 * chart deciding which cards each round takes; at every round start below a row's cut, each of
 * the row's fold strategies plays the round on the same cards, and bets under each plan at its
 * bet key. A row at 70% or 75% counts only the rounds that start below its cut, as the exact
 * states do.
 *
 * <p>Why one coin a round, shared: a back-counting plan plays the block of keys straddling its
 * threshold with probability PLAY (B2.5). Drawing the coin from its own stream, once per round
 * whether used or not, keeps the cards independent of the coins and every run the same to the
 * bit; sharing it between plans changes no plan's own expectation.
 */
public final class P3 {

    private final long seed;
    private final List<Row> rows;
    private final Chart chart;
    private final Rules rules;
    private final Plants plants;
    private final int size;

    public P3(long seed, List<Row> rows, Chart chart, Plants plants) {
        this.seed = seed;
        this.rows = rows;
        this.chart = chart;
        this.plants = plants;
        this.rules = Rules.montreal(plants);
        int n = 0;
        for (Row r : rows) {
            if (r.offset != n) {
                throw new IllegalArgumentException("rows laid out out of order at " + r.name);
            }
            n += r.size();
        }
        this.size = n;
    }

    public int size() {
        return size;
    }

    /** SIM_CHECK.md section 4's seed for P3. */
    public static final long SEED = 20261008L;

    /**
     * A whole P3 run: chunks of chunkSize shoes under dir, resumed when their headers match, added
     * in index order; null if stopAfter stopped it before every chunk was on disk.
     */
    public static double[] chunked(Bundle bundle, List<Row> rows, Path dir, String simCommit, long seed, int chunks,
                                   int chunkSize, int threads, Plants plants, int stopAfter) throws Exception {
        P3 p3 = new P3(seed, rows, bundle.chart, plants);
        List<String> header = new java.util.ArrayList<>(List.of("check\tP3", "sim\t" + simCommit,
                "bundle\t" + bundle.sha256, "seed\t" + seed, "size\t" + chunkSize, "k\t-", "plants\t" + plants.describe(),
                "generator\txoshiro256** seeded by SplitMix64, Xoshiro.of(seed, shoe, stream, 0)"));
        for (Row r : rows) {
            header.add("row\t" + r.name + "\t" + r.pen + "\t" + String.join(",", r.figures));
        }
        Chunks c = new Chunks(dir, "p3", header);
        return c.run(chunks, p3.size(), threads, i -> p3.run((long) i * chunkSize, chunkSize), stopAfter);
    }

    /** The sums of shoes first to first + count - 1, added in shoe order. */
    public double[] run(long first, int count) {
        double[] sums = new double[size];
        Table table = new Table(rules, plants, chart);
        Strategy.Play[][] plays = new Strategy.Play[rows.size()][];
        double[][][] y = new double[rows.size()][][];
        long[][][] unseen = new long[rows.size()][][];
        int[][][] minKey = new int[rows.size()][][];
        long[] n = new long[rows.size()];
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            plays[r] = new Strategy.Play[row.instances()];
            for (int f = 0; row.folds != null && f < 2; f++) {
                plays[r][f] = row.folds[f].new Play(chart, false);
            }
            y[r] = new double[row.instances()][row.figures.size()];
            unseen[r] = new long[row.instances()][row.figures.size()];
            minKey[r] = new int[row.instances()][row.figures.size()];
        }
        int[] dealt = new int[Shoe.KINDS];
        for (long h = first; h < first + count; h++) {
            int[] cards = Shoe.shuffled(Xoshiro.of(seed, h, Xoshiro.STREAM_CARDS, 0));
            Xoshiro coins = Xoshiro.of(seed, h, Xoshiro.STREAM_COINS, 0);
            Walk.ArrayDeck deck = new Walk.ArrayDeck(cards);
            java.util.Arrays.fill(dealt, 0);
            for (int r = 0; r < rows.size(); r++) {
                n[r] = 0;
                for (int f = 0; f < y[r].length; f++) {
                    java.util.Arrays.fill(y[r][f], 0);
                    java.util.Arrays.fill(unseen[r][f], 0);
                    java.util.Arrays.fill(minKey[r][f], Integer.MAX_VALUE);
                }
            }
            int depth = 0;
            while (depth < Shoe.CUTS[2]) {
                table.begin(deck, depth);
                double coin = coins.nextDouble();
                for (int r = 0; r < rows.size(); r++) {
                    Row row = rows.get(r);
                    if (depth >= row.cut) {
                        continue;
                    }
                    n[r]++;
                    if (row.folds == null) {
                        y[r][0][0] += table.chartRound();
                        continue;
                    }
                    for (int f = 0; f < 2; f++) {
                        Strategy s = row.folds[f];
                        Strategy.Play play = plays[r][f];
                        play.start(dealt, depth);
                        double x = table.result(play);
                        y[r][f][0] += x;
                        int key = s.keys.betKey(dealt, depth);
                        for (int p = 0; p < s.plans.size(); p++) {
                            Strategy.Plan plan = s.plans.get(p);
                            minKey[r][f][p + 1] = Math.min(minKey[r][f][p + 1], key);
                            if (!s.betListed(plan, key)) {
                                unseen[r][f][p + 1]++;
                            }
                            boolean seated = plan.exit == null || minKey[r][f][p + 1] >= plan.exit;
                            int line = s.betLine(plan, key);
                            if (seated && coin < plan.play[line]) {
                                y[r][f][p + 1] += plan.units[line] * x;
                            }
                        }
                    }
                }
                int next = table.chartNext();
                for (int i = depth; i < next; i++) {
                    dealt[cards[i]]++;
                }
                depth = next;
            }
            for (int r = 0; r < rows.size(); r++) {
                rows.get(r).addShoe(sums, y[r], n[r], unseen[r]);
            }
        }
        return sums;
    }
}
