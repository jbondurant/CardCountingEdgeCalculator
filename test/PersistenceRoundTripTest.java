import com.mongodb.BasicDBObject;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Everything that is written to the database has to read back as what was written.
 *
 * A BasicDBObject is a Map, so this needs no database: serialise, deserialise, compare.
 * These are the only paths in the project where a bug is silent and permanent, because a
 * table that does not load is a run that has to start over.
 */
public class PersistenceRoundTripTest {

    @Test
    public void anActionPayoffSurvivesTheRoundTrip() {
        ActionPayoff ap = new ActionPayoff();
        ap.insertEventSmart(1.5);
        ap.insertEventSmart(-1.0);
        ap.insertEventSmart(0.0);

        ActionPayoff back = ActionPayoff.getActionPayoffFromObject(ap.getDBObject());

        assertEquals(ap.numTimes, back.numTimes, "numTimes");
        assertEquals(ap.avPayoff, back.avPayoff, 1e-12, "avPayoff");
        assertEquals(ap.avPayoffPrecise, back.avPayoffPrecise, "avPayoffPrecise");
        assertEquals(ap.numPlayerBlackjacks, back.numPlayerBlackjacks, "numPlayerBlackjacks");
    }

    @Test
    public void aMoveChoicesSurvivesTheRoundTrip() {
        MoveChoices mc = new MoveChoices();
        for (PlayerMove pm : EnumSet.of(PlayerMove.Stand, PlayerMove.Hit, PlayerMove.Double)) {
            ActionPayoff ap = new ActionPayoff();
            ap.insertEvent(-0.1);
            mc.actionPayoffs.put(pm, ap);
        }

        MoveChoices back = MoveChoices.getMoveCountFromObject(mc.getDBObject());

        assertEquals(mc.actionPayoffs.keySet(), back.actionPayoffs.keySet(), "moves");
        for (PlayerMove pm : mc.actionPayoffs.keySet()) {
            assertEquals(mc.actionPayoffs.get(pm).avPayoff,
                    back.actionPayoffs.get(pm).avPayoff, 1e-12, pm.name());
        }
    }

    @Test
    public void aDecisionCellSurvivesTheRoundTrip() {
        DecisionCell dc = new DecisionCell();
        HandEncoding hard16 = new HandEncoding(false, false, 16);
        for (double count : new double[]{-2.0, 0.0, 3.0}) {
            dc.insertEvent(new EventResult(
                    -0.3, hard16, Rank.TEN, PlayerMove.Stand, new GranularCount(count)));
        }

        DecisionCell back = DecisionCell.getDecisionCellFromObject(dc.getDBObject());

        assertEquals(dc.countToMoveChoice.keySet(), back.countToMoveChoice.keySet(),
                "the count buckets must come back as the same keys");
    }

    @Test
    public void aCountMethodSurvivesTheRoundTrip() {
        CountMethod cm = CountMethod.getHiLoValue(1);
        CountMethod back = CountMethod.getCountMethodFromObject(cm.getDBObject());

        assertEquals(cm.deckEstimationPrecision, back.deckEstimationPrecision, "deck precision");
        assertEquals(cm.rankToCount, back.rankToCount, "the Hi-Lo tags");
    }

    @Test
    public void houseRulesSurviveTheRoundTrip() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        HouseRules back = HouseRules.getHouseRulesFromObject(hr.getDBOject());

        assertEquals(hr.numDecks, back.numDecks, "numDecks");
        assertEquals(hr.penetrationPercentage, back.penetrationPercentage, "penetration");
        assertEquals(hr.hitsOnSoft17, back.hitsOnSoft17, "hitsOnSoft17");
        assertEquals(hr.blackjackPayout, back.blackjackPayout, 1e-12, "blackjackPayout");
        assertEquals(hr.numSplitsAces, back.numSplitsAces, "numSplitsAces");
        assertEquals(hr.numSplitsNotAces, back.numSplitsNotAces, "numSplitsNotAces");
        assertEquals(hr.ranksThatCanBeDoubledDownAfterSplit,
                back.ranksThatCanBeDoubledDownAfterSplit, "DAS ranks");
        assertEquals(hr.possibleSideBets, back.possibleSideBets, "side bets");
        assertEquals(hr.canHitAfterSplittingAces, back.canHitAfterSplittingAces, "hit after split aces");
    }

    @Test
    public void simulationParametersSurviveTheRoundTrip() {
        SimulationParameters sp = new SimulationParameters(
                HouseRules.getMtlCasino25MinBlackjackParams(75),
                CountMethod.getHiLoValue(1), 1.0, 50000, 100000, -5, 5);

        SimulationParameters back = SimulationParameters.getSimParamFromObject(sp.getDBObject());

        assertEquals(sp.countGranularity, back.countGranularity, 1e-12, "granularity");
        assertEquals(sp.minHitsPerDecisionCellCount, back.minHitsPerDecisionCellCount, "min hits");
        assertEquals(sp.minMetaDealer, back.minMetaDealer, "min meta dealer");
        assertEquals(sp.minCountish, back.minCountish, "minCountish");
        assertEquals(sp.maxCountish, back.maxCountish, "maxCountish");
    }

    @Test
    public void aCountPayoffSurvivesTheRoundTrip() {
        CountPayoff cp = new CountPayoff(new GranularCount(-3.0));
        cp.actionPayoff.insertEventSmart(1.5);
        cp.actionPayoff.insertEventSmart(-1.0);

        CountPayoff back = CountPayoff.getFromObject(cp.getDBObject());

        assertEquals(cp.granularCount, back.granularCount, "the count");
        assertEquals(cp.actionPayoff.numTimes, back.actionPayoff.numTimes, "numTimes");
        assertEquals(cp.actionPayoff.avPayoff, back.actionPayoff.avPayoff, 1e-12, "avPayoff");
    }

    @Test
    public void aMetaDealerResultSurvivesTheRoundTrip() {
        MetaDealerResult mdr = new MetaDealerResult();
        mdr.insertEvent(17, false);
        mdr.insertEvent(21, false);
        mdr.insertEvent(25, false);

        MetaDealerResult back = MetaDealerResult.getMetaDealerResultFromString(mdr.getString());

        assertEquals(mdr.num17, back.num17, "num17");
        assertEquals(mdr.num21, back.num21, "num21");
        assertEquals(mdr.numBust, back.numBust, "numBust");
    }

    @Test
    public void aDealerBucketKeySurvivesTheRoundTrip() {
        for (double count : new double[]{-5.0, -0.5, 0.0, 2.5, 5.0}) {
            GranularCountAndDealerUpCard key =
                    new GranularCountAndDealerUpCard(new GranularCount(count), 10);
            GranularCountAndDealerUpCard back =
                    GranularCountAndDealerUpCard.getFromString(key.getString());
            assertEquals(key, back, "count " + count + " did not survive");
        }
    }

    @Test
    public void aHandSituationSurvivesTheRoundTrip() {
        for (HandEncoding he : HandEncoding.getOrderedEncodings()) {
            for (int up = 2; up <= 11; up++) {
                HandSituation hs = new HandSituation(he, up);
                assertEquals(hs, HandSituation.getEncodingFromString(hs.getStringFromEncoding()),
                        "situation did not survive: " + hs.getStringFromEncoding());
            }
        }
    }
}
