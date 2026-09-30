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
