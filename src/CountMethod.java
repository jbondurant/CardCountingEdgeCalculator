import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;

import java.util.ArrayList;
import java.util.EnumMap;

public class CountMethod {
    public EnumMap<Rank, Integer> rankToCount;
    public int deckEstimationPrecision;

    public CountMethod(EnumMap<Rank, Integer> rtc, int dep){
        rankToCount = rtc;
        deckEstimationPrecision = dep; //vs running count only;
    }

    public static CountMethod getHiLoValue(int deckPrecision){
        EnumMap<Rank, Integer> mapHiLo = new EnumMap<Rank, Integer>(Rank.class);
        mapHiLo.put(Rank.TWO, 1);
        mapHiLo.put(Rank.THREE, 1);
        mapHiLo.put(Rank.FOUR, 1);
        mapHiLo.put(Rank.FIVE, 1);
        mapHiLo.put(Rank.SIX, 1);
        mapHiLo.put(Rank.SEVEN, 0);
        mapHiLo.put(Rank.EIGHT, 0);
        mapHiLo.put(Rank.NINE, 0);
        mapHiLo.put(Rank.TEN, -1);
        mapHiLo.put(Rank.JACK, -1);
        mapHiLo.put(Rank.QUEEN, -1);
        mapHiLo.put(Rank.KING, -1);
        mapHiLo.put(Rank.ACE, -1);
        return new CountMethod(mapHiLo,deckPrecision);
    }

    public static int getNumDecksRoundedUp(int numCardsLeft, int deckPrecision, int deckSize){
        double ncl = (double) numCardsLeft;
        double dp = (double) deckPrecision;
        double ds = (double) deckSize;
        return (int) Math.ceil(ncl / (dp * ds));
    }

    public static CountMethod getCountMethodFromObject(BasicDBObject cmObject){
        BasicDBObject rankValuesObject = (BasicDBObject) cmObject.get("rankToCount");
        int deckEstPre = (int) cmObject.get("deckEstimationPrecision");
        EnumMap<Rank, Integer> rtc = new EnumMap<Rank, Integer>(Rank.class);

        for(String s : rankValuesObject.keySet()){
            Rank r = Rank.valueOf(s);
            Integer i = (Integer) rankValuesObject.get(s);
            rtc.put(r, i);
        }
        return new CountMethod(rtc, deckEstPre);

    }

    public BasicDBObject getDBObject(){

        BasicDBObject rankValuesObject = new BasicDBObject();
        for (Rank rank : rankToCount.keySet()) {
            int val = rankToCount.get(rank);
            rankValuesObject.append(rank.name(), val);
        }

        BasicDBObject countMethodObject = new BasicDBObject("_id", this.hashCode())
                .append("rankToCount", rankValuesObject)
                .append("deckEstimationPrecision", deckEstimationPrecision);
        return countMethodObject;
    }

}
