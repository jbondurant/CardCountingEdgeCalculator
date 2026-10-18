# Does it need more than 2 GB? The workflow's working papers (18 October 2026)

Justin: "are you sure the algorithm can't be rewritten efficiently so that it uses like <2gb at any moment as some sort of
sliding window of data, or does it truly need >2gb at some point during the run to be efficient?"

Four investigators (round dependencies, tree cost, RAM against disk, later stages), a designer and two adversarial judges
(exactness, speed), read-only. Probes read at most a few hundred states, read-only, at nice 15; their sources and outputs
are in disk/probes-2026-10-18/. MEASURED means measured by a probe or a log, not yet by tested repo code. DISK_STREAMING.md
is the summary. Kept as evidence, not instructions.

## Judge: exactness

**Corrected answer.** No. The run never needs more than 2 GB of disk to be correct. The one file over 2 GB, trees.bin (14.9 GB), is a cache of stand values that are a fixed function of each state's cards. Each round already works through the states one at a time. Three things really are fixed. (1) The fit is policy iteration: every round refits every play by signs and argmaxes over sums from all states, so rounds run one after another and each tree is needed once a round, 48 times. (2) Even the cells a round reads (R, about 26,100 a state, about 12.7 GB for all states, compressing at most 1.3x) cannot fit under 2 GB, and on cyclic access a partial cache saves time only in proportion to its size. (3) The bootstrap needs one item's per-part values in RAM at once. Those are limits on RAM and CPU, not disk. Everything else over 2 GB is how files are stored: the raw states (about 0.61 GB losslessly), a second copy of the results, uncompressed checkpoints, and fat collectors and replay records. With those fixed, the main run peaks at about 1.0 GB (ESTIMATED).

The real trade is time. Building trees again in every round costs about +23 h per main run with today's code (ESTIMATED). Two changes cut it and keep every bit: a regrouped dealer batch, and building only the R cells, together roughly 0.50-0.56 of today's build (an estimate from two separately measured ratios). Packing more items into each scoring batch cuts the rounds that build trees, from 43 last rounds to roughly 13 (an estimate from the heap model). That brings it to roughly +4-6 h.

Two conditions are needed for this to be exact, and the current plan does not state them:
(a) An R-only tree must hold a non-NaN placeholder in every feasible cell outside R, or Counted must take feasibility from the card counts, because Counted treats NaN as infeasible (PlayerTree.java:700). With NaN, every departing part silently takes the stored chart V(Hit). MEASURED: 98,349 of 98,349 departing parts affected and 0 gaps changed, so no NaN appears anywhere. With 0.0 there, 0 of 128,526 parts differ. The test for this change must compare walks on the R-only-built tree with the full tree bit for bit; poisoning a full tree cannot catch it.
(b) Build the full tree wherever perfect[s] is still NaN, not 'in round 0'. The replay and PageLook D clear the perfect values and recompute them all in their first batch. MEASURED on an R-only tree: 1,605 of 24,839 perfect-walk values are wrong, by up to 4.4x, which stops the replay. The fix costs about one more full build per replay.

Smaller corrections:
- The smallest store filled before the plays are known is R (about 12.7 GB), not N (about 10 GB).
- fit.ckpt is not only for resuming: every replay restores from it.
- KeyEdgesRun's extract must keep c06c061's stored resamples and identity lines, not only estimates and keys.
- v3 also changes the state-file identity (path, size, mtime) that about ten pairing checks compare, so stateFile must be made v3-aware.
- Moving the hooks into the main run also carries over today's export refusal (B2.4).

With these, every proposed change keeps figures bit-identical, because no sum is reordered. Anything that reorders sums agrees only to within 1e-12 and can still flip a sign, a zero test or an argmax, so it is rightly rejected.

### Refutations

- [major] **DESIGN code change 6: 'R-only trees in rounds 1 and later. Test: a poison unit test (cells outside R set to 1e250 leave every walk output unchanged...)', together with 'Nothing in the design changes the order in which anything is added up' / every figure bit-identical.**
  - Problem: The plan does not say what an R-only tree holds for feasible hands outside R. PlayerTree.Counted decides whether every graph node is feasible by !Double.isNaN(stand[u][h]) (PlayerTree.java:700), and start (2-card) and always-hit hands are outside R. If an R-only build leaves those cells NaN, the natural default since the constructor fills rows with NaN (PlayerTree.java:170), every start node becomes infeasible (PlayerTree.java:896-900). Its partHit turns NaN and partDeparts turns false. Then record() and accumulate() silently use the stored chart V(Hit) for every part (FullIndexFit.java:869, 912). The gaps do not change, so no NaN reaches any figure: the first-decision refit and the final Hit values are wrong without any sign. The poison test cannot catch this. It puts a non-NaN 1e250 into a full tree, so feasibility never changes.
  - Evidence: MEASURED with NanFillProbe (scratch: /private/tmp/claude-501/-Users-justinbondurant-Documents-docsClaude/078aba77-3392-4bd9-bf33-3d32e8794c9a/scratchpad/exact-verify/src/NanFillProbe.java, output nanfill.txt). Command: nice -n 15 java -cp out:runs/full-index/classes-c06c061:lib/mongo-java-driver-3.12.14.jar NanFillProbe runs/montreal-80.states 40, on 46 states, 10.6 s CPU, Hi-Lo, KISS I and Omega II under 2 arbitrary later plays. Tree with NaN outside R: 98,349 of 98,349 departing parts lost their departs flag, 128,526 of 128,526 part Hits became NaN, and 0 of 1,238,552 decisions (gaps) changed. Same R-only tree with 0.0 in feasible non-R cells (as TreeSubsetProbe does): 0 of 128,526 parts differ. Fix: a non-NaN placeholder, or feasibility taken from the card counts; and a component test that compares walks on the R-only-built tree with the full tree bit for bit. Only the end-to-end dry-run gate would catch the NaN fill as specified today.
- [minor] **DESIGN: '+ regrouped build on the R cells in rounds 1-48 (full build in round 0 for the perfect row)'; the replay row ('bit-identical, checked by a per-item digest', +1.5-2.2 h) and PageLook C+D assume the same trees.**
  - Problem: 'Round 0' is the wrong condition for a full build. The perfect value is computed on whatever tree trees.of returns, the first time a state with perfect[s] still NaN is visited (FullIndexFit.java:2023, 2039-2040). The replay sets every perfect value back to NaN (FullIndexReplay.java:636-637). Its restored fit already holds the 6 pass rounds (FullIndexCheckpoint.java:134-135), so by round index its first batch is round 6 or later, and it recomputes all 60,743 perfect values with the EVERY_NODE walk, which reads about 98% of cells, outside R. PageLook run D's replay of step 8 does the same.
  - Evidence: MEASURED (same probe, 46 states): the perfect walk on a 0.0-filled R-only tree gives 1,605 of 24,839 part values that differ from the full tree, by up to 4.43 relative. perfectAgain stops the replay beyond 1e-12 (FullIndexReplay.java:836-841). The real replay recomputed 60,743 perfect values in its first batch ('a batch of 4 items: last round 2084.6 s, 60743 states' perfect values worked out again', runs/replay/c06c061-0e0920d.log). The failure is loud, not a wrong published figure. The rule must be a full tree wherever perfect[s] is NaN. That costs about one more full build (T, about 0.5 h) per replay and in PageLook D.
- [minor] **DESIGN (2) and trees area: 'The part of each tree the walks use is about 165 KB ... or 10 GB for all states'; 'the smallest possible store is N-only'. Rounds area: 'No exact summary is much smaller (the walk reads 85%)'.**
  - Problem: N is the union over one round's plays (departing parts and reached decisions; FullIndexFit.java:869, 912). The plays change every fitting round and are not known when a store is filled in round 0, so a store built ahead of time must hold R, about 209-213 KB a state, about 12.7-12.8 GB. N bounds a per-round lazy build, not a store. The two areas also disagree: per round, N (65-70% of feasible cells) is markedly smaller than R (89%). The conclusion, over 2 GB either way, is unchanged.
  - Evidence: PlayerTree.java:908-938 (departs follows the later play); FullIndexFit.java:992-1013 (the play changes each pass); measured sizes from the rounds probe (26,118 cells, 208,944 B a state) and the trees probe (N 65-70% for given plays).
- [minor] **DESIGN choice table: '+ regrouped build on the R cells ... +10-15 h (24-27 T) | ratio MEASURED (R 0.889 x regrouped 0.566-0.625)'.**
  - Problem: The R-plus-regrouped build was never measured as one build. The figure is a product of two ratios, each measured separately (R-only 0.887-0.889, and regrouped on the full tree 0.566-0.625). Only full+regrouped and N+regrouped were measured together. The 0.50-0.56 is an ESTIMATE, and the regrouped gain need not be the same on a subset of hands.
  - Evidence: Trees area facts: 'Over the full tree it costs 56.6-62.5%... On N it costs 48.8-53.5%'; no R+regrouped row in probe2, probe3 or fast2.txt as quoted.
- [minor] **DESIGN 'What every round needs': 'fit.ckpt and parts: only for resuming.'**
  - Problem: fit.ckpt is also the only record of the fitted strategies. Every replay (wide, sink, export, PageLook) restores from it and hashes it into its identity, so it must stay until the last replay. Under 'one copy of the results', the parts are the results themselves, not only resume data.
  - Evidence: FullIndexReplay.restore reads fit.ckpt and refuses one not marked fitted (FullIndexReplay.java:619-638); the replay identity carries sha256(fit.ckpt) (FullIndexReplay.java:555-556); 2gb working papers line 805.
- [minor] **Stages / DESIGN step 9: per-reader extracts, with 'KeyEdgesRun's checks get estimates and keys', so c06c061's results.ser can leave before the replay, while A and A' are recomputed when the report runs.**
  - Problem: This drops data a later stage reads. Run A' compares every stored resample (w.boot[p][m][k] for all METRICS and 1,000 resamples) of its counts at REFIT_PEN (KeyEdgesRun.java:390-402). Run A and A' also run CeilingReport.requirePaired on the stored file's identity lines: state file, shoes/valued states, resamples and seed (CeilingReport.java:112-129; KeyEdgesRun.java:338, 446). With an extract of estimates and keys only, A' fails its checks and keeps nothing. The extract must also carry those boot arrays (about 1 MB) and the identity lines.
  - Evidence: Code read of KeyEdgesRun.scoreAPrime and checkAgainst (KeyEdgesRun.java:270-289, 370-410) and CeilingReport.read/requirePaired (CeilingReport.java:97-129).
- [minor] **DESIGN / ram-disk: v3 states keep every figure bit-identical, and 'only the files' SHA-256 hashes change'.**
  - Problem: Figures stay the same, but the state-file identity also changes. FullIndexRun.stateFile is the absolute path, Files.size and mtime (FullIndexRun.java:392-395). About ten consumers compare it: requirePaired in KeyEdgesRun (338, 446), StrategyExport (2868, 2890), CeilingReport (299) and RiskReport (316); PageLook (212, 318); SiteExport (576); and the replay's TreeStore (FullIndexReplay.java:565). Each refuses a v3 file paired with c06c061, step 7 or step 8 outputs unless stateFile becomes v3-aware and reports the decoded v2 identity. Step 1 of the build order lists no such change; the ram-disk area mentions it only for the replay.
  - Evidence: grep -n 'stateFile(\|requirePaired(' src/*.java (worktree 089562d).
- [minor] **DESIGN choice table: 'Hooks inside the main run (from the seeds only) -13 h on the pipeline'.**
  - Problem: One of the three hooks does not finish on today's fits. The c06c061 replay's export hook threw at finish: 'rows that could not be exported', about nine full-play rows with one (group, deal) ranked differently by the scored fit than by the play the last round walked (B2.4). Moved into the main run, the run would end in that refusal unless it is resolved, so the export part of the -13 h does not exist yet.
  - Evidence: Tail of runs/replay/c06c061-0e0920d.log (IllegalStateException at StrategyExport$ReplayExport.finish, StrategyExport.java:2771; check at StrategyExport.java:333-341).
- [minor] **Rounds verdict: the two per-state caches, trees and split values, 'are both fixed functions of a state's dealt cards'.**
  - Problem: Split values depend on the deal and on the post-split play's fingerprint too: SplitValues.value(i, e.play, e.print), cached per (pair deal, print). They are deterministic, and cached hits return raw bits, so exactness and the no-disk conclusion stand. But the cache's contents depend on which plays were asked.
  - Evidence: FullIndexFit.java:1782-1800; SplitValues.java:181-218 (stores Double.doubleToRawLongBits).

### Upheld

- The rounds are a true global barrier. endPass refits each strategy from sums pooled over all its training states (FullIndexFit.java:981-1014). refitLater sets each later cell by the sign of its sum, and an exact-zero sum or zero reach plays the chart (FullIndexFit.java:1170-1186). The log has 49 rounds, every one walking 60,743 states except four last rounds at 52,611; rounds 0-5 sum to 24,759.6 s (full-c06c061.log lines 15-70).
- Per-strategy sums are deterministic for a fixed thread count. Each thread takes shoes first, first+threads, ... (FullIndexFit.java:1952), and the worker accumulators are merged in worker order (FullIndexFit.java:1971-1980). So 'same Results to the bit on 6 threads' holds, and a different thread count only gives agreement within 1e-12.
- Batch makeup does not change any item. The replay matched 93 of 93 items and 60,743 of 60,743 perfect values to the bit, with 27 batches against the main run's 43 (runs/replay/c06c061-0e0920d.log). Items are scored one after another (FullIndexFit.java:2444); the Javadoc at 2201 saying 'at once' is stale.
- Rebuilding trees with no store gives the same bits: the --trees none replay matched 416,114 + 829,436 numbers and 60,743 perfect values (DISK_CLEANUP.md:65-69).
- R as a set does not depend on the play. Live is built from hand totals alone (PlayerTree.java:548-575). Every feasible live node reads its stand-target children whatever the play (PlayerTree.java:908-923). The rounds probe found the same 2,709 hands for all 4 class layouts. An R-only tree with a non-NaN placeholder is bit-identical: MEASURED 0 of 128,526 parts in my probe and 0 of 1,419,420 in TreeSubsetProbe.
- The regrouped dealer batch keeps the bits. After the rank-factor loop, p[s] depends only on the shoe's counts of that entry's ranks (ExactRound.java:589-595), and the per-shoe step then uses cards[s] (ExactRound.java:599-601). So the product computed once per projection is the same double, added in the same entry order.
- A sparse Hit store is exact. Parts that do not depart take st.move's own double (FullIndexFit.java:869, 912). The expansion must also reproduce PlayValues' NaN default for slots record() never writes (SystemScorer.java:581).
- Split values are kept losslessly as raw long bits (SplitValues.java:198, 218), so cache hits, misses and dropping the cache leave every value unchanged.
- Slim collectors are safe for the report. Collector.read is called only by KeyEdgesReport (grep). The report takes per-state values from the handed arrays (KeyEdges.java:236-263), which are kept, and from Entry only name, count, flags and estimate (KeyEdgesReport.java:268-325).
- One copy of the results is exact. results.ser is the identity, the merged parts, perfect and rounds (FullIndexRun.java:341-347), and the working papers measured the rebuilt bytes at SHA-256 8ba1412b (2gb working papers, line 747).
- The near-tie caveat is right. Sign, exact-zero and argmax tests decide the play (FullIndexFit.java:1176-1183, 989-992), so a reordering that agrees to within 1e-12 in the sums does not bound how far a figure can move.
- v3 decoding and gzip are lossless, so ScoringStates and the deserialized checkpoints come back the same. All readers go through the StateStore API (FullIndexPilot.java:648-672). The whole-file decode test is rightly listed as still owed.

## Judge: speed

**Corrected answer.** The verdict holds: the computation does not need more than 2 GB of disk. Only the tree store (14.9 GB) is over 2 GB, and it is a cache. Two numbers in the redesign are off, though: the speed of the cheaper tree build, and the disk margin across the whole pipeline.

**Speed (corrected).**
- **T, one full rebuild of 60,743 trees on 6 threads.** Measured today on a nearly quiet machine, it takes 1,190-1,380 s. In real runs, where tree builds share the threads with split valuation and other load, the dry runs' 0.13-0.19 CPU-s a tree gives about 1,300-1,900 s (estimate).
- **No store, code as it stands:** 48 rebuilds, so +16 to 25 h on the main run. The quoted +23 h is near the top of that range.
- **Regrouped dealer batch.** It is bit-identical (0 differing bits in about 40M cells, measured), but it is not reliably 1.6-1.75x faster:
  - at 6 threads, the same method ran at 0.54 to 0.88 of PlayerTree's time, depending on the JIT;
  - the earlier probe, rerun on its own sample, gives 1.44x.
- **R-only cells** cost 0.889 on PlayerTree's path. Under the regrouped build, building fewer cells saves less (about 0.95).
- **So a rebuild costs about 0.51 to 0.84 of today's** (estimate).

| Change | Extra time on the main run |
|---|---|
| Regrouped R-only build, no packing | +8 to 21 h (48 rebuilds) |
| Plus packed batches (43 to about 13 last rounds; heap model, not measured) | +3 to 8 h (18 rebuilds) |
| DESIGN's figure for the second row | +4 to 6 h |

Merging the replay's hooks into the main run saves about 13 h of the pipeline whether or not trees are stored. It does not offset the tree cost. That cost cannot be removed while staying under 2 GB and bit-identical: the smallest exact store is about 10 GB, and a smaller cache saves time only in proportion to its size.

**Disk (corrected).**
- With v3 states, gzip and one copy of the results, the main run peaks at about 1.0 GB (estimate).
- The DESIGN leaves out the twin chain: twin StateRun, calibration, CountSearch x47, choice-20000, SplitMargin and the optimism twin part. Its whole lifetime falls inside the main file's, so both v3 state files (0.61 + 0.61 GB) sit on disk together with c06c061's fit.ckpt and results (gzipped, about 0.40 GB) and the kept results (about 0.1 GB).
- That puts the pipeline's peak at about 1.7-1.9 GB (estimate; the results-only papers' model R says 1.83-1.90 GB), with 0.1-0.3 GB of margin, not 0.6 GB.
- The answer is still under 2 GB, but only just.
- Minor points:
  - Converting today's v2 file to v3 briefly needs about 2.06 GB with the kept results. It must be done before the cap applies, or StateRun must write v3 directly.
  - Swap on the same SSD (1.7 GB in use now) is not counted anywhere.

**Exactness.** Unchanged. Rebuilt, regrouped and R-only trees are bit-identical, and so is rebatching. Anything that changes summation order agrees only to about 1e-12 in the arithmetic, and that does not bound the figures, because the fit takes signs, argmaxes and zero tests.

**Still owed:**
- A sustained 6-thread T measurement (the fanless Air may throttle).
- The regrouped build implemented in ExactRound and timed at 6 threads in the real run mix.
- The measured heap per packed item.

Probes are in /private/tmp/claude-501/-Users-justinbondurant-Documents-docsClaude/078aba77-3392-4bd9-bf33-3d32e8794c9a/scratchpad/verify-speed/ (src/SpeedProbe.java, src/MaskProbe.java, run/*.txt). They read up to 300 states read-only, used about 9 CPU-minutes at nice 15, and wrote nothing in the repo or runs/.

### Refutations

- [major] **DESIGN and trees: the regrouped dealer batch (FastProbe.fast2) is 1.6-1.75x faster, measured. Together with R-only cells a tree costs 0.50-0.56 of today's build ("measured on a prototype"), which gives +4-6 h on the main run (DESIGN) and about 12 h (trees).**
  - Problem: I could not reproduce the speedup. Rerun today on the same machine with the same code, the gain is 1.13x to 1.85x, depending on the harness and the JIT. And 0.50-0.56 was never measured: it multiplies two factors measured apart. The regrouping gains less when fewer cells are built, so the product is too low.
  - Evidence: MEASURED. Scratch probes in .../scratchpad/verify-speed/ (nice 15, load 1.5-4, compiled against classes-c06c061, read-only, 150-300 states).
- FastProbe as written, rerun on its own sample (offsets 5,10005,...,60005 x4): variant 2 runs at 1.44x, not 1.67x (run/fastprobe-theirs.txt). On another sample, 1.24x (fastprobe-mine.txt).
- SpeedProbe, 6 threads: fast2 takes 0.80-0.88 of PlayerTree's wall time on 150-300 spread states (speed6.txt, speed6-spread.txt, speed6-again.txt). On 1 thread it takes 0.77-0.79.
- MaskProbe, 6 threads, the same fast2 method on the same states: 0.545-0.585 (mask6.txt, mask6-again.txt).
- Every run: 0 differing bits.
- The trees investigator's own N figures show the gain shrinking under a mask: fast2 on N is 0.488-0.535 against 0.677-0.679 for PlayerTree's path, a factor of 0.72-0.79, while on the full tree it is 0.57-0.63. So fast2 on R comes to about 0.95 x fast2 full, not 0.889 x.
- Corrected factor per rebuild: about 0.51-0.84 of today's build.
- [major] **DESIGN: later stages peak at 1.1-1.5 GB. When a stage combines earlier outputs, the margin is about 0.6 GB. The stage table covers StateRun, main run, step 7, step 8, replay, PageLook, KeyEdgesRun, simulator and RiskReport.**
  - Problem: The twin chain is left out: the twin StateRun, calibration, BoundRows, CountSearch x47, the SearchReports, choice-20000, SplitMargin and the optimism twin part. The working papers say the twin's whole lifetime falls inside the main file's: calibration needs c06c061's results, and step 7 needs both. So both state files are on disk together, with c06c061's fit and results. That window is the pipeline's real peak, about 1.7-1.9 GB, which leaves 0.1-0.3 GB of margin, not 0.6 GB.
  - Evidence: ESTIMATED sum of measured sizes and ratios:
- main v3: 0.606
- twin v3: 0.4523 x 1,341,183,279 = 0.607
- fit.ckpt gz: 0.105
- one copy of c06c061's results, gz: 0.293
- kept results: about 0.1
- classes: 0.01
That is about 1.72 GB at the best ordering. If the replay's wide and sink outputs are also at rest, it is about 1.98 GB.
The results-only working papers' own model R, with the same v3, gzip, one-copy and slim changes, puts S07-S16 at 1,833,308,147-1,896,670,177 B, a 103 MB margin (disk/2026-10-18-results-only-working-papers.md:738-748). Lines 673 and 678 list the steps that need the twin and its lifetime.
- [minor] **Rounds and DISK_2GB: with no store, extra time is about 48 x 1,721 s = 22.9 h "on a quiet machine". DESIGN's T is 1,500-2,000 s a round on a quiet machine.**
  - Problem: On a nearly quiet machine the measured rebuild is cheaper than that. 0.17 CPU-s a tree comes from loaded runs and from tree builds mixed in with split valuation. The honest range is +16 to 25 h, and 23 h sits near the top of it. On the other side, the fanless MacBook Air's throttling under sustained load is unmeasured, so a long-run T may be higher than my 20 s bursts.
  - Evidence: MEASURED with SpeedProbe and MaskProbe: 6 threads building PlayerTree take 0.0196-0.0227 s of wall time a tree (CPU 0.114-0.131 s a tree) at load 1.5-4. For 60,743 trees that is T = 1,189-1,379 s, and 48 rounds come to 15.9-18.4 h.
The round-0 tree CPU in the dry runs was 0.129-0.186 s a state (dry-run-*.txt, round table), at loads of 4-9 and mixed with splits. That gives T of about 1,300-1,900 s (ESTIMATED).
The machine is a MacBookAir10,1, fanless (sysctl hw.model).
Rounds 10, 22, 25 and 27 walked only 52,611 states, about 1% less.
- [minor] **DESIGN: a store of the 2-up row's R cells in the 0.8 GB left over saves 0.5-0.7 h.**
  - Problem: The whole 2-up row of R cells does not fit in 0.8 GB. Only about 60% of the states' rows fit.
  - Evidence: ESTIMATED: about 0.89 x 2,962 feasible cells (design-probe probe1.txt, up 2) = about 2,640 cells x 8 B = 21 KB a state, x 60,743 = about 1.29 GB. N-only would be 2,013 cells, about 0.98 GB, which still does not fit.
- [minor] **DESIGN: running the replay's hooks inside the main run would more than pay back the no-store cost (about -13 h).**
  - Problem: This saving does not depend on the tree store. It is just as available with the store. It removes the replay; it does not cut the cost of rebuilding trees. Next to the best design that keeps a store, no store still costs 18 rebuilds x f x T on the main run.
  - Evidence: Replay log: 27 last rounds take 15,145 s and 27 scorings take 38,487 s (MEASURED sums from replay/c06c061-0e0920d.log). Both are paid with or without trees.bin.
- [minor] **All areas: the main run took 19.8 h on the wall.**
  - Problem: The internal timers come to 19.7-19.8 h, but by the clock the run took 24.7 h. The passes alone took about 10.7 h by the clock against 6.88 h timed, which points to about 4.9 h of sleep. The compute figures stand, but a full run needs the machine to stay awake longer than 19.8 h suggests.
  - Evidence: MEASURED: log line 2 says started 2026-10-06T03:10:12, and the last line says finished 2026-10-07T03:51:19. fit.ckpt's mtime is Oct 6 13:53, and part-0-0-folds 13:59. Rounds 0-5 sum to 24,759.6 s.
- [minor] **DESIGN: the three things built into the mathematics need RAM or CPU, not disk.**
  - Problem: On this 16 GB machine, with a 10 GB heap, RAM pressure turns into swap on the same SSD. Swap is disk use the run causes, it is not counted, and it is unmeasured during a run.
  - Evidence: MEASURED: sysctl vm.swapusage shows 1,694.81 MB in use with no engine run going. hw.memsize is 17,179,869,184.
- [minor] **Ram-disk: converting the state file to v3 peaks at about 1.95 GB (v2 + v3).**
  - Problem: Under a 2.0e9 cap that also holds the kept results, the conversion goes over. It is only safe if done before the cap applies, or if StateRun writes v3 directly.
  - Evidence: 1,340,719,767 + about 606,400,000 (E) + 0.109 GB kept (DISK_RESULTS_ONLY.md) = about 2.056 GB.

### Upheld

- Round structure and times (MEASURED from runs/full-index/full-c06c061.log by summing log lines): 49 rounds. Rounds 0-5 took 24,759.6 s. There were 43 last rounds (rounds 6-48), whose last-round times sum to 7,560.2 s, and scoring took 38,717.2 s. 48 rounds read trees. The last rounds ran 10 or fewer strategies, 94.0-249.8 s each.
- The disk-cap comparison: the no-tree round took 8,120.8 s for 4 strategies and 3,278,544 splits. The trees-read round took 2,082.4 s for 8 strategies and 1,756,660 splits (both logs). The rounds investigator is right that this mixes split count, load and trees. The 2gb working papers measured a load average of 9.7/16.6/33.9 during the run.
- Rebuilt and regrouped trees are bit-identical: I measured 0 differing bits in about 40M cells at 6 threads (SpeedProbe and MaskProbe). The arithmetic matches ExactRound.java:582-599 term by term: the same factor order, the same per-shoe addition, and the same zero skip.
- R (the cells read by the contested walk) does not depend on the play, at 89% of feasible cells, so it is representative of real fitted plays. The poison test covered the real fitted plays of the null count, Hi-Lo, KISS I-III and Red Seven. The N figure (65-70%) was measured on the 372 final plays from fit.ckpt. It is an upper bound and does not cover the intermediate plays of rounds 1-5, but the design uses R there.
- The tree cost cannot be removed under 2 GB without losing bits: N-only takes about 10 GB, values compress at most about 1.3x, RAM cannot hold them next to the 4.4 GB the run already holds, and the sweep is cyclic, so a smaller cache saves time only in proportion to its size (about 1.54 h a GB at T=1,721 s, 1.1-1.2 h at my measured T).
- Prepared.bytes is 34.1 B a part + 16 B a pair part for untagged counts (FullIndexFit.java:2617-2619), plus 12 B a part for tagged counts. ByKey and InsuranceParts are built only while an item is scored (SystemScorer.java:2608, 2679). Batches form against room=5,683 MB (FullIndexFit.java:2404-2416). The 43-to-13 packing estimate follows from the model if (a), (b) and (c) are all done; (b) alone gives about 7 items a batch.
- The sparse Hit values stay bit-identical, because parts that do not depart take st.move's own double (FullIndexFit.java:869, 912). Items do not depend on batching: the replay matched 93 of 93 items with 27 batches against 43.
- Scoring reads no trees (grep of SystemScorer and Bootstrap).
- Main-run disk arithmetic: today 1.341+0.184+0.431+0.431=2.387 GB. With v3, gzip and one copy of the results: 0.606+0.105+0.293(+0.01 classes) = 1.004-1.014 GB, and 0.816 GB during the passes. The ram-disk table's other rows add up.

## The design (before the judges)

**Answer.** No. The program does not need more than 2 GB of disk at any point. The one thing over 2 GB is trees.bin, the 14.9 GB tree store, and it is only a cache. Each round already works like a sliding window: it takes one state at a time, builds or reads that state's tree, walks it and drops it. Three things are built into the mathematics. (1) The fit is policy iteration: after every sweep over all states it refits every play from sums over all of them, so the rounds must run one after another. That means each state's tree is needed once per sweep, 48 times in all, with a full sweep in between each use. (2) The part of each tree the walks use is about 165 KB of doubles that do not compress (1.06 to 1.3x without loss), or 10 GB for all states. So an exact cache under 2 GB holds only a small share of the trees, and because the sweep is cyclic it saves time only in proportion to its size. (3) The bootstrap needs one scoring item's per-part values all at once. All three need RAM or CPU, not disk. Everything else over 2 GB today is a storage choice: the raw state file (1.34 GB, about 0.61 GB stored without loss), a second copy of the results, uncompressed checkpoints, and oversized collectors and replay records. Fix those and the main run peaks at about 1.0 GB (estimate; 1.1 to 1.5 GB at the later stages). Every figure stays the same to the bit; only the files' SHA-256 hashes change.

The one real trade is keeping the tree store versus building the trees again in every round. Done with today's code (--trees none), that adds about +23 h to the 19.8 h main run on a quiet machine (estimate). Two changes that keep every bit cut most of it. First, a cheaper build: the dealer batch regrouped, and only the cells the walks read, which do not depend on the play. That costs about 0.50 to 0.56 of today's build (measured on a prototype). Second, fewer rounds that build trees. I measured today that under the 372 real fitted plays only 14.5% of scoring parts depart from the chart (78 states, 24,056 strategy-state pairs). So a scoring batch can hold Hit values only for those parts, about 13 to 15 items instead of 4 to 5, and the 43 last rounds drop to about 13 (estimate from the heap model, not measured). Together: about +4 to 6 h on the main run (up to +9 h if the heap model is optimistic), and about +8 to 12 h over the whole pipeline from the seeds instead of +38 to 51 h (estimates). Running the replay's hooks inside the main run would more than pay that back (about -13 h), but that is untested. Nothing in the design changes the order in which anything is added up, so no 1e-12 caveat is needed.

S = the main state file stored without loss (v3), about 0.61 GB (ESTIMATED from a measured 0.4523 ratio on 300 records). T = one round's full tree rebuild on 6 threads on a quiet machine, 1,500-2,000 s (ESTIMATED).

### What every round needs, and what is only a choice
- **The state records** (cards, legal move values, chance, insurance): truly needed. They take 1.65 GB in RAM. The file is read once, in 2.2 s (FullIndexPilot.java:648-672), so on disk it is needed only between stages: 0.61 GB as v3.
- **Stand values**: about 20,600 needed cells (N) or 26,500 read cells (R) a state. They are a fixed function of the dealt cards and take 0.086-0.115 CPU-s to build. A cache, not a need.
- **Plays and sums** (0.71 GB) and **split values** (1.94 GB, recomputable): RAM only.
- **Per-part values of a scoring item**: needed all at once by the bootstrap's refit on each resample. Per-resample sums would be larger (ESTIMATED: tens of keys × 550 deals × moves × 1,000 resamples, several GB an item) and would change the order of summation. RAM; this is why there are last rounds.
- **fit.ckpt and parts**: only for resuming. 0.105 and 0.293 GB gzipped (MEASURED ratios).

### Stage by stage
| Stage | Disk peak | Time against today | Exactness |
|---|---|---|---|
| StateRun (only if S is not kept) | S, written block by block: 0.61 | 13.0 h, unchanged | same bytes only if the three original builds are rerun in order (untested) |
| Main run | S 0.61 + fit.gz 0.105 (0.21 while it is rewritten) + parts.gz ≤0.29 + classes 0.01 = **1.0 GB** (0.82 during the passes); no trees.bin, no results.ser | 19.8 h → **+4-6 h** (E) | bit-identical |
| Step 7 save | about 1.15 | unchanged, no trees | bit-identical |
| Step 8 | about 1.2 (E) | 5.3 h → +1.7-2.7 h (E) | bit-identical |
| Replay, 93 items | 1.0-1.1 with gzip (E) | 14.9 h → +1.5-2.2 h; merged into the main run, about -13 h (E, untested) | bit-identical, checked by a per-item digest |
| PageLook C+D | 1.0-1.1 (E) | 3.75 h → +0.4-1.1 h | bit-identical |
| KeyEdgesRun a / a′ / report | 1.15 / 1.20 / 0.3-0.5 | unchanged | bit-identical |
| Simulator part 2, RiskReport | under 0.2 above inputs (E) | unchanged | bit-identical |
| At rest | results 0.1-0.3 GB, plus S 0.61 GB if Justin keeps it to save 13 h a regeneration | | |

The later-stage peaks are the stages investigator's figures minus 0.735 GB for S. When a stage combines earlier outputs, the margin is about 0.6 GB.

### Each choice against today's run with the store
| Choice | Main-run time | Basis |
|---|---|---|
| v3 states, gzip, one copy of the results, slim files | 0 (seconds) | MEASURED ratios |
| No store, code as it stands | +20-27 h (48 T) | E |
| + regrouped build on the R cells in rounds 1-48 (full build in round 0 for the perfect row) | +10-15 h (24-27 T) | ratio MEASURED (R 0.889 × regrouped 0.566-0.625), hours E |
| + packed batches, 43 → about 13 last rounds | **+4-6 h** (9-10 T); +6-9 h if the real heap is double the model | departures MEASURED, K ESTIMATED |
| + a store of the 2-up row's R cells in the 0.8 GB left over | -0.5-0.7 h | per-up-card CPU MEASURED (A 20.6%, 2 21.8%, 10 1.7%), saving E |
| Hooks inside the main run (from the seeds only) | -13 h on the pipeline | E, untested |

Building only N doesn't pay in packed batches: a ~30-strategy batch spends about 15 ms a state on structure to save 3-6 ms. R needs no pass per strategy.

### Why every figure stays to the bit
- The rebuilt, regrouped and R-only trees compute each cell with the same multiplications and additions, in ExactRound's order. Measured: 0 bits differ in about 11M cells, and the poison tests passed.
- A sparse Hit array returns st.move's own double for parts that do not depart (FullIndexFit.java:869).
- Items do not depend on how they are batched: the replay matched 93 of 93 items to the bit with 27 batches against 43.
- The threads stay at 6.

Rejected because they are not bit-identical: per-node partial sums, trees updated from one state to the next, block-local fits, and windowed partial sums. The fit's sign, argmax and zero tests mean "within 1e-12" in the arithmetic does not bound how far a figure moves.

### Code changes, in build order
1. **StateStore v3 and a converter**; StateRun writes v3. Test: decoding the whole file gives SHA-256 ed9e9fb4…6739; random records come back exactly, NaN bits included.
2. **gzip in FullIndexCheckpoint**, detected by its magic bytes. Test: the interrupted-resume properties of FullIndexFitTest still pass, and raw files still read.
3. **One copy of the results**: readers merge the parts. Test: c06c061's 186 parts deep-equal results.ser and serialize to 8ba1412b.
4. **New DiskBudget estimates** (ITEM 10 MB against about 1.6 MB measured). Test: a dry run is accepted at CCEC_DISK_BUDGET_GB=2, and its measured peak stays under the estimate.
5. **The regrouped DealerOutcomes batch.** Test: every cell is raw-bit equal to outcomes(counts,…) on 1,000 or more states across the three segments.
6. **R-only trees in rounds 1 and later.** Test: a poison unit test (cells outside R set to 1e250 leave every walk output unchanged for every count, under the chart, random and fitted plays).
7. **Packed batches.** (a) Charge ByKey, insurance and the dense Hit array once per batch: they live only while one item is scored (FullIndexFit.java:2619, SystemScorer.java:2679). (b) Hold Hit only for departing parts, and expand it for the item being scored. (c) Share Parts and SplitSlots between the fold and half items of a (count, penetration). Test: expanded arrays raw-bit equal to today's; a dry run's Results bit-equal packed against unpacked (ResultDiff, seconds left out); measured heap no larger than the estimate.
8. **--trees none as the default, with the optional row store.** Test: TreeStoreTest extended to rows.
9. **The stage changes, in the stages investigator's order**: slim collectors, lean replay records with per-item parts and a digest, PageLook --trees none, extracts, removing the simulator's leftover records. Test: the report's JSON equal apart from its inputs block, and the replay digest 93 of 93.
10. **Optional: the hooks inside the main run's scoring.** Test: the hooks' outputs bit-equal to a replay's in a dry run.

**Gate before any full run:** a 6,000-shoe dry run, today's code with the store against the new code without it, with 0 differences.

Probe: `/private/tmp/claude-501/-Users-justinbondurant-Documents-docsClaude/078aba77-3392-4bd9-bf33-3d32e8794c9a/scratchpad/design-probe/` (src/DesignProbe.java, run/probe1.txt). It read 78 states and fit.ckpt read-only, 40 s of CPU at nice 15.

### Experiments still needed

- T on a quiet machine, the multiplier behind every hour figure. Run before trees.bin is deleted, with no other session running. Replay one small batch of c06c061 twice, with FullIndexReplay on classes-c06c061: once reading the store, once with --trees none. Compare the last-round wall times in the logs. Today's 8,121 s against 2,082 s was measured under load and on different strategy counts.
- Real heap per scoring item against Prepared.bytes, which decides how many items a packed batch holds. Run FullIndexDryRun on 2,000 or 6,000 shoes with -Xlog:gc*:file=gc.log. Take `jcmd <pid> GC.class_histogram` after a last round and in the middle of scoring. Compare used-after-GC with the batch's estimate. Repeat after change 7.
- The departure rate and the split chart share over more states. Extend design-probe/src/DesignProbe.java to 1,000 states (about 8 min of CPU at nice 15, read-only). Also count pair parts whose split takes the stored value (the e.chart test in FullIndexFit.record). The packed bytes per part rest on today's 14.5% from 78 states (5.0-17.9% by count).
- The regrouped build at production speed. Implement it in ExactRound.DealerOutcomes on a branch. Check raw-bit equality on 1,000 or more states across shoes 0-6649, 6650-39999 and 40000-59999. Time PlayerTree builds on 6 threads on a quiet machine, full and R-only, with per-up-card shares. The 1.6-1.75x is from a single-threaded prototype under shared load; memory bandwidth on 6 threads is untested.
- The v3 state file, decoded from start to end. Convert montreal-80.states to v3 in a temporary directory (it needs about 0.61 GB of disk, which counts toward the cap). Decode it, check that the SHA-256 is ed9e9fb4…6739, and record the size and the decode time. So far only 300 records have been round-tripped.
- The bit-identity gate for the whole redesign. Run FullIndexDryRun on 6,000 shoes with today's code and the store, and with the new code (no store, R-only regrouped trees, packed batches). Compare every Result with ResultDiff, leaving out seconds; expect 0 differences. Also record the batch count and the wall time against the estimate.
- Only if regenerating from the seeds: the hooks merged into the main run. In a dry run, run the wide, sink and export hooks inside the main run's scoring, then compare their outputs with a replay of the same dry run, to the bit.
- Swap and free space during the next long run. Log `sysctl vm.swapusage` and `df -k` every 10 minutes. Swap (1,695 MB in use today) is on the same SSD, and DiskBudget does not count it.

## Investigator: rounds

**Verdict.** Needing more than 2 GB is not built into how the rounds depend on each other. It is a choice between storage and speed. The 6 fitting rounds really are a global barrier: each round refits every strategy from sums over all of its training states. But each round already reads the states in one pass and keeps nothing per state for the next round, except two caches: the player trees on disk and the split values in RAM. Both are fixed functions of a state's dealt cards. So "one window at a time" is already how every round works. What cannot be avoided is this: reusing trees between passes needs about 10 to 15 GB. No exact summary is much smaller (the walk reads 85% of each tree's values; the best lossless compression measured keeps about 77%). And every tree is reused only once per pass, after all 60,743 states, so a smaller cache saves time only in proportion to its size (about 1.5 h per GB). Under 2 GB the run rebuilds trees in each of 48 passes. The figures stay the same to the bit (measured). The cost is about +23 h on a quiet machine (estimate). Packing more items into each scoring batch and building only the values the walk reads could bring that to maybe +15 h.

## Bottom line

Making the rounds into a sliding window would not help. Each round already reads the states once from start to finish and keeps nothing per state. The rounds have to run one after another, but nothing they pass on to each other takes disk. The only per-state data reused from one pass to the next is the player tree, a cache of a fixed function of each state's dealt cards. Keeping it costs about 10 to 15 GB. Rebuilding it costs about +23 h. That is a choice between storage and speed.

## What a round reads and adds up

Per state, from RAM: the stored move values, chances, naturals, dealt cards and insurance values. From the tree: stand values (`trees.of`, FullIndexFit.java:2023). Split values come from ExactRound and the dealt cards (FullIndexFit.java:1790), cached in RAM. The state file is read once at load, so `trees.bin` is the only per-state disk read.

Per strategy, a round adds up three things over its training states:
- `w x reach x (V_stand - V_hit)` per (key, cell);
- the Hit and Split sums per (group, deal);
- the training EV (FullIndexFit.java:800-932).

## Why round r+1 needs round r

At the end of a round every strategy refits:
- later stands = the sign of each pooled (group, cell) sum (FullIndexFit.java:1184);
- first moves and rankings from the pooled sums (989-992);
- the later groups once, from pass 0's key mass (984).

Round r+1's walk at every state depends on all three:
- the reach roots (813);
- the stands at every node (687-699);
- the post-split play (710-723).

So any one state's work in r+1 depends on sums over every training state in r. This is policy iteration over cells pooled across all states. A block of states run through all rounds alone would fit a different play.

## Passes in a full run (full-c06c061.log)

| Passes | Count | Trees | Wall |
|---|---|---|---|
| Fitting rounds | 6 (0-5; the cap is 6 + 2 clean-ups) | all; round 0 builds them | 24,760 s |
| Last rounds | 43 (6-48), one per scoring batch | all | 7,560 s |
| Scoring | 186 items x 1,000 resamples | none (values in RAM) | 38,717 s |

The run already puts all 372 strategies into one pass per round, so the fitting cannot be compressed further. The 43 last rounds come from the heap: each batch's values must sit in RAM until scored.

## Could something smaller stand in for the tree?

Probes were run on 149 states.
- **Reachable values (MEASURED):** the contested walk reads 2,709 of 3,061 hands per up-card: 689 contested hands and 2,020 chart-stand children. Whatever the play, every contested node may hit, and every start's V_hit feeds the first-decision sums. The refit is not a threshold rule: each (group, cell) is set on its own by its sign.
- **Size (MEASURED):** that is 26,118 values a state, 85% of the record: 12.69 GB against 14.88 GB. A tree that holds only them gives identical walks to the bit (0 of 1.42M part Hits and 0 of 11.8M decisions differed, over 5 counts and 3 plays). Building only them saves 11% of the CPU.
- **Lossless compression (MEASURED):** byte-shuffled xz keeps 77.4%. Needed-only and compressed comes to about 9.8 GB (ESTIMATED).
- **A 1e-12 summary (MEASURED size):** one fixed partial sum per node takes 113 KB a state, 6.87 GB. It is not bit-identical, and it costs a full build.
- **A table of decisions:** 290 to 595 distinct (spot, key) decisions a state, so 2^290 or more plays. Infeasible.
- **The cache pattern:** each tree is reused once per pass, after all 60,743 states. A cache smaller than the whole set saves time only in proportion to its size, about 1.5 h per GB (`treesToKeep` already does this).

## Could rounds be fused?

- The fitting rounds cannot be: each one needs the global refit before it.
- The last rounds could be, if RAM allowed. Every play is known once fitting ends, but writing every strategy's final values to disk would take about 100 GB (ESTIMATED).
- **Bit-identical ways to cut passes** (items do not depend on order or batch makeup; each strategy's sums are thread-ordered):
  - Count ByKey and the insurance parts once per batch rather than per item. About 1.4x the items per batch, 43 to about 30 passes (ESTIMATED, heap not measured).
  - Do the final walks of converged items during fitting rounds 3-5, in the about 3.7 GB the passes leave free. Up to 3 fewer passes.
  - Hold sparse Hit values. The gain depends on a departure rate I did not measure.

## Under 2 GB

With no store, extra time is about 48 x 1,721 s = 22.9 h (ESTIMATED from 0.17 CPU-s a tree on 6 threads of the M1). Needed-only builds and about 30 last rounds would bring it to about 15 h. The 8,121 s against 2,082 s measured today mixes three causes: the no-tree round also valued 1.5M more splits (about 1,600 s), the trees took about 1,700 s, and the load the rest.

**Exactness:**
- Rebuilt and needed-only trees: bit-identical (measured).
- Anything that adds sums in another order: only "within 1e-12" in practice. The refit takes signs and argmaxes, so a near-tie can flip a cell, and that is not bounded by 1e-12.

Probes are in `/private/tmp/claude-501/-Users-justinbondurant-Documents-docsClaude/078aba77-3392-4bd9-bf33-3d32e8794c9a/scratchpad/rounds-probe/` (`src/`, `probe1-4.txt`).

### Facts

- MEASURED (code): a round reads per state only RAM data: st.move, st.chance, st.naturals, st.dealt, the insurance values, and the tree from trees.of (FullIndexFit.java:2023). Split values come from ExactRound and the dealt cards, not the tree (FullIndexFit.java:1790), and are cached in RAM between rounds. The state file is read once at load (FullIndexPilot.load), and trees.bin is the only per-state disk read during the run.
- MEASURED (code): what round r+1 takes from round r is all global. (1) The later-play groups, grown once from pass 0's key mass over all training states (FullIndexFit.java:984, 1147-1161). (2) The per-(group, cell) sums of w x reach x (V_stand - V_hit); stands = sign (1184). (3) The per-(group, deal) Hit and Split sums: leanFit gives the first moves and rankings (989-992). Those decide reach roots (813), the walk's stands (687-699) and the post-split play (710-723) at every state. So a block of states cannot go through all rounds alone.
- MEASURED (runs/full-index/full-c06c061.log): 49 rounds. Rounds 0-5 are fitting, walk all 60,743 states and took 24,760 s; round 0 built every tree. Rounds 6-48 are 43 last rounds, one per scoring batch, and took 7,560 s. Scoring of 186 items x 1,000 resamples took 38,717 s and reads no trees, only per-part values in RAM. Total 19.8 h. 48 rounds read trees.
- MEASURED (probe TreeSubsetProbe, 149 states of shoes 0-149): the contested walk reads stand values of 2,709 of 3,061 hands per up-card: 689 contested hands plus 2,020 chart-stand children (hard 17-21, soft 19-21). Counting only nodes reachable from a start: 2,452. The every-node walk (perfect row, round 0 only) reads 3,007.
- MEASURED (same probe): a state holds 29,224 feasible stand values of 30,610 slots. The contested walk needs 26,118 a state (85.3% of slots; min 17,152, max 27,090). That is 208,944 B a state against 244,928 B, or 12.69 GB against 14.88 GB for all states.
- MEASURED (same probe): a tree holding only the needed values gives the same walks to the bit. 0 of 3,891,586 stand values differed; 0 of 1,419,420 part Hit values and partDeparts flags; 0 of 11,781,090 (spot, key, reach, gap) decisions; terminal mass identical. Counts tested: Hi-Lo, Red Seven, Omega II, KISS I, BRH-I; plays: the chart and two arbitrary plays.
- MEASURED (same probe, one thread, nice 15, load about 3.7): build CPU a state is 0.0973 s for a full tree and 0.0867 s for needed values only (89.1%). Same bits, because the batched dealer computation adds each shoe's terms in the same order independently (ExactRound.java:560-569).
- MEASURED (TreeCompressProbe): lossless compression of stand values, 149 states, 34.8 MB. Deflate-9: 92.7%. Byte-shuffled + xz -9: 77.4% (26,951,636 B). Zstd -19: 78.4%. ESTIMATED: needed-only + xz gives about 0.894 x 0.774 x 14.6 GB = about 9.8 GB for all states.
- MEASURED (SummaryCountProbe): a summary with one fixed partial sum per visited node plus each contested hand's stand value takes 7,465 + 6,676 doubles = 113,134 B a state, 6.87 GB in all. It changes the order of summation, so it is not bit-identical, and building it costs a full tree build.
- MEASURED (DecisionCellProbe, 100 shoes): distinct (spot, key) decisions one state's contested walk asks: Hi-Lo 473 (272-893), Omega II 290 (164-522), KISS I 595 (426-632). A per-state table of walk outputs over plays would need 2^290 or more entries.
- MEASURED (code): the refit sets each (later group, cell) on its own by the sign of its sum (FullIndexFit.java:1172-1189). It is not a threshold rule. Every contested node can hit under some play, and every start's V_hit feeds the first-decision sums, so all reachable children's stand values are needed.
- ESTIMATED: rebuilding trees in 48 rounds costs 48 x 60,743 x 0.17 CPU-s / 6 threads = about 82,600 s = 22.9 h on a quiet machine. 0.17 s is TreeStore.java:17's figure; the dry runs give 0.15-0.19 s on 6 threads of an M1 with 4 performance and 4 efficiency cores (sysctl). About 1.54 h is saved for each GB of tree store kept (treesToKeep, FullIndexRun.java:372-379).
- ESTIMATED: the disk-cap comparison (8,121 s with no trees against 2,082 s) mixes three causes. The no-tree round valued 3,278,544 splits, the other 1,756,660. At about 6.3 ms CPU a split (round 0: 9,096 s x 6 x 0.66 / 5.74M), the extra splits come to about 1,600 s, the trees about 1,700 s, and load the rest.
- MEASURED (log line 4 and code): during the passes the heap held 2.24 GB of states, 0.71 GB of plays and 1.94 GB of split values, against 8.59 GB allowed, so about 3.7 GB is free. After the passes a batch may hold 5,683 MB. Prepared.bytes charges each item 34.1 B a part + 16 B a pair part (FullIndexFit.java:2619). Of that, ByKey (8 B a part) and the insurance parts (2.1 B a part) exist only while that one item is scored (SystemScorer.java around 2607-2680), and items in a batch are scored one after another.
- MEASURED (code and log): items are independent of order and of batch makeup. A resample's figures depend only on its own weights (SystemScorer.java:2533-2535). Each strategy's sums are added thread by thread over a fixed split of the shoes (FullIndexFit.java:1950). The disk-cap replay with no tree store matched 416,114 and 829,436 numbers and 60,743 perfect values to the bit (DISK_CLEANUP.md:65-69).

### Options

- **No tree store: rebuild every tree in each pass (existing --trees none)**. Disk: -14.88 GB; nothing per state at rest beyond the state file. Time: about +23 h on a quiet machine (48 x about 1,721 s), more under load. Exactness: bit-identical (TreeStoreTest; disk-cap replay 1,245,550 numbers + 60,743 perfect values). Effort: none, it exists. Confidence: high on exactness, medium on time.
- **Partial tree store sized to whatever the 2 GB budget leaves (existing treesToKeep)**. Disk: uses the leftover room, about 0.3-1.0 GB during the passes depending on the state-file format, less as parts pile up. Time: about -1.5 h per GB kept. Exactness: bit-identical. Effort: none. Confidence: high.
- **Build only the stand values the contested walk reads; a full tree only in round 0 for the perfect row**. Disk: 0 (or 12.69 GB instead of 14.88 GB if stored). Time: -11% of rebuild CPU, about -2.5 h of the 23 h. Exactness: bit-identical (measured on 149 states, 5 counts, 3 plays). Effort: small: a PlayerTree constructor that skips unread hands, a Trees implementation, a test. Confidence: high on exactness, medium on the saving under 6-thread load.
- **Count ByKey and the insurance parts once per batch, not per item, so each final batch holds about 1.4x the items**. Disk: 0. Time: 43 to about 30 final passes; about -6 h with no store (12 fewer rebuilds); also faster with the store. Exactness: bit-identical (items independent of batch makeup). Effort: medium: measure the real peak heap of a batch first; risk of running out of memory. Confidence: low-medium (estimate not measured).
- **Do the final walks of already-converged items during fitting rounds 3-5, in the about 3.7 GB the passes leave free**. Disk: 0. Time: up to 3 fewer passes, about -1.4 h with no store. Exactness: bit-identical. Effort: medium: scoring out of count order, holdFinal logic, checkpoint/resume. Confidence: medium-low.
- **Hold Hit values only for parts that depart from the chart (the others are already the stored value, FullIndexFit.java:869), so batches pack more items**. Disk: 0. Time: fewer final passes; how many is unmeasured (depends on the departure rate under the fitted plays, not probed). Exactness: bit-identical. Effort: medium-high. Confidence: low.
- **Store needed-only trees losslessly compressed**. Disk: about 9.8 GB (estimate), still far over 2 GB. Time: like the store, plus decompression. Exactness: bit-identical. Effort: medium. Confidence: medium; does not meet the cap.
- **Per-node fixed partial sums (1e-12 summary) in place of trees**. Disk: 6.87 GB (measured size), over 2 GB. Time: none: building it costs a full tree build. Exactness: not bit-identical; within about 1e-15 per value, but the refit takes signs and argmaxes, so a near-tie can flip and move figures by more than 1e-12. Effort: high. Confidence: high that it is not worth it.
- **Per-state table of walk outputs over plays**. Disk: impossible: 2^290 to 2^595 plays a state. Time: n/a. Exactness: n/a. Effort: n/a. Confidence: high that it is infeasible.
- **Fuse all 43 final rounds into one pass by writing every strategy's final values to disk**. Disk: about +100 GB (16 B a part x about 30M parts x 186 items, estimate). Time: -42 rebuild passes. Exactness: bit-identical. Effort: high. Confidence: high that it breaks the cap.
- **Run one block of states through all rounds (a true sliding window over states)**. Disk: would need only one block's trees. Time: n/a. Exactness: changes the fit (block-local refits): figures change. Effort: n/a. Confidence: high that it cannot be exact.

## Investigator: trees

**Verdict.** For the trees, none of the disk use above 2 GB is needed by the computation. It is a choice between disk and time. trees.bin (14.9 GB) is a cache. The computation already works through states one at a time: each tree is built, used and dropped. What cannot be done in a sliding window is the fit itself: 49 rounds of policy iteration run one after another, and each round walks every state, so each state's tree is needed in about 48 rounds. The tree cannot be stored compactly. The walks need 65-70% of its cells (measured). The values are raw doubles that compress only 1.06-1.08x without loss (measured). So a store of every state's trees under 0.8 GB is impossible: it would take about 10 GB at the least. Under 2 GB, the only route is to build the trees again in every round. Two rewrites, both bit-identical (measured: 0 differing bits), make that rebuild about twice as cheap. One builds only the cells the walks need. The other regroups the dealer products. Together they cost 45-54% of today's build (measured). The extra time for a full run without a store would drop from about 23 h to about 12 h (estimate). Figures stay bit-identical, because every stand cell is still computed with the same multiplications and additions, in the same order.

## Trees: what they cost, what the walks read, what can be cut

**Probes.** These are scratch programs in `/private/tmp/claude-501/-Users-justinbondurant-Documents-docsClaude/078aba77-3392-4bd9-bf33-3d32e8794c9a/scratchpad/treeprobe/` (`src/TreeProbe.java` and `src/FastProbe.java`; outputs `run/probe1.txt`, `probe2.txt`, `probe3.txt`, `fast2.txt`). They were compiled against `runs/full-index/classes-c06c061`, whose PlayerTree and ExactRound are the same as at 089562d. Everything ran read-only at nice 15 on one thread. Together they read about 420 records of `montreal-80.states` and the 372 fitted plays in `full-c06c061/fit.ckpt`. Nothing in the repo or in runs/ was written.

### 1. Where a tree's time goes
**99.0% goes to the batched dealer distributions** (measured: 0.1138 of 0.1149 s CPU per state). That is `DealerOutcomes.outcomes(counts,…)` (ExactRound.java:570), called once per up-card from PlayerTree.java:203. The tables, gathering the shoes and `stood()` together take about 1%.

The cost is the number of dealer entries times rank factors, paid for every shoe S−H−u. There are 9,907 entries in all, from 2,112 with a 2 up down to 183 with a ten up (measured). So rows against an ace or a 2 carry about 43% of the cost (estimate). One tree took 0.086-0.115 s CPU in my probes; the 0.17 s figure is from 6 threads under a load average of 57.

### 2. What the walks read
- **The contested walk as written reads 89% of the feasible cells (R)**, whatever the play. It walks backward over every live node, reached or not (PlayerTree.java:894-942), and `decisions` reads the stand value at every reached asked node (PlayerTree.java:1050).
- **The run uses fewer cells.** Hit values are used only for parts that depart from the chart; the rest take the stored V(Hit) (FullIndexFit.java:869, 912). The gaps need stand and Hit only at reached decisions. A top-down walk that computes only those needs (N, upper bound):

| Play | Share of feasible cells |
|---|---|
| one real fitted strategy | 65-66% |
| one count's 12 strategies plus the chart | 66-67% |
| all 372 strategies | 69-70% |
| the chart's first moves only | 61% |
| pass 0's chart alone | 53% |
| round 0's perfect walk | 98% |

- **The poison test confirms both sets.** Every feasible cell outside R was set to 1e250. In 4,752 walks, including every real Hi-Lo, KISS and Red Seven play, 0 changed any output. Outside N, 0 of 3,744 changed an output the run uses.

### 3. Lazy computation, and a faster build that gives the same bits
- **A memo filled on first read, one shoe at a time, would lose the batch.** The batch is several times faster than shoe by shoe (ExactRound.java:563-565). Laziness has to work in two phases instead: a structural pass collects the needed cells, one batch fills them, then the walk runs.
- **Values stay bit-identical.** Each shoe's terms are its own, in the same order, whatever else is in the batch (ExactRound.java:565-569). Measured: 0 differing bits in about 11 M cells.
- **Cost:** building only N takes 63-68% of a full build. The structural pass costs about as much as a walk (0.49 ms per strategy at a state), so it pays only in the rounds with about 10 strategies (6-48), not in passes 0-5.
- **A regrouped dealer batch (FastProbe.fast2).** It works each entry's product once per distinct projection of the hand on that entry's ranks: about 300 tuples instead of 2,943 shoes. It still adds every term per shoe in ExactRound's order. Measured: 1.6-1.75x faster, 0 bits differ.
- **Both together: 45-54% of today's build (measured).** For a full run with no store, the extra time drops from about 23 h to about 12 h (estimate). Figures would be bit-identical to today's. Nothing here changes a summation order, so no 1e-12 caveat is needed.

### 4. A compact store under 0.8 GB: no
- N-only takes about 165 KB a state, about **10.0 GB** for every state. R-only takes about 12.8 GB.
- The stand values are raw doubles and do not compress: deflate gives 1.08x, XOR with the previous value then deflate gives 1.06x (measured).
- A 0.8 GB store holds about 8% of the states. Storing only the costly ace and 2 rows still takes about 2 GB.
- Rounding the values to fewer bits would break exactness, because the fit's sign and tie tests (a sum exactly zero plays the chart) read them.
- Keeping N in RAM would take about 10 GB, which does not fit beside the 4.4 GB the run already holds.

### 5. Sharing across states: none to be had
- In the first 300 records, 76 shoes hold 2 or more states. Those states are 4 to 318 cards apart.
- Over 56 sampled states, 1,648,275 dealer shoes (up-card, S−H−u) were asked for, and **all were distinct**.
- A memo keyed by shoe would be bit-identical, but it would never hit.
- An incremental update from one state to the next would change the arithmetic. Values would then agree only to about 1e-15, not to the bit, and could in principle flip a near-tie cell in the fit. Since nothing is shared to begin with, I recommend against it.

### What this means for Justin's question
- **The trees are a cache, not a need of the computation.** A sliding window over states is what the run already does when it builds trees as it goes. What forces a store is that the fit is 49 sequential rounds over all states.
- **Under 2 GB means building trees every round.** The two rewrites above make that rebuild about twice as cheap, and figures stay bit-identical.
- **The biggest remaining lever is outside this area.** 43 of the 48 tree-reading rounds are last rounds of scoring batches, which exist because of heap limits. That needs to be checked in the rounds and scoring area.

### Facts

- MEASURED: 99.0% of a tree's build time is the batched dealer distributions. In TreeProbe's replica of PlayerTree's constructor (PlayerTree.java:177-206), 56 states took 0.1149 s CPU each on one thread at nice 15. Of that, ExactRound.DealerOutcomes.outcomes(counts,...) (ExactRound.java:570) took 0.1138 s, table setup 0.0002 s, gathering the shoes 0.0006 s and stood() 0.0003 s. No shoe went down the slower one-shoe-at-a-time path. Command: TreeProbe montreal-80.states fit.ckpt 0,10000,...,60000 8 300; output in scratchpad/treeprobe/run/probe1.txt.
- MEASURED: one PlayerTree takes 0.086-0.115 s CPU, single-threaded under shared load (probe1, probe2, probe3, FastProbe). This matches FULL_INDEX_PLAYS.md section 6's 0.085-0.112 s. The 0.17 s in TreeStore.java comes from 6 threads under heavy load: the run log shows a load average of 57.4 at its start.
- MEASURED: the dealer's hands by up-card (DealerHands, read by reflection), as (entries, rank factors): A 1968/7068, 2 2112/7346, 3 1636/5521, 4 1225/4015, 5 938/2979, 6 679/2090, 7 514/1519, 8 375/1057, 9 277/754, 10 183/470. That is 9,907 entries in all. Every shoe in a batch pays its up-card's entries times factors.
- ESTIMATED: from that operation count, cells against an ace or a 2 up carry about 43% of the build cost while making up 20% of the cells.
- MEASURED: a state has 29,434 to 29,770 feasible stand cells of 30,610; the rest are NaN (probe1 and probe2).
- MEASURED: the contested walk as written reads 89.2-89.3% of the feasible cells (R), whatever the play, over every count. It walks backward over every live node and reads the stand value of every child it does not visit (PlayerTree.java:894-942), and decisions() reads the stand value at every reached asked node (PlayerTree.java:1050). The perfect walk (EVERY_NODE), done once per state in round 0 (FullIndexFit.java:2039, 2053), reads 98.2%.
- MEASURED: a top-down walk needs fewer cells (N). It works out only what the run uses: the Hit value of parts whose later play departs from the chart (the stored V(Hit) is used otherwise, FullIndexFit.java:869 and 912), and stand and Hit at every reached decision. Every start was treated as hitting, so these are upper bounds. N covers 65.4-66.3% of feasible cells for one real fitted strategy (372 read from full-c06c061/fit.ckpt), 66.2-67.0% for one count's 12 strategies plus the chart (at most 23,172 cells), and 69.1-70.4% for all 372 plus the chart. With the chart's first moves the figure is 60.5-61.5%, and for the chart alone (pass 0) 53.0-53.3%.
- MEASURED: building only R costs 88.7-88.9% of the full build's CPU. Building only N costs 67.7-67.9% for all strategies, or 63.3-64.5% for Hi-Lo's 12 strategies (TreeProbe build() with a mask).
- MEASURED: the regrouped dealer batch (FastProbe.fast2) works each entry's product once per distinct projection of the hand on the entry's ranks: 2,298 rank sets, about 300 tuples each against 2,943 shoes. It keeps every per-shoe multiplication and addition in ExactRound's order. Over the full tree it costs 56.6-62.5% of PlayerTree's CPU (1.6-1.75x faster). On N it costs 48.8-53.5% for all strategies and 44.7-45.4% for Hi-Lo's N (probe2, probe3, fast2.txt).
- MEASURED: bit-identity. Every cell built by the replica, the R-only build, the N-only build and both regrouped variants was compared with PlayerTree, 0 differ in any bit: 4,259,190 cells (probe1), 4,152,178 (probe2), 1,765,556 (probe3), 857,080 (FastProbe).
- MEASURED: the poison test. Every feasible cell outside R was set to 1e250. In 4,752 eager walks (CHART, random thresholds, all-stand, all-hit, and every real fitted play of the null count, Hi-Lo, KISS I/II/III and Red Seven), 0 differed in any output: partHit, partDeparts, the value and hit arrays, decisions, or terminalMass. With each strategy's cells outside N poisoned, 0 of 3,744 walks differed in an output the run uses (56 states).
- MEASURED: per strategy at a state, the structural pass a top-down walk needs costs 0.49 ms CPU, the same as the walk itself with reach and decisions (0.49 ms, probe3). So laziness pays in rounds with about 10 strategies (rounds 6-48), not in passes 0-5 with 372.
- MEASURED: lossless compression of R's cells (210-213 KB raw per state): deflate level 9 gives 1.08x, XOR with the previous value then deflate gives 1.06x.
- ESTIMATED: the smallest possible store is N-only, about 20,600 cells x 8 B = 165 KB a state, or 10.0 GB for all 60,743 states. R-only is about 12.8 GB. A 0.8 GB store would hold about 4,800 states' N cells (8% of the states).
- MEASURED: no sharing across states. The first 300 records span shoes 0-305; 76 shoes hold 2 or more states (192 of the 300 states). Same-shoe states are 4 to 318 cards apart. Over 56 sampled states, 1,648,275 dealer shoes (up-card, S-H-u) were asked for and all 1,648,275 were distinct; over 28 states in probe2, 833,568 of 833,568 were distinct.
- MEASURED: rounds 6-48 of full-c06c061.log ran 10 or fewer strategies each, in 94-250 s wall with trees read from the store. 48 rounds read trees.
- ESTIMATED: without a store, each round adds about 60,743 x 0.17 s / 6 threads = 1,721 s (0.48 h), so about 23 h over 48 rounds. With the regrouped build in rounds 1-5 and regrouped plus needed-only in rounds 6-48, the extra is about 43 x 0.48 x 0.50 + 5 x 0.48 x 0.6 + 0.6 h of structural passes, so about 12 h.

### Options

- **Build only the needed cells (lazy, in two phases): a structural pass per state and round collects the cells the round's strategies need (departing parts plus reached decisions), then one batched build fills them, then the walk runs as now. Round 0 keeps the full build for the perfect row. Counted must take feasibility from the card counts instead of NaN (PlayerTree.java:700).**. Disk: Removes the need for trees.bin (14.9 GB to 0), the same as --trees none.. Time: Build CPU falls to 63-68% of today in the rounds with about 10 strategies (measured), plus 0.5 ms per strategy at a state. Alone: about +16 h per full run with no store, against +23 h (estimate).. Exactness: Bit-identical. Each needed cell goes through the same per-shoe arithmetic, which does not depend on which shoes share a batch (ExactRound.java:560-569). Measured 0 differing bits, and 0 of 3,744 poison checks failed.. Effort: Medium: a walk split in two phases, a union across the round's strategies, a test against the full tree.. Confidence: High on the cell fraction and on bit-identity; medium on the net wall-clock gain..
- **Regroup the dealer batch: a new method in ExactRound.DealerOutcomes works each dealer entry's product once per distinct projection of the hand on the entry's ranks (an index made once per engine, about 14 MB), then adds the terms per shoe in the same entry order.**. Disk: None by itself.. Time: The full tree takes 57-63% of today's CPU, 1.6-1.75x faster (measured, scratch prototype, not tuned).. Exactness: Bit-identical: the same factors in the same order and the same per-shoe summation order. Measured 0 of 857,080 cells differ, and 0 in the combined probes.. Effort: Small to medium: one method and a test of bit equality with outcomes(counts,...).. Confidence: High; the gain in production may differ by about ±20%..
- **Combine both, and run with no tree store.**. Disk: 14.9 GB to 0, so trees leave the 2 GB budget entirely.. Time: Build CPU is 45-54% of today (measured). Extra time per full run is about 12 h instead of about 23 h (estimate).. Exactness: Bit-identical (measured).. Effort: Medium.. Confidence: Medium on the hours, high on exactness..
- **Store the trees compactly: only N or R cells, delta or XOR encoded and deflated, or only the costly ace and 2 up-card rows.**. Disk: At least 10.0 GB for N-only (165 KB a state) and 12.8 GB for R-only. Compression gives only 1.06-1.08x. The ace and 2 rows alone, N-only, are about 2 GB. A 0.8 GB store covers about 8% of the states.. Time: Saves about the share of the build its cells cost: about 8-16% for a 0.8 GB store.. Exactness: Bit-identical if the raw doubles are stored.. Effort: Medium.. Confidence: High that it cannot fit 0.8 GB; not worth doing..
- **Share dealer distributions across states, or update them incrementally between states of the same shoe.**. Disk: None.. Time: About none: 0 repeated dealer shoes in 1.65 M (measured). Same-shoe states are at least 4 cards apart, so a hand can almost never absorb the difference.. Exactness: Memoising by (up-card, shoe) would be bit-identical, but it finds nothing to reuse. An incremental update changes the arithmetic, so results would agree only to about 1e-15, and the fit's sign and tie tests could in principle flip a cell.. Effort: Medium.. Confidence: High: reject..
- **Keep N-only trees in RAM instead of on disk.**. Disk: 0 on disk, unless the RAM pressure turns into macOS swap on the same disk.. Time: As fast as the store.. Exactness: Bit-identical.. Effort: Small.. Confidence: High that it does not fit: about 10 GB against a 10 GiB heap that already holds about 4.4 GB..
- **Outside this area: fewer rounds that read trees. 43 of the 48 are last rounds of scoring batches with 10 or fewer strategies (full-c06c061.log, rounds 6-48), and the batches exist for the heap. Merging them would cut the rebuilds about 7x, but only if scoring could stream instead of holding each item's values.**. Disk: None directly; it makes 'no store' cheap.. Time: Rebuild overhead could fall from about 23 h to about 3 h (estimate), if feasible.. Exactness: Bit-identical only if per-shoe summation orders are kept.. Effort: Large: a redesign of scoring.. Confidence: Low; I did not check it. It belongs to the scoring and rounds area..

## Investigator: ram-disk

**Verdict.** For this area, needing more than 2 GB is not built into the algorithm. It comes from how things are stored. The full run reads the state file once, in 2.2 s, works only from RAM after that, and never reads the file again unless it is resumed. Everything else it writes exists so a run of 20+ hours can resume (fit.ckpt and the parts), or is a second copy (results.ser duplicates the parts). Three changes bring the run's disk peak from 2.386 GB to about 1.0 GB: store the states losslessly compressed (about 0.606 GB; measured ratio 0.4523, the same in all three segments, every byte coming back unchanged), keep one copy of the results, and gzip the checkpoints. RAM does not change: the states take about 1.65 GB of the 10 GB heap whatever the file format. Every figure stays the same to the bit, because the decoded inputs are byte-identical and no sum is reordered. Inside the run, the only thing over 2 GB is the 14.9 GB tree store. It is a speed choice that belongs to another area, and it cannot be held in RAM on a 16 GB machine. Making the states in RAM and never writing them would save another 0.6 GB. But every later step that needs them would pay 13 h again (about 215 h from the seeds), the states could no longer be resumed after a crash, and the figures would not be bit-identical unless the three original builds were rerun in order. That has never been tested.

## RAM versus disk for the full run and the states

**Short answer:** no. The run does not need more than 2 GB of disk to be efficient. It reads the states once and then works from RAM. Its other files exist so that a run of 20+ hours can resume, and one of them is a second copy. The only file over 2 GB is the tree store, which saves time (another area).

### (1) Does the run touch the state file again?

No.
- **One read.** `FullIndexPilot.load` (FullIndexPilot.java:648-672, called at FullIndexRun.java:252) reads the file once, 256 records at a time: "states read in 2.2 s" in the log.
- **After that, only its name.** The file appears only as a path/size/mtime string built at start (FullIndexRun.java:392-395).
- **No file I/O while working.** SystemScorer, FullIndexFit, PlayerTree, SplitValues and Bootstrap contain no file I/O at all (grep). TreeStore builds from the in-RAM states.
- **Only a resume** reads the file again.

**So a compressed file works.** I checked it with a scratch probe (`RamProbe.java`, compiled against classes-7c1d40e, nice 15) on 300 records spread over all three build segments (records 0, 20,000 and 50,000 onward):
- The encoding keeps only the legal slots, groups their bytes by plane, and compresses with the JDK's Deflater. It gives **0.4522 / 0.4526 / 0.4521** per segment (MEASURED).
- Every record came back **byte-identical**.
- All 309,000 slots that are NaN by rule, and the 162 NaNs in legal slots, hold the canonical NaN.
- The whole file would be about **0.606 GB** (ESTIMATED).
- Decoding runs at 212-341 MB/s on one thread, so about 4-6 s for the whole file (ESTIMATED).

**In RAM the encoding changes nothing.**
- ScoringStates measured **27,109 B a state**, so 1.647 GB for 60,743 states. That is more than the file, because `chance[]` is derived and the NaN slots are kept. The dry runs said 1.64 GB.
- The log's heap figures stay as they are: 2.24 GB held before the passes; 7.43 of the 8.59 GB allowed; scoring batches up to 5,683 MB.

### (2) What is on disk only so the run can resume

| File | Why | Raw | gzip-6 (MEASURED) |
|---|---|---|---|
| fit.ckpt | Rewritten after each pass round through `.tmp` (FullIndexCheckpoint.java:176-187), so two copies exist for a moment. A resume past the passes reads it (FullIndexFit.java:2292) | 0.184 | 0.105 |
| 186 parts | Written as each item is scored (:2463). The run keeps them in RAM (:2465-2469) and reads them only on a resume (:2343) | 0.431 | 0.293 |
| results.ser | Written from RAM at the end (FullIndexRun.java:341-350); a merged copy of the parts | 0.431 | 0.293 |
| state file | Read again on a resume | 1.341 | 0.606 (v3, ESTIMATED) |

Two ways to make this smaller:
- **One copy of the results.** Readers merge the parts. This was measured identical to the bit, and the merge re-serializes to results.ser's own SHA-256 8ba1412b, given the 6.8 KB of round timings.
- **Gzip the checkpoints.** It is built into the JDK and costs about 40 MB/s of CPU: about 11 s for all the parts.

### (3) Disk peak with only compressed states, checkpoints and results (ESTIMATED, sums of measured sizes)

| Layout (`--trees none`) | Peak |
|---|---|
| Today | 2.386 GB |
| Raw states, no results.ser | 1.955 GB |
| v3 states, raw checkpoints, results.ser | 1.652 GB |
| v3, no results.ser | 1.221 GB |
| v3, gzip checkpoints, no results.ser | **1.004 GB** (0.816 GB during the passes) |
| …plus results.ser.gz written before the parts go | 1.297 GB |

- **RAM still fits, unchanged.** The in-RAM form is the same ScoringStates. A decode buffer is about 2 MB a 100-record block.
- **Room for some trees.** About 1.0 GB would be left under 2 GB, enough for about 4,000 states' trees (6.7%), saving about 1.5 h. That needs `DiskBudget.ITEM` re-estimated first: it charges 10 MB an item, about 1.6 MB measured gzipped, so today it reserves 1.86 GB at the start.
- **Swap.** 1.83 GB of swap is in use on the same SSD right now with no engine run going (MEASURED). DiskBudget does not count it.

### (4) Making the states in RAM and never writing them

- **Disk saved:** another 0.606 GB. The run's peak would be about 0.40 GB.
- **What it costs:**
  - Valuing takes 13.0 h for the main file (46,953 s on 8 threads, three builds) and 3.2 h for the twin.
  - 14 steps need the main file and 10 need the twin. Each would pay that again: **about 215 h** from the seeds, and +26 h for the pending P1/P2.
  - A crash loses the states, because StateRun's resume is its file.
  - One JVM can't hold both state sets: 7.43 + 1.65 = 9.08 GB, more than the 8.59 GB allowed.
- **Exactness:** shoes 0-6649 were valued by a build that sums in another order and agrees only to 1e-12 (PLAN_PAGE.md:706-709). So a run on one build is not bit-identical. It is within 1e-12 only if no fitted play or best-move choice sits that close to a tie, and that is untested. Piping the three original builds in order might reproduce the file exactly, but that has never been tested.

### Exactness of the recommended changes

- **v3 states:** the decoded bytes are identical, ScoringStates is built the same way, and no sum is reordered, so the figures stay bit-identical. The file's SHA-256 changes, so ed9e9fb4 can be checked only through the decoder; an identity string must carry the v2 path, size and mtime.
- **One copy of the results:** bit-identical (MEASURED).
- **Gzip:** lossless, so bit-identical figures, but each file's hash changes.
- **Still owed:** a whole-file test that decodes the v3 file back to ed9e9fb4.
- The 1e-12 standard covers only a reordering of sums (another thread count, or scoreAll; FullIndexFit.java:2203-2212). None of the changes above reorders a sum.

### What I did

- Read-only throughout.
- Scratch files are in `/private/tmp/claude-501/-Users-justinbondurant-Documents-docsClaude/078aba77-3392-4bd9-bf33-3d32e8794c9a/scratchpad/ramdisk/` (src/RamProbe.java).
- The probe read 300 states.
- I gzipped the c06c061 parts, results.ser and fit.ckpt to a pipe at nice 15, about 26 s of CPU.

### Facts

- MEASURED (code read): FullIndexRun reads the state file's data once, through FullIndexPilot.load (FullIndexRun.java:252; FullIndexPilot.java:648-672, positional reads 256 records at a time at :665). The log says 'states read in 2.2 s' (full-c06c061.log line 2). After that the file appears only as a path/size/mtime string built at start (FullIndexRun.java:251, 276, 392-395). A grep for java.nio.file, Files. and FileChannel finds nothing in SystemScorer, FullIndexFit, Bootstrap, PlayerTree or SplitValues. TreeStore builds from the in-RAM ScoringStates. Only a resume reads the file again.
- MEASURED (scratch RamProbe.java compiled against runs/full-index/classes-7c1d40e, nice 15, read-only, records 0-99, 20,000-20,099 and 50,000-50,099, which cover all three build segments): legal slots only, byte planes grouped by slot then record, JDK Deflater level 6, 100-record blocks. Ratios per segment 0.4522 / 0.4526 / 0.4521; 6,621,600 -> 2,994,997 B overall (0.4523). Every record's 22,072 bytes came back identical (Arrays.equals true on all 300). A plain Deflate of whole records gives 0.5795; planes grouped by record then slot give 0.5253.
- MEASURED (same probe): all 309,000 rule-NaN slots (1,030 a record) and all 162 NaNs in legal slots are the canonical 0x7ff8000000000000, so decoding can put the NaNs back exactly.
- ESTIMATED: the whole main file in this encoding is about 0.606 GB (0.4523 x 1,340,719,767 = 606.4 MB). The earlier 200 MB sample with 1024-record blocks gave 0.4495, or 0.603 GB.
- MEASURED: decoding runs at 212-341 MB/s on one thread and encoding at 45-71 MB/s. ESTIMATED from that: the whole file decodes in about 4-6 s on one thread (under 1 s on 6), against 2.2 s for today's raw load. Encoding it once takes about 20-30 s of CPU.
- MEASURED (probe, after GC): a ScoringStates holds 27,109 B a state (27,154-27,212 B across the three segments), close to the analytic 27,084 B. That is move[] 22,016 B, including the 1,030 NaN slots; chance[] 4,416; insurance 456; dealt 120; the scalars 76. A Record holds 22,493 B. ESTIMATED: 1.647 GB of heap for 60,743 states, which matches the dry runs' 'the states 1.64 GB' (dry-run-6000-73df32c.txt:34). Today's load also holds the Records for a moment: 1.366 GB.
- MEASURED (full-c06c061.log line 4): 'before the passes: 2.24 GB held, about 0.71 GB of plays and 1.94 GB of split values to come, and the largest scoring item, KISS I at 80%, 2.53 GB: 7.43 GB of the 8.59 GB allowed'. Line 71: scoring batches hold up to 5,683 MB. The heap is 10,737 MB (-Xmx10g). The run had 43 scoring batches.
- MEASURED (code): parts are written as each item is scored (FullIndexFit.java:2463) but kept in RAM (:2465-2469) and read back only on a resume (:2343). results.ser is written at the end from RAM (FullIndexRun.java:341-350). fit.ckpt is rewritten after every pass round (FullIndexFit.java:2305) and marked fitted at :2317. Each write goes through fit.ckpt.tmp with an atomic move (FullIndexCheckpoint.java:176-187), so two copies exist for a moment. A resume past the passes needs it (FullIndexFit.java:2292).
- MEASURED (gzip -6 on today's files, nice 15): all 186 parts, each file on its own, 431,014,069 -> 292,903,361 B (0.6795, 11.4 s user); results.ser whole, 430,694,877 -> 292,718,942 B (0.6797); fit.ckpt, 183,689,022 -> 104,613,242 B (0.5695). Together results.ser and fit.ckpt took 14.6 s of CPU.
- MEASURED (working papers, 2gb investigator): merging the 186 parts with SystemScorer.merge gives results.ser to the bit (0 differences in about 55M numbers). Re-serialized, the merge hashes to 8ba1412b..., results.ser's own SHA-256, provided the 6,800 B of last-round RoundStats are kept.
- ESTIMATED (sums of the measured sizes above). Peak disk of one full run with --trees none: today 2.386 GB. Raw state file without results.ser: 1.955 GB. Compressed (v3) states with raw checkpoints and results.ser: 1.652 GB. v3 without results.ser: 1.221 GB. v3, gzip checkpoints, no results.ser: 1.004 GB (0.816 GB during the passes, if each round's fit.ckpt is about the final size; the dry run projected 229 MB raw). The same, writing results.ser.gz at the end before deleting the parts: 1.297 GB. States never on disk: 0.398 GB (gzip) or 0.615 GB (raw).
- MEASURED (montreal-80.log, via the working papers): valuing the main file took 26,065 + 17,238 + 3,650 = 46,953 s, 13.0 h on 8 threads, across three builds. The twin took 11,637 s, 3.2 h on 7 threads. PLAN_PAGE.md:704-709: shoes 0-6649 were written by a build whose engine sums in a different order and agrees only to 1e-12, so one build alone cannot reproduce the file.
- ESTIMATED: if the states are never written, 14 steps need the main file and 10 need the twin (results-only working papers, section 2). Valuing again for each costs 14 x 13.0 + 10 x 3.2 = about 215 h (about 9 days) more from the seeds. For the pending chain (P1 and P2) it is +26 h.
- ESTIMATED: holding the main and twin states in one JVM would add about 1.65 GB to the preflight's 7.43 GB, giving 9.08 GB against 8.59 GB allowed. The preflight would refuse (FullIndexFit.java:2536-2560).
- MEASURED (sysctl vm.swapusage, df): 1,830.81 MB of swap in use on the same SSD with no engine run going (another session is running tests); 14 GiB free on the data volume. DiskBudget does not count swap.
- MEASURED (code): JointRun gives the same Results to the bit on a resume with the same thread count, because every sum is added in the same order (FullIndexFit.java:2209-2212). Against scoreAll it differs only in summation order (:2203-2205). That reordering is where the 1e-12 standard comes from.

### Options

- **A v3 state format, lossless: drop the 1,030 slots that are NaN by rule, group the remaining 1,720 doubles by byte plane in blocks of 64-256 records, compress each block with the JDK's Deflater, and keep a small index of block offsets for the positional readers. Change only StateStore's header/records/read/readAll, write a converter, and add a test that decodes the whole file back to SHA-256 ed9e9fb4. StateRun could write v3 directly, one block at a time.**. Disk: Main file 1.341 -> about 0.606 GB, at rest and at every run's peak (-0.735 GB); the same again for the twin. The one-time conversion peaks at about 1.95 GB (v2 + v3).. Time: +2-4 s per load (decode at 212-341 MB/s on one thread, measured); about 20-30 s of CPU once to encode.. Exactness: Bit-identical: the decoded records are byte for byte the stored ones (measured on 300 records across all three segments, plus 9,061 earlier), ScoringStates.prepare runs on the same bytes, and nothing is reordered. The file's SHA-256 changes, so ed9e9fb4 can then be checked only through the decoder. A new run's identity should name the decoded stream's hash. A replay of c06c061 needs the v3 header to carry the v2 path/size/mtime string.. Effort: 1-2 days with tests. Confidence: High on the ratio (0.4521-0.4526 in every segment) and on the round trip in the sample; a whole-file decode test is still owed.
- **One copy of the results: write no results.ser, and have readers (CeilingReport.read, FullIndexPeek.read) merge the parts; save the 6.8 KB of last-round RoundStats so results.ser can be rebuilt byte for byte on demand. Or keep results.ser and delete the parts after it is written, which saves space at rest but leaves the peak unchanged.**. Disk: -0.431 GB at the run's peak and at rest. Time: About 7 s of merging per reader (measured in the working papers). Exactness: Bit-identical: the merge was measured equal to results.ser to the bit, and re-serialized it has the same SHA-256. Effort: About 1 day: readers, SiteExport's artifact hash, re-estimating DiskBudget.ITEM. Confidence: High.
- **Gzip the checkpoints the run writes (fit.ckpt, part-*.ckpt, results.ser if kept) with the JDK's GZIPOutputStream inside FullIndexCheckpoint.writeObject; no new jar**. Disk: fit.ckpt 0.184 -> 0.105 GB (it is doubled for a moment at each write); parts 0.431 -> 0.293 GB; results.ser 0.431 -> 0.293 GB (all measured). Time: About 4.5 s per fit.ckpt write over 6 pass rounds, and about 11 s for all the parts: negligible against 20+ h. Exactness: Bit-identical: lossless, the deserialized objects are identical, so a resume and the figures are identical. Each file's SHA-256 changes; a cited hash must then be taken over the decompressed stream. Effort: Hours. Confidence: High.
- **All three changes together: v3 states, gzip checkpoints, one copy of the results**. Disk: The run's peak goes from 2.386 to about 1.004 GB (0.816 GB during the passes). At rest: about 0.293 GB of results plus 0.606 GB of states, if later runs need them. Time: None measurable. Room for a partial tree store of about 4,000 states (6.7%) under 2 GB, saving about 1.5 h of about 23 h of rebuilds, once DiskBudget.ITEM (10 MB, against about 1.6 MB measured per item gzipped) stops reserving 1.86 GB. Exactness: Bit-identical, for the reasons above. Effort: About 3 days. Confidence: Medium-high: every term is a measured size or ratio, and their sum as the peak is an estimate.
- **Make the states in the run's own process and never write them (StateRun feeding ScoringStates directly)**. Disk: Another -0.606 GB; the run's peak is about 0.40 GB. Time: +13.0 h for every process that needs the main states (+3.2 h for the twin): +26 h for the pending P1/P2, about +215 h from the seeds. A crash loses the states, because StateRun's resume is its file (StateStore.java:139-193). Exactness: Not bit-identical with one build: shoes 0-6649 came from a build that sums in another order, agreeing only to 1e-12 (PLAN_PAGE.md:706-709). Even 1e-12 holds only if no fitted play or best-move choice is that close to a tie. Piping the three original builds in order might reproduce the inputs to the bit, but that has never been tested. Effort: Moderate code plus a new identity scheme; breaks replays of c06c061. Confidence: High on the cost, low on exactness. Not recommended.
- **A sliding window in RAM rather than on disk: stream the v3 states from disk through each round instead of holding them, freeing heap for larger scoring batches**. Disk: None (the v3 file must stay on disk the whole run). Time: About -1.65 GB of heap. Batch room grows from 5.68 to about 7.3 GB, so about 43 -> about 33 last rounds, worth about 4-5 h without a tree store (crude estimate). Re-decoding costs about 2-5 s a round. Exactness: Bit-identical if prepare() reruns the same code on the same bytes (Java floating point has been strict IEEE since 17), with the same state order and threads. Effort: Large: st.move, st.chance and st.dealt are read throughout SystemScorer and FullIndexFit. Confidence: Low-medium. Only worth it if no tree store is chosen.
- **APFS transparent compression of the v2 file (ditto --hfsCompression), with no code at all**. Disk: Physical size 0.659 of logical (measured on a 22 MB sample), about 0.88 GB. DiskBudget counts logical size and would not see the saving. The conversion peaks at about 2.22 GB. Time: None. Exactness: Same bytes, size and mtime: every hash and identity unchanged. Effort: Minutes, with Justin's yes. Confidence: Medium (measured on a sample only).

## Investigator: stages

**Verdict.** The later stages never need more than 2 GB. Every peak over 2 GB comes from how the data is stored. Count each stage as Justin does: the state file S on disk uncompressed (1,340,719,767 B), plus what the stage really reads, plus what it must leave for the stages after it. On that count, every later stage's working set is under 2 GB with today's S. The tightest is the 93-item replay: about 1.99 GB uncompressed (about 9 MB under the cap), and 1.74 to 1.82 GB with Java's built-in gzip (ESTIMATED from MEASURED parts). Next come PageLook (2.01 to 2.04 GB uncompressed, 1.74 to 1.85 GB with gzip, E), step 7 (1.88 GB with its save gzipped), and KeyEdgesRun a and a-prime (1.88 and 1.93 GB with slim collectors). The key-edge report needs 0.32 to 0.50 GB, and simulator part 2 and RiskReport much less. Today's overshoots come from: replay records that hold a second copy of each item's source part and of the hook outputs; collectors holding 1.0 GB of Entry fields the report never reads; whole results files read for small slices; PageLook requiring the 14.9 GB tree store; and simulator files nothing reads. There are two real limits. First, the later stages combine several runs' outputs, and those outputs must wait on disk beside S between stages. With S uncompressed, the main run already leaves 1.955 GB on disk. So the pipeline fits under 2 GB only if c06c061's results are cut down to the slices their later readers need before the replay, and only with margins of tens of MB. With S compressed (another area), it fits with room to spare. Second, the only trade of time against space is the tree store, and it is all or nothing. 98% of trees are distinct and each replay batch uses each one once, so a window or cache under 2 GB saves at most about 2% of the rebuilding. The cost is 14.9 GB of disk, or about 11 to 15 h more for the main replay and 2 to 3 h for run D. Every change proposed keeps the figures bit-identical. Only the SHA-256 of the rewritten intermediate files changes, and so the inputs blocks of the result JSONs that cite those files.

## Later stages: is more than 2 GB needed? No. It comes from how files are stored.

Disk is counted at any moment, with the state file S on disk uncompressed (1,340,719,767 B). MEASURED unless marked E (estimate).

| Stage | Today, alone | Why it is big | After the change |
|---|---|---|---|
| Replay, 93 items, wide + sink + export | 3.84 GB | records repeat each item's source part (about 424 of 665.6 MB) and the hook outputs; the sink holds 559 MB of Entry copies; 93 parts are read for the check | 1.99 GB uncompressed (9 MB margin); 1.74-1.82 GB with gzip (E) |
| KeyEdgesRun a / a with a-prime | 1.99 / 2.06 GB | Entry copies (shoeSums alone is 112 MB of a) | 1.88 / 1.93 GB |
| KeyEdgesRun report | 1.94 GB | five full collectors, plus all of results.ser read for one 0.49 MB array (KeyEdgesReport.java:411) | 0.50 GB (0.32 with gzip) |
| PageLook, runs C and D | needs the 14.88 GB trees.bin (2.65 GB with no trees) | trees, full collectors, records, step 8's results read twice | 2.01-2.04 GB uncompressed; 1.74-1.85 GB with gzip (E) |
| Step 7 --save | 1.98 GB | S + all of results.ser + the save | 1.88 GB (save gzipped, 0.5451) |
| Simulator part 2 | about 0.75 GB of records (E) | record parts and the planted runs' records, which nothing reads | about 0.07 GB (E) |
| RiskReport | about 1.1 GB (E) | four whole .ser files read for about 50 rows (RiskReport.java:446-555) | under 0.1 GB (E) |

**Slim collectors, measured today** (SlimProbe, in memory, nothing written; re-serializing each file as read gave its exact size): a 108.9 MB, a-prime 53.0, run-c 77.6, main run-b 149.7, run-d 33.1. Together 422.2 MB of 1,428.2 MB, and 240.1 MB with Java's built-in gzip. The report reads none of the dropped fields (grep of KeyEdges*.java).

### Where reading the data in a window works, and where it cannot

- **It works over items.** Replay items run count by count (FullIndexReplay.java:365-377). Each item's hook output can be written as a final part and read back merged. Merging only fills slots, so no arithmetic changes. This is the one change that gets the replay under 2 GB with S uncompressed: slimmer records plus end-of-run outputs still come to about 2.5 GB.
- **Memory is already handled this way.** The report writes one row at a time (KeyEdgesReport.java:473), and keepOnly holds one penetration at a time (KeyEdges.java:345).
- **S cannot be read in a window.** Every batch's last round walks all states: 27 batches in the main replay, 5 in run D. S is loaded into memory whole, so on disk it is needed only between stages. Its size is the deciding term, and that belongs to another area.
- **Trees cannot be usefully windowed either.** A tree depends only on the cards left in the shoe. Only 508 states share the full-shoe composition, and 1,242 are at depth 10 or less, so 98% or more of the trees are distinct. Each is used once per batch walk. A cache under 2 GB saves at most about 2% of the rebuilding. The choice is 14.9 GB, or about 11-15 h more for the main replay and 2-3 h for run D (E, quiet machine).

### The real limit: stages that combine earlier outputs

Each stage's floor is under 2 GB. But the report combines runs A, A-prime, B, C and D, and RiskReport combines c06c061, steps 7 and 8, wide.ser and the simulator's records, so their inputs sit on disk next to S. After the main run, S + fit.ckpt + one copy of the results already make 1,955,103,666 B.

With S uncompressed, the later stages fit only if:
- c06c061's results are cut down to the slices their readers need before the replay;
- A and A-prime are recomputed when the report runs (254 + 612 s) instead of kept;
- every intermediate is gzipped.

Even then the margins are tens of MB (E). With S compressed (about 0.61 GB, E, another area's change), the margins are about 0.6 GB.

### Exactness

- **Every change here moves or drops bytes; none changes arithmetic.** The same doubles reach the same code in the same order, so the figures stay bit-identical.
  - The replay matched 93 of 93 items to the bit, and run D 15 of 15.
  - Merged parts deep-equal results.ser.
  - perfect.ser's array comes from fit.ckpt, whose perfect values equal results.ser's to the bit.
  - With --trees none the disk-cap check matched every number to the bit.
- **Checking each item against a digest is stricter than today.** It cannot accept an item that differs within 1e-12; it stops instead.
- **Some hashes change.** The SHA-256 of every rewritten intermediate changes: wide.ser ee3ff121, the five collectors, and gzipped files unless hashed over the decompressed stream. So the inputs blocks of spreads b86622c4 and key-edges 824f0769 change, and so does the page's files table. The figures do not.
- **Pre-summing in windows would break bit-identity.** If the per-state values were turned into partial sums per batch of states, the floating-point additions would be regrouped. KeyEdges.held adds (shoe, key) cells in sorted order, then each resample over those cells (KeyEdges.java:571-597). Windowed partial sums would agree only within 1e-12, not to the bit. That is not needed: the per-state arrays are already the compact form, at 0.42 GB slim.

### Order, smallest first

1. Slim collectors, with a test that the report's JSON is equal apart from its inputs block.
2. Let records go even when the export is refused (FullIndexRun.java:218-220, PageLook.java:532-535).
3. The replay without duplication: per-item parts, records without the part copy, a digest check.
4. gzip.
5. PageLook: --trees none and one copy of step 8's results.
6. Extracts, and A and A-prime recomputed when the report runs.
7. Clean up the simulator's records.

Probes: /private/tmp/ccec-later-stages-probe/FactsProbe.java and SlimProbe.java. They read the frozen classes and the run files read-only, and wrote nothing into the repo or runs/.

### Facts

- MEASURED (SlimProbe, /private/tmp/ccec-later-stages-probe, read-only, in memory, nothing written; re-serializing each collector as read reproduced its file size to the byte): a is 221,351,385 B, 108,902,217 slim, 54,279,202 with gzip; a-prime is 65,554,901, 53,002,753 slim, 35,156,613 gzip; run-c is 245,680,412, 77,558,716 slim, 38,721,821 gzip; main run-b is 704,599,811, 149,663,844 slim, 92,050,658 gzip; run-d is 191,051,009, 33,053,746 slim, 19,868,886 gzip. Total 1,428,237,518 B, 422,181,276 slim, 240,077,180 gzip. Slim keeps each Entry's name, count, flags, estimate and keys.
- MEASURED (grep): KeyEdgesReport, KeyEdges, KeyEdgesPrinted and KeyEdgesCsv read none of an Entry's boot, shoeSums, wide, wideBoot, wideKeys, widePlans or halfFit* fields (the only hit is a comment at KeyEdges.java:23). The report reads only the Entry's name, count, flags and estimate (KeyEdgesReport.java:268-312).
- MEASURED (FactsProbe read only the first 1,944,431 B of a-prime's file, the Facts object): of 60,743 states, 508 are at depth 0 (one shared full-shoe composition), 1,242 at depth 10 or less and 2,733 at depth 26 or less. Depth quartiles are 138, 229 and 291 cards. A tree depends only on the cards left (FullIndexFit.java:1595-1597), so at least 98% of the trees are distinct.
- MEASURED (grep of logs): the main replay ran 27 batches (replay/c06c061-0e0920d.log) and PageLook run D 5 (page-look-0e0920d.log). Each batch's last round walks every state once (FullIndexReplay.java:719, FullIndexFit.java:2023), so with --trees none each batch rebuilds every tree.
- ESTIMATED: rebuilding the trees takes about 1,500-2,023 s a batch on a quiet machine (the judge's T; 0.17 CPU-s x 60,743 / 6 threads = 1,721 s). That is about 11.3-15.2 h more for the 93-item replay and 2.1-2.8 h for run D. PageLook's run C does not read trees; only its replay does (PageLook.java:459-460).
- MEASURED (replay.txt files): the main replay matched 93 of 93 items to the bit, run D 15 of 15, and the perfect values 60,743 of 60,743.
- MEASURED (earlier working papers): the replay records are 928,881,429 B. Of that, Result is 665.6 MB (241.6 MB of it wide fields), the sink's Handed arrays 241.5 MB and the export rows 27.9 MB. The Result's non-wide part (about 424 MB) is the same size as the 93 fold parts (422,561,932 B), because each item equals its part to the bit.
- Code: a replay record keeps the whole Result (FullIndexReplay.java:212-233). The hooks' finish steps read only the wide fields, estimate and keys (WideReport.java:72-100) and Entry identity (ReplayHooks.java:183-217). The export reads only its own kept rows (StrategyExport.java:2745-2765). Items run count by count (FullIndexReplay.java:365-377), and a Result is merged per count from its items (:281-295).
- ESTIMATED replay floor, S uncompressed: S 1,340.7 + fit.ckpt 183.7 + wide 249.8 + slim sink 149.7 + export about 60 + replay.txt/perfect.ser 1.0 + classes and jar 5.6 = about 1,990 MB, with no second copy at the end and a digest check. With gzip (wide 0.668 and fit 0.570 MEASURED ratios, slim sink gzip 92.1 MEASURED, export about 0.5 E): about 1,741 MB. Today alone it is 3,837,166,252 B (earlier papers).
- MEASURED arithmetic: after the main run, S + fit.ckpt + one results copy = 1,340,719,767 + 183,689,022 + 430,694,877 = 1,955,103,666 B. That leaves 44.9 MB under 2.0e9 before any later stage writes anything.
- ESTIMATED PageLook floor with --trees none, S uncompressed. Inputs: S + step 7's save 202,956,286 + step 8's fit 40,239,384 + one copy of step 8's results 113,431,766 = 1,697.3 MB. Outputs: slim run-c 77.6, wide-first 61.5, export-first 23.0, replay wide 83.6, slim run-d 33.1 and export-full about 30-60 MB. Total 2,006-2,036 MB, or 1,834-1,849 MB with gzip (1,741-1,756 MB with the save gzipped too). Today it reads step 8's results twice, as parts for the replay's check and as results.ser for Step8 (PageLook.java:477).
- MEASURED sizes, ESTIMATED sums: step 7 alone is 1,977,227,599 B today; with its save gzipped (0.5451) it is 1,882,046,124. KeyEdgesRun a with slim collectors: 1,880,316,878 (today 1,992,766,046). a-prime with a still on disk: 1,933,319,631 (today 2,062,182,417). The report with slim collectors and perfect.ser instead of results.ser: 500,761,335, or 318,657,239 with gzip (today 1,937,379,049).
- Code: KeyEdgesReport reads all of results.ser only for its perfect array (KeyEdgesReport.java:411). perfect.ser's perfectSource is a clone of fit.ckpt's perfect (FullIndexReplay restore, :656), and the earlier review MEASURED that equal to the bit to results.ser's perfect.
- Code: RiskReport reads, per registered figure, estimate[p][m], boot[p][m] (1,000 doubles) and keys[p] from c06c061, step 8 and step 7's references (RiskReport.java:446-481). From wide.ser it reads only the six F43-F48 rows' wide[p] and wideBoot[p][k] (:509-555). ESTIMATED: under 1 MB in all, against 996,852,349 B of .ser files read today.
- ESTIMATED (results-only judge): simulator part 2 writes about 0.75 GB of per-shoe records, including p3-records/ parts it never deletes and the planted runs' records. Only $O2's records-F27/F29 (about 60 MB) have a reader (Sim.java:151-166; RiskReport --records).

### Options

- **Slim collectors: KeyEdges.Collector.save (KeyEdges.java:285-317) and Sink.finish (ReplayHooks.java:183-217) keep only each Entry's name, count, flags, estimate and keys.**. Disk: Collectors go from 1,428.2 to 422.2 MB (MEASURED), 1.006 GB less on disk. The replay peak drops 555 MB. KeyEdgesRun a goes from 1.99 to 1.88 GB, a with a-prime from 2.06 to 1.93 GB, the report from 1.94 to 0.93 GB.. Time: None. Slightly less I/O.. Exactness: Figures bit-identical by construction: the report reads no dropped field (grep). A test should still compare the report's JSON from slim and fat inputs, ignoring 'inputs'. The collector SHA-256s change (56148393, c649f44f, 2939a4ea, 3c1866b6, 3fe732ce), and so do key-edges JSON 824f0769 and the page's files table.. Effort: Small: one method and one test.. Confidence: High.
- **The replay without duplication. Records keep the ResultDiff, the perfect values and the export rows. Each item's wide fields and Handed arrays, with Entry slimmed, go into a per-item part file written whole and renamed. wide.ser and run-b.ser become those parts, and WideReport.read and Collector.read merge them in item order. The item check uses a per-item figures digest (timings excluded) instead of a second copy of the source's results.**. Disk: Replay peak from 3.84 GB to about 1.99 GB uncompressed (9 MB margin), about 1.74-1.82 GB with gzip (E). This is the only form that fits with S uncompressed: lean records plus end-of-run outputs is still about 2.5 GB.. Time: None, or slightly faster (about 0.5 GB less written).. Exactness: Figures bit-identical: the same objects reach the same code in the same order, and the merge only fills slots (merged parts deep-equal results.ser, MEASURED earlier). The digest check is stricter: it cannot accept a 'within 1e-12' item, it stops instead (93 of 93 items were identical last time). The SHAs of wide.ser and run-b change.. Effort: Medium: FullIndexReplay record and assemble, two readers, FullIndexReplayTest's resume-equality property.. Confidence: Medium-high.
- **gzip (java.util.zip, built in; readers detect the 1f8b magic) of collectors, wide files, records, fit.ckpt and step 7's save.**. Disk: Slim collectors 422.2 to 240.1 MB (MEASURED). wide 0.668, records 0.577, fit 0.570, save 0.5451 (MEASURED ratios). Step 7 to 1.88 GB; replay about 0.25 GB less.. Time: Seconds to a minute a file (E).. Exactness: Lossless, so figures are bit-identical. File SHA-256s change unless the hash is defined over the decompressed stream.. Effort: Small-medium.. Confidence: High.
- **PageLook: a --trees none option, one copy of step 8's results for both checks (the replay cuts its parts from results.ser, which Step8 already reads), the replay changes above for run D, and on resume run D with only the export and Step8 hooks.**. Disk: From 'needs 14.88 GB trees.bin' (2.65 GB with trees none) to 2.01-2.04 GB uncompressed, 1.74-1.85 GB with gzip (E).. Time: About 2.1-2.8 h more for run D's 5 batches (E, quiet).. Exactness: --trees none is bit-identical: the disk-cap check matched every number to the bit, and trees rebuilt from the same cards give the same bits (TreeStore.java:22-25).. Effort: Medium.. Confidence: Medium.
- **Per-reader extracts, taken once a big file's last full reader has run. RiskReport gets each row's estimate, 1,000 resamples and keys, plus the six wide rows. KeyEdgesRun's checks get estimates and keys. The replay gets digests. KeyEdgesReport takes perfect from perfect.ser (KeyEdgesReport.java:411).**. Disk: c06c061's results.ser (0.43 GB), step 8's run (0.27 GB) and wide.ser (0.25 GB) can leave before the replay, PageLook and the simulator. The report drops to 0.50 GB (0.32 GB with gzip). RiskReport needs under 0.1 GB (E).. Time: Seconds.. Exactness: Arrays copied, so figures are bit-identical. perfect.ser's array equals results.ser's through fit.ckpt (MEASURED earlier). The reports' inputs blocks would cite the extract, which records its source's SHA-256.. Effort: Medium.. Confidence: Medium.
- **Recompute runs A and A-prime when the report runs, instead of keeping them on disk through the replay and PageLook.**. Disk: About 162 MB slim (89 MB with gzip) off disk while the replay and PageLook run.. Time: About 14.4 min a report (253.9 + 611.8 s, MEASURED logs). S must still be on disk.. Exactness: Deterministic: rebuilt collectors differed only in their commit bytes (cmp, earlier review).. Effort: Small.. Confidence: Medium-high.
- **Simulator part 2: delete p3-records/ parts once writeRecords has merged them (Sim.java:151-166), and write no records for the planted runs.**. Disk: About 0.75 GB down to about 0.07 GB (E).. Time: None.. Exactness: No figure reads those files. Only $O2's records feed Ruin and RiskReport.. Effort: Small.. Confidence: Medium (sizes E).
- **Let records go even when the export is refused (FullIndexRun.java:218-220, PageLook.java:532-535).**. Disk: Prevents leftovers like the 1.16 GB in batch B.. Time: None.. Exactness: No figure reads records.. Effort: Small.. Confidence: High.
- **From the seeds only: run the wide, sink and export hooks inside the main run's scoring, so no replay is needed.**. Disk: Same peak as the replay, at the main run. fit.ckpt can go as soon as the main run ends.. Time: Saves the replay: 14.90 h with trees read (MEASURED), or 27 tree-building walks without them.. Exactness: Expected bit-identical: hooks may not change the Result, and 93 of 93 items equal to the bit shows scoring does not depend on batching. Needs its own test.. Effort: Large.. Confidence: Medium-low.
- **A tree cache or window under 2 GB (keyed on the cards left, or the first states).**. Disk: Under 2 GB.. Time: At most about 2-3% of the rebuilding. 508 states share one composition and at most 1,242 are at depth 10 or less (MEASURED). Every tree is used once per batch walk.. Exactness: Bit-identical.. Effort: Small.. Confidence: High that it is not worth doing.
