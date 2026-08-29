import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;

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
        if(pm1 == null){
            // The set above is built from the moves this bucket has measured, so it is
            // empty only if the bucket is. DecisionCell.insertEvent puts a move in as it
            // creates a bucket, so that cannot happen from a run; if it does, something
            // built a MoveChoices and never recorded anything in it.
            throw new UnsolvedCellException("this count bucket holds no moves at all, so "
                    + "there is no label to render for it");
        }
        if(pm1.equals(PlayerMove.Double) || pm1.equals(PlayerMove.Split)){
            if(pm1.equals(PlayerMove.Double)){
                legalMoves.remove(PlayerMove.Double);
                PlayerMove pm2 = getActionWithBestPayoff(legalMoves);
                return pm2 == null ? pm1.name() : pm1.name() + pm2.name();
            }
            else{
                legalMoves.remove(PlayerMove.Split);
                PlayerMove pm2 = getActionWithBestPayoff(legalMoves);
                return pm2 == null ? pm1.name() : pm1.name() + pm2.name();
            }
        }
        else{
            return pm1.name();
        }

    }

    /**
     * Best expected payoff among the moves that are both legal and have been measured.
     *
     * The search used to be seeded with Integer.MIN_VALUE and return it when no measured
     * move was legal, so -2147483648 came back as though it were an expected value. The
     * caller records whatever comes back, so a cell sitting at -0.1 over a hundred events
     * became -21,262,214. Returning 0.0 instead would be smaller but just as invented.
     *
     * Stand is always legal at a payoff lookup and, in any bucket holding data at all,
     * always measured, so reaching the throw means the bucket is empty rather than that
     * the caller asked an unusual question.
     */
    public double getPayoffOfActionWithBestPayoff(EnumSet<PlayerMove> legalMoves){
        double bestPayoff = Double.NEGATIVE_INFINITY;
        for(PlayerMove pm : actionPayoffs.keySet()){
            ActionPayoff ap = actionPayoffs.get(pm);
            if(legalMoves.contains(pm) && ap.avPayoff > bestPayoff){
                bestPayoff = ap.avPayoff;
            }
        }
        // Surrender needs no measuring: forfeiting half the bet is worth -0.5 by rule.
        if(legalMoves.contains(PlayerMove.Surrender) && bestPayoff < -0.5){
            bestPayoff = -0.5;
        }
        if(bestPayoff == Double.NEGATIVE_INFINITY){
            throw new UnsolvedCellException("no measured payoff for any legal move of "
                    + legalMoves + "; this count bucket holds " + actionPayoffs.keySet());
        }
        return bestPayoff;
    }

    /**
     * Best legal move, or null when none of the legal moves has been measured.
     *
     * Null is routine here rather than a failure, which is why this does not throw the way
     * the payoff lookup does. Callers narrow the legal set on purpose: runSingleEvent takes
     * the best move, removes it, and asks again to get the second best, and
     * getCompoundBestMove does the same to build a cell label. A bucket that has only ever
     * seen one move has nothing to say to that second question, and the answer is not
     * recorded anywhere -- it only steers exploration.
     *
     * This used to start at Stand and return it when the loop found nothing, so the
     * second-best lookup answered Stand even when Stand had just been ruled out.
     */
    public PlayerMove getActionWithBestPayoff(EnumSet<PlayerMove> legalMoves){
        PlayerMove bestMove = null;
        double bestPayoff = Double.NEGATIVE_INFINITY;
        for(PlayerMove pm : actionPayoffs.keySet()){
            ActionPayoff ap = actionPayoffs.get(pm);
            if(legalMoves.contains(pm) && ap.avPayoff > bestPayoff){
                bestPayoff = ap.avPayoff;
                bestMove = pm;
            }
        }
        if(legalMoves.contains(PlayerMove.Surrender) && bestPayoff < -0.5){
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
