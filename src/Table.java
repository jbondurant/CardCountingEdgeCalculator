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

    //TODO wow this is bad
    public void removeRandomAmountCardsAndRunCount(int maxPenetration){
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

    public void giveDealerRandomHandAndRunCount(){
        Card c1 = gameDeck.cards.remove(0);
        Card c2 = gameDeck.cards.remove(0);
        runningCount += countMethod.rankToCount.get(c1.rank);
        dealer.revealedCards.add(c1);
        dealer.hiddenCard.add(c2);

    }

    public void giveDealerHandAndRunCount(int dealerRankValue){
        for(int i=0; i<gameDeck.cards.size(); i++){
            Card c1 = gameDeck.cards.get(i);
            if(c1.rank.getRankpoints() == dealerRankValue){
                Card cardRemoved = gameDeck.cards.remove(i);
                runningCount += countMethod.rankToCount.get(cardRemoved.rank);
                dealer.revealedCards.add(cardRemoved);
                break;
            }

        }
        int randomCard = (int) (Math.random() * gameDeck.cards.size());
        dealer.hiddenCard.add(gameDeck.cards.remove(randomCard));
    }

    public void givePlayer2RandomCardsAndRunCount(){
        Card c1 = gameDeck.cards.remove(0);
        Card c2 = gameDeck.cards.remove(0);
        ArrayList<Card> playerCards = new ArrayList<>();
        playerCards.add(c1);
        playerCards.add(c2);
        runningCount += countMethod.rankToCount.get(c1.rank);
        runningCount += countMethod.rankToCount.get(c2.rank);
        randomishPlayer.playerHands.playerHand = new PlayerHand(playerCards);
    }

    public void givePlayer2CardsThatFitHandEncodingAndCount(HandEncoding he, HashMap<HandEncoding, ArrayList<DoubleRanks>> handEncodingToDoubleRanks){
        ArrayList<DoubleRanks> drs = handEncodingToDoubleRanks.get(he);
        int randomDoubleRank = (int) (Math.random() * drs.size());
        DoubleRanks dr = drs.get(randomDoubleRank);

        ArrayList<Card> handResult = new ArrayList<>();

        for(int i=0; i<gameDeck.cards.size(); i++){
            if(gameDeck.cards.get(i).rank.equals(dr.r1)){
                Card c1 = gameDeck.cards.remove(i);
                handResult.add(c1);
                runningCount += countMethod.rankToCount.get(c1.rank);
                break;
            }
        }
        for(int i=0; i<gameDeck.cards.size(); i++) {
            if (gameDeck.cards.get(i).rank.equals(dr.r2)) {
                Card c2 = gameDeck.cards.remove(i);
                handResult.add(c2);
                runningCount += countMethod.rankToCount.get(c2.rank);
                break;
            }
        }
        randomishPlayer.playerHands.playerHand = new PlayerHand(handResult);
    }

    public void givePlayer3CardsThatFitHandEncodingAndCount(HandEncoding he, ArrayList<TripleRanks> allHard20PossibleTripleRanks, ArrayList<TripleRanks> allHard21PossibleTripleRanks){
        ArrayList<TripleRanks> trs = allHard20PossibleTripleRanks;
        HandEncoding hard21He = new HandEncoding(false, false, 21);
        if(he.equals(hard21He)){
            trs = allHard21PossibleTripleRanks;
        }
        int randomDoubleRank = (int) (Math.random() * trs.size());
        TripleRanks tr = trs.get(randomDoubleRank);

        ArrayList<Card> handResult = new ArrayList<>();

        for(int i=0; i<gameDeck.cards.size(); i++){
            if(gameDeck.cards.get(i).rank.equals(tr.r1)){
                Card c1 = gameDeck.cards.remove(i);
                handResult.add(c1);
                runningCount += countMethod.rankToCount.get(c1.rank);
                break;
            }
        }
        for(int i=0; i<gameDeck.cards.size(); i++) {
            if (gameDeck.cards.get(i).rank.equals(tr.r2)) {
                Card c2 = gameDeck.cards.remove(i);
                handResult.add(c2);
                runningCount += countMethod.rankToCount.get(c2.rank);
                break;
            }
        }
        for(int i=0; i<gameDeck.cards.size(); i++) {
            if (gameDeck.cards.get(i).rank.equals(tr.r3)) {
                Card c3 = gameDeck.cards.remove(i);
                handResult.add(c3);
                runningCount += countMethod.rankToCount.get(c3.rank);
                break;
            }
        }
        randomishPlayer.playerHands.playerHand = new PlayerHand(handResult);

    }

    /*
    public void givePlayer2CardsThatFitHandEncodingAndCount(HandEncoding he){
        HashMap<HandEncoding, EnumSet<Rank>> encToRank = HandEncoding.getEncodingsToPossibleFirstCardRanks();
        ArrayList<Card> handResult = new ArrayList<>();
        for(int i=0; i<gameDeck.cards.size()-1; i++){
            Card c1 = gameDeck.cards.get(i);
            if(!encToRank.get(he).contains(c1.rank)){
                continue;
            }
            for(int j=i+1; j<gameDeck.cards.size(); j++){
                ArrayList<Card> hand = new ArrayList<>();
                Card c2 = gameDeck.cards.get(j);
                hand.add(c1);
                hand.add(c2);
                HandEncoding he2 = new HandEncoding(hand);
                if(!he.equals(he2)){
                    continue;
                }
                handResult = hand;
            }
        }
        for(int i=0; i<gameDeck.cards.size(); i++){
            if(gameDeck.cards.get(i).equals(handResult.get(0))){
                Card c = gameDeck.cards.remove(i);
                runningCount += countMethod.rankToCount.get(c.rank);
                break;
            }
        }
        for(int i=0; i<gameDeck.cards.size(); i++){
            if(gameDeck.cards.get(i).equals(handResult.get(1))){
                Card c = gameDeck.cards.remove(i);
                runningCount += countMethod.rankToCount.get(c.rank);
                break;
            }
        }
        randomishPlayer.playerHands.add(new PlayerHand(handResult));
    }

    public void givePlayer3CardsThatFitHandEncodingAndCount(HandEncoding he){
        ArrayList<Card> handResult = new ArrayList<>();
        for(int i=0; i<gameDeck.cards.size()-2; i++){
            Card c1 = gameDeck.cards.get(i);
            for(int j=i+1; j<gameDeck.cards.size()-1; j++){
                Card c2 = gameDeck.cards.get(j);
                for(int k=j+1; k<gameDeck.cards.size(); k++){
                    Card c3 = gameDeck.cards.get(k);
                    ArrayList<Card> hand = new ArrayList<>();
                    hand.add(c1);
                    hand.add(c2);
                    hand.add(c3);
                    HandEncoding he2 = new HandEncoding(hand);
                    if(!he.equals(he2)){
                        continue;
                    }
                    handResult = hand;
                }
            }
        }

        for(int i=0; i<gameDeck.cards.size(); i++){
            if(gameDeck.cards.get(i).equals(handResult.get(0))){
                Card c = gameDeck.cards.remove(i);
                runningCount += countMethod.rankToCount.get(c.rank);
                break;
            }
        }
        for(int i=0; i<gameDeck.cards.size(); i++){
            if(gameDeck.cards.get(i).equals(handResult.get(1))){
                Card c = gameDeck.cards.remove(i);
                runningCount += countMethod.rankToCount.get(c.rank);
                break;
            }
        }
        for(int i=0; i<gameDeck.cards.size(); i++){
            if(gameDeck.cards.get(i).equals(handResult.get(2))){
                Card c = gameDeck.cards.remove(i);
                runningCount += countMethod.rankToCount.get(c.rank);
                break;
            }
        }
        randomishPlayer.playerHands.add(new PlayerHand(handResult));
    }
    */

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
