import java.util.*;


public class HandEncoding {
    boolean isSoft;
    boolean canSplit;
    int hardCount;// 4 if you have A & 3. H4 or S14

    public HandEncoding(boolean is, boolean cs, int hc){
        isSoft = is;
        canSplit = cs;
        hardCount = hc;
    }

    public static HandEncoding getEncodingFromString(String s){
        boolean soft = false;
        boolean split = false;
        int hCount = 3;
        String[] parts = s.split("&");//hopefuilly doesn't need to be escaped
        if(parts[0].equals("s")){
            soft = true;
        }
        if(parts[1].equals("sp")){
            split = true;
        }
        hCount = Integer.parseInt(parts[2]);
        HandEncoding hEncoding = new HandEncoding(soft, split, hCount);
        return hEncoding;
    }

    public String getStringFromEncoding(){
        String result = "";
        if(isSoft){
            result += "s&";
        }
        else{
            result += "h&";
        }
        if(canSplit){
            result += "sp&";
        }
        else{
            result += "no&";
        }
        result += hardCount;
        return result;
    }

    public HandEncoding(ArrayList<Card> cards){
        int numAces = 0;
        canSplit = false;
        if(cards.size() == 2){
            if(cards.get(0).rank.getRankpoints() == cards.get(1).rank.getRankpoints() ){
                canSplit = true;
            }
        }
        hardCount = 0;
        for(Card card : cards){
            if(card.rank == Rank.ACE){
                numAces++;
                hardCount += 1;
            }
            else{
                hardCount += card.rank.getRankpoints();
            }
        }
        isSoft = false;
        if(numAces>0){
            isSoft = true;
        }
        if(hardCount >= 12){
            isSoft = false;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HandEncoding that = (HandEncoding) o;
        return isSoft == that.isSoft && canSplit == that.canSplit && hardCount == that.hardCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(isSoft, canSplit, hardCount);
    }

    public static HashSet<HandEncoding> getStartingEncodings(){
        HashSet<HandEncoding> hes = new HashSet<>();
        hes.add(new HandEncoding(false, false, 21));
        hes.add(new HandEncoding(false, false, 20));
        return hes;
    }

    //TODO fix, current version is wrong
    public static ArrayList<HandEncoding> getOrderedEncodings(){
        ArrayList<HandEncoding> orderedEncodings = new ArrayList<>();
        for(int i=21; i>= 12; i--){
            HandEncoding he = new HandEncoding(false, false, i);
            orderedEncodings.add(he);
        }
        for(int i=11; i>= 3; i--){
            HandEncoding he = new HandEncoding(true, false, i);
            orderedEncodings.add(he);
        }
        for(int i=11; i>= 5; i--){
            HandEncoding he = new HandEncoding(false, false, i);
            orderedEncodings.add(he);
        }

        for(int i=20; i>=4; i-=2){
            HandEncoding he = new HandEncoding(false, true, i);
            orderedEncodings.add(he);
        }
        orderedEncodings.add(new HandEncoding(true, true, 2));
        return orderedEncodings;
    }

    public boolean isSoft21(){
        HandEncoding s21 = new HandEncoding(true, false, 11);
        return this.equals(s21);
    }

    public boolean isBusted(){
        if(isSoft || canSplit){
            return false;
        }
        if(hardCount > 21){
            return true;
        }
        return false;
    }


    public int getBestScore(){
        if(!isSoft){
            return hardCount;
        }
        int softCount = hardCount + 10;
        return softCount;
    }

    public static ArrayList<TripleRanks> allPossibleTripleRanksForHard21(CompositeCardSource multiDeck){
        HandEncoding he = new HandEncoding(false, false, 21);
        return allPossibleTripleRanksForHandEncoding(multiDeck, he);
    }
    public static ArrayList<TripleRanks> allPossibleTripleRanksForHard20(CompositeCardSource multiDeck){
        HandEncoding he = new HandEncoding(false, false, 20);
        return allPossibleTripleRanksForHandEncoding(multiDeck, he);
    }

    public static HashMap<HandEncoding, ArrayList<DoubleRanks>> handEncodingToDoubleRanks(CompositeCardSource multiDeck){
        HashSet initHE = HandEncoding.getStartingEncodings();
        HashMap<HandEncoding, ArrayList<DoubleRanks>> result = new HashMap<>();
        for(HandEncoding he : getOrderedEncodings()){
            if(initHE.contains(he)){
                continue;
            }
            ArrayList<DoubleRanks> drs = allPossibleDoubleRanksForHandEncoding(multiDeck, he);
            result.put(he, drs);
        }
        return result;
    }

    public static ArrayList<TripleRanks> allPossibleTripleRanksForHandEncoding(CompositeCardSource multiDeck, HandEncoding goalHE){
        if(multiDeck.cards.size() != multiDeck.startingSize){
            System.out.println("nope");
            return null;
        }
        ArrayList<TripleRanks> result = new ArrayList<>();
        ArrayList<Card> currCards = new ArrayList<>();

        for(int i=0; i<multiDeck.cards.size()-2; i++){
            Card c1 = multiDeck.cards.get(i);
            currCards.add(c1);
            for(int j=i+1; j<multiDeck.cards.size()-1; j++){
                Card c2 = multiDeck.cards.get(j);
                currCards.add(c2);
                for(int k=j+1; k<multiDeck.cards.size(); k++){
                    Card c3 = multiDeck.cards.get(k);
                    currCards.add(c3);
                    HandEncoding currHE = new HandEncoding(currCards);
                    if(currHE.equals(goalHE)){
                        TripleRanks tr = new TripleRanks(c1.rank, c2.rank, c3.rank);
                        result.add(tr);
                    }
                    currCards.remove(c3);//todo check this works
                }
                currCards.remove(c2);
            }
            currCards.remove(c1);
        }
        return result;
    }

    public static ArrayList<DoubleRanks> allPossibleDoubleRanksForHandEncoding(CompositeCardSource multiDeck, HandEncoding goalHE){
        if(multiDeck.cards.size() != multiDeck.startingSize){
            System.out.println("nope");
            return null;
        }
        ArrayList<DoubleRanks> result = new ArrayList<>();
        ArrayList<Card> currCards = new ArrayList<>();

        for(int i=0; i<multiDeck.cards.size()-1; i++){
            Card c1 = multiDeck.cards.get(i);
            currCards.add(c1);
            for(int j=i+1; j<multiDeck.cards.size()-1; j++){
                Card c2 = multiDeck.cards.get(j);
                currCards.add(c2);

                HandEncoding currHE = new HandEncoding(currCards);
                if(currHE.equals(goalHE)){
                    DoubleRanks dr = new DoubleRanks(c1.rank, c2.rank);
                    result.add(dr);
                }
                currCards.remove(c2);
            }
            currCards.remove(c1);
        }
        return result;
    }

    /*
    public static HashMap<HandEncoding, EnumSet<Rank>> getEncodingsToPossibleFirstCardRanks(){

        HashMap<HandEncoding, EnumSet<Rank>> encToRank = new HashMap<>();

        for(int i=19; i>= 5; i--){//almost accidentally put >= 4 but that's an automatic split on hard hands
            HandEncoding he = new HandEncoding(false, false, i);
            EnumSet<Rank> possibleRanks = EnumSet.noneOf(Rank.class);

            for(Rank rank : Rank.getSetAllRanks()){
                if(rank.getRankpoints() + 10 >= i && rank.getRankpoints() + 2 <= i){
                    if(!rank.equals(Rank.ACE)) {
                        possibleRanks.add(rank);
                    }
                }
            }
            encToRank.put(he, possibleRanks);

        }
        for(int i=11; i>= 3; i--){//again almost put 2
            HandEncoding he = new HandEncoding(true, false, i);
            EnumSet<Rank> possibleRanks = EnumSet.noneOf(Rank.class);
            possibleRanks.add(Rank.ACE);
            for(Rank rank : Rank.getSetAllRanks()){
                if(rank.getRankpoints() + 1 == i){
                    possibleRanks.add(rank);
                }
            }
            encToRank.put(he, possibleRanks);
        }
        for(int i=20; i>=4; i-=2){
            HandEncoding he = new HandEncoding(false, true, i);
            EnumSet<Rank> possibleRanks = EnumSet.noneOf(Rank.class);
            for(Rank rank : Rank.getSetAllRanks()){
                if(rank.getRankpoints() * 2 == i){
                    possibleRanks.add(rank);
                }
            }
            encToRank.put(he, possibleRanks);
        }
        EnumSet<Rank> possibleRanks = EnumSet.noneOf(Rank.class);
        possibleRanks.add(Rank.ACE);
        encToRank.put((new HandEncoding(true, true, 2)), possibleRanks);
        return encToRank;
    }*/
}

