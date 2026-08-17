# TIMA operations tier registry

Run from `C:\!TIMA2` in PowerShell and inspect state before changing it.
Expected tooling: Docker Desktop with WSL2, Go 1.25.x, Microsoft OpenJDK 17,
Gradle 8.14.3, Android SDK platform/build-tools 35, and AVD `tima_test`.
Storage Compose services are PostgreSQL 5432, Redis 6379, MinIO 9000/9001; the
isolated test database is 55432. The host server is 8080 and escrow stub 8090.

Every root launcher is authoritative and stays in the repository root. The
launcher `start-tima.ps1` presently hardcodes unavailable Go, Docker Desktop,
and Gradle locations. Repair it through `Get-Command`; never launch its server
or enclave target directly as a workaround. `run:` entries below that name a
launcher are not frozen scripts and must never be copied to `project/scripts/`.
Run test and build verification through the sibling `adaptive-test-runner`
skill; this operations profile records state and launcher evidence only.

## Tiers

## tier: toolchain-verify
proves:    Required host tooling is available from persistent locations in the current shell.
stack:     docker, wsl, go, gradle, android
setup:     none
run:
  - Get-Command docker, go, java, gradle; docker version; docker compose version; go version; java -version; gradle -version; wsl --status
artifact:  command output showing each required executable and version
requires:  no installation or persistent PATH change without approval
forbids:   downloading tools; using a JDK or Gradle under TEMP
frozen:    —
runs_unfrozen: 0

## tier: diagnostics
proves:    Current TIMA stack, listener, process, and resource state has been inspected.
stack:     powershell, docker
setup:     none
run:
  - .\ОТЛАДКА-TIMA.bat
artifact:  diagnostic output including listener and process state
requires:  root diagnostic launcher exists
forbids:   passing -Clean or -FreeRam without explicit approval
frozen:    —
runs_unfrozen: 0

## tier: dev-stack
proves:    Development storage services are running without duplicate instances.
stack:     docker
setup:     none
run:
  - docker compose -f server/deploy/docker-compose.dev.yml up -d
  - docker compose -f server/deploy/docker-compose.dev.yml ps
artifact:  Compose ps showing healthy PostgreSQL, Redis, and MinIO
requires:  Docker Desktop ready; no duplicate stack
forbids:   down -v; down --volumes; modifying Compose files
frozen:    —
runs_unfrozen: 0

## tier: test-db
proves:    The isolated PostgreSQL test service is running on port 55432.
stack:     docker
setup:     none
run:
  - docker compose -f server/deploy/docker-compose.test.yml up -d
  - docker compose -f server/deploy/docker-compose.test.yml ps
artifact:  Compose ps showing the test database ready on 55432
requires:  Docker Desktop ready; no duplicate test Compose project
forbids:   down -v; using the dev tima database for tests
frozen:    —
runs_unfrozen: 0

## tier: full-runtime
proves:    The launcher-managed TIMA runtime exposes server and escrow health endpoints.
stack:     docker, launcher
setup:     none
run:
  - .\ЗАПУСК-TIMA.bat
artifact:  successful http://127.0.0.1:8080/healthz and http://127.0.0.1:8090/v1/pubkey probes
requires:  no existing duplicate listener or visible desktop peer
forbids:   direct TIMA.exe or go run invocation; reconstructing environment variables
frozen:    —
runs_unfrozen: 0

## tier: desktop-peer-fresh
proves:    The current desktop sources build and run as the visible desktop peer.
stack:     gradle, launcher
setup:     none
run:
  - .\ОБНОВИТЬ-И-ЗАПУСТИТЬ-ПК.bat
artifact:  one visible TIMA desktop window owned by the fresh launcher process
requires:  no existing visible desktop peer
forbids:   launching packaged TIMA.exe directly; concurrent client Gradle build
frozen:    —
runs_unfrozen: 0

## tier: android-emulator
proves:    AVD tima_test is booted with required reverse mappings and the app is launched.
stack:     android, adb, launcher
setup:     none
run:
  - .\ЗАПУСК-ANDROID.bat
artifact:  adb devices -l, adb reverse --list, and foreground io.tima.app/.MainActivity
requires:  no running intended emulator; Android SDK and AVD tima_test present
forbids:   emulator -wipe-data; adb shell monkey; installing arm64 APK on x86_64 emulator
frozen:    —
runs_unfrozen: 0

## tier: phone-install
proves:    The current debug APK is installed and launched on the intended USB phone.
stack:     android, adb, launcher
setup:     none
run:
  - .\ОБНОВИТЬ-ТЕЛЕФОН.bat
artifact:  adb device-specific install result and io.tima.app/.MainActivity launch
requires:  one intended USB phone identified by adb devices -l
forbids:   uninstalling an existing app without approval; adb shell monkey
frozen:    —
runs_unfrozen: 0
