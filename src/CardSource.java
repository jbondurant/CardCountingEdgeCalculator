import java.util.ArrayList;

public interface CardSource {
    public Card draw();
    public void shuffle();
    public ArrayList<Card> getCardSource();
}
