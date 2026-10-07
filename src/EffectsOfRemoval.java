import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The effects of removal off the top of this game's shoe (COUNT_DESIGN.md, section 4): the full
 * 8-deck shoe valued by ExactRound under the chart (StateValuer, as every state of a state file is
 * valued), then with k cards of each rank removed and with k added. A rank's effect is the central
 * difference, (value with k removed - value with k added) / 2k, the change in the chart's round
 * value per card of the rank removed, before insurance. Ethier and Lee computed their game's
 * effects of removal directly, finding single-deck figures converted to many decks less accurate;
 * this does the same for this game.
 *
 * java -cp CLASSES:lib/mongo-java-driver-3.12.14.jar EffectsOfRemoval [k] [threads]
 */
final class EffectsOfRemoval {

    private EffectsOfRemoval() {
    }

    /** What every first move of every deal is worth, and the chart's round value, of one shoe. */
    static double chartValue(RoundRules rules, int[] left, double[] values) {
        int[] chartMove = ScoringStates.chartMoves(rules);
        int moves = ScoringStates.MOVES;
        double chart = 0;
        double nat = 0;
        for (int i = 0; i < Deals.COUNT; i++) {
            double p = Deals.probability(left, i);
            if (p == 0) {
                continue;
            }
            double dn = Deals.dealerNatural(left, i);
            if (Deals.playerNatural(i)) {
                nat += p * (1 - dn) * rules.blackjackPayout;
                continue;
            }
            chart += p * (-dn + (1 - dn) * values[i * moves + chartMove[i]]);
        }
        return chart + nat;
    }

    /** A full shoe's ranks left, 1 to 10. */
    static int[] fullShoe() {
        int[] left = new int[11];
        for (int r = 1; r <= 10; r++) {
            left[r] = (r == 10 ? 16 : 4) * ShoeRun.DECKS;
        }
        return left;
    }

    /** The full shoe's chart value, then each rank's: [0] the full shoe; [r] removed and added, as {removed, added}. */
    static final class Effects {
        final int k;
        double full;
        final double[] removed = new double[11];
        final double[] added = new double[11];

        Effects(int k) {
            this.k = k;
        }

        /** The effect of removing one card of rank r: the central difference per card. */
        double of(int r) {
            return (removed[r] - added[r]) / (2.0 * k);
        }

        /** The effects as real tags by rank, 1 to 10: a card whose removal helps the player counts up. */
        double[] tags() {
            double[] t = new double[11];
            for (int r = 1; r <= 10; r++) {
                t[r] = of(r);
            }
            return t;
        }
    }

    static Effects effects(RoundRules rules, int k, int threads) throws Exception {
        List<int[]> shoes = new ArrayList<>();
        shoes.add(fullShoe());
        for (int r = 1; r <= 10; r++) {
            int[] less = fullShoe();
            less[r] -= k;
            int[] more = fullShoe();
            more[r] += k;
            shoes.add(less);
            shoes.add(more);
        }
        List<double[]> values;
        try (StateValuer v = new StateValuer(rules, BasicStrategy.chart(), threads)) {
            values = v.value(shoes);
        }
        Effects e = new Effects(k);
        e.full = chartValue(rules, shoes.get(0), values.get(0));
        for (int r = 1; r <= 10; r++) {
            e.removed[r] = chartValue(rules, shoes.get(2 * r - 1), values.get(2 * r - 1));
            e.added[r] = chartValue(rules, shoes.get(2 * r), values.get(2 * r));
        }
        return e;
    }

    static void print(PrintStream out, Effects e) {
        out.printf("The full shoe under the chart, before insurance: %+.5f%% a round.%n%n", 100 * e.full);
        out.printf("| Rank | %d removed | %d added | Effect of removing one card |%n", e.k, e.k);
        out.println("|---|---|---|---|");
        for (int r = 1; r <= 10; r++) {
            out.printf("| %s | %+.5f%% | %+.5f%% | %+.5f%% |%n", r == 1 ? "A" : r == 10 ? "T" : Integer.toString(r),
                    100 * e.removed[r], 100 * e.added[r], 100 * e.of(r));
        }
        double[] t = CountKeys.normalised(e.tags());
        StringBuilder b = new StringBuilder();
        for (int r = 1; r <= 10; r++) {
            b.append(r == 1 ? "" : ", ").append(String.format("%+.4f", t[r]));
        }
        out.printf("%nAs tags, centred and at Hi-Lo's spread (A, 2 to 9, T): %s.%n", b);
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        int k = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        long t0 = System.nanoTime();
        Effects e = effects(StateRun.montreal(), k, threads);
        System.out.printf("EffectsOfRemoval %s: %d cards of each rank removed and added, the 8-deck shoe valued by "
                + "StateValuer under the chart, %s.%n%n", FullIndexRun.commit(), k,
                StateRun.describe(StateRun.montreal(), BasicStrategy.chart()));
        print(System.out, e);
        System.out.printf("%n%d shoes valued in %.1f s.%n", 21, (System.nanoTime() - t0) / 1e9);
    }
}
