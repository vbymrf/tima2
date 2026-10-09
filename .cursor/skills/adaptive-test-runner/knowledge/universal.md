# Universal rules

Rules about how verification itself goes wrong. Nothing here names a tool, a
path or a repository, so all of it travels to the next project unchanged.

These five are seeded with the skill. Everything below them is earned: the
journalist appends here only when a promoted rule mentions neither a tool nor
anything specific to a repository.

## A skip counted as a pass
- code: BASE-1
- kind: preventive
- tier: *
- symptom: a suite exits zero, reports success, and executed few or no
  assertions; individual cases report as skipped.
- check: the number of cases actually executed, the skip count, and whether the
  environment each skipped case needs was present in the same process.
- cause: test frameworks routinely skip when a dependency is unreachable and
  still exit zero. The gate looks green and proved nothing.
- fix: establish the missing dependency, then rerun and require a skip count of
  zero for the cases the claim depends on.
- verify: the expected case names appear in the artifact and none is skipped.

## A cached result presented as a fresh run
- code: BASE-2
- kind: preventive
- tier: *
- symptom: a task reports up-to-date or shows no sign of execution; **or** a
  report's timestamp predates this run; **or** a total is assembled from reports
  whose times nobody compared — yet the result is treated as evidence.
- check: the time of **every** report file against a start stamp taken before
  the run, not a sample; the tool's output without a quiet mode that hides reuse;
  a summary that counts stale reports apart and keeps them out of the total.
- cause: tools reuse a result when inputs are unchanged — correct behaviour,
  wrong evidence. Worse: a check that reads files which are **not declared
  inputs** (sources, scenarios, documents read from disk) is reused even when
  those files changed, and the stale result looks legitimate.
- fix: for one run — the tool's own flag to force execution; when the check
  reads undeclared files — declare them as inputs or mark the task "always
  run", or it recurs on the next run. A total with stale reports is not a total:
  do not report "N of N" until stale is zero. Do not delete caches.
- verify: every report time falls inside the run, and the summary shows
  "stale 0" computed by a real comparison, not a stub.
- history: rewritten 2026-10-09 after TEST-005 and TEST-006 — the earlier text
  was obeyed and did not help: the reuse message was hidden by a quiet flag, and
  the summary never compared times.

## A summary that disagrees with the artifact
- code: BASE-3
- kind: curative
- tier: *
- symptom: reported totals differ from the report file or the per-unit output.
- check: parse the artifact independently and recompute.
- cause: summarisation error by whoever reported. It is not evidence of a test
  problem.
- fix: report the artifact-derived numbers and note the correction. Do not
  rerun a passing deterministic suite to obtain a tidier summary.
- verify: totals equal those in the source report.

## Evidence from the wrong revision
- code: BASE-4
- kind: curative
- tier: *
- symptom: results are cited for a change, but the artifact was produced before
  that change was present in the tree.
- check: the revision under test, the working tree state at run time, and
  whether uncommitted changes were included.
- cause: the run happened against a different state than the one being claimed
  about — a stale checkout, a partially applied change, or a build from a
  previous branch.
- fix: rerun against the stated revision and cite it with the result.
- verify: the artifact postdates the change and the revision is named in the
  report.

## A green exit code with nothing executed
- code: BASE-5
- kind: preventive
- tier: *
- symptom: exit code zero, no artifact written, or an artifact containing zero
  cases.
- check: whether the target actually matched anything, and whether the artifact
  exists at all.
- cause: a selector, filter or target name that matches nothing usually
  succeeds. Nothing ran, and nothing failed.
- fix: confirm the target resolves to the intended cases before treating zero
  as success.
- verify: the artifact exists and contains the expected cases.
