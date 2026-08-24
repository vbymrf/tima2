#Requires -Version 5.1
<#
.SYNOPSIS
    Пересобирает и запускает приложение TIMA для ПК из текущих исходников.

.DESCRIPTION
    Запускалка по правилу `.cursor/rules/runtime-launchers.mdc`: собирается из
    репозитория, а не из готового exe, и не подменяется ручным вызовом gradle.

    Корень репозитория берётся ОТ СВОЕГО РАСПОЛОЖЕНИЯ, а не из текущего каталога:
    иначе запуск из другого места собирал бы не тот checkout.

    Инструменты ищутся через JAVA_HOME или PATH, без зашитых путей: то, что стоит
    на этой машине, отличается от соседней, и зашитый путь превращает чужую
    раскладку во всеобщую поломку.

.PARAMETER Стоп
    Остановить запущенный экземпляр и выйти.
#>
[CmdletBinding()]
param(
    [switch]$Стоп
)

$ErrorActionPreference = 'Stop'

$корень = Split-Path -Parent $MyInvocation.MyCommand.Path
$клиент = Join-Path $корень 'client'
$замок  = Join-Path $env:TEMP 'tima-desktop.pid'

function Скажи($текст) { Write-Host $текст }
function Беда($текст) { Write-Host $текст -ForegroundColor Red }

# ── Уже запущенное ──────────────────────────────────────────────────────────
#
# Второй экземпляр — не мелочь: у приложения одна локальная база на устройство,
# и два процесса на одном файле дают расхождение, которое потом выглядит как
# «сообщения пропадают». Поэтому дубль отвергается, а не запускается тише.
function ЖивойЭкземпляр {
    if (-not (Test-Path $замок)) { return $null }
    $pidИзФайла = (Get-Content $замок -ErrorAction SilentlyContinue | Select-Object -First 1)
    if (-not $pidИзФайла) { return $null }
    $процесс = Get-Process -Id $pidИзФайла -ErrorAction SilentlyContinue
    if (-not $процесс) {
        # Замок от процесса, которого больше нет: снимаем и идём дальше.
        Remove-Item $замок -Force -ErrorAction SilentlyContinue
        return $null
    }
    return $процесс
}

$живой = ЖивойЭкземпляр

if ($Стоп) {
    if ($живой) {
        Скажи "Останавливаю приложение (pid $($живой.Id))"
        Stop-Process -Id $живой.Id -Force
        Remove-Item $замок -Force -ErrorAction SilentlyContinue
    } else {
        Скажи 'Запущенного приложения нет.'
    }
    exit 0
}

if ($живой) {
    Беда "Приложение уже запущено (pid $($живой.Id))."
    Скажи 'Остановить: ОБНОВИТЬ-И-ЗАПУСТИТЬ-ПК.bat -Стоп'
    exit 1
}

# ── Предпосылки ─────────────────────────────────────────────────────────────
if (-not (Test-Path (Join-Path $клиент 'gradlew.bat'))) {
    Беда "Не найден $клиент\gradlew.bat — запускалка лежит не в корне репозитория."
    exit 1
}

$java = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $найденный = Get-Command java -ErrorAction SilentlyContinue
    if ($найденный) { $java = $найденный.Source }
}
if (-not $java) {
    Беда 'JDK не найден: нет ни JAVA_HOME, ни java в PATH.'
    Скажи 'Нужен JDK 17 — той же версии, что просит сборка (jvmToolchain(17)).'
    exit 1
}

# java -version пишет в ПОТОК ОШИБОК, а при ErrorActionPreference = Stop
# PowerShell 5.1 считает это ошибкой и обрывает скрипт. Поймано первым же
# запуском: проверка версии роняла запускалку раньше, чем она что-то запускала.
$версия = & { $ErrorActionPreference = 'Continue'; (& $java -version 2>&1 | Select-Object -First 1) }
Скажи "JDK: $версия"
Скажи "Корень: $корень"

# ── Сборка и запуск ─────────────────────────────────────────────────────────
#
# Переменные окружения держатся ПРОЦЕССНЫМИ: правка машинных настроек ради
# запуска — это след, который переживёт запуск и однажды объяснит чужую поломку.
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java)
}

Скажи 'Собираю и запускаю приложение для ПК…'
$процесс = Start-Process -FilePath (Join-Path $клиент 'gradlew.bat') `
    -ArgumentList ':app-desktop:run' `
    -WorkingDirectory $клиент `
    -PassThru

Set-Content -Path $замок -Value $процесс.Id -Encoding ascii
Скажи "Запущено, pid $($процесс.Id). Окно появится после сборки — первый запуск дольше."
Скажи 'Остановить: ОБНОВИТЬ-И-ЗАПУСТИТЬ-ПК.bat -Стоп'
