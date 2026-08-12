---
name: tima-ops-runner
description: Installs, verifies, starts, diagnoses, and safely repairs the TIMA Windows development environment, including Docker Desktop and Compose, WSL2 backend, Go, JDK 17, Gradle, Android SDK/AVD/ADB, the escrow stub, and the desktop peer. Use automatically for environment setup, service failures, tooling installation, emulator problems, or local runtime troubleshooting.
---

# TIMA operations runner

The main model leads the implementation task: it decides the needed operation,
sets its safety boundary, interprets results, and continues independent work.
Composer 2.5 Fast executes delegated operations; the journal subagent
maintains the error knowledge base.

## Required workflow

1. Read [REFERENCE.md](REFERENCE.md). Do not read [WORKER.md](WORKER.md),
   [PROBLEMS.md](PROBLEMS.md), or [JOURNAL.md](JOURNAL.md) yourself: WORKER.md
   and PROBLEMS.md are read by the worker, and JOURNAL.md belongs to the
   journal subagent (see "Knowledge journal" below).
2. Inspect state first. Never start a second Compose stack, emulator, or visible
   Tima process.
3. Classify actions:
   - safe and reversible: execute automatically within the user's requested
     scope
   - UAC, installation, data mutation, process termination, migration, restore,
     or external side effect: obtain explicit approval first
   - forbidden actions below: do not execute
4. Delegate work to one local shell subagent with:
   - `model="composer-2.5-fast"`
   - `subagent_type="shell"`
   - the minimal worker prompt below; standing rules and the result contract
     live in WORKER.md, which the worker reads itself
5. Use the loop `inspect → diagnose → safe fix → verify`. Allow at most two
   evidence-based fix attempts. Stop only this operation branch on an
   authorization prompt, destructive requirement, unknown root cause, or
   repeated failure; continue unrelated implementation work where safe.
6. Start a long-running independent operation in the background and continue
   independent implementation work. Await its result before a dependent action
   or before reporting final verification.
7. The main model independently checks the final health probe, version, process
   state, device list, or other authoritative evidence before reporting success.

## Safe automatic actions

- Read versions, process/service/device state, disk space, Compose config/ps,
  health endpoints, and Git status.
- Run the repository diagnostic `debug-tima.ps1` without switches; its `-Clean`
  and `-FreeRam` switches stop processes or containers and need approval.
- Start an already installed Docker Desktop or the requested development stack
  when no duplicate exists.
- Add the required ADB reverse mapping and launch an existing AVD when none is
  running.
- Start TIMA applications only through the root launchers listed in REFERENCE.md;
  for the whole dev stack that is `ЗАПУСК-TIMA.bat` (`start-tima.ps1`), and for a
  freshly built desktop peer `ОБНОВИТЬ-И-ЗАПУСТИТЬ-ПК.bat`
  (`update-and-run-desktop.ps1`).
- Run non-destructive verification, build, contract, and health commands.
- Refresh the current process PATH after an approved installation.

The user's direct request to start or verify an existing service is sufficient
authorization for these reversible actions.

## Approval required

Ask before:

- `winget`, SDK/tool downloads, Android license acceptance, UAC, Windows feature
  or WSL changes
- uninstalling an app, stopping/killing a process, replacing an AVD, or changing
  persistent user/machine environment variables
- Compose rebuilds that replace runtime components, volume restore, or any
  operation that may drop database, MinIO, or escrow state
- deleting local files/state, changing firewall/network rules, Git push, or
  triggering credentialed/external workflows

Explain the effect and retained-state risk in one sentence before asking.

## Forbidden actions

- Never run `docker compose down -v` or `down --volumes` while a verification or
  live journey depends on the current state.
- Never wipe/overwrite Docker volumes or an AVD without explicit destructive
  approval and a verified backup.
- Never use `emulator -wipe-data` when session/cache evidence matters.
- Never use `adb shell monkey` to launch TIMA; use `io.tima.app/.MainActivity`.
- Never invoke `TIMA.exe`, `go run ./cmd/tima`, or `go run ./cmd/escrow-stub`
  directly when a root launcher covers the case; fix a failing launcher instead
  of bypassing it, and never rebuild the server's development environment
  variables by hand.
- When the requested target is the development Compose project, invoke it as
  `docker compose -f server/deploy/docker-compose.dev.yml` and add only the
  documented `calls` or `escrow` profile when the task needs it. The isolated
  test database is `docker-compose.test.yml`; never point tests at the dev
  database `tima`, whose name `ResetForTests` rejects on purpose.
- Never update global Git configuration to bypass repository safety.
- Never fabricate substitute evidence for a run that did not happen.
- Never log or retain tokens, OTP, QR payloads/secrets, private keys, DPAPI
  material, media/escrow keys, credentials, or presigned URLs.

## Minimal worker prompt

Send only:

- the absolute path to this skill's WORKER.md with the instruction to read it
  first and follow it
- repository path and requested target state
- observed current state
- the exact allowed actions for this task, plus task-specific prohibitions
  beyond WORKER.md, if any
- the verification command

Do not restate standing rules, the PROBLEMS.md requirement, or the result
contract — they live in WORKER.md. Never send JOURNAL.md to the worker. The
worker replies with the compact result contract defined in WORKER.md
(`status`, `state`, `root_cause`, `actions`, `verification`, `changes`,
`user_action`).

## Reproducible operation scripts

Treat creation of a `.ps1` or `.bat` script as an allowed action when it makes
the requested operation or verification repeatable or more reliable. Include
it in task-specific allowed actions and let the worker apply the storage and
reporting rules in WORKER.md.

## Knowledge journal

Journal maintenance is service work and must not consume the main model's
context: never read or edit [JOURNAL.md](JOURNAL.md) or
[PROBLEMS.md](PROBLEMS.md) yourself.

After the worker returns its result contract, retain the full result in the
task context. Launch the journal subagent when at least one of these holds:

- `status` is FAIL or BLOCKED
- an error occurred and is visible in `actions` or `root_cause`
- the run succeeded after an error seen earlier in this task

For an error occurrence, send the result contract as `event: occurrence`. Save
the returned journal code in the task context. For a later successful retry,
send `event: resolution`, that saved `journal_code`, and the full successful
result contract. When a single PASS result records both an encountered error
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

## Installation authority

This repository has no installer script. Install one missing package at a time
after approval, verify it before continuing, and install only what the requested
target actually needs. Expected components and versions are listed in
REFERENCE.md under "Host tooling".

Several launchers hardcode absolute tool paths (REFERENCE.md, "Known launcher
defects"), so after installing a toolchain check whether a launcher still points
at the old location and repair the launcher rather than duplicating the
toolchain to match it.
