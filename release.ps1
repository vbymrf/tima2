#Requires -Version 5.1
<#
.SYNOPSIS
    Готовит выпуск: поднимает номер сборки, собирает APK и MSI, считает хэши.

.DESCRIPTION
    Выпуск — это пять шагов (ПЛАН-ОБНОВЛЕНИЯ.md, О6). Скрипт делает первые три:

      1. поднимает `tima.versionCode` и `tima.versionName` в client/gradle.properties
      2. собирает APK и MSI
      3. считает sha256 и размеры

    Четвёртый — положить файлы на сервер и переписать `.env` — **не делается здесь и не
    делается само**: это выкатка, а выкатка в этом проекте всегда отдельное решение
    человека. Скрипт печатает готовые строки для `deploy/.env`, чтобы их не набирали
    руками: разошедшиеся файл и хэш — это отказ обновления у всех сразу.

    Пятый — записать выпуск в `doc_mig/ВЕРСИИ/КЛИЕНТЫ.md` — тоже за человеком: там
    словами про то, что именно приехало.

.PARAMETER Номер
    Какой номер сборки поставить. По умолчанию — текущий плюс один.

.PARAMETER Имя
    Имя версии (`tima.versionName`). По умолчанию остаётся прежним.

.PARAMETER ТолькоСобрать
    Не трогать gradle.properties: собрать и посчитать хэши на том, что есть.
#>
[CmdletBinding()]
param(
    [int]$Номер = 0,
    [string]$Имя = '',
    [switch]$ТолькоСобрать
)

$ErrorActionPreference = 'Stop'

$корень = Split-Path -Parent $MyInvocation.MyCommand.Path
$клиент = Join-Path $корень 'client'
$свойства = Join-Path $клиент 'gradle.properties'

function Скажи($текст) { Write-Host $текст }
function Беда($текст) { Write-Host $текст -ForegroundColor Red }

function Прочитать($имя) {
    $строка = Select-String -Path $свойства -Pattern "^$([regex]::Escape($имя))=(.*)$" | Select-Object -First 1
    if (-not $строка) { throw "в gradle.properties нет $имя" }
    return $строка.Matches[0].Groups[1].Value.Trim()
}

function Записать($имя, $значение) {
    # Файл перечитывается целиком и пишется целиком: правка построчно на Windows
    # однажды оставит смешанные переводы строк, и diff станет нечитаемым.
    $текст = Get-Content -Raw -Encoding UTF8 $свойства
    $текст = [regex]::Replace($текст, "(?m)^$([regex]::Escape($имя))=.*$", "$имя=$значение")
    [System.IO.File]::WriteAllText($свойства, $текст, (New-Object System.Text.UTF8Encoding($false)))
}

Скажи ''
Скажи '=== TIMA: выпуск версии ==='
Скажи ''

$кодСейчас = [int](Прочитать 'tima.versionCode')
$имяСейчас = Прочитать 'tima.versionName'
$поток = Прочитать 'tima.stream'
Скажи "Сейчас: код $кодСейчас, имя $имяСейчас, поток $поток"

if (-not $ТолькоСобрать) {
    $новыйКод = if ($Номер -gt 0) { $Номер } else { $кодСейчас + 1 }
    if ($новыйКод -le $кодСейчас) {
        Беда "Номер $новыйКод не больше текущего $кодСейчас."
        Скажи 'Номер обязан расти с каждой раздаваемой сборкой: Windows и Android сравнивают'
        Скажи 'именно его, и по нему же различают, что стоит на устройстве.'
        exit 1
    }
    Записать 'tima.versionCode' $новыйКод
    if ($Имя) { Записать 'tima.versionName' $Имя }
    $кодСейчас = $новыйКод
    $имяСейчас = Прочитать 'tima.versionName'
    Скажи "Стало:  код $кодСейчас, имя $имяСейчас"
}

Push-Location $клиент
try {
    Скажи ''
    Скажи 'Собираю APK...'
    & .\gradlew.bat ':app-android:assembleDebug'
    if ($LASTEXITCODE -ne 0) { throw "APK не собрался (код $LASTEXITCODE)" }
} finally {
    Pop-Location
}

# MSI собирает соседний скрипт: он же ищет WiX и объясняет его отсутствие.
& (Join-Path $корень 'build-installer.ps1')
if ($LASTEXITCODE -ne 0) { throw 'MSI не собрался' }

$apk = Join-Path $клиент 'app-android\build\outputs\apk\debug\app-android-debug.apk'
$msi = Get-ChildItem -Path (Join-Path $клиент 'app-desktop\build\compose\binaries\main\msi') -Filter '*.msi' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1

if (-not (Test-Path $apk)) { Беда "APK не появился: $apk"; exit 1 }
if (-not $msi) { Беда 'MSI не появился'; exit 1 }

$apkФайл = Get-Item $apk
$apkХэш = (Get-FileHash -Path $apk -Algorithm SHA256).Hash.ToLower()
$msiХэш = (Get-FileHash -Path $msi.FullName -Algorithm SHA256).Hash.ToLower()

Скажи ''
Скажи '── Собрано ─────────────────────────────────────────────────────────────'
Скажи "APK  $($apkФайл.FullName)"
Скажи "     $([math]::Round($apkФайл.Length / 1MB, 1)) МБ, sha256 $apkХэш"
Скажи "MSI  $($msi.FullName)"
Скажи "     $([math]::Round($msi.Length / 1MB, 1)) МБ, sha256 $msiХэш"
Скажи ''
Скажи '── Для deploy/.env (положив файлы в deploy/download/) ───────────────────'
Скажи ''
Скажи "APP_STREAM=$поток"
Скажи "APP_LATEST_VERSION_CODE=$кодСейчас"
Скажи "APP_LATEST_VERSION_NAME=$имяСейчас"
Скажи "APP_APK_URL=https://api.DOMAIN/download/TIMA-$кодСейчас.apk"
Скажи "APP_APK_SHA256=$apkХэш"
Скажи "APP_APK_SIZE=$($apkФайл.Length)"
Скажи "APP_WIN_VERSION_CODE=$кодСейчас"
Скажи "APP_WIN_VERSION_NAME=$имяСейчас"
Скажи "APP_MSI_URL=https://api.DOMAIN/download/TIMA-$кодСейчас.msi"
Скажи "APP_MSI_SHA256=$msiХэш"
Скажи "APP_MSI_SIZE=$($msi.Length)"
Скажи ''
Скажи 'Имена файлов в раздаче — с номером сборки: при общем имени однажды раздаётся'
Скажи 'вчерашний файл, и по экрану этого не видно.'
Скажи ''
Скажи 'Дальше — выкатка (только по просьбе заказчика) и запись в ВЕРСИИ/КЛИЕНТЫ.md.'
