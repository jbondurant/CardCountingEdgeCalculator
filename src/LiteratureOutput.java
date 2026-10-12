import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes what LiteratureCheck found: the report for a reader (markdown) and the page's
 * literature file, in the site generator's input form (SiteData: _source, figures, tables).
 *
 * Every number in both comes from the Result, which LiteratureCheck worked out from committed
 * code and outputs and from claims.json; nothing here computes a figure. The page sentence of
 * PLAN_PAGE.md section 1, item 9 ("Of the n published figures I compared with mine, a agree, ...")
 * is printed here from the verdict counts so a reader of the report sees the same sentence the
 * page will; on the page SiteBuild writes it again from the counts in the file. Each source's
 * status is worded by LiteratureClaims.statusWords from checked_by and read_by_justin, so neither
 * output can present an AI agent's reading as Justin's.
 */
final class LiteratureOutput {

    private LiteratureOutput() {
    }

    /** The verdict kinds the page's sentence counts, with the words it uses for each. */
    static final Map<LiteratureClaims.Verdict, String> SENTENCE = new LinkedHashMap<>();

    static {
        SENTENCE.put(LiteratureClaims.Verdict.AGREES, "agree");
        SENTENCE.put(LiteratureClaims.Verdict.DIRECTION, "agree in direction only");
        SENTENCE.put(LiteratureClaims.Verdict.TESTED, "differ for a reason tested by code here");
        SENTENCE.put(LiteratureClaims.Verdict.UNTESTED, "differ for a reason not yet tested");
        SENTENCE.put(LiteratureClaims.Verdict.UNEXPLAINED, "differ for no reason found");
    }

    /**
     * The page's sentence from the counts: the compared total, then each kind with a count above
     * zero, in order; a kind whose count is zero is dropped (PLAN_PAGE.md section 1, item 9).
     */
    static String sentence(Map<LiteratureClaims.Verdict, Integer> n) {
        int compared = 0;
        for (LiteratureClaims.Verdict v : SENTENCE.keySet()) {
            compared += n.get(v);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<LiteratureClaims.Verdict, String> e : SENTENCE.entrySet()) {
            int k = n.get(e.getKey());
            if (k > 0) {
                // One figure agrees, differs; several agree, differ.
                parts.add(k + " " + (k == 1 ? e.getValue().replaceFirst("^(agree|differ)", "$1s") : e.getValue()));
            }
        }
        StringBuilder b = new StringBuilder("Of the " + compared + " published figure" + (compared == 1 ? "" : "s")
                + " I compared with mine, ");
        for (int k = 0; k < parts.size(); k++) {
            b.append(k == 0 ? "" : k == parts.size() - 1 ? ", and " : ", ").append(parts.get(k));
        }
        return b.append(".").toString();
    }

    static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    static String conditions(LiteratureClaims.Claim c) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : c.conditions.entrySet()) {
            if (!e.getKey().equals("unit")) {
                b.append(b.length() == 0 ? "" : "; ").append(e.getKey().replace('_', ' ')).append(" ").append(e.getValue());
            }
        }
        return b.toString();
    }

    /** The report: the sentence, the verdict counts, every claim, the sources and who read them, each part's findings, the cache. */
    static void report(LiteratureCheck.Result r, String commit, PrintStream out) {
        LiteratureClaims l = r.register;
        out.println("# The published literature beside this study's results");
        out.println();
        out.printf("LiteratureCheck %s, from literature/claims.json (%d sources, %d published figures) and the cached copies "
                + "of the sources. Every reading of a source recorded here was done by an AI agent (Claude Code), not by "
                + "Justin; %s. Plan: LITERATURE.md.%n%n", commit, l.sources.size(), l.claims.size(), justin(l));
        Map<LiteratureClaims.Verdict, Integer> n = LiteratureClaims.counts(new ArrayList<>(r.outcomes.values()));
        out.println("## Verdicts");
        out.println();
        out.println("> " + sentence(n));
        out.println();
        out.println("| Verdict | Claims |");
        out.println("|---|---|");
        for (LiteratureClaims.Verdict v : LiteratureClaims.Verdict.values()) {
            out.printf("| %s | %d |%n", v.words, n.get(v));
        }
        out.println();
        out.println("A difference counts as explained only where committed code tested the explanation; a likely reason "
                + "no code has tested counts as untested. \"Agrees in direction\" means the published game or measure "
                + "differs from this one, or the published statement is only a direction.");
        out.println();
        out.println("## Every published figure");
        out.println();
        out.println("| Claim | Source | Their game | Theirs | Ours | Verdict | Why |");
        out.println("|---|---|---|---|---|---|---|");
        for (LiteratureClaims.Claim c : l.claims) {
            LiteratureClaims.Source s = l.source(c.source);
            LiteratureClaims.Ours o = c.ours == null ? null : r.ours.get(c.ours);
            LiteratureClaims.Outcome v = r.outcomes.get(c.id);
            String ours = o == null ? "" : o.pending != null ? "waits for " + o.pending
                    : (o.printed == null ? "" : o.printed) + ("search".equals(o.file) ? " (search file)" : "");
            out.printf("| %s (%s): %s | %s (%s) | %s; unit: %s | %s | %s | %s | %s |%n", c.id, c.topic, cell(c.what),
                    cell(s.title), cell(LiteratureClaims.statusWords(s, l)), cell(conditions(c)), cell(c.conditions.get("unit")),
                    cell(c.printed == null ? "" : c.printed), cell(ours), v.verdict.words, cell(v.why));
        }
        out.println();
        sources(r, out);
        for (String part : r.detail.keySet()) {
            out.println("## (" + part + ")");
            out.println();
            for (String line : r.detail.get(part)) {
                out.println("- " + line);
            }
            out.println();
        }
        out.println("## Snapshots that would need a request");
        out.println();
        out.println("Every source checked or read on the web with no snapshot recorded in claims.json. Some may already have "
                + "one: looking one up is a read, like fetching the page, and comes first. Asking the Archive to make a new "
                + "one (Save Page Now) sends a request to a third party, so this list goes on the push list (PLAN_PAGE.md "
                + "section 5, SiteManifest --push-list) and waits for Justin's yes. None has been requested.");
        out.println();
        for (LiteratureClaims.Source s : snapshotsNeeded(l)) {
            out.printf("- %s: %s%n", s.id, s.url);
        }
        out.println();
        out.println("## The cached copies");
        out.println();
        out.println("Each source's copy, checked against the SHA-256 claims.json records. A figure read again from a page "
                + "whose other parts changed is still confirmed; a claim whose figure cannot be found again is not compared.");
        out.println();
        for (Map.Entry<String, String> e : r.cache.entrySet()) {
            out.printf("- %s: %s%n", e.getKey(), e.getValue());
        }
        out.println();
        if (r.drift.isEmpty()) {
            out.println("Every figure read again from its cached page reads as claims.json records it.");
        } else {
            out.println("Figures the cache could not confirm:");
            out.println();
            for (Map.Entry<String, String> e : r.drift.entrySet()) {
                out.printf("- %s: %s%n", e.getKey(), e.getValue());
            }
        }
        out.println();
    }

    /** The sources checked or read at a web address that have no archive snapshot: the push list's snapshot requests. */
    static List<LiteratureClaims.Source> snapshotsNeeded(LiteratureClaims l) {
        List<LiteratureClaims.Source> out = new ArrayList<>();
        for (LiteratureClaims.Source s : l.sources) {
            if (s.read() && s.url != null && s.snapshot == null) {
                out.add(s);
            }
        }
        return out;
    }

    static String justin(LiteratureClaims l) {
        List<String> his = new ArrayList<>();
        for (LiteratureClaims.Source s : l.sources) {
            if (s.readByJustin) {
                his.add(s.title);
            }
        }
        return his.isEmpty() ? "nothing records Justin reading any of these sources" : "Justin says he has read " + his;
    }

    static void sources(LiteratureCheck.Result r, PrintStream out) {
        out.println("## The sources and who read them");
        out.println();
        out.println("| Source | Status | Link | Snapshot | Same author as |");
        out.println("|---|---|---|---|---|");
        for (LiteratureClaims.Source s : r.register.sources) {
            out.printf("| %s%s%s | %s | %s | %s | %s |%n", cell(s.author == null ? "" : s.author + ", "), cell(s.title),
                    s.year == null ? "" : " (" + s.year + ")", cell(LiteratureClaims.statusWords(s, r.register)),
                    s.url == null ? "not recorded" : s.url, s.snapshot == null ? "no snapshot yet" : s.snapshot,
                    cell(s.authorGroup));
        }
        out.println();
    }

    // ------------------------------------------------------------------ the page's file

    /**
     * The page's literature file, in SiteData's input form: _source (what, the commit, fixture false,
     * and each input's path and SHA-256), figures (the verdict counts, keys lit.*, printed as whole
     * numbers), and tables: every claim with its source's status worded for the page, every source,
     * and the comparison tables the charts draw. A claim whose figure comes from the search file
     * carries file "search", which the page must label.
     */
    static Map<String, Object> page(LiteratureCheck.Result r, String commit, Map<String, String> artifacts) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("what", "literature");
        source.put("commit", commit);
        source.put("fixture", false);
        source.put("file", "main");
        List<Object> arts = new ArrayList<>();
        for (Map.Entry<String, String> a : artifacts.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", a.getKey());
            m.put("sha256", a.getValue());
            arts.add(m);
        }
        source.put("artifacts", arts);
        root.put("_source", source);
        Map<LiteratureClaims.Verdict, Integer> n = LiteratureClaims.counts(new ArrayList<>(r.outcomes.values()));
        Map<String, Object> figures = new LinkedHashMap<>();
        int compared = 0;
        for (LiteratureClaims.Verdict v : SENTENCE.keySet()) {
            compared += n.get(v);
        }
        figures.put("lit.compared", count(compared, "published figures compared"));
        String[] keys = {"lit.agree", "lit.direction", "lit.tested", "lit.untested", "lit.unexplained", "lit.not_compared"};
        LiteratureClaims.Verdict[] kinds = LiteratureClaims.Verdict.values();
        for (int k = 0; k < kinds.length; k++) {
            figures.put(keys[k], count(n.get(kinds[k]), kinds[k].words));
        }
        int read = 0;
        int justin = 0;
        for (LiteratureClaims.Source s : r.register.sources) {
            read += s.read() ? 1 : 0;
            justin += s.readByJustin ? 1 : 0;
        }
        figures.put("lit.sources", count(r.register.sources.size(), "sources in the register"));
        figures.put("lit.sources_read", count(read, "sources checked or read by an AI agent"));
        figures.put("lit.read_by_justin", count(justin, "sources Justin says he has read"));
        root.put("figures", figures);
        Map<String, Object> tables = new LinkedHashMap<>();
        tables.put("literature_claims", claims(r));
        tables.put("literature_sources", sources(r.register));
        List<Object> snapshots = new ArrayList<>();
        for (LiteratureClaims.Source s : snapshotsNeeded(r.register)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id);
            m.put("url", s.url);
            snapshots.add(m);
        }
        tables.put("literature_snapshots_needed", snapshots);
        tables.putAll(r.tables);
        root.put("tables", tables);
        return root;
    }

    static Map<String, Object> count(int n, String what) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", n);
        m.put("fmt", "n");
        m.put("src", "LiteratureCheck: " + what);
        return m;
    }

    static List<Object> claims(LiteratureCheck.Result r) {
        List<Object> out = new ArrayList<>();
        for (LiteratureClaims.Claim c : r.register.claims) {
            LiteratureClaims.Source s = r.register.source(c.source);
            LiteratureClaims.Ours o = c.ours == null ? null : r.ours.get(c.ours);
            LiteratureClaims.Outcome v = r.outcomes.get(c.id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id);
            m.put("topic", c.topic);
            m.put("what", c.what);
            m.put("source", s.id);
            m.put("locator", c.locator);
            m.put("conditions", conditions(c));
            m.put("unit", c.conditions.get("unit"));
            m.put("same_game", c.sameGame);
            m.put("theirs", c.printed);
            m.put("ours", o == null || o.pending != null ? null : o.printed);
            m.put("ours_value", o == null || o.pending != null || Double.isNaN(o.value) ? null : o.value);
            m.put("ours_waits_for", o == null ? null : o.pending);
            m.put("ours_src", o == null ? null : o.src);
            m.put("file", o == null ? "main" : o.file);
            m.put("verdict", v.verdict.words);
            m.put("why", v.why);
            m.put("status", s.status);
            m.put("status_words", LiteratureClaims.statusWords(s, r.register));
            m.put("checked_by", s.checkedBy);
            m.put("read_by_justin", s.readByJustin);
            out.add(m);
        }
        return out;
    }

    static List<Object> sources(LiteratureClaims l) {
        List<Object> out = new ArrayList<>();
        for (LiteratureClaims.Source s : l.sources) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id);
            m.put("author", s.author);
            m.put("title", s.title);
            m.put("venue", s.venue);
            m.put("year", s.year);
            m.put("doi", s.doi);
            m.put("isbn", s.isbn);
            m.put("url", s.url);
            m.put("snapshot", s.snapshot);
            m.put("cache_sha256", s.cacheSha256);
            m.put("fetched", s.fetched);
            m.put("status", s.status);
            m.put("status_date", s.statusDate);
            m.put("status_words", LiteratureClaims.statusWords(s, l));
            m.put("checked_by", s.checkedBy);
            m.put("read_by_justin", s.readByJustin);
            m.put("read_by_justin_date", s.readByJustinDate);
            m.put("same_author_as", s.authorGroup);
            out.add(m);
        }
        return out;
    }

    /** JSON text for maps, lists, strings, numbers, booleans and nulls; a double in Java's round-trip form. */
    static String json(Object o) {
        StringBuilder b = new StringBuilder();
        json(o, b, "");
        return b.append('\n').toString();
    }

    @SuppressWarnings("unchecked")
    private static void json(Object o, StringBuilder b, String indent) {
        if (o == null) {
            b.append("null");
        } else if (o instanceof String) {
            b.append('"');
            for (char ch : ((String) o).toCharArray()) {
                switch (ch) {
                    case '"': b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break;
                    case '\t': b.append("\\t"); break;
                    default:
                        if (ch < 0x20 || ch > 0x7e) {
                            b.append(String.format("\\u%04x", (int) ch));
                        } else {
                            b.append(ch);
                        }
                }
            }
            b.append('"');
        } else if (o instanceof Double) {
            double d = (Double) o;
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                b.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                b.append((long) d).append(".0");
            } else {
                b.append(d);
            }
        } else if (o instanceof Number || o instanceof Boolean) {
            b.append(o);
        } else if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append("{\n");
            int k = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                b.append(indent).append("  ");
                json(e.getKey(), b, indent + "  ");
                b.append(": ");
                json(e.getValue(), b, indent + "  ");
                b.append(++k < m.size() ? ",\n" : "\n");
            }
            b.append(indent).append('}');
        } else if (o instanceof List) {
            List<Object> l = (List<Object>) o;
            if (l.isEmpty()) {
                b.append("[]");
                return;
            }
            b.append("[\n");
            for (int k = 0; k < l.size(); k++) {
                b.append(indent).append("  ");
                json(l.get(k), b, indent + "  ");
                b.append(k + 1 < l.size() ? ",\n" : "\n");
            }
            b.append(indent).append(']');
        } else {
            throw new IllegalArgumentException("cannot write " + o.getClass() + " as JSON");
        }
    }

    static void write(Path file, String text) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
