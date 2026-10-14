import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The adapters from each computation's own data file to SiteData's contract (PLAN_PAGE.md 2.4),
 * one per computation, each reading only the file its computation wrote and writing one input
 * of the page generator:
 * - risk: RiskReport's risk-SHA.json (SiteRisk);
 * - wide: WideReport's spreads-SHA.json (SiteWide);
 * - edges: KeyEdgesReport's key-edges-SHA.json (SiteEdges);
 * - ceiling: CeilingResults' ceiling-SHA.json, CEILING_RESULT_PARAGRAPHS.md and, for the searched
 *   counts' tags, the registration step 7 scored (SiteCeiling);
 * - literature: LiteratureCheck's literature-SHA.json, already in the contract, checked and copied;
 * - sim: the simulator's runs, planted runs and mode B, held to SIM_CHECK.md (SiteSim).
 * - ledger: the count-design ledger for the registration box (SiteLedger).
 * An adapter carries figures across and computes nothing the page shows but a change of unit
 * (fractions to percent) and a pick its computation's rule fixes; each lists the file it read,
 * with its SHA-256, in _source.artifacts. The computations write their files outside the page's
 * input directory (runs/site/), so an input the generator reads is always one of these.
 *
 * java -cp build/classes:lib/mongo-java-driver-3.12.14.jar SiteAdapt KIND FILE... --out OUT.json
 *   risk RISK.json | wide SPREADS.json | edges KEY-EDGES.json | ceiling CEILING.json PARAGRAPHS.md [COUNTS.json]
 *   | literature LITERATURE.json | sim --plan SIM_CHECK.md [--published RESULTS.ser] --run DIR ... (SiteSim)
 */
final class SiteAdapt {

    private SiteAdapt() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Path file) throws IOException {
        Object o = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException(file + " is not a JSON object");
        }
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o, String what) {
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException(what + " is not an object");
        }
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o, String what) {
        if (!(o instanceof List)) {
            throw new IllegalArgumentException(what + " is not a list");
        }
        return (List<Object>) o;
    }

    static String str(Object o, String what) {
        if (!(o instanceof String)) {
            throw new IllegalArgumentException(what + " is not a string");
        }
        return (String) o;
    }

    /** A number, or NaN for null. */
    static double num(Object o, String what) {
        return o == null ? Double.NaN : Json.num(o, what);
    }

    /** A plan or setting as a key's part: lower case, a minus as m, a plus as p, other marks as _. */
    static String part(String label) {
        String s = label.toLowerCase(java.util.Locale.ROOT).replace("-", "m").replace("+", "p")
                .replaceAll("[^a-z0-9]+", "_");
        return s.replaceAll("^_|_$", "");
    }

    /** A penetration as the computations print it ("80%") or as the simulator gives it (80), as a key's part. */
    static String pen(Object p) {
        String s = String.valueOf(p instanceof Number ? Math.round(((Number) p).doubleValue()) : p).replace("%", "");
        if (!s.equals("70") && !s.equals("75") && !s.equals("80")) {
            throw new IllegalArgumentException("no penetration " + p);
        }
        return s;
    }

    /** A play scope as the computations word it, as the page's scope. */
    static String scope(String play) {
        switch (play) {
            case "chart":
            case "betting only":
                return "bet";
            case "first decisions":
            case "first decision":
                return "fd";
            case "full play":
                return "full";
            default:
                throw new IllegalArgumentException("no page scope for \"" + play + "\"");
        }
    }

    /** A bet plan as the page's spread: flat s1, 1-N sN, a wide plan by its label. */
    static String spread(String bets) {
        if (bets.equals("flat")) {
            return "s1";
        }
        if (bets.matches("1-\\d+")) {
            return "s" + bets.substring(2);
        }
        return part(bets);
    }

    public static void main(String[] args) throws Exception {
        List<String> files = new ArrayList<>();
        Path out = null;
        for (int k = 1; k < args.length; k++) {
            if (args[k].equals("--out")) {
                out = Paths.get(args[++k]);
            } else {
                files.add(args[k]);
            }
        }
        if (args.length == 0 || out == null || files.isEmpty()) {
            throw new IllegalArgumentException("usage: SiteAdapt risk|wide|edges|ceiling|literature|sim FILE... --out OUT.json");
        }
        Path in = Paths.get(files.get(0));
        SiteInput made;
        switch (args[0]) {
            case "risk": made = SiteRisk.adapt(in, files.get(0)); break;
            case "wide": made = SiteWide.adapt(in, files.get(0)); break;
            case "edges": made = SiteEdges.adapt(in, files.get(0)); break;
            case "ceiling": made = SiteCeiling.adapt(in, files.get(0), Paths.get(files.get(1)),
                    files.size() > 2 ? Paths.get(files.get(2)) : null); break;
            case "sim": made = SiteSim.adapt(SiteSim.parse(files)); break;
            case "ledger": {
                int at = files.indexOf("--count-design");
                if (at < 0 || at + 1 >= files.size()) {
                    throw new IllegalArgumentException("usage: SiteAdapt ledger LEDGER.tsv --count-design DIR --out OUT.json");
                }
                made = SiteLedger.adapt(in, Paths.get(files.get(at + 1)));
                break;
            }
            case "literature": {
                byte[] b = literature(in);
                if (Files.exists(out)) {
                    throw new IllegalStateException(out + " exists; an input is never written over");
                }
                Files.write(out, b);
                System.out.println("SiteAdapt literature: checked and copied " + in + " to " + out);
                return;
            }
            default: throw new IllegalArgumentException("no adapter for " + args[0]);
        }
        made.write(out);
        System.out.printf("SiteAdapt %s: %d figures from %s; wrote %s.%n", args[0], made.figures.size(), in, out);
    }

    /**
     * LiteratureCheck writes the contract itself (LiteratureOutput.page): its file is checked as
     * SiteData reads it, refused if it is not the literature check's or not marked a real run,
     * and its figures held to the lit. prefix the template's section asks for; then copied as it is.
     */
    static byte[] literature(Path file) throws IOException {
        byte[] b = Files.readAllBytes(file);
        Map<String, Object> m = read(file);
        Map<String, Object> s = obj(m.get("_source"), file + " _source");
        if (!"literature".equals(s.get("what")) || !Boolean.FALSE.equals(s.get("fixture"))) {
            throw new IllegalArgumentException(file + " is not a literature check's file of a real run");
        }
        for (String k : obj(m.getOrDefault("figures", Map.of()), "figures").keySet()) {
            if (!k.startsWith("lit.")) {
                throw new IllegalArgumentException(file + ": figure " + k + " is outside the literature's lit. keys");
            }
        }
        new SiteData().add(file.getFileName().toString(), b);
        return b;
    }
}
