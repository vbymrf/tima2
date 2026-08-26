<#
.SYNOPSIS
    Проверка серверных тестов Go со службами: сама поднимает, сама считает, сама судит.

.DESCRIPTION
    Зачем скрипт, а не список команд. Прогон `go test` без служб выходит с нулём и
    печатает `ok`, пропустив 72 теста пакета api из 120: отсутствие окружения выглядит как
    пройденная проверка. Увидеть это можно, только считая строки, а строки руками
    никто не считает. Здесь считает скрипт и говорит словами.

    По шагам:
      1. Находит корень репозитория от собственного расположения.
      2. Проверяет инструменты: go, docker.
      3. Показывает ИНВЕНТАРЬ докера — какие контейнеры TIMA живы, включая старые.
         Чужие останавливает только по -Clean и никогда не трогает тома.
      4. Поднимает два compose-проекта: tima-test (Postgres на 55432) и tima-dev
         (Postgres, Redis, MinIO).
      5. Ждёт, пока службы ответят, а не пока докер скажет «создан».
      6. Прогоняет go build, go vet, gofmt и полный go test -v.
      7. Считает RUN / PASS / FAIL / SKIP и выносит вердикт.
      8. Пишет отчёт в doc_add\otchet-proverki-go.txt — его и присылать.

.PARAMETER Clean
    Остановить чужие контейнеры TIMA (проект `tima` — боевой compose) перед запуском.
    Меняет состояние машины, поэтому только явным ключом. Тома не трогает: данные
    остаются, останавливаются процессы.

.PARAMETER NoDocker
    Службы подняты иначе (портативный способ, server/deploy/СТЕК-ДЛЯ-ТЕСТОВ.md §2).
    Скрипт тогда ничего не поднимает, а только проверяет доступность и прогоняет.

.PARAMETER Down
    Погасить оба проекта после прогона. По умолчанию стек остаётся поднятым: гасить
    чужую машину без спросу — не дело скрипта.

.EXAMPLE
    .\check-server.ps1
.EXAMPLE
    .\check-server.ps1 -Clean
#>
[CmdletBinding()]
param(
    [switch]$Clean,
    [switch]$NoDocker,
    [switch]$Down
)

# Continue, а не Stop: скрипт обязан дойти до вердикта и сказать, что именно не так.
# Обрыв на середине оставил бы человека без отчёта — то есть без того, ради чего
# скрипт и запускался.
$ErrorActionPreference = 'Continue'

# ── Корень репозитория от собственного расположения ─────────────────────────────
#
# Не из текущего каталога и не из зашитого пути: скрипт обязан работать одинаково,
# откуда бы его ни позвали и где бы репозиторий ни лежал.
$root = $PSScriptRoot
$serverDir = Join-Path $root 'server'
$deployDir = Join-Path $serverDir 'deploy'
$devFile = Join-Path $deployDir 'docker-compose.dev.yml'
$testFile = Join-Path $deployDir 'docker-compose.test.yml'

$reportDir = Join-Path $root 'doc_add'
$reportFile = Join-Path $reportDir 'otchet-proverki-go.txt'
$logFile = Join-Path $reportDir 'go-test-latin.txt'

$lines = New-Object System.Collections.Generic.List[string]
$troubles = New-Object System.Collections.Generic.List[string]

function Say($text) {
    Write-Host $text
    $lines.Add($text) | Out-Null
}
function Head($text) {
    Say ''
    Say ("== " + $text + " " + ('=' * [Math]::Max(1, 74 - $text.Length)))
}
function Good($text) {
    Write-Host ("  OK   " + $text) -ForegroundColor Green
    $lines.Add("  OK   " + $text) | Out-Null
}
function Bad($text) {
    Write-Host ("  БЕДА " + $text) -ForegroundColor Red
    $lines.Add("  БЕДА " + $text) | Out-Null
    $troubles.Add($text) | Out-Null
}
function Note($text) {
    Write-Host ("  --   " + $text) -ForegroundColor DarkGray
    $lines.Add("  --   " + $text) | Out-Null
}

function Write-Report {
    if (-not (Test-Path $reportDir)) {
        New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
    }
    Set-Content -Path $reportFile -Value $lines -Encoding UTF8
    Write-Host ''
    Write-Host ("Отчёт: " + $reportFile) -ForegroundColor Cyan
    Write-Host 'Его и присылать — он самодостаточен.' -ForegroundColor Cyan
}

# ── Шаг 1. Где мы и что проверяем ───────────────────────────────────────────────

Head 'Что проверяем'

if (-not (Test-Path $serverDir)) {
    Bad "Не найден $serverDir — скрипт лежит не в корне репозитория."
    Write-Report
    exit 2
}

Say ("Корень:  " + $root)
Say ("Дата:    " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))

$git = Get-Command git -ErrorAction SilentlyContinue
if ($git) {
    $headLine = (& git -C $root log --oneline -1) -join ''
    $branch = (& git -C $root rev-parse --abbrev-ref HEAD) -join ''
    $dirty = (& git -C $root status --porcelain) -join "`n"
    Say ("Ветка:   " + $branch)
    Say ("Коммит:  " + $headLine)
    if ($dirty) {
        Note 'Рабочее дерево НЕ чисто — отчёт относится не к коммиту, а к тому, что на диске.'
    }
} else {
    Bad 'git не найден — вершину в отчёт вписать нечем, а отчёт без хеша ни о чём.'
}

# ── Шаг 2. Инструменты ──────────────────────────────────────────────────────────

Head 'Инструменты'

$go = Get-Command go -ErrorAction SilentlyContinue
if (-not $go) {
    Bad 'go не найден в PATH. Проверять нечем.'
    Write-Report
    exit 2
}
Good ("go " + ((& go env GOVERSION) -join '') + "  (" + $go.Source + ")")

if (-not $NoDocker) {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) {
        Bad 'docker не найден в PATH. Либо поставьте Docker Desktop, либо поднимите службы иначе и запустите с -NoDocker (СТЕК-ДЛЯ-ТЕСТОВ.md, «Способ 2»).'
        Write-Report
        exit 2
    }
    & docker info 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Bad 'Docker установлен, но движок не отвечает. Запустите Docker Desktop и дождитесь состояния «running».'
        Write-Report
        exit 2
    }
    Good ("docker " + ((& docker version --format '{{.Server.Version}}') -join ''))
} else {
    Note 'Режим -NoDocker: службы поднимает не скрипт, проверяется только доступность.'
}

# ── Шаг 3. Инвентарь: что от TIMA уже висит ─────────────────────────────────────
#
# Ради этого шага скрипт во многом и написан. У боевого стека (проект `tima`)
# контейнеры стоят с restart: unless-stopped и поднимаются сами вместе с Docker
# Desktop. Они держат 8080 и едят память, а по симптому «тесты странные» этого
# не увидеть.

if (-not $NoDocker) {
    Head 'Инвентарь докера: контейнеры TIMA'

    $ours = @('tima-dev', 'tima-test')
    $fmt = '{{.Label "com.docker.compose.project"}}|{{.Names}}|{{.Image}}|{{.Status}}'
    $psLines = & docker ps -a --format $fmt
    $foreign = New-Object System.Collections.Generic.List[string]
    $anything = $false

    foreach ($line in $psLines) {
        if (-not $line) { continue }
        $parts = $line.Split('|')
        $project = $parts[0]
        $name = $parts[1]
        $image = $parts[2]
        $state = $parts[3]
        if (($project -notmatch 'tima') -and ($name -notmatch 'tima') -and ($image -notmatch 'tima')) { continue }
        $anything = $true
        $mark = '      '
        if ($ours -notcontains $project) {
            $mark = 'СТАРОЕ'
            if ($state -match '^Up') { $foreign.Add($name) | Out-Null }
        }
        Say ("  " + $mark + " [" + $project + "] " + $name + "  " + $image + "  " + $state)
    }

    if (-not $anything) { Note 'Контейнеров TIMA нет вовсе — чистая машина.' }

    if ($foreign.Count -gt 0) {
        Say ''
        Say ("Живых чужих контейнеров TIMA: " + $foreign.Count + " — это и есть «старые версии сервера».")
        Say 'Они держат порты и память и поднимаются сами после перезапуска Docker Desktop.'
        if ($Clean) {
            Say 'Ключ -Clean задан: останавливаю. Тома не трогаю, данные остаются.'
            foreach ($name in $foreign) {
                & docker stop $name | Out-Null
                if ($LASTEXITCODE -eq 0) { Good ("остановлен " + $name) }
                else { Bad ("не удалось остановить " + $name) }
            }
        } else {
            Bad 'Прогон не начат: старый стек мешает. Перезапустите с -Clean — он остановит их, не удаляя данных.'
            Write-Report
            exit 3
        }
    }
}

# ── Шаг 4. Поднять службы ───────────────────────────────────────────────────────

if (-not $NoDocker) {
    Head 'Службы'

    function Compose-Up($file, $project) {
        if (-not (Test-Path $file)) {
            Bad ("Нет файла " + $file)
            return
        }
        & docker compose -f $file up -d 2>&1 | ForEach-Object { Note $_ }
        if ($LASTEXITCODE -eq 0) { Good ($project + " поднят") }
        else { Bad ($project + " не поднялся — смотрите вывод выше") }
    }

    # Тестовый первым: у него отдельный экземпляр Postgres на 55432, и именно он
    # нужен всем 70 тестам internal/api. Dev-стек добавляет Redis и MinIO.
    Compose-Up $testFile 'tima-test'
    Compose-Up $devFile 'tima-dev'
}

# ── Шаг 5. Дождаться, пока службы ОТВЕТЯТ ───────────────────────────────────────
#
# «Контейнер создан» и «служба отвечает» — разные события, у Postgres между ними
# десятки секунд. Прогон, начатый в этом промежутке, уходит в Skipf, а Skipf
# читается как успех.

Head 'Готовность служб'

function Wait-For($name, $probe, $seconds = 90) {
    $until = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $until) {
        if (& $probe) {
            Good ($name + " отвечает")
            return $true
        }
        Start-Sleep -Seconds 3
    }
    Bad ($name + " не ответил за " + $seconds + " с")
    return $false
}

function Test-Port($hostName, $port) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $async = $client.BeginConnect($hostName, $port, $null, $null)
        $inTime = $async.AsyncWaitHandle.WaitOne(1500, $false)
        if ($inTime -and $client.Connected) { $client.EndConnect($async); $client.Close(); return $true }
        $client.Close()
        return $false
    } catch { return $false }
}

$testDbUrl = 'postgres://tima:tima-test-only@localhost:55432/tima_test'
$redisUrl = 'redis://:tima-dev-only@localhost:6379'
$s3Url = 'http://localhost:9000'

$probes = @(
    @{ name = 'PostgreSQL тестовый (55432)'; probe = { Test-Port '127.0.0.1' 55432 } },
    @{ name = 'Redis (6379)'; probe = { Test-Port '127.0.0.1' 6379 } },
    @{ name = 'MinIO (9000)'; probe = {
            try {
                $answer = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri 'http://127.0.0.1:9000/minio/health/live'
                return ($answer.StatusCode -eq 200)
            } catch { return $false }
        }
    }
)

$ready = $true
foreach ($p in $probes) {
    # Первую службу ждём полторы минуты — Postgres после создания контейнера
    # столько и поднимается. Но если она не пришла, остальные почти наверняка
    # тоже не поднимались, и держать человека ещё три минуты незачем.
    $wait = 90
    if (-not $ready) { $wait = 10 }
    $ready = (Wait-For $p.name $p.probe $wait) -and $ready
}

if (-not $ready) {
    Bad 'Не все службы отвечают. Прогон был бы пустым отчётом, поэтому он не начат.'
    Write-Report
    exit 4
}

# ── Шаг 6. Проверки, которым службы не нужны ────────────────────────────────────

Head 'Сборка, vet, формат'

Push-Location $serverDir

& go build ./... 2>&1 | ForEach-Object { Note $_ }
if ($LASTEXITCODE -eq 0) { Good 'go build ./...' } else { Bad 'go build ./... — не собирается' }

& go vet ./... 2>&1 | ForEach-Object { Note $_ }
if ($LASTEXITCODE -eq 0) { Good 'go vet ./...' } else { Bad 'go vet ./... — есть замечания' }

$unformatted = & gofmt -l ./internal ./cmd
if ($unformatted) {
    foreach ($f in $unformatted) { Note $f }
    Bad 'gofmt -l — перечисленные файлы не отформатированы'
} else {
    Good 'gofmt -l ./internal ./cmd — пусто'
}

# ── Шаг 7. Полный прогон ────────────────────────────────────────────────────────

Head 'Тесты со службами'

# Процессные, а не машинные: правка машинных настроек ради прогона — это след,
# который переживёт прогон и однажды объяснит чужую поломку.
$env:TIMA_TEST_DATABASE_URL = $testDbUrl
$env:TIMA_TEST_REDIS_URL = $redisUrl
$env:TIMA_TEST_S3_ENDPOINT = $s3Url

Say ("TIMA_TEST_DATABASE_URL = " + $testDbUrl)
Say ("TIMA_TEST_REDIS_URL    = " + $redisUrl)
Say ("TIMA_TEST_S3_ENDPOINT  = " + $s3Url)
Say ''

if (-not (Test-Path $reportDir)) {
    New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
}

# -count=1 — иначе вместо настоящего прогона вернётся кэш прошлого.
& go test -count=1 -v ./... 2>&1 | Tee-Object -FilePath $logFile | ForEach-Object {
    if ($_ -match '^--- FAIL|^--- SKIP|^FAIL|^ok ') { Write-Host $_ }
}
$testExit = $LASTEXITCODE

Pop-Location

# ── Шаг 8. Счёт и вердикт ───────────────────────────────────────────────────────
#
# Считаем строки верхнего уровня: подтесты Go печатает с отступом, и без якоря «^»
# они удвоили бы счёт.

Head 'Счёт'

function Count-Lines($pattern) {
    return @(Select-String -Path $logFile -Pattern $pattern).Count
}

$run = Count-Lines '^=== RUN   Test'
$pass = Count-Lines '^--- PASS'
$fail = Count-Lines '^--- FAIL'
$skip = Count-Lines '^--- SKIP'

Say ("RUN  = " + $run)
Say ("PASS = " + $pass)
Say ("FAIL = " + $fail)
Say ("SKIP = " + $skip + "   (обязан быть 0)")
Say ("код возврата go test = " + $testExit)
Say ("журнал: " + $logFile)

Head 'Вердикт'

if ($skip -gt 0) {
    Bad ("Пропущено тестов: " + $skip + ". Служба не поднялась, прогон НЕДЕЙСТВИТЕЛЕН.")
    Say 'Строка t.Skipf сама называет, чего не хватило — ищите её в журнале.'
    Say 'Это не «почти прошло»: пропуск читается как успех, в этом вся ловушка.'
}
if ($fail -gt 0) {
    Bad ("Упало тестов: " + $fail + ". Имена — в журнале по строкам «--- FAIL».")

    # Одна причина на все падения — это отказ ПОДГОТОВКИ, а не поведения.
    #
    # t.Fatal в общем setup валит каждый тест пакета одним и тем же сообщением.
    # Семьдесят одно падение читается как «изменение сломало всё», хотя ни один
    # сценарий не начался. Это зеркало ловушки с SKIP: там пустота выглядит
    # успехом, здесь — катастрофой, и оба раза счёт врёт о том, что произошло.
    $reasons = @(Select-String -Path $logFile -Pattern '^\s+[\w-]+\.go:\d+: (.+)$' |
        ForEach-Object { $_.Matches[0].Groups[1].Value.Trim() } |
        Group-Object | Sort-Object Count -Descending)
    $порог = [Math]::Max(3, [int][Math]::Ceiling($fail / 2))
    if ($reasons.Count -gt 0 -and $reasons[0].Count -ge $порог) {
        Say ''
        Say ('Все падения об одном — ' + $reasons[0].Count + ' раз:')
        Say ('    ' + $reasons[0].Name)
        Say ''
        Say 'Это отказ подготовки: сценарии тестов не выполнялись вовсе.'
        if ($reasons[0].Name -match 'ResetForTests') {
            Say 'Чаще всего это СТАРЫЙ ТОМ Postgres — в базе лежат таблицы от прежней схемы.'
            Say 'Лечится пересозданием тестового тома:'
            Say '    docker compose -p tima-test -f server/deploy/docker-compose.test.yml down -v'
            Say 'Тестовый том данных не хранит: он для того и отдельный.'
        }
    }
}
if ($testExit -ne 0 -and $fail -eq 0 -and $skip -eq 0) {
    Bad 'go test вернул ненулевой код, но упавших тестов не видно — смотрите журнал целиком.'
}
if ($run -lt 100) {
    Note ("Выполнено " + $run + " тестов. Ожидание тира server-full — 120 (счёт: grep -rc '^func Test' server). Меньше значит, что часть не дошла до запуска.")
}

if ($troubles.Count -eq 0) {
    Say ''
    Say 'ПРИНЯТО. Все проверки зелёные, пропусков нет.'
} else {
    Say ''
    Say ("ЧИНИТЬ. Замечаний: " + $troubles.Count)
    foreach ($t in $troubles) { Say ("  - " + $t) }
}

# ── Шаг 9. Погасить, если просили ───────────────────────────────────────────────

if ($Down -and -not $NoDocker) {
    Head 'Гашу стек'
    # Без -v: тома с данными остаются. Удалять чужие данные скрипт не вправе.
    & docker compose -f $devFile down 2>&1 | Out-Null
    & docker compose -f $testFile down 2>&1 | Out-Null
    Good 'оба проекта погашены, тома целы'
}

Write-Report

if ($troubles.Count -eq 0) { exit 0 } else { exit 1 }
