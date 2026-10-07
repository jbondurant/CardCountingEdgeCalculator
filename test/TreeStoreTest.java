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

    /**
     * A store opened read-only, as a run of other classes reads an earlier run's trees
     * (COUNT_DESIGN.md, section 7): keyed on the states alone, whatever commit wrote it, it gives
     * the trees it holds to the bit, builds the ones it does not without keeping them, and
     * writes nothing; a store of other states, or none, is refused and left exactly as it was.
     */
    @Test
    public void aStoreOpenedReadOnlyIsReadAndNeverWritten() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        ScoringStates st = fx.st;
        Path dir = Files.createTempDirectory("trees-read-only");
        Path file = dir.resolve("trees.bin");
        Path index = dir.resolve("trees.bin.index");
        ExactRound engine = new ExactRound(RULES);
        String states = "states /some/file, 123 bytes, modified 456\nstates " + st.size;
        try (TreeStore store = new TreeStore(st, file, "c06c061\n" + states, 0)) {
            for (int s = 0; s < st.size; s += 2) {
                store.of(s, engine);
            }
        }
        byte[] trees = Files.readAllBytes(file);
        byte[] kept = Files.readAllBytes(index);
        long modified = Files.getLastModifiedTime(file).toMillis();
        assertEquals("c06c061\n" + states, TreeStore.identityOf(file));

        try (TreeStore store = TreeStore.readOnly(st, file, states)) {
            assertTrue(store.readOnly);
            assertEquals((st.size + 1) / 2, store.holds());
            for (int s = 0; s < st.size; s++) {
                PlayerTree built = new PlayerTree(engine, ShoeRun.ranksLeft(st.dealt[s]));
                PlayerTree read = store.of(s, engine);
                for (int u = 1; u <= 10; u++) {
                    double[] a = built.standRow(u);
                    double[] b = read.standRow(u);
                    for (int h = 0; h < a.length; h++) {
                        assertEquals(Double.doubleToRawLongBits(a[h]), Double.doubleToRawLongBits(b[h]));
                    }
                }
            }
            assertEquals((st.size + 1) / 2, store.read());
            assertEquals(st.size / 2, store.built());
            assertEquals((st.size + 1) / 2, store.holds(), "a tree built read-only is not kept");
            assertThrows(IllegalStateException.class, store::sync);
        }
        assertArrayEquals(trees, Files.readAllBytes(file), "nothing written to the trees");
        assertArrayEquals(kept, Files.readAllBytes(index), "nothing written to the index");
        assertEquals(modified, Files.getLastModifiedTime(file).toMillis());

        // Other states, another number of them, or no store: refused, and the store left as it is.
        assertThrows(java.io.IOException.class, () -> TreeStore.readOnly(st, file, "states /other, 1 bytes, modified 2\nstates "
                + st.size));
        assertThrows(java.io.IOException.class, () -> TreeStore.readOnly(st, file, "c06c061\n" + states));
        ScoringStates fewer = fx.st.subRun(0, 2);
        assertThrows(java.io.IOException.class, () -> TreeStore.readOnly(fewer, file, states.replace("states " + st.size,
                "states " + fewer.size)));
        assertThrows(java.io.IOException.class, () -> TreeStore.readOnly(st, dir.resolve("none.bin"), states));
        assertFalse(Files.exists(dir.resolve("none.bin")));
        assertArrayEquals(trees, Files.readAllBytes(file));
        assertArrayEquals(kept, Files.readAllBytes(index));
    }

    /**
     * Only a store is ever replaced: a file at the store's path without an index beside it (a
     * state file, a run's results), a store's index given as the store, or a directory, is refused
     * and left exactly as it was; and a new store writes its index before any tree, so a store's
     * file never stands without one.
     */
    @Test
    public void aFileThatIsNotAStoreIsNeverRemoved() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        ScoringStates st = fx.st;
        Path dir = Files.createTempDirectory("not-a-store");
        Path precious = dir.resolve("precious.states");
        byte[] bytes = "a state file, or results.ser".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(precious, bytes);
        java.io.IOException e = assertThrows(java.io.IOException.class, () -> new TreeStore(st, precious, "x", 0));
        assertTrue(e.getMessage().contains("not a tree store"), e.getMessage());
        assertArrayEquals(bytes, Files.readAllBytes(precious));
        assertFalse(Files.exists(dir.resolve("precious.states.index")));

        Path file = dir.resolve("trees.bin");
        Path index = dir.resolve("trees.bin.index");
        try (TreeStore store = new TreeStore(st, file, "kept", 0)) {
            assertEquals("kept", TreeStore.identityOf(file), "the index is written before any tree");
            store.of(0, new ExactRound(RULES));
        }
        byte[] kept = Files.readAllBytes(file);
        byte[] keptIndex = Files.readAllBytes(index);
        // The store's index given as the store: refused, the store and its index left as they are.
        assertThrows(java.io.IOException.class, () -> new TreeStore(st, index, "x", 0));
        assertArrayEquals(keptIndex, Files.readAllBytes(index));
        assertArrayEquals(kept, Files.readAllBytes(file));
        // A directory, and an index beside a path that does not read as one.
        Files.createDirectories(dir.resolve("adir"));
        assertThrows(java.io.IOException.class, () -> new TreeStore(st, dir.resolve("adir"), "x", 0));
        Path odd = dir.resolve("odd.bin");
        Files.write(dir.resolve("odd.bin.index"), bytes);
        assertThrows(java.io.IOException.class, () -> new TreeStore(st, odd, "x", 0));
        assertArrayEquals(bytes, Files.readAllBytes(dir.resolve("odd.bin.index")));
        // A store of another identity is a store, and starts afresh as before.
        try (TreeStore store = new TreeStore(st, file, "another", 0)) {
            assertEquals(0, store.holds());
        }
    }
}
