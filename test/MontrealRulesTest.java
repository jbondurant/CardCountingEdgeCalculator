import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Montreal preset against what Casino de Montreal says about split hands.
 *
 * Its blackjack page, casinos.lotoquebec.com/en/montreal/games/blackjack, states three
 * rules for them: a pair may be split up to a maximum of four hands, aces may be split
 * only once and take one card each, and there is no blackjack on split pairs. The preset
 * had the first two right and the third wrong. That cost nothing while no code read
 * blackjackOnSplitPairs, and would cost a bet and a half on every split ace and ten
 * against a dealer 21 as soon as something did.
 *
 * These pin the preset to the page, so that changing it means disagreeing with the
 * casino on purpose.
 */
public class MontrealRulesTest {

    private final HouseRules montreal = HouseRules.getMtlCasino25MinBlackjackParams(75);

    /** HouseRules counts splits, not hands, so three splits is four hands. */
    @Test
    public void aPairIsSplitToAtMostFourHands() {
        assertEquals(3, montreal.numSplitsNotAces,
                "Montreal lets a pair be split up to a maximum of four hands");
    }

    @Test
    public void acesAreSplitOnceAndTakeOneCardEach() {
        assertEquals(1, montreal.numSplitsAces, "Montreal lets aces be split only once");
        assertFalse(montreal.canHitAfterSplittingAces,
                "Montreal gives each split ace one more card and no more");
        assertEquals(1, montreal.maxNumCardsAfterSplittingAces,
                "Montreal gives each split ace one more card and no more");
    }

    @Test
    public void aSplitAceAndTenIsNotABlackjack() {
        assertFalse(montreal.blackjackOnSplitPairs,
                "Montreal says there is no blackjack on split pairs");
    }
}
