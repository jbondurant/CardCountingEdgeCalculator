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
- the best published counts, plain and with an ace side count, at their own grain and at the
  ceiling count's;
- the ceiling count: one tag vector (F1), and with a separate betting vector (F2);
- the ceiling count's own play, with each state bet on its own value;
- perfect information: each state's best first move and insurance, bet on its own value,
  which no first-decision count betting this schedule can beat.

The searched count is a lower bound on the linear ceiling: the best this search finds, scored
on shoes it was not chosen on. The bounds above it say how much room is left. The same ladder
is then given at full play. Steps 1 to 3 of section 8 have been run (the code, the twin file
and the calibration, whose figures are written in where the design takes them); from step 4
on, this is a design.

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
the coarsest step at which Hi-Lo and Zen, scored at that step, stop improving. **Calibration
kept g = 1/20, D = 500** (runs/custom-count/calibration-1579078.md): no finer grain gains on
it by more than the noise floor at 1°, 0.0012% a round, for either count, while at 1/10 Zen
still gains 0.0025% further down. Both gain most from the first halving: Hi-Lo from +0.300%
at g = 1, its own keys, to +0.315% at 1/2, and +0.318% at 1/20, at 80%, 1-8 on the twin. The
running divisor that goes with it is D_r = 2,423, at a median depth of 164 cards.

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

So part of any gain a fine-grained count shows comes from the grain, not the tags. How much
a count gains from grain varies with how many of its keys exceed M_play and how coarsely its
bets step, so every published row is also scored at g on the twin (section 4), and the best
of them there are registered as controls. Each gain over the published counts is reported in
two parts:
- **grain:** the controls at g, less the same counts at their own grain;
- **tags:** the ceiling count, less the best control at g (section 5 says how it is bounded).

A divisor is used rather than large raw tags because the scorer's group tables and its
resample index (Groups.table, ByKey) are sized by the span of play keys, ByKey with an entry
per key and deal. Hi-Lo's bet keys run from −16 to 16 at 80% (report-final), so at g = 1/20 a
count spans some hundreds of keys. Tags scaled to a largest of 1,000 would span tens of
thousands (by arithmetic from that range). The tags are rounded at K so that the deck sum
stays exactly zero, re-centred after rounding: normalised and rounded one rank at a time, a
balanced count's tags need not add up to zero, and CountKeys' side-count variant refuses a
count that is not balanced. Each tag is rounded to the nearest whole number; where the deck
sum is then not zero, the tags of the ace and 2 to 9 that rounding moved furthest the other
way are moved by one each until it is (CountKeys.rounded), so no tag is more than 1.5 from K
times its real value. The variant carries the divisor.
- **A catalog count at g** (CountKeys.atGrain) is normalised the same way over the 26 kinds,
  each a 26th of the shoe, which for a colour-blind count is the sum over ranks. Its kinds are
  rounded in groups, a rank's kinds of one catalog tag together, so a count that tags a rank
  alike still does and a kind-aware one keeps its classes; and a balanced count's zero tags stay
  zero, so an ace-neutral count keeps its side count, m then the rounded ten's tag.
- **A running catalog count at g** (KO, Red Seven, UBZ2, the KISS counts, Uston SS, BRH-0,
  BRH-II, the Ten Count, Ace-Five and Revere Five, 12 of the 30 entries) stays a running count:
  its tags are scaled to Hi-Lo's spread and not centred, since its shift is what carries depth,
  as F1r's is scaled with its balanced part; its zero tags stay zero, and its deck sum is the
  nearest it can reach to the catalog's scaled alike, so a balanced one stays balanced. It keys
  on its running count over a running divisor, set as F1r's D_r is (a key step of g Hi-Lo
  true-count units at the median depth of the 80% states). With the divisor one catalog tag
  unit at K (CountKeys.grainUnit), its keys are the catalog's (RealCountTest), so the grain part
  of a running count is grain alone.
  **Measured, it is zero.** At D_r = 2,423 each of the 12 scores at g exactly what it scores at
  its own grain, every figure to the bit (calibration-1579078). With whole tags, its running
  count only takes multiples of its tag unit (9,768 at K for Red Seven, its red seven's tag at g), so any divisor under the
  unit keeps the keys in the order the catalog's have, and the scorer's figures depend on that
  order alone. A divisor cannot make such a count finer; only a running count of real tags, as
  F1r's are, is keyed finer at D_r. The true counts' grain parts run from +0.001% to +0.023%
  at 80%, 1-8.
- **The unrounded true count,** on which the report's BC and IC are computed, is not divided by
  D: a correlation does not see the scale.

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
  counts and F2 with every row, as registration fixes (section 5).
- **In code** an F2 count is a Count with its own bet tags, whose base is the play count.
  SystemScorer.score scores such a variant, as itself and betting only, once it is handed
  one. Its callers today hand it only CountKeys.sideCountVariant, which knows the catalog's
  side-counted names (ComparisonReport.scoreAll, and both scoring sites in FullIndexFit), so
  step 1 adds a hook through which a count's own betting vector reaches all three.
- **As built,** the count listed and run is the play count, carrying the betting vector
  (Count.betVector); the hook, CountKeys.bettingVariant, makes from it the variant that bets
  on that vector (Count.betTag), named "<name> + betting vector", or else the catalog's side
  count. So an F2 count's Result holds, as a side-counted catalog count's does, its play vector
  as a plain count, then the F2 row, each also betting only. The F2 row is the variant's.

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
- A running key has no true-count unit, so its divisor D_r is set so that one key step equals
  g Hi-Lo true-count units at the median depth of the 80% states. Without it the running
  keys at K would span on the order of a million values, and ByKey, 550 ints a key, alone
  would take gigabytes (estimate). Halving and doubling D_r on the twin shows whether F1r's
  score depends on it.
- F1r gets a modest search from F1's optimum (section 4). Its tags are also scored keyed as a
  true count, a registered check row, so the depth effect is F1r less that row, paired, with
  the tags the same. If that is positive beyond noise, depth carries something no true count
  sees, and it is reported as a finding.

**The machinery is part of the definition.** A count is scored by the comparison's fixed
rules, each fitted on the other fold:
- groups of M_play;
- a move per (group, deal), and insurance per (group, ace-up deal), each chosen for the most
  flat value, every state weighted 1/q (SystemScorer.fit);
- an isotonic advantage and the same schedule.

So "the best linear count" means the best tags under these rules at this data size, the
standard every published row is held to. Two parts of that need saying.
- **Plays are fitted for flat bets,** though the headline bets 1-8. Within a play group the
  bet varies: for F1 through the deal's three cards, which move the play key off the bet
  key, and far more for F2, whose one play group can hold states bet anywhere from 1 to 8. So
  the ceiling is the ceiling with plays fitted for flat bets, as every published row's are.
  F1, F2 and Zen are also scored with moves and insurance refitted on the training fold
  with each state's schedule bet as a weight, a labelled check row: the check
  FULL_INDEX_PLAYS.md section 4 plans for one system, which nothing has measured yet.
  (SystemScorer.scheduleRefit.) The groups are held as the flat fit formed them; a training
  state's bet is the 1-8 same-schedule bet its key gets when the schedule is handed out over
  the training fold itself, by the advantage fitted there under the flat play; the test fold
  is then bet as usual, the advantage fitted again under the refitted play. The bootstrap
  refits all of it on every resample. At spread 1 every bet is 1 and the check is the flat
  fit, to the bit (CeilingScoringTest).
- **Finite data costs something.** Report-final's learning-curve column measures it: play
  and insurance fitted on half of each training fold lower the flat EV at 80% by 0.0015% for
  Hi-Lo and by up to 0.0027% (Canfield Expert), several times the decision bounds (0.0002% to
  0.0006%), and that is flat, not 1-8. So the ceiling is stated at about 30,370 training
  states a fold, and each registered custom count's half-fit change is given at 1-8 as well
  as flat. At 1-8 the bets' advantage is fitted on the same half as the play and insurance,
  and the schedule handed out on the whole test fold (Entry.halfFitSame). It is worked out at
  first decisions only; full play keeps the flat half fit it has.

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
  by Σ b δ over the states, with δ what perfect split play adds to a state and b the count's
  own bet. No bet exceeds S, so S times the flat mean of δ bounds that for any count. The flat
  mean is measured by committed code on a sample of twin states (section 4). The design
  review's scratch figure, 0.0097% a round flat on 84 states (FULL_INDEX_PLAYS.md, top table),
  is only a guide to its size: at S = 8 it would make the margin about 0.08% (by arithmetic).
  The report states the margin wherever it uses this row.
  - **As built** (SplitMargin), δ at a state is what the best split play a count's tables could
    give it adds to the perfect row's first moves: for each pair deal, the play a SplitPlay
    draws from a hit or stand at each contested later total and a first move for each second
    card, found by coordinate ascent from the chart's. The ascent stops at a local optimum, so
    δ is measured from below, and the margin with it; the tool's lines say so, at each
    penetration over the sampled states of that penetration, and a full-play bound plus this
    margin is stated as conditional on it.
- **The ceiling count's own play and insurance, bet on each state's own round value under
  that play.** This is not a bound on other counts. It splits the ceiling's gap to perfection
  into what its betting loses and what its play loses.

As built (SystemScorer.bounds, ownValueBets): a fold's test states with weight are sorted by
their own value, states of equal value form one block, and each block gets the average over
its weight of the reference bets laid out in order of chart advantage, as a count's keys do.
The own-value row is an entry a scorer adds, when asked (ownValueRows), after a count's other
entries, at first decisions and with full play, so every published Result keeps its entries
in their places. FullIndexRun asks for it whenever it runs counts from a file.

**Only flat and same-schedule cells have a bound.** Under the own ramp a count sets its own
bets, clamp(â/0.25%, 1, S), and where its fitted advantage exceeds a state's perfect value it
bets more there than proportional betting on that value would. So these rows bound nothing
under the own ramp, and own-ramp cells get no bound row, no bound check and no efficiency.

**A betting ceiling in closed form.** The report's empirical BC is the correlation, over the
valued states weighted 1/q, between the unrounded true count and the chart advantage.
- The true count is linear in the shares left. So no linear count's BC can exceed R, the
  multiple correlation of the chart advantage regressed on the shares, weighted the same way.
- The tags that reach it are the regression's coefficients, negated and centred: this game's
  effects of removal, as measured on its states.
- The best published BCs here are 0.984 (Uston SS) and 0.983 (Thorp Ultimate, and Revere 14
  with the ace side count) (report-final).

**What must hold.** Every row at or below its perfect bound, on every resample, on either
file: a row above it is a scoring bug. On the twin, where the search starts from them, the
searched optimum is at or above every start, and F2 at or above F1 (section 4). On the main
file those orderings are results, not checks: a published count may score above the searched
one there, and F2 below F1 (section 5). As built, CeilingReport checks every first-decision row
in flat and same-schedule cells: the counts scored, each with its own-value row, the stored
rows, and the refit check, which plays count-based first decisions. Full-play rows are checked
in the full-play report, against the full-play bound with the split margin.

**Efficiency.** In each flat and same-schedule cell the report gives (row − null) /
(perfect bound − null) for the cell's registered ceiling row and for the best published row
of its family.

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
+0.059 (KISS I) to +0.093 (Hi-Opt II), more than the gaps between the top counts, so close
candidates can swap. Section 4 therefore chooses the full-play count separately.

**A second search** maximises flat EV with insurance at 80%: the flat ceiling, play and
insurance together. Perfect insurance is +0.020 of the +0.130 by which perfect information
beats the null count flat at 80% (report-final, reference rows), and insurance is linear in
the ten share, so this search leans toward the tens. Its PE is therefore not a playing
ceiling; the report gives its PE and IE without calling either one.

**Every searched count is then scored in every cell,** exactly as the published rows are:
70, 75 and 80%; every spread; the same schedule and the own ramp; flat, PE, IE, BC and IC;
decision resolution and the learning curve. Play and bets are fitted per penetration and the
tags held. Which count is reported as the ceiling in each cell is fixed at registration
(section 5). Cells other than the two searched are honest scores, not their own ceilings,
and the report says so.

## 4. The search, on a twin file

**A twin file.** StateRun with a new seed, 20261007, and every other setting the main file's:
q0 0.008, u0 0.02, cut 332, the rules and the chart, 60,000 shoes.
- **Cost.** At the main run's last segment, 0.18 s a valued state on 8 threads
  (runs/montreal-80.log), it takes about 3 hours. The segment before, on the same engine, ran
  at 0.27 to 0.34 s a state over its later stretches, which would make it about 5 (estimates,
  from the increments of the log's cumulative lines). The rate over the twin's first hour
  sets the schedule after it. The file takes about 1.34 GB, the main file's size.
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
- **The noise floor:** what a difference in the objective between two directions must
  exceed to be one. It is measured between Zen and directions 1°, 2°, 5°, 10° and 30° away,
  two ways: the paired bootstrap SE (100 resamples), and the spread across 8 disjoint
  sub-runs (ComparisonReport --calibrate), scaled to the full size. The two can disagree:
  for differences between counts near each other, COUNTING_COMPARISON.md section 8 found the
  bootstrap's SE about three to four times the sub-run spread for some counts (Omega II:
  0.027% against 0.009%) and under it for Red Seven (0.031% against 0.045%). Both are
  printed, and the search uses the smaller, at the angle being compared. That stops it later
  and grants convergence less easily, at the price of false alarms, which send the result to
  a region rather than a count.

  **Measured** at g = 1/20 (calibration-1579078), as the SE of a difference from Zen at 80%,
  1-8:

  | Angle | Bootstrap | Sub-runs, scaled | Noise floor | Mean change from Zen |
  |---|---|---|---|---|
  | 1° | 0.0012% | 0.0021% | 0.0012% | −0.0004% |
  | 2° | 0.0014% | 0.0023% | 0.0014% | −0.0024% |
  | 5° | 0.0015% | 0.0026% | 0.0015% | −0.0122% |
  | 10° | 0.0017% | 0.0028% | 0.0017% | −0.0369% |
  | 30° | 0.0042% | 0.0047% | 0.0042% | −0.2516% |

  Here the bootstrap is the smaller at every angle (its SE is 0.61 of the sub-runs', the
  median over the directions), so the floor is the bootstrap's. Directions 1° from Zen already
  differ from it by up to 0.0030%, and on average they lose less than the floor at 1° and more
  at 2°: the objective tells apart directions about 2° from each other, not closer.
- **The grain g,** with Hi-Lo and Zen (section 1).
- **Every published row at g.** Each catalog count and side-count variant, scored at g, point
  estimates only, the running ones at their running divisor (section 1). The counts behind the
  five best rows at g, and Hi-Lo, are the controls.
- **The colour check.** Red Seven against its rank average (7 = +½), both at g: Red Seven as a
  catalog entry at g, and its rank average as a running count of our own in the candidates file,
  its tags Red Seven's at g with the seven's two kinds averaged, at the same divisor. Red Seven
  should not be better beyond noise; if it is, section 1's argument is reported as not borne
  out.
  **Measured** (calibration-1579078): Red Seven is not better; it is worse, by 0.0204% a round
  [0.0188%, 0.0222%] at 80%, 1-8 over 100 resamples (+0.2811% against +0.3015%). Not all of
  that need be colour. Red Seven at g scores as Red Seven does (its grain part is zero, as
  above), while its rank average, the seven at half a unit, has running counts in half units
  that Red Seven's whole tags cannot reach, so the two are not at the same grain.
- **The split margin.** The flat value of perfect split play, δ, on a sample of twin states,
  sized to take under an hour (section 2).
- **As built** (Calibration), one command runs all of it and the theory pass below and writes
  runs/custom-count/calibration-<sha>.md, from classes frozen from one commit (section 8, step 3).
  Where the design left a choice, it is made so:
  - the noise floor's directions are six, drawn at random perpendicular to Zen's in the search's
    coordinates, the same six at every angle, each count whole at K as the search scores it; each
    measure is the root mean square over the six at its angle, the sub-runs' spread divided by √8;
    it is measured first at the default g, 1/20, and again at g if g is another grain;
  - g is the coarsest of 1, 1/2, 1/4, 1/10, 1/20, 1/40, 1/100 and 1/200 past which no finer one
    gains more than the noise floor at 1°, for Hi-Lo and for Zen alike;
  - D_r is D times the cards left at the median depth of the 80% states, each weighted 1/q, over
    52;
  - the proxy check takes the main file's differences and paired SEs from full-c06c061's
    results.ser, after requiring that its rows print report-final's headline to the digit
    (CeilingReport.checkPrinted), and fails when, the rows taken as independent, chance would give
    so few within 2 less than 1% of the time;
  - the colour check pairs the two counts over 100 resamples, and calls Red Seven better beyond
    noise when its 95% interval's lower end is above zero;
  - the split margin's sample is sized from a pilot of 14 states to take about 50 minutes on 7
    threads, states spread evenly through the file;
  - one evaluation is the median of five on one thread, after two untimed, with nothing else of
    the calibration running, for F1, F2, F2's betting block, F1r and F1 on a half; a generation of
    twelve on the search's six threads, against the same twelve one after another, says what six
    threads are worth, and the budget of section 7 is priced from these;
  - the counts of our own it scores, the theory pass's starts at g and the controls are written to
    a candidates file beside the report and read back, so that file lists the counts scored, to
    the bit. Each section is kept as it finishes, so a calibration stopped part way resumes.

**The theory pass, on the twin,** for starting points and for reading the result. One pass,
every sum weighted 1/q (CountRegression, new):
- **Betting.** Each state's chart advantage regressed on the shares left. It gives the
  betting start (this game's measured effects of removal) and R, the BC ceiling.
- **Insurance.** The insurance value regressed on the shares after the deal must give the
  Ten Count with 1 − R² below 1e-12. The relation is exact, so this checks the code.
- **Each first decision.** For every deal and every legal move other than the chart's,
  V(m) − V(chart) regressed on the shares after the deal, weighted P(i)(1 − P(nat | i))/q.
  The coefficients are this game's index directions. Weighted by each deal's share of what
  the perfect row gains over the chart, they give a playing start.
- **Later decisions.** At each of the 110 contested later spots (FULL_INDEX_PLAYS.md section
  1), V(stand) − V(hit) regressed on the shares at the decision, weighted by its reach over
  q, on the trees of the first 2,000 twin shoes. Weighted by what each spot is worth, they
  give a later-play direction for the full-play choice.
- **Depth.** The R² that 1/n adds to the betting regression: how much the advantage moves
  with depth at an equal composition. No true count sees that; F1r can, in part.
- **As built,** the later regression reads the shares at each decision from the keys of ten
  indicator counts, one rank each at a tag of 1,000,000, whose true count gives the share to
  1e-8; reach is spread from the deals whose first move, the chart's, is Hit, and the walk is
  the chart's later play. A spot's worth is what deviating from the chart there gains, summed
  over its nodes. The playing and later-play starts add each direction weighted by its gain or
  worth, each first turned to lean Hi-Lo's way, since a count and its negative key a decision
  alike.
- **Effects of removal off the top** (EffectsOfRemoval, new). The full 8-deck shoe is valued
  by ExactRound with the chart, then with k cards of each rank removed and with k added
  (central differences): about 21 valuations of about a second each (COUNTING_COMPARISON.md
  section 7). Ethier and Lee computed their game's effects of removal directly, finding
  single-deck ones converted to many decks less accurate; this does the same.

The same regression is run on the main file only after the counts are registered (section 5),
to print R beside the report's BC column. As built, CeilingReport runs it on the states it
scores, within the registered look, and prints R beside every row's BC; CountRegression itself
refuses the main file.

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

**Measured** on one thread at g = 1/20 (calibration-1579078), each the median of five: 1.115 s
for F1, 1.083 s for F2 with both vectors, 0.920 s for F1r, 0.502 s for F1 on half the twin,
and 0.0155 s for a betting vector on a held play. A generation of twelve F1 evaluations took
12.85 s one after another and 4.28 s on six threads at once: six threads are worth 3.0.

**The optimiser is CMA-ES.** It learns correlated directions (the 4, 5 and 6 trade off
against each other) and does not depend on the choice of axes.
- **Space.** Coordinates in an orthonormal basis of the balanced subspace, nine of them,
  under the p⁰-weighted inner product. Each candidate is normalised to Hi-Lo's spread before
  it is scored, so the objective sees only its direction, eight numbers. The search therefore
  puts its mean back on the sphere every generation and scales its step with it, so a step
  keeps its angle. The sign is fixed so the count rises with the advantage, since an isotonic
  bet only rises.
- **Settings.** A population of about 12, a starting step of about 0.3 radians, at most 1,500
  evaluations a run. A run reports its final mean, not its best single evaluation, which
  tends to be the one that fit noise best. The objective is deterministic but piecewise
  constant at small scales, since groups and moves change in jumps, so steps are not pursued
  below the noise floor.
- **Starts.** Every published count's direction is scored once, kind-aware counts by their
  rank averages. One run starts from each of these, 16 in all:
  - the eight best of them;
  - the theory pass's betting and playing starts;
  - the two real-valued vectors in Table 2 of Vidámi, Szilágyi and Iclanzan;
  - four random directions.

  If the runs end in separate regions that differ by more than noise, each region's best
  gets one more run at double the population and double the cap.
- **F1 flat** runs five times: from the 1-8 optimum, the theory pass's playing start, the
  best published count by flat EV on the twin, and two random directions.
- **F2** is first scored at each of its starts: (u, u) at F1's optimum; each published
  side-count pair at g, its count's tags for play and the same with an ace tag of −m for bets;
  and the flat optimum for play with the theory pass's betting direction for bets. The search
  runs from the best three. It alternates two blocks: the betting vector with the play held,
  which is cheap, then the play vector. It stops when neither moves the objective by more
  than the noise floor, after at most three cycles, and ends with a joint polish.
- **F1r** runs three times: twice from F1's optimum with different shifts, once from KO's
  direction and balance.
- **Every evaluation is logged** (tags, objective, run, generation), so a search can be
  audited, its landscape inspected and a stopped search resumed.
- **As built** (CountSearch, Cma): the sign reference is Hi-Lo unless one is given, and the log's
  header names it, so a resume with another is refused. A run's starting step is an angle,
  whatever the scale of the start it is given. F2's blocks each take a seventh of the budget,
  over at most three cycles, and the joint polish a third, so an F2 search may spend somewhat
  more than its cap; the polish runs on the eighteen coordinates of both vectors, kept on the
  sphere of radius √2 times Hi-Lo's, each vector put on its own sphere at the start and
  normalised again before it is scored. The first block of each kind starts at the search's
  step; each later block, and the polish, at the step the block before it of its kind ended at
  (no less than twice the least angle), so it refines rather than searches afresh. A block's
  end, or the polish's, is taken only where it scores at least what its start does, so F2 ends
  at or above its start and every block's end; the log notes the start's score and any end not
  taken. F1r's sphere is its balanced part's, the shift scaled with it, so its step too is an
  angle and the least angle stops it.

**How we know it found the maximum, or how close.** The objective has jumps, so no search can
prove a global maximum. These show that nothing within the data's resolution was missed:
1. **Restarts agree.** Runs from different starts end within the noise floor of the best and
   within a few degrees of it. If they end in separate regions that differ by more than
   noise, each region's best is kept and the twin decides between them.
2. **Slices peak at the optimum.** Slices along each basis direction, each of CMA's principal
   axes on the sphere and the line to each top published count peak at the optimum within
   noise. Quadratic fits to them estimate any headroom left. Near a maximum the loss grows as
   the square of the angle, so every direction within the angle that costs less than the
   noise floor is as good as the data can tell apart. The report prints that angle.
3. **Every start is beaten,** by F1 and by F2 alike. On the twin, each optimum scores at least
   as well as every one of its starts, F2's side-count pairs included, and F2 at least as
   well as F1. Anything else is a search failure.
4. **A planted optimum is recovered** by the same code (section 6).
5. **The grain does not decide it.** Halving or doubling g moves the objective at the optimum
   by less than the noise floor.

**Is the twin big enough?** F1 at 1-8 is searched again on each half of the twin: the shoes
whose index halved is even, and the rest, the learning curve's split. Each half is searched
from the eight best starts of the full search at the same budget a run, with the M_play
coefficient raised to 5√2 so that its groups close at the full twin's size. An evaluation
there costs about half. A thinner search on the halves can only make them look worse, so it
errs toward reporting more shortfall, not less.
- The two half counts are disjoint, so they are compared with each other: their angle, and
  their scores.
- On the main file, the full twin's count less the mean of the two half counts, paired, is a
  learning curve. Near a smooth maximum a fitted optimum falls short by about 1/n, so that
  difference estimates the shortfall the full twin still leaves. It is labelled an estimate
  resting on that rate, since the objective is piecewise constant.
- The twin counts as big enough when that estimate's upper end is under 0.01% a round at 80%,
  1-8, the margin fixed here.

**Full play: choosing on the twin.** Seven F1 counts are fitted with full play: five on the
line from the 1-8 optimum to the flat optimum, at 0, ¼, ½, ¾ and all of the way
(renormalised), and two off it, the 1-8 optimum turned toward the theory pass's later-play
direction by about 10° and 20°.
- The fits use the first 20,000 twin shoes at 80%, both folds, no resamples. The best of the
  seven by 1-8 EV joins the registered counts as the full-play F1.
- The run builds its trees each round (`--trees none`), since no second tree store fits on
  the disk (section 7).
- Twenty thousand shoes give a smaller M_play than the whole file. That is enough to rank
  seven neighbouring directions by their full-play EV, and the main file scores the one
  chosen at full size.
- F2's 1-8 optimum is scored with full play as it is.
- This is a choice among seven, not a search. The full-play row is labelled as what it is:
  a lower bound, the best of seven candidates, not a searched ceiling.

## 5. Honesty

**Registration before the main file.** Before any main-file run, the chosen counts go into a
committed custom-counts.json. For each count it holds:
- the vector or vectors, as whole numbers at K, with the divisor;
- its family, and its role: headline, secondary, check or control;
- the twin file's header and the commit the search ran from.

It also fixes which count is reported as the ceiling in every cell, and the comparators of
each headline row:
- **First decisions.** F1 flat in flat cells, and F1 at 1-8 at every other spread and on the
  own ramp, at every penetration; F2 at 1-8 beside it wherever the bets vary.
- **Full play.** The full-play F1, with F2 beside it.
- **Comparators.** F1's are the plain published rows: each catalog count playing its own fit.
  F2's are every published row playing its own fit, side-count variants included.
  Betting-only rows answer another question and are not comparators.
- Every other custom row (F1r, F1r's tags as a true count, the half-twin counts, the refit
  check) is a labelled check, never promoted in any cell.
- **As built,** a count's entry in custom-counts.json may name its comparators, "plain" or
  "all"; without it the rule above applies, plain rows for a count with one vector and every
  row for one with a betting vector (CustomCounts, CeilingReport).
- **As built,** the registration's "cells" names the counts reported in the flat cells, on the
  same schedule at every spread, and on the own ramp, at every penetration: exactly one headline
  count of one vector, and at most one with a betting vector beside it, none in the flat cells;
  every headline count is named somewhere. A headline claim is made for those (count, cell)
  pairs alone, so a cell's claims are at most two, the Bonferroni pair below. Each cell's claims
  are a statement of their own, not covered jointly with the other cells; the headline is 80%,
  1-8.

**One look.** The main-file tools refuse a custom-counts.json that is uncommitted or modified,
and print its git blob hash. Each run appends a line to a committed ledger,
custom-count-ledger.tsv (commit, blob hash, time, what ran), and the tools refuse to start
while the ledger has uncommitted lines. The report prints the ledger. Nothing is chosen on the
main file: whatever it shows, the tags and g stay as registered. A count changed after a
main-file look is a second look and is reported as one; its held-out claim needs a fresh
file, a third seed, before it may replace the headline. So that no figure needs a second look,
the report saves everything it scores, every estimate and resample of the custom rows, the
bound rows, the refit check and the reference rows, with R and each custom row's BC and IC, to
a new file before it prints (ComparisonReport --save; CeilingReport.save and readSaved), and on
the main file it refuses a look without one. It prints every spread of the same schedule, and
PE, IE, BC and IC, for each custom row; any figure it does not print is worked out from that
file.

**On the main file the custom rows are scored like any count:**
- each fold fitted on the other parity;
- 1,000 resamples from seed 20260929;
- play, insurance and bets refitted on every resample;
- differences paired resample by resample.

**The published rows are not scored again.** FullIndexRun scores every catalog count and
side-count variant at first decisions too, on the same resamples from the same seed, and
runs/full-index/full-c06c061/results.ser holds those entries: estimates, resamples, per-key
tables, decision resolution and half fits. A resample depends only on the shoes, the number
of resamples and the seed, so those rows pair with new ones resample by resample. The peek
already matches report-final to the printed digit at 80%, 1-8 (Hi-Lo +0.2937 against +0.294,
Zen +0.3562 against +0.356). The report reads the published rows from results.ser and scores
only:
- the custom rows: F1 at 1-8 and flat, F2, F1r, F1r's tags as a true count, and the two
  half-twin counts;
- the controls at g;
- the refit check: F1, F2 and Zen with plays fitted on the schedule's bets;
- the bound rows of section 2.

Two checks tie the stored rows to the new code. If either fails, the run stops until the
reason is found. (CeilingReport compares, to the bit, every estimate and resample of every
entry of the three counts scored again; the refit check is run for the headline counts and
Zen. For the second it takes the published report, `--printed runs/report-final-26f0724.md`,
required on the main file, prints its headline section again from the stored rows with
ComparisonReport's own code, and requires every line of it, each row's estimate, SE, bias,
interval, difference from Hi-Lo and simultaneous interval, and each penetration's average bet
and multipliers, to read as published (CeilingReport.checkPrinted).)
- Hi-Lo, Hi-Opt II with its side count, and Red Seven, a kind-aware count, are scored again
  with the new classes, and their entries must equal c06c061's to the bit.
- c06c061's entries are set once beside report-final's printed figures: estimates, SE, bias
  and 95% intervals, and the published families' max-|t| multipliers (at 80%, 1-8, c = 3.30
  for the counts playing their own fits and 2.99 betting only) must match to the printed
  digit.

**The headline claim.** The published families stay as report-final has them: the max-|t|
multipliers over the published rows' differences from Hi-Lo are unchanged, and the custom
rows do not join them. Each headline row k is instead compared with each of its registered
comparators j, paired.
- "Better than the best published count" is claimed for k when the lower end of every one of
  its differences k − j is above zero. The smallest of those lower ends is a lower bound on k
  less the true best published row, whichever row that turns out to be, so naming the best
  one afterwards needs no multiplier over j.
- Each lower end is that of the ordinary 95% interval, a one-sided 97.5% bound, so the two
  headline rows' claims hold together at 95% (Bonferroni over F1 and F2). Full play is its own
  report with the same two rows, and its differences are widened by the larger of the two
  counts' ratios (FULL_INDEX_PLAYS.md section 4).
- The tags part of a gain is bounded the same way, against the controls at g.
- The point difference from the best published row is reported either way.
- **As built,** CeilingReport prints beside each claim its tags part, the smallest lower end
  against the registered controls (role "control"; the plain ones for a count of one vector,
  their side-count variants too for one with a betting vector), and the floor below; and a table
  of the grain part, each control at g less the same count's stored row, paired.

**What the interval means.** On the main file the tags are fixed inputs. A custom row's
interval is therefore for that count's EV on new shoes, the same claim made for a published
count, and needs no widening. It leaves out how the tags would move with another twin. So the
result is a lower bound on the linear ceiling, stated two ways:
- **the floor:** the lower end of the registered ceiling row's interval. Where a published
  row of the same family scores higher, that row is the better floor and the searched count's
  shortfall is reported beside it. The floor is then the largest lower end among the family's
  registered and published rows, each taken with the max-|t| multiplier over those rows' EVs,
  so choosing the largest is covered;
- **the gain:** the smallest lower end of the headline claim above.

As built, the floor is printed beside each claim, at its cell, over the row and its comparators.

Three measurements say how far below the ceiling that may sit:
- **Optimism,** for every registered custom row: (row − Zen) on the twin, less (row − Zen) on
  the main file. For the full-play F1 the twin figure comes from the 20,000-shoe choice and is
  labelled so. The files are independent, so their SEs add in quadrature. The same figure for
  a published pair, Hi-Opt II with its side count less Zen, is the control and should be zero
  within noise.
- **Search sufficiency:** the learning curve of the half-twin searches (section 4).
- **The bounds** of section 2, from above: R beside the best published BC, and the perfect
  rows.

**How a small gain is read.** A held-out gain at or below zero is a legitimate answer, not a
failure. If the optimism is large and the gain within noise, the report says that this search
could not find a better count from the twin's 60,000 shoes. It says the published counts sit
near the linear ceiling only if the learning curve also puts the shortfall still left under
the 0.01% margin, and it prints R and the perfect rows beside that, as the room left above. On
the main file, F2 below F1 is read as F2's extra eight numbers fitting the twin's noise, and
reported as such.

**Full play on the main file.** FullIndexRun is run on the full-play F1, F2, Hi-Lo as a
control, and two controls at g, the best plain one and the best side-counted one (each count
brings its side-count variant), with the same seed and resamples. Its Results are paired with
runs/full-index/full-c06c061/results.ser.
- Hi-Lo's full-play Result must match c06c061's within 1e-12, the joint run's own standard
  (FULL_INDEX_PLAYS.md section 3).
- The custom rows and the controls take the stated conservative widening defaults, 1.07 for
  differences and 1.80 for gains (FULL_INDEX_PLAYS.md section 4), since the pilot did not
  measure them.
- The full-play perfect bound comes from results.ser, which holds each state's full-scope
  perfect value (FullIndexFit.perfect). The ceiling's own-value row needs each state's round
  value under its full play, which results.ser does not keep, so it is worked out in the
  run's scoring.
- The two controls split the full-play gain into grain and tags, as at first decisions.

## 6. Checks

Each check is a committed test that must pass, and must fail on the planted bug it names.
- **Published counts unchanged.** With divisor 1, every catalog count's bet and play keys are
  bit-identical on the test fixtures. On the main file, three counts scored again match
  c06c061's entries to the bit (section 5).
- **Stored results readable.** One c06c061 item checkpoint and its results.ser are read with
  the new classes, and a few entries set beside the peek's. A zero divisor reads as 1 and a
  missing betting vector as the play vector.
- **Invariances:**
  - every tag plus a constant gives the same true-count keys;
  - tags times k with the divisor times k give the same keys, in the true and the running
    form;
  - doubling K changes only keys that sit on a rounding boundary;
  - colour-blind tags written over the 26 kinds or over the 10 ranks give the same keys;
  - rounding at K leaves the deck sum zero;
  - the largest tags the normalisation allows cannot overflow the running count.
- **F2 contains the side counts.** An ace tag of −m in the betting vector reproduces each
  "+ ace side count" variant's bet keys, to the bit.
- **The betting vector is never dropped.** An F2 count's bet keys differ from its play
  count's in ComparisonReport and in both of FullIndexFit's scoring paths.
- **A full-index run's identity names the tags.** It holds each count's whole-number tags,
  divisor, betting vector and form (running or true), and custom-counts.json's blob hash on
  the main file. A resume with any of them changed is refused, saying what differs.
- **A planted optimum.** SystemScorerTest's synthetic states get move values a_im + b_im ×
  (u*·x), linear in the composition along a hidden direction u*, and a chart advantage that
  rises along it.
  - From Hi-Lo, F1 recovers u* (cosine ≥ 0.99) and beats every start.
  - With different hidden directions for betting and play, F2 recovers both and F1 lands
    between them.
- **CMA-ES** reaches the known optima of the sphere, a rotated ellipsoid and Rosenbrock in 8
  dimensions, and of a scale-invariant one, the cosine to a hidden direction with noise
  added, scored through the same normalisation the counts use; the same way for the same
  seed.
- **Theory-pass anchors:**
  - the insurance regression gives the Ten Count, with 1 − R² below 1e-12;
  - a BC objective searched by the same optimiser matches the regression's direction (cosine
    ≥ 0.999), and the report's BC code gives R for it;
  - on hand-made states with values planted linear, the regression recovers them to 1e-9.
- **Bounds.** On a tiny state set each bound row equals a brute-force sort. No row exceeds its
  bound on any resample, in flat and same-schedule cells.
- **Leakage guards.** The search tools refuse the main file; the main-file tools refuse an
  uncommitted custom-counts.json or ledger; the twin's shoe seeds collide with none of the
  main file's.
- **Grain.** The fine-grain count's decision bound stays under the 0.005% target, and its
  learning curve holds, as for any count.

**On the twin, before registration,** these mean a bug or a failed search, and are fixed
there:
- any row above its perfect bound, which means a scoring bug;
- the search ending below one of its starts, F2 below F1, or F2 below a published side-count
  pair at g, which means an optimiser failure;
- restarts disagreeing beyond noise, or the two half-twin counts far apart (cosine below 0.95)
  with similar scores. Then the top is a flat ridge, and a region is reported, not a count;
- the twin's published differences disagreeing with report-final's beyond chance;
- a tag whose sign contradicts this game's measured effects of removal with no play decision
  to explain it, or a score that spikes under small changes of the tags, which would be a
  grouping artefact;
- a full-play gain on the twin's 20,000 shoes far outside the +0.059 to +0.093 the published
  counts show.

**On the main file nothing is changed.** The tags and g stay as registered, and each of these
is reported as found:
- the gain vanishing at equal grain: then it was grain, and the report says so;
- the gain coming mostly from one penetration;
- F2 below F1, or a published row above the searched one;
- a full-play gain outside +0.059 to +0.093.

A row above its perfect bound on the main file is still a scoring bug: the run stops until
the code is fixed, and the tags stay as they are.

Each gain is split into play, insurance and bets as far as the report's columns allow: the
flat table's play and insurance columns, and the betting-only rows at 1-8. Insurance is linear
in the ten share, so a gain there is a real gain for a linear count, not a warning sign.

## 7. Cost on this machine

Nothing reads a state file until full-c06c061 has written results.ser, due about 04:00 on 7
October. At 00:56 that day its log showed 138 of 186 items scored
(runs/full-index/full-c06c061.log); it wrote results.ser at 03:51.

| Step | Machine time (estimate) | Disk |
|---|---|---|
| Twin file | 3.2 h on 7 threads, measured (11,637 s, runs/twin-80.log) | 1.34 GB |
| Calibration and theory pass | 2 to 3 h | |
| Searches and slices | 5.5 to 6.1 h on 6 threads, priced from measured evaluations | logs |
| Full-play choice on the twin | 1 to 3 h, after a dry run on 2,000 shoes | none, trees rebuilt |
| Main file, first decisions, 1,000 resamples | about 3 h on 7 threads | a report |
| Main file, full play | 4 to 8 h with the tree store, about 7 h more without; after a dry run | results |

In all, about 21 to 30 machine hours with the tree store kept, more on the wall.

Where the estimates come from:
- **The searches.** Budgets in evaluations: F1 at 1-8, 16 runs of 1,500, 24,000, and up to
  6,000 more if the runs end in separate regions; F1 flat, five runs, 7,500; F2, 6,000 plus
  about 30,000 cheap betting-only ones; F1r, 4,500; F1 on the twin halves, 16 runs at about
  half the cost, about 12,000 in whole-twin terms; slices, the grain check and the noise
  floor, 1,000. That is about 55,000 to 61,000. At about 2 s each, with 6 threads taken as
  worth about 4 (COUNTING_COMPARISON.md section 7 found that CPU time does not add up across
  threads on this machine), the estimate was about 8 hours. Priced instead at the times
  calibration measured (section 4: 1.115 s an F1 evaluation, 6 threads worth 3.0), they come to
  5.5 to 6.1 machine hours, the betting blocks 0.04 of them (calibration-1579078's budget
  table).
- **Memory.** The states take about 1.6 GB in memory (FULL_INDEX_PLAYS.md section 6). Each
  evaluation running at once holds the play keys of 60,743 × 550 (state, deal) cells as ints,
  134 MB, plus its fits: about 250 MB (estimate), so six at once take about 1.5 GB.
- **Main file, first decisions.** About nineteen counts are scored: seven custom rows, six
  controls, three refit checks and three counts scored again for the bit check. At
  report-final's mean of 4,076 s a count, with 7 threads, that is about 3 hours. The catalog's
  18,623.7 s (5.2 h) are not spent again (section 5).
- **Main file, full play.** The projection put the whole-file run at 23.7 hours for 31 counts
  at 1,000 resamples (runs/full-index/projection-73df32c.txt), about 0.8 h a count on 6
  threads. A count fitted alone shares no split values with the others, so five counts take
  more than five times that. Without the tree store every round builds the trees again, at
  0.0333 wall s a state on 6 threads (the projection), about 2,000 s a round. Five counts make
  30 items in about six batches, each with its last round, after about six rounds of passes:
  about twelve rounds, about 7 h more.
- **Wall time.** These are machine hours while the machine is awake. Report-final ran 13.6 h
  from start to finish (runs/report-final.status) against 5.2 h on its own timer: the
  machine slept through much of the night on battery (pmset's log). StateRun, FullIndexRun and
  CountSearch resume after an interruption; ComparisonReport does not, so step 7 is rerun
  whole if it is cut off, which at about 3 hours is bearable.

**Disk.** On 7 October the disk had 12 GB free, 95% used (df), and the full-c06c061 directory
took 14 GB (du). The twin's 1.34 GB fits. A second whole-file tree store, 14.9 GB, does not.

**A hazard.** TreeStore deletes a store whose identity differs from its own, and the identity
includes the commit (TreeStore's constructor). So a new run must never be pointed at
runs/full-index/full-c06c061/trees.bin, except through the read-only mode of step 1. That
mode keys on the state file and its states alone, opens the store read-only and never
deletes it. FullIndexRun takes it as `--trees-read-only PATH`, and refuses a `--trees PATH`
whose store another run kept, rather than starting it afresh; a tree the store does not hold
is built in each round and not kept. Nor is anything at a `--trees` path that is not a tree
store ever deleted: a file there without an index beside it (a mistyped path to
runs/montreal-80.states, or to a run's results.ser or a store's index), a directory, or an
index that does not read as one is refused before anything is written, and a new store writes
its index before its first tree, so no store of this code stands without one
(TreeStore.refuseNonStore).

## 8. Steps

0. **Wait** for full-c06c061's results.ser. Until then, only code and tests on fixtures.
1. **Code and tests,** minutes of machine time:
   - real-tag counts in CountKeys: whole tags at K, rounded so the deck sum stays zero; the
     key divisor, in the true and the running form; an optional betting vector; the
     side-count variant carrying the divisor;
   - a betting-variant hook, used by ComparisonReport.scoreAll and both of FullIndexFit's
     scoring sites;
   - SystemScorer's point estimate, the betting-only re-evaluation, the half fit's EV at 1-8,
     and the refit with schedule bets as weights;
   - the bound rows, the own-value row inside full-play scoring, and a committed measurement
     of perfect split play;
   - CountRegression, its later-decision part included, and EffectsOfRemoval;
   - CountSearch: CMA-ES with its mean kept on the sphere, its log and resume;
   - custom-counts.json, a candidates file for the twin, the ledger, and their loaders;
   - ComparisonReport reading the published rows from c06c061's results.ser and scoring only
     the counts it is given;
   - `--counts` (custom-counts.json or a candidates file) and `--pens` for FullIndexRun and
     FullIndexDryRun; the tags in a run's identity; TreeStore's read-only mode;
   - c06c061's files kept readable: the serialised classes only gain fields, a zero divisor
     reads as 1 and a missing betting vector as the play vector. If that cannot be kept, the
     arrays needed are first exported from results.ser to a plain format with
     classes-c06c061. (It is kept: every class those files reach already declares
     serialVersionUID 1, as serialver on classes-c06c061 confirms, so no export is needed;
     CeilingScoringTest reads a Result those classes write, and StoredResultsTest a part the
     run wrote.);
   - the refusals, and every test of section 6.

   *Produces:* commits, with the suite passing.
2. **The twin file.** *Produces:* runs/twin-80.states, its log, the collision check, and the
   rate over its first hour.
3. **Calibration and the theory pass,** on the twin. *Produces:*
   runs/custom-count/calibration-<sha>.md, with the evaluation time, the proxy check, the
   noise floor both ways, g, every published row at g and the controls, the colour check, the
   split margin, the effects of removal, R, the later-play direction and the depth figure.
   It is one command, from classes frozen from one commit with the commit and the catalog beside
   them, as the full-index run's are; run again after an interruption, it resumes from the
   sections it kept in calibration-<sha>.md.parts:

   ```sh
   R=/Users/justinbondurant/Documents/CardCountingEdgeCalculator
   W=$R/.worktrees/count-design
   SHA=$(git -C $W rev-parse --short HEAD)
   C=$R/runs/custom-count/classes-$SHA
   T=$(mktemp -d) && git -C $W archive HEAD src counting-systems.json | tar -x -C $T \
     && mkdir -p $C && javac -nowarn -d $C -cp $W/lib/mongo-java-driver-3.12.14.jar $T/src/*.java \
     && git -C $W rev-parse HEAD > $C/commit.txt && cp $T/counting-systems.json $C/
   cd $C && nohup caffeinate -i nice -n 5 java -Xmx10g -cp "$C:$W/lib/mongo-java-driver-3.12.14.jar" \
     Calibration --states $R/runs/twin-80.states --out $R/runs/custom-count/calibration-$SHA.md \
     --published $R/runs/full-index/full-c06c061/results.ser --printed $R/runs/report-final-26f0724.md \
     --twin-log $R/runs/twin-80.log --threads 7 >> $R/runs/custom-count/calibration-$SHA.log 2>&1 &
   ```
4. **The searches and slices.** *Produces:* the evaluation logs and the candidate counts,
   with the convergence evidence of section 4.
5. **The full-play choice,** on the twin. *Produces:* the full-play F1.
6. **Registration.** *Produces:* custom-counts.json, committed, with the counts, each cell's
   reported count and each headline row's comparators.
7. **The main file, first decisions.** *Produces:* runs/report-ceiling-<sha>.md: every cell,
   the ladder, the grain and tag split, the difference from every published row, the headline
   bounds, the efficiencies against the bounds, the optimism, the learning curve and the
   ledger; and runs/report-ceiling-<sha>.ser, everything the run scored (`ComparisonReport
   runs/montreal-80.states --published runs/full-index/full-c06c061/results.ser --printed
   runs/report-final-26f0724.md --counts custom-counts.json --save runs/report-ceiling-<sha>.ser`).
   The optimism needs the twin's figures beside it and is worked out from the two files.
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
  run would both need a count per fold. The twin costs 3 to 5 machine hours and 1.34 GB, and
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
- **The custom rows inside the published max-|t| families.** That would change every
  published row's simultaneous interval, and ask of the headline a gain about 1.7 times
  larger than its claim needs (c of 3.30 against 1.96). The intersection bound of section 5
  covers the choice of the best published row without it.
- **Scoring the whole catalog again** beside the custom rows. FullIndexRun has already scored
  every published row at first decisions on the same resamples, so that would spend about
  five hours reproducing numbers on disk. Three counts scored again, to the bit, check the new
  code instead.

## 10. Questions for Justin

1. **Is F2 part of the answer?** F1 is one tag vector for everything. F2 lets a second vector
   set the bets, as the side-count rows that lead the table already do. *Default:* both, with
   F1 the headline. F2 adds about an hour of search and a few rows.
2. **Which cells get their own search?** Proposed: 80% at 1-8, and 80% flat. Every other cell
   is scored with those counts and labelled as not its own ceiling. A search per penetration
   or spread, at the flat search's budget (7,500 evaluations), costs about an hour each
   (estimate), and "the ceiling" then becomes a family of counts. *Default:* the two.
3. **Disk.** It is 95% full, 12 GB free on 7 October. May the twin file (1.34 GB) be written,
   and may the c06c061 tree store (14 GB) be kept until step 8 reads it read-only? Keeping it
   saves about 7 hours of step 8 (estimate, section 7). *Default:* write the twin, keep the
   store and delete nothing. If the store is gone by then, step 8 builds trees each round
   instead.

## 11. What it does not do

- **Practical counts.** Justin chose the ceiling only: no rounding to a level a person could
  keep, and no ceiling tags at a published count's grain.
- **Nonlinear counts, or keys of two numbers,** such as the count and the depth together. The
  theory pass's depth figure says how much the second would matter.
- **A separate count for each decision.**
- **Tags tuned per penetration or spread,** unless Justin asks (question 2).
- **Plays fitted for the bets.** Plays are fitted flat, as for every published row; the refit
  check says what that leaves.
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
