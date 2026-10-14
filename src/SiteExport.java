import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The page's main input (PLAN_PAGE.md 2.4): every published count's figures read from a full-index
 * run's results.ser (c06c061's) with these classes, written in SiteData's contract as
 * runs/site/results-COMMIT.json.
 *
 * What it writes, in the page's unit, percent of a unit a round:
 * - the grid: every row (each catalog entry, its ace side-count variant, the null count) at every
 *   penetration and spread, s1 flat (SystemScorer.FLAT) and s2 to s8 the same schedule, in three
 *   scopes: bet (the count for bets and insurance, the chart for play), fd (also the first
 *   decision) and full (every decision); each cell its estimate, SE and 95% interval
 *   (Bootstrap.interval). A full-play level's interval is widened by its count's difference ratio
 *   from the pilot (PLAN_PAGE.md 2.4's rule; the largest difference ratio the pilot measured for a
 *   count it did not measure), flat by the flat table's ratio and every spread by the 1-8 table's;
 *   first-decision and betting-only cells are not widened. Without a pilot it refuses to run, since
 *   SiteData shows no unwidened full-play interval;
 * - with boot, the resamples, every figure a claim guard of the template names (guardKeys): a
 *   guard's row named by rank (@best, @top5) may turn out to be any published row, so every row at
 *   that scope, penetration and spread carries its resamples, unwidened, with its ratio beside them
 *   (SiteGuards widens each quantity by the largest ratio among its figures);
 * - the gain from full play (full less first decisions, paired, widened by the count's gain ratio),
 *   per amount bet (each resample's EV over its average bet, widened as the level), the same
 *   schedule's average bet (refused unless every row's is the same to 1e-12 relative), the data's
 *   size and the bootstrap's from the run's identity, the rounds dealt at each cut (dealing the
 *   state file's shoes again from its header's seed, as ShoeSpecTest does, when --states is
 *   given), the game from HouseRules, the method's counts from Deals and StateValuer, the worked
 *   example from ExactRound on a fresh shoe, each catalog count's tags, the names and the rows
 *   table (level, balanced, side count and where its rule comes from).
 *
 * java -cp build/classes:lib/mongo-java-driver-3.12.14.jar SiteExport --stored RESULTS.ser
 *      --widening PILOT.txt --template site/template.html --out runs/site/results-COMMIT.json
 *      [--states STATES] [--catalog counting-systems.json] [--saved STEP7.ser [--counts custom-counts.json]
 *      [--fullplay-run STEP8/results.ser --fullplay-counts custom-counts-fullplay.json]]
 * With step 7's save it also writes the bound rows and, with the registrations, the registered
 * counts at every spread (ceilingRows).
 */
final class SiteExport {

    private SiteExport() {
    }

    static final String[] PENS = {"70", "75", "80"};
    static final String[] SCOPES = {"bet", "fd", "full"};
    static final String[] RANKS = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "T"};
    /** The page's interval: Bootstrap.interval's 2.5% and 97.5% quantiles. */
    static final int CI = 95;

    /** The metric a spread is: s1 flat, sN the same schedule from 1 to N units. */
    static int metric(int s) {
        return s == 1 ? SystemScorer.FLAT : SystemScorer.same(s);
    }

    /** The pilot's table a spread's widening is read from (FullIndexPilot.printSes's names). */
    static String table(int s) {
        return s == 1 ? "flat" : "1-8 same schedule";
    }

    // ------------------------------------------------------------------ rows

    /** One row of the page: a count or its ace side-count variant, with its entries in each scope. */
    static final class Row {
        final String id;
        final String name;
        final CountKeys.Count count;
        final Map<String, Object> record = new LinkedHashMap<>();
        SystemScorer.Entry betting;
        SystemScorer.Entry first;
        SystemScorer.Entry full;

        Row(String id, String name, CountKeys.Count count) {
            this.id = id;
            this.name = name;
            this.count = count;
        }

        SystemScorer.Entry in(String scope) {
            return scope.equals("bet") ? betting : scope.equals("fd") ? first : full;
        }

        boolean published() {
            return "published".equals(record.get("kind"));
        }
    }

    /** The catalog's systems by name, as counting-systems.json gives them. */
    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> catalog(Path file) throws IOException {
        Map<String, Object> doc = (Map<String, Object>) Json.parse(new String(Files.readAllBytes(file),
                StandardCharsets.UTF_8));
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Object o : (List<Object>) doc.get("systems")) {
            Map<String, Object> s = (Map<String, Object>) o;
            out.put((String) s.get("name"), s);
        }
        return out;
    }

    /**
     * Where a side-count variant's rule comes from, read from the catalog's aceSideCount words
     * (PLAN_PAGE.md 5a): the author's own, one the sources recommend or allow, or QFIT's general
     * rule applied here to an ace-neutral count.
     */
    static String sideRule(String words) {
        String w = words.toLowerCase(java.util.Locale.ROOT);
        if (w.contains("counted separately") || w.contains("with an ace side count:")) {
            return "the author's rule";
        }
        if (w.contains("recommended") || w.contains("optional")) {
            return "a side count its sources recommend or allow, with QFIT's multiplier";
        }
        return "QFIT's general rule, added here";
    }

    /**
     * The page's rows from a run's Results, in the run's order: each count's plain row and its ace
     * side-count variant, every scope's entry found by its count and kind. A row bet on its own
     * value is a bound, not a count, and is left out; a betting vector's variant (F2's kind) has no
     * place among the published rows and is refused.
     */
    static List<Row> rows(List<SystemScorer.Result> results, Map<String, Map<String, Object>> catalog) {
        List<Row> out = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (SystemScorer.Result r : results) {
            CountKeys.Count c = r.count;
            Map<CountKeys.Count, Row> by = new LinkedHashMap<>();
            List<SystemScorer.Entry> all = new ArrayList<>(r.entries);
            all.addAll(r.fullEntries);
            for (SystemScorer.Entry e : all) {
                if (e.ownValue) {
                    continue;
                }
                Row row = by.get(e.count);
                if (row == null) {
                    row = row(c, e.count, catalog);
                    if (!ids.add(row.id)) {
                        throw new IllegalStateException("two rows would be called " + row.id);
                    }
                    by.put(e.count, row);
                    out.add(row);
                }
                if (e.fullPlay) {
                    row.full = once(row.full, e);
                } else if (e.bettingOnly) {
                    row.betting = once(row.betting, e);
                } else {
                    row.first = once(row.first, e);
                }
            }
        }
        return out;
    }

    private static SystemScorer.Entry once(SystemScorer.Entry was, SystemScorer.Entry e) {
        if (was != null) {
            throw new IllegalStateException(e.name + " and " + was.name + " are the same row in the same scope");
        }
        return e;
    }

    private static Row row(CountKeys.Count plain, CountKeys.Count c, Map<String, Map<String, Object>> catalog) {
        if (c != plain && (c.base != plain || c.aceSideCount == 0)) {
            throw new IllegalStateException(c.name + " is scored beside " + plain.name + " but is not its ace side-count "
                    + "variant; the published rows hold no other kind");
        }
        boolean ace = c != plain;
        Row row;
        if (plain.systems.isEmpty()) {
            if (ace) {
                throw new IllegalStateException("the null count has no side-count variant");
            }
            row = new Row("null", "Null count", c);
            row.record.put("id", row.id);
            row.record.put("kind", "reference");
            row.record.put("level", 0L);
            row.record.put("balanced", true);
            row.record.put("side", "none");
            row.record.put("side_rule", "none");
            return row;
        }
        String first = plain.systems.get(0);
        Map<String, Object> sys = catalog.get(first);
        if (sys == null) {
            throw new IllegalStateException(first + " is not in the catalog");
        }
        String name = SiteInput.shortName(first) + (ace ? " + ace" : "");
        row = new Row(SiteInput.slug(SiteInput.shortName(first)) + (ace ? "_ace" : ""), name, c);
        row.record.put("id", row.id);
        row.record.put("kind", "published");
        row.record.put("level", ((Number) sys.get("level")).longValue());
        row.record.put("balanced", Boolean.TRUE.equals(sys.get("balanced")));
        row.record.put("side", ace ? "ace" : "none");
        String rule = "none";
        if (ace) {
            for (String s : plain.systems) {
                if (CountKeys.SIDE_COUNTED.contains(s)) {
                    rule = sideRule(String.valueOf(catalog.get(s).get("aceSideCount")));
                    break;
                }
            }
        }
        row.record.put("side_rule", rule);
        if (plain.systems.contains("Hi-Lo") && !ace) {
            row.record.put("role", "c1");
        }
        row.record.put("systems", new ArrayList<Object>(plain.systems));
        return row;
    }

    // ------------------------------------------------------------------ widening

    /**
     * The pilot's ratios (FullIndexPilot.readRatios: by table, by count name, {difference, its SE,
     * gain, its SE}) and the defaults for a count it did not measure: the largest ratio of the
     * kind it measured in either table (FULL_INDEX_PLAYS.md section 4).
     */
    static final class Widening {
        final Map<String, Map<String, double[]>> ratios;
        final String from;
        final double difference;
        final double gain;

        Widening(Map<String, Map<String, double[]>> ratios, String from) {
            this.ratios = ratios;
            this.from = from;
            double d = Double.NaN;
            double g = Double.NaN;
            for (Map<String, double[]> t : ratios.values()) {
                for (double[] r : t.values()) {
                    d = Double.isNaN(r[0]) ? d : Double.isNaN(d) ? r[0] : Math.max(d, r[0]);
                    g = Double.isNaN(r[2]) ? g : Double.isNaN(g) ? r[2] : Math.max(g, r[2]);
                }
            }
            if (Double.isNaN(d) || Double.isNaN(g)) {
                throw new IllegalArgumentException(from + " holds no difference or no gain ratio in its Results section");
            }
            this.difference = Math.max(1, d);
            this.gain = Math.max(1, g);
        }

        static Widening read(Path pilot) throws IOException {
            return new Widening(FullIndexPilot.readRatios(Files.readAllLines(pilot, StandardCharsets.UTF_8)),
                    pilot.toString());
        }

        private double own(CountKeys.Count c, int s, int at, double missing) {
            Map<String, double[]> t = ratios.get(table(s));
            double[] r = t == null ? null : t.get(c.name);
            return r == null || Double.isNaN(r[at]) ? missing : Math.max(1, r[at]);
        }

        /** A full-play level's ratio: its count's difference ratio (PLAN_PAGE.md 2.4). */
        double level(CountKeys.Count c, int s) {
            return own(c, s, 0, difference);
        }

        /** A gain's ratio: its count's gain ratio. */
        double gain(CountKeys.Count c, int s) {
            return own(c, s, 2, gain);
        }
    }

    // ------------------------------------------------------------------ the guards' figures

    private static final Pattern GUARD = Pattern.compile("\\{\\{(?:#if\\s+)?guard:(.*?)\\}\\}", Pattern.DOTALL);
    private static final Pattern KEY = Pattern.compile("[a-z0-9_]+(?:\\.[a-z0-9_@=]+)+");

    /**
     * Every figure key the template's claim guards name, as written: a row may be named by rank
     * (@best, @top5), which matches every row at that scope, penetration and spread.
     */
    static Set<String> guardKeys(String template) {
        Set<String> out = new LinkedHashSet<>();
        Matcher g = GUARD.matcher(template);
        while (g.find()) {
            Matcher k = KEY.matcher(g.group(1));
            while (k.find()) {
                out.add(k.group());
            }
        }
        return out;
    }

    /** Whether a guard key names this figure: the same key, or the same scope, penetration and spread with a rank for the row. */
    static boolean named(Set<String> guards, String scope, String pen, String spread, String id) {
        String at = scope + "." + pen + "." + spread + ".";
        for (String k : guards) {
            if (k.equals(at + id) || (k.startsWith(at) && k.substring(at.length()).startsWith("@"))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ figures

    /** An entry's interval in metric m at penetration p, its resamples widened by ratio first. */
    static Bootstrap.Interval interval(SystemScorer.Entry e, int p, int m, double ratio) {
        double[] b = e.boot[p][m];
        return Bootstrap.interval(e.estimate[p][m], ratio == 1 ? b : Bootstrap.widen(b, ratio));
    }

    /** The ratio a row's figure in a scope is widened by: a full-play level's, else none. */
    static double ratio(Widening w, Row r, String scope, int s) {
        return scope.equals("full") ? w.level(r.count, s) : 1;
    }

    /** The same schedule's average bet at p and s, the same for every row, or refused. */
    static double averageBet(List<Row> rows, int p, int s) {
        double bet = Double.NaN;
        String of = null;
        for (Row r : rows) {
            for (String scope : SCOPES) {
                SystemScorer.Entry e = r.in(scope);
                if (e == null || e.estimate[p] == null) {
                    continue;
                }
                double b = e.estimate[p][SystemScorer.sameBet(s)];
                if (Double.isNaN(bet)) {
                    bet = b;
                    of = e.name;
                } else if (Math.abs(b - bet) > 1e-12 * Math.abs(bet)) {
                    throw new IllegalStateException(e.name + " averages " + b + " units at 1-" + s + ", " + of + " " + bet
                            + ": the same schedule must give every row the same bets");
                }
            }
        }
        return bet;
    }

    /** What export reads besides results.ser. */
    static final class Options {
        Path stored;
        String storedShown;
        Widening widening;
        String template;
        Map<String, Map<String, Object>> catalog;
        /** The state file, read for its header only, to deal its shoes again for the rounds dealt; null leaves them out. */
        Path states;
        /** Step 7's save and its registration, step 8's run and its registration (ceilingRows); null leaves them out. */
        Path saved;
        String savedShown;
        Path counts;
        Path fullplay;
        String fullplayShown;
        Path fullplayCounts;
    }

    /** The input SiteData reads, from a run's results.ser. */
    static SiteInput export(Options o) throws Exception {
        CeilingReport.Published run = CeilingReport.read(o.stored);
        String commit = CeilingResults.line(run.identity, "commit ");
        SiteInput in = new SiteInput("results", commit, "main");
        in.artifact(o.stored, o.storedShown);
        List<Row> rows = rows(run.results, o.catalog);
        Set<String> guards = guardKeys(o.template);
        for (Row r : rows) {
            if (r.full != null && o.widening == null) {
                throw new IllegalStateException(r.full.name + " plays every decision, and no pilot was given to widen "
                        + "its intervals: the page shows no unwidened full-play interval");
            }
        }
        List<Object> table = new ArrayList<>();
        for (Row r : rows) {
            in.names.put(r.id, r.name);
            table.add(r.record);
            for (String scope : SCOPES) {
                if (r.in(scope) != null) {
                    final Row row = r;
                    cells(in, r.id, scope, r.in(scope), s -> ratio(o.widening, row, scope, s), guards);
                }
            }
            gains(in, r, o.widening);
        }
        ceilingRows(in, run, o, guards);
        in.grid.put("_widened", List.of("full"));
        in.tables.put("rows", table);
        perBet(in, rows, o.widening);
        identity(in, run.identity, o);
        game(in);
        method(in);
        tags(in, rows, o.catalog);
        return in;
    }

    /**
     * One row's grid cells in one scope at every penetration and spread, each widened by the ratio
     * given for its spread; with the resamples, as a figure too, where a guard names it.
     */
    static void cells(SiteInput in, String id, String scope, SystemScorer.Entry e,
                      java.util.function.IntToDoubleFunction ratio, Set<String> guards) {
        for (int p = 0; p < PENS.length; p++) {
            if (e.estimate[p] == null) {
                continue;
            }
            for (int s = 1; s <= SystemScorer.SPREADS; s++) {
                int m = metric(s);
                double w = ratio.applyAsDouble(s);
                Bootstrap.Interval iv = interval(e, p, m, w);
                in.cell(id, scope, PENS[p], "s" + s, new double[]{SiteInput.pct(iv.estimate), SiteInput.pct(iv.se),
                        SiteInput.pct(iv.lo), SiteInput.pct(iv.hi)});
                if (named(guards, scope, PENS[p], "s" + s, id)) {
                    Map<String, Object> f = in.figure(scope + "." + PENS[p] + ".s" + s + "." + id, iv, "s4", w);
                    f.put("boot", SiteInput.boot(e.boot[p][m]));
                    SiteInput.at(f, null, e.name + ", " + SystemScorer.PENETRATIONS[p] + ", metric " + m);
                }
            }
        }
    }

    /** The bound rows of step 7's save, by name, as the page's row perfect in each scope. */
    static final Map<String, String> BOUNDS = Map.of(
            "Chart play and perfect insurance, bet on its own value", "bet",
            "Perfect first move and insurance, bet on its own value", "fd",
            "Perfect play of the dealt hand and insurance, bet on its own value", "full");

    /**
     * The rows the page draws beside the published ones, from the ceiling count's files, at every
     * spread (CeilingResults' data file has only flat and 1-8): the bound rows of step 7's save as
     * the row perfect (betting only: the chart's play with perfect insurance; first decisions: the
     * perfect first move; full play: perfect play of the dealt hand; each bet on its own value and
     * fitted on nothing, so not widened), and each registered headline count by its family: step
     * 7's save at first decisions and betting only, step 8's run at full play, widened as any count
     * the pilot did not measure. Grid cells only: their names and rows-table records are the
     * ceiling adapter's (SiteCeiling), so one input describes each row, and where both give a
     * figure the generator holds the two to one value. Refuses a save made beside other stored
     * rows, a registration of other counts, and a run that does not pair.
     */
    static void ceilingRows(SiteInput in, CeilingReport.Published run, Options o, Set<String> guards) throws Exception {
        if (o.saved == null) {
            return;
        }
        CeilingReport.Saved saved = CeilingReport.readSaved(o.saved);
        String of = CeilingResults.line(saved.identity, "stored rows of ");
        if (of == null || !of.equals(run.line("commit "))) {
            throw new IllegalStateException("the save was made beside the stored rows of " + of + ", not of "
                    + run.line("commit "));
        }
        in.artifact(o.saved, o.savedShown);
        for (SystemScorer.Entry e : saved.bounds) {
            if (BOUNDS.containsKey(e.name)) {
                cells(in, "perfect", BOUNDS.get(e.name), e, s -> 1, guards);
            }
        }
        if (o.counts == null) {
            return;
        }
        CustomCounts.Listing counts = CustomCounts.read(o.counts);
        if (saved.custom.size() != counts.entries.size()) {
            throw new IllegalStateException("the save holds " + saved.custom.size() + " counts, the registration "
                    + counts.entries.size());
        }
        CeilingReport.Published full = o.fullplay == null ? null : CeilingReport.read(o.fullplay);
        CustomCounts.Listing fullCounts = full == null ? null : CustomCounts.read(o.fullplayCounts);
        if (full != null) {
            CeilingResults.requireSameRun(run, full);
            in.artifact(o.fullplay, o.fullplayShown);
        }
        for (int k = 0; k < counts.entries.size(); k++) {
            CustomCounts.Entry ce = counts.entries.get(k);
            SystemScorer.Result r = saved.custom.get(k);
            if (!r.count.name.equals(ce.count.name)) {
                throw new IllegalStateException("the save's count " + k + " is " + r.count.name + ", the registration's "
                        + ce.count.name);
            }
            if (!"headline".equals(ce.role) || ce.family == null) {
                continue;
            }
            String id = SiteAdapt.part(ce.family);
            SystemScorer.Entry first = CeilingReport.row(r);
            cells(in, id, "fd", first, s -> 1, guards);
            for (SystemScorer.Entry e : r.entries) {
                if (e.bettingOnly && e.count == first.count) {
                    cells(in, id, "bet", e, s -> 1, guards);
                }
            }
            int j = fullCounts == null ? -1 : fullCounts.indexOf(ce.count.name);
            if (j >= 0) {
                SystemScorer.Entry e = CeilingResults.fullRow(full.results.get(j));
                cells(in, id, "full", e, s -> o.widening.level(e.count, s), guards);
            }
        }
    }

    /** The gain from full play at each penetration, flat and 1-8, widened by the count's gain ratio. */
    static void gains(SiteInput in, Row r, Widening w) {
        if (r.full == null || r.first == null) {
            return;
        }
        for (int p = 0; p < PENS.length; p++) {
            if (r.full.estimate[p] == null || r.first.estimate[p] == null) {
                continue;
            }
            for (int s : new int[]{1, SystemScorer.SPREADS}) {
                double g = w.gain(r.count, s);
                Bootstrap.Interval iv = SystemScorer.gain(r.full, r.first, p, metric(s), g);
                Map<String, Object> f = in.figure("gain." + PENS[p] + ".s" + s + "." + r.id, iv, "s4", g);
                SiteInput.at(f, null, r.full.name + " less " + r.first.name + ", " + SystemScorer.PENETRATIONS[p]);
            }
        }
    }

    /**
     * The same schedule's average bet at each penetration and spread, and each row's EV per amount
     * bet at 1-8 (its EV over that bet, resample by resample, widened as its level), the figure to
     * set beside published "player advantage" figures.
     */
    static void perBet(SiteInput in, List<Row> rows, Widening w) {
        int s = SystemScorer.SPREADS;
        for (int p = 0; p < PENS.length; p++) {
            for (int t = 2; t <= s; t++) {
                double bet = averageBet(rows, p, t);
                if (!Double.isNaN(bet)) {
                    in.figure("avgbet." + PENS[p] + ".s" + t, bet, "d3");
                }
            }
            for (Row r : rows) {
                for (String scope : new String[]{"fd", "full"}) {
                    SystemScorer.Entry e = r.in(scope);
                    if (e == null || e.estimate[p] == null) {
                        continue;
                    }
                    int m = metric(s);
                    int b = SystemScorer.sameBet(s);
                    double[] per = new double[e.boot[p][m].length];
                    for (int k = 0; k < per.length; k++) {
                        per[k] = e.boot[p][m][k] / e.boot[p][b][k];
                    }
                    double ratio = ratio(w, r, scope, s);
                    double est = e.estimate[p][m] / e.estimate[p][b];
                    Bootstrap.Interval iv = Bootstrap.interval(est, ratio == 1 ? per : Bootstrap.widen(per, ratio));
                    Map<String, Object> f = in.figure("perbet." + scope + "." + PENS[p] + ".s" + s + "." + r.id, iv,
                            "s4", ratio);
                    SiteInput.at(f, null, e.name + ", " + SystemScorer.PENETRATIONS[p] + ", EV over average bet");
                }
            }
        }
    }

    /** The data's size and the bootstrap's, from the run's identity; the rounds dealt from the state file's own seed. */
    static void identity(SiteInput in, String identity, Options o) throws IOException {
        Matcher m = Pattern.compile("^shoes (\\d+), valued states (\\d+)$", Pattern.MULTILINE).matcher(identity);
        Matcher b = Pattern.compile("^resamples (\\d+), seed (\\d+)", Pattern.MULTILINE).matcher(identity);
        if (!m.find() || !b.find()) {
            throw new IllegalStateException("the run's identity names no shoes, valued states or resamples");
        }
        int shoes = Integer.parseInt(m.group(1));
        in.figure("data.shoes", shoes, "n");
        in.figure("data.states", Long.parseLong(m.group(2)), "n");
        in.figure("method.resamples", Long.parseLong(b.group(1)), "n");
        in.figure("method.ci", CI, "n");
        if (o.states != null) {
            String file = FullIndexRun.stateFile(o.states);
            if (!identity.contains("\n" + file + "\n")) {
                throw new IllegalStateException("the run was not scored on " + file);
            }
            long[] rounds = roundsDealt(StateStore.header(o.states), shoes);
            for (int p = 0; p < PENS.length; p++) {
                Map<String, Object> f = in.figure("data.rounds." + PENS[p], rounds[p], "n");
                SiteInput.at(f, file, "round starts before card " + SystemScorer.CUTS[p] + ", shoes dealt again from the "
                        + "header's seed");
            }
        }
    }

    /**
     * The rounds dealt at each cut: shoes 0 to shoes - 1 dealt again from a state file's header with
     * the chart, as the file was made, each round start before the cut counted (SHOE_SPEC.md A8).
     */
    static long[] roundsDealt(StateStore.Header h, int shoes) {
        if (h.cut != SystemScorer.CUTS[SystemScorer.CUTS.length - 1]) {
            throw new IllegalStateException("the state file is cut at card " + h.cut + ", not at the deepest cut "
                    + SystemScorer.CUTS[SystemScorer.CUTS.length - 1]);
        }
        ShoeRun run = new ShoeRun(h.seed, h.q0, h.u0, h.cut, StateRun.montreal(), BasicStrategy.chart());
        long[] out = new long[SystemScorer.CUTS.length];
        for (int i = 0; i < shoes; i++) {
            for (ShoeRun.State st : run.deal(i)) {
                for (int p = 0; p < out.length; p++) {
                    if (st.depth < SystemScorer.CUTS[p]) {
                        out[p]++;
                    }
                }
            }
        }
        return out;
    }

    /** The rules the engine ran with, from HouseRules, so the page cannot describe another game. */
    static void game(SiteInput in) {
        HouseRules hr = HouseRules.getMtlCasino25MinBlackjackParams(80);
        in.figure("game.decks", hr.numDecks, "n");
        in.figure("game.cards", hr.numDecks * 52, "n");
        in.figure("game.h17", hr.hitsOnSoft17 ? 1 : -1, "w:hits:stands");
        in.figure("game.bj_num", hr.blackjackPayout * 2, "n");
        in.figure("game.bj_den", 2, "n");
        in.figure("game.split_hands", hr.numSplitsNotAces + 1, "n");
        in.figure("game.peek", hr.dealerPeeksBlackjack ? 1 : -1, "w:checks:does not check");
        in.figure("game.surrender", hr.canLateSurrender || hr.canEarlySurrender ? 1 : -1, "w:is:is not");
        in.figure("game.das", hr.ranksThatCanBeDoubledDownAfterSplit.isEmpty() ? -1 : 1, "w:may:may not");
        in.figure("game.hit_split_aces", hr.canHitAfterSplittingAces ? 1 : -1, "w:may:may not");
        for (int p = 0; p < PENS.length; p++) {
            Map<String, Object> f = in.figure("game.pen." + PENS[p], Integer.parseInt(PENS[p]), "n");
            SiteInput.at(f, "src/SystemScorer.java", "PENETRATIONS[" + p + "], the cut at card " + SystemScorer.CUTS[p]);
        }
        in.figure("game.spread_max", SystemScorer.SPREADS, "n");
    }

    /**
     * The method's counts and the worked example, computed while the page is built: the deals of a
     * state (Deals), the legal first moves over them (StateValuer.legal), the tolerance a rerun of
     * the saved results is held to (CeilingResults.SAME_RUN), and a hard 16 against a ten off the
     * top of a fresh shoe, stood and hit, by ExactRound under the chart.
     */
    static void method(SiteInput in) {
        RoundRules rules = StateRun.montreal();
        in.figure("method.deals", Deals.COUNT, "n");
        long values = 0;
        for (int deal = 0; deal < Deals.COUNT; deal++) {
            for (PlayerMove m : StateValuer.MOVES) {
                values += StateValuer.legal(rules, deal, m) ? 1 : 0;
            }
        }
        in.figure("method.values_per_state", values, "n");
        in.figure("repro.ser_tolerance", CeilingResults.SAME_RUN, "e");
        int[] full = EffectsOfRemoval.fullShoe();
        int deal = Deals.index(6, 10, 10);
        ExactRound engine = new ExactRound(rules);
        in.figure("example.total", Deals.P1[deal] + Deals.P2[deal], "n");
        in.figure("example.up", Deals.UP[deal], "n");
        for (PlayerMove m : new PlayerMove[]{PlayerMove.Stand, PlayerMove.Hit}) {
            double v = engine.valueOfFirstMove(Deals.after(full, deal), Deals.P1[deal], Deals.P2[deal], Deals.UP[deal], m,
                    BasicStrategy.chart());
            Map<String, Object> f = in.figure("example." + m.name().toLowerCase(java.util.Locale.ROOT), v, "s4");
            SiteInput.at(f, "src/ExactRound.java", "valueOfFirstMove, a fresh shoe, 6 and 10 against 10, " + m.name());
        }
    }

    /** Each catalog count's tags by rank, A then 2 to 9 then T, and the ranks' and spreads' names. */
    @SuppressWarnings("unchecked")
    static void tags(SiteInput in, List<Row> rows, Map<String, Map<String, Object>> catalog) {
        for (int r = 1; r <= 10; r++) {
            in.names.put("r" + r, RANKS[r - 1]);
        }
        for (int s = 1; s <= SystemScorer.SPREADS; s++) {
            in.names.put("s" + s, s == 1 ? "flat" : "1-" + s);
        }
        for (Row row : rows) {
            if (!row.published() || row.count.base != null) {
                continue;
            }
            Map<String, Object> t = (Map<String, Object>) catalog.get(row.count.systems.get(0)).get("tags");
            if (t == null || !t.containsKey("A")) {
                continue;
            }
            boolean whole = true;
            for (String k : RANKS) {
                double v = Json.num(t.get(k), row.id + " " + k);
                whole &= v == Math.rint(v);
            }
            for (int r = 1; r <= 10; r++) {
                Map<String, Object> f = in.figure("tags." + row.id + ".r" + r, Json.num(t.get(RANKS[r - 1]), row.id),
                        whole ? "s0" : "s1");
                SiteInput.at(f, "counting-systems.json", row.count.systems.get(0) + ", tags." + RANKS[r - 1]);
            }
        }
    }

    /** Reads the flags, exports, and writes the file, never over one already there. */
    public static void main(String[] args) throws Exception {
        Map<String, String> a = new LinkedHashMap<>();
        for (int k = 0; k < args.length; k++) {
            if (!args[k].startsWith("--") || k + 1 >= args.length) {
                throw new IllegalArgumentException("unknown argument " + args[k]);
            }
            a.put(args[k].substring(2), args[++k]);
        }
        for (String need : new String[]{"stored", "template", "out"}) {
            if (!a.containsKey(need)) {
                throw new IllegalArgumentException("usage: SiteExport --stored RESULTS.ser --widening PILOT.txt "
                        + "--template site/template.html --out FILE [--states STATES] [--catalog counting-systems.json] "
                        + "[--saved STEP7.ser [--counts custom-counts.json] [--fullplay-run STEP8/results.ser "
                        + "--fullplay-counts custom-counts-fullplay.json]]");
            }
        }
        Options o = new Options();
        o.stored = Paths.get(a.get("stored"));
        o.storedShown = a.get("stored");
        o.widening = a.containsKey("widening") ? Widening.read(Paths.get(a.get("widening"))) : null;
        o.template = new String(Files.readAllBytes(Paths.get(a.get("template"))), StandardCharsets.UTF_8);
        o.catalog = catalog(Paths.get(a.getOrDefault("catalog", "counting-systems.json")));
        o.states = a.containsKey("states") ? Paths.get(a.get("states")) : null;
        if (a.containsKey("saved")) {
            o.saved = Paths.get(a.get("saved"));
            o.savedShown = a.get("saved");
            o.counts = a.containsKey("counts") ? Paths.get(a.get("counts")) : null;
        }
        if (a.containsKey("fullplay-run")) {
            if (o.counts == null || !a.containsKey("fullplay-counts")) {
                throw new IllegalArgumentException("--fullplay-run needs --saved, --counts and --fullplay-counts");
            }
            o.fullplay = Paths.get(a.get("fullplay-run"));
            o.fullplayShown = a.get("fullplay-run");
            o.fullplayCounts = Paths.get(a.get("fullplay-counts"));
        }
        SiteInput in = export(o);
        if (o.widening != null) {
            in.artifact(Paths.get(o.widening.from), o.widening.from);
        }
        in.write(Paths.get(a.get("out")));
        System.out.printf("SiteExport: %d rows from %s; wrote %s.%n", ((List<?>) in.tables.get("rows")).size(),
                o.stored, a.get("out"));
    }
}
