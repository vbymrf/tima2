# Положить наборы прогонов на все телефоны.
#
# Пускать через push-bench-presets.bat. Правило `runtime-launchers.mdc`: в обход
# запускалки не ходить — набор шагов разойдётся, и «положено» перестанет значить одно
# и то же.
#
# ── ЧТО ЗНАЧИТ «ПЕРЕДАТЬ НА ТЕЛЕФОН» ─────────────────────────────────────────
#
# Буквально — заменить файл. Список наборов живёт в `files/test/presets.json` на самом
# телефоне и оттуда же читается приложением при открытии окна стенда. Второго хранилища
# нет, сверять нечего.
#
# ── ПОЧЕМУ ЧЕРЕЗ ОБЩУЮ ПАМЯТЬ ────────────────────────────────────────────────
#
# `adb push` во внутреннюю память приложения не ходит, а `run-as` не читает `/sdcard`.
# Поэтому: положить файл в общую память (это умеет push), а внутрь переложить `cat`-ом
# от имени оболочки в `run-as` по трубе — она доступна обоим. И убрать за собой.
#
# Скрипт НЕ сверяет, что лежало раньше: замена и есть его работа. Набор, подправленный
# пальцем на телефоне, будет затёрт, и это сказано в README рядом с файлом.

[CmdletBinding()]
param(
    # Адрес чужого сервера adb, `host:port`. Пусто — сервер на этой машине.
    [string]$AdbServer = $env:TIMA_ADB_SERVER,

    # Положить только на эти устройства. Пусто — на все, что ответили.
    [string[]]$Serial,

    # Какой файл класть.
    [string]$File = (Join-Path $PSScriptRoot 'doc_add/наборы-прогонов/bench-presets.json')
)

# Не 'Stop' по той же причине, что в остальных запускалках: строка adb в потоке ошибок
# рвала бы скрипт на первом же телефоне, не дойдя до остальных.
$ErrorActionPreference = 'Continue'
Set-StrictMode -Version Latest

$package = 'io.tima.app.v2'
$inside = 'files/test/presets.json'
$stage = '/sdcard/Download/presets.json'

if (-not (Test-Path $File)) {
    Write-Host "Нет файла: $File"
    exit 1
}
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

$count = (Get-Content $File -Raw | Select-String -Pattern '"name"' -AllMatches).Matches.Count
# Проверяем по размеру, а не по числу наборов: считать их НА ТЕЛЕФОНЕ пришлось бы
# регулярным выражением через три слоя кавычек — PowerShell, adb, оболочка телефона, — и
# первый же из них его съедал.
$size = (Get-Item $File).Length
Write-Host "Наборов в файле: $count ($size байт)"

$done = 0
foreach ($device in $devices) {
    $a = adbArgs $device
    $model = (& adb @a shell getprop ro.product.model) -replace '\s', ''
    if (-not $model) { $model = $device }

    & adb @a push $File $stage | Out-Null
    & adb @a shell "run-as $package mkdir -p files/test" | Out-Null
    & adb @a shell "cat $stage | run-as $package tee $inside > /dev/null" | Out-Null
    & adb @a shell "rm -f $stage" | Out-Null

    # Без перенаправления `<`: его исполнил бы ВНЕШНИЙ shell телефона, которому путь
    # внутрь приложения недоступен, — и проверка падала на «can't open», хотя файл лёг.
    $there = ((& adb @a shell "run-as $package wc -c $inside 2>/dev/null") -split '\s+' |
        Where-Object { $_ } | Select-Object -First 1)
    if ($there -eq "$size") {
        Write-Host "→ $model — положено"
        $done++
    } else {
        Write-Host "→ $model — НЕ положено (байт там: $there, ждали $size)"
    }
}

Write-Host ''
Write-Host "Готово: телефонов $done из $($devices.Count)"
Write-Host 'На телефоне список обновится при следующем открытии окна стенда.'

# Явный ноль: без него код возврата достаётся от последнего вызова adb, и запускалка
# печатала бы отказ на удачном прогоне.
exit 0
