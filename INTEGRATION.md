# Integration of the four lanes into site-integration

Written 13 October 2026, on branch site-integration (worktree .worktrees/site-int), after the
lanes that finished PLAN_COMPUTE.md 7.1 were merged. It says what is wired end to end, what is
still open and where, and the order of the 7.2 runs with each command as the code now takes it.
Updated 14 October 2026 after the two lanes that worked through section 4's items were merged
(site-fix-sim as d27b5f8, site-fix-engine as 32422c1): sections 1 and 2 add the merges and their
test run, and sections 4 and 5 say how things stand now.
Nothing here wrote under runs/ or read a state file there. Every test below is a fixture test,
run one class at a time at `nice -n 15` with `-Xmx1g`, one processor and the serial collector,
apart from two reproduction tests that read c06c061's files in place: StoredResultsTest reads its
results.ser, and FitCheckpointClassesTest its kept classes.

## 1. What was merged

| Commit | Merge | Conflicts |
|---|---|---|
| 2c99a56 | site-int-spec: the full-play export from the replay (StrategyExport.ReplayExport), lookups.tsv and the chart's row in the bundle, SHOE_SPEC.md's answers to the simulator's twelve questions and its Part C files, SIM_CHECK.md's `sim-registry` block | none |
| b5bc25a | site-int-sim: the clean-room simulator's D2 (counting-systems.json) and D5 (lookups.tsv), P3's depth bands, differences, swings, records, fan and edge by key, mode B, the moving count, P2's paired differences, the ruin paths | none (sim/ only) |
| 9d3c1e7 | site-int-replay: the replay's wide, sink and export hooks (ReplayHooks), RiskReport reading the replay's wide.ser for F43 to F48 | none |
| fc408e2 | site-int-page: SiteExport, the adapters (risk, spreads, key edges, ceiling, simulator, literature), the page built end to end from one fixture world | none textual; one compile fix, below |
| d27b5f8 | site-fix-sim (14 October): p3 finishes on a bundle with no paths figure, the blind pilot, trip risk and being behind, figure ids on the paired differences, the simulator's formats held by FormatsTest | none (sim/ only) |
| 32422c1 | site-fix-engine (14 October): the wide plans exported, D3, PageLook, SiteSim held to section 12 over several runs, RiskReport's several swings files and ruin paths, the page's tables, fan and registration box, one widening rule, the money sentence | none; two test fixes after it, in section 2 |

Three things the merges needed that no lane had:

1. **The simulator's figure ids reach the page's reader.** site-int-sim gave `sim.Summary.Comparison`
   a twelfth field, `fig`, the registered id the simulator reads from SIM_CHECK.md's
   `sim-registry` block. SiteSimTest built comparisons with eleven, so the merge did not compile.
   SiteSimTest now names each comparison by the simulator's own reading of the block, and
   `SiteSim` refuses a comparison the simulator named as another figure than the one it matches
   from SimCheckPlan (a planted F18 renamed F19 is refused). Inside the merge commit fc408e2.
2. **One full-play exporter, not two** (232a287). Both lanes wrote a replay hook for
   `--export`: site-int-replay's `ReplayHooks.Export`, and site-int-spec's
   `StrategyExport.ReplayExport`. Both lanes' notes asked that `--export` build the second, and
   ReplayHooks.Export's own TODO asked to be replaced by StrategyExport's builder "so the two
   cannot drift". The second also writes keys-sample.tsv and lookups.tsv, which the simulator's
   D2 and D5 read (`sim.Sim p2` stops before P2 without them), checks every state on the
   strategies' values as its item is replayed, and round-trips the bundle before renaming it
   DIR. So:
   - `FullIndexReplay.hooks(o, log)` builds `StrategyExport.ReplayExport` for `--export`;
     ReplayHooks.Export and its helpers (Play, Row, build, check, section) are removed (they stay
     in history at 4527557).
   - Kept from ReplayHooks.Export, now on ReplayExport: the rows and plans in the replay's
     identity (`describe()`, so a resume asked for other rows is refused); the refusal of an
     existing bundle at the start, before any item is replayed (`start()`); the default rows,
     SIM_CHECK.md's full-play figures on c06c061's strategies (`registered(pens)`, 15 rows at
     70/75/80%); the default plan, same-8.
   - New: `--export-row NAME@P` names one penetration (a bare NAME means every penetration
     replayed), and `--only` with `--export` but no `--export-row` is refused at once, since the
     export refuses at its end a row it was asked for that no item replayed.
   - Dropped: ReplayHooks.Export's export.txt in the replay's directory. ReplayExport's account
     goes to the replay's log (one line a row as its item is checked, the round trip's lines,
     "round trip passed; the bundle is DIR").
   - The difference in substance: ReplayHooks.Export wrote a row whose fold strategies rank a
     deal two ways (the dealt hand by the scorer's refit, split hands by the play the last round
     walked) and counted the cells; ReplayExport refuses such a row, since a strategy file has one
     ranking (SHOE_SPEC.md B2.4). See open item 1.
   - FullIndexReplayTest now holds that hook: the bundle at DIR with every file, its first moves
     equal to `Result.fullFirst`, its later cells to the play in force, its round trip passed
     again on the records' checked sections, a round value and a bet moved in a copy's
     states.bin refused, the identity naming the rows, the existing bundle refused before any
     item is replayed, and `--only` without rows refused. StrategyExportTest holds the default and
     named rows. Two bugs planted in scratch copies of StrategyExport each failed it: the rows
     left out of the identity, and the start's refusal left out. Both are recorded in its Javadoc.
3. **A test that runs the two programs together** (test/SimReadsExportTest.java). Each lane had
   tested its side against the spec on its own fixtures; nothing had played the clean-room
   simulator on a bundle the engine wrote. Section 3 gives what it found.

## 2. Tests run on the merged tree

Each class alone, in its own JVM: `nice -n 15 java -Xmx1g -XX:+UseSerialGC
-XX:ActiveProcessorCount=1 -Dsim.test.threads=1`, from the worktree's root.

The last run, on the tree as committed (engine classes of 232a287 and every test file here), at
23:15 to 23:22 on 13 October 2026; the machine had no other Java process running. Every class
the task named is in it, and the merged branches' other test classes that touch what changed.
The skipped test is SimReadsExportTest's disabled half (open item 2).

| Class | Passed | Skipped | Failed | Seconds |
|---|---|---|---|---|
| ShoeSpecTest | 19 of 19 | 0 | 0 | 5 |
| SimCheckPlanTest | 9 of 9 | 0 | 0 | 1 |
| StrategyExportTest | 26 of 26 | 0 | 0 | 16 |
| FullPlayExportTest | 7 of 7 | 0 | 0 | 58 |
| FullIndexReplayTest | 7 of 7 | 0 | 0 | 105 |
| ResultDiffTest | 6 of 6 | 0 | 0 | 0 |
| FitCheckpointClassesTest | 1 of 1 | 0 | 0 | 1 |
| RiskReportTest | 16 of 16 | 0 | 0 | 8 |
| RiskMathTest | 22 of 22 | 0 | 0 | 1 |
| WideReportTest | 2 of 2 | 0 | 0 | 21 |
| WidePlansTest | 9 of 9 | 0 | 0 | 22 |
| SinkTest | 5 of 5 | 0 | 0 | 22 |
| KeyEdgesRunTest | 4 of 4 | 0 | 0 | 22 |
| KeyEdgesTest | 10 of 10 | 0 | 0 | 1 |
| SiteBuildTest | 29 of 29 | 0 | 0 | 3 |
| SiteSimTest | 2 of 2 | 0 | 0 | 0 |
| SiteRiskTest | 2 of 2 | 0 | 0 | 3 |
| SiteWideTest | 2 of 2 | 0 | 0 | 21 |
| SiteEdgesTest | 2 of 2 | 0 | 0 | 4 |
| SiteCeilingTest | 4 of 4 | 0 | 0 | 1 |
| SiteLiteratureTest | 2 of 2 | 0 | 0 | 0 |
| SiteExportTest | 9 of 9 | 0 | 0 | 23 |
| SiteExportCeilingTest | 2 of 2 | 0 | 0 | 2 |
| SitePageTest | 5 of 5 | 0 | 0 | 26 |
| CeilingResultsTest | 9 of 9 | 0 | 0 | 4 |
| CeilingParagraphsTest | 5 of 5 | 0 | 0 | 0 |
| BoundRowsTest | 2 of 2 | 0 | 0 | 1 |
| LiteratureCheckTest | 21 of 21 | 0 | 0 | 1 |
| CeilingScoringTest | 9 of 9 | 0 | 0 | 13 |
| StoredResultsTest | 2 of 2 | 0 | 0 | 1 |
| SimReadsExportTest | 1 of 2 | 1 | 0 | 6 |
| sim/test: 20 classes, each alone | 79 of 79 | 0 | 0 | 13 |

Not run: the rest of test/ (the whole suite is for a free machine, through run-tests.sh), and
RealCountTest and FullIndexFitTest, since no scoring class changed (open item 15).

**After the merges of 14 October** (d27b5f8, 32422c1), each class alone in the same way,
compiled from a clean archive of the commit named (src, sim, test and sim/test). A real 7.2 run
held six threads of the machine throughout, so the times are wall times beside it, and some are
far longer than the same class alone (WidePlansTest 2,965 s, SiteWideTest 4,322 s, SiteExportTest
5,757 s, against 22, 21 and 23 s on 13 October); nothing failed or timed out. src/ is the same
at all three commits; between 1eb8240 and ebc7761 only SIM_CHECK.md's identity table, SiteSimTest
and SimReadsExportTest changed, and SiteSim's plan reader is the only code that reads the table,
so the classes that use it (SiteSimTest, SimReadsExportTest, SitePageTest) ran again on ebc7761.

| Class | Commit | Passed | Skipped | Failed |
|---|---|---|---|---|
| sim/test: 23 classes, each alone | 32422c1 | 89 of 89 | 0 | 0 |
| SiteSimTest | ebc7761 | 6 of 6 | 0 | 0 |
| SimReadsExportTest | ebc7761 | 3 of 3 | 0 | 0 |
| SimCheckPlanTest | ebc7761 | 9 of 9 | 0 | 0 |
| SitePageTest | ebc7761 | 5 of 5 | 0 | 0 |
| FitCheckpointClassesTest | ebc7761 | 1 of 1 | 0 | 0 |
| StoredResultsTest | ebc7761 | 2 of 2 | 0 | 0 |
| CeilingScoringTest | ebc7761 | 9 of 9 | 0 | 0 |
| FullPlayExportTest | ebc7761 | 7 of 7 | 0 | 0 |
| FullIndexReplayTest | ebc7761 | 7 of 7 | 0 | 0 |
| ShoeSpecTest | 1eb8240 | 19 of 19 | 0 | 0 |
| SiteBuildTest | 1eb8240 | 31 of 31 | 0 | 0 |
| SiteLedgerTest | 1eb8240 | 1 of 1 | 0 | 0 |
| RiskSwingsAndRuinTest | 1eb8240 | 1 of 1 | 0 | 0 |
| RiskReportTest | 1eb8240 | 16 of 16 | 0 | 0 |
| SiteRiskTest | 1eb8240 | 2 of 2 | 0 | 0 |
| CeilingResultsTest | 1eb8240 | 9 of 9 | 0 | 0 |
| CeilingParagraphsTest | 1eb8240 | 5 of 5 | 0 | 0 |
| SiteCeilingTest | 1eb8240 | 4 of 4 | 0 | 0 |
| SiteLiteratureTest | 1eb8240 | 2 of 2 | 0 | 0 |
| SiteExportCeilingTest | 1eb8240 | 3 of 3 | 0 | 0 |
| SimVsExactSmallShoeTest | 1eb8240 | 1 of 1 | 0 | 0 |
| PageLookTest | 1eb8240 | 1 of 1 | 0 | 0 |
| MainFileLookTest | 1eb8240 | 5 of 5 | 0 | 0 |
| StrategyExportTest | 1eb8240 | 26 of 26 | 0 | 0 |
| WidePlansExportTest | 1eb8240 | 8 of 8 | 0 | 0 |
| WidePlansTest | 1eb8240 | 9 of 9 | 0 | 0 |
| SiteWideTest | 1eb8240 | 2 of 2 | 0 | 0 |
| SiteEdgesTest | 1eb8240 | 2 of 2 | 0 | 0 |
| SiteExportTest | 1eb8240 | 9 of 9 | 0 | 0 |

CeilingScoringTest and StoredResultsTest passing means report-final's and c06c061's figures are
read and scored to the bit as before; no scoring class changed. What the merges needed, beyond
the two lanes' commits:

- **SimReadsExportTest's second half enabled** (1eb8240): with 78cd388 merged, p3 finishes on the
  engine's bundle and SiteSim's and RiskReport's readers take what it wrote. With writeRecords's
  early return taken out of a scratch copy of the merged simulator, it fails again with
  "records-000000000000.part is missing".
- **SiteSimTest's fixture plan** (ebc7761) put its own commit and bundles into the identity
  table by replacing the words "not yet written" and "not yet exported", so once item 8 filled
  the table, the fixture world would have named fe9a91e instead of its own commit. It now
  replaces whatever the cells hold, and checks that SiteSim reads back only its own. The class
  as it was, its five tests, failed all five on the filled table. A new test holds each recorded
  row to its file's last commit (a copy of the table naming 4bf4830 for the simulator fails it).
- **Two cross-program checks** (ebc7761): SimReadsExportTest reads the simulator's real depth.tsv
  and ev-by-key.tsv with SiteSimReported, and runs the simulator's pilot on the engine's bundle
  and reads its file with the reader `SimCheckPlan fill` uses. Planted in scratch copies, each
  failed its test: the reader's depth.tsv header one name off, and the pilot writing its SDs in
  percent.

## 3. What is wired end to end

"Wired" means each stage writes a file the next stage's code reads, with the formats held by a
committed spec and a test on each side. Three stretches have also run as one, on fixtures: the
replay into its export bundle (FullIndexReplayTest, FullPlayExportTest); the page's command
lines into the built page (SitePageTest); and, across the two programs, the clean-room
simulator's exact checks and P2 on a bundle StrategyExport wrote (SimReadsExportTest, below).
Nothing has met a real file.

**A. First decisions, through the simulator, to the page (simulator part 1).**
- `StrategyExport` reads c06c061's results.ser, and with `--chart-from` step 7's save for the
  chart's row. It writes the bundle as DIR.partial: chart.tsv, two strategy files a row
  (SHOE_SPEC.md B2), states.bin, keys-sample.tsv, lookups.tsv (B8), exact.tsv, index.tsv and
  manifest.sha256. It round-trips the bundle, then renames it DIR.
- `sim.Sim p2` reads it: D1 and D4 on states.bin, D2 from its own reading of
  counting-systems.json (B9) against keys-sample.tsv, D5 against lookups.tsv, then P2.
  `sim.Sim p3` plays P3 on fresh shoes. With `--registry SIM_CHECK.md` each comparison carries
  its figure id.
- The simulator writes summary-p3.json and summary-p2.json, checks.txt, depth.tsv,
  ev-by-key.tsv, swings.tsv, records-FIG.tsv, fan-FIG.tsv, ruin-FIG.tsv and trip-FIG.tsv (Part C
  and sim/QUESTIONS.md item 27). `sim.Sim pilot` writes sim-pilot-partN.tsv, which `SimCheckPlan
  fill` turns into section 8's block.
- `SiteAdapt sim` (SiteSim) reads each run directory given (`--run DIR`, several): it works every
  z out again and holds the runs to all of SIM_CHECK.md section 12 (14 October: open items 6, 7
  and 10), with the reported figures beside them (SiteSimReported). `RiskReport` reads every
  run's swings.tsv, records-FIG.tsv, fan-FIG.tsv and ruin-FIG.tsv; `SiteAdapt risk` carries its
  JSON to the page.
- Tests: StrategyExportTest (round trip, planted edits, the chart's row, lookups.tsv),
  ShoeSpecTest (B9 against CountKeys; Part C's examples read by RiskReport's own readers),
  SimCheckPlanTest (the registry block), the simulator's 20 classes (its own fixtures, expected
  values worked by hand), SiteSimTest, RiskReportTest, SiteRiskTest.
- **SimReadsExportTest (new here) runs the two programs together.** StrategyExport writes a
  bundle from StrategyExportTest's fixture (408 valued states dealt as runs deal them, with
  made-up values; the null count and four catalog counts with their betting variants at 70, 75
  and 80%; the chart's row; keys-sample.tsv for the whole catalog). The simulator then runs as
  its own program, in a JVM of its own with only its own classes. Every exact check passes on
  the engine's files, and P2 runs and names F03 and F18 by their registered ids. The run's
  checks.txt:
  - D1: 408 of 408 records card for card and in q to the bit;
  - D2: 596,232 of 596,232 keys on 15,288 lines, no catalog count missing;
  - D4: 122,400 of 122,400 bet keys and bets;
  - D5: 1,071,360 of 1,071,360 lookups on 14,880 lines.

  Its second half (p3, then SiteSim on both summaries and RiskReport's reader on swings.tsv) was
  disabled on 13 October by a simulator bug (open item 2), and is enabled since the merge of 14
  October (1eb8240); it also reads the simulator's own depth.tsv and ev-by-key.tsv with
  SiteSimReported, and a third test runs the simulator's pilot on the bundle and reads its file
  with SimCheckPlan's fill reader (ebc7761). Four bugs were planted in scratch
  copies. A key-rounding bug in the simulator failed D2, D4 and D5, and the test with them. Three
  in the engine (a header seed, a play key, an unexported count's bet key) were each refused
  first by the export's own round trip, which recomputes the keys apart from CountKeys.

**B. The replay of c06c061 (row 6).**
- `FullIndexRun --fit-from ... --folds-only --wide --sink DIR --export DIR` replays each folds
  item. It holds the item to c06c061's part to the bit, with 1e-12 as the fallback, and works
  every state's perfect value out again.
- wide.ser (P8 checked on every item, WideReport.check before it is written) goes to
  `RiskReport --source replay=` for F43 to F48 and to `WideReport`, whose JSON goes to
  `SiteAdapt wide`.
- SINK/run-b.ser goes to `KeyEdgesRun report --full`, whose JSON goes to `SiteAdapt edges`.
- The export bundle (the 15 registered full-play rows, same-8 and flat, and since 14 October
  P16, B0,8 and X-1.0,8 for F43 to F48's rows) is part 2's input.
- Tests: FullIndexReplayTest (all three hooks on; killed and resumed, every output to the bit),
  FullPlayExportTest (the replay's bundle byte-identical to export()'s, killed and resumed the
  same, planted later lines, rankings and round values refused), RiskReportTest's wide rows,
  WideReportTest, KeyEdgesRunTest.

**C. The ceiling, the published rows and the literature, to the page.**
- `CeilingResults full|data|optimism` reads c06c061's results.ser, step 7's save, step 8's
  results.ser and the two registrations. `data`'s JSON goes to `SiteAdapt ceiling`, with the
  committed paragraphs and the registration.
- `SiteExport` writes runs/site/results-SHA.json from the same files and the pilot.
- `LiteratureCheck` writes its JSON, which goes to `SiteAdapt literature`.
- `SiteBuild` (site/build-site.sh) renders runs/site/*.json into the page.
- Tests: SitePageTest builds the real template end to end from one fixture world through each of
  these command lines and traces every number to an input; SiteBuildTest, the adapters' tests,
  SiteExportTest, SiteExportCeilingTest, CeilingResultsTest, CeilingParagraphsTest.
- The reproduction tests pass: CeilingScoringTest (every published count scores as c06c061
  scored it, to the bit) and StoredResultsTest (c06c061's results.ser, found on this machine,
  read figure for figure as its peek printed them). No scoring class changed in these merges.

## 4. Still open

Rewritten 14 October 2026, after site-fix-sim (merged as d27b5f8) and site-fix-engine (merged as
32422c1). The numbers are the ones of 13 October, so earlier references still find their item;
items 17 to 20 are new. Closed items are in the first table, with the commit on its lane's
branch and the test that fails without it. The open ones follow, in the order they block the
7.2 runs.

| Item | Closed by | Test |
|---|---|---|
| 2. p3 stopped on a bundle with no figure that keeps paths | 78cd388 (sim/P3.java, writeRecords returns when no figure keeps records); SimReadsExportTest's second half enabled in 1eb8240 | SimCommandsTest.p3FinishesOnABundleWithNoFigureWithPaths; SimReadsExportTest.theEnginesReadersTakeWhatTheSimulatorWrote (with the early return taken out of a scratch copy of the simulator, it fails with "records-000000000000.part is missing") |
| 3. no pilot | 2bf5aa2: `sim.Sim pilot` (sim/Pilot.java), blind, writes OUT/sim-pilot-partN.tsv; part 2 is not settled (item 17) | PilotTest; SimReadsExportTest.fillReadsThePilotsFile (ebc7761): the pilot run on the engine's bundle, its file read by the reader `SimCheckPlan fill` uses, in a one-unit bet as fill prints it (sim/QUESTIONS.md 25a) |
| 4. no page-look tool | 893dbfd: `PageLook` (row 7); F1's full play on its fixture meets item 1 | PageLookTest |
| 5. wide plans not exported | 2e4c97d: P16, B0,8 and X-1.0,8 for F43 to F48's rows when the replay has `--wide`; `--plans`/`--export-plans` take wide labels | WidePlansExportTest |
| 6. one bundle per reader | 569c858: `SiteAdapt sim --run DIR` once a run, `RiskReport --swings` once a file, each figure from one; the pilot still takes one bundle (item 17) | SiteSimTest, RiskSwingsAndRuinTest |
| 7. SiteSim did less than SIM_CHECK.md section 12 | 569c858: all of section 12 (checks.txt, identity table, section 8's sizes, chart fingerprint, registered tests, planted sizes) | SiteSimTest |
| 8. identity table unfilled | ebc7761: SHOE_SPEC.md c8d9b5a, the simulator fe9a91e (the last commit touching sim/), D3 283c62c; the bundles are recorded when exported (rows 5 and 8) | SiteSimTest.theIdentityTableNamesTheLastCommitOfEachFileItRecords (each recorded row holds its file's last commit, so a later change to sim/, SHOE_SPEC.md or D3 fails it) |
| 9. no D3 | 283c62c: test/SimVsExactSmallShoeTest.java, 16,125 comparisons on six 9-card shoes within 1e-12 | SimVsExactSmallShoeTest (a dealer standing on soft 17 fails it) |
| 10. simulator outputs no engine code read | simulator side 2bf5aa2 (fig and lessFig on every paired difference, the D checks' outcomes in summary-p2.json) and fe9a91e (its formats in sim/QUESTIONS.md item 27, held by FormatsTest); engine side 569c858 (`SiteSimReported`: `sim.rounds_needed` with `--published`, summary-variants.json, depth.tsv, ev-by-key.tsv, the planted runs; `RiskReport --ruin FIG=PATH`). KeyEdgesReport does not read ev-by-key.tsv; SiteSim carries it to the page | P2DifferencesTest, P3ExtrasTest, FormatsTest, SiteSimTest, RiskSwingsAndRuinTest; SimReadsExportTest (ebc7761) reads the simulator's own depth.tsv and ev-by-key.tsv (a reader header one name off fails it) |
| 11. no trip risk on the simulator side | 2bf5aa2: sim/Trip.java, trip-FIG.tsv beside ruin-FIG.tsv; no engine code reads it (item 19) | TripTest |
| 13. two widening rules | 2e4c97d: CeilingResults takes `--widening` and uses SiteExport's rule | CeilingResultsTest, SiteCeilingTest, SiteExportCeilingTest |
| 14. the money sentence stopped the build | 2e4c97d: it names the highest-ranked row with swings and says the others were not simulated | SiteBuildTest |
| 12, in part | 743e3a5: `{{table:NAME}}` and `{{fan:TABLE}}`, each cell held to its input; the risk fan and ruin table, the simulator's sentence, z table, planted runs and mode B, the literature claims, the registration box (`SiteAdapt ledger`); SiteExport takes `--report-final` and holds the stored rows to it at its printed precision (PLAN_PAGE.md 2.4) | SiteBuildTest, SiteLedgerTest, SiteExportCeilingTest |

Open:

1. **A full-play row whose strategies rank a deal two ways is refused, and real rows may have
   some.** (src/StrategyExport.java, Refit and build; SHOE_SPEC.md B2.4.) The engine plays the
   dealt hand by the scorer's refit and values split hands by the play the last round walked; a
   strategy file holds one ranking, so ReplayExport refuses a fold where the two differ, at the
   end of the replay. On the replay's fixture they never differ; on PageLookTest's 12-shoe
   fixture F1's full play does, and PageLook keeps its other outputs and exits 1. On c06c061 the
   replay lane expects some wherever the fit stopped at its cap (FullIndexFit's refitDiffers).
   Row 4 runs the export on one real item to find out in minutes. If that row is refused, B2
   needs a split-hand ranking (SHOE_SPEC.md, StrategyExport, ExportBundle, sim/Strategy.java)
   before row 6, or row 6 runs without `--export` and the full-play rows wait for a second
   replay. A refusal at the end of row 6 costs only the bundle: the export hook finishes last,
   after wide.ser and run-b.ser are written, and it names each refused row in the log as its
   item is replayed. The page look's F50 and F52 (row 7's export-full) meet the same refusal.
17. **Part 2's pilot cannot write a whole file.** (sim/Pilot.java, or src/SimCheckPlan.java;
   sim/QUESTIONS.md 25b.) `sim.Sim pilot` takes one bundle, and `SimCheckPlan fill` refuses a
   pilot file that lacks any figure of its part. Part 1 is one bundle, so row 5 is not affected.
   Part 2's figures are in three bundles: row 6's (F43 to F48 among them, now that the replay
   exports the wide plans) and row 7's two (F49 and F51; F50 and F52). Either the pilot takes
   `--bundle` more than once and writes one file, or fill takes the three pilot files and
   refuses a figure in two. Until one is written, row 8 has no gate and SiteSim, which holds
   part 2's runs to section 8's part 2 block, refuses them.
18. **Planted runs score only their own bundle's part.** (SIM_CHECK.md section 10; row 8.) F03,
   F15 and F18 are part 1's figures, in part 1's bundle; row 8 plants on part 2's bundle, so
   its planted runs score only F26 to F29. SiteSimReported reads planted runs on a bundle the
   identity table names for the part each figure is of. Justin's decision: run each plant on
   both bundles (twice the planted runs, part 1's bundle being the small one), or let section
   10 name part 2's figures only. Decided before row 5's pilot: after the first pilot a change to
   section 10 is a deviation (SIM_CHECK.md, "Changes").
19. **trip-FIG.tsv has no engine reader.** The simulator writes trip risk at B_25%, B_5% and B_1%
   and the chance of being behind, for H = 10, 100 and 1,000 hours at 50, 80 and 100 rounds an
   hour (sim/QUESTIONS.md 24); RiskReport works its own from the records, at bankrolls of 200,
   500 and 1,000 units. The page shows RiskReport's. Comparing the two needs a reader and a
   common set of bankrolls (src/RiskReport.java).
12. **The page's blocks not yet rendered** (src/SiteBuild.java, site/template.html): the BC/IC
    scatter and its verdict sentence, the 5c spread and wonging toggles (the adapters carry
    wide_plans), and the evidence table. SiteSimReported also carries sim_depth and
    sim_ev_by_key, which no block prints yet.
20. **No test plays the simulator on a bundle with the wide plans.** WidePlansExportTest holds
    the engine's side of SHOE_SPEC.md B2.5 (PLAY, the exit key, states.bin's bets 0 after the
    exit) and sim/test's P2Test and P3Test the simulator's, each against the spec alone. Running
    `sim.Sim p2` on WidePlansExportTest's bundle (D4 checks every bet key and bet) would meet the
    two, as SimReadsExportTest does for the plain plans. Before row 8.
15. **Not yet run here:** the simulator's D6 determinism on six threads (sim/test ran with
    `-Dsim.test.threads=1`; once without it when the machine is free); RealCountTest and
    FullIndexFitTest (no scoring class changed in either merge: SystemScorer, FullIndexFit,
    CountKeys and ScoringStates are as site-integration had them); and no independent tree walk
    values the export's later lines (the round trip holds them to the play the strategy walked).
16. **Sizes unmeasured on the main file:** a states.bin section is 216 bytes a state with all 24
    plans (about 13 MB a row at 80%), so part 1 exports with `--plans`; lookups.tsv's size is
    not known, nor the wide plans' share of row 6's bundle.

## 5. The 7.2 runs, in order

PLAN_COMPUTE.md 7.2's order, unchanged: the short runs that can find a bug cheaply first, the
overnight replay (row 6) after its gate, and the readers last. Each row ends in a commit before
the next starts. The heap sizes are the ones the tools' own comments give; where none does it
says so. Rows 3, 6 and 7, run A′ and the simulator's main runs each take the machine alone; row 1
and run A are short single-threaded reads that can sit beside nothing heavier than a test run
(7.2, "What never runs together").

| Row | Run | Can it run as the code stands? |
|---|---|---|
| 1 | Checks: CeilingResults `full`, the twin preview, `optimism` | yes, once step 8 has exited cleanly |
| 2 | KeyEdges runs A and A′ | yes |
| 3 | FullIndexPilot `--wide` | yes |
| 4 | Replay gate 0 | yes; it also answers open item 1 |
| 5 | Simulator part 1 | yes, after rows 1 and 2, with the bundle's SHA-256 recorded before the pilot; item 18 decided before the pilot |
| 6 | Replay of c06c061 | after row 4; its export depends on open item 1 |
| 7 | Page look | yes, after row 6 and custom-count-design's merge; its full-play export depends on open item 1 |
| 8 | Simulator part 2 | the runs yes, the gate no: its pilot cannot write a whole file (open item 17); the planted runs wait on item 18 |
| 9 | SplitMargin on the twin | yes |
| 10 | Reports, adapters, the page | each command runs once its inputs exist; `SiteAdapt sim` needs both parts' section 8 blocks filled (item 17 for part 2); open item 12 limits what the page shows |

**Setup, once: freeze the classes at the commit that runs them**, as FULL_INDEX_PLAYS.md does,
so every output names its commit (commit.txt) and the replay's classes stay fixed:

```sh
R=/Users/justinbondurant/Documents/CardCountingEdgeCalculator
W=$R/.worktrees/site-int                  # site-integration
CD=$R/.worktrees/count-design             # the registrations' and the ledger's one home (6.3)
M=$W/lib/mongo-java-driver-3.12.14.jar
J=$W/lib/junit-platform-console-standalone-1.10.2.jar
SHA=$(git -C $W rev-parse --short HEAD)
SIMSHA=$(git -C $W log -1 --format=%H -- sim)  # the simulator's commit, fe9a91e, as SIM_CHECK.md's identity table names it
C=$R/runs/full-index/classes-$SHA         # engine classes
CS=$R/runs/sim/classes-$SHA               # simulator classes, compiled alone (sim/ is fe9a91e's tree)
REG=97bf834                               # the registration: step 7's save, step 8's run
S8=$R/runs/custom-count/fullplay-main-$REG
C06=$R/runs/full-index/full-c06c061
T=$(mktemp -d) && git -C $W archive HEAD src sim counting-systems.json | tar -x -C $T \
  && mkdir -p $C $CS && javac -nowarn -d $C -cp $M $T/src/*.java && javac -nowarn -d $CS $T/sim/*.java \
  && git -C $W rev-parse HEAD > $C/commit.txt && cp $T/counting-systems.json $C/
```

**Row 1. Checks, about 15 min, single-threaded reads.** 4.2's NaN count and 4.6 checks 2 and 3
are inside `CeilingResults full` (samePerfect counts NaNs in both perfect arrays and holds step
8's to c06c061's to 1e-12; sameFullPlay does the same for Hi-Lo); check 1 is inside it too
(checkPrinted against step 7's report and the peek). Then the twin preview and check 6:

```sh
mkdir -p $R/runs/ceiling && cd $C
java -Xmx4g -cp "$C:$M" CeilingResults full --stored $C06/results.ser \
  --saved $R/runs/report-ceiling-$REG.ser --report $R/runs/report-ceiling-$REG.md \
  --peek $R/runs/full-index/peek-c06c061-final.txt --counts $CD/custom-counts.json \
  --fullplay-run $S8/results.ser --fullplay-counts $CD/custom-counts-fullplay.json \
  --widening $R/runs/full-index/pilot-1417-100.txt --out $R/runs/ceiling/report-ceiling-full-$SHA.md
java -Xmx4g -cp "$C:$M" BoundRows $R/runs/twin-80.states \
  --perfect $R/runs/custom-count/fullplay/choice-20000-38f4e60/results.ser --shoes 20000
java -Xmx4g -cp "$C:$M" CeilingResults optimism --twin $R/runs/twin-80.states \
  --counts $CD/custom-counts.json --stored $C06/results.ser --saved $R/runs/report-ceiling-$REG.ser \
  --out $R/runs/ceiling/optimism-$SHA.properties
```

**Row 2. KeyEdges runs A and A′, 20 to 35 min.** Run A checks every first-decision entry and
per-key table against c06c061's to the bit before it keeps anything: the sink's first real test.
No heap is documented; ComparisonReport, which holds the same file with `--published`, takes
-Xmx12g.

```sh
mkdir -p $R/runs/key-edges && cd $C
java -Xmx12g -cp "$C:$M" KeyEdgesRun a $R/runs/montreal-80.states --published $C06/results.ser \
  --out $R/runs/key-edges
java -Xmx12g -cp "$C:$M" KeyEdgesRun a-prime $R/runs/montreal-80.states --published $C06/results.ser \
  --out $R/runs/key-edges --threads 7
```

**Row 3. The wide pilot, about 45 min on 6 threads**, on the first 1,417 shoes with 100
resamples, as pilot-1417-100.txt was made:

```sh
cd $C && java -Xmx8g -cp "$C:$M" FullIndexPilot $R/runs/montreal-80.states 1417 100 6 --wide \
  > $R/runs/full-index/pilot-1417-100-wide-$SHA.txt
```

**Row 4. Replay gate 0, about 10 min plus the hooks' finish.** First the one-second check that
fit.ckpt's classes still read as c06c061 wrote them, then Hi-Lo at 70% alone. The plan's gate is
the replay alone; with the three hooks on, as here, it also meets each hook with the real file
and answers open item 1 (the export of Hi-Lo's 70% row is refused if its folds rank a deal two
ways). Go on to row 6 only if the item equals c06c061's (within 1e-12, written down) and the
log says "round trip passed".

```sh
cd $W && mkdir -p build/classes build/sim-classes build/test-classes \
  && javac -nowarn -d build/classes -cp $M src/*.java && javac -nowarn -d build/sim-classes sim/*.java \
  && javac -nowarn -d build/test-classes -cp build/classes:build/sim-classes:$M:$J test/*.java \
  && java -Xmx1g -jar $J --class-path build/classes:build/test-classes:build/sim-classes:$M \
       --select-class FitCheckpointClassesTest
G=$R/runs/replay/gate0-$SHA && mkdir -p $R/runs/replay
cd $C && caffeinate -i nice -n 5 java -Xmx10g -cp "$C:$M" FullIndexRun --fit-from $C06 \
  --states $R/runs/montreal-80.states --out $G --trees-read-only $C06/trees.bin \
  --resamples 1000 --threads 6 --folds-only --only "Hi-Lo = Hi-Lo Lite@0" \
  --wide --sink $G-sink --export $G-export --export-row "Hi-Lo = Hi-Lo Lite, full play@70" \
  > $G.log 2>&1
```

**Row 5. Simulator part 1: export, pilot, run (0.5 to 1.25 h, budget 1.5 h).** SIM_CHECK.md's
identity table already names SHOE_SPEC.md's, the simulator's and D3's commits (open item 8,
ebc7761); open item 18 is decided before the pilot. The export is part 1's rows (SIM_CHECK.md
section 3) with the chart's row from step 7's save, at the plans part 1's figures use (flat is
always written):

```sh
B1=$R/runs/sim/export-part1-$SHA && cd $C
java -Xmx8g -cp "$C:$M" StrategyExport $R/runs/montreal-80.states --published $C06/results.ser \
  --out $B1 --chart-from $R/runs/report-ceiling-$REG.ser@70,75,80 \
  --row "Null count (no count)@80" --row "Hi-Lo = Hi-Lo Lite@70,75,80" \
  --row "Hi-Lo = Hi-Lo Lite, betting only@80" \
  --row "KO (Knock-Out) = REKO (Ridiculously Easy Knock-Out)@80" --row "Red Seven (Red 7)@80" \
  --row "Hi-Opt II + ace side count@80" \
  --row "Victor Advanced Point Count (Victor APC) + ace side count@80" \
  --plans same-2,same-4,same-8,own-8
```

Then the bundle's identity, the SHA-256 of its manifest.sha256 (what the simulator writes into
every chunk and summary as bundleSha256), goes into the identity table's "Part 1's bundle" row,
committed before the pilot. The pilot is blind (SIM_CHECK.md section 8): it runs D1, D2, D4 and
D5, stops with no file if one fails, and otherwise writes sim-pilot-part1.tsv. That file is
copied beside SIM_CHECK.md and committed; `SimCheckPlan fill` prints section 8's part 1 block,
which replaces the "Not filled" lines between the fill-1 markers, committed (SimCheckPlanTest
then holds the block to the file). The block gives the main run's chunks N and re-deals K, or a
stop:

```sh
shasum -a 256 $B1/manifest.sha256        # into SIM_CHECK.md's identity table, committed
PI1=$R/runs/sim/pilot-part1-$SHA && O1=$R/runs/sim/part1-$SHA && cd $W
java -cp $CS sim.Sim pilot --part 1 --bundle $B1 --out $PI1 --sim-commit $SIMSHA \
  --registry $W/SIM_CHECK.md --catalog $W/counting-systems.json --threads 6
cp $PI1/sim-pilot-part1.tsv $W/ && java -cp $C SimCheckPlan fill sim-pilot-part1.tsv
java -cp $CS sim.Sim p2 --bundle $B1 --out $O1 --sim-commit $SIMSHA --catalog $W/counting-systems.json \
  --registry $W/SIM_CHECK.md --threads 6 --k K
java -cp $CS sim.Sim p3 --bundle $B1 --out $O1 --sim-commit $SIMSHA --registry $W/SIM_CHECK.md \
  --threads 6 --chunks N
```

p2 runs D1, D2, D4 and D5 again (checks.txt) and stops before P2 if one fails; D3 is
SimVsExactSmallShoeTest, on the engine side. p3 on this bundle keeps no per-shoe records (no
figure with paths is in it) and finishes (open item 2, closed). Part 1's summaries are
committed before part 2 starts.

**Row 6. The replay of c06c061, 10 to 11 h overnight**, only after row 4 passed. Every hook on;
the export writes the 15 registered full-play rows at same-8 and flat and, since `--wide` is
on, P16, B0,8 and X-1.0,8 for F43 to F48's rows (open item 5, closed by 2e4c97d). After an
interruption, the same command with `--resume`, appending to the log. A line "export: ... is not
exported" in the log means open item 1 hit a real row. The bundle's SHA-256 (of its
manifest.sha256) goes into the identity table's "Part 2's bundle(s)" row before part 2's pilot.

```sh
P=$R/runs/replay/c06c061-$SHA
cd $C && nohup caffeinate -i nice -n 5 java -Xmx10g -cp "$C:$M" FullIndexRun --fit-from $C06 \
  --states $R/runs/montreal-80.states --out $P --trees-read-only $C06/trees.bin \
  --resamples 1000 --threads 6 --folds-only --wide --sink $P-sink --export $R/runs/sim/export-part2-replay-$SHA \
  >> $P.log 2>&1 &
```

It writes $P/wide.ser, $P/replay.txt, $P/perfect.ser, $P-sink/run-b.ser and the bundle.

**Row 7. The page look (6.3), 1.5 to 2 h** (open item 4, closed by 893dbfd: PageLook). It needs
custom-count-design merged through step 8's ledger commit first, the count-design worktree's
custom-counts.json and custom-counts-fullplay.json by absolute path, and one ledger line
("page figures: wide plans, per-key tables, simulator export") committed in $CD. Its outputs
are run C's and run D's collectors (for `KeyEdgesRun report --rows/--full`), the registered
rows' wide figures, and the export of F1 and F2 (F49 to F52). PageLook writes the ledger line
itself, before it reads the states; commit it in $CD at once. Interrupted, the same command with
`--resume` (its line committed) finishes without a second line.

```sh
L=$R/runs/page-look-$SHA && cd $C
caffeinate -i nice -n 5 java -Xmx12g -cp "$C:$M" PageLook --states $R/runs/montreal-80.states --count-design $CD \
  --saved $R/runs/report-ceiling-$REG.ser --fullplay-run $S8 --trees-read-only $C06/trees.bin --out $L --threads 7 \
  > $L.log 2>&1
git -C $CD add custom-count-ledger.tsv && git -C $CD commit -m "page look: its one ledger line"
```

It writes $L/run-c.ser, $L/wide-first.ser, $L/export-first (F49, F51), $L/replay/wide.ser,
$L/run-d/run-b.ser and $L/export-full (F50, F52). It needs step 8's run named by the same counts
path step 8 was given (the replay holds step 8's identity line for line), and PageLook refuses
before writing its ledger line if that path is not the count-design file. If F1's or F2's folds
rank a deal two ways (open item 1), export-full is refused; PageLook keeps the other outputs and
exits 1 saying so. The two bundles' SHA-256 go into the identity table's part 2 row too.

**Row 8. Simulator part 2: pilot, run (2.5 to 4 h, budget 4.5 h).** On the replay's bundle and
the page look's two. The gate does not run yet (open item 17): `sim.Sim pilot --part 2` takes one
bundle, so on $B2 it names the figures not in it (F49 to F52 at least) and exits 1, and
`SimCheckPlan fill` refuses a file that lacks them. Once item 17 is closed: the three bundles in the identity table, then the
pilot, sim-pilot-part2.tsv and the fill-2 block committed as in row 5, giving N and K. Then mode
B and the moving count, and after the main run the planted bugs, each at one chunk and K = 6,000,
with a clean control beside them; on part 2's bundle these score F26 to F29 only (open item 18):

```sh
B2=$R/runs/sim/export-part2-replay-$SHA && O2=$R/runs/sim/part2-$SHA && cd $W
java -cp $CS sim.Sim p2 --bundle $B2 --out $O2 --sim-commit $SIMSHA --catalog $W/counting-systems.json \
  --registry $W/SIM_CHECK.md --threads 6 --k K
java -cp $CS sim.Sim p3 --bundle $B2 --out $O2 --sim-commit $SIMSHA --registry $W/SIM_CHECK.md \
  --threads 6 --chunks N --fan-paths 20 --fan-rounds 100000 --ruin-paths 20000
java -cp $CS sim.Sim variants --bundle $B2 --out $O2 --sim-commit $SIMSHA --chunks 4 --threads 6
for PL in none s17 no-das split-aces-hit hole-card truncate insure-whole-bet other-fold bet-key-plus-one; do
  A=""; [ $PL = none ] || A="--plant $PL"
  java -cp $CS sim.Sim p3 --bundle $B2 --out $O2-plant-$PL --sim-commit $SIMSHA --registry $W/SIM_CHECK.md \
    --threads 6 --chunks 1 $A
  java -cp $CS sim.Sim p2 --bundle $B2 --out $O2-plant-$PL --sim-commit $SIMSHA --registry $W/SIM_CHECK.md \
    --catalog $W/counting-systems.json --threads 6 --k 6000 $A
done
```

The page look's two bundles (row 7), $L/export-first (F49, F51) and $L/export-full (F50, F52), are
played the same way, p2 then p3, into O2L1=$R/runs/sim/part2-look-first-$SHA and
O2L2=$R/runs/sim/part2-look-full-$SHA; SIM_CHECK.md's identity table names all three part 2
bundles. Each run directory's checks.txt, summaries, swings, records and ruin files stay together:
SiteAdapt sim reads them by directory.

**Row 9. SplitMargin on the twin, 1.25 to 2.25 h, any daytime gap.** The stride given is
SplitMargin's default; check that its first line names the 890 states calibration-b99ccbf
measured, or give the stride that does.

```sh
cd $C && java -Xmx8g -cp "$C:$M" SplitMargin $R/runs/twin-80.states 1000 7 \
  --out $R/runs/ceiling/split-margin-$SHA.tsv
java -Xmx8g -cp "$C:$M" SplitMargin $R/runs/twin-80.states 1000 7 --restarts 8 --restart-states 40 \
  > $R/runs/ceiling/split-restarts-$SHA.txt
java -cp $C SplitMargin --rearranged $R/runs/ceiling/split-margin-$SHA.tsv --resamples 1000
```

**Row 10. The reports and the page's inputs, under 1 h.** Each computation writes outside
runs/site/; an adapter puts each input into runs/site/, the only directory SiteBuild reads.
RiskReport and WideReport take row 3's wide pilot; SiteExport and CeilingResults take
pilot-1417-100.txt, by one widening rule (open item 13, closed). RiskReport takes every run's
swings file, each figure from one, and the simulator's ruin paths beside its own (open items 6
and 10, closed). `SiteAdapt sim` refuses every run until both parts' section 8 blocks are filled
and every bundle is in the identity table, naming what is missing (part 2's waits on open item
17).

```sh
cd $C && mkdir -p $R/runs/site $R/runs/risk $R/runs/wide
PILOT=$R/runs/full-index/pilot-1417-100.txt
WPILOT=$R/runs/full-index/pilot-1417-100-wide-$SHA.txt
# The ceiling: the data file, then its adapter with the paragraphs committed before the look.
java -Xmx4g -cp "$C:$M" CeilingResults data --stored $C06/results.ser \
  --saved $R/runs/report-ceiling-$REG.ser --report $R/runs/report-ceiling-$REG.md \
  --peek $R/runs/full-index/peek-c06c061-final.txt --counts $CD/custom-counts.json \
  --fullplay-run $S8/results.ser --fullplay-counts $CD/custom-counts-fullplay.json \
  --split-margin $R/runs/ceiling/split-margin-$SHA.tsv --optimism $R/runs/ceiling/optimism-$SHA.properties \
  --paragraphs $CD/CEILING_RESULT_PARAGRAPHS.md --full-report $R/runs/ceiling/report-ceiling-full-$SHA.md \
  --widening $PILOT --out $R/runs/ceiling/ceiling-$SHA.json
java -cp "$C:$M" SiteAdapt ceiling $R/runs/ceiling/ceiling-$SHA.json $CD/CEILING_RESULT_PARAGRAPHS.md \
  $CD/custom-counts.json --out $R/runs/site/ceiling-$SHA.json
# The published rows, the bound rows and the registered counts at every spread.
java -Xmx8g -cp "$C:$M" SiteExport --stored $C06/results.ser --report-final $R/runs/report-final-26f0724.md --widening $PILOT \
  --template $W/site/template.html --states $R/runs/montreal-80.states \
  --saved $R/runs/report-ceiling-$REG.ser --counts $CD/custom-counts.json \
  --fullplay-run $S8/results.ser --fullplay-counts $CD/custom-counts-fullplay.json \
  --out $R/runs/site/results-$SHA.json
# Spreads and wonging.
java -Xmx4g -cp "$C:$M" WideReport $P/wide.ser --commit $SHA --md $R/runs/wide/report-wide-$SHA.md \
  --json $R/runs/wide/spreads-$SHA.json --widening $WPILOT
java -cp "$C:$M" SiteAdapt wide $R/runs/wide/spreads-$SHA.json --out $R/runs/site/spreads-$SHA.json
# The edge at each count (add --rows and --full of the page look's runs C and D once they exist).
java -Xmx8g -cp "$C:$M" KeyEdgesRun report --a $R/runs/key-edges/a-*.ser --a-prime $R/runs/key-edges/a-prime-*.ser \
  --published $C06/results.ser --full $P-sink/run-b.ser --printed $R/runs/report-final-26f0724.md \
  --out $R/runs/key-edges
java -cp "$C:$M" SiteAdapt edges $R/runs/key-edges/key-edges-*.json --out $R/runs/site/key-edges-$SHA.json
# Swings and risk.
java -Xmx3g -cp "$C:$M" RiskReport --commit $SHA --source c06c061=$C06/results.ser \
  --source step7=$R/runs/report-ceiling-$REG.ser --source step8=$S8/results.ser --source replay=$P/wide.ser \
  --widening $WPILOT --swings $O1/swings.tsv --swings $O2/swings.tsv --swings $O2L2/swings.tsv \
  --records F27=$O2/records-F27.tsv --records F29=$O2/records-F29.tsv --ruin F27=$O2/ruin-F27.tsv \
  --ruin F29=$O2/ruin-F29.tsv --fan F27=$O2/fan-F27.tsv --md $R/runs/risk/report-risk-$SHA.md \
  --json $R/runs/risk/risk-$SHA.json
java -cp "$C:$M" SiteAdapt risk $R/runs/risk/risk-$SHA.json --out $R/runs/site/risk-$SHA.json
# The simulator: every run, part 1's and part 2's (the replay's bundle, $O2, and the page look's two, $O2L1 and $O2L2),
# held to SIM_CHECK.md section 12; mode B, the planted runs beside their control, {N} from c06c061's SEs.
java -Xmx4g -cp "$C:$M" SiteAdapt sim --plan $W/SIM_CHECK.md --published $C06/results.ser --run $O1 --run $O2 \
  --run $O2L1 --run $O2L2 --variants $O2/summary-variants.json --planted none=$O2-plant-none --planted s17=$O2-plant-s17 \
  --out $R/runs/site/sim-$SHA.json      # and --planted NAME=$O2-plant-NAME for each of section 10's
# The registration box, from the ledger's one home.
java -cp "$C:$M" SiteAdapt ledger $CD/custom-count-ledger.tsv --count-design $CD --out $R/runs/site/ledger-$SHA.json
# The literature (it reads step 7's save and report; run from $C for the catalog and commit.txt).
java -cp "$C:$M" LiteratureCheck --claims $W/literature/claims.json --cache $R/runs/literature/cache \
  --report-final $R/runs/report-final-26f0724.md --custom-counts $CD/custom-counts.json \
  --ceiling-save $R/runs/report-ceiling-$REG.ser --ceiling-report $R/runs/report-ceiling-$REG.md \
  --out $R/runs/literature --json $R/runs/literature/literature-$SHA.json
java -cp "$C:$M" SiteAdapt literature $R/runs/literature/literature-$SHA.json \
  --out $R/runs/site/literature-$SHA.json
# The page, into a directory outside the website's tree; nothing is copied there without Justin's yes.
cd $W && sh site/build-site.sh --out $R/runs/page-$SHA --inputs $R/runs/site
```

After row 10, nothing needs c06c061's trees.bin; deleting it (14.9 GB) is Justin's call.
