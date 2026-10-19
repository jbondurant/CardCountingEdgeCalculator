import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The export bundle of SHOE_SPEC.md Part B, read back on the engine side: what StrategyExport
 * writes for the independent simulator (PLAN_COMPUTE.md 1.2), parsed strictly from the files on
 * disk.
 *
 * Why a reader of our own, when the simulator has one: the round trip (StrategyExport.roundTrip)
 * must show that the files hold the strategy the report scored, and the only way to show it is
 * to read them back as the simulator will, from the text and the bytes, and score what was read.
 * Reading the writer's objects instead would pass whatever the writer got wrong in the files. So
 * nothing here calls the writer; it is written from Part B's words, and it refuses a file that
 * strays from them (a keyword out of order, a count of lines that is wrong, a field that does not
 * parse) rather than guess.
 *
 * The model classes (Strategy, Plan, States, Section, Exact) are what both sides share: the
 * writer fills them from the engine and formats them, this class parses them back.
 */
final class ExportBundle {

    static final int KINDS = ShoeRun.KINDS;
    /** The ace-up deals, 0 to 54 in Deals' order: the only ones insurance is offered on. */
    static final int ACE_UP = ScoringStates.ACE_UP;
    /** B2.1: each group has a first line for every deal but the ten player naturals. */
    static final int FIRST_LINES = Deals.COUNT - 10;
    /** The contested spots of a full-play file's later lines (FullIndexFit.CELLS). */
    static final int CELLS = FullIndexFit.CELLS;
    static final String MOVE_LETTERS = "SHDPR";
    static final int[] PENS = {70, 75, 80};

    private ExportBundle() {
    }

    // ------------------------------------------------------------------ the model

    /** B2.1's scope: count-based first decision, count-based play throughout, or the chart with the count's bets. */
    enum Scope {
        FIRST, FULL, BETTING;

        String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Scope of(String word) {
            for (Scope s : values()) {
                if (s.word().equals(word)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("no scope " + word);
        }
    }

    /**
     * A bet plan (B2.5): its label, rule and exit key (null for none), and the bet at each key of
     * the fold's test states, in increasing key order, as units and the chance the round is played.
     */
    static final class Plan {
        final String label;
        final String rule;
        final Integer exit;
        final int[] key;
        final double[] units;
        final double[] play;

        Plan(String label, String rule, Integer exit, int[] key, double[] units, double[] play) {
            if (key.length != units.length || key.length != play.length) {
                throw new IllegalArgumentException(label + ": a unit and a chance for every key");
            }
            for (int k = 1; k < key.length; k++) {
                if (key[k] <= key[k - 1]) {
                    throw new IllegalArgumentException(label + ": keys must increase, " + key[k - 1] + " then " + key[k]);
                }
            }
            this.label = label;
            this.rule = rule;
            this.exit = exit;
            this.key = key;
            this.units = units;
            this.play = play;
        }

        /**
         * The expected bet at a key, UNITS x PLAY (B2.5): the listed key's, or for a key with no
         * line the nearest listed key's, the lower one when two are equally near. With no lines at
         * all there is no bet to take, and it is refused.
         */
        double bet(int k) {
            int at = nearest(k);
            return units[at] * play[at];
        }

        /** The place of the listed key a key takes its bet from (B2.5's unseen rule). */
        int nearest(int k) {
            if (key.length == 0) {
                throw new IllegalStateException(label + " has no bet lines");
            }
            int at = Arrays.binarySearch(key, k);
            if (at >= 0) {
                return at;
            }
            int above = -at - 1;
            if (above == 0) {
                return 0;
            }
            if (above == key.length) {
                return key.length - 1;
            }
            long down = (long) k - key[above - 1];
            long up = (long) key[above] - k;
            return up < down ? above : above - 1;
        }
    }

    /** One strategy file (B2): one row, one penetration, one fold. */
    static final class Strategy {
        String row;
        Scope scope;
        int pen;
        int cut;
        int fold;
        double trained;
        String playCount;
        int[] tags;
        int scale;
        boolean trueCounted;
        int divisor;
        int deckSum;
        String betCount;
        int[] betTags;
        int betDeckSum;
        int ace;
        int[] lo;
        int[] hi;
        /** [g][deal]: the deal's legal first moves, best first; null for a player natural, and for scope betting. */
        PlayerMove[][][] first;
        /**
         * Scope full only, null otherwise: [g][deal], B2.1's split line for the deal in group g,
         * the ranking split hands read where it is not the first line's, null where the file has
         * no line (B2.4).
         */
        PlayerMove[][][] split;
        /** [g][ace-up deal]: whether the deal is insured in group g. */
        boolean[][] insure;
        /** Scope full only: the later-play groups and, [l][cell], whether the cell stands. */
        int[] laterLo;
        int[] laterHi;
        boolean[][] stands;
        final List<Plan> plans = new ArrayList<>();

        String fileName() {
            return fileName(row, pen, fold);
        }

        static String fileName(String row, int pen, int fold) {
            return "strategy-" + slug(row) + "-p" + pen + "-f" + fold + ".txt";
        }

        /** B2.3: the first group whose hi is at least the key; the last group takes every key above. */
        int group(int key) {
            return groupOf(hi, key);
        }

        int laterGroup(int key) {
            return groupOf(laterHi, key);
        }

        /**
         * B2.4: the dealt hand's first move in group g, the first move of its first line, for scope
         * first and full. The dealt hand never reads a split line.
         */
        PlayerMove dealtFirst(int g, int deal) {
            if (first == null || Deals.playerNatural(deal)) {
                throw new IllegalStateException(row + ": no first line for deal " + deal + " in a file of scope "
                        + scope.word());
            }
            return first[g][deal][0];
        }

        /**
         * B2.4: the moves a split hand of a full-play file reads for a deal in group g, in order: the
         * split line if the file has one, else the first line.
         */
        PlayerMove[] splitRanking(int g, int deal) {
            if (scope != Scope.FULL) {
                throw new IllegalStateException(row + ": split hands of a file of scope " + scope.word()
                        + " play the chart (B2.4)");
            }
            PlayerMove[] own = split == null ? null : split[g][deal];
            return own != null ? own : first[g][deal];
        }

        /** The file's split lines, counted. */
        int splitLines() {
            int n = 0;
            for (int g = 0; split != null && g < split.length; g++) {
                for (PlayerMove[] m : split[g]) {
                    n += m != null ? 1 : 0;
                }
            }
            return n;
        }

        Plan plan(String label) {
            for (Plan p : plans) {
                if (p.label.equals(label)) {
                    return p;
                }
            }
            return null;
        }
    }

    static int groupOf(int[] hi, int key) {
        for (int g = 0; g < hi.length; g++) {
            if (key <= hi[g]) {
                return g;
            }
        }
        return hi.length - 1;
    }

    /**
     * B0's slug: the name in lower case, every run of characters other than a to z and 0 to 9
     * made one '-', and no '-' at either end.
     */
    static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        int a = 0;
        int b = s.length();
        while (a < b && s.charAt(a) == '-') {
            a++;
        }
        while (b > a && s.charAt(b - 1) == '-') {
            b--;
        }
        return s.substring(a, b);
    }

    static char letter(PlayerMove m) {
        return MOVE_LETTERS.charAt(m.ordinal());
    }

    static PlayerMove move(String letter) {
        int at = letter.length() == 1 ? MOVE_LETTERS.indexOf(letter.charAt(0)) : -1;
        if (at < 0) {
            throw new IllegalArgumentException("no move " + letter);
        }
        return PlayerMove.values()[at];
    }

    // ------------------------------------------------------------------ text files

    /**
     * A text file's lines under B0's conventions: UTF-8, every line ending in one \n, the last
     * too, no \r. Refused otherwise, since a simulator reading by those rules would misread it.
     */
    static List<String> lines(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(text.getBytes(StandardCharsets.UTF_8), bytes)) {
            throw new IOException(file + " is not UTF-8");
        }
        return lines(text, file.getFileName().toString());
    }

    static List<String> lines(String text, String source) {
        if (text.isEmpty() || text.charAt(text.length() - 1) != '\n') {
            throw new IllegalArgumentException(source + ": the last line must end in \\n");
        }
        if (text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(source + ": a line ends in \\r");
        }
        return new ArrayList<>(Arrays.asList(text.substring(0, text.length() - 1).split("\n", -1)));
    }

    /** The lines of a file other than comments, each with its line number, read keyword by keyword. */
    static final class Cursor {
        final String source;
        final List<String[]> fields = new ArrayList<>();
        final List<Integer> number = new ArrayList<>();
        int at;
        /** The line number of the line next() last returned, or -1. */
        int last = -1;

        Cursor(List<String> lines, String source) {
            this.source = source;
            for (int n = 0; n < lines.size(); n++) {
                String l = lines.get(n);
                if (l.startsWith("#")) {
                    continue;
                }
                fields.add(l.split("\t", -1));
                number.add(n + 1);
            }
        }

        boolean done() {
            return at == fields.size();
        }

        /** The next line's keyword, or null at the end. */
        String peek() {
            return done() ? null : fields.get(at)[0];
        }

        /** The next line, which must start with keyword and have exactly count fields after it (-1: one or more). */
        String[] next(String keyword, int count) {
            if (done()) {
                throw failHere("the file ends where a " + keyword + " line is due");
            }
            String[] f = fields.get(at);
            if (!f[0].equals(keyword)) {
                throw failHere("a " + keyword + " line is due, not " + f[0]);
            }
            if (count >= 0 ? f.length != count + 1 : f.length < 2) {
                throw failHere(keyword + " takes " + (count >= 0 ? count : "one or more") + " fields, not "
                        + (f.length - 1));
            }
            for (String x : f) {
                if (x.isEmpty()) {
                    throw failHere("an empty field");
                }
            }
            last = number.get(at);
            at++;
            return f;
        }

        /** A refusal about the line next() last returned. */
        IllegalArgumentException fail(String why) {
            return new IllegalArgumentException(source + (last > 0 ? " line " + last : "") + ": " + why);
        }

        /** A refusal about the line due next, or the end of the file. */
        IllegalArgumentException failHere(String why) {
            return new IllegalArgumentException(source + (done() ? ", at its end" : " line " + number.get(at)) + ": "
                    + why);
        }

        int integer(String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                throw fail("not an integer: " + s);
            }
        }

        long longInteger(String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                throw fail("not an integer: " + s);
            }
        }

        /** B0: a double as Double.toString writes it, never NaN or Infinity. */
        double real(String s) {
            double x;
            try {
                x = Double.parseDouble(s);
            } catch (NumberFormatException e) {
                throw fail("not a number: " + s);
            }
            if (!Double.isFinite(x) || !s.equals(Double.toString(x))) {
                throw fail("not a double as Double.toString writes it: " + s);
            }
            return x;
        }

        boolean bool(String s) {
            if (s.equals("0") || s.equals("1")) {
                return s.equals("1");
            }
            throw fail("not 0 or 1: " + s);
        }

        int[] integers(String[] f, int from) {
            int[] out = new int[f.length - from];
            for (int k = 0; k < out.length; k++) {
                out[k] = integer(f[from + k]);
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ B2: strategy files

    static Strategy readStrategy(Path file) throws IOException {
        return parseStrategy(lines(file), file.getFileName().toString());
    }

    /** A strategy file's text, parsed as readStrategy parses the file. */
    static Strategy readStrategyText(String text) {
        return parseStrategy(lines(text, "a strategy file"), "a strategy file");
    }

    /** A strategy file parsed by B2.1's table, in its order, every count of lines checked. */
    static Strategy parseStrategy(List<String> lines, String source) {
        Cursor c = new Cursor(lines, source);
        Strategy s = new Strategy();
        String[] f = c.next("format", 2);
        if (!f[1].equals("strategy") || !f[2].equals("1")) {
            throw c.fail("not format strategy 1");
        }
        s.row = c.next("row", 1)[1];
        try {
            s.scope = Scope.of(c.next("scope", 1)[1]);
        } catch (IllegalArgumentException e) {
            throw c.fail(e.getMessage());
        }
        f = c.next("pen", 2);
        s.pen = c.integer(f[1]);
        s.cut = c.integer(f[2]);
        int p = Arrays.binarySearch(PENS, s.pen);
        if (p < 0 || s.cut != SystemScorer.CUTS[p]) {
            throw c.fail("no penetration " + s.pen + " with cut " + s.cut);
        }
        f = c.next("fold", 2);
        s.fold = c.integer(f[1]);
        s.trained = c.real(f[2]);
        if (s.fold != 0 && s.fold != 1 || s.trained < 0) {
            throw c.fail("fold " + s.fold + ", trained on " + s.trained);
        }
        s.playCount = c.next("playcount", 1)[1];
        s.tags = c.integers(c.next("tags", KINDS), 1);
        s.scale = c.integer(c.next("scale", 1)[1]);
        String mode = c.next("mode", 1)[1];
        if (!mode.equals("true") && !mode.equals("running")) {
            throw c.fail("mode " + mode);
        }
        s.trueCounted = mode.equals("true");
        s.divisor = c.integer(c.next("divisor", 1)[1]);
        if (s.divisor < 1) {
            throw c.fail("divisor " + s.divisor);
        }
        s.deckSum = c.integer(c.next("decksum", 1)[1]);
        if (s.deckSum != deckSum(s.tags)) {
            throw c.fail("decksum " + s.deckSum + " but the tags add to " + deckSum(s.tags));
        }
        s.betCount = c.next("betcount", 1)[1];
        s.betTags = c.integers(c.next("bettags", KINDS), 1);
        s.betDeckSum = c.integer(c.next("betdecksum", 1)[1]);
        if (s.betDeckSum != deckSum(s.betTags)) {
            throw c.fail("betdecksum " + s.betDeckSum + " but the bet tags add to " + deckSum(s.betTags));
        }
        s.ace = c.integer(c.next("ace", 1)[1]);
        if (s.ace < 0 || s.ace != 0 && !s.trueCounted) {
            throw c.fail("an ace side count of " + s.ace + " on a " + mode + " count");
        }
        int[][] g = groupLines(c, "groups", "group");
        s.lo = g[0];
        s.hi = g[1];
        if (s.scope != Scope.BETTING) {
            s.first = firstLines(c, s.lo.length);
        }
        s.split = splitLines(c, s);
        s.insure = insureLines(c, s.lo.length);
        if (s.scope == Scope.FULL) {
            int[][] l = groupLines(c, "lgroups", "lgroup");
            s.laterLo = l[0];
            s.laterHi = l[1];
            s.stands = laterLines(c, s.laterLo.length);
        }
        planLines(c, s);
        f = c.next("unseen", 2);
        if (!f[1].equals("nearest") || !f[2].equals("lower")) {
            throw c.fail("an unseen rule other than nearest lower");
        }
        c.next("end", 0);
        if (!c.done()) {
            throw c.failHere("a line after end");
        }
        return s;
    }

    static int deckSum(int[] tags) {
        int sum = 0;
        for (int t : tags) {
            sum += 2 * t;
        }
        return sum;
    }

    /** A count line and that many group lines, numbered from 0, lo at most hi and hi increasing. */
    private static int[][] groupLines(Cursor c, String countWord, String lineWord) {
        int n = c.integer(c.next(countWord, 1)[1]);
        if (n < 1) {
            throw c.fail(n + " groups");
        }
        int[] lo = new int[n];
        int[] hi = new int[n];
        for (int g = 0; g < n; g++) {
            String[] f = c.next(lineWord, 3);
            if (c.integer(f[1]) != g) {
                throw c.fail(lineWord + " " + g + " is due");
            }
            lo[g] = c.integer(f[2]);
            hi[g] = c.integer(f[3]);
            if (lo[g] > hi[g] || g > 0 && hi[g] <= hi[g - 1]) {
                throw c.fail("group " + g + " runs " + lo[g] + " to " + hi[g]);
            }
        }
        return new int[][]{lo, hi};
    }

    /** B2.1's first lines: for every group, the 540 deals in Deals' order, each with its moves, the first legal. */
    private static PlayerMove[][][] firstLines(Cursor c, int groups) {
        RoundRules rules = StateRun.montreal();
        PlayerMove[][][] out = new PlayerMove[groups][Deals.COUNT][];
        for (int g = 0; g < groups; g++) {
            for (int i = 0; i < Deals.COUNT; i++) {
                if (Deals.playerNatural(i)) {
                    continue;
                }
                String[] f = c.next("first", -1);
                if (f.length < 6) {
                    throw c.fail("a first line names at least one move");
                }
                if (c.integer(f[1]) != g || c.integer(f[2]) != Deals.P1[i] || c.integer(f[3]) != Deals.P2[i]
                        || c.integer(f[4]) != Deals.UP[i]) {
                    throw c.fail("first " + g + " " + Deals.P1[i] + " " + Deals.P2[i] + " " + Deals.UP[i] + " is due");
                }
                PlayerMove[] moves = new PlayerMove[f.length - 5];
                for (int k = 0; k < moves.length; k++) {
                    try {
                        moves[k] = move(f[5 + k]);
                    } catch (IllegalArgumentException e) {
                        throw c.fail(e.getMessage());
                    }
                }
                if (!StateValuer.legal(rules, i, moves[0])) {
                    throw c.fail(moves[0] + " is not legal on the dealt hand");
                }
                out[g][i] = moves;
            }
        }
        return out;
    }

    /**
     * B2.1's split lines, after every first line: [g][deal] for a file of scope full, null where
     * there is no line; null for another scope, which may have none. Each names a group and deal
     * that have a first line, in the first lines' order and at most once, and lists that line's
     * moves, each as many times, in another order; anything else is refused.
     */
    private static PlayerMove[][][] splitLines(Cursor c, Strategy s) {
        PlayerMove[][][] out = s.scope == Scope.FULL ? new PlayerMove[s.lo.length][Deals.COUNT][] : null;
        int previous = -1;
        while ("split".equals(c.peek())) {
            String[] f = c.next("split", -1);
            if (s.scope != Scope.FULL) {
                throw c.fail("a split line in a file of scope " + s.scope.word() + "; only scope full has them");
            }
            if (f.length < 6) {
                throw c.fail("a split line names at least one move");
            }
            int g = c.integer(f[1]);
            int p1 = c.integer(f[2]);
            int p2 = c.integer(f[3]);
            int up = c.integer(f[4]);
            if (g < 0 || g >= out.length || p1 < 1 || p1 > p2 || p2 > 10 || up < 1 || up > 10) {
                throw c.fail("split " + g + " " + p1 + " " + p2 + " " + up + " names no group and deal of the file");
            }
            int i = Deals.index(p1, p2, up);
            if (Deals.playerNatural(i)) {
                throw c.fail("a split line for the player's natural, which has no first line");
            }
            int order = g * Deals.COUNT + i;
            if (order <= previous) {
                throw c.fail("split lines go by group, then up-card, then p1, then p2, each at most once");
            }
            previous = order;
            PlayerMove[] moves = new PlayerMove[f.length - 5];
            for (int k = 0; k < moves.length; k++) {
                try {
                    moves[k] = move(f[5 + k]);
                } catch (IllegalArgumentException e) {
                    throw c.fail(e.getMessage());
                }
            }
            PlayerMove[] first = s.first[g][i];
            if (Arrays.equals(moves, first)) {
                throw c.fail("a split line repeats its first line's order; a file has none where the two agree");
            }
            int[] count = new int[MOVE_LETTERS.length()];
            for (PlayerMove m : moves) {
                count[m.ordinal()]++;
            }
            for (PlayerMove m : first) {
                count[m.ordinal()]--;
            }
            for (int n : count) {
                if (n != 0) {
                    throw c.fail("a split line lists " + Arrays.toString(moves) + ", not its first line's moves "
                            + Arrays.toString(first) + " in another order");
                }
            }
            out[g][i] = moves;
        }
        return out;
    }

    /** B2.1's insure lines: ace-up deals by group, in increasing (g, p1, p2), each at most once. */
    private static boolean[][] insureLines(Cursor c, int groups) {
        boolean[][] out = new boolean[groups][ACE_UP];
        int previous = -1;
        while ("insure".equals(c.peek())) {
            String[] f = c.next("insure", 3);
            int g = c.integer(f[1]);
            int p1 = c.integer(f[2]);
            int p2 = c.integer(f[3]);
            if (g < 0 || g >= groups || p1 < 1 || p1 > p2 || p2 > 10) {
                throw c.fail("insure " + g + " " + p1 + " " + p2);
            }
            int i = Deals.index(p1, p2, 1);
            int order = g * ACE_UP + i;
            if (order <= previous) {
                throw c.fail("insure lines go by group, then p1, then p2, each once");
            }
            previous = order;
            out[g][i] = true;
        }
        return out;
    }

    /**
     * The contested spots in a later group's order (B2.1: up-card, then hard before soft, then
     * total), each as {up, total, soft}: the hard totals 12 to 16 and the soft 13 to 18.
     */
    static int[][] cells() {
        int[][] out = new int[CELLS][];
        int n = 0;
        for (int up = 1; up <= 10; up++) {
            for (int soft = 0; soft <= 1; soft++) {
                for (int total = 4; total <= 21; total++) {
                    if (soft == 0 ? total >= 12 && total <= 16 : total >= 13 && total <= 18) {
                        out[n++] = new int[]{up, total, soft};
                    }
                }
            }
        }
        if (n != CELLS) {
            throw new AssertionError(n + " contested spots");
        }
        return out;
    }

    /** B2.1's later lines: every later group's 110 cells in cells()'s order, each S or H. */
    private static boolean[][] laterLines(Cursor c, int groups) {
        int[][] cells = cells();
        boolean[][] out = new boolean[groups][CELLS];
        for (int l = 0; l < groups; l++) {
            for (int cell = 0; cell < CELLS; cell++) {
                String[] f = c.next("later", 5);
                int[] x = cells[cell];
                if (c.integer(f[1]) != l || c.integer(f[2]) != x[1] || c.bool(f[3]) != (x[2] == 1)
                        || c.integer(f[4]) != x[0]) {
                    throw c.fail("later " + l + " " + x[1] + " " + x[2] + " " + x[0] + " is due");
                }
                if (!f[5].equals("S") && !f[5].equals("H")) {
                    throw c.fail("a later cell stands (S) or hits (H), not " + f[5]);
                }
                out[l][cell] = f[5].equals("S");
            }
        }
        return out;
    }

    /** B2.5's plan lines, then their bet lines, by plan in plan order and by key within one. */
    private static void planLines(Cursor c, Strategy s) {
        List<String[]> plans = new ArrayList<>();
        while ("plan".equals(c.peek())) {
            String[] f = c.next("plan", 3);
            if (!f[1].matches("[A-Za-z0-9.,+-]+") || !(f[2].equals("same") || f[2].equals("own"))) {
                throw c.fail("plan " + f[1] + " " + f[2]);
            }
            for (String[] q : plans) {
                if (q[1].equals(f[1])) {
                    throw c.fail("plan " + f[1] + " twice");
                }
            }
            if (!f[3].equals("-")) {
                c.integer(f[3]);
            }
            plans.add(f);
        }
        List<List<double[]>> bets = new ArrayList<>();
        for (int k = 0; k < plans.size(); k++) {
            bets.add(new ArrayList<>());
        }
        int current = 0;
        while ("bet".equals(c.peek())) {
            String[] f = c.next("bet", 4);
            while (current < plans.size() && !plans.get(current)[1].equals(f[1])) {
                current++;
            }
            if (current == plans.size()) {
                throw c.fail("bet lines go by plan in plan order; " + f[1] + " is out of place or not a plan");
            }
            double units = c.real(f[3]);
            double play = c.real(f[4]);
            if (units < 0 || play < 0 || play > 1) {
                throw c.fail("a bet of " + units + " units played with chance " + play);
            }
            bets.get(current).add(new double[]{c.integer(f[2]), units, play});
        }
        for (int k = 0; k < plans.size(); k++) {
            String[] f = plans.get(k);
            List<double[]> b = bets.get(k);
            int[] key = new int[b.size()];
            double[] units = new double[b.size()];
            double[] play = new double[b.size()];
            for (int j = 0; j < key.length; j++) {
                key[j] = (int) b.get(j)[0];
                units[j] = b.get(j)[1];
                play[j] = b.get(j)[2];
            }
            try {
                s.plans.add(new Plan(f[1], f[2], f[3].equals("-") ? null : Integer.valueOf(f[3]), key, units, play));
            } catch (IllegalArgumentException e) {
                throw c.fail(e.getMessage());
            }
            if (k > 0 && !Arrays.equals(key, s.plans.get(0).key)) {
                throw c.fail("plan " + f[1] + " bets on other keys than " + s.plans.get(0).label
                        + "; every plan lists the keys of the fold's test states");
            }
        }
    }

    // ------------------------------------------------------------------ B3: states.bin

    static final String STATES_MAGIC = "SIMSTATE";
    static final String STATES_END = "SIMSTEND";

    /** states.bin: the main file's valued states, and per exported row and penetration what the engine says of each. */
    static final class States {
        long seed;
        double q0;
        double u0;
        int cut;
        int shoes;
        int[] shoe;
        int[] round;
        int[] depth;
        double[] q;
        int[] fold;
        int[][] dealt;
        final List<Section> sections = new ArrayList<>();

        int size() {
            return shoe.length;
        }

        Section section(String row, int pen) {
            for (Section s : sections) {
                if (s.row.equals(row) && s.pen == pen) {
                    return s;
                }
            }
            return null;
        }
    }

    /** One (row, penetration) section of states.bin: an entry per state below the cut, in record order. */
    static final class Section implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        String row;
        int pen;
        int cut;
        String[] labels;
        int[] index;
        int[] betKey;
        double[] rvPlay;
        double[] rvInsurance;
        /** [entry][plan]: the expected bet under each plan, in labels' order. */
        double[][] bet;

        int plan(String label) {
            for (int k = 0; k < labels.length; k++) {
                if (labels[k].equals(label)) {
                    return k;
                }
            }
            return -1;
        }
    }

    /** B3, read field by field, every record and section checked for what B3 says of it, and nothing after the trailer. */
    static States readStates(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 20))) {
            String source = file.getFileName().toString();
            magic(in, STATES_MAGIC, source);
            int version = in.readInt();
            if (version != 1) {
                throw new IOException(source + ": version " + version);
            }
            States s = new States();
            s.seed = in.readLong();
            s.q0 = in.readDouble();
            s.u0 = in.readDouble();
            s.cut = in.readInt();
            s.shoes = in.readInt();
            int n = in.readInt();
            if (n < 0 || s.shoes < 0) {
                throw new IOException(source + ": " + n + " states of " + s.shoes + " shoes");
            }
            s.shoe = new int[n];
            s.round = new int[n];
            s.depth = new int[n];
            s.q = new double[n];
            s.fold = new int[n];
            s.dealt = new int[n][KINDS];
            for (int r = 0; r < n; r++) {
                s.shoe[r] = in.readInt();
                s.round[r] = in.readInt();
                s.depth[r] = in.readInt();
                s.q[r] = in.readDouble();
                s.fold[r] = in.readByte();
                int cards = 0;
                for (int k = 0; k < KINDS; k++) {
                    s.dealt[r][k] = in.readByte();
                    if (s.dealt[r][k] < 0 || s.dealt[r][k] > 2 * ShoeRun.DECKS) {
                        throw new IOException(source + ": record " + r + " has " + s.dealt[r][k] + " of kind " + k);
                    }
                    cards += s.dealt[r][k];
                }
                boolean ordered = r == 0 || s.shoe[r] > s.shoe[r - 1]
                        || s.shoe[r] == s.shoe[r - 1] && s.round[r] > s.round[r - 1];
                if (cards != s.depth[r] || s.depth[r] >= s.cut || s.fold[r] != (s.shoe[r] & 1) || !ordered
                        || s.shoe[r] < 0 || s.shoe[r] >= s.shoes || !(s.q[r] > 0 && s.q[r] <= 1)) {
                    throw new IOException(source + ": record " + r + " (shoe " + s.shoe[r] + ", round " + s.round[r]
                            + ", depth " + s.depth[r] + ", " + cards + " cards, fold " + s.fold[r] + ", q " + s.q[r]
                            + ") is not what B3 says a record is");
                }
            }
            int sections = in.readInt();
            for (int k = 0; k < sections; k++) {
                Section sec = section(in, s, source);
                if (!s.sections.isEmpty()) {
                    Section prev = s.sections.get(s.sections.size() - 1);
                    int order = prev.row.compareTo(sec.row);
                    if (order > 0 || order == 0 && prev.pen >= sec.pen) {
                        throw new IOException(source + ": sections go by row, then penetration, each once");
                    }
                }
                s.sections.add(sec);
            }
            magic(in, STATES_END, source);
            if (in.read() != -1) {
                throw new IOException(source + ": bytes after the trailer");
            }
            return s;
        }
    }

    private static Section section(DataInputStream in, States s, String source) throws IOException {
        Section sec = new Section();
        sec.row = string(in, source);
        sec.pen = in.readInt();
        sec.cut = in.readInt();
        int p = Arrays.binarySearch(PENS, sec.pen);
        if (p < 0 || sec.cut != SystemScorer.CUTS[p]) {
            throw new IOException(source + ": " + sec.row + " at penetration " + sec.pen + ", cut " + sec.cut);
        }
        int plans = in.readInt();
        if (plans < 0 || plans > 10_000) {
            throw new IOException(source + ": " + plans + " plans");
        }
        sec.labels = new String[plans];
        for (int k = 0; k < plans; k++) {
            sec.labels[k] = string(in, source);
        }
        int m = in.readInt();
        int below = 0;
        for (int r = 0; r < s.size(); r++) {
            below += s.depth[r] < sec.cut ? 1 : 0;
        }
        if (m != below) {
            throw new IOException(source + ": " + sec.row + " at " + sec.pen + "% has " + m + " entries for " + below
                    + " states below its cut");
        }
        sec.index = new int[m];
        sec.betKey = new int[m];
        sec.rvPlay = new double[m];
        sec.rvInsurance = new double[m];
        sec.bet = new double[m][plans];
        for (int e = 0; e < m; e++) {
            sec.index[e] = in.readInt();
            int r = sec.index[e];
            if (r < 0 || r >= s.size() || s.depth[r] >= sec.cut || e > 0 && r <= sec.index[e - 1]) {
                throw new IOException(source + ": " + sec.row + " at " + sec.pen + "%: entry " + e + " is record " + r);
            }
            sec.betKey[e] = in.readInt();
            sec.rvPlay[e] = finite(in.readDouble(), source);
            sec.rvInsurance[e] = finite(in.readDouble(), source);
            for (int k = 0; k < plans; k++) {
                sec.bet[e][k] = finite(in.readDouble(), source);
            }
        }
        return sec;
    }

    private static double finite(double x, String source) throws IOException {
        if (!Double.isFinite(x)) {
            throw new IOException(source + ": " + x + " where B0 allows only finite doubles");
        }
        return x;
    }

    private static void magic(DataInputStream in, String magic, String source) throws IOException {
        byte[] b = new byte[magic.length()];
        try {
            in.readFully(b);
        } catch (EOFException e) {
            throw new IOException(source + ": ends where " + magic + " is due");
        }
        if (!new String(b, StandardCharsets.US_ASCII).equals(magic)) {
            throw new IOException(source + ": " + magic + " is due");
        }
    }

    private static String string(DataInputStream in, String source) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 1 << 16) {
            throw new IOException(source + ": a string of " + n + " bytes");
        }
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ B5: exact.tsv

    static final String EXACT_FIRST = "# exact.tsv: SHOE_SPEC.md B5, format 1";
    static final String EXACT_HEADER = "row\tpen\tfold\tfigure\tmetric\tshoes\tsumA\tsumB\tsumAA\tsumAB\tsumBB";

    /** One line of exact.tsv: a strategy file's figure, its fold's sums over shoes and their moments. */
    static final class Exact {
        String row;
        int pen;
        int fold;
        String figure;
        int metric;
        int shoes;
        double sumA;
        double sumB;
        double sumAA;
        double sumAB;
        double sumBB;
    }

    static List<Exact> readExact(Path file) throws IOException {
        List<String> lines = lines(file);
        String source = file.getFileName().toString();
        if (lines.size() < 2 || !lines.get(0).equals(EXACT_FIRST) || !lines.get(1).equals(EXACT_HEADER)) {
            throw new IllegalArgumentException(source + ": B5's first line and header are due");
        }
        Cursor c = new Cursor(lines.subList(2, lines.size()), source);
        List<Exact> out = new ArrayList<>();
        while (!c.done()) {
            String[] f = c.fields.get(c.at);
            c.last = c.number.get(c.at) + 2;
            c.at++;
            if (f.length != 11) {
                throw c.fail("an exact.tsv line has 11 fields, not " + f.length);
            }
            Exact e = new Exact();
            e.row = f[0];
            e.pen = c.integer(f[1]);
            e.fold = c.integer(f[2]);
            e.figure = f[3];
            e.metric = c.integer(f[4]);
            e.shoes = c.integer(f[5]);
            e.sumA = c.real(f[6]);
            e.sumB = c.real(f[7]);
            e.sumAA = c.real(f[8]);
            e.sumAB = c.real(f[9]);
            e.sumBB = c.real(f[10]);
            if (Arrays.binarySearch(PENS, e.pen) < 0 || e.fold != 0 && e.fold != 1 || e.metric < -1
                    || e.metric >= SystemScorer.METRICS || e.shoes < 0) {
                throw c.fail("row " + e.row + ", penetration " + e.pen + ", fold " + e.fold + ", metric " + e.metric);
            }
            out.add(e);
        }
        return out;
    }

    // ------------------------------------------------------------------ B1: chart.tsv

    static final String CHART_FIRST = "# chart.tsv: SHOE_SPEC.md B1, format 1";
    static final String CHART_HEADER = "up\ttotal\tsoft\tpair\tfromSplit\tfirst\tlegal\tmove";
    static final int CHART_DECISIONS = 253_440;

    /** B1's order of the chart's decisions, each {up, total, soft, pair, fromSplit, first, legal}. */
    static int[][] chartDecisions() {
        int[][] out = new int[CHART_DECISIONS][];
        int n = 0;
        for (int up = 1; up <= 10; up++) {
            for (int total = 4; total <= 21; total++) {
                for (int soft = 0; soft <= 1; soft++) {
                    for (int pair = 0; pair <= 10; pair++) {
                        for (int fromSplit = 0; fromSplit <= 1; fromSplit++) {
                            for (int first = 0; first <= 1; first++) {
                                for (int legal = 0; legal < 16; legal++) {
                                    out[n++] = new int[]{up, total, soft, pair, fromSplit, first, legal};
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /** B1's fingerprint of moves numbered 0 Stand to 4 Surrender, in chart.tsv's order. */
    static String fingerprint(int[] moves) {
        long h = 1125899906842597L;
        for (int m : moves) {
            h = 31 * h + m;
        }
        return Long.toHexString(h);
    }

    /** chart.tsv read and rehashed: every line in B1's order, and the fingerprint of line 2 that of the moves read. */
    static PlayerMove[] readChart(Path file) throws IOException {
        List<String> lines = lines(file);
        String source = file.getFileName().toString();
        if (lines.size() != 3 + CHART_DECISIONS || !lines.get(0).equals(CHART_FIRST)
                || !lines.get(1).startsWith("# fingerprint ") || !lines.get(2).equals(CHART_HEADER)) {
            throw new IllegalArgumentException(source + ": B1's three first lines and " + CHART_DECISIONS
                    + " decisions are due");
        }
        int[][] order = chartDecisions();
        int[] moves = new int[CHART_DECISIONS];
        PlayerMove[] out = new PlayerMove[CHART_DECISIONS];
        for (int n = 0; n < CHART_DECISIONS; n++) {
            String[] f = lines.get(3 + n).split("\t", -1);
            StringBuilder want = new StringBuilder();
            for (int x : order[n]) {
                want.append(x).append('\t');
            }
            if (f.length != 8 || !lines.get(3 + n).startsWith(want.toString())) {
                throw new IllegalArgumentException(source + " line " + (4 + n) + ": decision " + Arrays.toString(order[n])
                        + " is due");
            }
            out[n] = move(f[7]);
            moves[n] = out[n].ordinal();
        }
        String said = lines.get(1).substring("# fingerprint ".length());
        if (!said.equals(fingerprint(moves))) {
            throw new IllegalArgumentException(source + ": the fingerprint " + said + " is not the moves', "
                    + fingerprint(moves));
        }
        return out;
    }

    // ------------------------------------------------------------------ B4: keys-sample.tsv

    static final String KEYS_FIRST = "# keys-sample.tsv: SHOE_SPEC.md B4, format 1";
    static final String KEYS_FIXED = "line\tstate\tshoe\tround\tdepth\tp1\tp2\tup";

    /** keys-sample.tsv: the counts its header names, and its lines. */
    static final class KeysSample {
        long seed;
        String[] counts;
        final List<KeyLine> lines = new ArrayList<>();
    }

    /**
     * One line: a bet line (p1, p2 and up 0) or a play line. Per count, keys[c] holds the keys
     * and chances[c] their chances, null for a single key of chance 1; keys[c] is null where the
     * line says '-', a deal the cards left cannot make.
     */
    static final class KeyLine {
        boolean bet;
        int state;
        int shoe;
        int round;
        int depth;
        int p1;
        int p2;
        int up;
        int[][] keys;
        double[][] chances;
    }

    static KeysSample readKeysSample(Path file) throws IOException {
        List<String> lines = lines(file);
        String source = file.getFileName().toString();
        if (lines.size() < 3 || !lines.get(0).equals(KEYS_FIRST) || !lines.get(1).startsWith("# sample seed ")
                || !lines.get(2).startsWith(KEYS_FIXED + "\t")) {
            throw new IllegalArgumentException(source + ": B4's three first lines are due");
        }
        KeysSample out = new KeysSample();
        Cursor c = new Cursor(lines.subList(2, lines.size()), source);
        out.seed = c.longInteger(lines.get(1).substring("# sample seed ".length()));
        String[] header = lines.get(2).split("\t", -1);
        out.counts = Arrays.copyOfRange(header, 8, header.length);
        c.at = 1;
        while (!c.done()) {
            String[] f = c.fields.get(c.at);
            c.last = c.number.get(c.at) + 2;
            c.at++;
            if (f.length != header.length || !f[0].equals("bet") && !f[0].equals("play")) {
                throw c.fail("a bet or play line of " + header.length + " fields is due");
            }
            KeyLine k = new KeyLine();
            k.bet = f[0].equals("bet");
            k.state = c.integer(f[1]);
            k.shoe = c.integer(f[2]);
            k.round = c.integer(f[3]);
            k.depth = c.integer(f[4]);
            if (k.bet) {
                if (!f[5].equals("-") || !f[6].equals("-") || !f[7].equals("-")) {
                    throw c.fail("a bet line has - for p1, p2 and up");
                }
            } else {
                k.p1 = c.integer(f[5]);
                k.p2 = c.integer(f[6]);
                k.up = c.integer(f[7]);
                if (k.p1 < 1 || k.p1 > k.p2 || k.p2 > 10 || k.up < 1 || k.up > 10) {
                    throw c.fail("no deal " + k.p1 + " " + k.p2 + " " + k.up);
                }
            }
            int n = out.counts.length;
            k.keys = new int[n][];
            k.chances = new double[n][];
            for (int x = 0; x < n; x++) {
                String cell = f[8 + x];
                if (cell.equals("-")) {
                    if (k.bet) {
                        throw c.fail("a bet line has a key in every column");
                    }
                    continue;
                }
                if (k.bet || !cell.contains(":")) {
                    k.keys[x] = new int[]{c.integer(cell)};
                    continue;
                }
                String[] parts = cell.split(";", -1);
                k.keys[x] = new int[parts.length];
                k.chances[x] = new double[parts.length];
                for (int y = 0; y < parts.length; y++) {
                    String[] kv = parts[y].split(":", -1);
                    if (kv.length != 2) {
                        throw c.fail("a spread is key:chance;key:chance, not " + cell);
                    }
                    k.keys[x][y] = c.integer(kv[0]);
                    k.chances[x][y] = c.real(kv[1]);
                }
            }
            out.lines.add(k);
        }
        return out;
    }

    // ------------------------------------------------------------------ B8: lookups.tsv

    static final String LOOKUPS_FIRST = "# lookups.tsv: SHOE_SPEC.md B8, format 1";
    static final String LOOKUPS_FIXED = "line\tstate\tp1\tp2\tup\thit";

    /** lookups.tsv: its sample seed, the strategy files its header names, and its lines as written. */
    static final class Lookups {
        long seed;
        String[] files;
        final List<LookupLine> lines = new ArrayList<>();
    }

    /** B8: the split lines of a bundle holding a file of scope full, one each split rank, second card and up-card. */
    static final int SPLIT_LINES = 1_000;

    /**
     * B8's split line n, 0 to 999, in its order (up-card, then the split rank r, then the second
     * card x): {r, x, up}.
     */
    static int[] splitLine(int n) {
        return new int[]{n / 10 % 10 + 1, n % 10 + 1, n / 100 + 1};
    }

    /**
     * One line: first (hit 0), later or split, and one cell a file, as text. A first or later
     * line holds its state and deal; a split line its split rank in p1 and second card in p2, as
     * written, state -1.
     */
    static final class LookupLine {
        boolean later;
        boolean split;
        int state;
        int p1;
        int p2;
        int up;
        int hit;
        String[] cells;
    }

    /**
     * B8's lines read and their fields checked for form; the cells are compared as text by the
     * caller. full says whether the bundle holds a file of scope full, which index.tsv's scopes
     * say and lookups.tsv does not: then exactly B8's 1,000 split lines are due, in their order,
     * after every other line; otherwise none.
     */
    static Lookups readLookups(Path file, boolean full) throws IOException {
        List<String> lines = lines(file);
        String source = file.getFileName().toString();
        if (lines.size() < 3 || !lines.get(0).equals(LOOKUPS_FIRST) || !lines.get(1).startsWith("# sample seed ")
                || !lines.get(2).startsWith(LOOKUPS_FIXED + "\t")) {
            throw new IllegalArgumentException(source + ": B8's three first lines are due");
        }
        Lookups out = new Lookups();
        Cursor c = new Cursor(lines.subList(2, lines.size()), source);
        out.seed = c.longInteger(lines.get(1).substring("# sample seed ".length()));
        String[] header = lines.get(2).split("\t", -1);
        out.files = Arrays.copyOfRange(header, 6, header.length);
        c.at = 1;
        boolean later = false;
        int split = 0;
        while (!c.done()) {
            String[] f = c.fields.get(c.at);
            c.last = c.number.get(c.at) + 2;
            c.at++;
            if (f.length != header.length || !f[0].equals("first") && !f[0].equals("later") && !f[0].equals("split")) {
                throw c.fail("a first, later or split line of " + header.length + " fields is due");
            }
            LookupLine l = new LookupLine();
            l.later = f[0].equals("later");
            l.split = f[0].equals("split");
            if (split > 0 && !l.split) {
                throw c.fail("split lines come after every first and later line");
            }
            if (later && !l.later && !l.split) {
                throw c.fail("first lines come before every later line");
            }
            later |= l.later;
            if (l.split) {
                if (!full) {
                    throw c.fail("a split line, and the bundle holds no file of scope full");
                }
                if (split == SPLIT_LINES) {
                    throw c.fail("more than " + SPLIT_LINES + " split lines");
                }
                int[] want = splitLine(split++);
                if (!f[1].equals("-") || !f[5].equals("-")) {
                    throw c.fail("a split line has - for state and hit");
                }
                l.state = -1;
                l.p1 = c.integer(f[2]);
                l.p2 = c.integer(f[3]);
                l.up = c.integer(f[4]);
                if (l.p1 != want[0] || l.p2 != want[1] || l.up != want[2]) {
                    throw c.fail("split " + want[0] + " " + want[1] + " " + want[2] + " is due");
                }
                l.cells = Arrays.copyOfRange(f, 6, f.length);
                out.lines.add(l);
                continue;
            }
            l.state = c.integer(f[1]);
            l.p1 = c.integer(f[2]);
            l.p2 = c.integer(f[3]);
            l.up = c.integer(f[4]);
            if (l.p1 < 1 || l.p1 > l.p2 || l.p2 > 10 || l.up < 1 || l.up > 10) {
                throw c.fail("no deal " + l.p1 + " " + l.p2 + " " + l.up);
            }
            if (l.later) {
                l.hit = c.integer(f[5]);
                if (l.hit < 1 || l.hit > 10) {
                    throw c.fail("no hit card of rank " + l.hit);
                }
            } else if (!f[5].equals("-")) {
                throw c.fail("a first line has - for hit");
            }
            l.cells = Arrays.copyOfRange(f, 6, f.length);
            out.lines.add(l);
        }
        if (full && split != SPLIT_LINES) {
            throw c.failHere(split + " split lines; the bundle holds a file of scope full, so all " + SPLIT_LINES
                    + " are due");
        }
        return out;
    }

    // ------------------------------------------------------------------ B0: index.tsv and manifest.sha256

    static final String INDEX_HEADER = "path\tbytes\trow\tpen\tfold\tscope";

    /** index.tsv: path to {bytes, row, pen, fold, scope}, in file order, the last four "-" for a file that is not a strategy file. */
    static Map<String, String[]> readIndex(Path file) throws IOException {
        List<String> lines = lines(file);
        String source = file.getFileName().toString();
        if (lines.isEmpty() || !lines.get(0).equals(INDEX_HEADER)) {
            throw new IllegalArgumentException(source + ": the header " + INDEX_HEADER + " is due");
        }
        Map<String, String[]> out = new LinkedHashMap<>();
        String previous = null;
        for (int n = 1; n < lines.size(); n++) {
            String[] f = lines.get(n).split("\t", -1);
            if (f.length != 6 || previous != null && previous.compareTo(f[0]) >= 0) {
                throw new IllegalArgumentException(source + " line " + (n + 1) + ": six fields, sorted by path");
            }
            previous = f[0];
            out.put(f[0], Arrays.copyOfRange(f, 1, 6));
        }
        return out;
    }

    /** manifest.sha256: path to SHA-256, as shasum -a 256 prints it, sorted by path. */
    static Map<String, String> readManifest(Path file) throws IOException {
        List<String> lines = lines(file);
        Map<String, String> out = new LinkedHashMap<>();
        String previous = null;
        for (int n = 0; n < lines.size(); n++) {
            String l = lines.get(n);
            if (!l.matches("[0-9a-f]{64}  [^\t]+") || previous != null && previous.compareTo(l.substring(66)) >= 0) {
                throw new IllegalArgumentException("manifest.sha256 line " + (n + 1) + ": '<sha256>  <path>', sorted by path");
            }
            previous = l.substring(66);
            out.put(previous, l.substring(0, 64));
        }
        return out;
    }

    static String sha256(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            for (int r; (r = in.read(buf)) > 0; ) {
                md.update(buf, 0, r);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ B2.2: keys, from the file's parameters

    /*
     * Why the keys are worked out here again, from B2.2's words and a strategy file's own
     * parameters, rather than with CountKeys: the round trip has to show that the parameters the
     * file gives (tags, deck sums, divisor, mode, ace weight) make the keys the engine bet and
     * played on. Computing them with the engine's Count would pass a file whose tags line was
     * wrong. ShoeSpecTest holds these same formulas to CountKeys for every count the comparison
     * scores, so the two together tie the file to the engine.
     */

    /** B2.2's rounding: the double nearest n / d, to the nearest whole number, ties toward zero. */
    static int round(long n, long d) {
        double x = (double) n / (double) d;
        return (int) (Math.signum(x) * Math.ceil(Math.abs(x) - 0.5));
    }

    /** The sum of the tags of every card of a tally by kind. */
    static long tally(int[] tags, int[] dealt) {
        long rc = 0;
        for (int k = 0; k < KINDS; k++) {
            rc += (long) dealt[k] * tags[k];
        }
        return rc;
    }

    /** B2.2's bet key of a state, from a file's bet tags, bet deck sum, ace weight, mode and divisor. */
    static int betKey(Strategy s, int[] dealt, int depth) {
        long rcb = tally(s.betTags, dealt);
        long d = s.divisor;
        if (!s.trueCounted) {
            return d == 1 ? (int) rcb : round(rcb, d);
        }
        long left = ShoeRun.CARDS - depth;
        long aces = 2L * 2 * ShoeRun.DECKS - dealt[0] - dealt[1];
        return round(52 * rcb - (long) s.betDeckSum * depth + (long) s.ace * (52 * aces - 4 * left), left * d);
    }

    /** B2.2's key at a decision: seen cards of the round seen, their tags adding to t, after a tally of rc. */
    static int keyAt(Strategy s, long rc, int depth, int t, int seen) {
        long d = s.divisor;
        if (!s.trueCounted) {
            return d == 1 ? (int) (rc + t) : round(rc + t, d);
        }
        return round(52 * (rc + t) - (long) s.deckSum * (depth + seen), (long) (ShoeRun.CARDS - depth - seen) * d);
    }

    /**
     * The tag classes of a count, from its tags by kind: for each rank 1 to 10, the distinct tags
     * its kinds carry, in kind order. A rank of one class is tagged alike whatever its colour or
     * face; a count with any rank of two classes is the colour or face count of B4.
     */
    static int[][] classes(int[] tags) {
        int[][] out = new int[11][];
        for (int r = 1; r <= 10; r++) {
            List<Integer> distinct = new ArrayList<>();
            for (int k = 0; k < KINDS; k++) {
                if (rank(k) == r && !distinct.contains(tags[k])) {
                    distinct.add(tags[k]);
                }
            }
            out[r] = distinct.stream().mapToInt(Integer::intValue).toArray();
        }
        return out;
    }

    static boolean kindAware(int[] tags) {
        for (int[] c : classes(tags)) {
            if (c != null && c.length > 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * The tag sums the deal's three cards (p1, p2, up) can carry with their chances, given the
     * cards dealt by kind: each card's tag class drawn without replacement from what is left of
     * its rank, p1 first, then p2, then the up-card, classes tried in order, and a sum met again
     * added to the first entry with it. {sum, chance} pairs in the order first met; none when the
     * cards left cannot make the deal. This is the order B4's play cells list their keys in.
     */
    static double[][] tagSums(int[] tags, int[] dealt, int p1, int p2, int up) {
        return tagSums(tags, classes(tags), dealt, p1, p2, up);
    }

    /** The same, with the count's classes(tags) worked out once by the caller. */
    static double[][] tagSums(int[] tags, int[][] cls, int[] dealt, int p1, int p2, int up) {
        int[][] left = new int[11][];
        for (int r = 1; r <= 10; r++) {
            left[r] = new int[cls[r].length + 1];
        }
        for (int k = 0; k < KINDS; k++) {
            int r = rank(k);
            int c = 0;
            while (cls[r][c] != tags[k]) {
                c++;
            }
            int n = 2 * ShoeRun.DECKS - dealt[k];
            left[r][c] += n;
            left[r][cls[r].length] += n;
        }
        List<double[]> out = new ArrayList<>();
        spread(new int[]{p1, p2, up}, cls, left, 0, 0, 1.0, out);
        return out.toArray(new double[0][]);
    }

    private static void spread(int[] ranks, int[][] cls, int[][] left, int at, int sum, double p, List<double[]> out) {
        if (at == 3) {
            for (double[] e : out) {
                if (e[0] == sum) {
                    e[1] += p;
                    return;
                }
            }
            out.add(new double[]{sum, p});
            return;
        }
        int r = ranks[at];
        int n = cls[r].length;
        if (left[r][n] <= 0) {
            return;
        }
        for (int c = 0; c < n; c++) {
            if (left[r][c] <= 0) {
                continue;
            }
            double pc = p * left[r][c] / left[r][n];
            left[r][c]--;
            left[r][n]--;
            spread(ranks, cls, left, at + 1, sum + cls[r][c], pc, out);
            left[r][c]++;
            left[r][n]++;
        }
    }

    /** The contract's rank of a kind, as A1 gives it: 1 for the ace, 2 to 9, and 10 for the ten, J, Q and K. */
    static int rank(int kind) {
        int rank13 = kind / 2;
        return rank13 >= 9 ? 10 : rank13 + 1;
    }
}
