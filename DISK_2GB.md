# Can the program run in 2 GB of disk? (18 October 2026)

**Superseded the same day:** Justin does not want to rely on Drive ("I just want like results, and
produce files that are required to create those results"). See DISK_RESULTS_ONLY.md.

Justin asked: "Is there a way to make this program run with 2gb max of space". A workflow looked into
it on 18 October: five read-only investigators, three designers and an adversarial judge. Their
working papers are `disk/2026-10-18-2gb-working-papers.md`.

**Answer: yes, with conditions, for every run still pending.** It counts what runs/ holds,
2.0e9 bytes, as DiskBudget counts it. Two things make it possible:

- Every finished file lives on Google Drive, byte for byte.
- A run brings back only the files it reads, checks each one's SHA-256, and puts it back at the
  same path with the same modification time. A run's identity names that time
  (FullIndexRun.stateFile).

So every published figure and every SHA-256 the page cites stays the same. Drive has 3.65 TiB free
(`rclone about gdrive:`, 18 October).

**What does not fit in 2 GB as the code stands.** None of these is pending except PageLook:

| Run | Its files at the peak | Over by |
|---|---|---|
| A fresh full run of the catalog | state file 1.34 + fit 0.18 + parts 0.43 + results 0.43 = 2.39 GB | 0.39 GB |
| A rerun of all 93 items of the main replay, with its hooks | about 3.9 GB | about 1.9 GB |
| KeyEdgesRun a | 2.011 GB | 11 MB |
| PageLook | it reads only the 14.88 GB tree store | — |

The binding term is the main state file, 1.34 GB on its own. A fresh full run would fit only if
that file were stored re-encoded without loss. Its 1,030 always-empty slots per record would go,
and the rest would be compressed: about 0.61 GB, an estimate from a 200 MB sample, not the whole
file. Or the file could be read as a stream from Drive.

**The conditions, in order:**

1. **Finish item 1 while trees.bin is still there.** That is the 15 full-play rows of the main
   run, and the page look's F50/F52 export, refused under open item 1 (INTEGRATION.md:377). Only
   PageLook makes that export, and PageLook reads trees only from a kept store (PageLook.java:
   179-181). Doing it later would need a `--trees none` option in PageLook first, and a much
   slower run.
2. **A manifest of every file, committed.** Path, bytes, SHA-256, MD5 and modification time. The
   main state file's SHA-256 (ed9e9fb4…, measured 18 October) is recorded nowhere yet.
3. **An archive tool, with its test.** `push` copies to Drive with `rclone copy --immutable` and
   checks SHA-256 and MD5. `fetch` calls DiskBudget.require first, restores the exact path and
   time, and refuses a mismatch.
4. **A second copy off the laptop** before the last local copy of anything that cannot be rebuilt
   to its hash is removed: a GitHub Release, or an external disk.
5. **Removals batch by batch, with Justin's yes.** Then `CCEC_DISK_BUDGET_GB=2`.

**Smaller code changes the judge recommends:**

- Let a replay's records go even when its export is refused. That refusal is how 1.16 GB was left
  behind (FullIndexRun.java:218-220, PageLook.java:532-535).
- SiteExport should take the state file's header and identity from the manifest. It reads nothing
  else of it, so the results re-export needs 0.77 GB instead of 2.11 GB.
- Reuse the optimism output instead of rerunning it on each page build. Its three builds differ
  only in their commit line, so the twin state file is needed only for the spot check and for
  publishing it.

**What it costs.**

- **Without a tree store, every round builds every tree again.** A full run has 49 rounds, and 48
  of them read trees (full-c06c061.log). At TreeStore's 0.17 CPU-s a tree on 6 threads, that is
  about half an hour a round, or about 23 hours more for a full run (estimate). Under load it is
  more. In the disk-cap check, the last round took 8,121 s with trees built, against 2,082 s with
  twice the strategies read.
- **The first upload of about 6.4 GB:** at least 44 minutes (estimate, from one speed reading).
- **Fetches:** a few minutes per run.

**Justin's own actions.** rclone says this remote uses its shared Google Drive client_id, "which
is being retired and will stop working during 2026". Making his own client_id is an account step
only he can take: https://rclone.org/drive/#making-your-own-client-id

**Space DiskBudget does not see.**

- Files in the macOS Trash, until it is emptied.
- `.worktrees`, about 0.5 GB.
- macOS swap on the same disk, 1.6 to 2.2 GB on 18 October.

On 18 October the disk's data volume was 99% full, with 3.7 GiB free (`df -h`).
