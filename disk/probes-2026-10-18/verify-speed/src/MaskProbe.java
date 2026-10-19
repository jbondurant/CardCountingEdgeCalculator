import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Scratch, read-only: full vs R-like-masked builds, PlayerTree's batch and fast2, on N threads; bits on the masked cells. */
public class MaskProbe {
    static final PlayerTree.Hands H = PlayerTree.Hands.ALL;
    static boolean inR(int h) {
        if (H.cards[h] < 3) return false;
        boolean soft = H.ace[h] && H.hard[h] + 10 <= 21;
        int t = soft ? H.hard[h] + 10 : H.hard[h];
        if (t > 21) return false;
        return soft ? t >= 13 : t >= 12;
    }
    /** PlayerTree's own batch path, on the wanted hands only. */
    static double[][] masked(ExactRound engine, int[] shoe, boolean[][] want) {
        int cards = 0; for (int r = 1; r <= 10; r++) cards += shoe[r];
        double[][] stand = new double[11][];
        double[][] many = new double[ExactRound.DealerOutcomes.OUTCOMES][H.size];
        int[][] counts = new int[11][H.size]; int[] sizes = new int[H.size]; int[] which = new int[H.size]; int[] left = new int[11];
        for (int u = 1; u <= 10; u++) {
            double[] s = new double[H.size]; Arrays.fill(s, Double.NaN); stand[u] = s;
            if (shoe[u] == 0) continue;
            ExactRound.DealerOutcomes dealer = engine.dealerOutcomes(shoe, u);
            int together = 0;
            for (int h = 0; h < H.size; h++) {
                if (want != null && !want[u][h]) continue;
                boolean fits = true;
                for (int r = 1; r <= 10; r++) { left[r] = shoe[r] - H.held[h * 11 + r] - (r == u ? 1 : 0); fits &= left[r] >= 0; }
                if (!fits) continue;
                int after = cards - H.cards[h] - 1;
                if (after <= dealer.mostCardsToRunOutOf()) throw new IllegalStateException("small");
                for (int r = 1; r <= 10; r++) counts[r][together] = left[r];
                sizes[together] = after; which[together] = h; together++;
            }
            dealer.outcomes(counts, sizes, together, many);
            for (int k = 0; k < together; k++) s[which[k]] = ExactRound.DealerOutcomes.stood(H.total(which[k]), many, k);
        }
        return stand;
    }
    public static void main(String[] a) throws Exception {
        Path data = Paths.get(a[0]); String[] offs = a[1].split(","); int per = Integer.parseInt(a[2]); int threads = Integer.parseInt(a[3]);
        FastProbe.FALL = ExactRound.DealerOutcomes.class.getDeclaredField("fall"); FastProbe.FALL.setAccessible(true);
        FastProbe.INV = ExactRound.DealerOutcomes.class.getDeclaredField("inverseFall"); FastProbe.INV.setAccessible(true);
        List<int[]> shoes = new ArrayList<>();
        for (String o : offs) for (StateStore.Record r : StateStore.read(data, Integer.parseInt(o), per)) shoes.add(r.ranksLeft());
        int n = shoes.size();
        boolean[][] want = new boolean[11][H.size]; int inR = 0;
        for (int h = 0; h < H.size; h++) if (inR(h)) { inR++; for (int u = 1; u <= 10; u++) want[u][h] = true; }
        System.out.printf("states %d, threads %d; hands in the R-like mask %d of %d an up-card%n", n, threads, inR, H.size);
        ExactRound e0 = new ExactRound(StateRun.montreal());
        FastProbe.Plan[] plans = new FastProbe.Plan[11];
        for (int u = 1; u <= 10; u++) plans[u] = FastProbe.plan(e0, shoes.get(0), u);
        SpeedProbe.parallel(threads, 2 * threads, (i, e) -> { new PlayerTree(e, shoes.get(i)); masked(e, shoes.get(i), want); FastProbe.fast2(e, plans, shoes.get(i), want, new long[3]); FastProbe.fast2(e, plans, shoes.get(i), null, new long[3]); });
        double[][][] ref = new double[n][][];
        AtomicLong feas = new AtomicLong(), got = new AtomicLong();
        double[] full = SpeedProbe.parallel(threads, n, (i, e) -> { PlayerTree t = new PlayerTree(e, shoes.get(i)); double[][] rows = new double[11][]; for (int u = 1; u <= 10; u++) rows[u] = t.standRow(u); ref[i] = rows; });
        AtomicLong d1 = new AtomicLong(), d2 = new AtomicLong();
        double[] m1 = SpeedProbe.parallel(threads, n, (i, e) -> { double[][] f = masked(e, shoes.get(i), want); long d = 0, fe = 0, g = 0;
            for (int u = 1; u <= 10; u++) for (int h = 0; h < H.size; h++) { if (!Double.isNaN(ref[i][u][h])) fe++; if (!want[u][h]) continue; if (!Double.isNaN(f[u][h])) g++; if (Double.doubleToRawLongBits(f[u][h]) != Double.doubleToRawLongBits(ref[i][u][h])) d++; }
            d1.addAndGet(d); feas.addAndGet(fe); got.addAndGet(g); });
        double[] f2 = SpeedProbe.parallel(threads, n, (i, e) -> FastProbe.fast2(e, plans, shoes.get(i), null, new long[3]));
        double[] m2 = SpeedProbe.parallel(threads, n, (i, e) -> { double[][] f = FastProbe.fast2(e, plans, shoes.get(i), want, new long[3]); long d = 0;
            for (int u = 1; u <= 10; u++) for (int h = 0; h < H.size; h++) { if (!want[u][h]) continue; if (Double.doubleToRawLongBits(f[u][h]) != Double.doubleToRawLongBits(ref[i][u][h])) d++; }
            d2.addAndGet(d); });
        System.out.printf("masked cells %.1f of %.1f feasible a state (%.1f%%)%n", got.get() / (double) n, feas.get() / (double) n, 100.0 * got.get() / feas.get());
        System.out.printf("wall s a tree: PlayerTree %.4f; masked batch %.4f (%.3f); fast2 full %.4f (%.3f); fast2 masked %.4f (%.3f)%n",
                full[0] / n, m1[0] / n, m1[0] / full[0], f2[0] / n, f2[0] / full[0], m2[0] / n, m2[0] / full[0]);
        System.out.printf("CPU s a tree:  PlayerTree %.4f; masked batch %.4f (%.3f); fast2 full %.4f (%.3f); fast2 masked %.4f (%.3f)%n",
                full[1] / n, m1[1] / n, m1[1] / full[1], f2[1] / n, f2[1] / full[1], m2[1] / n, m2[1] / full[1]);
        System.out.printf("bits differing on masked cells: batch %d, fast2 %d; load %.2f%n", d1.get(), d2.get(), ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
    }
}
