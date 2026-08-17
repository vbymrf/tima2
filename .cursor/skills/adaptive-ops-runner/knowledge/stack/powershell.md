# PowerShell rules

## ToHexString unavailable
- code: IMPORTED-OPS-17
- kind: curative
- tier: *
- symptom: `[Convert]::ToHexString` raises MethodNotFound.
- check: inspect the PowerShell and .NET runtime version.
- cause: Windows PowerShell 5.1 lacks that API.
- fix: use `[BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()`.
- verify: output is lowercase hex of expected length without logging secrets.

## HttpClientHandler type unavailable
- code: IMPORTED-OPS-18
- kind: curative
- tier: *
- symptom: a local PowerShell HTTP helper raises TypeNotFound for System.Net.Http.HttpClientHandler.
- check: inspect whether System.Net.Http is loaded.
- cause: Windows PowerShell did not auto-load the assembly.
- fix: add `Add-Type -AssemblyName System.Net.Http` before using the type.
- verify: the helper forwards a harmless health request.

## Elevated helper has no useful diagnostics
- code: IMPORTED-OPS-19
- kind: curative
- tier: *
- symptom: an elevated helper returns an opaque negative code.
- check: confirm UAC acceptance and capture secret-free stdout/stderr locally.
- cause: UAC was cancelled or an error is hidden across the elevated process boundary.
- fix: request explicit approval, run a minimal wrapper, and inspect diagnostics; use manual action rather than blind retries.
- verify: the helper starts cleanly or manual completion is independently observed.
