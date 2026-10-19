import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Scratch probe (not repo code). An R-only tree (only the stand values the contested walk
 * reads) can leave the other feasible hands either at a non-NaN placeholder or at NaN.
 * Counted takes feasibility from !isNaN(stand) (PlayerTree.java:700). This measures what a
 * NaN fill does to the walk outputs the run uses, against the full tree, and what an R-only
 * (zero-filled) tree does to the perfect walk (EVERY_NODE), which the replay recomputes.
 */
public class NanFillProbe {
    static final PlayerTree.Hands H = PlayerTree.Hands.ALL;

    public static void main(String[] a) throws Exception {
        Path data = Paths.get(a[0]);
        int shoes = Integer.parseInt(a[1]);
        ScoringStates st = FullIndexPilot.load(data, shoes);
        System.out.printf("states %d from %d shoes%n", st.size, shoes);
        int[] ones = new int[11];
        Arrays.fill(ones, 1);
        boolean[] needC = TreeSubsetProbe.readHands(PlayerTree.Graph.of(ones), PlayerTree.Mode.CONTESTED, false);
        List<CountKeys.Count> test = new ArrayList<>();
        for (CountKeys.Count c : CountKeys.catalog()) {
            if (c.name.equals("Hi-Lo") || c.name.equals("KISS I") || c.name.startsWith("Omega II")) test.add(c);
        }
        ExactRound engine = new ExactRound(st.rules);
        long parts = 0, departFull = 0, nanLostDepart = 0, nanHitDiffers = 0, nanHitNaN = 0;
        long zeroDiff = 0, decN = 0, decDiffNan = 0;
        long perfParts = 0, perfDiffZero = 0;
        double perfMaxRel = 0;
        for (int s = 0; s < st.size; s++) {
            int[] shoe = ShoeRun.ranksLeft(st.dealt[s]);
            PlayerTree full = new PlayerTree(engine, shoe);
            double[][] zero = TreeSubsetProbe.prunedRows(engine, shoe, needC);
            double[][] nan = new double[11][];
            for (int u = 1; u <= 10; u++) {
                nan[u] = zero[u].clone();
                for (int h = 0; h < H.size; h++) if (!needC[h]) nan[u][h] = Double.NaN;
            }
            PlayerTree tz = new PlayerTree(shoe, zero);
            PlayerTree tn = new PlayerTree(shoe, nan);
            for (CountKeys.Count c : test) {
                PlayerTree.Counted cf = full.count(c, st.dealt[s]);
                PlayerTree.Counted cz = tz.count(c, st.dealt[s]);
                PlayerTree.Counted cn = tn.count(c, st.dealt[s]);
                for (int pl = 1; pl <= 2; pl++) {
                    PlayerTree.LaterPlay play = TreeSubsetProbe.randomPlay(pl * 977L + s);
                    PlayerTree.Walk wf = cf.walk(play, PlayerTree.Mode.CONTESTED);
                    PlayerTree.Walk wz = cz.walk(play, PlayerTree.Mode.CONTESTED);
                    PlayerTree.Walk wn = cn.walk(play, PlayerTree.Mode.CONTESTED);
                    for (int q = 0; q < wf.partHit.length; q++) {
                        if (Double.isNaN(wf.partHit[q])) continue;
                        parts++;
                        if (wf.partDeparts[q]) departFull++;
                        if (wf.partDeparts[q] && !wn.partDeparts[q]) nanLostDepart++;
                        if (Double.doubleToRawLongBits(wf.partHit[q]) != Double.doubleToRawLongBits(wn.partHit[q])) nanHitDiffers++;
                        if (Double.isNaN(wn.partHit[q])) nanHitNaN++;
                        if (Double.doubleToRawLongBits(wf.partHit[q]) != Double.doubleToRawLongBits(wz.partHit[q])
                                || wf.partDeparts[q] != wz.partDeparts[q]) zeroDiff++;
                    }
                    final int ss = s;
                    PlayerTree.FirstHits fh = (deal, part) -> ((deal * 31 + part + ss) % 3) != 0;
                    wf.reach(fh, 1.0);
                    wn.reach(fh, 1.0);
                    List<double[]> df = new ArrayList<>(), dn = new ArrayList<>();
                    wf.decisions((spot, key, r, g) -> df.add(new double[]{spot, key, r, g}));
                    wn.decisions((spot, key, r, g) -> dn.add(new double[]{spot, key, r, g}));
                    int m = Math.min(df.size(), dn.size());
                    decN += df.size();
                    decDiffNan += Math.abs(df.size() - dn.size());
                    for (int k = 0; k < m; k++) {
                        for (int z = 0; z < 4; z++) {
                            if (Double.doubleToRawLongBits(df.get(k)[z]) != Double.doubleToRawLongBits(dn.get(k)[z])) {
                                decDiffNan++;
                                break;
                            }
                        }
                    }
                }
            }
            // The perfect walk (EVERY_NODE, null count) on the full and the R-only (zero-filled) tree.
            PlayerTree.Walk pf = full.count(CountKeys.nullCount(), st.dealt[s]).perfect();
            PlayerTree.Walk pz = tz.count(CountKeys.nullCount(), st.dealt[s]).perfect();
            for (int q = 0; q < pf.partHit.length; q++) {
                if (Double.isNaN(pf.partHit[q])) continue;
                perfParts++;
                if (Double.doubleToRawLongBits(pf.partHit[q]) != Double.doubleToRawLongBits(pz.partHit[q])
                        || pf.partDeparts[q] != pz.partDeparts[q]) {
                    perfDiffZero++;
                    double d = Math.abs(pf.partHit[q] - pz.partHit[q]) / Math.max(1e-300, Math.abs(pf.partHit[q]));
                    perfMaxRel = Math.max(perfMaxRel, d);
                }
            }
        }
        System.out.printf("contested walks, 3 counts x 2 arbitrary plays: parts %d, departing in the full tree %d%n", parts, departFull);
        System.out.printf("  zero-filled R-only tree: parts differing (Hit bits or departs) %d%n", zeroDiff);
        System.out.printf("  NaN-filled R-only tree: departing parts that lose their departs flag %d; Hit bits differ %d; Hit NaN %d%n",
                nanLostDepart, nanHitDiffers, nanHitNaN);
        System.out.printf("  NaN-filled: decisions in full %d, differing or missing %d%n", decN, decDiffNan);
        System.out.printf("perfect walk (EVERY_NODE): parts %d, differing on the zero-filled R-only tree %d, largest relative %.3g%n",
                perfParts, perfDiffZero, perfMaxRel);
    }
}
