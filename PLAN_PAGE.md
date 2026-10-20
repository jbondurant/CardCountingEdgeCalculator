# The results page

On 8 October Justin asked for the results to become a tab on his website: "high effort",
"less html bland", "pelucid", with explanations of the process, the results, the existing
literature and the new results, and made so that a reader, or a language model looking for
information on counting, has reason to trust them. He asked whether a hash could do that,
and said he did not know how anything could show there was no bug in the code.

This file is the page and trust part of the plan. The four new computations he chose (an
independent simulation, swings and money per hour, wider spreads and wonging, the perfect
full-play ceiling) have their own plans; this one says where their results go on the page
and how they are checked. Nothing here is built yet.

Where things stand on 9 October:
- The ceiling counts (COUNT_DESIGN.md's name; the page calls them the searched counts,
  5b) were registered in 97bf834 (8 October, 12:19). Step 7, their first-decision scoring
  on the main file, started on 9 October at about 19:16 and is running now with 12 GB of
  heap; its ledger line is in (16df265). Step 8 (their full-play rows) follows it. Nothing in this plan runs on
  the machine until both finish.
- The figures quoted below come from `runs/report-final-26f0724.md`,
  `runs/full-index/peek-c06c061-final.txt` and the custom-count files named beside them. I
  read them for this plan. On the page, every figure is filled in by code; none is typed.

## 1. The page, section by section

**How each section reads.** Every section opens with the site's existing small uppercase
label, then one sentence that answers the section's question, set a little larger
(`.headline`: 1.2rem, weight 600). Two to four short paragraphs and at most one figure
follow. Mechanics a reader can skip go in `<details>More detail</details>`; they stay in
the page's text, so search engines and language models still read them.

**Conventions in this section.**
- `{…}` marks a figure or phrase the generator fills in: from steps 7–8 (`{main}`), from
  one of the four new computations (`{sim}`, `{risk}`, `{spread}`, `{perfect}`), computed
  from existing outputs that no report printed (such as `{x}`, `{pairs}`), or worded from
  one of Justin's answers (section 6).
- Every other figure is quoted from the file named, at its printed precision.
- A headline that compares figures carries a claim guard (section 2.4): unless the data
  supports the sentence with an interval, the build stops and the sentence is rewritten
  by hand.
- Terms are explained in a sentence where they first appear, and each links to its entry
  in the glossary at the end. The answer's terms are explained in the box right under it.

**0. The answer.** Above everything else, one short paragraph:

> At the Casino de Montréal's eight-deck blackjack, a player who counts without mistakes,
> bets between 1 and 8 units and plays every decision by the count wins about 0.47% of the
> minimum bet per round with the best of the counts scored here (Victor APC, with a side
> count of aces), and about 0.36% with Hi-Lo, the most common count, when the dealer puts the cut
> card 80% of the way into the shoe. With bets averaging 1.5 units, that is about 0.31% and
> 0.24% of the money bet. At 70%, the shallowest of the three places studied, Hi-Lo
> {wins or loses} {x}% and the best count {y}%. {cut-card sentence} Someone who plays the
> standard strategy chart without counting loses 0.66% of the first bet of each round.

Right under it, one line of conditions, in the same type:

> Every decision is played from a table of index plays the program worked out for this
> game, not from the authors' published index lists; published estimates for a six-deck
> game say a short list captures less (section 9).
> "1 to 8 units" means the same bet sizes for every count, averaging 1.5 units, handed out
> in the order of each count's own estimate of the advantage.

A unit is the player's smallest bet. 0.47 is Victor APC with an ace side count, +0.4717,
and 0.36 is Hi-Lo, +0.3641, both with the count used for every decision in the round
(peek-c06c061). Without its side count Victor APC scores +0.4154, and the best count with
no side count is Zen, +0.4405; the answer names the side count for that reason. 0.31 and
0.24 are those figures divided by the average bet, 1.503 units at 80% (report-final), and
the generator computes them; published "player advantage" figures (Wizard of Odds, CVCX)
are per amount bet, so this is the figure to set beside them. The 70% figures come from
`results.ser`, which holds full play at all three penetrations (the peek printed only 80%);
at first decisions Hi-Lo at 70% is −0.076 (report-final). 0.66 is the chart played flat,
−0.6604, per unit of each round's first bet, the basis of published house edges.

The cut-card sentence comes from Justin's answer to question 4 (section 6). By default it
reads: "I have not measured where Montreal's dealers put the cut card." Penetration is the
largest single influence on the result (5d), so the answer gives the worst of the three
places beside the best and does not lead with 80% alone.

When the risk computation lands, one sentence is added, per 100 rounds so that it needs
no assumed pace of play:

> At $25 a unit, that is about ${m} per 100 rounds, against a swing of ±${sd} over the
> same 100 rounds.

Money per hour stays in section 7, where the rounds per hour are visibly the reader's
choice.

Below the answer:
- **The words above.** A box of four to six lines, one plain sentence each: unit; spread,
  and what "the same bet sizes for every count" means; cut card and penetration; the
  chart (basic strategy); played by the count, meaning the fitted index table; points
  (percentage points of the minimum bet per round, used when two results are compared).
  Each links to its glossary entry. Section 3 explains them again more fully.
- **A summary for quoting.** A short `<dl>` with the headline figure per round and per
  amount bet, its unit, its 95% interval, and its conditions: the rules; the
  penetration, with 80% named as the most favourable of the three studied and the 70%
  figure beside it; the spread as the same schedule, average bet 1.5 units; every
  decision played from index tables fitted for this game, not published lists; the ace
  side count; a counter who makes no mistakes. Then the version and the date. It is
  written for people; it also gives a language model one unambiguous sentence to quote.
  It contains no instructions to machines.
- **A provenance line.** Version, date, the commits the figures come from (as links), the
  manifest's SHA-256, and a link to "What a hash proves, and what it doesn't" (section
  10 of the page).
- **The status, plainly:** "These results come from code written mostly by AI coding
  agents under my direction (see How AI was used). Nobody else has reproduced them yet."
- **Contents.** A list of the sections. On screens 75rem and wider it sits in the left
  margin and stays in view.

**1. The question.**

> I wanted to know which counting system is worth the most at a table I can actually play
> at, measured carefully enough that the differences between systems are bigger than the
> measurement's own uncertainty.

**2. The game.**

> The rules are the Casino de Montréal's: {rules sentence}.

The rules sentence is printed from the rules object the engine ran with (the `game` block
of `results.json`, built from `HouseRules.getMtlCasino25MinBlackjackParams`), not written
by hand, so the page cannot describe a game the code did not play. The figure is a bar of
the 416 cards of an eight-deck shoe drawn as thin ticks, with the three cut-card places
(70, 75 and 80%) marked. Its caption gives the size of the data: 60,000 shuffled shoes,
{rounds} rounds dealt, 60,743 moments sampled from them.

**3. How counting works.**

> A count gives each card a small number, its tag. Adding up the tags of every card you
> have seen tells you roughly whether the cards still to come favour you or the dealer.

The terms this section explains, each in one plain sentence: running count, true count
(the running count divided by the decks left), balanced and unbalanced, level (the
largest tag), index play (a departure from the chart when the count passes a number),
side count (a second count kept of one rank, here aces), insurance, and expected value,
always written as "% of a unit per round". It also explains more fully the words the
answer's box gave one line each, and says so rather than introducing them: penetration,
unit and spread, the same bet schedule, the chart (basic strategy), fitted plays, and
points. Every one of these is in the glossary. A small worked table shows Hi-Lo's tags
and the running count after six cards.

**4. How it was computed.**

> Instead of playing millions of hands and averaging what happened, the program took
> 60,743 moments from 60,000 shuffled shoes and worked out exactly what every possible
> first move was worth from each one.

- **A worked example, computed while the page is built.** The generator calls the exact
  engine (`ExactRound`) on a full eight-deck shoe and prints: "Off the top of a fresh
  shoe, a hard 16 against the dealer's 10: standing is worth {x} of the bet, hitting
  {y}." It takes milliseconds, and it shows the reader what "exact" means on one hand.
- **What "fitted" means**, one plain sentence before the list: the program chose each
  count's plays and bets from the data, picking on half of the shoes the move and the bet
  that did best at each count, then graded those choices on the other half.
- **The method as a numbered list of boxes** (an HTML `<ol>` with CSS arrows, so it reads
  the same at any width): a seeded shoe; dealt with the chart down to the cut card; a
  moment in that shoe, meaning the cards left (a state); kept with chance q and weighted
  1/q so the sample stays fair; 550 possible deals from it (two player cards and the
  dealer's up card), each with its legal first moves; the exact value of each move over
  every way the round can finish, about 1,720 numbers per state; each count's plays and
  bets fitted on half the shoes and scored on the other half; 1,000 bootstrap resamples
  of the shoes, the same 1,000 for every count.
- **Why exact values, not a simulation.**

  > The best counts differ by a few hundredths of a percent. A simulation that plays two
  > counts on the same rounds cancels most of the luck they share, but it still needs
  > about {N} rounds to tell them apart as finely as this study does. Exact values remove
  > what is left: the luck of the card order within each moment. What remains is the luck
  > of which shoes came up, the same for every count.

  {N} comes from the second program's mode A run (section 8 and PLAN_COMPUTE.md 1.5),
  which plays every count on the same rounds: the measured standard deviation of the
  per-round difference between Victor APC + ace and Hi-Lo at 1-8, set against
  report-final's paired standard error for that difference. It is computed by the
  generator, not here. It is not computed from the SD of one count's rounds, which would
  describe a simulation that plays each count on separate rounds and overstate what exact
  values add.
- **First decisions and full play.**

  > Every count was scored twice: once using the count only for the bet, insurance and
  > the first decision on the two cards dealt, and once for every decision in the round,
  > including later cards and split hands. For most counts, using the count everywhere
  > adds 0.06 to 0.10 points (percentage points of the minimum bet per round).

  At 80% and 1-8 the gain runs from +0.0594 (KISS I) to +0.0972 (Revere 14) for the
  published counts, except the two that follow only one or two ranks: Ace-Five +0.0223
  and Revere Five-Count +0.0298 (peek-c06c061). The generator words "most" from the data. The gain's interval is printed only once it has been
  widened as FULL_INDEX_PLAYS.md section 4 says (section 2.4 below).
- **What a bootstrap is**, in one sentence in `<details>`: the shoes are redrawn with
  replacement 1,000 times and every figure recomputed, and the spread of those 1,000
  answers is the figure's uncertainty.

**5. Results.**

*5a. The headline.*

> At 80% and a 1-8 spread, the five best counts here (Victor APC, Hi-Opt II, Revere 14,
> Uston APC and Omega II, each with an ace side count) are within 0.012 points of each
> other. This data cannot say which of them is best: it cannot separate {pairs}. It does
> rank {ordered pairs}. Each of the five beats Hi-Lo, by 0.10 to 0.11 points.

Full play, 80%, 1-8: +0.4717, +0.4703, +0.4671, +0.4663, +0.4604, against Hi-Lo +0.3641;
their differences from Hi-Lo run from +0.0962 to +0.1076 (peek-c06c061). The pair lists
are generated, not written. SiteExport takes the 10 pairwise full-play differences among
the five, resample by resample from `results.ser`, widens each by the larger of the two
counts' difference ratios (`SystemScorer.Widening.difference`; 1.07 for every side-count
variant, which the pilot did not measure), and sets one max-|t| multiplier over the 10
(`Bootstrap.maxT`). A pair whose simultaneous interval includes 0 is listed as "cannot
separate"; a pair whose interval excludes 0 is listed as ranked. The held SEs in the peek
already suggest which: Victor APC + ace and Hi-Opt II + ace are 0.0014 apart, while Victor
APC + ace and Omega II + ace are 0.0114 apart with SEs near 0.002 (about 4 to 5 SE, above
the multiplier); at first decisions, report-final's simultaneous intervals for those two
([+0.089, +0.099] and [+0.077, +0.088]) do not overlap. "Best" is guarded on its own: the
words "cannot say which is best" need at least one pair involving the leader to include 0.
"Each beats Hi-Lo" needs the lower end of each of the five's widened simultaneous interval
of its difference from Hi-Lo to be above 0. A side-count row's label says where its rule
comes from, read from the catalog's `aceSideCount` field: "the author's rule" (Victor APC;
Uston APC counts the ace separately, with QFIT's multiplier), "a side count its sources
recommend or allow, with QFIT's multiplier" (Hi-Opt II, Omega II), or "QFIT's general
rule, added here" (Revere 14, for which the catalog records no published side count). Only
the first kind is called a published system with a side count.

*5b. The ladder.* One figure, three lanes, all at 80% and 1-8, each row a dot with its
interval:

| Lane | Rows, lowest to highest |
|---|---|
| Betting only (the chart for play) | Hi-Lo, the best published count, perfect betting {main} |
| First decisions | Hi-Lo +0.294, best published +0.387, searched count (F1) {main}, perfect first move {main} |
| Full play | Hi-Lo +0.364, best published +0.472, searched count (F1) {main}, perfect full play {perfect} |

The null count (a count that tags every card 0, −0.993 at 1-8) and a break-even line
anchor the axis. The betting-only figures come from report-final's betting-only rows.
On the page, "ceiling" names only the perfect rows, which are true ceilings for this
schedule. The searched count is "searched count (F1)" or "best linear count found", never
"ceiling count", because it is the best of one kind of count that one search found
(COUNT_DESIGN.md calls it "a lower bound on the linear ceiling").

*5c. Every count.* By default a static dumbbell chart: one row per count, a hollow dot at
first decisions and a filled dot at full play, 80% and 1-8. Above it, controls for
penetration (70, 75, 80%), spread (flat to 1-8, then 1-12 and 1-16 when {spread} lands),
wonging on or off, and which decisions use the count. Below it, a table that sorts and
filters: level, balanced, side count (with where its rule comes from), EV with its
interval, difference from Hi-Lo with its simultaneous interval, gain from full play, and
this study's own correlations, labelled "measured betting correlation (this study's
definition)", "first-decision playing efficiency" and "measured insurance correlation".
They measure different things from Griffin's published BC, PE and IC (LITERATURE.md 2D:
ours correlates the true count with each state's actual advantage; our PE covers first
decisions only, flat bets), so the published figures do not share these columns or
labels; they appear in section 9, beside BC divided by R. Each row has a stable anchor
such as `#count-zen` so a reader or a model can link to one count.

*5d. Penetration.*

> Where the dealer puts the cut card matters more than which count you use. Hi-Lo goes
> from −0.076 at 70% to +0.294 at 80% (first decisions, 1-8), a bigger change than the gap
> between Hi-Lo and the best count.

Guarded: Hi-Lo's change across penetration less the best count's lead over Hi-Lo at 80%,
at the same play scope, is taken resample by resample, and the lower end of its 95%
interval must be above 0.

*5e. Spread and wonging.* One small panel per penetration, EV against spread; lines for
the null count, Hi-Lo, the best published count and the searched count, and the perfect
bound dashed.

> With flat bets nothing wins: even perfect knowledge of the first move loses {main}% a
> round. The edge comes from betting more when the count is high.

The flat bound must be the main-file figure from step 7. bounds-d6a005c is on the twin
file, so its numbers appear only in section 6's account of the search, labelled "search
file". Wonging means sitting out rounds when the count is low; its rows say how many
rounds are skipped.

**6. Is there a better count?**

> The best count of this kind (one number added per card, like Hi-Lo's tags) that a
> computer search found, with tags not designed for a person to keep, scores {main} at 80%
> and 1-8, {main} above the best published count, while perfect knowledge of the first
> move would score {main}.

The generator picks this sentence from the three result paragraphs written and committed
before anyone opens step 7's report (PLAN_COMPUTE.md 4.1); the one above is the version for
a gain whose interval is above zero.

- **How it was searched**, in two paragraphs. The tags were searched on a second file of
  shoes from a different seed (20261007), so the shoes the count is graded on played no
  part in choosing it. On that search file, one tag vector (F1) scored +0.391 and a
  separate play and betting vector (F2) +0.419, against Hi-Opt II with an ace side count
  at +0.398 on the same grain (search-0b85be2). These are labelled "search file" every
  time they appear and never used in a headline.
- **The registration box**, printed from `custom-count-ledger.tsv` and the registration
  commit: "The tags were committed in 97bf834 on 8 October 2026, before these counts were
  first scored on the main file (according to this repo's history; commit dates come from
  my own clock). The main file had already been used to score the published counts and to
  settle some method choices (section 10)." Below it, every ledger line verbatim (commit,
  file blob, time, what ran), each with one generated clause saying what that look was for:
  step 7's first-decision scoring, step 8's full-play rows, and the later scoring of the
  same registration for wide plans and the simulator (PLAN_COMPUTE.md 6.3). Section 10 of
  the page says what that does and does not prove.
- **The tag strip.** An HTML table, columns A, 2 to 9 and T; rows F1, F2's play and betting
  vectors, this game's effects of removal (how much removing one card of a rank moves the
  player's expected value), Zen, Hi-Opt II, Victor APC and Hi-Lo. Each row is centred and
  scaled to Hi-Lo's spread by the code `SearchReport` already uses, and the values are
  printed in the cells; the cell colour is a second cue, not the only one.
- **What the search file showed**, labelled as such: the 5 carries F1's largest tag, the 8
  is slightly positive and the 9 negative, and F1 lies 8.4° from the effects of removal and
  16.4° from Hi-Lo. Each is re-derived from the main-file tags when step 7 lands; if one no
  longer holds, it is dropped, not reworded.

**7. What it means at a table.** Filled by the risk computation; until then the section
says "Not yet computed: swings, money per hour and risk of ruin, expected {date}".

> The win is small next to the swings: after {T} rounds a Hi-Lo counter betting $25 to
> $200 is up ${m} on average, and {k} in 100 are still behind. At {r} rounds an hour,
> your choice below, that is about {h} hours.

- A fan chart: expected win against rounds played, with bands one and two standard
  deviations wide, for Hi-Lo and the best published count.
- A table of risk of ruin (the chance of losing a whole bankroll before winning) by
  bankroll in units, for the top counts.
- Figures per 100 rounds come first, since they need no assumed pace (PLAN_COMPUTE.md
  2.3, item 4). Rounds per hour is an assumption, shown as such, with three precomputed
  choices (50, 80 and 100), the same three PLAN_COMPUTE.md 2.3 computes; none is
  presented as the norm unless a cited source gives one. The browser only switches
  between them; it does no arithmetic, so every number on screen comes from the
  generator.

**8. A second program.** Filled by the simulation of PLAN_COMPUTE.md section 1. Its
opening links to How AI was used (section 11), since an AI agent wrote this program too.

> A second program, a plain simulator written separately from the same rules document by
> an AI agent that did not read the engine's code, played {N} rounds with the strategies
> and bets the engine exported. The dealing, play, payoffs and bets of those strategies
> agree with the exact values within {z} standard errors.

Under it, PLAN_COMPUTE.md 1.9's list of what this check cannot catch, as a short list: a
rule both programs misread, an export bug both share, conventions both follow by choice
(cards taken by the chart's play, the count frozen in split hands), the chart itself,
whether each fitted play is the best one, the fitting and the bootstrap intervals, the
catalog's tags, and differences smaller than its limits. The agreement rule (which
quantities, what z, how many comparisons) is committed before the simulation runs, and
the page links that commit. The figure is a chart-table of z
scores, one row per count and setting, with any row past the rule shown and explained,
not dropped. If the simulation disagrees, the page says so and the headline waits.

**9. Prior work.**

> Of the {n} published figures I compared with mine, {a} agree, {d} agree in direction
> only, {c} differ for a reason tested by code here, {u} differ for a reason not yet
> tested, and {x} differ for no reason found. The closest published study of an
> eight-deck game, Schlesinger's 1997 eight-deck simulations, was not read.

The sentence is generated: SiteBuild counts the verdicts in `literature.json` (written by
`LiteratureCheck`, LITERATURE.md section 1), one count per kind, and drops a kind whose
count is zero. No sentence explains a difference away unless committed code tested the
explanation; the open gaps LITERATURE.md records (the 0.0004% house-edge gap, the split
values' explanation from a scratch script with a further gap of 0.02 to 0.07, the size of
the ten's effect of removal, the untested reasons PE comes out lower) are counted as
untested or unexplained until code tests them. Wherever conditions differ, the verdict
column says "agrees in direction" rather than "agrees".

- The house edge: the chart flat at 80% is −0.660% here. The Wizard of Odds calculator is
  interactive, so its figure for these rules is entered, recorded with the exact inputs
  and the date, and archived before the page states it (section 3.5).
- Betting and insurance correlations: scatter plots with the y = x line, published IC
  against ours, and published BC against ours divided by R, the best correlation any
  linear count reaches on the same states (from step 7's save, as LITERATURE.md 1(d)
  plans; if the save has no R, the page shows BC without BC/R). Hi-Lo is the worked case:
  insurance correlation 0.762 here against .76, betting correlation 0.957 against .97
  (report-final; QFIT's figures). Each source's status (checked, read, secondary, not
  read, with its date) comes from `literature.json`, never from this plan's text; QFIT's
  figures were checked against the source on 9 October (LITERATURE.md 2D). The published
  BC is computed from effects of removal; ours correlates the true count with each
  state's actual advantage, on eight-deck states. The page says that difference where
  the points sit off the line.
- A claims table: claim, source (linked), their game, their number, our nearest comparable
  number, verdict and why, and the source's status worded from `checked_by` and
  `read_by_justin` as LITERATURE.md section 1 sets out. It is read from the committed
  `literature.json`; only records whose status is CHECKED or READ are stated as published
  figures. The counting-systems catalog was compiled by an AI research workflow, and the
  page says so.
- Six-deck simulation figures from other games (stand on 17, surrender) are shown only in
  `<details>`, marked as a different game.

**10. How it was checked.**

> On {N} random small shoes, over every card order of each, the exact engine matches a
> separate brute-force program. The dealing, play, payoffs and bets of the fitted
> strategies match a second program over {R} rounds (section 8). Of the bugs planted on
> purpose to test the tests, the tests caught most on their first run, not all:
> {first-run summary}. Each one missed then has a test that fails on it now.

The opening links to How AI was used (section 11), since AI agents wrote the code, the
tests and the reviews this section reports. The first-run summary is generated per
review from `verification/reviews.tsv` and states each kind of miss, for example
"ExactRound: 6 of 6 failed the suite, one only because a run ran out of memory; scorer:
9 of the reviewers' bugs (8 in the scorer, 1 in the report) still passed every test after
a first round of new tests". Where a commit records no first-run count, the row says
"not recorded" rather than a number (section 3.5). The brute-force sentence names the
shoe sizes in its row: 14 to 24 cards, 1 to 13 cards, and splits on 30 to 50 cards, with
the rounds too big for the brute force left out and counted.

A table: what was checked, what it would catch, what it cannot catch, the result, and
where it lives (a test or a commit), and whether it was re-run for the page. It is
section 3.5's evidence table, generated. Then the hash box:

> A SHA-256 hash proves that the file you downloaded is, byte for byte, the file listed
> here. That these commits produced it is my statement. You can test it by reproducing
> the file (section 13): the states file byte for byte, `results.ser` only to within
> 1e-12. For the first segment of the states file and for the search file, the build that
> made them is inferred, not recorded. A hash says nothing about whether the code is
> right; a bug is hashed just as faithfully. The table above is the evidence that the code
> is right, and it is evidence, not proof.

**Corrected 18 October 2026.** That box was wrong both ways, and the page no longer says it.
The figures come back to the bit, not only to within 1e-12: measured for one fit on the same
number of threads, predicted for the rest. The `.ser` files never come back byte for byte,
since each records its own run times (SystemScorer's `Result.seconds`, FullIndexFit's
`PassRecord.wallSeconds`). The states file coming back byte for byte is a prediction, never
tried. And "the file you downloaded" covered files the page never ships. The box in
`site/template.html` now says which hashes a reader can check from the page (those of the
files served with it) and that the rest are recorded values.

**11. How AI was used.** Section 4 of this plan. It stays after the checks so that the
page reads question, method, results, checks; but the status line under the answer says
in one clause that AI agents wrote most of the code, and sections 8 and 10 each open with
a link here, so no reader meets the checks without knowing who wrote them.

**12. Limits.**

> These results are for one player alone at the table, counting without mistakes and
> seeing every card, at one casino's rules. They say nothing about being noticed, about
> other players at the table, or about other games.

Then the design's own limits, generated from a committed list: the authors' own index
numbers and true-count divisors are not used, so each row is the best those tags can do
here; the count does not move within a split hand, so full play is a lower bound; 60,743
valued states; full-play intervals widened by ratios a 1,417-shoe pilot measured, each
count's own where the pilot measured it and otherwise the largest the pilot saw, and a
full-play level's interval by its count's difference ratio, a rule chosen here (2.4).

**13. Downloads, reproducing, citing.** The files table (name, size, SHA-256, commit,
download link), the three depths of reproduction (section 3.4), how to cite (version and
commit), a dated list of corrections, and the glossary.

## 2. How it looks and how it is made

### 2.1 Files

**In the website repo** (`jbondurant.github.io`), everything under `card-counting/` is
generated and copied in by one command; nothing there is edited by hand.
- `card-counting/index.html`: the page, complete without JavaScript.
- `card-counting/data/results.json` and `results.csv`: every figure on the page, with its
  source. `manifest.json`, `SHA256SUMS` and `MANIFEST.md`: section 3.2.
- `card-counting/assets/page.js` (vanilla JS, sorting and toggles only) and
  `card-counting/assets/fig/*.svg` (alternate chart views, fetched only when a control
  asks for one).
- `/llms.txt` at the site root (section 3.6).
- Hand edits, small: a one-line nav on `index.html` ("Home · Card counting"), the
  CardCountingEdgeCalculator blurb replaced by a link to the tab (the blurb has no
  numbers), and one README line: "card-counting/ is generated by CardCountingEdgeCalculator's
  `site/build-site.sh`; do not edit it here."

The website keeps no build step: it only receives built files. GitHub Pages runs Jekyll
over a branch-published site with plugins that cannot be turned off (GitHub's "About
GitHub Pages and Jekyll", checked 9 October), among them, as recalled, one that renders
Markdown without front matter to HTML. So `MANIFEST.md` would be served as HTML, not as
the bytes its hash names. The fix is an empty `.nojekyll` at the website root, which
tells Pages to publish the files as they are; it adds no build step. It goes on the push
list with the other website files. After each deploy, `SiteManifest --check-deployed`
downloads every site file `SHA256SUMS` lists from the live https URLs and checks it
against its hash.

**Before any of this: the site's HTTPS (a prerequisite, Justin's account actions).**
Checked on 9 October: https://justinbondurantdavid.com presents a `*.github.io`
certificate (curl: "no alternative certificate subject name matches"), and
https://jbondurant.github.io answers 301 to http://justinbondurantdavid.com/, plain HTTP.
In the website repo the `CNAME` file exists only on the branch `add-custom-domain`
(1147215), which is pushed but not merged; `main` has none, so it is not known from here
which branch Pages publishes. A trust page served over plain HTTP can be altered by anyone
on the path, and fetchers that upgrade to HTTPS, language-model fetchers among them, get a
certificate error and read nothing. So before the page is built for publishing, Justin:
(1) checks in the repo's Pages settings which branch publishes and whether
`add-custom-domain` should be merged; (2) gets a certificate issued for the custom domain
(re-save the domain in Pages settings and check the DNS records); (3) turns on Enforce
HTTPS. The page's edits go to the branch Pages publishes. Until https works, the page
states that its hashes are also in the GitHub tags and the Release, and the build's
browser pass and cold reads fetch the deployed https URLs.

**In the calculator repo**, under `site/`: the template (`template.html`, the only place
prose is written), `page.css`, `page.js`, `literature.json`, `short-names.tsv` (display
names such as "RPC / FELT / C-R"), `limits.tsv`, `releases.json` (filled only after an
upload Justin approved) and `build-site.sh`. The Java classes are in `src/` with the rest.

### 2.2 Colours and type

**Tokens.** The site's five tokens (`--bg`, `--fg`, `--mute`, `--rule`, `--link`) and their
dark-mode block have one home, `index.html`. The generator reads them from there at build
time and writes them into the new page; it stops if it cannot find all ten values. So the
two pages cannot drift apart, and nobody has to keep a copy in step. The page adds:

| Token | Light | Dark | Use |
|---|---|---|---|
| `--c1` | `#2a6cb0` | `#4f8fd6` | blue: Hi-Lo and published counts picked out |
| `--c2` | `#c2611f` | `#d9762f` | orange: this project's rows (searched count, simulation) |
| `--band` | `--fg` at 12% opacity | same | intervals and swing bands |
| `--grid` | `--rule` | `--rule` | grid lines |

The tag strip's diverging scale runs blue, grey, orange. Two hues only: an earlier draft
of this plan ran a palette check that passed these two in both modes and failed a third
(purple) against the blue for red-weak colour vision. Other distinctions use grey for
reference rows, the text colour for published rows and dashes for bounds. Orange is 4.11:1
on the light background, under the 4.5:1 that small text needs, so it is never used for
text; labels use the text tokens. `SiteBuildTest` computes the contrast of every token
pair the page uses (the WCAG formula, in Java), so the check is in committed code. Dark
mode follows the reader's system setting, as `index.html` does; every chart is inline SVG
or HTML using the variables, so it switches with the page.

**Type.** Prose stays 17px Charter or Georgia, as on the home page. Tables, axes and chart
labels use `system-ui` at 14px with `font-variant-numeric: tabular-nums`, because Georgia's
old-style figures do not line up in columns. Hashes and commands use `ui-monospace` at
13px and wrap anywhere.

**Layout.** Prose keeps the 40rem column. `.wide` figures may run to 56rem on large
screens. At phone width (375px) nothing scrolls sideways: wide tables scroll inside their
own box with the first column fixed, and the build's browser check measures it.

### 2.3 Charts

Two forms, chosen so text stays legible from 375px to 1280px:
1. **Chart-tables**, for every dot and interval chart (the ladder, the ranking, the
   simulation's z scores, the literature comparison). Each is a real `<table>`: labels and
   values are HTML text, and the mark cell holds spans placed by CSS (`left:%`,
   `width:%`) on one shared scale, with a tick row at the top. The table is the chart. It
   reflows on phones, a screen reader reads it, and a language model reads its numbers as
   text.
2. **Small SVG panels**, for line charts (spread, hours). Each is drawn 320 units wide and
   shown at 260 to 400px; panels wrap in a CSS grid.

Marks: 2px lines that keep their width when scaled; 8px dots, hollow for first decisions
and filled for full play; bounds dashed; this project's rows marked ◆ and "(this
project)", so no meaning rests on colour alone. Every mark has a native `<title>` tooltip.

Each figure has a caption that states its
takeaway with its number, and every chart has its numbers beside it.

### 2.4 The generator

Every number on the page comes from committed code run on the real outputs. Four Java
classes in `src/`, in the repo's own language and with no new libraries:

- **`SiteExport`** reads each result with that result's own frozen classes (`results.ser`
  is Java-serialised against `classes-c06c061`, the ceiling run's file against its own)
  and writes one plain JSON file per run into `runs/site/`, carrying the run's commit and
  the SHA-256 of what it read. It computes what the page needs and the runs did not print:
  intervals for every figure shown, the pairwise test among the five best rows (5a) with
  one max-|t| multiplier over its 10 pairs, the per-amount-bet figures (EV over the
  schedule's average bet), and the full-play intervals widened by FULL_INDEX_PLAYS.md
  section 4's factors. That section sets ratios only for a difference and for a gain, so
  this plan sets the rule for a full-play level (one count's EV, such as Victor APC + ace's
  +0.4717): its interval is widened by its count's difference ratio (1.03 to 1.07, or the
  1.07 default). A level's SE (about 0.018 at 80%, 1-8, first decisions, report-final) is
  mostly the luck of which shoes came up, which the bootstrap already redraws; the held
  later-play fit adds a part the size of a difference's, near 0.0015, so the choice moves
  a level's interval by under 0.002, and the page's Limits say it was chosen here.
  SiteBuildTest checks the rule on a fixture. SiteExport refuses to write a full-play
  interval, level, difference or gain, that has not been widened. If it cannot load a
  `.ser` against today's classes, the fallback is a small exporter committed on that run's
  own line and run with its class path, never a parse of the markdown report.
- **`SiteBuild`** merges those files into `results.json`, parses the file it just wrote and
  renders the page from the parsed copy, so the page shows exactly what the JSON says.
  Prose lives in `site/template.html`, numbers in placeholders such as
  `{{ev:full.80.s8.hilo}}`, each rendered as `<span data-k="full.80.s8.hilo">`. Each entry
  in `results.json` records `{value, se, ci, src, at, commit}`: the artifact, the table row
  or object path, and the commit that made it.
- **`SiteSvg`** draws the SVG panels and the chart-table marks from the same data.
- **`SiteManifest`** writes the verification files (section 3.2).

Rules the build enforces, each in `SiteBuildTest`:
- **No typed numbers.** The build stops if the template's prose holds a result-like number
  (`[+−-]?\d+\.\d{2,}`) outside a placeholder. Game constants (8 decks, 416 cards, 3 to 2)
  come from the `game` block. After rendering, every digit sequence in the page's text must
  sit in a `data-k` span or on a short committed allowlist (dates, rule constants, version
  strings), and every span must equal `results.json` under the page's formatting rule.
- **Claim guards.** A comparative sentence carries a test, for example
  `{{guard: (hilo.fd.s8.80 - hilo.fd.s8.70) - (best.fd.s8.80 - hilo.fd.s8.80) > 0}}`. The
  guard is met only with interval support: the quantity is computed resample by resample
  and the lower end of its 95% interval (widened where it involves full play, and
  simultaneous where the sentence ranks several rows) must be above 0. The right order of
  point estimates is not enough. A sentence saying the data cannot separate two rows needs
  their interval to include 0. If the data does not support the sentence, the build fails
  and a person rewrites it.
- **One number, one value.** Where two sources hold the same figure, they must agree at the
  printed precision or the build stops. c06c061's first-decision column must match
  report-final (Victor APC + ace: +0.3875 in results.ser, +0.387 in the report; Hi-Lo
  +0.2937 and +0.294), and the ceiling run's published rows must match c06c061's. This
  reuses `CeilingReport.checkPrinted`.
- **Search-file figures stay labelled.** A figure whose source is the twin file can only be
  rendered inside an element marked `data-file="search"`, which prints the label.
- **Pending sections.** A section whose data does not exist prints "Not yet computed:
  {what}, {when}". It never shows a gap or an older number.

`site/build-site.sh --to ../jbondurant.github.io` builds, runs the checks and copies the
output into the website's working tree. It does not commit or push (section 5).

### 2.5 Accessibility and weight

- Every `<figure>` has a `<figcaption>` stating its takeaway with the number. SVGs carry
  `role="img"` with a `<title>` and `<desc>` written from the data.
- Sort headers are `<button>`s with `aria-sort`; toggles are radio groups in a `<fieldset>`
  with a legend; focus rings are 2px in `--link`. Muted text on the light background is
  5.95:1.
- Without JavaScript the page is complete: the default views are static HTML, and controls
  appear only when the script has set `html.js`. A print stylesheet hides the controls.
- Budget, enforced by the build: page HTML at most 448 KB, `page.js` at most 12 KB, no web
  fonts, no CDN, no images other than inline SVG; `results.json` at most 2,560 KB, loaded only on
  the first toggle. The first build on the real inputs (17 October, 7c1d40e) measured the page at
  213,078 bytes (35,753 gzipped) and results.json at 1,581,573 (225,242 gzipped), over this
  plan's first estimates of 200 KB and 500 KB, which were made before any real input existed;
  the data file carries every figure the page or a reader can ask for. The first build with the
  simulator's and the risk sections (20 October, 1946b3d) measured the page at 385,633 bytes
  (58,547 gzipped): the simulator's section is 117,965 of it, its table of every registered test's
  z 44,787 and its edge by count, folded in a details element, 58,527. The page stays complete
  without JavaScript, so those stay static HTML and the budget went from 256 KB to 448 KB; moving
  the edge-by-count table into a downloadable CSV, with a sentence in its place, is the plain cut
  if the page should be lighter. Data URLs carry
  `?v=` and the manifest's hash, so a cached file from an older version is never mixed in.

### 2.6 How the page itself is checked

- `SiteBuildTest`: every placeholder resolves; the number rule and the claim guards pass;
  every figure has a caption and its numbers in a table; every internal link resolves;
  external links are on an allowlist; each SVG parses (`javax.xml`); two builds give the
  same bytes; the size budget holds.
- A browser pass at 375px and 1280px, light and dark: screenshots, no sideways scroll
  (`scrollWidth ≤ innerWidth`), sorting by keyboard. Once deployed, the same pass runs on
  the live https URL, and `SiteManifest --check-deployed` compares every served file with
  `SHA256SUMS` (2.1).
- Two cold reads by agents that have not seen the work: one lists every term used before
  it is explained; one traces 30 random numbers back to their source rows.
- Justin reads the whole page before anything is pushed.

Cost, all single-threaded under `nice`, measured on the first build and written here then:
reading the 431 MB `results.ser` once is estimated at under 2 minutes with a 3 to 4 GB
heap; the order test over 1,000 resamples takes seconds; rendering under 10 seconds.

## 3. The verification package

Justin asked whether a hash could make the results trustworthy. It can make them
checkable, which is different. The package has four parts: hashes that pin what was
published, instructions to reproduce it at three depths, the evidence that the code is
right with its limits stated, and machine-readable files so a reader or a model finds the
figures with their conditions attached.

### 3.1 What a hash proves, and what it doesn't

A SHA-256 proves identity: the file you have is byte for byte the file the manifest lists.
That the listed commits produced it is the publisher's statement until a reader
reproduces it. Three things build on the hash:
- the git commit IDs show the source is what the tag names;
- rebuilding the class files from that source and getting identical bytes shows the
  binaries that ran could have come from it;
- re-running shows the results follow from that code and those seeds, as far as each
  depth of 3.4 goes. Depth 1 re-deals the shoes, re-values about 40 states, re-scores
  Hi-Lo and the null count at first decisions without the bootstrap, and rebuilds
  `results.json` from `results.ser`; it does not re-score full play, the other counts or
  any interval. Depth 2 re-scores chosen counts. Only depth 3 reproduces the states file
  byte for byte (if the spot check's prediction in 3.3 holds), and `results.ser` can be
  reproduced only to within 1e-12, its run's own standard, not byte for byte. (Corrected 18
  October 2026: its figures come back to the bit, measured for one fit; its bytes never do,
  since it records its own run times. See the note under section 1, item 10.)

None of these shows that the code is right: that it models Montreal's rules correctly,
that it has no bug every test shares, or that its author did not try many variants and
publish the best. Nor do they show a date: a git date is whatever the committer's clock
said. Section 3.5 gives the evidence for correctness. The page says all of this in its
hash box, in about the words above.

### 3.2 The manifest

`SiteManifest` writes three files: `manifest.json`; `MANIFEST.md`, the same as a table;
and `SHA256SUMS` in the coreutils format, so `shasum -a 256 -c SHA256SUMS` checks every
download in one line.

Since 18 October 2026, `SHA256SUMS` lists only the files served with the page, by their paths
under `data/`. In 17 October's build (`runs/page-613255a/data`), 14 of its 16 lines named run
files the page did not have, two of them both `results.ser`. The run files' hashes are now in
`manifest.json` under `recorded` and in `MANIFEST.md`'s second table, as recorded values a
reader cannot check from the page alone. The build refuses a `SHA256SUMS` line it cannot back
with a file it writes, and `SiteBuildTest` runs the reader's check on the written folder.

Each artifact in `manifest.json` has: `id`, `path`, `bytes`, `sha256`; `produced_by`, a list
of segments, each with the commit (40 hex digits), the class directory's tree hash, the
command, and whether the command was `recorded` (as in `runs/final-report.sh`) or
`reconstructed` (from the log and the tool's usage line); `depends_on` (other artifacts,
seeds, the catalog file); its log; whether it is published as a release file, as a hash
only, or left to be regenerated; and the cost to regenerate, with its source. The top
level holds the Java version (javac 25.0.1), the two library jars with their Maven Central
URLs and hashes, the seeds (20260928 main, 20261007 twin, 20260929 bootstrap) and the tags.

A class directory's tree hash is
`(cd DIR && LC_ALL=C shasum -a 256 *.class | sort -k2) | shasum -a 256`, so anyone can
compute it in one line. The tool reads each output's provenance from the output's own
header where one is printed, and stops where none is: calibration, the search report and
the full-play log print their commit; report-final and the bounds file do not, so for
those the commit comes from the file name and is labelled so.

| Artifact | Bytes | Made by | Published as |
|---|---|---|---|
| `montreal-80.states` | 1,340,719,767 | StateRun, seed 20260928, three builds (below) | release file |
| `twin-80.states` | 1,341,183,279 | StateRun, seed 20261007, build not yet established | Justin's choice (section 6) |
| `report-final-26f0724.md` | 841,034 | `runs/final-report.sh`, recorded | in the repo |
| `full-c06c061/results.ser` | 430,694,877 | FullIndexRun, commit in its log | release file |
| `trees.bin` | 14,877,661,504 | FullIndexRun pass 0 | hash only, regenerable |
| calibration, search, bounds, peek files | small | as named | in the repo |
| registration, ledger, ceiling report and its `.ser` | pending | steps 6–8 | repo or release, by size |
| simulation, risk, spreads, perfect full play | pending | the four new computations | by size |
| frozen class directories | small | `javac` from the tag | tree hash only |

`trees.bin` is hashed before it is ever deleted.

**What was checked while planning** (8 October, small single-threaded runs, from the
verification draft this plan merges):
- Recompiling `git archive <commit> src` with javac 25.0.1 gave class files identical to
  the frozen ones for build-d3c0b1f (90 files), build-d791438 (92), build-26f0724 (120) and
  classes-c06c061 (184), in 1 to 3 s each. The custom-count class directories are not
  checked yet.
- `montreal-80.states` was written by three builds: shoes 0–6649 by a build the log does
  not name (probably d3c0b1f, by directory times; not confirmed), 6650–39999 by d791438,
  40000–59999 by 26f0724. d3c0b1f's engine sums in a different order (it agrees to 1e-12,
  not bit for bit), so regenerating the whole file with one build will not reproduce its
  hash. The manifest lists three segments, and 3.4 says how to check each.
- The twin file's build is recorded nowhere, and the engine changed between 26f0724 and
  fd7e2ab, so it matters. The spot check below settles it.
- On GitHub today: 26f0724, 077d971 and 552dc6c. c06c061, d791438, d3c0b1f, 7ce7e43, fd7e2ab
  and 97bf834 exist only locally. The repo has no licence and no README.
- The scoring code did not change between the first main-file look (report-quick-c3af4cf)
  and report-final, except 31 lines of report printing (`git diff --stat c3af4cf 26f0724
  -- src`).

**Tags.** One annotated tag per commit that produced something the page cites:
`states-seg1-d3c0b1f`, `states-seg2-d791438`, `report-final-26f0724`, `full-play-c06c061`,
`calibration-b99ccbf`, `search-0b85be2`, `bounds-d6a005c`, `register-97bf834`,
`ceiling-<sha>`, the four new computations' commits, and `site-data-<sha>`. Each tag
message carries the manifest's SHA-256, and the manifest records a SHA-256 of each source
file at each commit, so nothing rests on git's SHA-1 alone.

**The page's own HTML** is not in the manifest, since it shows the manifest's hash. It is
pinned by the website commit, whose message names the generator commit and the manifest
hash, and `SiteBuild --check-page` compares the published copy with a fresh build.

### 3.3 The spot check

A reader who will not spend hours needs a check that takes minutes and could still catch
a lie. `StateSpotCheck` (new) does it:
- It picks K states with a random generator seeded by a number the reader chooses;
  `verify.sh` asks for one. A seed taken from the manifest's hash would not stop a
  publisher choosing easy states, since the publisher writes the manifest and could change
  bytes until the draw suited. The page's own run, reported in section 10, uses a public
  value fixed after publication (the hash of the first Bitcoin block after the release
  time, named with its height), so its draw was not the publisher's choice.
- The page states its power. With K states drawn from 60,743, it catches a file in which
  a share f of states were altered with chance 1 − (1 − f)^K: at K = 40, about 33% when
  1% of states are altered and about 87% when 5% are (the generator prints these for the K
  used). It checks only the states file's stored values. It does not touch any EV figure,
  the fitting, the bootstrap or `results.ser`; depth 1's rescore (3.4) and depth 2 do.
- For each, it deals the shoe again from the seed (ShoeRun), values the state again on one
  thread (StateValuer) and compares all of its ~1,720 stored numbers bit for bit, the
  pattern of empty values included.
- It is compiled against each segment's own frozen classes, so every value is recomputed
  by the engine that first made it.
- Prediction, to be tested before the page states it: bit-identical in every segment. If
  it is not, the page says "reproduces within X", with X measured, and the byte-for-byte
  claims below are dropped.
- Cost: about 1 s of CPU per state on the current engine and about 14 s on d3c0b1f's
  (StateTimingReport, 28 September), so K = 32 plus 8 from segment 1 is about 3 minutes
  (an estimate). Run on the first 25 shoes, it also settles which build made segment 1,
  and run on the twin file, which build made the twin.

### 3.4 Three depths of reproduction

`verify.sh` runs depth 1 end to end. All times below are recorded unless marked.

**Depth 1, minutes: Java 25 and the downloads.**
1. Check the hashes: `shasum -a 256 -c SHA256SUMS`. About 1.8 GB hashes in seconds (an
   estimate from 0.44 s per 400 MB, cached).
2. Rebuild the classes from the tags and compare tree hashes: 1 to 3 s per build.
3. Deal all 60,000 shoes again from the seed and check every record's round, depth, card
   tallies and weight q against the file (the replay every report already runs: 1.9 s
   inside report-final).
4. The spot check (3.3).
5. A quick rescore: Hi-Lo and the null count at first decisions, with the build step 7
   holds to c06c061 to the bit (classes-97bf834; COUNT_DESIGN.md section 5) and a handful
   of resamples, since the point estimates do not use them (to be confirmed when the step
   is first written). Their point estimates must equal `results.ser`'s first-decision
   entries to the bit and report-final's to the printed digit. This is the one step of
   depth 1 that recomputes an EV figure. Minutes (an estimate: report-final spent 2,771 s
   on Hi-Lo with 1,000 resamples; measured on the first build and written here then).
6. Rebuild `results.json` from the downloaded artifacts and compare bytes: `SiteBuild
   --check`. Reading `results.ser` is estimated at about a minute. This shows the page's
   figures follow from `results.ser`, not that `results.ser` is right.

**Depth 2, hours: re-score from the published states.**
- First decisions for three counts from build-26f0724: `ComparisonReport montreal-80.states
  --systems "Zen Count" --resamples 1000 --threads 7` (with Hi-Lo and the null count).
  ComparisonReport scores one count per thread, so this takes about as long as the
  slowest count, Zen: report-final spent 4,577 s on it with every thread busy, so about
  1.3 hours or less, with four threads idle (estimate). The three rows must match
  report-final to the printed digit; the simultaneous intervals will differ, because the
  family of counts is smaller.
- Full play for Hi-Lo alone: FullIndexRun from classes-97bf834, the build step 8 runs,
  with `--counts` naming Hi-Lo and `--trees-read-only` (c06c061's own build has neither
  option, so at c06c061 only the whole catalog can be run). Its result must match
  c06c061's within 1e-12, the run's own standard, as step 8's Hi-Lo control already must.
  `trees.bin` is not published, so the trees are rebuilt each round and not kept: no
  extra disk, about 2,000 s a round on six threads, roughly 4 to 6 hours for one count
  (estimate, from COUNT_DESIGN.md section 7's prices; step 8's own timing replaces it).
- The whole catalog: 18,623.7 s on 7 threads for first decisions, and 19.8 hours on the
  wall for full play at c06c061 plus a new 14.9 GB tree store (both from their logs).

**Depth 3, days: from the seeds.** The states file is rebuilt the way the log shows it
was made: StateRun resumed through three builds in order, d3c0b1f to shoe 6649 (26,065 s;
the build is inferred, see 3.2), d791438 to shoe 39999 (17,238 s) and 26f0724 to shoe
59999 (3,650 s), all on 8 threads (`runs/montreal-80.log`), then `cmp` against the
published file. Then the twin (3.2 hours), calibration (1 hour), searches (5 to 6 hours)
and the ceiling run (step 7 about 3 to 4 hours, step 8 by its log); then depth 2. About
50 to 65 machine hours, or 3 to 5 days on a laptop that sleeps with its lid closed
(estimate).

The page gives the commands for all three and says plainly which have been run by whom.
Today that is: only by the author's own tools.

### 3.5 Evidence that the code is right

Each row on the page gives what was compared, at what scale, the result, the commit, and
whether it was re-run for the page. Rows come from two places, and the page labels which:
- **Re-run at the site tag, generated from the output.** The whole suite runs once at the
  tag with JUnit's XML report, which gives the test count and result. ExactVsBruteForceTest
  prints its cases, rounds, rounds left out and worst gaps (39 s on eight threads in
  077d971), and its row is written from that print. Tests that print no counts
  (RoundValueTest, BasicStrategyTest, the large-shoe test) give pass or fail at the tag,
  and the row quotes their scale from the test's source at the tag. The replay of every
  shoe and the depth-1 rescore (3.4) are re-run too.
- **As recorded, not re-run.** Every other row says "as recorded in commit X on date D,
  not rerun". `EvidenceCheck` (new) requires every such figure in
  `verification/evidence.tsv` to appear word for word in the cited commit's message or a
  file committed at it; a figure it cannot find is not printed. A match shows only that
  the record says it: the commit messages were written by AI agents.

**Planted bugs.** The bugs were planted in scratch copies that were not kept, so no
branch holds them. Two things go on the page. First, the catch rate on the first run, per
review, as the commits record it, with each kind of miss named: a bug caught only because
a run ran out of memory is not counted as caught by an assertion, and where a commit gives
no first-run count the row says "not recorded". Second, `verification/planted/`: each
planted bug the commits describe, re-created as a patch from that description and marked
so, with `planted.sh`, which applies each to a scratch copy at the site tag, runs the
test classes named and writes which fail. That table is regenerated from code, but it
shows only that today's tests catch each bug, which is nearly all by construction, since
tests were written to catch them; the page calls it a regression check, not a catch rate.
Cost: one compile and one or two test classes a patch, single-threaded, about an hour in
all (estimate).

| Check | Result (source) | Would catch / cannot catch |
|---|---|---|
| ExactRound against BruteForceRound, written separately from ROUND_CONTRACT.md | re-run: random small shoes, every card order of each; 077d971 recorded 3,000 cases on 14 to 24 cards giving 11,420 rounds valued by both, worst gap 1.3e-15, and 1,930 left out as too big for the brute force; 1,500 cases on 1 to 13 cards; 600 splits on 30 to 50 cards; a longer seed, 114,633 rounds, worst 2.0e-15 | engine logic on the shoes tested / a rule the contract itself states wrongly; rounds too big for the brute force |
| Six bugs planted in ExactRound after its tests were written | first run: five failed an assertion; the sixth (a split ace hit when the rules forbid it) failed only because the run ran out of memory (077d971, as recorded) | the page says that plainly |
| Large-shoe limit | standing on 16 to 20 approaches the infinite-deck value as 1/decks, under 1e-4 at 512 decks (077d971) | scaling errors |
| The chart against the engine | the published 4–8 deck H17 chart is the engine's best first move for all 540 hands off the top (552dc6c; BasicStrategyTest, re-run) | agreement with published strategy |
| Round-value formula | equals the average over every order of a 9-card shoe within 1e-12 (RoundValueTest, re-run) | |
| Player tree against stored values | 320,392,174 hit and 32,789,360 stand values, worst gap 7.8e-16 (7ce7e43) | |
| States against the seed | every report re-deals all 60,000 shoes and refuses a file that differs | a corrupted or swapped file |
| Scorer review | 3 reviewers, one recomputing independently; 24 + 38 planted bugs. First run: dd7eb69 lists those that passed every test, with no count. After a first round of new tests, 8 in the scorer and 1 in the report still passed every test (1157624, correcting da47a03); each then got a test that fails on it. 52 of 57 findings fixed, 5 left open as not changing a number (7500542), then closed | |
| Full-play reviews' planted bugs | per review, as recorded: for example four in SplitValues passed every test (0a39ed8), four shared by both walks passed (82b80f1), one in the later-cell bound passed (4fa2859); each now has a failing test | |
| Bootstrap against disjoint sub-runs | close for EVs; for Red Seven's difference, under (0.031 against 0.045); for Omega II, about 3 times over (COUNTING_COMPARISON section 8) | stated as found |
| Internal controls | null count flat = chart flat (−0.660); null count gains +0.0009 (SE 0.0004) from full play; no row above its perfect bound; 26f0724 and c06c061 agree to the printed digit | |
| House edge | chart flat −0.660 at 80%, against the Wizard of Odds calculator: to be entered with Montreal's rules exactly, the inputs recorded, a dated snapshot kept | not stated until done |
| Test suite | count and result read from the JUnit XML at the site tag (last recorded: 422 tests, a9d1d02; 508 `@Test` annotations now) | |
| Second program (simulation) | agreement rule committed before it runs; result as in page section 8 | dealing, play, payoffs and bets of the exported strategies / the fitting, the bootstrap intervals, the catalog's tags, and the rules document both programs share (PLAN_COMPUTE.md 1.9) |
| One look at the main file | search tools refuse the main file (ba2884c); twin seeds collide with none of the main file's; registration committed before the look; the ledger written by the tools | these are guards in code the author controls |

"22 of 22 planted bugs caught in stage 2a" is not in any commit message or design file
found so far. It goes on the page only if `EvidenceCheck` finds its source.

The page also says, in plain sentences under the table:
- Some method choices were made while looking at the main file: how play groups grow and
  which standard error sets the run size were tuned on its first 1,417 to 11,800 shoes.
  None of these choices picks between counts.
- The reviewers were AI agents from the same model families that wrote the code, so their
  independence is limited. No human expert has reviewed the work.
- Bugs found and fixed are part of the evidence. `verification/reviews.tsv` lists each
  review round with what it found: the comparison's design review (b046209), the scorer
  review, the split speed-up review, the full-play plan and code reviews, the ceiling
  design review (which found the headline claim "could not be computed as written",
  9adae30), and the August–September fixes to the old simulator.

### 3.6 Finding the figures: for readers, search engines and language models

- **Static HTML first.** Every table is in the page as rendered HTML. JavaScript only
  sorts, filters and switches views, because many crawlers do not run it.
- **JSON-LD `Dataset`** in the page head: `name`, `description` (the opening answer with
  its line of conditions, as generated: per round and per amount bet; 80% named as the
  most favourable of three cut-card places, with the 70% figure; the same bet schedule,
  average 1.5 units; index plays fitted for this game, not the published lists; the ace
  side count; a counter who makes no mistakes), `creator` (Justin), `version`, `license`,
  `isBasedOn` (a `SoftwareSourceCode` with the repository and commit), `distribution`
  (one `DataDownload` per file, with `contentUrl`, `contentSize`, `encodingFormat` and
  `sha256`), `measurementTechnique`, `variableMeasured`, and `citation` for the
  literature. The `sha256` property and `DataDownload` are schema.org core types (checked
  8 October). Every URL in it is https, which works only once the site's HTTPS does
  (2.1).
- **Plain data at stable URLs:** `/card-counting/data/results.json` and `results.csv`, with
  a `<link rel="alternate" type="application/json">` in the head. `results.json` opens with
  an `_about` block giving the unit (per round, in minimum bets, and how to convert to per
  amount bet), the rules, what "first decisions" and "full play" mean, that plays are
  fitted for this game rather than taken from published index lists, what "same schedule"
  means, which rows carry a side count and where its rule comes from, and the version.
- **A claims list** on the page: each headline claim as one sentence with its interval, its
  conditions (rules; penetration; spread as the same schedule with its average bet; which
  decisions use the count, played from fitted index tables; side count), its anchor
  (`#c1`, `#c2`, …), its key in `results.json`, the checks that bear on it, and its
  replication status. The headline sentences link to their entries.
- **`/llms.txt`** at the site root: a short markdown index pointing to the page, the data
  files and the manifest. llmstxt.org is a proposal that few crawlers read yet; it costs
  one small file. It describes; it gives no instructions.
- **In the code repo:** a README, a `CITATION.cff`, and a licence once Justin picks one.
  Without a licence, people and data indexers who care about reuse may hold back.
- **A dated corrections list** on the page, each entry with the version it changed.

### 3.7 Timestamps and signatures, and what each proves

These are optional, and each needs Justin's yes.
- **OpenTimestamps.** Only a hash leaves the machine; the proof shows the file existed no
  later than a given Bitcoin block, and only once that block's transaction has confirmed,
  usually hours after the stamp (recalled). A stamp cannot show what came before it. So a
  stamp of `custom-counts.json` would show it existed by time T and has not changed since,
  not that it preceded the look: the main file has existed since September, report dates
  come from this machine's clock, and nothing can rule out an earlier unrecorded scoring
  of these tags (PLAN_COMPUTE.md 4.1 says the same). The window in which a stamp could
  have predated the look closed when step 7 started on 9 October; step 7 alone takes
  about 3 to 4 hours, so its report exists before any stamp made now could confirm. That
  the registration came before the look rests on the repo's history and the ledger, and
  the page says so. The stamp that does something useful is one of `manifest.json` at
  release: it makes a later silent change to any listed file detectable. (Details recalled
  from opentimestamps.org, to be confirmed before use; installing its client needs
  Justin's yes.)
- **Signed tags.** Justin has an SSH key, but git signing is not set up and the key is not
  registered on GitHub for signing. A signature proves the key's holder made the tag; it
  does not prove when, or that the code is right. It needs a git setting and a GitHub
  account setting, both his. The manifest pins full commit IDs either way.
- **Software Heritage** ("save code now", recalled) keeps an independent copy of the
  repository whose IDs match git's, which guards against history being rewritten later.
- **Zenodo** (recalled, to check) gives a DOI and fixed file versions; it needs Justin to
  grant it access to his GitHub account.

The page states only the ones that were actually done, each with what it proves.

## 4. How AI was used: the page's statement

Justin chose to say this plainly. The page's section 11, in his voice:

> I started this repository in May 2022 with a Monte Carlo simulator I wrote myself. Since
> August 2026, most of the code, including the exact engine, the scorer, the count search
> and the second program, has been written by AI coding agents ({tools}) working from my
> questions and design decisions. I chose what to compute and read the designs and the
> results. AI agents ran the tests, looked up the published figures and compared them
> with mine. I did not write most of the Java myself. The reviews in section 10 were also
> done by AI agents, from the same families of models, so they are less independent than
> a human expert's review would be, and no human expert has reviewed this work. The design
> notes and this page were drafted with the same tools and edited by me. Weigh the checks
> with all of that in mind.

`{tools}` names only tools the history shows. On 9 October, of the 213 commits since 1
August on all refs, 184 carry a `Co-Authored-By: Claude …` trailer; the other 29 are 27
merges and 2 stash commits; no commit message and no design file mentions Codex. So by
default it reads "Claude Code, from Anthropic". Codex is named, with what it did, only
if Justin says it worked on this repo (question 5). The sentence on who ran the tests and
compared the figures is likewise his to confirm or correct: the design files and
LITERATURE.md record AI agents doing both.

Under the statement, figures computed by the generator from `git log` at the site tag: the
number of commits since 28 August 2026, with merges shown separately, and the number
carrying an AI co-author trailer, per tool. The first such trailer in the history is on 28
August 2026; the first commit is 22 May 2022, by Justin. Justin reads and edits this
statement before anything is published, the 2022 sentence included (the history shows
whose commits they were, not how the code was written).

## 5. Publishing

Nothing is pushed, uploaded, tagged on GitHub or submitted anywhere without Justin's
explicit yes on an exact list. `SiteManifest --push-list` prints that list: every branch
and tag with its commit, every release file with its size and SHA-256, every website
file, and anything that would leave the machine for a third party (a timestamp, an
archive request). He answers that list, not a summary of it.

**What would go where.**

| Where | What | Size |
|---|---|---|
| github.com/jbondurant/CardCountingEdgeCalculator, branches and tags | the commits the page cites (today only 26f0724, 077d971 and 552dc6c are public), `site/`, `verification/`, README, CITATION.cff, a licence | small |
| the same repo, one Release (each file must be under 2 GiB; no total limit, per GitHub's docs, checked 8 October) | `montreal-80.states` | 1,340,719,767 bytes |
| | `full-c06c061/results.ser` | 430,694,877 bytes |
| | `twin-80.states`, if Justin says so (section 6) | 1,341,183,279 bytes |
| | the ceiling run's saved results, and the new computations' outputs | pending, by size |
| | `manifest.json`, `SHA256SUMS`, `results.json`, `results.csv` | small |
| not published | `trees.bin` (14,877,661,504 bytes) and the frozen class directories | hash and tree hash only; regenerable |
| jbondurant.github.io, on the branch Pages publishes | `card-counting/` (about 1 MB), `/llms.txt`, an empty `.nojekyll`, the nav and blurb edits to `index.html`, one README line | small |
| Justin's GitHub account, before the website push | Pages settings: which branch publishes (and whether `add-custom-domain` is merged), the custom domain's certificate, Enforce HTTPS (2.1) | his actions, not on the push list |

Without the twin the Release is about 1.8 GB; with it, about 3.1 GB. Nothing on the
website is pushed while https://justinbondurantdavid.com fails its certificate check,
unless Justin decides otherwise knowing that (question 6).

**How the upload is done.**
- Each release file is uploaded, downloaded again and re-hashed against the manifest
  before its link goes into `site/releases.json`. Local copies are kept until then.
- `results.ser` is Java serialisation: tied to these classes, and unsafe to open from a
  source you do not trust. The page says to read it only after checking its hash, and
  offers `results.json` as the main format.
- Some artifacts contain absolute paths such as `/Users/justinbondurant/…` (in reports and
  logs). The default is to publish them unchanged, because editing them would change their
  hashes; the push list names every file that contains one.
- A release file can be replaced later by the repo's owner. That is why the hashes are on
  the page and in the tags, and why a timestamp on the manifest helps.
- The website commit message names the generator commit and the manifest hash.

## 6. Questions for Justin

Each has a default; the work goes ahead on the defaults unless he says otherwise, except
that nothing leaves the machine without his yes on the push list (section 5).

1. **Publish the twin file too (1.34 GB more)?** Default: yes. The searched count's claim
   that it was chosen on other shoes can only be checked with the file it was chosen on.
   Regenerating it from its seed takes 3.2 hours, but its build is not yet known, so a
   regenerated copy may not match its hash.
2. **Licences.** Default: MIT for the code; CC BY 4.0 for the data and the page's text.
   Until he picks, the page says "all rights reserved", and the repo stays unlicensed.
3. **Timestamps and signing (section 3.7).** A stamp of the registration made now would
   show only that `custom-counts.json` existed by the stamp's time and has not changed
   since, not that it came before step 7's look (PLAN_COMPUTE.md 4.1), so nothing about
   it is urgent. Default, as PLAN_COMPUTE.md decision 2: no outside record of the
   registration, and the page says the order rests on the repo's history and the ledger.
   At release: an OpenTimestamps proof of `manifest.json`, which makes later changes
   detectable; no signed tags; Software Heritage; no Zenodo. Installing the timestamp
   client and submitting anything to a third party each need his yes.
4. **Where do Montreal's dealers put the cut card?** If he knows, from his own play or a
   source, the answer says so and names which of 70, 75 and 80% is closest. Default: the
   answer says "I have not measured where Montreal's dealers put the cut card."
5. **Did Codex work on this repository?** The history shows Claude Code only (section 4).
   Default: the AI statement names Claude Code alone. If Codex did work here, he says
   what it did, and the page says that. He also confirms or corrects "AI agents ran the
   tests, looked up the published figures and compared them with mine".
6. **The website's HTTPS (2.1).** In the repo's Pages settings: which branch publishes,
   whether `add-custom-domain` should be merged, then the certificate for the custom
   domain and Enforce HTTPS. These are his account actions. Default: nothing is pushed to
   the website until https works.

Settled here without asking him: the unit shown in money is $25, the minimum at the table
whose rules the engine uses (`getMtlCasino25MinBlackjackParams`), with units as the main
measure and money in the answer only per 100 rounds; rounds per hour offered in section 7
as 50, 80 and 100, the three PLAN_COMPUTE.md 2.3 computes, labelled an assumption; the
colour tokens read from `index.html` rather than moved into a shared file; "22 of 22"
printed only if its source is found; a full-play level widened by its count's difference
ratio (2.4).

## 7. What could go wrong

- **A twin-file figure in a headline.** The search file's figures (F1 +0.391, F2 +0.419,
  the bound +0.528 at 80%, +0.281 at 75%, +0.079 at 70%) are easy to quote by mistake.
  The `data-file="search"` rule in 2.4 makes the build refuse it.
- **Two numbers for one quantity.** 26f0724, c06c061, the ceiling run and the four new
  computations could disagree; the cross-checks in 2.4 stop the build instead.
- **"Best", or "tied", when the data says otherwise.** Victor APC + ace (+0.4717) and
  Hi-Opt II + ace (+0.4703) differ by 0.0014 and are probably not separable; Victor APC +
  ace and Omega II + ace differ by 0.0114 and probably are. A sentence calling the five
  tied would be as wrong as one naming a single best. Every ranking word, and every "cannot
  separate", is guarded by the pairwise simultaneous intervals of 5a.
- **Unwidened full-play intervals.** The peek says "not yet widened". Five counts take
  ratios the pilot measured for them (Hi-Lo, KO, Red Seven, Omega II, Wong Halves); every
  other count takes the largest the pilot saw (1.07 for differences, 1.80 for gains), and
  the page marks those intervals as using a conservative default, not their own ratio.
  A level takes its count's difference ratio (2.4).
- **A headline figure quoted without its conditions.** "Hi-Lo wins 0.36% at Montreal"
  would be read as what a player with a published index list can expect, at whatever
  penetration Montreal deals. The answer, the summary for quoting, the claims list, the
  JSON-LD and `results.json`'s `_about` all carry the conditions (section 1, item 0), and
  the cold read checks each.
- **Units mixed.** Per round in minimum bets is about 1.5 times per amount bet at 1-8.
  The answer gives both, and the chart's house edge is labelled per first bet of a round.
- **The site served over plain HTTP.** Its certificate fails today (2.1); the push waits
  for it.
- **The spot check is not bit-identical.** Then the page says "reproduces within X" and
  depth 3 compares within a tolerance (`StateFileCompare`) instead of with `cmp`.
- **Segment 1's or the twin's build cannot be established.** The manifest marks it
  "inferred", and the spot check on their first shoes decides.
- **A reader or a model takes a hash as proof the code is right, or as proof of which
  commit made a file.** The hash box and the claims list say otherwise, next to the
  hashes: a hash proves the download matches the listing; the rest is my statement until
  reproduced.
- **Literature misquoted, or a page changes.** Only records whose status is CHECKED or
  READ are stated as published; each has a URL, an access date and an archived snapshot
  or the hash of the cached copy. The Wizard of Odds
  calculator is interactive, so its inputs are recorded too.
- **The page check too strict.** Allowed digit strings (years, rule constants) live in
  committed code, never edited per run.
- **Phone layout.** Long catalog names break narrow tables; short names and 320-unit
  panels address it, and the screenshot pass checks it.
- **A new computation is late.** Its section prints "Not yet computed", and the rest of
  the page can still ship if Justin wants it to.

## 8. Order of work

1. **While steps 7–8 run** (nothing heavy on the machine): write `SiteExport`, `SiteBuild`,
   `SiteSvg`, `SiteManifest`, `StateSpotCheck`, `EvidenceCheck` and their tests against
   small fixtures; write the template's prose; re-create the planted bugs as patches in
   `verification/planted/` with `planted.sh` (run later, single-threaded); verify
   `literature.json` entries one by one; enter the Wizard of Odds figure and keep the
   record. Ask Justin questions 4 to 6; question 6 (HTTPS) can be settled in his account
   at any time before the website push.
2. **After steps 7–8:** export, build, run the spot check, the depth-1 rescore, the suite
   and ExactVsBruteForceTest at the site tag, and `planted.sh`; hash everything (trees.bin
   first); check the custom-count class directories rebuild.
3. **As each new computation lands:** fill its section; the build fails until its guards
   pass.
4. **Before publishing:** the two cold reads, the browser pass, Justin's own read, then the
   push list and his yes. After the website push: the browser pass and
   `SiteManifest --check-deployed` on the live https URLs.
