import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SiteSim on summaries the clean-room simulator's own writer wrote (sim.Summary.write), with
 * made-up sums' results: every z is worked out again, each comparison is matched to SIM_CHECK.md's
 * registered figure, the counts of tests, passes, flags and failures follow the rule, a row past
 * it stays in the table, registered figures not compared are listed, and a file whose z or pass
 * disagrees with its own figures, a comparison the simulator names as another registered figure,
 * a run with a planted bug on, or a P2 of another bundle is refused.
 */
public class SiteSimTest {

    @TempDir
    Path dir;

    static Map<String, String> facts(String check, String plants) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("check", check);
        f.put("simulatorCommit", "simfixture");
        f.put("bundleSha256", "b0b0");
        f.put("seed", check.equals("P3") ? "20261008" : "20261009");
        if (check.equals("P3")) {
            f.put("shoes", "2000000");
        }
        f.put("plants", plants);
        return f;
    }

    /**
     * A comparison whose z is the one given: exact 0, simulated z x SE, SEs 0.001 and 0 (no exact
     * side's SE), named as the simulator names it, by its own reading of SIM_CHECK.md's registry.
     */
    static sim.Summary.Comparison c(String check, String row, int pen, String figure, double z, double rounds) {
        sim.Registry.Fig f;
        try {
            f = sim.Registry.read(Path.of("SIM_CHECK.md")).of(row, pen, figure);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return new sim.Summary.Comparison(check, row, pen, figure, 0, Double.NaN, z * 0.001, 0.001, rounds, 2_000_000, 0,
                f == null ? "" : f.id());
    }

    /** The registry's figure of these words, found here by its fields. */
    static String id(String count, SimCheckPlan.Play play, int pen, String bets) {
        for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
            if (f.count.equals(count) && f.play == play && f.pen == pen && f.bets.equals(bets)) {
                return f.id;
            }
        }
        throw new AssertionError("no registered " + count + " " + play + " " + pen + " " + bets);
    }

    Path[] write(String plants) throws Exception {
        return summaries(dir, plants);
    }

    /** The P3 and P2 summaries the simulator's own writer writes, in dir: z scores known, planted bugs as given. */
    static Path[] summaries(Path dir, String plants) throws Exception {
        List<sim.Summary.Comparison> p3 = new ArrayList<>();
        p3.add(c("P3", "Chart, no count, flat", 70, "flat", 0.4, 106_000_000));
        p3.add(c("P3", "Chart, no count, flat", 80, "flat", -1.1, 121_000_000));
        p3.add(c("P3", "Hi-Lo = Hi-Lo Lite", 80, "ev:same-8", 2.7, 121_000_000));
        p3.add(c("P3", "Hi-Lo = Hi-Lo Lite", 80, "ev:own-8", 0.2, 121_000_000));
        p3.add(c("P3", "Victor Advanced Point Count (Victor APC) + ace side count, full play", 80, "ev:X-1.0,8", -4.0,
                121_000_000));
        p3.add(c("P3", "Zen Count", 70, "flat", 1.0, 106_000_000));
        List<sim.Summary.Comparison> p2 = new ArrayList<>();
        p2.add(c("P2", "Chart, no count, flat", 80, "flat", 0.3, 60_743));
        Path a = dir.resolve("summary-p3.json");
        Path b = dir.resolve("summary-p2.json");
        sim.Summary.write(a, facts("P3", plants), p3);
        sim.Summary.write(b, facts("P2", plants), p2);
        return new Path[]{a, b};
    }

    static double value(SiteInput in, String key) {
        return (Double) ((Map<?, ?>) in.figures.get(key)).get("value");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void everyComparisonIsMatchedAndCountedByTheRule() throws Exception {
        Path[] f = write("a=off b=off");
        SiteInput in = SiteSim.adapt(f[0], f[1]);
        assertEquals(7.0, value(in, "sim.tests"));
        assertEquals(6.0, value(in, "sim.passed"));
        assertEquals(1.0, value(in, "sim.flagged"));
        assertEquals(1.0, value(in, "sim.failed"));
        assertEquals(4.0, value(in, "sim.z_max"), 1e-12);
        assertEquals(121_000_000.0, value(in, "sim.rounds"));
        assertEquals(2_000_000.0, value(in, "sim.shoes"));
        List<Object> table = (List<Object>) in.tables.get("sim_z");
        assertEquals(7, table.size(), "every comparison stays in the table, the failed one too");
        Map<String, Object> victor = (Map<String, Object>) table.get(4);
        assertEquals(id("Victor APC + ace", SimCheckPlan.Play.FULL, 80, "X-1,8"), victor.get("figure"));
        assertEquals("full.80.xm1_0_8.victor_advanced_point_count_ace", victor.get("key"));
        assertEquals("fails", victor.get("verdict"));
        Map<String, Object> hiLo = (Map<String, Object>) table.get(2);
        assertEquals(id("Hi-Lo", SimCheckPlan.Play.FIRST, 80, "1-8"), hiLo.get("figure"));
        assertEquals("fd.80.s8.hi_lo", hiLo.get("key"));
        assertEquals("flagged", hiLo.get("verdict"));
        assertEquals(id("Hi-Lo", SimCheckPlan.Play.FIRST, 80, "own 1-8"), ((Map<String, Object>) table.get(3)).get("figure"));
        assertNull(((Map<String, Object>) table.get(5)).get("figure"), "Zen at first decisions flat is not registered");
        assertEquals(id("chart", SimCheckPlan.Play.CHART, 80, "flat"), ((Map<String, Object>) table.get(6)).get("figure"));
        int registered = 0;
        for (SimCheckPlan.Figure x : SimCheckPlan.figures()) {
            registered += x.pen == 80 ? 2 : 1;
        }
        assertEquals(6.0, value(in, "sim.registered"));
        assertEquals(registered - 6.0, value(in, "sim.missing"));
        assertTrue(((List<Object>) in.tables.get("sim_missing")).contains("P2 " + id("Hi-Lo", SimCheckPlan.Play.FIRST, 80,
                "1-8")));
        new SiteData().add("sim.json", in.text("sim.json").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void aZOrPassThatDisagreesAPlantOrAnotherBundleIsRefused() throws Exception {
        Path[] f = write("a=off b=off");
        String text = new String(Files.readAllBytes(f[0]), StandardCharsets.UTF_8);
        Path z = Files.write(dir.resolve("z.json"), text.replaceFirst("\"z\": 0\\.4", "\"z\": 0.5").getBytes(StandardCharsets.UTF_8));
        assertNotEquals(text, new String(Files.readAllBytes(z), StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> SiteSim.adapt(z, null));
        Path pass = Files.write(dir.resolve("pass.json"), text.replaceFirst("\"pass\": false", "\"pass\": true")
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> SiteSim.adapt(pass, null));
        Path named = Files.write(dir.resolve("named.json"), text.replaceFirst("\"fig\": \"F18\"", "\"fig\": \"F19\"")
                .getBytes(StandardCharsets.UTF_8));
        assertNotEquals(text, new String(Files.readAllBytes(named), StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> SiteSim.adapt(named, null),
                "a comparison the simulator names as another registered figure");
        Path[] planted = write("a=on b=off");
        assertThrows(IllegalArgumentException.class, () -> SiteSim.adapt(planted[0], null));
        write("a=off b=off");
        String p2 = new String(Files.readAllBytes(f[1]), StandardCharsets.UTF_8);
        Path other = Files.write(dir.resolve("other.json"), p2.replace("b0b0", "c1c1").getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> SiteSim.adapt(f[0], other));
    }
}
