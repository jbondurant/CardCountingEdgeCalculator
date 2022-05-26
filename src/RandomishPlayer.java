public class RandomishPlayer {
    public HandNode playerHands;

    public RandomishPlayer(){
        playerHands = new HandNode();
    }

    public boolean playerHasBlackjack(){
        if(playerHands.getNumActualNodes() > 1){
            return false;
        }
        HandEncoding playerHE = new HandEncoding(playerHands.playerHand.handCards);
        if(playerHands.playerHand.handCards.size() == 2 && playerHE.isSoft21()){
            return true;
        }
        return false;
    }

}
