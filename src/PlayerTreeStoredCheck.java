import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The player tree held to the stored values over the whole state file, as FULL_INDEX_PLAYS.md
 * section 2 asks: "With basic strategy, the tree's V(Hit) for every deal of every stored state
 * equals the stored value within 1e-12." PlayerTreeTest makes the same comparison on 40 states
 * spread through the file, which a test run can afford; this makes it on every state, once.
 *
 * For each state it builds the trees and walks them under the chart, for Hi-Lo, Red Seven,
 * KISS I and BRH-I (counts that split no rank, one rank and two, and the unbalanced true
 * count) and in both walks. Every part of every deal must have the stored V(Hit), and every
 * start's stand value, made conditional on no natural, the stored V(Stand). A value stored as
 * NaN must be a deal the state cannot deal or a natural, and the tree must give NaN there too.
 * Every state must hold 85 cards or more, which the tree relies on and checks itself.
 *
 * The file is opened for reading only and read a run of states at a time, whole states to a
 * thread. It prints how many values were compared, the largest difference and where it was,
 * and how many values failed, and exits with status 1 if any did. Run it after
 * ./run-tests.sh has compiled the sources, with the mongo jar on the class path for the
 * catalog: java -cp build/classes:lib/mongo-java-driver-3.12.14.jar PlayerTreeStoredCheck
 * [state file] [threads] [first state] [states]
 */
public final class PlayerTreeStoredCheck {

    private static final double EXACT = 1e-12;
    private static final int FEWEST_CARDS = 85;
    private static final int RUN = 700;
    private static final RoundRules RULES = StateRun.montreal();

    private PlayerTreeStoredCheck() {
    }

    /** What one thread found over the states it checked. */
    static final class Tally {
        long hits;
        long stands;
        long failed;
        int states;
        int fewestCards = Integer.MAX_VALUE;
        double largest;
        String where = "nowhere";
        final List<String> failures = new ArrayList<>();

        /** Compares one value; a NaN on either side alone is a failure. */
        void see(double stored, double v, String at) {
            double d = Math.abs(stored - v);
            if (!(d <= EXACT)) {
                fail(at + ": stored " + stored + ", tree " + v);
                return;
            }
            if (d > largest) {
                largest = d;
                where = at;
            }
        }

        void fail(String what) {
            failed++;
            if (failures.size() < 20) {
                failures.add(what);
            }
        }

        void add(Tally t) {
            hits += t.hits;
            stands += t.stands;
            failed += t.failed;
            states += t.states;
            fewestCards = Math.min(fewestCards, t.fewestCards);
            if (t.largest > largest) {
                largest = t.largest;
                where = t.where;
            }
            for (String f : t.failures) {
                if (failures.size() < 20) {
                    failures.add(f);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path data = Paths.get(args.length > 0 ? args[0] : "runs/montreal-80.states");
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 7;
        int records = StateStore.records(data);
        int from = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int count = args.length > 3 ? Integer.parseInt(args[3]) : records - from;
        CountKeys.Count[] counts = {named("Hi-Lo"), named("Red Seven (Red 7)"), named("KISS I"), named("BRH-I")};
        System.out.printf("PlayerTreeStoredCheck: states %d to %d of %d in %s, %d threads%n", from, from + count - 1,
                records, data, threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Tally all = new Tally();
        long start = System.nanoTime();
        int reported = 0;
        for (int at = from; at < from + count; at += RUN) {
            List<StateStore.Record> run = StateStore.read(data, at, Math.min(RUN, from + count - at));
            AtomicInteger next = new AtomicInteger();
            List<Future<Tally>> jobs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                jobs.add(pool.submit(() -> {
                    Tally mine = new Tally();
                    for (int s = next.getAndIncrement(); s < run.size(); s = next.getAndIncrement()) {
                        check(run.get(s), counts, mine);
                    }
                    return mine;
                }));
            }
            for (Future<Tally> f : jobs) {
                all.add(f.get());
            }
            if (all.states - reported >= 7000 || at + RUN >= from + count) {
                reported = all.states;
                System.out.printf("  %d states in %.0f s: %d Hit and %d Stand values, largest difference %.1e, "
                                + "%d failed%n", all.states, (System.nanoTime() - start) / 1e9, all.hits, all.stands,
                        all.largest, all.failed);
            }
        }
        pool.shutdown();

        System.out.printf("%d states, each holding %d cards or more; %d Hit values (every part, both walks, four "
                        + "counts) and %d Stand values compared; largest difference %.1e, at %s; %d failed%n",
                all.states, all.fewestCards, all.hits, all.stands, all.largest, all.where, all.failed);
        for (String f : all.failures) {
            System.out.println("  FAILED " + f);
        }
        if (all.failed > 0 || all.fewestCards < FEWEST_CARDS) {
            System.exit(1);
        }
    }

    /** Compares one state's tree under the chart with its stored values. */
    static void check(StateStore.Record r, CountKeys.Count[] counts, Tally t) {
        int[] shoe = r.ranksLeft();
        int cards = 0;
        for (int x : shoe) {
            cards += x;
        }
        t.fewestCards = Math.min(t.fewestCards, cards);
        t.states++;
        String at = "shoe " + r.shoe + " round " + r.round;
        if (cards < FEWEST_CARDS) {
            t.fail(at + " holds " + cards + " cards");
        }
        PlayerTree tree = new PlayerTree(new ExactRound(RULES), shoe);
        for (CountKeys.Count c : counts) {
            PlayerTree.Counted counted = tree.count(c, r.dealt);
            for (PlayerTree.Mode mode : PlayerTree.Mode.values()) {
                PlayerTree.Walk walk = counted.walk(PlayerTree.CHART, mode);
                for (int i = 0; i < Deals.COUNT; i++) {
                    double stored = r.values[StateValuer.slot(i, PlayerMove.Hit)];
                    if (Double.isNaN(stored) || counted.parts(i) == 0) {
                        if (!(Double.isNaN(stored) && counted.parts(i) == 0
                                && (Deals.playerNatural(i) || counted.chance[i] == 0))) {
                            t.fail(at + " " + c.name + " " + mode + " deal " + i + ": stored Hit " + stored + ", "
                                    + counted.parts(i) + " parts");
                        }
                        continue;
                    }
                    for (int q = 0; q < counted.parts(i); q++) {
                        t.see(stored, walk.hit(i, q), at + " " + c.name + " " + mode + " deal " + i + " part " + q);
                        t.hits++;
                    }
                }
            }
        }
        for (int i = 0; i < Deals.COUNT; i++) {
            double stored = r.values[StateValuer.slot(i, PlayerMove.Stand)];
            double v = tree.standValue(Deals.P1[i], Deals.P2[i], Deals.UP[i]);
            if (Double.isNaN(stored)) {
                if (Deals.playerNatural(i) && !Double.isNaN(v)) {
                    t.fail(at + " deal " + i + ": a natural's stand value is " + v);
                }
                continue;
            }
            t.see(stored, v / noNatural(shoe, i), at + " deal " + i + " Stand");
            t.stands++;
        }
    }

    /** The chance of no dealer natural once deal i is out of the shoe. */
    private static double noNatural(int[] shoe, int deal) {
        int[] after = Deals.after(shoe, deal);
        int n = 0;
        for (int r = 1; r <= 10; r++) {
            n += after[r];
        }
        int natural = ExactRound.naturalHoleRank(Deals.UP[deal]);
        long naturals = natural == 0 ? 0 : after[natural];
        return (n - naturals) / (double) n;
    }

    private static CountKeys.Count named(String system) {
        for (CountKeys.Count c : CountKeys.catalog()) {
            if (c.systems.contains(system)) {
                return c;
            }
        }
        throw new IllegalArgumentException(system);
    }
}
