import com.mongodb.BasicDBObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What a stored document says about the code and the rules that produced its numbers, and
 * the check that refuses to add to it when those are not what the running code uses.
 *
 * Every stored table is found by name alone, and it used to be resumed whatever it had
 * been built under. SimulationTable.getTable handed back the stored parameters in place of
 * the code's, the MetaDealer and PayoffTable documents recorded no parameters at all, and
 * none of the three recorded which code wrote it. So a rule changed in getSimulation2
 * never reached a table that already existed, a count fix never reached one either, and
 * the payoff run averaged every run ever saved under the name into one figure, whatever
 * strategy and whatever code each run had been played with. Nothing said so.
 *
 * A running average can only take more of the same thing: once two kinds of observation
 * are averaged together, nothing can separate them again. So each document now records
 * what its numbers mean -- a semantics version for the code, and a key of the parameters
 * for the game -- and adding to it is refused when either differs from the running code.
 * A payoff table also records the strategy its hands were played with.
 *
 * Reading is never refused. printTables can still render any stored table, whatever
 * built it.
 */
public class StoredState {

    /**
     * Bump whenever a change alters what a stored number means -- counting, bucketing,
     * dealing, payoff rules. Tables stored under another version can still be printed,
     * but they can no longer be added to.
     *
     * Version 1 is every document written before this field existed, the 2022 tables
     * among them. Those were built by code with bugs since fixed: ties in the true count
     * rounded upward, a copied shoe counted in 51-card decks, the first observation of
     * every situation dropped, hands with two aces scored hard. So a bucket of theirs does
     * not hold the shoe states the same bucket holds now, and adding to one would average
     * the two.
     */
    public static final int SEMANTICS_VERSION = 2;

    /** What a document that carries no version was written by. */
    static final int VERSION_BEFORE_STAMPING = 1;

    static final String VERSION_FIELD = "semanticsVersion";
    static final String KEY_FIELD = "semanticsKey";
    static final String STRATEGY_FIELD = "strategyFingerprint";

    private static final String ENTRY_SEPARATOR = "; ";

    public final int version;
    /** The key of the parameters the numbers were built under, or null if not recorded. */
    public final String semanticsKey;
    /** The strategy a payoff table's hands were played with, or null if not recorded. */
    public final String strategyFingerprint;

    private StoredState(int version, String semanticsKey, String strategyFingerprint){
        this.version = version;
        this.semanticsKey = semanticsKey;
        this.strategyFingerprint = strategyFingerprint;
    }

    public static StoredState readFrom(BasicDBObject document){
        int version = document.containsField(VERSION_FIELD)
                ? document.getInt(VERSION_FIELD) : VERSION_BEFORE_STAMPING;
        return new StoredState(version, document.getString(KEY_FIELD),
                document.getString(STRATEGY_FIELD));
    }

    public static void stamp(BasicDBObject document, String semanticsKey){
        document.append(VERSION_FIELD, SEMANTICS_VERSION)
                .append(KEY_FIELD, semanticsKey);
    }

    public static void stamp(BasicDBObject document, String semanticsKey, String strategyFingerprint){
        stamp(document, semanticsKey);
        document.append(STRATEGY_FIELD, strategyFingerprint);
    }

    // ------------------------------------------------------------------- the refusal

    /**
     * Refuse unless this document's numbers mean what the running code's would: the same
     * semantics version, and the same key of the parameters.
     */
    public void refuseToResumeUnlessMeaning(String codeKey, String collection, String tableId){
        if(version != SEMANTICS_VERSION){
            throw new IllegalStateException("the stored " + collection + " document for "
                    + describeTable(tableId) + " was written by code of semantics version "
                    + version + ", and this code is version " + SEMANTICS_VERSION + ". Its "
                    + "numbers were counted, bucketed, dealt or paid differently, so adding to "
                    + "them would average two meanings into one. It is left as it is; give "
                    + "this run a new table name to start a fresh one.");
        }
        if(!codeKey.equals(semanticsKey)){
            throw new IllegalStateException("the stored " + collection + " document for "
                    + describeTable(tableId) + " was built under different rules or count "
                    + "settings than this code asks for: " + describeDifference(semanticsKey, codeKey)
                    + ". Adding to it would average two games into one. It is left as it "
                    + "is; give this run a new table name to start a fresh one.");
        }
    }

    /** Refuse unless this payoff document's hands were played with the given strategy. */
    public void refuseToResumeUnlessPlayedWith(String currentFingerprint, String tableId){
        if(!currentFingerprint.equals(strategyFingerprint)){
            throw new IllegalStateException("the stored PayoffTables document for "
                    + describeTable(tableId) + " holds hands played with a different strategy "
                    + "from the one the table plays now: a best move has changed in at least "
                    + "one cell since. Adding to it would average two strategies into one "
                    + "edge. Give this run a new table name, or remove that PayoffTables "
                    + "document if only the payoff count should start over.");
        }
    }

    // ------------------------------------------------------------------------ the key

    /**
     * A document rendered as one canonical string: every field as path=value, sorted by
     * path, with every _id left out.
     *
     * The _id fields are hashCodes of objects that have no equals, so they change on every
     * reload and would make a table look different from itself. The lists are sorted
     * because every list in these parameters is a set, and a HashSet does not promise to
     * iterate the same way twice.
     */
    static String keyOf(BasicDBObject document){
        TreeMap<String, String> entries = new TreeMap<>();
        flatten("", document, entries);
        StringJoiner key = new StringJoiner(ENTRY_SEPARATOR);
        for(Map.Entry<String, String> entry : entries.entrySet()){
            key.add(entry.getKey() + "=" + entry.getValue());
        }
        return key.toString();
    }

    private static void flatten(String prefix, BasicDBObject document, TreeMap<String, String> entries){
        for(String field : document.keySet()){
            if(field.equals("_id")){
                continue;
            }
            Object value = document.get(field);
            if(value instanceof BasicDBObject){
                flatten(prefix + field + ".", (BasicDBObject) value, entries);
            }
            else{
                entries.put(prefix + field, render(value));
            }
        }
    }

    private static String render(Object value){
        if(value instanceof List){
            ArrayList<String> items = new ArrayList<>();
            for(Object item : (List<?>) value){
                items.add(render(item));
            }
            Collections.sort(items);
            return items.toString();
        }
        return String.valueOf(value);
    }

    /** The entries of two keys that differ, in words. */
    static String describeDifference(String storedKey, String codeKey){
        if(storedKey == null){
            return "the stored document does not record what it was built under";
        }
        TreeMap<String, String> stored = entriesOf(storedKey);
        TreeMap<String, String> code = entriesOf(codeKey);
        TreeSet<String> paths = new TreeSet<>(stored.keySet());
        paths.addAll(code.keySet());
        StringJoiner differences = new StringJoiner("; ");
        for(String path : paths){
            String was = stored.get(path);
            String is = code.get(path);
            if(was == null){
                differences.add(path + " is not recorded in the stored document and is " + is + " in the code");
            }
            else if(is == null){
                differences.add(path + " is " + was + " in the stored document and absent from the code");
            }
            else if(!was.equals(is)){
                differences.add(path + " is " + was + " in the stored document and " + is + " in the code");
            }
        }
        return differences.toString();
    }

    private static TreeMap<String, String> entriesOf(String key){
        TreeMap<String, String> entries = new TreeMap<>();
        for(String entry : key.split(ENTRY_SEPARATOR)){
            int equals = entry.indexOf('=');
            if(equals > 0){
                entries.put(entry.substring(0, equals), entry.substring(equals + 1));
            }
        }
        return entries;
    }

    /**
     * A table's name as it was typed, with its _id, for messages. The _id is the name in
     * hex padded with "a" (see Simulation.fixName), so the padding is dropped and the rest
     * decoded; anything that does not decode is shown as it is.
     */
    static String describeTable(String tableId){
        String hex = tableId;
        while(hex.endsWith("aa")){
            hex = hex.substring(0, hex.length() - 2);
        }
        if(hex.isEmpty() || hex.length() % 2 != 0){
            return tableId;
        }
        StringBuilder name = new StringBuilder();
        for(int i = 0; i < hex.length(); i += 2){
            int c;
            try {
                c = Integer.parseInt(hex.substring(i, i + 2), 16);
            } catch (NumberFormatException e) {
                return tableId;
            }
            if(c < 0x20 || c > 0x7e){
                return tableId;
            }
            name.append((char) c);
        }
        return name + " (_id " + tableId + ")";
    }
}
