# What one round is worth

This defines a single round of blackjack precisely enough that two independent
implementations can be held to the same answer: within 1e-12, since two exact
computations that add the same terms in a different order round differently in the
last few bits. `RoundValuer` is the interface
both implement: `ExactRound`, which is fast, and `BruteForceRound`, which is
deliberately naive and exists to check the first.

Nothing here is an approximation. Every draw is from the shoe as it stands at that
moment, the hidden hole card is dealt before the player acts, and splits share one
shoe with each other and with the dealer.

## Cards and the shoe

- Ranks are 1 to 10. 1 is an ace; 10 is any ten-valued card, so a king and a queen
  are the same rank, and a pair.
- A shoe is an `int[11]`: `shoe[r]` is the number of cards of rank `r` left. Index 0
  is unused.
- Every draw takes one card uniformly at random from the cards left: rank `r` with
  probability `shoe[r] / (shoe[1] + ... + shoe[10])`, and removes it.
- If a draw is needed and the shoe is empty, throw `IllegalStateException`.

## Hand totals

- A hand's total counts each ace as 1, then adds 10 once if the result stays at 21
  or under. The hand is **soft** when that 10 was added.
- A **pair** is exactly two cards of the same rank.

## The question being answered

`valueOfFirstMove(shoe, p1, p2, up, first, policy)`:

- `p1` and `p2` are the player's two cards and `up` is the dealer's up-card. All three
  have already been removed from `shoe`.
- The dealer's **hole card** has also been dealt, face down, before the player acts:
  it is one uniformly random card of `shoe`, removed before any player draw. No
  decision may depend on it.
- **Peek.** If `up` is 1 and the hole card is a 10, or `up` is 10 and the hole card is
  a 1, the dealer has a natural and the round is excluded. The value returned is the
  expected payoff **conditional on the dealer not having a natural**. If no possible
  hole card avoids a natural, throw `IllegalArgumentException`.
- The value is in units of the original bet: the total won or lost across every hand
  the round ends up with.
- `first` is played as the dealt hand's first decision and must be legal there
  (otherwise throw `IllegalArgumentException`). Every later decision, of the dealt
  hand or of any hand a split creates, comes from `policy`, which is asked at every
  decision, even one where only Stand is legal.
- Decisions are only asked in rounds that are not excluded: a decision reachable only
  when the hole card makes a natural is never put to the policy.

**Order of checks.** Before anything is dealt: a malformed argument (a null policy, a
card outside 1 to 10, a negative count) and an illegal `first` throw
`IllegalArgumentException`. Then the hole card is dealt: an empty shoe throws
`IllegalStateException`, and a shoe whose every card would give the dealer a natural
throws `IllegalArgumentException`.

## The player's natural

If `p1` and `p2` are an ace and a ten, the round is settled at once: the player is
paid `blackjackPayout` (the dealer has no natural, by the conditioning above). `first`
must be `Stand`. The hole card is still dealt first, so the checks above on the shoe
still apply; no other card is drawn.

## Legal moves

A **first decision** is a hand's first decision on exactly two cards: the dealt hand
before anything is done to it, or a split hand as soon as it has its second card.

| move | legal when |
|---|---|
| Stand | always |
| Hit | the total is under 21, and the hand is not a split ace while `canHitSplitAces` is false |
| Double | first decision only. The dealt hand always; a split hand only if its split rank is in `doubleAfterSplit` |
| Split | first decision only, on a pair, and only if the round would then have no more hands than the limit for that rank: `maxHandsAces` for aces, `maxHandsNotAces` otherwise |
| Surrender | first decision only. The dealt hand if `surrender`; a split hand if `surrender` and `surrenderAfterSplit` |

"Hands in the round" counts every hand, finished or not. A limit of 1 means that rank
may not be split at all.

If the policy returns a move that is not legal, throw `IllegalStateException`.

## Playing a hand

- **Stand**: the hand ends on its total, with its stake.
- **Hit**: draw one card. Over 21 loses the stake and ends the hand. Otherwise the
  policy decides again; that decision is not a first decision, so only Hit and Stand
  can be legal.
- **Double**: the stake becomes 2, draw exactly one card, and the hand ends. Over 21
  loses 2.
- **Surrender**: the hand ends and loses half its stake, 0.5.

## Splitting

A hand holding a pair `(r, r)` that splits becomes two hands, each holding one `r`,
each with a stake of 1.

**Order.** Draw the second card of the first new hand, then the second card of the
second new hand, both at once. Then play the first new hand to completion, including
any split of its own, and only then play the second. A split of a split hand works the
same way, in its place.

**Split aces.** A split ace is a split hand like any other, with one difference:
unless `canHitSplitAces`, it may not be hit. So its first decision, on its two cards,
offers Stand; Double if 1 is in `doubleAfterSplit`; Split if it drew another ace and
the limit allows; and Surrender if `surrender` and `surrenderAfterSplit`. Under rules
that allow none of those, as Montreal's do not, a split ace takes its one card and
stands. This is how the simulator plays a split ace too.

**A split hand of an ace and a ten.** If `blackjackOnSplitPairs`, it is a blackjack:
it ends at once, with no decision, and is paid `blackjackPayout` whatever the dealer
makes. Otherwise it is an ordinary 21 and follows the rules for any split hand with a
total of 21.

## The dealer

Once every player hand has ended:

- The dealer turns the hole card and draws from the shoe as the player hands left it,
  until the total is 17 or more. The dealer hits a soft 17 if and only if
  `hitsSoft17`.
- The dealer's outcome only matters for hands that ended by standing or doubling
  without passing 21. When there are none, the dealer does not draw, so a shoe too
  short for the dealer does not matter in that case.

## Settling a hand

For a hand that stood or doubled on a total `t` of 21 or less, with stake `s`:

- the dealer passes 21: `+s`
- `t` beats the dealer's total: `+s`
- equal totals: `0`
- the dealer's total beats `t`: `-s`

A dealer 21 made with three or more cards is an ordinary 21: it pushes a player 21. A
split hand paid as a blackjack, a player's natural, a surrender and a bust are settled
as described above and are not compared with the dealer.

## The policy

`RoundPolicy.choose(Decision)` returns the move for one decision. `Decision` carries
the hand's total, whether it is soft, how many cards it holds, its pair rank (0 if it
is not a pair), the dealer's up-card, whether the hand came from a split, whether this
is a first decision, and which moves are legal. A policy must be deterministic, and it
cannot see the hole card or the shoe.
