import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What the replay of PLAN_COMPUTE.md 6.1 makes of each item besides its Result: the wide plans'
 * figures (--wide), each state's values for the edge at each count (--sink, run B of 5.3), and the
 * simulator's full-play export (--export, 1.2). Each keeps what it needs of an item in the item's
 * record, and puts its output together at the end from the records alone, so a replay killed and
 * resumed writes what an uninterrupted one writes (FullIndexReplayTest).
 */
final class ReplayHooks {

    private ReplayHooks() {
    }

    /**
     * --wide: every item scored with SystemScorer.WIDE_PLANS beside its existing figures, which
     * they leave as they were (CeilingScoringTest), so each item still equals the source's part to
     * the bit; ResultDiff lists the wide fields as added, since the source holds them null. Each
     * item's P8 must be its 1-8 same schedule and own ramp to the bit, on the estimate and every
     * resample (SystemScorer.p8Check, check 1 of 3.5), or the replay stops before keeping it. The
     * wide figures ride in the item's Result, so the hook keeps nothing of its own. At the end,
     * wide.ser in the replay's directory: every replayed count's rows, the oracle row
     * (SystemScorer.references) and the bound rows (SystemScorer.bounds), scored with the wide
     * plans on, written through WideReport.save under the replay's identity (which names the
     * source, its fit.ckpt's SHA-256 and the source's own identity), and only if WideReport.check
     * passes.
     */
    static final class WidePlans implements FullIndexReplay.Hook {
        private SystemScorer scorer;

        @Override
        public String name() {
            return "wide";
        }

        /** The plans on, and for those that leave the table each state's shoe dealt again from the state file's seed. */
        @Override
        public void start(SystemScorer scorer, FullIndexReplay.Options o) throws Exception {
            this.scorer = scorer;
            scorer.wide = SystemScorer.WIDE_PLANS;
            if (SystemScorer.anyExit(scorer.wide)) {
                ScoringStates st = scorer.st;
                AllStates dealt = AllStates.replay(StateStore.header(o.states), st.shoes, st.rules, BasicStrategy.chart(),
                        st);
                scorer.roundStarts = dealt.roundStarts(st);
            }
        }

        @Override
        public Serializable after(FullIndexReplay.Item item, SystemScorer scorer, SystemScorer.Result scored,
                                  List<FullIndexFit.Strategy> two) {
            List<String> bad = new ArrayList<>();
            List<SystemScorer.Entry> all = new ArrayList<>(scored.entries);
            all.addAll(scored.fullEntries);
            for (SystemScorer.Entry e : all) {
                // p8Check passes an entry with no wide figures, so their absence is refused here.
                boolean scoredHere = e.estimate[item.pen] != null;
                if (scoredHere && (e.widePlans == null || e.wide == null || e.wide[item.pen] == null)) {
                    bad.add(e.name + " at " + SystemScorer.PENETRATIONS[item.pen] + " holds no wide figures");
                }
                bad.addAll(SystemScorer.p8Check(e));
            }
            if (!bad.isEmpty()) {
                throw new IllegalStateException(item + ": the wide plans fail check 1 of PLAN_COMPUTE.md 3.5, so the "
                        + "replay stops and keeps nothing of it:\n" + String.join("\n", bad));
            }
            return null;
        }

        @Override
        public void finish(FullIndexReplay.Assembled all) throws Exception {
            List<SystemScorer.Result> results = new ArrayList<>();
            for (SystemScorer.Result r : all.results()) {
                if (r != null) {
                    results.add(r);
                }
            }
            List<SystemScorer.Entry> others = new ArrayList<>(scorer.references());
            others.addAll(scorer.bounds(perfect(all)));
            List<WideReport.Row> rows = WideReport.rows(results, others);
            WideReport.Saved s = new WideReport.Saved();
            s.identity = all.identity;
            s.plans = SystemScorer.WIDE_PLANS;
            s.rows = rows;
            List<String> bad = WideReport.check(s);
            if (!bad.isEmpty()) {
                throw new IllegalStateException("the replay's wide figures fail WideReport's checks, so wide.ser is not "
                        + "written:\n" + String.join("\n", bad));
            }
            WideReport.save(all.out.resolve("wide.ser"), all.identity, SystemScorer.WIDE_PLANS, rows);
        }

        /**
         * The perfect values the full-play bound row is scored on: the source's where it has one, so
         * that row's existing figures are those of the array the run kept, and the replay's where
         * the source left a state out (PLAN_COMPUTE.md 4.2). A penetration still lacking a state
         * leaves that row out there (SystemScorer.bounds).
         */
        static double[] perfect(FullIndexReplay.Assembled all) {
            double[] out = all.perfectSource.clone();
            for (int s = 0; s < out.length; s++) {
                if (Double.isNaN(out[s])) {
                    out[s] = all.perfectAgain[s];
                }
            }
            return out;
        }
    }

    /**
     * --sink DIR: run B of PLAN_COMPUTE.md 5.3. Each folds item is scored with a state sink on
     * (SystemScorer.stateSink, the point pass only, as 6.1 asks), which is handed every entry's
     * test states fold by fold: their weights, their round values under the entry's play and its
     * insurance, and their bet keys; the item's record keeps them, equal arrays kept once. A half
     * fits' item keeps nothing: its strategies are not the ones the page's figures come from. At
     * the end the records are handed, item by item, to one KeyEdges.Collector, each under the
     * entry of its count's merged Result, and saved as DIR/run-b.ser under the replay's identity
     * (KeyEdgesRun report --full reads it). No resample sink: full play cannot refit its later
     * plays per resample, so its per-key intervals are held and widened (5.3).
     */
    static final class Sink implements FullIndexReplay.Hook {
        static final String FILE = "run-b.ser";
        final Path dir;
        private ScoringStates st;
        private Handed handing;

        Sink(Path dir) {
            this.dir = dir;
        }

        @Override
        public String name() {
            return "sink";
        }

        @Override
        public void start(SystemScorer scorer, FullIndexReplay.Options o) {
            st = scorer.st;
        }

        @Override
        public void before(FullIndexReplay.Item item, SystemScorer scorer, List<FullIndexFit.Strategy> two) {
            if (item.halves) {
                return;
            }
            if (scorer.stateSink != null) {
                throw new IllegalStateException("the scorer's state sink is already taken");
            }
            handing = new Handed(item.pen);
            scorer.stateSink = handing;
        }

        @Override
        public Serializable after(FullIndexReplay.Item item, SystemScorer scorer, SystemScorer.Result scored,
                                  List<FullIndexFit.Strategy> two) {
            if (item.halves) {
                return null;
            }
            scorer.stateSink = null;
            Handed h = handing;
            handing = null;
            for (Map.Entry<String, Object[][]> e : h.byEntry.entrySet()) {
                if (e.getValue()[0] == null || e.getValue()[1] == null) {
                    throw new IllegalStateException(item + ": " + e.getKey() + " was handed one fold only");
                }
            }
            h.pool();
            return h;
        }

        @Override
        public void finish(FullIndexReplay.Assembled all) throws Exception {
            Map<String, SystemScorer.Entry> byName = new HashMap<>();
            for (SystemScorer.Result r : all.results()) {
                if (r == null) {
                    continue;
                }
                List<SystemScorer.Entry> es = new ArrayList<>(r.entries);
                es.addAll(r.fullEntries);
                for (SystemScorer.Entry e : es) {
                    if (byName.put(e.name, e) != null) {
                        throw new IllegalStateException("two entries named " + e.name + " among the replayed counts");
                    }
                }
            }
            KeyEdges.Collector c = new KeyEdges.Collector(st, 0);
            for (int k = 0; k < all.items.size(); k++) {
                Handed h = (Handed) all.record(k).kept.get(name());
                if (h == null) {
                    continue;
                }
                for (Map.Entry<String, Object[][]> x : h.byEntry.entrySet()) {
                    SystemScorer.Entry e = byName.get(x.getKey());
                    if (e == null) {
                        throw new IllegalStateException(all.items.get(k) + " handed " + x.getKey()
                                + ", which no replayed count's Result holds");
                    }
                    for (int f = 0; f < 2; f++) {
                        Object[] a = x.getValue()[f];
                        c.states(h.pen, e, f, (int[]) a[0], (double[]) a[1], (double[]) a[2], (double[]) a[3], (int[]) a[4]);
                    }
                }
            }
            Files.createDirectories(dir);
            c.save(dir.resolve(FILE), all.identity);
        }
    }

    /** One item's state sink and what it was handed: by entry name, [fold], {state, weight, play, insurance, key}. */
    static final class Handed implements SystemScorer.StateSink, Serializable {
        private static final long serialVersionUID = 1L;
        final int pen;
        final LinkedHashMap<String, Object[][]> byEntry = new LinkedHashMap<>();

        Handed(int pen) {
            this.pen = pen;
        }

        @Override
        public synchronized void states(int p, SystemScorer.Entry entry, int f, int[] state, double[] weight,
                                        double[] play, double[] insurance, int[] key) {
            if (p != pen) {
                throw new IllegalStateException(entry.name + " handed at penetration " + p + " in an item at " + pen);
            }
            Object[][] h = byEntry.computeIfAbsent(entry.name, x -> new Object[2][]);
            if (h[f] != null) {
                throw new IllegalStateException(entry.name + " handed twice at fold " + f);
            }
            h[f] = new Object[]{state, weight, play, insurance, key};
        }

        /** Arrays of equal content made one, so the record writes each once: every entry shares its fold's states and weights. */
        void pool() {
            Map<Object, Object> seen = new HashMap<>();
            for (Object[][] h : byEntry.values()) {
                for (Object[] a : h) {
                    for (int i = 0; i < a.length; i++) {
                        a[i] = seen.computeIfAbsent(new Content(a[i]), x -> ((Content) x).array);
                    }
                }
            }
        }
    }

    /** An int[] or double[] keyed by its content, to the bit. */
    private static final class Content {
        final Object array;

        Content(Object array) {
            this.array = array;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Content)) {
                return false;
            }
            Object b = ((Content) o).array;
            if (array instanceof int[]) {
                return b instanceof int[] && Arrays.equals((int[]) array, (int[]) b);
            }
            if (!(b instanceof double[]) || ((double[]) array).length != ((double[]) b).length) {
                return false;
            }
            double[] x = (double[]) array;
            double[] y = (double[]) b;
            for (int i = 0; i < x.length; i++) {
                if (Double.doubleToRawLongBits(x[i]) != Double.doubleToRawLongBits(y[i])) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return array instanceof int[] ? Arrays.hashCode((int[]) array) : Arrays.hashCode((double[]) array);
        }
    }

    /**
     * --export DIR: the full-play rows of the export bundle (PLAN_COMPUTE.md 1.2), in SHOE_SPEC.md
     * Part B's format, which the replay alone can make, since only it holds each fold strategy's
     * later plays and the Hit and Split values they give. For each exported row at the item's
     * penetration it keeps the two fold files' text (scope full: the first decisions refitted on
     * the full-play values as the scorer refits them, insurance, the later groups and cells of the
     * play in force, and the bets) and the row's states.bin section, after checking that the
     * section, summed as SystemScorer.scoreOnce sums it, gives the row's figures within 1e-12 and
     * its per-shoe sums at the 1-8 same schedule (StrategyExport.engineOrder); a row that does not
     * stops the replay before the item is kept. At the end, from the records: DIR.partial with
     * chart.tsv, the strategy files (each read back with ExportBundle, which refuses any that strays
     * from Part B), states.bin, exact.tsv, index.tsv and manifest.sha256, the figures checked again
     * from states.bin and the files as read back; and export.txt in the replay's directory.
     *
     * The bundle stays DIR.partial: StrategyExport's convention is that a directory without the
     * suffix passed its round trip, and StrategyExport.roundTrip has no path for scope full yet.
     * TODO(lane 1, the full-play export path): once StrategyExport.replay plays a scope-full file
     * (its later lines on the fold's Hit and Split values), call
     * StrategyExport.roundTrip(partial, st, header, expected, null) here and then move partial to
     * DIR, as StrategyExport.export does; and replace build below by StrategyExport's own full-play
     * builder if lane 1 writes one, so the two cannot drift. Not exported yet: the wide plans' bets
     * (P16, B0,8, X-1.0,8; SIM_CHECK.md row 9), whose per-fold bets and PLAY chances come from
     * SystemScorer.Wide and need a StrategyExport writer of their own; and keys-sample.tsv, which the
     * first-decision bundle carries for every catalog count.
     */
    static final class Export implements FullIndexReplay.Hook {
        /** The report plans exported by default: SIM_CHECK.md's full-play figures are flat and the 1-8 same schedule. */
        static final String[] PLANS = {"same-8"};
        final Path dir;
        final Path partial;
        /** The rows exported, by entry name, with the penetrations (0, 1, 2) each is exported at. */
        final Map<String, TreeSet<Integer>> rows;
        final String[] labels;
        private ScoringStates st;
        private StateStore.Header header;

        Export(Path dir, Map<String, TreeSet<Integer>> rows, String[] labels) {
            this.dir = dir;
            this.partial = dir.resolveSibling(dir.getFileName() + ".partial");
            this.rows = rows;
            this.labels = labels.clone();
            if (rows.isEmpty() || labels.length == 0) {
                throw new IllegalArgumentException("an export needs a row and a plan");
            }
            for (String l : labels) {
                StrategyExport.metrics(l);
            }
        }

        /** The full-play rows SIM_CHECK.md registers on c06c061's strategies (SimCheckPlan.figures), at their penetrations. */
        static Map<String, TreeSet<Integer>> registered() {
            Map<String, TreeSet<Integer>> out = new LinkedHashMap<>();
            for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
                if (f.play == SimCheckPlan.Play.FULL && (f.source.equals("c06c061") || f.source.equals("replay"))) {
                    out.computeIfAbsent(f.entry(), x -> new TreeSet<>()).add(StrategyExport.penIndex(f.pen));
                }
            }
            return out;
        }

        /** Rows named, each at every penetration the replay scores. */
        static Map<String, TreeSet<Integer>> everyPen(List<String> names) {
            Map<String, TreeSet<Integer>> out = new LinkedHashMap<>();
            for (String n : names) {
                out.put(n, new TreeSet<>(Arrays.asList(0, 1, 2)));
            }
            return out;
        }

        @Override
        public String name() {
            return "export";
        }

        @Override
        public String describe() {
            return "export (rows " + rows + "; plans " + String.join(",", labels) + ")";
        }

        @Override
        public void start(SystemScorer scorer, FullIndexReplay.Options o) throws Exception {
            if (Files.exists(dir) || Files.exists(partial)) {
                throw new IllegalStateException(dir + " or " + partial + " exists already; a bundle is written once");
            }
            st = scorer.st;
            header = StateStore.header(o.states);
        }

        @Override
        public Serializable after(FullIndexReplay.Item item, SystemScorer scorer, SystemScorer.Result scored,
                                  List<FullIndexFit.Strategy> two) {
            if (item.halves) {
                return null;
            }
            ArrayList<Row> out = new ArrayList<>();
            Play play = null;
            for (SystemScorer.Entry e : scored.fullEntries) {
                TreeSet<Integer> pens = rows.get(e.name);
                if (pens != null && pens.contains(item.pen)) {
                    if (play == null) {
                        play = new Play(scorer, item.pen, two);
                    }
                    out.add(build(scorer, play, scored.count, e, labels));
                }
            }
            return out.isEmpty() ? null : out;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void finish(FullIndexReplay.Assembled all) throws Exception {
            if (Files.exists(dir) || Files.exists(partial)) {
                throw new IllegalStateException(dir + " or " + partial + " exists already; a bundle is written once");
            }
            List<Row> kept = new ArrayList<>();
            for (int k = 0; k < all.items.size(); k++) {
                List<Row> rs = (List<Row>) all.record(k).kept.get(name());
                if (rs != null) {
                    kept.addAll(rs);
                }
            }
            if (kept.isEmpty()) {
                throw new IllegalStateException("no row of the export, " + rows.keySet() + ", was among the items replayed");
            }
            Files.createDirectories(partial);
            StrategyExport.writeChart(partial.resolve("chart.tsv"), BasicStrategy.chart());
            List<ExportBundle.Strategy> files = new ArrayList<>();
            List<ExportBundle.Section> sections = new ArrayList<>();
            for (Row r : kept) {
                for (int f = 0; f < 2; f++) {
                    Path file = partial.resolve(ExportBundle.Strategy.fileName(r.row, r.pen, f));
                    Files.write(file, r.text[f].getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE_NEW,
                            java.nio.file.StandardOpenOption.WRITE);
                    // Read back by Part B's reader, which refuses a file that strays from the format.
                    ExportBundle.Strategy s = ExportBundle.readStrategy(file);
                    if (!s.row.equals(r.row) || s.pen != r.pen || s.fold != f || s.scope != ExportBundle.Scope.FULL) {
                        throw new IllegalStateException(file + " reads back as " + s.row + " at " + s.pen + "%, fold "
                                + s.fold + ", scope " + s.scope.word());
                    }
                    files.add(s);
                }
                sections.add(r.section());
            }
            StrategyExport.writeStates(partial.resolve("states.bin"), header, st, sections);
            StrategyExport.writeExact(partial.resolve("exact.tsv"), st, sections);
            long total = StrategyExport.writeIndexAndManifest(partial, files);

            // The figures once more, from states.bin and the strategy files as read back.
            ExportBundle.States bin = ExportBundle.readStates(partial.resolve("states.bin"));
            StringBuilder t = new StringBuilder();
            t.append(all.identity).append("\n\nThe full-play export, in ").append(partial.getFileName()).append(": ")
                    .append(files.size()).append(" strategy files, ").append(total).append(" bytes in all with chart.tsv, "
                            + "states.bin, exact.tsv, index.tsv and manifest.sha256. Each row's figures, summed from its "
                            + "states.bin section and its files' bet lines as SystemScorer.scoreOnce sums them, against "
                            + "the replayed Entry.estimate, within ").append(StrategyExport.RELATIVE).append(" relative:\n");
            for (Row r : kept) {
                ExportBundle.Section sec = bin.section(r.row, r.pen);
                ExportBundle.Strategy[] pair = new ExportBundle.Strategy[2];
                for (ExportBundle.Strategy s : files) {
                    if (s.row.equals(r.row) && s.pen == r.pen) {
                        pair[s.fold] = s;
                    }
                }
                StrategyExport.Report check = check(st, sec, pair, r.estimate, r.shoeSums, r.row + " at " + r.pen
                        + "%, from the files written");
                t.append(String.format(java.util.Locale.ROOT, "- %s at %d%%: %d states; %d values compared, largest "
                                + "relative difference %s; ranked first moves that differ from those the split hands were "
                                + "valued with: %d of fold 0's %d (group, deal) cells and %d of fold 1's %d%n", r.row, r.pen,
                        sec.index.length, check.compared, check.worst, r.rankingDiffers[0], r.rankingCells[0],
                        r.rankingDiffers[1], r.rankingCells[1]));
            }
            for (Map.Entry<String, TreeSet<Integer>> w : rows.entrySet()) {
                for (int p : w.getValue()) {
                    boolean found = false;
                    for (Row r : kept) {
                        found |= r.row.equals(w.getKey()) && r.pen == ExportBundle.PENS[p];
                    }
                    if (!found) {
                        t.append("- not exported: ").append(w.getKey()).append(" at ").append(ExportBundle.PENS[p])
                                .append("%, not among the items replayed\n");
                    }
                }
            }
            t.append("\nThe bundle stays ").append(partial.getFileName()).append(": StrategyExport.roundTrip has no "
                    + "full-play path yet (the TODO in ReplayHooks.Export), and a bundle directory without that suffix is "
                    + "one whose round trip passed.\n");
            FullIndexReplay.writeWhole(all.out.resolve("export.txt"),
                    o -> o.write(t.toString().getBytes(StandardCharsets.UTF_8)));
            // TODO(lane 1): StrategyExport.roundTrip(partial, st, header, expected, null), then
            // Files.move(partial, dir, StandardCopyOption.ATOMIC_MOVE), once it plays scope full.
        }
    }

    /**
     * The figures of a row summed from its section and files in SystemScorer.scoreOnce's order
     * (StrategyExport.engineOrder), each against the row's estimate within 1e-12 relative, and at
     * same-8 each shoe's sum against Entry.shoeSums; refused, naming the first that differs.
     */
    static StrategyExport.Report check(ScoringStates st, ExportBundle.Section sec, ExportBundle.Strategy[] files,
                                       double[] estimate, double[] shoeSums, String where) {
        StrategyExport.Report r = new StrategyExport.Report();
        double[] engine = StrategyExport.engineOrder(st, sec, files);
        List<String> figures = StrategyExport.figures(sec.labels);
        for (int fi = 0; fi < figures.size(); fi++) {
            int m = StrategyExport.metric(sec.labels, fi);
            if (m >= 0) {
                r.same(engine[fi], estimate[m], where + ": " + figures.get(fi) + " summed in the engine's order");
            }
        }
        int same8 = Arrays.asList(sec.labels).indexOf("same-" + SystemScorer.SPREADS);
        if (same8 >= 0) {
            r.require(shoeSums != null && shoeSums.length == st.shoes, where + ": no per-shoe sums to set beside");
            double[] shoe = new double[st.shoes];
            for (int e = 0; e < sec.index.length; e++) {
                int s = sec.index[e];
                shoe[st.shoe[s]] += st.weight[s] * sec.bet[e][same8] * (sec.rvPlay[e] + sec.rvInsurance[e]);
            }
            for (int i = 0; i < st.shoes; i++) {
                r.same(shoe[i], shoeSums[i], where + ": shoe " + i + "'s sum at same-" + SystemScorer.SPREADS);
            }
        }
        return r;
    }

    /**
     * A count's full play at one penetration as SystemScorer.scorePen scores it: each fold's first
     * decisions refitted on that fold strategy's Hit and Split values (foldPlay with values), its
     * insurance in the same groups, and its moves ranked by that fit (FullIndexFit.rankings, as
     * SHOE_SPEC.md B7 says). The later plays are the fold strategy's play in force.
     */
    static final class Play {
        final int p;
        final SystemScorer.Pen pen;
        final FullIndexFit.Strategy[] fold = new FullIndexFit.Strategy[2];
        final SystemScorer.FoldPlay[] plays = new SystemScorer.FoldPlay[2];
        final double[][] insuranceSum = new double[2][];
        final double[][] insurance = new double[2][];
        final PlayerMove[][][][] ranking = new PlayerMove[2][][][];
        /**
         * [fold]: the (group, deal) cells whose ranked moves differ from the play in force's, which
         * the split hands were valued with (FullIndexFit.Strategy.split); B2.4 plays both from one
         * list, so where these are not 0 the file plays some split hands otherwise than the engine
         * valued them.
         */
        final int[] rankingDiffers = new int[2];
        /** [fold]: the (group, deal) cells its file ranks moves for, its groups times the deals but the player's naturals. */
        final int[] cells = new int[2];

        Play(SystemScorer scorer, int p, List<FullIndexFit.Strategy> two) {
            this.p = p;
            pen = scorer.pen(p);
            SystemScorer.Parts parts = two.get(0).parts;
            for (int f = 0; f < 2; f++) {
                FullIndexFit.Strategy t = two.get(f);
                if (t.fold != f || t.pen != p || t.half || t.values == null || t.parts != parts || t.now == null) {
                    throw new IllegalStateException(t + " is not fold " + f + "'s fit at penetration " + p
                            + " with its values and the item's parts");
                }
                fold[f] = t;
            }
            SystemScorer.InsuranceParts ip = scorer.insuranceParts(parts, pen);
            for (int f = 0; f < 2; f++) {
                plays[f] = scorer.foldPlay(parts, pen, ip, pen.train[f], pen.weight, null, null, null, null, fold[f].values);
                insuranceSum[f] = SystemScorer.insuranceSums(ip, plays[f].insuranceGroup, plays[f].groups.count(),
                        pen.train[f], pen.weight);
                insurance[f] = SystemScorer.insuranceValues(ip, plays[f].insuranceGroup, insuranceSum[f]);
                ranking[f] = FullIndexFit.rankings(scorer.st, scorer.rules(), plays[f].fit);
                PlayerMove[][][] held = fold[f].now.ranking;
                for (int g = 0; g < ranking[f].length; g++) {
                    for (int i = 0; i < Deals.COUNT; i++) {
                        if (Deals.playerNatural(i)) {
                            continue;
                        }
                        cells[f]++;
                        boolean same = held != null && g < held.length && Arrays.equals(ranking[f][g][i], held[g][i]);
                        rankingDiffers[f] += same ? 0 : 1;
                    }
                }
            }
        }
    }

    /** One exported row at one penetration, as an item's record keeps it: its two files' text and its states.bin section. */
    static final class Row implements Serializable {
        private static final long serialVersionUID = 1L;
        final String row;
        /** 70, 75 or 80. */
        final int pen;
        final int cut;
        final String[] text = new String[2];
        final String[] labels;
        int[] index;
        int[] betKey;
        double[] rvPlay;
        double[] rvInsurance;
        double[][] bet;
        /** The replayed entry's estimate and per-shoe sums at the penetration, which the section must give. */
        final double[] estimate;
        final double[] shoeSums;
        final int[] rankingDiffers;
        final int[] rankingCells;

        Row(SystemScorer.Entry e, Play play, String[] labels) {
            row = e.name;
            pen = ExportBundle.PENS[play.p];
            cut = play.pen.cut;
            this.labels = labels.clone();
            estimate = e.estimate[play.p].clone();
            shoeSums = e.shoeSums == null || e.shoeSums[play.p] == null ? null : e.shoeSums[play.p].clone();
            rankingDiffers = play.rankingDiffers.clone();
            rankingCells = play.cells.clone();
        }

        ExportBundle.Section section() {
            ExportBundle.Section s = new ExportBundle.Section();
            s.row = row;
            s.pen = pen;
            s.cut = cut;
            s.labels = labels.clone();
            s.index = index;
            s.betKey = betKey;
            s.rvPlay = rvPlay;
            s.rvInsurance = rvInsurance;
            s.bet = bet;
            return s;
        }
    }

    /**
     * A full-play row's two strategy files and its states.bin section at the play's penetration,
     * line for line as SHOE_SPEC.md B7 sources them (StrategyExport.build's way for first
     * decisions, with the full play's refit, insurance and base values, and the later lines), then
     * held to the entry's figures (check). Refused for a row that is not full play.
     */
    static Row build(SystemScorer scorer, Play play, CountKeys.Count playCount, SystemScorer.Entry e, String[] labels) {
        if (!e.fullPlay || e.ownValue || e.bettingOnly) {
            throw new IllegalArgumentException(e.name + ": only a count's full-play rows are exported by the replay");
        }
        CountKeys.Count bet = e.count;
        if (!bet.name.equals(playCount.name) && (bet.base == null || !bet.base.name.equals(playCount.name))) {
            throw new IllegalArgumentException(e.name + " does not bet on " + playCount.name + " or a variant of it");
        }
        if (bet.trueCounted != playCount.trueCounted || bet.divisor() != playCount.divisor()) {
            throw new IllegalArgumentException(e.name + ": a strategy file has one mode and one divisor for both keys");
        }
        ScoringStates st = scorer.st;
        SystemScorer.Pen pen = play.pen;
        SystemScorer.BetKeys bk = scorer.betKeys(bet, pen);
        Row out = new Row(e, play, labels);
        ExportBundle.Strategy[] files = new ExportBundle.Strategy[2];
        double[][] units = new double[2][];
        int[][] cells = ExportBundle.cells();
        for (int f = 0; f < 2; f++) {
            SystemScorer.FoldPlay fp = play.plays[f];
            double[] rv = new double[pen.states.length];
            for (int j = 0; j < rv.length; j++) {
                rv[j] = fp.base[j] + play.insurance[f][j];
            }
            double[][] detail = new double[bk.value.length][SystemScorer.DETAIL];
            SystemScorer.bets(bk, pen.train[f], pen.test[f], pen.testByChart[f], pen.weight, rv, pen.chart,
                    new double[SystemScorer.METRICS + 1], detail);
            ExportBundle.Strategy s = new ExportBundle.Strategy();
            s.row = e.name;
            s.scope = ExportBundle.Scope.FULL;
            s.pen = ExportBundle.PENS[play.p];
            s.cut = pen.cut;
            s.fold = f;
            s.trained = fp.fit.weight;
            s.playCount = playCount.name;
            s.tags = playCount.tag.clone();
            s.scale = playCount.scale;
            s.trueCounted = playCount.trueCounted;
            s.divisor = playCount.divisor();
            s.deckSum = playCount.deckSum;
            s.betCount = bet.name;
            s.betTags = bet.betTags().clone();
            s.betDeckSum = bet.betDeckSum();
            s.ace = bet.aceSideCount;
            s.lo = fp.groups.lo.clone();
            s.hi = fp.groups.hi.clone();
            s.first = play.ranking[f];
            int groups = fp.groups.count();
            s.insure = new boolean[groups][ScoringStates.ACE_UP];
            for (int g = 0; g < groups; g++) {
                for (int i = 0; i < ScoringStates.ACE_UP; i++) {
                    s.insure[g][i] = play.insuranceSum[f][g * ScoringStates.ACE_UP + i] > 0;
                }
            }
            // B7: lgroup from Strategy.later, later from Policy.stands at the spot's cell. A play
            // with no later groups plays the chart at every later decision: one group, the chart's cells.
            FullIndexFit.Strategy t = play.fold[f];
            boolean[] stands = t.now.stands;
            boolean chart = stands == null || t.later == null;
            s.laterLo = chart ? new int[]{0} : t.later.lo.clone();
            s.laterHi = chart ? new int[]{0} : t.later.hi.clone();
            s.stands = new boolean[s.laterLo.length][ExportBundle.CELLS];
            for (int l = 0; l < s.laterLo.length; l++) {
                for (int c = 0; c < ExportBundle.CELLS; c++) {
                    int ec = FullIndexFit.cellOf(PlayerTree.spot(cells[c][1], cells[c][2] == 1, cells[c][0]));
                    s.stands[l][c] = chart ? FullIndexFit.chartStands(ec) : stands[l * FullIndexFit.CELLS + ec];
                }
            }
            int present = 0;
            for (double[] d : detail) {
                present += d[0] > 0 ? 1 : 0;
            }
            int[] key = new int[present];
            int[] at = new int[present];
            present = 0;
            for (int k = 0; k < detail.length; k++) {
                if (detail[k][0] > 0) {
                    key[present] = bk.value[k];
                    at[present++] = k;
                }
            }
            units[f] = new double[bk.value.length * labels.length];
            Arrays.fill(units[f], Double.NaN);
            for (int l = 0; l < labels.length; l++) {
                double[] u = new double[present];
                double[] chance = new double[present];
                for (int x = 0; x < present; x++) {
                    u[x] = StrategyExport.units(detail[at[x]], labels[l]);
                    chance[x] = 1;
                    units[f][at[x] * labels.length + l] = u[x];
                }
                s.plans.add(new ExportBundle.Plan(labels[l], StrategyExport.rule(labels[l]), null, key, u, chance));
            }
            files[f] = s;
            out.text[f] = StrategyExport.format(s);
        }
        section(st, play, bk, units, out);
        try {
            check(st, out.section(), files, out.estimate, out.shoeSums, e.name + " at " + out.pen + "%");
        } catch (IllegalStateException x) {
            throw new IllegalStateException("the full-play export does not give the replayed figures, so the replay "
                    + "stops and keeps nothing of the item: " + x.getMessage(), x);
        }
        return out;
    }

    /** The row's states.bin section (B3): every state of the penetration with its own fold's play, insurance and bets. */
    static void section(ScoringStates st, Play play, SystemScorer.BetKeys bk, double[][] units, Row out) {
        SystemScorer.Pen pen = play.pen;
        int m = pen.states.length;
        int plans = out.labels.length;
        out.index = new int[m];
        out.betKey = new int[m];
        out.rvPlay = new double[m];
        out.rvInsurance = new double[m];
        out.bet = new double[m][plans];
        for (int j = 0; j < m; j++) {
            int s = pen.states[j];
            int f = st.fold(s);
            out.index[j] = s;
            out.betKey[j] = bk.value[bk.index[j]];
            out.rvPlay[j] = play.plays[f].base[j];
            out.rvInsurance[j] = play.insurance[f][j];
            for (int l = 0; l < plans; l++) {
                double u = units[f][bk.index[j] * plans + l];
                if (Double.isNaN(u)) {
                    throw new IllegalStateException(out.row + ": state " + s + "'s key " + out.betKey[j]
                            + " has no bet in its own fold, whose test states it is one of");
                }
                out.bet[j][l] = u;
            }
        }
    }
}
