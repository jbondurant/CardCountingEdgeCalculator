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
 *
 * <p>The columns D2 leaves out (B4's bullet of 19 October 2026: aColumnNoCountNamesIsLeftOutAndNamed
 * here, and SimCommandsTest.checksTxtNamesTheColumnsD2LeftOut and
 * FormatsTest.checksTxtsSecondD2LineIsTheDocumentedForm for checks.txt) were planted on 19 October
 * 2026 in a scratch copy of sim/ outside the worktree, one at a time; each failed the tests named.
 * Such a column counted as a mismatch again, as d188bca's sim/ counted it (aColumnNoCountNames...
 * and checksTxtNames...); not named (those two and checksTxtsSecondD2Line...); its cells counted
 * among the cells compared (aColumnNoCountNames... and checksTxtNames...); compared as if it were
 * the null count (the same two); its cells read, and one that is no key refused
 * (aColumnNoCountNames...); a file's own count left out as if no file named it
 * (aColumnNoCountNames... and everyCellIsComparedAndADifferenceIsNamed); the ace variants left out
 * too, the reader finding only the catalog's entries and the null count by name
 * (aColumnNoCountNames..., checksTxtNames..., checksTxtsSecondD2Line...,
 * everyCellIsCompared... and theCatalogMakesB9sEntriesAndVariants); a catalog count missing from
 * the header no longer failing D2 (aColumnNoCountNames... and checksTxtNames...). SimCommandsTest's
 * test names what was planted in checks.txt's lines.
 *
 * <p>A count of the bundle's files the header lacks (aFileCountTheHeaderLacksFailsD2 here, and
 * SimCommandsTest.checksTxtNamesAFilesCountTheHeaderLacks for checks.txt), after the clean-room
 * review of 268b2d0, was planted the same way on 19 October 2026: such a count not required
 * (aFileCount... and checksTxtNamesAFilesCount...); named but not failing D2 (the same two); named
 * once for each file that names it (the same two); the files' betcount not looked for (the same
 * two); a catalog count a file names named here as well as in missing (aFileCount...); named in
 * missing, the catalog counts' list on the first line (the same two as the first). Each plant of
 * the paragraph above, run again on that code, still failed the tests it names.
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
        assertEquals(List.of(), clean.leftOut(), "every column here is a count the reader knows");
        assertEquals(List.of(), clean.unlisted(), "the file's two counts have their columns");
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

    /**
     * A line of a header in another order: each column {@code natural} names takes its cell from the
     * line written in that order, and each other column the cell {@code others} gives it.
     */
    static String arrange(List<String> natural, List<String> order, String line, java.util.Map<String, String> others) {
        String[] f = line.split("\t", -1);
        List<String> out = new ArrayList<>();
        for (String name : order) {
            out.add(natural.contains(name) ? f[natural.indexOf(name)] : others.get(name));
        }
        return String.join("\t", out);
    }

    /** The line with field {@code at} removed. */
    static String remove(String line, int at) {
        List<String> f = new ArrayList<>(List.of(line.split("\t", -1)));
        f.remove(at);
        return String.join("\t", f);
    }

    /**
     * SHOE_SPEC.md B4's bullet of 19 October 2026: a column whose name no count of the catalog or of
     * the bundle's files has (in the page look's bundles, a registered count with no strategy file in
     * the bundle) is left out of D2's comparison and of its mismatches, and named, every one in header
     * order; whatever its cells hold makes no difference. The catalog's columns and the columns of the
     * file's own counts are held key for key as before, and a catalog count or ace variant missing
     * from the header still fails D2. The columns are where B4 puts them: the registered counts after
     * the null count, before the ace variants (two left out, and F9's play count between them), and
     * the registered betting variants at the end (F9's, and a third column left out).
     */
    @Test
    void aColumnNoCountNamesIsLeftOutAndNamed() throws IOException {
        Catalog c = catalog();
        States s = states();
        Strategy f9 = registered();
        List<KeysSample.Column> extra = List.of(KeysSample.column("F9 test", c, List.of(f9), false),
                KeysSample.column("F9 test + vector", c, List.of(f9), false));
        String u1 = "F1 flat (f1-flat-pub-uston-apc)";
        String u2 = "F1r's tags as a true count (f1r-double-shift-half)";
        String u3 = "Hi-Opt II at grain 500/10000";
        int afterNull = 8 + 31;
        // the columns: the catalog's 30 entries and the null count, u1, F9 test, u2, the 8 ace variants,
        // F9 test + vector, u3
        List<String> natural = List.of(header(c, "F9 test", "F9 test + vector").split("\t", -1));
        List<String> head = new ArrayList<>(natural.subList(0, afterNull));
        head.addAll(List.of(u1, "F9 test", u2));
        head.addAll(natural.subList(afterNull, afterNull + 8));
        head.addAll(List.of("F9 test + vector", u3));
        assertEquals(c.variants.get(0).name(), head.get(afterNull + 3), "the ace variants follow");
        assertEquals(natural.size() + 3, head.size());
        String h = String.join("\t", head);
        List<String> text = new ArrayList<>(List.of("# keys-sample.tsv: SHOE_SPEC.md B4, format 1", "# sample seed 20261009", h));
        List<String> altered = new ArrayList<>(text);
        for (int st = 0; st < 2; st++) {
            for (int[] d : new int[][]{{0, 0, 0}, {7, 7, 2}, {2, 3, 4}, {1, 10, 1}, {10, 10, 10}}) {
                String l = line(c, s, d[0] == 0 ? "bet" : "play", st, d[0], d[1], d[2], extra);
                // the cells of the columns left out: keys some count might have, then cells no count could
                text.add(arrange(natural, head, l, java.util.Map.of(u1, "-3", u2, "2", u3, "1:0.25;2:0.75")));
                altered.add(arrange(natural, head, l, java.util.Map.of(u1, "99999", u2, "not a key", u3, "")));
            }
        }
        KeysSample.Result clean = KeysSample.check(text, c, List.of(f9), s, Plants.NONE);
        assertTrue(clean.passes(), clean.first().toString());
        assertEquals(List.of(u1, u2, u3), clean.leftOut(), "every column left out is named, in header order");
        assertEquals(10, clean.lines());
        assertEquals(10L * 41, clean.cells(), "the catalog's 39 columns and F9's two, not the three left out");
        assertEquals(0, clean.mismatches());
        assertEquals(List.of(), clean.first());
        assertEquals(List.of(), clean.missing());
        assertEquals(List.of(), clean.unlisted());
        // what the columns left out hold makes no difference
        assertEquals(clean, KeysSample.check(altered, c, List.of(f9), s, Plants.NONE));
        // a key changed in a catalog count's column, an ace variant's, or a column of the file's own
        // counts still fails D2, and is named
        for (String col : List.of("Hi-Lo = Hi-Lo Lite", "Hi-Opt II + ace side count", "F9 test", "F9 test + vector")) {
            for (int n : new int[]{3, 4}) {
                List<String> planted = new ArrayList<>(text);
                String[] f = planted.get(n).split("\t", -1);
                int at = head.indexOf(col);
                f[at] = Integer.toString(Integer.parseInt(f[at]) + 1);
                planted.set(n, String.join("\t", f));
                KeysSample.Result bad = KeysSample.check(planted, c, List.of(f9), s, Plants.NONE);
                String what = col + " on line " + (n + 1);
                assertFalse(bad.passes(), what);
                assertEquals(1, bad.mismatches(), what);
                assertEquals(10L * 41, bad.cells(), what);
                assertTrue(bad.first().get(0).contains(", " + col + ": the file says "), bad.first().get(0));
                assertEquals(List.of(u1, u2, u3), bad.leftOut(), what);
            }
        }
        // a catalog count, or an ace variant, missing from the header still fails D2
        for (String gone : List.of("Red Seven (Red 7)", "Hi-Opt II + ace side count")) {
            int at = head.indexOf(gone);
            List<String> dropped = new ArrayList<>(text.subList(0, 2));
            for (String l : text.subList(2, text.size())) {
                dropped.add(remove(l, at));
            }
            KeysSample.Result missing = KeysSample.check(dropped, c, List.of(f9), s, Plants.NONE);
            assertEquals(List.of(gone), missing.missing());
            assertFalse(missing.passes(), gone);
            assertEquals(0, missing.mismatches(), gone);
            assertEquals(List.of(u1, u2, u3), missing.leftOut(), gone);
        }
        // F9's counts are known only from its file: in a bundle without it, their columns are left out too,
        // which is the page look's case, a registered count with no strategy file in the bundle
        KeysSample.Result noFile = KeysSample.check(text, c, List.of(), s, Plants.NONE);
        assertTrue(noFile.passes(), noFile.first().toString());
        assertEquals(List.of(u1, "F9 test", u2, "F9 test + vector", u3), noFile.leftOut());
        assertEquals(10L * 39, noFile.cells());
    }

    /**
     * B4's header lists the registered counts and their own betting variants, so a count a strategy
     * file of the bundle plays or bets with must have its column, as a catalog count must: one the
     * header lacks is named once, in the files' order, playcount before betcount, and fails D2. A
     * column of the file's count that is misnamed is left out and named as no count's (it is
     * unknown), and the file's count it stood for is still named, so the bundle's own count cannot go
     * unchecked by a slip of its name (the clean-room review of 268b2d0 found that it did). A
     * catalog count a file names is in {@code missing} alone, and the page look's case, a registered
     * count's column with no file in the bundle, still passes.
     */
    @Test
    void aFileCountTheHeaderLacksFailsD2() throws IOException {
        Catalog c = catalog();
        States s = states();
        Strategy f9 = registered();
        List<Strategy> folds = List.of(f9, f9);
        List<KeysSample.Column> extra = List.of(KeysSample.column("F9 test", c, List.of(f9), false),
                KeysSample.column("F9 test + vector", c, List.of(f9), false));
        List<String> natural = List.of(header(c, "F9 test", "F9 test + vector").split("\t", -1));
        List<String> named = new ArrayList<>();
        for (int st = 0; st < 2; st++) {
            for (int[] d : new int[][]{{0, 0, 0}, {7, 7, 2}, {2, 3, 4}, {1, 10, 1}, {10, 10, 10}}) {
                named.add(line(c, s, d[0] == 0 ? "bet" : "play", st, d[0], d[1], d[2], extra));
            }
        }
        // the header as given, each line's cells by name: F9's columns are what it calls them, with
        // the cells its map gives, and every other column the simulator's own cell
        java.util.function.BiFunction<List<String>, java.util.Map<String, String>, List<String>> sample = (head, cells) -> {
            List<String> text = new ArrayList<>(List.of("# keys-sample.tsv: SHOE_SPEC.md B4, format 1",
                    "# sample seed 20261009", String.join("\t", head)));
            for (String l : named) {
                text.add(arrange(natural, head, l, cells));
            }
            return text;
        };
        List<String> catalogOnly = new ArrayList<>(natural.subList(0, natural.size() - 2));
        // F9's two columns misnamed, their cells wrong: left out as no count's, and F9's counts named
        List<String> misnamed = new ArrayList<>(catalogOnly);
        misnamed.addAll(List.of("F9 Test", "F9 Test + vector"));
        KeysSample.Result r = KeysSample.check(sample.apply(misnamed, java.util.Map.of("F9 Test", "888",
                "F9 Test + vector", "777")), c, folds, s, Plants.NONE);
        assertFalse(r.passes());
        assertEquals(List.of("F9 test", "F9 test + vector"), r.unlisted(), "each once, though two files name them");
        assertEquals(List.of("F9 Test", "F9 Test + vector"), r.leftOut());
        assertEquals(0, r.mismatches());
        assertEquals(10L * 39, r.cells());
        assertEquals(List.of(), r.missing());
        assertEquals(List.of(), r.first());
        // no column for either: the same two named
        r = KeysSample.check(sample.apply(catalogOnly, java.util.Map.of()), c, folds, s, Plants.NONE);
        assertFalse(r.passes());
        assertEquals(List.of("F9 test", "F9 test + vector"), r.unlisted());
        assertEquals(List.of(), r.leftOut());
        // the play count's column there and the betting variant's not: the variant alone named
        List<String> playOnly = new ArrayList<>(catalogOnly);
        playOnly.add(39, "F9 test");
        r = KeysSample.check(sample.apply(playOnly, java.util.Map.of()), c, folds, s, Plants.NONE);
        assertFalse(r.passes());
        assertEquals(List.of("F9 test + vector"), r.unlisted());
        assertEquals(10L * 40, r.cells());
        // and the other way round
        List<String> betOnly = new ArrayList<>(catalogOnly);
        betOnly.add("F9 test + vector");
        r = KeysSample.check(sample.apply(betOnly, java.util.Map.of()), c, folds, s, Plants.NONE);
        assertFalse(r.passes());
        assertEquals(List.of("F9 test"), r.unlisted());
        // both there: passes
        r = KeysSample.check(sample.apply(natural, java.util.Map.of()), c, folds, s, Plants.NONE);
        assertTrue(r.passes(), r.first().toString());
        assertEquals(List.of(), r.unlisted());
        // a file of a catalog count, Hi-Lo, whose column the header lacks: in missing, and not again here
        Strategy hiLo = Strategy.parse(new StrategyFixture().lines(), "hi-lo", Plants.NONE);
        List<String> noHiLo = new ArrayList<>(catalogOnly);
        noHiLo.remove("Hi-Lo = Hi-Lo Lite");
        r = KeysSample.check(sample.apply(noHiLo, java.util.Map.of()), c, List.of(hiLo, hiLo), s, Plants.NONE);
        assertFalse(r.passes());
        assertEquals(List.of("Hi-Lo = Hi-Lo Lite"), r.missing());
        assertEquals(List.of(), r.unlisted());
        // with no file of F9 in the bundle, its misnamed columns are only left out, and D2 passes
        r = KeysSample.check(sample.apply(misnamed, java.util.Map.of("F9 Test", "888", "F9 Test + vector", "777")),
                c, List.of(hiLo), s, Plants.NONE);
        assertTrue(r.passes(), r.first().toString());
        assertEquals(List.of(), r.unlisted());
        assertEquals(List.of("F9 Test", "F9 Test + vector"), r.leftOut());
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
