# Звонки и голосовые комнаты (LiveKit)

> Медиа-политика: [ADR-0006](../adr/0006-livekit-media-policy.md). UI-состояния: [21-call](../doc_UI/21-call.md), [07-voice-chat](../doc_UI/07-voice-chat.md). Запись — [recording-policy.md](./recording-policy.md).

## 1. Роли компонентов

| Компонент | Роль |
|-----------|------|
| LiveKit Server (self-hosted) | SFU: комнаты, треки, адаптивный битрейт, simulcast |
| Встроенный TURN (+ отдельный при необходимости) | Прохождение NAT |
| Backend `calls` | Создание комнат, JWT-токены доступа, ring-логика, история |
| Клиент (LiveKit SDK, expect/actual) | Захват микрофона/камеры, рендер, управление |

Kodium в звонках используется только для identity/подписей signaling-запросов; медиапоток защищён SRTP — это транспортная защита, **не app-E2E**, и UI это не заявляет.

## 2. Топология

- **1:1** — P2P напрямую (ICE; TURN как fallback). SFU подключается только если P2P не установился.
- **Группа / голосовой чат** — всегда SFU. Лимит MVP: 20 участников (NFR).
- Одновременно один активный сеанс на пользователя (окно 0, [01-app-shell](../doc_UI/01-app-shell.md)).

## 3. Поток звонка 1:1

```
A: POST /calls {peer_id, kind}        → room + токены (JWT LiveKit, TTL 2 мин на подключение)
Сервер → B: WS call.incoming + push (VoIP push на iOS)
B: POST /calls/{id}/answer            → B подключается к room
Оба в room → call.state answered      → таймер разговора
Завершение любым → POST /calls/{id}/end → LiveKit webhook → history (outcome)
Не ответил за 40 с → missed + push «пропущенный»
```

Голосовой чат группы: комната живёт постоянно (`voice-rooms/{group_id}/join`), участники входят/выходят свободно; спикеры/слушатели — роли в токене.

## 4. Токены и безопасность

- LiveKit JWT подписывается ключом API LiveKit **на бэкенде**; клиент секрета не знает.
- Grants: room, identity (user_id + device), canPublish/canSubscribe по роли.
- Signaling (WS LiveKit) — через TLS; media UDP-диапазон открыт наружу ([server-setup.md](../07-deployment/server-setup.md)).
- Экран «демонстрация» — отдельный трек с пометкой; в группах — по правам роли.

## 5. Деградация сети

- Simulcast + динамическое качество (SFU выбирает слой по downlink подписчика).
- Потеря соединения → авто-reconnect в ту же комнату (grace 30 с, для собеседника — «связь потеряна…»).
- Падение LiveKit-узла → комната пересоздаётся, клиенты получают `call.state reconnect` с новым токеном (MVP: один узел, это полный обрыв — честный `ended`).

## 6. Будущее (не MVP)

- LiveKit E2EE (insertable streams) для приватных звонков — ортогонально Kodium, потребует поддержки во всех клиентах.
- Масштабирование SFU: региональные узлы LiveKit + маршрутизация по латентности ([scaling.md](../07-deployment/scaling.md)).
