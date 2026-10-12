package sim;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
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
 * bit; sharing it between plans changes no plan's own expectation, and keeps differences paired.
 *
 * <p>Kept on the way ({@link #extras}): the chart's result by depth band with its per-shoe
 * moments (depth.tsv); every figure's paired difference from Hi-Lo's at the same play,
 * penetration and bets (SIM_CHECK.md section 9); the swings of the registered figures
 * ({@link Swings}); the per-shoe records (Y, N, M) of the figures with paths, on the shoes below
 * {@code recordShoes} (SHOE_SPEC.md C2); and Hi-Lo's result by bet key ({@link EvByKey}).
 */
public final class P3 {

    private final long seed;
    private final List<Row> rows;
    private final Chart chart;
    private final Rules rules;
    private final Plants plants;
    private final int bandsAt;
    private int size;
    private boolean moving;
    /** Each pair: {row, figure, Hi-Lo's row, Hi-Lo's figure, offset}. */
    private final List<int[]> pairs = new ArrayList<>();
    private final List<Swings> swings = new ArrayList<>();
    /** Each record target: {row, figure}, with its registered id. */
    private final List<int[]> records = new ArrayList<>();
    private final List<String> recordIds = new ArrayList<>();
    private final List<EvByKey> evs = new ArrayList<>();
    private Path recordsDir;
    private long recordShoes;
    private RoundListener listener;

    /** Bands of 26 cards dealt for depth.tsv (PLAN_COMPUTE.md 1.8): 0 to 25, 26 to 51, ..., 312 to 331. */
    public static final int BANDS = 13;
    /** A band's per-shoe moments: Y and N. */
    static final int BAND = Moments.size(2);
    static final int PAIR = Moments.size(5);

    /** SIM_CHECK.md section 4's seed for P3. */
    public static final long SEED = 20261008L;

    /** Told of every round start once its instances have played: x[row][fold] and bet[row][fold][figure]. */
    public interface RoundListener {
        void round(long shoe, int depth, double[][] x, double[][][] bet);
    }

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
        this.bandsAt = n;
        this.size = n + BANDS * BAND;
    }

    /**
     * The variant whose count keeps moving through split hands (PLAN_COMPUTE.md 1.5, SIM_CHECK.md
     * section 9): reported beside the frozen count, never tested.
     */
    public P3 moving(boolean on) {
        this.moving = on;
        return this;
    }

    public P3 listener(RoundListener l) {
        this.listener = l;
        return this;
    }

    static boolean isHiLo(Row r) {
        return r.folds != null && r.folds[0].playCount.equals(EvByKey.ROWS.get(0)) && r.folds[0].betCount.equals(EvByKey.ROWS.get(0));
    }

    /** The row with this name at this penetration, or -1. */
    int rowOf(String name, int pen) {
        for (int r = 0; r < rows.size(); r++) {
            if (rows.get(r).name.equals(name) && rows.get(r).pen == pen) {
                return r;
            }
        }
        return -1;
    }

    /**
     * Adds what is kept on the way: the differences from Hi-Lo and the edge by key always; with a
     * registry, the swings of its figures with swings 1 and, if recordsDir is given, the per-shoe
     * records of its figures with paths 1 on shoes 0 to recordShoes - 1.
     */
    public P3 extras(Registry registry, Path recordsDir, long recordShoes) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            for (int h = 0; h < rows.size() && row.folds != null && !isHiLo(row); h++) {
                Row hl = rows.get(h);
                if (isHiLo(hl) && hl.pen == row.pen && hl.folds[0].scope.equals(row.folds[0].scope)) {
                    for (int j = 0; j < row.figures.size(); j++) {
                        int jh = hl.figures.indexOf(row.figures.get(j));
                        if (jh >= 0) {
                            pairs.add(new int[]{r, j, h, jh, size});
                            size += PAIR;
                        }
                    }
                }
            }
        }
        if (registry != null) {
            for (Registry.Fig fig : registry.figures) {
                int r = rowOf(fig.row(), fig.pen());
                int j = r < 0 ? -1 : rows.get(r).figures.indexOf(fig.figure());
                if (j >= 0 && fig.swings()) {
                    Swings s = new Swings(fig, r, rows.get(r), j, rules, size);
                    swings.add(s);
                    size += s.size();
                }
                if (j >= 0 && fig.paths() && recordsDir != null) {
                    records.add(new int[]{r, j});
                    recordIds.add(fig.id());
                }
            }
        }
        this.recordsDir = recordsDir;
        this.recordShoes = recordShoes;
        for (String name : EvByKey.ROWS) {
            int r = rowOf(name, 80);
            if (r >= 0 && rows.get(r).folds != null) {
                evs.add(new EvByKey(name, r, size));
                size += EvByKey.size();
            }
        }
        return this;
    }

    public List<Swings> swings() {
        return swings;
    }

    public List<EvByKey> evs() {
        return evs;
    }

    public List<String> recordIds() {
        return recordIds;
    }

    public int size() {
        return size;
    }

    /** The chart's walk in depth band b: {μ, SE, rounds, shoes}, the ratio estimator over shoes. */
    public double[] band(double[] sums, int b) {
        return Moments.ratio(sums, bandsAt + b * BAND, new double[]{1, 0}, new double[]{0, 1});
    }

    /** The pairs: {row, figure, Hi-Lo's row, Hi-Lo's figure, offset}. */
    public List<int[]> pairs() {
        return pairs;
    }

    /**
     * Pair i's paired difference, the row's figure less Hi-Lo's, each pooled over its folds with its
     * weights: {difference, SE, σ_eff of the per-round difference, the row's μ, Hi-Lo's μ}.
     */
    public double[] pair(double[] sums, int i, double w0, double w1, double h0, double h1) {
        int at = pairs.get(i)[4];
        double[] n = {0, 0, 0, 0, 1};
        return Moments.difference(sums, at, new double[]{w0, w1, 0, 0, 0}, n, new double[]{0, 0, h0, h1, 0}, n);
    }

    /**
     * A whole P3 run: chunks of chunkSize shoes under dir, resumed when their headers match, added
     * in index order; null if stopAfter stopped it before every chunk was on disk.
     */
    public static double[] chunked(Bundle bundle, List<Row> rows, Path dir, String simCommit, long seed, int chunks,
                                   int chunkSize, int threads, Plants plants, int stopAfter) throws Exception {
        return chunked(new P3(seed, rows, bundle.chart, plants), bundle, dir, simCommit, chunks, chunkSize, threads, stopAfter);
    }

    /** The same for a configured P3 (moving count, extras); its layout is in the chunks' header. */
    public static double[] chunked(P3 p3, Bundle bundle, Path dir, String simCommit, int chunks, int chunkSize, int threads,
                                   int stopAfter) throws Exception {
        List<String> header = new ArrayList<>(List.of("check\tP3", "moving\t" + p3.moving, "sim\t" + simCommit,
                "bundle\t" + bundle.sha256, "seed\t" + p3.seed, "size\t" + chunkSize, "k\t-", "plants\t" + p3.plants.describe(),
                "generator\txoshiro256** seeded by SplitMix64, Xoshiro.of(seed, shoe, stream, 0)"));
        for (Row r : p3.rows) {
            header.add("row\t" + r.name + "\t" + r.pen + "\t" + String.join(",", r.figures));
        }
        header.add("extras\t" + p3.pairs.size() + " pairs, swings " + p3.swings.stream().map(s -> s.fig.id()).toList()
                + ", records " + p3.recordIds + " below shoe " + p3.recordShoes + ", ev " + p3.evs.stream().map(e -> e.name).toList()
                + ", sums " + p3.size);
        Chunks c = new Chunks(dir, "p3", header);
        return c.run(chunks, p3.size(), threads, i -> p3.run((long) i * chunkSize, chunkSize), stopAfter);
    }

    /** The sums of shoes first to first + count - 1, added in shoe order. */
    public double[] run(long first, int count) {
        double[] sums = new double[size];
        Table table = new Table(rules, plants, chart);
        int nr = rows.size();
        Strategy.Play[][] plays = new Strategy.Play[nr][];
        double[][][] y = new double[nr][][];
        long[][][] unseen = new long[nr][][];
        int[][][] minKey = new int[nr][][];
        long[] n = new long[nr];
        double[][] x = new double[nr][2];
        boolean[][] split = new boolean[nr][2];
        double[][][] bet = new double[nr][2][];
        for (int r = 0; r < nr; r++) {
            Row row = rows.get(r);
            plays[r] = new Strategy.Play[row.instances()];
            for (int f = 0; row.folds != null && f < 2; f++) {
                plays[r][f] = row.folds[f].new Play(chart, moving);
            }
            y[r] = new double[row.instances()][row.figures.size()];
            unseen[r] = new long[row.instances()][row.figures.size()];
            minKey[r] = new int[row.instances()][row.figures.size()];
            bet[r][0] = new double[row.figures.size()];
            bet[r][1] = new double[row.figures.size()];
        }
        int[] evOf = new int[nr];
        java.util.Arrays.fill(evOf, -1);
        EvByKey.Shoe[] evShoe = new EvByKey.Shoe[evs.size()];
        for (int e = 0; e < evs.size(); e++) {
            evOf[evs.get(e).row] = e;
            evShoe[e] = new EvByKey.Shoe();
        }
        double[] bandY = new double[BANDS];
        double[] bandN = new double[BANDS];
        double[] cum = new double[records.size()];
        double[] low = new double[records.size()];
        int[] dealt = new int[Shoe.KINDS];
        Path part = recordsDir == null || records.isEmpty() || first >= recordShoes ? null
                : recordsDir.resolve(String.format("records-%012d.part", first));
        StringBuilder[] lines = new StringBuilder[records.size()];
        for (int k = 0; k < lines.length; k++) {
            lines[k] = new StringBuilder();
        }
        try (BufferedWriter out = part == null ? null : Files.newBufferedWriter(tmp(part), StandardCharsets.UTF_8)) {
            for (long h = first; h < first + count; h++) {
                int[] cards = Shoe.shuffled(Xoshiro.of(seed, h, Xoshiro.STREAM_CARDS, 0));
                Xoshiro coins = Xoshiro.of(seed, h, Xoshiro.STREAM_COINS, 0);
                Walk.ArrayDeck deck = new Walk.ArrayDeck(cards);
                java.util.Arrays.fill(dealt, 0);
                java.util.Arrays.fill(bandY, 0);
                java.util.Arrays.fill(bandN, 0);
                java.util.Arrays.fill(cum, 0);
                java.util.Arrays.fill(low, 0);
                for (int r = 0; r < nr; r++) {
                    n[r] = 0;
                    for (int f = 0; f < y[r].length; f++) {
                        java.util.Arrays.fill(y[r][f], 0);
                        java.util.Arrays.fill(unseen[r][f], 0);
                        java.util.Arrays.fill(minKey[r][f], Integer.MAX_VALUE);
                    }
                }
                int one = (int) (h & 1);
                int depth = 0;
                while (depth < Shoe.CUTS[2]) {
                    table.begin(deck, depth);
                    double coin = coins.nextDouble();
                    double chartX = table.chartRound();
                    boolean chartSplit = table.split;
                    bandY[depth / 26] += chartX;
                    bandN[depth / 26] += 1;
                    for (int r = 0; r < nr; r++) {
                        Row row = rows.get(r);
                        if (depth >= row.cut) {
                            continue;
                        }
                        n[r]++;
                        if (row.folds == null) {
                            x[r][0] = chartX;
                            split[r][0] = chartSplit;
                            bet[r][0][0] = 1;
                            y[r][0][0] += chartX;
                            continue;
                        }
                        for (int f = 0; f < 2; f++) {
                            Strategy s = row.folds[f];
                            Strategy.Play play = plays[r][f];
                            play.start(dealt, depth);
                            double xv = table.result(play);
                            x[r][f] = xv;
                            split[r][f] = table.split;
                            bet[r][f][0] = 1;
                            y[r][f][0] += xv;
                            int key = s.keys.betKey(dealt, depth);
                            if (evOf[r] >= 0) {
                                evs.get(evOf[r]).round(evShoe[evOf[r]], f, key, xv);
                            }
                            for (int p = 0; p < s.plans.size(); p++) {
                                Strategy.Plan plan = s.plans.get(p);
                                minKey[r][f][p + 1] = Math.min(minKey[r][f][p + 1], key);
                                if (!s.betListed(plan, key)) {
                                    unseen[r][f][p + 1]++;
                                }
                                boolean seated = plan.exit == null || minKey[r][f][p + 1] >= plan.exit;
                                int line = s.betLine(plan, key);
                                double b = seated && coin < plan.play[line] ? plan.units[line] : 0;
                                bet[r][f][p + 1] = b;
                                y[r][f][p + 1] += b * xv;
                            }
                        }
                    }
                    for (Swings s : swings) {
                        if (depth < rows.get(s.row).cut) {
                            int f = rows.get(s.row).folds == null ? 0 : one;
                            s.round(sums, bet[s.row][f][s.figure], x[s.row][f], split[s.row][f]);
                        }
                    }
                    for (int k = 0; k < records.size(); k++) {
                        int r = records.get(k)[0];
                        if (depth < rows.get(r).cut) {
                            int f = rows.get(r).folds == null ? 0 : one;
                            cum[k] += bet[r][f][records.get(k)[1]] * x[r][f];
                            low[k] = Math.min(low[k], cum[k]);
                        }
                    }
                    if (listener != null) {
                        listener.round(h, depth, x, bet);
                    }
                    int next = table.chartNext();
                    for (int i = depth; i < next; i++) {
                        dealt[cards[i]]++;
                    }
                    depth = next;
                }
                for (int r = 0; r < nr; r++) {
                    rows.get(r).addShoe(sums, y[r], n[r], unseen[r]);
                }
                for (int b = 0; b < BANDS; b++) {
                    Moments.add(sums, bandsAt + b * BAND, new double[]{bandY[b], bandN[b]});
                }
                for (int[] p : pairs) {
                    Moments.add(sums, p[4], new double[]{y[p[0]][0][p[1]], y[p[0]][1][p[1]], y[p[2]][0][p[3]],
                            y[p[2]][1][p[3]], n[p[0]]});
                }
                for (Swings s : swings) {
                    s.shoe(sums, y[s.row][rows.get(s.row).folds == null ? 0 : one][s.figure], n[s.row]);
                }
                for (int e = 0; e < evs.size(); e++) {
                    evs.get(e).shoe(sums, evShoe[e]);
                }
                if (out != null && h < recordShoes) {
                    for (int k = 0; k < records.size(); k++) {
                        int r = records.get(k)[0];
                        int f = rows.get(r).folds == null ? 0 : one;
                        out.write(recordIds.get(k) + "\t" + y[r][f][records.get(k)[1]] + "\t" + n[r] + "\t" + low[k] + "\n");
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (part != null) {
            try {
                Files.move(tmp(part), part, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return sums;
    }

    private static Path tmp(Path part) {
        return part.resolveSibling(part.getFileName() + ".tmp");
    }

    /**
     * SHOE_SPEC.md C2's records-FIG.tsv for each figure with paths: one line {@code Y N M} a shoe,
     * shoes 0 to recordShoes - 1 in order, from the parts the chunks wrote (each chunk's part is on
     * disk before its sums are). Refuses a missing part or one short of its shoes.
     */
    public void writeRecords(Path out, int chunkSize) throws IOException {
        List<List<String>> by = new ArrayList<>();
        for (int k = 0; k < recordIds.size(); k++) {
            by.add(new ArrayList<>());
        }
        for (long first = 0; first < recordShoes; first += chunkSize) {
            Path part = recordsDir.resolve(String.format("records-%012d.part", first));
            if (!Files.exists(part)) {
                throw new IOException(part + " is missing: the records of shoes from " + first + " were not kept");
            }
            long want = Math.min(chunkSize, recordShoes - first) * recordIds.size();
            long got = 0;
            for (String line : Files.readAllLines(part, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", 2);
                by.get(recordIds.indexOf(f[0])).add(f[1]);
                got++;
            }
            if (got != want) {
                throw new IOException(part + " holds " + got + " records, not " + want);
            }
        }
        for (int k = 0; k < recordIds.size(); k++) {
            Path file = out.resolve("records-" + recordIds.get(k) + ".tsv");
            Files.write(tmp(file), by.get(k), StandardCharsets.UTF_8);
            Files.move(tmp(file), file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * SHOE_SPEC.md C3's fan: paths of {@code rounds} rounds, the cumulative win written every
     * {@code every} rounds, for one row's figure, shoe h played by its fold-(h mod 2) file. Path 0
     * starts at shoe 0 and each takes whole shoes in order until it has its rounds, the rest of the
     * last shoe dropped; the next starts at the shoe after. Each path's wins, by write.
     */
    public static double[][] fan(long seed, Row row, int figure, Chart chart, Plants plants, int paths, int rounds, int every) {
        Row alone = row.folds == null ? Row.chart(row.pen, 0) : Row.of(row.folds[0], row.folds[1], 0);
        double[][] out = new double[paths][rounds / every];
        int[] at = {0, 0};
        double[] cum = {0};
        P3 p3 = new P3(seed, List.of(alone), chart, plants).listener((h, depth, x, bet) -> {
            if (at[0] >= paths || at[1] >= rounds || depth >= alone.cut) {
                return;
            }
            int f = alone.folds == null ? 0 : (int) (h & 1);
            cum[0] += bet[0][f][figure] * x[0][f];
            at[1]++;
            if (at[1] % every == 0) {
                out[at[0]][at[1] / every - 1] = cum[0];
            }
        });
        for (long h = 0; at[0] < paths; h++) {
            p3.run(h, 1);
            if (at[1] >= rounds) {
                at[0]++;
                at[1] = 0;
                cum[0] = 0;
            }
        }
        return out;
    }
}
