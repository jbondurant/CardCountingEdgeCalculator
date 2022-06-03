import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Random;

public enum PlayerMove {
    Stand,
    Hit,
    Double,
    Split,
    Surrender;

    public static PlayerMove getEpsilonMove(boolean canDouble, boolean canSplit, boolean canSurrender, boolean canHit, PlayerMove bestMove, PlayerMove secondBestMove, double oddsBestMove, double oddsSecondBestMove){
        //epsilon of 0.2 means pick random 0.2 times

        ArrayList<PlayerMove> givenList = new ArrayList<>();
        givenList.add(Stand);
        if(canHit) {
            givenList.add(Hit);
        }
        if(canDouble){
            givenList.add(Double);
        }
        if(canSplit){
            givenList.add(Split);
        }
        if(canSurrender){
            givenList.add(Surrender);
        }

        double random = Math.random();
        if(random < oddsBestMove || givenList.size() == 1){
            return bestMove;
        }

        else if(random < (oddsBestMove + oddsSecondBestMove) || givenList.size() == 2){
            return secondBestMove;
        }
        else{
            givenList.remove(bestMove);
            givenList.remove(secondBestMove);
        }

        Random rand = new Random();
        PlayerMove randomMove = givenList.get(rand.nextInt(givenList.size()));
        return randomMove;
    }

    public static EnumSet<PlayerMove> getLegalMoves(boolean canDouble, boolean canSplit, boolean canSurrender, boolean canHit){
        EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
        legalMoves.add(Stand);
        if(canHit) {
            legalMoves.add(Hit);
        }
        if(canDouble){
            legalMoves.add(Double);
        }
        if(canSplit){
            legalMoves.add(Split);
        }
        if(canSurrender){
            legalMoves.add(Surrender);
        }
        return legalMoves;
    }





}

