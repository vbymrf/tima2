> **⚠ УСТАРЕЛО В ЧАСТИ КЛИЕНТА · 2026-08-20.** Дерево `client/` удалено (этап К0.4),
> клиент строится заново — `doc_mig/Plan.md`. Правила запуска приложения и десктопа
> не действуют; серверные и крипто-правила действуют. Исходники v1 — тег `ref/client-v1`.

# TIMA project rules

## TIMA server health endpoint unreachable
- code: IMPORTED-TEST-7
- kind: curative
- tier: full-runtime
- symptom: http://127.0.0.1:8080/healthz is refused, times out, or returns no response.
- check: inspect dev Compose state, the launcher-created server window, health output, and port ownership.
- cause: the dev storage stack or host server is stopped, or another process owns the port.
- fix: use the root launcher after checking for duplicates; repair a failing launcher rather than starting the server directly.
- verify: /healthz succeeds before one retry of the dependent check.

## Git dubious ownership on shared checkout
- code: IMPORTED-OPS-1
- kind: curative
- tier: *
- symptom: Git refuses an operation because the intended checkout has dubious ownership.
- check: confirm the current path is the intended shared checkout and identify its owner.
- cause: VMware ownership mapping or a checkout created by another account.
- fix: use a command-scoped `git -c safe.directory='<confirmed-checkout>'` override; never change global Git configuration.
- verify: the requested Git command succeeds only for that confirmed checkout.

## VMware share cannot be mounted by Docker
- code: IMPORTED-OPS-4
- kind: curative
- tier: *
- symptom: a container cannot read a file that exists on the Z: VMware share.
- check: verify the host path and Docker Desktop access to that share.
- cause: the VMware shared directory is not reliably available in Docker's Linux VM.
- fix: stage the file on local C:, verify its size or checksum, and mount that local directory read-only.
- verify: a disposable container can list or test the staged file.

## AVD stored on a VMware share
- code: IMPORTED-OPS-8
- kind: curative
- tier: android-emulator
- symptom: tima_test boots slowly, corrupts snapshots, or has excessive I/O.
- check: inspect the AVD path in %USERPROFILE%\.android\avd\*.ini.
- cause: the active AVD is on Z: rather than local storage.
- fix: after approval, restore or recreate it under %USERPROFILE%\.android\avd on C:.
- verify: the local AVD reaches boot completed.

## TIMA Android ABI mismatch
- code: IMPORTED-OPS-11
- kind: curative
- tier: android-emulator
- symptom: APK install fails with INSTALL_FAILED_NO_MATCHING_ABIS.
- check: inspect the device ABI and arm64-v8a filter in client/composeApp/build.gradle.kts.
- cause: TIMA builds only arm64-v8a while the usual emulator is x86_64.
- fix: report Android runtime verification blocked; use an intended USB phone or an approved arm64 emulator image.
- verify: the APK installs on the chosen serial and io.tima.app/.MainActivity starts.

## Android media cannot reach TIMA MinIO
- code: IMPORTED-OPS-13
- kind: preventive
- tier: android-emulator
- symptom: messages work but encrypted media upload or preview fails.
- check: inspect the selected device plus adb reverse --list for ports 8080 and 9000.
- cause: the emulator cannot access the host API and presigned-media endpoints.
- fix: use the launcher or add device-specific reverse mappings for 8080 and 9000.
- verify: media sends and in-app decrypt/preview succeeds.

## TIMA window cannot be resolved
- code: IMPORTED-OPS-14
- kind: curative
- tier: desktop-peer-fresh
- symptom: a TIMA process exists but FindWindow has no valid handle.
- check: enumerate visible top-level windows and owner PIDs.
- cause: timing, title matching, hidden characters, or a background helper was selected.
- fix: use EnumWindows, the exact visible title, and GetWindowThreadProcessId after UI startup.
- verify: the handle has a rectangle and belongs to the visible TIMA window.

## More than one TIMA desktop process
- code: IMPORTED-OPS-15
- kind: curative
- tier: desktop-peer-fresh
- symptom: input targets the wrong PID or does nothing.
- check: compare CIM process list with the visible-window owner PID.
- cause: a launcher helper or stale packaged peer coexists with a fresh Gradle peer.
- fix: identify the visible owner; request approval before stopping a stale process.
- verify: exactly one intended visible window receives focus and input.

## Desktop click does not reach TIMA
- code: IMPORTED-OPS-16
- kind: curative
- tier: desktop-peer-fresh
- symptom: scripted UI action produces no state change.
- check: inspect foreground window, DPI scaling, live rectangle, and before/after screenshots.
- cause: activation did not give foreground focus or coordinates were not DPI-correct.
- fix: activate the verified window, click its title bar, and derive coordinates from its live bounds; prefer manual clicks when unreliable.
- verify: both UI and peer state show the requested transition.

## Outbox fault misses durable boundary
- code: IMPORTED-OPS-20
- kind: curative
- tier: full-runtime
- symptom: a forced send fault occurs too early or does not occur.
- check: inspect proxy route, readiness, and whether only POST /api/v1/messages is aborted after local persistence.
- cause: the wrong endpoint was intercepted or the proxy was not loaded.
- fix: use a one-shot loopback proxy for that POST, then restore the normal endpoint.
- verify: the durable row returns to QUEUED and a restart resends the same client_msg_id once.
