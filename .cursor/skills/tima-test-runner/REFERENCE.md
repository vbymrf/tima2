# TIMA test reference

All paths are relative to the repository root. Shell examples are PowerShell;
adapt them if this machine uses another. Read only the sections for the
requested tier.

Tool locations and what is or is not installed here are **not** in this file:
they differ per machine. They live in the untracked local environment note.

## What this repository actually contains

- `server/` — Go module `tima/server`. Commands `cmd/tima` and `cmd/escrow-stub`.
  Migrations in `server/migrations/` are embedded and applied by the code, not by
  a separate migrate container in dev.
- `messenger-crypto/` — Kotlin JVM library. Gradle task `test`, JUnit Platform.
  Wire codegen from `../schema/proto` runs inside this build; `vectors.json` from
  `../schema/test-vectors` is wired in as a test resource.
- `client/` — Gradle build `tima-client` with a single module `:composeApp`
  (Kotlin Multiplatform: `jvm("desktop")` and `androidTarget()`). Source sets:
  `commonMain`, `jvmCommon`, `desktopMain`, `androidMain`, and `desktopTest` —
  `desktopTest` is the only test source set. `settings.gradle.kts` pulls
  `../messenger-crypto` in as a composite build.
- `schema/` — `proto/` and `test-vectors/` only.

There is **no** CI in this repository, and no `.github/workflows/`, `infra/`,
`doc/08-quality/`, `schema/codegen/`, k6 scripts, integration harness module, or
acceptance harness. Never cite any of them as an authority and never present
their absence as a skipped gate — those gates do not exist here. Version
authority is the installed toolchain plus the Gradle files.

A `scripts/` directory does not exist yet either, but unlike the above it is
expected: it is where a worker puts a reusable test script it creates, rather
than in the crowded repository root.

## Preflight

```powershell
docker compose -f server/deploy/docker-compose.dev.yml ps
docker compose -f server/deploy/docker-compose.test.yml ps
go version
java -version
gradle -version
git status --short --branch
Get-PSDrive C
```

Required: Go 1.25.x, JDK 17, Gradle 8.14+ (or the checked-in `gradlew`). A
machine may have none of them — check the local environment note before
concluding that a tier failed. For an Android tier also check `ANDROID_HOME`:

```powershell
adb devices -l
Get-Process emulator,qemu-system-x86_64,TIMA -ErrorAction SilentlyContinue
```

## Test tiers

### T0: unit, no runtime services

```powershell
Push-Location server
go build ./...
go vet ./...
go test ./internal/crypto ./internal/pii ./internal/escrow ./internal/calls
Pop-Location

Push-Location messenger-crypto
.\gradlew --no-daemon test
Pop-Location

Push-Location client
.\gradlew --no-daemon :composeApp:desktopTest
Pop-Location
```

`server/internal/api` is deliberately absent from this tier: it needs a
database (T1).

### T1: server against PostgreSQL

Every test in `server/internal/api` goes through `setup(t)` in
`server/internal/api/api_test.go`, which calls `t.Skipf` when the database is
unreachable. A skipped `internal/api` package is a **failed gate, not a pass** —
the package silently reports `ok` with no assertions executed.

Preferred: the isolated test instance, because `ResetForTests` truncates every
table.

```powershell
docker compose -f server/deploy/docker-compose.test.yml up -d
# Пользователь, пароль и порт заданы в docker-compose.test.yml; пароль по
# умолчанию переопределяется TEST_POSTGRES_PASSWORD. Строка ниже — копия для
# удобства: при расхождении верен compose, а не этот файл.
$env:TIMA_TEST_DATABASE_URL = "postgres://tima:tima-test-only@localhost:55432/tima_test"
Push-Location server
go test ./internal/api
Pop-Location
```

Alternative: a database named `tima_test` inside the dev PostgreSQL on 5432 —
that is what `setup(t)` assumes when the variable is unset. The dev database
`tima` itself cannot be used: `ResetForTests` refuses any name without a
`_test` suffix, and that refusal is the only thing standing between a test run
and live dev data.

Migrations need no separate step. `setup(t)` calls `st.Migrate` over the
embedded FS, so any new file under `server/migrations/` is exercised by every
api test.

### T2: server against Redis and MinIO

Three tests skip on services beyond the database:

- `ws_test.go` and `ratelimit_test.go` — Redis, `TIMA_TEST_REDIS_URL`,
  default `redis://:tima-dev-only@localhost:6379`
- `media_test.go` — MinIO, `TIMA_TEST_S3_ENDPOINT`, default
  `http://localhost:9000`

Both services come from the dev stack:

```powershell
docker compose -f server/deploy/docker-compose.dev.yml up -d
# То же: значения — из compose, здесь копия.
$env:TIMA_TEST_DATABASE_URL = "postgres://tima:tima-test-only@localhost:55432/tima_test"
Push-Location server
go test ./...
Pop-Location
```

The dev stack alone is not enough for the api package: its database is `tima`,
so either keep the test compose running or create `tima_test` inside the dev
instance.

### T3: Android target

`:composeApp` has no Android unit-test source set, so there are no Android
tests to run. What can be proved is that the shared `commonMain`/`jvmCommon`
code compiles for Android, which the desktop tests do not cover:

```powershell
Push-Location client
.\gradlew --no-daemon :composeApp:compileDebugKotlinAndroid
.\gradlew --no-daemon :composeApp:assembleDebug
Pop-Location
```

APK: `client/composeApp/build/outputs/apk/debug/composeApp-debug.apk`. Requires
`ANDROID_HOME`. Only `arm64-v8a` is built, so a normal x86_64 emulator cannot
install it — Android runtime checks need a real phone.

### T4: desktop distributable

```powershell
Push-Location client
.\gradlew --no-daemon :composeApp:createDistributable
Pop-Location
```

Produces `client/composeApp/build/compose/binaries/main/app/TIMA/TIMA.exe`.
Only the AppImage/app format is configured; MSI is off because WiX needs the
blocked GitHub CDN.

### Live journeys

There is no acceptance harness. A two-peer journey is manual: dev stack, server,
Windows peer, and phone or emulator started through the root launchers (see the
`runtime-launchers` rule and `tima-ops-runner`). Record what you observed
yourself and never describe it as harness evidence.

## Authoritative outputs

- messenger-crypto JUnit XML:
  `messenger-crypto/build/test-results/test/TEST-*.xml`
- client desktop JUnit XML:
  `client/composeApp/build/test-results/desktopTest/TEST-*.xml`
- Gradle HTML: `*/build/reports/tests/**/index.html`
- Go has no JUnit output here: use the process exit code plus per-package lines,
  and count `SKIP` explicitly rather than trusting a package-level `ok`.

Recompute aggregate counts from these artifacts. Compare report timestamps when
fresh execution is required, and reject cached or stale evidence.

## Concurrency

- One Compose project per file. Shared ports: 5432, 6379, 9000/9001, 8080
  (server), 8090 (escrow-stub), 7880 (LiveKit); the test database uses 55432.
- `client` composite-builds `../messenger-crypto`. Never run a Gradle command in
  `client` and in `messenger-crypto` at the same time.
- Go and Gradle may run concurrently — they share no outputs.

## Cleanup

```powershell
Remove-Item Env:\TIMA_TEST_DATABASE_URL, Env:\TIMA_TEST_REDIS_URL, Env:\TIMA_TEST_S3_ENDPOINT -ErrorAction SilentlyContinue
docker compose -f server/deploy/docker-compose.test.yml stop
```

`stop` keeps the named volume; `down -v` destroys it.

## Current authorities

- `server/deploy/docker-compose.dev.yml`, `docker-compose.test.yml`
- `server/internal/api/api_test.go` — the skip contract for every server gate
- `client/settings.gradle.kts`, `client/composeApp/build.gradle.kts`
- `messenger-crypto/build.gradle.kts`
- `ПЛАН-РЕФАКТОРИНГА.md` — what is expected to pass and what is knowingly open
