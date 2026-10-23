# What would let a future model trust these results, and build on them (20 October 2026)

Justin asked: "If I want an llm in the future to be able to trust my results if it wants to
expand on them, what can i add?" A read-only workflow answered it: one agent took stock of what is
built, one researched current conventions, and one read the page as a skeptical model would. A
ranking step followed. The facts below that carry weight were checked again by hand that day.

A model trusts what it can check for itself:

- public code at the commits a page cites;
- the inputs, or their hashes;
- a check that runs in minutes;
- the limits stated next to each claim.

Most of that is built here, but on 20 October it existed only on Justin's laptop:

- None of c06c061, 1946b3d, 832ef3e, fe9a91e and 0f087c4 is on any remote branch
  (`git branch -r --contains`); only 26f0724, 077d971 and 552dc6c are.
- The repo has no tag.
- https://justinbondurantdavid.com fails its certificate check.

**Already built, not to redo:**

- results.json: 3,045 figures, each with its SE, interval, source and commit, and 4 claim guards;
- SHA256SUMS, which passes;
- the hash box ("what a hash proves") and the evidence table;
- the simulator check: 82 of 82 registered tests pass, 7 of 8 planted bugs caught;
- the registration ledger, the literature check, the statement on how AI was used, the Limits
  section.

## 1. Before the wrap-up deletes the large files (impossible after)

1. **Commit the SHA-256 of every file the wrap-up deletes.** *Done 20 October,
   disk/2026-10-20-sha256.tsv: 14 files.* trees.bin is not among them: the Trash was emptied
   first. It is a cache rebuilt to the same bytes, and no page cites it.
2. **Export the bootstrap replicates and a figures digest from each `.ser`**, about 35 MB as
   plain text or JSON. A new paired comparison needs the replicates, and a `.ser` opens only in
   Java with these exact classes.
3. **Publish about 50 sample states with their stored values, and a few known answers** (the
   worked example's Stand −0.5408267361609979, the house edge off the top). A model could then
   test its own solver against the engine in minutes, without the 1.34 GB file.
4. **Run the whole suite at the page's commit and keep its JUnit report.** The evidence table's 13
   rows say "not re-run" until then. Once the data is gone, seven test classes skip silently and a
   later suite looks green.
5. **Bring disk/2026-10-18-results-only-kept.tsv up to date.** It keeps page-613255a and the
   7c1d40e inputs. The page now comes from runs/page-832ef3e and runs/site-1946b3d, and part 2's
   summaries and the row-10 scripts are new.
6. **Decided 20 October 2026: not uploaded.** Justin: "given the amount of garbage on the internet
   and in my drive, and given the likely lack of importance and impact of this project, unless I'm
   wrong, i think we can skip the state file". The code and the seed regenerate it (about 13 h,
   never tested to the byte), and the page says the run files are not published. What was
   proposed: upload the data, as a GitHub Release (with immutable releases on), a Zenodo
   dataset, or both: the main state file, the three `.ser` files the page's figures come from, and the twin
   state file if Justin says so. That is about 2.1 GB, or 3.4 GB with the twin, and every file is
   under GitHub's 2 GiB limit. It is the only way the page's recorded hashes can ever be checked,
   or anything rerun. It is free, but it needs his account and his yes on the exact list.

## 2. Before or with the website push

1. **Push the cited commits, and tag each one that produced figures.** *Pushed 20 October 2026,
   with Justin's yes on the exact list:* the branches site-integration (to 76b17ac, 307 commits) and
   custom-count-design (to 428ec51, 83 commits), new on GitHub, main untouched, no tags. Every
   commit the page cites is now on a GitHub branch, and the page's link to c06c061 opens. The
   tags are still to do. *Published 23 October 2026* with Justin's yes: the page is live at
   https://justinbondurantdavid.com/card-counting/, byte for byte runs/page-aefd014, and the
   path fix and wording commits are on site-integration (to 2db5a5e).
2. **Fix HTTPS:** GitHub Pages settings and DNS, Justin's account (PLAN_PAGE.md 2.1). Until then a
   fetcher gets a certificate error and reads nothing. *Diagnosed 20 October 2026:* DNS is right
   (apex A and AAAA are GitHub's four addresses each, www is a CNAME to jbondurant.github.io,
   Cloudflare serves it unproxied, no CAA record). Pages publishes from main, whose CNAME file
   came in on 29 August (4f49a08), and the domain answers over HTTP. But the certificate served for
   it is still GitHub's own *.github.io one, so the custom-domain certificate was never issued. The
   fix is in Settings → Pages, signed in: remove the custom domain and add it again, which starts
   the certificate request, then tick Enforce HTTPS once it is issued. The website push waits
   for it; the push itself is prepared locally (card-counting at 71e0e1c, a fast-forward of main).
   *Working later on 20 October:* a Let's Encrypt certificate for justinbondurantdavid.com and
   www.justinbondurantdavid.com was issued at 15:48 UTC (valid to 18 January 2027). Both names
   pass the certificate check, and www redirects to the bare domain over HTTPS. Plain HTTP still
   serves the page without redirecting, so Enforce HTTPS is not on yet. *Enforce HTTPS ticked 22
   October 2026* (Justin: "let's fix the 2 things"; Settings → Pages, signed in, "DNS check
   successful"): uncached paths redirect to https at once, cached ones within GitHub's 10 minutes.
3. **Add a LICENSE, a README and CITATION.cff, and call the page version 1.0 instead of "draft".**
   The licence is his choice; the plan's default is MIT for code and CC BY 4.0 for data. Correct
   the stale status lines a model reads first, such as PLAN_PAGE.md:12's "Nothing here is built
   yet".
4. **Fix the manifest:**
   - paths relative, not /Users/justinbondurant/…; *done 20 October 2026 (c0f0eb9, 7954b8e;
     Justin: "fix the paths"):* every file is named from the repository's folder, a worktree's
     file as BRANCH:PATH, and the page says files under runs/ are not published. The page was
     rebuilt from the same inputs into runs/page-7954b8e, and site/compare_builds.py finds its
     data files byte for byte 832ef3e's once renamed;
   - commits in full;
   - four labels corrected (the ledger's "commit" is a blob; report-final is credited to 1946b3d;
     part 2's simulator summaries to fe9a91e, which 0f087c4 made; the inputs' directory is
     runs/site-1946b3d, *done with the paths*). The reviewers of the path fix found the same
     kind of thing in general: the Commit column gives the commit of the input that read a file,
     not of the run that wrote it, while the hash box says "That the commits named produced the
     files is my statement". *Wording fixed 22 October 2026 (aefd014):* the column is headed
     "Input's commit", and the page and manifest say what it holds and that it is not always the
     commit that made the file. Most run files record their own commit (the simulator summaries'
     simulatorCommit, the .ser files, the risk, spreads, ceiling and key-edge JSON); carrying it
     into the column means running every adapter again, not done;
   - the seeds, the Java build, the machine, the thread counts and the jars' hashes added.
5. **Add the missing disclosures:**
   - deviation 1, which SIM_CHECK.md requires the page to list;
   - that some method choices were tuned on the main file;
   - that the fits stopped at the pass cap while some strategies were still changing;
   - that commit dates come from Justin's own clock;
   - that the simulator's z tests share shoes, most of them negative, with one combined
     statistic;
   - why the fold-swap bug went uncaught;
   - that results.csv holds 308 of the 3,045 figures.

   And cut any wording that claims more than was measured.
6. **A claims.json:** each headline sentence with its keys, conditions, evidence and the status
   "not reproduced by anyone else" (PLAN_PAGE.md 3.6).
7. **A verify.sh with its expected outputs** (PLAN_PAGE.md 3.4, depth 1). It needs the Release.
8. **A dated note in Justin's own words** on what he checked himself and what he did not. AI
   drafted the first-person page, and no source is marked as read by him.
9. **A .nojekyll file,** so Pages serves MANIFEST.md as it is, and a check that the served files
   match the build.

## 3. Later, all free

- an archive copy at Software Heritage (no account);
- an OpenTimestamps stamp of manifest.json;
- signed tags;
- a Wayback Machine snapshot;
- a Zenodo DOI for the code;
- a bibliography with saved copies of the sources, the Loto-Québec rules among them;
- the planted-bug patches, so anyone can rerun them;
- llms.txt last, since no major provider says it reads it.

## What nothing here can provide

- A hash proves bytes match, not that the code is right.
- A timestamp proves a file existed by a date, not that the counts were registered before the look.
- The simulator's independence is shown only in part: AI wrote the engine, the simulator and most
  reviews, and the scan that shows the clean room held covers the later sessions, from logs that
  are not public.

What raises trust most is independent replication: someone else's code reproducing a few headline
numbers. Items 1.3, 1.6 and 2.7 make that cheap.
