import org.junit.jupiter.api.BeforeAll;
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
 * SiteExport's rows from the ceiling count's files, on SiteWorld's (c06c061's rows, step 7's save
 * and its registration, step 8's run and its registration): the save's bound rows as the row
 * perfect in each scope, not widened; each registered headline count by its family, at first
 * decisions and betting only from the save and at full play from step 8, widened as a count the
 * pilot did not measure; the check counts left out; every cell its entry's figure at every
 * penetration and spread, by a route written here. A save made beside other stored rows, a
 * registration of other counts or in another order, and a step 8 run on other states are refused.
 */
public class SiteExportCeilingTest {

    @TempDir
    static Path dir;
    static SiteWorld world;
    static String template;
    static Map<String, Object> grid;
    /** The world's pilot measured difference ratios of 1.04 and 1.12: a count it did not measure takes 1.12. */
    static final double NOT_MEASURED = 1.12;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void exported() throws Exception {
        world = SiteWorld.make(Files.createDirectories(dir.resolve("world")));
        template = new String(Files.readAllBytes(Paths.get("site/template.html")), StandardCharsets.UTF_8);
        grid = (Map<String, Object>) SiteExport.export(world.export(template)).json().get("grid");
    }

    /** A cell as the page shows it, worked out here: estimate, SE and interval in percent, resamples widened by w. */
    static double[] expected(SystemScorer.Entry e, int p, int s, double w) {
        int m = s == 1 ? SystemScorer.FLAT : 2 + s;
        Bootstrap.Interval iv = Bootstrap.interval(e.estimate[p][m], w == 1 ? e.boot[p][m] : Bootstrap.widen(e.boot[p][m], w));
        return new double[]{100 * iv.estimate, 100 * iv.se, 100 * iv.lo, 100 * iv.hi};
    }

    /** Checks a row's cells in one scope against an entry at every penetration and spread; the cells checked. */
    @SuppressWarnings("unchecked")
    static int check(String id, String scope, SystemScorer.Entry e, double w) {
        Map<String, Object> row = (Map<String, Object>) grid.get(id);
        assertNotNull(row, id);
        Map<String, Object> sc = (Map<String, Object>) row.get(scope);
        assertNotNull(sc, id + " " + scope);
        int n = 0;
        for (int p = 0; p < 3; p++) {
            Map<String, Object> pen = (Map<String, Object>) sc.get(new String[]{"70", "75", "80"}[p]);
            for (int s = 1; s <= 8; s++) {
                List<Object> c = (List<Object>) pen.get("s" + s);
                double[] want = expected(e, p, s, w);
                for (int k = 0; k < 4; k++) {
                    assertEquals(want[k], (Double) c.get(k), 0, id + " " + scope + " " + p + " s" + s + " [" + k + "]");
                }
                n++;
            }
        }
        return n;
    }

    static SystemScorer.Entry named(List<SystemScorer.Entry> entries, String name) {
        for (SystemScorer.Entry e : entries) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        throw new AssertionError("no entry " + name);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void theBoundsAndTheRegisteredCountsAreTheirEntriesAtEverySpread() {
        int cells = 0;
        cells += check("perfect", "bet", named(world.c.bounds, "Chart play and perfect insurance, bet on its own value"), 1);
        cells += check("perfect", "fd", named(world.c.bounds, "Perfect first move and insurance, bet on its own value"), 1);
        cells += check("perfect", "full", named(world.c.bounds,
                "Perfect play of the dealt hand and insurance, bet on its own value"), 1);
        // F1 and F1 flat: their own rows; F2 its row with the betting vector, which has no betting-only row.
        List<SystemScorer.Entry> f1 = world.c.custom.get(0).entries;
        cells += check("f1", "fd", named(f1, "F1"), 1);
        cells += check("f1", "bet", named(f1, "F1, betting only"), 1);
        cells += check("f1", "full", named(world.run.results.get(0).fullEntries, "F1, full play"), NOT_MEASURED);
        List<SystemScorer.Entry> flat = world.c.custom.get(1).entries;
        cells += check("f1_flat", "fd", named(flat, "F1 flat"), 1);
        cells += check("f1_flat", "bet", named(flat, "F1 flat, betting only"), 1);
        SystemScorer.Result f2 = world.c.custom.get(2);
        String vector = CountKeys.bettingVariant(f2.count).name;
        cells += check("f2", "fd", named(f2.entries, vector), 1);
        cells += check("f2", "full", named(world.run.results.get(1).fullEntries, vector + ", full play"), NOT_MEASURED);
        assertEquals(10 * 3 * 8, cells);
        assertNull(((Map<String, Object>) grid.get("f1_flat")).get("full"), "F1 flat was not scored at full play");
        assertNull(((Map<String, Object>) grid.get("f2")).get("bet"), "F2's row with its vector has no betting-only row");
        for (String check : new String[]{"f1_half_0", "f1_half_1", "f1_half_0_count"}) {
            assertNull(grid.get(check), "a check count is not a row of the page");
        }
        assertEquals(List.of("full"), grid.get("_widened"));
    }

    /** One way the files may not belong together, applied to the world's options; what the refusal says. */
    String refusal(java.util.function.Consumer<SiteExport.Options> change) throws Exception {
        SiteExport.Options o = world.export(template);
        change.accept(o);
        IllegalStateException x = assertThrows(IllegalStateException.class, () -> SiteExport.export(o));
        return x.getMessage();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void filesThatDoNotBelongTogetherAreRefused() throws Exception {
        CeilingReport.Published other = new CeilingReport.Published();
        other.identity = world.identity.replace("commit c06c061", "commit c0ffee1");
        other.results = world.c.published.results;
        other.perfect = world.c.published.perfect;
        Path stored = CeilingResultsTest.published(dir.resolve("other.ser"), other);
        String m = refusal(o -> o.stored = stored);
        assertTrue(m.contains("stored rows of c06c061, not of c0ffee1"), m);

        m = refusal(o -> o.counts = world.fullRegistration);
        assertTrue(m.contains("the save holds 5 counts, the registration 3"), m);

        Map<String, Object> reg = (Map<String, Object>) Json.parse(CeilingParagraphsTest.REGISTRATION);
        List<Object> counts = (List<Object>) reg.get("counts");
        counts.add(0, counts.remove(1));
        Path swapped = Files.write(dir.resolve("swapped.json"), Json.write(reg, 3).getBytes(StandardCharsets.UTF_8));
        m = refusal(o -> o.counts = swapped);
        assertTrue(m.contains("the save's count 0 is F1, the registration's F1 flat"), m);

        CeilingReport.Published run = new CeilingReport.Published();
        run.identity = world.run.identity.replaceFirst("states [^\n]*", "states /elsewhere.states, 1 bytes, modified 1");
        run.results = new ArrayList<>(world.run.results);
        run.perfect = world.run.perfect;
        Path elsewhere = CeilingResultsTest.published(dir.resolve("elsewhere.ser"), run);
        m = refusal(o -> o.fullplay = elsewhere);
        assertTrue(m.contains("the runs do not pair"), m);
    }
}
