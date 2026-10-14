import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SiteEdges on a key-edges file KeyEdgesReport wrote, after runs A and A' from files, as
 * KeyEdgesFilesTest drives them on a synthetic state file and stored rows the scorer made: each
 * figure carried is the file's own (an edge in percent, a share as a fraction), under the key of
 * its row as SiteExport names it, with the file's interval and widening; and the input reads in
 * SiteData.
 */
public class SiteEdgesTest {

    /** The key-edges file KeyEdgesReport writes from KeyEdgesRun's two parts on a synthetic state file, in dir. */
    static Path keyEdges(Path dir) throws Exception {
        Path file = ComparisonReportTest.syntheticRun(dir, 131, 131, 24);
        ScoringStates st = KeyEdgesRun.load(file);
        int resamples = CeilingReportTest.RESAMPLES;
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, resamples, Bootstrap.SEED), SystemScorer.M_PLAY);
        List<SystemScorer.Result> stored = new ArrayList<>();
        for (CountKeys.Count c : KeyEdgesRun.catalogRows()) {
            stored.add(sc.score(c, CountKeys.bettingVariant(c)));
        }
        Path ser = CeilingReportTest.results(dir, file, st, stored);
        Path out = Files.createDirectories(dir.resolve("out"));
        PrintStream log = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        KeyEdgesRun.runA(file, ser, out, "test", resamples, log);
        KeyEdgesRun.runAPrime(file, ser, out, "test", resamples, 1, log);
        KeyEdgesReport.write(out.resolve("a-test.ser"), new ArrayList<>(), out.resolve("a-prime-test.ser"),
                new ArrayList<>(), ser, null, out, "test", resamples, log);
        return out.resolve("key-edges-test.json");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void theEdgesAreTheFilesOwnUnderTheirRowsKeys(@TempDir Path dir) throws Exception {
        Path json = keyEdges(dir);

        SiteInput in = SiteEdges.adapt(json, "runs/site/key-edges-test.json");
        Map<String, Object> d = SiteAdapt.read(json);
        List<Object> top = (List<Object>) d.get("top");
        int one = top.indexOf(0.01);
        assertTrue(one >= 0, "the report's top shares hold 1%");
        int checked = 0;
        for (Object o : (List<Object>) d.get("rows")) {
            Map<String, Object> r = (Map<String, Object>) o;
            String id = SiteInput.catalogRow((String) r.get("row"))[0];
            for (Object po : (List<Object>) r.get("penetrations")) {
                Map<String, Object> p = (Map<String, Object>) po;
                String pen = ((String) p.get("penetration")).replace("%", "");
                for (Object lo : (List<Object>) p.get("layers")) {
                    Map<String, Object> l = (Map<String, Object>) lo;
                    String scope = Map.of("first decisions", "fd", "full play", "full", "chart + insurance", "bet")
                            .get((String) l.get("layer"));
                    if (scope == null) {
                        continue;
                    }
                    Map<String, Object> t1 = (Map<String, Object>) ((List<Object>) l.get("top")).get(one);
                    Map<String, Object> f = (Map<String, Object>) in.figures.get("edge.top1." + scope + "." + pen + "." + id);
                    if (!pen.equals("80")) {
                        assertNull(f, "top shares at 80% only");
                        continue;
                    }
                    assertEquals(100 * ((Number) t1.get("estimate")).doubleValue(), f.get("value"), id + " " + scope);
                    if (t1.get("lo") != null) {
                        assertEquals(List.of(100 * ((Number) t1.get("lo")).doubleValue(), 100 * ((Number) t1.get("hi"))
                                .doubleValue()), f.get("ci"), id + " " + scope);
                        double w = t1.get("widenedBy") == null ? 1 : ((Number) t1.get("widenedBy")).doubleValue();
                        assertEquals(w, f.get("widen"));
                    }
                    Map<String, Object> pos = (Map<String, Object>) l.get("positiveKeys");
                    assertEquals(((Number) pos.get("estimate")).doubleValue(),
                            ((Map<String, Object>) in.figures.get("edge.positive." + scope + "." + pen + "." + id)).get("value"));
                    checked++;
                }
            }
        }
        assertTrue(checked >= 4, checked + " row-layers checked");
        assertNotNull(in.figures.get("edge.slope.fd.70.hi_lo"), "Hi-Lo's line at every penetration");
        SiteData sd = new SiteData();
        sd.add("key-edges-test.json", in.text("key-edges-test.json").getBytes(StandardCharsets.UTF_8));
        assertEquals("key-edges", sd.inputs.get(0).what());
    }

    /** A full-play figure whose interval the report did not widen is carried without it. */
    @Test
    public void anUnwidenedFullPlayIntervalIsLeftOut() {
        SiteInput in = new SiteInput("key-edges", "test", "main");
        Map<String, Object> held = new java.util.LinkedHashMap<>();
        held.put("estimate", 0.01);
        held.put("lo", 0.005);
        held.put("hi", 0.015);
        held.put("se", 0.0025);
        held.put("how", "held");
        SiteEdges.figure(in, "edge.top1.full.80.hi_lo", held, true, "s2", "full", "x");
        assertNull(((Map<?, ?>) in.figures.get("edge.top1.full.80.hi_lo")).get("ci"));
        held.put("widenedBy", 1.07);
        SiteEdges.figure(in, "edge.top5.full.80.hi_lo", held, true, "s2", "full", "x");
        assertEquals(List.of(0.5, 1.5), ((Map<?, ?>) in.figures.get("edge.top5.full.80.hi_lo")).get("ci"));
        SiteEdges.figure(in, "edge.top1.fd.80.hi_lo", held, true, "s2", "fd", "x");
        assertEquals(1.07, ((Map<?, ?>) in.figures.get("edge.top1.fd.80.hi_lo")).get("widen"));
        new SiteData().add("x.json", in.text("x.json").getBytes(StandardCharsets.UTF_8));
    }
}
