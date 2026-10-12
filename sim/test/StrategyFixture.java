package sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes strategy files in SHOE_SPEC.md B2's format for the tests: Hi-Lo tags, true count, the
 * groups, moves, insurance, later plays and plans the test sets. What it writes is read back by
 * {@link Strategy#parse}, so a fixture and the reader can only agree if both follow B2.1.
 */
final class StrategyFixture {

    interface Moves {
        String moves(int g, int p1, int p2, int up);
    }

    String scope = "first";
    int pen = 80;
    int cut = 332;
    int fold = 1;
    int[] groupHi = {0, 99};
    int[] laterHi = {0, 99};
    Moves first = (g, p1, p2, up) -> "S\tH";
    /** Insured deals, as "g p1 p2". */
    List<String> insured = new ArrayList<>();
    /** later stand or hit, by later group and spot. */
    Moves later = (l, total, soft, up) -> "S";
    List<String> plans = new ArrayList<>();
    List<String> bets = new ArrayList<>();

    List<String> lines() {
        List<String> out = new ArrayList<>();
        int[] tags = KeysTest.hiLo();
        StringBuilder t = new StringBuilder();
        for (int x : tags) {
            t.append('\t').append(x);
        }
        out.add("format\tstrategy\t1");
        out.add("# a comment line is skipped");
        out.add("row\tHi-Lo = Hi-Lo Lite");
        out.add("scope\t" + scope);
        out.add("pen\t" + pen + "\t" + cut);
        out.add("fold\t" + fold + "\t1834567.25");
        out.add("playcount\tHi-Lo = Hi-Lo Lite");
        out.add("tags" + t);
        out.add("scale\t1");
        out.add("mode\ttrue");
        out.add("divisor\t1");
        out.add("decksum\t0");
        out.add("betcount\tHi-Lo = Hi-Lo Lite");
        out.add("bettags" + t);
        out.add("betdecksum\t0");
        out.add("ace\t0");
        out.add("groups\t" + groupHi.length);
        for (int g = 0; g < groupHi.length; g++) {
            out.add("group\t" + g + "\t" + (g == 0 ? -40 : groupHi[g - 1] + 1) + "\t" + groupHi[g]);
        }
        if (!"betting".equals(scope)) {
            for (int g = 0; g < groupHi.length; g++) {
                for (int i = 0; i < 540; i++) {
                    int[] d = Strategy.dealAt(i);
                    out.add("first\t" + g + "\t" + d[0] + "\t" + d[1] + "\t" + d[2] + "\t" + first.moves(g, d[0], d[1], d[2]));
                }
            }
        }
        for (String s : insured) {
            out.add("insure\t" + s.replace(' ', '\t'));
        }
        if ("full".equals(scope)) {
            out.add("lgroups\t" + laterHi.length);
            for (int l = 0; l < laterHi.length; l++) {
                out.add("lgroup\t" + l + "\t" + (l == 0 ? -40 : laterHi[l - 1] + 1) + "\t" + laterHi[l]);
            }
            for (int l = 0; l < laterHi.length; l++) {
                for (int up = 1; up <= 10; up++) {
                    for (int total = 12; total <= 16; total++) {
                        out.add("later\t" + l + "\t" + total + "\t0\t" + up + "\t" + later.moves(l, total, 0, up));
                    }
                    for (int total = 13; total <= 18; total++) {
                        out.add("later\t" + l + "\t" + total + "\t1\t" + up + "\t" + later.moves(l, total, 1, up));
                    }
                }
            }
        }
        for (String p : plans) {
            out.add("plan\t" + p.replace(' ', '\t'));
        }
        for (String b : bets) {
            out.add("bet\t" + b.replace(' ', '\t'));
        }
        out.add("unseen\tnearest\tlower");
        out.add("end");
        return out;
    }

    Strategy read(Plants plants) throws java.io.IOException {
        return Strategy.parse(lines(), "fixture", plants);
    }

    Strategy read() throws java.io.IOException {
        return read(Plants.NONE);
    }
}
