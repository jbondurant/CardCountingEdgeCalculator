package sim;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A run in fixed chunks (SIM_CHECK.md section 4): each chunk's sums go to a file forced to disk
 * and then renamed into place, headed by everything that fixes the chunk's numbers (the check,
 * the simulator's commit, the bundle's SHA-256, the seed, the chunk's place and size, K, the
 * planted bugs, the rows and figures). A resumed run reads a chunk back only when its header is
 * exactly the one it would write, and refuses the run otherwise. Chunks are added in index order
 * however many threads computed them, so a run's result is the same to the bit on any number of
 * threads, and after any number of kills and resumes (check D6).
 */
public final class Chunks {

    /** Computes one chunk's sums. */
    public interface Work {
        double[] chunk(int index) throws Exception;
    }

    private final Path dir;
    private final String prefix;
    private final List<String> header;

    /** header: the lines that fix a chunk's numbers, the chunk's own index excepted. */
    public Chunks(Path dir, String prefix, List<String> header) {
        this.dir = dir;
        this.prefix = prefix;
        this.header = header;
    }

    Path file(int index) {
        return dir.resolve(String.format("%s-%05d.txt", prefix, index));
    }

    List<String> headerOf(int index) {
        List<String> h = new ArrayList<>();
        h.add("# sim chunk, format 1");
        h.addAll(header);
        h.add("chunk\t" + index);
        return h;
    }

    /** The chunk's sums from its file, or null if there is none; a file with another header is refused. */
    double[] read(int index, int size) throws IOException {
        Path f = file(index);
        if (!Files.exists(f)) {
            return null;
        }
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        List<String> want = headerOf(index);
        if (lines.size() != want.size() + size + 1 || !lines.subList(0, want.size()).equals(want)
                || !lines.get(lines.size() - 1).equals("end")) {
            throw new IOException(f + " was written by another run (its header or length differs); refusing to resume");
        }
        double[] v = new double[size];
        for (int i = 0; i < size; i++) {
            v[i] = Double.parseDouble(lines.get(want.size() + i));
        }
        return v;
    }

    void write(int index, double[] sums) throws IOException {
        Files.createDirectories(dir);
        Path tmp = dir.resolve(file(index).getFileName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp.toFile());
             Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            for (String line : headerOf(index)) {
                w.write(line + "\n");
            }
            for (double d : sums) {
                w.write(Double.toString(d) + "\n");
            }
            w.write("end\n");
            w.flush();
            out.getFD().sync();
        }
        Files.move(tmp, file(index), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Runs chunks 0 to count - 1 on threads, skipping those already on disk, and returns their
     * sums added in index order. stopAfter, if at least 0, stops once that many chunks have been
     * computed in this call (a test's stand-in for a kill).
     */
    public double[] run(int count, int size, int threads, Work work, int stopAfter) throws Exception {
        double[][] parts = new double[count][];
        List<Integer> todo = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            parts[i] = read(i, size);
            if (parts[i] == null) {
                todo.add(i);
            }
        }
        if (stopAfter >= 0 && todo.size() > stopAfter) {
            todo = todo.subList(0, stopAfter);
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            List<Future<double[]>> futures = new ArrayList<>();
            for (int i : todo) {
                futures.add(pool.submit(() -> {
                    double[] s = work.chunk(i);
                    write(i, s);
                    return s;
                }));
            }
            for (int k = 0; k < todo.size(); k++) {
                parts[todo.get(k)] = futures.get(k).get();
            }
        } finally {
            pool.shutdown();
        }
        double[] total = new double[size];
        for (int i = 0; i < count; i++) {
            if (parts[i] == null) {
                return null;
            }
            for (int j = 0; j < size; j++) {
                total[j] += parts[i][j];
            }
        }
        return total;
    }
}
