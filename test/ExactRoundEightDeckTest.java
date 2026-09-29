import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExactRound on real eight-deck shoes, held to the values it gave before its split valuation
 * was rewritten for speed.
 *
 * ExactVsBruteForceTest checks splits against BruteForceRound only on shoes of up to 50
 * cards, where every rank runs short and a four-hand split rarely happens. The counting
 * comparison values splits on shoes of hundreds of cards. So the values below were computed
 * once, by the engine as it stood at commit b046209, and saved in ExactRoundEightDeckTest.tsv:
 * every split deal, ten pairs against ten up-cards, and Stand, Hit and Double on every fifth
 * deal, on each of StateTimingReport's four states, under StateRun's Montreal rules with
 * BasicStrategy's chart after the first move. A rearrangement of the arithmetic may change
 * the last few bits and nothing more.
 *
 * Each state is valued with one ExactRound, in StateValuer's order, as a thread valuing that
 * state would, so that anything one call leaves behind for the next is exercised too.
 *
 * To write the file again, from the engine as it is: java -cp build/classes:build/test-classes
 * ExactRoundEightDeckTest > test/ExactRoundEightDeckTest.tsv
 */
public class ExactRoundEightDeckTest {

    private static final double EXACT = 1e-12;
    private static final Path FIXTURE = Paths.get("test", "ExactRoundEightDeckTest.tsv");

    /** Every split deal, and Stand, Hit and Double on every fifth deal. */
    private static boolean sampled(int deal, PlayerMove m) {
        return m == PlayerMove.Split || deal % 5 == 0;
    }

    /** One value the fixture holds, or the engine gives. */
    private static final class Row {
        final int shoe;
        final int deal;
        final PlayerMove move;
        final double value;

        Row(int shoe, int deal, PlayerMove move, double value) {
            this.shoe = shoe;
            this.deal = deal;
            this.move = move;
            this.value = value;
        }

        String describe() {
            return StateTimingReport.NAMES[shoe] + ": " + Deals.P1[deal] + "," + Deals.P2[deal] + " vs "
                    + Deals.UP[deal] + " " + move;
        }
    }

    /** What the engine gives now for every sampled deal and move of every state. */
    private static List<Row> valueNow() {
        RoundRules rules = StateRun.montreal();
        RoundPolicy chart = BasicStrategy.chart();
        List<Row> rows = new ArrayList<>();
        try (StateValuer legality = new StateValuer(rules, chart, 1)) {
            for (int s = 0; s < StateTimingReport.SHOES.length; s++) {
                int[] shoe = StateTimingReport.SHOES[s];
                ExactRound exact = new ExactRound(rules);
                for (int deal = 0; deal < Deals.COUNT; deal++) {
                    if (Deals.playerNatural(deal) || Deals.probability(shoe, deal) == 0) {
                        continue;
                    }
                    int[] left = Deals.after(shoe, deal);
                    for (PlayerMove m : StateValuer.MOVES) {
                        if (legality.legal(deal, m) && sampled(deal, m)) {
                            rows.add(new Row(s, deal, m, exact.valueOfFirstMove(left, Deals.P1[deal],
                                    Deals.P2[deal], Deals.UP[deal], m, chart)));
                        }
                    }
                }
            }
        }
        return rows;
    }

    @Test
    public void everySplitAndASampleOfTheRestMatchTheSavedValues() throws IOException {
        List<String> lines = Files.readAllLines(FIXTURE, StandardCharsets.UTF_8);
        List<Row> saved = new ArrayList<>();
        int shoesSeen = 0;
        for (String line : lines) {
            if (line.startsWith("# shoe ")) {
                // The states the values were computed on, so that a change to them is caught
                // here rather than read as a change in value.
                String[] f = line.substring("# shoe ".length()).split("[: ]+");
                int s = Integer.parseInt(f[0]);
                int[] shoe = new int[11];
                for (int r = 1; r <= 10; r++) {
                    shoe[r] = Integer.parseInt(f[r]);
                }
                assertArrayEquals(StateTimingReport.SHOES[s], shoe, "state " + s + " is not the one saved");
                shoesSeen++;
                continue;
            }
            if (line.startsWith("#") || line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t");
            int deal = Deals.index(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]));
            saved.add(new Row(Integer.parseInt(f[0]), deal, PlayerMove.valueOf(f[4]), Double.parseDouble(f[5])));
        }
        assertEquals(StateTimingReport.SHOES.length, shoesSeen, "states named in the fixture");

        List<Row> now = valueNow();
        assertEquals(saved.size(), now.size(), "the number of values");
        int splits = 0;
        double worst = 0;
        for (int i = 0; i < saved.size(); i++) {
            Row was = saved.get(i);
            Row is = now.get(i);
            assertEquals(was.describe(), is.describe(), "the values are in another order");
            assertEquals(was.value, is.value, EXACT, is.describe());
            worst = Math.max(worst, Math.abs(was.value - is.value));
            splits += is.move == PlayerMove.Split ? 1 : 0;
        }
        // Ten pairs against ten up-cards on each state: every one of them can be dealt.
        assertEquals(100 * StateTimingReport.SHOES.length, splits, "split deals");
        System.out.printf("ExactRound on %d eight-deck states: %d values, %d of them splits, "
                + "worst gap from the saved values %.1e%n", StateTimingReport.SHOES.length, now.size(), splits, worst);
    }

    /** Writes the fixture from the engine as it is now. */
    public static void main(String[] args) {
        StringBuilder out = new StringBuilder();
        out.append("# ExactRound's values on eight-deck states: every split deal, and Stand, Hit and\n");
        out.append("# Double on every fifth deal. Montreal's rules (StateRun), BasicStrategy's chart after\n");
        out.append("# the first move. Written by ExactRoundEightDeckTest.main.\n");
        for (int s = 0; s < StateTimingReport.SHOES.length; s++) {
            int[] shoe = StateTimingReport.SHOES[s];
            StringBuilder counts = new StringBuilder();
            for (int r = 1; r <= 10; r++) {
                counts.append(' ').append(shoe[r]);
            }
            out.append("# shoe ").append(s).append(':').append(counts).append("   (")
                    .append(StateTimingReport.NAMES[s]).append(", ace to ten)\n");
        }
        out.append("# shoe\tp1\tp2\tup\tmove\tvalue\n");
        for (Row row : valueNow()) {
            out.append(row.shoe).append('\t').append(Deals.P1[row.deal]).append('\t').append(Deals.P2[row.deal])
                    .append('\t').append(Deals.UP[row.deal]).append('\t').append(row.move).append('\t')
                    .append(row.value).append('\n');
        }
        System.out.print(out);
    }
}
