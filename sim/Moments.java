package sim;

/**
 * Per-shoe moments of a vector of k quantities, for ratio estimates and their paired differences
 * with shoes as the independent units (SIM_CHECK.md section 6, section 9). For each shoe h a
 * vector v_h is added: the count S, each Σ v_i and each Σ v_i·v_j (i ≤ j). A ratio of two linear
 * combinations, μ = Σ a·v / Σ b·v, then has the linearised SE sqrt(S/(S − 1) · Σ_h ((a − μb)·v_h)²)
 * / Σ b·v, the ratio estimator's of section 6; the difference of two such ratios on the same
 * shoes has the SE of the difference of their linearised residuals, so what the two share cancels.
 * All of it lives in a flat array of doubles at an offset, so it rides in a run's chunks.
 */
public final class Moments {

    private Moments() {
    }

    /** The doubles k quantities take: S, k sums, k(k+1)/2 products. */
    public static int size(int k) {
        return 1 + k + k * (k + 1) / 2;
    }

    /** Adds one shoe's vector. */
    public static void add(double[] sums, int at, double[] v) {
        int k = v.length;
        sums[at] += 1;
        int p = at + 1 + k;
        for (int i = 0; i < k; i++) {
            sums[at + 1 + i] += v[i];
            for (int j = i; j < k; j++) {
                sums[p++] += v[i] * v[j];
            }
        }
    }

    static double shoes(double[] sums, int at) {
        return sums[at];
    }

    /** Σ c·v_h over shoes. */
    static double sum(double[] sums, int at, double[] c) {
        double s = 0;
        for (int i = 0; i < c.length; i++) {
            s += c[i] * sums[at + 1 + i];
        }
        return s;
    }

    /** Σ_h (c·v_h)². */
    static double square(double[] sums, int at, double[] c) {
        int k = c.length;
        int p = at + 1 + k;
        double s = 0;
        for (int i = 0; i < k; i++) {
            for (int j = i; j < k; j++) {
                s += (i == j ? 1 : 2) * c[i] * c[j] * sums[p++];
            }
        }
        return s;
    }

    /** μ = Σ a·v / Σ b·v, its SE, Σ b·v and S. */
    public static double[] ratio(double[] sums, int at, double[] a, double[] b) {
        double s = shoes(sums, at);
        double den = sum(sums, at, b);
        double mu = sum(sums, at, a) / den;
        double[] c = new double[a.length];
        for (int i = 0; i < c.length; i++) {
            c[i] = a[i] - mu * b[i];
        }
        return new double[]{mu, Math.sqrt(s / (s - 1) * Math.max(0, square(sums, at, c))) / den, den, s};
    }

    /**
     * The paired difference of two ratios on the same shoes, a1/b1 less a2/b2: {difference, its SE,
     * σ_eff of the per-round difference (the SE times the root of Σ b1·v), the two ratios}.
     */
    public static double[] difference(double[] sums, int at, double[] a1, double[] b1, double[] a2, double[] b2) {
        double s = shoes(sums, at);
        double den1 = sum(sums, at, b1);
        double den2 = sum(sums, at, b2);
        double mu1 = sum(sums, at, a1) / den1;
        double mu2 = sum(sums, at, a2) / den2;
        double[] c = new double[a1.length];
        for (int i = 0; i < c.length; i++) {
            c[i] = (a1[i] - mu1 * b1[i]) / den1 - (a2[i] - mu2 * b2[i]) / den2;
        }
        double se = Math.sqrt(s / (s - 1) * Math.max(0, square(sums, at, c)));
        return new double[]{mu1 - mu2, se, se * Math.sqrt(den1), mu1, mu2};
    }
}
