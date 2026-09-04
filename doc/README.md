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
| [01-product/](./01-product/) | Концепция продукта, сообщества, **разделы** (сквозная раскладка по темам), матрица контента и безопасности, этапы создания (roadmap) |
| [02-architecture/](./02-architecture/) | Системная архитектура, границы модулей, технологический стек, модель данных |
| [03-security/](./03-security/) | Крипто-протокол (каноника), escrow и юридический доступ, жизненный цикл ключей, аттестация клиентов |
| [04-data/](./04-data/) | Хранение медиа, поиск и индексация, синхронизация и офлайн |
| [05-api/](./05-api/) | Каталог REST API, события WebSocket |
| [06-realtime/](./06-realtime/) | Звонки (LiveKit), политика записи |
| [07-deployment/](./07-deployment/) | Развертывание серверов (VPS + docker-compose), путь масштабирования |
| [adr/](./adr/) | Architecture Decision Records |
| [интерфейс.md](./интерфейс.md) | **Функционал по макету:** что приложение обязано уметь, вычитано из Layout-UI-light; со временем заменяет doc_UI |
| [doc_UI/](./doc_UI/00-index.md) | UI-ТЗ: 35 спецификаций экранов (WireMD) — замысел. **Заменяется** [интерфейс.md](./интерфейс.md); соответствие файлов — его §13, непокрытое макетом — §12 |
| [Layout-UI-light/](./Layout-UI-light/README.md) | **Макет UI, светлая тема:** три формата — телефон, планшет, ПК — окнами и подокнами в HTML: квадратные аватары, салатовый как навигация и действие, аватар внутри сообщения, лента без рамок, каталог по **разделам**. Там же [палитра](./Layout-UI-light/палитра.html) |
| [Layout-UI-dark/](./Layout-UI-dark/README.md) | **Макет UI, тёмная тема:** те же экраны и классы, другие значения — `стиль.css` + `тьма.css` |

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
| Восстановление истории и мультиустройство | [0010](./adr/0010-history-recovery-multidevice.md) |
| Содержимое сообщения: узлы и разметка вместо плоского текста | [0011](./adr/0011-message-content-model.md) |
| Escrow по эпохам: ключ месяца, а не на сообщение | [0012](./adr/0012-escrow-key-epochs.md) |
| Обязательство по ключу сообщения (конверт версии 2) | [0013](./adr/0013-key-commitment.md) |
| Аккаунт как цепочка идентификаторов | [0014](./adr/0014-identity-chain.md) |
| Срок хранения и физическое стирание | [0015](./adr/0015-retention-and-erasure.md) |
| Местное хранилище и три состояния связи | [0016](./adr/0016-local-store-and-link-states.md) |
| Ротация группового ключа при смене состава и эпохи | [0017](./adr/0017-group-key-rotation.md) |
| Личная группа: карточка, аудитория, заявка | [0018](./adr/0018-personal-group-visibility.md) |
| Уровень сообщения: кому сервер его отдаёт | [0019](./adr/0019-message-level.md) |
| Очередь исходящих: позднее запечатывание, два прохода | [0020](./adr/0020-outgoing-queue.md) |
