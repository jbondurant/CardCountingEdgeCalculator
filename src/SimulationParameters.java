import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;

import java.util.Objects;

public class SimulationParameters {
    public HouseRules houseRules;
    public CountMethod countMethod;
    public double countGranularity;
    public int minHitsPerDecisionCellCountA;
    public int minHitsPerDecisionCellCountB;
    public int minCountish;
    public int maxCountish;

    public SimulationParameters(HouseRules hr, CountMethod cm, double cg, int mhpdcca, int mhpdccb, int minC, int maxC){
        houseRules = hr;
        countMethod = cm;
        countGranularity = cg;
        minHitsPerDecisionCellCountA = mhpdcca;
        minHitsPerDecisionCellCountB = mhpdccb;
        minCountish = minC;
        maxCountish = maxC;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SimulationParameters that = (SimulationParameters) o;
        return Double.compare(that.countGranularity, countGranularity) == 0 && minHitsPerDecisionCellCountA == that.minHitsPerDecisionCellCountA && minHitsPerDecisionCellCountB == that.minHitsPerDecisionCellCountB && minCountish == that.minCountish && maxCountish == that.maxCountish && Objects.equals(houseRules, that.houseRules) && Objects.equals(countMethod, that.countMethod);
    }

    @Override
    public int hashCode() {
        return Objects.hash(houseRules, countMethod, countGranularity, minHitsPerDecisionCellCountA, minHitsPerDecisionCellCountB, minCountish, maxCountish);
    }

    public BasicDBObject getDBObject(){
        BasicDBObject houseRulesObject = houseRules.getDBOject();
        BasicDBObject countMethodObject = countMethod.getDBObject();
        BasicDBObject simParamObject = new BasicDBObject("_id", this.hashCode());
        simParamObject.append("houseRules", houseRulesObject)
                .append("countMethod", countMethodObject)
                .append("countGranularity", countGranularity)
                .append("minHitsPerDecisionCellCountA", minHitsPerDecisionCellCountA)
                .append("minHitsPerDecisionCellCountB", minHitsPerDecisionCellCountB)
                .append("minCountish", minCountish)
                .append("maxCountish", maxCountish);

        return simParamObject;
    }

    public static SimulationParameters getSimParamFromObject(BasicDBObject simParamObject){
        HouseRules hr = HouseRules.getHouseRulesFromObject((BasicDBObject) simParamObject.get("houseRules"));
        CountMethod cm = CountMethod.getCountMethodFromObject((BasicDBObject) simParamObject.get("countMethod"));
        double cg = simParamObject.getDouble("countGranularity");
        int mhpdcca = simParamObject.getInt("minHitsPerDecisionCellCountA");
        int mhpdccb = simParamObject.getInt("minHitsPerDecisionCellCountB");
        int minCountish = simParamObject.getInt("minCountish");
        int maxCountish = simParamObject.getInt("maxCountish");

        return new SimulationParameters(hr, cm, cg, mhpdcca, mhpdccb, minCountish, maxCountish);
    }
}
