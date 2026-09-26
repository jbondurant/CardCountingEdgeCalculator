import com.mongodb.BasicDBObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.EnumSet;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A stored table is only added to by code that means the same thing by its numbers.
 *
 * Every table is found by name alone, and a running average cannot tell old observations
 * from new ones once they are in it. So a table built under other rules, another count
 * setting or older code has to be refused rather than resumed: resuming it averages two
 * different games into one number, and nothing afterwards can separate them. That is
 * what used to happen. SimulationTable.getTable handed back the stored parameters in place
 * of the code's, so a rule change never reached an existing table; the MetaDealer kept its
 * dealer distribution whatever rules it came from; and the payoff run kept adding to one
 * PayoffTable across every run and every strategy ever saved under the name.
 *
 * Each document now records a semantics version and a key of the parameters that give its
 * numbers their meaning, and the payoff table also records the strategy it was played
 * with. None of this needs a database, because the documents are Maps.
 */
public class StoredStateTest {

    private static final String ID = Simulation.fixName("testTable2");

    private static SimulationParameters montreal() {
        return new SimulationParameters(HouseRules.getMtlCasino25MinBlackjackParams(75),
                CountMethod.getHiLoValue(1), 1.0, 50000, 100000, -5, 5);
    }

    private static SimulationParameters reloaded(SimulationParameters sp) {
        return SimulationParameters.getSimParamFromObject(sp.getDBObject());
    }

    private static SimulationParameters standsOnSoft17() {
        SimulationParameters sp = montreal();
        sp.houseRules.hitsOnSoft17 = false;
        return sp;
    }

    /** Change one rule, whatever its type, so the key can be checked to cover it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void change(HouseRules hr, Field f) throws IllegalAccessException {
        Class<?> type = f.getType();
        if (type == int.class) {
            f.setInt(hr, f.getInt(hr) + 1);
        } else if (type == boolean.class) {
            f.setBoolean(hr, !f.getBoolean(hr));
        } else if (type == double.class) {
            f.setDouble(hr, f.getDouble(hr) + 0.25);
        } else if (type == EnumSet.class) {
            Class<?> element = (Class<?>) ((ParameterizedType) f.getGenericType())
                    .getActualTypeArguments()[0];
            Enum first = (Enum) element.getEnumConstants()[0];
            EnumSet set = (EnumSet) f.get(hr);
            if (set.contains(first)) {
                set.remove(first);
            } else {
                set.add(first);
            }
        } else if (type == HashSet.class) {
            ((HashSet<Integer>) f.get(hr)).add(9);
        } else {
            fail("this test does not know how to change a " + type.getSimpleName()
                    + ", so it cannot check that " + f.getName() + " reaches the key; teach it");
        }
    }

    private static Field[] rules() {
        return java.util.Arrays.stream(HouseRules.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .toArray(Field[]::new);
    }

    // ------------------------------------------------------------------------ the key

    @Test
    public void theKeySurvivesASaveAndLoad() {
        SimulationParameters sp = montreal();
        assertEquals(sp.getSemanticsKey(), reloaded(sp).getSemanticsKey(),
                "a table that was saved and loaded must still match the code that saved it");
        assertEquals(sp.getSemanticsKey(), reloaded(reloaded(sp)).getSemanticsKey(),
                "and again after a second trip");
    }

    /**
     * Every house rule changes the key, and every changed ruleset still survives a save
     * and load. The rules are walked by reflection, so a rule added later is covered
     * without anyone remembering this test.
     */
    @Test
    public void everyHouseRuleChangesTheKey() throws IllegalAccessException {
        String montrealKey = montreal().getSemanticsKey();
        for (Field f : rules()) {
            SimulationParameters sp = montreal();
            change(sp.houseRules, f);
            assertNotEquals(montrealKey, sp.getSemanticsKey(),
                    "changing " + f.getName() + " left the key alone, so a table built under "
                            + "the old value would be resumed under the new one");
            assertEquals(sp.getSemanticsKey(), reloaded(sp).getSemanticsKey(),
                    "with " + f.getName() + " changed, the key did not survive a save and load");
        }
    }

    @Test
    public void theCountMethodChangesTheKey() {
        String montrealKey = montreal().getSemanticsKey();

        SimulationParameters otherTags = montreal();
        otherTags.countMethod.rankToCount.put(Rank.ACE, 0);
        assertNotEquals(montrealKey, otherTags.getSemanticsKey(),
                "counting the ace as zero is a different count, and the key did not change");

        SimulationParameters otherPrecision = montreal();
        otherPrecision.countMethod.deckEstimationPrecision = 2;
        assertNotEquals(montrealKey, otherPrecision.getSemanticsKey(),
                "estimating decks to the half-shoe is a different count, and the key did not change");
    }

    @Test
    public void theGrainAndTheBoundsChangeTheKey() {
        String montrealKey = montreal().getSemanticsKey();
        SimulationParameters m = montreal();
        SimulationParameters finer = new SimulationParameters(m.houseRules, m.countMethod,
                0.5, m.minHitsPerDecisionCellCount, m.minMetaDealer, -5, 5);
        SimulationParameters lower = new SimulationParameters(m.houseRules, m.countMethod,
                1.0, m.minHitsPerDecisionCellCount, m.minMetaDealer, -4, 5);
        SimulationParameters upper = new SimulationParameters(m.houseRules, m.countMethod,
                1.0, m.minHitsPerDecisionCellCount, m.minMetaDealer, -5, 6);

        assertNotEquals(montrealKey, finer.getSemanticsKey(), "a finer grain must change the key");
        assertNotEquals(montrealKey, lower.getSemanticsKey(), "a new lower bound must change the key");
        assertNotEquals(montrealKey, upper.getSemanticsKey(), "a new upper bound must change the key");
    }

    /**
     * How much evidence is enough is not what the evidence means. Raising either threshold
     * on an existing table only asks it to keep going, which is how testTable2 went from
     * 10000 to 50000 hits a cell in 2022.
     */
    @Test
    public void theProgressThresholdsDoNotChangeTheKey() {
        SimulationParameters m = montreal();
        SimulationParameters raised = new SimulationParameters(m.houseRules, m.countMethod,
                1.0, 500000, 1000000, -5, 5);
        assertEquals(montreal().getSemanticsKey(), raised.getSemanticsKey(),
                "raising minHits or minMetaDealer should not make a table unresumable");
    }

    // ------------------------------------------------------------------ the refusal

    /** Documents written before the version existed carry no field, and are version 1. */
    @Test
    public void aDocumentWithoutAVersionIsVersionOne() {
        BasicDBObject asWrittenIn2022 = new BasicDBObject("numberCells", 11);
        assertEquals(1, StoredState.readFrom(asWrittenIn2022).version,
                "a document with no version was written before there was one");
        // Pinned on purpose, so that changing what a stored number means is a decision
        // someone makes here rather than a side effect.
        assertEquals(3, StoredState.SEMANTICS_VERSION, "this code is version 3");
    }

    /**
     * A strategy table stored by earlier code, like the 2022 tables, was built by code
     * whose count and payoff bugs have since been fixed. It can be printed, but not added
     * to.
     */
    @Test
    public void aTableStoredByEarlierCodeIsRefused() {
        BasicDBObject document = new SimulationTable(montreal(), ID).getDBObject();
        document.removeField("semanticsVersion");
        document.removeField("semanticsKey");
        SimulationTable stored = SimulationTable.fromDBObject(document, ID);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> stored.resumeUnder(montreal()),
                "a table from version 1 code was resumed as though its buckets meant the same");
        String message = e.getMessage();
        assertTrue(message.contains("version 1")
                        && message.contains("version " + StoredState.SEMANTICS_VERSION),
                "the message should say which versions differ: " + message);
        assertTrue(message.contains("testTable2"), "the message should name the table: " + message);
        assertTrue(message.contains("new table name"),
                "the message should say what to do about it: " + message);
    }

    /**
     * The version just before this one and the one just after are both refused. The one
     * before is the case that matters: version 2 paid every split ace and ten as an ordinary
     * 21, whatever blackjackOnSplitPairs said.
     */
    @Test
    public void aTableFromAnotherVersionIsRefused() {
        for (int other : new int[]{StoredState.SEMANTICS_VERSION - 1,
                StoredState.SEMANTICS_VERSION + 1}) {
            BasicDBObject document = new SimulationTable(montreal(), ID).getDBObject();
            document.put("semanticsVersion", other);
            SimulationTable stored = SimulationTable.fromDBObject(document, ID);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> stored.resumeUnder(montreal()),
                    "a table from version " + other + " was resumed");
            assertTrue(e.getMessage().contains("version " + other),
                    "the message should name it: " + e.getMessage());
        }
    }

    /** The rule change in the report: the code asks for S17, the table was built under H17. */
    @Test
    public void aTableStoredUnderOtherRulesIsRefusedNamingTheRule() {
        BasicDBObject document = new SimulationTable(montreal(), ID).getDBObject();
        SimulationTable stored = SimulationTable.fromDBObject(document, ID);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> stored.resumeUnder(standsOnSoft17()),
                "an H17 table was resumed by code that asks for S17");
        String message = e.getMessage();
        assertTrue(message.contains("hitsOnSoft17"), "the message should name the rule: " + message);
        assertTrue(message.contains("true") && message.contains("false"),
                "the message should give both values: " + message);
        assertFalse(message.contains("numDecks"),
                "the message should name only what differs: " + message);
        assertTrue(message.contains("testTable2"), "the message should name the table: " + message);
        assertTrue(message.contains("new table name"),
                "the message should say what to do about it: " + message);
    }

    /** When the meaning matches, the table is resumed, and the code's thresholds apply. */
    @Test
    public void aMatchingTableIsResumedUnderTheCodesThresholds() {
        SimulationTable built = new SimulationTable(montreal(), ID);
        built.insertEvent(new EventResult(
                -0.4, new HandEncoding(false, false, 16), Rank.TEN, PlayerMove.Stand,
                new GranularCount(0.0)));
        SimulationTable stored = SimulationTable.fromDBObject(built.getDBObject(), ID);

        SimulationParameters m = montreal();
        SimulationParameters raised = new SimulationParameters(m.houseRules, m.countMethod,
                1.0, 500000, 1000000, -5, 5);
        assertDoesNotThrow(() -> stored.resumeUnder(raised));

        assertEquals(500000, stored.simulationParameters.minHitsPerDecisionCellCount,
                "the stored threshold replaced the code's");
        assertEquals(1000000, stored.simulationParameters.minMetaDealer,
                "the stored threshold replaced the code's");
        assertEquals(1, stored.actionMap.size(), "the stored observations should be kept");
    }

    @Test
    public void aTableThatWasNeverStoredHasNothingToCheck() {
        SimulationTable fresh = new SimulationTable(montreal(), ID);
        assertDoesNotThrow(() -> fresh.resumeUnder(montreal()));
    }

    /**
     * The dealer distribution is priced under one set of rules. Deleting the strategy
     * table to force S17 used to leave the H17 dealer in place, complete and never
     * resampled, so every Stand value was priced against the wrong dealer.
     */
    @Test
    public void aDealerFromOtherRulesIsRefused() {
        MetaDealer h17 = new MetaDealer(ID);
        MetaDealer stored = MetaDealer.fromDBObject(
                h17.getDBObject(montreal().getSemanticsKey()), ID);

        assertDoesNotThrow(() -> stored.resumeUnder(montreal().getSemanticsKey()));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> stored.resumeUnder(standsOnSoft17().getSemanticsKey()),
                "an H17 dealer was resumed by code that asks for S17");
        assertTrue(e.getMessage().contains("hitsOnSoft17"),
                "the message should name the rule: " + e.getMessage());

        BasicDBObject asWrittenIn2022 = h17.getDBObject(montreal().getSemanticsKey());
        asWrittenIn2022.removeField("semanticsVersion");
        asWrittenIn2022.removeField("semanticsKey");
        MetaDealer old = MetaDealer.fromDBObject(asWrittenIn2022, ID);
        assertThrows(IllegalStateException.class,
                () -> old.resumeUnder(montreal().getSemanticsKey()),
                "a dealer from version 1 code was resumed");
    }

    // ------------------------------------------------------------------ the strategy

    private static final HandEncoding HARD_16 = new HandEncoding(false, false, 16);
    private static final HandEncoding HARD_12 = new HandEncoding(false, false, 12);

    private static void record(SimulationTable t, HandEncoding he, Rank up, PlayerMove pm,
                               double count, double payoff, int times) {
        for (int i = 0; i < times; i++) {
            t.insertEvent(new EventResult(payoff, he, up, pm, new GranularCount(count)));
        }
    }

    /** Stand on 16 against a ten, stand on 12 against a four. */
    private static SimulationTable smallStrategy() {
        SimulationTable t = new SimulationTable(montreal(), ID);
        record(t, HARD_16, Rank.TEN, PlayerMove.Stand, 0.0, -0.5, 3);
        record(t, HARD_16, Rank.TEN, PlayerMove.Hit, 0.0, -0.6, 3);
        record(t, HARD_12, Rank.FOUR, PlayerMove.Stand, 1.0, -0.2, 3);
        record(t, HARD_12, Rank.FOUR, PlayerMove.Hit, 1.0, -0.25, 3);
        return t;
    }

    @Test
    public void theFingerprintChangesWhenOneCellsBestMoveChanges() {
        SimulationTable t = smallStrategy();
        String before = t.getStrategyFingerprint();

        // Enough good hits to put hitting 16 against a ten ahead of standing.
        record(t, HARD_16, Rank.TEN, PlayerMove.Hit, 0.0, 1.0, 10);
        assertEquals("Hit", t.actionMap.get(new HandSituation(HARD_16, 10))
                .countToMoveChoice.get(new GranularCount(0.0)).getCompoundBestMove());

        assertNotEquals(before, t.getStrategyFingerprint(),
                "one cell's best move changed and the fingerprint did not");
    }

    /** More data that leaves every best move where it was leaves the strategy alone. */
    @Test
    public void moreDataThatMovesNoBestMoveLeavesTheFingerprintAlone() {
        SimulationTable t = smallStrategy();
        String before = t.getStrategyFingerprint();
        record(t, HARD_16, Rank.TEN, PlayerMove.Stand, 0.0, -0.5, 5);
        assertEquals(before, t.getStrategyFingerprint(),
                "the same strategy with more evidence behind it should fingerprint the same");
    }

    /** The payoff run compares a fingerprint taken now with one taken in an earlier session. */
    @Test
    public void theFingerprintSurvivesASaveAndLoad() {
        SimulationTable t = smallStrategy();
        SimulationTable back = SimulationTable.fromDBObject(t.getDBObject(), ID);
        assertEquals(t.getStrategyFingerprint(), back.getStrategyFingerprint(),
                "a table read back plays the same strategy and must fingerprint the same");
    }

    /**
     * A payoff table's average is the edge of one strategy only if every hand in it was
     * played by that strategy.
     */
    @Test
    public void aPayoffTablePlayedWithAnotherStrategyIsRefused() {
        String key = montreal().getSemanticsKey();
        String played = smallStrategy().getStrategyFingerprint();
        SimulationTable changed = smallStrategy();
        record(changed, HARD_16, Rank.TEN, PlayerMove.Hit, 0.0, 1.0, 10);

        PayoffTable run = new PayoffTable(-5, 5, 1.0, ID);
        PayoffTable stored = PayoffTable.fromDBObject(run.getDBObject(key, played), ID);

        assertDoesNotThrow(() -> stored.resumeUnder(key, played));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> stored.resumeUnder(key, changed.getStrategyFingerprint()),
                "hands played with one strategy were about to be averaged with another's");
        assertTrue(e.getMessage().contains("strategy"),
                "the message should say the strategy changed: " + e.getMessage());
        assertTrue(e.getMessage().contains("testTable2"),
                "the message should name the table: " + e.getMessage());
    }

    /**
     * The 2022 payoff document has no version, eleven cells where it had nine, and no
     * blackjack counts. Resuming it dropped every hand at plus or minus five and diluted
     * the blackjack rate, so it is refused like any other version 1 document.
     */
    @Test
    public void aPayoffTableStoredByEarlierCodeIsRefused() {
        String key = montreal().getSemanticsKey();
        String played = smallStrategy().getStrategyFingerprint();
        BasicDBObject asWrittenIn2022 = new PayoffTable(-4, 4, 1.0, ID).getDBObject(key, played);
        asWrittenIn2022.removeField("semanticsVersion");
        asWrittenIn2022.removeField("semanticsKey");
        asWrittenIn2022.removeField("strategyFingerprint");
        PayoffTable stored = PayoffTable.fromDBObject(asWrittenIn2022, ID);

        assertThrows(IllegalStateException.class, () -> stored.resumeUnder(key, played),
                "a payoff table from version 1 code was resumed");
    }

    @Test
    public void thePayoffRunRefusesAnUnfinishedTable() {
        Simulation sim = new Simulation(smallStrategy(), ID);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                sim::fingerprintOfFinishedStrategy,
                "the payoff run started on a table with cells still to fill");
        assertTrue(e.getMessage().contains("not finished"),
                "the message should say the table is unfinished: " + e.getMessage());
        assertTrue(e.getMessage().contains("testTable2"),
                "the message should name the table: " + e.getMessage());
    }

    @Test
    public void thePayoffRunAcceptsAFinishedTable() {
        SimulationParameters m = montreal();
        SimulationParameters oneBucket = new SimulationParameters(
                m.houseRules, m.countMethod, 1.0, 1, 1, 0, 0);
        SimulationTable t = new SimulationTable(oneBucket, ID);
        for (HandSituation hs : HandSituation.getOrderedSituations()) {
            Rank up = hs.dealerRankVal == 11 ? Rank.ACE : Rank.values()[hs.dealerRankVal - 1];
            assertEquals(hs.dealerRankVal, up.getRankpoints(), "picked the wrong up-card");
            record(t, hs.playerHE, up, PlayerMove.Stand, 0.0, -0.1, 1);
        }
        Simulation sim = new Simulation(t, ID);

        assertEquals(t.getStrategyFingerprint(), sim.fingerprintOfFinishedStrategy(),
                "a finished table should be played, under its own fingerprint");
    }
}
