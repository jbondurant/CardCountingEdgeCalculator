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

    public static Simulation initializeSimulation(){
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
        int minHitsPerDecisionCellCountA = 3000;//move to at least 500
        int minHitsPerDecisionCellCountB = 3000;
        int minCountish = -4;//move perhaps to -5
        int maxCountish = 4;//move perhaps to 5
        SimulationParameters simulationParameters = new SimulationParameters(houseRules, countMethod, countGranularity, minHitsPerDecisionCellCountA, minHitsPerDecisionCellCountB, minCountish, maxCountish);
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
        int minHitsPerDecisionCellCountA = 2000;//move to at least 500
        int minHitsPerDecisionCellCountB = 2000;
        int minCountish = -4;//move perhaps to -5
        int maxCountish = 4;//move perhaps to 5
        SimulationParameters simulationParameters = new SimulationParameters(houseRules, countMethod, countGranularity, minHitsPerDecisionCellCountA, minHitsPerDecisionCellCountB, minCountish, maxCountish);
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
        int numMinutes = 3;
        int numThreeMinutes = 0;
        for(int i=0; i<100; i++){
            numThreeMinutes++;
            simulation.runPayoffFinderSim(numMinutes);
            Thread.sleep(10 * 1000);
            System.out.println("ran " + numThreeMinutes + " three minute sessions");
        }
    }



    public Simulation(SimulationTable st, String n){
        simulationTable = st;
        table = new Table(st.simulationParameters.houseRules.numDecks, st.simulationParameters.countMethod);
        name = n;
    }

    public HandSituation handSituationToPlayV3(int minHitsPerDecisionCellCountA, int minHitsPerDecisionCellCountB, int minCountish, int maxCountish, double countPrecision){
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
            if(dc.countToMoveChoice.keySet().size() < dc.numGranCount(countPrecision, minCountish, maxCountish)){
                return hs;
            }
            for(GranularCount gc : dc.countToMoveChoice.keySet()){
                if(!gc.isCountInBoundaries(minCountish, maxCountish)){
                    continue;
                }
                MoveChoices mcs = dc.countToMoveChoice.get(gc);
                for(PlayerMove pm : mcs.actionPayoffs.keySet()){
                    ActionPayoff ap = mcs.actionPayoffs.get(pm);
                    if(initialEncodings.contains(handEnc)) {
                        if (ap.numTimes < minHitsPerDecisionCellCountA) {
                            return hs;
                        }
                    }
                    else{
                        if (ap.numTimes < minHitsPerDecisionCellCountB) {
                            return hs;
                        }
                    }
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

            payoffTable.insertEvent(eventResult);
        }
        System.out.println("Average payoff:\t" + payoffTable.getAveragePayoff());
        PayoffTable.saveTable(payoffTable);
    }



    //need to code it so that I can start with partial SimulationParameters
    public void runSimulation(int numMinutes) throws UnknownHostException, InterruptedException {
        Date start = new Date();
        Date end = new Date(start.getTime() + numMinutes * 60 * 1000);
        simulationTable = SimulationTable.getTable(name, this.simulationTable);
        SimulationParameters sp = simulationTable.simulationParameters;
        int mhpdcca = sp.minHitsPerDecisionCellCountA;
        int mhpdccb = sp.minHitsPerDecisionCellCountB;
        int minC = sp.minCountish;
        int maxC = sp.maxCountish;
        double countPrecision = sp.countGranularity;

        CompositeCardSource multiDeckExample = this.table.gameDeck.deepCopy();
        HashMap<HandEncoding, ArrayList<DoubleRanks>> hetdr = HandEncoding.handEncodingToDoubleRanks(multiDeckExample);
        ArrayList<TripleRanks> ah20ptr = HandEncoding.allPossibleTripleRanksForHard20(multiDeckExample);
        ArrayList<TripleRanks> ah21ptr = HandEncoding.allPossibleTripleRanksForHard21(multiDeckExample);

        while(this.handSituationToPlayV3(mhpdcca, mhpdccb, minC, maxC, countPrecision) != null && (new Date()).before(end)){
            EventResult eventResult = runSingleEvent(hetdr, ah20ptr, ah21ptr);
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
            if(eventResult.payoff > -3.0){
                simulationTable.insertEvent(eventResult);
            }



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

        HandEncoding playerHE = new HandEncoding(table.randomishPlayer.playerHands.get(0).handCards);
        Rank dealerRevealedRank = table.dealer.revealedCards.get(0).rank;
        int deckSize = table.gameDeck.startingSize / hr.numDecks;

        GranularCount granularCount = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity);
        if(granularCount.getDoubleFromCount() < minC){
            granularCount = new GranularCount((double) minC);
        }
        else if(granularCount.getDoubleFromCount()> maxC){
            granularCount = new GranularCount((double) maxC);
        }

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

        double payoff = doPlayerMoveSmartAndGetPayoff(pm, 0);

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
            int k = 1;
        }
        MoveChoices mcs = dc.countToMoveChoice.get(gc);
        PlayerMove pm = mcs.getActionWithBestPayoff(legalMoves);
        return pm;
    }



    public EventResult runSingleEvent(HashMap<HandEncoding, ArrayList<DoubleRanks>> hetdr, ArrayList<TripleRanks> ah20ptr, ArrayList<TripleRanks> ah21ptr){
        HouseRules hr = simulationTable.simulationParameters.houseRules;
        table = new Table(hr.numDecks, simulationTable.simulationParameters.countMethod);
        setCards(hetdr, ah20ptr, ah21ptr);

        HandEncoding playerHE = new HandEncoding(table.randomishPlayer.playerHands.get(0).handCards);
        boolean canDouble = table.randomishPlayer.playerHands.get(0).handCards.size() == 2;
        boolean canSplit = playerHE.canSplit;
        boolean canSurrender = hr.canEarlySurrender || hr.canLateSurrender;
        boolean canHit = true;
        HandEncoding hard21Encoding = new HandEncoding(false, false, 21);
        HandEncoding hard20Encoding = new HandEncoding(false, false, 20);
        if(playerHE.equals(hard21Encoding) || playerHE.equals(hard20Encoding)){
            canHit = false;
        }



        PlayerMove playerMove = PlayerMove.getMove(canDouble, canSplit, canSurrender, canHit);

        Rank dealerRevealedRank = table.dealer.revealedCards.get(0).rank;
        int deckSize = table.gameDeck.startingSize / hr.numDecks;
        GranularCount granularCount = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity);

        double payoff = doPlayerMoveAndGetPayoff(playerMove, 0);

        EventResult eventResult = new EventResult(payoff, playerHE, dealerRevealedRank, playerMove, granularCount);

        return eventResult;
    }

    public double doPlayerMoveSmartAndGetPayoff(PlayerMove pm, int handNumber){
        HouseRules hr = this.simulationTable.simulationParameters.houseRules;
        PlayerMove firstMove = pm;
        boolean hitsOnSoft17 = hr.hitsOnSoft17;
        int deckSize = table.gameDeck.startingSize / hr.numDecks;
        CountMethod countMethod = this.simulationTable.simulationParameters.countMethod;
        int maxSplitsAces = hr.numSplitsAces;
        int maxSplitsNotAces = hr.numSplitsNotAces;
        int minC = this.simulationTable.simulationParameters.minCountish;
        int maxC = this.simulationTable.simulationParameters.maxCountish;

        //TODO may 21 dealer peek
        if(hr.dealerPeeksBlackjack){
            if(this.table.dealer.dealerHasBlackjack()){
                if(!this.table.randomishPlayer.playerHasBlackjack()){
                    return -1.0;
                }
                return 0.0;
            }
        }

        if(firstMove.equals(PlayerMove.Stand)){
            table.dealerPlay(hitsOnSoft17);
            Outcome outcome = playerOutcomeVsDealer(this.table.randomishPlayer, handNumber, this.table.dealer, hr.dealerPeeksBlackjack, hr.pushOnDealerHard22, hr.player21AlwaysWins, true);
            return outcomePayoff(outcome, hr.blackjackPayout);
        }
        else if(firstMove.equals(PlayerMove.Hit)){
            //i'll need a while loop and i'll need to remove double from my nex possible moves
            Card c = table.gameDeck.cards.remove(0);
            //table.runningCount += countMethod.rankToCount.get(c.rank);
            table.randomishPlayer.playerHands.get(handNumber).handCards.add(c);

            while(true) {
                HandEncoding playerHE = new HandEncoding(table.randomishPlayer.playerHands.get(handNumber).handCards);
                int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();
                HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
                if (playerHE.isBusted()) {
                    return -1.0;
                }
                GranularCount gc = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity);
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
                    Outcome outcome = playerOutcomeVsDealer(this.table.randomishPlayer, handNumber, this.table.dealer, hr.dealerPeeksBlackjack, hr.pushOnDealerHard22, hr.player21AlwaysWins, true);
                    return outcomePayoff(outcome, hr.blackjackPayout);
                }
                // best move is hit
                Card cNext = table.gameDeck.cards.remove(0);
                //table.runningCount += countMethod.rankToCount.get(cNext.rank);
                table.randomishPlayer.playerHands.get(handNumber).handCards.add(cNext);
            }
        }
        else if(firstMove.equals(PlayerMove.Double)){
            table.randomishPlayer.playerHands.get(handNumber).handCards.add(table.gameDeck.cards.remove(0));
            HandEncoding playerHE = new HandEncoding(table.randomishPlayer.playerHands.get(handNumber).handCards);
            int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();
            HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
            if(playerHE.isBusted()){
                return -2.0;
            }

            table.dealerPlay(hitsOnSoft17);
            Outcome outcome = playerOutcomeVsDealer(this.table.randomishPlayer, handNumber, this.table.dealer, hr.dealerPeeksBlackjack, hr.pushOnDealerHard22, hr.player21AlwaysWins, true);
            return 2.0 * outcomePayoff(outcome, hr.blackjackPayout);
        }


        else {
            //Split
            EnumSet<Rank> ranksThatCanBeDoubledDownAfterSplit = hr.ranksThatCanBeDoubledDownAfterSplit;
            Rank rank = table.randomishPlayer.playerHands.get(handNumber).handCards.get(0).rank;
            boolean cantSplitAces = rank.equals(Rank.ACE) && table.randomishPlayer.playerHands.size() > maxSplitsAces;
            boolean cantSplitNotAces = (!rank.equals(Rank.ACE)) && table.randomishPlayer.playerHands.size() > maxSplitsNotAces;

            if (cantSplitAces || cantSplitNotAces) {
                HandEncoding playerHE = new HandEncoding(table.randomishPlayer.playerHands.get(handNumber).handCards);
                int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();
                HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
                GranularCount gc = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity);
                EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
                legalMoves.add(PlayerMove.Stand);
                if(hr.canHitAfterSplittingAces) {
                    legalMoves.add(PlayerMove.Hit);
                }
                if (hr.canEarlySurrender || hr.canLateSurrender) {
                    legalMoves.add(PlayerMove.Surrender);
                }
                if (ranksThatCanBeDoubledDownAfterSplit.contains(rank)) {
                    legalMoves.add(PlayerMove.Double);
                }
                //System.out.println("maxSplit");

                EnumSet<PlayerMove> knownMovesWithPayoffs = this.simulationTable.getKnownMovesWithPayoffs(playerHS, gc);
                knownMovesWithPayoffs.remove(PlayerMove.Split);
                legalMoves = knownMovesWithPayoffs;

                PlayerMove bestOtherMove = getBestPlayerMove(playerHS, gc, legalMoves, minC, maxC);
                return doPlayerMoveSmartAndGetPayoff(bestOtherMove, handNumber);
            } else {
                //System.out.println("not max split");
            }
            Card phV1c1 = table.randomishPlayer.playerHands.get(handNumber).handCards.get(0);
            Card phV2c1 = table.randomishPlayer.playerHands.get(handNumber).handCards.remove(1);

            Card phV1c2 = table.gameDeck.cards.remove(0);
            table.runningCount += countMethod.rankToCount.get(phV1c2.rank);
            table.randomishPlayer.playerHands.get(handNumber).handCards.add(phV1c2);


            double splitLeftPayoff = 0.0;
            double getLeftPayoff = 0.0;
            if (phV1c2.rank.getRankpoints() == rank.getRankpoints()) {
                splitLeftPayoff = doPlayerMoveSmartAndGetPayoff(PlayerMove.Split, handNumber);
            } else {
                HandEncoding playerHE = new HandEncoding(table.randomishPlayer.playerHands.get(handNumber).handCards);
                int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();
                HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
                GranularCount gc = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity);
                EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
                legalMoves.add(PlayerMove.Stand);
                if(hr.canHitAfterSplittingAces) {
                    legalMoves.add(PlayerMove.Hit);
                }
                if (hr.canEarlySurrender || hr.canLateSurrender) {
                    legalMoves.add(PlayerMove.Surrender);
                }
                if (ranksThatCanBeDoubledDownAfterSplit.contains(rank)) {
                    legalMoves.add(PlayerMove.Double);
                }
                if(playerHS.playerHE.hardCount > 21){
                    System.out.println("aaaaa");
                    int k2 = 1;
                }
                PlayerMove bestLeftPlayerMove = getBestPlayerMove(playerHS, gc, legalMoves, minC, maxC);
                getLeftPayoff = doPlayerMoveSmartAndGetPayoff(bestLeftPlayerMove, handNumber);
            }
            double leftPayoff = splitLeftPayoff + getLeftPayoff;

            //right payoff

            PlayerHand phNext = new PlayerHand(new ArrayList<Card>());
            phNext.handCards.add(phV2c1);
            table.randomishPlayer.playerHands.add(phNext);

            Card phV2c2 = table.gameDeck.cards.remove(0);
            table.runningCount += countMethod.rankToCount.get(phV2c2.rank);
            phNext.handCards.add(phV2c2);

            double splitRightPayoff = 0.0;
            double getRightPayoff = 0.0;
            if (phV2c2.rank.getRankpoints() == rank.getRankpoints()) {
                splitRightPayoff = doPlayerMoveSmartAndGetPayoff(PlayerMove.Split, table.randomishPlayer.playerHands.size() - 1);
            } else {
                HandEncoding playerHE = new HandEncoding(table.randomishPlayer.playerHands.get(table.randomishPlayer.playerHands.size() - 1).handCards);
                int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();
                HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
                GranularCount gc = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity);
                EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
                legalMoves.add(PlayerMove.Stand);
                if(hr.canHitAfterSplittingAces) {
                    legalMoves.add(PlayerMove.Hit);
                }
                if (hr.canEarlySurrender || hr.canLateSurrender) {
                    legalMoves.add(PlayerMove.Surrender);
                }
                if (ranksThatCanBeDoubledDownAfterSplit.contains(rank)) {
                    legalMoves.add(PlayerMove.Double);
                }
                if(playerHS.playerHE.hardCount > 21){
                    int k = 1;
                }
                PlayerMove bestRightPlayerMove = getBestPlayerMove(playerHS, gc, legalMoves, minC, maxC);
                getRightPayoff = doPlayerMoveSmartAndGetPayoff(bestRightPlayerMove, table.randomishPlayer.playerHands.size() - 1);
            }
            double rightPayoff = splitRightPayoff + getRightPayoff;

            double totalPayoff = leftPayoff + rightPayoff;
            return totalPayoff;
        }
    }

    //if move is stand i wanna get payoff by comparing with dealer
    //if move is hit i want to get payoff by looking up table
        //but what if its the initial hands? then it's covered by itBusted
    //if move is double i want to get payoff by looking up table
    //if move is split i want to get payoff by looking up table
    public double doPlayerMoveAndGetPayoff(PlayerMove pm, int handNumber){
        HouseRules hr = this.simulationTable.simulationParameters.houseRules;
        PlayerMove firstMove = pm;
        boolean hitsOnSoft17 = hr.hitsOnSoft17;
        int deckSize = table.gameDeck.startingSize / hr.numDecks;
        CountMethod countMethod = this.simulationTable.simulationParameters.countMethod;
        int maxSplitsAces = hr.numSplitsAces;
        int maxSplitsNotAces = hr.numSplitsNotAces;
        int minC = this.simulationTable.simulationParameters.minCountish;
        int maxC = this.simulationTable.simulationParameters.maxCountish;

        //TODO may 21 dealer peek
        if(hr.dealerPeeksBlackjack){
            if(this.table.dealer.dealerHasBlackjack()){
                return -5.0;
                /*if(!this.table.randomishPlayer.playerHasBlackjack()){
                    return -1.0;
                }
                return 0.0;*/
            }
        }


        if(firstMove.equals(PlayerMove.Stand)){
            table.dealerPlay(hitsOnSoft17);
            Outcome outcome = playerOutcomeVsDealer(this.table.randomishPlayer, handNumber, this.table.dealer, hr.dealerPeeksBlackjack, hr.pushOnDealerHard22, hr.player21AlwaysWins, false);
            return outcomePayoff(outcome, hr.blackjackPayout);
        }
        else if(firstMove.equals(PlayerMove.Hit)){
            Card c = table.gameDeck.cards.remove(0);
            table.runningCount += countMethod.rankToCount.get(c.rank);
            table.randomishPlayer.playerHands.get(handNumber).handCards.add(c);

            HandEncoding playerHE = new HandEncoding(table.randomishPlayer.playerHands.get(handNumber).handCards);
            int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();
            HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
            if(playerHE.isBusted()){
                return -1.0;
            }
            GranularCount gc = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity);
            EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
            legalMoves.add(PlayerMove.Hit);
            legalMoves.add(PlayerMove.Stand);
            if(hr.canEarlySurrender || hr.canLateSurrender){
                legalMoves.add(PlayerMove.Surrender);
            }
            double nextMoveAveragePayoff = getBestPlayerMovePayoff(playerHS, gc, legalMoves, minC, maxC);
            return nextMoveAveragePayoff;
        }
        else if(firstMove.equals(PlayerMove.Double)){
            //this doesn't need ot affect count since no further decision
            table.randomishPlayer.playerHands.get(handNumber).handCards.add(table.gameDeck.cards.remove(0));
            HandEncoding playerHE = new HandEncoding(table.randomishPlayer.playerHands.get(handNumber).handCards);
            int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();
            HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
            if(playerHE.isBusted()){
                return -2.0;
            }
            GranularCount gc = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity);
            EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
            legalMoves.add(PlayerMove.Stand);
            if(hr.canEarlySurrender || hr.canLateSurrender){
                legalMoves.add(PlayerMove.Surrender);
            }
            double nextMoveAveragePayoffDouble = 2.0 * getBestPlayerMovePayoff(playerHS, gc, legalMoves, minC, maxC);
            return nextMoveAveragePayoffDouble;
        }
        else{
            //Split
            EnumSet<Rank> ranksThatCanBeDoubledDownAfterSplit = hr.ranksThatCanBeDoubledDownAfterSplit;
            Rank rank = table.randomishPlayer.playerHands.get(handNumber).handCards.get(0).rank;
            boolean cantSplitAces = rank.equals(Rank.ACE) && table.randomishPlayer.playerHands.size() > maxSplitsAces;
            boolean cantSplitNotAces = (!rank.equals(Rank.ACE)) && table.randomishPlayer.playerHands.size() > maxSplitsNotAces;

            if(cantSplitAces || cantSplitNotAces){
                HandEncoding playerHE = new HandEncoding(table.randomishPlayer.playerHands.get(handNumber).handCards);
                int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();
                HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
                GranularCount gc = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity);
                EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
                legalMoves.add(PlayerMove.Stand);
                if(hr.canHitAfterSplittingAces) {
                    legalMoves.add(PlayerMove.Hit);
                }
                if(hr.canEarlySurrender || hr.canLateSurrender){
                    legalMoves.add(PlayerMove.Surrender);
                }
                if(ranksThatCanBeDoubledDownAfterSplit.contains(rank)){
                    legalMoves.add(PlayerMove.Double);
                }
                //System.out.println("maxSplit");


                //i think i need to convert my hand into a non split,
                // but this gets tricky for two aces or , two 2s,
                //perhaps i need to just play this hand and not look up a table
                //no none of these are right, I just need to look up the best player move payoff that's not a split
                // there's a small chance (medium chance for aces) that the first time we split, we split to the max
                // in that case i could either randomly play (kinda sucks)
                // or make entire event void (kinda bad, but can't be too statistically important)
                //or make last split a push (seems decent)
                EnumSet<PlayerMove> knownMovesWithPayoffs = this.simulationTable.getKnownMovesWithPayoffs(playerHS, gc);
                knownMovesWithPayoffs.remove(PlayerMove.Split); //check this works todo
                if(knownMovesWithPayoffs.size() == 0){
                    return 0.0;
                }
                legalMoves = knownMovesWithPayoffs;

                double nextMoveAveragePayoff = getBestPlayerMovePayoff(playerHS, gc, legalMoves, minC, maxC);
                return nextMoveAveragePayoff;
            }
            else{
                //System.out.println("not max split");
                int k=1;
            }
            Card phV1c1 = table.randomishPlayer.playerHands.get(handNumber).handCards.get(0);
            Card phV2c1 = table.randomishPlayer.playerHands.get(handNumber).handCards.remove(1);

            Card phV1c2 = table.gameDeck.cards.remove(0);
            table.runningCount += countMethod.rankToCount.get(phV1c2.rank);
            table.randomishPlayer.playerHands.get(handNumber).handCards.add(phV1c2);


            double splitLeftPayoff = 0.0;
            double getLeftPayoff = 0.0;
            if(phV1c2.rank.getRankpoints() == rank.getRankpoints()){
                //split again
                 splitLeftPayoff = doPlayerMoveAndGetPayoff(PlayerMove.Split, handNumber);
            }
            else{
                HandEncoding playerHE = new HandEncoding(table.randomishPlayer.playerHands.get(handNumber).handCards);
                int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();
                HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
                GranularCount gc = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity);
                EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
                legalMoves.add(PlayerMove.Stand);
                if(hr.canHitAfterSplittingAces) {
                    legalMoves.add(PlayerMove.Hit);
                }
                if(hr.canEarlySurrender || hr.canLateSurrender){
                    legalMoves.add(PlayerMove.Surrender);
                }
                if(ranksThatCanBeDoubledDownAfterSplit.contains(rank)){
                    legalMoves.add(PlayerMove.Double);
                }
                getLeftPayoff = getBestPlayerMovePayoff(playerHS, gc, legalMoves, minC, maxC);
            }
            double leftPayoff = splitLeftPayoff + getLeftPayoff;

            //right payoff

            PlayerHand phNext = new PlayerHand(new ArrayList<Card>());
            phNext.handCards.add(phV2c1);
            table.randomishPlayer.playerHands.add(phNext);

            Card phV2c2 = table.gameDeck.cards.remove(0);
            table.runningCount += countMethod.rankToCount.get(phV2c2.rank);
            phNext.handCards.add(phV2c2);

            double splitRightPayoff = 0.0;
            double getRightPayoff = 0.0;
            if(phV2c2.rank.getRankpoints() == rank.getRankpoints()){
                splitRightPayoff = doPlayerMoveAndGetPayoff(PlayerMove.Split, table.randomishPlayer.playerHands.size() - 1);
            }
            else{
                HandEncoding playerHE = new HandEncoding(table.randomishPlayer.playerHands.get(table.randomishPlayer.playerHands.size() - 1).handCards);
                int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();
                HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
                GranularCount gc = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity);
                EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
                legalMoves.add(PlayerMove.Stand);
                if(hr.canHitAfterSplittingAces) {
                    legalMoves.add(PlayerMove.Hit);
                }
                if(hr.canEarlySurrender || hr.canLateSurrender){
                    legalMoves.add(PlayerMove.Surrender);
                }
                if(ranksThatCanBeDoubledDownAfterSplit.contains(rank)){
                    legalMoves.add(PlayerMove.Double);
                }
                getRightPayoff = getBestPlayerMovePayoff(playerHS, gc, legalMoves, minC, maxC);
            }
            double rightPayoff = splitRightPayoff + getRightPayoff;

            double totalPayoff = leftPayoff + rightPayoff;
            return totalPayoff;

        }
    }

    public static double outcomePayoff(Outcome outcome, double blackjackPayoff){
        if(outcome.equals(Outcome.WINBLACKJACK)){
            return blackjackPayoff;
        }
        else if (outcome.equals(Outcome.WIN)){
            return 1.0;
        }
        else if(outcome.equals(Outcome.PUSH)){
            return 0.0;
        }
        else if(outcome.equals(Outcome.LOSS)){
            return -1.0;
        }
        else{
            return -5.0;
        }

    }

    //TODO, I think I want to add surrender stuff to this method.

    public static Outcome playerOutcomeVsDealer(RandomishPlayer player, int handNumber, Dealer dealer, boolean dealerPeeksBlackjack, boolean pushOnDealerHard22, boolean player21AlwaysWins, boolean isSmart){

        HandEncoding playerHE = new HandEncoding(player.playerHands.get(handNumber).handCards);
        HandEncoding dealerHE = new HandEncoding(dealer.getDealerCards());

        int bestScorePlayer = playerHE.getBestScore();
        int bestScoreDealer = dealerHE.getBestScore();

        if(dealerPeeksBlackjack && dealer.dealerHasBlackjack()){
            if(!isSmart){
                return Outcome.VOID;
            }
            if(!player.playerHasBlackjack()){
                return Outcome.LOSS;
            }
            return Outcome.PUSH;

        }

        if(bestScoreDealer >= 22 && player.playerHasBlackjack()){
            if(player.playerHands.size() == 1) {//for no blackjack after split
                return Outcome.WINBLACKJACK;
            }
            return Outcome.WIN;
        }
        else if(bestScoreDealer == 22 & bestScorePlayer == 21 && (!player.playerHasBlackjack())){
            if(pushOnDealerHard22){
                return Outcome.PUSH;
            }
            return Outcome.WIN;
        }
        else if(dealer.dealerHasBlackjack() && player.playerHasBlackjack()){
            if(player21AlwaysWins){
                if(player.playerHands.size() == 1) {//for no blackjack after split
                    return Outcome.WINBLACKJACK;
                }
                return Outcome.WIN;
            }
            else{
                return Outcome.PUSH;
            }
        }
        else if(player.playerHasBlackjack()){
            if(player.playerHands.size() == 1) {//for no blackjack after split
                return Outcome.WINBLACKJACK;
            }
            return Outcome.WIN;
        }
        else if(bestScoreDealer == 21 && bestScorePlayer == 21){
            if(player21AlwaysWins){
                return Outcome.WIN;
            }
            return Outcome.PUSH;
        }
        else if(bestScorePlayer > 21){
            return Outcome.LOSS;
        }
        else if(bestScoreDealer > 21){
            return Outcome.WIN;
        }
        else if(bestScoreDealer == bestScorePlayer){
            return Outcome.PUSH;
        }
        else if(bestScorePlayer > bestScoreDealer){
            return Outcome.WIN;
        }
        else{
            return Outcome.LOSS;
        }
    }

    public double getBestPlayerMovePayoff(HandSituation playerHS, GranularCount gc, EnumSet<PlayerMove> legalMoves, int minC, int maxC){
        if(gc.getDoubleFromCount() < minC){
            gc = new GranularCount((double) minC);
        }
        else if(gc.getDoubleFromCount()> maxC){
            gc = new GranularCount((double) maxC);
        }
        return simulationTable.getBestPlayerMovePayoff(playerHS, gc, legalMoves);
    }

    public void setCardsSmart(){
        SimulationParameters sp = this.simulationTable.simulationParameters;
        int penPercent = sp.houseRules.penetrationPercentage;
        int minC = sp.minCountish;
        int maxC = sp.maxCountish;
        double countPrecision = sp.countGranularity;

        this.table.givePlayer2RandomCardsAndRunCount();
        this.table.giveDealerRandomHandAndRunCount();
        this.table.removeRandomAmountCardsAndRunCount(penPercent);
    }

    public void setCards(HashMap<HandEncoding, ArrayList<DoubleRanks>> hetdr, ArrayList<TripleRanks> ah20ptr, ArrayList<TripleRanks> ah21ptr){
        SimulationParameters sp = this.simulationTable.simulationParameters;
        int penPercent = sp.houseRules.penetrationPercentage;
        int mhpdcca = sp.minHitsPerDecisionCellCountA;
        int mhpdccb = sp.minHitsPerDecisionCellCountB;
        int minC = sp.minCountish;
        int maxC = sp.maxCountish;
        double countPrecision = sp.countGranularity;

        HandSituation hs = this.handSituationToPlayV3(mhpdcca, mhpdccb, minC, maxC, countPrecision);
        HandEncoding he = hs.playerHE;
        HashSet initEncodedHands = HandEncoding.getStartingEncodings();
        if(initEncodedHands.contains(he)){
            this.table.givePlayer3CardsThatFitHandEncodingAndCount(he, ah20ptr, ah21ptr);
        }
        else{
            this.table.givePlayer2CardsThatFitHandEncodingAndCount(he, hetdr);//TODO and above
        }
        this.table.giveDealerHandAndRunCount(hs.dealerRankVal);
        this.table.removeRandomAmountCardsAndRunCount(penPercent);
    }



}
