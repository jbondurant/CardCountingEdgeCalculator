package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * keys up to 0 and above 0, moves and insurance that differ by group, split lines in the full-play
 * file, and the stand-in chart.
 *
 * <p>Planted on 19 October 2026 in a scratch copy of sim/ outside the worktree, one at a time;
 * each failed the test named. Split cells answered from the first lines alone, by Lookups' own
 * loop (theSplitCellsAreB24sWorkedByHand, splitHandsThatReadTheFirstLineAreCaught) or through
 * Strategy's method (those two and everyCellIsComparedAndADifferenceIsNamed); Split legal in both
 * parts of a pair's cell (the same three); a part after ':' for split aces too
 * (theSplitCellsAreB24sWorkedByHand); and, in theThousandSplitLinesAreThereInOrderOrRefused, each
 * of these left out in turn: the count of 1,000, the order of r, x and up, the refusal of split
 * lines with no full-play file, the refusal of a first or later line after them, and the failure
 * when a full-play file is present and no split cell was compared. The split cells not counted
 * apart failed everyCellIsComparedAndADifferenceIsNamed. After the clean-room review, also: the
 * part after ':' worked with Split alone legal, or without Hit (theSplitCellsAreB24sWorkedByHand
 * and splitHandsThatReadTheFirstLineAreCaught), or without Double (theSplitCellsAreB24sWorkedByHand);
 * a split line's state and hit left unchecked, together and each alone
 * (theThousandSplitLinesAreThereInOrderOrRefused); every column of a split line counted as a split
 * cell, the other files' columns not counted, and a mismatch there counted as a split cell's
 * (everyCellIsComparedAndADifferenceIsNamed); and D5 passed when only other files' columns of
 * split lines were compared (theThousandSplitLinesAreThereInOrderOrRefused).
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
        m.put("strategy-b-p80-f1.txt", full().read());
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

    /**
     * The full-play file: pairs split first and other deals hit first, in both groups, but (5, 5)
     * against a 6, which doubles first. Its split lines, all against a 6: group 1's (10, 10) stands
     * first (B2.4's example, in group 1 of 2), its (4, 4) hits first, and its (6, 6) splits first as
     * its first line does and then hits, not stands (the two lists start with the same move, as in
     * SHOE_SPEC.md B6 item 12's pair of threes against an ace); group 0's (3, 8) and (1, 5) double
     * first.
     */
    static StrategyFixture full() {
        StrategyFixture b = new StrategyFixture();
        b.scope = "full";
        b.later = (l, total, soft, up) -> l == 0 ? "H" : "S";
        b.first = (g, p1, p2, up) -> p1 == 5 && p2 == 5 && up == 6 ? "D\tP\tS\tH"
                : p1 == p2 ? "P\tS\tH\tD" : "H\tS\tD";
        b.split = (g, p1, p2, up) -> up != 6 ? null : g == 1 && p1 == 10 && p2 == 10 ? "S\tP\tH\tD"
                : g == 1 && p1 == 4 && p2 == 4 ? "H\tP\tS\tD" : g == 1 && p1 == 6 && p2 == 6 ? "P\tH\tS\tD"
                : g == 0 && (p1 == 3 && p2 == 8 || p1 == 1 && p2 == 5) ? "D\tH\tS" : null;
        return b;
    }

    /** A split line, as B8 writes it: a split r that has drawn x against up, state and hit "-". */
    static String splitRow(int r, int x, int up, Map<String, Strategy> files) {
        StringBuilder b = new StringBuilder("split\t-\t" + r + "\t" + x + "\t" + up + "\t-");
        for (Strategy st : files.values()) {
            b.append('\t').append(Lookups.split(st, r, x, up));
        }
        return b.toString();
    }

    /** B8's 1,000 split lines, by up-card, then r, then x. */
    static List<String> splitRows(Map<String, Strategy> files) {
        List<String> out = new ArrayList<>();
        for (int up = 1; up <= 10; up++) {
            for (int r = 1; r <= 10; r++) {
                for (int x = 1; x <= 10; x++) {
                    out.add(splitRow(r, x, up, files));
                }
            }
        }
        return out;
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
    void theSplitCellsAreB24sWorkedByHand() throws IOException {
        Map<String, Strategy> f = files();
        // a split ten that draws a ten against a 6: with Split not legal, group 0's first line
        // (P S H D) gives Stand and group 1's split line (S P H D) Stand; with Split legal, group 0
        // splits again and group 1 stands
        assertEquals("SS:PS", cell(splitRow(10, 10, 6, f), 1));
        // group 0's split line for (3, 8) against a 6 doubles, read by a split 3 that draws an 8 and
        // a split 8 that draws a 3; group 1 has no split line for it and its first line hits
        assertEquals("DH", cell(splitRow(3, 8, 6, f), 1));
        assertEquals("DH", cell(splitRow(8, 3, 6, f), 1));
        assertEquals("HH", cell(splitRow(3, 8, 5, f), 1), "no split line against a 5");
        // group 0's line for (1, 5) against a 6 starts with Double: a split 5 that draws an ace
        // doubles, a split ace that draws a 5 stands (Stand alone is legal on a split ace)
        assertEquals("DH", cell(splitRow(5, 1, 6, f), 1));
        assertEquals("SS", cell(splitRow(1, 5, 6, f), 1));
        // split aces split once, so (1, 1) has no part after ':'; an ace and a ten stand
        assertEquals("SS", cell(splitRow(1, 1, 6, f), 1));
        assertEquals("SS", cell(splitRow(1, 10, 6, f), 1));
        assertEquals("SS", cell(splitRow(10, 1, 6, f), 1));
        assertEquals("SS:PP", cell(splitRow(2, 2, 6, f), 1), "no split line: the first line in both groups");
        // Hit and Double are legal in both parts: (5, 5)'s first line (D P S H) doubles with Split
        // legal or not; group 1's split line for (4, 4) (H P S D) hits in both parts, where group 0's
        // first line stands, then splits
        assertEquals("DD:DD", cell(splitRow(5, 5, 6, f), 1));
        assertEquals("SH:PH", cell(splitRow(4, 4, 6, f), 1));
        // group 1's split line for (6, 6) starts with P as its first line does: with Split not legal
        // it hits by the split line, where the first line (group 0) stands; with Split legal both split
        assertEquals("SH:PP", cell(splitRow(6, 6, 6, f), 1));
        for (int c : new int[]{0, 2, 3, 4}) {
            assertEquals("-", cell(splitRow(10, 10, 6, f), c), "not a full-play file");
        }
        // B8's example: a file of 10 groups holding B2.4's two example lines, whose first lines for
        // (10, 10, 6) start with Stand in groups 0 to 7 and with Split in groups 8 and 9
        StrategyFixture ten = new StrategyFixture();
        ten.scope = "full";
        ten.groupHi = new int[]{-9, -8, -7, -6, -5, -4, -3, -2, -1, 99};
        ten.first = (g, p1, p2, up) -> p1 == 10 && p2 == 10 && up == 6 && g < 8 ? "S\tP\tH\tD"
                : p1 == p2 ? "P\tS\tH\tD" : "S\tH\tD";
        ten.split = (g, p1, p2, up) -> g == 9 && p1 == 10 && p2 == 10 && up == 6 ? "S\tP\tH\tD" : null;
        assertTrue(ten.lines().containsAll(List.of("first\t9\t10\t10\t6\tP\tS\tH\tD", "split\t9\t10\t10\t6\tS\tP\tH\tD")));
        assertEquals("SSSSSSSSSS:SSSSSSSSPS", Lookups.split(ten.read(), 10, 10, 6));
    }

    @Test
    void everyCellIsComparedAndADifferenceIsNamed() throws IOException {
        States s = states();
        Map<String, Strategy> f = files();
        List<String> paths = new ArrayList<>(f.keySet());
        List<String> text = firstAndLater(paths, f, s, true);
        int lastLater = text.size() - 1;
        text.addAll(splitRows(f));
        Lookups.Result clean = Lookups.check(text, paths, f::get, Chart.textbook(), s);
        assertTrue(clean.passes(), clean.first().toString());
        assertEquals(1025, clean.lines());
        assertEquals(5125, clean.cells());
        // the split cells are the full-play file's column of the 1,000 split lines; the other four
        // files' columns there are counted apart from them
        assertEquals(1000, clean.splitLines());
        assertEquals(1000, clean.splitCells());
        assertEquals(0, clean.splitMismatches());
        assertEquals(4000, clean.splitOtherCells());
        assertEquals(0, clean.splitOtherMismatches());
        // the colour count's parts in another order still agree
        List<String> reordered = new ArrayList<>(text);
        reordered.set(3, reordered.get(3).replace("1:1:S:-;2:1:S:-;3:1:S:-", "3:1:S:-;1:1:S:-;2:1:S:-"));
        assertTrue(Lookups.check(reordered, paths, f::get, Chart.textbook(), s).passes());
        // a first move, an insurance decision and a later move changed: each is named
        List<String> planted = new ArrayList<>(text);
        planted.set(6, planted.get(6).replaceFirst("\t0:0:H:0\t", "\t0:0:S:0\t"));
        planted.set(11, planted.get(11).replaceFirst("\t1:1:S:1\t", "\t1:1:S:0\t"));
        planted.set(lastLater, planted.get(lastLater).replaceFirst(":36:S", ":36:H"));
        Lookups.Result bad = Lookups.check(planted, paths, f::get, Chart.textbook(), s);
        assertEquals(3, bad.mismatches(), bad.first().toString());
        assertEquals(0, bad.splitMismatches());
        // a split cell changed is named, and counted among the split cells
        List<String> split = new ArrayList<>(text);
        int tens = split.indexOf(splitRow(10, 10, 6, f));
        split.set(tens, split.get(tens).replace("\tSS:PS\t", "\tSS:SS\t"));
        Lookups.Result badSplit = Lookups.check(split, paths, f::get, Chart.textbook(), s);
        assertEquals(1, badSplit.mismatches(), badSplit.first().toString());
        assertEquals(1, badSplit.splitMismatches());
        assertEquals(0, badSplit.splitOtherMismatches());
        assertTrue(badSplit.first().get(0).contains("split 10 drawing 10 up 6"), badSplit.first().get(0));
        // a letter where a file not of scope full has "-" (column 0) is a mismatch among the other columns
        List<String> dash = new ArrayList<>(text);
        dash.set(tens, dash.get(tens).replace("\t-\tSS:PS\t", "\tSS\tSS:PS\t"));
        Lookups.Result badDash = Lookups.check(dash, paths, f::get, Chart.textbook(), s);
        assertEquals(1, badDash.mismatches(), badDash.first().toString());
        assertEquals(0, badDash.splitMismatches());
        assertEquals(1, badDash.splitOtherMismatches());
        assertFalse(badDash.passes());
        // a header whose columns are not the files in path order is refused
        List<String> header = new ArrayList<>(text);
        header.set(2, Lookups.HEADER + "\t" + String.join("\t", paths.subList(1, paths.size())));
        assertThrows(IOException.class, () -> Lookups.check(header, paths, f::get, Chart.textbook(), s));
    }

    /** lookups.tsv's first three lines, then first lines at the three states and, if asked, later lines. */
    static List<String> firstAndLater(List<String> paths, Map<String, Strategy> f, States s, boolean later) {
        List<String> text = new ArrayList<>(List.of("# lookups.tsv: SHOE_SPEC.md B8, format 1", "# sample seed 20261009",
                Lookups.HEADER + "\t" + String.join("\t", paths)));
        for (int st = 0; st < 3; st++) {
            for (int[] d : new int[][]{{7, 7, 2}, {2, 3, 4}, {1, 10, 1}, {10, 10, 1}, {8, 8, 10}}) {
                text.add(row("first", st, d[0], d[1], d[2], "-", f, s));
            }
        }
        for (int hit = 1; later && hit <= 10; hit++) {
            text.add(row("later", 1, 2, 3, 4, Integer.toString(hit), f, s));
        }
        return text;
    }

    @Test
    void theThousandSplitLinesAreThereInOrderOrRefused() throws IOException {
        States s = states();
        Map<String, Strategy> f = files();
        List<String> paths = new ArrayList<>(f.keySet());
        List<String> head = firstAndLater(paths, f, s, true);
        List<String> splits = splitRows(f);
        // a lookups.tsv written before the split lines, for a bundle with a full-play file
        assertThrows(IOException.class, () -> Lookups.check(head, paths, f::get, Chart.textbook(), s));
        // one split line missing
        List<String> missing = new ArrayList<>(head);
        missing.addAll(splits);
        missing.remove(head.size() + 555);
        assertThrows(IOException.class, () -> Lookups.check(missing, paths, f::get, Chart.textbook(), s));
        List<String> lastMissing = new ArrayList<>(head);
        lastMissing.addAll(splits.subList(0, 999));
        assertThrows(IOException.class, () -> Lookups.check(lastMissing, paths, f::get, Chart.textbook(), s));
        // two split lines out of order
        List<String> swapped = new ArrayList<>(head);
        swapped.addAll(splits);
        java.util.Collections.swap(swapped, head.size() + 554, head.size() + 555);
        assertThrows(IOException.class, () -> Lookups.check(swapped, paths, f::get, Chart.textbook(), s));
        // a split line whose state or hit is not "-"
        for (String[] change : new String[][]{{"split\t-\t", "split\t0\t"}, {"\t6\t-\t", "\t6\t1\t"}}) {
            List<String> marked = new ArrayList<>(head);
            marked.addAll(splits);
            int at = head.size() + 599;
            assertTrue(marked.get(at).startsWith("split\t-\t10\t10\t6\t-\t"), marked.get(at));
            marked.set(at, marked.get(at).replaceFirst(change[0], change[1]));
            assertThrows(IOException.class, () -> Lookups.check(marked, paths, f::get, Chart.textbook(), s),
                    marked.get(at));
        }
        // a later line after the split lines
        List<String> late = new ArrayList<>(head);
        late.addAll(splits);
        late.add(head.get(head.size() - 1));
        assertThrows(IOException.class, () -> Lookups.check(late, paths, f::get, Chart.textbook(), s));
        // split lines in a bundle with no full-play file
        Map<String, Strategy> noFull = new LinkedHashMap<>(f);
        noFull.remove("strategy-b-p80-f1.txt");
        List<String> noFullPaths = new ArrayList<>(noFull.keySet());
        List<String> withSplits = firstAndLater(noFullPaths, noFull, s, false);
        List<String> old = new ArrayList<>(withSplits);
        withSplits.addAll(splitRows(noFull));
        assertThrows(IOException.class, () -> Lookups.check(withSplits, noFullPaths, noFull::get, Chart.textbook(), s));
        // without them it reads as before: no split cell, and D5 passes
        Lookups.Result before = Lookups.check(old, noFullPaths, noFull::get, Chart.textbook(), s);
        assertTrue(before.passes(), before.first().toString());
        assertEquals(15, before.lines());
        assertEquals(60, before.cells());
        assertEquals(0, before.splitLines());
        assertEquals(0, before.splitCells());
        assertEquals(0, before.splitOtherCells());
        assertFalse(before.fullPlay());
        // D5 fails when the bundle holds a full-play file and no split cell was compared, even when
        // other files' columns of split lines were
        assertFalse(new Lookups.Result(25, 125, 0, List.of(), 0, 0, 0, 0, 0, true).passes());
        assertFalse(new Lookups.Result(1025, 5125, 0, List.of(), 1000, 0, 0, 5000, 0, true).passes());
        assertTrue(new Lookups.Result(25, 125, 0, List.of(), 0, 0, 0, 0, 0, false).passes());
    }

    @Test
    void splitHandsThatReadTheFirstLineAreCaught() throws IOException {
        States s = states();
        Map<String, Strategy> f = files();
        List<String> paths = new ArrayList<>(f.keySet());
        List<String> text = firstAndLater(paths, f, s, true);
        text.addAll(splitRows(f));
        // The full-play file read without its split lines: its split hands read the first line, as a
        // simulator would that knew no split line. Six cells differ, in B8's order (3, 8), (4, 4),
        // (5, 1), (6, 6), (8, 3) and (10, 10), against a 6. Every first and later cell still agrees.
        StrategyFixture b = full();
        b.split = (g, p1, p2, up) -> null;
        Map<String, Strategy> firstOnly = new LinkedHashMap<>(f);
        firstOnly.put("strategy-b-p80-f1.txt", b.read());
        Lookups.Result r = Lookups.check(text, paths, firstOnly::get, Chart.textbook(), s);
        assertFalse(r.passes());
        assertEquals(6, r.mismatches(), r.first().toString());
        assertEquals(6, r.splitMismatches());
        assertEquals(6, r.first().size());
        for (String[] m : new String[][]{{"3", "8", "DH", "HH"}, {"4", "4", "SH:PH", "SS:PP"}, {"5", "1", "DH", "HH"},
                {"6", "6", "SH:PP", "SS:PP"}, {"8", "3", "DH", "HH"}, {"10", "10", "SS:PS", "SS:PP"}}) {
            assertTrue(r.first().stream().anyMatch(x -> x.contains("split " + m[0] + " drawing " + m[1] + " up 6")
                    && x.endsWith("the file says " + m[2] + ", the simulator " + m[3])), r.first().toString());
        }
    }
}
