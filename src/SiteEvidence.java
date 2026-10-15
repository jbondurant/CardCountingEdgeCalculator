import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The evidence table's input (PLAN_PAGE.md 3.5, "Evidence that the code is right"; INTEGRATION.md
 * open item 12): what was checked, the result as recorded and in which commit, what it would catch
 * and what it cannot, and whether it was re-run for the page.
 *
 *   SiteAdapt evidence verification/evidence.tsv --repo DIR [--junit TEST-junit-jupiter.xml --rerun-at SHA]
 *       --out runs/site/evidence-SHA.json
 *
 * evidence.tsv is tab-separated with a header line; a line starting with # is a comment. Columns:
 * id, check, recorded, source, quotes, catches, misses, tests.
 * <ul>
 *   <li>recorded is the result as the page prints it, and source says where it is recorded: a
 *   commit (its message) or COMMIT:PATH (a file committed at it). This is PLAN_PAGE.md's
 *   EvidenceCheck: quotes holds one or more phrases, joined by " || ", each of which must be in the
 *   source word for word (a run of white space read as one space), and every number in check,
 *   recorded, catches and misses must be in one of the quotes. A figure found shows only that the
 *   record says it: AI agents wrote the commit messages, and the page says so.</li>
 *   <li>tests names the test classes, space-separated, re-run for the page. With --junit, the
 *   JUnit XML report of the suite run at --rerun-at, each row says how its classes' tests did
 *   there, and the suite's totals are figures; without it, each row says it was not re-run.</li>
 * </ul>
 * The file must be committed as it stands, and the input's commit is
 * the last commit that touched it, so the page names the version of the table it prints.
 */
final class SiteEvidence {

    private SiteEvidence() {
    }

    static final String HEADER = "id\tcheck\trecorded\tsource\tquotes\tcatches\tmisses\ttests";
    static final String QUOTES = " || ";
    /** A number as the table prints one: 3,000, 1.3e-15, 0.031, 540. */
    static final Pattern NUMBER = Pattern.compile("\\d+(?:,\\d{3})*(?:\\.\\d+)?(?:e-?\\d+)?");

    /** A run of white space as one space, ends trimmed: a commit message wraps its lines. */
    static String squeeze(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    /** What the check reads from git, apart so a test can give it records of its own. */
    interface Records {
        /** A commit's message (ref COMMIT) or a file committed at it (COMMIT:PATH); null if there is none. */
        String text(String ref);

        /** A commit's short id and its date, YYYY-MM-DD, as git gives them; null if there is none. */
        String[] commit(String commit);
    }

    /** The records of the git repository at dir. */
    static Records git(Path dir) {
        return new Records() {
            public String text(String ref) {
                return ref.contains(":") ? CustomCounts.git(dir, "show", ref)
                        : CustomCounts.git(dir, "show", "-s", "--format=%B", ref + "^{commit}");
            }

            public String[] commit(String c) {
                String s = CustomCounts.git(dir, "show", "-s", "--format=%h %ad", "--date=short", c + "^{commit}");
                return s == null ? null : s.split(" ");
            }
        };
    }

    /** One row of evidence.tsv as read: its fields by column. */
    static List<Map<String, String>> rows(List<String> lines) {
        List<Map<String, String>> out = new ArrayList<>();
        String[] cols = HEADER.split("\t");
        boolean head = false;
        for (String l : lines) {
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            if (!head) {
                if (!l.equals(HEADER)) {
                    throw new IllegalArgumentException("evidence.tsv's header is \"" + l + "\", not \"" + HEADER + "\"");
                }
                head = true;
                continue;
            }
            String[] f = l.split("\t", -1);
            if (f.length != cols.length) {
                throw new IllegalArgumentException("evidence.tsv: a line of " + f.length + " fields, not " + cols.length + ": " + l);
            }
            Map<String, String> m = new LinkedHashMap<>();
            for (int k = 0; k < cols.length; k++) {
                m.put(cols[k], f[k].trim());
            }
            out.add(m);
        }
        if (!head) {
            throw new IllegalArgumentException("evidence.tsv has no header line");
        }
        return out;
    }

    /** Whether a number is in a quote as a whole number, not as a part of a longer one (8 is not in 38 or 0.8). */
    static boolean whole(String quote, String number) {
        return Pattern.compile("(?<![\\d.,])" + Pattern.quote(number) + "(?![\\d]|[.,]\\d|e-?\\d)").matcher(quote).find();
    }

    /**
     * EvidenceCheck: the problems with one row, none when every quote is in its source word for
     * word and every number the row prints is in a quote.
     */
    static List<String> check(Map<String, String> row, Records records) {
        List<String> p = new ArrayList<>();
        String id = row.get("id");
        String source = records.text(row.get("source"));
        if (source == null) {
            p.add(id + ": no record " + row.get("source") + " in the repository");
            return p;
        }
        String in = squeeze(source);
        List<String> quotes = new ArrayList<>();
        for (String q : row.get("quotes").split(Pattern.quote(QUOTES))) {
            String t = squeeze(q);
            if (t.isEmpty()) {
                continue;
            }
            quotes.add(t);
            if (!in.contains(t)) {
                p.add(id + ": \"" + t + "\" is not in " + row.get("source") + " word for word");
            }
        }
        if (quotes.isEmpty()) {
            p.add(id + ": no quote from " + row.get("source"));
        }
        for (String field : new String[]{"check", "recorded", "catches", "misses"}) {
            Matcher m = NUMBER.matcher(row.get(field));
            while (m.find()) {
                boolean found = false;
                for (String q : quotes) {
                    found |= whole(q, m.group());
                }
                if (!found) {
                    p.add(id + ": " + m.group() + " in its " + field + " is in none of its quotes");
                }
            }
        }
        return p;
    }

    /** One test class's tests in a JUnit XML report: how many ran, failed (or erred) and were skipped. */
    static final class Run {
        int tests;
        int failed;
        int skipped;
    }

    /** The JUnit XML report's test cases by class name, and the whole report's under the key "". */
    static Map<String, Run> junit(Path xml) throws IOException {
        Map<String, Run> out = new LinkedHashMap<>();
        out.put("", new Run());
        try {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.NodeList cases = f.newDocumentBuilder().parse(xml.toFile()).getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                org.w3c.dom.Element c = (org.w3c.dom.Element) cases.item(i);
                boolean failed = c.getElementsByTagName("failure").getLength() + c.getElementsByTagName("error").getLength() > 0;
                boolean skipped = c.getElementsByTagName("skipped").getLength() > 0;
                for (String k : new String[]{"", c.getAttribute("classname")}) {
                    Run r = out.computeIfAbsent(k, x -> new Run());
                    r.tests++;
                    r.failed += failed ? 1 : 0;
                    r.skipped += skipped && !failed ? 1 : 0;
                }
            }
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IllegalArgumentException(xml + " is not a JUnit XML report: " + e.getMessage(), e);
        }
        if (out.get("").tests == 0) {
            throw new IllegalArgumentException(xml + " holds no test case");
        }
        return out;
    }

    /** A row's re-run, as the page words it, from its classes' tests in the report; null without a report. */
    static String rerun(String tests, Map<String, Run> report, String at) {
        if (tests.isEmpty()) {
            return "not re-run: the record only";
        }
        if (report == null) {
            return "not re-run for this page";
        }
        int n = 0;
        int failed = 0;
        int skipped = 0;
        for (String t : tests.split("\\s+")) {
            Run r = report.get(t);
            if (r == null) {
                throw new IllegalArgumentException("the JUnit report holds no test of " + t + ", which evidence.tsv says was re-run");
            }
            n += r.tests;
            failed += r.failed;
            skipped += r.skipped;
        }
        String counts = (n - failed - skipped) + " of " + n + " tests passed" + (skipped > 0 ? ", " + skipped + " skipped" : "");
        return (failed == 0 ? "re-run at " : "FAILED when re-run at ") + at + ": " + counts;
    }

    /** The input: evidence.tsv's rows each checked against its record, with its commit's id and date and its re-run. */
    static SiteInput adapt(Path tsv, Records records, Path junitXml, String rerunAt) throws IOException {
        Path file = tsv.toAbsolutePath().normalize();
        String name = file.getFileName().toString();
        String status = CustomCounts.git(file.getParent(), "status", "--porcelain", "--", name);
        if (CustomCounts.git(file.getParent(), "ls-files", "--error-unmatch", "--", name) == null || status == null
                || !status.isEmpty()) {
            throw new IllegalArgumentException(tsv + " is not committed as it stands; the page prints a committed table only");
        }
        String commit = CustomCounts.git(file.getParent(), "log", "-1", "--format=%H", "--", name);
        if (commit == null || commit.isEmpty()) {
            throw new IllegalArgumentException(tsv + " has no commit");
        }
        return adapt(Files.readAllLines(tsv, StandardCharsets.UTF_8), commit, tsv, records, junitXml, rerunAt);
    }

    static SiteInput adapt(List<String> lines, String commit, Path shown, Records records, Path junitXml, String rerunAt)
            throws IOException {
        if ((junitXml == null) != (rerunAt == null)) {
            throw new IllegalArgumentException("--junit and --rerun-at go together: a report says nothing without the commit it ran at");
        }
        Map<String, Run> report = junitXml == null ? null : junit(junitXml);
        SiteInput in = new SiteInput("evidence", commit, "main");
        in.artifact(shown, shown.toString());
        List<String> problems = new ArrayList<>();
        List<Object> table = new ArrayList<>();
        int rerun = 0;
        for (Map<String, String> row : rows(lines)) {
            problems.addAll(check(row, records));
            String c = row.get("source").split(":")[0];
            String[] id = records.commit(c);
            if (id == null) {
                problems.add(row.get("id") + ": no commit " + c);
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : new String[]{"id", "check", "recorded", "catches", "misses"}) {
                m.put(k, row.get(k));
            }
            m.put("commit", id[0]);
            m.put("date", id[1]);
            m.put("where", row.get("source").contains(":") ? row.get("source").substring(row.get("source").indexOf(':') + 1)
                    : "the commit's message");
            m.put("rerun", rerun(row.get("tests"), report, rerunAt));
            rerun += row.get("tests").isEmpty() || report == null ? 0 : 1;
            table.add(m);
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("evidence.tsv: rows the records do not bear out; nothing is printed until each "
                    + "is fixed:\n  " + String.join("\n  ", problems));
        }
        in.tables.put("evidence", table);
        SiteInput.at(in.figure("evidence.rows", table.size(), "n"), null, "rows of evidence.tsv, each found in its record");
        SiteInput.at(in.figure("evidence.rerun_rows", rerun, "n"), null, "rows whose tests the JUnit report holds");
        if (report != null) {
            in.artifact(junitXml, junitXml.toString());
            Run all = report.get("");
            SiteInput.at(in.figure("evidence.suite_tests", all.tests, "n"), null, "test cases in the JUnit report");
            SiteInput.at(in.figure("evidence.suite_failed", all.failed, "n"), null, "failed or erred");
            SiteInput.at(in.figure("evidence.suite_skipped", all.skipped, "n"), null, "skipped");
            Map<String, Object> at = new LinkedHashMap<>();
            at.put("at", rerunAt);
            in.tables.put("evidence_suite", List.of(at));
        }
        return in;
    }
}
