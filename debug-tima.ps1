# Диагностика dev-окружения TIMA: что запущено, сколько ест памяти, нет ли дублей.
# Запуск (только отчёт):        powershell -ExecutionPolicy Bypass -File C:\!tima2\debug-tima.ps1
# Очистка мусора после повторных запусков (gradle-демоны, осиротевшие go run):
#                               powershell -ExecutionPolicy Bypass -File C:\!tima2\debug-tima.ps1 -Clean
# Освободить RAM у Docker/WSL (Vmmem) — ОСТАНОВИТ контейнеры (postgres/redis/minio):
#                               powershell -ExecutionPolicy Bypass -File C:\!tima2\debug-tima.ps1 -FreeRam
param([switch]$Clean, [switch]$FreeRam)
$ErrorActionPreference = "Continue"

function MB($bytes) { "{0,7:N0} МБ" -f ($bytes / 1MB) }
function Line() { Write-Host ("-" * 64) -ForegroundColor DarkGray }

# Порты стека и их назначение
$ports = [ordered]@{
    8080 = "сервер TIMA (API/WS)"
    6060 = "сервис отладки (pprof)"
    8090 = "escrow-анклав"
    5432 = "PostgreSQL (Docker)"
    6379 = "Redis (Docker)"
    9000 = "MinIO (Docker)"
    7880 = "LiveKit (звонки)"
}

Write-Host ""
Write-Host "  ДИАГНОСТИКА TIMA  " -ForegroundColor Cyan -NoNewline
Write-Host (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
Line

# ── 1. Порты: кто слушает, чем занят, дубли ──
Write-Host "ПОРТЫ СТЕКА" -ForegroundColor Yellow
foreach ($p in $ports.Keys) {
    $conns = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    if (-not $conns) {
        Write-Host ("  :{0,-5} {1,-26} — не слушает" -f $p, $ports[$p]) -ForegroundColor DarkGray
        continue
    }
    $pids = $conns.OwningProcess | Sort-Object -Unique
    foreach ($procId in $pids) {
        $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
        $mem = if ($proc) { MB $proc.WorkingSet64 } else { "?" }
        $name = if ($proc) { $proc.ProcessName } else { "pid $procId" }
        Write-Host ("  :{0,-5} {1,-26} <- {2} ({3})" -f $p, $ports[$p], $name, $mem) -ForegroundColor Green
    }
    if ($pids.Count -gt 1) {
        Write-Host ("      ! на порту {0} несколько слушателей — возможен дубль запуска" -f $p) -ForegroundColor Red
    }
}
Line

# ── 2. Процессы TIMA на хосте ──
Write-Host "ПРОЦЕССЫ (хост)" -ForegroundColor Yellow
$goCount = 0
$patterns = @(
    @{ Name = "go.exe";              Label = "go (компилятор/раннер)" }
    @{ Name = "gradle";              Label = "gradle" }
    @{ Name = "java";                Label = "java/gradle-демон" }
    @{ Name = "TIMA";                Label = "приложение TIMA (desktop)" }
    @{ Name = "qemu-system-x86_64";  Label = "эмулятор Android" }
    @{ Name = "adb";                 Label = "adb" }
)
foreach ($pat in $patterns) {
    $procs = Get-Process -Name $pat.Name -ErrorAction SilentlyContinue
    foreach ($proc in $procs) {
        Write-Host ("  {0,-28} pid {1,-6} {2}" -f $pat.Label, $proc.Id, (MB $proc.WorkingSet64))
        if ($pat.Name -eq "go.exe") { $goCount++ }
    }
}
# Скомпилированные go run временные бинарники (tima serve / escrow-stub) — по пути в TEMP
$goTemp = Get-Process -ErrorAction SilentlyContinue | Where-Object {
    $_.Path -and ($_.Path -like "*\go-build*" -or $_.Path -like "*\Temp\*tima*.exe")
}
foreach ($proc in $goTemp) {
    Write-Host ("  {0,-28} pid {1,-6} {2}" -f "бинарник go run", $proc.Id, (MB $proc.WorkingSet64)) -ForegroundColor Green
}
if ($goCount -gt 2) {
    Write-Host ("  ! запущено go.exe: {0} — после нескольких перезапусков копятся. -Clean уберёт." -f $goCount) -ForegroundColor Red
}
Line

# ── 3. Docker / WSL (главный пожиратель RAM на Windows) ──
Write-Host "DOCKER / WSL" -ForegroundColor Yellow
$vmmem = Get-Process -Name "vmmem", "vmmemWSL" -ErrorAction SilentlyContinue
foreach ($proc in $vmmem) {
    Write-Host ("  {0,-28} pid {1,-6} {2}   <- ВМ Docker/WSL" -f $proc.ProcessName, $proc.Id, (MB $proc.WorkingSet64)) -ForegroundColor Magenta
}
docker info *> $null
if ($LASTEXITCODE -eq 0) {
    Write-Host "  Контейнеры (docker stats):"
    docker stats --no-stream --format "    {{.Name}}: {{.MemUsage}} (CPU {{.CPUPerc}})" 2>$null
} else {
    Write-Host "  Docker не запущен" -ForegroundColor DarkGray
}
Line

# ── 4. Сводка от сервиса отладки сервера (если поднят) ──
Write-Host "RUNTIME СЕРВЕРА (/debug/stats)" -ForegroundColor Yellow
try {
    $stats = Invoke-RestMethod -Uri "http://127.0.0.1:6060/debug/stats" -TimeoutSec 2
    Write-Host ("  goroutines: {0}   heap: {1:N1} МБ   объектов: {2}   GC: {3}   sys: {4:N1} МБ" -f `
        $stats.goroutines, $stats.heap_alloc_mb, $stats.heap_objects, $stats.num_gc, $stats.sys_mb) -ForegroundColor Green
    Write-Host "  (запусти скрипт дважды с паузой: растут goroutines/heap при простое = утечка)" -ForegroundColor DarkGray
} catch {
    Write-Host "  сервис отладки недоступен (сервер не запущен или без TIMA_DEBUG_ADDR)" -ForegroundColor DarkGray
}
Line

# ── 5. Очистка ──
if ($Clean) {
    Write-Host "ОЧИСТКА" -ForegroundColor Yellow
    Write-Host "  Останавливаю gradle-демоны..."
    & "$env:USERPROFILE\gradle-8.14.3\bin\gradle.bat" --stop 2>$null
    Write-Host "  Убираю осиротевшие go.exe (раннеры/компиляторы)..."
    Get-Process -Name "go" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Write-Host "  Готово. Сервер и escrow (окна PowerShell) не тронуты — закрой их вручную при перезапуске." -ForegroundColor Green
    Line
}
if ($FreeRam) {
    Write-Host "ОСВОБОЖДЕНИЕ RAM (Docker/WSL)" -ForegroundColor Yellow
    Write-Host "  wsl --shutdown освободит Vmmem, но ОСТАНОВИТ контейнеры TIMA." -ForegroundColor Red
    Write-Host "  После этого подними стек заново: ЗАПУСК-TIMA.bat"
    wsl --shutdown
    Write-Host "  Готово." -ForegroundColor Green
    Line
}

if (-not $Clean -and -not $FreeRam) {
    Write-Host "Подсказки:" -ForegroundColor Cyan
    Write-Host "  -Clean    убрать gradle-демоны и осиротевшие go run (мусор от повторных запусков)"
    Write-Host "  -FreeRam  освободить память Docker/WSL (Vmmem) — остановит контейнеры"
}
Write-Host ""
