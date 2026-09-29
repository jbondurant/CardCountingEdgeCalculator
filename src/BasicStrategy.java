/**
 * Basic strategy for 4 to 8 decks, the dealer hitting soft 17, double after split: the
 * total-dependent chart, as a RoundPolicy.
 *
 * It is the play after every first move when counting systems are compared, and the play
 * of the rounds that take cards out of a shoe. BasicStrategyTest holds it to ExactRound
 * off the top of an eight-deck shoe.
 *
 * Surrender follows the late-surrender chart for these rules: 15 against a ten or an ace,
 * 16 against a 9, a ten or an ace, 17 against an ace, and 8,8 against an ace. Montreal
 * offers no surrender, so under its rules those rows are never reached.
 */
public final class BasicStrategy {

    private BasicStrategy() {
    }

    /** The chart. */
    public static RoundPolicy chart() {
        return policy(false);
    }

    /**
     * The chart, or with alwaysResplit a variant that splits every pair it may, which the
     * timing report uses for the worst case of a split.
     */
    public static RoundPolicy policy(boolean alwaysResplit) {
        return d -> {
            int t = d.total;
            int up = d.upCard == 1 ? 11 : d.upCard;
            if (d.canSurrender && surrenders(d, t, up)) {
                return PlayerMove.Surrender;
            }
            if (d.canSplit && (alwaysResplit || splitsPair(d.pairRank, up))) {
                return PlayerMove.Split;
            }
            if (d.soft) {
                if (t >= 19) {
                    return t == 19 && up == 6 && d.canDouble ? PlayerMove.Double : PlayerMove.Stand;
                }
                if (t == 18) {
                    if (up <= 6 && d.canDouble) {
                        return PlayerMove.Double;
                    }
                    return up <= 8 || !d.canHit ? PlayerMove.Stand : PlayerMove.Hit;
                }
                boolean doubles = (t == 17 && up >= 3 && up <= 6) || (t >= 15 && up >= 4 && up <= 6)
                        || (t >= 13 && up >= 5 && up <= 6);
                if (doubles && d.canDouble) {
                    return PlayerMove.Double;
                }
                return d.canHit ? PlayerMove.Hit : PlayerMove.Stand;
            }
            if (t >= 17 || !d.canHit) {
                return PlayerMove.Stand;
            }
            if (t >= 13) {
                return up <= 6 ? PlayerMove.Stand : PlayerMove.Hit;
            }
            if (t == 12) {
                return up >= 4 && up <= 6 ? PlayerMove.Stand : PlayerMove.Hit;
            }
            if (t == 11) {
                return d.canDouble ? PlayerMove.Double : PlayerMove.Hit;
            }
            if (t == 10) {
                return d.canDouble && up <= 9 ? PlayerMove.Double : PlayerMove.Hit;
            }
            if (t == 9) {
                return d.canDouble && up >= 3 && up <= 6 ? PlayerMove.Double : PlayerMove.Hit;
            }
            return PlayerMove.Hit;
        };
    }

    /**
     * A short digest of the chart's answer to every decision it can be asked, so a run's
     * file records which chart played it and refuses to be resumed under another.
     */
    public static String fingerprint(RoundPolicy policy) {
        long h = 1125899906842597L;
        boolean[] tf = {false, true};
        for (int up = 1; up <= 10; up++) {
            for (int total = 4; total <= 21; total++) {
                for (boolean soft : tf) {
                    for (int pair = 0; pair <= 10; pair++) {
                        for (boolean fromSplit : tf) {
                            for (boolean first : tf) {
                                for (int legal = 0; legal < 16; legal++) {
                                    RoundPolicy.Decision d = new RoundPolicy.Decision(total, soft, first ? 2 : 3,
                                            pair, up, fromSplit, first, (legal & 1) != 0, (legal & 2) != 0,
                                            (legal & 4) != 0, (legal & 8) != 0);
                                    h = 31 * h + policy.choose(d).ordinal();
                                }
                            }
                        }
                    }
                }
            }
        }
        return Long.toHexString(h);
    }

    private static boolean surrenders(RoundPolicy.Decision d, int t, int up) {
        if (d.soft) {
            return false;
        }
        if (d.pairRank == 8) {
            return up == 11;
        }
        if (d.pairRank != 0) {
            return false;
        }
        return (t == 15 && up >= 10) || (t == 16 && up >= 9) || (t == 17 && up == 11);
    }

    private static boolean splitsPair(int rank, int up) {
        switch (rank) {
            case 1:
            case 8:
                return true;
            case 2:
            case 3:
            case 7:
                return up <= 7;
            case 4:
                return up == 5 || up == 6;
            case 6:
                return up <= 6;
            case 9:
                return up <= 9 && up != 7;
            default:
                return false;
        }
    }
}
