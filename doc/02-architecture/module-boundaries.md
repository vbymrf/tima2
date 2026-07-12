# Границы модулей (группы · каналы · аудио-чаты · сообщества)

> Каноническое разделение доменов. Причина: функционал этих сущностей будет правиться регулярно — правки одной сущности не должны задевать соседние. Разделение **полное**: свои таблицы, свои API-неймспейсы, свои модули бэкенда и клиента.

## 1. Принцип: разделение по функции

| Модуль | Суть | Владеет таблицами | API |
|--------|------|-------------------|-----|
| `group` | **Переписка** | `groups`, `group_messages`, `group_key_history`, `user_wrapped_keys` | `/groups/*` |
| `channel` | **Публикации** (постов без чат-потока) | `channels` | `/channels/*` |
| `voiceroom` | **Live-аудио** | `voice_rooms` | `/voice-rooms/*` |
| `community` | **Контейнер** (состав, доступ, подписка, витрина) | `communities` | `/communities/*` |

Контент канала — **только посты** (создаются редактором, [34-content-editor](../doc_UI/34-content-editor.md)); обсуждение поста — комментарии. Чат-поток есть только у групп.

## 2. Общие подсистемы (полиморфные)

Механики, нужные нескольким сущностям, выносятся в отдельные модули с привязкой `target_type/target_id` — сущности зависят от них, но не друг от друга:

| Подсистема | Владеет | Кто использует |
|-----------|---------|----------------|
| `membership` | `memberships` (роли: owner/admin/moderator/author/member, ban) | group, channel, voiceroom, community |
| `publications` | `posts` (body + **entities**), `post_drafts`, отложенная публикация | channel (посты канала), личная страница/медиа-лента (посты пользователя), блогер (alias) |
| `comments` | `comments` | posts, публичные медиа, публичные коллекции |
| `reactions` | `emotions`, `rating_counters`, `recommendations` | всё, что оценивается |
| `attributes` | `attributes` (= хэштеги), `genres`, `post_attributes`, `user_attributes` | publications, feeds, search ([feed-ranking.md](../04-data/feed-ranking.md)) |
| `social_inbox` | `inbox_threads`, `inbox_events`, `social_inbox_preferences` (окно 4) | messages (обращения к ВП), reactions, membership ([virtual-users.md](../01-product/virtual-users.md)) |
| `invites` | `invites` (target: group/channel/community) | group, channel, community |
| `catalog` | `chat_folders`, `chat_folder_items` | окна 2–3 (каталог), папки |
| `feeds` | fan-out (Redis), выдача лент | publications, subscriptions |
| `reports` | `reports` | модерация всего |

## 3. Правила зависимостей

```
            ┌───────────────────────────────────────────┐
            │ Сущности:  group   channel   voiceroom     │   ← НЕ зависят друг от друга
            │                └───────┬───────┘           │
            │ community ─── знает только (type, id) ─────│   ← контейнер, без знания внутренностей
            └───────────────┬───────────────────────────┘
                            ▼
            Общие подсистемы: membership · publications · comments ·
            reactions · invites · catalog · feeds · reports
                            ▼
            Инфраструктура: PG · Redis · MinIO · LiveKit
```

1. **Сущности не импортируют друг друга.** Связь «аудио-чат прикреплён к группе» — это `attached_type/attached_id` (слабая ссылка), а не вызов модуля.
2. **Community оперирует только `(target_type, target_id, community_access)`** — не знает, что внутри элемента.
3. Общие подсистемы не знают о конкретных сущностях — только `target_type` как строка.
4. Кросс-модульные сценарии (создание аудио-чата с авто-сообществом) — на уровне application-сервиса (wizard), не внутри модулей.

## 4. Проекция на код

**Бэкенд (Go, модульный монолит):** пакет на модуль (`internal/group`, `internal/channel`, `internal/voiceroom`, `internal/community`, `internal/publications`, …). Каждый пакет: свои handlers, своя схема миграций, экспортирует узкий интерфейс. Импорт между пакетами сущностей запрещён линтером (depguard/ревью).

**Клиент (KMP):** фич-модули `feature-group`, `feature-channel`, `feature-voiceroom`, `feature-community`, `feature-editor` + `core-*` (общие подсистемы). Границы контролирует **Konsist** в CI (правило: feature-модули не зависят друг от друга, только от core).

**API:** отдельные неймспейсы ([api-overview.md](../05-api/api-overview.md)); версии эволюционируют независимо.

## 5. Формат контента постов: plain text + entities

Решение (обсуждено 2026-07-12): контент постов — **текст + массив entities** (Telegram-style), не Markdown и не JSON-блоки.

```json
{
  "body": "Релиз 2.0\nГлавные изменения…",
  "entities": [
    {"type": "heading",  "offset": 0,  "length": 9},
    {"type": "bold",     "offset": 10, "length": 7},
    {"type": "link",     "offset": 30, "length": 12, "url": "https://…"},
    {"type": "mention",  "offset": 50, "length": 8,  "user_id": "…"},
    {"type": "media",    "offset": 70, "length": 1,  "media_id": "…"}
  ]
}
```

Типы entities (MVP): `bold`, `italic`, `underline`, `strikethrough`, `code`, `quote`, `heading`, `list_item`, `link{url}`, `mention{user_id}`, `hashtag`, `media{media_id}` (вставка медиа в текст статьи; занимает 1 символ-заглушку `￼`). Расширение списка — обратносовместимо: неизвестный тип рендерится как обычный текст.

Сообщений чатов это не касается — их формат остаётся Protobuf внутри конверта ([crypto-protocol.md](../03-security/crypto-protocol.md) §9); entities могут использоваться внутри protobuf-body сообщений позднее тем же словарём типов.

## 6. Что где НЕ живёт (частые ошибки)

- Комментарии — не в канале и не в медиа-ленте: только модуль `comments`.
- Посты канала — не в `group_messages`: только `posts` (author_type='channel').
- Роли — не в таблицах сущностей: только `memberships`.
- Редактор — один (`feature-editor`), режимы: пост канала / статья / медиа-пост / история.
- Подписчики — не в `memberships`: подписка это `subscriptions`; membership — роли управления/членство в личной группе.
