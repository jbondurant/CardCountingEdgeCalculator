import java.util.EnumSet;

//taken from https://stackoverflow.com/questions/2151232/java-class-card-enum-example-revised
public enum Rank {
    ACE(11), TWO(2), THREE(3), FOUR(4), FIVE(5), SIX(6), SEVEN(7),
    EIGHT(8), NINE(9), TEN(10), JACK(10), QUEEN(10), KING(10);

    private int rankPoints;

    Rank(int points) {
        this.rankPoints = points;
    }

    public int getRankpoints() {
        return this.rankPoints;
    }

    //method for isAce?
    //method for sameRank?
    //probably not necessary

    public static EnumSet<Rank> getSetAllRanks(){
        EnumSet<Rank> allRanks = EnumSet.allOf(Rank.class);
        return allRanks;
    }

    public static EnumSet<Rank> getSetAllRanksExceptAce(){
        EnumSet<Rank> result = EnumSet.noneOf(Rank.class);
        for(Rank r : getSetAllRanks()){
            if(!r.equals(ACE)){
                result.add(r);
            }
        }
        return result;
    }

}
