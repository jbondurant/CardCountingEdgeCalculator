import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Why the payoff of a decision cannot be measured by playing on at random.
 *
 * The simulator scores a first move by playing the rest of the hand out. If the rest of
 * the hand is played at random, what comes back is not a noisy estimate of the move's
 * value; it is a precise estimate of a different quantity, namely the value of that move
 * followed by bad play. The two differ by a lot for some moves and hardly at all for
 * others, and that is what makes it dangerous: the error is not noise, it is a bias, and
 * it falls unevenly across the moves being compared.
 *
 * Moves that end the hand -- standing, doubling -- are scored correctly by random
 * continuation, because there is no continuation left to get wrong. Moves that hand you
 * another decision -- hitting a low total, splitting a pair -- are scored as though you
 * were going to squander them. So the method is biased against exactly the moves whose
 * value depends on playing well afterwards, and no amount of extra sampling fixes it.
 *
 * This computes both quantities exactly, for every starting hand against every up-card:
 *
 *   optimal continuation: the value of each first move assuming best play afterwards
 *   random continuation:  the value of each first move assuming a coin flip among the
 *                         legal moves at every later decision
 *
 * and reports where the two disagree about which move is best, and what that costs.
 *
 * Model: infinite deck, dealer hits soft 17 and peeks for blackjack, blackjack pays 3:2,
 * double after split allowed, no surrender, resplitting to the ruleset's limit of four
 * hands (two for aces). These are the Montreal casino rules the rest of the project uses.
 * Everything here is a closed-form expectation, so there is no sampling error to report.
 *
 * Run: java RandomVsOptimalReport [maxTotalTheRandomPlayerWillHit]
 */
public class RandomVsOptimalReport {

    /** Ranks 1..10, where 10 stands for every ten-valued card. */
    private static double drawProbability(int rank) {
        return rank == 10 ? 4.0 / 13.0 : 1.0 / 13.0;
    }

    private static final boolean DEALER_HITS_SOFT_17 = true;
    private static final boolean DOUBLE_AFTER_SPLIT = true;
    private static final double BLACKJACK_PAYOUT = 1.5;

    /**
     * How high a total the random player is willing to hit.
     *
     * Defaults to 19, meaning the random player is spared the obviously absurd move of
     * hitting a 20. That is deliberately generous: it makes the gap that survives an
     * honest one rather than an artifact of a strawman.
     */
    private int hitLimit;

    /** Split limits come from the ruleset; HouseRules counts splits, so 3 means four hands. */
    private final HouseRules houseRules = HouseRules.getMtlCasino25MinBlackjackParams(75);

    private final double[][] dealerDistribution = new double[12][];
    private final double[][][] playMemo = new double[2][23][2];
    private final boolean[][][] playMemoSet = new boolean[2][23][2];
    private double[] currentDealer;

    public static void main(String[] args) {
        int limit = args.length > 0 ? Integer.parseInt(args[0]) : 19;
        new RandomVsOptimalReport(limit).run();
    }

    public RandomVsOptimalReport(int hitLimit) {
        this.hitLimit = hitLimit;
        for (int up = 2; up <= 11; up++) {
            dealerDistribution[up] = dealerDistributionForUpCard(up == 11 ? 1 : up);
        }
    }

    // ------------------------------------------------------------------- the dealer

    /** Probabilities of the dealer finishing on 17, 18, 19, 20, 21, or busting. */
    private double[] dealerDistributionForUpCard(int upCard) {
        double[] out = new double[6];
        int startTotal = upCard == 1 ? 11 : upCard;
        boolean startSoft = upCard == 1;
        double norm = 0.0;

        for (int hole = 1; hole <= 10; hole++) {
            // The dealer peeks, so hands where the hole card makes a natural are settled
            // before the player acts and are excluded here.
            boolean natural = (upCard == 1 && hole == 10) || (upCard == 10 && hole == 1);
            if (natural) {
                continue;
            }
            double p = drawProbability(hole);
            norm += p;
            Hand h = addCard(startTotal, startSoft, hole);
            double[] sub = dealerOutcomesFrom(h.total, h.soft, new double[24][2][], new boolean[24][2]);
            for (int i = 0; i < 6; i++) {
                out[i] += p * sub[i];
            }
        }
        for (int i = 0; i < 6; i++) {
            out[i] /= norm;
        }
        return out;
    }

    private double[] dealerOutcomesFrom(int total, boolean soft, double[][][] memo, boolean[][] memoSet) {
        if (total > 21) {
            double[] bust = new double[6];
            bust[5] = 1.0;
            return bust;
        }
        int s = soft ? 1 : 0;
        if (memoSet[total][s]) {
            return memo[total][s];
        }
        boolean mustHit = total < 17 || (total == 17 && soft && DEALER_HITS_SOFT_17);
        double[] out = new double[6];
        if (!mustHit) {
            out[total - 17] = 1.0;
        } else {
            for (int c = 1; c <= 10; c++) {
                double p = drawProbability(c);
                Hand h = addCard(total, soft, c);
                double[] sub = dealerOutcomesFrom(h.total, h.soft, memo, memoSet);
                for (int i = 0; i < 6; i++) {
                    out[i] += p * sub[i];
                }
            }
        }
        memo[total][s] = out;
        memoSet[total][s] = true;
        return out;
    }

    // -------------------------------------------------------------------- the player

    static final class Hand {
        final int total;
        final boolean soft;
        Hand(int total, boolean soft) {
            this.total = total;
            this.soft = soft;
        }
    }

    /**
     * Add a card, counting an ace as 11 whenever the hand can still afford it.
     *
     * Working in hard points and promoting once at the end, rather than adding 11 and
     * demoting, is what keeps a second ace honest. A hand never holds two aces at 11, so
     * "soft" means exactly one of them is, and adding an ace to a soft hand leaves it
     * soft: A,A is soft 12, not hard 12, and A,6,A is soft 18. Demoting used to clear the
     * flag outright, which turned every multi-ace hand hard. That mattered most against a
     * dealer ace, where multi-ace hands are commonest, and it let the dealer stand on a
     * soft 17 it was required to hit.
     */
    static Hand addCard(int total, boolean soft, int rank) {
        int hardTotal = (soft ? total - 10 : total) + (rank == 1 ? 1 : rank);
        boolean holdsAce = soft || rank == 1;
        if (holdsAce && hardTotal + 10 <= 21) {
            return new Hand(hardTotal + 10, true);
        }
        return new Hand(hardTotal, false);
    }

    /** Value of standing on this total against the current up-card. */
    private double standValue(int total) {
        double ev = currentDealer[5];
        for (int d = 17; d <= 21; d++) {
            double p = currentDealer[d - 17];
            if (total > d) {
                ev += p;
            } else if (total < d) {
                ev -= p;
            }
        }
        return ev;
    }

    /**
     * Value of a hand already in progress, where every later decision is made either
     * optimally or by a coin flip among the legal moves.
     */
    private double playOn(int total, boolean soft, boolean optimal) {
        int mode = optimal ? 1 : 0;
        int s = soft ? 1 : 0;
        if (playMemoSet[mode][total][s]) {
            return playMemo[mode][total][s];
        }
        double stand = standValue(total);
        double value;
        if (total > hitLimit) {
            value = stand;
        } else {
            double hit = 0.0;
            for (int c = 1; c <= 10; c++) {
                double p = drawProbability(c);
                Hand h = addCard(total, soft, c);
                hit += p * (h.total > 21 ? -1.0 : playOn(h.total, h.soft, optimal));
            }
            value = optimal ? Math.max(stand, hit) : (stand + hit) / 2.0;
        }
        playMemoSet[mode][total][s] = true;
        playMemo[mode][total][s] = value;
        return value;
    }

    // ------------------------------------------------------------- the first move

    private double valueOfHitting(int total, boolean soft, boolean optimal) {
        double ev = 0.0;
        for (int c = 1; c <= 10; c++) {
            double p = drawProbability(c);
            Hand h = addCard(total, soft, c);
            ev += p * (h.total > 21 ? -1.0 : playOn(h.total, h.soft, optimal));
        }
        return ev;
    }

    /** Doubling ends the hand, so both kinds of continuation agree on its value. */
    private double valueOfDoubling(int total, boolean soft) {
        double ev = 0.0;
        for (int c = 1; c <= 10; c++) {
            double p = drawProbability(c);
            Hand h = addCard(total, soft, c);
            ev += p * (h.total > 21 ? -2.0 : 2.0 * standValue(h.total));
        }
        return ev;
    }

    /** Value of a fresh two-card hand, first decision included. */
    private double valueOfFreshHand(int total, boolean soft, boolean optimal, boolean mayDouble) {
        List<Double> options = new ArrayList<>();
        options.add(standValue(total));
        if (total <= hitLimit) {
            options.add(valueOfHitting(total, soft, optimal));
        }
        if (mayDouble) {
            options.add(valueOfDoubling(total, soft));
        }
        if (optimal) {
            double best = options.get(0);
            for (double v : options) {
                best = Math.max(best, v);
            }
            return best;
        }
        double sum = 0.0;
        for (double v : options) {
            sum += v;
        }
        return sum / options.size();
    }

    /** One split hand, played out without splitting again. */
    private double valueOfOneSplitHand(int pairRank, int drawn, boolean optimal) {
        if (pairRank == 1) {
            // Split aces take exactly one card and then stand. There is no later decision,
            // so random and optimal continuation agree here.
            Hand h = addCard(11, true, drawn);
            return standValue(h.total);
        }
        Hand h = addCard(pairRank, false, drawn);
        return valueOfFreshHand(h.total, h.soft, optimal, DOUBLE_AFTER_SPLIT);
    }

    /** How many moves other than splitting this hand has, matching valueOfFreshHand. */
    private int countMovesBesidesSplitting(int pairRank, int drawn) {
        if (pairRank == 1) {
            return 1;                       // split aces take one card and stand
        }
        Hand h = addCard(pairRank, false, drawn);
        int options = 1;                    // Stand
        if (h.total <= hitLimit) {
            options++;                      // Hit
        }
        if (DOUBLE_AFTER_SPLIT) {
            options++;                      // Double
        }
        return options;
    }

    /**
     * The whole split, resplits included, in units of the original bet.
     *
     * The hand limit is shared: if the left hand splits again it spends budget the right
     * hand can no longer have, so the branches are not independent. The model carries the
     * two numbers the dealer effectively does -- positions still awaiting a second card,
     * and hands committed so far -- and resolves them in order against one budget.
     *
     * The two continuations differ in what happens when a matching card arrives and
     * resplitting is legal. Optimal play takes the better of splitting again and playing
     * the pair out. Random play treats splitting as one more move to flip a coin over,
     * which is the whole point of the comparison: a move whose value depends on choosing
     * well afterwards is priced as though you were not going to.
     */
    private double resolveSplit(int pending, int hands, int pairRank, int maxHands, boolean optimal) {
        if (pending == 0) {
            return 0.0;
        }
        double ev = 0.0;
        for (int c = 1; c <= 10; c++) {
            double rest = resolveSplit(pending - 1, hands, pairRank, maxHands, optimal);
            double playedOut = valueOfOneSplitHand(pairRank, c, optimal) + rest;

            double branch = playedOut;
            if (c == pairRank && hands < maxHands) {
                double resplit = resolveSplit(pending + 1, hands + 1, pairRank, maxHands, optimal);
                if (optimal) {
                    branch = Math.max(playedOut, resplit);
                } else {
                    int others = countMovesBesidesSplitting(pairRank, c);
                    branch = (others * playedOut + resplit) / (others + 1);
                }
            }
            ev += drawProbability(c) * branch;
        }
        return ev;
    }

    private double valueOfSplitting(int pairRank, boolean optimal) {
        int maxHands = 1 + (pairRank == 1 ? houseRules.numSplitsAces : houseRules.numSplitsNotAces);
        return resolveSplit(2, 2, pairRank, maxHands, optimal);
    }

    // ------------------------------------------------------------------- reporting

    private static final class Row {
        String hand;
        int upCard;
        String bestByOptimal;
        String bestByRandom;
        double valueOfRightMove;
        double valueOfChosenMove;
        double cost;
        double undervaluation;
        String chosenMoveNote;
    }

    private void resetMemo() {
        for (int m = 0; m < 2; m++) {
            for (int t = 0; t < 23; t++) {
                playMemoSet[m][t][0] = false;
                playMemoSet[m][t][1] = false;
            }
        }
    }

    private Row evaluate(String label, int total, boolean soft, Integer pairRank, int upCard) {
        currentDealer = dealerDistribution[upCard];
        resetMemo();

        List<String> names = new ArrayList<>();
        List<Double> optimalValues = new ArrayList<>();
        List<Double> randomValues = new ArrayList<>();

        names.add("Stand");
        optimalValues.add(standValue(total));
        randomValues.add(standValue(total));

        if (total <= hitLimit) {
            names.add("Hit");
            optimalValues.add(valueOfHitting(total, soft, true));
            randomValues.add(valueOfHitting(total, soft, false));
        }

        names.add("Double");
        optimalValues.add(valueOfDoubling(total, soft));
        randomValues.add(valueOfDoubling(total, soft));

        if (pairRank != null) {
            names.add("Split");
            optimalValues.add(valueOfSplitting(pairRank, true));
            randomValues.add(valueOfSplitting(pairRank, false));
        }

        int bestOptimal = argMax(optimalValues);
        int bestRandom = argMax(randomValues);

        Row row = new Row();
        row.hand = label;
        row.upCard = upCard;
        row.bestByOptimal = names.get(bestOptimal);
        row.bestByRandom = names.get(bestRandom);
        row.valueOfRightMove = optimalValues.get(bestOptimal);
        row.valueOfChosenMove = optimalValues.get(bestRandom);
        row.cost = row.valueOfRightMove - row.valueOfChosenMove;
        // How far random continuation undersells the move that is actually best.
        row.undervaluation = optimalValues.get(bestOptimal) - randomValues.get(bestOptimal);
        row.chosenMoveNote = names.get(bestOptimal);
        return row;
    }

    private static int argMax(List<Double> values) {
        int best = 0;
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) > values.get(best)) {
                best = i;
            }
        }
        return best;
    }

    private static String upCardName(int up) {
        return up == 11 ? "A" : String.valueOf(up);
    }

    public void run() {
        List<Row> all = new ArrayList<>();

        for (int up = 2; up <= 11; up++) {
            // Hard totals reachable with two non-paired cards.
            for (int total = 5; total <= 20; total++) {
                all.add(evaluate("hard " + total, total, false, null, up));
            }
            // Soft totals: ace plus 2 through 9.
            for (int kicker = 2; kicker <= 9; kicker++) {
                all.add(evaluate("soft " + (11 + kicker), 11 + kicker, true, null, up));
            }
            // Pairs.
            for (int rank = 1; rank <= 10; rank++) {
                int total = rank == 1 ? 12 : rank * 2;
                boolean soft = rank == 1;
                String name = rank == 1 ? "A,A" : rank + "," + rank;
                all.add(evaluate(name, total, soft, rank, up));
            }
        }

        System.out.println("Infinite deck, H17, dealer peeks, 3:2, DAS, no surrender, resplit to 4.");
        System.out.println("Random player hits any total up to " + hitLimit + ".");
        System.out.println("All figures are exact expectations, in units of the original bet.");
        System.out.println();

        List<Row> flips = new ArrayList<>();
        for (Row r : all) {
            if (!r.bestByOptimal.equals(r.bestByRandom)) {
                flips.add(r);
            }
        }
        flips.sort(Comparator.comparingDouble((Row r) -> -r.cost));

        System.out.println("Hands where playing on at random picks a different move ("
                + flips.size() + " of " + all.size() + "):");
        System.out.println();
        System.out.printf("%-8s %4s   %-6s %-6s %9s %9s %9s%n",
                "hand", "vs", "right", "picked", "right EV", "picked EV", "cost");
        for (Row r : flips) {
            System.out.printf("%-8s %4s   %-6s %-6s %+9.4f %+9.4f %9.4f%n",
                    r.hand, upCardName(r.upCard), r.bestByOptimal, r.bestByRandom,
                    r.valueOfRightMove, r.valueOfChosenMove, r.cost);
        }

        System.out.println();
        System.out.println("How badly random continuation underprices the move that is actually best:");
        System.out.println();
        List<Row> byUndervaluation = new ArrayList<>(all);
        byUndervaluation.sort(Comparator.comparingDouble((Row r) -> -r.undervaluation));
        System.out.printf("%-8s %4s   %-6s %11s%n", "hand", "vs", "move", "underpriced");
        for (int i = 0; i < 8; i++) {
            Row r = byUndervaluation.get(i);
            System.out.printf("%-8s %4s   %-6s %11.4f%n",
                    r.hand, upCardName(r.upCard), r.chosenMoveNote, r.undervaluation);
        }

        System.out.println();
        System.out.println("And where it makes almost no difference:");
        System.out.println();
        System.out.printf("%-8s %4s   %-6s %11s%n", "hand", "vs", "move", "underpriced");
        for (int i = byUndervaluation.size() - 1; i >= byUndervaluation.size() - 6; i--) {
            Row r = byUndervaluation.get(i);
            System.out.printf("%-8s %4s   %-6s %11.4f%n",
                    r.hand, upCardName(r.upCard), r.chosenMoveNote, r.undervaluation);
        }
    }
}
