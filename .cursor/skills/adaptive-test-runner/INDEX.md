# Rule index

The only file the worker reads before executing. Everything else is opened on
demand, which is why the number of rules here does not matter — thirty or three
hundred, the worker still reads a handful of rows and opens at most one or two
entries.

Maintained by the journalist. The main model does not read this file.

## How the worker uses it

- Read only rows whose `tier` matches this run's tier or is `*`, and whose
  `stack` is empty or listed in the tier record.
- `preventive` rows: open the entry and apply it **before** executing.
- `curative` rows: do not open. Note that they exist; open one only if its
  symptom key matches something that actually went wrong.
- `absorbed` in the file column means the rule is already inlined into a tier
  script. Skip it — obeying it twice is harmless but pointless.

## Rows

| code | kind | tier | stack | symptom key | file |
|---|---|---|---|---|---|
| BASE-1 | preventive | * | | suite green, cases skipped | knowledge/universal.md |
| BASE-2 | preventive | * | | up-to-date / artifact older than run | knowledge/universal.md |
| BASE-3 | curative | * | | totals disagree with report | knowledge/universal.md |
| BASE-4 | curative | * | | result cited for wrong revision | knowledge/universal.md |
| BASE-5 | preventive | * | | exit zero, nothing executed | knowledge/universal.md |
| IMPORTED-TEST-1 | preventive | server-api | go,docker | API package green, no assertions or skipped | project/rules.md |
| IMPORTED-TEST-2 | preventive | server-full | go,docker | Redis, MinIO, or realtime test skipped | project/rules.md |
| IMPORTED-TEST-3 | curative | crypto-jvm | gradle | no Gradle settings script or useful task | project/rules.md |
| IMPORTED-TEST-4 | curative | * | go | Go command unavailable | knowledge/stack/go.md |
| IMPORTED-TEST-5 | curative | * | gradle | toolchain or unsupported class version | knowledge/stack/gradle.md |
| IMPORTED-TEST-6 | curative | * | gradle | locked output or missing XML | knowledge/stack/gradle.md |
