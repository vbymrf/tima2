# WSL rules

## WSL repair requires elevation
- code: IMPORTED-OPS-3
- kind: curative
- tier: toolchain-verify
- symptom: wsl --update or feature repair returns access or elevation errors.
- check: inspect wsl --status, current elevation, and Windows feature state.
- cause: the requested operation requires administrator privileges.
- fix: request approval for the exact elevated update, then restart Docker Desktop.
- verify: WSL is healthy and the Docker engine responds.
