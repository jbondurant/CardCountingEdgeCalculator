import java.util.ArrayList;
import java.util.Collections;

public class CompositeCardSource implements  CardSource{
    public ArrayList<Card> cards;
    public int startingSize;

    public CompositeCardSource(ArrayList<CardSource> cardSources){
        cards = new ArrayList<>();
        for(CardSource cardSource : cardSources){
            cards.addAll(cardSource.getCardSource());
        }
        startingSize = cards.size();
    }

    public CompositeCardSource deepCopy(){
        ArrayList<Card> cCopy = new ArrayList<>();
        for(Card c : this.cards){
            cCopy.add(c.deepCopy());
        }
        ArrayList<CardSource> csCopy = new ArrayList<>();
        csCopy.add(new CardSequence(cCopy));
        return new CompositeCardSource(csCopy);
    }

    public static CompositeCardSource getMultiDeck(int numDecks){
        ArrayList<CardSource> decks = new ArrayList<>();
        for(int i=0; i<numDecks; i++){
            Deck d = new Deck();
            decks.add(d);
        }
        CompositeCardSource gameDeck = new CompositeCardSource(decks);
        Collections.shuffle(gameDeck.cards);
        return gameDeck;
    }

    public static CompositeCardSource getMultiSpanish21Deck(int numDecks){
        ArrayList<CardSource> spanish21Decks = new ArrayList<>();
        for(int i=0; i<numDecks; i++){
            Spanish21Deck s21d = new Spanish21Deck();
            spanish21Decks.add(s21d);
        }
        CompositeCardSource gameDeck = new CompositeCardSource(spanish21Decks);
        Collections.shuffle(gameDeck.cards);
        return gameDeck;
    }

    public static void main(String[] args){
        CompositeCardSource md = getMultiSpanish21Deck(2);
        int i=1;
    }

    public ArrayList<Card> getCardSource() {
        return cards;
    }

    public Card draw() {
        return cards.remove(cards.size()-1);
    }

    public void shuffle() {
        Collections.shuffle(cards);
    }


}
