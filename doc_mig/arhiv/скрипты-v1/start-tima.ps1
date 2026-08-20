# Запуск стека TIMA для разработки: Docker-хранилища -> escrow -> сервер -> приложение Windows.
# Использование: powershell -ExecutionPolicy Bypass -File C:\!tima2\запуск-tima.ps1
$ErrorActionPreference = "Continue"
$root = "C:\!tima2"
$go = "$env:USERPROFILE\go-toolchain\bin\go.exe"

function Test-Http($url) {
    try { (Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri $url) | Out-Null; $true } catch { $false }
}

# 1. Docker Desktop
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Запускаю Docker Desktop и жду движок (до пары минут)..."
    Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    do { Start-Sleep 3; docker info *> $null } while ($LASTEXITCODE -ne 0)
}
Write-Host "Docker готов. Поднимаю PostgreSQL/Redis/MinIO..."
docker compose -f "$root\server\deploy\docker-compose.dev.yml" up -d

# 2. Escrow-анклав (отдельное окно)
if (-not (Test-Http "http://127.0.0.1:8090/v1/pubkey")) {
    Write-Host "Запускаю escrow-stub (окно можно свернуть, но не закрывать)..."
    Start-Process powershell -ArgumentList "-NoExit", "-Command",
        "`$host.UI.RawUI.WindowTitle='TIMA escrow-stub'; cd '$root\server'; `$env:ESCROW_STATE_DIR='$env:USERPROFILE\.tima-escrow-dev'; & '$go' run ./cmd/escrow-stub"
}

# 3. Сервер TIMA (отдельное окно)
if (-not (Test-Http "http://127.0.0.1:8080/healthz")) {
    Write-Host "Запускаю сервер TIMA (окно можно свернуть, но не закрывать)..."
    Start-Process powershell -ArgumentList "-NoExit", "-Command", @"
`$host.UI.RawUI.WindowTitle='TIMA server'; cd '$root\server'
`$env:DATABASE_URL='postgres://tima:tima-dev-only@localhost:5432/tima'
`$env:REDIS_URL='redis://:tima-dev-only@localhost:6379'
`$env:S3_ENDPOINT='http://localhost:9000'
`$env:S3_ACCESS_KEY='tima-admin'
`$env:S3_SECRET_KEY='tima-dev-only'
`$env:TIMA_DEV_SMS='1'
`$env:ESCROW_URL='http://127.0.0.1:8090'
`$env:JWT_SIGNING_KEY='tima-dev-only-jwt-signing-key-not-for-prod'
`$env:TIMA_DEBUG_ADDR='127.0.0.1:6060'
`$env:TIMA_RL_SMS_PER_IP='1000'
`$env:TIMA_RL_SMS_PER_PHONE='100'
`$env:LIVEKIT_API_KEY='devkey'
`$env:LIVEKIT_API_SECRET='devsecret_at_least_32_chars_long_000'
`$env:LIVEKIT_URL='ws://localhost:7880'
& '$go' run ./cmd/tima serve
"@
    Write-Host "Жду сервер..."
    do { Start-Sleep 2 } until (Test-Http "http://127.0.0.1:8080/healthz")
}

# 4. Приложение Windows
$exe = "$root\client\composeApp\build\compose\binaries\main\app\TIMA\TIMA.exe"
if (Test-Path $exe) {
    Write-Host "Открываю TIMA..."
    Start-Process $exe
} else {
    Write-Host "TIMA.exe не собран - собери: cd $root\client; & `"$env:USERPROFILE\gradle-8.14.3\bin\gradle`" :composeApp:createDistributable"
}
Write-Host "Готово. Сервер: http://127.0.0.1:8080"
