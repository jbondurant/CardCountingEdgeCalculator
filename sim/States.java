package sim;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The bundle's states.bin (SHOE_SPEC.md B3): every valued state of the main file, and per
 * exported row and penetration the engine's bet key, round value and bets at each. Check D1
 * holds the records to the simulator's own walk ({@link #d1}); check P2 re-deals each state.
 *
 * <p>The reader checks what the format promises and refuses the file otherwise: the magic words,
 * the version, records in shoe and round order with fold = shoe parity and tallies adding to the
 * depth, each section holding exactly the records below its cut in record order, and nothing
 * after the trailer.
 */
public final class States {

    public final long seed;
    public final double q0;
    public final double u0;
    public final int cut;
    public final int shoes;
    public final int n;
    public final int[] shoe;
    public final int[] round;
    public final int[] depth;
    public final double[] q;
    public final byte[] fold;
    /** dealt[26 * i + k]: the cards of kind k dealt before record i. */
    public final byte[] dealt;
    public final List<Section> sections = new ArrayList<>();

    /** One row at one penetration: the engine's side of every state of that penetration. */
    public static final class Section {
        public String row;
        public int pen;
        public int cut;
        public String[] plans;
        public int[] index;
        public int[] betKey;
        public double[] rvPlay;
        public double[] rvInsurance;
        /** bet[plan][entry]. */
        public double[][] bet;
    }

    private States(DataInputStream in) throws IOException {
        magic(in, "SIMSTATE");
        int version = in.readInt();
        if (version != 1) {
            throw new IOException("states.bin version " + version + ", not 1");
        }
        seed = in.readLong();
        q0 = in.readDouble();
        u0 = in.readDouble();
        cut = in.readInt();
        shoes = in.readInt();
        n = in.readInt();
        shoe = new int[n];
        round = new int[n];
        depth = new int[n];
        q = new double[n];
        fold = new byte[n];
        dealt = new byte[n * Shoe.KINDS];
        for (int i = 0; i < n; i++) {
            shoe[i] = in.readInt();
            round[i] = in.readInt();
            depth[i] = in.readInt();
            q[i] = in.readDouble();
            fold[i] = in.readByte();
            in.readFully(dealt, i * Shoe.KINDS, Shoe.KINDS);
            int sum = 0;
            for (int k = 0; k < Shoe.KINDS; k++) {
                int c = dealt[i * Shoe.KINDS + k];
                if (c < 0 || c > Shoe.PER_KIND) {
                    throw new IOException("record " + i + ": " + c + " cards of kind " + k);
                }
                sum += c;
            }
            boolean ordered = i == 0 || shoe[i] > shoe[i - 1] || shoe[i] == shoe[i - 1] && round[i] > round[i - 1];
            if (sum != depth[i] || fold[i] != (shoe[i] & 1) || !ordered || shoe[i] < 0 || shoe[i] >= shoes
                    || depth[i] >= cut || !(q[i] > 0 && q[i] <= 1)) {
                throw new IOException("record " + i + " breaks B3: shoe " + shoe[i] + " round " + round[i] + " depth "
                        + depth[i] + " tallies adding to " + sum + " fold " + fold[i] + " q " + q[i]);
            }
        }
        int count = in.readInt();
        for (int s = 0; s < count; s++) {
            sections.add(section(in, s));
        }
        magic(in, "SIMSTEND");
        if (in.read() != -1) {
            throw new IOException("bytes after states.bin's trailer");
        }
    }

    private Section section(DataInputStream in, int s) throws IOException {
        Section x = new Section();
        x.row = str(in);
        x.pen = in.readInt();
        x.cut = in.readInt();
        x.plans = new String[in.readInt()];
        for (int p = 0; p < x.plans.length; p++) {
            x.plans[p] = str(in);
        }
        int m = in.readInt();
        x.index = new int[m];
        x.betKey = new int[m];
        x.rvPlay = new double[m];
        x.rvInsurance = new double[m];
        x.bet = new double[x.plans.length][m];
        int expected = 0;
        int next = 0;
        for (int j = 0; j < m; j++) {
            x.index[j] = in.readInt();
            x.betKey[j] = in.readInt();
            x.rvPlay[j] = in.readDouble();
            x.rvInsurance[j] = in.readDouble();
            for (int p = 0; p < x.plans.length; p++) {
                x.bet[p][j] = in.readDouble();
            }
            while (next < n && depth[next] >= x.cut) {
                next++;
            }
            if (next >= n || x.index[j] != next) {
                throw new IOException("section " + s + " (" + x.row + ", " + x.pen + "): entry " + j + " is record "
                        + x.index[j] + ", not the next record below cut " + x.cut);
            }
            next++;
            expected++;
        }
        while (next < n) {
            if (depth[next++] < x.cut) {
                throw new IOException("section " + s + " (" + x.row + ", " + x.pen + ") leaves out a record below its cut");
            }
        }
        int p = java.util.Arrays.binarySearch(Shoe.PENS, x.pen);
        if (p < 0 || Shoe.CUTS[p] != x.cut || expected != m) {
            throw new IOException("section " + s + ": pen " + x.pen + " with cut " + x.cut);
        }
        if (s > 0) {
            Section prev = sections.get(s - 1);
            int order = prev.row.compareTo(x.row);
            if (order > 0 || order == 0 && prev.pen >= x.pen) {
                throw new IOException("sections not sorted by row, then penetration, at " + x.row + " " + x.pen);
            }
        }
        return x;
    }

    private static void magic(DataInputStream in, String word) throws IOException {
        byte[] b = new byte[8];
        try {
            in.readFully(b);
        } catch (EOFException e) {
            throw new IOException("states.bin ends before its " + word, e);
        }
        if (!new String(b, StandardCharsets.US_ASCII).equals(word)) {
            throw new IOException("states.bin: expected " + word);
        }
    }

    private static String str(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 1 << 16) {
            throw new IOException("a string of " + len + " bytes");
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Whether the header is the main file's, as SHOE_SPEC.md A2 gives it. */
    public boolean isMainFile() {
        return seed == Shoe.SEED_MAIN && q0 == Shoe.Q0 && u0 == Shoe.U0 && cut == Shoe.CUTS[2] && shoes == Shoe.SHOES_MAIN;
    }

    /** What check D1 found: the walk's round starts, and how its valued set met the records. */
    public static final class D1 {
        public final long[] rounds = new long[3];
        public long valued;
        public long matched;
        public long mismatches;
        public final List<String> first = new ArrayList<>();

        void miss(String what) {
            mismatches++;
            if (first.size() < 10) {
                first.add(what);
            }
        }

        /** Every valued state is the next record, equal card for card and in q to the bit, and none is left over. */
        public boolean cardForCard() {
            return mismatches == 0;
        }
    }

    /**
     * Check D1: the simulator walks shoes 0 to shoes - 1 of the header's seed to its cut with the
     * chart, recomputing q at every round start and making the stream-1 draw itself, and each
     * state it values must be the next record, in shoe, round, depth, the 26 tallies and q's bits.
     */
    public D1 d1(Round walker, Chart chart) {
        D1 out = new D1();
        int[] next = {0};
        Walk.Totals totals = new Walk.Totals();
        for (int h = 0; h < shoes; h++) {
            Walk.walkShoe(seed, h, cut, q0, u0, walker, chart, (s, r, d, tallies, qq, valued) -> {
                if (!valued) {
                    return;
                }
                out.valued++;
                int i = next[0]++;
                if (i >= n) {
                    out.miss("shoe " + s + " round " + r + " valued by the walk, past the file's last record");
                    return;
                }
                boolean same = shoe[i] == s && round[i] == r && depth[i] == d
                        && Double.doubleToRawLongBits(q[i]) == Double.doubleToRawLongBits(qq);
                for (int k = 0; same && k < Shoe.KINDS; k++) {
                    same = dealt[i * Shoe.KINDS + k] == tallies[k];
                }
                if (same) {
                    out.matched++;
                } else {
                    out.miss("record " + i + " (shoe " + shoe[i] + " round " + round[i] + " depth " + depth[i]
                            + ") differs from the walk's valued state at shoe " + s + " round " + r + " depth " + d);
                }
            }, totals);
        }
        if (next[0] < n) {
            out.miss((n - next[0]) + " records the walk did not value, from record " + next[0]);
        }
        System.arraycopy(totals.rounds, 0, out.rounds, 0, 3);
        return out;
    }

    public static States read(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 16))) {
            return new States(in);
        }
    }

    public static States read(InputStream stream) throws IOException {
        return new States(new DataInputStream(new BufferedInputStream(stream, 1 << 16)));
    }
}
