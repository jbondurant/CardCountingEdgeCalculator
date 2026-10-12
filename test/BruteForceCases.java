import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A fixed list of small rounds for BruteForceRound, drawn from a seed: shoes of 5 to 11 cards,
 * a deal, a first move legal on it, a later-decision policy and one of four rule sets. Shared
 * by the tests that hold BruteForceRound's walk to what it gave before distributionOfFirstMove
 * was added (the value of every case to the bit), and that hold the distribution's mean to the
 * value.
 *
 * The shoes are this small so that every case is followed card by card in milliseconds. Some
 * cases run the shoe out; those throw, and the throw is part of what is held fixed.
 */
final class BruteForceCases {

    private BruteForceCases() {
    }

    /** One round to value. */
    static final class Case {
        final int index;
        final RoundRules rules;
        final String rulesName;
        final int[] shoe;
        final int p1;
        final int p2;
        final int up;
        final PlayerMove first;
        final RoundPolicy policy;
        final String policyName;

        Case(int index, RoundRules rules, String rulesName, int[] shoe, int p1, int p2, int up, PlayerMove first,
             RoundPolicy policy, String policyName) {
            this.index = index;
            this.rules = rules;
            this.rulesName = rulesName;
            this.shoe = shoe;
            this.p1 = p1;
            this.p2 = p2;
            this.up = up;
            this.first = first;
            this.policy = policy;
            this.policyName = policyName;
        }

        @Override
        public String toString() {
            StringBuilder s = new StringBuilder("case " + index + ": " + rulesName + ", shoe [");
            for (int r = 1; r <= 10; r++) {
                s.append(r > 1 ? " " : "").append(shoe[r]);
            }
            return s + "], " + p1 + "," + p2 + " vs " + up + ", " + first + ", then " + policyName;
        }
    }

    static final String[] RULE_NAMES = {"S17, no DAS, 4 hands", "H17, DAS, surrender", "H17, DAS on split pairs, BJ on split pairs, hit split aces", "S17, 2 hands, surrender after split"};

    static RoundRules rules(int k) {
        boolean[] none = new boolean[11];
        boolean[] all = new boolean[11];
        for (int r = 1; r <= 10; r++) {
            all[r] = true;
        }
        switch (k) {
            case 0:
                return new RoundRules(false, 1.5, 2, 4, false, none, false, false, false);
            case 1:
                return new RoundRules(true, 1.5, 2, 4, false, all, false, true, false);
            case 2:
                return new RoundRules(true, 1.5, 4, 4, true, all, true, false, false);
            case 3:
                return new RoundRules(false, 1.2, 2, 2, false, all, false, true, true);
            default:
                throw new IllegalArgumentException("rules " + k);
        }
    }

    static final String[] POLICY_NAMES = {"stand", "hit below 17", "split, else double, else hit below 15",
            "a chart-like policy", "surrender, else hit below 13"};

    static RoundPolicy policy(int k) {
        switch (k) {
            case 0:
                return d -> PlayerMove.Stand;
            case 1:
                return d -> d.canHit && d.total < 17 ? PlayerMove.Hit : PlayerMove.Stand;
            case 2:
                return d -> d.canSplit ? PlayerMove.Split : d.canDouble ? PlayerMove.Double
                        : d.canHit && d.total < 15 ? PlayerMove.Hit : PlayerMove.Stand;
            case 3:
                return d -> {
                    if (d.canSplit && (d.pairRank == 1 || d.pairRank == 8)) {
                        return PlayerMove.Split;
                    }
                    if (d.canDouble && !d.soft && d.total >= 9 && d.total <= 11) {
                        return PlayerMove.Double;
                    }
                    if (d.canSurrender && !d.soft && d.total == 16 && d.upCard == 10) {
                        return PlayerMove.Surrender;
                    }
                    int stand = d.soft ? 18 : d.upCard >= 7 || d.upCard == 1 ? 17 : 13;
                    return d.canHit && d.total < stand ? PlayerMove.Hit : PlayerMove.Stand;
                };
            case 4:
                return d -> d.canSurrender ? PlayerMove.Surrender
                        : d.canHit && d.total < 13 ? PlayerMove.Hit : PlayerMove.Stand;
            default:
                throw new IllegalArgumentException("policy " + k);
        }
    }

    /**
     * What BruteForceRound.valueOfFirstMove gives on every case, as one SHA-256: each case's
     * value as its 64 bits, or the class of what it threw. Equal fingerprints mean every value
     * is the same to the bit and every throw the same kind.
     */
    static String fingerprint(List<Case> cases) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (Case c : cases) {
                String outcome;
                try {
                    double v = new BruteForceRound(c.rules).valueOfFirstMove(c.shoe, c.p1, c.p2, c.up, c.first,
                            c.policy);
                    outcome = Long.toHexString(Double.doubleToRawLongBits(v));
                } catch (RuntimeException e) {
                    outcome = e.getClass().getSimpleName();
                }
                md.update((c.index + " " + outcome + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) {
                b.append(String.format("%02x", x));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Prints the fingerprint of the first N cases (default 400) and how many threw. */
    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 400;
        List<Case> cases = cases(n);
        int threw = 0;
        long t0 = System.nanoTime();
        for (Case c : cases) {
            try {
                new BruteForceRound(c.rules).valueOfFirstMove(c.shoe, c.p1, c.p2, c.up, c.first, c.policy);
            } catch (RuntimeException e) {
                threw++;
            }
        }
        System.out.printf("%d cases, %d threw, %.2f s; fingerprint %s%n", n, threw, (System.nanoTime() - t0) / 1e9,
                fingerprint(cases));
    }

    /** A rank as a shoe would give it: a ten 4 times in 13, any other rank once in 13. */
    private static int rank(SplittableRandom rnd) {
        int k = rnd.nextInt(13);
        return k >= 9 ? 10 : k + 1;
    }

    /**
     * The cases, the same on every call. A pair is dealt one time in four so that splits, up
     * to four hands, come up often; the first move is drawn from the moves legal on the deal
     * (found by asking BruteForceRound, which refuses an illegal first move before it deals).
     */
    static List<Case> cases(int n) {
        SplittableRandom rnd = new SplittableRandom(20261012L);
        List<Case> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int rk = rnd.nextInt(RULE_NAMES.length);
            int pk = rnd.nextInt(POLICY_NAMES.length);
            int[] shoe = new int[11];
            int cards = 5 + rnd.nextInt(7);
            for (int c = 0; c < cards; c++) {
                shoe[rank(rnd)]++;
            }
            int p1 = rank(rnd);
            int p2 = rnd.nextInt(4) == 0 ? p1 : rank(rnd);
            int up = rank(rnd);
            RoundRules rules = rules(rk);
            BruteForceRound v = new BruteForceRound(rules);
            List<PlayerMove> legal = new ArrayList<>();
            for (PlayerMove m : PlayerMove.values()) {
                try {
                    v.valueOfFirstMove(new int[11], p1, p2, up, m, d -> PlayerMove.Stand);
                } catch (IllegalArgumentException e) {
                    continue;
                } catch (IllegalStateException e) {
                    // Legal: refused only once the empty shoe could give no hole card.
                }
                legal.add(m);
            }
            PlayerMove first = legal.get(rnd.nextInt(legal.size()));
            out.add(new Case(i, rules, RULE_NAMES[rk], shoe, p1, p2, up, first, policy(pk), POLICY_NAMES[pk]));
        }
        return out;
    }
}
