# ADR-0008: Caddy как входная точка (MVP)

**Статус:** принят · **Дата:** 2026-07-12

## Контекст

В исследованиях фигурировали Nginx, Caddy, HAProxy и Envoy/Kong. Для MVP на VPS нужен один reverse-proxy: TLS, HTTP/REST, WebSocket, раздача presigned-ссылок MinIO, проксирование LiveKit signaling.

## Решение

- **Caddy 2** — единственная входная точка MVP: автоматический TLS (Let's Encrypt), WebSocket из коробки, декларативный Caddyfile, health checks и балансировка upstream'ов (позволяет подложить второй узел бэкенда без простоя).
- Прямые порты наружу помимо Caddy: только LiveKit media (UDP/TCP диапазоны WebRTC) и TURN.

## Отклонено (отложено)

- **Nginx** — рабочая альтернатива, но TLS-автоматизация и конфиг сложнее; выгоды на MVP нет.
- **HAProxy** — его сценарий (балансировка нескольких узлов) покрывается Caddy upstreams.
- **Envoy/Kong** — целевой API-gateway при переходе на микросервисы (mTLS, gRPC, observability); триггер — выделение 3+ отдельных сервисов ([scaling.md](../07-deployment/scaling.md)).

## Последствия

- Конфигурация — в [server-setup.md](../07-deployment/server-setup.md).
- Certificate pinning клиентов настраивается на SPKI серверного сертификата — процедура ротации описывается вместе с деплоем.
