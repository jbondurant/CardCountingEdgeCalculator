import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a joint run (FullIndexFit.JointRun) writes what it needs to go on after an
 * interruption, in one directory under runs/full-index/:
 * - run.id, the run's identity: the commit its classes were built from, the state file (its
 *   path, size and time), and every setting that changes a result, the threads among them,
 *   since each thread's sums are added in their own order;
 * - fit.ckpt, after every round of the passes: each strategy's plays, passes and sums
 *   (FullIndexFit.State), the run's rounds and the perfect row, marked fitted once every
 *   strategy waits for its last round;
 * - part-*.ckpt, each scoring item's Result as it is scored.
 * Every file is written whole to a temporary file, forced to disk and moved into place in one
 * step, so an interruption leaves the file before or the file after, never half of one, and
 * every file begins with the identity. A fresh run refuses a directory that holds a run; a
 * resumed run refuses one whose identity is not its own, and says what differs.
 */
final class FullIndexCheckpoint {

    final Path dir;
    final String identity;
    /**
     * Whether this is another run's directory, read and never written: the source of a replay
     * (FullIndexReplay), whose fit.ckpt and part checkpoints are read under its own run.id and
     * nothing else. Every write refuses, so no slip in the replay can change the run it reads.
     */
    final boolean readOnly;
    private boolean opened;

    FullIndexCheckpoint(Path dir, String identity) {
        this(dir, identity, false);
    }

    private FullIndexCheckpoint(Path dir, String identity, boolean readOnly) {
        this.dir = dir;
        this.identity = identity;
        this.readOnly = readOnly;
    }

    /**
     * Another run's checkpoint directory, read only, under the identity its run.id holds: every
     * file read must carry that identity, and nothing is ever written there.
     */
    static FullIndexCheckpoint source(Path dir) throws IOException {
        Path id = dir.resolve("run.id");
        if (!Files.isRegularFile(id)) {
            throw new IllegalStateException(dir + " holds no run.id: it is not a run's checkpoint directory");
        }
        return new FullIndexCheckpoint(dir, new String(Files.readAllBytes(id), StandardCharsets.UTF_8), true);
    }

    /**
     * Readies the directory: for a fresh run, one holding no run yet, whose identity is then
     * written; for a resumed run, one holding a run of this identity. Once is enough.
     */
    void open(boolean resume) throws IOException {
        refuseIfReadOnly();
        if (opened) {
            return;
        }
        Files.createDirectories(dir);
        Path id = dir.resolve("run.id");
        if (!resume) {
            if (Files.exists(id) || Files.exists(dir.resolve("fit.ckpt"))) {
                throw new IllegalStateException(dir + " holds a run already: resume it, or start in another directory");
            }
            write(id, identity.getBytes(StandardCharsets.UTF_8));
            opened = true;
            return;
        }
        if (!Files.exists(id)) {
            throw new IllegalStateException(dir + " holds no run to resume");
        }
        String was = new String(Files.readAllBytes(id), StandardCharsets.UTF_8);
        if (!was.equals(identity)) {
            throw new IllegalStateException("the run in " + dir + " is not this one; it differs in:\n" + differences(was, identity));
        }
        opened = true;
    }

    /** The lines of two identities that differ, each as it was and as it is now. */
    static String differences(String was, String now) {
        String[] a = was.split("\n");
        String[] b = now.split("\n");
        StringBuilder out = new StringBuilder();
        for (int k = 0; k < Math.max(a.length, b.length); k++) {
            String x = k < a.length ? a[k] : "";
            String y = k < b.length ? b[k] : "";
            if (!x.equals(y)) {
                out.append("  was: ").append(x).append("\n  now: ").append(y).append('\n');
            }
        }
        return out.toString();
    }

    /** The passes' state after a round: every strategy's, in the order given, the rounds and the perfect row. */
    void writeFit(FullIndexFit fit, List<FullIndexFit.Strategy> all, boolean fitted) throws IOException {
        List<FullIndexFit.State> states = new ArrayList<>();
        for (FullIndexFit.Strategy t : all) {
            states.add(t.state());
        }
        writeObject(dir.resolve("fit.ckpt"), new Object[]{fitted, states, new ArrayList<>(fit.rounds), fit.perfect});
    }

    /**
     * Puts the passes' state back into a run made afresh with the same strategies, and gives
     * whether every strategy was fitted; null if no round was written.
     */
    @SuppressWarnings("unchecked")
    Boolean readFit(FullIndexFit fit, List<FullIndexFit.Strategy> all) throws IOException, ClassNotFoundException {
        Object[] x = (Object[]) readObject(dir.resolve("fit.ckpt"));
        if (x == null) {
            return null;
        }
        List<FullIndexFit.State> states = (List<FullIndexFit.State>) x[1];
        if (states.size() != all.size()) {
            throw new IllegalStateException("the checkpoint holds " + states.size() + " strategies, the run " + all.size());
        }
        for (int k = 0; k < all.size(); k++) {
            all.get(k).restore(states.get(k));
        }
        fit.rounds.clear();
        fit.rounds.addAll((List<FullIndexFit.RoundStats>) x[2]);
        double[] perfect = (double[]) x[3];
        if ((perfect == null) != (fit.perfect == null)) {
            throw new IllegalStateException("the checkpoint and the run differ in whether they work out the perfect row");
        }
        if (perfect != null) {
            System.arraycopy(perfect, 0, fit.perfect, 0, perfect.length);
        }
        return (Boolean) x[0];
    }

    void writePart(String key, SystemScorer.Result r) throws IOException {
        writeObject(dir.resolve(key + ".ckpt"), r);
    }

    /** An item's scored Result, or null if it was not written. */
    SystemScorer.Result readPart(String key) throws IOException, ClassNotFoundException {
        return (SystemScorer.Result) readObject(dir.resolve(key + ".ckpt"));
    }

    /** Whether an item's file, key.ckpt, is there; nothing is read. */
    boolean hasPart(String key) {
        return Files.isRegularFile(dir.resolve(key + ".ckpt"));
    }

    /** Anything kept under key (key.ckpt), whole, as a part is: a replay keeps each item's record this way. */
    void writeRecord(String key, java.io.Serializable o) throws IOException {
        writeObject(dir.resolve(key + ".ckpt"), o);
    }

    /** What writeRecord kept under key, or null if nothing was. */
    Object readRecord(String key) throws IOException, ClassNotFoundException {
        return readObject(dir.resolve(key + ".ckpt"));
    }

    private void refuseIfReadOnly() {
        if (readOnly) {
            throw new IllegalStateException(dir + " is another run's checkpoint directory, read and never written");
        }
    }

    private void writeObject(Path file, Object o) throws IOException {
        refuseIfReadOnly();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp.toFile());
             ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(fo, 1 << 20))) {
            out.writeUTF(identity);
            out.writeObject(o);
            out.flush();
            fo.getFD().sync();
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Object readObject(Path file) throws IOException, ClassNotFoundException {
        if (!Files.exists(file)) {
            return null;
        }
        try (InputStream fi = Files.newInputStream(file);
             ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(fi, 1 << 20))) {
            String id = in.readUTF();
            if (!id.equals(identity)) {
                throw new IllegalStateException(file + " was written by another run; it differs in:\n"
                        + differences(id, identity));
            }
            return in.readObject();
        }
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp.toFile())) {
            fo.write(bytes);
            fo.getFD().sync();
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
