import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Objects;

public class PlayerDealerBestScore {
    public int playerBestScore;
    public int dealerBestScore;
    public boolean playerHasBlackjack;
    public boolean dealerHasBlackjack;

    public PlayerDealerBestScore(int pbs, int dbs, boolean phbj, boolean dhbj){
        playerBestScore = pbs;
        dealerBestScore = dbs;
        playerHasBlackjack = phbj;
        dealerHasBlackjack = dhbj;
    }

    public static double getPlayerPayoff(HashMap<PlayerDealerBestScore, Outcome> outcomeFinder, MetaDealerResult mdr, int playerBestScore, double blackjackPayoff, boolean phbj, boolean dhbj){
        BigDecimal outcome = BigDecimal.ZERO;
        //System.out.println("pbs:\t" + playerBestScore);

        BigDecimal o17 = BigDecimal.valueOf(Outcome.outcomePayoff(outcomeFinder.get(new PlayerDealerBestScore(playerBestScore, 17, phbj, dhbj)), blackjackPayoff)).multiply(BigDecimal.valueOf(mdr.num17));
        BigDecimal o18  = BigDecimal.valueOf(Outcome.outcomePayoff(outcomeFinder.get(new PlayerDealerBestScore(playerBestScore, 18, phbj, dhbj)), blackjackPayoff)).multiply(BigDecimal.valueOf(mdr.num18));
        BigDecimal o19  = BigDecimal.valueOf(Outcome.outcomePayoff(outcomeFinder.get(new PlayerDealerBestScore(playerBestScore, 19, phbj, dhbj)), blackjackPayoff)).multiply(BigDecimal.valueOf(mdr.num19));
        BigDecimal o20  = BigDecimal.valueOf(Outcome.outcomePayoff(outcomeFinder.get(new PlayerDealerBestScore(playerBestScore, 20, phbj, dhbj)), blackjackPayoff)).multiply(BigDecimal.valueOf(mdr.num20));

        Outcome outcome21 = outcomeFinder.get(new PlayerDealerBestScore(playerBestScore, 21, phbj, dhbj));
        double outcomePayoff21 = Outcome.outcomePayoff(outcome21, blackjackPayoff);
        BigDecimal o21 = BigDecimal.valueOf(outcomePayoff21).multiply(BigDecimal.valueOf(mdr.num21));
        //BigDecimal o21  = BigDecimal.valueOf(Outcome.outcomePayoff(outcomeFinder.get(new PlayerDealerBestScore(playerBestScore, 21, phbj, dhbj)), blackjackPayoff)).multiply(BigDecimal.valueOf(mdr.num21));
        BigDecimal o22  = BigDecimal.valueOf(Outcome.outcomePayoff(outcomeFinder.get(new PlayerDealerBestScore(playerBestScore, 22, phbj, dhbj)), blackjackPayoff)).multiply(BigDecimal.valueOf(mdr.numBust));
        outcome = outcome.add(o17);
        outcome = outcome.add(o18);
        outcome = outcome.add(o19);
        outcome = outcome.add(o20);
        outcome = outcome.add(o21);
        outcome = outcome.add(o22);

        BigDecimal totalNum = BigDecimal.ZERO;
        totalNum = totalNum.add(BigDecimal.valueOf(mdr.num17));
        totalNum = totalNum.add(BigDecimal.valueOf(mdr.num18));
        totalNum = totalNum.add(BigDecimal.valueOf(mdr.num19));
        totalNum = totalNum.add(BigDecimal.valueOf(mdr.num20));
        totalNum = totalNum.add(BigDecimal.valueOf(mdr.num21));
        totalNum = totalNum.add(BigDecimal.valueOf(mdr.numBust));

        outcome = outcome.divide(totalNum, 100, RoundingMode.FLOOR);
        return outcome.doubleValue();
    }

    public String getString(){
        String s = playerBestScore + "&" + dealerBestScore + "&";
        if(playerHasBlackjack){
            s += "T" + "&";
        }
        else{
            s += "F" + "&";
        }
        if(dealerHasBlackjack){
            s += "T";
        }
        else{
            s += "F";
        }
        return s;
    }

    public PlayerDealerBestScore getPlayerDealerBestScoreFromString(String S){
        String[] parts = S.split("&");
        int pbs = Integer.parseInt(parts[0]);
        int dbs = Integer.parseInt(parts[1]);
        boolean phbj = false;
        if(parts[2].equals("T")){
            phbj = true;
        }
        boolean dhbj = false;
        if(parts[3].equals("T")){
            dhbj = true;
        }
        return new PlayerDealerBestScore(pbs, dbs, phbj, dhbj);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlayerDealerBestScore that = (PlayerDealerBestScore) o;
        return playerBestScore == that.playerBestScore && dealerBestScore == that.dealerBestScore && playerHasBlackjack == that.playerHasBlackjack && dealerHasBlackjack == that.dealerHasBlackjack;
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerBestScore, dealerBestScore, playerHasBlackjack, dealerHasBlackjack);
    }

    public static Outcome playerOutcomeVsDealerOld(RandomishPlayer player, HandNode handNode, Dealer dealer, HouseRules hr, boolean isSmart){
        HandEncoding playerHE = new HandEncoding(handNode.playerHand.handCards);
        HandEncoding dealerHE = new HandEncoding(dealer.getDealerCards());
        int bestScorePlayer = playerHE.getBestScore();
        int bestScoreDealer = dealerHE.getBestScore();
        boolean playerHasBlackjack = player.playerHasBlackjack();
        boolean dealerHasBlackjack = dealer.dealerHasBlackjack();
        if(isSmart) {
            return playerOutcomeVsDealerForPayoff(hr, bestScorePlayer, bestScoreDealer, playerHasBlackjack, dealerHasBlackjack);
        }
        return playerOutcomeVsDealerForTable(hr, bestScorePlayer, bestScoreDealer, playerHasBlackjack, dealerHasBlackjack);
    }

    //TODO, I think I want to add surrender stuff to these methods
    public static Outcome playerOutcomeVsDealerForTable(HouseRules hr, int bestScorePlayer, int bestScoreDealer, boolean playerHasBlackjack, boolean dealerHasBlackjack){
        boolean pushOnDealerHard22 =  hr.pushOnDealerHard22;
        boolean player21AlwaysWins = hr.player21AlwaysWins;
        boolean dealerPeeksBlackjack = hr.dealerPeeksBlackjack;

        if(dealerHasBlackjack && dealerPeeksBlackjack){
            return Outcome.VOID;
        }
        else if(playerHasBlackjack){
            return Outcome.VOID;
        }
        //DealerNoBlackjack or dealerblackjackAndNoPeek
        //PlayerNoBlackjack
        else if(dealerHasBlackjack){
            return Outcome.LOSS;
        }
        //DealerNoBlackjack and PlayerNoBlackjack
        return getOutcomeWhenNoPlayerNorDealerBlackjacks(bestScorePlayer, bestScoreDealer, pushOnDealerHard22, player21AlwaysWins);
    }

    //TODO, I think I want to add surrender stuff to these methods
    public static Outcome playerOutcomeVsDealerForPayoff(HouseRules hr, int bestScorePlayer, int bestScoreDealer, boolean playerHasBlackjack, boolean dealerHasBlackjack){
        boolean pushOnDealerHard22 =  hr.pushOnDealerHard22;
        boolean player21AlwaysWins = hr.player21AlwaysWins;

        if(dealerHasBlackjack){
            if(!playerHasBlackjack){
                return Outcome.LOSS;
            }
            if(player21AlwaysWins){
                return Outcome.WIN;
            }
            return Outcome.PUSH;
        }
        //dealerNoBlackJack
        if(playerHasBlackjack){
            return Outcome.WINBLACKJACK;
        }
        //DealerNoBlackjack and PlayerNoBlackjack

        else if(bestScoreDealer == 22 & bestScorePlayer == 21){
            if(pushOnDealerHard22){
                return Outcome.PUSH;
            }
            return Outcome.WIN;
        }
        return getOutcomeWhenNoPlayerNorDealerBlackjacks(bestScorePlayer, bestScoreDealer, pushOnDealerHard22, player21AlwaysWins);
    }

    public static Outcome getOutcomeWhenNoPlayerNorDealerBlackjacks(int bestScorePlayer, int bestScoreDealer, boolean pushOnDealerHard22, boolean player21AlwaysWins){
        if(bestScoreDealer == 22 & bestScorePlayer == 21){
            if(pushOnDealerHard22){
                return Outcome.PUSH;
            }
            return Outcome.WIN;
        }
        else if(bestScoreDealer == 21 && bestScorePlayer == 21){
            if(player21AlwaysWins){
                return Outcome.WIN;
            }
            return Outcome.PUSH;
        }
        else if(bestScorePlayer > 21){
            return Outcome.LOSS;
        }
        else if(bestScoreDealer > 21){
            return Outcome.WIN;
        }
        else if(bestScoreDealer == bestScorePlayer){
            return Outcome.PUSH;
        }
        else if(bestScorePlayer > bestScoreDealer){
            return Outcome.WIN;
        }
        else{
            return Outcome.LOSS;
        }
    }



    public static HashMap<PlayerDealerBestScore, Outcome> initializeOutcomeFinderForTable(HouseRules hr){
    HashMap<PlayerDealerBestScore, Outcome>  pdToOutcome = new HashMap<>();
        for(int i=1; i<40; i++){
            for(int j=17; j<40; j++) {
                if(i==20){
                    int k = 1;
                }
                PlayerDealerBestScore pdbs = new PlayerDealerBestScore(i,j, false, false);
                Outcome outcome = playerOutcomeVsDealerForTable(hr, i, j, false, false);
                pdToOutcome.put(pdbs, outcome);
            }
        }
        int i=21;
        for(int j=17; j<40; j++){
            PlayerDealerBestScore pdbsTF = new PlayerDealerBestScore(i,j, true, false);
            Outcome outcomeTF = playerOutcomeVsDealerForTable(hr, i, j, true, false);
            pdToOutcome.put(pdbsTF, outcomeTF);

        }
        int j=21;
        PlayerDealerBestScore pdbsFT = new PlayerDealerBestScore(i,j, false, true);
        PlayerDealerBestScore pdbsTT = new PlayerDealerBestScore(i,j, true, true);
        Outcome outcomeFT = playerOutcomeVsDealerForTable(hr, i, j, false, true);
        Outcome outcomeTT = playerOutcomeVsDealerForTable(hr, i, j, true, true);
        pdToOutcome.put(pdbsFT, outcomeFT);
        pdToOutcome.put(pdbsTT, outcomeTT);

        return pdToOutcome;
    }

}
