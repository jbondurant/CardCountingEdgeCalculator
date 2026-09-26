import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The hit limit belongs to the random player and to no one else.
 *
 * RandomVsOptimalReport prices each first move two ways: with the rest of the hand played
 * well, and with it played by coin flip. Its one argument says how high a total the
 * coin-flipping player is willing to hit, so that it can be spared absurd moves like
 * hitting a 20. The optimal player is the yardstick the random one is measured against,
 * and a yardstick has to stay put while the argument moves: it may hit anything short of
 * 21, and does whenever hitting is worth more.
 *
 * The limit used to cap both players. At the default of 19 that was harmless, because
 * hitting a 20 is never right, but below it the yardstick moved with the argument. At 17
 * the optimal player was not allowed to hit a soft 18, so the report called Stand the
 * right move against a 9, a 10 and an ace, where hitting is worth more.
 */
public class RandomPlayerHitLimitTest {

    /** One starting hand, in the form the report's evaluate takes it. */
    private static final class Start {
        final String label;
        final int total;
        final boolean soft;
        final Integer pairRank;

        Start(String label, int total, boolean soft, Integer pairRank) {
            this.label = label;
            this.total = total;
            this.soft = soft;
            this.pairRank = pairRank;
        }
    }

    /** The hands the report walks through, in the same order: hard, soft, then pairs. */
    private static List<Start> everyHandTheReportEvaluates() {
        List<Start> hands = new ArrayList<>();
        for (int total = 5; total <= 20; total++) {
            hands.add(new Start("hard " + total, total, false, null));
        }
        for (int kicker = 2; kicker <= 9; kicker++) {
            hands.add(new Start("soft " + (11 + kicker), 11 + kicker, true, null));
        }
        for (int rank = 1; rank <= 10; rank++) {
            hands.add(new Start(rank == 1 ? "A,A" : rank + "," + rank,
                    rank == 1 ? 12 : rank * 2, rank == 1, rank));
        }
        return hands;
    }

    private static String upCardName(int up) {
        return up == 11 ? "an ace" : "a " + up;
    }

    private static String runReport(int hitLimit) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(captured, true));
        try {
            new RandomVsOptimalReport(hitLimit).run();
        } finally {
            System.setOut(original);
        }
        return captured.toString();
    }

    // ------------------------------------------------------------- the case that broke

    /**
     * Soft 18 against a 9, a 10 or an ace is a hit: it is too weak to stand on against
     * those cards, and one more card cannot bust it. That stays true when the random
     * player is told to stop at 17, because the limit is about the random player.
     */
    @Test
    public void softEighteenAgainstANineIsAHitEvenWhenTheRandomPlayerStopsAtSeventeen() {
        RandomVsOptimalReport report = new RandomVsOptimalReport(17);
        for (int up : new int[]{9, 10, 11}) {
            RandomVsOptimalReport.Row row =
                    report.evaluate("soft 18", 18, true, null, up);
            assertEquals("Hit", row.bestByOptimal,
                    "with the random player stopping at 17, soft 18 against "
                            + upCardName(up) + " came out " + row.bestByOptimal
                            + " as the right move; the optimal player is not bound by"
                            + " that limit and should hit");
        }
    }

    // ------------------------------------------------------------------- the property

    /**
     * Moving the random player's limit changes what random play picks and must change
     * nothing about what is right. Every hand the report looks at, against every
     * up-card, has the same right move and the same value under a strict limit as under
     * the most lenient one, where the random player may hit anything at all.
     */
    @Test
    public void aHandsOptimalValueDoesNotDependOnTheRandomPlayersLimit() {
        RandomVsOptimalReport lenient = new RandomVsOptimalReport(21);
        List<Start> hands = everyHandTheReportEvaluates();
        List<RandomVsOptimalReport.Row> reference = new ArrayList<>();
        for (int up = 2; up <= 11; up++) {
            for (Start h : hands) {
                reference.add(lenient.evaluate(h.label, h.total, h.soft, h.pairRank, up));
            }
        }

        for (int limit : new int[]{12, 17, 19}) {
            RandomVsOptimalReport strict = new RandomVsOptimalReport(limit);
            int i = 0;
            for (int up = 2; up <= 11; up++) {
                for (Start h : hands) {
                    RandomVsOptimalReport.Row expected = reference.get(i++);
                    RandomVsOptimalReport.Row actual =
                            strict.evaluate(h.label, h.total, h.soft, h.pairRank, up);
                    String where = h.label + " against " + upCardName(up)
                            + " with the random player stopping at " + limit;
                    assertEquals(expected.bestByOptimal, actual.bestByOptimal,
                            where + " has a different right move from the one it has"
                                    + " when the random player may hit anything");
                    assertEquals(expected.valueOfRightMove, actual.valueOfRightMove, 1e-12,
                            where + " is worth a different amount under optimal play"
                                    + " from what it is worth when the random player may"
                                    + " hit anything");
                }
            }
        }
    }

    // ------------------------------------------------------------------ the printout

    /**
     * Where the right move is a hit the random player will not take, the two disagree
     * and the report has to say so. What it cannot do is price that hit by random
     * continuation, because random continuation never considers it, so the row has no
     * underpricing figure to print. It must not come out as underpriced by an infinite
     * amount.
     */
    @Test
    public void aHitTheRandomPlayerWillNotTakeIsADisagreementAndNotInfinitelyUnderpriced() {
        String out = runReport(17);
        assertTrue(Pattern.compile("(?m)^soft 18\\s+9\\s+Hit\\s+Stand\\s")
                        .matcher(out).find(),
                "at a limit of 17 the report should list soft 18 against a 9 as Hit"
                        + " where random play picks Stand, and it does not");
        assertFalse(out.contains("Infinity"),
                "the report at a limit of 17 printed an infinite figure");
        assertFalse(out.contains("NaN"),
                "the report at a limit of 17 printed an undefined figure");
    }
}
