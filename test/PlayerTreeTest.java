import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The player tree on the run's stored states (FULL_INDEX_PLAYS.md section 2): it reproduces
 * the stored values under basic strategy, its reach adds up to its Hit values under
 * count-based play, and its contested walk gives the walk of every node's values and gaps.
 * PlayerTreeOrderTest holds it to every card order on small shoes.
 */
public class PlayerTreeTest {

    private static final double EXACT = 1e-12;
    private static final RoundRules RULES = StateRun.montreal();

    private static final CountKeys.Count HI_LO = PlayerTreeOrderTest.named("Hi-Lo");
    private static final CountKeys.Count RED_SEVEN = PlayerTreeOrderTest.named("Red Seven (Red 7)");
    private static final CountKeys.Count KISS_I = PlayerTreeOrderTest.named("KISS I");
    /** The catalog's unbalanced true count, so the drift correction moves its keys. */
    private static final CountKeys.Count BRH_I = PlayerTreeOrderTest.named("BRH-I");
    private static final CountKeys.Count[] COUNTS = {HI_LO, RED_SEVEN, KISS_I, BRH_I};

    /**
     * Under basic strategy, every part of every deal of 40 stored states spread through the file
     * has the stored V(Hit), within 1e-12, in both walks and for counts that split no rank, one
     * rank and two; and every start's stand value made conditional is the stored V(Stand). Every
     * state holds at least 85 cards, and the tree's own check that no round can run out passes.
     */
    @Test
    public void reproducesTheStoredValuesUnderTheChart() throws Exception {
        List<StateStore.Record> states = StoredStates.spread(40);
        int hits = 0;
        int stands = 0;
        double largest = 0;
        for (StateStore.Record r : states) {
            int[] shoe = r.ranksLeft();
            int cards = 0;
            for (int x : shoe) {
                cards += x;
            }
            assertTrue(cards >= 85, "shoe " + r.shoe + " round " + r.round + " holds " + cards + " cards");
            PlayerTree tree = new PlayerTree(new ExactRound(RULES), shoe);
            String at = "shoe " + r.shoe + " round " + r.round;
            for (CountKeys.Count c : COUNTS) {
                PlayerTree.Counted counted = tree.count(c, r.dealt);
                for (PlayerTree.Mode mode : PlayerTree.Mode.values()) {
                    PlayerTree.Walk walk = counted.walk(PlayerTree.CHART, mode);
                    for (int i = 0; i < Deals.COUNT; i++) {
                        double stored = r.values[StateValuer.slot(i, PlayerMove.Hit)];
                        if (Double.isNaN(stored)) {
                            assertTrue(Deals.playerNatural(i) || counted.chance[i] == 0, at + " deal " + i);
                            continue;
                        }
                        for (int q = 0; q < counted.parts(i); q++) {
                            double v = walk.hit(i, q);
                            assertEquals(stored, v, EXACT, at + " " + c.name + " " + mode + " deal " + i + " part " + q);
                            largest = Math.max(largest, Math.abs(stored - v));
                            hits++;
                        }
                    }
                }
            }
            for (int i = 0; i < Deals.COUNT; i++) {
                double stored = r.values[StateValuer.slot(i, PlayerMove.Stand)];
                if (Double.isNaN(stored)) {
                    if (Deals.playerNatural(i)) {
                        assertTrue(Double.isNaN(tree.standValue(Deals.P1[i], Deals.P2[i], Deals.UP[i])));
                    }
                    continue;
                }
                double v = tree.standValue(Deals.P1[i], Deals.P2[i], Deals.UP[i]) / noNatural(shoe, i);
                assertEquals(stored, v, EXACT, at + " deal " + i + " Stand");
                largest = Math.max(largest, Math.abs(stored - v));
                stands++;
            }
        }
        System.out.printf("PlayerTreeTest: %d Hit values (parts, both walks, four counts) and %d Stand values of "
                + "%d stored states, largest difference %.1e%n", hits, stands, states.size(), largest);
        assertTrue(hits > 40 * 4 * 2 * 500, hits + " Hit values compared");
    }

    private static double noNatural(int[] shoe, int deal) {
        int[] after = Deals.after(shoe, deal);
        int n = 0;
        for (int r = 1; r <= 10; r++) {
            n += after[r];
        }
        int natural = ExactRound.naturalHoleRank(Deals.UP[deal]);
        long naturals = natural == 0 ? 0 : after[natural];
        return (n - naturals) / (double) n;
    }

    /** Random thresholds at the contested spots around this centre key, the chart elsewhere. */
    private static PlayerTree.LaterPlay thresholds(Random rnd, int centre, int spread) {
        int[] at = new int[PlayerTree.SPOTS];
        for (int s = 0; s < at.length; s++) {
            at[s] = centre - spread + rnd.nextInt(2 * spread + 1);
        }
        return (total, soft, up, key) -> PlayerTree.contested(total, soft)
                ? key >= at[PlayerTree.spot(total, soft, up)]
                : PlayerTree.CHART.stands(total, soft, up, key);
    }

    /** A first move per (deal, part): the chart's, with about one in four turned to Hit or away from it. */
    private static PlayerTree.FirstHits firstMoves(Random rnd, PlayerTree.Counted counted) {
        boolean[] hits = new boolean[counted.partTagSum.length];
        int[] chart = ScoringStates.chartMoves(RULES);
        for (int i = 0; i < Deals.COUNT; i++) {
            for (int p = counted.partFrom[i]; p < counted.partFrom[i + 1]; p++) {
                boolean chartHits = StateValuer.MOVES[chart[i]] == PlayerMove.Hit;
                hits[p] = rnd.nextInt(4) == 0 != chartHits;
            }
        }
        return (deal, part) -> hits[counted.partFrom[deal] + part];
    }

    /** The centre of a state's play keys: the key with no tags seen beyond the cards dealt before the round. */
    private static int centre(CountKeys.Count c, PlayerTree.Counted counted) {
        return c.keyAfter(counted.rc, counted.depth, 0, 3);
    }

    /**
     * Reach is right: on stored states under count-based later plays and first moves, the sum
     * over every hand that ends of reach x what it ends with is the sum over hitting deals of
     * (1/q) P(i) V_hit(i), within 1e-12, in both walks, part by part for the kind-aware counts.
     */
    @Test
    public void reachAddsUpToTheHitValues() throws Exception {
        Random rnd = new Random(20261005L);
        List<StateStore.Record> states = StoredStates.spread(12);
        int checked = 0;
        double largest = 0;
        for (StateStore.Record r : states) {
            PlayerTree tree = new PlayerTree(new ExactRound(RULES), r.ranksLeft());
            double weight = 1 / r.q;
            for (CountKeys.Count c : COUNTS) {
                PlayerTree.Counted counted = tree.count(c, r.dealt);
                PlayerTree.LaterPlay later = thresholds(rnd, centre(c, counted), c.trueCounted ? 3 : 5);
                PlayerTree.FirstHits first = firstMoves(rnd, counted);
                for (PlayerTree.Mode mode : PlayerTree.Mode.values()) {
                    PlayerTree.Walk walk = counted.walk(later, mode);
                    walk.reach(first, weight);
                    double expected = 0;
                    for (int i = 0; i < Deals.COUNT; i++) {
                        for (int q = 0; q < counted.parts(i); q++) {
                            if (first.hits(i, q)) {
                                int p = counted.partFrom[i] + q;
                                expected += weight * counted.chance[i] * counted.partShare[p] * walk.hit(i, q)
                                        * counted.noNatural[i];
                            }
                        }
                    }
                    double mass = walk.terminalMass();
                    assertEquals(expected, mass, EXACT, "shoe " + r.shoe + " round " + r.round + " " + c.name + " " + mode);
                    largest = Math.max(largest, Math.abs(expected - mass));
                    checked++;
                }
            }
        }
        System.out.printf("PlayerTreeTest: reach identity on %d walks, largest difference %.1e%n", checked, largest);
    }

    /**
     * Walking only the nodes a contested decision can reach gives what walking every node gives,
     * term for term, when the play is the chart's outside the contested spots: the same Hit
     * value for every part, and the same gap sums at every contested (spot, key). The walk of
     * every node also has gaps at the other spots, which the contested walk leaves to the chart.
     */
    @Test
    public void theContestedWalkGivesTheWalkOfEveryNode() throws Exception {
        Random rnd = new Random(20261006L);
        List<StateStore.Record> states = StoredStates.spread(10);
        int cells = 0;
        int elsewhere = 0;
        double largest = 0;
        for (StateStore.Record r : states) {
            PlayerTree tree = new PlayerTree(new ExactRound(RULES), r.ranksLeft());
            for (CountKeys.Count c : COUNTS) {
                PlayerTree.Counted counted = tree.count(c, r.dealt);
                PlayerTree.LaterPlay later = thresholds(rnd, centre(c, counted), c.trueCounted ? 3 : 5);
                PlayerTree.FirstHits first = firstMoves(rnd, counted);
                Map<Long, Double>[] gaps = new Map[2];
                PlayerTree.Walk[] walks = new PlayerTree.Walk[2];
                for (PlayerTree.Mode mode : PlayerTree.Mode.values()) {
                    PlayerTree.Walk walk = counted.walk(later, mode);
                    walk.reach(first, 1 / r.q);
                    Map<Long, Double> g = new HashMap<>();
                    walk.gaps((spot, key, gap) -> g.merge(((long) spot << 32) | (key & 0xffffffffL), gap, Double::sum));
                    gaps[mode.ordinal()] = g;
                    walks[mode.ordinal()] = walk;
                }
                Map<Long, Double> every = gaps[PlayerTree.Mode.EVERY_NODE.ordinal()];
                Map<Long, Double> contested = gaps[PlayerTree.Mode.CONTESTED.ordinal()];
                for (Map.Entry<Long, Double> e : every.entrySet()) {
                    int spot = (int) (e.getKey() >>> 32);
                    if (!PlayerTree.contested(PlayerTree.spotTotal(spot), PlayerTree.spotSoft(spot))) {
                        elsewhere++;
                        continue;
                    }
                    Double other = contested.get(e.getKey());
                    assertNotNull(other, "a contested cell the contested walk missed");
                    assertEquals(e.getValue(), other, 1e-15, "spot " + spot);
                    largest = Math.max(largest, Math.abs(e.getValue() - other));
                    cells++;
                }
                for (long k : contested.keySet()) {
                    assertTrue(every.containsKey(k), "a cell only the contested walk has");
                }
                for (int p = 0; p < counted.partTagSum.length; p++) {
                    assertEquals(walks[0].partHit[p], walks[1].partHit[p], 0.0);
                }
            }
        }
        System.out.printf("PlayerTreeTest: %d contested (spot, key) cells agree between the walks, largest difference "
                + "%.1e; %d cells elsewhere in the walk of every node%n", cells, largest, elsewhere);
        assertTrue(cells > 1000 && elsewhere > 1000, cells + " cells, " + elsewhere + " elsewhere");
    }

    /** Random thresholds at every spot around this centre key: the chart's answer nowhere in particular. */
    private static PlayerTree.LaterPlay thresholdsEverywhere(Random rnd, int centre, int spread) {
        int[] at = new int[PlayerTree.SPOTS];
        for (int s = 0; s < at.length; s++) {
            at[s] = centre - spread + rnd.nextInt(2 * spread + 1);
        }
        return (total, soft, up, key) -> key >= at[PlayerTree.spot(total, soft, up)];
    }

    /** A (spot, key) cell as one number. */
    private static long cell(int spot, int key) {
        return ((long) spot << 32) | (key & 0xffffffffL);
    }

    /** What the deals whose first move hits are worth together: the sum over them of (1/q) P(i) (1 - P(nat|i)) V_hit, by part. */
    private static double hitting(PlayerTree.Counted counted, PlayerTree.Walk walk, PlayerTree.FirstHits first,
                                  double weight) {
        double sum = 0;
        for (int i = 0; i < Deals.COUNT; i++) {
            for (int q = 0; q < counted.parts(i); q++) {
                if (first.hits(i, q)) {
                    sum += weight * counted.chance[i] * counted.partShare[counted.partFrom[i] + q] * counted.noNatural[i]
                            * walk.hit(i, q);
                }
            }
        }
        return sum;
    }

    /**
     * A cell's gap is what flipping its play changes, which holds the gaps to something the
     * walks do not compute the same way. The hard total only grows along a path and a hand's
     * total and softness fix its hard total, so no path meets a spot twice, and the nodes of one
     * (spot, key) cell lie on no path through each other. Flipping the play at one cell then
     * changes the play at those nodes alone, each reached as before and worth after the flip
     * what the other answer was worth before it: the hitting deals' worth moves by the cell's
     * gap sum where the play hit there, and by minus it where it stood.
     *
     * On stored states, for the four counts and both walks, cells are flipped one at a time:
     * cells the walk gave gaps for, and cells it asked about and gave none for, whose flip must
     * change nothing. The contested walk's play is the chart outside the contested spots; the
     * walk of every node's has random thresholds at every spot, so the cells it flips include
     * hard 11 or less, hard 17 to 20 and soft 19 and 20, both stood on and hit.
     */
    @Test
    public void flippingACellChangesTheHittingDealsByItsGap() throws Exception {
        Random rnd = new Random(20261007L);
        int flips = 0;
        int stood = 0;
        int outside = 0;
        int without = 0;
        double largest = 0;
        for (StateStore.Record r : StoredStates.spread(6)) {
            PlayerTree tree = new PlayerTree(new ExactRound(RULES), r.ranksLeft());
            double weight = 1 / r.q;
            for (CountKeys.Count c : COUNTS) {
                PlayerTree.Counted counted = tree.count(c, r.dealt);
                int centre = centre(c, counted);
                int spread = c.trueCounted ? 3 : 5;
                PlayerTree.FirstHits first = firstMoves(rnd, counted);
                for (PlayerTree.Mode mode : PlayerTree.Mode.values()) {
                    PlayerTree.LaterPlay later = mode == PlayerTree.Mode.EVERY_NODE
                            ? thresholdsEverywhere(rnd, centre, spread) : thresholds(rnd, centre, spread);
                    java.util.Set<Long> asked = new java.util.HashSet<>();
                    PlayerTree.LaterPlay asking = (total, soft, up, key) -> {
                        asked.add(cell(PlayerTree.spot(total, soft, up), key));
                        return later.stands(total, soft, up, key);
                    };
                    PlayerTree.Walk walk = counted.walk(asking, mode);
                    walk.reach(first, weight);
                    Map<Long, Double> gaps = new HashMap<>();
                    walk.gaps((spot, key, gap) -> gaps.merge(cell(spot, key), gap, Double::sum));
                    for (long k : gaps.keySet()) {
                        assertTrue(asked.contains(k), "a gap at a cell the walk never asked about");
                    }
                    double before = hitting(counted, walk, first, weight);
                    List<Long> with = new java.util.ArrayList<>(new java.util.TreeSet<>(gaps.keySet()));
                    List<Long> none = new java.util.ArrayList<>();
                    for (long k : new java.util.TreeSet<>(asked)) {
                        if (!gaps.containsKey(k)) {
                            none.add(k);
                        }
                    }
                    java.util.Collections.shuffle(with, rnd);
                    java.util.Collections.shuffle(none, rnd);
                    List<Long> cells = new java.util.ArrayList<>(with.subList(0, Math.min(20, with.size())));
                    cells.addAll(none.subList(0, Math.min(4, none.size())));
                    for (long k : cells) {
                        int spot = (int) (k >>> 32);
                        int key = (int) k;
                        int total = PlayerTree.spotTotal(spot);
                        boolean soft = PlayerTree.spotSoft(spot);
                        int up = PlayerTree.spotUp(spot);
                        boolean stands = later.stands(total, soft, up, key);
                        PlayerTree.LaterPlay flipped = (t, s, u, kk) -> (PlayerTree.spot(t, s, u) == spot && kk == key)
                                != later.stands(t, s, u, kk);
                        double after = hitting(counted, counted.walk(flipped, mode), first, weight);
                        double gap = gaps.getOrDefault(k, 0.0);
                        String at = "shoe " + r.shoe + " round " + r.round + " " + c.name + " " + mode + ": "
                                + (soft ? "soft " : "hard ") + total + " v " + up + " at key " + key
                                + (stands ? ", stood" : ", hit");
                        assertEquals(stands ? -gap : gap, after - before, 1e-12, at);
                        largest = Math.max(largest, Math.abs((stands ? -gap : gap) - (after - before)));
                        flips++;
                        stood += stands ? 1 : 0;
                        outside += PlayerTree.contested(total, soft) ? 0 : 1;
                        without += gaps.containsKey(k) ? 0 : 1;
                    }
                }
            }
        }
        System.out.printf("PlayerTreeTest: %d one-cell flips (%d stood, %d outside the contested spots, %d of cells "
                + "with no gap), largest |change - gap| %.1e%n", flips, stood, outside, without, largest);
        assertTrue(flips > 6 * 4 * 2 * 20, flips + " flips");
        assertTrue(stood > flips / 5 && flips - stood > flips / 5, stood + " of " + flips + " flips stood");
        assertTrue(outside > flips / 5, outside + " of " + flips + " flips outside the contested spots");
        assertTrue(without > 0, "no cell without a gap was flipped");
    }

    /**
     * A play that differs from the chart outside the contested spots changes the walk of every
     * node and not the contested walk, which plays the chart there whatever it is told.
     */
    @Test
    public void theContestedWalkPlaysTheChartOutsideTheContestedSpots() throws Exception {
        StateStore.Record r = StoredStates.spread(3).get(1);
        PlayerTree tree = new PlayerTree(new ExactRound(RULES), r.ranksLeft());
        PlayerTree.Counted counted = tree.count(HI_LO, r.dealt);
        PlayerTree.LaterPlay odd = (total, soft, up, key) -> PlayerTree.contested(total, soft)
                ? PlayerTree.CHART.stands(total, soft, up, key) : total >= 9;
        PlayerTree.Walk chartEvery = counted.walk(PlayerTree.CHART, PlayerTree.Mode.EVERY_NODE);
        PlayerTree.Walk oddEvery = counted.walk(odd, PlayerTree.Mode.EVERY_NODE);
        PlayerTree.Walk oddContested = counted.walk(odd, PlayerTree.Mode.CONTESTED);
        int moved = 0;
        for (int p = 0; p < counted.partTagSum.length; p++) {
            assertEquals(chartEvery.partHit[p], oddContested.partHit[p], 0.0);
            moved += Math.abs(chartEvery.partHit[p] - oddEvery.partHit[p]) > 1e-6 ? 1 : 0;
        }
        assertTrue(moved > 50, moved + " deals moved");
    }

    /**
     * The tree's size: 3,061 hands of two or more cards a start reaches by hitting, 54 of them
     * the starts that are not a natural, and 768 that a contested walk visits for each up-card
     * (the review's count, FULL_INDEX_PLAYS.md section 6). A count that splits a rank has more
     * nodes, each a hand with its classes.
     */
    @Test
    public void theTreeHoldsTheHandsThePlanCounts() {
        PlayerTree.Hands hands = PlayerTree.Hands.ALL;
        assertEquals(3061, hands.size);
        int starts = 0;
        for (int h = 0; h < hands.size; h++) {
            starts += hands.cards[h] == 2 ? 1 : 0;
            assertTrue(hands.hard[h] <= 21);
        }
        assertEquals(54, starts);
        int[] plain = new int[11];
        Arrays.fill(plain, 1);
        PlayerTree.Graph g = PlayerTree.Graph.of(plain);
        assertEquals(hands.size, g.size);
        assertEquals(768, g.live(PlayerTree.Mode.CONTESTED).node.length);
        int[] classes = new int[11];
        for (int r = 1; r <= 10; r++) {
            classes[r] = KISS_I.classes(r);
        }
        assertTrue(PlayerTree.Graph.of(classes).size > 2 * hands.size);
    }

    /** A shoe a round could run out of is refused; one card more than a round can take is not. */
    @Test
    public void aShoeARoundCouldRunOutOfIsRefused() {
        ExactRound engine = new ExactRound(RULES);
        int[] shoe = new int[11];
        shoe[10] = 6;
        shoe[1] = 2;
        shoe[2] = 2;
        // 2 aces and 2 twos add up to 6 within 32; tens take the rest of the budget: 2 more. So
        // 6 + 6 = 12 cards can go, and the shoe holds 10.
        assertEquals(12, PlayerTree.mostCardsARoundTakes(shoe));
        assertThrows(IllegalArgumentException.class, () -> new PlayerTree(engine, shoe));
        shoe[10] = 8;
        assertEquals(12, PlayerTree.mostCardsARoundTakes(shoe));
        new PlayerTree(engine, shoe);
    }

    /**
     * A deal's parts are CountKeys.tagSums' partition, in its order and with its shares, and the
     * class assignments in each part add up to its share.
     */
    @Test
    public void aDealsPartsAreTheCountsPartition() throws Exception {
        for (StateStore.Record r : StoredStates.spread(4)) {
            PlayerTree tree = new PlayerTree(new ExactRound(RULES), r.ranksLeft());
            for (CountKeys.Count c : COUNTS) {
                PlayerTree.Counted counted = tree.count(c, r.dealt);
                int[][] left = c.classesLeft(r.dealt);
                for (int i = 0; i < Deals.COUNT; i++) {
                    if (Deals.playerNatural(i) || counted.chance[i] == 0) {
                        assertEquals(0, counted.parts(i));
                        continue;
                    }
                    double[][] parts = c.tagSums(left, i);
                    assertEquals(parts.length, counted.parts(i));
                    double[] summed = new double[parts.length];
                    for (int q = counted.compFrom[i]; q < counted.compFrom[i + 1]; q++) {
                        summed[counted.compPart[q] - counted.partFrom[i]] += counted.compChance[q];
                    }
                    for (int q = 0; q < parts.length; q++) {
                        assertEquals((int) parts[q][0], counted.partTagSum[counted.partFrom[i] + q]);
                        assertEquals(parts[q][1], counted.partShare[counted.partFrom[i] + q], 0.0);
                        assertEquals(parts[q][1], summed[q], 1e-15);
                    }
                    assertEquals(Deals.probability(r.ranksLeft(), i), counted.chance[i], 0.0);
                }
            }
        }
        assertFalse(HI_LO.kindAware);
        assertTrue(RED_SEVEN.kindAware && KISS_I.kindAware);
        assertEquals(2, KISS_I.classes(2));
        assertEquals(2, KISS_I.classes(10));
        assertEquals(2, RED_SEVEN.classes(7));
    }
}
