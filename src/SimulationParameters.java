import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.connection.ServerDescription;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

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
        refuseACountGridTheRunCannotUse();
    }

    /**
     * Refuse a count grid the table run could never finish, or could not save.
     *
     * The run's buckets are stepped from minC by the grain: getRandomCount picks its
     * targets that way, and numGranCount, which MetaDealer.isCompleted and
     * handSituationToPlayV3 wait on, counts them that way. A true count rounds to a
     * multiple of the grain counted from zero and is then clamped to the bounds, so the
     * two agree only when the grain divides both bounds. When it does not, most targets
     * sit between the values a count can round to. Even when it does, a grain finer than
     * a running count over the decks left can resolve leaves buckets nothing lands on. In
     * both cases removeRandomAmountCardsAndRunCount, which reshuffles until it lands on
     * the bucket it asked for, never returns, and the dealer table waits on buckets it
     * will never fill.
     *
     * saveTable writes the whole table as one document, and MongoDB refuses a document
     * over 16 MB. Every bucket adds a cell to each of the 360 situations, so a fine enough
     * grid fails at the save, after the session's work is done, and the work is lost.
     *
     * All of these used to surface hours into a run, or as a run that quietly made no
     * progress. Refusing them here costs a moment at construction.
     */
    private void refuseACountGridTheRunCannotUse(){
        long step = Math.round(countGranularity * 100);
        if(!(countGranularity > 0) || step < 1 || Math.abs(countGranularity * 100 - step) > 1e-6){
            throw new IllegalArgumentException("countGranularity must be a positive whole number"
                    + " of hundredths, since a count is held to two decimal places; got "
                    + countGranularity);
        }
        if(minCountish > maxCountish){
            throw new IllegalArgumentException("minCountish " + minCountish
                    + " is above maxCountish " + maxCountish + ", so the grid has no buckets");
        }
        if((minCountish * 100L) % step != 0 || (maxCountish * 100L) % step != 0){
            throw new IllegalArgumentException("countGranularity " + countGranularity
                    + " does not divide the bounds " + minCountish + " and " + maxCountish
                    + ". The run's buckets are stepped from " + minCountish + " by the grain,"
                    + " but a true count rounds to a multiple of the grain counted from zero,"
                    + " so the table run would ask for buckets it can never land on and"
                    + " reshuffle forever");
        }

        List<GranularCount> grid = new ArrayList<>();
        for(long h = minCountish * 100L; h <= maxCountish * 100L; h += step){
            grid.add(new GranularCount(0, 0, (int) h));
        }

        TreeSet<GranularCount> reachable = Table.reachableBuckets(
                houseRules, countMethod, countGranularity, minCountish, maxCountish);
        List<String> unreachable = new ArrayList<>();
        for(GranularCount gc : grid){
            if(!reachable.contains(gc)){
                unreachable.add(gc.countToCellString());
            }
        }
        if(!unreachable.isEmpty()){
            throw new IllegalArgumentException("countGranularity " + countGranularity + " over ["
                    + minCountish + ", " + maxCountish + "] has " + unreachable.size() + " of its "
                    + grid.size() + " buckets that no deal can land on, with "
                    + houseRules.numDecks + " decks at " + houseRules.penetrationPercentage
                    + "% penetration: " + String.join(", ", unreachable) + ". The table run"
                    + " reshuffles until the true count lands on the bucket it asked for, so it"
                    + " would never return");
        }

        int size = SimulationTable.largestEncodedSize(this, grid);
        int limit = ServerDescription.getDefaultMaxDocumentSize();
        if(size > limit){
            throw new IllegalArgumentException(String.format(Locale.ROOT, "countGranularity %s"
                    + " over [%d, %d] makes a finished table of up to %,d bytes, and MongoDB"
                    + " refuses a document over %,d. saveTable writes the whole table as one"
                    + " document, so the save would fail after a session's work was done and"
                    + " the work would be lost. Use a coarser grain or a narrower range",
                    countGranularity, minCountish, maxCountish, size, limit));
        }
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
