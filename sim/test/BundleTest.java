package sim;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bundle's refusals that SHOE_SPEC.md's answers 10 and 11 added: a row with one fold file, a
 * strategy file without its exact.tsv lines, and groups that are not consecutive ranges.
 */
class BundleTest {

    @TempDir
    Path tmp;

    @Test
    void theFixtureBundleOpensAndResealingKeepsIt() throws IOException {
        Path dir = BundleFixture.write(tmp.resolve("b"));
        BundleFixture.reseal(dir);
        Bundle b = new Bundle(dir, Plants.NONE);
        assertEquals(List.of("strategy-hi-lo-hi-lo-lite-p80-f0.txt", "strategy-hi-lo-hi-lo-lite-p80-f1.txt"),
                b.strategyPaths());
        assertEquals(1, b.strategyAt("strategy-hi-lo-hi-lo-lite-p80-f1.txt").fold);
    }

    @Test
    void aRowWithOneFoldFileIsRefused() throws IOException {
        Path dir = BundleFixture.write(tmp.resolve("b"));
        Files.delete(dir.resolve("strategy-hi-lo-hi-lo-lite-p80-f1.txt"));
        BundleFixture.reseal(dir);
        IOException e = assertThrows(IOException.class, () -> new Bundle(dir, Plants.NONE));
        assertTrue(e.getMessage().contains("no fold 1 file"), e.getMessage());
    }

    @Test
    void aFileWithoutItsExactLinesIsRefused() throws IOException {
        Path dir = BundleFixture.write(tmp.resolve("b"));
        Path exact = dir.resolve("exact.tsv");
        List<String> kept = new ArrayList<>();
        for (String line : Files.readAllLines(exact)) {
            if (!line.startsWith(BundleFixture.HILO + "\t80\t0\tev:X-1,8")) {
                kept.add(line);
            }
        }
        BundleFixture.write(exact, kept);
        BundleFixture.reseal(dir);
        IOException e = assertThrows(IOException.class, () -> new Bundle(dir, Plants.NONE));
        assertTrue(e.getMessage().contains("no exact.tsv line for ev:X-1,8"), e.getMessage());
    }

    @Test
    void groupsMustBeConsecutiveRanges() throws IOException {
        StrategyFixture f = new StrategyFixture();
        f.groupHi = new int[]{-2, 3, 99};
        assertDoesNotThrow(() -> f.read());
        // Group 1's lo at group 0's hi: the ranges overlap, though each hi still increases.
        List<String> lines = new ArrayList<>(f.lines());
        lines.replaceAll(l -> l.equals("group\t1\t-1\t3") ? "group\t1\t-2\t3" : l);
        IOException e = assertThrows(IOException.class, () -> Strategy.parse(lines, "overlap", Plants.NONE));
        assertTrue(e.getMessage().contains("lo above the last hi"), e.getMessage());
        // lgroup lines in a file of scope first.
        List<String> stray = new ArrayList<>(f.lines());
        stray.add(stray.indexOf("unseen\tnearest\tlower"), "lgroup\t0\t-40\t99");
        assertThrows(IOException.class, () -> Strategy.parse(stray, "stray", Plants.NONE));
    }
}
