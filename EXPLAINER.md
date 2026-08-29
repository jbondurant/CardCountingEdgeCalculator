# Two things about this program that are easy to get wrong

This is the reasoning behind two decisions in the simulator that look like
implementation details and are not. Both are the kind of thing where the wrong
version still runs, still produces numbers, and the numbers are quietly wrong.

Every figure below comes from a solver in `src/`, not from a spreadsheet. They are
exact expectations under the Montreal casino rules — eight decks, dealer hits soft 17
and peeks for blackjack, blackjack pays 3:2, double after split, resplit to four hands
— computed in closed form rather than sampled, so there is no margin of error to
quote. Re-run them and you get the same numbers.

```
java RandomVsOptimalReport          # part one
java PerRoundVersusPerHandReport    # part two
```

---

## Part one: why the table is built in a particular order

### The parking lot

Suppose you can teleport to one of two places, and you want to know which will make
you happier.

**Central Park.** You land in the middle and start walking in a random direction.
You are 60% happy. Maybe 70% if there is a bench. 80% if a nice bird turns up.
Wherever you walk, it is roughly fine. Call it 65% on average.

**The Disneyland parking lot.** You land in the middle and start walking in a random
direction. If you happen to walk toward the entrance you are 90% happy. Every other
direction is more parking lot, and you are 5% happy. On average, maybe 10%.

Random walking says Central Park wins by a mile.

But you were not asking which place is better *to wander around in*. You were asking
which place to go. And if you go to Disneyland you will walk to the entrance, because
you can see it. The answer is Disneyland, and random walking got it backwards.

### Why more simulations do not fix this

The tempting response is that 10% was a bad estimate and more sampling would fix it.
It would not. Random walking does not give a noisy estimate of what the parking lot is
worth. It gives an increasingly precise estimate of a *different quantity*: what the
parking lot is worth to someone who wanders. Those are two different numbers, and no
amount of sampling turns one into the other.

> Running a billion random walks through the Disneyland parking lot does not make the
> parking lot better. It tells you, with enormous precision, exactly how bad random
> walking is.

That is what makes this a bias rather than noise, and it is why the error does not
average out.

### The same thing in blackjack

The error is not spread evenly across the options, which is what makes it dangerous.
Moves that **end the hand** are priced correctly, because there is no continuation to
get wrong. Moves that **hand you another decision** are priced as though you were
going to squander it. So the method is biased against exactly the moves whose value
is back-loaded.

The worst case is a pair of eights against a dealer 7:

| | value |
|---|---|
| Split, played well afterwards | **+0.3210** |
| Stand | −0.4754 |
| What random continuation thinks splitting is worth | −0.7395 |

Random continuation does not merely misrank them. **It flips the sign of the hand.**
This is one of the few spots in blackjack where correct play turns a losing hand into
a winning one, and the naive method throws that away. Cost: **0.80 of a bet**, every
time you are dealt it.

Across the whole table, **98 of 340** hands get a different recommendation. Let the
random player hit anything at all, up to and including a 21, and it is **154 of 340**
— nearly half the strategy table.

### The control case

Splitting aces is underpriced by **exactly 0.0000**.

Split aces take one card each and then stand. There are no downstream decisions, so
there is nothing for a random walker to get wrong. Same *shape* of decision as
splitting eights, with the navigation removed, and the error vanishes completely.

Two split decisions side by side, identical in form, one broken by 0.80 and the other
by nothing. That isolates the cause better than any argument: **the error is not in
the decision, it is in what the decision leaves you to do next.**

It is also worth knowing that not every disagreement matters. Hard 16 against a ten
flips too, and costs **0.0006**, because standing and hitting are nearly identical
there. The method is fine on flat ground and catastrophic on peaked ground.

### So the table is filled in dependency order

You cannot price "teleport to the parking lot" until you already know what "standing
at the entrance" is worth. Same here: hard 12 against a 5 cannot be evaluated until
hard 15 against a 5 is solved, because hitting might land you there.

So hands are solved backwards, from the ones with no decisions left toward the ones
with the most:

```
h21 h20 h19 h18 h17 h16 h15 h14 h13 h12
s21 s20 s19 s18 s17 s16 s15 s14 s13
h11 h10 h9 h8 h7 h6 h5
10s 9s 8s 7s 6s 5s 4s 3s 2s
As
```

That is `HandEncoding.getOrderedEncodings`, and it is not an implementation detail —
it is the reason the program works at all.

The non-obvious part is why the hard totals are **split around the soft ones**. Hard
21 down to 12 comes first, because a soft hand that hardens lands there. Hard 11 down
to 5 comes *after* every soft hand, because a low hard total that draws an ace becomes
soft. Put all the hard totals first, as an earlier version did, and it breaks in
exactly six places:

```
hard 10 hits an ace -> soft 21      hard 7 hits an ace -> soft 18
hard  9 hits an ace -> soft 20      hard 6 hits an ace -> soft 17
hard  8 hits an ace -> soft 19      hard 5 hits an ace -> soft 16
```

Six cells out of thirty-six, each looking up a hand that has not been solved yet.
Easy to ship without noticing.

Splits come last because a split hand can turn into almost anything, so it depends on
nearly everything else. Aces last of all.

---

## Part two: why the payoff is per round, not per hand

### The rule

**Never optimise a ratio whose denominator is one of your choices.**

You post one bet per round. Splitting is a decision to put a second bet at risk. If
you score per *hand*, splitting gets credit for spreading a loss across more hands —
but you had to fund the extra hand. The denominator moved because you moved it.

So the simulator scores the round: the split branch returns `leftPayoff + rightPayoff`
and doubling scales by `2.0`, everything in units of the one original bet.

### It is wrong in both directions

This is the part that convinces people, because a measure that is merely conservative
could be lived with. This one is not conservative. It is wrong whichever way flatters
the split.

**6,6 against a dealer 10** — per-hand says split, and it should not:

| | value |
|---|---|
| Best without splitting | **−0.3810** |
| Split, both hands (per round) | −0.6662 |
| Split, divided by hands (per hand) | −0.3331 |

Per hand, −0.3331 beats −0.3810, so split. Per round you lose two thirds of a bet
instead of a third. **Cost: 0.2852 a round.**

**9,9 against a dealer 6** — per-hand says stand, and it should not:

| | value |
|---|---|
| Stand on 18 | +0.2234 |
| Split, both hands (per round) | **+0.4611** |
| Split, divided by hands (per hand) | +0.2120 |

Per hand, +0.2120 loses to +0.2234, so stand. You leave half a good bet on the table.
**Cost: 0.2377 a round.**

Halving pulls a loss *up* and a gain *down*, so the per-hand measure argues for
splitting when the hand is bad and against splitting when the hand is good. Two
mirrored mistakes. **26 of 100** pair and up-card spots disagree.

### Resplitting, and why the denominator is not even fixed

Resplitting makes the per-hand measure worse in an instructive way: with resplits a
round does not produce two hands, it produces an expected 2.00 to 2.17 of them
depending on the pair. So "per hand" divides by a number that is itself an outcome of
the decision being evaluated.

Modelling it is the one genuinely fiddly part, because **the hand limit is shared**.
If the left hand splits again it spends budget the right hand can no longer have, so
the two branches are not independent and cannot be valued separately. The dealer
resolves them in order against one running count of hands, so the model carries the
same two numbers:

- `pending` — positions still waiting for a second card
- `hands` — hands committed, played and pending together

A card that does not match retires a position into a played hand. A card that matches
offers the choice: play it as a pair, or split, which turns one position into two and
spends a hand from the budget. Taking the better of those two is what makes the model
**decline** to resplit where it would not help — 6,6 against a ten gains exactly
0.0000, because re-splitting into another 6,6 is worse than playing the twelve.

The simulator gets this right already, by creating both children before resolving
either and counting hands from the root of the tree.

### A footnote worth having

9,9 against a 6 shows up in *both* halves of this document, at the same cost of
0.2377. Random continuation underprices the split and picks Stand; per-hand scoring
underprices the split and picks Stand. Two unrelated modelling mistakes, one shared
victim. Which is roughly the point of writing any of this down.

---

## Checks

Both solvers reproduce published figures where published figures exist. Hard 16
against a ten comes out as hit −0.5398 against stand −0.5404, matching the standard
infinite-deck H17 values to four decimals. Every per-round split decision matches
published basic strategy, including the non-obvious 4,4 against a 5 and a 6 with
double after split.
