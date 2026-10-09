# Индекс правил

Единственное, что исполнитель читает до запуска. `preventive` — открыть и выполнить до
запуска; `curative` — открыть, только если симптом совпал. `вшито` — правило уже внутри
`scenario.py` / `phone.py` / `flows`, выполнять отдельно не нужно.

Ведёт журналист.

| code | kind | симптом | где | вшито |
|---|---|---|---|---|
| PH-1 | preventive | звонок без видео, «видео=false» | knowledge/phone.md | да — «Вход» через «Контакты» |
| PH-2 | preventive | трубка не положена, звонок длится | knowledge/phone.md | да |
| PH-3 | preventive | «доставлен», а звонка на телефоне нет | knowledge/phone.md | да — `am start` после «Назад» |
| PH-4 | preventive | «could not get idle state» на Honor | knowledge/phone.md | роли в паре — в вызове |
| PH-5 | curative | «Видеозвонок» в меню не нажимается | knowledge/phone.md | да — нулевые рамки не нажимаются |
| PH-6 | preventive | «already registered», дамп не снимается | knowledge/phone.md | да — драйвер выгружается перед деревом экрана |
| PH-7 | preventive | «занят другим звонком», висит первый звонок | knowledge/phone.md | да — сброс перед «Входом» |
| PH-8 | preventive | запуск приложения | knowledge/phone.md | да |
| PH-9 | preventive | драйвер Maestro остался — не выгружать между шагами Maestro | knowledge/phone.md | да |
| PH-10 | preventive | звонок ушёл чужому контакту | knowledge/phone.md | да + `--peer-id` |
| PH-11 | preventive | клавиатура вместо выбора набора | knowledge/phone.md | да |
| PH-12 | preventive | нет «Стенд звонков» в переключателе | knowledge/phone.md | да |
| PH-13 | preventive | прокрутка не двигает список, телефон боком | knowledge/phone.md | да — размер по дереву |
| PH-14 | preventive | «Parsing Failed» сценария Maestro | knowledge/phone.md | да |
| PH-15 | preventive | переменная сценария Maestro | knowledge/phone.md | да |
| PH-16 | preventive | сценарий Maestro стартует на рабочем столе | knowledge/phone.md | да |
| PH-17 | preventive | журнал не растёт — пишется пачками | knowledge/phone.md | да — `journal(fresh=True)` |
| PH-18 | preventive | «Вход» не достигнут, хотя соединились | knowledge/phone.md | да |
| PH-19 | preventive | звонок ушёл чужому (Maestro `rightOf`) | knowledge/phone.md | да — поиск + страховка «кому=» |
| PH-20 | preventive | прокрутка печатает буквы | knowledge/phone.md | да |
| PH-21 | preventive | на снимке шторка уведомлений | knowledge/phone.md | да |
| PH-22 | preventive | у принимающего «нет» строк, которые есть | knowledge/phone.md | да — отсечка по часам каждого |
| PH-23 | preventive | нет плашки «Телефон» / кнопки прогона | knowledge/phone.md | да |
| PH-24 | preventive | Maestro «Принять» не дождался | knowledge/phone.md | да |
| PH-25 | preventive | «конец НЕТ» у принимающего, а звонок кончился | knowledge/phone.md | да — сброс с подтверждением |
| PH-26 | preventive | сторож на паре по Wi-Fi, adb отвечает секундами | knowledge/phone.md | нет — серии по очереди |
| PH-27 | preventive | нет плашки «Телефон» после серии | knowledge/phone.md | да |
| PH-28 | preventive | экран гаснет посреди видеозвонка, «Выход» — сторож | knowledge/phone.md | нет — `--hold` короче тайм-аута экрана |
| PH-29 | preventive | забег не вооружён после «bench» | knowledge/phone.md | да — нажимается кнопка, а не заголовок |
| PH-30 | preventive | свернул посреди видео — видео пропало | knowledge/phone.md | нет — не сворачивать Redmi в замерах дольше 4 с |
| PH-31 | diagnostic | отдельный `element exit` — «исключение: 'since'», звонок идёт | knowledge/phone.md | нет — элементы звонка гонять через `call` |
| PH-32 | diagnostic | окно звонка: `call_screen` «нет окна», `hang_up` False — сменился вид кнопок | knowledge/phone.md | да — сценарии на метках (ПЛАН-(МС)) |
| PH-33 | preventive | порты в `phone.py` устарели | knowledge/phone.md | нет — подставить текущие на прогон |
| PH-34 | preventive | `-v2-fast` на телефоне — `run-as` нет | knowledge/phone.md | нет — сценарии только на отладочной |
| PH-35 | diagnostic | exit «наборы или номера разные» у пары без стенда | knowledge/phone.md | нет — механику смотреть по «соединён/конец» |
| PH-36 | preventive | сценарий Maestro на метках ждёт по 8 с на шаге | knowledge/phone.md | да — флажок ветки метки |
| PH-37 | preventive | «Unknown Property: timeout» у `assertVisible` | knowledge/phone.md | да |
| PH-38 | preventive | сценарий не находит главное окно — телефон оставлен в другом | knowledge/phone.md | нет — `ready` adb перед каждым сценарием |
| PH-39 | preventive | `ready` Maestro «нет» — он только проверяет | knowledge/phone.md | нет — `ready` adb |
| PH-40 | preventive | `send-burst` зелёный, сообщений нет, приложение в фоне | knowledge/phone.md | да — метки `chat:input`, `chat:send` (сборки после 163) |
| PH-41 | diagnostic | после `inputText` кириллицей поле пустое | knowledge/phone.md | да — `send-burst` вводит латиницу |
| PH-42 | preventive | `rightOf` нажал аватар вместо кнопки отправки | knowledge/phone.md | да — `chat:send` |
| PH-43 | preventive | проверка Maestro стоит 7–8 с — ответ «нет» дорог | knowledge/phone.md | да — пара меток, флажки |
| PH-44 | diagnostic | касание 3–4 с; ожидание «после касания» не срезается | knowledge/phone.md | нет — выигрыш в ожиданиях исхода |
| PH-45 | preventive | `to_home` — «переход из screen:chat не помог», открыта клавиатура | knowledge/phone.md | да |
| PH-46 | preventive | `bench` откатил забег на набор №1 | knowledge/phone.md | нет — смотреть журнал прошлых дней |
| PH-47 | diagnostic | `send-burst`: сообщение ушло, а на экране его нет | knowledge/phone.md | да — прокрутка вниз; в приложении — прокрутка к новому (`ChatScreen`, после 164) |
| PH-48 | preventive | замер прокрутки: `gfxinfo` видит 2–7 кадров на росчерк | knowledge/phone.md | нет — SurfaceFlinger `--latency` |
