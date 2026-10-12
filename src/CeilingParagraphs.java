import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ceiling count's result paragraphs (CEILING_RESULT_PARAGRAPHS.md, committed in f7f8770 on
 * custom-count-design while step 7 ran and its report was still empty) and the rule that picks
 * one (COUNT_DESIGN.md section 5, "How a small gain is read"; PLAN_COMPUTE.md 4.1).
 *
 * The words were fixed before the outcome was known, so this class changes none of them. It reads
 * the file, refuses it unless its git blob is the one committed then (so a paragraph edited after
 * the report was read cannot reach the page), picks a paragraph per cell by the rule, and fills
 * only the bracketed figures, refusing to print a paragraph with a figure left unfilled.
 *
 * The rule, as the file states it. For the searched cell, 80% penetration on the 1-8 same
 * schedule, each headline row k the registration names for the cell (F1's own row; F2's row with
 * its betting vector, CeilingReport.row) is compared with each of its registered comparators j,
 * paired, by the ordinary 95% interval of k - j (CeilingReport.claim):
 * - A, the claim holds for k when every lower end is above zero; A is used if it holds for any row.
 * - B, the claim holds for no row, but some row's point estimate is above the best published row's.
 * - C, every row's point estimate is at or below the best published row's.
 * The flat cell's sentence is picked by the same rule with F1 flat against the plain rows.
 *
 * Three readings the file leaves open, fixed here before any main-file figure is read:
 * - "The best published row" is, for each row, the best of its own comparators (CeilingReport's
 *   Claim.best): the plain rows for F1, every published row for F2. So the rule tests each row
 *   against the family its claim is made against, as the report's claim lines do.
 * - The paragraph names one row: among the rows that qualify for it (A: those the claim holds for;
 *   B: those above their best; C: every row), the one with the highest point estimate in the cell,
 *   the first the registration names on a tie. Choice.qualifying lists them all.
 * - [LOWER] is the claim's gain, the smallest lower end over the row's comparators, in B as in A:
 *   it is the quantity the rule tests, and in B it is at or below zero.
 */
final class CeilingParagraphs {

    /** CEILING_RESULT_PARAGRAPHS.md's git blob as committed in f7f8770, before the report was read. */
    static final String BLOB = "08516b3f9b71f2c031619ae3a25f5a93a6106b78";
    /** The commit that holds it. */
    static final String COMMIT = "f7f8770f114d46ffed62fdaab8c78773286222cd";
    static final String FILE = "CEILING_RESULT_PARAGRAPHS.md";

    /** A bracketed figure in a paragraph: capitals and underscores between square brackets. */
    static final Pattern PLACEHOLDER = Pattern.compile("\\[([A-Z_]+)\\]");

    private CeilingParagraphs() {
    }

    /** The three paragraphs and the three flat sentences, by letter. */
    static final class Texts {
        final Map<Character, String> paragraphs = new LinkedHashMap<>();
        final Map<Character, String> flat = new LinkedHashMap<>();
        String blob;
    }

    /** git's blob hash of these bytes: SHA-1 of "blob LENGTH\0" and the bytes. */
    static String gitBlob(byte[] bytes) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            sha.update(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads the file's bytes as committed, refusing any other version. */
    static Texts read(java.nio.file.Path file) throws java.io.IOException {
        return parse(java.nio.file.Files.readAllBytes(file), BLOB);
    }

    /**
     * The paragraphs of these bytes, which must have the blob given: each "## A." to "## C."
     * section's quoted lines joined with spaces, and each "- **A:**" bullet of the flat section.
     */
    static Texts parse(byte[] bytes, String blob) {
        String have = gitBlob(bytes);
        if (!have.equals(blob)) {
            throw new IllegalStateException(FILE + " has blob " + have + ", not " + blob + ", the version committed "
                    + "before the report was read (" + COMMIT + "); its words are not changed after the look");
        }
        Texts t = new Texts();
        t.blob = have;
        Character in = null;
        boolean flat = false;
        StringBuilder quote = new StringBuilder();
        for (String l : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (l.startsWith("## ")) {
                close(t, in, quote);
                in = null;
                flat = l.startsWith("## The flat cell");
                Matcher m = Pattern.compile("^## ([ABC])\\. ").matcher(l);
                if (m.find()) {
                    in = m.group(1).charAt(0);
                }
                continue;
            }
            if (in != null && l.startsWith(">")) {
                quote.append(quote.length() == 0 ? "" : " ").append(l.substring(1).trim());
            }
            Matcher b = Pattern.compile("^- \\*\\*([ABC]):\\*\\* (.*)$").matcher(l);
            if (flat && b.find()) {
                t.flat.put(b.group(1).charAt(0), b.group(2).trim());
            } else if (flat && l.startsWith("  ") && !t.flat.isEmpty()) {
                char last = new ArrayList<>(t.flat.keySet()).get(t.flat.size() - 1);
                t.flat.put(last, t.flat.get(last) + " " + l.trim());
            }
        }
        close(t, in, quote);
        for (char c : new char[]{'A', 'B', 'C'}) {
            if (!t.paragraphs.containsKey(c) || !t.flat.containsKey(c)) {
                throw new IllegalStateException(FILE + " lacks paragraph or flat sentence " + c);
            }
        }
        return t;
    }

    private static void close(Texts t, Character in, StringBuilder quote) {
        if (in != null && quote.length() > 0) {
            t.paragraphs.put(in, quote.toString());
        }
        quote.setLength(0);
    }

    /** The placeholders of a text, in order, each once. */
    static List<String> placeholders(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            if (!out.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** The text with every placeholder filled; refuses one the fill lacks, naming each. */
    static String fill(String text, Map<String, String> fill) {
        List<String> missing = new ArrayList<>();
        for (String p : placeholders(text)) {
            if (fill.get(p) == null) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("no figure for " + missing + "; a paragraph is never printed with a "
                    + "bracket left in it");
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(fill.get(m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ------------------------------------------------------------------ the rule

    /** What the rule picked in one cell, and the figures for its brackets. */
    static final class Choice {
        final String cell;
        final int pen;
        char paragraph;
        /** Each headline row's family, in the registration's order, with its claim. */
        final List<String> families = new ArrayList<>();
        final List<CeilingReport.Claim> claims = new ArrayList<>();
        /** The families the claim holds for (A), and those that qualify for the paragraph picked. */
        final List<String> holdsFor = new ArrayList<>();
        final List<String> qualifying = new ArrayList<>();
        /** The row the paragraph names, by family, and its claim. */
        String family;
        CeilingReport.Claim claim;
        /** Each bracket's figure as a fraction of a unit a round; names in names. */
        final Map<String, Double> figures = new LinkedHashMap<>();
        final Map<String, String> names = new LinkedHashMap<>();

        Choice(String cell, int pen) {
            this.cell = cell;
            this.pen = pen;
        }
    }

    /**
     * The rule in a cell ("same" for the 1-8 same schedule, "flat") at penetration p, from the
     * headline rows the registration names for that cell. The searched cell is "same" at 80%.
     */
    static Choice choose(CeilingReport.Ceiling c, String cell, int p) {
        int m = CeilingReport.claimMetric(cell);
        List<SystemScorer.Result> rows = CeilingReport.claimed(c, cell);
        if (rows.isEmpty()) {
            throw new IllegalStateException("the registration names no row for the " + cell + " cell");
        }
        Choice ch = new Choice(cell, p);
        List<String> above = new ArrayList<>();
        for (SystemScorer.Result r : rows) {
            String family = c.counts.entries.get(c.custom.indexOf(r)).family;
            CeilingReport.Claim cl = CeilingReport.claim(CeilingReport.row(r), CeilingReport.comparators(c, r), p, m);
            ch.families.add(family);
            ch.claims.add(cl);
            if (cl.holds()) {
                ch.holdsFor.add(family);
            }
            if (cl.row.estimate[p][m] > cl.best.estimate[p][m]) {
                above.add(family);
            }
        }
        ch.paragraph = !ch.holdsFor.isEmpty() ? 'A' : !above.isEmpty() ? 'B' : 'C';
        ch.qualifying.addAll(ch.paragraph == 'A' ? ch.holdsFor : ch.paragraph == 'B' ? above : ch.families);
        for (int k = 0; k < ch.families.size(); k++) {
            CeilingReport.Claim cl = ch.claims.get(k);
            if (ch.qualifying.contains(ch.families.get(k))
                    && (ch.claim == null || cl.row.estimate[p][m] > ch.claim.row.estimate[p][m])) {
                ch.claim = cl;
                ch.family = ch.families.get(k);
            }
        }
        CeilingReport.Claim cl = ch.claim;
        ch.names.put("ROW", ch.family);
        ch.names.put("BEST", cl.best.name);
        ch.names.put("BEST_FLAT", cl.best.name);
        ch.figures.put("EV", cl.row.estimate[p][m]);
        ch.figures.put("BEST_EV", cl.best.estimate[p][m]);
        ch.figures.put("GAIN", cl.fromBest.estimate);
        ch.figures.put("LOWER", cl.gain);
        if (c.bounds != null && c.bounds.get(0).estimate[p] != null) {
            ch.figures.put("PERFECT", c.bounds.get(0).estimate[p][m]);
        }
        Bootstrap.Interval s = shortfall(c, p);
        if (s != null) {
            ch.figures.put("SHORTFALL", s.hi);
        }
        return ch;
    }

    /**
     * The search shortfall (COUNT_DESIGN.md section 4): F1 at 1-8 less the mean of the two half-twin
     * counts, paired resample by resample, at the 1-8 same schedule; null when the registration has
     * no F1 or not two half counts. The twin counts as big enough when its upper end is under 0.01%.
     */
    static Bootstrap.Interval shortfall(CeilingReport.Ceiling c, int p) {
        int m = SystemScorer.same(SystemScorer.SPREADS);
        SystemScorer.Entry f1 = null;
        List<SystemScorer.Entry> halves = new ArrayList<>();
        for (int k = 0; k < c.custom.size(); k++) {
            String family = c.counts.entries.get(k).family;
            if ("F1".equals(family)) {
                f1 = c.custom.get(k).entries.get(0);
            } else if (family != null && family.startsWith("F1 half ")) {
                halves.add(c.custom.get(k).entries.get(0));
            }
        }
        if (f1 == null || halves.size() != 2) {
            return null;
        }
        double[] d = new double[f1.boot[p][m].length];
        for (int r = 0; r < d.length; r++) {
            d[r] = f1.boot[p][m][r] - (halves.get(0).boot[p][m][r] + halves.get(1).boot[p][m][r]) / 2;
        }
        double est = f1.estimate[p][m] - (halves.get(0).estimate[p][m] + halves.get(1).estimate[p][m]) / 2;
        return Bootstrap.interval(est, d);
    }

    /** Brackets the template follows with its own "%": the figure is printed without one. */
    static final java.util.Set<String> BARE = new java.util.HashSet<>(java.util.Arrays.asList("EV", "BEST_EV"));

    /**
     * The paragraph or flat sentence picked, its brackets filled: figures as the report prints them
     * (ComparisonReport.pct, % of a unit, three places), names as the rows are named. more holds
     * figures from other computations by bracket (OPTIMISM and TWIN_GAIN, from the optimism scoring
     * on the twin); a bracket with no figure is refused.
     */
    static String render(Texts t, Choice ch, Map<String, Double> more) {
        Map<String, String> fill = new LinkedHashMap<>(ch.names);
        Map<String, Double> figures = new LinkedHashMap<>(ch.figures);
        if (more != null) {
            figures.putAll(more);
        }
        for (Map.Entry<String, Double> e : figures.entrySet()) {
            if (e.getValue() != null && !Double.isNaN(e.getValue())) {
                fill.put(e.getKey(), ComparisonReport.pct(e.getValue()) + (BARE.contains(e.getKey()) ? "" : "%"));
            }
        }
        String text = "flat".equals(ch.cell) ? t.flat.get(ch.paragraph) : t.paragraphs.get(ch.paragraph);
        return fill(text, fill);
    }
}
