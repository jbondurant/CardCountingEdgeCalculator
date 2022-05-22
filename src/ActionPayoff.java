import com.mongodb.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.UnknownHostException;
import java.util.ArrayList;

public class ActionPayoff {
    public int numTimes;
    public double avPayoff;
    public BigDecimal avPayoffPrecise;

    public ActionPayoff(){
        numTimes = 0;
        avPayoff = 0;
        avPayoffPrecise = BigDecimal.ZERO;
    }

    public ActionPayoff(int nt, double ap, BigDecimal app){
        numTimes = nt;
        avPayoff = ap;
        avPayoffPrecise = app;
    }

    public static double getAverage(ArrayList<ActionPayoff> aps){
        long numTimesTotal = 0;
        BigDecimal avPayPrecSum = BigDecimal.ZERO;
        for(ActionPayoff ap : aps){
            BigDecimal multAvEvents = ap.avPayoffPrecise.multiply(BigDecimal.valueOf(ap.numTimes));
            avPayPrecSum = avPayPrecSum.add(multAvEvents);
            numTimesTotal += ap.numTimes;
        }
        BigDecimal avPayPrec = avPayPrecSum.divide(BigDecimal.valueOf(numTimesTotal), 100, RoundingMode.FLOOR);
        return avPayPrec.doubleValue();

    }

    public void insertEvent(double payoff){
        BigDecimal multipliedAverage = avPayoffPrecise.multiply(BigDecimal.valueOf(numTimes));
        BigDecimal multAvWithEvent = multipliedAverage.add(BigDecimal.valueOf(payoff));
        numTimes++;
        BigDecimal updatedAverage = multAvWithEvent.divide(BigDecimal.valueOf(numTimes), 100, RoundingMode.FLOOR);
        avPayoffPrecise = updatedAverage;
        avPayoff = updatedAverage.doubleValue();
    }

    public BasicDBObject getDBObject(){
        return new BasicDBObject("_id", this.hashCode())
                .append("numTimes", numTimes)
                .append("avPayoff", avPayoff)
                .append("avPayoffPrecise", avPayoffPrecise.toString());
    }

    public static void main(String[] args) throws UnknownHostException {
        //insertTestAP();
        //getTestAP();
    }

    public static void insertTestAP() throws UnknownHostException {
        ActionPayoff ap = new ActionPayoff();
        ap.avPayoffPrecise = BigDecimal.ONE.divide(BigDecimal.valueOf(9), 100, RoundingMode.FLOOR);

        MongoClient mongoClient = new MongoClient();
        DB database = mongoClient.getDB("CardCountinTest");
        DBCollection collection = database.getCollection("SimulationTablesTest");

        collection.insert(ap.getDBObject());
    }

    public static ActionPayoff getActionPayoffFromObject(BasicDBObject actionPayoffObject){
        int nt = actionPayoffObject.getInt("numTimes");
        double ap = actionPayoffObject.getDouble("avPayoff");
        BigDecimal app = new BigDecimal(actionPayoffObject.getString("avPayoffPrecise"));
        return new ActionPayoff(nt, ap, app);

    }

    public static ActionPayoff getTestAP() throws  UnknownHostException{
        MongoClient mongoClient = new MongoClient();
        DB database = mongoClient.getDB("CardCountinTest");
        DBCollection collection = database.getCollection("SimulationTablesTest");

        ArrayList<DBObject> all = (ArrayList<DBObject>) collection.find().toArray();
        BasicDBObject apObject = (BasicDBObject) all.get(0);
        int numTimes = apObject.getInt("numTimes");
        double avPayoff = apObject.getDouble("avPayoff");
        BigDecimal avPayoffPrecise = new BigDecimal(apObject.getString("avPayoffPrecise"));
        mongoClient.close();
        return new ActionPayoff(numTimes, avPayoff, avPayoffPrecise);
    }
}
