# Go rules

## Go unavailable to the host
- code: IMPORTED-OPS-6
- kind: curative
- tier: toolchain-verify
- symptom: go raises CommandNotFoundException.
- check: run Get-Command go, go version, and inspect the persistent Go installation.
- cause: Go is absent or missing from the current PATH.
- fix: request approval to install the required Go version or refresh PATH; repair a launcher that uses a stale location.
- verify: go version reports the required release and a narrow package command starts.
