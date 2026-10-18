import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Read-only probe (scratch, not repo code). For a few hundred records of a state file:
 * (a) a lossless "legal slots only, byte-plane shuffled, Deflate" encoding: size, exact round trip
 *     of every record's bytes, encode/decode speed on one thread;
 * (b) the heap a ScoringStates of those records holds, measured after GC, against the record bytes.
 * Args: FILE START,START,... PER_BLOCK
 */
public class RamProbe {

    static final long CANON = 0x7ff8000000000000L;

    public static void main(String[] a) throws Exception {
        Path data = Paths.get(a[0]);
        String[] starts = a[1].split(",");
        int per = Integer.parseInt(a[2]);
        StateStore.Header h = StateStore.header(data);
        int hb = h.bytes().length;
        int recs = StateStore.records(data);
        int R = StateStore.RECORD_BYTES;
        System.out.printf("header %d B, records %d, record %d B, values %d%n", hb, recs, R, StateStore.VALUES);

        RoundRules rules = StateRun.montreal();
        boolean[] legal = new boolean[StateStore.VALUES];
        int nLegal = 0;
        for (int i = 0; i < Deals.COUNT; i++) {
            for (int m = 0; m < StateValuer.MOVES.length; m++) {
                legal[i * StateValuer.MOVES.length + m] = StateValuer.legal(rules, i, StateValuer.MOVES[m]);
                if (legal[i * StateValuer.MOVES.length + m]) nLegal++;
            }
        }
        System.out.printf("legal slots %d, rule-NaN slots %d%n", nLegal, StateStore.VALUES - nLegal);
        int[] legalIdx = new int[nLegal];
        for (int v = 0, k = 0; v < legal.length; v++) if (legal[v]) legalIdx[k++] = v;

        // Raw bytes of each block, read positionally, read-only.
        List<byte[][]> blocks = new ArrayList<>();
        try (FileChannel ch = FileChannel.open(data, StandardOpenOption.READ)) {
            for (String s : starts) {
                int from = Integer.parseInt(s);
                byte[][] blk = new byte[per][R];
                for (int i = 0; i < per; i++) {
                    ByteBuffer b = ByteBuffer.wrap(blk[i]);
                    long at = hb + (long) (from + i) * R;
                    while (b.hasRemaining()) {
                        if (ch.read(b, at + b.position()) < 0) throw new RuntimeException("eof");
                    }
                }
                blocks.add(blk);
            }
        }

        // NaN audit.
        long ruleNaN = 0, ruleNonCanon = 0, legalNaN = 0, legalNaNNonCanon = 0;
        for (byte[][] blk : blocks) {
            for (byte[] r : blk) {
                ByteBuffer b = ByteBuffer.wrap(r);
                for (int v = 0; v < StateStore.VALUES; v++) {
                    long bits = b.getLong(72 + 8 * v);
                    if (!legal[v]) {
                        ruleNaN++;
                        if (bits != CANON) ruleNonCanon++;
                    } else if (Double.isNaN(Double.longBitsToDouble(bits))) {
                        legalNaN++;
                        if (bits != CANON) legalNaNNonCanon++;
                    }
                }
            }
        }
        System.out.printf("rule-NaN slots %d, not canonical %d; legal-slot NaNs %d, not canonical %d%n", ruleNaN,
                ruleNonCanon, legalNaN, legalNaNNonCanon);

        for (int order = 0; order < 3; order++) {
            long raw = 0, packed = 0, encNs = 0, decNs = 0;
            boolean same = true;
            int reps = 10;
            for (byte[][] blk : blocks) {
                raw += (long) blk.length * R;
                byte[] enc = null;
                long t0 = System.nanoTime();
                for (int k = 0; k < reps; k++) enc = encode(blk, legalIdx, order);
                encNs += System.nanoTime() - t0;
                packed += enc.length;
                byte[][] dec = null;
                t0 = System.nanoTime();
                for (int k = 0; k < reps; k++) dec = decode(enc, blk.length, R, legal, legalIdx, order);
                decNs += System.nanoTime() - t0;
                for (int i = 0; i < blk.length; i++) same &= Arrays.equals(blk[i], dec[i]);
            }
            String name = order == 0 ? "whole records, Deflate 6 (baseline)"
                    : order == 1 ? "legal only, planes x slot x record, Deflate 6"
                    : "legal only, planes x record x slot, Deflate 6";
            System.out.printf("%s: %d -> %d B, ratio %.4f; round trip byte-identical: %b; encode %.1f MB/s, decode %.1f MB/s (raw MB, one thread)%n",
                    name, raw, packed, (double) packed / raw, same, raw * reps / (encNs / 1e9) / 1e6,
                    raw * reps / (decNs / 1e9) / 1e6);
        }

        // Heap: records, then the ScoringStates made from them (the run's in-RAM form).
        int last = Integer.parseInt(starts[starts.length - 1]) + per - 1;
        // Warm-up: statics (chart, deals) made once before measuring.
        {
            List<StateStore.Record> one = StateStore.read(data, 0, 1);
            new ScoringStates(one, one.get(0).shoe + 1, rules);
        }
        blocks = null;
        long m0 = FullIndexPilot.usedAfterGc();
        List<StateStore.Record> list = new ArrayList<>();
        for (String s : starts) list.addAll(StateStore.read(data, Integer.parseInt(s), per));
        int n = list.size();
        int shoes = list.get(n - 1).shoe + 1;
        long m1 = FullIndexPilot.usedAfterGc();
        ScoringStates st = new ScoringStates(list, shoes, rules);
        long m2 = FullIndexPilot.usedAfterGc();
        System.out.printf("records %d (shoes up to %d): Records held %d B (%.1f B a record); ScoringStates held %d B (%.1f B a state)%n",
                n, shoes, m1 - m0, (double) (m1 - m0) / n, m2 - m0, (double) (m2 - m0) / n);
        double perState = 16 + 8.0 * StateStore.VALUES + 16 + 8.0 * Deals.COUNT + 16 + 8.0 * ScoringStates.ACE_UP
                + 16 + 4.0 * ShoeRun.KINDS + 3 * 4 + 6 * 8 + 4 * 4;
        System.out.printf("analytic ScoringStates a state %.0f B; x 60,743 = %.3f GB; move[] alone %.3f GB, of which rule-NaN %.3f GB, chance[] %.3f GB%n",
                perState, perState * 60743 / 1e9, (16 + 8.0 * StateStore.VALUES) * 60743 / 1e9,
                8.0 * (StateStore.VALUES - nLegal) * 60743 / 1e9, (16 + 8.0 * Deals.COUNT) * 60743 / 1e9);
        System.out.println("keep " + st.size + " " + last);
    }

    static byte[] encode(byte[][] blk, int[] legalIdx, int order) {
        int n = blk.length, L = legalIdx.length;
        byte[] buf;
        if (order == 0) {
            buf = new byte[n * blk[0].length];
            for (int i = 0; i < n; i++) System.arraycopy(blk[i], 0, buf, i * blk[i].length, blk[i].length);
        } else {
            buf = new byte[n * 72 + 8 * n * L];
            for (int i = 0; i < n; i++) System.arraycopy(blk[i], 0, buf, i * 72, 72);
            int base = n * 72;
            for (int i = 0; i < n; i++) {
                byte[] r = blk[i];
                for (int k = 0; k < L; k++) {
                    int off = 72 + 8 * legalIdx[k];
                    for (int p = 0; p < 8; p++) {
                        int at = order == 1 ? base + p * n * L + k * n + i : base + p * n * L + i * L + k;
                        buf[at] = r[off + p];
                    }
                }
            }
        }
        Deflater d = new Deflater(6);
        d.setInput(buf);
        d.finish();
        byte[] out = new byte[buf.length + 1024];
        int len = 0;
        while (!d.finished()) {
            if (len == out.length) out = Arrays.copyOf(out, out.length * 2);
            len += d.deflate(out, len, out.length - len);
        }
        d.end();
        return Arrays.copyOf(out, len);
    }

    static byte[][] decode(byte[] enc, int n, int R, boolean[] legal, int[] legalIdx, int order) throws Exception {
        int L = legalIdx.length;
        int size = order == 0 ? n * R : n * 72 + 8 * n * L;
        byte[] buf = new byte[size];
        Inflater inf = new Inflater();
        inf.setInput(enc);
        int got = 0;
        while (got < size) got += inf.inflate(buf, got, size - got);
        inf.end();
        byte[][] out = new byte[n][R];
        if (order == 0) {
            for (int i = 0; i < n; i++) System.arraycopy(buf, i * R, out[i], 0, R);
            return out;
        }
        int base = n * 72;
        for (int i = 0; i < n; i++) {
            byte[] r = out[i];
            System.arraycopy(buf, i * 72, r, 0, 72);
            ByteBuffer b = ByteBuffer.wrap(r);
            for (int v = 0; v < legal.length; v++) if (!legal[v]) b.putLong(72 + 8 * v, CANON);
            for (int k = 0; k < L; k++) {
                int off = 72 + 8 * legalIdx[k];
                for (int p = 0; p < 8; p++) {
                    int at = order == 1 ? base + p * n * L + k * n + i : base + p * n * L + i * L + k;
                    r[off + p] = buf[at];
                }
            }
        }
        return out;
    }
}
