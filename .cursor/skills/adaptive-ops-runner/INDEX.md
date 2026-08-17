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
| BASE-6 | curative | * | | corrupt evidence screenshot | knowledge/universal.md |
| BASE-7 | preventive | * | | secret in logs or evidence | knowledge/universal.md |
| IMPORTED-TEST-7 | curative | full-runtime | launcher | server health endpoint unreachable | project/rules.md |
| IMPORTED-OPS-1 | curative | * | git | dubious ownership shared checkout | project/rules.md |
| IMPORTED-OPS-2 | curative | * | docker | Docker engine unavailable | knowledge/stack/docker.md |
| IMPORTED-OPS-3 | curative | toolchain-verify | wsl | WSL update needs elevation | knowledge/stack/wsl.md |
| IMPORTED-OPS-4 | curative | * | docker | Docker cannot read VMware share | project/rules.md |
| IMPORTED-OPS-5 | curative | * | docker | required host tool unavailable | knowledge/stack/docker.md |
| IMPORTED-OPS-6 | curative | toolchain-verify | go | Go command unavailable | knowledge/stack/go.md |
| IMPORTED-OPS-7 | curative | android-emulator | android | Android SDK incomplete | knowledge/stack/android.md |
| IMPORTED-OPS-8 | curative | android-emulator | android | AVD on VMware share | project/rules.md |
| IMPORTED-OPS-9 | preventive | * | android | multiple Android devices | knowledge/stack/android.md |
| IMPORTED-OPS-10 | curative | phone-install | android | Android signature mismatch | knowledge/stack/android.md |
| IMPORTED-OPS-11 | curative | android-emulator | android | no matching APK ABI | project/rules.md |
| IMPORTED-OPS-12 | curative | android-emulator | android | UI coordinate tap misses | knowledge/stack/android.md |
| IMPORTED-OPS-13 | preventive | android-emulator | adb | media cannot reach host MinIO | project/rules.md |
| IMPORTED-OPS-14 | curative | desktop-peer-fresh | powershell | TIMA window not found | project/rules.md |
| IMPORTED-OPS-15 | curative | desktop-peer-fresh | powershell | multiple TIMA processes | project/rules.md |
| IMPORTED-OPS-16 | curative | desktop-peer-fresh | powershell | Windows click not delivered | project/rules.md |
| IMPORTED-OPS-17 | curative | * | powershell | ToHexString unavailable | knowledge/stack/powershell.md |
| IMPORTED-OPS-18 | curative | * | powershell | HttpClientHandler unavailable | knowledge/stack/powershell.md |
| IMPORTED-OPS-19 | curative | * | powershell | elevated helper opaque error | knowledge/stack/powershell.md |
| IMPORTED-OPS-20 | curative | full-runtime | launcher | outbox fault misses durable boundary | project/rules.md |
