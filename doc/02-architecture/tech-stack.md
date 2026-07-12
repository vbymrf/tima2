# Технологический стек

> **Актуализировано:** 2026-07-12. Конкретные версии проверяются на момент старта каждой фазы — не копировать из исследовательских docx (там версии устарели и внутренне несогласованы).

## Клиент

| Компонент | Выбор | Примечание |
|-----------|-------|-----------|
| Язык / шаринг кода | Kotlin Multiplatform | [ADR-0001](../adr/0001-kmp-compose-client.md) |
| UI | Compose Multiplatform | Android, iOS, Desktop (Windows) |
| Локальная БД | SQLDelight | + SQLite FTS5 для локального поиска |
| HTTP/WS | Ktor Client | |
| DI | Koin | |
| Сериализация | kotlinx.serialization + Protobuf | Конверты — Protobuf ([crypto-protocol.md](../03-security/crypto-protocol.md)) |
| Криптография | **Kodium** (`eu.livotov.labs:kodium`) | Единственная криптобиблиотека, [ADR-0005](../adr/0005-kodium-readiness-gate.md) |
| Сжатие | zstd (до шифрования) | expect/actual биндинги |
| Архитектурный контроль | Konsist | Правила в CI |
| Минимальные ОС | Android API 26+ · iOS 15+ · Windows 10+ | Пересмотреть на старте |
| Web-клиент | В планах (пост-MVP) | Compose Multiplatform for Web/Wasm — оценить зрелость на момент старта; доверие через QR-привязку, как Windows |

## Сервер

| Компонент | Выбор | Примечание |
|-----------|-------|-----------|
| Язык | Go (актуальный stable) | [ADR-0002](../adr/0002-go-backend.md) |
| HTTP router | chi или echo | Выбрать при старте фазы 0 |
| WebSocket | nhooyr/websocket (coder/websocket) | |
| PostgreSQL driver | pgx v5 | + golang-migrate для миграций |
| Redis | go-redis v9 | Streams, Pub/Sub |
| S3 | minio-go v7 | |
| LiveKit | livekit/server-sdk-go | Токены комнат, webhooks |
| Push | FCM HTTP v1 + APNs (token-based) | |
| Метрики | prometheus/client_golang | |

## Инфраструктура

| Компонент | Выбор | Версия |
|-----------|-------|--------|
| БД | PostgreSQL | 16+ |
| Кэш/очереди | Redis | 7+ |
| Объектное хранилище | MinIO | актуальный stable |
| Медиасервер | LiveKit (self-hosted) + встроенный TURN | актуальный stable |
| Edge | Caddy | 2.x, [ADR-0008](../adr/0008-caddy-edge.md) |
| Контейнеризация | Docker + docker-compose | [server-setup.md](../07-deployment/server-setup.md) |
| Наблюдаемость | Prometheus + Grafana (+ Loki) | |
| Escrow | AWS Nitro Enclave или HSM (production, фаза 6) | stub на MVP |

## Безопасность клиента

| Платформа | Механизм |
|-----------|----------|
| iOS | App Attest (DeviceCheck) |
| Android | Play Integrity API |
| Windows | QR-привязка через доверенный телефон ([client-attestation.md](../03-security/client-attestation.md)) |
| Транспорт | TLS 1.3 + certificate pinning (SPKI) |

## Отложено (подключается по триггерам, [scaling.md](../07-deployment/scaling.md))

OpenSearch (публичный поиск) · Kafka (fan-out под нагрузкой) · Envoy (микросервисный gateway) · Kubernetes · TimescaleDB/шардинг PG.
