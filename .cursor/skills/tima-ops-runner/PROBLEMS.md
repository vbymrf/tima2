# TIMA operations problems

Apply only entries whose cause is demonstrated. Format is
`symptom → check → cause → fix → verify`.

## Git reports dubious ownership on a shared checkout

- Symptom: Git refuses operations because the intended checkout has dubious
  ownership.
- Check: confirm the current path is the intended checkout and who owns it. The
  primary checkout `C:\!TIMA2` is owned by the current user and needs no
  override; this appears when working from the VMware shared drive `Z:` or from
  a copy created by another account.
- Cause: VMware shared-folder ownership cannot be mapped normally, or the
  directory was created by a different user.
- Fix: use a command-scoped `git -c safe.directory='<confirmed-checkout>' ...`
  override. Never change global Git configuration and never use wildcard trust.
- Verify: the requested read-only Git command succeeds for this checkout only.

## Docker Desktop does not become ready

- Symptom: Docker CLI exists but engine calls fail or hang.
- Check: the Docker Desktop process
  (`%LOCALAPPDATA%\Programs\DockerDesktop\Docker Desktop.exe`),
  `docker version`, `docker info`, WSL status, virtualization availability,
  disk, and memory.
- Cause: Desktop is stopped, the WSL2 backend is stale, required virtualization
  is unavailable, or the VM is resource-starved.
- Fix: start Desktop; with approval update/restart WSL and Docker. Check
  Docker/WSL memory in `.\ОТЛАДКА-TIMA.bat` output before heavy builds, and if
  the VMware guest has only two vCPUs, increase its allocation first.
- Verify: both client and server versions return, Compose v2 is present, and
  `docker compose -f server/deploy/docker-compose.dev.yml config` succeeds.

## WSL update fails without elevation

- Symptom: `wsl --update` or feature repair returns access/elevation errors.
- Check: `wsl --status`, current user elevation, Windows feature state.
- Cause: the operation requires administrator privileges.
- Fix: request approval, run the exact elevated update (use
  `wsl --update --web-download` when Store delivery is unavailable), then
  `wsl --shutdown` and restart Docker Desktop.
- Verify: WSL status is healthy and Docker engine responds.

## A file on the VMware share is unreadable inside a container

- Symptom: a container cannot open a file that plainly exists on `Z:` — for
  example `tar` failing on an archive whose host path is correct.
- Check: host path resolution, and whether Docker Desktop can mount that VMware
  shared directory at all.
- Cause: the VMware share is not reliably exposed to Docker's Linux VM, so the
  bind mount resolves to nothing usable.
- Fix: stage the file on local `C:` first (for example `%LOCALAPPDATA%\Temp`),
  verify its size or checksum after the copy, and mount that local directory
  read-only. Never hand a container a path on `Z:`.
- Verify: a disposable container lists or tests the staged file successfully
  before any operation that consumes it.

## A required host component is missing or PATH is stale

- Symptom: Docker, JDK, Git, Gradle, Go, or an Android SDK component is
  reported missing.
- Check: `Get-Command` plus the version flag for that tool, and the expected
  location — Go at `C:\Program Files\Go`, Gradle at
  `%LOCALAPPDATA%\Programs\Gradle\gradle-8.14.3`, Android SDK at
  `%LOCALAPPDATA%\Android\Sdk`.
- Cause: the package is absent, is the wrong version, or the current process
  has a stale PATH.
- Fix: this repository has no installer script. After approval install one
  component at a time, persistently, then refresh the current process PATH and
  re-verify. Never install a JDK or Gradle under `%TEMP%`.
- Verify: the tool reports the expected version from its persistent path.

## Go is unavailable for server tests

- Symptom: `go` raises `CommandNotFoundException` and server tests do not start.
- Check: `Get-Command go`, `go version`, and `C:\Program Files\Go\bin\go.exe`.
- Cause: Go is absent or not on this process's PATH.
- Fix: request approval, install Go 1.25.x persistently, refresh PATH. If Go is
  present but a launcher cannot find it, the launcher's hardcoded path is the
  defect — repair the launcher rather than relocating the toolchain.
- Verify: `go version` reports 1.25.x and a narrow package test executes.

## Android SDK is partial

- Symptom: `sdkmanager`, platform-tools, emulator, platform, or build-tools is
  missing; package installation previously returned failure.
- Check: the directories under `%LOCALAPPDATA%\Android\Sdk` and
  `sdkmanager --list`.
- Cause: interrupted download or license acceptance.
- Fix: after approval accept licenses and install only the missing packages
  (build-tools 35.0.0 and platform 35 are the expected baseline).
- Verify: `adb version`, `emulator -list-avds` showing `tima_test`, and
  `:composeApp:assembleDebug` all succeed.

## AVD is slow or unreliable on Z:

- Symptom: emulator boot stalls, snapshots corrupt, or disk I/O is excessive.
- Check: the AVD `path` in `%USERPROFILE%\.android\avd\*.ini`.
- Cause: the active AVD lives on the VMware shared drive `Z:` instead of local
  storage.
- Fix: stop the emulator with approval and restore or recreate the AVD under
  `%USERPROFILE%\.android\avd` on local `C:`. An active AVD must never live on
  `Z:`.
- Verify: the AVD path is local and one emulator reaches boot completed.

## More than one Android device is present

- Symptom: ADB says more than one device/emulator, or actions hit the wrong peer.
- Check: `adb devices -l`.
- Cause: a second or stale emulator is running, or a phone is attached as well.
- Fix: select the intended serial from `adb devices -l` and add `-s <serial>` to
  every command. Stop a stale second peer only after approval.
- Verify: package, reverse mapping, and activity belong to the intended serial.

## Android app signature mismatch

- Symptom: install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- Check: whether `io.tima.app` is installed and its signing identity.
- Cause: the existing package was signed with another key.
- Fix: warn that app session/cache will be lost, obtain approval, uninstall the
  old package, then install the intended APK — for a debug build that is
  `client/composeApp/build/outputs/apk/debug/composeApp-debug.apk`.
- Verify: `am start -n io.tima.app/.MainActivity` starts the app, never
  `monkey`, and expected state is visible.

## Android APK will not install on the emulator

- Symptom: install fails with `INSTALL_FAILED_NO_MATCHING_ABIS`.
- Check: the emulator image ABI and `ndk { abiFilters += "arm64-v8a" }` in
  `client/composeApp/build.gradle.kts`.
- Cause: only `arm64-v8a` is built, so a normal x86_64 emulator image cannot
  install the debug APK.
- Fix: stop retrying the emulator install. Real Android verification needs a
  physical phone over USB (`ОБНОВИТЬ-ТЕЛЕФОН.bat`,
  `update-and-install-phone.ps1`); an arm64 system image is the only emulator
  alternative and needs approval to add.
- Verify: the APK installs on the intended `adb devices -l` serial and
  `io.tima.app/.MainActivity` starts, or the Android runtime claim is reported
  as BLOCKED rather than passed.

## Android UI automation taps or scrolls the wrong control

- Symptom: ADB tap/swipe has no effect or selects a different element.
- Check: fresh `uiautomator dump`, bounds, orientation, resolution, and current
  screen state.
- Cause: coordinates were reused after layout/state changed.
- Fix: derive coordinates from the current XML. Prefer resource IDs/text and use
  manual clicks when coordinate automation is not reliable.
- Verify: dump the UI again and confirm the intended state transition.

## Android media cannot reach MinIO

- Symptom: messaging works but encrypted media upload or preview fails.
- Check: the selected device, the API and media endpoints, and
  `adb reverse --list` when using an emulator.
- Cause: the device cannot reach the host's `localhost:8080` and
  `localhost:9000`, where the API and the presigned media URLs point.
- Fix: add the missing mappings — `adb reverse tcp:8080 tcp:8080` and
  `adb reverse tcp:9000 tcp:9000`, which is what `start-android.ps1` does. With
  several devices pass `-s <serial>`.
- Verify: media sends and in-app decrypt/preview succeeds.

## TIMA window cannot be found

- Symptom: the process exists but `FindWindow` returns no handle.
- Check: enumerate visible top-level windows and their owning process IDs.
- Cause: timing, title matching, hidden characters, or a background helper
  process was selected.
- Fix: use `EnumWindows`, the exact visible title, and
  `GetWindowThreadProcessId`; wait for UI startup.
- Verify: the resolved handle has a valid rectangle and belongs to the visible
  TIMA window.

## Multiple TIMA.exe processes or wrong activation target

- Symptom: input goes nowhere or `AppActivate` targets the wrong PID.
- Check: CIM process list plus the visible-window owner PID.
- Cause: a launcher helper process or a stale instance coexists — for example
  the packaged `ПРИЛОЖЕНИЕ-ПК.bat` app alongside a `:composeApp:run` build.
- Fix: identify the visible owner. Stop stale processes only with approval;
  never kill all processes blindly during evidence collection.
- Verify: one intended visible window receives focus and action.

## Windows clicks or SendKeys do not reach TIMA

- Symptom: scripted send/edit/delete produces no state change.
- Check: foreground window, DPI scaling, window rectangle, and screenshot before
  and after.
- Cause: `AppActivate` alone did not grant foreground focus, or coordinates were
  not DPI-correct.
- Fix: bring the verified handle to top, activate it, click the title bar, then
  use coordinates relative to the live window. Prefer manual clicks during a
  manual journey when focus remains unreliable.
- Verify: UI and peer state both show the requested operation.

## PowerShell ToHexString is unavailable

- Symptom: `[Convert]::ToHexString` raises `MethodNotFound`.
- Check: PowerShell/.NET runtime version.
- Cause: Windows PowerShell 5.1 lacks the newer API.
- Fix: use
  `[BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()`.
- Verify: output is lowercase hex of expected length without logging secrets.

## PowerShell HttpClientHandler type is missing

- Symptom: a local PowerShell HTTP helper reports `TypeNotFound` for
  `System.Net.Http.HttpClientHandler`.
- Check: whether `System.Net.Http` was loaded.
- Cause: Windows PowerShell did not auto-load the assembly.
- Fix: add `Add-Type -AssemblyName System.Net.Http` before constructing types.
- Verify: the helper starts and forwards a harmless health request.

## Elevated helper exits without useful diagnostics

- Symptom: helper returns an opaque negative code, often after UAC cancellation
  or parsing failure.
- Check: whether elevation was accepted and capture stdout/stderr to a local
  diagnostic file that contains no secrets.
- Cause: cancelled UAC or an error hidden by the elevated process boundary.
- Fix: request explicit approval; run a minimal wrapper and inspect captured
  diagnostics. Fall back to manual UI action instead of repeated blind retries.
- Verify: helper starts cleanly or the manual action is independently observed.

## Forced outbox fault does not occur at the durable boundary

- Symptom: the send fails too early, or the message sends normally.
- Check: the proxy route, readiness, and whether exactly the serialized message
  POST is aborted after the local row was stored and encrypted.
- Cause: wrong endpoint interception, or the proxy was not loaded.
- Fix: use a one-shot loopback proxy that fails only `POST /api/v1/messages`,
  then return to the normal endpoint for the restart.
- Verify: the durable row returns to `QUEUED` instead of being lost, the restart
  resends the same `client_msg_id` to `SENT` (the server deduplicates it), and
  the peer receives it once. Remove the proxy and task-only environment
  variables afterwards.

## Evidence screenshot is corrupt

- Symptom: image decoder throws `OutOfMemoryException` or cannot open a PNG.
- Check: decode every file and compare it to the step it documents.
- Cause: the capture was truncated or corrupted.
- Fix: recapture the same state. Do not substitute another step's image.
- Verify: all evidence decodes, corresponds to its step, and is secret-free.

## Secrets appear in logs or evidence

- Symptom: output includes a token, OTP, QR/link payload, key material, DPAPI
  bytes, credentials, or a presigned URL.
- Check: stop further output and identify every retained copy.
- Cause: verbose command or UI capture crossed a secret boundary.
- Fix: do not repeat the value. Remove unsafe transient output from proposed
  evidence and recapture a redacted state; do not modify committed history
  without explicit incident handling.
- Verify: retained reports prove behavior without secret material.
