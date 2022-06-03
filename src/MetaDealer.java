import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.MongoClient;
import org.bson.types.ObjectId;

import java.net.UnknownHostException;
import java.util.HashMap;

public class MetaDealer {
    public HashMap<GranularCountAndDealerUpCard, MetaDealerResult> dealerCountAndUpCardToResults;
    public String name;

    public MetaDealer(String n){
        dealerCountAndUpCardToResults = new HashMap<>();
        name = n;
    }

    public boolean isCompleted(int minMetaDealer, int minC, int maxC, double countPrecision){
        int numCompleted = 0;
        int numRequired = GranularCount.numGranCount(countPrecision, minC, maxC) * 10;
        if(dealerCountAndUpCardToResults.keySet() == null){
            return false;
        }
        for(GranularCountAndDealerUpCard gcadup : dealerCountAndUpCardToResults.keySet()){
            if(!gcadup.granularCount.isCountInBoundaries(minC, maxC)){
                continue;
            }
            MetaDealerResult mdr = dealerCountAndUpCardToResults.get(gcadup);
            if(!mdr.isCompleted(minMetaDealer)){
                System.out.println("mdr not completed:\t" + minMetaDealer + " " + gcadup.granularCount.countToCellString() + " " + gcadup.dealerUpCardScore);
                return false;
            }
            numCompleted++;
        }
        if(numCompleted < numRequired){
            return false;
        }
        return true;
    }

    public void insertEvent(MetaDealerEventResult mder, boolean dealerPeeksForBlackjack){
        GranularCount gc = mder.granularCount;
        int dealerBestScore = mder.dealerBestScore;
        boolean dealerHasBlackjack = mder.dealerHasBlackjack;
        GranularCountAndDealerUpCard gcadup = new GranularCountAndDealerUpCard(gc, mder.dealerRevealedCardScore);

        if(dealerHasBlackjack && dealerPeeksForBlackjack){
            return;
        }
        MetaDealerResult mdr = dealerCountAndUpCardToResults.get(gcadup);
        if(mdr == null){
            mdr = new MetaDealerResult();
        }
        mdr.insertEvent(dealerBestScore, dealerHasBlackjack);
        dealerCountAndUpCardToResults.put(gcadup, mdr);
    }

    public void saveToDB() throws UnknownHostException, InterruptedException {
        MongoClient mongoClient = new MongoClient();
        DB database = mongoClient.getDB("CardCounting");
        DBCollection collection = database.getCollection("MetaDealers");

        ObjectId nameID = new ObjectId(name);
        BasicDBObject mdObject = new BasicDBObject("_id", nameID);

        BasicDBObject dctrObject = new BasicDBObject();
        for(GranularCountAndDealerUpCard gcadup : dealerCountAndUpCardToResults.keySet()){
            String keyAsString = gcadup.getString();
            MetaDealerResult mdr = dealerCountAndUpCardToResults.get(gcadup);
            String valueAsString = mdr.getString();
            dctrObject.append(keyAsString, valueAsString);
        }


        BasicDBObject query = new BasicDBObject();
        query.put("_id", nameID);

        System.out.println(name);
        System.out.println("about to remove metaDealer");
        collection.remove(query);

        Thread.sleep(2000);


        mdObject.append("dctrObject", dctrObject);
        //collection.update(query, tableObject);
        try {
            collection.insert(mdObject);
            System.out.println("reinserted metaDealer part A");
        } finally {
            collection.update(query, mdObject);
            System.out.println("reinserted metaDealer part B");

        }
    }

   public static MetaDealer getMetaDealer(String name) throws UnknownHostException {
        MetaDealer emptyMetaDealer = new MetaDealer(name);

       MongoClient mongoClient = new MongoClient();
       DB database = mongoClient.getDB("CardCounting");
       DBCollection collection = database.getCollection("MetaDealers");

       BasicDBObject query = new BasicDBObject();
       ObjectId nameID = new ObjectId(name);
       query.put("_id", nameID);
       BasicDBObject mdObject = (BasicDBObject) collection.findOne(query);
       if(mdObject == null){
           return emptyMetaDealer;
       }

       BasicDBObject dctrObject = (BasicDBObject) mdObject.get("dctrObject");
       HashMap<GranularCountAndDealerUpCard, MetaDealerResult> dctr = new HashMap<>();


       for(String s : dctrObject.keySet()){
           GranularCountAndDealerUpCard gcadup = GranularCountAndDealerUpCard.getFromString(s);
           String mdrString = (String) dctrObject.get(s);
           MetaDealerResult mdr = MetaDealerResult.getMetaDealerResultFromString(mdrString);
           dctr.put(gcadup, mdr);
       }

       MetaDealer md = new MetaDealer(name);
       md.dealerCountAndUpCardToResults = dctr;

       return md;

   }

}
