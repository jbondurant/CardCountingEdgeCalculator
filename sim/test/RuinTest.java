package sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ruin paths on records whose answer is known: shoes of one round winning or losing one unit,
 * 55 to 45, a walk whose chance of ever falling k units is (45/55)^k (gambler's ruin). A path from
 * B is ruined once it has fallen ceil(B) units, so its rate must be (9/11)^ceil(B) within its SE.
 */
class RuinTest {

    @TempDir
    Path tmp;

    static Path records(Path dir, int up, int down) throws IOException {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < up + down; i++) {
            lines.add(i < up ? "1.0\t1\t0.0" : "-1.0\t1\t-1.0");
        }
        Path f = dir.resolve("records-F27.tsv");
        Files.write(f, lines);
        return f;
    }

    @Test
    void theRuinRatesAreTheGamblersRuin() throws IOException {
        Ruin r = Ruin.read(records(tmp, 5500, 4500));
        assertEquals(0.1, r.mu, 1e-15);
        assertEquals(Math.sqrt(10000 / 9999.0 * 0.99), r.sigmaEff, 1e-12);
        double[][] res = r.run(20_000);
        for (int k = 0; k < 3; k++) {
            double b = res[k][0];
            assertEquals(r.sigmaEff * r.sigmaEff * Math.log(1 / Ruin.RISKS[k]) / 0.2, b, 1e-12);
            double exact = Math.pow(0.45 / 0.55, Math.ceil(b));
            assertTrue(Math.abs(res[k][3] - exact) < 4 * res[k][4] + 1e-4,
                    "from " + b + ": simulated " + res[k][3] + " ± " + res[k][4] + ", gambler's ruin " + exact);
            assertEquals(0, res[k][5], "no path capped");
        }
        assertEquals(res[1][3], r.run(20_000)[1][3], "the same draws every time");
        List<String> lines = r.lines("F27", res);
        assertEquals(7, lines.size());
        assertTrue(lines.get(4).startsWith("0.25\t"));
    }

    @Test
    void recordsThatBreakC2AreRefusedAndANegativeEdgeHasNoBankroll() throws IOException {
        Path f = tmp.resolve("records-F29.tsv");
        Files.write(f, List.of("1.0\t1\t0.5"));
        assertThrows(IOException.class, () -> Ruin.read(f));
        Files.write(f, List.of("-1.0\t2\t-0.5"));
        assertThrows(IOException.class, () -> Ruin.read(f));
        Ruin losing = Ruin.read(records(tmp, 4500, 5500));
        assertThrows(IllegalStateException.class, () -> losing.bankroll(0.05));
    }
}
