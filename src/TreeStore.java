import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.BitSet;

/**
 * Every state's player tree, built once and kept on disk, so that each later round of a run
 * reads it back instead of building it again (FULL_INDEX_PLAYS.md section 6).
 *
 * A tree takes about 0.17 s of CPU to build and holds 245 KB: its stand values, a row of 3,061
 * doubles for each up-card. The whole file's 60,743 states hold 14.9 GB, too much for the heap
 * beside everything else a run holds, and building them again in every round and every
 * scoring batch costs half an hour of the wall a time. Read back from disk they take seconds.
 *
 * The file holds a fixed-size record per state, at the state's place: the state's index and
 * shoe, then the rows, each double as its raw bits, so a tree read back is the tree that was
 * built, bit for bit (TreeStoreTest). Which states are in it is kept apart, in a small file
 * written only after the trees in it are forced to disk (sync), so after a crash a state
 * whose record may be partial is not marked and is built again. A record carries its state's
 * index and shoe, checked as it is read, and the index file an identity (the run's commit,
 * state file and states): a store opened with another identity starts afresh.
 *
 * Several threads may read and write at once, each its own states: positional reads and writes
 * of a FileChannel do not interfere.
 */
final class TreeStore implements FullIndexFit.Trees, AutoCloseable {

    private static final int HANDS = PlayerTree.Hands.ALL.size;
    /** The state's index and shoe, as ints, then ten rows of doubles. */
    private static final int HEADER = 4 * 12;
    static final long RECORD = HEADER + 8L * 10 * HANDS;

    private final ScoringStates st;
    private final Path file;
    private final Path index;
    private final String identity;
    private final FileChannel channel;
    private final BitSet held;
    private final ThreadLocal<ByteBuffer> buffers = ThreadLocal.withInitial(() -> ByteBuffer.allocate((int) RECORD));
    /** Trees built (and written) and read back since the store was opened. */
    private final java.util.concurrent.atomic.AtomicLong built = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong read = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Opens the store at file (and file + ".index"), keeping what it holds if its identity is
     * the one given, starting afresh otherwise. Refuses when the disk would be left with less
     * than margin bytes once every state's tree is written.
     */
    TreeStore(ScoringStates st, Path file, String identity, long margin) throws IOException {
        this.st = st;
        this.file = file;
        this.index = file.resolveSibling(file.getFileName() + ".index");
        this.identity = identity;
        BitSet kept = readIndex(index, identity, st.size);
        if (kept == null) {
            Files.deleteIfExists(index);
            Files.deleteIfExists(file);
            kept = new BitSet(st.size);
        }
        held = kept;
        long need = RECORD * st.size - (Files.exists(file) ? Files.size(file) : 0);
        long free = Files.getFileStore(file.toAbsolutePath().getParent()).getUsableSpace();
        if (free - need < margin) {
            throw new IOException(String.format("the trees of %d states need %.1f GB more on disk; %.1f GB is free, and "
                    + "%.1f GB must be left", st.size, need / 1e9, free / 1e9, margin / 1e9));
        }
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    /** The bytes every state's tree takes on disk. */
    static long bytes(int states) {
        return RECORD * states;
    }

    @Override
    public PlayerTree of(int s, ExactRound engine) {
        try {
            if (held(s)) {
                read.incrementAndGet();
                return readTree(s);
            }
            PlayerTree tree = new PlayerTree(engine, ShoeRun.ranksLeft(st.dealt[s]));
            writeTree(s, tree);
            synchronized (held) {
                held.set(s);
            }
            built.incrementAndGet();
            return tree;
        } catch (IOException e) {
            throw new UncheckedIOException("the tree store " + file, e);
        }
    }

    private boolean held(int s) {
        synchronized (held) {
            return held.get(s);
        }
    }

    long built() {
        return built.get();
    }

    long read() {
        return read.get();
    }

    /** States whose trees the store holds. */
    int holds() {
        synchronized (held) {
            return held.cardinality();
        }
    }

    private void writeTree(int s, PlayerTree tree) throws IOException {
        ByteBuffer b = buffers.get();
        b.clear();
        b.putInt(s);
        for (int r = 0; r <= 10; r++) {
            b.putInt(tree.shoe[r]);
        }
        for (int u = 1; u <= 10; u++) {
            for (double v : tree.standRow(u)) {
                b.putDouble(v);
            }
        }
        b.flip();
        long at = RECORD * s;
        while (b.hasRemaining()) {
            at += channel.write(b, at);
        }
    }

    private PlayerTree readTree(int s) throws IOException {
        ByteBuffer b = buffers.get();
        b.clear();
        long at = RECORD * s;
        while (b.hasRemaining()) {
            int n = channel.read(b, at);
            if (n < 0) {
                throw new IOException("state " + s + "'s tree is cut short");
            }
            at += n;
        }
        b.flip();
        if (b.getInt() != s) {
            throw new IOException("the record at state " + s + " is another state's");
        }
        int[] shoe = new int[11];
        for (int r = 0; r <= 10; r++) {
            shoe[r] = b.getInt();
        }
        if (!Arrays.equals(shoe, ShoeRun.ranksLeft(st.dealt[s]))) {
            throw new IOException("state " + s + "'s tree is of another shoe");
        }
        double[][] stand = new double[11][];
        for (int u = 1; u <= 10; u++) {
            double[] row = new double[HANDS];
            for (int h = 0; h < HANDS; h++) {
                row[h] = b.getDouble();
            }
            stand[u] = row;
        }
        return new PlayerTree(shoe, stand);
    }

    /**
     * Forces every tree written to disk, then records which states the store holds, replacing
     * the record before in one move, so a crash leaves the old record or the new one.
     */
    void sync() throws IOException {
        channel.force(true);
        BitSet copy;
        synchronized (held) {
            copy = (BitSet) held.clone();
        }
        Path tmp = index.resolveSibling(index.getFileName() + ".tmp");
        byte[] id = identity.getBytes(StandardCharsets.UTF_8);
        byte[] bits = copy.toByteArray();
        ByteBuffer b = ByteBuffer.allocate(4 + id.length + 4 + 4 + bits.length);
        b.putInt(id.length).put(id).putInt(st.size).putInt(bits.length).put(bits);
        Files.write(tmp, b.array());
        try (FileChannel c = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            c.force(true);
        }
        Files.move(tmp, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The states an index records, or null if there is none or it is of another identity or size. */
    private static BitSet readIndex(Path index, String identity, int size) throws IOException {
        if (!Files.exists(index)) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(index));
        byte[] id = new byte[b.getInt()];
        b.get(id);
        if (!new String(id, StandardCharsets.UTF_8).equals(identity) || b.getInt() != size) {
            return null;
        }
        byte[] bits = new byte[b.getInt()];
        b.get(bits);
        return BitSet.valueOf(bits);
    }

    @Override
    public void close() throws IOException {
        sync();
        channel.close();
    }
}
