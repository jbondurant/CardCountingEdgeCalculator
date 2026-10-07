import org.bson.Document;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The counts the comparison scores, and the keys each gives a state and a deal
 * (COUNTING_COMPARISON.md, section 5).
 *
 * A count is kept in whole numbers: a system's card tags are multiplied by the smallest
 * factor that makes every one whole, 2 for Wong Halves and C-R and 1 for the rest. That
 * matters because a true count is rounded to a whole number, so the scale sets how fine the
 * count's steps are.
 *
 * Every state has a bet key, fixed before the round's cards come out, and every (state,
 * deal) a play key, which also sees the player's two cards and the up-card:
 * - a running count keys on its running count RC, from 0 at the shuffle, and its play key
 *   adds the tags of the deal's three cards;
 * - a true count keys on RC_b over the decks left, rounded to the nearest whole number with
 *   ties toward zero (GranularCount.roundToGrain), where RC_b removes an unbalanced count's
 *   drift, RC - deckSum x dealt / 52; its play key counts the deal's three cards and takes
 *   them out of the cards left.
 *
 * RC_b is computed as 52 x RC - deckSum x dealt, a whole number, over the cards left, so a
 * true count that is exactly half-way between two whole numbers comes out exactly
 * half-way, and the tie rule applies as it should.
 *
 * Red Seven and the KISS counts tag some cards by colour or face, which a deal's ranks do
 * not say. Their play key is then a spread: the deal's weight is split over the tag classes
 * of its three cards, with the exact chances of each class given the kinds left, drawn
 * without replacement, and each part goes to its own key.
 *
 * Systems whose whole-number tags are identical and that are counted the same way give
 * identical results, so they are one entry: Hi-Lo and Hi-Lo Lite, KO and REKO, Omega II and
 * Canfield Master, and RPC, FELT and C-R. The balanced ace-neutral systems of section 5 are
 * scored a second time with an ace side count that moves only the bet key. The null count,
 * every tag 0, is the baseline the efficiencies are measured from.
 *
 * A count of our own (COUNT_DESIGN.md, section 1) has real-valued tags, kept as whole numbers
 * at scale K = 10,000, and a key divisor D: its keys are the true or running count over D,
 * rounded as above, so a key step is D / K of a tag unit. A catalog count has D = 1, which
 * leaves every key as it was, to the bit. A count may also carry a betting vector of its own
 * (F2): its betting variant (bettingVariant) keeps its play and bets on the other tags, as the
 * ace side-count variants do, which are such a vector exactly.
 */
final class CountKeys {

    /** The balanced ace-neutral systems that are also scored with an ace side count for betting. */
    static final List<String> SIDE_COUNTED = Collections.unmodifiableList(Arrays.asList("Hi-Opt I", "Hi-Opt II",
            "Omega II", "Canfield Expert", "Revere Advanced Plus-Minus", "Revere 14 Count (1973 Advanced Point Count)",
            "Uston Advanced Point Count (Uston APC)", "Victor Advanced Point Count (Victor APC)"));

    private CountKeys() {
    }

    /** One count as the comparison scores it. */
    static final class Count implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        /** The row's name: its systems joined by " = ", with " + ace side count" for a variant. */
        final String name;
        /** The catalog systems it stands for, in catalog order; empty for the null count. */
        final List<String> systems;
        /** The tag of each of the 26 kinds (ShoeRun), on the whole-number scale. */
        final int[] tag;
        /** What the catalog's tags were multiplied by to make them whole. */
        final int scale;
        final boolean trueCounted;
        /** What 52 cards add to the count: zero exactly when the count is balanced. */
        final int deckSum;
        /** m: the ace side count's weight for betting, the ten's tag in absolute value, or 0 for none. */
        final int aceSideCount;
        /** For a side-count variant, the plain count whose play it keeps; null otherwise. */
        final Count base;
        /** The catalog's published betting correlation, playing efficiency and insurance correlation, as text. */
        final String published;

        /** Per rank 1 to 10: the distinct tags its kinds carry, and which one each kind has. */
        private final int[][] classTag = new int[11][];
        private final int[] classOfKind = new int[ShoeRun.KINDS];
        /** Whether some rank's cards carry different tags, so a deal's play key is a spread. */
        final boolean kindAware;

        // The fields below were added after c06c061 wrote its results. The class keeps its
        // serialVersionUID, so a Count read from those files has them at their defaults: a
        // divisor of 0, read as 1, and no betting vectors, so it bets on its play tags.

        /** D: the keys are the count over D, rounded; 0 in a file written before it, read as 1. */
        private final int divisor;
        /** The tags the bet key counts, by kind; null for the play tags. */
        final int[] betTag;
        /**
         * For a count with a betting vector of its own (F2), the tags its betting variant bets on,
         * by kind (withBetting); null otherwise.
         */
        final int[] betVector;

        Count(String name, List<String> systems, int[] tag, int scale, boolean trueCounted, int aceSideCount,
              Count base, String published) {
            this(name, systems, tag, scale, trueCounted, aceSideCount, base, published, 1, null, null);
        }

        /**
         * A count with a key divisor, the tags its bet key counts (null for the play tags), and a
         * betting vector for its betting variant (null for none).
         */
        Count(String name, List<String> systems, int[] tag, int scale, boolean trueCounted, int aceSideCount,
              Count base, String published, int divisor, int[] betTag, int[] betVector) {
            if (tag.length != ShoeRun.KINDS || (betTag != null && betTag.length != ShoeRun.KINDS)
                    || (betVector != null && betVector.length != ShoeRun.KINDS)) {
                throw new IllegalArgumentException("a count tags " + ShoeRun.KINDS + " kinds");
            }
            if (aceSideCount != 0 && !trueCounted) {
                throw new IllegalArgumentException(name + ": the ace side count adjusts a true count");
            }
            if (divisor < 1) {
                throw new IllegalArgumentException(name + ": a key divisor of " + divisor);
            }
            for (int[] t : new int[][]{tag, betTag, betVector}) {
                if (t != null && worstCount(t) > MAX_COUNT) {
                    throw new IllegalArgumentException(name + ": tags this large could overflow the running count");
                }
            }
            this.name = name;
            this.systems = Collections.unmodifiableList(new ArrayList<>(systems));
            this.tag = tag.clone();
            this.scale = scale;
            this.trueCounted = trueCounted;
            this.aceSideCount = aceSideCount;
            this.base = base;
            this.published = published;
            this.divisor = divisor;
            this.betTag = betTag == null ? null : betTag.clone();
            this.betVector = betVector == null ? null : betVector.clone();
            this.deckSum = deckSum(tag);
            boolean aware = false;
            for (int r = 1; r <= 10; r++) {
                List<Integer> distinct = new ArrayList<>();
                for (int k = 0; k < ShoeRun.KINDS; k++) {
                    if (ShoeRun.rankOf(k) == r && !distinct.contains(tag[k])) {
                        distinct.add(tag[k]);
                    }
                }
                classTag[r] = new int[distinct.size()];
                for (int c = 0; c < distinct.size(); c++) {
                    classTag[r][c] = distinct.get(c);
                }
                aware |= distinct.size() > 1;
                for (int k = 0; k < ShoeRun.KINDS; k++) {
                    if (ShoeRun.rankOf(k) == r) {
                        classOfKind[k] = distinct.indexOf(tag[k]);
                    }
                }
            }
            this.kindAware = aware;
        }

        boolean balanced() {
            return deckSum == 0;
        }

        /** D, the key divisor: 1 for every catalog count, and for a count read from a file written before it. */
        int divisor() {
            return divisor == 0 ? 1 : divisor;
        }

        /** The tags the bet key counts: the betting vector's, or the play tags. */
        int[] betTags() {
            return betTag == null ? tag : betTag;
        }

        /** What 52 cards add to the bet key's count. */
        int betDeckSum() {
            return betTag == null ? deckSum : deckSum(betTag);
        }

        /** The bet key's running count: the sum of the bet tags of every card dealt. */
        int betRunningCount(int[] dealt) {
            if (betTag == null) {
                return runningCount(dealt);
            }
            int rc = 0;
            for (int k = 0; k < ShoeRun.KINDS; k++) {
                rc += dealt[k] * betTag[k];
            }
            return rc;
        }

        /** The tag every card of contract rank r carries; only for a count that is not kind-aware. */
        int rankTag(int r) {
            if (classTag[r].length != 1) {
                throw new IllegalStateException(name + " tags rank " + r + " by colour or face");
            }
            return classTag[r][0];
        }

        /** RC: the sum of the tags of every card dealt since the shuffle. */
        int runningCount(int[] dealt) {
            int rc = 0;
            for (int k = 0; k < ShoeRun.KINDS; k++) {
                rc += dealt[k] * tag[k];
            }
            return rc;
        }

        /** 52 x RC_b: the running count less its drift, times 52 so that it is whole. */
        long driftFree52(int rc, int depth) {
            return 52L * rc - (long) deckSum * depth;
        }

        /** 52 x m x (aces left - cards left / 13): the side count's adjustment, times 52. */
        long sideCount52(int[] dealt, int depth) {
            if (aceSideCount == 0) {
                return 0;
            }
            int acesLeft = 4 * ShoeRun.DECKS - dealt[0] - dealt[1];
            int left = ShoeRun.CARDS - depth;
            return (long) aceSideCount * (52L * acesLeft - 4L * left);
        }

        /** 52 x RC_b of the bet tags. */
        private long betDriftFree52(int rc, int depth) {
            return 52L * rc - (long) betDeckSum() * depth;
        }

        /**
         * The bet key of a state with these cards dealt, depth of them in all: the bet tags'
         * count, drift-free over the cards left for a true count, over D, rounded.
         */
        int betKey(int[] dealt, int depth) {
            int rc = betRunningCount(dealt);
            int d = divisor();
            if (!trueCounted) {
                return d == 1 ? rc : roundedRatio(rc, d);
            }
            return roundedRatio(betDriftFree52(rc, depth) + sideCount52(dealt, depth), (long) (ShoeRun.CARDS - depth) * d);
        }

        /**
         * The unrounded, drift-free true count of the bet tags, with the side count's adjustment
         * for a variant: RC_b over decks left, whatever way the count is played, and not divided
         * by D. The empirical betting and insurance correlations are computed on it.
         */
        double trueCount(int[] dealt, int depth) {
            return (double) (betDriftFree52(betRunningCount(dealt), depth) + sideCount52(dealt, depth))
                    / (ShoeRun.CARDS - depth);
        }

        /** The play key when the deal's three cards add tagSum to a running count of rc. */
        int playKey(int rc, int depth, int tagSum) {
            return keyAfter(rc, depth, tagSum, 3);
        }

        /**
         * The key at a decision once seen cards of the round have been seen, adding tagSum to a
         * running count of rc: the player's cards, the up-card and every card drawn, never the
         * hole card. A running count keys on the count itself. A true count keys on it drift-free
         * over the decks not yet seen, the cards left before the round less the seen ones, so the
         * hole card is counted among the cards not seen; it is rounded as the play key is. With
         * the deal's three cards seen this is the play key.
         */
        int keyAfter(int rc, int depth, int tagSum, int seen) {
            int d = divisor();
            if (!trueCounted) {
                return d == 1 ? rc + tagSum : roundedRatio(rc + tagSum, d);
            }
            return roundedRatio(driftFree52(rc + tagSum, depth + seen), (long) (ShoeRun.CARDS - depth - seen) * d);
        }

        /** How many tag classes rank r's kinds fall into: 1 unless the count tags the rank by colour or face. */
        int classes(int r) {
            return classTag[r].length;
        }

        /** The tag of class c of rank r, as classesLeft numbers the classes. */
        int classTag(int r, int c) {
            return classTag[r][c];
        }

        /** The tag class of a kind (ShoeRun), within its rank. */
        int classOf(int kind) {
            return classOfKind[kind];
        }

        /**
         * The chance of each total tag the deal's three cards can carry, given the kinds left:
         * the tag class of each card, drawn without replacement once the deal's ranks are
         * known. Returned as {tagSum, chance} pairs in the order first met; the chances add up
         * to 1. Empty if the cards left cannot make the deal.
         */
        double[][] tagSums(int[] dealt, int deal) {
            return tagSums(classesLeft(dealt), deal);
        }

        /** The cards left in each tag class of each rank: [r][class], and the rank's total at [r][classes]. */
        int[][] classesLeft(int[] dealt) {
            int[][] left = new int[11][];
            for (int r = 1; r <= 10; r++) {
                left[r] = new int[classTag[r].length + 1];
            }
            for (int k = 0; k < ShoeRun.KINDS; k++) {
                int r = ShoeRun.rankOf(k);
                int n = 2 * ShoeRun.DECKS - dealt[k];
                left[r][classOfKind[k]] += n;
                left[r][classTag[r].length] += n;
            }
            return left;
        }

        /** tagSums from classesLeft, which the caller may reuse for every deal of a state; left is restored. */
        double[][] tagSums(int[][] left, int deal) {
            int[] ranks = {Deals.P1[deal], Deals.P2[deal], Deals.UP[deal]};
            List<double[]> out = new ArrayList<>(4);
            spread(ranks, left, 0, new int[3], 1.0, out);
            return out.toArray(new double[0][]);
        }

        private void spread(int[] ranks, int[][] left, int at, int[] chosen, double p, List<double[]> out) {
            if (at == 3) {
                int sum = 0;
                for (int j = 0; j < 3; j++) {
                    sum += classTag[ranks[j]][chosen[j]];
                }
                for (double[] e : out) {
                    if (e[0] == sum) {
                        e[1] += p;
                        return;
                    }
                }
                out.add(new double[]{sum, p});
                return;
            }
            int r = ranks[at];
            int classes = classTag[r].length;
            if (left[r][classes] <= 0) {
                return;
            }
            for (int c = 0; c < classes; c++) {
                if (left[r][c] <= 0) {
                    continue;
                }
                double pc = p * left[r][c] / left[r][classes];
                left[r][c]--;
                left[r][classes]--;
                chosen[at] = c;
                spread(ranks, left, at + 1, chosen, pc, out);
                left[r][c]++;
                left[r][classes]++;
            }
        }

        /**
         * The same count with an ace side count for betting, m the ten's tag in absolute value,
         * and the same divisor.
         */
        Count sideCounted() {
            if (!trueCounted || !balanced() || tag[0] != 0 || tag[1] != 0 || betTag != null) {
                throw new IllegalStateException(name + " is not a balanced ace-neutral true count");
            }
            int m = Math.abs(rankTag(10));
            return new Count(name + " + ace side count", systems, tag, scale, true, m, this, published, divisor(),
                    null, null);
        }

        /**
         * The variant that plays as this count does and bets on its betting vector (F2): its base
         * is this count, so SystemScorer.score scores it beside this one.
         */
        Count withBetting() {
            if (betVector == null) {
                throw new IllegalStateException(name + " has no betting vector of its own");
            }
            return new Count(name + " + betting vector", systems, tag, scale, trueCounted, 0, this, published,
                    divisor(), betVector, null);
        }

        /**
         * Everything that sets the count's keys, on one line, for a run's identity: its form, its
         * tags by kind, its divisor, the tags its bet key counts, its side count, and the betting
         * vector of its variant.
         */
        String describe() {
            return name + ": " + (trueCounted ? "true" : "running") + " count, tags " + Arrays.toString(tag)
                    + " at scale " + scale + ", divisor " + divisor() + ", bet tags "
                    + (betTag == null ? "the play tags" : Arrays.toString(betTag)) + ", ace side count " + aceSideCount
                    + ", betting vector " + (betVector == null ? "none" : Arrays.toString(betVector));
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** numerator / denominator rounded to the nearest whole number, ties toward zero, as the simulator rounds. */
    static int roundedRatio(long numerator, long denominator) {
        return (int) GranularCount.roundToGrain((double) numerator / denominator, 1.0);
    }

    /** What 52 cards add to a count with these tags by kind. */
    static int deckSum(int[] tag) {
        int sum = 0;
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            sum += 2 * tag[k];
        }
        return sum;
    }

    /**
     * The largest a running count of these tags can reach in absolute value, any cards dealt:
     * every card of each kind whose tag has one sign, or of the other.
     */
    static long worstCount(int[] tag) {
        long up = 0;
        long down = 0;
        for (int t : tag) {
            if (t > 0) {
                up += 2L * ShoeRun.DECKS * t;
            } else {
                down -= 2L * ShoeRun.DECKS * t;
            }
        }
        return Math.max(up, down);
    }

    /**
     * The largest running count a count may reach: with a play key's three cards added, 52
     * times it and the deck sum's drift still well inside what a long and a double hold exactly,
     * and the count itself an int.
     */
    static final long MAX_COUNT = Integer.MAX_VALUE / 4;

    /** A catalog system on the whole-number scale. */
    static Count of(CountingSystem s, String published) {
        int scale = 1;
        while (!whole(s, scale)) {
            scale++;
            if (scale > 100) {
                throw new IllegalArgumentException(s.name + " has a tag that no small factor makes whole");
            }
        }
        int[] tag = new int[ShoeRun.KINDS];
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            tag[k] = (int) Math.rint(scale * s.tag(ShoeRun.simulatorRank(k), ShoeRun.suit(k)));
        }
        return new Count(s.name, Collections.singletonList(s.name), tag, scale, s.trueCounted, 0, null, published);
    }

    private static boolean whole(CountingSystem s, int scale) {
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            double t = scale * s.tag(ShoeRun.simulatorRank(k), ShoeRun.suit(k));
            if (Math.abs(t - Math.rint(t)) > 1e-9) {
                return false;
            }
        }
        return true;
    }

    /** Every tag 0: one group, so its play is the best two-card play pooled over every state. */
    static Count nullCount() {
        return new Count("Null count (no count)", Collections.emptyList(), new int[ShoeRun.KINDS], 1, false, 0, null, "");
    }

    /**
     * The catalog's systems with duplicates made one entry, in catalog order: systems whose
     * whole-number tags are identical and that are counted the same way. Each entry carries
     * the published figures of its first system.
     */
    static List<Count> distinct(List<CountingSystem> catalog, List<String> published) {
        List<Count> out = new ArrayList<>();
        List<List<String>> names = new ArrayList<>();
        for (int i = 0; i < catalog.size(); i++) {
            Count c = of(catalog.get(i), published.get(i));
            boolean placed = false;
            for (int j = 0; j < out.size(); j++) {
                Count o = out.get(j);
                if (o.trueCounted == c.trueCounted && Arrays.equals(o.tag, c.tag)) {
                    names.get(j).add(c.name);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                out.add(c);
                names.add(new ArrayList<>(c.systems));
            }
        }
        List<Count> merged = new ArrayList<>();
        for (int j = 0; j < out.size(); j++) {
            Count o = out.get(j);
            merged.add(new Count(String.join(" = ", names.get(j)), names.get(j), o.tag, o.scale, o.trueCounted, 0,
                    null, o.published));
        }
        return merged;
    }

    /** The catalog's entries, duplicates made one, each with its published figures. */
    static List<Count> catalog() {
        List<String> published = new ArrayList<>();
        try {
            Document doc = Document.parse(new String(Files.readAllBytes(CountingSystem.FILE), StandardCharsets.UTF_8));
            for (Document d : doc.getList("systems", Document.class)) {
                String text = d.getString("publishedBcPeIc");
                published.add(text == null ? "" : text);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + CountingSystem.FILE.toAbsolutePath(), e);
        }
        return distinct(CountingSystem.all(), published);
    }

    /** The side-count variant of an entry, if one of its systems is listed in SIDE_COUNTED; else null. */
    static Count sideCountVariant(Count c) {
        for (String s : c.systems) {
            if (SIDE_COUNTED.contains(s)) {
                return c.sideCounted();
            }
        }
        return null;
    }

    /**
     * The count scored beside c with c's play and another bet key (SystemScorer.score's second
     * count): c's own betting vector's variant (F2) when it has one, else the catalog's ace
     * side-count variant, else null. Every place that scores a list of counts takes the variant
     * from here, so a count's own betting vector is never dropped.
     */
    static Count bettingVariant(Count c) {
        if (c.betVector != null) {
            return c.withBetting();
        }
        return sideCountVariant(c);
    }

    // ---------------------------------------------------------------- real-valued tags

    /** K: real tags are kept as whole numbers at this scale. */
    static final int K = 10_000;
    /** The ranks' shares of a full shoe, p0: 1/13 for the ace and 2 to 9, 4/13 for the ten-valued cards. */
    static final double[] P0 = {0, 1 / 13.0, 1 / 13.0, 1 / 13.0, 1 / 13.0, 1 / 13.0, 1 / 13.0, 1 / 13.0, 1 / 13.0,
            1 / 13.0, 4 / 13.0};
    /** Hi-Lo's spread, sum over ranks of p0 x tag squared once centred: every real count is scaled to it. */
    static final double SPREAD = 10 / 13.0;

    private static int[] append(int[] a, int x) {
        int[] out = Arrays.copyOf(a, a.length + 1);
        out[a.length] = x;
        return out;
    }

    /** Tags by kind from tags by rank, index 1 (the ace) to 10 (the ten-valued cards): colour-blind. */
    static int[] byKind(int[] rankTag) {
        if (rankTag.length != 11) {
            throw new IllegalArgumentException("tags by rank are indexed 1 to 10");
        }
        int[] out = new int[ShoeRun.KINDS];
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            out[k] = rankTag[ShoeRun.rankOf(k)];
        }
        return out;
    }

    /** The p0-weighted mean of tags by rank, 1 to 10. */
    static double mean(double[] rankTag) {
        double m = 0;
        for (int r = 1; r <= 10; r++) {
            m += P0[r] * rankTag[r];
        }
        return m;
    }

    /** Sum over ranks of p0 x (tag - mean) squared: the spread the normalisation fixes. */
    static double spread(double[] rankTag) {
        double m = mean(rankTag);
        double v = 0;
        for (int r = 1; r <= 10; r++) {
            v += P0[r] * (rankTag[r] - m) * (rankTag[r] - m);
        }
        return v;
    }

    /**
     * A direction of real tags by rank (1 to 10), centred and scaled to Hi-Lo's spread: the
     * count every direction stands for (COUNT_DESIGN.md, section 1). Refuses one with no spread.
     */
    static double[] normalised(double[] rankTag) {
        double v = spread(rankTag);
        if (!(v > 0)) {
            throw new IllegalArgumentException("tags that do not vary by rank are no count");
        }
        double m = mean(rankTag);
        double f = Math.sqrt(SPREAD / v);
        double[] out = new double[11];
        for (int r = 1; r <= 10; r++) {
            out[r] = (rankTag[r] - m) * f;
        }
        return out;
    }

    /**
     * Real tags by rank rounded to whole numbers at scale k, with the deck sum kept exactly zero:
     * each tag is rounded to the nearest whole number, and if the deck sum (4 x each of the ace
     * and 2 to 9, 16 x the ten) is then not zero, the tags of the ace and 2 to 9 whose rounding
     * moved them furthest the other way are moved by one each until it is. The tags must have a
     * deck sum of zero to begin with, as normalised's do.
     */
    static int[] rounded(double[] rankTag, int k) {
        double[] x = new double[11];
        int[] out = new int[11];
        long sum = 0;
        for (int r = 1; r <= 10; r++) {
            x[r] = rankTag[r] * k;
            out[r] = (int) Math.rint(x[r]);
            sum += (r == 10 ? 4L : 1L) * out[r];
        }
        double drift = 0;
        for (int r = 1; r <= 10; r++) {
            drift += (r == 10 ? 4 : 1) * x[r];
        }
        if (Math.abs(drift) > 1e-6 * k) {
            throw new IllegalArgumentException("tags with a deck sum of " + drift / k + ", not zero");
        }
        while (sum != 0) {
            int s = sum > 0 ? 1 : -1;
            int best = -1;
            for (int r = 1; r <= 9; r++) {
                // How far the rounding moved this tag in the direction the sum is off.
                double over = s * (out[r] - x[r]);
                if (best < 0 || over > s * (out[best] - x[best])) {
                    best = r;
                }
            }
            out[best] -= s;
            sum -= s;
        }
        return out;
    }

    /**
     * A true count of our own: real tags by rank (1 to 10) normalised and rounded at K, with key
     * divisor d, and when betting is given, a betting vector normalised and rounded the same way
     * for its F2 variant.
     */
    static Count real(String name, double[] rankTag, double[] betting, int d) {
        int[] play = byKind(rounded(normalised(rankTag), K));
        int[] bet = betting == null ? null : byKind(rounded(normalised(betting), K));
        return new Count(name, Collections.emptyList(), play, K, true, 0, null, "", d, null, bet);
    }

    /**
     * A count of our own from whole-number tags by rank at K, as registered: true or running,
     * key divisor d, and a betting vector by rank or null. Nothing is normalised or rounded, so
     * a registered count is the count scored, to the bit.
     */
    static Count whole(String name, int[] rankTag, int[] betting, int d, boolean trueCounted) {
        return new Count(name, Collections.emptyList(), byKind(rankTag), K, trueCounted, 0, null, "", d, null,
                betting == null ? null : byKind(betting));
    }

    /**
     * A catalog count at grain D / K (COUNT_DESIGN.md, section 4: every published row at g): its
     * tags by kind scaled to Hi-Lo's spread over the 26 kinds, each of share 1/26, rounded at K,
     * and key divisor d. The name says the grain; the systems stay the catalog's, so the side-count
     * variant comes with it, carrying the divisor. A rank's kinds of one catalog tag are rounded
     * together, so a kind-aware count keeps its classes and a colour-blind one stays colour-blind.
     * - A true count is centred first, as normalised does, and its deck sum kept exactly zero.
     * - A running count is scaled and not centred, since its shift is what carries depth (as F1r's
     *   is scaled with its balanced part): its deck sum is kept the nearest whole number to the
     *   catalog's scaled alike, and a kind the catalog tags 0 stays 0. It keys on its running count
     *   over d; with d the whole tags of one catalog tag unit (grainUnit), its keys are the
     *   catalog's.
     */
    static Count atGrain(Count c, int d) {
        if (c.base != null || c.betTag != null || c.betVector != null || c.aceSideCount != 0) {
            throw new IllegalArgumentException(c.name + ": only a plain catalog count is scored at another grain");
        }
        long total = 0;
        for (int t : c.tag) {
            total += t;
        }
        double mean = (double) total / ShoeRun.KINDS;
        double v = 0;
        for (int t : c.tag) {
            v += (t - mean) * (t - mean) / ShoeRun.KINDS;
        }
        if (!(v > 0)) {
            throw new IllegalArgumentException(c.name + " does not vary");
        }
        double f = Math.sqrt(SPREAD / v);
        // The kinds go in groups, a rank's kinds with the same catalog tag, so a count that tags
        // a rank alike keeps doing so; each group is rounded as one.
        int[] group = new int[ShoeRun.KINDS];
        List<int[]> groups = new ArrayList<>();
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            group[k] = -1;
            for (int g = 0; g < groups.size(); g++) {
                int k0 = groups.get(g)[0];
                if (ShoeRun.rankOf(k0) == ShoeRun.rankOf(k) && c.tag[k0] == c.tag[k]) {
                    group[k] = g;
                    groups.set(g, append(groups.get(g), k));
                    break;
                }
            }
            if (group[k] < 0) {
                group[k] = groups.size();
                groups.add(new int[]{k});
            }
        }
        int n = groups.size();
        double[] x = new double[n];
        int[] r = new int[n];
        long sum = 0;
        double exact = 0;
        for (int g = 0; g < n; g++) {
            int[] kinds = groups.get(g);
            x[g] = (c.tag[kinds[0]] - (c.trueCounted ? mean : 0)) * f * K;
            r[g] = (int) Math.rint(x[g]);
            sum += (long) kinds.length * r[g];
            exact += kinds.length * x[g];
        }
        // Then the deck sum is brought back to its target, moving by one the groups whose rounding
        // moved them furthest the other way. A group the catalog tags 0 in a balanced count, or in
        // a running one, is left at 0, so an ace-neutral count stays ace-neutral and keeps its side
        // count. A true count's target is zero; a running count's the nearest to its scaled deck
        // sum that the groups that may move can reach (a multiple of their sizes' common factor),
        // so a balanced one stays balanced.
        if (!c.trueCounted) {
            int step = 0;
            for (int g = 0; g < n; g++) {
                if (c.tag[groups.get(g)[0]] != 0) {
                    step = java.math.BigInteger.valueOf(step).gcd(java.math.BigInteger.valueOf(groups.get(g).length))
                            .intValue();
                }
            }
            sum -= step * (long) Math.rint(exact / step);
        }
        while (sum != 0) {
            int s = sum > 0 ? 1 : -1;
            int best = -1;
            for (int g = 0; g < n; g++) {
                boolean zero = c.tag[groups.get(g)[0]] == 0 && (c.balanced() || !c.trueCounted);
                if (zero || groups.get(g).length > Math.abs(sum)) {
                    continue;
                }
                if (best < 0 || s * (r[g] - x[g]) > s * (r[best] - x[best])) {
                    best = g;
                }
            }
            if (best < 0) {
                throw new IllegalStateException(c.name + ": no rounding at K leaves its deck sum zero");
            }
            r[best] -= s;
            sum -= (long) s * groups.get(best).length;
        }
        int[] out = new int[ShoeRun.KINDS];
        for (int k = 0; k < ShoeRun.KINDS; k++) {
            out[k] = r[group[k]];
        }
        return new Count(c.name + " at grain " + d + "/" + K, c.systems, out, K, c.trueCounted, 0, null, c.published, d,
                null, null);
    }

    /**
     * What one catalog tag unit becomes at grain: K times the factor atGrain scales the count's
     * tags by, unrounded. With divisor rint(grainUnit(c)), a running count at grain keys as the
     * catalog count does (RealCountTest); the calibration sets a running count's divisor at g from
     * the states, as F1r's (COUNT_DESIGN.md, section 1).
     */
    static double grainUnit(Count c) {
        long total = 0;
        for (int t : c.tag) {
            total += t;
        }
        double mean = (double) total / ShoeRun.KINDS;
        double v = 0;
        for (int t : c.tag) {
            v += (t - mean) * (t - mean) / ShoeRun.KINDS;
        }
        return Math.sqrt(SPREAD / v) * K;
    }
}
