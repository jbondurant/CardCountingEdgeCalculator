import java.io.IOException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.stream.Collectors;

public class Simulation {
    public Table table;
    public SimulationTable simulationTable;
    public String name;


    public static void main(String[] args) throws IOException, InterruptedException {

        //TODO select option
        //op1
        //runMetaSimulation();

        //op2
        //printTables();

        //op3
        runMetaPayoffFinderSim();
    }



    public static Simulation initializeSimulation() throws UnknownHostException {
        Simulation s = getSimulation2();
        return s;
    }

    public static String fixName(String ogName){
        String hexName = ogName.chars().mapToObj(c -> Integer.toHexString(c)).collect(Collectors.joining());
        String hexName24 = hexName;
        while(hexName24.length() < 24){
            hexName24 += "a";
        }
        return hexName24;
    }

    public static Simulation getSimulation1(){
        String namePreHex = "testTable1";
        String name = fixName(namePreHex);
        int penPercent = 75;
        int deckPrecision = 1;
        HouseRules houseRules = HouseRules.getMtlCasino25MinBlackjackParams(penPercent);
        CountMethod countMethod = CountMethod.getHiLoValue(deckPrecision);
        double countGranularity = 1.0;
        int minHitsPerDecisionCellCount = 250000;//move to at least 500
        int minMetaDealer = 250000;
        int minCountish = -0;//move perhaps to -5
        int maxCountish = 0;//move perhaps to 5
        SimulationParameters simulationParameters = new SimulationParameters(houseRules, countMethod, countGranularity, minHitsPerDecisionCellCount, minMetaDealer, minCountish, maxCountish);
        //actionmap??
        SimulationTable simulationTable = new SimulationTable(simulationParameters, name);
        Simulation simulation = new Simulation(simulationTable, name);
        return simulation;
    }

    public static Simulation getSimulation2(){
        String namePreHex = "testTable2";
        String name = fixName(namePreHex);
        int penPercent = 75;
        int deckPrecision = 1;
        HouseRules houseRules = HouseRules.getMtlCasino25MinBlackjackParams(penPercent);
        CountMethod countMethod = CountMethod.getHiLoValue(deckPrecision);
        double countGranularity = 1.0;
        int minHitsPerDecisionCellCount = 10000;//move to at least 500
        int minMetaDealer = 15000;
        int minCountish = -5;//move perhaps to -5
        int maxCountish = 5 ;//move perhaps to 5
        SimulationParameters simulationParameters = new SimulationParameters(houseRules, countMethod, countGranularity, minHitsPerDecisionCellCount, minMetaDealer, minCountish, maxCountish);
        //actionmap??
        SimulationTable simulationTable = new SimulationTable(simulationParameters, name);
        Simulation simulation = new Simulation(simulationTable, name);
        return simulation;
    }


    public static void runMetaSimulation() throws InterruptedException, UnknownHostException {
        Simulation simulation = initializeSimulation();


        int numMinutes = 10;
        int numTenMinutes = 0;
        for(int i=0; i<100; i++) {
            numTenMinutes++;
            simulation.runSimulation(numMinutes);
            Thread.sleep(10 * 1000);
            System.out.println("ran " + numTenMinutes + " ten minute sessions");
            System.out.println("num action map:\t" + simulation.simulationTable.actionMap.keySet().size());

        }
    }

    public static void printTables() throws IOException {
        Simulation simulation = initializeSimulation();
        String name = simulation.name;
        SimulationTable simulationTable = SimulationTable.getTable(name, new SimulationTable(null, null, ""));
        simulationTable.printAllTables();
    }

    public static void runMetaPayoffFinderSim() throws InterruptedException, UnknownHostException {
        Simulation simulation = initializeSimulation();
        int numMinutes = 1;
        int numOneMinutes = 0;
        for(int i=0; i<10; i++){
            numOneMinutes++;
            simulation.runPayoffFinderSim(numMinutes);
            Thread.sleep(10 * 1000);
            System.out.println("ran " + numOneMinutes + " one minute sessions");
        }
    }



    public Simulation(SimulationTable st, String n){
        simulationTable = st;
        table = new Table(st.simulationParameters.houseRules.numDecks, st.simulationParameters.countMethod);
        name = n;
    }

    public HandSituation handSituationToPlayV3(int minHitsPerDecisionCellCount, int minCountish, int maxCountish, double countPrecision){
        HashMap<HandSituation, DecisionCell> am = this.simulationTable.actionMap;
        ArrayList<HandSituation> orderedHS =  HandSituation.getOrderedSituations();

        HashSet<HandEncoding> initialEncodings = HandEncoding.getStartingEncodings();

        for(HandSituation hs : orderedHS){
            HandEncoding handEnc = hs.playerHE;
            if(!am.containsKey(hs)){
                return hs;
            }
            DecisionCell dc = am.get(hs);
            //this should work for using numGranCount since count is similar to a normal distribution
            if(dc.countToMoveChoice.keySet().size() < GranularCount.numGranCount(countPrecision, minCountish, maxCountish)){
                return hs;
            }
            for(GranularCount gc : dc.countToMoveChoice.keySet()){
                if(!gc.isCountInBoundaries(minCountish, maxCountish)){
                    continue;
                }
                MoveChoices mcs = dc.countToMoveChoice.get(gc);
                boolean oneMoveHitMaxNeededForCurrCount = false;
                for(PlayerMove pm : mcs.actionPayoffs.keySet()){
                    ActionPayoff ap = mcs.actionPayoffs.get(pm);
                    if (ap.numTimes >= minHitsPerDecisionCellCount) {
                        oneMoveHitMaxNeededForCurrCount = true;
                    }
                }
                if(!oneMoveHitMaxNeededForCurrCount){
                    return hs;
                }
            }
        }
        return null;
    }

    public void runPayoffFinderSim(int numMinutes) throws UnknownHostException, InterruptedException {
        Date start = new Date();
        Date end = new Date(start.getTime() + numMinutes * 60 * 1000);
        simulationTable = SimulationTable.getTable(name, this.simulationTable);
        SimulationParameters sp = simulationTable.simulationParameters;
        int minC = sp.minCountish;
        int maxC = sp.maxCountish;
        double countPrecision = sp.countGranularity;

        PayoffTable emptyPayoffTable = new PayoffTable(minC, maxC, countPrecision, name);
        PayoffTable payoffTable = PayoffTable.getTable(name, emptyPayoffTable);

        while((new Date()).before(end)){
            EventResult eventResult = runSingleSmartEvent(minC, maxC, countPrecision);
            GranularCount eventGC = eventResult.granularCount;
            if(!eventGC.isCountInBoundaries(minC, maxC)){
                continue;
            }

            payoffTable.insertEventSmart(eventResult);
        }
        System.out.println("Average payoff:\t" + payoffTable.getAveragePayoff());
        PayoffTable.saveTable(payoffTable);
    }


    //need to code it so that I can start with partial SimulationParameters
    public void runSimulation(int numMinutes) throws UnknownHostException, InterruptedException {
        Date start = new Date();
        Date end = new Date(start.getTime() + numMinutes * 60 * 1000);
        double oddsBestMove = 0.5;
        double oddsSecondBestMove = 0.3;
        simulationTable = SimulationTable.getTable(name, this.simulationTable);
        SimulationParameters sp = simulationTable.simulationParameters;

        HashMap<PlayerDealerBestScore, Outcome> outcomeFinder = PlayerDealerBestScore.initializeOutcomeFinderForTable(simulationTable.simulationParameters.houseRules);

        int minC = sp.minCountish;
        int maxC = sp.maxCountish;
        double countPrecision = sp.countGranularity;

        int minMetaDealer = sp.minMetaDealer;
        boolean dealerPeeksForBlackjack = sp.houseRules.dealerPeeksBlackjack;
        MetaDealer md = MetaDealer.getMetaDealer(name);
        while((!md.isCompleted(minMetaDealer, minC, maxC, countPrecision)) && (new Date()).before(end)){
            MetaDealerEventResult mdEventResult = runSingleMetaDealerEvent();
            GranularCount mdEventGC = mdEventResult.granularCount;
            mdEventGC.forceCountIntoBoundaries(minC, maxC);
            md.insertEvent(mdEventResult, dealerPeeksForBlackjack);
        }
        md.saveToDB();


        int mhpdcc = sp.minHitsPerDecisionCellCount;

        CompositeCardSource multiDeckExample = this.table.gameDeck.deepCopy();
        HashMap<HandEncoding, ArrayList<DoubleRanks>> hetdr = HandEncoding.handEncodingToDoubleRanks(multiDeckExample);
        ArrayList<TripleRanks> ah20ptr = HandEncoding.allPossibleTripleRanksForHard20(multiDeckExample);
        ArrayList<TripleRanks> ah21ptr = HandEncoding.allPossibleTripleRanksForHard21(multiDeckExample);
        ArrayList<TripleRanks> as21ptr = HandEncoding.allPossibleTripleRanksForSoft21(multiDeckExample);
        while(this.handSituationToPlayV3(mhpdcc, minC, maxC, countPrecision) != null && (new Date()).before(end)){
            EventResult eventResult = runSingleEvent(hetdr, ah20ptr, ah21ptr, as21ptr, outcomeFinder, md, oddsBestMove, oddsSecondBestMove);
            if(eventResult == null){
                continue;
            }
            GranularCount eventGC = eventResult.granularCount;
            if(!eventGC.isCountInBoundaries(minC, maxC)){
                //this makes a count of say 3 for [-3, 3] as a count of 3+.
                if(eventGC.getDoubleFromCount() < minC){
                    eventResult.granularCount = new GranularCount((double) minC);
                }
                else if(eventGC.getDoubleFromCount()> maxC){
                    eventResult.granularCount = new GranularCount((double) maxC);
                }

            }
            simulationTable.insertEvent(eventResult);




            /*
            if(numEvents % 10 == 0){
                HandEncoding playerHE = new HandEncoding(table.randomishPlayer.playerHands.get(0).handCards);
                System.out.println("num events:\t" + numEvents);
                if(eventResult.playedFirstMove.equals(PlayerMove.Stand)) {
                    System.out.println("Best score:\t" + playerHE.getBestScore());
                }
            }*/
        }
        SimulationTable.saveTable(simulationTable);
    }

    public EventResult runSingleSmartEvent(int minC, int maxC, double countPrecision) {
        HouseRules hr = simulationTable.simulationParameters.houseRules;
        table = new Table(hr.numDecks, simulationTable.simulationParameters.countMethod);
        setCardsSmart();

        HandEncoding playerHE = new HandEncoding(table.randomishPlayer.playerHands.playerHand.handCards);
        Rank dealerRevealedRank = table.dealer.revealedCards.get(0).rank;
        int deckSize = table.gameDeck.startingSize / hr.numDecks;

        GranularCount granularCount = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity, minC, maxC);

        HandSituation hs = new HandSituation(playerHE, dealerRevealedRank.getRankpoints());
        DecisionCell dc = simulationTable.actionMap.get(hs);
        MoveChoices mcs = dc.countToMoveChoice.get(granularCount);//TODO
        EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
        if(mcs == null){
            int k=1;
        }
        for(PlayerMove lm : mcs.actionPayoffs.keySet()){
            legalMoves.add(lm);
        }
        PlayerMove pm = mcs.getActionWithBestPayoff(legalMoves);

        //System.out.println("-----");
        double payoff = doPlayerMoveSmartAndGetPayoff(pm, table.randomishPlayer.playerHands);

        EventResult eventResult = new EventResult(payoff, playerHE, dealerRevealedRank, pm, granularCount);
        return eventResult;

    }

    public PlayerMove getBestPlayerMove(HandSituation hs, GranularCount gc, EnumSet<PlayerMove> legalMoves, int minC, int maxC){
        if(gc.getDoubleFromCount() < minC){
            gc = new GranularCount((double) minC);
        }
        else if(gc.getDoubleFromCount()> maxC){
            gc = new GranularCount((double) maxC);
        }
        DecisionCell dc = simulationTable.actionMap.get(hs);
        if(dc == null){
            return null;
        }
        MoveChoices mcs = dc.countToMoveChoice.get(gc);
        if(mcs == null){
            //System.out.println("hmm");
            return null;
        }
        PlayerMove pm = mcs.getActionWithBestPayoff(legalMoves);
        return pm;
    }

    public MetaDealerEventResult runSingleMetaDealerEvent(){
        HouseRules hr = simulationTable.simulationParameters.houseRules;
        table = new Table(hr.numDecks, simulationTable.simulationParameters.countMethod);
        setCardsSmart();

        int deckSize = table.gameDeck.startingSize / hr.numDecks;
        int minC = this.simulationTable.simulationParameters.minCountish;
        int maxC = this.simulationTable.simulationParameters.maxCountish;
        GranularCount granularCount = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity, minC, maxC);
        boolean hitsOnSoft17 = hr.hitsOnSoft17;
        table.dealerPlay(hitsOnSoft17);
        int dealerRevealedCardScore = table.dealer.revealedCards.get(0).rank.getRankpoints();
        boolean dealerHasBlackjack = table.dealer.dealerHasBlackjack();
        HandEncoding dealerHE = new HandEncoding(table.dealer.getDealerCards());
        int dealerBestScore = dealerHE.getBestScore();

        return new MetaDealerEventResult(granularCount, dealerBestScore, dealerHasBlackjack, dealerRevealedCardScore);
    }


    public EventResult runSingleEvent(HashMap<HandEncoding, ArrayList<DoubleRanks>> hetdr, ArrayList<TripleRanks> ah20ptr, ArrayList<TripleRanks> ah21ptr, ArrayList<TripleRanks> as21ptr, HashMap<PlayerDealerBestScore, Outcome> outcomeFinder, MetaDealer metaDealer, double oddsBestMove, double oddsSecondBestMove){
        HouseRules hr = simulationTable.simulationParameters.houseRules;
        table = new Table(hr.numDecks, simulationTable.simulationParameters.countMethod);
        setCards(hetdr, ah20ptr, ah21ptr, as21ptr);

        HandEncoding playerHE = new HandEncoding(table.randomishPlayer.playerHands.playerHand.handCards);

        //System.out.println(playerHE.getStringFromEncoding());
        boolean canDouble = table.randomishPlayer.playerHands.playerHand.handCards.size() == 2;
        boolean canSplit = playerHE.canSplit;
        boolean canSurrender = hr.canEarlySurrender || hr.canLateSurrender;
        boolean canHit = true;
        HandEncoding hard21Encoding = new HandEncoding(false, false, 21);
        HandEncoding hard20Encoding = new HandEncoding(false, false, 20);
        if(playerHE.equals(hard21Encoding) || playerHE.equals(hard20Encoding)){
            canHit = false;
        }

        Rank dealerRevealedRank = table.dealer.revealedCards.get(0).rank;
        int deckSize = table.gameDeck.startingSize / hr.numDecks;
        int minC = this.simulationTable.simulationParameters.minCountish;
        int maxC = this.simulationTable.simulationParameters.maxCountish;
        GranularCount granularCount = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity, minC, maxC);

        HandSituation playerHS = new HandSituation(playerHE, dealerRevealedRank.getRankpoints());
        EnumSet<PlayerMove> legalMoves = PlayerMove.getLegalMoves(canDouble, canSplit, canSurrender, canHit);
        PlayerMove bestMove = getBestPlayerMove(playerHS, granularCount, legalMoves, minC, maxC);
        PlayerMove secondBestMove = PlayerMove.Stand;
        if(bestMove == null){
            bestMove = PlayerMove.Stand;

        }
        else {
            legalMoves.remove(bestMove);
            secondBestMove = getBestPlayerMove(playerHS, granularCount, legalMoves, minC, maxC);
            if(secondBestMove == null){
                secondBestMove = PlayerMove.Stand;
            }
        }

        PlayerMove playerMove = PlayerMove.getEpsilonMove(canDouble, canSplit, canSurrender, canHit, bestMove, secondBestMove, oddsBestMove, oddsSecondBestMove);


        if(table.dealer.dealerHasBlackjack() && hr.dealerPeeksBlackjack){
            return null;
        }

        HandEncoding soft21 = new HandEncoding(true, false, 11);
        if(playerHE.equals(soft21) && playerMove.equals(PlayerMove.Stand)){
            int k = 1;
        }

        double payoff = doPlayerMoveAndGetPayoff(playerMove, table.randomishPlayer.playerHands, outcomeFinder, metaDealer);
        EventResult eventResult = new EventResult(payoff, playerHE, dealerRevealedRank, playerMove, granularCount);
        return eventResult;
    }

    public double doPlayerMoveSmartAndGetPayoff(PlayerMove pm, HandNode handNode){
        HouseRules hr = this.simulationTable.simulationParameters.houseRules;
        PlayerMove firstMove = pm;
        boolean hitsOnSoft17 = hr.hitsOnSoft17;
        int deckSize = table.gameDeck.startingSize / hr.numDecks;
        CountMethod countMethod = this.simulationTable.simulationParameters.countMethod;
        int maxSplitsAces = hr.numSplitsAces;
        int maxSplitsNotAces = hr.numSplitsNotAces;
        int minC = this.simulationTable.simulationParameters.minCountish;
        int maxC = this.simulationTable.simulationParameters.maxCountish;

        if(hr.dealerPeeksBlackjack){
            if(this.table.dealer.dealerHasBlackjack()){
                if(!this.table.randomishPlayer.playerHasBlackjack()){
                    return -1.0;
                }
                //return 0.0;
                //todo june 3 remove this
                return 0.00001;
            }
        }

        if(firstMove.equals(PlayerMove.Stand)){
            table.dealerPlay(hitsOnSoft17);
            Outcome outcome = PlayerDealerBestScore.playerOutcomeVsDealerOld(table.randomishPlayer, handNode, this.table.dealer, hr, true);
            return Outcome.outcomePayoff(outcome, hr.blackjackPayout);
        }
        else if(firstMove.equals(PlayerMove.Hit)){
            //i'll need a while loop and i'll need to remove double from my nex possible moves
            Card c = table.gameDeck.cards.remove(0);
            //table.runningCount += countMethod.rankToCount.get(c.rank);
            handNode.playerHand.handCards.add(c);

            while(true) {
                HandEncoding playerHE = new HandEncoding(handNode.playerHand.handCards);
                int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();
                HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
                if (playerHE.isBusted()) {
                    return -1.0;
                }
                GranularCount gc = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity, minC, maxC);
                EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
                legalMoves.add(PlayerMove.Hit);
                legalMoves.add(PlayerMove.Stand);
                if (hr.canEarlySurrender || hr.canLateSurrender) {
                    legalMoves.add(PlayerMove.Surrender);
                }

                PlayerMove bestMove = getBestPlayerMove(playerHS, gc, legalMoves, minC, maxC);
                if(bestMove.equals(PlayerMove.Surrender)){
                    return -0.5;
                }
                else if(bestMove.equals(PlayerMove.Stand)){
                    table.dealerPlay(hitsOnSoft17);
                    Outcome outcome = PlayerDealerBestScore.playerOutcomeVsDealerOld(table.randomishPlayer, handNode, this.table.dealer, hr, true);
                    return Outcome.outcomePayoff(outcome, hr.blackjackPayout);
                }
                // best move is hit
                Card cNext = table.gameDeck.cards.remove(0);
                //table.runningCount += countMethod.rankToCount.get(cNext.rank);
                handNode.playerHand.handCards.add(cNext);
            }
        }
        else if(firstMove.equals(PlayerMove.Double)){
            handNode.playerHand.handCards.add(table.gameDeck.cards.remove(0));
            HandEncoding playerHE = new HandEncoding(handNode.playerHand.handCards);
            if(playerHE.isBusted()){
                return -2.0;
            }

            table.dealerPlay(hitsOnSoft17);
            Outcome outcome = PlayerDealerBestScore.playerOutcomeVsDealerOld(table.randomishPlayer, handNode, this.table.dealer, hr, true);
            return 2.0 * Outcome.outcomePayoff(outcome, hr.blackjackPayout);
        }


        else {
            //Split

            EnumSet<Rank> ranksThatCanBeDoubledDownAfterSplit = hr.ranksThatCanBeDoubledDownAfterSplit;
            Rank rank = handNode.playerHand.handCards.get(0).rank;
            boolean cantSplitAces = rank.equals(Rank.ACE) && table.randomishPlayer.playerHands.getNumActualNodes() > maxSplitsAces;
            boolean cantSplitNotAces = (!rank.equals(Rank.ACE)) && table.randomishPlayer.playerHands.getNumActualNodes() > maxSplitsNotAces;

            int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();

            if (cantSplitAces || cantSplitNotAces) {
                return playBestSmartNotSplit(handNode);
            }

            Card phV1c1 = handNode.playerHand.handCards.get(0);
            Card phV2c1 = handNode.playerHand.handCards.get(1);

            Card phV1c2 = table.gameDeck.cards.remove(0);
            table.runningCount += countMethod.rankToCount.get(phV1c2.rank);
            Card phV2c2 = table.gameDeck.cards.remove(0);
            table.runningCount += countMethod.rankToCount.get(phV2c2.rank);

            handNode.leftChildHandNode = HandNode.createHand(phV1c1, phV1c2);
            handNode.rightChildHandNode = HandNode.createHand(phV2c1, phV2c2);
            handNode.playerHand = null;


            boolean cantSplitLeft = phV1c1.rank.getRankpoints() != phV1c2.rank.getRankpoints();
            boolean cantSplitRight = phV2c1.rank.getRankpoints() != phV2c2.rank.getRankpoints();

           double leftPayoff = 0.0;
           if(cantSplitLeft){
               leftPayoff = playBestSmartNotSplit(handNode.leftChildHandNode);
           }
           else{
               leftPayoff = doPlayerMoveSmartAndGetPayoff(PlayerMove.Split, handNode.leftChildHandNode);
           }
           double rightPayoff = 0.0;
           if(cantSplitRight){
               rightPayoff = playBestSmartNotSplit(handNode.rightChildHandNode);
           }
           else{
               rightPayoff = doPlayerMoveSmartAndGetPayoff(PlayerMove.Split, handNode.rightChildHandNode);
           }
            double totalPayoff = leftPayoff + rightPayoff;
            return totalPayoff;
        }
    }


    public double doPlayerMoveAndGetPayoff(PlayerMove pm, HandNode handNode, HashMap<PlayerDealerBestScore, Outcome> outcomeFinder, MetaDealer metaDealer){
        HouseRules hr = this.simulationTable.simulationParameters.houseRules;
        PlayerMove firstMove = pm;
        boolean hitsOnSoft17 = hr.hitsOnSoft17;
        int deckSize = table.gameDeck.startingSize / hr.numDecks;
        double countGranularity = simulationTable.simulationParameters.countGranularity;
        CountMethod countMethod = simulationTable.simulationParameters.countMethod;
        int maxSplitsAces = hr.numSplitsAces;
        int maxSplitsNotAces = hr.numSplitsNotAces;
        int minC = this.simulationTable.simulationParameters.minCountish;
        int maxC = this.simulationTable.simulationParameters.maxCountish;

        if(firstMove.equals(PlayerMove.Stand)){
            GranularCount gcKey = table.getGranularCount(deckSize, countGranularity, minC, maxC);
            int dKey = table.dealer.revealedCards.get(0).rank.getRankpoints();
            GranularCountAndDealerUpCard gcadup = new GranularCountAndDealerUpCard(gcKey, dKey);
            MetaDealerResult mdr = metaDealer.dealerCountAndUpCardToResults.get(gcadup);
            HandEncoding playerHE = new HandEncoding(handNode.playerHand.handCards);
            int playerBestScore = playerHE.getBestScore();
            boolean playerHasBlackjack = table.randomishPlayer.playerHasBlackjack();
            boolean dealerHasBlackjack = false;
            return PlayerDealerBestScore.getPlayerPayoff(outcomeFinder, mdr, playerBestScore, hr.blackjackPayout, playerHasBlackjack, dealerHasBlackjack);
        }
        else if(firstMove.equals(PlayerMove.Hit)){
            Card c = table.gameDeck.cards.remove(0);
            table.runningCount += countMethod.rankToCount.get(c.rank);
            handNode.playerHand.handCards.add(c);

            HandEncoding playerHE = new HandEncoding(handNode.playerHand.handCards);
            int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();
            HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
            if(playerHE.isBusted()){
                return -1.0;
            }
            GranularCount gc = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity, minC, maxC);
            EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
            legalMoves.add(PlayerMove.Hit);
            legalMoves.add(PlayerMove.Stand);
            if(hr.canEarlySurrender || hr.canLateSurrender){
                legalMoves.add(PlayerMove.Surrender);
            }
            double nextMoveAveragePayoff = getBestPlayerMovePayoff(playerHS, gc, legalMoves);
            return nextMoveAveragePayoff;
        }
        else if(firstMove.equals(PlayerMove.Double)){
            handNode.playerHand.handCards.add(table.gameDeck.cards.remove(0));
            HandEncoding playerHE = new HandEncoding(handNode.playerHand.handCards);
            int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();
            HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
            if(playerHE.isBusted()){
                return -2.0;
            }
            GranularCount gc = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity, minC, maxC);
            EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
            legalMoves.add(PlayerMove.Stand);
            if(hr.canEarlySurrender || hr.canLateSurrender){
                legalMoves.add(PlayerMove.Surrender);
            }
            double nextMoveAveragePayoffDouble = 2.0 * getBestPlayerMovePayoff(playerHS, gc, legalMoves);
            return nextMoveAveragePayoffDouble;
        }
        else{
            //Split

            Rank rank = handNode.playerHand.handCards.get(0).rank;
            boolean cantSplitAces = rank.equals(Rank.ACE) && table.randomishPlayer.playerHands.getNumActualNodes() > maxSplitsAces;
            boolean cantSplitNotAces = (!rank.equals(Rank.ACE)) && table.randomishPlayer.playerHands.getNumActualNodes() > maxSplitsNotAces;

            int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();

            if(cantSplitAces || cantSplitNotAces){
                return playBestNotSplit(handNode);
            }

            Card phV1c1 = handNode.playerHand.handCards.get(0);
            Card phV2c1 = handNode.playerHand.handCards.get(1);

            Card phV1c2 = table.gameDeck.cards.remove(0);
            table.runningCount += countMethod.rankToCount.get(phV1c2.rank);
            Card phV2c2 = table.gameDeck.cards.remove(0);
            table.runningCount += countMethod.rankToCount.get(phV2c2.rank);

            handNode.leftChildHandNode = HandNode.createHand(phV1c1, phV1c2);
            handNode.rightChildHandNode = HandNode.createHand(phV2c1, phV2c2);
            handNode.playerHand = null;


            boolean cantSplitLeft = phV1c1.rank.getRankpoints() != phV1c2.rank.getRankpoints();
            boolean cantSplitRight = phV2c1.rank.getRankpoints() != phV2c2.rank.getRankpoints();

            double leftPayoff = 0.0;
            if(cantSplitLeft){
                leftPayoff = playBestNotSplit(handNode.leftChildHandNode);
            }
            else {
                leftPayoff = doPlayerMoveAndGetPayoff(PlayerMove.Split, handNode.leftChildHandNode, outcomeFinder, metaDealer);
            }
            double rightPayoff = 0.0;
            if(cantSplitRight){
                rightPayoff = playBestNotSplit(handNode.rightChildHandNode);
            }
            else {
                rightPayoff = doPlayerMoveAndGetPayoff(PlayerMove.Split, handNode.rightChildHandNode, outcomeFinder, metaDealer);
            }
            double totalPayoff = leftPayoff + rightPayoff;
            return totalPayoff;

        }
    }





    public double getBestPlayerMovePayoff(HandSituation playerHS, GranularCount gc, EnumSet<PlayerMove> legalMoves){
        return simulationTable.getBestPlayerMovePayoff(playerHS, gc, legalMoves);
    }

    public void setCardsSmart(){
        SimulationParameters sp = this.simulationTable.simulationParameters;
        int penPercent = sp.houseRules.penetrationPercentage;
        int minC = sp.minCountish;
        int maxC = sp.maxCountish;
        double countPrecision = sp.countGranularity;
        //todo june 3 attempt
        this.table.givePlayer2RandomCardsAndRunCountMaybe(false);
        this.table.giveDealerRandomHandAndRunCountMaybe(false);
        this.table.removeRandomAmountCardsAndRunCountSmart(penPercent);
    }

    public void setCards(HashMap<HandEncoding, ArrayList<DoubleRanks>> hetdr, ArrayList<TripleRanks> ah20ptr, ArrayList<TripleRanks> ah21ptr, ArrayList<TripleRanks> as21ptr){
        SimulationParameters sp = this.simulationTable.simulationParameters;
        int penPercent = sp.houseRules.penetrationPercentage;
        int mhpdcc = sp.minHitsPerDecisionCellCount;
        int minC = sp.minCountish;
        int maxC = sp.maxCountish;
        double countPrecision = sp.countGranularity;
        int deckSize = table.gameDeck.startingSize / this.simulationTable.simulationParameters.houseRules.numDecks;

        HandSituation hs = this.handSituationToPlayV3(mhpdcc, minC, maxC, countPrecision);
        HandEncoding he = hs.playerHE;
        HashSet initEncodedHands = HandEncoding.getStartingEncodings();
        HandEncoding soft21HE = new HandEncoding(true, false, 11);
        if(initEncodedHands.contains(he) || he.equals(soft21HE)){
            if(soft21HE.equals(he)){
                int k = 1;
            }
            this.table.givePlayer3CardsThatFitHandEncodingAndCountMaybe(he, ah20ptr, ah21ptr, as21ptr, false);
        }
        else{
            this.table.givePlayer2CardsThatFitHandEncodingAndCountMaybe(he, hetdr, false);
        }
        this.table.giveDealerHandAndRunCountMaybe(hs.dealerRankVal, false);

        GranularCount randomCount = GranularCount.getRandomCount(minC, maxC, countPrecision);
        this.table.removeRandomAmountCardsAndRunCount(penPercent, randomCount, deckSize, countPrecision, minC, maxC);
    }

    public double playBestSmartNotSplit(HandNode handNode){
        int minC = this.simulationTable.simulationParameters.minCountish;
        int maxC = this.simulationTable.simulationParameters.maxCountish;
        HouseRules hr = this.simulationTable.simulationParameters.houseRules;
        int deckSize = table.gameDeck.startingSize / hr.numDecks;
        int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();

        HandEncoding playerHE = new HandEncoding(handNode.playerHand.handCards);
        Rank rank = handNode.playerHand.handCards.get(0).rank;
        HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
        GranularCount gc = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity, minC, maxC);

        EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
        legalMoves.add(PlayerMove.Stand);
        if((!rank.equals(Rank.ACE)) || hr.canHitAfterSplittingAces){
            legalMoves.add(PlayerMove.Hit);
        }
        if (hr.canEarlySurrender || hr.canLateSurrender) {
            legalMoves.add(PlayerMove.Surrender);
        }
        if (hr.ranksThatCanBeDoubledDownAfterSplit.contains(rank)) {
            legalMoves.add(PlayerMove.Double);
        }
        legalMoves.remove(PlayerMove.Split);



        PlayerMove bestOtherMove = getBestPlayerMove(playerHS, gc, legalMoves, minC, maxC);
        return doPlayerMoveSmartAndGetPayoff(bestOtherMove, handNode);
    }

    public double playBestNotSplit(HandNode handNode){
        int minC = this.simulationTable.simulationParameters.minCountish;
        int maxC = this.simulationTable.simulationParameters.maxCountish;
        HouseRules hr = this.simulationTable.simulationParameters.houseRules;
        int deckSize = table.gameDeck.startingSize / hr.numDecks;
        int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();

        HandEncoding playerHE = new HandEncoding(handNode.playerHand.handCards);
        Rank rank = handNode.playerHand.handCards.get(0).rank;
        HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
        GranularCount gc = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity, minC, maxC);

        EnumSet<PlayerMove> knownMovesWithPayoffs = this.simulationTable.getKnownMovesWithPayoffs(playerHS, gc);
        EnumSet<PlayerMove> legalMoves = knownMovesWithPayoffs;
        legalMoves.remove(PlayerMove.Split);
        if((rank.equals(Rank.ACE)) && (!hr.canHitAfterSplittingAces)) {
            legalMoves.remove(PlayerMove.Hit);
        }
        if((!hr.canEarlySurrender) && (!hr.canLateSurrender)){
            legalMoves.remove(PlayerMove.Surrender);
        }
        if(!hr.ranksThatCanBeDoubledDownAfterSplit.contains(rank)) {
            legalMoves.remove(PlayerMove.Double);
        }
        if(legalMoves.size() == 0){
            return 0.0;//not optimal but ok should not matter with many simulations
        }
        double nextMoveAveragePayoff = getBestPlayerMovePayoff(playerHS, gc, legalMoves);
        return nextMoveAveragePayoff;
    }


}
