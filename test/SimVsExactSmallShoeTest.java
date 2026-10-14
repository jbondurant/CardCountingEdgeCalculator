import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D3 of SIM_CHECK.md section 5, tiny shoes, on the engine side and compiling both programs: the
 * clean-room simulator's round (sim.Round, under its own sim.Rules.montreal) averaged over every
 * order of a 9-card shoe equals ExactRound's value of the same deal (ROUND_CONTRACT.md, under
 * the engine's Montreal rules) for every first move and every policy, within 1e-12.
 *
 * How the two meet. ExactRound values a deal, the player's two cards and the up-card, given no
 * dealer natural, the hole card and every later card drawn from what is left. The simulator plays
 * a real round from a fixed order: the player's first card, the up-card, the player's second card,
 * the hole card, then each draw. So for each deal a shoe can make, its three cards are put first
 * (p1, up, p2) and the simulator plays every order of the six cards left, all 720; the orders whose
 * hole card makes a dealer natural are left out, as ExactRound conditions them out, and the rest
 * are averaged, each order as likely as any other. A shoe too short for some order's round makes
 * ExactRound throw (IllegalStateException), and then the simulator must have run out on some
 * order too, and the other way round; a deal every one of whose hole cards makes a natural is
 * refused by ExactRound (IllegalArgumentException), and the simulator then has no order to average.
 *
 * The policies, each a deterministic function of what a player sees, written once here and
 * handed to both programs through their own interfaces (RoundPolicy, sim.Round.Player): the chart,
 * on the engine side BasicStrategy.chart() and on the simulator's its own reading of chart.tsv as
 * StrategyExport writes it (sim.Chart.load, fingerprint 1af534aac9abcb39); hit below 17; double,
 * split and hit wherever allowed; and a policy that splits every pair and stands on 12 and up.
 * First moves: every one legal on the deal under Montreal's rules (Stand, Hit, Double, and Split
 * on a pair; no surrender; a natural stands). Insurance is never taken, so the simulator's main
 * result is what ExactRound values.
 *
 * The cases are fixed here, committed before the first pilot (SIM_CHECK.md's identity table
 * records this file's commit): six shoes chosen for pairs of every kind (aces, eights, tens),
 * dealer naturals with an ace and a ten up, soft hands, and shoes too short for some rounds. What
 * was compared is counted and each feature must be met.
 */
public class SimVsExactSmallShoeTest {

    static final double TOLERANCE = 1e-12;
    static final String CHART_FINGERPRINT = "1af534aac9abcb39";

    /** The shoes, by rank, nine cards each. */
    static final int[][] SHOES = {
            {1, 1, 10, 10, 10, 8, 8, 6, 5},
            {2, 3, 4, 5, 6, 7, 8, 9, 10},
            {1, 2, 2, 3, 3, 4, 7, 10, 10},
            {8, 8, 8, 3, 2, 10, 6, 5, 4},
            {1, 1, 1, 10, 9, 6, 5, 4, 2},
            {10, 10, 10, 10, 6, 5, 4, 3, 2},
    };

    /** One policy, as a function of what a player sees. Moves are Stand 0, Hit 1, Double 2, Split 3, Surrender 4. */
    interface Rule {
        int move(int total, boolean soft, int cards, int pair, int up, boolean fromSplit, boolean first, boolean canHit,
                 boolean canDouble, boolean canSplit, boolean canSurrender);
    }

    static final Rule HIT_BELOW_17 = (t, soft, n, pair, up, split, first, h, d, p, r) -> h && t < 17 ? 1 : 0;
    static final Rule AGGRESSIVE = (t, soft, n, pair, up, split, first, h, d, p, r) ->
            p ? 3 : d ? 2 : h && t < 15 ? 1 : 0;
    static final Rule SPLIT_THEN_STAND = (t, soft, n, pair, up, split, first, h, d, p, r) ->
            p ? 3 : h && t < 12 ? 1 : 0;

    /** The engine's side of a rule. */
    static RoundPolicy engine(Rule rule) {
        return d -> PlayerMove.values()[rule.move(d.total, d.soft, d.numCards, d.pairRank, d.upCard, d.fromSplit,
                d.firstDecision, d.canHit, d.canDouble, d.canSplit, d.canSurrender)];
    }

    /** The simulator's side of a rule: never insures. */
    static sim.Round.Player simulator(Rule rule) {
        return new sim.Round.Player() {
            @Override
            public boolean insure(sim.Round.Decision d) {
                return false;
            }

            @Override
            public int choose(sim.Round.Decision d) {
                return rule.move(d.total, d.soft, d.cards, d.pair, d.up, d.fromSplit, d.first,
                        (d.legal & sim.Round.L_HIT) != 0, (d.legal & sim.Round.L_DOUBLE) != 0,
                        (d.legal & sim.Round.L_SPLIT) != 0, (d.legal & sim.Round.L_SURRENDER) != 0);
            }
        };
    }

    /** The simulator's kind for a rank (SHOE_SPEC.md A1: kinds 2(r - 1) and 2(r - 1) + 1 for ranks 1 to 9, 18 to 25 for ten). */
    static int kind(int rank) {
        int k = rank == 10 ? 18 : 2 * (rank - 1);
        assertEquals(rank, sim.Shoe.rankOf(k));
        return k;
    }

    /** What was compared, and what each comparison met. */
    static final class Count {
        int deals;
        int compared;
        int bothShort;
        int bothAllNatural;
        int rounds;
        int excluded;
        int splits;
        int naturals;
        int doubles;
        int softHands;
        double worst;
    }

    /** Every permutation of a, in place, handed to each in turn. */
    static void permutations(int[] a, int k, java.util.function.Consumer<int[]> each) {
        if (k == a.length) {
            each.accept(a);
            return;
        }
        for (int i = k; i < a.length; i++) {
            int x = a[k];
            a[k] = a[i];
            a[i] = x;
            permutations(a, k + 1, each);
            a[i] = a[k];
            a[k] = x;
        }
    }

    /** The simulator's average over every order of rest after the deal, given no dealer natural; null when it ran out of cards on some order; NaN when every order was a dealer natural. */
    static double[] simulated(int p1, int up, int p2, int[] rest, int first, sim.Round.Player player, Count c) {
        sim.Round round = new sim.Round(sim.Rules.montreal());
        int[] order = new int[3 + rest.length];
        order[0] = kind(p1);
        order[1] = kind(up);
        order[2] = kind(p2);
        double[] sum = new double[2];
        boolean[] ranOut = {false};
        int[] cards = new int[rest.length];
        for (int i = 0; i < rest.length; i++) {
            cards[i] = kind(rest[i]);
        }
        sim.Round.Deck deck = i -> {
            if (i >= order.length) {
                throw new IllegalStateException("past the last card");
            }
            return order[i];
        };
        permutations(cards, 0, perm -> {
            System.arraycopy(perm, 0, order, 3, perm.length);
            try {
                round.play(deck, 0, player, first);
            } catch (IllegalStateException e) {
                ranOut[0] = true;
                return;
            }
            c.rounds++;
            if (round.dealerNatural) {
                c.excluded++;
                return;
            }
            c.splits += round.handsPlayed > 1 ? 1 : 0;
            sum[0] += round.main;
            sum[1]++;
        });
        if (ranOut[0]) {
            return null;
        }
        return new double[]{sum[1] == 0 ? Double.NaN : sum[0] / sum[1]};
    }

    /** The legal first moves of a deal under Montreal's rules: a natural stands; else Stand, Hit, Double, and Split on a pair. */
    static List<Integer> firstMoves(int p1, int p2) {
        if (p1 + p2 == 11 && (p1 == 1 || p2 == 1)) {
            return List.of(0);
        }
        List<Integer> out = new ArrayList<>(List.of(0, 1, 2));
        if (p1 == p2) {
            out.add(3);
        }
        return out;
    }

    @Test
    public void theSimulatorsRoundsAverageToExactRoundOnTinyShoes(@TempDir Path dir) throws Exception {
        Path chartFile = dir.resolve("chart.tsv");
        StrategyExport.writeChart(chartFile, BasicStrategy.chart());
        sim.Chart chart = sim.Chart.load(chartFile);
        assertEquals(CHART_FINGERPRINT, chart.fingerprint());
        String[] names = {"the chart", "hit below 17", "double, split and hit wherever allowed", "split, then stand on 12"};
        RoundPolicy[] engine = {BasicStrategy.chart(), engine(HIT_BELOW_17), engine(AGGRESSIVE), engine(SPLIT_THEN_STAND)};
        sim.Round.Player[] simulator = {chart, simulator(HIT_BELOW_17), simulator(AGGRESSIVE), simulator(SPLIT_THEN_STAND)};
        ExactRound exact = new ExactRound(StateRun.montreal());
        Count c = new Count();
        for (int[] shoe : SHOES) {
            assertEquals(9, shoe.length);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int a = 0; a < 9; a++) {
                for (int b = 0; b < 9; b++) {
                    for (int u = 0; u < 9; u++) {
                        if (a == b || a == u || b == u) {
                            continue;
                        }
                        int p1 = shoe[a];
                        int p2 = shoe[b];
                        int up = shoe[u];
                        if (!seen.add(p1 + "," + up + "," + p2)) {
                            continue;
                        }
                        c.deals++;
                        int[] rest = new int[6];
                        int[] left = new int[11];
                        for (int i = 0, j = 0; i < 9; i++) {
                            if (i != a && i != b && i != u) {
                                rest[j++] = shoe[i];
                                left[shoe[i]]++;
                            }
                        }
                        for (int first : firstMoves(p1, p2)) {
                            for (int k = 0; k < engine.length; k++) {
                                String what = Arrays.toString(shoe) + ": deal " + p1 + "," + p2 + " against " + up
                                        + ", first " + PlayerMove.values()[first] + ", " + names[k];
                                compare(exact, left, p1, p2, up, first, engine[k], simulator[k], rest, c, what);
                                c.naturals += first == 0 && firstMoves(p1, p2).size() == 1 ? 1 : 0;
                                c.doubles += first == 2 ? 1 : 0;
                                c.softHands += (p1 == 1 || p2 == 1) && p1 != p2 ? 1 : 0;
                            }
                        }
                    }
                }
            }
        }
        System.out.printf("D3: %d deals on %d shoes; %d comparisons equal within %s (largest difference %.3g), %d too short "
                        + "for both, %d all dealer naturals for both; %d rounds played, %d dealer naturals left out, %d "
                        + "with a split%n", c.deals, SHOES.length, c.compared, TOLERANCE, c.worst, c.bothShort,
                c.bothAllNatural, c.rounds, c.excluded, c.splits);
        assertTrue(c.compared > 2000, c.compared + " comparisons");
        assertTrue(c.bothShort > 0, "some round runs out of cards on both sides");
        assertTrue(c.excluded > 0 && c.splits > 0 && c.naturals > 0 && c.doubles > 0 && c.softHands > 0,
                "dealer naturals, splits, player naturals, doubles and soft hands are all met");
    }

    static void compare(ExactRound exact, int[] left, int p1, int p2, int up, int first, RoundPolicy engine,
                        sim.Round.Player simulator, int[] rest, Count c, String what) {
        double value;
        Class<? extends RuntimeException> threw = null;
        try {
            value = exact.valueOfFirstMove(left.clone(), p1, p2, up, PlayerMove.values()[first], engine);
        } catch (IllegalStateException e) {
            threw = IllegalStateException.class;
            value = Double.NaN;
        } catch (IllegalArgumentException e) {
            threw = IllegalArgumentException.class;
            value = Double.NaN;
        }
        double[] sim = simulated(p1, up, p2, rest, first, simulator, c);
        if (threw == IllegalStateException.class) {
            assertNull(sim, what + ": ExactRound ran out of cards, the simulator did not on any order");
            c.bothShort++;
            return;
        }
        assertNotNull(sim, what + ": the simulator ran out of cards on some order, ExactRound did not");
        if (threw == IllegalArgumentException.class) {
            assertTrue(Double.isNaN(sim[0]), what + ": ExactRound refused the deal (every hole card a natural), the "
                    + "simulator averaged " + sim[0]);
            c.bothAllNatural++;
            return;
        }
        double d = Math.abs(sim[0] - value);
        assertTrue(d <= TOLERANCE, what + ": simulator " + sim[0] + ", ExactRound " + value);
        c.worst = Math.max(c.worst, d);
        c.compared++;
    }
}
