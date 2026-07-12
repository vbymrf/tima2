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
