package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The commands end to end on the fixture bundle, at tiny sizes, in a temporary directory: what
 * each writes, in the formats of SHOE_SPEC.md Part C, and that a summary reads back as JSON.
 */
class SimCommandsTest {

    @TempDir
    Path tmp;

    @SuppressWarnings("unchecked")
    @Test
    void p3P2AndVariantsWriteTheirFiles() throws Exception {
        Path bundle = BundleFixture.write(tmp.resolve("b"));
        Path registry = tmp.resolve("SIM_CHECK.md");
        Files.write(registry, List.of("# a registry for the test", "```sim-registry", Registry.HEADER,
                "F03\t1\tChart, no count, flat\t80\tflat\tP3,P2\tstep 7\t1\t0",
                "F26\t2\tHi-Lo = Hi-Lo Lite\t80\tflat\tP3,P2\tc06c061\t0\t0",
                "F18\t1\tHi-Lo = Hi-Lo Lite\t80\tev:same-8\tP3,P2\tc06c061\t1\t1", "```"));
        Path out = tmp.resolve("run");
        String[] common = {"--bundle", bundle.toString(), "--out", out.toString(), "--sim-commit", "test",
                "--registry", registry.toString()};
        Sim.p3(concat(common, "--chunks", "2", "--chunk-size", "60", "--fan-paths", "2", "--fan-rounds", "300",
                "--ruin-paths", "200"));
        Map<String, Object> summary = (Map<String, Object>) Json.parse(Files.readString(out.resolve("summary-p3.json")));
        assertEquals(Chart.textbook().fingerprint(), summary.get("chartFingerprint"));
        List<Object> comparisons = (List<Object>) summary.get("comparisons");
        assertTrue(comparisons.stream().anyMatch(c -> "F18".equals(((Map<String, Object>) c).get("fig"))));
        assertEquals(15, Files.readAllLines(out.resolve("depth.tsv")).size());
        assertEquals("# ev-by-key.tsv: SHOE_SPEC.md C4, format 1", Files.readAllLines(out.resolve("ev-by-key.tsv")).get(0));
        List<String> swings = Files.readAllLines(out.resolve("swings.tsv"));
        assertTrue(swings.get(0).startsWith("shoes\tF03\t120\t"), swings.get(0));
        assertTrue(swings.stream().anyMatch(l -> l.startsWith("z\tF18\tP3\t")));
        assertFalse(swings.stream().anyMatch(l -> l.startsWith("z\tF18\tP2\t")), "P2 has not run");
        assertEquals(120, Files.readAllLines(out.resolve("records-F18.tsv")).size());
        List<String> fan = Files.readAllLines(out.resolve("fan-F18.tsv"));
        assertEquals(2, fan.size());
        assertEquals(4, fan.get(1).split("\t").length);
        // Trip risk and being behind (open item 11): a behind line for each hours and pace, and,
        // when the records' mean is above 0, a trip line for each of B_25%, B_5% and B_1%.
        List<String> trip = Files.readAllLines(out.resolve("trip-F18.tsv"));
        assertEquals("kind\thours\tpace\trounds\tblocks\tmeanRounds\trisk\tbankroll\tsimulated\tse\tformula", trip.get(3));
        assertEquals(Files.exists(out.resolve("ruin-F18.tsv")) ? 4 + 9 * 4 : 4 + 9, trip.size());
        assertTrue(trip.get(4).startsWith("behind\t10\t50\t500\t"), trip.get(4));
        // P2 with a plant goes on past the failed exact checks (the fixture has no keys-sample.tsv)
        assertFalse(Sim.p2(concat(common, "--k", "20", "--plant", "s17")));
        assertTrue(Files.readString(out.resolve("checks.txt")).contains("D2\tnot run"));
        Map<String, Object> facts = (Map<String, Object>) Json.parse(Files.readString(out.resolve("summary-p2.json")));
        assertEquals("not run", facts.get("D2"), "each exact check's outcome is in the summary (open item 10)");
        assertEquals("not run", facts.get("D5"));
        assertEquals("failed", facts.get("D4"), "the fixture's bets are made up");
        swings = Files.readAllLines(out.resolve("swings.tsv"));
        int p3z = swings.indexOf(swings.stream().filter(l -> l.startsWith("z\tF18\tP3\t")).findFirst().orElseThrow());
        assertTrue(swings.get(p3z + 1).startsWith("z\tF18\tP2\t"), "P2's z follows P3's");
        Map<String, Object> p2 = (Map<String, Object>) Json.parse(Files.readString(out.resolve("summary-p2.json")));
        assertTrue(((String) p2.get("plants")).contains("s17=on"));
        Sim.variants(concat(common, "--chunks", "1", "--chunk-size", "30"));
        List<String> variants = Files.readAllLines(out.resolve("variants.tsv"));
        assertEquals(2 + 5, variants.size(), "F26 is the one figure of F26 to F29 this registry has");
        Map<String, Object> v = (Map<String, Object>) Json.parse(Files.readString(out.resolve("summary-variants.json")));
        assertEquals(2, ((List<Object>) v.get("differences")).size());
    }

    /**
     * INTEGRATION.md open item 2: on a bundle whose rows hold no registered figure with paths
     * (part 1's: no F27 or F29), p3 kept no per-shoe records and then asked writeRecords for a part
     * of every chunk, so it stopped after its chunks, before summary-p3.json. It must finish, with
     * or without --registry (without, it reads SIM_CHECK.md from the working directory, the
     * repository's root, where Hi-Lo's first-decision figures at 80% have swings but no paths).
     */
    @SuppressWarnings("unchecked")
    @Test
    void p3FinishesOnABundleWithNoFigureWithPaths() throws Exception {
        Path bundle = BundleFixture.write(tmp.resolve("b"));
        Path registry = tmp.resolve("SIM_CHECK.md");
        Files.write(registry, List.of("```sim-registry", Registry.HEADER,
                "F03\t1\tChart, no count, flat\t80\tflat\tP3,P2\tstep 7\t1\t0",
                "F18\t1\tHi-Lo = Hi-Lo Lite\t80\tev:same-8\tP3,P2\tc06c061\t1\t0", "```"));
        for (boolean given : new boolean[]{true, false}) {
            Path out = tmp.resolve(given ? "with" : "without");
            String[] args = {"--bundle", bundle.toString(), "--out", out.toString(), "--sim-commit", "test",
                    "--chunks", "3", "--chunk-size", "20"};
            Sim.p3(given ? concat(args, "--registry", registry.toString()) : args);
            Map<String, Object> summary = (Map<String, Object>) Json.parse(Files.readString(out.resolve("summary-p3.json")));
            List<Object> comparisons = (List<Object>) summary.get("comparisons");
            assertTrue(comparisons.stream().anyMatch(c -> "F18".equals(((Map<String, Object>) c).get("fig"))),
                    "F18 is registered in both registries");
            assertTrue(Files.readAllLines(out.resolve("swings.tsv")).get(0).startsWith("shoes\tF03\t60\t"));
            try (var files = Files.list(out)) {
                assertFalse(files.anyMatch(f -> f.getFileName().toString().matches("(records|fan|ruin|trip)-.*")),
                        "no figure here keeps paths");
            }
        }
    }

    /**
     * D5's line in checks.txt reads as it did before split lines existed, and a second line counts
     * the split cells apart: none on a bundle with no full-play file, which is what SIM_CHECK.md
     * deviation 1's check on part 1's bundle looks for; on a bundle whose two Hi-Lo files play in
     * full, their columns of the 1,000 split lines, and none of another file. Planted on 19 October
     * 2026 in a scratch copy of sim/ outside the worktree, one at a time; each failed this test:
     * the second line left out of checks.txt; the full-play columns' cells and the other columns'
     * given in each other's place.
     */
    @Test
    void checksTxtCountsTheSplitCellsApart() throws Exception {
        Path dir = BundleFixture.write(tmp.resolve("b"));
        int lines = writeLookups(dir, false);
        assertEquals(List.of("D5\t" + 2 * lines + " of " + 2 * lines + " lookups equal to lookups.tsv's on " + lines
                        + " lines; 0 mismatches",
                "D5\tsplit cells, the full-play files' columns of 0 split lines: 0 of 0 equal to lookups.tsv's; 0"
                        + " mismatches; the other files' columns there, each -: 0 of 0 equal; the bundle holds no file"
                        + " of scope full"),
                d5Lines(dir, tmp.resolve("run")));
        // Hi-Lo's two fold files at full play, and one of fold 1's split cells changed in lookups.tsv
        Path full = BundleFixture.write(tmp.resolve("full"));
        for (int fold = 0; fold < 2; fold++) {
            String path = "strategy-hi-lo-hi-lo-lite-p80-f" + fold + ".txt";
            BundleFixture.write(full.resolve(path), BundleFixture.hiLo("full", fold).lines());
            BundleFixture.reseal(full, path + "\t" + BundleFixture.HILO + "\t80\t" + fold + "\tfull");
        }
        int all = writeLookups(full, true);
        assertEquals(List.of("D5\t" + (2 * all - 1) + " of " + 2 * all + " lookups equal to lookups.tsv's on " + all
                        + " lines; 1 mismatches",
                "D5\tsplit cells, the full-play files' columns of 1000 split lines: 1999 of 2000 equal to"
                        + " lookups.tsv's; 1 mismatches; the other files' columns there, each -: 0 of 0 equal; the"
                        + " bundle holds a file of scope full"),
                d5Lines(full, tmp.resolve("runFull")));
    }

    /**
     * Writes lookups.tsv for the bundle from the simulator's own answers: a first line for (9, 9)
     * against a 6 at every fifth state and, if asked, the 1,000 split lines, fold 1's first split
     * cell changed. Returns the number of lines after the header.
     */
    static int writeLookups(Path dir, boolean splits) throws Exception {
        Bundle b = new Bundle(dir, Plants.NONE);
        States s = b.states();
        List<String> text = new java.util.ArrayList<>(List.of("# lookups.tsv: SHOE_SPEC.md B8, format 1",
                "# sample seed 20261009", Lookups.HEADER + "\t" + String.join("\t", b.strategyPaths())));
        for (int i = 0; i < s.n; i += 5) {
            StringBuilder line = new StringBuilder("first\t" + i + "\t9\t9\t6\t-");
            for (String p : b.strategyPaths()) {
                line.append('\t').append(Lookups.text(Lookups.first(b.strategyAt(p), b.chart, Lookups.dealt(s, i),
                        s.depth[i], 9, 9, 6)));
            }
            text.add(line.toString());
        }
        for (int up = 1; splits && up <= 10; up++) {
            for (int r = 1; r <= 10; r++) {
                for (int x = 1; x <= 10; x++) {
                    StringBuilder line = new StringBuilder("split\t-\t" + r + "\t" + x + "\t" + up + "\t-");
                    for (String p : b.strategyPaths()) {
                        String cell = Lookups.split(b.strategyAt(p), r, x, up);
                        boolean changed = up == 1 && r == 1 && x == 1 && p.endsWith("-f1.txt");
                        line.append('\t').append(changed ? cell.replace('S', 'H') : cell);
                    }
                    text.add(line.toString());
                }
            }
        }
        BundleFixture.write(dir.resolve("lookups.tsv"), text);
        BundleFixture.reseal(dir, "lookups.tsv\t-\t-\t-\t-");
        return text.size() - 3;
    }

    /** Runs p2 on the bundle (a plant on, so that it goes past the fixture's failed checks); checks.txt's D5 lines. */
    static List<String> d5Lines(Path dir, Path out) throws Exception {
        Sim.p2(new String[]{"--bundle", dir.toString(), "--out", out.toString(), "--sim-commit", "test", "--k", "2",
                "--plant", "s17"});
        return Files.readAllLines(out.resolve("checks.txt")).stream().filter(l -> l.startsWith("D5\t")).toList();
    }

    /**
     * D2 in checks.txt (SHOE_SPEC.md B4's bullet of 19 October 2026; sim/QUESTIONS.md item 33). Its
     * line keeps its form and counts only the keys compared; a second D2 line follows it, always,
     * with the number of columns left out and their names, and any mismatch's note comes after both.
     * On a bundle whose keys-sample.tsv names only counts the reader knows, as part 1's and the
     * replay's do, the second line gives 0 and is the one line added; on one with twelve columns no
     * count names, more than the ten notes checks.txt keeps, D2 passes, all twelve are named in header
     * order, and every other line is the same; a catalog key changed, or a catalog count missing from
     * the header, still fails it. Planted on 19 October 2026 in a scratch copy of sim/ outside the
     * worktree, one at a time; each failed this test: the second line written only when a column
     * was left out, and the names written as a Java list (both also failing
     * FormatsTest.checksTxtsSecondD2LineIsTheDocumentedForm); only the first ten named; the second
     * line written after the mismatches' notes; the first line's mismatches counting the columns
     * left out; a count of them appended after the missing list; the first line's form changed
     * (" lines: " for " lines; "). The plants of D2's reading are in KeysSampleTest's comment.
     */
    @Test
    void checksTxtNamesTheColumnsD2LeftOut() throws Exception {
        Map<String, String> status = new LinkedHashMap<>();
        Path plain = BundleFixture.write(tmp.resolve("plain"));
        int lines = writeKeys(plain, List.of(), null, null);
        int cells = 39 * lines;
        String d2 = "D2\t" + cells + " of " + cells + " keys equal to keys-sample.tsv's on " + lines
                + " lines; 0 mismatches; catalog counts missing from its header: []";
        String none = "D2\tcolumns left out, since no count of the catalog or of the bundle's files has the name: 0";
        List<String> without = checks(plain, tmp.resolve("runPlain"), status);
        assertEquals(List.of(d2, none), d2Lines(without));
        assertEquals(without.indexOf(d2) + 1, without.indexOf(none), "the second line follows the first");
        assertEquals("passed", status.get("D2"));
        // twelve columns no count names: ten registered counts after the null count and two registered
        // betting variants at the end, where B4 puts them
        List<String> unknown = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            unknown.add("F" + i + (i > 10 ? " + vector" : "") + " at 1-8 (f" + i + "'s, no file in the bundle)");
        }
        Path look = BundleFixture.write(tmp.resolve("look"));
        assertEquals(lines, writeKeys(look, unknown, null, null));
        List<String> with = checks(look, tmp.resolve("runLook"), status);
        String twelve = "D2\tcolumns left out, since no count of the catalog or of the bundle's files has the name: 12\t"
                + String.join("\t", unknown);
        assertEquals(List.of(d2, twelve), d2Lines(with));
        assertEquals("passed", status.get("D2"));
        List<String> rest = new ArrayList<>(with);
        rest.remove(twelve);
        List<String> restWithout = new ArrayList<>(without);
        restWithout.remove(none);
        assertEquals(restWithout, rest, "every other line is the same");
        // a catalog key changed still fails D2; its note follows both D2 lines
        Path changed = BundleFixture.write(tmp.resolve("changed"));
        writeKeys(changed, unknown, BundleFixture.HILO, null);
        List<String> failed = checks(changed, tmp.resolve("runChanged"), status);
        int at = failed.indexOf(d2Lines(failed).get(0));
        assertEquals("D2\t" + (cells - 1) + " of " + cells + " keys equal to keys-sample.tsv's on " + lines
                + " lines; 1 mismatches; catalog counts missing from its header: []", failed.get(at));
        assertEquals(twelve, failed.get(at + 1));
        assertTrue(failed.get(at + 2).startsWith("line 4 bet state 0, " + BundleFixture.HILO + ": the file says "),
                failed.get(at + 2));
        assertEquals("failed", status.get("D2"));
        // a catalog count missing from the header still fails D2
        Path dropped = BundleFixture.write(tmp.resolve("dropped"));
        writeKeys(dropped, unknown, null, "Red Seven (Red 7)");
        List<String> missing = checks(dropped, tmp.resolve("runDropped"), status);
        assertEquals(List.of("D2\t" + 38 * lines + " of " + 38 * lines + " keys equal to keys-sample.tsv's on " + lines
                + " lines; 0 mismatches; catalog counts missing from its header: [Red Seven (Red 7)]", twelve),
                d2Lines(missing));
        assertEquals("failed", status.get("D2"));
    }

    /**
     * B4's header lists the registered counts and their own betting variants, so a count the
     * bundle's strategy files play or bet with must have a column (sim/QUESTIONS.md item 33). The
     * fixture's two Hi-Lo files renamed to play "F9 test" and bet "F9 test + vector" (their tags
     * unchanged): with the two columns where B4 puts them, D2 passes and checks.txt is as on any
     * bundle; with the two misnamed, they are left out as no count's, and D2 fails, a note naming
     * each count of the files the header lacks, once, right after the two D2 lines; with neither
     * column, the same notes, before a changed cell's note. Planted on 19 October 2026 in a scratch
     * copy of sim/ outside the worktree, one at a time; each failed this test: the files' counts not
     * required; their absence named but not failing D2; the notes not written; the notes written
     * after the cells' notes; each count named once for each file naming it; the betcount not looked
     * for; the counts named in the first line's list of catalog counts missing. The reader's plants
     * are in KeysSampleTest's comment.
     */
    @Test
    void checksTxtNamesAFilesCountTheHeaderLacks() throws Exception {
        Map<String, String> status = new LinkedHashMap<>();
        Path dir = BundleFixture.write(tmp.resolve("f9"));
        for (int fold = 0; fold < 2; fold++) {
            String path = "strategy-hi-lo-hi-lo-lite-p80-f" + fold + ".txt";
            List<String> lines = new ArrayList<>(BundleFixture.hiLo("first", fold).lines());
            lines.replaceAll(l -> l.startsWith("playcount\t") ? "playcount\tF9 test"
                    : l.startsWith("betcount\t") ? "betcount\tF9 test + vector" : l);
            BundleFixture.write(dir.resolve(path), lines);
            BundleFixture.reseal(dir, path + "\t" + BundleFixture.HILO + "\t80\t" + fold + "\tfirst");
        }
        Bundle b = new Bundle(dir, Plants.NONE);
        Catalog c = KeysSampleTest.catalog();
        List<KeysSample.Column> f9 = List.of(KeysSample.column("F9 test", c, b.strategies.values(), false),
                KeysSample.column("F9 test + vector", c, b.strategies.values(), false));
        String none = "D2\tcolumns left out, since no count of the catalog or of the bundle's files has the name: 0";
        List<String> notes = List.of(KeysSample.unlistedNote("F9 test"), KeysSample.unlistedNote("F9 test + vector"));
        assertEquals("count F9 test, which a strategy file of the bundle plays or bets with: no column of"
                + " keys-sample.tsv's header has this name, so D2 holds none of its keys", notes.get(0));
        // the two columns there
        int lines = writeKeys(dir, f9, List.of(), null, null);
        List<String> named = checks(dir, tmp.resolve("runNamed"), status);
        assertEquals(List.of("D2\t" + 41 * lines + " of " + 41 * lines + " keys equal to keys-sample.tsv's on " + lines
                + " lines; 0 mismatches; catalog counts missing from its header: []", none), d2Lines(named));
        assertFalse(named.stream().anyMatch(notes::contains), named.toString());
        assertEquals("passed", status.get("D2"));
        // the two misnamed, with made-up cells
        String d2 = "D2\t" + 39 * lines + " of " + 39 * lines + " keys equal to keys-sample.tsv's on " + lines
                + " lines; 0 mismatches; catalog counts missing from its header: []";
        assertEquals(lines, writeKeys(dir, List.of(), List.of("F9 Test", "F9 Test + vector"), null, null));
        List<String> misnamed = checks(dir, tmp.resolve("runMisnamed"), status);
        int at = misnamed.indexOf(d2);
        assertEquals(List.of(d2, "D2\tcolumns left out, since no count of the catalog or of the bundle's files has the"
                + " name: 2\tF9 Test\tF9 Test + vector", notes.get(0), notes.get(1)), misnamed.subList(at, at + 4));
        assertTrue(misnamed.get(at + 4).startsWith("D4\t"), misnamed.get(at + 4));
        assertEquals("failed", status.get("D2"));
        // neither column, and a catalog key changed: the notes come before the cell's
        writeKeys(dir, List.of(), List.of(), BundleFixture.HILO, null);
        List<String> absent = checks(dir, tmp.resolve("runAbsent"), status);
        String changed = "D2\t" + (39 * lines - 1) + " of " + 39 * lines + " keys equal to keys-sample.tsv's on " + lines
                + " lines; 1 mismatches; catalog counts missing from its header: []";
        at = absent.indexOf(changed);
        assertEquals(List.of(changed, none, notes.get(0), notes.get(1)), absent.subList(at, at + 4));
        assertTrue(absent.get(at + 4).startsWith("line 4 bet state 0, " + BundleFixture.HILO + ": the file says "),
                absent.get(at + 4));
        assertTrue(absent.get(at + 5).startsWith("D4\t"), absent.get(at + 5));
        assertEquals("failed", status.get("D2"));
    }

    /**
     * Writes keys-sample.tsv for the fixture bundle from the simulator's own keys (KeysSampleTest's
     * lines): a bet line and a play line for (9, 9) against a 6 at every fourth state. The columns
     * {@code unknown} names, which no count of the catalog or of the bundle's files has, go after the
     * null count, all but the last two, which go at the end, with made-up cells; the column
     * {@code change} names, if any, is one more on the first bet line, and the column {@code drop}
     * names, if any, is not written. Returns the number of lines after the header.
     */
    static int writeKeys(Path dir, List<String> unknown, String change, String drop) throws Exception {
        return writeKeys(dir, List.of(), unknown, change, drop);
    }

    /**
     * As above, with the columns {@code known} gives, whose cells are the simulator's own: the first
     * after the null count, where B4 puts the registered counts, before any of {@code unknown}, and
     * the rest after the ace variants, where it puts their betting variants.
     */
    static int writeKeys(Path dir, List<KeysSample.Column> known, List<String> unknown, String change, String drop)
            throws Exception {
        Bundle b = new Bundle(dir, Plants.NONE);
        States s = b.states();
        Catalog c = KeysSampleTest.catalog();
        List<String> knownNames = known.stream().map(KeysSample.Column::name).toList();
        List<String> natural = List.of(KeysSampleTest.header(c, knownNames.toArray(new String[0])).split("\t", -1));
        int afterNull = 8 + 31;
        int end = Math.max(0, unknown.size() - 2);
        List<String> head = new ArrayList<>(natural.subList(0, afterNull));
        head.addAll(knownNames.subList(0, Math.min(1, knownNames.size())));
        head.addAll(unknown.subList(0, end));
        head.addAll(natural.subList(afterNull, afterNull + 8));
        head.addAll(knownNames.subList(Math.min(1, knownNames.size()), knownNames.size()));
        head.addAll(unknown.subList(end, unknown.size()));
        head.remove(drop);
        Map<String, String> made = new HashMap<>();
        for (String u : unknown) {
            made.put(u, Integer.toString(made.size() - 5));
        }
        List<String> text = new ArrayList<>(List.of("# keys-sample.tsv: SHOE_SPEC.md B4, format 1", "# sample seed 20261009",
                String.join("\t", head)));
        for (int i = 0; i < s.n; i += 4) {
            text.add(KeysSampleTest.arrange(natural, head, KeysSampleTest.line(c, s, "bet", i, 0, 0, 0, known), made));
            text.add(KeysSampleTest.arrange(natural, head, KeysSampleTest.line(c, s, "play", i, 9, 9, 6, known), made));
        }
        if (change != null) {
            String[] f = text.get(3).split("\t", -1);
            int at = head.indexOf(change);
            f[at] = Integer.toString(Integer.parseInt(f[at]) + 1);
            text.set(3, String.join("\t", f));
        }
        BundleFixture.write(dir.resolve("keys-sample.tsv"), text);
        BundleFixture.reseal(dir, "keys-sample.tsv\t-\t-\t-\t-");
        return text.size() - 3;
    }

    /** The exact checks on the bundle, as p2 and the pilot run them; checks.txt's lines. */
    static List<String> checks(Path dir, Path out, Map<String, String> status) throws Exception {
        Bundle b = new Bundle(dir, Plants.NONE);
        States s = b.states();
        status.clear();
        Sim.exactChecks(b, s, Sim.p2For(b, s, 2, Plants.NONE), Path.of("counting-systems.json"), Plants.NONE, out, status);
        return Files.readAllLines(out.resolve("checks.txt"));
    }

    static List<String> d2Lines(List<String> checks) {
        return checks.stream().filter(l -> l.startsWith("D2\t")).toList();
    }

    static String[] concat(String[] a, String... b) {
        String[] c = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
}
