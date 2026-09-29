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
| PH-6 | preventive | «already registered», дамп не снимается | knowledge/phone.md | свой драйвер — да; чужой — спросить |
| PH-7 | preventive | «занят другим звонком», висит первый звонок | knowledge/phone.md | да — сброс перед «Входом» |
| PH-8 | preventive | запуск приложения | knowledge/phone.md | да |
| PH-9 | preventive | драйвер Maestro остался после прогона | knowledge/phone.md | да |
| PH-10 | preventive | звонок ушёл чужому контакту | knowledge/phone.md | да + `--peer-id` |
| PH-11 | preventive | клавиатура вместо выбора набора | knowledge/phone.md | да |
| PH-12 | preventive | нет «Стенд звонков» в переключателе | knowledge/phone.md | да |
| PH-13 | preventive | прокрутка не двигает список, телефон боком | knowledge/phone.md | да — размер по дереву |
| PH-14 | preventive | «Parsing Failed» сценария Maestro | knowledge/phone.md | да |
| PH-15 | preventive | переменная сценария Maestro | knowledge/phone.md | да |
| PH-16 | preventive | сценарий Maestro стартует на рабочем столе | knowledge/phone.md | да |
| PH-17 | curative | журнал приложения не растёт | knowledge/phone.md | нет — стоп и заказчику |
| PH-18 | preventive | «Вход» не достигнут, хотя соединились | knowledge/phone.md | да — ожидание до 20 с |
