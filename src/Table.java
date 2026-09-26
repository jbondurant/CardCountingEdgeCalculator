import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

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
        return getGranularCount(runningCount, gameDeck.cards.size(), countMethod, deckSize, countGranularity, minC, maxC);
    }

    /**
     * The bucket a running count lands in with this many cards left. It takes the shoe as
     * numbers so that reachableBuckets can ask about every shoe the run could deal and get
     * the answer the run would.
     */
    static GranularCount getGranularCount(int runningCount, int cardsLeft, CountMethod countMethod, int deckSize, double countGranularity, int minC, int maxC){
        double numDecksRoundedUpDouble = (double) CountMethod.getNumDecksRoundedUp(cardsLeft, countMethod.deckEstimationPrecision, deckSize);
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

    /**
     * Every bucket the table run can land on under these rules, found by trying everything
     * the run can do rather than by reasoning about the grain.
     *
     * setCards deals the player two or three cards and the dealer two, none of them
     * counted, and removeRandomAmountCardsAndRunCount then burns anywhere from none up to
     * the penetration limit and counts what it burned. So a bucket can be landed on when
     * some number of burned cards, and some running count that many cards can add up to,
     * give a true count that rounds and clamps onto it. That loop reshuffles until it lands
     * on the bucket it was given and has no other way out, so a bucket missing from here is
     * one it would chase forever.
     *
     * Which cards the deal took depends on the hand, so each count tag is taken to have
     * lost as many cards as were dealt. That shoe is poorer than any real one, so whatever
     * it can reach, every deal can reach. A bucket has to be reachable after a two-card and
     * after a three-card player hand, because the run asks for every bucket with both.
     */
    static TreeSet<GranularCount> reachableBuckets(HouseRules hr, CountMethod cm, double countGranularity, int minC, int maxC){
        CompositeCardSource shoe = CompositeCardSource.getMultiDeck(hr.numDecks);
        int deckSize = shoe.startingSize / hr.numDecks;
        TreeMap<Integer, Integer> cardsPerTag = new TreeMap<>();
        int lowestRunningCount = 0;
        for(Card card : shoe.cards){
            int tag = cm.rankToCount.get(card.rank);
            cardsPerTag.merge(tag, 1, Integer::sum);
            lowestRunningCount += Math.min(tag, 0);
        }

        TreeSet<GranularCount> reachable = null;
        for(int dealt : new int[]{4, 5}){
            int cardsInShoe = shoe.startingSize - dealt;
            int mostBurned = Math.max(0, mostCardsBurned(cardsInShoe, shoe.startingSize, hr.penetrationPercentage));

            // Bit r of runningCounts[n] is set when some n of the cards left add up to a
            // running count of r + lowestRunningCount. Each card is added once, the way a
            // 0/1 knapsack adds an item, so no card is burned twice.
            BigInteger[] runningCounts = new BigInteger[mostBurned + 1];
            Arrays.fill(runningCounts, BigInteger.ZERO);
            runningCounts[0] = BigInteger.ONE.shiftLeft(-lowestRunningCount);
            int cardsAdded = 0;
            for(Map.Entry<Integer, Integer> tagAndCards : cardsPerTag.entrySet()){
                int tag = tagAndCards.getKey();
                int cardsLeft = Math.max(0, tagAndCards.getValue() - dealt);
                for(int i = 0; i < cardsLeft; i++){
                    cardsAdded++;
                    for(int n = Math.min(mostBurned, cardsAdded); n >= 1; n--){
                        runningCounts[n] = runningCounts[n].or(runningCounts[n - 1].shiftLeft(tag));
                    }
                }
            }

            TreeSet<GranularCount> landed = new TreeSet<>();
            for(int n = 0; n <= mostBurned; n++){
                BigInteger counts = runningCounts[n];
                for(int r = 0; r < counts.bitLength(); r++){
                    if(counts.testBit(r)){
                        landed.add(getGranularCount(r + lowestRunningCount, cardsInShoe - n, cm, deckSize, countGranularity, minC, maxC));
                    }
                }
            }
            if(reachable == null){
                reachable = landed;
            }
            else{
                reachable.retainAll(landed);
            }
        }
        return reachable;
    }

    /**
     * The most cards the burn may take from a shoe holding cardsInShoe before penetration
     * says it is reshuffled. Both burns use it, and so does reachableBuckets, so the check
     * and the run cannot disagree about how deep the shoe goes.
     */
    static int mostCardsBurned(int cardsInShoe, int startingSize, int maxPenetration){
        double ogSizeDouble = (double) startingSize;
        double maxPenDouble = (double) maxPenetration;
        double maxPenDoublePercent = maxPenDouble / 100.0;
        int minDeckSize = (int) ((1 - maxPenDoublePercent) * ogSizeDouble);
        return cardsInShoe - minDeckSize;
    }

    public void removeRandomAmountCardsAndRunCount(int maxPenetration, GranularCount randomCount, int deckSize, double countGranularity, int minC, int maxC){
        int maxCardsRemoved = mostCardsBurned(gameDeck.cards.size(), gameDeck.startingSize, maxPenetration);

        // The chosen cards were each taken as the first card of their rank from the top,
        // so the cards above where they were found are all other ranks. Burning from that
        // order leaves the player drawing from a top that is short of the ranks just
        // dealt. A missed count reshuffles below, but the first attempt needs it too.
        gameDeck.shuffle();
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
        int maxCardsRemoved = mostCardsBurned(gameDeck.cards.size(), gameDeck.startingSize, maxPenetration);
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
