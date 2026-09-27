import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Simulation refuses house rules the engine does not play.
 *
 * HouseRules can describe more games than the engine knows how to deal. Some fields are
 * never read at all, and a few are read only halfway, so setting them changes nothing or
 * changes the wrong thing. Either way the run goes ahead, the tables come out looking like
 * any other tables, and they describe a game nobody configured. Nothing downstream can
 * tell, so the only place to catch it is before the first card.
 *
 * Each rule is tested on its own, starting from the Montreal rules, so a failure names the
 * one rule that slipped through.
 */
public class UnplayableRulesTest {

    private static HouseRules montreal() {
        return HouseRules.getMtlCasino25MinBlackjackParams(75);
    }

    private static Simulation simulationFor(HouseRules hr) {
        SimulationParameters sp = new SimulationParameters(
                hr, CountMethod.getHiLoValue(1), 1.0, 10, 10, -5, 5);
        return new Simulation(new SimulationTable(sp, "test"), "test");
    }

    /**
     * The rules differ from Montreal in exactly one field, and that field is the only
     * thing refused: a Simulation will not be built on them, and the reason names it.
     */
    private static void assertRefusedAlone(HouseRules hr, String field) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> simulationFor(hr),
                "a Simulation was built on rules with " + field + " set, which the engine "
                        + "does not play");
        assertTrue(e.getMessage().contains(field),
                "the refusal should name " + field + ", but said: " + e.getMessage());

        List<String> unplayable = hr.unplayableRules();
        assertEquals(1, unplayable.size(),
                "only " + field + " was changed, but the list of unplayable rules is "
                        + unplayable);
        assertTrue(unplayable.get(0).contains(field),
                "the one unplayable rule should be " + field + ", but it is "
                        + unplayable.get(0));
    }

    /** The rules the published tables were built on have to pass, or nothing runs. */
    @Test
    public void theMontrealRulesArePlayable() {
        HouseRules hr = montreal();
        assertEquals(List.of(), hr.unplayableRules(),
                "the Montreal rules are the ones the engine was written for");
        assertDoesNotThrow(hr::requirePlayable);
        assertDoesNotThrow(() -> simulationFor(hr));
    }

    /**
     * Without a peek a dealer natural takes doubles and splits in full. The MetaDealer
     * drops every natural whether or not the dealer peeks, so the table run never prices
     * one and the tables would be the peek game's tables with a different label.
     */
    @Test
    public void aDealerWhoDoesNotPeekIsRefused() {
        HouseRules hr = montreal();
        hr.dealerPeeksBlackjack = false;
        assertRefusedAlone(hr, "dealerPeeksBlackjack");
    }

    /**
     * Early surrender is offered before the peek, so it is worth something against a
     * natural. The engine settles naturals first, which is late surrender.
     */
    @Test
    public void earlySurrenderIsRefused() {
        HouseRules hr = montreal();
        hr.canEarlySurrender = true;
        assertRefusedAlone(hr, "canEarlySurrender");
    }

    /** Late surrender is what the engine does model, so it is not refused. */
    @Test
    public void lateSurrenderIsPlayable() {
        HouseRules hr = montreal();
        hr.canLateSurrender = true;
        assertEquals(List.of(), hr.unplayableRules());
        assertDoesNotThrow(() -> simulationFor(hr));
    }

    /**
     * Both split paths read surrender after a split, so it is not refused either, with
     * surrender offered or without it, where it simply means nothing.
     */
    @Test
    public void surrenderAfterASplitIsPlayable() {
        HouseRules hr = montreal();
        hr.canSurrenderAfterSplit = true;
        assertEquals(List.of(), hr.unplayableRules());
        hr.canLateSurrender = true;
        assertEquals(List.of(), hr.unplayableRules());
        assertDoesNotThrow(() -> simulationFor(hr));
    }

    @Test
    public void doubleDownRescueIsRefused() {
        HouseRules hr = montreal();
        hr.hasDoubleDownRescue = true;
        assertRefusedAlone(hr, "hasDoubleDownRescue");
    }

    @Test
    public void swappingCardsBetweenHandsIsRefused() {
        HouseRules hr = montreal();
        hr.canSwap = true;
        assertRefusedAlone(hr, "canSwap");
    }

    /**
     * Push-22 pushes every standing hand against a dealer 22. The outcome code pushes
     * only a player 21 there, and the MetaDealer counts a 22 in the same bin as every
     * other bust, so the table run could not price the rule even if the outcome were right.
     */
    @Test
    public void pushingOnADealer22IsRefused() {
        HouseRules hr = montreal();
        hr.pushOnDealerHard22 = true;
        assertRefusedAlone(hr, "pushOnDealerHard22");
    }

    /**
     * The rule is that a player 21 wins even against a dealer natural. The peek settles
     * the natural before the player draws, so there is never a 21 to hold against it.
     */
    @Test
    public void aPlayer21ThatAlwaysWinsIsRefused() {
        HouseRules hr = montreal();
        hr.player21AlwaysWins = true;
        assertRefusedAlone(hr, "player21AlwaysWins");
    }

    @Test
    public void dealingMoreThanOneHandIsRefused() {
        HouseRules hr = montreal();
        hr.numHandsDealt = 2;
        assertRefusedAlone(hr, "numHandsDealt");
    }

    /** The engine lets any two-card hand double, so a narrower set is not played. */
    @Test
    public void limitingWhichHandsCanDoubleIsRefused() {
        HouseRules hr = montreal();
        hr.notSplitCardsThatCanBeDoubled = Rank.getSetAllRanksExceptAce();
        assertRefusedAlone(hr, "notSplitCardsThatCanBeDoubled");
    }

    @Test
    public void freeDoublesAreRefused() {
        HouseRules hr = montreal();
        hr.scoreOfHardHandsPairsThatCanBeFreeDoubled = new HashSet<>(List.of(9, 10, 11));
        assertRefusedAlone(hr, "scoreOfHardHandsPairsThatCanBeFreeDoubled");
    }

    @Test
    public void freeSplitsAreRefused() {
        HouseRules hr = montreal();
        hr.ranksWithFreeBetAfterSplit = Rank.getSetAllRanksExceptAce();
        assertRefusedAlone(hr, "ranksWithFreeBetAfterSplit");
    }

    /** Split aces that cannot hit take one card each, so any other count is not played. */
    @Test
    public void splitAcesThatCannotHitMustTakeExactlyOneCard() {
        HouseRules hr = montreal();
        hr.maxNumCardsAfterSplittingAces = 2;
        assertRefusedAlone(hr, "maxNumCardsAfterSplittingAces");

        HouseRules none = montreal();
        none.maxNumCardsAfterSplittingAces = 0;
        assertRefusedAlone(none, "maxNumCardsAfterSplittingAces");
    }

    /**
     * Split aces that can hit are hit with no limit at all. A limit of 21 cards is no
     * limit, since an ace and twenty more cards is at least 21, but anything lower is a
     * rule the engine would ignore.
     */
    @Test
    public void aCardLimitOnSplitAcesThatCanHitIsRefused() {
        HouseRules hr = montreal();
        hr.canHitAfterSplittingAces = true;
        hr.maxNumCardsAfterSplittingAces = 3;
        assertRefusedAlone(hr, "maxNumCardsAfterSplittingAces");
    }

    @Test
    public void splitAcesThatCanHitWithoutALimitArePlayable() {
        HouseRules hr = montreal();
        hr.canHitAfterSplittingAces = true;
        hr.maxNumCardsAfterSplittingAces = 21;
        assertEquals(List.of(), hr.unplayableRules());
        assertDoesNotThrow(() -> simulationFor(hr));
    }

    /** Declining a side bet is always a legal way to play, so offering more is harmless. */
    @Test
    public void offeringSideBetsIsNeverRefused() {
        HouseRules hr = montreal();
        hr.possibleSideBets = EnumSet.allOf(PlayerSideBetMove.class);
        assertEquals(List.of(), hr.unplayableRules());
        assertDoesNotThrow(() -> simulationFor(hr));
    }

    /** A ruleset with several problems gets all of them in one message, not one per run. */
    @Test
    public void everyUnplayableRuleIsNamedAtOnce() {
        HouseRules hr = montreal();
        hr.canSwap = true;
        hr.numHandsDealt = 3;
        hr.pushOnDealerHard22 = true;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                hr::requirePlayable);
        for (String field : List.of("canSwap", "numHandsDealt", "pushOnDealerHard22")) {
            assertTrue(e.getMessage().contains(field),
                    "the refusal should name " + field + ", but said: " + e.getMessage());
        }
        assertEquals(3, hr.unplayableRules().size(), "three rules were changed");
    }
}
