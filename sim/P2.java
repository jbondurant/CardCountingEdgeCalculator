package sim;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Check P2, the main file's own states (PLAN_COMPUTE.md 1.6, SIM_CHECK.md sections 4 and 6), and
 * check D4. Each valued state's cards left are re-dealt K times; re-deal k of the record at place
 * i is the order {@link LazyDeck} draws with {@code Xoshiro.of(seed, i, 0, k)}, the same for every
 * instance, and its coin is the k-th draw of {@code Xoshiro.of(seed, i, 1, 0)}. Each row plays a
 * state with the fold strategy whose test set holds it (the record's fold), bets at the state's
 * bet key, and the state's mean of bet × result is weighted by 1/q, the state's weight in every
 * engine figure. The engine's side is the same sum over the same states of 1/q × bet × (rvPlay +
 * rvInsurance) from states.bin, so the luck of which states came up cancels and the SE is the
 * re-deals' alone.
 *
 * <p>A plan with an exit needs the lowest bet key of the state's shoe so far. It comes from the
 * simulator's own walk of that shoe (D1's), never from the engine.
 */
public final class P2 {

    /** SIM_CHECK.md section 4's seed for P2. */
    public static final long SEED = 20261009L;

    private final long seed;
    private final int k;
    private final States states;
    private final List<Row> rows;
    private final List<States.Section> sections = new ArrayList<>();
    private final Chart chart;
    private final Rules rules;
    private final Plants plants;
    private final int[] offset;
    private final int size;

    /** rows: those of rows that have a section in states.bin at their penetration; the others are left out. */
    public P2(long seed, int k, States states, List<Row> candidates, Chart chart, Plants plants) {
        this.seed = seed;
        this.k = k;
        this.states = states;
        this.chart = chart;
        this.plants = plants;
        this.rules = Rules.montreal(plants);
        this.rows = new ArrayList<>();
        for (Row r : candidates) {
            for (States.Section s : states.sections) {
                if (s.row.equals(r.name) && s.pen == r.pen) {
                    rows.add(r);
                    sections.add(s);
                    break;
                }
            }
        }
        offset = new int[rows.size()];
        int n = 0;
        for (int r = 0; r < rows.size(); r++) {
            offset[r] = n;
            n += 4 * rows.get(r).figures.size();
            if (rows.get(r).folds != null && sections.get(r).plans.length != rows.get(r).figures.size() - 1) {
                throw new IllegalArgumentException(rows.get(r).name + ": states.bin's plans are not the files'");
            }
        }
        size = n;
    }

    public int size() {
        return size;
    }

    /** A whole P2 run in chunks of chunkSize states (SIM_CHECK.md: 1,000), resumable, added in index order. */
    public double[] chunked(Path dir, String simCommit, String bundleSha, int chunkSize, int threads, int stopAfter)
            throws Exception {
        List<String> header = new ArrayList<>(List.of("check\tP2", "sim\t" + simCommit, "bundle\t" + bundleSha,
                "seed\t" + seed, "size\t" + chunkSize, "k\t" + k, "plants\t" + plants.describe(),
                "generator\txoshiro256** seeded by SplitMix64, Xoshiro.of(seed, record, 0, redeal) and (seed, record, 1, 0)"));
        for (Row r : rows) {
            header.add("row\t" + r.name + "\t" + r.pen + "\t" + String.join(",", r.figures));
        }
        int chunks = (states.n + chunkSize - 1) / chunkSize;
        return new Chunks(dir, "p2", header).run(chunks, size, threads, i -> run(i * chunkSize, chunkSize), stopAfter);
    }

    public List<Row> rows() {
        return rows;
    }

    /** The section entry holding record i, or -1 (a record at or beyond the section's cut). */
    static int entry(States.Section s, int i) {
        int j = java.util.Arrays.binarySearch(s.index, i);
        return j >= 0 ? j : -1;
    }

    /** The fold strategy that plays record i: its own fold's, or the other's under the plant. */
    Strategy foldOf(Row row, int i) {
        int f = states.fold[i] ^ (plants.on(Plants.OTHER_FOLD) ? 1 : 0);
        return row.folds[f];
    }

    /** The tallies of record i. */
    int[] dealt(int i) {
        int[] d = new int[Shoe.KINDS];
        for (int c = 0; c < Shoe.KINDS; c++) {
            d[c] = states.dealt[i * Shoe.KINDS + c];
        }
        return d;
    }

    /** For each row, the lowest bet key over every round start of record i's shoe up to its round, by the simulator's walk. */
    int[] lowestKeys(int i) {
        int[] low = new int[rows.size()];
        java.util.Arrays.fill(low, Integer.MAX_VALUE);
        Round walker = new Round(rules);
        Walk.walkShoe(states.seed, states.shoe[i], states.cut, states.q0, states.u0, walker, chart,
                (s, r, d, tallies, q, valued) -> {
                    if (r <= states.round[i]) {
                        for (int x = 0; x < rows.size(); x++) {
                            Row row = rows.get(x);
                            if (row.folds != null) {
                                low[x] = Math.min(low[x], foldOf(row, i).keys.betKey(tallies, d));
                            }
                        }
                    }
                }, null);
        return low;
    }

    /**
     * The sums of records first to first + count - 1, in record order: per row and figure,
     * Σ w·x̄, Σ w, Σ w²·v/K and the engine's Σ w·bet·(rvPlay + rvInsurance), x̄ and v the mean and
     * sample variance of bet × result over the state's K re-deals.
     */
    public double[] run(int first, int count) {
        double[] sums = new double[size];
        Table table = new Table(rules, plants, chart);
        LazyDeck deck = new LazyDeck();
        Strategy.Play[][] plays = new Strategy.Play[rows.size()][2];
        for (int r = 0; r < rows.size(); r++) {
            for (int f = 0; rows.get(r).folds != null && f < 2; f++) {
                plays[r][f] = rows.get(r).folds[f].new Play(chart, false);
            }
        }
        double[][] s1 = new double[rows.size()][];
        double[][] s2 = new double[rows.size()][];
        double[][] units = new double[rows.size()][];
        double[][] chance = new double[rows.size()][];
        int[] entries = new int[rows.size()];
        Strategy.Play[] who = new Strategy.Play[rows.size()];
        for (int i = first; i < Math.min(states.n, first + count); i++) {
            int[] dealt = dealt(i);
            int depth = states.depth[i];
            int[] lowest = null;
            for (int r = 0; r < rows.size(); r++) {
                Row row = rows.get(r);
                entries[r] = entry(sections.get(r), i);
                int jn = row.figures.size();
                s1[r] = new double[jn];
                s2[r] = new double[jn];
                units[r] = new double[jn];
                chance[r] = new double[jn];
                units[r][0] = 1;
                chance[r][0] = 1;
                if (entries[r] < 0 || row.folds == null) {
                    who[r] = null;
                    continue;
                }
                Strategy s = foldOf(row, i);
                who[r] = plays[r][s.fold];
                who[r].start(dealt, depth);
                int key = s.keys.betKey(dealt, depth);
                for (int p = 0; p < s.plans.size(); p++) {
                    if (s.plans.get(p).exit != null && lowest == null) {
                        lowest = lowestKeys(i);
                    }
                    double[] b = bet(s, p, key, lowest == null ? Integer.MAX_VALUE : lowest[r]);
                    units[r][p + 1] = b[0];
                    chance[r][p + 1] = b[1];
                }
            }
            deck.state(dealt);
            Xoshiro coins = Xoshiro.of(seed, i, Xoshiro.STREAM_COINS, 0);
            for (int redeal = 0; redeal < k; redeal++) {
                deck.redeal(Xoshiro.of(seed, i, Xoshiro.STREAM_CARDS, redeal));
                double coin = coins.nextDouble();
                table.begin(deck, 0);
                for (int r = 0; r < rows.size(); r++) {
                    if (entries[r] < 0) {
                        continue;
                    }
                    double x;
                    if (who[r] == null) {
                        x = table.chartRound();
                    } else {
                        who[r].redeal();
                        x = table.result(who[r]);
                    }
                    for (int j = 0; j < s1[r].length; j++) {
                        double v = coin < chance[r][j] ? units[r][j] * x : 0;
                        s1[r][j] += v;
                        s2[r][j] += v * v;
                    }
                }
            }
            double w = 1 / states.q[i];
            for (int r = 0; r < rows.size(); r++) {
                int e = entries[r];
                if (e < 0) {
                    continue;
                }
                States.Section sec = sections.get(r);
                double rv = sec.rvPlay[e] + sec.rvInsurance[e];
                for (int j = 0; j < s1[r].length; j++) {
                    double mean = s1[r][j] / k;
                    double var = (s2[r][j] - k * mean * mean) / (k - 1);
                    int o = offset[r] + 4 * j;
                    sums[o] += w * mean;
                    sums[o + 1] += w;
                    sums[o + 2] += w * w * var / k;
                    sums[o + 3] += w * (j == 0 ? 1 : sec.bet[j - 1][e]) * rv;
                }
            }
        }
        return sums;
    }

    /** P2's comparisons from a run's sums: the engine's figure on the same states, and the simulator's with its SE. */
    public List<Summary.Comparison> comparisons(double[] sums) {
        List<Summary.Comparison> out = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            for (int j = 0; j < row.figures.size(); j++) {
                int o = offset[r] + 4 * j;
                out.add(new Summary.Comparison("P2", row.name, row.pen, row.figures.get(j), sums[o + 3] / sums[o + 1],
                        Double.NaN, sums[o] / sums[o + 1], Math.sqrt(sums[o + 2]) / sums[o + 1],
                        (double) k * sections.get(r).index.length, sections.get(r).index.length, 0, ""));
            }
        }
        return out;
    }

    /**
     * Check D4: at every state of every row's section, the simulator's bet key and its expected
     * bet under each plan (UNITS × PLAY, 0 once exited) equal states.bin's, to the bit. Returns
     * {values compared, mismatches}; the first few mismatches are added to first.
     */
    public long[] d4(List<String> first) {
        long compared = 0;
        long bad = 0;
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            if (row.folds == null) {
                continue;
            }
            States.Section sec = sections.get(r);
            for (int e = 0; e < sec.index.length; e++) {
                int i = sec.index[e];
                Strategy s = row.folds[states.fold[i]];
                int key = s.keys.betKey(dealt(i), states.depth[i]);
                compared++;
                if (key != sec.betKey[e]) {
                    bad++;
                    note(first, row.name + " record " + i + ": bet key " + key + ", states.bin " + sec.betKey[e]);
                }
                int[] lowest = null;
                for (int p = 0; p < s.plans.size(); p++) {
                    if (s.plans.get(p).exit != null && lowest == null) {
                        lowest = lowestKeys(i);
                    }
                    double[] b = bet(s, p, key, lowest == null ? Integer.MAX_VALUE : lowest[r]);
                    compared++;
                    if (Double.doubleToRawLongBits(b[0] * b[1]) != Double.doubleToRawLongBits(sec.bet[p][e])) {
                        bad++;
                        note(first, row.name + " record " + i + " plan " + sec.plans[p] + ": bet " + b[0] * b[1]
                                + ", states.bin " + sec.bet[p][e]);
                    }
                }
            }
        }
        return new long[]{compared, bad};
    }

    private static void note(List<String> first, String what) {
        if (first.size() < 10) {
            first.add(what);
        }
    }

    /** The bet under plan p of row r's strategy s at record i's key: units, PLAY, and 0 once exited. */
    static double[] bet(Strategy s, int p, int key, int lowest) {
        Strategy.Plan plan = s.plans.get(p);
        if (plan.exit != null && lowest < plan.exit) {
            return new double[]{0, 0};
        }
        int line = s.betLine(plan, key);
        return new double[]{plan.units[line], plan.play[line]};
    }
}
