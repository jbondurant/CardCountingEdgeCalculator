import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The valued states of a run, in one append-only binary file.
 *
 * The file starts with a header that says how the states were made: the seed, the
 * valuation rate's q0 and u0 (ShoeRun), the cut card, and a description of the rules and
 * the play. Then come fixed-size records, one per valued state, written a whole shoe at a
 * time: the shoe, the round, the depth, the chance q the state was valued with, the cards
 * dealt so far by kind (ShoeRun), and the values of every first move of every deal
 * (StateValuer). A shoe with no valued state writes nothing, but its number is still
 * counted as done, so the states of any shoe can be dealt again from the seed.
 *
 * A shoe's records are forced to disk before the progress file names the shoe as done, and a
 * run that resumes cuts the data back to what the progress file vouches for, so a run
 * stopped at any moment resumes as if it had not been.
 */
final class StateStore {

    static final int VERSION = 2;
    private static final byte[] MAGIC = "CCSTATES".getBytes(StandardCharsets.US_ASCII);
    static final int VALUES = Deals.COUNT * StateValuer.MOVES.length;
    static final int RECORD_BYTES = 4 * 3 + 8 + 2 * ShoeRun.KINDS + 8 * VALUES;

    /** How the states in a file were made. */
    static final class Header {
        final long seed;
        final double q0;
        final double u0;
        final int cut;
        final String rules;

        Header(long seed, double q0, double u0, int cut, String rules) {
            this.seed = seed;
            this.q0 = q0;
            this.u0 = u0;
            this.cut = cut;
            this.rules = rules;
        }

        byte[] bytes() {
            byte[] r = rules.getBytes(StandardCharsets.UTF_8);
            ByteBuffer b = ByteBuffer.allocate(MAGIC.length + 4 + 8 + 8 + 8 + 4 + 4 + 4 + 4 + r.length);
            b.put(MAGIC).putInt(VERSION).putLong(seed).putDouble(q0).putDouble(u0).putInt(cut)
                    .putInt(Deals.COUNT).putInt(StateValuer.MOVES.length).putInt(r.length).put(r);
            return b.array();
        }

        static Header read(DataInputStream in) throws IOException {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new IOException("not a state file");
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("state file version " + version + ", this code reads " + VERSION);
            }
            long seed = in.readLong();
            double q0 = in.readDouble();
            double u0 = in.readDouble();
            int cut = in.readInt();
            int deals = in.readInt();
            int moves = in.readInt();
            if (deals != Deals.COUNT || moves != StateValuer.MOVES.length) {
                throw new IOException("state file has " + deals + " deals and " + moves + " moves");
            }
            byte[] r = new byte[in.readInt()];
            in.readFully(r);
            return new Header(seed, q0, u0, cut, new String(r, StandardCharsets.UTF_8));
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Header)) {
                return false;
            }
            Header h = (Header) o;
            return seed == h.seed && q0 == h.q0 && u0 == h.u0 && cut == h.cut && rules.equals(h.rules);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(seed) ^ rules.hashCode();
        }
    }

    /** One valued state. */
    static final class Record {
        final int shoe;
        final int round;
        final int depth;
        /** The chance this state was valued; it stands for 1/q states. */
        final double q;
        final int[] dealt;
        final double[] values;

        Record(int shoe, int round, int depth, double q, int[] dealt, double[] values) {
            if (dealt.length != ShoeRun.KINDS || values.length != VALUES) {
                throw new IllegalArgumentException("a record holds " + ShoeRun.KINDS + " kinds and " + VALUES + " values");
            }
            this.shoe = shoe;
            this.round = round;
            this.depth = depth;
            this.q = q;
            this.dealt = dealt;
            this.values = values;
        }

        int[] ranksLeft() {
            return ShoeRun.ranksLeft(dealt);
        }
    }

    private StateStore() {
    }

    static Path progressFile(Path data) {
        return data.resolveSibling(data.getFileName() + ".progress");
    }

    /**
     * The next shoe to deal, after making the data file agree with its progress file: a new
     * file gets its header, and a file with records the progress file does not vouch for is
     * cut back.
     */
    static int open(Path data, Header header) throws IOException {
        Path progress = progressFile(data);
        byte[] head = header.bytes();
        if (!Files.exists(data)) {
            try (FileChannel ch = FileChannel.open(data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ch.write(ByteBuffer.wrap(head));
                ch.force(true);
            }
            writeProgress(progress, 0, head.length);
            return 0;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(data)))) {
            Header existing = Header.read(in);
            if (!existing.equals(header)) {
                throw new IOException(data + " was made with other settings: seed " + existing.seed + ", q0 "
                        + existing.q0 + ", u0 " + existing.u0 + ", cut " + existing.cut + ", " + existing.rules);
            }
        }
        long[] done = readProgress(progress);
        try (FileChannel ch = FileChannel.open(data, StandardOpenOption.WRITE)) {
            if (ch.size() < done[1]) {
                throw new IOException(data + " is shorter than its progress file says");
            }
            ch.truncate(done[1]);
            ch.force(true);
        }
        return (int) done[0];
    }

    /** Appends one shoe's records and then records the shoe as done. */
    static void appendShoe(Path data, int shoe, List<Record> records) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(RECORD_BYTES * records.size());
        for (Record r : records) {
            if (r.shoe != shoe) {
                throw new IllegalArgumentException("record of shoe " + r.shoe + " in shoe " + shoe);
            }
            b.putInt(r.shoe).putInt(r.round).putInt(r.depth).putDouble(r.q);
            for (int k : r.dealt) {
                b.putShort((short) k);
            }
            for (double v : r.values) {
                b.putDouble(v);
            }
        }
        b.flip();
        long size;
        try (FileChannel ch = FileChannel.open(data, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            while (b.hasRemaining()) {
                ch.write(b);
            }
            ch.force(true);
            size = ch.size();
        }
        writeProgress(progressFile(data), shoe + 1, size);
    }

    private static void writeProgress(Path progress, int nextShoe, long bytes) throws IOException {
        Path tmp = progress.resolveSibling(progress.getFileName() + ".tmp");
        Files.write(tmp, (nextShoe + " " + bytes + "\n").getBytes(StandardCharsets.US_ASCII));
        Files.move(tmp, progress, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static long[] readProgress(Path progress) throws IOException {
        String[] parts = new String(Files.readAllBytes(progress), StandardCharsets.US_ASCII).trim().split(" ");
        return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])};
    }

    /** The header of a state file. */
    static Header header(Path data) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(data)))) {
            return Header.read(in);
        }
    }

    /** The number of shoes the progress file vouches for: shoes 0 to this less one are complete. */
    static int shoesDone(Path data) throws IOException {
        return (int) readProgress(progressFile(data))[0];
    }

    /** Every record the progress file vouches for. */
    static List<Record> readAll(Path data) throws IOException {
        long limit = readProgress(progressFile(data))[1];
        List<Record> out = new ArrayList<>();
        try (InputStream raw = Files.newInputStream(data);
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 20))) {
            Header h = Header.read(in);
            long at = h.bytes().length;
            while (at + RECORD_BYTES <= limit) {
                int shoe = in.readInt();
                int round = in.readInt();
                int depth = in.readInt();
                double q = in.readDouble();
                int[] dealt = new int[ShoeRun.KINDS];
                for (int k = 0; k < dealt.length; k++) {
                    dealt[k] = in.readShort();
                }
                double[] values = new double[VALUES];
                for (int v = 0; v < VALUES; v++) {
                    values[v] = in.readDouble();
                }
                out.add(new Record(shoe, round, depth, q, dealt, values));
                at += RECORD_BYTES;
            }
        } catch (EOFException e) {
            throw new IOException(data + " ends inside a record its progress file vouches for", e);
        }
        return out;
    }
}
