package sim;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a states.bin in SHOE_SPEC.md B3's format from the simulator's own walk of a few shoes,
 * so the reader and check D1 can be tested before the exporter exists. The records are a list
 * the test may change before writing, to plant a fault D1 must see.
 */
final class StatesFixture {

    static final class Rec {
        int shoe;
        int round;
        int depth;
        double q;
        int fold;
        int[] dealt;
    }

    long seed = Shoe.SEED_MAIN;
    double q0 = Shoe.Q0;
    double u0 = Shoe.U0;
    int cut = 332;
    int shoes;
    String magic = "SIMSTATE";
    int version = 1;
    final List<Rec> records = new ArrayList<>();
    /** Section rows; each gets one section at 80% and one at 70%, with one plan. */
    final List<String> rows = new ArrayList<>(List.of("Chart, no count, flat", "Hi-Lo = Hi-Lo Lite"));
    boolean trailingByte;
    /** The plans every section lists. */
    List<String> plans = new ArrayList<>(List.of("same-8"));

    /** A section entry's values: {betKey, rvPlay, rvInsurance, bet per plan...}. */
    interface Filler {
        double[] fill(String row, int pen, int record);
    }

    /** By default made-up values, which test the format and nothing else. */
    Filler filler = (row, pen, i) -> {
        double[] v = new double[3 + plans.size()];
        v[0] = i % 7 - 3;
        v[1] = -0.005 + i * 1e-6;
        v[2] = i % 11 == 0 ? 0.01 : 0;
        for (int p = 0; p < plans.size(); p++) {
            v[3 + p] = 1 + (i + p) % 8;
        }
        return v;
    };

    StatesFixture(int shoes) {
        this.shoes = shoes;
        Round round = new Round(Rules.montreal());
        Chart chart = Chart.textbook();
        for (int h = 0; h < shoes; h++) {
            Walk.walkShoe(seed, h, cut, q0, u0, round, chart, (s, r, d, tallies, q, valued) -> {
                if (valued) {
                    Rec x = new Rec();
                    x.shoe = s;
                    x.round = r;
                    x.depth = d;
                    x.q = q;
                    x.fold = s & 1;
                    x.dealt = tallies.clone();
                    records.add(x);
                }
            }, null);
        }
    }

    byte[] bytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(magic.getBytes(StandardCharsets.US_ASCII));
        out.writeInt(version);
        out.writeLong(seed);
        out.writeDouble(q0);
        out.writeDouble(u0);
        out.writeInt(cut);
        out.writeInt(shoes);
        out.writeInt(records.size());
        for (Rec r : records) {
            out.writeInt(r.shoe);
            out.writeInt(r.round);
            out.writeInt(r.depth);
            out.writeDouble(r.q);
            out.writeByte(r.fold);
            for (int c : r.dealt) {
                out.writeByte(c);
            }
        }
        out.writeInt(rows.size() * 2);
        for (String row : rows) {
            for (int[] pc : new int[][]{{70, 291}, {80, 332}}) {
                writeString(out, row);
                out.writeInt(pc[0]);
                out.writeInt(pc[1]);
                out.writeInt(plans.size());
                for (String p : plans) {
                    writeString(out, p);
                }
                List<Integer> in = new ArrayList<>();
                for (int i = 0; i < records.size(); i++) {
                    if (records.get(i).depth < pc[1]) {
                        in.add(i);
                    }
                }
                out.writeInt(in.size());
                for (int i : in) {
                    double[] v = filler.fill(row, pc[0], i);
                    out.writeInt(i);
                    out.writeInt((int) v[0]);
                    out.writeDouble(v[1]);
                    out.writeDouble(v[2]);
                    for (int p = 0; p < plans.size(); p++) {
                        out.writeDouble(v[3 + p]);
                    }
                }
            }
        }
        out.write("SIMSTEND".getBytes(StandardCharsets.US_ASCII));
        if (trailingByte) {
            out.writeByte(0);
        }
        out.flush();
        return bytes.toByteArray();
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    States read() throws IOException {
        return States.read(new java.io.ByteArrayInputStream(bytes()));
    }
}
