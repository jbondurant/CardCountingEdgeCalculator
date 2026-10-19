import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The page look at the registered counts (PLAN_COMPUTE.md 6.3, row 7 of 7.2; INTEGRATION.md open
 * item 4): one run on the main state file, made through CustomCounts.lookOnMain with its one ledger
 * line, "page figures: wide plans, per-key tables, simulator export", that adds descriptive figures
 * to the registered rows and changes no claim: the chooser of the result paragraphs reads step 7's
 * and step 8's outputs only.
 *
 * What it makes, in OUT:
 * - run C (5.3): step 7's registered rows scored again at first decisions with the state sink on,
 *   OUT/run-c.ser (KeyEdgesRun report --rows); with the wide accumulator, OUT/wide-first.ser
 *   (WideReport's format), with step 7's resamples for the headline rows and points for the rest;
 * - the export of their first decisions, F49 and F51 (SIM_CHECK.md section 2), OUT/export-first;
 * - run D: step 8's fit.ckpt replayed as in 6.1 (FullIndexReplay, its look set), every folds item
 *   checked against step 8's part, with the wide plans (OUT/replay/wide.ser), the sink
 *   (OUT/run-d/run-b.ser, KeyEdgesRun report --full) and the full-play export, F50 and F52
 *   (OUT/export-full), reading the given tree store read-only.
 *
 * What it refuses, every refusal before the ledger line unless said: a registration not the one
 * home's (the count-design worktree's custom-counts.json and custom-counts-fullplay.json, given as
 * --count-design DIR), either not committed as it stands, a ledger with lines not committed or a
 * look at another registration (CustomCounts.checkOnMain on both); a ledger without step 7's line
 * (the registration's blob) and step 8's (the full-play registration's), or already holding a page
 * look (unless --resume, which then needs it, committed); step 7's save or step 8's run made on
 * other states or another registration's blob; step 8's run not the one these classes replay
 * (FullIndexReplay.differences on its identity); a tree store of other states; a state file that is
 * not the main file; an OUT that exists (unless --resume). After the line, before writing any
 * output: every point estimate and per-key table of run C must be step 7's to the bit, and every
 * item of run D step 8's results.ser's to the bit (Step8), else it stops naming the first. The runs
 * folder's budget (DiskBudget) is checked among the refusals before the line, for BYTES, what the
 * whole look writes; the replay's records are let go once the look is done (FullIndexReplay.letGo).
 *
 * Usage (the machine to itself; about 1.5 to 2 h on the real files, PLAN_COMPUTE.md 6.3):
 *   java -Xmx12g -cp CLASSES:lib/mongo-java-driver-3.12.14.jar PageLook --states runs/montreal-80.states
 *     --count-design .worktrees/count-design --saved runs/report-ceiling-97bf834.ser
 *     --fullplay-run runs/custom-count/fullplay-main-97bf834
 *     --trees-read-only runs/full-index/full-c06c061/trees.bin --out runs/page-look-SHA [--threads 7] [--resume]
 * Then commit the count-design ledger's new line there, as every look's.
 */
final class PageLook {

    /** The ledger line's words (PLAN_COMPUTE.md 6.3). */
    static final String WHAT = "page figures: wide plans, per-key tables, simulator export";
    /** Step 7's and step 8's ledger lines, by how they begin (CeilingReport.run, FullIndexRun.run). */
    static final String STEP7 = "ComparisonReport --published";
    static final String STEP8 = "FullIndexRun started";
    /** What a look writes, its replay's records among them: 0e0920d's wrote 0.80 GB, its first attempt's aside. */
    static final long BYTES = 1_000_000_000L;

    private PageLook() {
    }

    /** What the look is given. */
    static final class Options {
        Path states;
        /** The count-design worktree: the registrations' and the ledger's one home. */
        Path countDesign;
        /** Step 7's save (CeilingReport.save), and step 8's run directory (FullIndexRun with the full-play registration). */
        Path saved;
        Path fullplayRun;
        /** The tree store the replay reads, read-only (c06c061's on the real run). */
        Path trees;
        Path out;
        int threads = 7;
        boolean resume;
        /** The rows exported, NAME@P: at first decisions from run C, at full play from the replay; SIM_CHECK.md's by default. */
        List<String> firstRows;
        List<String> fullRows;
        String[] plans = {"same-8"};
        /** Passed to the replay, for a test to make small batches. */
        double batchBytes;
        /** Further hooks for the replay, after the look's own: for a test to see each item's strategies. */
        final List<FullIndexReplay.Hook> hooks = new ArrayList<>();
    }

    /**
     * SIM_CHECK.md's part 2 figures from step 7 (first decisions) or step 8 (full play), as
     * NAME@P: the registered counts' rows the page look exports. The chart's figures also name
     * step 7, whose save holds the chart's reference row, but they are part 1's and part 1's
     * bundle exports them; asked for here they are refused, since step 7's Results hold no chart
     * row (the page look of 17 October stopped on it).
     */
    static List<String> registeredRows(String source) {
        List<String> out = new ArrayList<>();
        for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
            String row = f.entry() + "@" + f.pen;
            if (f.part == 2 && f.source.equals(source) && !out.contains(row)) {
                out.add(row);
            }
        }
        return out;
    }

    /** One line of an identity that starts with the given words, or null. */
    static String line(String identity, String start) {
        for (String l : identity.split("\n")) {
            if (l.startsWith(start)) {
                return l;
            }
        }
        return null;
    }

    /** The whole number after the words given in an identity's line that holds them. */
    static int number(String identity, String words) {
        for (String l : identity.split("\n")) {
            int at = l.indexOf(words);
            if (at >= 0) {
                String rest = l.substring(at + words.length()).trim();
                int end = 0;
                while (end < rest.length() && Character.isDigit(rest.charAt(end))) {
                    end++;
                }
                return Integer.parseInt(rest.substring(0, end));
            }
        }
        throw new IllegalStateException("the identity names no \"" + words + "\":\n" + identity);
    }

    static void refuse(boolean bad, String why) {
        if (bad) {
            throw new IllegalStateException(why);
        }
    }

    /** The ledger's lines, the header left out, as fields. */
    static List<String[]> ledger(Path ledger) throws IOException {
        List<String[]> out = new ArrayList<>();
        for (String l : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
            if (!l.isEmpty() && !l.equals(CustomCounts.LEDGER_HEADER)) {
                out.add(l.split("\t", -1));
            }
        }
        return out;
    }

    /** Whether the ledger holds a line of this blob whose description starts so. */
    static boolean holds(List<String[]> lines, String blob, String start) {
        for (String[] f : lines) {
            if (f.length >= 4 && f[1].equals(blob) && f[3].startsWith(start)) {
                return true;
            }
        }
        return false;
    }

    /** What the checks before the ledger line found, for the run after it. */
    static final class Checked {
        CustomCounts.Listing registration;
        CustomCounts.Listing fullPlay;
        String blob;
        String fullBlob;
        CeilingReport.Saved saved;
        int resamples7;
        /** Step 8's run as its identity gives it: the counts file as it was named, shoes, valued states, resamples, threads, penetrations. */
        String step8;
        String fullFile;
        int shoes;
        int size;
        int resamples8;
        int threads8;
        int[] pens;
    }

    /**
     * Every refusal that comes before the ledger line (see the class comment), reading no state:
     * the registrations, the ledger, step 7's save, step 8's run and the tree store, against each
     * other and against the state file's name, size and time.
     */
    static Checked check(Options o, String commit) throws Exception {
        refuse(o.states == null || o.countDesign == null || o.saved == null || o.fullplayRun == null || o.trees == null
                || o.out == null, "the page look needs --states, --count-design, --saved, --fullplay-run, "
                + "--trees-read-only and --out");
        Checked c = new Checked();
        Path home = o.countDesign.toAbsolutePath().normalize();
        c.registration = CustomCounts.read(home.resolve(CustomCounts.REGISTERED));
        c.fullPlay = CustomCounts.read(home.resolve(CustomCounts.FULL_PLAY));
        refuse(!c.registration.registered || !CustomCounts.FULL_PLAY.equals(c.registration.fullPlayFile)
                        || !CustomCounts.REGISTERED.equals(c.fullPlay.registeredWith),
                home + " does not hold a registration and the full-play registration it names");
        refuse(!CustomCounts.isMain(o.states), o.states + " is not the main state file (seed " + CustomCounts.MAIN_SEED
                + "); the page look scores the registered counts on the main file, where steps 7 and 8 did");
        // The registration and the ledger committed as they stand, no look at another registration:
        // the checks lookOnMain makes, on both registrations, before anything else.
        c.blob = CustomCounts.checkOnMain(c.registration);
        c.fullBlob = CustomCounts.checkOnMain(c.fullPlay);
        Path ledgerFile = CustomCounts.ledgerOf(c.registration);
        List<String[]> lines = ledger(ledgerFile);
        refuse(!holds(lines, c.blob, STEP7), ledgerFile + " holds no line of step 7 (" + STEP7 + ", blob " + c.blob
                + "); the page look comes after steps 7 and 8, their lines committed (PLAN_COMPUTE.md 6.3)");
        refuse(!holds(lines, c.fullBlob, STEP8), ledgerFile + " holds no line of step 8 (" + STEP8 + ", blob "
                + c.fullBlob + "); the page look comes after steps 7 and 8, their lines committed (PLAN_COMPUTE.md 6.3)");
        boolean made = holds(lines, c.blob, WHAT);
        refuse(made && !o.resume, ledgerFile + " holds the page look already: it is made once. --resume finishes an "
                + "interrupted one in OUT without a second line");
        refuse(!made && o.resume, ledgerFile + " holds no page look to resume; commit its line first, or start it anew");
        Path out = o.out.toAbsolutePath().normalize();
        refuse(!o.resume && Files.exists(out), out + " exists; the page look writes a new directory");
        refuse(o.resume && !Files.isRegularFile(out.resolve("look.txt")), out + " holds no page look to resume");
        refuse(out.getParent() == null || !Files.isDirectory(out.getParent()), "the directory of " + out
                + " does not exist");
        // Step 7's save: these states, this registration's blob.
        c.saved = CeilingReport.readSaved(o.saved);
        String states = FullIndexRun.stateFile(o.states);
        refuse(!c.saved.identity.contains("\n" + states + "\n"), "step 7's save was not scored on " + o.states
                + " as it is now:\n" + c.saved.identity);
        refuse(!c.saved.identity.contains("\ncounts " + c.registration.file.toAbsolutePath().normalize() + ", blob "
                + c.blob + "\n"), "step 7's save names another registration than " + c.registration.file + ", blob "
                + c.blob + ":\n" + c.saved.identity);
        c.resamples7 = number(c.saved.identity, "resamples ");
        refuse(c.saved.custom.size() != c.registration.entries.size(), "step 7's save holds " + c.saved.custom.size()
                + " counts, the registration " + c.registration.entries.size());
        // Step 8's run: these states, the full-play registration's blob, and the run these classes replay.
        c.step8 = FullIndexCheckpoint.source(o.fullplayRun.toAbsolutePath().normalize()).identity;
        String counts = line(c.step8, "counts from ");
        String tail = ", blob " + c.fullBlob + ", each also bet on its own value";
        refuse(counts == null || !counts.endsWith(tail), "step 8's run names another registration than "
                + c.fullPlay.file + ", blob " + c.fullBlob + ": " + counts);
        c.fullFile = counts.substring("counts from ".length(), counts.length() - tail.length());
        refuse(!Paths.get(c.fullFile).toAbsolutePath().normalize().equals(c.fullPlay.file.toAbsolutePath().normalize()),
                "step 8 scored " + c.fullFile + ", not the one home's " + c.fullPlay.file);
        c.shoes = number(c.step8, "shoes ");
        c.size = number(c.step8, "valued states ");
        c.resamples8 = number(c.step8, "resamples ");
        c.threads8 = number(c.step8, "threads ");
        String pens = line(c.step8, "penetrations ");
        c.pens = FullIndexRun.pens(pens.substring("penetrations ".length()).replaceAll("[\\[\\] ]", ""));
        String expected = FullIndexRun.identity(commit, o.states, c.shoes, c.size, c.resamples8, c.threads8,
                c.fullPlay.counts(), c.pens, c.fullFile + ", blob " + c.fullBlob);
        String differ = FullIndexReplay.differences(c.step8, expected, c.fullPlay.counts());
        refuse(!differ.isEmpty(), "step 8's run is not the one these classes replay on " + o.states
                + ", apart from its commit; it differs in:\n" + differ);
        String store = TreeStore.identityOf(o.trees);
        refuse(store == null || !Files.isRegularFile(o.trees), "no tree store at " + o.trees + " to read");
        String stateLines = states + "\nstates " + c.size;
        refuse(!store.substring(store.indexOf('\n') + 1).equals(stateLines), "the tree store " + o.trees
                + " holds the trees of other states:\n" + store);
        // The whole look's files, its replay's among them, counted here, before the ledger line.
        DiskBudget.require(o.out, Math.max(0, BYTES - DiskBudget.bytes(o.out)), DiskBudget.budget(null), "PageLook");
        return c;
    }

    /** What the look made, by where it put it. */
    static final class Made {
        Path out;
        Path runC;
        Path wideFirst;
        Path exportFirst;
        Path replay;
        Path runD;
        Path exportFull;
        List<SystemScorer.Result> first;
        FullIndexReplay.Assembled full;
        /**
         * Why the full-play export wrote no bundle, or null: a row that cannot be exported (a fold
         * whose strategy groups are not the refit's, or whose files fail their check on its values,
         * StrategyExport.ReplayExport) is refused at the replay's end, after wide.ser and the sink's
         * file are written, so the look keeps those and says this. A fold whose split hands were
         * valued under another ranking than its dealt hand plays is exported with split lines
         * (SHOE_SPEC.md B2.4), not refused.
         */
        String exportFullRefused;
    }

    /**
     * The look: the checks, the one ledger line (none on --resume, whose line is committed), then
     * the states read, run C checked against step 7 and written, its export, and the replay of step
     * 8 with its checks, wide plans, sink and export. commit names the classes in the ledger.
     */
    static Made run(Options o, String commit, PrintStream log) throws Exception {
        Checked c = check(o, commit);
        Path out = o.out.toAbsolutePath().normalize();
        if (!o.resume) {
            // The one look, through the same checks as every main-file look (lookOnMain calls checkOnMain).
            String blob = CustomCounts.lookOnMain(c.registration, commit, WHAT + " (PageLook into " + out + ")");
            refuse(!blob.equals(c.blob), "the registration's blob moved during the checks");
            Files.createDirectories(out);
            Files.write(out.resolve("look.txt"), identity(o, c, commit).getBytes(StandardCharsets.UTF_8));
        }
        log.printf("PageLook %s: %s, blob %s; step 8's run %s; out %s%s%n", commit, c.registration.file, c.blob,
                o.fullplayRun, out, o.resume ? ", resumed" : "");
        Made m = new Made();
        m.out = out;
        m.runC = out.resolve("run-c.ser");
        m.wideFirst = out.resolve("wide-first.ser");
        m.exportFirst = out.resolve("export-first");
        m.replay = out.resolve("replay");
        m.runD = out.resolve("run-d");
        m.exportFull = out.resolve("export-full");
        ScoringStates st = FullIndexPilot.load(o.states, c.shoes);
        refuse(st.size != c.size, o.states + " holds " + st.size + " valued states, step 8's run " + c.size);
        if (!Files.exists(m.exportFirst)) {
            m.first = firstDecisions(o, c, st, m, commit, log);
        }
        try {
            m.full = replay(o, c, m, commit, log);
        } catch (IllegalStateException e) {
            if (e.getMessage() == null || !e.getMessage().startsWith("--export writes no bundle")) {
                throw e;
            }
            m.exportFullRefused = e.getMessage();
            log.println("PageLook: the full-play export wrote no bundle; the replay's wide.ser and run D's collector "
                    + "are written. " + e.getMessage());
        }
        log.printf("PageLook: wrote %s, %s, %s, %s, %s and %s; commit the ledger's line in %s%n", m.runC, m.wideFirst,
                m.exportFirst, m.replay.resolve("wide.ser"), m.runD.resolve(ReplayHooks.Sink.FILE), m.exportFull,
                o.countDesign);
        return m;
    }

    /** What the look is: its commit, the states, the registrations and their blobs, step 7's save and step 8's run. */
    static String identity(Options o, Checked c, String commit) throws IOException {
        return "PageLook\ncommit " + commit + "\n" + FullIndexRun.stateFile(o.states) + "\ncounts "
                + c.registration.file + ", blob " + c.blob + "\nfull play " + c.fullPlay.file + ", blob " + c.fullBlob
                + "\nstep 7 " + o.saved.toAbsolutePath().normalize() + ", sha256 " + KeyEdgesRun.sha256(o.saved)
                + "\nstep 8 " + o.fullplayRun.toAbsolutePath().normalize() + "\ntrees " + o.trees.toAbsolutePath().normalize()
                + "\n";
    }

    /** A Result's entry of this name, or null. */
    static SystemScorer.Entry entry(SystemScorer.Result r, String name) {
        List<SystemScorer.Entry> all = new ArrayList<>(r.entries);
        all.addAll(r.fullEntries);
        for (SystemScorer.Entry e : all) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        return null;
    }

    /**
     * Every entry of now against the same-named entry of was at the penetrations given: its point
     * estimate (every metric) and, where both have one, its per-key table, to the bit. The first
     * difference, or null.
     */
    static String sameBits(SystemScorer.Result now, SystemScorer.Result was, int[] pens, String source) {
        List<SystemScorer.Entry> all = new ArrayList<>(now.entries);
        all.addAll(now.fullEntries);
        for (SystemScorer.Entry e : all) {
            SystemScorer.Entry w = entry(was, e.name);
            if (w == null) {
                return e.name + ": " + source + " holds no such entry";
            }
            for (int p : pens) {
                if (!KeyEdgesRun.sameBits(e.estimate[p], w.estimate[p])) {
                    return e.name + " at " + SystemScorer.PENETRATIONS[p] + ": its estimate is not " + source + "'s to the bit";
                }
                if (e.keys[p] != null && w.keys[p] != null && !KeyEdgesRun.sameBits(e.keys[p], w.keys[p])) {
                    return e.name + " at " + SystemScorer.PENETRATIONS[p] + ": its per-key table is not " + source
                            + "'s to the bit";
                }
            }
        }
        return null;
    }

    /**
     * Run C and its export: each registered count scored again at first decisions as step 7 scored
     * it (CeilingReport.scoreCustom: its betting variant, bet on its own value too), the headline
     * counts with step 7's resamples and the rest at points, the state sink and the wide plans on;
     * every entry held to step 7's save to the bit before anything is written; then run-c.ser,
     * wide-first.ser (WideReport.check first) and the export of the first-decision rows.
     */
    static List<SystemScorer.Result> firstDecisions(Options o, Checked c, ScoringStates st, Made m, String commit,
                                                    PrintStream log) throws Exception {
        long t0 = System.nanoTime();
        AllStates all = AllStates.replay(StateStore.header(o.states), st.shoes, StateRun.montreal(), BasicStrategy.chart(),
                st);
        KeyEdges.Collector sink = new KeyEdges.Collector(st, 0);
        SystemScorer full = scorer(st, c.resamples7, all, sink);
        SystemScorer points = scorer(st, 0, all, sink);
        List<SystemScorer.Result> out = new ArrayList<>();
        for (int k = 0; k < c.registration.entries.size(); k++) {
            CustomCounts.Entry e = c.registration.entries.get(k);
            SystemScorer sc = "headline".equals(e.role) ? full : points;
            SystemScorer.Result r = sc.score(e.count, CountKeys.bettingVariant(e.count), null, null, o.threads);
            List<String> bad = new ArrayList<>();
            for (SystemScorer.Entry x : r.entries) {
                bad.addAll(SystemScorer.p8Check(x));
            }
            refuse(!bad.isEmpty(), "run C: the wide plans fail check 1 of PLAN_COMPUTE.md 3.5:\n" + String.join("\n", bad));
            String differ = sameBits(r, c.saved.custom.get(k), new int[]{0, 1, 2}, "step 7's save");
            refuse(differ != null, "run C differs from step 7, so nothing of it is written: " + differ);
            out.add(r);
            log.printf("run C: %s scored again, %s, every figure step 7's to the bit%n", e.count.name,
                    sc == full ? c.resamples7 + " resamples" : "points");
        }
        String id = identity(o, c, commit) + "run C: the registered rows at first decisions, the state sink and the wide "
                + "plans on\n";
        Path tmp = m.out.resolve("run-c.ser.new");
        sink.save(tmp, id);
        settle(tmp, m.runC);
        List<SystemScorer.Entry> others = new ArrayList<>(points.references());
        List<WideReport.Row> rows = WideReport.rows(out, others);
        WideReport.Saved ws = new WideReport.Saved();
        ws.identity = id;
        ws.plans = SystemScorer.WIDE_PLANS;
        ws.rows = rows;
        List<String> bad = WideReport.check(ws);
        refuse(!bad.isEmpty(), "run C's wide figures fail WideReport's checks:\n" + String.join("\n", bad));
        tmp = m.out.resolve("wide-first.ser.new");
        WideReport.save(tmp, id, SystemScorer.WIDE_PLANS, rows);
        settle(tmp, m.wideFirst);
        List<StrategyExport.Wanted> wanted = new ArrayList<>();
        for (String row : o.firstRows == null ? registeredRows("step 7") : o.firstRows) {
            int at = row.lastIndexOf('@');
            int p = StrategyExport.penIndex(Integer.parseInt(row.substring(at + 1)));
            wanted.add(StrategyExport.wanted(out, row.substring(0, at), new int[]{p}));
        }
        List<CountKeys.Count> own = new ArrayList<>(c.registration.counts());
        StrategyExport.export(m.exportFirst, points, StateStore.header(o.states), wanted, o.plans,
                StrategyExport.keyCounts(CountKeys.catalog(), own), StrategyExport.SAMPLE_SEED, log);
        log.printf("run C and its export: %.1f s%n", (System.nanoTime() - t0) / 1e9);
        return out;
    }

    /** A scorer as step 7's (CeilingReport.run), its rows bet on their own value too, with the wide plans and the sink. */
    static SystemScorer scorer(ScoringStates st, int resamples, AllStates all, KeyEdges.Collector sink) {
        SystemScorer sc = new SystemScorer(st, new Bootstrap(st.shoes, resamples, Bootstrap.SEED), SystemScorer.M_PLAY);
        sc.ownValueRows = true;
        sc.wide = SystemScorer.WIDE_PLANS;
        sc.roundStarts = all.roundStarts(st);
        sc.stateSink = sink;
        return sc;
    }

    /**
     * Moves a new file into place; on a resumed look whose earlier start wrote the file already,
     * requires the two equal byte for byte and keeps the first.
     */
    static void settle(Path tmp, Path file) throws IOException {
        if (Files.exists(file)) {
            boolean same = Arrays.equals(Files.readAllBytes(tmp), Files.readAllBytes(file));
            Files.delete(tmp);
            refuse(!same, file + " was written by an earlier start and differs from what this one makes");
            return;
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Run D: step 8's fit.ckpt replayed (FullIndexReplay, as 6.1 replays c06c061's), its folds items
     * only, each checked against step 8's part by the replay and against step 8's results.ser to the
     * bit by Step8 before its record is kept; the wide plans, the sink into run-d and the full-play
     * rows' export.
     */
    static FullIndexReplay.Assembled replay(Options o, Checked c, Made m, String commit, PrintStream log)
            throws Exception {
        FullIndexReplay.Options r = new FullIndexReplay.Options();
        r.fitFrom = o.fullplayRun.toAbsolutePath().normalize();
        r.states = o.states;
        r.out = m.replay;
        r.trees = o.trees;
        r.readOnlyTrees = true;
        r.countsFile = Paths.get(c.fullFile);
        r.pens = c.pens;
        r.shoes = c.shoes;
        r.resamples = c.resamples8;
        r.threads = c.threads8;
        r.resume = o.resume && Files.exists(m.replay.resolve("run.id"));
        r.foldsOnly = true;
        r.wide = true;
        r.sink = m.runD;
        r.export = m.exportFull;
        r.exportRows = new ArrayList<>(o.fullRows == null ? registeredRows("step 8") : o.fullRows);
        r.exportPlans = o.plans.clone();
        r.look = c.fullBlob;
        r.batchBytes = o.batchBytes;
        // Counted in the look's own check, before its ledger line, so never refused after it.
        r.budget = Long.MAX_VALUE;
        r.hooks.add(new Step8(CeilingReport.read(r.fitFrom.resolve("results.ser"))));
        r.hooks.addAll(o.hooks);
        return FullIndexReplay.run(r, commit, log);
    }

    /**
     * Holds each replayed item to step 8's results.ser to the bit (6.3: the look's point estimates
     * must be step 7's and step 8's before it writes anything): every entry's estimate and per-key
     * table at the item's penetration. The replay's own check against step 8's part allows 1e-12;
     * this one allows nothing, and stops the replay before the item is kept.
     */
    static final class Step8 implements FullIndexReplay.Hook {
        final CeilingReport.Published step8;

        Step8(CeilingReport.Published step8) {
            this.step8 = step8;
        }

        @Override
        public String name() {
            return "step 8 to the bit";
        }

        @Override
        public Serializable after(FullIndexReplay.Item item, SystemScorer scorer, SystemScorer.Result scored,
                                  List<FullIndexFit.Strategy> two) {
            SystemScorer.Result was = null;
            for (SystemScorer.Result r : step8.results) {
                was = r.count.name.equals(item.name) ? r : was;
            }
            refuse(was == null, item + ": step 8's results.ser holds no " + item.name);
            String differ = sameBits(scored, was, new int[]{item.pen}, "step 8's results.ser");
            refuse(differ != null, item + " differs from step 8, so the page look keeps nothing of it: " + differ);
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        Options o = new Options();
        for (int a = 0; a < args.length; a++) {
            switch (args[a]) {
                case "--states": o.states = Paths.get(args[++a]); break;
                case "--count-design": o.countDesign = Paths.get(args[++a]); break;
                case "--saved": o.saved = Paths.get(args[++a]); break;
                case "--fullplay-run": o.fullplayRun = Paths.get(args[++a]); break;
                case "--trees-read-only": o.trees = Paths.get(args[++a]); break;
                case "--out": o.out = Paths.get(args[++a]); break;
                case "--threads": o.threads = Integer.parseInt(args[++a]); break;
                case "--resume": o.resume = true; break;
                default:
                    throw new IllegalArgumentException("usage: PageLook --states FILE --count-design DIR --saved STEP7.ser "
                            + "--fullplay-run STEP8_DIR --trees-read-only TREES --out DIR [--threads T] [--resume]; not "
                            + args[a]);
            }
        }
        Made m = run(o, FullIndexRun.commit(), System.out);
        if (m.exportFullRefused != null) {
            System.exit(1);
        }
        FullIndexReplay.letGo(m.full, System.out);
    }
}
