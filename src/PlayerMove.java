import java.util.ArrayList;
import java.util.Random;

public enum PlayerMove {
    Stand,
    Hit,
    Double,
    Split,
    Surrender;

    public static PlayerMove getMove(boolean canDouble, boolean canSplit, boolean canSurrender, boolean canHit){
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
        Random rand = new Random();
        PlayerMove randomMove = givenList.get(rand.nextInt(givenList.size()));
        return randomMove;
    }



}

