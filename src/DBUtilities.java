import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;

import java.util.EnumSet;
import java.util.HashSet;

public class DBUtilities {

    public static EnumSet<Rank> getEnumRankSetFromObject(BasicDBObject ersObject, String key){
        EnumSet<Rank> allRanks = EnumSet.noneOf(Rank.class);
        BasicDBObject ersVal = (BasicDBObject) ersObject.get(key);
        BasicDBList ersList = (BasicDBList) ersVal.get("allRanks");
        for(Object o : ersList){
            allRanks.add(Rank.valueOf((String) o));
        }
        return allRanks;
    }

    public static BasicDBObject getObjectFromEnumRankSet(EnumSet<Rank> rankSet){
        BasicDBObject rankSetObject = new BasicDBObject("_id", rankSet.hashCode());
        BasicDBList allRanksList = new BasicDBList();
        for(Rank rank : rankSet){
            allRanksList.add(rank.name());
        }
        rankSetObject.append("allRanks", allRanksList);
        return rankSetObject;
    }

    public static EnumSet<PlayerSideBetMove> getEnumPSBMSetFromObject(BasicDBObject ePSBMsObject, String key){
        EnumSet<PlayerSideBetMove> allPSBM = EnumSet.noneOf(PlayerSideBetMove.class);
        BasicDBObject ePSBMsVal = (BasicDBObject) ePSBMsObject.get(key);
        BasicDBList ePSBMsList = (BasicDBList) ePSBMsVal.get("allPSBM");
        for(Object o : ePSBMsList){
            allPSBM.add(PlayerSideBetMove.valueOf((String) o));
        }
        return allPSBM;
    }

    public static BasicDBObject getObjectFromEnumPSBMSet(EnumSet<PlayerSideBetMove> psbmSet){
        BasicDBObject psbmSetObject = new BasicDBObject("_id", psbmSet.hashCode());
        BasicDBList allPsbmList = new BasicDBList();
        for(PlayerSideBetMove psbm : psbmSet){
            allPsbmList.add(psbm.name());
        }
        psbmSetObject.append("allPSBM", allPsbmList);
        return psbmSetObject;
    }

    public static HashSet<Integer> getIntSetFromObject(BasicDBObject iSetObject, String key){
        HashSet<Integer> allInts = new HashSet<>();
        BasicDBObject iSetVal = (BasicDBObject) iSetObject.get(key);
        BasicDBList iSetList = (BasicDBList) iSetVal.get("allInts");
        for(Object o : iSetList){
            allInts.add(Integer.valueOf((String) o));
        }
        return allInts;
    }

    public static BasicDBObject getObjectFromIntSet(HashSet<Integer> scores){
        BasicDBObject intSetObject = new BasicDBObject("_id", scores.hashCode());
        BasicDBList allIntsList = new BasicDBList();
        for(Integer i : scores){
            allIntsList.add(i);
        }
        intSetObject.append("allInts", allIntsList);
        return intSetObject;
    }
}
