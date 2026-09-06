#Requires -Version 5.1
<#
.SYNOPSIS
    Собирает установщик TIMA для ПК (MSI) из текущих исходников.

.DESCRIPTION
    Запускалка по правилу `.cursor/rules/runtime-launchers.mdc`: собирается из
    репозитория, а не из готового артефакта.

    Что делает jpackage под капотом: берёт собранное приложение со встроенной JRE и
    отдаёт его WiX Toolset, который и порождает MSI. Своего упаковщика MSI у JDK нет,
    поэтому без WiX задача падает с «Can not find WiX tools (light.exe, candle.exe)».

    **WiX ищется, а не зашивается.** На каждой машине он лежит по-своему; путь в
    репозитории был бы неправдой на соседней. Порядок поиска: PATH, затем WIX_HOME,
    затем WIX (переменную с таким именем ставит установщик WiX).

.PARAMETER Проверить
    Только проверить готовность машины (JDK, WiX) и выйти, ничего не собирая.
#>
[CmdletBinding()]
param(
    [switch]$Проверить
)

$ErrorActionPreference = 'Stop'

$корень = Split-Path -Parent $MyInvocation.MyCommand.Path
$клиент = Join-Path $корень 'client'

function Скажи($текст) { Write-Host $текст }
function Беда($текст) { Write-Host $текст -ForegroundColor Red }

# ── WiX ─────────────────────────────────────────────────────────────────────
#
# Возвращает каталог с candle.exe и light.exe либо $null. Пустой ответ — не повод
# падать молча: ниже он объясняется словами, потому что «сборка не прошла» без
# причины отправляет человека читать чужие журналы.
function НайтиWiX {
    $вPath = Get-Command candle.exe -ErrorAction SilentlyContinue
    if ($вPath) { return (Split-Path -Parent $вPath.Source) }
    foreach ($имя in @('WIX_HOME', 'WIX')) {
        $значение = [Environment]::GetEnvironmentVariable($имя)
        if (-not $значение) { continue }
        foreach ($подкаталог in @('', 'bin')) {
            $путь = if ($подкаталог) { Join-Path $значение $подкаталог } else { $значение }
            if (Test-Path (Join-Path $путь 'candle.exe')) { return $путь }
        }
    }
    return $null
}

Скажи ''
Скажи '=== TIMA: сборка установщика для ПК ==='
Скажи ''

$jpackage = $null
if ($env:JAVA_HOME) {
    $кандидат = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
    if (Test-Path $кандидат) { $jpackage = $кандидат }
}
if (-not $jpackage) {
    $вPath = Get-Command jpackage.exe -ErrorAction SilentlyContinue
    if ($вPath) { $jpackage = $вPath.Source }
}
if (-not $jpackage) {
    Беда 'Не найден jpackage. Он входит в JDK 17+; задай JAVA_HOME или добавь bin JDK в PATH.'
    exit 1
}
Скажи "jpackage: $jpackage"

$wix = НайтиWiX
if (-not $wix) {
    Беда 'Не найден WiX Toolset (candle.exe, light.exe) — без него jpackage не соберёт MSI.'
    Скажи ''
    Скажи 'Нужна ТРЕТЬЯ версия: jpackage из JDK 17 знает имена candle/light, а в WiX 4 и 5'
    Скажи 'их нет вовсе. Ставить установщиком не обязательно — хватает распакованных'
    Скажи 'бинарников:'
    Скажи ''
    Скажи '  1. wix314-binaries.zip со страницы выпусков wixtoolset/wix3 на github.com'
    Скажи '  2. распаковать в любой каталог'
    Скажи '  3. $env:WIX_HOME = "<этот каталог>"  (или добавить его в PATH)'
    Скажи ''
    Скажи 'Путь к нему в репозитории не хранится: у каждой машины он свой.'
    exit 1
}
Скажи "WiX: $wix"

if ($Проверить) {
    Скажи ''
    Скажи 'Машина готова: jpackage и WiX на месте. Ничего не собиралось — был задан -Проверить.'
    exit 0
}

# candle.exe и light.exe jpackage ищет в PATH, поэтому каталог добавляется в PATH
# ЭТОГО процесса. Машину это не меняет: у соседнего окна PowerShell PATH прежний.
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
    $env:PATH = "$wix;$env:PATH"
}

Push-Location $клиент
try {
    Скажи ''
    Скажи 'Собираю MSI (это долго: внутрь пакета кладётся своя JRE)...'
    & .\gradlew.bat ':app-desktop:packageMsi'
    if ($LASTEXITCODE -ne 0) { throw "сборка не прошла (код $LASTEXITCODE)" }
} finally {
    Pop-Location
}

$msi = Get-ChildItem -Path (Join-Path $клиент 'app-desktop\build\compose\binaries\main\msi') -Filter '*.msi' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $msi) {
    Беда 'Gradle отработал, а MSI не появился. Смотри вывод выше.'
    exit 1
}

$хэш = (Get-FileHash -Path $msi.FullName -Algorithm SHA256).Hash.ToLower()

Скажи ''
Скажи 'Готово.'
Скажи "  файл:   $($msi.FullName)"
Скажи "  размер: $([math]::Round($msi.Length / 1MB, 1)) МБ ($($msi.Length) байт)"
Скажи "  sha256: $хэш"
Скажи ''
Скажи 'Хэш и размер объявляет сервер (APP_MSI_SHA256, APP_MSI_SIZE): подписи кода у'
Скажи 'пакета нет, и это единственное, чем клиент проверит скачанное.'
