# Хранение медиа

> MinIO (S3 API) — все бинарные объекты; PostgreSQL — только метаданные (`media_objects`, [data-model.md](../02-architecture/data-model.md) §6). Шифрование медиа — [crypto-protocol.md](../03-security/crypto-protocol.md) §5. UI: [19-attachments-media](../doc_UI/19-attachments-media.md).

## 1. Правила

1. Файлы **не проксируются** через бэкенд — только presigned URL, клиент ходит в MinIO напрямую (через поддомен за Caddy).
2. Приватное медиа приходит в хранилище **только как ciphertext**; сервер не может валидировать содержимое — валидируются размер, квота, mime-заявка.
3. Публичное медиа — plaintext, обрабатывается сервером (превью, транскодирование — пост-MVP).
4. Лимит файла: 2 ГБ (NFR).

## 2. Протокол загрузки

```
1. Клиент: подготовка
   публичное:  compress/transcode → SHA-256(plaintext) для CAS
   приватное:  media_key = random(32) → SecretBox (chunked при > 10 MB)
2. POST /media/init {size, mime, is_encrypted, content_hash?}
   → { upload: presigned PUT | multipart } либо { dedup: media_id }  // CAS, только публичное
3. Клиент → MinIO: PUT (фоновая очередь, retry, докачка multipart)
4. POST /media/complete {media_id} → запись метаданных, квоты
5. media_ref уходит в сообщение-указатель обычным конвертом
```

Скачивание: `GET /media/{id}/url` → presigned GET (TTL 10 мин); приватное клиент расшифровывает локально, ключ — из сообщения.

## 3. Chunked-шифрование (файлы > 10 MB)

```
chunk_size = 4 MB
chunk_key[i] = HKDF(media_key, info = "chunk:" + i)
chunk_ct[i]  = SecretBox(chunk[i], chunk_key[i])
```

- Заливка — S3 multipart по чанкам; манифест (chunk_count, chunk_size, размеры) — в метаданных сообщения.
- Скачивание и расшифровка потоковые: RAM не держит весь файл; видео-плеер может начинать с нужного чанка.

## 4. Превью

- Превью (WebP, ≤ 50 KB) генерирует **клиент** до отправки; для приватного шифруется отдельным ключом `HKDF(media_key, "preview")` и часто встраивается прямо в конверт сообщения (мгновенный показ в чате).
- Blur-hash строки — в метаданных сообщения (плейсхолдер до загрузки).

## 5. CAS-дедупликация (только публичное)

- Ключ — SHA-256 plaintext; повторная заливка того же файла → ссылка на существующий объект.
- Для приватного медиа CAS отключён: хэш открытого файла в БД — утечка «этот файл уже был» ([ADR-0004](../adr/0004-controlled-escrow.md), риски).

## 6. Жизненный цикл (hot/warm/cold)

| Ярус | Данные | Реализация |
|------|--------|-----------|
| hot | < 30 дней, активные превью | MinIO основной bucket (SSD) |
| warm | 30–180 дней | S3 Lifecycle → bucket на HDD (при разделении дисков) |
| cold | > 180 дней | Lifecycle: сжатие/архивный класс; latency допустимо выше |

На однохостовом MVP ярусы — это только политики Lifecycle + `tier` в метаданных; физическое разделение появляется при масштабировании ([scaling.md](../07-deployment/scaling.md)).

Удаление: `deleted` сообщения → media garbage collector (фоновая джоба: объект без живых ссылок старше N дней → удаление из MinIO; escrow-retention учитывается для приватного, [escrow-legal-access.md](../03-security/escrow-legal-access.md) §5).

## 7. Кэш на клиенте

- Автосохранение в локальный кэш с настраиваемым лимитом (по умолчанию 2 ГБ) и автоочисткой LRU.
- «Скачать для офлайна» — пост/коллекция целиком pin'уется и не вытесняется ([sync-offline.md](./sync-offline.md)).
