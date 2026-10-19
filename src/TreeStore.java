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
 * Only a store is ever replaced: a file at the store's path without an index beside it, which no
 * store this class makes can be (a new store writes its index before its file), is refused and
 * left as it is, as is anything there that is not a regular file or an index that does not read
 * as one (refuseNonStore). So a mistyped path, to a state file or a run's results, is never
 * deleted.
 *
 * Opened read-only (readOnly), a store is keyed on its state file and states alone, not the
 * commit, so a run built from other classes can read the trees an earlier run kept. It is then
 * only read: a store of other states is refused, never deleted, a tree it does not hold is built
 * and not written, and nothing is written to the file or its index (TreeStoreTest).
 *
 * A store may keep only the first states' trees (keep, the states numbered below it), when the
 * runs folder's budget leaves room for no more (DiskBudget): the others are built each time they
 * are asked for and never written, as a read-only store builds what it does not hold, and the file
 * holds keep records at most. Opened again with a smaller keep, the store lets the states beyond it
 * go: their marks first, then the file cut to keep records.
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
    /** Whether the store is only read: nothing is written, and a tree it does not hold is built and not kept. */
    final boolean readOnly;
    /** The states whose trees are kept, those numbered below it; the others are built and not written. */
    final int keep;

    /**
     * Opens the store at file (and file + ".index"), keeping what it holds if its identity is
     * the one given, starting afresh otherwise. Refuses when the disk would be left with less
     * than margin bytes once every state's tree is written.
     */
    TreeStore(ScoringStates st, Path file, String identity, long margin) throws IOException {
        this(st, file, identity, margin, st.size);
    }

    /** The same, keeping only the trees of the states numbered below keep. */
    TreeStore(ScoringStates st, Path file, String identity, long margin, int keep) throws IOException {
        if (keep < 0 || keep > st.size) {
            throw new IllegalArgumentException("a store keeps 0 to " + st.size + " states' trees, not " + keep);
        }
        this.st = st;
        this.file = file;
        this.index = indexOf(file);
        this.identity = identity;
        this.readOnly = false;
        this.keep = keep;
        refuseNonStore(file);
        BitSet kept = readIndex(index, identity, st.size);
        boolean fresh = kept == null;
        if (fresh) {
            // A store, its index beside it, of another identity or size.
            Files.deleteIfExists(index);
            Files.deleteIfExists(file);
            kept = new BitSet(st.size);
        }
        held = kept;
        if (!fresh && Files.exists(file) && Files.size(file) > RECORD * keep) {
            // Kept with more room before: the marks beyond keep go first, so a crash between the
            // two leaves no state marked whose record is gone.
            held.clear(keep, st.size);
            writeIndex((BitSet) held.clone());
            try (FileChannel c = FileChannel.open(file, StandardOpenOption.WRITE)) {
                c.truncate(RECORD * keep);
                c.force(true);
            }
        }
        long need = RECORD * keep - (Files.exists(file) ? Files.size(file) : 0);
        long free = Files.getFileStore(file.toAbsolutePath().getParent()).getUsableSpace();
        if (free - need < margin) {
            throw new IOException(String.format("the trees of %d states need %.1f GB more on disk; %.1f GB is free, and "
                    + "%.1f GB must be left", keep, need / 1e9, free / 1e9, margin / 1e9));
        }
        if (fresh) {
            // The index first, holding no state, so the store's file never stands without one.
            writeIndex(new BitSet(st.size));
        }
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    /**
     * Refuses a path where a store may not be opened afresh, deleting nothing: a file there that
     * is not a tree store, having no index beside it, or not a regular file, or an index beside it
     * that does not read as one.
     */
    static void refuseNonStore(Path file) throws IOException {
        Path index = indexOf(file);
        if (Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(file + " is not a regular file, so not a tree store; it is left as it is");
            }
            if (!Files.exists(index)) {
                throw new IOException(file + " is there and is not a tree store (no " + index.getFileName() + " beside "
                        + "it); it is left as it is. Give the run a store path of its own");
            }
        }
        if (Files.exists(index, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !readsAsIndex(index)) {
            throw new IOException(index + " is not a tree store's index; it and " + file.getFileName()
                    + " are left as they are");
        }
    }

    /** Whether a file reads as an index: an identity, a number of states, and the bits, filling it exactly. */
    private static boolean readsAsIndex(Path index) throws IOException {
        if (!Files.isRegularFile(index, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(index));
        try {
            int id = b.getInt();
            if (id < 0 || id > b.remaining()) {
                return false;
            }
            b.position(b.position() + id);
            int size = b.getInt();
            int bits = b.getInt();
            return size >= 0 && bits >= 0 && bits == b.remaining();
        } catch (java.nio.BufferUnderflowException e) {
            return false;
        }
    }

    /** A store opened read-only (readOnly). */
    private TreeStore(ScoringStates st, Path file, String identity, BitSet held) throws IOException {
        this.st = st;
        this.file = file;
        this.index = indexOf(file);
        this.identity = identity;
        this.readOnly = true;
        this.keep = 0;
        this.held = held;
        channel = FileChannel.open(file, StandardOpenOption.READ);
    }

    /**
     * Opens an existing store only to read it, keyed on its states alone: stateLines must be the
     * lines of its identity after the first, which names the commit (FullIndexRun gives the state
     * file and the number of states there), and it must hold a tree for st's states. Refuses any
     * other store, and a missing one, deleting nothing and writing nothing.
     */
    static TreeStore readOnly(ScoringStates st, Path file, String stateLines) throws IOException {
        Path index = indexOf(file);
        if (!Files.isRegularFile(file) || !Files.isRegularFile(index)) {
            throw new IOException("no tree store at " + file + " to read");
        }
        String was = identityOf(file);
        int cut = was.indexOf('\n');
        String states = cut < 0 ? "" : was.substring(cut + 1);
        if (!states.equals(stateLines)) {
            throw new IOException("the tree store " + file + " holds the trees of other states; it is left as it is. "
                    + "It is of:\n" + states + "\nnot:\n" + stateLines);
        }
        BitSet held = readIndex(index, was, st.size);
        if (held == null) {
            throw new IOException("the tree store " + file + " holds another number of states than " + st.size
                    + "; it is left as it is");
        }
        return new TreeStore(st, file, was, held);
    }

    private static Path indexOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".index");
    }

    /** The identity a store's index records, or null where there is no index. Only reads. */
    static String identityOf(Path file) throws IOException {
        Path index = indexOf(file);
        if (!Files.exists(index)) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(index));
        byte[] id = new byte[b.getInt()];
        b.get(id);
        return new String(id, StandardCharsets.UTF_8);
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
            if (readOnly || s >= keep) {
                built.incrementAndGet();
                return tree;
            }
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
        if (readOnly) {
            throw new IllegalStateException("the tree store " + file + " is open only to read");
        }
        channel.force(true);
        BitSet copy;
        synchronized (held) {
            copy = (BitSet) held.clone();
        }
        writeIndex(copy);
    }

    /** Records the states given as held, replacing the record before in one move. */
    private void writeIndex(BitSet copy) throws IOException {
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
        if (!readOnly) {
            sync();
        }
        channel.close();
    }
}
