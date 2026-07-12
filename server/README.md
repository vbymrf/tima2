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

## WS-доставка (live)

`GET /ws` — один WebSocket на устройство (websocket-events.md): первый кадр — `{token}` (device JWT), ответ `{event:"ok"}`, дальше live-поток. Кадры — JSON (debug-транспорт контракта; protobuf — вместе с клиентом). Ping каждые 30 с.

| Событие | Когда | Payload |
|---------|-------|---------|
| `message.new` | POST /messages | конверт base64url(protobuf) с единственной обёрткой устройства |
| `key.rotated` | POST /groups/{id}/keys | group_id, gk_version, sender_ephemeral_pub, wrapped_gk |

Шина — Redis Pub/Sub (`REDIS_URL`, dev: `redis://:tima-dev-only@localhost:6379`); без него `/ws` отвечает 503. Доставка live-only и best-effort: `sync.pull`/`ack`/cursor и офлайн-очередь push (Redis Stream → FCM/APNs) — итерация sync-offline §2 + worker; до неё догон после офлайна — REST-историей.

## Group keys API

| Метод | Путь | Что делает |
|-------|------|-----------|
| POST | `/api/v1/groups/{id}/keys` | Приём ротации GK от клиента-инициатора: `gk_version` строго current+1 (гонки исключает advisory-lock; конфликт → 409), escrow версии + wrapped_GK по устройствам |
| GET | `/api/v1/groups/{id}/keys?since_version=` | Пропущенные версии для устройства из токена (догон после офлайна); исключённый участник новых версий не получает |

Сервер GK не видит — только escrow-блоб и обёртки. Проверка «ротирующий — админ группы» появится вместе с модулем групп (membership) — TODO в `groups.go`.

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
