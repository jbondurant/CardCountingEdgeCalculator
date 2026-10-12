import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The bound rows of COUNT_DESIGN.md section 2, on the twin: what perfect information earns when
 * it bets the same schedule as every count, each state bet in order of its own value. No count,
 * with any number of side counts, can beat the first row at first decisions or the second
 * betting only. Printed for every penetration, flat and at every spread of the same schedule, in
 * % of a unit per round, beside the schedule's average bet.
 *
 * The third row, perfect play of the dealt hand, needs each state's full-play value, which a
 * full-index run keeps in its results.ser (FullIndexFit.perfect). Given --perfect RESULTS, the
 * row is scored from that array, and every full-play row that run scored is printed beside it,
 * so the bound and the counts come from the same shoes (PLAN_COMPUTE.md 4.3, the preview on the
 * twin); the figures are then printed to four places, as the run's own report gives them.
 * --shoes N reads the first N shoes alone, to match a run made on part of the twin. The results
 * must be of these states (the same file, shoes and valued states), and the array must hold a
 * value for every state of every penetration (CeilingReport.requireCompletePerfect): an array
 * lacking a state would drop the bound there without a word, so it is refused.
 *
 * Point estimates only, no resamples: a preview that sizes the room above the counts, not a
 * figure for the page. Fitted on nothing, so it reads the twin only, to keep the main file to its
 * one look (section 5):
 *
 *   java -cp CLASSES:lib/mongo-java-driver-3.12.14.jar BoundRows runs/twin-80.states
 *       [--perfect runs/custom-count/fullplay/choice-20000-38f4e60/results.ser --shoes 20000]
 */
final class BoundRows {

    private BoundRows() {
    }

    public static void main(String[] args) throws Exception {
        run(args, System.out);
    }

    static void run(String[] args, PrintStream out) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path states = null;
        Path perfectFile = null;
        int shoes = -1;
        for (int a = 0; a < args.length; a++) {
            switch (args[a]) {
                case "--perfect":
                    perfectFile = Paths.get(args[++a]);
                    break;
                case "--shoes":
                    shoes = Integer.parseInt(args[++a]);
                    break;
                default:
                    if (args[a].startsWith("--") || states != null) {
                        throw new IllegalArgumentException("usage: BoundRows TWIN_STATES [--perfect RESULTS] [--shoes N]; "
                                + "not " + args[a]);
                    }
                    states = Paths.get(args[a]);
            }
        }
        if (states == null) {
            throw new IllegalArgumentException("usage: BoundRows TWIN_STATES [--perfect RESULTS] [--shoes N]");
        }
        CustomCounts.requireTwin(states);
        long t0 = System.nanoTime();
        int done = StateStore.shoesDone(states);
        if (shoes > done || shoes == 0 || shoes < -1) {
            throw new IllegalArgumentException(states + " holds " + done + " shoes; --shoes " + shoes + " is not 1 to that");
        }
        ScoringStates st = FullIndexPilot.load(states, shoes < 0 ? done : shoes);
        SystemScorer scorer = new SystemScorer(st, new Bootstrap(st.shoes, 0, Bootstrap.SEED), SystemScorer.M_PLAY);
        CeilingReport.Published p = null;
        if (perfectFile != null) {
            p = CeilingReport.read(perfectFile);
            requireSameStates(p, states, st);
            CeilingReport.requireCompletePerfect(p.perfect, scorer, perfectFile.toString());
        }
        List<SystemScorer.Entry> rows = scorer.bounds(p == null ? null : p.perfect);
        out.printf("BoundRows: %s, %d valued states of %d shoes%s, read and scored in %.0f s.%n", states, st.size,
                st.shoes, st.shoes < done ? " (the first " + st.shoes + " of " + done + ")" : "",
                (System.nanoTime() - t0) / 1e9);
        if (p != null) {
            out.printf("The full-play bound from the perfect array of %s, and that run's full-play rows beside it, on "
                    + "the same shoes. Point estimates, no resamples: a preview, not a figure for the page.%n", perfectFile);
        }
        print(rows, p == null ? new ArrayList<>() : fullPlayRows(p), p == null ? 3 : 4, out);
    }

    /**
     * Refuses a results file of other states than these: another state file, or another number of
     * shoes or valued states. Its resamples do not matter here, since the perfect array does not
     * depend on them.
     */
    static void requireSameStates(CeilingReport.Published p, Path states, ScoringStates st) throws java.io.IOException {
        List<String> wrong = new ArrayList<>();
        String file = FullIndexRun.stateFile(states);
        if (!p.identity.contains("\n" + file + "\n")) {
            wrong.add("its state file is not " + file);
        }
        String shoes = p.line("shoes ");
        if (!(st.shoes + ", valued states " + st.size).equals(shoes)) {
            wrong.add("it has shoes " + shoes + ", not " + st.shoes + ", valued states " + st.size);
        }
        if (!wrong.isEmpty()) {
            throw new IllegalStateException("the results are not of these states: " + String.join("; ", wrong));
        }
    }

    /** Every full-play row a run scored that plays its own fit, each count's and its variant's, in the run's order. */
    static List<SystemScorer.Entry> fullPlayRows(CeilingReport.Published p) {
        List<SystemScorer.Entry> out = new ArrayList<>();
        for (SystemScorer.Result r : p.results) {
            for (SystemScorer.Entry e : r.fullEntries) {
                if (!e.bettingOnly) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /**
     * The bound rows and then the other rows given, by penetration, to the places given; a row not
     * scored at a penetration is left out there.
     */
    static void print(List<SystemScorer.Entry> bounds, List<SystemScorer.Entry> fullPlay, int places, PrintStream out) {
        String f = "%+." + places + "f";
        for (int p = 0; p < SystemScorer.CUTS.length; p++) {
            out.printf("%n%s penetration, %% of a unit per round:%n%n", SystemScorer.PENETRATIONS[p]);
            StringBuilder head = new StringBuilder("| Row | Flat | Play | Insurance |");
            StringBuilder rule = new StringBuilder("|---|---|---|---|");
            for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                head.append(" 1-").append(s).append(" |");
                rule.append("---|");
            }
            out.println(head);
            out.println(rule);
            List<SystemScorer.Entry> rows = new ArrayList<>(bounds);
            rows.addAll(fullPlay);
            for (SystemScorer.Entry e : rows) {
                double[] m = e.estimate[p];
                if (m == null) {
                    continue;
                }
                StringBuilder line = new StringBuilder("| " + e.name + " | ");
                line.append(String.format(f + " | " + f + " | " + f + " |", 100 * m[SystemScorer.FLAT],
                        100 * m[SystemScorer.PLAY], 100 * m[SystemScorer.INSURANCE]));
                for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                    line.append(String.format(" " + f + " |", 100 * m[SystemScorer.same(s)]));
                }
                out.println(line);
            }
            StringBuilder bet = new StringBuilder("| Average bet of the schedule, units | | | |");
            double[] m = bounds.get(0).estimate[p];
            for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                bet.append(String.format(" %.3f |", m[SystemScorer.sameBet(s)]));
            }
            out.println(bet);
        }
    }
}
