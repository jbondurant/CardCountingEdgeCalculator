import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SIM_CHECK.md, the simulator check's pre-registration, against the code that prints it
 * (SimCheckPlan). The file's tables and numbers must be this code's output to the character, so
 * the registry a run is checked against and the file a reader sees are the same; the registry
 * must hold the counts PLAN_COMPUTE.md 1.3 gives (55 instances, 21 at first decisions or the
 * chart, 34 at full play) and stay under its cap of 100 tests; and the arithmetic behind the
 * pass rule, the detection limits and the scaling rule is held to published values and to the
 * rule's own words.
 */
public class SimCheckPlanTest {

    static final Path ROOT = Paths.get("").toAbsolutePath();

    @Test
    public void theFileHoldsWhatTheCodePrints() throws Exception {
        String doc = new String(Files.readAllBytes(ROOT.resolve("SIM_CHECK.md")), StandardCharsets.UTF_8);
        for (String name : SimCheckPlan.BLOCKS) {
            assertEquals(SimCheckPlan.block(name), SimCheckPlan.docBlock(doc, name),
                    "SIM_CHECK.md's block " + name + " must be SimCheckPlan's output; paste `java SimCheckPlan` again");
        }
        for (int part = 1; part <= 2; part++) {
            Path pilot = ROOT.resolve("sim-pilot-part" + part + ".tsv");
            String held = SimCheckPlan.docBlock(doc, "fill-" + part);
            if (Files.exists(pilot)) {
                assertEquals(SimCheckPlan.fill(SimCheckPlan.Pilot.read(pilot)), held,
                        "part " + part + "'s filled block must be printed from its committed pilot file");
            } else {
                assertEquals(SimCheckPlan.unfilled(part), held, "part " + part + " has no pilot file, so nothing is filled");
            }
        }
    }

    @Test
    public void theRegistryHasThePlansCounts() {
        assertEquals(52, SimCheckPlan.figures().size());
        assertEquals(82, SimCheckPlan.tests(0, "all"));
        assertEquals(52, SimCheckPlan.tests(0, "P3"));
        assertEquals(30, SimCheckPlan.tests(0, "P2"));
        assertTrue(SimCheckPlan.tests(0, "all") <= SimCheckPlan.MAX_TESTS, "PLAN_COMPUTE.md 1.3 caps the tests at 100");
        assertEquals(55, SimCheckPlan.instances(0), "PLAN_COMPUTE.md 1.3: about 55 instances");
        assertEquals(21, SimCheckPlan.instances(0, SimCheckPlan.Play.CHART, SimCheckPlan.Play.FIRST));
        assertEquals(34, SimCheckPlan.instances(0, SimCheckPlan.Play.FULL));
        assertEquals(17, SimCheckPlan.instances(1), "part 1: the chart and the first-decision rows exported by refitting");
        assertEquals(38, SimCheckPlan.instances(2), "part 2: full play, and F1 and F2, which only the page look exports");
        assertEquals(38, SimCheckPlan.tests(1, "all"));
        assertEquals(44, SimCheckPlan.tests(2, "all"));
    }

    @Test
    public void everyFigureHasOneStrategyAndP2IsAt80Only() {
        Set<String> ids = new HashSet<>();
        int previousRow = 0;
        for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
            assertTrue(ids.add(f.id), "figure ids are unique");
            assertTrue(f.row >= previousRow, "figures follow the plan's rows");
            previousRow = f.row;
            assertEquals(f.pen == 80, f.p2(), f.id + ": P2 plays the 80% instances only");
            int owners = 0;
            for (SimCheckPlan.Strategy s : SimCheckPlan.strategies()) {
                owners += s.same(f) ? 1 : 0;
            }
            assertEquals(1, owners, f.id + " is played by exactly one strategy");
            int expectedPart = f.play == SimCheckPlan.Play.FULL || f.count.startsWith("F") ? 2 : 1;
            assertEquals(expectedPart, f.part, f.id + ": part 2 holds full play and the registered counts");
            assertNotNull(f.metric());
        }
        for (SimCheckPlan.Strategy s : SimCheckPlan.strategies()) {
            for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
                if (s.same(f)) {
                    assertEquals(s.part, f.part, "a strategy is played in one part only");
                }
            }
        }
    }

    @Test
    public void theEntryNamesAreTheOnesTheScorerGives() throws Exception {
        assertEquals(CountKeys.nullCount().name, SimCheckPlan.ENTRY.get("null count"));
        List<String> catalog = new ArrayList<>();
        for (CountKeys.Count c : CountKeys.catalog()) {
            catalog.add(c.name);
            CountKeys.Count v = CountKeys.bettingVariant(c);
            if (v != null) {
                catalog.add(v.name);
            }
        }
        for (String c : new String[]{"Hi-Lo", "KO", "Red Seven", "Zen", "Hi-Opt II + ace", "Victor APC + ace"}) {
            assertTrue(catalog.contains(SimCheckPlan.ENTRY.get(c)), c + "'s entry name is a catalog row's");
        }
        CustomCounts.Listing l = CustomCounts.read(ROOT.resolve(CustomCounts.REGISTERED));
        CountKeys.Count f1 = null;
        CountKeys.Count f2 = null;
        for (CustomCounts.Entry e : l.entries) {
            if (e.count.name.equals("F1 at 1-8 (f1-8-vidami-ar)")) {
                f1 = e.count;
            }
            if (e.count.name.equals("F2 at 1-8, play vector (f2-pub-victor-apc)")) {
                f2 = e.count;
            }
        }
        assertNotNull(f1);
        assertNotNull(f2);
        assertEquals(f1.name, SimCheckPlan.ENTRY.get("F1"));
        assertEquals(CountKeys.bettingVariant(f2).name, SimCheckPlan.ENTRY.get("F2"),
                "F2 is scored with its betting vector (a planted bug of PLAN_COMPUTE.md 4.6 scores it by its play vector)");
        for (int p = 0; p < SimCheckPlan.PENS.length; p++) {
            assertEquals(SimCheckPlan.PENS[p] + "%", SystemScorer.PENETRATIONS[p]);
        }
    }

    /** Published values: Phi^-1 of 0.975, 0.95 and 0.9, and 1 - Phi(3.5). */
    @Test
    public void theNormalArithmeticMatchesPublishedValues() {
        assertEquals(0.5, SimCheckPlan.upperTail(0), 1e-13);
        assertEquals(0.025, SimCheckPlan.upperTail(ComparisonReport.Z95), 1e-12);
        assertEquals(2.3262907903552504e-4, SimCheckPlan.upperTail(3.5), 1e-15);
        assertEquals(0.975, SimCheckPlan.upperTail(-ComparisonReport.Z95), 1e-12);
        assertEquals(ComparisonReport.Z95, SimCheckPlan.quantile(0.975), 1e-9);
        assertEquals(1.6448536269514722, SimCheckPlan.quantile(0.95), 1e-9);
        assertEquals(1.2815515655446004, SimCheckPlan.quantile(0.90), 1e-9);
        assertEquals(4.652581580710501e-4, SimCheckPlan.twoSidedP(-3.5), 1e-15, "two-sided, either sign");
        assertEquals(100 * 4.652581580710501e-4, SimCheckPlan.falseFailBound(100), 1e-13);
        assertEquals(82 * (2 * 0.006209665325776132 - 4.652581580710501e-4), SimCheckPlan.expectedFlags(82), 1e-10);
    }

    /** The bias caught with a given chance: about Z_FAIL plus the normal quantile, since the far tail is nil. */
    @Test
    public void detectionLimitsAreNotTheThreshold() {
        assertEquals(3.5 + 1.2815515655446004, SimCheckPlan.detectionMultiple(0.90), 1e-8);
        assertEquals(3.5 + 1.6448536269514722, SimCheckPlan.detectionMultiple(0.95), 1e-8);
        assertEquals(3.5, SimCheckPlan.detectionMultiple(0.50), 1e-8, "a bias of exactly 3.5 SEs is caught half the time");
    }

    @Test
    public void standardErrorsScaleWithTheRootOfTheSize() {
        double full = SimCheckPlan.seP3(2.4, 38, 80);
        assertEquals(2.4 / Math.sqrt(38e6 * 3_644_432 / 60_000.0), full, 1e-18);
        assertEquals(Math.sqrt(38 / 19.0), SimCheckPlan.seP3(2.4, 19, 80) / full, 1e-12);
        assertEquals(Math.sqrt(3_644_432 / 3_197_853.0), SimCheckPlan.seP3(2.4, 38, 70) / full, 1e-12,
                "70% has fewer rounds a shoe");
        assertEquals(Math.sqrt(2), SimCheckPlan.seP2(1e-4, 60_000) / SimCheckPlan.seP2(1e-4, 120_000), 1e-12);
        assertEquals(2.4 / Math.sqrt(60_743.0 * 120_000), SimCheckPlan.seP2(SimCheckPlan.vEqual(2.4), 120_000), 1e-18);
    }

    @Test
    public void theScalingRuleFitsTheBudgetAndRoundsDown() {
        SimCheckPlan.Sizes roomy = new SimCheckPlan.Sizes(1, 30, 10, 10, 0);
        assertEquals(1.0, roomy.f, 0);
        assertEquals(38, roomy.chunks);
        assertEquals(120_000, roomy.k);
        assertTrue(roomy.stops.isEmpty());

        // Part 1: 5,400 s; at full size 4,000 s of P3 and 5,000 s of P2, so f = 0.6.
        SimCheckPlan.Sizes tight = new SimCheckPlan.Sizes(1, 0, 40, 50, 0);
        assertEquals(0.6, tight.f, 1e-12);
        assertEquals(22, tight.chunks, "38 x 0.6 = 22.8 chunks, rounded down");
        assertEquals(72_000, tight.k);
        assertTrue(tight.predicted() <= tight.budget());
        assertTrue(tight.stops.isEmpty());

        SimCheckPlan.Sizes edge = new SimCheckPlan.Sizes(1, 0, 54, 54, 0);
        assertEquals(0.5, edge.f, 1e-12);
        assertTrue(edge.stops.isEmpty(), "a factor of exactly 0.5 runs; only one under it stops");
        assertEquals(19, edge.chunks);
        assertEquals(60_000, edge.k);

        SimCheckPlan.Sizes slow = new SimCheckPlan.Sizes(1, 0, 60, 60, 0);
        assertEquals(0.45, slow.f, 1e-12);
        assertEquals(1, slow.stops.size(), "under 0.5 the part stops for Justin's decision");

        // Part 2 pays its fixed extras first: 16,200 s less 10,000 s of extras leaves 6,200 s.
        SimCheckPlan.Sizes extras = new SimCheckPlan.Sizes(2, 200, 60, 60, 100);
        assertEquals(6_000 / 12_000.0, extras.f, 1e-12);
        SimCheckPlan.Sizes over = new SimCheckPlan.Sizes(2, 0, 1, 1, 200);
        assertEquals(0, over.f, 0, "extras past the budget leave nothing");
        assertFalse(over.stops.isEmpty());

        Random r = new Random(5);
        for (int i = 0; i < 2_000; i++) {
            int part = 1 + r.nextInt(2);
            SimCheckPlan.Sizes s = new SimCheckPlan.Sizes(part, 600 * r.nextDouble(), 200 * r.nextDouble(),
                    200 * r.nextDouble(), part == 2 ? 30 * r.nextDouble() : 0);
            if (s.stops.isEmpty()) {
                assertTrue(s.predicted() <= s.budget() + 1e-6, "a run that goes ahead is predicted within budget");
                assertTrue(s.chunks >= 19 && s.k >= 60_000);
            }
            assertTrue(s.chunks <= 38 && s.k <= 120_000);
        }
    }

    @Test
    public void aPilotFillsTheSizesAndEveryTestsSe(@TempDir Path dir) throws Exception {
        StringBuilder b = new StringBuilder("part\t1\nsetup\t30\ntime\tP3\t20\ntime\tP2\t25\ntime\textra\t0\n");
        for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
            if (f.part == 1) {
                double sd = f.bets.equals("flat") ? SimCheckPlan.ASSUMED_SD_FLAT : SimCheckPlan.ASSUMED_SD_18;
                b.append("sd\t").append(f.id).append('\t').append(sd).append('\n');
            }
        }
        for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
            if (f.part == 1 && f.p2()) {
                b.append("v\t").append(f.id).append('\t').append(SimCheckPlan.vEqual(SimCheckPlan.ASSUMED_SD_FLAT)).append('\n');
            }
        }
        for (SimCheckPlan.Figure f : SimCheckPlan.figures()) {
            if (f.part == 1) {
                b.append("exact\t").append(f.id).append('\t').append(SimCheckPlan.ASSUMED_EXACT_SE_CHART_80).append('\n');
            }
        }
        Path file = dir.resolve("pilot.tsv");
        Files.write(file, b.toString().getBytes(StandardCharsets.UTF_8));
        String filled = SimCheckPlan.fill(SimCheckPlan.Pilot.read(file));
        assertTrue(filled.contains("- f = 1.000: P3 38 chunks"), filled);
        assertTrue(filled.contains("- No stop"), filled);
        String chart = String.format(Locale.ROOT, "| F03.P3 | 1.1600 | %s |",
                SimCheckPlan.pct(SimCheckPlan.seP3(1.16, 38, 80), 4));
        assertTrue(filled.contains(chart), "F03's row carries P3's SE at full size: " + chart);
        assertTrue(filled.contains("| F03.P2 |"));
        assertFalse(filled.contains("| F05.P2 |"), "no P2 at 70%");
        assertFalse(filled.contains("F26"), "part 2's figures are not part 1's");

        Files.write(file, b.toString().replace("sd\tF03\t1.16", "sd\tF03\t2.9").getBytes(StandardCharsets.UTF_8));
        assertTrue(SimCheckPlan.fill(SimCheckPlan.Pilot.read(file)).contains("- STOP: F03's pilot SD 2.900"),
                "an SD 2.5 times the assumed one stops the part for a look");

        for (String broken : new String[]{b.toString().replace("sd\tF04\t1.16\n", ""),
                b.toString() + "sd\tF26\t2.4\n", b.toString() + "v\tF03\t1e-5\n",
                b.toString().replace("time\tP2\t25\n", ""), b.toString().replace("v\tF15", "v\tF05")}) {
            Files.write(file, broken.getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, () -> SimCheckPlan.Pilot.read(file),
                    "a pilot file missing a figure, naming another part's or 70%'s, or repeating one is refused");
        }
    }
}
