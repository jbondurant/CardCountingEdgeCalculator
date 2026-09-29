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
 */
final class CountKeys {

    /** The balanced ace-neutral systems that are also scored with an ace side count for betting. */
    static final List<String> SIDE_COUNTED = Collections.unmodifiableList(Arrays.asList("Hi-Opt I", "Hi-Opt II",
            "Omega II", "Canfield Expert", "Revere Advanced Plus-Minus", "Revere 14 Count (1973 Advanced Point Count)",
            "Uston Advanced Point Count (Uston APC)", "Victor Advanced Point Count (Victor APC)"));

    private CountKeys() {
    }

    /** One count as the comparison scores it. */
    static final class Count {
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

        Count(String name, List<String> systems, int[] tag, int scale, boolean trueCounted, int aceSideCount,
              Count base, String published) {
            if (tag.length != ShoeRun.KINDS) {
                throw new IllegalArgumentException("a count tags " + ShoeRun.KINDS + " kinds");
            }
            if (aceSideCount != 0 && !trueCounted) {
                throw new IllegalArgumentException(name + ": the ace side count adjusts a true count");
            }
            this.name = name;
            this.systems = Collections.unmodifiableList(new ArrayList<>(systems));
            this.tag = tag.clone();
            this.scale = scale;
            this.trueCounted = trueCounted;
            this.aceSideCount = aceSideCount;
            this.base = base;
            this.published = published;
            int sum = 0;
            for (int k = 0; k < ShoeRun.KINDS; k++) {
                sum += 2 * tag[k];
            }
            this.deckSum = sum;
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

        /** The bet key of a state with these cards dealt, depth of them in all. */
        int betKey(int[] dealt, int depth) {
            int rc = runningCount(dealt);
            if (!trueCounted) {
                return rc;
            }
            return roundedRatio(driftFree52(rc, depth) + sideCount52(dealt, depth), ShoeRun.CARDS - depth);
        }

        /**
         * The unrounded, drift-free true count, with the side count's adjustment for a variant:
         * RC_b over decks left, whatever way the count is played. The empirical betting and
         * insurance correlations are computed on it.
         */
        double trueCount(int[] dealt, int depth) {
            return (double) (driftFree52(runningCount(dealt), depth) + sideCount52(dealt, depth))
                    / (ShoeRun.CARDS - depth);
        }

        /** The play key when the deal's three cards add tagSum to a running count of rc. */
        int playKey(int rc, int depth, int tagSum) {
            if (!trueCounted) {
                return rc + tagSum;
            }
            return roundedRatio(driftFree52(rc + tagSum, depth + 3), ShoeRun.CARDS - depth - 3);
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

        /** The same count with an ace side count for betting, m the ten's tag in absolute value. */
        Count sideCounted() {
            if (!trueCounted || !balanced() || tag[0] != 0 || tag[1] != 0) {
                throw new IllegalStateException(name + " is not a balanced ace-neutral true count");
            }
            int m = Math.abs(rankTag(10));
            return new Count(name + " + ace side count", systems, tag, scale, true, m, this, published);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** numerator / denominator rounded to the nearest whole number, ties toward zero, as the simulator rounds. */
    static int roundedRatio(long numerator, int denominator) {
        return (int) GranularCount.roundToGrain((double) numerator / denominator, 1.0);
    }

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
}
