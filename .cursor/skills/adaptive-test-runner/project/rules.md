# Project rules

Rules naming TIMA-specific paths, packages, ports, services, or variables.

## API package reported green without running
- code: IMPORTED-TEST-1
- kind: preventive
- tier: server-api
- symptom: server/internal/api prints ok with no assertions or every test is skipped.
- check: inspect executed names, SKIP lines, and TIMA_TEST_DATABASE_URL in the same test process.
- cause: server/internal/api/api_test.go skips when PostgreSQL is unreachable while Go exits zero.
- fix: start server/deploy/docker-compose.test.yml, set TIMA_TEST_DATABASE_URL to the isolated tima_test database, then rerun.
- verify: expected API cases execute, SKIP is zero, and the process exits zero.

## Redis or MinIO server test skipped
- code: IMPORTED-TEST-2
- kind: preventive
- tier: server-full
- symptom: ws_test.go, ratelimit_test.go, or media_test.go is skipped, or a realtime test times out.
- check: inspect TIMA_TEST_REDIS_URL, TIMA_TEST_S3_ENDPOINT, and health of Redis and MinIO in server/deploy/docker-compose.dev.yml.
- cause: a required environment variable is missing or the named service is unhealthy.
- fix: start the dev storage stack, set the variables in the test process, and rerun.
- verify: required named tests execute with no SKIP and exit successfully.

## Gradle invoked outside a TIMA build
- code: IMPORTED-TEST-3
- kind: curative
- tier: crypto-jvm
- symptom: Gradle cannot find a settings script or resolves no useful task.
- check: inspect the invocation directory and its settings.gradle.kts.
- cause: the repository root is not a Gradle build; TIMA builds are messenger-crypto and client, while the vendored Kodium git copy is not a target.
- fix: enter client or messenger-crypto for the documented target, then return to the repository root.
- verify: the intended task resolves and writes its JUnit XML artifact.
