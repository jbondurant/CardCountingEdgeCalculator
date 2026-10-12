package sim;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The edge at each count (SIM_CHECK.md section 9, SHOE_SPEC.md C4): a row's mean round result by
 * bet key at 80%, flat bet, in P3's mode A, each fold's file playing every shoe. For each shoe h
 * and key, N_h is the rounds whose bet key is that key and Y_h the sum of their X; the run keeps,
 * by fold and key, ΣN, ΣN², ΣY, ΣY², ΣYN, and Σ Y_h0·Y_h1, so the reader can pool the folds as
 * SIM_CHECK.md section 6 pools a figure. The exact side comes from states.bin's section.
 */
public final class EvByKey {

    /** The rows C4 names, in its order. */
    public static final List<String> ROWS = List.of("Hi-Lo = Hi-Lo Lite", "Hi-Lo = Hi-Lo Lite, full play");
    static final int LO = -128;
    static final int KEYS = 257;
    static final int PER_KEY = 11;

    public final String name;
    public final int row;
    final int offset;

    EvByKey(String name, int row, int offset) {
        this.name = name;
        this.row = row;
        this.offset = offset;
    }

    static int size() {
        return KEYS * PER_KEY;
    }

    /** One shoe's tallies by fold and key, reused shoe after shoe. */
    static final class Shoe {
        final long[][] n = new long[2][KEYS];
        final double[][] y = new double[2][KEYS];
        final boolean[] touched = new boolean[KEYS];
        final int[] list = new int[KEYS];
        int count;
    }

    static int slot(int key) {
        int k = key - LO;
        if (k < 0 || k >= KEYS) {
            throw new IllegalStateException("a bet key of " + key + " is outside ev-by-key's range " + LO + " to " + (LO + KEYS - 1));
        }
        return k;
    }

    void round(Shoe s, int fold, int key, double x) {
        int k = slot(key);
        s.n[fold][k]++;
        s.y[fold][k] += x;
        if (!s.touched[k]) {
            s.touched[k] = true;
            s.list[s.count++] = k;
        }
    }

    /** Adds the shoe's tallies to the sums and clears them; a key the shoe never met adds zeros. */
    void shoe(double[] sums, Shoe s) {
        for (int i = 0; i < s.count; i++) {
            int k = s.list[i];
            int at = offset + k * PER_KEY;
            sums[at + 10] += s.y[0][k] * s.y[1][k];
            for (int f = 0; f < 2; f++) {
                double n = s.n[f][k];
                double y = s.y[f][k];
                sums[at + 5 * f] += n;
                sums[at + 5 * f + 1] += n * n;
                sums[at + 5 * f + 2] += y;
                sums[at + 5 * f + 3] += y * y;
                sums[at + 5 * f + 4] += y * n;
                s.n[f][k] = 0;
                s.y[f][k] = 0;
            }
            s.touched[k] = false;
        }
        s.count = 0;
    }

    /** C4's lines for this row: by key upward, sim fold 0, sim fold 1, cross, exact; shoes the run's S. */
    List<String> lines(double[] sums, long shoes, States.Section section, States states) {
        double[] w = new double[KEYS];
        double[] wx = new double[KEYS];
        TreeSet<Integer> met = new TreeSet<>();
        if (section != null) {
            for (int e = 0; e < section.index.length; e++) {
                int k = slot(section.betKey[e]);
                double weight = 1 / states.q[section.index[e]];
                w[k] += weight;
                wx[k] += weight * (section.rvPlay[e] + section.rvInsurance[e]);
                met.add(k);
            }
        }
        for (int k = 0; k < KEYS; k++) {
            int at = offset + k * PER_KEY;
            if (sums[at] > 0 || sums[at + 5] > 0) {
                met.add(k);
            }
        }
        List<String> out = new ArrayList<>();
        for (int k : met) {
            int at = offset + k * PER_KEY;
            int key = k + LO;
            for (int f = 0; f < 2; f++) {
                out.add("sim\t" + name + "\t80\t" + f + "\t" + key + "\t" + shoes + "\t" + sums[at + 5 * f] + "\t"
                        + sums[at + 5 * f + 1] + "\t" + sums[at + 5 * f + 2] + "\t" + sums[at + 5 * f + 3] + "\t"
                        + sums[at + 5 * f + 4]);
            }
            out.add("cross\t" + name + "\t80\t" + key + "\t" + sums[at + 10]);
            out.add("exact\t" + name + "\t80\t" + key + "\t" + w[k] + "\t" + wx[k]);
        }
        return out;
    }
}
