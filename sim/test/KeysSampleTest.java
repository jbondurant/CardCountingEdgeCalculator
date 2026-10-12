package sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Check D2: the catalog read as SHOE_SPEC.md B9 says (from the repository's counting-systems.json,
 * run from the repository's root), and keys-sample.tsv compared cell by cell. The expected keys
 * below are worked by hand from B2.2's formulas; the rest of a test file is written by the
 * simulator's own code, so those cells test the comparison, not the keys.
 */
class KeysSampleTest {

    static Catalog catalog() throws IOException {
        return Catalog.read(Path.of("counting-systems.json"));
    }

    @Test
    void theCatalogMakesB9sEntriesAndVariants() throws IOException {
        Catalog c = catalog();
        assertEquals(30, c.entries.size());
        assertEquals(8, c.variants.size());
        assertEquals(39, c.all().size());
        assertNotNull(c.named("Hi-Lo = Hi-Lo Lite"));
        assertNotNull(c.named("KO (Knock-Out) = REKO (Ridiculously Easy Knock-Out)"));
        assertNotNull(c.named("Revere Point Count (RPC) = FELT (Fairly Easy Level Two) = C-R (Chambliss-Roginski)"));
        assertNotNull(c.named("Omega II = Canfield Master"));
        assertNotNull(c.named("Omega II = Canfield Master + ace side count"));
        // SIM_CHECK.md's count list, as the results files name its entries
        for (String n : List.of("Red Seven (Red 7)", "Zen Count", "Hi-Opt II + ace side count",
                "Victor Advanced Point Count (Victor APC) + ace side count", Catalog.NULL_COUNT)) {
            assertNotNull(c.named(n), n);
        }
        Catalog.Count red7 = c.named("Red Seven (Red 7)");
        assertEquals(1, red7.scale());
        assertEquals(1, red7.tags()[13]);
        assertEquals(0, red7.tags()[12]);
        assertTrue(red7.byColourOrFace());
        assertFalse(red7.trueCount());
        Catalog.Count halves = c.named("Wong Halves");
        assertEquals(2, halves.scale());
        assertArrayEquals(new int[]{-2, -2, 1, 1, 2, 2, 2, 2, 3, 3, 2, 2, 1, 1, 0, 0, -1, -1, -2, -2, -2, -2, -2, -2, -2, -2},
                halves.tags());
        Catalog.Count kiss1 = c.named("KISS I");
        assertEquals(0, kiss1.tags()[2] + kiss1.tags()[3] - 1, "2black 1, 2red 0");
        assertEquals(0, kiss1.tags()[18]);
        assertEquals(-1, kiss1.tags()[21]);
        assertEquals(2, c.named("Hi-Opt II + ace side count").ace());
        assertEquals(3, c.named("Victor Advanced Point Count (Victor APC) + ace side count").ace());
        assertEquals(0, c.named("Hi-Opt II").ace());
    }

    @Test
    void theSideCountedListIsSpecsBlock() throws IOException {
        assertEquals(SpecCopyTest.block("```shoe-spec-side-counted"), Catalog.SIDE_COUNTED);
    }

    /** Two hand-made records: a full shoe, and one after ten black twos (Hi-Lo running count 10). */
    static States states() throws IOException {
        StatesFixture f = new StatesFixture(0);
        f.shoes = 2;
        for (int d : new int[]{0, 10}) {
            StatesFixture.Rec r = new StatesFixture.Rec();
            r.shoe = d == 0 ? 0 : 1;
            r.fold = r.shoe;
            r.depth = d;
            r.q = 0.5;
            r.dealt = new int[Shoe.KINDS];
            r.dealt[2] = d;
            f.records.add(r);
        }
        return f.read();
    }

    static String header(Catalog c, String... extra) {
        StringBuilder b = new StringBuilder(KeysSample.HEADER);
        for (Catalog.Count k : c.all()) {
            b.append('\t').append(k.name());
        }
        for (String e : extra) {
            b.append('\t').append(e);
        }
        return b.toString();
    }

    /** A line whose cells are the simulator's own, then overridden where the test knows the answer. */
    static String line(Catalog c, States s, String kind, int state, int p1, int p2, int up, List<KeysSample.Column> extra) {
        int[] dealt = new int[Shoe.KINDS];
        for (int k = 0; k < Shoe.KINDS; k++) {
            dealt[k] = s.dealt[state * Shoe.KINDS + k];
        }
        StringBuilder b = new StringBuilder(kind + "\t" + state + "\t" + s.shoe[state] + "\t" + s.round[state] + "\t"
                + s.depth[state] + (kind.equals("bet") ? "\t-\t-\t-" : "\t" + p1 + "\t" + p2 + "\t" + up));
        List<KeysSample.Column> cols = new ArrayList<>();
        for (Catalog.Count k : c.all()) {
            cols.add(new KeysSample.Column(k.name(), k.keys(false), k.keys(false)));
        }
        cols.addAll(extra);
        for (KeysSample.Column col : cols) {
            b.append('\t');
            if (kind.equals("bet")) {
                b.append(col.bet().betKey(dealt, s.depth[state]));
            } else if (col.byColourOrFace()) {
                TreeMap<Integer, Double> parts = KeysSample.playKeyParts(col.play(), dealt, s.depth[state], p1, p2, up);
                StringBuilder cell = new StringBuilder();
                parts.forEach((key, chance) -> cell.append(cell.length() == 0 ? "" : ";").append(key).append(':').append(chance));
                b.append(parts.isEmpty() ? "-" : cell);
            } else {
                b.append(KeysSample.playKey(col.play(), dealt, s.depth[state], p1, p2, up));
            }
        }
        return b.toString();
    }

    static String cell(String header, String line, String column) {
        return line.split("\t", -1)[List.of(header.split("\t", -1)).indexOf(column)];
    }

    @Test
    void theKeysAreB22sWorkedByHand() throws IOException {
        Catalog c = catalog();
        States s = states();
        String h = header(c);
        String bet1 = line(c, s, "bet", 1, 0, 0, 0, List.of());
        // ten black twos dealt, 406 cards left: Hi-Lo round(520 / 406) = 1; KO running 10;
        // Hi-Opt II + ace: 52·10 + 2·(52·32 − 4·406) = 600 over 406, 1; Wong Halves at scale 2: 1.
        assertEquals("1", cell(h, bet1, "Hi-Lo = Hi-Lo Lite"));
        assertEquals("10", cell(h, bet1, "KO (Knock-Out) = REKO (Ridiculously Easy Knock-Out)"));
        assertEquals("1", cell(h, bet1, "Hi-Opt II + ace side count"));
        assertEquals("0", cell(h, bet1, "Hi-Opt I"));
        assertEquals("0", cell(h, bet1, Catalog.NULL_COUNT));
        // the deal (2, 3) against a 4 at that state: Hi-Lo running 13 over 403 cards, round(676 / 403) = 2
        String play = line(c, s, "play", 1, 2, 3, 4, List.of());
        assertEquals("2", cell(h, play, "Hi-Lo = Hi-Lo Lite"));
        assertEquals("13", cell(h, play, "KO (Knock-Out) = REKO (Ridiculously Easy Knock-Out)"));
        // Red Seven, a full shoe, (7, 7) against a 2: both black 16/32 · 15/31, one of each 2 · 16/32 · 16/31
        String sevens = line(c, s, "play", 0, 7, 7, 2, List.of());
        TreeMap<Integer, Double> parts = KeysSample.readParts(cell(h, sevens, "Red Seven (Red 7)"));
        assertEquals(List.of(1, 2, 3), new ArrayList<>(parts.keySet()));
        assertEquals(240.0 / 992, parts.get(1), 1e-15);
        assertEquals(512.0 / 992, parts.get(2), 1e-15);
        assertEquals(240.0 / 992, parts.get(3), 1e-15);
    }

    @Test
    void everyCellIsComparedAndADifferenceIsNamed() throws IOException {
        Catalog c = catalog();
        States s = states();
        Strategy f2 = registered();
        List<KeysSample.Column> extra = List.of(KeysSample.column("F9 test", c, List.of(f2), false),
                KeysSample.column("F9 test + vector", c, List.of(f2), false));
        String h = header(c, "F9 test", "F9 test + vector");
        List<String> text = new ArrayList<>(List.of("# keys-sample.tsv: SHOE_SPEC.md B4, format 1", "# sample seed 20261009", h));
        for (int st = 0; st < 2; st++) {
            text.add(line(c, s, "bet", st, 0, 0, 0, extra));
            for (int[] d : new int[][]{{7, 7, 2}, {2, 3, 4}, {1, 10, 1}, {10, 10, 10}}) {
                text.add(line(c, s, "play", st, d[0], d[1], d[2], extra));
            }
        }
        KeysSample.Result clean = KeysSample.check(text, c, List.of(f2), s, Plants.NONE);
        assertTrue(clean.passes(), clean.first().toString());
        assertEquals(10, clean.lines());
        assertEquals(10L * 41, clean.cells());
        // the registered count's bet column takes the file's bet tags; its play column the play tags
        assertEquals("5", cell(h, text.get(8), "F9 test + vector"), "ten twos at bet tag 1, running, over divisor 2");
        // one planted difference, and one catalog column dropped from the header
        List<String> planted = new ArrayList<>(text);
        int red7 = List.of(h.split("\t", -1)).indexOf("Red Seven (Red 7)");
        String[] f = planted.get(4).split("\t", -1);
        f[red7] = "1:" + (240.0 / 992 * (1 + 1e-11)) + ";2:" + (512.0 / 992) + ";3:" + (240.0 / 992);
        planted.set(4, String.join("\t", f));
        KeysSample.Result bad = KeysSample.check(planted, c, List.of(f2), s, Plants.NONE);
        assertEquals(1, bad.mismatches(), bad.first().toString());
        assertTrue(bad.first().get(0).contains("Red Seven"), bad.first().get(0));
        // a chance off by 1e-13 relative is within D2's tolerance
        f[red7] = "1:" + (240.0 / 992 * (1 + 1e-13)) + ";2:" + (512.0 / 992) + ";3:" + (240.0 / 992);
        planted.set(4, String.join("\t", f));
        assertTrue(KeysSample.check(planted, c, List.of(f2), s, Plants.NONE).passes());
        // a catalog count the header leaves out is named
        List<String> dropped = new ArrayList<>();
        for (String l : text) {
            List<String> g = new ArrayList<>(List.of(l.split("\t", -1)));
            if (!l.startsWith("#")) {
                g.remove(red7);
            }
            dropped.add(String.join("\t", g));
        }
        assertEquals(List.of("Red Seven (Red 7)"), KeysSample.check(dropped, c, List.of(f2), s, Plants.NONE).missing());
        // the truncate plant changes a true count's rounding, and D2 sees it
        KeysSample.Result truncated = KeysSample.check(text, c, List.of(f2), s, Plants.of(Plants.TRUNCATE));
        assertFalse(truncated.passes());
    }

    /** A strategy of a count of our own: running, divisor 2, betting on a vector of its own. */
    static Strategy registered() throws IOException {
        List<String> lines = new ArrayList<>(new StrategyFixture().lines());
        int[] bet = KeysTest.hiLo().clone();
        bet[2] = 1;
        StringBuilder bt = new StringBuilder("bettags");
        long sum = 0;
        for (int t : bet) {
            bt.append('\t').append(t);
            sum += 2L * t;
        }
        long betSum = sum;
        lines.replaceAll(l -> l.startsWith("playcount\t") ? "playcount\tF9 test"
                : l.startsWith("betcount\t") ? "betcount\tF9 test + vector"
                : l.startsWith("bettags\t") ? bt.toString()
                : l.startsWith("betdecksum\t") ? "betdecksum\t" + betSum
                : l.equals("mode\ttrue") ? "mode\trunning"
                : l.equals("divisor\t1") ? "divisor\t2" : l);
        return Strategy.parse(lines, "registered", Plants.NONE);
    }
}
