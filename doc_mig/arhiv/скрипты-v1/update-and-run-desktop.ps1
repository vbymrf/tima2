# Пересобрать и запустить десктопный клиент TIMA из текущих исходников.
#
# Зачем отдельно от ПРИЛОЖЕНИЕ-ПК.bat: тот запускает уже собранный TIMA.exe и о
# свежих правках не знает. Этот собирает то, что лежит в репозитории прямо сейчас,
# — то есть проверяется именно та версия, которую я только что выкатил.
#
# Сервер по умолчанию — боевой (api.xn--80aa4ar0b.xn--p1ai), он зашит в клиенте и
# меняется в поле «Сервер» на экране входа.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$client = Join-Path $root "client"

Write-Host ""
Write-Host "=== TIMA: сборка и запуск десктопного клиента ===" -ForegroundColor Cyan
Write-Host ""

# Версия из build.gradle.kts — чтобы было видно, что именно запускается.
$gradleFile = Join-Path $client "composeApp\build.gradle.kts"
if (Test-Path $gradleFile) {
    $ver = (Select-String -Path $gradleFile -Pattern 'versionName\s*=\s*"([^"]+)"').Matches.Groups[1].Value
    $code = (Select-String -Path $gradleFile -Pattern 'versionCode\s*=\s*(\d+)').Matches.Groups[1].Value
    Write-Host "Версия в исходниках: $ver (сборка $code)" -ForegroundColor Yellow
}

# Свежесть кода: если есть незакоммиченное — предупреждаем, но не мешаем.
Push-Location $root
try {
    $dirty = & git status --short 2>$null
    if ($dirty) { Write-Host "ВНИМАНИЕ: в рабочем дереве есть незакоммиченные правки." -ForegroundColor Yellow }
    $head = & git log --oneline -1 2>$null
    if ($head) { Write-Host "Коммит: $head" }
} catch { }
Pop-Location

Write-Host ""
Write-Host "Собираю и запускаю (первый раз — несколько минут)..." -ForegroundColor Cyan
Write-Host ""

Push-Location $client
try {
    # :composeApp:run собирает и сразу запускает — отдельная упаковка в exe не нужна.
    & .\gradlew.bat ":composeApp:run"
    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "Сборка не прошла. Полный вывод выше." -ForegroundColor Red
    }
} finally {
    Pop-Location
}
