# Results only, no Drive: the workflow's working papers (18 October 2026)

Justin: "i don't want to have to rely on drive for state, I just want like results, and produce files that are required to create those results".
Two dependency mappers (top-down, bottom-up), a reconciler, three analysts (bit-reproducibility, time and the
one-look ledger, peaks), a designer and an adversarial judge, read-only, 18 October 2026. MEASURED means measured
by the agent with stat, shasum, logs or a scratch script, not yet by repo code, unless it names DiskBudget.
DISK_RESULTS_ONLY.md is the summary. Kept as evidence, not instructions.

## The judge

**Answer.** Justin, the requirement can be met with conditions, but not the way the design does it. Kept on the laptop, the results are 106,981,701 B today; I measured that again. Once the pending results exist they come to about 0.19-0.34 GB, mostly because of the simulator's records, which the design left out (ESTIMATED). Both are well under 2 GB. Getting there cannot stay under 2 GB at every moment. runs/ holds 23,286,908,933 B now (MEASURED), and the smallest it can get before the pending runs is 2,929,752,865 B, already over. The design's default keeps the 14.9 GB tree store and sits at about 18 GB. The better default deletes the tree store first and gives PageLook a --trees none option. The worst overshoot is then about 3.2-3.3 GB, for about 4-5 h more machine time. From the moment the main state file goes (batch D4), the cap holds, but only if the driver also deletes the simulator's leftover record parts after each run; otherwise part 2 reaches about 2.0 GB. Before any of this runs, four fixes are needed. P1's command needs --wide, or six registered simulator figures (F43-F48) are missing. Batch D1 must keep c06c061's and 97bf834's class folders (1.85 MB), or the check that runs before the replay is silently skipped. The PageLook change (code change 6) must land before the page look's old replay files are deleted, or a kept result (replay.txt) is overwritten. And the page text must not say the other 13 hashes stay checkable: only 2 of the 16 listed files ship with the page today. Once the last batch is deleted, only page rebuilds are cheap. Any new figure means a 5-6 day rerun from the seeds and new code. The deleted files' hashes can never be checked again, and the plan in PLAN_PAGE.md for readers to download the state files and spot-check them goes away.

## What held up when I checked
- runs/ holds 23,286,908,933 B in 7,450 files (MEASURED, re-stat today).
- The results are 106,981,701 B in 264 files (MEASURED, re-summed).
- D1 to D6 add up to the 23,179,927,232 B of intermediates (MEASURED arithmetic).
- Logged times I checked against the logs:
  - main replay: 53,631.8 s
  - run D: 11,915.0 s
  - main state file: 26,065 + 17,238 + 3,650 s
  - c06c061: 19.8 h
  - step 8: 5.3 h
  - twin: 11,637 s
- The `rc=` lines in the scripts always read 0 (MEASURED in zsh, bash and sh).
- These code claims match the code: PageLook.java:202 (no second fresh look) and :207 (a resume checks only that look.txt exists); PageLook.java:466 together with FullIndexCheckpoint.java:86-89 (a resume under another commit is refused); CustomCounts.isMain reads only the header's seed.

## Corrected migration (default is option b)

**Step 0. Commit the evidence.** Commit batches.tsv and the peak model into the repo, under disk/, as the first version of `Pipeline plan` (code change 9). Nothing gets deleted against a list that lives only in /private/tmp.

**M0, before any deletion.**
- Run RunsManifest (all hashes, plus per-segment hashes of both state files).
- Run ResultDigest. Also dump step 7's Result.seconds, its only in-program time record.
- Run StateSpotCheck.
- Check that every classes folder rebuilds from git.
- Run FitCheckpointClassesTest now.

**D1′, after Justin's yes.** This is D1 with three changes:
- Leave out runs/full-index/classes-c06c061 and custom-count/classes-97bf834 (1,846,605 B). They become results.
- Leave out the page look's wide-first.ser and replay/wide.ser (145,123,610 B) until Justin decides.
- Delete the page look's replay run.id and records only after change 6 is committed.

D1′ is about 2.56 GB.

**D2 (2,769,420,814 B).** Unchanged.

**C. Code changes, in this order.**
- **Change 4, the item-1 fix,** with a test that B1 and export-first still play under the new simulator. If they don't, re-export them before D4, and record the new simulator commit in SIM_CHECK.md.
- **Change 5.**
- **Change 6:** PageLook replays into replay-<commit>/ with only the export and Step8 hooks, compares look.txt's content apart from the commit line, takes --trees none, and gets a re-estimated BYTES.
- **Changes 7 and 8.**
- **Change 9, the driver.** It must refuse to overwrite any path listed in results.tsv. During P3 it deletes p3-records/ and the planted runs' records-*.tsv after each run.

**D3, trees.bin (14,877,669,295 B).** Delete it now, so runs/ is about 2.93 GB.

**P1. The item-1 export.** Command: FullIndexRun --fit-from c06c061 --only <15 items> --export-row ×15 --folds-only **--wide** --trees none --resamples 1000 --threads 6 --disk-budget 3.5. Peak about 3.18 GB (E).

**P2. PageLook --resume** with changes 6 and --trees none. Set CCEC_DISK_BUDGET_GB to about 3.5. Peak about 3.24 GB (E).

**D4 (1,748,269,423 B).** runs/ falls to about 1.27 GB. The cap holds from here.

**P3, simulator part 2,** with per-run cleanup: peak about 1.48 GB (E). Without the cleanup it is about 2.0 GB (E), which breaks the cap.

**D5, the bundles.**

**P4 RiskReport, then P5 SiteAdapt sim.**

**The whole suite at the site's tag.** It needs results.ser and the classes folders.

**P6, with a new script.** It runs only the adapters, SiteExport through --states-header if the guard keys changed, and SiteBuild. It does not use row10-partial-7c1d40e.sh, which needs the twin and the collectors.

**D6 (996,852,349 B).** Then set BUDGET_GB = 2.

**At rest at the end:** 0.19-0.34 GB (E), depending on whether $O2's records and the planted runs' records are kept.

**Option (a)**, keeping trees.bin until P2, still works. It is faster, about 10-13 h of machine time, but runs/ holds about 18.1 GB along the way.

## Time (machine)
| Step | Time |
|---|---|
| M0 | about 0.5 h |
| P1 | 3.0-3.5 h with trees read (closest measured analog: run D, 11,915 s) + 4T for tree building = 4.7-5.8 h (E) |
| P2 | 3.31 h measured, + 5T = 5.4-6.1 h (E) |
| P3 | 2.5-4 h (E) |
| P4 | under 1 h |
| The suite at the site's tag | not measured |
| **Total under option (b)** | **about 14-17 h on a quiet machine (E)** |

Under the 18 Oct load, T is about 3,000-3,500 s a round (E), not 6,000.

A full regeneration from the seeds (code changes 11-15) is still about 128-149 h (E). The peak model gives 1,973,476,385 B at the 93-item replay (MEASURED output of the model; ESTIMATED inputs), not counting the P3 records.

## Bit-identity per result
- **P1 and P2 bundles:** the figures are checked item by item (93 of 93 to the bit last time). Rebuilt trees give the same bits (S32, last round). Bundle manifests are predicted to rebuild byte for byte.
- **P2's Step8 check:** to the bit against step 8's results.ser.
- **Simulator:** the figures do not depend on thread count. The summaries carry wall-clock seconds; records and checks are deterministic.
- **RiskReport:** deterministic on one JDK.
- **The page:** byte for byte from runs/site.
- **Deleted .ser and checkpoint files:** never by SHA-256; only by ResultDigest, which is not built yet.
- **State files:** a prediction only.

## Ledger
- The migration writes no ledger line, and P2 is a resume.
- Change 6 should add the comparison with look.txt.
- Change 15 must name the commit of each output: 0a447de for runs C and D, and the item-1 commit for export-full. The ledger names 0e0920d, which crashed.
- CeilingResults data must check the reused optimism file's header hashes.

## Decisions for Justin
1. Option (b) or (a).
2. Keep the page look's wide files (made only once)?
3. Which result files ship with the page. Today 2 of the 16 listed do, and the state-file downloads and spot check in PLAN_PAGE.md 3.3-3.4 cannot exist under results-only.
4. Are the planted runs' records results?
5. Keep the 77.6 MB key-edges JSON and CSV, and the simulator bundles?
6. The rest of the design's own list stands: strict results-only or keep the three .ser files (0.75 GB more), and how much regeneration code to build.

### Refutations

- [major] **From D4 on the 2.0e9 cap holds. P3 (simulator part 2) peaks at about 1.28 GB, and its results are about 10 MB (regen_model.py uses p3 = 14 MB).**
  - Problem: Simulator part 2 writes per-shoe records for F27 and F29, the two figures with paths = 1. Three sets are left out of the estimate. (1) On $O2, records for 2,000,000 shoes per figure. (2) The per-chunk parts in p3-records/, which the simulator never deletes. (3) The 9 planted p3 runs on B2 at --chunks 1, each keeping 1,000,000 shoes per figure. At 15-19 B a 'Y N M' line that is about 0.75 GB (ESTIMATED). Only $O2's records are read, by RiskReport --records. Unless the driver deletes the parts and the planted runs' records after each run, P3 reaches about 2.0 GB (ESTIMATED). The at-rest total also grows by about 0.06-0.33 GB.
  - Evidence: sim/Sim.java:151-166 (recordShoes = min(2*size, chunks*size); parts in OUT/p3-records; writeRecords). sim/P3.java:259-261 and 398-432. SHOE_SPEC.md:1093-1096. SIM_CHECK.md registry: only F27 and F29 have paths=1. INTEGRATION.md:659-668 (planted runs on $B2 at --chunks 1) and :734 (RiskReport reads only $O2's records). sim/*.java has no delete except Pilot's work dir (grep). regen_model.py: p3 = 14*MB.
- [major] **D1 holds only files that nothing reads and nothing cites, including the frozen classes dirs (26,437,161 B).**
  - Problem: D1 deletes runs/full-index/classes-c06c061 (all 186 files are in batches.tsv; 756,148 B MEASURED). Five tests read it: FitCheckpointClassesTest, CeilingScoringTest, RealCountTest, StoredResultsTest and WidePlansTest. When the directory is missing they are skipped, not failed. INTEGRATION.md runs FitCheckpointClassesTest as the gate before replaying c06c061's fit.ckpt under new classes, and P1 is exactly such a replay. D1 comes before P1, so the gate stops checking without saying so. The site-tag suite (open item 21) would then record skips.
  - Evidence: test/C06c061.java:33-40: assumeTrue(false, "no c06c061 classes ..."). test/FitCheckpointClassesTest.java:10-18. INTEGRATION.md:520-525 (--select-class FitCheckpointClassesTest before the replay). grep -c full-index/classes-c06c061/ in batches.tsv = 186. custom-count/classes-97bf834 (1,090,457 B) is also in D1.
- [major] **P1 = FullIndexRun --fit-from c06c061 --only <15 items> --export-row x15 --folds-only --resamples 1000 --threads 6 --trees-read-only. Change 4 makes --only with --export-row attach F43-F48.**
  - Problem: F43-F48 are wide plans scored only by the WidePlans hook. The code attaches them only when o.wide is set, and the command has no --wide. The replay bundle would lack six registered part-2 figures, and SiteAdapt sim refuses a registered test that is never met. --wide is required. It adds wide.ser at about 2.7 MB an item, about 40 MB for 15 items (ESTIMATED).
  - Evidence: FullIndexReplay.java:407-438 (if (o.wide) export.plans(...), else 'not exported'). SIM_CHECK.md registry F43-F48 engine 'replay'. SiteSim.java class comment ('a registered test of a part the runs cover not met at all'). DiskBudget.java:43-45 (wide plans 2.7 MB an item).
- [major] **The graph says P2 needs the page look's old replay/run.id deleted, and the design's D1 deletes it before the code changes in step C.**
  - Problem: Under the current code, once that run.id is gone, PageLook --resume starts a fresh replay into page-look-0e0920d/replay with the wide and sink hooks on. That overwrites the kept result replay/replay.txt (the 15-of-15-to-the-bit record). It also rewrites wide.ser and run-d/run-b.ser, about 275 MB more. Only change 6 (a new replay-<commit>/ dir with export and Step8 hooks only) makes the deletion safe. Either the driver refuses PageLook until change 6 is committed, or it refuses to overwrite any path listed in results.tsv.
  - Evidence: PageLook.java:466-470 (r.resume = o.resume && exists(run.id); wide=true; sink=run-d). FullIndexCheckpoint.java:73-80 (a fresh open refuses only when run.id or fit.ckpt exists). FullIndexReplay.java:910-911 and 1011 (replay.txt written with REPLACE_EXISTING).
- [major] **For the reader: 3 of the 16 SHA256SUMS lines would name missing files; the other 13 stay checkable as before.**
  - Problem: MEASURED today: running shasum -a 256 -c SHA256SUMS in runs/page-613255a/data reports that 14 of the 16 listed files could not be read. Only results.json and results.csv ship with the page. Five of the lines are git files, and most of their commits are on no remote. So the page's check fails today whatever is deleted, and deletion adds three more. The fix is a decision about which result files ship with the page, not a footnote.
  - Evidence: cd runs/page-613255a/data && shasum -a 256 -c SHA256SUMS → 'WARNING: 14 listed files could not be read'. template.html:477. The working papers: 14 of the 17 commits the evidence table cites are on no remote.
- [major] **The runs/ overshoot happens once and only shrinks. The fastest default is option (a): keep trees.bin until P2, at about 18 GB.**
  - Problem: The overshoot is not monotonic. In (a) runs/ grows from 17.81 to 18.12 GB during P1 and P2; in (b) it grows from 2.93 to 3.24 GB (MEASURED output of regen_model.py). Under 'at no moment above 2.0e9', (a) runs at about 9 times the cap for the whole code-writing period plus about 6.5 h of runs. (b) stays at or under about 3.3 GB for about 4-5 h more. The default should be (b), with (a) as Justin's opt-in.
  - Evidence: python3 /private/tmp/results-only-pipeline-93809/bin/regen_model.py: P1 17,990,675,656 and P2 18,122,175,481 with trees kept; D3 2,929,752,865, P1 3,113,006,361 and P2 3,244,506,186 with trees deleted first.
- [major] **Change 15: PageLook --reproduce refuses unless the classes commit, settings, blobs and state hash equal the original look's.**
  - Problem: The rule cannot be met for PageLook. The look's ledger line and look.txt name 0e0920d, and that PageLook crashed in firstDecisions. Runs C and D were made by 0a447de, and export-full will be made by the item-1 commit. A reproduction at 0e0920d fails again. The rule has to name the commit that made each output.
  - Evidence: runs/page-look-0e0920d/look.txt line 2: commit 0e0920d. The count-design ledger line 3 is 0e0920d. attempt1-0e0920d/page-look.log ends with: IllegalArgumentException: no row named "Chart, no count, flat" at PageLook.firstDecisions. page-look-0e0920d.log:1 is PageLook 0a447de.
- [minor] **P1 takes about 2.4 h with trees read (561 s a batch for the last round, 414 s an item for scoring), so the migration takes 9-12 h.**
  - Problem: These are averages over all 93 items of the main replay, whose later batches ran on a warm cache of split values. The first two batches' last rounds took 2,084.6 s and 2,134.3 s. The closest measured analog is run D: 15 fold items, cold cache, trees read, which took 11,915.0 s (7,196.3 s last rounds + 4,718.7 s scoring). P1 is about 3.0-3.5 h (ESTIMATED), and the migration about 10-13 h.
  - Evidence: grep '^a batch' replay/c06c061-0e0920d.log gives 27 batches, 15,145.1 + 38,486.7 s; batches 1-2 are 2,084.6 and 2,134.3 s. page-look-0e0920d.log:45-111: batch 1 4,618.5 s ... total 11,915.0 s.
- [minor] **Under the 18 Oct load, building trees costs about 6,000 s a round (8,120.8 s against 2,082.4 s), so a full regeneration is about 230 h.**
  - Problem: The two rounds compared did different amounts of work: 3,278,544 against 1,756,660 splits valued. Run D's cold first batch with trees read took 4,618.5 s on 3,083,455 splits. That puts tree building under load at about 3,000-3,500 s a round (ESTIMATED), so the 230 h figure is too high.
  - Evidence: disk-cap-check/replay-trees-none-03b116f.log: round 6 8,120.8 s, 3,278,544 splits. replay/c06c061-0e0920d.log:13: 2,082.4 s, 1,756,660 splits. page-look-0e0920d.log:45: 4,616.3 s, 3,083,455 splits.
- [minor] **The totals come from time-ledger totals.py, and the peaks and batch lists from scratch scripts.**
  - Problem: /private/tmp/time-ledger-84202/ is empty, so totals.py does not exist. Re-adding the table gives 115.8-133.3 h, so the totals hold. But every peak, total and the per-file deletion list Justin is asked to approve live only in /private/tmp. That breaks the standing rule that numbers come from repo code, and Justin's note that the scratchpad is wiped at session end.
  - Evidence: ls /private/tmp/time-ledger-84202 → empty. batches.tsv and regen_model.py are only under /private/tmp. AGENTS.md: 'Numbers from repo code'.
- [minor] **Reuse optimism-7c1d40e at P6 and in R8 instead of rerunning it.**
  - Problem: CeilingResults data loads the properties with no check. Yet optimism's figures subtract main-file figures read from step 7's save and from results.ser, and its header records those files' SHA-256s. That is harmless while the originals are in place. In a from-seeds rerun, nothing would catch a stale pairing.
  - Evidence: CeilingResults.java:761-765 (Properties.load, no compare). :1340-1343 (main-file figures from step 7 and c06c061). :1397-1402 (header hashes). optimism-7c1d40e.properties lines 3-6.
- [minor] **D1: the page look's unread wide-first.ser and replay/wide.ser (145,123,610 B) can go now.**
  - Problem: These are outputs the look declared: its ledger line says 'page figures: wide plans', and PLAN_COMPUTE.md lists 'registered rows' wide'. The look is made only once. No row-10 command reads them yet, so deleting them is one-way, and it is Justin's call, not a no-reader batch.
  - Evidence: custom-count-ledger.tsv line 3. PLAN_COMPUTE.md:1065-1070 and 1130. PageLook.java:19-26 and 202.
- [minor] **P1 and P2 run with the budget raised to about 18.5 GB, logged.**
  - Problem: PageLook takes no --disk-budget option; it reads only CCEC_DISK_BUDGET_GB. With BYTES = 1e9 against about 17.9 GB held, P2 needs at least about 18.9 GB. The driver also sets the variable to 2.
  - Evidence: PageLook.java:516-529 (accepted options) and :58, :247. DiskBudget.java:63-71.
- [minor] **Step 7 has no in-program time; its 3.96 h is estimated from file times.**
  - Problem: report-ceiling-97bf834.ser stores each count's Result.seconds, so step 7's in-program time can be read from it before D6 deletes the file.
  - Evidence: SystemScorer.java:2456, 2590 (Result.seconds). The .ser is born 23:14:24 and its .log is empty (stat).
- [minor] **The disk has 3.6 GB free, which limits the macOS Trash option.**
  - Problem: The figure is out of date: df now shows 15,703,428 KiB available, 93% used.
  - Evidence: df -k /Users/justinbondurant/Documents, 18 Oct about 17:20.

### Missing from the graph

- Simulator part 2's per-shoe records: records-F27.tsv and records-F29.tsv in $O2 (about 60 MB), the parts in p3-records/ that are never deleted (about 76 MB), and the records and parts of the 9 planted p3 runs on B2 (about 0.6 GB). All ESTIMATED; only $O2's records have a reader (RiskReport).
- P1 needs --wide for F43-F48, and its WidePlans output wide.ser (about 40 MB ESTIMATED) is an intermediate with no reader.
- runs/full-index/classes-c06c061 and custom-count/classes-97bf834 (1,846,605 B MEASURED) are inputs to FitCheckpointClassesTest (the pre-replay gate, INTEGRATION.md:520-525) and to four other test classes, which skip when they are missing.
- The suite's JUnit report at the site's tag, for SiteAdapt evidence --junit --rerun-at (INTEGRATION.md open item 21). It needs the classes dirs above, and results.ser for StoredResultsTest, so it must run before D1 and D6 touch them.
- PLAN_PAGE.md 3.3-3.4 and 8 plan more results: the StateSpotCheck run printed on the page with a seed fixed after publication (it needs both state files after release), the depth-1 rescore against results.ser, verify.sh, and about 1.8 GB of published downloads including the state files. None can exist under results-only with no off-laptop copy.
- The page look's wide-first.ser and replay/wide.ser are declared look outputs ('page figures: wide plans') that can never be remade.
- The item-1 fix touches ExportBundle and sim/Strategy.java (INTEGRATION.md:365-377). The graph does not check whether B1 and export-first stay readable, or the SIM_CHECK.md identity row ('fe9a91e ... for part 2 unless sim/ changes').
- P6 needs a new script. The committed row10-partial-7c1d40e.sh reruns CeilingResults optimism (it needs the twin, line 22) and KeyEdgesRun report (it needs the collectors, line 28), and D2 deletes both inputs.
- Step 7's in-program time (Result.seconds) inside report-ceiling-97bf834.ser.
- SiteSimReported hashes c06c061's results.ser as an artifact (SiteSimReported.java:135), so the final page cites 8ba1412b again, after D6 has deleted it.

### Estimated, not measured

- Simulator part 2's records: about 0.75 GB (15-19 B a line × (2 × 2,000,000 + 9 × 2 × 1,000,000) lines, final files plus parts)
- P3 peak: about 1.48 GB with per-run cleanup, about 2.0 GB without
- At rest at the end: 0.19-0.34 GB
- P1 time with trees read: 3.0-3.5 h (analog: run D's 11,915 s)
- P1 with --trees none: 4.7-5.8 h; P2 with --trees none: 5.4-6.1 h
- Migration total: about 14-17 h under option (b), about 10-13 h under option (a)
- T (extra seconds per round to build trees): 1,500-2,023 s on a quiet machine; about 3,000-3,500 s under the 18 Oct load
- P1's wide.ser: about 40 MB (2.7 MB an item × 15)
- Peaks under option (b): P1 about 3.18 GB, P2 about 3.24 GB, after D4 about 1.27 GB
- PageLook budget needed under option (a): at least about 18.9 GB
- D1′ size: about 2.56 GB (D1 less the classes dirs and the look's wide files)
- Full regeneration: 128-149 h; peak 1,973,476,385 B in the model (its inputs are estimates, e.g. a v3 ratio from a 200 MB sample)
- P3 2.5-4 h and P4 under 1 h (INTEGRATION.md estimates)
- Bundle manifests rebuilding byte for byte, and the state files coming back to the same bytes: predictions, not measurements

## The design (before the judge)

**Answer.** With conditions. At rest, yes: the results are 106,981,701 B today (MEASURED, 264 files; DiskBudget --without agrees), and about 0.12 GB once the pending results exist (ESTIMATED). The way there cannot stay under 2 GB at every moment. runs/ holds 23,286,908,933 B now (MEASURED, re-stat this session). The pending runs (the item-1 re-export, PageLook's F50/F52 export, simulator part 2, RiskReport) need 2.82 GB of files that exist only once, plus trees.bin or a new PageLook --trees none option. So the cap can start only after PageLook has run and batch D4 is deleted, which leaves about 1.27 GB. That overshoot happens once and only shrinks. After that, the only things the program can regenerate within 2 GB as the code stands are the page itself (3.8 s) and the adapters' re-reads of kept JSON. Regenerating any engine result from the seeds needs code that does not exist yet. With a lossless v3 state-file format, one copy of each run's results, gzip, slim collectors, a reproduce mode for the one look, PageLook --trees none, and SiteExport reading a header record, the whole main chain fits at an estimated peak of 1.97 GB, at the 93-item replay. That is a 26 MB margin, and it rests on a v3 compression ratio measured on 200 MB only. A full regeneration takes about 128-149 machine hours on a quiet machine. The figures should come back to the bit: measured for one fit, and predicted for the states file, which needs build-d3c0b1f for its first 6,650 shoes. The cited SHA-256s of the deleted .ser and checkpoint files never come back, because those files record their own run times. Once the last batch goes, any page change that needs a figure not already in runs/site costs that 5-6 day rerun.

**At rest.** 106,981,701 B in 264 files today (MEASURED: stat sum over the merged results list; `java -cp runs/full-index/classes-03b116f DiskBudget runs --without results.txt` printed 0.107 GB, 0 missing). The pending results add about 13.5 MB (ESTIMATED: sim part 2 about 10 MB, risk 0.2 MB, site/sim 0.05 MB, final page 3.2 MB). Final runs/ is about 118.5 MB (ESTIMATED, /private/tmp/results-only-pipeline-93809/bin/regen_model.py). Variants: minus 77,605,620 B if the key-edges JSON and CSV are not results (only the 0.47 MB page input is kept); plus about 0.16 GB if the four simulator bundles stay; plus 746,082,929 B if c06c061's results.ser, step 7's save and step 8's results.ser stay. That last variant is about 0.86 GB at rest, still under 2 GB.

**Time.** - **Migration (machine time):** about 9-12 h with trees.bin kept until PageLook (ESTIMATED). That is M0 about 0.5 h, P1 about 2.4 h, P2 about 3.3 h, P3 2.5-4 h, P4 under 1 h, and minutes for the rest.
  - If trees.bin goes first: 13-17 h on a quiet machine, about 25 h at the 18 Oct load.
  - Writing and testing code changes 1-10 comes on top.
- **Steady state:** a page rebuild takes 3.8 s (MEASURED).
- **A full regeneration from the seeds:** 128-149 h on a quiet machine that stays awake (5.3-6.2 days), about 230 h at the 18 Oct load (ESTIMATED; time-ledger totals.py). The main chain alone, without the twin chain, is about 118-136 h. Building trees for 92 rounds is 38-51 h of that, a cost the 2 GB cap forces.

### Pipeline

- **M0a. Record what will be deleted (new tool RunsManifest, code change 1)**: writes A committed disk/<date>-runs-manifest.tsv: path, bytes, ms mtime and SHA-256 for all 7,450 files. The two state files also get a SHA-256 at shoe boundaries 6650 and 40000. Nothing is written in runs/.; deletes after: nothing; peak 23,286,908,933 B (runs/ unchanged; MEASURED this session); wall about 4 min (ESTIMATED: 23.29 GB at 92.7 MB/s, from the archive investigator's 747 MB in 8.06 s); bit-identical: Read-only. It is the only chance to commit ed9e9fb4 (main state file), a2f8a751 (twin) and trees.bin's hash. PLAN_PAGE.md:697 requires that last one, and none of the three is committed today.
- **M0b. Figures digests of every engine file that will go (new tool ResultDigest, code change 2)**: writes A committed pipeline/digests.tsv covering c06c061's results.ser and fit.ckpt, step 7's save, step 8's results.ser and fit.ckpt, wide.ser, the five collectors and export-first; deletes after: nothing; peak 23,286,908,933 B; wall minutes (ESTIMATED: about 2.6 GB of .ser to deserialize); bit-identical: Read-only. The digest leaves out Result.seconds, PassRecord.wallSeconds and RoundStats times, so a regenerated file can be held to the deleted one's figures. MEASURED: two fits with bit-identical figures differ in 57 of 57 wallSeconds.
- **M0c. StateSpotCheck and a check that the class dirs rebuild from git (code change 3)**: writes A small report in runs/ (a result, a few KB); deletes after: nothing; peak 23,286,908,933 B; wall about 15-30 min (ESTIMATED: 200 states a segment at 3.85 s for segment 1 and 0.18-0.19 s elsewhere, from montreal-80.log and twin-80.log); bit-identical: Turns two predictions into sample measurements: that segment 1 needs build-d3c0b1f, and that today's classes remake the twin. Any classes dir that does not rebuild identically stays as a result; full-index/classes-cbdd02b and sim/classes-0e0920d have no commit.txt.
- **D1. Delete what nothing reads and nothing cites (Justin's yes)**: writes nothing; deletes after: 2,710,065,959 B in 7,101 files:
- superseded runs and logs: 956,175,021 (873 files)
- main replay records, perfect.ser and run.id: 929,858,299 (95)
- c06c061's other 171 parts: 358,798,073
- the page look's replay records, perfect.ser and run.id: 227,464,825 (17). As the code stands, P2 is refused until this run.id goes (PageLook.java:466, FullIndexReplay.java:459).
- the page look's unread wide-first.ser and replay/wide.ser: 145,123,610
- choice dir, calibration parts and search logs: 61,545,942 (90)
- frozen classes dirs: 26,437,161 (5,737)
- sim part 1 chunk folders: 1,945,070
- step 8's halves: 1,742,829
- the disk-cap check's perfect.ser and run.id: 975,129; peak runs/ falls to 20,576,842,974 B (MEASURED stat sizes); wall seconds to minutes; bit-identical: Nothing cited is lost. The 186 parts are byte slices of results.ser apart from Result.seconds (working papers).
- **D2. Delete files that are cited but have no pending reader (after M0, Justin's yes)**: writes nothing; deletes after: 2,769,420,814 B in 7 files:
- twin and its .progress: 1,341,183,296
- key-edges a: 221,351,385
- a-prime: 65,554,901
- sink run-b.ser: 704,599,811
- run-c.ser: 245,680,412
- run-d/run-b.ser: 191,051,009; peak 17,807,422,160 B (MEASURED stat sizes); wall seconds; bit-identical: One-way: a2f8a751, 56148393, c649f44f, 3c1866b6, 2939a4ea and 3fe732ce can never be checked again; only their M0 digests remain. Optimism is reused, so no pending step reads the twin (CeilingResults.java:761-765).
- **C. Code changes 4-8, then classes frozen at that commit**: writes classes in a temp dir outside runs/, as build-site.sh:39-41 does; deletes after: the temp classes after each step; peak 17.81 GB (unchanged); wall code time; javac takes 1-3 s a dir (PLAN_PAGE.md:699-702); bit-identical: The item-1 fix changes only the export, not the scoring.
- **P1. Item-1 re-export of c06c061's 15 full-play rows**: writes runs/sim/export-part2-replay-<sha>/ (about 60 MB, ESTIMATED), plus replay.txt, perfect.ser and run.id. Records of about 120 MB (ESTIMATED) are written, then let go. Command: FullIndexRun --fit-from c06c061 --only <15 items> --export-row x15 --folds-only --resamples 1000 --threads 6 --trees-read-only trees.bin, with --disk-budget about 18.5 GB for this run only, logged.; deletes after: its own records (FullIndexReplay.letGo); the driver journals them; peak about 17.99 GB (ESTIMATED). About 3.11 GB if trees.bin went first and the run uses --trees none.; wall about 2.4 h with trees read (ESTIMATED from c06c061-0e0920d.log: 561 s a batch for the last round, 414 s an item for scoring). With --trees none, 1.8-2.5 h more on a quiet machine, about 7 h more under load.; bit-identical: The replay holds every item to its part; last time 93 of 93 matched to the bit. The bundle carries no commit, time or path, so its manifest should rebuild byte for byte (PREDICTION); commit it when made.
- **P2. PageLook --resume into page-look-0e0920d under the item-1 classes, for F50/F52**: writes export-full/ (about 23 MB, ESTIMATED) and replay-<commit>/replay.txt (code change 6). Records of about 227 MB are written, then let go; the size is a proxy MEASURED on the last run D.; deletes after: its records; peak about 18.12 GB (ESTIMATED). About 3.24 GB with trees.bin gone and PageLook --trees none.; wall about 3.3 h with trees read (the last run D took 11,915 s, page-look-0e0920d.log). 5.4-6.1 h with --trees none (ESTIMATED).; bit-identical: Step8 holds every item to step 8's results.ser to the bit before keeping it (PageLook.java:487-511). A resume writes no ledger line (:278-284). It needs export-first present (:297), look.txt (:207) and the main state file's mtime untouched.
- **D3. Delete trees.bin and its index (batch A, Justin's yes)**: writes nothing; deletes after: 14,877,669,295 B in 2 files; peak about 3.01 GB (ESTIMATED, with P1's and P2's outputs); wall seconds; bit-identical: It only saves time: rebuilt trees give the same bits (disk-cap check: 416,114, 829,436 and 60,743 numbers).
- **D4. Delete the main state file, the fits and the parts (Justin's yes)**: writes nothing; deletes after: 1,748,269,423 B in 36 files:
- montreal-80.states and .progress: 1,340,719,784
- c06c061's fit.ckpt and run.id: 183,690,200
- its 15 parts: 72,215,996
- step 8's fit.ckpt, run.id and 15 fold parts: 151,643,443; peak about 1.27 GB (ESTIMATED). The 2.0e9 cap holds from here on.; wall seconds; bit-identical: One-way. The main file comes back only through a 13 h regeneration on three builds (a prediction, checked against M0's segment hashes). 479595d1 and 0a2b8c88 become uncheckable. Before this, either freeze the template's guard keys or land code change 8: SiteExport reads the header (SiteExport.java:575-593), and the template uses data.rounds.80.
- **P3. Simulator part 2: 4 pilots, SimCheckPlan fill into git, p2, p3, variants, planted runs on B1 and B2**: writes summaries, checks, swings, records, ruin, trip and fan files (results, about 10 MB, ESTIMATED), and chunk folders; deletes after: its chunk folders, deleted by the driver; peak about 1.28 GB (ESTIMATED); wall 2.5-4 h, budget 4.5 h (INTEGRATION.md:620, ESTIMATED); bit-identical: Figures are the same on any thread count (SIM_CHECK.md:307-310). The summaries carry wallSeconds.
- **D5. Delete the four bundles, unless Justin keeps them**: writes nothing; deletes after: export-part1 54,632,498 and export-first 23,016,894 (MEASURED), plus P1's and P2's bundles (about 83 MB, ESTIMATED); peak about 1.11 GB; wall seconds; bit-identical: 4a424be6 (committed, SIM_CHECK.md:26), d385bccd and the new manifests become uncheckable. SiteSim reads only the run directories, not the bundles.
- **P4/P5. RiskReport and SiteAdapt risk, then SiteAdapt sim**: writes risk/*.json and *.md, site/risk-*.json, site/sim-*.json (small, ESTIMATED); deletes after: nothing yet; the four .ser files wait for P6; peak about 1.114 GB (ESTIMATED); wall under 1 h, plus seconds (ESTIMATED); bit-identical: Deterministic on one JDK. RiskMath calls Math.exp, log and pow, so the last bits may differ across JDKs.
- **P6. Final page at the tag, with the new hash text (code change 10)**: writes page-<sha>/ (about 3.2 MB); deletes after: nothing; peak about 1.12 GB; wall 3.8 s (MEASURED), plus about 7 s if adapters rerun; bit-identical: Byte for byte from runs/site (SiteBuild --check, 48 of 48, MEASURED 18 Oct). SiteExport reruns only if the guard keys changed, and that must happen before D6.
- **D6. Delete the last engine files (one-way, Justin's yes)**: writes nothing; deletes after: 996,852,349 B in 4 files:
- c06c061's results.ser: 430,694,877
- step 7's save: 202,956,286
- step 8's results.ser: 113,431,766
- wide.ser: 249,769,420; peak about 0.12 GB: results only (118.5 MB, ESTIMATED); wall seconds; bit-identical: 8ba1412b, c854767f, 6a456558 and ee3ff121 can never be checked again; no rerun regenerates them (SystemScorer.java:2456, 2590). Afterwards, any figure not already in runs/site needs a from-seeds run. Then commit BUDGET_GB = 2 (DiskBudget.java:55); `Pipeline check` confirms runs/ holds only what pipeline/results.tsv lists.
- **R0. Steady state: rebuild the page from runs/site**: writes page-<sha>/ (3.2 MB); deletes after: nothing; peak about 0.12 GB; wall 3.8 s (MEASURED); bit-identical: Byte for byte with the same --version, --date, --commit and JDK 25.0.1+8-27 (SiteManifest.java:68).
- **R1. Re-adapt the kept JSON and rebuild**: writes runs/site/*.json and a page; deletes after: the superseded adapter output; peak about 0.12 GB; wall about 7 s (MEASURED from row 10's step times); bit-identical: Same figures. The bytes differ only in the commit, path and hash fields (36-170 B, MEASURED cmp).
- **R2. From seeds: StateRun main in three segments**: writes montreal-80.states: 0.61 GB as v3 (ESTIMATED, code change 14) or 1.34 GB as v2. Shoes 0-6649 use build-d3c0b1f; the rest use a d791438-or-later build.; deletes after: the main state file, after PageLook (R7); peak about 0.72 GB with v3 (ESTIMATED); 1.34 GB with v2; wall 13.0 h (MEASURED: 26,065 + 17,238 + 3,650 s in montreal-80.log); bit-identical: PREDICTED equal to ed9e9fb4, checked against M0's per-segment hashes. With today's classes alone, 11.1% of records differ at about 1e-12, and the figures with them.
- **R3. report-final, pilot 1417, wide pilot (rerun and compared with the kept copies)**: writes text files in a compare dir, deleted after the comparison; deletes after: the compare copies; peak about 0.72 GB (ESTIMATED); wall 5.2 h + 0.8 h + 0.5 h (MEASURED); bit-identical: Figures equal at the printed digits (MEASURED for the pilot). The bytes differ in the timing lines.
- **R4. FullIndexRun c06c061 --trees none (code changes 11, 12)**: writes fit.ckpt, 186 parts and a tail (gzip); deletes after: fit.ckpt after the replay; the parts after SiteAdapt sim; peak about 1.12 GB (ESTIMATED). 2.39 GB as the code stands, which the planner refuses.; wall 39.8-46.8 h on a quiet machine (ESTIMATED: 19.8 h measured plus 48 rounds of tree building); bit-identical: Figures to the bit on 6 threads only. Measured analog: step 8's Hi-Lo against c06c061's, 153,153 of 153,153. The bytes never match, so the run is checked against M0's digest.
- **R5. Replay of c06c061: 93 items with wide, sink and the item-1 export (code changes 11-14)**: writes wide.ser, sink run-b.ser, export-part2, replay.txt; deletes after: fit.ckpt and the records; peak about 1.97 GB (ESTIMATED; 26 MB margin, the tightest step). 3.84 GB as the code stands.; wall 26.1-30.1 h (ESTIMATED); bit-identical: Last round and scoring to the bit (93 of 93 last time). The hashes are new, because the identity embeds fit.ckpt's SHA (FullIndexReplay.java:460).
- **R6. Step 7 --save --reproduce, StrategyExport part 1, KeyEdges a and a-prime, step 8 --counts --reproduce --trees none (code change 15)**: writes step 7's save, export-part1, the a and a-prime collectors, step 8's run; deletes after: each after its last reader; peak 1.44, 1.50, 1.60 and 1.70 GB (ESTIMATED); wall 4.0 h, 1 min, 14 min and 9.9-11.5 h (ESTIMATED / MEASURED); bit-identical: Figures to the bit. Each reproduction writes a labelled ledger line instead of a second look's line. The bundle is predicted to meet 4a424be6 again.
- **R7. PageLook --reproduce --trees none (runs C and D)**: writes run-c.ser, run-d/run-b.ser, export-first, export-full; deletes after: the main state file and step 8's fit.ckpt; peak about 1.94 GB (ESTIMATED). As the code stands it is refused (PageLook.java:202) and would need 2.65 GB plus trees.bin.; wall 5.8-6.6 h (ESTIMATED); bit-identical: Run C is held to step 7 and run D to step 8, both to the bit. New hashes.
- **R8. Row 10 (optimism reused), sim parts 1 and 2, LiteratureCheck, RiskReport, SiteAdapt sim, SiteBuild**: writes the runs/site inputs, sim results, the page; deletes after: the collectors after the KeyEdges report; the bundles after the sim; wide.ser and steps 7 and 8 after RiskReport; the results after SiteAdapt sim; peak from 1.18 GB down to 0.40 GB (ESTIMATED); wall about 5-7 h; bit-identical: Figures identical. 6 of the 7 runs/site files and manifest d672cb26 change in their hash, path and mtime fields unless code change 16 is made.
- **R9. Optional, last: the twin chain (twin, calibration, CountSearch x47, SearchReports, choice, splits, optimism)**: writes its kept results, to compare; deletes after: the twin and its leftovers; peak about 1.43 GB with a v3 twin (ESTIMATED); 2.16 GB with a v2 twin, which is over; wall 9.5-13.6 h (mostly MEASURED); bit-identical: The twin is predicted to equal a2f8a751 with today's classes. CountSearch's last bits may differ on another JDK. The page needs none of this: the registration is in git.

### Code changes

1. 1. RunsManifest (new src/RunsManifest.java). Writes path, bytes, ms mtime and SHA-256 for every file under runs/, links not followed. For state files it also writes a SHA-256 at given shoe boundaries, from StateStore's record offsets. Its output for today's 7,450 files is committed before anything is deleted. Proof: RunsManifestTest on a temp runs/ holding a StateRun fixture. Whole-file and segment hashes must equal MessageDigest over those byte ranges, a symlink is not followed, and the output is stable across runs.
2. 2. ResultDigest (new src/ResultDigest.java), plus ResultDiff skipping PassRecord.wallSeconds and RoundStats times (ResultDiff.java:17, 278-290). It is a SHA-256 over every figure in field order, timings left out, for results.ser, fit.ckpt, the parts, step 7's save, wide.ser and the collectors. Digests of the real files are committed before D1. Proof: ResultDigestTest. Two fixture FullIndexRuns of the same counts get equal digests although their SHA-256s differ; one ulp changed in one estimate changes the digest; ResultDiff on two fresh fixture runs reports nothing beyond.
3. 3. StateSpotCheck (new src/StateSpotCheck.java, PLAN_PAGE.md 3.3). Re-values K states per segment with that segment's frozen classes, rebuilt from git: d3c0b1f for shoes 0-6649, d791438 for 6650-39999, 26f0724 for 40000-59999, and today's classes for the twin. It compares the bits and runs on the real files before D2 and D4. Proof: StateSpotCheckTest. A StateRun fixture passes; a copy with one planted ulp is refused, naming the state.
4. 4. The item-1 fix: a split-hand ranking (SHOE_SPEC.md B2.4) in StrategyExport.java (Refit and build), ExportBundle and sim/Strategy.java. --only with --export-row also attaches the registered wide plans F43-F48 (FullIndexReplay.java:418-439). Proof: PageLookTest's 12-shoe fixture, where F1's full play ranks a deal two ways, now writes export-full and exits 0. SimReadsExportTest plays that bundle. FullIndexReplayTest shows --only with --export-row exporting the F43-F48 plans.
5. 5. Records let go when an export is refused (FullIndexRun.java:218-221, PageLook.java:532-535). Proof: FullIndexReplayTest and PageLookTest with a planted refusing export. records-let-go.txt is written, the records are gone, and exit 1 is kept.
6. 6. PageLook's resume for P2 (PageLook.java:452-480, BYTES :58). Under other classes it replays into replay-<commit>/ with only the export and Step8 hooks. So it never needs the old replay/run.id deleted, and never rewrites replay/replay.txt, replay/wide.ser or run-d/run-b.ser. Optional: --trees none (:178-181, 241-245, 460), needed only if trees.bin goes before P2. Proof: PageLookTest. A resume under another commit, with the old replay dir present, succeeds and writes export-full; cmp shows replay.txt, run-c.ser and run-d/run-b.ser unchanged; with --trees none, export-full is byte-identical to the trees-read run's.
7. 7. Budget constants measured again, and the cap. BUDGET_GB = 2 (DiskBudget.java:55), plus ITEM, REPLAY_ITEM, PageLook.BYTES, KeyEdgesRun.BYTES (:53), CeilingReport SAVE_BYTES and SAVE_MARGIN (:355, 437-468, now 3e9 B free asked of the volume) and StateRun.SHOE_BYTES (:26). Proof: DiskBudgetTest and FullIndexRunTest. A fixture run's measured writes stay under its estimate, and an estimate over the room is refused before anything is written.
8. 8. SiteExport without the state file (SiteExport.java:575-593). A --states-header FILE takes a committed record of the 271-byte header and the identity line, written by RunsManifest, so data.rounds.* comes from the header's seed. The template uses data.rounds.80. Proof: SiteExportTest. cmp shows the output with --states-header byte-identical to the output with --states.
9. 9. The driver: new src/Pipeline.java, with pipeline/steps.tsv (the run order with commits, commands, inputs, outputs, kinds and measured bytes) and pipeline/results.tsv. It has three commands: plan (dry run with each step's peak; refuses the whole plan before step 1), run and check. For each step it freezes classes in a temp dir outside runs/, calls DiskBudget.require at 2e9, and runs a subprocess with CCEC_DISK_BUDGET_GB=2. A watcher measures DiskBudget.bytes every 5 s and stops the step if it passes the cap. It takes the real exit status, not the rc= echo, which always reads 0. It verifies outputs by SHA-256 or ResultDigest, deletes journaled intermediates after their last reader, and resumes from the journal. It never deletes a file it did not create in this run. Proof: PipelineTest on a toy graph of fake steps. (a) A plan over the cap is refused with nothing run. (b) Each intermediate is gone right after its last reader, while a same-named file that existed before is untouched. (c) A step writing past the cap is stopped. (d) A resume after a kill skips verified steps. (e) At the end, DiskBudget --without results.tsv leaves 0 B.
10. 10. The page's hash text and SiteManifest (SiteManifest.java:115, 121 give two files the same name, results.ser; the template's hash paragraph). The page lists only files that exist. A deleted file shows as deleted after use, with its SHA-256 at the time and its figures digest, and no check line. The 1e-12 sentence is corrected. This is needed before P6. Proof: SiteBuildTest. SHA256SUMS names only files in the bundle with no duplicate names, and `shasum -a 256 -c SHA256SUMS` passes on the built page.
11. 11. One copy of each run's results (FullIndexRun.java:341-350). It writes the parts plus a 0.49 MB tail (perfect values and rounds), and CeilingReport.read and FullIndexPeek.read merge them; or it keeps results.ser only and slices items (FullIndexReplay.java:546-551, 762). Proof: on a fixture run the merged parts deep-equal results.ser with equal ResultDigests. On the real files, run before D1 deletes 171 parts, the merged stream must hash to 8ba1412b (measured once, not committed).
12. 12. gzip of the files the engine writes, read either way by sniffing the 1f8b magic (FullIndexCheckpoint.java:176-187, CeilingReport.java:491-507, KeyEdges.java:285-317, WideReport.java:155-163). Proof: plain and gzip files round-trip with equal ResultDigests, and today's plain files still read.
13. 13. Slim collectors (KeyEdges.Collector.save, ReplayHooks.java:183-199): the Entry copies the report never reads are dropped, 1,028.8 of the 1,428.2 MB. Proof: KeyEdgesRun report on fixture collectors, full against slim, gives an identical CSV and identical JSON figures.
14. 14. StateStore v3 plus StateConvert, with StateRun --first-shoe writing v3 directly. Only legal slots are stored, byte planes are deflated, and the header holds the v2 identity and SHA-256 (StateStore.java:32, 67-69, 208-294; StateRun.java:26, 69; FullIndexRun.stateFile :392-395). Proof: StateConvertTest decodes a fixture file to its exact v2 bytes. On the real main file, run before D4, the decode must hash to ed9e9fb4, and the whole-file ratio is measured; the 2 GB from-seeds plan rests on that ratio.
15. 15. A reproduce mode for ComparisonReport --save, FullIndexRun --counts and PageLook (CeilingReport.java:416, FullIndexRun.java:325, PageLook.java:202, 207). It refuses unless the classes commit, settings, both blobs and the state hash equal the original look's. It writes a 'REPRODUCTION of the look of <time>' ledger line. It keeps its output aside unless the ResultDigest matches the committed one, and SiteLedger prints reproductions apart from looks. Proof: on the fixture registration, a matching reproduction passes with its labelled line, a changed blob is refused, and a mismatching digest leaves the page's figures as they were.
16. 16. Optional; it changes every hash once. Timings move out of the hashed files into a sidecar (Result.seconds, PassRecord.wallSeconds, RoundStats); the state file is named by its SHA-256 instead of path and ms mtime (FullIndexRun.java:392-395); SiteInput writes paths relative to runs/ (SiteInput.java:45-52). Proof: two fixture FullIndexRuns write byte-identical results.ser, and a moved state file keeps every downstream identity.

### For the reader

At rest, 3 of the 16 lines in the page's SHA256SUMS name files that would exist nowhere:
- 8ba1412b and 6a456558, both listed as "results.ser";
- c854767f, report-ceiling-97bf834.ser;
- a fourth, 824f0769, if the key-edges JSON is not kept.

Ten more hashes stay printed inside kept files but can no longer be checked:
- ee3ff121 (wide.ser)
- 56148393 and c649f44f (the a and a-prime collectors)
- 2939a4ea (run-c)
- 3c1866b6 (the sink)
- 3fe732ce (run-d)
- 479595d1 (fit.ckpt)
- a2f8a751 (the twin)
- the bundle manifests 4a424be6 and d385bccd, if the bundles go.

The states file's SHA-256, ed9e9fb4, was never on the page and would be recorded only in the committed manifest.

The sentence "You can test it by reproducing the file: the states file byte for byte, the saved results only to within 1e-12" is wrong both ways:
- The figures reproduce to the bit with 6 threads (measured for one fit).
- The .ser files never reproduce, because they record how long each step took.
- The states file coming back byte for byte is a prediction, which StateSpotCheck turns into a sample measurement.

Proposed text, in Justin's voice:

"Three files the figures were computed from (the two results.ser files and report-ceiling-97bf834.ser) were deleted after use to keep this project's disk use under 2 GB. The SHA-256s listed for them are what they were; nobody can check those now. What you can check: every figure is in results.json at full precision. The figures digest of each deleted file, with timings left out, is committed at pipeline/digests.tsv. Rerunning the pipeline from the seeds takes about five to six days on one machine. It should give the same figures to the bit, but not the same files, because those files record run times. The first 6,650 shoes of the states file need the build that made them (inferred as d3c0b1f)."

Still checkable as before:
- the other 13 SHA256SUMS lines;
- `SiteBuild --check`;
- results.json's full-precision numbers (99,955 of 101,407 have 15 or more decimals).

A regenerated chain would change 6 of the 7 runs/site hashes and the page manifest d672cb26, with the figures unchanged, unless code change 16 is made. A reproduced look would show "REPRODUCTION" lines in the ledger box.

### Decisions

- Strict results-only (0.12 GB at rest), or also keep c06c061's results.ser, step 7's save and step 8's results.ser (0.75 GB more, 0.86 GB at rest, still under 2 GB)? Keeping them keeps three hashes the page cites checkable. A template, literature or ceiling change then reruns in minutes instead of after a 5-6 day regeneration, and the one look never needs redoing. Strict deletes the only copies of the look's records.
- The one-time overshoot: runs/ cannot be at or under 2 GB until PageLook has run and batch D4 is deleted. Option (a): keep trees.bin until P2. runs/ sits at about 18 GB until the item-1 fix is written and about 6 h of runs finish; this is the fastest and needs no PageLook --trees none. Option (b): delete trees.bin now and add PageLook --trees none. That means about 3.0-3.2 GB, with 4-5 h more of runs on a quiet machine (about 15 h under load).
- Approve deletion batches D1-D6 one at a time (lists by file in /private/tmp/results-only-pipeline-93809/batches.tsv; D1 2.71 GB, D2 2.77, D3 14.88, D4 1.75, D5 0.08 plus the new bundles, D6 1.00). Moving them to the macOS Trash keeps an undo but frees the disk only when you empty it; the disk has 3.6 GB free. The driver's own intermediates are deleted outright.
- Is the 77.6 MB key-edges JSON and CSV a result, or only the 0.47 MB page input made from it? Dropping it also makes 824f0769 uncheckable.
- Keep the four simulator bundles (about 0.16 GB) as results? With them, the simulator's results can be rerun and the committed manifest 4a424be6 checked without the 5-day chain.
- Freeze the template's guard keys, the literature claims and the ceiling paragraphs before D6. Under strict results-only, changing any of them afterwards needs a from-seeds regeneration.
- How much regeneration to build. (a) Only page rebuilds: code changes 1-10. (b) Full from-seeds within 2 GB: add 11-15, with an estimated peak of 1.97 GB (26 MB margin) and about 5-6 days per run. (c) Also make the file hashes reproducible: change 16, which changes every hash once.
- Prove v3 and the one-copy merge on the real files before D4 and D1, which needs code changes 11 and 14 first? Or accept proving them only on a regenerated chain (13 h + 40 h)?
- Approve the new hash text on the page: hashes of deleted files recorded but not checkable, a committed figures digest, and the 1e-12 sentence corrected.
- Does 2.0e9 cover only runs/, which is what DiskBudget counts, or also the Trash, swap (1.6-2.2 GB) and .worktrees (about 0.5 GB)?

## Results-only, no Drive

**Short answer.** At rest, yes: 0.107 GB today, about 0.12 GB once the pending results exist. On the way there, not at every moment. runs/ holds 23.29 GB, and the pending runs need 2.82 GB of files that exist only once, so the cap can start only after PageLook and batch D4. After that:
- **As the code stands,** the program regenerates within 2 GB only the page and the adapters.
- **With changes 11-15,** the whole main chain fits at an estimated 1.97 GB peak, in about 5-6 machine-days. The figures should match to the bit; the .ser hashes never will.

### The driver
`src/Pipeline.java`, with `pipeline/steps.tsv` (the run order: commit, class, arguments, inputs, outputs, kind, measured bytes, ledger effect) and `pipeline/results.tsv` (every result's bytes and SHA-256 or figures digest).

- **`plan --targets ...`** lists the steps whose outputs are missing. Each step's peak is what runs/ holds, plus the live intermediates, plus that step's outputs. If any step is over 2.0e9, it refuses the whole plan before running anything.
- **`run`** does this for each step:
  - freezes classes from `git archive <commit>` into a temp dir outside runs/;
  - calls `DiskBudget.require(runs, expected, 2e9, step)`;
  - runs the step with `CCEC_DISK_BUDGET_GB=2`, while a watcher measures `DiskBudget.bytes` every 5 s and stops the step if it passes the cap;
  - takes the real exit status, not the `rc=` echo, which always reads 0;
  - verifies the outputs by SHA-256 or ResultDigest;
  - then deletes every intermediate this run created and journaled once its last reader has run.

  It never deletes a file it did not create in this run.
- **`check`** confirms runs/ holds only what results.tsv lists.

### Migration from today's 23.29 GB

| Batch | When | Bytes | What |
|---|---|---|---|
| D1 | now | 2,710,065,959 (7,101 files) | superseded runs, replay records (both), 171 unused parts, unread wide files, twin-chain leftovers, classes dirs, sim chunks |
| D2 | after M0 | 2,769,420,814 (7) | twin; collectors a, a′, sink, run-c, run-d |
| D3 | after P2 | 14,877,669,295 (2) | trees.bin and index |
| D4 | after P2 | 1,748,269,423 (36) | main state file, c06c061's fit and 15 parts, step 8's fit and fold parts |
| D5 | after P3 | 77,649,392 + about 83 MB new | the four bundles |
| D6 | after P6 | 996,852,349 (4) | c06c061's results.ser, step 7's save, step 8's results.ser, wide.ser |

All sizes are MEASURED by stat; the per-file list is in `/private/tmp/results-only-pipeline-93809/batches.tsv`. D1 through D6 add up to the 23,179,927,232 B of intermediates.

**Order:**
1. **M0, before any deletion:**
   - commit a manifest of all 7,450 files, with per-segment hashes of both state files (ed9e9fb4, a2f8a751 and trees.bin's hash are committed nowhere today);
   - commit figures digests of every engine file that will go;
   - run StateSpotCheck;
   - check that each classes dir rebuilds from git.
2. D1, D2 → 17.81 GB.
3. Code changes 4-8, then P1 and P2 with trees.bin read. Their budget is raised to about 18.5 GB, and the log says so.
4. D3 and D4 → about 1.27 GB. **The 2 GB cap holds from here on.**
5. P3, D5, P4, P5, then P6, the final page with the new hash text.
6. D6 → about 0.12 GB. Commit `BUDGET_GB = 2`.

The alternative deletes trees.bin before P1 and adds PageLook --trees none. That keeps the overshoot at 2.93-3.24 GB instead of about 18 GB, for 4-5 h more of runs on a quiet machine. No route avoids an overshoot: the pending set plus the results is already 2.93 GB.

### What must happen before deleting
- P1 needs c06c061's fit.ckpt, run.id and 15 parts, plus the main state file.
- P2 needs:
  - the main state file with its mtime untouched;
  - step 7's save, and step 8's fit.ckpt, run.id, fold parts and results.ser;
  - look.txt and export-first (PageLook.java:207, 297);
  - trees.bin, or PageLook --trees none;
  - as the code stands, the old `replay/run.id` gone (:466, FullIndexReplay.java:459). Change 6 removes this need.
- Freeze the template's guard keys before D4 and D6, or land change 8. SiteExport reads the state file's header (SiteExport.java:575-593), and the template uses data.rounds.80.

### What cannot meet 2 GB
- **Every moment before D4.**
- **Steps that are over even with nothing else on disk, as the code stands:** c06c061 2.39 GB, KeyEdges a and a′ 2.06, StrategyExport 2.04, the 93-item replay 3.84, PageLook 2.65 (and it needs trees.bin), SiteExport 2.10. The planner refuses them.
- **With changes 11-15,** the maximum is 1.97 GB at the replay (ESTIMATED, 26 MB margin). That margin rests on a v3 ratio measured on the first 200 MB only. The twin chain also needs a v3 twin, or it peaks at 2.16 GB.

### What cannot be bit-identical
- **Every .ser and checkpoint file.** They store run times (SystemScorer.java:2456, 2590; FullIndexFit.java:425, 1647-1650).
- **The main state file,** except with build-d3c0b1f for shoes 0-6649 (a prediction).
- **Every downstream identity.** Each one names the state file's path and ms mtime (FullIndexRun.java:392-395), so a regenerated chain changes 6 of the 7 runs/site hashes unless change 16 is made.
- **Fits on any other thread count than 6.**
- **The one look.** PageLook refuses a second look (:202), and steps 7 and 8 append ledger lines, so redoing it needs change 15 with labelled REPRODUCTION lines.

### What Justin gives up under strict results-only
- Checkable hashes: 3 on the page (4 if the key-edges JSON goes), 8 inside kept files, and 2 bundle manifests if the bundles go. Only digests remain.
- Cheap reruns: after D6, any new figure, guard key or literature claim costs a from-seeds run of 128-149 h on a quiet machine, and code changes 11-15 first.
- The tree store, for good: about 44 h more per full regeneration.
- The twin's original bytes. A rebuild is predicted, not proven, to match.

Scratch work: `/private/tmp/results-only-pipeline-93809/bin/batches.py`, `regen_model.py`.

## Analysis: bit-reproducibility

## Bit-reproducibility of the page's chain

**Short answer.** Rebuilding the chain from the seeds would reproduce every published figure to the bit, with conditions, but almost none of the cited SHA-256s. The figures depend only on the arithmetic, the seeds and one thread count. The hashed files also store wall-clock timings, timestamped ledger lines, absolute paths, the state file's mtime and other files' SHA-256s. Once the intermediates are deleted, their hashes can never be checked again by anyone, and neither can every hash that embeds them.

**What comes back to the bit**
- **A fresh full-play fit (MEASURED today, scratch probe in `/private/tmp/bitrepro-84150/probe`).** Step 8's Hi-Lo control and c06c061's Hi-Lo were fitted under different classes, beside different counts, both on 6 threads. They agree to the bit:
  - 153,153 of 153,153 full-play numbers;
  - both first-decision entries;
  - 26,400 later-cell gains and SEs;
  - 60,743 perfect values.
  
  The only differences are 57 of 57 `wallSeconds`. CeilingResults had only checked this to 1e-12.
- **Last round and scoring.** Four replays matched to the bit, none merely within tolerance: 93/93, 1/1, 15/15, and 2/2 with every tree rebuilt at 03b116f.
- **First decisions.** They do not depend on the thread count (SystemScorer.java:2532-2535). Step 7 on 7 threads equals c06c061 on 6 to the bit, and so does a-prime.
- **The pilot.** The 304901c rerun reprints every figure of pilot-1417-100.txt identically (MEASURED diff with the timings removed).
- **Derived steps.** Reruns differ only in the commit and the input path and hash fields: 0 bytes for results-*.json, 0 for the key-edges CSV, 36 B for optimism, 37 B (offsets 29-67) for run-c.ser (MEASURED cmp).
- **State values (code reading, not yet tested).** The engine's arithmetic is unchanged from d791438 to HEAD (git diff). So segments 2-3 and the twin should come back byte for byte with today's classes. Segment 1 (shoes 0-6649, 11.1% of records) needs build-d3c0b1f, whose order differs at about 1e-12. That build's birth time matches the log's to the second.

**What does not come back**
- **The .ser and checkpoint files:** results.ser, fit.ckpt, the parts, step 7's .ser and step 8's results.ser. They hold `Result.seconds`, `PassRecord.wallSeconds` and the RoundStats CPU and wall times, so 8ba1412b, 479595d1, c854767f and 6a456558 can never be regenerated.
- **Text outputs:** report-final's Time section, the pilots, the peeks' 'read on' line, step 7's md (it prints the ledger), the calibration and search reports, and the sim summaries.
- **Another thread count:** full-play fit sums are added per thread, so another T is ESTIMATED to land within 1e-12 (untested). The identity names T.
- **Hashes chained to the deleted files:** the collectors embed results.ser's SHA, the replay embeds fit.ckpt's, and every run embeds montreal's path and mtime. From a rebuilt chain, 6 of the 7 runs/site hashes and the page manifest d672cb26 change, with identical figures.
- **The ledger and the page look:** steps 7 and 8 add ledger lines, and a fresh page look is refused (PageLook.java:202). So run-c and run-d can never be remade.
- **One exception:** export bundles carry no commit, time or path, so a rebuild should meet part 1's committed manifest 4a424be6 again.

**Cited hashes that would vanish at rest**
- **On the page:** 8ba1412b, 6a456558 and c854767f, plus 824f0769 if the key-edges JSON is treated as an intermediate.
- **Inside kept JSONs:** ee3ff121, 56148393, c649f44f, 2939a4ea, 3c1866b6, 3fe732ce, 479595d1 and a2f8a751.
- **The states file:** cited only by path, size and mtime. Its SHA, ed9e9fb4, was re-measured today and appears only in the working papers on unmerged branch disk-cap.

**What the page would then promise, and what still holds**

The page says 'check every download with `shasum -c SHA256SUMS`' and 'the states file byte for byte, the saved results only to within 1e-12'. At rest, three or four of those lines name files that no longer exist, and two of them are both called results.ser. The states file's hash is not on the page. The 1e-12 sentence is wrong both ways: the figures reproduce to the bit, and the file never does.

What a reader could still check:
- the kept text and JSON hashes;
- the five git files;
- `SiteBuild --check`;
- a rerun's figures against runs/site and results.json. These hold full-precision doubles: 99,955 of 101,407 numbers in results-7c1d40e.json have 15 or more decimals.

**To make the chain reproduce its hashes**
1. Keep timings out of hashed files, or cite a committed digest of the figures that leaves timings out.
2. Name the state file by content, not by path and mtime.
3. Make the full-play fit's sums independent of the thread count.
4. Rebuild the main state file per segment (d3c0b1f, then any later build), and add the planned StateSpotCheck.
5. Add a reproduce mode for steps 7 and 8 and the page look that checks the existing ledger line instead of adding one.
6. Change the page's hash text to match what exists.

Until then, the rule 'keep only results' and the page's current hash claims cannot both hold.

### Per step

- **S02 StateRun main -> montreal-80.states (seed 20260928, three segments)**: Within one build, the file's bytes should not depend on threads. Each state is valued on its own thread-local engine. Values go to fixed slots (StateValuer.java:83-95). The kept dealer distributions change 'no value, not even in the last bit' (ExactRound.java:626-640). Shoes are written in shoe order (StateRun.java:95-101). The header holds no time (StateStore.java:53-58; header xxd MEASURED). Across builds: ExactRound's arithmetic is the same from d791438 to HEAD. git diff d791438..26f0724 changes only StateValuer.legal; 26f0724..c06c061 moves the dealer loop into dealerDistribution()/fallTable() with the same operations in the same order (Arrays.fill(out,1,WIDTH) = fill(out,at,at+OUTCOMES)); nothing changes after c06c061 (MEASURED git diff). Segment 1 is different: shoes 0-6649, 6,769 states (11.1%, montreal-80.log:272), which build-d3c0b1f valued. That build adds in another order and agrees only to about 1e-12 (PLAN_PAGE.md:707-709; ed3ac67 'unchanged but for rounding'). build-d3c0b1f's birth time, 09-28 16:13:37, equals the birth of montreal-80.log and .states to the second (MEASURED stat). That strengthens 'probably d3c0b1f' to near certain. Verdict, a PREDICTION never tested: the same bytes as ed9e9fb4… if shoes 0-6649 are run with build-d3c0b1f (26,065 s) and 6650-59999 with any build from d791438 on (17,238+3,650 s; montreal-80.log). Rebuilt with today's classes alone, 11.1% of the records differ at about 1e-12 and the SHA changes. Either way the absolute path and the ms mtime 1790724357600 must be restored (FullIndexRun.java:392-394), or every identity downstream changes. SHA re-measured today: ed9e9fb45de1… (MEASURED shasum). It is recorded in full only in disk/2026-10-18-2gb-working-papers.md:906,987 on unmerged branch disk-cap (b0ec21b), not on the page.
- **S07 StateRun twin -> twin-80.states (seed 20261007)**: The build is not recorded, but the engine can be bounded. The twin ran at 0.19 s per valued state on 7 threads (twin-80.log last line), against 3.85 s for d3c0b1f's segment and 0.18 s for 26f0724's. So it is a d791438-or-later engine. The commits made just before and as it started (fc327dd 03:52, f9d39f9 04:03) have an engine identical to c06c061's (git diff empty, MEASURED). The count-design build that probably ran it was recompiled on 10-08, so its classes are gone. Verdict, a PREDICTION: today's classes should give the same bytes, a2f8a751… (re-measured today). That fails only if the run used uncommitted engine edits. One 3.2 h rebuild (11,637 s) settles it, since a2f8a751 is cited in the optimism header (CeilingResults.java:1400). The twin's identity is path plus SHA, with no mtime.
- **S03 ComparisonReport -> report-final-26f0724.md (718a8e23)**: Thread count does not matter. Each count is scored whole on one thread (ComparisonReport.java:286-305), and resamples go to their own slots (SystemScorer.java:2532-2535). A rerun with build-26f0724 (rebuildable identical, PLAN_PAGE.md:699-702) should print the same figures, but the hash is lost: the '## Time' section (line 1838 on) and its per-count seconds change on every run. Its figures are tied to c06c061's. SiteExport's checkPrinted passed (SiteExport.java:370-373), and KeyEdgesPrinted found 7,646/7,646 per-key lines equal at the printed digits (row10-partial-7c1d40e.log.out:8). Both are comparisons at the printed digits, not bit for bit.
- **S04 FullIndexPilot -> pilot-1417-100.txt (0018be90)**: Its classes are not recorded (probably cbdd02b, run from a worktree). MEASURED: the 304901c --wide rerun (pilot-1417-100-wide-304901c.txt) reprints every figure identically: all 100 resample lines once the refit and scored seconds are blanked, and every non-timing table. Only the CPU and wall columns and the added wide sections differ. Both runs used 6 threads, and the pilot uses FullIndexFit, whose sums depend on the thread count. Figures reproduce at the printed digits; the bytes and 0018be90 never do.
- **S05 FullIndexRun c06c061 -> results.ser (8ba1412b), fit.ckpt (479595d1), 186 parts, trees.bin**: Figures: to the bit with the same classes and --threads 6. MEASURED today with a read-only scratch probe (/private/tmp/bitrepro-84150/probe/BitProbe.java and HistProbe.java, compiled against classes-7c1d40e). Step 8's Hi-Lo control is a fresh fit at 97bf834, in a 5-count run, 6 threads, reading c06c061's trees. Set beside c06c061's Hi-Lo, all of these are equal to the bit: 153,153 of 153,153 full-play numbers (3 pens x 51 metrics x 1,001), 2 x 153,153 first-decision numbers, 26,400 later-cell gains and SEs, and 60,743 of 60,743 perfect values. ResultDiff finds 59 'beyond': 2 list lengths (step 8 adds own-value rows) and 57 of 57 PassRecord.wallSeconds. So the passes reproduce whatever counts are fitted beside them. Bytes: never. The files hold Result.seconds (SystemScorer.java:2456, 2590), PassRecord.wallSeconds (FullIndexFit.java:425) and RoundStats wall and CPU times (FullIndexFit.java:1647-1650). So no rerun gives 8ba1412b, 479595d1 or the parts' hashes. Threads: thread k takes shoes k, k+T, …, and the per-thread sums are merged in thread order (FullIndexFit.java:320-322, 1946-1980). Another T is ESTIMATED to land within 1e-12; this is untested, and a tied cell could flip. The identity names T (FullIndexRun.java:425), so a replay refuses another T (FullIndexReplay.java:539-545). trees.bin is speed only: rebuilt trees give the same bits (MEASURED, S32).
- **S06 FullIndexPeek -> peek-c06c061-final.txt (cfa5d96a), fullplay and choice peeks**: These are deterministic functions of results.ser, apart from line 1's 'read on <timestamp>' (MEASURED head). Figures reproduce; bytes do not.
- **S08-S11 calibration, CountSearch x47, SearchReport, choice, bounds-d6a005c, starts**: All read only the twin. Their text embeds timings: calibration-b99ccbf.md (8 timing lines), search-0b85be2.md (14), bounds 'scored in 14 s' (MEASURED grep), so no hash reproduces. Figures are ESTIMATED to be deterministic given the classes: Cma is seeded with seed+run.hashCode() (CountSearch.java:559). But Cma uses Math.exp/log/pow (3 calls), so the last bits may differ on another JDK. None of these is needed to rebuild the page: the registration is in git (custom-counts*.json).
- **S13 step 7 -> report-ceiling-97bf834.ser (c854767f) and .md (18763003)**: Figures are thread-independent and reproduce to the bit. MEASURED evidence: step 7 at 97bf834 on 7 threads rescored Hi-Lo, Hi-Opt II and Red Seven equal to c06c061's 6-thread rows to the bit (report-ceiling-97bf834.md:5). The .ser can never be reproduced byte for byte: it saves List<Result> with Result.seconds (CeilingReport.java:499), and its identity holds the state file's path and mtime (:492). The .md prints the ledger, its own timestamped line included (lines 1081-1089), so a rerun changes it. A rerun also appends a ledger line in git (COUNT_DESIGN.md:1212-1214). That changes the page-cited 8dfb83d1 and site/ledger.
- **S15 step 8 -> fullplay-main-97bf834/results.ser (6a456558), fit.ckpt, parts**: Same as S05: figures to the bit with classes-97bf834 and 6 threads; bytes never (timing fields). A rerun appends a ledger line as its passes start (FullIndexRun.java:325). The page look (run D) replayed it 15/15 to the bit (page-look-0e0920d/replay/replay.txt).
- **S16 row 1 and S30a-c CeilingResults full, optimism, data; BoundRows preview**: Deterministic given their inputs. MEASURED cmp across reruns: the optimism .properties and .md at 304901c and 7c1d40e differ in 36 B, line 1, the commit. The ceiling JSON at 0a447de and 7c1d40e differs in 170 B: the commit and the optimism and full-report paths and hashes. The full report differs in 39 B, the commit line. They cannot be rerun once results.ser, step 7's .ser and step 8's results.ser are gone. A chain rebuilt from the seeds gives identical figures but new bytes, because the optimism header and the ceiling JSON embed those inputs' SHA-256s.
- **S17 gate 0 and S25 replay -> wide.ser (ee3ff121), sink run-b.ser (3c1866b6), replay.txt**: The replays reproduce the last round and the scoring to the bit: 93/93 at 0e0920d, 1/1 for gate 0, and 60,743 of 60,743 perfect values (replay.txt files). The strategies come from fit.ckpt and the threads must equal the source's 6, so these replays do not test the passes. Hook outputs are deterministic. MEASURED: run-c.ser and wide-first.ser, made by the same code under 0e0920d and under 0a447de, differ in 37 bytes, all at offsets 29-67, the commit. But the replay's identity holds fit.ckpt's SHA-256 (FullIndexReplay.java:460), and that SHA can never be regenerated. So wide.ser and run-b.ser from a fresh chain would have the same figures and new bytes. spreads-7c1d40e.json embeds 479595d1, ee3ff121 and the mtime 1790724357600 (MEASURED grep), so b86622c4 would change too.
- **S18 KeyEdgesRun a / a-prime -> collectors (56148393, c649f44f)**: Figures were checked to the bit against c06c061: run A at 1 thread, and A' at 7 threads, which printed 'every refitted figure is c06c061's to the bit' (queue-304901c.out). The collector bytes need classes-304901c, the same state identity, and results.ser's exact bytes, because the identity embeds sha256(published) (KeyEdgesRun.java:198-203). Once results.ser is gone, these hashes cannot be regenerated. The key-edges CSV (figures only) was identical between the 0a447de and 7c1d40e reports, and the JSON differed in 39 B (MEASURED cmp).
- **S19 wide pilot, S20/S21 SplitMargin**: Wide pilot: as S04. Its figure lines are deterministic and its timings are not. SplitMargin: the tsv and txt headers carry seconds and the bodies are ESTIMATED deterministic (restarts seeded 20260929). Both are results kept at rest, so neither needs a rerun.
- **S22-S24 StrategyExport part 1, sim pilot, sim part 1**: The bundle holds no commit, time or path. MEASURED: grep finds none, and chart.tsv is byte-identical between the 0e0920d and 0a447de exports. Its manifest SHA 4a424be6 is committed (SIM_CHECK.md:26). So a re-export from bit-identical inputs should meet the committed hash again (PREDICTION). It is the one cited hash a chain rebuilt from the seeds can match. The sim's figures are the same to the bit on any thread count (SIM_CHECK.md:307-310, D6 test). summary-p2.json and summary-p3.json carry wallSeconds, so their bytes differ; checks, depth, ev-by-key and swings carry none.
- **S27/S28 PageLook -> look.txt, run-c.ser (2939a4ea), run-d/run-b.ser (3fe732ce), export-first (d385bccd)**: A fresh look is refused: 'it is made once' (PageLook.java:202). So run-c and run-d cannot be regenerated by any rerun as the code stands, and neither can the key-edges JSON that hashes them. The outputs are deterministic apart from the commit (MEASURED, 37 B). export-first is a bundle like S22, but its manifest d385bccd is not yet committed.
- **S30 row 10 adapters, SiteExport, WideReport, KeyEdges report, LiteratureCheck**: Deterministic given their inputs. MEASURED: results-0a447de.json and results-7c1d40e.json differ in 0 bytes (SiteExport records the source commit c06c061, not its own). The site ceiling, spreads and key-edges JSONs differ only in the commit and the source path and SHA (105, 75 and 138 B). LiteratureCheck prints '12 s.' (literature md:298) and hashes report-ceiling-97bf834.ser. Every adapter writes absolute paths (SiteInput.java:45-52). From a chain rebuilt from the seeds, 6 of the 7 runs/site hashes change (all but evidence), and with them the manifest d672cb26. The figures stay bit-identical.
- **S31 SiteBuild -> page-613255a**: Byte for byte from the 7 runs/site inputs. The 18 Oct investigator MEASURED 48/48 files with SiteBuild --check under a sandbox that blocked reads of the project folder. That needs the same --version, --date and --commit and Java 25.0.1+8-27 (SiteManifest.java:68).
- **S32 disk-cap check (03b116f, --trees none)**: MEASURED: part-1-2 (416,114 numbers) and part-23-2 (829,436) equal to the bit, and 60,743 of 60,743 perfect values, with every tree rebuilt. So the tree store changes no bit, and today's classes reproduce the last round.
- **P1-P6 pending**: P1 and P2 bundles: byte-reproducible like S22 (PREDICTION). Their manifests should be committed when made. P3 sim: thread-independent figures; summaries hold timings. P4 RiskReport: RiskMath calls Math.exp/log/pow (13 uses). It is deterministic on one JDK and machine; across JDKs the last bits may differ (ESTIMATED). It also hashes deleted .ser inputs. P6: SiteBuild reproduces byte for byte.
- **Cost of the whole page chain from seeds**: About 70 machine hours with a tree store, ESTIMATED as the sum of the logged figures: states 13.0 h, twin 3.2 h, report-final 5.2 h, c06c061 19.8 h, step 7 about 4 h (estimated), step 8 5.3 h, replay 14.9 h, KeyEdges 0.3 h, look C and D 3.7 h, splits and pilots about 1 h. A 2.0e9 cap rules out the 14.9 GB tree store; without it, roughly +23 h on c06c061 alone (working papers). The page look is refused regardless.

### Blockers

- No rerun can ever reproduce the SHA-256 of results.ser (8ba1412b), step 8's results.ser (6a456558), step 7's .ser (c854767f) or fit.ckpt (479595d1). The files store wall-clock timings: Result.seconds (SystemScorer.java:2456, 2590), PassRecord.wallSeconds (FullIndexFit.java:425) and RoundStats wall/CPU (FullIndexFit.java:1647-1650). MEASURED: two fits of Hi-Lo with bit-identical figures differ in 57 of 57 wallSeconds. Deleting these files under the results-only rule makes those page-cited hashes permanently uncheckable.
- The hashes of the kept results lock onto those files. KeyEdgesRun's identity embeds results.ser's SHA (KeyEdgesRun.java:198-203). The replay's identity embeds fit.ckpt's SHA (FullIndexReplay.java:460). Every identity embeds the state file's absolute path and ms mtime (FullIndexRun.java:392-394). spreads-7c1d40e.json contains 479595d1, ee3ff121 and 1790724357600; results-7c1d40e.json contains 8ba1412b, c854767f, 6a456558 and the mtime (MEASURED grep). So a chain rebuilt from the seeds gives bit-identical figures but changes 6 of the 7 runs/site hashes and the page manifest d672cb26.
- The page look cannot be redone: PageLook.java:202 refuses a second look. So run-c.ser (2939a4ea) and run-d/run-b.ser (3fe732ce) can never be regenerated once deleted, and the key-edges JSON (824f0769) cannot be rebuilt without them. Rerunning step 7 or step 8 appends timestamped ledger lines (FullIndexRun.java:325; COUNT_DESIGN.md:1212-1214), which changes the git ledger and its page-cited hash 8dfb83d1.
- The published page text would be false at rest. It says 'Check every download at once with shasum -a 256 -c SHA256SUMS' and 'You can test it by reproducing the file: the states file byte for byte, the saved results only to within 1e-12'. Three of the 16 SHA256SUMS lines (four if the key-edges JSON goes) name files that would exist nowhere, two of them both called results.ser (SiteManifest.java:115,121). The states file's SHA is not on the page at all. The 1e-12 sentence is wrong both ways: the figures reproduce to the bit (MEASURED), and the file never does.
- Reproducing montreal-80.states byte for byte needs build-d3c0b1f for shoes 0-6649 and any build from d791438 on for the rest, then the mtime set back to 1790724357600 ms at the same absolute path. Only a prediction: no StateSpotCheck exists, and today's classes alone change about 11.1% of the records at about 1e-12. Its SHA ed9e9fb4… is recorded only on unmerged branch disk-cap (working papers :906, :987).

### Code changes

- Keep timings out of the hashed artifacts. Make SystemScorer.Result.seconds, FullIndexFit.PassRecord.wallSeconds and RoundStats.{treeCpu,walkCpu,splitCpu,wall} transient, or write them to a sidecar or the log. Then results.ser, fit.ckpt, the parts and step 7's .ser become byte-reproducible for the same classes, threads and state identity. This changes their hashes once, in a new tagged run.
- Add a committed figures digest, e.g. ResultDigest: a SHA-256 over every number ResultDiff walks, in field order, with timings excluded. The page would cite that beside or instead of each .ser's SHA, so a reader's rerun can match it after the file is deleted. Also teach ResultDiff (ResultDiff.java:17) to skip PassRecord.wallSeconds and RoundStats, so it can compare a fresh run, not only a replay (MEASURED: 57 false 'beyond').
- Use content-addressed identities. FullIndexRun.stateFile (FullIndexRun.java:392-394) should name the state file by SHA-256, cached with its .progress, plus seed, shoes and bytes, not by absolute path and ms mtime. SiteInput.java:45-52 should record paths relative to runs/. A rebuilt or moved file then keeps every downstream hash.
- Make FullIndexFit's sums independent of the thread count. Accumulate in fixed shoe blocks reduced in block order, as the simulator's chunks are (SIM_CHECK.md:307-310), instead of thread k taking shoes k mod T (FullIndexFit.java:1946-1980). Then drop 'threads' from the identity (FullIndexRun.java:425).
- Record the commit of each StateRun segment in the progress file or a sidecar. Write the planned StateSpotCheck (PLAN_PAGE.md 3.3) to re-value K states per segment with that segment's frozen classes and compare the bits. Commit ed9e9fb4… and a2f8a751… to main with a committed tool. One rebuild of the twin with today's classes (3.2 h) settles its build.
- Add a reproduce mode for ComparisonReport --save, FullIndexRun --counts and PageLook. It would check against the existing ledger line instead of appending one, and lift PageLook's made-once refusal (PageLook.java:202) only for a run that must equal the recorded look.
- Fix the page's hash text and SiteManifest. List only files that exist, or mark 'deleted after use, hash not checkable'. Fix the duplicate basenames in SHA256SUMS (SiteManifest.java:115,121). Say that the figures reproduce to the bit with 6 threads, not 'within 1e-12', and that results.json and runs/site carry full-precision doubles to compare against. Put the states file's SHA on the page if it claims byte-for-byte reproduction.
- Commit each export bundle's manifest when it is made: export-first's d385bccd, and P1's and P2's. Bundles carry no commit, time or path, so those hashes are the ones a rebuild from the seeds can meet.

## Analysis: time and the one-look ledger

## Time and the one-look ledger (read-only, 18 Oct)

**Assumptions.** One heavy run at a time (16 GB of RAM; runs use -Xmx10-12g), and the machine stays awake. The programs time themselves with nanoTime, which stops during clamshell sleep. With no tree store, every round that read trees builds them again. On a quiet machine that adds **T = 1,500-2,023 s a round** (ESTIMATED from projection-73df32c.txt:10, from the choice run's tree-building rounds 4-5 scaled up, and from TreeStore.java:17-20). Under the 18 Oct load it was about 6,000 s (MEASURED once: 8,120.8 s against 2,082.4 s with trees read).

### Regeneration times (quiet machine, no trees)

| Step | Time | Basis |
|---|---|---|
| Main state file (three builds, as before) | 13.0 h | MEASURED, log segments |
| c06c061 full run | 39.8-46.8 h | 19.8 h MEASURED + 48T |
| Step 7 | 4.0 h | ESTIMATED from file times |
| Step 8 | 9.9-11.5 h | 5.3 h MEASURED + 11T |
| Replay of c06c061 | 26.1-30.1 h | 14.90 h MEASURED + 27T |
| Page look, runs C and D | 5.8-6.6 h | 3.75 h MEASURED + 5T |
| report-final | 5.2 h | MEASURED |
| Twin chain (twin, calibration, search, choice, splits) | 9.5-13.6 h | mostly MEASURED |
| Small steps (pilots, key edges, sim part 1, row 10, page) | about 2.5 h | MEASURED |
| Pending P1-P6 | 12.2-15.8 h | ESTIMATED |

**Everything run so far: 116-133 h (4.8-5.5 days).** With the pending steps it is 128-149 h. The 91 rounds that read trees account for 38-51 h; at the 18 Oct load the total would be about 230 h. My arithmetic is in /private/tmp/time-ledger-84202/totals.py.

**Cheapest subsets:**
- The page from runs/site: **3.8 s**.
- Re-adapting the kept JSON and rebuilding: about 7 s.
- Row 10 with its inputs present: 6 min 16 s.
- A SiteExport rerun after a guard-key change, from nothing: 67-75 h plus two ledger lines.

**Some steps cannot run under 2.0e9 at all as the code stands:**
- c06c061: asks for 3.31e9 (FullIndexRun.java:281).
- Step 7: asks for 2.18e9 (CeilingReport.java:468), and also needs 3e9 B free on the volume.
- The 93-item replay.
- P2: PageLook asks for 2.92e9 (:247). Its real peak is about 2.44e9 even with a `--trees none` option, because run D always writes wide.ser and run-b.ser.

Only step 8 fits on its own (1.75e9).

### The ledger

- **Steps 7 and 8, rerun on a regenerated file, are not refused and need no `--second-look`.** That flag only clears lines of another registration (CustomCounts.java:449-456). Each rerun appends a line with the same blob (CeilingReport.java:416, FullIndexRun.java:325). COUNT_DESIGN.md:758-760 and :1212-1214 count those lines as part of the one look, and the page prints every line (SiteLedger.java:57-69).
- **PageLook refuses a second fresh look for good** (PageLook.java:202) and has no `--second-look`. The only route is `--resume` into the original OUT. That writes no line and checks only that look.txt *exists* (:207); no code ever reads look.txt's content.
- **isMain checks only the header seed** (CustomCounts.java:391-393), so any regenerated file passes.
- **stateFile is path, size and millisecond mtime** (FullIndexRun.java:392-394). A new mtime fails every identity it is embedded in. Set the mtime back to 1790724357600 and they all pass whatever the bytes are; the replay then accepts 1e-12 differences.
- **Getting the same bytes back is plausible only with the three builds** (13.0 h), and it is untested. The target hash ed9e9fb4…6739 is committed only as prose in the working papers.
- **Even then, step 7's .ser (c854767f), step 8's results.ser (6a456558) and c06c061's results.ser (8ba1412b) never return to their SHA-256.** Every Result stores its own run time (SystemScorer.java:2590; ResultDiff.java:17).

### Proposal (keeps "nothing chosen after a look")

1. **Keep the look's own records as results:** report-ceiling-97bf834.ser (202,956,286 B) and fullplay-main-97bf834/results.ser (113,431,766 B). Results at rest become 423,369,753 B. The save exists "so that no figure needs a second look" (COUNT_DESIGN.md:777); deleting it brings that need back. LiteratureCheck, CeilingResults, SiteExport, RiskReport and StrategyExport all read them.
   - Optionally also keep c06c061's results.ser (0.43 GB). It is not a look, but it can't be rebuilt to 8ba1412b and costs 40-47 h.
2. **Run P2 before deleting any of its inputs.**
3. **Check content, not just mtime.**
   - Commit a manifest of the main file (path, bytes, ms mtime, SHA-256).
   - Every main-file tool hashes the file before its ledger line (about 14 s) and refuses a main-seed file with a different hash.
   - Never touch an mtime back by hand.
4. **If the records must go anyway, add `--reproduce`:**
   - The target is committed first: a digest that leaves out Result.seconds.
   - It must use the original's classes, settings, blobs and state hash.
   - It writes its own "REPRODUCTION" ledger line.
   - Output that doesn't match is kept aside, and the page keeps the original figures. The only outcomes are "same" or "refused".
   - PageLook's version also compares look.txt's content.
5. **Never use `--second-look` for this.** It means a different registration and would mislabel the ledger. Never adopt figures from a re-valued main file: that would put two sets of figures for one registration on record, the fork the ledger exists to prevent.
6. **Always point at .worktrees/count-design by absolute path.** .worktrees/disk-cap holds committed copies of the registration with the same blobs, and a 3-line ledger. A rerun pointed there would pass every check and start a second ledger.

**For Justin:**
- Are the two look records (0.32 GB) results?
- Should c06c061's results.ser stay?
- Is `--reproduce` worth building, or are the look's outputs final once deleted?

### Per step

- **Assumptions (all times)**: One heavy run at a time: 16 GB RAM and runs use -Xmx10-12g. The machine stays awake: the programs time themselves with System.nanoTime, which stops during clamshell sleep, and caffeinate -i does not prevent that sleep (COUNT_DESIGN.md section 7). Without a tree store, every round that read trees builds them again. Call that extra cost T. Quiet machine: T = 1,500-2,023 s a round on 6 threads (ESTIMATED). Sources: projection-73df32c.txt:10 measured 0.0333 wall s a state x 60,743 = 2,023 s; choice-20000-38f4e60.log rounds 4-5 (trees built: 565.5 s and 502.8 s on 20,320 states) scaled to 60,743 gives 1,503-1,690 s; TreeStore.java:17-20 gives 0.17 CPU-s x 60,743 / 6 = 1,721 s. Under the 18 Oct load: about 6,000 s (MEASURED once: replay-trees-none-03b116f.log round 6 took 8,120.8 s, against 2,082.4 s for the trees-read first round of c06c061-0e0920d.log, which had twice the strategies). Totals are computed by /private/tmp/time-ledger-84202/totals.py (scratch arithmetic over the measured log values).
- **S01 freeze classes**: 1-3 s per directory (MEASURED in PLAN_PAGE.md:699-702), under a minute for about 15 directories. No trees involved.
- **S02 StateRun main**: 46,953 s = 13.04 h on 8 threads (MEASURED: montreal-80.log segment ends at lines 271 and 1607 and the last line, 26,065 + 17,238 + 3,650 s; StateRun times itself with nanoTime, StateRun.java:74,105). This is the only route that could give the same bytes: shoes 0-6649 on the unnamed build (probably d3c0b1f), 6650-39999 on d791438, 40000-59999 on 26f0724. 26f0724 contains ed3ac67, 'every value unchanged but for rounding'. Shoes are written in order (StateRun.java:18), so the same bytes are plausible but untested. With one current build it would take about 3.2 h (ESTIMATED from twin-80.log: 11,637 s on 7 threads, 0.19 s a state), but the bytes would differ (PLAN_PAGE.md:706-709). Needs no trees. Fits under 2.0e9 on its own.
- **S03 report-final**: 18,629.6 s = 5.17 h on 7 threads (MEASURED by its own clock, report-final-26f0724.md:1840). The output is a kept result, so regenerating it is optional. No trees.
- **S04 FullIndexPilot 1417 100**: 2,772 s (MEASURED: pilot-1417-100.txt lines 4 and 326, 21:50:10 to 22:36:22). It builds its own trees in memory, so losing the store does not change it.
- **S05 FullIndexRun c06c061**: 19.8 h with the store (MEASURED, full-c06c061.log: passes 24,759.6 s, 43 last rounds 7,560.2 s, 43 scorings 38,717.2 s). Rounds 1-48 read trees, so add 48T: 39.8-46.8 h on a quiet machine (ESTIMATED), about 100 h at the 18 Oct load. It cannot run under 2.0e9. DiskBudget asks for 3,307,701,468 B at FullIndexRun.java:281, and the end state would be 2,493,099,436 B (MEASURED sum of sizes). Its results.ser can never come back to 8ba1412b: every Result stores its own run time (SystemScorer.java:2456, 2590; ResultDiff.java:17).
- **S06 FullIndexPeek and other peeks**: Seconds (ESTIMATED). The outputs are kept results.
- **S07-S11, S16, S20, S21 twin chain**: Twin: 11,637 s (MEASURED, twin-80.log). Calibration: 3,680 s (calibration-b99ccbf.log, 08:31:30 to 09:32:50). CountSearch x47: the runs' own timers sum to 7,208 s (MEASURED from the 'Found ... in N s' lines in search/*.out), but the runs overlapped in lanes, so 2.0-6.1 h (ESTIMATED; COUNT_DESIGN.md section 7). SearchReports: 400 + 411 + 501 s (queue.log). Choice run: 1.7 h by its own clock, already with no trees. SplitMargin: 157 + 721 + 1 s and 2,499 s (queue-304901c.out, s68.out). Total 9.5-13.6 h. No page result needs it: the registration is in git and the chain's outputs are kept. The twin's build is unknown, so a rebuild cannot be held to a2f8a751.
- **S13 step 7 (ComparisonReport --save)**: About 14,262 s = 3.96 h (ESTIMATED from file times: the .log was born 19:16:43 and the .md and .ser were written 23:14:25; the .log is empty, and the pmset log starts 11 Oct, so sleep cannot be ruled out). No trees. A rerun writes a new ledger line. Under 2.0e9 it is refused: CeilingReport.java:468 asks for 2,178,396,345 B, and it also needs 3e9 B free on the volume (CeilingReport.java:355, 464). It needs c06c061's results.ser paired by state identity (CeilingReport.java:112-117). Its .ser cannot be rebuilt to c854767f, because it holds Results with their seconds.
- **S15 step 8 (FullIndexRun --counts)**: 5.3 h with trees read (MEASURED, fullplay-main-97bf834.log, 12:14:39 to 17:30:43, rounds 0-10 all reading c06c061's store). Add 11T: 9.9-11.5 h on a quiet machine (ESTIMATED; COUNT_DESIGN.md section 7 said 'about 7 h more'). --trees none already exists for FullIndexRun. A rerun writes a new ledger line. It fits under 2.0e9 on its own (1,747,701,468 B at its check). Its results.ser cannot be rebuilt to 6a456558, for the same seconds reason.
- **S17 gate 0**: 0.70 h by its own clock (MEASURED, gate0-304901c.log). Add about 0.75T for its 45,495 states: 1.0-1.1 h. A check only; replay.txt is kept.
- **S18 KeyEdgesRun a and a-prime**: 253.9 s + 611.8 s (MEASURED, queue-304901c.out). No trees.
- **S19 wide pilot**: 904.8 + 40.5 + 1,008.3 = 1,953.6 s by its own clock (MEASURED, pilot-1417-100-wide-304901c.txt lines 9, 36, 603). The 4.2 h span includes sleep.
- **S22-S24 sim part 1**: StrategyExport about 1 min (ESTIMATED from file times). Pilot about 77 s. p2 1,046 s and p3 1,985 s (MEASURED from sim-part1-0e0920d.log timestamps).
- **S25 replay of c06c061**: 53,631.8 s = 14.90 h (MEASURED: 27 last rounds 15,145.1 s plus 27 scorings 38,486.7 s). Add 27T: 26.1-30.1 h on a quiet machine. It does not fit under 2.0e9 (about 3.9 GB).
- **S28 page look (run C + run D)**: 1,568.2 s + 11,915.0 s = 3.75 h (MEASURED: page-look-0e0920d.log:32, and run D's 5 last rounds 7,196.3 s plus 5 scorings 4,718.7 s). Add 5T: 5.8-6.6 h. As the code stands it cannot run without a tree store (PageLook.java:241-244, 460). A fresh start is refused for good (PageLook.java:202).
- **S30 row 10 and S31 page**: Row 10 took 376 s (MEASURED, row10-partial-7c1d40e.log, 05:13:34 to 05:19:50), 59 s of it optimism. The page build takes 3.8 s (MEASURED by the 18 Oct page investigator).
- **P1 item-1 re-export (15 items, trees none)**: About 4 batches: 4.1-4.6 h on a quiet machine (ESTIMATED from the main replay's batch times plus 4T), about 9 h at the 18 Oct load (the disk-cap check's 2 items took 2.55 h). DiskBudget would also refuse it at FullIndexReplay.java:541-542 if runs/ held more than its inputs.
- **P2 PageLook --resume**: 3.3 h with trees (run D's 11,915 s). With 5T added: 5.4-6.1 h (ESTIMATED), but that needs a --trees none option in PageLook. It is a resume, so it writes no ledger line. Under 2.0e9 it is refused at PageLook.java:247: its inputs at rest plus the results are 1,938,749,874 B (MEASURED), and BYTES (PageLook.java:58) asks for 976,982,206 B more. Its real peak is about 2,439,878,005 B, because run D always runs with wide and sink (PageLook.java:468-469) and rewrites records 226,485,724 B, wide 83,591,398 B and run-b 191,051,009 B.
- **P3-P6**: Simulator part 2: 2.5-4 h (ESTIMATED, INTEGRATION.md row 8). RiskReport: under 1 h (ESTIMATED). SiteAdapt sim: seconds. Final row 10 with optimism reused, then SiteBuild: about 5 min.
- **Totals**: Main chain: 106.4-119.6 h. Twin chain: 9.5-13.6 h. Everything run so far, regenerated: 115.8-133.1 h (4.8-5.5 days) on a quiet machine that stays awake, one step at a time. With the pending steps: 128-149 h. The 91 tree-reading rounds account for 38-51 h of that; at the 18 Oct load they would take about 152 h, about 230 h in all. One-look chain from nothing (main file, c06c061, step 7, step 8, page look): 72.5-81.8 h. Under 2.0e9, c06c061, step 7, the full replay and the page look cannot run at all.
- **Cheapest useful subsets**: Rebuild the page from runs/site: 3.8 s (MEASURED). Re-adapt the kept JSON (ceiling, wide, edges, ledger, literature, evidence) and rebuild: about 7 s (MEASURED from row 10's step times). Row 10 with its inputs present: 376 s. A SiteExport rerun after a guard-key change, from nothing: 66.7-75.2 h plus two new ledger lines, and blocked by the cap at S05 and S13.
- **Ledger: step 7 and step 8 reruns**: Neither is refused, and neither needs --second-look. That flag only clears lines of another registration's blob (CustomCounts.java:449-456). Each rerun appends a line with the same blob (CeilingReport.java:416; FullIndexRun.java:325). The ledger must be committed with no pending lines first (CustomCounts.java:448, 520-541). COUNT_DESIGN.md:758-760 and :1212-1214 count every such line as part of the one look. SiteLedger prints every line (SiteLedger.java:57-69), so the page would show 5 lines, not 3. Pointed at another worktree's copy of the registration, a rerun would pass and write to that copy's ledger instead. .worktrees/disk-cap has committed copies with the same blobs, and its ledger has 3 lines against count-design's 4 (PLAN_COMPUTE.md:1051-1056).
- **Ledger: PageLook**: A second fresh look is refused for good (PageLook.java:202), and PageLook has no --second-look (main, :516-529). The only route is --resume into the original OUT. That writes no line (:278-284) and checks only that look.txt exists (:207); no code reads look.txt's content. So a resume over a regenerated step 7 or step 8 would run under a look.txt that names other inputs (step 7 SHA c854767f), and nothing would compare them.
- **Ledger: isMain and stateFile on a regenerated file**: isMain reads only the header seed (CustomCounts.java:391-393; the sim's check is header-only too, sim/States.java:182-184), so any regenerated file passes. stateFile is path, size and millisecond mtime (FullIndexRun.java:392-394). The path and size would match (ESTIMATED: a 271-byte header and 60,743 fixed-size records, both deterministic), but a new mtime fails every identity it is embedded in: CeilingReport.java:112-117, FullIndexReplay.java:541-543, PageLook.java:213/239/241-244, SiteExport.java:576-578, StrategyExport.java:2890. Set the mtime back to 1790724357600 and every one passes whatever the bytes are. Content checks remain only where code rescores to the bit (CeilingReport.java:299-320, PageLook Step8). The replay accepts 1e-12 differences (FullIndexReplay.java:29), and SiteExport has no content check. The target SHA-256 ed9e9fb45de18a25d37572d720aa8cfb144e4d6dee9ac695bdba561141016739 is committed only as prose (disk/2026-10-18-2gb-working-papers.md:906, disk-cap branch).

### Blockers

- Step 7's .ser (c854767f), step 8's results.ser (6a456558) and c06c061's results.ser (8ba1412b) can never be regenerated to the SHA-256 the page cites. Every SystemScorer.Result stores its own run time (SystemScorer.java:2456, 2590; ResultDiff.java:17: 'a timing, which no rerun repeats'). Once deleted, those citations can never be checked again. A rerun could only be compared field by field (ResultDiff) against originals that would no longer exist.
- PageLook refuses any second fresh look (PageLook.java:202), with no override. Its outputs can be remade only by --resume into the original page-look-0e0920d with look.txt present. If step 7's .ser, step 8's run dir, montreal-80.states or export-first is deleted before P2, the F50/F52 export becomes unreachable without a code change.
- P2 is refused under a 2.0e9 budget even with a --trees none option. PageLook.BYTES = 1e9 (PageLook.java:58) is checked at :247 against 1,938,749,874 B of inputs plus results (MEASURED), which gives 2.92e9. The real peak is about 2.44e9, because run D always writes wide.ser and run-b.ser (PageLook.java:468-469; last time 0.50 GB with its records). The merged graph's 0.02 GB for P2's output understates this.
- The one-look chain cannot be regenerated under 2.0e9 as the code stands. c06c061: 3.31e9 asked at FullIndexRun.java:281, 2.49e9 at its end. Step 7: 2.18e9 asked at CeilingReport.java:468, plus 3e9 B free on the volume (CeilingReport.java:355, 464). Only step 8 fits alone (1.75e9).
- State identity is metadata. A regenerated main file always passes isMain (header seed), and passes stateFile if its mtime is set back, whatever its bytes. The replay's 1e-12 fallback would then let P1 export from a re-valued file without saying so. ed9e9fb4... sits only in working-paper prose that no code reads.
- Getting the main file back to the same bytes needs all three builds, segment by segment (13.0 h). The first segment's build is not recorded (probably d3c0b1f), and same-bytes regeneration has never been tested. The twin's build is unknown, so a2f8a751 cannot be targeted.
- Regenerating everything takes 116-133 h on a quiet machine that stays awake (about 230 h at the 18 Oct load). 38-51 h of that is rebuilding trees, which the 2.0e9 cap forces.

### Code changes

- None made; the work was read-only. The changes below are proposals.
- PageLook --trees none: pass r.noTrees instead of readOnlyTrees (PageLook.java:460) and skip the store refusal (:241-244). Re-estimate BYTES (:58), and add a way to rerun only the owed export without rewriting wide.ser and run-b.ser (:468-469). Without this, P2 cannot run under the cap.
- Check the main state file's content, not only its mtime. Commit a manifest (path, bytes, ms mtime, SHA-256) in the count-design worktree. Every main-file tool (the isMain callers) hashes the file before its ledger line (about 14 s, ESTIMATED from 747 MB in 8.06 s) and refuses a main-seed file with a different hash. Never set an mtime back by hand.
- PageLook --resume: compare look.txt with identity(o, c, commit) apart from the commit line, instead of only checking that it exists (PageLook.java:207).
- A results digest that leaves out Result.seconds (ResultDiff's field walk at ResultDiff.java:278-290, fed to SHA-256), committed for every look output before it could be deleted.
- Only if the look's records must still be deleted: a --reproduce mode for ComparisonReport, FullIndexRun --counts and PageLook. It refuses unless the state hash, classes commit, settings and both registration blobs equal the original look's. It writes a 'REPRODUCTION of the look of <time>' ledger line before reading any state, and keeps its output aside unless the digest matches. SiteLedger would print such lines apart from the looks.

## Analysis: peaks under delete-after-use

## Peaks under delete-after-use

All work was read-only. My scripts are in `/private/tmp/peaks-dau-84183/`:
- `model.py` walks the graph's order.
- `model2.py` runs the from-seeds plans.
- `pending.py` covers the pending chain only.
- `subsets.py` tests which changes are necessary.

Sizes come from stat of every file in runs/ (23,286,908,933 B, which matches the merge). I also measured gzip ratios today:

| File | gzip ratio |
|---|---|
| results.ser | 0.6796 |
| all 186 parts | 0.6795 |
| step 7 save | 0.5451 |
| step 8 results.ser | 0.5401 |
| step 8 fit.ckpt | 0.5735 |

### 1. The graph's order, code as it stands

**With trees.bin kept**, every step from S05 to P2 holds 17.3–21.3 GB.

**With --trees none everywhere**, S02–S04 fit at 1.34 GB. Every step from S05 to P2 is over:

| Step(s) | Peak |
|---|---|
| S05 | 2.388 GB |
| S07–S12 (twin present) | 3.72–3.78 GB |
| S25 | 6.42 GB |
| S30i–S32 | 2.92–2.94 GB |
| P1 / P2 (ESTIMATED) | 3.11 / 3.26 GB |

P3–P6 fit (1.28, 1.12, 0.55 and 0.12 GB, ESTIMATED).

**Seven steps are over even with nothing else on disk:**

| Step | Alone |
|---|---|
| S05 | 2.388 GB |
| S18 | 2.062 GB |
| S22 | 2.035 GB |
| S25 | 3.837 GB |
| S27/28 | 2.647 GB |
| S30e | 2.096 GB |
| P2 | 2.466 GB |

Three more are within 45 MB of the cap: S13 (1.977), S30b (1.981) and S30h (1.956).

**Two files drive almost everything:**
- The main state file (1,340,719,784 B) is read by 16 steps, from S03 to P2.
- The twin (1,341,183,296 B) is read from S08 to S30b.
- Together they are 2,681,903,080 B.

### 2. Which steps need a state file, and can the main file be made once?

**Steps that need the main file:** S03, S04, S05, S13, S15, S17, S18, S19, S22, S25, S27/28, S32, P1 and P2. S30e and a P6 SiteExport rerun need only its header and identity (SiteExport.java:575-580).

**Steps that need the twin:** S08, S08b, S09, S10a, S11, S10b, S16, S20, S21 and S30b.

**Can the main file be made once and deleted once?**
- **Pending chain:** yes. It was made once (S02) and can be deleted once, after P2.
- **From seeds, with today's file format:** no.
  - The twin's work needs c06c061's results (S05) and feeds step 7 (S13), so the twin's whole lifetime sits inside the main file's.
  - Deleting the main file and making it again (about 3.0–3.2 h, ESTIMATED from the twin's 11,637 s) still does not fit: the model peaks at 2.60 GB at S25.
- **From seeds, with both state files re-encoded** (v3, about 0.608 GB each, ESTIMATED): yes.
  - The main file is made at S02 and deleted after S30e.
  - The twin is made at S07 and deleted after the optimism split's twin part.

### 3. From seeds: the only plan that fits

The plan needs all of these together:
- PageLook --trees none
- the v3 state format
- one copy of c06c061's results (parts plus a tail, or results.ser plus slicing)
- gzip of the files the engine writes
- slim key-edge collectors
- the optimism split
- the order: S02, S03, S04, S19, S05, S06, S17, S25, S32, then the twin steps through the optimism twin part, then S13, S15, S27, S22, S18, S30e, S30h, then the rest

Its maximum is 1,896,670,177 B at S16 (ESTIMATED), a 103 MB margin. The margin is gone if v3 compresses worse than 0.492.

Dropping any one of v3, gzip, slim collectors, one results copy or the split puts a step over. The P1 and P2 re-exports fold into S25 and S27.

### 4. The pending chain from today's files

- **At rest before anything runs:** 2,929,752,865 B. That is already over, so a one-time overshoot cannot be avoided.
- **The first step would be X:** extract what RiskReport and SiteSim read from results.ser, step 7, step 8 and wide.ser (peak 2.94–2.96 GB), then let those four go (996,852,349 B).
- **Then P2 before P1, with four PageLook changes** (--trees none; step 7's identity from look.txt; Step8 checked against step 8's fold parts; run D with only the export and Step8 hooks) and records that keep only the export rows:
  - P2 peaks at 1.971–1.997 GB.
  - P1 peaks at 1.924–1.950 GB (both ESTIMATED).
  - The other order, P1 before P2, is 78 MB over.
- **If the 77.6 MB key-edges JSON and CSV are not counted as results**, the margins grow to 81 and 128 MB.
- **A P6 SiteExport rerun** needs 2,088,697,501 B of inputs, which would be deleted by then, unless SiteExport takes the identity from results-7c1d40e.json.
- **DiskBudget would refuse P1 and P2** until REPLAY_ITEM and PageLook.BYTES are re-estimated.

### 5. Bit-identity, hashes and time

**Bit-identity:**
- --trees none: bit-identical. The disk-cap check matched every number to the bit.
- One results copy: bit-identical (MEASURED merge; the rebuilt stream hashes to 8ba1412b).
- gzip: bit-identical.
- v3: bit-identical on 9,061 sampled records per file; a whole-file test is still needed.
- Optimism split: bit-identical; f4562fa0 changes only in its commit line.
- Slim collectors: expected bit-identical, but this needs a test.

**Hashes that change:**
- **Slim collectors:** 56148393, c649f44f, 2939a4ea, 3c1866b6, 3fe732ce and 824f0769, which changes the page's files table.
- **gzip:** the SHA-256 of every new file, unless hashes are taken over the decompressed stream.
- **v3:** the raw SHA of the state files; ed9e9fb4 and a2f8a751 can then be checked only through the decoder.
- **The pending changes:** none that is cited. RiskReport has not run yet.

**Time:** no tree store means about +44 h (quiet) to about +154 h (loaded) from seeds, over 92 walks that read trees. P1 and P2 together add +4.3 h to +15 h (ESTIMATED).

### Per step

- **Method**: For each step I counted three things: results written so far, intermediates whose last reader has not run yet, and the step's own outputs and classes dir. Sizes come from stat of all 7,450 files in runs/ (23,286,908,933 B, the same as the merge; /private/tmp/peaks-dau-84183/all.tsv). Each peak is a sum of MEASURED sizes; that the sum is the true peak is an estimate. Pending outputs are ESTIMATED. Left out: superseded runs S14, S26, S29, the 4-6 Oct dry runs, and PageLook attempt 1. S27 and S28 count as one PageLook. 'A0' means the graph's order, the code as it stands, and --trees none. With trees.bin kept, add 14,877,669,295 B to every step from S05 to P2, giving 17.3-21.3 GB. 'R' means the proposed plan in its reordered order (ESTIMATED). Scripts: model.py, model2.py, pending.py, subsets.py.
- **S00/S01 git inputs, freeze classes**: Git inputs are free at rest. The classes dirs are 0.26-2.2 MB each (MEASURED), counted as temporaries in each step. Not over.
- **S02 StateRun main**: A0: 1,341,367,878 B, under. The main state file (1,340,719,767 + 17 B .progress) is its only large output. R (v3, ESTIMATED ratio 0.4534): 608,476,020. Note that StateRun reserves 25,000 B a shoe (StateRun.java:26, 69), which is 1.5 GB.
- **S03 ComparisonReport report-final**: A0: 1,342,209,675, under. Needs the main file.
- **S04 FullIndexPilot 1417**: A0: 1,342,438,003, under. Needs the main file.
- **S05 FullIndexRun c06c061**: A0: 2,388,073,326, OVER, and over even with nothing else on disk. At the results.ser rename the disk holds the main file, fit.ckpt+run.id 183,690,200, 186 parts 431,014,069 and results.ser 430,694,877 (FullIndexRun.java:341-350). Smallest change: keep one copy of the results. Either write the parts plus a 0.49 MB tail (perfect + rounds) and merge on read, or write results.ser and slice items in the replay. That gives 1,957,872,348, a 42 MB margin. DiskBudget.ITEM must also fall from 10 MB to about 3.5 MB, or the budget refuses the run (FullIndexRun.java:279-281). Figures stay bit-identical: merged parts deep-equal results.ser, and the rebuilt stream hashes to 8ba1412b (working papers). R (v3 + gzip): 1,007,867,592. Needs the main file.
- **S06 FullIndexPeek**: A0: 2,379,632,496, OVER. Alone it would be 616,350,780; it is over only because of what is at rest (main + fit + 93 folds + results.ser). With one results copy: about 1,957,883,655. R: 1,007,878,899.
- **S07 StateRun twin**: A0: 3,720,708,233, OVER. Main and twin together are 2,681,903,080 B on their own. In the graph's order the twin lives from S07 to S30b. Change: v3 for both files (about 0.608 GB each, ESTIMATED), and the optimism split so the twin dies after S21. R: 1,833,308,147.
- **S08 Calibration**: A0: 3,721,411,779, OVER. Alone: 1,774,439,863. Needs the twin and c06c061's results. R: 1,834,011,693.
- **S08b BoundRows d6a005c**: A0: 3,721,413,729, OVER (cause: what is at rest). Needs the twin. R: 1,834,013,643.
- **S09 CountSearch x47**: A0: 3,729,325,760, OVER (at rest). Twin only. R: 1,841,925,674.
- **S10a SearchReport 38f4e60**: A0: 3,729,455,170, OVER (at rest). Needs the twin. R: 1,842,055,084.
- **S11 choice-20000 (twin, --trees none)**: A0: 3,783,128,078, OVER (at rest). R: 1,895,727,992.
- **S10b lane9 + SearchReport 0b85be2**: A0: 3,783,101,857, OVER (at rest). R: 1,842,071,743.
- **S12 registration (git)**: No runs/ I/O. At rest it holds 3,774,127,654 (A0), which is over.
- **S13 step 7 --save**: A0: 3,978,307,163, OVER. Alone: 1,977,227,599, a 23 MB margin (main + results.ser + its 202,956,286 B save). R (gzip save, ratio 0.5451 MEASURED): 1,338,513,543. Needs the main file.
- **S15 step 8 --counts**: A0: 4,245,142,324, OVER. Alone with --trees none: 1,610,411,597. As run it read trees.bin read-only. R: 1,438,503,383. Needs the main file.
- **S16 BoundRows twin preview**: A0: 4,244,379,653, OVER (at rest). R: 1,896,670,177. This is the plan's maximum, with a 103 MB margin. The margin is gone if the v3 ratio is above 0.492.
- **S17 replay gate 0**: A0: 4,237,020,459, OVER (at rest). Alone: 1,646,764,855. Its 46,277,405 B of records, wide, sink and export have no reader. R (run right after S05): 1,055,464,200. Needs the main file.
- **S18 KeyEdgesRun a, a-prime**: A0: 4,477,649,340, OVER. Alone it is 2,062,182,417, still over. Run a by itself would be 1,996,627,516, but a-prime runs while a is still on disk. Smallest change: slim the collectors to the fields the report reads (KeyEdges.java:285-317; KeyEdgesReport.java:268-312), or gzip them. Either brings it to about 1.94 GB. Figures should stay the same, but that needs a test. Hashes change: collectors 56148393 and c649f44f, and the key-edges JSON 824f0769 through its inputs block, so the page's files table too. R: 1,687,983,244. Needs the main file.
- **S19 FullIndexPilot --wide**: A0: 4,477,722,939, OVER (at rest). Main file only. R (moved before S05): 611,101,186.
- **S20/S21 SplitMargin 1000 and 68**: A0: 4,477,827,768 and 4,479,284,901, OVER (at rest). Twin only. R: 1,835,258,876 and 1,836,716,009.
- **S22 StrategyExport part 1**: A0: 4,534,067,538, OVER. Alone it is 2,034,650,615, still over (main + results.ser + step 7 + bundle 54,632,498). Smallest change: gzip step 7's save and the results, or v3. The bundle is built from values, so the manifest 4a424be6 should stay the same (ESTIMATED). R: 1,586,751,019. Needs the main file.
- **S23/S24 sim pilot and p2/p3 part 1**: A0: 4,532,128,138 and 4,534,201,028, OVER (at rest). Alone they are 58-60 MB. R: 915,544,675 and 917,617,565.
- **S25 replay c06c061 (93 items, wide + sink)**: A0: 6,418,614,704, OVER, and the largest step. Alone: 3,837,166,252 (main + fit + 93 folds + records 929,858,299 + wide 249,769,420 + sink 704,599,811). No single change brings it under. It needs v3, gzip of records and wide (ratios 0.577 and 0.668 MEASURED on samples), a slim sink (559 MB of Entry copies dropped), one results copy, and to run right after S05. That gives R: 1,867,958,437, with the item-1 export folded in. Hashes change: ee3ff121 and 3c1866b6, and through them b86622c4 and 824f0769, unless hashes are taken over the decompressed stream. Needs the main file.
- **S27/S28 PageLook (runs C and D)**: A0: 5,970,793,432, OVER. Alone with --trees none it is 2,647,101,048. As the code stands it cannot run under any 2 GB cap, because it requires the 14.88 GB trees.bin (PageLook.java:178-181, 241-245). It needs a --trees none option, plus v3, gzip and slim run-c and run-d. That gives R: 1,681,669,322, with F50/F52 folded in. Hashes 2939a4ea and 3fe732ce change. Needs the main file.
- **S30a CeilingResults full**: A0: 5,598,230,078, OVER (at rest; alone 753,121,029). R: 894,109,731.
- **S30b CeilingResults optimism**: A0: 5,598,249,944, OVER. Alone: 1,980,892,425 (twin + results.ser + step 7). Change: split optimism. The twin part scores the registered rows on the twin in the twin window and writes about 20 KB (CeilingResults.java:1378-1408). The main part reads that file instead of the twin. Figures stay bit-identical; f4562fa0 changes only through its commit line. R: 894,129,597.
- **S30c/S30d CeilingResults data, SiteAdapt ceiling**: A0: 4,257,133,144 and 4,257,155,987, OVER (at rest). R: 894,176,093 and 894,198,936.
- **S30e SiteExport**: A0: 4,259,293,917, OVER. Alone it is 2,096,087,948, over: it needs the main file's header and identity (SiteExport.java:575-580), results.ser, step 7 and step 8. Change: take the header and identity from the v3 header, a manifest, or results-7c1d40e.json. With that, no state file is needed here. R: 1,690,267,871.
- **S30f/S30g WideReport, SiteAdapt wide**: A0: 4,269,842,044 and 4,270,155,367, OVER (at rest). R: 904,747,063 and 905,060,386.
- **S30h KeyEdgesRun report**: A0: 4,347,760,987, OVER. Alone: 1,955,684,700, a 44 MB margin. In R it runs right after S18 with slim collectors and takes perfect from the tail: 1,160,045,565.
- **S30i-S30m adapters, LiteratureCheck**: A0: 2,919,996,032 to 2,928,767,595, OVER only because of what is at rest (the 2,822,771,164 B pending set). R: about 0.91 GB.
- **S31 SiteBuild**: A0: 2,929,743,948, OVER (at rest). It compiles into mktemp outside runs/ (build-site.sh:39-41) and writes 3,179,557 B. R: 915,280,865.
- **S32 disk-cap check**: A0: 2,943,867,497, OVER (at rest; alone 1,717,722,313). Its 10,921,007 B of records were let go at the end. R: 1,343,350,538.
- **X (new, pending) RiskReport/SiteSim engine extract**: This step reads results.ser, step 7, step 8's results and wide.ser once, then lets all four go (996,852,349 B). Peak: 2,936,971,361 to 2,962,971,361 (ESTIMATED; extract 5-31 MB). It is over because today's at-rest set, 2,929,752,865, is already over the cap.
- **P1 item-1 re-export (15 items)**: A0: 3,113,006,361 (E), OVER. Alone: 1,886,861,177 (E). With X done first, P2 run before P1, and records keeping only the export rows: 1,923,510,569-1,949,510,569 (E), a 50-76 MB margin. Run before P2 it would be 2,078,154,012, over. REPLAY_ITEM (25 MB) must be re-measured, or the budget refuses it (FullIndexReplay.java:552-554). Needs the main file.
- **P2 PageLook --resume**: A0: 3,261,172,397 (E), OVER, and it cannot run without trees.bin. Five changes bring it to 1,970,954,012-1,996,954,012 (E), a 3-29 MB margin: (1) a --trees none option; (2) step 7's identity read from look.txt, which already holds its SHA-256 c854767f (PageLook.java:211-217, 318-322); (3) Step8 compared against step 8's fold parts (PageLook.java:477; 585/585 slots equal, MEASURED in the working papers); (4) run D with only the export and Step8 hooks (PageLook.java:467-470); (5) BYTES lowered from 1e9 (PageLook.java:58, 247). It also needs export-first present, the old replay/run.id gone (PageLook.java:297, 466), and the main file.
- **P3 simulator part 2**: A0: 1,280,701,938 (E), under. With X done first: about 288-315 MB.
- **P4 RiskReport + SiteAdapt risk**: A0: 1,116,252,546 (E), under (its .ser inputs are 996,852,349 B). With X done first: about 124-150 MB.
- **P5 SiteAdapt sim**: A0: 550,145,074 (E), under. It reads c06c061's results.ser (SiteSimReported.java:70-75), or the extract after X.
- **P6 row 10 + SiteBuild**: A0: 122,650,197 (E), under, if SiteExport does not rerun. A SiteExport rerun needs 2,088,697,501 B of inputs, which is over, and those inputs would already be deleted. Change: SiteExport reads the identity and header from results-7c1d40e.json or a manifest. Reuse optimism-7c1d40e.

### Blockers

- Today's at-rest minimum is already over the cap: 2,929,752,865 B (MEASURED). That is 106,981,701 B of results plus 2,822,771,164 B of intermediates that a pending step reads. No order of P1-P6 fits as the code stands: P1 3.11 GB, P2 3.26 GB (ESTIMATED). Montreal, c06c061's results.ser, step 7 and step 8's results.ser and wide.ser alone are 2,337,572,133 B. None can be remade to the hash the page cites.
- P2 cannot run at all under a 2 GB cap until PageLook has a --trees none option. PageLook refuses to run without a kept tree store (PageLook.java:178-181, 241-245), and trees.bin is 14,877,669,295 B.
- Even with every change, the first pending step (the RiskReport/SiteSim extract) starts from 2.93 GB and peaks at 2.94-2.96 GB. Getting below 2.0e9 means a one-time overshoot, or deleting an input that a pending step still needs.
- From seeds, no order fits with v2 state files. The twin's work (S08-S12) needs c06c061's results, made from the main file at S05, and feeds step 7 (S13), which also runs on the main file. So the twin's whole lifetime sits inside the main file's, and the two files together are 2,681,903,080 B. Generating the main file twice still fails: the model peaks at 2.60 GB at S25 (ESTIMATED).
- The only plan that fits from seeds rests on ESTIMATES. It needs the v3 state format at 0.4534 of v2: that ratio was measured on the first 200 MB only, and the whole-file ratio is unmeasured. It also needs gzip of files the engine writes, slim collectors, one results copy, the optimism split, --trees none everywhere, and a reordering. The model's maximum is 1,896,670,177 at S16, a 103 MB margin. That margin is gone if v3 is worse than 0.492. Dropping any one of v3, gzip, slim collectors, one results copy or the split puts some step over (subsets.py).
- Deleting the main file, results.ser, step 7, step 8, wide.ser and the five collectors cannot be undone. Their cited hashes can never be checked again. A SiteExport rerun becomes impossible unless SiteExport stops reading them. The main file cannot be rebuilt to its bytes: three builds made it, segment 1 agrees only to 1e-12, and FullIndexRun.java:392-395 puts its mtime in milliseconds into the identity.
- Time: no tree store under 2 GB. From seeds there are 92 walks that read trees (48 + 11 + 27 + 5 + 1, MEASURED round counts). At an extra 1,721 s each on a quiet machine to about 6,000 s under load, that is +44 h to about +154 h (ESTIMATED). P1 and P2 together add +4.3 h to +15 h.

### Code changes

- None made: read-only. Scratch only, in /private/tmp/peaks-dau-84183/. Proposed changes follow.
- PageLook --trees none: PageLook.java:178-181, 241-245, 459-460. Required for S27 and P2. Bit-identical: the disk-cap check matched 416,114/416,114, 829,436/829,436 and 60,743/60,743 numbers to the bit. No cited hash changes.
- One copy of a full-index run's results. Either write parts plus a 0.49 MB tail and merge them in CeilingReport.read and FullIndexPeek.read, or write results.ser only and slice items in FullIndexReplay.java:546-551 and 762. Touches FullIndexRun.java:341-350. Bit-identical; 8ba1412b and 6a456558 can be rebuilt from the stream.
- Gzip the files the engine writes, with readers that sniff the 1f8b magic: FullIndexCheckpoint.java:176-187 (fit.ckpt, parts, records), CeilingReport.java:491-507, KeyEdges.java:285-317, WideReport.java:155-163. Bit-identical figures. Every new file's SHA-256 changes, unless the hash is taken over the decompressed stream. MEASURED gzip ratios: results.ser 0.6796, all parts 0.6795, step 7 save 0.5451, step 8 results.ser 0.5401, step 8 fit.ckpt 0.5735.
- Slim collectors: KeyEdges.Collector.save and the Sink finish (ReplayHooks.java:183-199) drop the Entry copies the report never reads. That is 1,028.8 MB of the five files' 1,428.2 MB. Changes hashes 56148393, c649f44f, 2939a4ea, 3c1866b6, 3fe732ce and 824f0769. Needs a test that the report is unchanged.
- StateStore v3, written directly by StateRun (StateStore.java:32; StateRun.java:26, 69). FullIndexRun.stateFile returns the stored v2 identity (FullIndexRun.java:392-395), and hash helpers hash the decoded v2 bytes. Bit-identical on 9,061 sampled records per file; a whole-file test is still needed. Frozen classes cannot read it.
- Optimism split: CeilingResults.java:1378-1408 becomes a twin part, run right after S12, and a main part that reads its output.
- Pending only, part 1: a RiskReport and SiteSim engine extract (RiskReport.java:286-317; SiteSimReported.java:70-75).
- Pending only, part 2: PageLook takes step 7's identity from look.txt (PageLook.java:211-217), and its Step8 hook compares against step 8's fold parts (PageLook.java:477, 487-511).
- Pending only, part 3: a resumed PageLook runs run D with only the export and Step8 hooks (PageLook.java:467-470). Replay records keep the Result only when a hook calls results() (FullIndexReplay.java:212-230, 771-778; ReplayHooks.java:84, 185).
- Re-estimate the budget constants, or DiskBudget refuses runs that would fit: ITEM (DiskBudget.java:58), REPLAY_ITEM (:60), PageLook.BYTES (PageLook.java:58), KeyEdgesRun.BYTES (KeyEdgesRun.java:53), StateRun.SHOE_BYTES (StateRun.java:26).
- SiteExport takes the state file's identity and header from the v3 header, a manifest, or results-7c1d40e.json (SiteExport.java:575-580). Then neither S30e nor a P6 rerun needs the state file.
- Let records go even when the export is refused (FullIndexRun.java:218-221; PageLook.java:532-535).

## The reconciled graph

## Merged graph: results back to seeds

I merged the two maps into one graph. It has 84 nodes and 44 steps in run order. Where the maps disagreed, I settled it by reading the code, the run scripts, the logs and the pmset power log.

Everything was read-only. My scratch files are in `/private/tmp/merge-depgraph-80186/`:
- `results.txt` and `intermediates.txt`
- `bin/classify2.py` and `bin/groups.py`
- `out/nodes.json`

**Results kept at rest (MEASURED): 264 files, 106,981,701 B.**
- This is a stat sum over `results.txt`.
- The committed DiskBudget agrees. I ran it from classes-03b116f with `--without results.txt` and it printed 0.107 GB, 0 files missing.
- The set holds the 7 page inputs and page-613255a, the reports and tables on the page's path, the check records, the literature cache, and 113 logs and scripts.
- The key-edges JSON and CSV make up 72.5% of it, at 77.6 MB.

**Intermediates (MEASURED partition): 23,179,927,232 B.**
- **No pending reader as the code stands: 5,479,486,773 B.** These can go now:
  - the twin, 1,341,183,296 B
  - the replay records, 929,858,299 B
  - the five key-edge collectors, 1,428,237,518 B
  - 171 of the 186 c06c061 parts
  - the page look's two unread wide files and its replay records
  - superseded outputs, 956,175,021 B
- **Needed by a pending step: 17,700,440,459 B.**
  - trees.bin is 14,877,669,295 B of this, and only P2 (PageLook) reads it.
  - The other 2,822,771,164 B are the main state file and c06c061's fit.ckpt, run.id and 15 parts. Also step 7's .ser, step 8's results.ser, fit.ckpt, run.id and fold parts, wide.ser, and the two simulator bundles.

**What the code showed that neither map had:**
- **P2 needs export-first present.** With it there, PageLook's resume skips run C (PageLook.java:297).
- **P2 needs the page look's old `replay/run.id` deleted.** PageLook resumes the replay whenever that file exists (PageLook.java:466). That file names commit 0a447de, and the replay refuses a run.id that names another commit (FullIndexReplay.java:459). So under the item-1 classes, P2 is refused until it is removed.
- **No code needs the twin again.** CeilingResults data loads `--optimism` without checking its commit (CeilingResults.java:761-765). The three optimism builds differ only in their commit line (MEASURED diff). Only the committed row-10 script reruns that step.
- **Several walls in both maps were queue spans.** The programs time themselves with System.nanoTime. The pmset log shows clamshell sleeps inside those spans. For example, the wide pilot's 4.2 h span holds 1,008.3 s of resamples.

**Where "results only, nothing off the laptop" meets the 2.0e9-byte cap**

1. **No order of the pending steps fits as the code stands.**
   - Before P1 writes anything, runs/ already holds the results plus the pending set without trees: **2,929,752,865 B** (MEASURED sum).
   - P4 (RiskReport) comes after P1 and P3. It needs c06c061's results.ser, step 7, step 8's results.ser and wide.ser.
   - P1 and P2 need the main state file.
   - Those five files alone come to **2,337,572,133 B**, and none can be remade to the hash the page cites.
2. **P2 cannot run under any 2 GB cap** until PageLook gets a `--trees none` option.
3. **A later SiteExport rerun cannot fit either.** A change to the template's guard keys forces one. It needs 2,087,802,713 B of inputs plus the results.
4. **Deleting these files after the chain cannot be undone:**
   - The cited hashes could never be checked again: 8ba1412b, c854767f, 6a456558, ee3ff121, a2f8a751 and the five collectors.
   - Steps 7 and 8 and the page look cannot be rerun without new ledger lines (PageLook.java:202; COUNT_DESIGN.md:1212-1214).
   - A rebuilt main state file needs its millisecond mtime restored (FullIndexRun.java:392-394), and nothing shows it would come back with the same bytes.

**Other fixes in the merge:**
- `site/key-edges-7c1d40e.json` came from an unscripted call. The scripted step read the 0a447de JSON.
- The `rc=` lines in four scripts always read 0, because `$(date)` runs before `$?` is read. Measured in zsh.
- Step 7's 3.96 h is still an estimate from file times.

**Questions for Justin:**
- Is the 77.6 MB key-edges JSON and CSV a result, or only the 0.47 MB page input made from it?
- Should the twin stay for the spot check, or go now?
- Which code changes should come first? The options are PageLook `--trees none`, SiteExport taking the state identity from a manifest, and slimmer inputs for RiskReport and the replay. Without some of them, the cap and the rule cannot both hold.

**Results at rest.** 264 files, 106,981,701 B (MEASURED: sum of stat sizes over the merged result list /private/tmp/merge-depgraph-80186/results.txt; 107,585,536 B in allocated blocks). Confirmed with the committed tool: `java -cp runs/full-index/classes-03b116f DiskBudget runs --without results.txt` printed "The 264 paths ... hold 0.107 GB (0 not there)". DiskBudget at 03b116f differs from HEAD only in comments. Breakdown: key-edges JSON+CSV 77,605,620; wide spreads JSON + report 10,548,127; literature cache 8,384,809; page-613255a 3,179,185; the 7 runs/site inputs 3,112,143; ceiling tables and JSON 1,667,019; logs and scripts 858,245 (113 files); report-final 841,034; literature report 221,270; pilots and peek 138,563; step 7 .md 132,766; sim part 1 summaries 125,094; calibration, search-0b85be2, bounds and peek text 77,890; replay.txt files 66,134; search starts 10,626; choice md and peek 6,381; disk-cap records 3,291; sim pilot 2,604; look.txt 900. Everything else in runs/ (23,179,927,232 B in 7,186 files; runs/ total 23,286,908,933 B in 7,450 files, MEASURED) is an intermediate. Pending results (sim part 2, risk, site/risk, site/sim, the final page) are not on disk yet.

**Order.** S00 git inputs: code at each commit, catalog, seeds, registrations, ledger, paragraphs, claims, evidence, template, SIM_CHECK.md, website index.html -> S01 freeze classes (javac of git archive <commit>) before each engine step -> S02 StateRun main, seed 20260928: shoes 0-6649 on an unnamed build (probably d3c0b1f), 6650-39999 on build-d791438 (from 28 Sep 23:28), 40000-59999 on build-26f0724 (from 29 Sep 17:13) -> montreal-80.states (29 Sep 19:25:57) -> (superseded) ComparisonReport report-quick and report-all-200 at c3af4cf on the 40,000-shoe file, 29 Sep 15:25-17:12 -> S03 ComparisonReport build-26f0724 via final-report.sh, 29 Sep 19:26 to 30 Sep 09:01 -> report-final-26f0724.md -> (superseded) full-index dry runs, pilots and projections, 4-6 Oct (dry-classes, cbdd02b, c6cb59e, 65013f3, dae1701, 73df32c, 6efe316) -> S04 FullIndexPilot 1417 100 6 from a worktree, probably cbdd02b, 5 Oct 21:50-22:36 -> pilot-1417-100.txt -> S05 FullIndexRun c06c061, 6 Oct 03:10 to 7 Oct 03:51 -> fit.ckpt, 186 parts, trees.bin, results.ser, run.id -> S06 FullIndexPeek c06c061, 7 Oct 04:03 -> peek-c06c061-final.txt -> S07 StateRun twin, seed 20261007, 7 Oct about 04:04-07:17, build unknown -> twin-80.states -> S08 Calibration b99ccbf (after the superseded 1579078 run), 7 Oct 08:31-09:32 -> calibration-b99ccbf.md and .md.parts -> BoundRows d6a005c on the twin, 7 Oct 10:38 -> bounds-d6a005c.txt -> S09 CountSearch x47 at b99ccbf on the twin, queue from 7 Oct 09:40 -> search/*.log and *.out -> S10a SearchReport 38f4e60 (20:21:59) and 38f4e60-2 (20:45:20); fp.sh dry-2000 (20:22-20:33) -> S11 FullIndexRun choice-20000-38f4e60 on the twin (7 Oct 20:45 to 8 Oct 11:05), then SearchReport fullplay and FullIndexPeek -> choice-20000-38f4e60.md -> S10b CountSearch lane9, then SearchReport 0b85be2 (8 Oct 11:18-11:26) -> search-0b85be2.* -> S12 registration commit 97bf834 in git (custom-counts.json, custom-counts-fullplay.json) -> S13 step 7 ComparisonReport --save at 97bf834, 9 Oct 19:16-23:14, ledger line 19:36:49 -> report-ceiling-97bf834.ser and .md -> S14 step 8's dry run on the twin (attempt 1 on 10 Oct; 12 Oct 11:53-12:10) -> S15 step 8 FullIndexRun --counts at 97bf834, trees read, 12 Oct 12:14-17:30, ledger line 12:15:02 -> fullplay-main-97bf834/ -> (superseded) LiteratureCheck c7620b1, 12 Oct 15:02; the literature cache was fetched with curl the same day -> S16 row 1 at 304901c (queue-304901c.sh), 13 Oct 23:43-23:44: CeilingResults full, BoundRows twin preview, optimism -> S17 row 4 replay gate 0, 13 Oct 23:44 to 14 Oct 17:13 -> S18 row 2 KeyEdgesRun a and a-prime, 14 Oct 17:13-18:30 -> S19 row 3 FullIndexPilot --wide, 14 Oct 18:30-22:43 -> S20 row 9 SplitMargin stride 1000, restarts, rearranged, 14 Oct 22:43-22:57 -> S21 SplitMargin stride 68 and rearranged (split-s68-304901c.sh), done 15 Oct 18:25 -> S22 row 5 StrategyExport part 1 at 0e0920d, 16 Oct 00:16 -> sim/export-part1-0e0920d/ -> S23 sim pilot part 1 (00:17-00:18), then SimCheckPlan fill into git -> S24 sim p2 and p3 on part 1 (sim-part1-0e0920d.sh), 00:19-01:09 -> S25 row 6 replay of c06c061 at 0e0920d, 16 Oct 01:13-19:55 -> wide.ser, sink run-b.ser, replay.txt; export refused -> S26 (superseded) WideReport 0e0920d, 16 Oct 19:57 -> S27 row 7 PageLook attempt 1 at 0e0920d, 17 Oct 00:07-00:35: ledger line and look.txt, then failed in firstDecisions -> S28 PageLook --resume at 0a447de, 00:40-04:28 -> run-c.ser, export-first, run-d/run-b.ser; export-full refused -> S29 (superseded) row10-partial-0a447de.sh, 05:04-05:10, stopped at SiteAdapt edges -> S30 row10-partial-7c1d40e.sh, 05:13:34-05:19:50: a CeilingResults full, b optimism, c data, d SiteAdapt ceiling, e SiteExport, f WideReport, g SiteAdapt wide, h KeyEdgesRun report, i SiteAdapt edges (read the 0a447de JSON), j SiteAdapt ledger, k LiteratureCheck, l SiteAdapt literature, m SiteAdapt evidence; then the unscripted SiteAdapt edges at 05:20:45 -> S31 build-site.sh: 7c1d40e refused (05:20:54), 7c1d40e-b (05:21:44), 79ff68b (05:24:04), 613255a (05:25:25) -> page-613255a/ -> S32 disk-cap check at 03b116f (--trees none, 2 items), 18 Oct 12:23-14:54 -> P1 (pending) item-1 fix, then the 15-item re-export from c06c061 -> sim/export-part2-replay-<sha>/ -> P2 (pending) PageLook --resume for F50/F52 -> export-full/; needs trees.bin or a --trees none option, export-first present, and the old replay/run.id removed -> P3 (pending) simulator part 2: 4 pilots, fill, p2/p3/variants, planted runs on B1 and B2 -> P4 (pending) RiskReport, then SiteAdapt risk -> P5 (pending) SiteAdapt sim -> P6 (pending) row 10 at the final tag (SiteExport only if the guard keys change; reuse optimism-7c1d40e), then SiteBuild

### Disagreements settled

- Code HEAD: top-down said b0ec21b, bottom-up e6d6b90. `git log` and `git show --stat` show e6d6b90 is b0ec21b plus one line in DISK_CLEANUP.md, so the code read is the same.
- Size and members of the result set: top-down had 106,895,338 B, bottom-up 108,699,882 B in 261 files. Merged and MEASURED: 264 files, 106,981,701 B.
- Out (bottom-up kept them):
  - the sim chunk folders, 1,945,070 B. SiteSim.java:24-25 and RiskReport read only the summaries, checks, depth, ev-by-key and swings.
  - perfect.ser from the main replay and the disk-cap check, 1,948,005 B. No code reads it; it is only written, at FullIndexReplay.java:912.
  - run.id files.
- In (top-down lacked them):
  - bounds-d6a005c.txt, cited at PLAN_COMPUTE.md:725.
  - look.txt, which PageLook.java:207 requires for P2.
  - gate 0's replay.txt, and the 47 CountSearch stdout .out files (42,750 B).
- Out (top-down kept all 38 *.log): logs of superseded runs and the six dry-run .err files (633 KB).
- Twin pending reader: top-down said none if optimism is reused; bottom-up said optimism reruns on every row-10 pass. The code settles it for top-down. CeilingResults data loads --optimism with no commit or hash check (CeilingResults.java:761-765), and the optimism files at 304901c, 0a447de and 7c1d40e differ only in line 1, the commit (MEASURED diff). Bottom-up is right that the committed script reruns it (row10-partial-7c1d40e.sh:22).
- Pending set without trees: top-down 2.82 GB, bottom-up 4.17 GB. The whole difference is the twin (1,341,183,296 B). MEASURED without the twin and trees: 2,822,771,164 B.
- pilot-1417-100.txt commit: top-down said not recorded, bottom-up said cbdd02b (FULL_INDEX_PLAYS.md:370).
- That line describes the pilot 'as first built', not this file.
- `git log --all -- src` shows cbdd02b (4 Oct 16:28) is the last src commit before the run (5 Oct 21:50).
- The header's '../../runs' path shows it ran from a worktree, so uncommitted edits cannot be excluded.
- Recorded as: probably cbdd02b, not confirmed.
- trees.bin readers: top-down left out gate 0. queue-304901c.sh:32 passes --trees-read-only trees.bin, so bottom-up is right.
- Inputs of wide.ser and the sink: bottom-up left out trees.bin and run.id. The replay log's first line says trees were read from trees.bin, and run.id is the source identity, so both are inputs.
- Who reads calibration-b99ccbf.md: top-down said its own .md.parts, bottom-up said CountSearch (the candidates). Neither is right.
- No program reads the .md or the candidates file.
- cs.sh:10 passes fixed settings and only the twin.
- SearchReport reads three .md.parts properties (report.sh:8-10, 13).
- Who reads choice-20000-38f4e60: top-down listed the row-9 split files. Only BoundRows reads it (queue-304901c.sh:27); SplitMargin reads only the twin (:36-38).
- How the literature cache was fetched: top-down said by hand, bottom-up said curl by an agent. LITERATURE.md:109-114 says an AI agent (Claude Code) fetched it with curl on 12 Oct. There is no committed fetch command either way.
- Reproducing c06c061 results.ser: top-down said bit-exact from the parts plus RoundStats; bottom-up said a rerun agrees only to 1e-12. Both are true for different operations. Reassembly needs results.ser's own 6.8 KB of RoundStats (working papers). A fresh rerun is expected to agree within 1e-12 (PLAN_PAGE.md:653-655).
- The page look's replay records: both maps said 'only a resume'. P2 is that resume, but it cannot use them.
- PageLook resumes the replay whenever replay/run.id exists (PageLook.java:466).
- The replay refuses a run.id naming another commit (FullIndexReplay.java:459, FullIndexCheckpoint.java:87).
- So under the item-1 classes they must be removed before P2. Neither map had this.
- export-first readers: both maps listed only simulator part 2. P2 reads it too: if export-first exists, the resume skips run C (PageLook.java:297); if not, run C reruns and run-c.ser is rewritten.
- Step 8 files that P2 needs: top-down said results, fit and parts, 0.27 GB. Run D replays folds only (PageLook.java:467), so P2 needs fit.ckpt, run.id and the 15 fold parts (151,643,443 B) plus results.ser for the Step8 hook (PageLook.java:477). The 15 halves (1,742,829 B) can go.
- Walls: both maps used shell or queue spans for some steps, for example the wide pilot at 4.2 h, KeyEdges a at 67 min and gate 0 at 17.5 h.
- The programs time themselves with System.nanoTime (FullIndexRun.java:231, FullIndexReplay.java:490, ComparisonReport.java:191).
- pmset -g log shows clamshell sleeps inside those spans: 14 Oct 00:11:57 to 18:17:54, and 18:41:13 to 22:22:29.
- The nodes give the in-program times. For example, the wide pilot's resamples took 1,008.3 s.
- The 15 parts P1 needs: both maps quoted 72,215,996 B from the working papers. MEASURED here: the fold parts of Hi-Lo, KO, Hi-Opt II, Zen and Victor APC, mapped from replay/c06c061-0e0920d.log, at 70/75/80.
- StateRun main command: both maps gave 60,000 shoes. montreal-80.log:1 shows it was started for shoes 0-39999 and extended to 60,000 when resumed on 26f0724 (log:1608).
- PageLook attempt 1: bottom-up said it failed; its starter log says rc=0. The attempt's page-look.log ends in a stack trace in PageLook.firstDecisions. The 'rc=' value is not real: `echo "$(date) ... rc=$?"` prints 0 after a failing command (MEASURED in zsh and sh).
- KeyEdgesRun a threads: top-down said none were given on the command line; bottom-up was silent. Confirmed: queue-304901c.sh:33 passes no --threads.
- Single-step peaks: bottom-up said SiteExport needs about 2.1 GB. MEASURED: its at-rest inputs (montreal, results.ser, step 7, step 8 results) are 2,087,802,713 B, before the results set.

### Gaps

- twin-80.states: its build is recorded nowhere (PLAN_PAGE.md:710-711). Neither StateRun command is in a script.
- montreal-80.states shoes 0-6649: montreal-80.log does not name the build (probably d3c0b1f). Neither the state file's SHA-256 (ed9e9fb4, measured on 18 Oct only) nor trees.bin's hash is committed.
- full-index/pilot-1417-100.txt: no commit or classes are recorded. It ran from a worktree. cbdd02b is inferred from git log only.
- No script records the commands or classes for the four FullIndexPeek outputs (peek-c06c061-final, the 6 Oct 21:39 peek, peek-choice-20000, fullplay-main peek) or for bounds-d6a005c.txt.
- custom-count/search/starts-*.txt (classes 11f99fd, c177ba8, ac7ab4d): no committed command made them. The start vectors were typed into lane*.jobs.
- Registration: the program that copied the search candidates into custom-counts.json and custom-counts-fullplay.json is not named (COUNT_DESIGN.md section 8 step 6).
- Step 7 has no in-program time because its .log is empty. Its 3.96 h is estimated from mtimes.
- report-final: 18,623.7 s of scoring against a 13 h 35 min span, unexplained. The run predates the pmset log window, which starts 11 Oct.
- Gate 0's records were written at 13:10 on 14 Oct, inside a logged clamshell sleep. So it is not established how much of each span was sleep and how much compute, or whether nanoTime pauses in dark wakes.
- Rows 5 and 6 (StrategyExport part 1, the sim pilot for part 1, the c06c061 replay) were typed from INTEGRATION.md, with no site-runs script. Their logs and run.id files hold the settings.
- site/key-edges-7c1d40e.json on disk came from an unscripted SiteAdapt edges call at 05:20:45. The scripted step read the 0a447de JSON.
- The page builds (7c1d40e, 7c1d40e-b, 79ff68b, 613255a) are in no script. build-site.sh compiles the working tree, so a dirty tree would go unrecorded, and its logs record no time.
- Exit statuses are unrecorded for PageLook (both starts), sim part 1's p2 and p3, and the s68 split run. Their 'rc=' lines always read 0.
- literature/cache: no committed fetch tool, and a re-fetch cannot be held to the recorded hashes.
- sim/classes-0e0920d and full-index/classes-cbdd02b have no commit.txt. No one has checked whether the custom-count and sim class dirs rebuild byte-identical.
- Untested whether reruns of KeyEdgesRun a and a-prime, step 7, step 8 or PageLook give the same SHA-256s. For step 7, step 8 and PageLook a rerun also means a new ledger line.
- Pending outputs have no measured sizes: export-part2-replay, export-full, sim part 2, risk, site/risk, site/sim and the final page.
- Under a 2.0e9 cap with nothing off the laptop, the pending chain needs code that does not exist yet: a PageLook --trees none option, SiteExport taking the state identity from a manifest, and smaller inputs for the replay and RiskReport. Which of these Justin wants is undecided.

### Nodes

- `git:src/,sim/,lib/ at each commit` [git-input, repository; lib jars 2,314,903 (mongo) + 2,680,679 (junit) (MEASURED stat)] by committed code; read at .worktrees/disk-cap HEAD e6d6b90 (b0ec21b plus one DISK_CLEANUP.md line, git show --stat). Inputs: . Consumers: S01 freeze classes, S31 SiteBuild (build-site.sh:39-41 compiles the working tree's src into mktemp). Wall: n/a. 14 of the 17 commits the evidence table cites are on no remote (working papers). Free at rest (git).
- `git:counting-systems.json` [git-input, 68,040 (MEASURED)] by committed catalog, copied into each classes dir at freeze (queue-304901c.sh:15-19). Inputs: . Consumers: every engine step (read from its classes dir), S24 sim p2 --catalog, P3 sim part 2. Wall: n/a. Cited d63b01b9.
- `git:seeds (command-line and code constants)` [git-input, 0] by StateRun seeds 20260928 (main) and 20261007 (twin), q0 0.008, u0 0.02, cut 332; bootstrap seed 20260929. Inputs: . Consumers: S02 StateRun main, S07 StateRun twin. Wall: n/a. Roots of the graph.
- `git:.worktrees/count-design/custom-counts.json` [git-input, 9,693 (MEASURED), blob 39bd6d4a] by S12 registration commit 97bf834; copied from search-0b85be2.candidates.json 'by a program' that is not named (COUNT_DESIGN.md section 8 step 6). Inputs: custom-count/search-0b85be2.{md,candidates.json,fullplay.json}, custom-count/fullplay/choice-20000-38f4e60.md + peek-choice-20000-38f4e60.txt. Consumers: S13 step 7, S16 row 1, S27/S28 PageLook, S30a,b,c,d,e,k, P2 PageLook. Wall: n/a. Cited 87e56e7e. Read by absolute .worktrees/count-design path.
- `git:.worktrees/count-design/custom-counts-fullplay.json` [git-input, 5,034 (MEASURED), blob 250e64a7] by S12 registration. Inputs: custom-count/fullplay/choice-20000-38f4e60.md + peek-choice-20000-38f4e60.txt. Consumers: S15 step 8, S16a, S27/S28 PageLook, S30a,c,e, P2 PageLook. Wall: n/a. 
- `git:.worktrees/count-design/custom-count-ledger.tsv` [git-input, 922 (MEASURED)] by lines written by S13 (9 Oct 19:36:49), S15 (12 Oct 12:15:02) and S27 (17 Oct 00:07:06), each committed by hand. Inputs: . Consumers: S15 (needs step 7's line), S27/S28 and P2 PageLook checks (PageLook.java:194-206), S30j SiteAdapt ledger. Wall: n/a. A run writes to it: rerunning step 7, step 8 or a fresh PageLook adds a line (PageLook.java:202 refuses a second look).
- `git:.worktrees/count-design/CEILING_RESULT_PARAGRAPHS.md` [git-input, 3,870 (MEASURED)] by written before the look. Inputs: . Consumers: S30c CeilingResults data, S30d SiteAdapt ceiling. Wall: n/a. 
- `git:.worktrees/site-int/literature/claims.json` [git-input, 97,855 (MEASURED)] by committed; holds each cached page's SHA-256. Inputs: . Consumers: S30k LiteratureCheck. Wall: n/a. Cited da7b74fe.
- `git:.worktrees/site-int/verification/evidence.tsv (+ .git history)` [git-input, 6,383 (MEASURED)] by committed. Inputs: . Consumers: S30m SiteAdapt evidence. Wall: n/a. Needs the local .git: rows cite commits.
- `git:.worktrees/site-int/site/template.html, page.css, page.js` [git-input, 39,306 + 9,619 + 4,497 (MEASURED)] by committed. Inputs: . Consumers: S30e SiteExport (guard keys, SiteExport.java:284-294), S31 SiteBuild, P6. Wall: n/a. A guard-key change forces SiteExport to rerun, which reads montreal's header and the three cited .ser files.
- `git:.worktrees/site-int/SIM_CHECK.md + sim-pilot-part1.tsv` [git-input, 52,342 + 1,856 (MEASURED)] by committed; section 8 printed by SimCheckPlan fill from S23's pilot file; identity table cites export-part1's manifest 4a424be6 (SIM_CHECK.md:26). Inputs: sim/pilot-part1-0e0920d/. Consumers: S24 (--registry), P3, P5 SiteAdapt sim (--plan). Wall: n/a. 
- `git:../jbondurant.github.io/index.html` [git-input, 4,077 (MEASURED)] by website repo. Inputs: . Consumers: S31 SiteBuild (colour tokens), P6. Wall: n/a. 
- `classes-* and build-* (frozen classes)` [intermediate, 26,437,161 in 5,737 files (MEASURED stat sum)] by S01: javac of 'git archive <commit> src' plus commit.txt and the catalog (queue-304901c.sh:15-19; FULL_INDEX_PLAYS.md:458-462); build-site.sh compiles its own into mktemp. Inputs: git:src/,sim/,lib/ at each commit, git:counting-systems.json. Consumers: every engine and simulator step (-cp). Wall: 1 to 3 s each (PLAN_PAGE.md:699-702). Recompiling gave identical class files for build-d3c0b1f, d791438, 26f0724 and classes-c06c061 (PLAN_PAGE.md:699-702); custom-count and sim dirs unchecked; classes-cbdd02b and sim/classes-0e0920d have no commit.txt. The pending steps need new classes anyway. Can go now.
- `montreal-80.states (+ .progress)` [intermediate, 1,340,719,767 + 17 (MEASURED)] by S02 StateRun main (usage StateRun.java:13): started for 40,000 shoes (montreal-80.log:1 'shoes 0 to 39999'); shoes 0-6649 by a build the log does not name (probably d3c0b1f, PLAN_PAGE.md:703-709); resumed on build-d791438 (log:272) for 6650-39999 and on build-26f0724 (log:1608) extended to 60,000. Inputs: git:seeds (command-line and code constants), classes-* and build-* (frozen classes). Consumers: S03, S04, S05, S13, S15, S17, S18, S19, S22, S25, S27/S28, S30e SiteExport (header and identity only, SiteExport.java:575-580, StateStore.java:208-211), S32, P1, P2, P6 (SiteExport, if rerun). Wall: 26,065 + 17,238 + 3,650 = 46,953 s (13.0 h) on 8 threads (montreal-80.log segment ends). Identity = path, size and mtime 1790724357600 ms (FullIndexRun.java:392-394), embedded in c06c061's run.id and results.ser, step 7, step 8 and results-7c1d40e.json, and checked by the replay, PageLook and SiteExport (SiteExport.java:576-578). A rebuilt file needs its mtime set back to the millisecond and is not proven to give the same bytes (segment 1 agrees to 1e-12). SHA ed9e9fb4 is committed nowhere. Last reader P2, or P6 if SiteExport reruns. One-way door.
- `twin-80.states (+ .progress)` [intermediate, 1,341,183,279 + 17 (MEASURED)] by S07 StateRun runs/twin-80.states 20261007 0.008 0.02 60000 7 (twin-80.log:1); build recorded nowhere (PLAN_PAGE.md:710-711); command in no script. Inputs: git:seeds (command-line and code constants), classes-* and build-* (frozen classes). Consumers: S08, S09, S10a,b (SearchReport --states), S11 choice and dry runs, S14 step 8 dry run, S16 BoundRows preview and optimism, S20, S21, S29b, S30b CeilingResults optimism, BoundRows d6a005c. Wall: 11,637 s on 7 threads (twin-80.log last line). No pending step needs it in code: CeilingResults data loads --optimism with no commit or hash check (CeilingResults.java:761-765), and the 304901c, 0a447de and 7c1d40e optimism files differ only in their commit line (MEASURED diff). The committed row-10 script (row10-partial-7c1d40e.sh:22) and INTEGRATION.md row 1 rerun optimism, so they would read it. SHA a2f8a751 is cited in the optimism header; the planned StateSpotCheck needs it. Build unknown, so a rebuild cannot be held to that hash. Can go now, one-way.
- `report-final-26f0724.md (+ .err, report-final.status)` [result, 841,034 + 0 + 97 (MEASURED)] by S03 ComparisonReport montreal --resamples 1000 --threads 7 from runs/build-26f0724, cwd .worktrees/clean (runs/final-report.sh). Inputs: montreal-80.states (+ .progress), classes-* and build-* (frozen classes), git:counting-systems.json. Consumers: S08 Calibration --printed, S13 step 7 --printed, S30e SiteExport --report-final, S30h KeyEdgesRun report --printed, S30k LiteratureCheck, P6. Wall: scoring 18,623.7 s on 7 threads by its own clock (report-final-26f0724.md:1840); start to finish 13 h 35 min (report-final.status). Cited 718a8e23.
- `full-index/pilot-1417-100.txt (+ .err)` [result, 53,657 + 0 (MEASURED)] by S04 FullIndexPilot montreal 1417 100 6, run from a worktree (header path '../../runs'); classes not recorded; cbdd02b is the only src commit between 4 Oct 16:28 and the start (git log), so probably cbdd02b. Inputs: montreal-80.states (+ .progress), classes-* and build-* (frozen classes). Consumers: S16a, S29a,c, S30a,c CeilingResults --widening, S30e SiteExport --widening, P6. Wall: resamples 1,204.0 s; 21:50:10 to 22:36:22 on 5 Oct, 6 threads (file lines 4, 298, 326). Cited 0018be90.
- `full-index/full-c06c061/{fit.ckpt, run.id}` [intermediate, 183,689,022 + 1,178 (MEASURED)] by S05 FullIndexRun --states montreal --resamples 1000 --threads 6 from classes-c06c061 (FULL_INDEX_PLAYS.md:464-466). Inputs: montreal-80.states (+ .progress), classes-* and build-* (frozen classes), git:counting-systems.json. Consumers: S06 FullIndexPeek, S17 gate 0, S25 replay, S32 disk-cap check, P1 item-1 re-export. Wall: the run's passes, rounds 0-5, 6.88 h (full-c06c061.log). fit.ckpt SHA 479595d1 is in every replay identity (FullIndexReplay.java:460, 555); run.id holds the states identity. Last reader P1.
- `full-index/full-c06c061/part-{1,2,5,8,23}-{0,1,2}-folds.ckpt (15 parts)` [intermediate, 72,215,996 (MEASURED: the fold parts of Hi-Lo, KO, Hi-Opt II, Zen and Victor APC, mapped from replay/c06c061-0e0920d.log)] by S05. Inputs: montreal-80.states (+ .progress), classes-* and build-* (frozen classes). Consumers: S17 (part-1-0), S25, S32 (part-1-2, part-23-2), P1 (FullIndexReplay.java:548 refuses an item with no part). Wall: inside S05. Byte-exact slices of results.ser apart from Result.seconds (working papers, 186/186). Last reader P1.
- `full-index/full-c06c061/part-*.ckpt, the other 171` [intermediate, 358,798,073 in 171 files (MEASURED; 431,014,069 for all 186)] by S05. Inputs: montreal-80.states (+ .progress), classes-* and build-* (frozen classes). Consumers: S25 replay (the other 78 folds). Wall: inside S05. No pending reader. Can go now.
- `full-index/full-c06c061/results.ser` [intermediate, 430,694,877 (MEASURED)] by S05 (FullIndexRun.java:341-350). Inputs: montreal-80.states (+ .progress), classes-* and build-* (frozen classes), git:counting-systems.json. Consumers: S06, S08, S13, S16a,b, S18, S22, S29, S30a,b,c, S30e SiteExport (SiteExport.java:369), S30h KeyEdgesRun report (perfect only, KeyEdgesReport.java:411), P4 RiskReport, P5 SiteAdapt sim --published, P6. Wall: 19.8 h by its own clock on 6 threads (full-c06c061.log last line); run.id 6 Oct 03:10 to results.ser 7 Oct 03:51 is 24.7 h (mtimes). Cited 8ba1412b. A fresh rerun is expected to agree only within 1e-12 (PLAN_PAGE.md:653-655); it holds 6.8 KB of last-round RoundStats found nowhere else (working papers). Last reader P5/P6. One-way door.
- `full-index/full-c06c061/trees.bin (+ .index)` [intermediate, 14,877,661,504 + 7,791 (MEASURED)] by S05, round 0. Inputs: montreal-80.states (+ .progress), classes-* and build-* (frozen classes). Consumers: S05 rounds 1-48, S15 step 8, S17, S25, S27/S28 PageLook, P2 PageLook (PageLook.java:178-180, 241-245 require a kept store). Wall: round 0 9,096.5 s (full-c06c061.log:15). Only saves time: rebuilt trees give the same bits (DiskBudget.java:20-22; disk-cap check). No hash recorded. Last reader P2; under any 2.0e9 cap P2 cannot run until PageLook has a --trees none option.
- `full-index/peek-c06c061-final.txt` [result, 11,307 (MEASURED)] by S06 FullIndexPeek on full-c06c061 (usage FullIndexPeek.java:22); command and stdout redirect in no script. Inputs: full-index/full-c06c061/results.ser, full-index/full-c06c061/{fit.ckpt, run.id}. Consumers: S16a, S29a,c, S30a,c CeilingResults --peek (checkPeek, CeilingResults.java:745). Wall: seconds (ESTIMATED); read at 7 Oct 04:03:23. Cited cfa5d96a.
- `custom-count/calibration-b99ccbf.md + .candidates.json` [result, 35,313 + 7,824 (MEASURED)] by S08 Calibration --states twin --published c06c061 --printed report-final --twin-log --threads 7 (COUNT_DESIGN.md:1090-1093), classes-b99ccbf. Inputs: twin-80.states (+ .progress), full-index/full-c06c061/results.ser, report-final-26f0724.md (+ .err, report-final.status), logs and scripts (provenance: *.log, *.out, *.err, *.status, *.nohup, *.sh, *.jobs), classes-* and build-* (frozen classes). Consumers: none by code (cs.sh:10 passes fixed settings; CountSearch reads no calibration file). Wall: 61 min on 7 threads (calibration-b99ccbf.log 08:31:30 to 09:32:50, 7 Oct). Reads twin-80.log (in the provenance node).
- `custom-count/calibration-b99ccbf.md.parts/` [intermediate, 57,858 in 23 (MEASURED)] by S08, its resumable sections. Inputs: twin-80.states (+ .progress), full-index/full-c06c061/results.ser. Consumers: S10a,b SearchReport via report.sh:8-10, 13 (04-noise-500, 10-theory, 11-effects-of-removal). Wall: inside S08. No pending reader. Can go now.
- `custom-count/bounds-d6a005c.txt` [result, 1,950 (MEASURED)] by BoundRows on the twin at d6a005c (7 Oct 10:38); command not recorded. Inputs: twin-80.states (+ .progress). Consumers: none by code. Wall: 14 s (file line 1). Cited PLAN_COMPUTE.md:725; tag planned PLAN_PAGE.md:720.
- `custom-count/search/<run>.log (48 CountSearch logs)` [intermediate, 7,843,948 in 48 (MEASURED)] by S09: 47 CountSearch runs, cs.sh + queue.sh + lane*.jobs, classes-b99ccbf. Inputs: twin-80.states (+ .progress), classes-* and build-* (frozen classes). Consumers: S10a,b SearchReport --logs (report.sh:12). Wall: queue.log 7 Oct 09:40:26 to 8 Oct 11:18:25, a span that includes S10a, S11 and sleep; PLAN_PAGE.md:802 says 5 to 6 h of machine time. Their stdout .out files (42,750 B) are kept with the provenance. No pending reader. Can go now.
- `custom-count/search/starts-*.txt (+ .err)` [result, 10,626 (MEASURED)] by start vectors, made by classes 11f99fd, c177ba8 and ac7ab4d; no committed command. Inputs: twin-80.states (+ .progress). Consumers: none by code (vectors typed into lane*.jobs). Wall: not recorded. The only record of where the search's starts came from.
- `custom-count/search-38f4e60*.{md,candidates.json,fullplay.json} (+ logs)` [intermediate, 58,000 in 8 (MEASURED)] by S10a SearchReport report via report.sh 38f4e60, twice. Inputs: twin-80.states (+ .progress), custom-count/search/<run>.log (48 CountSearch logs), custom-count/calibration-b99ccbf.md.parts/, classes-* and build-* (frozen classes). Consumers: S11 choice (fp.sh given search-38f4e60.fullplay.json). Wall: 6 min 40 s and 6 min 51 s (queue.log). Superseded by search-0b85be2; counted in the superseded total.
- `custom-count/fullplay/choice-20000-38f4e60/` [intermediate, 53,644,136 in 19 (MEASURED)] by S11 fp.sh 38f4e60 choice-20000-38f4e60 20000 search-38f4e60.fullplay.json: twin, --resamples 0 --trees none --pens 2, 6 threads. Inputs: twin-80.states (+ .progress), custom-count/search-38f4e60*.{md,candidates.json,fullplay.json} (+ logs), classes-* and build-* (frozen classes). Consumers: S11 SearchReport fullplay and FullIndexPeek, S16 BoundRows twin preview (--perfect results.ser, queue-304901c.sh:27). Wall: 1.7 h by its own clock (choice log:111); queue span 20:45:24 to 11:06:07 (14.3 h). No pending reader. Can go now.
- `custom-count/fullplay/choice-20000-38f4e60.md + peek-choice-20000-38f4e60.txt` [result, 1,547 + 4,834 (MEASURED)] by S11 SearchReport fullplay (after-choice.sh:10-11) and FullIndexPeek (command not recorded). Inputs: custom-count/fullplay/choice-20000-38f4e60/. Consumers: S12 registration. Wall: seconds. The full-play F1 choice.
- `custom-count/search-0b85be2.{md,candidates.json,fullplay.json}` [result, 25,462 + 1,729 + 2,118 (MEASURED)] by S10b after-choice.sh: lane9, then report.sh 0b85be2 (SearchReport report), classes-0b85be2. Inputs: twin-80.states (+ .progress), custom-count/search/<run>.log (48 CountSearch logs), custom-count/calibration-b99ccbf.md.parts/, logs and scripts (provenance: *.log, *.out, *.err, *.status, *.nohup, *.sh, *.jobs), classes-* and build-* (frozen classes). Consumers: S12 registration. Wall: 8 min 21 s on 6 threads (queue.log 11:18:25 to 11:26:46, 8 Oct). Also reads custom-count/search/queue.log (--queue-log, report.sh:15).
- `report-ceiling-97bf834.ser` [intermediate, 202,956,286 (MEASURED)] by S13 step 7: ComparisonReport montreal --published c06c061 --printed report-final --counts custom-counts.json --save --resamples 1000 --threads 7 (COUNT_DESIGN.md:1200-1206), classes-97bf834; ledger line 19:36:49. Inputs: montreal-80.states (+ .progress), full-index/full-c06c061/results.ser, report-final-26f0724.md (+ .err, report-final.status), git:.worktrees/count-design/custom-counts.json, git:counting-systems.json, classes-* and build-* (frozen classes). Consumers: S16a,b, S22 --chart-from, S27/S28 PageLook --saved, S29, S30a,b,c, S30e (SiteExport.java:456), S30k, P2 PageLook, P4 RiskReport, P6. Wall: about 3.96 h on 7 threads (ESTIMATED: .log created 19:16:43, .md and .ser written 23:14:25 on 9 Oct; PLAN_COMPUTE.md:19); the .log is empty. Cited c854767f. Does not resume; a rerun writes a second ledger line of the same look (COUNT_DESIGN.md:1212-1214). Last reader P4/P6. One-way door.
- `report-ceiling-97bf834.md (+ .log)` [result, 132,766 + 0 (MEASURED)] by S13 stdout. Inputs: montreal-80.states (+ .progress), full-index/full-c06c061/results.ser, report-final-26f0724.md (+ .err, report-final.status), git:.worktrees/count-design/custom-counts.json, git:counting-systems.json, classes-* and build-* (frozen classes). Consumers: S16a, S29a,c, S30a,c --report, S30k --ceiling-report. Wall: see report-ceiling-97bf834.ser. Cited 18763003.
- `custom-count/fullplay-main-97bf834/results.ser` [intermediate, 113,431,766 (MEASURED)] by S15 step 8: FullIndexRun --counts custom-counts-fullplay.json --trees-read-only c06c061 trees --threads 6 --resamples 1000 --pens 0,1,2 (start-step8-on-ac.sh), classes-97bf834. Inputs: montreal-80.states (+ .progress), git:.worktrees/count-design/custom-counts-fullplay.json, full-index/full-c06c061/trees.bin (+ .index), git:counting-systems.json, classes-* and build-* (frozen classes). Consumers: S16a, S29, S30a,c, S30e (SiteExport.java:476-480), S28 and P2 PageLook Step8 hook (PageLook.java:477), P4 RiskReport, P6. Wall: 5.3 h by its own clock on 6 threads (fullplay-main-97bf834.log last line); 12:14:39 to 17:30:43 on 12 Oct. Cited 6a456558. Part of the one look (ledger line at 12:15:02). Last reader P4/P6. One-way door.
- `custom-count/fullplay-main-97bf834/{fit.ckpt, run.id, 15 fold parts}` [intermediate, 40,239,384 + 3,023 + 111,401,036 (MEASURED)] by S15. Inputs: montreal-80.states (+ .progress), full-index/full-c06c061/trees.bin (+ .index), classes-* and build-* (frozen classes). Consumers: S28 PageLook run D, P2 PageLook run D (folds only, PageLook.java:467). Wall: inside S15. fit.ckpt SHA 0a2b8c88 is in run D's identity. Last reader P2.
- `custom-count/fullplay-main-97bf834/part-*-halves.ckpt (15)` [intermediate, 1,742,829 (MEASURED)] by S15. Inputs: montreal-80.states (+ .progress), full-index/full-c06c061/trees.bin (+ .index), classes-* and build-* (frozen classes). Consumers: none (run D replays folds only). Wall: inside S15. Can go now.
- `custom-count/fullplay-main-97bf834.peek.txt` [result, 3,494 (MEASURED)] by FullIndexPeek on step 8 (command not recorded). Inputs: custom-count/fullplay-main-97bf834/results.ser. Consumers: none by code. Wall: seconds. 
- `ceiling/bounds-twin-preview-304901c.txt` [result, 6,088 (MEASURED)] by S16 row 1 BoundRows twin --perfect choice results.ser --shoes 20000 (queue-304901c.sh:27), classes-304901c. Inputs: twin-80.states (+ .progress), custom-count/fullplay/choice-20000-38f4e60/, classes-* and build-* (frozen classes). Consumers: none by code. Wall: 1 s (queue-304901c.log). A check. Row 1's CeilingResults full and optimism at 304901c are superseded.
- `replay/gate0-304901c/replay.txt` [result, 2,724 (MEASURED; its log, 4,813, is in the provenance node)] by S17 row 4 replay gate 0: FullIndexRun --fit-from c06c061 --trees-read-only --only Hi-Lo@0 --wide --sink --export (queue-304901c.sh:32), classes-304901c. Inputs: montreal-80.states (+ .progress), full-index/full-c06c061/{fit.ckpt, run.id}, full-index/full-c06c061/part-{1,2,5,8,23}-{0,1,2}-folds.ckpt (15 parts), full-index/full-c06c061/trees.bin (+ .index), classes-* and build-* (frozen classes). Consumers: none by code (the go/no-go for row 6). Wall: 0.70 h by its own clock (log:33); queue span 13 Oct 23:44:42 to 14 Oct 17:13:28 contains a clamshell sleep from 00:11:57 (pmset log). Its records, wide, sink and export (46,277,405 B) are superseded.
- `key-edges/a-304901c*.ser` [intermediate, 221,351,385 (MEASURED)] by S18 row 2 KeyEdgesRun a montreal --published c06c061 (queue-304901c.sh:33; no --threads given), classes-304901c. Inputs: montreal-80.states (+ .progress), full-index/full-c06c061/results.ser, classes-* and build-* (frozen classes). Consumers: S29h, S30h KeyEdgesRun report --a. Wall: 253.9 s by its own clock (queue-304901c.out); its 67 min queue span ends after a wake at 18:17:54 (pmset). Cited 56148393 in the key-edges JSON. Rebuilding to the bit is claimed by one plan and judged an overclaim (working papers:125). No pending reader.
- `key-edges/a-prime-304901c*.ser` [intermediate, 65,554,901 (MEASURED)] by S18 KeyEdgesRun a-prime --threads 7 (queue-304901c.sh:34). Inputs: montreal-80.states (+ .progress), full-index/full-c06c061/results.ser, classes-* and build-* (frozen classes). Consumers: S29h, S30h --a-prime. Wall: 611.8 s on 7 threads (queue-304901c.out). Cited c649f44f. No pending reader.
- `full-index/pilot-1417-100-wide-304901c.txt` [result, 73,599 (MEASURED)] by S19 row 3 FullIndexPilot montreal 1417 100 6 --wide (queue-304901c.sh:35), classes-304901c. Inputs: montreal-80.states (+ .progress), classes-* and build-* (frozen classes). Consumers: S26, S29f, S30f WideReport --widening, P4 RiskReport --widening. Wall: resamples 1,008.3 s by its own clock (file line 603); span 18:30:52 to 22:43:09 contains a clamshell sleep 18:41:13 to 22:22:29 (pmset). Cited c90f91e3 in report-wide.
- `ceiling/split-margin-304901c.tsv, split-restarts-304901c.txt, split-rearranged-304901c.txt` [result, 100,301 + 2,969 + 1,559 (MEASURED)] by S20 row 9 SplitMargin twin 1000 7 --out; --restarts 8 --restart-states 40; --rearranged on the tsv (queue-304901c.sh:36-38). Inputs: twin-80.states (+ .progress), classes-* and build-* (frozen classes). Consumers: none by code. Wall: 157 s, 12 min 1 s and 1 s on 7 threads (queue-304901c.out and .log). The restarts file is the only restart evidence: the s68 run made 0 restarts (s68.out line 1).
- `ceiling/split-margin-304901c-s68.tsv + split-rearranged-304901c-s68.txt` [result, 1,451,850 + 1,560 (MEASURED; .out 2,453 in provenance)] by S21 SplitMargin twin 68 7 --out, then --rearranged (split-s68-304901c.sh), classes-304901c. Inputs: twin-80.states (+ .progress), classes-* and build-* (frozen classes). Consumers: S29a,c, S30a,c --split-margin. Wall: 2,499 s on 7 threads (s68.out line 1); done 15 Oct 18:25:41. 894 states. Cited 433124c8.
- `sim/export-part1-0e0920d/` [intermediate, 54,632,498 in 25 (MEASURED)] by S22 StrategyExport montreal --published c06c061 --chart-from step 7 @70,75,80 --plans same-2,same-4,same-8,own-8 (INTEGRATION.md:543-551; no script), classes-0e0920d. Inputs: montreal-80.states (+ .progress), full-index/full-c06c061/results.ser, report-ceiling-97bf834.ser, classes-* and build-* (frozen classes). Consumers: S23, S24, P3 (pilot on part 1's bundle; planted runs on B1). Wall: about 1 min (ESTIMATED: files 00:16:28 to 00:16:39, log 00:17:08 on 16 Oct). manifest.sha256 4a424be6 is committed (SIM_CHECK.md:26), so a remade bundle must match it. Last reader P3.
- `sim/pilot-part1-0e0920d/` [result, 2,604 in 2 (MEASURED; log 864 in provenance)] by S23 sim.Sim pilot --part 1 (INTEGRATION.md:564-566), sim/classes-0e0920d. Inputs: sim/export-part1-0e0920d/, git:.worktrees/site-int/SIM_CHECK.md + sim-pilot-part1.tsv, git:counting-systems.json, classes-* and build-* (frozen classes). Consumers: SimCheckPlan fill into git:SIM_CHECK.md. Wall: about 1 min (ESTIMATED from mtimes 00:17:08 to 00:18:25). Copied into git as sim-pilot-part1.tsv.
- `sim/part1-0e0920d/{summary-p2.json, summary-p3.json, checks.txt, depth.tsv, ev-by-key.tsv, swings*.tsv}` [result, 125,094 in 8 (MEASURED)] by S24 sim.Sim p2 --k 120000 and p3 --chunks 38, --sim-commit fe9a91e (site-runs/sim-part1-0e0920d.sh). Inputs: sim/export-part1-0e0920d/, git:.worktrees/site-int/SIM_CHECK.md + sim-pilot-part1.tsv, git:counting-systems.json, classes-* and build-* (frozen classes). Consumers: P4 RiskReport --swings (INTEGRATION.md:731-738), P5 SiteAdapt sim --run (SiteSim.java:24-25). Wall: p2 about 17 min (checks.txt 00:19:22 to 00:36:48), p3 33 min (to 01:09:53), 6 threads. 
- `sim/part1-0e0920d/{p2,p3,p3-records}/ (chunks)` [intermediate, 1,945,070 in 99 (MEASURED)] by S24. Inputs: sim/export-part1-0e0920d/, classes-* and build-* (frozen classes). Consumers: S24's own summaries. Wall: inside S24. SiteSim and RiskReport read none of them (SiteSim.java:24-25). Can go now.
- `replay/c06c061-0e0920d/wide.ser` [intermediate, 249,769,420 (MEASURED)] by S25 row 6: FullIndexRun --fit-from c06c061 --states montreal --trees-read-only trees.bin --resamples 1000 --threads 6 --folds-only --wide --sink --export (INTEGRATION.md:588-592, launched by hand), classes-0e0920d; the export was refused at the end (log:534). Inputs: montreal-80.states (+ .progress), full-index/full-c06c061/{fit.ckpt, run.id}, full-index/full-c06c061/part-{1,2,5,8,23}-{0,1,2}-folds.ckpt (15 parts), full-index/full-c06c061/part-*.ckpt, the other 171, full-index/full-c06c061/trees.bin (+ .index), classes-* and build-* (frozen classes). Consumers: S26, S29f, S30f WideReport, P4 RiskReport --source replay=. Wall: 27 batches: last rounds 15,145.1 s + scoring 38,486.7 s = 14.90 h by its own clock, 6 threads (MEASURED from c06c061-0e0920d.log); run.id 01:13:58 to 19:55:31 on 16 Oct is 18.7 h. Cited ee3ff121 in the spreads JSON. Remaking it means the whole 93-item replay. Last reader P4.
- `replay/c06c061-0e0920d-sink/run-b.ser` [intermediate, 704,599,811 (MEASURED)] by S25, the sink hook. Inputs: montreal-80.states (+ .progress), full-index/full-c06c061/{fit.ckpt, run.id}, full-index/full-c06c061/part-{1,2,5,8,23}-{0,1,2}-folds.ckpt (15 parts), full-index/full-c06c061/part-*.ckpt, the other 171, full-index/full-c06c061/trees.bin (+ .index), classes-* and build-* (frozen classes). Consumers: S29h, S30h KeyEdgesRun report --full. Wall: see S25. Cited 3c1866b6 in the key-edges JSON. No pending reader; only a rerun of the KeyEdges report reads it.
- `replay/c06c061-0e0920d/{replayed-*.ckpt, perfect.ser, run.id}` [intermediate, 928,881,429 in 93 + 974,439 + 2,431 (MEASURED)] by S25. Inputs: full-index/full-c06c061/{fit.ckpt, run.id}, full-index/full-c06c061/part-{1,2,5,8,23}-{0,1,2}-folds.ckpt (15 parts), full-index/full-c06c061/part-*.ckpt, the other 171. Consumers: only a resume of this replay (FullIndexReplay.java:661). Wall: see S25. A resume under other classes is refused: the identity names the commit (FullIndexReplay.java:459, FullIndexCheckpoint.java:87). No code reads perfect.ser (written at FullIndexReplay.java:912). Left because the refused export skipped letGo (FullIndexRun.java:219). Can go now.
- `replay/c06c061-0e0920d/replay.txt` [result, 51,657 (MEASURED; log 136,691 in provenance)] by S25. Inputs: replay/c06c061-0e0920d/{replayed-*.ckpt, perfect.ser, run.id}. Consumers: none by code. Wall: see S25. 93 of 93 to the bit; evidence for PLAN_COMPUTE.md 4.2.
- `page-look-0e0920d/look.txt` [result, 900 (MEASURED)] by S27 PageLook attempt 1 at 0e0920d (start-pagelook-on-ac.sh) wrote it after its ledger line (PageLook.java:280-283), then failed in firstDecisions (attempt1 page-look.log). Inputs: montreal-80.states (+ .progress), report-ceiling-97bf834.ser, git:.worktrees/count-design/custom-counts.json, git:.worktrees/count-design/custom-counts-fullplay.json, git:.worktrees/count-design/custom-count-ledger.tsv, full-index/full-c06c061/trees.bin (+ .index), classes-* and build-* (frozen classes). Consumers: S28 resume, P2 resume (PageLook.java:207 requires it). Wall: seconds. The look's identity, with step 7's SHA-256.
- `page-look-0e0920d/run-c.ser` [intermediate, 245,680,412 (MEASURED)] by S28 PageLook --resume at 0a447de (pagelook-resume-0a447de.sh), run C. Inputs: montreal-80.states (+ .progress), report-ceiling-97bf834.ser, git:.worktrees/count-design/custom-counts.json, git:.worktrees/count-design/custom-count-ledger.tsv, full-index/full-c06c061/trees.bin (+ .index), classes-* and build-* (frozen classes). Consumers: S29h, S30h KeyEdgesRun report --rows. Wall: run C and its export 1,568.2 s on 7 threads (page-look-0e0920d.log:32). Cited 2939a4ea. No pending reader: P2 skips run C while export-first exists (PageLook.java:297). The look is made once (PageLook.java:202).
- `page-look-0e0920d/export-first/` [intermediate, 23,016,894 in 11 (MEASURED)] by S28 run C's export (F49, F51). Inputs: montreal-80.states (+ .progress), report-ceiling-97bf834.ser, full-index/full-c06c061/trees.bin (+ .index), classes-* and build-* (frozen classes). Consumers: P2 (its presence makes the resume skip run C, PageLook.java:297), P3 look-first pilot, p2, p3. Wall: inside run C's 1,568.2 s. Manifest d385bccd still to go into SIM_CHECK.md. Last reader P3.
- `page-look-0e0920d/run-d/run-b.ser` [intermediate, 191,051,009 (MEASURED)] by S28 run D, a replay of step 8 with the sink. Inputs: montreal-80.states (+ .progress), custom-count/fullplay-main-97bf834/{fit.ckpt, run.id, 15 fold parts}, custom-count/fullplay-main-97bf834/results.ser, full-index/full-c06c061/trees.bin (+ .index), classes-* and build-* (frozen classes). Consumers: S29h, S30h KeyEdgesRun report (second --full). Wall: run D's 10 batch lines sum to 11,915.0 s on 7 threads (page-look-0e0920d.log); resume 00:40 to 04:28:42. Cited 3fe732ce. No pending reader. export-full (F50, F52) was refused.
- `page-look-0e0920d/{wide-first.ser, replay/wide.ser}` [intermediate, 61,532,212 + 83,591,398 (MEASURED)] by S28. Inputs: montreal-80.states (+ .progress), full-index/full-c06c061/trees.bin (+ .index), classes-* and build-* (frozen classes). Consumers: none (no class and no INTEGRATION.md command reads them). Wall: inside S28. Cited nowhere. Can go now.
- `page-look-0e0920d/replay/{replayed-*.ckpt, perfect.ser, run.id}` [intermediate, 226,485,724 in 15 + 975,557 + 3,544 (MEASURED)] by S28 run D. Inputs: custom-count/fullplay-main-97bf834/{fit.ckpt, run.id, 15 fold parts}. Consumers: only a resume. Wall: inside S28. P2 cannot use them: PageLook resumes the replay whenever replay/run.id exists (PageLook.java:466), and that run.id names commit 0a447de, so under the item-1 classes the replay refuses (FullIndexReplay.java:459, FullIndexCheckpoint.java:87). They must go before P2. Left because PageLook exits before letGo (PageLook.java:532-535).
- `page-look-0e0920d/replay/replay.txt` [result, 11,753 (MEASURED)] by S28 run D. Inputs: page-look-0e0920d/replay/{replayed-*.ckpt, perfect.ser, run.id}. Consumers: none by code. Wall: inside S28. 15 of 15 to the bit.
- `ceiling/report-ceiling-full-7c1d40e.md` [result, 16,330 (MEASURED)] by S30a CeilingResults full (row10-partial-7c1d40e.sh:21), classes-7c1d40e. Inputs: full-index/full-c06c061/results.ser, report-ceiling-97bf834.ser, report-ceiling-97bf834.md (+ .log), full-index/peek-c06c061-final.txt, git:.worktrees/count-design/custom-counts.json, custom-count/fullplay-main-97bf834/results.ser, git:.worktrees/count-design/custom-counts-fullplay.json, ceiling/split-margin-304901c-s68.tsv + split-rearranged-304901c-s68.txt, full-index/pilot-1417-100.txt (+ .err), classes-* and build-* (frozen classes). Consumers: S30c --full-report. Wall: 1 s (row10 log 05:13:34 to 05:13:35). Cited 0806044e in the ceiling JSON.
- `ceiling/optimism-7c1d40e.properties + .md` [result, 10,778 + 9,088 (MEASURED)] by S30b CeilingResults optimism (row10-partial-7c1d40e.sh:22). Inputs: twin-80.states (+ .progress), git:.worktrees/count-design/custom-counts.json, full-index/full-c06c061/results.ser, report-ceiling-97bf834.ser, classes-* and build-* (frozen classes). Consumers: S30c --optimism, P6 (reuse it instead of rerunning). Wall: 59 s (05:13:35 to 05:14:34). Its header hashes all four inputs (CeilingResults.java:1400). Cited f4562fa0. The only page step that reads the twin.
- `ceiling/ceiling-7c1d40e.json` [result, 66,496 (MEASURED)] by S30c CeilingResults data (row10-partial-7c1d40e.sh:23). Inputs: full-index/full-c06c061/results.ser, report-ceiling-97bf834.ser, report-ceiling-97bf834.md (+ .log), full-index/peek-c06c061-final.txt, git:.worktrees/count-design/custom-counts.json, custom-count/fullplay-main-97bf834/results.ser, git:.worktrees/count-design/custom-counts-fullplay.json, ceiling/split-margin-304901c-s68.tsv + split-rearranged-304901c-s68.txt, ceiling/optimism-7c1d40e.properties + .md, git:.worktrees/count-design/CEILING_RESULT_PARAGRAPHS.md, ceiling/report-ceiling-full-7c1d40e.md, full-index/pilot-1417-100.txt (+ .err), classes-* and build-* (frozen classes). Consumers: S30d SiteAdapt ceiling. Wall: 1 s. Cited d9964301.
- `site/ceiling-7c1d40e.json` [result, 22,843 (MEASURED)] by S30d SiteAdapt ceiling. Inputs: ceiling/ceiling-7c1d40e.json, git:.worktrees/count-design/CEILING_RESULT_PARAGRAPHS.md, git:.worktrees/count-design/custom-counts.json, classes-* and build-* (frozen classes). Consumers: S31 SiteBuild. Wall: 1 s. Page input ee6e8832.
- `site/results-7c1d40e.json` [result, 2,137,930 (MEASURED)] by S30e SiteExport (row10-partial-7c1d40e.sh:25), -Xmx8g. Inputs: full-index/full-c06c061/results.ser, report-final-26f0724.md (+ .err, report-final.status), full-index/pilot-1417-100.txt (+ .err), git:.worktrees/site-int/site/template.html, page.css, page.js, montreal-80.states (+ .progress), report-ceiling-97bf834.ser, git:.worktrees/count-design/custom-counts.json, custom-count/fullplay-main-97bf834/results.ser, git:.worktrees/count-design/custom-counts-fullplay.json, classes-* and build-* (frozen classes). Consumers: S31 SiteBuild. Wall: 2 s (05:14:36 to 05:14:38). Page input b2c4f9a6. Holds montreal's identity string. Reruns when the template's guard keys change.
- `wide/spreads-7c1d40e.json + report-wide-7c1d40e.md` [result, 8,402,452 + 2,145,675 (MEASURED)] by S30f WideReport (row10-partial-7c1d40e.sh:26). Inputs: replay/c06c061-0e0920d/wide.ser, full-index/pilot-1417-100-wide-304901c.txt, classes-* and build-* (frozen classes). Consumers: S30g SiteAdapt wide. Wall: 4 s. Cited b86622c4.
- `site/spreads-7c1d40e.json` [result, 313,323 (MEASURED)] by S30g SiteAdapt wide. Inputs: wide/spreads-7c1d40e.json + report-wide-7c1d40e.md, classes-* and build-* (frozen classes). Consumers: S31 SiteBuild. Wall: under 1 s. Page input e582f099.
- `key-edges/key-edges-7c1d40e*.json + .csv` [result, 56,333,649 + 21,271,971 (MEASURED)] by S30h KeyEdgesRun report (row10-partial-7c1d40e.sh:28). Inputs: key-edges/a-304901c*.ser, key-edges/a-prime-304901c*.ser, full-index/full-c06c061/results.ser, replay/c06c061-0e0920d-sink/run-b.ser, page-look-0e0920d/run-c.ser, page-look-0e0920d/run-d/run-b.ser, report-final-26f0724.md (+ .err, report-final.status), classes-* and build-* (frozen classes). Consumers: S30i SiteAdapt edges (the hand rerun). Wall: 294.0 s (row10 log.out). Cited 824f0769. 72.5% of the result set.
- `site/key-edges-7c1d40e.json` [result, 472,563 (MEASURED)] by S30i SiteAdapt edges, unscripted rerun at 05:20:45 naming the 7c1d40e JSON. The scripted step (row10-partial-7c1d40e.sh:29) globbed key-edges-*.json and read the 0a447de file (log.out: '817 figures from ...0a447de...json'); that output is site-old-0a447de/key-edges-7c1d40e-from-0a447de-report.json (05:19:37). Inputs: key-edges/key-edges-7c1d40e*.json + .csv, classes-* and build-* (frozen classes). Consumers: S31 SiteBuild. Wall: 1 s. Page input 32efc530. Rewritten 9 s before the first page build (page-7c1d40e.log 05:20:54), so every page build used it.
- `site/ledger-7c1d40e.json` [result, 1,537 (MEASURED)] by S30j SiteAdapt ledger. Inputs: git:.worktrees/count-design/custom-count-ledger.tsv, classes-* and build-* (frozen classes). Consumers: S31 SiteBuild. Wall: under 1 s. Page input f8a45334.
- `literature/cache/` [result, 8,384,809 in 47 (MEASURED)] by fetched with curl by an AI agent on 12 Oct (LITERATURE.md:109-114); no committed command. Inputs: . Consumers: S30k LiteratureCheck --cache (each page checked against claims.json). Wall: n/a. Kept although an input: a re-fetch can change the bytes and fail the hash check. Third-party material.
- `literature/literature-7c1d40e.json + .md` [result, 157,434 + 63,836 (MEASURED)] by S30k LiteratureCheck (row10-partial-7c1d40e.sh:31). Inputs: git:.worktrees/site-int/literature/claims.json, literature/cache/, report-final-26f0724.md (+ .err, report-final.status), git:.worktrees/count-design/custom-counts.json, report-ceiling-97bf834.ser, report-ceiling-97bf834.md (+ .log), git:counting-systems.json, classes-* and build-* (frozen classes). Consumers: S30l SiteAdapt literature. Wall: 12 s (05:19:37 to 05:19:49). Reads no state file.
- `site/literature-7c1d40e.json` [result, 157,434 (MEASURED)] by S30l SiteAdapt literature, a checked copy. Inputs: literature/literature-7c1d40e.json + .md, classes-* and build-* (frozen classes). Consumers: S31 SiteBuild. Wall: under 1 s. Page input 2d2e4757.
- `site/evidence-7c1d40e.json` [result, 6,513 (MEASURED)] by S30m SiteAdapt evidence --repo site-int. Inputs: git:.worktrees/site-int/verification/evidence.tsv (+ .git history), classes-* and build-* (frozen classes). Consumers: S31 SiteBuild. Wall: 1 s. Page input 70a84362.
- `page-613255a/ (+ page-613255a.log)` [result, 3,179,185 in 48 + 372 (MEASURED)] by S31 site/build-site.sh --out runs/page-613255a --inputs runs/site at 613255a (not scripted; javac of the working tree into mktemp, build-site.sh:39-41). Inputs: site/ceiling-7c1d40e.json, site/results-7c1d40e.json, site/spreads-7c1d40e.json, site/key-edges-7c1d40e.json, site/ledger-7c1d40e.json, site/literature-7c1d40e.json, site/evidence-7c1d40e.json, git:.worktrees/site-int/site/template.html, page.css, page.js, git:../jbondurant.github.io/index.html, git:src/,sim/,lib/ at each commit. Consumers: copy into the website (Justin's yes), SiteBuild --check. Wall: 05:25:25 on 17 Oct; 3.8 s and 271 MB RSS (MEASURED by the 18 Oct page investigator; the log records no time). manifest.json lists the 7 inputs' SHA-256, equal to runs/site (MEASURED shasum). SiteBuild reads only runs/site/*.json (SiteData.java:148-161).
- `disk-cap-check/replay-trees-none-03b116f/{replay.txt, records-let-go.txt}` [result, 2,906 + 385 (MEASURED; log 4,327 in provenance)] by S32 FullIndexRun --fit-from c06c061 --trees none --only Hi-Lo@2, Victor APC@2 (site-runs/disk-cap-check-03b116f.sh), classes-03b116f. Inputs: montreal-80.states (+ .progress), full-index/full-c06c061/{fit.ckpt, run.id}, full-index/full-c06c061/part-{1,2,5,8,23}-{0,1,2}-folds.ckpt (15 parts), classes-* and build-* (frozen classes). Consumers: none by code (DISK_CLEANUP.md evidence). Wall: 2.55 h; last round 8,120.8 s with every tree built (its log). The measured cost of --trees none. Its perfect.ser and run.id (975,129 B) are intermediates that can go now.
- `superseded outputs (batch C and others)` [intermediate, 956,175,021 in 873 files (MEASURED partition)] by dry runs, pilots and projections (4-6 Oct); report-quick and report-all-200 at c3af4cf; calibration-1579078; search-38f4e60 reports; dry-2000 and step 8's dry runs; literature-c7620b1; row 1's 304901c ceiling copies; gate 0's records, wide, sink and export; WideReport 0e0920d; PageLook attempt 1; row10-partial-0a447de; site-old-0a447de; pages 7c1d40e-b and 79ff68b; and their logs. Inputs: montreal-80.states (+ .progress), twin-80.states (+ .progress), full-index/full-c06c061/{fit.ckpt, run.id}, full-index/full-c06c061/trees.bin (+ .index). Consumers: none: each was replaced by a later run whose output the page uses. Wall: n/a. page-look attempt1 .ser 307,212,624; full-index dry runs 426,424,606; fullplay dry runs 66,780,830; gate 0 46,277,405; earlier text and JSON copies 109,421,556 (key-edges 0a447de 77.6 MB of it); search-38f4e60 58,000. Can go now.
- `logs and scripts (provenance: *.log, *.out, *.err, *.status, *.nohup, *.sh, *.jobs)` [result, 858,245 in 113 (MEASURED)] by each run's stdout and stderr; scripts written by hand (site-runs/*.sh, final-report.sh, search/*.sh and *.jobs, fullplay/*.sh). Inputs: . Consumers: S08 Calibration (twin-80.log via --twin-log), S10b SearchReport (search/queue.log via --queue-log). Wall: n/a. montreal-80.log 247,700 and twin-80.log 248,195 are the only record of the state files' builds. Logs of superseded runs are counted in the superseded node. The 'rc=' lines of start-pagelook-on-ac.sh:18, pagelook-resume-0a447de.sh:3, sim-part1-0e0920d.sh:4,6 and split-s68-304901c.sh:4 always read 0, because $(date) runs first (MEASURED in zsh).
- `sim/export-part2-replay-<sha>/ (pending)` [intermediate, not on disk; about 0.06 GB (ESTIMATED, working papers)] by P1: the item-1 fix, then FullIndexRun --fit-from c06c061 --only (15 items) --export-row x15 into a new directory under new classes (INTEGRATION.md open item 1; DISK_2GB.md condition 1). Inputs: montreal-80.states (+ .progress), full-index/full-c06c061/{fit.ckpt, run.id}, full-index/full-c06c061/part-{1,2,5,8,23}-{0,1,2}-folds.ckpt (15 parts), classes-* and build-* (frozen classes). Consumers: P3 sim part 2 (the replay's bundle). Wall: not run; with --trees none about 0.5 h a batch (ESTIMATED; the disk-cap check's 2 items took 2.55 h). With --only, F43 to F48's wide plans are not attached (FullIndexReplay.java:418-438). runs/ would already hold 2,929,752,865 B before it writes (MEASURED sum, see report).
- `page-look-0e0920d/export-full/ (pending)` [intermediate, not on disk; about 0.02 GB (ESTIMATED, like export-first)] by P2: PageLook --resume into page-look-0e0920d under the item-1 classes (INTEGRATION.md:596-618). Inputs: montreal-80.states (+ .progress), report-ceiling-97bf834.ser, custom-count/fullplay-main-97bf834/{fit.ckpt, run.id, 15 fold parts}, custom-count/fullplay-main-97bf834/results.ser, full-index/full-c06c061/trees.bin (+ .index), page-look-0e0920d/look.txt, page-look-0e0920d/export-first/, git:.worktrees/count-design/custom-counts.json, git:.worktrees/count-design/custom-counts-fullplay.json, git:.worktrees/count-design/custom-count-ledger.tsv, classes-* and build-* (frozen classes). Consumers: P3 sim part 2 (look-full). Wall: not run; run D took 11,915 s with trees read. Needs trees.bin (PageLook.java:178-180), export-first present (:297), and page-look-0e0920d/replay/run.id gone (:466). Counts BYTES = 1e9 against the budget before it starts (PageLook.java:58, 247).
- `sim/part2-*, pilot-part2-*, part2-look-first-*, part2-look-full-*, *-plant-* (pending)` [result, not on disk (ESTIMATED small; part 1's run is 2.07 MB)] by P3: sim.Sim pilot x4, SimCheckPlan fill (git), p2, p3, variants, the planted runs (INTEGRATION.md:620-669). Inputs: sim/export-part2-replay-<sha>/ (pending), page-look-0e0920d/export-first/, page-look-0e0920d/export-full/ (pending), sim/export-part1-0e0920d/, git:.worktrees/site-int/SIM_CHECK.md + sim-pilot-part1.tsv, git:counting-systems.json, classes-* and build-* (frozen classes). Consumers: P4 RiskReport, P5 SiteAdapt sim. Wall: 2.5 to 4 h, budget 4.5 h (INTEGRATION.md:620, ESTIMATED). Summaries, checks, swings, records, ruin, trip and fan files are results; chunk folders intermediates.
- `risk/risk-<sha>.json + report-risk-<sha>.md (pending)` [result, not on disk (ESTIMATED small)] by P4 RiskReport (INTEGRATION.md:731-738). Inputs: full-index/full-c06c061/results.ser, report-ceiling-97bf834.ser, custom-count/fullplay-main-97bf834/results.ser, replay/c06c061-0e0920d/wide.ser, full-index/pilot-1417-100-wide-304901c.txt, sim/part1-0e0920d/{summary-p2.json, summary-p3.json, checks.txt, depth.tsv, ev-by-key.tsv, swings*.tsv}, sim/part2-*, pilot-part2-*, part2-look-first-*, part2-look-full-*, *-plant-* (pending), classes-* and build-* (frozen classes). Consumers: P4 SiteAdapt risk. Wall: under 1 h for all of row 10 (ESTIMATED). Its .ser inputs alone are 996,852,349 B (MEASURED sum).
- `site/risk-<sha>.json + site/sim-<sha>.json (pending)` [result, not on disk (ESTIMATED small)] by P4 SiteAdapt risk and P5 SiteAdapt sim (INTEGRATION.md:744-745). Inputs: risk/risk-<sha>.json + report-risk-<sha>.md (pending), sim/part1-0e0920d/{summary-p2.json, summary-p3.json, checks.txt, depth.tsv, ev-by-key.tsv, swings*.tsv}, sim/part2-*, pilot-part2-*, part2-look-first-*, part2-look-full-*, *-plant-* (pending), full-index/full-c06c061/results.ser, git:.worktrees/site-int/SIM_CHECK.md + sim-pilot-part1.tsv, classes-* and build-* (frozen classes). Consumers: P6 SiteBuild. Wall: seconds (ESTIMATED). SiteAdapt sim reads c06c061's results.ser (--published).
- `page-<final sha>/ (pending)` [result, not on disk; about 3.2 MB like page-613255a (ESTIMATED)] by P6: row 10 again at the final tag (SiteExport only if the guard keys changed; pass optimism-7c1d40e.properties instead of rerunning it), then build-site.sh. Inputs: site/ceiling-7c1d40e.json, site/results-7c1d40e.json, site/spreads-7c1d40e.json, site/key-edges-7c1d40e.json, site/ledger-7c1d40e.json, site/literature-7c1d40e.json, site/evidence-7c1d40e.json, site/risk-<sha>.json + site/sim-<sha>.json (pending), git:.worktrees/site-int/site/template.html, page.css, page.js, git:../jbondurant.github.io/index.html, git:src/,sim/,lib/ at each commit, montreal-80.states (+ .progress), full-index/full-c06c061/results.ser, report-ceiling-97bf834.ser, custom-count/fullplay-main-97bf834/results.ser, report-final-26f0724.md (+ .err, report-final.status), full-index/pilot-1417-100.txt (+ .err), ceiling/optimism-7c1d40e.properties + .md. Consumers: copy into the website (Justin's yes). Wall: seconds (ESTIMATED). A SiteExport rerun needs montreal, results.ser, step 7 and step 8 at rest: 2,087,802,713 B plus the results (MEASURED sum), over 2.0e9.

## Map: top-down

## How the page's files depend on each other

**What the page is built from.** SiteBuild reads only the 7 files `runs/site/*-7c1d40e.json` (3,112,143 B), plus the template, CSS and JS and the website's index.html, all in git (SiteData.java:148-161). Every other file in runs/ comes before those 7 files.

**Results to keep at rest: about 107 MB** (106,895,338 B, MEASURED with a scratch walk over the result list; confirm with `java DiskBudget runs --without`):
- the 7 site inputs and the built page;
- report-final, step 7's report, the ceiling json, the optimism, split-margin and peek text files, and both pilots;
- spreads json (8.4 MB), and the key-edges json plus csv (77.6 MB, the largest result);
- the literature report, and its cache (8.4 MB; no program can make it again);
- the search, calibration and full-play-choice reports;
- the simulator's part 1 summaries;
- logs and scripts.

Everything else, about 23.18 GB of 23,286,908,933 B (MEASURED), is an intermediate.

**What happens to each intermediate (last reader in the run order):**
- **No reader still to come, so each can go now:** the replay sink run-b.ser 0.70 GB; KeyEdges a 0.22 and a-prime 0.07; run-c 0.25 and run-d 0.19; both replays' records 1.16; the page look's two unread wide files 0.15; the choice and dry-run folders; the search logs; the calibration parts; batch C 0.87.
- **Twin, 1.34 GB:** nothing pending reads it if `optimism-7c1d40e.properties` is reused. CeilingResults data loads that file without checking its commit (CeilingResults.java:761-766). The twin is still wanted for the planned StateSpotCheck and for publication.
- **Still needed by pending steps:**

| File | GB | Pending reader |
|---|---|---|
| montreal-80.states | 1.34 | item-1 re-export, PageLook export-full, SiteExport rerun |
| c06c061 fit.ckpt + 15 parts + run.id | 0.25 | item-1 re-export |
| c06c061 results.ser | 0.43 | RiskReport, SiteAdapt sim (`--published`), any rerun of row 10 |
| step 7 .ser | 0.20 | RiskReport, PageLook export-full, row 10 reruns |
| step 8 results, fit and parts | 0.27 | PageLook export-full, RiskReport |
| replay wide.ser | 0.25 | RiskReport |
| part 1 bundle + export-first | 0.08 | simulator part 2 |
| trees.bin | 14.88 | PageLook export-full (PageLook.java:179-181 needs a kept store) |

**Conflicts with "results only, nothing off the laptop":**
1. **The pending files don't fit together.** Without trees.bin they add up to about 2.82 GB (sum of MEASURED sizes). The chain is item-1 export, then simulator part 2, then RiskReport. Through it, montreal-80.states plus the four .ser files plus wide.ser are about 2.4 GB on their own. So under a 2.0e9 cap something expensive has to be deleted and made again mid-chain, or the code has to change. The working papers' suggestion is for SiteExport to take the states identity from a manifest.
2. **The main state file can't simply be regenerated.** Its path, size and mtime are an identity (FullIndexRun.java:392-395) that the replay, SiteExport and PageLook check. A new file has a new mtime, so all three refuse it unless the mtime is restored. Its bytes are not proven to rebuild either: d3c0b1f's segment agrees only to 1e-12. It takes 46,953 s on 8 threads (montreal-80.log).
3. **The page cites hashes of files that would be deleted.** These are the three .ser files (8ba1412b, c854767f, 6a456558), plus wide.ser and the collectors through the spreads and key-edges JSON files.
   - c06c061's results.ser is bit-exact only on the same classes and 6 threads: 19.8 h (full-c06c061.log).
   - Steps 7 and 8 are the registration's one look. A rerun appends a ledger line, and nobody has tested whether it gives the same hash. This is a protocol decision for Justin, not only a compute cost.
4. **PageLook's F50/F52 export needs trees.bin.** Under a 2 GB cap it is blocked until PageLook gets a `--trees none` option. That costs about 0.5 h a round; the disk-cap check's 2 items took 2.55 h (its log).

**Correction to the brief.** Step 7 was **ComparisonReport --save**, not CeilingReport. CeilingReport.readSaved is the class that reads its output (COUNT_DESIGN.md section 8 step 7).

**Reads that cost more than they need to:**
- KeyEdgesReport reads all of results.ser only for its perfect array (KeyEdgesReport.java:411).
- SiteExport reads only the header of the 1.34 GB state file, plus its identity string (SiteExport.java:575-580).

**Gaps** (listed in `gaps`):
- No committed command for the 1,417-shoe pilot's classes or for the peek.
- The final runs/site/key-edges write is in no script.
- The twin's build is unknown.
- The literature cache was fetched by hand.
- The program that copied the registration is not named.
- Step 7 has no recorded wall time.

Working files, all read-only probes: `/private/tmp/claude-501/-Users-justinbondurant-Documents-docsClaude/078aba77-3392-4bd9-bf33-3d32e8794c9a/scratchpad/depgraph`. Nothing in the repo, the worktrees or runs/ was changed.

**Gaps.** full-index/pilot-1417-100.txt: no commit or classes recorded; it ran from a worktree build ('../../runs/montreal-80.states'). Only INTEGRATION.md:506 describes its command.; full-index/peek-c06c061-final.txt: FullIndexPeek's command and stdout redirect are in no committed script (only the usage line, FullIndexPeek.java:22).; runs/site/key-edges-7c1d40e.json: the copy on disk (05:20:45) was written by a SiteAdapt edges call that is in no script. The scripted step read the 0a447de JSON (glob plus files.get(0)), and its output was moved to site-old-0a447de/ by hand.; twin-80.states: the build that wrote it is recorded nowhere (PLAN_PAGE.md:710-711).; montreal-80.states shoes 0-6649: the build is inferred to be d3c0b1f, not confirmed (PLAN_PAGE.md:704-706). No file's SHA-256 is committed (ed9e9fb4 measured on 18 Oct only).; literature/cache/: fetched by hand on 12 Oct; no committed fetch tool.; custom-counts.json and custom-counts-fullplay.json: 'copied by a program', and the program is not named (COUNT_DESIGN.md section 8 step 6).; custom-count/search: no committed command made the starts-*.txt files (classes 11f99fd, c177ba8, ac7ab4d) or the start vectors typed into lane*.jobs.; report-ceiling-97bf834.{ser,md}: no recorded wall time (the .log is empty); estimated from mtimes.; report-final-26f0724.md: 18,623.7 s of scoring against 13.6 h between start and finish lines; the difference is unexplained.; Rows 5 and 6 (StrategyExport part 1, sim pilot part 1, the c06c061 replay) were typed from INTEGRATION.md with no site-runs script. Their logs and run.id record the settings.; page-613255a/: the build command is in no script, its classes were compiled in mktemp, and the log records no time.; sim/classes-0e0920d has no commit.txt (the sim commit fe9a91e is given only as --sim-commit).; Pending outputs (part 2 bundles, sim part 2, risk, site/sim and site/risk) have no measured sizes yet.

## Map: bottom-up

## How the page's files depend on each other

All of this was read-only. My scratch directory was `/private/tmp/ccec-depgraph-75226`. I read code at `.worktrees/disk-cap` HEAD **e6d6b90**, which is b0ec21b plus one line in DISK_CLEANUP.md. I traced 33 executed steps from the committed scripts and logs (S01-S32) and 6 pending ones (P1-P6).

**The main chain.**
- The two seeds are git and the two StateRuns: `montreal-80.states` (13.0 h, three builds) and `twin-80.states` (3.2 h, build unknown).
- The main file feeds report-final (13.6 h), the c06c061 run (19.8 h) and steps 7 and 8 (about 4.0 h and 5.3 h).
- After that come the replay (14.90 h of batches) and PageLook (3.7 h for runs C and D).
- The row-10 adapters take 6 min and write the 7 `runs/site/*.json` files. SiteBuild reads only those 7, plus git (3.8 s).
- Adding up the walls on the page's critical path gives about **84 h of machine time** (ESTIMATED sum of MEASURED walls). That leaves out the search, which ran as a 25.6 h queue.

**Sizes (MEASURED with stat).**
- runs/ holds **23,286,908,933 B in 7,450 files**.
- The result set holds **108,699,882 B in 261 files**: the site JSONs, the page, the cited reports and text, ceiling, spreads, the key-edges JSON and CSV (77.6 MB of it), literature, the sim part-1 tables, logs and scripts.
- Everything else is an intermediate.
- The literature cache is an input that is not in git and cannot be fetched again byte for byte, and the page cites 41 of its hashes. I classed it as a result.

**When each intermediate stops being needed.**
- **Nothing later reads these now:**
  - replay records, 1,155,367,153 B;
  - the five collectors, 1,428,237,518 B (only a rerun of the KeyEdges report reads them);
  - the page look's two unread wide files, 145,123,610 B;
  - the choice and dry runs, the search logs, gate 0, the superseded row-10 outputs and pages;
  - 171 of the 186 c06c061 parts.
- **Still needed by pending steps:**

| File | Kept for |
|---|---|
| trees.bin | P2: PageLook needs it (PageLook.java:179-181) |
| fit.ckpt and 15 parts | P1, the item-1 re-export |
| step 8's fit.ckpt and parts | P2 |
| export-first and the part-1 bundle | P3, simulator part 2 |
| replay wide.ser | P4, RiskReport |

- **Needed every time the page inputs are rebuilt:**
  - montreal: SiteExport reads only its 271-byte header and identity (SiteExport.java:575-580);
  - the twin: optimism reruns on every row-10 pass, though its output changes only in the commit line;
  - the three cited .ser files.

**Where your rule and the 2.0e9-byte cap collide (sizes MEASURED, sums ESTIMATED).**
1. The files needed today for pending work, leaving out trees, come to about **4.17 GB**. Of that, 2.68 GB is the two state files. PageLook also needs trees.bin, 14.88 GB.
2. Some single steps go over even when they hold only their own files: FullIndexRun 2.39 GB, the 93-item replay about 3.9 GB, PageLook at least 17.6 GB, SiteExport about 2.1 GB.
3. The overlaps break the cap structurally. KeyEdgesRun report needs five collectors made by three steps (S18, S25, S28), and montreal must be kept between those steps. montreal plus the main run-b.ser alone is **2,045,319,578 B**.
4. **One-way doors.** Several files cannot be rebuilt to the same bytes:
   - montreal (three builds);
   - the twin (build unknown);
   - c06c061's results.ser (agrees only to 1e-12);
   - steps 7 and 8 and PageLook, because each rerun is a new ledger look.

   Deleting them as intermediates means a guard-key change in the template (which reruns SiteExport), a RiskReport rerun, or checking a cited hash (8ba1412b, c854767f, 6a456558, a2f8a751, the key-edges chain) would need tens of hours of recompute and new ledger lines. A recomputed montreal would also get a new mtime, which is in the identity string embedded in results-7c1d40e.json.

**Checked.**
- The 7 site-input hashes equal manifest.json.
- export-part1 4a424be6 equals SIM_CHECK.md:26.
- The split-margin and optimism hashes equal the ceiling JSON's inputs.
- The cited code lines match: SiteExport.java:369, 377, 575-580; KeyEdgesReport.java:403-411; CeilingResults.java:1384-1400; FullIndexRun.java:218-220, 392-395; FullIndexReplay.java:546-556, 628, 762.

**Gaps.** Commands are missing or unscripted for both StateRuns, the peeks, the sim export and pilot, the 05:20 SiteAdapt edges rewrite, the page builds and the literature fetch. Step 7's wall time is estimated from file times. Gate 0's log says 0.70 h, but its queue entry spans 17.5 h. The full list is in `gaps`.

**Gaps.** twin-80.states: the StateRun build/classes is recorded nowhere (PLAN_PAGE.md:710-711); the command is in no script; montreal-80.states: build of segment 1 (shoes 0-6649) not named in montreal-80.log, 'probably d3c0b1f' (PLAN_PAGE.md:703-705); StateRun commands not scripted; report-quick-c3af4cf.md, report-all-200-c3af4cf.md: commands not recorded; full-index/pilot-1417-100.txt: run from .worktrees/full-index with code at cbdd02b per FULL_INDEX_PLAYS.md:370; no frozen classes path or script; peek-c06c061-final.txt, peek-c06c061-2026-10-06-2139.txt, fullplay-main-97bf834.peek.txt, peek-choice-20000-38f4e60.txt: FullIndexPeek commands and classes not recorded; report-ceiling-97bf834.{ser,md}: no start/end log lines (.log is empty); wall time ESTIMATED from mtimes; replay/gate0-304901c: log says 0.70 h on the wall, queue-304901c.log spans 17.5 h; difference unexplained; sim/export-part1-0e0920d and sim/pilot-part1-0e0920d: no script and no timing; command only in INTEGRATION.md:542-551, 564-566; wide/spreads-0e0920d.json, report-wide-0e0920d.md: WideReport command in no script; page-look-0e0920d/attempt1-0e0920d/: moved aside by hand, no record; site/key-edges-7c1d40e.json: rewritten at 05:20 by a hand-run SiteAdapt edges; the scripted run read the 0a447de JSON; page-613255a, page-79ff68b, page-7c1d40e-b: build-site.sh invocations not scripted; page-7c1d40e-b's commit not established; literature/cache/: fetched by curl with no committed command; literature-c7620b1's classes not recorded; custom-count/bounds-d6a005c.txt: producer not traced; custom-count/search/starts-*.txt (classes 11f99fd, ac7ab4d, c177ba8): commands not read; pending outputs (export-part2-replay bundle, export-full, sim part 2, risk, sim JSON): not on disk, sizes unknown
