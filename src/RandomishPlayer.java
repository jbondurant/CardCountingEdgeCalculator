public class RandomishPlayer {
    public HandNode playerHands;

    public RandomishPlayer(){
        playerHands = new HandNode();
    }

    /**
     * Whether the player was dealt a natural: two cards making 21, and no split.
     *
     * This is the only player hand a dealer natural does not beat; it pushes one. It is
     * not the same question as whether a hand is paid as a blackjack, which a hand out of
     * a split can be under some house rules (see handIsPaidAsBlackjack).
     */
    public boolean playerHasBlackjack(){
        if(playerHands.getNumActualNodes() > 1){
            return false;
        }
        return isTwoCardTwentyOne(playerHands.playerHand);
    }

    /**
     * Whether this hand, one of the player's, is paid as a blackjack if it stands.
     *
     * It has to be two cards making 21. A dealt natural always is paid as one. A hand
     * that came out of a split -- a split ace that drew a ten, a split ten that drew an
     * ace, at any depth of resplitting -- is paid as one only where the house pays
     * blackjack on split pairs; anywhere else it is an ordinary 21. Both runs ask this one
     * question of a standing hand, so the rule is decided here and nowhere else.
     */
    public boolean handIsPaidAsBlackjack(HandNode hand, HouseRules hr){
        if(!isTwoCardTwentyOne(hand.playerHand)){
            return false;
        }
        boolean roundWasSplit = playerHands.getNumActualNodes() > 1;
        return !roundWasSplit || hr.blackjackOnSplitPairs;
    }

    private static boolean isTwoCardTwentyOne(PlayerHand hand){
        return hand.handCards.size() == 2 && new HandEncoding(hand.handCards).isSoft21();
    }

}
