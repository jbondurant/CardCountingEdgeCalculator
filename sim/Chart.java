package sim;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The chart: one move for each of the 253,440 decisions of SHOE_SPEC.md B1, keyed by the up-card,
 * the hand's total, its softness, its pair rank, whether it came from a split, whether it is a
 * first decision and its legal moves. The chart plays every round of the walk (so it decides which
 * cards each round takes), and every decision a strategy file leaves to it. It never insures.
 *
 * <p>The real chart is the engine's, read from the bundle's chart.tsv and rehashed against its
 * fingerprint ({@link #load}). {@link #textbook} is a stand-in for running before a bundle exists.
 */
public final class Chart implements Round.Player {

    public static final int DECISIONS = 253_440;

    private final byte[] moves;

    Chart(byte[] moves) {
        if (moves.length != DECISIONS) {
            throw new IllegalArgumentException(moves.length + " moves");
        }
        this.moves = moves;
    }

    /** The place of a decision in chart.tsv's order (B1): up 1-10, total 4-21, soft, pair 0-10, fromSplit, first, legal 0-15. */
    public static int index(int up, int total, boolean soft, int pair, boolean fromSplit, boolean first, int legal) {
        if (up < 1 || up > 10 || total < 4 || total > 21 || pair < 0 || pair > 10 || legal < 0 || legal > 15) {
            throw new IllegalArgumentException("no chart line for up " + up + " total " + total + " pair " + pair + " legal " + legal);
        }
        int i = (up - 1) * 18 + (total - 4);
        i = i * 2 + (soft ? 1 : 0);
        i = i * 11 + pair;
        i = i * 2 + (fromSplit ? 1 : 0);
        i = i * 2 + (first ? 1 : 0);
        return i * 16 + legal;
    }

    public int move(int index) {
        return moves[index];
    }

    @Override
    public boolean insure(Round.Decision d) {
        return false;
    }

    @Override
    public int choose(Round.Decision d) {
        return moves[index(d.up, d.total, d.soft, d.pair, d.fromSplit, d.first, d.legal)];
    }

    /** B1's fingerprint: h = 31h + move over the moves in file order, from 1125899906842597, in hexadecimal. */
    public String fingerprint() {
        long h = 1125899906842597L;
        for (byte m : moves) {
            h = 31 * h + m;
        }
        return Long.toHexString(h);
    }

    /**
     * Reads chart.tsv (B1): two comment lines, the header, then 253,440 lines in B1's order, each
     * checked against the order it should be in. The file is refused unless its moves rehash to
     * the fingerprint on line 2.
     */
    public static Chart load(Path file) throws IOException {
        byte[] moves = new byte[DECISIONS];
        String claimed;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            expect(in.readLine(), "# chart.tsv: SHOE_SPEC.md B1, format 1", file);
            String line2 = in.readLine();
            if (line2 == null || !line2.startsWith("# fingerprint ")) {
                throw new IOException(file + ": line 2 is not '# fingerprint ...'");
            }
            claimed = line2.substring("# fingerprint ".length());
            expect(in.readLine(), "up\ttotal\tsoft\tpair\tfromSplit\tfirst\tlegal\tmove", file);
            int n = 0;
            for (int up = 1; up <= 10; up++) {
                for (int total = 4; total <= 21; total++) {
                    for (int soft = 0; soft <= 1; soft++) {
                        for (int pair = 0; pair <= 10; pair++) {
                            for (int fromSplit = 0; fromSplit <= 1; fromSplit++) {
                                for (int first = 0; first <= 1; first++) {
                                    for (int legal = 0; legal < 16; legal++) {
                                        String line = in.readLine();
                                        String want = up + "\t" + total + "\t" + soft + "\t" + pair + "\t"
                                                + fromSplit + "\t" + first + "\t" + legal + "\t";
                                        if (line == null || !line.startsWith(want) || line.length() != want.length() + 1) {
                                            throw new IOException(file + ": decision " + n + " should be '" + want
                                                    + "<move>', found '" + line + "'");
                                        }
                                        int move = Round.MOVES.indexOf(line.charAt(want.length()));
                                        if (move < 0) {
                                            throw new IOException(file + ": unknown move in '" + line + "'");
                                        }
                                        moves[n++] = (byte) move;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (in.readLine() != null) {
                throw new IOException(file + ": lines after the 253,440th decision");
            }
        }
        Chart chart = new Chart(moves);
        if (!chart.fingerprint().equals(claimed)) {
            throw new IOException(file + ": moves rehash to " + chart.fingerprint() + ", line 2 says " + claimed);
        }
        return chart;
    }

    private static void expect(String line, String want, Path file) throws IOException {
        if (!want.equals(line)) {
            throw new IOException(file + ": expected '" + want + "', found '" + line + "'");
        }
    }

    /**
     * A stand-in chart, the textbook basic strategy for eight decks, dealer hits soft 17, double
     * after split, no surrender, written from general knowledge of the game, not from the engine
     * (whose chart this session never saw). It exists so that the walk of check D1 can be run
     * before the bundle's chart.tsv exists: the walk's totals in SHOE_SPEC.md A8 depend on the
     * chart only through the decisions rounds actually reach, so if this chart and the engine's
     * agree on every one of those, the stand-in walk gives exactly A8's figures. A run that the
     * page reports always plays chart.tsv, never this.
     *
     * <p>Its moves do not depend on the cards in the hand beyond the first decision, nor on a
     * split except through the legal moves, as B1 says of the engine's chart. Where a move is
     * not legal it falls back: double to hit (or to stand, for soft 18 and 19), hit to stand.
     */
    public static Chart textbook() {
        byte[] moves = new byte[DECISIONS];
        for (int up = 1; up <= 10; up++) {
            for (int total = 4; total <= 21; total++) {
                for (int soft = 0; soft <= 1; soft++) {
                    for (int pair = 0; pair <= 10; pair++) {
                        for (int fromSplit = 0; fromSplit <= 1; fromSplit++) {
                            for (int first = 0; first <= 1; first++) {
                                for (int legal = 0; legal < 16; legal++) {
                                    moves[index(up, total, soft == 1, pair, fromSplit == 1, first == 1, legal)] =
                                            (byte) textbookMove(up, total, soft == 1, pair, legal);
                                }
                            }
                        }
                    }
                }
            }
        }
        return new Chart(moves);
    }

    private static int textbookMove(int up, int total, boolean soft, int pair, int legal) {
        boolean canHit = (legal & Round.L_HIT) != 0;
        boolean canDouble = (legal & Round.L_DOUBLE) != 0;
        if (pair != 0 && (legal & Round.L_SPLIT) != 0 && textbookSplits(pair, up)) {
            return Round.SPLIT;
        }
        char action = soft ? textbookSoft(total, up) : textbookHard(total, up);
        switch (action) {
            case 'D':
                return canDouble ? Round.DOUBLE : canHit ? Round.HIT : Round.STAND;
            case 'd':
                return canDouble ? Round.DOUBLE : Round.STAND;
            case 'H':
                return canHit ? Round.HIT : Round.STAND;
            default:
                return Round.STAND;
        }
    }

    private static boolean between(int up, int lo, int hi) {
        return up != 1 && up >= lo && up <= hi;
    }

    /** H hit, S stand, D double else hit. */
    private static char textbookHard(int total, int up) {
        if (total <= 8) {
            return 'H';
        }
        if (total == 9) {
            return between(up, 3, 6) ? 'D' : 'H';
        }
        if (total == 10) {
            return between(up, 2, 9) ? 'D' : 'H';
        }
        if (total == 11) {
            return 'D';
        }
        if (total == 12) {
            return between(up, 4, 6) ? 'S' : 'H';
        }
        if (total <= 16) {
            return between(up, 2, 6) ? 'S' : 'H';
        }
        return 'S';
    }

    /** As textbookHard, with d: double else stand. */
    private static char textbookSoft(int total, int up) {
        if (total <= 12) {
            return 'H';
        }
        if (total <= 14) {
            return between(up, 5, 6) ? 'D' : 'H';
        }
        if (total <= 16) {
            return between(up, 4, 6) ? 'D' : 'H';
        }
        if (total == 17) {
            return between(up, 3, 6) ? 'D' : 'H';
        }
        if (total == 18) {
            return between(up, 2, 6) ? 'd' : between(up, 7, 8) ? 'S' : 'H';
        }
        if (total == 19) {
            return up == 6 ? 'd' : 'S';
        }
        return 'S';
    }

    private static boolean textbookSplits(int pair, int up) {
        switch (pair) {
            case 1:
            case 8:
                return true;
            case 2:
            case 3:
            case 7:
                return between(up, 2, 7);
            case 4:
                return between(up, 5, 6);
            case 6:
                return between(up, 2, 6);
            case 9:
                return between(up, 2, 6) || between(up, 8, 9);
            default:
                return false;
        }
    }
}
