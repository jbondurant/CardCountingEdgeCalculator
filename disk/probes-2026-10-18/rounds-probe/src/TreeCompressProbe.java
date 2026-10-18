import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.zip.Deflater;

/** Scratch probe: lossless compressibility of the stand values a contested walk needs (raw, and byte-shuffled per state). */
public class TreeCompressProbe {
    public static void main(String[] a) throws Exception {
        ScoringStates st = FullIndexPilot.load(Paths.get(a[0]), Integer.parseInt(a[1]));
        int[] ones = new int[11];
        Arrays.fill(ones, 1);
        PlayerTree.Hands H = PlayerTree.Hands.ALL;
        ExactRound engine = new ExactRound(st.rules);
        long raw = 0, deflRaw = 0, deflShuf = 0;
        Path out = Paths.get(a[2]);
        try (java.io.OutputStream os = Files.newOutputStream(out)) {
            for (int s = 0; s < st.size; s++) {
                PlayerTree t = new PlayerTree(engine, ShoeRun.ranksLeft(st.dealt[s]));
                List<Double> vals = new ArrayList<>();
                for (int u = 1; u <= 10; u++) {
                    for (double v : t.standRow(u)) if (!Double.isNaN(v)) vals.add(v);
                }
                int n = vals.size();
                ByteBuffer b = ByteBuffer.allocate(8 * n);
                for (double v : vals) b.putDouble(v);
                byte[] r = b.array();
                byte[] sh = new byte[r.length];
                for (int i = 0; i < n; i++) for (int k = 0; k < 8; k++) sh[k * n + i] = r[i * 8 + k];
                raw += r.length;
                deflRaw += deflate(r);
                deflShuf += deflate(sh);
                os.write(sh);
            }
        }
        System.out.printf("states %d: feasible stand values %.0f B a state; deflate-9 %.1f%%; byte-shuffled deflate-9 %.1f%%%n",
                st.size, raw / (double) st.size, 100.0 * deflRaw / raw, 100.0 * deflShuf / raw);
    }
    static long deflate(byte[] in) {
        Deflater d = new Deflater(9);
        d.setInput(in);
        d.finish();
        byte[] buf = new byte[in.length + 1024];
        long n = 0;
        while (!d.finished()) n += d.deflate(buf);
        d.end();
        return n;
    }
}
