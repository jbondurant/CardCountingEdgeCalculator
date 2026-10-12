import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LiteratureCheck and its parts (LITERATURE.md section 1), on hand-written fixtures and on pages
 * built here, never on the cached third-party pages, which are not in the repository.
 *
 * What is held:
 * <ul>
 *   <li>the page readers: rows of nested layout tables, entities, a row found by its leading cells
 *   and refused when ambiguous; appendix 9 read cell by cell and refused when a hand is missing or
 *   given twice; the calculator's lookup derived from the form's own labels; the effects of
 *   removal; QFIT's table, read once though the page prints it twice;</li>
 *   <li>the planted change LITERATURE.md asks for: 1e-4 added to one cached cell of appendix 9 is
 *   flagged three ways, by the cached copy's hash, by the spot figure claims.json records when the
 *   cell is that figure, and by the cell-by-cell comparison, which names the cell and turns the
 *   claim from agrees to differs;</li>
 *   <li>the verdict rules, each kind of outcome, and the status wording, which never presents an
 *   AI agent's reading as Justin's;</li>
 *   <li>R taken only from a save of the main file over report-final's states;</li>
 *   <li>the committed register itself: it parses, every claim names a known figure of ours, and its
 *   quotations stay short (copyright);</li>
 *   <li>report-final's tables read by column occurrence, since its headline names two columns 95% CI.</li>
 * </ul>
 */
public class LiteratureCheckTest {

    // ------------------------------------------------------------------ the page readers

    /** A hand-written page: a layout table around a data table, as QFIT's pages are laid out, with entities and a header row. */
    static final String NESTED = "<html><body><table class=\"layout\"><tr><td>Intro &amp; menu"
            + "<table class=\"data\"><tr><th>Name</th><th>Value</th></tr>"
            + "<tr><td>Hi-Lo</td><td>&#8722;1</td></tr>"
            + "<tr><td><b>Zen</b> Count</td><td>.96</td></tr>"
            + "<tr><td>Hi-Lo</td><td>.97</td></tr></table></td></tr></table>"
            + "<script>var x = '<tr><td>not a row</td></tr>';</script></body></html>";

    @Test
    public void rowsOfANestedTableAreItsOwnRowsWithTextDecoded() {
        List<List<String>> rows = LiteratureSources.rows(NESTED);
        List<List<String>> data = new ArrayList<>();
        for (List<String> r : rows) {
            if (!r.isEmpty()) {
                data.add(r);
            }
        }
        assertEquals(Arrays.asList(Arrays.asList("Name", "Value"), Arrays.asList("Hi-Lo", "−1"),
                Arrays.asList("Zen Count", ".96"), Arrays.asList("Hi-Lo", ".97")), data,
                "the layout row's open cell is not a cell, the script's text is not a row, entities are decoded");
        assertEquals(-1, LiteratureSources.number(data.get(1).get(1)), "a printed minus sign reads as a minus");
        assertEquals(".96", LiteratureSources.row(rows, List.of("Zen Count")).get(1));
        IllegalArgumentException two = assertThrows(IllegalArgumentException.class,
                () -> LiteratureSources.row(rows, List.of("Hi-Lo")));
        assertTrue(two.getMessage().contains("two rows"), "a locator that names two rows cannot be checked");
        assertThrows(IllegalArgumentException.class, () -> LiteratureSources.row(rows, List.of("KO")));
    }

    @Test
    public void figuresAsPrintedReadInTheirOwnScale() {
        assertEquals(0.6468, LiteratureSources.number("0.6468%"), 0);
        assertEquals(-0.664, LiteratureSources.number("-.664"), 0);
        assertEquals(0.143, LiteratureSources.number("+0.143%"), 0);
        assertEquals(1.0, LiteratureSources.number("1.0"), 0);
        assertThrows(IllegalArgumentException.class, () -> LiteratureSources.number("0/1"));
        assertThrows(IllegalArgumentException.class, () -> LiteratureSources.number("about .5%"));
    }

    /** Appendix 9's two tables built from values[slot], printed to six places, with the dealer-blackjack table after them. */
    static String appendix9(double[] values) {
        StringBuilder b = new StringBuilder("<html><body><p>Expected return of game:</p>");
        b.append("<table class=\"data\"><tbody><tr><th>Dealer</th><th>Player</th><th>Stand</th><th>Hit</th>"
                + "<th>Double</th><th>Probability</th></tr>");
        for (int deal = 0; deal < Deals.COUNT; deal++) {
            if (Deals.P1[deal] != Deals.P2[deal]) {
                boolean natural = Deals.playerNatural(deal);
                b.append("<tr><td>").append(LiteratureCheck.name(Deals.UP[deal])).append("</td><td>")
                        .append(Deals.P2[deal] == 10 && Deals.P1[deal] == 1 ? "10,A"
                                : LiteratureCheck.name(Deals.P2[deal]) + "," + LiteratureCheck.name(Deals.P1[deal]))
                        .append("</td>");
                for (PlayerMove m : new PlayerMove[]{PlayerMove.Stand, PlayerMove.Hit, PlayerMove.Double}) {
                    b.append("<td>").append(natural ? (m == PlayerMove.Stand ? "1.500000" : "0.100000")
                            : String.format(Locale.ROOT, "%.6f", values[StateValuer.slot(deal, m)])).append("</td>");
                }
                b.append("<td>0.00063275</td></tr>");
            }
        }
        b.append("</tbody></table><table class=\"data\"><tbody><tr><th>Dealer</th><th>Player</th><th>Stand</th>"
                + "<th>Hit</th><th>Double</th><th>Split<br> (DAS=No)</th><th>Split<br> (DAS=Yes)</th><th>Probability</th></tr>");
        for (int deal = 0; deal < Deals.COUNT; deal++) {
            if (Deals.P1[deal] == Deals.P2[deal]) {
                String p = LiteratureCheck.name(Deals.P1[deal]);
                b.append("<tr><td>").append(LiteratureCheck.name(Deals.UP[deal])).append("</td><td>").append(p).append(",")
                        .append(p).append("</td>");
                for (PlayerMove m : new PlayerMove[]{PlayerMove.Stand, PlayerMove.Hit, PlayerMove.Double}) {
                    b.append("<td>").append(String.format(Locale.ROOT, "%.6f", values[StateValuer.slot(deal, m)])).append("</td>");
                }
                // DAS=No first, deliberately different, so a reader of the wrong column is caught.
                b.append("<td>").append(String.format(Locale.ROOT, "%.6f", values[StateValuer.slot(deal, PlayerMove.Split)] - 0.01))
                        .append("</td><td>").append(String.format(Locale.ROOT, "%.6f", values[StateValuer.slot(deal, PlayerMove.Split)]))
                        .append("</td><td>0.00030649</td></tr>");
            }
        }
        b.append("</tbody></table><table><tr><th>Player</th><th>Stand</th><th>Probability</th></tr>"
                + "<tr><td>BJ</td><td>0</td><td>0.00218521</td></tr></table></body></html>");
        return b.toString();
    }

    /** Made-up values for every cell, distinct and easy to recompute: not the game's. */
    static double[] madeUp() {
        double[] v = new double[Deals.COUNT * StateValuer.MOVES.length];
        Arrays.fill(v, Double.NaN);
        for (int deal = 0; deal < Deals.COUNT; deal++) {
            for (PlayerMove m : new PlayerMove[]{PlayerMove.Stand, PlayerMove.Hit, PlayerMove.Double, PlayerMove.Split}) {
                if (m != PlayerMove.Split || Deals.P1[deal] == Deals.P2[deal]) {
                    v[StateValuer.slot(deal, m)] = Math.round(1e6 * (Math.sin(deal * 7 + m.ordinal()) * 0.9)) / 1e6;
                }
            }
        }
        return v;
    }

    @Test
    public void appendix9IsReadCellByCellFromTheDasYesColumn() {
        double[] v = madeUp();
        LiteratureSources.Appendix9 a = LiteratureSources.appendix9(appendix9(v));
        int cells = 0;
        for (int deal = 0; deal < Deals.COUNT; deal++) {
            if (Deals.playerNatural(deal)) {
                assertTrue(Double.isNaN(a.value[StateValuer.slot(deal, PlayerMove.Stand)]), "a natural has no move");
                continue;
            }
            for (PlayerMove m : new PlayerMove[]{PlayerMove.Stand, PlayerMove.Hit, PlayerMove.Double, PlayerMove.Split}) {
                int slot = StateValuer.slot(deal, m);
                if (m != PlayerMove.Split || Deals.P1[deal] == Deals.P2[deal]) {
                    assertEquals(v[slot], a.value[slot], 1e-12, LiteratureCheck.describe(deal) + " " + m);
                    cells++;
                } else {
                    assertTrue(Double.isNaN(a.value[slot]));
                }
            }
        }
        assertEquals(540 * 3 + 100, cells, "540 hands that are not naturals, three moves each, and 100 pair splits");
    }

    @Test
    public void appendix9RefusesAMissingOrDoubledHand() {
        String page = appendix9(madeUp());
        int at = page.indexOf("<tr><td>7</td><td>9,4</td>");
        int end = page.indexOf("</tr>", at) + 5;
        String missing = page.substring(0, at) + page.substring(end);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LiteratureSources.appendix9(missing));
        assertTrue(e.getMessage().contains("4,9 against 7"), e.getMessage());
        String doubled = page.substring(0, end) + page.substring(at, end) + page.substring(end);
        assertThrows(IllegalArgumentException.class, () -> LiteratureSources.appendix9(doubled));
    }

    /** A claims.json of one source, appendix 9 cached as given, and one claim per move like the committed ones. */
    static String register(String sha) {
        return "{\"sources\": [{\"id\": \"woo-a9\", \"title\": \"appendix 9\", \"url\": \"https://example.invalid/a9\", "
                + "\"cache\": {\"file\": \"a9.html\", \"sha256\": \"" + sha + "\", \"fetched\": \"2026-10-12\"}, "
                + "\"status\": \"CHECKED\", \"status_date\": \"2026-10-12\", \"checked_by\": \"AI agent (Claude Code), 2026-10-12\", "
                + "\"read_by_justin\": false}], \"claims\": ["
                + claim("a9.stand", "Stand", "a9.Stand", 1e-6, "6,5", "A")
                + ", " + claim("a9.hit", "Hit", "a9.Hit", 1e-6, "6,5", "A") + "]}";
    }

    static String claim(String id, String move, String ours, double tol, String hand, String up) {
        int deal = Deals.index(LiteratureSources.rank(hand.split(",")[0]), LiteratureSources.rank(hand.split(",")[1]),
                LiteratureSources.rank(up));
        String printed = String.format(Locale.ROOT, "%.6f", madeUp()[StateValuer.slot(deal, PlayerMove.valueOf(move))]);
        return "{\"id\": \"" + id + "\", \"topic\": \"2B\", \"source\": \"woo-a9\", \"locator\": \"every cell\", "
                + "\"what\": \"" + move + "\", \"conditions\": {\"unit\": \"a fraction of the bet\"}, \"same_game\": true, "
                + "\"printed\": \"" + printed + "\", \"extract\": {\"how\": \"appendix9\", \"hand\": \"" + hand + "\", "
                + "\"up\": \"" + up + "\", \"move\": \"" + move + "\"}, \"ours\": \"" + ours + "\", "
                + "\"compare\": {\"rule\": \"at_most\", \"tolerance\": " + tol + "}}";
    }

    /** The check of LITERATURE.md section 1 on the cached copy given, beside the values given as ours. */
    static LiteratureCheck.Result check(Path dir, String page, String recordedSha, double[] ours) throws Exception {
        Files.write(dir.resolve("a9.html"), page.getBytes(StandardCharsets.UTF_8));
        LiteratureCheck.Result r = new LiteratureCheck.Result();
        r.register = LiteratureClaims.parse(register(recordedSha), "fixture");
        Map<String, String> pages = LiteratureCheck.cachedPages(r, dir);
        LiteratureSources.Appendix9 a9 = LiteratureSources.appendix9(pages.get("woo-a9"));
        double[] resplit = new double[Deals.COUNT];
        for (int deal = 0; deal < Deals.COUNT; deal++) {
            resplit[deal] = ours[StateValuer.slot(deal, PlayerMove.Split)];
        }
        LiteratureCheck.compareAppendix9(r, a9, ours, resplit);
        LiteratureCheck.drift(r, pages, a9);
        for (LiteratureClaims.Claim c : r.register.claims) {
            r.outcomes.put(c.id, LiteratureClaims.verdict(c, r.register.source(c.source), r.ours.get(c.ours), r.tests,
                    r.drift.get(c.id)));
        }
        return r;
    }

    /**
     * LITERATURE.md section 1's test: a change of 1e-4 planted in one cached cell must be flagged.
     * Ours here are the fixture's own values, so before the plant every cell agrees to the table's
     * rounding. The plant is in the Stand cell of 6,5 against an ace, the spot figure claims.json
     * records for that claim: the hash, the spot figure and the comparison each flag it. A second
     * plant, in a Hit cell no claim records, is flagged by the hash and the comparison alone.
     */
    @Test
    public void aChangeOf1eMinus4InOneCachedCellIsFlagged(@TempDir Path dir) throws Exception {
        double[] v = madeUp();
        String page = appendix9(v);
        String sha = LiteratureSources.sha256(page.getBytes(StandardCharsets.UTF_8));
        LiteratureCheck.Result clean = check(dir, page, sha, v);
        assertEquals("matches its recorded hash", clean.cache.get("woo-a9"));
        assertTrue(clean.drift.isEmpty(), clean.drift.toString());
        assertEquals(LiteratureClaims.Verdict.AGREES, clean.outcomes.get("a9.stand").verdict);
        assertEquals(LiteratureClaims.Verdict.AGREES, clean.outcomes.get("a9.hit").verdict);

        int deal = Deals.index(5, 6, 1);
        String cell = String.format(Locale.ROOT, "%.6f", v[StateValuer.slot(deal, PlayerMove.Stand)]);
        String planted = String.format(Locale.ROOT, "%.6f", v[StateValuer.slot(deal, PlayerMove.Stand)] + 1e-4);
        String row = "<tr><td>A</td><td>6,5</td><td>" + cell + "</td>";
        assertEquals(page.indexOf(row), page.lastIndexOf(row), "the planted row is one row");
        LiteratureCheck.Result r = check(dir, page.replace(row, "<tr><td>A</td><td>6,5</td><td>" + planted + "</td>"), sha, v);
        assertTrue(r.cache.get("woo-a9").startsWith("CHANGED"), "the hash flags it: " + r.cache.get("woo-a9"));
        assertTrue(r.drift.get("a9.stand").contains(planted), "the spot figure flags it: " + r.drift.get("a9.stand"));
        assertEquals(1e-4, r.ours.get("a9.Stand").value, 1e-9, "the comparison finds the planted gap");
        assertTrue(r.ours.get("a9.Stand").printed.contains("6,5 against A"), "and names the cell: "
                + r.ours.get("a9.Stand").printed);
        assertNotEquals(LiteratureClaims.Verdict.AGREES, r.outcomes.get("a9.stand").verdict);

        int hitDeal = Deals.index(2, 9, 7);
        String hitCell = String.format(Locale.ROOT, "%.6f", v[StateValuer.slot(hitDeal, PlayerMove.Hit)]);
        String hitRow = "<tr><td>7</td><td>9,2</td><td>" + String.format(Locale.ROOT, "%.6f",
                v[StateValuer.slot(hitDeal, PlayerMove.Stand)]) + "</td><td>" + hitCell + "</td>";
        assertTrue(page.contains(hitRow));
        String hitPlanted = hitRow.replace("<td>" + hitCell + "</td>", "<td>" + String.format(Locale.ROOT, "%.6f",
                v[StateValuer.slot(hitDeal, PlayerMove.Hit)] - 1e-4) + "</td>");
        LiteratureCheck.Result h = check(dir, page.replace(hitRow, hitPlanted), sha, v);
        assertTrue(h.cache.get("woo-a9").startsWith("CHANGED"));
        assertFalse(h.drift.containsKey("a9.hit"), "no claim records that cell, so only the hash and the comparison see it");
        assertEquals(1e-4, h.ours.get("a9.Hit").value, 1e-9);
        assertTrue(h.ours.get("a9.Hit").printed.contains("9,2 against 7"), h.ours.get("a9.Hit").printed);
        assertTrue(h.ours.get("a9.Hit").printed.contains("ours above theirs by more than 1e-06 in 1"),
                "ours now exceeds theirs in one cell, which the hit's reason says cannot happen");
        assertEquals(LiteratureClaims.Verdict.UNEXPLAINED, h.outcomes.get("a9.hit").verdict,
                "the fixture's hit claim names no reason");
    }

    /**
     * A hand-written calculator in the Wizard of Odds' shape, two decks of options instead of six,
     * with the options of the doubling question listed in another order than their values. The
     * lookup must follow the values the labels carry, not the order they are listed in.
     */
    static String calculatorPage(String eightDeckEntry, String secondEntry) {
        return "<form name=\"selectForm\">"
                + "<td>Decks: <input name=\"SELECT1\" type=\"radio\" value=\"0\" />1 <input name=\"SELECT1\" type=\"radio\" value=\"1\" />8</td>"
                + "<td>Soft 17: <input checked=\"checked\" name=\"SELECT2\" type=\"radio\" value=\"0\" />Stands "
                + "<input name=\"SELECT2\" type=\"radio\" value=\"1\" />Hits</td>"
                + "<td>Double on: <input name=\"SELECT4\" type=\"radio\" value=\"1\" />9-11 only "
                + "<input checked=\"checked\" name=\"SELECT4\" type=\"radio\" value=\"0\" />Any first two cards</td>"
                + "<td>Pays: <input checked=\"checked\" name=\"SELECT10\" type=\"radio\" value=\"0\" />3 to 2 "
                + "<input name=\"SELECT10\" type=\"radio\" value=\"1\" />6 to 5</td></form>"
                + "<script>var edgeTable = new Array(2);\n"
                + "edgeTable[1][1][0]=" + eightDeckEntry + ";\nedgeTable[1][1][1]=0.9;\nedgeTable[1][0][0]=0.4;\n"
                + (secondEntry == null ? "" : "edgeTable[1][1][0]=" + secondEntry + ";\n")
                + "function CalculateResult()\n{\n"
                + "    for (i=0; i < 2; i++) if (document.selectForm.SELECT1[i].checked) a = i;\n"
                + "    for (i=0; i < 2; i++) if (document.selectForm.SELECT2[i].checked) b = i;\n"
                + "    for (i=0; i < 2; i++) if (document.selectForm.SELECT4[i].checked) d = i;\n"
                + "    var edgeValue = edgeTable[a][b][d];\n"
                + "    if (document.selectForm.SELECT10[1].checked)\n    {\n        edgeValue+=1.3;\n    }\n"
                + "    edgeValueBS = edgeValue;\n    edgeValueCS = edgeValueBS;\n"
                + "    if (a == 0) { edgeValueBS += 0.1517; }\n    if (a == 1) { edgeValueBS += 0.0159; }\n"
                + "    edgeValueCS = edgeValueBS;\n"
                + "    if (a == 0) { edgeValueCS -= 0.113; }\n    if (a == 1) { edgeValueCS -= 0.014; }\n}\n</script>";
    }

    static Map<String, String> answers() {
        Map<String, String> w = new LinkedHashMap<>();
        w.put("SELECT1", "8");
        w.put("SELECT2", "Hits");
        w.put("SELECT4", "Any first two cards");
        w.put("SELECT10", "3 to 2");
        return w;
    }

    @Test
    public void theCalculatorIsReadFromItsCodeByTheFormsOwnLabels() {
        LiteratureSources.Calculator k = LiteratureSources.calculator(calculatorPage("0.644928", "0.644928"), answers());
        assertEquals(Arrays.asList("SELECT1", "SELECT2", "SELECT4"), k.order);
        assertEquals(0, (int) k.answers.get("SELECT4"), "Any first two cards carries value 0 though it is listed second");
        assertEquals(0.644928, k.optimal, 0);
        assertEquals(2, k.assignments);
        assertEquals(0.66083, LiteratureSources.Calculator.shown(k.cutCard()), 0);
        assertEquals(0.64683, LiteratureSources.Calculator.shown(k.shuffler()), 0);
        assertThrows(IllegalArgumentException.class, () -> LiteratureSources.calculator(calculatorPage("0.644928", "0.7"),
                answers()), "an entry assigned twice with two values");
        Map<String, String> six = answers();
        six.put("SELECT1", "6");
        assertThrows(IllegalArgumentException.class, () -> LiteratureSources.calculator(calculatorPage("0.644928", null), six),
                "an answer the form does not offer");
        Map<String, String> fiveToSix = answers();
        fiveToSix.put("SELECT10", "6 to 5");
        assertThrows(IllegalArgumentException.class, () -> LiteratureSources.calculator(calculatorPage("0.644928", null),
                fiveToSix), "only a 3 to 2 payout is read");
    }

    static final String EOR = "<table class=\"data\"><tr><td>Card</td><td>Stand Soft 17</td><td>Hit Soft 17</td></tr>"
            + "<tr><td>2</td><td>0.069%</td><td>0.071%</td></tr><tr><td>3</td><td>0.082%</td><td>0.086%</td></tr>"
            + "<tr><td>4</td><td>0.110%</td><td>0.117%</td></tr><tr><td>5</td><td>0.141%</td><td>0.143%</td></tr>"
            + "<tr><td>6</td><td>0.079%</td><td>0.084%</td></tr><tr><td>7</td><td>0.041%</td><td>0.038%</td></tr>"
            + "<tr><td>8</td><td>-0.008%</td><td>-0.010%</td></tr><tr><td>9</td><td>-0.040%</td><td>-0.045%</td></tr>"
            + "<tr><td>10</td><td>-0.091%</td><td>-0.097%</td></tr><tr><td>Ace</td><td>-0.094%</td><td>-0.084%</td></tr></table>";

    @Test
    public void effectsOfRemovalAreReadByColumnAndScaledFromSixDecksToEight() {
        double[] h = LiteratureSources.effectOfRemoval(EOR, "Hit Soft 17");
        assertEquals(0.143, h[5], 0);
        assertEquals(-0.084, h[1], 0);
        assertEquals(-0.091, LiteratureSources.effectOfRemoval(EOR, "Stand Soft 17")[10], 0);
        assertThrows(IllegalArgumentException.class, () -> LiteratureSources.effectOfRemoval(EOR.replace(
                "<tr><td>9</td><td>-0.040%</td><td>-0.045%</td></tr>", ""), "Hit Soft 17"), "a rank missing");
        // Ours as fractions per card: the published ones scaled, in per cent, divided by 100.
        EffectsOfRemoval.Effects e = new EffectsOfRemoval.Effects(1);
        for (int r = 1; r <= 10; r++) {
            e.removed[r] = h[r] * 311.0 / 415.0 / 100;
            e.added[r] = -e.removed[r];
        }
        LiteratureCheck.Result res = new LiteratureCheck.Result();
        LiteratureCheck.compareEffects(res, e, h);
        assertEquals(h[5] * 311 / 415, res.ours.get("eor.5").value, 1e-12, "ours per card, in per cent");
        assertEquals(0, res.ours.get("eor.angle").value, 1e-6, "the same direction");
        assertTrue(res.ours.get("eor.signs").pass);
        assertEquals(0.143 * 311 / 415, (Double) res.tables.get("literature_effects_of_removal").get(4).get("theirs_scaled"),
                1e-12, "311/415 takes six decks to eight, not the other way");
        e.removed[8] = -e.removed[8];
        e.added[8] = -e.added[8];
        LiteratureCheck.Result flipped = new LiteratureCheck.Result();
        LiteratureCheck.compareEffects(flipped, e, h);
        assertFalse(flipped.ours.get("eor.signs").pass);
        assertTrue(flipped.ours.get("eor.signs").printed.contains("8"));
    }

    static final String QFIT = "<table><tr><td>Strategy</td><td>A</td><td>2</td><td>3</td><td>4</td><td>5</td><td>6</td>"
            + "<td>7</td><td>8</td><td>9</td><td>T</td><td>BC</td><td>PE</td><td>IC</td><td>Ease</td><td>Type</td></tr>"
            + "<tr><td>Hi-Lo</td><td>-1</td><td>1</td><td>1</td><td>1</td><td>1</td><td>1</td><td>0</td><td>0</td><td>0</td>"
            + "<td>-1</td><td>.97</td><td>.51</td><td>.76</td><td>6</td><td>B1</td></tr>"
            + "<tr><td>Red Seven</td><td>-1</td><td>1</td><td>1</td><td>1</td><td>1</td><td>1</td><td>0/1</td><td>0</td>"
            + "<td>0</td><td>-1</td><td>.98</td><td>.54</td><td>.78</td><td>7</td><td>USC1</td></tr>"
            + "<tr><td>Strategy</td><td>BC</td><td>PE</td><td>IC</td><td>Ease</td><td>Type</td></tr>"
            + "<tr><td>Hi-Lo</td><td>.97</td><td>.51</td><td>.76</td><td>6</td><td>B1</td></tr></table>";

    @Test
    public void qfitsTableIsReadOnceWithASuitTagAsItsAverage() {
        List<LiteratureSources.QfitRow> t = LiteratureSources.qfitTable(QFIT);
        assertEquals(2, t.size(), "the second, tagless printing is not read");
        LiteratureSources.QfitRow red = LiteratureSources.qfit(t, "Red Seven");
        assertEquals(0.5, red.tag(7), 0, "0/1: counted in one colour only");
        assertEquals(".78", red.ic);
        assertEquals(-1, LiteratureSources.qfit(t, "Hi-Lo").tag(1), 0);
        assertArrayEquals(LiteratureCheck.tagsByRank(CountingSystem.named("Red Seven (Red 7)")),
                new double[]{0, -1, 1, 1, 1, 1, 1, 0.5, 0, 0, -1}, 1e-12, "the catalog's Red Seven, averaged over suits");
    }

    // ------------------------------------------------------------------ verdicts and wording

    static LiteratureClaims.Source source(String status) {
        LiteratureClaims.Source s = new LiteratureClaims.Source();
        s.id = "s";
        s.title = "A source";
        s.status = status;
        s.statusDate = "2026-10-09";
        s.checkedBy = "AI agent (Claude Code), 2026-10-09";
        return s;
    }

    static LiteratureClaims.Claim rule(String rule, String printed, double tolerance, boolean sameGame) {
        LiteratureClaims.Claim c = new LiteratureClaims.Claim();
        c.id = "c";
        c.rule = rule;
        c.printed = printed;
        c.tolerance = tolerance;
        c.sameGame = sameGame;
        c.ours = "o";
        return c;
    }

    static LiteratureClaims.Ours ours(double value, double lo, double hi) {
        LiteratureClaims.Ours o = new LiteratureClaims.Ours("o");
        o.value = value;
        o.lo = lo;
        o.hi = hi;
        return o;
    }

    static LiteratureClaims.Verdict v(LiteratureClaims.Claim c, LiteratureClaims.Source s, LiteratureClaims.Ours o,
                                      Map<String, Boolean> tests, String drift) {
        return LiteratureClaims.verdict(c, s, o, tests, drift).verdict;
    }

    @Test
    public void aVerdictComesFromTheRuleTheConditionsAndWhatCodeTested() {
        LiteratureClaims.Source read = source("CHECKED");
        Map<String, Boolean> none = new HashMap<>();
        LiteratureClaims.Ours o = ours(-0.660, -0.671, -0.649);
        assertEquals(LiteratureClaims.Verdict.AGREES, v(rule("interval", "-.664", 0, true), read, o, none, null));
        assertEquals(LiteratureClaims.Verdict.DIRECTION, v(rule("interval", "-.664", 0, false), read, o, none, null),
                "the rule holds but the game differs");
        LiteratureClaims.Claim far = rule("interval", "-.700", 0, true);
        assertEquals(LiteratureClaims.Verdict.UNEXPLAINED, v(far, read, o, none, null), "no reason named");
        far.reason = "a likely reason";
        assertEquals(LiteratureClaims.Verdict.UNTESTED, v(far, read, o, none, null), "a reason no code tested");
        far.testedBy = "t";
        assertEquals(LiteratureClaims.Verdict.UNTESTED, v(far, read, o, none, null), "a test that did not run");
        Map<String, Boolean> passed = new HashMap<>();
        passed.put("t", true);
        assertEquals(LiteratureClaims.Verdict.TESTED, v(far, read, o, passed, null));
        Map<String, Boolean> failed = new HashMap<>();
        failed.put("t", false);
        assertEquals(LiteratureClaims.Verdict.UNEXPLAINED, v(far, read, o, failed, null),
                "an explanation tested and found wanting explains nothing");
        assertEquals(LiteratureClaims.Verdict.NOT_COMPARED, v(rule("interval", "-.664", 0, true), source("SECONDARY"), o,
                none, null), "a figure known only secondhand is not stated as published");
        assertEquals(LiteratureClaims.Verdict.NOT_COMPARED, v(rule("interval", "-.664", 0, true), source("NOT READ"), o,
                none, null));
        assertEquals(LiteratureClaims.Verdict.NOT_COMPARED, v(rule("interval", "-.664", 0, true), read, o, none,
                "the page now gives -.665"), "a figure the cache cannot confirm");
        assertEquals(LiteratureClaims.Verdict.NOT_COMPARED, v(rule("interval", "-.664", 0, true), read,
                LiteratureClaims.Ours.pending("o", "a later run"), none, null));
        assertEquals(LiteratureClaims.Verdict.NOT_COMPARED, v(rule("none", null, 0, true), read, o, none, null));
    }

    @Test
    public void eachRuleTestsWhatItSays() {
        LiteratureClaims.Source read = source("CHECKED");
        Map<String, Boolean> none = new HashMap<>();
        LiteratureClaims.Claim within = rule("within", "0.6468%", 0.00005, true);
        within.multiply = -1;
        assertEquals(LiteratureClaims.Verdict.AGREES, v(within, read, ours(-0.64684, Double.NaN, Double.NaN), none, null));
        assertEquals(LiteratureClaims.Verdict.UNEXPLAINED, v(within, read, ours(-0.64645, Double.NaN, Double.NaN), none, null),
                "the sign is applied before the comparison, and 0.00035 is outside 0.00005");
        LiteratureClaims.Claim sign = rule("sign", null, 0, true);
        sign.sign = "+";
        assertEquals(LiteratureClaims.Verdict.DIRECTION, v(sign, read, ours(0.003, 0.001, 0.006), none, null),
                "a direction, even in the same game, is only a direction");
        assertEquals(LiteratureClaims.Verdict.UNEXPLAINED, v(sign, read, ours(0.003, -0.002, 0.008), none, null),
                "the whole interval must be on the published side, not the estimate alone");
        sign.sign = "-";
        assertEquals(LiteratureClaims.Verdict.DIRECTION, v(sign, read, ours(-0.076, -0.103, -0.049), none, null));
        LiteratureClaims.Claim atMost = rule("at_most", null, 1e-6, true);
        assertEquals(LiteratureClaims.Verdict.AGREES, v(atMost, read, ours(5.7e-7, Double.NaN, Double.NaN), none, null));
        assertEquals(LiteratureClaims.Verdict.UNEXPLAINED, v(atMost, read, ours(1e-4, Double.NaN, Double.NaN), none, null));
        LiteratureClaims.Claim below = rule("below", "0.6449%", 0, true);
        below.multiply = -1;
        assertEquals(LiteratureClaims.Verdict.DIRECTION, v(below, read, ours(-0.64645, Double.NaN, Double.NaN), none, null));
        assertEquals(LiteratureClaims.Verdict.UNEXPLAINED, v(below, read, ours(-0.640, Double.NaN, Double.NaN), none, null));
        LiteratureClaims.Claim check = rule("check", null, 0, true);
        LiteratureClaims.Ours passed = new LiteratureClaims.Ours("o");
        passed.pass = true;
        assertEquals(LiteratureClaims.Verdict.AGREES, v(check, read, passed, none, null));
        passed.pass = false;
        assertEquals(LiteratureClaims.Verdict.UNEXPLAINED, v(check, read, passed, none, null));
    }

    /** No status is worded as Justin's own reading unless read_by_justin says so, with the date he gives. */
    @Test
    public void aStatusIsWordedFromWhoReadItNeverAsJustinsUnlessHeSaysSo() {
        LiteratureClaims all = new LiteratureClaims();
        LiteratureClaims.Source checked = source("CHECKED");
        checked.statusDate = "2026-10-12";
        checked.checkedBy = "AI agent (Claude Code), 2026-10-12";
        assertEquals("checked against the source by an AI agent (Claude Code) on 12 Oct 2026",
                LiteratureClaims.statusWords(checked, all));
        LiteratureClaims.Source read = source("READ");
        read.statusDate = "2026-10-08";
        assertEquals("read by an AI agent (Claude Code) on 8 Oct 2026", LiteratureClaims.statusWords(read, all));
        LiteratureClaims.Source secondary = source("SECONDARY");
        secondary.via = "s";
        all.sources.add(read);
        assertEquals("known only through A source", LiteratureClaims.statusWords(secondary, all));
        assertEquals("not read", LiteratureClaims.statusWords(source("NOT READ"), all));
        for (LiteratureClaims.Source s : new LiteratureClaims.Source[]{checked, read, secondary}) {
            assertFalse(LiteratureClaims.statusWords(s, all).contains("Justin"));
        }
        read.readByJustin = true;
        read.readByJustinDate = "2026-10-20";
        assertEquals("read by an AI agent (Claude Code) on 8 Oct 2026; read by Justin on 20 Oct 2026",
                LiteratureClaims.statusWords(read, all));
    }

    @Test
    public void theRegisterRefusesASourceThatDoesNotSayWhoReadIt() {
        String ok = "{\"sources\": [{\"id\": \"a\", \"title\": \"T\", \"url\": \"https://example.invalid\", "
                + "\"snapshot\": \"https://web.archive.org/x\", \"status\": \"READ\", \"status_date\": \"2026-10-08\", "
                + "\"checked_by\": \"AI agent (Claude Code), 2026-10-08\", \"read_by_justin\": false}], \"claims\": []}";
        assertEquals(1, LiteratureClaims.parse(ok, "ok").sources.size());
        assertThrows(IllegalArgumentException.class, () -> LiteratureClaims.parse(ok.replace(
                "\"checked_by\": \"AI agent (Claude Code), 2026-10-08\", ", ""), "no checked_by"));
        assertThrows(IllegalArgumentException.class, () -> LiteratureClaims.parse(ok.replace(
                ", \"read_by_justin\": false", ""), "no read_by_justin"));
        assertThrows(IllegalArgumentException.class, () -> LiteratureClaims.parse(ok.replace(
                "\"read_by_justin\": false", "\"read_by_justin\": true"), "Justin read it, but no date"));
        assertThrows(IllegalArgumentException.class, () -> LiteratureClaims.parse(ok.replace(
                "\"snapshot\": \"https://web.archive.org/x\", ", ""), "read, with a URL, but neither a hash nor a snapshot"));
        assertThrows(IllegalArgumentException.class, () -> LiteratureClaims.parse(ok.replace("\"READ\"", "\"SEEN\""),
                "an unknown status"));
        assertThrows(IllegalArgumentException.class, () -> LiteratureClaims.parse(ok.replace("\"READ\"", "\"SECONDARY\""),
                "secondary, but through nothing named"));
    }

    // ------------------------------------------------------------------ R from step 7's save

    static final String MAIN = "CeilingReport\ncommit 97bf834\nstates /x/runs/montreal-80.states, 1340719767 bytes";
    static final List<String> REPORT = Arrays.asList("## The betting correlation and its ceiling", "",
            "R, the multiple correlation of the chart advantage regressed on the shares left over these 60743 valued "
                    + "states weighted 1/q, is 0.9876: no linear count's BC on these states can exceed it.");

    @Test
    public void rIsTakenOnlyFromASaveOfTheMainFileOverReportFinalsStates() {
        assertEquals(0.98761, LiteratureCheck.ceilingR(MAIN, 0.98761, REPORT, 60743), 0);
        assertThrows(IllegalArgumentException.class, () -> LiteratureCheck.ceilingR(MAIN.replace("montreal-80", "twin-80"),
                0.98761, REPORT, 60743), "the twin file's R is never used for BC/R");
        assertThrows(IllegalArgumentException.class, () -> LiteratureCheck.ceilingR(MAIN, 0.98761, REPORT, 60764),
                "R over other states than the BC's");
        assertThrows(IllegalArgumentException.class, () -> LiteratureCheck.ceilingR(MAIN, 0.98857, REPORT, 60743),
                "a save and a report that disagree on R");
        assertThrows(IllegalArgumentException.class, () -> LiteratureCheck.ceilingR(MAIN, Double.NaN, REPORT, 60743));
        assertThrows(IllegalArgumentException.class, () -> LiteratureCheck.ceilingR(MAIN, 0.98761,
                Arrays.asList("no R here"), 60743));
    }

    // ------------------------------------------------------------------ report-final, as printed

    static final List<String> PRINTED = Arrays.asList("# Report", "", "## Headline: EV per round", "",
            "### 80% penetration (average bet 1.503 units; c = 3.30)", "",
            "| Row | EV | SE | Bias | 95% CI | Diff from Hi-Lo | 95% CI | Simultaneous |",
            "|---|---|---|---|---|---|---|---|",
            "| Hi-Lo = Hi-Lo Lite | +0.294 | 0.018 | -0.000 | [+0.260, +0.329] |  |  |  |",
            "| KO (Knock-Out) = REKO | +0.297 | 0.018 | -0.000 | [+0.263, +0.332] | +0.003 | [+0.001, +0.006] | [-0.002, +0.008] |",
            "", "## Flat play", "", "### 80% penetration", "", "| Row | PE | 95% CI |", "|---|---|---|",
            "| Hi-Lo = Hi-Lo Lite | 0.460 | [0.455, 0.465] b |");

    @Test
    public void reportTablesAreReadByHeadingAndColumnOccurrence() {
        LiteraturePrinted.Table h = LiteraturePrinted.table(PRINTED, "Headline: EV per round", "80% penetration*");
        assertEquals("[+0.001, +0.006]", h.cell("KO (Knock-Out) = REKO", "95% CI", 2), "the difference's interval");
        assertEquals("[+0.263, +0.332]", h.cell("KO (Knock-Out) = REKO", "95% CI"), "the estimate's interval");
        LiteratureClaims.Ours d = LiteratureCheck.estimate("diff", h.cell("KO (Knock-Out) = REKO", "Diff from Hi-Lo") + " "
                + h.cell("KO (Knock-Out) = REKO", "95% CI", 2), "fixture");
        assertEquals(0.003, d.value, 0);
        assertEquals(0.001, d.lo, 0);
        assertEquals(0.006, d.hi, 0);
        LiteraturePrinted.Table pe = LiteraturePrinted.table(PRINTED, "Flat play", "80% penetration");
        assertArrayEquals(new double[]{0.455, 0.465}, LiteraturePrinted.interval(pe.cell("Hi-Lo = Hi-Lo Lite", "95% CI")), 0,
                "a basic interval's trailing b is read past");
        assertThrows(IllegalArgumentException.class, () -> LiteraturePrinted.table(PRINTED, "80% penetration*"),
                "a subheading alone is ambiguous: two sections have one");
        assertThrows(IllegalArgumentException.class, () -> h.cell("Zen", "EV"));
    }

    @Test
    public void theQuadraticThroughThePerKeyMeansCurvesTheWayTheyDo() {
        assertEquals(0.5, LiteratureCheck.quadratic(new double[]{0, 0.5, 2, 4.5, 8, 12.5}), 1e-12);
        assertEquals(0, LiteratureCheck.quadratic(new double[]{1, 2, 3, 4, 5, 6}), 1e-12);
        assertEquals(1.0, LiteratureCheck.spearman(Arrays.asList(new double[]{1, 10}, new double[]{2, 20},
                new double[]{3, 30})), 1e-12);
        assertEquals(-1.0, LiteratureCheck.spearman(Arrays.asList(new double[]{1, 30}, new double[]{2, 20},
                new double[]{3, 10})), 1e-12);
    }

    // ------------------------------------------------------------------ the committed register

    /** Our figures LiteratureCheck can work out, by key; a row label is checked against the catalog separately. */
    static boolean knownOurs(String key) {
        if (key.matches("exact\\.edge|a9\\.(Stand|Hit|Double)|a9\\.Split\\.([1-9]|10)|eor\\.([1-9]|10|signs|angle)"
                + "|report\\.(chart|perfect)\\.(70|75|80)|cutcard\\.cost\\.80|ev\\.hilo\\.(70|75|80)"
                + "|perkey\\.(step|bends|crossing)|theorem|pen\\.hilo|sidecount\\.hiopt2|tags\\.qfit|pe\\.order|ic\\.perfect"
                + "|decksum\\.(KO \\(Knock-Out\\)|Red Seven)|irc\\.KO \\(Knock-Out\\)|start\\.Red Seven|vidami\\.angle\\.(an|ar)"
                + "|variance\\.chart\\.80|wide\\.shape|wong\\.shape|f1\\.heldout")) {
            return true;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(diff|bc|ic|pe|bcr)\\.(.+)$").matcher(key);
        if (!m.find()) {
            return false;
        }
        List<String> names = new ArrayList<>();
        for (CountingSystem s : CountingSystem.all()) {
            names.add(s.name);
        }
        for (String part : m.group(2).replaceFirst(" \\+ ace side count$", "").split(" = ")) {
            if (!names.contains(part)) {
                return false;
            }
        }
        return true;
    }

    @Test
    public void theCommittedRegisterParsesAndNamesOnlyFiguresTheCheckWorksOut() throws Exception {
        LiteratureClaims l = LiteratureClaims.read(Paths.get("literature", "claims.json"));
        assertTrue(l.claims.size() > 50, "the register holds its claims");
        for (LiteratureClaims.Claim c : l.claims) {
            if (c.ours != null) {
                assertTrue(knownOurs(c.ours), c.id + " names " + c.ours + ", which LiteratureCheck does not work out");
            }
            assertNotNull(c.conditions.get("unit"), c.id);
        }
        assertTrue(knownOurs("bcr.Hi-Opt II + ace side count"));
        assertFalse(knownOurs("bc.Hi-Lo = High Low"), "a row label must be the catalog's names");
        for (LiteratureClaims.Source s : l.sources) {
            if (s.read()) {
                assertTrue(s.checkedBy.startsWith("AI agent"), s.id + ": every reading so far was an AI agent's: "
                        + s.checkedBy);
            }
        }
    }

    /** Copyright (LITERATURE.md section 4): short quotations only, none over fifteen words. */
    @Test
    public void theRegistersQuotationsAreShort() throws Exception {
        Document root = Document.parse(new String(Files.readAllBytes(Paths.get("literature", "claims.json")),
                StandardCharsets.UTF_8));
        int quotes = 0;
        for (Document c : root.getList("claims", Document.class)) {
            Document x = (Document) c.get("extract");
            if (x != null && x.getString("quote") != null) {
                quotes++;
                int words = x.getString("quote").trim().split("\\s+").length;
                assertTrue(words <= 15, c.getString("id") + "'s quotation has " + words + " words");
            }
        }
        assertTrue(quotes > 10);
    }

    // ------------------------------------------------------------------ what is written

    @Test
    public void thePageSentenceDropsAKindWithNoClaims() {
        Map<LiteratureClaims.Verdict, Integer> n = new LinkedHashMap<>();
        for (LiteratureClaims.Verdict v : LiteratureClaims.Verdict.values()) {
            n.put(v, 0);
        }
        n.put(LiteratureClaims.Verdict.AGREES, 3);
        n.put(LiteratureClaims.Verdict.UNTESTED, 2);
        n.put(LiteratureClaims.Verdict.NOT_COMPARED, 9);
        assertEquals("Of the 5 published figures I compared with mine, 3 agree, and 2 differ for a reason not yet tested.",
                LiteratureOutput.sentence(n));
    }

    @Test
    public void theJsonWriterIsReadBackAsWritten() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", "a \"quoted\" back\\slash, Vidámi\nnext");
        m.put("n", 3);
        m.put("x", 0.1 + 0.2);
        m.put("whole", 2.0);
        m.put("none", null);
        m.put("list", Arrays.asList(true, "b"));
        Document d = Document.parse(LiteratureOutput.json(m));
        assertEquals(m.get("text"), d.getString("text"));
        assertEquals(0.1 + 0.2, d.getDouble("x"), 0, "a double comes back to the same bits");
        assertEquals(2.0, d.getDouble("whole"), 0);
        assertEquals(3, (int) d.getInteger("n"));
        assertNull(d.get("none"));
        assertEquals(Arrays.asList(true, "b"), d.getList("list", Object.class));
    }
}
