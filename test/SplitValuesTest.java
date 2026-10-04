import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Split values under a post-split play (SplitPlay, SplitValues): exactly ExactRound's, kept by
 * the play's answers to the decisions a split can reach, and the stored value wherever those
 * answers are the chart's. PlayerTreeOrderTest holds them to every card order under a
 * count-group play.
 */
public class SplitValuesTest {

    private static final RoundRules RULES = StateRun.montreal();
    private static final RoundPolicy CHART = BasicStrategy.chart();

    /**
     * A deal's first moves ranked as the chart plays them: what it plays with every move
     * offered, then with Split taken away, then Stand. Under Montreal's rules a split play that
     * reads this ranking and the chart's later play plays every split hand as the chart does.
     */
    static PlayerMove[] chartRanking(int deal) {
        int p1 = Deals.P1[deal];
        int p2 = Deals.P2[deal];
        boolean ace = p1 == 1 || p2 == 1;
        int hard = p1 + p2;
        int total = ace && hard <= 11 ? hard + 10 : hard;
        boolean soft = ace && hard <= 11;
        int pair = p1 == p2 ? p1 : 0;
        return new PlayerMove[]{
                CHART.choose(new RoundPolicy.Decision(total, soft, 2, pair, Deals.UP[deal], false, true, total < 21,
                        true, pair != 0, false)),
                CHART.choose(new RoundPolicy.Decision(total, soft, 2, pair, Deals.UP[deal], false, true, total < 21,
                        true, false, false)),
                PlayerMove.Stand};
    }

    /** The chart as a split play's tables. */
    static SplitPlay chartPlay(int rank, int up) {
        return new SplitPlay(rank, up, (total, soft) -> PlayerTree.CHART.stands(total, soft, up, 0),
                SplitValuesTest::chartRanking);
    }

    /** The chart's split play with one later spot answered the other way. */
    static SplitPlay flipped(int rank, int up, int flipTotal, boolean flipSoft) {
        return new SplitPlay(rank, up, (total, soft) -> (total == flipTotal && soft == flipSoft)
                != PlayerTree.CHART.stands(total, soft, up, 0), SplitValuesTest::chartRanking);
    }

    /**
     * On stored states, the chart's split play gets every pair's stored V(Split) without valuing
     * anything, and that is what ExactRound gives under the play. The run's values were written
     * by the engine of the run, which differs from today's only in rounding (ed3ac67), so the
     * two agree within 1e-12, not to the last bit.
     */
    @Test
    public void theChartsPlayGetsTheStoredValue() throws Exception {
        int checked = 0;
        for (StateStore.Record r : StoredStates.spread(3)) {
            int[] shoe = r.ranksLeft();
            SplitValues values = new SplitValues(RULES, shoe, r.values);
            ExactRound engine = new ExactRound(RULES);
            for (int i = 0; i < Deals.COUNT; i++) {
                if (Deals.P1[i] != Deals.P2[i] || Deals.probability(shoe, i) == 0) {
                    continue;
                }
                int rank = Deals.P1[i];
                SplitPlay play = chartPlay(rank, Deals.UP[i]);
                assertArrayEquals(SplitValues.fingerprint(RULES, i, CHART), SplitValues.fingerprint(RULES, i, play),
                        "deal " + i);
                double v = values.value(i, play);
                assertEquals(r.values[StateValuer.slot(i, PlayerMove.Split)], v, 0.0);
                if (i % 3 == 0) {
                    assertEquals(engine.valueOfFirstMove(Deals.after(shoe, i), rank, rank, Deals.UP[i], PlayerMove.Split,
                            play), v, 1e-12, "deal " + i);
                }
                checked++;
            }
            assertEquals(0, values.valued);
        }
        assertTrue(checked > 250, checked + " splits");
    }

    /**
     * A play that differs where a split goes is valued by ExactRound under it: standing on a
     * hard 16 against a ten, which a split 8 reaches by hitting. A play that differs only where
     * no split goes gets the stored value: 10,10 against a 6 stands on every split hand, so its
     * later play is never asked, and split aces only stand, whatever their ranking. Plays that
     * answer alike are valued once.
     */
    @Test
    public void onlyWhatASplitReachesDecidesTheValue() throws Exception {
        StateStore.Record r = StoredStates.spread(5).get(2);
        int[] shoe = r.ranksLeft();
        SplitValues values = new SplitValues(RULES, shoe, r.values);
        ExactRound engine = new ExactRound(RULES);

        int eights = Deals.index(8, 8, 10);
        SplitPlay stand16 = flipped(8, 10, 16, false);
        assertFalse(java.util.Arrays.equals(SplitValues.fingerprint(RULES, eights, CHART),
                SplitValues.fingerprint(RULES, eights, stand16)));
        double v = values.value(eights, stand16);
        assertEquals(1, values.valued);
        assertEquals(engine.valueOfFirstMove(Deals.after(shoe, eights), 8, 8, 10, PlayerMove.Split, stand16), v, 0.0);
        assertNotEquals(r.values[StateValuer.slot(eights, PlayerMove.Split)], v);
        // The same answers from another object: kept, not valued again.
        SplitPlay again = flipped(8, 10, 16, false);
        assertEquals(v, values.value(eights, again), 0.0);
        assertEquals(1, values.valued);
        assertEquals(1, values.fromKept);

        int tens = Deals.index(10, 10, 6);
        SplitPlay hitsEverything = new SplitPlay(10, 6, (total, soft) -> false, SplitValuesTest::chartRanking);
        assertEquals(r.values[StateValuer.slot(tens, PlayerMove.Split)], values.value(tens, hitsEverything), 0.0);
        int aces = Deals.index(1, 1, 6);
        PlayerMove[] hitFirst = {PlayerMove.Hit, PlayerMove.Double, PlayerMove.Split, PlayerMove.Stand};
        SplitPlay acesHit = new SplitPlay(1, 6, (total, soft) -> false, i -> hitFirst);
        assertEquals(r.values[StateValuer.slot(aces, PlayerMove.Split)], values.value(aces, acesHit), 0.0);
        assertEquals(1, values.valued);
        assertEquals(2, values.fromStore);
    }

    /** The split hand's first decision plays the best legal move of its deal's ranking. */
    @Test
    public void aSplitHandPlaysTheBestLegalMoveOfItsDeal() {
        PlayerMove[] splitDoubleHit = {PlayerMove.Split, PlayerMove.Double, PlayerMove.Hit, PlayerMove.Stand};
        SplitPlay play = new SplitPlay(8, 10, (total, soft) -> total >= 17, i -> splitDoubleHit);
        // 8,8 at the hand limit: Split is not offered, Double is.
        assertEquals(PlayerMove.Double, play.choose(new RoundPolicy.Decision(16, false, 2, 8, 10, true, true, true, true,
                false, false)));
        assertEquals(PlayerMove.Split, play.choose(new RoundPolicy.Decision(16, false, 2, 8, 10, true, true, true, true,
                true, false)));
        // 8,3 without doubling after a split: Hit.
        assertEquals(PlayerMove.Hit, play.choose(new RoundPolicy.Decision(11, false, 2, 0, 10, true, true, true, false,
                false, false)));
        // A later decision reads the later table.
        assertEquals(PlayerMove.Hit, play.choose(new RoundPolicy.Decision(16, false, 3, 0, 10, true, false, true, false,
                false, false)));
        assertEquals(PlayerMove.Stand, play.choose(new RoundPolicy.Decision(17, false, 3, 0, 10, true, false, true,
                false, false, false)));
        // The deal it reads is the split hand's: 8,3 against a ten is deal (3, 8, 10).
        int[] asked = new int[1];
        SplitPlay reading = new SplitPlay(8, 10, (total, soft) -> false, i -> {
            asked[0] = i;
            return splitDoubleHit;
        });
        reading.choose(new RoundPolicy.Decision(11, false, 2, 0, 10, true, true, true, true, false, false));
        assertEquals(Deals.index(3, 8, 10), asked[0]);
        // A split ace that drew a ten holds 21 and stands; a ten that drew an ace too.
        SplitPlay aces = new SplitPlay(1, 6, (total, soft) -> false, i -> splitDoubleHit);
        assertEquals(PlayerMove.Stand, aces.choose(new RoundPolicy.Decision(21, true, 2, 0, 6, true, true, false, false,
                false, false)));
        SplitPlay tens = new SplitPlay(10, 6, (total, soft) -> false, i -> splitDoubleHit);
        assertEquals(PlayerMove.Stand, tens.choose(new RoundPolicy.Decision(21, true, 2, 0, 6, true, true, false, true,
                false, false)));
        // It plays split hands only.
        assertThrows(IllegalStateException.class, () -> play.choose(new RoundPolicy.Decision(16, false, 2, 8, 10, false,
                true, true, true, true, false)));
    }
}
