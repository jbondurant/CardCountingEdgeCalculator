public class EventResult {
    public double payoff;
    public HandEncoding playerHE;
    public Rank dealerRevealedCard;
    public PlayerMove playedFirstMove;
    GranularCount granularCount;

    public EventResult(double p, HandEncoding phe, Rank drc, PlayerMove pfm, GranularCount gc){
        payoff = p;
        playerHE = phe;
        dealerRevealedCard = drc;
        playedFirstMove = pfm;
        granularCount = gc;
    }
}
