import com.mongodb.BasicDBObject;
import com.mongodb.DB;

import java.util.EnumSet;
import java.util.HashSet;

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
        hr.numSplitsNotAces = 3;// could also be 4
        hr.numSplitsAces = 1;//
        hr.ranksThatCanBeDoubledDownAfterSplit = Rank.getSetAllRanksExceptAce();
        hr.canHitAfterSplittingAces = false;
        hr.maxNumCardsAfterSplittingAces = 1;
        hr.dealerPeeksBlackjack = true;// I think

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
