import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SiteWide on a spreads file WideReport wrote from wide.ser of WideReportTest's scoring (Hi-Lo and
 * the null count with their full-index fits, Red Seven, the bounds and references, every wide
 * plan), widened by a pilot: P12, P16, P20 and S16 land as the spreads of the page's keys, the B
 * plans entering at 0 as the wonging grid, each figure the file's own in percent with the file's
 * widening; the plans' bets and shares are the same for every row; Hi-Lo's thresholds are its
 * keys; and the input reads in SiteData.
 */
public class SiteWideTest {

    @TempDir
    Path dir;

    /** The spreads file WideReport writes from wide.ser of WideReportTest's scoring, widened by a pilot, in dir. */
    static Path spreads(Path dir) throws Exception {
        List<WideReport.Row> rows = WideReportTest.scored(FullIndexFitTest.capped(), 6);
        Path ser = dir.resolve("wide.ser");
        WideReport.save(ser, "test scoring\ncommit test", SystemScorer.WIDE_PLANS, rows);
        String red = FullIndexFitTest.RED_SEVEN.name;
        Path pilot = Files.write(dir.resolve("pilot.txt"), WideReportTest.pilot(WideReportTest.ratios("1-8 same schedule",
                red, 1.04, "flat", red, 1.60, "P16 same schedule", red, 1.30)).getBytes(StandardCharsets.UTF_8));
        Path json = dir.resolve("spreads.json");
        WideReport.run(ser, pilot, "wide-fixture", dir.resolve("report.md"), json);
        return json;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void theWidePlansLandAsSpreadsAndTheWongingGrid() throws Exception {
        Path json = spreads(dir);
        String red = FullIndexFitTest.RED_SEVEN.name;
        SiteInput in = SiteWide.adapt(json, "runs/site/spreads-fixture.json");
        Map<String, Object> d = SiteAdapt.read(json);

        // The file's own figures, read here by the row's name, penetration and plan.
        String hiLoFull = FullIndexFitTest.HI_LO.name + ", full play";
        Map<String, Object> p16 = same(d, hiLoFull, "80%", "P16");
        Map<String, Object> f = (Map<String, Object>) in.figures.get("full.80.s16.hi_lo");
        Map<String, Object> ev = (Map<String, Object>) p16.get("ev");
        assertEquals(100 * ((Number) ev.get("est")).doubleValue(), f.get("value"));
        assertEquals(List.of(100 * ((Number) ev.get("lo")).doubleValue(), 100 * ((Number) ev.get("hi")).doubleValue()),
                f.get("ci"));
        assertEquals(((Number) p16.get("widen")).doubleValue(), f.get("widen"));
        assertTrue(((Number) p16.get("widen")).doubleValue() > 1, "full play is widened");
        Map<String, Object> red12 = same(d, red, "70%", "P12");
        assertEquals(100 * ((Number) ((Map<String, Object>) red12.get("ev")).get("est")).doubleValue(),
                ((Map<String, Object>) in.figures.get("fd.70.s12.red_seven")).get("value"));
        assertNotNull(in.figures.get("fd.80.s20.red_seven"));
        assertNull(in.figures.get("fd.70.s20.red_seven"), "1-20 only at 80%");
        assertNotNull(in.figures.get("fd.80.s16_steep.hi_lo"));

        // Wonging: B0,8 as fullw s8, the cell [EV, SE, lo, hi] in percent.
        Map<String, Object> b08 = (Map<String, Object>) same(d, hiLoFull, "80%", "B0,8").get("ev");
        List<Object> cell = (List<Object>) ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) in.grid
                .get("hi_lo")).get("fullw")).get("80")).get("s8");
        assertEquals(List.of(100 * ((Number) b08.get("est")).doubleValue(), 100 * ((Number) b08.get("se")).doubleValue(),
                100 * ((Number) b08.get("lo")).doubleValue(), 100 * ((Number) b08.get("hi")).doubleValue()), cell);
        assertEquals(List.of("fullw"), in.grid.get("_widened"));
        assertNotNull(((Map<String, Object>) in.grid.get("red_seven")).get("fdw"));
        assertNull(((Map<String, Object>) in.grid.get("red_seven")).get("bet"), "betting only has no wonging scope");

        // The same schedule's bet and share, the same for every row.
        assertEquals(((Number) same(d, red, "80%", "B0,8").get("share")).doubleValue(),
                ((Map<String, Object>) in.figures.get("wide.share.80.b0_8")).get("value"));
        assertEquals(((Number) same(d, red, "80%", "P16").get("bet")).doubleValue(),
                ((Map<String, Object>) in.figures.get("wide.bet.80.p16")).get("value"));
        assertEquals(SystemScorer.WIDE_PLANS.length + 0.0, ((Map<String, Object>) in.figures.get("wide.plans")).get("value"));

        // Hi-Lo's key at the entry threshold of 0.
        Map<String, Object> keys = (Map<String, Object>) ((Map<String, Object>) row(d, FullIndexFitTest.HI_LO.name)
                .get("thresholdKeys")).get("80%");
        Object k0 = keys.get("0.0");
        if (k0 != null) {
            assertEquals(((Number) k0).doubleValue(), ((Map<String, Object>) in.figures.get("wide.threshold.80.0.hi_lo"))
                    .get("value"));
        }
        SiteData sd = new SiteData();
        sd.add("spreads-fixture.json", in.text("spreads-fixture.json").getBytes(StandardCharsets.UTF_8));
        assertTrue(sd.resolve("fullw.80.s8.hi_lo").widened);
    }

    /** Two rows placing different bets under one same-schedule plan are refused: the schedule gives every row the same. */
    @Test
    public void unequalBetsUnderOneScheduleAreRefused() throws Exception {
        List<WideReport.Row> rows = WideReportTest.scored(FullIndexFitTest.capped(), 3);
        Path ser = dir.resolve("wide.ser");
        WideReport.save(ser, "test scoring\ncommit test", SystemScorer.WIDE_PLANS, rows);
        Path json = dir.resolve("spreads.json");
        WideReport.run(ser, null, "wide-fixture", dir.resolve("report.md"), json);
        String text = new String(Files.readAllBytes(json), StandardCharsets.UTF_8);
        SiteWide.adapt(json, "ok");
        int at = text.indexOf("\"bet\":");
        int end = text.indexOf(',', at);
        double was = Double.parseDouble(text.substring(at + 6, end));
        Path bad = Files.write(dir.resolve("bad.json"), (text.substring(0, at + 6) + (was * (1 + 1e-9))
                + text.substring(end)).getBytes(StandardCharsets.UTF_8));
        IllegalStateException x = assertThrows(IllegalStateException.class, () -> SiteWide.adapt(bad, "bad"));
        assertTrue(x.getMessage().contains("the same schedule gives every row the same"), x.getMessage());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> row(Map<String, Object> d, String name) {
        for (Object o : (List<Object>) d.get("rows")) {
            if (name.equals(((Map<String, Object>) o).get("name"))) {
                return (Map<String, Object>) o;
            }
        }
        throw new AssertionError("no row " + name);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> same(Map<String, Object> d, String name, String pen, String plan) {
        Map<String, Object> pens = (Map<String, Object>) row(d, name).get("pens");
        return (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) pens.get(pen)).get(plan)).get("same");
    }
}
