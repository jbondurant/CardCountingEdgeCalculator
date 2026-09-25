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
        if(mdr == null){
            // runSimulation fills the MetaDealer to completion before the table phase
            // begins, so every (count, up-card) should be here. A missing one means it
            // did not, and the weighted payoff below would be built on nothing.
            throw new UnsolvedCellException("no dealer outcomes recorded for a player "
                    + playerBestScore + "; the MetaDealer is not finished");
        }

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

        if(totalNum.signum() == 0){
            throw new UnsolvedCellException("the dealer bucket for a player "
                    + playerBestScore + " is empty; there is nothing to average");
        }
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
        boolean dealerHasBlackjack = dealer.dealerHasBlackjack();
        // A dealer natural beats every hand but a dealt natural, which it pushes, so a
        // split hand paid as a blackjack still loses to one. That only arises where the
        // dealer does not peek; a peeked natural ends the round before anyone can split.
        boolean playerHasBlackjack = dealerHasBlackjack
                ? player.playerHasBlackjack()
                : player.handIsPaidAsBlackjack(handNode, hr);
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

        // A natural the dealer peeks at resolves before the player makes a decision, so it
        // carries no information about whether hitting or standing was better. VOID marks
        // that: the hand does not count, and is dropped rather than scored. It is not
        // reachable in the table run, which discards a dealer natural in runSingleEvent and
        // never records one in the MetaDealer.
        //
        // A player blackjack is reachable, but never a dealt natural: setCards deals a
        // soft-21 target as three cards, and a natural is two. That three-card dealing is
        // what keeps a natural's 3:2 out of the soft-21 cell, so hitting a soft 14 into a
        // 21 does not inherit a bonus it has not earned. What can reach here is a hand out
        // of a split that drew to two cards making 21, where the house pays blackjack on
        // split pairs (RandomishPlayer.handIsPaidAsBlackjack). It is paid as a blackjack,
        // and its value goes to the pair that was split, not to the soft-21 cell. Being a
        // split hand, it loses to a dealer natural like any other hand but a dealt one.
        if(dealerHasBlackjack && dealerPeeksBlackjack){
            return Outcome.VOID;
        }
        //dealerblackjackAndNoPeek
        else if(dealerHasBlackjack){
            return Outcome.LOSS;
        }
        else if(playerHasBlackjack){
            return Outcome.WINBLACKJACK;
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
