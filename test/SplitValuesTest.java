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

    /** The chart's split play with these later spots, by total and softness, answered the other way. */
    static SplitPlay flipped(int rank, int up, int[][] spots, PlayerTree.LaterPlay base, int baseUp,
                             SplitPlay.FirstMoves first) {
        return new SplitPlay(rank, up, (total, soft) -> {
            boolean stands = base.stands(total, soft, baseUp, 0);
            for (int[] s : spots) {
                if (s[0] == total && (s[1] == 1) == soft) {
                    return !stands;
                }
            }
            return stands;
        }, first);
    }

    /** The chart's ranking, but for deal (r, r, up), which hits whether or not Split is offered. */
    static SplitPlay.FirstMoves noResplit(int r, int up) {
        int pair = Deals.index(r, r, up);
        PlayerMove[] hitStand = {PlayerMove.Hit, PlayerMove.Stand};
        return i -> i == pair ? hitStand : chartRanking(i);
    }

    /** What SplitValues gave, held to a fresh ExactRound under the same play. */
    private static void isExact(int[] shoe, int deal, SplitPlay play, double v) {
        int r = Deals.P1[deal];
        double exact = new ExactRound(RULES).valueOfFirstMove(Deals.after(shoe, deal), r, r, Deals.UP[deal],
                PlayerMove.Split, play);
        assertEquals(exact, v, 1e-12, "deal " + deal);
    }

    /**
     * The answer when a resplit is offered is part of the print. Under Montreal's rules a split
     * 8 that draws another may be split again up to four hands, and a count-based pair index
     * (splitting tens at a high count, say) differs from the chart at exactly that decision. So
     * the chart's play with 8,8 against a ten ranked Hit first, which hits where the chart
     * resplits and agrees everywhere else, is valued and not given the stored value; and two
     * plays away from the chart elsewhere that differ only there are each valued.
     */
    @Test
    public void theAnswerWhenAResplitIsOfferedIsPartOfThePrint() throws Exception {
        StateStore.Record r = StoredStates.spread(5).get(2);
        int[] shoe = r.ranksLeft();
        int eights = Deals.index(8, 8, 10);
        assertTrue(RULES.maxHandsFor(8) >= 3);

        SplitValues values = new SplitValues(RULES, shoe, r.values);
        SplitPlay hits = new SplitPlay(8, 10, (total, soft) -> PlayerTree.CHART.stands(total, soft, 10, 0),
                noResplit(8, 10));
        byte[] chartPrint = SplitValues.fingerprint(RULES, eights, CHART);
        byte[] print = SplitValues.fingerprint(RULES, eights, hits);
        assertFalse(java.util.Arrays.equals(chartPrint, print));
        int differ = 0;
        for (int k = 0; k < print.length; k++) {
            differ += print[k] != chartPrint[k] ? 1 : 0;
        }
        assertEquals(1, differ, "the two plays differ at one decision a split reaches");
        double v = values.value(eights, hits);
        assertEquals(1, values.valued);
        assertEquals(0, values.fromStore);
        isExact(shoe, eights, hits, v);
        assertNotEquals(r.values[StateValuer.slot(eights, PlayerMove.Split)], v, 1e-6);

        // Both away from the chart at hard 16, and apart only where a resplit is offered.
        SplitValues two = new SplitValues(RULES, shoe, r.values);
        int[][] at16 = {{16, 0}};
        SplitPlay resplits = flipped(8, 10, at16, PlayerTree.CHART, 10, SplitValuesTest::chartRanking);
        SplitPlay hitsInstead = flipped(8, 10, at16, PlayerTree.CHART, 10, noResplit(8, 10));
        double a = two.value(eights, resplits);
        double b = two.value(eights, hitsInstead);
        assertEquals(2, two.valued);
        assertEquals(0, two.fromKept);
        assertEquals(0, two.fromStore);
        isExact(shoe, eights, resplits, a);
        isExact(shoe, eights, hitsInstead, b);
        assertNotEquals(a, b, 1e-6);
    }

    /**
     * Every later decision a split hand's hits lead to is part of the print, however many hits
     * deep. A split 2 against a 5 reaches soft 20 only after two hits (2,3 then a 4 and an ace,
     * or 2,3 then an ace and a 4): the chart doubles 2,7, 2,8, 2,9 and 2,A there, so no
     * one-hit path gets to it. The chart's play hitting soft 20 is valued, not given the stored
     * value; and two plays away from the chart at hard 12 that differ only at soft 20 are each
     * valued.
     */
    @Test
    public void aDecisionTwoHitsDeepIsPartOfThePrint() throws Exception {
        StateStore.Record r = StoredStates.spread(5).get(2);
        int[] shoe = r.ranksLeft();
        int twos = Deals.index(2, 2, 5);

        SplitValues values = new SplitValues(RULES, shoe, r.values);
        SplitPlay hitSoft20 = flipped(2, 5, 20, true);
        assertTrue(PlayerTree.CHART.stands(20, true, 5, 0), "the chart stands on soft 20");
        assertFalse(java.util.Arrays.equals(SplitValues.fingerprint(RULES, twos, CHART),
                SplitValues.fingerprint(RULES, twos, hitSoft20)));
        double v = values.value(twos, hitSoft20);
        assertEquals(1, values.valued);
        assertEquals(0, values.fromStore);
        isExact(shoe, twos, hitSoft20, v);
        assertNotEquals(r.values[StateValuer.slot(twos, PlayerMove.Split)], v, 1e-6);

        SplitValues two = new SplitValues(RULES, shoe, r.values);
        assertTrue(PlayerTree.CHART.stands(12, false, 5, 0), "the chart stands on hard 12 against a 5");
        SplitPlay hit12 = flipped(2, 5, new int[][]{{12, 0}}, PlayerTree.CHART, 5, SplitValuesTest::chartRanking);
        SplitPlay hit12And20 = flipped(2, 5, new int[][]{{12, 0}, {20, 1}}, PlayerTree.CHART, 5,
                SplitValuesTest::chartRanking);
        double a = two.value(twos, hit12);
        double b = two.value(twos, hit12And20);
        assertEquals(2, two.valued);
        assertEquals(0, two.fromKept);
        assertEquals(0, two.fromStore);
        isExact(shoe, twos, hit12, a);
        isExact(shoe, twos, hit12And20, b);
        assertNotEquals(a, b, 1e-6);
    }

    /**
     * Values are kept by deal, up-card included, not by the pair's rank. In the fit one
     * SplitValues serves every pair deal of a state, and a group's plays for 8s against a 9 and
     * against a ten can answer every split decision alike. Two such plays, with equal prints and
     * neither the chart's, are each valued, and each is ExactRound's value for its own up-card.
     */
    @Test
    public void keptValuesAreKeptByDealNotByRank() throws Exception {
        StateStore.Record r = StoredStates.spread(5).get(2);
        int[] shoe = r.ranksLeft();
        int tens = Deals.index(8, 8, 10);
        int nines = Deals.index(8, 8, 9);
        int[][] at16 = {{16, 0}};
        SplitPlay v10 = flipped(8, 10, at16, PlayerTree.CHART, 10, SplitValuesTest::chartRanking);
        // Against a 9, the answers the play against a ten gives.
        SplitPlay v9 = flipped(8, 9, at16, PlayerTree.CHART, 10,
                i -> chartRanking(Deals.index(Deals.P1[i], Deals.P2[i], 10)));
        assertArrayEquals(SplitValues.fingerprint(RULES, tens, v10), SplitValues.fingerprint(RULES, nines, v9));
        assertFalse(java.util.Arrays.equals(SplitValues.fingerprint(RULES, nines, CHART),
                SplitValues.fingerprint(RULES, nines, v9)));

        SplitValues values = new SplitValues(RULES, shoe, r.values);
        double a = values.value(tens, v10);
        double b = values.value(nines, v9);
        assertEquals(2, values.valued);
        assertEquals(0, values.fromKept);
        assertEquals(0, values.fromStore);
        isExact(shoe, tens, v10, a);
        isExact(shoe, nines, v9, b);
        assertNotEquals(a, b, 1e-6);
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
