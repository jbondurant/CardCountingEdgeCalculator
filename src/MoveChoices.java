import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.util.Hash;

import javax.swing.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;

public class MoveChoices {
    public HashMap<PlayerMove, ActionPayoff> actionPayoffs;

    public MoveChoices(){
        actionPayoffs = new HashMap<PlayerMove, ActionPayoff>();
    }

    public String getCompoundBestMove(){
        EnumSet<PlayerMove> legalMoves = EnumSet.noneOf(PlayerMove.class);
        for(PlayerMove key : actionPayoffs.keySet()){
            legalMoves.add(key);
        }
        PlayerMove pm1 = getActionWithBestPayoff(legalMoves);
        if(pm1.equals(PlayerMove.Double) || pm1.equals(PlayerMove.Split)){
            if(pm1.equals(PlayerMove.Double)){
                legalMoves.remove(PlayerMove.Double);
                PlayerMove pm2 = getActionWithBestPayoff(legalMoves);
                return pm1.name() + pm2.name();
            }
            else{
                legalMoves.remove(PlayerMove.Split);
                PlayerMove pm2 = getActionWithBestPayoff(legalMoves);
                return pm1.name() + pm2.name();
            }
        }
        else{
            return pm1.name();
        }

    }

    public double getPayoffOfActionWithBestPayoff(EnumSet<PlayerMove> legalMoves){
        PlayerMove bestMove = PlayerMove.Stand;
        double bestPayoff = Integer.MIN_VALUE;
        for(PlayerMove pm : actionPayoffs.keySet()){
            ActionPayoff ap = actionPayoffs.get(pm);
            if(legalMoves.contains(pm) && ap.avPayoff > bestPayoff){
                bestPayoff = ap.avPayoff;
                bestMove = pm;
            }
        }
        if(legalMoves.contains(PlayerMove.Surrender) && bestPayoff < -0.5){
            bestPayoff = -0.5;
        }
        return bestPayoff;
    }

    public PlayerMove getActionWithBestPayoff(EnumSet<PlayerMove> legalMoves){
        PlayerMove bestMove = PlayerMove.Stand;
        double bestPayoff = Integer.MIN_VALUE;
        for(PlayerMove pm : actionPayoffs.keySet()){
            ActionPayoff ap = actionPayoffs.get(pm);
            if(legalMoves.contains(pm) && ap.avPayoff > bestPayoff){
                bestPayoff = ap.avPayoff;
                bestMove = pm;
            }
        }
        if(legalMoves.contains(PlayerMove.Surrender) && bestPayoff < -0.5){
            bestPayoff = -0.5;
            bestMove = PlayerMove.Surrender;
        }
        return bestMove;
    }

    public void insertEvent(EventResult er){
        if(actionPayoffs.containsKey(er.playedFirstMove)) {
            ActionPayoff ap = actionPayoffs.get(er.playedFirstMove);
            ap.insertEvent(er.payoff);
            actionPayoffs.put(er.playedFirstMove, ap);
        }
        else{
            ActionPayoff ap = new ActionPayoff();
            ap.insertEvent(er.payoff);
            actionPayoffs.put(er.playedFirstMove, ap);
        }
    }

    public static MoveChoices getMoveCountFromObject(BasicDBObject moveChoicesObject){
        HashMap<PlayerMove, ActionPayoff> actionPayoffs = new HashMap<>();
        for(String s : moveChoicesObject.keySet()){
            PlayerMove pm = PlayerMove.valueOf(s);
            ActionPayoff ap = ActionPayoff.getActionPayoffFromObject((BasicDBObject) moveChoicesObject.get(s));
            actionPayoffs.put(pm, ap);
        }
        MoveChoices mc = new MoveChoices();
        mc.actionPayoffs = actionPayoffs;
        return mc;
    }

    public BasicDBObject getDBObject(){
        BasicDBObject moveChoicesObject = new BasicDBObject();
        for(PlayerMove pm : this.actionPayoffs.keySet()){
            ActionPayoff ap = actionPayoffs.get(pm);
            BasicDBObject apObject = ap.getDBObject();
            moveChoicesObject.append(pm.name(), apObject);
        }
        return moveChoicesObject;
    }
}
