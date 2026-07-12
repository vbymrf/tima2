# Развертывание серверов (VPS + docker-compose)

> Пошаговая инструкция поднятия полного стека MVP на одном VPS. Решения: [ADR-0008 Caddy](../adr/0008-caddy-edge.md), [ADR-0003 PostgreSQL](../adr/0003-postgresql-storage.md). Масштабирование — [scaling.md](./scaling.md).

## 1. Требования

| Ресурс | Минимум (dev/бета) | Рекомендуется |
|--------|--------------------|---------------|
| VPS | 4 vCPU, 8 ГБ RAM, 160 ГБ NVMe | 8 vCPU, 16 ГБ RAM, 500 ГБ NVMe |
| ОС | Ubuntu 24.04 LTS | — |
| Сеть | Публичный IPv4, без NAT провайдера | + IPv6 |
| DNS | A-записи: `api.`, `s3.`, `lk.`, `turn.example.com` → IP VPS | |

LiveKit требует реального публичного IP (WebRTC): убедитесь, что UDP не фильтруется хостером.

## 2. Подготовка VPS

```bash
# Пользователь и базовая гигиена
adduser deploy && usermod -aG sudo deploy
# SSH: только ключи
sed -i 's/#PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config
systemctl restart ssh

# Firewall
ufw allow OpenSSH
ufw allow 80,443/tcp                 # Caddy (HTTP для ACME, HTTPS)
ufw allow 443/udp                    # HTTP/3
ufw allow 7881/tcp                   # LiveKit signaling fallback (TCP)
ufw allow 50000:60000/udp            # LiveKit WebRTC media
ufw allow 3478/udp                   # TURN
ufw allow 5349/tcp                   # TURN TLS
ufw enable

# Docker
curl -fsSL https://get.docker.com | sh
usermod -aG docker deploy

# Автообновления безопасности
apt install unattended-upgrades && dpkg-reconfigure -plow unattended-upgrades
```

## 3. Структура на сервере

```
/opt/tima/
├── docker-compose.yml
├── .env                    # секреты (chmod 600, в git не попадает)
├── caddy/Caddyfile
├── livekit/livekit.yaml
└── volumes/                # postgres/ redis/ minio/ caddy/
```

## 4. `.env` (шаблон)

```dotenv
POSTGRES_USER=tima
POSTGRES_PASSWORD=<openssl rand -hex 24>
POSTGRES_DB=tima
REDIS_PASSWORD=<openssl rand -hex 24>
MINIO_ROOT_USER=tima-admin
MINIO_ROOT_PASSWORD=<openssl rand -hex 24>
LIVEKIT_API_KEY=<openssl rand -hex 16>
LIVEKIT_API_SECRET=<openssl rand -hex 32>
JWT_SIGNING_KEY=<openssl rand -hex 32>
SMS_PROVIDER_KEY=...
FCM_SERVICE_ACCOUNT_JSON=/run/secrets/fcm.json
APNS_KEY_PATH=/run/secrets/apns.p8
DOMAIN=example.com
```

Ротация серверных секретов — каждые 90 дней ([key-lifecycle.md](../03-security/key-lifecycle.md) §7). При росте — вынести в Vault/SOPS.

## 5. `docker-compose.yml`

```yaml
name: tima

services:
  caddy:
    image: caddy:2
    restart: unless-stopped
    ports: ["80:80", "443:443", "443:443/udp"]
    volumes:
      - ./caddy/Caddyfile:/etc/caddy/Caddyfile:ro
      - ./volumes/caddy:/data
    depends_on: [backend, minio]

  backend:
    image: ghcr.io/<org>/tima-backend:latest   # Go, один бинарник (модульный монолит)
    restart: unless-stopped
    env_file: .env
    environment:
      DATABASE_URL: postgres://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/${POSTGRES_DB}
      REDIS_URL: redis://:${REDIS_PASSWORD}@redis:6379
      S3_ENDPOINT: http://minio:9000
      LIVEKIT_URL: http://livekit:7880
    depends_on:
      postgres: { condition: service_healthy }
      redis:    { condition: service_healthy }
      minio:    { condition: service_started }
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/healthz"]
      interval: 15s
      retries: 5

  worker:                                       # fan-out лент, push-очередь, GC медиа
    image: ghcr.io/<org>/tima-backend:latest
    command: ["/app/tima", "worker"]
    restart: unless-stopped
    env_file: .env
    environment:
      DATABASE_URL: postgres://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/${POSTGRES_DB}
      REDIS_URL: redis://:${REDIS_PASSWORD}@redis:6379
      S3_ENDPOINT: http://minio:9000
    depends_on: [backend]

  postgres:
    image: postgres:16
    restart: unless-stopped
    environment:
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_DB: ${POSTGRES_DB}
    volumes: ["./volumes/postgres:/var/lib/postgresql/data"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER}"]
      interval: 10s
      retries: 10
    shm_size: 256mb

  redis:
    image: redis:7
    restart: unless-stopped
    command: ["redis-server", "--requirepass", "${REDIS_PASSWORD}", "--appendonly", "yes"]
    volumes: ["./volumes/redis:/data"]
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "${REDIS_PASSWORD}", "ping"]
      interval: 10s
      retries: 10

  minio:
    image: minio/minio:latest
    restart: unless-stopped
    command: ["server", "/data", "--console-address", ":9001"]
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    volumes: ["./volumes/minio:/data"]

  livekit:
    image: livekit/livekit-server:latest
    restart: unless-stopped
    network_mode: host              # WebRTC: реальные UDP-порты и внешний IP
    command: ["--config", "/etc/livekit.yaml"]
    volumes: ["./livekit/livekit.yaml:/etc/livekit.yaml:ro"]
```

> **Escrow-stub** (фазы 0–5) — отдельный изолированный контейнер добавляется в фазе 1; production HSM/Nitro — вне этого compose ([escrow-legal-access.md](../03-security/escrow-legal-access.md) §7). **Egress отсутствует намеренно** ([recording-policy.md](../06-realtime/recording-policy.md)).

## 6. `caddy/Caddyfile`

```caddyfile
api.{$DOMAIN} {
    reverse_proxy /ws  backend:8080     # WebSocket — Caddy проксирует автоматически
    reverse_proxy /api/* backend:8080
    reverse_proxy /bot/* backend:8080   # Bot API (bot-api.md) — тот же монолит, модуль bot_gateway
    encode zstd gzip
    header {
        Strict-Transport-Security "max-age=31536000; includeSubDomains"
        -Server
    }
}

s3.{$DOMAIN} {
    reverse_proxy minio:9000            # presigned URL клиентов ходят сюда
    request_body { max_size 2GB }
}

lk.{$DOMAIN} {
    reverse_proxy localhost:7880        # LiveKit signaling (host network)
}
```

TLS-сертификаты Caddy получает и продлевает сам (Let's Encrypt). SPKI-пиннинг клиентов делать на **корневой/промежуточный** сертификат или собственный leaf-план с процедурой ротации — зафиксировать до релиза.

## 7. `livekit/livekit.yaml`

```yaml
port: 7880
rtc:
  tcp_port: 7881
  port_range_start: 50000
  port_range_end: 60000
  use_external_ip: true
keys:
  <LIVEKIT_API_KEY>: <LIVEKIT_API_SECRET>
turn:
  enabled: true
  domain: turn.example.com
  tls_port: 5349
  udp_port: 3478
```

## 8. Первый запуск

```bash
cd /opt/tima
docker compose up -d postgres redis minio        # база
docker compose run --rm backend /app/tima migrate up    # миграции (data-model.md)

# MinIO: bucket'ы и lifecycle
docker compose exec minio mc alias set local http://localhost:9000 $MINIO_ROOT_USER $MINIO_ROOT_PASSWORD
docker compose exec minio mc mb local/media local/previews
docker compose exec minio mc ilm rule add local/media --transition-days 180 --transition-tier COLD   # cold-ярус (media-storage.md §6)

docker compose up -d                              # всё остальное
docker compose ps                                 # все healthy?
curl -fsS https://api.example.com/api/v1/healthz  # smoke-тест через Caddy
```

**Smoke-чеклист:** healthz через TLS · WS-подключение (`wscat`) · presigned upload в `s3.` · создание LiveKit-комнаты через API · регистрация тестового пользователя (SMS-провайдер в sandbox).

## 9. Обновление без простоя

```bash
docker compose pull backend worker
docker compose up -d --no-deps backend worker    # Caddy ретраит на время рестарта
docker compose run --rm backend /app/tima migrate up   # миграции только backward-compatible
```

Правило миграций: сначала выкладывается код, читающий обе схемы, затем миграция, затем очистка — никаких деструктивных изменений в одном релизе.

## 10. Резервное копирование

| Что | Как | Частота | Хранение |
|-----|-----|---------|----------|
| PostgreSQL | `pg_dump -Fc` → offsite (S3 другого провайдера, restic/rclone) | ежедневно + WAL-архив (при росте — pgBackRest) | 30 дней |
| MinIO | `mc mirror` на offsite bucket | ежедневно | 30 дней |
| Redis | AOF включён; допустимо потерять (кэш/очереди восстановимы) | — | — |
| `.env`, конфиги | зашифрованная копия (age/SOPS) вне сервера | при изменении | — |

**Проверка восстановления — раз в квартал** (тестовый restore на чистую VM). Цели: RPO 24 ч, RTO 4 ч (NFR).

## 11. Мониторинг

Минимальный набор (добавить в compose при фазе 5): Prometheus + Grafana + node_exporter + postgres_exporter + redis_exporter; LiveKit отдаёт `/metrics` сам. Алерты: диск > 80 %, отставание воркера Streams, error rate 5xx, недоступность WS, истечение сертификатов (Caddy сам продлевает — алерт на сбой продления).

## 12. Частые проблемы

| Симптом | Причина |
|---------|---------|
| Звонок соединяется, но нет звука | UDP 50000–60000 закрыт / `use_external_ip` не включён |
| WS рвётся каждые ~60 с | Прокси-таймаут: убедиться, что путь `/ws` идёт через `reverse_proxy` Caddy (таймауты по умолчанию ок), а не через сторонний CDN |
| Presigned PUT → 403 | Расхождение часов клиента/сервера или неверный `S3_ENDPOINT` (подпись содержит хост: снаружи должен быть `s3.example.com`) |
| ACME не выдаёт сертификат | Порт 80 закрыт или DNS ещё не расползся |
| LiveKit не стартует в compose | Занят порт из host-диапазона; проверить `network_mode: host` конфликты |
