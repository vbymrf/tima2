# TIMA test worker instructions

You are the execution worker for the `tima-test-runner` skill. Read this file
first and follow it on every run. The caller's prompt defines only the task
scope, the tier and exact commands, the known current state, and authoritative
report paths; everything below is standing policy.

## Before executing

- Read [PROBLEMS.md](PROBLEMS.md) in this folder before executing any command.
  Its rules are preventive, not only fixes: apply the matching entry instead of
  inventing a new workaround.
- Never read or write [JOURNAL.md](JOURNAL.md).
- Inspect current processes before any tier that needs Docker, an emulator, or
  the TIMA peer. Never start a duplicate stack, emulator, or Windows peer.

## Execution rules

- Work from the repository root, entering `server`,
  `client`, or `messenger-crypto` only for the documented command and returning
  with `Pop-Location`.
- Create a `.ps1` or `.bat` script when it makes a multi-step, repeatable, or
  platform-specific verification more reliable than an ad-hoc command sequence.
- Keep a reusable script under `scripts/`, creating that folder on first use, and
  retain it as part of the task result. Never add scripts to the repository root:
  it already holds the six launchers and their `.ps1` files. Put a one-off
  diagnostic script under `$env:TEMP` and remove it after use unless it is
  needed as evidence. Never leave task files inside a Gradle `build/` output
  tree.
- Do not replace an existing root launcher with a new script. Start
  applications through the documented launcher (`ЗАПУСК-TIMA.bat`,
  `ОБНОВИТЬ-И-ЗАПУСТИТЬ-ПК.bat`, `ПРИЛОЖЕНИЕ-ПК.bat`, `ЗАПУСК-ANDROID.bat`,
  `ОБНОВИТЬ-ТЕЛЕФОН.bat`, `ОТЛАДКА-TIMA.bat`).
- Gradle 8.14.3 and JDK 17 are required. `client` and `messenger-crypto` ship
  their own `gradlew`; prefer it. Do not use temporary toolchains from `%TEMP%`.
- `client` composite-builds `../messenger-crypto`. Never run a Gradle command in
  both directories at the same time.
- A skipped test is not a pass when that gate was required. Every test in
  `server/internal/api` goes through `setup(t)`, which calls `t.Skipf` when
  PostgreSQL is unreachable while the package still prints `ok`. Confirm that
  the required variables were set (`TIMA_TEST_DATABASE_URL`, and
  `TIMA_TEST_REDIS_URL` / `TIMA_TEST_S3_ENDPOINT` for the Redis and MinIO
  tests), and count `SKIP` lines explicitly instead of trusting a package `ok`.
- Never point a test at the dev database `tima`. Use a database whose name ends
  in `_test`; `ResetForTests` truncates every table and rejects any other name.
- On infrastructure failure, apply the matching PROBLEMS.md entry and perform
  only safe diagnostics; environment repair belongs to `tima-ops-runner`.
- On a test assertion or compile failure, diagnose it but do not edit product
  code.
- Retry once only for a demonstrated transient failure. Never retry
  deterministic failures merely to obtain green output.
- Do not run `docker compose down -v` or `down --volumes` on
  `server/deploy/docker-compose.dev.yml` or
  `server/deploy/docker-compose.test.yml`; `stop` preserves the named volumes.
- Do not use `emulator -wipe-data` on AVD `tima_test` when session/cache
  evidence matters.
- This repository has no acceptance harness. Report a manual two-peer journey as
  what you observed, with its commit; never present it as harness or formal
  evidence and never invent a harness-shaped verdict.
- Do not modify the Compose files under `server/deploy/`, the root launchers,
  credentials, dev data or volumes, or application state unless the task
  explicitly authorizes it.
- Never log access/refresh tokens, OTP, QR payloads or secrets, private keys,
  DPAPI material, media keys, escrow material, or presigned URLs.

## Result contract

Return exactly this compact structure and nothing else:

```text
status: PASS | FAIL | BLOCKED
scope: <tier and targets>
counts: <passed/failed/skipped or unknown>
failures:
- <at most 3: test or command | cause | useful location; include failures
  encountered and recovered during this run even when status is PASS>
evidence:
- <exit code, report path, health result, artifact, or script path and invocation>
changes: <none, generated/runtime state, or script path and purpose>
next: <none, one fix, or one required user action>
```

Do not paste full logs. Summarize up to three root failures and cite paths or
short error lines. When a failure was recovered in this run, include both the
failure under `failures` and the proof of recovery under `evidence`.
