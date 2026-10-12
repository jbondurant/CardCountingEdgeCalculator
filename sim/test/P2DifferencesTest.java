package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P2's paired differences (SIM_CHECK.md section 9): a count less Hi-Lo on the same states and the
 * same re-deals, against a replay written apart from P2's loop, each instance playing its own
 * round on the re-dealt cards; and the engine's difference from states.bin's sections.
 */
class P2DifferencesTest {

    @Test
    void aCountLessHiLoIsTheDirectPairedFormula() throws Exception {
        StatesFixture f = new StatesFixture(8);
        f.rows.add("Other count");
        f.plans = new ArrayList<>(List.of("same-8", "X-1,8"));
        States states = f.read();
        Strategy[] hl = {BundleFixture.hiLo("first", 0).read(), BundleFixture.hiLo("first", 1).read()};
        Strategy[] ot = {P3ExtrasTest.other(0), P3ExtrasTest.other(1)};
        Row hiLo = Row.of(hl[0], hl[1], 0);
        Row oth = Row.of(ot[0], ot[1], hiLo.size());
        Chart chart = Chart.textbook();
        int k = 40;
        P2 p2 = new P2(P2.SEED, k, states, List.of(hiLo, oth), chart, Plants.NONE);
        double[] sums = p2.run(0, states.n);
        List<Summary.Difference> d = p2.differences(sums);
        assertEquals(3, d.size());
        Round round = new Round(Rules.montreal());
        LazyDeck deck = new LazyDeck();
        for (int j = 0; j < 3; j++) {
            double sw = 0, swd = 0, sv = 0, eng = 0;
            for (int i = 0; i < states.n; i++) {
                int[] dealt = p2.dealt(i);
                int depth = states.depth[i];
                Strategy a = ot[states.fold[i]];
                Strategy b = hl[states.fold[i]];
                int[] low = p2.lowestKeys(i);
                deck.state(dealt);
                Xoshiro coins = Xoshiro.of(P2.SEED, i, 1, 0);
                double s1 = 0, s2 = 0;
                for (int r = 0; r < k; r++) {
                    deck.redeal(Xoshiro.of(P2.SEED, i, 0, r));
                    double coin = coins.nextDouble();
                    double va = value(a, chart, round, deck, dealt, depth, j, low[1], coin);
                    double vb = value(b, chart, round, deck, dealt, depth, j, low[0], coin);
                    s1 += va - vb;
                    s2 += (va - vb) * (va - vb);
                }
                double w = 1 / states.q[i];
                double mean = s1 / k;
                sw += w;
                swd += w * mean;
                sv += w * w * (s2 - k * mean * mean) / (k - 1) / k;
                States.Section sa = states.sections.get(5);
                States.Section sb = states.sections.get(3);
                int e = P2.entry(sa, i);
                eng += w * ((j == 0 ? 1 : sa.bet[j - 1][e]) * (sa.rvPlay[e] + sa.rvInsurance[e])
                        - (j == 0 ? 1 : sb.bet[j - 1][e]) * (sb.rvPlay[e] + sb.rvInsurance[e]));
            }
            Summary.Difference got = d.get(j);
            assertEquals("Other count", got.row());
            assertEquals(BundleFixture.HILO, got.less());
            assertEquals(swd / sw, got.difference(), 1e-12, "difference, figure " + j);
            assertEquals(Math.sqrt(sv) / sw, got.se(), 1e-9 * got.se(), "SE, figure " + j);
            assertEquals(eng / sw, got.exact(), 1e-12, "the engine's difference, figure " + j);
            assertEquals(got.first() - got.second(), got.difference(), 1e-12);
        }
    }

    /** One instance's bet × result on a re-deal, its own round played out. */
    static double value(Strategy s, Chart chart, Round round, LazyDeck deck, int[] dealt, int depth, int figure,
                        int lowest, double coin) {
        Strategy.Play play = s.new Play(chart, false);
        play.start(dealt, depth);
        round.play(deck, 0, play, -1);
        double x = round.main + round.insurance;
        if (figure == 0) {
            return x;
        }
        Strategy.Plan plan = s.plans.get(figure - 1);
        if (plan.exit != null && lowest < plan.exit) {
            return 0;
        }
        return P3Test.bet(plan, s.keys.betKey(dealt, depth), coin) * x;
    }
}
