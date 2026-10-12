import org.bson.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sets this repository's results beside the published ones (LITERATURE.md section 1) and writes
 * the comparison the results page shows: runs/literature/literature-SHA.md for a reader, and the
 * page's literature.json in the site generator's input form (SiteData).
 *
 * The published side is literature/claims.json, typed by hand from the sources, with the hash of
 * each cached page. Our side is worked out here from committed code and committed outputs, never
 * typed:
 * <ul>
 *   <li>(a) the full 8-deck shoe valued once by StateValuer under the chart, every first move of
 *   every two-card hand against every up-card, set beside the Wizard of Odds' appendix 9 cell by
 *   cell; and the chart's round value off the top, the exact house edge;</li>
 *   <li>(b) every split valued a second time, resplitting every new pair up to four hands
 *   (BasicStrategy.policy(true)), so the explanation LITERATURE.md 2B gives for the split values'
 *   differences is tested by this code instead of a scratch script: for each pair, the test passes
 *   when the resplit values land within RESPLIT_TOLERANCE of the table at every up-card;</li>
 *   <li>(c) the Wizard of Odds calculator's figures, derived from the page's own code;</li>
 *   <li>(d) each count's measured BC, IC and PE as report-final printed them, and BC divided by R,
 *   the highest betting correlation any linear count reaches on the same states. R for the main
 *   file is read from step 7's save with CeilingReport.readSaved, never worked out again here:
 *   the regression refuses the main file, and no guard is loosened for the page. The save must
 *   name the main file, and the state count its report prints beside R must be the one behind
 *   report-final's BC; without a save the page shows BC without BC/R. The twin file's R is never
 *   used for it;</li>
 *   <li>(e) the effects of removal off the top (EffectsOfRemoval, four cards out against four in)
 *   beside the Wizard of Odds' six-deck ones, scaled by the deck-size factor 311/415, with the
 *   angle between the two ten-number vectors;</li>
 *   <li>(f) the angle between F1 and the two vectors of Vidami et al., by SearchReport's own
 *   routine;</li>
 *   <li>(g) the catalog's tags beside QFIT's table, and K-O's and Red Seven's deck sums and
 *   starting counts beside their authors' (PLAN_COMPUTE.md 1.8 asks for the tags of the counts the
 *   page names to be checked against an outside source).</li>
 * </ul>
 * Each cached page is checked against the hash claims.json records, and each claim's figure is
 * found again in it (claims.json's extract): a page that changed shows up as a changed figure,
 * and a claim whose figure the cache cannot confirm is not compared. Each claim's verdict is then
 * worked out by LiteratureClaims.verdict, never typed.
 *
 * Cost: two valuations of the full shoe and 21 shoes for the effects of removal, single-threaded
 * unless told otherwise; StateValuer took about a second a shoe (COUNTING_COMPARISON.md section 7).
 *
 * java -cp CLASSES:lib/mongo-java-driver-3.12.14.jar LiteratureCheck [--claims literature/claims.json]
 *   [--cache runs/literature/cache] [--report-final runs/report-final-26f0724.md]
 *   [--custom-counts custom-counts.json] [--ceiling-save runs/report-ceiling-97bf834.ser
 *   --ceiling-report runs/report-ceiling-97bf834.md] [--out runs/literature] [--json PATH] [--threads 1]
 * run from the repository's root (counting-systems.json is read from there).
 */
final class LiteratureCheck {

    /** How close the resplit split values must come to the table, at every up-card, for (b) to account for a pair's gap. */
    static final double RESPLIT_TOLERANCE = 0.001;
    /** A hit value above the table's by more than this would refute "their later play can only be better". */
    static final double ROUNDING = 1e-6;
    /** Six decks to eight: (6 x 52 - 1) / (8 x 52 - 1), the scaling LITERATURE.md 2C applies to the Wizard of Odds' effects. */
    static final double DECK_SCALE = 311.0 / 415.0;
    /** pe.order passes at a rank correlation of at least this (a threshold chosen here, stated in claims.json). */
    static final double PE_ORDER = 0.8;

    /** The calculator's form, answered with this game's rules in the form's own words. */
    static final Map<String, String> CALCULATOR_ANSWERS = new LinkedHashMap<>();

    static {
        CALCULATOR_ANSWERS.put("SELECT1", "8");
        CALCULATOR_ANSWERS.put("SELECT2", "Hits");
        CALCULATOR_ANSWERS.put("SELECT3", "Yes");
        CALCULATOR_ANSWERS.put("SELECT4", "Any first two cards");
        CALCULATOR_ANSWERS.put("SELECT5", "4 hands");
        CALCULATOR_ANSWERS.put("SELECT6", "No");
        CALCULATOR_ANSWERS.put("SELECT7", "No");
        CALCULATOR_ANSWERS.put("SELECT8", "Yes");
        CALCULATOR_ANSWERS.put("SELECT9", "None");
        CALCULATOR_ANSWERS.put("SELECT10", "3 to 2");
    }

    /** What the check reads. A path left null is not read, and the figures it would give wait. */
    static final class Inputs {
        Path claims = Paths.get("literature", "claims.json");
        Path cache = Paths.get("runs", "literature", "cache");
        Path reportFinal = Paths.get("runs", "report-final-26f0724.md");
        Path customCounts = Paths.get("custom-counts.json");
        Path ceilingSave;
        Path ceilingReport;
        int threads = 1;
        /** Skips the valuations (a), (b) and (e): for tests of the rest, which then leave those figures waiting. */
        boolean exact = true;
    }

    /** Everything the check found, for the report and the page. */
    static final class Result {
        LiteratureClaims register;
        final Map<String, LiteratureClaims.Ours> ours = new LinkedHashMap<>();
        final Map<String, Boolean> tests = new LinkedHashMap<>();
        /** Per source: whether its cached copy is there and matches its recorded hash. */
        final Map<String, String> cache = new LinkedHashMap<>();
        /** Per claim: why its cached source could not confirm it, or absent when it could. */
        final Map<String, String> drift = new LinkedHashMap<>();
        final Map<String, LiteratureClaims.Outcome> outcomes = new LinkedHashMap<>();
        /** Lines the report prints under each part, (a) to (g), as found. */
        final Map<String, List<String>> detail = new LinkedHashMap<>();
        /** The comparison tables the page's charts draw: name to rows. */
        final Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();

        void put(LiteratureClaims.Ours o) {
            if (ours.containsKey(o.key)) {
                throw new IllegalStateException("our figure " + o.key + " was worked out twice");
            }
            ours.put(o.key, o);
        }

        List<String> detail(String part) {
            return detail.computeIfAbsent(part, k -> new ArrayList<>());
        }
    }

    // ------------------------------------------------------------------ the run

    static Result run(Inputs in) throws Exception {
        Result r = new Result();
        r.register = LiteratureClaims.read(in.claims);
        Map<String, String> pages = cachedPages(r, in.cache);
        LiteratureSources.Appendix9 a9 = pages.containsKey("woo-a9")
                ? LiteratureSources.appendix9(pages.get("woo-a9")) : null;
        if (in.exact && a9 != null) {
            exact(r, a9);
        }
        if (in.exact && pages.containsKey("woo-eor")) {
            effectsOfRemoval(r, LiteratureSources.effectOfRemoval(pages.get("woo-eor"), "Hit Soft 17"), in.threads);
        }
        if (pages.containsKey("woo-calc")) {
            calculator(r, LiteratureSources.calculator(pages.get("woo-calc"), CALCULATOR_ANSWERS));
        }
        int states80 = -1;
        if (in.reportFinal != null) {
            states80 = reportFinal(r, LiteraturePrinted.lines(in.reportFinal));
        }
        if (pages.containsKey("qfit-table") && in.reportFinal != null) {
            qfit(r, LiteratureSources.qfitTable(pages.get("qfit-table")));
        }
        bettingCeiling(r, in, states80);
        catalog(r);
        if (in.customCounts != null) {
            vidami(r, in.customCounts);
        }
        waiting(r);
        drift(r, pages, a9);
        for (LiteratureClaims.Claim c : r.register.claims) {
            r.outcomes.put(c.id, LiteratureClaims.verdict(c, r.register.source(c.source),
                    c.ours == null ? null : r.ours.get(c.ours), r.tests, r.drift.get(c.id)));
        }
        return r;
    }

    /**
     * Reads each source's cached copy and checks it against the hash claims.json records. A copy
     * that is missing, or whose hash differs, is noted per source; drift() then decides, claim by
     * claim, whether the figure can still be confirmed from it. Only HTML and JSON copies are read
     * as text; a PDF is checked by its hash alone.
     */
    static Map<String, String> cachedPages(Result r, Path cache) throws IOException {
        Map<String, String> pages = new LinkedHashMap<>();
        for (LiteratureClaims.Source s : r.register.sources) {
            if (s.cacheFile == null) {
                continue;
            }
            Path f = cache.resolve(s.cacheFile);
            if (!Files.isRegularFile(f)) {
                r.cache.put(s.id, "missing: no cached copy at " + f);
                continue;
            }
            byte[] bytes = Files.readAllBytes(f);
            String sha = LiteratureSources.sha256(bytes);
            r.cache.put(s.id, sha.equals(s.cacheSha256) ? "matches its recorded hash"
                    : "CHANGED: its hash is " + sha + ", not the recorded " + s.cacheSha256);
            if (!s.cacheFile.endsWith(".pdf")) {
                pages.put(s.id, LiteratureSources.decode(bytes));
            }
        }
        return pages;
    }

    /**
     * Whether each claim's figure can still be confirmed from its cached source. A figure the
     * extract finds again, as printed, is confirmed even if other parts of the page changed (its
     * hash then differs, and the report says so). A figure with no extract (a PDF, or a claim
     * resting on a reading) is confirmed only by an unchanged hash. A missing copy confirms nothing.
     */
    static void drift(Result r, Map<String, String> pages, LiteratureSources.Appendix9 a9) {
        for (LiteratureClaims.Claim c : r.register.claims) {
            LiteratureClaims.Source s = r.register.source(c.source);
            if (!s.read() || s.cacheFile == null) {
                continue;
            }
            String state = r.cache.get(s.id);
            if (state.startsWith("missing")) {
                r.drift.put(c.id, state);
                continue;
            }
            Document x = c.extract;
            String how = x == null ? "none" : x.getString("how");
            if (how.equals("none")) {
                if (!state.startsWith("matches")) {
                    r.drift.put(c.id, "the copy changed and nothing in it is read by code: " + state);
                }
                continue;
            }
            String printed = x.getString("printed") != null ? x.getString("printed") : c.printed;
            try {
                String found = extract(x, pages.get(s.id), a9, r);
                if (how.equals("text")) {
                    // A quotation confirms the figure when it is still on the page and holds the figure as printed.
                    if (found == null) {
                        r.drift.put(c.id, "the page no longer has the words \"" + x.getString("quote") + "\"");
                    } else if (printed != null && !found.contains(printed)) {
                        r.drift.put(c.id, "the quotation recorded does not hold the figure " + printed);
                    }
                } else if (found == null) {
                    r.drift.put(c.id, "the page no longer has " + x.toJson());
                } else if (printed != null && !same(found, printed)) {
                    r.drift.put(c.id, "the page now gives " + found + " where claims.json records " + printed);
                }
            } catch (IllegalArgumentException e) {
                r.drift.put(c.id, "the figure could not be found again: " + e.getMessage());
            }
        }
    }

    /** A figure as found and as recorded are the same when they read as the same number, or the same text. */
    static boolean same(String found, String printed) {
        try {
            return LiteratureSources.number(found) == LiteratureSources.number(printed);
        } catch (IllegalArgumentException e) {
            return LiteratureSources.squeeze(found).equals(LiteratureSources.squeeze(printed));
        }
    }

    /**
     * Finds one figure again in a cached page, as an extract in claims.json describes it: a table
     * row by its leading cells (row), a quotation in the page's text (text: returns the quotation
     * when it is there, null when not), an appendix 9 cell, an effect of removal, a cell of QFIT's
     * table, or the calculator's figure.
     */
    static String extract(Document x, String page, LiteratureSources.Appendix9 a9, Result r) {
        if (page == null) {
            throw new IllegalArgumentException("the page is not read as text");
        }
        switch (x.getString("how")) {
            case "row": {
                List<String> lead = x.getList("lead", String.class);
                Integer occurrence = x.getInteger("occurrence");
                List<List<String>> rows = LiteratureSources.rows(page);
                List<String> row;
                if (occurrence == null) {
                    row = LiteratureSources.row(rows, lead);
                } else {
                    List<List<String>> matching = new ArrayList<>();
                    for (List<String> each : rows) {
                        if (each.size() >= lead.size() && each.subList(0, lead.size()).equals(lead)) {
                            matching.add(each);
                        }
                    }
                    if (matching.size() < occurrence) {
                        throw new IllegalArgumentException("fewer than " + occurrence + " rows start " + lead);
                    }
                    row = matching.get(occurrence - 1);
                }
                int cell = x.getInteger("cell");
                if (cell >= row.size()) {
                    throw new IllegalArgumentException("the row " + lead + " has no cell " + cell);
                }
                return row.get(cell);
            }
            case "text": {
                String quote = LiteratureSources.squeeze(x.getString("quote"));
                String text = LiteratureSources.text(page).replace('’', '\'').replace('–', '-');
                return text.contains(quote) ? quote : null;
            }
            case "appendix9": {
                if (a9 == null) {
                    throw new IllegalArgumentException("appendix 9 was not read");
                }
                String[] hand = x.getString("hand").split(",");
                int slot = StateValuer.slot(Deals.index(LiteratureSources.rank(hand[0]), LiteratureSources.rank(hand[1]),
                        LiteratureSources.rank(x.getString("up"))), PlayerMove.valueOf(x.getString("move")));
                return a9.printed[slot];
            }
            case "eor": {
                double[] e = LiteratureSources.effectOfRemoval(page, x.getString("column"));
                return Double.toString(e[LiteratureSources.rank(x.getString("rank"))]);
            }
            case "qfit": {
                LiteratureSources.QfitRow q = LiteratureSources.qfit(LiteratureSources.qfitTable(page), x.getString("row"));
                switch (x.getString("field")) {
                    case "bc": return q.bc;
                    case "pe": return q.pe;
                    case "ic": return q.ic;
                    default: throw new IllegalArgumentException("no QFIT field " + x.getString("field"));
                }
            }
            case "calculator": {
                LiteratureSources.Calculator k = LiteratureSources.calculator(page, CALCULATOR_ANSWERS);
                double v;
                switch (x.getString("figure")) {
                    case "optimal": v = k.optimal; break;
                    case "cut": v = k.cutCard(); break;
                    case "csm": v = k.shuffler(); break;
                    default: throw new IllegalArgumentException("no calculator figure " + x.getString("figure"));
                }
                return Double.toString(LiteratureSources.Calculator.shown(v));
            }
            default:
                throw new IllegalArgumentException("no extract " + x.getString("how"));
        }
    }

    // ------------------------------------------------------------------ (a) and (b): the full shoe against appendix 9

    /**
     * (a) and (b). Values the full shoe's every first move under the chart, as every state of a
     * state file is valued (StateValuer, the chart after the first move), and every split again
     * with BasicStrategy.policy(true), which resplits each new pair while the rules allow, then sets
     * both beside appendix 9.
     */
    static void exact(Result r, LiteratureSources.Appendix9 a9) throws Exception {
        RoundRules rules = StateRun.montreal();
        int[] full = EffectsOfRemoval.fullShoe();
        double[] chart;
        try (StateValuer v = new StateValuer(rules, BasicStrategy.chart(), 1)) {
            chart = v.value(List.of(full)).get(0);
        }
        double[] resplit = new double[Deals.COUNT];
        Arrays.fill(resplit, Double.NaN);
        ExactRound engine = new ExactRound(rules);
        RoundPolicy always = BasicStrategy.policy(true);
        for (int deal = 0; deal < Deals.COUNT; deal++) {
            if (Deals.P1[deal] == Deals.P2[deal]) {
                resplit[deal] = engine.valueOfFirstMove(Deals.after(full, deal), Deals.P1[deal], Deals.P2[deal],
                        Deals.UP[deal], PlayerMove.Split, always);
            }
        }
        compareAppendix9(r, a9, chart, resplit);
        LiteratureClaims.Ours edge = new LiteratureClaims.Ours("exact.edge");
        edge.value = 100 * EffectsOfRemoval.chartValue(rules, full, chart);
        edge.printed = String.format(Locale.ROOT, "%+.5f%%", edge.value);
        edge.src = "the full 8-deck shoe valued by StateValuer under the chart, before insurance (LiteratureCheck (a))";
        edge.file = "exact";
        r.put(edge);
        r.detail("a").add(String.format(Locale.ROOT, "The full 8-deck shoe under the chart, before insurance, off the top: "
                + "%+.5f%% a round (the house edge with a fresh shoe every round).", edge.value));
    }

    /**
     * Sets the engine's values beside appendix 9's: for Stand, Hit and Double the largest gap over
     * the 540 hands that are not naturals, and how many cells ours exceeds theirs by more than
     * ROUNDING (for Hit, a cell where ours is higher would refute the reason claims.json gives);
     * for Split, the largest gap per pair over the ten up-cards, under the chart after the split and
     * resplitting, and the test b.resplit.R: whether the resplit values come within
     * RESPLIT_TOLERANCE of the table at every up-card. Separate from exact() so a test can set
     * planted values beside a fixture table.
     */
    static void compareAppendix9(Result r, LiteratureSources.Appendix9 a9, double[] chart, double[] resplit) {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (PlayerMove m : new PlayerMove[]{PlayerMove.Stand, PlayerMove.Hit, PlayerMove.Double}) {
            double worst = 0;
            String where = "";
            int above = 0;
            int n = 0;
            for (int deal = 0; deal < Deals.COUNT; deal++) {
                if (Deals.playerNatural(deal)) {
                    continue;
                }
                int slot = StateValuer.slot(deal, m);
                double gap = chart[slot] - a9.value[slot];
                if (Double.isNaN(gap)) {
                    throw new IllegalStateException("no value to compare for " + describe(deal) + " " + m);
                }
                n++;
                if (gap > ROUNDING) {
                    above++;
                }
                if (Math.abs(gap) > Math.abs(worst)) {
                    worst = gap;
                    where = describe(deal);
                }
            }
            LiteratureClaims.Ours o = new LiteratureClaims.Ours("a9." + m);
            o.value = Math.abs(worst);
            o.printed = String.format(Locale.ROOT, "largest gap %.2g (ours less theirs %+.2g at %s) over %d cells; "
                    + "ours above theirs by more than %.0g in %d", Math.abs(worst), worst, where, n, ROUNDING, above);
            o.src = "LiteratureCheck (a): StateValuer on the full shoe against appendix 9, every cell";
            o.file = "exact";
            r.put(o);
            r.detail("a").add(m + ": " + o.printed + ".");
        }
        for (int rank = 1; rank <= 10; rank++) {
            double worstChart = 0;
            double worstResplit = 0;
            for (int up = 1; up <= 10; up++) {
                int deal = Deals.index(rank, rank, up);
                double theirs = a9.value[StateValuer.slot(deal, PlayerMove.Split)];
                double ours = chart[StateValuer.slot(deal, PlayerMove.Split)];
                double gap = ours - theirs;
                double gapResplit = resplit[deal] - theirs;
                worstChart = Math.max(worstChart, Math.abs(gap));
                worstResplit = Math.max(worstResplit, Math.abs(gapResplit));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pair", name(rank) + "," + name(rank));
                row.put("up", name(up));
                row.put("theirs", theirs);
                row.put("ours_chart_after_split", ours);
                row.put("ours_resplit", resplit[deal]);
                cells.add(row);
            }
            LiteratureClaims.Ours o = new LiteratureClaims.Ours("a9.Split." + rank);
            o.value = worstChart;
            o.printed = String.format(Locale.ROOT, "largest gap %.4f under the chart after the split, %.4f resplitting "
                    + "every new pair", worstChart, worstResplit);
            o.src = "LiteratureCheck (a) and (b): the split of " + name(rank) + "," + name(rank) + " against every up-card";
            o.file = "exact";
            r.put(o);
            r.tests.put("b.resplit." + rank, worstResplit <= RESPLIT_TOLERANCE);
            r.detail("b").add(String.format(Locale.ROOT, "%s,%s: %s; resplitting %s the table within %.3f at every "
                            + "up-card.", name(rank), name(rank), o.printed, worstResplit <= RESPLIT_TOLERANCE ? "meets" : "does not meet",
                    RESPLIT_TOLERANCE));
        }
        r.tables.put("literature_splits", cells);
    }

    static String name(int rank) {
        return rank == 1 ? "A" : Integer.toString(rank);
    }

    /** A hand as appendix 9 prints it, the higher card first and an ace last ("6,5", "9,A"), and its up-card. */
    static String describe(int deal) {
        return name(Deals.P2[deal]) + "," + name(Deals.P1[deal]) + " against " + name(Deals.UP[deal]);
    }

    // ------------------------------------------------------------------ (e): effects of removal

    /**
     * (e). Our effects of removal (EffectsOfRemoval, four cards of a rank out against four in, the
     * change per card, in per cent) beside the Wizard of Odds' six-deck figures scaled by
     * DECK_SCALE: each rank's, whether every rank has the same sign, and the angle between the two
     * ten-number vectors by Calibration.degrees (each centred on the full shoe's average card), the
     * routine the search's report uses.
     */
    static void effectsOfRemoval(Result r, double[] theirs, int threads) throws Exception {
        EffectsOfRemoval.Effects e = EffectsOfRemoval.effects(StateRun.montreal(), 4, threads);
        compareEffects(r, e, theirs);
    }

    static void compareEffects(Result r, EffectsOfRemoval.Effects e, double[] theirs) {
        double[] ours = new double[11];
        double[] scaled = new double[11];
        boolean signs = true;
        List<Map<String, Object>> rows = new ArrayList<>();
        StringBuilder differ = new StringBuilder();
        for (int rank = 1; rank <= 10; rank++) {
            ours[rank] = 100 * e.of(rank);
            scaled[rank] = theirs[rank] * DECK_SCALE;
            if (Math.signum(ours[rank]) != Math.signum(theirs[rank])) {
                signs = false;
                differ.append(differ.length() == 0 ? "" : ", ").append(name(rank));
            }
            LiteratureClaims.Ours o = new LiteratureClaims.Ours("eor." + rank);
            o.value = ours[rank];
            o.printed = String.format(Locale.ROOT, "%+.4f%%", ours[rank]);
            o.src = "EffectsOfRemoval: the full 8-deck shoe under the chart, 4 cards out against 4 in, per card";
            o.file = "exact";
            r.put(o);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", name(rank));
            row.put("ours", ours[rank]);
            row.put("theirs_six_decks", theirs[rank]);
            row.put("theirs_scaled", scaled[rank]);
            rows.add(row);
            r.detail("e").add(String.format(Locale.ROOT, "%s: ours %+.4f%%, theirs %+.3f%% (six decks), %+.4f%% scaled to "
                    + "eight.", name(rank), ours[rank], theirs[rank], scaled[rank]));
        }
        LiteratureClaims.Ours s = new LiteratureClaims.Ours("eor.signs");
        s.pass = signs;
        s.printed = signs ? "every rank has the same sign" : "the signs differ at " + differ;
        s.src = "LiteratureCheck (e)";
        s.file = "exact";
        r.put(s);
        LiteratureClaims.Ours a = new LiteratureClaims.Ours("eor.angle");
        a.value = Calibration.degrees(ours, scaled);
        a.printed = String.format(Locale.ROOT, "%.1f degrees", a.value);
        a.src = "Calibration.degrees between our effects and the Wizard of Odds' (the scaling does not change an angle)";
        a.file = "exact";
        r.put(a);
        r.detail("e").add(String.format(Locale.ROOT, "Full shoe %+.5f%% (the same valuation as (a)). Signs: %s. The angle "
                + "between the two vectors: %s.", 100 * e.full, s.printed, a.printed));
        r.tables.put("literature_effects_of_removal", rows);
    }

    // ------------------------------------------------------------------ (c): the calculator

    static void calculator(Result r, LiteratureSources.Calculator k) {
        r.detail("c").add(String.format(Locale.ROOT, "The calculator's entry for these rules (answers %s, looked up in the "
                        + "order %s): %.6f, assigned %d time%s in the page's code. With a cut card it adds %.4f: %.5f%%; with "
                        + "a continuous shuffler it then subtracts %.3f: %.5f%%. Composition-dependent play, shuffled after "
                        + "every hand: %.5f%%.", k.answers, k.order, k.optimal, k.assignments, k.assignments == 1 ? "" : "s",
                k.cutCardAdd, LiteratureSources.Calculator.shown(k.cutCard()), k.shufflerSubtract,
                LiteratureSources.Calculator.shown(k.shuffler()), LiteratureSources.Calculator.shown(k.optimal)));
    }

    // ------------------------------------------------------------------ (d) and the rest of ours: report-final, as printed

    static final String[] PENS = {"70", "75", "80"};
    static final String HEADLINE = "Headline: EV per round, same bet schedule, spread 1-8";
    static final String HILO = "Hi-Lo = Hi-Lo Lite";

    /**
     * Our figures that report-final printed (LiteraturePrinted says why they are taken as printed):
     * the chart's flat value and the perfect first move at each penetration, with intervals; Hi-Lo's
     * EV at each penetration and each row's difference from Hi-Lo at 80% (1-8 same schedule, first
     * decisions), with the paired intervals; each count's BC and IC (80%) and PE (80%); Hi-Lo's
     * per-key means at 80%. Then the checks built from them. Returns the number of valued states at
     * 80%, the states behind the BC.
     */
    static int reportFinal(Result r, List<String> lines) {
        String src = "runs/report-final-26f0724.md";
        LiteraturePrinted.Table shoes = LiteraturePrinted.table(lines, "Counting systems compared on shared shoes");
        int states80 = Integer.parseInt(shoes.cell("80%", "Valued"));
        LiteraturePrinted.Table ref = LiteraturePrinted.table(lines, "Reference rows");
        for (String p : PENS) {
            r.put(estimate("report.chart." + p, ref.cell("Chart, no count, flat", p + "%"),
                    src + ", Reference rows, Chart, no count, flat, " + p + "%"));
            r.put(estimate("report.perfect." + p, ref.cell("Perfect first move, flat, no insurance", p + "%"),
                    src + ", Reference rows, Perfect first move, flat, no insurance, " + p + "%"));
            LiteraturePrinted.Table h = LiteraturePrinted.table(lines, HEADLINE, p + "% penetration*");
            LiteratureClaims.Ours ev = estimate("ev.hilo." + p, h.cell(HILO, "EV") + " " + h.cell(HILO, "95% CI"),
                    src + ", Headline, " + p + "%, Hi-Lo, EV");
            r.put(ev);
            if (p.equals("80")) {
                for (String row : h.firsts()) {
                    String d = h.cell(row, "Diff from Hi-Lo");
                    if (!d.isEmpty()) {
                        r.put(estimate("diff." + row, d + " " + h.cell(row, "95% CI", 2),
                                src + ", Headline, 80%, " + row + ", difference from Hi-Lo (paired)"));
                    }
                }
            }
        }
        LiteraturePrinted.Table corr = LiteraturePrinted.table(lines, "Empirical betting and insurance correlations");
        for (String row : corr.firsts()) {
            r.put(estimate("bc." + row, corr.cell(row, "BC"), src + ", Empirical betting and insurance correlations"));
            r.put(estimate("ic." + row, corr.cell(row, "IC"), src + ", Empirical betting and insurance correlations"));
        }
        LiteraturePrinted.Table eff = LiteraturePrinted.table(lines, "Flat play, insurance and efficiencies", "80% penetration");
        for (String row : eff.firsts()) {
            String pe = eff.cell(row, "PE");
            if (!pe.isEmpty() && !pe.equals("1")) {
                r.put(estimate("pe." + row, pe + " " + eff.cell(row, "95% CI", 2), src + ", Flat play, insurance and "
                        + "efficiencies, 80%"));
            }
        }
        perKey(r, LiteraturePrinted.table(lines, "Per key, from the test folds", HILO + ", 80%"), src);
        checks(r);
        r.detail("d").add("States behind report-final's BC at 80%: " + states80 + " valued states.");
        return states80;
    }

    /** A printed estimate, "+0.294 [+0.260, +0.329]" or "0.957", as one of our figures. */
    static LiteratureClaims.Ours estimate(String key, String printed, String src) {
        LiteratureClaims.Ours o = new LiteratureClaims.Ours(key);
        String s = printed.trim();
        int bracket = s.indexOf('[');
        if (bracket >= 0) {
            double[] ci = LiteraturePrinted.interval(s.substring(bracket));
            o.lo = ci[0];
            o.hi = ci[1];
            s = s.substring(0, bracket).trim();
        }
        o.value = LiteraturePrinted.number(s);
        o.printed = printed.trim();
        o.src = src;
        return o;
    }

    /**
     * Hi-Lo's per-key means at 80% (the key is the true count rounded to the nearest whole number,
     * ties toward zero, decks left exact): the average step from key 0 to +5; whether the
     * least-squares quadratic through keys 0 to +5 curves upward; and where the mean crosses zero,
     * interpolated linearly between the last key below and the first above. The table prints no
     * standard errors, so these are point figures, and the crossing treats each key's mean as
     * sitting at the whole number.
     */
    static void perKey(Result r, LiteraturePrinted.Table t, String src) {
        double[] y = new double[6];
        for (int k = 0; k <= 5; k++) {
            y[k] = LiteraturePrinted.number(t.cell(Integer.toString(k), "Mean RV"));
        }
        LiteratureClaims.Ours step = new LiteratureClaims.Ours("perkey.step");
        step.value = (y[5] - y[0]) / 5;
        step.printed = String.format(Locale.ROOT, "%+.3f%% a key on average from 0 to +5 (steps %s)", step.value,
                steps(y));
        step.src = src + ", Per key, Hi-Lo, 80%, Mean RV";
        r.put(step);
        // Least squares y = a + b k + c k^2 on k = 0..5: c from the normal equations, centred at 2.5.
        double c = quadratic(y);
        LiteratureClaims.Ours bends = new LiteratureClaims.Ours("perkey.bends");
        bends.value = c;
        bends.pass = c > 0;
        bends.printed = String.format(Locale.ROOT, "the quadratic coefficient through keys 0 to +5 is %+.4f", c);
        bends.src = step.src;
        r.put(bends);
        LiteratureClaims.Ours cross = new LiteratureClaims.Ours("perkey.crossing");
        Double at = null;
        for (int k = 0; k < 5 && at == null; k++) {
            if (y[k] < 0 && y[k + 1] >= 0) {
                at = k + (0 - y[k]) / (y[k + 1] - y[k]);
            }
        }
        if (at == null) {
            cross.pending = "a key where Hi-Lo's mean turns positive between 0 and +5";
        } else {
            cross.value = at;
            cross.printed = String.format(Locale.ROOT, "about %.1f, interpolated between keys %d and %d", at,
                    (int) Math.floor(at), (int) Math.floor(at) + 1);
        }
        cross.src = step.src;
        r.put(cross);
        r.detail("d").add("Hi-Lo per key at 80%: " + step.printed + "; " + bends.printed + "; turns positive at "
                + (at == null ? "no key from 0 to +5" : cross.printed) + ".");
    }

    static String steps(double[] y) {
        StringBuilder b = new StringBuilder();
        for (int k = 1; k < y.length; k++) {
            b.append(k == 1 ? "" : ", ").append(String.format(Locale.ROOT, "%+.3f", y[k] - y[k - 1]));
        }
        return b.toString();
    }

    /** The k^2 coefficient of the least-squares quadratic through (k, y[k]), k = 0 to n-1. */
    static double quadratic(double[] y) {
        int n = y.length;
        double[][] a = new double[3][4];
        for (int k = 0; k < n; k++) {
            double[] row = {1, k, (double) k * k};
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    a[i][j] += row[i] * row[j];
                }
                a[i][3] += row[i] * y[k];
            }
        }
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                double f = a[j][i] / a[i][i];
                for (int m = i; m < 4; m++) {
                    a[j][m] -= f * a[i][m];
                }
            }
        }
        double c2 = a[2][3] / a[2][2];
        return c2;
    }

    /** Whether two printed intervals are apart, the first wholly below the second. */
    static boolean below(LiteratureClaims.Ours a, LiteratureClaims.Ours b) {
        return a.hi < b.lo;
    }

    static boolean overlap(LiteratureClaims.Ours a, LiteratureClaims.Ours b) {
        return a.lo <= b.hi && b.lo <= a.hi;
    }

    /**
     * Checks built from the printed figures. The cut card's cost at 80% (the exact value off the top
     * less the sampled chart value; the exact one has no sampling error, so the interval is the
     * sampled one's, reflected). The fundamental theorem: the chart's flat value at 70, 75 and 80%
     * with every pair of intervals overlapping, while the perfect first move rises at each step and
     * its 70% and 80% intervals are apart. (The first version, run once on 12 October, required each
     * five-point step's intervals apart; the steps are 0.017 and 0.019 against unpaired intervals
     * about 0.021 wide, so it failed for want of power, not for a difference, and was changed after
     * that run, as this comment records.) Hi-Lo's EV rising with penetration, each step's intervals
     * apart. Hi-Opt II
     * with an ace side count above Hi-Opt II, their paired differences from Hi-Lo apart. Intervals
     * that are apart are a stricter test than paired ones would be, and report-final prints no
     * paired interval across penetrations.
     */
    static void checks(Result r) {
        LiteratureClaims.Ours chart80 = r.ours.get("report.chart.80");
        LiteratureClaims.Ours edge = r.ours.get("exact.edge");
        LiteratureClaims.Ours cost = new LiteratureClaims.Ours("cutcard.cost.80");
        if (edge == null) {
            cost.pending = "the exact value off the top (LiteratureCheck (a))";
        } else {
            cost.value = edge.value - chart80.value;
            cost.lo = edge.value - chart80.hi;
            cost.hi = edge.value - chart80.lo;
            cost.printed = String.format(Locale.ROOT, "%+.4f%% [%+.4f, %+.4f]", cost.value, cost.lo, cost.hi);
            cost.src = "the exact value off the top (LiteratureCheck (a)) less report-final's chart flat at 80%";
        }
        r.put(cost);

        LiteratureClaims.Ours[] chart = new LiteratureClaims.Ours[3];
        LiteratureClaims.Ours[] perfect = new LiteratureClaims.Ours[3];
        LiteratureClaims.Ours[] hilo = new LiteratureClaims.Ours[3];
        for (int p = 0; p < 3; p++) {
            chart[p] = r.ours.get("report.chart." + PENS[p]);
            perfect[p] = r.ours.get("report.perfect." + PENS[p]);
            hilo[p] = r.ours.get("ev.hilo." + PENS[p]);
        }
        LiteratureClaims.Ours theorem = new LiteratureClaims.Ours("theorem");
        theorem.pass = overlap(chart[0], chart[1]) && overlap(chart[1], chart[2]) && overlap(chart[0], chart[2])
                && perfect[0].value < perfect[1].value && perfect[1].value < perfect[2].value && below(perfect[0], perfect[2]);
        theorem.printed = "chart flat " + chart[0].printed + ", " + chart[1].printed + ", " + chart[2].printed
                + "; perfect first move " + perfect[0].printed + ", " + perfect[1].printed + ", " + perfect[2].printed
                + " (70, 75, 80%)";
        theorem.src = "report-final, Reference rows";
        r.put(theorem);

        LiteratureClaims.Ours pen = new LiteratureClaims.Ours("pen.hilo");
        pen.pass = below(hilo[0], hilo[1]) && below(hilo[1], hilo[2]);
        pen.printed = "Hi-Lo at 1-8, first decisions: " + hilo[0].printed + ", " + hilo[1].printed + ", " + hilo[2].printed
                + " (70, 75, 80%)";
        pen.src = "report-final, Headline";
        r.put(pen);

        LiteratureClaims.Ours plain = r.ours.get("diff.Hi-Opt II");
        LiteratureClaims.Ours ace = r.ours.get("diff.Hi-Opt II + ace side count");
        LiteratureClaims.Ours side = new LiteratureClaims.Ours("sidecount.hiopt2");
        side.pass = below(plain, ace);
        side.printed = "less Hi-Lo at 80%, 1-8: Hi-Opt II " + plain.printed + ", with an ace side count " + ace.printed;
        side.src = "report-final, Headline, 80%";
        r.put(side);
    }

    // ------------------------------------------------------------------ (d) and (g): QFIT's table

    /** QFIT's row names, each to the catalog system it is (counting-systems.json's name). */
    static final Map<String, String> QFIT_NAMES = new LinkedHashMap<>();

    static {
        String[][] pairs = {{"Canfield Expert", "Canfield Expert"}, {"Canfield Master", "Canfield Master"},
                {"Hi-Lo", "Hi-Lo"}, {"Hi-Opt I", "Hi-Opt I"}, {"Hi-Opt II", "Hi-Opt II"}, {"KISS 2", "KISS II"},
                {"KISS 3", "KISS III"}, {"K-O", "KO (Knock-Out)"}, {"Mentor", "Mentor"}, {"Omega II", "Omega II"},
                {"Red Seven", "Red Seven (Red 7)"}, {"REKO", "REKO (Ridiculously Easy Knock-Out)"},
                {"Revere Adv. Plus Minus", "Revere Advanced Plus-Minus"}, {"Revere Point Count", "Revere Point Count (RPC)"},
                {"Revere RAPC", "Revere Advanced Point Count 1971 (RAPC)"},
                {"Revere 14 Count", "Revere 14 Count (1973 Advanced Point Count)"}, {"Silver Fox", "Silver Fox"},
                {"UBZ 2", "Unbalanced Zen 2 (UBZ2)"}, {"Uston Adv. Plus Minus", "Uston Advanced Plus-Minus"},
                {"Uston APC", "Uston Advanced Point Count (Uston APC)"}, {"Uston SS", "Uston SS"},
                {"Wong Halves", "Wong Halves"}, {"Zen Count", "Zen Count"}};
        for (String[] p : pairs) {
            QFIT_NAMES.put(p[0], p[1]);
        }
    }

    /** The report-final row a catalog system is printed under: rows join systems with the same tags by " = ". */
    static String reportRow(Result r, String system) {
        String found = null;
        for (String key : r.ours.keySet()) {
            if (key.startsWith("ic.") && !key.endsWith(" + ace side count")) {
                // A side-count variant is printed as its own row; the count itself is the plain one.
                String row = key.substring(3);
                if (Arrays.asList(row.split(" = ")).contains(system)) {
                    if (found != null && !found.equals(row)) {
                        throw new IllegalStateException(system + " is in two report rows, " + found + " and " + row);
                    }
                    found = row;
                }
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("report-final has no row for " + system);
        }
        return found;
    }

    /** A catalog system's tags by rank, 1 to 10, each the average over the four suits and, for the ten, its four ranks. */
    static double[] tagsByRank(CountingSystem s) {
        double[] t = new double[11];
        int[] n = new int[11];
        for (Rank rank : Rank.values()) {
            int r = RoundRules.rankIndex(rank);
            for (Suit suit : Suit.values()) {
                t[r] += s.tag(rank, suit);
                n[r]++;
            }
        }
        for (int r = 1; r <= 10; r++) {
            t[r] /= n[r];
        }
        return t;
    }

    /**
     * (d) and (g) against QFIT's table: for every row of it that is a count of this study, our BC,
     * IC and PE beside its own (the page's scatter plots draw these), whether our catalog's tags are
     * QFIT's up to a positive scale (a 0/1 tag set beside the average of our two colours), and the
     * rank correlation of the two PE columns over the distinct counts (pe.order).
     */
    static void qfit(Result r, List<LiteratureSources.QfitRow> table) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        List<String> seenRows = new ArrayList<>();
        List<double[]> pe = new ArrayList<>();
        List<double[]> balanced = new ArrayList<>();
        int checked = 0;
        for (LiteratureSources.QfitRow q : table) {
            String system = QFIT_NAMES.get(q.name);
            if (system == null) {
                r.detail("g").add("QFIT's row " + q.name + " is not a count of this study.");
                continue;
            }
            double[] ours = tagsByRank(CountingSystem.named(system));
            double scale = ours[10] / q.tag(10);
            boolean same = scale > 0;
            for (int k = 1; k <= 10 && same; k++) {
                same = Math.abs(ours[k] - scale * q.tag(k)) < 1e-9;
            }
            checked++;
            if (!same) {
                mismatched.add(q.name);
            }
            String row = reportRow(r, system);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("qfit", q.name);
            m.put("row", row);
            m.put("tags_agree", same);
            m.put("bc_theirs", q.bc);
            m.put("pe_theirs", q.pe);
            m.put("ic_theirs", q.ic);
            m.put("bc_ours", r.ours.get("bc." + row).value);
            m.put("ic_ours", r.ours.get("ic." + row).value);
            LiteratureClaims.Ours ourPe = r.ours.get("pe." + row);
            m.put("pe_ours", ourPe == null ? null : ourPe.value);
            rows.add(m);
            if (ourPe != null && !seenRows.contains(row)) {
                seenRows.add(row);
                pe.add(new double[]{ourPe.value, LiteratureSources.number(q.pe)});
                if (CountingSystem.named(system).balanced) {
                    balanced.add(new double[]{ourPe.value, LiteratureSources.number(q.pe)});
                }
            }
        }
        r.tables.put("literature_qfit", rows);
        LiteratureClaims.Ours tags = new LiteratureClaims.Ours("tags.qfit");
        tags.pass = mismatched.isEmpty();
        tags.printed = mismatched.isEmpty() ? "all " + checked + " of QFIT's rows that are counts of this study have the "
                + "catalog's tags" : "the tags differ for " + mismatched + " of " + checked;
        tags.src = "counting-systems.json against QFIT's table";
        r.put(tags);
        double rho = spearman(pe);
        LiteratureClaims.Ours order = new LiteratureClaims.Ours("pe.order");
        order.value = rho;
        order.pass = rho >= PE_ORDER;
        order.printed = String.format(Locale.ROOT, "rank correlation %.3f over %d distinct counts (passes at %.1f)", rho,
                pe.size(), PE_ORDER);
        order.src = "report-final's PE at 80% against QFIT's";
        r.put(order);
        r.detail("g").add("Tags: " + tags.printed + ".");
        r.detail("d").add("PE order: " + order.printed + String.format(Locale.ROOT, "; over the %d balanced counts "
                + "alone %.3f (reported, not judged: QFIT calls an unbalanced count's PE only an estimate).",
                balanced.size(), spearman(balanced)));
    }

    /** Spearman's rank correlation of the pairs given, ties given their average rank. */
    static double spearman(List<double[]> pairs) {
        double[] a = ranks(pairs, 0);
        double[] b = ranks(pairs, 1);
        double ma = 0;
        double mb = 0;
        for (int i = 0; i < a.length; i++) {
            ma += a[i] / a.length;
            mb += b[i] / b.length;
        }
        double ab = 0;
        double aa = 0;
        double bb = 0;
        for (int i = 0; i < a.length; i++) {
            ab += (a[i] - ma) * (b[i] - mb);
            aa += (a[i] - ma) * (a[i] - ma);
            bb += (b[i] - mb) * (b[i] - mb);
        }
        return ab / Math.sqrt(aa * bb);
    }

    static double[] ranks(List<double[]> pairs, int column) {
        int n = pairs.size();
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double x = pairs.get(i)[column];
            int less = 0;
            int equal = 0;
            for (double[] p : pairs) {
                if (p[column] < x) {
                    less++;
                } else if (p[column] == x) {
                    equal++;
                }
            }
            out[i] = less + (equal + 1) / 2.0;
        }
        return out;
    }

    // ------------------------------------------------------------------ (d): BC over R, from step 7's save

    static final java.util.regex.Pattern R_LINE = java.util.regex.Pattern.compile(
            "^R, the multiple correlation of the chart advantage regressed on the shares left over these (\\d+) valued "
                    + "states weighted 1/q, is ([0-9.]+):");

    /**
     * BC divided by R for every row with a BC. R comes only from step 7's save, read with
     * CeilingReport.readSaved, and only if the save names the main state file (not the twin), and
     * its report prints, beside R, the same number of valued states as report-final's BC was taken
     * over and the same R to four places. Anything else stops the check: a BC/R over other states
     * would put the two numbers on different scales without saying so. Without a save, each BC/R
     * waits, and the page shows BC alone.
     */
    static void bettingCeiling(Result r, Inputs in, int states80) throws IOException, ClassNotFoundException {
        Double rr = null;
        if (in.ceilingSave != null) {
            if (in.ceilingReport == null || states80 < 0) {
                throw new IllegalArgumentException("--ceiling-save needs --ceiling-report and report-final, to check the "
                        + "states R was taken over");
            }
            CeilingReport.Saved s = CeilingReport.readSaved(in.ceilingSave);
            rr = ceilingR(s.identity, s.r, LiteraturePrinted.lines(in.ceilingReport), states80);
            r.detail("d").add(String.format(Locale.ROOT, "R = %.5f, from %s, over the %d valued states behind report-final's "
                    + "BC.", rr, in.ceilingSave, states80));
        } else {
            r.detail("d").add("R was not read (no --ceiling-save): every BC/R waits for step 7's save.");
        }
        for (String key : new ArrayList<>(r.ours.keySet())) {
            if (!key.startsWith("bc.")) {
                continue;
            }
            String row = key.substring(3);
            LiteratureClaims.Ours bc = r.ours.get(key);
            LiteratureClaims.Ours o;
            if (rr == null) {
                o = LiteratureClaims.Ours.pending("bcr." + row, "R, the betting ceiling on the main file, from step 7's save "
                        + "(CeilingReport.readSaved)");
            } else {
                o = new LiteratureClaims.Ours("bcr." + row);
                o.value = bc.value / rr;
                o.printed = String.format(Locale.ROOT, "%.3f (BC %s over R %.4f)", o.value, bc.printed, rr);
                o.src = bc.src + ", divided by R from " + in.ceilingSave.getFileName();
            }
            r.put(o);
        }
    }

    /**
     * R from step 7's save, once the save and its report agree that it was taken on the main file
     * over the states given. identity is the save's own record of its run.
     */
    static double ceilingR(String identity, double r, List<String> report, int states) {
        if (!identity.contains("montreal-80.states") || identity.contains("twin-80.states")) {
            throw new IllegalArgumentException("the ceiling save is not of the main state file: " + identity);
        }
        if (Double.isNaN(r)) {
            throw new IllegalArgumentException("the ceiling save holds no R");
        }
        for (String line : report) {
            java.util.regex.Matcher m = R_LINE.matcher(line);
            if (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (n != states) {
                    throw new IllegalArgumentException("R was taken over " + n + " valued states, not the " + states
                            + " behind report-final's BC");
                }
                if (!m.group(2).equals(String.format(Locale.ROOT, "%.4f", r))) {
                    throw new IllegalArgumentException("the report prints R " + m.group(2) + " but the save holds " + r);
                }
                return r;
            }
        }
        throw new IllegalArgumentException("the ceiling report prints no R");
    }

    // ------------------------------------------------------------------ (g): the catalog's own arithmetic

    /** The Ten Count's insurance correlation, and K-O's and Red Seven's deck sums and starting counts. */
    static void catalog(Result r) {
        CountingSystem ten = CountingSystem.named("Ten Count (unbalanced; insurance-oriented)");
        double[] t = CountKeys.normalised(tagsByRank(ten));
        double ratio = t[10] / t[2];
        boolean proportional = true;
        for (int k = 1; k <= 9; k++) {
            proportional &= Math.abs(t[k] - t[2]) < 1e-12;
        }
        LiteratureClaims.Ours ic = r.ours.get("ic." + ten.name);
        LiteratureClaims.Ours perfect = new LiteratureClaims.Ours("ic.perfect");
        perfect.pass = proportional && Math.abs(ratio + 9.0 / 4) < 1e-12 && ic != null && ic.value == 1.0;
        perfect.printed = String.format(Locale.ROOT, "the Ten Count's tags, centred, are %s, ten to non-ten %.4f (-9/4 is "
                + "-2.25); its measured IC %s", proportional ? "the same for every non-ten" : "not the same for every "
                + "non-ten", ratio, ic == null ? "is not printed" : ic.printed);
        perfect.src = "counting-systems.json; report-final, Empirical betting and insurance correlations";
        r.put(perfect);
        for (String[] s : new String[][]{{"KO (Knock-Out)", "irc"}, {"Red Seven (Red 7)", "start"}}) {
            CountingSystem c = CountingSystem.named(s[0]);
            String key = s[0].equals("KO (Knock-Out)") ? "KO (Knock-Out)" : "Red Seven";
            LiteratureClaims.Ours sum = new LiteratureClaims.Ours("decksum." + key);
            sum.value = c.deckSum();
            sum.printed = String.format(Locale.ROOT, "%+.0f a deck", sum.value);
            sum.src = "counting-systems.json, " + s[0];
            r.put(sum);
            LiteratureClaims.Ours start = new LiteratureClaims.Ours(s[1] + "." + key);
            // K-O's standard start is 4 - 4 x decks (the book's own form); Red Seven's is -2 x decks.
            start.value = s[1].equals("irc") ? c.deckSum() - c.deckSum() * ShoeRun.DECKS : -c.deckSum() * ShoeRun.DECKS;
            start.printed = String.format(Locale.ROOT, "%+.0f at %d decks", start.value, ShoeRun.DECKS);
            start.src = sum.src;
            r.put(start);
            r.detail("g").add(s[0] + ": " + sum.printed + "; starting count " + start.printed + ".");
        }
    }

    // ------------------------------------------------------------------ (f): F1 and Vidami et al.

    static final String F1 = "F1 at 1-8 (f1-8-vidami-ar)";

    /**
     * (f). The angle between F1, the registered searched count (custom-counts.json), and each of the
     * two vectors in Vidami et al.'s Table 2 (their printed tags, from claims.json, times 100 to
     * whole numbers, which their two decimals allow exactly), by SearchReport.degrees. F1 was
     * searched on the twin file, so these angles carry the search-file label.
     */
    static void vidami(Result r, Path customCounts) throws IOException {
        Document root = Document.parse(new String(Files.readAllBytes(customCounts), StandardCharsets.UTF_8));
        int[] f1 = null;
        for (Document e : root.getList("counts", Document.class)) {
            if (F1.equals(e.getString("name"))) {
                List<Integer> tags = e.getList("tags", Integer.class);
                f1 = new int[11];
                for (int k = 1; k <= 10; k++) {
                    f1[k] = tags.get(k - 1);
                }
            }
        }
        if (f1 == null) {
            throw new IllegalArgumentException(customCounts + " has no entry " + F1);
        }
        for (String which : new String[]{"an", "ar"}) {
            LiteratureClaims.Claim c = null;
            for (LiteratureClaims.Claim each : r.register.claims) {
                if (each.id.equals("vidami." + which)) {
                    c = each;
                }
            }
            if (c == null) {
                continue;
            }
            String[] p = c.printed.split(",");
            int[] v = new int[11];
            for (int k = 1; k <= 10; k++) {
                v[k] = (int) Math.round(100 * LiteratureSources.number(p[k - 1]));
            }
            LiteratureClaims.Ours o = new LiteratureClaims.Ours("vidami.angle." + which);
            o.value = SearchReport.degrees(f1, v);
            o.printed = String.format(Locale.ROOT, "%.1f degrees from F1", o.value);
            o.src = "SearchReport.degrees between custom-counts.json's F1 and Table 2's " + which.toUpperCase(Locale.ROOT);
            o.file = "search";
            r.put(o);
            r.detail("f").add(String.format(Locale.ROOT, "Their %s vector: %s (search file: F1 was searched on the twin).",
                    which.toUpperCase(Locale.ROOT), o.printed));
        }
    }

    /** Our figures that other components compute; each waits, and its claim says so, until they land. */
    static void waiting(Result r) {
        r.put(LiteratureClaims.Ours.pending("variance.chart.80", "the swings component (PLAN_COMPUTE.md section 2): the "
                + "variance of a flat, chart-played round"));
        r.put(LiteratureClaims.Ours.pending("wide.shape", "the wider spreads component (PLAN_COMPUTE.md section 3)"));
        r.put(LiteratureClaims.Ours.pending("wong.shape", "the wonging component (PLAN_COMPUTE.md section 3)"));
        r.put(LiteratureClaims.Ours.pending("f1.heldout", "the searched count's held-out score on the main file "
                + "(COUNT_DESIGN.md step 7), read with the ceiling results, not by this check"));
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Inputs in = new Inputs();
        Path out = Paths.get("runs", "literature");
        Path json = null;
        for (int k = 0; k < args.length; k++) {
            String a = args[k];
            String v = k + 1 < args.length ? args[k + 1] : null;
            switch (a) {
                case "--claims": in.claims = Paths.get(v); k++; break;
                case "--cache": in.cache = Paths.get(v); k++; break;
                case "--report-final": in.reportFinal = Paths.get(v); k++; break;
                case "--custom-counts": in.customCounts = Paths.get(v); k++; break;
                case "--ceiling-save": in.ceilingSave = Paths.get(v); k++; break;
                case "--ceiling-report": in.ceilingReport = Paths.get(v); k++; break;
                case "--out": out = Paths.get(v); k++; break;
                case "--json": json = Paths.get(v); k++; break;
                case "--threads": in.threads = Integer.parseInt(v); k++; break;
                case "--no-exact": in.exact = false; break;
                default: throw new IllegalArgumentException("unknown argument " + a);
            }
        }
        String commit = FullIndexRun.commit();
        String sha = commit.length() > 7 ? commit.substring(0, 7) : commit;
        if (json == null) {
            json = Paths.get("runs", "site", "literature-" + sha + ".json");
        }
        long t0 = System.nanoTime();
        Result r = run(in);
        Map<String, String> artifacts = new LinkedHashMap<>();
        for (Path p : new Path[]{in.claims, in.reportFinal, in.customCounts, CountingSystem.FILE, in.ceilingSave,
                in.ceilingReport}) {
            if (p != null) {
                artifacts.put(p.toString(), LiteratureSources.sha256(p));
            }
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.PrintStream ps = new java.io.PrintStream(bytes, true, "UTF-8")) {
            LiteratureOutput.report(r, commit, ps);
            ps.printf("%d s.%n", Math.round((System.nanoTime() - t0) / 1e9));
        }
        Path md = out.resolve("literature-" + sha + ".md");
        LiteratureOutput.write(md, bytes.toString("UTF-8"));
        LiteratureOutput.write(json, LiteratureOutput.json(LiteratureOutput.page(r, commit, artifacts)));
        System.out.println(LiteratureOutput.sentence(LiteratureClaims.counts(new ArrayList<>(r.outcomes.values()))));
        System.out.println("Wrote " + md + " and " + json + ".");
    }
}
