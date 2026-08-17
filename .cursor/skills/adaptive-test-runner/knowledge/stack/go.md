# Go rules

## Go command unavailable
- code: IMPORTED-TEST-4
- kind: curative
- tier: *
- symptom: a Go command raises CommandNotFoundException.
- check: run Get-Command go and go version.
- cause: Go is missing or absent from the current process PATH.
- fix: hand installation or a PATH refresh to the operations skill; do not substitute a temporary toolchain.
- verify: go version reports the required version and the narrow package command starts.
