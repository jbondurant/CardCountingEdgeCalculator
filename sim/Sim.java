package sim;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * The simulator's command line. {@code javac -d out sim/*.java}, then {@code java -cp out sim.Sim
 * COMMAND ...}:
 * <ul>
 * <li>{@code walk [--chart chart.tsv] [--shoes N]}: check D1's totals. Walks the main file's
 * first N shoes (all 60,000 by default) with the chart and prints the round starts, valued states
 * and weights at the three cuts; with all 60,000 it compares them with SHOE_SPEC.md A8. Without
 * {@code --chart} it plays {@link Chart#textbook()}, the stand-in.
 * </ul>
 */
public final class Sim {

    /** SHOE_SPEC.md A2's rounds.*, valued.* and weight.* lines: what the 60,000 main shoes must give. */
    public static final long[] A8_ROUNDS = {3_197_853L, 3_426_592L, 3_644_432L};
    public static final long[] A8_VALUED = {45_495L, 52_611L, 60_743L};
    public static final long[] A8_WEIGHT = {3_222_940L, 3_453_206L, 3_667_722L};

    private Sim() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: java -cp out sim.Sim walk [--chart chart.tsv] [--shoes N]");
            System.exit(2);
        }
        switch (args[0]) {
            case "walk":
                System.exit(walk(Arrays.copyOfRange(args, 1, args.length)) ? 0 : 1);
                break;
            default:
                System.err.println("unknown command " + args[0]);
                System.exit(2);
        }
    }

    static String option(String[] args, String name, String otherwise) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return otherwise;
    }

    /** Walks the shoes and, for all 60,000, holds the totals to A8; true when they match or were not compared. */
    static boolean walk(String[] args) throws Exception {
        String chartFile = option(args, "--chart", null);
        Chart chart = chartFile == null ? Chart.textbook() : Chart.load(Path.of(chartFile));
        int shoes = Integer.parseInt(option(args, "--shoes", Integer.toString(Shoe.SHOES_MAIN)));
        long t0 = System.nanoTime();
        Walk.Totals totals = Walk.mainTotals(0, shoes, new Round(Rules.montreal()), chart);
        double seconds = (System.nanoTime() - t0) / 1e9;
        System.out.println("chart " + (chartFile == null ? "textbook stand-in" : chartFile)
                + ", fingerprint " + chart.fingerprint() + "; shoes 0 to " + (shoes - 1)
                + " of seed " + Shoe.SEED_MAIN + "; " + String.format("%.1f", seconds) + " s");
        boolean all = shoes == Shoe.SHOES_MAIN;
        boolean ok = true;
        for (int p = 0; p < 3; p++) {
            long w = Math.round(totals.weight[p]);
            String line = Shoe.PENS[p] + "% (cut " + Shoe.CUTS[p] + "): rounds " + totals.rounds[p]
                    + ", valued " + totals.valued[p] + ", weight " + w;
            if (all) {
                boolean match = totals.rounds[p] == A8_ROUNDS[p] && totals.valued[p] == A8_VALUED[p] && w == A8_WEIGHT[p];
                ok &= match;
                line += match ? "  = A8" : "  differs from A8: rounds " + A8_ROUNDS[p] + ", valued "
                        + A8_VALUED[p] + ", weight " + A8_WEIGHT[p];
            }
            System.out.println(line);
        }
        System.out.println("fewest cards left at a round start " + totals.fewestLeft
                + "; most cards one round took " + totals.longestRound);
        return ok;
    }
}
