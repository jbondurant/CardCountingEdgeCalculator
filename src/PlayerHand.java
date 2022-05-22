import java.util.ArrayList;

public class PlayerHand {
    public ArrayList<Card> handCards;

    public PlayerHand(ArrayList<Card> hc){
        handCards = hc;
    }

    public PlayerHand deepCopy(){
        ArrayList<Card> hcCopy = new ArrayList<>();
        for(Card c : this.handCards){
            Card cCopy = c.deepCopy();
            hcCopy.add(cCopy);
        }
        PlayerHand phCopy = new PlayerHand(hcCopy);
        return phCopy;
    }
}
