import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The runs folder's budget (DiskBudget, 18 October 2026): the folder is the nearest named runs; it
 * holds the bytes of its regular files, a link's target not counted; a run that would take it past
 * the budget is refused, naming its largest items, the files inside a large folder among them; a
 * path outside any runs folder is never refused; and a budget is a number of GB, 0 or more.
 */
public class DiskBudgetTest {

    @Test
    public void aRunThatWouldPassTheBudgetIsRefusedNamingTheLargestItems(@TempDir Path dir) throws Exception {
        Path runs = dir.resolve("runs");
        Path deep = runs.resolve("full").resolve("run-a");
        Files.createDirectories(deep);
        Files.write(deep.resolve("results.ser"), new byte[3000]);
        Files.write(runs.resolve("small.txt"), new byte[10]);
        Path outside = dir.resolve("outside.bin");
        Files.write(outside, new byte[100_000]);
        Files.createSymbolicLink(runs.resolve("link.bin"), outside);

        assertEquals(runs, DiskBudget.root(deep.resolve("not-yet").resolve("x.ser")));
        assertNull(DiskBudget.root(outside));
        assertEquals(3010, DiskBudget.bytes(runs), "a link's target is not counted");
        assertEquals(10_000 - 3010, DiskBudget.room(deep, 10_000));
        assertEquals(Long.MAX_VALUE, DiskBudget.room(outside, 0));

        DiskBudget.require(deep, 6990, 10_000, "a run");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DiskBudget.require(deep, 6991, 10_000, "a run"));
        assertTrue(e.getMessage().startsWith("a run would write about"), e.getMessage());
        assertTrue(e.getMessage().contains("full"), e.getMessage());
        // Not counting the results, as a run about to cut its tree store does not count the store.
        DiskBudget.require(deep, 9990, 10_000, "a run", deep.resolve("results.ser"));
        DiskBudget.require(outside, Long.MAX_VALUE / 2, 0, "a run outside");

        assertTrue(DiskBudget.largest(runs, 1).contains("full"));
        assertFalse(DiskBudget.largest(runs, 1).contains("results.ser"), "a folder under OPEN is one item");
        assertEquals(5_000_000_000L, DiskBudget.budget("5"));
        assertEquals(0, DiskBudget.budget("0"));
        assertThrows(IllegalArgumentException.class, () -> DiskBudget.budget("-1"));
        assertThrows(IllegalArgumentException.class, () -> DiskBudget.budget("NaN"));
    }
}
