# What runs/ can let go of (proposed 18 October 2026, awaiting Justin's yes)

**Decided 18 October 2026.** Justin, after the 2 GB reviews: "ok, no need to make it small then.
But we will want to delete it once we wrap up this project". So nothing is rewritten to run small.
runs/ keeps what it holds, the tree store among it, until the project is wrapped up. Then it goes
down to its results: the kept list and the deletion batches of DISK_RESULTS_ONLY.md, each batch
confirmed with Justin when the time comes. DiskBudget's default is 27 GB until then.
Batches A to C below stay until then too; at wrap-up they are part of DISK_RESULTS_ONLY.md's batches.

On 18 October runs/ held 23.28 GB. Justin asked for the code to stop making that much ("perhaps
like 5gb max of content at any time"). The code side is `DiskBudget` (commit 03b116f): every run
that writes much checks a budget for runs/ before writing anything, and is refused if it would go
past it. The tree store keeps only the trees that fit. A finished replay deletes its own records.
The budget was 9 GB at first; since Justin decided not to make the project small (DISK_RESULTS_ONLY.md),
it is 27 GB, so the pending runs fit beside what is kept until wrap-up.

The files already there are a separate decision. Nothing below is deleted without Justin's yes,
batch by batch. Each list names every file with its size in bytes, paths relative to runs/:

| Batch | List | Files | Size | What it is |
|---|---|---|---|---|
| A | `disk/2026-10-18-trees.tsv` | 2 | 14.88 GB | The main run's tree store, `full-index/full-c06c061/trees.bin` and its index |
| B | `disk/2026-10-18-records.tsv` | 108 | 1.16 GB | The two replays' records, one per item: `replay/c06c061-0e0920d/replayed-*.ckpt` (93) and `page-look-0e0920d/replay/replayed-*.ckpt` (15) |
| C | `disk/2026-10-18-leftovers.tsv` | 738 | 0.87 GB | Superseded files: two dry runs, two old page previews, replay gate 0 with its sink and export, the page look's first attempt, the 0a447de key-edge table, `site-old-0a447de` |

With all three gone, runs/ would hold 6.38 GB (`java DiskBudget runs --without LIST`, which
measures it).

**A, the tree store.** Every state's player tree, kept only so later rounds read it instead of
building it again. No figure comes from it. A tree built again is the same tree, to the bit
(TreeStoreTest). On the real files too: the check below gave back two published items and every
state's perfect value to the bit with every tree built again. What it costs to delete: a replay
builds every tree again in each batch. In the check, the batch's last round took 8,123 s (2.3 h)
for 4 strategies with the trees built. The main replay took 2,082 s for 8 strategies with them
read. Part of that gap is load, since the test suite and other work shared the machine for most of
the check. The item-1 re-export (15 rows, about 4 batches, as the main replay batched 4 items)
pays it about 4 times.

**Not yet, then.** Item 1 is still pending: the 15 full-play rows of the main run and the page
look's F50/F52 export, both refused under open item 1 (INTEGRATION.md:377). Only PageLook makes
the F50/F52 export, and PageLook reads trees only from a kept store (`--trees-read-only`,
PageLook.java:181-183). So deleting A before item 1 is done would block that export until
PageLook gets a `--trees none` option. Even then the export would be slow: a full run has 49
rounds, and 48 of them read trees (full-c06c061.log). The recommendation, from the 2 GB review
(DISK_2GB.md), is A after item 1. B and C can go now.

**B, the replays' records.** Only a resume reads them. Both replays scored every item, and wrote
`replay.txt`, `perfect.ser` and `wide.ser` from them. Those stay. A resume would be pointless
anyway, because the item-1 fix reruns the export under new classes, and new classes make a new
replay. They would only matter for the 6 full-play rows whose export was refused along with the
other 9, and that fix exports all 15 again.

**C, the superseded files.** Each one was replaced by a later run whose output the page uses.

The code and its tests: the whole suite passes on 03b116f, 815 of 815 and the simulator's 89 of 89.
Each of the nine bugs planted in a scratch copy failed the test meant to catch it (DiskBudget's
class comment names them).

**How.** Each batch is moved to the macOS Trash, into one folder,
`~/.Trash/ccec-runs-2026-10-18/`, keeping its paths. So it can be looked at or put back until
the Trash is emptied, and the space comes back only then. Folders the move leaves empty are
removed. Then `java DiskBudget runs` reports what runs/ holds.

## The check against existing results

Two items of the main run, Hi-Lo and Victor APC at 80% (their fold fits), were replayed by the
disk-cap classes with `--trees none`. Every tree was built again and none was read from
trees.bin. Each was set beside the part the run wrote, number by number
(`runs/disk-cap-check/replay-trees-none-03b116f`, script
`runs/site-runs/disk-cap-check-03b116f.sh`).

Result, 18 October 2026 (`replay.txt` there):

- Hi-Lo at 80%: 416,114 numbers, all 416,114 the same to the bit.
- Victor APC at 80%: 829,436 numbers, all 829,436 the same to the bit.
- Perfect-play values: worked out again for 60,743 of 60,743 states, all the same to the bit.

The replay then let its 2 records go (0.01 GB, `records-let-go.txt`), the new code's path, on the
real files. So a tree built again gives the published figures to the bit, and deleting trees.bin
costs time, not numbers.
