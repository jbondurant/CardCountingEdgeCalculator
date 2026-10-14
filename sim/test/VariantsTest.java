package sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Mode B and the moving count: mode B against a plain walk of each instance alone on the shoe, its
 * mode A beside it equal to P3's on the same shoes, the moving count equal to the frozen one at
 * first decisions and not at full play, and the paired differences the differences of the estimates.
 */
class VariantsTest {

    static final long SEED = 43;

    static Registry registry(String row) throws IOException {
        return Registry.parse(List.of("```sim-registry", Registry.HEADER,
                "F26\t2\t" + row + "\t80\tflat\tP3,P2\tc06c061\t0\t0",
                "F27\t2\t" + row + "\t80\tev:same-8\tP3,P2\tc06c061\t1\t1", "```"));
    }

    static Row row(String scope) throws IOException {
        return Row.of(BundleFixture.hiLo(scope, 0).read(), BundleFixture.hiLo(scope, 1).read(), 0);
    }

    /** Shoe h walked by one instance alone: {Y flat, Y same-8, N}. */
    static double[] alone(Strategy s, Chart chart, long h) {
        Round round = new Round(Rules.montreal());
        Strategy.Play play = s.new Play(chart, false);
        int[] cards = Shoe.shuffled(Xoshiro.of(SEED, h, 0, 0));
        Xoshiro coins = Xoshiro.of(SEED, h, 1, 0);
        int[] dealt = new int[Shoe.KINDS];
        double[] out = new double[3];
        int depth = 0;
        while (depth < 332) {
            double coin = coins.nextDouble();
            int key = s.keys.betKey(dealt, depth);
            play.start(dealt, depth);
            round.play(new Walk.ArrayDeck(cards), depth, play, -1);
            double x = round.main + round.insurance;
            out[0] += x;
            out[1] += P3Test.bet(s.plans.get(0), key, coin) * x;
            out[2]++;
            for (int i = depth; i < round.next; i++) {
                dealt[cards[i]]++;
            }
            depth = round.next;
        }
        return out;
    }

    @Test
    void modeBIsEachInstanceAloneAndModeAIsP3s() throws IOException {
        Row full = row("full");
        Chart chart = Chart.textbook();
        Variants v = new Variants(SEED, List.of(full), registry(full.name), chart, Plants.NONE);
        assertEquals(List.of("F26", "F27"), v.ids);
        int shoes = 60;
        double[] sums = v.run(0, shoes);
        double[][] want = new double[2][3];
        for (long h = 0; h < shoes; h++) {
            for (int f = 0; f < 2; f++) {
                double[] a = alone(full.folds[f], chart, h);
                for (int i = 0; i < 3; i++) {
                    want[f][i] += a[i];
                }
            }
        }
        for (int f = 0; f < 2; f++) {
            assertEquals(want[f][0], Moments.sum(sums, 0, Variants.unit(4 + f)), 1e-9, "mode B flat, fold " + f);
            assertEquals(want[f][1], Moments.sum(sums, Variants.BLOCK, Variants.unit(4 + f)), 1e-9, "mode B 1-8, fold " + f);
            assertEquals(want[f][2], Moments.sum(sums, 0, Variants.unit(7 + f)), "mode B rounds, fold " + f);
        }
        double[] p3 = new P3(SEED, List.of(full), chart, Plants.NONE).run(0, shoes);
        for (int t = 0; t < 2; t++) {
            for (int f = 0; f < 2; f++) {
                assertEquals(p3[full.at(f, t)], Moments.sum(sums, t * Variants.BLOCK, Variants.unit(f)), 1e-9, "mode A");
            }
            assertEquals(p3[full.offset + 1], Moments.sum(sums, t * Variants.BLOCK, Variants.unit(6)), "mode A's rounds");
        }
        assertNotEquals(Moments.sum(sums, 0, Variants.unit(0)), Moments.sum(sums, 0, Variants.unit(2)),
                "at full play the moving count plays split hands differently");
        double[][] r = v.results(sums, 1, 0.5, 0.5);
        assertEquals(r[2][0] - r[0][0], r[4][0], 1e-15);
        assertEquals(r[1][0] - r[0][0], r[3][0], 1e-15);
        assertEquals((want[0][1] + want[1][1]) / (want[0][2] + want[1][2]), r[2][0], 1e-15, "mode B pooled per round");
    }

    @Test
    void atFirstDecisionsTheMovingCountChangesNothing() throws IOException {
        Row first = row("first");
        Variants v = new Variants(SEED, List.of(first), registry(first.name), Chart.textbook(), Plants.NONE);
        double[] sums = v.run(0, 40);
        for (int t = 0; t < 2; t++) {
            for (int f = 0; f < 2; f++) {
                assertEquals(Moments.sum(sums, t * Variants.BLOCK, Variants.unit(f)),
                        Moments.sum(sums, t * Variants.BLOCK, Variants.unit(2 + f)));
            }
        }
        List<Summary.Difference> d = new ArrayList<>();
        List<String> lines = v.lines(sums, exact(first), d);
        assertEquals(2 + 2 * 5, lines.size());
        assertEquals(4, d.size());
        assertEquals(0.0, d.get(0).difference());
        assertArrayEquals(new String[]{"difference", "F27", first.name, "80", "ev:same-8", "mode B less mode A"},
                java.util.Arrays.copyOf(lines.get(11).split("\t"), 6));
    }

    static Exact exact(Row row) throws IOException {
        List<String> t = new ArrayList<>(List.of("# exact.tsv: SHOE_SPEC.md B5, format 1", Exact.HEADER));
        for (int f = 0; f < 2; f++) {
            t.add(row.name + "\t80\t" + f + "\tflat\t0\t30000\t-10\t" + (1000 + f) + "\t1\t1\t1");
        }
        return Exact.parse(t, "fixture");
    }
}
