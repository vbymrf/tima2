# UI-ТЗ: индекс экранов

> **Формат:** WireMD (strict). **Стек:** KMP + Compose Multiplatform.
> **Навигация:** 5 базовых окон + временное окно `0` (активный звонок/аудиочат) + блогерские окна 6–7.
> **Архитектура:** см. [doc/README.md](../README.md) · [content-security-matrix.md](../01-product/content-security-matrix.md)

## Связь UI ↔ backend ↔ crypto

| UI экран | API / realtime | Crypto / media |
|----------|----------------|----------------|
| [03-personal-chat](./03-personal-chat.md) | `POST /messages`, WS `message.new` | Kodium envelope + escrow |
| [19-attachments-media](./19-attachments-media.md) | `POST /media/init` | Client encrypt → MinIO |
| [21-call](./21-call.md) | `POST /calls`, LiveKit | SRTP, не app E2E ([ADR-0006](../adr/0006-livekit-media-policy.md)) |
| [17-global-search](./17-global-search.md) | OpenSearch (public) | Local FTS (private) |
| [24-device-linking](./24-device-linking.md) | `POST /link/*` | Wrapped keys per device |

## Базовая оболочка

| Файл | Экран |
|------|-------|
| [01-app-shell.md](./01-app-shell.md) | Оболочка приложения, переключение окон, глобальные правила |

## Окно 1 — Телефон

| Файл | Экран |
|------|-------|
| [02-phone-home.md](./02-phone-home.md) | Телефонная книга, список личных чатов, история звонков |
| [03-personal-chat.md](./03-personal-chat.md) | Личный чат 1-на-1 (E2E) |

## Окно 2 — Новостная лента

| Файл | Экран |
|------|-------|
| [04-news-window.md](./04-news-window.md) | Общая лента, лента друзей, каталог |
| [05-group-chat.md](./05-group-chat.md) | Групповой чат (E2E / публичный) |
| [06-channel.md](./06-channel.md) | Канал |
| [07-voice-chat.md](./07-voice-chat.md) | Аудио-чат (только внутри сообщества) |

## Окно 3 — Медиа-лента

| Файл | Экран |
|------|-------|
| [08-media-window.md](./08-media-window.md) | Режим «Лента» |
| [09-media-slides.md](./09-media-slides.md) | Режим «Слайды» |

## Окно 4 — Социальное взаимодействие

| Файл | Экран |
|------|-------|
| [10-social-interaction.md](./10-social-interaction.md) | Агрегатор: Входящие (managed inbox ВП), Мои треды, Реакции, Коллекции |
| [11-stories.md](./11-stories.md) | Истории |
| [12-activity-reactions.md](./12-activity-reactions.md) | Комментарии и оценки |
| [13-collections.md](./13-collections.md) | Коллекции |

## Окно 5 — Личная страница

| Файл | Экран |
|------|-------|
| [14-personal-page.md](./14-personal-page.md) | Профиль, избранное, подписки, роли |

## Общие механики

| Файл | Экран |
|------|-------|
| [15-comments.md](./15-comments.md) | Окно комментариев |
| [16-profile-popup.md](./16-profile-popup.md) | Окно переходов / профиль |
| [17-global-search.md](./17-global-search.md) | Глобальный поиск |
| [18-content-actions.md](./18-content-actions.md) | Панель действий (+/−, эмоции, ответ, пересылка) |
| [19-attachments-media.md](./19-attachments-media.md) | Вложения, плеер, голосовые |
| [20-share-repost.md](./20-share-repost.md) | Пересылка и репост |

## Звонки и доступ

| Файл | Экран |
|------|-------|
| [21-call.md](./21-call.md) | Все состояния звонка |
| [22-auth-registration.md](./22-auth-registration.md) | Регистрация и вход |
| [23-auth-recovery.md](./23-auth-recovery.md) | Восстановление, резервные коды |
| [24-device-linking.md](./24-device-linking.md) | QR-привязка Windows через телефон |

## Настройки и специализированные окна

| Файл | Экран |
|------|-------|
| [25-settings-help-bugs.md](./25-settings-help-bugs.md) | Настройки, помощь, баги |
| [26-notifications.md](./26-notifications.md) | Уведомления (3 уровня) |
| [27-security-privacy.md](./27-security-privacy.md) | Безопасность, E2E, блокировка |
| [28-qr-invites.md](./28-qr-invites.md) | QR и инвайт-ссылки |
| [29-blogger-media-window.md](./29-blogger-media-window.md) | Окно 6 — медиа-блогер |
| [30-blogger-news-window.md](./30-blogger-news-window.md) | Окно 7 — новостной блогер |

## Сообщества и создание

| Файл | Экран |
|------|-------|
| [32-community.md](./32-community.md) | Страница сообщества, админ-режим |
| [33-create-group-channel.md](./33-create-group-channel.md) | Мастер создания: группа / канал / аудио-чат / сообщество |
| [34-content-editor.md](./34-content-editor.md) | Редактор контента: пост канала / статья / медиа-пост / история |
| [35-attributes-genres.md](./35-attributes-genres.md) | Атрибуты (= хэштеги), жанры, чипсы лент, карточка атрибута |

## Адаптация

| Файл | Экран |
|------|-------|
| [31-adaptive-layout.md](./31-adaptive-layout.md) | Телефон, планшет, десктоп |

---

> **Архитектура и деплой:** [doc/README.md](../README.md) — навигация по всей документации проекта.
