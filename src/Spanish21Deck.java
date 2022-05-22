import java.util.ArrayList;
import java.util.Collections;

public class Spanish21Deck implements CardSource{
    public ArrayList<Card> cards;

    public Spanish21Deck(){
        cards = new ArrayList<>();
        for(Suit s: Suit.values()){
            for(Rank r: Rank.values()){
                if(r == Rank.TEN){
                    continue;
                }
                cards.add(new Card(r, s));
            }
        }
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