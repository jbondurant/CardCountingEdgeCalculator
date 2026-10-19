# Questions the simulator's author could not settle from the spec

The simulator in `sim/` was written in a clean room: from ROUND_CONTRACT.md, SHOE_SPEC.md,
SIM_CHECK.md (branch site-sim-check), PLAN_COMPUTE.md section 1 and 7.1, RESULTS_SITE.md and
Loto-Québec's published rules, without reading the engine (`src/`, `test/`) or any run's output.
Where those sources left something open, the question is written here with the reading chosen,
so the engine's side can answer it and either confirm the choice or say what to change.

**Second session, 12 October 2026 (branch site-int-sim, off site-int-spec).** It finished D2, D5,
mode B, the paired differences, the per-shoe records, fans and ruin paths, the swings and
ev-by-key.tsv, from the engine side's answers now in SHOE_SPEC.md. It read: SHOE_SPEC.md (all,
including Part C and the answers), SIM_CHECK.md (all, including the `sim-registry` block),
PLAN_COMPUTE.md sections 1 (lines 96-349), 2.4 (423-458, for the formats), 5.4 (925-972, for
item 9's key convention) and 7 (1079-1148), RESULTS_SITE.md, counting-systems.json, everything in
sim/, run-tests.sh (the build script), and the earlier builder's report for site-sim. It opened
nothing under src/ or test/ and no run's output. PLAN_COMPUTE.md has no section 11; SIM_CHECK.md's
section 11 (the paths) is the one used.

Items 1 to 12 were answered on the engine side in SHOE_SPEC.md ("Answers to the simulator's
questions"); each now says what was done. Items 13 to 23 are the second session's.

**Third session, 13 October 2026 (branch site-fix-sim, off site-integration at 304901c).** It
mended INTEGRATION.md's open items 2 (p3 stopped after its chunks on a bundle with no figure
that keeps paths), 3 (the pilot), 11 (trip risk and the chance of being behind) and the
simulator's side of 10. It read: INTEGRATION.md (all), SIM_CHECK.md (all), SHOE_SPEC.md B9, Part
C, the answers and "How this file is held" (lines 1000 to the end), PLAN_COMPUTE.md sections 1
(lines 96-349) and 2.4 (423-458), everything in sim/ and sim/test. A search for headings over
PLAN_COMPUTE.md also showed the first lines of 2.3's items 7 and 8 (trip risk "losing B within T
rounds, by Brownian first passage"; being behind "after H hours: Φ(−μ√T/σ_eff), T = H·r, for H
= 10, 100, 1,000"); nothing else of 2.3 was read. It opened nothing under src/ or test/ and no
run's output. It compiled src/ and test/ unread and ran test/SimReadsExportTest.java as a black
box, looking only at its counts (1 passed, 1 skipped: its disabled half, as before). Items 24 on
are this session's.

**Fourth session, 19 October 2026 (branch split-ranking-sim, off split-ranking at 46d76a5).** Its
dates, here and in the test classes' records of plants, are UTC; by the local clock (EDT, UTC − 4)
its first commit, 285f0fb, and the plants recorded with it were made on the evening of 18
October. It made sim/ read SHOE_SPEC.md's `split` lines (B2.1, B2.4, B6 item 12, B8, "Changes to
Part B"): INTEGRATION.md open item 1's bullet addressed to the clean-room session, relayed in the
task's words; nothing else of INTEGRATION.md was read, and the file itself was not opened. It
read: ROUND_CONTRACT.md; SHOE_SPEC.md Part B but B7 (lines 514 to 970 and 1097 to 1240, the last
few Part C's opening), A4 to A7 (lines 328 to 427, which B2.4 and B8 cite for the split hands'
legal moves and the frozen key), and "Answers to the simulator's questions", "How this file is
held to the engine" and "Changes to Part B" (line 1305 to the end); SIM_CHECK.md's identity table
and deviation 1 (lines 1 to 73), sections 5, 9, 10 and 12, and the headings of both files; this
file; in sim/, Strategy, Lookups, Round, Table, Rules, Plants, Bundle and Sim's exact checks and
entry point, and in sim/test BundleFixture, StrategyFixture, StrategyTest, LookupsTest,
SimCommandsTest, SpecCopyTest and TableTest's varied fixture (FormatsTest only as item 27 names
it; the file was not opened), with searches of sim/ by grep, one of which, for the wording of the
plant records, also printed the 3 lines of PLAN_COMPUTE.md and the 1 of SIM_CHECK.md that match
it; and `git log` of sim/. It opened nothing under src/ or test/, no other worktree, no run's
output, and neither PLAN_COMPUTE.md (beyond those 3 lines), RESULTS_SITE.md nor
counting-systems.json. (This paragraph was corrected after the review, from its reading of the
session's tool calls: it had listed FormatsTest as read and left out the grep's other files.)
Items 28 on are this session's.

**The fourth session's review fixes, 19 October 2026 (the same branch).** A clean-room review of
285f0fb, against the same sources, found two major problems (no test read a `split` line whose list
starts with its `first` line's move; no test held Hit and Double legal after a cell's `:`) and five
minor ones (the low rank and up-card refusals, a `split` line's `state` and `hit`, item 28, this
file's account of what was read, and the dates); the second commit on this branch fixes all seven.
For it the session read: sim/Strategy and Lookups; searches of Sim (for D5, lookups and split), of
Bundle (for its refusals) and of Round (for the legal moves' bits), and Sim's D5 lines; in sim/test
LookupsTest, StrategyTest, StrategyFixture, SimCommandsTest, BundleFixture and TableTest's varied
fixture; this file; SHOE_SPEC.md B2 (lines 592 to 771), B6 items 10 to 12 (925 to 970), B8 (1097 to
1174) and the headings; SIM_CHECK.md lines 1 to 73, section 5 (349 to 392), section 12 (711 to 725)
and the headings; the first commit's scratch scripts for its plants and its runs of old against
new; and sim/ at 46d76a5 (`git archive 46d76a5 sim`), to build it for that comparison again. A
search for `split` over SHOE_SPEC.md also printed 27 lines of B7 (lines 984 to 1091), which is for
the exporter; nothing in them was used. A search for `checks.txt` over SIM_CHECK.md, SHOE_SPEC.md
and ROUND_CONTRACT.md printed SIM_CHECK.md's line 713 alone. It opened nothing under src/ or test/,
no other worktree, no INTEGRATION.md and no run's output.

**Fifth session, 19 October 2026 (branch d2-sim, off d188bca).** It made D2 leave out, and name, a
keys-sample.tsv column whose name no count of the catalog or of the bundle's files has: the bullet
d188bca added to SHOE_SPEC.md B4 after part 2's pilot stopped on D2 for the page look's two
bundles, with the requirements for checks.txt as the task relayed them. It read: SHOE_SPEC.md B0 to
B6 (lines 514 to 980), B9 (1224 to 1275, and Part C's heading on 1276) and the headings of the
whole file (which also printed the `#` lines of the examples in B1, B4, B5, B8 and C4), and d188bca
itself (`git show`: its message and its diff of B4); SIM_CHECK.md section 5 (lines 349 to 392),
section 12 (711 to 725) and its headings; ROUND_CONTRACT.md's first 60 lines; this file; in sim/,
KeysSample and Catalog, Sim's exact checks (lines 255 to 335) and Outputs' first 60 lines, with
searches of sim/ by grep for D2, keys-sample.tsv, checks.txt and wider patterns, whose hits
included single lines of Catalog, Lookups, Plants, Rules, States, Pilot and Sim (none of the first
three patterns matches Lookups, Plants, Rules or States; the review found that patterns such as
`checks` and `KeysSample` do, and this sentence, which had tied those files to the three, was
corrected after it), of Pilot and Sim for the exact checks' callers, and of States and Bundle for
one method each; in sim/test, KeysSampleTest, FormatsTest, SimCommandsTest and BundleFixture,
StatesFixture's first 80 lines and LookupsTest's record of plants (lines 19 to 33), with searches
of sim/test for D2 and KeysSample's result and of StrategyFixture for its counts; `git log` of the
branch and of sim/; and, of the runs' output, the one file the task named,
runs/sim/pilot-part2-ae283e4/look-first/pilot-part2-checks.txt, for its lines' text. For item 34 it
built sim/ at d188bca (`git archive d188bca sim`) outside the worktree. It opened nothing under
src/ or test/, no other worktree, no INTEGRATION.md, neither PLAN_COMPUTE.md, RESULTS_SITE.md,
SHOE_SPEC.md B7 or B8, nor counting-systems.json (which the tests read), and no other run's output.
Items 33 on are this session's.

**The fifth session's review fixes, 19 October 2026 (the same branch).** A clean-room review of
268b2d0, against the same sources, found one major problem (a column of a file's count that is
misnamed was left out as no count's, so the bundle's own count went unchecked and D2 passed, where
at d188bca it failed) and one minor one (the paragraph above tied the grep's files to patterns that
do not match them); the second commit on this branch fixes both (item 33's last paragraph). For it
the session read: the review's findings as the task relayed them; sim/KeysSample (all), Sim's exact
checks and p2's arguments (lines 255 to 380), Strategy's fields (30 to 60), Bundle's reading of the
strategy files (95 to 135), with searches of sim/ for KeysSample, leftOut and D2 (hits in Catalog,
Lookups, Pilot and Sim), of sim/ and SHOE_SPEC.md for playcount and betcount, of Bundle for its
strategies and refusals, of Catalog for `named` and `all`, of Strategy for `parse` and of EvByKey
for its rows; in sim/test KeysSampleTest and BundleFixture (all), SimCommandsTest's lines 1 to 60,
108 to 160 and 185 to 330, FormatsTest's lines 1 to 30 and 88 to 140 and P3ExtrasTest's 160 to 200
(a fixture whose files name a count "Other"), with searches of sim/test for keys-sample.tsv,
KeysSample, playcount and `Result(`; SHOE_SPEC.md's headings, B2's opening and its table (lines 592
to 625), B4 (818 to 872), B6's opening (907 to 930) and B9 (1224 to 1275); this file's first 120
lines and items 33 and 34; the one pilot checks.txt again; the first commit's scratch plant script
and runs of old against new; and `git status` and `git log` of the branch. It opened nothing under
src/ or test/, no other worktree, no INTEGRATION.md, no bundle and no other run's output, and this
time neither SIM_CHECK.md, ROUND_CONTRACT.md, PLAN_COMPUTE.md, RESULTS_SITE.md nor
counting-systems.json (which the tests read).

1. **Soft 17.** Loto-Québec's PDF leaves hit-or-stand on soft 17 to the sign at the table.
   *Chosen:* the dealer hits soft 17, from SHOE_SPEC.md A2 (`rule.hits.soft.17 = true`) and
   PLAN_COMPUTE.md 1.4 ("the dealer hits soft 17"). The walk's totals agree with A8 to the
   round under it (sim/test/WalkTest), and do not under the other reading: with the dealer
   standing on soft 17, planted on 9 October, the walk gives 3,210,144 round starts at 70%, not
   3,197,853. *Answered: confirmed.*

2. **"Only java.util".** PLAN_COMPUTE.md 1.1 says the simulator uses only `java.util` and
   compiles alone. Reading files needs `java.io` and `java.nio.file`, and checking the bundle's
   `manifest.sha256` needs `java.security.MessageDigest`. *Chosen:* the JDK's own library and
   nothing else, no third-party jar and no class of the engine; `javac -d out sim/*.java` with
   no classpath still compiles it. The tests in `sim/test/` use JUnit 5 from `lib/`, and are
   not part of that one-command compile. *Answered: confirmed.*

3. **counting-systems.json.** Check D2 has the simulator read counting-systems.json with its own
   parser, but that file is not among the sources this session was allowed to read, so its
   format is unknown here. *Chosen:* D2's parser is not written yet. The keys themselves (B2.2)
   are, and are tested on the parameters a strategy file gives (`tags`, `bettags`, `decksum`,
   `betdecksum`, `ace`, `divisor`, `mode`). A later session that may read the catalog adds the
   parser and the keys-sample.tsv comparison. *Answered: corrected, and done:* `Catalog.java`
   reads the file as B9 says with the simulator's own `Json.java`, and `KeysSample.java` is D2.

4. **The chart before the bundle exists.** D1 plays the chart in every round of the walk, and
   chart.tsv comes from the bundle. *Chosen:* until it exists, the walk plays a stand-in chart
   (`Chart.textbook()`, the published eight-deck basic strategy for H17 with double after split
   and no surrender, written from general knowledge). Its walk of all 60,000 main shoes gives
   exactly A8's round starts, valued states and weights at the three cuts. Its fingerprint is not
   the engine's (unreachable cells differ, B1), so every reported run plays chart.tsv; the
   stand-in is for the D1 totals before the bundle, and for tests. *Answered: confirmed;* every
   summary now carries the chart's fingerprint (`chartFingerprint`) for the reader to refuse.

5. **The deal's play key for insurance on a player's natural.** B2.4 says the player's natural is
   an ace-up deal like any other for insurance. *Chosen:* as written: the natural's deal (1, 10,
   ace) is looked up in `insure` lines like any other deal. *Answered: confirmed.*

6. **How the chart's row reaches the bundle.** SIM_CHECK.md's count list names the chart row
   "Chart, no count, flat" and calls it one instance at every penetration, fitted on nothing,
   whose flat figure has no insurance; B2 and B5 describe strategy files and exact.tsv lines by
   fold. *Chosen:* the simulator plays the chart's own walk as that row at 70, 75 and 80%
   (`Row.chart`), and takes the engine's figure from exact.tsv lines with that row name and
   folds 0 and 1, pooled as B5 pools any row; P2 takes it from a states.bin section of that name
   at 80%. A strategy file with that row name is not played beside the walk. If the exporter
   writes the chart another way, the summary's exact side for the chart is empty until this is
   settled. *Answered: confirmed, and the exporter writes it this way.*

7. **D5's lookups.tsv.** SIM_CHECK.md section 5 adds `lookups.tsv` to the bundle, but neither it
   nor SHOE_SPEC.md gives its format. *Chosen:* not written. Strategy.Play exposes what D5
   compares (`keyOfDeal`, `insuredAt`, `firstMoveAt`, and `choose` for later decisions), so the
   reader is short once the format is fixed. *Answered: corrected, and done:* `Lookups.java` is
   D5, from B8.

8. **The coin.** SIM_CHECK.md section 4 asks for a coin a round in P3 and a coin a re-deal in P2,
   from a stream of its own. *Chosen:* one `nextDouble` a round start (P3) or a re-deal (P2) from
   `Xoshiro.of(seed, index, 1, 0)`, drawn whether or not any plan needs it, and shared by every
   plan and instance on that round; a round is played when the coin is under PLAY. *Answered:
   confirmed.*

9. **The figures the simulator reports.** The registry of the 52 figures is SimCheckPlan's,
   engine side, which this session did not read. *Chosen:* the simulator reports every row and
   figure it can, `flat` and `ev:LABEL` for each plan of each row's files, under exact.tsv's
   figure names, and the engine-side reader picks the registered ones (SIM_CHECK.md section 12).
   P2 runs the rows at 80% only, as section 2 says. *Answered: confirmed, with one addition, done:*
   `Registry.java` reads the `sim-registry` block (`--registry`, by default SIM_CHECK.md in the
   working directory); every comparison carries its registered id (`fig`, empty for a figure the
   registry does not list), and the swings, records and fans are keyed by it.

10. **The pooling weights when a fold file is missing.** P3 pools the two folds with weights from
    exact.tsv's `flat` lines. *Chosen:* a row is played only when both its fold files are in the
    bundle; a figure without exact.tsv lines is reported with an empty exact side. *Answered:
    corrected, and done:* the bundle is refused when a row at a penetration has one fold file, or
    a strategy file lacks its exact.tsv line for `flat` or for any of its plans' `ev:LABEL`.

11. **Which groups are allowed.** B2.1 says `group g lo hi` lines come by g and B2.3 looks up the
    first group whose `hi` is at least the key. *Chosen:* the reader requires each group's `hi` to
    be above the last one's and `lo` to be at most its own `hi`, and nothing more; `lo` is for
    reading only. It refuses `first` lines in a betting-scope file, since B2.1 lists them for
    scopes first and full only. *Answered: confirmed, with one more check, done:* each group's
    `lo` must be above the previous group's `hi` (the same for `lgroup`), and `lgroup` lines need
    `lgroups` before them.

12. **What sizes a planted run uses.** SIM_CHECK.md section 10: P3 on the first chunk and P2 at
    K = 6,000, and a planted run is reported even when an exact check fails first. *Chosen:*
    `sim.Sim p2 --plant NAME` goes on to P2 after failed D checks whenever a plant is on (and
    stops there otherwise); the sizes are the command's `--chunks` and `--k`, given by whoever runs
    it. *Answered: the sizes are fixed* (P3 on the first chunk, P2 at K = 6,000: `--chunks 1 --k
    6000`), and the engine-side reader refuses a planted run of other sizes. The simulator does not
    refuse them itself, since its tests run planted bugs at tiny sizes.

13. **A registered count's bet column in keys-sample.tsv.** B9 says a column naming a registered
    count or its betting variant takes its parameters from the strategy file whose `playcount` or
    `betcount` names it. *Chosen:* a column some file bets with (`betcount`) takes that file's bet
    tags, bet deck sum, ace weight, mode and divisor for its bet key; a column some file plays
    with (`playcount`) takes that file's play tags for its play key. A column that only a
    `betcount` names plays on its base (the file's play tags), as B4 says of betting variants; a
    column that only a `playcount` names (the base of a count that bets on a vector of its own)
    bets on its own play tags with no ace count.

14. **The order of a colour or face count's parts.** B4 and B8 list the parts of a colour or
    face count's cell "in the order `CountKeys.tagSums` meets them", which is the engine's code.
    *Chosen:* D2 and D5 compare the parts by key, whatever their order: D2 requires the same set
    of keys with each chance within 1e-12 relative, D5 the same set of keys with the same group,
    move and insurance at each. The chances are the deal's three cards drawn without replacement
    from the kinds left, given the deal's ranks (the cards within a rank are exchangeable, so the
    order the three are drawn in does not matter).

15. **Mode B's coins and pooled rounds.** In mode B each instance has its own round starts.
    *Chosen:* each instance draws its coin, one a round start of its own walk, from a fresh copy
    of the shoe's coin stream `Xoshiro.of(20261008, h, 1, 0)`, and its exits are over its own round
    starts. Its rounds are its own; the folds are pooled shoe by shoe with exact.tsv's weights for
    the rounds as for the results, N_h = W_0·N_h0 + W_1·N_h1, so mode B's figure is
    Σ(W_0·Y_h0 + W_1·Y_h1) / Σ(W_0·N_h0 + W_1·N_h1). Mode B and the moving count run for the
    registry's F26 to F29 only (SIM_CHECK.md section 9's "flat and 1-8"), on P3's first four chunks
    (`sim.Sim variants`), each paired shoe by shoe with mode A's frozen count on the same shoes.

16. **Which differences from Hi-Lo.** SIM_CHECK.md section 9 asks for "every figure less
    Hi-Lo's at the same play, penetration and bets". *Chosen:* "the same play" is the same scope
    (`first`, `full` or `betting`); Hi-Lo's row is the one whose `playcount` and `betcount` are
    both `Hi-Lo = Hi-Lo Lite`; "the same bets" is the same exact.tsv figure name. The chart (no
    scope) and Hi-Lo itself have no difference. P2 reports the same pairs at 80% and, as section 9
    names it, Victor APC + ace less Hi-Opt II + ace at full play, each beside the engine's
    difference on the same states. All are in the summaries' `differences`, never in the pass rule.

17. **The ruin paths' formula.** SIM_CHECK.md section 11 runs the paths from "B_25%, B_5% and B_1%
    (the formula's, at the simulator's own μ and σ_eff from the same records)" and stops them "where
    the formula's remaining risk is under 5e-5"; the formula is in PLAN_COMPUTE.md 2.3, outside
    this session's reading. *Chosen:* the long-run risk of ruin exp(−2μB/σ²) with σ = σ_eff, so
    B_p = σ_eff² ln(1/p) / (2μ), and a path stops, survived, once its bankroll is above B at 5e-5.
    μ = ΣY/ΣN and σ_eff² = S/(S − 1) · Σ(Y − μN)²/ΣN from the records. A path that draws 10 million
    shoes without either end is counted apart as capped. The format of `ruin-FIG.tsv` is the
    simulator's own. If RiskReport runs its own paths from records-FIG.tsv, these are a second
    computation of the same thing, from the same seed rule.

18. **Trip risk and the chance of being behind.** SIM_CHECK.md section 11 cuts the records into
    blocks of H·r rounds; H and r are in PLAN_COMPUTE.md 2.3, outside this session's reading.
    *Chosen:* not computed in the simulator; records-FIG.tsv holds every shoe's (Y, N, M) in shoe
    order, which is all the blocks need, for RiskReport. *Done in the third session:* item 24.

19. **The records' shoes.** SHOE_SPEC.md C2 says "P3's first two chunks (2,000,000 shoes)".
    *Chosen:* the shoes of the first two chunks, 2 × `--chunk-size` (fewer if the run has fewer),
    so a run at another chunk size keeps its first two chunks' records. Each chunk writes its part
    of the records before its sums, so a chunk on disk has its records on disk; records-FIG.tsv is
    assembled in shoe order and refused if a part is missing or short.

20. **Files whose format nothing fixed.** `depth.tsv` (PLAN_COMPUTE.md 1.8): a line a band of 26
    cards dealt, `band from to rounds shoes simulated se exactWeight exact`, the simulated side the
    chart's walk with the ratio estimator's SE over shoes, the exact side states.bin's chart
    section at 80% weighted by 1/q. `variants.tsv`: `kind fig row pen figure what value se
    sigmaEff rounds shoes`, an `estimate` line for mode A, the moving count and mode B and a
    `difference` line for each variant less mode A. The summaries gain `differences` (row, the row
    it is less, the difference, its SE, σ_eff of the per-round difference for P3, both figures and
    the engine's difference for P2). C1 to C4's files are written as SHOE_SPEC.md gives them, the
    records and fan files without a comment line, ev-by-key.tsv with C4's.

21. **Swings in planted runs.** *Chosen:* the swings, records and fans play shoe h with the
    fold-(h mod 2) file whatever the `other-fold` plant says (that plant swaps P3's pooling
    weights, section 10). A round whose 2X is not whole or not inside the range derived from the
    rules (−17 to 16 here; −18 under `insure-whole-bet`) stops the run, as PLAN_COMPUTE.md 2.4
    asks; a bet not among the plan's units stops it too.

22. **The edge by key's range.** *Chosen:* ev-by-key.tsv's keys run from −128 to 128, wider than
    any Hi-Lo true count with 85 or more cards left; a key outside stops the run rather than being
    dropped.

23. **A bundle without keys-sample.tsv or lookups.tsv.** *Chosen:* `sim.Sim p2` reports D2 or D5
    as not run and counts the exact checks as failed, so P2 does not start (unless a plant is on).

24. **Trip risk and being behind: H, r and the bankrolls** (open item 11; item 18 is now done).
    *Chosen:* `p3` writes `trip-FIG.tsv` beside `ruin-FIG.tsv` for each figure with paths, from
    the same records (`Trip.java`). H = 10, 100 and 1,000 hours, from the one line of
    PLAN_COMPUTE.md 2.3 seen; r = 50, 80 and 100 rounds an hour, the paces SIM_CHECK.md section 11
    names, since 2.3's r was not read; both are options (`--trip-hours`, `--trip-paces`). The
    bankrolls are the ruin paths' B_25%, B_5% and B_1% (2.4 check 4: "the same for trip risk and
    P(behind)"), and trip lines are written only when the records' μ is above 0. A block is the
    fewest whole shoes reaching T = H·r rounds, so it holds a little more than T (column
    `meanRounds`); the formula is taken at T itself. A trip is lost when bankroll + the block's
    lowest running total ≤ 0, the lowest being the least of (the block's total before a shoe + that
    shoe's M); behind when the block's total is below 0; each share with its binomial SE over the
    blocks. Formulas: Φ((−B − μT)/(σ√T)) + exp(−2μB/σ²)·Φ((−B + μT)/(σ√T)) and Φ(−μ√T/σ), σ = σ_eff.
    *For the engine side:* confirm r, or name the paces RiskReport uses, and say whether the trip's
    bankrolls should be B_p or some other B.

25. **The pilot** (open item 3). *Chosen:* `sim.Sim pilot --part N --bundle DIR --out DIR
    --sim-commit SHA [--registry] [--catalog] [--threads]` reads the bundle, runs D1, D2, D4 and D5
    (setup; a failure stops it with no file, SIM_CHECK.md section 5), then P3, P2 and, in part 2,
    the extras, at 1% and blind, and writes `sim-pilot-partN.tsv` as section 8 gives it, plus
    `pilot-partN-checks.txt`. Its chunks and records go to `OUT/pilot-partN.work`, which it deletes
    when done (only a directory holding its own mark). What it times: P3 is the whole p3 command at
    1% (38 chunks of 10,000 shoes, the records of the first two, 20 fans of 1,000 rounds, 200 ruin
    paths, the trip blocks); P2 is K = 1,200 on every state. Part 2's extras are the variants
    command (4 chunks of 10,000 shoes) and the eight planted runs and the control (each P3 on one
    chunk of 10,000 shoes and P2 at K = 60); each planted run's bundle reading and exact checks are
    a fixed cost, so they enter `time extra` divided by 100 and the rule's ×100 counts them once a
    run. Every number is in units of a one-unit bet, as exact.tsv's and the summaries' are, not in
    percent: σ_eff near 1.16 for F03, the section 8 assumption. A figure of the part the bundle
    cannot give (its row not exported) is left out, named on the console, and the command exits 1.
    One bundle a pilot. *For the engine side:* (a) does `SimCheckPlan fill` read `sd`, `v` and
    `exact` in units (as written) or in percent? (b) part 2's F43 to F48 (open item 5) and F49 to
    F52 (open item 4) are not in any bundle yet, and part 2's rows come from two bundles (open
    item 6): should the pilot take several bundles, and should fill accept a file without the
    figures that cannot run (section 7)?

26. **What the summaries gained** (open item 10, the simulator's side). Every paired difference
    carries `fig` and `lessFig`, the registered ids of the figure and of the one it is less ("" for
    one the registry does not list), so the reader finds F25 less F18 and F29 less F27 by id; their
    `sigmaEff` is σ_eff of the per-round difference, from which {N} of PLAN_PAGE.md section 3 is
    computed engine side (this session did not read its formula). In summary-variants.json both
    ids are the figure's own and `less` says which variant less mode A. summary-p2.json carries
    each exact check's outcome as facts `D1`, `D2`, `D4` and `D5` (`passed`, `failed`, `not run`),
    which a planted run's page block needs ("which D checks failed"). summary-p3.json's `records`
    fact names the figures kept, or says none were, and a `trip` fact gives H, r and the
    bankrolls. A reader that refuses unknown keys must accept these.

27. **The simulator's own formats, in one place.** SHOE_SPEC.md Part C fixes swings.tsv,
    records-FIG.tsv, fan-FIG.tsv and ev-by-key.tsv. The others are the simulator's; their header
    lines are below, as the writers write them, and `sim/test/FormatsTest.java` holds the writers
    to this block. The engine side may move them into Part C. A summary's comparison and difference
    are JSON objects with these keys in this order; its facts are strings.

```sim-formats
depth.tsv	band	from	to	rounds	shoes	simulated	se	exactWeight	exact
variants.tsv	kind	fig	row	pen	figure	what	value	se	sigmaEff	rounds	shoes
ruin-FIG.tsv	risk	bankroll	paths	ruined	simulated	se	capped	meanShoes
trip-FIG.tsv	kind	hours	pace	rounds	blocks	meanRounds	risk	bankroll	simulated	se	formula
comparison	check	row	pen	figure	exact	exactSe	simulated	simulatedSe	rounds	shoes	difference	z	pass	flagged	unseenKeyRounds	fig
difference	check	row	pen	figure	less	difference	se	sigmaEff	first	second	exact	rounds	shoes	fig	lessFig
sim-pilot-partN.tsv	part|setup|time|sd|v|exact
```

28. **Split cells, counted apart** (B8: D5 "counts the `split` cells apart from the others, and
    fails if the bundle holds a file of scope `full` and no `split` cell was compared"; SIM_CHECK.md
    section 5: for the full-play instances, every split hand's first decision in every group, and
    "Those cells are counted apart"). *Chosen, after the review:* the split cells are the
    full-play files' columns of the `split` lines, the decisions section 5 names; the other files'
    columns of those lines, each `-`, are compared too and counted apart from them. (285f0fb
    counted every cell of a `split` line as a split cell, the `-` included: 5,000 in LookupsTest,
    where 1,000 are of a full-play file.) The totals of lines, cells and mismatches still include
    every line and cell, as D5 has always counted every cell of a `first` or `later` line and as
    the task's figures for LookupsTest count them (1,025 lines and 5,125 cells); `splitLines`,
    `splitCells`, `splitMismatches`, `splitOtherCells` and `splitOtherMismatches` are the parts on
    `split` lines, so the `first` and `later` lines' own counts are the totals less these. A
    full-play column gets its letters whatever its count (a colour count too) and its
    penetration, since B8 gives a `split` cell no state and no key. checks.txt keeps its D5 line
    as before and gains a second: `D5`, a tab and `split cells, the full-play files' columns of L
    split lines: N of M equal to lookups.tsv's; K mismatches; the other files' columns there, each
    -: N' of M' equal; the bundle holds a|no file of scope full`. On a bundle with no full-play
    file the first line is byte for byte what fe9a91e wrote and the second gives 0 split lines and
    0 of 0 (what deviation 1's check on part 1's bundle reads as "no `split` cell"). Since the
    reader refuses lookups.tsv when the bundle holds a full-play file and the `split` lines are not
    all 1,000 in order, the "none compared" failure cannot be reached through a whole file;
    `Lookups.Result.passes` holds it anyway, on the full-play columns alone, and a test holds the
    rule itself. *For the engine side:* confirm that the split cells are the full-play columns'
    (checks.txt gives both counts, so either reading can be read from it).

29. **What the strategy reader refuses of a `split` line, and the one refusal it adds.** *Chosen:*
    each refusal B2.1 lists, with its own message: a file not of scope `full`; a group outside 0
    to G − 1; p1 above p2, or a rank (up-card included) outside 1 to 10; the natural (1, 10); a
    line not after the last `split` line in `first` order (so a deal given twice is refused too);
    moves other than its `first` line's, each as many times; that line's own order. Added: a
    `split` line for a deal whose `first` line was not read, refused at once, which only a file
    short of `first` lines can have (and the count of `first` lines refuses it anyway). Not
    refused, since B2.1 does not refuse it: a `split` line whose first move no split hand of the
    deal can play, such as `P` on a deal that is not a pair; the hand plays the first legal move.
    Nor a `split` line whose list starts with its `first` line's move and differs after it (B6 item
    12's pair of threes against an ace, both lists starting with `H`): it is read like any other,
    and StrategyTest and LookupsTest each hold one.

30. **The legal moves of D5's split cells, and of a round's split hands.** B8 fixes Montreal's
    legal moves for the cells "whatever the simulator's own rules are set to". *Chosen:* Lookups
    never reads the rules: Stand alone for a split ace, Stand, Hit and Double for a split 2 to
    10, and Split too after `:`. A round's split hands play by the round's own legal moves, so a
    planted `no-das` or `split-aces-hit` changes what they may do, as it should; both reach the
    ranking through the one method, `Strategy.splitFirstMove`. The one two-card 21 a split 2 to 10
    can hold, a ten that draws an ace, stands before any list is read, so B8's "Stand, Hit and
    Double" and A4's "Hit is legal when the total is under 21" give the same cells.

31. **`later` lines in a bundle with no full-play file.** B8 writes them "only when the bundle
    holds a file of scope `full`" and refuses "a line of any other kind, or out of this order".
    The reader refuses `split` lines in such a bundle, but, as since the second session, not
    `later` lines (all their cells are then `-`). *Chosen:* left as it was, so that a bundle
    without `split` lines is read exactly as fe9a91e read it. *For the engine side:* say whether
    the reader is to refuse them too.

32. **An old bundle, played as before** (deviation 1). Not a question; a record of what this
    session could check without a run's output. On two scratch fixture bundles with no `split`
    line (sim/test's BundleFixture bundle with a lookups.tsv of `first` lines, and the same with
    Hi-Lo's full-play fold files in place of its first-decision ones), sim/ at 46d76a5 and at this
    session's commit ran `p3`, `p3 --moving`, `p2` (K = 20, with a plant on, since the fixture's
    made-up states fail D1) and `variants`; every file they wrote was the same byte for byte but
    checks.txt's added D5 line and the summaries' load average. After the review's fixes the same
    runs were made again, by sim/ at 46d76a5 and by the fixed sim/, on copies of the same two
    bundles: of the 54 files each wrote, all were the same byte for byte but checks.txt's added D5
    line (and the p2 log that prints it), the summaries' wall seconds and load average, and the
    output paths in the logs. The check deviation 1 asks for, on part 1's real bundle against
    runs/sim/part1-0e0920d, is still to be run, and the identity table's simulator commit for part
    2 still to be recorded; this session read no run's output.

33. **The columns D2 leaves out** (SHOE_SPEC.md B4's bullet of 19 October 2026). *Chosen:* a
    column is left out when its name is none of the catalog's 39 counts (the 30 entries, the null
    count and the 8 ace variants, B9) and is neither the `playcount` nor the `betcount` of any
    strategy file of the bundle; that is the one test, and nothing else is left out. A column left
    out is not compared: its cells are not read (a line must still have as many fields as the
    header, as before), they are not among the cells counted, and none is a mismatch. Every other
    column is held key for key as before, with item 13's reading of a registered count's
    parameters. D2 still fails when a catalog count or ace variant is missing from the header, when
    a key or chance of a column compared differs, or when no cell was compared, and now also when a
    count a strategy file of the bundle names has no column (below). B4's "the counts of
    the bundle's own strategy files with their betting variants" is read as the names the files'
    `playcount` and `betcount` lines give, so a registered count's betting variant that no file of
    the bundle bets with is left out even when its base's file is there, its bet tags and ace weight
    being in no file.

    In checks.txt the D2 line keeps its form, `D2`, a tab and `C − M of C keys equal to
    keys-sample.tsv's on L lines; M mismatches; catalog counts missing from its header: [...]`,
    with C now the cells of the columns compared and M their mismatches alone, and nothing after
    the list. Right after it comes a second line, always, also when no column was left out. Its
    form, the engine side's reference, is the `sim-checks` block below, which `sim/test/FormatsTest`
    holds the writer to: fields separated by tabs; `D2`; the fixed text and N, the number of
    columns left out, in decimal; then N fields, each a column's name exactly as the header gives
    it, in header order (`NAME…` stands for them; with N = 0 the line ends after the 0). B0 lets no
    field hold a tab, so no name does and the line splits on tabs; a name may hold ", " (nothing in
    B4 forbids it), which is why the names are not written as a Java list, as the missing counts
    are. Then, as before, the notes of the first ten cells that differ. The notes `column NAME: no
    count of the catalog or of the bundle's files has this name`, one a column and capped with the
    cells' notes at ten, are no longer written: they were mismatch notes, and the second line names
    every column (the pilot's look-first checks.txt named 10 of its 21). When the bundle has no keys-sample.tsv,
    `D2	not run: …` stands alone, with no second line, since there is no comparison for it to
    follow. *For the engine side:* confirm the form, and say whether a D2 that did not run should
    have a second line too.

    *What the pilot's line will read.* By d188bca's code, the look-first bundle's D2 line,
    `1386357 of 1386378 keys … on 33009 lines; 21 mismatches`, counted each column left out as one
    mismatch, counted none of its cells among the cells, and wrote cells less mismatches before
    "of". So if the 21 mismatches were the 21 columns, as d188bca's message says, the cells compared
    were 1,386,378 = 33,009 lines × 42 columns (the catalog's 39 and 3 more that a file names), all
    equal, and the new line will read `1386378 of 1386378 keys equal to keys-sample.tsv's on 33009
    lines; 0 mismatches; catalog counts missing from its header: []`, not 1,386,357 of 1,386,357 as
    d188bca's message has it. That is arithmetic on the old code and the one line; this session ran
    nothing on a real bundle.

```sim-checks
D2	columns left out, since no count of the catalog or of the bundle's files has the name: N	NAME…
```

    *Every count the bundle's files name must have its column* (after the clean-room review of
    268b2d0). B4's header lists the registered counts and their own betting variants, and its first
    sentence makes the file hold every count the comparison scores, so a correct export has a column
    for each count a strategy file of the bundle plays or bets with. At 268b2d0 a column of such a
    count that was misnamed (`F9 Test` for a file's `F9 test`) was left out as no count's, and D2
    passed with the bundle's own count, the one the simulator plays and bets with, unchecked; at
    d188bca the same bundle failed D2, the misnamed column counting as a mismatch. (A header with no
    column for it at all passed at both.) *Chosen:* D2 fails when no column of the header is named
    exactly as a file's `playcount` or `betcount`. A catalog count's absence is in the first line's
    list already and is not named again. checks.txt names each such count once, in the order of the
    bundle's strategy files (index.tsv's, by path), `playcount` before `betcount`, in a note of its
    own right after the two D2 lines and before the cells' notes:
    `count NAME, which a strategy file of the bundle plays or bets with: no column of
    keys-sample.tsv's header has this name, so D2 holds none of its keys` (one line, no tab; held
    by sim/test/SimCommandsTest). The first line keeps its form, its mismatches are still the
    cells' alone and nothing follows its list; summary's `D2` reads `failed`. A misnamed column is
    still left out and named on the second line, so a misnaming shows on both. Where every file's
    count has its column, as B4 requires, no note is written and checks.txt is as at 268b2d0.
    *For the engine side:* this session could not look at part 1's, the replay's or the page
    look's bundles (the clean room lets it read none). If one of them has no column for a count its
    files name, D2 now fails on it with that note where at 268b2d0 it passed. The look-first line's
    42 columns compared (the catalog's 39 and 3 a file names) show that three are there in that
    bundle, not whether its files name a fourth. Confirm that the exports write a column for every
    count of their files, or rule that they need not; the ruling goes here.

34. **Bundles without such columns, read as before.** Not a question; a record of what this session
    could check without a run's output. Four scratch fixture bundles were written with sim/test's
    helpers: BundleFixture's with a keys-sample.tsv of the catalog's 39 columns from the simulator's
    own keys and a lookups.tsv of `first` lines; the same with Hi-Lo's full-play fold files and
    lookups.tsv's 1,000 `split` lines; the first with one Hi-Lo key changed; and the first with
    twelve columns no count names (ten after the null count, two at the end). sim/ at d188bca and
    this session's sim/ each ran `p2 --k 20` on each, with and without `--plant s17` (the fixture's
    made-up bets fail D4, so only a plant takes p2 past the checks). On the three bundles without
    such columns, every file the two wrote (checks.txt, and with the plant summary-p2.json,
    swings-p2.tsv and P2's chunk) was the same byte for byte but for checks.txt's added line
    `D2	columns left out, …: 0`, right after the D2 line, and the summary's load average; the changed
    key's note still follows the D2 lines. On the fourth, checks.txt's D2 line went from `924 of 936
    …; 12 mismatches` with ten `column …` notes to `936 of 936 …; 0 mismatches` with the second line
    naming all twelve, and summary-p2.json's `D2` from `failed` to `passed`; nothing else changed
    but the load average. The same check on part 1's and the replay's real bundles, against their
    recorded runs, is still to be made; this session read no run's output but the one file named.
    After the review's fix (item 33's last paragraph), the same eight runs gave every file the same
    byte for byte as 268b2d0's but the load average: the four bundles' files play Hi-Lo, a catalog
    count, so the new rule adds nothing there.
