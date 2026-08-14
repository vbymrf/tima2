# TIMA Windows operations reference

Run from the repository root. Tool locations and what is installed differ per
machine — they live in the untracked local environment note, not here. Inspect before
changing state.

There is no `infra/` directory and no installer script in this repository.
Compose files live in `server/deploy/`, and the runtime launchers are PowerShell
scripts in the repository root with `.bat` wrappers next to them. A `scripts/`
directory does not exist yet either, but it is where a worker puts a reusable
operation script it creates — never in the repository root.

## Preflight

```powershell
docker compose -f server/deploy/docker-compose.dev.yml ps
adb devices -l    # путь к SDK — из ANDROID_HOME, не зашивать
Get-Process TIMA,emulator,qemu-system-x86_64,java,go -ErrorAction SilentlyContinue
Get-PSDrive C
go version
java -version
gradle -version
git status --short --branch
```

The repository's own diagnostic is usually faster and more complete — it reports
listeners on every stack port, duplicate launches, host processes, Docker/WSL
memory, and the server's `/debug/stats`:

```powershell
.\ОТЛАДКА-TIMA.bat          # or: powershell -File .\debug-tima.ps1
```

Its `-Clean` switch stops Gradle daemons and orphaned `go` processes, and
`-FreeRam` runs `wsl --shutdown`, which **stops all containers**. Both mutate
state and need approval.

Do not rely on a JDK or Gradle under `%TEMP%`. The active AVD belongs under
`%USERPROFILE%\.android\avd`.

## Host tooling

Expected versions: Go 1.25.x, Microsoft OpenJDK 17, Gradle 8.14.3 (the client
and messenger-crypto also ship `gradlew`), Android SDK with build-tools 35.0.0
and platform 35, AVD `tima_test`, Docker Desktop with the WSL2 backend.

Verify with `Get-Command` and the version flags above. There is no
`setup-windows` script here: install one missing component at a time after
approval, verify it before continuing, then refresh the current process PATH.
Record what was installed and where **in the local environment note**, not here:
the launchers hardcode some paths (see "Launcher defect class"), and the next
agent on this machine needs the real locations without re-deriving them.

WSL is not a TIMA command surface, but Docker Desktop needs its WSL2 backend:

```powershell
wsl --status
wsl --list --verbose
docker version
docker compose version
```

`wsl --update`, `wsl --shutdown`, enabling Windows features, and elevated
repairs require approval.

## Development stack

Storage only — the server itself runs on the host, not in Compose.

```powershell
docker compose -f server/deploy/docker-compose.dev.yml config
docker compose -f server/deploy/docker-compose.dev.yml up -d
docker compose -f server/deploy/docker-compose.dev.yml ps
```

Services: PostgreSQL 5432 (`tima`/`tima`/`tima-dev-only`), Redis 6379
(password `tima-dev-only`), MinIO 9000 and console 9001. Two opt-in profiles:

```powershell
docker compose -f server/deploy/docker-compose.dev.yml --profile calls up -d
docker compose -f server/deploy/docker-compose.dev.yml --profile escrow up -d --build
```

`calls` adds LiveKit on 7880/7881/7882; `escrow` builds and runs the stub enclave
on 8090. In the normal dev flow the enclave runs on the host instead, started by
`start-tima.ps1`, and its Shamir shares are printed on the very first run only.

The isolated test database is a separate project on port 55432:

```powershell
docker compose -f server/deploy/docker-compose.test.yml up -d
```

No `--env-file` is used with either file: neither reads one. `server/deploy/.env.example`
belongs to `docker-compose.prod.yml` and is not needed on a development machine.

A plain `stop`/`down` preserves named volumes. `down -v` deletes PostgreSQL,
Redis, and MinIO state and needs explicit destructive approval. Migrations are
embedded in the Go binary and applied at startup, so there is no migrate
container and no separate migrate step.

Health of the running server:

```powershell
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8080/healthz
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8090/v1/pubkey
```

## Runtime launchers

Each `.bat` is a thin wrapper over the `.ps1` of the same purpose; either form
is fine.

- `ЗАПУСК-TIMA.bat` → `start-tima.ps1` — the whole dev stack in order: Docker
  Desktop, `docker-compose.dev.yml`, escrow-stub in its own window, server in
  its own window with all development environment variables kept process-local,
  then the packaged desktop app. Idempotent: each step is skipped when its
  endpoint already answers.
- `ОБНОВИТЬ-И-ЗАПУСТИТЬ-ПК.bat` → `update-and-run-desktop.ps1` — rebuild the
  desktop client from current sources and run it via `:composeApp:run`. Use this
  after changing client code; the packaged exe does not know about new edits.
- `ПРИЛОЖЕНИЕ-ПК.bat` — start the already packaged
  `client\composeApp\build\compose\binaries\main\app\TIMA\TIMA.exe`. It only
  launches; it never builds.
- `ЗАПУСК-ANDROID.bat` → `start-android.ps1` — boot AVD `tima_test`, add the
  `adb reverse` mappings for 8080 and 9000, install the debug APK, start
  `io.tima.app/.MainActivity`.
- `ОБНОВИТЬ-ТЕЛЕФОН.bat` → `update-and-install-phone.ps1` — build
  `:composeApp:assembleDebug` and install it on a USB-connected phone.
- `ОТЛАДКА-TIMA.bat` → `debug-tima.ps1` — diagnostics described above.

Never invoke `TIMA.exe` or `go run ./cmd/tima` directly when a launcher covers
the case, and never reconstruct the server's environment variables by hand: they
are the launcher's contract and belong in one place.

Before starting anything, check for an existing instance. Duplicate listeners on
a stack port are exactly what `debug-tima.ps1` flags in red. Stopping a stale
process needs approval.

## Launcher defect class

`start-tima.ps1`, `debug-tima.ps1 -Clean` and `start-android.ps1` contain
absolute paths to Go, Docker Desktop and Gradle. Whether any given one is
correct depends on the machine, so this file does not claim which are broken —
that is an observation about one setup and belongs in the untracked local
environment note, where it can be kept current.

What is true everywhere: **an absolute path baked into a launcher is the defect
itself.** One machine's layout becomes everyone's failure, and the failure looks
like a missing tool rather than a wrong assumption.

Fix by resolving through `Get-Command` or a documented environment variable, not
by substituting a different absolute path. A failed launcher is a launcher defect
to repair, never a reason to start the server or enclave by hand.

Before repairing, check the local environment note: the tool may be genuinely
absent here, and the work belongs on another machine.

## Android emulator and app

Start the emulator only when `adb devices -l` shows no intended device; the
launcher already does this check.

```powershell
# $sdk — из ANDROID_HOME или локальной заметки об окружении, не зашивать
& "$sdk\emulator\emulator.exe" -avd $avd -gpu auto
& "$sdk\platform-tools\adb.exe" reverse tcp:8080 tcp:8080
& "$sdk\platform-tools\adb.exe" reverse tcp:9000 tcp:9000
```

Port 8080 reverse gives the emulator the host API; 9000 is needed because MinIO
presigned URLs point at the host's `localhost:9000`. With several devices always
pass `-s`.

The build targets `arm64-v8a` only, so a normal x86_64 emulator image cannot
install the APK — real Android verification needs a phone over USB
(`ОБНОВИТЬ-ТЕЛЕФОН.bat`). Killing an unintended emulator or uninstalling an app
requires approval.

## End of an operations block

```powershell
.\ОТЛАДКА-TIMA.bat
git status --short --branch
```

Run tests through `tima-test-runner`. Record which containers, windows, and
emulators remain running — `start-tima.ps1` leaves two PowerShell windows
(server and escrow) that nothing else will close. Clear task-only environment
variables.

## Primary repository authorities

- `server/deploy/docker-compose.dev.yml`, `docker-compose.test.yml`,
  `docker-compose.prod.yml`
- `start-tima.ps1` and the other root launchers
- `server/README.md`
- `ДОКУМЕНТАЦИЯ/01-сервер/README.md`
- `ПЛАН-РЕФАКТОРИНГА.md`
