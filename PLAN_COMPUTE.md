# The page's computations

On 8 October Justin asked for the results to become a tab on his website, with more
simulation behind them and evidence a stranger could check. He chose four extra
computations: an independent simulation, swings and money per hour, wider spreads and
wonging, and the perfect full-play ceiling. This file plans those four and a fifth that the
page needs to explain why counting works at all: the edge at each count. For each it says how
the figure is computed in terms of the code, exactly what is computed, how it is checked and
what passes, what the page gets, and what it costs on this machine. Section 7 gives the order
of work, section 8 the decisions that are Justin's.

Nothing here has run. A figure marked *estimate* is arithmetic or scratch work and never goes
on the page. Every number the page shows comes from a generator committed to this repo, run on
the real outputs.

## 0. Where things stand, and what binds every part

**On 9 October at 20:00.** Step 6 of COUNT_DESIGN.md, the registration, was committed on 8
October as 97bf834 on custom-count-design (custom-counts.json, blob 39bd6d4a…;
custom-counts-fullplay.json, blob 250e64a7…). Step 7 has been running since 19:16 from
runs/custom-count/classes-97bf834 (ComparisonReport, pid 42127, -Xmx12g, 7 threads, `--save
runs/report-ceiling-97bf834.ser`); its .md and .log are still empty. Step 7 writes its ledger
line once three counts have been re-checked, about 20 minutes in, and that line was committed
at 19:37 as 16df265, while the run goes on for hours. So the ledger commit is not the signal to
start step 8. **Step 8 starts only after step 7's process has exited cleanly and its .md and
.ser are written** (7.1 says how a script tells). Anyone or any script starting it waits on
that process, never on the ledger commit: two -Xmx12g runs on a 16 GB machine would swap or
kill step 7, which cannot resume. COUNT_DESIGN.md's step 8 says "once step 7's ledger line
is committed"; it is read here as "once step 7 has exited cleanly", and that sentence is to be
corrected the same way on custom-count-design, in a commit of its own there. Three things
follow.
- **Steps 7 and 8 stay exactly as registered.** Their classes are frozen and nothing here
  changes them. The spreads proposal's preferred route, merging the wide-spread code into the
  ceiling branch so step 7's save would hold those rows, is no longer open. The registered
  counts' wide rows, per-key tables and simulator strategies come from one later scoring of the
  same registration, written to the ledger like every other look (section 6.3).
- **The ceiling proposal's three items "before step 6" change.** An outside timestamp can no
  longer predate the look (section 4.1). The result paragraphs can still be written blind if
  they are committed before anyone opens report-ceiling-97bf834.md. The check of c06c061's
  perfect array moves to after step 7, and a row it is missing can be recovered without a
  second look at the registered counts (section 4.2).
- **Nothing heavy runs beside steps 7 and 8.** Step 7 does not resume; if it dies it is run
  again whole, about 3 to 4 hours, with a second ledger line. While it runs, only code and
  single-threaded fixture tests at `nice -n 15` with a small heap.

**Rules every part follows.**
- **Numbers from committed code.** Each component ends in a committed report under runs/ and a
  data file the site generator reads. The generator refuses to write the page if any check of
  this plan failed or any input's SHA-256 differs from its manifest.
- **Components write data, the site generator renders.** Every component here writes JSON into
  `runs/site/`, with the SHA-256 of each input and the commit, and nothing else for the page:
  no HTML, no SVG, no script. PLAN_PAGE.md section 2.4's one path does the rest: SiteBuild
  merges the files and renders the page, SiteSvg draws every chart-table mark and SVG panel,
  and every digit on the page sits in a `data-k` span. The browser switches between
  precomputed views and does no arithmetic, so there is no calculator. SiteBuildTest holds a
  budget for each file that goes into `card-counting/`: page HTML 200 KB and page.js 12 KB
  (PLAN_PAGE.md 2.5); `results.json` 500 KB and `results.csv` 300 KB; the toggle-loaded
  `key-edges-{70,75,80}.json` 150 KB each; `spreads.json` 200 KB; `risk.json` 60 KB;
  `ceiling.json` 60 KB; `fig/*.svg` 250 KB together; the manifest files 60 KB. That sums to at
  most about 2.1 MB, the figure the push list uses for `card-counting/` in place of PLAN_PAGE.md
  section 5's "about 1 MB". Anything bigger, such as full-detail CSVs, is a Release asset or
  stays local.
- **Same states, same resamples.** Every new exact figure is scored on runs/montreal-80.states
  with the bootstrap's seed 20260929 and 1,000 resamples, so it pairs with every published
  figure: resample r is the same draw of shoes everywhere. The simulator is the one deliberate
  exception; it plays shoes of its own.
- **One machine.** 16 GB, 8 cores. One heavy run at a time. Every run over about 30 minutes
  resumes after an interruption or is cheap enough to run again whole. The lid stays open or the
  charger stays in: on 7 October a lid closed on battery slept the machine for 8.4 hours, and
  `caffeinate -i` does not prevent that (COUNT_DESIGN.md section 7).
- **Keep the tree store.** runs/full-index/full-c06c061/trees.bin (14.9 GB) and fit.ckpt (184
  MB) stay until the replay of section 6.1 and the look of section 6.3 are done. Rebuilding the
  trees took 9,096 s for round 0 alone. The disk had 14 GiB free on 9 October (df).
- **A hash is not a proof.** A SHA-256 on the page shows that a file is the one a given commit
  produced. It says nothing about whether the code is right. The evidence for that is the checks
  each section lists, and the page keeps the two apart.

**Claims of the proposals checked against the code for this plan (9 October).**
- Cuts 291, 312 and 332 cards (SystemScorer.CUTS); 3,197,853, 3,426,592 and 3,644,432 rounds
  dealt and 60,743 valued at 80% (the header of runs/report-final-26f0724.md).
- Shoes: seed 20260928, SplittableRandom seeded with ShoeRun.mix(seed, shoe, 0), Fisher–Yates
  from the top index down; whether a round start is valued is drawn on stream 1 of the same mix,
  with q0 0.008 and u0 0.02.
- True counts: CountKeys.roundedRatio divides once in double and rounds with
  GranularCount.roundToGrain, `signum(x)·ceil(|x| − 0.5)`: to nearest, ties toward zero.
- 51 figures per entry and penetration (SystemScorer.METRICS = 3 + 6 × 8): flat, play,
  insurance, then for spreads 1 to 8 the same schedule's EV and average bet, the own ramp's,
  and the own ramp rounded to whole units.
- Full-play entries carry per-key tables: scorePen calls `scoreOnce(..., true)` for them too.
- SystemScorer.bounds and perfectPlayRow leave out, with no warning, any penetration where the
  perfect array holds a NaN; FullIndexFit fills that array only for the states it visits.
- A later scoring of the registered counts from other classes is allowed and is part of the
  one look: CustomCounts.lookOnMain appends a ledger line carrying the registration's blob, and
  only a look at another registration is refused.

## 1. The independent simulation

The question it answers: if a separate, plain program deals real shoes card by card and plays
the same fitted strategies with the same bets, does it get the same EV as the exact engine?
A simulation cannot prove the engine right. It can catch a mistake in how rounds are dealt,
played, counted, paid or weighted, down to a size the page states.

**1.1 A new simulator, not the old one.** The repo's older Monte Carlo code (src/Simulation.java,
Table.java, Deck.java; EXPLAINER.md) cannot estimate the quantity the report gives. It builds a
fresh 416-card table each event and burns a uniform random number of cards, so its depths are
not those of round starts in a shoe; it plays from its own total-keyed tables in MongoDB, with
the count clamped and decks rounded up; it has no insurance and no bet sizes; it uses unseeded
`Math.random`; and it needs a MongoDB server. Adapting it would mean rewriting its dealing,
play and bets.

So a new simulator goes in `sim/`, about 800 to 1,200 lines (estimate), using only `java.util`
and compiling alone (`javac -d out sim/*.java`, no classpath), which anyone can check in one
command. It is written in a separate agent session from four sources only: ROUND_CONTRACT.md,
Loto-Québec's published blackjack rules (quoted in its source), the file format of 1.2, and
`SHOE_SPEC.md`. ROUND_CONTRACT.md says nothing about how a shoe is laid out or shuffled, and D1
(1.6) needs exactly that, so SHOE_SPEC.md is written on the engine side from ShoeRun and
committed before the session starts: the 416 cards laid out as two of each of 26 kinds per
deck in kind order; the 26 kinds and their encoding, which colour and face counts use;
`mix(seed, shoe, stream)` with its three constants; the SplittableRandom `nextInt`
Fisher–Yates from the top index down; the round-start rule at each cut; the valuation draw on
stream 1, one `nextDouble` per round start whether valued or not, with u, q0, u0 and q; and
when each card enters the count. A test on the engine side (`test/ShoeSpecTest.java`) holds
the spec's constants and order equal to ShoeRun's. The session does not read ShoeRun,
ExactRound, ConcreteRound, CountKeys, ScoringStates or SystemScorer. The page says that AI
agents wrote both the engine and the simulator, and that the simulator's author was given the
shoe's procedure as a written spec.

A throwaway prototype in this stage's scratch directory (an 8-deck H17 DAS shoe walk, chart
play, flat bets, one thread at nice 15 with a load average near 10) dealt 200 million rounds in
30.7 s, flat EV −0.668% ± 0.008% against the report's −0.660%, SD 1.161 units a round. Scratch
only: it shows the cost is minutes, not days.

**1.2 What it reads: an export bundle written by the engine side.** `src/StrategyExport.java`
writes `runs/sim/export-<sha>/` with `manifest.sha256`. results.ser alone is not enough: for
first decisions it keeps only `Result.groups` (no ranked moves, no insurance fit, no per-fold
bet maps), and `Entry.keys` pools the two folds' bets. So the exporter fits first decisions
again through the same package-private path the estimate uses (`fit`, the insurance fit,
`bets(..., detail)`), minutes a count without resamples. Full-play strategies and their
per-state values come from the replay of section 6.1, which already rebuilds them; no extra
tree pass. Files:
- `chart.tsv`: BasicStrategy's answer to all 253,440 decisions, enumerated as
  `BasicStrategy.fingerprint` does, with the fingerprint; the simulator rehashes what it loaded.
- `strategy-<row>-p<P>-f<fold>.txt`, one per fold strategy. Header: tags by the 26 kinds at
  their scale, running or true count, `deckSum`, the ace multiplier or betting vector, each key
  formula as an exact integer ratio. Then `group g lo hi`; first-decision moves ranked best first
  (`first g p1 p2 up S H D P`); `insure g p1 p2`; full-play later cells (`later g total soft up
  S|H`, with unreached cells already resolved to the chart); bets `bet plan key units` for every
  plan the simulator plays (1-8, and section 3's plans); and the rule for a key the fold never
  saw (nearest present key), whose use the simulator counts.
- `states.bin`: every valued state (shoe, round, depth, q, fold, the 26 tallies dealt) and, per
  exported strategy and penetration, the engine's bet key, round value and bet under each plan.
  About 3 MB of states and 1 to 3 MB for each of about 31 row and penetration pairs, so 40 to
  100 MB (*estimate*); the exporter measures it.
- `keys-sample.tsv`: for 2,000 states, the engine's bet key for all 31 catalog counts and 8 ace
  variants (one line a state), and their play keys, with the parts for colour and face counts,
  for 10 deals a state drawn with a fixed seed plus all 550 deals of the first 20 states (one
  line a state and deal, every count's key on it): about 31,000 lines, about 7 MB
  (*estimate*). Every deal of every state, 2,000 × 550 × 39 keys, would be about 43 million
  rows, hundreds of MB as text, and test little the sample does not: a play key depends on the
  deal only through the sum of the three dealt cards' tags (`CountKeys.playKey`).
- `exact.tsv`: per fold strategy and figure, the test fold's sums and per-shoe moments, which
  give each estimate's SE with the fit held fixed.

**The round trip, inside the exporter.** The bundle is parsed back by an engine-side reader and
scored by SystemScorer. Pooled over folds it must equal `Entry.estimate[p][metric]` and
`Entry.shoeSums` in c06c061's results.ser (or wide.ser, report-ceiling-97bf834.ser, step 8's
results.ser for their rows) within 1e-12 relative. This is the one thing the simulator cannot
check for itself: that the files hold the strategy the report scored.

**1.3 Rows.** One list, pre-registered in `SIM_CHECK.md` before the first pilot, serving four
components. Pass/fail rows carry a z test; by-product rows are reported, not tested.

| Row | Play | Pen. | Bets | Serves |
|---|---|---|---|---|
| Chart | — | 70/75/80 | flat, no insurance | check; literature |
| Null count | first decision | 80 | flat | check |
| Hi-Lo | first decision | 70/75/80 | flat, 1-2, 1-4, 1-8, own 1-8 | check; edge by count |
| Hi-Lo | betting only | 80 | 1-8 | check |
| KO, Red Seven, Hi-Opt II + ace | first decision | 80 | 1-8 | running count, colour, side count |
| Victor APC + ace | first decision | 80 | flat, 1-8 | check |
| Hi-Lo, Victor APC + ace | full play | 80 | flat, 1-8 | check; swings |
| Hi-Lo, KO, Zen, Hi-Opt II + ace, Victor APC + ace | full play | 70/75/80 | 1-8 | swings |
| Hi-Lo, Victor APC + ace | full play | 80 | P16, B0,8, X−1,8 (section 3) | spreads |
| F1, F2 | first decision and full play | 80 | 1-8 | ceiling, after 6.3 |

About 50 figures, each compared twice (1.6), so about 100 z tests; the list is capped there.

**Strategy instances, which set the cost.** Each row plays one strategy per fold and
penetration, so the table means about 55 instances: the chart 1; the null count 2; Hi-Lo at
first decisions 6; KO, Red Seven and Hi-Opt II + ace 6; Victor APC + ace 2; full play for five
counts at three penetrations 30; F1 and F2 at both scopes 8. Betting only plays the chart and
adds bets, not a playout. The 21 first-decision instances share playouts by first move; the 34
full-play instances each need their own playout, with a lookup at every later decision.
SIM_CHECK.md lists the instances each check plays and prices the check by them.

**1.4 The game it plays,** from the contract and the casino's rules: 416 cards, no burn card; a
round starts while fewer than 291, 312 or 332 cards have been dealt; deal order player, up-card,
player, hole card; the dealer peeks, a dealer natural takes one unit and pushes a player
natural, which otherwise pays 3:2; insurance pays 2:1 on half a unit, decided on the deal's play
key; double on any two cards; split to four hands; double after split except on split aces;
split aces once, one card each, and a split ace with a ten is an ordinary 21; split hands are
dealt in contract order (both second cards, then the first hand played out); the dealer hits
soft 17 and draws only if some hand stood or doubled without busting; every card dealt, the
hole card included, enters the count after the round. Full play keys later decisions on every
card the player has seen, never the hole card, over (cards left before the round − cards seen
this round)/52; split hands are frozen at the deal's play key (FULL_INDEX_PLAYS.md section 1).
True counts are exact integer ratios, rounded to nearest with ties toward zero, as section 0 found in the code.

**1.5 Two ways to walk a shoe.** The exact states come from rounds the chart played
(ShoeRun.deal calls `ConcreteRound.play` with the chart). So in **mode A**, the comparison mode,
each round start is played twice on the same card order: by each strategy, for its payoff, and
by the chart, to decide which cards leave and enter the next round's count. Every strategy,
penetration and plan is then scored on the same rounds, bets are multipliers on one payoff, and
first-decision strategies share playouts by first move. **Mode B** lets each strategy's own play
take the cards, as at a real table; it runs for Hi-Lo and Victor APC + ace at full play, 1-8,
and is reported beside mode A, along with full play whose count keeps moving through split
hands. The page names both as choices of convention and shows what each changes.

**1.6 Checks.** Exact checks must match to the last card or bit:
- **D1, the walk and the sampling.** The simulator deals the 60,000 shoes of seed 20260928
  with its own copy of the shuffle (java.util.SplittableRandom from its own copy of `mix`,
  Fisher–Yates, as SHOE_SPEC.md gives them). Its chart walk must give 3,197,853, 3,426,592 and
  3,644,432 rounds. At every round start it recomputes u and q from the cards left and makes
  the stream-1 draw, so it chooses the valued set itself: that set must be exactly the 60,743
  states in states.bin, no more and no fewer, and every one must match card for card (shoe,
  round, depth, 26 tallies) and in q, bit for bit. This checks the weights 1/q exactly, the one
  layer P2 cannot test and P3 tests only statistically.
- **D2, the keys.** The simulator reads counting-systems.json with its own parser. Its bet and
  play keys must equal keys-sample.tsv for all 31 counts and 8 variants, simulated or not.
- **D3, tiny shoes.** An engine-side test (`test/SimVsExactSmallShoeTest.java`, compiling both)
  averages the simulator's round over every order of 9-card shoes; every first move and policy
  must equal ExactRound within 1e-12.
- **Lookups.** For sampled (state, deal) pairs the simulator's move, insurance and bet equal the
  engine's.

Statistical checks, each a z score with the SEs stated:
- **P2, same states.** Each valued state's remaining cards are re-dealt K times. The simulator's
  Σw·bet·mean(payoff)/Σw is compared with the exact Σw·bet·RV/Σw (w = 1/q). Both sides share the
  states and their weights from states.bin, so the noise between states cancels and the SE is
  the simulator's alone. This tests round values, keys, lookups, insurance and bets end to end;
  it does not test the weights, which D1 checks exactly. A paired difference between two counts
  in P2 converges to the engine's own exact difference on the same states (Victor APC + ace less
  Hi-Opt II + ace at full play, 1-8, 80%: +0.0014), so a small P2 SE shows that the simulator
  agrees with the engine, not that one count is better.
- **P3, fresh shoes.** Seed 20261008 and a different generator, xoshiro256** seeded per shoe by
  SplitMix64, written into the simulator. Each fold strategy plays every shoe; the two are pooled
  by the folds' test weights and compared with the report's estimate. SE = √(SE_sim² + SE_exact²),
  SE_exact being the fixed-fit SE from exact.tsv. This adds the sampling layer: the weights, the
  frequency of each state, the 70% and 75% subsets. Its mode-A paired differences between
  counts, reported and not tested, measure the difference between two fixed fitted strategies
  over new shoes; the fits do not move, so their own noise is not in it, and the page labels
  them that way.

**Pass rule, fixed in SIM_CHECK.md before the first pilot.** Every D check matches exactly, and
every z test has |z| < 3.5. Two-sided, that is p = 4.65e-4 a test; with at most 100 tests the
chance of a false fail is at most 4.7% (Bonferroni), printed beside the result. 2.5 ≤ |z| < 3.5
is flagged on the page but does not fail.

**What size of bias a check would catch.** A bias of exactly 3.5 combined SEs gives |z| ≥ 3.5
only half the time, so 3.5 SE is not a detection limit. The page prints, for each check, the
bias it would catch with 90% probability, 4.78 times the combined SE, and with 95%
probability, 5.14 times, labelled with that probability. A bias under those sizes may pass.

**On a fail.** The run's summary is committed before anyone looks into it, and the page shows
it. The affected figures are held back. The search runs in order: the D checks; P2 split by
(deal, first move) with the simulator playing every first move; then by later-decision spot;
then tiny shoes. Every fix on either side is a commit with its reason and its effect on the
figures, listed on the page, which guards against fixing until the two agree.

**Planted bugs,** each run at reduced K to show on the page what the check can see: dealer
stands on soft 17; no double after split; split aces may hit; hole card counted; true count
truncated instead of rounded; insurance paid on the whole bet; the other fold's strategy scored;
bet map off by one key. The page lists which were caught and which fell under the limit.

**1.7 Sizes, from SDs to be measured by a pilot** (1% of each run, which also measures speed and
the weight-dispersion factor Σw²·n/(Σw)²). Assumed: flat σ ≈ 1.16 units (prototype); 1-8 σ ≈ 2.4
(*estimate*: 1.16 × √E[bet²], E[bet²] ≈ 4.27 from report-final's Hi-Lo bets by key).

| Check | SE target at 1-8 | Rounds | Exact-side SE | Bias caught 90% of the time (4.78 SE) | 95% (5.14 SE) |
|---|---|---|---|---|---|
| P3 fresh shoes | 0.005% | ≈2.3e9, about 38M shoes | ≈0.016–0.018% | ≈0.080–0.089% | ≈0.086–0.096% |
| P3, chart flat | 0.0024% | the same rounds | ≈0.0055% | ≈0.029% | ≈0.031% |
| P2 same states | 0.003% | 7–8e9 re-deals (about 120,000 a state) | none | ≈0.014% | ≈0.015% |

Mode A shares one walk, so these rounds serve every row at once, wide plans included (at 1-16, σ
near 4.5 gives an SE near 0.009%, *estimate*).

**Cost, priced by instances** (*estimate*; the one measurement is the prototype's chart round,
about 0.15 µs on one loaded thread). About 0.2 µs a full-play playout and about 1 µs a round for
the 21 first-decision instances sharing playouts, so about 8 µs a round for all 55 on one
thread; six threads are worth about three (COUNTING_COMPARISON.md section 7). P3, every
instance on 2.3e9 rounds: about 1.5 to 2 hours. P2 plays the 80% instances (a state is in one
fold's test set, so one fold's instance a row), about 2.5 µs a re-deal: about 1.5 to 2.5 hours.
Mode B and planted bugs about 30 minutes; D checks seconds. **About 3 to 5 hours** in all, under
1 GB of heap, split into the two parts of 6.2. The exporter: minutes a count for first
decisions; full play rides on section 6.1.

**The pilot is a gate.** Before each part's main run (6.2), a pilot of 1% of it measures the
speed a round of each instance, the SDs and the weight-dispersion factor. Budgets, fixed in
SIM_CHECK.md before the pilot: part 1 at most 1.5 machine hours, part 2 at most 4.5. If the
pilot puts a part over budget, P3's rounds and P2's K are scaled down together by the one
factor that fits, the same for every instance; every SE then grows by the square root of its
inverse, and the page prints the achieved SEs and detection limits, not the targets. A factor
under 0.5 (SEs more than 1.41 times the targets) stops that part for Justin's decision instead
of running smaller. This rule and the SEs it implies are committed before the pilot runs.

Runs are in fixed chunks (1M shoes for P3, 1,000 states for P2); each chunk's sums go to a
temporary file that is forced to disk and renamed, headed by the simulator commit, bundle
SHA-256, seed, chunk size and K, and a resume refuses a mismatch. Chunks are summed in index
order, so results are the same to the bit at any thread count; a test checks this.

**1.8 What the page gets.** `runs/sim/run-<simsha>/`: `summary.json` (per comparison: exact value
and SE, simulated value and SE, rounds, shoes, difference, z, pass; and seed, generator, threads,
wall time, rounds a second, load average, Java version, commits, bundle SHA-256); `checks.txt`
(D1–D3 counts); `depth.tsv` (chart flat EV by 26-card depth band, exact and simulated);
`ev-by-key.tsv` (Hi-Lo mean round value by true count at 80%, exact and simulated); `variants.tsv`
(mode B, moving count in splits, SD a round, per-shoe variance). A small reader writes these
into `runs/site/sim-<simsha>.json`, and the site generator renders from it: the comparison
table (exact | simulated | difference ± 95% | z, for P3 and P2); an agreement chart-table with
the ±3.5 SE band; the bias each check would catch 90% and 95% of the time; the depth profile of
the house edge; EV by Hi-Lo true count, exact against simulated; the D-check line ("3,644,432
of 3,644,432 rounds; 60,743 of 60,743 states card for card, in q to the bit; keys N of N");
and a reproduce box: the bundle and `sim/` rerun every check but the round trip on a laptop,
without the 1.34 GB states file. The bundle's size is measured when the exporter writes it and
printed in its manifest before it is listed anywhere (*estimate* 50 to 120 MB, most of it
states.bin).

**1.9 What it cannot catch,** stated on the page with the cover for each:
- *A rule both sides misread* (hole card, burn card, split rules, H17). The simulator's rules
  are quoted from the casino's PDF and `test/SimRulesMatchHouseRulesTest.java` holds them equal
  to HouseRules; the page lists each rule with its source.
- *A shared export bug.* The round trip, D2 and the lookups.
- *The catalog's tags* in counting-systems.json, a shared input: both sides read it, so a tag
  transcribed wrongly passes D2. The cover is the catalog's two-source rule (its `_about`: a
  system is listed only when two sources agree on its tags or its author published them), and
  the literature stage re-checks the tags of the counts the page names.
- *Shared conventions that are choices, not bugs* (cards taken by chart play, the count frozen
  in splits, contract split order). Mode B and the moving-count variant measure them.
- *The chart itself*, an input; BasicStrategyTest holds it to ExactRound off the top.
- *Whether each fitted play is the best one.* Not this check's job.
- *Biases below the limits above.*
- *Which of two near-tied counts is better.* Victor APC + ace (+0.472) and Hi-Opt II + ace
  (+0.470) at full play rest only on the main file's paired, widened intervals. P2's paired
  difference converges to the engine's exact difference on the same states (+0.0014), so it
  shows agreement, not order; P3's is between two fixed fits on new shoes, without the fits'
  own noise. The page says so.
- *Same authorship.* Both sides were written by AI agents; the clean-room session, a short
  readable simulator and the planted bugs are the cover.

## 2. Swings, money per hour and risk of ruin

The question: a count wins on average, but how much does a player's bankroll swing, how long
before the average shows, and how big a bankroll keeps the risk of going broke small?

**2.1 The win rate from the engine, the swings from the simulator.** Exact second moments
would be possible for every first move but splits. A split round's square needs the cross terms
between hands sharing one dealer, and the hand-by-hand split valuation that makes 8,8 against a
ten at four hands take about 2 ms instead of minutes rests entirely on "the round is worth the
sum over its hands". A pairwise version would be a new exact algorithm with its own checks, and
revaluing all 60,743 states would cost about 17 CPU hours at the measured ~1 s a state
(StateTimingReport, 28 September) before any of it. Splits are about 2.4% of rounds but 6 to 7%
of E[X²] (*estimate*), so they cannot be left out. And the swings over an hour depend on whole
shoes, which the exact file, one valued state per shoe on average, cannot give.

So **μ, the EV a round, is always the exact engine's**: the same number as the headline table,
from `Entry.estimate[p][metric]` and its 1,000 resamples `Entry.boot[p][metric][r]` in c06c061's
results.ser (and the ceiling's files for its rows). **σ comes from the simulator's mode A run**
(section 1). The page never shows a simulated win rate beside an exact one for the same row; the
simulated μ is used only in the check of 2.4.

**2.2 Rows.** Full play at 1-8 same schedule for Hi-Lo, KO, Zen, Hi-Opt II + ace and Victor
APC + ace at 70, 75 and 80%; F1 and F2 at 80% once section 6.3 has run; chart flat at 80%
(literature); Hi-Lo first decisions at 1-8, 80% (to show the play mode barely moves σ); and
section 3's plans P16, B0,8 and X−1,8 for Hi-Lo and Victor APC + ace at 80%. Each gets at
least 1e8 rounds; all come from the one 2.3e9-round run of section 1.

**2.3 Quantities,** per row, in units of the minimum bet:
1. **μ**, as above.
2. **σ**, the SD of one round's result: √(E[(bX)²] − μ²), b the bet, X the result a unit bet.
3. **σ_eff**, the SD that scales with time. Shoes are independent (a new shuffle, the count
   reset), so a session is a sum of shoe totals: with Y a shoe's total won and N its rounds,
   σ_eff² = Var(Y − μ_sim·N) / E[N]. The page gives σ_eff/σ, which measures how far rounds in
   one shoe are correlated through the count. *Estimate*: within 1% of 1; it is measured, not
   assumed (SE about 0.2% at 1.6M shoes).
4. **Per 100 rounds and per hour.** Per 100 rounds, which needs no assumed pace: EV 100μ, SD
   10σ_eff. Per hour at r = 50, 80 and 100 rounds an hour, PLAN_PAGE.md's three precomputed
   choices, labelled an assumption (no rate is presented as the norm until the literature
   stage cites one): EV per hour μr, SD per hour σ_eff√r, in units.
5. **N0 = σ_eff²/μ²**, rounds until the expected win equals one SD, after which a player is
   still behind about 16% of the time. Credited to Brett Harris in Wattenberger's *Modern
   Blackjack* (qfit.com/book/ModernBlackjackPage518.htm), to be quoted by the literature stage.
6. **Risk of ruin** with a fixed unit and bankroll B units, played forever:
   RoR(B) = exp(−2μB/σ_eff²), and the bankroll for risk p, B_p = −ln(p)·σ_eff²/(2μ), for p = 1,
   5 and 25%. The George C. form ((1−μ/σ)/(1+μ/σ))^(B/σ) agrees to many digits here, since μ/σ
   is about 0.0015. Assumptions stated: independent increments with fixed mean and variance, no
   end to play, steps small next to B, the unit never resized.
7. **Trip risk**, losing B within T rounds, by Brownian first passage:
   P = Φ((−B−μT)/(σ√T)) + e^(−2μB/σ²)·Φ((−B+μT)/(σ√T)).
8. **Chance of being behind** after H hours: Φ(−μ√T/σ_eff), T = H·r, for H = 10, 100, 1,000
   and N0's hours.

Money appears only where PLAN_PAGE.md's section 7 puts it: at $25 a unit, the table minimum
the engine's rules come from, beside the assumptions every such figure rests on (the rounds an
hour, the fitted plays made without a mistake, and, for wonging plans, back-counting the
casino may refuse). There is no table of the unit needed to earn a given amount an hour: it
would present best-case results as a wage.

**Uncertainty.** N0, B_p and the trip risk are computed on each of μ's 1,000 resamples with σ
held, and given as 2.5 and 97.5 percentiles, called percentile intervals. Where a resample has
μ ≤ 0 the upper end reads "no upper bound (the win rate's interval reaches zero)"; a row whose
estimate is ≤ 0 (most of 70%) reads "loses: ruin is certain for any bankroll". σ's own SE (delta
method, from the fourth moment) is one footnote; it is small next to μ's, about ±10% for Hi-Lo
at 80%, full play.

**Expected finding, to be tested, not asserted:** under the same schedule every count bets
nearly the same distribution of bets (E[bet²] = Σw·sameBet², 4.27 to 4.31 across five counts at
80%, *estimate* from report-final's tables; it differs a little by count because each count's
key blocks average the schedule over different ranges), so σ should be nearly the same for all
of them and the risk table is
driven by μ. If it holds, the page says: the bet schedule sets the swings; the count sets the
win rate.

**2.4 How, in code.** The simulator keeps, per row, an exact histogram of long counts keyed by
(bet level, 2X). X includes insurance, so 2X is a whole number in [−17, 16]: an insured round
(−0.5) split to four hands, each doubled and lost (−8), is X = −8.5; four doubled hands won
are +8. The range is derived from the rules in code (hands at most 4, a double 2 units,
insurance half a unit), and every result is asserted to fall inside it, so a wrong bound
fails at once instead of clamping. Bets follow 3.2's convention, so they come from a finite
set of block averages; every moment is then computed exactly at the end. Split rounds are
tagged. Per shoe it keeps ΣY, ΣY², ΣY³, ΣY⁴, ΣN, ΣN², ΣYN, with N counted
as rounds dealt and rounds played apart (for wonging). For Hi-Lo and Victor APC + ace at 80% it
keeps each shoe's (Y, N, m), m the shoe's lowest running total, about 26 MB each, not committed.
New code in this repo: `src/RiskMath.java` (the formulas), `src/RiskReport.java` (the generator:
reads results.ser through FullIndexPeek's reader, refusing a file without `boot`, plus the
simulator's outputs; runs the checks; refuses to write its data file if one fails),
`test/RiskMathTest.java`, `test/RiskReportTest.java`.

**Checks.**
1. *Whole payoff distributions on small shoes.* A test-only `distributionOfFirstMove` in
   BruteForceRound accumulates payoff → probability on the same branch walk; its mean equals
   `valueOfFirstMove` within 1e-12. The simulator's histogram on fixed deals from ~20-card shoes,
   splits included, must pass a chi-square test at 1e7 rounds a case. σ depends on exactly this.
2. *Bets.* The simulator's E[b] and E[b²] equal the scorer's per-key figures (E[b] 1.503 at 80%)
   within the combined SEs, both under 3.2's convention: Σw·b and Σw·b² over the bets actually
   placed, the random play-or-sit of a B plan's threshold block included.
3. *Same μ.* The z test of section 1; if it fails, no risk figure is published.
4. *Formula against simulated ruin.* 20,000 bankroll paths resampled from the (Y, m) shoe records
   at B_25%, B_5% and B_1%, ruined when bankroll + m ≤ 0, stopped where the formula's remaining
   risk is under 5e-5; the simulated RoR (binomial SE about 0.0015 at 5%) is set beside the
   formula at the simulator's own μ and σ_eff, and the measured gap stated. The same for trip risk
   and P(behind), on consecutive blocks of H·r rounds.
5. *Literature.* Flat chart variance against the Wizard of Odds variance page, whose nearest rows
   are for other rules; shown as published figures for other rules, not as a match.
6. *Unit tests.* Trip formula against a seeded Gaussian walk; zero drift gives 2Φ(−B/σ√T); T → ∞
   gives exp(−2μB/σ²); σ_eff = σ on i.i.d. streams and the known ratio on a built correlated one.
   Planted bugs that must fail: σ for σ², r for √r, a dropped μ², a percentile taken before the
   transform, an infinite resample mishandled.

**2.5 What the page gets.** `runs/site/risk-<sha>.json` (every figure with its interval and the
input hashes, the paths and histogram the figures need; within 60 KB) and
`runs/report-risk-<sha>.md` (every check). The site generator renders, from that file only:
tables of swings at 80%, the same at 75 and 70%, per 100 rounds and per hour at the three
rates, behind and trip risk after 10, 100 and 1,000 hours, and the checks; SVG panels of risk
of ruin against bankroll (log scale, simulated ruin points over the formula), a fan of 20
simulated 1,000-hour paths for Hi-Lo at 80% over ±1 and 2 SD bands, and one flat round's payoff
distribution, to say what an SD is. The 20 paths are the first 20 by seed index, a rule fixed
in SIM_CHECK.md before the run and printed in the caption, so none is picked for its look.
There is no calculator: the reader picks among precomputed rates.

**Cost.** The rounds belong to section 1; the accumulators add a few integer adds a round.
Ruin resampling: seconds to a minute a row (*estimate*), timed once and written in the report.
The generator reads the 431 MB results.ser, about a minute and 2 GB of heap (*estimate*). Under
an hour beyond the simulation.

## 3. Wider spreads and wonging

The question: how much more do bigger bet spreads and sitting out bad rounds make, and is
+0.8% a round within reach? The +0.8% is Justin's own question, not a benchmark from the
literature: on 7 October he asked whether there "might be a way to push this to like +0.8 with
side counts, and other strategies" (this project's session d6aafcf8, saved chat). The page
says so in his voice ("I wanted to know whether +0.8% a round is reachable") and treats it as
a question about the game, not a target to plan real play by. *Estimate*, from report-final's
printed Hi-Lo table at 80% (the
same arithmetic gives +0.338 for the own ramp at 1-8 against the published +0.337): own ramp
1-12 about +0.56, 1-16 +0.66, 1-20 +0.70; 1-16 at one unit per 0.125% about +1.48; playing only
at true count +2 or more about +1.14 per round watched. If the engine agrees, most of a +0.8%
comes from the spread, the steepness of the ramp and sitting out, not from the choice of count,
and the page says so.

**3.1 Plans.** A plan's bet on a round whose fitted advantage is a:
`g(a) = 0 if a < enter, else clamp(a/U, 1, cap)`, with an exit rule for the X plans.

| Id | U | Cap | Enter | Exit | Same schedule? | Why |
|---|---|---|---|---|---|---|
| P8 | 0.25% | 8 | none | none | yes | must equal today's 1-8 to the bit |
| P12, P16, P20 | 0.25% | 12/16/20 | none | none | yes | Justin's spreads |
| S16 | 0.125% | 16 | none | none | yes | the 1-16 most players mean: top bet at a 2% edge |
| Bτ,c | 0.25% | 1, 8, 16 | τ ∈ {−0.5, 0, +0.5, +1.0}% | = enter | yes | back-counting, 12 plans |
| Xτ,c | 0.25% | 8, 16 | none | τ ∈ {−1.0, −0.5, 0}% | own rule only | leave and wait for the shuffle, 6 plans |

23 plans. The generator prints each plan's thresholds as each count's keys from its fitted
advantages (for Hi-Lo, *estimate*: enter 0 is TC +2, +0.5 is +3, +1.0 is +4; exit −1.0 is TC −1).

**Why the X plans.** The spreads stage read Loto-Québec's blackjack rules
(casinos.lotoquebec.com/dam/jcr:e6e0f452-a684-44cc-a40a-e29e7045427e/Regles_Blackjack_e.pdf):
the dealer can stop a player who did not play the shoe's first deal, or who skipped a deal, from
betting until the shuffle, or limit them to the minimum on one spot. So back-counting in and
sitting out then returning can be refused at this table; leaving for good is always possible.
The page quotes the rule with its link (the literature stage checks the quote), labels the B
plans "where back-counting is allowed (it may be refused at Casino de Montréal)", and leads with
the X plans. A by-law citation and a forum claim found in search are not verified and stay off.

**3.2 How "same schedule" generalizes.** Today each fold's reference bets are
`clamp(chart/UNIT, 1, s)` on each test state's true chart advantage, and each count hands them
out in the order of its own fitted advantage (SystemScorer.bets and scheduleIntegral). P, S and B
plans are the same construction with g in place of the clamp. g never decreases, so the hand-out
is unchanged, and in every fold and resample every count gets the same bets, the same average
bet and the same share of rounds played. Under wonging each count sits out the same share and
chooses which by its own count: a fair comparison inside the existing framework.

**One bet convention, used by the scorer, the simulator and the page alike.** The scorer gives
every key in a block one fixed bet, the block's average of the schedule over its range
(`sameBet = (integral[b+1] − integral[b]) / blockWeight[b]`), and the simulator plays it.
- *P and S plans:* fixed block averages, as today. E[b] = Σ_b W_b·avg_b and E[b²] = Σ_b
  W_b·avg_b², the second moment of the bets actually placed (not ∫g², the second moment of a
  randomised rule nobody plays).
- *B plans:* the same, except the one block that straddles the entry threshold. A fixed
  average there would be a bet below one unit, which is not a legal bet, so that block is
  played at random: each of its rounds is played with probability π = ∫1[g>0]/W_b, at the
  fixed bet ∫g/∫1[g>0] (at least one unit), and sat out otherwise. Its EV equals the block
  average's; its E[b²] is ∫1[g>0]·(∫g/∫1[g>0])². The simulator draws the same play-or-sit coin
  from its own stream, and the page says that threshold rounds are played at random in that
  share.
- *Own rule, the X plans included:* each key's own bet g(a) on its fitted advantage, fixed, as
  today's own ramp; a key below a threshold sits out whole, so no bet is fractional.

`spreads.json`, the risk figures of section 2 and check 2 of 2.4 all use these E[b²].

Under the **own rule** a count plays when its fitted advantage is at least τ, that is from key
k*(τ). The X plans exist only under the own rule: a reference would need the exact advantage at
every earlier round start. For X, minKey[s] is the lowest bet key over all round starts of state
s's shoe up to s, found by dealing every shoe again from its seed with ShoeRun and ConcreteRound
(report-final dealt all 60,000 again in 1.9 s); the player is still seated iff minKey[s] ≥
k*(τ_exit). The fit is isotonic, so this is exact. A built-in check requires the re-dealt
tallies to equal `st.dealt[s]` at all 60,743 states.

Assumptions on the page: cards flow as heads-up chart rounds whether one plays or not; "per
round watched" assumes every round is seen; plays stay as fitted for flat bets, a slight
underestimate at wide spreads and when wonging, since nothing here refits them for the new bets.

**3.3 Figures per plan, entry and penetration:** same schedule and own rule each give EV per
round watched, average bet, share played and E[bet²]. The generator derives EV per round played
(watched ÷ share) and EV per unit bet (EV ÷ average bet), and always shows the average bet beside
EV per round played: at B0,8 for Hi-Lo about 6% "per round played" is on rounds averaging about
3.9 units (*estimate*); shown alone it misleads.

**3.4 Code, additive, every existing figure the same to the bit.**
- `SystemScorer.Plan` (Serializable, serialVersionUID 1L: U, cap, enter, exit, label) and
  `WIDE_PLANS`. SPREADS, METRICS, scheduleIntegral and every existing index are untouched.
- Entry gains `wide[p][metric]`, `wideBoot[p][row][r]` (same-schedule EV, own EV, own average bet,
  own share for the wonging plans: 81 rows, about 1.9 MB an entry, about 0.22 GB over 117
  entries; float would halve it) and `wideKeys[p][key][plan]`, commented "added after c06c061
  and the ceiling run; null in Results written before". Adding fields under the same
  serialVersionUID is compatible, as `ownValue` and `halfFitSame` showed (StoredResultsTest).
- `bets(...)` takes a trailing `Wide acc`; null is today's path byte for byte. `planIntegral`
  sits beside scheduleIntegral with the same loop and gives ∫g and ∫1[g>0] at every block
  boundary per plan, from which the bets of 3.2's convention, their E[b²] and the threshold
  block's π follow.
- scoreOnce, resamples, ownValueBets, bounds and referenceMetrics pass the accumulator when
  `scorer.wide` is set, so the wide bound rows (perfect first move; perfect full play, from the
  perfect array) and the oracle row come with it, fitted on nothing.
- `WideReport` prints `runs/report-wide-<sha>.md` in the house style (estimates, SE, bias, basic
  intervals where needed, differences from Hi-Lo, max-|t| families per plan and penetration).

Full play is **rescored from c06c061, not refitted**: section 6.1's replay restores every
strategy from fit.ckpt, reruns the folds items' last rounds and scores them with the wide
accumulator. Each folds item re-scores that count's first-decision entries too, so one replay
gives the wide figures for all 31 counts and 8 variants, first decisions and full play.

**Intervals at full play hold the later plays fixed.** The replay's resamples refit first
decisions, insurance and bets, and hold the later-play fit, as every full-play interval does
(FULL_INDEX_PLAYS.md section 4). The ratios that widen them were measured at flat and 1-8 only
(pilot-1417-100: up to 1.07 for differences, 1.80 for gains). At P16, S16 and the B plans the EV
leans harder on high-count rounds, where later plays are fitted on the fewest states, so the
ratios may be larger there. They are measured: FullIndexPilot gains `--wide` and is run again
on the same 1,417 shoes, 100 resamples, printing refitted over held SE for differences and gains
at P16, S16, B0,8 and X−1,8 (about 45 minutes on 6 threads; the pilot took 46 minutes on 5
October). Full-play wide intervals are then widened per plan as section 4 of FULL_INDEX_PLAYS.md
does at 1-8: a measured count takes its own ratio, every other the largest measured for that
plan, marked as not its own. If that pilot cannot run, the 1-8 ratios are applied and labelled
"widened by the 1-8 ratios, an extrapolation". SiteExport writes no full-play wide interval
without one of the two.

**3.5 Checks.**
1. P8 equals `same(8)`, `sameBet(8)`, `own(8)`, `ownBet(8)` to the bit, on fixtures and in every
   item of the real run.
2. With wide on, every existing array is the same to the bit (fixtures), and every rescored item
   equals c06c061's `part-<c>-<p>-folds.ckpt` (all 93 items; section 6.1).
3. A naive reference in the test, expanding each state's weight into quantile pieces in O(n²),
   equals `bets`/`planIntegral` within 1e-12 on random small cases, B, S, X and straddling
   blocks included: E[b], E[b²], share played and π, all under 3.2's convention.
4. Properties: a B plan with enter = −∞ equals P; cap 1 without enter equals flat; share played
   falls as τ rises; same-schedule average bet and share played are equal across counts on every
   resample to 1e-12 relative.
5. Planted bugs, all to be caught: ≥ turned into > at the threshold; enter tested on the chart
   advantage instead of the fitted one; exit on the current key instead of minKey; E[b²] taken
   as ∫g² instead of from the bets placed; a B plan's threshold block given its sub-unit
   average instead of the coin; floor and cap swapped; a block boundary off by one; share
   played integrated on bets.
6. minKey re-deal equality at all 60,743 states.
7. The simulator plays P16, B0,8 and X−1,8 for Hi-Lo and Victor APC + ace at full play, 80%
   (section 1's rows).

**3.6 What the page gets.** WideReport writes `runs/site/spreads-<sha>.json`: plans and
thresholds; per entry, penetration and plan, EV, SE, 95% interval, difference from Hi-Lo with
its interval, average bet, share played, EV per round played, EV per unit bet, E[bet²]; input
hashes. The site generator copies the part the page can switch to into
`card-counting/data/spreads.json` within its 200 KB budget (the top ten counts, Hi-Lo and the
bounds; the full table is a Release asset if Justin wants it) and renders the default view as
static HTML: 80%, full play, the top counts. Figures:
- **W1** EV against spread (1-8 to 1-20, 80%) for Hi-Lo, the best published count with full
  play and the perfect bound, with a second panel per unit bet. A dashed +0.8% line marks
  Justin's question, labelled as his.
- **W2** P16 against S16.
- **W3** EV per round watched against share played, one point per τ, with Montréal's
  exit-only (X) points marked apart. Every own-rule plan, the X plans included, is shown here
  and only here: under the own rule counts play different shares of rounds at different
  average bets, so they are placed on those two axes rather than ranked.
- **W4** a ranking of counts (penetration × plan × play mode) over **same-schedule plans only**
  (P, S and B), where every count gets the same bets and the same share played, so the order
  says which count is better.
- **W5** Hi-Lo's bet by true count under each plan.
- **The +0.8% box**, generated from the data: for each top count, the same-schedule or X plans
  whose 95% lower end of EV **per round watched** clears +0.8%, taken in order of average bet,
  and the first of them shown with its average bet, EV per unit bet and E[bet²] beside it (σ
  where section 2 measured that plan), units spelled out. A B plan is marked "where back-counting is allowed (it may be refused
  at Casino de Montréal)". The box says plainly that a plan clears +0.8% mostly by betting
  more and sitting out more, where that is so (a claim guard tests it against EV per unit
  bet), and if no plan clears it, it says that.

**Cost:** inside section 6.1 (the wide sums add under 2% to scoring, *estimate*), plus minKey
(seconds a count) and WideReport (minutes). Spreads past 1-8 widen the SE because the top bets
fall on rare high counts: about 1.5 to 2 times 1-8's 0.018 at 1-16 (*estimate*).

## 4. The perfect full-play ceiling and the ceiling-count ladder

The question: how good could any count be at this game, and how close do the published counts
and the searched ceiling count come? The answer is a ladder of rows on the same states and
resamples, so every gap has a paired interval.

**4.1 Registration and the one look, as they now stand.** CustomCounts.checkOnMain and the
ledger show anyone holding the repo that step 7 scored the committed custom-counts.json, and
the report prints its blob. They cannot show a stranger that the file was fixed before the
look: commit dates are set on this machine, and custom-count-design has not been pushed. Step 7
has started, so nothing done now can predate it. What is still possible, each needing Justin's
explicit yes: push a tag on 97bf834; stamp the SHA-256 of custom-counts.json and, once written,
of report-ceiling-97bf834.md with OpenTimestamps (only hashes leave the machine), which shows
the registration existed no later than the stamp, not that it preceded the look; or neither. The
page says which, and otherwise that the order rests on the repo's history and ledger.

**The result paragraphs, written blind.** Before anyone opens report-ceiling-97bf834.md, commit
three versions beside the registration: the claim holds (every lower end of F1 or F2 minus its
comparators is above zero); the point gain is positive but the claim is not made; the searched
count scores at or below the best published row. The generator picks one by that rule, which
COUNT_DESIGN.md section 5 ("How a small gain is read") already fixes. The commit's own date is
local, and the page says so.

**4.2 The perfect array, checked after step 7.** SystemScorer.bounds drops the full-play bound
row, without a word, at any penetration where c06c061's perfect array has a NaN, and
FullIndexFit fills that array only for states it visits. After step 7: read the report for the
bound row at all three penetrations, and count NaNs in results.ser (a minute, about 2 GB of
heap, *estimate*; no state file is read, so no look). If one is missing, the replay of section
6.1 computes `perfectValue` for every state again (it walks every state's tree in its last
rounds), checks it against c06c061's values where both exist (1e-12), and the bound row is
scored from the full array. The bound is fitted on nothing and involves no registered count, so
this is not a look; it pairs with step 7's rows through the saved resamples, since resample r is
the same draw everywhere. A refusal of an incomplete array goes into CeilingReport for any later
run, with a test.

**4.3 What "perfect" means and its one gap.** Perfect here means knowing the exact mix of cards
left, not their order. Every decision of the dealt hand is the best for that mix
(`FullIndexFit.perfectValue`: the best first move, then the better of hit and stand at every
later node); insurance is taken exactly when it pays; the same bet schedule every count gets is
handed out in order of each round's own value (`ownValueBets`). By the rearrangement
inequality, no full-play count betting that schedule can beat this line, except through split
hands, which the bound plays by the chart and a count plays by its own tables.

So the full-play bound is shown as a range from B to B + M, M being what better split play could
add. Today's M is crude: 8 × 0.00907% = 0.0726% at 80%, 1-8 (calibration-b99ccbf.md, 890 twin
states, SE of the mean 0.00073%), as if every state were bet 8 units. And δ, a state's gain from
the best split play found, is measured from below: the ascent can stop at a local optimum. Three
changes to SplitMargin:
1. `--out FILE` writes each state's δ and its per-deal gains (the 890-state run again: 2,662 s
   on 7 threads, measured).
2. `rearranged` pairs the sample's δ quantiles (weights 1/q) with the schedule's bet quantiles
   at each penetration and spread, which is the tight OV(δ) instead of S·mean(δ), with a
   bootstrap over the 890 states for its SE.
3. `--restarts K --restart-states N` reruns the ascent from K random split plays on N states (8
   on 40 proposed) and reports the largest gain over the chart-start optimum. This is evidence
   about the local-optimum gap, not a proof.

**Whether to build an exact perfect-split computation: no, with a rule fixed now.** Exhaustive
search over split plays is out of reach (2^11 later settings times about 3^9 × 4 first moves per
pair deal, each needing an exact split valuation). The ascent on every main-file state would
take about 50 hours on 7 threads by arithmetic (60,743 × 2,662 s ÷ 890) and would still be from
below. An upper bound in which split hands see the cards left would need a new valuation path
(RoundPolicy's contract forbids seeing the shoe), its own BruteForceRound cross-check, and bounds
a different, stronger player. Rule: build that upper bound only if M, after tightening, moves
the best full-play row's share of the room (4.4) by more than 5 points.

**A preview on the twin, first.** The full-play choice run's
runs/custom-count/fullplay/choice-20000-38f4e60/results.ser holds a perfect array for 20,320 twin
states at 80%. `BoundRows --perfect FILE --shoes 20000` prints the full-play bound beside F1
(+0.4445%) and F2 (+0.4642%) at 1-8 full play from that same run (BoundRows on the whole twin
took 14 s, measured). Point estimates on a fifth of the twin, labelled so; it sizes M against
the gap before any main-file figure is read.

**4.4 What the page shows.**
- **The ladder**, a chart-table (PLAN_PAGE.md 2.3) drawn by the site generator in 12 views,
  70/75/80% × flat/1-8 × first decisions/full play, the default static and the rest switched,
  in % of a unit a round. Rungs: no count; Hi-Lo; the best plain published row, named (at 80%,
  1-8: Zen, +0.356 first decisions, +0.441 full play); the best published row of any kind,
  named (Victor APC + ace, +0.387, +0.472); F1; F2 (spread cells); F1's own play bet on its own
  value; perfect first move and insurance (on the twin +0.528 at 80%, +0.281 at 75%, +0.079 at
  70%, bounds-d6a005c.txt; the main file's come from step 7); perfect full play, with a hatched
  extension of length M. In the full-play view the first-move bound is drawn faint and labelled
  "applies to first-decision play only". Each rung carries the paired 95% interval of its gap
  from the rung below; one side bar shows the luck every row shares (Hi-Lo's own interval
  half-width, about ±0.035 at 80%, 1-8, from report-final's [+0.260, +0.329]). Full-play gaps
  are widened by the measured difference ratio (FULL_INDEX_PLAYS.md section 4; 1.07 by default,
  not measured for the custom rows); bound rows are fitted on nothing and are not widened.
- **Share of the room closed**, (row − null)/(bound − null), on each resample, as a percentile
  interval; at full play a range, [with B + M, with B].
- **A betting-only view**: Hi-Lo betting only, the best published (+0.187), F1 and F2 betting
  only, and the bound "chart play and perfect insurance, bet on its own value", all on the main
  file (the bound from step 7's saved rows; +0.226 on the twin is not used here). Beside the
  best published betting correlation on the main file (Uston SS, 0.984, report-final) goes R,
  the highest betting correlation a linear count reaches, on the main file too: CeilingReport
  computed it inside step 7's look (`CountRegression.betting` on the states it scored) and
  saved it in report-ceiling-97bf834.ser, read with `CeilingReport.readSaved`, as
  LITERATURE.md 1(d) does. Nothing runs the regression on the main file again
  (`CountRegression.main` refuses it). If the save holds no R, the twin's R (0.98857,
  calibration-b99ccbf) appears only inside `data-file="search"`, labelled, never beside 0.984.
- **Where F1's gap goes**: betting (F1's own-value row less F1) and play (the bound less F1's
  own-value row), which add up exactly.
- **Grain and tags**, from `grainPart`, with the failed one-sided grain check named: doubling g
  costs F1 0.0031%, above the 0.0012% noise floor, while no finer grain gains.
- **Registration and the look**: commit, blob, SHA-256, any outside stamp, every ledger line
  verbatim, and one paragraph on what this proves and what it does not.
- **Optimism**, (row − Zen) on the twin less the same on the main file: the 74 published rows in
  grey (twin values from calibration-b99ccbf.md.parts/02-proxy.properties, main from c06c061;
  73 of 74 within 2 SE there), F1, F2 and F1 flat in colour; SE √2 × the main file's paired SE,
  the approximation the proxy check tested.
- **Two learning curves, named apart**: search shortfall (F1 less the mean of the two half-twin
  counts on the main file; "the twin was big enough" if the 95% upper end is under 0.01%,
  COUNT_DESIGN.md section 4) and fitting shortfall (each custom row's half-fit change).
- **Claims table**, registered (count, cell) pairs only; every other cell "scored, not claimed".
- **The tags**: the ten rank tags of Hi-Lo, Zen, F1, F2 and the effects of removal, recomputed
  from custom-counts.json, with the angles between them (twin: F1 8.4° from the effects of
  removal, 9.3° from Zen, 16.4° from Hi-Lo).
- **How we know the search found the top**, collapsed: restarts, slices, every start beaten, a
  planted optimum recovered (CountSearchTest), the half counts 0.9° apart, the grain check. The
  generator gets these from SearchReport's committed methods on the search logs, not from
  markdown.

**4.5 Code.** All of it reads saved files or the twin, never the main state file:
- `src/CeilingResults.java`: `full` writes runs/report-ceiling-full-<sha>.md, pairing c06c061's
  results.ser, step 8's results.ser and the bound rows step 7 saved, widening the full-play gaps
  and reusing CeilingReport's `claim`, `floor` and `row`; `optimism` scores the registered rows on
  the twin at three penetrations, point estimates only (runs/custom-count/optimism-<sha>.md and
  .properties); `data` writes `runs/site/ceiling-<sha>.json` (every figure at full precision
  with its source path, the source's SHA-256 and the commit) and nothing else. The ladder, the
  betting-only view, the gap, grain, optimism and tag figures are rendered by SiteBuild and
  SiteSvg from that file like every other figure, so there is one ladder renderer and every
  number on them is a `data-k` span the build checks; a reader or a crawler gets them as table
  text without JavaScript (PLAN_PAGE.md 2.3).
- SplitMargin's three changes (4.3); `BoundRows --perfect FILE --shoes N`; the refusal of 4.2.

**4.6 Checks.**
1. *Data equals reports.* `data` reprints the ladder and claim lines with CeilingReport's own
   formatting and diffs them with report-ceiling-97bf834.md, as `checkPrinted` does; the same for
   full-play rows against peek-c06c061-final.txt and the `full` report. Any difference fails.
2. *The perfect array computed twice.* Step 8's results.ser holds its own, from other classes; it
   must equal c06c061's on every state to 1e-12 (and the replay's, if 4.2 needed it).
3. *Hi-Lo at full play* in step 8 equals c06c061 within 1e-12 (already in the design).
4. *Bound order.* perfectPlay[s] ≥ st.perfect[s] on every state; the full-play bound ≥ the
   first-move bound on every resample in every cell.
5. *Rows under the bound.* Every full-play row ≤ B + S·mean(δ) on every resample, with the
   largest restart gain added to δ. A row above it stops the page for that row until explained:
   it means a scoring bug or split gains the ascent missed. Rows between B and B + M are listed
   by name.
6. *The twin ties to the search.* `optimism`'s F1 and F2 at 80% equal the search logs' objective
   to the bit (+0.3912, +0.4187).
7. *Unit tests* on synthetic Results: ladder order, paired gaps, share of the room per resample,
   widening, the paragraph chooser. Planted bugs that must fail: F2 scored by its play vector
   instead of its betting variant (a real trap: FullIndexRun's summary used to print F2 that
   way); the first-move bound used as the full-play denominator. SplitMarginTest gains: the
   rearranged margin ≤ S·mean δ; it equals a brute-force sort on a tiny set; a restart never ends
   below the chart's start.

**4.7 Cost.**

| Item | Machine time | Basis |
|---|---|---|
| Step 7 | about 3 to 4 h on 7 threads, not resumable | estimate (COUNT_DESIGN.md section 7); started 19:16, 9 October |
| Step 8 | 4 to 8 h with c06c061's tree store read-only | estimate (same) |
| NaN check of the perfect array | about 1 min, 1 thread | estimate |
| Twin preview (`BoundRows --perfect`) | under 1 min | BoundRows on the whole twin: 14 s, measured |
| `optimism` | 1 to 2 min, about 2 GB | 0.93 s an evaluation, measured, × about 24 |
| `full` and `data` | about 2 min, about 4 GB | estimate (reading 431 MB) |
| SplitMargin with `--out` | about 45 min on 7 threads | 2,662 s for 890 states, measured |
| Restarts, 8 × 40 states | 0.5 to 1.5 h on 7 threads | estimate |
| Not done: the ascent on every state | about 50 h on 7 threads | arithmetic |

Beyond steps 7 and 8 this adds 2 to 3 machine hours, each piece short enough to run again whole.

## 5. The edge at each count

The question: at each value of a count, what is the player's average result a round? This is
the figure that explains why counting works, and the one a reader can turn into their own bets.

**5.1 What exists.** `scoreOnce(..., detail=true)` fills `Entry.keys[p]` through `keyTable`, one
row per bet key pooled over both test folds: key, share of rounds (weight 1/q), mean test round
value (`play[j] + ins[j]`, insurance included), the isotonic fitted advantage, and the bets at
1-8. report-final prints these for 39 entries × 3 penetrations; full-play entries carry the same
tables in c06c061's results.ser (checked in section 0). Its "Mean RV" column is the out-of-sample
edge (Hi-Lo at 80%: TC 0 −0.615, TC +4 +1.518); the "fitted advantage" column is a betting fit
and is not shown as the edge. **No per-key uncertainty is stored anywhere**: `Entry.boot` holds
only the 51 row figures, and `shoeSums` pools keys. Intervals need per-state values, so they are
recomputed.

**5.2 What is computed,** for every row (null count, 31 counts, 8 ace variants, and the
registered rows F1, F1 flat, F2 with its betting vector, F1r, F1r as a true count, the controls
at g), at each penetration, under each play layer:

| Layer | Per-state value | Source |
|---|---|---|
| chart | basic strategy, no insurance | `ScoringStates.chart` |
| chart + insurance | chart and the count's insurance | recomputed |
| first | the count's fitted first move and insurance | recomputed |
| full | full play and insurance | replay (6.1, 6.3) |
| perfect first | best first move and perfect insurance | `st.perfect + st.perfectInsurance` |
| perfect full | perfect play of the dealt hand and perfect insurance | the perfect array + `st.perfectInsurance` |

F1 flat and F1r have no full-play rows (step 8 does not play them); the page shows them at first
decisions and says so.

Per key k: its share of rounds with an interval; the edge Σw·rv/Σw with SE and 95% interval; the
part of it from insurance; the number of valued states and n_eff = (Σw)²/Σw²; and the 10th and
90th percentiles of state edges within the key, the spread of shoes the count cannot tell apart.
- **Key axis.** The book's own: key/scale for catalog counts (halving Wong Halves and C-R).
  Running counts key on the running count from the shuffle, their pivot derived from `deckSum`
  (KO's key 32 is a drift-free true count of exactly +4 at every depth; Red Seven's pivot is 16).
- **Hi-Lo-equivalent position** for true counts: key × √((10/13)/spread of the bet tags), by a
  new `CountKeys.betSpread` (Hi-Opt II ×0.598, Zen ×0.559 by arithmetic from their tags).
- **Custom counts** at grain 1/20 are pooled into whole Hi-Lo steps with
  `GranularCount.roundToGrain(k·D/K, 1)`, exactly (ΣW, ΣW·edge); with ties toward zero, bin 0
  holds 21 keys and every other 20. A quarter-step version goes into the CSV only.
- **Tails** are pooled by a rule on weights alone, fixed in code before any run: a key stands
  alone when n_eff ≥ 100, the rest pool into "≤ k" and "≥ k". The CSV keeps every key, flagged.

**Comparing counts without their scales:** each count's own key order ranks rounds; a straddling
key contributes its mean prorated by weight, as scheduleIntegral splits blocks.
- *Top-q edge*: the mean edge over the share q of rounds the count rates highest, q = 0.5, 1, 2,
  5, 10, 25, 50, 100%; at 100% it must equal the flat figure.
- *Bands* by percentile: 0–50, 50–75, 75–90, 90–95, 95–98, 98–99, 99–99.5, 99.5–100.
- *The oracle*: states ranked by their own value under the layer's perfect play, the most any
  ranking can reach (it matches section 4's "bet on its own value" rows).
- *Share of rounds with a positive edge*, under chart play so skill is held equal: (a) states
  whose own chart edge is above 0, (b) keys whose mean chart edge is above 0.
- *Derived, each with an interval*: Hi-Lo's slope per true count (weighted least squares over
  keys −3 to +6), the break-even key, the edge at each running count's pivot.
- *Depth*: Hi-Lo's first-decision edge per key and decks-left band (8–6, 6–4, 4–2, 2–1.6), the
  "floating advantage", from per-state depth already stored.

**5.3 How, in code.** New class `KeyEdges`, with tests. Its code needs CustomCounts.lookOnMain,
so it is written on a base with 97bf834 merged into results-site; runs C and D happen in the
page look of 6.3, after custom-count-design is merged again through step 8's ledger commit.
1. **A read-only sink.** `scorer.stateSink`, null by default; when set, `scoreOnce(detail=true)`
   hands it, per entry and fold, the test states' `play[j]`, `ins[j]` and `bk.index[j]`. It is
   only called on the point-estimate pass, so it fits inside a run that also does resamples. A
   second hook, `scorer.resampleSink`, null by default and used only in run A′, hands it the same
   per-key sums on each resample, under that resample's refitted plays and its multiplicities. A
   test holds Results the same to the bit with either sink on and off.
2. **Per-key bootstrap with plays held.** `new Bootstrap(60000, 1000, Bootstrap.SEED)`, the same
   draws as every published interval, so resample r pairs with `Entry.boot[...][r]`. On each
   resample the values are held and only the weights move: per-key sums, top-q boundaries,
   bands; about 5 × 10⁷ multiply-adds per row, penetration and layer.
3. **Widening, measured where the noise is**, the project's practice (FULL_INDEX_PLAYS.md
   section 4) applied per band of keys. A whole-row ratio, refitted over held SE of the flat EV,
   would be close to 1: the flat EV's SE is mostly shoe luck (chart flat about 0.0055%). The
   noise from refitting the plays sits where play groups are thin, the high keys (true count +4
   and up), which are the keys E1, the slope and the break-even key depend on. So:
   - *Five counts refitted outright.* Run A′ (below) scores Hi-Lo, KO, Zen, Hi-Opt II + ace and
     Victor APC + ace at 80% at first decisions on all 1,000 resamples with plays refitted, the
     published bootstrap's own path, with the resample sink on. Their per-key, top-q, band and
     difference-from-Hi-Lo intervals are refitted ones, like every published interval. Hi-Lo's
     slope, break-even key and the pivot edges are computed on each refitted resample directly.
   - *Every other first-decision row* is widened with `Bootstrap.widen` by ρ_band, refitted over
     held SE measured on those five, per band of Hi-Lo-equivalent position (up to 0, +1 to +3,
     +4 and up) and per q for top-q, the largest of the five, applied as max(1, ρ_band) and
     marked "widened by a ratio measured on other counts". Differences from Hi-Lo take the
     largest band ratio of the five's differences, which matters because holding play
     understated a difference's SE 2 to 3 times (KO: 0.011% held, 0.034% refitted,
     COUNTING_COMPARISON.md section 6).
   - *Full play:* later plays cannot be refitted per resample at all. Full-play per-key
     intervals take the first-decision band ratio and are captioned "later plays held fixed;
     their fitting uncertainty is not included"; full-play gains are widened by
     `Widening.of(...).gain`. Perfect layers are fitted on nothing and are not widened.
   The T2 table also gives `Bootstrap.maxT` intervals across counts. Per-key intervals are not
   simultaneous across about 30 keys, and the caption says so.

**Runs**, folded into the shared runs of section 6 so no state file is read more than it must be:
- **A**, catalog counts at first decisions, alone: `scorer.score(count, bettingVariant(count),
  null, {p}, 1)` with the sink on and no resamples. About 5 to 10 minutes on 1 to 2 threads, 4 GB
  (*estimate*: states read in 2.2 s in c06c061's log; 0.93 s an F1 evaluation, measured). It is
  also the first real-file test of the sink, and runs before the long replay as a gate.
- **A′**, the five counts of item 3 at 80% at first decisions, 1,000 resamples with plays
  refitted and the resample sink on: about 15 to 25 minutes on 7 threads (*estimate*:
  report-final's per-count times, 2,771 to 4,577 thread-seconds a count for three penetrations
  and their variants). It runs right after A.
- **B**, catalog counts at full play: inside the replay of section 6.1.
- **C and D**, the registered counts at first decisions and full play: inside the look of 6.3.

**5.4 Checks** (each failure stops that row's intervals; its point tables still come from the
saved Results, with the reason stated):
1. Recomputed `keys[p]` and `estimate[p][*]` equal c06c061's for all 39 first-decision rows, the
   betting-only rows, and (B) all 39 full-play rows; C equals report-ceiling-97bf834.ser and D
   step 8's results.ser; B's first-decision per-state values equal A's; A′'s row figures on
   every resample equal `Entry.boot[p][metric][r]` of the same five entries in c06c061's
   results.ser within 1e-12, so the refitted per-key intervals come from the published
   bootstrap and no other.
2. Σ_k share × edge = flat, and top-q at 100% = flat, to 1e-12 relative; shares sum to 1.
3. An ace variant's per-state values equal its base count's; only its keys differ.
4. The null count has one key, whose edge is its flat figure.
5. Every key with n_eff ≥ 100 has weight in at least 99.9% of resamples.
6. No NaN in the perfect array at any penetration (4.2).
7. Rebinning conserves weight; F1's and F2's bet-tag spreads are 10/13 to rounding.
8. Unit tests: tables from sink data equal `keyTable` to the bit; top-q on a three-key toy worked
   by hand; a held resample's per-key mean equals the mean on the data with each drawn shoe
   actually repeated (as SystemScorerTest checks the bootstrap); the rebin tie rule; a tiny
   FullIndexRun whose replay reproduces its own full-play keys; planted bugs (fold swap, key off
   by one, prorating at the wrong boundary), each failing a test.
9. Reported, not pass/fail: A against report-final's printed tables at their 3 decimals; the
   simulator's mean result by Hi-Lo true count (`ev-by-key.tsv`, section 1) against these edges,
   on the same key convention (nearest, ties toward zero, exact cards left).

**5.5 What the page gets.** KeyEdges writes `runs/site/key-edges-<sha>.json` with every row,
layer and key (key, book key, HL-equivalent position, share, edge, interval and how it was
widened, n_eff, tail flag) and the input hashes. The site generator copies into
`card-counting/data/key-edges-{70,75,80}.json` what the page can switch to, within 150 KB
each (tails pooled, intervals at four digits), the 80% file loaded first. `key-edges.csv`
(about 2 MB) and the full-detail CSV stay in runs/, Release assets only if Justin says so.
Figures: **E1** Hi-Lo's edge at each true count at 80%, one line per layer,
with a share-of-rounds strip ("1 in N rounds"), switchable to any count; **E2** the edge in the
rounds each count rates best (top-q, log axis) for the null count, Hi-Lo, KO, Zen, Wong Halves,
Hi-Opt II + ace, Victor APC + ace, F1, F2 and the oracle; **E3** Hi-Lo by depth; **E4** F1 and
F2 in Hi-Lo-equivalent steps over Hi-Lo and Zen (true counts only). Tables: Hi-Lo per key at
80%, all layers; top 1/5/10/25% edge and positive-edge share for every row with the widened
difference from Hi-Lo. Hooks for the literature stage, which fetches and quotes each before any
is called checked: "about 0.5% per true count" against the fitted slope (*estimate* from
report-final, 0.555% a step over keys 0 to +5); the break-even count; the floating advantage;
KO's pivot; Red Seven's pivot; true-count frequency tables (books often truncate or floor where
this study rounds); Griffin's betting correlation.

**Cost:** A, 5 to 10 minutes; A′, 15 to 25 minutes; B and D, inside 6.1 and 6.3; C, 2 to 3
minutes (18 counts × 3 penetrations at about 0.93 s, measured); per-state arrays 150 to 200 MB,
kept local. If Justin wants every catalog row's per-key intervals refitted instead of widened by
the five counts' band ratios: one more catalog bootstrap at first decisions with the resample
sink, about 5.2 machine hours (report-final's own timer, 18,623.7 s on 7 threads; 13.6 h on the
wall last time); full play cannot be refitted per resample at all.

## 6. Shared runs: one replay, one look, one simulator in two parts

Three proposals each wanted to replay c06c061's full-play strategies: the wide rescore (3.4),
the per-key run B (5.3) and the simulator's full-play export (1.2). Each replay reruns the last
rounds over the 14.9 GB tree store, so they are one run here. Likewise every later scoring of
the registered counts is one look, not three.

**6.1 The replay of c06c061.** A new mode,
`FullIndexRun --fit-from runs/full-index/full-c06c061 --folds-only --trees-read-only
runs/full-index/full-c06c061/trees.bin --wide --sink DIR --export DIR`, which:
- checks c06c061's run.id apart from its commit line, and refuses if its "count …" lines differ
  from the plan FullIndexFit.plan makes for the same 31 counts;
- restores every strategy with `FullIndexCheckpoint.readFit`, writing nothing in the source
  directory, and skips the passes (fit.ckpt is marked fitted);
- runs only the folds items: per batch the last round, then scoring with 1,000 resamples, the
  wide accumulator on, the sink on for the point pass, and the export hook writing each exported
  row's full-play strategy files and per-state values;
- compares every scored item with c06c061's `part-<c>-<p>-folds.ckpt` on every existing array
  (estimates and resamples of the first-decision and full-play entries, shoeSums, key tables,
  halfFit*, the resolution): `sameBits` first, and the project's standard for a full-play result
  rerun in another run, 1e-12 relative (FULL_INDEX_PLAYS.md section 3; COUNT_DESIGN.md's test
  of step 8's Hi-Lo), as the fallback fixed now, before anything runs. An item that differs in
  the last bits but within 1e-12 is reported, with its largest difference, and the run goes on;
  the page's existing figures still come from c06c061's file, so nothing printed changes. An
  item beyond 1e-12 stops the run, naming it;
- computes `perfectValue` again for every state it walks and checks it against c06c061's array
  (4.2);
- keeps a checkpoint per item in its own directory with its own run.id, written whole, forced to
  disk and renamed as FullIndexCheckpoint does. Each item's checkpoint holds everything that
  item produced: its wide arrays, its sink output (the per-state arrays) and its export part.
  So a crash or a kill loses at most the item in hand (5 to 8 minutes) and nothing already
  scored; a sleep only pauses the JVM. At the end it assembles wide.ser (plans, entry names,
  wide arrays, the source's identity and fit.ckpt's SHA-256), the per-state arrays and the
  export from the checkpoints. A test kills a fixture replay mid-run, resumes it, and requires
  wide.ser, the per-state arrays and the export to equal an uninterrupted run's to the bit.

The existing figures keep coming from c06c061's results.ser; the replay only adds. The tree store
must be opened read-only: TreeStore deletes a store whose identity differs from its own, and
`--trees-read-only` is the mode that never does (COUNT_DESIGN.md section 7, "A hazard").

**Gate 0**, before the whole run: replay Hi-Lo at 70% only and compare (about 10 minutes,
*estimate*: c06c061's 70% folds items took 250 to 300 s plus a last round of 100 to 150 s). If
fit.ckpt does not read under the new classes, or the replayed item differs by more than
1e-12, nothing longer starts. A difference within 1e-12 is written down and the run goes ahead
on the fallback above.

**Cost.** c06c061 spent 7,560 s on last rounds and 38,717 s on scoring with 1,000 resamples over
its 186 items, folds and halves (its log, 43 batches, summed for this plan); the spreads stage
summed the 93 folds items' checkpoint times to 39,278 s. So, on 6 threads with -Xmx10g (its
largest batch held 5.7 GB):

| Option | Wall time | What it gives |
|---|---|---|
| All 31 counts, 1,000 resamples | **about 10 to 11 h** | wide intervals with first decisions, insurance and bets refitted on each resample and later plays held, like the published full-play 1-8 intervals, then widened as 3.4 says |
| The top ten counts, 1,000 resamples | about 3.5 h (proportional estimate) | the same for ten rows; the rest at points only |
| All 31, no resamples; wide intervals held and widened as in 5.3 | about 1 to 1.5 h (estimate) | a different interval method from the published 1-8 |

The default is the first, run overnight; section 8 asks Justin. Whichever is chosen, a
full-play wide or wonging interval holds the later plays fixed, and the page says so beside it.

**6.2 The simulator in two parts.** SIM_CHECK.md, with every row of 1.3, the strategy instances
each check plays, the pass rule, the planted bugs, each part's machine-hour budget, the rule
that scales run sizes to it and the SEs that rule implies, the rule that picks the fan chart's
20 paths, and SHOE_SPEC.md's commit, is committed before the first pilot, so no row, size or
threshold is chosen after a result is seen.
- **Part 1**, before the replay: chart, null count and first-decision rows (21 instances and
  the chart), exported by refitting (minutes a count), with D1 to D3 and the lookups. It catches
  a simulator or format bug before the 11-hour replay. Pilot as a gate, then about 0.5 to 1.25
  h (*estimate*); budget 1.5 machine hours.
- **Part 2**, after 6.1 and 6.3: the 34 full-play instances, wide-plan and registered rows,
  mode B, the moving count, planted bugs. Pilot as a gate, then about 2.5 to 4 h (*estimate*);
  budget 4.5 machine hours.
Part 1's summary is committed before part 2 starts; the pass rule applies to the union.

**6.3 The page look at the registered counts.** One tool run, after steps 7 and 8 have exited
cleanly and their ledger lines are committed, through `CustomCounts.lookOnMain` with one ledger
line ("page figures: wide plans, per-key tables, simulator export"). An interrupted look is
finished by `PageLook --resume`, with no second line (COUNT_DESIGN.md section 5).

**The ledger has one home.** lookOnMain appends to the custom-count-ledger.tsv beside the
counts file it is given, and checkOnMain checks that ledger only, so a look pointed at another
copy of custom-counts.json would start a second ledger and pass every check. The one home is
the count-design worktree's ledger on custom-count-design, which holds step 7's line (16df265)
and will hold step 8's. The page look is given that worktree's custom-counts.json and
custom-counts-fullplay.json by absolute path, as steps 7 and 8 were, and its line is committed
there. Before the look, custom-count-design is merged into results-site through step 8's
ledger commit, not only 97bf834; after it, through the page look's ledger commit. The site
generator refuses to print the ledger unless the copy it reads holds step 7's line, step 8's
and the page look's, each with the registration's blob, and equals the count-design file byte
for byte.

The run:
- first decisions: the registered rows of step 7 scored again with the sink (5.3's run C),
  the export hook for F1 and F2, and the wide accumulator, with 1,000 resamples for F1, F1
  flat and F2 only (about 30 minutes, *estimate*: 3 × 4,076 s ÷ 7 threads) and points for the
  rest (2 to 3 minutes);
- full play: step 8's fit.ckpt replayed as in 6.1, one item first as a gate (5.3's run D), for
  F1, F2, Hi-Lo at its own grain, Zen at g and Hi-Opt II at g, with the sink, the export hook
  for F1 and F2, and wide resamples for F1 and F2 only (about 1 to 1.5 h, *estimate*: c06c061's
  folds items averaged about 420 s with resamples, and run D's last rounds 15 to 25 minutes),
  reading c06c061's trees read-only.

Its point estimates of every registered figure must equal step 7's .ser and step 8's results.ser
to the bit before it writes anything. It cannot change which of the three result paragraphs the
page prints: the chooser reads step 7's and step 8's outputs only, and the page says the claims
rest on those and that this line only added descriptive figures.

## 7. Order of work

Steps 7 and 8 hold the machine first. After them, short runs that can find a bug cheaply come
before the one long run, so a mistake in the sink, the export format or the replay costs minutes,
not a night. Each line ends in a commit before the next starts. Times are machine time while
awake, estimates unless marked.

**7.1 While steps 7 and 8 run** (step 7 from 19:16 on 9 October, about 3 to 4 h; step 8 about 4
to 8 h, started only once step 7's process has exited cleanly and its .md and .ser are
written, not when its ledger line is committed, which happened at 19:37 while it ran). Code and
fixture tests only: single-threaded test classes at `nice -n 15` with `-Xmx1g` or less, never
the whole suite beside step 7 (it holds -Xmx12g of 16 GB, and dying would cost a rerun and a
second ledger line). Each item lands on a branch off results-site with 97bf834 merged in for
the code, with its tests and planted bugs (custom-count-design is merged again through step
8's ledger commit before row 6 of 7.2, as 6.3 says):
1. **The simulator**, in a separate clean-room session (1.1): `sim/`, D1 on a few shoes it can
   check without the bundle, its unit tests. Nothing it needs comes from a run.
2. **SHOE_SPEC.md and ShoeSpecTest**, committed before item 1's session starts (1.1); then
   **StrategyExport**, its reader and the round trip on a fixture states file;
   `keys-sample.tsv`.
3. **Wide plans** in SystemScorer (3.4), the bet convention of 3.2, the naive reference test,
   the planted bugs; WideReport; FullIndexPilot's `--wide`.
4. **The sinks and KeyEdges** (5.3), with the on/off bit tests.
5. **The replay mode** of FullIndexRun (6.1), tested on a tiny fixture run that replays its own
   checkpoints to the bit, as StoredResultsTest reads a part, and the kill-and-resume test.
6. **RiskMath and RiskReport** (2.4), and BruteForceRound's `distributionOfFirstMove` test.
7. **CeilingResults**, SplitMargin's three changes, `BoundRows --perfect`, the refusal of 4.2.
8. **SIM_CHECK.md**, drafted with its instances, budgets, scaling rule and path rule, so only
   the pilot's measured speeds and SDs remain to fill in.
9. **The three result paragraphs** (4.1), committed before anyone opens
   report-ceiling-97bf834.md. This is the only time-critical item.
10. The literature stage fetches and quotes sources (no machine load).

A queue script like runs/custom-count/search/queue.sh could chain step 7 and step 8 so the
machine is not idle overnight; Justin's call (section 8). Step 7 was started from its own
shell, so a later script cannot read its exit status. It waits for pid 42127 to end, then
requires report-ceiling-97bf834.ser to read back whole with `CeilingReport.readSaved` (step 7
saves everything before it prints), report-ceiling-97bf834.md to be non-empty, and the .log to
hold no exception; only then does it start step 8. It never treats the ledger commit as the
signal.

**7.2 After step 8.**

| # | Run | Machine time | Needs | Gives |
|---|---|---|---|---|
| 1 | Checks: NaNs in the perfect arrays, step 8 against c06c061 (perfect array, Hi-Lo), twin preview, `optimism`, CeilingResults `full` | about 15 min | step 8 | ladder figures, 4.6 checks 2, 3, 6 |
| 2 | KeyEdges runs A and A′ | 20 to 35 min | 7.1 item 4 | catalog per-key tables; the sink's first real test; the five refitted counts and the band ratios |
| 3 | FullIndexPilot `--wide` on the 1,417-shoe pilot | about 45 min | 7.1 item 3 | widening ratios at P16, S16, B0,8, X−1,8 (3.4) |
| 4 | Replay gate 0 | about 10 min | 7.1 item 5 | go or stop for row 6 |
| 5 | Simulator part 1: export, pilot as a gate, run | 0.5 to 1.25 h, budget 1.5 h | rows 1, 2; 7.1 items 1, 2, 8 | D1–D3; first-decision rows |
| 6 | **Replay of c06c061** (6.1) | **10 to 11 h, overnight** | row 4 passed | wide.ser; run B; full-play export; perfect array again |
| 7 | Page look (6.3) | 1.5 to 2 h | row 6's classes; steps 7 and 8 exited cleanly; custom-count-design merged through step 8's ledger commit | registered rows' wide, per-key and export |
| 8 | Simulator part 2: pilot as a gate, run | 2.5 to 4 h, budget 4.5 h | rows 5 to 7 | full-play, wide and registered rows; mode B; planted bugs |
| 9 | SplitMargin `--out`, `rearranged`, restarts (twin) | 1.25 to 2.25 h | 7.1 item 7 | M, tight, and the restart evidence |
| 10 | RiskReport, WideReport, KeyEdges reports, CeilingResults `data`, the simulator's reader | under 1 h | rows 1 to 9 | the JSON files in runs/site/ that SiteBuild and SiteSvg render |

Row 9 has no dependencies on the others and can move to any daytime gap. After row 8, nothing
needs trees.bin; deleting it frees 14.9 GB and is Justin's call.

**Totals.** Steps 7 and 8: about 7 to 12 h. After them: about 18 to 23 h with the full replay
(the rows above summed), about 11 to 16 h with the top-ten replay, about 9 to 14 h with held
wide intervals. Allowing for sleep and for someone to start each gated row, about three to four
days on the wall from the end of step 8 (estimate). The critical path is row 6; everything
that can fail fast comes before it.

**What never runs together.** Steps 7 and 8 (-Xmx12g), the replay and the page look (-Xmx10g),
the wide pilot and run A′ (6 to 7 threads), and the simulator's main runs (6 threads) each take
the machine alone. Short single-threaded reads (row 1 and run A) can sit beside nothing heavier
than a test run.

## 8. Decisions for Justin

Each has a default this plan follows unless he says otherwise.
1. **Chain steps 7 and 8 by script** (start step 8 as soon as step 7's process has ended and its
   outputs check out as 7.1 says; step 7's ledger line is already committed), or wait for
   someone at the machine. Default: chain, if he says yes.
2. **An outside record of the registration** (4.1): push a tag on 97bf834, stamp hashes with
   OpenTimestamps, or neither. Default: neither, and the page says the order rests on the repo's
   history. Either of the first two sends something off the machine and needs his explicit yes.
3. **Wide-plan intervals** (6.1): resampled with first decisions, insurance and bets refitted
   and later plays held, for all 31 counts (10 to 11 h, the default), for the top ten (3.5 h),
   or held and widened (1 to 1.5 h); full-play ones widened by ratios the wide pilot measures
   (3.4, about 45 minutes, default) or by the 1-8 ratios, labelled an extrapolation.
4. **Wide rows for the registered counts** (6.3): F1, F1 flat and F2 (default), or none, which
   saves about 1.5 h and leaves the ceiling count out of the spread figures.
5. **Per-key intervals** (5.3): refitted for five counts at 80% and widened by their band ratios
   for every other row (default, 15 to 25 minutes), or refitted for every catalog row at first
   decisions for about 5.2 more machine hours.
6. **Tail pooling** at n_eff ≥ 100 (default).
7. **Plans 1-20 and S16** in (default; they cost nothing extra) or out.
8. **An exact upper bound on split play** (4.3): only if the rule there says so (default: no).
9. **What becomes a Release asset**, decided with the publishing plan, nothing pushed without his
   yes on an exact list: the simulator's bundle (size measured when written, *estimate* 50 to
   120 MB) and run summaries are natural candidates, as are `key-edges.csv` (about 2 MB) and
   the full spreads table; the per-state key-edge arrays (150 to 200 MB) and the simulator's
   per-shoe records (about 52 MB) default to staying local.
10. **Deleting trees.bin** (14.9 GB) once row 8 of 7.2 is done: his call; it can be rebuilt in
    about 2.5 hours of round 0 (9,096 s, measured) if ever needed.
11. **The +0.8% question on the page** (3.6): shown as his own question, in his words, with the
    dashed line in W1 and the box (default), or left off. It came from his message of 7 October;
    the page never presents it as a target or a wage.

## 9. What this plan does not do

- It does not prove the code free of bugs. It narrows where one could hide: an exact engine held
  to brute force on small shoes, an independent simulator agreeing within stated limits, planted
  bugs that the checks catch, figures reproduced to the bit across runs. The page says how large
  a bias each check would catch 90% and 95% of the time; a smaller one may pass.
- It does not refit plays for wider spreads or wonging. Those figures are a slight underestimate,
  and the page says so. Nor does any full-play interval refit the later plays per resample; the
  widening ratios stand in for that, and the page says which were measured and which borrowed.
- It does not separate near ties: Victor APC + ace and Hi-Opt II + ace at full play rest only on
  the main file's paired, widened intervals and are called a tie unless those separate them. No
  simulated difference is used to order them.
- It does not change steps 7 and 8, or choose anything about the ceiling count after its look.
- It does not quote SCORE or published Kelly-style figures, which assume ramps other than the
  fixed same schedule; DI (1000μ/σ) is in the JSON only, for readers who want it.
- It does not cite a literature figure as checked until the literature stage has fetched and
  quoted it: the Wizard of Odds' house edge and variance tables, *Blackjack Attack*'s ruin
  formulas and page numbers, rounds-per-hour norms, "about 0.5% per true count", the KO and Red
  Seven pivots, and the Loto-Québec rule quoted in 3.1.
- It does not push, publish or delete anything. Every file that would leave the machine is listed
  for Justin, by name and size, before he decides.

## Paths

Read for this plan (9 October): this worktree's COUNT_DESIGN.md, FULL_INDEX_PLAYS.md,
COUNTING_COMPARISON.md, ROUND_CONTRACT.md, src/ShoeRun.java, src/CountKeys.java,
src/GranularCount.java, src/SystemScorer.java, src/FullIndexFit.java, src/CeilingReport.java;
.worktrees/count-design's COUNT_DESIGN.md sections 7 and 8, src/CustomCounts.java and the
registration commit 97bf834; runs/report-final-26f0724.md (header), runs/full-index/full-c06c061.log,
runs/full-index/full-c06c061/ (listing), runs/custom-count/bounds-d6a005c.txt,
runs/custom-count/calibration-b99ccbf.md (split margin). For its revision the same day:
FULL_INDEX_PLAYS.md sections 3 and 4, runs/full-index/pilot-1417-100.txt and
peek-c06c061-final.txt, PLAN_PAGE.md sections 2, 5 and 6, LITERATURE.md 1(d),
counting-systems.json's `_about`, SystemScorer.bets and scheduleIntegral, CountKeys.playKey,
ShoeRun's shuffle and valuation draw, CustomCounts.lookOnMain and ledgerOf, the count-design
ledger and commit 16df265, the process list (step 7, pid 42127), report-final's timing table,
and Justin's message of 7 October on +0.8% (chat_history.py). The five component proposals this plan
merges (independent simulation, swings, spreads and wonging, ceiling, edge by count) were written
by earlier stages of the same planning run and are not committed; where this plan differs from
them it says why (sections 0, 4.2, 4.6, 6).
