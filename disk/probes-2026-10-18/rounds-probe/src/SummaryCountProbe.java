import java.nio.file.Paths;
import java.util.*;

/** Scratch probe: size of a 1e-12-level summary (one fixed partial sum per visited node + stand at contested nodes). */
public class SummaryCountProbe {
    public static void main(String[] a) throws Exception {
        ScoringStates st = FullIndexPilot.load(Paths.get(a[0]), Integer.parseInt(a[1]));
        int[] ones = new int[11];
        Arrays.fill(ones, 1);
        PlayerTree.Graph g = PlayerTree.Graph.of(ones);
        PlayerTree.Live live = g.live(PlayerTree.Mode.CONTESTED);
        PlayerTree.Hands H = PlayerTree.Hands.ALL;
        int starts = 0, asked = 0, hits = 0;
        for (byte r : live.role) { if (r == PlayerTree.START) starts++; else if (r == PlayerTree.ASKED) asked++; else hits++; }
        System.out.printf("rank-graph contested positions an up-card: %d = %d starts + %d contested + %d always-hit%n",
                live.node.length, starts, asked, hits);
        ExactRound engine = new ExactRound(st.rules);
        long fSum = 0, standSum = 0;
        for (int s = 0; s < st.size; s++) {
            PlayerTree t = new PlayerTree(engine, ShoeRun.ranksLeft(st.dealt[s]));
            for (int u = 1; u <= 10; u++) {
                double[] row = t.standRow(u);
                for (int j = 0; j < live.node.length; j++) {
                    int h = g.hand[live.node[j]];
                    if (Double.isNaN(row[h])) continue;
                    fSum++;
                    if (live.role[j] == PlayerTree.ASKED) standSum++;
                }
            }
        }
        double per = (fSum + standSum) * 8.0 / st.size;
        System.out.printf("states %d: fixed sums a state %.1f, contested stand values a state %.1f; %.0f B a state; x 60,743 = %.2f GB%n",
                st.size, fSum / (double) st.size, standSum / (double) st.size, per, per * 60743 / 1e9);
    }
}
