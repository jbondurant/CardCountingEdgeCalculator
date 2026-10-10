import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WideReport on the fixture's states: wide.ser written and read back whole; the report and the data
 * file written only when the checks pass, and then holding the scorer's own figures; differences
 * from Hi-Lo paired resample by resample; full-play intervals widened by the rule its class comment
 * gives, from a pilot's printed tables, and not given at all without a pilot.
 */
public class WideReportTest {

    // ------------------------------------------------------------------ a small JSON reader

    /** Reads JSON into maps, lists, doubles, booleans, strings and null; anything else is refused. */
    static final class Json {
        private final String s;
        private int at;

        Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            Json j = new Json(s);
            Object v = j.value();
            j.space();
            assertEquals(s.length(), j.at, "nothing after the value");
            return v;
        }

        private void space() {
            while (at < s.length() && Character.isWhitespace(s.charAt(at))) {
                at++;
            }
        }

        private Object value() {
            space();
            char c = s.charAt(at);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>();
                at++;
                space();
                if (s.charAt(at) == '}') {
                    at++;
                    return m;
                }
                do {
                    space();
                    String k = (String) value();
                    space();
                    assertEquals(':', s.charAt(at++));
                    assertNull(m.put(k, value()), "key " + k + " twice");
                    space();
                } while (s.charAt(at++) == ',');
                assertEquals('}', s.charAt(at - 1));
                return m;
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>();
                at++;
                space();
                if (s.charAt(at) == ']') {
                    at++;
                    return l;
                }
                do {
                    l.add(value());
                    space();
                } while (s.charAt(at++) == ',');
                assertEquals(']', s.charAt(at - 1));
                return l;
            }
            if (c == '"') {
                StringBuilder b = new StringBuilder();
                at++;
                while (s.charAt(at) != '"') {
                    char x = s.charAt(at++);
                    if (x == '\\') {
                        char e = s.charAt(at++);
                        if (e == 'u') {
                            b.append((char) Integer.parseInt(s.substring(at, at + 4), 16));
                            at += 4;
                        } else {
                            b.append(e);
                        }
                    } else {
                        b.append(x);
                    }
                }
                at++;
                return b.toString();
            }
            for (String word : new String[]{"true", "false", "null"}) {
                if (s.startsWith(word, at)) {
                    at += word.length();
                    return word.equals("null") ? null : Boolean.valueOf(word);
                }
            }
            int from = at;
            while (at < s.length() && "+-0123456789.eE".indexOf(s.charAt(at)) >= 0) {
                at++;
            }
            return Double.parseDouble(s.substring(from, at));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o, String key) {
        return (Map<String, Object>) ((Map<String, Object>) o).get(key);
    }

    // ------------------------------------------------------------------ the fixture's scoring

    /** The fixture's Hi-Lo, Red Seven and the null count scored with the plans on, full play from capped fits, and the other rows. */
    static List<WideReport.Row> scored(FullIndexFitTest.Run run, int resamples) throws Exception {
        SystemScorer sc = WidePlansTest.scorer(run.fx, SystemScorer.WIDE_PLANS, resamples);
        sc.ownValueRows = false;
        List<SystemScorer.Result> results = new ArrayList<>();
        for (CountKeys.Count c : Arrays.asList(FullIndexFitTest.NULL, FullIndexFitTest.HI_LO)) {
            results.add(sc.score(c, null, run.fits.get(c)));
        }
        CountKeys.Count red = FullIndexFitTest.RED_SEVEN;
        results.add(sc.score(red, CountKeys.bettingVariant(red)));
        List<SystemScorer.Entry> others = new ArrayList<>(sc.bounds(null));
        others.addAll(sc.references());
        return WideReport.rows(results, others);
    }

    static String text(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** A pilot's Results section as printSes prints it, with these difference ratios: [metric][count] = ratio. */
    static String pilot(Map<String, Map<String, Double>> ratios) {
        StringBuilder b = new StringBuilder("# Full-index pilot\n\n## Results\n");
        for (Map.Entry<String, Map<String, Double>> m : ratios.entrySet()) {
            b.append("\nDifference from Hi-Lo at 80%, ").append(m.getKey())
                    .append(", over 100 resamples (SEs in % of a unit per round):\n\n")
                    .append("| Count | Estimate, full play | SE, first decisions only | SE, later plays held | "
                            + "SE, later plays refitted | Refitted / held | its jackknife SE |\n|---|---|---|---|---|---|---|\n");
            for (Map.Entry<String, Double> c : m.getValue().entrySet()) {
                b.append(String.format(java.util.Locale.ROOT, "| %s | +0.0100%% | 0.0300%% | 0.0280%% | 0.0300%% | %.2f | 0.02 |%n",
                        c.getKey(), c.getValue()));
            }
            b.append("\nGain from full play at 80%, ").append(m.getKey())
                    .append(": full play less first decisions only, paired:\n\n| Count | Estimate | SE, later plays held | "
                            + "SE, later plays refitted | Refitted / held | its jackknife SE |\n|---|---|---|---|---|---|\n")
                    .append("| Hi-Lo = Hi-Lo Lite | +0.0700% | 0.0100% | 0.0140% | 1.40 | 0.10 |\n");
        }
        return b.append("\n## The full run, projected\n").toString();
    }

    static Map<String, Map<String, Double>> ratios(Object... metricCountRatio) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (int k = 0; k < metricCountRatio.length; k += 3) {
            out.computeIfAbsent((String) metricCountRatio[k], x -> new LinkedHashMap<>()).put((String) metricCountRatio[k + 1],
                    (Double) metricCountRatio[k + 2]);
        }
        return out;
    }

    static WideReport.Row row(List<WideReport.Row> rows, String name) {
        for (WideReport.Row r : rows) {
            if (r.name.equals(name)) {
                return r;
            }
        }
        throw new AssertionError("no row " + name + " among " + rows.stream().map(r -> r.name).toArray().length);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> rowJson(Object doc, String name) {
        for (Object r : (List<Object>) ((Map<String, Object>) doc).get("rows")) {
            if (((Map<String, Object>) r).get("name").equals(name)) {
                return (Map<String, Object>) r;
            }
        }
        throw new AssertionError("no row " + name + " in the data file");
    }

    static Map<String, Object> figure(Object doc, String row, String pen, String plan, String rule) {
        return obj(obj(obj(rowJson(doc, row), "pens"), pen).get(plan), rule);
    }

    /**
     * wide.ser written and read back holds every row's figures to the bit; the report and the data
     * file hold the scorer's figures, its differences from Hi-Lo and the inputs' SHA-256; and a
     * full-play interval is widened by the ratio the rule picks: the plan's largest for a count the
     * pilot did not measure under it, the largest of any plan for a plan it did not measure.
     */
    @Test
    public void theReportHoldsTheScorersFiguresWidenedByThePilotsRule(@TempDir Path dir) throws Exception {
        List<WideReport.Row> rows = scored(FullIndexFitTest.capped(), 6);
        Path ser = dir.resolve("wide.ser");
        WideReport.save(ser, "test scoring\ncommit test", SystemScorer.WIDE_PLANS, rows);
        WideReport.Saved back = WideReport.read(ser);
        assertEquals(rows.size(), back.rows.size());
        assertEquals(SystemScorer.labels(SystemScorer.WIDE_PLANS), SystemScorer.labels(back.plans));
        for (int r = 0; r < rows.size(); r++) {
            assertEquals(rows.get(r).name, back.rows.get(r).name);
            for (int p = 0; p < SystemScorer.CUTS.length; p++) {
                WidePlansTest.arrays(rows.get(r).wide[p], back.rows.get(r).wide[p], rows.get(r).name);
            }
        }
        Path pilot = dir.resolve("pilot.txt");
        String red = FullIndexFitTest.RED_SEVEN.name;
        Files.write(pilot, pilot(ratios("1-8 same schedule", red, 1.04, "flat", red, 1.60, "P16 same schedule", red, 1.30,
                "P16 same schedule", "KO (Knock-Out)", 1.20)).getBytes(StandardCharsets.UTF_8));
        Path md = dir.resolve("report.md");
        Path json = dir.resolve("spreads.json");
        WideReport.run(ser, pilot, "abc1234", md, json);
        Object doc = Json.parse(text(json));
        assertEquals("abc1234", ((Map<?, ?>) doc).get("commit"));
        assertEquals(WideReport.sha256(ser), obj(doc, "inputs").get(ser.toString()));
        assertEquals(WideReport.sha256(pilot), obj(doc, "inputs").get(pilot.toString()));

        int p16 = SystemScorer.planIndex(SystemScorer.WIDE_PLANS, "P16");
        int at = SystemScorer.wideAt(p16, SystemScorer.W_SAME);
        WideReport.Row hiLo = row(rows, FullIndexFitTest.HI_LO.name);
        WideReport.Row rs = row(rows, red);
        Map<String, Object> f = figure(doc, red, "80%", "P16", "same");
        assertEquals(rs.wide[2][at], (Double) obj(f, "ev").get("est"), 0.0);
        assertEquals(rs.wide[2][at] - hiLo.wide[2][at], (Double) obj(f, "diff").get("est"), 0.0);
        assertEquals(rs.wide[2][at + 1], (Double) f.get("bet"), 0.0);
        assertEquals(rs.wide[2][at] / rs.wide[2][at + 1], (Double) f.get("evPerUnit"), 0.0);
        assertEquals(1.0, (Double) f.get("widen"), 0.0);

        // Hi-Lo with full play: P16 measured, but not for Hi-Lo, so the plan's largest; S16 not measured at all.
        String full = FullIndexFitTest.HI_LO.name + ", full play";
        WideReport.Row hf = row(rows, full);
        int[] kept = SystemScorer.wideRows(SystemScorer.WIDE_PLANS);
        int k16 = -1;
        for (int k = 0; k < kept.length; k++) {
            k16 = kept[k] == at ? k : k16;
        }
        double held = Bootstrap.interval(hf.wide[2][at], hf.wideBoot[2][k16]).se;
        Map<String, Object> g = figure(doc, full, "80%", "P16", "same");
        assertEquals(1.30, (Double) g.get("widen"), 1e-12);
        assertEquals(WideReport.Basis.PLAN_LARGEST.words, g.get("widenBasis"));
        assertEquals(1.30 * held, (Double) obj(g, "ev").get("se"), 1e-12 * held);
        Map<String, Object> s16 = figure(doc, full, "80%", "S16", "same");
        assertEquals(1.60, (Double) s16.get("widen"), 1e-12);
        assertEquals(WideReport.Basis.BORROWED.words, s16.get("widenBasis"));
        String report = text(md);
        assertTrue(report.contains("### P16, same schedule, 80%"), "the P16 table");
        assertTrue(report.contains("| " + full + " | "), "the full-play row");
        assertTrue(report.contains(" w1.30p"), "a widened interval marked with its ratio and basis");
        assertFalse(report.contains("NaN"), "no figure printed as NaN");

        // A pilot without the wide plans: every plan takes the 1-8 ratios, labelled an extrapolation.
        Path old = dir.resolve("old-pilot.txt");
        Files.write(old, pilot(ratios("1-8 same schedule", red, 1.04, "flat", red, 1.60)).getBytes(StandardCharsets.UTF_8));
        WideReport.run(ser, old, "abc1234", dir.resolve("old.md"), dir.resolve("old.json"));
        Map<String, Object> x = figure(Json.parse(text(dir.resolve("old.json"))), full, "80%", "P16", "same");
        assertEquals(1.60, (Double) x.get("widen"), 1e-12);
        assertEquals(WideReport.Basis.EXTRAPOLATED.words, x.get("widenBasis"));

        // No pilot: no full-play interval at all, the first decisions' as before.
        WideReport.run(ser, null, "abc1234", dir.resolve("none.md"), dir.resolve("none.json"));
        Object none = Json.parse(text(dir.resolve("none.json")));
        assertNull(figure(none, full, "80%", "P16", "same").get("ev"));
        assertNull(figure(none, full, "80%", "P16", "same").get("diff"));
        assertEquals(obj(f, "ev").get("se"), obj(figure(none, red, "80%", "P16", "same"), "ev").get("se"));
        assertTrue(text(dir.resolve("none.md")).contains("not widened: no pilot"));
    }

    /**
     * A failed check writes nothing: P8 off the 1-8 figure by one bit, or one row's same-schedule
     * average bet off the others', and neither file is made; a file already there is never written over.
     */
    @Test
    public void aFailedCheckWritesNothing(@TempDir Path dir) throws Exception {
        List<WideReport.Row> rows = scored(FullIndexFitTest.capped(), 3);
        int p8 = SystemScorer.planIndex(SystemScorer.WIDE_PLANS, "P8");
        int p16 = SystemScorer.planIndex(SystemScorer.WIDE_PLANS, "P16");
        WideReport.Saved s = new WideReport.Saved();
        s.identity = "test";
        s.plans = SystemScorer.WIDE_PLANS;
        s.rows = rows;
        assertEquals(new ArrayList<String>(), WideReport.check(s));
        double[] w = rows.get(1).wide[2];
        int at = SystemScorer.wideAt(p8, SystemScorer.W_SAME);
        w[at] = Math.nextUp(w[at]);
        assertEquals(1, WideReport.check(s).size(), WideReport.check(s).toString());
        Path ser = dir.resolve("wide.ser");
        WideReport.save(ser, "test", SystemScorer.WIDE_PLANS, rows);
        Path md = dir.resolve("r.md");
        Path json = dir.resolve("r.json");
        assertThrows(IllegalStateException.class, () -> WideReport.run(ser, null, "abc", md, json));
        assertFalse(Files.exists(md));
        assertFalse(Files.exists(json));
        w[at] = Math.nextDown(w[at]);
        int bet = SystemScorer.wideAt(p16, SystemScorer.W_SAME_BET);
        w[bet] *= 1 + 1e-9;
        assertEquals(1, WideReport.check(s).size(), WideReport.check(s).toString());
        w[bet] /= 1 + 1e-9;
        WideReport.save(ser, "test", SystemScorer.WIDE_PLANS, rows);
        Files.write(md, new byte[0]);
        assertThrows(IllegalStateException.class, () -> WideReport.run(ser, null, "abc", md, json));
        assertFalse(Files.exists(json));
    }
}
