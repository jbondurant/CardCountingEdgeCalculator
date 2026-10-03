# Full index plays

COUNTING_COMPARISON.md lets each counting system vary only the first decision on the
two cards dealt, plus insurance and the bet. Every later decision follows basic strategy:
a three-card 16 against a 10, a soft 18 after hitting. So the comparison judges how well
a count bets, and most of how well it plays, but not all of it. Counts built for playing
(Omega II, Hi-Opt II, Uston APC, Zen) lose the most from the limit.

This step gives every system count-based play on every decision of the dealt hand, fits
those plays as carefully as the first decisions, and scores every system again on the
same 60,000 stored shoes. The result is each system's best strategy for this game:
- first-decision plays;
- later hit-or-stand plays;
- insurance;
- bets.

It is printed as an index table per system.

## 1. Scope

- **Later decisions.** Every decision of the dealt hand after its first is count-based.
  After the first decision the only choices left are hit and stand, since doubling,
  splitting and surrender are first-decision moves under these rules.
- **First decisions** (stand, hit, double, split, surrender where offered) and
  **insurance** stay as in COUNTING_COMPARISON.md section 6, refitted. A hit's value now
  includes the count-based play after it.
- **Split hands play basic strategy after the split.** The split decision itself is
  count-based, as now. Making split hands' later decisions count-based would break the
  exact split valuation's reliance on a hand's play depending only on its own cards, and
  the value at stake is small, since a split is a small share of rounds. This is the
  one place play stays basic strategy, and the report says so.
- **The count at a decision** is the system's count of every card the player has seen:
  the cards dealt before the round, the player's own cards, the up-card and every card
  the player has drawn. Never the hole card.
  - A running-count system keys on that running count.
  - A true-count system keys on it, drift-free, over the decks not yet seen (the cards
    left before the round, less the cards seen this round, over 52), rounded as its play
    key is (nearest, ties toward zero).
  - Side-count variants play on the plain count, as now.
- **An index play** is a rule per spot and count group. A spot is the hand's total,
  whether it is soft, and the up-card, for hands of three or more cards. Two-card hands
  are first decisions, already fitted per deal. This is how real index tables are
  written: one number per total and up-card, not per composition.

## 2. Exact values with count-based play: the player tree

For one state (the cards left at a round's start) and one up-card u, the **player tree**
holds every hand H the player can hold after hitting: every multiset of ranks of two or
more cards with a hard total of 21 or less, reached from a two-card start by hitting. That
is about 3,000 hands per up-card, 30,000 a state.

**Values.** Each value is unconditional, carrying the indicator that the dealer has no
natural, as ExactRound's are before its final division.
- **Stand.** V_stand(H) is the dealer's outcome distribution from the shoe S − H − u,
  with the hole card dealt last from that shoe and a natural hole card counting for
  nothing, compared with H's total. ExactRound already does this: the hole card dealt
  last, the dealer's hands listed once per up-card, and its distributions kept within a
  state. ExactRound gains one package-private method for it, `dealerOutcomes(shoe, up)`:
  the dealer's chance of finishing on each of 17 to 21 or busting, with the indicator.
- **Bust.** A bust is worth −P(no natural) from the shoe it leaves, the total mass of the
  same distribution.
- **Hit.** V_hit(H) = Σ_c P(c | S − H − u) × V(H + c), where V(H + c) is a bust, or the
  policy's choice at H + c. Draws come from the shoe including the hole card's place:
  the hole card is dealt last, by exchangeability, exactly as in ExactRound.

**The policy.** At H it sees H's total, softness and up-card, and the count of the seen
cards. For Red Seven and the KISS counts, a seen card's tag depends on its colour or
face, which the rank does not say. The tree then carries, at each hand, the distribution
of the count's offset over the tag classes of its cards, drawn without replacement from
the kinds left, and a decision is taken for each offset. That is the same exact split
CountKeys makes for the first decision's three cards, carried on through the draws.

**From the tree to the round.** The value of Hit as a first move is
V_hit(p1, p2) / P(no natural | the deal). It replaces the stored V(Hit) for that system.
Stand, Double, Split and Surrender keep their stored values, which no later decision
changes, except that split hands keep basic strategy (section 1).

**Checks.**
- **Against the stored values.** With basic strategy as the policy, the tree's V(Hit) for
  every deal of every stored state must equal the stored value within 1e-12. That holds
  the tree to ExactRound on the run's own shoes.
- **Against every card order.** With count-aware policies on shoes of a few cards, the
  tree must equal ConcreteRound played on every order of the cards, with the count read
  from the seen cards, within 1e-12. That checks the count at each decision, and that the
  hole card is never in it. A test-only policy hook gives ConcreteRound the seen cards.
- **Planted bugs.** For example, counting the hole card, or the true count's divisor
  forgetting the seen cards. Each must fail a test.

## 3. Fitting the later plays: policy iteration, without grading a system on its own shoes

Folds, penetrations and the 1/q weights are as in COUNTING_COMPARISON.md section 6.

**Iteration 0.** The continuation is basic strategy. For every training state, up-card,
and three-or-more-card hand H, the tree gives:
- the reach of H, the chance of holding it under the current policy, summed over the
  first moves that lead there by hitting;
- V_stand(H) − V_hit(H) under the current continuation;
- H's play key.

For each (spot, play-key group), the play is stand when Σ (1/q) × reach × (V_stand −
V_hit) > 0 over the training (state, hand) pairs in it, and hit otherwise. Groups are
formed as for the first decision: grown from the most-populated key, closing at M_play.

**Later iterations.** The continuation is the previous iteration's fitted policy, and
the reach is under the current policy. Stop when no (spot, group) changes, at most four
passes; the report gives the number of changes per pass.

**The first decisions** are then refitted per deal as now, with V(Hit) from the tree
under the final later-decision policy, and insurance and bets after them.

**Decision resolution** extends to the later plays. Each (spot, group) gap gets a
standard error from per-shoe sums of the weighted contributions. A play is settled when
the gap exceeds 2 SE; otherwise it is a close call bounded by frequency × (2 SE − gap).
The bound is summed with the first-decision and insurance bounds into the system's
decision bound, under the same 0.005% target. The learning curve refits the later plays
on half the training fold too.

**The bootstrap.** It refits the first decisions, insurance and bets per resample, as
now. It keeps the later-play fit fixed: refitting it per resample would mean a pass of
trees per resample. The decision-resolution check answers whether those plays are right;
the report says the intervals hold them fixed.

## 4. Scoring

Per system, the scorer runs exactly as now on a copy of the states whose Hit values are
that system's tree values. Every system is compared with Hi-Lo playing its own full fit,
and with its own first-decision-only score, so the gain from later index plays is printed
per system. The headline, spreads, own ramp, efficiencies, decision resolution and sizing
sections are as before. The perfect reference row stays first-move-only; a new reference
row gives perfect play on every decision of the dealt hand, from each state's own
composition: the most any count can reach with this scope.

## 5. The index tables

For each system: its play-key groups and, per spot, the count from which it stands or
hits (or doubles, splits, surrenders on the first two cards), from the final fit pooled
over the folds. It is given as an index number per spot, as published tables are,
wherever the fitted play changes once along the count. A spot whose play changes more
than once is printed as its group-by-group table and flagged. The bet ramp and insurance
index come with it. For Hi-Lo, the table is set beside the Illustrious 18, as a check
that the fit lands where published indices do, allowing for this game's rules.

## 6. Cost and run

**Cost.**
- About 30,000 dealer distributions per state.
- Sharing distributions within a state, as ExactRound already does, is expected to cost
  well under a second of CPU a state.
- A pass over the 60,000 stored states runs every system at once, since the trees'
  values do not depend on the system, and each system's policy is a walk over them.
- There are three to five passes: the iterations and the final scoring.
- No new shoes are dealt and nothing is stored beyond the per-system sums. The existing
  state file is read, never written.

Measured on the first states before the full run, by a committed timing report.

## 7. What it does not do

- **Count-based play after a split** (section 1).
- **Composition-dependent play beyond the count.** An index depends on total, softness,
  up-card and count, not on which cards make the total.
- **Refitting later plays inside the bootstrap** (section 3).
- **Risk.** There is still no round variance, so no SCORE.
