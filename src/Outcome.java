public enum Outcome {
    WINBLACKJACK, WIN, PUSH, LOSS, VOID;

    public static double outcomePayoff(Outcome outcome, double blackjackPayoff){
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
            return -1.0;
        }

    }

}
