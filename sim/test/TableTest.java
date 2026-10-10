package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Playouts shared by first move give, to the bit, what each strategy gets by playing its own
 * round, on every round start of 1,500 fresh shoes, for strategies of every scope whose moves and
 * insurance change with the count; and the chart's shared round ends where the chart's own does.
 */
class TableTest {

    static StrategyFixture varied(String scope) {
        StrategyFixture f = new StrategyFixture();
        f.scope = scope;
        f.groupHi = new int[]{-2, 0, 2, 99};
        f.laterHi = new int[]{-1, 1, 99};
        f.first = (g, p1, p2, up) -> {
            int v = (g + p1 + 2 * p2 + 3 * up) % 4;
            if (p1 == p2 && v == 0) {
                return "P\tH\tS";
            }
            return v == 1 ? "H\tS" : v == 2 ? "D\tH\tS" : "S\tH";
        };
        f.later = (l, total, soft, up) -> (l + total + up) % 3 == 0 ? "H" : "S";
        for (int g = 0; g < 4; g++) {
            for (int p1 = 1; p1 <= 10; p1++) {
                for (int p2 = p1; p2 <= 10; p2++) {
                    if ((g * 7 + p1 + p2) % 3 == 0) {
                        f.insured.add(g + " " + p1 + " " + p2);
                    }
                }
            }
        }
        return f;
    }

    static void sharedIsOwn(Plants plants) throws IOException {
        Rules rules = Rules.montreal(plants);
        Chart chart = Chart.textbook();
        Strategy.Play[] plays = {
            varied("first").read(plants).new Play(chart, false),
            varied("betting").read(plants).new Play(chart, false),
            varied("full").read(plants).new Play(chart, false)};
        Table table = new Table(rules, plants, chart);
        Round own = new Round(rules, plants);
        long rounds = 0;
        long insured = 0;
        for (int h = 0; h < 1500; h++) {
            int[] cards = Shoe.shuffled(Xoshiro.of(77, h, Xoshiro.STREAM_CARDS, 0));
            Walk.ArrayDeck deck = new Walk.ArrayDeck(cards);
            int[] dealt = new int[Shoe.KINDS];
            int depth = 0;
            while (depth < 332) {
                table.begin(deck, depth);
                for (Strategy.Play p : plays) {
                    p.start(dealt, depth);
                    double shared = table.result(p);
                    p.start(dealt, depth);
                    own.play(deck, depth, p, -1);
                    assertEquals(Double.doubleToRawLongBits(own.main + own.insurance), Double.doubleToRawLongBits(shared),
                            "shoe " + h + " depth " + depth + " scope " + p.strategy().scope);
                    insured += own.insured ? 1 : 0;
                }
                own.play(deck, depth, chart, -1);
                assertEquals(own.main, table.chartRound());
                assertEquals(own.next, table.chartNext());
                for (int i = depth; i < own.next; i++) {
                    dealt[cards[i]]++;
                }
                depth = own.next;
                rounds++;
            }
        }
        assertTrue(rounds > 80_000, rounds + " rounds");
        assertTrue(insured > 1000, insured + " insured");
    }

    @Test
    void sharedPlayoutsAreEachStrategysOwnRound() throws IOException {
        sharedIsOwn(Plants.NONE);
    }

    @Test
    void andUnderThePlantsThatChangeARule() throws IOException {
        sharedIsOwn(Plants.of(Plants.HOLE_CARD, Plants.INSURE_WHOLE_BET, Plants.S17));
    }
}
