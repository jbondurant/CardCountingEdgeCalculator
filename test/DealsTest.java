import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The chance of each deal, checked against dealing player, up-card, player, hole card in
 * every order the cards allow.
 */
public class DealsTest {

    private static final double EXACT = 1e-12;

    @Test
    public void everyDealHasItsOwnIndex() {
        for (int i = 0; i < Deals.COUNT; i++) {
            assertEquals(i, Deals.index(Deals.P1[i], Deals.P2[i], Deals.UP[i]));
            assertEquals(i, Deals.index(Deals.P2[i], Deals.P1[i], Deals.UP[i]));
            assertTrue(Deals.P1[i] <= Deals.P2[i]);
        }
        assertEquals(10, java.util.stream.IntStream.range(0, Deals.COUNT).filter(Deals::playerNatural).count());
    }

    @Test
    public void theChancesMatchDealingEveryOrder() {
        Random rnd = new Random(5);
        for (int t = 0; t < 40; t++) {
            int[] shoe = new int[11];
            int n = t == 0 ? 0 : 3 + rnd.nextInt(30);
            if (t == 0) {
                shoe = ExactRoundTimingReport.eightDecksLess();
            } else {
                for (int k = 0; k < n; k++) {
                    shoe[1 + rnd.nextInt(10)]++;
                }
            }
            double[] deal = new double[Deals.COUNT];
            double[] natural = new double[Deals.COUNT];
            int cards = 0;
            for (int r = 1; r <= 10; r++) {
                cards += shoe[r];
            }
            // Ordered: first player card a, up-card u, second player card b, hole card h.
            for (int a = 1; a <= 10; a++) {
                for (int u = 1; u <= 10; u++) {
                    for (int b = 1; b <= 10; b++) {
                        int[] left = shoe.clone();
                        double p = 1;
                        int[] seq = {a, u, b};
                        for (int k = 0; k < 3; k++) {
                            p *= (double) Math.max(left[seq[k]], 0) / (cards - k);
                            left[seq[k]]--;
                        }
                        if (p <= 0) {
                            continue;
                        }
                        int i = Deals.index(a, b, u);
                        deal[i] += p;
                        for (int h = 1; h <= 10; h++) {
                            if (left[h] > 0 && ((u == 1 && h == 10) || (u == 10 && h == 1))) {
                                natural[i] += p * left[h] / (cards - 3);
                            }
                        }
                    }
                }
            }
            double sum = 0;
            for (int i = 0; i < Deals.COUNT; i++) {
                double p = Deals.probability(shoe, i);
                sum += p;
                assertEquals(deal[i], p, EXACT, "deal " + i + " on shoe " + t);
                if (p > 0) {
                    assertEquals(natural[i] / deal[i], Deals.dealerNatural(shoe, i), EXACT, "natural " + i);
                }
            }
            if (cards >= 3) {
                assertEquals(1, sum, 1e-12, "shoe " + t);
            }
        }
    }

    @Test
    public void aFullShoeGivesTheDealerANaturalAsOften() {
        int[] shoe = ExactRoundTimingReport.eightDecksLess();
        double natural = 0;
        for (int i = 0; i < Deals.COUNT; i++) {
            natural += Deals.probability(shoe, i) * Deals.dealerNatural(shoe, i);
        }
        // Up ace then hole ten, or up ten then hole ace.
        assertEquals(2.0 * 32 * 128 / (416.0 * 415), natural, EXACT);
    }
}
