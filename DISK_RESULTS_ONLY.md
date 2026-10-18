# Results only, under 2 GB, nothing on Drive (18 October 2026)

Justin: "i don't want to have to rely on drive for state, I just want like results, and produce
files that are required to create those results". This replaces the Drive plan of DISK_2GB.md.
A workflow traced every result back to the seeds that produce it, read-only. It used two mappers,
a reconciler, three analysts, a designer and an adversarial judge, and its working papers are
`disk/2026-10-18-results-only-working-papers.md`. Sizes below are measured with `DiskBudget
--without`, except those marked as estimates.

## What is kept

`disk/2026-10-18-results-only-kept.tsv` lists 679 files, 0.109 GB:

- the page and the seven `runs/site` files it is built from;
- the key-edge tables (78 MB, the largest);
- the spreads table and its report;
- the literature cache;
- the ceiling tables, the reports and the run logs;
- the simulator's part 1 summaries;
- the frozen classes of c06c061 and 97bf834. Five tests read them, and the check run before any
  replay of c06c061 is skipped, silently, without them.

Once the pending results exist, the kept set would be about 0.2 to 0.3 GB, mostly the
simulator's part 2 records. That is an estimate.

## The answer

**Keeping only results: yes, about 0.1 GB.** Getting there is the hard part. The pending runs
read about 2.8 GB of files that exist only once:

- the item-1 re-export of the main run's 15 full-play rows;
- the page look's F50/F52 export;
- the simulator's part 2;
- RiskReport.

So the folder goes over 2 GB once, on the way down:

- **Option (b): trees.bin goes first.** About 3.2 GB at the peak and 14 to 17 hours of machine
  time (estimate). PageLook first needs a `--trees none` option.
- **Option (a): trees.bin stays until the page-look export is done.** About 18 GB at the peak
  and 10 to 13 hours (estimate).

From then on the 2 GB cap holds.

**Making the intermediates again later, within 2 GB: not with today's code.** The main run alone
peaks at 2.4 to 3.3 GB. Bringing it under 2 GB would need a stack of changes:

- a compressed state-file format, estimated at 0.61 GB from one 200 MB sample;
- one copy of each run's results instead of two;
- compressed outputs;
- smaller key-edge collectors;
- a reproduce mode for the one look.

Even with all of them, the margin is about 0.1 GB, and it rests on that estimate. Each full
regeneration from the seeds would take about 5 to 6 days of machine time (estimate). Without a
tree store, every round builds every tree again.

## What is given up for good once the intermediates are deleted

- **The page look cannot be made again.** PageLook refuses a second look on the main file, by
  design (PageLook.java:202). Its outputs (run C, run D, its wide-plan files) would be gone. The
  key-edge tables made from them stay, as results, but could never be rebuilt.
- **The hashes the page cites for deleted files can never be checked again.** Each `.ser` file
  records how long its own steps took, so even a perfect rerun writes different bytes. The
  figures come back to the bit: measured for one fit today, predicted for the rest. The page
  would have to say so.
- **The state files are gone.** The downloads and the reader's spot check planned in PLAN_PAGE.md
  3.3-3.4 cannot exist. The main state file can come back byte for byte only if each of its
  three segments is valued again by the build that made it (13 h). That has never been tested.

**A middle option:** also keep the three `.ser` files the page's figures were exported from,
0.75 GB (c06c061's results, step 7's save, step 8's results). Then about 0.86 GB stays at rest,
SiteExport can still be rerun, and three of the page's cited hashes stay checkable.

## Deletion batches

`disk/2026-10-18-results-only-batches.tsv` lists every file, its size and its batch. Each batch
needs Justin's yes.

| Batch | Files | Size | What | When |
|---|---|---|---|---|
| D1 | 6,668 | 2.34 GB | Nothing reads or cites them: the main replay's records, 171 of the main run's 186 parts, dry runs, first attempts, superseded tables and previews | after the checks below |
| D1-ask | 2 | 0.15 GB | The page look's two wide-plan files. They were made once and nothing reads them yet | Justin's call |
| D1-after-change-6 | 16 | 0.23 GB | The page look's replay records and run.id. Deleting them before PageLook writes its replay to a new folder would let a resume overwrite its kept `replay.txt` | after that PageLook change |
| D2 | 7 | 2.77 GB | The twin state file, the main replay's sink, page-look runs C and D, the key-edge collectors | after the checks below |
| D3 | 2 | 14.88 GB | trees.bin | before item 1 under option (b), after the page-look export under (a) |
| D4 | 36 | 1.75 GB | The main state file, the fit files of c06c061 and step 8, and the 30 parts the item-1 re-export and the page-look export read, 15 each | after the page-look export |
| D5 | 36 | 0.08 GB | The simulator's two first-decision bundles (`page-look-0e0920d/export-first`, `sim/export-part1-0e0920d`) | after part 2 and RiskReport |
| D6 | 4 | 1.00 GB | c06c061's and step 8's results, step 7's save, the main wide plans | last, after the final page (or kept: the middle option) |

## Before anything is deleted

1. **Commit a manifest:** every file's size, time and SHA-256, the main state file's
   (ed9e9fb4…, measured 18 October, recorded nowhere yet) among them, with a hash per segment.
   Also commit a figures digest of each `.ser`, timings left out, so a later rerun can be checked
   against something.
2. **Run the replay gate (FitCheckpointClassesTest) and the whole suite at the site's tag** while
   the classes and results it reads still exist.
3. **Do item 1:**
   - the split-hand ranking fix;
   - the re-export of the 15 rows, which needs `--wide`, or six registered simulator figures
     (F43-F48) go missing;
   - the page-look export;
   - then the simulator's part 2.
4. **Fix the driver's lists before they are used.** Today the peaks and the batch lists come from
   the agents' scratch scripts. A committed driver (`Pipeline`) should hold them, and refuse to
   overwrite any kept file.

## Found on the way: the page's own hash check fails today

The page tells a reader to check its downloads with `shasum -a 256 -c SHA256SUMS`. In
`runs/page-613255a/data`, 14 of the 16 files listed could not be read, measured 18 October. Only
`results.json` and `results.csv` ship with the page, and two different files are both named
`results.ser` (SiteManifest.java:115, 121). This is so whatever is deleted, and it needs fixing
before the page is published.
