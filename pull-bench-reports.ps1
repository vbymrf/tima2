# Забрать отчёты испытательного стенда со всех телефонов.
#
# Пускать через pull-bench-reports.bat. Правило `runtime-launchers.mdc`: в обход
# запускалки не ходить — набор шагов разойдётся, и «забрал» перестанет значить одно
# и то же.
#
# ── ПОЧЕМУ ТРИ ШАГА, А НЕ ОДИН `adb pull` ────────────────────────────────────
#
# Отчёт лежит во ВНУТРЕННЕЙ памяти приложения (`/data/data/<пакет>/files/test`), и
# снаружи она не видна никому. Отладочная сборка позволяет прочитать её через
# `run-as`, но `adb pull` через него не ходит. Поэтому: вынуть файл на общую память,
# забрать обычным `pull`, убрать за собой.
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
    [string]$Into = (Join-Path $PSScriptRoot 'doc_add/тесты-звонков')
)

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
        $stage = "/sdcard/Download/$name"
        & adb @a shell "run-as $package cat $folder/$name > $stage" | Out-Null
        & adb @a pull $stage (Join-Path $where $name) | Out-Null
        & adb @a shell "rm -f $stage" | Out-Null
        if (Test-Path (Join-Path $where $name)) {
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
