# Забрать отчёты испытательного стенда со всех телефонов.
#
# Пускать через pull-bench-reports.bat. Правило `runtime-launchers.mdc`: в обход
# запускалки не ходить — набор шагов разойдётся, и «забрал» перестанет значить одно
# и то же.
#
# ── ПОЧЕМУ НЕ `adb pull` ─────────────────────────────────────────────────────
#
# Отчёт лежит во ВНУТРЕННЕЙ памяти приложения (`/data/data/<пакет>/files/test`), и
# снаружи она не видна никому. Отладочная сборка позволяет прочитать её через
# `run-as`, но `adb pull` через него не ходит. Поэтому `exec-out run-as … cat` прямо в
# файл на этой машине. Перевалка через общую память (/sdcard) отдавала пустые файлы и
# убрана 2026-09-26.
#
# С телефона файлы НЕ стираются. Стирает человек, когда убедился, что забрал: отчёт
# прогона стоит минуты разговора, и потерять его из-за чужой ошибки нельзя.

[CmdletBinding()]
param(
    # Адрес чужого сервера adb, `host:port`. Пусто — сервер на этой машине.
    [string]$AdbServer = $env:TIMA_ADB_SERVER,

    # Забрать только с этих устройств. Пусто — со всех, что ответили.
    [string[]]$Serial,

    # Куда складывать. По умолчанию — вне git, см. doc_mig/ТЕСТЫ-НА-ТЕЛЕФОНАХ/README.md.
    [string]$Into
)

# Путь по умолчанию — здесь, а не в param(): Windows PowerShell 5.1, запущенный из pwsh
# через .bat, отдавал в значении по умолчанию пустой $PSScriptRoot, и скрипт падал на
# Join-Path, не дойдя до телефонов (2026-09-26).
if (-not $Into) {
    $Into = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'doc_add/тесты-звонков'
}

# Не 'Stop' по той же причине, что в update-and-install-phone.ps1: строка adb в потоке
# ошибок рвала бы скрипт на первом же телефоне, не дойдя до остальных.
$ErrorActionPreference = 'Continue'
Set-StrictMode -Version Latest

$package = 'io.tima.app.v2'
$folder = 'files/test'

if ($Serial) { $Serial = @($Serial | ForEach-Object { $_ -split ',' } | Where-Object { $_ }) }

function adbArgs([string]$serial) {
    $args = @()
    if ($AdbServer) { $args += @('-L', "tcp:$AdbServer") }
    if ($serial) { $args += @('-s', $serial) }
    return $args
}

$devices = & adb @(adbArgs $null) devices |
    Select-Object -Skip 1 |
    Where-Object { $_ -match '\sdevice$' } |
    ForEach-Object { ($_ -split '\s+')[0] }

if (-not $devices) {
    Write-Host 'Ни один телефон не ответил. Связь проверяется, а не чинится — см. doc_mig/ЖУРНАЛ-И-ОТЛАДКА/ПОДКЛЮЧЕНИЕ.md'
    exit 1
}
if ($Serial) { $devices = $devices | Where-Object { $Serial -contains $_ } }

$total = 0
foreach ($device in $devices) {
    $a = adbArgs $device
    $model = (& adb @a shell getprop ro.product.model) -replace '\s', ''
    if (-not $model) { $model = $device }
    Write-Host ''
    Write-Host "→ $model ($device)"

    $names = & adb @a shell "run-as $package ls $folder 2>/dev/null" |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -like '*.md' }

    if (-not $names) {
        Write-Host '   отчётов нет'
        continue
    }

    $where = Join-Path $Into $model
    if (-not (Test-Path $where)) { New-Item -ItemType Directory -Force $where | Out-Null }

    foreach ($name in $names) {
        $target = Join-Path $where $name
        # exec-out прямо в файл, через cmd: его перенаправление байты не трогает, а
        # PowerShell 5.1 перекодировал бы вывод в UTF-16. Прежняя перевалка через
        # /sdcard давала ПУСТЫЕ файлы (2026-09-26, Android 11): `cat` под run-as пишет от
        # имени приложения в файл, открытый оболочкой, и получает отказ молча.
        cmd /c "adb $($a -join ' ') exec-out run-as $package cat $folder/$name > `"$target`"" | Out-Null
        if ((Test-Path $target) -and (Get-Item $target).Length -gt 0) {
            Write-Host "   $name"
            $total++
        } else {
            Write-Host "   $name — не забрался"
        }
    }
}

Write-Host ''
Write-Host "Готово: файлов $total → $Into"
Write-Host 'С телефонов ничего не стёрто — это делает человек.'

# Явный ноль: без него код возврата достаётся от последнего вызова adb, а у него
# «папки нет» — обычный ответ, не беда. Запускалка иначе печатала бы отказ на удачном
# прогоне, и ей перестали бы верить.
exit 0
