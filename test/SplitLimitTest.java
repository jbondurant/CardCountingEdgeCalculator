import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A split limit of 0 means the pair is played as it was dealt.
 *
 * HouseRules holds two limits, one for aces and one for every other pair, and each counts
 * how many times a hand may be split: the Montreal game allows three splits of most pairs
 * and one of aces. A limit of 0 is how a game that never lets you split, or never lets you
 * split aces, is written down.
 *
 * The table-building run offered Split on every pair regardless. The limits were only read
 * inside the split itself, where a hand already over its limit is played as though it had
 * not been split again -- right for a resplit, wrong for the pair as dealt. With a limit of
 * 0 the dealt pair was already over it, so choosing Split dealt nothing and returned the
 * value of the pair's best other move, and that value was recorded as the payoff of
 * Split. The largest of several noisy averages is biased upward, so Split's average
 * settled at or above the best real move and the cell read Split in a game that does not
 * allow it. On aces it was a different wrong number: the hand was given the split-ace
 * restrictions without ever having been split, so Split recorded standing on soft 12.
 */
public class SplitLimitTest {

    /** Enough observations that the run treats a cell as finished. */
    private static final int FINISHED = 1_000_000;

    /** How many hands each run deals. */
    private static final int EVENTS = 400;

    private static final GranularCount ZERO = new GranularCount(0.0);

    private static final HandSituation EIGHTS_VS_TWO =
            new HandSituation(new HandEncoding(false, true, 16), 2);
    private static final HandSituation ACES_VS_TWO =
            new HandSituation(new HandEncoding(true, true, 2), 2);

    /**
     * Parameters for a run that stays in one count bucket and never finishes a cell.
     *
     * A count range of [0, 0] clamps every true count to 0, so the dealing step lands on
     * its target count at once and every lookup reads the same bucket. The threshold is
     * one the target cell cannot reach in a test, so the run keeps dealing it.
     */
    private static SimulationParameters parameters(HouseRules hr) {
        return new SimulationParameters(hr, CountMethod.getHiLoValue(1), 1.0, FINISHED, 10, 0, 0);
    }

    private static MoveChoices standAndHit(int times, double stand, double hit) {
        MoveChoices mc = new MoveChoices();
        mc.actionPayoffs.put(PlayerMove.Stand, new ActionPayoff(times, stand, BigDecimal.valueOf(stand)));
        mc.actionPayoffs.put(PlayerMove.Hit, new ActionPayoff(times, hit, BigDecimal.valueOf(hit)));
        return mc;
    }

    /**
     * A table in which every situation ordered before the target is finished, so the run
     * deals the target and nothing else.
     *
     * The finished cells hold Stand and Hit, which is all that hitting, doubling or
     * splitting the target ever looks up. The target itself has each measured once, so
     * that the old code, which answered a Split here with the best of the other moves,
     * had one to answer with and recorded it rather than throwing.
     */
    private static SimulationTable tableWaitingOn(HouseRules hr, HandSituation target) {
        SimulationTable table = new SimulationTable(parameters(hr), "test");
        for (HandSituation hs : HandSituation.getOrderedSituations()) {
            if (hs.equals(target)) {
                break;
            }
            DecisionCell dc = new DecisionCell();
            dc.countToMoveChoice.put(ZERO, standAndHit(FINISHED, -0.2, -0.3));
            table.actionMap.put(hs, dc);
        }
        DecisionCell dc = new DecisionCell();
        dc.countToMoveChoice.put(ZERO, standAndHit(1, -0.2, -0.3));
        table.actionMap.put(target, dc);
        return table;
    }

    /** A dealer who finishes on each total, and busts, equally often. */
    private static MetaDealer evenDealer() {
        MetaDealer md = new MetaDealer("test");
        for (int upCard = 2; upCard <= 11; upCard++) {
            MetaDealerResult r = new MetaDealerResult();
            r.num17 = 100;
            r.num18 = 100;
            r.num19 = 100;
            r.num20 = 100;
            r.num21 = 100;
            r.numBust = 100;
            md.dealerCountAndUpCardToResults.put(new GranularCountAndDealerUpCard(ZERO, upCard), r);
        }
        return md;
    }

    /**
     * Deal the target pair repeatedly and record each hand, as runSimulation does, and
     * return the first moves that were played.
     *
     * Once two moves are measured, as they are here from the first hand, exploration plays
     * every legal first move at least one time in ten, so over this many hands the set
     * played is the set that was legal.
     */
    private static EnumSet<PlayerMove> firstMovesPlayed(SimulationTable table, HandSituation target, Rank pairRank) {
        HouseRules hr = table.simulationParameters.houseRules;
        Simulation sim = new Simulation(table, "test");
        HashMap<HandEncoding, ArrayList<DoubleRanks>> dealable = new HashMap<>();
        ArrayList<DoubleRanks> thePair = new ArrayList<>();
        thePair.add(new DoubleRanks(pairRank, pairRank));
        dealable.put(target.playerHE, thePair);
        HashMap<PlayerDealerBestScore, Outcome> outcomeFinder =
                PlayerDealerBestScore.initializeOutcomeFinderForTable(hr);
        MetaDealer dealer = evenDealer();

        EnumSet<PlayerMove> played = EnumSet.noneOf(PlayerMove.class);
        for (int i = 0; i < EVENTS; i++) {
            EventResult er = sim.runSingleEvent(dealable, new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), outcomeFinder, dealer, 0.5, 0.3);
            assertNotNull(er, "a dealer showing a two cannot have a natural");
            assertEquals(target.playerHE, er.playerHE,
                    "the run should have dealt " + target.getStringFromEncoding());
            played.add(er.playedFirstMove);
            table.insertEvent(er);
        }
        return played;
    }

    private static MoveChoices bucket(SimulationTable table, HandSituation hs) {
        return table.actionMap.get(hs).countToMoveChoice.get(ZERO);
    }

    /**
     * Each limit governs its own ranks: turning off ace splits leaves 8,8 alone, and
     * turning off the others leaves A,A alone. Ten-valued pairs follow the non-ace limit.
     */
    @Test
    public void eachLimitOnlyGovernsItsOwnPairs() {
        HouseRules noAceSplits = HouseRules.getMtlCasino25MinBlackjackParams(75);
        noAceSplits.numSplitsAces = 0;
        assertFalse(noAceSplits.allowsSplitting(Rank.ACE), "a limit of 0 on aces forbids splitting them");
        assertTrue(noAceSplits.allowsSplitting(Rank.EIGHT), "the ace limit should not reach 8,8");

        HouseRules noOtherSplits = HouseRules.getMtlCasino25MinBlackjackParams(75);
        noOtherSplits.numSplitsNotAces = 0;
        assertFalse(noOtherSplits.allowsSplitting(Rank.EIGHT), "a limit of 0 forbids splitting 8,8");
        assertFalse(noOtherSplits.allowsSplitting(Rank.KING), "tens follow the same limit as eights");
        assertTrue(noOtherSplits.allowsSplitting(Rank.ACE), "the non-ace limit should not reach A,A");

        HouseRules montreal = HouseRules.getMtlCasino25MinBlackjackParams(75);
        for (Rank r : Rank.values()) {
            assertTrue(montreal.allowsSplitting(r), "the Montreal game lets every pair split at least once: " + r);
        }
    }

    /** With no splits allowed, 8,8 is played as sixteen and Split is never recorded. */
    @Test
    public void eightsAreNeverSplitInAGameThatAllowsNoSplits() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        hr.numSplitsNotAces = 0;
        SimulationTable table = tableWaitingOn(hr, EIGHTS_VS_TWO);

        EnumSet<PlayerMove> played = firstMovesPlayed(table, EIGHTS_VS_TWO, Rank.EIGHT);

        ActionPayoff split = bucket(table, EIGHTS_VS_TWO).actionPayoffs.get(PlayerMove.Split);
        assertNull(split, "the game allows no splits, yet Split was recorded for 8,8 "
                + (split == null ? 0 : split.numTimes) + " times in " + EVENTS + " hands");
        assertEquals(EnumSet.of(PlayerMove.Stand, PlayerMove.Hit, PlayerMove.Double), played,
                "the legal first moves on 8,8 in a game without splitting");
    }

    /** With no ace splits allowed, A,A is played as soft 12 and Split is never recorded. */
    @Test
    public void acesAreNeverSplitInAGameThatAllowsNoAceSplits() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        hr.numSplitsAces = 0;
        SimulationTable table = tableWaitingOn(hr, ACES_VS_TWO);

        EnumSet<PlayerMove> played = firstMovesPlayed(table, ACES_VS_TWO, Rank.ACE);

        ActionPayoff split = bucket(table, ACES_VS_TWO).actionPayoffs.get(PlayerMove.Split);
        assertNull(split, "the game allows no ace splits, yet Split was recorded for A,A "
                + (split == null ? 0 : split.numTimes) + " times in " + EVENTS + " hands");
        assertEquals(EnumSet.of(PlayerMove.Stand, PlayerMove.Hit, PlayerMove.Double), played,
                "the legal first moves on A,A in a game without ace splits");
    }

    /** The Montreal game allows both splits, and the run still makes them. */
    @Test
    public void theMontrealLimitsStillOfferTheSplit() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);

        EnumSet<PlayerMove> onEights =
                firstMovesPlayed(tableWaitingOn(hr, EIGHTS_VS_TWO), EIGHTS_VS_TWO, Rank.EIGHT);
        EnumSet<PlayerMove> onAces =
                firstMovesPlayed(tableWaitingOn(hr, ACES_VS_TWO), ACES_VS_TWO, Rank.ACE);

        assertTrue(onEights.contains(PlayerMove.Split),
                "three splits are allowed, so 8,8 should be split sometimes: played " + onEights);
        assertTrue(onAces.contains(PlayerMove.Split),
                "one ace split is allowed, so A,A should be split sometimes: played " + onAces);
    }
}
