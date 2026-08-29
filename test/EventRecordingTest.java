import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every observation is recorded, including the first one for a situation.
 *
 * insertEvent used to create the cell for an unseen situation and return without
 * inserting, so each of the roughly three hundred and forty (hand, up-card) pairs threw
 * away its first result. Small against fifty thousand events a cell, but it is the kind of
 * thing that is invisible unless something checks.
 */
public class EventRecordingTest {

    /** The first event recorded for a situation must not be thrown away. */
    @Test
    public void theFirstEventForASituationIsRecorded() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        CountMethod cm = CountMethod.getHiLoValue(1);
        SimulationParameters sp = new SimulationParameters(hr, cm, 1.0, 10, 10, -5, 5);
        SimulationTable table = new SimulationTable(sp, "test");

        HandEncoding hard16 = new HandEncoding(false, false, 16);
        EventResult er = new EventResult(
                -0.5, hard16, Rank.TEN, PlayerMove.Hit, new GranularCount(0.0));

        table.insertEvent(er);

        HandSituation hs = new HandSituation(hard16, Rank.TEN.getRankpoints());
        DecisionCell dc = table.actionMap.get(hs);
        assertNotNull(dc, "the situation must be in the table");
        MoveChoices mc = dc.countToMoveChoice.get(new GranularCount(0.0));
        assertNotNull(mc, "the count bucket must exist after one event");
        assertEquals(1, mc.actionPayoffs.get(PlayerMove.Hit).numTimes,
                "the first observation for a cell must be counted");
    }

    /** Every observation counts, not every observation after the first. */
    @Test
    public void everyEventForASituationIsCounted() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        CountMethod cm = CountMethod.getHiLoValue(1);
        SimulationParameters sp = new SimulationParameters(hr, cm, 1.0, 10, 10, -5, 5);
        SimulationTable table = new SimulationTable(sp, "test");

        HandEncoding hard16 = new HandEncoding(false, false, 16);
        for (int i = 0; i < 5; i++) {
            table.insertEvent(new EventResult(
                    -1.0, hard16, Rank.TEN, PlayerMove.Hit, new GranularCount(0.0)));
        }

        HandSituation hs = new HandSituation(hard16, Rank.TEN.getRankpoints());
        MoveChoices mc = table.actionMap.get(hs).countToMoveChoice.get(new GranularCount(0.0));
        assertEquals(5, mc.actionPayoffs.get(PlayerMove.Hit).numTimes,
                "five events must be counted as five");
        assertEquals(-1.0, mc.actionPayoffs.get(PlayerMove.Hit).avPayoff, 1e-9);
    }
}
