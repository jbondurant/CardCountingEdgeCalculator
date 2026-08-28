public enum Outcome {
    WINBLACKJACK, WIN, PUSH, LOSS, VOID;

    public static double outcomePayoff(Outcome outcome, double blackjackPayoff){
        if(outcome == null){
            // The outcome table is built once and covers every matchup that can arise.
            // A miss means the score is outside what the game can produce.
            throw new UnsolvedCellException("no recorded outcome for this matchup");
        }
        if(outcome.equals(Outcome.WINBLACKJACK)){
            return blackjackPayoff;
        }
        else if (outcome.equals(Outcome.WIN)){
            return 1.0;
        }
        else if(outcome.equals(Outcome.PUSH)){
            return 0.0;
        }
        else if(outcome.equals(Outcome.LOSS)){
            return -1.0;
        }
        else{
            // VOID: the hand ended before the player made a decision -- a dealer natural,
            // or a player one -- so it carries no information about that decision and must
            // be dropped rather than scored. -1.0 was a fabricated loss; 0.0 would be no
            // more real, since averaging a void hand in still pulls a cell toward zero.
            throw new UnsolvedCellException("a void hand has no payoff; it ended before "
                    + "the player acted and must be dropped rather than scored");
        }

    }

}
