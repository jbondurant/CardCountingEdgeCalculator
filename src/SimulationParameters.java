import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;

import java.util.Objects;

public class SimulationParameters {
    public HouseRules houseRules;
    public CountMethod countMethod;
    public double countGranularity;
    public int minHitsPerDecisionCellCount;
    public int minMetaDealer;
    public int minCountish;
    public int maxCountish;

    public SimulationParameters(HouseRules hr, CountMethod cm, double cg, int mhpdcc, int mmd, int minC, int maxC){
        houseRules = hr;
        countMethod = cm;
        countGranularity = cg;
        minHitsPerDecisionCellCount = mhpdcc;
        minMetaDealer = mmd;
        minCountish = minC;
        maxCountish = maxC;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SimulationParameters that = (SimulationParameters) o;
        return Double.compare(that.countGranularity, countGranularity) == 0 && minHitsPerDecisionCellCount == that.minHitsPerDecisionCellCount && minMetaDealer == that.minMetaDealer && minCountish == that.minCountish && maxCountish == that.maxCountish && Objects.equals(houseRules, that.houseRules) && Objects.equals(countMethod, that.countMethod);
    }

    @Override
    public int hashCode() {
        return Objects.hash(houseRules, countMethod, countGranularity, minHitsPerDecisionCellCount, minMetaDealer, minCountish, maxCountish);
    }

    public BasicDBObject getDBObject(){
        BasicDBObject houseRulesObject = houseRules.getDBOject();
        BasicDBObject countMethodObject = countMethod.getDBObject();
        BasicDBObject simParamObject = new BasicDBObject("_id", this.hashCode());
        simParamObject.append("houseRules", houseRulesObject)
                .append("countMethod", countMethodObject)
                .append("countGranularity", countGranularity)
                .append("minHitsPerDecisionCellCount", minHitsPerDecisionCellCount)
                .append("minMetaDealer", minMetaDealer)
                .append("minCountish", minCountish)
                .append("maxCountish", maxCountish);

        return simParamObject;
    }

    /**
     * What the numbers of a table built under these parameters mean, as one string: the
     * house rules, the count method, the grain and the count range. A stored table can
     * only be added to by code whose key is the same.
     *
     * minHitsPerDecisionCellCount and minMetaDealer are left out on purpose. They say how
     * much evidence is enough, not what the evidence is, so raising them on an existing
     * table only asks it to keep going.
     *
     * It is built from getDBObject rather than listed by hand, so a rule added there is
     * covered without anyone remembering this method. equals cannot do this job: it
     * compares HouseRules and CountMethod by identity, so parameters never equal an
     * identical copy of themselves read back from the database.
     */
    public String getSemanticsKey(){
        BasicDBObject meaning = getDBObject();
        meaning.removeField("minHitsPerDecisionCellCount");
        meaning.removeField("minMetaDealer");
        return StoredState.keyOf(meaning);
    }

    public static SimulationParameters getSimParamFromObject(BasicDBObject simParamObject){
        HouseRules hr = HouseRules.getHouseRulesFromObject((BasicDBObject) simParamObject.get("houseRules"));
        CountMethod cm = CountMethod.getCountMethodFromObject((BasicDBObject) simParamObject.get("countMethod"));
        double cg = simParamObject.getDouble("countGranularity");
        int mhpdcc = simParamObject.getInt("minHitsPerDecisionCellCount");
        int mmd = simParamObject.getInt("minMetaDealer");
        int minCountish = simParamObject.getInt("minCountish");
        int maxCountish = simParamObject.getInt("maxCountish");

        return new SimulationParameters(hr, cm, cg, mhpdcc, mmd, minCountish, maxCountish);
    }
}
