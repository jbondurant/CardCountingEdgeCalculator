import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SiteEvidence, the evidence table's input (PLAN_PAGE.md 3.5): a row is printed only when its
 * record bears it out, every quote word for word in the commit it cites and every number it prints
 * in a quote, whole; its re-run comes from the suite's JUnit report; the table committed in
 * verification/evidence.tsv is borne out by this repository's own history; and an uncommitted copy
 * is refused. Records are given here as git would give them, apart from git, except in the test of
 * the committed table, which reads this repository's history and writes nothing.
 */
public class SiteEvidenceTest {

    @TempDir
    Path dir;

    static final String COMMIT = "abcdef0123456789abcdef0123456789abcdef01";

    /** A commit message wrapped as git keeps it, its lines broken mid-phrase. */
    static final String MESSAGE = "check the exact round against the brute-force one\n\nThere are three sets of cases. "
            + "3,000 on shoes of 14 to 24\ncards give 13,396 rounds: 11,420 valued by both, with a worst gap of 1.3e-15,\n"
            + "and 38 left out as too big.";

    static SiteEvidence.Records records(Map<String, String> texts) {
        return new SiteEvidence.Records() {
            public String text(String ref) {
                return texts.get(ref);
            }

            public String[] commit(String c) {
                return texts.containsKey(c) ? new String[]{c.substring(0, 7), "2026-09-28"} : null;
            }
        };
    }

    static final SiteEvidence.Records RECORDS = records(Map.of(COMMIT, MESSAGE, COMMIT + ":NOTES.md",
            "The sub-runs: 0.031% against 0.045% for Red Seven."));

    static List<String> lines(String... rows) {
        List<String> out = new ArrayList<>();
        out.add("# a comment line");
        out.add(SiteEvidence.HEADER);
        for (String r : rows) {
            out.add(r);
        }
        return out;
    }

    static String row(String id, String recorded, String source, String quotes, String tests) {
        return String.join("\t", id, "ExactRound against BruteForceRound", recorded, source, quotes, "engine logic",
                "a rule both misread", tests);
    }

    static final String GOOD = row("exact", "3,000 cases on 14 to 24 cards: 11,420 rounds valued by both, worst gap 1.3e-15",
            COMMIT, "3,000 on shoes of 14 to 24 cards || 11,420 valued by both, with a worst gap of 1.3e-15", "");

    static SiteInput adapt(List<String> lines) throws Exception {
        return SiteEvidence.adapt(lines, COMMIT, Paths.get("verification/evidence.tsv"), RECORDS, null, null);
    }

    static String refusal(List<String> lines) {
        return assertThrows(IllegalArgumentException.class, () -> adapt(lines)).getMessage();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aRowIsPrintedOnlyWhenItsRecordBearsItOut() throws Exception {
        SiteInput in = adapt(lines(GOOD, row("file", "under the spread, 0.031% against 0.045%", COMMIT + ":NOTES.md",
                "0.031% against 0.045%", "")));
        List<Object> t = (List<Object>) in.tables.get("evidence");
        assertEquals(2, t.size());
        Map<String, Object> r = (Map<String, Object>) t.get(0);
        assertEquals("abcdef0", r.get("commit"));
        assertEquals("2026-09-28", r.get("date"));
        assertEquals("the commit's message", r.get("where"), "a quote read across the message's line breaks");
        assertEquals("not re-run: the record only", r.get("rerun"));
        assertEquals("NOTES.md", ((Map<String, Object>) t.get(1)).get("where"));
        new SiteData().add("evidence.json", in.text("evidence.json").getBytes(StandardCharsets.UTF_8));

        assertTrue(refusal(lines(row("x", "3,001 cases", COMMIT, "3,001 on shoes", ""))).contains(
                "x: \"3,001 on shoes\" is not in " + COMMIT + " word for word"));
        assertTrue(refusal(lines(row("x", "13,396 rounds, 11,420 valued", COMMIT, "11,420 valued by both", ""))).contains(
                "x: 13,396 in its recorded is in none of its quotes"));
        // A number is found only whole: 8 is not in "38", nor 1.3 in "1.3e-15".
        assertTrue(refusal(lines(row("x", "8 left out", COMMIT, "and 38 left out as too big", ""))).contains(
                "x: 8 in its recorded is in none of its quotes"));
        assertTrue(refusal(lines(row("x", "a gap of 1.3", COMMIT, "a worst gap of 1.3e-15", ""))).contains("1.3 in its recorded"));
        assertTrue(refusal(lines(row("x", "none", "0000000", "anything", ""))).contains("x: no record 0000000"));
        assertTrue(refusal(lines(row("x", "none", COMMIT, "", ""))).contains("x: no quote from"));
        assertThrows(IllegalArgumentException.class, () -> adapt(List.of("id\tcheck", GOOD)), "another header");
    }

    /** A JUnit XML report as the console launcher's --reports-dir writes one, its cases by class. */
    static Path report(Path dir) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"JUnit Jupiter\" tests=\"5\">\n"
                + "<testcase name=\"a()\" classname=\"ExactVsBruteForceTest\" time=\"1\"/>\n"
                + "<testcase name=\"b()\" classname=\"ExactVsBruteForceTest\" time=\"1\"/>\n"
                + "<testcase name=\"c()\" classname=\"ExactVsBruteForceTest\" time=\"1\"/>\n"
                + "<testcase name=\"d()\" classname=\"BasicStrategyTest\" time=\"1\"><failure message=\"x\"/></testcase>\n"
                + "<testcase name=\"e()\" classname=\"OtherTest\" time=\"0\"><skipped/></testcase>\n</testsuite>\n";
        return Files.write(dir.resolve("TEST-junit-jupiter.xml"), xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void theReRunComesFromTheSuitesJUnitReport() throws Exception {
        Path xml = report(dir);
        List<String> l = lines(row("a", "3,000 cases", COMMIT, "3,000 on shoes", "ExactVsBruteForceTest"),
                row("b", "3,000 cases", COMMIT, "3,000 on shoes", "BasicStrategyTest"), row("c", "3,000 cases", COMMIT,
                        "3,000 on shoes", ""));
        Path e = Files.write(dir.resolve("e.tsv"), String.join("\n", l).getBytes(StandardCharsets.UTF_8));
        SiteInput in = SiteEvidence.adapt(l, COMMIT, e, RECORDS, xml, "1234abc");
        List<Object> t = (List<Object>) in.tables.get("evidence");
        assertEquals("re-run at 1234abc: 3 of 3 tests passed", ((Map<String, Object>) t.get(0)).get("rerun"));
        assertEquals("FAILED when re-run at 1234abc: 0 of 1 tests passed", ((Map<String, Object>) t.get(1)).get("rerun"));
        assertEquals("not re-run: the record only", ((Map<String, Object>) t.get(2)).get("rerun"));
        assertEquals(5.0, ((Map<String, Object>) in.figures.get("evidence.suite_tests")).get("value"));
        assertEquals(1.0, ((Map<String, Object>) in.figures.get("evidence.suite_failed")).get("value"));
        assertEquals(1.0, ((Map<String, Object>) in.figures.get("evidence.suite_skipped")).get("value"));
        assertEquals(2.0, ((Map<String, Object>) in.figures.get("evidence.rerun_rows")).get("value"));
        assertEquals("1234abc", ((Map<String, Object>) ((List<Object>) in.tables.get("evidence_suite")).get(0)).get("at"));
        // Without a report every row says so; a class the report does not hold, or a report without its commit, is refused.
        SiteInput none = SiteEvidence.adapt(l, COMMIT, e, RECORDS, null, null);
        assertEquals("not re-run for this page", ((Map<String, Object>) ((List<Object>) none.tables.get("evidence")).get(0))
                .get("rerun"));
        assertNull(none.figures.get("evidence.suite_tests"));
        assertThrows(IllegalArgumentException.class, () -> SiteEvidence.adapt(lines(row("d", "3,000 cases", COMMIT,
                "3,000 on shoes", "NoSuchTest")), COMMIT, e, RECORDS, xml, "1234abc"));
        assertThrows(IllegalArgumentException.class, () -> SiteEvidence.adapt(l, COMMIT, e, RECORDS, xml, null));
    }

    /** The table committed beside the code: every row's quotes are in the commits it cites, in this repository's history. */
    @Test
    @SuppressWarnings("unchecked")
    public void theCommittedTableIsBorneOutByThisRepositorysHistory() throws Exception {
        Path tsv = Paths.get("verification/evidence.tsv");
        SiteInput in = SiteEvidence.adapt(Files.readAllLines(tsv, StandardCharsets.UTF_8), "committed", tsv,
                SiteEvidence.git(Paths.get("").toAbsolutePath()), null, null);
        List<Object> t = (List<Object>) in.tables.get("evidence");
        assertTrue(t.size() >= 10, "rows: " + t.size());
        for (Object o : t) {
            Map<String, Object> r = (Map<String, Object>) o;
            assertTrue(((String) r.get("commit")).matches("[0-9a-f]{7,}"), r.toString());
            assertTrue(((String) r.get("date")).matches("2026-\\d\\d-\\d\\d"), r.toString());
        }
    }

    @Test
    public void aTableNotCommittedAsItStandsIsRefused() throws Exception {
        Path copy = Files.write(dir.resolve("evidence.tsv"), String.join("\n", lines(GOOD)).getBytes(StandardCharsets.UTF_8));
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class, () -> SiteEvidence.adapt(copy, RECORDS,
                null, null));
        assertTrue(x.getMessage().contains("is not committed as it stands"), x.getMessage());
    }
}
