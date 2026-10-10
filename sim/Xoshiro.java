package sim;

/**
 * xoshiro256** (Blackman and Vigna, 2018), the generator of the simulator's own runs: the fresh
 * shoes of check P3, the re-deals of check P2 and the coins of a back-counting plan. It is a
 * different generator from the main file's java.util.SplittableRandom on purpose, so that P3's
 * shoes share nothing with the shoes the engine was fitted and scored on.
 *
 * <p><b>Seeding, fixed here before the first pilot (SIM_CHECK.md section 4).</b> SplitMix64
 * started at a run's seed is the sequence {@code out(i) = mix64(seed + (i + 1) * 0x9E3779B97F4A7C15)},
 * i = 0, 1, 2, ..., with mix64 Stafford's variant 13 (the finaliser of {@link Shoe#mix}). The
 * generator for (index, stream, sub) takes its four words of state from the four outputs
 * {@code 4b} to {@code 4b + 3}, where {@code b = (index * 2 + stream) * 2^20 + sub}. Index is a
 * shoe (P3) or a state's place in states.bin (P2); stream 0 is the cards and stream 1 the coins;
 * sub is a re-deal's number within its state (P2, below 2^20) and 0 otherwise. So every shoe,
 * state and re-deal has its own generator, reachable directly, and the cards an instance sees
 * never depend on how many coins were drawn or on which thread dealt them.
 */
public final class Xoshiro {

    public static final long GOLDEN = 0x9E3779B97F4A7C15L;
    public static final int STREAM_CARDS = 0;
    public static final int STREAM_COINS = 1;
    static final int SUB_BITS = 20;

    private long s0;
    private long s1;
    private long s2;
    private long s3;

    /** A generator from its four words of state, which must not all be zero. */
    public Xoshiro(long s0, long s1, long s2, long s3) {
        if ((s0 | s1 | s2 | s3) == 0) {
            throw new IllegalArgumentException("xoshiro256** state is all zero");
        }
        this.s0 = s0;
        this.s1 = s1;
        this.s2 = s2;
        this.s3 = s3;
    }

    /** Stafford's variant 13, SplitMix64's output function. */
    public static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Output i (from 0) of SplitMix64 started at seed. */
    public static long splitMix64(long seed, long i) {
        return mix64(seed + (i + 1) * GOLDEN);
    }

    /** The generator of (index, stream, sub) under a run's seed, as the class comment derives it. */
    public static Xoshiro of(long seed, long index, int stream, int sub) {
        if (index < 0 || index >= (1L << 41) || stream < 0 || stream > 1 || sub < 0 || sub >= (1 << SUB_BITS)) {
            throw new IllegalArgumentException("index " + index + ", stream " + stream + ", sub " + sub);
        }
        long b = ((index * 2 + stream) << SUB_BITS) + sub;
        return new Xoshiro(splitMix64(seed, 4 * b), splitMix64(seed, 4 * b + 1),
                splitMix64(seed, 4 * b + 2), splitMix64(seed, 4 * b + 3));
    }

    public long nextLong() {
        long result = Long.rotateLeft(s1 * 5, 7) * 9;
        long t = s1 << 17;
        s2 ^= s0;
        s3 ^= s1;
        s1 ^= s2;
        s0 ^= s3;
        s2 ^= t;
        s3 = Long.rotateLeft(s3, 45);
        return result;
    }

    /** Uniform on 0 to bound - 1, unbiased: Lemire's multiply-and-shift on the top 32 bits, rejecting the short block. */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound " + bound);
        }
        long m = (nextLong() >>> 32) * bound;
        long low = m & 0xFFFFFFFFL;
        if (low < bound) {
            long threshold = (0x100000000L - bound) % bound;
            while (low < threshold) {
                m = (nextLong() >>> 32) * bound;
                low = m & 0xFFFFFFFFL;
            }
        }
        return (int) (m >>> 32);
    }

    /** The top 53 bits times 2^-53: uniform on [0, 1). */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }
}
