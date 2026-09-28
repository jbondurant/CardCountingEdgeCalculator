import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExactRound held to BruteForceRound on random rounds, and to the infinite deck on big
 * shoes.
 *
 * The two were written independently from ROUND_CONTRACT.md. BruteForceRound follows every
 * card in the order the contract deals it, so on a shoe small enough for it to finish, the
 * two must return the same value, to within 1e-12, or throw the same kind of exception,
 * and ask the policy about the same decisions. The random rounds draw shoes of 14 to 24
 * cards of varied make-up, every RoundRules field, a family of deterministic policies, and
 * every first move, legal or not. Two more sets aim at what those reach least: shoes of 1
 * to 13 cards, where rounds run out of cards, and splits on shoes of 30 to 50 cards, which
 * ExactRound values hand by hand. The features that are easy to miss are counted, the
 * counts are printed, and each must be reached many times among the rounds both valued.
 *
 * Two points of the contract can be read two ways, and each has a hand-worked case below
 * that both must match: a split ace that may not be hit has a first decision like any
 * split hand, whatever card it drew, and an illegal first move is refused before anything
 * is dealt. The random rounds reach both: split aces that double and surrender, on another
 * ace and on any other card, are among the counted features, and illegal first moves are
 * compared on every shoe, empty ones too.
 */
public class ExactVsBruteForceTest {

    private static final double TOLERANCE = 1e-12;

    // ------------------------------------------------------------------ the random rounds

    private static final long SEED = 0x5EED_2026_0927L;
    /**
     * The number of random cases. It, the seed and WORK can be changed with -Dcrosscheck.cases,
     * -Dcrosscheck.seed and -Dcrosscheck.work, for a longer search than the test's.
     */
    private static final int CASES = Integer.getInteger("crosscheck.cases", 3000);

    /**
     * A limit on BruteForceRound's work on one round, which is about the number of player
     * paths times the number of orders the dealer can draw in. On a small shoe of small cards
     * a split whose hands hit can take it minutes, so rounds over this are left out. The
     * player paths are counted by the policy's calls, since BruteForceRound asks it at every
     * decision on every path, split aces included. (The only hands it is not asked about
     * after the first move are split blackjacks, which do not need the dealer.) The count,
     * unlike a clock, leaves out the same rounds on every machine.
     */
    private static final long WORK = Long.getLong("crosscheck.work", 1_000_000L);

    @Test
    void exactAgreesWithBruteForceOnRandomTinyRounds() throws InterruptedException {
        Coverage cov = compareRandomRounds(Long.getLong("crosscheck.seed", SEED), CASES, 14, 24);
        cov.print("shoes of 14 to 24 cards");
        int enough = Math.max(1, CASES / 30);
        cov.require(enough, "a split", cov.split);
        cov.require(enough, "a re-split", cov.resplit);
        cov.require(enough, "the hand limit binding", cov.limitBinds);
        cov.require(enough, "split aces re-split", cov.acesResplit);
        cov.require(enough, "a split blackjack paid", cov.splitBlackjack);
        cov.require(enough, "a surrender after a split", cov.surrenderAfterSplit);
        cov.require(enough, "the peek with an ace up", cov.peekAceUp);
        cov.require(enough, "the peek with a ten up", cov.peekTenUp);
        cov.require(enough, "the dealer hitting soft 17", cov.dealerHitsSoft17);
        cov.require(enough, "a split ace doubling on another card", cov.splitAceDoubledOnCard);
        cov.require(enough, "a split ace doubling on another ace", cov.splitAceDoubledOnAce);
        cov.require(enough, "a split ace surrendering on another card", cov.splitAceSurrenderedOnCard);
        cov.require(enough, "a split ace surrendering on another ace", cov.splitAceSurrenderedOnAce);
    }

    /**
     * The same on shoes of 1 to 13 cards, where rounds run out of cards. Which draw finds the
     * shoe empty is where the two differ most in how they work: BruteForceRound deals the
     * hole card first, as the contract does, and ExactRound deals it last.
     */
    @Test
    void exactAgreesWithBruteForceWhereTheShoeRunsOut() throws InterruptedException {
        int cases = CASES / 2;
        Coverage cov = compareRandomRounds(Long.getLong("crosscheck.seed", SEED) + 1, cases, 1, 13);
        cov.print("shoes of 1 to 13 cards");
        int enough = Math.max(1, cases / 5);
        cov.require(enough, "a round valued", cov.valued);
        cov.require(enough, "a round that runs out of cards", cov.illegalState);
        cov.require(enough / 10, "a split valued", cov.split);
    }

    /**
     * Compares the two on this many random cases, every legal first move of each, on as many
     * threads as there are processors. Each case draws from its own generator, seeded from
     * the case number, so a case is the same whichever thread runs it.
     */
    private static Coverage compareRandomRounds(long seed, int cases, int minCards, int maxCards)
            throws InterruptedException {
        return compareRandomRounds(cases, WORK, false, i -> randomCase(seed, i, minCards, maxCards));
    }

    private static Coverage compareRandomRounds(int cases, long work, boolean splitOnly,
                                                IntFunction<Case> generator) throws InterruptedException {
        long start = System.nanoTime();
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Coverage>> parts = new ArrayList<>();
        for (int i = 0; i < cases; i++) {
            int index = i;
            parts.add(pool.submit(() -> {
                Coverage cov = new Coverage();
                Case c = generator.apply(index);
                List<PlayerMove> legal = legalFirstMoves(c);
                for (PlayerMove first : legal) {
                    if (!splitOnly || first == PlayerMove.Split) {
                        compare(c, first, work, cov);
                    }
                }
                if (!splitOnly) {
                    for (PlayerMove first : PlayerMove.values()) {
                        if (!legal.contains(first)) {
                            compareIllegalFirst(c, first, cov);
                        }
                    }
                }
                return cov;
            }));
        }
        pool.shutdown();
        Coverage total = new Coverage();
        try {
            // In case order, so the failure reported is the same whatever the threads did.
            for (Future<Coverage> part : parts) {
                total.add(part.get());
            }
        } catch (ExecutionException e) {
            pool.shutdownNow();
            if (e.getCause() instanceof AssertionError) {
                throw (AssertionError) e.getCause();
            }
            throw new AssertionError(e.getCause());
        }
        total.cases = cases;
        total.seconds = (System.nanoTime() - start) / 1e9;
        return total;
    }

    /**
     * Splits on shoes of 30 to 50 cards, mostly high ones, where ExactRound values a split
     * hand by hand rather than coupled. It does that only on a shoe that cannot run out,
     * which among the shoes above only some rich in tens are. Only the Split first move is
     * compared, since the others do not take that path.
     */
    @Test
    void exactAgreesWithBruteForceOnSplitsValuedHandByHand() throws InterruptedException {
        long seed = Long.getLong("crosscheck.seed", SEED) + 2;
        int cases = CASES / 5;
        Coverage cov = compareRandomRounds(cases, 5 * WORK, true, i -> mediumSplitCase(seed, i));
        cov.print("split rounds on shoes of 30 to 50 cards");
        assertEquals(cov.split, cov.handByHand, "a split here that ExactRound values coupled");
        int enough = Math.max(1, cases / 10);
        cov.require(enough, "a split", cov.split);
        cov.require(enough / 2, "a re-split", cov.resplit);
        cov.require(enough / 5, "split aces re-split", cov.acesResplit);
        // ExactRound prices each finished split hand on its own here, a split ace included,
        // so what a split ace does with its first decision goes through that path too.
        cov.require(enough / 5, "a split ace doubling on another card", cov.splitAceDoubledOnCard);
        cov.require(enough / 5, "a split ace doubling on another ace", cov.splitAceDoubledOnAce);
        cov.require(enough / 5, "a split ace surrendering on another card", cov.splitAceSurrenderedOnCard);
        cov.require(enough / 5, "a split ace surrendering on another ace", cov.splitAceSurrenderedOnAce);
    }

    /**
     * A pair to split on a shoe of 30 to 50 cards of a few ranks, mostly high, redrawn until
     * the shoe passes the bound under which ExactRound values the split hand by hand. Half
     * the aces are aimed at what a split ace that may not be hit can do on its first
     * decision.
     */
    private static Case mediumSplitCase(long seed, int index) {
        Random rnd = new Random(mix(seed + index));
        int rank = rnd.nextDouble() < 0.7 ? new int[]{1, 7, 8, 9, 10}[rnd.nextInt(5)] : 1 + rnd.nextInt(10);
        double u = rnd.nextDouble();
        int up = u < 0.2 ? 1 : u < 0.4 ? 10 : 1 + rnd.nextInt(10);
        boolean[] das = new boolean[11];
        for (int r = 1; r <= 10; r++) {
            das[r] = rnd.nextBoolean();
        }
        int limit = 2 + rnd.nextInt(3);
        // Half the aces are aces that may not be hit, played by a policy that doubles,
        // surrenders or splits them wherever it may, under rules that often let it.
        boolean acesAct = rank == 1 && rnd.nextBoolean();
        boolean acesSurrender = acesAct && rnd.nextDouble() < 0.7;
        if (acesAct) {
            das[1] = rnd.nextDouble() < 0.7;
        }
        boolean hitsSoft17 = rnd.nextBoolean();
        double payout = rnd.nextBoolean() ? 1.5 : 1.2;
        int maxAces = rank == 1 ? limit : 1 + rnd.nextInt(4);
        int maxOthers = rank == 1 ? 1 + rnd.nextInt(4) : limit;
        boolean canHitSplitAces = !acesAct && rnd.nextBoolean();
        boolean splitBlackjack = rnd.nextBoolean();
        boolean surrender = acesSurrender || rnd.nextDouble() < 0.6;
        boolean surrenderAfterSplit = acesSurrender || rnd.nextBoolean();
        RoundRules rules = new RoundRules(hitsSoft17, payout, maxAces, maxOthers, canHitSplitAces, das,
                splitBlackjack, surrender, surrenderAfterSplit);
        // Few ranks, and mostly high ones, so that hands and the dealer end soon and
        // BruteForceRound has few branches to follow. A shoe that fails the bound is drawn
        // again; with an ace or ten up, a shoe of mostly naturals may keep failing it, so after
        // a while the up-card changes to one that cannot make a natural.
        int[] shoe;
        int attempts = 0;
        do {
            if (++attempts > 100 && (up == 1 || up == 10)) {
                up = 2 + rnd.nextInt(8);
            }
            double[] weight = new double[11];
            weight[10] = 8;
            weight[rank] += 3;
            for (int extra = rnd.nextInt(4); extra > 0; extra--) {
                int r = rnd.nextDouble() < 0.7 ? 6 + rnd.nextInt(4) : 1 + rnd.nextInt(5);
                weight[r] += 1 + rnd.nextInt(3);
            }
            shoe = randomShoe(rnd, 30 + rnd.nextInt(21), weight);
        } while (!valuedHandByHand(shoe, rank, up, rules));
        int which = acesAct && rnd.nextDouble() < 0.8 ? SPLIT_HANDS_ACT : rnd.nextInt(POLICY_NAMES.length);
        return new Case(index, acesAct ? "hand by hand, split aces act" : "hand by hand", rules, shoe,
                rank, rank, up, POLICY_NAMES[which], policy(which, rnd));
    }

    /**
     * Whether ExactRound values a split of this pair hand by hand: the bound its
     * mostCardsASplitCanDraw documents, restated. The round ends with at most `reachable`
     * hands; each hand's cards but its last add up to 18 at most and the dealer's draws but
     * its last to 14, and no set of cards with that total holds more than the smallest
     * cards do; then one last card per hand, the dealer's last draw, the hole card, and the
     * second cards of the splits. The shoe must hold that many cards, and that many that are
     * not the natural hole card.
     */
    private static boolean valuedHandByHand(int[] shoe, int rank, int up, RoundRules rules) {
        long reachable = Math.min(rules.maxHandsFor(rank), 2L + shoe[rank]);
        long budget = 18 * reachable + 14;
        long smallest = 0;
        for (int r = 1; r <= 10 && budget >= r; r++) {
            long take = Math.min(shoe[r], budget / r);
            smallest += take;
            budget -= take * r;
        }
        long most = 1 + 2 * (reachable - 1) + reachable + 1 + smallest;
        int cards = 0;
        for (int r = 1; r <= 10; r++) {
            cards += shoe[r];
        }
        int naturals = up == 1 ? shoe[10] : up == 10 ? shoe[1] : 0;
        return cards >= most && cards - naturals >= most;
    }

    // ------------------------------------------------------------------ what the contract settled

    /**
     * Whether a split ace that may not be hit may surrender. The contract says a split ace is
     * a split hand like any other except that it may not be hit: whatever card it drew, it
     * has a first decision and may surrender by the same rule as any split hand. Both must
     * give the values worked out here.
     *
     * A,A against a 6, split, with one ace and three tens left; surrender and surrender after
     * split on, split aces take one card, room for three hands, the dealer stands on soft 17.
     * No hole card is a natural. With the hole a ten (3/4) the dealer has 16 and must draw. If
     * the first split hand draws the ace (1/3), it is A,A, the second hand draws a ten for 21,
     * and the dealer draws the last ten and busts. If the first draws a ten (2/3), it has 21,
     * and the second draws the ace (1/2: A,A, the dealer busts on the last ten) or a ten (1/2:
     * 21, the dealer makes 17 on the ace). With the hole the ace (1/4) the dealer stands on
     * soft 17 and both hands draw tens for 21. Every hand is asked, and three policies answer.
     *
     * Standing: soft 12 wins whenever the dealer busts, so every branch pays +2, and the
     * round is worth 2. A reading that never offers a split ace surrender gives this value
     * for the last policy below too.
     *
     * Surrendering A,A and standing on 21: the surrender turns +2 into +0.5 on the two
     * branches where A,A is dealt, each 3/4 x 1/3 = 1/4, so 1/4 x 0.5 + 1/4 x 0.5 + 1/2 x 2 =
     * 1.25. A reading that asks a split ace only when it drew another ace gives this value
     * for the last policy below.
     *
     * Surrendering whenever it may: a split ace holding a ten is an ordinary 21 here, with no
     * blackjack on split pairs, and it too is offered surrender. Every branch is two
     * surrenders, and the round is worth -1 without the dealer drawing at all.
     */
    @Test
    void aSplitAceMaySurrenderWhateverItDrew() {
        RoundRules rules = new RoundRules(false, 1.5, 3, 4, false, new boolean[11], false, true, true);
        int[] shoe = new int[11];
        shoe[1] = 1;
        shoe[10] = 3;
        RoundPolicy stand = d -> PlayerMove.Stand;
        RoundPolicy surrenderAcePairs = d -> d.canSurrender && d.pairRank == 1 ? PlayerMove.Surrender : PlayerMove.Stand;
        RoundPolicy surrenderWhenLegal = d -> d.canSurrender ? PlayerMove.Surrender : PlayerMove.Stand;
        for (RoundValuer v : new RoundValuer[]{new BruteForceRound(rules), new ExactRound(rules)}) {
            String who = v.getClass().getSimpleName();
            assertEquals(2.0, v.valueOfFirstMove(shoe, 1, 1, 6, PlayerMove.Split, stand), TOLERANCE, who);
            assertEquals(1.25, v.valueOfFirstMove(shoe, 1, 1, 6, PlayerMove.Split, surrenderAcePairs),
                    TOLERANCE, who);
            assertEquals(-1.0, v.valueOfFirstMove(shoe, 1, 1, 6, PlayerMove.Split, surrenderWhenLegal),
                    TOLERANCE, who);
        }
    }

    /**
     * Which is checked first when a first move is illegal and the shoe is empty. The contract
     * checks the move before anything is dealt, then deals the hole card, a natural's
     * included. So on an empty shoe an illegal move is IllegalArgumentException and a legal
     * one IllegalStateException, and on a shoe of nothing but natural hole cards both are
     * IllegalArgumentException.
     */
    @Test
    void anIllegalFirstMoveIsRefusedBeforeTheShoeIsDealtFrom() {
        RoundRules rules = new RoundRules(false, 1.5, 2, 4, false, new boolean[11], false, false, false);
        RoundPolicy stand = d -> PlayerMove.Stand;
        int[] empty = new int[11];
        int[] aces = new int[11];
        aces[1] = 3;
        for (RoundValuer v : new RoundValuer[]{new BruteForceRound(rules), new ExactRound(rules)}) {
            String who = v.getClass().getSimpleName();
            assertThrows(IllegalArgumentException.class,
                    () -> v.valueOfFirstMove(empty, 10, 6, 5, PlayerMove.Split, stand), who);
            assertThrows(IllegalArgumentException.class,
                    () -> v.valueOfFirstMove(empty, 10, 6, 5, PlayerMove.Surrender, stand), who);
            assertThrows(IllegalArgumentException.class,
                    () -> v.valueOfFirstMove(empty, 1, 10, 5, PlayerMove.Hit, stand), who);
            assertThrows(IllegalStateException.class,
                    () -> v.valueOfFirstMove(empty, 10, 6, 5, PlayerMove.Hit, stand), who);
            assertThrows(IllegalStateException.class,
                    () -> v.valueOfFirstMove(empty, 1, 10, 5, PlayerMove.Stand, stand), who);
            assertThrows(IllegalArgumentException.class,
                    () -> v.valueOfFirstMove(aces, 10, 6, 10, PlayerMove.Split, stand), who);
            assertThrows(IllegalArgumentException.class,
                    () -> v.valueOfFirstMove(aces, 10, 6, 10, PlayerMove.Stand, stand), who);
        }
    }

    // ------------------------------------------------------------------ big shoes

    /**
     * Standing on 16 to 20 against each up-card, on shoes of more and more decks, comes
     * closer and closer to RandomVsOptimalReport's infinite-deck value: the stand value
     * against its dealer distribution, which is conditioned on the peek and hits soft 17.
     * The gap shrinks as one over the number of decks, the first-order effect of the cards
     * already dealt, so the gap times the decks settles on a constant.
     *
     * ExactRound packs a shoe into 64 bits and refuses more than fifteen decks, so beyond
     * that the values come from BruteForceRound, which standing leaves only the dealer's draws
     * to follow and is quick on any shoe. Where both run, they must agree.
     */
    @Test
    void standingConvergesToTheInfiniteDeck() {
        RandomVsOptimalReport infinite = new RandomVsOptimalReport(19);
        RoundRules rules = new RoundRules(true, 1.5, 2, 4, false, new boolean[11], false, false, false);
        RoundPolicy never = d -> {
            throw new AssertionError("standing asks nothing, but was asked " + d);
        };
        int[] decks = {1, 2, 4, 8, 15, 64, 512};
        StringBuilder table = new StringBuilder("Standing against the infinite deck: the gap at each "
                + "number of decks, and at 512 decks times 512\n");
        for (int total = 16; total <= 20; total++) {
            for (int up = 1; up <= 10; up++) {
                double[] dealer = infinite.dealerDistributionFor(up == 1 ? 11 : up);
                double limit = dealer[5];
                for (int d = 17; d <= 21; d++) {
                    limit += Integer.signum(total - d) * dealer[d - 17];
                }
                table.append(String.format("  %d vs %-2d %+.6f:", total, up, limit));
                double previous = Double.POSITIVE_INFINITY;
                double gap = 0;
                for (int k : decks) {
                    int[] shoe = new int[11];
                    for (int r = 1; r <= 9; r++) {
                        shoe[r] = 4 * k;
                    }
                    shoe[10] = 16 * k;
                    shoe[10]--;
                    shoe[total - 10]--;
                    shoe[up]--;
                    String at = total + " vs " + up + " on " + k + " decks";
                    int second = total - 10;
                    int upCard = up;
                    double brute = new BruteForceRound(rules).valueOfFirstMove(shoe, 10, second, upCard,
                            PlayerMove.Stand, never);
                    Object exact = outcome(() -> new ExactRound(rules).valueOfFirstMove(shoe, 10,
                            second, upCard, PlayerMove.Stand, never));
                    if (exact instanceof Double) {
                        assertEquals(brute, (Double) exact, TOLERANCE, "the two disagree standing " + at);
                    } else {
                        assertTrue(k > 15 && exact instanceof UnsupportedOperationException,
                                "ExactRound threw " + exact + " standing " + at);
                    }
                    gap = brute - limit;
                    assertTrue(Math.abs(gap) < previous, "the gap grew standing " + at);
                    previous = Math.abs(gap);
                    table.append(String.format(" %d:%+.1e", k, gap));
                }
                table.append(String.format("  x512 %+.4f%n", gap * 512));
                assertTrue(Math.abs(gap) < 1e-4, "still " + gap + " from the infinite deck standing "
                        + total + " vs " + up + " on 512 decks");
            }
        }
        System.out.print(table);
    }

    /** One round's inputs, less the first move. */
    private static final class Case {
        final int index;
        final String scenario;
        final RoundRules rules;
        final int[] shoe;
        final int p1;
        final int p2;
        final int up;
        final String policyName;
        final RoundPolicy policy;

        Case(int index, String scenario, RoundRules rules, int[] shoe, int p1, int p2, int up,
             String policyName, RoundPolicy policy) {
            this.index = index;
            this.scenario = scenario;
            this.rules = rules;
            this.shoe = shoe;
            this.p1 = p1;
            this.p2 = p2;
            this.up = up;
            this.policyName = policyName;
            this.policy = policy;
        }

        String describe(PlayerMove first) {
            return "case " + index + " (" + scenario + "): " + p1 + "," + p2 + " vs " + up
                    + ", first " + first + ", shoe " + Arrays.toString(Arrays.copyOfRange(shoe, 1, 11))
                    + ", policy " + policyName + ", " + describeRules(rules);
        }
    }

    static String describeRules(RoundRules r) {
        StringBuilder das = new StringBuilder();
        for (int k = 1; k <= 10; k++) {
            if (r.doubleAfterSplit[k]) {
                das.append(das.length() > 0 ? "," : "").append(k);
            }
        }
        return "rules [" + (r.hitsSoft17 ? "H17" : "S17") + ", pays " + r.blackjackPayout
                + ", hands aces " + r.maxHandsAces + " others " + r.maxHandsNotAces
                + ", hit split aces " + r.canHitSplitAces + ", DAS {" + das + "}"
                + ", BJ on split " + r.blackjackOnSplitPairs + ", surrender " + r.surrender
                + ", after split " + r.surrenderAfterSplit + "]";
    }

    /**
     * One random case. Most are drawn with nothing forced; the rest aim at a feature that
     * an unaimed draw reaches too rarely, by forcing the pair, the rule, the shoe or the
     * policy that it needs.
     */
    private static Case randomCase(long seed, int index, int minCards, int maxCards) {
        Random rnd = new Random(mix(seed + index));
        double s = rnd.nextDouble();
        String scenario = s < 0.20 ? "any" : s < 0.36 ? "split aces act" : s < 0.48 ? "pair"
                : s < 0.64 ? "aces" : s < 0.76 ? "split blackjack" : s < 0.88 ? "surrender after split"
                : "resplit";
        // Split aces that may not be hit, under rules that often let them double or
        // surrender, played by a policy that does whatever it may on a split hand's first
        // decision rather than stand.
        boolean acesAct = scenario.equals("split aces act");

        int p1;
        int p2;
        switch (scenario) {
            case "any": {
                double kind = rnd.nextDouble();
                if (kind < 0.12) {
                    p1 = rnd.nextBoolean() ? 1 : 10;
                    p2 = 11 - p1;
                } else if (kind < 0.35) {
                    p1 = p2 = 1 + rnd.nextInt(10);
                } else {
                    p1 = 1 + rnd.nextInt(10);
                    p2 = 1 + rnd.nextInt(10);
                }
                break;
            }
            case "pair":
                p1 = p2 = rnd.nextDouble() < 0.4 ? (rnd.nextBoolean() ? 1 : 10) : 1 + rnd.nextInt(10);
                break;
            case "resplit":
                p1 = p2 = rnd.nextDouble() < 0.7 ? new int[]{1, 8, 9, 10}[rnd.nextInt(4)]
                        : 1 + rnd.nextInt(10);
                break;
            case "aces":
            case "split aces act":
                p1 = p2 = 1;
                break;
            case "split blackjack":
                p1 = p2 = rnd.nextBoolean() ? 1 : 10;
                break;
            default:    // surrender after split: pairs whose split hands often total 14 to 16
                p1 = p2 = 6 + rnd.nextInt(3);
                break;
        }
        double u = rnd.nextDouble();
        int up = u < 0.25 ? 1 : u < 0.5 ? 10 : 1 + rnd.nextInt(10);
        boolean pair = p1 == p2;

        boolean[] das = new boolean[11];
        double dasKind = rnd.nextDouble();
        for (int r = 1; r <= 10; r++) {
            das[r] = dasKind >= 0.2 && (dasKind < 0.4 || rnd.nextBoolean());
        }
        if (acesAct) {
            das[1] = rnd.nextDouble() < 0.75;
        }
        int maxAces = 1 + rnd.nextInt(4);
        int maxOthers = 1 + rnd.nextInt(4);
        if (pair && !scenario.equals("any")) {
            // Limits from 2, where no pair may split again, for the scenarios that need a
            // pair to be kept; from 3 for the ones that aim at a resplit.
            int limit = scenario.equals("pair") || scenario.equals("split blackjack")
                    || scenario.equals("surrender after split") || acesAct
                    ? 2 + rnd.nextInt(3) : 3 + rnd.nextInt(2);
            if (p1 == 1) {
                maxAces = limit;
            } else {
                maxOthers = limit;
            }
        }
        boolean acesSurrender = acesAct && rnd.nextDouble() < 0.75;
        boolean splitBlackjack = scenario.equals("split blackjack") || rnd.nextBoolean();
        boolean surrenderAfterSplit = scenario.equals("surrender after split") || acesSurrender
                || rnd.nextDouble() < 0.5;
        boolean surrender = scenario.equals("surrender after split") || acesSurrender || rnd.nextDouble() < 0.6;
        boolean hitsSoft17 = rnd.nextBoolean();
        double payout = rnd.nextBoolean() ? 1.5 : 1.2;
        boolean canHitSplitAces = !acesAct && rnd.nextBoolean();
        RoundRules rules = new RoundRules(hitsSoft17, payout, maxAces, maxOthers, canHitSplitAces, das,
                splitBlackjack, surrender, surrenderAfterSplit);

        int shoeKind = rnd.nextInt(7);
        if ((scenario.equals("resplit") || scenario.equals("aces") || acesAct) && rnd.nextDouble() < 0.8) {
            shoeKind = rnd.nextDouble() < 0.35 ? 6 : 7;
        } else if (scenario.equals("split blackjack") && rnd.nextDouble() < 0.6) {
            shoeKind = 5;
        }
        int size = minCards + rnd.nextInt(maxCards - minCards + 1);
        int[] shoe = randomShoe(rnd, size, shoeKind, pair ? p1 : 0);

        int which = rnd.nextInt(POLICY_NAMES.length);
        if (scenario.equals("resplit") || (scenario.equals("aces") && rnd.nextBoolean())) {
            which = 2;
        } else if (scenario.equals("surrender after split") && rnd.nextDouble() < 0.6) {
            which = 5;
        } else if (acesAct && rnd.nextDouble() < 0.8) {
            which = SPLIT_HANDS_ACT;
        }
        return new Case(index, scenario, rules, shoe, p1, p2, up, POLICY_NAMES[which], policy(which, rnd));
    }

    /** SplitMix64's finalizer, so that neighbouring case numbers seed unrelated generators. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * A shoe of this many cards: like a real shoe (0), rich in aces (1), in tens (2) or in
     * small cards (3), two or three ranks only (4), aces and tens (5), rich in the pair's
     * rank (6), or in the pair's rank and tens (7), so that split hands end soon.
     */
    private static int[] randomShoe(Random rnd, int size, int kind, int pairRank) {
        double[] weight = new double[11];
        for (int r = 1; r <= 10; r++) {
            weight[r] = r == 10 ? 4 : 1;
        }
        switch (kind) {
            case 0:
                break;
            case 1:
                weight[1] = 6;
                break;
            case 2:
                weight[10] = 14;
                break;
            case 3:
                for (int r = 2; r <= 5; r++) {
                    weight[r] = 5;
                }
                weight[10] = 1;
                break;
            case 4: {
                Arrays.fill(weight, 0);
                int ranks = 2 + rnd.nextInt(2);
                for (int i = 0; i < ranks; i++) {
                    weight[1 + rnd.nextInt(10)] += 1 + rnd.nextInt(3);
                }
                break;
            }
            case 5:
                weight[1] = 5;
                weight[10] = 10;
                break;
            case 6:
                if (pairRank != 0) {
                    weight[pairRank] += 6;
                }
                break;
            default:
                if (pairRank != 0) {
                    weight[pairRank] += 6;
                }
                weight[10] += 6;
                break;
        }
        return randomShoe(rnd, size, weight);
    }

    /** A shoe of this many cards, each drawn with chance in proportion to its rank's weight. */
    private static int[] randomShoe(Random rnd, int size, double[] weight) {
        double total = 0;
        for (int r = 1; r <= 10; r++) {
            total += weight[r];
        }
        int[] shoe = new int[11];
        for (int i = 0; i < size; i++) {
            double x = rnd.nextDouble() * total;
            int r = 1;
            while (r < 10 && x >= weight[r]) {
                x -= weight[r];
                r++;
            }
            while (weight[r] == 0) {
                r = r == 10 ? 1 : r + 1;
            }
            shoe[r]++;
        }
        return shoe;
    }

    private static List<PlayerMove> legalFirstMoves(Case c) {
        List<PlayerMove> moves = new ArrayList<>();
        moves.add(PlayerMove.Stand);
        if ((c.p1 == 1 && c.p2 == 10) || (c.p1 == 10 && c.p2 == 1)) {
            return moves;
        }
        moves.add(PlayerMove.Hit);
        moves.add(PlayerMove.Double);
        if (c.p1 == c.p2 && c.rules.maxHandsFor(c.p1) >= 2) {
            moves.add(PlayerMove.Split);
        }
        if (c.rules.surrender) {
            moves.add(PlayerMove.Surrender);
        }
        return moves;
    }

    // ------------------------------------------------------------------ one comparison

    private static void compare(Case c, PlayerMove first, long work, Coverage cov) {
        int[] before = c.shoe.clone();
        cov.rounds++;
        boolean splitsAces = first == PlayerMove.Split && c.p1 == 1;
        Recording asBrute = new Recording(c.rules, splitsAces, c.policy, bruteForceWork(c, work));
        Object brute = outcome(() -> new BruteForceRound(c.rules)
                .valueOfFirstMove(c.shoe, c.p1, c.p2, c.up, first, asBrute));
        if (brute instanceof TooBig) {
            cov.tooBig++;
            return;
        }
        Recording asExact = new Recording(c.rules, splitsAces, c.policy, null);
        Object exact = outcome(() -> new ExactRound(c.rules)
                .valueOfFirstMove(c.shoe, c.p1, c.p2, c.up, first, asExact));
        assertArrayEquals(before, c.shoe, "the shoe was modified: " + c.describe(first));

        if (brute instanceof Double && exact instanceof Double) {
            double b = (Double) brute;
            double e = (Double) exact;
            assertEquals(b, e, TOLERANCE, "values differ on " + c.describe(first));
            // Each asks about exactly the decisions the contract reaches.
            assertEquals(asBrute.asked, asExact.asked, "the policy was asked different questions on "
                    + c.describe(first));
            cov.valued++;
            cov.worstGap = Math.max(cov.worstGap, Math.abs(b - e));
            cov.count(c, first, asBrute, e);
            return;
        }
        String got = "brute force " + describeOutcome(brute) + ", exact " + describeOutcome(exact);
        assertFalse(brute instanceof Double || exact instanceof Double,
                "only one threw (" + got + ") on " + c.describe(first));
        assertEquals(brute.getClass(), exact.getClass(),
                "different exceptions (" + got + ") on " + c.describe(first));
        cov.threw(brute.getClass());
    }

    /**
     * A first move that is not legal is refused by both with IllegalArgumentException, on any
     * shoe: the contract checks the move before anything is dealt, so an empty shoe, or one
     * of nothing but natural hole cards, makes no difference.
     */
    private static void compareIllegalFirst(Case c, PlayerMove first, Coverage cov) {
        Object brute = outcome(() -> new BruteForceRound(c.rules)
                .valueOfFirstMove(c.shoe, c.p1, c.p2, c.up, first, c.policy));
        Object exact = outcome(() -> new ExactRound(c.rules)
                .valueOfFirstMove(c.shoe, c.p1, c.p2, c.up, first, c.policy));
        String got = "brute force " + describeOutcome(brute) + ", exact " + describeOutcome(exact);
        assertTrue(brute instanceof IllegalArgumentException && exact instanceof IllegalArgumentException,
                "an illegal first move was not refused by both (" + got + ") on " + c.describe(first));
        cov.illegalFirst++;
    }

    /**
     * What each decision costs BruteForceRound on this case, and the most it may spend: a
     * decision leads to about one more player path, and each needs the dealer.
     */
    private static long[] bruteForceWork(Case c, long work) {
        return new long[]{Math.max(1, dealerOrders(c.shoe, c.up, work)), work};
    }

    /**
     * The number of orders in which the dealer can take a hole card that is not a natural and
     * then draw to a finish from this shoe, counted up to cap. Every hand that needs the
     * dealer costs BruteForceRound about this many branches, or fewer from a smaller shoe.
     */
    static long dealerOrders(int[] shoe, int up, long cap) {
        int[] left = shoe.clone();
        int natural = up == 1 ? 10 : up == 10 ? 1 : 0;
        long n = 0;
        for (int hole = 1; hole <= 10 && n < cap; hole++) {
            if (left[hole] == 0 || hole == natural) {
                continue;
            }
            left[hole]--;
            n += dealerOrdersFrom(left, up + hole, up == 1 || hole == 1, cap - n);
            left[hole]++;
        }
        return n;
    }

    /** From a dealer hand whose cards add to hard with aces as 1, standing on every soft 17. */
    private static long dealerOrdersFrom(int[] left, int hard, boolean ace, long cap) {
        int total = ace && hard <= 11 ? hard + 10 : hard;
        if (total >= 17) {
            return 1;
        }
        long n = 0;
        for (int r = 1; r <= 10 && n < cap; r++) {
            if (left[r] == 0) {
                continue;
            }
            left[r]--;
            n += dealerOrdersFrom(left, hard + r, ace || r == 1, cap - n);
            left[r]++;
        }
        return Math.max(n, 1);
    }

    /** BruteForceRound was stopped at a decision past its work limit. */
    private static final class TooBig extends RuntimeException {
        TooBig() {
            super("over the work limit", null, false, false);
        }
    }

    private static String describeOutcome(Object o) {
        return o instanceof Double ? "returned " + o : "threw " + o;
    }

    private interface Valuing {
        double value();
    }

    private static Object outcome(Valuing valuing) {
        try {
            return valuing.value();
        } catch (RuntimeException e) {
            return e;
        }
    }

    /**
     * Records every distinct decision asked, and whether a split hand split, surrendered or,
     * for a split ace that may not be hit, doubled. Given costs from bruteForceWork, it adds
     * up BruteForceRound's work and throws TooBig at the first decision past the limit.
     */
    private static final class Recording implements RoundPolicy {
        final Set<String> asked = new LinkedHashSet<>();
        boolean resplit;
        boolean acesResplit;
        boolean surrenderedAfterSplit;
        boolean aceDoubledOnCard;
        boolean aceDoubledOnAce;
        boolean aceSurrenderedOnCard;
        boolean aceSurrenderedOnAce;
        /**
         * The round splits aces that may not be hit. The policy never makes the dealt hand's
         * first move, so a round splits aces only if its first move does, and then every split
         * hand in it is one of those aces.
         */
        private final boolean stiffAces;
        private final RoundPolicy inner;
        private final long[] cost;
        private long work;

        Recording(RoundRules rules, boolean splitsAces, RoundPolicy inner, long[] cost) {
            this.stiffAces = splitsAces && !rules.canHitSplitAces;
            this.inner = inner;
            this.cost = cost;
        }

        @Override
        public PlayerMove choose(Decision d) {
            if (cost != null) {
                work += cost[0];
                if (work > cost[1]) {
                    throw new TooBig();
                }
            }
            asked.add(d.toString());
            PlayerMove m = inner.choose(d);
            if (d.fromSplit && m == PlayerMove.Split) {
                resplit = true;
                acesResplit |= d.pairRank == 1;
            }
            if (d.fromSplit && m == PlayerMove.Surrender) {
                surrenderedAfterSplit = true;
            }
            if (stiffAces && d.fromSplit && d.firstDecision) {
                boolean onAce = d.pairRank == 1;
                if (m == PlayerMove.Double) {
                    aceDoubledOnAce |= onAce;
                    aceDoubledOnCard |= !onAce;
                } else if (m == PlayerMove.Surrender) {
                    aceSurrenderedOnAce |= onAce;
                    aceSurrenderedOnCard |= !onAce;
                }
            }
            return m;
        }
    }

    // ------------------------------------------------------------------ coverage

    private static final class Coverage {
        int cases;
        double seconds;
        int rounds;
        int tooBig;
        int valued;
        int illegalState;
        int illegalArgument;
        int otherThrow;
        int illegalFirst;
        double worstGap;
        int split;
        int handByHand;
        int resplit;
        int limitBinds;
        int acesResplit;
        int splitBlackjack;
        int surrenderAfterSplit;
        int peekAceUp;
        int peekTenUp;
        int dealerHitsSoft17;
        int splitAceDoubledOnCard;
        int splitAceDoubledOnAce;
        int splitAceSurrenderedOnCard;
        int splitAceSurrenderedOnAce;

        void threw(Class<?> type) {
            if (type == IllegalStateException.class) {
                illegalState++;
            } else if (type == IllegalArgumentException.class) {
                illegalArgument++;
            } else {
                otherThrow++;
            }
        }

        /**
         * The features a round that both valued reached. The policy's answers show resplits,
         * surrenders after a split, and what split aces that may not be hit did with their
         * first decision, and the shoe shows whether the peek removed any hole
         * card. The rest are found by changing one rule and seeing whether ExactRound's answer
         * moves, which it can only do if the round reaches what that rule governs: raising
         * the split rank's limit by one, turning blackjack on split pairs off, and standing on
         * soft 17.
         */
        void count(Case c, PlayerMove first, Recording asked, double value) {
            RoundRules r = c.rules;
            if (first == PlayerMove.Split) {
                split++;
                handByHand += valuedHandByHand(c.shoe, c.p1, c.up, r) ? 1 : 0;
                boolean aces = c.p1 == 1;
                RoundRules roomier = with(r, r.hitsSoft17, r.blackjackOnSplitPairs,
                        r.maxHandsAces + (aces ? 1 : 0), r.maxHandsNotAces + (aces ? 0 : 1));
                limitBinds += moves(roomier, c, first, value) ? 1 : 0;
                if (r.blackjackOnSplitPairs && (c.p1 == 1 || c.p1 == 10)) {
                    RoundRules plain = with(r, r.hitsSoft17, false, r.maxHandsAces, r.maxHandsNotAces);
                    splitBlackjack += moves(plain, c, first, value) ? 1 : 0;
                }
            }
            resplit += asked.resplit ? 1 : 0;
            acesResplit += asked.acesResplit ? 1 : 0;
            surrenderAfterSplit += asked.surrenderedAfterSplit ? 1 : 0;
            splitAceDoubledOnCard += asked.aceDoubledOnCard ? 1 : 0;
            splitAceDoubledOnAce += asked.aceDoubledOnAce ? 1 : 0;
            splitAceSurrenderedOnCard += asked.aceSurrenderedOnCard ? 1 : 0;
            splitAceSurrenderedOnAce += asked.aceSurrenderedOnAce ? 1 : 0;
            peekAceUp += c.up == 1 && c.shoe[10] > 0 ? 1 : 0;
            peekTenUp += c.up == 10 && c.shoe[1] > 0 ? 1 : 0;
            if (r.hitsSoft17) {
                RoundRules s17 = with(r, false, r.blackjackOnSplitPairs, r.maxHandsAces, r.maxHandsNotAces);
                dealerHitsSoft17 += moves(s17, c, first, value) ? 1 : 0;
            }
        }

        /** Whether ExactRound under other rules gives another value, or throws. */
        private static boolean moves(RoundRules other, Case c, PlayerMove first, double value) {
            Object o = outcome(() -> new ExactRound(other).valueOfFirstMove(c.shoe, c.p1, c.p2, c.up,
                    first, c.policy));
            return !(o instanceof Double) || Math.abs((Double) o - value) > TOLERANCE;
        }

        void add(Coverage o) {
            rounds += o.rounds;
            tooBig += o.tooBig;
            valued += o.valued;
            illegalState += o.illegalState;
            illegalArgument += o.illegalArgument;
            otherThrow += o.otherThrow;
            illegalFirst += o.illegalFirst;
            worstGap = Math.max(worstGap, o.worstGap);
            split += o.split;
            handByHand += o.handByHand;
            resplit += o.resplit;
            limitBinds += o.limitBinds;
            acesResplit += o.acesResplit;
            splitBlackjack += o.splitBlackjack;
            surrenderAfterSplit += o.surrenderAfterSplit;
            peekAceUp += o.peekAceUp;
            peekTenUp += o.peekTenUp;
            dealerHitsSoft17 += o.dealerHitsSoft17;
            splitAceDoubledOnCard += o.splitAceDoubledOnCard;
            splitAceDoubledOnAce += o.splitAceDoubledOnAce;
            splitAceSurrenderedOnCard += o.splitAceSurrenderedOnCard;
            splitAceSurrenderedOnAce += o.splitAceSurrenderedOnAce;
        }

        void print(String shoes) {
            System.out.printf("ExactRound against BruteForceRound on %s: %d rounds from %d cases in %.1f s%n",
                    shoes, rounds, cases, seconds);
            System.out.printf("  both valued %d (worst gap %.1e); both threw IllegalStateException %d, "
                    + "IllegalArgumentException %d, other %d; too big for BruteForceRound %d%n",
                    valued, worstGap, illegalState, illegalArgument, otherThrow, tooBig);
            if (illegalFirst > 0) {
                System.out.printf("  illegal first moves both refused: %d%n", illegalFirst);
            }
            System.out.println("  among the rounds both valued:");
            System.out.printf("    a split                         %6d%n", split);
            System.out.printf("      valued hand by hand           %6d%n", handByHand);
            System.out.printf("    a re-split                      %6d%n", resplit);
            System.out.printf("    the hand limit binds            %6d%n", limitBinds);
            System.out.printf("    split aces re-split             %6d%n", acesResplit);
            System.out.printf("    a split blackjack paid          %6d%n", splitBlackjack);
            System.out.printf("    a surrender after a split       %6d%n", surrenderAfterSplit);
            System.out.printf("    the peek with an ace up         %6d%n", peekAceUp);
            System.out.printf("    the peek with a ten up          %6d%n", peekTenUp);
            System.out.printf("    the dealer hitting soft 17      %6d%n", dealerHitsSoft17);
            System.out.println("    a split ace that may not be hit");
            System.out.printf("      doubling on another card      %6d%n", splitAceDoubledOnCard);
            System.out.printf("      doubling on another ace       %6d%n", splitAceDoubledOnAce);
            System.out.printf("      surrendering on another card  %6d%n", splitAceSurrenderedOnCard);
            System.out.printf("      surrendering on another ace   %6d%n", splitAceSurrenderedOnAce);
            assertEquals(0, otherThrow, "an exception the contract does not name");
        }

        void require(int enough, String what, int count) {
            assertTrue(count >= enough, "too few rounds with " + what + ": " + count
                    + ", fewer than " + enough);
        }
    }

    private static RoundRules with(RoundRules r, boolean hitsSoft17, boolean blackjackOnSplitPairs,
                                   int maxHandsAces, int maxHandsNotAces) {
        return new RoundRules(hitsSoft17, r.blackjackPayout, maxHandsAces, maxHandsNotAces,
                r.canHitSplitAces, r.doubleAfterSplit, blackjackOnSplitPairs, r.surrender,
                r.surrenderAfterSplit);
    }

    // ------------------------------------------------------------------ the policies

    private static final String[] POLICY_NAMES = {
            "stand on thresholds", "basic strategy", "always split", "never split",
            "double 9 to 11", "surrender 14 to 16", "hashed", "split hands act"
    };

    /** The policy that acts on split hands' first decisions, which aimed cases ask for. */
    private static final int SPLIT_HANDS_ACT = 7;

    private static RoundPolicy policy(int which, Random rnd) {
        switch (which) {
            case 0:
                return thresholds(12 + rnd.nextInt(7), 16 + rnd.nextInt(4));
            case 1:
                return ExactRoundTimingReport.basicStrategy(false);
            case 2:
                return ExactRoundTimingReport.basicStrategy(true);
            case 3: {
                RoundPolicy basic = ExactRoundTimingReport.basicStrategy(false);
                return d -> basic.choose(new RoundPolicy.Decision(d.total, d.soft, d.numCards,
                        d.pairRank, d.upCard, d.fromSplit, d.firstDecision, d.canHit, d.canDouble,
                        false, d.canSurrender));
            }
            case 4: {
                RoundPolicy after = thresholds(17, 18);
                return d -> d.canDouble && !d.soft && d.total >= 9 && d.total <= 11
                        ? PlayerMove.Double : after.choose(d);
            }
            case 5: {
                RoundPolicy after = thresholds(17, 18);
                return d -> d.canSurrender && d.total >= 14 && d.total <= 16
                        ? PlayerMove.Surrender : after.choose(d);
            }
            case SPLIT_HANDS_ACT: {
                long salt = rnd.nextLong();
                RoundPolicy after = thresholds(17, 18);
                return d -> d.fromSplit && d.firstDecision ? hashedChoice(salt, d, false) : after.choose(d);
            }
            default: {
                long salt = rnd.nextLong();
                return d -> hashedChoice(salt, d, true);
            }
        }
    }

    /** Hits a hard total under hard and a soft one under soft; never doubles, splits or surrenders. */
    private static RoundPolicy thresholds(int hard, int soft) {
        return d -> d.canHit && d.total < (d.soft ? soft : hard) ? PlayerMove.Hit : PlayerMove.Stand;
    }

    /**
     * A legal move picked by hashing every field of the decision. Without stand, Stand is
     * picked only where nothing else is legal: on a split ace that may not be hit, that is a
     * double, a split or a surrender wherever the rules allow one.
     */
    private static PlayerMove hashedChoice(long salt, RoundPolicy.Decision d, boolean stand) {
        long h = salt;
        long[] fields = {d.total, d.soft ? 1 : 0, d.numCards, d.pairRank, d.upCard,
                d.fromSplit ? 1 : 0, d.firstDecision ? 1 : 0, d.canHit ? 1 : 0,
                d.canDouble ? 1 : 0, d.canSplit ? 1 : 0, d.canSurrender ? 1 : 0};
        for (long f : fields) {
            h = mix(h ^ f);
        }
        List<PlayerMove> legal = new ArrayList<>();
        for (PlayerMove m : PlayerMove.values()) {
            if (d.isLegal(m) && (stand || m != PlayerMove.Stand)) {
                legal.add(m);
            }
        }
        return legal.isEmpty() ? PlayerMove.Stand : legal.get((int) Long.remainderUnsigned(h, legal.size()));
    }
}
