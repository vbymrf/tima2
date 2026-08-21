# Каталог REST API

> Обзорный каталог эндпоинтов по доменам. Детальные контракты (схемы запросов/ответов) генерируются из OpenAPI-спеки в репозитории бэкенда; этот документ — карта поверхности API. Realtime-события — [websocket-events.md](./websocket-events.md).

**Общее:** префикс `/api/v1` · JSON (бинарные поля base64url; конверты сообщений — Protobuf, `Content-Type: application/x-protobuf`) · авторизация `Bearer` (device JWT) · rate limiting per device · ошибки `{code, message, details}`.
**Schema-first:** машинная истина — `schema/` (OpenAPI + Protobuf), этот документ — карта ([ADR-0009](../adr/0009-schema-first-api.md)). Публичный **Bot API** — отдельный контур: [bot-api.md](./bot-api.md).

## Auth и устройства

| Метод | Путь | Назначение |
|-------|------|-----------|
| POST | `/auth/sms/request` | ✅ Запрос SMS-кода (регистрация/вход) |
| POST | `/auth/sms/verify` | ✅ Проверка кода → временный токен |
| POST | `/auth/register` | ✅ Завершение регистрации: профиль + публичные ключи устройства; в ответе — 10 резервных кодов (один раз) |
| POST | `/auth/guest` | Временный аккаунт: без телефона/email, одно устройство, TTL 30 дней неактивности |
| POST | `/auth/upgrade` | Апгрейд временного аккаунта до полного (телефон + email + резервные коды) |
| POST | `/auth/login` | Вход существующего устройства (пароль/биометрия локально) |
| POST | `/auth/refresh` | Обновление JWT |
| POST | `/auth/recovery` | Трёхфакторное восстановление: SMS + email + резервный код |
| GET | `/devices` | ✅ Свои активные устройства (device_id, имя, дата, признак текущего) |
| DELETE | `/devices/{deviceID}` | ✅ Отзыв устройства. Последнее отозвать нельзя — 409 `last_device` |
| PUT | `/devices/me/platform` | ✅ Устройство объявляет платформу (`android`/`ios`/`desktop`) — от неё зависит право подтверждать привязку. Клиент вызывает при запуске |
| POST | `/link/start` | ✅ **Без авторизации.** Новое устройство: публичные ключи + имя → `session_id`, `qr_payload`, `claim_token`, `expires_at` |
| POST | `/link/confirm` | ✅ Телефон-якорь подтверждает привязку: подпись данных из QR своим ключом. Только `platform IN ('android','ios')`, иначе 403 `not_a_phone`. В ответе — `device_id` нового устройства |
| POST | `/link/claim` | ✅ **Без авторизации.** Новое устройство меняет `claim_token` на сессию. До подтверждения — 403 `not_ready` (сигнал продолжать опрос, не ошибка) |
| POST | `/attest/ios` · `/attest/android` | Верификация аттестации |

> ✅ — маршрут есть в коде. Пометки сверены целиком 2026-08-21 по
> `mux.HandleFunc` в `server/internal/api/*.go`; до сверки их несли шесть строк из
> двадцати шести реализованных. Где реализована **часть** склеенной строки, это
> сказано в самой строке словами — молчаливое ✅ на «`join` · `/leave`» означало бы,
> что есть оба.
>
> Строки без пометки — проектные, кода за ними может не быть. **Обратное молчание
> опаснее и оговоркой не покрывается:** кода без строки в каталоге оказалось 45
> маршрутов из 71. Полный список, извлечённый из кода, — в приложении в конце
> документа.
>
> Раньше здесь значился `POST /link/init` — такой ручки не существует, привязка
> устроена в три шага (`start` → `confirm` → `claim`), потому что новое устройство
> не имеет аккаунта и не может ни авторизоваться, ни получить push: оно опрашивает
> `claim`, пока телефон не подтвердит. Протокол и нормативные байты подписи —
> [key-lifecycle.md §2](../03-security/key-lifecycle.md),
> [schema/proto/README.md](../../schema/proto/README.md).

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
| POST | `/appeals` | Пользователь пишет сущности `{target_type, target_id, text}`. К **ВП** — E2E-чат (боты недоступны); к **группе/каналу/сообществу** — публичный plaintext-тред с пометкой в UI (отвечают операторы или бот через [bot-api](./bot-api.md) `answerAppeal`) |
| POST | `/inbox/notify` | Сообщение-карточка от имени сущности её аудитории (owner/admin, MVP-путь без ботов); те же лимиты и `block_messages`, что `notifyUser` |

## Сообщения и ключи

| Метод | Путь | Назначение |
|-------|------|-----------|
| GET/POST | `/chats` | Список / создание чата 1:1 |
| PATCH | `/chats/{id}/settings` | Пер-пользовательские настройки чата/сущности: архив, закрепление, `block_messages` (запрет карточек от сущности; уведомления — отдельно в `/notifications/settings`) |
| POST/DELETE | `/messages/{id}/pin` | Закрепить/открепить сообщение |
| POST | `/messages` | ✅ Отправка конверта (payload+escrow+wrapped keys, `client_msg_id` для дедупликации) |
| GET | `/chats/{id}/messages?before=&limit=` | ✅ История (конверты + wrapped keys для устройства) |
| POST | `/messages/{id}/receipt` | delivered / read / listened |
| DELETE | `/messages/{id}?scope=me\|all` | Удаление (all = soft delete) |
| GET | `/keys/devices?user_id=` | ✅ Публичные ключи устройств собеседника; для ВП возвращает его identity-ключ (виртуальное «устройство» = vu_id) |
| GET/PUT | `/keys/prekeys` | PreKey bundles (фаза ratchet) |

## Сообщества

| Метод | Путь | Назначение |
|-------|------|-----------|
| POST | `/communities` | Создать сообщество (аудио-чат standalone создаёт его автоматически) |
| GET | `/communities/{id}` | Страница: инфо + элементы с учётом доступа (`preview` видны всем; `open` — состав виден, контент по подписке; `restricted` — по `restricted_visible`) |
| PATCH/DELETE | `/communities/{id}` | Настройки; удаление → элементы становятся standalone |
| POST/DELETE | `/communities/{id}/items` | Добавить/убрать элемент `{target_type: group\|channel\|voice_room, target_id, community_access, restricted_visible}` |
| PATCH | `/communities/{id}/items/{target_id}` | Сменить уровень доступа элемента |
| GET/PUT/DELETE | `/communities/{id}/roles` | Роли owner/admin/moderator ([communities.md](../01-product/communities.md) §4) |
| POST/DELETE | `/communities/{id}/subscribe` | Подписка на сообщество (= `subscriptions target_type='community'`) |
| GET | `/communities/{id}/subscribers` | Счётчик/список подписчиков (по правам) |

> Сущности разделены по неймспейсам ([module-boundaries.md](../02-architecture/module-boundaries.md)); роли всех сущностей — единые эндпоинты `/{entity}/{id}/members` поверх подсистемы `membership`.

## Группы (переписка)

| Метод | Путь | Назначение |
|-------|------|-----------|
| POST | `/groups` | ✅ Создание: `{kind: private\|public, title, community_id?, community_access?, slow_mode_sec, premoderation, threads_only}` ([33-create-group-channel](../doc_UI/33-create-group-channel.md)) |
| GET/PATCH/DELETE | `/groups/{id}` | Инфо / настройки / удаление (owner) |
| GET/POST/DELETE | `/groups/{id}/members` | Участники; PUT `…/{uid}/role` (admin/moderator/member); POST `…/{uid}/ban` |
| POST | `/groups/{id}/messages` | ✅ Сообщение (private: SecretBox(GK); public: plaintext; премодерация → pending) |
| GET | `/groups/{id}/messages` · `?thread=` | ✅ История, ветки. Фильтр `?thread=` есть (`listGroupMessages`), как и `?before=` и `?limit=` |
| POST | `/groups/{id}/keys` | ✅ Ротация GK: wrapped_GK[] + escrow_blob |
| GET | `/groups/{id}/keys?since_version=` | ✅ Пропущенные wrapped_GK для устройства |

## Каналы (публикации)

| Метод | Путь | Назначение |
|-------|------|-----------|
| POST | `/channels` | ✅ Создание: `{title, community_id?, community_access?, who_can_post, premoderation}` |
| GET/PATCH/DELETE | `/channels/{id}` | Инфо / настройки / удаление |
| GET/POST/DELETE | `/channels/{id}/members` | Роли: owner/admin/author (PUT `…/{uid}/role`) |
| GET | `/channels/{id}/posts?cursor=` | ✅ Лента постов канала (выдача из `publications`) |
| GET | `/channels/{id}/stats` | Статистика (по правам) |

> Контент канала создаётся через `/posts` с `author_type='channel'` — сообщений у канала нет.

## Аудио-чаты (live)

| Метод | Путь | Назначение |
|-------|------|-----------|
| POST | `/voice-rooms` | ✅ Создание: `{title, community_id \| auto_create_community, attached?: {type, id}, speak_policy}` — `community_id` обязателен |
| GET/PATCH/DELETE | `/voice-rooms/{id}` | Инфо / настройки / удаление |
| POST | `/voice-rooms/{id}/join` · `/leave` | ✅ **только `join`**. `/leave` маршрута нет: выход определяется вебхуком LiveKit, а не запросом клиента |
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
| POST | `/media/init` | ✅ Presigned upload / CAS-дедуп (публичное) |
| POST | `/media/complete` | ✅ Фиксация метаданных |
| GET | `/media/{id}/url` | ✅ Presigned download (TTL 10 мин) |

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
| POST | `/calls` | ✅ Инициировать: создание LiveKit-room + токены |
| POST | `/calls/{id}/answer` · `/decline` · `/end` | ✅ **`answer` и `end`**; `/decline` маршрута нет — отказ выражается через `end` |
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

---

# Приложение. Фактическая поверхность API · сверено 2026-08-21

> **Зачем приложение, если выше уже каталог.** Каталог — карта **замысла**: он
> описывает весь ТЗ, включая то, чего на сервере нет. Это законно и в нём же
> оговорено. Но обратное молчание — код без строки в каталоге — оговоркой не
> покрывается, а его оказалось больше, чем описанного.
>
> Сверка Д3 дала числа:
>
> | | |
> |---|---|
> | маршрутов в коде | **71** |
> | из них описано в каталоге выше | **26** |
> | из этих 26 помечено ✅ «реализовано» | **6** |
> | есть в коде, но в каталоге **отсутствует** | **45** |
> | описано в каталоге, кода нет (замысел) | **47** |
>
> То есть карта покрывала треть территории, а пометка «реализовано» — двенадцатую
> часть. Клиент, который на К3 писал бы сеть по этому каталогу, не нашёл бы
> `POST /chats/{chatID}/read` вовсе.
>
> Поэтому ниже — **полный список, извлечённый из кода**, а не написанный руками.
> Он воспроизводится одной командой, и расхождение ловится её повторным запуском:
>
> ```bash
> grep -rhoE 'mux\.HandleFunc\("[^"]+",[^)]*\)+' server/internal/api/*.go \
>   | sed -E 's/mux\.HandleFunc\("//; s/",[[:space:]]*/|/; s/\)+$//' \
>   | sed -E 's#/api/v1##' | sort
> ```
>
> Столбец «токен» означает обёртку `requireActiveDevice`: она требует **живую
> запись устройства**, а не просто действительный JWT. Отозванное устройство
> теряет доступ немедленно, не дожидаясь истечения токена.

## Без токена — восемь маршрутов

Список короткий намеренно: каждый пункт здесь — это поверхность, доступная кому
угодно, и её стоит знать наизусть.

| Метод и путь | Обработчик | Почему без токена |
|---|---|---|
| `GET /app/version` | `appVersion` | клиент спрашивает до входа |
| `GET /ws` | `handleWS` | токен проверяется внутри, при рукопожатии |
| `POST /auth/sms/request` | `smsRequest` | вход начинается здесь |
| `POST /auth/sms/verify` | `smsVerify` | то же |
| `POST /auth/register` | `register` | устройства ещё не существует |
| `POST /link/start` | `linkStart` | у нового устройства нет аккаунта |
| `POST /link/claim` | `linkClaim` | оно же опрашивает результат подтверждения |
| `POST /livekit/webhook` | `livekitWebhook` | зовёт LiveKit; проверяется подписью вебхука, а не токеном |

## Все 71 маршрут

| Метод и путь | Обработчик | Токен |
|---|---|---|
| `DELETE /channels/{channelID}/subscribe` | `unsubscribeChannel` | да |
| `DELETE /chats/{chatID}/archive` | `setChatArchived` | да |
| `DELETE /devices/{deviceID}` | `revokeDevice` | да |
| `DELETE /groups/{groupID}/members/{userID}` | `removeGroupMember` | да |
| `DELETE /groups/{groupID}` | `deleteGroup` | да |
| `DELETE /users/me` | `deleteAccount` | да |
| `GET /app/version` | `appVersion` | **нет** |
| `GET /channels/discover` | `discoverChannels` | да |
| `GET /channels/{channelID}/posts` | `listChannelPosts` | да |
| `GET /channels` | `listMyChannels` | да |
| `GET /chats/archived` | `listArchivedChats` | да |
| `GET /chats/{chatID}/backup` | `chatBackupList` | да |
| `GET /chats/{chatID}/messages` | `listMessages` | да |
| `GET /devices` | `listMyDevices` | да |
| `GET /escrow/key` | `escrowKeyForChat` | да |
| `GET /escrow/pubkey` | `escrowPubkey` | да |
| `GET /groups/{groupID}/keys` | `groupKeys` | да |
| `GET /groups/{groupID}/members` | `listGroupMembers` | да |
| `GET /groups/{groupID}/messages` | `listGroupMessages` | да |
| `GET /groups/{groupID}` | `getGroup` | да |
| `GET /groups` | `listMyGroups` | да |
| `GET /keys/devices` | `listDeviceKeys` | да |
| `GET /media/{mediaID}/url` | `mediaURL` | да |
| `GET /users/lookup` | `lookupUser` | да |
| `GET /voice-rooms` | `listVoiceRooms` | да |
| `GET /ws` | `handleWS` | **нет** |
| `PATCH /groups/{groupID}` | `patchGroup` | да |
| `PATCH /users/me/name` | `setDisplayName` | да |
| `POST /auth/register` | `register` | **нет** |
| `POST /auth/sms/request` | `smsRequest` | **нет** |
| `POST /auth/sms/verify` | `smsVerify` | **нет** |
| `POST /calls/group` | `startGroupCall` | да |
| `POST /calls/{callID}/answer` | `answerCall` | да |
| `POST /calls/{callID}/end` | `endCall` | да |
| `POST /calls/{callID}/join` | `joinCall` | да |
| `POST /calls` | `startCall` | да |
| `POST /channels/{channelID}/posts` | `postToChannel` | да |
| `POST /channels/{channelID}/subscribe` | `subscribeChannel` | да |
| `POST /channels` | `createChannel` | да |
| `POST /chats/{chatID}/backup` | `chatBackupSave` | да |
| `POST /chats/{chatID}/read` | `chatRead` | да |
| `POST /chats/{chatID}/recover/provide` | `chatRecoverProvide` | да |
| `POST /chats/{chatID}/recover` | `chatRecover` | да |
| `POST /chats/{chatID}/typing` | `chatTyping` | да |
| `POST /groups/{groupID}/keys/recover/provide` | `groupKeyProvide` | да |
| `POST /groups/{groupID}/keys/recover` | `groupKeyRecover` | да |
| `POST /groups/{groupID}/keys` | `groupRotate` | да |
| `POST /groups/{groupID}/members/{userID}/ban` | `banGroupMember` | да |
| `POST /groups/{groupID}/members` | `addGroupMember` | да |
| `POST /groups/{groupID}/messages` | `postGroupMessage` | да |
| `POST /groups` | `createGroup` | да |
| `POST /link/claim` | `linkClaim` | **нет** |
| `POST /link/confirm` | `linkConfirm` | да |
| `POST /link/start` | `linkStart` | **нет** |
| `POST /livekit/webhook` | `livekitWebhook` | **нет** |
| `POST /media/complete` | `mediaComplete` | да |
| `POST /media/init` | `mediaInit` | да |
| `POST /messages` | `postMessage` | да |
| `POST /users/discover` | `discoverContacts` | да |
| `POST /users/identities` | `resolveIdentities` | да |
| `POST /users/me/reidentify/challenge` | `reidentifyChallenge` | да |
| `POST /users/me/reidentify` | `reidentify` | да |
| `POST /users/names` | `resolveNames` | да |
| `POST /voice-rooms/{roomID}/grant` | `grantSpeaker` | да |
| `POST /voice-rooms/{roomID}/hand` | `raiseHand` | да |
| `POST /voice-rooms/{roomID}/join` | `joinVoiceRoom` | да |
| `POST /voice-rooms/{roomID}/revoke` | `revokeSpeaker` | да |
| `POST /voice-rooms` | `createVoiceRoom` | да |
| `PUT /chats/{chatID}/archive` | `setChatArchived` | да |
| `PUT /devices/me/platform` | `setMyPlatform` | да |
| `PUT /groups/{groupID}/members/{userID}/role` | `setGroupRole` | да |
