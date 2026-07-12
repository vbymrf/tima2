# Каталог REST API

> Обзорный каталог эндпоинтов по доменам. Детальные контракты (схемы запросов/ответов) генерируются из OpenAPI-спеки в репозитории бэкенда; этот документ — карта поверхности API. Realtime-события — [websocket-events.md](./websocket-events.md).

**Общее:** префикс `/api/v1` · JSON (бинарные поля base64url; конверты сообщений — Protobuf, `Content-Type: application/x-protobuf`) · авторизация `Bearer` (device JWT) · rate limiting per device · ошибки `{code, message, details}`.

## Auth и устройства

| Метод | Путь | Назначение |
|-------|------|-----------|
| POST | `/auth/sms/request` | Запрос SMS-кода (регистрация/вход) |
| POST | `/auth/sms/verify` | Проверка кода → временный токен |
| POST | `/auth/register` | Завершение регистрации: профиль + публичные ключи устройства; в ответе — 10 резервных кодов (один раз) |
| POST | `/auth/guest` | Временный аккаунт: без телефона/email, одно устройство, TTL 30 дней неактивности |
| POST | `/auth/upgrade` | Апгрейд временного аккаунта до полного (телефон + email + резервные коды) |
| POST | `/auth/login` | Вход существующего устройства (пароль/биометрия локально) |
| POST | `/auth/refresh` | Обновление JWT |
| POST | `/auth/recovery` | Трёхфакторное восстановление: SMS + email + резервный код |
| GET/DELETE | `/devices` · `/devices/{id}` | Список устройств / отзыв («выйти со всех») |
| POST | `/link/init` | Новое устройство: заявка + QR-нонс |
| POST | `/link/confirm` | Телефон-якорь подтверждает привязку |
| POST | `/attest/ios` · `/attest/android` | Верификация аттестации |

## Пользователи и соц. граф

| Метод | Путь | Назначение |
|-------|------|-----------|
| GET/PATCH | `/me` | Профиль, настройки |
| DELETE | `/me` | Удаление аккаунта (задержка 30 дней) + `/me/export` |
| GET | `/users/{id}` | Публичный профиль (профиль-попап) |
| GET/POST/DELETE | `/contacts` | Телефонная книга |
| GET/POST/PATCH | `/friends/requests` | Запросы в друзья |
| GET/POST/DELETE | `/subscriptions` | Подписки |
| GET/POST/DELETE | `/blocklist` | Чёрный список |

## Виртуальные пользователи

> Действие от имени ВП — параметр `acting_as={vu_id}` в любом запросе: сервер проверяет право (владелец/оператор) и пишет `vu_audit` (actor публично не раскрывается). Модель: [virtual-users.md](../01-product/virtual-users.md).

| Метод | Путь | Назначение |
|-------|------|-----------|
| GET/POST | `/virtual-users` | Мои ВП / создание (полный аттестованный аккаунт; лимит NFR) — клиент владельца генерирует ключи ВП |
| GET/PATCH/DELETE | `/virtual-users/{id}` | Профиль ВП / отзыв (контент не удаляется, новые действия запрещены) |
| GET/POST/DELETE | `/virtual-users/{id}/operators` | Операторы (реальные пользователи); смена состава → ротация ключей ВП |
| GET/PUT | `/virtual-users/{id}/keys?version=` | Wrapped-ключи ВП для устройств владельца/операторов; PUT — новая версия при ротации |
| POST | `/virtual-users/{id}/transfer` · `/transfer/accept` | Передача (продажа): двустороннее согласие, смена владельца, отзыв операторов, ротация ключей, запись в аудит |
| GET | `/virtual-users/{id}/audit?period=` | Аудит действий (только владелец) |

## Социальное взаимодействие (окно 4)

| Метод | Путь | Назначение |
|-------|------|-----------|
| GET | `/inbox/threads?identity=&status=&assignee=&priority=` | Карточки обращений (managed inbox; статус/ответственный — серверные, общие для команды) |
| PATCH | `/inbox/threads/{id}` | Взять/Отложить/Закрыть: `{status, assignee_id?, snoozed_until?}` |
| GET | `/inbox/events?cursor=` | Личные события (реакции, упоминания, назначения); read-state per пользователь |
| POST | `/inbox/events/read` · `/hide` | Пачкой: прочитано / скрыть |
| GET/PUT | `/inbox/preferences` | Правила агрегации (source + event_type → вкладка/скрыть/push/приоритет); синхронизируются между устройствами |

## Сообщения и ключи

| Метод | Путь | Назначение |
|-------|------|-----------|
| GET/POST | `/chats` | Список / создание чата 1:1 |
| PATCH | `/chats/{id}/settings` | Пер-пользовательские настройки: архив, закрепление чата |
| POST/DELETE | `/messages/{id}/pin` | Закрепить/открепить сообщение |
| POST | `/messages` | Отправка конверта (payload+escrow+wrapped keys, `client_msg_id` для дедупликации) |
| GET | `/chats/{id}/messages?before=&limit=` | История (конверты + wrapped keys для устройства) |
| POST | `/messages/{id}/receipt` | delivered / read / listened |
| DELETE | `/messages/{id}?scope=me\|all` | Удаление (all = soft delete) |
| GET | `/keys/devices?user_id=` | Публичные ключи устройств собеседника |
| GET/PUT | `/keys/prekeys` | PreKey bundles (фаза ratchet) |

## Сообщества

| Метод | Путь | Назначение |
|-------|------|-----------|
| POST | `/communities` | Создать сообщество (аудио-чат standalone создаёт его автоматически) |
| GET | `/communities/{id}` | Страница: инфо + элементы с учётом доступа (`preview` видны всем; `open` — состав виден, контент по подписке; `restricted` — по `restricted_visible`) |
| PATCH/DELETE | `/communities/{id}` | Настройки; удаление → элементы становятся standalone |
| POST/DELETE | `/communities/{id}/items` | Добавить/убрать группу/канал/аудио-чат `{group_id, community_access, restricted_visible}` |
| PATCH | `/communities/{id}/items/{group_id}` | Сменить уровень доступа элемента |
| GET/PUT/DELETE | `/communities/{id}/roles` | Роли owner/admin/moderator ([communities.md](../01-product/communities.md) §4) |
| POST/DELETE | `/communities/{id}/subscribe` | Подписка на сообщество (= `subscriptions target_type='community'`) |
| GET | `/communities/{id}/subscribers` | Счётчик/список подписчиков (по правам) |

> Сущности разделены по неймспейсам ([module-boundaries.md](../02-architecture/module-boundaries.md)); роли всех сущностей — единые эндпоинты `/{entity}/{id}/members` поверх подсистемы `membership`.

## Группы (переписка)

| Метод | Путь | Назначение |
|-------|------|-----------|
| POST | `/groups` | Создание: `{kind: private\|public, title, community_id?, community_access?, slow_mode_sec, premoderation, threads_only}` ([33-create-group-channel](../doc_UI/33-create-group-channel.md)) |
| GET/PATCH/DELETE | `/groups/{id}` | Инфо / настройки / удаление (owner) |
| GET/POST/DELETE | `/groups/{id}/members` | Участники; PUT `…/{uid}/role` (admin/moderator/member); POST `…/{uid}/ban` |
| POST | `/groups/{id}/messages` | Сообщение (private: SecretBox(GK); public: plaintext; премодерация → pending) |
| GET | `/groups/{id}/messages` · `?thread=` | История, ветки |
| POST | `/groups/{id}/keys` | Ротация GK: wrapped_GK[] + escrow_blob |
| GET | `/groups/{id}/keys?since_version=` | Пропущенные wrapped_GK для устройства |

## Каналы (публикации)

| Метод | Путь | Назначение |
|-------|------|-----------|
| POST | `/channels` | Создание: `{title, community_id?, community_access?, who_can_post, premoderation}` |
| GET/PATCH/DELETE | `/channels/{id}` | Инфо / настройки / удаление |
| GET/POST/DELETE | `/channels/{id}/members` | Роли: owner/admin/author (PUT `…/{uid}/role`) |
| GET | `/channels/{id}/posts?cursor=` | Лента постов канала (выдача из `publications`) |
| GET | `/channels/{id}/stats` | Статистика (по правам) |

> Контент канала создаётся через `/posts` с `author_type='channel'` — сообщений у канала нет.

## Аудио-чаты (live)

| Метод | Путь | Назначение |
|-------|------|-----------|
| POST | `/voice-rooms` | Создание: `{title, community_id \| auto_create_community, attached?: {type, id}, speak_policy}` — `community_id` обязателен |
| GET/PATCH/DELETE | `/voice-rooms/{id}` | Инфо / настройки / удаление |
| POST | `/voice-rooms/{id}/join` · `/leave` | Вход/выход (LiveKit-токен) |
| GET | `/voice-rooms/{id}/participants` | Кто в эфире (live, из LiveKit/Redis) |
| PUT | `/voice-rooms/{id}/members/{uid}/role` | speaker (при speak_policy='by_role') |

## Публикации (посты, редактор)

| Метод | Путь | Назначение |
|-------|------|-----------|
| POST | `/posts` | Пост: `{author_type, author_id, kind, title?, body, entities[], media_ids[], attribute_ids[], scheduled_at?}` ([34-content-editor](../doc_UI/34-content-editor.md)); при премодерации канала → status='pending' |
| GET/PATCH/DELETE | `/posts/{id}` | Чтение / правка (author/admin) / удаление |
| GET/PUT/DELETE | `/drafts` · `/drafts/{id}` | Черновики (автосохранение) |
| POST | `/posts/{id}/approve` · `/reject` | Премодерация (admin канала) |

## Атрибуты и жанры

| Метод | Путь | Назначение |
|-------|------|-----------|
| GET | `/attributes?q=` | Поиск/автодополнение по реестру (и для редактора, и для глобального поиска) |
| POST | `/attributes` | Создание автором при публикации: `{name, proposed_genre?}`; ответ может содержать `similar[]` («похожий уже есть») |
| GET | `/attributes/{id}` | Карточка: жанр, счётчики, описание |
| GET | `/attributes/{id}/posts?cursor=` | Approved-посты атрибута |
| POST/DELETE | `/attributes/{id}/follow` | «Добавить себе» / убрать (user_attributes) |
| GET | `/genres` · `/genres/{id}/attributes` | Список жанров; атрибуты жанра (по популярности) |

> Состав жанров правит только сервер (курирование + автоклассификация) — публичного API назначения жанра нет ([feed-ranking.md](../04-data/feed-ranking.md) §3).

## Общие подсистемы

| Метод | Путь | Назначение |
|-------|------|-----------|
| POST/GET | `/invites` · `/invites/{code}` | Инвайт-ссылки, QR — target: группа / канал / сообщество |
| GET | `/catalog?type=&folder=` | Каталог: сообщества + standalone группы/каналы |
| GET/POST/PATCH/DELETE | `/folders` · `/folders/{id}/items` | Папки — личная сортировка каталога ([communities.md](../01-product/communities.md) §6) |

## Медиа

| Метод | Путь | Назначение |
|-------|------|-----------|
| POST | `/media/init` | Presigned upload / CAS-дедуп (публичное) |
| POST | `/media/complete` | Фиксация метаданных |
| GET | `/media/{id}/url` | Presigned download (TTL 10 мин) |

## Ленты, посты, реакции

| Метод | Путь | Назначение |
|-------|------|-----------|
| GET | `/feed?tab=general\|friends&genre=&attribute=&cursor=` | Общая лента (скоринг + тематический срез) / лента друзей (полки, хронология) — [feed-ranking.md](../04-data/feed-ranking.md) |
| POST | `/recommend` | [+]/[−] `{target, value}` — только публичное |
| PUT/DELETE | `/emotions` | Эмоция из шкалы 9 `{target, emotion}` |
| GET | `/emotions?target=` | Счётчики эмоций под сообщением |
| GET | `/ratings?subject_type=&subject_id=` | Рейтинг «+/−» пользователя/группы (раздельные счётчики) |
| GET/POST | `/comments?target=` | Комментарии |
| GET/POST/DELETE | `/favorites?shelf=public` | Публичная полка избранного (питает ленту друзей) |
| GET/PUT | `/shelf/private` | Личная полка: зашифрованный blob (SecretBox(shelf_key)) |
| POST | `/shelf/access/request` · `/grant` · `/revoke` | Доступ к личной полке по запросу; grant = wrapped shelf_key на устройства друга; revoke = ротация ключа |
| POST | `/share` | Пересылка/репост `{source, dest}` |
| GET | `/activity?tab=` | Окно 4: отслеживаемое / реакции |

## Истории и коллекции

| Метод | Путь | Назначение |
|-------|------|-----------|
| GET/POST/DELETE | `/stories` | Истории (TTL 24ч), лента историй |
| GET/POST/PATCH | `/collections` | Коллекции, уровень приватности |
| POST/DELETE | `/collections/{id}/items` | Наполнение |
| POST | `/collections/{id}/members` | Совместные коллекции |

## Звонки

| Метод | Путь | Назначение |
|-------|------|-----------|
| POST | `/calls` | Инициировать: создание LiveKit-room + токены |
| POST | `/calls/{id}/answer` · `/decline` · `/end` | Управление |
| GET | `/calls/history` | История звонков |

## Поиск, уведомления, прочее

| Метод | Путь | Назначение |
|-------|------|-----------|
| GET | `/search?q=&type=` | Серверный поиск (только публичное, [ADR-0007](../adr/0007-search-split.md)) |
| GET/PUT | `/notifications/settings` | 3 уровня настроек, тихие часы |
| POST | `/push/register` | FCM/APNs токен |
| POST | `/reports` | Жалоба |
| GET | `/stats?virtual_user=&period=` | Статистика блогера / ВП |
| POST | `/bugs` | Баг-репорт из приложения |
