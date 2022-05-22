import java.util.ArrayList;
import java.util.Collections;

public class CardSequence implements CardSource{
    public ArrayList<Card> cards;

    public CardSequence(){
        cards = new ArrayList<>();
    }

    public CardSequence(ArrayList<Card> cs){
        cards = cs;
    }


    @Override
    public Card draw() {
        return cards.remove(cards.size()-1);
    }

    @Override
    public void shuffle() {
        Collections.shuffle(cards);
    }

    @Override
    public ArrayList<Card> getCardSource() {
        return cards;
    }
}
