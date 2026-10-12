package sim;

// SHOE_SPEC.md A9's port of java.util.SplittableRandom, copied verbatim below this header
// (SpecCopyTest holds the copy equal to the spec's block). Test-only.

/** java.util.SplittableRandom(long seed)'s nextLong, nextInt(bound) and nextDouble, step by step. */
final class PortableSplittableRandom {

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private long seed;

    PortableSplittableRandom(long seed) {
        this.seed = seed;
    }

    private long nextSeed() {
        seed += GOLDEN_GAMMA;
        return seed;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static int mix32(long z) {
        z = (z ^ (z >>> 33)) * 0x62A9D9ED799705F5L;
        return (int) (((z ^ (z >>> 28)) * 0xCB24D0A5C88C35B3L) >>> 32);
    }

    long nextLong() {
        return mix64(nextSeed());
    }

    int nextInt() {
        return mix32(nextSeed());
    }

    /** Uniform on 0 to bound - 1: a power of two takes the low bits; otherwise draws are rejected until unbiased. */
    int nextInt(int bound) {
        int m = bound - 1;
        int r = nextInt();
        if ((bound & m) == 0) {
            return r & m;
        }
        for (int u = r >>> 1; u + m - (r = u % bound) < 0; u = nextInt() >>> 1) {
            // u + m - r overflows to a negative int exactly when u falls in the last, partial block
        }
        return r;
    }

    /** The top 53 bits of nextLong, times 2^-53: uniform on [0, 1). */
    double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }
}
