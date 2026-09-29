import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Values every first move of every deal from the shoes it is given: V(m) as ExactRound
 * computes it, conditional on no dealer natural, with the continuation policy playing every
 * decision after the first.
 *
 * The result for one shoe is a double[Deals.COUNT * MOVES.length]: the value of move m in
 * deal i sits at i * MOVES.length + m. It is NaN where the move is not legal, where the
 * player holds a natural (paid at once, with no move), and where the shoe cannot make the
 * deal.
 *
 * A long run gives each thread whole states (submit), so no thread waits for another's
 * slowest split; value spreads the deals of a few shoes over the threads instead. Each
 * thread keeps its own ExactRound, which caches the dealer's hands per up-card. Any call
 * that takes longer than a minute is reported with its shoe, since the only way one could
 * is the slow coupled split computation on a shoe near running out.
 */
final class StateValuer implements AutoCloseable {

    static final PlayerMove[] MOVES = {PlayerMove.Stand, PlayerMove.Hit, PlayerMove.Double,
            PlayerMove.Split, PlayerMove.Surrender};

    private final RoundRules rules;
    private final RoundPolicy continuation;
    private final ExecutorService pool;
    private final ThreadLocal<ExactRound> engines;

    StateValuer(RoundRules rules, RoundPolicy continuation, int threads) {
        this.rules = rules;
        this.continuation = continuation;
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "state-valuer");
            t.setDaemon(true);
            return t;
        });
        this.engines = ThreadLocal.withInitial(() -> new ExactRound(rules));
    }

    static int slot(int deal, PlayerMove m) {
        for (int k = 0; k < MOVES.length; k++) {
            if (MOVES[k] == m) {
                return deal * MOVES.length + k;
            }
        }
        throw new IllegalArgumentException(m.toString());
    }

    /** Whether m is a legal first move for deal i under these rules. */
    boolean legal(int deal, PlayerMove m) {
        return legal(rules, deal, m);
    }

    /** Whether m is a legal first move for deal i under the rules given, so a stored value exists. */
    static boolean legal(RoundRules rules, int deal, PlayerMove m) {
        if (Deals.playerNatural(deal)) {
            return false;
        }
        int p1 = Deals.P1[deal];
        int p2 = Deals.P2[deal];
        switch (m) {
            case Stand:
            case Hit:
            case Double:
                return true;
            case Split:
                return p1 == p2 && rules.maxHandsFor(p1) >= 2;
            case Surrender:
                return rules.surrender;
            default:
                return false;
        }
    }

    /** Values one shoe on one pool thread. */
    Future<double[]> submit(int[] shoe) {
        int[] copy = shoe.clone();
        return pool.submit(() -> {
            double[] values = new double[Deals.COUNT * MOVES.length];
            java.util.Arrays.fill(values, Double.NaN);
            for (int deal = 0; deal < Deals.COUNT; deal++) {
                if (!Deals.playerNatural(deal) && Deals.probability(copy, deal) > 0) {
                    valueDeal(copy, deal, values);
                }
            }
            return values;
        });
    }

    /** The values of every shoe given, in the same order. */
    List<double[]> value(List<int[]> shoes) throws InterruptedException {
        List<double[]> out = new ArrayList<>();
        List<Future<?>> jobs = new ArrayList<>();
        for (int[] shoe : shoes) {
            double[] values = new double[Deals.COUNT * MOVES.length];
            java.util.Arrays.fill(values, Double.NaN);
            out.add(values);
            for (int deal = 0; deal < Deals.COUNT; deal++) {
                if (Deals.playerNatural(deal) || Deals.probability(shoe, deal) == 0) {
                    continue;
                }
                final int i = deal;
                jobs.add(pool.submit(() -> valueDeal(shoe, i, values)));
            }
        }
        for (Future<?> f : jobs) {
            try {
                f.get();
            } catch (ExecutionException e) {
                throw new IllegalStateException("valuing a deal failed", e.getCause());
            }
        }
        return out;
    }

    private void valueDeal(int[] shoe, int deal, double[] values) {
        ExactRound exact = engines.get();
        int[] left = Deals.after(shoe, deal);
        for (PlayerMove m : MOVES) {
            if (legal(deal, m)) {
                long start = System.nanoTime();
                values[slot(deal, m)] = exact.valueOfFirstMove(left, Deals.P1[deal], Deals.P2[deal],
                        Deals.UP[deal], m, continuation);
                double seconds = (System.nanoTime() - start) / 1e9;
                if (seconds > 60) {
                    System.err.printf("slow: %.0f s for %d,%d vs %d %s on %s%n", seconds, Deals.P1[deal],
                            Deals.P2[deal], Deals.UP[deal], m, java.util.Arrays.toString(left));
                }
            }
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
