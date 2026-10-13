import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SiteRisk on a risk file RiskReport wrote from RiskReportTest's fixture files (the engine's
 * results, step 7's chart, the replay's wide plans, the simulator's swings): every figure carried
 * is the file's own at its place in the layout, under the key of its row as SiteExport names it;
 * the rows the file did not compute get none; and the input reads in SiteData.
 */
public class SiteRiskTest {

    @TempDir
    Path dir;

    Path riskFile() throws Exception {
        RiskReportTest t = new RiskReportTest();
        t.dir = dir;
        RiskReportTest.Inputs in = t.new Inputs();
        in.replay = true;
        RiskReport.Outcome o = in.run();
        assertTrue(o.failures.isEmpty(), o.failures.toString());
        return in.json();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void everyFigureIsTheRiskFilesOwnUnderItsRowsKey() throws Exception {
        Path file = riskFile();
        Map<String, Object> risk = SiteAdapt.read(file);
        SiteInput in = SiteRisk.adapt(file, "runs/site/risk-fixture.json");
        Map<String, Object> figs = in.figures;
        List<Object> layout = (List<Object>) risk.get("layout");
        int checked = 0;
        for (Object o : (List<Object>) risk.get("rows")) {
            Map<String, Object> r = (Map<String, Object>) o;
            String fig = (String) r.get("figure");
            // The row's key, worked out here from the registry's words.
            String id = Map.of("F03", "chart", "F18", "hi_lo", "F27", "hi_lo", "F29", "victor_advanced_point_count_ace",
                    "F50", "f1", "F52", "f2").getOrDefault(fig, null);
            RiskReport.Spec s = RiskReport.spec(fig);
            if (id == null) {
                id = Map.of("Hi-Lo", "hi_lo", "KO", "ko", "Zen", "zen_count", "Hi-Opt II + ace", "hi_opt_ii_ace",
                        "Victor APC + ace", "victor_advanced_point_count_ace").get(s.count);
            }
            String scope = s.play.equals("chart") ? "bet" : s.play.equals("full play") ? "full" : "fd";
            String spread = s.bets.equals("flat") ? "s1" : s.bets.equals("1-8") ? "s8"
                    : Map.of("P16", "p16", "B0,8", "b0_8", "X-1,8", "xm1_8").get(s.bets);
            String at = scope + "." + new String[]{"70", "75", "80"}[s.pen] + "." + spread + "." + id;
            if (!"computed".equals(r.get("status"))) {
                assertNull(figs.get("risk.ev_per100." + at), fig + " was not computed and gets no figure");
                continue;
            }
            List<Object> f = (List<Object>) r.get("f");
            for (String k : SiteRisk.SHOWN.keySet()) {
                Object want = f.get(layout.indexOf(k));
                Map<String, Object> got = (Map<String, Object>) figs.get("risk." + k.replace(".", "_").replace("%", "")
                        + "." + at);
                if (want == null || (want instanceof List && ((List<Object>) want).get(0) == null)) {
                    assertNull(got, k + " of " + fig + " is null in the file");
                    continue;
                }
                assertNotNull(got, k + " of " + fig);
                if (want instanceof List) {
                    List<Object> w = (List<Object>) want;
                    assertEquals(((Number) w.get(0)).doubleValue(), got.get("value"), k + " of " + fig);
                    if (w.get(2) != null) {
                        assertEquals(List.of(((Number) w.get(1)).doubleValue(), ((Number) w.get(2)).doubleValue()),
                                got.get("ci"), k + " of " + fig);
                    } else {
                        assertNull(got.get("ci"), "no upper end: no interval");
                    }
                } else {
                    assertEquals(((Number) want).doubleValue(), got.get("value"), k + " of " + fig);
                }
                checked++;
            }
            if (s.play.equals("full play")) {
                assertEquals(((Number) r.get("widen")).doubleValue(),
                        ((Map<String, Object>) figs.get("risk.ev_per100." + at)).get("widen"), fig);
            }
        }
        assertTrue(checked >= 10 * SiteRisk.SHOWN.size(), checked + " figures checked");
        assertEquals(25.0, ((Map<String, Object>) figs.get("risk.unit_usd")).get("value"));
        assertEquals(100.0, ((Map<String, Object>) figs.get("risk.rounds")).get("value"));
        assertNull(figs.get("risk.ev_per100.full.80.s8.f1"), "step 8's rows were not given to RiskReport");
        SiteData d = new SiteData();
        d.add("risk-fixture.json", in.text("risk-fixture.json").getBytes(StandardCharsets.UTF_8));
        assertEquals(SiteInput.sha256(file), ((Map<String, Object>) ((List<Object>) d.inputs.get(0).source.get("artifacts"))
                .get(0)).get("sha256"));
    }

    /** A file whose row is not the one RiskReport's registry gives that figure id is refused. */
    @Test
    public void aRowThatIsNotItsRegisteredFigureIsRefused() throws Exception {
        Path file = riskFile();
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Path bad = Files.write(dir.resolve("bad.json"), text.replaceFirst("\"figure\":\"F29\",\"count\":\"Victor APC \\+ ace\"",
                "\"figure\":\"F29\",\"count\":\"Zen\"").getBytes(StandardCharsets.UTF_8));
        assertNotEquals(text, new String(Files.readAllBytes(bad), StandardCharsets.UTF_8));
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class, () -> SiteRisk.adapt(bad, "bad"));
        assertTrue(x.getMessage().contains("F29"), x.getMessage());
    }
}
