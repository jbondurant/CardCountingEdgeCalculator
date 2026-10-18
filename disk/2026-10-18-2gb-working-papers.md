# The 2 GB question: the workflow's working papers (18 October 2026)

Five investigators (one per kind of file), three designers and one adversarial judge, run read-only on
18 October 2026 while the disk-cap check ran. Their numbers are marked MEASURED (with the command) or
ESTIMATED; MEASURED here means measured by the agent with standard tools (stat, gzip, xz, zstd, rclone),
not by repo code. DISK_2GB.md is the summary. Kept as evidence, not instructions.

## The judge

**Answer.** Yes, with conditions, if "2 GB" means at most 2.0e9 bytes in runs/, which is what DiskBudget counts. Every run still pending can fit, and every published figure and cited SHA-256 stays the same, because files are archived byte for byte and restored to the same absolute path and millisecond mtime. Five conditions: (1) The twin state file and every finished intermediate live on Drive, checked by SHA-256 and MD5 (Drive has 3.65 TiB free; checked today). (2) A second off-laptop copy exists before the last local copy of anything that cannot be rebuilt to its hash is removed. (3) Justin approves each removal and makes his own rclone client_id; rclone's notice says the shared one stops working during 2026. (4) Runs go without the tree store. That costs hours per round, so the item-1 fix should run before trees.bin is deleted. (5) Two small code changes: a committed fetch/archive tool, and SiteExport taking the state file's header and identity from the manifest. Without the second, a results re-export needs 2.108 GB, which is 0.108 GB over 2.0e9 but under 2 GiB. Four things still do not fit as written, and none is pending. A fresh full-catalog run needs about 2.39 GB, 0.39 GB over; it fits only after the state file is re-encoded (about 0.61 GB, estimated) or streamed. KeyEdgesRun a is 11 MB over. A rerun of the whole 93-item replay is about 3.9 GB, about 1.9 GB over. PageLook needs the 14.88 GB tree store. The page look's F50/F52 export, which is pending, goes through PageLook, so it must run before trees.bin goes, or PageLook needs a --trees none option first. The cap does not cover macOS swap on the same SSD (1.56 GB in use now, 2.18 GB earlier), .worktrees (485 MB), or files in the Trash before Justin empties it.

## Recommended: archive-first (T0), three narrow code changes, the twin kept on Drive, the optimism output reused

**Base plan.** Archive verbatim and fetch to the exact path; no change to StateStore. This takes T0 from the archive plan, the optimism cache from the recompute plan, and the letGo fix from the replay area. The state-file re-encoding (v3) and streaming are left as optional, needed only for a fresh full-catalog run.

### Order
1. **Wait for pid 48286 to exit.** It reads the states, fit.ckpt and the parts. Its first item already came back with no tree store, identical to the bit: 416,114 of 416,114 numbers (runs/disk-cap-check/replay-trees-none-03b116f.log).
2. **Finish item 1 while trees.bin still exists**, under today's budget, raised if needed:
   - c06c061's 15 rows;
   - the page look's F50/F52 export, refused under open item 1 (INTEGRATION.md:377, 614-617). Only PageLook makes it, and PageLook requires --trees-read-only (PageLook.java:179-181, 241-245). Simulator part 2 needs both bundles (INTEGRATION.md:629-646).
3. **Commit the manifest** with a committed tool: path, bytes, SHA-256, MD5, mtime in ns, remote path. This records for the first time ed9e9fb4… for montreal-80.states and trees.bin's hash, as PLAN_PAGE.md:697 requires.
4. **Push** with `rclone copy --immutable` to gdrive:backups/data/ccec-runs/, then check `rclone hashsum sha256` and `rclone md5sum` against the manifest.
5. **Second copy:** a Release (fix the duplicate `results.ser` names in SHA256SUMS first, SiteManifest.java:115, 121) or an external disk.
6. **Removals,** with Justin's yes batch by batch: batches A-C to the Trash, then the archived files.
7. **Set `CCEC_DISK_BUDGET_GB=2`.**

### Code changes, in order
1. **RunArchive + test** (about 1 day): manifest, push, fetch, evict and need.
   - `fetch` calls DiskBudget.require first.
   - It writes to the exact absolute path, sets the ns mtime from the manifest, and brings a state file's `.progress` with it.
   - It refuses on a mismatch and accepts a file downloaded by hand.
2. **Let records go when an export is refused.** A hook's finish throws inside FullIndexReplay.run (:584-586), so FullIndexRun.java:218-220 and PageLook.java:532-535 never reach letGo. That is how 1.16 GB was left behind.
3. **Attach the registered wide plans with `--only` + `--export-row`** (FullIndexReplay.java:418-439). Item 1 is then 15 items, not 93.
4. **Budget constants:**
   - KeyEdgesRun.BYTES per mode (:53);
   - ITEM and REPLAY_ITEM re-measured;
   - correct DiskBudget's comment: building the page reads 3.1 MB, not 6.38 GB.
5. **SiteExport** takes the header and the identity string from the manifest when the state file is absent. It reads only these (SiteExport.java:575-580). Values stay bit-identical, and the `src` string is copied verbatim.
6. **Optimism is reused, not rerun on each page build.** The three builds' properties files differ only in line 1, the commit (diff, MEASURED), and CeilingResults reads the file without a commit check (:761-764). So the twin is fetched only for StateSpotCheck or publication.
7. **Optional: v3 main file**, chosen over streaming because it works offline:
   - legal slots only, byte planes, Deflater;
   - the v2 identity and SHA-256 stored in its header;
   - a test that decodes the whole file to ed9e9fb4….

### Peaks (runs/ only, 2.0e9 B)
| Run | Peak |
|---|---|
| Page rebuild | 0.02 |
| SiteExport | 0.77 (2.108 without change 5) |
| CeilingResults data | 0.77 |
| WideReport | 0.28 |
| RiskReport | ~1.1-1.2 + part-2 outputs |
| Optimism (if rerun) | 1.993 |
| Spot check | 1.79 |
| Custom 5-count run | 1.62 |
| StateRun | 1.36 |
| Item 1 after the cap | ~1.80 (1.89 with --wide); DiskBudget passes by ~10 MB |
| KeyEdgesRun report | 1.955 |
| **Over:** KeyEdgesRun a | 2.011 (+11 MB) |
| **Over:** fresh full catalog | 2.39 (+0.39); about 1.66 + trees with v3 |
| **Over:** 93-item replay | ~3.9 |
| **Over:** PageLook | ≥17.6 |

At rest, runs/ holds 0.02-0.15 GB, depending on how many small files stay local.

### Bit-identity and hashes
- **Every figure is bit-identical**, and every cited hash is unchanged: 8ba1412b, c854767f, 6a456558, 824f0769, b86622c4, ee3ff121, the collector hashes, 479595d1 and a2f8a751. The bytes, path and ms mtime all come back the same (FullIndexRun.java:392-395).
- **What changes:**
  - files a rerun writes at a new commit, as today;
  - with v3, the local file's raw SHA, so ed9e9fb4 must then be checked through the decoder;
  - the SHA256SUMS naming fix, which changes only SHA256SUMS and MANIFEST.md of a new build.
- **Not recommended:** slim collectors, and perfect taken from fit.ckpt. Both change the key-edges JSON's hash.

### Time
- **One-time:** hashing about 4 min; upload at least 44 min for 6.4 GB at 2.42 MB/s.
- **Each run:** fetches of 3-20 min.
- **No tree store:** 48 of a full run's 49 rounds read trees (full-c06c061.log). At TreeStore's 0.17 CPU-s per tree that is about +0.5 h a round. Under today's load it is up to about +1.7 h: one last round took 8,121 s with no trees, against 2,085 s with trees.

### Justin decides
1. What the 2 GB covers: runs/ only, the project folder too (.worktrees 485 MB), and whether swap counts; 2.0e9 bytes or 2 GiB.
2. The Drive home, the upload, and his own rclone client_id.
3. The second copy: a Release, an external disk, or both.
4. Each removal batch. Trashed files free space only when he empties the Trash.
5. Item 1 before trees.bin goes (recommended), or after, with a PageLook --trees none option and a slower run.
6. Reusing optimism instead of rerunning it.
7. Whether a fresh full-catalog run must fit. If it must, add v3.

### Refutations

- **shrink-first**: No tree store costs about +3.5 h on a 19.8 h full run (7 rounds)
  - Problem: A full run has 49 rounds, and 48 of them (rounds 1-48) read trees, not 7. At TreeStore's 0.17 CPU-s per tree on 6 threads that is about +23 h, and more under load. The cost is understated about 7 times.
  - Evidence: runs/full-index/full-c06c061.log has 49 'round N done in' lines (rounds 0-48; round 0 built every tree, 9,096.5 s). TreeStore.java:17-20 gives 0.17 s a tree. The live check runs/disk-cap-check/replay-trees-none-03b116f.log: one last round took 8,120.8 s with no trees, against 2,084.6 s with trees for the main replay's first batch (runs/replay/c06c061-0e0920d.log line 19).
- **shrink-first**: Every pending run fits (page build, SiteExport, item-1 re-export, RiskReport, spot check...)
  - Problem: It leaves out the page look's F50/F52 full-play export. That export was refused under open item 1 and simulator part 2 needs it. Only PageLook can make it, and PageLook requires --trees-read-only on trees.bin (14.88 GB), which the plan deletes in batch A.
  - Evidence: INTEGRATION.md:377 ('The page look's F50 and F52 (row 7's export-full) meet the same refusal'), :614-617, :629-646 (part 2 runs on the page look's two bundles). PageLook.java:179-181 and 241-245 refuse to run without a tree store. FullIndexReplay.java:522-526 refuses a plain replay of registered counts on the main file.
- **shrink-first**: APFS-pack read-only files in place: ditto to a temp file, then rename over the original; no reader changes
  - Problem: Renaming a copy over an existing file of Justin's is the move-onto-an-existing-name pattern his safe_move rule exists to prevent. It needs his explicit approval, even with cmp checks first. The saving is also fragile: any rewrite or rclone restore undoes it, and DiskBudget counts Files.size, so it needs a block-counting rewrite before the budget sees any of it. Under an archive the large .ser files are not local anyway, so it saves little.
  - Evidence: AGENTS.md standing rule 'Move with safe_move.py, not mv'. DiskBudget.java:86-91 (Files.size). The dossier: Java's unix view has no block count (MEASURED).
- **shrink-first**: montreal v3 = 0.603 GB (1,340,719,767 x 0.4495)
  - Problem: The 0.4495 ratio was measured with 1,024-record blocks on the first 200 MB, which is segment 1 and part of segment 2 from two of the three builds. The plan specifies 64-record blocks, measured at 90,671,880/200,000,000 = 0.4534, about 0.608 GB. The whole-file ratio is unmeasured. Margins of 0.11-0.16 GB rest on it.
  - Evidence: Dossier, state-file area: '64-record blocks: gzip -6 90,671,880'. The sample covers shoes 0-8939 only.
- **recompute-instead-of-store**: PageLook is once per ledger and not pending
  - Problem: The page look's export-full (F50/F52) was refused and is still owed to simulator part 2. Redoing it goes through PageLook, which requires trees.bin, the store this plan deletes. So one pending run is not runnable under the plan.
  - Evidence: INTEGRATION.md:377, 614-617, 629-646; PageLook.java:179-181, 241-245, 297-309 (resume reruns the replay; export-full refusal caught at :302-309).
- **recompute-instead-of-store**: The a and a-prime collectors never need storing: they rebuild to the bit (254 s, 612 s)
  - Problem: Rebuilding gives the same figures, but the cited bytes (56148393…, c649f44f… in key-edges-7c1d40e json) come back only with frozen classes-304901c reading the v2 file at the same path and mtime. The plan's own v3 format locks frozen classes out. Decoding v2 beside v3, results.ser and the output comes to about 0.151+1.341+0.61+0.431+0.221 = 2.75 GB, over the cap. The determinism evidence (run-c and wide-first differing in bytes 29-67) is across classes, so same-class reproduction is untested. The plan's own Drive list archives a and a-prime anyway, which contradicts the claim.
  - Evidence: StateStore.java:32, 67-69 (VERSION 2 only). KeyEdgesRun.java:199-204 (the identity embeds the commit and stateFile). The plan's steady-state Drive list includes 'a, a-prime'.
- **recompute-instead-of-store**: KeyEdgesRun report fits at 1.84 GB by taking perfect from fit.ckpt
  - Problem: The figures stay bit-identical, but the report hashes the --published file into the JSON's inputs block. Swapping results.ser for fit.ckpt changes key-edges JSON's SHA 824f0769… and so the page's files table. It is also not pending.
  - Evidence: KeyEdgesReport.java:411 (perfect from results.ser), :430-431 (published path and sha256 in inputs).
- **recompute-instead-of-store**: Fresh full-catalog run peaks at 1.81 GB
  - Problem: treesToKeep fills whatever the budget leaves after ITEM x items, so the real peak sits at about the budget by design. With ITEM cut to 6 MB against a measured 5.65 MB, only a 6% estimate margin protects the 2 GB cap.
  - Evidence: FullIndexRun.java:279-282, 360-379; DiskBudget.java:42-45.
- **archive-first**: Under T1 every check stays as strong as now, and the SHA-gated stream is stronger
  - Problem: For header-only readers (SiteExport.java:575-580, PageLook.java:212-213), and for stateFile when the path is absent, T1 returns the identity string copied from the manifest and reads no bytes. 'The run was scored on this file' then compares the manifest against an identity taken from the same source, which is weaker, not stronger. The stream's SHA check covers only the runs that load values.
  - Evidence: The plan's own code change: 'FullIndexRun.java:392-395 stateFile: when the path is absent locally, return the manifest's identity string'. SiteExport never loads values.
- **archive-first**: A full run has about 42 walks that read trees, about +20 h; and under T1 'every run fits, with at least 0.3 GB of margin'
  - Problem: 48 rounds read trees (log), about +23 h. The plan's own table keeps KeyEdgesRun report at 1.955 GB under T1, a 45 MB margin.
  - Evidence: full-c06c061.log rounds 1-48; the plan's peaks table row 'KeyEdges report 1.955 / 1.955'.

### Scores (1-10, 10 best)

- **archive-first**: correctness 8, cost 6, risk 6. Meets 2 GB: Yes for every pending run under T1. T0 misses SiteExport by 0.108 GB, which a one-class header shim fixes. Also over: a fresh full run 0.40 (T0), the 93-item replay 1.9 (T0) / 0.57 (T1), PageLook. It is the only plan that flagged the F50/F52 PageLook dependency. Sums re-added from stat and correct; verbatim archiving keeps every hash. Weak spots: T1 touches about 20 call sites and makes every state-file run download 1.34 GB through an rclone client_id that is being retired this year; the trees-walk count is low (42 vs 48); T1's identity check gets weaker, not stronger.
- **shrink-first**: correctness 5, cost 4, risk 5. Meets 2 GB: Not on its own (about 3.17 GB at rest). With the twin and collectors on Drive, most pending runs fit, but it misses the F50/F52 export (needs trees.bin), optimism before the pending runs (2.26), and the one-time migration (about 3.0 GB). The no-tree time cost is understated about 7x; it misses PageLook; APFS rename-over-original needs Justin's yes and DiskBudget block counting; v3 locks out frozen classes. The useful parts: one copy of the results via slicing (measured 186/186), gzip checkpoints detected by magic bytes, and v3 as a local, offline option.
- **recompute-instead-of-store**: correctness 6, cost 5, risk 5. Meets 2 GB: Yes for pending runs except F50/F52, which it wrongly calls not pending. Over: the 93-item replay 1.31 GB, KeyEdgesRun report as written (2.09), PageLook. Correct 48-round time cost and a sound optimism cache, verified: the three builds' files differ only in line 1 and CeilingResults.java:761-764 reads the file without a commit check. Overclaims that a and a-prime rebuild to the bit, contradicts itself by archiving them, and depends on v3, Drive, and the slowdown from having no tree store.

### Estimated, not yet measured by repo code

- v3 main state file about 0.61 GB: whole-file ratio not measured (0.4495 at 1,024-record blocks and 0.4534 at 64 on the first 200 MB only)
- Item-1 re-export peak about 1.80 GB (1.89 with --wide): its records (about 0.12 GB), bundle (about 0.06 GB) and wide plans (about 0.09 GB) are estimated from other runs
- RiskReport about 1.1-1.2 GB and simulator part 2's outputs: part 2's per-shoe records are unmeasured
- No-tree slowdown of +0.5 to +1.7 h per round, +2 to +13 h for item 1 after the cap, about +23 h or more for a full run: one measured round under load average about 30 plus TreeStore's documented 0.17 CPU-s per tree
- That --trees none reproduces every part to the bit: 1 of 2 items measured identical so far, the second still scoring
- Upload of at least 44 min for about 6.4 GB and fetches of 3-20 min per run: from one networkQuality reading and a few rclone cat streams
- That a Drive round trip preserves the state files' millisecond mtime and that rclone writes .partial files; the fetch tool must set the ns mtime from the manifest
- That Drive's server-side SHA-256 is filled in for every uploaded file (Google documents it as 'if available'); MD5 is the fallback
- Hashing about 4 min in total for the manifest, from 747 MB in 8.06 s
- SiteExport 0.77 GB, CeilingResults data 0.77 GB, optimism 1.993 GB, KeyEdgesRun report 1.955 GB: sums of measured sizes; that these sums are the true peaks is an estimate
- ITEM about 6 MB being enough for future catalogs (measured 5.65 MB/item on c06c061 only)
- That no published figure reads Entry.shoeSums (grep, not a test); relevant only if shoeSums were ever dropped, which is not recommended
- Swap usage on the same SSD (1.56 GB now, 2.18 GB earlier): no runs/ budget bounds it

## Plan: Recompute instead of store. Nothing that rebuilds to the bit gets stored anywhere: the tree store, replay records, the second copy of results.ser or its parts, the always-NaN slots of the state files, the a and a-prime collectors, the key-edges JSON/CSV, the page itself, the classes folders, and superseded runs. Steps whose output depends only on frozen inputs get their small output cached and are not rerun at each page build. Recompute alone does not reach 2 GB, so two ideas are borrowed and labelled as such: (1) a byte-exact Drive archive, from the archive angle, for the material that cannot be recomputed; (2) a lossless "legal values only" state-file format, from the state-file angle, which here means rebuilding the NaN mask from the rules instead of storing it.

**Verdict.** Yes, with conditions. Every pending or routine run fits under 2 GB local if four things hold. (1) Justin approves a byte-exact Drive archive, about 6.17 GB uploaded once. It is required: what cannot be recomputed comes to about 2.2 GB even compressed with xz (ESTIMATED from MEASURED ratios), before any run starts. (2) The state files get the lossless v3 format, about 0.61 GB each instead of 1.34 GB. (3) No tree store, which costs time. (4) Justin says yes to the deletions. Under those conditions the largest pending peaks are: KeyEdgesRun report 2.09 GB as written, 1.84 GB with one small change (take `perfect` from fit.ckpt); SiteExport 1.51 GB; item-1 re-export 1.20 GB; a fresh full-catalog run 1.81 GB (only if DiskBudget.ITEM is re-estimated). Two runs do not fit as written, and neither is pending. The full 93-item replay peaks at 3.31 GB, over by 1.31 GB; it gets to about 1.94 GB only with three borrowed code changes, one of which changes run-b.ser's hash. PageLook needs the 14.88 GB tree store. All of this counts runs/ only. macOS swap (2.14 GB MEASURED) and .worktrees (0.497 GB) sit on the same SSD outside the budget.

**Steady state.** 0.151 GB local at rest (MEASURED: du -skc of the always-local set, 147,872 KiB with the classes folders, 0.112 GB without). That is the page inputs, the latest page, the cited small text, the spreads JSON, the literature cache, the simulator bundles, logs and frozen builds. Keeping the main v3 state file local raises it to about 0.76 GB (ESTIMATED). Drive holds about 6.17 GB byte-exact (sum of MEASURED sizes): both v2 state files, results.ser, fit.ckpt, the parts, report-ceiling, the step-8 folder, wide.ser, both run-b files, run-c, a, a-prime, key-edges JSON/CSV, the two unread wide files, export-first and choice-20000.

**Peaks.**

- At rest (steady state): 0.151 GB (MEASURED); 0.76 GB if the main v3 state file stays local = MEASURED: `du -skc` of runs/site, page-613255a, ceiling, the 7c1d40e spreads and report-wide, literature, sim, page-look export-first and look.txt, the cited report/pilot/peek text, the logs, .progress files, run.id, build-*, site-runs = 109,300 KiB. All classes-* dirs add 38,572 KiB. Total 147,872 KiB = 0.151 GB. Optional: montreal v3, about 0.61 GB (ESTIMATED: 1,340,719,767 x the MEASURED shuffled-gzip ratio 0.4495). Everything else lives on Drive.
- SiteBuild page rebuild: 0.154 GB = 0.151 at rest + 0.003 output (3,179,185 B, MEASURED by the page area). No archive fetch. Rebuilt byte for byte in 3.8 s with runs/ unreadable (MEASURED by the page area).
- CeilingResults data: 0.90 GB (ESTIMATED sum of MEASURED sizes) = 0.151 + results.ser 0.4307 + report-ceiling-97bf834.ser 0.2030 + fullplay-main results.ser 0.1134 + about 0.001 of output.
- SiteExport re-export (when the template's guard keys change): 1.51 GB with v3 state; 0.90 GB with a header stub; 2.24 GB with v2 (over) = 0.151 + the three .ser 0.747 + montreal v3 0.61 + output 0.002. SiteExport reads only the header and the identity string, and deals the shoes again from the header's seed (SiteExport.java:575-593). With v2 it is 0.151 + 0.747 + 1.341 = 2.24 GB, over by 0.24.
- WideReport: 0.41 GB = 0.151 + wide.ser 0.2498 + output about 0.0105 (spreads JSON 8.4 MB + report md 2.1 MB, MEASURED ls).
- KeyEdgesRun report: 2.09 GB as written (over by 0.09); 1.96 GB with everything else parked; 1.84 GB taking `perfect` from fit.ckpt = 0.151 + a 0.2214 + a-prime 0.0656 + run-c 0.2457 + run-d run-b 0.1911 + sink run-b 0.7046 + results.ser 0.4307 + output JSON+CSV 0.0776. The report uses results.ser only for `.perfect` (KeyEdgesReport.java:411). fit.ckpt's perfect equals it to the bit (MEASURED by the full-index area). Swapping results.ser for fit.ckpt (0.1837) saves 0.247 GB. Not routinely rerun if its output is cached.
- KeyEdgesRun a / a-prime (recomputed instead of stored): 1.41 GB / 1.48 GB = a: 0.151 + v3 states 0.61 + results.ser 0.4307 + output 0.2214. a-prime: the same plus its output 0.0656, with a kept for the report. 254 s and 612 s (MEASURED by the replay area).
- CeilingResults optimism (only if its code changes): 1.39 GB with twin v3; 2.13 GB with twin v2 (over) = 0.151 + twin v3 0.61 (ESTIMATED from 0.4494) + results.ser 0.4307 + report-ceiling 0.2030, main state file not local. Otherwise reuse optimism-7c1d40e.properties: three builds' copies differ only in line 1, the commit (MEASURED: diff).
- RiskReport / SiteAdapt sim (pending, after simulator part 2): about 1.15 GB plus part-2 outputs / about 0.6 GB = Risk: 0.151 + results.ser 0.4307 + report-ceiling 0.2030 + step-8 results.ser 0.1134 + wide.ser 0.2498, plus part 2's tsvs (size unknown; ESTIMATED small). Sim adapter: 0.151 + results.ser 0.4307 + sim runs (part 1 is 2.3 MB, MEASURED du).
- Item-1 re-export (15 items, export hook, --trees none): 1.20 GB (+0.09 with --wide) = 0.151 + v3 0.61 + fit.ckpt 0.1837 + 15 parts 0.0722 (fetched from Drive, or sliced from results.ser) + records about 0.12 + bundle about 0.06 (records and bundle ESTIMATED by the replay area).
- Fresh full-catalog FullIndexRun (186 items; not pending): 1.81 GB at the end; 1.13 GB during the passes = End: 0.151 + v3 0.61 + fit.ckpt 0.1837 + 186 parts 0.4310 + results.ser 0.4307. Passes: 0.151 + 0.61 + fit.ckpt + fit.ckpt.tmp about 0.37. Trees: treesToKeep (FullIndexRun.java:372-379) fills the remaining 0.19 GB with about 770 states. DiskBudget refuses the run unless ITEM is cut from 10 MB to about 6 MB (5.6 MB measured, DiskBudget.java:42-45, 56).
- Custom 5-count FullIndexRun: 1.03 GB = 0.151 + v3 0.61 + fit.ckpt 0.0402 + parts 0.1131 + results.ser 0.1134.
- Full 93-item replay, three hooks (not pending): 3.31 GB as written (over by 1.31); about 1.94 GB with the borrowed changes = 0.151 + v3 0.61 + fit 0.1837 + 93 parts 0.4226 + records 0.9289 + wide.ser 0.2498 + run-b 0.7046 + bundle 0.06. With parts fetched per item (0.007), records deflated (x0.577 = 0.536) and a slim sink (run-b minus its 559 MB of Entry copies = 0.146): 1.94. The slim sink changes run-b.ser's SHA.
- PageLook (once per ledger; not pending): not runnable as written; about 2.1 GB with a --trees none option (over by about 0.1) = Needs --trees-read-only, i.e. trees.bin 14.88 GB (PageLook.java:176-181). With a trees-none option: 0.151 + v3 0.61 + step-8 fit/parts/results 0.27 + report-ceiling 0.20 + outputs about 0.84 (run-c, run-d, both wide files, export-first, records), all ESTIMATED from MEASURED sizes.
- StateRun (a new 60,000-shoe state file) / planned StateSpotCheck: 1.49 GB (v2 written; DiskBudget checks 1.65) / 0.76-1.37 GB = StateRun: 0.151 + 1.341, or 0.76 if it writes v3 blocks. DiskBudget reserves 25,000 B x 60,000 = 1.5 GB (StateRun.java:26, 69). Spot check: 0.151 + one or two v3 files at 0.61 each.

**Code changes.**

- src/StateStore.java (read 208-211, 214-223, 230-264, 267-294; layout 32-35): a v3 format, borrowed from the state-file area. It stores only the 1,720 legal doubles per record; the 1,030 rule-NaN slots are rebuilt on read from StateValuer.legal (StateValuer.java:61-79). Values are byte-plane shuffled in blocks of 64 records and compressed with java.util.zip.Deflater, so no new jar. The 72-byte prefix stays: it is 0.33% of the file and is what AllStates.replay (AllStates.java:38-76) checks against shoes dealt again. The v3 header stores the v2 identity (path, bytes, mtime ms) and the v2 SHA-256.
- New src/StateConvert.java (v2 to v3, and v3 to the exact v2 bytes, streamed) and a test that decodes each whole file to SHA-256 ed9e9fb4... and a2f8a751... (both MEASURED by the state-file area).
- src/FullIndexRun.java:392-395 stateFile(): for a v3 file, return the stored v2 identity string, so run.id, the replay's check (FullIndexReplay.java:539-545), SiteExport.java:575-578, PageLook and results-7c1d40e.json stay unchanged.
- src/CeilingResults.java:1400: hash the decoded v2 stream for a v3 twin, so the cited a2f8a751... stays.
- src/DiskBudget.java:53, 56 and src/StateRun.java:26: BUDGET_GB = 2 (or set CCEC_DISK_BUDGET_GB=2). Cut ITEM from 10 MB to about 6 MB (5.6 MB measured, DiskBudget.java:42-45) and SHOE_BYTES to about 11,000 for v3. Correct the comment at :26-32: building the page needs 3.1 MB, not 6.38 GB.
- src/FullIndexRun.java:218-220 and src/PageLook.java:532-535: let the records go even when the export is refused at StrategyExport.java:2771 (borrowed from the replay area).
- src/FullIndexReplay.java:546-551, 762: optionally cut each item's part out of results.ser in the heap (the inverse of SystemScorer.merge, SystemScorer.java:2880-2958), so parts are never stored. MEASURED by the full-index area: 186 of 186 parts match to the bit, apart from Result.seconds, which ResultDiff ignores.
- src/KeyEdgesReport.java:411: an option to take `perfect` from fit.ckpt (equal to the bit, MEASURED by the full-index area). That brings the report from 2.09 to 1.84 GB.
- A new committed fetch tool with archive/manifest.tsv (path, bytes, SHA-256, MD5, mtime ms, remote), borrowed from the archive area. It fetches with rclone to the exact absolute path, checks SHA-256, sets and checks mtime, brings the .progress sibling with a state file, calls DiskBudget.require first, and refuses on a mismatch.
- A step cache (a committed tool or an INTEGRATION.md row-10 rule): CeilingResults optimism, KeyEdgesRun report and WideReport are rerun only when the source of their class closure changed since the cached output's commit. Otherwise the cached output is reused and its inputs stay on Drive.
- Docs: DISK_CLEANUP.md; PLAN_PAGE.md:697 (hash trees.bin before deleting it), :991-992 (a verified Drive copy stands in for local copies); PLAN_COMPUTE.md:71-73, 1136, 1175 (keeping the tree store).

**Figures bit-identical.** Yes, for every published figure. Rebuilt trees are bit-identical: TreeStoreTest per TreeStore.java:22-24 and DiskBudget.java:20-22, with the real-file check still running (runs/disk-cap-check). Decoded v3 records equal the v2 records to the bit: MEASURED round trip on 9,061 sampled records per file, with a whole-file test to add. results.ser, fit.ckpt, the collectors and the registration outputs are never re-encoded, only archived verbatim. Rebuilt parts match results.ser to the bit (MEASURED, 186/186). Cached outputs equal fresh ones apart from the commit field (MEASURED: three optimism files differ only in line 1; spreads JSONs in 6 bytes; ceiling JSONs in 170 bytes, consistent with commit and input-hash fields). Excluded because they are not bit-identical: re-valuing the states (segment 1 agrees only to 1e-12, PLAN_PAGE.md:706-709; the twin's build is unknown) and rescoring from scratch.

**Hashes.** None of the hashes the current page cites change. That covers the 17 SHA-256s on index.html, the 7 runs/site inputs in manifest.json, the chain through the spreads, key-edges and ceiling JSONs down to wide.ser, a, a-prime, run-c, both run-b files and the optimism properties, and the twin's a2f8a751... Three conditions: (a) every cited file is archived byte for byte and restored to the same absolute path, state files with their mtime (SiteInput.java:45-52; FullIndexRun.java:392-395); (b) stateFile reports the v2 identity for v3 files; (c) optimism hashes the decoded v2 bytes. The state files' own SHA-256s (ed9e9fb4..., a2f8a751...) stay checkable through the archived v2 bytes. In future builds, a rerun of the key-edges report (with perfect taken from fit.ckpt), or a and a-prime regenerated under new classes (classes-304901c cannot read v3), changes the key-edges JSON's hash and the page's files table for that build. Figures do not change, and a rerun at a new commit already changes that hash through the commit field. The cache cuts that churn.

**Time.** - **Page rebuild:** 0 extra (3.8 s, MEASURED).
- **Routine page build with the cache:** saves time, because optimism, the key-edges report and WideReport are skipped.
- **A heavy step rerun:** add the fetches. results.ser is 1.5-4.7 min, the report's 1.86 GB of inputs 6.6-20 min, the v3 state file 2.2-6.8 min (ESTIMATED at the 1.53-4.68 MB/s the archive area MEASURED). Decoding v3 on load adds about 5-15 s against 2.2 s for v2 (ESTIMATED).
- **Regenerating a and a-prime:** +14.4 min (254 + 612 s, MEASURED).
- **No tree store (the dominant cost):**
  - DiskBudget.java:30-32 and TreeStore.java:19-20 say about 0.5 h more per round on 6 threads (0.17 CPU-s x 60,743 trees).
  - Today's --trees none check (MEASURED, runs/disk-cap-check/replay-trees-none-03b116f.log) reached 90% of one 2-item last round after 7,370 s, at load averages 9.7/16.6/33.9 at 14:29. The trees-read last rounds in full-c06c061.log took 94-240 s. So it is about 2.3 h per round under this load.
  - Item-1 re-export: about 4 rounds, so +2 h on a quiet machine to +9 h loaded (ESTIMATED).
  - Fresh full-catalog run: about 48 rounds, so +24 h to about +100 h on top of 19.8 h (ESTIMATED).
  - Full replay: 27 batches, so +13 to +62 h (ESTIMATED).
- **One-time work:** upload of about 6.17 GB at 2.42 MB/s, at least 42 min (ESTIMATED). Hashing about 14 s per 1.34 GB (747 MB in 8.06 s, archive area MEASURED). Converting to v3 about 2-5 min per file (ESTIMATED).
- **Not done because too slow and not bit-identical:** re-valuing states, 13.0 h main and 3.2 h twin (MEASURED); refitting, 6.88 h (MEASURED: rounds 0-5 sum to 24,759.6 s in full-c06c061.log); recomputing the resamples, which needs a replay of 14.9 h or more.

**Risks.**

- Rebuilding trees costs far more than the documented half hour per round when the machine is loaded: about 2.3 h for one 2-item round today (MEASURED, in progress). The disk-cap check that proves trees-none bit-identity on the real files has no result yet (DISK_CLEANUP.md:55).
- v3 locks out the frozen classes (classes-c06c061, classes-304901c, build-*), which read only v2 (StateStore.java:32). Any frozen-class rerun needs the v2 bytes back locally (1.34 GB) with the v3 copy parked.
- The stateFile shim must not lie: if the v3 header's v2 identity is wrong, replays, SiteExport and PageLook refuse to run, or the checks get weaker. The header needs the v2 SHA-256 and a whole-file decode test.
- The plan depends on Drive. rclone's shared client_id 'will stop working during 2026' (archive area). Downloads ran at 1.5-4.7 MB/s. Never read through the desktop mount. A restore must keep the path and the mtime in ms (ESTIMATED from Drive's 1 ms precision, untested round trip).
- The cache could reuse a stale output if a dependency changed. The trigger has to be conservative: any change to the source of the step's classes.
- The KeyEdgesRun report fits only with the perfect-from-fit.ckpt change (1.84 GB) or with everything else parked (1.96 GB, 37 MB of margin).
- DiskBudget counts logical sizes in runs/ only. The Trash keeps holding space until Justin empties it. Swap (2.14 GB used, MEASURED vm.swapusage) and .worktrees (0.497 GB) are on the same SSD. The disk is at 99% with 4.06 GB free (MEASURED df).
- The running replay (pid 48286, 2 h 07 min elapsed) reads montreal-80.states, fit.ckpt and the parts. Nothing may be converted, moved or deleted until it exits.

**Decisions for Justin.**

- Use a byte-exact Drive archive (for example gdrive:backups/data/ccec-runs/, about 6.17 GB once) as the stand-in for the local copies? No honest plan reaches 2 GB with everything kept on the laptop. This also changes PLAN_PAGE.md:991-992. It also needs his own rclone client_id.
- Approve deleting batches A, B and C (16.9 GB, DISK_CLEANUP.md), and the local copies of archived files once Drive's SHA-256 and MD5 match. He empties the Trash himself.
- Is 2 GB strict for heavy runs too? If yes, accept the trees-none time cost (hours per run, tens of hours for a full run). If no, raise CCEC_DISK_BUDGET_GB for a heavy run.
- Approve the v3 state format (code, converter and a whole-file test). The alternative is state files on Drive only, streamed for each run.
- Cache derived steps (optimism, key-edges report, WideReport) across page builds instead of rerunning every row-10 step at each commit?
- Does the 2 GB count only runs/ (what DiskBudget sees), or also swap, .worktrees and .git?
- Must the not-pending runs (the full 93-item replay, a fresh full-catalog run, PageLook) also fit under 2 GB? If so, approve the borrowed changes (parts fetched per item, compressed records, a slim sink, a PageLook trees-none option). The slim sink changes run-b.ser's hash.

## Recompute instead of store: the plan

### 1. What never needs storing (rebuilt to the bit)
- **trees.bin (14.88 GB).** Rebuilt every round, bit for bit (TreeStore.java:22-24). Record its SHA-256 first, as PLAN_PAGE.md:697 requires, then delete it. Run with `--trees` none or a 2 GB budget (FullIndexRun.java:372-379).
- **Replay records (1.16 GB).** Only a resume reads them. Let them go even when the export is refused (FullIndexRun.java:218-220, PageLook.java:532-535).
- **Parts or results.ser (0.43 GB).** Keep results.ser, which is cited. Each part can be cut back out of it (MEASURED 186/186 to the bit). The fullplay-main parts the same way (30/30).
- **The state files' 1,030 always-NaN slots (37.3%).** Rebuilt from StateValuer.legal (StateValuer.java:61-79).
- **The state files' 72-byte prefix.** It can be dealt again from the seed in about 4 s (AllStates.java:38-76), but it is kept: it is only 0.33% of the file.
- **a and a-prime collectors (254 s and 612 s), and the key-edges JSON/CSV (294 s).**
- **The page (3.8 s), the classes folders (javac from git), and the superseded and dry runs.**

### 2. What cannot be recomputed, and must be stored byte for byte
| Item | Why not |
|---|---|
| State values | 13.0 h and 3.2 h, and not bit-identical (PLAN_PAGE.md:706-711) |
| fit.ckpt | 6.88 h, same classes and threads, hash untested |
| Bootstrap resamples | Each one refits on drawn shoes (SystemScorer.java:2823-2835); the seed rebuilds only the multiplicity table (Bootstrap.java:28-45) |
| results.ser | 1e-12 only, or 19.8 h |
| Step 7, step 8, PageLook C/D, export-first | One look per ledger (PageLook.java:201-203) |
| wide.ser, sink run-b.ser | A replay of at least 14.9 h |
| Literature cache | Third-party pages change |

Even compressed with xz this comes to about 2.2 GB, so it has to live off the laptop. **Borrowed from the archive angle:** a verbatim Drive copy, checked by SHA-256 and MD5, fetched to the exact path and mtime.

### 3. Local layout
- **At rest:** 0.151 GB (MEASURED): runs/site, the latest page, cited small text, spreads JSON, literature, sim bundles, logs, builds. Optionally add the main v3 state file, about 0.61 GB.
- **v3 state format (borrowed from the state-file angle):** legal values only, byte-plane shuffled, Deflater. The header carries the v2 identity and SHA-256. stateFile (FullIndexRun.java:392-395) and optimism's hash (CeilingResults.java:1400) report v2, so identities and cited hashes stay as they are.

### 4. Running a step
1. Check DiskBudget.
2. Fetch only that step's inputs.
3. Run.
4. Park the inputs and archive the outputs.

Cache optimism, the key-edges report and WideReport. Their outputs differ between builds only in the commit field (MEASURED). Rerun them only when their code changes.

**Peaks:**
| Run | Peak |
|---|---|
| Page rebuild | 0.15 GB |
| CeilingResults data | 0.90 GB |
| SiteExport | 1.51 GB |
| KeyEdgesRun report | 1.84 GB with perfect from fit.ckpt (2.09 as written) |
| Item-1 re-export | 1.20 GB |
| Optimism | 1.39 GB |
| Fresh full run | 1.81 GB |
| Full replay | 3.31 GB as written (about 1.94 with borrowed changes) |

### 5. Order
1. Wait for pid 48286 to exit.
2. Commit the SHA-256s of both state files and trees.bin.
3. Upload with `rclone copy --immutable` and verify.
4. Write v3 and its test; convert.
5. Justin's yes on batches A, B and C and on the archived local copies.
6. Set the budget to 2 GB and cut ITEM to about 6 MB.

### 6. Cost
- The tree rebuild is the big one: 0.5 h per round on a quiet machine, about 2.3 h at today's load (MEASURED, in progress).
- Fetches: minutes per step.
- Upload: at least 42 min, once.

## Plan: archive-first: every file stays byte for byte as it is. Anything the run in hand does not need goes to a verified off-laptop archive (Drive through rclone, with a GitHub Release as the second copy for the publishable files), and runs/ holds only the current run's working set. Two tiers: T0 is archiving plus a fetch tool, with no change to how the engine reads anything. T1 adds one I/O change borrowed from the stream idea: state files are read from the archive as a SHA-256-checked stream, and their identity comes from the manifest. T1 changes how the bytes are read, not which bytes are read.

**Verdict.** Yes, with conditions. The cap is counted the way DiskBudget counts it (runs/, 2.0e9 B, DiskBudget.java:67).

**T0, archiving and fetching only.** Every pending run fits except two:
- SiteExport needs 2.108 GB, which is 0.108 GB over. It would fit under 2 GiB, with 39 MB to spare.
- KeyEdgesRun a needs 2.011 GB, which is 11 MB over.

A fresh full-catalog run (2.403 GB, 0.40 GB over) and the whole 93-item replay (about 3.91 GB, about 1.9 GB over) also miss, but neither is planned.

**T1, adding the state-file stream.** Every run fits, with at least 0.3 GB of margin:
- full run 1.062 GB
- SiteExport 0.767 GB
- optimism 0.652 GB
- KeyEdgesRun a 0.670 GB

Two exceptions remain:
- **The whole 93-item replay**, about 2.57 GB, 0.57 GB over. To fit, it needs its source parts fetched per batch plus compressed records (an idea from the other angle), which gives about 1.57 GB. It is not pending.
- **PageLook**, which requires the 14.9 GB tree store (PageLook.java:179-181, 241-243), so no 2 GB plan can run it. It is made once per ledger (PageLook.java:203-205) and is not pending.

**Conditions:**
- Justin says yes to the archive and to each local removal.
- A second off-laptop copy exists before the last local copy of an unreproducible file goes.
- Justin creates his own rclone client_id.
- He accepts the slower runs that come from having no tree store.
- The cap leaves out macOS swap. Stale worktrees are pruned if the cap covers the whole checkout.

**Steady state.** **Inside runs/: 15,770,124 B (MEASURED sum of the steady list).**

| Item | Bytes |
|---|---|
| site/*.json | 3,112,143 |
| page-613255a | 3,179,185 |
| literature/cache (irreplaceable, kept local and on Drive) | 8,384,809 |
| report-final-26f0724.md | 841,034 |
| report-ceiling-97bf834.md | 132,766 |
| ceiling/ceiling-7c1d40e.json | 66,496 |
| full-index/pilot-1417-100.txt | 53,657 |
| the two .progress files | 34 |

Everything else in the post-cleanup 6.38 GB goes to the archive.

**Outside runs/, which DiskBudget does not count:**
- The checkout without runs/ and .worktrees: 27.7 MB (MEASURED, `du -sk -I runs -I .worktrees`).
- .worktrees: 485 MB (MEASURED du; 68 folders, 38 of them registered worktrees).

So the whole program at rest is about 0.53 GB, or about 0.06 GB if the stale worktrees are pruned (Justin's call).

**Peaks.**

- Page rebuild (SiteBuild from runs/site): 0.019 GB, T0 and T1 (MEASURED components) = steady set 15,770,124 + new page dir about 3,179,185 (size of page-613255a). Classes compile into mktemp outside runs/ (site/build-site.sh:39).
- SiteExport (re-export results-*.json): T0 2.108 GB, 0.108 GB over 2.0e9 (39 MB under 2 GiB). T1 0.767 GB. = montreal-80.states 1,340,719,767 + c06c061 results.ser 430,694,877 + report-ceiling-97bf834.ser 202,956,286 + fullplay-main-97bf834/results.ser 113,431,766 + classes-7c1d40e 2,203,204 + steady 15,770,124 (holds report-final, pilot and the current site JSON) + new results JSON about 2,137,930 (ESTIMATED as today's size) = 2,107,913,954. MEASURED inputs: 2.093 GB (`java -cp .worktrees/disk-cap/build/classes DiskBudget runs --without lists/siteexport.txt`). Under T1 the state file is not needed: SiteExport reads only its 271-byte header and identity (SiteExport.java:575-580), which come from the manifest.
- CeilingResults data / full: 0.767 GB, T0 and T1 = MEASURED list 751,290,827 (results.ser, report-ceiling .ser and .md, peek-c06c061-final, fullplay results.ser, pilot, ceiling/ folder, classes) + steady, less what the two share.
- CeilingResults optimism (twin): T0 1.993 GB, only 7.2 MB under. T1 0.652 GB. = twin-80.states 1,341,183,279 + results.ser 430,694,877 + report-ceiling .ser 202,956,286 + classes 2,203,204 + output about 24 KB + steady = 1,992,827,636 (MEASURED list sum). All four inputs are hashed by path at the end (CeilingResults.java:1398-1400), so in T0 all of them must be local at once.
- WideReport: 0.278 GB = wide.ser 249,769,420 + wide pilot + spreads json and md (about 10.5 MB) + classes = 262,594,350 (MEASURED) + steady.
- KeyEdgesRun report: 1.955 GB, T0 and T1, 45 MB under. It reads no state file (KeyEdgesRun.java:127-129). = a 221,351,385 + a-prime 65,554,901 + run-c 245,680,412 + run-d/run-b 191,051,009 + sink run-b 704,599,811 + results.ser 430,694,877 + classes 2.2 MB + output json+csv 77,605,620 = 1,939,582,253 (MEASURED) + steady − report-final overlap = 1,954,511,343. Requires the earlier key-edges outputs to be archived first; otherwise add 77.6 MB.
- KeyEdgesRun a: T0 2.011 GB, 10.6 MB over. T1 0.670 GB. = states 1,340,719,767 + results.ser 430,694,877 + a-*.ser 221,351,385 + classes-304901c 2,056,507 + steady = 2,010,592,660 (MEASURED). With only site/ local it is 1.998 GB. DiskBudget also refuses it at a 2 GB budget: 1.789 GB held + BYTES 0.300 (KeyEdgesRun.java:53, :111-112).
- KeyEdgesRun a-prime: T0 1.855 GB. T1 0.514 GB. = states + results.ser + a-prime 65,554,901 + classes = 1,839,026,069 (MEASURED) + steady. It fits, but DiskBudget's 300 MB allowance refuses it unless BYTES is set per mode.
- RiskReport (pending): about 1.07-1.2 GB (ESTIMATED) = MEASURED list 1,056,100,053: results.ser, report-ceiling .ser, fullplay results.ser, wide.ser, wide pilot and sim/ (56 MB). Plus steady, plus simulator part-2 outputs, whose size is unknown (about 52 MB estimated, PLAN_COMPUTE.md:1173).
- Item-1 re-export (15 items, export hook, --trees none): T0 about 1.80 GB; about 1.89 with --wide; about 2.04 (over) if step 8's fit.ckpt and parts are also needed. T1 about 0.45-0.69 GB. = MEASURED base 1,526,628,480 (states, c06c061 fit.ckpt 183,689,022, run.id, classes-03b116f) + 15 parts 72,215,996 (MEASURED by the replay area) + records about 0.12 GB (ESTIMATED) + bundle about 0.06 GB (ESTIMATED) + steady. fullplay-main-97bf834's fit.ckpt and parts come to 153,383,249 (MEASURED), needed only if those rows are replayed from step 8. The DiskBudget check (FullIndexReplay.java:552-554) passes by 9 MB in T0.
- Fresh full-catalog FullIndexRun (186 items): T0 2.403 GB, 0.403 GB over, and DiskBudget refuses it. T1 1.062 GB. = states 1,340,719,767 + fit.ckpt 183,689,022 + 186 parts 431,014,069 + results.ser 430,694,877 + run.id, log and classes, about 0.8 MB = 2,386,930,172 (MEASURED, c06c061's own files as the proxy) + steady. The peak comes when results.ser.tmp is complete (FullIndexRun.java:341-350). After the start, the state file is touched only at FullIndexRun.java:232-276 (309 only with --trees-read-only), so T1 drops it entirely. During the passes T1 holds about 0.4-0.5 GB: fit.ckpt plus its .tmp (ESTIMATED).
- Custom-count full-play run (5 counts): T0 1.624 GB. T1 0.284 GB. = states + fullplay-main-97bf834 (results.ser 113,431,766, fit.ckpt 40,239,384, 30 parts 113,143,865, run.id) + classes = 1,608,628,279 (MEASURED) + steady.
- Spot check / depth-1 rescore (states + results.ser): T0 1.787 GB. T1 0.446 GB. = 1,771,414,661 (MEASURED) + steady.
- Whole 93-item replay, wide + sink + export hooks (not pending): T0 about 3.91 GB, about 1.9 over. T1 about 2.57, about 0.57 over. T1 plus parts fetched per batch plus fit.ckpt let go after restore: about 1.96 (ESTIMATED, too thin to promise). Adding compressed records, borrowed from the other angle: about 1.57. = MEASURED list 3,833,454,313: states, fit.ckpt, 93 fold parts 422,561,932, replay dir (records 928,881,429, wide.ser 249,769,420), sink run-b 704,599,811, classes. Plus bundle about 0.06 (ESTIMATED), plus steady. Records are read back at finish (FullIndexReplay.java:271-292, 586-589), so they cannot leave mid-run.
- PageLook (once per ledger, not pending): at least about 17.6 GB, more than 15 GB over, under any plan here = Requires trees.bin 14,877,661,504 (PageLook.java:179-181, 241-243), plus states, step 8's files and its outputs. Running it under 2 GB would need a --trees none option first.
- StateRun (a new 60,000-shoe state file): 1.357 GB = 1,340,719,767 + steady. DiskBudget reserves 25,000 B a shoe, 1.5 GB (StateRun.java:26, 69), so its check is 1.516 GB and passes. Archived once finished.
- Archive push and fetch themselves: push 0 B extra; fetch = the file's own size = rclone copy reads in place. A fetch writes a .partial file and renames it, so only one copy ever exists. Hashing streams.

**Code changes.**

- T0. New src/RunArchive.java and test/RunArchiveTest.java.
- `manifest` writes archive/manifest.tsv, committed in the repo, not in runs/: path relative to runs/, bytes, SHA-256, MD5, mtime in ns, remote path.
- `push` runs `rclone copy --immutable` to gdrive:backups/data/ccec-runs/<path>, then requires `rclone hashsum sha256` and `rclone md5sum` to equal the manifest.
- `fetch PATH...` calls DiskBudget.require(sum) first, then `rclone copyto` to the exact absolute path. It checks SHA-256, sets mtime to the manifest's ns value (Files.setLastModifiedTime), checks toMillis, and brings a .states file's .progress with it. On a mismatch it deletes the .partial and refuses. It also accepts a file downloaded by hand from the Drive web page.
- `evict` removes a local copy only when the manifest shows its archive copy verified.
- `need RUN` prints a run's working set.
- T0. src/DiskBudget.java:53 BUDGET_GB 9 -> 2, or CCEC_DISK_BUDGET_GB=2. Rewrite the class comment (DiskBudget.java:26-32): its 6.38 GB 'built and reproduced from' figure no longer holds once the files are archived. Update DISK_CLEANUP.md to match.
- T0. src/KeyEdgesRun.java:53: set BYTES per mode, about 0.25 GB for a and 0.08 GB for a-prime, from the 221 and 66 MB measured. Otherwise a 2 GB budget refuses a-prime, which fits.
- T1. src/StateStore.java: add a StateSource that is either the local file or an archive stream (`rclone cat` through ProcessBuilder). Header, progress and identity come from the manifest. Records are read sequentially (as readAll already does, StateStore.java:267-294), and the SHA-256 of the whole stream must equal the manifest before anything is written.
- T1. src/FullIndexPilot.java:648-672 (load) and src/KeyEdgesRun.java:140-153 (load) read from the StateSource and return the same Records.
- T1. src/FullIndexRun.java:392-395 stateFile: when the path is absent locally, return the manifest's identity string. All 13 call sites go through it (BoundRows:97, Calibration:217/497, CeilingReport:113/492, CountSearch:744, FullIndexRun:251/309/421, FullIndexReplay:565, KeyEdgesRun:201, PageLook:212/318, SiteExport:576, StrategyExport:2890). The same applies to shoesDone and records (StateStore.java:214-223) and to header-only readers: SiteExport.java:575-580, CustomCounts.java:382-393, PageLook.java:373, ReplayHooks.java:54.
- T1. src/CeilingResults.java:1398-1400: hash the twin from the stream's digest, which yields the same a2f8a751 when the bytes are the same.
- T1, only if they must run on an archived twin. Calibration.java:1585-1595 and SplitMargin.java:276-283 become one ascending pass over the same indices.
- T1 test, StreamedStatesTest. On a fixture file:
- the stream-loaded ScoringStates equals the file-loaded one bit for bit;
- the manifest's identity string equals stateFile of the local file;
- a truncated or altered stream is refused before any output exists.
Run it once on the real main and twin files before their local copies leave.
- Optional, needed only to rerun the whole 93-item replay. Borrowed from the other angle:
- Fetch each source part just before its item. That means moving the up-front hasPart check at FullIndexReplay.java:546-551.
- Let go of fit.ckpt after restore (:628).
- Deflate the records in FullIndexCheckpoint.writeRecord/readRecord (FullIndexCheckpoint.java:156-163).

**Figures bit-identical.** Yes.
- **T0** copies bytes and restores them to the same absolute path with the same mtime. Every reader sees identical files, so every figure is bit-identical.
- **T1** changes how the state file's bytes are read, not which bytes. The stream must match the manifest's SHA-256 (ed9e9fb4… main, a2f8a751… twin, both MEASURED by the investigators). The records come from the same getDouble reads, and StreamedStatesTest would prove stream-load equals file-load bit for bit.
- **No tree store** (--trees none) builds each tree to the same bits (TreeStoreTest, cited at DiskBudget.java:20-22). The check of that against the real c06c061 parts is still running (pid 48286; DISK_CLEANUP.md 'Result: pending'), so the claim is tested on fixtures but not yet on the real run.
- **Verbatim archive check:** a Drive file's server-side SHA-256 equals the downloaded bytes. MEASURED today on a 136,113 B bundle: aefd300f… on both sides. The archive area measured the same on an 83 MB file.

**Hashes.** No cited hash changes.
- **The archive is verbatim.** These keep their exact bytes:
  - results.ser 8ba1412b…, report-ceiling c854767f…, fullplay results.ser 6a456558…
  - key-edges json 824f0769…, spreads json b86622c4…
  - wide.ser ee3ff121…, the collectors, and fit.ckpt 479595d1…
  - twin a2f8a751…
  - The manifest also records the main file's ed9e9fb4… for the first time.
- **Identity strings are unchanged.** FullIndexRun.stateFile (FullIndexRun.java:392-395) needs path, size and mtime to the ms:
  - T0 restores the ns mtime from the manifest. MEASURED: an rclone download sets mtime to Drive's ms value, 1789495784975000000 ns on the test file.
  - T1 copies the identity string from the manifest.
- **The site JSON stays the same.** runs/site/*.json, results.json, manifest.json and SHA256SUMS stay identical as long as re-exports read inputs at the same absolute paths (SiteInput.java:45-50).
- **What does change:**
  - A rerun under new classes, T1's included, writes its new commit into its own outputs. Its new files then get new hashes, as any rerun at a new commit does today; the archived originals keep theirs.
  - If the Release mirror is used, fixing SHA256SUMS naming (SiteManifest.java:115, 121) changes only SHA256SUMS and MANIFEST.md of a new page build.

**Time.** **One-time:**
- Hash 6.38 GB for the manifest: about 70 s, plus about 160 s for trees.bin if it is hashed first as PLAN_PAGE.md:697 requires. ESTIMATED from 747 MB hashed in 8.06 s, MEASURED by the archive area.
- Upload 6.36 GB at 2.42 MB/s (MEASURED networkQuality): at least 44 min (ESTIMATED). Batch C adds about 6 min if archived instead of deleted.
- Server-side hash checks take seconds.

**Each run, downloads (ESTIMATED from MEASURED single-stream speeds of 1.53-4.68 MB/s):**

| Run | Download | Time |
|---|---|---|
| SiteBuild | nothing | 0 |
| WideReport | 0.25 GB | 0.9-2.7 min |
| SiteExport | 0.75 GB under T1 | 2.7-8.1 min |
| CeilingResults data | 0.75 GB | 2.7-8.1 min |
| Item-1 re-export | 1.60 GB | 5.7-17 min |
| KeyEdgesRun report | 1.86 GB | 6.6-20 min |
| Optimism | 1.98 GB | 7-21.5 min |
| A full or custom run | 1.34 GB state stream | 4.8-14.6 min |

- T1 pays the state download at every run start, because nothing is cached.
- Checking SHA-256 adds about 14 s per 1.34 GB.
- New outputs go up at 2.42 MB/s. A full run's 1.05 GB takes about 7 min.

**The largest cost, which any 2 GB plan pays, is having no tree store:**
- TreeStore.java:17-20 gives 0.17 CPU-s a tree, about 1,721 s more for each walk on 6 threads.
- A full run has about 42 walks that read trees (full-c06c061.log rounds 1-42). That adds about 20 h to a run of about 19.8 h (ESTIMATED).
- The --trees none check running now is 90% through one round after 7,370 s. MEASURED comparable 2-item batches that read trees took 418-618 s (replay log lines 54, 68). That is 3-4 times the estimate per batch, though the load average was 16-57 at the time.
- So expect +0.5 to +2 h per replay batch, and +2 to +8 h for item-1's 4 batches (ESTIMATED).

**Risks.**

- The archive may be the only copy. Once the local copies go, Drive alone would hold files that cannot be regenerated to their hash:
- montreal-80.states: three builds and about 13 h, bit-identity unproven;
- results.ser: reproducible only to within 1e-12;
- steps 7 and 8: one look each;
- the twin, run-c, run-d and the sink.
A second off-laptop copy (the Release or an external disk) is needed before the last local copy is removed.
- rclone's shared client_id is being retired. MEASURED notice: 'will stop working during 2026'. Fetches through rclone stop until Justin makes his own client_id. The fetch tool must also accept a file downloaded by hand and set its mtime from the manifest.
- mtime fidelity. Identity strings embed the mtime to the ms. The twin's mtime has a sub-ms part of 0.535 ms (1791371871711534978 ns, MEASURED), and its identity string with ms 1791371871711 is cited in 10 custom-count/search logs. If an upload or download rounded instead of truncating, the identity would change. The fetch tool must set the ns mtime from the manifest, not from Drive.
- Absolute paths. Identities, run.id files and site JSON embed /Users/justinbondurant/Documents/CardCountingEdgeCalculator/runs/... so fetches must restore to exactly that path. Moving the repo breaks this, as it would today.
- Tight T0 margins:
- optimism is 7.2 MB under;
- KeyEdgesRun report is 45 MB under;
- KeyEdgesRun a is 10.6 MB over;
- SiteExport is 108 MB over.
Any stray file breaks the tight ones. DiskBudget refuses before writing, but the fetch tool must check before downloading.
- The tree store cannot fit (14.9 GB). PageLook requires it, so any rerun or resume of the page look, including a full-play export redone through PageLook for item 1, needs a --trees none option first. Otherwise item 1 has to run before trees.bin is deleted, which means before the 2 GB cap holds.
- Runs get much slower without trees. The check now running suggests 3-4 times TreeStore's 0.5 h-a-round estimate under load. Whether --trees none reproduces the parts is still pending.
- The replay running now (pid 48286) reads states, fit.ckpt and parts. Nothing may be archived or removed until it finishes.
- Space not counted. macOS swap sits on the same SSD: 2,142.56 MB used now (MEASURED sysctl vm.swapusage), from -Xmx10g JVMs on 16 GB. DiskBudget never sees it, and neither do .worktrees (485 MB) or the macOS Trash. Trashed files free nothing until Justin empties the Trash.
- T1 code risk. If the manifest's identity string differed from stateFile by even one byte, every replay, SiteExport and PageLook identity check would refuse, or would quietly mint new identities. StreamedStatesTest has to assert equality on the real files before they leave.
- Download speed varies: 1.5-4.7 MB/s on Wi-Fi (MEASURED). A 2 GB working set takes 7-22 min to arrive, and a dropped stream means refetching. rclone writes a .partial, so a dropped stream leaves no corrupt file.
- Drive's sha256 is filled in only 'if available'. MD5 is always present, so the manifest records both and the check needs both.

**Decisions for Justin.**

- Approve the archive home gdrive:backups/data/ccec-runs/. MEASURED: gdrive:backups/data holds only conversationalData/ and laptop/, and the home is outside both protected folders. Approve the one-time upload of about 6.36 GB, at least 44 min.
- Approve local removal batch by batch, every file listed with its size and verified hash, as DISK_CLEANUP.md does. Then choose between two ways of letting go of fetched copies after each run: a standing rule that RunArchive may evict a verified copy with rm (the Trash frees no space), or a yes asked for every run.
- Choose a second off-laptop copy before the last local copy goes:
- A GitHub Release. The repo is public, so this is publishing; it needs gh installed and the SHA256SUMS naming fix (SiteManifest.java:115, 121).
- An external disk.
- Both.
Only Drive can hold the literature cache, which is third-party material.
- Create your own rclone Google Drive client_id. This is an account action only you can do.
- Say what '2gb' means. With 2.0e9 bytes, SiteExport and KeyEdgesRun a need T1. With 2 GiB (2.147e9), both fit under T0. Also: does the cap cover only runs/, or the whole checkout (then prune stale worktrees, 485 MB) or swap (no runs/ budget can promise that)?
- Approve T1, the stream of state files from the archive with identity from the manifest. It is the change that makes SiteExport, a fresh full run and KeyEdgesRun a fit, and turns the tight margins (optimism, item 1) into wide ones.
- trees.bin: deleting it is safe, since it rebuilds bit for bit. Archiving it costs 1.7 h of upload and could never come back under the cap. Accept the --trees none slowdown, and decide how item 1 runs: before trees.bin goes, or after a --trees none option is added to PageLook.
- Batch C (0.87 GB of superseded files): delete it, or archive it for about 6 min of upload. Batch B, the records, are not needed: delete.
- Keep the twin on Drive only, the default. Optimism reruns then fetch or stream it, and its hash a2f8a751 stays checkable.

## Archive-first plan for a 2 GB runs/

**Idea.** No computation and no file bytes change. Every file not in the working set of the run in hand lives off the laptop, byte for byte, and is listed in a committed manifest. A committed tool fetches it back to its exact path and mtime, with checksum verification, when a run needs it. Everything rests on one fact: every identity check in the code compares only path, size, mtime in ms (FullIndexRun.java:392-395) and SHA-256s taken by path (SiteInput.java:45-50, KeyEdgesReport.java:419-433). A verbatim restore satisfies all of them.

### Steps
1. **Wait for pid 48286 to finish.** It reads states, fit.ckpt and parts.
2. **Write and commit the manifest** (`RunArchive manifest`): every file in the post-cleanup runs/ with path, bytes, SHA-256, MD5, mtime in ns and remote path. This records ed9e9fb4… for montreal-80.states for the first time, and trees.bin's hash if Justin wants PLAN_PAGE.md:697 honoured.
3. **Push.** `rclone copy --immutable` to `gdrive:backups/data/ccec-runs/`; a differing file already there is an error, never an overwrite. Then `rclone hashsum sha256` and `rclone md5sum` must equal the manifest, and for cited files the page's hash. Never use the desktop mount.
4. **Second copy** of the unreproducible files, by Justin's choice: a Release (after fixing the SHA256SUMS name collision) or an external disk.
5. **Local removal**, Justin's yes per batch with every file listed. Batch B and trees.bin are deleted, not archived.
6. **Set the budget to 2 GB** (DiskBudget.java:53 or `CCEC_DISK_BUDGET_GB=2`). Set KeyEdgesRun.BYTES per mode.
7. **Each run then goes:** `RunArchive need RUN`, then `fetch` (DiskBudget.require first, exact path, SHA-256 and ns mtime from the manifest, `.progress` with states), then run, then `push` the new outputs, then `evict` the inputs.
8. **T1:** state files are read as an rclone stream checked against the manifest's SHA-256. Identity and header come from the manifest, so no state file is ever local.

### What stays local
15.8 MB: site JSON, the latest page, the literature cache, four small cited text files and two .progress files. Outside runs/: 28 MB of checkout and 485 MB of worktrees (prunable).

### Peaks (2.0e9 cap)
| Run | T0 | T1 |
|---|---|---|
| SiteBuild | 0.019 | 0.019 |
| SiteExport | **2.108** | 0.767 |
| Ceiling data | 0.767 | 0.767 |
| Optimism | 1.993 | 0.652 |
| KeyEdges report | 1.955 | 1.955 |
| KeyEdges a / a′ | **2.011** / 1.855 | 0.670 / 0.514 |
| Item-1 re-export | ~1.80 | ~0.45 |
| Full catalog run | **2.403** | 1.062 |
| Custom run | 1.624 | 0.284 |
| Spot check | 1.787 | 0.446 |
| 93-item replay | **~3.91** | **~2.57** |
| PageLook | **≥17.6** | **≥16** |

- T0 misses SiteExport by 0.108 GB and KeyEdgesRun a by 11 MB.
- T1 fits everything except the 93-item replay and PageLook:
  - **The replay** would need parts fetched per batch plus compressed records, borrowed from the other angle, to reach about 1.57 GB. It is not pending.
  - **PageLook** needs the 14.9 GB tree store under any plan, and is made once per ledger.

### What it costs
- **Archive traffic:** about 44 min of one-time upload, then 3-22 min of download per run.
- **No tree store:** this is the real cost, and it applies to any 2 GB plan. It means about 0.5 to 2 h more per replay batch, and roughly double the wall time of a full run (ESTIMATED; the check now running will measure it).

### Why archive-first
- Nothing published moves: no bytes, no hashes, no identity strings.
- Every check stays as strong as it is now. T1's SHA-256-gated stream is stronger than the path, size and mtime check.
- The T0 code is small and sits outside the engine.

**Weak spots:**
- The single remote copy, until a second one exists.
- The retiring rclone client_id.
- The thin T0 margins.

Hence the conditions in the verdict.

### Not adopted
- **A sparse placeholder file** with the same size and mtime: it fakes the identity, and a whole-file reader would get zeros.
- **A RAM disk:** it pushes the heap into swap on the same SSD.
- **Moving files out of runs/ behind links:** it hides the use from DiskBudget. Only acceptable if the target is genuinely off the internal disk (an external drive).

Scratch work: /private/tmp/ccec-archfirst-66261. That holds the working-set lists and one 136 KB Drive test download. The repo, runs/ and Drive were not modified.

## Plan: Shrink the files first. Everything stays on the laptop but gets smaller: a lossless v3 encoding of the state files, APFS transparent compression of every read-only file, one copy of each run's results instead of two, gzip for the checkpoints new runs write, and deletion of files nothing reads. Shrinking alone stops at about 3.17 GB (ESTIMATED), so the plan borrows one move from the archive angle: the twin state file and the five finished key-edge collectors go to Drive, kept byte for byte.

**Verdict.** Not under this angle alone. Yes with conditions if two things leave the laptop. After batches A to C, runs/ holds 6.387 GB (MEASURED, /private/tmp/ccec-shrink-66386/atrest.py, matching DiskBudget's 6.38). Shrunk losslessly and kept whole on the laptop, it is still about 3.17 GB (ESTIMATED). That assumes v3 state files at 0.4495, every other file APFS-compressed at the 0.61 to 0.80 measured on 50 MB heads, the 216 duplicate parts deleted, and 7 unread or superseded files deleted. The state files are the floor: no coder tried gets them under about 0.59 GB each (MEASURED). The plain byte-plane shuffle gives 0.449 with zlib and 0.438 with xz. XOR and delta predictors give 0.449 to 0.464 and do not beat it. With the twin and the five key-edge collectors on Drive, the local set is about 1.66 GB now. It drops to 1.24 GB once the item-1 re-export, simulator part 2 and RiskReport are done (ESTIMATED). Then every pending run fits: the page build, SiteExport, CeilingResults data, the item-1 re-export (about 1.89 GB), RiskReport and the spot check. So do new custom-count runs (1.45) and a new state file written directly as v3 (1.84). Optimism fits only after those pending runs (1.84; 2.26 before them). Still over 2 GB: a KeyEdgesRun report rerun (about 2.74), a full 93-item replay (2.22 or more), a fresh full-catalog run (2.08, unless results.ser is written compressed or shoeSums are dropped), any frozen-class run on the main file (about 2.8), and the one-time migration itself (about 3.0, for minutes).

**Steady state.** About 1.66 GB (ESTIMATED) while the item-1 re-export, simulator part 2 and RiskReport are pending, and about 1.24 GB after them, with the twin and the five collectors on Drive. For comparison: 23.29 GB today and 6.39 GB after batches A to C (both MEASURED); 3.17 GB if everything stayed local and were shrunk (ESTIMATED). Outside runs/, and not counted by DiskBudget: .worktrees 0.497 GB in 68 worktrees (MEASURED du), .git 0.021 GB, and macOS swap of 2.18 GB on the same SSD.

**Peaks.**

- At rest while the item-1 re-export, simulator part 2 and RiskReport are pending (twin and collectors on Drive): about 1.66 GB (ESTIMATED) = File sizes MEASURED with stat; shrunk sizes ESTIMATED as size x ratio by /private/tmp/ccec-shrink-66386/atrest.py. montreal v3 0.603 (1,340,719,767 x 0.4495, a MEASURED gzip-6 ratio on the shuffled legal slots; 0.4492 MEASURED here with zlib-6 on 1,024 records). c06c061 results.ser 0.317 (x0.7353). replay wide.ser 0.178 (x0.7118). report-ceiling-97bf834.ser 0.124 (x0.6109). c06c061 fit.ckpt 0.118 (x0.6424). Those four APFS ratios MEASURED by ditto --hfsCompression on 50 MB heads (apfs.sh). fullplay-main results.ser 0.085 and fit.ckpt 0.026 (ESTIMATED ratios 0.75 and 0.642). custom-count/fullplay step-6 runs 0.089 (E). sim/ 0.036 (E). key-edges json+csv 0.019 (x0.2446, MEASURED on the json). export-first 0.010 (E). Classes and build dirs 0.026 (MEASURED, left as they are). Everything else 0.029 (E).
- At rest after those runs (fit.ckpt, wide.ser, export-first, fullplay fit.ckpt and the step-6 runs parked on Drive or deleted): about 1.24 GB (ESTIMATED) = The 1.659 above minus 0.118 + 0.178 + 0.010 + 0.026 + 0.089 = 1.238 GB (atrest.py). What is left: montreal v3, the three cited .ser files, key-edges json/csv, sim/, small cited text and classes.
- Everything kept local, shrink only, no archive (the pure angle): about 3.17 GB at rest (ESTIMATED); 2.26 GB with the collectors gone = Adds to the 1.66 GB: the twin as v3, 0.603 (1,341,183,279 x 0.4494, MEASURED ratio), and the five collectors APFS-compressed, 0.910. Of that, run-b 0.482 (x0.6841 M) and a-* 0.150 (x0.6787 M). a-prime 0.050, run-c 0.115 and run-d/run-b 0.112 are ESTIMATED at their gzip ratio x 1.13, the APFS/gzip factor MEASURED on 7 binary samples (1.113-1.140). No run fits on top of it.
- SiteBuild page rebuild: about 1.66 GB = Local set 1.659 (E) + runs/site inputs already in it + new page build 0.0032 (MEASURED 3,179,185 B, site area). It reads no artifact.
- SiteExport re-export of results-*.json: about 1.66 GB (2.09 GB today, ESTIMATED by the site area) = Local set 1.659 (E): it holds the three .ser files it reads, APFS-compressed and read transparently, and the montreal v3 header with its stored v2 identity (SiteExport.java:575-580 reads only the header and FullIndexRun.stateFile). Output 0.0021 (MEASURED size of results-7c1d40e.json), written as .tmp then renamed.
- Item-1 re-export (15 items, export hook, --wide, --trees none): about 1.89 GB (ESTIMATED) = Local set 1.659, which holds montreal v3, fit.ckpt and results.ser. The 15 parts are cut from results.ser in memory: 0 on disk. Records about 0.124 raw (site area ESTIMATED) x 0.577 gzip (MEASURED, 4 records) = 0.072. Export bundle about 0.06 (E). --wide plans about 0.09 (E). One record .tmp in flight, about 0.012. DiskBudget refuses it unless REPLAY_ITEM is re-measured: 25 MB x 15 = 0.375 on top of 1.659 is 2.03.
- CeilingResults optimism (needs the twin): about 2.26 GB now (over by 0.26); about 1.84 GB after the pending runs = The local set (1.659, or 1.238 later) + the twin fetched as its v3 copy, 0.603 (E) + output about 20 KB. Its other inputs, results.ser and report-ceiling, are already local. Fetch time 2-7 min at 1.53-4.68 MB/s (MEASURED by the archive area).
- RiskReport, simulator part 2, CeilingResults data, LiteratureCheck, adapters: about 1.67 GB plus simulator part 2's outputs (unmeasured) = Local set 1.659 holds wide.ser, the three .ser files, export-first and sim/. Outputs: a few MB each (E). Simulator part 1 wrote 2.07 MB (MEASURED, sim/part1-0e0920d); part 2's per-shoe records are not measured.
- Spot check / depth-1 rescore (states + results.ser): about 1.24-1.66 GB = Both inputs are in the local set; decoded into RAM, no temporary files (StateStore.java:230-294).
- Custom-count run, 5 counts x 3 pens, on the main file: about 1.48 GB (ESTIMATED) = Local set 1.238 (E) + fit.ckpt gzip 0.023 x2 during a round write (40,239,384 x 0.57 gzip ratio of fit.ckpt, MEASURED) + parts gzip 0.080 (113,143,865 x 0.71, MEASURED part ratio) + results.ser 0.113 raw (MEASURED size). The tree store keeps what is left, about 0 to 1,000 states.
- Fresh full-catalog FullIndexRun (186 items), c06c061 kept: about 2.08 GB (over by 0.08); 1.94 if results.ser is written gzip; 1.79 if shoeSums are dropped = Local set 1.238 + at the final rename: fit.ckpt gzip 0.105 (183,689,022 x 0.570 MEASURED) + parts gzip 0.306 (431,014,069 x 0.71 MEASURED) + results.ser.tmp 0.431 raw (FullIndexRun.java:341-350). During the passes: 1.238 + 0.105 x 2 = 1.45. With shoeSums dropped (39% of parts and results, MEASURED tally): parts 0.187 + results 0.263. DiskBudget.ITEM must fall to about 4 MB, or the run is refused.
- StateRun, a new 60,000-shoe state file: about 1.84 GB if written as v3 directly; about 3.18 GB if written as v2 then converted = 1.238 + 0.603 (E, the v3 ratio). The v2 route is 1.238 + 1.341 + 0.603. StateRun's reserve per shoe (StateRun.java:26, 69) must fall from 25,000 B to about 11,000 B.
- KeyEdgesRun report rerun: about 2.74 GB (over by 0.74) = Local set 1.238 + five collectors fetched raw, 1.428 (MEASURED) + json and csv out, 0.078 (MEASURED). Its output is already written and nothing pending needs it.
- Full 93-item replay with wide, sink and export hooks: about 2.22 GB with gzip records and a slim gzip sink; about 2.91 GB as written (over either way) = 1.238 + fit.ckpt 0.118 + records 928,881,429 x 0.577 = 0.536 + wide.ser gzip 0.167 (raw 0.250) + sink slim gzip about 0.10 (raw 0.705) + bundle 0.06 (E). Nothing pending needs it.
- Any frozen-class run on the main file (e.g. KeyEdgesRun a with classes-304901c, for its cited bytes): about 2.8 GB (over) = Frozen classes read only v2, so a v2 decode is needed at the same path: v3 0.603 + v2 1.341 side by side, + results.ser 0.317 + output 0.221 + the rest of the local set.
- One-time migration (convert montreal to v3, then APFS-pack the rest): about 3.0 GB, once, for minutes = Local set with montreal still v2: 1.659 - 0.603 + 1.341 = 2.397, + the v3 output 0.603. APFS packing then adds one compressed temporary copy at a time, at most 0.32 for results.ser. The disk has 4.06 GB free now (MEASURED df) and 16.9 GB more once batches A-C are emptied from the Trash.

**Code changes.**

- src/StateStore.java: add format v3. Keep only the legal slots (StateValuer.legal, StateValuer.java:62-80). Store each block's doubles in byte planes, slot-major (the layout measured at 0.449), in 64-record blocks, compressed with java.util.zip.Deflater level 6, with a CRC32 per block and an inline block header so read(from,count) seeks by block. The header records the v2 identity (absolute path, 1,340,719,767 bytes, mtime 1790724357600) and the v2 SHA-256. header(), records(), shoesDone(), read() and readAll() (StateStore.java:208-294) decode it transparently. Add StateStore.identity(Path) and StateStore.v2Sha256(Path).
- src/StateStoreConvert.java (new): v2 to v3 conversion. It writes .tmp, decodes the whole result, compares every record and the v2 SHA-256 (ed9e9fb4..., a2f8a751...), and only then renames. Also a v3 to v2 decoder that streams to stdout, for the Release and for frozen classes.
- src/FullIndexRun.java:392-395: stateFile() returns StateStore.identity(), the stored v2 string for a v3 file. That keeps the checks in FullIndexReplay.java:539-545, SiteExport.java:575-578, PageLook.java:212-213 and CeilingReport.java:110-127 passing, and keeps the src strings unchanged.
- Every file-hash helper hashes a v3 state file's decoded v2 bytes, routed through one shared helper: CeilingResults.java:65 (the twin at :1400), SiteInput.java:54, KeyEdgesRun.java:206, WideReport.java:776, RiskReport.java:1789, FullIndexReplay.java:471 and ExportBundle.java:1181.
- src/StateRun.java:26, 69: write v3 blocks of 64 shoes directly, each forced to disk before the progress file vouches for it, and re-measure the per-shoe reserve at about 11,000 B.
- src/SystemScorer.java (beside merge, :2880-2958): add slice(Result, pen, part), the inverse of merge. src/FullIndexReplay.java:546-551 and :762: when part-*.ckpt is absent, compare each item with its slice of results.ser. src/FullIndexRun.java:349-350: delete the run's parts after results.ser is renamed (keep them with --keep-parts).
- src/FullIndexCheckpoint.java:176-201: writeObject writes through GZIPOutputStream. readObject tells 0x1f8b from 0xaced, so old raw fit.ckpt, parts and records read as before. FullIndexReplay.java:555 hashes fit.ckpt's decoded stream, which is the same hash (479595d1) for c06c061's raw file.
- src/FullIndexRun.java:218-220 and src/PageLook.java:532-535: let the records go even when an export hook refuses after the other outputs are written.
- src/DiskBudget.java:56-58, 86-91: count allocated blocks (du -k or stat %b through ProcessBuilder; Java's unix view has no block count) instead of Files.size. BUDGET_GB = 2. Re-measure ITEM (about 4-5 MB with gzip parts) and REPLAY_ITEM (about 12-16 MB).
- A committed packing script in the CardCountingEdgeCalculator repo (e.g. tools/apfs-pack.sh). It runs ditto --hfsCompression to a temp in the same folder, then checks cmp, same size and same ns mtime, then renames over the original. It refuses state files and anything open for writing, and prints du before and after.
- Tests: StateStoreTest (v3 round trip, resume, random access, stored identity, CRC refusal); StateStoreConvertTest (whole-file decode equals the v2 SHA); FullIndexCheckpointTest (gzip and raw both read); FullIndexReplayTest (a replay against results.ser slices with no parts, 186 of 186 equal); DiskBudgetTest (an APFS-compressed file counted at its blocks).
- Optional, needed only if a fresh full-catalog run must fit: stop storing Entry.shoeSums in new runs (SystemScorer.java:2280, :3289, :3341), and slim KeyEdges.Collector.save (KeyEdges.java:285-317) to the fields KeyEdgesReport reads (KeyEdgesReport.java:268-312).

**Figures bit-identical.** Yes, for every recommended step:
- **v3 state files:** they decode to the same Record bytes. The round trip is MEASURED on 9,061 records of each file (dossier) and on a 1,024-record block here (speed.py: lossless). The converter must prove the whole file by decoding it to ed9e9fb4 / a2f8a751 before it replaces anything.
- **APFS compression:** keeps every byte. MEASURED: cmp identical on all 8 samples.
- **Dropping the parts:** leaves results.ser untouched. The replay's comparison source changes from parts to slices, and those slices are MEASURED equal to the parts to the bit, 186 of 186 and 30 of 30. Only Result.seconds is lost, and ResultDiff.java:17 already ignores it.
- **gzip checkpoints:** carry the same serialized stream.
- **Letting records go:** changes no output.

The optional changes (dropping shoeSums, slimming the collectors) would leave published figures unchanged (ESTIMATED from grep, needs a test). But they change what is stored and lose StrategyExport's per-shoe check (StrategyExport.java:1851-1861) and ComparisonReport's seShoe (:1036).

**Hashes.** No cited hash changes. All of these stay valid:
- the 17 SHA-256s on the page;
- the 7 input hashes in manifest.json;
- the 41 literature cache hashes;
- the cited results.ser, report-ceiling and fullplay results.ser hashes (8ba1412b, c854767f, 6a456558);
- fit.ckpt's 479595d1 inside the replay identities;
- the twin's a2f8a751 in optimism-*.properties.

Why they hold:
- APFS packing keeps every byte.
- Deleting parts touches no cited file.
- The v3 hashes and identity strings are taken from the decoded v2 bytes and the stored v2 path, size and mtime.

What does change:
- **montreal-80.states on disk:** its raw SHA-256 after conversion is no longer ed9e9fb4. The manifest must cite the v2 hash and say the local file is an encoding of it, and the Release must ship decoded v2 bytes.
- **New runs:** their gzip checkpoints get new hashes, as any new run does.
- **Re-exports:** a re-export at a new commit differs only in its commit field, as before.
- **Optional changes:** slim collectors or dropping shoeSums would change new files' hashes, and with them a future key-edges json's inputs block.

**Time.** **Every run that loads the main file:** it inflates v3 in about 4 s on one thread. That is from 333 MB/s of v2 bytes, MEASURED with Python zlib (speed.py); java.util.zip.Inflater is ESTIMATED to match. Spread over blocks on 6 threads it is about 1 s, against 2.2 s to read v2 today (MEASURED, full-c06c061.log). APFS-compressed .ser files decompress on read, about 1-2 s per 0.4 GB (ESTIMATED).

**One-time migration:**
- Converting each state file: about 50 s of deflate on one thread (26.8 MB/s MEASURED) plus about 20 s to verify. The SHA-256 runs at 93 MB/s (MEASURED by the dossier).
- APFS packing of 1.57 GB: a few minutes (ESTIMATED; 8 x 50 MB samples with gzip took 38 s).

**Per run, new code:**
- gzip checkpoints: under 2 min per 15-20 h run, under 0.2% (ESTIMATED). fit.ckpt 184 MB at about 27 MB/s is about 7 s x 7 writes; the parts add about 16 s, the records about 35 s.
- Replay slicing parts from results.ser: about 7 s (MEASURED by the full-index area's probe) and about 0.45 GB more heap.

**Tree store:** at 2 GB it keeps almost no trees, which costs about 0.5 h a round on 6 threads (DiskBudget.java:30-32). That is about +3.5 h on a 19.8 h full run (7 rounds) and about +2 h on the item-1 re-export (4 batches), both ESTIMATED. The machine is under load: the running 2-item check was 70% through one last round after 5,304 s (MEASURED).

**Borrowed archive:**
- One-time upload: twin v2 + twin v3 + collectors, 3.37 GB, at least 23 min at 2.42 MB/s up (MEASURED speed). Later parking of fit.ckpt, wide.ser and others, 0.62 GB: at least 4 min.
- Per optimism run: a 2-7 min fetch.

**Risks.**

- Frozen classes (classes-c06c061, -0e0920d, -304901c, -03b116f, build-d3c0b1f/-d791438/-26f0724) read only v2. Rerunning anything to its cited bytes with them on the main file needs a v2 decode at the same path, about 2.8 GB. For example, regenerating a or a-prime.
- The replay running now (pid 48286, classes-03b116f) reads montreal v2 and the part files. Nothing is converted, packed or deleted until it ends.
- The state-file check gets weaker. A v3 file reports a stored path, size and mtime instead of live ones. Protection against a changed file moves to the per-block CRC32 and the converter's whole-file SHA check.
- Once montreal is converted, `shasum runs/montreal-80.states` no longer gives ed9e9fb4. Anyone checking the local file must use the decoder. The Release must ship the decoded v2 bytes: streamed to the uploader, or written as a 1.34 GB file, which does not fit under 2 GB.
- Any rewrite undoes APFS compression, and copies or downloads lose it (rclone writes uncompressed), so a restored file must be packed again. If DiskBudget keeps counting Files.size, it will refuse runs that fit on disk.
- The ratios come from 50 MB heads and a 1,024-record sample. Whole files may differ by a few percent. Margins are thin: 0.11 GB for the item-1 re-export, 0.16 GB for optimism and for a new state file.
- Simulator part 2's outputs are not measured. If its per-shoe records are large, part 2 or RiskReport may not fit.
- The borrowed half depends on Drive through rclone, whose shared client_id rclone says stops working during 2026.
- The migration goes over 2 GB once, to about 3.0 GB. Files moved to the macOS Trash free nothing until Justin empties it.
- v3 changes StateStore, which every run reads. A decode bug would corrupt every figure; the guard is the whole-file SHA check plus a test comparing every record.
- The cap covers runs/ only. .worktrees (0.497 GB), .git (0.021 GB) and swap (2.18 GB on the same SSD) are not counted by DiskBudget.

**Decisions for Justin.**

- Yes or no to batches A, B and C (16.90 GB, already listed), and to a new batch D: 7 files, 166,219,864 B. D is page-look-0e0920d/wide-first.ser and replay/wide.ser (nothing reads them), plus wide/spreads-*.json and report-wide-* for 0a447de and 0e0920d, which are superseded and cited nowhere (MEASURED grep).
- Yes or no to deleting the 216 part files (0.544 GB) once the replay compares against results.ser slices and pid 48286 has finished.
- May the twin live on Drive, byte for byte, plus a v3 copy for fetching? Without that, 2 GB cannot be reached; the honest alternative is a cap of about 2.3 GB.
- The five key-edge collectors (1.428 GB): Drive, or delete for good? Deleting means KeyEdgesRun report can never be rerun and five hashes cited in key-edges-7c1d40e.json can no longer be checked.
- What does the 2 GB cover: runs/ only, or the whole project folder (then the 68 worktrees, 0.497 GB, need pruning)? Does swap count?
- Approve the v3 state format, accepting two things: frozen classes can no longer read the converted main file, and the identity check becomes a stored one backed by CRC and SHA checks.
- Rerun optimism only when CeilingResults or its inputs change, not on every page build, so the twin is fetched rarely.
- Order: finish the item-1 re-export, simulator part 2 and RiskReport under today's 9 GB budget before migrating, or after it with classes that read v3.
- Optional: drop Entry.shoeSums and slim the collectors in new runs. Figures are unchanged but two checks are lost. Needed only if a fresh full-catalog run must fit under 2 GB.

## Where shrinking stops

After batches A to C, runs/ holds 6.39 GB (MEASURED). Shrunk losslessly and kept whole on the laptop, it is still about **3.17 GB** (ESTIMATED).

The state files set the floor. I tried XOR-with-neighbour and integer-delta predictors on 1,024 records:
- the plain byte-plane shuffle compresses to 0.449 with zlib and 0.438 with xz;
- the predictors give 0.449-0.464 and beat neither (MEASURED, `planes2.py`);
- the low six bytes of each value are close to random: they compress only to 0.87 of their size.

So each state file stays at about 0.59 GB however it is coded. Two things must leave the laptop: the twin and the five finished key-edge collectors. That part is borrowed from the archive angle.

## Steps

1. **Delete what nothing reads** (Justin's yes, no code).
   - Batches A, B and C: 16.90 GB.
   - New batch D: 7 files, 166,219,864 B. Two are the page look's `wide-first.ser` and `replay/wide.ser`, which nothing reads. Four are the superseded 0a447de and 0e0920d spreads and report-wide files, cited nowhere (MEASURED grep). The seventh is an empty 0e0920d log.
   - First, commit the SHA-256 of both state files and of trees.bin (PLAN_PAGE.md:697).
2. **One copy of the results** (code, about 1 day).
   - The replay cuts each item out of results.ser, the inverse of `SystemScorer.merge` (:2880-2958), instead of reading `part-*.ckpt` (FullIndexReplay.java:546-551, :762).
   - FullIndexRun deletes its parts after the rename at :349.
   - Then delete the 216 existing parts (0.544 GB) once pid 48286 ends. Bit-identical: 186 of 186 and 30 of 30 parts MEASURED equal to their slices.
3. **Budget the disk, not the logical size** (about ½ day).
   - `DiskBudget.bytes` (:86-91) counts allocated blocks.
   - `BUDGET_GB` becomes 2.
   - `ITEM` and `REPLAY_ITEM` are re-measured for the compressed checkpoints.
4. **Compress read-only files in place, no reader changes.**
   - A committed script runs `ditto --hfsCompression` to a temp copy, checks `cmp`, size and mtime, then renames over the original.
   - MEASURED on 50 MB heads: physical size is 0.61-0.80 of logical for .ser and .ckpt files and 0.24 for JSON, with every byte identical.
   - Frozen classes, hashes and identities are untouched. The 1.57 GB these files hold drops to about 1.06 GB.
5. **State file v3** (code, about 2-3 days). The format:
   - legal slots only (StateValuer.legal);
   - slot-major byte planes in 64-record blocks;
   - JDK Deflater level 6, a CRC32 per block, and a block index;
   - a header that stores the v2 path, bytes, mtime and SHA-256.

   How it plugs in:
   - StateStore decodes it transparently.
   - `FullIndexRun.stateFile` (:392-395) returns the stored identity, so replays, SiteExport and PageLook still accept c06c061's runs and the `src` strings are unchanged.
   - Every file-hash helper hashes a v3 file's decoded v2 bytes, so optimism still records a2f8a751.
   - The converter decodes the whole file to ed9e9fb4 before it renames.

   Result: each file goes from 1.341 to about 0.603 GB. Decoding takes about 4 s on one thread (MEASURED zlib speed).
6. **New runs write small** (about 1 day).
   - gzip inside `FullIndexCheckpoint.writeObject/readObject`, detected by magic bytes, so old raw files read as before.
   - fit.ckpt is hashed over its decoded stream, which gives the same hash for c06c061's raw file.
   - Records are let go after a refused export (FullIndexRun.java:218-220, PageLook.java:532-535).
   - StateRun writes v3 directly.
7. **Borrowed: the twin and the collectors go to Drive, byte for byte** (Justin's yes).
   - Upload with `rclone copy --immutable`, then check with `rclone hashsum sha256` against a2f8a751 and the five cited collector hashes.
   - Also upload a v3 copy of the twin, so optimism fetches 0.60 GB, not 1.34.
   - After the item-1 re-export, simulator part 2 and RiskReport, park fit.ckpt, wide.ser, export-first and the step-6 runs the same way, or delete them.

## Order

Pending work can finish under today's 9 GB budget. Then:
1. pid 48286 ends;
2. steps 1-3;
3. step 7;
4. convert montreal, a one-time peak of about 3.0 GB for minutes;
5. step 4;
6. budget set to 2 GB.

Every run after the conversion uses classes built after step 5.

## Result

The local set is **1.66 GB now and 1.24 GB after the pending runs** (ESTIMATED).

**Fits under 2 GB:**
- page build, 1.66;
- SiteExport, 1.66 (2.09 today);
- CeilingResults data, RiskReport and the spot check, about 1.67;
- item-1 re-export, 1.89;
- custom-count runs, 1.48;
- a new state file written as v3, 1.84;
- optimism, 1.84, but only after the pending runs (2.26 before them).

**Does not fit:**
- KeyEdgesRun report rerun, 2.74;
- a full 93-item replay, 2.22 or more;
- a fresh full-catalog run, 2.08. It fits at 1.79-1.94 only if results.ser is written gzip or shoeSums are dropped;
- any frozen-class run on the main file, about 2.8;
- the migration itself, about 3.0, once.

## Not recommended

- **An xz or zstd jar:** saves about 0.3 GB more, but every reader and hash site changes and frozen classes break.
- **float32 values or dropping resamples:** the figures change.
- **Regenerating the state files:** 13 h, and bit-identity is unproven.
- **Deleting the twin:** its hash is cited and it cannot be rebuilt to that hash.

Scratch scripts are in /private/tmp/ccec-shrink-66386. Nothing in the repo, the worktrees, runs/ or Drive was changed.

## Investigator: The full-index run's outputs (runs/full-index/full-c06c061 and the 5-count custom run runs/custom-count/fullplay-main-97bf834): results.ser, part-*.ckpt, fit.ckpt, trees.bin, and the state file each run reads

## The full-index run's outputs

**Bottom line:** results.ser and the 186 part files are two copies of the same numbers, so one of them, 0.43 GB, can go with every figure kept to the bit. Even then, a fresh full-catalog run cannot stay under 2 GB on disk until a state file (1.34 GB) and a 6.8 KB file of round timings are dealt with.

### What fills a Result
I deserialized the files with a scratch reader compiled against `runs/full-index/classes-c06c061`; it changed nothing. The primitive arrays make up 99.8% of results.ser (430,694,877 B):

| Field | Bytes | Share |
|---|---|---|
| `Entry.shoeSums` (117 entries × 3 penetrations × 60,000 shoes × 8 B) | 168,480,000 | 39.1% |
| `Entry.boot` (351 entry-penetrations × 51 metrics × 1000 resamples × 8 B) | 143,208,000 | 33.3% |
| `Fit.sum` | 73,678,000 | 17.1% |
| everything else | about 45 MB | |

fit.ckpt (183,689,022 B) is 72% `PolicyState.sum` (132.1 MB), across its 372 strategies.

### Parts and results.ser hold the same numbers (MEASURED)
- **Every part matches results.ser.** For all 31 counts, merging a count's 6 parts with c06c061's `SystemScorer.merge` gives a Result identical to results.ser's to the bit (0 differences in about 55M numbers).
- **The rebuilt file has the same hash.** Re-serializing the merged parts, with the `perfect` array and `rounds` from results.ser, in the order `FullIndexRun.java:343-347` writes them, gives SHA-256 `8ba1412b…`. That is results.ser's own hash, the one runs/site/results-7c1d40e.json cites.
- **Inputs found in fit.ckpt match too.** fit.ckpt's `perfect` (60,743 doubles) and its 6 pass rounds equal results.ser's to the bit.
- **A small piece exists only in results.ser:** the 43 last-round `RoundStats`, 6,800 B of timing records.
- **Each part can be cut back out of results.ser.** For all 186 parts, 2,427 of 2,427 own-penetration slots equal results.ser's, every other-penetration slot is empty, and no other field differs. The one exception is `Result.seconds`, a timing that `ResultDiff` already ignores (ResultDiff.java:17).
- **The 5-count run shows the same thing.** fullplay-main-97bf834: rebuilt SHA `6a456558…` matches the file, and 585 of 585 slots match.

### What this means
- **A finished run can keep just one copy.** A run only needs its parts while it may still be resumed.
- **The replay can compare against results.ser.** It now refuses without the parts (FullIndexReplay.java:546-551) and diffs each part (:762-770). It could cut each part out of results.ser instead (the inverse of merge, SystemScorer.java:2880-2958), with the same check to the bit. PageLook already checks to the bit against step 8's results.ser (PageLook.java:477-508).
- **Keeping results.ser is the safe choice for c06c061,** because its SHA is cited. For a new run, keeping only the parts plus the 6.8 KB of timings gives the lowest peak.

### Bootstrap resamples
The seed rebuilds only the multiplicity table, in milliseconds (Bootstrap.java:26-45). Each resample's figure comes from refitting the play on that resample's drawn shoes (SystemScorer.java:2837, :2854). Recomputing them needs every state's last-round values, which means a replay:
- The main run spent 2.10 h on last rounds and 10.75 h scoring (full-c06c061.log, 43 batch lines).
- The main replay took 14.90 h for 93 items.

So the resamples have to stay stored.

### Compressibility (MEASURED, nice 15, single thread)
| File | gzip -6 | zstd -3 | zstd -19 | xz -6 |
|---|---|---|---|---|
| results.ser (first 200 MB) | 0.680 | 0.615 | 0.512 | 0.465 |
| fit.ckpt (whole) | 0.570 | | 0.533 | 0.484 |
| part-23-2-folds | 0.711 | | 0.548 | 0.503 |
| part-1-2-folds | 0.713 | | 0.621 | 0.580 |

Compression keeps the figures bit-identical but changes each file's SHA-256:
- results.ser's hash is the page artifact (SiteExport.java:377 → SiteInput.java:45-50 → SHA256SUMS).
- fit.ckpt's hash `479595d1…` is in the replays' identities (FullIndexReplay.java:556; runs/replay/c06c061-0e0920d/run.id).

Both stay valid only if the hash is taken over the decompressed stream. Java has only gzip built in; xz or zstd would need a new jar.

### Peak working set
| Run | Without trees | With every tree kept |
|---|---|---|
| Full catalog (186 items) | **2.386 GB**: state file 1.341 + fit.ckpt 0.184 + parts 0.431 + results.ser 0.431, at the moment results.ser.tmp is finished and renamed | 17.26 GB |
| Custom, 5 counts | **1.608 GB**: 1.341 + 0.040 + 0.113 + 0.113 | 16.49 GB |

- **Full catalog under a 2 GB budget:** `DiskBudget.require` refuses it outright, since 1.34 GB held plus 10 MB × 186 items = 1.86 GB comes to 3.2 GB.
- **Custom run under a 2 GB budget:** with only its state file in runs/, it would keep 1,466 of 60,743 states' trees, for a peak of about 1.97 GB.
- **Full catalog with parts but no results.ser:** peak 1.955 GB. That is under 2 GB only if nothing else is in runs/ and `DiskBudget.ITEM` (10 MB, against a measured 5.6 MB per item) is re-estimated.
- **The state files are the hard limit.** The two together hold 2.68 GB, more than the whole 2 GB.
- **Disk use outside runs/ that the budget never sees:**
  - macOS swap, 2.18 GB used now, from a 10 GB Java heap on a 16 GB machine.
  - The disk has only 3.9 GiB free, and a new tree store needs 3 GB left free (TreeStore.java:112-117).

### Limits of this report
- The peaks are sums of measured file sizes; that they are the true peaks is an estimate.
- "No published figure reads shoeSums" is an estimate from grep, not a test.
- I deleted nothing. The running replay (`--fit-from full-c06c061 --trees none`) reads these parts and fit.ckpt.
- Scratch probe: /private/tmp/claude-501/-Users-justinbondurant-Documents-docsClaude/d6aafcf8-409d-439c-8160-09a15aed8376/scratchpad/fia/Probe.java

**Peak working set.** Fresh full catalog run (31 counts x 3 pens = 186 items, main file). The peak comes at the end, when results.ser.tmp is complete and the parts still exist (FullIndexRun.java:341-350): montreal-80.states 1,340,719,767 + fit.ckpt 183,689,022 + 186 parts 431,014,069 + results.ser 430,694,877 = 2,386,117,735 B. Adding run.id 1,178 B, the log about 55 KB, the classes dir 1.24 MB (du) and the largest part's .tmp (6.8 MB, while it is written) gives about 2.39 GB without trees. Every component is MEASURED; the sum as the peak is ESTIMATED. With every tree kept, add trees.bin 14,877,661,504 + index 7,791, about 17.26 GB. During the passes it is about 1.71 GB (state file + fit.ckpt + fit.ckpt.tmp, FullIndexCheckpoint.java:176-187), ESTIMATED on the assumption that each round's fit.ckpt is close to the final 0.18 GB (the dry run projected 229 MB). Under a 2 GB budget, DiskBudget refuses this run whatever the trees setting: 1.34 GB held + ITEM 10 MB x 186 = 1.86 GB is more than 2.0 GB (FullIndexRun.java:279-281, DiskBudget.java:56). Custom-count run (5 counts, main file, 3 pens = 30 items): 1,340,719,767 + fit.ckpt 40,239,384 + parts 113,143,865 + results.ser 113,431,766 = 1,607,534,782 B (1.61 GB), plus classes 1.7 MB, run.id 3 KB and log 16 KB. With its own full tree store, add 14.88 GB (16.49 GB). With --trees-read-only, which is how 97bf834 ran per its log, c06c061's 14.88 GB store must stay on disk. Under a 2 GB budget with only that state file in runs/, treesToKeep (FullIndexRun.java:372-379) gives floor((2e9 - 1,340,719,767 - 300,000,000) / 244,928) = 1,466 states (2.4%), a 0.36 GB store and a peak of about 1.97 GB. The two state files together hold 2.68 GB, already over 2 GB. Outside runs/: the JVM runs with -Xmx10g on 16 GB, and macOS swap on the same SSD shows 2.18 GB used now (MEASURED: sysctl vm.swapusage). The internal disk has 3.9 GiB free (MEASURED: df), and TreeStore needs 3 GB free beside a new store (FullIndexRun.java:73, TreeStore.java:112-117).

**Artifacts.**

- `runs/full-index/full-c06c061/results.ser` (430,694,877 (MEASURED ls -la)). Holds: Written at FullIndexRun.java:341-350 as identity (UTF), List of 31 Results (430,200,156 B serialized alone), perfect double[60743] (485,971 B), rounds: 44 lists (the passes' 1,128 B plus 43 last-round RoundStats, 6,800 B). MEASURED with a scratch reflective tally (Probe.java, compiled against runs/full-index/classes-c06c061). The largest fields are Entry.shoeSums 168,480,000 B (39.1%: 351 arrays, 117 entries x 3 pens x 60,000 shoes x 8 B; SystemScorer.java:2280, filled at :3289/:3341), Entry.boot 143,208,000 B (33.3%: 17,901 arrays of 1000 doubles = 351 entry-pens x METRICS 51; SystemScorer.java:75, :2278), Fit.sum 73,678,000 (17.1%), Fit.reach 14.7 MB, Fit.move 7.4 MB, Summary.frequency/gain/se 5.25 MB each, Entry.keys 3.67 MB, halfFitBoot and fullHalfFitBoot 0.74 MB each. Read by: CeilingReport.read (CeilingReport.java:96-104), called from SiteExport.java:369 (hashed at :377), Calibration.java:589, CeilingResults.java:741/746/1387, KeyEdgesRun.java:336/444, KeyEdgesReport.java:411, SearchReport.java:898, StrategyExport.java:2866, BoundRows.java:75, ComparisonReport --published, and PageLook.java:477 (step 8's copy). FullIndexPeek.read (FullIndexPeek.java:100) is used by RiskReport.java:294. Readers do not read the rounds. Needed for: Every published figure of the main run. SiteExport lists its SHA-256 as an artifact. Regenerable: Byte-identical from the 186 parts plus fit.ckpt's perfect array and passes' rounds plus 6,800 B of last-round RoundStats, which exist only in this file. MEASURED: merging the parts with c06c061's SystemScorer.merge and re-serializing (FullIndexRun.java:343-347 order, into a digest only) gives SHA-256 8ba1412b... over 430,694,877 B, the file's own hash. The probe took about 7 s. Without those 6,800 B the bytes differ but the figures do not. From nothing it is the whole run: passes 6.88 h (log rounds 0-5), last rounds 2.10 h, scoring 10.75 h (43 batch lines of full-c06c061.log). Compressibility: MEASURED on the first 200,000,000 B: head -c 200000000 results.ser | nice -n 15 gzip -6 | wc -c gave 136,036,790 (0.680); zstd -3 -T1 gave 123,084,485 (0.615, 2.7 s); zstd -19 -T1 gave 102,308,335 (0.512, 2:12 wall); xz -T1 -6 gave 92,975,952 (0.465, 3:39 wall). Hash-cited: Yes. sha256 8ba1412b64c8b0276ee988440172737277b36aebec4ab63da94c77536ec93285 with bytes 430694877 is in runs/site/results-7c1d40e.json _source.artifacts (SiteExport.java:377 via SiteInput.java:45-50), and from there in the page manifest and SHA256SUMS (SiteManifest.java:79-124). It is not in verification/evidence.tsv or any committed file (grep of the worktree).
- `runs/full-index/full-c06c061/part-*.ckpt (186 files)` (431,014,069 in all: 93 folds 422,561,932, 93 halves 8,452,137; largest part-19-2-folds 6,763,005; smallest 18,176 (MEASURED ls -la | awk)). Holds: Each file is the identity plus one Result with one penetration's slots filled (FullIndexCheckpoint.java:146-148, key at FullIndexFit.java:2564-2566). A folds part holds the entries' and fullEntries' estimate, boot, shoeSums and keys at pen p, plus resolution, groups, fullFirst, fullFit and halfFitBoot. A halves part holds fullHalfFit*, fullInSample and fullHalfFitBoot. MEASURED tally of part-1-2-folds (3,390,145 B): shoeSums 42.5%, boot 36.1%, Fit.sum 14.3%. Read by: JointRun resume (FullIndexFit.java:2343); FullIndexReplay hasPart refusal (FullIndexReplay.java:546-551) and readPart + ResultDiff per item (:762-770); FullIndexPeek.java:64 mid-run; FullIndexDryRun.java:286 for its size. The replay running now (classes-03b116f, --fit-from full-c06c061 --trees none) reads them. Needed for: Only resuming an unfinished run, and a replay's item-by-item check. Once results.ser exists they are a second copy of it. Regenerable: Yes, exactly, by cutting them out of results.ser (the inverse of SystemScorer.merge, SystemScorer.java:2880-2958). MEASURED (Probe slices): for all 186 parts, 2,427 of 2,427 own-pen slots equal results.ser's to the bit, every other-pen slot is empty (null, 0.0, or Groups/Fit/Summary[2] of nulls), and no non-pen field differs. The only exception is the per-part Result.seconds timing, which results.ser keeps only as a sum and ResultDiff sets aside (ResultDiff.java:17, :278). Compressibility: MEASURED (whole files): part-1-2-folds gzip -6 2,415,491 (0.713), xz -T1 -6 1,966,016 (0.580), zstd -19 2,104,270 (0.621); part-23-2-folds 4,798,339 (0.711), 3,397,940 (0.503), 3,696,253 (0.548); part-1-2-halves gzip -6 55,682 (0.815). A naive 8-byte-plane shuffle of part-23-2-folds was worse than plain xz at every shift (best 3,662,592, ratio 0.543; scratch shuffle.py), because the serialization headers break the alignment. Hash-cited: No. Not in runs/site/*.json; replay identities name fit.ckpt's SHA-256 only.
- `runs/full-index/full-c06c061/fit.ckpt` (183,689,022 (MEASURED)). Holds: Object[]{fitted=true, 372 FullIndexFit.State (31 counts x 3 pens x 2 folds + 2 halves), 6 RoundStats, perfect double[60743]} (FullIndexCheckpoint.java:109-115). MEASURED tally: PolicyState.sum 132,110,000 B (71.9%), reach 26,290,000 (14.3%), move 13,211,000 (7.2%), State.bestSums 10,496,640 (5.7%), perfect 485,944. Its perfect array and rounds equal results.ser's perfect and rounds[0] to the bit (MEASURED). Read by: JointRun resume (FullIndexFit.java:2292 via FullIndexCheckpoint.java:122-144); FullIndexReplay.restore (FullIndexReplay.java:628), with sha256(fitFile) at :556 in the replay identity (:460); FullIndexDryRun.java:228 for its size. Needed for: Every replay (wide plans, sink, simulator export, page look). It is the only record of the fitted strategies. Regenerable: Only by rerunning the passes, 6.88 h on 6 threads (MEASURED: sum of 'round 0..5 done in' lines in full-c06c061.log), on the same classes and thread count to be bit-identical (JointRun doc, FullIndexFit.java:2185-2212). Compressibility: MEASURED (whole file): nice -n 15 gzip -6 gave 104,613,233 (0.570); zstd -19 -T1 gave 97,873,967 (0.533, 2:29 wall); xz -T1 -6 gave 88,863,908 (0.484, 3:10 wall). Hash-cited: Yes, in replay identities: SHA-256 479595d13bceba7c... appears in runs/replay/c06c061-0e0920d/run.id and replay.txt, and in runs/replay/gate0-304901c/run.id. It is carried into wide.ser's identity (ReplayHooks.java:34-36). It is not in runs/site/*.json.
- `runs/full-index/full-c06c061/trees.bin (+ trees.bin.index)` (14,877,661,504 + 7,791 (MEASURED) = 60,743 records x 244,928 B (TreeStore.RECORD, TreeStore.java:52-55)). Holds: Every state's player trees (header plus 10 rows of doubles per hand). Read by: TreeStore (FullIndexRun.java:308-310); replays and PageLook with --trees-read-only. Needed for: Time only. No figure comes from it (DiskBudget.java:20-22). Regenerable: Yes, built every round to the same bits (TreeStoreTest per DiskBudget.java:22). Cost: 'about half an hour more a round on six threads' (DiskBudget.java:30-32). Round 0, which built and stored every tree, took 9,096.5 s (log). Compressibility: Not measured (outside this area's sample list). Hash-cited: No.
- `runs/custom-count/fullplay-main-97bf834/{results.ser, fit.ckpt, part-*.ckpt}` (results.ser 113,431,766; fit.ckpt 40,239,384; 30 parts 113,143,865; run.id 3,023 (MEASURED)). Holds: The 5-count full-play run (F1, F2, Hi-Lo, Zen and Hi-Opt II at grain 500) on the main file, 3 pens, 1000 resamples. results.ser tally: shoeSums 44,640,000 (39.4%), boot 37,944,000 (33.5%), Fit.sum 16,544,000 (14.6%). Read by: Same readers as c06c061's results.ser through --fullplay-run (SiteExport.java:476/480, CeilingResults.java:746); its fit.ckpt and parts are read by PageLook run D (PageLook.java:448-477). Needed for: The registered rows at full play on the page. Regenerable: MEASURED: merged parts deep-equal results.ser for all 5 counts; the rebuilt stream hashes to 6a456558... (113,431,766 B), the file's own hash; 585 of 585 own-pen slots match with other pens empty. So the parts are a second copy here too. Compressibility: Not separately measured; same layout, so ESTIMATED like c06c061's (xz about 0.47-0.58). Hash-cited: results.ser: sha256 6a456558649bf3d01ad7cd543856e8312fe6913c533788e7435501400d8c5302 in runs/site/results-7c1d40e.json. Parts and fit.ckpt: not in runs/site.
- `runs/montreal-80.states (input of every main-file run)` (1,340,719,767 (MEASURED, from run.id)). Holds: The valued states, loaded whole into memory at start (FullIndexPilot.load, FullIndexRun.java:252; 'states read in 2.2 s' in the log). Read by: FullIndexRun.java:252, FullIndexReplay.java:528, every reader that pairs with it (CeilingReport.requirePaired, CeilingReport.java:110-127). Needed for: Every run. Its path, size and mtime are in each identity (FullIndexRun.java:392-395). Regenerable: Another area (StateRun). Compressibility: Not measured here (state-file area). Hash-cited: No hash; its path, size and mtime are in run.id identities.

**Ideas.**

- Keep results.ser and drop the parts once it is written. The replay would cut each item out of results.ser (the inverse of SystemScorer.merge, SystemScorer.java:2880-2958) instead of reading part-*.ckpt (FullIndexReplay.java:546-551, :762). PageLook.Step8 already checks against a results.ser to the bit (PageLook.java:477-508). Saves 431.0 MB on c06c061, 113.1 MB on fullplay-main-97bf834, and 40.6 MB in the other custom dirs. Bit-identical: yes. MEASURED on 186 of 186 and 30 of 30 parts; only Result.seconds is lost, and ResultDiff ignores it. The SHA-256 of results.ser is unchanged because the file is untouched. Cost: about a day of code and tests (a split function, the replay, FullIndexRun deleting parts after :349-350, FullIndexReplayTest); the replay heap grows about 0.45 GB to hold results.ser; at-rest only, the run's peak is unchanged. Deleting the existing parts is Justin's call, and must wait for the running replay, which reads them.
- For a new run to stay under 2 GB at every moment, do the reverse: keep the parts, write no results.ser, and have readers (CeilingReport.read, FullIndexPeek.read) merge the parts on read. Peak drops to 1,340,719,767 + 183,689,022 + 431,014,069 = 1.955 GB, from 2.386 GB. Figures are bit-identical (MEASURED: merged parts deep-equal results.ser). The results.ser bytes, and so the SHA a page cites, can be rebuilt exactly on demand if the 43 last-round RoundStats (6,800 B, MEASURED) are saved in a small file. Cost: readers, SiteExport's artifact hash, and DiskBudget.ITEM, which is 10 MB against a measured 5.6 MB per item. Without re-estimating ITEM, a 2 GB budget still refuses the run.
- Stop storing Entry.shoeSums in new runs. It is 168.5 MB of results.ser and the same again in the parts (39%; 44.6 MB per copy for the 5-count run), MEASURED tally. No page reader uses it (grep of Site*, CeilingReport, CeilingResults, KeyEdges*, RiskReport: ESTIMATED that published numbers are unchanged). Two checks would be lost: StrategyExport's per-shoe check of first-decision exports (StrategyExport.java:1851-1861, fed from results.ser at :2377/:2866) and ComparisonReport's seShoe (:1036). A new run's results.ser bytes change anyway (new commit). It cannot be applied to c06c061 without breaking the cited SHA 8ba1412b.
- Lossless compression at rest. MEASURED ratios: results.ser xz -6 0.465, zstd -19 0.512, zstd -3 0.615, gzip -6 0.680 (200 MB sample); fit.ckpt xz 0.484, zstd -19 0.533, gzip 0.570; fold parts xz 0.50-0.58, gzip 0.71. With xz that is about 0.43 to 0.20 GB for results.ser and 0.18 to 0.09 GB for fit.ckpt (ESTIMATED from the ratios). Figures are bit-identical, but each file's SHA-256 changes. The page's 8ba1412b (SiteInput.java:45-50) and the replay identity's fit.ckpt hash 479595d1 (FullIndexReplay.java:556) stay valid only if the hash is defined over the decompressed stream. Java has only gzip built in; lib/ holds only the mongo driver and junit, so xz or zstd would need a new jar. xz -6 costs about 3.6 min of wall time per 200 MB at nice 15.
- Park results.ser, fit.ckpt and parts on Google Drive between uses with rclone copy, checked with rclone md5sum. Drive has 3.65 TiB free (MEASURED: rclone about gdrive:). The bytes and SHA are unchanged, and 0 GB stays local at rest. A replay pulls back 0.61 GB (results.ser + fit.ckpt). Never read through the desktop mount.
- Do not try to regenerate the bootstrap from the seed. The seed rebuilds only the multiplicity table (Bootstrap.java:26-45, 60 MB in memory, instantly). Each boot[p][m][r] comes from refitting the play on resample r's drawn training shoes (SystemScorer.java:2837, :2854), so it needs every state's last-round values: a replay. MEASURED cost: the main run's last rounds 2.10 h plus scoring 10.75 h, and the main replay 14.90 h for 93 fold items. Storing boot as float would save about 72 MB but is not bit-identical.
- Run with --trees none, or let a 2 GB budget keep 0 to about 1,466 states: 0 to 0.36 GB instead of 14.88 GB, with no change to any figure (DiskBudget.java:20-22). The cost is about half an hour more a round on 6 threads (DiskBudget.java:30-32).
- Keep one state file local at a time (1.34 GB each, 2.68 GB for both); the other goes to Drive. That is the binding term in any 2 GB plan. It belongs to the state-file area.

## Investigator: The results page and its evidence/reproducibility story: which files the page build reads, which files' SHA-256s the page and manifest cite, and which of those must stay on local disk versus only exist somewhere (Drive archive or GitHub Release)

## The page build reads 3.1 MB and nothing else from runs/

SiteBuild reads three things:
- `runs/site/*.json` (SiteData.java:149-161, called at SiteBuild.java:1668);
- `site/template.html`, `page.css` and `page.js` (SiteBuild.java:1669-1670);
- the website's index.html, for its colour tokens.

It never opens an artifact. The hashes it prints are copied from each input's `_source.artifacts` (SiteManifest.java:80-103); SiteData only uses those paths as text (SiteData.java:240-246).

**MEASURED proof.** I copied the 7 inputs (3,112,143 B), 613255a's `src`+`site` (`git archive`), the mongo jar and index.html into `/private/tmp/sitearea-53838`. I then ran `SiteBuild --check` under a `sandbox-exec` profile that blocks reads of the whole CardCountingEdgeCalculator folder and the website repo; `cat` of a runs/site file fails under it with "Operation not permitted". The result: "every file matches a fresh build", exit 0, 3.8 s, 271 MB RSS. All 48 files of `runs/page-613255a` were rebuilt byte for byte.

A bit-identical rebuild also needs the same `--version draft --date 2026-10-17 --commit 613255a…` and Java 25.0.1+8-27, because manifest.json records `java` (SiteManifest.java:68).

## What the page cites

- **On the page itself:** index.html shows 17 SHA-256s (MEASURED grep). They are manifest.json (d672cb26…), results.json, results.csv and 14 artifacts.
- **The 14 artifacts:**
  - three `.ser`: results.ser 430.7 MB, report-ceiling-97bf834.ser 203.0 MB, fullplay-main-97bf834/results.ser 113.4 MB;
  - key-edges json 56.3 MB and spreads json 8.4 MB;
  - report-final md, report-ceiling md, pilot txt and ceiling json, about 1.1 MB together;
  - five committed files: evidence.tsv, custom-counts.json, ledger, claims.json, counting-systems.json.
- **Re-checked today:** all 14 hashes match the files on disk (MEASURED shasum).
- **In the data files:** manifest.json also lists the 7 runs/site input hashes, and the page's results.json carries 41 literature-cache hashes. All 41 match `runs/literature/cache` (MEASURED).
- **verification/evidence.tsv** cites commits, not file hashes. SiteEvidence needs the local `.git` (20 MB). 14 of the 17 cited commits are on no remote branch (MEASURED).

**Not cited, but promised in the plan.** Both states files are planned as release files (PLAN_PAGE.md:687-688, 977). The page cites montreal-80.states only by path, size and mtime (`data.rounds.*.src`, from FullIndexRun.java:392-394). Neither file's SHA-256 is recorded anywhere I searched. MEASURED now: ed9e9fb4… and a2f8a751….

**Bug:** SHA256SUMS names each file by its basename (SiteManifest.java:115, 121). The built file therefore has two lines called `results.ser` (c06c061's and step 8's). A Release cannot hold both, and `shasum -c` must fail one.

## What must be local, and what only has to exist somewhere

**Local, always:** runs/site/*.json, the repo (src, site, lib, .git) and the website's index.html. That is about 15 MB at peak.

**Local only while regenerating one input:**
- **SiteExport** (results-*.json) needs about 2.091 GB: the three `.ser` plus montreal-80.states plus small files (ESTIMATED from measured sizes).
  - It has to rerun whenever the template's guards change, because it writes resamples only for the keys those guards name (SiteExport.java:284-294, 379). Prose-only template edits need just SiteBuild.
  - It reads only the states file's header (StateStore.java:208-211), but the file's absolute path, size and mtime must match the run's identity (SiteExport.java:575-578).
- **KeyEdgesRun report:** about 1.50 GB.
- **CeilingResults data:** about 0.75 GB.
- **WideReport:** about 0.26 GB.
- **Adapters on JSON:** under 60 MB each.

**Only has to exist somewhere, for a reader to check a hash:** the three `.ser`, the key-edges json and, as planned, both states files. None of them can be regenerated to its hash:
- results.ser only to within 1e-12 (PLAN_PAGE.md:653-655);
- montreal-80.states only through three builds in order (PLAN_PAGE.md:703-709);
- the twin's build is unknown (PLAN_PAGE.md:1009-1011);
- steps 7 and 8 are the registration's one look, and a rerun adds a second ledger line (PLAN_COMPUTE.md:38-40).

So "somewhere" has to be a byte-exact archive, not a plan to recompute.

**Bit-identity:**
- Moving or deleting any of these changes no figure and no cited hash.
- Re-running an adapter from a restored copy keeps every value bit-identical. But each input records the path as given (SiteInput.java:45-52), so a copy restored to a new path changes runs/site/*.json, and with it the results.json and manifest.json hashes the page shows.
- So restore each file to the same path, and the states file with its mtime too.

Drive has 3.65 TiB free (MEASURED). None of the run files is there today. The Drive git bundles (Sept 9 and 15, 136 KB) are older than every cited commit except 077d971 and 552dc6c.

## Plan text a 2 GB cap would contradict

- **Tree store:** PLAN_COMPUTE.md:71-73 says keep it until 6.1 and 6.3 are done; both are done, but the planned item-1 re-export wants it. PLAN_COMPUTE.md:1136 and :1175, and INTEGRATION.md:762, say delete only after row 8 or row 10, and neither has run.
- **Hash first:** "trees.bin is hashed before it is ever deleted" (PLAN_PAGE.md:697, :1097). No hash is recorded.
- **Local copies:** "Local copies are kept until" each release file is uploaded and re-hashed (PLAN_PAGE.md:991-992), and the Release itself waits on Justin's yes (966-970). A cap before the Release means a verified Drive copy has to stand in for the local one, which changes that sentence and needs his approval.
- **Stay local by default:** the per-state key-edge arrays and the simulator's per-shoe records (PLAN_COMPUTE.md:1170-1174), and the full-detail CSV (PLAN_COMPUTE.md:953).
- **Checks before publishing:** the spot check and the depth-1 rescore (PLAN_PAGE.md 3.3-3.4) need states + results.ser, 1.77 GB, on local disk.
- **The 6.38 GB figure:** DiskBudget.java:26-32 and DISK_CLEANUP.md:7-8 call 6.38 GB "what the page is built and reproduced from". Measured, building takes 3.1 MB and reproducing takes at most about 2.09 GB at any one step.
- **Hash check at build time:** PLAN_COMPUTE.md:48-49 says the generator refuses an input whose hash differs from its manifest. SiteBuild does not implement this against files on disk. If it were implemented as written, every build would need 0.82 GB of artifacts locally.

Nothing in runs/, the repo or Drive was changed. My only writes were scratch files in `/private/tmp/sitearea-53838`.

**Peak working set.** Rebuilding the page from runs/site/*.json alone, MEASURED in scratch: inputs 3,112,143 B + 613255a src/site about 3.0 MB + mongo jar 2,314,903 B + website index.html 4,077 B + compiled classes about 3.2 MB (build-site.sh puts these in mktemp -d, outside runs/, build-site.sh:39-41) + output 3,179,185 B. That is about 15 MB on disk, 271 MB RSS, 3.8 s. Inside runs/ it is 3.1 MB of inputs plus 3.2 MB of output. The area's largest step is regenerating results-*.json with SiteExport (INTEGRATION.md:716-720): results.ser 430,694,877 + report-ceiling-97bf834.ser 202,956,286 + fullplay results.ser 113,431,766 + montreal-80.states 1,340,719,767 + report-final 841,034 + pilot 53,657 + template, catalog and counts about 0.12 MB + output 2,137,930 (written as .tmp, then renamed, SiteInput.java:176-178). That totals about 2,090,957,000 B, or 2.091 GB (ESTIMATED as the sum of MEASURED sizes): 91 MB over 2.0e9 and 56 MB under 2 GiB. The other regeneration steps, ESTIMATED the same way: KeyEdgesRun report about 1.50 GB, CeilingResults data about 0.75 GB, WideReport about 0.26 GB, LiteratureCheck about 0.21 GB, each adapter under 60 MB. A depth-1 reader rescore needs states + results.ser = 1.77 GB.

**Artifacts.**

- `runs/site/*.json (ceiling-, evidence-, key-edges-, ledger-, literature-, results-, spreads-7c1d40e.json)` (3,112,143 in total, MEASURED with stat. results-7c1d40e.json is 2,137,930, key-edges 472,563, spreads 313,323, literature 157,434, ceiling 22,843, evidence 6,513, ledger 1,537). Holds: SiteData's contract: _source (what, commit, fixture, artifacts[] with path/bytes/sha256), figures, grid, names, tables. results-*.json is most of the size: the grid of every row x scope x pen x spread, plus the boot resamples for every key the template's guards name Read by: SiteData.read src/SiteData.java:149-161, called from SiteBuild.main src/SiteBuild.java:1668. Written by SiteInput.write src/SiteInput.java:170-178 (SiteExport.java:735, SiteAdapt.java:176) Needed for: Every page build. They are the only thing SiteBuild reads from runs/ Regenerable: Yes, by the adapters (INTEGRATION.md:705-757), but only with each one's upstream data on disk. SiteExport's peak is about 2.09 GB (ESTIMATED from measured sizes); the JSON-only adapters take seconds Compressibility: MEASURED: `cd runs/site && tar cf - *.json | xz -T1 -6 | wc -c` gives 910,668 of 3,133,440 (0.29) Hash-cited: Yes. manifest.json inputs[] (SiteManifest.java:80-86) and results.json inputs[]; all 7 match MEASURED shasum. The page shows manifest.json's hash d672cb26…
- `runs/page-613255a/ (the built page, 48 files)` (3,179,185 bytes in files, MEASURED. index.html 213,108, data/results.json 1,581,573, results.csv 61,026, assets/views 1,348 KiB. Superseded builds page-79ff68b, page-7c1d40e-b and site-old-0a447de hold 9,304,969 together). Holds: index.html, data/{results.json, results.csv, manifest.json, SHA256SUMS, MANIFEST.md}, assets/page.js, assets/views/* Read by: Only SiteBuild --check, which compares a fresh build against it (src/SiteBuild.java:1677-1686) Needed for: The files that get copied into the website. Nothing reads them as an input Regenerable: MEASURED: the 613255a classes ran SiteBuild --check on scratch copies, under a sandbox-exec profile that denied reads of the whole CardCountingEdgeCalculator folder and the website repo. It printed 'every file matches a fresh build', exit 0, 3.83 s, 271 MB RSS. Needs --version draft --date 2026-10-17 --commit 613255a3222b… and Java 25.0.1+8-27 Compressibility: MEASURED: `tar cf - . | xz -T1 -6 | wc -c` gives 275,432 of 3,328,000 (0.083) Hash-cited: index.html shows manifest.json d672cb26…, results.json 34d63969…, results.csv e38baf5e… (SiteBuild.java:1410, 1416-1418)
- `runs/full-index/full-c06c061/results.ser` (430,694,877 (MEASURED)). Holds: Every published count's stored results for the main c06c061 run: SystemScorer entries, resamples and identity (Java serialisation) Read by: SiteExport.java:369 (CeilingReport.read). SiteSimReported.java:75 (--published). CeilingResults data, KeyEdgesRun report, RiskReport and SiteAdapt sim (INTEGRATION.md:707, 716, 726, 731, 742) Needed for: Re-exporting results-*.json, which has to happen whenever the template's guard keys change (SiteExport.java:284-294, 379), plus the ceiling, key-edges, risk and sim inputs, and a reader's depth-1 rescore (PLAN_PAGE.md:757-778). Not needed by SiteBuild Regenerable: Not to its hash: only to within 1e-12 (PLAN_PAGE.md:653-655). Full play took 19.8 h plus a new 14.9 GB tree store (PLAN_PAGE.md:796-798) Compressibility: MEASURED: `head -c 200000000 F | gzip -6 | wc -c` gives 0.680. On the first 50 MB: gzip -6 0.651, `xz -T1 -6` 0.459, `zstd -19 -T1` 0.505 Hash-cited: Yes, 8ba1412b… in index.html, SHA256SUMS, MANIFEST.md and manifest.json (via results-7c1d40e.json _source). The SHA256SUMS line is named only 'results.ser', which collides with the next entry
- `runs/report-ceiling-97bf834.ser` (202,956,286 (MEASURED)). Holds: Step 7's save: the bound rows and the registered custom counts' results Read by: SiteExport.java:456 (readSaved). CeilingResults data, LiteratureCheck and RiskReport (INTEGRATION.md:708, 750-751, 731) Needed for: Re-exporting results-*.json, re-running ceiling/literature/risk, and hash verification Regenerable: Effectively no. Step 7 is the registration's one look; a rerun is about 3-4 h and adds a second ledger line (PLAN_COMPUTE.md:38-40). Whether a rerun would reproduce the hash is unknown Compressibility: MEASURED: gzip -6 on the first 200 MB 0.551. On the first 50 MB: gzip 0.544, xz -6 0.345, zstd -19 0.377 Hash-cited: Yes, c854767f… (page, SHA256SUMS, manifest; also inside ceiling-7c1d40e.json and literature-7c1d40e.json)
- `runs/custom-count/fullplay-main-97bf834/results.ser` (430,694,877 is the main run's; this one is 113,431,766 (MEASURED). Its folder holds 260,628 KiB with fit.ckpt and the parts). Holds: Step 8: the registered counts at full play on the main file Read by: SiteExport.java:476-480. CeilingResults data and RiskReport (INTEGRATION.md:709, 731) Needed for: Re-exporting results-*.json, re-running ceiling/risk, and hash verification Regenerable: Effectively no, for the same one-look reason as step 7 (PLAN_COMPUTE.md:24-28) Compressibility: MEASURED: gzip -6 on the whole file 0.540. On the first 50 MB: xz -6 0.388, zstd -19 0.426 Hash-cited: Yes, 6a456558…. Its SHA256SUMS line is also named 'results.ser' (SiteManifest.java:115, 121)
- `runs/key-edges/key-edges-7c1d40ee5a8db29440dcc053b4a06ff792058600.json` (56,333,649 (MEASURED). The csv beside it is 21,271,971. A same-size 0a447de copy differs at byte 268). Holds: KeyEdgesReport's full per-key edge tables Read by: SiteEdges.java:80 (SiteAdapt edges, INTEGRATION.md:728) Needed for: Re-making runs/site/key-edges-*.json, and hash verification Regenerable: Yes, by KeyEdgesRun report from a-*.ser (221 MB), a-prime (66 MB), results.ser and run-b.ser (705 MB), INTEGRATION.md:725-727. Peak about 1.50 GB (ESTIMATED) Compressibility: MEASURED: gzip -6 on the whole file 0.172. xz -6 on the first 50 MB 0.078, zstd -19 0.076 Hash-cited: Yes, 824f0769… on the page
- `runs/wide/spreads-7c1d40e.json` (8,402,452 (MEASURED)). Holds: WideReport's spreads and wonging tables. It cites the hash of wide.ser (ee3ff121…) and of pilot-1417-100-wide-304901c.txt Read by: SiteWide.java:73 (SiteAdapt wide, INTEGRATION.md:724) Needed for: Re-making runs/site/spreads-*.json Regenerable: Yes, by WideReport from replay wide.ser (249,769,420). Peak about 0.26 GB (ESTIMATED) Compressibility: Not measured (small) Hash-cited: Yes, b86622c4… on the page
- `small cited text in runs/: report-final-26f0724.md, report-ceiling-97bf834.md, ceiling/ceiling-7c1d40e.json, full-index/pilot-1417-100.txt` (841,034; 132,766; 66,496; 53,657 (MEASURED)). Holds: The printed final report, step 7's report, the CeilingResults data file, and the widening pilot Read by: SiteExport.java:372-374, 713, 731-733. SiteCeiling.java:89. LiteratureCheck (INTEGRATION.md:749-751) Needed for: Re-exporting and adapting inputs, and hash verification Regenerable: report-final: by runs/final-report.sh, recorded, hours. The others: by their runs (ESTIMATED minutes to hours) Compressibility: Not measured; about 1.1 MB in total Hash-cited: Yes: 718a8e23…, 18763003…, d9964301…, 0018be90…
- `committed cited files (worktree paths): verification/evidence.tsv, custom-counts.json, custom-count-ledger.tsv (count-design), literature/claims.json (site-int), counting-systems.json` (6,383; 9,693; 922; 97,855; 68,040 (MEASURED)). Holds: The evidence table, the registration, the ledger, the literature claims and the catalog Read by: SiteEvidence.java:227-237, SiteCeiling.java:138, SiteLedger.java:35-58, LiteratureCheck, SiteExport.java:715 Needed for: Adapters, and hash verification. All of them are in git Regenerable: Yes, from git (.git is 20 MB). 14 of the 17 commits checked are on no remote branch (MEASURED git branch -r --contains) Compressibility: Not applicable (KB) Hash-cited: Yes: fa8771cb…, 87e56e7e…, 8dfb83d1…, da7b74fe…, d63b01b9…, all matching today
- `runs/literature/cache/ (47 fetched third-party pages)` (8,384,809 (MEASURED)). Holds: Cached source pages and PDFs that LiteratureCheck extracts figures from Read by: LiteratureCheck --cache (INTEGRATION.md:749) Needed for: Re-running LiteratureCheck, and Justin's own check of each cited cache hash. These are third-party copyrighted pages, so not for a Release Regenerable: No. Pages change on re-fetch Compressibility: Not measured (8 MB) Hash-cited: Yes, in the page's results.json tables.literature_sources: 41 cache_sha256, all 41 matched to cache files (MEASURED)
- `runs/montreal-80.states` (1,340,719,767 (MEASURED), mtime 1790724357 (Sep 29 19:25:57)). Holds: The main file: 60,000 shoes, 60,743 valued states Read by: SiteExport.java:575-580: the header (StateStore.java:208-211), plus its path/size/mtime identity (FullIndexRun.java:392-394), which has to match the run's identity Needed for: The data.rounds.* figures (template line 83 prints data.rounds.80) on any re-export; the spot check and depth 1-2 (PLAN_PAGE.md 3.3-3.4); every scoring run Regenerable: Not to its hash with one build. Only three builds in order reproduce it, about 46,953 s on 8 threads (PLAN_PAGE.md:703-709, 799-803) Compressibility: MEASURED on the first 50 MB: gzip -6 0.580, xz -T1 -6 0.507 Hash-cited: No. The page cites it only by path, bytes and mtime (results.json data.rounds.*.src). SHA-256 MEASURED now as ed9e9fb45de18a25d37572d720aa8cfb144e4d6dee9ac695bdba561141016739, found in none of the files searched. Planned 'release file' (PLAN_PAGE.md:687, 977)
- `runs/twin-80.states` (1,341,183,279 (MEASURED), mtime 1791371871). Holds: The search file (seed 20261007) Read by: BoundRows, CeilingResults optimism and SplitMargin (INTEGRATION.md:485-487, 681-683) Needed for: Search-file figures (data-file=search) and the check that the searched count was chosen on other shoes (PLAN_PAGE.md:1007-1011) Regenerable: 3.2 h, but its build is unknown, so it may not match its hash (PLAN_PAGE.md:1009-1011) Compressibility: Not measured. ESTIMATED like the main file, xz about 0.5 Hash-cited: No. SHA-256 MEASURED now as a2f8a751e8c7ed30ee72e1202e2de805f2904dad6320733bd16243c6a9680d26. Release default yes (RESULTS_SITE.md:47-50)
- `runs/sim/export-part1-0e0920d/` (about 53.4 MB (du 53,396 KiB, MEASURED). states.bin 35,973,461). Holds: Simulator part 1's bundle Read by: sim.Sim p2/p3 and SiteAdapt sim (INTEGRATION.md:565-570, 742) Needed for: The page's simulator section, which is not built yet (no runs/site/sim-*.json) Regenerable: Yes, by StrategyExport (minutes, ESTIMATED), but the identity table pins its hash Compressibility: Not measured Hash-cited: Yes, in committed SIM_CHECK.md:26: manifest.sha256 4a424be6… (matches, MEASURED)
- `runs/replay/c06c061-0e0920d/wide.ser and runs/replay/c06c061-0e0920d-sink/run-b.ser` (249,769,420 and 704,599,811 (MEASURED)). Holds: The replay's wide-plan results and the full-play sink Read by: WideReport, RiskReport and KeyEdgesRun report (INTEGRATION.md:722, 731, 727) Needed for: Re-making spreads, risk (not yet built) and key-edges Regenerable: About 10-11 h replay (PLAN_COMPUTE.md 7.2 row 6). Hash reproducibility not established Compressibility: Not measured here. ESTIMATED 0.35-0.46 with xz, like the other .ser Hash-cited: wide.ser's hash ee3ff121… is cited inside spreads-7c1d40e.json, which the page cites. run-b.ser is not cited
- `runs/full-index/full-c06c061/trees.bin` (14,877,661,504 (disk/2026-10-18-trees.tsv)). Holds: The tree store (speed only) Read by: Replays and PageLook via --trees-read-only (INTEGRATION.md:589, 608) Needed for: No page figure Regenerable: Bit for bit (TreeStoreTest); round 0 took 9,096 s (PLAN_COMPUTE.md:1175-1176) Compressibility: Not measured here (other area) Hash-cited: No, but PLAN_PAGE.md:697 says it is 'hashed before it is ever deleted', and the manifest plan lists it as 'hash only' (PLAN_PAGE.md:691, 982). No hash is recorded (disk/*.tsv lists sizes only)

**Ideas.**

- Keep locally only runs/site/*.json (3.1 MB), the latest page build (3.2 MB), the small cited text files (about 1.1 MB), spreads json (8.4 MB), the literature cache (8.4 MB) and the sim bundle (53 MB), about 0.08 GB in all. The page then rebuilds bit-identically (MEASURED in a sandbox with runs/ unreadable). Cost: none. Every cited hash is unchanged.
- Archive the large cited files to Drive byte for byte: the three .ser (0.75 GB), key-edges json (56 MB), both states files (2.68 GB), wide.ser and run-b.ser (0.95 GB). Check each by SHA-256 and by `rclone md5sum`, and keep the modtimes (Drive keeps them; `rclone lsl` shows ns times). Drive has 3.65 TiB free (MEASURED rclone about). Bring a file back only for the one step that needs it. Cost: about 4.4 GB uploaded once, then each step downloads 0.3-2.1 GB. Figures and hashes stay bit-identical if each file comes back to the same absolute path.
- Restore to the same absolute paths, and montreal-80.states with its mtime. Each input records the path as given (SiteInput.java:45-52) and figures carry it as src, so a re-export from another path keeps every figure value bit-identical but changes runs/site/*.json, which changes the page's results.json and manifest.json hashes. FullIndexRun.stateFile (FullIndexRun.java:392-394) also refuses a states file whose mtime differs (SiteExport.java:576-578).
- Code change, only if a strict 2.0e9 cap must hold while SiteExport runs: let SiteExport take the states header and the recorded identity instead of the 1.34 GB file. data.rounds.* comes from re-dealing shoes from the header's seed (SiteExport.java:575-593), so values stay bit-identical. results-*.json stays bit-identical only if the src string (path, bytes, mtime) is written unchanged. SiteExport's peak drops to about 0.75 GB. Cost: code, a test, and a weaker 'scored on this file' check.
- Before anything leaves the disk, record the SHA-256s in a committed file: montreal-80.states ed9e9fb4… and twin a2f8a751… (both MEASURED today, recorded nowhere found), and trees.bin, as PLAN_PAGE.md:697 requires. ESTIMATED 2-4 min to hash at the 70-130 MB/s measured here. Changes no figure and no cited hash.
- Fix SHA256SUMS naming before any Release (SiteManifest.java:115, 121 use the basename, so two lines are named results.ser). A GitHub Release cannot hold two assets of one name, and `shasum -c` fails one of them. Naming by a unique path-derived name changes only SHA256SUMS and MANIFEST.md. manifest.json is built independently of that (SiteManifest.java:58-106), so its hash and index.html are unchanged (ESTIMATED from code).
- Publish the Release first (PLAN_PAGE.md section 5, Justin's yes on the push list). After upload and a re-hash, PLAN_PAGE.md:991-992 allows the local copies to go. That is about 1.8 GB without the twin, 3.1 GB with it. Add runs/site/*.json (3.1 MB, about 0.9 MB as xz) to the asset list, because depth 1's `SiteBuild --check` needs them and the plan's list leaves them out. Hashes are unchanged.
- Optionally keep xz copies at rest locally instead of the raw cited .ser files: about 0.75 GB down to about 0.31 GB (ESTIMATED from MEASURED xz -6 ratios 0.459, 0.345, 0.388 on 50 MB samples), and decompress to the exact path when a step needs one. Cost: roughly 5-10 min of single-thread xz to compress (ESTIMATED). Hashes after decompression are identical.
- Delete the superseded page builds page-79ff68b, page-7c1d40e-b and site-old-0a447de (9.3 MB, MEASURED). This needs Justin's yes, as DISK_CLEANUP.md requires. No effect on any figure or hash.

## Investigator: The state files (runs/montreal-80.states, runs/twin-80.states): record layout, consumers, compressibility, re-encoding, regeneration cost, whether the twin is still needed

**Answer for this area.** The two state files take 2.68 GB, more than a 2 GB cap on their own. Almost all of that is stored values. The dealt cards are 0.24% of each record, and dealing them again from the seed saves almost nothing. Two moves get the area well under 2 GB while every published figure stays bit-identical:

- **Twin off the machine.** Keep it byte for byte on Drive (its hash is cited) and bring it back only when needed. Saves 1.34 GB.
- **Main file re-encoded losslessly.** About 0.61 GB instead of 1.34 GB (ESTIMATED from measured ratios).

Both need code or Justin's yes, and nothing should touch the main file while the replay that reads it (pid 48286) is running.

**Record layout (StateStore.java:34-35, 53-58, 175-181; MEASURED with `states_probe.py meta`):**
- Header: 271 B. Then 60,743 records (main) / 60,764 (twin) of 22,072 B each, nothing left over.
- Per record: shoe, round and depth 12 B; q 8 B; dealt as 26 shorts, 52 B; 2,750 doubles, 22,000 B (99.67%).
- 1,030 of those slots are NaN in every record because the rules make the move illegal: naturals, Surrender, Split on non-pairs (StateValuer.java:62-80). That is 37.3% of the file.
- Checks on the full file: q recomputes bit for bit from dealt in every record of both files; depth equals the sum of dealt; records are in shoe order; at most 16 cards of a kind dealt.
- On a stride-20 sample: no value in a rule-NaN slot, and every NaN is canonical. Legal slots are also NaN for impossible deals (76 of 3,038 records).

**Who uses what.** Every reader goes through StateStore (StateStore.java:208-294).
- ScoringStates uses every field: q as weight, dealt for counts, every legal value turned into move[] (ScoringStates.java:94-120, 189-237).
- AllStates.replay deals every shoe again and checks shoe, round, depth, q and dealt (AllStates.java:38-76, 140-142).
- SplitMargin, PlayerTreeStoredCheck and Calibration read single records at offsets, so a new format needs random access by block.
- SiteExport and CustomCounts read the header only.
- **The catch:** FullIndexRun.stateFile names the file by path, size and mtime (FullIndexRun.java:392-395). The c06c061 replay (FullIndexReplay.java:539-545), SiteExport (:575-578) and PageLook (:212-213) all check it. That string also sits 3 times inside runs/site/results-7c1d40e.json. Any change to the file's bytes, size, mtime or path breaks those runs and changes that JSON unless the code keeps reporting the old identity.

**Compressibility of the main file** (MEASURED on its first 200,000,000 B, which is all of segment 1 plus part of segment 2):

| Encoding | gzip -6 | xz -6 | zstd -3 | zstd -19 |
|---|---|---|---|---|
| Raw, as stored | 0.580 | 0.508 | 0.572 | 0.543 |
| Legal slots only, byte-plane shuffled, 72-byte prefix kept | 0.4495 | 0.4382 | 0.4487 | 0.4415 |

- The twin gives the same ratios (gzip 0.580 raw, 89,872,593 B shuffled).
- Decoding the shuffled form gave the same SHA-256 as the original on every sampled record, for both files (scripts/roundtrip.py).
- Blocks of 64 records cost almost nothing (gzip 90,671,880 vs 89,894,539 at 1024).
- The JDK's built-in Deflater should match gzip, so no new jar is needed (ESTIMATED).
- `ditto --hfsCompression` (APFS compression the filesystem undoes on read) stores 0.659 physical on a 22 MB sample. Bytes, size and mtime came back identical, and Java saw no difference (MEASURED).

**Dealing again vs valuing.**
- Dealing 1,417 shoes again took 0.1 s (MEASURED, pilot-1417-100-wide-304901c.txt), so about 4 s for 60,000 (ESTIMATED).
- Valuing the twin took 11,637 s on 7 threads (MEASURED, twin-80.log).
- The main file's three builds took 26,065 + 17,238 + 3,650 s, about 13.0 h on 8 threads (MEASURED, montreal-80.log).
- So valuing costs about 3,000 times more. Since the values are what is stored, there is nothing cheap to drop.
- Regenerating does not reproduce the files exactly. The segment-1 engine sums in a different order and agrees only to 1e-12 (PLAN_PAGE.md:706-709). The twin's build is unknown (:710-711). The spot check that would settle this does not exist yet. The tree path recomputes Hit and Stand values only to within 7.8e-16 (verification/evidence.tsv row 'tree').

**Is the twin still needed?**
- No main-file figure depends on it.
- The search (search-0b85be2), SplitMargin (row 9) and the twin preview are done.
- It is still needed for three things:
  1. CeilingResults optimism, rerun on each page build (three times 13-17 Oct). Its SHA-256 a2f8a751… is in optimism-*.properties, and that file is cited up through runs/ceiling/ceiling-7c1d40e.json to runs/site/ceiling-7c1d40e.json.
  2. The planned StateSpotCheck to establish the twin's build.
  3. Publishing it, Justin's question 1 (default yes), which needs these exact bytes.
- So keep it exactly, but it does not have to be local.

**Hashes, MEASURED today:** main ed9e9fb4… (cited nowhere yet, so record it before any change); twin a2f8a751… (matches the cited value).

**Disk and DiskBudget caveats.**
- DiskBudget counts logical size, so APFS compression would not show in the budget.
- The internal disk is at 99%, with 4.1 GB free.
- I left two scratch samples in /private/tmp/states-area-agent (fsc-sample.bin 22 MB, fsc-sample.ditto 14 MB), plus scripts in its scripts/ folder.

**Peak working set.** Every StateStore reader reads the file in place: positional reads or one buffered stream, records into RAM, no disk temporaries (StateStore.java:230-294). A run on the main file needs its 1,340,719,767 B, plus that run's own outputs (other areas). Optimism needs twin 1,341,183,279 + results.ser 430,694,877 + report-ceiling-97bf834.ser 202,956,286 + custom-counts.json 9,693 + about 20 KB of output = 1.975 GB, and only fits under 2 GB if the main file is not on disk at the same time (MEASURED sizes; inputs per INTEGRATION.md:487-489). StateRun writing a fresh 60,000-shoe file ends at 1.341 GB plus two 17 B progress files; DiskBudget reserves 25,000 B per shoe = 1.5 GB (StateRun.java:26, 69). After re-encoding (idea 2): 0.840 GB for the legal slots uncompressed (exact arithmetic, 13,832 B x 60,743), or about 0.61 GB compressed (ESTIMATED). The conversion itself peaks at input + output, about 1.95 GB, unless the v2 input is streamed from Drive with rclone cat; then only the output's 0.61 GB is local. The ditto conversion peaks at about 1.341 + 0.88 = 2.22 GB per file (ESTIMATED). Minimum local footprint for this area: main v2 only with the twin off-machine, 1.341 GB; main re-encoded only, about 0.61 GB.

**Artifacts.**

- `runs/montreal-80.states` (1,340,719,767 (MEASURED, stat)). Holds: Header of 271 bytes: magic, version 2, seed 20260928, q0 0.008, u0 0.02, cut 332, 550 deals, 5 moves, and 219 bytes of rules text (StateStore.java:53-58). Then 60,743 records of 22,072 bytes each, remainder 0 (MEASURED, states_probe.py meta). A record holds shoe, round and depth (3 ints, 12 B), q (8 B), dealt as 26 shorts (52 B) and 2,750 doubles (22,000 B = 99.67%) (StateStore.java:34-35, 175-181). Of the 2,750 values, 1,030 slots are NaN by rule in every record: naturals, Surrender (Montreal 'surrender false'), Split on non-pairs (StateValuer.java:62-80). That is 8,240 B, 37.3% of the file. The other 1,720 legal slots hold 13,760 B (62.3%). Legal slots are also NaN when a deal is impossible: 1,704 values in 76 of 3,038 sampled records. Every NaN is the canonical 0x7ff8000000000000 (MEASURED, stride-20 sample). The values are what dominates the size. The header plus the 17-byte progress file are about 2e-7 of it. Read by: FullIndexPilot.load (FullIndexPilot.java:648-672) -> ScoringStates (ScoringStates.java:94-120, 189-237). It uses every field: q as weight 1/q, dealt kept for counts, every legal value turned into move[], and the NaN pattern checked at :218. Callers include FullIndexRun (FullIndexRun.java:252), FullIndexReplay, PageLook (PageLook.java:295), CeilingReport and SearchReport. StateStore.readAll: ComparisonReport.java:204, KeyEdgesRun.java:153. StateStore.read at offsets: Calibration.java:1590, SplitMargin.java:279, PlayerTreeStoredCheck.java:104, FullIndexTimingReport.java:84. Header only: SiteExport.java:580-611 (deals all shoes again), CustomCounts.java:391-392, AllStates.replay (AllStates.java:38-76, which deals again and checks shoe, round, depth, q and dealt of every record at :140-142). Identity string from FullIndexRun.stateFile (FullIndexRun.java:392-395: path, size, mtime) is checked by FullIndexReplay.java:539-545, SiteExport.java:575-578 and PageLook.java:212-213, 236-239. Being read right now by the running replay (pid 48286, --states montreal-80.states). Needed for: Every main-file figure: the c06c061 replay and item-1 re-export, the page look, key edges, the ceiling report, SiteExport's rounds dealt. Also the Release file (PLAN_PAGE.md:687). Moving it, re-encoding it or touching it changes path, size or mtime. The replay of c06c061, SiteExport and PageLook then refuse to run, and the text 'states ..., 1340719767 bytes, modified 1790724357600' inside runs/site/results-7c1d40e.json would change. Regenerable: Only partly. Regenerating with the three frozen builds took 26,065 s + 17,238 s + 3,650 s, about 13.0 h on 8 threads (MEASURED, montreal-80.log lines 271, 1607, last). Bit-identity is unproven: d3c0b1f's engine sums in a different order, agreeing to 1e-12 (PLAN_PAGE.md:706-709). StateSpotCheck does not exist yet. Regenerating with one build will not reproduce the hash. Compressibility: MEASURED on the first 200,000,000 bytes (9,061 records, shoes 0-8939). Raw: gzip -6 115,912,517 (0.580); xz -T1 -6 101,550,320 (0.508); zstd -3 114,374,952 (0.572); zstd -19 108,593,757 (0.543). Command: head -c 200000000 F | nice -n 15 <compressor> | wc -c. Legal slots only, byte-plane shuffled in 1024-record blocks, 72-byte prefix kept (125,331,752 B): gzip -6 89,894,539; zstd -3 89,744,870; zstd -19 88,300,068; xz -6 87,640,420. That is 0.438-0.450 of the original. 64-record blocks: gzip -6 90,671,880. Command: sh scripts/shuf_fast.sh F 9061 shuf. The round trip gives the same SHA-256 (56f8fa5d...) on all 9,061 records (scripts/roundtrip.py). APFS transparent compression (ditto --hfsCompression) on a 22,072,271 B sample: 14,208 KiB vs 21,556 KiB physical (0.659). The bytes, size and nanosecond mtime came out identical (cmp, stat). Hash-cited: SHA-256 ed9e9fb45de18a25d37572d720aa8cfb144e4d6dee9ac695bdba561141016739 (MEASURED, openssl dgst). It is cited nowhere I found (grep of runs/ and the worktree), because the manifest is still pending. What the page cites is its identity string (path, 1340719767 bytes, mtime 1790724357600). That string appears 3 times inside runs/site/results-7c1d40e.json, whose SHA-256 the page and manifest cite.
- `runs/twin-80.states` (1,341,183,279 (MEASURED)). Holds: Same layout as the main file: seed 20261007, 271-byte header, 60,764 records x 22,072 B, from 60,000 shoes. q recomputes bit for bit and depth equals the sum of dealt in every record (MEASURED). 1,030 always-NaN slots per record. 1,424 legal-slot NaNs in 84 of 3,039 sampled records. Read by: CeilingResults optimism (CeilingResults.java:1385-1401, CustomCounts.requireTwin). BoundRows twin preview and SplitMargin (INTEGRATION.md:485-489, 676-685). CountSearch/Calibration for the search, which is finished (search-0b85be2, 8 Oct). Needed for: (1) Re-running optimism on each new page build. It ran 3 times between 13 and 17 Oct (runs/ceiling/optimism-{304901c,0a447de,7c1d40e}); TWIN_GAIN comes from it (CeilingResults.java:1212). (2) StateSpotCheck on the twin to establish which build made it (PLAN_PAGE.md:755). That class is not written yet. (3) Publishing it, Justin's question 1, default yes (PLAN_PAGE.md:1008-1012), which needs these exact bytes. No main-file figure needs it. SplitMargin (row 9) and the twin preview are done (split-margin-304901c*.tsv, bounds-twin-preview-304901c.txt). Regenerable: 11,637 s on 7 threads, about 3.2 h (MEASURED, twin-80.log last line). The build that made it is recorded nowhere, so a regenerated copy may not match its hash (PLAN_PAGE.md:710-711). Compressibility: MEASURED on the first 200,000,000 bytes. Raw: gzip -6 115,915,642 (0.580); zstd -3 114,612,374. Legal slots byte-plane shuffled: gzip -6 89,872,593; zstd -3 89,717,268. The round trip gives the same SHA-256 (93269e22...). Hash-cited: Yes. a2f8a751e8c7ed30ee72e1202e2de805f2904dad6320733bd16243c6a9680d26, which matches the file today (MEASURED). It sits in the header of runs/ceiling/optimism-*.properties. optimism-7c1d40e.properties (sha f4562fa0...) is cited by runs/ceiling/ceiling-7c1d40e.json, and runs/site/ceiling-7c1d40e.json's _source cites that file (sha d99643018e...).
- `runs/montreal-80.states.progress, runs/twin-80.states.progress` (17 each (MEASURED)). Holds: '60000 1340719767' and '60000 1341183279': shoes done and the bytes vouched for Read by: StateStore.records/shoesDone (StateStore.java:214-223). Every reader goes through them. Needed for: Every read: without it, records() and shoesDone() throw Regenerable: Yes, trivially (contents shown above) Compressibility: n/a Hash-cited: no
- `runs/montreal-80.log, runs/twin-80.log` (247,700 and 248,195 (MEASURED)). Holds: Progress lines every 25 shoes, the segment and build markers, 4 'slow' lines Read by: No code. Provenance only: which build wrote which segment (PLAN_PAGE.md:704-709) Needed for: The manifest's produced_by and the regeneration cost Regenerable: No (a historical record) Compressibility: ESTIMATED about 10x, being repetitive text; not measured Hash-cited: no
- `runs/build-d3c0b1f, runs/build-d791438, runs/build-26f0724` (504, 516, 712 KiB (MEASURED du -sk)). Holds: Frozen classes that wrote the main file's three segments Read by: Only a regeneration or the planned StateSpotCheck (PLAN_PAGE.md:752-753) Needed for: Reproducing each segment with the engine that made it Regenerable: Yes, by javac of git archive at the commit, 1-3 s, identical class files (PLAN_PAGE.md:699-702) Compressibility: negligible size Hash-cited: tree hash planned (PLAN_PAGE.md:678-680)

**Ideas.**

- 1. Twin off the machine, kept byte for byte. Hash it first (a2f8a751..., MEASURED, matching the cited value), upload with rclone, check with rclone md5sum, then remove the local copy. Restore with 'rclone copy' to the same path when optimism, the spot check or publication needs it. Drive has 3.650 TiB free (MEASURED, rclone about); a connector title search found no copy there today. Saves 1.341 GB. Figures and every cited hash stay bit-identical. Restoring with the same path and mtime keeps the identity string; Drive keeps mtime to the millisecond and stateFile uses toMillis (ESTIMATED, untested). Cost: about 1.34 GB downloaded per use. Needs Justin's yes. Moving it to the macOS Trash frees nothing until the Trash is emptied.
- 2. A lossless v3 StateStore format. Drop the 1,030 slots that are NaN by rule (StateValuer.legal), shuffle the 1,720 legal doubles by byte plane in blocks of 64-1024 records, and compress with the JDK's own Deflater, so no new jar is needed. Result: about 0.60-0.61 GB per file instead of 1.34 GB (ESTIMATED from a MEASURED gzip-6 ratio of 0.4495 on both files). Uncompressed legal-only is exactly 0.840 GB. The round trip measured the same SHA-256 on 9,061 records of each file. The code lives in StateStore.read/readAll/records (StateStore.java:208-294), plus a converter and a test that decodes the whole file to ed9e9fb4.... Figures stay bit-identical because the decoded Records are bit-identical. Catch: FullIndexRun.stateFile (FullIndexRun.java:392-395) must keep reporting the v2 path, size and mtime, stored in the v3 header. Otherwise the c06c061 replay, SiteExport and PageLook refuse to run, and results-*.json changes. Likewise CeilingResults.optimism must hash the decoded bytes, or the twin's cited hash would change. Publish the decoded v2 bytes as the Release file. StateRun can keep writing v2 and convert at the end.
- 3. APFS transparent compression with 'ditto --hfsCompression', no code at all. Physical size 0.659 of the original (MEASURED on a 22 MB sample), about 0.88 GB per file, saving about 0.46 GB each (ESTIMATED). Bytes, logical size and mtime are identical, and Java sees the same size and mtime (MEASURED), so every figure and hash is unchanged. But DiskBudget counts Files.size (DiskBudget.java:86-91), and Java's unix:* view has no block count (MEASURED), so the budget would not see the saving without du. A write (StateStore.open on a resume) undoes the compression. It needs a temporary copy and Justin's yes.
- 4. Not worth it: dealing the 72-byte prefix (shoe, round, depth, q, dealt) again from the seed instead of storing it. It saves 4.37 MB per file (0.33%). q recomputes bit for bit in all 121,507 records (MEASURED), and AllStates.replay already deals again and checks every record. Dealing 60,000 shoes again costs about 4 s (ESTIMATED from 0.1 s for 1,417 shoes, MEASURED in pilot-1417-100-wide-304901c.txt), against 11,637 s to value them. Storing dealt in 5 bits saves 2.1 MB; 4 bits cannot hold the measured maximum of 16 cards of a kind.
- 5. Rejected: regenerating instead of storing. That is 13.0 h for the main file and 3.2 h for the twin (MEASURED), bit-identity is unproven for both, and the cited hashes could no longer be checked. Also rejected: float32 values, which halve the values but change figures. Also rejected: moving the state files out of runs/ so DiskBudget stops counting them, which only hides the disk use and changes the path inside the identity strings.

## Investigator: Where a reproducibility archive could live off the laptop (Drive, GitHub Releases), and how runs would fetch, verify or stream what they need

## Answer

Yes, partly. With an off-laptop archive, runs/ can hold only what the current run needs. That is enough for the page rebuild (3 MB), a re-adapt of the results (about 0.82 GB), KeyEdgesReport (about 1.94 GB, borderline) and a main replay in batches (about 1.82 GB). It is not enough for a full rerun or a whole replay. Those need the 1.34 GB state file on disk next to their outputs: 2.4-4.3 GB. Getting them under 2 GB needs a code change: stream the state file. It is read once, in order, into RAM (FullIndexPilot.java:663-671).

## Where the archive can live

- **Google Drive.** `rclone about gdrive:` (MEASURED): 5 TiB total, 1.103 TiB used, 3.650 TiB free.
  - `rclone backend features gdrive:` (MEASURED) shows Drive keeps md5, sha1 and sha256 on its side, with 1 ms modtime precision.
  - MEASURED: Drive's sha256 for an existing 83 MB file (`rclone hashsum sha256`, 0e64f75c...) equals `rclone cat ... | shasum -a 256` of the downloaded bytes. So an archived results.ser can be checked against the hash the page cites without downloading it.
  - Google's docs say sha256 is filled in "if available". MD5 is always there for binary files, so record both.
  - Risk: rclone warns this remote uses its shared client_id, which "will stop working during 2026". Justin needs his own client_id.
  - Today: no runs/ data is on Drive. gdrive:backups/git/CardCountingEdgeCalculator holds two 136 KB git bundles. I listed only the top level and never inside the protected folders.
- **GitHub.** origin is git@github.com:jbondurant/CardCountingEdgeCalculator.git, and the repo is **public** (MEASURED via the API).
  - Release limits from GitHub's docs: each asset under 2 GiB, up to 1000 assets per release, no limit on total size or bandwidth. Every file here fits.
  - `gh` is not installed (MEASURED). Uploading means publishing, which needs Justin's yes.

## Transfer speeds and times

MEASURED speeds:
- Drive download: 4.68, 2.43 and 1.72 MB/s on single `rclone cat` streams; 1.53 MB/s in total over 4 parallel ranges, so the link was the bottleneck.
- `networkQuality -s` on Wi-Fi: 8.00 MB/s down, **2.42 MB/s up**, 87 ms round trip.

| File | Download | Upload |
|---|---|---|
| 1.34 GB state file | 4.8-14.6 min (2.8 min at link speed) | ≥9.2 min |
| 0.43 GB results.ser | 1.5-4.7 min | ≥3.0 min |

ESTIMATED from those speeds:
- Whole 6.39 GB working set, uploaded once: about 44 min or more.
- Checking a fetched 1.34 GB file's SHA-256: about 14 s (MEASURED: 747 MB hashed in 8.06 s).

## The scheme

1. **Manifest, committed.** One line per file: path under runs/, bytes, SHA-256, MD5, mtime in ms, remote path. A committed fetch tool reads it.
2. **Copy, never move.** `rclone copy ... --immutable`: a differing file already on Drive is an error, not an overwrite, which is safe_move's collision rule. Then `rclone hashsum sha256` and `rclone md5sum` must equal the local digest and, for cited files, the page's. MEASURED: local SHA-256s of results.ser, report-ceiling-97bf834.ser and the custom results.ser match the page's 8ba1412b..., c854767f... and 6a456558....
3. **Local removal only after Justin's yes**, listed file by file. The Trash still occupies the disk until he empties it (DISK_CLEANUP.md:42-45).
4. **Fetch.**
   - Call `DiskBudget.require` first.
   - Fetch to the **exact original path**, and check SHA-256 and mtime in ms, setting mtime from the manifest if needed. MEASURED: local mtime 1790724357600255056 ns, 1 ms on Drive, so the ms value survives the round trip.
   - Fetch the `.progress` sibling too (StateStore.java:214-217).
   - On a mismatch, refuse.

Why not `safe_move.py DEST SRC... [--hash]`: it needs DEST to be a directory (the Drive mount). Across volumes it copies, reads the copy back through that mount, and **unlinks the original once the copy checks out** (safe_move.py:103-125). That is a local deletion before Justin's yes, and a 1.34 GB read through a mount that stalls. AGENTS.md's own check for new files, `rclone md5sum gdrive:<path>`, is the right tool here.

## Do the figures stay identical?

- Verbatim archive and fetch: the same bytes, path and mtime give the same figures, the same identities and every cited SHA-256 unchanged.
- The path and mtime matter as much as the bytes. FullIndexRun.stateFile (FullIndexRun.java:392-394) puts path, size and mtime in ms into the run identities. runs/site/results-7c1d40e.json embeds "states .../runs/montreal-80.states, 1340719767 bytes, modified 1790724357600" four times.
- So a re-export from a copy at another path or mtime would leave the numbers alone but change that JSON's bytes, and with them the page manifest's hashes.

## What can stream instead of being held

- **State files: yes, with a code change.**
  - FullIndexPilot.load reads ascending positions (StateStore.java:230-264), and readAll is already an InputStream (StateStore.java:267-294).
  - But the code reopens the path for the header, progress and identity (FullIndexRun.java:232, 251, 276, 309; FullIndexReplay.java:527, 539, 565).
  - Calibration.java:1585-1595 and SplitMargin.java:276-283 read single records at scattered positions. They could become one sequential pass over the same indices.
  - Results stay bit-identical: same bytes, read with getDouble.
- **.ser/.ckpt files: read whole and in order, so streamable.** Readers: FullIndexCheckpoint.java:194, CeilingReport.java:99 and 513, WideReport.java:168, KeyEdges.java:332. But they are hashed by local path (SiteInput.java:44-51, KeyEdgesReport.java:420-431, FullIndexReplay.java:555), so fetching them beats streaming.
- **Tree store: no.** It is read and written at random positions by several threads (TreeStore.java:47-48, 285, 294). It is a cache that rebuilds bit for bit, so don't archive it.

## Compressibility

MEASURED on 100 MB samples, at nice 15:

| File | gzip -6 | zstd -3 | zstd -19 | xz -6 |
|---|---|---|---|---|
| state file | 0.580 | 0.573 | 0.545 | 0.508 |
| results.ser | 0.675 | 0.605 | 0.486 | 0.441 |
| sink run-b.ser | 0.653 | 0.580 | 0.483 | 0.441 |
| fit.ckpt | 0.568 | 0.556 | 0.533 | 0.482 |

Compression saves transfer time, not the 2 GB.

## Not counted by DiskBudget

.worktrees holds 0.497 GB (68 worktrees) and .git 21 MB (MEASURED du), both outside runs/.

Caveat: my speeds and hashes came from shell commands in a scratch directory, not repo code. Any figure that gets published should come from the committed fetch tool or from DiskBudget.

**Peak working set.** MEASURED sizes; output allowances are DiskBudget's constants (DiskBudget.java:56-58). Archiving costs 0 extra local bytes: rclone copy reads in place, and 'zstd -c | rclone rcat' streams because Drive supports PutStream (MEASURED: rclone backend features). Fetching one file costs that file's size and no more: rclone writes a .partial file and renames it, so there is no second copy. Hashing streams. Minimum local bytes per run, assuming only that run's inputs are fetched:
(1) SiteBuild page rebuild: runs/site 3.05 MB.
(2) Re-adapting the results: results.ser 0.431 + custom results.ser 0.113 + report-ceiling .ser 0.203 + small md/txt/json files (about 0.07) = about 0.82 GB. Fits.
(3) KeyEdgesReport: the four collectors 0.724 + sink 0.705 + results.ser 0.431 + about 0.08 of output = about 1.94 GB. Borderline, about 0.06 GB under the cap.
(4) Main replay as one run: state 1.341 + fit 0.184 + 93 fold parts 0.423 + 93 x 25 MB = 2.33 (or 1.88 measured) = about 4.3 GB. Does not fit.
(5) Main replay in --only batches of 10, parts fetched per batch: 1.341 + 0.184 + 0.045 + 0.25 = about 1.82 GB, with the wide and sink outputs archived between batches. Whether separate batches' wide.ser and run-b.ser can be combined is unverified (ReplayHooks.java:101 writes one per replay).
(6) FullIndexRun on the main file: state 1.341 + output 1.05 measured (DiskBudget allowance 31 x 3 x 2 x 10 MB = 1.86, FullIndexRun.java:278) = 2.39-3.20 GB. Does not fit unless the state file is streamed, in which case it needs 1.05-1.86 GB.
(7) StateRun making a state file: 1.34 GB.
Outside runs/, and not counted by DiskBudget (DiskBudget.java:16-23): .worktrees 0.497 GB (68 worktrees), .git 21 MB, lib 5 MB (MEASURED du -sk).

**Artifacts.**

- `runs/montreal-80.states` (1,340,719,767 (MEASURED ls -la)). Holds: A 271-byte header (magic CCSTATES, version 2, seed, q0, u0, cut, 550 deals, 5 moves, rules text; StateStore.java:53-58). Then 60,743 fixed records of 22,072 B each (StateStore.java:35, 175-181): shoe, round and depth as ints, q as a double, 26 shorts of cards dealt, and 2,750 doubles of first-move values. The values are 22,000 of each record's 22,072 B and dominate the size. Record count from (1,340,719,767-271)/22,072, which matches 'valued states 60743' in run.id. Read by: FullIndexPilot.load (FullIndexPilot.java:648-672: reads records in ascending order, 256 per call, all into RAM), called from FullIndexRun.java:252, FullIndexReplay.java:528, CeilingReport.java:404, CountRegression.java:508, SearchReport.java:820, StrategyExport.java:2775. StateStore.readAll, a plain InputStream (StateStore.java:267-294), from KeyEdgesRun.java:153, StrategyExport.java:2865, ComparisonReport.java:204. Only the header (271 B) at SiteExport.java:580, PageLook.java:373, ReplayHooks.java:54. Its path, size and mtime go into identities through FullIndexRun.stateFile (FullIndexRun.java:392-394). Needed for: Every rerun, replay, page look, key-edges run or re-export on the main file. Not needed to rebuild the page from runs/site/*.json. Regenerable: Yes in principle, with StateRun from the seed (StateStore.java:23-24). Time ESTIMATED at about 3 h: the twin file took 11,637 s on 7 threads (twin-80.log, last line), and montreal's last start took 3,650 s for shoes 40000-59999 on 8 threads (montreal-80.log). No test proves a regenerated file is bit-identical: StateStoreTest.java:39 and :91 cover only round trip and resume. A regenerated file would also get a new mtime, which changes every identity line that names it. Compressibility: MEASURED on 100 MB at offset 600 MB: dd if=runs/montreal-80.states bs=1000000 skip=600 count=100 | nice -n 15 <c> | wc -c. Ratios: gzip -6 0.580 (11 s), zstd -3 -T1 0.573 (2 s), zstd -19 -T1 0.545 (98 s), xz -T1 -6 0.508 (114 s). Hash-cited: Its SHA-256 is not cited. The file is cited by identity string instead: 'states /Users/.../runs/montreal-80.states, 1340719767 bytes, modified 1790724357600' appears 4 times as a src in runs/site/results-7c1d40e.json, a file whose own hash the page manifest records. The same string is in runs/full-index/full-c06c061/run.id and in the replay's run.id.
- `runs/twin-80.states` (1,341,183,279 (MEASURED ls -la)). Holds: Same format as montreal: a 271-byte header and 60,764 records of 22,072 B, almost all of it doubles of first-move values (StateStore.java:30-35). Read by: Through CustomCounts.requireTwin: SplitMargin.java:271-283, which reads single records at every o.every-th position. Also CeilingResults.java:1390 (FullIndexPilot.load) and Calibration.sample, which reads n single records spread evenly through the file (Calibration.java:1585-1595). Needed for: Custom-count search, split-margin and ceiling runs on the twin file. Regenerable: StateRun: 11,637 s on 7 threads (MEASURED from twin-80.log). Bit-identity of a regenerated file is unproven, and its mtime would change. Compressibility: ESTIMATED to match montreal-80.states (same record format), about 0.51-0.58. Not measured. Hash-cited: Not cited in runs/site/*.json (MEASURED: grep -c '.states' returns 0 for every site JSON except results-7c1d40e.json, which names montreal).
- `runs/full-index/full-c06c061/results.ser` (430,694,877 (MEASURED)). Holds: Java serialization (CeilingReport.java:97-105): an identity UTF string, then List<SystemScorer.Result> for 31 counts, then double[] perfect. Each Result carries entries, half-fit bootstrap arrays and fit summaries (SystemScorer.java:2417-2449). ESTIMATED that the bootstrap and fit double arrays dominate, given 1,000 resamples (run.id). Read by: CeilingReport.read (CeilingReport.java:97-105, a sequential ObjectInputStream). Hashed by path in SiteInput.artifact (SiteInput.java:44-51, from SiteExport.java:377) and in KeyEdgesReport.java:430-431. Needed for: Re-adapting the page's results, ceiling and key-edges JSON, and the ceiling report. Regenerable: Effectively no: FullIndexRun took 19.8 h on the wall in its last start (full-c06c061.log, last line). Compressibility: MEASURED on the first 100 MB (dd skip=0 count=100 | nice -n 15 <c> | wc -c): gzip -6 0.675, zstd -3 0.605, zstd -19 0.486, xz -6 0.441. Hash-cited: Yes. 8ba1412b64c8... appears in runs/site/results-7c1d40e.json, runs/ceiling/ceiling-7c1d40e.json and runs/key-edges/key-edges-7c1d40e...json. MEASURED: a local shasum -a 256 matches it.
- `runs/full-index/full-c06c061/fit.ckpt` (183,689,022 (MEASURED)). Holds: A FullIndexCheckpoint object: an identity UTF string and the fitted strategies (FullIndexCheckpoint.java:177-201). Read by: FullIndexReplay.java:517-519 (must exist), FullIndexReplay.java:555 (SHA-256 of the whole file) and FullIndexReplay.java:628 (src.readFit, a sequential ObjectInputStream, FullIndexCheckpoint.java:194). Needed for: Any replay of c06c061, including the item-1 re-export. Regenerable: Only by rerunning the 19.8 h main run. Compressibility: MEASURED on the first 100 MB: gzip -6 0.568, zstd -3 0.556, zstd -19 0.533, xz -6 0.482. Hash-cited: Not by the page. Its SHA-256 479595d13bce... is part of the replay identity (runs/replay/c06c061-0e0920d/run.id, line 3), so a changed byte would make every replay of c06c061 a different replay.
- `runs/full-index/full-c06c061/part-*.ckpt (186 files)` (431,014,069 in total (MEASURED stat sum): 93 *-folds.ckpt hold 422,561,932 and 93 *-halves.ckpt hold 8,452,137. The largest is 6,763,005.). Holds: One SystemScorer.Result per scoring item (FullIndexCheckpoint.java:146-158). Read by: FullIndexReplay.java:546-551 checks hasPart only for the items being replayed (an --only batch needs only its own parts). FullIndexReplay.java:762 reads one part per item, when that item is replayed. Needed for: Replays: each item is compared with its part to within 1e-12 (FullIndexReplay.java:763-767). Regenerable: Only by the 19.8 h main run. Compressibility: ESTIMATED at about 0.55-0.6, the same serializer as fit.ckpt. Not measured. Hash-cited: No.
- `runs/replay/c06c061-0e0920d-sink/run-b.ser` (704,599,811 (MEASURED)). Holds: A KeyEdges.Collector: facts, entries, handed arrays and refits (KeyEdges.java:331-340). Written by the replay's sink hook (ReplayHooks.java:128-133). Read by: KeyEdgesReport.java:406 (KeyEdges.Collector.read, a sequential ObjectInputStream), which hashes it by path (KeyEdgesReport.java:420-431). Needed for: Rebuilding the key-edges table (KeyEdgesRun report --full). Regenerable: Only by rerunning the main replay (hours, on six threads). Compressibility: MEASURED on the first 100 MB: gzip -6 0.653, zstd -3 0.580, zstd -19 0.483, xz -6 0.441. Hash-cited: Indirectly. Its SHA-256 3c1866b667ea... is inside runs/key-edges/key-edges-7c1d40e...json, and runs/site/key-edges-7c1d40e.json cites that file's hash (824f0769...).
- `runs/replay/c06c061-0e0920d/wide.ser` (249,769,420 (MEASURED)). Holds: WideReport.Saved: an identity, the wide plans and the rows (WideReport.java:166-175). Written at ReplayHooks.java:101. Read by: WideReport.read (WideReport.java:166-175, sequential). PageLook.java:311 reads its own replay's wide.ser. Needed for: The spreads table (runs/wide/spreads-*.json). Regenerable: Only by the main replay. Compressibility: Not measured. ESTIMATED at about 0.45-0.65, the same serializer as the files measured. Hash-cited: Not directly. The page cites the derived runs/wide/spreads-7c1d40e.json (b86622c4...).
- `runs/report-ceiling-97bf834.ser` (202,956,286 (MEASURED)). Holds: CeilingReport.Saved: an identity, custom rows, bounds, refits and checked lists (CeilingReport.java:511-520). Read by: CeilingReport.readSaved (CeilingReport.java:511-520, sequential). Hashed by SiteInput.artifact. Needed for: Re-adapting the ceiling and results sections. Regenerable: Only by the 97bf834 ceiling run (hours; not measured here). Compressibility: Not measured. Hash-cited: Yes. c854767f4d72... appears in runs/site/results-7c1d40e.json and literature-7c1d40e.json. MEASURED: a local shasum matches it.
- `runs/custom-count/fullplay-main-97bf834/results.ser` (113,431,766 (MEASURED)). Holds: FullIndexRun results for the custom counts, in the same layout as the main results.ser. Read by: SiteExport.java:480 (o.fullplay, hashed through SiteInput.artifact). Needed for: Re-adapting the page's full-play rows for the custom counts. Regenerable: Only by the custom-count run (the full fullplay-main-97bf834 folder is 0.267 GB). Compressibility: Not measured. Hash-cited: Yes. 6a456558649b... appears in runs/site/results-7c1d40e.json and the ceiling JSON. MEASURED: a local shasum matches it.
- `runs/key-edges/a-304901c...ser, a-prime-304901c...ser, runs/page-look-0e0920d/run-c.ser, runs/page-look-0e0920d/run-d/run-b.ser` (221,351,385 + 65,554,901 + 245,680,412 + 191,051,009 = 723,637,707 (MEASURED)). Holds: KeyEdges.Collector files (KeyEdges.java:331-340). Read by: KeyEdgesReport.java:403-409, sequential ObjectInputStream reads, each hashed by path at KeyEdgesReport.java:420-431. Needed for: Rebuilding the key-edges table. Regenerable: Only by rerunning KeyEdgesRun and PageLook. Compressibility: Not measured. Hash-cited: Indirectly. Their SHA-256s (561483938513..., c649f44fd3b8..., 2939a4ea52cd..., 3fe732cee593...) are inside the key-edges JSON that the page cites.
- `runs/full-index/full-c06c061/trees.bin (+ trees.bin.index)` (14,877,661,504 + 7,791 (MEASURED)). Holds: 60,743 fixed records of each state's player-tree stand values (TreeStore.java:13-24). Read by: TreeStore: positional reads and writes from several threads, random by state (TreeStore.java:47-48, 285, 294). Needed for: Nothing figure-bearing. It only saves rebuild time. Regenerable: Yes, bit for bit (TreeStore.java:22-24, TreeStoreTest). It costs about half an hour of wall time per round on six threads (DiskBudget.java:30-32). Compressibility: Not measured. Not worth archiving. Hash-cited: No.
- `runs/site/*.json (7 files)` (3,052 KB (MEASURED du -sk)). Holds: The adapted inputs the page is built from, each recording its sources' paths and SHA-256s. Read by: SiteBuild --inputs runs/site (SiteBuild.java:14-20, 1638). Needed for: Rebuilding the page bit for bit. This is the only runs/ input SiteBuild reads. Regenerable: By SiteAdapt, from the heavy files above, which must be at their original paths and mtimes. Compressibility: Irrelevant at this size. Hash-cited: Yes. The site manifest hashes each one (SiteBuild.java:1324, 1418; SiteManifest.java:74-83).

**Ideas.**

- Archive to Drive verbatim, mirroring paths relative to runs/ (proposed home gdrive:backups/data/ccec-runs/, Justin's call). Upload with 'rclone copy SRC gdrive:DEST --immutable', so a differing file already there is an error, not an overwrite: safe_move's collision rule. Prove each file with 'rclone hashsum sha256' and 'rclone md5sum' against the local digest and, for cited files, against the page's hash, with no download. Cost: about 44 min once for the 6.39 GB working set at 2.42 MB/s (ESTIMATED; lower bound). Figures, identity strings and every cited SHA-256 stay bit-identical, because the bytes are the same.
- Commit a manifest plus a fetch tool to the repo (rule: numbers from repo code). The manifest (e.g. archive/manifest.tsv) lists path, bytes, SHA-256, MD5, mtime in ms and remote path. The tool calls DiskBudget.require before fetching. It fetches to the exact original absolute path, because FullIndexRun.stateFile puts the path into identities (FullIndexRun.java:392-394). It checks SHA-256 and sets and checks mtime.toMillis against the manifest. It brings the .progress sibling of a state file (StateStore.java:130-132, 214-217). On a mismatch it refuses and removes the partial copy. Cost: a small Java class and a test. Bit-identical. This matters: results-7c1d40e.json embeds the state file's path, byte count and mtime as a source string, so a file fetched elsewhere would change that JSON's hash on a re-export even though the numbers would not move.
- Stream the state file instead of holding it. It is read once, in order, into RAM (FullIndexPilot.java:663-671), and StateStore.readAll is already a pure InputStream (StateStore.java:267-294). The source could be 'rclone cat' from Drive, or a local gzip copy (JDK GZIPInputStream, MEASURED ratio 0.580, about 0.78 GB). The code change: header, progress and the identity line come from the manifest instead of reopening the path (FullIndexRun.java:232, 251, 276, 309; FullIndexReplay.java:527, 539, 565), and SHA-256 is checked at end of stream before scoring starts. It saves 1.34 GB, which is what makes a main rerun or replay fit in 2 GB. Figures stay bit-identical (same bytes, read with getDouble). Identities stay the same only if the identity line is reproduced exactly from the manifest. Cost: about 5-15 min of download per run (MEASURED 1.53-4.68 MB/s), against 2.2 s from SSD (full-c06c061.log, line 2).
- Change the two random-access samplers of the twin file to one sequential pass that keeps the same indices: Calibration.sample (Calibration.java:1585-1595) and SplitMargin (SplitMargin.java:276-283). They could then stream too. Same records come back, so the results are bit-identical. Cost: a small change plus a test comparing against the positional reads.
- Replay in --only batches, fetching each batch's parts just in time. FullIndexReplay.java:546-551 requires parts only for the items being replayed, and each part is read when its item comes up (:762). Peak is about 1.82 GB with the state file local and about 0.48 GB if it is streamed. Archive each batch's wide/sink output before the next batch. Bit-identical per item. Unverified: whether the page's adapters accept per-batch wide.ser and run-b.ser files in place of one each.
- Optional public mirror on GitHub Releases. The repo is public (MEASURED: api.github.com reports private:false). Each asset must be under 2 GiB, a release holds up to 1000 assets, and there is no limit on total size or bandwidth (GitHub docs), so every file here fits; the largest is 1.34 GB. Readers could check the page's cited SHA-256s themselves. Cost: install and authenticate gh (MEASURED: not installed), about the same upload time, and Justin's yes, because it publishes. Bit-identical.
- Compress only the non-cited state files on the way to Drive: 'zstd -3 -c | rclone rcat', MEASURED ratio 0.573. Upload of a state file drops from about 9.2 to about 5.3 min (ESTIMATED). Zero local temp in both directions ('rclone cat | zstd -d >'). Drive's server-side SHA-256 then covers the .zst, so the manifest must also hold the original's SHA-256, checked after decompression. Keep page-cited files verbatim, so Drive's own hash equals the page's.
- Do not archive trees.bin (14.88 GB). It is rebuilt bit for bit (TreeStore.java:22-24), uploading it would take at least 1.7 h at 2.42 MB/s, and it could never fit a 2 GB cap anyway. Under the cap the store keeps about 0 trees, which costs about half an hour per round (DiskBudget.java:30-32).
- Account for space DiskBudget does not see. Files moved to the macOS Trash still occupy the disk until it is emptied (DISK_CLEANUP.md:42-45); a verified Drive copy is what makes emptying it safe, and Justin empties it himself. .worktrees holds 0.497 GB in 68 worktrees outside runs/; removing a worktree goes through Justin under the guard rules.
- Replace the rclone remote's shared client_id before relying on fetches. rclone prints a notice that this remote uses rclone's shared Google Drive client_id, which 'will stop working during 2026'. Without a fix the archive becomes unreachable through rclone. Creating his own client_id is an account action, so it needs Justin.

## Investigator: The replay and its hooks (WidePlans, Sink, StrategyExport.ReplayExport), and the key-edge runs (KeyEdgesRun a, a-prime, report), with the page look's files

## Replay, hooks and key-edge runs: what fits in 2 GB

**Answer for this area:**
- **Item-1 re-export** (15 items, export hook only, `--trees none`): peak about 1.78 GB (ESTIMATED from measured inputs). It fits if runs/ holds only its inputs.
- **KeyEdgesRun a / a-prime:** 1.99 GB and 1.84 GB, both from MEASURED file sizes. They fit, with almost no margin for `a`.
- **Full replay** (all 93 fold items, three hooks): about 3.92 GB. It cannot fit as written. Its inputs alone are 1.95 GB: states 1.34 + fit.ckpt 0.18 + 93 parts 0.42. Its outputs at the end are 1.98 GB more. Those outputs already exist, and nothing pending needs it rerun.

**What each file holds and who reads it next.** I checked this with a read-only inspector in /private/tmp, run on the frozen classes-0e0920d.
- **Collector files** (a, a-prime, run-c, main run-b, run-d run-b): 1,428 MB together. 1,029 MB of that (72%) is whole copies of `SystemScorer.Entry`: wideBoot, shoeSums, boot and wideKeys. The key-edge report never reads those fields; it reads only name, count, flags and estimate (KeyEdgesReport.java:268-312). The main run-b.ser is 559.0 of its 704.6 MB in such entries.
  - PLAN_COMPUTE.md:966-967 budgeted 150 to 200 MB for these per-state arrays.
  - All five are read only by `KeyEdgesRun report`, which already wrote key-edges-7c1d40e on 17 Oct.
- **wide.ser:** 94% wideBoot. WideReport has read it (4 s). RiskReport still will, after simulator part 2.
- **Unread files:** wide-first.ser and the page look's replay/wide.ser are read by no code and no INTEGRATION.md command.
- **Records:** the 93 records (0.93 GB) are Result 666 MB, sink arrays 242 MB and export 28 MB. They are needed only for a resume, which the item-1 fix's new classes rule out.

**A failure mode that wastes space.** When the export is refused at its end (StrategyExport.java:2771), FullIndexRun skips letGo (FullIndexRun.java:218-220). PageLook does the same: it exits before letGo (PageLook.java:532-535). That is why batch B's 1.16 GB of records exist even though wide.ser and run-b.ser were written.

**Hash chain** (MEASURED: all cited hashes match the files on disk now):
- The page's files table cites the SHA-256 of runs/wide/spreads-7c1d40e.json (b86622c4…) and of key-edges-7c1d40e.json (824f0769…), through runs/site/*.json `_source.artifacts`.
- Those two JSONs cite, by absolute path and SHA-256: wide.ser (ee3ff121…), a (56148393…), a-prime (c649f44f…), run-c (2939a4ea…), main run-b (3c1866b6…) and run-d run-b (3fe732ce…).
- No committed file cites these hashes (grep of the worktree).
- Two consequences:
  - Deleting or archiving these .ser files changes no page hash, but the chain can then only be checked against an archived copy.
  - Rewriting them, for example in a slimmer format, and rerunning the reports changes the JSON hashes and so the page's table. The figures would stay the same.
- Records, perfect.ser, replay.txt and the two unread wide files are cited nowhere.

**Determinism** (MEASURED with cmp):
- run-c.ser and wide-first.ser, rebuilt under classes 0a447de, differ from the 0e0920d first attempt only in bytes 29-67, which is the commit hash.
- The 0a447de and 7c1d40e key-edge JSONs differ in 39 bytes (the commit field), and their CSVs are identical.
- So regenerating gives the same figures. It gives the same SHA only with the same frozen classes, the same paths and the same state-file mtime, because the identities embed all three (FullIndexRun.java:392-395).

**Regeneration cost** (MEASURED from logs):
- KeyEdgesRun a 254 s, a-prime 612 s, report 294 s.
- Page look: run C 1,568 s; run D 11,915 s.
- Main replay: 14.90 h of batches with trees.bin read.
- PageLook cannot run without trees.bin (PageLook.java:179-181, 241-245), and it is made once per ledger.
- `--trees none` is far slower than reading trees. The check running now (2 items) was 70% through one last round after 5,304 s. The main replay's 2-item batches took 222 to 618 s with trees read. Machine load was 50 to 120 during my work.

**Compressibility** (MEASURED on samples of 200 MB or less, all at nice 15):

| File | gzip-6 | zstd-3 | zstd-19 | xz-6 |
|---|---|---|---|---|
| wide.ser | 0.668 | 0.571 | 0.536 | 0.490 |
| run-b.ser, first 200 MB | 0.680 | 0.597 | 0.503 | 0.458 |
| run-b.ser, last 144 MB | 0.625 | 0.510 | 0.422 | not run |
| a | 0.561 | 0.431 | 0.310 | 0.278 |
| a-prime | 0.680 | 0.637 | 0.482 | 0.435 |
| run-c | 0.413 | 0.345 | 0.258 | 0.234 |
| run-d | 0.520 | 0.458 | 0.382 | 0.349 |
| records (4, weighted) | 0.577 | 0.518 | 0.450 | 0.413 |
| key-edges JSON | 0.172 | 0.117 | 0.077 | 0.080 |

Byte-plane shuffling does not help wide.ser (xz 0.372 → 0.367).

**Item-1 re-export caveat.** With `--only`, the replay attaches the registered per-row wide plans for F43 to F48 only when no `--export-row` is given (FullIndexReplay.java:418-439). But `--only` requires `--export-row`, so a 15-item re-export would leave out P16, B0,8 and X-1.0,8 unless the code changes. Adding those plans also needs `--wide`, about +0.09 GB.

**Ways to shrink the footprint** (details in `ideas`):
1. Let go of records on a refused export, and compress records. Figures bit-identical, no hash changes.
2. Write slim collectors: about 1.0 GB saved. Figures should be bit-identical (needs a test), but the hashes in the chain change.
3. Archive the 1.57 GB of collectors and unread wide files that are done with to Drive, byte for byte (3.65 TiB free). Do the same for wide.ser after RiskReport.
4. Rerun a, a-prime and the report on demand.

**Disk and memory right now.** The internal disk had 3.9 GiB free (df), and swap on the same SSD held 2.17 GB (vm.swapusage). A strict 2 GB cap would also have to cover JVM swap.

Everything I wrote is under /private/tmp/ccec-disk-replay-56972 (scripts, compress.tsv, Inspect.java, Sum.java). Nothing in the repo, the worktrees, runs/ or Drive was changed.

**Peak working set.** MEASURED byte sizes from ls/stat. ESTIMATED where marked.

(1) Replay of all 93 fold items with the wide, sink and export hooks, --trees none:
- Inputs read during the run: runs/montreal-80.states 1,340,719,767 (loaded whole, FullIndexReplay.java:528) and its .progress (17); full-c06c061/fit.ckpt 183,689,022 (:628); the 93 part-*-folds.ckpt, 422,561,932 (each read at its item, :762); run.id 1,178; classes 3.3 MB; mongo jar 2.3 MB. Total about 1.953 GB.
- Outputs at the peak, after the export hook's finish and before letGo (FullIndexRun.java:218-220): records 928,881,429 (about +26 MB more, ESTIMATED, once the 9 refused rows export); wide.ser 249,769,420; run-b.ser 704,599,811; export bundle about 60 MB (ESTIMATED from gate0's 1-row bundle at 15.9 MB, export-first at 23.0 MB, and part 1's 53.4 MB); perfect.ser, replay.txt and run.id about 1 MB; at most one record .tmp in flight (20 MB or less).
- Peak: about 3.92 GB. With --trees-read-only it is 18.8 GB (adding trees.bin 14,877,661,504 and its index 7,791). After letGo it is about 2.97 GB.

(2) Item-1 re-export: 15 items, export hook only, --trees none:
- Inputs: states 1,340,719,784 + fit.ckpt 183,689,022 + the 15 parts 72,215,996 + run.id + classes, about 1.600 GB.
- Records, about 0.12 GB (ESTIMATED from MEASURED parts): the 15 corresponding main records' Results are 115.5 MB, minus 43.1 MB of wide fields, plus about 3.4 MB of export per row × 15.
- Bundle, about 0.06 GB (ESTIMATED).
- Peak about 1.78 GB. Adding --wide adds about 0.09 GB. Reading trees.bin adds 14.88 GB.

(3) KeyEdgesRun:
- a: states 1,340,719,784 + results.ser 430,694,877 + output a-*.ser 221,351,385 (written as .tmp then renamed, KeyEdges.java:302-317) = 1,992,766,046, about 1.99 GB, plus 3.3 MB of classes.
- a-prime: 1,836,969,562, about 1.84 GB.
- For reference, the report: its 5 collectors + results.ser + report-final.md + the JSON and CSV it writes = 1,937,379,049, about 1.94 GB.

Not counted above: macOS swap lives on the same SSD. vm.swapusage measured 2.17 GB in use while -Xmx10g JVMs ran.

**Artifacts.**

- `runs/replay/c06c061-0e0920d/wide.ser` (249,769,420). Holds: MEASURED with a read-only inspector (/private/tmp/ccec-disk-replay-56972/inspect/Inspect.java, run on classes-0e0920d). The file holds the identity (2,431 chars) and 121 WideReport.Row objects. Field shares: wideBoot 235.5 MB (94.3%), wideKeys 12.9 MB, keys 0.79 MB, wide 0.54 MB. Read by: WideReport.run (WideReport.java:800-818) read it to make runs/wide/spreads-7c1d40e.json and report-wide-7c1d40e.md. That step took 4 s (row10-partial-7c1d40e.log, 05:14:38 to 05:14:42). RiskReport.readEngine, case "replay" (RiskReport.java:305-310), will read it later: RiskReport has not run yet and waits for simulator part 2 (INTEGRATION.md row 10). Needed for: RiskReport, which is still pending. Also any rerun of WideReport. Regenerable: Only by rerunning the whole 93-item replay with --wide. MEASURED from replay/c06c061-0e0920d.log, the 27 batch lines sum to 14.90 h with trees.bin read (last rounds 15,145 s, scoring 38,487 s). With --trees none it would take much longer: the --trees none check running now (2 items) was 70% through one last round after 5,304 s. The bytes would match only with the frozen classes-0e0920d, because the identity embeds the commit, the paths and the state file's mtime (FullIndexRun.java:392-395). Compressibility: MEASURED on a 200 MB sample (head -c 200000000 | nice -n 15 gzip -6 / zstd -3 -T1 / zstd -19 -T1 / xz -T1 -6, then wc -c): gzip 0.668, zstd-3 0.571, zstd-19 0.536, xz 0.490. A byte-plane shuffle (16 MB sample) only took xz from 0.372 to 0.367. Hash-cited: Yes. ee3ff121… is cited in runs/wide/spreads-7c1d40e.json "inputs" and in report-wide-7c1d40e.md line 3. That JSON's own SHA (b86622c4…) is cited in runs/site/spreads-7c1d40e.json _source.artifacts[0], which feeds the page's files table (SiteManifest.java:79-124). No committed file cites it.
- `runs/replay/c06c061-0e0920d/replayed-part-*-folds.ckpt (93 records)` (928,881,429). Holds: MEASURED by re-serializing each part alone (/private/tmp/ccec-disk-replay-56972/inspect/Sum.java). Each record holds one item's Result, 665.6 MB in total, of which 241.6 MB are the wide fields; then shoeSums at 480 KB per entry and boot. Also: the sink's Handed arrays 241.5 MB, the export's RowExport list 27.9 MB, ResultDiff 0.06 MB, and the perfect values 0.73 MB. Read by: Only FullIndexReplay itself: on a resume (FullIndexReplay.java:661) and in assemble, write and each hook's finish (:272-306, :857-924; ReplayHooks.java:84, :199; StrategyExport.java:2747). Needed for: Nothing now. Every output was written from them. The item-1 fix uses new classes, which give a new identity, so these records cannot be resumed (DISK_CLEANUP.md:30-35). They were left behind only because the export's refusal at StrategyExport.java:2771 skipped letGo (FullIndexRun.java:218-220). Regenerable: Not needed. Compressibility: MEASURED on 4 whole records (45.2 MB): gzip-6 0.577, zstd-3 0.518, zstd-19 0.450, xz 0.413. Single records range from 0.49 to 0.70 with gzip. Hash-cited: No.
- `runs/replay/c06c061-0e0920d/perfect.ser, replay.txt, run.id` (974,439 + 51,657 + 2,431). Holds: perfect.ser holds the identity, the fit SHA, and two arrays: perfectSource and perfectAgain, each double[60,743] (FullIndexReplay.java:912-920). replay.txt holds each item's comparison, written out in words. Read by: No class in src reads them (grep). They are evidence for PLAN_COMPUTE.md 4.2. Needed for: The record of the replay's check against the source. Regenerable: Only through the full replay. Compressibility: MEASURED, whole files. perfect.ser: gzip 0.951, zstd-3 0.476, xz 0.468 (its two arrays are mostly the same bits). replay.txt: gzip 0.067. Hash-cited: No.
- `runs/replay/c06c061-0e0920d-sink/run-b.ser` (704,599,811). Holds: MEASURED with Inspect. It is a KeyEdges.Collector. Entries take 559.0 MB (79.3%), copies of the merged SystemScorer.Entry objects: wideBoot 227.7 MB, shoeSums 168.5 MB, boot 143.4 MB, wideKeys 12.9 MB. Handed takes 143.6 MB (play 80.1, insurance 36.9, key 24.8 MB raw). Facts take 1.9 MB. Read by: KeyEdgesRun report --full (KeyEdgesReport.java:403-410). It ran on 17 Oct at 7c1d40e. The only Entry fields KeyEdges*.java reads are name, count, flags and estimate (grep; KeyEdgesReport.java:268-312). Needed for: Only a rerun of the key-edge report. Its JSON is already written. Regenerable: Only through the full replay with --sink: the same 14.90 h or more. Compressibility: MEASURED. First 200 MB (entries): gzip 0.680, zstd-3 0.597, zstd-19 0.503, xz 0.458. Last 144 MB (handed): gzip 0.625, zstd-3 0.510, zstd-19 0.422. Hash-cited: Yes. 3c1866b6… is cited in key-edges-7c1d40e….json inputs. That JSON's SHA (824f0769…) is cited in runs/site/key-edges-7c1d40e.json _source.artifacts[0].
- `runs/key-edges/a-304901c9e8dc586e0382f803096964ec94b549e3.ser` (221,351,385). Holds: MEASURED with Inspect. This is run A's Collector. Entries take 115.2 MB (78 entries), of which shoeSums alone is 112.3 MB (50.7% of the file) and is never read by the report. Handed takes 104.2 MB (play 40.7, insurance 36.9, key 24.8 MB). Facts take 1.9 MB. Read by: KeyEdgesRun report --a (KeyEdgesReport.java:403). Needed for: Only a rerun of the key-edge report. Regenerable: Yes: KeyEdgesRun a, 253.9 s by its own clock (queue-304901c.out). The queue log spans 67 min wall for that step; the gap is not explained. It reads the states file and results.ser. The bytes would match with classes-304901c and an unchanged states mtime. Evidence that runs are deterministic: run-c.ser and wide-first.ser were rebuilt under other classes and differ only in bytes 29-67, the commit hash (cmp). Compressibility: MEASURED on a 200 MB sample: gzip 0.561, zstd-3 0.431, zstd-19 0.310, xz 0.278. Hash-cited: Yes. 56148393… is cited in key-edges-7c1d40e json inputs. The chain then continues to the site as above.
- `runs/key-edges/a-prime-304901c9e8dc586e0382f803096964ec94b549e3.ser` (65,554,901). Holds: MEASURED. refitted (resample sums) take 43.1 MB (66%). Entries take 12.7 MB (shoeSums 6.7, boot 5.7). Handed takes 7.8 MB and facts 1.9 MB. Read by: KeyEdgesRun report --a-prime (KeyEdgesReport.java:404, 378). Needed for: Only a rerun of the key-edge report. Regenerable: Yes: KeyEdgesRun a-prime, 611.8 s by its own clock and 10 min 12 s wall. Compressibility: MEASURED, whole file: gzip 0.680, zstd-3 0.637, zstd-19 0.482, xz 0.435. Hash-cited: Yes. c649f44f… is cited in key-edges-7c1d40e json inputs.
- `runs/key-edges/key-edges-7c1d40ee….json and .csv (plus 0a447de's pair, superseded)` (56,333,649 + 21,271,971 (0a447de's: the same sizes, 77,605,620)). Holds: The report's figures, plus an inputs block that gives each input's absolute path and SHA-256. Read by: SiteAdapt edges (SiteAdapt.java:137, SiteEdges.java:74-80). Note that it takes files.get(0), so the glob key-edges-*.json picked 0a447de's on 17 Oct (row10 log.out). The site file was rewritten at 05:20:45 citing 7c1d40e's. Needed for: Rebuilding runs/site/key-edges-7c1d40e.json. Regenerable: Yes: the report takes 294.0 s (log). MEASURED: the 0a447de and 7c1d40e JSONs differ in 39 bytes, the commit field, and their CSVs are identical. Compressibility: MEASURED, whole files. JSON: gzip 0.172, zstd-19 0.077, xz 0.080. CSV: gzip 0.216, xz 0.135. Hash-cited: Yes. 824f0769… is cited in runs/site/key-edges-7c1d40e.json and so in the page's files table. 0a447de's pair is not cited.
- `runs/page-look-0e0920d/run-c.ser` (245,680,412). Holds: MEASURED. This is the Collector of run C. Entries take 179.5 MB (shoeSums 95.0, wideKeys 37.4, wideBoot 21.6, boot 13.6, keys 11.3). Handed takes 64.2 MB. Read by: KeyEdgesRun report --rows (KeyEdgesReport.java:409). Needed for: Only a rerun of the key-edge report. Regenerable: In practice, no. Run C plus its export took 1,568.2 s (log), but PageLook is made once per ledger (PageLook.java:201-207). It also requires trees.bin (PageLook.java:179-181, 241-245), and on --resume it reruns run C only if export-first is absent (:297). Compressibility: MEASURED on a 200 MB sample: gzip 0.413, zstd-3 0.345, zstd-19 0.258, xz 0.234. Hash-cited: Yes. 2939a4ea… is cited in key-edges-7c1d40e json inputs.
- `runs/page-look-0e0920d/run-d/run-b.ser` (191,051,009). Holds: MEASURED. Entries take 162.4 MB (85%: wideBoot 60.3, shoeSums 44.6, boot 38.0, wideKeys 14.4). Handed takes 26.7 MB. Read by: KeyEdgesRun report --full. Needed for: Only a rerun of the key-edge report. Regenerable: Only through PageLook's replay of step 8, which needs trees.bin. MEASURED: 11,915 s of batches (7,196 s last rounds, 4,719 s scoring). Compressibility: MEASURED, whole file: gzip 0.520, zstd-3 0.458, zstd-19 0.382, xz 0.349. Hash-cited: Yes. 3fe732ce… is cited in key-edges-7c1d40e json inputs.
- `runs/page-look-0e0920d/wide-first.ser and replay/wide.ser` (61,532,212 + 83,591,398). Holds: wide-first.ser holds 67 WideReport rows (MEASURED: wideKeys 37.4 MB, wideBoot 21.6 MB). replay/wide.ser holds the wide rows of step 8's replay. Read by: Nothing. No class in src and no command in INTEGRATION.md row 10 reads either file. WideReport takes a single wide.ser (WideReport.java:845-866). Needed for: No current reader. Regenerable: Only through PageLook, which needs trees.bin. Compressibility: MEASURED, whole files. wide-first: gzip 0.270, xz 0.199. replay/wide.ser: gzip 0.439, xz 0.339. Hash-cited: No.
- `runs/page-look-0e0920d/replay/replayed-*.ckpt (15), perfect.ser, replay.txt, run.id` (226,485,724 of the folder's 311,067,976). Holds: Run D's records: each holds a Result, the sink's Handed arrays, and the export's expectations (export-full was refused). Read by: Only a resume. Needed for: Nothing. The records are batch B. PageLook exited 1 before letGo ran (PageLook.java:532-535). Regenerable: Not needed. Compressibility: MEASURED on one record (19.8 MB): gzip 0.556, zstd-3 0.509, zstd-19 0.453, xz 0.416. Hash-cited: No.
- `runs/page-look-0e0920d/export-first/` (23,016,894). Holds: The F49/F51 bundle: states.bin 6.7 MB, keys-sample 8.5 MB, chart.tsv 4.4 MB, lookups 1.8 MB, and 4 strategy files. Read by: Simulator part 2 (INTEGRATION.md row 8). It has not run yet. Needed for: Simulator part 2. Its manifest SHA-256 (d385bccd…) goes into SIM_CHECK.md's identity table. Regenerable: Only through PageLook, which needs trees.bin. Compressibility: MEASURED. states.bin: gzip 0.556, xz 0.377. keys-sample: gzip 0.208, xz 0.142. Hash-cited: Not yet. The part-2 row is unfilled.
- `runs/page-look-0e0920d/attempt1-0e0920d/ and runs/replay/gate0-304901c{,-export,-sink}` (307,215,255 + 21,540,459 + 15,915,699 + 8,823,971). Holds: Superseded first attempts. Read by: Nothing. Needed for: Nothing. These are in batch C. Regenerable: Not needed. Compressibility: Not measured. ESTIMATED to behave like their successors. Hash-cited: No.

**Ideas.**

- Delete the replay records (batch B, 1.16 GB). Also make a refused export still let its records go: FullIndexRun.java:218-220 and PageLook.java:532-535 skip letGo when StrategyExport.java:2771 throws, even though wide.ser and run-b.ser were already written. That is how 0.93 GB + 0.23 GB were left behind. Cost: a small code change and one test. Figures bit-identical; no cited hash changes, since no file cites the records.
- Compress the replay records inside FullIndexCheckpoint.writeRecord/readRecord (FullIndexCheckpoint.java:161-168) with java.util.zip.Deflater, using a magic header so old records still read. MEASURED gzip-6 ratio is 0.577, weighted over 4 records, which would cut the 93-item peak by about 0.39 GB. Cost: a code change plus a test; a few CPU-seconds per item. All outputs are built from the deserialized records, so they stay bit-identical and no hash changes.
- Slim KeyEdges.Collector.save (KeyEdges.java:285-317) so it keeps only name, count, flags, estimate and keys of each Entry. KeyEdgesReport reads only these (KeyEdgesReport.java:268-312). MEASURED: Entry copies are 1,028.8 MB of the five collector files' 1,428.2 MB, including 559.0 of run-b.ser's 704.6 MB, so this saves about 1.0 GB at rest and cuts the replay peak by about 0.55 GB. Figures expected bit-identical, but this needs a test comparing reports from slim and full files with the inputs block excluded. Hashes: each .ser's SHA-256 changes, so key-edges json's inputs block and its own SHA change, and so do runs/site/key-edges json's _source.artifacts and the page's files table. Apply it to future runs, or keep the old files archived.
- Archive the intermediates that are done with to Drive, byte for byte (rclone copy, then rclone check and a local shasum against the cited hashes). That covers main run-b.ser, a, a-prime, run-c and run-d/run-b (1.428 GB), plus wide-first.ser and the look's replay/wide.ser (0.145 GB, which nothing reads). Do wide.ser (0.250 GB) after RiskReport has run. Drive has 3.65 TiB free (rclone about). Cost: about 1.8 GB of upload; a rerun of KeyEdgesRun report needs 1.43 GB restored to the same absolute paths, because the JSON cites absolute paths (KeyEdgesReport.java:419-433). Bit-identical, and every cited hash stays verifiable. xz before upload is optional (ratios 0.23 to 0.49, MEASURED).
- Treat a, a-prime and the report as recomputable: 254 s, 612 s and 294 s (MEASURED). Rerunning with the frozen classes-304901c, the same paths and an unchanged state-file mtime should give the same bytes (ESTIMATED; the only differences seen between reruns were the commit hash). Under new classes the SHA changes and the figures do not. Do not plan to regenerate run-c, run-d or the main sink or wide.ser: they need PageLook, which needs trees.bin and is made once, or the 14.9 h replay.
- Run the item-1 re-export under 2 GB: use --trees none, --only for the 15 items and --export-row ×15, with runs/ holding only the states file, fit.ckpt, run.id and the 15 parts. The peak is about 1.78 GB. It must also be given --disk-budget at or above what runs/ holds + 0.375 GB (FullIndexReplay.java:552-554). Cost: every batch rebuilds the trees, about 0.5 h a batch by TreeStore's 0.17 CPU-s × 60,743 states, and slower under load. Caveat: with --only, the per-row wide plans of F43 to F48 (P16, B0,8 and X-1.0,8 on Hi-Lo@80 and Victor@80) are not attached (FullIndexReplay.java:418-439); including them needs a code change plus --wide (StrategyExport.java:2665-2680). Figures are unaffected, and the new bundle's hash is not cited anywhere yet.
- Do not rerun the 93-item replay under a 2 GB cap: its inputs alone are 1.95 GB. If it ever had to be rerun, every one of these would be needed together: fetch each source part just in time (it is read once at FullIndexReplay.java:762; the up-front existence check at :547-551 would move), compress the records, slim the sink, and store or stream the state file compressed. Even then the peak would be about 2.3 to 2.5 GB unless the run is split by --only, and WideReport and RiskReport take one wide.ser (code change; new file hashes; figures can stay bit-identical). The state file is another area's call: FullIndexRun.stateFile puts its size and mtime into every identity (FullIndexRun.java:392-395).
- KeyEdgesReport reads all of results.ser (0.43 GB) only for its perfect array (KeyEdgesReport.java:411). The replay's perfect.ser (0.97 MB) holds the source's array (FullIndexReplay.java:636, :912-920). Reading that instead would cut the report's working set to about 1.51 GB. It changes the JSON's inputs block and so its hash. Figures would be bit-identical only if the two arrays are equal to the bit, which I did not test.
