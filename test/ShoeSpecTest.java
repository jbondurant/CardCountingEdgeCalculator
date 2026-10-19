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
 * plantedDivergencesAreCaught compiles the spec's code with one deliberate change at a time and
 * requires the comparison to see each, so a comparison that had gone blind would fail.
 *
 * Part B of the spec describes files no code writes yet, so the files are not tested here; but
 * what Part B says about the engine is (the key formulas, the group lookup, the figure numbers,
 * the deal and cell orders, how split hands read a full-play file), each against a transcription
 * of the spec's words, since the simulator will be written from those words.
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
                    firstMiss[0] = "the round at card " + from + " (ranks from there "
                            + Arrays.toString(Arrays.copyOfRange(ranks, from, Math.min(ranks.length, from + 24)))
                            + ") ends at " + engine + ", A4's prose says " + prose;
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
     * chart's. B1 says the chart's answer depends neither on the hand's number of cards beyond
     * whether it is a first decision nor on whether the hand came from a split, which lets a
     * full-play file's split hands read the chart's later play (B2.4); both are checked here.
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
            int[] otherSplit = d.clone();
            otherSplit[4] = 1 - d[4];
            assertEquals(CHART.choose(decision), CHART.choose(decision(otherSplit, first ? 2 : 3)), decision.toString());
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

    // ------------------------------------------------------------------ Part B's claims about the engine

    /** B2.2's rounding, transcribed: the double nearest the ratio, to nearest, ties toward zero. */
    static int b22Round(long n, long d) {
        double x = (double) n / (double) d;
        return (int) (Math.signum(x) * Math.ceil(Math.abs(x) - 0.5));
    }

    /** B2.2's bet key, transcribed from its words and the file's parameters, as the simulator will. */
    static int b22BetKey(CountKeys.Count c, int[] dealt, int depth) {
        long rcb = 0;
        int aces = 2 * 2 * ShoeRun.DECKS - dealt[0] - dealt[1];
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            rcb += (long) dealt[k] * c.betTags()[k];
        }
        long d = c.divisor();
        long left = ShoeRun.CARDS - depth;
        if (!c.trueCounted) {
            return d == 1 ? (int) rcb : b22Round(rcb, d);
        }
        return b22Round(52 * rcb - (long) c.betDeckSum() * depth + (long) c.aceSideCount * (52L * aces - 4 * left),
                left * d);
    }

    /** B2.2's key at a decision, s cards of the round seen, their tags adding to t. */
    static int b22Key(CountKeys.Count c, int[] dealt, int depth, int t, int s) {
        long rc = 0;
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            rc += (long) dealt[k] * c.tag[k];
        }
        long d = c.divisor();
        if (!c.trueCounted) {
            return d == 1 ? (int) (rc + t) : b22Round(rc + t, d);
        }
        return b22Round(52 * (rc + t) - (long) c.deckSum * (depth + s), (ShoeRun.CARDS - depth - s) * d);
    }

    /** Every count the comparison scores, every betting variant, and counts of our own at several divisors. */
    static List<CountKeys.Count> everyKindOfCount() {
        List<CountKeys.Count> out = new ArrayList<>(CountKeys.catalog());
        out.add(CountKeys.nullCount());
        for (CountKeys.Count c : new ArrayList<>(out)) {
            CountKeys.Count v = CountKeys.sideCountVariant(c);
            if (v != null) {
                out.add(v);
            }
        }
        SplittableRandom rnd = new SplittableRandom(17);
        for (int d : new int[]{1, 7, 250, 10_000}) {
            for (boolean trueCounted : new boolean[]{true, false}) {
                int[] tag = new int[11];
                int[] bet = new int[11];
                for (int r = 1; r <= 10; r++) {
                    tag[r] = rnd.nextInt(-20_000, 20_001);
                    bet[r] = rnd.nextInt(-20_000, 20_001);
                }
                CountKeys.Count own = CountKeys.whole("own " + d + " " + trueCounted, tag, bet, d, trueCounted);
                out.add(own);
                out.add(CountKeys.bettingVariant(own));
            }
        }
        return out;
    }

    /**
     * B2.1's tag sums and B2.2's keys, transcribed, are CountKeys' for every count the comparison
     * scores, every betting variant and counts of our own at four divisors, on tallies of real
     * shoes at every depth and on 3 to 9 cards of the round seen.
     */
    @Test
    public void theKeysOfB2AreCountKeys() {
        List<CountKeys.Count> counts = everyKindOfCount();
        assertTrue(counts.size() >= 31 + 8, counts.size() + " counts");
        SplittableRandom rnd = new SplittableRandom(19);
        for (int t = 0; t < 400; t++) {
            int[] shoe = code.shuffled(rnd.nextLong(), t);
            int depth = t == 0 ? 0 : rnd.nextInt(ShoeRun.CARDS - 12);
            int[] dealt = new int[ShoeRun.KINDS];
            for (int i = 0; i < depth; i++) {
                dealt[shoe[i]]++;
            }
            int seen = 3 + rnd.nextInt(7);
            for (CountKeys.Count c : counts) {
                int deckSum = 0;
                int betDeckSum = 0;
                for (int k = 0; k < ShoeRun.KINDS; k++) {
                    deckSum += 2 * c.tag[k];
                    betDeckSum += 2 * c.betTags()[k];
                }
                assertEquals(c.deckSum, deckSum, c.name);
                assertEquals(c.betDeckSum(), betDeckSum, c.name);
                assertEquals(c.betKey(dealt, depth), b22BetKey(c, dealt, depth), c.name + " at depth " + depth);
                int tagSum = 0;
                for (int i = 0; i < seen; i++) {
                    tagSum += c.tag[shoe[depth + i]];
                    if (i >= 2) {
                        int rc = c.runningCount(dealt);
                        assertEquals(c.keyAfter(rc, depth, tagSum, i + 1), b22Key(c, dealt, depth, tagSum, i + 1),
                                c.name + " at depth " + depth + ", " + (i + 1) + " seen");
                        if (i == 2) {
                            assertEquals(c.playKey(rc, depth, tagSum), b22Key(c, dealt, depth, tagSum, 3), c.name);
                        }
                    }
                }
            }
        }
    }

    /** B2.3's lookup, transcribed: the first group whose hi is at least the key, the last group above. */
    static int b23Group(int[] hi, int key) {
        for (int g = 0; g < hi.length; g++) {
            if (key <= hi[g]) {
                return g;
            }
        }
        return hi.length - 1;
    }

    /** B2.3's group lookup is SystemScorer.Groups.of, below, inside and above the training keys. */
    @Test
    public void theGroupsOfB2AreTheEngines() {
        SplittableRandom rnd = new SplittableRandom(23);
        for (int t = 0; t < 2_000; t++) {
            int groups = 1 + rnd.nextInt(12);
            int[] lo = new int[groups];
            int[] hi = new int[groups];
            int at = rnd.nextInt(-40, 0);
            for (int g = 0; g < groups; g++) {
                lo[g] = at;
                hi[g] = at + rnd.nextInt(4);
                at = hi[g] + 1 + rnd.nextInt(3);
            }
            SystemScorer.Groups engine = new SystemScorer.Groups(lo, hi);
            for (int key = lo[0] - 30; key <= hi[groups - 1] + 30; key++) {
                assertEquals(engine.of(key), b23Group(hi, key), "key " + key + " in " + Arrays.toString(hi));
            }
        }
    }

    /** B5's figure numbers are SystemScorer's metric indices. */
    @Test
    public void theFigureNumbersOfB5AreTheEngines() {
        assertEquals(0, SystemScorer.FLAT);
        assertEquals(1, SystemScorer.PLAY);
        assertEquals(2, SystemScorer.INSURANCE);
        assertEquals(51, SystemScorer.METRICS);
        for (int s = 1; s <= SystemScorer.SPREADS; s++) {
            assertEquals(2 + s, SystemScorer.same(s));
            assertEquals(10 + s, SystemScorer.sameBet(s));
            assertEquals(18 + s, SystemScorer.own(s));
            assertEquals(26 + s, SystemScorer.ownBet(s));
            assertEquals(34 + s, SystemScorer.played(s));
            assertEquals(42 + s, SystemScorer.playedBet(s));
        }
        assertEquals(8, SystemScorer.SPREADS);
    }

    /**
     * B2.1's counts and orders: 540 deals with a first decision, numbered up-card, then p1, then
     * p2; 110 contested spots, hard 12 to 16 and soft 13 to 18, ordered up-card, hard before
     * soft, then total, as FullIndexFit numbers its later cells.
     */
    @Test
    public void theDealsAndCellsOfB2AreTheEngines() {
        int n = 0;
        int withDecision = 0;
        for (int up = 1; up <= 10; up++) {
            for (int p1 = 1; p1 <= 10; p1++) {
                for (int p2 = p1; p2 <= 10; p2++) {
                    assertEquals(n, Deals.index(p1, p2, up));
                    boolean natural = p1 == 1 && p2 == 10;
                    assertEquals(natural, Deals.playerNatural(n));
                    withDecision += natural ? 0 : 1;
                    n++;
                }
            }
        }
        assertEquals(Deals.COUNT, n);
        assertEquals(540, withDecision);
        int cell = 0;
        for (int up = 1; up <= 10; up++) {
            for (boolean soft : new boolean[]{false, true}) {
                for (int total = 4; total <= 21; total++) {
                    boolean contested = soft ? total >= 13 && total <= 18 : total >= 12 && total <= 16;
                    assertEquals(contested, PlayerTree.contested(total, soft), total + " " + soft);
                    int spot = PlayerTree.spot(total, soft, up);
                    assertEquals(contested ? cell : -1, FullIndexFit.cellOf(spot));
                    if (contested) {
                        assertEquals(spot, FullIndexFit.spotOf(cell));
                        cell++;
                    }
                }
            }
        }
        assertEquals(110, cell);
        assertEquals(FullIndexFit.CELLS, cell);
    }

    /**
     * B2.4's order for a (group, deal) no training state reached, transcribed: the chart's move
     * with every move of the dealt hand offered, its move with Split taken away, then Stand.
     */
    @Test
    public void theChartsOrderOfB24IsFullIndexFits() {
        for (int i = 0; i < Deals.COUNT; i++) {
            if (Deals.playerNatural(i)) {
                continue;
            }
            List<Integer> hand = Arrays.asList(Deals.P1[i], Deals.P2[i]);
            int total = ConcreteRound.total(hand);
            boolean soft = ConcreteRound.soft(hand);
            int pair = Deals.P1[i] == Deals.P2[i] ? Deals.P1[i] : 0;
            PlayerMove all = CHART.choose(new RoundPolicy.Decision(total, soft, 2, pair, Deals.UP[i], false, true,
                    total < 21, true, pair != 0, RULES.surrender));
            PlayerMove noSplit = CHART.choose(new RoundPolicy.Decision(total, soft, 2, pair, Deals.UP[i], false, true,
                    total < 21, true, false, RULES.surrender));
            assertArrayEquals(new PlayerMove[]{all, noSplit, PlayerMove.Stand}, FullIndexFit.chartRanking(RULES, i),
                    "deal " + i);
        }
    }

    /**
     * B2.4's play of a full-play file's split hands, transcribed, is SplitPlay's on every decision
     * a split hand can face, with random later tables and random ranked lists, so that the rule
     * "the first move of the list that is legal, an ace and a ten standing" is tested as a rule.
     * The engine's split hands play the walked play's ranking (B2.4's kept play), random here; the
     * file's first lines are that ranking with some lists put in another order, as a refit ranks
     * them, and its split lines are the walked lists wherever the first line is not, as B7 writes
     * them. The transcription reads a deal's split line if the file has one and its first line if
     * not, and must give SplitPlay's answer over the walked ranking at every decision. Of these
     * tables' split-hand first decisions, 1,407 read a split line and 711 of those play otherwise
     * than their first line would. Planted on 19 October 2026 in scratch copies of this test, each
     * failed it: the words read as B2.4 read them before 18 October, the first line only (a split
     * hand of two cards, hard 6, against a 2: Hit by SplitPlay, Double by the words); and SplitPlay
     * given the first lines for the walked ranking, as if splits were valued under the refit's
     * (the same decision: Double by SplitPlay, Hit by the words).
     */
    @Test
    public void theSplitHandsOfB24AreSplitPlays() {
        SplittableRandom rnd = new SplittableRandom(29);
        PlayerMove[] moves = {PlayerMove.Stand, PlayerMove.Hit, PlayerMove.Double, PlayerMove.Split};
        int readSplit = 0;
        int differs = 0;
        for (int t = 0; t < 300; t++) {
            int r = 1 + rnd.nextInt(10);
            int up = 1 + rnd.nextInt(10);
            boolean[] stands = new boolean[FullIndexFit.CELLS];
            for (int c = 0; c < stands.length; c++) {
                stands[c] = rnd.nextBoolean();
            }
            PlayerMove[][] walked = new PlayerMove[Deals.COUNT][];
            PlayerMove[][] first = new PlayerMove[Deals.COUNT][];
            PlayerMove[][] split = new PlayerMove[Deals.COUNT][];
            for (int i = 0; i < Deals.COUNT; i++) {
                PlayerMove[] list = new PlayerMove[1 + rnd.nextInt(4)];
                for (int k = 0; k < list.length; k++) {
                    list[k] = moves[rnd.nextInt(moves.length)];
                }
                walked[i] = list;
                first[i] = list.clone();
                if (rnd.nextInt(3) == 0) {
                    for (int k = first[i].length - 1; k > 0; k--) {
                        int j = rnd.nextInt(k + 1);
                        PlayerMove m = first[i][k];
                        first[i][k] = first[i][j];
                        first[i][j] = m;
                    }
                }
                if (!Arrays.equals(first[i], walked[i])) {
                    split[i] = walked[i].clone();
                }
            }
            SplitPlay engine = new SplitPlay(r, up, (total, soft) -> {
                int c = FullIndexFit.cellOf(PlayerTree.spot(total, soft, up));
                return c < 0 ? PlayerTree.CHART.stands(total, soft, up, 0) : stands[c];
            }, deal -> walked[deal]);
            for (int x = 1; x <= 10; x++) {
                List<Integer> hand = Arrays.asList(r, x);
                int total = ConcreteRound.total(hand);
                boolean soft = ConcreteRound.soft(hand);
                for (int hands = 2; hands <= 4; hands++) {
                    boolean canHit = total < 21 && r != 1;
                    boolean canDouble = r != 1;
                    boolean canSplit = x == r && hands + 1 <= (r == 1 ? 2 : 4);
                    RoundPolicy.Decision d = new RoundPolicy.Decision(total, soft, 2, x == r ? r : 0, up, true, true,
                            canHit, canDouble, canSplit, false);
                    PlayerMove spec = PlayerMove.Stand;
                    if (!((r == 1 && x == 10) || (r == 10 && x == 1))) {
                        int deal = Deals.index(Math.min(r, x), Math.max(r, x), up);
                        PlayerMove[] list = split[deal] != null ? split[deal] : first[deal];
                        for (PlayerMove m : list) {
                            if (d.isLegal(m)) {
                                spec = m;
                                break;
                            }
                        }
                        readSplit += split[deal] != null ? 1 : 0;
                        PlayerMove byFirst = PlayerMove.Stand;
                        for (PlayerMove m : first[deal]) {
                            if (d.isLegal(m)) {
                                byFirst = m;
                                break;
                            }
                        }
                        differs += byFirst != spec ? 1 : 0;
                    }
                    assertEquals(engine.choose(d), spec, d.toString());
                }
            }
            for (int total = 4; total <= 21; total++) {
                for (boolean soft : new boolean[]{false, true}) {
                    if (r == 1 || (soft && total < 12)) {
                        continue;
                    }
                    RoundPolicy.Decision d = new RoundPolicy.Decision(total, soft, 3, 0, up, true, false, total < 21,
                            false, false, false);
                    int c = FullIndexFit.cellOf(PlayerTree.spot(total, soft, up));
                    PlayerMove spec = c >= 0 ? (d.canHit && !stands[c] ? PlayerMove.Hit : PlayerMove.Stand)
                            : CHART.choose(d);
                    assertEquals(engine.choose(d), spec, d.toString());
                }
            }
        }
        // The split lines are read, and where they are, reading the first line instead plays otherwise:
        // the counts the class comment gives, from seed 29.
        assertEquals(1407, readSplit, "first decisions that read a split line");
        assertEquals(711, differs, "first decisions a split line plays otherwise than its first line");
    }

    // ------------------------------------------------------------------ B9: the catalog as the simulator reads it

    /**
     * B9's words, transcribed with a JSON parser and nothing of the engine's reading: the
     * catalog's systems turned into tags by kind and a scale, merged into entries, and the null
     * count and the ace side-count variants made; every one equal to what CountKeys makes of the
     * same file (name, mode, scale, tags, divisor, bet tags and ace weight), and the list of
     * side-counted systems in the spec's block equal to CountKeys.SIDE_COUNTED.
     */
    @Test
    public void b9TheCatalogAsTheSpecReadsIt() throws Exception {
        org.bson.Document doc = org.bson.Document.parse(new String(Files.readAllBytes(Paths.get("counting-systems.json")),
                StandardCharsets.UTF_8));
        List<String> names = new ArrayList<>();
        List<List<String>> systems = new ArrayList<>();
        List<int[]> tags = new ArrayList<>();
        List<Integer> scales = new ArrayList<>();
        List<Boolean> modes = new ArrayList<>();
        String[] rank13 = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
        int read = 0;
        for (org.bson.Document d : doc.getList("systems", org.bson.Document.class)) {
            read++;
            String mode = d.getString("count");
            assertTrue(mode.equals("true") || mode.equals("running"), mode);
            double[] t = new double[ShoeRun.KINDS];
            org.bson.Document perRank = (org.bson.Document) d.get("tags");
            for (int k = 0; k < t.length; k++) {
                t[k] = ((Number) perRank.get(k / 2 >= 9 ? "T" : rank13[k / 2])).doubleValue();
            }
            org.bson.Document card = (org.bson.Document) d.get("cardTags");
            if (card != null) {
                for (String key : card.keySet()) {
                    String rank = key.replaceFirst("(red|black)$", "");
                    int r = Arrays.asList(rank13).indexOf(rank);
                    assertTrue(r >= 0, key);
                    for (int colour = 0; colour < 2; colour++) {
                        if (key.equals(rank) || key.endsWith(colour == 1 ? "red" : "black")) {
                            t[2 * r + colour] = ((Number) card.get(key)).doubleValue();
                        }
                    }
                }
            }
            int scale = 1;
            while (true) {
                boolean whole = true;
                for (double x : t) {
                    whole &= Math.abs(scale * x - Math.rint(scale * x)) <= 1e-9;
                }
                if (whole) {
                    break;
                }
                scale++;
                assertTrue(scale <= 100);
            }
            int[] w = new int[t.length];
            for (int k = 0; k < t.length; k++) {
                w[k] = (int) Math.rint(scale * t[k]);
            }
            int at = -1;
            for (int e = 0; e < tags.size(); e++) {
                if (at < 0 && Arrays.equals(tags.get(e), w) && modes.get(e) == mode.equals("true")) {
                    at = e;
                }
            }
            if (at < 0) {
                systems.add(new ArrayList<>());
                tags.add(w);
                scales.add(scale);
                modes.add(mode.equals("true"));
                at = tags.size() - 1;
            }
            systems.get(at).add(d.getString("name"));
        }
        for (List<String> x : systems) {
            names.add(String.join(" = ", x));
        }
        assertEquals(35, read);
        List<CountKeys.Count> catalog = CountKeys.catalog();
        assertEquals(30, names.size());
        assertEquals(names.size(), catalog.size());
        List<String> sideCounted = block(spec, "shoe-spec-side-counted");
        assertEquals(CountKeys.SIDE_COUNTED, sideCounted);
        int variants = 0;
        for (int e = 0; e < names.size(); e++) {
            CountKeys.Count c = catalog.get(e);
            assertEquals(names.get(e), c.name);
            assertEquals(modes.get(e), c.trueCounted, c.name);
            assertEquals((int) scales.get(e), c.scale, c.name);
            assertArrayEquals(tags.get(e), c.tag, c.name);
            assertArrayEquals(tags.get(e), c.betTags(), c.name);
            assertEquals(1, c.divisor(), c.name);
            assertEquals(0, c.aceSideCount, c.name);
            boolean listed = systems.get(e).stream().anyMatch(sideCounted::contains);
            CountKeys.Count v = CountKeys.bettingVariant(c);
            assertEquals(listed, v != null, c.name);
            if (listed) {
                variants++;
                assertEquals(c.name + " + ace side count", v.name);
                assertTrue(v.trueCounted);
                assertArrayEquals(c.tag, v.tag);
                assertArrayEquals(c.tag, v.betTags());
                assertEquals(c.scale, v.scale);
                assertEquals(Math.abs(tags.get(e)[18]), v.aceSideCount, v.name);
            }
        }
        assertEquals(8, variants);
        CountKeys.Count none = CountKeys.nullCount();
        assertEquals("Null count (no count)", none.name);
        assertArrayEquals(new int[ShoeRun.KINDS], none.tag);
        assertFalse(none.trueCounted);
        assertEquals(1, none.scale);
        assertEquals(1, none.divisor());
    }

    // ------------------------------------------------------------------ Part C: the simulator's files

    /**
     * Part C's formats as RiskReport reads them: the swings example read by readSwings (sums,
     * cells and both tests of the figure), a records file and a fan file written from C2's and
     * C3's words read by readRecords and readFan, and the ev-by-key example's lines of the field
     * counts C4 gives.
     */
    @Test
    public void partCIsWhatRiskReportReads(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Map<String, RiskReport.Swing> swings = RiskReport.readSwings(block(spec, "shoe-spec-swings"));
        assertEquals(java.util.Collections.singleton("F27"), swings.keySet());
        RiskReport.Swing f27 = swings.get("F27");
        assertNotNull(f27.sums);
        assertEquals(38_000_000L, f27.sums.shoes);
        assertEquals(2, f27.counts().length);
        assertEquals(-4, f27.twoXs()[0]);
        assertEquals(java.util.Set.of("P3", "P2"), f27.z.keySet());
        StringBuilder records = new StringBuilder();
        double[][] shoe = {{-3.5, 62, -12.0}, {8.0, 58, 0.0}, {-1.0, 61, -4.5}};
        for (double[] h : shoe) {
            records.append(h[0]).append('\t').append((int) h[1]).append('\t').append(h[2]).append('\n');
        }
        Path rec = dir.resolve("records-F27.tsv");
        Files.write(rec, records.toString().getBytes(StandardCharsets.UTF_8));
        RiskMath.Records r = RiskReport.readRecords(rec);
        assertArrayEquals(new double[]{-3.5, 8.0, -1.0}, r.y);
        assertArrayEquals(new int[]{62, 58, 61}, r.n);
        StringBuilder fan = new StringBuilder();
        for (int j = 0; j < 20; j++) {
            fan.append(j);
            for (int w = 1; w <= 1000; w++) {
                fan.append('\t').append((double) (j - w % 7));
            }
            fan.append('\n');
        }
        Path fanFile = dir.resolve("fan-F27.tsv");
        Files.write(fanFile, fan.toString().getBytes(StandardCharsets.UTF_8));
        double[][] paths = RiskReport.readFan(fanFile);
        assertEquals(20, paths.length);
        assertEquals(1000, paths[0].length);
        Map<String, Integer> fields = Map.of("sim", 11, "cross", 5, "exact", 6);
        int lines = 0;
        for (String l : block(spec, "shoe-spec-ev-by-key")) {
            if (l.startsWith("#")) {
                continue;
            }
            String[] f = l.split("\t", -1);
            assertEquals((int) fields.get(f[0]), f.length, l);
            lines++;
        }
        assertEquals(4, lines);
    }
}
