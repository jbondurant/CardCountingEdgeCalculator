import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BasicStrategy held to ExactRound off the top of a full eight-deck shoe under Montreal's
 * rules: for every two-card hand and up-card, the chart's first move must be the one with
 * the highest exact value, when the chart also plays every later decision.
 *
 * A total-dependent chart could in principle be wrong for some pairs of cards making a
 * total, but on eight decks it is not: all 540 hands agree, so any that differs is a
 * mistake in the chart.
 */
public class BasicStrategyTest {

    private static final RoundRules MONTREAL = RoundRules.from(HouseRules.getMtlCasino25MinBlackjackParams(75));

    @Test
    public void theChartsFirstMoveIsTheBestOffTheTop() {
        RoundPolicy chart = BasicStrategy.chart();
        ExactRound exact = new ExactRound(MONTREAL);
        List<String> wrong = new ArrayList<>();
        int hands = 0;
        for (int up = 1; up <= 10; up++) {
            for (int p1 = 1; p1 <= 10; p1++) {
                for (int p2 = p1; p2 <= 10; p2++) {
                    if (p1 == 1 && p2 == 10) {
                        continue;
                    }
                    int[] shoe = ExactRoundTimingReport.eightDecksLess(p1, p2, up);
                    List<Integer> cards = List.of(p1, p2);
                    int total = ConcreteRound.total(cards);
                    boolean soft = ConcreteRound.soft(cards);
                    int pair = p1 == p2 ? p1 : 0;
                    boolean canSplit = pair != 0 && MONTREAL.maxHandsFor(pair) >= 2;
                    RoundPolicy.Decision first = new RoundPolicy.Decision(total, soft, 2, pair, up, false,
                            true, true, true, canSplit, MONTREAL.surrender);
                    PlayerMove chosen = chart.choose(first);
                    PlayerMove best = null;
                    double bestValue = Double.NEGATIVE_INFINITY;
                    double chosenValue = Double.NaN;
                    StringBuilder all = new StringBuilder();
                    for (PlayerMove m : PlayerMove.values()) {
                        if (!first.isLegal(m)) {
                            continue;
                        }
                        double v = exact.valueOfFirstMove(shoe, p1, p2, up, m, chart);
                        all.append(String.format(" %s %+.5f", m, v));
                        if (v > bestValue) {
                            bestValue = v;
                            best = m;
                        }
                        if (m == chosen) {
                            chosenValue = v;
                        }
                    }
                    hands++;
                    double loss = bestValue - chosenValue;
                    if (loss > 1e-12) {
                        wrong.add(String.format("%d,%d vs %d: chart %s, best %s, gives up %.5f:%s",
                                p1, p2, up, chosen, best, loss, all));
                    }
                }
            }
        }
        assertEquals(540, hands);
        assertTrue(wrong.isEmpty(), wrong.size() + " hands where the chart is not the best first move:\n"
                + String.join("\n", wrong));
    }
}
