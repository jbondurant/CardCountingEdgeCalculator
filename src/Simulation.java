import java.io.IOException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.stream.Collectors;

public class Simulation {
    public Table table;
    public SimulationTable simulationTable;
    public String name;


    public static void main(String[] args) throws IOException, InterruptedException {

        //select option

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

    /**
     * The _id a table is stored under: the name's characters in hex, padded with "a" to
     * the 24 hex digits of an ObjectId.
     *
     * Nothing shortened a longer name, so anything over 12 characters came out over 24
     * digits and new ObjectId threw from inside the first load, before anything was
     * simulated, with a message about hexadecimal that did not mention the name. It is
     * refused here instead. The encoding of the names that fit is unchanged, so the tables
     * already stored under them still resolve.
     */
    public static String fixName(String ogName){
        String hexName = ogName.chars().mapToObj(c -> Integer.toHexString(c)).collect(Collectors.joining());
        if(hexName.length() > 24){
            throw new IllegalArgumentException("the table name \"" + ogName + "\" is too long "
                    + "to store: its _id is the name in hex, which comes to "
                    + hexName.length() + " digits, and an ObjectId holds 24. That is 12 "
                    + "plain ASCII characters; pick a shorter name.");
        }
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
        int minHitsPerDecisionCellCount = 500000;//move to at least 500
        int minMetaDealer = 1000000;
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
        int minHitsPerDecisionCellCount = 50000;//move to at least 500
        int minMetaDealer = 100000;
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
        for(int i=0; i<3; i++){
            numOneMinutes++;
            simulation.runPayoffFinderSim(numMinutes);
            Thread.sleep(10 * 1000);
            System.out.println("ran " + numOneMinutes + " one minute sessions");
        }
    }



    public Simulation(SimulationTable st, String n){
        // Refused here because nothing later would notice: a rule the engine does not play
        // does not fail, it just produces tables for a different game.
        st.simulationParameters.houseRules.requirePlayable();
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

    /**
     * The fingerprint of the strategy the payoff run is about to play, refusing when the
     * table is not finished.
     *
     * A payoff run measures the edge of a strategy, so it needs one to measure. On a
     * partly built table it used to start anyway and stop at the first unmeasured cell it
     * happened to reach; and if it reached none, it measured a strategy that the next
     * strategy run would go on to change.
     */
    public String fingerprintOfFinishedStrategy(){
        SimulationParameters sp = simulationTable.simulationParameters;
        HandSituation unfinished = handSituationToPlayV3(sp.minHitsPerDecisionCellCount,
                sp.minCountish, sp.maxCountish, sp.countGranularity);
        if(unfinished != null){
            throw new IllegalStateException("the payoff run plays the strategy in "
                    + StoredState.describeTable(name) + ", but that table is not finished: "
                    + unfinished.getStringFromEncoding() + " still needs "
                    + sp.minHitsPerDecisionCellCount + " hands on some move at every count "
                    + "from " + sp.minCountish + " to " + sp.maxCountish
                    + ". Finish it with runMetaSimulation first.");
        }
        return simulationTable.getStrategyFingerprint();
    }

    public void runPayoffFinderSim(int numMinutes) throws UnknownHostException, InterruptedException {
        Date start = new Date();
        Date end = new Date(start.getTime() + numMinutes * 60 * 1000);
        // Checked before it replaces this.simulationTable, whose parameters are the code's.
        SimulationTable stored = SimulationTable.getTable(name, this.simulationTable);
        stored.resumeUnder(this.simulationTable.simulationParameters);
        simulationTable = stored;
        SimulationParameters sp = simulationTable.simulationParameters;
        int minC = sp.minCountish;
        int maxC = sp.maxCountish;
        double countPrecision = sp.countGranularity;
        String semanticsKey = sp.getSemanticsKey();
        String strategyFingerprint = fingerprintOfFinishedStrategy();

        PayoffTable emptyPayoffTable = new PayoffTable(minC, maxC, countPrecision, name);
        PayoffTable payoffTable = PayoffTable.getTable(name, emptyPayoffTable);
        payoffTable.resumeUnder(semanticsKey, strategyFingerprint);

        while((new Date()).before(end)){
            EventResult eventResult = runSingleSmartEvent(minC, maxC, countPrecision);
            GranularCount eventGC = eventResult.granularCount;
            if(!eventGC.isCountInBoundaries(minC, maxC)){
                continue;
            }

            payoffTable.insertEventSmart(eventResult);
        }
        System.out.println("Average payoff:\t" + payoffTable.getAveragePayoff());
        PayoffTable.saveTable(payoffTable, semanticsKey, strategyFingerprint);
    }


    //need to code it so that I can start with partial SimulationParameters
    public void runSimulation(int numMinutes) throws UnknownHostException, InterruptedException {
        Date start = new Date();
        Date end = new Date(start.getTime() + numMinutes * 60 * 1000);
        double oddsBestMove = 0.5;
        double oddsSecondBestMove = 0.3;
        // Checked before it replaces this.simulationTable, whose parameters are the code's.
        SimulationTable stored = SimulationTable.getTable(name, this.simulationTable);
        stored.resumeUnder(this.simulationTable.simulationParameters);
        simulationTable = stored;
        SimulationParameters sp = simulationTable.simulationParameters;
        String semanticsKey = sp.getSemanticsKey();

        HashMap<PlayerDealerBestScore, Outcome> outcomeFinder = PlayerDealerBestScore.initializeOutcomeFinderForTable(simulationTable.simulationParameters.houseRules);

        int minC = sp.minCountish;
        int maxC = sp.maxCountish;
        double countPrecision = sp.countGranularity;

        int minMetaDealer = sp.minMetaDealer;
        boolean dealerPeeksForBlackjack = sp.houseRules.dealerPeeksBlackjack;
        MetaDealer md = MetaDealer.getMetaDealer(name);
        md.resumeUnder(semanticsKey);
        while((!md.isCompleted(minMetaDealer, minC, maxC, countPrecision)) && (new Date()).before(end)){
            MetaDealerEventResult mdEventResult = runSingleMetaDealerEvent();
            GranularCount mdEventGC = mdEventResult.granularCount;
            mdEventGC.forceCountIntoBoundaries(minC, maxC);
            md.insertEvent(mdEventResult, dealerPeeksForBlackjack);
        }
        md.saveToDB(semanticsKey);


        int mhpdcc = sp.minHitsPerDecisionCellCount;

        Holdings holdings = enumerateHoldings();
        while(this.handSituationToPlayV3(mhpdcc, minC, maxC, countPrecision) != null && (new Date()).before(end)){
            EventResult eventResult = runSingleEvent(holdings.twoCard, holdings.hard20, holdings.hard21, holdings.soft21, outcomeFinder, md, oddsBestMove, oddsSecondBestMove);
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

    /**
     * The ranks the table run deals a player from, one list per hand it can ask for.
     *
     * Hard 20, hard 21 and soft 21 have three-card lists of their own, because two cards
     * cannot make them except as a pair of tens or a natural. Every other hand is dealt as
     * two cards, from twoCard.
     */
    public static class Holdings {
        public final HashMap<HandEncoding, ArrayList<DoubleRanks>> twoCard;
        public final ArrayList<TripleRanks> hard20;
        public final ArrayList<TripleRanks> hard21;
        public final ArrayList<TripleRanks> soft21;

        public Holdings(HashMap<HandEncoding, ArrayList<DoubleRanks>> twoCard, ArrayList<TripleRanks> hard20, ArrayList<TripleRanks> hard21, ArrayList<TripleRanks> soft21){
            this.twoCard = twoCard;
            this.hard20 = hard20;
            this.hard21 = hard21;
            this.soft21 = soft21;
        }
    }

    /**
     * Enumerate the holdings over a fresh, undealt shoe.
     *
     * setCards deals a hand by picking one entry of its list at random, so the list has to
     * hold every way a full shoe makes that hand, once for each way, for 10-6 and 9-7 to
     * come up as often as a real shoe deals them. This used to enumerate over a copy of
     * this.table's shoe, but by the time the table run starts that shoe has been dealt
     * from: the MetaDealer phase and every table hand replace this.table and deal into it.
     * So the enumeration gets a shoe of its own, whatever this.table holds.
     */
    public Holdings enumerateHoldings(){
        int numDecks = simulationTable.simulationParameters.houseRules.numDecks;
        CompositeCardSource fullShoe = CompositeCardSource.getMultiDeck(numDecks);
        return new Holdings(
                HandEncoding.handEncodingToDoubleRanks(fullShoe),
                HandEncoding.allPossibleTripleRanksForHard20(fullShoe),
                HandEncoding.allPossibleTripleRanksForHard21(fullShoe),
                HandEncoding.allPossibleTripleRanksForSoft21(fullShoe));
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
        if(dc == null){
            throw new UnsolvedCellException("the payoff run reads a finished table, but it "
                    + "has no cell for " + hs.getStringFromEncoding()
                    + " at true count " + granularCount.countToCellString());
        }
        MoveChoices mcs = dc.countToMoveChoice.get(granularCount);
        if(mcs == null){
            throw new UnsolvedCellException("the payoff run reads a finished table, but "
                    + hs.getStringFromEncoding() + " has nothing at true count "
                    + granularCount.countToCellString());
        }
        EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
        for(PlayerMove lm : mcs.actionPayoffs.keySet()){
            legalMoves.add(lm);
        }
        PlayerMove pm = mcs.getActionWithBestPayoff(legalMoves);
        if(pm == null){
            throw new UnsolvedCellException("the payoff run reads a finished table, but "
                    + hs.getStringFromEncoding() + " has no measured move at true count "
                    + granularCount.countToCellString());
        }

        // Taken from the cards before the round is played, since playing it changes them. A
        // natural is paid when the dealer has none to push it and the player stands on it;
        // hitting it would leave an ordinary three-card hand, settled as one. In practice the
        // soft-21 cell always says stand, so this comes down to a natural the dealer lacks.
        boolean paidNatural = table.randomishPlayer.playerHasBlackjack()
                && !table.dealer.dealerHasBlackjack()
                && pm.equals(PlayerMove.Stand);

        //System.out.println("-----");
        double payoff = doPlayerMoveSmartAndGetPayoff(pm, table.randomishPlayer.playerHands);

        EventResult eventResult = new EventResult(payoff, playerHE, dealerRevealedRank, pm, granularCount, paidNatural);
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
        // A pair can be split only if the rules allow at least one split of its rank. The
        // limit checks inside the split are for resplits: with a limit of 0 they found the
        // dealt pair already over it, dealt nothing, and returned the pair's best other
        // move, which was then recorded as the payoff of Split.
        Rank pairRank = table.randomishPlayer.playerHands.playerHand.handCards.get(0).rank;
        boolean canSplit = playerHE.canSplit && hr.allowsSplitting(pairRank);
        // Surrender is a first action on the original two cards. setCards deals three of
        // them for the hard 20, hard 21 and soft 21 targets, and those cannot surrender.
        boolean canSurrender = (hr.canEarlySurrender || hr.canLateSurrender)
                && table.randomishPlayer.playerHands.playerHand.handCards.size() == 2;
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

        double payoff = doPlayerMoveAndGetPayoff(playerMove, table.randomishPlayer.playerHands, outcomeFinder, metaDealer);
        EventResult eventResult = new EventResult(payoff, playerHE, dealerRevealedRank, playerMove, granularCount);
        return eventResult;
    }

    // Neither dispatcher counts a card the player draws, whether from a hit, a double or a
    // split. Every cell and the MetaDealer are measured at the count before the round's own
    // cards, which is why setCards and setCardsSmart deal the hand and the dealer's up card
    // without counting them. That was settled in June 2022: counting the player's cards
    // made low counts look better only because a low count meant the hand was more likely
    // to be holding a ten or an ace. So every lookup within a round reads the count the
    // round was dealt at. Counting the drawn card would look up the hand it made at a count
    // that includes one of that hand's own cards, which is not how its cell was measured.
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
                return 0.0;
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
                // A card has already been taken above, so surrender is gone.
                legalMoves.add(PlayerMove.Hit);
                legalMoves.add(PlayerMove.Stand);

                PlayerMove bestMove = getBestPlayerMove(playerHS, gc, legalMoves, minC, maxC);
                if(bestMove == null){
                    // Substituting Stand would quietly play a different strategy than the
                    // one being measured, and still report the hand as though the table
                    // had chosen the move.
                    throw new UnsolvedCellException("the payoff run reads a finished table, "
                            + "but " + playerHS.getStringFromEncoding()
                            + " has no measured move at true count " + gc.countToCellString());
                }
                if(bestMove.equals(PlayerMove.Stand)){
                    table.dealerPlay(hitsOnSoft17);
                    Outcome outcome = PlayerDealerBestScore.playerOutcomeVsDealerOld(table.randomishPlayer, handNode, this.table.dealer, hr, true);
                    return Outcome.outcomePayoff(outcome, hr.blackjackPayout);
                }
                // best move is hit
                Card cNext = table.gameDeck.cards.remove(0);
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
        else if(firstMove.equals(PlayerMove.Surrender)){
            // Forfeit half the bet and stop. Without this the move fell through to the
            // split branch below and tried to split whatever the hand happened to be.
            return -0.5;
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
            Card phV2c2 = table.gameDeck.cards.remove(0);

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
        CountMethod countMethod = simulationTable.simulationParameters.countMethod;
        int maxSplitsAces = hr.numSplitsAces;
        int maxSplitsNotAces = hr.numSplitsNotAces;
        int minC = this.simulationTable.simulationParameters.minCountish;
        int maxC = this.simulationTable.simulationParameters.maxCountish;

        if(firstMove.equals(PlayerMove.Stand)){
            return getStandPayoff(handNode, outcomeFinder, metaDealer);
        }
        else if(firstMove.equals(PlayerMove.Hit)){
            Card c = table.gameDeck.cards.remove(0);
            handNode.playerHand.handCards.add(c);

            HandEncoding playerHE = new HandEncoding(handNode.playerHand.handCards);
            int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();
            HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
            if(playerHE.isBusted()){
                return -1.0;
            }
            GranularCount gc = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity, minC, maxC);
            EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
            // A card has already been taken above, so surrender is gone.
            legalMoves.add(PlayerMove.Hit);
            legalMoves.add(PlayerMove.Stand);
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
            // A doubled hand takes exactly one card and then stands. Surrender is no
            // longer on the table once the extra bet is down.
            EnumSet<PlayerMove> legalMoves = EnumSet.of(PlayerMove.Stand);
            double nextMoveAveragePayoffDouble = 2.0 * getBestPlayerMovePayoff(playerHS, gc, legalMoves);
            return nextMoveAveragePayoffDouble;
        }
        else if(firstMove.equals(PlayerMove.Surrender)){
            // Forfeit half the bet and stop. Without this the move fell through to the
            // split branch below and tried to split whatever the hand happened to be.
            return -0.5;
        }
        else{
            //Split

            Rank rank = handNode.playerHand.handCards.get(0).rank;
            boolean cantSplitAces = rank.equals(Rank.ACE) && table.randomishPlayer.playerHands.getNumActualNodes() > maxSplitsAces;
            boolean cantSplitNotAces = (!rank.equals(Rank.ACE)) && table.randomishPlayer.playerHands.getNumActualNodes() > maxSplitsNotAces;

            int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();

            if(cantSplitAces || cantSplitNotAces){
                return playBestNotSplit(handNode, outcomeFinder, metaDealer);
            }

            Card phV1c1 = handNode.playerHand.handCards.get(0);
            Card phV2c1 = handNode.playerHand.handCards.get(1);

            Card phV1c2 = table.gameDeck.cards.remove(0);
            Card phV2c2 = table.gameDeck.cards.remove(0);

            handNode.leftChildHandNode = HandNode.createHand(phV1c1, phV1c2);
            handNode.rightChildHandNode = HandNode.createHand(phV2c1, phV2c2);
            handNode.playerHand = null;


            boolean cantSplitLeft = phV1c1.rank.getRankpoints() != phV1c2.rank.getRankpoints();
            boolean cantSplitRight = phV2c1.rank.getRankpoints() != phV2c2.rank.getRankpoints();

            double leftPayoff = 0.0;
            if(cantSplitLeft){
                leftPayoff = playBestNotSplit(handNode.leftChildHandNode, outcomeFinder, metaDealer);
            }
            else {
                leftPayoff = doPlayerMoveAndGetPayoff(PlayerMove.Split, handNode.leftChildHandNode, outcomeFinder, metaDealer);
            }
            double rightPayoff = 0.0;
            if(cantSplitRight){
                rightPayoff = playBestNotSplit(handNode.rightChildHandNode, outcomeFinder, metaDealer);
            }
            else {
                rightPayoff = doPlayerMoveAndGetPayoff(PlayerMove.Split, handNode.rightChildHandNode, outcomeFinder, metaDealer);
            }
            double totalPayoff = leftPayoff + rightPayoff;
            return totalPayoff;

        }
    }





    /**
     * What standing on this hand is worth in the table run, at the current count and
     * up-card.
     *
     * Nothing here is looked up in a cell. The MetaDealer is filled before the table run
     * starts and does not change during it, so the dealer's odds of ending on each total
     * are already settled, and a stand is weighed against those. It follows that a stand
     * recorded by the table run is the same number every time for a given count, up-card
     * and total.
     */
    public double getStandPayoff(HandNode handNode, HashMap<PlayerDealerBestScore, Outcome> outcomeFinder, MetaDealer metaDealer){
        HouseRules hr = this.simulationTable.simulationParameters.houseRules;
        int deckSize = table.gameDeck.startingSize / hr.numDecks;
        double countGranularity = simulationTable.simulationParameters.countGranularity;
        int minC = this.simulationTable.simulationParameters.minCountish;
        int maxC = this.simulationTable.simulationParameters.maxCountish;

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

    public double getBestPlayerMovePayoff(HandSituation playerHS, GranularCount gc, EnumSet<PlayerMove> legalMoves){
        return simulationTable.getBestPlayerMovePayoff(playerHS, gc, legalMoves);
    }

    public void setCardsSmart(){
        SimulationParameters sp = this.simulationTable.simulationParameters;
        int penPercent = sp.houseRules.penetrationPercentage;
        int minC = sp.minCountish;
        int maxC = sp.maxCountish;
        double countPrecision = sp.countGranularity;
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
        // No surrender here: it is a first action on the original two cards, and this hand
        // came out of a split. A few houses do allow it; this ruleset does not model that.
        if (hr.ranksThatCanBeDoubledDownAfterSplit.contains(rank)) {
            legalMoves.add(PlayerMove.Double);
        }
        legalMoves.remove(PlayerMove.Split);

        // With one legal move there is no choice to read from the table, so play it.
        // Asking anyway went wrong for split aces, which may only stand: a split ace that
        // draws another ace is A,A again, and a finished A,A cell need not have measured
        // standing at every count, since standing is rarely the best way to play A,A.
        if(legalMoves.size() == 1){
            return doPlayerMoveSmartAndGetPayoff(legalMoves.iterator().next(), handNode);
        }

        PlayerMove bestOtherMove = getBestPlayerMove(playerHS, gc, legalMoves, minC, maxC);
        if(bestOtherMove == null){
            throw new UnsolvedCellException("the payoff run reads a finished table, but the "
                    + "split hand " + playerHS.getStringFromEncoding()
                    + " has no measured move at true count " + gc.countToCellString());
        }
        return doPlayerMoveSmartAndGetPayoff(bestOtherMove, handNode);
    }

    /**
     * What a hand that came out of a split is worth in the table run: standing, or the
     * best of its other legal moves that its cell has measured.
     *
     * Standing is priced directly rather than read from the cell, and that is not a
     * shortcut. A child that is still a pair and cannot be split again -- A,A once aces
     * have been split, x,x at four hands -- has for its own cell the pair cell being
     * built at this moment, often at a count that cell has not reached yet. Split aces
     * may not hit or double, so standing is all they have left, and a pair cell that has
     * been leaning towards splitting has seldom measured it. Reading the cell found
     * nothing and stopped the run; before that, the gap was filled with 0.0 and recorded
     * as an observation.
     *
     * For any other split hand this changes nothing. A stand the table run recorded is
     * the same number every time for a given count, up-card and total (see
     * getStandPayoff), so a cell that has measured Stand holds exactly the value
     * computed here.
     */
    public double playBestNotSplit(HandNode handNode, HashMap<PlayerDealerBestScore, Outcome> outcomeFinder, MetaDealer metaDealer){
        int minC = this.simulationTable.simulationParameters.minCountish;
        int maxC = this.simulationTable.simulationParameters.maxCountish;
        HouseRules hr = this.simulationTable.simulationParameters.houseRules;
        int deckSize = table.gameDeck.startingSize / hr.numDecks;
        int dealerRevealedScore = table.dealer.revealedCards.get(0).rank.getRankpoints();

        HandEncoding playerHE = new HandEncoding(handNode.playerHand.handCards);
        Rank rank = handNode.playerHand.handCards.get(0).rank;
        HandSituation playerHS = new HandSituation(playerHE, dealerRevealedScore);
        GranularCount gc = table.getGranularCount(deckSize, simulationTable.simulationParameters.countGranularity, minC, maxC);

        EnumSet<PlayerMove> otherMeasuredMoves = this.simulationTable.getKnownMovesWithPayoffs(playerHS, gc);
        otherMeasuredMoves.remove(PlayerMove.Stand);
        otherMeasuredMoves.remove(PlayerMove.Split);
        if((rank.equals(Rank.ACE)) && (!hr.canHitAfterSplittingAces)) {
            otherMeasuredMoves.remove(PlayerMove.Hit);
        }
        // Always removed, not just when the rules forbid it: this hand came out of a
        // split, and surrender is a first action on the original two cards.
        otherMeasuredMoves.remove(PlayerMove.Surrender);
        if(!hr.ranksThatCanBeDoubledDownAfterSplit.contains(rank)) {
            otherMeasuredMoves.remove(PlayerMove.Double);
        }

        double bestPayoff = getStandPayoff(handNode, outcomeFinder, metaDealer);
        if(!otherMeasuredMoves.isEmpty()){
            bestPayoff = Math.max(bestPayoff, getBestPlayerMovePayoff(playerHS, gc, otherMeasuredMoves));
        }
        return bestPayoff;
    }


}
