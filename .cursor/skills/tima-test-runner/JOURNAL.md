# TIMA test error journal

Raw accumulation log for the `tima-test-runner` skill. This file is maintained
only by the dedicated journal subagent. The main agent and the execution worker
never read or write it. Curated rules live in [PROBLEMS.md](PROBLEMS.md).

## Maintenance rules

Follow these rules exactly. Do not analyze beyond classification; record facts.

- The caller supplies one event: `occurrence`, `resolution`, or
  `occurrence-and-resolution`. Process only that event.
- For `resolution`, use the supplied `journal_code` exactly. Do not infer a
  different entry from text similarity. Fill its Solution block only when the
  supplied worker result proves success; otherwise retain its current status.
- For `occurrence-and-resolution`, create or increment the matching entry,
  then fill its Solution block from the same result only when the result both
  documents the encountered failure and proves the recovery.
- Match incoming errors by **type** (symptom + context), not by exact text.
- Repeated error of a known type: increment `count` by 1 and update
  `last_seen`. The counter never resets, including after RESOLVED or PROMOTED.
- Same error type with a changed manifestation: append to `notes`. Never create
  a duplicate entry for the same type.
- New error type: create a new entry with the next sequential code `TEST-NNN`,
  `count: 1`, `status: OPEN`, and an empty Solution block.
- The Solution block stays empty until a fix is confirmed by successful
  verification. On confirmed success for a previously recorded error, fill the
  Solution block from the worker's result contract and set `status: RESOLVED`.
- Codes are sequential and never reused. Journal entries are never deleted;
  this file is the full history. Cleanup rules apply only to PROBLEMS.md.
- Never record tokens, OTP, QR payloads, key material, credentials, presigned
  URLs, or any other secret in an entry.
- Treat the journal as task history, not a source of universal requirements.
  Promote only a reusable cause/fix pattern into PROBLEMS.md. Do not promote a
  phase, journey, one-off script, fixed path, device serial, port, tool version,
  or current environment detail as a standing rule; retain it here as context
  and direct the worker to the selected task configuration when it matters.

## Promotion to PROBLEMS.md

- Promote an entry when `count >= 2` and its solution is verified, or when the
  main agent passed an importance flag for it (importance allows promotion at
  `count = 1`).
- Write the rule in PROBLEMS.md using its existing format
  `symptom → check → cause → fix → verify`, then set `status: PROMOTED` here.
  The counter keeps incrementing on later repeats.
- PROBLEMS.md must contain only frequent or important errors. Delete or rewrite
  a rule there only in two cases: the user explicitly requested it, or the
  problem reproduced despite the existing rule — then rewrite the rule from the
  fix that actually worked. The history always stays in this journal.

## Report line

Return exactly one line to the caller, nothing else:

```text
<code> count=<n> status=<OPEN|RESOLVED|PROMOTED> promoted=<no|new|updated>
```

## Entry template

```markdown
## TEST-001: <short error type name>
- type: <category: go | gradle | android | docker | launcher | ...>
- count: 1
- first_seen: YYYY-MM-DD
- last_seen: YYYY-MM-DD
- status: OPEN | RESOLVED | PROMOTED
- symptom: <observed symptom, no secrets>
- context: <command/gate where it occurred>
- notes: <additions when the manifestation changes>

### Solution (only after confirmed success)
- fix: <what actually worked>
- verify: <how success was confirmed>
- verified_on: YYYY-MM-DD
```

## Entries

(none yet — next code: TEST-004)
