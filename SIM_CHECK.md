# The simulator check, fixed before its first pilot

This file is the agreement rule for the second program, the independent simulator of
PLAN_COMPUTE.md section 1: the rule PLAN_PAGE.md section 8 says the page links as "committed
before the simulation runs". It fixes every figure the two programs are compared on, the
strategies each part plays, the exact checks, the z tests and the pass rule, the planted bugs,
each part's machine-hour budget, the rule that turns a pilot's speeds and SDs into run sizes and
the SEs that rule implies, and the rules that pick the paths the page draws. So no row, size,
seed or threshold is chosen after a result is seen (PLAN_COMPUTE.md 6.2).

Nothing here has run, and no simulated figure exists yet. The only published numbers this file
uses are a few of report-final's standard errors, as assumptions for section 8's table of the
SEs the rule implies; no exact figure is copied into it.

**What is left to fill in, and only this.** Section 8's two blocks, one a part, each printed by
`java SimCheckPlan fill` from that part's pilot files, one a bundle: its measured speeds, the SD
of every figure, P2's weight dispersion, and the sizes and SEs the rule then gives. And the
bundles' identities in the table below, which are facts to record, not choices (its three commits
were recorded on 14 October 2026, before any pilot), with the two commits deviation 1 adds for
part 2: SHOE_SPEC.md's and the simulator's.

| Identity | Recorded when | Value |
|---|---|---|
| SHOE_SPEC.md's commit (branch site-shoe-spec) | before the first pilot; for part 2's `split` lines, before part 2's pilot | c8d9b5a (c8d9b5ab990a3d2278c109ead692b175b170b4bc), its last commit, made on site-int-spec, which continued site-shoe-spec; merged into site-integration as 2c99a56; the words part 1 was exported and played to. For part 2, also the commit that adds Part B's `split` lines (SHOE_SPEC.md, "Changes to Part B"), recorded here once it is made and before part 2's pilot (deviation 1 below) |
| The simulator's commit (`sim/`) | before each part's pilot | fe9a91e (fe9a91ea88f49b73c86278eec075d2b17e4bd4bd), the last commit touching sim/, made on site-fix-sim; merged into site-integration with sim/ unchanged on 14 October 2026; for part 1, and for part 2 unless sim/ changes. sim/ is to change for part 2: its reader refuses B2.1's new `split` lines as an unknown keyword and lookups.tsv's `split` lines as an unknown line, so the commit that reads them is recorded here before part 2's pilot (deviation 1). Which plays what: fe9a91e made part 1's pilot and main run; the new commit makes part 2's pilot and main run, and both halves of every planted run and of the clean control (section 10), its halves on part 1's bundle included, once it has played part 1's bundle as fe9a91e did (deviation 1) |
| `test/SimVsExactSmallShoeTest.java`'s commit (D3) | before the first pilot | 283c62c (283c62c5a133f518608f27d10249230bd0735126), made on site-fix-engine; merged into site-integration as 32422c1 |
| Part 1's bundle, `runs/sim/export-<sha>/manifest.sha256`, its SHA-256 | before part 1's pilot | 4a424be6640fe3ce3d380eb232fe7659a378c4beb57538597e71d0e5d4826b3a, of runs/sim/export-part1-0e0920d/manifest.sha256, exported on 16 October 2026 by StrategyExport at 0e0920d (round trip passed: 270,026,867 values, largest relative difference 1.1e-14) |
| Part 2's bundle(s), the same | before part 2's pilot | not yet exported |

**How the file is kept true to its code.** Every table and list between `<!-- SimCheckPlan … -->`
markers is printed by `src/SimCheckPlan.java` (`java SimCheckPlan` prints them all), and
`test/SimCheckPlanTest.java` fails if the file differs from that output by one character. The
same class is the registry the simulator's reader checks a run against (section 12), so the list
a reader sees here and the list a run is held to are one list. The class reads no state file and
no result.

**Changes.** Until the first pilot starts, by commits to this file, each saying why. After it,
only section 8 and the identities above. Any other change is a deviation: committed with its
reason before the run it affects, and listed on the page with that commit. Seeds never change.

**Deviations**, after part 1's pilot (16 October 2026):

1. **Split hands' own ranking** (18 October 2026, before part 2's pilot; part 2 only; written while
   Justin slept, awaiting his yes, and nothing runs under it until he gives it). A
   full-play strategy plays the dealt hand by the play fitted from its last round's values, and
   values its splits under the ranking of the play it kept, the one that round played: the last
   pass's play when the fit settled, the best-scoring pass's when it stopped at its last allowed
   pass (SHOE_SPEC.md B2.4, "the kept play"). The two can rank a deal differently. A strategy
   file held one ranking, so the export refused 9 of the 15 full-play rows of c06c061's replay and
   the page look's F50 and F52 (INTEGRATION.md open item 1). SHOE_SPEC.md Part B now gives split
   hands their own ranking where it differs (`split` lines, B2.1 and B2.4), the same in the
   moving-count variant of section 9 at its moving key's group, and lookups.tsv every split
   hand's first decision in every group (B8), and D5 (section 5) holds the simulator's answers to
   those cells. What changes: Part B's words, D5's list of cells, the simulator (sim/ reads the
   new lines), and the two commits the identity table records for part 2. What does not: every
   figure, test, seed, size rule and threshold of this file; the engine's figures, which were
   always computed with these split hands; and all of part 1. Part 1's bundle holds no file of
   scope `full`, so it has no `split` line and its lookups.tsv none; every byte of it and its
   recorded SHA-256 stand, and part 1's pilot, section 8 block and run stand as made with
   fe9a91e.

   **Which simulator plays what.** The planted runs and the clean control come after part 2's
   main run, and their halves on part 1's bundle are part 2's fixed extras (section 10), so the
   simulator commit that reads the new lines makes them all, both halves; a planted half on part
   1's bundle is held to part 2's commit, not to fe9a91e. That bundle has none of the new lines,
   so the new commit must play it exactly as fe9a91e did, and this is checked before part 2's
   pilot, on part 1's bundle with no switch on, against part 1's main run
   (runs/sim/part1-0e0920d): D1, D2, D4 and D5 give the same counts with 0 mismatches and no
   `split` cell; and P3's first chunk and P2's first chunk, at part 1's sizes, equal that run's
   p3/p3-00000.txt and p2/p2-00000.txt in every line but the `sim` line (D6 makes the sums
   exact). A difference stops
   part 2 there until it is explained and fixed, in a commit that says what it fixed.

## 1. Words used here

- **Figure**: one quantity both programs estimate, in % of a unit a round: a strategy, at one
  penetration, under one bet plan (per round watched for the B and X plans of PLAN_COMPUTE.md
  3.1). The engine's value is the published one, `Entry.estimate[p][metric]` in the file the
  figure table names.
- **Strategy and instance.** A strategy is a count, a play mode and a penetration. Its fitted
  versions are two, one a fold, each fitted on one half of the shoes and scored by the engine on
  the other; each is an **instance**. The chart, fitted on nothing, is one instance at every
  penetration.
- **Test**: one figure compared by one statistical check, P3 (fresh shoes) or P2 (the main
  file's own states). A test has a z and passes or fails; a figure has one or two tests.
- **Exact check**: one of D0 to D6, which must match to the bit or the card, or within the
  1e-12 it states (D0 and D3).
- **Part 1 and part 2**: the simulator's two runs (PLAN_COMPUTE.md 6.2). Part 1, before the
  replay of c06c061: the chart, the null count and the catalog's first-decision rows. Part 2,
  after the replay and the page look: full play, the wide plans, and the registered counts F1
  and F2. The pass rule applies to the union.
- **Mode A**, the comparison mode (PLAN_COMPUTE.md 1.5): every round start is played by every
  instance on the same card order, and the chart's play decides which cards leave the shoe, as
  in the exact states. **Mode B**: each instance's own play takes the cards.
- **σ_eff**: the SD a round that scales with time, from whole shoes (PLAN_COMPUTE.md 2.3, item 3).

## 2. The figures

PLAN_COMPUTE.md 1.3's ten rows, each figure listed once: its row, the strategy, the penetration,
the bets, the part that plays it, its tests, and where the engine's figure is read.
`c06c061` is runs/full-index/full-c06c061/results.ser; `replay` is the wide.ser the replay of
PLAN_COMPUTE.md 6.1 writes; `step 7` is runs/report-ceiling-97bf834.ser; `step 8` is step 8's
results.ser. A metric such as `same(8)` is SystemScorer's index of that name; FLAT is the flat EV
with the fitted play and insurance, except for the chart, whose flat row has no insurance. The
three wide plans are PLAN_COMPUTE.md 3.1's, with 3.2's bet convention. P16, same schedule: the
bets one unit per 0.25% of true advantage would place, from 1 to 16 units, handed out in the
count's own order. B0,8, back-counting, same schedule: the same, 1 to 8 units, on rounds with a
true advantage of 0 or more and nothing below, handed out the same way, so every count sits out
the same share of rounds and picks which by its own order (the block straddling the threshold
played at random, 3.2). X-1,8, own rule: each key's own bet, 1 to 8 units on its fitted
advantage, until a round start of the shoe has a key whose fitted advantage is under -1%; from
that round on, nothing until the shuffle.

The counts, short name to the entry that holds them:

<!-- SimCheckPlan counts -->
| Count here | Its entry in the results files |
|---|---|
| chart | Chart, no count, flat (a reference row, fitted on nothing) |
| null count | Null count (no count) |
| Hi-Lo | Hi-Lo = Hi-Lo Lite |
| KO | KO (Knock-Out) = REKO (Ridiculously Easy Knock-Out) |
| Red Seven | Red Seven (Red 7) |
| Zen | Zen Count |
| Hi-Opt II + ace | Hi-Opt II + ace side count |
| Victor APC + ace | Victor Advanced Point Count (Victor APC) + ace side count |
| F1 | F1 at 1-8 (f1-8-vidami-ar) |
| F2 | F2 at 1-8, play vector (f2-pub-victor-apc) + betting vector |
<!-- /SimCheckPlan counts -->

Every figure carries a P3 test. The figures at 80% also carry a P2 test: P2 re-deals the main
file's valued states and plays each with the one fold instance whose test set holds it, and the
plan runs it at 80% only, where every valued state is in play.

<!-- SimCheckPlan figures -->
| Figure | Row | Count | Play | Pen. | Bets | Part | Tests | Engine's figure |
|---|---|---|---|---|---|---|---|---|
| F01 | 1 | chart | chart | 70% | flat | 1 | P3 | step 7: FLAT |
| F02 | 1 | chart | chart | 75% | flat | 1 | P3 | step 7: FLAT |
| F03 | 1 | chart | chart | 80% | flat | 1 | P3, P2 | step 7: FLAT |
| F04 | 2 | null count | first decision | 80% | flat | 1 | P3, P2 | c06c061: FLAT |
| F05 | 3 | Hi-Lo | first decision | 70% | flat | 1 | P3 | c06c061: FLAT |
| F06 | 3 | Hi-Lo | first decision | 70% | 1-2 | 1 | P3 | c06c061: same(2) |
| F07 | 3 | Hi-Lo | first decision | 70% | 1-4 | 1 | P3 | c06c061: same(4) |
| F08 | 3 | Hi-Lo | first decision | 70% | 1-8 | 1 | P3 | c06c061: same(8) |
| F09 | 3 | Hi-Lo | first decision | 70% | own 1-8 | 1 | P3 | c06c061: own(8) |
| F10 | 3 | Hi-Lo | first decision | 75% | flat | 1 | P3 | c06c061: FLAT |
| F11 | 3 | Hi-Lo | first decision | 75% | 1-2 | 1 | P3 | c06c061: same(2) |
| F12 | 3 | Hi-Lo | first decision | 75% | 1-4 | 1 | P3 | c06c061: same(4) |
| F13 | 3 | Hi-Lo | first decision | 75% | 1-8 | 1 | P3 | c06c061: same(8) |
| F14 | 3 | Hi-Lo | first decision | 75% | own 1-8 | 1 | P3 | c06c061: own(8) |
| F15 | 3 | Hi-Lo | first decision | 80% | flat | 1 | P3, P2 | c06c061: FLAT |
| F16 | 3 | Hi-Lo | first decision | 80% | 1-2 | 1 | P3, P2 | c06c061: same(2) |
| F17 | 3 | Hi-Lo | first decision | 80% | 1-4 | 1 | P3, P2 | c06c061: same(4) |
| F18 | 3 | Hi-Lo | first decision | 80% | 1-8 | 1 | P3, P2 | c06c061: same(8) |
| F19 | 3 | Hi-Lo | first decision | 80% | own 1-8 | 1 | P3, P2 | c06c061: own(8) |
| F20 | 4 | Hi-Lo | betting only | 80% | 1-8 | 1 | P3, P2 | c06c061: same(8) |
| F21 | 5 | KO | first decision | 80% | 1-8 | 1 | P3, P2 | c06c061: same(8) |
| F22 | 5 | Red Seven | first decision | 80% | 1-8 | 1 | P3, P2 | c06c061: same(8) |
| F23 | 5 | Hi-Opt II + ace | first decision | 80% | 1-8 | 1 | P3, P2 | c06c061: same(8) |
| F24 | 6 | Victor APC + ace | first decision | 80% | flat | 1 | P3, P2 | c06c061: FLAT |
| F25 | 6 | Victor APC + ace | first decision | 80% | 1-8 | 1 | P3, P2 | c06c061: same(8) |
| F26 | 7 | Hi-Lo | full play | 80% | flat | 2 | P3, P2 | c06c061: FLAT |
| F27 | 7 | Hi-Lo | full play | 80% | 1-8 | 2 | P3, P2 | c06c061: same(8) |
| F28 | 7 | Victor APC + ace | full play | 80% | flat | 2 | P3, P2 | c06c061: FLAT |
| F29 | 7 | Victor APC + ace | full play | 80% | 1-8 | 2 | P3, P2 | c06c061: same(8) |
| F30 | 8 | Hi-Lo | full play | 70% | 1-8 | 2 | P3 | c06c061: same(8) |
| F31 | 8 | KO | full play | 70% | 1-8 | 2 | P3 | c06c061: same(8) |
| F32 | 8 | Zen | full play | 70% | 1-8 | 2 | P3 | c06c061: same(8) |
| F33 | 8 | Hi-Opt II + ace | full play | 70% | 1-8 | 2 | P3 | c06c061: same(8) |
| F34 | 8 | Victor APC + ace | full play | 70% | 1-8 | 2 | P3 | c06c061: same(8) |
| F35 | 8 | Hi-Lo | full play | 75% | 1-8 | 2 | P3 | c06c061: same(8) |
| F36 | 8 | KO | full play | 75% | 1-8 | 2 | P3 | c06c061: same(8) |
| F37 | 8 | Zen | full play | 75% | 1-8 | 2 | P3 | c06c061: same(8) |
| F38 | 8 | Hi-Opt II + ace | full play | 75% | 1-8 | 2 | P3 | c06c061: same(8) |
| F39 | 8 | Victor APC + ace | full play | 75% | 1-8 | 2 | P3 | c06c061: same(8) |
| F40 | 8 | KO | full play | 80% | 1-8 | 2 | P3, P2 | c06c061: same(8) |
| F41 | 8 | Zen | full play | 80% | 1-8 | 2 | P3, P2 | c06c061: same(8) |
| F42 | 8 | Hi-Opt II + ace | full play | 80% | 1-8 | 2 | P3, P2 | c06c061: same(8) |
| F43 | 9 | Hi-Lo | full play | 80% | P16 | 2 | P3, P2 | replay: P16, same schedule |
| F44 | 9 | Hi-Lo | full play | 80% | B0,8 | 2 | P3, P2 | replay: B0,8, same schedule, per round watched |
| F45 | 9 | Hi-Lo | full play | 80% | X-1,8 | 2 | P3, P2 | replay: X-1,8, own rule, per round watched |
| F46 | 9 | Victor APC + ace | full play | 80% | P16 | 2 | P3, P2 | replay: P16, same schedule |
| F47 | 9 | Victor APC + ace | full play | 80% | B0,8 | 2 | P3, P2 | replay: B0,8, same schedule, per round watched |
| F48 | 9 | Victor APC + ace | full play | 80% | X-1,8 | 2 | P3, P2 | replay: X-1,8, own rule, per round watched |
| F49 | 10 | F1 | first decision | 80% | 1-8 | 2 | P3, P2 | step 7: same(8) |
| F50 | 10 | F1 | full play | 80% | 1-8 | 2 | P3, P2 | step 8: same(8) |
| F51 | 10 | F2 | first decision | 80% | 1-8 | 2 | P3, P2 | step 7: same(8) |
| F52 | 10 | F2 | full play | 80% | 1-8 | 2 | P3, P2 | step 8: same(8) |
<!-- /SimCheckPlan figures -->

The chart's three figures are read from step 7's file: c06c061's results.ser holds only the
counts' Results, and the chart's flat row is a reference row (SystemScorer.references) that the
ceiling report of step 7 scored on the same states and resamples. This was "c06c061" until 12
October 2026, before any pilot; RiskReport already read F03 from step 7.

The same list as the simulator's files name it (SHOE_SPEC.md Part C): the id that keys
swings.tsv, records-FIG.tsv and fan-FIG.tsv, the part, the row and penetration as the bundle
names them, the figure as exact.tsv names it (B5), the tests, the engine file, and whether swings
(section 9) and the fan and per-shoe records (section 11) are kept for it. It is a fenced block
the simulator may read as it stands.

<!-- SimCheckPlan registry -->
```sim-registry
fig	part	row	pen	figure	tests	engine	swings	paths
F01	1	Chart, no count, flat	70	flat	P3	step 7	0	0
F02	1	Chart, no count, flat	75	flat	P3	step 7	0	0
F03	1	Chart, no count, flat	80	flat	P3,P2	step 7	1	0
F04	1	Null count (no count)	80	flat	P3,P2	c06c061	0	0
F05	1	Hi-Lo = Hi-Lo Lite	70	flat	P3	c06c061	0	0
F06	1	Hi-Lo = Hi-Lo Lite	70	ev:same-2	P3	c06c061	0	0
F07	1	Hi-Lo = Hi-Lo Lite	70	ev:same-4	P3	c06c061	0	0
F08	1	Hi-Lo = Hi-Lo Lite	70	ev:same-8	P3	c06c061	0	0
F09	1	Hi-Lo = Hi-Lo Lite	70	ev:own-8	P3	c06c061	0	0
F10	1	Hi-Lo = Hi-Lo Lite	75	flat	P3	c06c061	0	0
F11	1	Hi-Lo = Hi-Lo Lite	75	ev:same-2	P3	c06c061	0	0
F12	1	Hi-Lo = Hi-Lo Lite	75	ev:same-4	P3	c06c061	0	0
F13	1	Hi-Lo = Hi-Lo Lite	75	ev:same-8	P3	c06c061	0	0
F14	1	Hi-Lo = Hi-Lo Lite	75	ev:own-8	P3	c06c061	0	0
F15	1	Hi-Lo = Hi-Lo Lite	80	flat	P3,P2	c06c061	0	0
F16	1	Hi-Lo = Hi-Lo Lite	80	ev:same-2	P3,P2	c06c061	0	0
F17	1	Hi-Lo = Hi-Lo Lite	80	ev:same-4	P3,P2	c06c061	0	0
F18	1	Hi-Lo = Hi-Lo Lite	80	ev:same-8	P3,P2	c06c061	1	0
F19	1	Hi-Lo = Hi-Lo Lite	80	ev:own-8	P3,P2	c06c061	0	0
F20	1	Hi-Lo = Hi-Lo Lite, betting only	80	ev:same-8	P3,P2	c06c061	0	0
F21	1	KO (Knock-Out) = REKO (Ridiculously Easy Knock-Out)	80	ev:same-8	P3,P2	c06c061	0	0
F22	1	Red Seven (Red 7)	80	ev:same-8	P3,P2	c06c061	0	0
F23	1	Hi-Opt II + ace side count	80	ev:same-8	P3,P2	c06c061	0	0
F24	1	Victor Advanced Point Count (Victor APC) + ace side count	80	flat	P3,P2	c06c061	0	0
F25	1	Victor Advanced Point Count (Victor APC) + ace side count	80	ev:same-8	P3,P2	c06c061	0	0
F26	2	Hi-Lo = Hi-Lo Lite, full play	80	flat	P3,P2	c06c061	0	0
F27	2	Hi-Lo = Hi-Lo Lite, full play	80	ev:same-8	P3,P2	c06c061	1	1
F28	2	Victor Advanced Point Count (Victor APC) + ace side count, full play	80	flat	P3,P2	c06c061	0	0
F29	2	Victor Advanced Point Count (Victor APC) + ace side count, full play	80	ev:same-8	P3,P2	c06c061	1	1
F30	2	Hi-Lo = Hi-Lo Lite, full play	70	ev:same-8	P3	c06c061	1	0
F31	2	KO (Knock-Out) = REKO (Ridiculously Easy Knock-Out), full play	70	ev:same-8	P3	c06c061	1	0
F32	2	Zen Count, full play	70	ev:same-8	P3	c06c061	1	0
F33	2	Hi-Opt II + ace side count, full play	70	ev:same-8	P3	c06c061	1	0
F34	2	Victor Advanced Point Count (Victor APC) + ace side count, full play	70	ev:same-8	P3	c06c061	1	0
F35	2	Hi-Lo = Hi-Lo Lite, full play	75	ev:same-8	P3	c06c061	1	0
F36	2	KO (Knock-Out) = REKO (Ridiculously Easy Knock-Out), full play	75	ev:same-8	P3	c06c061	1	0
F37	2	Zen Count, full play	75	ev:same-8	P3	c06c061	1	0
F38	2	Hi-Opt II + ace side count, full play	75	ev:same-8	P3	c06c061	1	0
F39	2	Victor Advanced Point Count (Victor APC) + ace side count, full play	75	ev:same-8	P3	c06c061	1	0
F40	2	KO (Knock-Out) = REKO (Ridiculously Easy Knock-Out), full play	80	ev:same-8	P3,P2	c06c061	1	0
F41	2	Zen Count, full play	80	ev:same-8	P3,P2	c06c061	1	0
F42	2	Hi-Opt II + ace side count, full play	80	ev:same-8	P3,P2	c06c061	1	0
F43	2	Hi-Lo = Hi-Lo Lite, full play	80	ev:P16	P3,P2	replay	1	0
F44	2	Hi-Lo = Hi-Lo Lite, full play	80	ev:B0,8	P3,P2	replay	1	0
F45	2	Hi-Lo = Hi-Lo Lite, full play	80	ev:X-1.0,8	P3,P2	replay	1	0
F46	2	Victor Advanced Point Count (Victor APC) + ace side count, full play	80	ev:P16	P3,P2	replay	1	0
F47	2	Victor Advanced Point Count (Victor APC) + ace side count, full play	80	ev:B0,8	P3,P2	replay	1	0
F48	2	Victor Advanced Point Count (Victor APC) + ace side count, full play	80	ev:X-1.0,8	P3,P2	replay	1	0
F49	2	F1 at 1-8 (f1-8-vidami-ar)	80	ev:same-8	P3,P2	step 7	0	0
F50	2	F1 at 1-8 (f1-8-vidami-ar), full play	80	ev:same-8	P3,P2	step 8	1	0
F51	2	F2 at 1-8, play vector (f2-pub-victor-apc) + betting vector	80	ev:same-8	P3,P2	step 7	0	0
F52	2	F2 at 1-8, play vector (f2-pub-victor-apc) + betting vector, full play	80	ev:same-8	P3,P2	step 8	1	0
```
<!-- /SimCheckPlan registry -->

F1 and F2 are the registered counts at 1-8 (custom-counts.json, custom-counts-fullplay.json),
F2 scored as registered, with its betting vector. Their strategies reach the simulator only
through the page look's export (PLAN_COMPUTE.md 6.3), which comes after part 1, so all four of
their figures are in part 2. Rows 7 to 10 serve the swings, the spreads and the ceiling as well
as the check; a test of theirs that fails holds back those figures too (PLAN_COMPUTE.md 2.4,
check 3).

## 3. The strategies each part plays

Each line is one strategy; its instances are its two fold strategies (the chart: one). A
first-decision instance shares its playout with every other that makes the same first move on
the same cards; a full-play instance plays its own, looking its table up at every later
decision; Hi-Lo betting only plays the chart's playout with its own insurance and bets.

<!-- SimCheckPlan strategies -->
| Count | Play | Pen. | Instances | Part | Playout | Figures | Also played by |
|---|---|---|---|---|---|---|---|
| chart | chart | 70/75/80% | 1 | 1 | the chart's walk itself | F01, F02, F03 | planted bugs |
| null count | first decision | 80% | 2 | 1 | shared by first move | F04 |  |
| Hi-Lo | first decision | 70% | 2 | 1 | shared by first move | F05, F06, F07, F08, F09 |  |
| Hi-Lo | first decision | 75% | 2 | 1 | shared by first move | F10, F11, F12, F13, F14 |  |
| Hi-Lo | first decision | 80% | 2 | 1 | shared by first move | F15, F16, F17, F18, F19 | planted bugs |
| Hi-Lo | betting only | 80% | 2, no playout | 1 | the chart's | F20 |  |
| KO | first decision | 80% | 2 | 1 | shared by first move | F21 |  |
| Red Seven | first decision | 80% | 2 | 1 | shared by first move | F22 |  |
| Hi-Opt II + ace | first decision | 80% | 2 | 1 | shared by first move | F23 |  |
| Victor APC + ace | first decision | 80% | 2 | 1 | shared by first move | F24, F25 |  |
| Hi-Lo | full play | 80% | 2 | 2 | its own | F26, F27, F43, F44, F45 | mode B, moving count; planted bugs |
| Victor APC + ace | full play | 80% | 2 | 2 | its own | F28, F29, F46, F47, F48 | mode B, moving count; planted bugs |
| Hi-Lo | full play | 70% | 2 | 2 | its own | F30 |  |
| KO | full play | 70% | 2 | 2 | its own | F31 |  |
| Zen | full play | 70% | 2 | 2 | its own | F32 |  |
| Hi-Opt II + ace | full play | 70% | 2 | 2 | its own | F33 |  |
| Victor APC + ace | full play | 70% | 2 | 2 | its own | F34 |  |
| Hi-Lo | full play | 75% | 2 | 2 | its own | F35 |  |
| KO | full play | 75% | 2 | 2 | its own | F36 |  |
| Zen | full play | 75% | 2 | 2 | its own | F37 |  |
| Hi-Opt II + ace | full play | 75% | 2 | 2 | its own | F38 |  |
| Victor APC + ace | full play | 75% | 2 | 2 | its own | F39 |  |
| KO | full play | 80% | 2 | 2 | its own | F40 |  |
| Zen | full play | 80% | 2 | 2 | its own | F41 |  |
| Hi-Opt II + ace | full play | 80% | 2 | 2 | its own | F42 |  |
| F1 | first decision | 80% | 2 | 2 | shared by first move | F49 |  |
| F1 | full play | 80% | 2 | 2 | its own | F50 |  |
| F2 | first decision | 80% | 2 | 2 | shared by first move | F51 |  |
| F2 | full play | 80% | 2 | 2 | its own | F52 |  |
<!-- /SimCheckPlan strategies -->

<!-- SimCheckPlan totals -->
- 52 figures; 82 z tests: 52 by P3 (every figure), 30 by P2 (the figures at 80%). The cap is 100.
- Part 1: 25 figures, 38 z tests (25 P3, 13 P2); 17 instances with a playout (17 at first decisions or the chart, 0 at full play), and Hi-Lo betting only's 2 fold strategies on the chart's playout.
- Part 2: 27 figures, 44 z tests (27 P3, 17 P2); 38 instances with a playout (4 at first decisions or the chart, 34 at full play).
- In all: 55 instances with a playout, 21 at first decisions or the chart and 34 at full play.
- Each planted-bug run is scored on 7 figures (F03, F15, F18, F26, F27, F28, F29), by P3 and P2: 14 z tests a run, in two halves (section 10): F03, F15, F18 on part 1's bundle, F26, F27, F28, F29 on part 2's.
- Swings are kept (section 9) for 25 figures: F03, F18, F27, F29, F30, F31, F32, F33, F34, F35, F36, F37, F38, F39, F40, F41, F42, F43, F44, F45, F46, F47, F48, F50, F52.
<!-- /SimCheckPlan totals -->

This corrects one line of PLAN_COMPUTE.md 6.2, which puts "21 instances and the chart" in part
1. The 21 first-decision instances include F1's and F2's four, and those exist only after the
page look, so part 1 plays 17 (the chart and 16 fitted) and part 2 plays 38 (34 at full play,
F1's and F2's four at first decisions). The 55 in all and the two parts' budgets are unchanged.

## 4. How the runs deal

- **P3, fresh shoes.** Seed 20261008. Shoe h is shuffled by its own xoshiro256** generator,
  seeded by SplitMix64 from the seed and h, so shoe h is the same shoe in part 1, part 2, both
  pilots, mode B, the moving count, the planted runs and the paths. Mode A walks each shoe with
  the chart down to the 80% cut; a figure at 70% or 75% is scored on the rounds of that walk
  that start before 291 or 312 cards are dealt, as the exact states are.
- **P2, the main file's states.** Seed 20261009. Each valued state of states.bin, in its order
  there, gets its own generator from the seed and its index; each re-deal is a fresh uniform
  order of the state's remaining cards, and one round is dealt from it. Every instance plays the
  same orders.
- **Coins.** A B plan's threshold block is played at random (PLAN_COMPUTE.md 3.2), in P3 a coin
  a round, in P2 a coin a re-deal. Coins come from a second generator a shoe or state, seeded
  from the same seed and index with a stream tag of their own, so the cards an instance sees
  never depend on how many coins were drawn.
- The exact derivations (the SplitMix64 steps, the stream tags) are the simulator's, written in
  its source, printed in every summary, and fixed by its commit before the first pilot. Any
  derivation meeting the rules above is the registered one; none is changed after the pilot.
- **Chunks.** P3 in chunks of 1,000,000 shoes, P2 in chunks of 1,000 states. Each chunk's sums
  go to a file forced to disk and renamed, headed by the simulator's commit, the bundle's
  SHA-256, the seed, the chunk's size and K; a resume refuses a mismatch. Chunks are summed in
  index order, so a run's result is the same to the bit on any number of threads (D6).
- **The X plans' exit** needs the shoe's lowest bet key over every round start so far. In P3 the
  simulator keeps it on its walk; in P2 it takes it from D1's walk of the state's own shoe.
  Neither reads the engine's minKey; D4 compares the bets that follow from it.

## 5. The exact checks

Each must match exactly, or within the 1e-12 it states; one that does not fails the whole
check, whatever the z tests say.
They run before each part's pilot, and a failure stops the part there.

- **D0, the round trip** (in the exporter, engine side, PLAN_COMPUTE.md 1.2). The bundle, read
  back and scored by SystemScorer, gives every exported figure equal to its source file's
  `Entry.estimate[p][metric]` and `shoeSums` within 1e-12 relative. No simulator run starts on a
  bundle that fails it.
- **D1, the walk and the sampling.** The simulator deals the 60,000 shoes of seed 20260928 with
  its own copy of SHOE_SPEC.md's shuffle and walks them with the chart: 3,197,853, 3,426,592 and
  3,644,432 rounds at the three cuts. At every round start it recomputes u and q and makes the
  stream-1 draw, so it chooses the valued set itself; that set must be exactly the 60,743 states
  of states.bin, each equal card for card (shoe, round, depth, the 26 tallies dealt) and in q to
  the bit.
- **D2, the keys.** With its own parser of counting-systems.json, the simulator's bet key and
  play keys equal keys-sample.tsv on every line: all 31 counts and 8 ace variants, simulated or
  not, at the 2,000 sampled states and their sampled deals, with the colour and face parts.
- **D3, tiny shoes.** `test/SimVsExactSmallShoeTest.java`, engine side, compiling both programs:
  on the cases it fixes when it is committed (its commit recorded above, before the first pilot),
  the simulator's round averaged over every order of each 9-card shoe equals ExactRound for every
  first move and policy within 1e-12.
- **D4, keys and bets at every state.** For every exported fold strategy, at every valued state
  of its penetration and under every plan it plays, the simulator's bet key and bet equal those
  states.bin holds for it, to the bit (for a B plan's threshold block, its share played and its
  fixed bet). So the bets' first and second moments over the main file's states, E[b] and
  E[b^2], are the engine's exactly: check 2 of PLAN_COMPUTE.md 2.4 done on the states without
  noise, its z test on fresh shoes being then a report (section 9).
- **D5, the lookups.** At the 31,000 (state, deal) pairs of keys-sample.tsv (10 deals at each of
  2,000 states, and all 550 deals at the first 20), for every exported instance whose
  penetration holds the state: the insurance decision and the first move equal the engine's.
  For the full-play instances, also at the first deal of the first 200 sampled states: after one
  hit card of each rank left in the state, the later key, the cell it falls in and the move.
  And for the full-play instances, every split hand's first decision in every group of the file:
  each split rank, second card and up-card, and, where a split 2 to 10 draws its own rank, with a
  resplit allowed and not, under Montreal's legal moves whatever switch is on (lookups.tsv's
  `split` lines, SHOE_SPEC.md B8; deviation 1, part 2 only). Those cells are counted apart, and
  D5 fails if a bundle with a full-play file has none.
  The engine's answers are written by the exporter into the bundle as `lookups.tsv`, so the
  check reruns from the bundle alone.
- **D6, determinism.** A test in `sim/` runs three small chunks on 1 and on 6 threads, and once
  killed after the first chunk and resumed, and requires every sum to be the same to the bit.

## 6. The z tests

X is a round's result for a one-unit bet, insurance included; b is the bet the figure's plan
places on the round (0 when it sits out). E is the engine's figure, `Entry.estimate[p][metric]`
of the source file. Every sum below is a double summed in a fixed order.

**P3, fresh shoes** (every figure). For each fold instance f and shoe h, Y_hf is the sum of b·X
over the shoe's rounds at the figure's penetration, and N_h the number of those rounds, every
round dealt, so that B and X plans are per round watched. The folds are pooled shoe by shoe
with their test weights from exact.tsv, W_f = (sum of 1/q over fold f's test states) / (sum
over all valued states), at that penetration: Y_h = W_1·Y_h1 + W_2·Y_h2 (the chart, one
instance, is its own Y_h). Over S shoes:

- the simulated figure, μ = ΣY_h / ΣN_h;
- its SE, the ratio estimator's with shoes as the independent units:
  SE_sim² = S/(S−1) · Σ(Y_h − μ·N_h)² / (ΣN_h)², so SE_sim = σ_eff / √(ΣN_h) with σ_eff as
  PLAN_COMPUTE.md 2.3 defines it;
- the exact side's SE with the fit held fixed, from exact.tsv's per-shoe sums over the 60,000
  shoes of the main file, A_h = Σ w·b·RV and B_h = Σ w over shoe h's valued states in its test
  fold at that penetration: SE_exact² = 60000/59999 · Σ(A_h − E·B_h)² / (ΣB_h)²;
- z = (μ − E) / √(SE_sim² + SE_exact²).

Two things this rests on, named so a failure can be read. The pooling weights are constants to
the simulator; their own variation is inside SE_exact, which is taken over the same shoes. And a
fold's same-schedule bets are set from its own test fold's distribution of true advantages
(SystemScorer.bets), never from its outcomes; P3 treats the exported bet map as fixed.

**P2, the main file's states** (the figures at 80%). For each valued state s, with w_s = 1/q_s
and the instance of the fold whose test set holds s, x̄_s is the mean of b·X over its K
re-deals and v_s their sample variance. Then:

- the simulated figure, μ = Σ w_s·x̄_s / Σ w_s, over the same states and weights as E;
- SE² = Σ w_s²·v_s / (K·(Σ w_s)²), the simulator's alone: the two sides share the states, so the
  luck of which states came up cancels (PLAN_COMPUTE.md 1.6);
- z = (μ − E) / SE.

P2 tests the round values, keys, lookups, insurance and bets end to end, but not the weights 1/q,
which D1 checks exactly; P3 adds the sampling: the weights, how often each state comes up, and
the 70% and 75% subsets.

**Who computes them.** The simulator computes every μ, SE and z and writes them with the sums
above. The engine-side reader (PLAN_COMPUTE.md 7.2, row 10) computes each z again from those sums
and the bundle, and refuses a summary where any differs by more than 1e-9 relative, or that lacks
a registered test, or puts any other test into the pass family.

## 7. The pass rule, and what it means

**Pass**: D0 to D6 all match exactly, and every registered test has |z| < 3.5, over both parts
together. A test with 2.5 ≤ |z| < 3.5 is **flagged**: shown and explained on the page, but not a
fail. The rule is two-sided and is the same for every test.

<!-- SimCheckPlan pass -->
- One unbiased test reaches |z| >= 3.5 with chance 4.653e-04 (two-sided).
- With the 82 registered tests, the chance that at least one fails with no bias anywhere is at most 3.82% (Bonferroni, whatever the tests' dependence); at the cap of 100 it would be 4.65%.
- Flags (2.5 <= |z| < 3.5) expected among 82 unbiased tests: 0.98.
- A bias of 4.78 combined SEs is caught 90% of the time, 5.14 SEs 95% of the time, 3.50 SEs half the time.
<!-- /SimCheckPlan pass -->

So with nothing wrong about one flag is to be expected, and the page says so beside any it
shows. The detection limits are what the page prints for each test: the two multiples above
times that test's achieved combined SE, labelled "a bias this large is caught 90% (95%) of the
time"; a smaller bias may pass. For each test the page also prints the exact figure, the
simulated one, the difference with ±1.96 combined SEs, and z.

**A registered test that cannot run** (for instance F1's and F2's, if the page look never
happens) is neither passed nor failed. The page prints "{n} of 82 registered tests ran" and
names the others with the reason; the bound on a false fail stays the one for 82.

## 8. Sizes, budgets, the pilot and the scaling rule

**Full size and budgets.**

<!-- SimCheckPlan sizes -->
- P3 at full size: seed 20261008, 38 chunks of 1,000,000 shoes, 38,000,000 shoes; about 2.03, 2.17 and 2.31 billion rounds at 70, 75 and 80% (report-final's rounds a shoe, 53.30, 57.11 and 60.74).
- P2 at full size: seed 20261009, K = 120,000 re-deals of each of the 60,743 valued states, 7,289,160,000 re-deals, in 61 chunks of 1,000 states (the last one short).
- Not scaled (part 2): mode B and the moving count each on P3's first 4 chunks (4,000,000 shoes); each planted-bug run, and the clean control beside them, on part 1's bundle and on part 2's (section 10), P3 on the first 1 chunk and P2 at K = 6,000.
- Kept on the way, not extra runs: the per-shoe records (Y, N, m) on P3's first 2 chunks; the 20 fan paths, each 100,000 rounds, the cumulative win written every 100 rounds.
- Budgets: part 1 1.5 machine hours, part 2 4.5, each on 6 threads with the machine otherwise free.
- The pilot: 1% of every component at full size, so P3 on the first 380,000 shoes and P2 at K = 1,200 on every state, and each fixed extra at 1% of its size.
- The smallest run the rule allows, f = 0.5: P3 19 chunks, P2 K = 60,000; every simulated SE then at most 1.414 times its full-size value.
<!-- /SimCheckPlan sizes -->

A budget bounds a part's **main run**: everything after its pilot, that is P3 and P2 and, in
part 2, the fixed extras. The exporter, the D checks and the pilot itself are outside it (the D
checks take seconds, the pilot about 1%). A machine hour is an hour of wall time with the run
holding the machine alone (PLAN_COMPUTE.md 7.2, "What never runs together").

**The pilot is a gate, and blind.** Before each part's main run, its pilot runs 1% of every
component at full size, on the same seeds and threads. It measures and writes only: the setup
time (reading the bundle, the D checks), the wall time of each component, σ_eff of every figure
by P3, and V = Σ w_s²·v_s / (Σ w_s)² of every figure at 80% by P2 (V is what P2's SE needs:
SE = √(V/K)). It prints no simulated figure, no difference from the engine and no z. So the
sizes are set from speeds and spreads alone, and nothing about the main run is chosen after an
agreement has been seen.

**The rule.** With the pilot's times multiplied by 100 to full size, the setup paid once and the
fixed extras paid first:

  f = min(1, (budget − setup − extras) / (P3 + P2 at full size)),

then P3 runs ⌊38·f⌋ chunks and P2 runs K = 1,000·⌊120·f⌋, the same f for every instance and
both checks, each rounded down. Every simulated SE then grows by the square root of the inverse
of the share it runs (P3: 38 over its chunks; P2: 120,000 over K), and the page prints the
achieved SEs and detection limits, not the targets.

**Stops.** The part stops for Justin's decision, instead of running smaller, if f is under 0.5
(SEs more than 1.41 times their full-size values). It stops for a look before the main run if
the pilot's σ_eff of the chart flat at 80% (F03) or of any same-schedule 1-8 figure at 80% is
more than twice, or under half, its assumed value below: a sign of a bug or a wrong assumption,
not of agreement. Either stop, and what was decided, is committed and shown on the page.

**Once set, sizes do not move.** The main run goes to the end at the sizes the rule gave,
however long it takes; an overrun is reported beside the budget, never cut, and no run is
extended. An interrupted run resumes from its chunks.

**The SEs the rule implies**, before any pilot, at the assumed SDs a round (flat 1.16 from the
prototype of PLAN_COMPUTE.md 1.1, 1-8 2.4 its estimate) and the exact sides' SEs report-final
prints (Hi-Lo at 1-8: 0.014 at 70%, 0.018 at 80%; the chart's flat interval at 80% as a
half-width over 1.96). P2's rows assume the weights 1/q equal (D = 1); the pilot measures V,
which carries their real dispersion. These are not on the page; the page has section 8's
filled blocks.

<!-- SimCheckPlan implied -->
| f | P3 chunks, P2 K | Check | Assumed SD a round | Assumed exact-side SE | Simulated SE | Combined SE | Caught 90% | Caught 95% |
|---|---|---|---|---|---|---|---|---|
| 1.00 | 38, 120,000 | P3, chart flat, 80% | 1.16 | 0.0056% | 0.0024% | 0.0061% | 0.0292% | 0.0314% |
| 1.00 | 38, 120,000 | P3, 1-8, 80% | 2.40 | 0.0180% | 0.0050% | 0.0187% | 0.0893% | 0.0961% |
| 1.00 | 38, 120,000 | P3, 1-8, 70% | 2.40 | 0.0140% | 0.0053% | 0.0150% | 0.0716% | 0.0771% |
| 1.00 | 38, 120,000 | P2, chart flat, 80%, D = 1 | 1.16 | none | 0.0014% | 0.0014% | 0.0065% | 0.0070% |
| 1.00 | 38, 120,000 | P2, 1-8, 80%, D = 1 | 2.40 | none | 0.0028% | 0.0028% | 0.0134% | 0.0145% |
| 0.75 | 28, 90,000 | P3, chart flat, 80% | 1.16 | 0.0056% | 0.0028% | 0.0063% | 0.0300% | 0.0323% |
| 0.75 | 28, 90,000 | P3, 1-8, 80% | 2.40 | 0.0180% | 0.0058% | 0.0189% | 0.0905% | 0.0973% |
| 0.75 | 28, 90,000 | P3, 1-8, 70% | 2.40 | 0.0140% | 0.0062% | 0.0153% | 0.0732% | 0.0788% |
| 0.75 | 28, 90,000 | P2, chart flat, 80%, D = 1 | 1.16 | none | 0.0016% | 0.0016% | 0.0075% | 0.0081% |
| 0.75 | 28, 90,000 | P2, 1-8, 80%, D = 1 | 2.40 | none | 0.0032% | 0.0032% | 0.0155% | 0.0167% |
| 0.50 | 19, 60,000 | P3, chart flat, 80% | 1.16 | 0.0056% | 0.0034% | 0.0066% | 0.0314% | 0.0338% |
| 0.50 | 19, 60,000 | P3, 1-8, 80% | 2.40 | 0.0180% | 0.0071% | 0.0193% | 0.0925% | 0.0995% |
| 0.50 | 19, 60,000 | P3, 1-8, 70% | 2.40 | 0.0140% | 0.0075% | 0.0159% | 0.0760% | 0.0818% |
| 0.50 | 19, 60,000 | P2, chart flat, 80%, D = 1 | 1.16 | none | 0.0019% | 0.0019% | 0.0092% | 0.0099% |
| 0.50 | 19, 60,000 | P2, 1-8, 80%, D = 1 | 2.40 | none | 0.0040% | 0.0040% | 0.0190% | 0.0205% |
<!-- /SimCheckPlan implied -->

**Filling in.** The pilot runs on one bundle and writes `sim-pilot-partN.tsv`: tab-separated
lines `part N`; `setup SECONDS`; `time P3 SECONDS`, `time P2 SECONDS` and `time extra SECONDS`
(each at 1%; extra is 0 in part 1); `sd FIGURE σ_eff` for every figure of the part its bundle
holds; `v FIGURE V` for every one of them at 80%; and `exact FIGURE SE_exact` for every one, from
exact.tsv. Part 1 is one bundle. Part 2's figures are in three (the replay's, F26 to F48, and the
page look's two, F49 and F51, F50 and F52), so its pilot runs once on each, and once on part 1's
bundle, where it times the planted runs' first halves (section 10) and holds no figure of part 2.
Each file is committed beside this one as `sim-pilot-partN.tsv` or `sim-pilot-partN-NAME.tsv`, and
`java SimCheckPlan fill FILE ...` takes a part's files together and prints the block below. It adds
the bundles' setups, P3s and P2s, each bundle being a main run of its own, and the fixed extras
only of the bundles the main run plays them on: the one holding F26 to F29 (mode B, the moving
count and the planted runs' second halves) and part 1's (their first halves; that file's P3 and P2
are part 1's main run and are not added). The extras so counted include mode B's walk over shoes
on part 1's bundle, which holds none of its instances, a small cost the main run does not have, so
the rule errs toward a smaller run. Fill refuses a file that names another part's figure or a V at
70% or 75%, repeats a line, or lacks a time; files of two parts; a figure in two files or in none;
and a file with no figure, but for part 2's one on part 1's bundle. SimCheckPlanTest holds each
block equal to its committed files' output, and to "Not filled" while there are none. The block is
committed before the main run starts.

Part 1:

<!-- SimCheckPlan fill-1 -->
- Part 1's pilot, 1 file, one a bundle:
  - sim-pilot-part1.tsv: F01 to F25 (25 figures); setup 2 s, at 1% P3 18 s, P2 9 s, extras 0 s; all counted.
- Part 1. Setup 2 s; at full size P3 1781 s, P2 942 s, fixed extras 0 s; budget 5400 s.
- f = 1.000: P3 38 chunks (38,000,000 shoes), P2 K = 120,000 (7,289,160,000 re-deals); predicted wall time 2724 s, 0.76 h.
- No stop: the main run goes ahead at these sizes.

| Test | Pilot SD a round | Simulated SE | Exact-side SE | Combined SE | Caught 90% | Caught 95% |
|---|---|---|---|---|---|---|
| F01.P3 | 1.1549 | 0.0026% | 0.0053% | 0.0059% | 0.0280% | 0.0302% |
| F02.P3 | 1.1547 | 0.0025% | 0.0054% | 0.0059% | 0.0284% | 0.0306% |
| F03.P3 | 1.1541 | 0.0024% | 0.0055% | 0.0060% | 0.0288% | 0.0310% |
| F03.P2 | 1.3041 (sqrt(nV)) | 0.0015% | none | 0.0015% | 0.0073% | 0.0079% |
| F04.P3 | 1.1541 | 0.0024% | 0.0055% | 0.0060% | 0.0288% | 0.0310% |
| F04.P2 | 1.3041 (sqrt(nV)) | 0.0015% | none | 0.0015% | 0.0073% | 0.0079% |
| F05.P3 | 1.1524 | 0.0026% | 0.0053% | 0.0059% | 0.0281% | 0.0303% |
| F06.P3 | 1.3414 | 0.0030% | 0.0068% | 0.0074% | 0.0354% | 0.0381% |
| F07.P3 | 1.6996 | 0.0038% | 0.0099% | 0.0106% | 0.0507% | 0.0545% |
| F08.P3 | 2.0565 | 0.0046% | 0.0142% | 0.0150% | 0.0716% | 0.0770% |
| F09.P3 | 2.1362 | 0.0047% | 0.0148% | 0.0156% | 0.0744% | 0.0801% |
| F10.P3 | 1.1519 | 0.0025% | 0.0054% | 0.0060% | 0.0286% | 0.0307% |
| F11.P3 | 1.3611 | 0.0029% | 0.0071% | 0.0077% | 0.0368% | 0.0396% |
| F12.P3 | 1.7706 | 0.0038% | 0.0107% | 0.0114% | 0.0544% | 0.0585% |
| F13.P3 | 2.2266 | 0.0048% | 0.0161% | 0.0168% | 0.0805% | 0.0866% |
| F14.P3 | 2.3111 | 0.0050% | 0.0167% | 0.0174% | 0.0832% | 0.0895% |
| F15.P3 | 1.1513 | 0.0024% | 0.0056% | 0.0061% | 0.0290% | 0.0312% |
| F15.P2 | 1.3019 (sqrt(nV)) | 0.0015% | none | 0.0015% | 0.0073% | 0.0078% |
| F16.P3 | 1.3794 | 0.0029% | 0.0074% | 0.0080% | 0.0381% | 0.0410% |
| F16.P2 | 1.4876 (sqrt(nV)) | 0.0017% | none | 0.0017% | 0.0083% | 0.0090% |
| F17.P3 | 1.8371 | 0.0038% | 0.0114% | 0.0120% | 0.0575% | 0.0619% |
| F17.P2 | 1.8404 (sqrt(nV)) | 0.0022% | none | 0.0022% | 0.0103% | 0.0111% |
| F18.P3 | 2.3868 | 0.0050% | 0.0177% | 0.0184% | 0.0881% | 0.0948% |
| F18.P2 | 2.2071 (sqrt(nV)) | 0.0026% | none | 0.0026% | 0.0124% | 0.0133% |
| F19.P3 | 2.4747 | 0.0052% | 0.0183% | 0.0190% | 0.0907% | 0.0976% |
| F19.P2 | 2.2812 (sqrt(nV)) | 0.0027% | none | 0.0027% | 0.0128% | 0.0137% |
| F20.P3 | 2.3286 | 0.0048% | 0.0157% | 0.0165% | 0.0787% | 0.0847% |
| F20.P2 | 2.1639 (sqrt(nV)) | 0.0025% | none | 0.0025% | 0.0121% | 0.0130% |
| F21.P3 | 2.4060 | 0.0050% | 0.0181% | 0.0188% | 0.0898% | 0.0967% |
| F21.P2 | 2.1797 (sqrt(nV)) | 0.0026% | none | 0.0026% | 0.0122% | 0.0131% |
| F22.P3 | 2.4011 | 0.0050% | 0.0179% | 0.0186% | 0.0889% | 0.0957% |
| F22.P2 | 2.2853 (sqrt(nV)) | 0.0027% | none | 0.0027% | 0.0128% | 0.0138% |
| F23.P3 | 2.4040 | 0.0050% | 0.0183% | 0.0190% | 0.0909% | 0.0978% |
| F23.P2 | 2.2095 (sqrt(nV)) | 0.0026% | none | 0.0026% | 0.0124% | 0.0133% |
| F24.P3 | 1.1506 | 0.0024% | 0.0056% | 0.0061% | 0.0290% | 0.0312% |
| F24.P2 | 1.3016 (sqrt(nV)) | 0.0015% | none | 0.0015% | 0.0073% | 0.0078% |
| F25.P3 | 2.4027 | 0.0050% | 0.0184% | 0.0191% | 0.0913% | 0.0983% |
| F25.P2 | 2.2078 (sqrt(nV)) | 0.0026% | none | 0.0026% | 0.0124% | 0.0133% |
<!-- /SimCheckPlan fill-1 -->

Part 2:

<!-- SimCheckPlan fill-2 -->
Not filled: part 2's pilot has not run.
<!-- /SimCheckPlan fill-2 -->

## 9. Reported, not tested

These come from the same runs and go on the page as measurements, never into the pass rule.

- **Mode B.** Hi-Lo and Victor APC + ace at full play, 80%, flat and 1-8 (the instances of F26
  to F29), on P3's first 4 chunks with each instance's own play taking the cards; beside each,
  the same instances in mode A on the same shoes, and the paired difference shoe by shoe with
  its SE. A choice of convention, not an error: the page shows what it changes.
- **The moving count.** The same instances and shoes in mode A, with the count moving through
  split hands instead of frozen at the deal's play key; the paired difference from the frozen
  count, with its SE.
- **Differences between counts on fresh shoes.** In mode A, P3, every figure less Hi-Lo's at the
  same play, penetration and bets, shoe by shoe, with its SE; labelled "between two fixed fitted
  strategies on new shoes; the fits' own noise is not in it". And P2's paired difference, Victor
  APC + ace less Hi-Opt II + ace at full play, 1-8, 80%, beside the engine's difference on the
  same states: it shows agreement, not which count is better (PLAN_COMPUTE.md 1.9).
- **The rounds a simulation would need** (PLAN_PAGE.md section 3's {N}): σ_eff of the per-round
  difference Victor APC + ace less Hi-Lo at 1-8, 80%, in mode A, at first decisions (F25 less
  F18, part 1) and at full play (F29 less F27, part 2).
- **Swings** (PLAN_COMPUTE.md 2.2 to 2.4), for the figures the totals of section 3 list: σ, σ_eff
  and their SEs; E[b] and E[b^2] on fresh shoes with their SEs, beside the scorer's, whose z is
  shown but is not in the pass family (D4 already holds the bets equal on every state); the
  exact histogram of (bet level, 2X), split rounds tagged, every result asserted inside the range
  the rules allow; and the per-shoe sums ΣY, ΣY², ΣY³, ΣY⁴, ΣN, ΣN², ΣYN, with rounds dealt and
  rounds played counted apart.
- **The house edge by depth** (`depth.tsv`): the chart's flat EV in bands of 26 cards dealt,
  exact (from states.bin) and simulated (P3).
- **The edge at each count** (`ev-by-key.tsv`): Hi-Lo's mean round value by bet key at 80%, at
  first decisions (part 1) and full play (part 2), exact and simulated, on the key convention of
  PLAN_COMPUTE.md 5.4, item 9.
- **Unseen keys**: for each instance, how often a fold met a key its fit never saw and took the
  nearest present one.
- **The run itself**: rounds and shoes, threads, wall time, rounds a second, load average, Java
  version, commits, the bundle's SHA-256, the seed derivations, and the state of every
  planted-bug switch (all off in a main run).

## 10. Planted bugs

Each is a switch in the simulator, `--plant NAME`, off unless named; a summary prints the state
of every switch, and a main run with any switch on is refused by the reader.

1. `s17`: the dealer stands on soft 17.
2. `no-das`: no double after a split.
3. `split-aces-hit`: split aces are played like other split hands.
4. `hole-card`: the hole card enters the count when it is dealt, so every key read during the
   round includes it.
5. `truncate`: true counts truncated toward zero instead of rounded to nearest.
6. `insure-whole-bet`: insurance staked at the whole bet instead of half of it.
7. `other-fold`: the other fold's strategy. In P2 each state is played by the instance fitted on
   its own shoes; in P3 the pooling weights W_1 and W_2 are swapped.
8. `bet-key-plus-one`: every key is bet as the next key up.

**Each run**, after part 2's main run so no switch can be on during it: the D checks, then P3 on
the first chunk and P2 at K = 6,000, scored on the planted figures the totals of section 3 list,
by both checks. Beside them, a clean control at the same size, with no switch on.

**On both bundles.** The planted figures are in two bundles: F03, F15 and F18 in part 1's, F26 to
F29 in part 2's (the replay's). So every planted run, and the clean control, is made twice at the
sizes above, once on each bundle, and a run is its two halves together: its tests are the 14 the
totals list, F03, F15 and F18 from its half on part 1's bundle and F26 to F29 from its half on part
2's; it is caught if either half's D checks or tests catch it; and the control must pass on both.
The reader takes each half on the bundle the identity table names for the part of the figures it
scores, and refuses a run with a half missing. Both halves are part 2's fixed extras, paid first
from its budget (section 8), and part 2's pilot times the halves on part 1's bundle with a pilot of
its own there. Decided on 14 October 2026, before any pilot (INTEGRATION.md open item 18): until
then the commands planted on part 2's bundle alone, so F03, F15 and F18 were scored by no planted
run although the totals listed them.

**Caught** means any D check failed or any of the run's tests reached |z| ≥ 3.5. For each bug the
page prints which D checks failed, every test's z, the largest |z|, and the bias each test would
catch 90% and 95% of the time at this size. It says whether the z tests alone caught it, since
most of these bugs fail an exact check first and the z tests' reach is the thing being shown. A
bug nothing caught is listed as under the limit, with the limit. The clean control must pass; if
it does not, the planted runs are reported as saying nothing about the z tests. The planted runs
are not in the pass family.

## 11. The paths the page draws

- **The fan** (PLAN_COMPUTE.md 2.5): Hi-Lo at full play, 1-8, 80% (the instances of F27), and
  the same for Victor APC + ace (F29) should the page draw a second fan. Path 0 starts at P3's
  shoe 0; each path takes whole shoes in order until it has played 100,000 rounds (1,000 hours at
  100 rounds an hour), ending inside a shoe if need be, the rest of that shoe dropped; path j+1
  starts at the shoe after path j's last. Shoe h is played by the instance of fold (h mod 2) + 1:
  one real strategy a shoe, since a weighted mix of the two folds is not a path anyone could
  play. The cumulative win in units is written every 100 rounds; at 50 and 80 rounds an hour the
  page draws the first 50,000 and 80,000 rounds of the same paths. The 20 drawn are the first 20,
  and none is replaced or chosen for its look; the caption says so.
- **The per-shoe records** (Y, N, m), m the lowest the shoe's cumulative win falls to, for
  PLAN_COMPUTE.md 2.4's ruin check: the same two strategies at 1-8, on P3's first 2 chunks, shoe
  h played by fold (h mod 2) + 1 as above.
- **The ruin paths**: 20,000 bankroll paths, each drawing shoes with replacement from those
  records with a generator seeded from 20261010 and the path's index; each path is run from
  B_25%, B_5% and B_1% (the formula's, at the simulator's own μ and σ_eff from the same records)
  on the same draws, is ruined when bankroll + m ≤ 0, and stops where the formula's remaining
  risk is under 5e-5.
- **Trip risk and the chance of being behind**: consecutive blocks of whole shoes from the
  records in shoe order, from shoe 0, each the fewest shoes reaching H·r rounds; a last
  incomplete block is dropped.

## 12. What a run writes, and what the reader refuses

`runs/sim/run-<simsha>/` holds what PLAN_COMPUTE.md 1.8 lists (`summary.json`, `checks.txt`,
`depth.tsv`, `ev-by-key.tsv`, `variants.tsv`), plus, for every test, the sums section 6 defines,
the paths and records of section 11, and the pilot's `sim-pilot-partN.tsv`. The formats of the
files the engine side reads beyond the summary, `swings.tsv`, `records-FIG.tsv`, `fan-FIG.tsv` and
`ev-by-key.tsv`, are SHOE_SPEC.md Part C's, keyed by the ids of the registry block in section 2. The engine-side reader
that turns them into `runs/site/sim-<simsha>.json` refuses a run whose summary:

- lacks any D check, or any registered test of the parts it covers, or holds a test that is not
  registered (SimCheckPlan's list is the registry);
- gives a z that its own sums do not give within 1e-9 relative (section 6);
- names a bundle, a simulator commit or sizes other than section 8's filled block for its part;
- has any planted-bug switch on in a main run.

## 13. On a fail

As PLAN_COMPUTE.md 1.6 says, and fixed here. The run's summary is committed before anyone looks
into the failure, and the page shows it. The figures a failed test bears on are held back: the
figure itself and, for rows 7 to 10, the swings, spreads and ceiling figures that rest on it.
The search runs in order: the D checks; P2 split by deal and first move, with the simulator
playing every first move; then by later-decision spot; then tiny shoes. Every fix, on either
side, is a commit that says what it fixed and what it changed in the figures, listed on the
page. A rerun after a fix uses the same seeds and sizes, so it plays the same shoes, and the page
shows the failed run beside it. There is no rerun without a fix: a fail is never rolled again.

## 14. Where this differs from PLAN_COMPUTE.md, and why

1. Part 1 plays 17 instances, not "21 and the chart" (section 3): F1's and F2's first-decision
   strategies exist only after the page look.
2. 82 tests, not about 100: the figures at 70% and 75% carry P3 only, since P2 plays the 80%
   instances (PLAN_COMPUTE.md 1.7). The cap of 100 stands.
3. Check 2 of PLAN_COMPUTE.md 2.4 (the bets' moments within the combined SEs) becomes exact: D4
   holds every bet at every state to the bit, and the moments on fresh shoes are reported.
4. The pilot is blind, and the SD stop is new (section 8): so the sizes are set before any
   agreement is seen, and a gross bug shows before the main run.
5. P2's seed (20261009), the ruin paths' (20261010) and the coins' streams are new; the plan named
   only P3's (section 4).
6. Mode B, the moving count and the planted runs have fixed sizes and are paid from the budget
   first; the plan's scaling rule names P3's rounds and P2's K only.
7. The fold rule for the paths and records, one fold a shoe by parity, is new (section 11).
8. The lookups (D5) are defined, which adds `lookups.tsv` to the bundle the exporter writes.
9. The exact side's SE is written out (section 6): the ratio estimator over the main file's shoes,
   with the fit held fixed, as exact.tsv's per-shoe sums allow.
10. The chart's figures F01 to F03 are read from step 7's file, not c06c061's, which holds no
    reference row (section 2; changed 12 October 2026, before any pilot).
11. Each planted run is made on both parts' bundles, so it scores all seven planted figures the
    totals list; the plan names no bundle for them (section 10; 14 October 2026, before any
    pilot).
12. A full-play strategy file ranks a deal for split hands apart from the dealt hand where the
    two differ (`split` lines), and D5 holds every split hand's first decision; the plan's file
    has one ranking a deal (deviation 1; 18 October 2026, after part 1's pilot, part 2 only).

## 15. The code

`src/SimCheckPlan.java` holds the registry (the figures, the strategies, the tests), the normal
arithmetic of section 7 (an upper tail by Simpson's rule, its quantiles by bisection), the
scaling rule and the SEs of section 8, and prints every marked block: `java SimCheckPlan` all of
them, `java SimCheckPlan block NAME` one, `java SimCheckPlan fill FILE ...` a part's pilot files'.
`test/SimCheckPlanTest.java` holds this file equal to that output; the registry to the plan's
counts and cap; the entry names to the ones the scorer gives (the catalog, the null count, and
F1 and F2 read from custom-counts.json, F2 with its betting vector); the arithmetic to published
values of the normal distribution; and the scaling rule to its own words (rounding down, the
stop under 0.5 and not at it, extras paid first, no run that goes ahead predicted over budget).
