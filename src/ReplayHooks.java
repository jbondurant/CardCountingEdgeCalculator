import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the replay of PLAN_COMPUTE.md 6.1 makes of each item besides its Result: the wide plans'
 * figures (--wide) and each state's values for the edge at each count (--sink, run B of 5.3). Each
 * keeps what it needs of an item in the item's record, and puts its output together at the end from
 * the records alone, so a replay killed and resumed writes what an uninterrupted one writes
 * (FullIndexReplayTest). The simulator's full-play export (--export, 1.2) is StrategyExport's own
 * hook, StrategyExport.ReplayExport; the second builder this class held until the integration of
 * site-int-replay with site-int-spec (ReplayHooks.Export, at 4527557) was retired for it, as its
 * own TODO asked, so the two cannot drift.
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
}
