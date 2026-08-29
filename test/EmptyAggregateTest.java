import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The same rule as the cell lookups, applied to the things that average over them.
 *
 * A weighted payoff over no dealer outcomes, or a table average over no events, is not a
 * payoff of zero. It is the absence of one. Reporting 0.0 is worse here than in a cell,
 * because it reads as an edge of zero rather than as a run that recorded nothing, and that
 * is the number a reader takes away.
 */
public class EmptyAggregateTest {

    private static HashMap<PlayerDealerBestScore, Outcome> outcomeFinder(HouseRules hr) {
        return PlayerDealerBestScore.initializeOutcomeFinderForTable(hr);
    }

    /** The MetaDealer is filled to completion before the table phase, so a gap is a bug. */
    @Test
    public void aMissingDealerBucketIsAnError() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        assertThrows(UnsolvedCellException.class,
                () -> PlayerDealerBestScore.getPlayerPayoff(
                        outcomeFinder(hr), null, 20, hr.blackjackPayout, false, false));
    }

    /** A bucket that exists but recorded nothing has no expectation either. */
    @Test
    public void anEmptyDealerBucketIsAnError() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        assertThrows(UnsolvedCellException.class,
                () -> PlayerDealerBestScore.getPlayerPayoff(
                        outcomeFinder(hr), new MetaDealerResult(), 20,
                        hr.blackjackPayout, false, false));
    }

    /** A matchup the outcome table does not cover cannot be scored. */
    @Test
    public void anUnknownMatchupIsAnError() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        MetaDealerResult mdr = new MetaDealerResult();
        mdr.num17 = 1;
        assertThrows(UnsolvedCellException.class,
                () -> PlayerDealerBestScore.getPlayerPayoff(
                        outcomeFinder(hr), mdr, 99, hr.blackjackPayout, false, false));
    }

    /** A run that recorded nothing has no average to report. */
    @Test
    public void averagingNothingIsAnError() {
        assertThrows(UnsolvedCellException.class,
                () -> ActionPayoff.getAverage(new ArrayList<>()));
    }

    /** And the payoff table says so rather than reporting an edge of zero. */
    @Test
    public void averagePayoffOfAFreshTableIsAnError() {
        PayoffTable pt = new PayoffTable(-5, 5, 1.0, "test");
        assertThrows(UnsolvedCellException.class, pt::getAveragePayoff);
    }

    /** With real observations it reports normally. */
    @Test
    public void aBucketWithObservationsReportsNormally() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        MetaDealerResult mdr = new MetaDealerResult();
        mdr.num17 = 10;
        assertEquals(1.0, PlayerDealerBestScore.getPlayerPayoff(
                outcomeFinder(hr), mdr, 20, hr.blackjackPayout, false, false), 1e-9,
                "20 beats 17 every time");
    }
}
