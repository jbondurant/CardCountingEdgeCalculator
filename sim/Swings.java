package sim;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * One figure's swings (SIM_CHECK.md section 9, SHOE_SPEC.md C0 and C1), kept on P3's main run: one
 * real strategy a shoe, shoe h played by the fold-(h mod 2) file of the figure's row (the chart's
 * row by the chart's walk), with the figure's plan's bets, every round below the figure's cut
 * counted, a round sat out with bet 0. Per shoe, Y = Σ b·X and N = its rounds, summed as ΣY, ΣY²,
 * ΣY³, ΣY⁴, ΣN, ΣN², ΣYN; per round, the exact histogram of (bet, 2X) with the rounds the player
 * split in. Every 2X is asserted whole and inside the range the rules allow, derived from them.
 */
public final class Swings {

    static final int SHOE_SUMS = 8;

    public final Registry.Fig fig;
    /** The row's place in P3's rows, and the figure's place in the row's figures. */
    public final int row;
    public final int figure;
    final double[] levels;
    final int min2x;
    final int span;
    final int offset;

    Swings(Registry.Fig fig, int row, Row r, int figure, Rules rules, int offset) {
        this.fig = fig;
        this.row = row;
        this.figure = figure;
        this.offset = offset;
        TreeSet<Double> set = new TreeSet<>();
        if (figure == 0) {
            set.add(1.0);
        } else {
            set.add(0.0);
            for (Strategy s : r.folds) {
                for (double u : s.plans.get(figure - 1).units) {
                    set.add(u);
                }
            }
        }
        levels = new double[set.size()];
        int i = 0;
        for (double d : set) {
            levels[i++] = d;
        }
        // The worst round: every hand split to the limit, doubled and lost, and the insurance lost;
        // the best: every hand doubled and won (a won insurance comes only with a dealer natural).
        int hands = Math.max(rules.maxHandsAces, rules.maxHandsOther);
        min2x = -4 * hands - (int) Math.round(2 * rules.insuranceStake);
        span = 4 * hands - min2x + 1;
    }

    int size() {
        return SHOE_SUMS + levels.length * span * 2;
    }

    /** One round of the figure's instance: the bet placed (0 when sat out), X, and whether the player split. */
    void round(double[] sums, double bet, double x, boolean split) {
        double twoX = 2 * x;
        int t = (int) twoX;
        if (t != twoX || t < min2x || t > span - 1 + min2x) {
            throw new IllegalStateException(fig.id() + ": a round's 2X of " + twoX + " is not whole or not in "
                    + min2x + " to " + (span - 1 + min2x));
        }
        int level = java.util.Arrays.binarySearch(levels, bet);
        if (level < 0) {
            throw new IllegalStateException(fig.id() + ": a bet of " + bet + " units is not one of the plan's");
        }
        int at = offset + SHOE_SUMS + (level * span + t - min2x) * 2;
        sums[at] += 1;
        if (split) {
            sums[at + 1] += 1;
        }
    }

    /** One shoe's Y and N. */
    void shoe(double[] sums, double y, long n) {
        double y2 = y * y;
        sums[offset] += 1;
        sums[offset + 1] += y;
        sums[offset + 2] += y2;
        sums[offset + 3] += y2 * y;
        sums[offset + 4] += y2 * y2;
        sums[offset + 5] += n;
        sums[offset + 6] += (double) n * n;
        sums[offset + 7] += y * n;
    }

    /** C1's shoes line and hist lines (BET, then TWOX, increasing), from a run's sums. */
    List<String> lines(double[] sums) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder("shoes\t" + fig.id() + "\t" + (long) sums[offset]);
        for (int i = 1; i < SHOE_SUMS; i++) {
            b.append('\t').append(sums[offset + i]);
        }
        out.add(b.toString());
        for (int level = 0; level < levels.length; level++) {
            for (int t = 0; t < span; t++) {
                int at = offset + SHOE_SUMS + (level * span + t) * 2;
                if (sums[at] > 0) {
                    out.add("hist\t" + fig.id() + "\t" + levels[level] + "\t" + (t + min2x) + "\t" + (long) sums[at]
                            + "\t" + (long) sums[at + 1]);
                }
            }
        }
        return out;
    }
}
