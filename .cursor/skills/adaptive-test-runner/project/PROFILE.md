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
requires:  Go 1.25.x; no runtime services
forbids:   testing server/internal/api here; using a temporary toolchain
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
proves:    Server HTTP API tests execute against the isolated PostgreSQL test database with no skips.
stack:     go, docker
setup:     none
run:
  - docker compose -f server/deploy/docker-compose.test.yml up -d
  - $env:TIMA_TEST_DATABASE_URL = "postgres://tima:tima-test-only@localhost:55432/tima_test"
  - Push-Location server; go test -count=1 ./internal/api; Pop-Location
artifact:  command output with named API tests and an explicit zero SKIP count; no JUnit artifact is produced
requires:  isolated test Compose project ready on 55432; TIMA_TEST_DATABASE_URL in the test process
forbids:   pointing at dev database tima; removing test volumes; treating skipped tests as a pass
frozen:    —
runs_unfrozen: 0

## tier: server-full
proves:    All Go server tests execute against test PostgreSQL plus required Redis and MinIO services.
stack:     go, docker
setup:     none
run:
  - docker compose -f server/deploy/docker-compose.test.yml up -d
  - docker compose -f server/deploy/docker-compose.dev.yml up -d
  - $env:TIMA_TEST_DATABASE_URL = "postgres://tima:tima-test-only@localhost:55432/tima_test"; $env:TIMA_TEST_REDIS_URL = "redis://:tima-dev-only@localhost:6379"; $env:TIMA_TEST_S3_ENDPOINT = "http://localhost:9000"
  - Push-Location server; go test -count=1 ./...; Pop-Location
artifact:  command output with per-package test results and explicit zero SKIP count for required API, Redis, and MinIO tests
requires:  test PostgreSQL on 55432; dev Redis and MinIO healthy; variables set in the test process
forbids:   pointing at dev database tima; removing volumes; accepting skipped realtime or media tests
frozen:    —
runs_unfrozen: 0

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
