import java.util.ArrayList;

public class HandNode {
    public PlayerHand playerHand;
    public HandNode leftChildHandNode;
    public HandNode rightChildHandNode;

    public HandNode(PlayerHand ph){
        playerHand = ph;
        leftChildHandNode = null;
        rightChildHandNode = null;
    }

    public HandNode(){
        playerHand = null;
        leftChildHandNode = null;
        rightChildHandNode = null;
    }

    public static HandNode createHand(Card a, Card b){
        ArrayList<Card> cards = new ArrayList<>();
        cards.add(a);
        cards.add(b);
        HandNode handNode = new HandNode();
        handNode.playerHand = new PlayerHand(cards);
        return handNode;
    }

    public int getNumActualNodes(){
        int numNodes = 0;
        if(playerHand != null){
            numNodes = 1;
        }
        if(leftChildHandNode != null){
            numNodes += leftChildHandNode.getNumActualNodes();
        }
        if(rightChildHandNode != null){
            numNodes += rightChildHandNode.getNumActualNodes();
        }
        return numNodes;

    }



}

