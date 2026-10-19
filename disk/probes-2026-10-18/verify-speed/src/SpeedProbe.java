import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Scratch, read-only: PlayerTree and FastProbe.fast2 built on 1 and on N threads; wall, CPU, bits. */
public class SpeedProbe {
    static final ThreadMXBean BEAN = ManagementFactory.getThreadMXBean();
    interface Job { void run(int i, ExactRound engine) throws Exception; }

    static double[] parallel(int threads, int n, Job job) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger next = new AtomicInteger();
        DoubleAdder cpu = new DoubleAdder();
        long w0 = System.nanoTime();
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) fs.add(pool.submit(() -> {
            ExactRound engine = new ExactRound(StateRun.montreal());
            long c0 = BEAN.getCurrentThreadCpuTime();
            for (int i = next.getAndIncrement(); i < n; i = next.getAndIncrement()) job.run(i, engine);
            cpu.add((BEAN.getCurrentThreadCpuTime() - c0) / 1e9);
            return null;
        }));
        for (Future<?> f : fs) f.get();
        double wall = (System.nanoTime() - w0) / 1e9;
        pool.shutdown();
        return new double[] {wall, cpu.sum()};
    }

    public static void main(String[] a) throws Exception {
        Path data = Paths.get(a[0]);
        String[] offs = a[1].split(",");
        int per = Integer.parseInt(a[2]);
        int threads = Integer.parseInt(a[3]);
        FastProbe.FALL = ExactRound.DealerOutcomes.class.getDeclaredField("fall"); FastProbe.FALL.setAccessible(true);
        FastProbe.INV = ExactRound.DealerOutcomes.class.getDeclaredField("inverseFall"); FastProbe.INV.setAccessible(true);
        List<int[]> shoes = new ArrayList<>();
        for (String o : offs) for (StateStore.Record r : StateStore.read(data, Integer.parseInt(o), per)) shoes.add(r.ranksLeft());
        int n = shoes.size();
        ExactRound e0 = new ExactRound(StateRun.montreal());
        FastProbe.Plan[] plans = new FastProbe.Plan[11];
        for (int u = 1; u <= 10; u++) plans[u] = FastProbe.plan(e0, shoes.get(0), u);
        System.out.printf("states %d; threads %d; processors %d; load %.2f%n", n, threads,
                Runtime.getRuntime().availableProcessors(), ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
        // warm up, on the pool threads
        parallel(threads, Math.min(n, 2 * threads), (i, e) -> { new PlayerTree(e, shoes.get(i)); FastProbe.fast2(e, plans, shoes.get(i), null, new long[3]); });
        double[][][] ref = new double[n][][];
        double[] t1 = parallel(1, Math.min(n, 24), (i, e) -> new PlayerTree(e, shoes.get(i)));
        System.out.printf("PlayerTree, 1 thread, %d states: wall %.4f s a tree, CPU %.4f s a tree%n", Math.min(n, 24), t1[0] / Math.min(n, 24), t1[1] / Math.min(n, 24));
        double[] f1 = parallel(1, Math.min(n, 24), (i, e) -> FastProbe.fast2(e, plans, shoes.get(i), null, new long[3]));
        System.out.printf("fast2,      1 thread, %d states: wall %.4f s a tree, CPU %.4f s a tree (%.3f of PlayerTree)%n", Math.min(n, 24), f1[0] / Math.min(n, 24), f1[1] / Math.min(n, 24), f1[1] / t1[1]);
        for (int rep = 0; rep < 2; rep++) {
            double[] tp = parallel(threads, n, (i, e) -> { PlayerTree t = new PlayerTree(e, shoes.get(i)); double[][] rows = new double[11][]; for (int u = 1; u <= 10; u++) rows[u] = t.standRow(u); ref[i] = rows; });
            System.out.printf("PlayerTree, %d threads, %d states: wall %.4f s a tree (%.1f trees/s), CPU %.4f s a tree; 60,743 trees -> %.0f s wall; load %.2f%n",
                    threads, n, tp[0] / n, n / tp[0], tp[1] / n, 60743 * tp[0] / n, ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
            AtomicLong diff = new AtomicLong();
            double[] fp = parallel(threads, n, (i, e) -> { double[][] f = FastProbe.fast2(e, plans, shoes.get(i), null, new long[3]);
                long d = 0; for (int u = 1; u <= 10; u++) { double[] r = ref[i][u]; for (int h = 0; h < r.length; h++) if (Double.doubleToRawLongBits(r[h]) != Double.doubleToRawLongBits(f[u][h])) d++; } diff.addAndGet(d); });
            System.out.printf("fast2,      %d threads, %d states: wall %.4f s a tree, CPU %.4f s a tree; wall ratio %.3f, CPU ratio %.3f; cells differing in any bit %d; load %.2f%n",
                    threads, n, fp[0] / n, fp[1] / n, fp[0] / tp[0], fp[1] / tp[1], diff.get(), ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
        }
    }
}
