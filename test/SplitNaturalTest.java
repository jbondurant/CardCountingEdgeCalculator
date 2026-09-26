import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a split pair that makes ace and ten is worth, under both versions of the rule.
 *
 * Most casinos say a hand that comes out of a split cannot be a blackjack: a split ace
 * that draws a ten, or a split ten that draws an ace, is an ordinary 21. It pays even
 * money and pushes a dealer who draws to 21. A few say it is a blackjack, and then it is
 * paid at the table's blackjack odds and beats a dealer's drawn 21. HouseRules records
 * which in blackjackOnSplitPairs.
 *
 * Both closed-form solvers load the Montreal preset for the split limits, and both used
 * to score the hand as an ordinary 21 whatever the flag said. Their dealer has already
 * peeked, so under the favourable rule the hand beats everything the dealer can still
 * make and is worth the payout outright. That makes the effect of the rule exact: every
 * hand that draws the card completing ace and ten stops being worth standing on 21 and
 * starts being worth the payout, and nothing else moves.
 */
public class SplitNaturalTest {

    private static final double TEN = 4.0 / 13.0;
    private static final double ACE = 1.0 / 13.0;

    private static HouseRules rules(boolean splitNaturalIsBlackjack, double payout) {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        hr.blackjackOnSplitPairs = splitNaturalIsBlackjack;
        hr.blackjackPayout = payout;
        return hr;
    }

    /** The same rules with no resplitting, so each split makes exactly two hands. */
    private static HouseRules rulesWithoutResplits(boolean splitNaturalIsBlackjack, double payout) {
        HouseRules hr = rules(splitNaturalIsBlackjack, payout);
        hr.numSplitsNotAces = 1;
        return hr;
    }

    private static String upCardName(int up) {
        return up == 11 ? "an ace" : "a " + up;
    }

    private static String pairName(int rank) {
        return rank == 1 ? "A,A" : rank + "," + rank;
    }

    /** Hand total after the second card, for a split ace drawing a card: A,A is soft 12. */
    private static int aceTotalWith(int drawn) {
        return drawn == 1 ? 12 : 11 + drawn;
    }

    // ------------------------------------------------------------- the rule applied

    /**
     * Split aces take one card each and stand, and the preset allows no resplit of aces,
     * so a split makes two hands. Each draws a ten with probability 4/13, and under the
     * rule that hand is worth the payout instead of what standing on 21 is worth. So the
     * rule adds exactly 2 x 4/13 x (payout - stand on 21), at 3:2 and at 6:5 alike, and to
     * random and optimal continuation alike, because a split ace has no decision left.
     */
    @Test
    public void splittingAcesGainsWhatATenOnEitherAceIsWorthAtTheTablesPayout() {
        for (double payout : new double[]{1.5, 1.2}) {
            HouseRules off = rules(false, payout);
            HouseRules on = rules(true, payout);
            assertEquals(1, on.numSplitsAces, "the closed form assumes aces are split once");

            RandomVsOptimalReport solverOff = new RandomVsOptimalReport(19, off);
            RandomVsOptimalReport solverOn = new RandomVsOptimalReport(19, on);
            PerRoundVersusPerHandReport perRoundOff = new PerRoundVersusPerHandReport(off);
            PerRoundVersusPerHandReport perRoundOn = new PerRoundVersusPerHandReport(on);

            for (int up = 2; up <= 11; up++) {
                double gain = 2 * TEN * (payout - solverOff.valueOfStandingAgainst(21, up));
                String where = "splitting aces against " + upCardName(up) + " at a payout of "
                        + payout;
                for (boolean optimal : new boolean[]{true, false}) {
                    assertEquals(gain,
                            solverOn.valueOfSplittingAgainst(1, up, optimal)
                                    - solverOff.valueOfSplittingAgainst(1, up, optimal),
                            1e-12, where + (optimal ? ", played well," : ", played at random,")
                                    + " did not gain what a ten on either ace is worth under the"
                                    + " rule");
                }
                assertEquals(gain,
                        perRoundOn.valueOfSplittingAgainst(1, up)
                                - perRoundOff.valueOfSplittingAgainst(1, up),
                        1e-12, where + " did not gain, per round, what a ten on either ace is"
                                + " worth under the rule");
            }
        }
    }

    /**
     * The flag names split pairs, not split aces, so a split ten that draws an ace counts
     * too. With resplitting turned off a split makes two hands, each drawing an ace with
     * probability 1/13, so played well the rule adds 2 x 1/13 x (payout - stand on 21).
     *
     * A natural leaves no decision, so the random player is paid the same as the optimal
     * one on it. Under the common rule the random player instead flips a coin between
     * standing on the soft 21 and doubling it, and a doubled soft 21 draws to a hard 12
     * to 21. So its gain is the payout less the average of those two.
     */
    @Test
    public void splittingTensGainsWhatAnAceOnEitherTenIsWorthAtTheTablesPayout() {
        for (double payout : new double[]{1.5, 1.2}) {
            HouseRules off = rulesWithoutResplits(false, payout);
            HouseRules on = rulesWithoutResplits(true, payout);

            RandomVsOptimalReport solverOff = new RandomVsOptimalReport(19, off);
            RandomVsOptimalReport solverOn = new RandomVsOptimalReport(19, on);
            PerRoundVersusPerHandReport perRoundOff = new PerRoundVersusPerHandReport(off);
            PerRoundVersusPerHandReport perRoundOn = new PerRoundVersusPerHandReport(on);

            for (int up = 2; up <= 11; up++) {
                double standOn21 = solverOff.valueOfStandingAgainst(21, up);
                double doubleSoft21 = 0.0;
                for (int c = 1; c <= 10; c++) {
                    doubleSoft21 += (c == 10 ? TEN : ACE)
                            * 2 * solverOff.valueOfStandingAgainst(11 + c, up);
                }
                double optimalGain = 2 * ACE * (payout - standOn21);
                double randomGain = 2 * ACE * (payout - (standOn21 + doubleSoft21) / 2);
                String where = "splitting tens against " + upCardName(up) + " at a payout of "
                        + payout;

                assertEquals(optimalGain,
                        solverOn.valueOfSplittingAgainst(10, up, true)
                                - solverOff.valueOfSplittingAgainst(10, up, true),
                        1e-12, where + ", played well, did not gain what an ace on either ten"
                                + " is worth under the rule");
                assertEquals(randomGain,
                        solverOn.valueOfSplittingAgainst(10, up, false)
                                - solverOff.valueOfSplittingAgainst(10, up, false),
                        1e-12, where + ", played at random, did not gain what an ace on"
                                + " either ten is worth under the rule");
                assertEquals(optimalGain,
                        perRoundOn.valueOfSplittingAgainst(10, up)
                                - perRoundOff.valueOfSplittingAgainst(10, up),
                        1e-12, where + " did not gain, per round, what an ace on either ten"
                                + " is worth under the rule");
            }
        }
    }

    /**
     * No other pair can make ace and ten in two cards, so no other split moves, however
     * often its hands reach 21 with a third card. Resplitting is left out to keep this
     * quick; every hand a resplit makes is valued by the same one-hand code.
     */
    @Test
    public void theRuleLeavesEveryOtherPairAlone() {
        HouseRules off = rulesWithoutResplits(false, 1.5);
        HouseRules on = rulesWithoutResplits(true, 1.5);
        RandomVsOptimalReport solverOff = new RandomVsOptimalReport(19, off);
        RandomVsOptimalReport solverOn = new RandomVsOptimalReport(19, on);
        PerRoundVersusPerHandReport perRoundOff = new PerRoundVersusPerHandReport(off);
        PerRoundVersusPerHandReport perRoundOn = new PerRoundVersusPerHandReport(on);
        for (int rank = 2; rank <= 9; rank++) {
            for (int up = 2; up <= 11; up++) {
                String where = "splitting " + pairName(rank) + " against " + upCardName(up);
                for (boolean optimal : new boolean[]{true, false}) {
                    assertEquals(solverOff.valueOfSplittingAgainst(rank, up, optimal),
                            solverOn.valueOfSplittingAgainst(rank, up, optimal), 0.0,
                            where + " changed with the split-natural rule");
                }
                assertEquals(perRoundOff.valueOfSplittingAgainst(rank, up),
                        perRoundOn.valueOfSplittingAgainst(rank, up), 0.0,
                        where + " changed per round with the split-natural rule");
            }
        }
    }

    // ---------------------------------------------------------- the rule not applied

    /**
     * Under the common rule a split ace that draws a ten stands on an ordinary 21, like
     * any other split ace stands on what it drew, so the split is worth two hands of
     * standing on whatever arrives.
     */
    @Test
    public void withoutTheRuleASplitAceAndTenIsAnOrdinaryTwentyOne() {
        HouseRules off = rules(false, 1.5);
        RandomVsOptimalReport solver = new RandomVsOptimalReport(19, off);
        PerRoundVersusPerHandReport perRound = new PerRoundVersusPerHandReport(off);
        for (int up = 2; up <= 11; up++) {
            double expected = 0.0;
            for (int c = 1; c <= 10; c++) {
                expected += (c == 10 ? TEN : ACE)
                        * 2 * solver.valueOfStandingAgainst(aceTotalWith(c), up);
            }
            String where = "without the rule, splitting aces against " + upCardName(up);
            assertEquals(expected, solver.valueOfSplittingAgainst(1, up, true), 1e-12,
                    where + " is not worth two hands standing on what they drew");
            assertEquals(expected, solver.valueOfSplittingAgainst(1, up, false), 1e-12,
                    where + ", played at random, is not worth two hands standing on what"
                            + " they drew");
            assertEquals(expected, perRound.valueOfSplittingAgainst(1, up), 1e-12,
                    where + " is not worth, per round, two hands standing on what they drew");
        }
    }

    /**
     * The dealer has already peeked and no starting hand is a natural, so without the
     * rule nothing either solver values can be paid at blackjack odds. Only splitting
     * aces or tens can reach ace and ten, so it is enough to see that neither moves with
     * the payout.
     */
    @Test
    public void withoutTheRuleThePayoutNeverEnters() {
        HouseRules threeToTwo = rules(false, 1.5);
        HouseRules sixToFive = rules(false, 1.2);
        PerRoundVersusPerHandReport perRoundA = new PerRoundVersusPerHandReport(threeToTwo);
        PerRoundVersusPerHandReport perRoundB = new PerRoundVersusPerHandReport(sixToFive);
        for (int limit : new int[]{19, 21}) {
            RandomVsOptimalReport a = new RandomVsOptimalReport(limit, threeToTwo);
            RandomVsOptimalReport b = new RandomVsOptimalReport(limit, sixToFive);
            for (int rank : new int[]{1, 10}) {
                for (int up = 2; up <= 11; up++) {
                    String where = "without the rule, splitting " + pairName(rank)
                            + " against " + upCardName(up);
                    for (boolean optimal : new boolean[]{true, false}) {
                        assertEquals(a.valueOfSplittingAgainst(rank, up, optimal),
                                b.valueOfSplittingAgainst(rank, up, optimal), 0.0,
                                where + (optimal ? ", played well," : ", played at random,")
                                        + " at a limit of " + limit
                                        + ", changed with the blackjack payout");
                    }
                }
            }
        }
        for (int rank : new int[]{1, 10}) {
            for (int up = 2; up <= 11; up++) {
                assertEquals(perRoundA.valueOfSplittingAgainst(rank, up),
                        perRoundB.valueOfSplittingAgainst(rank, up), 0.0,
                        "without the rule, splitting " + pairName(rank) + " against "
                                + upCardName(up) + " changed per round with the blackjack"
                                + " payout");
            }
        }
    }

    // --------------------------------------------------------------- what it decides

    /**
     * Published basic strategy for these rules, with double after split: split 2s, 3s and
     * 7s against 2 to 7, 4s against 5 and 6, 6s against 2 to 6, 9s against 2 to 6, 8 and
     * 9, eights and aces always, fives and tens never.
     */
    private static boolean basicStrategySplits(int rank, int up) {
        switch (rank) {
            case 1: case 8: return true;
            case 2: case 3: case 7: return up <= 7;
            case 4: return up == 5 || up == 6;
            case 6: return up <= 6;
            case 9: return up <= 6 || up == 8 || up == 9;
            default: return false;
        }
    }

    /**
     * The rule makes splitting tens worth more, but not enough: a pair of tens is a 20,
     * and splitting it still costs more than an ace on either ten brings back. Every
     * per-round split decision matches basic strategy under the rule and without it.
     */
    @Test
    public void everyPerRoundSplitDecisionIsBasicStrategyWithOrWithoutTheRule() {
        for (boolean ruleOn : new boolean[]{false, true}) {
            PerRoundVersusPerHandReport perRound = new PerRoundVersusPerHandReport(rules(ruleOn, 1.5));
            for (int rank = 1; rank <= 10; rank++) {
                for (int up = 2; up <= 11; up++) {
                    boolean splits = perRound.valueOfSplittingAgainst(rank, up)
                            > perRound.valueOfNotSplittingAgainst(rank, up);
                    assertEquals(basicStrategySplits(rank, up), splits,
                            (ruleOn ? "with" : "without") + " the rule, the per-round measure "
                                    + (splits ? "splits " : "does not split ") + pairName(rank)
                                    + " against " + upCardName(up)
                                    + ", which basic strategy does not");
                }
            }
        }
    }

    // --------------------------------------------------------------- what it prints

    private static String ruleLine(boolean perRound, HouseRules hr) {
        return perRound
                ? new PerRoundVersusPerHandReport(hr).splitNaturalLine()
                : new RandomVsOptimalReport(19, hr).splitNaturalLine();
    }

    /**
     * Each report's rule line says what a split ace and ten is and, when it is a
     * blackjack, at what odds. Without the rule it quotes no payout, because none enters.
     */
    @Test
    public void theRuleLineSaysWhereThePayoutEnters() {
        for (boolean perRound : new boolean[]{false, true}) {
            String name = perRound ? "the per-round report" : "the random-versus-optimal report";
            String atThreeToTwo = ruleLine(perRound, rules(true, 1.5));
            String atSixToFive = ruleLine(perRound, rules(true, 1.2));
            String withoutTheRule = ruleLine(perRound, rules(false, 1.5));
            assertTrue(atThreeToTwo.contains("3:2"),
                    name + " pays split naturals 3:2 and its rule line does not say so: "
                            + atThreeToTwo);
            assertTrue(atSixToFive.contains("6:5") && !atSixToFive.contains("3:2"),
                    name + " pays split naturals 6:5 and its rule line does not say so: "
                            + atSixToFive);
            assertFalse(withoutTheRule.contains("3:2") || withoutTheRule.contains("blackjack paid"),
                    name + " quotes a payout it does not use without the rule: "
                            + withoutTheRule);
        }
    }
}
