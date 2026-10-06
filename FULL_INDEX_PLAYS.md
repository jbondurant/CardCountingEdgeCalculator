# Full index plays

COUNTING_COMPARISON.md lets each counting system vary only the first decision on the two
cards dealt, plus insurance and the bet. Every later decision follows basic strategy: a
three-card 16 against a 10, a soft 18 after hitting, every decision on a split hand. So
the comparison judges how well a count bets, and most of how well it plays, but not all
of it.

This step gives every system count-based play on every decision of the round, fits it
as carefully as the first decisions, and scores every system again on the same 60,000
stored shoes. The result is each system's best strategy for this game:
- first-decision plays;
- later hit-or-stand plays;
- play on split hands;
- insurance;
- bets.

It is printed as an index table per system.

**How much is at stake.** A design review measured, with its own scratch code on 84
stored states, every value from ExactRound, the most that per-state perfect play adds at
80%. The build replaces these figures with committed measurements:

| | Flat bets | Weighted by Hi-Lo's 1-8 schedule |
|---|---|---|
| The first move | 0.094% a round | 0.33% |
| Later decisions of the dealt hand | 0.028% | 0.092% |
| Decisions on split hands | 0.0097% | 0.053% |

The last two are what this step adds. They are ceilings, which no count reaches.

## 1. Scope

- **Later decisions of the dealt hand.** Under these rules the only later choices are hit
  and stand: doubling, splitting and surrender are first-decision moves (ROUND_CONTRACT.md;
  Montreal offers no surrender). They are count-based at the contested spots: a hand of
  three or more cards at hard 12 to 16 or soft 13 to 18, against each up-card, 110 spots
  in all. Everywhere else a later decision plays the chart: it hits hard 11 or less and
  stands on hard 17 or more and soft 19 or more. The contested walk of section 2 visits
  only the nodes those spots can reach and plays the chart at every other node whatever it
  is told, so a fit cannot leave the chart there by accident.
- **First decisions and insurance** are count-based as now, refitted with the new values.
- **Split hands** play by the system's own fitted plays, keyed on its count at the
  moment of the split, which is the deal's play key:
  - the later-play table for hit and stand;
  - the first-decision fit of deal (r, x, up) for a split hand's two-card decision,
    restricted to the moves legal after a split;
  - the fit of (r, r, up) for a resplit.

  A first-decision fit is read as the deal's legal moves ranked best first by the
  per-(group, deal) sums the fit chose its move from, and a split hand plays the first of
  them that is legal after a split. A (group, deal) no training state reaches ranks its
  moves as the chart plays them.

  The count does not move with the cards drawn to split hands, so this is a slight
  lower bound on count-based play after a split. Moving it would make a hand's play
  depend on the other split hand's cards, which ExactRound's exact split valuation
  cannot take; the report says so.
- **The count at a decision** is the system's count of every card the player has seen:
  the cards dealt before the round, the player's own cards, the up-card and every card
  the player has drawn, never the hole card.
  - A running-count system keys on that running count.
  - A true-count system keys on it, drift-free, over the decks not yet seen (the cards
    left before the round, less the cards seen this round, over 52), rounded as its play
    key is (nearest, ties toward zero).
  - Side-count variants play on the plain count.
- **An index play** is a rule per spot and count group. A spot is the hand's total,
  whether it is soft, and the up-card, for hands of three or more cards. Two-card hands
  are first decisions, fitted per deal. This is how published index tables are written,
  and the composition dependence it leaves out is small: the Wizard of Odds gives
  0.0019% for composition-dependent over total-dependent basic strategy at 8 decks, H17,
  DAS.
- **Indices are fitted for flat bets**, as the first decisions are. The headline scores
  them under the 1-8 schedule, where they are near-optimal but not refitted for it.
  Section 4's check measures what refitting for the schedule would change.

## 2. Exact values: the player tree

For one state (the cards left at a round's start, S) and one up-card u, the **player
tree** holds every hand the player can hold after hitting. A hand is a multiset of ranks
of two or more cards with a hard total of 21 or less, reached from a two-card start by
hitting: about 3,060 per up-card, about 30,600 a state.

**Values.** Values are unconditional, carrying the indicator that the dealer has no
natural, as ExactRound's are before its final division.
- **Stand.** V_stand(H) is the dealer's outcome distribution from S − H − u, the hole
  card dealt last from that shoe and a natural hole card counting for nothing, compared
  with H's total. It is computed with ExactRound's dealer hands (package-private access),
  in ExactRound's order of summation, so that under basic strategy the tree reproduces the
  stored values.
- **Bust.** −(n − nat)/n of the shoe it leaves (n cards, nat of them natural hole cards).
- **Hit.** V_hit(H) = Σ_c (count of c in S − H − u)/n × V(H + c). The draw comes from the
  shoe with the hole card's place still in it: the hole card is dealt last, by
  exchangeability. That holds with count-based decisions too, since in contract order
  every card's kind is exchangeable and no decision reads the hole card. The review
  checked this in exact rational arithmetic.

**Reach** is forward propagation over the multiset graph, in the same unconditional
units. The root of deal i gets (1/q) × P(i | S) × [the first move is Hit]. A child gets
reach(H + c) += reach(H) × [the policy hits at H] × (count of c)/n, with no natural
conditioning. reach × (V_stand − V_hit) is then the hand's contribution to
P(i)(1 − P(nat|i)) × conditional value: the same units as the first-decision objective.

**Colour and face counts.**
- For Red Seven and KISS II and III one rank's tag depends on colour, so a hand's count
  offset fixes the class composition.
- For KISS I two ranks do: the 2 by colour, the ten-valued cards by face. The offset does
  not then fix the composition, and once a count-based decision has been taken the
  composition given the offset is not hypergeometric.

So for every kind-aware count, a tree node is (rank multiset, class counts of each split
rank, the deal's three cards included). A draw's class comes from the kinds left less that
composition. Values go backward and reach forward per node. A deal then has one Hit value
per tag-class part, in the same partition CountKeys.tagSums produces. The scorer stores
and uses Hit per part for these counts in fit, playValues, playValuesFrom and resolve.

**From the tree to the round.** A Hit first move is worth V_hit(start) / P(no natural |
deal), per part for kind-aware counts. Stand, Double and Surrender keep their stored
values. Split is valued by ExactRound with the post-split policy of section 1, a
deterministic function of the decision for a given (state, deal, count group). It is
cached by the policy's answers to every decision a split of that deal can reach, since
many systems and groups share them, and it is the stored value wherever those answers are
the chart's.

**Edges.** The stored states always hold at least 85 cards, so no round can run out.
The tree assumes this and checks it per state. The tests limit the every-order check to
shoes that cannot run out, and check ExactRound's exceptions separately.

**Checks, each required to pass and to fail on a planted bug:**
- **Against the stored values.** With basic strategy, the tree's V(Hit) for every deal of
  every stored state equals the stored value within 1e-12. The review's scratch code
  measured 4.4e-16 over 21,600 deals of 40 states. PlayerTreeTest checks 40 states spread
  through the file on every test run; PlayerTreeStoredCheck checks every state, once. On
  all 60,743 states, each holding 85 cards or more, it compared 320,392,174 Hit values
  (every part, both walks, Hi-Lo, Red Seven, KISS I and BRH-I) and 32,789,360 Stand values:
  largest difference 7.8e-16, none over 1e-12.
- **Against every card order, by kind.** ConcreteRound gains a test-only hook that deals
  kinds in parallel with ranks and tells a test policy each kind it is shown, in order.
  On shoes big enough that no order runs out, with depth set so the true-count divisor
  (416 − depth − seen) is exercised, the tree's values per part must equal the average
  over every order within 1e-12. The cases cover:
  - random thresholds at hard 12-16 and soft 13-18, for Hi-Lo, Red Seven and KISS I;
  - deals of A,A, A,2 and pairs, and ace and ten up-cards;
  - split hands under a count-group policy.
- **Reach.** On stored states, with count-aware policies,
  Σ_H reach(H) × terminal(H) = Σ_i (1/q) P(i) V_hit(i) within 1e-12, where terminal is
  the stand value, or the bust value.
- **Planted bugs:**
  - counting the hole card;
  - the divisor forgetting the seen cards;
  - carrying the offset without the composition;
  - one Hit value per deal for a kind-aware count;
  - draws taken from the shoe with the natural hole removed;
  - reach conditioned on no natural;
  - reach double-counting a hand reached two ways;
  - a test state scored with the later plays fitted on its own fold.

## 3. Fitting: joint policy iteration, without grading a system on its own shoes

Folds, penetrations and 1/q weights are as in COUNTING_COMPARISON.md section 6. Every fit
below exists once per (system, penetration, fold), plus the learning curve's half fits,
which run as extra policies in the same passes.

**Groups.** Later-play groups are grown, as first-decision groups are, from the play key
holding the most rounds at the decision, closing at M_play = 5 √n valued states. Each
valued state contributes mass 1, spread over its later-decision keys by its reach
normalised to 1. The groups are formed once, from pass 0's reach on the training fold,
and held fixed through the passes.

**A pass** walks every training state's trees under the current joint policy. It
accumulates, per (spot, group), Σ (1/q) × reach × (V_stand − V_hit), with per-shoe Σ and
Σ² for standard errors. Per (group, deal) it accumulates the first-decision sums, Hit from
the tree. At the end of the pass, both are refitted:
- every later (spot, group) takes stand if its sum is positive, hit if negative;
- every first-decision (group, deal) takes its best move;
- insurance is refitted as now.

Where a cell has no training reach, or a sum of exactly zero, it plays the chart's move,
and the report counts such cells. Pass 0's policy is the stored first-decision fit with
the chart after the first move. Its split hands play as section 1 says, their two-card
decisions from the stored fit's ranked moves and hit or stand by the chart, so its Split
values are the stored ones only where those answers are the chart's.

A first-decision cell counts as changed when its move changes, or, for a pair deal, when
any answer its split hands read from the rankings changes.

**Convergence.** Passes repeat until no first-decision or later cell changes, at most six,
pass 0 among them. Simultaneous updates of pooled cells are not guaranteed to improve, so
every pass records the training fold's actual weighted EV, and if the cap is reached the
best pass's policy is kept and flagged, with the changes per pass printed. A final
evaluation pass then computes every later cell's one-flip gain and its standard error under
the policy actually scored. A cell whose fitted choice has a negative gain is flipped and
the pass repeated, up to two clean-up passes; the cells are flipped together, by the sums of
the pass that walked the play, and the clean-up records its own EV. After that, its bound is
charged with the negative gap. A converged policy has no such cell: each of its later cells
already takes the sign of its own sum. At the cap the first decisions the scorer refits from
the kept policy's values can differ from the kept ones, and the strategy counts them.

**Which Hit values a state gets.** For each (system, penetration), a state in fold φ needs
two Hit arrays:
- under L_φ, fitted on the other fold, for scoring it as a test state;
- under L_{1−φ}, fitted on its own fold, for the training sums of strategy 1−φ.

The arrays, and the Split arrays beside them, are kept per (penetration, fit), never
pooled. Tests fail if a test state is scored under the later plays fitted on its own fold,
or if a fold's first decisions are fitted on Hit values from the other fold's later plays.

**The joint run (FullIndexFit.JointRun).** Every count's strategies, both folds and the
half fits at every penetration, run their passes together: each round visits each state
once, gets its trees once, sets them up once for each count and walks every strategy still
fitting. Strategies of a count that play the same later play at a state share one walk, as
all of them do in pass 0. The split values worked out at a state are shared by every
strategy there, kept by the post-split play's fingerprint, so a play several counts or
penetrations share is valued once, and they are kept from round to round, packed (40 bytes a
value). While they fit, the strategies hold no Hit or Split value per state: a pass needs of
them only the first-decision sums of Hit and Split per (group, deal) and the training EV,
which it adds up as it walks; the other moves' sums do not change with the play.

Once every strategy is fitted, the scoring goes in items: each count's fold fits, and then
its half fits, at each penetration in turn, scored apart (SystemScorer.Part) and put
together once all of a count's are scored (SystemScorer.merge). As many items go to a batch
as the heap holds. A batch's strategies are given their last round, which fills the values
they are scored with, Split held for the parts of the 100 pair deals alone, and each item's
bootstrap resamples are shared among the threads. Every state's trees are built once, in
the first round, and kept on disk (TreeStore, 245 KB a state); every later round and every
batch reads them back, bit for bit the trees built.

The joint run fits and scores as the earlier batched run (FullIndexFit.scoreAll) does, to
1e-12, the sums being added in another order, and scoring a penetration or a part at a time
on several threads gives the Result of scoring it all at once, to the bit
(FullIndexFitTest). A split value kept for one play is never served to another: every Split
value every strategy holds at the end is valued afresh under its own play and must agree.

**Checkpoints (FullIndexCheckpoint).** The run checkpoints into its output directory: after
every round of the passes, each strategy's plays, passes and sums, the run's rounds and the
perfect row; after the passes, the same marked fitted; and each scoring item's Result as it
is scored. Each file is written whole, forced to disk and moved into place, and begins with
the run's identity: the commit its classes were built from, the state file's path, size and
time, and every setting that changes a result, the threads among them, since each thread adds
its own sums in its own order. Restarted with --resume, the run refuses a checkpoint of
another identity, saying what differs; otherwise it goes on from the last round written, or
from the fitted strategies, scores only the items not written, and gives the Results an
uninterrupted run gives, to the bit (FullIndexFitTest, interrupted after the first and the
second-last round and after five of twelve items). The tree store keeps an index of the
states it holds, written only after their trees are forced to disk, so a crash costs at most
the trees built since the last round.

**Decision resolution** covers the later cells, and split hands through the
first-decision cells they use. A later cell is settled when its one-flip gain under the
final policy exceeds 2 SE. Otherwise it is a close call bounded by frequency ×
(2 SE − gain). The SEs come from per-shoe sums, with whole shoes per thread. The bound is
added to the first-decision and insurance bounds into the system's decision bound, under
the same 0.005% target. The learning curve's half fits include the later plays. Their
interval holds both fits' later plays on every resample, as the bootstrap holds every full
fit's, so it leaves out how much the later plays move on half the data, and no pilot has
measured by how much (section 7). The full-play learning curve is reported with its held
interval named as such, and is not taken by itself to show the 0.005% sizing criterion met.

**Section 8's decision-bound scaling** on quarters and halves of the file holds the whole
file's later-play fit fixed in the parts. Its fitted exponent therefore measures how the
first-decision and insurance bounds fall, and the report says so. The decision bound on
the whole file still includes the later cells.

**In-sample gaps.** A training fold's Hit values include later plays fitted on that fold,
so near-zero gaps lean slightly toward settled. The report states this. As a check, the
later cells' signs of each learning-curve half fit are recomputed on the other half of its
training fold, where it was not fitted, under the half fit's play. The report prints the
cells compared (reached in both halves, with a sum not zero on the other), the number that
disagree, and the same two among the cells settled on the half fitted.

## 4. Scoring

Per system, the scorer runs as now, with these changes:
- each fold's test states use the Hit values (per part for kind-aware counts) and Split
  values under the other fold's fit;
- each fold's training states use their own.

What is reported:
- **The full-play score.** Every system is compared with Hi-Lo playing its own full fit.
- **The gain from full play.** Each system's full-play score less its own first-decision-
  only score, scored in the same run on the same resamples, so the gain has a paired
  interval. The null count, refitted with later plays in one group, gains from
  total-dependent corrections with no count at all. That is printed once as the
  count-free part, and each system's gain is also printed net of it.
- **Two playing efficiencies.**
  - The first-decision PE, as now.
  - The full PE: (system − null) / (perfect − null), with the null count's full fit and
    a new perfect row. It plays every decision of the dealt hand best from each state's
    own composition: each deal's best first move, Hit valued by a walk of every node that
    takes the better of standing and hitting at each, and insurance exactly when it pays.
    Its split hands play the chart, as the stored V(Split) has them, so it leaves out
    perfect play on split hands, which the review's figures at the top put at 0.0097% a
    round flat; the report says so.
- **A check of what the indices would gain if fitted for the 1-8 schedule.** On one
  system, the plays are refitted with the training fold's schedule bet as a weight; the
  report gives how many cells change and what that is worth.
- Everything else as before: the headline, every spread, the own ramp, decision
  resolution and sizing.

**The bootstrap** refits the first decisions, insurance and bets per resample, as now. It
holds the later-play fit fixed, and with it every Hit and Split value, the post-split play
included, since refitting it per resample would mean a pass of trees per resample. COUNTING_COMPARISON.md section 6 found that holding a play fit fixed
understates a difference's SE by 2 to 3 times. So the effect here is measured before the
full run, as that section measured it:
- the trees of the first 1,417 shoes are kept in memory, about 330 MB;
- the later plays are refitted per resample by walks alone;
- five counts are compared over 200 resamples: Hi-Lo, KO, Red Seven, Omega II and Wong
  Halves.

The SE of each difference from Hi-Lo is printed with the later plays held and refitted.
If the ratio is near 1 the run goes as planned. If not, the full run's intervals are
widened by the measured ratio per count, and the report says how.

**What the pilot measured** (runs/full-index/pilot-1417-100.txt: the first 1,417 shoes,
1,472 states, at 80%, 100 resamples). Refitted SE over held SE:
- for each count's full-play difference from Hi-Lo, 1.04 to 1.07 on the 1-8 same schedule
  and 1.03 to 1.06 flat, jackknife SEs 0.01 to 0.02;
- for each count's gain from full play, 1.03 to 1.80 on the 1-8 same schedule (Hi-Lo 1.43,
  KO 1.10, Red Seven 1.03, Omega II 1.80, Wong Halves 1.66) and 1.08 to 1.52 flat,
  jackknife SEs 0.03 to 0.15.
So holding the later plays narrows a difference's interval a little and a gain's by up to
almost half.

**How the intervals are widened (SystemScorer.Widening).** Each count's Result carries two
ratios: one for its full-play differences from other counts, one for its gain from full
play. A count the pilot measured takes its own, the larger of its two metrics' for each kind.
Every other count, and every side-count variant, takes a stated conservative default, the
largest ratio of its kind the pilot measured over all its counts and both metrics (1.07 for
differences, 1.80 for gains), and its Result marks the ratio as not measured; a count with
neither its own ratio nor a default is refused, so no count is left unwidened by default.
The full run (FullIndexRun) writes its Results unwidened, Widening null; the report sets
each count's Widening before it prints a full-play interval, and prints none while it is
null.

## 5. The index tables

For each system, from each fold's final fit, printed per fold with the spots where the
folds disagree flagged:
- its play-key groups;
- per spot, the count from which it stands rather than hits;
- per deal, its first-decision indices;
- its insurance index;
- its bet ramp.

A spot whose play changes more than once along the count is printed as its
group-by-group table. Tail groups span several keys, so an index is printed with its
group's span.

**The check against the Illustrious 18** needs like-for-like figures. For it, a per-total
index is pooled over every hand of that total, two-card and multi-card, reach-weighted,
and set beside a named published Hi-Lo table, with its count convention. The expected
differences, given this game's rules and conventions:
- H17 moves 11 v A;
- no surrender here, so no Fab 4;
- our keys round to nearest, where most published tables floor;
- pair indices here keep the count fixed after the split (section 1).

## 6. Cost and run

**Measured by the review's scratch code, to be confirmed by the committed timing
report:**
- the trees of one state take 0.085 to 0.112 s of CPU, single-threaded;
- walking only the nodes where any decision is contested (hard 12-16, soft 13-18, and
  the two-card starts) gives the same gap sums as walking every node, to within 9.4e-16;
- stored states never run out, holding at least 85 cards.

**Passes:** up to six fitting passes, two clean-up passes and a final evaluation, each a
walk of every training state per (system, penetration, fit), the half fits included. Split
values are computed per distinct post-split policy and cached.

**Before the full run,** a committed timing report measures, on the first 1,000 states:
- seconds a state for the trees, the walks and the split values;
- the number of distinct post-split policies per (state, deal);
- peak memory at the planned batch size.

Stage 1's report (FullIndexTimingReport) gives the seconds a state for the trees and the
walks, the CPU time of a split valued afresh under one play that is not the chart's, the
heap's peak and the memory a state's trees hold. It cannot give the second figure: a
post-split policy is read from a group's fitted first decisions and later plays, and
stage 1 fits nothing. Stage 2 counts it before the run is sized, as the distinct
SplitValues prints per (state, pair deal) over every system's groups: under pass 0's
policy (the stored first-decision fits, the chart after the first move), and again after
pass 1, when the later plays first leave the chart.

The run's size and batch plan are set from those figures and the bootstrap measurement
of section 4. The state file is read, never written.

## 7. What it does not do

- **The count moving within a split.** Section 1 explains why this makes the result a
  lower bound.
- **Composition-dependent play beyond the count** (section 1).
- **Refitting the later plays inside the full run's bootstrap.** The pilot measures the
  effect and the intervals are widened by it (section 4).
- **Widening the full-play learning curve.** Its half fits hold their later plays on every
  resample too, and the pilot did not measure what that leaves out of the half-less-full
  interval; the curve is reported as an estimate with a held interval (section 3).
- **Risk.** There is still no round variance, so no SCORE.
