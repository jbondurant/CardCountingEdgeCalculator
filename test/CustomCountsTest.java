import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Counts of our own as files list them, and the rules that keep the main file to one look
 * (COUNT_DESIGN.md, sections 4 to 6): the formats read to the bit, every refusal refuses, a run's
 * identity names the tags, and a count's own betting vector reaches every place that scores a
 * list of counts.
 */
public class CustomCountsTest {

    static final String CANDIDATES = "{ \"kind\": \"candidates\", \"K\": 10000, \"counts\": [\n"
            + "  { \"name\": \"F1 start\", \"family\": \"F1\", \"role\": \"candidate\", \"form\": \"true\", \"divisor\": 500,\n"
            + "    \"tags\": [-10000, 10000, 10000, 10000, 10000, 10000, 0, 0, 0, -10000] },\n"
            + "  { \"name\": \"F2 start\", \"family\": \"F2\", \"form\": \"true\", \"divisor\": 500,\n"
            + "    \"tags\": [0, 10000, 10000, 20000, 20000, 10000, 10000, 0, 0, -20000],\n"
            + "    \"betting\": [-20000, 10000, 10000, 20000, 20000, 10000, 10000, 0, 0, -20000] },\n"
            + "  { \"name\": \"F1r start\", \"family\": \"F1r\", \"form\": \"running\", \"divisor\": 2250,\n"
            + "    \"tags\": [-9000, 11000, 11000, 11000, 11000, 11000, 1000, 1000, 1000, -9000] },\n"
            + "  { \"name\": \"Hi-Opt II at g\", \"role\": \"control\", \"catalog\": \"Hi-Opt II\", \"divisor\": 500 },\n"
            + "  { \"name\": \"Hi-Lo\", \"role\": \"control\", \"catalog\": \"Hi-Lo\" } ] }\n";

    static Path write(Path dir, String name, String text) throws Exception {
        Path f = dir.resolve(name);
        Files.write(f, text.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    /** A file's counts are the tags it holds, as whole numbers at K, nothing normalised or rounded. */
    @Test
    public void aCandidatesFileReadsToTheBit() throws Exception {
        Path dir = Files.createTempDirectory("custom-counts");
        CustomCounts.Listing l = CustomCounts.read(write(dir, "candidates.json", CANDIDATES));
        assertFalse(l.registered);
        assertEquals(5, l.entries.size());
        CountKeys.Count f1 = l.entries.get(0).count;
        assertEquals("F1", l.entries.get(0).family);
        assertEquals("candidate", l.entries.get(0).role);
        assertTrue(f1.trueCounted);
        assertEquals(500, f1.divisor());
        assertEquals(CountKeys.K, f1.scale);
        assertEquals(-10000, f1.rankTag(1));
        assertEquals(10000, f1.rankTag(2));
        assertEquals(-10000, f1.rankTag(10));
        assertNull(f1.betVector);
        assertNull(CountKeys.bettingVariant(f1));

        CountKeys.Count f2 = l.entries.get(1).count;
        assertEquals(-20000, f2.betVector[0]);
        assertEquals(-20000, f2.betVector[1]);
        assertEquals(0, f2.tag[0]);
        CountKeys.Count v = CountKeys.bettingVariant(f2);
        assertSame(f2, v.base);
        assertArrayEquals(f2.betVector, v.betTag);

        CountKeys.Count r = l.entries.get(2).count;
        assertFalse(r.trueCounted);
        assertEquals(2250, r.divisor());
        assertFalse(r.balanced(), "a running count's shift is its own");

        CountKeys.Count hiOpt = l.entries.get(3).count;
        assertEquals(500, hiOpt.divisor());
        assertTrue(hiOpt.systems.contains("Hi-Opt II"));
        CountKeys.Count side = CountKeys.bettingVariant(hiOpt);
        assertNotNull(side, "a control at g brings its side-count variant");
        assertEquals(500, side.divisor());

        CountKeys.Count hiLo = l.entries.get(4).count;
        assertEquals(1, hiLo.divisor());
        assertArrayEquals(named("Hi-Lo").tag, hiLo.tag);

        // Malformed files are refused, saying why.
        for (String bad : new String[]{
                CANDIDATES.replace("\"K\": 10000", "\"K\": 1000"),
                CANDIDATES.replace("\"candidates\"", "\"chosen\""),
                CANDIDATES.replace("-10000, 10000, 10000, 10000, 10000, 10000, 0, 0, 0, -10000",
                        "-10000, 10000, 10000, 10000, 10000, 10000, 0, 0, -10000"),
                CANDIDATES.replace("-10000, 10000, 10000, 10000, 10000, 10000, 0, 0, 0, -10000",
                        "-10000.5, 10000, 10000, 10000, 10000, 10000, 0, 0, 0, -10000"),
                CANDIDATES.replace("\"F2 start\"", "\"F1 start\""),
                CANDIDATES.replace("\"catalog\": \"Hi-Opt II\"", "\"catalog\": \"Hi-Opt IX\""),
                CANDIDATES.replace("\"form\": \"running\"", "\"form\": \"walking\""),
                CANDIDATES.replace("\"kind\": \"candidates\"", "\"kind\": \"registered\"")}) {
            assertThrows(IllegalArgumentException.class, () -> CustomCounts.read(write(dir, "bad.json", bad)), bad);
        }
    }

    private static CountKeys.Count named(String system) {
        for (CountKeys.Count c : CountKeys.catalog()) {
            if (c.systems.contains(system)) {
                return c;
            }
        }
        throw new IllegalArgumentException(system);
    }

    static String registered() {
        return CANDIDATES.replace("\"kind\": \"candidates\"", "\"kind\": \"registered\", \"twin\": \"runs/twin-80.states, "
                + "seed 20261007\", \"searchCommit\": \"abc\", \"cells\": {\"80% 1-8\": \"F1 start\"}");
    }

    /** A small state file with the seed given, for the refusals that read a header. */
    static Path stateFile(Path dir, long seed) throws Exception {
        Path f = dir.resolve("seed-" + seed + ".states");
        StateStore.Header h = new StateStore.Header(seed, 0.008, 0.02, 332,
                StateRun.describe(StateRun.montreal(), BasicStrategy.chart()));
        StateStore.open(f, h);
        return f;
    }

    /** The search, calibration and theory-pass tools refuse the main file, by its header's seed. */
    @Test
    public void theSearchToolsRefuseTheMainFile() throws Exception {
        Path dir = Files.createTempDirectory("seeds");
        Path main = stateFile(dir, CustomCounts.MAIN_SEED);
        Path twin = stateFile(dir, CustomCounts.TWIN_SEED);
        assertTrue(CustomCounts.isMain(main));
        assertFalse(CustomCounts.isMain(twin));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CustomCounts.requireTwin(main));
        assertTrue(e.getMessage().contains("main state file"));
        CustomCounts.requireTwin(twin);
    }

    /** No twin shoe's generators, the cards' or the valuation's, share a seed with a main-file shoe's. */
    @Test
    public void theTwinsShoeSeedsCollideWithNoneOfTheMainFiles() {
        int shoes = 60_000;
        Set<Long> main = new HashSet<>();
        for (int s = 0; s < shoes; s++) {
            for (int stream = 0; stream < 2; stream++) {
                main.add(ShoeRun.mix(CustomCounts.MAIN_SEED, s, stream));
            }
        }
        assertEquals(2 * shoes, main.size());
        for (int s = 0; s < shoes; s++) {
            for (int stream = 0; stream < 2; stream++) {
                assertFalse(main.contains(ShoeRun.mix(CustomCounts.TWIN_SEED, s, stream)), "twin shoe " + s);
            }
        }
    }

    /**
     * A main-file tool refuses a candidates file, a registration not committed or changed since,
     * and a ledger with lines not committed; given all committed, it writes one ledger line,
     * which must be committed before the next look. Checked in a git repository of its own.
     */
    @Test
    public void theMainFileIsLookedAtOnlyThroughACommittedRegistration() throws Exception {
        Path repo = Files.createTempDirectory("one-look");
        assertNotNull(CustomCounts.git(repo, "init", "-q"));
        CustomCounts.git(repo, "config", "user.email", "test@example.com");
        CustomCounts.git(repo, "config", "user.name", "test");
        Path counts = write(repo, CustomCounts.REGISTERED, registered());
        CustomCounts.Listing l = CustomCounts.read(counts);
        assertTrue(l.registered);
        assertEquals("abc", l.searchCommit);

        CustomCounts.Listing candidates = CustomCounts.read(write(repo, "candidates.json", CANDIDATES));
        assertThrows(IllegalStateException.class, () -> CustomCounts.checkOnMain(candidates));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CustomCounts.checkOnMain(l));
        assertTrue(e.getMessage().contains("not committed"), e.getMessage());

        Path ledger = repo.resolve(CustomCounts.LEDGER);
        Files.write(ledger, (CustomCounts.LEDGER_HEADER + "\n").getBytes(StandardCharsets.UTF_8));
        CustomCounts.git(repo, "add", CustomCounts.REGISTERED);
        CustomCounts.git(repo, "commit", "-q", "-m", "register");
        e = assertThrows(IllegalStateException.class, () -> CustomCounts.checkOnMain(l), "the ledger is not committed");
        assertTrue(e.getMessage().contains(CustomCounts.LEDGER), e.getMessage());
        CustomCounts.git(repo, "add", CustomCounts.LEDGER);
        CustomCounts.git(repo, "commit", "-q", "-m", "ledger");

        String blob = CustomCounts.lookOnMain(l, "deadbeef", "a test look");
        assertEquals(CustomCounts.git(repo, "rev-parse", "HEAD:" + CustomCounts.REGISTERED), blob);
        List<String> lines = Files.readAllLines(ledger);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).startsWith("deadbeef\t" + blob + "\t"));
        assertTrue(lines.get(1).endsWith("\ta test look"));
        e = assertThrows(IllegalStateException.class, () -> CustomCounts.lookOnMain(l, "deadbeef", "a second look"),
                "the first look's line is not committed");
        assertTrue(e.getMessage().contains(CustomCounts.LEDGER));
        assertEquals(2, Files.readAllLines(ledger).size(), "a refused look writes nothing");
        CustomCounts.git(repo, "commit", "-q", "-am", "first look");
        CustomCounts.lookOnMain(l, "deadbeef", "a second look");
        CustomCounts.git(repo, "commit", "-q", "-am", "second look");

        // Changed after its commit: refused until committed, and then the hash is the new one.
        Files.write(counts, registered().replace("\"abc\"", "\"abd\"").getBytes(StandardCharsets.UTF_8));
        e = assertThrows(IllegalStateException.class, () -> CustomCounts.checkOnMain(CustomCounts.read(counts)));
        assertTrue(e.getMessage().contains("not committed"), e.getMessage());
        CustomCounts.git(repo, "commit", "-q", "-am", "a second registration");
        assertNotEquals(blob, CustomCounts.checkOnMain(CustomCounts.read(counts)));
    }

    /**
     * A run's identity names every count's tags, divisor, betting vector and form, so a resume
     * with any one of them changed is refused, saying what differs.
     */
    @Test
    public void aResumeWithAnyTagChangedIsRefused() throws Exception {
        Path dir = Files.createTempDirectory("identity");
        Path states = write(dir, "x.states", "states");
        int[] tags = {0, -10000, 10000, 10000, 10000, 10000, 10000, 0, 0, 0, -10000};
        int[] bet = tags.clone();
        bet[1] = -20000;
        CountKeys.Count base = CountKeys.whole("F", tags, bet, 500, true);
        List<CountKeys.Count> changed = new ArrayList<>();
        int[] t2 = tags.clone();
        t2[7] = 1;
        changed.add(CountKeys.whole("F", t2, bet, 500, true));
        changed.add(CountKeys.whole("F", tags, bet, 501, true));
        int[] b2 = bet.clone();
        b2[10] = -10001;
        changed.add(CountKeys.whole("F", tags, b2, 500, true));
        changed.add(CountKeys.whole("F", tags, null, 500, true));
        changed.add(CountKeys.whole("F", tags, bet, 500, false));
        String id = FullIndexRun.identity("c", states, 10, 20, 5, 2, Collections.singletonList(base), new int[]{2},
                "custom-counts.json, blob 123");
        assertTrue(id.contains("divisor 500"));
        assertTrue(id.contains(Arrays.toString(base.tag)));
        assertTrue(id.contains(Arrays.toString(base.betVector)));
        assertTrue(id.contains("blob 123"));
        new FullIndexCheckpoint(dir.resolve("run"), id).open(false);
        new FullIndexCheckpoint(dir.resolve("run"), id).open(true);
        for (CountKeys.Count c : changed) {
            String other = FullIndexRun.identity("c", states, 10, 20, 5, 2, Collections.singletonList(c), new int[]{2},
                    "custom-counts.json, blob 123");
            assertNotEquals(id, other);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new FullIndexCheckpoint(dir.resolve("run"), other).open(true));
            assertTrue(e.getMessage().contains("was: count F: "), e.getMessage());
        }
        String blob = FullIndexRun.identity("c", states, 10, 20, 5, 2, Collections.singletonList(base), new int[]{2},
                "custom-counts.json, blob 124");
        assertThrows(IllegalStateException.class, () -> new FullIndexCheckpoint(dir.resolve("run"), blob).open(true));
        String pens = FullIndexRun.identity("c", states, 10, 20, 5, 2, Collections.singletonList(base), new int[]{1, 2},
                "custom-counts.json, blob 123");
        assertThrows(IllegalStateException.class, () -> new FullIndexCheckpoint(dir.resolve("run"), pens).open(true));
        assertArrayEquals(new int[]{0, 2}, FullIndexRun.pens("0,2"));
        assertThrows(IllegalArgumentException.class, () -> FullIndexRun.pens("2,1"));
        assertThrows(IllegalArgumentException.class, () -> FullIndexRun.pens("3"));
    }

    // ------------------------------------------------------------ the betting vector is never dropped

    /**
     * A count with a betting vector of its own (F2), the play Hi-Lo's and the bets another count's,
     * scored in ComparisonReport and in both of FullIndexFit's scoring paths: each Result holds
     * its betting variant beside it, playing as it does, and the variant's bet keys are its
     * betting vector's, not its play count's.
     */
    @Test
    public void theBettingVectorIsNeverDropped() throws Exception {
        FullIndexFitTest.Fixture fx = FullIndexFitTest.standard();
        ScoringStates sub = fx.st.subRun(0, 2);
        SystemScorer scorer = new SystemScorer(sub, new Bootstrap(sub.shoes, 3, 1), 1);
        int[] play = {0, -CountKeys.K, CountKeys.K, CountKeys.K, CountKeys.K, CountKeys.K, CountKeys.K, 0, 0, 0,
                -CountKeys.K};
        int[] bet = {0, -3 * CountKeys.K, 0, CountKeys.K, CountKeys.K, 2 * CountKeys.K, CountKeys.K, 0, -CountKeys.K, 0,
                -CountKeys.K};
        CountKeys.Count f2 = CountKeys.whole("F2", play, bet, CountKeys.K / 4, true);
        CountKeys.Count v = CountKeys.bettingVariant(f2);
        int p = SystemScorer.CUTS.length - 1;
        SystemScorer.Pen pen = scorer.pen(p);
        int[] own = scorer.betKeys(v, pen).value;
        int[] plain = scorer.betKeys(f2, pen).value;
        assertFalse(Arrays.equals(own, plain), "the fixture's states tell the two vectors apart");

        SystemScorer.Result report = ComparisonReport.scoreAll(scorer, Collections.singletonList(f2), 1).get(0);
        bettingKept(report.entries, f2, own, plain, p);

        FullIndexFit.Scored batched = FullIndexFit.scoreAll(scorer, FullIndexFit.built(sub), Collections.singletonList(f2),
                new int[]{p}, 1, 2, false, false, null, null);
        bettingKept(batched.results.get(0).entries, f2, own, plain, p);
        bettingKept(batched.results.get(0).fullEntries, f2, own, plain, p);

        FullIndexFit.Scored joint = FullIndexFit.scoreJoint(scorer, FullIndexFit.built(sub),
                Collections.singletonList(f2), new int[]{p}, 1, 2, false, false, null, null);
        bettingKept(joint.results.get(0).entries, f2, own, plain, p);
        bettingKept(joint.results.get(0).fullEntries, f2, own, plain, p);
        // The joint run scores what the batched run scores.
        for (int e = 0; e < 4; e++) {
            assertArrayEquals(batched.results.get(0).entries.get(e).estimate[p], joint.results.get(0).entries.get(e).estimate[p],
                    1e-12);
        }
    }

    /** Entries of a count with a betting vector: the count, then its variant, each betting on its own keys. */
    private static void bettingKept(List<SystemScorer.Entry> entries, CountKeys.Count f2, int[] own, int[] plain, int p) {
        List<SystemScorer.Entry> playing = new ArrayList<>();
        for (SystemScorer.Entry e : entries) {
            if (!e.bettingOnly) {
                playing.add(e);
            }
        }
        assertEquals(2, playing.size(), "the count and its betting variant");
        assertSame(f2, playing.get(0).count);
        assertSame(f2, playing.get(1).count.base);
        assertArrayEquals(f2.betVector, playing.get(1).count.betTag);
        assertArrayEquals(plain, keysOf(playing.get(0).keys[p]));
        assertArrayEquals(own, keysOf(playing.get(1).keys[p]));
    }

    private static int[] keysOf(double[][] table) {
        int[] out = new int[table.length];
        for (int k = 0; k < out.length; k++) {
            out[k] = (int) table[k][0];
        }
        return out;
    }
}
