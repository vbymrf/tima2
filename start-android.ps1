# Запуск TIMA на эмуляторе Android (второй собеседник для проверки переписки).
# Сначала запусти запуск-tima.ps1 (сервер должен работать).
# Использование: powershell -ExecutionPolicy Bypass -File C:\!tima2\запуск-android.ps1
$ErrorActionPreference = "Continue"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$apk = "C:\!tima2\client\composeApp\build\outputs\apk\debug\composeApp-debug.apk"

# 1. Эмулятор (если ещё не запущен)
$devices = (& $adb devices) -match "emulator-.*device$"
if (-not $devices) {
    Write-Host "Запускаю эмулятор tima_test (окно появится через ~минуту)..."
    Start-Process "$sdk\emulator\emulator.exe" -ArgumentList "-avd", "tima_test", "-gpu", "auto"
    & $adb wait-for-device
    Write-Host "Жду загрузку Android..."
    do { Start-Sleep 5; $boot = (& $adb shell getprop sys.boot_completed 2>$null) } until ("$boot".Trim() -eq "1")
}

# 2. Установка приложения (обновляет, если уже стоит)
if (Test-Path $apk) {
    Write-Host "Ставлю TIMA на эмулятор..."
    & $adb install -r $apk
} else {
    Write-Host "APK не собран - собери: cd C:\!tima2\client; & `"$env:USERPROFILE\gradle-8.14.3\bin\gradle`" :composeApp:assembleDebug"
}

# 3. Запуск
& $adb shell am start -n io.tima.app/.MainActivity
Write-Host "Готово: TIMA открыт на эмуляторе."
