import com.mongodb.*;
import org.bson.types.ObjectId;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.UnknownHostException;
import java.util.*;

public class SimulationTable {
    SimulationParameters simulationParameters;
    HashMap<HandSituation, DecisionCell> actionMap;
    String name;


    public SimulationTable(SimulationParameters sp, String n){
        simulationParameters = sp;
        actionMap = new HashMap<HandSituation, DecisionCell>();
        name = n;
    }

    public SimulationTable(SimulationParameters sp, HashMap<HandSituation, DecisionCell> am, String n){
        simulationParameters = sp;
        actionMap = am;
        name = n;
    }

    public void insertEvent(EventResult er){
        HandSituation hs = new HandSituation(er.playerHE, er.dealerRevealedCard.getRankpoints());
        if(!actionMap.containsKey(hs)){
            DecisionCell emptyDC = new DecisionCell();
            actionMap.put(hs, emptyDC);
        }
        else {
            DecisionCell dc = actionMap.get(hs);
            dc.insertEvent(er);
            actionMap.put(hs, dc);
        }
    }

    public EnumSet<PlayerMove> getKnownMovesWithPayoffs(HandSituation playerHS, GranularCount gc){
        DecisionCell dc = actionMap.get(playerHS);
        EnumSet<PlayerMove> knownMovesWithPayoffs = EnumSet.noneOf(PlayerMove.class);
        if(dc == null){
            return knownMovesWithPayoffs;
        }
        MoveChoices mc = dc.countToMoveChoice.get(gc);
        if(mc == null){
            return knownMovesWithPayoffs;
        }
        for(PlayerMove pm : mc.actionPayoffs.keySet()){
            knownMovesWithPayoffs.add(pm);
        }
        return knownMovesWithPayoffs;
    }

    public double getBestPlayerMovePayoff(HandSituation playerHS, GranularCount gc, EnumSet<PlayerMove> legalMoves){
        DecisionCell dc = actionMap.get(playerHS);
        //System.out.println(gc.units);
        if(!dc.countToMoveChoice.containsKey(gc)){
            int k=1;
        }
        return dc.getBestPlayerMovePayoff(gc, legalMoves);
    }

    public static void saveTable(SimulationTable simulationTable) throws UnknownHostException, InterruptedException {
        MongoClient mongoClient = new MongoClient();
        DB database = mongoClient.getDB("CardCounting");
        DBCollection collection = database.getCollection("SimulationTables");

        ObjectId nameID = new ObjectId(simulationTable.name);//?

        BasicDBObject tableObject = new BasicDBObject("_id", nameID);
        BasicDBObject simulationParameterObject = simulationTable.simulationParameters.getDBObject();

        BasicDBObject actionMapObject = new BasicDBObject();
        for(HandSituation orderedHS : HandSituation.getOrderedSituations()){
            for(HandSituation hs : simulationTable.actionMap.keySet()){
                if(orderedHS.equals(hs)) {
                    String keyAsString = hs.getStringFromEncoding();
                    DecisionCell dc = simulationTable.actionMap.get(hs);
                    BasicDBObject decisionCellObject = dc.getDBObject();
                    actionMapObject.append(keyAsString, decisionCellObject);
                }
            }
        }

        BasicDBObject query = new BasicDBObject();
        query.put("_id", nameID);

        System.out.println(simulationTable.name);
        System.out.println("about to remove table");
        collection.remove(query);

        Thread.sleep(2000);


        tableObject.append("simulationParameterObject", simulationParameterObject)
                .append("actionMapObject", actionMapObject);
        //collection.update(query, tableObject);
        try {
            collection.insert(tableObject);
            System.out.println("reinserted table part A");
        } finally {
            collection.update(query, tableObject);
            System.out.println("reinserted table part B");

        }




    }


    public static SimulationTable getTable(String name, SimulationTable emptySimTable) throws UnknownHostException {
        MongoClient mongoClient = new MongoClient();
        DB database = mongoClient.getDB("CardCounting");
        DBCollection collection = database.getCollection("SimulationTables");

        BasicDBObject query = new BasicDBObject();
        ObjectId nameID = new ObjectId(name);
        query.put("_id", nameID);
        BasicDBObject stObject = (BasicDBObject) collection.findOne(query);
        if(stObject == null){
            return emptySimTable;
        }
        BasicDBObject spObject = (BasicDBObject) stObject.get("simulationParameterObject");
        SimulationParameters sp = SimulationParameters.getSimParamFromObject(spObject);
        HashMap<HandSituation, DecisionCell> am = new HashMap<>();
        BasicDBObject amObject = (BasicDBObject) stObject.get("actionMapObject");
        for(String s : amObject.keySet()){
            HandSituation hs = HandSituation.getEncodingFromString(s);
            DecisionCell dc = DecisionCell.getDecisionCellFromObject((BasicDBObject) amObject.get(s));
            am.put(hs, dc);
        }


        return new SimulationTable(sp, am, name);
    }



    public ArrayList<String> printStylesAndHead() throws FileNotFoundException {
        Scanner s = new Scanner(new File("stuffForHTML/style.txt"));
        ArrayList<String> list = new ArrayList<String>();
        while (s.hasNextLine()){
            list.add(s.nextLine());
        }
        s.close();
        return list;
    }

    public ArrayList<String> printStartTableRow(String rowName){
        ArrayList<String> startTableRow = new ArrayList<>();
        startTableRow.add("</tr>");
        startTableRow.add("<td class=\"tg-0pky\">" + rowName + "</td>");
        for(int i=2; i<=10; i++){
            startTableRow.add("<td class=\"tg-0pky\">" + i + "</td>");
        }
        startTableRow.add("<td class=\"tg-0pky\">A</td>");
        startTableRow.add("</tr>");
        return startTableRow;
    }

    public void printAllTables() throws IOException{
        printHardCountTable();
        printSoftTable();;
        printSplitTable();
    }

    public void printHardCountTable() throws IOException {
        ArrayList<String> hctStrings = getHardCountTableStrings();
        String fileName =  "Hard" + name;
        printTable(hctStrings, fileName);
    }

    public void printSoftTable() throws IOException{
        ArrayList<String> sctStrings = getSoftTableStrings();
        String fileName =  "Soft" + name;
        printTable(sctStrings, fileName);
    }

    public void printSplitTable() throws IOException{
        ArrayList<String> sctStrings = getSplitTableStrings();
        String fileName =  "Split" + name;
        printTable(sctStrings, fileName);
    }

    public void  printTable(ArrayList<String> lines, String fileName) throws IOException {
        FileWriter writer = new FileWriter("stuffForHTML/" + fileName + ".html");
        for(String str: lines) {
            writer.write(str + System.lineSeparator());
        }
        writer.close();
    }

    public ArrayList<String> getHardCountTableStrings() throws FileNotFoundException {
        ArrayList<String> hardCountTable = new ArrayList<>();
        ArrayList<String> styles = printStylesAndHead();
        ArrayList<String> startTableRow = printStartTableRow("Hard");
        hardCountTable.addAll(styles);
        styles.add("<tbody>");
        hardCountTable.addAll(startTableRow);


        for(int i=5; i<=21; i++){
            hardCountTable.add("<tr>");
            hardCountTable.add("<td class=\"tg-0pky\">" + i + "</td>");
            System.out.println("aaa" + i);
            for(int j=2; j<=11; j++){
                HandEncoding he = new HandEncoding(false, false, i);
                HandSituation hs = new HandSituation(he, j);
                DecisionCell dc = actionMap.get(hs);
                String cellContent = dc.createStringCell();
                String cellColorTag = dc.getCellColorTag();
                String line = "<td class=\"tg-" + cellColorTag + "\">" + cellContent + "</td>";
                hardCountTable.add(line);
            }
            hardCountTable.add("</tr>");
        }
        return hardCountTable;
    }

    public ArrayList<String> getSoftTableStrings() throws FileNotFoundException {
        ArrayList<String> softTable = new ArrayList<>();
        ArrayList<String> styles = printStylesAndHead();
        ArrayList<String> startTableRow = printStartTableRow("Soft");
        softTable.addAll(styles);
        styles.add("<tbody>");
        softTable.addAll(startTableRow);


        for(int i=3; i<=11; i++){
            softTable.add("<tr>");
            int softCount = i + 10;
            softTable.add("<td class=\"tg-0pky\">" + softCount + "</td>");
            System.out.println("aaa" + i);
            for(int j=2; j<=11; j++){
                HandEncoding he = new HandEncoding(true, false, i);
                HandSituation hs = new HandSituation(he, j);
                DecisionCell dc = actionMap.get(hs);
                String cellContent = dc.createStringCell();
                String cellColorTag = dc.getCellColorTag();
                String line = "<td class=\"tg-" + cellColorTag + "\">" + cellContent + "</td>";
                softTable.add(line);
            }
            softTable.add("</tr>");
        }
        return softTable;
    }

    public ArrayList<String> getSplitTableStrings() throws FileNotFoundException {
        ArrayList<String> splitTable = new ArrayList<>();
        ArrayList<String> styles = printStylesAndHead();
        ArrayList<String> startTableRow = printStartTableRow("Split");
        splitTable.addAll(styles);
        styles.add("<tbody>");
        splitTable.addAll(startTableRow);


        for(int i=4; i<=20; i += 2){
            splitTable.add("<tr>");
            int handPart = i/2;
            splitTable.add("<td class=\"tg-0pky\">" + handPart + ", " + handPart + "</td>");
            System.out.println("aaa" + i);
            for(int j=2; j<=11; j++){
                HandEncoding he = new HandEncoding(false, true, i);
                HandSituation hs = new HandSituation(he, j);
                DecisionCell dc = actionMap.get(hs);
                String cellContent = dc.createStringCell();
                String cellColorTag = dc.getCellColorTag();
                String line = "<td class=\"tg-" + cellColorTag + "\">" + cellContent + "</td>";
                splitTable.add(line);
            }
            splitTable.add("</tr>");
        }
        splitTable.add("<tr>");
        splitTable.add("<td class=\"tg-0pky\">A, A</td>");
        System.out.println("aaa" + "A");
        for(int j=2; j<=11; j++){
            HandEncoding he = new HandEncoding(true, true, 2);
            HandSituation hs = new HandSituation(he, j);
            DecisionCell dc = actionMap.get(hs);
            String cellContent = dc.createStringCell();
            String cellColorTag = dc.getCellColorTag();
            String line = "<td class=\"tg-" + cellColorTag + "\">" + cellContent + "</td>";
            splitTable.add(line);
        }
        splitTable.add("</tr>");



        return splitTable;
    }


}
