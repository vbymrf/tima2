> **⚠ УСТАРЕЛО В ЧАСТИ КЛИЕНТА · 2026-08-20.** Дерево `client/` удалено (этап К0.4
> [дорожной карты](../../../../doc_mig/ДОРОЖНАЯ-КАРТА.md)); клиент строится заново по
> [Plan.md](../../../../doc_mig/Plan.md). Все тиры и команды, упоминающие
> `client/` или `:composeApp`, **не работают** — их регистр переписывается на этапе
> К1.8 вместе с появлением CI. Тиры `go-unit` и `messenger-crypto` действуют:
> ни сервер, ни крипто-модуль не тронуты. Исходники v1 — тег `ref/client-v1`.

# TIMA test tier registry

Written by the main model and read by the worker. Work from the repository
root in PowerShell. The repository has no CI or acceptance harness: a manual
two-peer journey is reported as an observation against a named commit, never as
a harness verdict.

`server/` is the Go module; `messenger-crypto/` and `client/` are separate
Gradle builds. `client` composite-builds `../messenger-crypto`, so they never
run heavy Gradle work concurrently. Use checked-in `gradlew`, JDK 17 and Gradle
8.14+. Go has no JUnit report: its fresh, per-package output and explicit
`SKIP` lines are the authoritative evidence.

The test Compose project listens on 55432. The dev database `tima` must never
be used: `ResetForTests` truncates tables. `docker compose down -v`,
`down --volumes`, and emulator wiping are forbidden for every tier.

## Tiers

## tier: go-unit
proves:    Go production code builds, vets, and unit tests outside API integration pass.
stack:     go
setup:     none
run:
  - Push-Location server; go build ./...; go vet ./...; go test -count=1 ./internal/crypto ./internal/pii ./internal/escrow ./internal/calls; Pop-Location
artifact:  command output with per-package test results; no JUnit artifact is produced
requires:  Go toolchain satisfying server/go.mod, which pins `toolchain go1.26.7`; no runtime services
forbids:   testing server/internal/api here; overriding the pinned toolchain — GOTOOLCHAIN=local with an older local Go, or a hand-picked patch
frozen:    —
runs_unfrozen: 0

## tier: crypto-jvm
proves:    The messenger-crypto Kotlin/JVM library tests pass.
stack:     gradle
setup:     none
run:
  - Push-Location messenger-crypto; .\gradlew --no-daemon --rerun-tasks test; Pop-Location
artifact:  messenger-crypto/build/test-results/test/TEST-*.xml
requires:  JDK 17; no concurrent Gradle invocation in client
forbids:   running Gradle from the repository root or a temporary toolchain
frozen:    —
runs_unfrozen: 0

## tier: client-desktop
proves:    Compose desktop tests pass, including shared client and composite crypto code.
stack:     gradle
setup:     none
run:
  - Push-Location client; .\gradlew --no-daemon --rerun-tasks :composeApp:desktopTest; Pop-Location
artifact:  client/composeApp/build/test-results/desktopTest/TEST-*.xml
requires:  JDK 17; no concurrent Gradle invocation in messenger-crypto
forbids:   running Gradle from the repository root or a temporary toolchain
frozen:    —
runs_unfrozen: 0

## tier: server-api
proves:    Server HTTP API tests execute against an isolated PostgreSQL test database.
stack:     go
setup:     none
run:
  - # Compose path (build machine). Docker-free path: see the note under this tier.
  - docker compose -f server/deploy/docker-compose.test.yml up -d
  - $env:TIMA_TEST_DATABASE_URL = "postgres://tima:tima-test-only@localhost:55432/tima_test"
  - Push-Location server; go test -count=1 ./internal/api; Pop-Location
artifact:  command output listing named API tests, plus the SKIP count stated explicitly
requires:  a PostgreSQL whose database name ends in _test, reachable at TIMA_TEST_DATABASE_URL
forbids:   pointing at the dev database tima; removing test volumes; reporting this tier as full API coverage
expects:   exactly 62 PASS and 8 SKIP out of 70. The 8 need Redis (6) and MinIO (2); only server-full covers them
frozen:    —
runs_unfrozen: 0

> **Two ways to satisfy this tier, and the connection string differs between them.**
> Compose uses `tima-test-only` on port 55432; a locally installed PostgreSQL uses
> whatever it was initialised with — typically `tima-dev-only` on 5432. Take the URL
> from the machine, not from this file: a wrong URL surfaces as `Skipf`, which reads
> as a pass. Where Docker is deliberately unused, the exact commands live in
> `doc_mig/ОКРУЖЕНИЕ.local.md` (untracked, per-machine).
>
> **Why the skip count is written down.** `internal/api/api_test.go:52` calls
> `t.Skipf` when the database is unreachable, so `go test ./...` exits 0 having run
> nothing at all. A run of this tier is evidence only if the SKIP count is quoted
> and equals the expected one.

## tier: server-full
proves:    Every Go server test executes — 118 of 118, no skips — against PostgreSQL, Redis and MinIO.
stack:     go
setup:     none
run:
  - # Compose path (build machine); Docker-free path in the note below.
  - docker compose -f server/deploy/docker-compose.test.yml up -d
  - docker compose -f server/deploy/docker-compose.dev.yml up -d
  - $env:TIMA_TEST_DATABASE_URL = "postgres://tima:tima-test-only@localhost:55432/tima_test"; $env:TIMA_TEST_REDIS_URL = "redis://:tima-dev-only@localhost:6379"; $env:TIMA_TEST_S3_ENDPOINT = "http://localhost:9000"
  - Push-Location server; go test -count=1 -v ./...; Pop-Location
artifact:  per-package results plus counted RUN / PASS / FAIL / SKIP lines from the -v output
requires:  PostgreSQL with a _test database; Redis; S3-compatible storage with credentials tima-admin / tima-dev-only — the media-test bucket is created by the test, not by hand
forbids:   pointing at the dev database tima; removing volumes; accepting any SKIP in realtime or media tests
expects:   RUN 118, PASS 118, FAIL 0, SKIP 0. Measured 2026-08-20 on toolchain 1.26.7: 15 s wall clock
frozen:    —
runs_unfrozen: 0

> **Redis must match the password, not merely be reachable.** A Redis with no
> password answers `ERR Client sent AUTH, but no password is set` to the URL above,
> and the test skips — so a live service reads as an absent one. Against a
> passwordless Redis use `redis://localhost:6379`.
>
> **MinIO is one binary and needs no Docker:** `minio server <dir>` with
> `MINIO_ROOT_USER=tima-admin` and `MINIO_ROOT_PASSWORD=tima-dev-only`.
>
> **How to count.** `go test` prints `ok` for a package in which every test was
> skipped, so `ok` proves nothing by itself. The evidence is counted lines from
> `-v` output: `grep -c "^=== RUN   Test"` against `--- PASS`, `--- FAIL`,
> `--- SKIP`.

## tier: android-compile
proves:    Shared client code compiles for Android and a debug APK can be assembled.
stack:     gradle, android
setup:     none
run:
  - Push-Location client; .\gradlew --no-daemon --rerun-tasks :composeApp:compileDebugKotlinAndroid :composeApp:assembleDebug; Pop-Location
artifact:  client/composeApp/build/outputs/apk/debug/composeApp-debug.apk
requires:  JDK 17; ANDROID_HOME; Android SDK platform 35 and build-tools 35.0.0
forbids:   claiming Android runtime testing; concurrent Gradle work in messenger-crypto
frozen:    —
runs_unfrozen: 0

## tier: desktop-dist
proves:    A desktop distributable can be created from the current client sources.
stack:     gradle
setup:     none
run:
  - Push-Location client; .\gradlew --no-daemon --rerun-tasks :composeApp:createDistributable; Pop-Location
artifact:  client/composeApp/build/compose/binaries/main/app/TIMA/TIMA.exe
requires:  JDK 17; no concurrent Gradle invocation in messenger-crypto
forbids:   treating a pre-existing packaged executable as fresh output
frozen:    —
runs_unfrozen: 0
