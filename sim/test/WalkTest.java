package sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Check D1's half that needs no bundle: the simulator's own shuffle, walk and valuation draw
 * against SHOE_SPEC.md. Three layers. The shuffle against the spec's reference code and against
 * its step-by-step port of SplittableRandom (A9), so the JDK's generator is checked too. The walk
 * against the spec's reference {@code walk} on a few shoes, round start by round start: shoe,
 * round, depth, tallies, q to the bit, valued. And the walk of all 60,000 main shoes against A8's
 * totals, which only come out if every round of the walk takes exactly the engine's cards.
 */
class WalkTest {

    /** A kind of each rank, for decks given by rank: the black card of that rank, the ten for 10. */
    static int kindOfRank(int rank) {
        return rank == 10 ? 18 : 2 * (rank - 1);
    }

    @Test
    void mixAndShuffleAreTheSpecs() {
        long[] seeds = {Shoe.SEED_MAIN, 20261008L, 0L, 1L, -7L, Long.MAX_VALUE};
        for (long seed : seeds) {
            for (int shoe = 0; shoe < 20; shoe++) {
                for (int stream = 0; stream < 2; stream++) {
                    assertEquals(ShoeSpec.mix(seed, shoe, stream), Shoe.mix(seed, shoe, stream));
                }
                int[] mine = Shoe.shuffled(seed, shoe);
                assertArrayEquals(ShoeSpec.shuffled(seed, shoe), mine, "seed " + seed + " shoe " + shoe);
                // The same shuffle through A9's port: java.util.SplittableRandom is what A9 says.
                int[] ported = ShoeSpec.layout();
                PortableSplittableRandom rnd = new PortableSplittableRandom(ShoeSpec.mix(seed, shoe, 0));
                for (int i = ported.length - 1; i > 0; i--) {
                    int j = rnd.nextInt(i + 1);
                    int t = ported[i];
                    ported[i] = ported[j];
                    ported[j] = t;
                }
                assertArrayEquals(ported, mine, "port, seed " + seed + " shoe " + shoe);
            }
        }
    }

    @Test
    void valuationDrawIsThePorts() {
        for (int shoe = 0; shoe < 200; shoe++) {
            long s = Shoe.mix(Shoe.SEED_MAIN, shoe, 1);
            SplittableRandom jdk = new SplittableRandom(s);
            PortableSplittableRandom port = new PortableSplittableRandom(s);
            for (int i = 0; i < 70; i++) {
                assertEquals(Double.doubleToRawLongBits(port.nextDouble()), Double.doubleToRawLongBits(jdk.nextDouble()));
            }
        }
    }

    @Test
    void layoutHoldsSixteenOfEachKind() {
        int[] layout = Shoe.layout();
        assertEquals(416, layout.length);
        int[] per = new int[Shoe.KINDS];
        for (int k : layout) {
            per[k]++;
        }
        for (int k = 0; k < Shoe.KINDS; k++) {
            assertEquals(16, per[k]);
            assertEquals(ShoeSpec.rankOf(k), Shoe.rankOf(k));
            assertEquals(ShoeSpec.red(k), Shoe.red(k));
        }
    }

    @Test
    void qIsTheSpecsToTheBit() {
        SplittableRandom rnd = new SplittableRandom(5);
        for (int t = 0; t < 20_000; t++) {
            int[] dealt = new int[Shoe.KINDS];
            int cards = rnd.nextInt(390);
            for (int c = 0; c < cards; c++) {
                int k = rnd.nextInt(Shoe.KINDS);
                if (dealt[k] < 16) {
                    dealt[k]++;
                }
            }
            int[] left = Shoe.ranksLeft(dealt);
            assertArrayEquals(ShoeSpec.ranksLeft(dealt), left);
            assertEquals(Double.doubleToRawLongBits(ShoeSpec.rate(left, 0.008, 0.02)),
                    Double.doubleToRawLongBits(Shoe.rate(left, Shoe.Q0, Shoe.U0)));
        }
    }

    record Start(int shoe, int round, int depth, int[] dealt, double q, boolean valued) {
    }

    /** The simulator's walk of shoe, as a list of round starts. */
    static List<Start> mine(int shoe, Round round, Chart chart) {
        List<Start> out = new ArrayList<>();
        Walk.walkShoe(Shoe.SEED_MAIN, shoe, 332, Shoe.Q0, Shoe.U0, round, chart,
                (s, r, depth, dealt, q, valued) -> out.add(new Start(s, r, depth, dealt.clone(), q, valued)), null);
        return out;
    }

    @Test
    void walkIsTheSpecsReferenceWalk() {
        Round round = new Round(Rules.montreal());
        Chart chart = Chart.textbook();
        for (int shoe = 0; shoe < 100; shoe++) {
            // The spec's walk, its rounds' ends given by the simulator's round on a deck of ranks.
            List<ShoeSpec.Start> spec = ShoeSpec.walk(Shoe.SEED_MAIN, shoe, 332, 0.008, 0.02, (ranks, from) -> {
                round.play(i -> kindOfRank(ranks[i]), from, chart, -1);
                return round.next;
            });
            List<Start> mine = mine(shoe, round, chart);
            assertEquals(spec.size(), mine.size(), "round starts in shoe " + shoe);
            for (int i = 0; i < spec.size(); i++) {
                ShoeSpec.Start a = spec.get(i);
                Start b = mine.get(i);
                String where = "shoe " + shoe + " round " + i;
                assertEquals(a.shoe(), b.shoe(), where);
                assertEquals(a.round(), b.round(), where);
                assertEquals(a.depth(), b.depth(), where);
                assertArrayEquals(a.dealt(), b.dealt(), where);
                assertEquals(Double.doubleToRawLongBits(a.q()), Double.doubleToRawLongBits(b.q()), where);
                assertEquals(a.valued(), b.valued(), where);
            }
        }
    }

    @Test
    void shoeZerosSecondRoundStartsAtDepthFive() {
        // SHOE_SPEC.md's table of planted changes reports "shoe 0 round 1: the spec's depth 5
        // tallies": in the spec's own walk, shoe 0's round 1 starts after 5 cards.
        List<Start> starts = mine(0, new Round(Rules.montreal()), Chart.textbook());
        assertEquals(5, starts.get(1).depth());
    }

    /**
     * D1's totals: every main shoe walked to cut 332 gives A8's round starts, valued states and
     * weights at all three cuts. About 3 s. It reads no file: the shoes come from the seed.
     */
    @Test
    void mainShoesGiveA8() {
        Walk.Totals totals = Walk.mainTotals(0, Shoe.SHOES_MAIN, new Round(Rules.montreal()), Chart.textbook());
        for (int p = 0; p < 3; p++) {
            assertEquals(Sim.A8_ROUNDS[p], totals.rounds[p], "rounds at cut " + Shoe.CUTS[p]);
            assertEquals(Sim.A8_VALUED[p], totals.valued[p], "valued at cut " + Shoe.CUTS[p]);
            assertEquals(Sim.A8_WEIGHT[p], Math.round(totals.weight[p]), "weight at cut " + Shoe.CUTS[p]);
        }
        // A4: "The last round of a shoe starts with at least 85 cards left".
        assertTrue(totals.fewestLeft >= 85, "fewest left " + totals.fewestLeft);
    }
}
