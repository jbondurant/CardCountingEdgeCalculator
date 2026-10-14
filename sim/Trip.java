package sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Trip risk and the chance of being behind (SIM_CHECK.md section 11, PLAN_COMPUTE.md 2.4 check
 * 4), from a figure's per-shoe records (records-FIG.tsv, SHOE_SPEC.md C2), beside the formula at
 * the records' own μ and σ_eff, as the ruin paths are ({@link Ruin}).
 *
 * <p>For T = H·r rounds (H hours at r rounds an hour), the records are cut into consecutive blocks
 * of whole shoes in shoe order from shoe 0, each the fewest shoes whose rounds reach T; a last
 * incomplete block is dropped. A block's running total starts at 0, and its lowest point is the
 * least, over the block's shoes, of the block's total before that shoe plus the shoe's M (M is
 * already the lowest within the shoe, counting 0 before its first round). At bankroll B a block is
 * a lost trip when B plus its lowest point is at or below 0, as a ruin path is ruined; it is behind
 * when its total is below 0. Each simulated share has its binomial SE over the blocks, which are
 * independent (they share no shoe).
 *
 * <p>The formulas, with drift μ and SD σ = σ_eff a round over T rounds: losing B within T, by
 * Brownian first passage, Φ((−B − μT)/(σ√T)) + exp(−2μB/σ²)·Φ((−B + μT)/(σ√T)), which is
 * 2Φ(−B/(σ√T)) at μ = 0 and exp(−2μB/σ²) as T grows (PLAN_COMPUTE.md 2.4, check 6); behind after
 * T rounds, Φ(−μ√T/σ). The bankrolls are the ruin paths' B_25%, B_5% and B_1% (2.4 check 4: "the
 * same for trip risk and P(behind)"). H and r are read as sim/QUESTIONS.md item 24 says.
 */
public final class Trip {

    /** The hours and paces by default (sim/QUESTIONS.md item 24). */
    public static final int[] HOURS = {10, 100, 1000};
    public static final int[] PACES = {50, 80, 100};

    private final Ruin records;

    public Trip(Ruin records) {
        this.records = records;
    }

    /** The standard normal distribution function, to about 1e-15 absolute. */
    public static double phi(double x) {
        if (Double.isNaN(x)) {
            return Double.NaN;
        }
        return x >= 0 ? 1 - upper(x) : upper(-x);
    }

    /** Q(t) = 1 − Φ(t) for t ≥ 0: Marsaglia's series below 3, the continued fraction from 3 up. */
    static double upper(double t) {
        double density = Math.exp(-t * t / 2) / Math.sqrt(2 * Math.PI);
        if (t < 3) {
            double term = t;
            double sum = t;
            for (int n = 1; n < 200 && Math.abs(term) > 1e-17 * Math.abs(sum); n++) {
                term *= t * t / (2 * n + 1);
                sum += term;
            }
            return 0.5 - density * sum;
        }
        double f = t;
        for (int n = 300; n >= 1; n--) {
            f = t + n / f;
        }
        return density / f;
    }

    /** The formula's chance of losing b within t rounds, drift mu and SD sigma a round. */
    public static double tripRisk(double mu, double sigma, double b, double t) {
        double s = sigma * Math.sqrt(t);
        double first = phi((-b - mu * t) / s);
        if (mu == 0) {
            return 2 * first;
        }
        double exponent = -2 * mu * b / (sigma * sigma);
        double second = phi((-b + mu * t) / s);
        return first + (second == 0 ? 0 : Math.exp(exponent) * second);
    }

    /** The formula's chance of being behind after t rounds. */
    public static double behind(double mu, double sigma, double t) {
        return phi(-mu * Math.sqrt(t) / sigma);
    }

    /** One block of whole shoes: its rounds, its total and its lowest running total. */
    record Block(long rounds, double total, double low) {
    }

    /** The complete blocks of at least t rounds each, in shoe order. */
    List<Block> blocks(long t) {
        List<Block> out = new ArrayList<>();
        long rounds = 0;
        double total = 0;
        double low = 0;
        for (int i = 0; i < records.y.length; i++) {
            low = Math.min(low, total + records.m[i]);
            total += records.y[i];
            rounds += records.n[i];
            if (rounds >= t) {
                out.add(new Block(rounds, total, low));
                rounds = 0;
                total = 0;
                low = 0;
            }
        }
        return out;
    }

    /**
     * For each hours and pace: a behind line {hours, pace, T, blocks, mean rounds a block, -1, NaN,
     * simulated, SE, formula}, then, when μ > 0, a trip line for each of Ruin.RISKS {..., risk, B,
     * simulated, SE, formula}.
     */
    public List<double[]> run(int[] hours, int[] paces) {
        List<double[]> out = new ArrayList<>();
        double mu = records.mu;
        double sigma = records.sigmaEff;
        for (int h : hours) {
            for (int r : paces) {
                long t = (long) h * r;
                List<Block> blocks = blocks(t);
                int nb = blocks.size();
                double rounds = 0;
                long behind = 0;
                for (Block b : blocks) {
                    rounds += b.rounds;
                    behind += b.total < 0 ? 1 : 0;
                }
                double mean = nb == 0 ? Double.NaN : rounds / nb;
                out.add(line(h, r, t, nb, mean, -1, Double.NaN, behind, behind(mu, sigma, t)));
                for (int k = 0; mu > 0 && k < Ruin.RISKS.length; k++) {
                    double bank = records.bankroll(Ruin.RISKS[k]);
                    long lost = 0;
                    for (Block b : blocks) {
                        lost += bank + b.low <= 0 ? 1 : 0;
                    }
                    out.add(line(h, r, t, nb, mean, Ruin.RISKS[k], bank, lost, tripRisk(mu, sigma, bank, t)));
                }
            }
        }
        return out;
    }

    private static double[] line(int h, int r, long t, int blocks, double mean, double risk, double bank, long count,
                                 double formula) {
        double p = blocks == 0 ? Double.NaN : (double) count / blocks;
        return new double[]{h, r, t, blocks, mean, risk, bank, p, Math.sqrt(p * (1 - p) / blocks), formula};
    }

    /** trip-FIG.tsv: the simulator's own format (sim/QUESTIONS.md item 24). */
    public List<String> lines(String fig, List<double[]> results) {
        List<String> out = new ArrayList<>(List.of(
                "# trip-" + fig + ".tsv: SIM_CHECK.md section 11's trip risk and chance of being behind from records-" + fig
                        + ".tsv (" + records.y.length + " shoes): consecutive blocks of whole shoes from shoe 0, each the fewest"
                        + " reaching hours x pace rounds, a last incomplete block dropped; a trip is lost when bankroll + the"
                        + " block's lowest running total <= 0, behind when the block's total < 0; formula at the records' mu and"
                        + " sigmaEff over hours x pace rounds",
                "# mu\t" + records.mu, "# sigmaEff\t" + records.sigmaEff,
                "kind\thours\tpace\trounds\tblocks\tmeanRounds\trisk\tbankroll\tsimulated\tse\tformula"));
        for (double[] v : results) {
            boolean trip = v[5] >= 0;
            out.add((trip ? "trip" : "behind") + "\t" + (long) v[0] + "\t" + (long) v[1] + "\t" + (long) v[2] + "\t"
                    + (long) v[3] + "\t" + v[4] + "\t" + (trip ? Double.toString(v[5]) : "-") + "\t"
                    + (trip ? Double.toString(v[6]) : "-") + "\t" + v[7] + "\t" + v[8] + "\t" + v[9]);
        }
        return out;
    }
}
