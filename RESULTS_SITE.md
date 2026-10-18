# The results tab: index to the plan

On 8 October 2026 Justin asked for this repository's results to become a tab on his website,
justinbondurantdavid.com. He wanted it high effort, with more simulation behind the figures and
less bland than plain HTML. He wanted it clear about the process, the results, the published
literature and what is new here, and made so that a reader, or a language model looking for
information on card counting, has reason to trust it. The tab is one static page in the site's
existing style. Its charts and tables are written by code committed here, and every figure on it
is filled in by that code, never typed. The page says what a hash proves (that a download is, byte
for byte, the file the manifest lists) and what it does not: that the listed commits made the file
(Justin's statement; for the first segment of the states file and for the search file the build
is inferred, not recorded), or that the code is right. Only the files served with the page can be
checked from it; the run files' hashes are recorded values (PLAN_PAGE.md 3.2, 18 October 2026). Then
it gives the actual evidence for correctness. Nothing in these files is built or run yet. Nothing is pushed or
published without Justin's yes on an exact list of files.

## The plan

- [PLAN_COMPUTE.md](PLAN_COMPUTE.md): the five new computations (an independent simulator, swings
  and money per hour, wider spreads and wonging, the perfect full-play ceiling, the edge at each
  count), how each is checked, what it costs on this laptop, and 11 decisions with defaults.
- [PLAN_PAGE.md](PLAN_PAGE.md): the page section by section, how it looks and how it is generated,
  the verification package (manifest, hashes, spot check, reproduction), the statement on how AI
  was used, and publishing.
- [LITERATURE.md](LITERATURE.md): the sourced comparison with published work, topic by topic, with
  each source's status (checked, read, secondary, not read) and the plan for `LiteratureCheck`.

## Order of work

1. **While steps 7 and 8 of the ceiling count run** (its one scoring on the main file, started
   9 October at about 19:16, then its full-play rows; about 7 to 12 hours together): code and
   fixture tests only, at `nice -n 15` with 1 GB of heap. The only time-critical item is the
   three ceiling result paragraphs, committed before anyone opens `report-ceiling-97bf834.md`.
2. **After step 8:** the short checks that can find a bug cheaply, then the overnight replay of
   c06c061, the simulator's two parts and the reports (PLAN_COMPUTE.md 7.2: about 18 to 23
   machine hours, about three to four days on the wall, an estimate).
3. **Build:** export, generate the page, run the spot check, hash everything. Each section fills
   as its computation lands; the build fails until that section's checks pass.
4. **Before publishing:** the site's HTTPS working (question 6), two cold reads, a browser pass,
   Justin's own read, then the push list and his yes; after the push, every served file checked
   against its hash over https.

## Questions for Justin (copied from PLAN_PAGE.md section 6)

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

PLAN_COMPUTE.md section 8 lists 11 more decisions for him, each with a default (among them
chaining steps 7 and 8, the interval method for wide plans, Release assets, deleting trees.bin).
The two files agree on what a stamp of the registration can prove (PLAN_COMPUTE.md 4.1) and on
the default for it: no outside record now, and a stamp of the manifest at release.
