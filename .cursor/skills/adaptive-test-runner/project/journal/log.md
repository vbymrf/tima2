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
