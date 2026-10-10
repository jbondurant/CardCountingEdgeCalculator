package sim;

import java.util.ArrayList;
import java.util.List;

/**
 * One row of the report at one penetration, as the simulator plays it: its two fold strategies
 * (fitted on one fold, played on the other, pooled by the folds' test weights in check P3), or,
 * for the chart, the chart's own walk, one instance at every penetration. Its figures are the
 * flat bet ({@code flat}) and each plan of its files ({@code ev:LABEL}), the names exact.tsv uses.
 *
 * <p>Its sums live in a flat array so a chunk can be written, read back and added to the bit:
 * shoes, ΣN and ΣN², then for each fold and figure ΣY, ΣY², ΣYN and the rounds whose bet key had
 * no bet line of its own, then for two folds Σ Y0·Y1 by figure. N is a shoe's rounds at the
 * row's penetration, every round dealt; Y a shoe's sum of bet × result.
 */
public final class Row {

    /** The chart's walk row, as SIM_CHECK.md's count list names it. */
    public static final String CHART = "Chart, no count, flat";

    public final String name;
    public final int pen;
    public final int cut;
    /** The fold strategies, index = fold; null for the chart's walk. */
    public final Strategy[] folds;
    public final List<String> figures = new ArrayList<>();
    public final int offset;

    Row(String name, int pen, Strategy[] folds, int offset) {
        this.name = name;
        this.pen = pen;
        int p = java.util.Arrays.binarySearch(Shoe.PENS, pen);
        if (p < 0) {
            throw new IllegalArgumentException("penetration " + pen);
        }
        this.cut = Shoe.CUTS[p];
        this.folds = folds;
        this.offset = offset;
        figures.add("flat");
        if (folds != null) {
            if (folds.length != 2 || folds[0].fold != 0 || folds[1].fold != 1 || folds[0].pen != pen || folds[1].pen != pen
                    || !folds[0].row.equals(name) || !folds[1].row.equals(name) || !folds[0].scope.equals(folds[1].scope)) {
                throw new IllegalArgumentException(name + " at " + pen + "%: needs its fold 0 and fold 1 files, same scope");
            }
            if (folds[0].plans.size() != folds[1].plans.size()) {
                throw new IllegalArgumentException(name + " at " + pen + "%: the folds' plans differ");
            }
            for (int i = 0; i < folds[0].plans.size(); i++) {
                String label = folds[0].plans.get(i).label;
                if (!label.equals(folds[1].plans.get(i).label)) {
                    throw new IllegalArgumentException(name + " at " + pen + "%: the folds' plans differ");
                }
                figures.add("ev:" + label);
            }
        }
    }

    public static Row chart(int pen, int offset) {
        return new Row(CHART, pen, null, offset);
    }

    public static Row of(Strategy fold0, Strategy fold1, int offset) {
        return new Row(fold0.row, fold0.pen, new Strategy[]{fold0, fold1}, offset);
    }

    public int instances() {
        return folds == null ? 1 : 2;
    }

    /** How many doubles this row's sums take. */
    public int size() {
        int f = instances();
        int j = figures.size();
        return 3 + f * j * 4 + (f == 2 ? j : 0);
    }

    int at(int fold, int figure) {
        return offset + 3 + (fold * figures.size() + figure) * 4;
    }

    int cross(int figure) {
        return offset + 3 + 2 * figures.size() * 4 + figure;
    }

    /** Adds one shoe's Y (by fold and figure) and N to the sums. */
    void addShoe(double[] sums, double[][] y, long n, long[][] unseen) {
        sums[offset] += 1;
        sums[offset + 1] += n;
        sums[offset + 2] += (double) n * n;
        for (int f = 0; f < instances(); f++) {
            for (int j = 0; j < figures.size(); j++) {
                int a = at(f, j);
                sums[a] += y[f][j];
                sums[a + 1] += y[f][j] * y[f][j];
                sums[a + 2] += y[f][j] * n;
                sums[a + 3] += unseen[f][j];
            }
        }
        if (instances() == 2) {
            for (int j = 0; j < figures.size(); j++) {
                sums[cross(j)] += y[0][j] * y[1][j];
            }
        }
    }

    /**
     * SIM_CHECK.md section 6's P3 estimate of a figure: the folds pooled shoe by shoe with weights
     * w0 and w1 (1 and 0 for the chart), μ = ΣY / ΣN, and the ratio estimator's SE with shoes as
     * the units, sqrt(S/(S − 1) · Σ(Y − μN)²) / ΣN. Returns {μ, SE, ΣN, S}.
     */
    public double[] estimate(double[] sums, int figure, double w0, double w1) {
        double s = sums[offset];
        double sn = sums[offset + 1];
        double snn = sums[offset + 2];
        int a = at(0, figure);
        double sy = w0 * sums[a];
        double syy = w0 * w0 * sums[a + 1];
        double syn = w0 * sums[a + 2];
        if (instances() == 2) {
            int b = at(1, figure);
            sy += w1 * sums[b];
            syy += 2 * w0 * w1 * sums[cross(figure)] + w1 * w1 * sums[b + 1];
            syn += w1 * sums[b + 2];
        }
        double mu = sy / sn;
        double ss = syy - 2 * mu * syn + mu * mu * snn;
        return new double[]{mu, Math.sqrt(s / (s - 1) * Math.max(0, ss)) / sn, sn, s};
    }

    /** Rounds of fold f that bet at a key with no bet line of its own, for figure j (a plan). */
    public double unseen(double[] sums, int fold, int figure) {
        return sums[at(fold, figure) + 3];
    }
}
