import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trees kept on disk (TreeStore) are the trees that were built, bit for bit, and a store
 * reopened after a crash holds only the trees it had forced to disk.
 */
public class TreeStoreTest {

    private static final RoundRules RULES = StateRun.montreal();

    /**
     * On FullIndexFitTest's hand-made states: every tree written and read back has every stand
     * value of the tree built, to the bit, and its walks under a count-based later play give
     * every part's Hit value to the bit, for Hi-Lo and Red Seven. Reopened with the same identity
     * the store reads every tree and builds none; with another, it starts afresh; and trees
     * written after the last sync are built again after a reopening, as after a crash.
     */
    @Test
    public void aTreeReadBackIsTheTreeBuilt() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        ScoringStates st = fx.st;
        Path dir = Files.createTempDirectory("trees");
        Path file = dir.resolve("trees.bin");
        ExactRound engine = new ExactRound(RULES);
        try (TreeStore store = new TreeStore(st, file, "test 1", 0)) {
            for (int s = 0; s < st.size; s++) {
                store.of(s, engine);
            }
            assertEquals(st.size, store.built());
            assertEquals(0, store.read());
        }
        assertEquals(TreeStore.bytes(st.size), Files.size(file));
        PlayerTree.LaterPlay play = (total, soft, up, key) -> total >= 13 + Math.floorMod(key + up, 4);
        try (TreeStore store = new TreeStore(st, file, "test 1", 0)) {
            assertEquals(st.size, store.holds());
            for (int s = 0; s < st.size; s++) {
                PlayerTree built = new PlayerTree(engine, ShoeRun.ranksLeft(st.dealt[s]));
                PlayerTree read = store.of(s, engine);
                for (int u = 1; u <= 10; u++) {
                    double[] a = built.standRow(u);
                    double[] b = read.standRow(u);
                    for (int h = 0; h < a.length; h++) {
                        assertEquals(Double.doubleToRawLongBits(a[h]), Double.doubleToRawLongBits(b[h]),
                                "state " + s + " up " + u + " hand " + h);
                    }
                }
                for (CountKeys.Count c : new CountKeys.Count[]{FullIndexFitTest.HI_LO, FullIndexFitTest.RED_SEVEN}) {
                    double[] x = built.count(c, st.dealt[s]).walk(play, PlayerTree.Mode.CONTESTED).partHit;
                    double[] y = read.count(c, st.dealt[s]).walk(play, PlayerTree.Mode.CONTESTED).partHit;
                    assertEquals(x.length, y.length);
                    for (int q = 0; q < x.length; q++) {
                        assertEquals(Double.doubleToRawLongBits(x[q]), Double.doubleToRawLongBits(y[q]));
                    }
                }
            }
            assertEquals(0, store.built());
            assertEquals(st.size, store.read());
        }
        try (TreeStore store = new TreeStore(st, file, "test 2", 0)) {
            assertEquals(0, store.holds(), "another identity starts afresh");
        }
        Path index = dir.resolve("trees.bin.index");
        byte[] beforeTwo;
        try (TreeStore store = new TreeStore(st, file, "test 3", 0)) {
            store.of(0, engine);
            store.sync();
            beforeTwo = Files.readAllBytes(index);
            store.of(1, engine);
        }
        // Put back the index as it stood before state 1 was written, as a crash before the next sync leaves it.
        Files.write(index, beforeTwo);
        try (TreeStore store = new TreeStore(st, file, "test 3", 0)) {
            assertEquals(1, store.holds());
            store.of(1, engine);
            assertEquals(1, store.built(), "a tree written after the last sync is built again");
        }
        assertThrows(java.io.IOException.class, () -> new TreeStore(st, dir.resolve("other.bin"), "x", Long.MAX_VALUE / 2));
    }
}
