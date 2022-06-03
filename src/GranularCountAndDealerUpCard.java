import java.util.Objects;

public class GranularCountAndDealerUpCard {
    public GranularCount granularCount;
    public int dealerUpCardScore;

    public GranularCountAndDealerUpCard(GranularCount gc, int ducs){
        granularCount = gc;
        dealerUpCardScore = ducs;
    }

    public String getString(){
        String s = "";
        s+= this.granularCount.getStringFromCount();
        s+= "#" + dealerUpCardScore;
        return s;
    }

    public static GranularCountAndDealerUpCard getFromString(String s){
        String[] parts = s.split("#");
        GranularCount gc = GranularCount.getCountFromString(parts[0]);
        int ducs = Integer.parseInt(parts[1]);
        return new GranularCountAndDealerUpCard(gc, ducs);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GranularCountAndDealerUpCard that = (GranularCountAndDealerUpCard) o;
        return dealerUpCardScore == that.dealerUpCardScore && Objects.equals(granularCount, that.granularCount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(granularCount, dealerUpCardScore);
    }
}
