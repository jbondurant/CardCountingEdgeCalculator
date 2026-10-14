package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Check D5: lookups.tsv's cells against the simulator's reading of the strategy files. The cells
 * the test asserts are worked by hand from B2.2 to B2.4 on fixture files: Hi-Lo with groups at
 * keys up to 0 and above 0, moves and insurance that differ by group, and the stand-in chart.
 */
class LookupsTest {

    /** Three records: a full shoe, ten black twos dealt, and 300 cards dealt (past the 70% cut). */
    static States states() throws IOException {
        StatesFixture f = new StatesFixture(0);
        f.shoes = 3;
        for (int d : new int[]{0, 10, 300}) {
            StatesFixture.Rec r = new StatesFixture.Rec();
            r.shoe = f.records.size();
            r.fold = r.shoe & 1;
            r.depth = d;
            r.q = 0.5;
            r.dealt = new int[Shoe.KINDS];
            if (d == 10) {
                r.dealt[2] = 10;
            } else if (d == 300) {
                for (int k = 0; k < Shoe.KINDS; k++) {
                    r.dealt[k] = k < 14 ? 12 : 11;
                }
            }
            f.records.add(r);
        }
        return f.read();
    }

    static Map<String, Strategy> files() throws IOException {
        Map<String, Strategy> m = new LinkedHashMap<>();
        StrategyFixture a = new StrategyFixture();
        a.first = (g, p1, p2, up) -> g == 0 ? "H\tS" : "S\tH";
        a.insured.add("0 1 10");
        a.insured.add("1 10 10");
        m.put("strategy-a-p80-f1.txt", a.read());
        StrategyFixture b = new StrategyFixture();
        b.scope = "full";
        b.later = (l, total, soft, up) -> l == 0 ? "H" : "S";
        m.put("strategy-b-p80-f1.txt", b.read());
        StrategyFixture c = new StrategyFixture();
        c.scope = "betting";
        m.put("strategy-c-p80-f1.txt", c.read());
        StrategyFixture d = new StrategyFixture();
        d.pen = 70;
        d.cut = 291;
        m.put("strategy-d-p70-f1.txt", d.read());
        List<String> red7 = new ArrayList<>(new StrategyFixture().lines());
        String tags = "\t-1\t-1\t1\t1\t1\t1\t1\t1\t1\t1\t1\t1\t0\t1\t0\t0\t0\t0\t-1\t-1\t-1\t-1\t-1\t-1\t-1\t-1";
        red7.replaceAll(l -> l.startsWith("tags\t") ? "tags" + tags : l.startsWith("bettags\t") ? "bettags" + tags
                : l.startsWith("decksum\t") ? "decksum\t2" : l.startsWith("betdecksum\t") ? "betdecksum\t2"
                : l.equals("mode\ttrue") ? "mode\trunning" : l);
        m.put("strategy-e-p80-f1.txt", Strategy.parse(red7, "red7", Plants.NONE));
        return m;
    }

    static String row(String kind, int state, int p1, int p2, int up, String hit, Map<String, Strategy> files, States s) {
        StringBuilder b = new StringBuilder(kind + "\t" + state + "\t" + p1 + "\t" + p2 + "\t" + up + "\t" + hit);
        for (Strategy st : files.values()) {
            int[] dealt = Lookups.dealt(s, state);
            if (kind.equals("first")) {
                var m = Lookups.first(st, Chart.textbook(), dealt, s.depth[state], p1, p2, up);
                StringBuilder cell = new StringBuilder();
                m.forEach((k, v) -> cell.append(cell.length() == 0 ? "" : ";").append(k).append(':').append(v));
                b.append('\t').append(m.isEmpty() ? "-" : cell);
            } else {
                b.append('\t').append(Lookups.later(st, Chart.textbook(), dealt, s.depth[state], p1, p2, up, Integer.parseInt(hit)));
            }
        }
        return b.toString();
    }

    static String cell(String line, int column) {
        return line.split("\t", -1)[6 + column];
    }

    @Test
    void theCellsAreB24sWorkedByHand() throws IOException {
        States s = states();
        Map<String, Strategy> f = files();
        // a full shoe, (10, 10) against an ace: Hi-Lo key round(−156 / 413) = 0, group 0, Hit, not insured
        assertEquals("0:0:H:0", cell(row("first", 0, 10, 10, 1, "-", f, s), 0));
        // ten twos dealt: key round(52 · 7 / 403) = 1, group 1, Stand, insured; the chart stands on 20
        assertEquals("1:1:S:1", cell(row("first", 1, 10, 10, 1, "-", f, s), 0));
        assertEquals("1:1:S:0", cell(row("first", 1, 10, 10, 1, "-", f, s), 2));
        // the natural has no move and is insured like any ace-up deal
        assertEquals("0:0:-:1", cell(row("first", 0, 1, 10, 1, "-", f, s), 0));
        assertEquals("2:1:S:-", cell(row("first", 1, 2, 3, 4, "-", f, s), 0));
        // past the 70% cut the pen-70 file has no answer
        assertEquals("-", cell(row("first", 2, 2, 3, 4, "-", f, s), 3));
        // Red Seven, a full shoe, (7, 7) against a 2: running keys 1, 2 and 3, all in group 1
        assertEquals("1:1:S:-;2:1:S:-;3:1:S:-", cell(row("first", 0, 7, 7, 2, "-", f, s), 4));
        // later, full play, ten twos dealt, (2, 3) against a 4:
        // a ten makes hard 15 (cell 36), key round(52 · 12 / 402) = 2, later group 1, Stand
        assertEquals("2:1:36:S", cell(row("later", 1, 2, 3, 4, "10", f, s), 1));
        assertEquals("2:1:41:S", cell(row("later", 1, 2, 3, 4, "1", f, s), 1), "an ace makes soft 16");
        assertEquals("2:1:-:H", cell(row("later", 1, 2, 3, 4, "3", f, s), 1), "hard 8: the chart hits");
        // the hit card is counted: a full shoe, (2, 3) against a 4 and a 5 hit, four cards seen,
        // round(52 · 4 / 412) = 1 and later group 1 (with three cards seen it would be 0, group 0)
        assertEquals("1:1:-:H", cell(row("later", 0, 2, 3, 4, "5", f, s), 1));
        assertEquals("1:1:-:-", cell(row("later", 1, 10, 10, 5, "2", f, s), 1), "22 busts");
        assertEquals("1:1:-:S", cell(row("later", 1, 10, 10, 5, "1", f, s), 1), "21 stands");
        assertEquals("-", cell(row("later", 1, 2, 3, 4, "10", f, s), 0), "not a full-play file");
        assertEquals("-", cell(row("later", 0, 2, 3, 4, "10", f, s), 4), "a colour count");
    }

    @Test
    void everyCellIsComparedAndADifferenceIsNamed() throws IOException {
        States s = states();
        Map<String, Strategy> f = files();
        List<String> paths = new ArrayList<>(f.keySet());
        List<String> text = new ArrayList<>(List.of("# lookups.tsv: SHOE_SPEC.md B8, format 1", "# sample seed 20261009",
                Lookups.HEADER + "\t" + String.join("\t", paths)));
        for (int st = 0; st < 3; st++) {
            for (int[] d : new int[][]{{7, 7, 2}, {2, 3, 4}, {1, 10, 1}, {10, 10, 1}, {8, 8, 10}}) {
                text.add(row("first", st, d[0], d[1], d[2], "-", f, s));
            }
        }
        for (int hit = 1; hit <= 10; hit++) {
            text.add(row("later", 1, 2, 3, 4, Integer.toString(hit), f, s));
        }
        Lookups.Result clean = Lookups.check(text, paths, f::get, Chart.textbook(), s);
        assertTrue(clean.passes(), clean.first().toString());
        assertEquals(25, clean.lines());
        assertEquals(125, clean.cells());
        // the colour count's parts in another order still agree
        List<String> reordered = new ArrayList<>(text);
        reordered.set(3, reordered.get(3).replace("1:1:S:-;2:1:S:-;3:1:S:-", "3:1:S:-;1:1:S:-;2:1:S:-"));
        assertTrue(Lookups.check(reordered, paths, f::get, Chart.textbook(), s).passes());
        // a first move, an insurance decision and a later move changed: each is named
        List<String> planted = new ArrayList<>(text);
        planted.set(6, planted.get(6).replaceFirst("\t0:0:H:0\t", "\t0:0:S:0\t"));
        planted.set(11, planted.get(11).replaceFirst("\t1:1:S:1\t", "\t1:1:S:0\t"));
        planted.set(text.size() - 1, planted.get(text.size() - 1).replaceFirst(":36:S", ":36:H"));
        Lookups.Result bad = Lookups.check(planted, paths, f::get, Chart.textbook(), s);
        assertEquals(3, bad.mismatches(), bad.first().toString());
        // a header whose columns are not the files in path order is refused
        List<String> header = new ArrayList<>(text);
        header.set(2, Lookups.HEADER + "\t" + String.join("\t", paths.subList(1, paths.size())));
        assertThrows(IOException.class, () -> Lookups.check(header, paths, f::get, Chart.textbook(), s));
    }
}
