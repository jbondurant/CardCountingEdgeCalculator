# What the ceiling count's result will say, written before it is read

Written and committed while step 7 was still running, with runs/report-ceiling-97bf834.md
still empty (0 bytes), so that the words for each outcome are fixed before the outcome is
known. The commit's date is set on this machine, so a stranger has the repo's history and
this file's place in it to go on, not an outside record.

The page generator picks one paragraph per cell, by the rule of COUNT_DESIGN.md section 5,
and fills the bracketed figures from runs/report-ceiling-97bf834.ser. Nothing else in the
paragraphs is changed after the report is read.

## The rule

For the searched cell, 80% penetration on the 1-8 same schedule, each headline row k (F1, one
tag vector; F2, a play vector and a betting vector) is compared with each of its registered
comparators j (F1: every published count playing its own fit; F2: every published row, ace
side-count variants included), paired, by the ordinary 95% interval of k − j:

- **A, the claim holds** for k if the lower end of every k − j is above zero. If it holds for
  either row, paragraph A is used, naming the rows it holds for.
- **B, a gain too small to claim**: the claim holds for neither row, but at least one row's
  point estimate is above the best published row's.
- **C, no gain**: both rows' point estimates are at or below the best published row's.

The same rule, with F1 flat against the plain comparators, picks the flat cell's sentence.

## A. The claim holds

> A computer search for the best count of this kind, run on a separate set of 60,000 shoes
> and then scored once here, found one that beats every published count I tested: [ROW]
> scores [EV]% of a unit a round at 80% penetration with a 1-8 spread, [GAIN] more than the
> best published row, [BEST], and every published count by at least [LOWER] (the smallest
> lower end of the 95% intervals). The gain is small next to the room left above it: perfect
> knowledge of the cards left would earn [PERFECT]. The count's tags are not meant for a
> person to keep; they show how much better a count of ten numbers can do at this game, not a
> system to play.

## B. A gain too small to claim

> A computer search for the best count of this kind, run on a separate set of 60,000 shoes
> and then scored once here, found counts that score a little above the best published one on
> these shoes ([ROW] at [EV]% against [BEST] at [BEST_EV]%), but not by enough to claim it: the
> 95% interval of the difference reaches down to [LOWER]. What I can say is that no count of
> this kind beat the best published counts by a margin 60,000 shoes can show. The search found
> [OPTIMISM] more on the shoes it was tuned on than it kept here, which is the usual cost of
> tuning. Perfect knowledge of the cards left would earn [PERFECT].

## C. No gain

> A computer search for the best count of this kind, run on a separate set of 60,000 shoes
> and then scored once here, did not beat the best published counts: its best, [ROW], scores
> [EV]% a round against [BEST_EV]% for [BEST]. The gain it found on the shoes it was tuned on,
> [TWIN_GAIN], did not hold up on new shoes. That is an answer, not a failure: this search
> could not find a better count of this kind from 60,000 shoes. I say the published counts are
> near the best a count of ten numbers can do only if the half-file check also puts the
> shortfall left under 0.01% a round ([SHORTFALL] here). Perfect knowledge of the cards left
> would earn [PERFECT].

## The flat cell, one sentence each

- **A:** Flat-betting, the searched count F1 flat beats every published count I tested, by
  at least [LOWER].
- **B:** Flat-betting, the searched count scores [GAIN] above the best published count, too
  little to claim.
- **C:** Flat-betting, the searched count does not beat the best published count,
  [BEST_FLAT].
