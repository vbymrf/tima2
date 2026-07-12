# server — бэкенд TIMA (фаза 2)

Go, модульный монолит: один бинарник `tima` с подкомандами `serve | worker | migrate` ([server-setup.md](../doc/07-deployment/server-setup.md) §5). Хранилища: PostgreSQL 16, Redis 7, MinIO.

## Dev-запуск (Windows)

```powershell
docker compose -f deploy/docker-compose.dev.yml up -d   # PostgreSQL + Redis + MinIO
sh scripts/gen-proto.sh                                 # Go-классы конверта из ../schema/proto
$env:DATABASE_URL="postgres://tima:tima-dev-only@localhost:5432/tima"
go run ./cmd/tima                                       # serve: миграции + API на :8080
```

## Message Service (MVP)

| Метод | Путь | Что делает |
|-------|------|-----------|
| POST | `/api/v1/messages` | Приём `Envelope` (protobuf): валидация инвариантов wire-формата → **проверка подписи Ed25519 по canonical_bytes** (ключ из `devices`) → раскладка в `personal_messages` + `personal_message_keys`. Дедупликация по заголовку `X-Client-Msg-Id` (UUID, обязателен) |
| GET | `/api/v1/chats/{id}/messages?before=&limit=` | История (новые → старые): конверт как base64url(protobuf) с единственной обёрткой запрашивающего устройства |
| POST | `/api/v1/dev/devices` | **Dev-заглушка** регистрации устройства (до фазы Auth — потом `/auth/register`) |

Авторизация MVP — заголовок `X-Device-Id` (dev-заглушка до device JWT). Наружу не выставлять.

Интеграционный тест (`internal/api`) гоняет полный цикл против живого PostgreSQL: «клиент» шифрует и подписывает конверт тем же конвейером, что `messenger-crypto`, сервер проверяет и раскладывает, «получатель» разворачивает wrapped_key и читает plaintext; плюс негативные сценарии (битая подпись, подмена метаданных, чужое устройство → 403, дубль → dedup). Без поднятой базы тест пропускается.

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
