import java.util.ArrayList;
import java.util.Objects;

public class HandSituation {
    HandEncoding playerHE;
    int dealerRankVal;

    public HandSituation(HandEncoding phe, int drv){
        playerHE = phe;
        dealerRankVal = drv;
    }

    public static ArrayList<HandSituation> getOrderedSituations(){
        ArrayList<HandSituation> handSituations = new ArrayList<>();
        ArrayList<HandEncoding> handEncodings = HandEncoding.getOrderedEncodings();
        for(HandEncoding phe : handEncodings){
            for(int i=2; i<=11; i++){
                handSituations.add(new HandSituation(phe, i));
            }
        }
        return handSituations;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HandSituation that = (HandSituation) o;
        return dealerRankVal == that.dealerRankVal && Objects.equals(playerHE, that.playerHE);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerHE, dealerRankVal);
    }

    public static HandSituation getEncodingFromString(String s){
        String[] parts = s.split("\\$");
        HandEncoding phe = HandEncoding.getEncodingFromString(parts[0]);
        int drv = Integer.parseInt(parts[1]);
        return new HandSituation(phe, drv);
    }

    public String getStringFromEncoding(){
        String pheString = playerHE.getStringFromEncoding();
        return pheString + "$" + dealerRankVal;
    }
}
