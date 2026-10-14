package sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One fitted strategy, read from a bundle's {@code strategy-<slug>-p<P>-f<F>.txt} (SHOE_SPEC.md
 * B2): one row at one penetration, fitted on one fold and played on the other's states. It holds
 * the row's keys, its first-decision groups and ranked moves, its insurance, its later plays if
 * it has them, and its bet plans. {@link Play} plays it in a round, as B2.4 says.
 *
 * <p>The reader is strict: every line must come in B2.1's order, every keyword marked once must
 * appear once, the {@code first} and {@code later} lines must be exactly the deals and spots B2.1
 * lists in its order, and the file must end with {@code end}. A file that is not whole, or that
 * was written to another reading of the format, is refused rather than half used.
 */
public final class Strategy {

    /** The keywords of B2.1, in the order their lines appear. */
    static final List<String> ORDER = List.of("format", "row", "scope", "pen", "fold", "playcount", "tags",
            "scale", "mode", "divisor", "decksum", "betcount", "bettags", "betdecksum", "ace", "groups", "group",
            "first", "insure", "lgroups", "lgroup", "later", "plan", "bet", "unseen", "end");
    static final int DEALS = 550;
    static final int SPOTS = 110;

    public final String row;
    /** "first", "full" or "betting". */
    public final String scope;
    public final int pen;
    public final int cut;
    public final int fold;
    public final double trained;
    public final String playCount;
    public final String betCount;
    public final Keys keys;
    final int[] groupHi;
    /** firstMoves[g][deal]: the deal's legal first moves, best first; null for the naturals. */
    final byte[][][] firstMoves;
    /** insure[g][deal] for the ace-up deals listed. */
    final boolean[][] insure;
    final int[] laterHi;
    /** laterStand[l][spot]: stand, else hit. */
    final boolean[][] laterStand;
    public final List<Plan> plans;
    private final boolean betKeyPlusOne;

    /** A bet plan (B2.5): its bets at the keys the fold's test states hold. */
    public static final class Plan {
        public final String label;
        public final String rule;
        /** The exit key, or null for a plan that never leaves the table. */
        public final Integer exit;
        final int[] keys;
        final double[] units;
        final double[] play;

        Plan(String label, String rule, Integer exit, int[] keys, double[] units, double[] play) {
            this.label = label;
            this.rule = rule;
            this.exit = exit;
            this.keys = keys;
            this.units = units;
            this.play = play;
        }

        /** The place of the bet line that serves this key: its own, else the nearest, the lower on a tie. */
        int line(int key) {
            int i = Arrays.binarySearch(keys, key);
            if (i >= 0) {
                return i;
            }
            int above = -i - 1;
            if (above == 0) {
                return 0;
            }
            if (above == keys.length) {
                return keys.length - 1;
            }
            return key - keys[above - 1] <= keys[above] - key ? above - 1 : above;
        }

        /** Whether this key has its own bet line. */
        boolean listed(int key) {
            return Arrays.binarySearch(keys, key) >= 0;
        }

        /** The units bet at this key when the round is played (B2.5's UNITS). */
        public double units(int key) {
            return units[line(key)];
        }

        /** The chance the round is played at this key (B2.5's PLAY). */
        public double play(int key) {
            return play[line(key)];
        }
    }

    private Strategy(Builder b, Plants plants) {
        this.row = b.row;
        this.scope = b.scope;
        this.pen = b.pen;
        this.cut = b.cut;
        this.fold = b.fold;
        this.trained = b.trained;
        this.playCount = b.playCount;
        this.betCount = b.betCount;
        this.keys = new Keys(b.tags, b.betTags, b.trueCount, b.divisor, b.deckSum, b.betDeckSum, b.ace,
                plants.on(Plants.TRUNCATE));
        this.groupHi = b.groupHi;
        this.firstMoves = b.firstMoves;
        this.insure = b.insure;
        this.laterHi = b.laterHi;
        this.laterStand = b.laterStand;
        this.plans = b.plans;
        this.betKeyPlusOne = plants.on(Plants.BET_KEY_PLUS_ONE);
    }

    /** The place of a deal (p1 ≤ p2, up) in B2.1's order: up-card, then p1, then p2. */
    static int deal(int p1, int p2, int up) {
        int pair = 0;
        for (int a = 1; a < p1; a++) {
            pair += 11 - a;
        }
        return (up - 1) * 55 + pair + (p2 - p1);
    }

    /** The place of a contested spot in B2.1's order within an up-card: hard 12 to 16, then soft 13 to 18. */
    static int spot(int total, boolean soft, int up) {
        return (up - 1) * 11 + (soft ? 5 + total - 13 : total - 12);
    }

    static boolean contested(int total, boolean soft) {
        return soft ? total >= 13 && total <= 18 : total >= 12 && total <= 16;
    }

    /** B2.3: the first group whose hi is at least the key; the last takes every key above. */
    static int group(int[] hi, int key) {
        for (int g = 0; g < hi.length - 1; g++) {
            if (hi[g] >= key) {
                return g;
            }
        }
        return hi.length - 1;
    }

    /** The plan's bet line at a bet key, the planted bug bet-key-plus-one applied. */
    public int betLine(Plan plan, int betKey) {
        return plan.line(betKeyPlusOne ? betKey + 1 : betKey);
    }

    public boolean betListed(Plan plan, int betKey) {
        return plan.listed(betKeyPlusOne ? betKey + 1 : betKey);
    }

    /** Expected bet at a bet key: UNITS × PLAY, as the engine's figures and states.bin use it. */
    public double expectedBet(Plan plan, int betKey) {
        int i = betLine(plan, betKey);
        return plan.units[i] * plan.play[i];
    }

    /**
     * B2.4: this strategy playing one round. {@link #start} is called at the round start with the
     * tallies and depth; the round then asks {@link #insure} and {@link #choose}. One object a
     * thread. With {@code moving}, the count keeps moving through split hands (the variant of
     * PLAN_COMPUTE.md 1.5, reported beside the frozen count, never tested).
     */
    public final class Play implements Round.Player {
        private final Chart chart;
        private final boolean moving;
        private long rc;
        private int depth;
        private int dealKey;
        private boolean dealKeyKnown;

        public Play(Chart chart, boolean moving) {
            this.chart = chart;
            this.moving = moving;
        }

        public Strategy strategy() {
            return Strategy.this;
        }

        public void start(int[] dealt, int depth) {
            this.rc = keys.playRunning(dealt);
            this.depth = depth;
            this.dealKeyKnown = false;
        }

        /** Another deal from the same round start (check P2's next re-deal): the deal's key is forgotten. */
        public void redeal() {
            this.dealKeyKnown = false;
        }

        /** The deal's play key: the cards the deal showed (three; four with the hole-card plant). */
        public int dealKey(Round.Decision d) {
            if (!dealKeyKnown) {
                dealKey = keys.playKey(rc, depth, d.seen, d.dealSeen);
                dealKeyKnown = true;
            }
            return dealKey;
        }

        /** The play key of a deal whose first s seen cards are these (for playouts shared by first move). */
        public int keyOfDeal(int[] seen, int s) {
            return keys.playKey(rc, depth, seen, s);
        }

        /** Whether the ace-up deal (p1, p2) is insured at this play key. */
        public boolean insuredAt(int key, int p1, int p2) {
            return insure[group(groupHi, key)][deal(Math.min(p1, p2), Math.max(p1, p2), 1)];
        }

        /** The dealt hand's first move at this play key, scopes first and full. */
        public int firstMoveAt(int key, int p1, int p2, int up) {
            return firstMoves[group(groupHi, key)][deal(Math.min(p1, p2), Math.max(p1, p2), up)][0];
        }

        @Override
        public boolean insure(Round.Decision d) {
            int g = group(groupHi, dealKey(d));
            return insure[g][deal(Math.min(d.p1, d.p2), Math.max(d.p1, d.p2), 1)];
        }

        @Override
        public int choose(Round.Decision d) {
            boolean full = "full".equals(scope);
            if (!d.fromSplit) {
                if (d.first) {
                    if ("betting".equals(scope)) {
                        return chart.choose(d);
                    }
                    int g = group(groupHi, dealKey(d));
                    return firstMoves[g][deal(Math.min(d.p1, d.p2), Math.max(d.p1, d.p2), d.up)][0];
                }
                if (full && contested(d.total, d.soft)) {
                    int l = group(laterHi, keys.playKey(rc, depth, d.seen, d.seenAll));
                    return laterStand[l][spot(d.total, d.soft, d.up)] ? Round.STAND : Round.HIT;
                }
                return chart.choose(d);
            }
            if (!full) {
                return chart.choose(d);
            }
            // A split hand: the count frozen at the deal's play key, or moving in the variant.
            int key = moving ? keys.playKey(rc, depth, d.seen, d.seenAll) : dealKey(d);
            if (d.first) {
                int r = d.c1;
                int x = d.c2;
                if (r == 1 && x == 10 || r == 10 && x == 1) {
                    return Round.STAND;
                }
                for (byte m : firstMoves[group(groupHi, key)][deal(Math.min(r, x), Math.max(r, x), d.up)]) {
                    if (m == Round.STAND || (d.legal & (1 << (m - 1))) != 0) {
                        return m;
                    }
                }
                return Round.STAND;
            }
            if (contested(d.total, d.soft)) {
                boolean stand = laterStand[group(laterHi, key)][spot(d.total, d.soft, d.up)];
                return !stand && (d.legal & Round.L_HIT) != 0 ? Round.HIT : Round.STAND;
            }
            return chart.choose(d);
        }
    }

    public static Strategy load(Path file, Plants plants) throws IOException {
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8), file.toString(), plants);
    }

    /** The fields as the reader gathers them, before the Strategy is built. */
    private static final class Builder {
        String row;
        String scope;
        int pen;
        int cut;
        int fold;
        double trained;
        String playCount;
        String betCount;
        int[] tags;
        int[] betTags;
        boolean trueCount;
        int divisor;
        long deckSum;
        long betDeckSum;
        int ace;
        int[] groupHi;
        byte[][][] firstMoves;
        boolean[][] insure;
        int[] laterHi;
        boolean[][] laterStand;
        final List<Plan> plans = new ArrayList<>();
    }

    static Strategy parse(List<String> lines, String name, Plants plants) throws IOException {
        Builder b = new Builder();
        int rank = -1;
        int[] seen = new int[ORDER.size()];
        int groupsRead = 0;
        int firstRead = 0;
        int laterGroupsRead = 0;
        int laterRead = 0;
        int lastInsure = -1;
        List<String[]> pendingPlans = new ArrayList<>();
        List<List<double[]>> bets = new ArrayList<>();
        boolean ended = false;
        for (int n = 0; n < lines.size(); n++) {
            String line = lines.get(n);
            if (line.startsWith("#")) {
                continue;
            }
            String where = name + " line " + (n + 1) + ": ";
            if (ended) {
                throw new IOException(where + "a line after 'end'");
            }
            String[] f = line.split("\t", -1);
            int r = ORDER.indexOf(f[0]);
            if (r < 0) {
                throw new IOException(where + "unknown keyword '" + f[0] + "'");
            }
            if (r < rank) {
                throw new IOException(where + "'" + f[0] + "' out of B2.1's order");
            }
            if (n == 0 != f[0].equals("format")) {
                throw new IOException(where + "the first line must be 'format strategy 1'");
            }
            rank = r;
            seen[r]++;
            try {
                switch (f[0]) {
                    case "format" -> need(f, 3, "strategy".equals(f[1]) && "1".equals(f[2]), "format strategy 1");
                    case "row" -> b.row = one(f);
                    case "scope" -> {
                        b.scope = one(f);
                        need(f, 2, List.of("first", "full", "betting").contains(b.scope), "scope first, full or betting");
                    }
                    case "pen" -> {
                        need(f, 3, true, "pen P CUT");
                        b.pen = Integer.parseInt(f[1]);
                        b.cut = Integer.parseInt(f[2]);
                        int p = Arrays.binarySearch(Shoe.PENS, b.pen);
                        need(f, 3, p >= 0 && Shoe.CUTS[p] == b.cut, "pen 70, 75 or 80 with its cut");
                    }
                    case "fold" -> {
                        need(f, 3, "0".equals(f[1]) || "1".equals(f[1]), "fold 0 or 1");
                        b.fold = Integer.parseInt(f[1]);
                        b.trained = Double.parseDouble(f[2]);
                    }
                    case "playcount" -> b.playCount = one(f);
                    case "tags" -> b.tags = ints(f, Shoe.KINDS);
                    case "scale" -> need(f, 2, Long.parseLong(f[1]) >= 1, "scale S");
                    case "mode" -> {
                        need(f, 2, "true".equals(f[1]) || "running".equals(f[1]), "mode true or running");
                        b.trueCount = "true".equals(f[1]);
                    }
                    case "divisor" -> {
                        b.divisor = ints(f, 1)[0];
                        need(f, 2, b.divisor >= 1, "divisor at least 1");
                    }
                    case "decksum" -> b.deckSum = longOf(f);
                    case "betcount" -> b.betCount = one(f);
                    case "bettags" -> b.betTags = ints(f, Shoe.KINDS);
                    case "betdecksum" -> b.betDeckSum = longOf(f);
                    case "ace" -> b.ace = ints(f, 1)[0];
                    case "groups" -> {
                        int g = ints(f, 1)[0];
                        need(f, 2, g >= 1, "groups at least 1");
                        b.groupHi = new int[g];
                        b.firstMoves = new byte[g][DEALS][];
                        b.insure = new boolean[g][DEALS];
                    }
                    case "group" -> {
                        int[] v = ints(f, 3);
                        need(f, 4, b.groupHi != null && groupsRead < b.groupHi.length, "groups before its group lines");
                        // SHOE_SPEC.md answer 11: consecutive ranges, lo <= hi, and each lo above the last hi.
                        need(f, 4, v[0] == groupsRead && v[1] <= v[2]
                                && (groupsRead == 0 || v[1] > b.groupHi[groupsRead - 1]), "group g lo hi, by g, lo above the last hi");
                        b.groupHi[groupsRead++] = v[2];
                    }
                    case "first" -> readFirst(b, f, firstRead++);
                    case "insure" -> lastInsure = readInsure(b, f, lastInsure);
                    case "lgroups" -> {
                        int l = ints(f, 1)[0];
                        need(f, 2, l >= 1, "lgroups at least 1");
                        b.laterHi = new int[l];
                        b.laterStand = new boolean[l][SPOTS];
                    }
                    case "lgroup" -> {
                        int[] v = ints(f, 3);
                        need(f, 4, b.laterHi != null && laterGroupsRead < b.laterHi.length, "lgroups before its lgroup lines");
                        need(f, 4, v[0] == laterGroupsRead && v[1] <= v[2]
                                && (laterGroupsRead == 0 || v[1] > b.laterHi[laterGroupsRead - 1]), "lgroup l lo hi, by l, lo above the last hi");
                        b.laterHi[laterGroupsRead++] = v[2];
                    }
                    case "later" -> readLater(b, f, laterRead++);
                    case "plan" -> {
                        need(f, 4, f[1].matches("[A-Za-z0-9.,+-]+") && ("same".equals(f[2]) || "own".equals(f[2]))
                                && f[3].matches("-|-?[0-9]+"), "plan LABEL same|own EXIT");
                        for (String[] p : pendingPlans) {
                            need(f, 4, !p[1].equals(f[1]), "a plan label used once");
                        }
                        pendingPlans.add(f);
                        bets.add(new ArrayList<>());
                    }
                    case "bet" -> readBet(f, pendingPlans, bets);
                    case "unseen" -> need(f, 3, "nearest".equals(f[1]) && "lower".equals(f[2]), "unseen nearest lower");
                    case "end" -> {
                        need(f, 1, true, "end");
                        ended = true;
                    }
                    default -> throw new IllegalStateException(f[0]);
                }
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                throw new IOException(where + "cannot read '" + line + "': " + e.getMessage());
            } catch (IllegalArgumentException e) {
                throw new IOException(where + e.getMessage() + ", found '" + line + "'");
            }
        }
        finish(b, seen, name, groupsRead, firstRead, laterGroupsRead, laterRead, ended, pendingPlans, bets);
        return new Strategy(b, plants);
    }

    private static void finish(Builder b, int[] seen, String name, int groupsRead, int firstRead, int laterGroupsRead,
                               int laterRead, boolean ended, List<String[]> plans, List<List<double[]>> bets)
            throws IOException {
        for (String once : List.of("format", "row", "scope", "pen", "fold", "playcount", "tags", "scale", "mode",
                "divisor", "decksum", "betcount", "bettags", "betdecksum", "ace", "groups", "unseen", "end")) {
            if (seen[ORDER.indexOf(once)] != 1) {
                throw new IOException(name + ": '" + once + "' appears " + seen[ORDER.indexOf(once)] + " times, not once");
            }
        }
        boolean full = "full".equals(b.scope);
        if (seen[ORDER.indexOf("lgroups")] != (full ? 1 : 0)) {
            throw new IOException(name + ": 'lgroups' must appear once in scope full and never otherwise");
        }
        if (!ended || groupsRead != b.groupHi.length) {
            throw new IOException(name + ": " + groupsRead + " group lines for " + b.groupHi.length + " groups");
        }
        int firstWanted = "betting".equals(b.scope) ? 0 : b.groupHi.length * 540;
        if (firstRead != firstWanted) {
            throw new IOException(name + ": " + firstRead + " first lines, not " + firstWanted);
        }
        if (full && (laterGroupsRead != b.laterHi.length || laterRead != b.laterHi.length * SPOTS)) {
            throw new IOException(name + ": " + laterGroupsRead + " lgroup and " + laterRead + " later lines for "
                    + b.laterHi.length + " later groups");
        }
        for (int p = 0; p < plans.size(); p++) {
            List<double[]> list = bets.get(p);
            if (list.isEmpty()) {
                throw new IOException(name + ": plan " + plans.get(p)[1] + " has no bet lines");
            }
            int[] keys = new int[list.size()];
            double[] units = new double[list.size()];
            double[] play = new double[list.size()];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = (int) list.get(i)[0];
                units[i] = list.get(i)[1];
                play[i] = list.get(i)[2];
            }
            String exit = plans.get(p)[3];
            b.plans.add(new Plan(plans.get(p)[1], plans.get(p)[2], "-".equals(exit) ? null : Integer.valueOf(exit),
                    keys, units, play));
        }
    }

    private static void need(String[] f, int fields, boolean ok, String what) {
        if (f.length != fields || !ok) {
            throw new IllegalArgumentException("expected " + what);
        }
    }

    private static String one(String[] f) {
        need(f, 2, !f[1].isEmpty(), f[0] + " and one field");
        return f[1];
    }

    private static int[] ints(String[] f, int count) {
        need(f, count + 1, true, f[0] + " and " + count + " integers");
        int[] v = new int[count];
        for (int i = 0; i < count; i++) {
            v[i] = Integer.parseInt(f[i + 1]);
        }
        return v;
    }

    private static long longOf(String[] f) {
        need(f, 2, true, f[0] + " and one integer");
        return Long.parseLong(f[1]);
    }

    /** The deal at place i of a group's 540 first lines: B2.1's order, the naturals left out. */
    static int[] dealAt(int i) {
        int n = 0;
        for (int up = 1; up <= 10; up++) {
            for (int p1 = 1; p1 <= 10; p1++) {
                for (int p2 = p1; p2 <= 10; p2++) {
                    if (p1 == 1 && p2 == 10) {
                        continue;
                    }
                    if (n++ == i) {
                        return new int[]{p1, p2, up};
                    }
                }
            }
        }
        throw new IllegalArgumentException("deal " + i);
    }

    private static void readFirst(Builder b, String[] f, int place) {
        if (b.groupHi == null || "betting".equals(b.scope)) {
            throw new IllegalArgumentException("no first lines in scope betting, and none before groups");
        }
        int g = place / 540;
        int[] deal = dealAt(place % 540);
        if (f.length < 6 || Integer.parseInt(f[1]) != g || Integer.parseInt(f[2]) != deal[0]
                || Integer.parseInt(f[3]) != deal[1] || Integer.parseInt(f[4]) != deal[2]) {
            throw new IllegalArgumentException("first " + g + " " + deal[0] + " " + deal[1] + " " + deal[2] + " and moves");
        }
        byte[] moves = new byte[f.length - 5];
        for (int i = 0; i < moves.length; i++) {
            int m = f[i + 5].length() == 1 ? Round.MOVES.indexOf(f[i + 5].charAt(0)) : -1;
            if (m < 0) {
                throw new IllegalArgumentException("moves S, H, D, P or R");
            }
            moves[i] = (byte) m;
        }
        // The dealt hand's first move must be legal there: Stand, Hit and Double always, Split on a pair.
        if (moves[0] == Round.SURRENDER || moves[0] == Round.SPLIT && deal[0] != deal[1]) {
            throw new IllegalArgumentException("a first move legal on the dealt hand");
        }
        b.firstMoves[g][deal(deal[0], deal[1], deal[2])] = moves;
    }

    private static int readInsure(Builder b, String[] f, int last) {
        int[] v = ints(f, 3);
        int g = v[0];
        int p1 = v[1];
        int p2 = v[2];
        if (b.groupHi == null || g < 0 || g >= b.groupHi.length || p1 < 1 || p1 > p2 || p2 > 10) {
            throw new IllegalArgumentException("insure g p1 p2, g a group, 1 <= p1 <= p2 <= 10");
        }
        int place = g * DEALS + deal(p1, p2, 1);
        if (place <= last) {
            throw new IllegalArgumentException("insure lines by g, then p1, then p2, each once");
        }
        b.insure[g][deal(p1, p2, 1)] = true;
        return place;
    }

    private static void readLater(Builder b, String[] f, int place) {
        if (b.laterHi == null || !"full".equals(b.scope)) {
            throw new IllegalArgumentException("later lines only in scope full, after lgroups");
        }
        int l = place / SPOTS;
        int within = place % SPOTS;
        int up = within / 11 + 1;
        int cell = within % 11;
        boolean soft = cell >= 5;
        int total = soft ? cell - 5 + 13 : cell + 12;
        need(f, 6, Integer.parseInt(f[1]) == l && Integer.parseInt(f[2]) == total
                        && Integer.parseInt(f[3]) == (soft ? 1 : 0) && Integer.parseInt(f[4]) == up
                        && ("S".equals(f[5]) || "H".equals(f[5])),
                "later " + l + " " + total + " " + (soft ? 1 : 0) + " " + up + " S|H");
        b.laterStand[l][spot(total, soft, up)] = "S".equals(f[5]);
    }

    private static void readBet(String[] f, List<String[]> plans, List<List<double[]>> bets) {
        need(f, 5, true, "bet LABEL KEY UNITS PLAY");
        int p = plans.size() - 1;
        while (p >= 0 && !plans.get(p)[1].equals(f[1])) {
            p--;
        }
        if (p < 0) {
            throw new IllegalArgumentException("a bet of a plan already listed");
        }
        for (int later = p + 1; later < plans.size(); later++) {
            if (!bets.get(later).isEmpty()) {
                throw new IllegalArgumentException("bet lines by plan, in plan order");
            }
        }
        double key = Integer.parseInt(f[2]);
        double units = Double.parseDouble(f[3]);
        double play = Double.parseDouble(f[4]);
        List<double[]> list = bets.get(p);
        if (!list.isEmpty() && list.get(list.size() - 1)[0] >= key) {
            throw new IllegalArgumentException("bet keys increasing within a plan");
        }
        if (units < 0 || play < 0 || play > 1 || Double.isNaN(units) || Double.isNaN(play)) {
            throw new IllegalArgumentException("UNITS at least 0 and PLAY from 0 to 1");
        }
        list.add(new double[]{key, units, play});
    }
}
