import java.util.ArrayList;

public class RandomishPlayer {
    public ArrayList<PlayerHand> playerHands;

    public RandomishPlayer(){
        playerHands = new ArrayList<PlayerHand>();
    }

    public boolean playerHasBlackjack(){
        HandEncoding playerHE = new HandEncoding(playerHands.get(0).handCards);
        if(playerHands.size() == 2 && playerHE.isSoft21()){
            return true;
        }
        return false;
    }

    public RandomishPlayer deepCopy(){
        RandomishPlayer rpCopy = new RandomishPlayer();
        for(PlayerHand ph : this.playerHands){
            PlayerHand phCopy = ph.deepCopy();
            rpCopy.playerHands.add(phCopy);
        }
        return rpCopy;
    }
}
