public class EventResult {
    public double payoff;
    public HandEncoding playerHE;
    public Rank dealerRevealedCard;
    public PlayerMove playedFirstMove;
    GranularCount granularCount;
    // Whether the player was paid for a natural. The payoff cannot say: it is 1.5 only
    // while naturals pay 3:2, and a round could come to 1.5 some other way. The payoff run
    // counts this for its blackjack percentage; the table run never deals a natural, which
    // is why the original constructor leaves it false.
    public boolean paidNatural;

    public EventResult(double p, HandEncoding phe, Rank drc, PlayerMove pfm, GranularCount gc){
        this(p, phe, drc, pfm, gc, false);
    }

    public EventResult(double p, HandEncoding phe, Rank drc, PlayerMove pfm, GranularCount gc, boolean pn){
        payoff = p;
        playerHE = phe;
        dealerRevealedCard = drc;
        playedFirstMove = pfm;
        granularCount = gc;
        paidNatural = pn;
    }
}
