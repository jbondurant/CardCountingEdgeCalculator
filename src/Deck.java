import java.util.ArrayList;
import java.util.Collections;

public class Deck implements CardSource{
    public ArrayList<Card> cards;

    public Deck(){
        cards = new ArrayList<>();
        for(Suit s: Suit.values()){
            for(Rank r: Rank.values()){
                cards.add(new Card(r, s));
            }
        }
        int i=1;
    }

    public Card draw() {
        return cards.remove(cards.size()-1);
    }

    public void shuffle() {
        Collections.shuffle(cards);
    }

    public ArrayList<Card> getCardSource() {
        return cards;
    }
}
