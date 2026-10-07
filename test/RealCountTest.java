import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Counts with real-valued tags, a key divisor and a betting vector (COUNT_DESIGN.md, sections 1
 * and 6): the published counts keep every key they had, to the bit; a betting vector holds every
 * ace side count exactly; and the keys do not move under the changes of tags that should not move
 * them.
 */
public class RealCountTest {

    private static final int STATES = 400;

    /** The keys of one count at one state, every one the scorer and the player tree ask for. */
    private static List<Long> keys(CountKeys.Count c, int[] dealt, int depth) {
        List<Long> out = new ArrayList<>();
        out.add((long) c.betKey(dealt, depth));
        out.add(Double.doubleToLongBits(c.trueCount(dealt, depth)));
        int rc = c.runningCount(dealt);
        out.add((long) rc);
        for (int i = 0; i < Deals.COUNT; i += 7) {
            for (double[] e : c.tagSums(dealt, i)) {
                out.add((long) c.playKey(rc, depth, (int) e[0]));
                out.add(Double.doubleToLongBits(e[1]));
                for (int seen = 4; seen <= 6; seen++) {
                    out.add((long) c.keyAfter(rc, depth, (int) e[0] + 3 * c.tag[20], seen));
                }
            }
        }
        return out;
    }

    private static int depth(Random rnd) {
        return rnd.nextInt(340);
    }

    private static double[] direction(Random rnd) {
        double[] u = new double[11];
        for (int r = 1; r <= 10; r++) {
            u[r] = rnd.nextGaussian();
        }
        return u;
    }

    // ------------------------------------------------------------ published counts unchanged

    /**
     * Every catalog count and side-count variant gives the keys c06c061's classes gave, the
     * classes that scored the published rows, to the bit, at every key and unrounded true count.
     */
    @Test
    public void everyPublishedCountKeysAsC06c061Did() throws Exception {
        ClassLoader old = C06c061.loader();
        Class<?> ck = old.loadClass("CountKeys");
        @SuppressWarnings("unchecked")
        List<Object> was = (List<Object>) C06c061.call(ck, "catalog", new Class<?>[0]);
        List<CountKeys.Count> now = CountKeys.catalog();
        assertEquals(was.size(), now.size());
        List<Object[]> pairs = new ArrayList<>();
        for (int k = 0; k < now.size(); k++) {
            assertEquals(C06c061.get(was.get(k), "name"), now.get(k).name);
            assertEquals(1, now.get(k).divisor());
            pairs.add(new Object[]{was.get(k), now.get(k)});
            Object wasSide = C06c061.call(ck, "sideCountVariant", new Class<?>[]{old.loadClass("CountKeys$Count")},
                    was.get(k));
            CountKeys.Count nowSide = CountKeys.bettingVariant(now.get(k));
            assertEquals(wasSide == null, nowSide == null, now.get(k).name);
            if (nowSide != null) {
                pairs.add(new Object[]{wasSide, nowSide});
            }
        }
        Random rnd = new Random(20261007);
        Class<?>[] dd = {int[].class, int.class};
        for (int n = 0; n < STATES; n++) {
            int depth = depth(rnd);
            int[] dealt = CountKeysTest.dealt(rnd, depth);
            for (Object[] pair : pairs) {
                Object o = pair[0];
                CountKeys.Count c = (CountKeys.Count) pair[1];
                assertEquals(C06c061.call(o, "betKey", dd, dealt, depth), c.betKey(dealt, depth), c.name);
                assertEquals(Double.doubleToLongBits((double) C06c061.call(o, "trueCount", dd, dealt, depth)),
                        Double.doubleToLongBits(c.trueCount(dealt, depth)), c.name);
                int rc = c.runningCount(dealt);
                assertEquals(C06c061.call(o, "runningCount", new Class<?>[]{int[].class}, dealt), rc);
                for (int i = 0; i < Deals.COUNT; i += 3) {
                    double[][] ts = (double[][]) C06c061.call(o, "tagSums", new Class<?>[]{int[].class, int.class},
                            dealt, i);
                    assertTrue(Arrays.deepEquals(ts, c.tagSums(dealt, i)), c.name);
                    for (double[] e : ts) {
                        for (int seen = 3; seen <= 6; seen++) {
                            int extra = (seen - 3) * c.tag[2 * (seen + 1)];
                            Class<?>[] four = {int.class, int.class, int.class, int.class};
                            assertEquals(C06c061.call(o, "keyAfter", four, rc, depth, (int) e[0] + extra, seen),
                                    c.keyAfter(rc, depth, (int) e[0] + extra, seen), c.name);
                        }
                    }
                }
            }
        }
    }

    /** A Count c06c061 wrote reads with this build's class: a divisor of 1, no betting vector, the same keys. */
    @Test
    public void aCountC06c061WroteReadsAsDivisorOneAndNoBettingVector() throws Exception {
        ClassLoader old = C06c061.loader();
        Class<?> ck = old.loadClass("CountKeys");
        @SuppressWarnings("unchecked")
        List<Object> was = (List<Object>) C06c061.call(ck, "catalog", new Class<?>[0]);
        List<CountKeys.Count> now = CountKeys.catalog();
        for (int k = 0; k < was.size(); k++) {
            List<Object> written = new ArrayList<>();
            written.add(was.get(k));
            Object side = C06c061.call(ck, "sideCountVariant", new Class<?>[]{old.loadClass("CountKeys$Count")},
                    was.get(k));
            if (side != null) {
                written.add(side);
            }
            for (Object o : written) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                    out.writeObject(o);
                }
                CountKeys.Count read;
                try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                    read = (CountKeys.Count) in.readObject();
                }
                assertEquals(1, read.divisor());
                assertNull(read.betTag);
                assertNull(read.betVector);
                CountKeys.Count fresh = o == was.get(k) ? now.get(k) : CountKeys.bettingVariant(now.get(k));
                assertEquals(fresh.name, read.name);
                assertEquals(fresh.kindAware, read.kindAware);
                assertEquals(fresh.betDeckSum(), read.betDeckSum());
                Random rnd = new Random(k);
                for (int n = 0; n < 40; n++) {
                    int depth = depth(rnd);
                    int[] dealt = CountKeysTest.dealt(rnd, depth);
                    assertEquals(keys(fresh, dealt, depth), keys(read, dealt, depth), fresh.name);
                }
            }
        }
    }

    /** A divisor and both betting vectors come back from a round trip through serialisation. */
    @Test
    public void aDivisorAndABettingVectorSurviveSerialisation() throws Exception {
        CountKeys.Count c = CountKeys.real("F2", direction(new Random(1)), direction(new Random(2)), 500);
        CountKeys.Count v = CountKeys.bettingVariant(c);
        for (CountKeys.Count x : new CountKeys.Count[]{c, v}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(x);
            }
            CountKeys.Count read;
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                read = (CountKeys.Count) in.readObject();
            }
            assertEquals(500, read.divisor());
            assertEquals(x.describe(), read.describe());
            Random rnd = new Random(3);
            for (int n = 0; n < 40; n++) {
                int depth = depth(rnd);
                int[] dealt = CountKeysTest.dealt(rnd, depth);
                assertEquals(keys(x, dealt, depth), keys(read, dealt, depth));
            }
        }
    }

    /** With divisor 1, a count written as whole tags by rank keys as the catalog count with those tags. */
    @Test
    public void wholeTagsAtDivisorOneKeyAsTheCatalog() {
        for (CountKeys.Count c : CountKeys.catalog()) {
            if (c.kindAware) {
                continue;
            }
            int[] rank = new int[11];
            for (int r = 1; r <= 10; r++) {
                rank[r] = c.rankTag(r);
            }
            CountKeys.Count w = CountKeys.whole(c.name, rank, null, 1, c.trueCounted);
            Random rnd = new Random(c.name.hashCode());
            for (int n = 0; n < 60; n++) {
                int depth = depth(rnd);
                int[] dealt = CountKeysTest.dealt(rnd, depth);
                assertEquals(keys(c, dealt, depth), keys(w, dealt, depth), c.name);
            }
        }
    }

    // ------------------------------------------------------- the betting vector holds the side counts

    /**
     * An ace tag of -m added to the betting vector gives each "+ ace side count" variant's bet
     * keys and true count to the bit, at divisor 1 and at a finer grain, and its play keys too.
     */
    @Test
    public void anAceTagOfMinusMIsTheAceSideCount() {
        int variants = 0;
        for (CountKeys.Count plain : CountKeys.catalog()) {
            CountKeys.Count side0 = CountKeys.sideCountVariant(plain);
            if (side0 == null) {
                continue;
            }
            for (CountKeys.Count c : new CountKeys.Count[]{plain, CountKeys.atGrain(plain, 500)}) {
                CountKeys.Count side = CountKeys.bettingVariant(c);
                assertNotNull(side, c.name);
                assertEquals(c.divisor(), side.divisor());
                int m = side.aceSideCount;
                int[] bet = c.tag.clone();
                bet[0] -= m;
                bet[1] -= m;
                CountKeys.Count f2 = new CountKeys.Count(c.name + " as F2", Collections.emptyList(), c.tag, c.scale, true,
                        0, null, "", c.divisor(), null, bet);
                CountKeys.Count v = CountKeys.bettingVariant(f2);
                assertSame(f2, v.base);
                Random rnd = new Random(variants);
                for (int n = 0; n < STATES; n++) {
                    int depth = depth(rnd);
                    int[] dealt = CountKeysTest.dealt(rnd, depth);
                    assertEquals(side.betKey(dealt, depth), v.betKey(dealt, depth), c.name);
                    assertEquals(Double.doubleToLongBits(side.trueCount(dealt, depth)),
                            Double.doubleToLongBits(v.trueCount(dealt, depth)), c.name);
                    assertEquals(keys(side, dealt, depth).subList(2, keys(side, dealt, depth).size()),
                            keys(v, dealt, depth).subList(2, keys(v, dealt, depth).size()), c.name);
                }
                variants++;
            }
        }
        long entries = CountKeys.catalog().stream().filter(c -> CountKeys.sideCountVariant(c) != null).count();
        assertTrue(entries >= 7);
        assertEquals(2 * entries, variants);
    }

    // ------------------------------------------------------------------------- invariances

    /** Adding one whole number to every tag changes no true-count key, to the bit, at any divisor. */
    @Test
    public void aConstantAddedToEveryTagChangesNoTrueKey() {
        Random rnd = new Random(11);
        for (int trial = 0; trial < 6; trial++) {
            int[] rank = CountKeys.rounded(CountKeys.normalised(direction(rnd)), CountKeys.K);
            int[] bet = CountKeys.rounded(CountKeys.normalised(direction(rnd)), CountKeys.K);
            int shift = new int[]{1, -7, 1234, -30_000, 3, 99}[trial];
            int[] rs = rank.clone();
            int[] bs = bet.clone();
            for (int r = 1; r <= 10; r++) {
                rs[r] += shift;
                bs[r] += shift;
            }
            for (int d : new int[]{1, 500, 777}) {
                CountKeys.Count a = CountKeys.whole("a", rank, bet, d, true);
                CountKeys.Count b = CountKeys.whole("b", rs, bs, d, true);
                assertNotEquals(0, b.deckSum);
                for (int n = 0; n < 60; n++) {
                    int depth = depth(rnd);
                    int[] dealt = CountKeysTest.dealt(rnd, depth);
                    assertEquals(trueKeys(a, dealt, depth), trueKeys(b, dealt, depth));
                    assertEquals(trueKeys(a.withBetting(), dealt, depth), trueKeys(b.withBetting(), dealt, depth));
                }
            }
        }
    }

    /** The keys that do not depend on the running count's level: every one but RC itself. */
    private static List<Long> trueKeys(CountKeys.Count c, int[] dealt, int depth) {
        List<Long> k = keys(c, dealt, depth);
        List<Long> out = new ArrayList<>();
        out.add(k.get(0));
        out.add(k.get(1));
        int rc = c.runningCount(dealt);
        for (int i = 0; i < Deals.COUNT; i += 5) {
            int tagSum = c.rankTag(Deals.P1[i]) + c.rankTag(Deals.P2[i]) + c.rankTag(Deals.UP[i]);
            out.add((long) c.playKey(rc, depth, tagSum));
            for (int seen = 4; seen <= 7; seen++) {
                out.add((long) c.keyAfter(rc, depth, tagSum + (seen - 3) * c.rankTag(seen), seen));
            }
        }
        return out;
    }

    /** Tags times k with the divisor times k give the same keys, true and running. */
    @Test
    public void tagsAndDivisorScaledTogetherGiveTheSameKeys() {
        Random rnd = new Random(12);
        for (boolean trueCounted : new boolean[]{true, false}) {
            for (int trial = 0; trial < 4; trial++) {
                int[] rank = CountKeys.rounded(CountKeys.normalised(direction(rnd)), CountKeys.K);
                if (!trueCounted) {
                    rank[5] += 3_000;
                }
                int d = 300 + rnd.nextInt(2_000);
                CountKeys.Count a = CountKeys.whole("a", rank, rank, d, trueCounted);
                for (int k : new int[]{2, 3, 7}) {
                    int[] scaled = rank.clone();
                    for (int r = 1; r <= 10; r++) {
                        scaled[r] *= k;
                    }
                    CountKeys.Count b = CountKeys.whole("b", scaled, scaled, d * k, trueCounted);
                    for (int n = 0; n < 50; n++) {
                        int depth = depth(rnd);
                        int[] dealt = CountKeysTest.dealt(rnd, depth);
                        assertEquals(scaledKeys(a, dealt, depth), scaledKeys(b, dealt, depth), "k " + k);
                        assertEquals(a.withBetting().betKey(dealt, depth), b.withBetting().betKey(dealt, depth));
                    }
                }
            }
        }
    }

    /** The rounded keys alone, which a common scale of tags and divisor leaves as they are. */
    private static List<Long> scaledKeys(CountKeys.Count c, int[] dealt, int depth) {
        List<Long> out = new ArrayList<>();
        out.add((long) c.betKey(dealt, depth));
        int rc = c.runningCount(dealt);
        for (int i = 0; i < Deals.COUNT; i += 5) {
            int tagSum = c.rankTag(Deals.P1[i]) + c.rankTag(Deals.P2[i]) + c.rankTag(Deals.UP[i]);
            out.add((long) c.playKey(rc, depth, tagSum));
            for (int seen = 4; seen <= 6; seen++) {
                out.add((long) c.keyAfter(rc, depth, tagSum + (seen - 3) * c.rankTag(seen), seen));
            }
        }
        return out;
    }

    /**
     * Rounding the same direction at 2K with twice the divisor moves only keys whose true count
     * sits on a rounding boundary: the two counts' true counts, in key steps, lie a small part of
     * a step apart, and a key moves only where a half step falls between them.
     */
    @Test
    public void doublingKMovesOnlyKeysOnARoundingBoundary() {
        Random rnd = new Random(13);
        int moved = 0;
        int all = 0;
        double widest = 0;
        for (int trial = 0; trial < 5; trial++) {
            double[] t = CountKeys.normalised(direction(rnd));
            int d = 500;
            CountKeys.Count a = new CountKeys.Count("K", Collections.emptyList(),
                    CountKeys.byKind(CountKeys.rounded(t, CountKeys.K)), CountKeys.K, true, 0, null, "", d, null, null);
            CountKeys.Count b = new CountKeys.Count("2K", Collections.emptyList(),
                    CountKeys.byKind(CountKeys.rounded(t, 2 * CountKeys.K)), 2 * CountKeys.K, true, 0, null, "", 2 * d,
                    null, null);
            for (int n = 0; n < 2_000; n++) {
                int depth = depth(rnd);
                int[] dealt = CountKeysTest.dealt(rnd, depth);
                for (int seen = 0; seen <= 3; seen += 3) {
                    int ka;
                    int kb;
                    double xa;
                    double xb;
                    int left = ShoeRun.CARDS - depth - seen;
                    if (seen == 0) {
                        ka = a.betKey(dealt, depth);
                        kb = b.betKey(dealt, depth);
                        xa = 52.0 * a.runningCount(dealt) / ((double) left * d);
                        xb = 52.0 * b.runningCount(dealt) / ((double) left * 2 * d);
                    } else {
                        // The deal's three cards: an ace, a five and a ten.
                        int sa = a.rankTag(1) + a.rankTag(5) + a.rankTag(10);
                        int sb = b.rankTag(1) + b.rankTag(5) + b.rankTag(10);
                        ka = a.playKey(a.runningCount(dealt), depth, sa);
                        kb = b.playKey(b.runningCount(dealt), depth, sb);
                        xa = 52.0 * (a.runningCount(dealt) + sa) / ((double) left * d);
                        xb = 52.0 * (b.runningCount(dealt) + sb) / ((double) left * 2 * d);
                    }
                    all++;
                    widest = Math.max(widest, Math.abs(xa - xb));
                    assertEquals(GranularCount.roundToGrain(xa, 1), ka, 0);
                    assertEquals(GranularCount.roundToGrain(xb, 1), kb, 0);
                    if (ka != kb) {
                        moved++;
                        double lo = Math.min(xa, xb);
                        double hi = Math.max(xa, xb);
                        double half = Math.floor(lo - 0.5) + 0.5;
                        boolean straddles = false;
                        for (int s = 0; s < 3; s++) {
                            straddles |= half + s >= lo - 1e-9 && half + s <= hi + 1e-9;
                        }
                        assertTrue(straddles, "a key moved with no rounding boundary between " + xa + " and " + xb);
                    }
                }
            }
        }
        assertTrue(widest < 0.05, "the two true counts are " + widest + " of a key step apart at most");
        assertTrue(moved < all / 50, moved + " of " + all + " keys moved");
    }

    /** Colour-blind tags written over the 26 kinds or over the 10 ranks give the same keys, on the cheap path. */
    @Test
    public void colourBlindTagsByKindOrByRankAreOneCount() {
        Random rnd = new Random(14);
        for (int trial = 0; trial < 5; trial++) {
            int[] rank = CountKeys.rounded(CountKeys.normalised(direction(rnd)), CountKeys.K);
            int[] kinds = new int[ShoeRun.KINDS];
            for (int r = 1; r <= 10; r++) {
                for (int k = 0; k < ShoeRun.KINDS; k++) {
                    if (ShoeRun.rankOf(k) == r) {
                        kinds[k] = rank[r];
                    }
                }
            }
            CountKeys.Count byKind = new CountKeys.Count("kinds", Collections.emptyList(), kinds, CountKeys.K, true, 0,
                    null, "", 500, null, null);
            CountKeys.Count byRank = CountKeys.whole("ranks", rank, null, 500, true);
            assertFalse(byKind.kindAware);
            assertFalse(byRank.kindAware);
            for (int n = 0; n < 60; n++) {
                int depth = depth(rnd);
                int[] dealt = CountKeysTest.dealt(rnd, depth);
                assertEquals(keys(byRank, dealt, depth), keys(byKind, dealt, depth));
                for (int i = 0; i < Deals.COUNT; i += 11) {
                    double[][] parts = byKind.tagSums(dealt, i);
                    if (parts.length == 0) {
                        continue;
                    }
                    assertEquals(1, parts.length);
                    assertEquals(1.0, parts[0][1], 1e-15);
                    assertEquals(byRank.rankTag(Deals.P1[i]) + byRank.rankTag(Deals.P2[i]) + byRank.rankTag(Deals.UP[i]),
                            (int) parts[0][0]);
                }
            }
        }
    }

    /** Rounding at K leaves the deck sum exactly zero, each tag within one and a half of K x its real value. */
    @Test
    public void roundingAtKLeavesTheDeckSumZero() {
        Random rnd = new Random(15);
        int moved = 0;
        for (int trial = 0; trial < 3_000; trial++) {
            double[] u = direction(rnd);
            if (trial % 3 == 0) {
                u[rnd.nextInt(10) + 1] += 40 * rnd.nextGaussian();
            }
            double[] t = CountKeys.normalised(u);
            assertEquals(CountKeys.SPREAD, CountKeys.spread(t), 1e-12);
            assertEquals(0, CountKeys.mean(t), 1e-12);
            int k = trial % 2 == 0 ? CountKeys.K : 7;
            int[] w = CountKeys.rounded(t, k);
            assertEquals(0, CountKeys.deckSum(CountKeys.byKind(w)));
            for (int r = 1; r <= 10; r++) {
                assertTrue(Math.abs(w[r] - k * t[r]) <= 1.5, "rank " + r);
                moved += Math.abs(w[r] - Math.rint(k * t[r])) > 0 ? 1 : 0;
            }
        }
        assertTrue(moved > 0, "some rounding needed the deck sum brought back");
        // Every catalog true count at a finer grain: balanced, at Hi-Lo's spread, no rank split
        // that the catalog does not split, and an ace-neutral one still ace-neutral.
        for (CountKeys.Count c : CountKeys.catalog()) {
            if (!c.trueCounted || c.systems.isEmpty()) {
                continue;
            }
            CountKeys.Count g = CountKeys.atGrain(c, 500);
            assertTrue(g.balanced(), g.name);
            assertEquals(c.kindAware, g.kindAware, g.name);
            assertEquals(500, g.divisor());
            double v = 0;
            for (int t : g.tag) {
                v += (double) t * t / ShoeRun.KINDS;
            }
            assertEquals(CountKeys.SPREAD, v / ((double) CountKeys.K * CountKeys.K), 2e-4, g.name);
            for (int k = 0; k < ShoeRun.KINDS; k++) {
                for (int j = 0; j < ShoeRun.KINDS; j++) {
                    if (ShoeRun.rankOf(k) == ShoeRun.rankOf(j) && c.tag[k] == c.tag[j]) {
                        assertEquals(g.tag[k], g.tag[j], g.name);
                    }
                }
                if (c.balanced() && c.tag[k] == 0) {
                    assertEquals(0, g.tag[k], g.name);
                }
            }
        }
    }

    /**
     * The largest tags the normalisation allows, every rank's at its extreme, cannot overflow the
     * running count: a rank's tag is at most sqrt(spread (1 - p) / p), and no running count of
     * normalised tags at K exceeds 416 sqrt(spread) K plus the rounding, far under the limit.
     */
    /**
     * Every catalog count takes a grain (COUNT_DESIGN.md, section 4: every published row at g), the
     * twelve running ones among them, Red Seven and the KISS counts kind-aware. Each keeps its form
     * and its classes: kinds of a rank tagged alike stay alike, and kinds tagged apart stay apart. A
     * running count is scaled, not centred, since its shift carries depth: each kind within 1.5 of
     * grainUnit times its catalog tag, a kind tagged 0 still 0, its deck sum the nearest it can
     * reach to the catalog's scaled, a balanced one still balanced; and at divisor rint(grainUnit)
     * its bet and play keys, and each deal's chances over them, are the catalog's on random states.
     * A kind-aware true count (Red Seven's tags counted true) keeps its classes and a deck sum of zero.
     */
    @Test
    public void everyCatalogCountTakesAGrain() {
        Random rnd = new Random(16);
        int running = 0;
        for (CountKeys.Count c : CountKeys.catalog()) {
            CountKeys.Count g = CountKeys.atGrain(c, 500);
            sameClasses(c, g);
            assertEquals(c.trueCounted, g.trueCounted, g.name);
            assertEquals(500, g.divisor());
            if (c.trueCounted) {
                continue;
            }
            running++;
            double unit = CountKeys.grainUnit(c);
            long sum = 0;
            for (int k = 0; k < ShoeRun.KINDS; k++) {
                assertTrue(Math.abs(g.tag[k] - unit * c.tag[k]) <= 1.5, g.name + " kind " + k);
                if (c.tag[k] == 0) {
                    assertEquals(0, g.tag[k], g.name);
                }
                sum += g.tag[k];
            }
            assertTrue(Math.abs(2 * sum - unit * c.deckSum) <= 2, g.name + "'s deck sum " + 2 * sum);
            assertEquals(c.balanced(), g.balanced(), g.name);
            CountKeys.Count at = CountKeys.atGrain(c, (int) Math.rint(unit));
            for (int trial = 0; trial < 3_000; trial++) {
                int[] dealt = CountKeysTest.dealt(rnd, rnd.nextInt(ShoeRun.CARDS - 80));
                int depth = 0;
                for (int x : dealt) {
                    depth += x;
                }
                assertEquals(c.betKey(dealt, depth), at.betKey(dealt, depth), c.name);
                int deal = rnd.nextInt(Deals.COUNT);
                double[][] a = c.tagSums(dealt, deal);
                double[][] b = at.tagSums(dealt, deal);
                assertEquals(a.length, b.length, c.name);
                for (int i = 0; i < a.length; i++) {
                    assertEquals(a[i][1], b[i][1], 0, c.name + ": a deal's chances over its tag sums");
                    assertEquals(c.playKey(c.runningCount(dealt), depth, (int) a[i][0]),
                            at.playKey(at.runningCount(dealt), depth, (int) b[i][0]), c.name);
                }
            }
        }
        assertEquals(12, running);
        CountKeys.Count red = PlayerTreeOrderTest.named("Red Seven (Red 7)");
        CountKeys.Count redTrue = new CountKeys.Count("Red Seven counted true", red.systems, red.tag, red.scale, true, 0,
                null, "");
        CountKeys.Count g = CountKeys.atGrain(redTrue, 500);
        assertTrue(g.kindAware && g.balanced() && g.trueCounted);
        sameClasses(redTrue, g);
    }

    /** Kinds of a rank tagged alike by c are tagged alike by g, and kinds tagged apart, apart. */
    private static void sameClasses(CountKeys.Count c, CountKeys.Count g) {
        assertEquals(c.kindAware, g.kindAware, g.name);
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            for (int j = 0; j < ShoeRun.KINDS; j++) {
                if (ShoeRun.rankOf(k) == ShoeRun.rankOf(j)) {
                    assertEquals(c.tag[k] == c.tag[j], g.tag[k] == g.tag[j], g.name + " kinds " + k + ", " + j);
                }
            }
        }
    }

    @Test
    public void theLargestNormalisedTagsCannotOverflow() {
        double bound = ShoeRun.CARDS * Math.sqrt(CountKeys.SPREAD) * CountKeys.K + ShoeRun.CARDS * 1.5;
        assertTrue(bound * 100 < CountKeys.MAX_COUNT, "the limit leaves a hundredfold room");
        assertTrue(52 * (double) CountKeys.MAX_COUNT * 4 < Math.pow(2, 53), "52 x RC_b is exact in a double");
        for (int r = 1; r <= 10; r++) {
            for (int sign : new int[]{1, -1}) {
                double[] u = new double[11];
                u[r] = sign;
                double[] t = CountKeys.normalised(u);
                double p = CountKeys.P0[r];
                assertEquals(sign * Math.sqrt(CountKeys.SPREAD * (1 - p) / p), t[r], 1e-12);
                CountKeys.Count c = CountKeys.real("extreme " + r, u, u, 1);
                assertTrue(CountKeys.worstCount(c.tag) <= bound);
                for (int depth : new int[]{0, 1, 200, 331, 415}) {
                    int[] dealt = new int[ShoeRun.KINDS];
                    int left = depth;
                    for (int k = 0; k < ShoeRun.KINDS && left > 0; k++) {
                        if (ShoeRun.rankOf(k) == r) {
                            dealt[k] = Math.min(left, 2 * ShoeRun.DECKS);
                            left -= dealt[k];
                        }
                    }
                    for (int k = 0; k < ShoeRun.KINDS && left > 0; k++) {
                        int add = Math.min(left, 2 * ShoeRun.DECKS - dealt[k]);
                        dealt[k] += add;
                        left -= add;
                    }
                    int d = Arrays.stream(dealt).sum();
                    long rc = 0;
                    for (int k = 0; k < ShoeRun.KINDS; k++) {
                        rc += (long) dealt[k] * c.tag[k];
                    }
                    assertEquals(rc, c.runningCount(dealt));
                    if (d < ShoeRun.CARDS) {
                        double exact = (52.0 * rc - (double) c.deckSum * d) / (ShoeRun.CARDS - d);
                        assertEquals(GranularCount.roundToGrain(exact, 1), c.betKey(dealt, d), 0);
                    }
                }
            }
        }
        Random rnd = new Random(16);
        for (int n = 0; n < 2_000; n++) {
            CountKeys.Count c = CountKeys.real("random", direction(rnd), null, 1);
            assertTrue(CountKeys.worstCount(c.tag) <= bound);
        }
        int[] huge = new int[11];
        huge[10] = Integer.MAX_VALUE / 100;
        assertThrows(IllegalArgumentException.class, () -> CountKeys.whole("too large", huge, null, 1, true));
    }
}
