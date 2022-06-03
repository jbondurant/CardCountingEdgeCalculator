import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;

public class Table {
    public RandomishPlayer randomishPlayer;
    public Dealer dealer;
    public CompositeCardSource gameDeck;
    public int runningCount;
    CountMethod countMethod;


    public Table(int numDeck, CountMethod cm){
        randomishPlayer = new RandomishPlayer();
        dealer = new Dealer();
        gameDeck = CompositeCardSource.getMultiDeck(numDeck);
        runningCount = 0;
        countMethod = cm;
    }


    /*public Table halfDeepCopy(){
        CountMethod cmCopy = this.countMethod;
        Table tCopy = new Table(1, cmCopy);

        tCopy.randomishPlayer = this.randomishPlayer.deepCopy();
        tCopy.dealer = this.dealer.deepCopy();
        tCopy.gameDeck = this.gameDeck.deepCopy();
        tCopy.runningCount = this.runningCount;
        return tCopy;

    }*/

    public GranularCount getGranularCount(int deckSize, double countGranularity, int minC, int maxC){
        double numDecksRoundedUpDouble = (double) CountMethod.getNumDecksRoundedUp(gameDeck.cards.size(), countMethod.deckEstimationPrecision, deckSize);
        double runningCountDouble = (double) runningCount;
        double trueCount = runningCount / numDecksRoundedUpDouble;
        double grainTrueCount = GranularCount.roundToGrain(trueCount, countGranularity);
        GranularCount granularCount = new GranularCount(grainTrueCount);

        if(granularCount.getDoubleFromCount() < minC){
            granularCount = new GranularCount((double) minC);
        }
        else if(granularCount.getDoubleFromCount()> maxC){
            granularCount = new GranularCount((double) maxC);
        }

        return granularCount;
    }

    public void removeRandomAmountCardsAndRunCount(int maxPenetration, GranularCount randomCount, int deckSize, double countGranularity, int minC, int maxC){
        double ogSizeDouble = (double) gameDeck.startingSize;
        double maxPenDouble = (double) maxPenetration;
        double maxPenDoublePercent = maxPenDouble / 100.0;
        int minDeckSize = (int) ((1 - maxPenDoublePercent) * ogSizeDouble);
        int maxCardsRemoved = gameDeck.cards.size() - minDeckSize;

        CompositeCardSource currDeckCopy = gameDeck.deepCopy();
        int runningCountCopy = runningCount;
        while(true) {
            int numCardsToRemove = (int) (Math.random() * ((maxCardsRemoved) + 1));
            for (int i = 0; i < numCardsToRemove; i++) {
                Card cardRemoved = gameDeck.cards.remove(0);
                runningCount += countMethod.rankToCount.get(cardRemoved.rank);
            }

            GranularCount trueCount = getGranularCount(deckSize, countGranularity, minC, maxC);
            if(trueCount.equals(randomCount)){
                //System.out.println("break");
                break;
            }

            gameDeck = currDeckCopy.deepCopy();
            gameDeck.shuffle();
            runningCount = runningCountCopy;
        }
    }

    public void removeRandomAmountCardsAndRunCountSmart(int maxPenetration){
        double ogSizeDouble = (double) gameDeck.startingSize;
        double maxPenDouble = (double) maxPenetration;
        double maxPenDoublePercent = maxPenDouble / 100.0;
        int minDeckSize = (int) ((1 - maxPenDoublePercent) * ogSizeDouble);
        int maxCardsRemoved = gameDeck.cards.size() - minDeckSize;
        int numCardsToRemove = (int)(Math.random() * ((maxCardsRemoved) + 1));
        for(int i=0; i<numCardsToRemove; i++){
            Card cardRemoved = gameDeck.cards.remove(0);
            runningCount += countMethod.rankToCount.get(cardRemoved.rank);
        }
    }

    public void giveDealerRandomHandAndRunCountMaybe(boolean runCount){
        Card c1 = gameDeck.cards.remove(0);
        Card c2 = gameDeck.cards.remove(0);
        if(runCount) {
            runningCount += countMethod.rankToCount.get(c1.rank);
        }
        dealer.revealedCards.add(c1);
        dealer.hiddenCard.add(c2);

    }

    public void giveDealerHandAndRunCountMaybe(int dealerRankValue, boolean runCount){
        for(int i=0; i<gameDeck.cards.size(); i++){
            Card c1 = gameDeck.cards.get(i);
            if(c1.rank.getRankpoints() == dealerRankValue){
                Card cardRemoved = gameDeck.cards.remove(i);
                if(runCount) {
                    runningCount += countMethod.rankToCount.get(cardRemoved.rank);
                }
                dealer.revealedCards.add(cardRemoved);
                break;
            }

        }
        int randomCard = (int) (Math.random() * gameDeck.cards.size());
        dealer.hiddenCard.add(gameDeck.cards.remove(randomCard));
    }

    public void givePlayer2RandomCardsAndRunCountMaybe(boolean runCount){
        Card c1 = gameDeck.cards.remove(0);
        Card c2 = gameDeck.cards.remove(0);
        ArrayList<Card> playerCards = new ArrayList<>();
        playerCards.add(c1);
        playerCards.add(c2);
        if(runCount) {
            runningCount += countMethod.rankToCount.get(c1.rank);
            runningCount += countMethod.rankToCount.get(c2.rank);
        }
        randomishPlayer.playerHands.playerHand = new PlayerHand(playerCards);
    }

    public void givePlayer2CardsThatFitHandEncodingAndCountMaybe(HandEncoding he, HashMap<HandEncoding, ArrayList<DoubleRanks>> handEncodingToDoubleRanks, boolean runCount){
        ArrayList<DoubleRanks> drs = handEncodingToDoubleRanks.get(he);
        int randomDoubleRank = (int) (Math.random() * drs.size());
        DoubleRanks dr = drs.get(randomDoubleRank);

        ArrayList<Card> handResult = new ArrayList<>();

        for(int i=0; i<gameDeck.cards.size(); i++){
            if(gameDeck.cards.get(i).rank.equals(dr.r1)){
                Card c1 = gameDeck.cards.remove(i);
                handResult.add(c1);
                if(runCount) {
                    runningCount += countMethod.rankToCount.get(c1.rank);
                }
                break;
            }
        }
        for(int i=0; i<gameDeck.cards.size(); i++) {
            if (gameDeck.cards.get(i).rank.equals(dr.r2)) {
                Card c2 = gameDeck.cards.remove(i);
                handResult.add(c2);
                if(runCount) {
                    runningCount += countMethod.rankToCount.get(c2.rank);
                }
                break;
            }
        }
        randomishPlayer.playerHands.playerHand = new PlayerHand(handResult);
    }

    public void givePlayer3CardsThatFitHandEncodingAndCountMaybe(HandEncoding he, ArrayList<TripleRanks> allHard20PossibleTripleRanks, ArrayList<TripleRanks> allHard21PossibleTripleRanks, ArrayList<TripleRanks> allSoft21PossibleTripleRanks, boolean runCount){
        ArrayList<TripleRanks> trs = allHard20PossibleTripleRanks;
        HandEncoding hard21HE = new HandEncoding(false, false, 21);
        HandEncoding soft21HE = new HandEncoding(true, false, 11);
        if(he.equals(hard21HE)){
            trs = allHard21PossibleTripleRanks;
        }
        else if(he.equals(soft21HE)){
            trs = allSoft21PossibleTripleRanks;
        }
        int randomDoubleRank = (int) (Math.random() * trs.size());
        TripleRanks tr = trs.get(randomDoubleRank);

        ArrayList<Card> handResult = new ArrayList<>();

        for(int i=0; i<gameDeck.cards.size(); i++){
            if(gameDeck.cards.get(i).rank.equals(tr.r1)){
                Card c1 = gameDeck.cards.remove(i);
                handResult.add(c1);
                if(runCount) {
                    runningCount += countMethod.rankToCount.get(c1.rank);
                }
                break;
            }
        }
        for(int i=0; i<gameDeck.cards.size(); i++) {
            if (gameDeck.cards.get(i).rank.equals(tr.r2)) {
                Card c2 = gameDeck.cards.remove(i);
                handResult.add(c2);
                if(runCount) {
                    runningCount += countMethod.rankToCount.get(c2.rank);
                }
                break;
            }
        }
        for(int i=0; i<gameDeck.cards.size(); i++) {
            if (gameDeck.cards.get(i).rank.equals(tr.r3)) {
                Card c3 = gameDeck.cards.remove(i);
                handResult.add(c3);
                if(runCount) {
                    runningCount += countMethod.rankToCount.get(c3.rank);
                }
                break;
            }
        }
        randomishPlayer.playerHands.playerHand = new PlayerHand(handResult);

    }

    public void dealerPlay(boolean hitsOnSoft17){
        if(dealer.hiddenCard.size() == 0){
            return; //happens when evaluating split hands
        }
        dealer.revealedCards.add(dealer.hiddenCard.remove(0));
        while(dealer.mustTakeCard(hitsOnSoft17)){
            dealer.revealedCards.add(gameDeck.cards.remove(0));
        }
    }
}
