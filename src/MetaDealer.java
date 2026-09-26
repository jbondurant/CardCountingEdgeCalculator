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
    /**
     * What the stored document said about the code and the rules it was sampled under, or
     * null for a dealer that has never been stored.
     */
    StoredState storedState;

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
                //System.out.println("mdr not completed:\t" + minMetaDealer + " " + gcadup.granularCount.countToCellString() + " " + gcadup.dealerUpCardScore);
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

    public void saveToDB(String semanticsKey) throws UnknownHostException, InterruptedException {
        MongoClient mongoClient = new MongoClient();
        try {
            DB database = mongoClient.getDB("CardCounting");
            DBCollection collection = database.getCollection("MetaDealers");

            BasicDBObject mdObject = getDBObject(semanticsKey);

            BasicDBObject query = new BasicDBObject();
            query.put("_id", mdObject.get("_id"));

            // A single upsert; see SimulationTable.saveTable for why this is not a remove
            // followed by an insert.
            collection.update(query, mdObject, true, false);
        } finally {
            mongoClient.close();
        }
    }

    /**
     * The document saveToDB writes. The dealer's results carry no parameters of their own,
     * so the key of the ones they were sampled under is recorded next to them.
     */
    public BasicDBObject getDBObject(String semanticsKey){
        ObjectId nameID = new ObjectId(name);
        BasicDBObject mdObject = new BasicDBObject("_id", nameID);

        BasicDBObject dctrObject = new BasicDBObject();
        for(GranularCountAndDealerUpCard gcadup : dealerCountAndUpCardToResults.keySet()){
            String keyAsString = gcadup.getString();
            MetaDealerResult mdr = dealerCountAndUpCardToResults.get(gcadup);
            String valueAsString = mdr.getString();
            dctrObject.append(keyAsString, valueAsString);
        }

        mdObject.append("dctrObject", dctrObject);
        StoredState.stamp(mdObject, semanticsKey);
        return mdObject;
    }

   public static MetaDealer getMetaDealer(String name) throws UnknownHostException {
        MetaDealer emptyMetaDealer = new MetaDealer(name);

       MongoClient mongoClient = new MongoClient();
       try {
           DB database = mongoClient.getDB("CardCounting");
           DBCollection collection = database.getCollection("MetaDealers");

           BasicDBObject query = new BasicDBObject();
           ObjectId nameID = new ObjectId(name);
           query.put("_id", nameID);
           BasicDBObject mdObject = (BasicDBObject) collection.findOne(query);
           if(mdObject == null){
               return emptyMetaDealer;
           }
           return fromDBObject(mdObject, name);
       } finally {
           mongoClient.close();
       }
   }

    /** Read a stored dealer back, along with what its document says about how it was built. */
    public static MetaDealer fromDBObject(BasicDBObject mdObject, String name){
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
        md.storedState = StoredState.readFrom(mdObject);
        return md;
    }

    /**
     * Refuse to add to a stored dealer sampled under other rules or older code.
     *
     * The dealer's document used to hold nothing but its results. Once isCompleted said
     * yes it was never sampled again, so deleting the strategy table to force a rule change
     * still priced every Stand, and through it every other move, against the old rules'
     * dealer.
     */
    public void resumeUnder(String semanticsKey){
        if(storedState != null){
            storedState.refuseToResumeUnlessMeaning(semanticsKey, "MetaDealers", name);
        }
    }

}
