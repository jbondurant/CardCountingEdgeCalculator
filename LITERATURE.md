# How these results compare with published work

This file is the sourced comparison between this repository's results and what
others have published about counting cards at blackjack. It is the input for the
website's "What others found" section, and the plan for the code that will
generate that section. It is written for Justin and for whoever (person or agent)
builds the page. Nothing in it is a page figure yet: the page takes its numbers
only from committed code (`src/LiteratureCheck.java`, planned in section 1),
never from this file.

Written by an AI agent (Claude Code) on 9 October 2026, from the literature
design of 8 October 2026 and a second round of checks. Not committed by its
writer; a later stage commits it.

## How to read this file

**Who read the sources.** Every reading and check in this file was done by an AI
agent (Claude Code, in the 8 and 9 October literature passes), not by Justin.
Nothing records Justin reading any of these sources, so each counts as unread by
him until he says otherwise (a question for him). The page is in
Justin's first person, so nothing here may be carried onto it as "I read" or "I
checked"; section 1 says how the page words it.

**Status of each published source.**

- **CHECKED 9 Oct** — an AI agent fetched the source again on 9 October 2026
  (live, or through an existing Internet Archive snapshot) and the figure quoted
  here matched.
- **READ 8 Oct** — an AI agent fetched and read the source text on 8 October.
  Where the line says "copy re-read", an AI agent re-read the saved copy on
  9 October; otherwise it was not re-checked.
- **SECONDARY** — known only from a named report of it.
- **NOT READ** — citation details only. Nothing it says is used as a figure.

**Where "ours" comes from.** Every figure marked *ours* is in a committed output
of this repository:

- `runs/report-final-26f0724.md` — first-decision play, all 31 counts, 70/75/80%
  penetration, every spread, 1,000 bootstrap resamples;
- `runs/full-index/peek-c06c061-final.txt` — full play (count-based decisions on
  every hand), 80%, 1-8; its standard errors are "not yet widened";
- `runs/custom-count/calibration-b99ccbf.md` — the theory pass: the full 8-deck
  shoe's exact value and its effects of removal (computed from the full shoe, so
  no sample is involved), and the linear ceiling R on the twin file
  `runs/twin-80.states`;
- `runs/custom-count/search-0b85be2.md` — the custom-count search, also on the
  twin file, not the main one.

Two kinds of figure are not committed output and are labelled **scratch**: a
cell-by-cell comparison with one Wizard of Odds table (section 2B) and a small
infinite-deck calculation (section 2B). Scratch figures are leads. They go on the
page only after committed code reproduces them.

**Units.** Ours are percent of one betting unit per round dealt, unless a line
says otherwise. "1-8" means the bet schedule that spreads from 1 to 8 units by
count, the same schedule for every count, so the average bet is the same for
all of them (1.503 units at 80% penetration). Published figures come in other
units: percent of initial bets, Kelly-ramp SCOREs, or correlations. Section 4
lists the conversions that must happen before anything sits side by side.

**Conditions.** Our game is 8 decks, the dealer hits soft 17 (H17), double on
any two cards, double after splitting (DAS), split to four hands, split aces
once with one card each, the dealer peeks for blackjack, no surrender. Most
published work is 6 decks, the dealer stands on soft 17 (S17), often with late
surrender. So "agrees" in this file means agrees in direction, unless the
conditions match.

## What the second round of checks found (9 October)

1. **The four most load-bearing citations check out.** The Wizard of Odds
   8-deck H17 table (split tens against an ace −0.449008, standing +0.600677),
   Wattenberger's *Modern Blackjack* p.106 (8 decks, H17, DAS: −.664), QFIT's
   counting-system table (Hi-Lo BC .97, PE .51, IC .76) and the Vidámi,
   Szilágyi and Iclanzan paper (method and Table 2, from the saved preprint;
   citation through Crossref) all read as the 8 October design said. Crossref
   also confirms the two theory papers' DOIs.
2. **The split-tens disagreement probably has a plain cause** (section 2B,
   scratch). The Wizard of Odds' split values for tens and fives behave as if the
   player resplits every new pair up to four hands. Our engine follows the chart
   after the split, so it stands on 20. In a scratch infinite-deck calculation,
   forced resplitting gives −0.383 for tens against an ace (their table −0.449)
   and never resplitting gives +0.066 (ours +0.057). Not yet settled; see 2B.
3. **Several of the "independent" sources share an author.** *Modern Blackjack*,
   *Blackjack in Color* and the CVCX simulator (behind the Wizard of Odds'
   index-play figures) are all Norm Wattenberger's work. QFIT is Wattenberger's
   site. Much of the published PE/BC/IC rests on Peter Griffin's effects of
   removal. Agreement across these sources is less independent than it looks.
4. **Two citation details in the design were wrong or unconfirmed.** Snackjack's
   page range (49–82) is not on the journal's own record; the record gives
   volume 24, issue 1, article 4, doi 10.9741/2327-8455.1425. The Wizard of Odds
   effects-of-removal table assumes "split anything only once", unlike our game.
5. **Snackjack's point about effects of removal is narrower than the design
   put it** (section 2C).

## 1. What gets built

Three pieces, all in this repository except the page itself.

**`literature/claims.json`** (new). One record per published figure:

- the citation: author, title, year, venue, ISBN or DOI, URL, and an Internet
  Archive snapshot if one already exists (see "Snapshots" below);
- the locator: page, table, row;
- the status: CHECKED, READ, SECONDARY or NOT READ, with the date;
- `checked_by`: who did that reading, for example "AI agent (Claude Code),
  2026-10-09";
- `read_by_justin`: false unless Justin says he has read the source, with the
  date he says;
- the conditions: decks, S17 or H17, DAS, surrender, penetration, spread, count,
  index set, betting rule, and the **unit**;
- the value, exactly as printed.

These are cited inputs typed in by hand, and the page labels them "published",
never "ours". Each record also says which of our rows it is compared with.

**Snapshots.** A record cites an Internet Archive snapshot only if one already
exists; looking one up is a read, like fetching the page. Asking the Archive to
make a new one (Save Page Now) sends a request to a third party, so it is on the
push list (PLAN_PAGE.md section 5) and waits for Justin's yes like everything
else there. Until then a source with no snapshot carries "no snapshot yet" and
the SHA-256 and date of the cached copy. Several Wizard of Odds pages were
updated on 3 August 2026 and may have no snapshot of the version read here;
`LiteratureCheck` lists every URL that needs a new snapshot, and that list goes
into `SiteManifest --push-list`.

**`src/LiteratureCheck.java`** (new). A committed tool that writes
`runs/literature-<sha>.md` and the page's `literature.json`. It does six things:

- **(a)** Values the full 8-deck shoe once with StateValuer (about 1 s on one
  thread; COUNTING_COMPARISON.md section 7 measured 0.99 to 1.04 s per state)
  and compares Stand, Hit, Double and Split for every hand and up-card with the
  Wizard of Odds 8-deck H17 table.
- **(b)** Values every split twice, once following the chart after the split
  (as the engine does now) and once resplitting every new pair up to four hands,
  so section 2B's explanation is tested by committed code instead of a scratch
  script.
- **(c)** Reads the Wizard of Odds calculator figure from the page's own
  JavaScript (`edgeTable` plus the 8-deck adjustments), from a cached copy.
- **(d)** Sets our BC, IC and PE beside the published values in `claims.json`,
  and prints BC divided by R, the linear ceiling (the highest betting correlation
  any linear count can reach on the same states). R for the main file comes from
  step 7: CeilingReport runs CountRegression's betting regression on the states
  it scores, inside the registered look, prints R in
  `runs/report-ceiling-97bf834.md` ("The betting correlation and its ceiling")
  and saves it in `runs/report-ceiling-97bf834.ser`. LiteratureCheck reads R
  from that save with `CeilingReport.readSaved` and never runs the regression on
  the main file itself: `CountRegression.main` refuses the main file
  (`CustomCounts.requireTwin`), MainFileLookTest keeps it that way, and no guard
  is loosened for the page. LiteratureCheck checks that the state count printed
  beside R is the 60,743 states behind report-final's BC. The twin's R (0.98857,
  `calibration-b99ccbf.md`) is never used for BC/R; if step 7's save has no R,
  the page shows BC without BC/R.
- **(e)** Compares our effects of removal with the Wizard of Odds 6-deck ones
  after scaling by the deck-size factor, and prints the angle between the two
  ten-number vectors.
- **(f)** Prints the angle between F1 and the two Vidámi vectors, using
  SearchReport's own routine.

Third-party pages go into a gitignored cache. Only their SHA-256 hashes, URLs
and fetch dates are committed (copyright). Tests: a hand-written fixture for the
table parser, and a planted change of 1e-4 in one cached cell that the report
must flag.

**The page section "What others found".** One table (claim, conditions, theirs,
ours, verdict), short prose by topic, and the source list with each status shown.
The status is worded from `checked_by` and `read_by_justin`, never as Justin's
own reading: "checked against the source by an AI agent on 9 Oct 2026", "read
by an AI agent on 8 Oct 2026", "known only through [named source]", "not read".
The section's opening says once that the literature was read and checked by AI
agents, and names any source Justin says he has read himself. Three figures:

- house-edge points with intervals: Wizard of Odds with a continuous shuffler,
  Wizard of Odds with a cut card, Wattenberger, our exact top-of-shoe value, and
  ours at 70, 75 and 80%;
- ours against published IC and BC/R, around the 45-degree line;
- effects of removal by rank, ours against the Wizard of Odds.

The prose is a template and the generator fills in every number. A sentence
with a number in it never exists only in the HTML.

## 2. The comparison, topic by topic

Each topic gives the sources, what they say, what we found, whether the two
agree, and why they might differ.

### 2A. The house edge — agrees

Rules for every figure in this subsection: 8 decks, H17, DAS, split to four
hands, aces split once, the dealer peeks, no surrender.

**Sources.**

- Wizard of Odds, "Blackjack House Edge Calculator",
  <https://wizardofodds.com/games/blackjack/calculator/>. READ 8 Oct (copy
  re-read 9 Oct). The figure comes from the page's code, not its display: the
  `edgeTable` entry for these rules is 0.644928 (composition-dependent play,
  shuffled after every hand). For 8 decks the code then adds 0.0159 for basic
  strategy with a cut card, giving **0.66083%**, and subtracts 0.014 for a
  continuous shuffler, giving **0.64683%**. Which array index means which rule
  was read from the code by the 8 October pass; LiteratureCheck must re-derive
  it rather than trust this line.
- Wizard of Odds, "Total Dependent vs. Composition Dependent Basic Strategy"
  (appendix 15), <https://wizardofodds.com/games/blackjack/composition-dependent-benefit/>,
  last updated 3 Aug 2026. READ 8 Oct (copy re-read 9 Oct). Row "8 decks, dealer
  does not stand on soft 17, DAS": total-dependent 0.6468%,
  composition-dependent 0.6449%, difference **0.0019%**. The page says surrender
  is not allowed and aces may not be resplit in every row.
- Wizard of Odds, "Cut Card Effect" (appendix 10),
  <https://wizardofodds.com/games/blackjack/cut-card-effect/>, last updated
  3 Aug 2026. READ 8 Oct (copy re-read 9 Oct). At 8 decks the cut card costs the
  player **0.014%**.
- Wizard of Odds, "Blackjack expected returns for eight decks, dealer hits on
  soft 17" (appendix 9), <https://wizardofodds.com/games/blackjack/appendix/9/8dh17r4/>,
  last updated 3 Aug 2026. CHECKED 9 Oct. Its own expected return with DAS:
  −0.006444, that is 0.6444%, composition-dependent.
- Norm Wattenberger, *Modern Blackjack* (online edition, 2009), p.106, "Basic
  Strategy Advantage vs. FELT True Count Zero", read through
  <https://web.archive.org/web/20150111033408/http://www.qfit.com/book/ModernBlackjackPage106.htm>.
  CHECKED 9 Oct. Eight decks, H17, DAS: **−.664**, a simulated cut-card game.

**Ours.**

- The full 8-deck shoe valued exactly under the chart, before insurance:
  **−0.64645%** (`calibration-b99ccbf.md`, the theory pass).
- The chart, flat bets, over the sampled states (so with a cut card):
  −0.661 [−0.672, −0.651] at 70%, −0.659 [−0.669, −0.648] at 75%,
  −0.660 [−0.671, −0.649] at 80% (`report-final-26f0724.md`, reference rows).

**Verdict: agrees.**

- Off the top of the shoe, exact against exact: ours 0.64645% against the
  Wizard of Odds' total-dependent 0.6468%, a gap of about 0.0004%. Their
  composition-dependent figure (0.6449%) is lower than ours, as it should be,
  since the chart we play is total-dependent.
- With a cut card: ours 0.660 [0.649, 0.671] at 80%, against 0.6608 (Wizard of
  Odds calculator) and 0.664 (Wattenberger). Both sit inside our interval.
- The cost of the cut card: ours (sampled states less the exact top-of-shoe
  value) is about 0.013, with an interval of about ±0.011, consistent with the
  Wizard of Odds' 0.014 but too wide to confirm it.

**Why they might differ.** The 0.0004% gap is not explained. Candidates, none
checked: the table's note that its split values ignore the other split hands'
cards; the resplitting difference in 2B; rounding in the published 0.6468. The
page should state the gap and say it is unexplained. The penetration behind the
Wizard of Odds' 0.014 cut-card figure should go into `claims.json`; this pass
did not record it.

### 2B. The value of each hand against the Wizard of Odds table — agrees, except split values that probably mean something different

**Source.** Wizard of Odds, "Blackjack expected returns for eight decks, dealer
hits on soft 17" (appendix 9),
<https://wizardofodds.com/games/blackjack/appendix/9/8dh17r4/>, last updated
3 Aug 2026. CHECKED 9 Oct. Rules as in 2A; values conditional on no dealer
blackjack. The page says that its split values take account of how many hands
have been split so far, but not of the other cards drawn to those hands. The
rest of the table is composition-dependent: later plays can depend
on the exact cards.

**Ours.** The engine's committed full-shoe values, `test/ExactRoundEightDeckTest.tsv`
(shoe 0 is the full 8-deck shoe; the chart is played after the first move).

**Scratch comparison** (8 October, split rows re-run 9 October):

| Move | Cells | Largest difference | Note |
|---|---|---|---|
| Stand | 110 | 5.6e-7 | about the table's printed precision |
| Double | 110 | 5.3e-7 | about the table's printed precision |
| Hit | 110 | 4.8e-4 (11 = 4,7 against a ten); median 2.8e-7 | expected: their later plays depend on the cards, ours follow the chart |
| Split aces | 10 | under 1e-5 | agrees |
| Split eights | 10 | 5e-4 | agrees within their split shortcut |
| Split 9s, 7s, 3s, 2s, 6s, 4s | 60 | 0.020 to 0.053 (largest per pair) | ours higher where they differ |
| Split fives | 10 | 0.068 to 0.112 | ours higher |
| Split tens | 10 | 0.107 to 0.506 | ours higher |

Of the 100 split cells, 52 agree to within 0.001. In every split cell ours is
higher than theirs or within 0.0006 of it, which is what a difference in how
the hands are played after the split would produce.

**A probable explanation (scratch, not yet a page figure).** The game allows
resplitting to four hands. After splitting tens, a hand that is dealt another
ten is a new pair of tens. Our engine plays the chart after the split, and the
chart stands on 20. The pattern in the table fits a program that resplits every
new pair, up to four hands:

- aces cannot be resplit here, so the two policies agree, and so do the tables;
- the chart always resplits eights, so again the policies agree, and so do the
  tables;
- the chart never resplits tens or fives, so the gap is largest there;
- for the other pairs the chart resplits against some up-cards and not others,
  so the gaps are small.

To test this, a scratch infinite-deck script
(`scratchpad/site-plan/literature-writer/resplit_check.py`, wiped at session
end) valued splitting tens both ways. "Infinite deck" means each card is drawn
with fixed chances, as if the shoe never ran down:

| Dealer shows | Ours, 8 decks, chart after split | Infinite deck, never resplit | Wizard of Odds table | Infinite deck, always resplit to 4 | Standing on 20 (their table) |
|---|---|---|---|---|---|
| Ace | +0.057 | +0.066 | −0.449 | −0.383 | +0.601 |
| 6 | +0.558 | +0.565 | +0.451 | +0.470 | +0.677 |
| 9 | +0.227 | +0.233 | −0.269 | −0.207 | +0.757 |
| 10 | +0.052 | +0.051 | −0.420 | −0.371 | +0.558 |

Ours sits within 0.01 of the never-resplit figure. Theirs sits 0.02 to 0.07
below the always-resplit figure. That remaining gap is not explained; it may be
the finite shoe, their split shortcut, or a different resplitting rule.

**Verdict: agrees on stand, double and hit; split values probably answer a
different question.** Splitting tens is never right at a count of zero under
either policy, since standing on 20 is worth more in every row above.

**What must happen before the page says anything.** LiteratureCheck (b) values
every 8-deck split both ways with committed code. If the always-resplit values
land within about 0.001 of the table, the page says the tables agree once the
resplitting rule is matched. If not, the page shows the gap and both checks, and
says plainly that we cannot account for it. The repo's older infinite-deck
solvers (EXPLAINER.md) are a third check if they model splits; this pass did
not look. Either way the page does not say the Wizard of Odds is wrong.

**Why it matters here.** Split values decide the count at which splitting tens
becomes worth it (an "index"). In first-decision play the chart makes every
later decision, so after a count-based split of tens the engine stands on a new
pair of tens. In full play a resplit follows the count's own fit for that pair
(FULL_INDEX_PLAYS.md section 1). The page should say which policy each tens
index assumes.

### 2C. Effects of removal — agree in direction; the size needs care

An **effect of removal** is how much the player's expected value changes when
one card of a given rank is taken out of the shoe. Counts are built on them:
a count's tags try to follow these ten numbers.

**Sources.**

- Wizard of Odds, "Effect of Card Removal in Blackjack",
  <https://wizardofodds.com/games/blackjack/effect-of-removal/>, last updated
  3 Aug 2026. READ 8 Oct (copy re-read 9 Oct). One card removed from a 6-deck
  shoe; double on any two cards, DAS, **split anything only once**, strategy
  adjusted to the changed shoe. Dealer hits soft 17: removing a 5 is worth
  **+0.143%**, removing a ten **−0.097%**, an ace −0.084%. (Dealer stands: +0.141,
  −0.091.)
- Stewart N. Ethier and Jiyeon Lee, "Snackjack: A Toy Model of Blackjack",
  *UNLV Gaming Research & Review Journal* 24(1), article 4 (2020),
  doi [10.9741/2327-8455.1425](https://doi.org/10.9741/2327-8455.1425); preprint
  [arXiv:1906.01220](https://arxiv.org/abs/1906.01220). Preprint READ 8 Oct (copy
  re-read 9 Oct); journal record CHECKED 9 Oct. The convention is to compute
  effects of removal for one deck and scale them by (52−1)/(N−1) for a shoe of
  N cards. The paper shows why this works in the limit, then tests it on its toy
  game: the scaled figures correlate 0.999 with the exact ones. As a stand-in
  for the exact figures they do worse than the paper's six-level count and
  better than its one-level count. So computing them for the game at hand is
  better, but the scaled figures are close.

**Ours.** 8 decks, chart play, measured as a central difference: the full shoe
with four cards of the rank removed, less the full shoe with four added, divided
by eight:
**+0.090%** for a 5 and **−0.066%** for a ten (`calibration-b99ccbf.md`, theory
pass).

**Verdict: agree in direction and in ranking; ours are smaller for the 5 and
the ten.** Scaled from 6 to 8 decks by 311/415, the Wizard of Odds' figures
become about +0.107 (5), −0.073 (ten) and −0.063 (ace); ours are +0.090, −0.066
and −0.063 (arithmetic here, not a page figure). The ace matches; the 5 and the
ten are 9 to 16% smaller.

**Why they might differ.**

- They adjust strategy to each changed shoe; we play the chart. Re-optimising
  can only raise the changed shoe's value, which pushes their figures up: it
  enlarges a positive effect (the 5) and shrinks a negative one (the ten). So it
  may explain part of the gap for the 5, but not for the ten.
- They split only once; we resplit to four hands.
- One-sided (one card out) against central (four out against four in).
- The deck-size scaling itself is an approximation (Snackjack, above).

LiteratureCheck (e) reports the angle between the two ten-number vectors, which
ignores overall size and asks only whether the ranks line up the same way.

**Not used.** Vidámi et al. (2J) quote a list attributed to Thorp's *Beat the
Dealer*: −2.42, 1.75, +2.14, +2.64, +3.58, +2.40, +2.05, +0.43, −0.41, +1.62 (ace
to ten). Its ten entry is positive, where removing tens hurts the player in
every other source, so it is a different quantity or a misprint. Nobody on this
project has checked it against the book, so the page should not use it.

### 2D. BC, PE and IC — IC agrees closely; BC and PE come out lower, for known reasons

These are Peter Griffin's three measures of a count:

- **BC (betting correlation):** how well the count's tags line up with the
  effects of removal, so how well it tells you when to bet more.
- **PE (playing efficiency):** how much of the possible gain from changing play
  the count captures.
- **IC (insurance correlation):** how well the count tracks the share of tens,
  which is all that decides the insurance bet.

**Sources.**

- QFIT (Norm Wattenberger), "Card Counting - Strategies",
  <https://www.qfit.com/card-counting.htm>, read through
  <https://web.archive.org/web/20201208222104/https://www.qfit.com/card-counting.htm>.
  CHECKED 9 Oct. Hi-Lo .97/.51/.76 (BC/PE/IC), Hi-Opt II .91/.67/.91, Zen
  .96/.63/.85, Wong Halves .99/.56/.72, KO .98/.55/.78. The page notes that its
  PE for unbalanced counts is only an estimate, that its PE and BC leave out side
  counts, and that tags of −9 for tens and +4 for every other card would predict
  the insurance bet perfectly.
- BlackjackInfo, "Card Counting Efficiency Calculator",
  <https://www.blackjackinfo.com/card-counting-efficiency-calculator/>. Used by
  the earlier comparison stage (its figures are in `report-final-26f0724.md`,
  e.g. Hi-Lo PE 0.5114, BC 0.9682, IC 0.7601, described there as Griffin's 6-deck
  effects of removal). Not re-checked here.
- The Encyclopedia of Blackjack (blackjackreview.com), "Card Counting System
  Comparisons",
  <https://www.blackjackreview.com/wp/encyclopedia/card-counting-system-comparisons/>.
  Used by the earlier stage; not re-checked here.
- Wattenberger, *Modern Blackjack* p.172 (BC with an ace side count). Used by the
  earlier stage; not re-checked here.

**Ours** (`report-final-26f0724.md`, "Empirical betting and insurance
correlations" and "Flat play, insurance and efficiencies", 80%):

| Count | Ours BC / IC | QFIT BC / IC | Ours PE | QFIT PE |
|---|---|---|---|---|
| Hi-Lo | .957 / .762 | .97 / .76 | .460 | .51 |
| Hi-Opt II | .919 / .909 | .91 / .91 | .616 | .67 |
| Zen | .959 / .850 | .96 / .85 | .579 | .63 |
| Wong Halves | .982 / .725 | .99 / .72 | .526 | .56 |
| KO | .959 / .784 | .98 / .78 | .286 | .55 |

**IC: agrees closely.** Hi-Lo .762 against .76, Hi-Opt II .909 against .91, Zen
.850 against .85, Uston APC .901 against .90 (all QFIT). The theory pass finds
the perfect insurance count at exactly 4 for every non-ten and −9 for tens
(tags +0.5847 and −1.3156, `calibration-b99ccbf.md`), as QFIT states. The
exceptions are the KISS counts (from Fred Renzey's *Blackjack Bluebook II*, NOT
READ), whose tags depend on a card's colour or face: KISS I .744 against .81
(blackjackreview). A likely reason, not checked: the published figure treats a
face-dependent tag as its average (a ten as −0.75), while a real KISS I count
gives the 10-spot nothing and so does not see one ten in four.

**BC: ours is lower** (Hi-Lo .957 against .97). The two numbers measure
different things. The published BC correlates a count's tags with the linear
effects of removal. Ours correlates the count's true count with each sampled
state's actual advantage under the chart. No linear count follows that
advantage perfectly: the best possible correlation, R, is 0.98857 on the twin
file. Dividing by R puts ours on the published scale. As a rough illustration
(arithmetic here, R from the twin file): Hi-Lo .957/.989 = .968 against .97,
Zen .970 against .96, Wong Halves .993 against .99, Hi-Opt II .930 against .91,
KO .970 against .98. The page shows BC/R beside the published BC, with the
main file's R as step 7 saved it (section 1, (d)).

**PE: ours is lower, and ranks about the same** (Hi-Lo .460 against .51,
Hi-Opt II .616 against .67). Reasons:

- ours covers the first decision only (later cards follow the chart), at 8
  decks, with flat bets;
- our true count is rounded to the nearest whole number before it picks a play;
- our baseline is the exact best first move in each state, not a linear model;
- for unbalanced counts such as KO, QFIT says its own PE is only an estimate,
  and ours (.286 against .55) uses KO's running count, as KO is played.

**Verdict: IC agrees; BC agrees once our ceiling is allowed for; PE is lower for
known reasons and keeps the order.**

### 2E. Ranking the counts — agree on direction; the size of the gaps does not carry over

| Source | Conditions | What it says | Ours (80%, 1-8, % per round) |
|---|---|---|---|
| QFIT, "KO versus HiLo" | 6 decks, S17, DAS, 1-15, bet ramps tuned per penetration | KO scores 99.5% to 102% of Hi-Lo's SCORE, from 3.63/6 to 5.21/6 dealt | KO less Hi-Lo, first decisions +0.003 [+0.001, +0.006] (70%: +0.009); full play 0.362 against 0.364 |
| *Modern Blackjack* p.102 | 6 decks; FELT with simplified indexes | FELT 7% to 12% above Hi-Lo by SCORE, depending on penetration; REKO about equal to Hi-Lo | RPC (same tags as FELT) less Hi-Lo +0.053; full play 0.424 against 0.364 |
| *Blackjack in Color* 4.4 | 6 decks, S17, DAS, late surrender, Sweet 16 and Fab 4 indexes, 1-16, full Kelly | Zen and Halves gain over Hi-Lo, but not by much | Zen +0.063, Halves +0.054 (first decisions) |
| Snyder, *Beat the 6-Deck Game* (excerpt) | 6 decks | most valid systems land "within one- to two-tenths of a percent of each other" | the mainstream counts lie within about 0.1 of each other |

**Sources.**

- QFIT (Wattenberger), "KO versus HiLo", saved as
  `KO-HiLo-BlackjackComparison.htm` from qfit.com (URL and archive snapshot to be
  recorded in `claims.json`). READ 8 Oct (copy re-read 9 Oct).
- Wattenberger, *Modern Blackjack* (2009), p.102 "Performance Comparisons",
  through the Internet Archive copy of
  `http://www.qfit.com/book/ModernBlackjackPage102.htm`. READ 8 Oct (copy
  re-read 9 Oct).
- Norm Wattenberger, *Blackjack in Color* (2007), section 4.4 "How much do card
  counting strategies matter?", <http://www.blackjackincolor.com/penetration6.htm>.
  READ 8 Oct.
- Arnold Snyder, *Beat the 6-Deck Game*, excerpt published by Las Vegas Advisor,
  <https://www.lasvegasadvisor.com/gambling-with-an-edge/an-excerpt-from-arnold-snyders-beat-the-6-deck-game/>.
  READ 8 Oct (copy re-read 9 Oct).
- Also read, little weight: a commercial benchmark (BlackjackPilot, 18 June 2026)
  of 8 decks, H17, DAS, 75%, 1-10, with no per-system table for that game. READ
  8 Oct; URL to be recorded.

**On SCORE.** *Modern Blackjack* p.518 (READ 8 Oct) defines SCORE as DI squared,
where DI is Don Schlesinger's Desirability Index. DI is commonly described as
expected value divided by standard deviation, times 100 (SECONDARY: not checked
in Schlesinger). If that holds, then at equal standard deviation a SCORE ratio of
1.07 to 1.12 means an expected-value ratio of only about 1.03 to 1.06 (the square
root). SCORE also assumes each count bets its own optimal (Kelly) ramp; ours all
bet one fixed schedule.

**Why our gaps look bigger in percentage terms.** Hi-Lo's expected value in this
game is small (+0.294 at 80%, 1-8, first decisions), because 8 decks with H17 and
no surrender is a poor game. A gap of 0.05 is then a large share. And our fitted
play lets each count's tags earn their full playing value, which helps the
level-two counts.

**Verdict: agree on direction.** The page compares absolute differences in
expected value per round, and never treats our fixed-schedule figure as a SCORE.

### 2F. Index plays — not like for like yet

An **index play** is a departure from basic strategy when the count passes a set
number, the "index" (for example, stand on 16 against a ten at a true count of 0
or more).

**Sources.**

- Wizard of Odds, "Introduction to the High-Low Card Counting Strategy",
  <https://wizardofodds.com/games/blackjack/card-counting/high-low/>. READ 8 Oct
  (copy re-read 9 Oct). Republishes, with permission, Don Schlesinger's
  "Illustrious 18" and "Fab 4" index lists from *Blackjack Attack*, and says
  that knowing only these gives 80% to 85% of the value of knowing every index,
  based on a six-deck game. Its CVCX simulation (CVCX is Wattenberger's software;
  6 decks, S17, DAS, late surrender, aces resplit; percent of initial bets):
  4.5/6 dealt at 1-10, Illustrious 18 and Fab 4 **0.587%** against all indexes
  **0.608%**; 5/6 dealt at 1-15, **1.147%** against **1.182%**.
- The Encyclopedia of Blackjack, "I is for the Illustrious 18",
  <https://www.blackjackreview.com/wp/encyclopedia/i/>. READ 8 Oct. Places the
  original study in a 4-deck Hi-Lo game, with insurance worth over 30% of the
  gain, and cites *Blackjack Attack* (1997) p.70.
- Don Schlesinger, "Attacking the Shoe", *Blackjack Forum* Vol. VI, September
  1986: the first publication of the Illustrious 18. Listed in the Encyclopedia's
  Blackjack Forum index, <https://www.blackjackreview.com/wp/encyclopedia/blackjack-forum/>
  (READ 8 Oct). The article itself is NOT READ.
- Wattenberger, *Blackjack in Color* 4.2 "How much do card counting indexes
  matter?", <http://www.blackjackincolor.com/penetration4.htm>. READ 8 Oct. Extra
  indexes add little at ordinary penetration and more at deep penetration.

**Ours** splits the gain from play differently: the first decision plus
insurance, against every hand including later cards and split hands. Hi-Lo at
80%, 1-8: betting only **+0.134**, first decisions **+0.294**
(`report-final-26f0724.md`), full play **+0.364** (`peek-c06c061-final.txt`).
So first-decision play and insurance give about 70% of what full play adds over
betting alone (arithmetic here). The published split is different: a short list
of indexes against every index.

**Verdict: not yet comparable.** To make it comparable, run the Illustrious 18
check that FULL_INDEX_PLAYS.md section 5 designs: read `results.ser` (431 MB;
minutes, an estimate), print Hi-Lo's fitted index for each hand and the
insurance index, and set them beside the published list. Conventions the page
must state:

- H17 changes the index for doubling 11 against an ace;
- there is no surrender here, so the Fab 4 (surrender indexes) do not apply;
- we round the true count to the nearest whole number (ties toward zero) where
  those tables floor or truncate it, so a published index of +1 and our key of
  +1 do not cover the same true counts (*Blackjack in Color* 2.4,
  <http://www.blackjackincolor.com/truecount4.htm>, READ 8 Oct, copy re-read
  9 Oct, explains the methods; 2H shows what this does to a comparison);
- the generator also shows the full-play gain at 70, 75 and 80%, once those runs
  exist.

### 2G. Ace side counts and two-vector counts — agree

**Sources.**

- Wattenberger, *Blackjack in Color* 4.5 "What about ace-neutral strategies and
  ace side counts?", <http://www.blackjackincolor.com/penetration7.htm>. READ
  8 Oct. Hi-Opt II alone gains little over Hi-Lo at normal penetration; most of
  its gain comes from adding an ace side count.
- QFIT (Wattenberger), "Side counts", <https://www.qfit.com/blackjack-side-counts.htm>.
  READ 8 Oct. Gives the betting adjustment for an ace side count that we use, and
  describes counts that keep more than one number.

**Ours** (80%, 1-8, less Hi-Lo):

- Hi-Opt II **+0.026**, Hi-Opt II with aces **+0.090** (first decisions);
  betting only **−0.020** and **+0.053** (`report-final-26f0724.md`);
- full play **0.413** against **0.470** (`peek-c06c061-final.txt`);
- on the twin file, F2 (one tag vector for play, another for bets) splits the
  job the same way: its play vector gives the ace +0.12, close to neutral, and
  its betting vector gives it −0.93, heavy (`search-0b85be2.md`).

**Verdict: agree.** The ace matters for betting, not for play, and a side count
is how a level-two count gets it back.

### 2H. Penetration, rules and the value of each true count — agree, except where the round turns positive, which these figures cannot compare

**Theory.**

- Edward O. Thorp and William E. Walden, "The Fundamental Theorem of Card
  Counting with applications to Trente-et-Quarante and Baccarat",
  *International Journal of Game Theory* 2(1) (1973) 109–119,
  doi [10.1007/BF01737563](https://doi.org/10.1007/BF01737563). Citation CHECKED
  9 Oct (Crossref); content SECONDARY, through Snackjack section 6.
- S. N. Ethier and David A. Levin, "On the fundamental theorem of card counting,
  with application to the game of trente et quarante", *Advances in Applied
  Probability* 37(1) (2005) 90–107,
  doi [10.1239/aap/1113402401](https://doi.org/10.1239/aap/1113402401). Citation
  CHECKED 9 Oct (Crossref); abstract READ 8 Oct.
- The theorem, as Snackjack states it (copy re-read 9 Oct): under a fixed
  strategy, the player's expected value given the cards seen so far keeps the
  same average as the shoe runs down, while its spread grows.

**Ours.** The chart's flat value stays at about −0.66 at every penetration
(−0.661, −0.659, −0.660), while the best first move in each state gains as the
shoe runs deeper (−0.586, −0.567, −0.550 at 70, 75, 80%;
`report-final-26f0724.md`, reference rows). Agrees.

**Penetration** (how much of the shoe is dealt before the shuffle).

- Wattenberger, *Blackjack in Color* 4.1 "How much does penetration matter?",
  <http://www.blackjackincolor.com/penetration3.htm>. READ 8 Oct. For 6-deck
  Hi-Lo, SCORE is about seven times higher with half a deck cut off than with
  two and a half.
- Snyder's excerpt (2E) calls penetration a huge difference to the win rate.
  READ 8 Oct.
- *Modern Blackjack* p.111 (copy re-read 9 Oct): the worse the penetration, the
  wider the spread needed for a decent result.
- **Ours**, Hi-Lo first decisions at 1-8: **−0.076** at 70%, **+0.105** at 75%,
  **+0.294** at 80%. Agrees.

**Rules.** *Blackjack in Color* 4.6 "What effect do rules have?",
<http://www.blackjackincolor.com/penetration8.htm>, READ 8 Oct: an H17 shoe game
is generally not worth playing at 1-12 unless the penetration is excellent.
Ours agrees: at 70%, Hi-Lo loses money even at 1-8.

**Value of each true count.** *Blackjack in Color* 2.2 "How valuable is each true
count?", <http://www.blackjackincolor.com/truecount2.htm>, READ 8 Oct (saved copy
re-read 9 Oct): the rule of thumb of about 0.5% per true count is rough, and the
curve bends upward. Their six-deck game is S17, DAS, late surrender, 4.81 of 6
decks dealt, Hi-Lo with over 100 indexes; its true counts are **truncated** (for
positive counts the same as flooring, *Blackjack in Color* 2.4) with decks left
estimated to the half deck. So their "+1" holds true counts from 1 up to 2, and
the advantage there is positive.
**Ours**, Hi-Lo at 80% with its fitted play and insurance
(`report-final-26f0724.md`, "Per key"): steps of +0.52 to +0.64 per true count
from 0 to +5, bending upward. Our key is the true count **rounded to the
nearest** whole number, ties toward zero, with decks left exact
(COUNTING_COMPARISON.md section 5). So our "+1" holds true counts above 0.5 up to
1.5 (−0.100) and our "+2" those above 1.5 up to 2.5 (+0.433).

**Verdict: agrees on the shape; the turning point cannot be compared from these
numbers.** Our +2 and their +1 overlap (1.5 to 2), so the one-step difference in
where the round turns positive could come from the binning alone, not from the
worse rules. Interpolating between our key means puts our crossing near a true
count of 1.2 (arithmetic here, not a page figure; it treats each key's mean as
sitting at the whole number). Theirs lies somewhere between about 0 and 1.5 (a
negative bin from −1 to 1, a positive one from 1 to 2); their page pins it no
closer. So the page sets the two bins side by side with each side's convention
(rounding, deck estimation) printed next to the numbers, and says that a
later turn from the worse rules is expected but not shown by this comparison.
LiteratureCheck prints our crossing from report-final's per-key means, labelled
as an interpolation; there is no published crossing to set beside it.

### 2I. Spreads, wonging and risk — waiting for the other components

These are for the components still to run (wider spreads, wonging, swings).
They are listed now so those components test against them.

- **Spreads.** Wattenberger, *Modern Blackjack* pp.110–111 "Bet Spreads"
  (Internet Archive copies of `ModernBlackjackPage110.htm` and `...111.htm`).
  READ 8 Oct (copies re-read 9 Oct). Charts SCORE for 6-deck spreads of 1-8,
  1-12, 1-16 and 1-20; each step adds less than the one before, and spread adds
  at every penetration where extra indexes matter mostly at deep ones. Our
  planned 1-12 and 1-16 rows test the same shape.
- **Wonging** (watching the shoe and playing only when the count is good).
  *Modern Blackjack* pp.113–114 "Wonging/Back-Counting". READ 8 Oct (p.113 copy
  re-read 9 Oct). The page credits Stanford Wong, author of *Professional
  Blackjack* (that book NOT READ), with popularising it. The result lies between two
  cases: waiting at the table for the next shoe, or moving straight to another
  table. The wonging component should report expected value per round played
  and per round watched, and state its entry and exit counts.
  *Blackjack in Color*'s "Effect of a Wonger" page (`othereffects1.htm`) was
  saved on 8 Oct but its content was not summarised; read it before use.
- **Risk.**
  - Wizard of Odds, "Variance in Blackjack" (appendix 4),
    <https://wizardofodds.com/games/blackjack/variance/>. READ 8 Oct (copy
    re-read 9 Oct). Basic strategy in a cut-card game, about ten billion hands
    per table. Six decks, H17, DAS, no surrender, no resplitting aces: variance
    **1.346** per hand. Its rule table adds +0.00071 for eight decks, so about
    **1.347** for this game, a standard deviation of about 1.16 units (inference
    from their table, not a figure they print).
  - Wizard of Odds, "Risk of Ruin in blackjack",
    <https://wizardofodds.com/games/blackjack/risk-of-ruin/>. READ 8 Oct (copy
    re-read 9 Oct). Covers only a basic-strategy player with a fixed number of
    hands (6 decks, S17); it says it does not treat counting. Not a check for us.
  - *Modern Blackjack* p.518 (READ 8 Oct) defines Harris's N0, the number of
    rounds after which the expected win equals one standard deviation of the
    result, (SD/EV) squared.
  - *Blackjack in Color* 8.1 "What is my risk of ruin?",
    <http://www.blackjackincolor.com/blackjackrisk1.htm>, saved 8 Oct, not
    summarised.

  The swings component can use the variance figure as a direct check: our flat,
  chart-played rounds should show a variance near 1.347.

### 2J. Searching for the best count — Vidámi, Szilágyi and Iclanzan

Mózes Vidámi, László Szilágyi and David Iclanzan, "Real Valued Card Counting
Strategies for the Game of Blackjack", in *Neural Information Processing
(ICONIP 2020)*, Lecture Notes in Computer Science, Springer, 2020, pp. 63–73,
doi [10.1007/978-3-030-63833-7_6](https://doi.org/10.1007/978-3-030-63833-7_6);
preprint <http://real.mtak.hu/114350/>. Preprint READ 8 Oct (copy re-read
9 Oct); citation CHECKED 9 Oct (Crossref).

- **Their method.** A genetic algorithm (population 100) searched for real-valued
  tags, balanced so the deck sums to zero. Fitness was a simulated win over
  Hi-Lo, on hands drawn afresh every generation ("10e6" hands, as printed), so the
  fitness was noisy. Six decks, bets 1 to 100; the rules and penetration are not
  stated. They ran 150 runs of 50 generations for ace-neutral counts and 150 for
  ace-reckoned ones.
- **How they judged the results.** With BlackjackInfo's PE, BC and IC
  calculator, not with a held-out simulation.
- **What they found.** Their best ace-neutral vector beat every count in their
  Table 1 on PE, IC and their combined measure. On BC the best evolved count
  reached 0.989, below several published counts at 0.99. Their Table 2 gives
  that ace-neutral vector and one ace-reckoned vector.

**Ours** (`search-0b85be2.md`, `calibration-b99ccbf.md`; all on the twin file
`runs/twin-80.states`, 80%, 1-8):

- F1, one tag vector used for both play and bets, found by CMA-ES (an
  evolutionary search method): **+0.391**. The best published tags rescaled and
  played the same way as F1 (Zen's): **+0.369**.
- F2, one vector for play and another for bets: **+0.419**, against Hi-Opt II
  with an ace side count, **+0.398**, both with their counts grouped into the
  same number of steps for betting and play.
- F1's best run started at their ace-reckoned Table 2 vector. Runs started at
  Wong Halves (+0.3911) and at Zen (+0.3910) ended at the same value within
  noise, so the starting point did not decide the result.
- The angle between F1 and their two vectors is for LiteratureCheck (f) to
  print; it is not computed here.
- The held-out score on the main file (COUNT_DESIGN.md steps 7 and 8) has not
  run yet.

**Verdict: same direction; the size cannot be compared.** Both searches find
that tags free of the integer constraint do better than the published counts.
Their figures are correlations on Griffin's effects of removal; ours are
expected value per round on exact values. Their fitness used fresh hands every
generation, which guards against fitting one fixed set of hands, but their final
judgment used the calculator's correlations, not the win rate the search
optimised. Ours is searched on one file and scored once on another.

**Not found.** The 8 and 9 October passes (AI agents) found no published figure
for perfect play on every hand at 8 decks, the quantity of our planned "ceiling"
rows.

## 3. Could not verify (the page says so)

These are cited by others or known by reputation, and NOT READ here. Nothing
from them is used as a figure. Publishers and ISBNs are as the 8 October pass
gave them and were not checked.

- Peter A. Griffin, *The Theory of Blackjack*, 6th ed. (1999), ISBN
  978-0-929712-13-0. The source of the effects of removal behind most
  published BC and PE, and of the measures themselves. Known here only through
  QFIT, BlackjackInfo and Snackjack. (A pirated PDF appeared in search results
  on 8 Oct and was not opened.)
- Don Schlesinger, *Blackjack Attack*, 3rd ed. (Huntington Press, 2018), ISBN
  978-1-944877-24-8; earlier edition RGE, 2005, ISBN 978-0-910575-20-1. Holds the
  SCORE tables and the Illustrious 18 study.
- Don Schlesinger, "Blackjack Attack: The 8-Deck Simulations", *Blackjack Forum*,
  June 1997. The index entry was READ 8 Oct; the article was not. Probably the
  closest published comparison to this game.
- Arnold Snyder, *Beat the 8-Deck Game* (1987; Cardoza, 2005) and *The Blackjack
  Formula* (1980, ISBN 978-0-910575-01-0).
- Stanford Wong, *Professional Blackjack* (1994, ISBN 978-0-935926-21-7).
- Olaf Vancura and Ken Fuchs, *Knock-Out Blackjack* (1998, ISBN
  978-0-929712-31-4). Correction to the project brief: KO is by Vancura and
  Fuchs, not by Wong.
- Edward O. Thorp, *Beat the Dealer* (1966, ISBN 978-0-394-70310-7), and "A
  Favorable Strategy for Twenty-One", *PNAS* 47(1) (1961) 110–112,
  doi [10.1073/pnas.47.1.110](https://doi.org/10.1073/pnas.47.1.110) (citation
  CHECKED 9 Oct, Crossref).
- Roger R. Baldwin, Wilbert E. Cantey, Herbert Maisel and James P. McDermott,
  "The Optimum Strategy in Blackjack", *Journal of the American Statistical
  Association* 51(275) (1956) 429–439,
  doi [10.2307/2281431](https://doi.org/10.2307/2281431) (citation CHECKED 9 Oct,
  Crossref).
- Manson, Barr and Goodnight, *The American Statistician* 29 (1975) 84–88; Nairn,
  arXiv:1909.13710; Werthamer (2018). Citation details as the 8 October pass gave
  them; not checked.
- Fred Renzey, *Blackjack Bluebook II* (source of the KISS counts).

If Justin wants comparisons at the level of SCORE, the page needs *Blackjack
Attack* (2018) and Griffin's book; their tables would go into `claims.json` with
page numbers. Until then the page says "not checked" wherever they would apply.

## 4. What could go wrong

- **Units.** Ours are percent of one unit per round dealt. The Wizard of Odds and
  CVCX report percent of initial bets; SCORE (2E) assumes each count bets its
  own optimal (Kelly) ramp. The generator converts where a
  conversion exists, labels every figure with its unit, and never sets a
  fixed-schedule expected value beside a SCORE.
- **Conditions.** Most prior work is 6 decks, S17, often with surrender. Every
  row carries its conditions, and "agrees" means agrees in direction unless the
  conditions match.
- **Independence.** *Modern Blackjack*, *Blackjack in Color*, QFIT and the CVCX
  figures on the Wizard of Odds are all Wattenberger's work, and most published
  BC and PE rest on Griffin's effects of removal. The page should say so, so a
  reader does not count five agreeing sources as five independent checks.
- **Twin file against main file.** R (0.98857), F1, F2 and the twin-file rows of
  2D and 2J come from `runs/twin-80.states`. The page's figures come from the
  main file once steps 7 and 8 have run (R from step 7's save, read rather than
  recomputed); the generator must not mix the two.
- **Over-claiming.** The split explanation in 2B and every scratch number are
  leads until committed code reproduces them. Arithmetic done in this file
  (scaled effects of removal, BC/R, shares of a gain) is for orientation; the
  page recomputes all of it.
- **Drift.** Pages change; the Wizard of Odds pages show update dates in 2026.
  `claims.json` keeps the SHA-256 of each fetched page, the fetch date and an
  archive link where a snapshot already exists, and LiteratureCheck reports any
  figure whose cached page no longer matches its hash. New snapshots wait for
  Justin's yes on the push list (section 1, "Snapshots").
- **Copyright.** Short paraphrase and single figures only: no copied tables, and
  no cached third-party pages in the public repository.
- **Trust.** `literature.json` carries each claim's status, `checked_by` and
  `read_by_justin` in machine-readable form, so a reader or a language model can
  tell a checked figure from a cited one, ours from theirs, and an AI agent's
  check from Justin's own reading. Without `checked_by`, a status on a page
  written as "I" reads as Justin's check and overstates how independent the
  literature check is.

## 5. Source register

One line per source, for `claims.json` to start from. Every status below was set
by an AI agent; `checked_by` records that and `read_by_justin` starts false for
every row. "Copy" means a saved copy from 8 October exists in the session
scratchpad (`scratchpad/site-plan/literature/`), which is wiped when the session
ends; LiteratureCheck's cache replaces it.

| Source | Link | Status | Used in |
|---|---|---|---|
| Wizard of Odds, house edge calculator | <https://wizardofodds.com/games/blackjack/calculator/> | READ 8 Oct, copy re-read 9 Oct | 2A |
| Wizard of Odds, total- vs composition-dependent (appendix 15) | <https://wizardofodds.com/games/blackjack/composition-dependent-benefit/> | READ 8 Oct, copy re-read 9 Oct | 2A |
| Wizard of Odds, cut card effect (appendix 10) | <https://wizardofodds.com/games/blackjack/cut-card-effect/> | READ 8 Oct, copy re-read 9 Oct | 2A |
| Wizard of Odds, 8-deck H17 expected returns (appendix 9) | <https://wizardofodds.com/games/blackjack/appendix/9/8dh17r4/> | CHECKED 9 Oct (live) | 2A, 2B |
| Wizard of Odds, effect of card removal | <https://wizardofodds.com/games/blackjack/effect-of-removal/> | READ 8 Oct, copy re-read 9 Oct | 2C |
| Wizard of Odds, High-Low strategy (I18, Fab 4, CVCX sims) | <https://wizardofodds.com/games/blackjack/card-counting/high-low/> | READ 8 Oct, copy re-read 9 Oct | 2F |
| Wizard of Odds, variance (appendix 4) | <https://wizardofodds.com/games/blackjack/variance/> | READ 8 Oct, copy re-read 9 Oct | 2I |
| Wizard of Odds, risk of ruin | <https://wizardofodds.com/games/blackjack/risk-of-ruin/> | READ 8 Oct, copy re-read 9 Oct | 2I (not a check) |
| Wattenberger, *Modern Blackjack* p.106 | <https://web.archive.org/web/20150111033408/http://www.qfit.com/book/ModernBlackjackPage106.htm> | CHECKED 9 Oct (archive) | 2A |
| Wattenberger, *Modern Blackjack* pp.102, 110–111, 113–114, 172, 518 | `http://www.qfit.com/book/ModernBlackjackPage<N>.htm` via the Internet Archive | READ 8 Oct; pp.102, 110, 111, 113 copies re-read 9 Oct | 2D, 2E, 2H, 2I |
| QFIT, card counting systems table | <https://web.archive.org/web/20201208222104/https://www.qfit.com/card-counting.htm> | CHECKED 9 Oct (archive) | 2D |
| QFIT, KO versus HiLo | qfit.com, saved as `KO-HiLo-BlackjackComparison.htm`; URL to record | READ 8 Oct, copy re-read 9 Oct | 2E |
| QFIT, side counts | <https://www.qfit.com/blackjack-side-counts.htm> | READ 8 Oct | 2G |
| Wattenberger, *Blackjack in Color* (2007), sections 2.2, 2.4, 4.1, 4.2, 4.4, 4.5, 4.6 | <http://www.blackjackincolor.com/> (pages named in each section) | READ 8 Oct; 2.2 and 2.4 copies re-read 9 Oct | 2E–2H |
| BlackjackInfo, counting efficiency calculator | <https://www.blackjackinfo.com/card-counting-efficiency-calculator/> | used by the earlier stage; not re-checked | 2D, 2J |
| Encyclopedia of Blackjack, system comparisons | <https://www.blackjackreview.com/wp/encyclopedia/card-counting-system-comparisons/> | used by the earlier stage; not re-checked | 2D |
| Encyclopedia of Blackjack, "I is for the Illustrious 18" | <https://www.blackjackreview.com/wp/encyclopedia/i/> | READ 8 Oct | 2F |
| Encyclopedia of Blackjack, Blackjack Forum index | <https://www.blackjackreview.com/wp/encyclopedia/blackjack-forum/> | READ 8 Oct (index only) | 2F, 3 |
| Snyder, *Beat the 6-Deck Game* excerpt | <https://www.lasvegasadvisor.com/gambling-with-an-edge/an-excerpt-from-arnold-snyders-beat-the-6-deck-game/> | READ 8 Oct, copy re-read 9 Oct | 2E, 2H |
| Ethier and Lee, "Snackjack" (2020) | <https://doi.org/10.9741/2327-8455.1425>, <https://arxiv.org/abs/1906.01220> | preprint READ 8 Oct, copy re-read 9 Oct; record CHECKED 9 Oct | 2C, 2H |
| Thorp and Walden (1973) | <https://doi.org/10.1007/BF01737563> | citation CHECKED 9 Oct; content SECONDARY | 2H |
| Ethier and Levin (2005) | <https://doi.org/10.1239/aap/1113402401> | citation CHECKED 9 Oct; abstract READ 8 Oct | 2H |
| Vidámi, Szilágyi and Iclanzan (2020) | <https://doi.org/10.1007/978-3-030-63833-7_6>, <http://real.mtak.hu/114350/> | preprint READ 8 Oct, copy re-read 9 Oct; citation CHECKED 9 Oct | 2C, 2J |
| BlackjackPilot benchmark (18 June 2026) | URL to record | READ 8 Oct, little weight | 2E |

## 6. Checks done on 9 October, and how

All of these were done by an AI agent (Claude Code), not by Justin.

- **Wizard of Odds appendix 9** (live, WebFetch): title, rules, the split-value
  note, 10,10 against an ace (stand +0.600677, split −0.449008), 8,8 against a
  ten (split −0.476849), last updated 3 Aug 2026. All matched the saved copy.
- **Split cells, ours against theirs** (scratch, single-threaded, under a
  second): parsed the saved copy of appendix 9 and `test/ExactRoundEightDeckTest.tsv`
  (shoe 0) and compared all 100 split cells. Results in 2B.
- **Resplitting tens** (scratch, `resplit_check.py`, infinite deck, under a
  second): never-resplit and always-resplit values against an ace, 6, 9 and 10.
  Results in 2B.
- ***Modern Blackjack* p.106** (Internet Archive snapshot 20150111033408, curl):
  the table "Basic Strategy Advantage vs. FELT True Count Zero", row H17, DAS,
  column Eight: −.664. Matched.
- **QFIT counting table** (Internet Archive snapshot 20201208222104, curl):
  Hi-Lo, Hi-Opt II, Zen, Wong Halves and KO rows; the note on PE for unbalanced
  counts and on side counts; the −9/+4 insurance tags. Matched.
- **Crossref**: Vidámi et al. (LNCS, 2020, pp. 63–73), Thorp and Walden (1973),
  Ethier and Levin (2005), Thorp (1961), Baldwin et al. (1956). Matched. The
  journal's own record for Snackjack (UNLV) gives volume 24, issue 1, article 4,
  no page range.
- **Saved copies re-read** (8 October copies): the Wizard of Odds calculator
  code (the `edgeTable` entry and the 8-deck adjustments 0.0159 and 0.014), the
  total- vs composition-dependent table, the cut-card table, the effect-of-removal
  table and its rules, the variance tables, the High-Low page; Snackjack's
  sections on effects of removal and the fundamental theorem; the Vidámi preprint
  (method, Table 2); *Modern Blackjack* pp.102, 110, 111, 113; QFIT "KO versus
  HiLo"; Snyder's excerpt; *Blackjack in Color* 2.2 (its simulation details:
  truncated true counts, half-deck estimation) and 2.4 (rounding methods).
- **Ours re-read**: every figure marked ours was read again from the committed
  output named beside it.

WebFetch could not reach web.archive.org from this machine; curl could. QFIT
itself refused TLS on 8 October, which is why the archive copies are used.
