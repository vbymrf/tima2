[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$BuildOnly,
    [switch]$ValidateOnly
)

# Builds a debug APK from current sources, copies it to TIMA.apk, and delegates
# installation to run-app.ps1 unless BuildOnly is specified.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$client = Join-Path $root "client"
$gradle = Join-Path $client "gradlew.bat"
$builtApk = Join-Path $client "composeApp\build\outputs\apk\debug\composeApp-debug.apk"
$apk = Join-Path $root "TIMA.apk"

Write-Host ""
Write-Host "=== TIMA: update APK ===" -ForegroundColor Cyan

if (-not (Test-Path -LiteralPath $gradle)) {
    throw "Gradle wrapper was not found: $gradle."
}
if ($ValidateOnly) {
    Write-Host "Validation passed: Gradle wrapper was found." -ForegroundColor Green
    return
}

Push-Location $client
try {
    Write-Host "Building debug APK..." -ForegroundColor Cyan
    & $gradle ":composeApp:assembleDebug"
    if ($LASTEXITCODE -ne 0) {
        throw "APK build failed (code $LASTEXITCODE)."
    }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $builtApk)) {
    throw "Gradle completed without an APK: $builtApk."
}
Copy-Item -LiteralPath $builtApk -Destination $apk -Force
Write-Host "APK updated: $apk" -ForegroundColor Green

if (-not $BuildOnly) {
    & (Join-Path $root "run-app.ps1") -Serial $Serial
}
