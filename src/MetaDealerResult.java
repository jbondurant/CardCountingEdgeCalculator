public class MetaDealerResult {
    public long num17;
    public long num18;
    public long num19;
    public long num20;
    public long num21;
    public long numBust;
    //do not put numBlackjack, cause then you will lose more often on 21,
    // and it will cause you to not double on a h11 vs dealer Ace

    public boolean isCompleted(int minMD){
        if(num17 < minMD || num18 < minMD || num19 < minMD){
            return false;
        }
        if(num20 < minMD || num21 < minMD || numBust < minMD){
            return false;
        }
        return true;
    }


    public String getString(){
        String s = "";
        s += num17 + "&";
        s += num18 + "&";
        s += num19 + "&";
        s += num20 + "&";
        s += num21 + "&";
        s += numBust;
        return s;
    }

    public static MetaDealerResult getMetaDealerResultFromString(String s){
        String[] parts = s.split("&");
        MetaDealerResult mdr = new MetaDealerResult();
        mdr.num17 = Long.parseLong(parts[0]);
        mdr.num18 = Long.parseLong(parts[1]);
        mdr.num19 = Long.parseLong(parts[2]);
        mdr.num20 = Long.parseLong(parts[3]);
        mdr.num21 = Long.parseLong(parts[4]);
        mdr.numBust = Long.parseLong(parts[5]);
        return mdr;
    }

    public void insertEvent(int dealerBestScore, boolean dealerHasBlackJack){
        if(dealerHasBlackJack){
            return;
        }
        if(dealerBestScore > 21){
            numBust++;
        }
        else if(dealerBestScore == 21){
            num21++;
        }
        else if(dealerBestScore == 20){
            num20++;
        }
        else if(dealerBestScore == 19){
            num19++;
        }
        else if(dealerBestScore == 18){
            num18++;
        }
        else if(dealerBestScore == 17){
            num17++;
        }
    }
}
