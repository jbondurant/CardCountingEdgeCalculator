import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The label a strategy table cell carries, and the colour it is drawn in.
 *
 * A cell covers every hand with its total against one up-card, however many cards made
 * it. Double, Split and Surrender are only legal on some of those hands, so when one of
 * them is best the label also names a fallback, and the fallback has to mean one thing:
 * what to do when the best move is not available. Double and Surrender are gone once the
 * hand has a third card, which leaves Hit and Stand. Split is gone on a pair at the
 * resplit limit; that is still a two-card hand, so anything but surrendering is left,
 * because surrender is never allowed on a hand that came out of a split.
 *
 * The fallback used to be simply the runner-up. A Surrender cell got none at all, so a
 * player holding a three-card 16 against a ten was told only to surrender, which he no
 * longer can. A pair could read SplitSurrender, naming the one move a split hand never
 * has. And the colours had not kept up: Double, Split and every Surrender label were
 * written out with class names the stylesheet did not define, so those cells rendered
 * plain.
 *
 * The checks below run over every set of measured moves drawn from all five and every
 * order their payoffs can come in, 325 buckets in all, rather than over a few examples.
 * Surrender is always recorded at -0.5, since that is what it is worth by rule; the
 * other payoffs are placed around it.
 */
public class CellLabelTest {

    private static final EnumSet<PlayerMove> HIT_OR_STAND =
            EnumSet.of(PlayerMove.Hit, PlayerMove.Stand);

    /** Every non-empty set of moves, in every order of payoff, best first. */
    private static List<List<PlayerMove>> everyRanking() {
        List<List<PlayerMove>> rankings = new ArrayList<>();
        PlayerMove[] all = PlayerMove.values();
        for (int mask = 1; mask < (1 << all.length); mask++) {
            List<PlayerMove> chosen = new ArrayList<>();
            for (int i = 0; i < all.length; i++) {
                if ((mask & (1 << i)) != 0) {
                    chosen.add(all[i]);
                }
            }
            permute(chosen, 0, rankings);
        }
        return rankings;
    }

    private static void permute(List<PlayerMove> moves, int from, List<List<PlayerMove>> out) {
        if (from == moves.size()) {
            out.add(new ArrayList<>(moves));
            return;
        }
        for (int i = from; i < moves.size(); i++) {
            Collections.swap(moves, from, i);
            permute(moves, from + 1, out);
            Collections.swap(moves, from, i);
        }
    }

    /**
     * A bucket whose measured moves pay off in the given order, best first.
     *
     * Payoffs step by 0.1 down the ranking, anchored so that Surrender, when present, sits
     * at exactly -0.5.
     */
    private static MoveChoices bucketRanked(List<PlayerMove> bestFirst) {
        int anchor = Math.max(0, bestFirst.indexOf(PlayerMove.Surrender));
        MoveChoices mc = new MoveChoices();
        for (int i = 0; i < bestFirst.size(); i++) {
            ActionPayoff ap = new ActionPayoff();
            ap.insertEvent(-0.5 + 0.1 * (anchor - i));
            mc.actionPayoffs.put(bestFirst.get(i), ap);
        }
        return mc;
    }

    private static MoveChoices bucket(Object... moveThenPayoff) {
        MoveChoices mc = new MoveChoices();
        for (int i = 0; i < moveThenPayoff.length; i += 2) {
            ActionPayoff ap = new ActionPayoff();
            ap.insertEvent((Double) moveThenPayoff[i + 1]);
            mc.actionPayoffs.put((PlayerMove) moveThenPayoff[i], ap);
        }
        return mc;
    }

    /** The first move in the ranking that is one of the allowed ones, or null. */
    private static PlayerMove firstOf(List<PlayerMove> bestFirst, EnumSet<PlayerMove> allowed) {
        for (PlayerMove pm : bestFirst) {
            if (allowed.contains(pm)) {
                return pm;
            }
        }
        return null;
    }

    private static String expectedLabel(PlayerMove best, PlayerMove fallback) {
        return fallback == null ? best.name() : best.name() + fallback.name();
    }

    private static String stylesheet() throws IOException {
        return new String(Files.readAllBytes(new File("stuffForHTML", "style.txt").toPath()),
                StandardCharsets.UTF_8);
    }

    @Test
    public void theEnumerationCoversEveryBucketShape() {
        // 5 one-move buckets, 20 ordered pairs, 60 triples, 120 of four, 120 of all five.
        assertEquals(325, everyRanking().size(),
                "the enumeration should reach every ordered set of the five moves");
    }

    /**
     * Every label a cell can carry is drawn in some colour.
     *
     * The published tables are checked separately against the stylesheet, but they only
     * contain what one run happened to produce. This asks the question of every label
     * getCompoundBestMove can return, including the ones no committed table has hit yet.
     */
    @Test
    public void everyLabelACellCanCarryHasAColour() throws IOException {
        String stylesheet = stylesheet();
        Map<String, List<PlayerMove>> uncoloured = new TreeMap<>();
        for (List<PlayerMove> ranking : everyRanking()) {
            String tag = bucketRanked(ranking).getCompoundBestMove().toLowerCase(Locale.ROOT);
            if (!stylesheet.contains(".tg-" + tag + "{")) {
                uncoloured.putIfAbsent(tag, ranking);
            }
        }
        assertTrue(uncoloured.isEmpty(),
                "these labels have no .tg- rule in style.txt, so their cells render with no "
                        + "colour (each shown with a ranking, best first, that produces it): "
                        + uncoloured);
    }

    /**
     * The report's case: hard 16 against a ten with surrender allowed.
     *
     * Surrender at -0.5 beats hitting at about -0.54, but the same cell also covers the
     * 16 reached by hitting a 12, which cannot surrender. The label has to say what to
     * do with that one.
     */
    @Test
    public void aSurrenderCellSaysWhatToDoOnceSurrenderIsGone() {
        assertEquals("SurrenderHit",
                bucket(PlayerMove.Surrender, -0.5, PlayerMove.Hit, -0.54, PlayerMove.Stand, -0.56)
                        .getCompoundBestMove(),
                "a three-card 16 cannot surrender, so the label must name Hit as well");
        assertEquals("SurrenderStand",
                bucket(PlayerMove.Surrender, -0.5, PlayerMove.Hit, -0.58, PlayerMove.Stand, -0.54)
                        .getCompoundBestMove(),
                "a three-card 16 cannot surrender, so the label must name Stand as well");
    }

    /**
     * Surrender and Double fall back only to Hit or Stand, whichever is better.
     *
     * Both are gone once the hand has a third card, and so are Split and each other. The
     * runner-up used to be named whatever it was, so a Double cell could read DoubleSplit
     * or DoubleSurrender, sending a three-card hand to a move it cannot make either, and a
     * Surrender cell named no fallback at all.
     */
    @Test
    public void surrenderAndDoubleFallBackOnlyToHitOrStand() {
        int checked = 0;
        for (List<PlayerMove> ranking : everyRanking()) {
            PlayerMove best = ranking.get(0);
            if (best != PlayerMove.Surrender && best != PlayerMove.Double) {
                continue;
            }
            String label = bucketRanked(ranking).getCompoundBestMove();
            assertEquals(expectedLabel(best, firstOf(ranking, HIT_OR_STAND)), label,
                    "with payoffs ranked " + ranking + ", " + best + " should fall back to "
                            + "the better of Hit and Stand, the only moves left on a "
                            + "three-card hand");
            checked++;
        }
        assertEquals(130, checked, "65 rankings start with each of Surrender and Double");
    }

    /**
     * Split falls back to the best move other than Surrender.
     *
     * A pair that cannot be split is one at the resplit limit, which came out of a split
     * and still has two cards: it may double, if the rules allow doubling after a split,
     * but it may never surrender. Taking 8,8 against an ace, splitting at -0.3 used to be
     * labelled SplitSurrender when surrendering at -0.5 beat hitting at -0.6.
     */
    @Test
    public void splitFallsBackToAnythingButSurrender() {
        assertEquals("SplitHit",
                bucket(PlayerMove.Split, -0.3, PlayerMove.Surrender, -0.5, PlayerMove.Hit, -0.6)
                        .getCompoundBestMove(),
                "a hand at the resplit limit cannot surrender either, so the fallback is Hit");

        EnumSet<PlayerMove> anythingButSurrender = EnumSet.complementOf(
                EnumSet.of(PlayerMove.Split, PlayerMove.Surrender));
        int checked = 0;
        for (List<PlayerMove> ranking : everyRanking()) {
            if (ranking.get(0) != PlayerMove.Split) {
                continue;
            }
            String label = bucketRanked(ranking).getCompoundBestMove();
            assertEquals(expectedLabel(PlayerMove.Split, firstOf(ranking, anythingButSurrender)),
                    label, "with payoffs ranked " + ranking + ", Split should fall back to "
                            + "the best of Stand, Hit and Double");
            checked++;
        }
        assertEquals(65, checked, "65 rankings start with Split");
    }

    /**
     * A label already in the published tables reads the same from the same payoffs.
     *
     * For Double and Split the old rule named the runner-up whatever it was, and the new
     * one names the best move still available. Where the runner-up is itself still
     * available the two agree, and that covers every compound label the committed tables
     * contain: DoubleHit, DoubleStand, SplitHit, SplitStand and SplitDouble. The only
     * Double and Split labels that change are the ones that named a move the hand could
     * not make. Surrender had no fallback before, and no committed table has a Surrender
     * cell.
     */
    @Test
    public void labelsInThePublishedTablesReadAsBefore() throws IOException {
        for (List<PlayerMove> ranking : everyRanking()) {
            PlayerMove best = ranking.get(0);
            if (ranking.size() < 2) {
                continue;
            }
            PlayerMove runnerUp = ranking.get(1);
            boolean stillAvailable = best == PlayerMove.Double
                    ? HIT_OR_STAND.contains(runnerUp)
                    : best == PlayerMove.Split && runnerUp != PlayerMove.Surrender;
            String label = bucketRanked(ranking).getCompoundBestMove();
            if (stillAvailable) {
                assertEquals(best.name() + runnerUp.name(), label,
                        "with payoffs ranked " + ranking + " the runner-up was already a "
                                + "move the hand can make, so the label should not change");
            }
            else if (best == PlayerMove.Stand || best == PlayerMove.Hit) {
                assertEquals(best.name(), label,
                        "Stand and Hit are always available and carry no fallback");
            }
        }

        TreeSet<String> producible = new TreeSet<>();
        for (List<PlayerMove> ranking : everyRanking()) {
            producible.add(bucketRanked(ranking).getCompoundBestMove().toLowerCase(Locale.ROOT));
        }
        File[] tables = new File("stuffForHTML").listFiles((d, name) -> name.endsWith(".html"));
        assertNotNull(tables, "no stuffForHTML directory to check");
        assertTrue(tables.length > 0, "no generated tables to check");
        Pattern asked = Pattern.compile("class=\"tg-([A-Za-z0-9]+)\"");
        int checked = 0;
        for (File table : tables) {
            Matcher m = asked.matcher(new String(Files.readAllBytes(table.toPath()),
                    StandardCharsets.UTF_8));
            while (m.find()) {
                String tag = m.group(1);
                if (tag.equals("0pky")) {
                    continue;
                }
                assertTrue(producible.contains(tag), table.getName() + " has a cell labelled "
                        + tag + ", which no bucket can produce any more");
                checked++;
            }
        }
        assertTrue(checked > 0, "found no move labels in the published tables");
    }
}
