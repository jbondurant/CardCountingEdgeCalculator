import com.mongodb.*;
import org.bson.types.ObjectId;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;

public class PayoffTable {

    public CountPayoff[] countPayoffs;
    public String name;
    public int numberCells;

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
                cp.actionPayoff.insertEventSmart(payoff);
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

    public static void saveTable(PayoffTable payoffTable) throws UnknownHostException, InterruptedException {
        MongoClient mongoClient = new MongoClient();
        DB database = mongoClient.getDB("CardCounting");
        DBCollection collection = database.getCollection("PayoffTables");

        ObjectId nameID = new ObjectId(payoffTable.name);
        BasicDBObject tableObject = new BasicDBObject("_id", nameID);

        BasicDBList countPayoffsList = new BasicDBList();
        for(int i=0; i< payoffTable.countPayoffs.length; i++){
            CountPayoff cp = payoffTable.countPayoffs[i];
            BasicDBObject cpObject = cp.getDBObject();
            countPayoffsList.add(cpObject);
        }

        BasicDBObject query = new BasicDBObject();
        query.put("_id", nameID);

        System.out.println(payoffTable.name);
        collection.remove(query);

        Thread.sleep(2000);
        tableObject.append("numberCells", payoffTable.numberCells)
            .append("countPayoffsList", countPayoffsList);
        try {
            collection.insert(tableObject);
        } finally {
            collection.update(query, tableObject);
        }
    }

    public static PayoffTable getTable(String name, PayoffTable emptyPaySimTable) throws UnknownHostException {
        MongoClient mongoClient = new MongoClient();
        DB database = mongoClient.getDB("CardCounting");
        DBCollection collection = database.getCollection("PayoffTables");

        BasicDBObject query = new BasicDBObject();
        ObjectId nameID = new ObjectId(name);
        query.put("_id", nameID);
        BasicDBObject ptObject = (BasicDBObject) collection.findOne(query);
        if(ptObject == null){
            return emptyPaySimTable;
        }
        String n = ptObject.getString("name");
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
        return pt;

    }


}
