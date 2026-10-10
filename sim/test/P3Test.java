package sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Check P3's machinery on a fixture bundle: the bundle's checks, the chunks (D6: the same sums to
 * the bit on any number of threads, after a kill and a resume, and in any order of computing),
 * and the ratio estimate against a direct computation from the shoes. The number of threads is
 * the system property sim.test.threads, 6 as SIM_CHECK.md D6 asks unless set lower.
 */
class P3Test {

    static final int THREADS = Integer.getInteger("sim.test.threads", 6);

    @TempDir
    Path tmp;

    @Test
    void theBundleIsCheckedBeforeItIsUsed() throws IOException {
        Path dir = BundleFixture.write(tmp.resolve("b"));
        Bundle b = new Bundle(dir, Plants.NONE);
        assertEquals(64, b.sha256.length());
        assertEquals(2, b.strategies.size());
        assertEquals(4, b.rows(true).size());
        Path f = dir.resolve("strategy-hi-lo-hi-lo-lite-p80-f1.txt");
        String text = Files.readString(f);
        Files.writeString(f, text.replace("same-8\t4\t8\t1", "same-8\t4\t7\t1"));
        assertThrows(IOException.class, () -> new Bundle(dir, Plants.NONE));
        Files.writeString(f, text);
        Path idx = dir.resolve("index.tsv");
        String index = Files.readString(idx);
        Files.writeString(idx, index.replace("states.bin\t", "states.bin\t1"));
        assertThrows(IOException.class, () -> new Bundle(dir, Plants.NONE));
    }

    @Test
    void chunksGiveTheSameSumsToTheBit() throws Exception {
        Bundle b = new Bundle(BundleFixture.write(tmp.resolve("b")), Plants.NONE);
        List<Row> rows = b.rows(true);
        double[] one = P3.chunked(b, rows, tmp.resolve("one"), "test", 5, 3, 150, 1, Plants.NONE, -1);
        double[] many = P3.chunked(b, rows, tmp.resolve("many"), "test", 5, 3, 150, THREADS, Plants.NONE, -1);
        assertArrayEquals(one, many);
        // Killed after one chunk, then resumed.
        assertNull(P3.chunked(b, rows, tmp.resolve("killed"), "test", 5, 3, 150, 1, Plants.NONE, 1));
        assertArrayEquals(one, P3.chunked(b, rows, tmp.resolve("killed"), "test", 5, 3, 150, 1, Plants.NONE, -1));
        // Computed last chunk first.
        P3 p3 = new P3(5, rows, b.chart, Plants.NONE);
        Chunks c = new Chunks(tmp.resolve("reverse"), "p3", List.of("x"));
        for (int i = 2; i >= 0; i--) {
            c.write(i, p3.run(i * 150L, 150));
        }
        assertArrayEquals(one, c.run(3, p3.size(), 1, i -> {
            throw new AssertionError("every chunk is on disk");
        }, -1));
        // A chunk written under another seed is refused, not mixed in.
        assertThrows(IOException.class, () -> P3.chunked(b, rows, tmp.resolve("one"), "test", 6, 3, 150, 1, Plants.NONE, -1));
    }

    @Test
    void theChartsEstimateIsTheMeanOfItsRoundsWithTheRatioSe() throws Exception {
        Bundle b = new Bundle(BundleFixture.write(tmp.resolve("b")), Plants.NONE);
        List<Row> rows = b.rows(true);
        int shoes = 400;
        double[] sums = new P3(9, rows, b.chart, Plants.NONE).run(0, shoes);
        Round round = new Round(Rules.montreal());
        for (int p = 0; p < 3; p++) {
            double[] y = new double[shoes];
            double[] n = new double[shoes];
            for (int h = 0; h < shoes; h++) {
                int[] cards = Shoe.shuffled(Xoshiro.of(9, h, 0, 0));
                int depth = 0;
                while (depth < 332) {
                    round.play(new Walk.ArrayDeck(cards), depth, b.chart, -1);
                    if (depth < Shoe.CUTS[p]) {
                        y[h] += round.main;
                        n[h]++;
                    }
                    depth = round.next;
                }
            }
            double sy = 0;
            double sn = 0;
            for (int h = 0; h < shoes; h++) {
                sy += y[h];
                sn += n[h];
            }
            double mu = sy / sn;
            double ss = 0;
            for (int h = 0; h < shoes; h++) {
                ss += (y[h] - mu * n[h]) * (y[h] - mu * n[h]);
            }
            double se = Math.sqrt(shoes / (shoes - 1.0) * ss) / sn;
            double[] e = rows.get(p).estimate(sums, 0, 1, 0);
            assertEquals(mu, e[0], "chart at cut " + Shoe.CUTS[p]);
            assertEquals(se, e[1], se * 1e-9);
            assertEquals(sn, e[2]);
        }
    }

    /**
     * Every fold and figure of the Hi-Lo row against a plain walk written here from B2.4 and
     * B2.5: each fold strategy plays its own round (no shared playouts), and the bet is looked
     * up afresh: the nearest listed key, the lower on a tie; for the exit plan, nothing once any
     * round start of the shoe so far had a key under the exit; and the straddling key played when
     * the round's coin is under PLAY.
     */
    @Test
    void everyFigureIsWhatAPlainWalkGives() throws Exception {
        Bundle b = new Bundle(BundleFixture.write(tmp.resolve("b")), Plants.NONE);
        List<Row> rows = b.rows(true);
        Row row = rows.get(3);
        int shoes = 300;
        double[] sums = new P3(31, rows, b.chart, Plants.NONE).run(0, shoes);
        Round round = new Round(Rules.montreal());
        for (int f = 0; f < 2; f++) {
            Strategy s = row.folds[f];
            Strategy.Play play = s.new Play(b.chart, false);
            double[] y = new double[3];
            double unseenX = 0;
            for (int h = 0; h < shoes; h++) {
                int[] cards = Shoe.shuffled(Xoshiro.of(31, h, 0, 0));
                Xoshiro coins = Xoshiro.of(31, h, 1, 0);
                int[] dealt = new int[Shoe.KINDS];
                int depth = 0;
                boolean left = false;
                while (depth < 332) {
                    double coin = coins.nextDouble();
                    int key = s.keys.betKey(dealt, depth);
                    play.start(dealt, depth);
                    round.play(new Walk.ArrayDeck(cards), depth, play, -1);
                    double x = round.main + round.insurance;
                    y[0] += x;
                    y[1] += x * bet(s.plans.get(0), key, coin);
                    left |= key < -1;
                    y[2] += left ? 0 : x * bet(s.plans.get(1), key, coin);
                    unseenX += key < -2 || key > 2 ? 1 : 0;
                    round.play(new Walk.ArrayDeck(cards), depth, b.chart, -1);
                    for (int i = depth; i < round.next; i++) {
                        dealt[cards[i]]++;
                    }
                    depth = round.next;
                }
            }
            for (int j = 0; j < 3; j++) {
                assertEquals(y[j], sums[row.at(f, j)], 1e-9, "fold " + f + " figure " + row.figures.get(j));
            }
            assertEquals(unseenX, row.unseen(sums, f, 2));
        }
    }

    /** B2.5 read afresh: the nearest listed key (the lower on a tie), played when the coin is under PLAY. */
    static double bet(Strategy.Plan plan, int key, double coin) {
        int best = 0;
        for (int i = 1; i < plan.keys.length; i++) {
            if (Math.abs(plan.keys[i] - key) < Math.abs(plan.keys[best] - key)) {
                best = i;
            }
        }
        return coin < plan.play[best] ? plan.units[best] : 0;
    }

    @Test
    void depthBandsAddUpToTheChartsWalk() throws Exception {
        Bundle b = new Bundle(BundleFixture.write(tmp.resolve("b")), Plants.NONE);
        List<Row> rows = b.rows(true);
        P3 p3 = new P3(9, rows, b.chart, Plants.NONE);
        double[] sums = p3.run(0, 300);
        double y = 0;
        double n = 0;
        for (int band = 0; band < P3.BANDS; band++) {
            y += sums[p3.bandsAt() + 2 * band];
            n += sums[p3.bandsAt() + 2 * band + 1];
        }
        Row chart80 = rows.get(2);
        assertEquals(sums[chart80.offset + 1], n);
        assertEquals(sums[chart80.at(0, 0)], y, 1e-9);
    }

    @Test
    void theMovingCountChangesFullPlayOnly() throws Exception {
        Bundle b = new Bundle(BundleFixture.write(tmp.resolve("b")), Plants.NONE);
        Row full = Row.of(BundleFixture.hiLo("full", 0).read(), BundleFixture.hiLo("full", 1).read(), 0);
        double[] frozen = new P3(9, List.of(full), b.chart, Plants.NONE).run(0, 400);
        double[] moving = new P3(9, List.of(full), b.chart, Plants.NONE).moving(true).run(0, 400);
        assertNotEquals(java.util.Arrays.toString(frozen), java.util.Arrays.toString(moving));
        List<Row> first = b.rows(false);
        assertArrayEquals(new P3(9, first, b.chart, Plants.NONE).run(0, 100),
                new P3(9, first, b.chart, Plants.NONE).moving(true).run(0, 100));
    }

    @Test
    void theStandInChartsHouseEdgeIsNearThePublishedOne() throws Exception {
        // PLAN_COMPUTE.md 1.1: the report's chart flat EV at 80% is -0.660%. 20,000 fresh shoes
        // (about 1.2 million rounds) put the stand-in chart's within a few SEs of it.
        Bundle b = new Bundle(BundleFixture.write(tmp.resolve("b")), Plants.NONE);
        List<Row> rows = b.rows(true);
        double[] e = rows.get(2).estimate(new P3(P3.SEED, rows.subList(0, 3), b.chart, Plants.NONE).run(0, 20_000), 0, 1, 0);
        assertTrue(Math.abs(e[0] + 0.0066) < 4 * e[1], "flat EV " + e[0] + " SE " + e[1]);
        assertTrue(e[1] < 0.0015, "SE " + e[1]);
    }

    @Test
    void theOtherFoldPlantSwapsThePoolingWeights() throws Exception {
        Bundle b = new Bundle(BundleFixture.write(tmp.resolve("b")), Plants.NONE);
        List<Row> rows = b.rows(true);
        double[] sums = new P3(P3.SEED, rows, b.chart, Plants.NONE).run(0, 300);
        List<Summary.Comparison> clean = Summary.p3(rows, sums, b.exact, Plants.NONE);
        List<Summary.Comparison> swapped = Summary.p3(rows, sums, b.exact, Plants.of(Plants.OTHER_FOLD));
        assertEquals(clean.get(0).simulated(), swapped.get(0).simulated(), "the chart has one instance");
        int hiLoFlat = 3;
        assertEquals("flat", clean.get(hiLoFlat).figure());
        assertNotEquals(clean.get(hiLoFlat).simulated(), swapped.get(hiLoFlat).simulated());
        Summary.write(tmp.resolve("summary.json"), new java.util.LinkedHashMap<>(java.util.Map.of("check", "P3")), clean);
        assertTrue(Files.readString(tmp.resolve("summary.json")).contains("\"figure\": \"ev:X-1,8\""));
    }
}
