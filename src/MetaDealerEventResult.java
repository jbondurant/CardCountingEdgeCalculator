import java.util.Objects;

public class MetaDealerEventResult {
    public GranularCount granularCount;
    public int dealerBestScore;
    public boolean dealerHasBlackjack;
    public int dealerRevealedCardScore;

    public MetaDealerEventResult(GranularCount gc, int dbs, boolean dhbj, int drcs){
        granularCount = gc;
        dealerBestScore = dbs;
        dealerHasBlackjack = dhbj;
        dealerRevealedCardScore = drcs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MetaDealerEventResult that = (MetaDealerEventResult) o;
        return dealerBestScore == that.dealerBestScore && dealerHasBlackjack == that.dealerHasBlackjack && dealerRevealedCardScore == that.dealerRevealedCardScore && Objects.equals(granularCount, that.granularCount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(granularCount, dealerBestScore, dealerHasBlackjack, dealerRevealedCardScore);
    }
}
