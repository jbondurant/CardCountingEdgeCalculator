package sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The ruin paths of SIM_CHECK.md section 11, for PLAN_COMPUTE.md 2.4's check of the risk formula
 * against simulated ruin: 20,000 bankroll paths, each drawing shoes with replacement from a
 * figure's per-shoe records (records-FIG.tsv, SHOE_SPEC.md C2) with a generator of its own,
 * {@code Xoshiro.of(20261010, path, 0, 0)}. Each path is run from B_25%, B_5% and B_1% on the same
 * draws, is ruined when its bankroll plus the shoe's lowest running total M is at or below 0, and
 * stops, survived, once the formula's remaining risk at its bankroll is under 5e-5.
 *
 * <p>The formula is the long-run risk of ruin of a drift μ and SD σ a round, exp(−2μB/σ²), so
 * B_p = σ² ln(1/p) / (2μ); μ and σ = σ_eff are the simulator's own from the same records, μ =
 * ΣY/ΣN and σ_eff² = S/(S − 1) · Σ(Y − μN)² / ΣN (SIM_CHECK.md section 6). This reading of "the
 * formula's" is the simulator's (sim/QUESTIONS.md).
 */
public final class Ruin {

    public static final long SEED = 20261010L;
    public static final double[] RISKS = {0.25, 0.05, 0.01};
    public static final double STOP = 5e-5;
    /** A path that has drawn this many shoes without ruin or stop is counted apart, as capped. */
    public static final long CAP = 10_000_000L;

    final double[] y;
    final long[] n;
    final double[] m;
    public final double mu;
    public final double sigmaEff;

    Ruin(double[] y, long[] n, double[] m) {
        this.y = y;
        this.n = n;
        this.m = m;
        double sy = 0, sn = 0;
        for (int i = 0; i < y.length; i++) {
            sy += y[i];
            sn += n[i];
        }
        mu = sy / sn;
        double ss = 0;
        for (int i = 0; i < y.length; i++) {
            ss += (y[i] - mu * n[i]) * (y[i] - mu * n[i]);
        }
        sigmaEff = Math.sqrt(y.length / (y.length - 1.0) * ss / sn);
    }

    /** records-FIG.tsv's lines, Y N M a shoe, each M at most 0 and at most Y, each N at least 1. */
    public static Ruin read(Path records) throws IOException {
        List<String> lines = Files.readAllLines(records, StandardCharsets.UTF_8);
        double[] y = new double[lines.size()];
        long[] n = new long[lines.size()];
        double[] m = new double[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            String[] f = lines.get(i).split("\t", -1);
            y[i] = Double.parseDouble(f[0]);
            n[i] = Long.parseLong(f[1]);
            m[i] = Double.parseDouble(f[2]);
            if (f.length != 3 || n[i] < 1 || m[i] > 0 || m[i] > y[i]) {
                throw new IOException(records + " line " + (i + 1) + " is not 'Y N M' with N >= 1, M <= 0 and M <= Y");
            }
        }
        return new Ruin(y, n, m);
    }

    /** The bankroll at which the formula's risk is p. */
    public double bankroll(double p) {
        if (!(mu > 0)) {
            throw new IllegalStateException("the records' mean result a round is " + mu + ": no bankroll has a finite risk");
        }
        return sigmaEff * sigmaEff * Math.log(1 / p) / (2 * mu);
    }

    /** One path from bankroll b: 1 if ruined, 0 if it reached the stop, -1 if capped; shoes drawn in shoes[0]. */
    int path(int index, double b, long[] shoes) {
        Xoshiro g = Xoshiro.of(SEED, index, 0, 0);
        double stop = bankroll(STOP);
        for (long s = 0; s < CAP; s++) {
            int i = g.nextInt(y.length);
            shoes[0]++;
            if (b + m[i] <= 0) {
                return 1;
            }
            b += y[i];
            if (b > stop) {
                return 0;
            }
        }
        return -1;
    }

    /** For each risk p: {B_p, paths, ruined, simulated rate, its binomial SE, capped, mean shoes a path}. */
    public double[][] run(int paths) {
        double[][] out = new double[RISKS.length][];
        for (int r = 0; r < RISKS.length; r++) {
            double b = bankroll(RISKS[r]);
            long ruined = 0, capped = 0;
            long[] shoes = {0};
            for (int j = 0; j < paths; j++) {
                int v = path(j, b, shoes);
                ruined += v == 1 ? 1 : 0;
                capped += v == -1 ? 1 : 0;
            }
            double rate = (double) ruined / paths;
            out[r] = new double[]{b, paths, ruined, rate, Math.sqrt(rate * (1 - rate) / paths), capped, (double) shoes[0] / paths};
        }
        return out;
    }

    /** ruin-FIG.tsv: the simulator's own format (sim/QUESTIONS.md). */
    public List<String> lines(String fig, double[][] results) {
        List<String> out = new ArrayList<>(List.of(
                "# ruin-" + fig + ".tsv: SIM_CHECK.md section 11's ruin paths from records-" + fig + ".tsv (" + y.length
                        + " shoes), seed " + SEED + ", Xoshiro.of(seed, path, 0, 0) drawing shoes with replacement; ruined when"
                        + " bankroll + M <= 0, stopped when exp(-2 mu b / sigma^2) < " + STOP,
                "# mu\t" + mu, "# sigmaEff\t" + sigmaEff,
                "risk\tbankroll\tpaths\truined\tsimulated\tse\tcapped\tmeanShoes"));
        for (int r = 0; r < RISKS.length; r++) {
            double[] v = results[r];
            out.add(RISKS[r] + "\t" + v[0] + "\t" + (long) v[1] + "\t" + (long) v[2] + "\t" + v[3] + "\t" + v[4] + "\t"
                    + (long) v[5] + "\t" + v[6]);
        }
        return out;
    }
}
