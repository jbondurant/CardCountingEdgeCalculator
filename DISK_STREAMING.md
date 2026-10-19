# Does the program need more than 2 GB of disk? (18 October 2026)

**Decided 18 October 2026.** Justin, after the 2 GB reviews: "ok, no need to make it small then.
But we will want to delete it once we wrap up this project". So nothing is rewritten to run small.
runs/ keeps what it holds, the tree store among it, until the project is wrapped up. Then it goes
down to its results: the kept list and the deletion batches of DISK_RESULTS_ONLY.md, each batch
confirmed with Justin when the time comes. DiskBudget's default is 27 GB until then.
The design below is kept for reference; it is not being built.

Justin asked whether the algorithm could be rewritten to stay under 2 GB at every moment, as a
sliding window over the data, or whether it truly needs more than 2 GB to be efficient. A
read-only workflow looked into it: four investigators, a designer and two adversarial judges,
one on exactness and one on speed. Its working papers are
`disk/2026-10-18-streaming-working-papers.md`. The probes it ran are in
`disk/probes-2026-10-18/`. They are scratch measurements of a few hundred states, not tested
repo code yet.

## The answer

**No, the computation never needs more than 2 GB of disk.** The full run reads the state file
once, in about 2 s, and works from memory after that. Each round already works as a sliding
window: it takes one state at a time, builds or reads that state's tree, walks it and drops it.
The one file over 2 GB is trees.bin (14.9 GB), and it is a cache.

**What the mathematics does fix:**

1. **The rounds run one after another.** The fit is policy iteration. After each sweep over all
   60,743 states it refits every play by the signs and argmaxes of sums over all of them
   (FullIndexFit.java:981-1014, 1170-1186). So no block of states can go through all the rounds
   on its own. Each state's tree is needed once a round, 48 times in a full run.
2. **The trees cannot be stored small.** A walk reads about 89% of each tree's feasible cells,
   whatever the play. The values are raw doubles that compress at most about 1.3 times without
   loss. So the smallest exact store is about 10 to 12.7 GB. Every pass cycles through all the
   states, so a smaller cache saves time only in proportion to its size, about 1.5 h per GB.
3. **The bootstrap needs one scoring item's values in memory at once.**

These limit memory and processor time, not disk. Under 2 GB, the trees are built again in every
round. The figures come back the same to the bit: the disk-cap check measured this on the real
files.

**Everything else over 2 GB is a storage choice**, and each fix keeps every figure to the bit:

- The state file stored without loss: about 0.61 GB instead of 1.34 GB. The ratio, 0.4523, is
  measured on 300 records from all three of its segments.
- One copy of the results instead of two. results.ser is the merged parts.
- Gzipped checkpoints.
- Slim key-edge collectors (1.0 GB of what they hold is never read).
- Replay records that do not copy their hooks' outputs.

**With those, the main run peaks at about 1.0 GB** (estimate), down from 2.39 GB. The whole
pipeline peaks at about 1.7 to 1.9 GB (estimate). That peak comes while the twin's chain runs,
since the twin's state file and the main one are on disk together. So it fits under 2 GB, with
0.1 to 0.3 GB to spare.

## The one real cost: time

Today the main run took 19.8 h of compute, and 24.7 h by the clock (the machine slept).

| Version | Extra time on the main run |
|---|---|
| No tree store, code as it stands | +16 to 25 h |
| + only the cells the walks read, and the dealer arithmetic regrouped | +8 to 21 h |
| + more items packed into each scoring batch (43 final rounds down to about 13) | +3 to 8 h |

All three are estimates.

- **The two tree-build changes** are bit-identical: 0 differing bits in about 40 million cells,
  measured on a prototype. The regrouped arithmetic's speedup is unreliable, though: 1.13 to
  1.85 times, depending on the run.
- **Packing more items per batch** rests on a heap model, not a measurement. It works because
  only 14.5% of scoring parts depart from the chart (measured on 78 states).
- **Separately, running the replay's three hooks inside the main run** would save about 13 h of
  the pipeline, with or without a tree store. That is untested, and one hook, the export, still
  refuses rows until item 1 is fixed.

## Two conditions the exactness judge found

- **A tree built with only the read cells must hold a placeholder, not NaN, in its other
  feasible cells.** Otherwise every departing part silently falls back to the chart's value, and
  no figure even shows a NaN (measured: all 98,349 departing parts affected). The test must
  compare walks on such a tree with walks on the full tree, bit for bit.
- **The full tree must be built wherever perfect-play values are recomputed,** not only in round
  0. The replay and PageLook recompute them all, and on a partial tree 1,605 of 24,839 were wrong.

## Still to measure before building it

- **One full tree rebuild of all states on 6 threads, on a quiet machine, sustained.** It is the
  multiplier in every hour above, and the MacBook Air may throttle.
- **The regrouped dealer arithmetic, built into ExactRound,** timed at 6 threads in a real run,
  and checked bit for bit on at least 1,000 states from each of the three segments.
- **The real memory per item in a packed batch** (a dry run with GC logging).
- **The whole state file converted and decoded back,** to SHA-256 ed9e9fb4…. Only 300 records
  have been round-tripped. The conversion needs about 2.06 GB for a moment, so it must come
  before the cap.
- **The gate:** a 6,000-shoe dry run with today's code and the store, against the new code
  without it, with 0 differences.
- **Swap on the same disk during a long run.** It was 1.7 GB on 18 October, and DiskBudget does
  not count it.
