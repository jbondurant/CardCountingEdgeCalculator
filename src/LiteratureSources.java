import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the published pages LiteratureCheck compares with (LITERATURE.md section 1): the cached
 * copy of each page, its SHA-256, its tables and its text.
 *
 * The pages are third-party HTML, cached outside git (copyright), so only what is read here and
 * printed as single figures ever reaches the repository. Nothing here fetches: an AI agent
 * fetched each page once and recorded its hash in literature/claims.json, and these methods read
 * that copy. A figure is taken from a page's own table or code, never from a summary of it, so
 * that a change to the page shows up as a change to the figure (LITERATURE.md section 4, drift).
 *
 * The parsing is deliberately plain (regular expressions over rows and cells, not a DOM): the
 * tables read here are flat, and a parser that fails loudly on a changed layout is what drift
 * detection needs. Every reader refuses a table that is not the shape it expects instead of
 * returning what it found.
 */
final class LiteratureSources {

    private LiteratureSources() {
    }

    // ------------------------------------------------------------------ files

    static String sha256(byte[] bytes) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder b = new StringBuilder();
            for (byte x : h) {
                b.append(String.format("%02x", x & 0xff));
            }
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(Path file) throws IOException {
        return sha256(Files.readAllBytes(file));
    }

    /**
     * A cached page's text: UTF-8 where it decodes as UTF-8, otherwise Latin-1. QFIT's pages are
     * Windows-1252 and the Wizard of Odds' are UTF-8; Latin-1 never fails, and every character
     * a figure is made of is the same in both.
     */
    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    static String read(Path file) throws IOException {
        return decode(Files.readAllBytes(file));
    }

    // ------------------------------------------------------------------ text and tables

    private static final Pattern SCRIPT = Pattern.compile("(?is)<script\\b.*?</script>");
    private static final Pattern STYLE = Pattern.compile("(?is)<style\\b.*?</style>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern ROW_START = Pattern.compile("(?i)<tr\\b");
    private static final Pattern CELL = Pattern.compile("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]>");

    /** HTML entities this repo's sources use, and numeric ones; anything else is left as written. */
    static String entities(String s) {
        Matcher m = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String e = m.group(1);
            String r;
            if (e.startsWith("#x") || e.startsWith("#X")) {
                r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
            } else if (e.startsWith("#")) {
                r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
            } else {
                switch (e) {
                    case "amp": r = "&"; break;
                    case "lt": r = "<"; break;
                    case "gt": r = ">"; break;
                    case "quot": r = "\""; break;
                    case "apos": r = "'"; break;
                    case "nbsp": r = " "; break;
                    case "rsquo": case "lsquo": r = "'"; break;
                    case "rdquo": case "ldquo": r = "\""; break;
                    case "ndash": r = "\u2013"; break;
                    case "mdash": r = "\u2014"; break;
                    case "minus": r = "\u2212"; break;
                    default: r = m.group(0);
                }
            }
            m.appendReplacement(b, Matcher.quoteReplacement(r));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** Collapses white space, the no-break space included, and trims. */
    static String squeeze(String s) {
        return s.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    /** A fragment of HTML as the text a reader sees: tags dropped, entities decoded, space squeezed. */
    static String plain(String html) {
        return squeeze(entities(TAG.matcher(html).replaceAll(" ")));
    }

    /** The page's visible text: scripts and styles dropped, then as plain. */
    static String text(String html) {
        return plain(STYLE.matcher(SCRIPT.matcher(html).replaceAll(" ")).replaceAll(" "));
    }

    /**
     * Every table row of the page, in order, as its cells' plain text. Scripts and styles are
     * dropped first. A row runs from its &lt;tr&gt; to its &lt;/tr&gt; or to the next &lt;tr&gt;,
     * whichever comes first, because QFIT lays its page out in tables and nests the data table in
     * a layout row: matched to its own &lt;/tr&gt;, that outer row would swallow the data table's
     * header. A cell left open when its row ends (the layout cell around a nested table) is not a
     * cell of that row.
     */
    static List<List<String>> rows(String html) {
        String s = STYLE.matcher(SCRIPT.matcher(html).replaceAll(" ")).replaceAll(" ");
        String lower = s.toLowerCase(Locale.ROOT);
        List<Integer> starts = new ArrayList<>();
        Matcher r = ROW_START.matcher(s);
        while (r.find()) {
            starts.add(r.start());
        }
        List<List<String>> out = new ArrayList<>();
        for (int k = 0; k < starts.size(); k++) {
            int from = starts.get(k);
            int next = k + 1 < starts.size() ? starts.get(k + 1) : s.length();
            int close = lower.indexOf("</tr>", from);
            int to = close >= 0 && close < next ? close : next;
            List<String> cells = new ArrayList<>();
            Matcher c = CELL.matcher(s.substring(from, to));
            while (c.find()) {
                cells.add(plain(c.group(1)));
            }
            out.add(cells);
        }
        return out;
    }

    /**
     * The one row whose leading cells are exactly the ones given, refusing none or several: a
     * locator that names a row ambiguously cannot be checked against the source.
     */
    static List<String> row(List<List<String>> rows, List<String> leading) {
        List<String> found = null;
        for (List<String> r : rows) {
            if (r.size() >= leading.size() && r.subList(0, leading.size()).equals(leading)) {
                if (found != null) {
                    throw new IllegalArgumentException("two rows start " + leading);
                }
                found = r;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("no row starts " + leading);
        }
        return found;
    }

    // ------------------------------------------------------------------ numbers as printed

    /**
     * A figure as a source prints it: "0.6468%", "-.664", "+0.143%", "\u22120.449008", ".97", "1.0".
     * The per cent sign is dropped, not applied: a claim records its unit separately, and the value
     * stays in the source's own scale. Anything else that is not a plain number is refused.
     */
    static double number(String printed) {
        String s = squeeze(printed).replace('\u2212', '-').replace('\u2013', '-');
        if (s.endsWith("%")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.startsWith("+")) {
            s = s.substring(1);
        }
        if (!s.matches("-?(\\d+\\.?\\d*|\\.\\d+)")) {
            throw new IllegalArgumentException("not a number as printed: \"" + printed + "\"");
        }
        return Double.parseDouble(s);
    }

    /** A rank as the tables print it: A for the ace, 2 to 10 (and T, or a ten-valued face) for the rest. */
    static int rank(String printed) {
        String s = printed.trim().toUpperCase(Locale.ROOT);
        switch (s) {
            case "A": case "ACE": return 1;
            case "T": case "10": case "J": case "Q": case "K": return 10;
            default:
                int r = Integer.parseInt(s);
                if (r < 2 || r > 9) {
                    throw new IllegalArgumentException("not a rank: " + printed);
                }
                return r;
        }
    }

    // ------------------------------------------------------------------ Wizard of Odds, appendix 9

    /**
     * Appendix 9's two tables for one rule set (LITERATURE.md 2B): the value of standing, hitting,
     * doubling and (for pairs) splitting with double after split, for every two-card hand against
     * every up-card, conditional on no dealer blackjack. Laid out as StateValuer lays out a shoe's
     * values, value[deal * 5 + move], NaN where the table has no value (a natural, a split of a
     * non-pair, surrender). printed keeps each cell as the page prints it.
     */
    static final class Appendix9 {
        final double[] value = new double[Deals.COUNT * StateValuer.MOVES.length];
        final String[] printed = new String[Deals.COUNT * StateValuer.MOVES.length];

        Appendix9() {
            java.util.Arrays.fill(value, Double.NaN);
        }

        double at(int p1, int p2, int up, PlayerMove m) {
            return value[StateValuer.slot(Deals.index(p1, p2, up), m)];
        }
    }

    /**
     * Reads appendix 9 from its page. Every row under a header that starts "Dealer | Player" is a
     * hand: the dealer's up-card, the player's two cards ("9,A"), then a cell per column the header
     * names. Columns read: Stand, Hit, Double, and "Split (DAS=Yes)" (this game allows a double after
     * a split). Refuses a page whose tables do not hold exactly one value for each move of each of
     * the 540 hands that are not naturals, and one split value for each of the 100 pairs.
     */
    static Appendix9 appendix9(String html) {
        Appendix9 a = new Appendix9();
        Map<String, Integer> header = null;
        for (List<String> r : rows(html)) {
            if (r.size() >= 2 && r.get(0).equals("Dealer") && r.get(1).equals("Player")) {
                header = new LinkedHashMap<>();
                for (int k = 0; k < r.size(); k++) {
                    header.put(r.get(k), k);
                }
                continue;
            }
            if (header == null || r.size() != header.size()) {
                // A row of another shape ends the table: the dealer-blackjack table below has three cells.
                header = null;
                continue;
            }
            int up = rank(r.get(0));
            String[] hand = r.get(1).split(",");
            if (hand.length != 2) {
                throw new IllegalArgumentException("appendix 9: a hand of " + r.get(1));
            }
            int p1 = rank(hand[0]);
            int p2 = rank(hand[1]);
            int deal = Deals.index(p1, p2, up);
            if (Deals.playerNatural(deal)) {
                continue;
            }
            put(a, deal, PlayerMove.Stand, r, header, "Stand");
            put(a, deal, PlayerMove.Hit, r, header, "Hit");
            put(a, deal, PlayerMove.Double, r, header, "Double");
            if (p1 == p2) {
                put(a, deal, PlayerMove.Split, r, header, "Split (DAS=Yes)");
            }
        }
        for (int deal = 0; deal < Deals.COUNT; deal++) {
            if (Deals.playerNatural(deal)) {
                continue;
            }
            for (PlayerMove m : new PlayerMove[]{PlayerMove.Stand, PlayerMove.Hit, PlayerMove.Double, PlayerMove.Split}) {
                boolean wanted = m != PlayerMove.Split || Deals.P1[deal] == Deals.P2[deal];
                if (wanted && a.printed[StateValuer.slot(deal, m)] == null) {
                    throw new IllegalArgumentException("appendix 9 has no " + m + " value for " + Deals.P1[deal] + ","
                            + Deals.P2[deal] + " against " + Deals.UP[deal]);
                }
            }
        }
        return a;
    }

    private static void put(Appendix9 a, int deal, PlayerMove m, List<String> r, Map<String, Integer> header,
                            String column) {
        Integer k = header.get(column);
        if (k == null) {
            throw new IllegalArgumentException("appendix 9: no column " + column + " in " + header.keySet());
        }
        int slot = StateValuer.slot(deal, m);
        if (a.printed[slot] != null) {
            throw new IllegalArgumentException("appendix 9 gives " + m + " for " + Deals.P1[deal] + "," + Deals.P2[deal]
                    + " against " + Deals.UP[deal] + " twice");
        }
        a.printed[slot] = r.get(k);
        a.value[slot] = number(r.get(k));
    }

    // ------------------------------------------------------------------ Wizard of Odds, house edge calculator

    /**
     * The house edge calculator's figure for one set of rules (LITERATURE.md 2A, 1(c)), read from
     * the page's own JavaScript rather than its display: the calculator is interactive, so its
     * shown figure exists only after a click, and the code is what a click runs.
     *
     * The code looks up edgeTable[a][b][c][d][e][f][g][h][j], one index per form question, where each
     * index is the position of the checked radio button; adds a payout adjustment only when 6 to 5
     * is checked; adds a per-deck amount for "basic strategy with cut card"; subtracts a per-deck
     * amount from that for "basic strategy with continuous shuffler"; and shows each rounded to five
     * decimals. Which index means which rule is derived here from the form's own labels, so a
     * reordered form changes the lookup instead of silently reading another rule set.
     */
    static final class Calculator {
        /** Form question (SELECT1...) to the position of the answer asked for. */
        final Map<String, Integer> answers = new LinkedHashMap<>();
        /** The edgeTable index of each form question, in lookup order. */
        final List<String> order = new ArrayList<>();
        /** The table entry: composition-dependent play, the shoe shuffled after every hand. */
        double optimal;
        /** How many times the code assigns that entry; all assignments must agree. */
        int assignments;
        double cutCardAdd;
        double shufflerSubtract;

        double cutCard() {
            return optimal + cutCardAdd;
        }

        double shuffler() {
            return cutCard() - shufflerSubtract;
        }

        /** As the page shows a figure: Math.round(100000 * x) / 100000. */
        static double shown(double x) {
            return Math.round(100000 * x) / 100000.0;
        }
    }

    private static final Pattern RADIO = Pattern.compile(
            "(?is)<input\\b[^>]*\\bname=\"(SELECT\\d+)\"[^>]*\\bvalue=\"(\\d+)\"[^>]*>([^<]*)");

    /**
     * Reads the calculator for the answers given, each a form question's label text as the page
     * prints it beside its radio button (for example SELECT1 "8", SELECT5 "4 hands"). Refuses an
     * answer the form does not offer, a question the lookup does not use, an entry assigned twice
     * with two values, and a page whose code no longer has the shape described above.
     */
    static Calculator calculator(String html, Map<String, String> want) {
        Calculator c = new Calculator();
        Map<String, Map<String, Integer>> offered = new LinkedHashMap<>();
        Matcher m = RADIO.matcher(html);
        while (m.find()) {
            offered.computeIfAbsent(m.group(1), k -> new LinkedHashMap<>()).put(squeeze(entities(m.group(3))),
                    Integer.parseInt(m.group(2)));
        }
        for (Map.Entry<String, String> e : want.entrySet()) {
            Map<String, Integer> options = offered.get(e.getKey());
            if (options == null || !options.containsKey(e.getValue())) {
                throw new IllegalArgumentException("the calculator offers no answer \"" + e.getValue() + "\" to "
                        + e.getKey() + "; it offers " + (options == null ? "nothing" : options.keySet()));
            }
            c.answers.put(e.getKey(), options.get(e.getValue()));
        }
        // Which variable holds which question: for (i=0; i < 6; i++) if (document.selectForm.SELECT1[i].checked) a = i;
        Map<String, String> variable = new LinkedHashMap<>();
        Matcher v = Pattern.compile("if \\(document\\.selectForm\\.(SELECT\\d+)\\[i\\]\\.checked\\) (\\w+) = i;")
                .matcher(html);
        while (v.find()) {
            variable.put(v.group(2), v.group(1));
        }
        Matcher look = Pattern.compile("var edgeValue = edgeTable((?:\\[\\w+\\])+);").matcher(html);
        if (!look.find()) {
            throw new IllegalArgumentException("the calculator's code has no edgeTable lookup");
        }
        Matcher idx = Pattern.compile("\\[(\\w+)\\]").matcher(look.group(1));
        StringBuilder key = new StringBuilder();
        while (idx.find()) {
            String q = variable.get(idx.group(1));
            if (q == null || !c.answers.containsKey(q)) {
                throw new IllegalArgumentException("the lookup uses " + idx.group(1) + " (" + q + "), which no answer gives");
            }
            c.order.add(q);
            key.append("\\[").append(c.answers.get(q)).append("\\]");
        }
        if (c.order.size() != c.answers.size() - (c.answers.containsKey("SELECT10") ? 1 : 0)) {
            throw new IllegalArgumentException("the lookup uses " + c.order + ", not every question answered but the payout");
        }
        Matcher entry = Pattern.compile("edgeTable" + key + "=([0-9.]+);").matcher(html);
        Double value = null;
        while (entry.find()) {
            double x = Double.parseDouble(entry.group(1));
            if (value != null && value != x) {
                throw new IllegalArgumentException("the calculator assigns its entry twice, " + value + " and " + x);
            }
            value = x;
            c.assignments++;
        }
        if (value == null) {
            throw new IllegalArgumentException("the calculator has no entry for these answers");
        }
        c.optimal = value;
        if (c.answers.containsKey("SELECT10") && c.answers.get("SELECT10") != 0
                || !html.contains("if (document.selectForm.SELECT10[1].checked)")) {
            throw new IllegalArgumentException("only a 3 to 2 payout, with the 6 to 5 adjustment where the code puts it, is read");
        }
        String decks = variable.entrySet().stream().filter(e -> e.getValue().equals("SELECT1")).map(Map.Entry::getKey)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("no variable holds the number of decks"));
        int d = c.answers.get("SELECT1");
        c.cutCardAdd = adjustment(html, decks, d, "edgeValueBS \\+=");
        c.shufflerSubtract = adjustment(html, decks, d, "edgeValueCS -=");
        int bs = html.indexOf("edgeValueBS = edgeValue;");
        int cs = html.indexOf("edgeValueCS = edgeValueBS;", bs);
        int csAdjust = html.indexOf("edgeValueCS -=", cs);
        if (bs < 0 || cs < 0 || csAdjust < 0) {
            throw new IllegalArgumentException("the calculator no longer starts the shuffler's figure from the cut card's");
        }
        return c;
    }

    private static double adjustment(String html, String variable, int value, String statement) {
        Matcher a = Pattern.compile("if \\(" + variable + " == " + value + "\\) \\{ " + statement + " ([0-9.]+); \\}")
                .matcher(html);
        if (!a.find()) {
            throw new IllegalArgumentException("no \"" + statement + "\" for " + variable + " == " + value);
        }
        double x = Double.parseDouble(a.group(1));
        if (a.find()) {
            throw new IllegalArgumentException("two \"" + statement + "\" for " + variable + " == " + value);
        }
        return x;
    }

    // ------------------------------------------------------------------ Wizard of Odds, effect of removal

    /**
     * The effect of removing one card of each rank (LITERATURE.md 2C), from the table whose header
     * starts "Card", in the column named (for example "Hit Soft 17"), in per cent as printed, indexed
     * by rank 1 to 10. Refuses a table without exactly one row for each rank.
     */
    static double[] effectOfRemoval(String html, String column) {
        double[] out = new double[11];
        boolean[] seen = new boolean[11];
        int k = -1;
        for (List<String> r : rows(html)) {
            if (!r.isEmpty() && r.get(0).equals("Card")) {
                k = r.indexOf(column);
                if (k < 0) {
                    throw new IllegalArgumentException("effect of removal: no column " + column + " in " + r);
                }
                continue;
            }
            if (k < 0 || r.size() <= k) {
                continue;
            }
            int rank = rank(r.get(0));
            if (seen[rank]) {
                throw new IllegalArgumentException("effect of removal: rank " + rank + " twice");
            }
            seen[rank] = true;
            out[rank] = number(r.get(k));
        }
        for (int r = 1; r <= 10; r++) {
            if (!seen[r]) {
                throw new IllegalArgumentException("effect of removal: no row for rank " + r);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ QFIT's counting-systems table

    /** One row of QFIT's table: the strategy's name, its tags ace to ten as printed, and BC, PE and IC as printed. */
    static final class QfitRow {
        final String name;
        final String[] tags;
        final String bc;
        final String pe;
        final String ic;

        QfitRow(String name, String[] tags, String bc, String pe, String ic) {
            this.name = name;
            this.tags = tags;
            this.bc = bc;
            this.pe = pe;
            this.ic = ic;
        }

        /**
         * A tag as a number: "0/1" (a card counted only in one colour, as QFIT prints Red Seven's 7)
         * is its average over the two colours, 0.5.
         */
        double tag(int rank) {
            String t = tags[rank - 1];
            if (t.matches("-?\\d+/-?\\d+")) {
                String[] p = t.split("/");
                return (Double.parseDouble(p[0]) + Double.parseDouble(p[1])) / 2;
            }
            return number(t);
        }
    }

    /**
     * The table under the header "Strategy | A | 2 | ... | 9 | T | BC | PE | IC | Ease | Type", one
     * row per strategy until a row of another shape. The page prints the table twice (the second
     * time without tags); only the first is read. Refuses a page without it, or with a name twice.
     */
    static List<QfitRow> qfitTable(String html) {
        List<String> header = List.of("Strategy", "A", "2", "3", "4", "5", "6", "7", "8", "9", "T", "BC", "PE", "IC",
                "Ease", "Type");
        List<QfitRow> out = new ArrayList<>();
        boolean in = false;
        for (List<String> r : rows(html)) {
            if (r.equals(header)) {
                if (!out.isEmpty()) {
                    break;
                }
                in = true;
                continue;
            }
            if (!in) {
                continue;
            }
            if (r.size() != header.size()) {
                break;
            }
            for (QfitRow q : out) {
                if (q.name.equals(r.get(0))) {
                    throw new IllegalArgumentException("QFIT's table lists " + q.name + " twice");
                }
            }
            out.add(new QfitRow(r.get(0), r.subList(1, 11).toArray(new String[0]), r.get(11), r.get(12), r.get(13)));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("QFIT's counting table is not on this page");
        }
        return out;
    }

    static QfitRow qfit(List<QfitRow> table, String name) {
        for (QfitRow q : table) {
            if (q.name.equals(name)) {
                return q;
            }
        }
        throw new IllegalArgumentException("QFIT's table has no row " + name);
    }
}
