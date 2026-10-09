# Gradle rules

## Wrong Java or Gradle version
- code: IMPORTED-TEST-5
- kind: curative
- tier: *
- symptom: a toolchain error, unsupported class version, or Gradle daemon startup failure.
- check: inspect gradle -version, java -version, JAVA_HOME, and whether the build's gradlew was used.
- cause: the active JDK or Gradle is incompatible, temporary, or PATH is stale.
- fix: use the checked-in Gradle wrapper with JDK 17, or refresh the persistent toolchain PATH.
- verify: Gradle reports the required version on JVM 17 and the narrow target runs.

## Shared Gradle outputs locked or inconsistent
- code: IMPORTED-TEST-6
- kind: curative
- tier: *
- symptom: locked Gradle cache/output, missing XML, or an unstable parallel build failure.
- check: inspect active Gradle and Java processes plus other agent jobs.
- cause: concurrent heavy Gradle invocations own shared outputs, including composite builds.
- fix: wait for the active build and serialize Gradle work. Do not kill a healthy build.
- verify: one invocation owns the outputs and writes fresh JUnit XML.

## Test task reused although what it checks has changed
- code: TEST-005
- kind: preventive
- tier: *
- symptom: a test task is UP-TO-DATE (or prints nothing under `-q`) after edits to files its tests read from disk; the old report is reused.
- check: run without `-q` and look for `UP-TO-DATE`/`FROM-CACHE` on the test task; compare its XML time with the run start.
- cause: Gradle tracks only declared inputs; files a test opens itself (sources of other modules, scripts, documents) are invisible to it.
- fix: declare them (`inputs.dir(...)`, `inputs.file(...)`) or `outputs.upToDateWhen { false }` on that task; for a single run — the built-in `--rerun` option after the task name.
- verify: the task executes after such an edit and its XML is newer than the run start.
