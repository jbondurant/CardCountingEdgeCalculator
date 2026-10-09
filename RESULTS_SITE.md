# The results tab: index to the plan

On 8 October 2026 Justin asked for this repository's results to become a tab on his website,
justinbondurantdavid.com. He wanted it high effort, with more simulation behind the figures and
less bland than plain HTML. He wanted it clear about the process, the results, the published
literature and what is new here, and made so that a reader, or a language model looking for
information on card counting, has reason to trust it. The tab is one static page in the site's
existing style. Its charts and tables are written by code committed here, and every figure on it
is filled in by that code, never typed. The page says what a hash proves (that these are the files
a given commit produced) and what it does not (that the code is right), then gives the actual
evidence for correctness. Nothing in these files is built or run yet. Nothing is pushed or
published without Justin's yes on an exact list of files.

## The plan

- [PLAN_COMPUTE.md](PLAN_COMPUTE.md): the five new computations (an independent simulator, swings
  and money per hour, wider spreads and wonging, the perfect full-play ceiling, the edge at each
  count), how each is checked, what it costs on this laptop, and 10 decisions with defaults.
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
   c06c061, the simulator's two parts and the reports (PLAN_COMPUTE.md 7.2: about 15 to 20
   machine hours, roughly three days on the wall, an estimate).
3. **Build:** export, generate the page, run the spot check, hash everything. Each section fills
   as its computation lands; the build fails until that section's checks pass.
4. **Before publishing:** two cold reads, a browser pass, Justin's own read, then the push list
   and his yes.

## Questions for Justin (copied from PLAN_PAGE.md section 6)

Each has a default; the work goes ahead on the defaults unless he says otherwise, except
that nothing leaves the machine without his yes on the push list (section 5).

1. **Publish the twin file too (1.34 GB more)?** Default: yes. The ceiling count's claim
   that it was chosen on other shoes can only be checked with the file it was chosen on.
   Regenerating it from its seed takes 3.2 hours, but its build is not yet known, so a
   regenerated copy may not match its hash.
2. **Licences.** Default: MIT for the code; CC BY 4.0 for the data and the page's text.
   Until he picks, the page says "all rights reserved", and the repo stays unlicensed.
3. **Timestamps and signing (section 3.7).** Default: an OpenTimestamps proof of the
   registration file as soon as he says yes, while step 7 is still running, and one of
   `manifest.json` at release; no signed tags; Software Heritage at release; no Zenodo.
   Installing the timestamp client and submitting anything to a third party each need his
   yes.

PLAN_COMPUTE.md section 8 lists 10 more decisions for him, each with a default (among them
chaining steps 7 and 8, the interval method for wide plans, Release assets, deleting trees.bin).
The two files disagree on one default: its decision 2 says no outside timestamp of the
registration, question 3 above says an OpenTimestamps proof. His one answer settles both.
