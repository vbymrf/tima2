# WebSocket-события

> Один WS на устройство: `wss://api.example.com/ws` (через Caddy). Аутентификация — device JWT в первом кадре. Транспорт кадров: Protobuf (debug-режим — JSON). Синхронизация и cursor — [sync-offline.md](../04-data/sync-offline.md) §2.

## Жизненный цикл соединения

```
connect → auth {token, device_id} → ok {session}
        → sync.pull {cursor}      → батчи событий → ack {event_id}
        → live-поток
ping/pong каждые 30 с; reconnect с экспоненциальным backoff + jitter
```

Все server→client события имеют монотонный `event_id` и идемпотентны.

## Server → Client

| Событие | Payload (ядро) | Примечание |
|---------|----------------|-----------|
| `message.new` | конверт + wrapped_key для устройства | Личные и групповые |
| `message.deleted` | message_id, scope | soft delete «для всех» |
| `receipt.update` | message_id, user_id, delivered/read/listened | Статусы ✓/✓✓ |
| `typing` | chat_id, user_id, kind: typing\|watching | TTL 5 с, не персистится |
| `presence` | user_id, online\|offline\|invisible-скрыт | По подписке на видимые чаты |
| `key.rotated` | group_id, gk_version, wrapped_gk | Ротация GK |
| `group.rotation_needed` | group_id, reason | Просьба админу сменить GK (`reason=device_revoked` — отозвано устройство участника). Сервер GK не видит и ротировать не может, поэтому просит того, у кого ключ есть. Уходит всем админским устройствам: первое успевает, остальные получают `version_conflict` — это штатная гонка |
| `key.changed` | user_id, device_id | Смена identity собеседника → UI-предупреждение |
| `chat.updated` | chat/group метаданные, роли | |
| `call.incoming` | call_id, from, kind, room_token | Параллельно push |
| `call.state` | call_id, ringing\|answered\|ended\|declined | |
| `voice-room.update` | group_id, участники | Голосовые чаты |
| `feed.new` | счётчик новых постов | Бейдж «новые посты», не сами посты |
| `inbox.thread` | thread_id, identity_id, status, assignee | Окно 4: новое обращение / смена статуса (командное) |
| `inbox.event` | event_type, identity_id, target_ref | Окно 4: личное событие по правилам маршрутизации |
| `notify` | сгруппированное уведомление | Если приложение активно (вместо push) |
| `sync.gap` | — | Cursor устарел (> 90 дней) → полный re-bootstrap |

## Client → Server

| Событие | Payload | Примечание |
|---------|---------|-----------|
| `ack` | event_id | Сдвиг cursor |
| `typing` | chat_id, kind | Троттлинг 3 с на клиенте |
| `receipt` | message_id, status | Пачками (батч до 100) |
| `presence.set` | online\|invisible | Режим «невидимка» |
| `sync.pull` | cursor, limit | Догрузка после офлайна |

Отправка сообщений — **только REST `POST /messages`** (надёжность, retry, идемпотентность по `client_msg_id`); WS — для доставки и лёгких сигналов.

## Доставка и офлайн

```
message.new → устройство онлайн?  да → WS push
                                  нет → очередь push (Redis Stream)
                                        → FCM/APNs (с учётом настроек и тихих часов)
Умная группировка: ≤ 1 push за 5 мин из одного чата (агрегированный текст)
```

Push-payload не содержит plaintext защищённых сообщений — только chat_id и счётчик; текст превью расшифровывает клиент (data-push → локальная нотификация).

---

# Приложение. Сверка с кодом · 2026-08-21

Таблицы выше — карта замысла. Ниже — что действительно есть, извлечённое из
`server/internal/api/`. Воспроизводится командами в конце раздела.

```
имён событий в коде:                 19
из них описано выше:                  8
есть в коде, но не описано:          11
описано, но кода нет:                10
```

## Расхождение устройства, а не имён — читать первым

**Документ отправляет `typing` и `receipt` кадрами WS от клиента к серверу. Код
принимает их по REST:** `POST /api/v1/chats/{chatID}/typing` и
`POST /api/v1/chats/{chatID}/read`.

Клиент, написанный по этому документу, отправлял бы кадры, которые сервер **молча
игнорирует**: в `ws.go` разбираются ровно два входящих кадра — `sync.pull` и `ack`.
Молчание здесь хуже ошибки: ни отказа, ни записи в журнал, а «печатает…» просто
никогда не появляется у собеседника.

**И расхождение имён того же рода:** документ обещает `receipt.update`, код рассылает
**`receipt.read`**. Клиент подписался бы на имя, которое не придёт никогда. Отсутствие
события заметно сразу, а неверное имя выглядит как реализованное — поэтому оно и
дороже.

## Server → client: что действительно рассылается

| Событие | Где | Описано выше |
|---|---|---|
| `message.new` | `server.go` | да |
| `message.group` | `group_messages.go` | **нет** |
| `receipt.read` | `receipts.go` | **имя другое** (`receipt.update`) |
| `typing` | `receipts.go`, `event_id=0` — живой кадр без персистенции и без `ack` | да |
| `key.rotated` | `groups.go` | да |
| `group.rotation_needed` | `devices.go` | да |
| `call.incoming` | `calls.go`, `group_calls.go` | да |
| `call.state` | `calls.go` | да |
| `voice.hand` | `calls.go` | **нет** |
| `voice.granted` · `voice.revoked` | `calls.go`, через переменную — грепом по строке не находятся | **нет** |
| `channel.post` | `channels.go` | **нет** |
| `recovery.msg_request` · `recovery.msg_ready` | `chat_recovery.go` | **нет** |
| `recovery.gk_request` · `recovery.gk_ready` | восстановление групповых ключей | **нет** |
| `sync.gap` | `ws.go` | да |
| `sync.done` | `ws.go` | **нет** |

## Client → server: ровно два кадра

`ws.go` разбирает `sync.pull` и `ack`. Больше ничего.

Значит из документа не существует: `typing` (это REST), `receipt` (это REST,
`/read`), `presence.set` (присутствия на сервере нет вовсе — решение А3 в
[ВОПРОСЫ-К-ЗАКАЗЧИКУ.md](../../doc_mig/ВОПРОСЫ-К-ЗАКАЗЧИКУ.md)).

## Описано, но кода нет

`message.deleted` · `presence` · `key.changed` · `chat.updated` ·
`voice-room.update` · `feed.new` · `inbox.thread` · `inbox.event` · `notify` ·
`presence.set`

Это законно — документ карта замысла, — но `message.deleted` стоит отметить особо:
маршрута удаления сообщения в API **нет ни одного**, хотя столбцы мягкого удаления в
схеме есть. Подробно — [ДЕЛЬТА-СЕРВЕРА.md](../../doc_mig/ДЕЛЬТА-СЕРВЕРА.md).

## Как перепроверить

```bash
# рассылка на устройства
grep -rhoE 's\.notify\([^,]+, *[^,]+, *"[a-z_.]+"' server/internal/api/*.go \
  | grep -oE '"[a-z_.]+"' | sort -u
# прямые публикации мимо notify
grep -rn 'Events\.Publish(' server/internal/api/*.go | grep -v _test
# имена, собираемые в переменной — грепом по строке НЕ находятся
grep -rn 'event := "\|event = "' server/internal/api/*.go | grep -v _test
# входящие кадры
grep -n 'case "' server/internal/api/ws.go
```

Четвёртая команда нужна отдельно: `voice.granted` и `voice.revoked` кладутся в
переменную и уходят в `notify` через неё. Поиск по строке рядом с `notify` их не
видит — так они и не попали в документ.
