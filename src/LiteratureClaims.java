import org.bson.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The register of published figures, literature/claims.json (LITERATURE.md section 1), and the
 * rule that turns each comparison into a verdict.
 *
 * Each source records who read it and how: its status (CHECKED, READ, SECONDARY, NOT READ) with
 * the date, checked_by (who did that reading: on this project an AI agent, never Justin unless he
 * says so) and read_by_justin. The page is written in Justin's first person, so a status shown
 * without checked_by would read as his own check and overstate how independent the literature
 * check is; that is why both fields are required on every source, and why the page's wording of
 * a status is built here from them and never typed.
 *
 * Each claim is one published figure: where it is (locator), the game it is for (conditions,
 * with its unit), the value exactly as printed, how to find it again in the cached page
 * (extract), which of our figures it is set beside (ours, a key LiteratureCheck fills), and the
 * comparison rule:
 * <ul>
 *   <li>within: ours less theirs (times multiply, a sign change or a deck-size scaling) no more
 *   than the tolerance, which the claim states;</li>
 *   <li>interval: theirs inside our 95% interval;</li>
 *   <li>sign: our whole interval (or, with none, our figure) on the side the source states, + or -;</li>
 *   <li>at_most: our figure, a largest gap over many cells, no more than the tolerance;</li>
 *   <li>above, below: our figure above or below theirs, for exact figures with no sampling error;</li>
 *   <li>check: a check LiteratureCheck ran and named, which passed;</li>
 *   <li>none: shown for context, not compared.</li>
 * </ul>
 * The verdict is then computed, never typed, as one of the kinds the page's sentence counts
 * (PLAN_PAGE.md section 1, item 9); sign, above and below give at most "agrees in direction":
 * <ul>
 *   <li>agrees: the rule holds and the conditions match;</li>
 *   <li>agrees in direction: the rule holds but the conditions differ, or the rule is only about
 *   a direction (a sign, with interval support);</li>
 *   <li>differs, for a reason tested by code here: the rule fails, and the claim names an
 *   explanation that a committed check tested and found to account for it;</li>
 *   <li>differs, for a reason not yet tested: an explanation is named but no code has tested it;</li>
 *   <li>differs, for no reason found: no explanation is named, or the one named was tested and
 *   did not account for it;</li>
 *   <li>not compared: our figure does not exist yet, the rule is "none" (context only), the
 *   source was not read, or the cached copy could not confirm the figure.</li>
 * </ul>
 * No sentence explains a difference away unless committed code tested the explanation
 * (PLAN_PAGE.md section 1, item 9): a reason with no test counts as untested, however likely.
 */
final class LiteratureClaims {

    enum Verdict {
        AGREES("agrees"),
        DIRECTION("agrees in direction"),
        TESTED("differs, for a reason tested by code here"),
        UNTESTED("differs, for a reason not yet tested"),
        UNEXPLAINED("differs, for no reason found"),
        NOT_COMPARED("not compared");

        final String words;

        Verdict(String words) {
            this.words = words;
        }
    }

    static final List<String> STATUSES = List.of("CHECKED", "READ", "SECONDARY", "NOT READ");

    /** A published source and who read it. */
    static final class Source {
        String id;
        String author;
        String title;
        String venue;
        String year;
        String updated;
        String isbn;
        String doi;
        String url;
        String snapshot;
        String cacheFile;
        String cacheSha256;
        String fetched;
        String status;
        String statusDate;
        String checkedBy;
        boolean readByJustin;
        String readByJustinDate;
        String via;
        String authorGroup;
        List<String> history = new ArrayList<>();
        String note;

        /** CHECKED and READ sources are the only ones whose figures the page states as published. */
        boolean read() {
            return status.equals("CHECKED") || status.equals("READ");
        }
    }

    /** One published figure and how it is compared. */
    static final class Claim {
        String id;
        String topic;
        String source;
        String locator;
        String what;
        Map<String, String> conditions = new LinkedHashMap<>();
        boolean sameGame;
        String printed;
        Document extract;
        String ours;
        String rule;
        double multiply = 1;
        double tolerance = Double.NaN;
        String sign;
        String reason;
        String testedBy;
        String note;

        /** The published value in our scale: as printed, times the claim's factor (a sign, or a deck-size scaling). */
        double theirs() {
            return LiteratureSources.number(printed) * multiply;
        }
    }

    /** One of our figures, as LiteratureCheck works it out, or why it does not exist yet. */
    static final class Ours {
        final String key;
        double value = Double.NaN;
        double lo = Double.NaN;
        double hi = Double.NaN;
        /** For a rule "check": whether the check LiteratureCheck ran passed. */
        Boolean pass;
        /** Ours as the report prints it, digits and all. */
        String printed;
        /** Where it comes from: the artifact and the table, row or computation. */
        String src;
        /** "main", "search" (the twin file; the page labels it) or "exact" (no sample). */
        String file = "main";
        /** Non-null when the figure does not exist yet, saying what it waits for. */
        String pending;

        Ours(String key) {
            this.key = key;
        }

        boolean hasInterval() {
            return !Double.isNaN(lo) && !Double.isNaN(hi);
        }

        static Ours pending(String key, String why) {
            Ours o = new Ours(key);
            o.pending = why;
            return o;
        }
    }

    /** A verdict and the plain reason for it, which the report and the page print beside it. */
    static final class Outcome {
        final Verdict verdict;
        final String why;

        Outcome(Verdict verdict, String why) {
            this.verdict = verdict;
            this.why = why;
        }
    }

    // ------------------------------------------------------------------ the register

    final List<Source> sources = new ArrayList<>();
    final List<Claim> claims = new ArrayList<>();

    Source source(String id) {
        for (Source s : sources) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        throw new IllegalArgumentException("claims.json has no source " + id);
    }

    static LiteratureClaims read(Path file) throws IOException {
        return parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), file.toString());
    }

    /**
     * Reads the register, refusing anything the page could misstate: a source without a status
     * from the four, without checked_by or read_by_justin, a SECONDARY source that does not say
     * through what it is known, a READ or CHECKED web source without a cached copy's hash or an
     * archive snapshot; a claim whose source is missing, whose conditions do not give a unit, or
     * whose rule is unknown or lacks what it needs; and an id given twice.
     */
    static LiteratureClaims parse(String json, String name) {
        Document root = Document.parse(json);
        LiteratureClaims l = new LiteratureClaims();
        for (Document d : root.getList("sources", Document.class)) {
            Source s = new Source();
            s.id = required(d, "id", name);
            s.author = d.getString("author");
            s.title = required(d, "title", name + " " + s.id);
            s.venue = d.getString("venue");
            s.year = d.getString("year");
            s.updated = d.getString("updated");
            s.isbn = d.getString("isbn");
            s.doi = d.getString("doi");
            s.url = d.getString("url");
            s.snapshot = d.getString("snapshot");
            Document cache = (Document) d.get("cache");
            if (cache != null) {
                s.cacheFile = required(cache, "file", name + " " + s.id + ".cache");
                s.cacheSha256 = required(cache, "sha256", name + " " + s.id + ".cache");
                s.fetched = required(cache, "fetched", name + " " + s.id + ".cache");
            }
            s.status = required(d, "status", name + " " + s.id);
            if (!STATUSES.contains(s.status)) {
                throw new IllegalArgumentException(name + ": " + s.id + "'s status is " + s.status + ", not one of " + STATUSES);
            }
            s.statusDate = s.status.equals("NOT READ") ? d.getString("status_date") : required(d, "status_date", name + " " + s.id);
            s.checkedBy = s.status.equals("NOT READ") ? d.getString("checked_by") : required(d, "checked_by", name + " " + s.id);
            if (!(d.get("read_by_justin") instanceof Boolean)) {
                throw new IllegalArgumentException(name + ": " + s.id + " must say read_by_justin, true or false");
            }
            s.readByJustin = d.getBoolean("read_by_justin");
            s.readByJustinDate = d.getString("read_by_justin_date");
            if (s.readByJustin && s.readByJustinDate == null) {
                throw new IllegalArgumentException(name + ": " + s.id + " is read by Justin but gives no date he says");
            }
            s.via = d.getString("via");
            if (s.status.equals("SECONDARY") && s.via == null) {
                throw new IllegalArgumentException(name + ": " + s.id + " is SECONDARY but does not say through what");
            }
            if (s.read() && s.url != null && s.cacheSha256 == null && s.snapshot == null) {
                throw new IllegalArgumentException(name + ": " + s.id + " is " + s.status + " but has neither a cached "
                        + "copy's hash nor an archive snapshot");
            }
            s.authorGroup = d.getString("author_group");
            if (d.get("history") != null) {
                s.history.addAll(d.getList("history", String.class));
            }
            s.note = d.getString("note");
            for (Source t : l.sources) {
                if (t.id.equals(s.id)) {
                    throw new IllegalArgumentException(name + ": source " + s.id + " twice");
                }
            }
            l.sources.add(s);
        }
        for (Document d : root.getList("claims", Document.class)) {
            Claim c = new Claim();
            c.id = required(d, "id", name);
            String at = name + " claim " + c.id;
            c.topic = required(d, "topic", at);
            c.source = required(d, "source", at);
            l.source(c.source);
            c.locator = required(d, "locator", at);
            c.what = required(d, "what", at);
            Document cond = (Document) d.get("conditions");
            if (cond == null || cond.getString("unit") == null) {
                throw new IllegalArgumentException(at + ": conditions must give the unit (LITERATURE.md section 4)");
            }
            for (Map.Entry<String, Object> e : cond.entrySet()) {
                c.conditions.put(e.getKey(), String.valueOf(e.getValue()));
            }
            if (!(d.get("same_game") instanceof Boolean)) {
                throw new IllegalArgumentException(at + ": same_game must be true or false");
            }
            c.sameGame = d.getBoolean("same_game");
            c.printed = d.getString("printed");
            c.extract = (Document) d.get("extract");
            c.ours = d.getString("ours");
            Document cmp = (Document) d.get("compare");
            if (cmp == null) {
                throw new IllegalArgumentException(at + ": no compare");
            }
            c.rule = required(cmp, "rule", at + ".compare");
            if (cmp.get("multiply") != null) {
                c.multiply = ((Number) cmp.get("multiply")).doubleValue();
            }
            if (cmp.get("tolerance") != null) {
                c.tolerance = ((Number) cmp.get("tolerance")).doubleValue();
            }
            c.sign = cmp.getString("sign");
            requireRule(c, at);
            Document why = (Document) d.get("if_differs");
            if (why != null) {
                c.reason = why.getString("reason");
                c.testedBy = why.getString("tested_by");
                if (c.testedBy != null && c.reason == null) {
                    throw new IllegalArgumentException(at + ": a test with no reason it tests");
                }
            }
            c.note = d.getString("note");
            for (Claim e : l.claims) {
                if (e.id.equals(c.id)) {
                    throw new IllegalArgumentException(name + ": claim " + c.id + " twice");
                }
            }
            l.claims.add(c);
        }
        return l;
    }

    private static void requireRule(Claim c, String at) {
        switch (c.rule) {
            case "within":
                if (Double.isNaN(c.tolerance) || c.printed == null || c.ours == null) {
                    throw new IllegalArgumentException(at + ": rule within needs printed, ours and a tolerance");
                }
                break;
            case "interval":
                if (c.printed == null || c.ours == null) {
                    throw new IllegalArgumentException(at + ": rule interval needs printed and ours");
                }
                break;
            case "sign":
                if (!"+".equals(c.sign) && !"-".equals(c.sign) || c.ours == null) {
                    throw new IllegalArgumentException(at + ": rule sign needs ours and a sign, + or -");
                }
                break;
            case "check":
                if (c.ours == null) {
                    throw new IllegalArgumentException(at + ": rule check needs ours");
                }
                break;
            case "at_most":
                if (Double.isNaN(c.tolerance) || c.ours == null) {
                    throw new IllegalArgumentException(at + ": rule at_most needs ours and a tolerance");
                }
                break;
            case "above":
            case "below":
                if (c.printed == null || c.ours == null) {
                    throw new IllegalArgumentException(at + ": rule " + c.rule + " needs printed and ours");
                }
                break;
            case "none":
                break;
            default:
                throw new IllegalArgumentException(at + ": unknown rule " + c.rule);
        }
    }

    private static String required(Document d, String key, String at) {
        Object v = d.get(key);
        if (!(v instanceof String) || ((String) v).isEmpty()) {
            throw new IllegalArgumentException(at + ": " + key + " is required");
        }
        return (String) v;
    }

    // ------------------------------------------------------------------ verdicts

    /**
     * The verdict on one claim. source is the claim's source; ours our figure or null; tests the
     * outcome of each named check LiteratureCheck ran (true: the explanation accounts for the
     * difference); drift non-null when the cached page could not confirm the published figure.
     */
    static Outcome verdict(Claim c, Source source, Ours ours, Map<String, Boolean> tests, String drift) {
        if (!source.read()) {
            return new Outcome(Verdict.NOT_COMPARED, "the source was not read (" + source.status + ")");
        }
        if (drift != null) {
            return new Outcome(Verdict.NOT_COMPARED, "the cached source could not confirm the figure: " + drift);
        }
        if (c.rule.equals("none")) {
            return new Outcome(Verdict.NOT_COMPARED, c.note != null ? c.note : "shown for context, not compared");
        }
        if (ours == null) {
            return new Outcome(Verdict.NOT_COMPARED, "our figure " + c.ours + " was not worked out");
        }
        if (ours.pending != null) {
            return new Outcome(Verdict.NOT_COMPARED, "our figure waits for " + ours.pending);
        }
        boolean pass;
        String how;
        switch (c.rule) {
            case "within": {
                double gap = ours.value - c.theirs();
                pass = Math.abs(gap) <= c.tolerance;
                how = String.format(Locale.ROOT, "ours less theirs %+.6g, %s the tolerance %.3g", gap,
                        pass ? "within" : "outside", c.tolerance);
                break;
            }
            case "interval": {
                if (!ours.hasInterval()) {
                    throw new IllegalArgumentException(c.id + ": rule interval but our figure " + ours.key + " has no interval");
                }
                double t = c.theirs();
                pass = t >= ours.lo && t <= ours.hi;
                how = String.format(Locale.ROOT, "theirs %.6g %s our 95%% interval [%.6g, %.6g]", t,
                        pass ? "inside" : "outside", ours.lo, ours.hi);
                break;
            }
            case "sign": {
                double edge = c.sign.equals("+") ? (ours.hasInterval() ? ours.lo : ours.value)
                        : -(ours.hasInterval() ? ours.hi : ours.value);
                pass = edge > 0;
                how = (ours.hasInterval() ? "our interval " : "our figure ") + (pass ? "is" : "is not") + " "
                        + (c.sign.equals("+") ? "above" : "below") + " zero throughout, the direction published";
                break;
            }
            case "at_most":
                pass = ours.value <= c.tolerance;
                how = String.format(Locale.ROOT, "the largest gap %.3g is %s the tolerance %.3g", ours.value,
                        pass ? "within" : "outside", c.tolerance);
                break;
            case "above":
            case "below": {
                double t = c.theirs();
                pass = c.rule.equals("above") ? ours.value > t : ours.value < t;
                how = String.format(Locale.ROOT, "ours %.6g is %s theirs %.6g", ours.value, ours.value > t ? "above"
                        : ours.value < t ? "below" : "equal to", t);
                break;
            }
            case "check":
                if (ours.pass == null) {
                    throw new IllegalArgumentException(c.id + ": rule check but " + ours.key + " ran no check");
                }
                pass = ours.pass;
                how = "the check " + (pass ? "passed" : "failed");
                break;
            default:
                throw new IllegalStateException(c.rule);
        }
        if (pass) {
            if (c.rule.equals("sign") || c.rule.equals("above") || c.rule.equals("below")) {
                return new Outcome(Verdict.DIRECTION, how);
            }
            return c.sameGame ? new Outcome(Verdict.AGREES, how)
                    : new Outcome(Verdict.DIRECTION, how + "; the published game differs from this one");
        }
        if (c.reason == null) {
            return new Outcome(Verdict.UNEXPLAINED, how + "; no reason is known");
        }
        if (c.testedBy == null) {
            return new Outcome(Verdict.UNTESTED, how + "; a likely reason, not tested by code: " + c.reason);
        }
        Boolean t = tests.get(c.testedBy);
        if (t == null) {
            return new Outcome(Verdict.UNTESTED, how + "; the check of the likely reason (" + c.testedBy + ") did not run: "
                    + c.reason);
        }
        return t ? new Outcome(Verdict.TESTED, how + "; tested by " + c.testedBy + ", which accounts for it: " + c.reason)
                : new Outcome(Verdict.UNEXPLAINED, how + "; the reason " + c.testedBy + " tested (" + c.reason
                + ") does not account for it");
    }

    /** How many outcomes of each kind, in the order of the enum. */
    static Map<Verdict, Integer> counts(List<Outcome> outcomes) {
        Map<Verdict, Integer> n = new LinkedHashMap<>();
        for (Verdict v : Verdict.values()) {
            n.put(v, 0);
        }
        for (Outcome o : outcomes) {
            n.put(o.verdict, n.get(o.verdict) + 1);
        }
        return n;
    }

    // ------------------------------------------------------------------ how a status is worded

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct",
            "Nov", "Dec"};

    /** 2026-10-09 as the page writes it: 9 Oct 2026. */
    static String date(String iso) {
        String[] p = iso.split("-");
        if (p.length != 3) {
            throw new IllegalArgumentException("not a date: " + iso);
        }
        return Integer.parseInt(p[2]) + " " + MONTHS[Integer.parseInt(p[1]) - 1] + " " + p[0];
    }

    /** Who did the reading, from checked_by ("AI agent (Claude Code), 2026-10-12"), as the page writes it. */
    static String who(String checkedBy) {
        String w = checkedBy.replaceFirst(",\\s*\\d{4}-\\d{2}-\\d{2}\\s*$", "").trim();
        return w.startsWith("AI agent") ? "an " + w : w;
    }

    /**
     * A source's status as the page words it (LITERATURE.md section 1): "checked against the source
     * by an AI agent (Claude Code) on 12 Oct 2026", "read by an AI agent (Claude Code) on 8 Oct
     * 2026", "known only through ...", "not read"; then, if Justin says he read it, that too. Never
     * worded as Justin's own reading unless read_by_justin says so.
     */
    static String statusWords(Source s, LiteratureClaims all) {
        String w;
        switch (s.status) {
            case "CHECKED":
                w = "checked against the source by " + who(s.checkedBy) + " on " + date(s.statusDate);
                break;
            case "READ":
                w = "read by " + who(s.checkedBy) + " on " + date(s.statusDate);
                break;
            case "SECONDARY": {
                String via = s.via;
                for (Source t : all.sources) {
                    if (t.id.equals(s.via)) {
                        via = t.title;
                    }
                }
                w = "known only through " + via;
                break;
            }
            default:
                w = "not read";
        }
        if (s.readByJustin) {
            w += "; read by Justin on " + date(s.readByJustinDate);
        }
        return w;
    }

    /** The sources whose status is CHECKED or READ, in register order. */
    List<Source> readSources() {
        List<Source> out = new ArrayList<>();
        for (Source s : sources) {
            if (s.read()) {
                out.add(s);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
