import java.nio.file.Paths;
import java.util.*;

/** Scratch probe: distinct (contested spot, count key) decisions a state's contested walk asks, per count. */
public class DecisionCellProbe {
    public static void main(String[] a) throws Exception {
        ScoringStates st = FullIndexPilot.load(Paths.get(a[0]), Integer.parseInt(a[1]));
        ExactRound engine = new ExactRound(st.rules);
        PlayerTree.Hands H = PlayerTree.Hands.ALL;
        for (CountKeys.Count c : CountKeys.catalog()) {
            if (!(c.name.startsWith("Hi-Lo") || c.name.startsWith("Omega II") || c.name.startsWith("KISS I") && !c.name.startsWith("KISS II"))) continue;
            long sumD = 0, sumNodes = 0; int maxD = 0, minD = Integer.MAX_VALUE;
            for (int s = 0; s < st.size; s++) {
                PlayerTree t = new PlayerTree(engine, ShoeRun.ranksLeft(st.dealt[s]));
                PlayerTree.Counted k = t.count(c, st.dealt[s]);
                PlayerTree.Live live = k.graph.live(PlayerTree.Mode.CONTESTED);
                Set<Long> cells = new HashSet<>();
                int nodes = 0;
                for (int tr = 0; tr < k.trees; tr++) {
                    int u = k.treeUp[tr];
                    for (int j = 0; j < live.node.length; j++) {
                        if (live.role[j] != PlayerTree.ASKED) continue;
                        int node = live.node[j];
                        if (!k.feasible[tr][node]) continue;
                        int h = k.graph.hand[node];
                        int spot = PlayerTree.spot(H.total(h), H.soft(h), u);
                        cells.add((long) spot * 100000 + (k.key[tr][node] + 50000));
                        nodes++;
                    }
                }
                sumD += cells.size(); sumNodes += nodes;
                maxD = Math.max(maxD, cells.size()); minD = Math.min(minD, cells.size());
            }
            System.out.printf("%s: contested nodes a state %.0f; distinct (spot, key) decisions a state %.0f (min %d, max %d)%n",
                    c.name, sumNodes / (double) st.size, sumD / (double) st.size, minD, maxD);
        }
    }
}
