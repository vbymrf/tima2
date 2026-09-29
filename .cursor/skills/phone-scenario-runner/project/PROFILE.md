# Сценарии и элементы TIMA

Запуск — из `C:\!TIMA2`, Git Bash: `python -X utf8 maestro/phone/scenario.py …`.
Имена телефонов и адреса — `maestro/phone/phone.py`, `PHONES`. Пределы времени —
`scenario.py`, `LIMITS`.

## Сценарий: прогон стенда

```
Прогон стенда (серия; параметр — сколько звонков N; смены набора нет — стенд берёт следующий сам)
├─ Подготовка
│  ├─ ready     × каждый телефон
│  ├─ presets   × каждый телефон
│  └─ bench     × каждый телефон
├─ Совершить звонок × N           (call: enter → hold → exit; не достигнут — reset)
└─ Завершение: снять журналы и отчёты стенда, вернуть наборы заказчика, run.sh без KEEP_AWAKE
```

Пары: Samsung → Redmi (кладёт звонящий), Honor → realme (кладёт принимающий — PH-4).

## Элементы

### element: ready
приводит к: экран не спит, приложение впереди в главном окне, звонка нет, драйвера Maestro нет
проверка:   `dumpsys power` Awake; фокус TIMA; `tab:Chats` в дереве; `in_call` ложно
способы:    adb — `scenario.py element ready --dev <тел> --method adb`
            maestro — то же с `--method maestro` (`flows/ready.yaml`)
предел:     90 с
сброс:      не нужен — элемент сам и есть приведение
frozen:     —

### element: presets
приводит к: на телефоне ровно заданный файл наборов стенда
проверка:   sha256 на ПК = sha256 `files/test/presets.json` на телефоне
способы:    adb — `scenario.py element presets --dev <тел> --file <json>` (внутри `push-bench-presets.ps1`)
            maestro — не применим: файлы Maestro не кладёт
предел:     90 с
frozen:     —

### element: bench
приводит к: забег вооружён, выбран набор PRESET, на экране «Прогон N из M» = EXPECT, приложение снова в главном окне
проверка:   экран (журнал выбор набора не пишет): «Остановить прогон», EXPECT, потом `tab:Chats`
способы:    adb — `scenario.py element bench --dev <тел> --method adb --preset "<имя>" --expect "Прогон 1 из 16"`
            maestro — то же с `--method maestro` (`flows/bench.yaml`)
предел:     200 с
frozen:     —

### element: enter  (часть call)
приводит к: видеозвонок соединён у обоих
ветки:      принимающий — ждёт «Принять» и нажимает (запускается первым);
            звонящий — «Контакты» → строка PEER → «📹» той же строки
проверка:   журнал звонящего «звонок начат … видео=true», «кому=» = `--peer-id`;
            «стадия звонка стала=Connected» у обоих, ждать до 20 с
предел:     150 с
сброс:      reset пары

### element: hold  (часть call)
приводит к: разговор держится HOLD с; снимки обоих на середине
проверка:   у обоих «своя камера включена=true», «уходящее видео» есть; «прогон стенда начат пресет=» одинаков
предел:     HOLD + 60 с
сброс:      не нужен — неудача замера не авария, звонок кладётся штатно

### element: exit  (часть call)
приводит к: трубка положена, окна звонка закрыты у обоих, забег сдвинулся
проверка:   «звонок закончен»/«кончился» у обоих; «следующий набор забега номер=» одинаков у обоих
предел:     120 с
сброс:      reset пары

### element: reset
приводит к: звонков нет, окна звонка закрыты, драйверов Maestro нет, приложение впереди
проверка:   `in_call` ложно у всех телефонов элемента
предел:     120 с

Команда звонка:

```bash
python -X utf8 maestro/phone/scenario.py call --caller samsung --callee redmi \
    --peer "redmi, @test_redmi" --peer-id c0443dac --hang caller --method adb --hold 60
python -X utf8 maestro/phone/scenario.py series … --calls 16
```

Известные адресаты (`кому=` в журнале звонящего): Samsung → Redmi `c0443dac`;
realme → Honor `46496289`; Honor → realme — `69a94f52`.
