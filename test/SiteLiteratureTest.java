import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SiteAdapt's literature step on files LiteratureOutput.page wrote from checks LiteratureCheckTest
 * ran on its cached appendix-9 fixture, once as cached and once with a cell planted 1e-4 off: the
 * file is carried byte for byte, its lit. figures are the check's own verdicts counted here, the
 * page generator reads it, and a file marked a fixture, one of another computation, or one with a
 * figure outside lit. is refused, each edit made to the check's own file.
 */
public class SiteLiteratureTest {

    @TempDir
    Path dir;

    /** The check on the fixture's cached page, the Stand cell of 6,5 against an ace planted 1e-4 high if asked. */
    LiteratureCheck.Result check(boolean planted) throws Exception {
        double[] v = LiteratureCheckTest.madeUp();
        String page = LiteratureCheckTest.appendix9(v);
        String sha = LiteratureSources.sha256(page.getBytes(StandardCharsets.UTF_8));
        if (planted) {
            int deal = Deals.index(5, 6, 1);
            String cell = String.format(Locale.ROOT, "%.6f", v[StateValuer.slot(deal, PlayerMove.Stand)]);
            String plant = String.format(Locale.ROOT, "%.6f", v[StateValuer.slot(deal, PlayerMove.Stand)] + 1e-4);
            String row = "<tr><td>A</td><td>6,5</td><td>" + cell + "</td>";
            assertEquals(page.indexOf(row), page.lastIndexOf(row), "the planted row is one row");
            page = page.replace(row, "<tr><td>A</td><td>6,5</td><td>" + plant + "</td>");
        }
        return LiteratureCheckTest.check(dir, page, sha, v);
    }

    /** The page's file as LiteratureCheck.main writes it, the cached page listed with its SHA-256. */
    Path file(LiteratureCheck.Result r, String name) throws Exception {
        Map<String, String> artifacts = new LinkedHashMap<>();
        artifacts.put("fixture/a9.html", LiteratureSources.sha256(dir.resolve("a9.html")));
        Path f = dir.resolve(name);
        LiteratureOutput.write(f, LiteratureOutput.json(LiteratureOutput.page(r, "fixture-lit", artifacts)));
        return f;
    }

    /** How many of the check's outcomes have each verdict, counted here from the outcomes themselves. */
    static int count(LiteratureCheck.Result r, LiteratureClaims.Verdict v) {
        int n = 0;
        for (LiteratureClaims.Outcome o : r.outcomes.values()) {
            n += o.verdict == v ? 1 : 0;
        }
        return n;
    }

    @Test
    public void theCheckOwnFileIsCarriedAsWrittenWithItsVerdictsCounted() throws Exception {
        String[] keys = {"lit.agree", "lit.direction", "lit.tested", "lit.untested", "lit.unexplained", "lit.not_compared"};
        int planted = 0;
        for (boolean plant : new boolean[]{false, true}) {
            LiteratureCheck.Result r = check(plant);
            Path f = file(r, "literature-" + plant + ".json");
            byte[] carried = SiteAdapt.literature(f);
            assertArrayEquals(Files.readAllBytes(f), carried, "the file is copied as it is");
            SiteData d = new SiteData();
            d.add(f.getFileName().toString(), carried);
            assertEquals("fixture-lit", d.inputs.get(0).commit());
            LiteratureClaims.Verdict[] kinds = LiteratureClaims.Verdict.values();
            int compared = 0;
            for (int k = 0; k < kinds.length; k++) {
                assertEquals(count(r, kinds[k]), d.resolve(keys[k]).value, 0, keys[k] + (plant ? ", planted" : ""));
                compared += kinds[k] == LiteratureClaims.Verdict.NOT_COMPARED ? 0 : count(r, kinds[k]);
            }
            assertEquals(compared, d.resolve("lit.compared").value, 0);
            assertEquals(r.register.sources.size(), d.resolve("lit.sources").value, 0);
            assertEquals(r.register.claims.size(), ((List<?>) d.tables.get("literature_claims")).size());
            planted += plant ? count(r, LiteratureClaims.Verdict.AGREES) : -count(r, LiteratureClaims.Verdict.AGREES);
        }
        assertEquals(-1, planted, "the plant moves one claim out of agrees, and the figures follow");

        // Through main, as build-site's inputs are made: copied once, never written over.
        Path f = file(check(false), "literature-main.json");
        Path out = dir.resolve("site").resolve("literature.json");
        Files.createDirectories(out.getParent());
        SiteAdapt.main(new String[]{"literature", f.toString(), "--out", out.toString()});
        assertArrayEquals(Files.readAllBytes(f), Files.readAllBytes(out));
        assertThrows(IllegalStateException.class,
                () -> SiteAdapt.main(new String[]{"literature", f.toString(), "--out", out.toString()}));
    }

    /** One edit to the check's own file: the file's JSON with what set at the path given. */
    @SuppressWarnings("unchecked")
    Path edited(Path f, String name, java.util.function.Consumer<Map<String, Object>> edit) throws Exception {
        Map<String, Object> m = (Map<String, Object>) Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
        edit.accept(m);
        return Files.write(dir.resolve(name), Json.write(m, 3).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aFixtureAnotherComputationsFileOrAFigureOutsideLitIsRefused() throws Exception {
        Path f = file(check(false), "literature.json");
        // The edits are made on a copy written by the same JSON writer, which is itself accepted.
        Path same = edited(f, "same.json", m -> { });
        assertNotNull(SiteAdapt.literature(same));
        Path fixture = edited(f, "fixture.json", m -> ((Map<String, Object>) m.get("_source")).put("fixture", true));
        Path other = edited(f, "other.json", m -> ((Map<String, Object>) m.get("_source")).put("what", "risk"));
        Path outside = edited(f, "outside.json", m -> {
            Map<String, Object> figs = (Map<String, Object>) m.get("figures");
            figs.put("agree", figs.remove("lit.agree"));
        });
        for (Path bad : new Path[]{fixture, other, outside}) {
            IllegalArgumentException x = assertThrows(IllegalArgumentException.class, () -> SiteAdapt.literature(bad),
                    bad.getFileName().toString());
            assertTrue(x.getMessage().contains(bad.getFileName().toString()), x.getMessage());
        }
    }
}
