import java.io.IOException;
import java.io.Writer;

/**
 * The CSV of the edge at each count (PLAN_COMPUTE.md 5.5): one line per key of every row,
 * penetration and layer, every key kept and flagged with the bin the page pools it into, so a
 * reader can redo the pooling or any figure of their own. A row pooled into whole steps also
 * gives its keys in quarter steps, marked "quarter" in the axis column. Figures are printed in
 * full (Double.toString), as fractions of a unit per round.
 */
final class KeyEdgesCsv {

    private KeyEdgesCsv() {
    }

    static final String HEADER = "row,penetration,layer,axis,key,book_key,hi_lo_position,share,edge,insurance,valued,"
            + "n_eff,p10,p90,bin_kind\n";

    static String quote(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    static void write(KeyEdges.Edges e, Writer out) throws IOException {
        KeyEdges.Table t = e.table;
        for (int k = 0; k < t.keys(); k++) {
            int bin = 0;
            while (e.bins.to[bin] <= k) {
                bin++;
            }
            int key = t.sums.key[k];
            line(out, e, e.axis.rebinned ? "whole" : "own", key, e.axis.book(key), e.axis.hiLoPosition(key), t, k,
                    e.bins.kind[bin].name());
        }
        if (e.quarter != null) {
            KeyEdges.Table q = e.quarter;
            for (int k = 0; k < q.keys(); k++) {
                double book = q.sums.key[k] / 4.0;
                line(out, e, "quarter", q.sums.key[k], book, book * e.axis.hiLo, q, k, "");
            }
        }
    }

    private static void line(Writer out, KeyEdges.Edges e, String axis, int key, double book, double hiLo,
                             KeyEdges.Table t, int k, String kind) throws IOException {
        out.write(quote(e.row) + "," + SystemScorer.PENETRATIONS[e.p] + "," + quote(e.layer.label) + "," + axis + ","
                + key + "," + book + "," + (Double.isNaN(hiLo) ? "" : Double.toString(hiLo)) + "," + t.share(k) + ","
                + t.edge(k) + "," + t.insurance(k) + "," + t.valued[k] + "," + t.nEff(k) + "," + t.p10[k] + ","
                + t.p90[k] + "," + kind + "\n");
    }
}
