import com.mongodb.BasicDBObject;

import java.util.*;

public class DecisionCell {
    HashMap<GranularCount, MoveChoices> countToMoveChoice;

    public DecisionCell(){
        countToMoveChoice = new HashMap<GranularCount, MoveChoices>();
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
        if(mcs == null){
            throw new UnsolvedCellException("nothing measured at true count "
                    + gc.countToCellString());
        }
        return mcs.getPayoffOfActionWithBestPayoff(legalMoves);
    }

    /**
     * Read a cell back from the database.
     *
     * This carried "TODO fix wrong", and an older note worrying that an _id key would end
     * up in the map. Neither applies: getDBObject writes only count keys, one per bucket,
     * and PersistenceRoundTripTest checks that a cell serialises and deserialises to the
     * same buckets.
     */
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

    /**
     * The CSS class a rendered cell is coloured by, taken from the move at a true count
     * of zero.
     *
     * Nothing guarantees a cell has a bucket at exactly zero. A finished table will, but
     * printTables is worth running against a partly built one, and this used to
     * dereference the missing bucket. An uncoloured cell is the honest rendering of a
     * count that has not been reached yet.
     */
    public String getCellColorTag(){
        GranularCount zeroCount = new GranularCount(0,0,0);
        MoveChoices mcs = countToMoveChoice.get(zeroCount);
        if(mcs == null){
            return "unmeasured";
        }
        return mcs.getCompoundBestMove();
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
