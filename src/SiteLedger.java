import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The registration box's input (PLAN_COMPUTE.md 6.3, PLAN_PAGE.md's registration box;
 * INTEGRATION.md open item 12): the ledger of the looks at the main file, as the page prints it.
 *
 *   SiteAdapt ledger LEDGER.tsv --count-design DIR --out runs/site/ledger-SHA.json
 *
 * The generator prints the ledger only if the copy it is given equals the count-design worktree's
 * custom-count-ledger.tsv byte for byte, that file and both registrations are committed there as
 * they stand (CustomCounts.committedBlob), and it holds step 7's line (the registration's blob),
 * step 8's (the full-play registration's) and the page look's (the registration's), and no line
 * of another registration: a second look is not printed as if it were the one.
 */
final class SiteLedger {

    private SiteLedger() {
    }

    static SiteInput adapt(Path copy, Path countDesign) throws IOException {
        Path home = countDesign.toAbsolutePath().normalize();
        CustomCounts.Listing reg = CustomCounts.read(home.resolve(CustomCounts.REGISTERED));
        CustomCounts.Listing full = CustomCounts.read(home.resolve(CustomCounts.FULL_PLAY));
        String blob = CustomCounts.committedBlob(reg.file);
        String fullBlob = CustomCounts.committedBlob(full.file);
        Path ledger = CustomCounts.ledgerOf(reg);
        CustomCounts.committedBlob(ledger);
        if (!Arrays.equals(Files.readAllBytes(copy), Files.readAllBytes(ledger))) {
            throw new IllegalArgumentException(copy + " is not " + ledger + " byte for byte; the page prints the ledger's "
                    + "one home only");
        }
        if (!fullBlob.equals(reg.fullPlayBlob)) {
            throw new IllegalArgumentException(full.file + " is committed as blob " + fullBlob + ", not the "
                    + reg.fullPlayBlob + " the registration names");
        }
        List<String[]> lines = PageLook.ledger(ledger);
        String[][] wanted = {{blob, PageLook.STEP7, "step 7"}, {fullBlob, PageLook.STEP8, "step 8"},
                {blob, PageLook.WHAT, "the page look"}};
        for (String[] w : wanted) {
            if (!PageLook.holds(lines, w[0], w[1])) {
                throw new IllegalArgumentException(ledger + " holds no line of " + w[2] + " (blob " + w[0] + ", \"" + w[1]
                        + "\"); the box is printed once steps 7 and 8 and the page look are in it");
            }
        }
        List<String> other = CustomCounts.otherLooks(ledger, new java.util.LinkedHashSet<>(List.of(blob, fullBlob)));
        if (!other.isEmpty()) {
            throw new IllegalArgumentException(ledger + " holds " + other.size() + " look(s) at another registration, the "
                    + "first: " + other.get(0) + "; a second look is not printed as the one");
        }
        SiteInput in = new SiteInput("ledger", blob, "main");
        in.artifact(copy, copy.toString());
        List<Object> table = new ArrayList<>();
        for (String[] f : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("commit", f[0]);
            m.put("blob", f[1]);
            m.put("time", f.length > 2 ? f[2] : "");
            m.put("what", f.length > 3 ? f[3] : "");
            table.add(m);
        }
        in.tables.put("ledger", table);
        SiteInput.at(in.figure("ledger.lines", table.size(), "n"), null, "the looks at the main file the ledger holds");
        return in;
    }
}
