#Requires -Version 5.1
<#
.SYNOPSIS
    Ищет в репозитории TIMA внешние обращения и механизмы, способные их породить.

.DESCRIPTION
    Правило проекта: код, сборка и выкатка не имеют права ходить куда-либо, кроме серверов
    проекта и явно перечисленных источников зависимостей. Список — allowed-hosts.txt рядом.
    Всё, чего в списке нет, сканер считает внедрением: в коде и конфигурации — нарушение,
    в документации — к сведению (нарушение при -StrictDocs).

    Четыре группы проверок:
      1. хосты      — каждый URL и адрес в файлах против списка допустимых;
      2. механизмы  — то, чем внедрение обычно пользуется: `curl … | sh`, Invoke-Expression,
                      загрузчики в скриптах, Exec-задачи Gradle, ProcessBuilder / os.exec,
                      поведение сборки по переменным окружения, непрозрачные блобы;
      3. целостность — gradle-wrapper.jar против официальных хешей, хуки git, конфиги агентов
                      (hooks, MCP), чужие CLAUDE.md / AGENTS.md внутри рабочего каталога,
                      непривязанные docker-образы и GitHub Actions, файлы, похожие на секреты;
      4. машина     — init-скрипты Gradle, ~/.m2, переменные окружения с прокси и агентами JVM.

    Сканер сам никуда не ходит. Исключение — -Online: тогда для версий Gradle, которых нет во
    встроенной таблице, контрольная сумма wrapper берётся с services.gradle.org.

.PARAMETER Root
    Корень репозитория. По умолчанию — родитель каталога, где лежит скрипт.
.PARAMETER IncludeIgnored
    Сканировать и то, что игнорирует git: doc_add, копии сторонних репозиториев, keystore.
    Артефакты сборки (build/, .gradle/, node_modules/, эталон-v1/) не сканируются никогда.
.PARAMETER Online
    Разрешить обращение к services.gradle.org за контрольными суммами wrapper.
.PARAMETER StrictDocs
    Недопустимый хост в документации считать нарушением, а не сведением.
.PARAMETER ShowAccepted
    Печатать и принятые замечания (из accepted-findings.txt), а не только их число.
.PARAMETER LogPath
    Полный протокол. По умолчанию last-run.log рядом со скриптом.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File doc_vnedren\check-external-access.ps1
.EXAMPLE
    pwsh -NoProfile -File doc_vnedren\check-external-access.ps1 -IncludeIgnored -Online -StrictDocs

.NOTES
    Коды выхода: 0 — чисто; 1 — есть замечания, которых нет в accepted-findings.txt;
    2 — есть нарушения. Нарушение снимается только правкой кода или allowed-hosts.txt.
    Файл сохранён с BOM: Windows PowerShell 5.1 без BOM читает кириллицу как ANSI.
#>
[CmdletBinding()]
param(
    [string]$Root,
    [switch]$IncludeIgnored,
    [switch]$Online,
    [switch]$StrictDocs,
    [switch]$ShowAccepted,
    [string]$LogPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Root) { $Root = Split-Path -Parent $here }
$Root = (Resolve-Path $Root).Path
if (-not $LogPath) { $LogPath = Join-Path $here 'last-run.log' }
$allowedPath  = Join-Path $here 'allowed-hosts.txt'
$acceptedPath = Join-Path $here 'accepted-findings.txt'

# ── Вывод: на экран и в протокол одновременно ────────────────────────────────
$script:log = New-Object System.Text.StringBuilder
function Out-Line {
    param([string]$Text = '', [string]$Color)
    [void]$script:log.AppendLine($Text)
    if ($Color) { Write-Host $Text -ForegroundColor $Color } else { Write-Host $Text }
}
function Out-Head { param([string]$Text) Out-Line ''; Out-Line ('══ ' + $Text + ' ' + ('═' * [Math]::Max(0, 76 - $Text.Length))) 'Cyan' }

# ── Находки ─────────────────────────────────────────────────────────────────
# Level: VIOLATION — нарушение правила; WARN — требует взгляда; INFO — к сведению.
$findings = New-Object System.Collections.Generic.List[object]
function Add-Finding {
    param([string]$Kind, [string]$Level, [string]$Path, [int]$Line, [string]$Detail)
    $findings.Add([pscustomobject]@{ Kind = $Kind; Level = $Level; Path = $Path; Line = $Line; Detail = $Detail; Accepted = $false })
}

# ── Списки: допустимые хосты и принятые замечания ────────────────────────────
$allowExact  = @{}   # host -> причина
$allowSuffix = @{}   # suffix -> причина (для *.suffix)
if (Test-Path $allowedPath) {
    foreach ($raw in [IO.File]::ReadAllLines($allowedPath)) {
        $line = $raw.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $parts = $line.Split('|', 2)
        $entry = $parts[0].Trim().ToLowerInvariant()
        $why  = if ($parts.Count -gt 1) { $parts[1].Trim() } else { '' }
        if ($entry.StartsWith('*.')) { $allowSuffix[$entry.Substring(2)] = $why } else { $allowExact[$entry] = $why }
    }
} else {
    Out-Line "Нет списка допустимых хостов: $allowedPath — все внешние хосты будут нарушениями." 'Yellow'
}

$accepted = @{}      # "kind|path" -> причина
if (Test-Path $acceptedPath) {
    foreach ($raw in [IO.File]::ReadAllLines($acceptedPath)) {
        $line = $raw.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $parts = $line.Split('|', 3)
        if ($parts.Count -lt 2) { continue }
        $key = ($parts[0].Trim() + '|' + $parts[1].Trim().Replace('\', '/'))
        $accepted[$key] = if ($parts.Count -gt 2) { $parts[2].Trim() } else { '' }
    }
}

# ── Какие файлы смотреть ─────────────────────────────────────────────────────
# Источник истины — git: он знает, что отслеживается, что нет и что игнорируется.
[Console]::OutputEncoding = [Text.Encoding]::UTF8
function Git-Files {
    # $Args — автоматическая переменная PowerShell, параметр так называть нельзя: splatting
    # @Args берёт её, а не параметр, и списки приходят пустыми. Поймано первым прогоном.
    param([string[]]$GitArgs)
    $out = & git -C $Root -c core.quotepath=false ls-files -z @GitArgs 2>$null
    if (-not $out) { return @() }
    return (($out -join '') -split "`0" | Where-Object { $_ })
}
$head = (& git -C $Root rev-parse --short HEAD 2>$null)
$branch = (& git -C $Root rev-parse --abbrev-ref HEAD 2>$null)

# Артефакты и двоичное — не источники, их не читаем. doc_vnedren — сам сканер, его списки и
# отчёты: там перечислены и хосты, и шаблоны, и находить их в себе — считать дважды.
$skipDirRx = [regex]'(^|/)(\.git|build|\.gradle|\.kotlin|node_modules|\.idea|эталон-v1|out|target|dist|doc_vnedren)(/|$)'
$binaryExt = @('.jar','.jks','.db','.png','.jpg','.jpeg','.gif','.ico','.docx','.pdf','.zip','.exe','.dll','.so','.dylib','.class','.ttf','.otf','.woff','.woff2','.apk','.bin','.keystore','.p12','.pfx','.webp','.mp3','.mp4','.wasm')
function Select-Sources { param([string[]]$Paths)
    return @($Paths | Sort-Object -Unique | Where-Object {
        -not $skipDirRx.IsMatch($_) -and ($binaryExt -notcontains [IO.Path]::GetExtension($_).ToLowerInvariant())
    })
}
$trackedFiles   = @(Git-Files @())
$untrackedFiles = @(Git-Files @('--others', '--exclude-standard'))
# Игнорируемое перечисляется всегда: чужие инструкции агентам и секреты ищутся и там,
# даже когда содержимое этих каталогов не сканируется.
$ignoredFiles   = @(Git-Files @('--others', '--ignored', '--exclude-standard') | Where-Object { -not $skipDirRx.IsMatch($_) })
$files = Select-Sources ($trackedFiles + $untrackedFiles)
if ($IncludeIgnored) { $files = Select-Sources ($trackedFiles + $untrackedFiles + $ignoredFiles) }
$trackedSet = @{}; foreach ($t in $trackedFiles) { $trackedSet[$t] = $true }
# Игнорируемое — копии чужих репозиториев и черновики: в сборку не входит, поэтому найденное
# там не нарушение, а сведение. Внедрение через них возможно только руками агента — это
# ловят проверки чужих инструкций и хуков, а не список хостов.
$ignoredSet = @{}; foreach ($t in $ignoredFiles) { $ignoredSet[$t] = $true }

# Документация или код? Решает, чем считать недопустимый хост.
$docExt = @('.md','.txt','.rst','.adoc','.html','.htm','.docx')
$docPathRx = [regex]'(?i)(^|/)(doc|docs|doc_mig|doc_add|doc_vnedren|ДОКУМЕНТАЦИЯ|CHANGES)(/|$)|(^|/)(README|CHANGELOG|CHANGES|HISTORY|LICENSE|NOTICE|CITATION)[^/]*$'
function Test-DocFile { param([string]$Path)
    if ($docExt -contains [IO.Path]::GetExtension($Path).ToLowerInvariant()) { return $true }
    return $docPathRx.IsMatch($Path)
}
$scriptExt = @('.ps1','.bat','.cmd','.sh','.yml','.yaml','.kts','.gradle','.mjs','.js','.py','.mk')

# ── Регулярные выражения ─────────────────────────────────────────────────────
$rxUrl    = [regex]'(?i)\b(?:https?|wss?|ssh|ftp|git|s3)\\?://(?:[^\s/@"''<>()\[\]]+@)?(\[[0-9a-f:.]+\]|[a-z0-9][a-z0-9.\-]*)(?::(\d{1,5}))?'
$rxGitAt  = [regex]'(?i)\bgit@([a-z0-9][a-z0-9.\-]*):'
$rxUserAt = [regex]'(?i)\b[a-z0-9_.\-]+@((?:\d{1,3}\.){3}\d{1,3}|[a-z0-9][a-z0-9.\-]*\.[a-z]{2,}):\d{1,5}\b'
# Образы: `image:` только в compose и workflow, `FROM` (с учётом регистра) только в Dockerfile —
# иначе каждый `from x import y` в Python становится «образом».
$rxImage  = [regex]'(?i)^\s*(?:-\s*)?image:\s*["'']?([a-z0-9][a-z0-9._\-/:@]*)'
$rxFrom   = [regex]'^\s*FROM\s+(?:--platform=\S+\s+)?["'']?([A-Za-z0-9][A-Za-z0-9._\-/:@]*)'
$composeRx = [regex]'(?i)(^|/)[^/]*compose[^/]*\.ya?ml$|(^|/)\.github/workflows/[^/]+\.ya?ml$'
$dockerfileRx = [regex]'(?i)(^|/)Dockerfile[^/]*$|\.dockerfile$'
$rxUses   = [regex]'(?i)^\s*-?\s*uses:\s*["'']?([^\s"''#@]+)@([^\s"''#]+)'

# Механизмы. Каждая строка: вид, уровень по умолчанию, где искать (all|code|script|gradle), выражение.
$mechanisms = @(
    @{ Kind='pipe-to-shell';         Level='VIOLATION'; Where='all';    Rx=[regex]'(?i)\b(curl|wget)\b[^\r\n|]*\|\s*(sudo\s+(-E\s+)?)?(ba|z|da)?sh\b' }
    @{ Kind='ps-remote-exec';        Level='VIOLATION'; Where='code';   Rx=[regex]'(?i)\b(Invoke-Expression|iex|DownloadString|DownloadFile|Start-BitsTransfer|Net\.WebClient|ScriptBlock\]::Create)\b' }
    # Загрузчик считается вызванным, когда за ним идёт ключ, адрес или переменная — иначе это `apk add wget` или слово в комментарии.
    @{ Kind='downloader-in-script';  Level='WARN';      Where='script'; Rx=[regex]'(?i)(^|[\s;&|(`])(curl|wget|Invoke-WebRequest|Invoke-RestMethod|iwr|irm|certutil|bitsadmin)\s+(-|https?:|"|''|\$|\d)' }
    @{ Kind='env-conditional-build'; Level='WARN';      Where='gradle'; Rx=[regex]'System\.getenv\(|providers\.environmentVariable\(|System\.getProperty\("(http|https|socks)' }
    @{ Kind='gradle-exec-task';      Level='WARN';      Where='gradle'; Rx=[regex]'register<Exec>|<Exec>\(|\bcommandLine\(|\bexec\s*\{|Runtime\.getRuntime\(\)' }
    @{ Kind='process-exec';          Level='WARN';      Where='code';   Rx=[regex]'ProcessBuilder\(|Runtime\.getRuntime\(\)\.exec|exec\.Command(Context)?\(|"os/exec"|subprocess\.|child_process|Start-Process\b' }
    @{ Kind='dynamic-code';          Level='WARN';      Where='code';   Rx=[regex]'Class\.forName\(|System\.load(Library)?\(|plugin\.Open\(|(?<![\w.])eval\(|ScriptEngine|Add-Type\b|new Function\(' }
    @{ Kind='opaque-blob';           Level='WARN';      Where='code';   Rx=[regex]'[A-Za-z0-9+/]{300,}={0,2}' }
    # Открытый http:// к чужому хосту ловится проверкой хостов; здесь — только явные разрешения незащищённого протокола.
    @{ Kind='insecure-protocol';     Level='WARN';      Where='code';   Rx=[regex]'(?i)allowInsecureProtocol|usesCleartextTraffic\s*=\s*"true"|cleartextTrafficPermitted\s*=\s*"true"|InsecureSkipVerify\s*:\s*true|GOINSECURE|--insecure\b|-k\s+https?://' }
)
# Где блобы — данные, а не код, и потому не подозрительны.
$blobExemptRx = [regex]'(?i)(\.pb\.go|\.json|\.svg|\.lock|\.sq|vectors)$'

# ── Проход по файлам ─────────────────────────────────────────────────────────
$hosts = @{}   # host -> list of @{Path;Line;Doc;Ignored}
$imageRefs = New-Object System.Collections.Generic.List[object]
$actionRefs = New-Object System.Collections.Generic.List[object]
$scanned = 0; $skippedLarge = 0

function Add-Host { param([string]$HostName, [string]$Path, [int]$Line, [bool]$Doc, [bool]$Ignored)
    $h = $HostName.ToLowerInvariant()
    if (-not $hosts.ContainsKey($h)) { $hosts[$h] = New-Object System.Collections.Generic.List[object] }
    $hosts[$h].Add([pscustomobject]@{ Path = $Path; Line = $Line; Doc = $Doc; Ignored = $Ignored })
}

foreach ($rel in $files) {
    $full = Join-Path $Root $rel
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { continue }
    $fi = Get-Item -LiteralPath $full
    if ($fi.Length -gt 4MB) { $skippedLarge++; continue }
    $text = [IO.File]::ReadAllText($full)
    $scanned++
    $isDoc = Test-DocFile $rel
    $isIgnored = $ignoredSet.ContainsKey($rel)
    $ext = [IO.Path]::GetExtension($rel).ToLowerInvariant()
    $isDockerfile = $dockerfileRx.IsMatch($rel)
    $isCompose = $composeRx.IsMatch($rel)
    $isScript = $scriptExt -contains $ext -or $isDockerfile
    $isGradle = $ext -eq '.kts' -or $ext -eq '.gradle'
    $isCode = -not $isDoc
    $lines = $text -split "`r?`n"
    $n = 0
    foreach ($ln in $lines) {
        $n++
        foreach ($m in $rxUrl.Matches($ln))    { Add-Host $m.Groups[1].Value $rel $n $isDoc $isIgnored }
        foreach ($m in $rxGitAt.Matches($ln))  { Add-Host $m.Groups[1].Value $rel $n $isDoc $isIgnored }
        foreach ($m in $rxUserAt.Matches($ln)) { Add-Host $m.Groups[1].Value $rel $n $isDoc $isIgnored }
        if ($isCompose -or $isDockerfile) {
            $mi = if ($isDockerfile) { $rxFrom.Match($ln) } else { $rxImage.Match($ln) }
            if ($mi.Success) {
                $img = $mi.Groups[1].Value
                if ($img -notmatch '^(scratch|\$)') {
                    $first = $img.Split('/')[0]
                    $registry = if ($img.Contains('/') -and ($first.Contains('.') -or $first.Contains(':'))) { $first.Split(':')[0] } else { 'docker.io' }
                    Add-Host $registry $rel $n $false $isIgnored
                    $imageRefs.Add([pscustomobject]@{ Path = $rel; Line = $n; Image = $img; Ignored = $isIgnored })
                }
            }
        }
        if ($isCompose) {
            $mu = $rxUses.Match($ln)
            if ($mu.Success) { $actionRefs.Add([pscustomobject]@{ Path = $rel; Line = $n; Action = $mu.Groups[1].Value; Ref = $mu.Groups[2].Value; Ignored = $isIgnored }) }
        }
        foreach ($mech in $mechanisms) {
            $applies = switch ($mech.Where) {
                'all'    { $true }
                'code'   { $isCode }
                'script' { $isScript }
                'gradle' { $isGradle }
            }
            if (-not $applies) { continue }
            if ($mech.Kind -eq 'opaque-blob' -and $blobExemptRx.IsMatch($rel)) { continue }
            if ($mech.Rx.IsMatch($ln)) {
                # Загрузчик, бьющий только в петлю (проверка живости своей службы), — не обращение наружу.
                if ($mech.Kind -eq 'downloader-in-script') {
                    $lineHosts = @($rxUrl.Matches($ln) | ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() })
                    if ($lineHosts.Count -gt 0 -and -not @($lineHosts | Where-Object { $_ -notmatch '^(localhost|127\.\d+\.\d+\.\d+|0\.0\.0\.0|\[::1\])$' }).Count) { continue }
                }
                $level = $mech.Level
                if ($mech.Kind -eq 'pipe-to-shell' -and $isDoc) { $level = 'WARN' }
                if ($isIgnored) { $level = 'INFO' }   # копия чужого репозитория: не исполняется сборкой
                $snippet = $ln.Trim(); if ($snippet.Length -gt 140) { $snippet = $snippet.Substring(0, 140) + '…' }
                Add-Finding $mech.Kind $level $rel $n $snippet
            }
        }
    }
}

# ── Хосты: вердикты ──────────────────────────────────────────────────────────
function Resolve-Host { param([string]$h)
    # возвращает @{Verdict; Why}
    if ($h.EndsWith('.') -or $h -match '\$|\{') { return @{ Verdict = 'шаблон'; Why = 'подстановка переменной' } }
    if ($h -match '^(localhost|127\.\d+\.\d+\.\d+|0\.0\.0\.0|\[::1\]|::1)$') { return @{ Verdict = 'локальный'; Why = 'петля' } }
    if ($h -notmatch '\.') { return @{ Verdict = 'локальный'; Why = 'имя службы (compose) или ярлык' } }
    if ($h -match '^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)') { return @{ Verdict = 'локальный'; Why = 'частная сеть' } }
    if ($allowExact.ContainsKey($h)) { return @{ Verdict = 'допустим'; Why = $allowExact[$h] } }
    foreach ($s in $allowSuffix.Keys) { if ($h.EndsWith('.' + $s)) { return @{ Verdict = 'допустим'; Why = $allowSuffix[$s] } } }
    return @{ Verdict = 'НЕДОПУСТИМ'; Why = '' }
}

$hostRows = @()
$ignoredHosts = @{}   # каталог верхнего уровня -> список хостов, встреченных только в игнорируемом
foreach ($h in ($hosts.Keys | Sort-Object)) {
    $refs = $hosts[$h]
    $r = Resolve-Host $h
    $inCode = @($refs | Where-Object { -not $_.Doc -and -not $_.Ignored })
    $inDocs = @($refs | Where-Object { $_.Doc -and -not $_.Ignored })
    $inIgnored = @($refs | Where-Object { $_.Ignored })
    $verdict = $r.Verdict
    if ($verdict -eq 'НЕДОПУСТИМ' -and $inCode.Count -eq 0 -and $inDocs.Count -eq 0) {
        # Только в игнорируемых копиях: сводкой по каталогу, а не строкой на хост.
        foreach ($top in ($inIgnored | ForEach-Object { $_.Path.Split('/')[0] } | Sort-Object -Unique)) {
            if (-not $ignoredHosts.ContainsKey($top)) { $ignoredHosts[$top] = New-Object System.Collections.Generic.List[string] }
            $ignoredHosts[$top].Add($h)
        }
        foreach ($ref in $inIgnored) { Add-Finding 'host-in-ignored' 'INFO' $ref.Path $ref.Line $h }
        continue
    }
    if ($verdict -eq 'НЕДОПУСТИМ') {
        if ($inCode.Count -gt 0) {
            $verdict = 'НАРУШЕНИЕ'
            foreach ($ref in $inCode) { Add-Finding 'host-not-allowed' 'VIOLATION' $ref.Path $ref.Line $h }
        } elseif ($StrictDocs) {
            $verdict = 'НАРУШЕНИЕ (док.)'
            foreach ($ref in $inDocs) { Add-Finding 'host-not-allowed' 'VIOLATION' $ref.Path $ref.Line $h }
        } else {
            $verdict = 'к сведению (док.)'
            foreach ($ref in $inDocs) { Add-Finding 'host-in-docs' 'INFO' $ref.Path $ref.Line $h }
        }
    }
    $hostRows += [pscustomobject]@{ Host = $h; Verdict = $verdict; Code = $inCode.Count; Docs = $inDocs.Count; Why = $r.Why; Refs = $refs }
}

# ── Целостность ──────────────────────────────────────────────────────────────
# Официальные sha256 gradle-wrapper.jar. Источник: https://services.gradle.org/distributions/gradle-<v>-wrapper.jar.sha256
$knownWrapper = @{
    '7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172' = '8.14.3'
    'b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13' = '9.3.1'
    '91a239400bb638f36a1795d8fdf7939d532cdc7d794d1119b7261aac158b1e60' = '7.5 / 7.5.1'
    '0336f591bc0ec9aa0c9988929b93ecc916b3c1d52aed202c7381db144aa0ef15' = '8.4'
}
$wrapperRows = @()
foreach ($props in ($files | Where-Object { $_ -match '(^|/)gradle/wrapper/gradle-wrapper\.properties$' })) {
    $dir = $props.Substring(0, $props.LastIndexOf('/'))   # не Split-Path: он вернёт обратные косые, и ключ базы принятых не совпадёт
    $jarRel = ($dir + '/gradle-wrapper.jar')
    $ptext = [IO.File]::ReadAllText((Join-Path $Root $props))
    $urlM = [regex]::Match($ptext, 'distributionUrl=(\S+)')
    $url = $urlM.Groups[1].Value.Replace('\', '')
    $ver = [regex]::Match($url, 'gradle-([\d.]+?)-(bin|all)\.zip').Groups[1].Value
    $urlHost = [regex]::Match($url, '://([^/]+)/').Groups[1].Value
    $hasSum = $ptext -match 'distributionSha256Sum='
    $jarFull = Join-Path $Root $jarRel
    $status = 'нет jar'; $hash = ''
    if (Test-Path -LiteralPath $jarFull) {
        $hash = (Get-FileHash -LiteralPath $jarFull -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($knownWrapper.ContainsKey($hash)) {
            $known = $knownWrapper[$hash]
            if ($known -eq $ver -or $known.Split('/') -contains $ver) { $status = "официальный $ver" }
            else {
                $status = "официальный Gradle $known, а properties просят $ver"
                Add-Finding 'wrapper-version-skew' 'WARN' $jarRel 0 $status
            }
        } elseif ($Online -and $ver) {
            try {
                $official = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 20 -Uri "https://services.gradle.org/distributions/gradle-$ver-wrapper.jar.sha256").Content.Trim().ToLowerInvariant()
                if ($official -eq $hash) { $status = "официальный $ver (сверено online)" }
                else { $status = "НЕ СОВПАДАЕТ с официальным $ver"; Add-Finding 'wrapper-mismatch' 'VIOLATION' $jarRel 0 "sha256 $hash ≠ официальный $official" }
            } catch { $status = "не сверен: $($_.Exception.Message)"; Add-Finding 'wrapper-unverified' 'WARN' $jarRel 0 $status }
        } else {
            $status = 'хеш неизвестен таблице (запустить с -Online)'
            Add-Finding 'wrapper-unverified' 'WARN' $jarRel 0 "sha256 $hash"
        }
    } else { Add-Finding 'wrapper-missing' 'WARN' $jarRel 0 'gradle-wrapper.jar отсутствует' }
    if ($urlHost -ne 'services.gradle.org') { Add-Finding 'wrapper-foreign-url' 'VIOLATION' $props 0 "distributionUrl ведёт на $urlHost" }
    $wrapperRows += [pscustomobject]@{ Build = $dir; Version = $ver; Host = $urlHost; Sha256Pinned = $hasSum; Status = $status; Hash = $hash }
}

# Сверка зависимостей Gradle: без verification-metadata.xml подмену артефакта не заметит никто.
$gradleBuilds = @($files | Where-Object { $_ -match '(^|/)settings\.gradle(\.kts)?$' } | ForEach-Object { if ($_.Contains('/')) { $_.Substring(0, $_.LastIndexOf('/')) } else { '' } })
foreach ($b in $gradleBuilds) {
    $vm = if ($b) { "$b/gradle/verification-metadata.xml" } else { 'gradle/verification-metadata.xml' }
    if (-not (Test-Path -LiteralPath (Join-Path $Root $vm))) { Add-Finding 'no-dependency-verification' 'INFO' $b 0 'нет gradle/verification-metadata.xml — зависимости не сверяются по хешам' }
}

# Хуки git: исполняются при коммите и не видны в diff.
$hooksDir = Join-Path $Root '.git/hooks'
if (Test-Path $hooksDir) {
    foreach ($hk in (Get-ChildItem -LiteralPath $hooksDir -File | Where-Object { $_.Name -notlike '*.sample' })) {
        Add-Finding 'git-hook-present' 'WARN' (".git/hooks/" + $hk.Name) 0 "хук git присутствует ($($hk.Length) Б)"
    }
}
foreach ($scope in @('--local', '--global', '--system')) {
    $hp = & git -C $Root config $scope --get core.hooksPath 2>$null
    if ($hp) { Add-Finding 'git-hooks-path' 'WARN' "git config $scope" 0 "core.hooksPath = $hp" }
}

# Конфиги агентов внутри репозитория: hooks и MCP — это исполнение чужого кода при каждом действии.
foreach ($cfg in @('.claude/settings.json', '.claude/settings.local.json', '.mcp.json', '.cursor/mcp.json', '.vscode/mcp.json')) {
    $p = Join-Path $Root $cfg
    if (Test-Path -LiteralPath $p) {
        $t = [IO.File]::ReadAllText($p)
        $what = @(); if ($t -match '"hooks"') { $what += 'hooks' }; if ($t -match '"mcpServers"|"command"') { $what += 'mcp/command' }
        Add-Finding 'agent-config' ($(if ($what.Count) { 'WARN' } else { 'INFO' })) $cfg 0 ("конфиг агента" + $(if ($what.Count) { ': ' + ($what -join ', ') } else { '' }))
    }
}

# Чужие инструкции агентам в рабочем каталоге. Агент, открывший файл под таким каталогом,
# получает эти инструкции как свои. Свои — корневой CLAUDE.md и .cursor/.
$ownAgentFiles = @('CLAUDE.md', '.cursor')
$agentFileRx = [regex]'(?i)(^|/)(CLAUDE\.md|AGENTS\.md|\.cursorrules|\.clinerules|\.windsurfrules|copilot-instructions\.md|GEMINI\.md)$|(^|/)\.(claude|serena|cursor)(/|$)'
$foreignDirs = @{}
$agentSearch = @($trackedFiles + $untrackedFiles + $ignoredFiles | Sort-Object -Unique)
foreach ($f in $agentSearch) {
    if (-not $agentFileRx.IsMatch($f)) { continue }
    $top = $f.Split('/')[0]
    if ($ownAgentFiles -contains $top -and $f -notmatch '/\.(claude|serena)/') { continue }
    if (-not $foreignDirs.ContainsKey($top)) { $foreignDirs[$top] = New-Object System.Collections.Generic.List[string] }
    $foreignDirs[$top].Add($f)
}
foreach ($d in $foreignDirs.Keys) {
    $shown = @($foreignDirs[$d] | Sort-Object { $_ -match '/\.' }, { $_ } | Select-Object -First 4)   # сначала CLAUDE.md/AGENTS.md, потом скрытые каталоги
    Add-Finding 'foreign-agent-files' 'WARN' $d 0 ("инструкции агентам в стороннем каталоге ($($foreignDirs[$d].Count) файлов): " + ($shown -join ', '))
}

# Docker-образы без привязки к digest: что придёт при следующем pull — неизвестно.
foreach ($ir in $imageRefs) {
    if ($ir.Image -match '@sha256:') { continue }
    $tag = if ($ir.Image -match ':([^/:]+)$') { $Matches[1] } else { '' }
    if ($tag -eq 'local') { continue }
    $lvl = if ($ir.Ignored) { 'INFO' } elseif (-not $tag -or $tag -eq 'latest') { 'WARN' } else { 'INFO' }
    Add-Finding 'floating-image-tag' $lvl $ir.Path $ir.Line ("образ " + $ir.Image + $(if (-not $tag) { ' (без тега = latest)' } else { '' }))
}
# GitHub Actions по тегу, а не по SHA: тег переставляется, SHA — нет.
foreach ($ar in $actionRefs) {
    if ($ar.Ref -notmatch '^[0-9a-f]{40}$') { Add-Finding 'unpinned-action' 'INFO' $ar.Path $ar.Line ($ar.Action + '@' + $ar.Ref) }
}
# Файлы, похожие на секреты. Печатается только путь — никогда содержимое.
$secretRx = [regex]'(?i)(token|secret|credential|password|\.pem|\.key|\.jks|\.p12|\.pfx|keystore\.properties|(^|/)\.env(\.[^/]+)?)$'
foreach ($f in $agentSearch) {
    if ($secretRx.IsMatch($f) -and $f -notmatch '\.example$|_test\.go$|Test\.kt$|/build/|node_modules/|/tests?/') {
        $tracked = $trackedSet.ContainsKey($f)
        Add-Finding 'secret-shaped-file' ($(if ($tracked) { 'VIOLATION' } else { 'WARN' })) $f 0 ($(if ($tracked) { 'ОТСЛЕЖИВАЕТСЯ GIT' } else { 'вне git, но на диске' }))
    }
}

# ── Машина ───────────────────────────────────────────────────────────────────
$machine = @()
$home_ = $env:USERPROFILE; if (-not $home_) { $home_ = $env:HOME }
foreach ($p in @("$home_/.gradle/init.d", "$home_/.gradle/init.gradle", "$home_/.gradle/init.gradle.kts", "$home_/.gradle/gradle.properties", "$home_/.m2/settings.xml", "$home_/.npmrc", "$home_/.docker/config.json", "$home_/.cursor/mcp.json")) {
    if (Test-Path -LiteralPath $p) {
        $extra = ''
        if ($p -match 'init\.d$') { $extra = ' — ' + ((Get-ChildItem -LiteralPath $p | Select-Object -ExpandProperty Name) -join ', ') }
        $machine += "ЕСТЬ   $p$extra"
        Add-Finding 'machine-config' 'WARN' $p 0 'файл влияет на сборку или на агента вне репозитория'
    } else { $machine += "нет    $p" }
}
$claudeSettings = "$home_/.claude/settings.json"
if (Test-Path -LiteralPath $claudeSettings) {
    $cs = [IO.File]::ReadAllText($claudeSettings)
    if ($cs -match '"hooks"') { Add-Finding 'machine-config' 'WARN' $claudeSettings 0 'в глобальных настройках Claude есть hooks' ; $machine += "ЕСТЬ   $claudeSettings (hooks!)" }
    else { $machine += "ок     $claudeSettings (без hooks)" }
}
$envRx = [regex]'(?i)proxy|JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS|GRADLE_OPTS|GRADLE_USER_HOME|GOFLAGS|GOPROXY|GONOSUMDB|GOINSECURE|GOPRIVATE|NODE_OPTIONS|NODE_EXTRA_CA_CERTS|SSL_CERT_FILE|REQUESTS_CA_BUNDLE|PIP_INDEX_URL|NPM_CONFIG_REGISTRY|DOCKER_HOST'
foreach ($ev in (Get-ChildItem Env: | Where-Object { $envRx.IsMatch($_.Name) } | Sort-Object Name)) {
    $val = $ev.Value; if ($ev.Name -match '(?i)token|secret|key|pass') { $val = '<скрыто>' }
    $machine += "ENV    $($ev.Name)=$val"
    Add-Finding 'machine-env' 'WARN' ('ENV ' + $ev.Name) 0 $val
}

# ── Принятые замечания ───────────────────────────────────────────────────────
foreach ($f in $findings) {
    if ($f.Level -eq 'VIOLATION') { continue }
    $key = $f.Kind + '|' + $f.Path
    if ($accepted.ContainsKey($key)) { $f.Accepted = $true }
}

# ── Печать ───────────────────────────────────────────────────────────────────
Out-Line ("Проверка внешних обращений TIMA — " + (Get-Date -Format 'yyyy-MM-dd HH:mm'))
Out-Line ("Корень: $Root   ветка: $branch   вершина: $head")
Out-Line ("Файлов просмотрено: $scanned" + $(if ($skippedLarge) { " (пропущено крупных: $skippedLarge)" } else { '' }) + "   режим: " + $(if ($IncludeIgnored) { 'с игнорируемыми' } else { 'отслеживаемые + неигнорируемые' }) + $(if ($Online) { ', online' } else { '' }) + $(if ($StrictDocs) { ', строго к документации' } else { '' }))

Out-Head '1. Хосты'
$fmt = '{0,-32} {1,-20} {2,5} {3,5}  {4}'
Out-Line ($fmt -f 'хост', 'вердикт', 'код', 'док.', 'зачем / где')
Out-Line ('-' * 100)
foreach ($row in ($hostRows | Sort-Object @{Expression = { switch -Wildcard ($_.Verdict) { 'НАРУШЕНИЕ*' { 0 } 'к сведению*' { 1 } 'допустим' { 2 } default { 3 } } }}, Host)) {
    $where = if ($row.Verdict -like 'НАРУШЕНИЕ*' -or $row.Verdict -like 'к сведению*') {
        (($row.Refs | Select-Object -First 3 | ForEach-Object { "$($_.Path):$($_.Line)" }) -join '; ') + $(if ($row.Refs.Count -gt 3) { " … ещё $($row.Refs.Count - 3)" } else { '' })
    } else { $row.Why }
    $color = switch -Wildcard ($row.Verdict) { 'НАРУШЕНИЕ*' { 'Red' } 'к сведению*' { 'Yellow' } 'допустим' { 'Green' } default { 'DarkGray' } }
    Out-Line ($fmt -f $row.Host, $row.Verdict, $row.Code, $row.Docs, $where) $color
}
if ($ignoredHosts.Count) {
    Out-Line ''
    Out-Line 'Хосты только в игнорируемых каталогах (копии чужих репозиториев, черновики) — к сведению, сборкой не исполняются:' 'DarkGray'
    foreach ($top in ($ignoredHosts.Keys | Sort-Object)) {
        $list = $ignoredHosts[$top]
        Out-Line ("    {0,-24} {1,4} хостов: {2}{3}" -f $top, $list.Count, (($list | Select-Object -First 6) -join ', '), $(if ($list.Count -gt 6) { ', …' } else { '' })) 'DarkGray'
    }
}

Out-Head '2. Механизмы загрузки и исполнения'
$mechKinds = @('pipe-to-shell','ps-remote-exec','downloader-in-script','env-conditional-build','gradle-exec-task','process-exec','dynamic-code','opaque-blob','insecure-protocol')
foreach ($k in $mechKinds) {
    $items = @($findings | Where-Object { $_.Kind -eq $k })
    if (-not $items.Count) { Out-Line ("{0,-24} нет" -f $k) 'DarkGray'; continue }
    $new = @($items | Where-Object { -not $_.Accepted -and $_.Level -ne 'INFO' })
    Out-Line ("{0,-24} {1} (новых: {2})" -f $k, $items.Count, $new.Count) $(if ($new.Count) { if ($items[0].Level -eq 'VIOLATION') { 'Red' } else { 'Yellow' } } else { 'Green' })
    foreach ($it in $items) {
        if ($it.Accepted -and -not $ShowAccepted) { continue }
        Out-Line ("    {0}{1}:{2}  {3}" -f $(if ($it.Accepted) { '[принято] ' } else { '' }), $it.Path, $it.Line, $it.Detail)
    }
}

Out-Head '3. Целостность'
Out-Line 'gradle-wrapper.jar:'
foreach ($w in $wrapperRows) {
    $c = if ($w.Status -like 'официальный*' -and $w.Status -notlike '*просят*') { 'Green' } elseif ($w.Status -like 'НЕ СОВПАДАЕТ*') { 'Red' } else { 'Yellow' }
    Out-Line ("    {0,-32} {1,-8} {2,-22} sha256Sum={3,-5} {4}" -f $w.Build, $w.Version, $w.Host, $w.Sha256Pinned, $w.Status) $c
}
$otherKinds = @('wrapper-foreign-url','wrapper-mismatch','wrapper-unverified','wrapper-missing','no-dependency-verification','git-hook-present','git-hooks-path','agent-config','foreign-agent-files','floating-image-tag','unpinned-action','secret-shaped-file')
foreach ($k in $otherKinds) {
    $items = @($findings | Where-Object { $_.Kind -eq $k })
    if (-not $items.Count) { Out-Line ("{0,-28} нет" -f $k) 'DarkGray'; continue }
    $new = @($items | Where-Object { -not $_.Accepted -and $_.Level -ne 'INFO' })
    $lvl = ($items | Sort-Object { switch ($_.Level) { 'VIOLATION' { 0 } 'WARN' { 1 } default { 2 } } } | Select-Object -First 1).Level
    Out-Line ("{0,-28} {1} (новых: {2}, уровень: {3})" -f $k, $items.Count, $new.Count, $lvl) $(if ($new.Count) { if ($lvl -eq 'VIOLATION') { 'Red' } elseif ($lvl -eq 'WARN') { 'Yellow' } else { 'Gray' } } else { 'Green' })
    foreach ($it in $items) {
        if ($it.Accepted -and -not $ShowAccepted) { continue }
        Out-Line ("    {0}{1}{2}  {3}" -f $(if ($it.Accepted) { '[принято] ' } else { '' }), $it.Path, $(if ($it.Line) { ":$($it.Line)" } else { '' }), $it.Detail)
    }
}

Out-Head '4. Машина (вне репозитория)'
foreach ($m in $machine) { Out-Line ("    " + $m) $(if ($m -like 'ЕСТЬ*' -or $m -like 'ENV*') { 'Yellow' } else { 'DarkGray' }) }
if (-not ($machine | Where-Object { $_ -like 'ЕСТЬ*' -or $_ -like 'ENV*' })) { Out-Line '    Init-скриптов, прокси и агентов JVM в окружении нет.' 'Green' }

# ── Итог ─────────────────────────────────────────────────────────────────────
$violations = @($findings | Where-Object { $_.Level -eq 'VIOLATION' })
$newWarn    = @($findings | Where-Object { $_.Level -eq 'WARN' -and -not $_.Accepted })
$acceptedN  = @($findings | Where-Object { $_.Accepted }).Count
Out-Head 'Итог'
$infoN = @($findings | Where-Object { $_.Level -eq 'INFO' -and -not $_.Accepted -and $_.Kind -ne 'host-in-ignored' }).Count
$ignoredN = @($findings | Where-Object { $_.Kind -eq 'host-in-ignored' }).Count
Out-Line ("Нарушений: {0}   новых замечаний: {1}   осмотренных: {2}   к сведению: {3}{4}" -f $violations.Count, $newWarn.Count, $acceptedN, $infoN, $(if ($ignoredN) { "   ссылок в игнорируемых копиях: $ignoredN" } else { '' })) $(if ($violations.Count) { 'Red' } elseif ($newWarn.Count) { 'Yellow' } else { 'Green' })
if ($violations.Count) {
    Out-Line 'Нарушения (снимаются правкой кода или allowed-hosts.txt, не базой принятых):' 'Red'
    foreach ($v in ($violations | Sort-Object Kind, Path, Line)) { Out-Line ("    {0,-22} {1}{2}  {3}" -f $v.Kind, $v.Path, $(if ($v.Line) { ":$($v.Line)" } else { '' }), $v.Detail) 'Red' }
}
if ($newWarn.Count) {
    Out-Line 'Новые замечания (осмотреть; принятые — в accepted-findings.txt):' 'Yellow'
    foreach ($v in ($newWarn | Sort-Object Kind, Path, Line)) { Out-Line ("    {0,-22} {1}{2}  {3}" -f $v.Kind, $v.Path, $(if ($v.Line) { ":$($v.Line)" } else { '' }), $v.Detail) 'Yellow' }
}
Out-Line ("Протокол: $LogPath")
[IO.File]::WriteAllText($LogPath, $script:log.ToString(), (New-Object System.Text.UTF8Encoding($true)))

if ($violations.Count) { exit 2 } elseif ($newWarn.Count) { exit 1 } else { exit 0 }
