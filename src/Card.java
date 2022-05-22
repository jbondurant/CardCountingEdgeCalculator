import java.util.Objects;

public class Card {
    public Rank rank;
    public Suit suit;

    public Card(Rank r, Suit s){
        rank = r;
        suit = s;
    }

    public Card deepCopy(){
        return new Card(this.rank, this.suit);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Card card = (Card) o;
        return rank == card.rank && suit == card.suit;
    }

    @Override
    public int hashCode() {
        return Objects.hash(rank, suit);
    }
}
