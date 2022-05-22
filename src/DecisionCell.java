import com.mongodb.BasicDBObject;

import java.util.*;

public class DecisionCell {
    HashMap<GranularCount, MoveChoices> countToMoveChoice;

    public DecisionCell(){
        countToMoveChoice = new HashMap<GranularCount, MoveChoices>();
    }

    public int numGranCount(double countPrecision, int minC, int maxC){
        double interval = (double) (maxC - minC);
        return (int) (interval / countPrecision + 1.0);
    }

    public BasicDBObject getDBObject(){
        BasicDBObject decisionCellObject = new BasicDBObject();
        for(GranularCount gc : countToMoveChoice.keySet()) {
            String granCountString = gc.getStringFromCount();
            MoveChoices mc = countToMoveChoice.get(gc);
            BasicDBObject moveChoiceObject = mc.getDBObject();
            decisionCellObject.append(granCountString, moveChoiceObject);
        }
        return decisionCellObject;
    }

    public void insertEvent(EventResult er){
        if(countToMoveChoice.containsKey(er.granularCount)){
            MoveChoices mc = countToMoveChoice.get(er.granularCount);
            mc.insertEvent(er);
            countToMoveChoice.put(er.granularCount, mc);
        }
        else{
            MoveChoices mc = new MoveChoices();
            mc.insertEvent(er);
            countToMoveChoice.put(er.granularCount, mc);
        }

    }

    public double getBestPlayerMovePayoff(GranularCount gc, EnumSet<PlayerMove> legalMoves){
        MoveChoices mcs = countToMoveChoice.get(gc);
        return mcs.getPayoffOfActionWithBestPayoff(legalMoves);
    }

    //is there a problem where one of the keys is _id and the value is the hashcode.
    //I think it will make my hashmap wonky, but not cause any problems for a small personal project like this

    //TODO fix wrong
    public static DecisionCell getDecisionCellFromObject(BasicDBObject decisionCellObject){
        HashMap<GranularCount, MoveChoices> ctmc = new HashMap<>();
        for(String s : decisionCellObject.keySet()){
            GranularCount gc = GranularCount.getCountFromString(s);
            MoveChoices mc = MoveChoices.getMoveCountFromObject((BasicDBObject) decisionCellObject.get(s));
            ctmc.put(gc, mc);
        }
        DecisionCell dc = new DecisionCell();
        dc.countToMoveChoice = ctmc;
        return dc;
    }

    public String getCellColorTag(){
        GranularCount zeroCount = new GranularCount(0,0,0);
        MoveChoices mcs = countToMoveChoice.get(zeroCount);
        String bestCompoundMove = mcs.getCompoundBestMove();
        return bestCompoundMove;
    }


    public String createStringCell(){
        ArrayList<String> allLines = new ArrayList<>();
        HashSet<GranularCount> allCounts = new HashSet<>();
        for(GranularCount key : countToMoveChoice.keySet()){
            allCounts.add(key);
        }
        ArrayList<GranularCount> allCountsList = new ArrayList<>();
        allCountsList.addAll(allCounts);
        Collections.sort(allCountsList);

        GranularCount startIntervalCount = allCountsList.get(0);
        MoveChoices mcs = countToMoveChoice.get(startIntervalCount);
        String lastMove = mcs.getCompoundBestMove();

        for(int i=1; i<allCountsList.size(); i++){//start at 1
            GranularCount currCount = allCountsList.get(i);
            MoveChoices currMCS = countToMoveChoice.get(currCount);
            String currMove = currMCS.getCompoundBestMove();

            if(!currMove.equals(lastMove)){
                GranularCount endIntervalCount = allCountsList.get(i-1);
                String countInterval = "[" + startIntervalCount.countToCellString() + ", " + endIntervalCount.countToCellString() + "]";
                String line = countInterval + " do " + lastMove;
                allLines.add(line);

                startIntervalCount = currCount;
                lastMove = currMove;
            }
            if(i == allCountsList.size() - 1){
                GranularCount endIntervalCount = allCountsList.get(i);
                String countInterval = "[" + startIntervalCount.countToCellString() + ", " + endIntervalCount.countToCellString() + "]";
                String line = countInterval + " do " + lastMove;
                allLines.add(line);
            }
        }

        String cellString = "";
        for(int i=0; i<allLines.size(); i++){
            String line = allLines.get(i);
            if(i != allLines.size()-1){
                cellString += line + "<br>";
            }
            else{
                cellString += line;
            }

        }
        return cellString;
    }
}
