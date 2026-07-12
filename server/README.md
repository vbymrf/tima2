# server — бэкенд TIMA (фаза 2)

Go, модульный монолит: один бинарник `tima` с подкомандами `serve | worker | migrate` ([server-setup.md](../doc/07-deployment/server-setup.md) §5). Хранилища: PostgreSQL 16, Redis 7, MinIO.

## Dev-запуск (Windows)

```powershell
docker compose -f deploy/docker-compose.dev.yml up -d   # PostgreSQL + Redis + MinIO
go run ./cmd/tima                                       # API на :8080 (пока /healthz)
```

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
