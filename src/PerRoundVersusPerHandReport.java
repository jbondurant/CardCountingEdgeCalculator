/**
 * Why a hand's value is measured per round and not per hand.
 *
 * You post one bet per round. Splitting is a decision to put a second bet at risk, so the
 * round's result is the sum of what both hands do -- which is what the simulator records,
 * as leftPayoff + rightPayoff, in units of that one original bet.
 *
 * The tempting alternative is to score each hand separately and compare averages. That
 * measure is broken in a specific way: its denominator is one of the things the player
 * chooses. Splitting a losing hand halves the loss on paper without improving anything,
 * because the second hand had to be funded; splitting a winning hand halves the gain the
 * same way. So it does not err in one direction, it errs in whichever direction flatters
 * the split.
 *
 * This prints every pair against every up-card where the two measures disagree about
 * whether to split, and what following the per-hand answer would cost per round.
 *
 * Model: infinite deck, dealer hits soft 17 and peeks, blackjack pays 3:2, double after
 * split allowed, resplitting to the limit in HouseRules -- four hands for a pair, two for
 * aces. The per-hand figure divides the round by the expected number of hands, which is
 * itself a moving target, and that is part of the point.
 *
 * Run: java PerRoundVersusPerHandReport
 */
public class PerRoundVersusPerHandReport {

    private static double drawProbability(int rank) {
        return rank == 10 ? 4.0 / 13.0 : 1.0 / 13.0;
    }

    private static final boolean DEALER_HITS_SOFT_17 = true;
    private static final boolean DOUBLE_AFTER_SPLIT = true;

    private double[] dealerOutcomes;
    private double[][] playMemo;
    private double[][][] dealerMemo;

    // ----------------------------------------------------------------- card handling

    /** Total and softness after adding a card, demoting the ace if the hand would bust. */
    private static int[] addCard(int total, int soft, int rank) {
        int newTotal = total + (rank == 1 ? 11 : rank);
        int newSoft = (soft == 1 || rank == 1) ? 1 : 0;
        while (newTotal > 21 && newSoft == 1) {
            newTotal -= 10;
            newSoft = 0;
        }
        return new int[]{newTotal, newSoft};
    }

    // ---------------------------------------------------------------------- dealer

    private double[] dealerFrom(int total, int soft) {
        if (total > 21) {
            double[] bust = new double[6];
            bust[5] = 1.0;
            return bust;
        }
        if (dealerMemo[total][soft] != null) {
            return dealerMemo[total][soft];
        }
        double[] out = new double[6];
        boolean mustHit = total < 17 || (total == 17 && soft == 1 && DEALER_HITS_SOFT_17);
        if (!mustHit) {
            out[total - 17] = 1.0;
        } else {
            for (int c = 1; c <= 10; c++) {
                int[] next = addCard(total, soft, c);
                double[] sub = dealerFrom(next[0], next[1]);
                for (int i = 0; i < 6; i++) {
                    out[i] += drawProbability(c) * sub[i];
                }
            }
        }
        dealerMemo[total][soft] = out;
        return out;
    }

    /** Point the report at one up-card, excluding the hands where the dealer has a natural. */
    private void faceUpCard(int upCard) {
        dealerMemo = new double[24][2][];
        double[] out = new double[6];
        int startTotal = upCard == 1 ? 11 : upCard;
        int startSoft = upCard == 1 ? 1 : 0;
        double norm = 0.0;
        for (int hole = 1; hole <= 10; hole++) {
            if ((upCard == 1 && hole == 10) || (upCard == 10 && hole == 1)) {
                continue;
            }
            norm += drawProbability(hole);
            int[] next = addCard(startTotal, startSoft, hole);
            double[] sub = dealerFrom(next[0], next[1]);
            for (int i = 0; i < 6; i++) {
                out[i] += drawProbability(hole) * sub[i];
            }
        }
        for (int i = 0; i < 6; i++) {
            out[i] /= norm;
        }
        dealerOutcomes = out;
        playMemo = new double[24][2];
        for (double[] row : playMemo) {
            row[0] = Double.NEGATIVE_INFINITY;
            row[1] = Double.NEGATIVE_INFINITY;
        }
    }

    // ---------------------------------------------------------------------- player

    private double standValue(int total) {
        double ev = dealerOutcomes[5];
        for (int d = 17; d <= 21; d++) {
            if (total > d) {
                ev += dealerOutcomes[d - 17];
            } else if (total < d) {
                ev -= dealerOutcomes[d - 17];
            }
        }
        return ev;
    }

    private double playOn(int total, int soft) {
        if (playMemo[total][soft] != Double.NEGATIVE_INFINITY) {
            return playMemo[total][soft];
        }
        double stand = standValue(total);
        double value = stand;
        if (total < 21) {
            double hit = 0.0;
            for (int c = 1; c <= 10; c++) {
                int[] next = addCard(total, soft, c);
                hit += drawProbability(c) * (next[0] > 21 ? -1.0 : playOn(next[0], next[1]));
            }
            value = Math.max(stand, hit);
        }
        playMemo[total][soft] = value;
        return value;
    }

    private double hitValue(int total, int soft) {
        double ev = 0.0;
        for (int c = 1; c <= 10; c++) {
            int[] next = addCard(total, soft, c);
            ev += drawProbability(c) * (next[0] > 21 ? -1.0 : playOn(next[0], next[1]));
        }
        return ev;
    }

    private double doubleValue(int total, int soft) {
        double ev = 0.0;
        for (int c = 1; c <= 10; c++) {
            int[] next = addCard(total, soft, c);
            ev += drawProbability(c) * (next[0] > 21 ? -2.0 : 2.0 * standValue(next[0]));
        }
        return ev;
    }

    private double bestWithoutSplitting(int total, int soft, boolean mayDouble) {
        double best = Math.max(standValue(total), hitValue(total, soft));
        return mayDouble ? Math.max(best, doubleValue(total, soft)) : best;
    }

    /** One split hand: the pair rank plus its card, played out without splitting again. */
    private double splitHandValue(int pairRank, int drawn) {
        if (pairRank == 1) {
            // Split aces take exactly one card and then stand.
            int[] hand = addCard(11, 1, drawn);
            return standValue(hand[0]);
        }
        int[] hand = addCard(pairRank, 0, drawn);
        return bestWithoutSplitting(hand[0], hand[1], DOUBLE_AFTER_SPLIT);
    }

    /**
     * What the round is worth if the pair is split, resplits included.
     *
     * The awkward part of resplitting is that the hand limit is shared. If the left hand
     * splits again it uses up budget the right hand can no longer have, so the two are not
     * independent and cannot be valued separately. The dealer resolves them in order
     * against one running count of hands, so the model does the same: carry the number of
     * positions still waiting for a second card, and the number of hands committed so far.
     *
     *   pending  positions still awaiting their second card
     *   hands    hands committed, played and pending together
     *
     * Drawing a card that does not match retires a position into a played hand. Drawing a
     * match offers the choice: play it as a pair, or split, which turns one position into
     * two and spends a hand from the budget. Taking the better of those two is what makes
     * the model decline to resplit where it would not help.
     */
    private double resolveSplit(int pending, int hands, int pairRank, int maxHands) {
        if (pending == 0) {
            return 0.0;
        }
        double ev = 0.0;
        for (int c = 1; c <= 10; c++) {
            double asPlayedHand =
                    splitHandValue(pairRank, c) + resolveSplit(pending - 1, hands, pairRank, maxHands);
            double branch = asPlayedHand;
            boolean matches = (c == pairRank);
            if (matches && hands < maxHands) {
                branch = Math.max(asPlayedHand,
                        resolveSplit(pending + 1, hands + 1, pairRank, maxHands));
            }
            ev += drawProbability(c) * branch;
        }
        return ev;
    }

    /** Expected number of hands the round ends up with, for the per-hand denominator. */
    private double resolveHandCount(int pending, int hands, int pairRank, int maxHands) {
        if (pending == 0) {
            return hands;
        }
        double expected = 0.0;
        for (int c = 1; c <= 10; c++) {
            double asPlayedHand =
                    splitHandValue(pairRank, c) + resolveSplit(pending - 1, hands, pairRank, maxHands);
            double count = resolveHandCount(pending - 1, hands, pairRank, maxHands);
            if (c == pairRank && hands < maxHands) {
                double resplit = resolveSplit(pending + 1, hands + 1, pairRank, maxHands);
                if (resplit > asPlayedHand) {
                    count = resolveHandCount(pending + 1, hands + 1, pairRank, maxHands);
                }
            }
            expected += drawProbability(c) * count;
        }
        return expected;
    }

    private double splitRoundValue(int pairRank, int maxHands) {
        return resolveSplit(2, 2, pairRank, maxHands);
    }

    private double expectedHandCount(int pairRank, int maxHands) {
        return resolveHandCount(2, 2, pairRank, maxHands);
    }

    /** The hand limit: HouseRules counts splits, so a limit of 3 means four hands. */
    private static int maxHandsFor(int pairRank, HouseRules hr) {
        return 1 + (pairRank == 1 ? hr.numSplitsAces : hr.numSplitsNotAces);
    }

    // --------------------------------------------------------------------- report

    private static String pairName(int rank) {
        return rank == 1 ? "A,A" : rank + "," + rank;
    }

    private static String upCardName(int upCard) {
        return upCard == 11 ? "A" : String.valueOf(upCard);
    }

    public static void main(String[] args) {
        new PerRoundVersusPerHandReport().run();
    }

    public void run() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        System.out.println("Every pair where scoring per hand and scoring per round disagree.");
        System.out.println("Infinite deck, H17, dealer peeks, DAS, resplitting to "
                + (1 + hr.numSplitsNotAces) + " hands (" + (1 + hr.numSplitsAces)
                + " for aces).");
        System.out.println("Units of the original bet. Exact expectations, not sampled.");
        System.out.println();
        System.out.printf("%-6s %-3s %10s %10s %6s %10s   %-9s %-9s %9s%n",
                "pair", "vs", "no split", "split", "hands", "per hand", "per round", "per hand", "cost");

        double worstOverSplit = 0.0;
        String worstOverSplitAt = "";
        double worstMissedSplit = 0.0;
        String worstMissedSplitAt = "";
        int disagreements = 0;

        for (int rank = 1; rank <= 10; rank++) {
            for (int up = 2; up <= 11; up++) {
                faceUpCard(up == 11 ? 1 : up);
                int total = rank == 1 ? 12 : rank * 2;
                int soft = rank == 1 ? 1 : 0;

                int maxHands = maxHandsFor(rank, hr);
                double noSplit = bestWithoutSplitting(total, soft, true);
                double splitRound = splitRoundValue(rank, maxHands);
                double handCount = expectedHandCount(rank, maxHands);
                double splitPerHand = splitRound / handCount;

                boolean splitByRound = splitRound > noSplit;
                boolean splitByHand = splitPerHand > noSplit;
                if (splitByRound == splitByHand) {
                    continue;
                }
                disagreements++;

                // What following the per-hand answer costs, measured properly.
                double cost = Math.abs(Math.max(splitRound, noSplit)
                        - (splitByHand ? splitRound : noSplit));
                String where = pairName(rank) + " vs " + upCardName(up);
                if (splitByHand && cost > worstOverSplit) {
                    worstOverSplit = cost;
                    worstOverSplitAt = where;
                }
                if (!splitByHand && cost > worstMissedSplit) {
                    worstMissedSplit = cost;
                    worstMissedSplitAt = where;
                }

                System.out.printf("%-6s %-3s %+10.4f %+10.4f %6.2f %+10.4f   %-9s %-9s %9.4f%n",
                        pairName(rank), upCardName(up), noSplit, splitRound, handCount, splitPerHand,
                        splitByRound ? "split" : "no split",
                        splitByHand ? "split" : "no split",
                        cost);
            }
        }

        System.out.println();
        System.out.println(disagreements + " of 100 pair and up-card spots disagree.");
        System.out.printf("Worst split that should not happen : %s, costing %.4f a round%n",
                worstOverSplitAt, worstOverSplit);
        System.out.printf("Worst split that should happen     : %s, costing %.4f a round%n",
                worstMissedSplitAt, worstMissedSplit);
        System.out.println();
        System.out.println("The per-hand measure is not cautious or reckless, it is just wrong.");
        System.out.println("It splits a loss across more hands and splits a gain the same way,");
        System.out.println("so it argues for splitting when the hand is bad and against it when");
        System.out.println("the hand is good.");
    }
}
