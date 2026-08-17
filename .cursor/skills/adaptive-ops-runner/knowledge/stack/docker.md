# Docker rules

## Docker Desktop not ready
- code: IMPORTED-OPS-2
- kind: curative
- tier: *
- symptom: Docker CLI exists but engine calls fail or hang.
- check: inspect Docker Desktop process, docker version/info, WSL state, virtualization, disk, and memory.
- cause: Desktop is stopped, WSL2 is stale, virtualization is unavailable, or the VM is resource-starved.
- fix: start Desktop; request approval for Docker or WSL restart/repair.
- verify: client and server versions, Compose v2, and a Compose config command succeed.

## Required host tool unavailable
- code: IMPORTED-OPS-5
- kind: curative
- tier: *
- symptom: Docker or another required host tool is missing or absent from PATH.
- check: use Get-Command and the tool's version flag.
- cause: the package is absent, incompatible, or the current shell PATH is stale.
- fix: request approval to install one persistent component or refresh the process PATH; do not use TEMP toolchains.
- verify: the persistent executable reports the required version.
