/**
 * How long ExactRound takes on a real eight-deck shoe, for a few rounds that range from one
 * draw to four split hands.
 *
 * The shoe is the Montreal one, 32 of each rank from ace to nine and 128 tens, less the
 * player's two cards and the up-card. The rules are Montreal's (dealer hits soft 17, 3:2,
 * double after any split but aces, split aces take one card, no surrender), with the split
 * limits each case names. Every decision after the first move follows a basic-strategy-
 * like policy, written here so that the numbers do not depend on any solved table.
 *
 * Each case is valued a few times in one JVM, with a fresh ExactRound each time. The first
 * run includes the JIT warming up; the fastest is closer to what a long batch of calls
 * would see. Run it after ./run-tests.sh has compiled the sources:
 * java -cp build/classes ExactRoundTimingReport [runs per case].
 */
public class ExactRoundTimingReport {

    /**
     * Basic strategy for a multi-deck game where the dealer hits soft 17, close enough to
     * give the rounds a realistic shape. With alwaysResplit, every pair that may split does.
     */
    static RoundPolicy basicStrategy(boolean alwaysResplit) {
        return d -> {
            int t = d.total;
            int up = d.upCard == 1 ? 11 : d.upCard;
            if (d.canSplit && (alwaysResplit || splitsPair(d.pairRank, up))) {
                return PlayerMove.Split;
            }
            if (d.canSurrender && !d.soft && ((t == 16 && up >= 9) || (t == 15 && up == 10))) {
                return PlayerMove.Surrender;
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

    static RoundRules montreal(int maxHandsAces, int maxHandsNotAces) {
        boolean[] das = new boolean[11];
        for (int r = 2; r <= 10; r++) {
            das[r] = true;
        }
        return new RoundRules(true, 1.5, maxHandsAces, maxHandsNotAces, false, das, false, false, false);
    }

    static int[] eightDecksLess(int... dealt) {
        int[] shoe = new int[11];
        for (int r = 1; r <= 9; r++) {
            shoe[r] = 32;
        }
        shoe[10] = 128;
        for (int c : dealt) {
            shoe[c]--;
        }
        return shoe;
    }

    private static void time(String name, RoundRules rules, int p1, int p2, int up, PlayerMove first,
                             RoundPolicy policy, int runs) {
        int[] shoe = eightDecksLess(p1, p2, up);
        double value = 0;
        long firstNanos = 0;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < runs; i++) {
            // A fresh ExactRound each run, so no run reuses another's dealer hands.
            ExactRound exact = new ExactRound(rules);
            long start = System.nanoTime();
            value = exact.valueOfFirstMove(shoe, p1, p2, up, first, policy);
            long took = System.nanoTime() - start;
            if (i == 0) {
                firstNanos = took;
            }
            best = Math.min(best, took);
        }
        System.out.printf("%-58s %+.15f   first %9.1f ms   fastest %9.1f ms%n", name, value,
                firstNanos / 1e6, best / 1e6);
    }

    public static void main(String[] args) {
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        RoundPolicy basic = basicStrategy(false);
        RoundPolicy resplit = basicStrategy(true);
        time("hard 16 (10,6) vs 10, Hit", montreal(2, 4), 10, 6, 10, PlayerMove.Hit, basic, runs);
        time("11 (6,5) vs 6, Double", montreal(2, 4), 6, 5, 6, PlayerMove.Double, basic, runs);
        time("A,A vs 6, Split, aces to 2 hands", montreal(2, 4), 1, 1, 6, PlayerMove.Split, basic, runs);
        time("8,8 vs 10, Split, 4 hands, always resplit", montreal(2, 4), 8, 8, 10, PlayerMove.Split, resplit, runs);
        time("10,10 vs 6, Split, 4 hands, basic (stands on 20)", montreal(2, 4), 10, 10, 6, PlayerMove.Split, basic, runs);
        time("10,10 vs 6, Split, 4 hands, always resplit", montreal(2, 4), 10, 10, 6, PlayerMove.Split, resplit, runs);
        // Two harder shapes: small cards, so every hand takes many draws.
        time("2,2 vs 6, Split, 4 hands, always resplit", montreal(2, 4), 2, 2, 6, PlayerMove.Split, resplit, runs);
        time("A,2 vs 2, Hit", montreal(2, 4), 1, 2, 2, PlayerMove.Hit, basic, runs);
    }
}
