# Собрать APK из текущих исходников и поставить на подключённый по USB телефон.
#
# Когда это нужно: когда хочется проверить правку, которая ещё не выложена на сервер.
# Если версия уже выложена — проще нажать «Обновить приложение» в самом приложении,
# ставить руками незачем.
#
# Требуется: телефон в режиме отладки по USB и разрешение на этом компьютере.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$client = Join-Path $root "client"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$apk = Join-Path $client "composeApp\build\outputs\apk\debug\composeApp-debug.apk"

Write-Host ""
Write-Host "=== TIMA: сборка APK и установка на телефон ===" -ForegroundColor Cyan
Write-Host ""

if (-not (Test-Path $adb)) {
    Write-Host "Не найден adb: $adb" -ForegroundColor Red
    Write-Host "Нужен Android SDK platform-tools."
    exit 1
}

$devices = (& $adb devices) | Select-String -Pattern "\sdevice$"
if (-not $devices) {
    Write-Host "Телефон не виден." -ForegroundColor Red
    Write-Host "Проверь: кабель, режим отладки по USB, и что на телефоне подтверждён"
    Write-Host "запрос «Разрешить отладку с этого компьютера»."
    exit 1
}
Write-Host "Устройство на связи." -ForegroundColor Green

Push-Location $client
try {
    Write-Host "Собираю APK..." -ForegroundColor Cyan
    & .\gradlew.bat ":composeApp:assembleDebug"
    if ($LASTEXITCODE -ne 0) { throw "сборка не прошла" }
} finally {
    Pop-Location
}

if (-not (Test-Path $apk)) { Write-Host "APK не появился: $apk" -ForegroundColor Red; exit 1 }

Write-Host "Ставлю на телефон (обновляю, если уже установлен)..." -ForegroundColor Cyan
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Установка не прошла. Частая причина — подпись отличается от установленной" -ForegroundColor Yellow
    Write-Host "версии. Тогда удали приложение с телефона и запусти снова."
    exit 1
}

& $adb shell am start -n io.tima.app/.MainActivity | Out-Null
Write-Host ""
Write-Host "Готово: TIMA запущен на телефоне." -ForegroundColor Green
