import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CeilingResults' data file (runs/site/ceiling-SHA.json) as an input of the page generator, with
 * the result paragraphs of CEILING_RESULT_PARAGRAPHS.md.
 *
 * The ladder's registered rows and bounds, in each of its views (first decisions and full play,
 * each penetration, flat and 1-8), as SCOPE.PEN.SPREAD.ROW: each searched count by its family (f1,
 * f2), F1 bet on its own value (f1_own), the perfect first move at first decisions and the
 * full-play bound at full play (perfect). Full-play levels carry the ratio CeilingResults widened
 * them by; a bound, fitted on nothing, is marked widened by 1. The ladder's published rungs (the
 * null count, Hi-Lo, the best rows) are written at first decisions too, where nothing is widened,
 * so the generator's one-number-one-value rule holds the ceiling run's reading of c06c061's rows
 * to SiteExport's; at full play CeilingResults widens every published row by 1.07 where SiteExport
 * takes each count's own ratio, so they are not written twice there.
 *
 * The paragraphs. CeilingResults picks each cell's paragraph by the rule written before the look
 * (CeilingParagraphs.choose). This picks it again from the file's own claims, by the same rule as
 * the paragraphs file states it, and refuses the file if the two disagree, in the letter or in the
 * row named: A when the claim holds for some headline row; B when it holds for none but some
 * row's estimate is above its best comparator's; C otherwise; the row named the highest
 * qualifying one. The words are the committed file's, read only at its committed blob
 * (CeilingParagraphs.parse): the table ceiling_paragraphs holds each picked paragraph's words
 * with its brackets, and what fills each, a figure ceiling.para.CELL.BRACKET (percent, as the
 * report prints it) or a row's name. The generator prints the words as they are, each run of them
 * in a data-q span that the page check holds to the table, and the brackets as figures and names.
 * A paragraph whose bracket has no figure in the file is not printed: the table says what is
 * missing, and the page says it is not yet computed.
 *
 * With the registration (--counts), each headline row's play tags, tags.ROW.rN, for the page's
 * tag table.
 */
final class SiteCeiling {

    private SiteCeiling() {
    }

    /** The cells the paragraphs file has words for. */
    static final String[] CELLS = {"same", "flat"};

    static String spread(String view) {
        String s = view.substring(view.lastIndexOf('.') + 1);
        return s.equals("flat") ? "s1" : s;
    }

    /** A rung's row on the page, {id, name, kind}, or null for one the view does not write. */
    static String[] rung(String role, String name, boolean full) {
        switch (role) {
            case "null":
            case "hilo":
            case "best-plain":
            case "best-any": {
                if (full) {
                    return null;
                }
                String[] c = SiteInput.catalogRow(name);
                if (c == null) {
                    throw new IllegalArgumentException("no page row for the ladder's " + name);
                }
                return new String[]{c[0], c[1], c[0].equals("null") ? "reference" : "published"};
            }
            case "F1-own-value":
                return new String[]{"f1_own", "F1 bet on its own value", "bound"};
            case "bound-first":
                return full ? null : new String[]{"perfect", "Perfect knowledge", "perfect"};
            case "bound-full":
                return new String[]{"perfect", "Perfect knowledge", "perfect"};
            default:
                return new String[]{SiteAdapt.part(role), role, "searched"};
        }
    }

    static SiteInput adapt(Path file, String shown, Path paragraphs) throws IOException {
        return adapt(file, shown, paragraphs, null);
    }

    static SiteInput adapt(Path file, String shown, Path paragraphs, Path counts) throws IOException {
        Map<String, Object> d = SiteAdapt.read(file);
        if (!d.containsKey("ladder") || !d.containsKey("claims")) {
            throw new IllegalArgumentException(file + " is not CeilingResults' data file");
        }
        SiteInput in = new SiteInput("ceiling", SiteAdapt.str(d.get("commit"), "commit"), "main");
        in.artifact(file, shown);
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map.Entry<String, Object> v : SiteAdapt.obj(d.get("ladder"), "ladder").entrySet()) {
            String[] view = v.getKey().split("\\.");
            boolean full = view[0].equals("full");
            String at = view[0] + "." + SiteAdapt.pen(view[1]) + "." + spread(v.getKey());
            for (Object o : SiteAdapt.list(v.getValue(), "ladder." + v.getKey())) {
                Map<String, Object> r = SiteAdapt.obj(o, "a rung");
                String role = SiteAdapt.str(r.get("role"), "role");
                String[] row = rung(role, SiteAdapt.str(r.get("name"), "name"), full);
                if (row == null) {
                    continue;
                }
                Map<String, Object> level = SiteAdapt.obj(r.get("level"), "level");
                if (in.figures.containsKey(at + "." + row[0])) {
                    // A row that is two rungs of one view (the best plain row may be the best of any kind).
                    double was = (Double) ((Map<String, Object>) in.figures.get(at + "." + row[0])).get("value");
                    if (was != SiteInput.pct(SiteAdapt.num(level.get("v"), "v"))) {
                        throw new IllegalArgumentException("ladder." + v.getKey() + " gives " + row[0] + " two levels");
                    }
                    continue;
                }
                Map<String, Object> m = in.figure(at + "." + row[0], SiteInput.pct(SiteAdapt.num(level.get("v"), "v")),
                        "s4");
                m.put("se", SiteInput.pct(SiteAdapt.num(level.get("se"), "se")));
                m.put("ci", List.of(SiteInput.pct(SiteAdapt.num(level.get("lo"), "lo")),
                        SiteInput.pct(SiteAdapt.num(level.get("hi"), "hi"))));
                SiteInput.widened(m, full ? SiteAdapt.num(r.get("ratio"), "ratio") : 1);
                SiteInput.at(m, null, "ladder." + v.getKey() + ", " + role + ", " + r.get("name"));
                if (!row[2].equals("published") && !row[2].equals("reference") && !rows.containsKey(row[0])) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("id", row[0]);
                    t.put("kind", row[2]);
                    t.put("level", "real tags");
                    t.put("balanced", "n/a");
                    t.put("side", "none");
                    t.put("side_rule", "none");
                    t.put("entry", r.get("name"));
                    rows.put(row[0], t);
                    in.names.put(row[0], row[1]);
                }
            }
        }
        in.tables.put("rows", new ArrayList<Object>(rows.values()));
        if (d.get("paragraphs") != null) {
            paragraphs(in, d, CeilingParagraphs.parse(Files.readAllBytes(paragraphs), CeilingParagraphs.BLOB));
        }
        if (counts != null) {
            tags(in, counts);
            in.artifact(counts, counts.toString());
        }
        return in;
    }

    /** A cell's claims at 80% first decisions, by family in the registration's order: the rule's inputs. */
    static Map<String, Map<String, Object>> claims(Map<String, Object> d, String cell) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        String prefix = "fd.80." + cell + ".";
        for (Map.Entry<String, Object> e : SiteAdapt.obj(d.get("claims"), "claims").entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                out.put(e.getKey().substring(prefix.length()), SiteAdapt.obj(e.getValue(), e.getKey()));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("the file holds no claim for the " + cell + " cell at 80%");
        }
        return out;
    }

    /**
     * The rule of CEILING_RESULT_PARAGRAPHS.md on a cell's claims: {letter, the family named}. A if
     * the claim holds for any row; else B if any row's estimate is above its best comparator's;
     * else C. The row named is the qualifying row with the highest estimate, the first on a tie.
     */
    static String[] pick(Map<String, Map<String, Object>> claims) {
        List<String> holds = new ArrayList<>();
        List<String> above = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> c : claims.entrySet()) {
            Object h = c.getValue().get("holds");
            if (!(h instanceof Boolean)) {
                throw new IllegalArgumentException("the claim of " + c.getKey() + " does not say whether it holds");
            }
            if ((Boolean) h) {
                holds.add(c.getKey());
            }
            if (SiteAdapt.num(c.getValue().get("ev"), "ev") > SiteAdapt.num(c.getValue().get("bestEv"), "bestEv")) {
                above.add(c.getKey());
            }
        }
        String letter = !holds.isEmpty() ? "A" : !above.isEmpty() ? "B" : "C";
        List<String> qualifying = letter.equals("A") ? holds : letter.equals("B") ? above : new ArrayList<>(claims.keySet());
        String named = null;
        for (String f : qualifying) {
            if (named == null || SiteAdapt.num(claims.get(f).get("ev"), "ev") > SiteAdapt.num(claims.get(named).get("ev"), "ev")) {
                named = f;
            }
        }
        return new String[]{letter, named};
    }

    static void paragraphs(SiteInput in, Map<String, Object> d, CeilingParagraphs.Texts texts) {
        Map<String, Object> ps = SiteAdapt.obj(d.get("paragraphs"), "paragraphs");
        if (!CeilingParagraphs.BLOB.equals(ps.get("blob"))) {
            throw new IllegalArgumentException("the file's paragraphs come from blob " + ps.get("blob") + ", not the one "
                    + "committed before the look, " + CeilingParagraphs.BLOB);
        }
        Map<String, Object> table = new LinkedHashMap<>();
        for (String cell : CELLS) {
            Map<String, Object> p = SiteAdapt.obj(ps.get(cell), "paragraphs." + cell);
            Map<String, Map<String, Object>> claims = claims(d, cell);
            String[] pick = pick(claims);
            if (!pick[0].equals(p.get("letter")) || !pick[1].equals(p.get("row"))) {
                throw new IllegalStateException("the " + cell + " cell's claims pick paragraph " + pick[0] + " naming "
                        + pick[1] + ", but the file says " + p.get("letter") + " naming " + p.get("row"));
            }
            char letter = pick[0].charAt(0);
            String words = cell.equals("flat") ? texts.flat.get(letter) : texts.paragraphs.get(letter);
            Map<String, Object> figures = SiteAdapt.obj(p.get("figures"), "paragraphs." + cell + ".figures");
            Map<String, Object> fill = new LinkedHashMap<>();
            List<Object> percent = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String b : CeilingParagraphs.placeholders(words)) {
                if (b.equals("ROW")) {
                    fill.put(b, "name:" + SiteAdapt.part(pick[1]));
                } else if (b.equals("BEST") || b.equals("BEST_FLAT")) {
                    String best = SiteAdapt.str(claims.get(pick[1]).get("best"), "best");
                    String[] id = SiteInput.catalogRow(best);
                    if (id == null) {
                        throw new IllegalArgumentException("no page row for the best comparator " + best);
                    }
                    fill.put(b, "name:" + id[0]);
                } else {
                    double v = SiteAdapt.num(figures.get(b), b);
                    if (Double.isNaN(v)) {
                        missing.add(b);
                        continue;
                    }
                    String key = "ceiling.para." + cell + "." + b.toLowerCase(java.util.Locale.ROOT);
                    Map<String, Object> m = in.figure(key, SiteInput.pct(v), "s3");
                    SiteInput.at(m, null, "paragraphs." + cell + ".figures." + b);
                    fill.put(b, key);
                    if (!CeilingParagraphs.BARE.contains(b)) {
                        percent.add(b);
                    }
                }
            }
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("letter", pick[0]);
            t.put("row", pick[1]);
            t.put("words", words);
            t.put("fill", fill);
            t.put("percent_after", percent);
            if (!missing.isEmpty()) {
                t.put("pending", "the figures " + String.join(", ", missing) + " of paragraph " + pick[0]);
            }
            table.put(cell, t);
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("blob", CeilingParagraphs.BLOB);
        source.put("commit", CeilingParagraphs.COMMIT);
        source.put("file", CeilingParagraphs.FILE);
        table.put("_words_from", source);
        in.tables.put("ceiling_paragraphs", table);
    }

    /** Each headline row's play tags by rank, A then 2 to 9 then T, from the registration. */
    static void tags(SiteInput in, Path counts) throws IOException {
        CustomCounts.Listing l = CustomCounts.read(counts);
        for (CustomCounts.Entry e : l.entries) {
            if (e.family == null || !"headline".equals(e.role)) {
                continue;
            }
            String id = SiteAdapt.part(e.family);
            if (!in.names.containsKey(id)) {
                continue;
            }
            for (int r = 1; r <= 10; r++) {
                double t = Double.NaN;
                for (int k = 0; k < ShoeRun.KINDS; k++) {
                    if (ShoeRun.rankOf(k) == r) {
                        double v = (double) e.count.tag[k] / e.count.scale;
                        if (!Double.isNaN(t) && t != v) {
                            throw new IllegalArgumentException(e.family + " tags one rank two ways");
                        }
                        t = v;
                    }
                }
                Map<String, Object> m = in.figure("tags." + id + ".r" + r, t, "s2");
                SiteInput.at(m, counts.toString(), e.family + "'s tag of rank " + SiteExport.RANKS[r - 1]);
            }
        }
    }
}
