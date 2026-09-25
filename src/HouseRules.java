import com.mongodb.BasicDBObject;
import com.mongodb.DB;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;

public class HouseRules {
    public int numDecks;
    public int penetrationPercentage;
    public boolean hitsOnSoft17;
    public double blackjackPayout;
    public int numSplitsNotAces;
    public int numSplitsAces; //note 0 for can't split aces
    public EnumSet<Rank> ranksThatCanBeDoubledDownAfterSplit;//watch for aces
    public boolean canHitAfterSplittingAces;
    public int maxNumCardsAfterSplittingAces;
    public boolean dealerPeeksBlackjack;

    public boolean blackjackOnSplitPairs;
    public EnumSet<PlayerSideBetMove> possibleSideBets;
    public EnumSet<Rank> notSplitCardsThatCanBeDoubled;

    public boolean canSwap;
    public int numHandsDealt;
    public boolean pushOnDealerHard22;

    public HashSet<Integer> scoreOfHardHandsPairsThatCanBeFreeDoubled; //notice the pairs
    public EnumSet<Rank> ranksWithFreeBetAfterSplit;

    public boolean canEarlySurrender;
    public boolean canLateSurrender;
    public boolean player21AlwaysWins; //even against dealer blackjack
    public boolean hasDoubleDownRescue;
    //bonusAfterSplit


    //penPercent perhaps 63 to 75
    public static HouseRules getMtlCasino25MinBlackjackParams(int penPercent){
        HouseRules hr = new HouseRules();
        hr.numDecks = 8;
        hr.penetrationPercentage = penPercent;
        hr.hitsOnSoft17 = true;
        hr.blackjackPayout = 1.5;
        hr.numSplitsNotAces = 3;
        hr.numSplitsAces = 1;
        hr.ranksThatCanBeDoubledDownAfterSplit = Rank.getSetAllRanksExceptAce();
        hr.canHitAfterSplittingAces = false;
        hr.maxNumCardsAfterSplittingAces = 1;
        hr.dealerPeeksBlackjack = true;

        hr.blackjackOnSplitPairs = true;
        hr.possibleSideBets = EnumSet.of(PlayerSideBetMove.Insurance);
        hr.notSplitCardsThatCanBeDoubled = Rank.getSetAllRanks();

        hr.canSwap = false;
        hr.numHandsDealt = 1;
        hr.pushOnDealerHard22 = false;

        hr.scoreOfHardHandsPairsThatCanBeFreeDoubled = new HashSet<Integer>();
        hr.ranksWithFreeBetAfterSplit = EnumSet.noneOf(Rank.class);

        hr.canEarlySurrender = false;
        hr.canLateSurrender = false;
        hr.player21AlwaysWins = false;
        hr.hasDoubleDownRescue = false;

        return hr;
    }

    /**
     * The configured rules the engine does not play, one line each saying why, or an empty
     * list when it plays all of them.
     *
     * These fields can describe more games than the engine was written to deal. Some are
     * never read, and a few are read only in part, which is worse, because setting one
     * changes something and so looks like it worked. Either way a run would go ahead and
     * produce tables that look like any others while describing a game nobody configured,
     * so the Simulation constructor refuses them instead.
     *
     * blackjackOnSplitPairs is left out on purpose. The engine pays a split ace and ten as
     * an ordinary 21 while the Montreal rules say it is a blackjack, and which of the two is
     * right about Montreal is an open question rather than something to settle by refusing
     * to run. possibleSideBets is left out because declining a side bet is always a legal
     * way to play the main game.
     */
    public List<String> unplayableRules(){
        List<String> unplayable = new ArrayList<>();
        if(!dealerPeeksBlackjack){
            unplayable.add("dealerPeeksBlackjack is false: no-peek is not modelled, since the "
                    + "MetaDealer drops every dealer natural either way and so the table run "
                    + "never prices one");
        }
        if(canEarlySurrender){
            unplayable.add("canEarlySurrender is true: surrender is modelled as late surrender "
                    + "only, after a dealer natural has been settled");
        }
        if(hasDoubleDownRescue){
            unplayable.add("hasDoubleDownRescue is true: a doubled hand takes one card and "
                    + "stands, with no rescue");
        }
        if(canSwap){
            unplayable.add("canSwap is true: the engine plays one hand and has no swap");
        }
        if(pushOnDealerHard22){
            unplayable.add("pushOnDealerHard22 is true: it cannot be priced, since the outcome "
                    + "code pushes only a player 21 against a dealer 22 and MetaDealerResult "
                    + "counts a 22 in the same bin as every other bust");
        }
        if(player21AlwaysWins){
            unplayable.add("player21AlwaysWins is true: the peek settles a dealer natural "
                    + "before the player can make a 21");
        }
        if(numHandsDealt != 1){
            unplayable.add("numHandsDealt is " + numHandsDealt
                    + ": the engine deals one hand a round");
        }
        if(!notSplitCardsThatCanBeDoubled.equals(EnumSet.allOf(Rank.class))){
            unplayable.add("notSplitCardsThatCanBeDoubled leaves out "
                    + EnumSet.complementOf(notSplitCardsThatCanBeDoubled)
                    + ": the engine lets every two-card hand double");
        }
        if(!scoreOfHardHandsPairsThatCanBeFreeDoubled.isEmpty()){
            unplayable.add("scoreOfHardHandsPairsThatCanBeFreeDoubled is "
                    + scoreOfHardHandsPairsThatCanBeFreeDoubled
                    + ": the engine has no free doubles");
        }
        if(!ranksWithFreeBetAfterSplit.isEmpty()){
            unplayable.add("ranksWithFreeBetAfterSplit is " + ranksWithFreeBetAfterSplit
                    + ": the engine has no free splits");
        }
        // Every card is worth at least one, so an ace and twenty-one more cards is a bust.
        // A limit of 21 cards after the split can never bind; anything lower is a limit.
        if(!canHitAfterSplittingAces && maxNumCardsAfterSplittingAces != 1){
            unplayable.add("maxNumCardsAfterSplittingAces is " + maxNumCardsAfterSplittingAces
                    + " while canHitAfterSplittingAces is false: split aces that cannot "
                    + "hit take exactly one card each");
        }
        if(canHitAfterSplittingAces && maxNumCardsAfterSplittingAces < 21){
            unplayable.add("maxNumCardsAfterSplittingAces is " + maxNumCardsAfterSplittingAces
                    + " while canHitAfterSplittingAces is true: split aces that can hit "
                    + "are hit with no card limit");
        }
        return unplayable;
    }

    /** Throws, naming every rule unplayableRules lists, unless it lists none. */
    public void requirePlayable(){
        List<String> unplayable = unplayableRules();
        if(!unplayable.isEmpty()){
            throw new IllegalArgumentException("the engine does not play these house rules, "
                    + "so a run would describe a different game: "
                    + String.join("; ", unplayable));
        }
    }

    public BasicDBObject getDBOject(){

        BasicDBObject rtcbddasObject = new BasicDBObject("_id", this.ranksThatCanBeDoubledDownAfterSplit.hashCode());
        rtcbddasObject.append("ranksThatCanBeDoubledDownAfterSplit", DBUtilities.getObjectFromEnumRankSet(this.ranksThatCanBeDoubledDownAfterSplit));
        BasicDBObject psbObject = new BasicDBObject("_id", this.possibleSideBets.hashCode());
        psbObject.append("possibleSideBets", DBUtilities.getObjectFromEnumPSBMSet(this.possibleSideBets));
        BasicDBObject nsctcbdObject = new BasicDBObject("_id", this.notSplitCardsThatCanBeDoubled.hashCode());
        nsctcbdObject.append("notSplitCardsThatCanBeDoubled", DBUtilities.getObjectFromEnumRankSet(this.notSplitCardsThatCanBeDoubled));
        BasicDBObject sohhptcbfdObject = new BasicDBObject("_id", this.scoreOfHardHandsPairsThatCanBeFreeDoubled.hashCode());
        sohhptcbfdObject.append("scoreOfHardHandsPairsThatCanBeFreeDoubled", DBUtilities.getObjectFromIntSet(this.scoreOfHardHandsPairsThatCanBeFreeDoubled));
        BasicDBObject rwfbasObject = new BasicDBObject("_id", this.ranksWithFreeBetAfterSplit.hashCode());
        rwfbasObject.append("ranksWithFreeBetAfterSplit", DBUtilities.getObjectFromEnumRankSet(this.ranksWithFreeBetAfterSplit));

        BasicDBObject houseRulesObject = new BasicDBObject("_id", this.hashCode())
                .append("numDecks", numDecks)
                .append("penetrationPercentage", penetrationPercentage)
                .append("hitsOnSoft17", hitsOnSoft17)
                .append("blackjackPayout", blackjackPayout)
                .append("numSplitAces", numSplitsAces)
                .append("numSplitsNotAces", numSplitsNotAces)
                .append("ranksThatCanBeDoubledDownAfterSplit", rtcbddasObject)
                .append("canHitAfterSplittingAces", canHitAfterSplittingAces)
                .append("maxNumCardsAfterSplittingAces", maxNumCardsAfterSplittingAces)
                .append("dealerPeeksBlackjack", dealerPeeksBlackjack)
                .append("blackjackOnSplitPairs", blackjackOnSplitPairs)
                .append("possibleSideBets", psbObject)
                .append("notSplitCardsThatCanBeDoubled", nsctcbdObject)
                .append("canSwap", canSwap)
                .append("numHandsDealt", numHandsDealt)
                .append("pushOnDealerHard22", pushOnDealerHard22)
                .append("scoreOfHardHandsPairsThatCanBeFreeDoubled", sohhptcbfdObject)
                .append("ranksWithFreeBetAfterSplit", rwfbasObject)
                .append("canEarlySurrender", canEarlySurrender)
                .append("canLateSurrender", canLateSurrender)
                .append("player21AlwaysWins", player21AlwaysWins)
                .append("hasDoubleDownRescue", hasDoubleDownRescue);
        return houseRulesObject;
    }

    public static HouseRules getHouseRulesFromObject(BasicDBObject houseRulesObject){
        HouseRules hr = getMtlCasino25MinBlackjackParams(0);

        hr.numDecks = (int) houseRulesObject.get("numDecks");
        hr.penetrationPercentage = (int) houseRulesObject.get("penetrationPercentage");
        hr.hitsOnSoft17 = (boolean) houseRulesObject.get("hitsOnSoft17");
        hr.blackjackPayout = (double) houseRulesObject.get("blackjackPayout");
        hr.numSplitsNotAces = (int) houseRulesObject.get("numSplitsNotAces");
        hr.numSplitsAces = (int) houseRulesObject.get("numSplitAces");
        hr.ranksThatCanBeDoubledDownAfterSplit = DBUtilities.getEnumRankSetFromObject((BasicDBObject) houseRulesObject.get("ranksThatCanBeDoubledDownAfterSplit"), "ranksThatCanBeDoubledDownAfterSplit");
        hr.canHitAfterSplittingAces = (boolean) houseRulesObject.get("canHitAfterSplittingAces");
        hr.maxNumCardsAfterSplittingAces = (int) houseRulesObject.get("maxNumCardsAfterSplittingAces");
        hr.dealerPeeksBlackjack = (boolean)  houseRulesObject.get("dealerPeeksBlackjack");

        hr.blackjackOnSplitPairs = (boolean) houseRulesObject.get("blackjackOnSplitPairs");
        hr.possibleSideBets = DBUtilities.getEnumPSBMSetFromObject((BasicDBObject) houseRulesObject.get("possibleSideBets"), "possibleSideBets");
        hr.notSplitCardsThatCanBeDoubled = DBUtilities.getEnumRankSetFromObject((BasicDBObject) houseRulesObject.get("notSplitCardsThatCanBeDoubled"), "notSplitCardsThatCanBeDoubled");

        hr.canSwap = (boolean) houseRulesObject.get("canSwap");
        hr.numHandsDealt = (int) houseRulesObject.get("numHandsDealt");
        hr.pushOnDealerHard22 = (boolean) houseRulesObject.get("pushOnDealerHard22");

        hr.scoreOfHardHandsPairsThatCanBeFreeDoubled = DBUtilities.getIntSetFromObject((BasicDBObject) houseRulesObject.get("scoreOfHardHandsPairsThatCanBeFreeDoubled"), "scoreOfHardHandsPairsThatCanBeFreeDoubled");
        hr.ranksWithFreeBetAfterSplit = DBUtilities.getEnumRankSetFromObject((BasicDBObject) houseRulesObject.get("ranksWithFreeBetAfterSplit"), "ranksWithFreeBetAfterSplit");

        hr.canEarlySurrender = (boolean) houseRulesObject.get("canEarlySurrender");
        hr.canLateSurrender = (boolean) houseRulesObject.get("canLateSurrender");
        hr.player21AlwaysWins = (boolean) houseRulesObject.get("player21AlwaysWins");
        hr.hasDoubleDownRescue = (boolean) houseRulesObject.get("hasDoubleDownRescue");

        return hr;
    }

    //mtlCasino25MinBlackjack
    //mtlCasino10MinBlackjack
    //blackjackSwitch
    //freeBetBlackjack
    //spanish21Blackjack
}
