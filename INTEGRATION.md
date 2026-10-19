# Integration of the four lanes into site-integration

Written 13 October 2026, on branch site-integration (worktree .worktrees/site-int), after the
lanes that finished PLAN_COMPUTE.md 7.1 were merged. It says what is wired end to end, what is
still open and where, and the order of the 7.2 runs with each command as the code now takes it.
Updated 14 October 2026 after the two lanes that worked through section 4's items were merged
(site-fix-sim as d27b5f8, site-fix-engine as 32422c1): sections 1 and 2 add the merges and their
test run, and sections 4 and 5 say how things stand now. Updated again 15 October 2026 after the
last two lanes were merged (site-last-a as 5cb48a4: items 17 to 20; site-last-b as 62ae14b: the
rest of item 12, and item 21 for what it left): sections 1 and 2 add the merges and their test
run, section 3 the readers they added, and sections 4 and 5 say how things stand now.
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
| 5cb48a4 | site-last-a (15 October): `SimCheckPlan fill` takes a part's pilot files together, the planted runs in two halves on both bundles (SIM_CHECK.md section 10), `RiskReport --trip`, SimReadsWidePlansTest | none |
| 62ae14b | site-last-b (15 October): the BC/IC scatter and its verdict sentence, the every-count chart's views (cut card, spread, wonging), the evidence table (`SiteAdapt evidence`), the simulator's depth and edge-by-key tables in percent | INTEGRATION.md only (section 4's two lists and row 10 of section 5), both sides kept; src/SiteSimReported.java and test/SiteSimTest.java merged without conflict; no code fix needed |

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

**On site-last-a** (14 October, late: open items 17 to 20), each class alone in the same way,
compiled from the worktree at 67c655d with nothing uncommitted, beside the real 7.2 run and
another lane's tests. src/ changed only in SimCheckPlan, SiteSim, SiteSimReported and RiskReport;
sim/ not at all.

| Class | Passed | Skipped | Failed | Seconds |
|---|---|---|---|---|
| SimCheckPlanTest | 10 of 10 | 0 | 0 | 4 |
| SiteSimTest | 7 of 7 | 0 | 0 | 5 |
| SimReadsExportTest | 3 of 3 | 0 | 0 | 18 |
| SimReadsWidePlansTest (new) | 3 of 3 | 0 | 0 | 77 |
| RiskSwingsAndRuinTest | 2 of 2 | 0 | 0 | 8 |
| RiskReportTest | 16 of 16 | 0 | 0 | 21 |
| SiteRiskTest | 2 of 2 | 0 | 0 | 4 |
| WidePlansExportTest | 8 of 8 | 0 | 0 | 70 |
| SitePageTest | 5 of 5 | 0 | 0 | 68 |
| CeilingScoringTest | 9 of 9 | 0 | 0 | 29 |

Not run there: StoredResultsTest, which reads c06c061's results.ser in place (that lane did not
touch runs/full-index/full-c06c061/); no scoring class changed, and CeilingScoringTest holds the
published counts' scores to the bit.

site-last-b's own run (each class alone on a snapshot of its tree, SiteBuildTest, SiteEvidenceTest,
SiteSimTest, SitePageTest and SiteWideTest) is in that lane's report; it ran no CeilingScoringTest.

**After the merges of 15 October** (5cb48a4, then 62ae14b), each class alone in the same way,
compiled from a clean archive of 62ae14b (src, sim, test and sim/test) and run from the worktree's
root, at 18:32 to 18:35; no other Java process was running. Every test class either lane added or
changed is in it (SiteFixture, which site-last-b changed, is SiteBuildTest's), with the classes
the task named and the other adapter and template tests, since site-last-b changed SiteAdapt and
SiteBuild. Nothing failed, so the merges needed no code fix.

| Class | Passed | Skipped | Failed | Seconds |
|---|---|---|---|---|
| SimCheckPlanTest | 10 of 10 | 0 | 0 | 1 |
| SiteSimTest | 7 of 7 | 0 | 0 | 2 |
| SimReadsExportTest | 3 of 3 | 0 | 0 | 8 |
| SimReadsWidePlansTest | 3 of 3 | 0 | 0 | 27 |
| RiskSwingsAndRuinTest | 2 of 2 | 0 | 0 | 4 |
| RiskReportTest | 16 of 16 | 0 | 0 | 7 |
| SiteRiskTest | 2 of 2 | 0 | 0 | 2 |
| SiteEvidenceTest | 4 of 4 | 0 | 0 | 1 |
| SiteWideTest | 2 of 2 | 0 | 0 | 20 |
| SiteBuildTest | 36 of 36 | 0 | 0 | 8 |
| SitePageTest | 5 of 5 | 0 | 0 | 25 |
| CeilingScoringTest | 9 of 9 | 0 | 0 | 12 |
| SiteExportCeilingTest | 3 of 3 | 0 | 0 | 1 |
| SiteLedgerTest | 1 of 1 | 0 | 0 | 2 |
| SiteCeilingTest | 4 of 4 | 0 | 0 | 1 |
| SiteLiteratureTest | 2 of 2 | 0 | 0 | 0 |
| SiteEdgesTest | 2 of 2 | 0 | 0 | 4 |
| SiteExportTest | 9 of 9 | 0 | 0 | 21 |

SiteSimTest's seven hold both lanes' changes to it at once: site-last-a's planted halves and part
2 runs, and site-last-b's percent units and unmet keys. SiteBuildTest's
everyColourPairReadsInBothModes, which needs the website's index.html beside the repository and
was aborted on site-last-b's snapshot, ran and passed here.
Not run: StoredResultsTest (it reads runs/full-index/full-c06c061/results.ser, which this pass did
not touch), sim/test (sim/ is unchanged since fe9a91e), and the whole suite. No scoring class
changed in either merge, and CeilingScoringTest holds report-final's and c06c061's scores to the bit.

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
  and sim/QUESTIONS.md item 27). `sim.Sim pilot` writes sim-pilot-partN.tsv on one bundle;
  `SimCheckPlan fill` takes a part's files together, one a bundle, and prints section 8's block
  (15 October: open item 17).
- `SiteAdapt sim` (SiteSim) reads each run directory given (`--run DIR`, several): it works every
  z out again and holds the runs to all of SIM_CHECK.md section 12 (14 October: open items 6, 7
  and 10), with the reported figures beside them (SiteSimReported, its tables in percent of a
  unit since 15 October) and each planted run in its two halves (open item 18). `RiskReport` reads
  every run's swings.tsv, records-FIG.tsv, fan-FIG.tsv, ruin-FIG.tsv and, since 15 October,
  trip-FIG.tsv beside its own formula (open item 19); `SiteAdapt risk` carries its JSON to the
  page.
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
- `SiteAdapt evidence` (SiteEvidence, 15 October) checks each row of verification/evidence.tsv
  against the commit it cites and writes the evidence table's input.
- `SiteBuild` (site/build-site.sh) renders runs/site/*.json into the page, and the every-count
  chart's other views into assets/views/.
- Tests: SitePageTest builds the real template end to end from one fixture world through each of
  these command lines and traces every number to an input; SiteBuildTest, the adapters' tests,
  SiteExportTest, SiteExportCeilingTest, CeilingResultsTest, CeilingParagraphsTest.
- The reproduction tests pass: CeilingScoringTest (every published count scores as c06c061
  scored it, to the bit) and StoredResultsTest (c06c061's results.ser, found on this machine,
  read figure for figure as its peek printed them). No scoring class changed in these merges.

## 4. Still open

Rewritten 14 October 2026, after site-fix-sim (merged as d27b5f8) and site-fix-engine (merged as
32422c1), and again 15 October after site-last-a (merged as 5cb48a4: items 17 to 20) and
site-last-b (merged as 62ae14b: the rest of item 12). The numbers are the ones of 13 October, so
earlier references still find their item; 21 and 22 are new. Closed items are in the first table,
with the commit on its lane's branch, the merge that brought it here, and the test that fails
without it. The open ones follow, in the order they block the 7.2 runs.

| Item | Closed by | Test |
|---|---|---|
| 2. p3 stopped on a bundle with no figure that keeps paths | 78cd388 (sim/P3.java, writeRecords returns when no figure keeps records); SimReadsExportTest's second half enabled in 1eb8240 | SimCommandsTest.p3FinishesOnABundleWithNoFigureWithPaths; SimReadsExportTest.theEnginesReadersTakeWhatTheSimulatorWrote (with the early return taken out of a scratch copy of the simulator, it fails with "records-000000000000.part is missing") |
| 3. no pilot | 2bf5aa2: `sim.Sim pilot` (sim/Pilot.java), blind, writes OUT/sim-pilot-partN.tsv, one bundle a pilot; part 2's several are filled together (item 17) | PilotTest; SimReadsExportTest.fillReadsThePilotsFile (ebc7761): the pilot run on the engine's bundle, its file read by the reader `SimCheckPlan fill` uses, in a one-unit bet as fill prints it (sim/QUESTIONS.md 25a) |
| 4. no page-look tool | 893dbfd: `PageLook` (row 7); F1's full play on its fixture meets item 1 | PageLookTest |
| 5. wide plans not exported | 2e4c97d: P16, B0,8 and X-1.0,8 for F43 to F48's rows when the replay has `--wide`; `--plans`/`--export-plans` take wide labels | WidePlansExportTest |
| 6. one bundle per reader | 569c858: `SiteAdapt sim --run DIR` once a run, `RiskReport --swings` once a file, each figure from one; the pilot takes one bundle and fill a part's files together (item 17) | SiteSimTest, RiskSwingsAndRuinTest |
| 7. SiteSim did less than SIM_CHECK.md section 12 | 569c858: all of section 12 (checks.txt, identity table, section 8's sizes, chart fingerprint, registered tests, planted sizes) | SiteSimTest |
| 8. identity table unfilled | ebc7761: SHOE_SPEC.md c8d9b5a, the simulator fe9a91e (the last commit touching sim/), D3 283c62c; the bundles are recorded when exported (rows 5 and 8) | SiteSimTest.theIdentityTableNamesTheLastCommitOfEachFileItRecords (each recorded row holds its file's last commit, so a later change to sim/, SHOE_SPEC.md or D3 fails it) |
| 9. no D3 | 283c62c: test/SimVsExactSmallShoeTest.java, 16,125 comparisons on six 9-card shoes within 1e-12 | SimVsExactSmallShoeTest (a dealer standing on soft 17 fails it) |
| 10. simulator outputs no engine code read | simulator side 2bf5aa2 (fig and lessFig on every paired difference, the D checks' outcomes in summary-p2.json) and fe9a91e (its formats in sim/QUESTIONS.md item 27, held by FormatsTest); engine side 569c858 (`SiteSimReported`: `sim.rounds_needed` with `--published`, summary-variants.json, depth.tsv, ev-by-key.tsv, the planted runs; `RiskReport --ruin FIG=PATH`). KeyEdgesReport does not read ev-by-key.tsv; SiteSim carries it to the page | P2DifferencesTest, P3ExtrasTest, FormatsTest, SiteSimTest, RiskSwingsAndRuinTest; SimReadsExportTest (ebc7761) reads the simulator's own depth.tsv and ev-by-key.tsv (a reader header one name off fails it) |
| 11. no trip risk on the simulator side | 2bf5aa2: sim/Trip.java, trip-FIG.tsv beside ruin-FIG.tsv; RiskReport reads it since item 19 | TripTest |
| 13. two widening rules | 2e4c97d: CeilingResults takes `--widening` and uses SiteExport's rule | CeilingResultsTest, SiteCeilingTest, SiteExportCeilingTest |
| 14. the money sentence stopped the build | 2e4c97d: it names the highest-ranked row with swings and says the others were not simulated | SiteBuildTest |
| 17. part 2's pilot could not write a whole file | site-last-a, merged in 5cb48a4. f9c3b90: `SimCheckPlan fill` takes a part's pilot files together, one a bundle, and prints one block; a figure in two files or in none is refused; the bundles' setups, P3s and P2s are added, the extras only where the main run plays them (the bundle holding F26 to F29, and part 1's, whose part 2 pilot holds no figure). 1fe01cf: SiteSim holds part 2's runs to that block and leaves out the chart's walk, which P3 and P2 compare on every bundle with no exact side where the chart's row is not exported (part 2's) | SimCheckPlanTest.severalFilesOneABundleFillOnePartsBlock (the page look's extras counted, the two-files check dropped, or part 1's P3 and P2 counted each fail it); SiteSimTest.partTwosRunsAcrossItsBundlesAreHeldToTheBlockItsPilotFilesFill and thePartsAndBundlesAreTakenTogether (with the chart walk counted, they fail as "figures of both parts"); SimReadsWidePlansTest shows the simulator writing that chart walk |
| 18. planted runs scored only their own bundle's part | site-last-a, merged in 5cb48a4. Decided 14 October, before any pilot, and recorded in SIM_CHECK.md section 10 (5e8bece): every planted run and its control on both bundles. 1fe01cf: `SiteAdapt sim --planted NAME=DIR` twice a run, one half on each part's bundle as the identity table names them, the 14 tests made once | SiteSimTest.thePlantedRunsAreReadInTwoHalvesBesideTheirControl (without the both-halves check it fails) |
| 19. trip-FIG.tsv had no engine reader | site-last-a, merged in 5cb48a4. 4084ae9: `RiskReport --trip FIG=PATH`, each line beside the report's own blocks and formula at the simulator's bankrolls and T = hours x pace, the agreement stated in the report; refused when the moments, bankrolls, blocks, counts or formula part; the page's figures unchanged | RiskSwingsAndRuinTest.theSimulatorsTripRiskSitsBesideThisReportsFormula, on a trip file sim.Trip writes (the formula at the blocks' mean rounds, or the blocks check dropped, each fail it) |
| 20. no test played the simulator on the wide plans | site-last-a, merged in 5cb48a4. 4fe443d: test/SimReadsWidePlansTest.java, the compiled simulator's p2 on WidePlansExportTest's bundle: D4 671 of 671, the five wide plans among them | SimReadsWidePlansTest (with the exit ignored in a scratch copy of the simulator's D4, 32 mismatches and two of its three tests fail) |
| 12, in part | 743e3a5: `{{table:NAME}}` and `{{fan:TABLE}}`, each cell held to its input; the risk fan and ruin table, the simulator's sentence, z table, planted runs and mode B, the literature claims, the registration box (`SiteAdapt ledger`); SiteExport takes `--report-final` and holds the stored rows to it at its printed precision (PLAN_PAGE.md 2.4) | SiteBuildTest, SiteLedgerTest, SiteExportCeilingTest |
| 12, the rest | site-last-b, merged in 62ae14b: fd97b8a, 34d2959 and 61504be. The BC/IC scatter (`{{scatter:ID}}`, SiteSvg.scatter) from the literature check's own claims and verdicts, with a verdict sentence of count figures SiteBuild works out on the inputs (`{{count:TABLE\|FILTER}}`, like a range); the 5c toggles (`{{#view}}`: every setting the data has rendered into assets/views/, the default static in the page, page.js swapping whole fragments); the evidence table (`SiteAdapt evidence`, SiteEvidence, verification/evidence.tsv); the simulator's depth and edge-by-key tables. Item 21 says what is left, item 22 what the planted runs' block still lacks | SiteBuildTest (correlations, views, a fragment checked as the page, evidence, depth and edge by key), SiteEvidenceTest, SiteSimTest, SiteWideTest |

Open:

1. **A full-play row whose strategies rank a deal two ways: refused on the real runs; the
   exporter writes `split` lines (134da21) and the simulator reads them (ae283e4); the exports
   again and Justin's yes to deviation 1 remain.** (SHOE_SPEC.md B2.1, B2.4, B6 item 12, B7, B8 and "Changes to Part B";
   SIM_CHECK.md deviation 1, sections 5 and 14; drafted and checked 18 October 2026 in the
   split-ranking worktree, the engine's side built there on 19 October.) The engine plays the dealt hand by the scorer's refit of the
   strategy's last-round values (src/SystemScorer.java:2624-2628) and values split hands by the
   play the last round walked, `now`, the "kept play" of SHOE_SPEC.md B2.4: at convergence the
   last pass's play, at the pass cap the best pass's after its clean-ups
   (src/FullIndexFit.java:1004-1012, 710-722, 872-881). A strategy file held one ranking, so
   until 134da21 ReplayExport refused a fold where the two differ, and `finish` then wrote no
   bundle at all, the good rows' included.
   - **Status, 19 October 2026 (integration, split-ranking).** The simulator's side was written
     in a fourth clean-room session (285f0fb, after its review ae283e4; sim/QUESTIONS.md), in a
     worktree that held only the specs and sim/, and merged as e2cc9e4. A scan of the three
     clean-room agents' 173 tool calls (builder, reviewer, fixer) found none that read src/, test/,
     another worktree, INTEGRATION.md or a run's output; the reviewer's own audit of the builder's
     calls is the one that names those paths. SimReadsSplitLinesTest
     (b6a4b71): the page look's full-play export on PageLookTest's fixture, 31 `split` lines in
     fold 0's file and 1 in fold 1's, played by the compiled simulator: D1, D2, D4 and D5 with 0
     mismatches, D5's split cells compared, then P2; a copy with one split line taken out is
     caught. Found there: the replay's export sampled keys only for the counts it scored, so the
     page look's bundle would have failed D2; it samples the whole catalog now (b6a4b71).
     Deviation 1's check passed: ae283e4 on part 1's bundle gives part 1's D1, D2, D4 and D5 with
     no split cell, all 61 P2 chunks equal and P3's first chunk equal in every result line
     (runs/sim/deviation1-check-ae283e4/compare.txt; P3's `extras` line differs only in the
     records bound, 1,000,000 for one chunk against part 1's 2,000,000, with no figure keeping
     records). The identity table records 3b2f104 and ae283e4. Left: (1) Justin's yes to
     deviation 1; (2) the exports again: row 6's 15 full-play items with `--export` and `--wide`
     (the wide plans F43-F48 are attached only with `--wide`), and row 7's F50 and F52, for
     which PageLook must first replay into a directory of its own under new classes (its resume
     under another commit is refused today); (3) SHOE_SPEC.md's three passages in "How this file
     is held to the engine" that still describe the split lines as to come (7dca9f5), to be
     corrected in a commit the identity table records again.
   - **What rows 4, 6 and 7 found.** Row 4's gate (Hi-Lo at 70%) exported and passed
     (runs/replay/gate0-304901c.log:14, 32). On c06c061's replay (runs/replay/c06c061-0e0920d.log),
     6 of the 15 full-play rows were checked (largest relative difference 0.0) and 9 refused; the
     page look refused F50 and F52 too (runs/page-look-0e0920d.log:48, 64). Each refused fold
     named one cell, F2's two. Ten of the eleven named are a pair of tens against 4, 5 or 6, or
     F2's nines against 5, ranked P S H D by the refit and S P H D by the walked play: the dealt
     pair splits, and a split hand that draws the pair card stands. The eleventh (Victor APC + ace
     at 75%, fold 1, a pair of threes against an ace: H P S D against H S P D) differs only where
     no hand reads. `build` stops at the first fold that fails, so fold 1 is unknown for 8 of the
     main rows and for F2. That these fits stopped at their pass cap is an inference: 85 of 372
     strategies were still fitting in round 5 and none went on after it, with no new split
     valued in rounds 4 and 5 (runs/full-index/full-c06c061.log:59, 70), and the run printed no
     per-strategy capped flag or refitDiffers (the replay does since 134da21, so the exports
     again test it); a near-tie between the lean pass sums and the scorer's would be refused the
     same way.
   - **What is specified.** A full-play file gains `split g p1 p2 up M…` lines, the kept play's
     ranking, only where it is not the `first` line's list; each is its `first` line's moves in
     another order; split hands read the `split` line, else the `first` line, at the deal's group,
     or at the moving key's in the moving-count variant (B2.1, B2.4). lookups.tsv gains exactly
     1,000 `split` lines when, and only when, the bundle holds a full-play file, every split
     hand's first decision in every group under Montreal's fixed legal moves; a reader refuses
     them missing or out of place, and D5 counts them apart (B8). Format numbers stay 1. Part 1's
     bundle and the page look's export-first hold no full-play file, so they, part 1's recorded
     SHA-256 and part 1's run are unchanged; the gate's bundle (runs/replay/gate0-304901c-export)
     keeps its meaning in its strategy files but its lookups.tsv is now refused, and no part plays
     it. No figure and no byte of states.bin changes: the engine always valued split hands this
     way.
   - **Decided here, for Justin to overrule.** (a) Dense or sparse: sparse, whole lists, written
     wherever the two lists differ at all (B6 item 12). (b) Commits: SHOE_SPEC.md's commit and the
     SIM_CHECK.md commit recording it go in back to back, as ebc7761 did for c8d9b5a, and the same
     for the simulator's; test/SiteSimTest.java:296-325 fails between the two, and again if either
     file changes after its record. (c) SiteSim.Plan keeps one list of simulator commits for both
     parts (src/SiteSim.java:132-133): SIM_CHECK.md's words say which commit made what, and
     deviation 1's check proves the new commit plays part 1's bundle as fe9a91e did; a per-part
     check in SiteSim is optional. (d) lookups.tsv's 1,000 `split` lines stay whole, the split
     aces' all-`S` cells included, so the cells need no rule about which ranks they cover; they
     add 1,000 cells of G or 2G + 1 letters to each full-play column, beside its 30,990 `first`
     cells, and item 16's measured total is to include them. (e) Before the exports again, the
     replay's log prints each strategy's capped flag and refitDiffers, so each `split` line's
     cause is on record and the inference above is tested: done in 134da21
     (`FullIndexReplay.howFitted`, a line a strategy after its item's comparison).
   - **For Justin.** Deviation 1 changes D5 after part 1's pilot, and SIM_CHECK.md:37-39 lists it
     on the page with its commit; it needs his yes before that commit.
   - **The engine's side, built in 134da21** (19 October 2026; SHOE_SPEC.md B7 says what each
     part does, corrected there to match):
     - **The exporter.** Refit keeps `t.now.ranking` beside the refit's ranking and refuses a fold
       whose Strategy groups or table are not the refit's (each row of its item kept as failed,
       the replay going on); `build` writes `split` lines where the two differ; the round trip,
       on each row's values as `checkFull` runs it, holds every `first` line whole to the refit's
       ranking and every ranking split hands read to the walked one, after the states' values so
       that a first move written wrong is still refused by them; `Lookup` writes B8's 1,000 split
       cells from the walked play's SplitPlay (SplitValues.fingerprint's first-decision slots),
       and the round trip's `lookups` works them out from B8's words through ExportBundle and
       holds every cell. The export's log names each fold's `split` lines and the first.
     - **ExportBundle**: `Strategy.split`, the parser and every refusal of B2.1, the B2.4
       accessors (`dealtFirst`, `splitRanking`), and `readLookups(file, full)`, told by the round
       trip whether the bundle holds a full-play file, with B8's presence rule.
     - **Tests**, each with its plants recorded in its class comment (22 planted in scratch copies
       outside the worktree, each failing the test named): ShoeSpecTest's split hands of B2.4
       (1,407 decisions of its random tables read a `split` line, 711 of them otherwise than their
       `first` line); StrategyExportTest's hand-made full file with two `split` lines, every
       refusal of B2.1, the B2.4 accessors and a `split` line in a lookups.tsv with no full-play
       file; FullPlayExportTest, whose fixture has 0 `split` lines and keeps its byte equality
       and kill and resume, with a hand-made difference for the bundles that need one (first and
       `split` swapped, a `split` line dropped, a split cell read from the `first` line; made on
       the walked table until 3b2f104, below) and planted bundles on its own (a `split` line
       where the rankings agree, a `first` line's tail reordered, a split cell flipped,
       lookups.tsv without its split lines or with two out of order); PageLookTest, whose F1
       full-play export now passes with no refusal branch: fold 0 stopped at the pass cap of 6
       with pass 0's play kept, refitDiffers 29, 31 `split` lines; fold 1 converged, refitDiffers
       0, one `split` line, a pair of tens against a 10 ranked S P H D and S H D P, which no split
       hand reads differently; FullIndexReplayTest, the hook path's files held to each strategy's
       walked ranking and the log's lines.
     - **Two tests fail at 134da21, neither from the exporter.** SimReadsWidePlansTest's two D4
       tests: the committed simulator (fe9a91e) refuses lookups.tsv's `split` lines ("lookups.tsv
       line 12024: not a first line (hit -) or a later line (hit 1 to 10), first lines first"),
       as SIM_CHECK.md's identity row says it will until sim/ reads them; they pass again with the
       clean-room session's simulator. SiteSimTest.theIdentityTableNamesTheLastCommitOfEachFileItRecords
       has failed since 46d76a5 changed SHOE_SPEC.md, and now names 3b2f104, its last commit
       (B7 again, below); it passes once SIM_CHECK.md's identity row records that commit, by (b),
       after Justin's yes.
     - **The review of 134da21, answered in 3b2f104** (19 October 2026). Every check above held a
       file's `split` lines to `Refit.walked`, which Refit takes from `t.now.ranking`, and `Lookup`
       built B8's split cells from the same table, so a wrong walked table passed them all: the
       reviewer's plant, `walked(f, ranking[f])`, the one ranking of before the split lines,
       passed F1's 80% export in PageLookTest with no `split` line where 32 are due (the review's
       run). Now Refit
       also keeps, from the strategy itself, every split its last round valued with its
       fingerprint (`FullIndexFit.valuedSplits`), and the check on the values plays each again from
       the file alone (`StrategyExport.playedAsValued`): the file's split hands must answer every
       decision as the valued play did. With that plant F1's export is refused (fold 0, a split of
       8s against an ace in group 0, Split offered: S by the file, P as valued). FullPlayExportTest's
       hand-made difference changed the walked table and not the values, so its `split` line was
       not the play its split was valued under and it passed; it now reorders a `first` line's
       tail at a valued split instead, and the old hand-made walked table is a test that must be
       refused. PageLookTest holds F1's files to the replayed strategies apart from Refit (31 and 1
       `split` lines). The full-play files of every 12-shoe fixture have one group, so a check that
       skipped groups past the first passed every test; FullPlayExportTest now also uses
       FullIndexFitTest's capped run (Hi-Lo at 80%, stopped after pass 0, groups formed with mPlay
       1: 2 and 3 groups, 109 and 22 `split` lines across every group, 500 splits played again)
       and plants files wrong in fold 1's last group. Only `Refit.Refused` (groups or table, a
       walked ranking's group count) is kept as a row's failure; anything else Refit throws stops
       the replay at its item, and a row whose exception has no message is kept by its class, so
       `finish` refuses it rather than writing it. ShoeSpecTest asserts its two counts (1,407 and
       711). 17 bugs planted in scratch copies outside the worktree (9 new, and the 8 of 134da21's
       in FullPlayExportTest planted again on this code), each failing the test named and recorded
       in its class comment. SHOE_SPEC.md B7 says what was built; no figure
       changes.
   - **What remains on the engine side**, in order:
     - **SHOE_SPEC.md's "How this file is held to the engine"** is stale in three places, left
       alone while the clean-room session reads that section: it gives ShoeSpecTest 17 tests
       twice (it has 19, as at 46d76a5), calls the transcription of the `split` lines "a change not
       yet made" (made in 134da21), and says StrategyExportTest is to refuse planted full-play
       bundles "once the split lines are written" (they are, and those plants are in
       FullPlayExportTest). To be corrected when that session ends.
     - **The exports again.** RowExport kept nothing of a refused row
       (src/StrategyExport.java:2463-2472), so row 6's 15 full-play rows and row 7's F1 and F2
       full play are replayed with `--export`, after (e) above.
     - **The identity table**: SHOE_SPEC.md's new commit and the simulator's go into
       SIM_CHECK.md's rows before part 2's pilot, by (b). Before that pilot too, deviation 1's
       check of the new simulator commit on part 1's bundle against part 1's main run
       (runs/sim/part1-0e0920d): run by an integration session, the simulator as a black box,
       not by the clean-room session.
   - **The simulator: the task of its clean-room session.** The session reads this bullet, not the
     rest of this item or of INTEGRATION.md, whose other bullets name engine code. Its reading:
     SHOE_SPEC.md except B7 (for this change B2.1, B2.4, B6 item 12, B8 and "Changes to Part B"),
     SIM_CHECK.md (deviation 1, sections 5, 9, 10 and 12, the identity table), ROUND_CONTRACT.md,
     sim/QUESTIONS.md, sim/ and sim/test; nothing under src/ or test/ and no run's output. It
     records itself as a fourth session in sim/QUESTIONS.md, with any question it cannot settle
     from those words. What it is to do, in the words of those files:
     - sim/Strategy.java: `split` in ORDER between `first` and `insure` (lines 25-27); a split
       table by group and deal, empty where no line; a reader of `split` lines refusing each
       thing B2.1 refuses (a file not of scope `full`, a group outside 0 to G − 1, p1 > p2 or a
       rank outside 1 to 10, the natural (1, 10), a line not after the last one in `first`
       order, moves other than its `first` line's in another order, a repeat of that order); the
       split hand's first decision in Play.choose (lines 250-263) reading the `split` line, else
       the `first` line, through one method that sim/Lookups.java calls too, frozen at the deal's
       key or, in the variant, at the moving key; the dealt hand's paths (lines 220-222 and 239,
       and sim/Table.java:106) unchanged; the class comment (lines 11-21) saying so.
     - sim/Lookups.java: B8's `split` lines after the `later` lines, `state` and `hit` `-`, r and
       x unsorted (the parse at lines 151-163 takes `state` as a number and needs p1 ≤ p2 today);
       exactly 1,000 when a full-play file is in the bundle and none otherwise, else refused;
       cells `-` for a file not of scope `full`, else the G letters by B2.4 under Montreal's fixed
       legal sets, and `:` and G more for a split 2 to 10 that draws its own rank; split cells
       counted apart, D5 failing when a full-play file is present and none was compared.
     - Tests: sim/test/StrategyFixture.java gains `split` lines; StrategyTest gains the refusals
       above and play cases: `first 9 10 10 6 P S H D` with `split 9 10 10 6 S P H D`, where the
       dealt pair splits and a split ten that draws a ten stands, and resplits without the line;
       one non-pair `split` line read by both hands that reach it (a split 3 drawing an 8 and a
       split 8 drawing a 3); ace and ten, and split aces, still standing; the moving variant
       reading its moving group's `split` line. sim/test/LookupsTest.java's text (lines 45-54,
       125-139) gains the full-play file's 1,000 `split` lines (1,025 lines and 5,125 cells), split
       cells worked by hand, a planted split cell, the refusals (missing, out of order, present
       with no full-play file), and a case where split hands read `first` and D5 catches it.

21. **What item 12 left, and what it found** (site-last-b; src/SiteBuild.java, site/template.html,
    site/page.css). Decided there, for Justin to overrule: the every-count table keeps 80% and 1-8
    and says so, since the chart above it switches; the controls are cut card, spread and wonging,
    not "which decisions use the count" (the chart shows first decisions and full play together);
    wonging is the B plans entering at 0, labelled "where back-counting is allowed (it may be
    refused at the Casino de Montreal)", each view giving the share of rounds played and the
    average bet. Found and fixed: the contents list, fixed in the left margin from 75rem, overlapped
    every wide figure at any width (wide figures reach 28rem left of the centre, the list ended 22rem
    left of it); it is fixed from 88rem now. A headless Chrome run on a fixture build (a scratch
    harness, not committed) worked the toggles, the failed-fetch path and the page without its
    script, at 375px light and dark with no sideways scroll, and at 1280 and 1440px. Open:
    - **The page's weight.** A fixture build with every new block but no risk, ledger or
      simulator z tables is 196,264 bytes of the 200 KB budget; the full claims table (29 KB with
      claims.json's claims), the every-count chart (36 KB) and the counts table (37 KB) are most
      of it. Row 10's real build may go over, and SiteBuild then writes nothing. If it does, the
      plain cut is the counts table's three figure columns, which repeat the chart's.
    - **The views' size on disk.** 39 fragments on the small fixture, 1.4 MB, each fetched only
      when asked for (5 to 6 KB compressed); PLAN_PAGE.md 2.1 guessed about 1 MB for all of
      card-counting/. Every fragment is under VIEW_BUDGET (120 KB) at the plan's largest grid.
    - **The suite's JUnit report.** The evidence table says "not re-run for this page" until row 10
      gives `--junit` and `--rerun-at`: the whole suite at the site's tag on a free machine, with
      `--reports-dir`. PLAN_PAGE.md 3.5's "422 tests, a9d1d02" is not in a9d1d02's message, so it
      is not a row.
    - **Units.** SiteSimReported's tables are in percent of a unit now (the files' fractions
      times 100), and mode B's differences print in percentage points; they had printed fractions
      beside percent figures. A key one fold never met had made a NaN that Json.write refuses, so
      `SiteAdapt sim` would have stopped on a real ev-by-key.tsv; it is null and prints a dash.
    - **Not built** (site-last-b's report; recorded here at the merge): PLAN_COMPUTE.md 3.6's
      spread figures W1 to W5 and the +0.8% box. The template has neither (its spread chart stops
      at 1-8; W1 runs 1-8 to 1-20). Whether the +0.8% question is on the page at all is Justin's
      decision 11 in PLAN_COMPUTE.md section 8.
22. **The page prints the planted runs a line a run, not a line a test.** (site/template.html,
    src/SiteSimReported.java; found at the merge of 15 October.) SIM_CHECK.md section 10 says
    that for each planted bug the page prints which D checks failed, every test's z, the largest
    |z|, and the bias each test would catch 90% and 95% of the time. The `sim_planted` block
    prints the run, its failed D checks, its largest |z| and whether it was caught. SiteAdapt sim
    writes every test's z and those biases into `sim_planted_tests` (14 tests a run, with the
    part of each since site-last-a's two halves), but no block prints that table. Its `caught90`
    and `caught95` are still fractions of a unit, the test's SE times a multiple. Every other
    table SiteSimReported writes has been in percent since site-last-b, and its class comment
    says all of them are. So the block that prints them must convert them the same way, with
    a test, the way site-last-b's units fix was made. Before row 10's page.
15. **Not yet run here:** the simulator's D6 determinism on six threads (sim/test ran with
    `-Dsim.test.threads=1`; once without it when the machine is free); RealCountTest and
    FullIndexFitTest (no scoring class changed in any merge since: SystemScorer, FullIndexFit,
    CountKeys and ScoringStates are as site-integration had them, and 5cb48a4 and 62ae14b touch
    none of them, nor sim/); and no independent tree walk
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
| 5 | Simulator part 1 | yes, after rows 1 and 2, with the bundle's SHA-256 recorded before the pilot (item 18 is decided: SIM_CHECK.md section 10) |
| 6 | Replay of c06c061 | after row 4; its export depends on open item 1 |
| 7 | Page look | yes, after row 6 and custom-count-design's merge; its full-play export depends on open item 1 |
| 8 | Simulator part 2 | yes: a pilot a bundle, then one fill (open item 17, closed); the planted runs on both bundles (item 18, closed) |
| 9 | SplitMargin on the twin | yes |
| 10 | Reports, adapters, the page | each command runs once its inputs exist; `SiteAdapt sim` needs both parts' section 8 blocks filled; the page may pass its 200 KB budget (open item 21), and it prints the planted runs a line a run, without each test's z or the bias it would catch (open item 22) |

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
ebc7761); open item 18 is decided (SIM_CHECK.md section 10: the planted runs on both bundles, so
this bundle is played again after part 2's main run, row 8). The export is part 1's rows
(SIM_CHECK.md section 3) with the chart's row from step 7's save, at the plans part 1's figures use
(flat is always written):

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
the page look's two, with the planted runs' first halves on part 1's bundle. First the three part 2
bundles' SHA-256 in the identity table (rows 6 and 7), committed. Then the pilot, one a bundle
(open item 17, closed by f9c3b90): the replay's, the page look's two, and part 1's, where the
planted runs' first halves are timed (SIM_CHECK.md section 10). Each names the figures of part 2
its bundle lacks and exits 1, which is expected; the one on part 1's bundle holds none. A pilot
that writes no file stopped on an exact check, and the part stops there. The four files are
copied beside SIM_CHECK.md, and `SimCheckPlan fill` takes them at once and prints section 8's part
2 block: the bundles' setups, P3s and P2s added, the extras only of the replay's bundle and part
1's, where the main run plays them. The four files and the block (between the fill-2 markers) are
committed before the main run, which then plays each bundle at the block's N and K:

```sh
B2=$R/runs/sim/export-part2-replay-$SHA && O2=$R/runs/sim/part2-$SHA && PI2=$R/runs/sim/pilot-part2-$SHA && cd $W
for X in replay=$B2 look-first=$L/export-first look-full=$L/export-full part1-bundle=$B1; do
  java -cp $CS sim.Sim pilot --part 2 --bundle ${X#*=} --out $PI2/${X%%=*} --sim-commit $SIMSHA \
    --registry $W/SIM_CHECK.md --catalog $W/counting-systems.json --threads 6
  cp $PI2/${X%%=*}/sim-pilot-part2.tsv $W/sim-pilot-part2-${X%%=*}.tsv
done
java -cp $C SimCheckPlan fill sim-pilot-part2-*.tsv     # section 8's fill-2 block, committed with the four files
java -cp $CS sim.Sim p2 --bundle $B2 --out $O2 --sim-commit $SIMSHA --catalog $W/counting-systems.json \
  --registry $W/SIM_CHECK.md --threads 6 --k K
java -cp $CS sim.Sim p3 --bundle $B2 --out $O2 --sim-commit $SIMSHA --registry $W/SIM_CHECK.md \
  --threads 6 --chunks N --fan-paths 20 --fan-rounds 100000 --ruin-paths 20000
java -cp $CS sim.Sim variants --bundle $B2 --out $O2 --sim-commit $SIMSHA --chunks 4 --threads 6
O2L1=$R/runs/sim/part2-look-first-$SHA && O2L2=$R/runs/sim/part2-look-full-$SHA
for X in $O2L1=$L/export-first $O2L2=$L/export-full; do
  java -cp $CS sim.Sim p2 --bundle ${X#*=} --out ${X%%=*} --sim-commit $SIMSHA --catalog $W/counting-systems.json \
    --registry $W/SIM_CHECK.md --threads 6 --k K
  java -cp $CS sim.Sim p3 --bundle ${X#*=} --out ${X%%=*} --sim-commit $SIMSHA --registry $W/SIM_CHECK.md \
    --threads 6 --chunks N
done
```

After the main run, the planted bugs, each at one chunk and K = 6,000, with a clean control beside
them, every one in two halves (SIM_CHECK.md section 10, open item 18): on part 1's bundle ($B1, into
$O1-plant-NAME), scoring F03, F15 and F18, and on the replay's ($B2, into $O2-plant-NAME), scoring
F26 to F29:

```sh
for PL in none s17 no-das split-aces-hit hole-card truncate insure-whole-bet other-fold bet-key-plus-one; do
  A=""; [ $PL = none ] || A="--plant $PL"
  for H in $O1=$B1 $O2=$B2; do
    java -cp $CS sim.Sim p3 --bundle ${H#*=} --out ${H%%=*}-plant-$PL --sim-commit $SIMSHA \
      --registry $W/SIM_CHECK.md --threads 6 --chunks 1 $A
    java -cp $CS sim.Sim p2 --bundle ${H#*=} --out ${H%%=*}-plant-$PL --sim-commit $SIMSHA \
      --registry $W/SIM_CHECK.md --catalog $W/counting-systems.json --threads 6 --k 6000 $A
  done
done
```

SIM_CHECK.md's identity table names all three part 2 bundles. Each run directory's checks.txt,
summaries, swings, records, ruin and trip files stay together: SiteAdapt sim reads them by
directory. P3 and P2 compare the chart's walk on every bundle; on part 2's, which do not export
the chart's row, it has no exact side, and SiteAdapt sim leaves it out.

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
and 10, closed), and the simulator's trip files beside its own trip risk (open item 19, closed).
`SiteAdapt sim` refuses every run until both parts' section 8 blocks are filled and every bundle is
in the identity table, naming what is missing; it takes each planted run's two halves (item 18).
`SiteAdapt evidence` holds verification/evidence.tsv, as committed, to the commits its rows cite in
$W's history; until it is given the whole suite's JUnit report at the site's tag, each row says it
was not re-run for this page (open item 21). SiteBuild writes the every-count chart's views beside
the page, in assets/views/.

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
  --ruin F29=$O2/ruin-F29.tsv --trip F27=$O2/trip-F27.tsv --trip F29=$O2/trip-F29.tsv --fan F27=$O2/fan-F27.tsv \
  --md $R/runs/risk/report-risk-$SHA.md \
  --json $R/runs/risk/risk-$SHA.json
java -cp "$C:$M" SiteAdapt risk $R/runs/risk/risk-$SHA.json --out $R/runs/site/risk-$SHA.json
# The simulator: every run, part 1's and part 2's (the replay's bundle, $O2, and the page look's two, $O2L1 and $O2L2),
# held to SIM_CHECK.md section 12; mode B, the planted runs beside their control, each in its two halves, {N} from
# c06c061's SEs.
PLANTED=""; for PL in none s17 no-das split-aces-hit hole-card truncate insure-whole-bet other-fold bet-key-plus-one; do
  PLANTED="$PLANTED --planted $PL=$O1-plant-$PL --planted $PL=$O2-plant-$PL"; done
java -Xmx4g -cp "$C:$M" SiteAdapt sim --plan $W/SIM_CHECK.md --published $C06/results.ser --run $O1 --run $O2 \
  --run $O2L1 --run $O2L2 --variants $O2/summary-variants.json $PLANTED --out $R/runs/site/sim-$SHA.json
# The registration box, from the ledger's one home.
java -cp "$C:$M" SiteAdapt ledger $CD/custom-count-ledger.tsv --count-design $CD --out $R/runs/site/ledger-$SHA.json
# The literature (it reads step 7's save and report; run from $C for the catalog and commit.txt).
java -cp "$C:$M" LiteratureCheck --claims $W/literature/claims.json --cache $R/runs/literature/cache \
  --report-final $R/runs/report-final-26f0724.md --custom-counts $CD/custom-counts.json \
  --ceiling-save $R/runs/report-ceiling-$REG.ser --ceiling-report $R/runs/report-ceiling-$REG.md \
  --out $R/runs/literature --json $R/runs/literature/literature-$SHA.json
java -cp "$C:$M" SiteAdapt literature $R/runs/literature/literature-$SHA.json \
  --out $R/runs/site/literature-$SHA.json
# The evidence table, each row found in the commit it cites; with the suite's JUnit report at the site's tag
# (the whole suite on a free machine, --reports-dir) add --junit .../TEST-junit-jupiter.xml --rerun-at TAG.
java -cp "$C:$M" SiteAdapt evidence $W/verification/evidence.tsv --repo $W --out $R/runs/site/evidence-$SHA.json
# The page, into a directory outside the website's tree; nothing is copied there without Justin's yes.
cd $W && sh site/build-site.sh --out $R/runs/page-$SHA --inputs $R/runs/site
```

After row 10, nothing needs c06c061's trees.bin; deleting it (14.9 GB) is Justin's call.
