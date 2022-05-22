import com.mongodb.BasicDBObject;

import java.math.BigDecimal;
import java.util.Objects;

public class CountPayoff implements Comparable<CountPayoff>{
    public GranularCount granularCount;
    public ActionPayoff actionPayoff;

    public CountPayoff(GranularCount gc){
        granularCount = gc;
        actionPayoff = new ActionPayoff();
    }

    public BasicDBObject getDBObject(){
        BasicDBObject countPayoffObject = new BasicDBObject();
        String gcString = granularCount.getStringFromCount();
        BasicDBObject apObject = actionPayoff.getDBObject();
        countPayoffObject.append("gcString", gcString)
                .append("apObject", apObject);
        return countPayoffObject;
    }

    public static CountPayoff getFromObject(BasicDBObject countPayoffObject){
        String s = countPayoffObject.getString("gcString");
        GranularCount gc = GranularCount.getCountFromString(s);
        ActionPayoff ap = ActionPayoff.getActionPayoffFromObject((BasicDBObject) countPayoffObject.get("apObject"));
        CountPayoff cp = new CountPayoff(gc);
        cp.actionPayoff = ap;
        return cp;
    }


    @Override
    public int compareTo(CountPayoff cp2) {
        return granularCount.compareTo(cp2.granularCount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CountPayoff that = (CountPayoff) o;
        return Objects.equals(granularCount, that.granularCount) && Objects.equals(actionPayoff, that.actionPayoff);
    }

    @Override
    public int hashCode() {
        return Objects.hash(granularCount, actionPayoff);
    }
}
