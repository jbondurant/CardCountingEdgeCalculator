import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Every first move of every deal, valued in parallel, is what ExactRound gives alone. */
public class StateValuerTest {

    @Test
    public void theValuesAreExactRoundsAndTheSameOnAnyNumberOfThreads() throws Exception {
        RoundRules rules = StateRun.montreal();
        RoundPolicy chart = BasicStrategy.chart();
        // Two decks with some cards gone, one of them all the sevens but one.
        int[] shoe = new int[11];
        for (int r = 1; r <= 9; r++) {
            shoe[r] = 8;
        }
        shoe[10] = 32;
        shoe[7] = 1;
        shoe[3] = 5;
        List<double[]> one;
        List<double[]> four;
        try (StateValuer v = new StateValuer(rules, chart, 1)) {
            one = v.value(List.of(shoe));
        }
        try (StateValuer v = new StateValuer(rules, chart, 4)) {
            four = v.value(List.of(shoe, shoe));
        }
        assertArrayEquals(one.get(0), four.get(0));
        assertArrayEquals(one.get(0), four.get(1));

        double[] values = one.get(0);
        int valued = 0;
        for (int i = 0; i < Deals.COUNT; i++) {
            for (int m = 0; m < StateValuer.MOVES.length; m++) {
                double v = values[i * StateValuer.MOVES.length + m];
                PlayerMove move = StateValuer.MOVES[m];
                boolean possible = Deals.probability(shoe, i) > 0;
                boolean legal = !Deals.playerNatural(i) && move != PlayerMove.Surrender
                        && (move != PlayerMove.Split || Deals.P1[i] == Deals.P2[i]);
                if (!possible || !legal) {
                    assertTrue(Double.isNaN(v), "deal " + i + " " + move);
                    continue;
                }
                valued++;
                if (i % 37 == 0 || Deals.P1[i] == 7) {
                    double direct = new ExactRound(rules).valueOfFirstMove(Deals.after(shoe, i), Deals.P1[i],
                            Deals.P2[i], Deals.UP[i], move, chart);
                    assertEquals(direct, v, 0.0, "deal " + i + " " + move);
                }
            }
        }
        // 7,7 cannot be dealt with one seven left: 10 up-cards x 9 non-seven pairs x 4 moves,
        // plus 44 other deals x 3 moves each, less the naturals (none have moves).
        assertTrue(valued > 1600, valued + " values");
        for (int up = 1; up <= 10; up++) {
            assertEquals(0, Deals.probability(shoe, Deals.index(7, 7, up)));
        }
    }
}
