import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The registration as committed at the root of the repository (COUNT_DESIGN.md, section 5, and
 * section 8, step 6): custom-counts.json and its full-play registration, custom-counts-fullplay.json,
 * each read by the committed loader. Before step 6 there is no registration and these are skipped.
 *
 * It checks only the files, and never the main state file or the ledger: the two files name each
 * other as the loader requires, by file and blob; their cells and comparators are as section 5
 * fixes them; the full-play F1 and F2 are the first-decision ones to the bit; and every catalog
 * count is named as the loader names it, with the whole tags at K the file records for it (an
 * unread field) equal to those the loader computes now.
 */
public class RegistrationTest {

    static final Path ROOT = Paths.get("").toAbsolutePath();

    /** The controls of section 4: the counts behind the five best rows at g in each comparator family, and Hi-Lo. */
    static final List<String> CONTROLS = Arrays.asList("Hi-Opt II", "Victor Advanced Point Count (Victor APC)",
            "Uston Advanced Point Count (Uston APC)", "Omega II", "Revere 14 Count (1973 Advanced Point Count)", "Hi-Lo",
            "Zen Count", "BRH-I", "Mentor", "Wong Halves", "Revere Point Count (RPC)");

    @Test
    public void theRegistrationReadsAsSectionFiveFixesIt() throws Exception {
        Path file = ROOT.resolve(CustomCounts.REGISTERED);
        assumeTrue(Files.exists(file), "no registration yet");
        CustomCounts.Listing l = CustomCounts.read(file);
        assertTrue(l.registered);
        assertEquals("b99ccbf1954ee702ddd8e0dc7c22f665edc6c041", l.searchCommit);
        assertEquals(CustomCounts.FULL_PLAY, l.fullPlayFile);
        assertEquals(CustomCounts.git(ROOT, "hash-object", "--", CustomCounts.FULL_PLAY), l.fullPlayBlob,
                "the registration names its full-play registration as it stands");

        assertEquals(Arrays.asList("F1 flat (f1-flat-pub-uston-apc)"), l.cells.get("flat"));
        List<String> both = Arrays.asList("F1 at 1-8 (f1-8-vidami-ar)", "F2 at 1-8, play vector (f2-pub-victor-apc)");
        assertEquals(both, l.cells.get("same"));
        assertEquals(both, l.cells.get("own"));
        CustomCounts.Entry f1 = entry(l, "F1 at 1-8 (f1-8-vidami-ar)");
        CustomCounts.Entry f2 = entry(l, "F2 at 1-8, play vector (f2-pub-victor-apc)");
        assertFalse(f1.allComparators());
        assertFalse(entry(l, "F1 flat (f1-flat-pub-uston-apc)").allComparators());
        assertTrue(f2.allComparators());
        assertEquals("F2 at 1-8, play vector (f2-pub-victor-apc) + betting vector", CountKeys.bettingVariant(f2.count).name,
                "the headline row reads as F2 with its betting vector");

        List<String> roles = new ArrayList<>();
        List<String> controls = new ArrayList<>();
        for (CustomCounts.Entry e : l.entries) {
            roles.add(e.role);
            if ("control".equals(e.role)) {
                assertEquals(500, e.count.divisor(), e.count.name);
                controls.add(e.count.systems.get(0));
            }
        }
        assertEquals(Arrays.asList("headline", "headline", "headline", "check", "check", "check", "check", "control",
                "control", "control", "control", "control", "control", "control", "control", "control", "control", "control"),
                roles);
        assertEquals(CONTROLS.size(), controls.size());
        for (String c : CONTROLS) {
            assertTrue(controls.stream().anyMatch(x -> x.equals(c) || x.startsWith(c + " ")), c);
        }
        CustomCounts.Entry r = entry(l, "F1r at 1-8 (f1r-double-shift-half)");
        assertFalse(r.count.trueCounted);
        assertEquals(2423, r.count.divisor());
        assertTagsAtG(file);
    }

    @Test
    public void theFullPlayRegistrationIsTheFirstDecisionCountsAndThreeControls() throws Exception {
        Path file = ROOT.resolve(CustomCounts.REGISTERED);
        Path full = ROOT.resolve(CustomCounts.FULL_PLAY);
        assumeTrue(Files.exists(file) && Files.exists(full), "no registration yet");
        CustomCounts.Listing l = CustomCounts.read(file);
        CustomCounts.Listing f = CustomCounts.read(full);
        assertTrue(f.registered);
        assertEquals(CustomCounts.REGISTERED, f.registeredWith);
        assertNull(f.fullPlayFile);
        assertEquals(l.searchCommit, f.searchCommit);
        assertEquals(l.twin, f.twin);
        List<String> names = new ArrayList<>();
        for (CustomCounts.Entry e : f.entries) {
            names.add(e.count.name);
        }
        assertEquals(Arrays.asList("F1 at 1-8 (f1-8-vidami-ar)", "F2 at 1-8, play vector (f2-pub-victor-apc)",
                "Hi-Lo = Hi-Lo Lite", "Zen Count at grain 500/10000", "Hi-Opt II at grain 500/10000"), names);
        for (String name : new String[]{"F1 at 1-8 (f1-8-vidami-ar)", "F2 at 1-8, play vector (f2-pub-victor-apc)",
                "Zen Count at grain 500/10000", "Hi-Opt II at grain 500/10000"}) {
            assertEquals(entry(l, name).count.describe(), entry(f, name).count.describe(),
                    name + " is the same count in both files, to the bit");
            assertEquals(entry(l, name).role, entry(f, name).role, name);
        }
        CountKeys.Count hiLo = entry(f, "Hi-Lo = Hi-Lo Lite").count;
        assertEquals(1, hiLo.divisor(), "Hi-Lo at its own grain, to be tied to c06c061's within 1e-12");
        assertEquals(PublishedCatalog.hiLo().describe(), hiLo.describe());
        assertEquals("check", entry(f, "Hi-Lo = Hi-Lo Lite").role);
        assertNotNull(CountKeys.bettingVariant(entry(f, "Hi-Opt II at grain 500/10000").count), "its side count comes with it");
        assertEquals(Arrays.asList("F1 at 1-8 (f1-8-vidami-ar)"), f.cells.get("flat"));
        assertEquals(Arrays.asList("F1 at 1-8 (f1-8-vidami-ar)", "F2 at 1-8, play vector (f2-pub-victor-apc)"),
                f.cells.get("same"));
        assertEquals(f.cells.get("same"), f.cells.get("own"));
        assertTagsAtG(full);
    }

    /** The catalog's Hi-Lo as the stored rows hold it. */
    static final class PublishedCatalog {
        static CountKeys.Count hiLo() {
            for (CountKeys.Count c : CountKeys.catalog()) {
                if (c.systems.contains("Hi-Lo")) {
                    return c;
                }
            }
            throw new IllegalStateException("no Hi-Lo");
        }
    }

    private static CustomCounts.Entry entry(CustomCounts.Listing l, String name) {
        int i = l.indexOf(name);
        assertTrue(i >= 0, name);
        return l.entries.get(i);
    }

    /** Each catalog count at g's recorded tagsAtG, A and 2 to 9 and T, equal to the loader's whole tags at K now. */
    private static void assertTagsAtG(Path file) throws Exception {
        CustomCounts.Listing l = CustomCounts.read(file);
        Document d = Document.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        int seen = 0;
        for (Document c : d.getList("counts", Document.class)) {
            if (c.getString("catalog") == null || c.get("divisor") == null) {
                continue;
            }
            CountKeys.Count count = entry(l, c.getString("name")).count;
            assertFalse(count.kindAware, count.name);
            List<?> recorded = c.getList("tagsAtG", Object.class);
            assertNotNull(recorded, count.name + " records its tags at g");
            int[] now = new int[10];
            int[] was = new int[10];
            for (int r = 1; r <= 10; r++) {
                now[r - 1] = count.rankTag(r);
                was[r - 1] = ((Number) recorded.get(r - 1)).intValue();
            }
            assertArrayEquals(was, now, count.name + "'s tags at g as recorded and as the loader computes them");
            seen++;
        }
        assertTrue(seen > 0);
    }
}
