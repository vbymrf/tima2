# Собрать отладочный APK из текущих исходников и поставить его на телефоны.
#
# Пускать через update-and-install-phone.bat. Правило `runtime-launchers.mdc`:
# запускать сборку и установку в обход этого скрипта нельзя — набор шагов и
# проверок расходится, и «поставилось» перестаёт значить одно и то же.
#
# ── ПОЧЕМУ ЗДЕСЬ ЕСТЬ ЧУЖОЙ СЕРВЕР ADB ───────────────────────────────────────
#
# Телефоны могут висеть не на этой машине. Так и есть на машине разработки:
# работа идёт в гостевой ВМ, телефоны — в сети хоста, и в маршрутах гостя такой
# сети нет — она уходит в туннель. Поэтому adb-сервер живёт на хосте, а здесь
# работает только клиент: `adb -L tcp:<хост>:5037`. Команду `install` исполняет
# сервер хоста, туннель ей не мешает.
#
# Адрес сервера — параметром или переменной среды TIMA_ADB_SERVER, но не
# литералом в скрипте: на другой машине телефон будет висеть по USB, и вписанный
# адрес превратил бы чужую раскладку в общую поломку.
#
# ── ЧТО СТАВИТСЯ ─────────────────────────────────────────────────────────────
#
# Отладочная сборка: `io.tima.app.v2`, версия с суффиксом `-v2`. Она живёт РЯДОМ
# с v1 (`io.tima.app`), а не вместо неё — так решено в app-android/build.gradle.kts.
#
# Установка идёт обновлением (`install -r`). Переустановка с потерей данных на
# телефоне здесь не делается никогда: подпись разошлась — скрипт скажет об этом и
# остановится, а решение стирать чужие данные принимает человек.

[CmdletBinding()]
param(
    # Адрес чужого сервера adb, `host:port`. Пусто — сервер на этой машине.
    [string]$AdbServer = $env:TIMA_ADB_SERVER,

    # Ставить только на эти устройства. Пусто — на все, что отвечают.
    [string[]]$Serial,

    # Не собирать, взять уже собранный APK. Для повторной установки после отказа.
    [switch]$SkipBuild,

    # Не запускать приложение после установки.
    [switch]$NoLaunch
)

# ── ПОЧЕМУ ЗДЕСЬ НЕ 'Stop' ────────────────────────────────────────────────────
#
# При 'Stop' любая строка, которую внешняя программа пишет в поток ошибок,
# превращается в NativeCommandError и рвёт скрипт. Поймано на первом же прогоне:
# adb отказал в установке на один телефон, PowerShell бросил исключение прямо в
# цикле, и до двух остальных дело не дошло — притом что отказ был обработан
# двумя строками ниже. Ошибки внешних программ здесь разбираются по коду выхода
# и по тексту, а не исключением: у adb «не поставилось» — обычный исход, а не
# сбой скрипта. Ошибки самих командлетов ловятся строгим режимом и проверками.
$ErrorActionPreference = 'Continue'
Set-StrictMode -Version Latest

# Список через запятую приходит от .bat ОДНОЙ строкой: `powershell -File` не
# разбирает массивы, и «-Serial a,b» превращается в одно значение «a,b». Режем
# сами, иначе фильтр не совпадает ни с чем и скрипт говорит «ничего не подошло»
# на два верных серийника.
if ($Serial) { $Serial = @($Serial | ForEach-Object { $_ -split ',' } | Where-Object { $_ }) }

$root = $PSScriptRoot
$package = 'io.tima.app.v2'
$activity = 'io.tima.app.MainActivity'
$apk = Join-Path $root 'client\app-android\build\outputs\apk\debug\app-android-debug.apk'

function Note([string]$text) { Write-Host $text }
function Bad([string]$text) { Write-Host $text -ForegroundColor Red }
function Good([string]$text) { Write-Host $text -ForegroundColor Green }

# ── Второй запуск отклоняется ────────────────────────────────────────────────
#
# Две сборки Gradle в одном дереве дерутся за кеш, а две установки на один
# телефон — за adb. Замок именованный: файл в TEMP с номером процесса, чтобы
# после падения он не остался запертым навсегда.
$lock = Join-Path $env:TEMP 'tima-update-phone.lock'
if (Test-Path $lock) {
    $owner = (Get-Content $lock -Raw).Trim()
    $alive = $null
    try { $alive = Get-Process -Id ([int]$owner) -ErrorAction Stop } catch { $alive = $null }
    if ($alive) {
        Bad "Уже идёт установка, процесс $owner. Двух сразу нельзя: они подерутся за Gradle и за adb."
        exit 2
    }
    Note "Замок остался от процесса $owner, которого больше нет. Беру его себе."
}
Set-Content -Path $lock -Value $PID -Encoding ascii

try {
    # ── adb: только через Get-Command или переменную SDK ─────────────────────
    $adb = $null
    $found = Get-Command adb -ErrorAction SilentlyContinue
    if ($found) {
        $adb = $found.Source
    } else {
        foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
            if (-not $sdk) { continue }
            $try = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path $try) { $adb = $try; break }
        }
    }
    if (-not $adb) {
        Bad 'adb не найден. Нужен он в PATH либо ANDROID_HOME / ANDROID_SDK_ROOT с platform-tools.'
        exit 3
    }
    Note "adb: $adb"

    # Префикс адреса сервера. Живёт в массиве, а не в строке: строку PowerShell
    # передал бы одним аргументом, и adb получил бы «-L tcp:host:5037» целиком.
    $server = @()
    if ($AdbServer) {
        $server = @('-L', "tcp:$AdbServer")
        Note "сервер adb: $AdbServer (чужой, эта машина только клиент)"
    } else {
        Note 'сервер adb: на этой машине'
    }

    # ── Кто отвечает ─────────────────────────────────────────────────────────
    $lines = & $adb @server devices 2>&1
    if ($LASTEXITCODE -ne 0) {
        Bad ('adb не смог поговорить с сервером:' + [Environment]::NewLine + $lines)
        if ($AdbServer) {
            Bad 'На хосте нужен: adb -a nodaemon server, плюс правило брандмауэра на входящий 5037.'
        }
        exit 4
    }

    $attached = @()
    foreach ($line in $lines) {
        if ($line -match '^(\S+)\s+device$') { $attached += $Matches[1] }
    }
    if (-not $attached) {
        Bad 'Ни одно устройство не отвечает. Проверьте, что телефон подключён и отладка разрешена.'
        exit 5
    }

    # ── Один телефон — одно устройство ───────────────────────────────────────
    #
    # Один и тот же телефон виден дважды: по TCP и по mDNS
    # (`adb-…._adb-tls-connect._tcp`). Без этой свёртки установка идёт на него
    # два раза, вторая падает или, хуже, проходит и удваивает время.
    # Различает их аппаратный серийник, а не имя записи.
    $devices = @{}
    foreach ($id in $attached) {
        $hw = (& $adb @server -s $id shell getprop ro.serialno 2>$null)
        if ($LASTEXITCODE -ne 0 -or -not $hw) { $hw = $id }
        $hw = "$hw".Trim()
        # Запись по mDNS проигрывает обычной: она пропадает вместе со службой.
        if ($devices.ContainsKey($hw) -and $id -like '*_adb-tls-connect._tcp') { continue }
        $devices[$hw] = $id
    }

    $targets = @()
    foreach ($hw in $devices.Keys) {
        $id = $devices[$hw]
        if ($Serial -and ($Serial -notcontains $id) -and ($Serial -notcontains $hw)) { continue }
        $model = (& $adb @server -s $id shell getprop ro.product.model 2>$null)
        $release = (& $adb @server -s $id shell getprop ro.build.version.release 2>$null)
        $abi = (& $adb @server -s $id shell getprop ro.product.cpu.abi 2>$null)
        $targets += [pscustomobject]@{
            Id = $id
            Hardware = $hw
            Model = "$model".Trim()
            Android = "$release".Trim()
            Abi = "$abi".Trim()
        }
    }
    if (-not $targets) {
        Bad 'Под заданный -Serial ничего не подошло.'
        exit 5
    }

    Note ''
    Note 'Телефоны:'
    foreach ($t in $targets) {
        Note ("  {0,-24} {1} · Android {2} · {3}" -f $t.Id, $t.Model, $t.Android, $t.Abi)
    }
    Note ''

    # ── Сборка ───────────────────────────────────────────────────────────────
    if ($SkipBuild) {
        if (-not (Test-Path $apk)) {
            Bad "Сказано -SkipBuild, а собранного APK нет: $apk"
            exit 6
        }
        Note 'Сборка пропущена, беру уже собранный APK.'
    } else {
        $gradlew = Join-Path $root 'client\gradlew.bat'
        if (-not (Test-Path $gradlew)) {
            Bad "Не найден $gradlew — скрипт лежит не в корне репозитория?"
            exit 3
        }
        Note 'Собираю отладочный APK…'
        Push-Location (Join-Path $root 'client')
        try {
            & $gradlew --no-daemon :app-android:assembleDebug --console=plain
            if ($LASTEXITCODE -ne 0) {
                Bad 'Сборка упала. Ставить нечего.'
                exit 7
            }
        } finally { Pop-Location }
        if (-not (Test-Path $apk)) {
            Bad "Сборка прошла, а APK нет там, где ожидался: $apk"
            exit 7
        }
    }

    # ── Чем подписан APK ─────────────────────────────────────────────────────
    #
    # Спрашивается заранее и ровно для одного: чтобы отказ по подписи назвал оба
    # ключа, а не только «signatures do not match». Отладочный ключ Android
    # генерирует на каждой машине СВОЙ, поэтому смена машины сборки ломает
    # обновление у всех, у кого приложение уже стоит, — и по сообщению Android
    # этого не видно.
    #
    # apksigner, а не keytool: APK подписан схемой v2/v3, JAR-подписи в нём нет,
    # и keytool отвечает «Not a signed jar file».
    $fingerprint = ''
    $signer = Get-Command apksigner -ErrorAction SilentlyContinue
    $signerPath = if ($signer) { $signer.Source } else { $null }
    if (-not $signerPath) {
        foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
            if (-not $sdk) { continue }
            $tools = Join-Path $sdk 'build-tools'
            if (-not (Test-Path $tools)) { continue }
            $newest = Get-ChildItem $tools -Directory | Sort-Object Name | Select-Object -Last 1
            if (-not $newest) { continue }
            $try = Join-Path $newest.FullName 'apksigner.bat'
            if (Test-Path $try) { $signerPath = $try; break }
        }
    }
    if ($signerPath -and (Test-Path $apk)) {
        $certs = & $signerPath verify --print-certs $apk 2>&1
        foreach ($line in $certs) {
            if ($line -match 'certificate SHA-256 digest:\s*(\S+)') { $fingerprint = $Matches[1]; break }
        }
    }

    $built = Get-Item $apk
    Note ''
    Note ("APK: {0}  {1:N1} МБ  собран {2:HH:mm:ss}" -f $built.Name, ($built.Length / 1MB), $built.LastWriteTime)
    if ($fingerprint) { Note "подписан ключом SHA-256 $fingerprint" }
    Note ''

    # ── Установка ────────────────────────────────────────────────────────────
    $failed = @()
    foreach ($t in $targets) {
        Note "→ $($t.Model) ($($t.Id))"
        $out = & $adb @server -s $t.Id install -r $apk 2>&1
        $text = ($out | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or $text -notmatch 'Success') {
            Bad '   не поставилось:'
            # PowerShell приписывает к строке чужой программы своё оформление
            # ошибки: «At …ps1:248 char:16», подчёркивание плюсами, CategoryInfo.
            # Человеку это ничего не говорит, а настоящую причину прячет — режем.
            foreach ($l in ($text -split "`r?`n")) {
                if ($l -match '^\s*(At |\+|CategoryInfo|FullyQualifiedErrorId)') { continue }
                $clean = $l -replace '^adb(\.exe)?\s*:\s*', '' -replace '^adb(\.exe)?:\s*', ''
                if ($clean.Trim()) { Bad "   $($clean.Trim())" }
            }
            # Самый частый отказ, и он не про код: отладочный ключ на каждой
            # машине свой, и Android не даёт обновить приложение чужой подписью.
            # Лечится только переустановкой, а она стирает данные на телефоне —
            # поэтому решение за человеком, а не за скриптом.
            if ($text -match 'SIGNATURE|UPDATE_INCOMPATIBLE') {
                Bad '   Подпись разошлась с уже установленной сборкой. Обновить нельзя — так решает'
                Bad '   Android, и обойти это нечем.'
                if ($fingerprint) { Bad "   Этот APK подписан ключом SHA-256 $fingerprint" }
                Bad '   Причина почти всегда одна: сборка идёт на ДРУГОЙ машине. Отладочный ключ'
                Bad '   Android создаёт на каждой машине свой, и он лежит в ~/.android/debug.keystore.'
                Bad '   Два выхода, и первый лучше:'
                Bad '     1) принести debug.keystore с той машины, где собирали раньше — данные целы;'
                Bad "     2) переустановить, СТЕРЕВ данные приложения на телефоне:"
                Bad "          adb -s $($t.Id) uninstall $package"
            }
            $failed += $t
            continue
        }

        Good '   поставилось'

        # grep телефона, а не Select-String: строка уезжает в оболочку Android.
        $version = (& $adb @server -s $t.Id shell "dumpsys package $package | grep -m1 versionName" 2>$null)
        if ($version) { Note ("   " + "$version".Trim()) }

        if (-not $NoLaunch) {
            $null = & $adb @server -s $t.Id shell am start -n "$package/$activity" 2>&1
            if ($LASTEXITCODE -eq 0) { Note '   запущено' } else { Bad '   поставилось, но не запустилось' }
        }
    }

    Note ''
    if ($failed) {
        Bad ("Не поставилось на {0} из {1}: {2}" -f $failed.Count, $targets.Count, (($failed | ForEach-Object { $_.Model }) -join ', '))
        exit 8
    }
    Good ("Готово: {0} из {0} телефонов обновлены." -f $targets.Count)
} finally {
    Remove-Item $lock -ErrorAction SilentlyContinue
}
