import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Deals shoes under Montreal's rules to the 80% cut card and values the states chosen, into
 * a state file that resumes where it stopped.
 *
 * java -Xmx3g -cp build/classes:lib/mongo-java-driver-3.12.14.jar StateRun FILE SEED Q0 U0 SHOES [THREADS]
 *
 * SHOES is the number of shoes the file should hold when the run ends; a file that already
 * holds some continues with the next. Q0 and U0 set the chance each state is valued
 * (ShoeRun). Each thread values whole states, several shoes are in flight at once, and
 * shoes are written in order as they finish. Every 25 shoes it prints the valued states so
 * far and the sustained wall seconds per valued state since the run started, which is the
 * figure a run's length follows.
 */
public final class StateRun {

    static final int CUT_80 = 332;

    static RoundRules montreal() {
        return RoundRules.from(HouseRules.getMtlCasino25MinBlackjackParams(80));
    }

    /** What the header records about the game and the play, beyond the seed and the rates. */
    static String describe(RoundRules r, RoundPolicy play) {
        StringBuilder das = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            das.append(r.doubleAfterSplit[i] ? i + " " : "");
        }
        return "decks " + ShoeRun.DECKS + ", H17 " + r.hitsSoft17 + ", payout " + r.blackjackPayout + ", hands aces "
                + r.maxHandsAces + ", hands other " + r.maxHandsNotAces + ", hit split aces " + r.canHitSplitAces
                + ", DAS " + das.toString().trim() + ", BJ on split pairs " + r.blackjackOnSplitPairs
                + ", surrender " + r.surrender + ", after split " + r.surrenderAfterSplit
                + ", play and continuation BasicStrategy " + BasicStrategy.fingerprint(play);
    }

    private static final class Pending {
        final int shoe;
        final List<ShoeRun.State> valued = new ArrayList<>();
        final List<Future<double[]>> values = new ArrayList<>();

        Pending(int shoe) {
            this.shoe = shoe;
        }
    }

    public static void main(String[] args) throws Exception {
        Path file = Paths.get(args[0]);
        long seed = Long.parseLong(args[1]);
        double q0 = Double.parseDouble(args[2]);
        double u0 = Double.parseDouble(args[3]);
        int shoes = Integer.parseInt(args[4]);
        int threads = args.length > 5 ? Integer.parseInt(args[5]) : Runtime.getRuntime().availableProcessors();

        RoundRules rules = montreal();
        RoundPolicy basic = BasicStrategy.chart();
        StateStore.Header header = new StateStore.Header(seed, q0, u0, CUT_80, describe(rules, basic));
        int first = StateStore.open(file, header);
        ShoeRun run = new ShoeRun(seed, q0, u0, CUT_80, rules, basic);
        System.out.printf("%s: shoes %d to %d, q0 %.4f, u0 %.3f, %d threads%n", file, first, shoes - 1, q0, u0, threads);

        long start = System.nanoTime();
        long valuedSoFar = 0;
        long roundsSoFar = 0;
        Deque<Pending> inFlight = new ArrayDeque<>();
        int statesInFlight = 0;
        int next = first;
        try (StateValuer valuer = new StateValuer(rules, basic, threads)) {
            while (next < shoes || !inFlight.isEmpty()) {
                while (next < shoes && statesInFlight < 3 * threads) {
                    Pending p = new Pending(next);
                    for (ShoeRun.State s : run.deal(next)) {
                        roundsSoFar++;
                        if (s.valued) {
                            p.valued.add(s);
                            p.values.add(valuer.submit(s.ranksLeft()));
                        }
                    }
                    statesInFlight += p.valued.size();
                    inFlight.addLast(p);
                    next++;
                }
                Pending done = inFlight.removeFirst();
                List<StateStore.Record> records = new ArrayList<>();
                for (int i = 0; i < done.valued.size(); i++) {
                    ShoeRun.State s = done.valued.get(i);
                    records.add(new StateStore.Record(s.shoe, s.round, s.depth, s.q, s.dealt, done.values.get(i).get()));
                }
                StateStore.appendShoe(file, done.shoe, records);
                statesInFlight -= done.valued.size();
                valuedSoFar += records.size();
                if ((done.shoe + 1) % 25 == 0 || done.shoe == shoes - 1) {
                    double wall = (System.nanoTime() - start) / 1e9;
                    System.out.printf("shoe %d done: %d states valued this run, of %d rounds dealt; %.0f s, %.2f s per valued state%n",
                            done.shoe, valuedSoFar, roundsSoFar, wall, valuedSoFar == 0 ? 0 : wall / valuedSoFar);
                }
            }
        }
    }
}
