import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The registration box's input (SiteLedger; PLAN_COMPUTE.md 6.3), in a git repository of its own
 * standing for the count-design worktree, never the real one: the ledger is printed only from a
 * copy equal to the one home's byte for byte, committed, holding step 7's, step 8's and the page
 * look's lines with their registrations' blobs and no look at another registration.
 */
public class SiteLedgerTest {

    @TempDir
    Path dir;

    static String line(String blob, String what) {
        return "97bf834\t" + blob + "\t2026-10-09T19:16:00-04:00\t" + what + "\n";
    }

    @Test
    @SuppressWarnings("unchecked")
    public void theLedgerIsPrintedFromItsOneHomeWithEveryLook() throws Exception {
        assertNotNull(CustomCounts.git(dir, "init", "-q"));
        CustomCounts.git(dir, "config", "user.email", "test@example.com");
        CustomCounts.git(dir, "config", "user.name", "test");
        Path full = CustomCountsTest.write(dir, CustomCounts.FULL_PLAY, CustomCountsTest.fullPlay("abc"));
        String fullBlob = CustomCounts.git(dir, "hash-object", "--", CustomCounts.FULL_PLAY);
        Path reg = CustomCountsTest.write(dir, CustomCounts.REGISTERED, CustomCountsTest.naming(fullBlob));
        String blob = CustomCounts.git(dir, "hash-object", "--", CustomCounts.REGISTERED);
        assertNotNull(full);
        String three = CustomCounts.LEDGER_HEADER + "\n" + line(blob, PageLook.STEP7 + " runs/results.ser")
                + line(fullBlob, PageLook.STEP8 + " into runs/step8") + line(blob, PageLook.WHAT + " (PageLook into runs/look)");
        Path ledger = CustomCountsTest.write(dir, CustomCounts.LEDGER, three);
        CustomCounts.git(dir, "add", "-A");
        CustomCounts.git(dir, "commit", "-q", "-m", "registered, and three looks");
        Path copy = Files.write(dir.resolve("ledger-copy.tsv"), three.getBytes(StandardCharsets.UTF_8));

        SiteInput in = SiteLedger.adapt(copy, dir);
        List<Object> t = (List<Object>) in.tables.get("ledger");
        assertEquals(3, t.size());
        assertEquals(blob, ((Map<?, ?>) t.get(2)).get("blob"));
        assertEquals(3.0, ((Number) ((Map<?, ?>) in.figures.get("ledger.lines")).get("value")).doubleValue());
        assertNotNull(reg);

        // A copy one byte off.
        Path off = Files.write(dir.resolve("ledger-off.tsv"), three.replace("19:16", "19:17").getBytes(StandardCharsets.UTF_8));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SiteLedger.adapt(off, dir));
        assertTrue(e.getMessage().contains("byte for byte"), e.getMessage());
        // The page look's line missing.
        String two = three.substring(0, three.lastIndexOf("97bf834\t" + blob + "\t2026-10-09T19:16:00-04:00\t" + PageLook.WHAT));
        Files.write(ledger, two.getBytes(StandardCharsets.UTF_8));
        Files.write(copy, two.getBytes(StandardCharsets.UTF_8));
        CustomCounts.git(dir, "commit", "-q", "-am", "the page look's line taken out");
        e = assertThrows(IllegalArgumentException.class, () -> SiteLedger.adapt(copy, dir));
        assertTrue(e.getMessage().contains("the page look"), e.getMessage());
        // A look at another registration.
        String other = three + line("ffffffffffffffffffffffffffffffffffffffff", "FullIndexRun started into runs/other");
        Files.write(ledger, other.getBytes(StandardCharsets.UTF_8));
        Files.write(copy, other.getBytes(StandardCharsets.UTF_8));
        CustomCounts.git(dir, "commit", "-q", "-am", "a look at another registration");
        e = assertThrows(IllegalArgumentException.class, () -> SiteLedger.adapt(copy, dir));
        assertTrue(e.getMessage().contains("another registration"), e.getMessage());
        // The ledger changed and not committed.
        Files.write(ledger, three.getBytes(StandardCharsets.UTF_8));
        Files.write(copy, three.getBytes(StandardCharsets.UTF_8));
        IllegalStateException x = assertThrows(IllegalStateException.class, () -> SiteLedger.adapt(copy, dir));
        assertTrue(x.getMessage().contains("not committed"), x.getMessage());
    }
}
