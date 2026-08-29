import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which hands late surrender is worth taking, and by how much.
 *
 * Late surrender is offered after the dealer has peeked, so it exists only on the hands
 * the peek did not already settle. That makes the test a straight comparison: forfeit half
 * the bet, or play the hand for whatever the best legal move is worth. Surrender wins
 * exactly when that best move is worth less than -0.5.
 *
 * Both sides of that comparison are conditioned on the same event -- the dealer not having
 * a natural -- which is what makes it legitimate. RandomVsOptimalReport's dealer model
 * already drops the natural hole card and renormalises, so the values reused here are the
 * conditional ones the comparison needs. Early surrender would not work this way, since it
 * is taken before the peek and so applies to hands this model has excluded.
 *
 * The margin is printed rather than a yes/no because several cells sit within a hundredth
 * of the line. There an infinite-deck model and a real eight-deck shoe can disagree, and a
 * table that only printed the verdict would hide that.
 *
 * Run: java SurrenderIndexReport
 */
public class SurrenderIndexReport {

    private static final double SURRENDER_VALUE = -0.5;

    /** How close to the line counts as too close to call on deck composition alone. */
    private static final double BORDERLINE = 0.03;

    private static final class Cell {
        final String hand;
        final int upCard;
        final String bestMove;
        final double bestValue;

        Cell(String hand, int upCard, String bestMove, double bestValue) {
            this.hand = hand;
            this.upCard = upCard;
            this.bestMove = bestMove;
            this.bestValue = bestValue;
        }

        /** Positive when surrender is the better of the two. */
        double margin() {
            return SURRENDER_VALUE - bestValue;
        }
    }

    public static void main(String[] args) {
        new SurrenderIndexReport().run();
    }

    private List<Cell> everyCell() {
        // 21 rather than the report's default: nothing here is about the random player, so
        // the optimal solver should be free to consider hitting any total.
        RandomVsOptimalReport solver = new RandomVsOptimalReport(21);
        List<Cell> cells = new ArrayList<>();
        for (int up = 2; up <= 11; up++) {
            for (int total = 5; total <= 20; total++) {
                cells.add(cellOf(solver, "hard " + total, total, false, null, up));
            }
            for (int kicker = 2; kicker <= 9; kicker++) {
                cells.add(cellOf(solver, "soft " + (11 + kicker), 11 + kicker, true, null, up));
            }
            for (int rank = 1; rank <= 10; rank++) {
                int total = rank == 1 ? 12 : rank * 2;
                boolean soft = rank == 1;
                String name = rank == 1 ? "A,A" : rank + "," + rank;
                cells.add(cellOf(solver, name, total, soft, rank, up));
            }
        }
        return cells;
    }

    private Cell cellOf(RandomVsOptimalReport solver, String label, int total, boolean soft,
                        Integer pairRank, int upCard) {
        RandomVsOptimalReport.Row row = solver.evaluate(label, total, soft, pairRank, upCard);
        return new Cell(label, upCard, row.bestByOptimal, row.valueOfRightMove);
    }

    private static String upCardName(int up) {
        return up == 11 ? "A" : String.valueOf(up);
    }

    public void run() {
        List<Cell> cells = everyCell();

        System.out.println("Late surrender against best play.");
        System.out.println("Infinite deck, H17, dealer peeks, 3:2, DAS, resplit to 4.");
        System.out.println("Values are exact expectations conditioned on no dealer natural.");
        System.out.println("A positive margin means surrender beats playing the hand.");
        System.out.println();

        List<Cell> surrenders = new ArrayList<>();
        for (Cell c : cells) {
            if (c.margin() > 0.0) {
                surrenders.add(c);
            }
        }
        surrenders.sort(Comparator.comparingDouble((Cell c) -> -c.margin()));

        System.out.println("Cells where surrender wins (" + surrenders.size() + "):");
        System.out.println();
        System.out.printf("%-8s %4s   %-6s %10s %9s%n",
                "hand", "vs", "best", "best EV", "margin");
        for (Cell c : surrenders) {
            System.out.printf("%-8s %4s   %-6s %+10.4f %+9.4f%n",
                    c.hand, upCardName(c.upCard), c.bestMove, c.bestValue, c.margin());
        }

        List<Cell> borderline = new ArrayList<>();
        for (Cell c : cells) {
            if (Math.abs(c.margin()) <= BORDERLINE) {
                borderline.add(c);
            }
        }
        borderline.sort(Comparator.comparingDouble((Cell c) -> -c.margin()));

        System.out.println();
        System.out.println("Within " + BORDERLINE + " of the line either way ("
                + borderline.size() + "), where deck composition can decide it:");
        System.out.println();
        System.out.printf("%-8s %4s   %-6s %10s %9s   %s%n",
                "hand", "vs", "best", "best EV", "margin", "verdict");
        for (Cell c : borderline) {
            System.out.printf("%-8s %4s   %-6s %+10.4f %+9.4f   %s%n",
                    c.hand, upCardName(c.upCard), c.bestMove, c.bestValue, c.margin(),
                    c.margin() > 0.0 ? "surrender" : "play it");
        }

        System.out.println();
        System.out.println("Dealer finishing distribution, for checking against published tables:");
        System.out.println();
        RandomVsOptimalReport solver = new RandomVsOptimalReport(21);
        System.out.printf("%4s %8s %8s %8s %8s %8s %8s%n",
                "up", "17", "18", "19", "20", "21", "bust");
        for (int up = 2; up <= 11; up++) {
            double[] d = solver.dealerDistributionFor(up);
            System.out.printf("%4s %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f%n",
                    upCardName(up), d[0], d[1], d[2], d[3], d[4], d[5]);
        }

        System.out.println();
        System.out.println("The whole ace column, worst hands first:");
        System.out.println();
        List<Cell> vsAce = new ArrayList<>();
        for (Cell c : cells) {
            if (c.upCard == 11) {
                vsAce.add(c);
            }
        }
        vsAce.sort(Comparator.comparingDouble((Cell c) -> c.bestValue));
        System.out.printf("%-8s   %-6s %10s %9s%n", "hand", "best", "best EV", "margin");
        for (int i = 0; i < 12; i++) {
            Cell c = vsAce.get(i);
            System.out.printf("%-8s   %-6s %+10.4f %+9.4f%n",
                    c.hand, c.bestMove, c.bestValue, c.margin());
        }
    }
}
