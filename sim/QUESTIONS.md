# Questions the simulator's author could not settle from the spec

The simulator in `sim/` was written in a clean room: from ROUND_CONTRACT.md, SHOE_SPEC.md,
SIM_CHECK.md (branch site-sim-check), PLAN_COMPUTE.md section 1 and 7.1, RESULTS_SITE.md and
Loto-Québec's published rules, without reading the engine (`src/`, `test/`) or any run's output.
Where those sources left something open, the question is written here with the reading chosen,
so the engine's side can answer it and either confirm the choice or say what to change.

1. **Soft 17.** Loto-Québec's PDF leaves hit-or-stand on soft 17 to the sign at the table.
   *Chosen:* the dealer hits soft 17, from SHOE_SPEC.md A2 (`rule.hits.soft.17 = true`) and
   PLAN_COMPUTE.md 1.4 ("the dealer hits soft 17"). The walk's totals agree with A8 to the
   round under it (sim/test/WalkTest), and do not under the other reading: with the dealer
   standing on soft 17, planted on 9 October, the walk gives 3,210,144 round starts at 70%, not
   3,197,853.

2. **"Only java.util".** PLAN_COMPUTE.md 1.1 says the simulator uses only `java.util` and
   compiles alone. Reading files needs `java.io` and `java.nio.file`, and checking the bundle's
   `manifest.sha256` needs `java.security.MessageDigest`. *Chosen:* the JDK's own library and
   nothing else, no third-party jar and no class of the engine; `javac -d out sim/*.java` with
   no classpath still compiles it. The tests in `sim/test/` use JUnit 5 from `lib/`, and are
   not part of that one-command compile.

3. **counting-systems.json.** Check D2 has the simulator read counting-systems.json with its own
   parser, but that file is not among the sources this session was allowed to read, so its
   format is unknown here. *Chosen:* D2's parser is not written yet. The keys themselves (B2.2)
   are, and are tested on the parameters a strategy file gives (`tags`, `bettags`, `decksum`,
   `betdecksum`, `ace`, `divisor`, `mode`). A later session that may read the catalog adds the
   parser and the keys-sample.tsv comparison.

4. **The chart before the bundle exists.** D1 plays the chart in every round of the walk, and
   chart.tsv comes from the bundle. *Chosen:* until it exists, the walk plays a stand-in chart
   (`Chart.textbook()`, the published eight-deck basic strategy for H17 with double after split
   and no surrender, written from general knowledge). Its walk of all 60,000 main shoes gives
   exactly A8's round starts, valued states and weights at the three cuts. Its fingerprint is not
   the engine's (unreachable cells differ, B1), so every reported run plays chart.tsv; the
   stand-in is for the D1 totals before the bundle, and for tests.

5. **The deal's play key for insurance on a player's natural.** B2.4 says the player's natural is
   an ace-up deal like any other for insurance. *Chosen:* as written: the natural's deal (1, 10,
   ace) is looked up in `insure` lines like any other deal.

6. **How the chart's row reaches the bundle.** SIM_CHECK.md's count list names the chart row
   "Chart, no count, flat" and calls it one instance at every penetration, fitted on nothing,
   whose flat figure has no insurance; B2 and B5 describe strategy files and exact.tsv lines by
   fold. *Chosen:* the simulator plays the chart's own walk as that row at 70, 75 and 80%
   (`Row.chart`), and takes the engine's figure from exact.tsv lines with that row name and
   folds 0 and 1, pooled as B5 pools any row; P2 takes it from a states.bin section of that name
   at 80%. A strategy file with that row name is not played beside the walk. If the exporter
   writes the chart another way, the summary's exact side for the chart is empty until this is
   settled.

7. **D5's lookups.tsv.** SIM_CHECK.md section 5 adds `lookups.tsv` to the bundle, but neither it
   nor SHOE_SPEC.md gives its format. *Chosen:* not written. Strategy.Play exposes what D5
   compares (`keyOfDeal`, `insuredAt`, `firstMoveAt`, and `choose` for later decisions), so the
   reader is short once the format is fixed.

8. **The coin.** SIM_CHECK.md section 4 asks for a coin a round in P3 and a coin a re-deal in P2,
   from a stream of its own. *Chosen:* one `nextDouble` a round start (P3) or a re-deal (P2) from
   `Xoshiro.of(seed, index, 1, 0)`, drawn whether or not any plan needs it, and shared by every
   plan and instance on that round; a round is played when the coin is under PLAY.

9. **The figures the simulator reports.** The registry of the 52 figures is SimCheckPlan's,
   engine side, which this session did not read. *Chosen:* the simulator reports every row and
   figure it can, `flat` and `ev:LABEL` for each plan of each row's files, under exact.tsv's
   figure names, and the engine-side reader picks the registered ones (SIM_CHECK.md section 12).
   P2 runs the rows at 80% only, as section 2 says.

10. **The pooling weights when a fold file is missing.** P3 pools the two folds with weights from
    exact.tsv's `flat` lines. *Chosen:* a row is played only when both its fold files are in the
    bundle; a figure without exact.tsv lines is reported with an empty exact side.

11. **Which groups are allowed.** B2.1 says `group g lo hi` lines come by g and B2.3 looks up the
    first group whose `hi` is at least the key. *Chosen:* the reader requires each group's `hi` to
    be above the last one's and `lo` to be at most its own `hi`, and nothing more; `lo` is for
    reading only. It refuses `first` lines in a betting-scope file, since B2.1 lists them for
    scopes first and full only.

12. **What sizes a planted run uses.** SIM_CHECK.md section 10: P3 on the first chunk and P2 at
    K = 6,000, and a planted run is reported even when an exact check fails first. *Chosen:*
    `sim.Sim p2 --plant NAME` goes on to P2 after failed D checks whenever a plant is on (and
    stops there otherwise); the sizes are the command's `--chunks` and `--k`, given by whoever runs
    it.
