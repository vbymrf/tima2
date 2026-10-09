# Failure journal

Raw, append-only history for this repository. Written only by the journalist.
The main model and the worker never read or write it — it grows without limit
and is the largest file in the skill.

Maintenance rules are in `JOURNAL-AGENT.md`, deliberately not here: instructions
kept inside the data file are lost the moment the data file is rewritten.

The next unused code lives in `next-code.txt`, also deliberately separate. A
counter stored inside its own log disappears together with the log, and the
codes already handed out stop resolving.

Entries are never deleted and never rewritten into something else. Curation
happens in the rule files; this is the record of what actually happened.

## Entries

## TEST-004: Go build failure — missing import + type mismatch in new server API
- type: go
- count: 1
- first_seen: 2026-08-14
- last_seen: 2026-08-14
- status: RESOLVED
- tier: go-unit
- symptom: `go build ./...` exited 1 with an undefined `context` import and a string-to-`[]byte` type mismatch in a new server API.
- context: server build gate on branch feature/device-link-qr.
- notes: both failures appeared together in the first post-commit build; the code was corrected before verification.

### Solution (only after confirmed success)
- fix: added the missing context import, corrected the link-session ID type, and repaired related reset and authorization logic.
- verify: fresh `go build ./...`, `go vet ./...`, and full isolated-database Go tests exited 0; API tests passed.
- verified_on: 2026-08-14

## TEST-005: Gradle reused a test task whose tests read undeclared files
- type: gradle
- count: 1
- first_seen: 2026-10-07
- last_seen: 2026-10-09
- status: PROMOTED
- tier: client-jvm (`jvmTest :architecture-tests:test`)
- symptom: `:architecture-tests:test` stayed up-to-date after edits to client sources, `maestro/` and `doc_mig/`; its XML was reused and summed into "N of N green" (1548, 1556) while three real violations existed (hard-coded colours, global-words reads, Cyrillic literals — introduced 2026-10-07..08). Found when a deliberately broken scenario tag "passed" ScenarioTagsTest.
- context: the module's tests read files from disk (client sources, scenarios, ledger) that are not declared task inputs; Gradle was run with `-q`, which hides UP-TO-DATE.
- notes: BASE-2 existed and did not catch it — the reuse message was hidden by `-q`, and the summary did not compare report times with the run start (see TEST-006).

### Solution (only after confirmed success)
- fix: `outputs.upToDateWhen { false }` in `client/architecture-tests/build.gradle.kts` (35387a70); the three violations fixed in the same commit.
- verify: a deliberately broken tag, ledger row and journal phrase each failed ScenarioTagsTest with its own message, then passed after revert; full gate 1559 of 1559 with architecture tests executed.
- verified_on: 2026-10-09

## TEST-006: Summary script reported "stale 0" without checking
- type: harness
- count: 1
- first_seen: 2026-10-07
- last_seen: 2026-10-09
- status: PROMOTED
- tier: client-jvm
- symptom: the ad-hoc summary script printed `stale files 0` on every run; its freshness check was a stub (`if False: old += 1`), so reused XML was summed as fresh.
- context: main-model summary of `**/build/test-results/*/*.xml` after `jvmTest :architecture-tests:test`; the script lived in the session scratchpad, not in the repository.
- notes: the reported totals of 2026-10-07..09 (1545, 1548, 1556) are not evidence of a fresh run.

### Solution (only after confirmed success)
- fix: `project/scripts/count_results.py` — `start` stamps the run start, `count` compares every report with it, lists stale reports apart and leaves them out of the total, exits non-zero on stale, failed or missing reports.
- verify: see the TEST-006 rule in `project/rules.md`.
- verified_on: 2026-10-09
