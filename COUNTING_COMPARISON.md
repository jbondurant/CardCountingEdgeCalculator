# Comparing counting systems on shared shoes

This measures what each published counting system (counting-systems.json) is worth at the
Montreal table. It covers bet spreads from flat to 1-8 and cut cards at 70, 75 and 80%,
and it is measured so that the differences between systems are larger than the noise, with
every play decision settled by enough data.

The simulator estimates one table per count by dealing hands and averaging payoffs. Its
noise per cell is about ±0.005 at 50,000 hands, larger than the flat-bet differences
between most systems. This design removes most of that noise in three ways:

- **Exact values.** Every value is exact for the shoe it is computed on (ExactRound,
  ROUND_CONTRACT.md). The only sampling left is which shoes occur.
- **Shared shoes.** Every system is scored on the same shoes, so their differences are
  paired: the noise from which shoes came up largely cancels.
- **Valued once.** Every shoe state is valued once. Systems, penetrations, spreads and
  conventions are all scored from the stored values, so adding a system costs minutes,
  not days.

An independent review, with four reviewers and a skeptic on every serious finding,
changed sections 5 to 8. Section 11 lists what it found.

## 1. The game

**Rules.** Montreal's, from HouseRules.getMtlCasino25MinBlackjackParams, which match
Loto-Québec's published rules:
- 8 decks; the dealer hits soft 17 and peeks; blackjack pays 3:2.
- Double on any two cards, and after a split except after splitting aces.
- Split to 4 hands; aces split once and take one card.
- No blackjack on split pairs, no surrender, insurance offered.

**The table.** One player, one hand per round. There is no burn card, and every card dealt
is seen, the dealer's hole card included once the round ends.

**The cut card.** A round is dealt while fewer cards have been dealt than the cut position:
291, 312 or 332 cards (70, 75 and 80% of 416, rounded down). The shoe is shuffled after the
round in which the cut card comes out.

## 2. Shoes and states (ShoeRun, built)

**Shoes.** A shoe is a seeded shuffle of 416 cards of 26 kinds: rank, with J, Q and K
apart from the ten, and colour. Red Seven and the KISS counts need both. Shoe i has its
own generator, so any shoe can be dealt again alone.

**Dealing.** The shoe is dealt to the 80% cut card in table order: player, up-card,
player, hole card, then draws. ConcreteRound plays each round with basic strategy, so
cards come out in realistic amounts. Averaged over every order of a small shoe,
ConcreteRound's payoff equals ExactRound's value within 1e-12.

**States.** A state is the shoe at the start of a round. Every round start before the cut
card is a state, so states come at their natural frequency.

**The 70% and 75% games.** They are the same shoes stopped earlier: a state at depth d
belongs to the P% game when d < cut(P). One run at 80% gives all three games, paired
with each other as well as across systems.

**Which states are valued.** Valuing a state is the expensive step, so only some are
valued, each with its own chance q. A valued state stands for 1/q states. Unusual shoes
matter most once bets vary, so q grows with how far the cards left are from a full
shoe's proportions:

    u = (1/N) × Σ_r (n_r − N p_r)² / (N p_r),     q = min(1, q0 × (1 + u/u0))

u favours no counting system: it is the same for a shoe rich in tens as for one rich in
fives. Each state gets one uniform draw from the shoe's own second generator, whether or
not it is valued. So the cards never depend on q, and a larger q0 values every state a
smaller one did.

**The run.** q0 = 0.008 and u0 = 0.02 value about one state per shoe of about 61 rounds,
weighted toward the deep end of the shoe. The seed is 20260928.

## 3. What is stored per state (StateValuer, StateStore, built)

**Values.** For each of the 550 deals (unordered player pair p1 ≤ p2, up-card u; Deals),
V(m) is stored for every legal first move m: Stand, Hit, Double, Split on a pair, and
Surrender where offered.
- V(m) is ExactRound's value from the cards left after the deal, conditional on no
  dealer natural, with basic strategy playing every later decision.
- The entry is NaN where the move is illegal, where the player has a natural, and where
  the cards left cannot make the deal.
- That is about 1,720 values per state.

**The record.** Each record also carries the shoe, the round, the depth, q, and the cards
dealt so far by kind (26 tallies).

**Derived, not stored.** P(deal), P(dealer natural | deal) and, with an ace up, P(hole
ten | deal) are functions of the cards left. Deals computes them, and it is the one place
that does.

**The file.** Fixed-size records, written a whole shoe at a time and forced to disk
before a progress file names the shoe as done. The header pins the seed, q0, u0, the cut,
the rules and a fingerprint of the chart. A stopped run resumes without loss, and a run
with other settings is refused. A shoe with no valued state writes nothing but still
counts as done, so every state of every done shoe can be dealt again from the seed.

**The value of a round** (RoundValue), for a player who plays move σ(i) in deal i and
insures in deal i when ι(i):

    EV = Σ_i P(i) × [ P(nat|i) × (natural ? 0 : −1) + (1 − P(nat|i)) × (natural ? payout : V_i(σ(i))) ]
         + Σ_{i: u = ace, ι(i)} P(i) × 0.5 × (3 × P(hole ten|i) − 1)

A dealer natural takes only the original bet, since the dealer peeks, and it pushes a
player natural. Even money on a natural is the same bet as insurance. RoundValueTest holds
this whole formula to playing every order of a nine-card shoe, within 1e-12.

## 4. Basic strategy (BasicStrategy, built)

**The chart.** The 4-8 deck H17 DAS chart, with late-surrender rows that Montreal never
reaches. It plays the rounds that take cards out of a shoe, and every decision after a
first move.

**Checked against the engine.** Off the top of an eight-deck shoe, its first move is
ExactRound's best for all 540 two-card hands.

**First move only.** No system varies the play after the first decision. So a system's
index plays act on the first decision and insurance only; a stiff hand of three or more
cards is played by the chart. That undervalues every system, and the high
playing-efficiency counts most. Stage 3 measures the size of this for Hi-Lo, Omega II and
KO. Until then, flat-bet results are labelled "first-decision indices".

## 5. Counts

**Scale.** A system's count is kept in whole numbers. Card tags are scaled by the
smallest factor that makes every card's tag whole: 2 for Wong Halves and C-R, 1 for the
rest.

**Drift.** The running count RC starts at 0 at the shuffle. For an unbalanced system,
RC_b = RC − deckSum × dealt / 52 removes the drift; a balanced system's RC_b is RC. The
published starting count is a constant shift and changes nothing here.

**Two keys per state and deal.** Each state has a **bet key**, fixed before the cards
come out. Each (state, deal) has a **play key**, which includes what the player sees
when deciding.
- **A system marked `running`** keys on its running count.
  - Bet key: RC.
  - Play key: RC plus the tags of p1, p2 and the up-card.
- **A system marked `true`** keys on its true count, RC_b over decks left, rounded to
  the nearest whole number with ties toward zero. That is the simulator's own rounding,
  GranularCount.roundToGrain(x, 1.0).
  - Bet key: the true count with the cards left before the deal.
  - Play key: the true count with the deal's three cards counted and removed.
  - Decks left is the cards left over 52, unrounded.
- **Colour or face.** For Red Seven and the KISS counts, a dealt card's tag depends on
  its colour or face, which the stored rank does not say. The deal's weight is split
  over the tag classes of its three cards, with the exact chances of each class given
  the kinds left, drawn without replacement. Each part goes to its own play key.
- **Ace side-count variants** are scored as separate entries. The balanced ace-neutral
  systems are Hi-Opt I, Hi-Opt II, Omega II, Canfield Expert, Revere Advanced
  Plus-Minus, Revere 14, Uston APC and Victor APC. Their bet key adds m × (aces left −
  cards left/13) to RC before dividing, where m is the ten's tag in absolute value (QFIT's
  procedure; it gives Victor's and Uston's 3). The play key stays the plain count.
- **The simulator's convention** is used only in Stage 3's check of Hi-Lo against the
  simulator: decks rounded up, and the round's own cards uncounted.

**Duplicates.** Systems whose tags are identical once scaled to whole numbers, and that
are counted the same way, give identical results. Tags that are only multiples of each
other would not: a true count of twice Hi-Lo's tags, rounded to whole numbers, steps in
halves of Hi-Lo's, so its keys are finer. No two catalog systems are such multiples. The
duplicates are reported as one row naming all of them:
- Hi-Lo and Hi-Lo Lite
- KO and REKO
- Omega II and Canfield Master
- RPC, FELT and C-R

## 6. Fitting and scoring

### Folds, penetrations and weights

**Folds.** Shoes are split into two folds by the parity of their index. Each fold's
strategy is fitted on the other fold and scored on its own, so no system is graded on
the noise it was fitted to. The two scores are pooled as ratios of summed totals, not
averaged.

**Penetrations.** Everything is fitted and scored per penetration P, on states with
depth < cut(P). What a running count means depends on depth, so a fit made on 80%
states would be wrong for the 70% game.

**Weights.** Every sum over states weights a state by 1/q.

### Play

**Groups.** Play is fitted per group of play keys. Groups are formed on the training
fold:
1. Start at the play-key value holding the most states, each valued state standing for
   1/q of them as everywhere else, since that is where the rounds are.
2. Walking outward in each direction, close a group once it holds at least M_play valued
   states: M_play = 5 √n, with n the valued states of the training fold in the
   penetration. That is about 110 in the pilot's 80% game and 560 for a training fold of
   12,500. This threshold counts valued states, unweighted, because it is about how much
   data a group's decisions rest on.
3. A partial group at either end joins its neighbour.
4. Test states outside the training range join the end group.

So both tails are as fine as the data allows, and no system's range is cut off by a
fixed floor. A play key belongs to a deal, so a state counts once, spread over its deals'
play keys by their chances.

**Why M_play grows with the run.** With M_play fixed, a longer run only makes more
groups of about M_play states each: no decision rests on more data, so the close calls
do not settle and their summed bound stays about where it is. Measured on the start of
the run at a fixed 150, doubling the shoes did not lower the bound. A threshold growing
in proportion to the run would keep the same groups and give nothing to finer play. Growing it as the square root splits the gain: a run four times as long has
about twice as many groups, each resting on twice the data. How fast the bound then falls
is measured on each run, not assumed (section 8).

**The move.** For each (group, deal): the move maximising the sum of
(1/q) × P(i) × (1 − P(nat|i)) × V_i(m), over training (state, deal) pairs whose play key
falls in the group.

**Insurance.** For each (group, ace-up deal): insure when
Σ (1/q) × P(i) × 0.5 × (3 × P(hole ten|i) − 1) > 0. This weight has no (1 − P(nat))
factor, because insurance is settled before the peek.

**Decision resolution**, as the owner asked, so that every decision is shown to be
optimal or shown not to matter:
- For each (group, deal), compare the fitted move with every other legal move, not just
  the runner-up: a third move whose values swing more can be the one not ruled out.
- Each gap Δ gets a standard error from the spread of the per-shoe contributions.
- A decision is **settled** when Δ > 2 SE for every other move.
- An unsettled decision is a close call. The true gap is at least Δ − 2 SE, so keeping
  the fitted move costs at most its frequency × (2 SE − Δ) per round against that move.
  The close call is bounded by the largest of these over the moves it is not settled
  against. This is what the fitted play can lose, the question the check asks. An
  earlier version used frequency × (Δ + 2 SE), which bounds how much the choice between
  the two moves matters either way and overstated the fitted play's cost several times.
- Insurance per (group, ace-up deal) is checked the same way, insuring against not.
- The report gives, per system and penetration, for the play and for insurance: the
  number of decisions, the number unsettled, and the summed bound. The **decision bound**
  is the two added.
- A run is big enough when the decision bound is under 0.005% of a unit per round for
  every system. Insurance is a fitted decision like the first move, so its close calls
  count; they can be larger than the play's.
- The **learning-curve check** must agree. Play and insurance are fitted again on half
  of each training fold, the shoes whose index halved is even, and scored on the same
  test states. The change in the flat EV from the full fit must be shown under the
  decision target: its whole 95% interval inside ±0.005%. The interval is taken resample
  by resample, the half fit refitted on each resample like everything else, so the shoe
  luck the two fits share cancels and what is left is how much the fit still moves with
  the data. Halving the data moving the EV by less than the target means doubling it
  would move it less still.
- Comparing the change with the interval on the flat EV itself, about ±0.08% on the
  start of the run, would not do: that interval is mostly shoe luck, which the two fits
  share, so the check could not fail at the scale the run is sized for.

### Bets

**Advantage.** For each training state, RV is its round value under the fitted play and
insurance. The advantage â(k) is the isotonic (non-decreasing) fit of RV on the bet key
k, weighted by 1/q (pool-adjacent-violators over the key's integer values). A monotone
fit does not let sparse tails set the bets, and it needs no floor.

**Two ways to bet, spreads S = 1 to 8:**
- **Same schedule (headline).** Every system bets the same distribution of bet sizes.
  The reference is the proportional rule below applied to each state's true
  basic-strategy advantage. Each system places those bets in order of its own â: its
  best states get the biggest bets, and a key value that spans a range of the reference
  distribution gets that range's average bet. Keys whose fitted advantage is equal, as
  pool-adjacent-violators makes them, form one block that spans one range and shares its
  average bet: the fit does not rank them, so ordering them by key would hand one a
  bigger bet on no evidence. The average bet is the same for every
  system, so EV per round compares count quality directly. Unlike a rule that sizes
  bets from a system's own estimate, a coarser count cannot gain by rounding into
  bigger bets.
- **Own proportional ramp.** units = clamp(â / 0.25%, 1, S), unrounded. The report
  gives EV per round, average bet and EV per unit bet. A whole-unit version (round half
  up) is shown beside it as "as played".

### Scoring

On the test fold's states in the penetration, EV per round is Σ w × bet × RV / Σ w, with
RV under the fitted play and insurance. Also reported:
- the average bet;
- the per-key frequency, advantage and bet, all from the test fold;
- the paired difference from Hi-Lo on the same states.

### Reference rows, scored the same way

- **Chart, no count, flat.** The house edge for these rules. It should be about −0.6%.
- **Null count.** Every tag 0, fitted the same way: one group, so its play is the best
  two-card play pooled over all states. This is the baseline for efficiencies, because
  it absorbs whatever the per-deal fit gains without any count.
- **Betting-only variant of each system.** The chart plays, and the count sets the bets
  and insurance. This is how many shoe players use a count.
- **Perfect first move.** Each state plays its own best first move and insures exactly
  when insurance pays, flat. No first-move-only strategy can do better.
- **Efficiencies.** PE = (system − null) / (perfect − null), flat, without insurance.
  Insurance efficiency is computed separately in the same way.
- **Empirical BC and IC.** The correlation between the unrounded, drift-free true count
  RC_b / (cards left/52) and, for BC, the state's chart advantage without insurance, or,
  for IC, the chance the next card is a ten. A state's chart advantage is known only
  where it was valued, so BC is taken over the valued states, each weighted 1/q, which
  estimates the correlation over all states. IC needs only the cards, so it is taken over
  every state dealt, the file's shoes dealt again. Every system is computed on this true
  count, running-count ones included, so the ranking can be checked against the
  published figures. A side-count variant's BC uses its adjusted count; its IC is its
  base's, since it insures on the plain play key.

### Uncertainty

**The bootstrap.** Shoes are resampled within each fold, with 1,000 multiplicity vectors
drawn once from a fixed seed. The same vectors are used for every system, variant,
penetration and spread, so each difference is taken resample by resample and stays
paired. Each resample refits everything the estimate fits, from its own resampled
training shoes: the play's groups and moves, insurance and the bets. A resample scores
exactly as a run holding each drawn shoe as many times as it was drawn would be scored,
which SystemScorerTest checks. So an interval carries how much a count's play fit moves
with the shoes it was fitted on, and a difference between two counts carries both fits
moving.

Keeping the play as fitted and refitting only insurance and the bets would leave the play
fit's own spread out of every interval, and for a difference from Hi-Lo it is most of the
spread. On the run's first 1,417 shoes, the SE of the 1-8 same-schedule difference from
Hi-Lo at 80% comes out 0.011% for KO with the play kept and 0.034% with it refitted; Red Seven's 0.009% and 0.023%, Omega II's 0.011% and 0.025%, Wong Halves'
0.010% and 0.020%. The decision-resolution check answers a different question: whether
each fitted decision is right, not how much the estimate moves.

**What is reported.** The bootstrap SE, its bias (mean of the resamples minus the
estimate), and 95% intervals. The intervals are basic intervals if the bias is not small
next to the SE. The summary table also gives the maximum-|t| simultaneous interval over
systems within each spread and penetration, since about 40 systems are compared at once.
The family is the systems compared: every system and side-count variant playing its own
fit, less Hi-Lo, whose difference from itself is nothing. The betting-only rows are a
second family with a multiplier of their own, since they answer another question. The
null count is a baseline, not a system compared, and is in neither.

## 7. Timing

**Measured, not estimated.** Measurements under load showed that CPU seconds do not add
up across threads on this machine (MacBook Air M1: 4 performance and 4 efficiency cores,
fanless, and Bluetooth and Drive-sync daemons taking about 2 cores). So the report gives
two numbers from committed code:
- the single-thread seconds per valued state;
- the sustained wall seconds per valued state at 8 threads, which StateRun prints every
  25 shoes.

**What the report shows:**
- the wall time of the run;
- the time to fit and score one system from stored states, which is the marginal cost of
  a system. Most of it is the bootstrap refitting the system's play on every resample,
  penetration and fold. A resample's play is valued from the estimate's by the (key,
  deal) pairs it plays differently, which gives the same sums up to rounding for a small
  part of the cost of valuing every state again;
- the simulator's time per table, measured the same way (wall time and threads).

**The split speed-up.** In the split valuation, another hand's second card matters to a
hand only by being the split rank or not. ExactRound now values each split hand once per
second card, weighted by hypergeometric chances. It changes the order of summation, so it
is held to ExactVsBruteForceTest at 1e-12, and to the earlier engine's values on four
eight-deck states by ExactRoundEightDeckTest. Keeping the dealer's distributions from one
call to the next within a state then sped up the 1,620 calls of a state that do not split,
without changing any value in any bit.

**Timing a change.** The machine is shared, and what a call costs, its CPU time included,
depends on what else is running. So a before and an after are measured in one sitting, one
engine straight after the other, and quoted together; a figure from another sitting is not
comparable with them. StateTimingReport's second pass, single-threaded, gives the mean CPU
seconds a state over its four states, with the splits and the rest apart. In one sitting on
28 September 2026, twice round, with a load average of 230 to 290 from other work and
nothing else of mine running:
- before the split speed-up (13f29ed): 13.82 and 15.02 s a state; splits 12.59 and 13.66,
  the rest 1.22 and 1.36;
- each split hand valued once per second card (4a6d568): 1.73 and 1.69; splits 0.49 and
  0.47, the rest 1.24 and 1.23;
- the dealer's distributions kept within a state (f5f923b, 9ad4b8f's engine with a count
  that tests read): 1.04 and 0.99; splits 0.59 and 0.56, the rest 0.45 and 0.43.

So the new split valuation cut the splits' CPU time about 26 and 29 times, round by
round, keeping the distributions cut the rest about 2.8 times, and a state takes about a
fourteenth of the CPU time it did (13.3 and 15.2 times less). The last two engines value
splits the same way; the tenth of a second between their splits is noise, which went one
way and then the other in 9ad4b8f's own two measurements (0.51 to 0.54 s, then 0.54 to
0.46). Every engine's values summed to the same twelve decimals on each state.

Under more load the ratios move by more than they do between two quiet rounds, and not
always in the same part. With ExactVsBruteForceTest running over and over alongside (up to
eight threads), the three engines gave 13.81, 2.50 and 1.17 s a state (splits 12.29, 0.80
and 0.64; the rest 1.52, 1.71 and 0.53): the splits' gain fell to about 15 to 19 times and
a state's to about 12, while keeping the distributions still cut the rest about 3 times.
With the whole test suite running alongside, in the order last, middle, first, middle and
last engine, they gave 0.97, 1.89, 15.67, 1.98 and 1.16 s a state: about 13.5 to 16 times
in all, the splits about 22 to 23 times, the rest cut 2.5 to 2.8 times. A review's run with
other tests going saw the opposite pattern, the gain on the rest shrinking and the splits'
gain holding. So a ratio is quoted with the conditions it was measured under.

ExactRoundTimingReport times single calls, a few milliseconds each on the current engine.
At that size the wall time is mostly the wait for a core, so it prints CPU time too, and
that is the figure to compare, again from one sitting.

## 8. Size of a run

**The pilot.** The pilot is the start of the run: the first 2,000 or so shoes, which is
about 2,000 valued states. Nothing is thrown away.

**The size is then set from the pilot**, by three criteria:
1. **Absolute EV.** The SE of each system's EV per round at 1-8 (same-schedule bets).
   The target is ±0.05% of a unit (95%).
2. **Paired differences.** The same for each system's difference from Hi-Lo.
3. **Decision resolution.** The decision bound, the summed bound on close calls of the
   play and of insurance, must fall under 0.005% of a unit per round, and the
   learning-curve check must pass (section 6).

The first two shrink as one over the square root of the states. The third is measured,
not assumed. The report finds each count's decision bound on each quarter and each half of
the file's shoes, every part fitted and resolved alone as a run of its own and the parts
averaged, and on the whole file. It fits the exponent b of bound ∝ states^−b to the three
sizes at each penetration and projects the states that bring each bound under the target.
- One count's three bounds are too noisy at a pilot's size to fit a rate of their own. On
  the run's first 1,417 shoes, at 75%, KO's decision bound was 0.0048% on the quarters,
  0.0056% on the halves and 0.0076% on the whole, while Red Seven's was 0.0053%, 0.0017%
  and 0.0043%. The rate is set by how M_play grows, the same for every count, so one
  exponent is fitted over the counts, each keeping its own level. There, with Hi-Lo, KO,
  Red Seven, Omega II and Wong Halves, the bounds fell at states^−0.59, −0.44 and −0.47 at
  70, 75 and 80%, near the one over the square root that M_play = 5 √n is meant to give.
- The null count is left out of that fit. Its one group grows with the whole run, so its
  bound falls faster.
- An exponent above 1 is taken as 1, since a bound summed over close calls is not relied
  on to fall faster than one over the data. Bounds whose exponent is under 0.1 are
  reported as not brought down by running longer.

The run is big enough when all three hold on the file itself. The projections only guide
how long that will take; the run continues until the criteria hold, or the projected
time says to stop and report what does hold. The null count is a baseline, not a system:
it is shown, but it sets none of the three. When every system's decision bound is already
under the target on the file, the report says the criterion is met there rather than
naming a row, since bounds that are not measured to fall all project to the file's size
and tie.

**Which SE.** The first two are sized by the bootstrap's SE, which refits everything on
every resample as the estimate does: the play's groups and moves, insurance and the bets.
The SE from per-state residuals summed per shoe holds every fit fixed, and the report
prints it beside the bootstrap's. Which of them is the sampling spread is checked by
scoring disjoint sub-runs of the shoes alone (ComparisonReport --calibrate K) and setting
each SE beside the spread of the estimates across the sub-runs. With 8 sub-runs that
spread is itself known to about ±27%. The sub-runs must be big enough to form several play
groups, or they cannot test the play refit: sub-runs of 120 shoes form one group per
fold.

On the first 11,800 shoes of the run (11,882 valued states), 8 sub-runs of 1,474 shoes,
about 1,484 valued states each, with KO, Red Seven, Omega II and Wong Halves beside Hi-Lo
and the null count and 200 resamples, at 80%. A sub-run's fits close groups at 5 √742 ≈
136 states, so they hold at most five a fold, where the whole file's form 8 to 13; the
sub-runs are larger than the 850-shoe ones the reviewer found to form several.
- For an EV, the bootstrap's SE is close to the spread: from 0.115% against 0.114% for
  Hi-Lo to 0.117% against 0.147% for Red Seven, the furthest under. The per-shoe SE is a
  little lower for every count but the null count.
- For a difference from Hi-Lo, it depends on the count. For KO the bootstrap's SE is
  0.030% against a spread of 0.031%, and for the null count 0.105% against 0.107%. For
  Red Seven it is under the spread, 0.031% against 0.045%. For Omega II, with and
  without its side count, and Wong Halves, both SEs are about three to four times the
  spread: Omega II's bootstrap SE is 0.027% and its per-shoe SE 0.024% against a spread
  of 0.009%. The median ratio of the mean SE to the spread over the differences is 3.00
  for the bootstrap and 2.72 for the per-shoe SE.
- A reviewer's calibration on the first 6,801 shoes, 8 sub-runs of 850 shoes and 100
  resamples, gave median ratios of 1.04 and 1.27.

So the bootstrap's SE is close for an EV. For a difference it is right or safe for most
counts, and under the spread for Red Seven by a ratio that 8 sub-runs only just tell apart
from chance. Why both SEs overstate the spread of the balanced counts nearest Hi-Lo is not
settled here. The run stays sized by the bootstrap's SE, which refits what the estimate
fits. In this run the difference criterion asked for 966 valued states and the EV
criterion for 31,277, so an SE somewhat under for one count's difference does not set the
run's size.

## 9. Classes

- Built and tested:
  - ConcreteRound, BasicStrategy, Deals, ShoeRun, StateValuer, StateStore, StateRun,
    RoundValue.
  - **CountKeys**: bet and play keys per system and variant, including the colour and
    face split and the side counts.
  - **ScoringStates**, **SystemScorer** and **Bootstrap**: grouping, fitting, decision
    resolution, isotonic advantage, both ways of betting, scoring, bootstrap, reference
    rows.
  - **AllStates** and **ComparisonReport**: deal the file's shoes again to check it and
    to find the states not valued, run every system, penetration and spread, and print
    the tables and the sizing figures.
- Every reported number comes from these classes, run on a stated state file.
- State files live in `runs/`, which is gitignored, under the main checkout.

## 10. What this does not do yet

- **Late index plays** (section 4). Stage 3 measures their size.
- **Composition-dependent play** beyond what a count's groups can see.
- **More players, or more hands per round.** Other players change how many rounds a
  shoe deals, not what a round is worth in a given state.
- **Risk.** No round's standard deviation is computed, so there is no risk of ruin and
  no SCORE. EV at a fixed spread is not risk-adjusted.
- **Authors' own index sets and divisors.** REKO's single +2 index, FELT's half-deck
  floor, Mentor's double decks and Victor's half decks are not modelled. Each system is
  scored with the best first-move indices its tags allow, so these rows are a ceiling
  for their tags, not their authors' exact methods.

## 11. How this was checked

**The design** was reviewed before any scoring code was written, by four independent
reviewers (statistics, blackjack practice, the mathematics against the engine, and cost),
with a skeptic trying to refute every serious finding. Every one a skeptic ruled on was
upheld, and each is a choice above:
- **No floor on bucket size.** Merging buckets at the tails until each held a fixed number
  of states would starve the bet ramps of high-level and running-count systems, whose
  counts spread over many more values. Play groups grow from the centre instead, and bets
  follow an isotonic advantage.
- **The simulator's rounding.** It rounds the true count to nearest with ties toward
  zero; it does not truncate.
- **The same bet schedule** as the headline, since rounding a system's own advantage to
  whole units makes results depend on the tags' scale.
- **Side-count variants** in the main run, with separate play and bet keys, since those
  systems are published with a side count for betting.
- **Fits per penetration**, and a play key that includes the player's own cards and the
  up-card.
- **Empirical BC, IC and PE** on a drift-free true count, against a null-count baseline.
- **One set of bootstrap resamples** for every system, so differences stay paired.
- **Sizing in valued states**, about one a shoe, from the start of the run itself.
- **State files outside any temporary folder**, and whole states per thread.

The reviewers confirmed the rest: the round-value formula, the deal chances and the
insurance value; the rules and the chart, cell by cell; the 70/75% subsets; that a
published starting count changes nothing; the drift correction; that the stored tallies
suffice for every system; that ExactRound is safe across threads; and that memory and disk
suffice.

**The scorer** was reviewed by three more: one read it against sections 5 and 6 line by
line, one recomputed its keys, fits and EVs with a scorer of its own, and one planted bugs
to see which the tests missed. Each of their 57 findings was then checked again on the
final code, every planted bug put back: each is fixed, with a test that fails on the bug,
or was shown not to be a problem.

**The engine's split speed-up** is held to BruteForceRound at 1e-12 and to the earlier
engine's values on eight-deck shoes (section 7).
