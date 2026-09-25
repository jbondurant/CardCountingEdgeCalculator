import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The table run deals each hand it measures from a list of the ways a full shoe can make
 * that hand, so the lists have to come from a full shoe whatever was dealt before them.
 *
 * runSimulation used to build them from a copy of this.table's shoe. By then the
 * MetaDealer phase had replaced this.table and dealt from it, and in a second session so
 * had the previous table run. The enumeration refuses a partial shoe, and once a copy
 * kept the size its shoe started at, the refusal fired every time: every list came back
 * null and the first table hand threw a NullPointerException.
 */
public class TableRunEnumerationTest {

    private static Simulation sim;
    private static MetaDealer metaDealer;
    private static Simulation.Holdings holdings;

    /**
     * Run the MetaDealer phase the way runSimulation does, then enumerate.
     *
     * The count range is the single bucket at zero, so a few thousand dealer rounds are
     * enough to finish the MetaDealer the first table hand reads from. The enumeration
     * itself does not depend on the count.
     */
    @BeforeAll
    public static void dealTheMetaDealerPhaseThenEnumerate() {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(75);
        SimulationParameters sp = new SimulationParameters(
                hr, CountMethod.getHiLoValue(1), 1.0, 10, 1, 0, 0);
        sim = new Simulation(new SimulationTable(sp, "test"), "test");
        metaDealer = new MetaDealer("test");

        int rounds = 0;
        while (!metaDealer.isCompleted(
                sp.minMetaDealer, sp.minCountish, sp.maxCountish, sp.countGranularity)) {
            MetaDealerEventResult round = sim.runSingleMetaDealerEvent();
            round.granularCount.forceCountIntoBoundaries(sp.minCountish, sp.maxCountish);
            metaDealer.insertEvent(round, hr.dealerPeeksBlackjack);
            rounds++;
            assertTrue(rounds < 1_000_000, "the MetaDealer never finished filling");
        }

        holdings = sim.enumerateHoldings();
    }

    /** Every hand the table run can be asked to deal has ways to deal it. */
    @Test
    public void everySituationHasHoldingsAfterTheTableHasBeenDealtFrom() {
        assertTrue(sim.table.gameDeck.cards.size() < sim.table.gameDeck.startingSize,
                "the MetaDealer phase should have left this.table's shoe partly dealt, "
                        + "which is the state runSimulation enumerates in");

        // The same routing as setCards and givePlayer3CardsThatFitHandEncodingAndCountMaybe.
        HandEncoding hard20 = new HandEncoding(false, false, 20);
        HandEncoding hard21 = new HandEncoding(false, false, 21);
        HandEncoding soft21 = new HandEncoding(true, false, 11);
        for (HandSituation hs : HandSituation.getOrderedSituations()) {
            HandEncoding he = hs.playerHE;
            List<?> ways;
            if (he.equals(hard20)) {
                ways = holdings.hard20;
            } else if (he.equals(hard21)) {
                ways = holdings.hard21;
            } else if (he.equals(soft21)) {
                ways = holdings.soft21;
            } else {
                ways = holdings.twoCard.get(he);
            }
            assertNotNull(ways, "there is no list to deal " + hs.getStringFromEncoding()
                    + " from after the table has been dealt from");
            assertFalse(ways.isEmpty(), "the list to deal " + hs.getStringFromEncoding()
                    + " from is empty, though a full shoe makes it");
        }
    }

    /**
     * And the table run can deal and play a hand from them.
     *
     * An empty table asks first for hard 21 against a two, a three-card hand, which is
     * where the NullPointerException came from.
     */
    @Test
    public void aTableHandCanBePlayedAfterTheTableHasBeenDealtFrom() {
        HashMap<PlayerDealerBestScore, Outcome> outcomeFinder =
                PlayerDealerBestScore.initializeOutcomeFinderForTable(
                        sim.simulationTable.simulationParameters.houseRules);

        EventResult result = assertDoesNotThrow(() -> sim.runSingleEvent(
                        holdings.twoCard, holdings.hard20, holdings.hard21, holdings.soft21,
                        outcomeFinder, metaDealer, 0.5, 0.3),
                "the first table hand after the MetaDealer phase could not be dealt");

        assertNotNull(result, "a dealer two cannot hold a natural, so the hand plays out");
        assertEquals(new HandEncoding(false, false, 21), result.playerHE,
                "the hand dealt is not the hand the table asked for");
    }

    /**
     * The enumeration counts the ways a full shoe makes a hand, so handing it a partial
     * shoe is the caller's mistake, and it fails there, saying how far short the shoe is.
     * It used to print "nope" and return null, which moved the failure to the first hand
     * dealt from the null.
     */
    @Test
    public void enumeratingAPartlyDealtShoeFailsAtTheShoe() {
        CompositeCardSource shoe = CompositeCardSource.getMultiDeck(8);
        shoe.cards.remove(0);
        shoe.cards.remove(0);
        shoe.cards.remove(0);

        IllegalArgumentException twoCard = assertThrows(IllegalArgumentException.class,
                () -> HandEncoding.allPossibleDoubleRanksForHandEncoding(
                        shoe, new HandEncoding(false, false, 16)),
                "a partial shoe must be refused, not answered with null");
        assertTrue(twoCard.getMessage().contains("413")
                        && twoCard.getMessage().contains("416"),
                "the refusal should name both sizes: " + twoCard.getMessage());

        IllegalArgumentException threeCard = assertThrows(IllegalArgumentException.class,
                () -> HandEncoding.allPossibleTripleRanksForHard20(shoe),
                "a partial shoe must be refused, not answered with null");
        assertTrue(threeCard.getMessage().contains("413")
                        && threeCard.getMessage().contains("416"),
                "the refusal should name both sizes: " + threeCard.getMessage());
    }
}
