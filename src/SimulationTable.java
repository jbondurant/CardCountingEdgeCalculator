import com.mongodb.*;
import org.bson.BasicBSONEncoder;
import org.bson.types.ObjectId;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class SimulationTable {
    SimulationParameters simulationParameters;
    HashMap<HandSituation, DecisionCell> actionMap;
    String name;
    /**
     * What the stored document said about the code and the rules it was built under, or
     * null for a table that has never been stored.
     */
    StoredState storedState;


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

    /**
     * Record one observation.
     *
     * The first event for a situation used to create the cell and then return without
     * inserting, so every (hand, up-card) pair silently threw away its first result.
     */
    public void insertEvent(EventResult er){
        HandSituation hs = new HandSituation(er.playerHE, er.dealerRevealedCard.getRankpoints());
        DecisionCell dc = actionMap.get(hs);
        if(dc == null){
            dc = new DecisionCell();
            actionMap.put(hs, dc);
        }
        dc.insertEvent(er);
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

    /**
     * The measured payoff of the best legal move here. The caller records what comes back,
     * so there is no honest answer when the cell has not been solved.
     */
    public double getBestPlayerMovePayoff(HandSituation playerHS, GranularCount gc, EnumSet<PlayerMove> legalMoves){
        DecisionCell dc = actionMap.get(playerHS);
        if(dc == null){
            throw new UnsolvedCellException("no cell for " + playerHS.getStringFromEncoding()
                    + ", needed at true count " + gc.countToCellString());
        }
        try {
            return dc.getBestPlayerMovePayoff(gc, legalMoves);
        } catch (UnsolvedCellException e) {
            throw new UnsolvedCellException(playerHS.getStringFromEncoding() + ": " + e.getMessage());
        }
    }

    public static void saveTable(SimulationTable simulationTable) throws UnknownHostException, InterruptedException {
        MongoClient mongoClient = new MongoClient();
        try {
            DB database = mongoClient.getDB("CardCounting");
            DBCollection collection = database.getCollection("SimulationTables");

            BasicDBObject tableObject = simulationTable.getDBObject();

            BasicDBObject query = new BasicDBObject();
            query.put("_id", tableObject.get("_id"));

            // One upsert, so the stored table is replaced in a single write and is never
            // absent. This used to remove the document and then insert a new one, which left
            // a window where a table representing hours of simulation did not exist, and put
            // the reinsert in a finally block: on success it wrote everything twice, and on
            // failure it updated a document that had just been deleted, matching nothing.
            collection.update(query, tableObject, true, false);
        } finally {
            mongoClient.close();
        }
    }

    /**
     * The document saveTable writes, stamped with the semantics version and the key of the
     * parameters, so that a later run can tell whether it means the same by these numbers.
     */
    public BasicDBObject getDBObject(){
        ObjectId nameID = new ObjectId(name);//?

        BasicDBObject tableObject = new BasicDBObject("_id", nameID);
        BasicDBObject simulationParameterObject = simulationParameters.getDBObject();

        BasicDBObject actionMapObject = new BasicDBObject();
        for(HandSituation orderedHS : HandSituation.getOrderedSituations()){
            for(HandSituation hs : actionMap.keySet()){
                if(orderedHS.equals(hs)) {
                    String keyAsString = hs.getStringFromEncoding();
                    DecisionCell dc = actionMap.get(hs);
                    BasicDBObject decisionCellObject = dc.getDBObject();
                    actionMapObject.append(keyAsString, decisionCellObject);
                }
            }
        }

        tableObject.append("simulationParameterObject", simulationParameterObject)
                .append("actionMapObject", actionMapObject);
        StoredState.stamp(tableObject, simulationParameters.getSemanticsKey());
        return tableObject;
    }


    /**
     * The most bytes saveTable could write for a finished table at these parameters.
     *
     * Every situation gets a cell at every bucket, holding every move the house rules
     * could let it record there, each with its average written out to the full hundred
     * decimal places insertEvent keeps. The three-card hands, hard 20, hard 21 and soft
     * 21, get every move a two-card hand gets, which is more than the run offers them, so
     * this comes out a little larger than any real table. That is the side to err on for
     * a limit. The document is the one getDBObject builds for saveTable, and the driver's
     * own encoder measures it, so the figure follows any change to what a cell stores.
     */
    static int largestEncodedSize(SimulationParameters sp, List<GranularCount> buckets){
        // An average of a third never terminates, so insertEvent writes it out to its full
        // scale with a sign in front, which is the longest string a cell holds.
        ActionPayoff widest = new ActionPayoff();
        widest.insertEventSmart(-1.0);
        widest.insertEventSmart(0.0);
        widest.insertEventSmart(0.0);

        HouseRules hr = sp.houseRules;
        boolean canSurrender = hr.canEarlySurrender || hr.canLateSurrender;
        SimulationTable table = new SimulationTable(sp, "000000000000000000000000");
        for(HandSituation hs : HandSituation.getOrderedSituations()){
            MoveChoices mc = new MoveChoices();
            for(PlayerMove pm : PlayerMove.getLegalMoves(true, hs.playerHE.canSplit, canSurrender, true)){
                mc.actionPayoffs.put(pm, widest);
            }
            DecisionCell dc = new DecisionCell();
            for(GranularCount gc : buckets){
                dc.countToMoveChoice.put(gc, mc);
            }
            table.actionMap.put(hs, dc);
        }
        return new BasicBSONEncoder().encode(table.getDBObject()).length;
    }

    public static SimulationTable getTable(String name, SimulationTable emptySimTable) throws UnknownHostException {
        MongoClient mongoClient = new MongoClient();
        try {
            DB database = mongoClient.getDB("CardCounting");
            DBCollection collection = database.getCollection("SimulationTables");

            BasicDBObject query = new BasicDBObject();
            ObjectId nameID = new ObjectId(name);
            query.put("_id", nameID);
            BasicDBObject stObject = (BasicDBObject) collection.findOne(query);
            if(stObject == null){
                return emptySimTable;
            }
            return fromDBObject(stObject, name);
        } finally {
            mongoClient.close();
        }
    }

    /**
     * Read a stored table back, along with what its document says about how it was built.
     *
     * It comes back with the parameters it was stored under, which is what printTables
     * wants. A run that means to add to it calls resumeUnder first.
     */
    public static SimulationTable fromDBObject(BasicDBObject stObject, String name){
        BasicDBObject spObject = (BasicDBObject) stObject.get("simulationParameterObject");
        SimulationParameters sp = SimulationParameters.getSimParamFromObject(spObject);
        HashMap<HandSituation, DecisionCell> am = new HashMap<>();
        BasicDBObject amObject = (BasicDBObject) stObject.get("actionMapObject");
        for(String s : amObject.keySet()){
            HandSituation hs = HandSituation.getEncodingFromString(s);
            DecisionCell dc = DecisionCell.getDecisionCellFromObject((BasicDBObject) amObject.get(s));
            am.put(hs, dc);
        }

        SimulationTable st = new SimulationTable(sp, am, name);
        st.storedState = StoredState.readFrom(stObject);
        return st;
    }

    /**
     * Get a table ready to be added to by the running code, or refuse.
     *
     * getTable used to hand back the stored parameters in place of the code's without a
     * word, so a rule changed in getSimulation2 never reached a table that already
     * existed: the run went on building the old game, and nothing said so. A stored
     * table now has to mean by its numbers what the code would, and then it runs
     * under the code's parameters, which can differ from the stored ones only in the
     * progress thresholds. A table that was never stored has nothing to check.
     */
    public void resumeUnder(SimulationParameters codeParameters){
        if(storedState != null){
            storedState.refuseToResumeUnlessMeaning(
                    codeParameters.getSemanticsKey(), "SimulationTables", name);
        }
        simulationParameters = codeParameters;
    }

    /**
     * A digest of the strategy this table plays: every situation, every count bucket, and
     * the compound best move there, as the printed tables show it.
     *
     * A PayoffTable records this, because its average is the edge of one strategy only if
     * every hand in it was played by that strategy. More data can move a best move, and
     * the next payoff run would then average hands played two ways. Data that leaves every
     * best move where it was leaves the digest alone.
     */
    public String getStrategyFingerprint(){
        ArrayList<String> lines = new ArrayList<>();
        for(HandSituation hs : actionMap.keySet()){
            DecisionCell dc = actionMap.get(hs);
            for(GranularCount gc : dc.countToMoveChoice.keySet()){
                lines.add(hs.getStringFromEncoding() + " " + gc.getStringFromCount() + " "
                        + dc.countToMoveChoice.get(gc).getCompoundBestMove());
            }
        }
        Collections.sort(lines);
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for(byte b : digest){
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException(e);
        }
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

    /**
     * One cell of a rendered table.
     *
     * The table fills in dependency order, hard 21 first, so while a run is going most
     * situations have no cell yet. Each builder used to call through whatever the lookup
     * returned, and the first missing situation stopped the render with a
     * NullPointerException. A situation not reached yet is rendered the way a cell with
     * nothing at count zero already is: empty, and marked unmeasured.
     */
    String getCellLine(HandSituation hs){
        DecisionCell dc = actionMap.get(hs);
        if(dc == null){
            dc = new DecisionCell();
        }
        return "<td class=\"tg-" + dc.getCellColorTag() + "\">" + dc.createStringCell() + "</td>";
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
        // Was appended to `styles` after that list had already been copied, so the tag
        // never reached the generated file.
        hardCountTable.add("<tbody>");
        hardCountTable.addAll(startTableRow);


        for(int i=5; i<=21; i++){
            hardCountTable.add("<tr>");
            hardCountTable.add("<td class=\"tg-0pky\">" + i + "</td>");
            System.out.println("aaa" + i);
            for(int j=2; j<=11; j++){
                HandEncoding he = new HandEncoding(false, false, i);
                HandSituation hs = new HandSituation(he, j);
                String line = getCellLine(hs);
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
        // Was appended to `styles` after that list had already been copied, so the tag
        // never reached the generated file.
        softTable.add("<tbody>");
        softTable.addAll(startTableRow);


        for(int i=3; i<=11; i++){
            softTable.add("<tr>");
            int softCount = i + 10;
            softTable.add("<td class=\"tg-0pky\">" + softCount + "</td>");
            System.out.println("aaa" + i);
            for(int j=2; j<=11; j++){
                HandEncoding he = new HandEncoding(true, false, i);
                HandSituation hs = new HandSituation(he, j);
                String line = getCellLine(hs);
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
        // Was appended to `styles` after that list had already been copied, so the tag
        // never reached the generated file.
        splitTable.add("<tbody>");
        splitTable.addAll(startTableRow);


        for(int i=4; i<=20; i += 2){
            splitTable.add("<tr>");
            int handPart = i/2;
            splitTable.add("<td class=\"tg-0pky\">" + handPart + ", " + handPart + "</td>");
            System.out.println("aaa" + i);
            for(int j=2; j<=11; j++){
                HandEncoding he = new HandEncoding(false, true, i);
                HandSituation hs = new HandSituation(he, j);
                String line = getCellLine(hs);
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
            String line = getCellLine(hs);
            splitTable.add(line);
        }
        splitTable.add("</tr>");



        return splitTable;
    }


}
