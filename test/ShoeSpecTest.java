import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.ToIntBiFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Holds SHOE_SPEC.md to the engine (PLAN_COMPUTE.md 1.1).
 *
 * The independent simulator is written by someone who reads SHOE_SPEC.md and never ShoeRun. If
 * the spec drifted from ShoeRun, the simulator would deal other shoes, and check D1 would fail
 * only after the simulator was written, with nothing to say whether the simulator or the spec was
 * wrong. So this test reads the spec itself, not a copy of it: its two data blocks (the 26 kinds,
 * and every constant the shoes depend on) are parsed, and its two Java blocks are compiled
 * exactly as written and run beside the engine. A change on either side that changes a card, a
 * round start, a tally, a q in its last bit or a valuation draw fails here.
 *
 * The heaviest check deals all 60,000 shoes of the main seed twice, with the spec's walk and
 * with ShoeRun, and compares every round start; it reads nothing from runs/montreal-80.states.
 * The round itself is the engine's (ConcreteRound with the chart), since the spec leaves the
 * round to the simulator; A4's prose about which cards a round takes is checked separately, by
 * a transcription of that prose (proseRoundEnd) that must take the same cards as ConcreteRound
 * in every one of those rounds.
 *
 * Last, plantedDivergencesAreCaught compiles the spec's code with one deliberate change at a
 * time and requires the comparison to see each, so a comparison that had gone blind would fail.
 */
public class ShoeSpecTest {

    static final Path SPEC = Paths.get("SHOE_SPEC.md");

    private static String spec;
    private static Map<String, String> constants;
    private static Map<String, String> sources;
    private static final List<Path> TEMP = new ArrayList<>();
    private static Code code;

    @BeforeAll
    static void readSpec() throws Exception {
        spec = new String(Files.readAllBytes(SPEC), StandardCharsets.UTF_8);
        constants = constants(spec);
        sources = javaBlocks(spec);
        code = new Code(compile(sources));
    }

    @AfterAll
    static void removeTemp() throws IOException {
        for (Path dir : TEMP) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    // ------------------------------------------------------------------ reading the spec

    /** The lines of the one fenced block whose info string is exactly info. */
    static List<String> block(String text, String info) {
        List<String> out = null;
        List<String> found = null;
        for (String line : text.split("\n", -1)) {
            if (out == null && line.equals("```" + info)) {
                if (found != null) {
                    throw new IllegalStateException("SHOE_SPEC.md has two ```" + info + " blocks");
                }
                out = new ArrayList<>();
            } else if (out != null && line.equals("```")) {
                found = out;
                out = null;
            } else if (out != null) {
                out.add(line);
            }
        }
        if (found == null) {
            throw new IllegalStateException("SHOE_SPEC.md has no ```" + info + " block");
        }
        return found;
    }

    /** The constants block as name to value, in order; a name given twice is an error. */
    static Map<String, String> constants(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : block(text, "shoe-spec-constants")) {
            if (line.isBlank()) {
                continue;
            }
            int eq = line.indexOf(" = ");
            if (eq < 0 || out.put(line.substring(0, eq).trim(), line.substring(eq + 3).trim()) != null) {
                throw new IllegalStateException("constants line \"" + line + "\"");
            }
        }
        return out;
    }

    static String constant(String name) {
        String v = constants.get(name);
        if (v == null) {
            throw new IllegalStateException("SHOE_SPEC.md's constants have no " + name);
        }
        return v;
    }

    static int intConstant(String name) {
        return Integer.parseInt(constant(name));
    }

    static long longConstant(String name) {
        String v = constant(name);
        return v.startsWith("0x") ? Long.parseUnsignedLong(v.substring(2), 16) : Long.parseLong(v);
    }

    static double doubleConstant(String name) {
        return Double.parseDouble(constant(name));
    }

    static boolean booleanConstant(String name) {
        String v = constant(name);
        if (!v.equals("true") && !v.equals("false")) {
            throw new IllegalStateException(name + " = " + v);
        }
        return v.equals("true");
    }

    /** Every ```java NAME.java block, by file name. */
    static Map<String, String> javaBlocks(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("```java ") && line.endsWith(".java")) {
                String name = line.substring("```java ".length()).trim();
                out.put(name, String.join("\n", block(text, "java " + name)) + "\n");
            }
        }
        if (!out.containsKey("ShoeSpec.java") || !out.containsKey("PortableSplittableRandom.java")) {
            throw new IllegalStateException("SHOE_SPEC.md's code blocks are " + out.keySet());
        }
        return out;
    }

    // ------------------------------------------------------------------ compiling the spec's code

    /** Compiles the sources (file name to text) into a fresh directory and loads them. */
    static ClassLoader compile(Map<String, String> files) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalStateException("no system Java compiler: run the tests on a JDK");
        }
        Path dir = Files.createTempDirectory("shoe-spec");
        TEMP.add(dir);
        Path src = Files.createDirectories(dir.resolve("src"));
        Path out = Files.createDirectories(dir.resolve("classes"));
        List<String> args = new ArrayList<>(Arrays.asList("-nowarn", "-d", out.toString()));
        for (Map.Entry<String, String> f : files.entrySet()) {
            Path p = src.resolve(f.getKey());
            Files.write(p, f.getValue().getBytes(StandardCharsets.UTF_8));
            args.add(p.toString());
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = javac.run(null, null, err, args.toArray(new String[0]));
        if (status != 0) {
            throw new IllegalStateException("SHOE_SPEC.md's code does not compile:\n" + err.toString(StandardCharsets.UTF_8));
        }
        return new URLClassLoader(new URL[]{out.toUri().toURL()}, ShoeSpecTest.class.getClassLoader());
    }

    /** The spec's ShoeSpec class, called by reflection, since it is compiled from the spec's text here. */
    static final class Code {
        final ClassLoader loader;
        private final Method mix;
        private final Method layout;
        private final Method shuffled;
        private final Method ranksLeft;
        private final Method unusualness;
        private final Method rate;
        private final Method walk;
        private final Method rankOf;
        private final Method red;
        private final Method chartDecisions;
        private final Method fingerprint;
        private final Method shoe;
        private final Method round;
        private final Method depth;
        private final Method dealt;
        private final Method q;
        private final Method valued;

        Code(ClassLoader loader) throws ReflectiveOperationException {
            this.loader = loader;
            Class<?> c = loader.loadClass("ShoeSpec");
            mix = method(c, "mix", long.class, long.class, long.class);
            layout = method(c, "layout");
            shuffled = method(c, "shuffled", long.class, int.class);
            ranksLeft = method(c, "ranksLeft", int[].class);
            unusualness = method(c, "unusualness", int[].class);
            rate = method(c, "rate", int[].class, double.class, double.class);
            walk = method(c, "walk", long.class, int.class, int.class, double.class, double.class,
                    ToIntBiFunction.class);
            rankOf = method(c, "rankOf", int.class);
            red = method(c, "red", int.class);
            chartDecisions = method(c, "chartDecisions");
            fingerprint = method(c, "fingerprint", int[].class);
            Class<?> start = loader.loadClass("ShoeSpec$Start");
            shoe = method(start, "shoe");
            round = method(start, "round");
            depth = method(start, "depth");
            dealt = method(start, "dealt");
            q = method(start, "q");
            valued = method(start, "valued");
        }

        private static Method method(Class<?> c, String name, Class<?>... types) throws NoSuchMethodException {
            Method m = c.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m;
        }

        private static Object call(Method m, Object target, Object... args) {
            try {
                return m.invoke(target, args);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            } catch (InvocationTargetException e) {
                Throwable t = e.getCause();
                throw t instanceof RuntimeException ? (RuntimeException) t : new IllegalStateException(t);
            }
        }

        long mix(long seed, long shoe, long stream) {
            return (long) call(mix, null, seed, shoe, stream);
        }

        int[] layout() {
            return (int[]) call(layout, null);
        }

        int[] shuffled(long seed, int shoe) {
            return (int[]) call(shuffled, null, seed, shoe);
        }

        int[] ranksLeft(int[] dealt) {
            return (int[]) call(ranksLeft, null, (Object) dealt);
        }

        double unusualness(int[] left) {
            return (double) call(unusualness, null, (Object) left);
        }

        double rate(int[] left, double q0, double u0) {
            return (double) call(rate, null, left, q0, u0);
        }

        int rankOf(int kind) {
            return (int) call(rankOf, null, kind);
        }

        boolean red(int kind) {
            return (boolean) call(red, null, kind);
        }

        int[][] chartDecisions() {
            return (int[][]) call(chartDecisions, null);
        }

        String fingerprint(int[] moves) {
            return (String) call(fingerprint, null, (Object) moves);
        }

        /** The spec's walk of one shoe, each start as a ShoeRun.State so the two compare field by field. */
        List<ShoeRun.State> walk(long seed, int shoeNumber, int cut, double q0, double u0,
                                 ToIntBiFunction<int[], Integer> roundEnd) {
            List<?> starts = (List<?>) call(walk, null, seed, shoeNumber, cut, q0, u0, roundEnd);
            List<ShoeRun.State> out = new ArrayList<>(starts.size());
            for (Object s : starts) {
                out.add(new ShoeRun.State((int) call(shoe, s), (int) call(round, s), (int) call(depth, s),
                        (int[]) call(dealt, s), (double) call(q, s), (boolean) call(valued, s)));
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ the engine's side

    private static final RoundRules RULES = StateRun.montreal();
    private static final RoundPolicy CHART = BasicStrategy.chart();

    /** The engine's round: ShoeRun.deal plays every round this way. */
    static int engineRoundEnd(int[] ranks, int from) {
        return ConcreteRound.play(ranks, from, RULES, CHART).next;
    }

    /**
     * A4's prose, transcribed: the index after the last card of the round that starts at
     * ranks[from], each decision put to the chart with the legal moves A4 lists. It follows the
     * spec's numbered steps, not ConcreteRound's code, so its taking the same cards as
     * ConcreteRound in every round of the main shoes shows the words are enough.
     */
    static int proseRoundEnd(int[] ranks, int from) {
        int[] next = {from + 4};
        int p1 = ranks[from];
        int up = ranks[from + 1];
        int p2 = ranks[from + 2];
        int hole = ranks[from + 3];
        boolean dealerNatural = (up == 1 && hole == 10) || (up == 10 && hole == 1);
        boolean playerNatural = (p1 == 1 && p2 == 10) || (p1 == 10 && p2 == 1);
        if (dealerNatural || playerNatural) {
            return next[0];
        }
        int[] hands = {1};
        boolean[] standing = {false};
        playProse(ranks, next, up, new ArrayList<>(Arrays.asList(p1, p2)), 0, hands, standing);
        if (standing[0]) {
            List<Integer> dealer = new ArrayList<>(Arrays.asList(up, hole));
            while (ConcreteRound.total(dealer) < 17
                    || (ConcreteRound.total(dealer) == 17 && ConcreteRound.soft(dealer))) {
                dealer.add(ranks[next[0]++]);
            }
        }
        return next[0];
    }

    /** One hand of two cards played to its end by A4's steps 4 and 5; splitRank is 0 for the dealt hand. */
    private static void playProse(int[] ranks, int[] next, int up, List<Integer> hand, int splitRank, int[] hands,
                                  boolean[] standing) {
        boolean fromSplit = splitRank != 0;
        boolean splitAce = splitRank == 1;
        int total = ConcreteRound.total(hand);
        int pair = hand.get(0).equals(hand.get(1)) ? hand.get(0) : 0;
        boolean canHit = total < 21 && !splitAce;
        boolean canDouble = !splitAce;
        boolean canSplit = pair != 0 && hands[0] + 1 <= (pair == 1 ? 2 : 4);
        PlayerMove m = CHART.choose(new RoundPolicy.Decision(total, ConcreteRound.soft(hand), 2, pair, up, fromSplit,
                true, canHit, canDouble, canSplit, false));
        switch (m) {
            case Stand:
                standing[0] = true;
                return;
            case Double:
                hand.add(ranks[next[0]++]);
                standing[0] |= ConcreteRound.total(hand) <= 21;
                return;
            case Split: {
                hands[0]++;
                List<Integer> a = new ArrayList<>(Arrays.asList(pair, ranks[next[0]++]));
                List<Integer> b = new ArrayList<>(Arrays.asList(pair, ranks[next[0]++]));
                playProse(ranks, next, up, a, pair, hands, standing);
                playProse(ranks, next, up, b, pair, hands, standing);
                return;
            }
            case Hit:
                hand.add(ranks[next[0]++]);
                while (ConcreteRound.total(hand) < 21) {
                    PlayerMove again = CHART.choose(new RoundPolicy.Decision(ConcreteRound.total(hand),
                            ConcreteRound.soft(hand), hand.size(), 0, up, fromSplit, false, true, false, false, false));
                    if (again == PlayerMove.Stand) {
                        break;
                    }
                    hand.add(ranks[next[0]++]);
                }
                standing[0] |= ConcreteRound.total(hand) <= 21;
                return;
            default:
                throw new IllegalStateException("A4 offers no " + m);
        }
    }

    /** How two lists of round starts first differ, or null if they are the same to the bit. */
    static String difference(List<ShoeRun.State> spec, List<ShoeRun.State> engine) {
        for (int k = 0; k < Math.min(spec.size(), engine.size()); k++) {
            ShoeRun.State a = spec.get(k);
            ShoeRun.State b = engine.get(k);
            if (a.shoe != b.shoe || a.round != b.round || a.depth != b.depth || !Arrays.equals(a.dealt, b.dealt)
                    || Double.doubleToRawLongBits(a.q) != Double.doubleToRawLongBits(b.q) || a.valued != b.valued) {
                return "shoe " + b.shoe + " round " + b.round + ": the spec has depth " + a.depth + ", q " + a.q
                        + ", valued " + a.valued + ", tallies " + Arrays.toString(a.dealt) + "; ShoeRun has depth "
                        + b.depth + ", q " + b.q + ", valued " + b.valued + ", tallies " + Arrays.toString(b.dealt);
            }
        }
        if (spec.size() != engine.size()) {
            return "shoe " + engine.get(0).shoe + ": the spec has " + spec.size() + " round starts, ShoeRun "
                    + engine.size();
        }
        return null;
    }

    /** The first shoe of from to to - 1 whose round starts differ between the spec's code and ShoeRun, or null. */
    static String firstDifference(Code c, long seed, int from, int to) {
        double q0 = doubleConstant("q0");
        double u0 = doubleConstant("u0");
        int cut = intConstant("cut.80");
        ShoeRun run = new ShoeRun(seed, q0, u0, cut, RULES, CHART);
        for (int shoe = from; shoe < to; shoe++) {
            if (!Arrays.equals(c.shuffled(seed, shoe), run.shuffled(shoe))) {
                return "shoe " + shoe + " is shuffled differently";
            }
            String d = difference(c.walk(seed, shoe, cut, q0, u0, ShoeSpecTest::engineRoundEnd), run.deal(shoe));
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the checks

    /** A2's sizes, cuts and rules are the engine's. */
    @Test
    public void theConstantsAreTheEngines() {
        assertEquals(ShoeRun.DECKS, intConstant("decks"));
        assertEquals(ShoeRun.CARDS, intConstant("cards"));
        assertEquals(ShoeRun.KINDS, intConstant("kinds"));
        assertEquals(52, intConstant("kinds") * intConstant("cards.per.kind.per.deck"));
        assertArrayEquals(SystemScorer.CUTS,
                new int[]{intConstant("cut.70"), intConstant("cut.75"), intConstant("cut.80")});
        assertEquals(StateRun.CUT_80, intConstant("cut.80"));
        assertEquals(0, intConstant("stream.cards"));
        assertEquals(1, intConstant("stream.valuation"));

        RoundRules r = RULES;
        assertEquals(r.hitsSoft17, booleanConstant("rule.hits.soft.17"));
        assertEquals(r.blackjackPayout, doubleConstant("rule.blackjack.payout"), 0);
        assertEquals(r.maxHandsAces, intConstant("rule.max.hands.aces"));
        assertEquals(r.maxHandsNotAces, intConstant("rule.max.hands.other"));
        assertEquals(r.canHitSplitAces, booleanConstant("rule.hit.split.aces"));
        boolean[] das = new boolean[11];
        for (String rank : constant("rule.double.after.split").split(" ")) {
            das[Integer.parseInt(rank)] = true;
        }
        assertArrayEquals(r.doubleAfterSplit, das);
        assertEquals(r.blackjackOnSplitPairs, booleanConstant("rule.blackjack.on.split.pairs"));
        assertEquals(r.surrender, booleanConstant("rule.surrender"));
        assertEquals(r.surrenderAfterSplit, booleanConstant("rule.surrender.after.split"));
    }

    /** A1's table of kinds is ShoeRun's: rank13, card, contract rank and colour of every kind. */
    @Test
    public void theKindsAreTheEngines() {
        String[] cards = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
        List<String> rows = new ArrayList<>();
        for (String line : block(spec, "shoe-spec-kinds")) {
            if (!line.isBlank()) {
                rows.add(line);
            }
        }
        assertEquals(ShoeRun.KINDS, rows.size());
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            String[] f = rows.get(k).trim().split("\\s+");
            assertEquals(5, f.length, rows.get(k));
            assertEquals(k, Integer.parseInt(f[0]), rows.get(k));
            int rank13 = Integer.parseInt(f[1]);
            assertEquals(ShoeRun.simulatorRank(k).ordinal(), rank13, rows.get(k));
            assertEquals(cards[rank13], f[2], rows.get(k));
            assertEquals(ShoeRun.rankOf(k), Integer.parseInt(f[3]), rows.get(k));
            assertEquals(ShoeRun.rankOf(k), RoundRules.rankIndex(ShoeRun.simulatorRank(k)), rows.get(k));
            assertEquals(ShoeRun.red(k) ? "red" : "black", f[4], rows.get(k));
            assertEquals(ShoeRun.red(k), ShoeRun.suit(k) == Suit.HEARTS, rows.get(k));
            assertEquals(ShoeRun.rankOf(k), code.rankOf(k));
            assertEquals(ShoeRun.red(k), code.red(k));
        }
    }

    /** A2's mix constants, put together as A3 describes, give ShoeRun.mix; so does A3's code. */
    @Test
    public void theMixIsTheEngines() {
        SplittableRandom rnd = new SplittableRandom(7);
        for (int t = 0; t < 20_000; t++) {
            long seed = t < 3 ? new long[]{0, -1, Long.MIN_VALUE}[t] : rnd.nextLong();
            long shoe = t % 2 == 0 ? rnd.nextInt(1 << 20) : rnd.nextLong();
            long stream = t % 3 == 0 ? rnd.nextInt(4) : rnd.nextLong();
            long engine = ShoeRun.mix(seed, shoe, stream);
            assertEquals(engine, mixFromConstants(seed, shoe, stream), seed + " " + shoe + " " + stream);
            assertEquals(engine, code.mix(seed, shoe, stream), seed + " " + shoe + " " + stream);
        }
    }

    static long mixFromConstants(long seed, long shoe, long stream) {
        long z = seed * longConstant("mix.seed") + shoe * longConstant("mix.shoe") + stream * longConstant("mix.stream");
        z = (z ^ (z >>> intConstant("mix.shift1"))) * longConstant("mix.mult1");
        z = (z ^ (z >>> intConstant("mix.shift2"))) * longConstant("mix.mult2");
        return z ^ (z >>> intConstant("mix.shift3"));
    }

    /**
     * A3's layout and shuffle, and its words: the cards, kind by kind within each deck, then a
     * top-down Fisher-Yates on SplittableRandom(mix(seed, shoe, stream.cards)), are ShoeRun's
     * shoes, for the main seed and others.
     */
    @Test
    public void theShuffleIsTheEngines() {
        int[] layout = code.layout();
        for (int i = 0; i < ShoeRun.CARDS; i++) {
            assertEquals((i % 52) / 2, layout[i], "card " + i + " before the shuffle");
        }
        long main = longConstant("seed.main");
        for (long seed : new long[]{main, 20261007L, 20261008L, 0, -5}) {
            ShoeRun run = new ShoeRun(seed, 0.5, 0, intConstant("cut.80"), RULES, CHART);
            for (int shoe : new int[]{0, 1, 2, 3, 59_999, 1 << 20}) {
                int[] engine = run.shuffled(shoe);
                assertArrayEquals(engine, code.shuffled(seed, shoe), seed + " shoe " + shoe);
                int[] words = layout.clone();
                SplittableRandom rnd = new SplittableRandom(mixFromConstants(seed, shoe, intConstant("stream.cards")));
                for (int i = words.length - 1; i > 0; i--) {
                    int j = rnd.nextInt(i + 1);
                    int t = words[i];
                    words[i] = words[j];
                    words[j] = t;
                }
                assertArrayEquals(engine, words, seed + " shoe " + shoe);
            }
        }
    }

    /** A3's u and q, and its tally of the cards left, are ShoeRun's to the last bit. */
    @Test
    public void qIsTheEnginesToTheBit() {
        SplittableRandom rnd = new SplittableRandom(11);
        double q0 = doubleConstant("q0");
        double u0 = doubleConstant("u0");
        for (int t = 0; t < 50_000; t++) {
            int[] dealt = new int[ShoeRun.KINDS];
            int cards = t == 0 ? 0 : t == 1 ? ShoeRun.CARDS : rnd.nextInt(ShoeRun.CARDS + 1);
            int[] shoe = ShoeRun.CARDS == cards ? null : code.shuffled(rnd.nextLong(), 0);
            for (int i = 0; i < cards; i++) {
                dealt[shoe == null ? i % ShoeRun.KINDS : shoe[i]]++;
            }
            int[] left = ShoeRun.ranksLeft(dealt);
            assertArrayEquals(left, code.ranksLeft(dealt));
            assertEquals(Double.doubleToRawLongBits(ShoeRun.unusualness(left)),
                    Double.doubleToRawLongBits(code.unusualness(left)), Arrays.toString(left));
            for (double[] rates : new double[][]{{q0, u0}, {0.05, 0.05}, {0.3, 0}, {1, 1e-9}}) {
                assertEquals(Double.doubleToRawLongBits(ShoeRun.rate(left, rates[0], rates[1])),
                        Double.doubleToRawLongBits(code.rate(left, rates[0], rates[1])), Arrays.toString(left));
            }
        }
    }

    /**
     * A3's words on the valuation draw: one nextDouble per round start from
     * SplittableRandom(mix(seed, shoe, stream.valuation)), valued when it is below q.
     */
    @Test
    public void theValuationDrawIsTheEngines() {
        long seed = longConstant("seed.main");
        ShoeRun run = new ShoeRun(seed, doubleConstant("q0"), doubleConstant("u0"), intConstant("cut.80"), RULES, CHART);
        ShoeRun all = new ShoeRun(seed, 0.3, 0, intConstant("cut.80"), RULES, CHART);
        for (int shoe = 0; shoe < 300; shoe++) {
            for (ShoeRun r : new ShoeRun[]{run, all}) {
                SplittableRandom draws = new SplittableRandom(mixFromConstants(seed, shoe, intConstant("stream.valuation")));
                for (ShoeRun.State s : r.deal(shoe)) {
                    assertEquals(draws.nextDouble() < s.q, s.valued, "shoe " + shoe + " round " + s.round);
                }
            }
        }
    }

    /**
     * Every round start of the 60,000 main shoes, from A3's walk with the engine's round, is
     * ShoeRun's: depth, tallies, q to the bit and the valuation. The walk's totals are A2's and
     * A8's, and A4's prose takes the same cards as ConcreteRound in every round.
     */
    @Test
    public void everyRoundStartOfTheMainShoesIsTheEngines() {
        long seed = longConstant("seed.main");
        double q0 = doubleConstant("q0");
        double u0 = doubleConstant("u0");
        int[] cuts = {intConstant("cut.70"), intConstant("cut.75"), intConstant("cut.80")};
        ShoeRun run = new ShoeRun(seed, q0, u0, cuts[2], RULES, CHART);
        long[] rounds = new long[3];
        long[] valued = new long[3];
        double[] weight = new double[3];
        long[] proseMisses = {0};
        String[] firstMiss = {null};
        ToIntBiFunction<int[], Integer> bothRounds = (ranks, from) -> {
            int engine = engineRoundEnd(ranks, from);
            int prose = proseRoundEnd(ranks, from);
            if (prose != engine) {
                proseMisses[0]++;
                if (firstMiss[0] == null) {
                    firstMiss[0] = "the round at card " + from + " of " + Arrays.toString(ranks) + " ends at " + engine
                            + ", A4's prose says " + prose;
                }
            }
            return engine;
        };
        int shoes = intConstant("shoes.main");
        for (int shoe = 0; shoe < shoes; shoe++) {
            List<ShoeRun.State> spec = code.walk(seed, shoe, cuts[2], q0, u0, bothRounds);
            String d = difference(spec, run.deal(shoe));
            assertNull(d, d);
            for (ShoeRun.State s : spec) {
                for (int p = 0; p < 3; p++) {
                    if (s.depth < cuts[p]) {
                        rounds[p]++;
                        if (s.valued) {
                            valued[p]++;
                            weight[p] += 1 / s.q;
                        }
                    }
                }
            }
        }
        assertEquals(0, proseMisses[0], firstMiss[0]);
        String[] pens = {"70", "75", "80"};
        for (int p = 0; p < 3; p++) {
            assertEquals(longConstant("rounds." + pens[p]), rounds[p], pens[p] + "%");
            assertEquals(longConstant("valued." + pens[p]), valued[p], pens[p] + "%");
            assertEquals(constant("weight." + pens[p]), String.format("%.0f", weight[p]), pens[p] + "%");
        }
    }

    /** A8's table says what A2's block says. */
    @Test
    public void theTableOfTargetsIsTheConstants() {
        String[] pens = {"70", "75", "80"};
        for (String pen : pens) {
            String row = null;
            for (String line : spec.split("\n")) {
                if (line.startsWith("| " + pen + "% | ")) {
                    assertNull(row, "two rows for " + pen + "%");
                    row = line;
                }
            }
            assertNotNull(row, "A8 has no row for " + pen + "%");
            String[] f = row.replace(",", "").split("\\|");
            assertEquals(constant("cut." + pen), f[2].trim(), row);
            assertEquals(constant("rounds." + pen), f[3].trim(), row);
            assertEquals(constant("valued." + pen), f[4].trim(), row);
            assertEquals(constant("weight." + pen), f[5].trim(), row);
        }
    }

    /**
     * chart.tsv's order (B1) is BasicStrategy.fingerprint's, and the fingerprint in A2 is the
     * chart's. A4 looks a decision up without the hand's number of cards beyond whether it is a
     * first decision, which holds because the chart's answer does not depend on it.
     */
    @Test
    public void theChartsOrderAndFingerprintAreTheEngines() {
        int[][] order = code.chartDecisions();
        assertEquals(intConstant("chart.decisions"), order.length);
        int[] moves = new int[order.length];
        for (int n = 0; n < order.length; n++) {
            int[] d = order[n];
            boolean first = d[5] == 1;
            RoundPolicy.Decision decision = decision(d, first ? 2 : 3);
            moves[n] = CHART.choose(decision).ordinal();
            for (int cards = 2; cards <= 12; cards++) {
                assertEquals(CHART.choose(decision), CHART.choose(decision(d, cards)), decision.toString());
            }
        }
        String engine = BasicStrategy.fingerprint(CHART);
        assertEquals(engine, code.fingerprint(moves));
        assertEquals(engine, constant("chart.fingerprint"));
        assertEquals(Arrays.asList(PlayerMove.Stand, PlayerMove.Hit, PlayerMove.Double, PlayerMove.Split,
                PlayerMove.Surrender), Arrays.asList(PlayerMove.values()), "B1's move numbers are the ordinals");
    }

    private static RoundPolicy.Decision decision(int[] d, int cards) {
        return new RoundPolicy.Decision(d[1], d[2] == 1, cards, d[3], d[0], d[4] == 1, d[5] == 1, (d[6] & 1) != 0,
                (d[6] & 2) != 0, (d[6] & 4) != 0, (d[6] & 8) != 0);
    }

    /** A9's portable generator is the JDK's SplittableRandom on every call the shoes make. */
    @Test
    public void thePortableGeneratorIsTheJdks() throws Exception {
        Class<?> c = code.loader.loadClass("PortableSplittableRandom");
        java.lang.reflect.Constructor<?> make = c.getDeclaredConstructor(long.class);
        make.setAccessible(true);
        Method nextInt = c.getDeclaredMethod("nextInt", int.class);
        Method nextLong = c.getDeclaredMethod("nextLong");
        Method nextDouble = c.getDeclaredMethod("nextDouble");
        for (Method m : new Method[]{nextInt, nextLong, nextDouble}) {
            m.setAccessible(true);
        }
        SplittableRandom seeds = new SplittableRandom(13);
        long main = longConstant("seed.main");
        for (int t = 0; t < 400; t++) {
            long seed = t < 4 ? new long[]{0, -1, Long.MIN_VALUE, Long.MAX_VALUE}[t]
                    : t < 200 ? ShoeRun.mix(main, t, t % 2) : seeds.nextLong();
            SplittableRandom jdk = new SplittableRandom(seed);
            Object port = make.newInstance(seed);
            for (int bound = 1; bound <= ShoeRun.CARDS; bound++) {
                assertEquals(jdk.nextInt(bound), (int) nextInt.invoke(port, bound), "seed " + seed + " bound " + bound);
            }
            for (int k = 0; k < 50; k++) {
                assertEquals(Double.doubleToRawLongBits(jdk.nextDouble()),
                        Double.doubleToRawLongBits((double) nextDouble.invoke(port)), "seed " + seed);
                assertEquals(jdk.nextLong(), (long) nextLong.invoke(port), "seed " + seed);
            }
            int big = 1 << 30 | seeds.nextInt(1 << 30);
            assertEquals(jdk.nextInt(big), (int) nextInt.invoke(port, big), "seed " + seed + " bound " + big);
        }
    }

    /**
     * The comparison is not blind: each of these changes to A3's code, one at a time, makes the
     * spec's shoes differ from ShoeRun's within the first 300 shoes, and is reported. Each is a
     * mistake a reader of the spec could make, or an edit to the spec that would mislead one.
     */
    @Test
    public void plantedDivergencesAreCaught() throws Exception {
        String shoeSpec = sources.get("ShoeSpec.java");
        String[][] plants = {
                {"a mix constant", "seed * 0x9E3779B97F4A7C15L", "seed * 0x9E3779B97F4A7C17L"},
                {"the finalizer's first shift", "(z ^ (z >>> 30))", "(z ^ (z >>> 31))"},
                {"the shuffle from the bottom up",
                        "for (int i = CARDS - 1; i > 0; i--) {\n            int j = rnd.nextInt(i + 1);",
                        "for (int i = 0; i < CARDS - 1; i++) {\n            int j = i + rnd.nextInt(CARDS - i);"},
                {"the layout by kind across decks",
                        "for (int d = 0; d < DECKS; d++) {\n            for (int k = 0; k < KINDS; k++) {",
                        "for (int k = 0; k < KINDS; k++) {\n            for (int d = 0; d < DECKS; d++) {"},
                {"the valuation on the cards' stream", "mix(seed, shoe, 1)", "mix(seed, shoe, 0)"},
                {"u divided by N as a product", "return u / n;", "return u * (1.0 / n);"},
                {"a round at the cut card", "while (depth < cut)", "while (depth <= cut)"},
                {"the round's own cards counted at its start",
                        "starts.add(new Start(shoe, round, depth, dealt.clone(), q, valued));\n            int next = roundEnd.applyAsInt(ranks, depth);\n            for (int i = depth; i < next; i++) {\n                dealt[cards[i]]++;\n            }",
                        "int next = roundEnd.applyAsInt(ranks, depth);\n            for (int i = depth; i < next; i++) {\n                dealt[cards[i]]++;\n            }\n            starts.add(new Start(shoe, round, depth, dealt.clone(), q, valued));"},
                {"the hole card left out of the count",
                        "for (int i = depth; i < next; i++) {\n                dealt[cards[i]]++;",
                        "for (int i = depth; i < next; i++) {\n                if (i == depth + 3) continue;\n                dealt[cards[i]]++;"},
        };
        long seed = longConstant("seed.main");
        assertNull(firstDifference(code, seed, 0, 300), "the spec as written");
        for (String[] plant : plants) {
            assertEquals(1, count(shoeSpec, plant[1]), "the plant of " + plant[0] + " finds its text once in A3");
            Map<String, String> planted = new LinkedHashMap<>(sources);
            planted.put("ShoeSpec.java", shoeSpec.replace(plant[1], plant[2]));
            Code mutant = new Code(compile(planted));
            assertNotNull(firstDifference(mutant, seed, 0, 300), plant[0] + " is not seen");
        }
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            n++;
        }
        return n;
    }
}
