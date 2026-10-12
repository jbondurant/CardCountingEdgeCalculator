import java.io.BufferedInputStream;
import java.io.ObjectInputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * What a FullIndexRun still scoring has found so far: every count whose scoring items at every
 * penetration are in the run's checkpoint directory, put together as the run puts them
 * (SystemScorer.merge), and printed as the run's own summary will print them when it ends
 * (FullIndexRun.summary). A count's Result is final once its last item is written, so these
 * are the figures the run will report for it.
 *
 * It only reads the directory. The identity it checks every part against is the run's own,
 * from its run.id, so a part another run wrote is refused. Run it on the classes the run was
 * started from, so the parts it reads are read by the code that wrote them:
 *
 *   java -cp classes-COMMIT:lib/mongo-java-driver-3.12.14.jar FullIndexPeek runs/full-index/full-COMMIT
 */
final class FullIndexPeek {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: FullIndexPeek CHECKPOINT_DIRECTORY");
        }
        Path dir = Paths.get(args[0]);
        String identity = new String(Files.readAllBytes(dir.resolve("run.id")), StandardCharsets.UTF_8);
        FullIndexCheckpoint checkpoint = new FullIndexCheckpoint(dir, identity);
        List<String> counts = new ArrayList<>();
        int[] pens = FullIndexRun.PENS;
        for (String line : identity.split("\n")) {
            if (line.startsWith("count ")) {
                // A run since the ceiling count's step 1 describes each count after its name.
                String c = line.substring("count ".length());
                int colon = c.indexOf(": ");
                counts.add(colon < 0 ? c : c.substring(0, colon));
            } else if (line.startsWith("penetrations [")) {
                pens = FullIndexRun.pens(line.substring("penetrations [".length(), line.length() - 1));
            }
        }
        FullIndexFit.Scored sc = new FullIndexFit.Scored();
        List<String> waiting = new ArrayList<>();
        int items = 2 * pens.length;
        Path finished = dir.resolve("results.ser");
        if (Files.exists(finished)) {
            // The run has ended: its results, as it wrote them.
            Stored stored = read(finished);
            if (!stored.identity.equals(identity)) {
                throw new IllegalStateException(finished + " was written by another run than " + dir.resolve("run.id"));
            }
            sc.results.addAll(stored.results);
            counts.clear();
        }
        for (int k = 0; k < counts.size(); k++) {
            // The parts in the order the run puts them together: each penetration's fold fits,
            // then its half fits (FullIndexFit.JointRun).
            List<SystemScorer.Result> parts = new ArrayList<>();
            for (int p : pens) {
                for (String part : new String[]{"folds", "halves"}) {
                    SystemScorer.Result r = checkpoint.readPart("part-" + k + "-" + p + "-" + part);
                    if (r != null) {
                        parts.add(r);
                    }
                }
            }
            if (parts.size() == items) {
                sc.results.add(SystemScorer.merge(parts));
            } else {
                waiting.add(counts.get(k) + (parts.isEmpty() ? "" : " (" + parts.size() + " of " + items + " items)"));
            }
        }
        System.out.printf("FullIndexPeek: %s, %d counts scored%s, read on %s.%n", dir, sc.results.size(),
                Files.exists(finished) ? ", the run finished (results.ser)" : " of " + counts.size(),
                java.time.LocalDateTime.now());
        FullIndexRun.summary(System.out, sc, null, pens[pens.length - 1]);
        System.out.printf("%nNot scored yet: %s.%n", waiting.isEmpty() ? "none" : String.join(", ", waiting));
        ranked(System.out, sc.results, pens[pens.length - 1]);
    }

    /** A finished run's results.ser: the run's identity and every count's Result. */
    static final class Stored {
        final String identity;
        final List<SystemScorer.Result> results;

        Stored(String identity, List<SystemScorer.Result> results) {
            this.identity = identity;
            this.results = results;
        }
    }

    /**
     * Reads the identity and the Results at the head of a results.ser as FullIndexRun writes it,
     * and nothing after them; it only reads. Run it on the classes the file was written with.
     * RiskReport reads the engine's win rates through it.
     */
    static Stored read(Path file) throws java.io.IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 20))) {
            String identity = in.readUTF();
            @SuppressWarnings("unchecked")
            List<SystemScorer.Result> results = (List<SystemScorer.Result>) in.readObject();
            return new Stored(identity, results);
        }
    }

    /**
     * Every row that plays, each count and its ace side-count variant, at penetration p, ranked
     * by its full-play EV: its EV with first decisions only and with full play, the gain, and its
     * full play's difference from Hi-Lo's full play. Every row is scored on the same resamples,
     * so the SEs are of paired differences; the later plays are held and the SEs not yet widened
     * (FULL_INDEX_PLAYS.md section 4).
     */
    static void ranked(PrintStream out, List<SystemScorer.Result> results, int p) {
        List<SystemScorer.Entry[]> rows = new ArrayList<>();
        // The side-count variants: their side count keys the bets alone, so flat they play as their count.
        java.util.Set<SystemScorer.Entry> variants = new java.util.HashSet<>();
        SystemScorer.Entry hiLo = null;
        for (SystemScorer.Result r : results) {
            for (SystemScorer.Entry full : r.fullEntries) {
                // A row bet on its own value is a bound, its bets set by what each state is worth
                // under its play (COUNT_DESIGN.md section 2): not a count, so not ranked here.
                if (full.ownValue) {
                    continue;
                }
                SystemScorer.Entry first = null;
                for (SystemScorer.Entry e : r.entries) {
                    if (!e.bettingOnly && !e.ownValue && e.count == full.count) {
                        first = e;
                    }
                }
                rows.add(new SystemScorer.Entry[]{first, full});
                if (full != r.fullEntries.get(0)) {
                    variants.add(full);
                }
                if (r.count.name.equals("Hi-Lo = Hi-Lo Lite") && full == r.fullEntries.get(0)) {
                    hiLo = full;
                }
            }
        }
        int[] metrics = {SystemScorer.same(SystemScorer.SPREADS), SystemScorer.FLAT};
        String[] names = {"1-8 same schedule", "flat"};
        for (int k = 0; k < metrics.length; k++) {
            int m = metrics[k];
            List<SystemScorer.Entry[]> sorted = new ArrayList<>(rows);
            sorted.sort((a, b) -> Double.compare(b[1].estimate[p][m], a[1].estimate[p][m]));
            out.printf("%nEvery row that plays, %s at %s, ranked by full play; %% of a unit per round; SEs of the "
                    + "paired differences, later plays held, not yet widened:%n%n", names[k], new String[]{"70%", "75%",
                    "80%"}[p]);
            if (m == SystemScorer.FLAT) {
                sorted.removeIf(row -> variants.contains(row[1]));
                out.println("The side-count variants are left out: flat, they play as their counts.");
                out.println();
            }
            out.println("| Row | First decisions | Full play | Gain (SE) | Full play less Hi-Lo's (SE) |");
            out.println("|---|---|---|---|---|");
            for (SystemScorer.Entry[] row : sorted) {
                Bootstrap.Interval g = SystemScorer.gain(row[1], row[0], p, m, 1);
                // A run of candidates alone has no Hi-Lo to set them beside.
                String d = "no Hi-Lo in this run";
                if (hiLo != null) {
                    Bootstrap.Interval i = SystemScorer.difference(row[1], hiLo, p, m, 1);
                    d = String.format("%+.4f (%.4f)", 100 * i.estimate, 100 * i.se);
                }
                out.printf("| %s | %+.4f | %+.4f | %+.4f (%.4f) | %s |%n", row[0].name,
                        100 * row[0].estimate[p][m], 100 * row[1].estimate[p][m], 100 * g.estimate, 100 * g.se, d);
            }
        }
    }
}
