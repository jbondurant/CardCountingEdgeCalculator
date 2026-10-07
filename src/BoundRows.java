import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * The bound rows of COUNT_DESIGN.md section 2, on the twin: what perfect information earns when
 * it bets the same schedule as every count, each state bet in order of its own value. No count,
 * with any number of side counts, can beat the first row at first decisions or the second
 * betting only; the third, perfect play of the whole dealt hand, needs each state's full-play
 * value and is not given here. Printed for every penetration, flat and at every spread of the
 * same schedule, in % of a unit per round, beside the schedule's average bet.
 *
 * Fitted on nothing, so it reads the twin only to keep the main file to its one look (section 5):
 *
 *   java -cp CLASSES:lib/mongo-java-driver-3.12.14.jar BoundRows runs/twin-80.states
 */
final class BoundRows {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: BoundRows TWIN_STATES");
        }
        Path states = Paths.get(args[0]);
        CustomCounts.requireTwin(states);
        long t0 = System.nanoTime();
        ScoringStates st = FullIndexPilot.load(states, StateStore.shoesDone(states));
        SystemScorer scorer = new SystemScorer(st, new Bootstrap(st.shoes, 0, Bootstrap.SEED), SystemScorer.M_PLAY);
        List<SystemScorer.Entry> rows = scorer.bounds(null);
        System.out.printf("BoundRows: %s, %d valued states of %d shoes, read and scored in %.0f s.%n", states, st.size,
                st.shoes, (System.nanoTime() - t0) / 1e9);
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            System.out.printf("%n%s penetration, %% of a unit per round:%n%n", SystemScorer.PENETRATIONS[p]);
            StringBuilder head = new StringBuilder("| Row | Flat | Play | Insurance |");
            StringBuilder rule = new StringBuilder("|---|---|---|---|");
            for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                head.append(" 1-").append(s).append(" |");
                rule.append("---|");
            }
            System.out.println(head);
            System.out.println(rule);
            for (SystemScorer.Entry e : rows) {
                double[] m = e.estimate[p];
                StringBuilder line = new StringBuilder("| " + e.name + " | ");
                line.append(String.format("%+.3f | %+.3f | %+.3f |", 100 * m[SystemScorer.FLAT],
                        100 * m[SystemScorer.PLAY], 100 * m[SystemScorer.INSURANCE]));
                for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                    line.append(String.format(" %+.3f |", 100 * m[SystemScorer.same(s)]));
                }
                System.out.println(line);
            }
            StringBuilder bet = new StringBuilder("| Average bet of the schedule, units | | | |");
            double[] m = rows.get(0).estimate[p];
            for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                bet.append(String.format(" %.3f |", m[SystemScorer.sameBet(s)]));
            }
            System.out.println(bet);
        }
    }
}
