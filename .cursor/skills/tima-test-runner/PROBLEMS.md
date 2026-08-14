# TIMA test problems

Use each entry as `symptom → check → cause → fix → verify`. Do not apply a fix
until its stated cause is demonstrated.

## Required server test reported green but did not run

- Symptom: `server/internal/api` prints `ok` with no assertions, or every test
  in it reports `SKIP`.
- Check: the executed test names, the `SKIP` lines, and whether
  `TIMA_TEST_DATABASE_URL` was set in the same process as `go test`.
- Cause: `setup(t)` in `server/internal/api/api_test.go` calls `t.Skipf` when
  PostgreSQL is unreachable, and the package still exits zero.
- Fix: start the test database
  (`docker compose -f server/deploy/docker-compose.test.yml up -d`), set
  `TIMA_TEST_DATABASE_URL` to a database whose name ends in `_test`, and rerun.
- Verify: expected test names execute, `SKIP` count is zero, and the process
  exits zero.

## Server on 8080 is unreachable

- Symptom: connection refused, timeout, or no response from
  `http://127.0.0.1:8080/healthz`.
- Check: `docker compose -f server/deploy/docker-compose.dev.yml ps`, the server
  window started by `start-tima.ps1`, `/healthz`, and port ownership.
- Cause: the dev stack or the host server process is stopped, or another process
  owns the port.
- Fix: hand repair to `tima-ops-runner`; the server is started by the root
  launcher, never by hand.
- Verify: `/healthz` returns success before retrying the check once.

## Server test is skipped on Redis or MinIO

- Symptom: `ws_test.go`, `ratelimit_test.go`, or `media_test.go` reports `SKIP`,
  or a realtime test times out.
- Check: `TIMA_TEST_REDIS_URL`, `TIMA_TEST_S3_ENDPOINT`, and the health of the
  `redis` and `minio` services in the dev stack.
- Cause: missing environment variable or an unhealthy service.
- Fix: start the dev stack, set the variables, and rerun. Do not substitute the
  remaining passing packages for the skipped gate.
- Verify: the named test files execute with no `SKIP` and exit successfully.

## Go command is unavailable

- Symptom: server test command raises `CommandNotFoundException`.
- Check: `Get-Command go`, `go version`, and the Go path recorded in the local
  environment note.
- Cause: Go is missing, or absent from this process's PATH — a freshly installed
  tool does not appear in an already-open shell.
- Fix: hand installation or a PATH refresh to `tima-ops-runner`.
- Verify: `go version` reports 1.25.x and the narrow package command executes.

## Gradle was invoked from the repository root

- Symptom: Gradle fails for lack of a settings script ("Directory does not
  contain a Gradle build"), or it runs and does nothing useful because no task
  matched.
- Check: the working directory of the invocation, and whether a
  `settings.gradle.kts` exists there.
- Cause: the repository root has no settings script. The only Gradle builds are
  `client/` and `messenger-crypto/`; the vendored copy under `Kodium git/` is
  not ours and must never be built.
- Fix: `Push-Location client` or `Push-Location messenger-crypto` first, run the
  target there, then `Pop-Location`. Never run Gradle in both at once, since
  `client` composite-builds `../messenger-crypto`.
- Verify: the invocation resolves the intended `:composeApp:*` or
  `messenger-crypto` task and produces its JUnit XML.

## Gradle uses the wrong Java or version

- Symptom: toolchain error, unsupported class version, daemon startup failure.
- Check: `gradle -version`, `java -version`, `JAVA_HOME`, and whether the
  build's own `gradlew` was used.
- Cause: a different or temporary toolchain is on PATH, or PATH is stale.
- Fix: use the checked-in `gradlew` in `client` or `messenger-crypto`, or Gradle
  8.14.3 with JDK 17; refresh PATH. Never use a JDK or Gradle under `%TEMP%`.
- Verify: Gradle reports 8.14.3 on JVM 17, then rerun the narrow failed target.

## Gradle output is inconsistent or locked

- Symptom: locked cache/output, missing XML, unstable parallel failure.
- Check: running Gradle/Java processes and other agent jobs.
- Cause: multiple heavy Gradle invocations share one checkout, or Gradle runs in
  `client` and `messenger-crypto` at once — `client/settings.gradle.kts`
  composite-builds `../messenger-crypto`, so both own the same outputs.
- Fix: wait for the active run; serialize heavy Gradle tasks. Do not kill a
  healthy build merely to start another.
- Verify: one invocation owns the outputs and emits current JUnit XML.

## Test appears cached when execution was required

- Symptom: a test task is `UP-TO-DATE`, or JUnit XML is older than this run.
- Check: the Gradle task outcome and the timestamps of
  `client/composeApp/build/test-results/desktopTest/TEST-*.xml` and
  `messenger-crypto/build/test-results/test/TEST-*.xml`.
- Cause: nothing changed since the previous run, so Gradle reused the result;
  `go test` reuses its own cache the same way.
- Fix: rerun the narrow target with `--rerun-tasks`, or `go test -count=1`, when
  fresh execution is what the gate requires.
- Verify: report timestamps and executed test names belong to this run.

## Worker summary disagrees with test artifacts

- Symptom: the aggregate or per-suite counts differ from the JUnit XML or the
  Go per-package output.
- Check: parse authoritative artifact totals and timestamps independently.
- Cause: worker parsing or summarization error.
- Fix: report the artifact-derived result and note the corrected discrepancy;
  do not rerun a passing deterministic test solely to change the summary.
- Verify: passed/failed/skipped totals equal the source reports.
