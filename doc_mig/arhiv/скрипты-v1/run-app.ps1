[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$ValidateOnly
)

# Installs a ready TIMA.apk on one USB phone and starts the app.
# Build work intentionally belongs to update-app.ps1.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$apk = Join-Path $root "TIMA.apk"
$adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "=== TIMA: launch ready APK ===" -ForegroundColor Cyan

if (-not $adbCommand) {
    throw "adb.exe was not found. Install Android SDK platform-tools and add it to PATH."
}
if (-not (Test-Path -LiteralPath $apk)) {
    throw "$apk was not found. Run OBNOVIT-PRILOZHENIE.bat first."
}
if ($ValidateOnly) {
    Write-Host "Validation passed: adb and TIMA.apk were found." -ForegroundColor Green
    return
}

$rawDevices = & $adbCommand.Source devices
if ($LASTEXITCODE -ne 0) {
    throw "adb devices exited with code $LASTEXITCODE."
}
$phones = $rawDevices |
    Select-String -Pattern '^\S+\s+device$' |
    ForEach-Object { ($_ -split '\s+')[0] } |
    Where-Object { $_ -notlike 'emulator-*' }

if ($Serial) {
    if ($phones -notcontains $Serial) {
        throw "Phone '$Serial' was not found or is not ready. Check adb devices."
    }
    $target = $Serial
} elseif ($phones.Count -eq 1) {
    $target = $phones[0]
} elseif ($phones.Count -eq 0) {
    throw "No USB phone found. Enable USB debugging and authorize this computer."
} else {
    throw "Several phones are connected. Run run-app.ps1 -Serial <serial>."
}

Write-Host "Installing APK on $target..." -ForegroundColor Cyan
& $adbCommand.Source -s $target install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw "APK installation failed (code $LASTEXITCODE)."
}

Write-Host "Opening TIMA..." -ForegroundColor Cyan
& $adbCommand.Source -s $target shell am start -n io.tima.app/.MainActivity | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Failed to open TIMA (code $LASTEXITCODE)."
}
Write-Host "Done: TIMA is running on $target." -ForegroundColor Green
