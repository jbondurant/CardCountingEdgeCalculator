import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SiteExport on a results.ser written as FullIndexRun writes one, from Results the scorer made on
 * FullIndexFitTest's sixteen-shoe fixture: the null count and Hi-Lo with their full-index fits,
 * Omega II and its ace side-count variant at first decisions. Every figure is checked by a route
 * written here: which entry and metric a key reads, which ratio widens it, and the unit.
 */
public class SiteExportTest {

    @TempDir
    static Path dir;
    static List<SystemScorer.Result> results;
    static SiteExport.Options options;
    static Map<String, Object> out;
    static CountKeys.Count omega;
    /** The pilot's ratios the fixture gives: Omega II's difference ratios, Hi-Lo's gain ratio. */
    static final double OMEGA_18 = 1.05;
    static final double OMEGA_FLAT = 1.03;
    static final double HILO_GAIN = 1.40;

    @BeforeAll
    static void scored() throws Exception {
        FullIndexFitTest.Run run = FullIndexFitTest.capped();
        omega = PlayerTreeOrderTest.named("Omega II");
        results = new ArrayList<>();
        results.add(run.results.get(FullIndexFitTest.NULL));
        results.add(run.results.get(FullIndexFitTest.HI_LO));
        results.add(run.fx.scorer.score(omega, CountKeys.bettingVariant(omega)));
        options = options(write(results, "results.ser"));
        out = SiteExport.export(options).json();
    }

    static String identity() {
        return "FullIndexRun joint run\ncommit fixture-export\nstates /fixture.states, 1 bytes, modified 1\nshoes 16, valued "
                + "states 20\nresamples 20, seed 1, M_play 1.0\n";
    }

    /** A results.ser as FullIndexRun writes it: its identity, the Results, the perfect array, the rounds. */
    static Path write(List<SystemScorer.Result> rs, String name) throws Exception {
        Path f = dir.resolve(name);
        try (ObjectOutputStream o = new ObjectOutputStream(Files.newOutputStream(f))) {
            o.writeUTF(identity());
            o.writeObject(new ArrayList<>(rs));
            o.writeObject(null);
            o.writeObject(new ArrayList<>());
        }
        return f;
    }

    static SiteExport.Options options(Path ser) throws Exception {
        Path pilot = dir.resolve("pilot.txt");
        Files.write(pilot, WideReportTest.pilot(WideReportTest.ratios("1-8 same schedule", omega.name, OMEGA_18, "flat",
                omega.name, OMEGA_FLAT)).getBytes(StandardCharsets.UTF_8));
        SiteExport.Options o = new SiteExport.Options();
        o.stored = ser;
        o.storedShown = "fixture/results.ser";
        o.widening = SiteExport.Widening.read(pilot);
        o.template = new String(Files.readAllBytes(Paths.get("site/template.html")), StandardCharsets.UTF_8);
        o.catalog = SiteExport.catalog(Paths.get("counting-systems.json"));
        return o;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o, String... path) {
        Object at = o;
        for (String p : path) {
            at = ((Map<String, Object>) at).get(p);
        }
        return (Map<String, Object>) at;
    }

    /** The entries of a row id, by scope, found here by the entries' own flags and counts. */
    static SystemScorer.Entry entry(String id, String scope) {
        for (SystemScorer.Result r : results) {
            for (SystemScorer.Entry e : scope.equals("full") ? r.fullEntries : r.entries) {
                if (e.ownValue || (scope.equals("bet") != e.bettingOnly)) {
                    continue;
                }
                boolean ace = e.count.base != null;
                String base = e.count.systems.isEmpty() ? "null" : e.count.systems.get(0).equals("Hi-Lo") ? "hi_lo" : "omega_ii";
                if ((base + (ace ? "_ace" : "")).equals(id)) {
                    return e;
                }
            }
        }
        return null;
    }

    /** The ratio the fixture's pilot sets for a row's full-play level at spread s: its own, else the largest measured. */
    static double level(String id, int s) {
        if (id.equals("omega_ii")) {
            return s == 1 ? OMEGA_FLAT : OMEGA_18;
        }
        return Math.max(OMEGA_18, OMEGA_FLAT);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void theRowsAreTheCatalogsNamedAsThePageNamesThem() {
        List<Object> rows = (List<Object>) obj(out, "tables").get("rows");
        List<String> ids = new ArrayList<>();
        for (Object o : rows) {
            ids.add((String) ((Map<String, Object>) o).get("id"));
        }
        assertEquals(List.of("null", "hi_lo", "omega_ii", "omega_ii_ace"), ids);
        Map<String, Object> ace = (Map<String, Object>) rows.get(3);
        assertEquals("ace", ace.get("side"));
        assertEquals("a side count its sources recommend or allow, with QFIT's multiplier", ace.get("side_rule"));
        assertEquals(2L, ace.get("level"));
        assertEquals("c1", ((Map<String, Object>) rows.get(1)).get("role"));
        assertEquals("reference", ((Map<String, Object>) rows.get(0)).get("kind"));
        assertEquals("Omega II + ace", obj(out, "names").get("omega_ii_ace"));
        assertEquals("Hi-Lo", obj(out, "names").get("hi_lo"));
        assertEquals("1-8", obj(out, "names").get("s8"));
        assertEquals("flat", obj(out, "names").get("s1"));
    }

    static double[] expected(String id, String scope, int p, int s) {
        SystemScorer.Entry e = entry(id, scope);
        int m = s == 1 ? SystemScorer.FLAT : 2 + s;
        double w = scope.equals("full") ? level(id, s) : 1;
        Bootstrap.Interval iv = Bootstrap.interval(e.estimate[p][m], w == 1 ? e.boot[p][m] : Bootstrap.widen(e.boot[p][m], w));
        return new double[]{100 * iv.estimate, 100 * iv.se, 100 * iv.lo, 100 * iv.hi};
    }

    /**
     * Every cell of the grid is its row's entry in that scope, at that penetration, flat or the same
     * schedule: estimate, SE and 95% interval in percent, a full-play level's widened by its count's
     * own difference ratio (Omega II's, flat or 1-8) or, for a count the pilot did not measure, the
     * largest it measured; first decisions and betting only not widened. No entry is left out.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void everyCellIsItsEntrysFigureWidenedByItsCountsRatio() {
        Map<String, Object> grid = obj(out, "grid");
        int cells = 0;
        for (String id : new String[]{"null", "hi_lo", "omega_ii", "omega_ii_ace"}) {
            for (String scope : new String[]{"bet", "fd", "full"}) {
                SystemScorer.Entry e = entry(id, scope);
                for (int p = 0; p < 3; p++) {
                    String pen = new String[]{"70", "75", "80"}[p];
                    boolean has = e != null && e.estimate[p] != null;
                    Map<String, Object> row = (Map<String, Object>) grid.get(id);
                    Map<String, Object> at = row.get(scope) == null ? null : obj(row.get(scope), pen);
                    assertEquals(has, at != null, id + " " + scope + " " + pen);
                    for (int s = 1; has && s <= 8; s++) {
                        List<Object> c = (List<Object>) at.get("s" + s);
                        double[] want = expected(id, scope, p, s);
                        for (int k = 0; k < 4; k++) {
                            assertEquals(want[k], (Double) c.get(k), 0, id + " " + scope + " " + pen + " s" + s + " [" + k + "]");
                        }
                        cells++;
                    }
                }
            }
        }
        // Three counts' rows (plain and variant) at three penetrations in two scopes, and full play at 80% for two.
        assertEquals((4 * 2 * 3 + 2) * 8, cells);
        assertEquals(List.of("full"), grid.get("_widened"));
    }

    /**
     * The figures the template's guards name carry their resamples: Hi-Lo at 70% and 80% at first
     * decisions, and, since a guard names the best or the top five by rank, every row at full play
     * and at first decisions at 80%, 1-8; the resamples in percent and unwidened, the ratio beside
     * them for SiteGuards. Nothing else carries any.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void theFiguresTheGuardsNameCarryTheirResamples() {
        Map<String, Object> figs = obj(out, "figures");
        List<String> booted = new ArrayList<>();
        for (Map.Entry<String, Object> f : figs.entrySet()) {
            if (((Map<String, Object>) f.getValue()).containsKey("boot")) {
                booted.add(f.getKey());
            }
        }
        java.util.Collections.sort(booted);
        assertEquals(List.of("fd.70.s8.hi_lo", "fd.80.s8.hi_lo", "fd.80.s8.null", "fd.80.s8.omega_ii", "fd.80.s8.omega_ii_ace",
                "full.80.s8.hi_lo", "full.80.s8.null"), booted);
        for (String k : booted) {
            String[] p = k.split("\\.");
            int pen = p[1].equals("70") ? 0 : 2;
            SystemScorer.Entry e = entry(p[3], p[0]);
            List<Object> b = (List<Object>) obj(figs, k).get("boot");
            assertEquals(e.boot[pen][2 + 8].length, b.size());
            for (int r = 0; r < b.size(); r++) {
                assertEquals(100 * e.boot[pen][2 + 8][r], (Double) b.get(r), 0, k + " resample " + r);
            }
            assertEquals(p[0].equals("full") ? level(p[3], 8) : 1.0, obj(figs, k).get("widen"), k);
        }
    }

    /** The gain from full play: the full-play entry less the first-decision one, paired, widened by the gain ratio. */
    @Test
    public void theGainIsFullPlayLessFirstDecisionsWidenedByTheGainRatio() {
        for (String id : new String[]{"hi_lo", "null"}) {
            for (int s : new int[]{1, 8}) {
                int m = s == 1 ? SystemScorer.FLAT : 2 + s;
                SystemScorer.Entry full = entry(id, "full");
                SystemScorer.Entry first = entry(id, "fd");
                double[] d = new double[full.boot[2][m].length];
                for (int r = 0; r < d.length; r++) {
                    d[r] = full.boot[2][m][r] - first.boot[2][m][r];
                }
                Bootstrap.Interval iv = Bootstrap.interval(full.estimate[2][m] - first.estimate[2][m],
                        Bootstrap.widen(d, HILO_GAIN));
                Map<String, Object> g = obj(out, "figures", "gain.80.s" + s + "." + id);
                assertEquals(100 * iv.estimate, (Double) g.get("value"), 0, id);
                assertEquals(List.of(100 * iv.lo, 100 * iv.hi), g.get("ci"), id);
                assertEquals(HILO_GAIN, g.get("widen"));
                assertEquals(true, g.get("widened"));
            }
        }
        assertNull(obj(out, "figures").get("gain.70.s8.hi_lo"), "the fixture fits full play at 80% only");
    }

    /** Per amount bet: the EV over the same schedule's average bet, the bet the same for every row. */
    @Test
    public void perAmountBetIsTheEvOverTheAverageBet() {
        SystemScorer.Entry full = entry("hi_lo", "full");
        int ev = 2 + 8;
        int bet = 2 + 8 + 8;
        assertEquals(full.estimate[2][bet], (Double) obj(out, "figures", "avgbet.80.s8").get("value"), 0);
        assertEquals(100 * (full.estimate[2][ev] / full.estimate[2][bet]),
                (Double) obj(out, "figures", "perbet.full.80.s8.hi_lo").get("value"), 0);
        assertEquals(entry("omega_ii_ace", "fd").estimate[0][bet], (Double) obj(out, "figures", "avgbet.70.s8").get("value"), 0);
    }

    /** The data's size and the bootstrap's come from the run's identity; the rules from HouseRules; the method's counts from the engine. */
    @Test
    public void theDataTheRulesAndTheMethodComeFromTheirSources() throws Exception {
        assertEquals(16.0, obj(out, "figures", "data.shoes").get("value"));
        assertEquals(20.0, obj(out, "figures", "data.states").get("value"));
        assertEquals(20.0, obj(out, "figures", "method.resamples").get("value"));
        assertEquals(8.0, obj(out, "figures", "game.decks").get("value"));
        assertEquals(1.0, obj(out, "figures", "game.h17").get("value"));
        assertEquals(Deals.COUNT + 0.0, obj(out, "figures", "method.deals").get("value"));
        // Every deal but the player's naturals stands, hits and doubles; a pair also splits; no surrender.
        int values = 0;
        for (int d = 0; d < Deals.COUNT; d++) {
            values += Deals.playerNatural(d) ? 0 : 3 + (Deals.P1[d] == Deals.P2[d] ? 1 : 0);
        }
        assertEquals(1720, values);
        assertEquals(values + 0.0, obj(out, "figures", "method.values_per_state").get("value"));
        int[] full = EffectsOfRemoval.fullShoe();
        double[] chart;
        try (StateValuer v = new StateValuer(StateRun.montreal(), BasicStrategy.chart(), 1)) {
            chart = v.value(List.of(full)).get(0);
        }
        int deal = Deals.index(10, 6, 10);
        assertEquals(chart[StateValuer.slot(deal, PlayerMove.Stand)], (Double) obj(out, "figures", "example.stand").get("value"), 1e-15);
        assertEquals(chart[StateValuer.slot(deal, PlayerMove.Hit)], (Double) obj(out, "figures", "example.hit").get("value"), 1e-15);
        assertEquals(16.0, obj(out, "figures", "example.total").get("value"));
        assertEquals(1.0, obj(out, "figures", "tags.hi_lo.r2").get("value"));
        assertEquals(-1.0, obj(out, "figures", "tags.hi_lo.r1").get("value"));
        assertEquals(-2.0, obj(out, "figures", "tags.omega_ii.r10").get("value"));
    }

    /** Full play with no pilot to widen it is refused, and so is a row whose same schedule averages another bet. */
    @Test
    public void unwidenedFullPlayAndUnequalBetsAreRefused() throws Exception {
        SiteExport.Options o = options(options.stored);
        o.widening = null;
        assertThrows(IllegalStateException.class, () -> SiteExport.export(o));
        SystemScorer.Entry e = entry("omega_ii", "fd");
        double was = e.estimate[1][2 + 8 + 4];
        try {
            e.estimate[1][2 + 8 + 4] = Math.nextUp(Math.nextUp(was * (1 + 1e-11)));
            SiteExport.Options bad = options(write(results, "unequal.ser"));
            IllegalStateException x = assertThrows(IllegalStateException.class, () -> SiteExport.export(bad));
            assertTrue(x.getMessage().contains("the same schedule must give every row the same bets"), x.getMessage());
        } finally {
            e.estimate[1][2 + 8 + 4] = was;
        }
    }

    /** The rounds dealt at each cut, dealing the main file's shoes again from its seed alone: SHOE_SPEC.md A8's. */
    @Test
    public void theRoundsDealtAreTheMainFilesWhenItsShoesAreDealtAgain() {
        StateStore.Header h = new StateStore.Header(20260928L, 0.008, 0.02, 332, "fixture");
        assertArrayEquals(SimCheckPlan.ROUNDS, SiteExport.roundsDealt(h, SimCheckPlan.SHOES));
    }

    /** What SiteExport writes reads back through SiteData's own rules: widened full play, one value per key. */
    @Test
    public void theInputReadsInSiteData() throws Exception {
        SiteInput in = SiteExport.export(options);
        SiteData d = new SiteData();
        d.add("results-fixture-export.json", in.text("results-fixture-export.json").getBytes(StandardCharsets.UTF_8));
        assertEquals("fixture-export", d.inputs.get(0).commit());
        assertTrue(d.resolve("full.80.s8.hi_lo").widened);
        assertEquals(level("hi_lo", 8), d.resolve("full.80.s8.hi_lo").widen);
        assertNotNull(d.resolve("fd.75.s3.omega_ii_ace"));
    }
}
