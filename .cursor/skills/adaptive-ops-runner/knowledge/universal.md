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
- symptom: a task reports up-to-date, or the artifact's timestamp predates this
  run, yet the run is being treated as evidence.
- check: the artifact's modification time against the start of this run, and
  whether the build tool or test runner reported reuse.
- cause: build systems and test runners reuse results when inputs are
  unchanged. That is correct behaviour and wrong evidence when the point of the
  run was to observe execution.
- fix: force execution with the tool's own flag for it when the gate requires a
  fresh run. Do not delete caches to achieve this.
- verify: artifact timestamps fall inside this run.

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

## Corrupt evidence screenshot
- code: BASE-6
- kind: curative
- tier: *
- symptom: an image decoder cannot open a captured PNG or reports corruption.
- check: decode every evidence file and compare it with the state it is claimed to document.
- cause: the capture was truncated, corrupted, or substituted from another step.
- fix: recapture the same state; do not substitute a different step's image.
- verify: every retained image decodes, matches its documented step, and contains no secrets.

## Secret appears in logs or evidence
- code: BASE-7
- kind: preventive
- tier: *
- symptom: output includes a token, OTP, QR/link payload, key material, credential, or presigned URL.
- check: stop further output and identify each retained copy.
- cause: a verbose command or UI capture crossed a secret boundary.
- fix: do not repeat the value; remove unsafe transient evidence and recapture a redacted state.
- verify: retained reports prove the behavior without secret material.
