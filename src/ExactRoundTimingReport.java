import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * How long ExactRound takes on a real eight-deck shoe, for a few rounds that range from one
 * draw to four split hands.
 *
 * The shoe is the Montreal one, 32 of each rank from ace to nine and 128 tens, less the
 * player's two cards and the up-card. The rules are Montreal's (dealer hits soft 17, 3:2,
 * double after any split but aces, split aces take one card, no surrender), with the split
 * limits each case names. Every decision after the first move follows a basic-strategy-
 * like policy, written here so that the numbers do not depend on any solved table.
 *
 * Each case is valued a few times in one JVM, with a fresh ExactRound each time. The first
 * run includes the JIT warming up; the fastest is closer to what a long batch of calls
 * would see. Both the wall time and the thread's CPU time are shown, as in
 * StateTimingReport. On a machine busy with other work a call of a few milliseconds can
 * wait for a core longer than it runs, so its wall time says more about the machine than
 * about the engine; the CPU time is the figure to compare between versions, measured in one
 * sitting, since it too grows when other work shares the memory. Run it after
 * ./run-tests.sh has compiled the sources:
 * java -cp build/classes ExactRoundTimingReport [runs per case].
 */
public class ExactRoundTimingReport {

    /**
     * BasicStrategy's chart. With alwaysResplit, every pair that may split does, the worst
     * case for a split's cost.
     */
    static RoundPolicy basicStrategy(boolean alwaysResplit) {
        return BasicStrategy.policy(alwaysResplit);
    }

    static RoundRules montreal(int maxHandsAces, int maxHandsNotAces) {
        boolean[] das = new boolean[11];
        for (int r = 2; r <= 10; r++) {
            das[r] = true;
        }
        return new RoundRules(true, 1.5, maxHandsAces, maxHandsNotAces, false, das, false, false, false);
    }

    static int[] eightDecksLess(int... dealt) {
        int[] shoe = new int[11];
        for (int r = 1; r <= 9; r++) {
            shoe[r] = 32;
        }
        shoe[10] = 128;
        for (int c : dealt) {
            shoe[c]--;
        }
        return shoe;
    }

    private static void time(String name, RoundRules rules, int p1, int p2, int up, PlayerMove first,
                             RoundPolicy policy, int runs) {
        int[] shoe = eightDecksLess(p1, p2, up);
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        double value = 0;
        long firstWall = 0;
        long firstCpu = 0;
        long bestWall = Long.MAX_VALUE;
        long bestCpu = Long.MAX_VALUE;
        for (int i = 0; i < runs; i++) {
            // A fresh ExactRound each run, so no run reuses another's dealer hands.
            ExactRound exact = new ExactRound(rules);
            long startCpu = threads.getCurrentThreadCpuTime();
            long start = System.nanoTime();
            value = exact.valueOfFirstMove(shoe, p1, p2, up, first, policy);
            long wall = System.nanoTime() - start;
            long cpu = threads.getCurrentThreadCpuTime() - startCpu;
            if (i == 0) {
                firstWall = wall;
                firstCpu = cpu;
            }
            bestWall = Math.min(bestWall, wall);
            bestCpu = Math.min(bestCpu, cpu);
        }
        System.out.printf("%-50s %+.15f   wall first %8.1f fastest %8.1f ms   cpu first %8.1f fastest %8.1f ms%n",
                name, value, firstWall / 1e6, bestWall / 1e6, firstCpu / 1e6, bestCpu / 1e6);
    }

    public static void main(String[] args) {
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        RoundPolicy basic = basicStrategy(false);
        RoundPolicy resplit = basicStrategy(true);
        time("hard 16 (10,6) vs 10, Hit", montreal(2, 4), 10, 6, 10, PlayerMove.Hit, basic, runs);
        time("11 (6,5) vs 6, Double", montreal(2, 4), 6, 5, 6, PlayerMove.Double, basic, runs);
        time("A,A vs 6, Split, aces to 2 hands", montreal(2, 4), 1, 1, 6, PlayerMove.Split, basic, runs);
        time("8,8 vs 10, Split, 4 hands, always resplit", montreal(2, 4), 8, 8, 10, PlayerMove.Split, resplit, runs);
        time("10,10 vs 6, Split, 4 hands, basic (stands on 20)", montreal(2, 4), 10, 10, 6, PlayerMove.Split, basic, runs);
        time("10,10 vs 6, Split, 4 hands, always resplit", montreal(2, 4), 10, 10, 6, PlayerMove.Split, resplit, runs);
        // Two harder shapes: small cards, so every hand takes many draws.
        time("2,2 vs 6, Split, 4 hands, always resplit", montreal(2, 4), 2, 2, 6, PlayerMove.Split, resplit, runs);
        time("A,2 vs 2, Hit", montreal(2, 4), 1, 2, 2, PlayerMove.Hit, basic, runs);
    }
}
