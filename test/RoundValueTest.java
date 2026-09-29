import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The value of a whole round in a state, from the stored values, held to playing every
 * order of a small shoe: the deal, the peek, the natural, insurance and the play, all at
 * once. Every order of the cards is equally likely, so the average over every order is the
 * round's value exactly.
 */
public class RoundValueTest {

    private static final double TOLERANCE = 1e-12;

    private static final boolean[] DAS = das();

    private static boolean[] das() {
        boolean[] d = new boolean[11];
        for (int r = 2; r <= 10; r++) {
            d[r] = true;
        }
        return d;
    }

    /** Two hands at most, so a small shoe cannot run out. */
    private static final RoundRules RULES = new RoundRules(true, 1.5, 2, 2, false, DAS, false, false, false);

    @Test
    public void theStoredValuesGiveTheRoundsValue() throws Exception {
        // Nine cards, rich in tens so few are drawn, with an ace for naturals and insurance.
        int[] shoe = new int[11];
        shoe[1] = 1;
        shoe[4] = 1;
        shoe[6] = 1;
        shoe[9] = 1;
        shoe[10] = 5;
        RoundPolicy chart = BasicStrategy.chart();
        double[] values;
        try (StateValuer valuer = new StateValuer(RULES, chart, 2)) {
            values = valuer.value(List.of(shoe)).get(0);
        }
        // The chart's first move for every deal, and insurance always.
        RoundValue.Play play = i -> {
            if (Deals.playerNatural(i)) {
                return -1;
            }
            int p1 = Deals.P1[i];
            int p2 = Deals.P2[i];
            List<Integer> cards = List.of(p1, p2);
            int pair = p1 == p2 ? p1 : 0;
            RoundPolicy.Decision d = new RoundPolicy.Decision(ConcreteRound.total(cards), ConcreteRound.soft(cards),
                    2, pair, Deals.UP[i], false, true, true, true, pair != 0, false);
            PlayerMove m = chart.choose(d);
            return StateValuer.slot(0, m);
        };
        for (boolean insure : new boolean[]{false, true}) {
            double formula = RoundValue.of(shoe, values, RULES.blackjackPayout, play, i -> insure);
            double[] sums = new double[2];
            int n = 0;
            for (int r = 1; r <= 10; r++) {
                n += shoe[r];
            }
            everyOrder(shoe.clone(), new int[n], 0, 1.0, chart, insure, sums);
            assertEquals(sums[0] / sums[1], formula, TOLERANCE, "insure " + insure);
        }
    }

    private static void everyOrder(int[] left, int[] cards, int at, double weight, RoundPolicy chart,
                                   boolean insure, double[] sums) {
        if (at == cards.length) {
            ConcreteRound.Result r = ConcreteRound.play(cards, 0, RULES, chart);
            double payoff = r.payoff;
            if (insure && cards[1] == 1) {
                payoff += r.dealerNatural ? 1 : -0.5;
            }
            sums[0] += weight * payoff;
            sums[1] += weight;
            return;
        }
        for (int r = 1; r <= 10; r++) {
            if (left[r] > 0) {
                double w = weight * left[r];
                left[r]--;
                cards[at] = r;
                everyOrder(left, cards, at + 1, w, chart, insure, sums);
                left[r]++;
            }
        }
    }
}
