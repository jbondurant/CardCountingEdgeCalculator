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
