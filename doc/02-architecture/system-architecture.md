# Системная архитектура

> Целевая картина системы. Решения: [ADR-0002 Go](../adr/0002-go-backend.md), [ADR-0003 PostgreSQL](../adr/0003-postgresql-storage.md), [ADR-0008 Caddy](../adr/0008-caddy-edge.md). Крипто-слой: [crypto-protocol.md](../03-security/crypto-protocol.md).

## 1. Общая схема

```
   Android (KMP)      iOS (KMP)      Windows Desktop (KMP)
        │                 │                  │
        └────────┬────────┴──────────────────┘
                 │  TLS 1.3 (+ cert pinning)
                 ▼
        ┌─────────────────┐        WebRTC (SRTP, UDP)
        │      Caddy      │      ┌──────────────────────┐
        │  (edge, TLS)    │      │   LiveKit Server      │
        └───────┬─────────┘      │   + TURN              │
                │                └──────────┬───────────┘
     ┌──────────┼──────────────┐            │ webhooks/API
     ▼          ▼              ▼            ▼
 ┌────────┐ ┌────────┐   ┌──────────────────────────────┐
 │  REST  │ │   WS   │   │      Backend (Go, монолит)    │
 │  API   │ │Gateway │──►│  auth · users · chats ·       │
 └────────┘ └────────┘   │  messages · keys · media ·    │
                         │  groups · feeds · calls ·     │
                         │  notifications · search       │
                         └───┬──────┬──────┬─────────────┘
                             │      │      │
              ┌──────────────┘      │      └──────────────┐
              ▼                     ▼                     ▼
      ┌──────────────┐      ┌────────────┐        ┌────────────┐
      │ PostgreSQL 16│      │   Redis    │        │   MinIO    │
      │ метаданные + │      │ кэш · pub/ │        │ ciphertext │
      │ ciphertext   │      │ sub · очер.│        │ blobs (S3) │
      └──────────────┘      └────────────┘        └────────────┘
                                                        ▲
                             ┌────────────────┐         │ presigned
                             │ Escrow (HSM /  │         │ upload/download
                             │ Nitro Enclave) │◄── клиенты напрямую
                             └────────────────┘
```

## 2. Компоненты

### 2.1. Клиент (KMP + Compose)

| Слой | Содержание |
|------|-----------|
| UI (Compose MP) | 5 окон + специализированные; спецификации — [doc_UI](../doc_UI/00-index.md) |
| Domain (commonMain) | Сценарии, состояние, offline-очередь исходящих |
| `messenger-crypto` | Слои шифрования поверх Kodium ([ADR-0005](../adr/0005-kodium-readiness-gate.md)) |
| Data | SQLDelight (кэш сообщений, локальный FTS, ratchet-сессии), Ktor Client (REST/WS) |
| Platform (expect/actual) | Микрофон, камера, push, Keystore/Secure Enclave, аттестация |

Клиент — **основная копия защищённой истории** (сервер отдаёт ciphertext, расшифровка только на устройстве).

### 2.2. Backend (Go) — модульный монолит

Один бинарник, внутренние модули с чёткими границами (интерфейсы, свои таблицы). Сущности group/channel/voiceroom/community разделены полностью и не зависят друг от друга — правила: [module-boundaries.md](./module-boundaries.md). Порядок выделения в отдельные сервисы при росте — [scaling.md](../07-deployment/scaling.md).

| Модуль | Ответственность | Хранение |
|--------|----------------|----------|
| `auth` | SMS-регистрация, JWT, сессии, аттестация клиентов | PG: users, devices, sessions |
| `users` | Профили, контакты, соц. граф, подписки, чёрный список, **виртуальные пользователи** (владение, операторы, передача, аудит) | PG |
| `social_inbox` | Окно 4: карточки обращений (статус/assignee — серверные), события, правила маршрутизации | PG + Redis |
| `chats` | Чаты 1:1, архив | PG |
| `messages` | Приём/выдача конвертов (ciphertext), статусы доставки, threads | PG (партиции по времени) |
| `keys` | Identity keys устройств, wrapped keys CRUD, PreKey bundles | PG |
| `group` | Переписка: группы (личные/публичные), ротация GK (планировщик) | PG |
| `channel` | Каналы: метаданные, авторы, статистика (контент — в `publications`) | PG |
| `voiceroom` | Аудио-чаты: live-комнаты (LiveKit), «в эфире» | PG + LiveKit |
| `community` | Сообщества: состав, уровни доступа, витрина | PG |
| `publications` | Посты (body + entities), черновики, отложенная публикация | PG |
| `comments` | Комментарии (полиморфные; только публичный контур) | PG |
| `membership` | Роли всех сущностей, инвайты, папки каталога | PG |
| `media` | Presigned URL, CAS-дедупликация публичного, квоты | PG (метаданные) + MinIO |
| `feeds` | Общая лента: скоринг по оценкам + атрибуты; лента друзей: fan-out публичных полок ([feed-ranking.md](../04-data/feed-ranking.md)) | PG + Redis (feed:user_id) |
| `attributes` | Атрибуты (= хэштеги), жанры, одобрение меток «автор + репутация» | PG |
| `reactions` | Шкала 9 эмоций, рейтинговые счётчики «+/−» (воркер) | PG |
| `calls` | Комнаты LiveKit, токены, история звонков | PG + LiveKit API |
| `notifications` | Push (FCM/APNs), группировка, тихие часы | PG + Redis |
| `search` | Публичный FTS (PG), фасад для клиента | PG |
| `escrow` | Только запись escrow_blob и интерфейс к анклаву; приватный ключ вне бэкенда | HSM/анклав |

### 2.3. Инфраструктура

| Компонент | Роль |
|-----------|------|
| **Caddy** | TLS-терминация, маршрутизация REST/WS, балансировка upstream'ов |
| **PostgreSQL 16** | Единственная БД ([data-model.md](./data-model.md)) |
| **Redis** | Online-статусы, pub/sub «новое сообщение» → WS-узлы, Redis Streams (fan-out, push-очередь), rate limiting |
| **MinIO** | Все бинарные объекты; приватное — только ciphertext; presigned upload/download напрямую клиентом |
| **LiveKit + TURN** | SFU для звонков и голосовых чатов; Egress не разворачивается ([ADR-0006](../adr/0006-livekit-media-policy.md)) |
| **HSM / Nitro Enclave** | Escrow private key, M-of-N; production — фаза 6 |

## 3. Ключевые потоки

### 3.1. Отправка личного сообщения

```
Клиент A                          Backend                        Клиент B
   │ 1. конверт: SecretBox(msg_key)  │                              │
   │    + wrapped_key(B, устройства) │                              │
   │    + escrow_blob                │                              │
   │──── POST /messages ────────────►│ 2. INSERT (append-only)      │
   │                                 │ 3. Redis PUBLISH chat:{id}   │
   │◄─── 200 {message_id} ───────────│ 4. WS push ─────────────────►│
   │                                 │    (или FCM/APNs если офлайн)│
   │                                 │                              │ 5. unwrap msg_key
   │                                 │◄──── delivery receipt ───────│    → расшифровка
```

Клиент не ждёт fan-out: подтверждение — после записи. Офлайн-получатель заберёт конверт + wrapped key при подключении (путь B, [crypto-protocol.md](../03-security/crypto-protocol.md)).

### 3.2. Отправка медиа

```
1. Клиент: сжатие → (приватное: SecretBox media_key) → SHA-256
2. POST /media/init {hash, size, mime} → presigned PUT URL (или "уже есть" — CAS, только публичное)
3. Клиент → MinIO напрямую (PUT, минуя бэкенд)
4. POST /media/complete → метаданные в PG
5. Сообщение-указатель {media_ref, wrapped media_key} обычным путём 3.1
```

Файлы **никогда не проксируются** через бэкенд.

### 3.3. Звонок

```
1. A: POST /calls {peer} → backend создаёт LiveKit room, выдаёт JWT-токены комнаты
2. Push/WS «incoming call» → B принимает → оба подключаются к LiveKit (SRTP)
3. 1:1 — P2P (ICE/TURN), группа — SFU
4. Завершение → webhook LiveKit → история звонков в PG
```

### 3.4. Формирование лент

```
Общая лента (сервер, feed-ranking.md §5):
1. POST /posts → INSERT + post_attributes (declared/approved)
2. Воркер-скоринг: кандидаты (подписки + approved-посты моих атрибутов + популярное
   в моих жанрах) × сигналы ([+]/[−], эмоции) → feed:{user_id} в Redis
3. GET /feed?genre=|attribute= → тематический срез из Redis/PG

Лента друзей (пользовательское курирование, feed-ranking.md §6):
1. Друг добавил пост на ПУБЛИЧНУЮ полку / репостнул на стену
2. Redis Stream shelf_fanout → RPUSH feed_friends:{user_id} каждого его друга
3. GET /feed?tab=friends → хронология с пометкой «⭐ от кого»
```

### 3.5. Ротация группового ключа

```
Триггер (100 сообщений / join / leave) → модуль groups:
1. Инициатор-клиент (админ-устройство) генерирует новый GK
2. wrapped_GK для каждого активного участника + escrow_blob(GK)
3. POST /groups/{id}/keys → запись gk_version, рассылка события key.rotated
4. Исключённые участники не получают wrapped_GK новой версии
```

Сервер **не генерирует** GK — только распределяет (ключи создаются на клиентах).

## 4. Реестр портов и зон доверия

| Зона | Компоненты | Наружу |
|------|-----------|--------|
| Edge | Caddy :443, LiveKit :7881/UDP 50000–60000, TURN :3478/:5349 | да |
| App | Backend (Go) :8080 | нет (только через Caddy) |
| Data | PostgreSQL :5432, Redis :6379, MinIO :9000 | нет |
| Secure | HSM/анклав | нет; доступ только у Escrow-процедуры |

MinIO presigned-ссылки публикуются через поддомен за Caddy (`s3.example.com` → MinIO), сам порт наружу не открыт.

## 5. Наблюдаемость

- Метрики: Prometheus-эндпоинт бэкенда + node_exporter + экспортеры PG/Redis/MinIO; дашборды Grafana.
- Логи: структурированные (JSON) → Loki (или journald на MVP).
- Алерты минимум: недоступность WS, рост латентности доставки, ошибки LiveKit, заполнение диска, отставание воркеров Streams.
- Клиент: встроенный логгер ошибок с отправкой отчётов (с согласия), анонимная продуктовая аналитика — opt-in.
