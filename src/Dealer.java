import java.util.ArrayList;

public class Dealer {
    public ArrayList<Card> hiddenCard;
    public ArrayList<Card> revealedCards;

    public Dealer(){
        hiddenCard = new ArrayList<Card>();
        revealedCards = new ArrayList<Card>();
    }

    public Dealer deepCopy(){
        ArrayList<Card> hcCopy = new ArrayList<>();
        ArrayList<Card> rcCopy = new ArrayList<>();
        for(Card c : hiddenCard){
            hcCopy.add(c.deepCopy());
        }
        for(Card c : revealedCards){
            rcCopy.add(c.deepCopy());
        }
        Dealer dCopy = new Dealer();
        dCopy.hiddenCard = hcCopy;
        dCopy.revealedCards = rcCopy;
        return dCopy;
    }

    public ArrayList<Card> getDealerCards(){
        ArrayList<Card> dealerCards = new ArrayList<>();
        dealerCards.addAll(hiddenCard);
        dealerCards.addAll(revealedCards);
        return dealerCards;
    }

    public boolean dealerHasBlackjack(){
        HandEncoding dealerHE = new HandEncoding(getDealerCards());
        if(getDealerCards().size() == 2 && dealerHE.isSoft21()){
            return true;
        }
        return false;
    }

    public boolean mustTakeCard(boolean hitsOnSoft17){
        HandEncoding dealerHE = new HandEncoding(getDealerCards());
        if(!dealerHE.isSoft){
            if(dealerHE.hardCount < 17){
                return true;
            }
            return false;
        }
        //is soft
        if(hitsOnSoft17){
            if(dealerHE.hardCount == 7){
                return true;
            }
        }
        if(dealerHE.hardCount < 7){
            return true;
        }
        return false;
    }

}
