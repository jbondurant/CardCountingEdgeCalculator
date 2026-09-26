import com.mongodb.*;
import org.bson.types.ObjectId;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;

public class PayoffTable {

    public CountPayoff[] countPayoffs;
    public String name;
    public int numberCells;
    /**
     * What the stored document said about the code, the rules and the strategy its hands
     * were played under, or null for a payoff table that has never been stored.
     */
    StoredState storedState;

    public PayoffTable(CountPayoff[] cps, String n, int nc){
        countPayoffs = cps;
        name = n;
        numberCells = nc;
    }

    public double getAveragePayoff(){
        ArrayList<ActionPayoff> allActionPayoffs = new ArrayList<>();
        for(int i=0; i<numberCells; i++){
            CountPayoff cp = countPayoffs[i];
            ActionPayoff ap = cp.actionPayoff;
            allActionPayoffs.add(ap);
        }
        double avPayoff = ActionPayoff.getAverage(allActionPayoffs);
        return avPayoff;
    }

    public PayoffTable(int minC, int maxC, double countPrecision, String n){
        name = n;
        double range = (double) (maxC - minC);
        int numCells = (int) (range / countPrecision + 1);
        numberCells = numCells;
        CountPayoff[] cps = new CountPayoff[numCells];

        for(int i=0; i<cps.length; i++){
            double currCount = minC + i * countPrecision;
            GranularCount gc = new GranularCount(currCount);
            cps[i] = new CountPayoff(gc);
        }
        countPayoffs = cps;
    }


    public void insertEventSmart(EventResult er){
        GranularCount gc = er.granularCount;
        double payoff = er.payoff;

        for(CountPayoff cp : countPayoffs){
            if(cp.granularCount.equals(gc)){
                cp.actionPayoff.insertEventSmart(payoff, er.paidNatural);
            }
        }
    }

    public void insertEvent(EventResult er){
        GranularCount gc = er.granularCount;
        double payoff = er.payoff;

        for(CountPayoff cp : countPayoffs){
            if(cp.granularCount.equals(gc)){
                cp.actionPayoff.insertEvent(payoff);
            }
        }
    }

    public static void saveTable(PayoffTable payoffTable, String semanticsKey, String strategyFingerprint) throws UnknownHostException, InterruptedException {
        MongoClient mongoClient = new MongoClient();
        try {
            DB database = mongoClient.getDB("CardCounting");
            DBCollection collection = database.getCollection("PayoffTables");

            BasicDBObject tableObject = payoffTable.getDBObject(semanticsKey, strategyFingerprint);

            BasicDBObject query = new BasicDBObject();
            query.put("_id", tableObject.get("_id"));

            // A single upsert; see SimulationTable.saveTable for why this is not a remove
            // followed by an insert.
            collection.update(query, tableObject, true, false);
        } finally {
            mongoClient.close();
        }
    }

    /**
     * The document saveTable writes. Besides the key of the parameters, it records the
     * fingerprint of the strategy the hands were played with, because the average is only
     * the edge of one strategy if every hand in it was played by that strategy.
     */
    public BasicDBObject getDBObject(String semanticsKey, String strategyFingerprint){
        ObjectId nameID = new ObjectId(name);
        BasicDBObject tableObject = new BasicDBObject("_id", nameID);

        BasicDBList countPayoffsList = new BasicDBList();
        for(int i=0; i< countPayoffs.length; i++){
            CountPayoff cp = countPayoffs[i];
            BasicDBObject cpObject = cp.getDBObject();
            countPayoffsList.add(cpObject);
        }

        tableObject.append("numberCells", numberCells)
            .append("countPayoffsList", countPayoffsList);
        StoredState.stamp(tableObject, semanticsKey, strategyFingerprint);
        return tableObject;
    }

    public static PayoffTable getTable(String name, PayoffTable emptyPaySimTable) throws UnknownHostException {
        MongoClient mongoClient = new MongoClient();
        try {
            DB database = mongoClient.getDB("CardCounting");
            DBCollection collection = database.getCollection("PayoffTables");

            BasicDBObject query = new BasicDBObject();
            ObjectId nameID = new ObjectId(name);
            query.put("_id", nameID);
            BasicDBObject ptObject = (BasicDBObject) collection.findOne(query);
            if(ptObject == null){
                return emptyPaySimTable;
            }
            return fromDBObject(ptObject, name);
        } finally {
            mongoClient.close();
        }
    }

    /** Read a stored payoff table back, along with what its document says about how it was played. */
    public static PayoffTable fromDBObject(BasicDBObject ptObject, String name){
        int numberCells = ptObject.getInt("numberCells");
        BasicDBList cpList = (BasicDBList) ptObject.get("countPayoffsList");
        CountPayoff[] allCP = new CountPayoff[numberCells];
        int i=0;
        for(Object o : cpList){
            CountPayoff cp = CountPayoff.getFromObject((BasicDBObject) o);
            allCP[i] = cp;
            i++;
        }
        PayoffTable pt = new PayoffTable(allCP, name, numberCells);
        pt.storedState = StoredState.readFrom(ptObject);
        return pt;
    }

    /**
     * Refuse to add to a stored payoff table unless its hands were played by the same code,
     * under the same parameters, with the same strategy.
     *
     * getTable used to return whatever was stored under the name, so every payoff run ever
     * saved went into one average: the printed edge, N and blackjack rate mixed strategies
     * and code versions, and a table stored over a narrower count range silently dropped
     * every hand outside it.
     */
    public void resumeUnder(String semanticsKey, String strategyFingerprint){
        if(storedState == null){
            return;
        }
        storedState.refuseToResumeUnlessMeaning(semanticsKey, "PayoffTables", name);
        storedState.refuseToResumeUnlessPlayedWith(strategyFingerprint, name);
    }


}
