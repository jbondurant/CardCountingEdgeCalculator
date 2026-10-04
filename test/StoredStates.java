import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The run's state file, for the checks that hold the player tree to stored values. It lives in
 * runs/ under the main checkout, which git ignores, so a test run in a worktree finds it two
 * levels up; -Dstates=PATH names another. Tests that need it are skipped, and say so, where it
 * is not there. It is only ever read.
 */
final class StoredStates {

    private StoredStates() {
    }

    static Path file() {
        List<Path> tried = new ArrayList<>();
        String named = System.getProperty("states");
        if (named != null) {
            tried.add(Paths.get(named));
        }
        tried.add(Paths.get("runs", "montreal-80.states"));
        tried.add(Paths.get("..", "..", "runs", "montreal-80.states"));
        for (Path p : tried) {
            if (Files.isRegularFile(p) && Files.isRegularFile(StateStore.progressFile(p))) {
                return p;
            }
        }
        assumeTrue(false, "no state file at " + tried + "; these checks need the run's states");
        return null;
    }

    /** count records spread evenly through the file, the first and the last among them. */
    static List<StateStore.Record> spread(int count) throws IOException {
        Path data = file();
        int records = StateStore.records(data);
        List<StateStore.Record> out = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            int at = (int) ((long) k * (records - 1) / Math.max(1, count - 1));
            out.add(StateStore.read(data, at, 1).get(0));
        }
        return out;
    }
}
