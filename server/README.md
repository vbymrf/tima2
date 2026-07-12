# server — бэкенд TIMA (фаза 2)

Go, модульный монолит: один бинарник `tima` с подкомандами `serve | worker | migrate` ([server-setup.md](../doc/07-deployment/server-setup.md) §5). Хранилища: PostgreSQL 16, Redis 7, MinIO.

## Dev-запуск (Windows)

```powershell
docker compose -f deploy/docker-compose.dev.yml up -d   # PostgreSQL + Redis + MinIO
sh scripts/gen-proto.sh                                 # Go-классы конверта из ../schema/proto
$env:DATABASE_URL="postgres://tima:tima-dev-only@localhost:5432/tima"
go run ./cmd/tima                                       # serve: миграции + API на :8080
```

## Auth (MVP-ядро)

| Метод | Путь | Что делает |
|-------|------|-----------|
| POST | `/api/v1/auth/sms/request` | `{phone}` (E.164) → одноразовый код. Провайдера SMS нет: при `TIMA_DEV_SMS=1` код возвращается в ответе (`dev_code`), иначе пишется в лог |
| POST | `/api/v1/auth/sms/verify` | `{request_id, code}` → короткий `registration_token` (10 мин). Код одноразовый, хранится только hash |
| POST | `/api/v1/auth/register` | `{registration_token, encryption_pub, signing_pub}` → пользователь (по телефону) + устройство → `access_token` (device JWT, 24 ч). Повторный вход с тем же телефоном добавляет новое устройство (мультиустройство) |
| GET | `/api/v1/keys/devices?user_id=` | Публичные ключи устройств пользователя: отправителю — адресаты wrapped keys, получателю — проверка подписи |

Секрет JWT — `JWT_SIGNING_KEY`; без него генерируется эфемерный (dev). Ещё не реализовано из карты Auth: guest, refresh, recovery, `/link/*` (QR), attestation, rate limiting (Redis).

## Message Service

| Метод | Путь | Что делает |
|-------|------|-----------|
| POST | `/api/v1/messages` | Bearer. Приём `Envelope` (protobuf): инварианты wire-формата → **sender из токена == meta** → **подпись Ed25519 по canonical_bytes** → раскладка в `personal_messages` + `personal_message_keys`. Дедуп по `X-Client-Msg-Id` (UUID, обязателен) |
| GET | `/api/v1/chats/{id}/messages?before=&limit=` | Bearer. История (новые → старые): конверт base64url(protobuf) с единственной обёрткой устройства из токена |

## WS-доставка и синхронизация

`GET /ws` — один WebSocket на устройство (websocket-events.md): первый кадр — `{token}` (device JWT), ответ `{event:"ok"}`, дальше `sync.pull` и live-поток. Кадры — JSON (debug-транспорт контракта; protobuf — вместе с клиентом). Ping каждые 30 с.

Каждое доставляемое событие пишется в персистентный `device_events` (монотонный `event_id`), live через Redis Pub/Sub — лишь ускорение: офлайн ничего не теряет (sync-offline.md §2).

| Событие | Когда | Payload |
|---------|-------|---------|
| `message.new` | POST /messages | конверт base64url(protobuf) с единственной обёрткой устройства |
| `message.group` | POST /groups/{id}/messages | сообщение группы (все поля preimage подписи) |
| `key.rotated` | POST /groups/{id}/keys | group_id, gk_version, sender_ephemeral_pub, wrapped_gk |

Client → server: `sync.pull {cursor?, limit?}` — события с `event_id > cursor` (без cursor — серверная копия), в конце `sync.done {count, next_cursor, more}`; `ack {event_id}` — сдвиг серверного cursor (только вперёд). События идемпотентны — пересечение догона и live безопасно; клиентский cursor первичен, серверный — резерв на потерю локальной БД.

Шина — Redis Pub/Sub (`REDIS_URL`, dev: `redis://:tima-dev-only@localhost:6379`); без него `/ws` отвечает 503. Если cursor старше ретеншена (GC уже удалил события после него) — `sync.gap {next_cursor}`: полный re-bootstrap REST-историей, дальше live. Офлайн-очередь push (Redis Stream → FCM/APNs) — когда появится push-провайдер.

## Worker (GC ретеншена)

`tima worker` — фоновый процесс: каждый `TIMA_GC_INTERVAL` (1h) чистит по правилам sync-offline.md §1 и escrow-legal-access.md §5:

| Что | Срок | Правило |
|-----|------|---------|
| `device_events` | `TIMA_RETENTION_DAYS` (90) | журнал доставки; max удалённый event_id → watermark для `sync.gap` |
| `personal_message_keys` | 90 дней | обёртки удаляются → сообщение исчезает из выдачи; **конверт с escrow остаётся** (юридический доступ до 7 лет — до escrow-архива) |
| `group_wrapped_keys` | 90 дней | по `rotated_at` версии; escrow версии в `group_key_history` остаётся |
| wrapped_GK исключённых | `TIMA_APPEAL_WINDOW_DAYS` (30) после выхода | окно апелляции (crypto-protocol §4.2) |
| `sms_codes` | просроченные + 24 ч | |

GC медиа — вместе со связью media↔message; push-очередь — с провайдером FCM/APNs.

## Group Service

Группы (переписка) + полиморфная подсистема `memberships` (роли: owner > admin > moderator > member; действия над участником — только при строго старшем ранге). Активное членство — `left_at IS NULL`; выход/исключение и soft delete группы сохраняют историю.

| Метод | Путь | Что делает |
|-------|------|-----------|
| POST | `/api/v1/groups` | `{kind: private\|public, title, …}` → группа, создатель — owner |
| GET/PATCH/DELETE | `/api/v1/groups/{id}` | Инфо (+`my_role`; private не-участнику — 404) / настройки (admin+) / soft delete (owner) |
| GET/POST | `/api/v1/groups/{id}/members` | Список (участникам) / добавление (admin+, роль строго ниже своей; инвайты — позже) |
| DELETE | `/api/v1/groups/{id}/members/{uid}` | Самовыход или исключение (admin+ над младшим рангом); owner не выходит (передача владения — позже) |
| PUT | `/api/v1/groups/{id}/members/{uid}/role` | Смена роли (admin+; обе роли строго ниже своей) |
| POST | `/api/v1/groups/{id}/members/{uid}/ban` | `{seconds}` → `banned_until` (moderator+ над младшим рангом); членство сохраняется, запрет писать проверит Message Service групп |

## Сообщения групп

| Метод | Путь | Что делает |
|-------|------|-----------|
| POST | `/api/v1/groups/{id}/messages` | Только участникам: private — `payload = SecretBox(zstd(MessageBody), GK)` + существующий `gk_version`; public — plaintext protobuf без `gk_version`. Подпись Ed25519 по `group_message_canonical_bytes` (schema/proto/README.md, KAT `group_message_canonical`) проверяется при приёме; дедуп по `client_msg_id`; `thread_root`/`reply_to` — на сообщения этой же группы. Бан → 403, slow mode (кроме moderator+) → 429 |
| GET | `/api/v1/groups/{id}/messages?before=&limit=&thread=` | История (новые → старые), фильтр ветки; только участникам |

`message_id` назначает сервер (в подпись не входит — версию раскладки несёт доменная метка `tima.group_message.v1`). Live-доставка — событие `message.group` устройствам активных участников. Премодерация (pending) и сообщения от имени сущности — с Bot API.

## Group keys API

| Метод | Путь | Что делает |
|-------|------|-----------|
| POST | `/api/v1/groups/{id}/keys` | Приём ротации GK: **только owner\|admin private-группы**; получатели wrapped_GK — устройства активных участников (иначе 400); `gk_version` строго current+1 (гонки исключает advisory-lock; конфликт → 409), escrow версии + wrapped_GK по устройствам |
| GET | `/api/v1/groups/{id}/keys?since_version=` | Пропущенные версии для устройства из токена (догон после офлайна); исключённый участник новых версий не получает, старые (уже выданные ему) остаются читаемыми — окно апелляции |

Сервер GK не видит — только escrow-блоб и обёртки.

## Media Service

| Метод | Путь | Что делает |
|-------|------|-----------|
| POST | `/api/v1/media/init` | `{size_bytes, mime, is_encrypted, content_hash?, chunk_count?}` → `media_id` + presigned PUT URL(ы) (15 мин). Публичное с `content_hash` — CAS-дедуп (`{dedup, media_id}` без заливки); для приватного `content_hash` **запрещён** (утечка «файл уже был») |
| POST | `/api/v1/media/complete` | Проверка, что объект(ы) реально в MinIO → фактический размер, `status=complete`. Идемпотентен |
| GET | `/api/v1/media/{id}/url` | Presigned GET (TTL 10 мин); для чанков — список URL |

Файлы не проксируются через бэкенд — клиент ходит в MinIO напрямую (media-storage.md §1). Env: `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET` (по умолчанию `media`); без `S3_ENDPOINT` media-эндпоинты отвечают 503.

Интеграционные тесты (`internal/api`) гоняют полный производственный поток против живого PostgreSQL: SMS-код → регистрация двух пользователей и трёх устройств → отправка с обёртками → дедуп → история → «получатель» разворачивает wrapped_key и читает plaintext. Негативные: без токена 401; чужой токен, битая подпись, подмена метаданных, повтор/подбор SMS-кода → 403. Без поднятой базы тесты пропускаются.

## Крипто-паритет

`internal/crypto` проходит **те же KAT-векторы** [schema/test-vectors](../schema/test-vectors/), что и Kotlin-клиент (`messenger-crypto`): canonical_bytes, Ed25519, SecretBox, Box, HKDF, ключи чанков, ML-KEM-768 (stdlib `crypto/mlkem`). Паритет двух реализаций — и есть контракт; расхождение = красный билд.

```powershell
go test ./...
```

Сервер контент не расшифровывает: его крипто-обязанность — проверка подписи конверта по `canonical_bytes` при приёме (crypto-protocol.md §7, §10).

## Структура

```
cmd/tima/            — entrypoint (serve | worker | migrate)
internal/crypto/     — canonical_bytes + проверка подписи (KAT-паритет)
migrations/          — SQL-миграции (0001: devices, personal_messages, personal_message_keys)
deploy/              — docker-compose.dev.yml (локальные хранилища)
```
