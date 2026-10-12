package sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Check P2's re-deals and check D4, on the fixture bundle's strategies and a states.bin made from
 * the simulator's own walk of 40 main shoes.
 */
class P2Test {

    @TempDir
    Path tmp;

    @Test
    void aLazyDeckDealsTheCardsLeftInAFreshUniformOrder() {
        int[] dealt = new int[Shoe.KINDS];
        dealt[0] = 16;
        dealt[18] = 10;
        LazyDeck deck = new LazyDeck();
        deck.state(dealt);
        assertEquals(416 - 26, deck.left());
        int[] firstKind = new int[Shoe.KINDS];
        int draws = 39_000;
        int[] again = new int[12];
        for (int k = 0; k < draws; k++) {
            deck.redeal(Xoshiro.of(4, 0, 0, k));
            firstKind[deck.kind(0)]++;
            if (k == 5) {
                for (int i = 0; i < 12; i++) {
                    again[i] = deck.kind(i);
                }
            }
        }
        assertEquals(0, firstKind[0], "every black ace was dealt");
        // Each kind's share of first cards is its share of the cards left: a chi-square on 24 cells.
        double chi = 0;
        for (int k = 1; k < Shoe.KINDS; k++) {
            double expected = draws * (Shoe.PER_KIND - dealt[k]) / 390.0;
            chi += (firstKind[k] - expected) * (firstKind[k] - expected) / expected;
        }
        assertTrue(chi < 60, "chi-square " + chi + " on 24 degrees of freedom");
        // The same generator gives the same order after other re-deals: the undo restores kind order.
        deck.redeal(Xoshiro.of(4, 0, 0, 5));
        int[] now = new int[12];
        for (int i = 0; i < 12; i++) {
            now[i] = deck.kind(i);
        }
        assertArrayEquals(again, now);
        LazyDeck fresh = new LazyDeck();
        fresh.state(dealt);
        fresh.redeal(Xoshiro.of(4, 0, 0, 5));
        for (int i = 0; i < 12; i++) {
            assertEquals(again[i], fresh.kind(i));
        }
        assertThrows(IllegalStateException.class, () -> fresh.kind(390));
    }

    /** The fixture's states, with each Hi-Lo section entry holding what the simulator itself computes. */
    static States consistent(Bundle b, List<Row> rows, StatesFixture f) throws Exception {
        f.plans = new ArrayList<>(List.of("same-8", "X-1,8"));
        P2 first = new P2(P2.SEED, 2, f.read(), rows, b.chart, Plants.NONE);
        States s0 = f.read();
        f.filler = (row, pen, i) -> {
            if (!row.equals(BundleFixture.HILO) || pen != 80) {
                return new double[]{0, -0.006, 0, 1, 1};
            }
            int[] dealt = first.dealt(i);
            Strategy s = rows.get(3).folds[s0.fold[i]];
            int key = s.keys.betKey(dealt, s0.depth[i]);
            int[] low = first.lowestKeys(i);
            double[] v = {key, -0.004, 0.001, 0, 0};
            for (int p = 0; p < 2; p++) {
                double[] bet = P2.bet(s, p, key, low[first.rows().indexOf(rows.get(3))]);
                v[3 + p] = bet[0] * bet[1];
            }
            return v;
        };
        return f.read();
    }

    /**
     * P2's sums against a plain computation written here: every re-deal on a deck built afresh,
     * each fold strategy playing its own round, the coin read from its stream, the bets from
     * states.bin's own entries (which D4 holds equal to the simulator's), and the mean, variance
     * and weights summed state by state.
     */
    @Test
    void theRedealSumsAreWhatAPlainComputationGives() throws Exception {
        Bundle b = new Bundle(BundleFixture.write(tmp.resolve("b")), Plants.NONE);
        List<Row> rows = b.rows(true);
        States s = consistent(b, rows, new StatesFixture(40));
        P2 p2 = new P2(P2.SEED, 40, s, rows, b.chart, Plants.NONE);
        double[] sums = p2.run(0, 25);
        plain(b, s, p2, sums, rows.get(3), 2 * 4, 40, 25);
        List<Summary.Comparison> cmp = p2.comparisons(sums);
        assertEquals("P2", cmp.get(0).check());
        assertEquals(sums[8] / sums[9], cmp.get(2).simulated(), 1e-15);
    }

    /** The same at full play, whose rounds are not shared: each re-deal's deal key must be its own. */
    @Test
    void andAtFullPlay() throws Exception {
        Bundle b = new Bundle(BundleFixture.write(tmp.resolve("b")), Plants.NONE);
        States s = consistent(b, b.rows(true), new StatesFixture(40));
        Row full = Row.of(BundleFixture.hiLo("full", 0).read(), BundleFixture.hiLo("full", 1).read(), 0);
        P2 p2 = new P2(P2.SEED, 40, s, List.of(full), b.chart, Plants.NONE);
        plain(b, s, p2, p2.run(0, 25), full, 0, 40, 25);
    }

    static void plain(Bundle b, States s, P2 p2, double[] sums, Row hiLo, int base, int k, int count) {
        States.Section sec = s.sections.get(3);
        assertEquals(BundleFixture.HILO + " 80", sec.row + " " + sec.pen);
        Round round = new Round(Rules.montreal());
        double[][] plain = new double[3][4];
        for (int i = 0; i < count; i++) {
            Strategy st = hiLo.folds[s.fold[i]];
            Strategy.Play play = st.new Play(b.chart, false);
            int[] dealt = p2.dealt(i);
            double[] s1 = new double[3];
            double[] s2 = new double[3];
            Xoshiro coins = Xoshiro.of(P2.SEED, i, 1, 0);
            for (int r = 0; r < k; r++) {
                LazyDeck deck = new LazyDeck();
                deck.state(dealt);
                deck.redeal(Xoshiro.of(P2.SEED, i, 0, r));
                double coin = coins.nextDouble();
                play.start(dealt, s.depth[i]);
                round.play(deck, 0, play, -1);
                double x = round.main + round.insurance;
                int key = st.keys.betKey(dealt, s.depth[i]);
                for (int j = 0; j < 3; j++) {
                    double units = 1;
                    double chance = 1;
                    if (j > 0) {
                        int line = st.betLine(st.plans.get(j - 1), key);
                        boolean exited = sec.bet[j - 1][i] == 0 && st.plans.get(j - 1).exit != null;
                        units = exited ? 0 : st.plans.get(j - 1).units[line];
                        chance = exited ? 0 : st.plans.get(j - 1).play[line];
                    }
                    double v = coin < chance ? units * x : 0;
                    s1[j] += v;
                    s2[j] += v * v;
                }
            }
            double w = 1 / s.q[i];
            for (int j = 0; j < 3; j++) {
                double mean = s1[j] / k;
                plain[j][0] += w * mean;
                plain[j][1] += w;
                plain[j][2] += w * w * ((s2[j] - k * mean * mean) / (k - 1)) / k;
                plain[j][3] += w * (j == 0 ? 1 : sec.bet[j - 1][i]) * (sec.rvPlay[i] + sec.rvInsurance[i]);
            }
        }
        for (int j = 0; j < 3; j++) {
            for (int c = 0; c < 4; c++) {
                assertEquals(plain[j][c], sums[base + 4 * j + c], Math.abs(plain[j][c]) * 1e-12, "figure " + j + " sum " + c);
            }
        }
    }

    @Test
    void d4HoldsEveryKeyAndBetToStatesBin() throws Exception {
        Bundle b = new Bundle(BundleFixture.write(tmp.resolve("b")), Plants.NONE);
        List<Row> rows = b.rows(true);
        StatesFixture f = new StatesFixture(40);
        States s = consistent(b, rows, f);
        List<String> first = new ArrayList<>();
        long[] d4 = new P2(P2.SEED, 2, s, rows, b.chart, Plants.NONE).d4(first);
        assertEquals(0, d4[1], String.valueOf(first));
        assertTrue(d4[0] > 100);
        // One bet changed in the file is one mismatch.
        StatesFixture.Filler good = f.filler;
        f.filler = (row, pen, i) -> {
            double[] v = good.fill(row, pen, i);
            if (row.equals(BundleFixture.HILO) && pen == 80 && i == 7) {
                v[4] = Math.nextUp(v[4]);
            }
            return v;
        };
        assertEquals(1, new P2(P2.SEED, 2, f.read(), rows, b.chart, Plants.NONE).d4(new ArrayList<>())[1]);
        // The planted bet-key-plus-one is seen by D4.
        Bundle planted = new Bundle(tmp.resolve("b"), Plants.of(Plants.BET_KEY_PLUS_ONE));
        f.filler = good;
        assertTrue(new P2(P2.SEED, 2, f.read(), planted.rows(true), planted.chart, Plants.NONE).d4(new ArrayList<>())[1] > 0);
    }
}
