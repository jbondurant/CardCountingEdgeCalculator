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
        System.out.printf("FullIndexPeek: %s, %d of %d counts scored, read on %s.%n", dir, sc.results.size(),
                counts.size(), java.time.LocalDateTime.now());
        FullIndexRun.summary(System.out, sc, null, pens[pens.length - 1]);
        System.out.printf("%nNot scored yet: %s.%n", waiting.isEmpty() ? "none" : String.join(", ", waiting));
    }
}
