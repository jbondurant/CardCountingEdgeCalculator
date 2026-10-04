import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The player tree held to every card order, by kind (FULL_INDEX_PLAYS.md section 2).
 *
 * A small state is dealt in every order of its cards, each card a kind (ShoeRun), and
 * ConcreteRound plays each order with a test policy that keeps its own count of the kinds it
 * is shown through the round's kind hook: the deal's three cards and every draw, never the
 * hole card. The policy hits or stands at hard 12 to 16 and soft 13 to 18 by random
 * thresholds on its count key, plays the chart elsewhere, and works the key out itself, in
 * whole numbers. Averaged over the orders without a dealer natural, by the tag-class part of
 * the deal's three cards, the payoff must be the tree's Hit value for that part.
 *
 * The state's depth is 416 less its cards, so a true count's divisor is the handful of cards
 * not yet seen and moves with every card. The counts are Hi-Lo (a true count), Red Seven (the
 * 7 by colour) and KISS I (the 2 by colour and the ten-valued cards by face, so the count does
 * not fix the classes a hand holds). The deals include A,A, A,2, pairs, and ace and ten
 * up-cards. Every state is big enough that no order runs out of cards, which PlayerTree
 * requires and checks.
 *
 * The same holds for a split under a count-group play: ConcreteRound plays the split hands
 * with the SplitPlay of the group the deal's play key falls in, never moving the count, and the
 * average by part must be what SplitValues gives for that group's play.
 *
 * Orders are enumerated by prefix: an order is played with the cards after a prefix in any
 * arrangement, and if the round used no card past the prefix every order sharing it pays the
 * same; otherwise the prefix is extended by every card left. Cards of one tag class of one rank
 * are told apart only by kinds that every count tested here tags alike, so one kind stands for
 * the class, with the class's count as its weight.
 */
public class PlayerTreeOrderTest {

    private static final double EXACT = 1e-12;
    private static final RoundRules RULES = StateRun.montreal();
    private static final IntFunction<SplitPlay> NO_SPLIT = k -> {
        throw new AssertionError("no split here");
    };

    static CountKeys.Count named(String system) {
        for (CountKeys.Count c : CountKeys.catalog()) {
            if (c.systems.contains(system)) {
                return c;
            }
        }
        throw new IllegalArgumentException(system);
    }

    /** The count key at a decision, worked out here in whole numbers, ties toward zero. */
    static int key(CountKeys.Count c, int rc, int depth, int tags, int seen) {
        if (!c.trueCounted) {
            return rc + tags;
        }
        long num = 52L * (rc + tags) - (long) c.deckSum * (depth + seen);
        long den = ShoeRun.CARDS - depth - seen;
        long whole = Math.abs(num) / den;
        if (2 * (Math.abs(num) - whole * den) > den) {
            whole++;
        }
        return (int) (Long.signum(num) * whole);
    }

    /** A small state for one count: the cards left in each (rank, tag class) unit. */
    static final class Small {
        final CountKeys.Count count;
        final List<int[]> units = new ArrayList<>();   // {rank, class, kind, tag}
        final int[] left;
        final int[] dealt = new int[ShoeRun.KINDS];
        final int[] shoe = new int[11];
        final int cards;
        final int depth;
        final int rc;

        Small(CountKeys.Count count, int[] left) {
            this.count = count;
            for (int r = 1; r <= 10; r++) {
                for (int k = 0; k < count.classes(r); k++) {
                    for (int kind = 0; kind < ShoeRun.KINDS; kind++) {
                        if (ShoeRun.rankOf(kind) == r && count.classOf(kind) == k) {
                            units.add(new int[]{r, k, kind, count.tag[kind]});
                            break;
                        }
                    }
                }
            }
            if (left.length != units.size()) {
                throw new IllegalArgumentException(units.size() + " units");
            }
            this.left = left.clone();
            Arrays.fill(dealt, 2 * ShoeRun.DECKS);
            int n = 0;
            for (int u = 0; u < units.size(); u++) {
                dealt[units.get(u)[2]] -= left[u];
                shoe[units.get(u)[0]] += left[u];
                n += left[u];
            }
            cards = n;
            depth = ShoeRun.CARDS - n;
            rc = count.runningCount(dealt);
        }

        int unit(int rank, int cls) {
            for (int u = 0; u < units.size(); u++) {
                if (units.get(u)[0] == rank && units.get(u)[1] == cls) {
                    return u;
                }
            }
            throw new IllegalArgumentException(rank + " class " + cls);
        }

        int rank(int u) {
            return units.get(u)[0];
        }

        int tag(int u) {
            return units.get(u)[3];
        }

        int kind(int u) {
            return units.get(u)[2];
        }
    }

    /** One order's player: its own count from the kinds shown, the given first move, then the plays. */
    static final class Player implements RoundPolicy, ConcreteRound.KindWatcher {
        final Small st;
        final PlayerMove first;
        final PlayerTree.LaterPlay later;
        final IntFunction<SplitPlay> splitAt;
        final Map<Integer, Integer> answers;
        final Map<Integer, int[]> keys;
        int seen;
        int tags;
        int dealKey;

        Player(Small st, PlayerMove first, PlayerTree.LaterPlay later, IntFunction<SplitPlay> splitAt,
               Map<Integer, Integer> answers, Map<Integer, int[]> keys) {
            this.st = st;
            this.first = first;
            this.later = later;
            this.splitAt = splitAt;
            this.answers = answers;
            this.keys = keys;
        }

        @Override
        public void shown(int kind) {
            seen++;
            tags += st.count.tag[kind];
        }

        @Override
        public PlayerMove choose(Decision d) {
            int key = key(st.count, st.rc, st.depth, tags, seen);
            if (d.firstDecision && !d.fromSplit) {
                assertEquals(3, seen, "the first decision sees the deal's three cards");
                dealKey = key;
                return first;
            }
            if (d.fromSplit) {
                return splitAt.apply(dealKey).choose(d);
            }
            if (!d.canHit) {
                return PlayerMove.Stand;
            }
            boolean stands = later.stands(d.total, d.soft, d.upCard, key);
            int spot = PlayerTree.spot(d.total, d.soft, d.upCard);
            answers.merge(spot, stands ? 1 : 2, (a, b) -> a | b);
            int[] range = keys.computeIfAbsent(spot, x -> new int[]{key, key});
            range[0] = Math.min(range[0], key);
            range[1] = Math.max(range[1], key);
            return stands ? PlayerMove.Stand : PlayerMove.Hit;
        }
    }

    /** Per part of the deal, by tag sum: payoff x weight and weight over orders without a natural, and all weight. */
    static Map<Integer, double[]> everyOrder(Small st, int p1, int p2, int up, PlayerMove first,
                                             PlayerTree.LaterPlay later, IntFunction<SplitPlay> splitAt,
                                             Map<Integer, Integer> answers, Map<Integer, int[]> keys) {
        Map<Integer, double[]> sums = new HashMap<>();
        int[] left = st.left.clone();
        int[] seq = new int[st.cards];
        int[] ranksAt = {p1, up, p2};
        dealCards(st, left, seq, 0, ranksAt, 1.0, first, later, splitAt, answers, keys, sums);
        return sums;
    }

    private static void dealCards(Small st, int[] left, int[] seq, int at, int[] ranksAt, double w, PlayerMove first,
                                  PlayerTree.LaterPlay later, IntFunction<SplitPlay> splitAt,
                                  Map<Integer, Integer> answers, Map<Integer, int[]> keys, Map<Integer, double[]> sums) {
        if (at == 3) {
            int tag = st.tag(seq[0]) + st.tag(seq[1]) + st.tag(seq[2]);
            explore(st, left, seq, 3, w, tag, first, later, splitAt, answers, keys, sums);
            return;
        }
        for (int u = 0; u < left.length; u++) {
            if (left[u] > 0 && st.rank(u) == ranksAt[at]) {
                double w2 = w * left[u] / (st.cards - at);
                left[u]--;
                seq[at] = u;
                dealCards(st, left, seq, at + 1, ranksAt, w2, first, later, splitAt, answers, keys, sums);
                left[u]++;
            }
        }
    }

    private static void explore(Small st, int[] left, int[] seq, int len, double w, int tag, PlayerMove first,
                                PlayerTree.LaterPlay later, IntFunction<SplitPlay> splitAt,
                                Map<Integer, Integer> answers, Map<Integer, int[]> keys, Map<Integer, double[]> sums) {
        int[] ranks = new int[st.cards];
        int[] kinds = new int[st.cards];
        for (int i = 0; i < len; i++) {
            ranks[i] = st.rank(seq[i]);
            kinds[i] = st.kind(seq[i]);
        }
        int i = len;
        for (int u = 0; u < left.length; u++) {
            for (int k = 0; k < left[u]; k++) {
                ranks[i] = st.rank(u);
                kinds[i] = st.kind(u);
                i++;
            }
        }
        Player player = new Player(st, first, later, splitAt, answers, keys);
        ConcreteRound.Result r = ConcreteRound.play(ranks, kinds, 0, RULES, player, player);
        if (r.next <= len) {
            double[] s = sums.computeIfAbsent(tag, t -> new double[6]);
            add(s, 2, w);
            if (!r.dealerNatural) {
                add(s, 0, w * r.payoff);
                add(s, 1, w);
            }
            return;
        }
        for (int u = 0; u < left.length; u++) {
            if (left[u] > 0) {
                double w2 = w * left[u] / (st.cards - len);
                left[u]--;
                seq[len] = u;
                explore(st, left, seq, len + 1, w2, tag, first, later, splitAt, answers, keys, sums);
                left[u]++;
            }
        }
    }

    /**
     * Adds x to the sum at s[i], keeping its running compensation at s[i + 3] (Neumaier). A
     * split's orders run to millions of small terms, whose plain sum drifts by 1e-11.
     */
    private static void add(double[] s, int i, double x) {
        double t = s[i] + x;
        s[i + 3] += Math.abs(s[i]) >= Math.abs(x) ? (s[i] - t) + x : (x - t) + s[i];
        s[i] = t;
    }

    /** The compensated sum at s[i]. */
    static double sum(double[] s, int i) {
        return s[i] + s[i + 3];
    }

    /**
     * Random thresholds at the contested spots, the chart elsewhere: a spot stands from its
     * threshold up. Each threshold is drawn from the keys the chart's play meets at that spot,
     * so that both answers come up wherever the count moves.
     */
    static PlayerTree.LaterPlay thresholds(Random rnd, Map<Integer, int[]> keys) {
        int[] at = new int[PlayerTree.SPOTS];
        for (int s = 0; s < at.length; s++) {
            int[] range = keys.get(s);
            at[s] = range == null ? 0 : range[0] + 1 + rnd.nextInt(Math.max(1, range[1] - range[0]));
        }
        return (total, soft, up, key) -> PlayerTree.contested(total, soft)
                ? key >= at[PlayerTree.spot(total, soft, up)]
                : PlayerTree.CHART.stands(total, soft, up, key);
    }

    /** The keys the chart's play meets at each spot, over every order. */
    static Map<Integer, int[]> keysMet(Small st, int p1, int p2, int up, PlayerMove first,
                                       IntFunction<SplitPlay> splitAt) {
        Map<Integer, int[]> keys = new HashMap<>();
        everyOrder(st, p1, p2, up, first, PlayerTree.CHART, splitAt, new HashMap<>(), keys);
        return keys;
    }

    /**
     * The most cards a round that splits this pair can take, as ExactRound bounds it, from the
     * shoe left after the deal: the hole card, two second cards a split, a last card per hand
     * and the dealer's last, and the smallest cards adding up to 18 a hand and 14. The deal's
     * three cards are added.
     */
    static int mostCardsASplitTakes(int[] state, int rank, int up) {
        int[] after = state.clone();
        after[rank] -= 2;
        after[up]--;
        int hands = Math.min(RULES.maxHandsFor(rank), 2 + after[rank]);
        int budget = 18 * hands + 14;
        int smallest = 0;
        for (int r = 1; r <= 10 && budget >= r; r++) {
            int take = Math.min(after[r], budget / r);
            smallest += take;
            budget -= take * r;
        }
        return 3 + 1 + 2 * (hands - 1) + hands + 1 + smallest;
    }

    /**
     * A small state holding the deal's cards, of both classes where the count splits their
     * rank, and mostly high cards besides, so no order runs out; extra cards are added until
     * it cannot.
     */
    static Small state(Random rnd, CountKeys.Count count, int p1, int p2, int up, boolean split) {
        Small probe = new Small(count, new int[unitsOf(count)]);
        int[] left = new int[probe.units.size()];
        for (int r : new int[]{p1, p2, up}) {
            left[probe.unit(r, rnd.nextInt(count.classes(r)))]++;
        }
        for (int r : new int[]{p1, p2, up}) {
            if (count.classes(r) > 1) {
                left[probe.unit(r, rnd.nextInt(count.classes(r)))]++;
            }
        }
        int tens = 5 + rnd.nextInt(3);
        for (int t = 0; t < tens; t++) {
            left[probe.unit(10, rnd.nextInt(count.classes(10)))]++;
        }
        left[probe.unit(9, 0)] += 1 + rnd.nextInt(2);
        for (int extra = 0; extra < 3; extra++) {
            int r = 1 + rnd.nextInt(9);
            if (split && r == p1) {
                continue;
            }
            left[probe.unit(r, rnd.nextInt(count.classes(r)))]++;
        }
        while (true) {
            Small st = new Small(count, left);
            int need = split ? mostCardsASplitTakes(st.shoe, p1, up) : PlayerTree.mostCardsARoundTakes(st.shoe);
            if (st.cards >= need) {
                return st;
            }
            left[probe.unit(10, rnd.nextInt(count.classes(10)))]++;
        }
    }

    private static int unitsOf(CountKeys.Count count) {
        int n = 0;
        for (int r = 1; r <= 10; r++) {
            n += count.classes(r);
        }
        return n;
    }

    /** The largest difference any comparison here has found. */
    private static double largest;

    /** The tree's Hit values against the average over every order, part by part, in both walks. */
    private static int checkHit(Small st, int p1, int p2, int up, PlayerTree.LaterPlay later,
                                Map<Integer, Integer> answers, String what) {
        PlayerTree tree = new PlayerTree(new ExactRound(RULES), st.shoe);
        PlayerTree.Counted counted = tree.count(st.count, st.dealt);
        int deal = Deals.index(p1, p2, up);
        Map<Integer, double[]> orders = everyOrder(st, p1, p2, up, PlayerMove.Hit, later, NO_SPLIT, answers,
                new HashMap<>());
        double all = 0;
        for (double[] s : orders.values()) {
            all += sum(s, 2);
        }
        int parts = counted.parts(deal);
        assertEquals(orders.size(), parts, what + ": parts");
        for (PlayerTree.Mode mode : PlayerTree.Mode.values()) {
            PlayerTree.Walk walk = counted.walk(later, mode);
            for (int q = 0; q < parts; q++) {
                int tag = counted.partTagSum[counted.partFrom[deal] + q];
                double[] s = orders.get(tag);
                assertNotNull(s, what + ": no order has tag sum " + tag);
                double average = sum(s, 0) / sum(s, 1);
                assertEquals(sum(s, 2) / all, counted.partShare[counted.partFrom[deal] + q], EXACT, what + ": share of part " + q);
                assertEquals(average, walk.hit(deal, q), EXACT, what + " " + mode + ": part " + q + " (tag sum " + tag + ")");
                largest = Math.max(largest, Math.abs(average - walk.hit(deal, q)));
            }
        }
        return parts;
    }

    @Test
    public void hitValuesAreTheAverageOverEveryOrderByKind() {
        Random rnd = new Random(20261003L);
        Object[][] cases = {
                {"Hi-Lo", new int[][]{{1, 1, 10}, {1, 2, 1}, {8, 8, 10}, {10, 6, 10}, {9, 3, 1}, {2, 3, 6}, {7, 7, 9}, {1, 6, 1}}},
                {"Red Seven (Red 7)", new int[][]{{7, 7, 10}, {7, 9, 7}, {1, 7, 1}, {1, 1, 7}, {1, 2, 10}, {10, 7, 7}, {6, 7, 10}, {5, 5, 7}}},
                {"KISS I", new int[][]{{2, 10, 10}, {2, 2, 1}, {10, 10, 2}, {1, 2, 10}, {1, 1, 10}, {2, 4, 2}, {3, 10, 7}, {6, 2, 10}}},
        };
        int compared = 0;
        int both = 0;
        for (Object[] c : cases) {
            CountKeys.Count count = named((String) c[0]);
            for (int[] d : (int[][]) c[1]) {
                for (int rep = 0; rep < 2; rep++) {
                    Small st = state(rnd, count, d[0], d[1], d[2], false);
                    PlayerTree.LaterPlay later = thresholds(rnd, keysMet(st, d[0], d[1], d[2], PlayerMove.Hit, NO_SPLIT));
                    Map<Integer, Integer> answers = new HashMap<>();
                    String what = count.name + " " + d[0] + "," + d[1] + " vs " + d[2] + " on " + Arrays.toString(st.left);
                    compared += checkHit(st, d[0], d[1], d[2], later, answers, what);
                    for (int a : answers.values()) {
                        both += a == 3 ? 1 : 0;
                    }
                }
            }
        }
        System.out.printf("PlayerTreeOrderTest: %d parts of %d deals compared, largest difference %.1e, "
                + "%d spots played both ways%n", compared, 2 * 24, largest, both);
        assertTrue(compared > 60, compared + " parts compared");
        // The count has to change decisions for the check to mean anything: spots where the
        // same thresholds both stood and hit, at different keys.
        assertTrue(both > 40, "only " + both + " spots played both ways");
    }

    /**
     * A random post-split play for one group: hit or stand at random at the contested totals
     * and as the chart elsewhere, and each deal's first moves in a random order of value.
     */
    static SplitPlay randomSplitPlay(Random rnd, int rank, int up) {
        boolean[] stands = new boolean[2 * 22];
        for (int total = 4; total <= 20; total++) {
            for (int soft = 0; soft <= 1; soft++) {
                stands[soft * 22 + total] = PlayerTree.contested(total, soft == 1) ? rnd.nextBoolean()
                        : PlayerTree.CHART.stands(total, soft == 1, up, 0);
            }
        }
        PlayerMove[][] ranked = new PlayerMove[Deals.COUNT][];
        for (int i = 0; i < Deals.COUNT; i++) {
            List<PlayerMove> moves = new ArrayList<>(Arrays.asList(PlayerMove.Stand, PlayerMove.Hit, PlayerMove.Double));
            if (Deals.P1[i] == Deals.P2[i]) {
                moves.add(PlayerMove.Split);
            }
            java.util.Collections.shuffle(moves, rnd);
            ranked[i] = moves.toArray(new PlayerMove[0]);
        }
        return new SplitPlay(rank, up, (total, soft) -> stands[(soft ? 22 : 0) + total], i -> ranked[i]);
    }

    /**
     * A split under a count-group play: ConcreteRound plays the split hands with the play of the
     * group the deal's play key falls in, and the average over every order of each part must be
     * what SplitValues, and so ExactRound, gives for that part's group. With two groups whose
     * boundary falls among the parts' keys, the parts of a kind-aware deal play differently.
     */
    @Test
    public void splitValuesAreTheAverageOverEveryOrderUnderACountGroupPlay() {
        Random rnd = new Random(20261004L);
        Object[][] cases = {
                {"Hi-Lo", new int[][]{{8, 8, 10}, {9, 9, 1}, {1, 1, 6}, {3, 3, 10}}},
                {"Red Seven (Red 7)", new int[][]{{7, 7, 10}, {7, 7, 7}, {7, 7, 1}, {8, 8, 7}}},
                {"KISS I", new int[][]{{2, 2, 10}, {10, 10, 2}, {2, 2, 2}, {6, 6, 10}}},
        };
        int compared = 0;
        int groupsDiffer = 0;
        for (Object[] c : cases) {
            CountKeys.Count count = named((String) c[0]);
            for (int[] d : (int[][]) c[1]) {
                int r = d[0];
                int up = d[2];
                int deal = Deals.index(r, r, up);
                Small st = state(rnd, count, r, r, up, true);
                PlayerTree tree = new PlayerTree(new ExactRound(RULES), st.shoe);
                PlayerTree.Counted counted = tree.count(count, st.dealt);
                int lo = Integer.MAX_VALUE;
                int hi = Integer.MIN_VALUE;
                for (int q = 0; q < counted.parts(deal); q++) {
                    int k = count.playKey(st.rc, st.depth, counted.partTagSum[counted.partFrom[deal] + q]);
                    lo = Math.min(lo, k);
                    hi = Math.max(hi, k);
                }
                int boundary = lo + 1 + rnd.nextInt(Math.max(1, hi - lo));
                SplitPlay[] plays = {randomSplitPlay(rnd, r, up), randomSplitPlay(rnd, r, up)};
                IntFunction<SplitPlay> splitAt = k -> plays[k < boundary ? 0 : 1];
                Map<Integer, double[]> orders = everyOrder(st, r, r, up, PlayerMove.Split, PlayerTree.CHART, splitAt,
                        new HashMap<>(), new HashMap<>());
                SplitValues values = new SplitValues(RULES, st.shoe, null);
                assertEquals(orders.size(), counted.parts(deal));
                java.util.Set<Integer> groups = new java.util.HashSet<>();
                for (int q = 0; q < counted.parts(deal); q++) {
                    int tag = counted.partTagSum[counted.partFrom[deal] + q];
                    int k = count.playKey(st.rc, st.depth, tag);
                    groups.add(k < boundary ? 0 : 1);
                    double[] s = orders.get(tag);
                    double average = sum(s, 0) / sum(s, 1);
                    double v = values.value(deal, splitAt.apply(k));
                    assertEquals(average, v, EXACT, count.name + " " + r + "," + r + " vs " + up + " part " + q
                            + " on " + Arrays.toString(st.left));
                    largest = Math.max(largest, Math.abs(average - v));
                    compared++;
                }
                groupsDiffer += groups.size() > 1 ? 1 : 0;
            }
        }
        System.out.printf("PlayerTreeOrderTest: %d split parts compared, %d deals whose parts play in two groups, "
                + "largest difference so far %.1e%n", compared, groupsDiffer, largest);
        assertTrue(compared >= 20, compared + " split parts compared");
        assertTrue(groupsDiffer >= 4, "only " + groupsDiffer + " deals had parts in both groups");
    }
}
