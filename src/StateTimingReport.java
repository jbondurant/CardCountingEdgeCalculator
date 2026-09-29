import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * How long one state takes to value, the unit a long run of the counting comparison is made
 * of: every legal first move of all 550 deals, as StateValuer values them, on one thread.
 *
 * The states are four fixed eight-deck shoes: a full one, and three from deep in a shoe, rich
 * in tens, rich in small cards, and neutral but short of aces. The rules are StateRun's
 * (Montreal's) and every decision after the first move is BasicStrategy's chart. Each state
 * is valued with a fresh ExactRound, as a thread starting on it would, in the order
 * StateValuer takes the deals and moves, and the time spent in splits is shown apart from the
 * rest, since splits were most of it.
 *
 * Every state is valued once per pass; the first pass includes the JIT warming up. Both the
 * wall time and the thread's CPU time are shown: on a machine busy with other work the wall
 * time grows with the wait for a core, and the CPU time much less. Run it after
 * ./run-tests.sh has compiled the sources:
 * java -cp build/classes StateTimingReport [passes]
 */
public final class StateTimingReport {

    /** The states' names, in the order of SHOES. */
    static final String[] NAMES = {"full shoe", "deep, rich in tens", "deep, rich in small cards",
            "mid-shoe, short of aces"};

    /**
     * The cards left in each state, ace to ten at indices 1 to 10. A full shoe is 32 of each
     * rank from ace to nine and 128 tens.
     *
     * Deep, rich in tens: 166 cards left of 416, with the small cards gone faster than the
     * rest, a Hi-Lo running count of +34, about +10.6 a deck. Deep, rich in small cards: 135
     * left, a running count of -32, about -12.3 a deck. Mid-shoe, short of aces: 200 left, a
     * running count of 0, and 6 aces where about 15 would be usual.
     */
    static final int[][] SHOES = {
            {0, 32, 32, 32, 32, 32, 32, 32, 32, 32, 128},
            {0, 12, 9, 10, 9, 8, 10, 13, 13, 14, 68},
            {0, 10, 14, 14, 13, 14, 13, 11, 10, 10, 26},
            {0, 6, 14, 14, 14, 14, 14, 20, 20, 20, 64},
    };

    private StateTimingReport() {
    }

    public static void main(String[] args) {
        int passes = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        RoundRules rules = StateRun.montreal();
        RoundPolicy chart = BasicStrategy.chart();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        try (StateValuer legality = new StateValuer(rules, chart, 1)) {
            for (int pass = 1; pass <= passes; pass++) {
                // wall and CPU nanoseconds, for splits [0] and the rest [1], over every state
                long[] allWall = new long[2];
                long[] allCpu = new long[2];
                for (int s = 0; s < SHOES.length; s++) {
                    int[] shoe = SHOES[s];
                    ExactRound exact = new ExactRound(rules);
                    long[] wall = new long[2];
                    long[] cpu = new long[2];
                    int[] calls = new int[2];
                    double checksum = 0;
                    for (int deal = 0; deal < Deals.COUNT; deal++) {
                        if (Deals.playerNatural(deal) || Deals.probability(shoe, deal) == 0) {
                            continue;
                        }
                        int[] left = Deals.after(shoe, deal);
                        for (PlayerMove m : StateValuer.MOVES) {
                            if (!legality.legal(deal, m)) {
                                continue;
                            }
                            long startCpu = threads.getCurrentThreadCpuTime();
                            long start = System.nanoTime();
                            checksum += exact.valueOfFirstMove(left, Deals.P1[deal], Deals.P2[deal],
                                    Deals.UP[deal], m, chart);
                            int kind = m == PlayerMove.Split ? 0 : 1;
                            wall[kind] += System.nanoTime() - start;
                            cpu[kind] += threads.getCurrentThreadCpuTime() - startCpu;
                            calls[kind]++;
                        }
                    }
                    for (int k = 0; k < 2; k++) {
                        allWall[k] += wall[k];
                        allCpu[k] += cpu[k];
                    }
                    System.out.printf("pass %d  %-26s %s   %d splits, %d other calls, values summing to %+.12f%n",
                            pass, NAMES[s], seconds(wall, cpu, 1), calls[0], calls[1], checksum);
                }
                System.out.printf("pass %d  %-26s %s%n", pass, "mean of the four", seconds(allWall, allCpu, SHOES.length));
            }
        }
    }

    /** Seconds a state, wall and CPU: in all, in splits, and in the rest. */
    private static String seconds(long[] wall, long[] cpu, int states) {
        return String.format("wall %6.2f s (splits %6.2f, rest %5.2f)   cpu %6.2f s (splits %6.2f, rest %5.2f)",
                (wall[0] + wall[1]) / 1e9 / states, wall[0] / 1e9 / states, wall[1] / 1e9 / states,
                (cpu[0] + cpu[1]) / 1e9 / states, cpu[0] / 1e9 / states, cpu[1] / 1e9 / states);
    }
}
