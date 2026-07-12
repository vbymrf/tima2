# TIMA — документация проекта

> **Продукт:** мессенджер-комбайн — единая среда для личного общения, новостного и медиа-контента, социальных взаимодействий.
> **Стек:** KMP + Compose Multiplatform (клиент) · Go (бэкенд) · PostgreSQL · Redis · MinIO · LiveKit · Kodium (крипто).

## Правило каноники

1. **Каноника проекта — только md-файлы** этой директории.
2. `*.docx` — исследовательские материалы (черновики обсуждений). На них нельзя ссылаться как на требования; при конфликте md всегда важнее.
3. Технические решения фиксируются в [ADR](./adr/); изменение решения = новый ADR, а не правка старого.
4. [messenger-crypto-architecture.md](./messenger-crypto-architecture.md) — legacy-обзор с обоснованиями; каноническая крипто-спецификация — [03-security/crypto-protocol.md](./03-security/crypto-protocol.md).

## Структура

| Раздел | Содержание |
|--------|-----------|
| [01-product/](./01-product/) | Концепция продукта, сообщества, матрица контента и безопасности, этапы создания (roadmap) |
| [02-architecture/](./02-architecture/) | Системная архитектура, границы модулей, технологический стек, модель данных |
| [03-security/](./03-security/) | Крипто-протокол (каноника), escrow и юридический доступ, жизненный цикл ключей, аттестация клиентов |
| [04-data/](./04-data/) | Хранение медиа, поиск и индексация, синхронизация и офлайн |
| [05-api/](./05-api/) | Каталог REST API, события WebSocket |
| [06-realtime/](./06-realtime/) | Звонки (LiveKit), политика записи |
| [07-deployment/](./07-deployment/) | Развертывание серверов (VPS + docker-compose), путь масштабирования |
| [adr/](./adr/) | Architecture Decision Records |
| [doc_UI/](./doc_UI/00-index.md) | UI-ТЗ: 32 спецификации экранов (WireMD) |

## Ключевые документы для входа в проект

1. [01-product/concept.md](./01-product/concept.md) — что мы строим (5 окон, механики).
2. [02-architecture/system-architecture.md](./02-architecture/system-architecture.md) — как это устроено.
3. [03-security/crypto-protocol.md](./03-security/crypto-protocol.md) — как шифруем.
4. [01-product/roadmap.md](./01-product/roadmap.md) — в каком порядке строим.
5. [07-deployment/server-setup.md](./07-deployment/server-setup.md) — как поднять серверы.

## Сводка главных решений

| Решение | ADR |
|---------|-----|
| Клиент: KMP + Compose Multiplatform (Android, iOS, Desktop) | [0001](./adr/0001-kmp-compose-client.md) |
| Бэкенд: Go, монолит-модуль → микросервисы | [0002](./adr/0002-go-backend.md) |
| Хранение: PostgreSQL 16 (сообщения, метаданные), Redis, MinIO | [0003](./adr/0003-postgresql-storage.md) |
| Шифрование: клиентское (Kodium) + controlled escrow — всё, кроме публичного контента | [0004](./adr/0004-controlled-escrow.md) |
| Kodium — единственная крипто-библиотека клиента; gate перед production | [0005](./adr/0005-kodium-readiness-gate.md) |
| Звонки: LiveKit/SRTP, не app-E2E; запись private запрещена | [0006](./adr/0006-livekit-media-policy.md) |
| Поиск: приватное — только локальный индекс, публичное — серверный | [0007](./adr/0007-search-split.md) |
| Входная точка: Caddy (MVP), Envoy — при переходе на микросервисы | [0008](./adr/0008-caddy-edge.md) |
| API: schema-first, два контура (Client REST / Bot RPC) | [0009](./adr/0009-schema-first-api.md) |
