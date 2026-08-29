import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a natural blackjack is worth, and how the soft-21 cell is protected from it.
 *
 * Two scoring paths exist and they answer different questions.
 *
 * The payoff path reports real money, so a natural pays 3:2 there.
 *
 * The table path is asked which move was better, and a hand that ended before the player
 * chose anything cannot answer that. A dealer natural and a player natural both resolve
 * without a decision, so both are VOID: the hand does not count, and is meant to be
 * dropped rather than scored.
 *
 * That leaves a question VOID does not answer. The soft-21 cell has to hold some value,
 * because hitting a soft 14 can land on soft 21 and the lookup has to return something --
 * and if that value carried a natural's 3:2, hitting soft 14 would look better than it is.
 * What prevents that is not the scoring: setCards deals a soft-21 target as three cards,
 * so the cell fills with ordinary three-card soft 21s and never with naturals. These tests
 * pin that down, because it is the load-bearing part.
 */
public class NaturalScoringTest {

    private static HashMap<PlayerDealerBestScore, Outcome> outcomeFinder(HouseRules hr) {
        return PlayerDealerBestScore.initializeOutcomeFinderForTable(hr);
    }

    private static MetaDealerResult dealerMade(int total) {
        MetaDealerResult mdr = new MetaDealerResult();
        switch (total) {
            case 17: mdr.num17 = 1; break;
            case 18: mdr.num18 = 1; break;
            case 19: mdr.num19 = 1; break;
            case 20: mdr.num20 = 1; break;
            case 21: mdr.num21 = 1; break;
            default: mdr.numBust = 1; break;
        }
        return mdr;
    }

    private static ArrayList<Card> hand(Rank... ranks) {
        ArrayList<Card> h = new ArrayList<>();
        Suit[] suits = Suit.values();
        for (int i = 0; i < ranks.length; i++) {
            h.add(new Card(ranks[i], suits[i % suits.length]));
        }
        return h;
    }

    // ----------------------------------------------------------------- payoff path

    /** The payoff path reports real money, so a natural pays 3:2 there. */
    @Test
    public void payoffPathPaysANaturalThreeToTwo() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        Outcome outcome = PlayerDealerBestScore.playerOutcomeVsDealerForPayoff(
                hr, 21, 20, true, false);
        assertEquals(Outcome.WINBLACKJACK, outcome);
        assertEquals(1.5, Outcome.outcomePayoff(outcome, hr.blackjackPayout), 1e-9);
    }

    /** Both sides holding a natural is a push. */
    @Test
    public void payoffPathPushesWhenBothHaveNaturals() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        Outcome outcome = PlayerDealerBestScore.playerOutcomeVsDealerForPayoff(
                hr, 21, 21, true, true);
        assertEquals(0.0, Outcome.outcomePayoff(outcome, hr.blackjackPayout), 1e-9,
                "natural against natural is a push");
    }

    /** Losing to a dealer natural is a real loss in money terms. */
    @Test
    public void payoffPathScoresLosingToADealerNaturalAsALoss() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        Outcome outcome = PlayerDealerBestScore.playerOutcomeVsDealerForPayoff(
                hr, 20, 21, false, true);
        assertEquals(-1.0, Outcome.outcomePayoff(outcome, hr.blackjackPayout), 1e-9);
    }

    // ------------------------------------------------------------------ table path

    /** A dealer natural ended the hand before the player acted, so it does not count. */
    @Test
    public void tableTreatsADealerNaturalAsVoid() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        assertTrue(hr.dealerPeeksBlackjack, "these rules have the dealer peek");
        assertEquals(Outcome.VOID, PlayerDealerBestScore.playerOutcomeVsDealerForTable(
                hr, 20, 21, false, true));
    }

    /** A player natural ended the hand before the player acted too. */
    @Test
    public void tableTreatsAPlayerNaturalAsVoid() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        assertEquals(Outcome.VOID, PlayerDealerBestScore.playerOutcomeVsDealerForTable(
                hr, 21, 20, true, false));
    }

    /**
     * A void hand has no payoff at all.
     *
     * outcomePayoff used to map VOID to -1.0, which is a fabricated loss. Returning 0.0
     * instead would be gentler but no more real: averaging a void hand in as zero still
     * pulls a cell's mean toward zero. There is no right number, so asking for one is the
     * error. Nothing reaches this in practice -- both VOID cases are unreachable in the
     * table run -- which is exactly why a wrong answer here would have gone unnoticed.
     */
    @Test
    public void aVoidHandCannotBeScored() {
        assertThrows(UnsolvedCellException.class,
                () -> Outcome.outcomePayoff(Outcome.VOID, 1.5));
    }

    /** Every outcome that does have a payoff is worth between losing the bet and 3:2. */
    @Test
    public void everyScorableOutcomeIsWithinTheBounds() {
        for (Outcome outcome : Outcome.values()) {
            if (outcome == Outcome.VOID) {
                continue;
            }
            double payoff = Outcome.outcomePayoff(outcome, 1.5);
            assertTrue(payoff >= -1.0 && payoff <= 1.5, outcome + " scored " + payoff);
        }
    }

    // -------------------------------------------- what actually protects soft 21

    /**
     * A three-card soft 21 is not a natural.
     *
     * This is the property the soft-21 cell depends on. setCards deals a soft-21 target
     * as three cards precisely so the cell fills with these rather than with naturals,
     * which is what stops a hit soft 14 from inheriting a 3:2 expectation.
     */
    @Test
    public void aThreeCardSoftTwentyOneIsNotANatural() {
        RandomishPlayer player = new RandomishPlayer();
        player.playerHands.playerHand =
                new PlayerHand(hand(Rank.ACE, Rank.FIVE, Rank.FIVE));

        HandEncoding he = new HandEncoding(player.playerHands.playerHand.handCards);
        assertTrue(he.isSoft21(), "A+5+5 encodes as soft 21");
        assertEquals(21, he.getBestScore());
        assertFalse(player.playerHasBlackjack(),
                "three cards is not a natural, however the hand encodes");
    }

    /** Two cards making 21 is a natural, which is why the cell is fed three. */
    @Test
    public void aTwoCardSoftTwentyOneIsANatural() {
        RandomishPlayer player = new RandomishPlayer();
        player.playerHands.playerHand = new PlayerHand(hand(Rank.ACE, Rank.KING));
        assertTrue(player.playerHasBlackjack());
    }

    /**
     * And so the soft-21 cell is worth a plain win, not 3:2.
     *
     * Scored the way a three-card soft 21 actually is: an ordinary 21 against a dealer 20.
     */
    @Test
    public void theSoftTwentyOneCellIsWorthAPlainWin() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        double payoff = PlayerDealerBestScore.getPlayerPayoff(
                outcomeFinder(hr), dealerMade(20), 21, hr.blackjackPayout, false, false);
        assertEquals(1.0, payoff, 1e-9,
                "a non-natural 21 beating a dealer 20 pays even money");
    }

    /** An ordinary 21 against a dealer 21 is a push. */
    @Test
    public void ordinaryTwentyOneAgainstTwentyOneIsAPush() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        double payoff = PlayerDealerBestScore.getPlayerPayoff(
                outcomeFinder(hr), dealerMade(21), 21, hr.blackjackPayout, false, false);
        assertEquals(0.0, payoff, 1e-9);
    }

    /** 20 beats 17 every time. A sanity anchor for the weighting arithmetic. */
    @Test
    public void weightedPayoffForTwentyAgainstSeventeenIsOne() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        double payoff = PlayerDealerBestScore.getPlayerPayoff(
                outcomeFinder(hr), dealerMade(17), 20, hr.blackjackPayout, false, false);
        assertEquals(1.0, payoff, 1e-9);
    }
}
