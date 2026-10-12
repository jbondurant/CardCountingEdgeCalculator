package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What P3 keeps on the way, against a plain walk of the same shoes written apart from P3's loop
 * (each fold's file playing its own round on the shoe's cards, bets looked up afresh): the swings'
 * per-shoe sums and histogram, the per-shoe records (Y, N, M), the edge by bet key, and a count's
 * paired difference from Hi-Lo.
 */
class P3ExtrasTest {

    @TempDir
    Path tmp;

    static final long SEED = 41;

    static Registry registry() throws IOException {
        return Registry.parse(List.of("```sim-registry", Registry.HEADER,
                "F03\t1\tChart, no count, flat\t80\tflat\tP3,P2\tstep 7\t1\t0",
                "F18\t1\tHi-Lo = Hi-Lo Lite\t80\tev:same-8\tP3,P2\tc06c061\t1\t1",
                "F45\t2\tHi-Lo = Hi-Lo Lite\t80\tev:X-1,8\tP3,P2\treplay\t1\t0", "```"));
    }

    /** One shoe of one fold's file, walked plainly: per figure Y, the rounds, the lowest running total of figure 1, and by key N and Y. */
    static final class Walked {
        double[] y = new double[3];
        long n;
        double low;
        TreeMap<Integer, double[]> byKey = new TreeMap<>();
        /** "bet,2X" to {count, splits}, for figure 1. */
        TreeMap<String, long[]> hist = new TreeMap<>();
        /** Figure 1's win a round, in order. */
        List<Double> wins = new ArrayList<>();
    }

    static Walked walk(Strategy s, Chart chart, long h) {
        Walked w = new Walked();
        Round round = new Round(Rules.montreal());
        Strategy.Play play = s.new Play(chart, false);
        int[] cards = Shoe.shuffled(Xoshiro.of(SEED, h, 0, 0));
        Xoshiro coins = Xoshiro.of(SEED, h, 1, 0);
        int[] dealt = new int[Shoe.KINDS];
        int depth = 0;
        boolean left = false;
        double cum = 0;
        while (depth < 332) {
            double coin = coins.nextDouble();
            int key = s.keys.betKey(dealt, depth);
            play.start(dealt, depth);
            round.play(new Walk.ArrayDeck(cards), depth, play, -1);
            double x = round.main + round.insurance;
            double b1 = P3Test.bet(s.plans.get(0), key, coin);
            left |= key < -1;
            double b2 = left ? 0 : P3Test.bet(s.plans.get(1), key, coin);
            w.y[0] += x;
            w.y[1] += b1 * x;
            w.y[2] += b2 * x;
            w.n++;
            cum += b1 * x;
            w.wins.add(b1 * x);
            w.low = Math.min(w.low, cum);
            double[] k = w.byKey.computeIfAbsent(key, z -> new double[2]);
            k[0]++;
            k[1] += x;
            long[] cell = w.hist.computeIfAbsent(b1 + "," + (int) (2 * x), z -> new long[2]);
            cell[0]++;
            cell[1] += round.handsPlayed > 1 ? 1 : 0;
            round.play(new Walk.ArrayDeck(cards), depth, chart, -1);
            for (int i = depth; i < round.next; i++) {
                dealt[cards[i]]++;
            }
            depth = round.next;
        }
        return w;
    }

    @Test
    void swingsRecordsAndTheEdgeByKeyAreThePlainWalks() throws Exception {
        Bundle b = new Bundle(BundleFixture.write(tmp.resolve("b")), Plants.NONE);
        List<Row> rows = b.rows(true);
        Row hl = rows.get(3);
        int shoes = 120;
        Path parts = tmp.resolve("parts");
        Files.createDirectories(parts);
        P3 p3 = new P3(SEED, rows, b.chart, Plants.NONE).extras(registry(), parts, 100);
        double[] sums = p3.run(0, shoes);
        assertEquals(3, p3.swings().size());
        Swings f18 = p3.swings().get(1);
        assertEquals("F18", f18.fig.id());
        double[] s = new double[8];
        TreeMap<String, long[]> hist = new TreeMap<>();
        List<String> records = new ArrayList<>();
        Map<Integer, double[]> key0 = new TreeMap<>();
        for (long h = 0; h < shoes; h++) {
            Walked one = walk(hl.folds[(int) (h & 1)], b.chart, h);
            double y = one.y[1];
            double[] add = {1, y, y * y, y * y * y, y * y * y * y, one.n, one.n * one.n, y * one.n};
            for (int i = 0; i < 8; i++) {
                s[i] += add[i];
            }
            one.hist.forEach((k, v) -> {
                long[] c = hist.computeIfAbsent(k, z -> new long[2]);
                c[0] += v[0];
                c[1] += v[1];
            });
            if (h < 100) {
                records.add(y + "\t" + one.n + "\t" + one.low);
            }
            walk(hl.folds[0], b.chart, h).byKey.forEach((k, v) -> {
                double[] t = key0.computeIfAbsent(k, z -> new double[3]);
                t[0] += v[0];
                t[1] += v[0] * v[0];
                t[2] += v[1];
            });
        }
        List<String> lines = f18.lines(sums);
        String[] shoesLine = lines.get(0).split("\t");
        assertEquals("shoes", shoesLine[0]);
        assertEquals(Long.toString(shoes), shoesLine[2]);
        for (int i = 1; i < 8; i++) {
            assertEquals(s[i], Double.parseDouble(shoesLine[2 + i]), 1e-9 * Math.max(1, Math.abs(s[i])), "shoes sum " + i);
        }
        TreeMap<String, long[]> fromLines = new TreeMap<>();
        for (String l : lines.subList(1, lines.size())) {
            String[] f = l.split("\t");
            fromLines.put(Double.parseDouble(f[2]) + "," + f[3], new long[]{Long.parseLong(f[4]), Long.parseLong(f[5])});
        }
        assertEquals(hist.keySet(), fromLines.keySet());
        hist.forEach((k, v) -> assertEquals(List.of(v[0], v[1]), List.of(fromLines.get(k)[0], fromLines.get(k)[1]), k));
        assertTrue(hist.keySet().stream().anyMatch(k -> fromLines.get(k)[1] > 0), "some rounds split");
        p3.writeRecords(tmp, 120);
        List<String> written = Files.readAllLines(tmp.resolve("records-F18.tsv"));
        assertEquals(records.size(), written.size());
        for (int i = 0; i < records.size(); i++) {
            String[] want = records.get(i).split("\t");
            String[] got = written.get(i).split("\t");
            assertEquals(Double.parseDouble(want[0]), Double.parseDouble(got[0]), 1e-9, "Y of shoe " + i);
            assertEquals(want[1], got[1], "N of shoe " + i);
            assertEquals(Double.parseDouble(want[2]), Double.parseDouble(got[2]), 1e-9, "M of shoe " + i);
        }
        EvByKey ev = p3.evs().get(0);
        List<String> evLines = ev.lines(sums, shoes, null, null);
        for (Map.Entry<Integer, double[]> e : key0.entrySet()) {
            String want = "sim\tHi-Lo = Hi-Lo Lite\t80\t0\t" + e.getKey() + "\t" + shoes + "\t";
            String line = evLines.stream().filter(l -> l.startsWith(want)).findFirst().orElseThrow();
            String[] f = line.split("\t");
            assertEquals(e.getValue()[0], Double.parseDouble(f[6]), 1e-9);
            assertEquals(e.getValue()[1], Double.parseDouble(f[7]), 1e-9);
            assertEquals(e.getValue()[2], Double.parseDouble(f[8]), 1e-9);
        }
        assertEquals(4 * key0.size(), evLines.size(), "four lines a key, both folds meeting the same keys");
    }

    /** A row of another count: Hi-Lo's tags, but the other fold's groups and bets, so it plays differently. */
    static Strategy other(int fold) throws IOException {
        List<String> lines = new ArrayList<>(BundleFixture.hiLo("first", 1 - fold).lines());
        lines.replaceAll(l -> l.startsWith("row\t") ? "row\tOther count" : l.startsWith("playcount\t") ? "playcount\tOther"
                : l.startsWith("betcount\t") ? "betcount\tOther" : l.startsWith("fold\t") ? "fold\t" + fold + "\t1" : l);
        return Strategy.parse(lines, "other", Plants.NONE);
    }

    @Test
    void aCountsDifferenceFromHiLoIsTheDirectPairedFormula() throws Exception {
        Strategy[] hl = {BundleFixture.hiLo("first", 0).read(), BundleFixture.hiLo("first", 1).read()};
        Strategy[] ot = {other(0), other(1)};
        Row hiLo = Row.of(hl[0], hl[1], 0);
        Row oth = Row.of(ot[0], ot[1], hiLo.size());
        Chart chart = Chart.textbook();
        P3 p3 = new P3(SEED, List.of(hiLo, oth), chart, Plants.NONE).extras(null, null, 0);
        assertEquals(3, p3.pairs().size(), "flat, same-8 and X-1,8, each against Hi-Lo's");
        int shoes = 150;
        double[] sums = p3.run(0, shoes);
        double[] w = {0.4, 0.6};
        double[] v = {0.55, 0.45};
        for (int j = 0; j < 3; j++) {
            double[] yo = new double[shoes];
            double[] yh = new double[shoes];
            double[] n = new double[shoes];
            double so = 0, sh = 0, sn = 0;
            for (int h = 0; h < shoes; h++) {
                for (int f = 0; f < 2; f++) {
                    Walked a = walk(ot[f], chart, h);
                    Walked c = walk(hl[f], chart, h);
                    yo[h] += w[f] * a.y[j];
                    yh[h] += v[f] * c.y[j];
                    n[h] = a.n;
                }
                so += yo[h];
                sh += yh[h];
                sn += n[h];
            }
            double d = (so - sh) / sn;
            double ss = 0;
            for (int h = 0; h < shoes; h++) {
                double e = (yo[h] - so / sn * n[h]) / sn - (yh[h] - sh / sn * n[h]) / sn;
                ss += e * e;
            }
            double[] got = p3.pair(sums, j, w[0], w[1], v[0], v[1]);
            assertEquals(d, got[0], 1e-12, "difference, figure " + j);
            assertEquals(Math.sqrt(shoes / (shoes - 1.0) * ss), got[1], 1e-9 * got[1], "SE, figure " + j);
        }
    }

    @Test
    void theFanTakesWholeShoesOneFoldAShoe() throws Exception {
        Strategy[] hl = {BundleFixture.hiLo("first", 0).read(), BundleFixture.hiLo("first", 1).read()};
        Row hiLo = Row.of(hl[0], hl[1], 0);
        Chart chart = Chart.textbook();
        double[][] fan = P3.fan(SEED, hiLo, 1, chart, Plants.NONE, 3, 150, 50);
        long h = 0;
        for (int j = 0; j < 3; j++) {
            List<Double> wins = new ArrayList<>();
            while (wins.size() < 150) {
                wins.addAll(walk(hl[(int) (h & 1)], chart, h).wins);
                h++;
            }
            double cum = 0;
            for (int r = 0; r < 150; r++) {
                cum += wins.get(r);
                if ((r + 1) % 50 == 0) {
                    assertEquals(cum, fan[j][(r + 1) / 50 - 1], 1e-9, "path " + j + " after " + (r + 1) + " rounds");
                }
            }
        }
        assertTrue(fan[1][0] != fan[0][0] || fan[1][1] != fan[0][1], "paths start at different shoes");
        List<String> lines = Outputs.fan(fan);
        assertEquals(3, lines.size());
        assertEquals(4, lines.get(2).split("\t").length);
        assertTrue(lines.get(2).startsWith("2\t"));
    }

    @Test
    void aResultOutsideTheRulesRangeStopsTheRun() {
        Registry.Fig fig = new Registry.Fig("F00", 1, Row.CHART, 80, "flat", List.of("P3"), "x", true, false);
        Swings s = new Swings(fig, 0, Row.chart(80, 0), 0, Rules.montreal(), 0);
        double[] sums = new double[s.size()];
        s.round(sums, 1.0, -8.5, true);
        s.round(sums, 1.0, 8, false);
        assertThrows(IllegalStateException.class, () -> s.round(sums, 1.0, -9, false));
        assertThrows(IllegalStateException.class, () -> s.round(sums, 1.0, 8.5, false));
        assertThrows(IllegalStateException.class, () -> s.round(sums, 1.0, 0.25, false));
        assertThrows(IllegalStateException.class, () -> s.round(sums, 2.0, 1, false));
        assertEquals(List.of("shoes\tF00\t0\t0.0\t0.0\t0.0\t0.0\t0.0\t0.0\t0.0", "hist\tF00\t1.0\t-17\t1\t1",
                "hist\tF00\t1.0\t16\t1\t0"), s.lines(sums));
    }
}
