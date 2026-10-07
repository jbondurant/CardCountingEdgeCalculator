# The ceiling count

On 3 October Justin was asked how practical a count of our own would have to be at a real
table, and chose "Ceiling only: Unconstrained weights, just to learn the theoretical best
for this game". He took full index plays first (FULL_INDEX_PLAYS.md); this is the step after
them.

The question is how much better than the best published count any linear count could be at
this game, and how close it comes to perfect information. This step finds the best linear
count it can for the Montreal game: real-valued tags, with no thought for whether a person
could keep the count. It then scores that count the way every published count was scored,
on the same 60,000 shoes and the same resamples. The tags are searched on a second,
independent file of shoes, so the shoes a count is finally scored on play no part in
choosing it.

The answer is a ladder of rows in the headline cell (80%, 1-8 same schedule), every row on
the same states and resamples, so each gap has a paired interval:
- the null count;
- the best published counts, plain and with an ace side count;
- the ceiling count: one tag vector (F1), and with a separate betting vector (F2);
- the ceiling count's own play, with each state bet on its own value;
- perfect information: each state's best first move and insurance, bet on its own value,
  which no count can beat.

The same ladder is then given at full play. This is a design; nothing in it has been run.

## 1. What is searched

**Ten rank tags, real-valued.** A count here is ten numbers, one each for A, 2 to 9 and T.
Colour and face are left out:
- A round's value depends only on the ranks left. Given the ranks dealt, how the dealt
  sevens split by colour says nothing about the cards to come. So Red Seven is the rank
  count with the seven at its average tag, +½, plus a term that is noise about the future,
  apart from a second-order effect through that term's spread. Section 4's colour check
  measures it on Red Seven.
- Red Seven and the KISS counts split a rank to get a half weight out of whole-number tags.
  Real tags carry any weight directly.
- It keeps the count on the scorer's cheap path, one play-key part per (state, deal)
  (SystemScorer.parts). KISS I, II and III were the costliest counts to score in
  runs/report-final-26f0724.md: 10,240, 8,909 and 8,155 s, against 2,771 s for Hi-Lo.

**A true count, drift-free.** CountKeys keys a true count on 52 × RC − deckSum × dealt, over
the cards left. With p_r the share of rank r among the cards left, p⁰_r its share in a full
shoe and t̄ the tags' mean per card, that is

    true count = −52 × Σ_r (t_r − t̄)(p_r − p⁰_r)

a linear function of the shares left (of the shares after the deal, for the play key). Three
things follow.
- **Adding one constant to every tag changes no key, to the bit.** It adds c × dealt to RC
  and 52c to deckSum, and the two cancel in driftFree52 and keyAfter, in whole numbers. So
  balanced or unbalanced does not arise for a true count, and the tags are printed balanced.
- **Only the direction counts.** Ten tags, less the constant, less a scale, leave eight free
  numbers. The scale only sets the grain (below). Tags are normalised to Hi-Lo's spread,
  Σ_r p⁰_r t_r² = 10/13 once centred, so a key step means the same for every count as a
  step of Hi-Lo's true count.
- **It is the natural family.** The linear estimate of a state's advantage from the effects
  of removal has this same form (Ethier and Lee, eq. 22).

**The grain is fixed fine, and its share is shown apart.** Keys are whole numbers, so a
count's scale sets how finely its true count steps (COUNTING_COMPARISON.md section 5,
"Duplicates"). Real tags are stored as whole numbers at scale K = 10,000. A new key divisor D
rounds the true count to steps of g Hi-Lo units, with D = g × K. With D = 1, every catalog
count keeps its keys bit for bit. The default g is 1/20; calibration sets it (section 4) as
the coarsest step at which Hi-Lo and Zen, scored at that step, stop improving.

Why it must be fine:
- **Play.** Groups close at M_play = 5√n valued states, about 870 on an 80% training fold
  of about 30,370 states. Where one key alone holds more than that, the key sets the group,
  not the data. At 80% Hi-Lo forms 11 groups a fold, Zen 17, Wong Halves 19 and Uston SS 28,
  and Hi-Lo's key 0 holds 28.8% of rounds (report-final, decision resolution and per-key
  tables). A count fine enough that the data set every group forms about 30,370/870 ≈ 35
  (by arithmetic).
- **Bets.** Hi-Lo's 1-8 ramp spans keys 2 to 5 (same-schedule bets 1.73, 3.70, 5.60 and 7.39).
  A finer key ranks states within those steps, as far as the isotonic advantage's blocks
  allow.

So part of any gain a fine-grained count shows comes from the grain, not the tags. The top
published counts are therefore also scored at g, and each gain over a published count is
reported in two parts:
- **grain:** the published count at g, less the same count at its own grain;
- **tags:** the ceiling count, less the published count at g.

A divisor is used rather than large raw tags because the scorer's group tables and its
resample index (Groups.table, ByKey) are sized by the span of play keys, ByKey with an entry
per key and deal. Hi-Lo's bet keys run from −16 to 16 at 80% (report-final), so at g = 1/20 a
count spans some hundreds of keys. Tags scaled to a largest of 1,000 would span tens of
thousands (by arithmetic from that range).

**F1, the headline: one tag vector** for the bet key, the play key and insurance, as every
plain published count is used.

**F2: a betting vector beside it.** One vector sets the play key and insurance, another the
bet key.
- **It contains every "+ ace side count" row exactly.** CountKeys adds m × (52 × aces left −
  4 × cards left) to 52 × RC_b. That is, to the bit, an ace tag of −m added to the betting
  vector.
- **Those are the best published rows.** At 80%, 1-8: Victor APC with the ace side count
  +0.387 [+0.353, +0.424] and Hi-Opt II with it +0.384, against Zen's +0.356 [+0.321,
  +0.392], the best single count (report-final, headline). So F1 is compared with the plain
  counts, and F2 with every row.
- **In code** an F2 count is a Count with its own bet tags, whose base is the play count.
  SystemScorer.score already scores such a variant, as itself and betting only.

**Insurance needs no third vector.** P(hole ten | deal) is linear in the ten share left after
the deal, so the Ten Count's play key carries everything insurance needs. Its insurance
efficiency is 1.000 [1.000, 1.000] at 80% (report-final). A separate insurance vector could
add at most the gap between a count's own fitted insurance and perfect insurance, and that
gap is reported as such.

**F1r: the running-count check.** The same ten tags keyed on the running count, as KO is.
Here the constant shift is a real parameter: it adds c × depth to the key, which is how KO
carries depth.
- KO scores +0.003 [+0.001, +0.006] over Hi-Lo at 80%, 1-8 (simultaneous [−0.002, +0.008]),
  but with other tags, so the report cannot say which did it.
- F1r gets a modest search from F1's optimum (section 4). If it beats F1, depth carries
  something no true count sees, and that is reported as a finding.

**The machinery is part of the definition.** A count is scored by the comparison's fixed
rules, each fitted on the other fold:
- groups of M_play;
- a move per (group, deal), and insurance per (group, ace-up deal);
- an isotonic advantage and the same schedule.

So "the best linear count" means the best tags under these rules at this data size, the
standard every published row is held to. What these rules lose to finite data is small:
decision bounds at 80% are 0.0002% to 0.0006% a round (report-final).

## 2. Where the ceiling sits

**The present reference rows do not bound the headline cell.**
- "Chart play, 1-8 schedule on the true advantage" (+0.119 at 80%) has neither count-based
  play nor insurance. Hi-Lo betting only already scores +0.134, and the best betting-only
  rows +0.187 (Hi-Opt II and Victor APC, each with the ace side count).
- The perfect-first-move row (−0.530 at 80%, with insurance) is flat only.

**Rows added, each a bound by the rearrangement inequality.** Within each test fold, the
schedule's bets (the fold's own distribution, as every count gets) are handed out in order of
each state's own value.
- **Perfect first move and insurance, bet on its own value.** No first-decision count betting
  this schedule can beat it:
  - every bet is at least 1;
  - no count's round value exceeds the perfect one, state by state;
  - no way of handing out the schedule beats sorting it by the values, blocks that share an
    average bet included.

  It holds on every resample, not only on average.
- **Chart play with perfect insurance, bet on its own value.** The same bound for the
  betting-only rows.
- **The full-play version.** Perfect play of the dealt hand (FullIndexFit.perfect) and perfect
  insurance. It plays split hands by the chart, so a count with full play can beat it there
  by up to what perfect split play is worth. The design review's scratch figures put that at
  0.0097% a round flat and 0.053% under Hi-Lo's 1-8 schedule (FULL_INDEX_PLAYS.md, top
  table). The report states the margin wherever it uses this row.
- **The ceiling count's own play and insurance, bet on each state's own round value under
  that play.** This is not a bound on other counts. It splits the ceiling's gap to perfection
  into what its betting loses and what its play loses.

**A betting ceiling in closed form.** The report's empirical BC is the correlation, over the
valued states weighted 1/q, between the unrounded true count and the chart advantage.
- The true count is linear in the shares left. So no linear count's BC can exceed R, the
  multiple correlation of the chart advantage regressed on the shares, weighted the same way.
- The tags that reach it are the regression's coefficients, negated and centred: this game's
  effects of removal, as measured on its states.
- The best published BCs here are 0.984 (Uston SS) and 0.983 (Thorp Ultimate, and Revere 14
  with the ace side count) (report-final).

**What must hold, each up to noise:** null ≤ every published count of a family ≤ that
family's ceiling; F1 ≤ F2; every row ≤ its perfect bound. In each cell the report gives
(ceiling − null) / (perfect bound − null).

## 3. Objective

The search maximises one figure: the EV per round at 80% penetration, 1-8 same schedule,
first-decision play with fitted insurance. It is SystemScorer's point estimate, both folds
pooled.
- **80% and 1-8** are the existing headline. 80% has the most states and the widest spread
  of compositions.
- **The same schedule** gives every count the same bets, so no count gains from its scale or
  its rounding (COUNTING_COMPARISON.md section 6).
- **Insurance** is in every published headline row.
- **First-decision play,** because an evaluation must take seconds, and full play takes most
  of an hour a count (section 7).

Full play mostly keeps the first-decision order. Among the 16 counts the full-index run had
scored by 21:39 on 6 October, it reorders only Hi-Lo and KO, 0.003 apart
(runs/full-index/peek-c06c061-2026-10-06-2139.txt). But the gain from full play runs from
+0.059 (KISS I) to +0.093 (Hi-Opt II), so close candidates can swap. Section 4 therefore
chooses the full-play count separately.

**A second search** maximises flat EV with insurance at 80%. That is the playing ceiling,
whose direction leans toward play.

**Every searched count is then scored in every cell,** exactly as the published rows are:
70, 75 and 80%; every spread; the same schedule and the own ramp; flat, PE, IE, BC and IC;
decision resolution and the learning curve. Play and bets are fitted per penetration and the
tags held. Cells other than the two searched are honest scores, not their own ceilings, and
the report says so.

## 4. The search, on a twin file

**A twin file.** StateRun with a new seed, 20261007, and every other setting the main file's:
q0 0.008, u0 0.02, cut 332, the rules and the chart, 60,000 shoes.
- **Cost.** At the main run's last segment, 0.18 s a valued state on 8 threads
  (runs/montreal-80.log), that is about 3 hours (estimate). The file takes about 1.34 GB, the
  main file's size.
- **The same size matters.** M_play grows with n, so on a twin of the same size the scorer's
  groups behave as they will on the main file.
- **Guards.** A committed check confirms that no twin shoe's generator seed (ShoeRun.mix)
  equals a main-file shoe's. The search, calibration and theory-pass tools refuse a state
  file whose header seed is 20260928, the main file's.

**Calibration, on the twin:**
- **One evaluation, timed** single-threaded. It sets the search budget.
- **The proxy check.** The published rows' 80%, 1-8 point estimates on the twin, as
  differences from Hi-Lo, are set beside report-final's. The two files are independent and
  the same size, so each difference's SE is about √2 times report-final's paired SE. About
  95% should fall within two of those.
- **The noise floor.** The paired bootstrap SE (100 resamples) of the objective's difference
  between Zen and directions 1°, 2° and 5° away from it. Differences below it are not
  differences.
- **The grain g** (section 1).
- **The colour check.** Red Seven against its rank average (7 = +½), both at g. Red Seven
  should not be better beyond noise; if it is, section 1's argument is reported as not borne
  out.

**The theory pass, on the twin,** for starting points and for reading the result. One pass,
every sum weighted 1/q (CountRegression, new):
- **Betting.** Each state's chart advantage regressed on the shares left. It gives the
  betting start (this game's measured effects of removal) and R, the BC ceiling.
- **Insurance.** The insurance value regressed on the shares after the deal must give the
  Ten Count with 1 − R² below 1e-12. The relation is exact, so this checks the code.
- **Each decision.** For every deal and every legal move other than the chart's,
  V(m) − V(chart) regressed on the shares after the deal, weighted P(i)(1 − P(nat | i))/q.
  The coefficients are this game's index directions. Weighted by each deal's share of what
  the perfect row gains over the chart, they give a playing start.
- **Depth.** The R² that 1/n adds to the betting regression: how much the advantage moves
  with depth at an equal composition. No true count sees that; F1r can, in part.
- **Effects of removal off the top** (EffectsOfRemoval, new). The full 8-deck shoe is valued
  by ExactRound with the chart, then with k cards of each rank removed and with k added
  (central differences): about 21 valuations of about a second each (COUNTING_COMPARISON.md section 7). Ethier and Lee
  computed their game's effects of removal directly, finding single-deck ones converted to
  many decks less accurate; this does the same.

The same regression is run on the main file only after the counts are registered (section 5),
to print R beside the report's BC column.

**One evaluation** is SystemScorer's estimate at 80% with no resamples, no decision resolution
and no half fits:
- the count's play keys;
- each fold's groups and moves, fitted on the other fold, and insurance;
- the bet keys, the isotonic advantage and the same-schedule bets.

Report-final took 2,770.7 s for Hi-Lo over three penetrations, each fitted once and again on
1,000 resamples: about 0.9 s a penetration and fit. There a resample revalues only the
(key, deal) pairs it plays differently, while a fresh fit values every state. So an
evaluation is taken as one to a few seconds (estimate) until calibration measures it. For F2
with the play vector held, a betting-vector candidate needs only new bet keys, the isotonic
fit and the bets: hundredths of a second, by the operation count.

**The optimiser is CMA-ES.** It learns correlated directions (the 4, 5 and 6 trade off
against each other) and does not depend on the choice of axes.
- **Space.** Coordinates in an orthonormal basis of the balanced subspace, under the
  p⁰-weighted inner product. Each candidate is normalised to Hi-Lo's spread before it is
  scored. The sign is fixed so the count rises with the advantage, since an isotonic bet only
  rises.
- **Settings.** A population of about 12, a starting step of about 0.3 radians, restarts with
  the population doubled, at most about 1,500 evaluations a run. A run reports its final mean,
  not its best single evaluation, which tends to be the one that fit noise best. The
  objective is deterministic but piecewise constant at small scales, since groups and moves
  change in jumps, so steps are not pursued below the noise floor.
- **Starts.** Every published count's direction is scored once, kind-aware counts by their
  rank averages. The runs start from:
  - the eight best of them;
  - the theory pass's betting and playing starts;
  - the two real-valued vectors in Table 2 of Vidámi, Szilágyi and Iclanzan;
  - four random directions.
- **F2** starts from (u, u) at F1's optimum, so F1's best is among its starting points, and
  ending below it on the twin is an optimiser failure (section 6). It alternates two blocks: the betting vector with the play held, which is cheap, then the play
  vector. It stops when neither moves the objective by more than the noise floor, after at
  most three cycles, and ends with a joint polish.
- **F1r** runs three times: twice from F1's optimum with different shifts, once from KO's
  direction and balance.
- **Every evaluation is logged** (tags, objective, run, generation), so a search can be
  audited, its landscape inspected and a stopped search resumed.

**How we know it found the maximum, or how close.** The objective has jumps, so no search can
prove a global maximum. These show that nothing within the data's resolution was missed:
1. **Restarts agree.** Runs from different starts end within the noise floor of the best and
   within a few degrees of it. If they end in separate regions that differ by more than
   noise, each region's best is kept and the twin decides between them.
2. **Slices peak at the optimum.** Slices along each basis direction, each of CMA's principal
   axes and the line to each top published count peak at the optimum within noise. Quadratic
   fits to them estimate any headroom left. Near a maximum the loss grows as the square of the
   angle, so every direction within the angle that costs less than the noise floor is as good
   as the data can tell apart. The report prints that angle.
3. **Every start is beaten.** On the twin, the optimum scores at least as well as every start.
   Anything else is a search failure.
4. **A planted optimum is recovered** by the same code (section 6).

**Is the twin big enough?** F1 at 1-8 is searched again on each half of the twin: the shoes
whose index halved is even, and the rest, the learning curve's split. Both half-searches'
counts are scored on the main file beside the full search's. If they score as well, within
paired SE, more shoes would not find a better count.

**Full play: choosing on the twin.** Five F1 counts on the line from the 1-8 optimum to the
flat optimum, at 0, ¼, ½, ¾ and all of the way (renormalised), are fitted with full play.
- The fits use the first 20,000 twin shoes at 80%, both folds, no resamples. The best of the
  five by 1-8 EV joins the registered counts as the full-play F1.
- The run builds its trees each round (`--trees none`), since no second tree store fits on
  the disk (section 7).
- Twenty thousand shoes give a smaller M_play than the whole file. That is enough to rank five
  neighbouring directions by their full-play EV, and the main file scores the one chosen at
  full size.
- F2's 1-8 optimum is scored with full play as it is.

## 5. Honesty

**Registration before the main file.** Before any main-file run, the chosen counts go into a
committed custom-counts.json. For each count it holds:
- the vector or vectors, as whole numbers at K, with the divisor;
- its family, and its role: headline, secondary, check or control;
- the twin file's header and the commit the search ran from.

The headline of each cell is fixed there: F1's 1-8 count at first decisions, F2's beside it,
and the full-play F1 under full play. The main-file tools refuse a custom-counts.json that is
uncommitted or modified, and print its git blob hash. Nothing is chosen on the main file.

**On the main file the custom rows are just more counts** in the existing comparison:
- each fold fitted on the other parity;
- 1,000 resamples from seed 20260929;
- play, insurance and bets refitted on every resample;
- differences paired resample by resample.

The whole catalog is scored again in the same run, so every published row is reproduced and
paired. Each must match report-final's figures to the printed digit; if one does not, the run
stops until the reason is found. The rows:
- the catalog and its variants;
- the custom rows: F1 at 1-8 and flat, F2, F1r, and the two half-twin counts;
- the fine-grain controls at g: Hi-Lo, Zen, Wong Halves, and Victor APC and Hi-Opt II with
  their ace side counts;
- the bound rows of section 2.

**Simultaneous intervals.** The custom rows and the fine-grain controls join the family that
the max-|t| multiplier covers.
- "Better than the best published count" is claimed only when the custom row's simultaneous
  interval against every published row excludes zero.
- The family covers every comparator at once, so naming the best one afterwards is still
  covered.
- The point difference from the best published row is reported either way.

**What the interval means.** On the main file the tags are fixed inputs. A custom row's
interval is therefore for that count's EV on new shoes, the same claim made for a published
count, and needs no widening. It leaves out how the tags would move with another twin. So the
ceiling is stated as a lower bound: some linear count reaches at least this much. Three
measurements bound the rest:
- **Optimism.** (F1 − Zen) on the twin, less (F1 − Zen) on the main file. The two files are
  independent, so their SEs add in quadrature. The same figure for a published pair, Hi-Opt II
  with its side count less Zen, is the control and should be zero within noise. If the
  optimism is large while the main-file gain over the best published count is within noise,
  the honest reading is that the published counts already sit near the linear ceiling.
- **Search sufficiency:** the half-twin searches (section 4).
- **The bounds** of section 2, from above.

A held-out gain at or below zero is a legitimate answer, not a failure. It would mean that no
linear count beats the best published one by a margin these shoes can detect.

**Full play on the main file.** FullIndexRun is run on the full-play F1, F2, and Hi-Lo as a
control, with the same seed and resamples, and its Results are paired with
runs/full-index/full-c06c061/results.ser.
- Hi-Lo's full-play Result must match c06c061's within 1e-12, the joint run's own standard
  (FULL_INDEX_PLAYS.md section 3).
- The custom rows take the stated conservative widening defaults, 1.07 for differences and
  1.80 for gains (FULL_INDEX_PLAYS.md section 4), since the pilot did not measure them.

## 6. Checks

Each check is a committed test that must pass, and must fail on the planted bug it names.
- **Published counts unchanged.** With divisor 1, every catalog count's bet and play keys are
  bit-identical on the test fixtures. On the main file, the published rows match report-final
  (section 5).
- **Invariances:**
  - every tag plus a constant gives the same true-count keys;
  - tags times k with the divisor times k give the same keys;
  - doubling K changes only keys that sit on a rounding boundary;
  - colour-blind tags written over the 26 kinds or over the 10 ranks give the same keys;
  - the largest tags the normalisation allows cannot overflow the running count.
- **F2 contains the side counts.** An ace tag of −m in the betting vector reproduces each
  "+ ace side count" variant's bet keys, to the bit.
- **A planted optimum.** SystemScorerTest's synthetic states get move values a_im + b_im ×
  (u*·x), linear in the composition along a hidden direction u*, and a chart advantage that
  rises along it.
  - From Hi-Lo, F1 recovers u* (cosine ≥ 0.99) and beats every start.
  - With different hidden directions for betting and play, F2 recovers both and F1 lands
    between them.
- **CMA-ES** reaches the known optima of the sphere, a rotated ellipsoid and Rosenbrock in 9
  dimensions, the same way for the same seed.
- **Theory-pass anchors:**
  - the insurance regression gives the Ten Count, with 1 − R² below 1e-12;
  - a BC objective searched by the same optimiser matches the regression's direction (cosine
    ≥ 0.999), and the report's BC code gives R for it;
  - on hand-made states with values planted linear, the regression recovers them to 1e-9.
- **Bounds.** On a tiny state set each bound row equals a brute-force sort. No row exceeds its
  bound on any resample.
- **Leakage guards.** The search tools refuse the main file; the main-file tools refuse an
  uncommitted custom-counts.json; the twin's shoe seeds collide with none of the main file's.
- **Grain.** Halving or doubling g moves the headline EV by less than its paired SE. The
  fine-grain count's decision bound stays under the 0.005% target, and its learning curve
  holds, as for any count.

**Results that would make the outcome untrustworthy:**
- any row above its perfect bound, which means a scoring bug;
- the search ending below one of its starts on the twin, or F2 below F1, which means an
  optimiser bug;
- restarts disagreeing beyond noise, or the two half-twin counts far apart (cosine below 0.95)
  with similar scores. Then the top is a flat ridge, and a region is reported, not a count;
- the twin's published differences disagreeing with report-final's beyond chance;
- the gain vanishing at equal grain. Then it was grain, and the report says so;
- the gain coming mostly from insurance, or from one penetration;
- a tag whose sign contradicts this game's measured effects of removal with no play decision
  to explain it, or a score that spikes under small changes of the tags, which would be a
  grouping artefact;
- the ceiling's full-play gain far outside the +0.059 to +0.093 the published counts show.

## 7. Cost on this machine

Nothing reads a state file until full-c06c061 has written results.ser, due about 04:00 on 7
October. At 00:56 that day its log showed 138 of 186 items scored
(runs/full-index/full-c06c061.log).

| Step | Machine time (estimate) | Disk |
|---|---|---|
| Twin file | about 3 h on 8 threads | 1.34 GB |
| Calibration and theory pass | 1 to 2 h | |
| Searches and slices | about 6 h on 6 threads | logs |
| Full-play choice on the twin | 1 to 2 h, after a dry run on 2,000 shoes | none, trees rebuilt |
| Main file, first decisions, 1,000 resamples | about 7 h | a report |
| Main file, full play | 3 to 6 h, after a dry run | results |

Where the estimates come from:
- **The searches.** Budgets in evaluations: F1 at 1-8, about ten restarts, 15,000; F1 flat,
  7,500; F2, 6,000 plus about 30,000 cheap betting-only ones; F1r, 4,500; F1 on each twin
  half, 7,500 between them; slices and the noise floor, 1,000. That is about 41,500 at about
  2 s each. COUNTING_COMPARISON.md section 7 found that CPU time does not add up across
  threads on this machine, so 6 threads are taken as worth about 4: about 6 hours.
- **Memory.** The states take about 1.6 GB in memory (FULL_INDEX_PLAYS.md section 6). Each
  evaluation running at once holds the play keys of 60,743 × 550 (state, deal) cells as ints,
  134 MB, plus its fits: about 250 MB (estimate), so six at once take about 1.5 GB.
- **Main file, first decisions.** Report-final scored the catalog in 18,623.7 s of wall time
  on 7 threads, at a mean of 4,076 s a count. About twelve more counts at that mean add about
  2 hours on 7 threads.
- **Main file, full play.** The projection put the whole-file run at 23.7 hours for 31 counts
  at 1,000 resamples (runs/full-index/projection-73df32c.txt), about 0.8 h a count on 6
  threads. A count fitted alone shares no split values with the others, so three counts take
  more than three times that. Without the tree store, trees are built again each round, about
  half an hour of the wall a time (FULL_INDEX_PLAYS.md section 6).

**Disk.** On 7 October the disk had 12 GB free, 95% used (df), and the full-c06c061 directory
took 14 GB (du). The twin's 1.34 GB fits. A second whole-file tree store, 14.9 GB, does not.

**A hazard.** TreeStore deletes a store whose identity differs from its own, and the identity
includes the commit (TreeStore's constructor). So a new run must never be pointed at
runs/full-index/full-c06c061/trees.bin, except through the read-only mode of step 1. That
mode keys on the state file and its states alone, opens the store read-only and never
deletes it.

## 8. Steps

0. **Wait** for full-c06c061's results.ser. Until then, only code and tests on fixtures.
1. **Code and tests,** minutes of machine time:
   - real-tag counts in CountKeys: whole tags at K, the key divisor, an optional betting
     vector, the running form;
   - SystemScorer's point estimate, and the betting-only re-evaluation;
   - the bound rows;
   - CountRegression and EffectsOfRemoval;
   - CountSearch: CMA-ES, its log and resume;
   - custom-counts.json and its loader in ComparisonReport;
   - `--counts` and `--pens` for FullIndexRun, and TreeStore's read-only mode;
   - the refusals, and every test of section 6.

   *Produces:* commits, with the suite passing.
2. **The twin file.** *Produces:* runs/twin-80.states, its log, the collision check.
3. **Calibration and the theory pass,** on the twin. *Produces:*
   runs/custom-count/calibration-<sha>.md, with the evaluation time, the proxy check, the
   noise floor, g, the colour check, the effects of removal, R and the depth figure.
4. **The searches and slices.** *Produces:* the evaluation logs and the candidate counts,
   with the convergence evidence of section 4.
5. **The full-play choice,** on the twin. *Produces:* the full-play F1.
6. **Registration.** *Produces:* custom-counts.json, committed.
7. **The main file, first decisions.** *Produces:* runs/report-ceiling-<sha>.md: every cell,
   the ladder, the grain and tag split, the difference from every published row, the
   efficiencies against the bounds.
8. **The main file, full play.** *Produces:* full-play rows paired with c06c061's
   results.ser, and the full-play ladder.
9. **The results,** written into this file as they are measured, as FULL_INDEX_PLAYS.md does:
   the ladder, the tags beside the measured effects of removal and the published counts'
   normalised tags, the optimism and the search diagnostics.

## 9. What was not taken, and why

Three proposals were weighed for this design. What each offered and was not used:
- **Tags chosen per fold on the main file,** with a nested holdout inside the training fold.
  It is honest too. But each search would see half the shoes, at a smaller M_play than the
  scoring uses, and the result would be two tag vectors rather than one count. The interval
  would need a pilot that reruns the search on resamples, and SystemScorer and the full-index
  run would both need a count per fold. The twin costs about 3 machine hours and 1.34 GB, and
  leaves every existing result paired as it stands.
- **A closed-form surrogate as the search objective,** polished on the scorer afterwards. It
  is kept as starting points (the theory pass), not as the objective. The scorer is cheap
  enough to search directly, and the surrogate leaves out what decides the top: the groups,
  the isotonic blocks, the ramp's tail and fit noise.
- **A "linear information" row,** a separate regression for every decision. It asks whether
  one number is what binds, a different question, and it would need a scoring path of its
  own. F2 measures the part of it a count can use.
- **Tags scaled to a largest of 1,000, with no divisor.** That makes the scorer's key-span
  arrays tens of times larger for the same effect (section 1).
- **A Nelder–Mead polish.** It can stall on the flats of a piecewise-constant objective. The
  end of each CMA-ES run and the slices do the same job.
- **A full search of running counts.** Kept as a check (F1r), since the true count is the
  natural linear family and the published evidence for depth (KO) is small.
- **Scoring only the eight best published counts** beside the custom rows. Rescoring the
  whole catalog costs about five more hours, but it reproduces every published row as a check
  of the new code and gives a report that stands alone.

## 10. Questions for Justin

1. **Is F2 part of the answer?** F1 is one tag vector for everything. F2 lets a second vector
   set the bets, as the side-count rows that lead the table already do. *Default:* both, with
   F1 the headline. F2 adds about an hour of search and a few rows.
2. **Which cells get their own search?** Proposed: 80% at 1-8, and 80% flat. Every other cell
   is scored with those counts and labelled as not its own ceiling. A search per penetration
   or spread costs about an hour each, and "the ceiling" then becomes a family of counts.
   *Default:* the two.
3. **Disk.** It is 95% full, 12 GB free on 7 October. May the twin file (1.34 GB) be written,
   and may the c06c061 tree store (14 GB) be kept until step 8 reads it read-only?
   *Default:* write the twin, keep the store and delete nothing. If the store is gone by then,
   step 8 builds trees each round instead.

## 11. What it does not do

- **Practical counts.** Justin chose the ceiling only: no rounding to a level a person could
  keep, and no ceiling tags at a published count's grain.
- **Nonlinear counts, or keys of two numbers,** such as the count and the depth together. The
  theory pass's depth figure says how much the second would matter.
- **A separate count for each decision.**
- **Tags tuned per penetration or spread,** unless Justin asks (question 2).
- **Risk.** There is still no round variance, so no SCORE.

## Sources

- Ethier and Lee, "Snackjack: A toy model of blackjack" (2019),
  [arxiv.org/abs/1906.01220](https://arxiv.org/abs/1906.01220). The linear estimate of the
  advantage from effects of removal (eq. 22); effects of removal computed directly for their
  game rather than converted from a single deck; their finding, on limited evidence, that
  betting efficiency is close to the betting correlation; and that, for the first two thirds
  of their shoe, a level-one count's error was mostly the count's and not the game's
  nonlinearity. Griffin's *The Theory of Blackjack* is cited only as this paper cites it; it
  was not read directly.
- Vidámi, Szilágyi and Iclanzan, "Real Valued Card Counting Strategies for the Game of
  Blackjack", [real.mtak.hu/114350/1/sub_736.pdf](https://real.mtak.hu/114350/1/sub_736.pdf).
  They evolved real-valued balanced tags with a genetic algorithm, with fitness the simulated
  win over Hi-Lo on 10⁶ hands drawn afresh each generation, at 6 decks and a 1-100 spread. They
  judged the results by BlackjackInfo's PE, BC and IC calculator, not by a held-out EV. Their
  Table 2 vectors are used here as starting points.
- BlackjackInfo, card counting efficiency calculator,
  [blackjackinfo.com/card-counting-efficiency-calculator](https://www.blackjackinfo.com/card-counting-efficiency-calculator/):
  it centres a count's tags and measures how closely they line up with the effects of removal
  from Griffin's *The Theory of Blackjack*, for six decks. The closed-form BC ceiling in
  section 2 is that idea measured on this game's own states.
