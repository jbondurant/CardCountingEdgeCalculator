import com.mongodb.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.UnknownHostException;
import java.util.ArrayList;

public class ActionPayoff {
    public int numTimes;
    public double avPayoff;
    public BigDecimal avPayoffPrecise;
    public int numPlayerBlackjacks;
    public double playerBlackjackPercentage;

    public ActionPayoff(){
        numTimes = 0;
        avPayoff = 0;
        avPayoffPrecise = BigDecimal.ZERO;
        numPlayerBlackjacks = 0;
        double playerBlackjackPercentage;
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

    public void insertEventSmart(double payoff){
        BigDecimal multipliedAverage = avPayoffPrecise.multiply(BigDecimal.valueOf(numTimes));
        BigDecimal multAvWithEvent = multipliedAverage.add(BigDecimal.valueOf(payoff));
        numTimes++;
        BigDecimal updatedAverage = multAvWithEvent.divide(BigDecimal.valueOf(numTimes), 100, RoundingMode.FLOOR);
        avPayoffPrecise = updatedAverage;
        avPayoff = updatedAverage.doubleValue();
        if(payoff == 1.5 || payoff == 0.00001){//not optimal, but will do for now
            numPlayerBlackjacks++;
        }
        playerBlackjackPercentage = (numPlayerBlackjacks * 100.0) / (numTimes * 1.0);
    }



    public BasicDBObject getDBObject(){
        return new BasicDBObject("_id", this.hashCode())
                .append("numTimes", numTimes)
                .append("avPayoff", avPayoff)
                .append("avPayoffPrecise", avPayoffPrecise.toString())
                .append("playerBlackjackPercentage", playerBlackjackPercentage)
                .append("numPlayerBlackjacks", numPlayerBlackjacks);
    }



    public static ActionPayoff getActionPayoffFromObject(BasicDBObject actionPayoffObject){
        int nt = actionPayoffObject.getInt("numTimes");
        double ap = actionPayoffObject.getDouble("avPayoff");
        BigDecimal app = new BigDecimal(actionPayoffObject.getString("avPayoffPrecise"));
        ActionPayoff actionPayoff =  new ActionPayoff(nt, ap, app);

        if(actionPayoffObject.containsKey("numPlayerBlackjacks")) {
            int npbj = actionPayoffObject.getInt("numPlayerBlackjacks");
            actionPayoff.numPlayerBlackjacks = npbj;
        }
        if(actionPayoffObject.containsKey("playerBlackjackPercentage")){
            double pbjp = actionPayoffObject.getDouble("playerBlackjackPercentage");
            actionPayoff.playerBlackjackPercentage = pbjp;
        }
        return actionPayoff;

    }


}
