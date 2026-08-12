---
name: tima-test-runner
description: Runs and diagnoses TIMA tests and build gates - Go server packages against PostgreSQL/Redis/MinIO, the messenger-crypto JVM library, and the Compose client (desktop tests, Android compile, desktop distributable). Use automatically whenever tests, verification, test counts, or build validation are requested.
---

# TIMA test runner

The main model leads the implementation task: it writes code, interprets test
results, and decides follow-up work. Composer 2.5 Fast executes delegated test
commands; the journal subagent maintains the error knowledge base.

## Required workflow

1. Read only the sections of [REFERENCE.md](REFERENCE.md) relevant to the
   requested test tier and surface. There is no CI in this repository: the
   authorities are the Gradle files, the Compose files under `server/deploy/`,
   and the task-selected plan. Do not read [WORKER.md](WORKER.md),
   [PROBLEMS.md](PROBLEMS.md), or [JOURNAL.md](JOURNAL.md) yourself: WORKER.md
   and PROBLEMS.md are read by the worker, and JOURNAL.md belongs to the
   journal subagent (see "Knowledge journal" below).
2. Select the smallest test tier that proves the requested claim. Do not expand
   into Android packaging or the desktop distributable unless requested or
   required by the changed surface.
3. Inspect current processes before any tier that needs Docker, an emulator, or
   the TIMA peer. Never start a duplicate stack, emulator, or Windows peer.
4. Delegate execution to one local shell subagent with:
   - `model="composer-2.5-fast"`
   - `subagent_type="shell"`
   - the minimal worker prompt below; standing rules and the result contract
     live in WORKER.md, which the worker reads itself
5. Independent commands may use separate Composer workers only when they do not
   share Gradle outputs, ports, Docker state, or emulator state. Never run
   multiple heavy Gradle invocations concurrently, and remember that `client`
   composite-builds `../messenger-crypto`.
6. Start a long-running independent worker in the background and continue
   independent implementation work. Await its result before a dependent action
   or before reporting final verification.
7. The main model checks the worker's exit codes and at least one authoritative
   artifact: JUnit XML, a health/ready response, Go per-package results, or a
   built package on disk. When fresh execution is required,
   compare report timestamps and reject stale or merely cached evidence.
   Recompute aggregate counts from the artifact; correct any worker summary
   that disagrees with the source report.
8. Return the verified concise result. Include raw log excerpts only when they
   are necessary to identify a failure.

## Minimal worker prompt

Send only:

- the absolute path to this skill's WORKER.md with the instruction to read it
  first and follow it
- repository path and requested scope
- chosen tier and exact commands, with timeout expectations
- known current state relevant to the commands
- authoritative report paths
- task-specific constraints beyond WORKER.md, if any

Do not send the full conversation or unrelated source code. Do not restate
standing rules, the PROBLEMS.md requirement, or the result contract — they
live in WORKER.md. Never send JOURNAL.md to the worker. The worker replies
with the compact result contract defined in WORKER.md (`status`, `scope`,
`counts`, `failures`, `evidence`, `changes`, `next`).

## Reproducible test scripts

Allow the worker to create a `.ps1` or `.bat` script when that makes the
requested verification repeatable or more reliable. It follows the storage and
reporting rules in WORKER.md. State a task-specific location or retention
requirement only when one differs from that standing policy.

## Live journeys

This repository has no acceptance harness and no scenario runner. A journey
across two peers is manual: the dev stack, the server, the Windows peer, and a
phone or emulator, each started through the root launchers named in the
`runtime-launchers` rule.

A manual journey is weaker evidence than a test run, so say so. Report what you
observed and with which commit; never call it harness or formal evidence, and
never invent a `PASS`/`FAIL` verdict in the shape the harness would have
produced. Never put secrets, credentials, OTP, link payloads, tokens, or keys
on the clipboard or into UI automation.

## Knowledge journal

Journal maintenance is service work and must not consume the main model's
context: never read or edit [JOURNAL.md](JOURNAL.md) or
[PROBLEMS.md](PROBLEMS.md) yourself.

After the worker returns its result contract, retain the full result in the
task context. Launch the journal subagent when at least one of these holds:

- `status` is FAIL or BLOCKED
- an error occurred and is visible in `failures`
- the run succeeded after an error seen earlier in this task

For an error occurrence, send the result contract as `event: occurrence`. Save
the returned journal code in the task context. For a later successful retry,
send `event: resolution`, that saved `journal_code`, and the full successful
result contract. When a single PASS result records both an encountered failure
and its verified recovery, send it as `event: occurrence-and-resolution`.

Launch the journal subagent in the background with:

- `model="claude-4.6-sonnet-medium-thinking"`
- `subagent_type="generalPurpose"`

Send only: the absolute path to this skill folder, the worker's result
contract verbatim, the event type, the journal code for a resolution, today's
date, an optional importance flag when you judge the error important enough to
promote on first occurrence, and the instruction to follow the maintenance
rules in the preamble of `JOURNAL.md`.

The journal subagent performs all classification, type matching, counter
increments, solution recording, and promotion into PROBLEMS.md by itself. It
must return exactly one report line (defined in `JOURNAL.md`); retain it only
to link a later resolution. Never run two journal subagents against this skill
at once: queue later journal events until the active one completes. The worker
never reads or writes the journal and never promotes rules.

## Failure policy

- A skipped test is not a pass when that gate was required. `server/internal/api`
  skips its entire package when PostgreSQL is unreachable and still prints `ok`,
  so confirm the required environment variables were set and the service was up.
- Worker-side failure handling (applying PROBLEMS.md entries, safe diagnostics
  only, no product-code edits, a single retry for demonstrated transient
  failures) is defined in WORKER.md. Hand environment repair to
  `tima-ops-runner`.

## Invariants

- Worker-side execution rules (working directory, Gradle 8.14+/JDK 17, and the
  protection of dev data and volumes) live in WORKER.md. Reject results produced
  in violation of them, such as evidence from wiped volumes or emulator state,
  or from a temporary toolchain.
- Never log access/refresh tokens, OTP, QR payloads or secrets, private keys,
  DPAPI material, media keys, escrow material, or presigned URLs — including
  in your own summaries.
