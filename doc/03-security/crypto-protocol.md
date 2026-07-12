# Крипто-протокол (каноническая спецификация)

> **Статус:** каноника. Обоснования и сравнение альтернатив — [messenger-crypto-architecture.md](../messenger-crypto-architecture.md) (legacy overview), решение — [ADR-0004](../adr/0004-controlled-escrow.md).
> Библиотека: **Kodium** ([ADR-0005](../adr/0005-kodium-readiness-gate.md)). Область применения — по [матрице](../01-product/content-security-matrix.md): всё, кроме публичного контента.

## 1. Принцип

> **Ratchet — для PFS. Wrapped keys — для доставки. Escrow — для юридического доступа.**

Три независимых механизма. Контент всегда шифруется конвертом; потеря ratchet-сессии не означает потерю сообщений.

```
Слой 1  Конверт     SecretBox(zstd(protobuf(msg)), message_key)      — всегда
Слой 2  Escrow      ML-KEM-768(Escrow_Public) → wrap(message_key)    — всегда
Слой 3  Ratchet     DoubleRatchet(payload)                            — опционально (PFS, путь A)
Слой 4  Wrapped     Box(ephemeral, device_identity, message_key)      — всегда (план Б, путь B)
```

## 2. Ключи

> **Привязка к Kodium (сверено с исходниками `io.kodium`, 2026-07-12).** Пакет — `io.kodium`, координата Maven — `eu.livotov.labs:kodium`. Одна пара `KodiumPrivateKey` (генерируется `Kodium.generateKeyPair()`) даёт **и** encryption-ключ (X25519, для Box/wrapped), **и** signing-ключ (Ed25519) — оба выводятся из одного 32-байтного seed; публичная часть — `KodiumPublicKey(encryptionKey, signingKey)`. **Все операции возвращают `Result<T>`** — обязательна обработка (`getOrElse`/`fold`), «тихого» исключения нет.

| Ключ | Тип | Где живёт | Назначение |
|------|-----|-----------|-----------|
| Device key pair (`KodiumPrivateKey`) | X25519 + Ed25519 из одного seed | Приватный — Keystore / Secure Enclave (экспорт `exportToEncryptedString`) | encryption-часть — разворачивание wrapped keys; signing-часть — подпись сообщений |
| `message_key` | 32 байта random | Одноразовый, не хранится в открытом виде | Конверт одного сообщения |
| `GK` (Group Key) | 32 байта random | На клиентах участников; версии в `group_key_history` | Личные группы |
| `media_key` | 32 байта random | Внутри encrypted_payload сообщения-указателя | Медиа-объект |
| Escrow key pair | ML-KEM-768 | Public — у клиентов; private — только HSM/анклав | Юридический доступ |
| Ratchet-состояние | X3DH + Double Ratchet | SQLDelight (шифрованный экспорт) | PFS активных сессий |
| `shelf_key` | 32 байта random | Wrapped на устройства владельца и грантополучателей; escrow per версия | Личная полка избранного ([feed-ranking.md](../04-data/feed-ranking.md) §2) |
| `collection_key` / `story_key` | 32 байта random | Wrapped на устройства участников/аудитории; escrow через period/blob | Личные коллекции и истории |
| Ключи ВП (identity + signing) | Curve25519 / Ed25519 | **Только wrapped** на устройства владельца и операторов (не в Keystore); ротация при смене команды/передаче | Виртуальные пользователи ([key-lifecycle.md](./key-lifecycle.md) §8) |

Жизненный цикл (генерация, ротация, отзыв, восстановление) — [key-lifecycle.md](./key-lifecycle.md).

## 3. Личные чаты (1:1)

### 3.1. Формат сообщения

```text
PersonalMessage {
    message_id, chat_id, sender_id, sender_device            // метаданные (plaintext)

    encrypted_payload:    SecretBox(zstd(protobuf(body)), message_key)   // nonce внутри вывода Kodium
    escrow_blob:          MLKEM_ct(1088) ‖ SecretBox(message_key, hkdf(mlkem_shared))
    sender_ephemeral_pub: KodiumPublicKey   // нужен получателю для разворачивания wrapped_key
    ratchet_envelope:     RatchetMessage | null                  // путь A
    signature:            Ed25519(device_signing, canonical_bytes)
}
// Отдельно, в personal_message_keys (по одной на устройство получателя И отправителя):
wrapped_key[device] = Kodium.encrypt(sender_ephemeral_priv, device_public, message_key)
```

### 3.2. Отправка

```kotlin
// Все Kodium-вызовы возвращают Result<T> — .getOrThrow() для краткости
val messageKey = Kodium.generateHighEntropyKey()                         // ByteArray(32)
val plaintext  = zstd(protobuf(body))                                    // сжатие ДО шифрования

val payload = Kodium.encryptSymmetric(messageKey, plaintext).getOrThrow()  // слой 1 (nonce+box)

// слой 2 — escrow. ВНИМАНИЕ порядок: encapsulate возвращает Pair(sharedSecret, ciphertext)
val (kemShared, kemCt) = MLKEM.encapsulate(escrowPublicKey)              // io.kodium.core.MLKEM
val escrowBlob = kemCt + Kodium.encryptSymmetric(hkdf(kemShared), messageKey).getOrThrow()  // ct=1088 B

// слой 4 — обёртки: Kodium.encrypt(myPrivate, theirPublic, data); эфемерная пара отправителя
val wrapped = (recipientDevices + myOtherDevices).associate { dev ->
    val eph = Kodium.generateKeyPair()
    dev.id to Kodium.encrypt(eph, dev.identityPublicKey, messageKey).getOrThrow()  // nonce+box
}
val ratchetEnv = ratchetSession?.encrypt(payload)?.getOrThrow()          // слой 3 (RatchetMessage), если сессия жива
val signature  = Kodium.signDetached(myDeviceKey, canonicalBytes).getOrThrow()  // Ed25519 из device key pair
```

Wrapped keys создаются **и для собственных устройств отправителя** — так работает мультиустройство и история на новом устройстве.

### 3.3. Получение (два пути)

```kotlin
fun decrypt(msg: PersonalMessage): ByteArray {
    msg.ratchetEnvelope?.let { env ->                 // Путь A: ratchet (PFS)
        ratchetSession?.decrypt(env)?.getOrNull()?.let { return it }
    }
    // Путь B: Kodium.decrypt(myPrivate, senderEphemeralPublic, cipher) → Result
    val key = Kodium.decrypt(myDeviceKey, msg.senderEphemeralPublic, msg.wrappedKeyForThisDevice).getOrThrow()
    return unzstd(Kodium.decryptSymmetric(key, msg.encryptedPayload).getOrThrow())
}
```

> `Kodium.decrypt`/`decryptSymmetric` возвращают `Result` (null-исключений нет). Для wrapped-ключа получателю нужен **публичный эфемерный ключ отправителя** — он кладётся в конверт рядом с `wrapped_key` (в `personal_message_keys` или в заголовке сообщения).

Путь B делает `MAX_SKIP`-desync, офлайн любой длительности и переустановку нефатальными.

### 3.4. Ratchet (фаза 5)

- X3DH по PreKey bundle (`prekeys`), **с верификацией Signed PreKey** (обязательное условие gate).
- `DoubleRatchetSession` Kodium; `maxSkippedMessages = 2000`; экспорт состояния — `exportToEncryptedString()` в локальную БД.
- Отказ ratchet — не ошибка доставки: клиент молча падает на путь B и пересоздаёт сессию в фоне.
- PQ-вариант (`PQDoubleRatchetSession`, PQXDH) — после classical, отдельным флагом.

## 4. Личные группы (Sender Keys / GK)

### 4.1. Модель

```
payload      = SecretBox(zstd(protobuf(body)), GK, nonce)          // одно шифрование на сообщение
wrapped_GK   = Box(ephemeral, member_device_identity, GK)          // per устройство, при ротации
escrow_blob  = MLKEM(Escrow_Public) wrap GK                        // ОДИН на версию GK
```

### 4.2. Ротация GK

| Триггер | Действие |
|---------|----------|
| Каждые 100 сообщений | Новая версия GK |
| Вход участника | Немедленная ротация (новичок не читает прошлое) |
| Выход / исключение | Немедленная ротация; исключённым wrapped_GK не выдаётся |
| Компрометация устройства | Ротация всех групп участника |

GK генерирует **клиент-инициатор** (админ-устройство), не сервер. Сервер распределяет `wrapped_GK` и хранит `group_key_history`. Старые версии GK остаются у участников для чтения истории; на сервере старые wrapped_GK архивируются (TTL 30 дней после исключения — окно апелляции).

### 4.3. `GroupKeyManager` (модуль `messenger-crypto`)

```kotlin
class GroupKeyManager {
    fun rotate(groupId: UUID, devices: List<DeviceIdentity>): GroupKeyRotation {
        val gk = Kodium.generateHighEntropyKey()
        val wrapped = devices.associate {
            it.deviceId to Kodium.encrypt(Kodium.generateKeyPair(), it.identityPub, gk)
        }
        return GroupKeyRotation(gkVersion + 1, wrapped, escrowModule.wrap(gk))
    }
}
```

## 5. Медиа

Единый паттерн для фото, голосовых, видео, файлов ([media-storage.md](../04-data/media-storage.md)):

```
1. plaintext → (публичное: SHA-256 для CAS) → media_key = random(32)
2. ciphertext = SecretBox(plaintext, media_key)          // < 10 MB целиком
   большие файлы: chunk_key[i] = HKDF(media_key, "chunk:i"); SecretBox по чанкам
3. upload ciphertext → MinIO (presigned, мимо бэкенда)
4. Сообщение-указатель: {media_ref, метаданные} внутри обычного конверта §3;
   media_key передаётся внутри encrypted_payload (или wrapped отдельно)
```

- Голосовые: запись → Opus → этот же паттерн. **Не** LiveKit ([ADR-0006](../adr/0006-livekit-media-policy.md)).
- CAS-дедупликация по SHA-256 plaintext — **только публичные медиа** (для приватных — утечка «файл уже есть в системе»; opt-in запрещён на MVP).

## 6. Escrow

- На каждый `message_key` (1:1) или версию `GK`/`period_id` (группы, медиа) создаётся `escrow_blob` — ML-KEM-768 инкапсуляция на `Escrow_Public`.
- Приватный ключ escrow существует **только** в HSM/анклаве; доступ M-of-N (Shamir) по юридическому запросу, каждый доступ — в append-only audit log.
- MVP: stub-анклав (изолированный контейнер с тем же API); production HSM — gate фазы 6.
- Процедуры, политика периодов и формулировки для пользователей — [escrow-legal-access.md](./escrow-legal-access.md).

## 7. Подписи и целостность

- Каждое сообщение подписано Ed25519-ключом устройства (`canonical_bytes` = детерминированная сериализация полей без подписи).
- Сервер проверяет подпись при приёме (публичный ключ из `devices`), клиенты — при получении.
- **Исключение — сообщения от имени сущности** (боты/система, `sender_type='entity'`, только публичные группы): клиентской подписи нет, аутентичность гарантирует сервер (bot token + HMAC, [bot-api.md](../05-api/bot-api.md) §4); в защищённом контуре таких сообщений не бывает.
- Смена identity-ключа собеседника → предупреждение в UI (safety number, [16-profile-popup](../doc_UI/16-profile-popup.md)).

## 8. Транспорт

TLS 1.3 + certificate pinning (SPKI) на всех соединениях (REST, WS, LiveKit signaling). TLS — дополнение, не замена клиентского шифрования: компрометация сервера раскрывает только ciphertext + метаданные.

## 9. Форматы и сериализация

- Все конверты — **Protobuf** (schema-репозиторий, версионирование через поля `oneof` + `reserved`).
- Сжатие **zstd** строго до шифрования (после — бессмысленно).
- Кодировка при передаче — бинарная (BYTEA/base64url в JSON REST — только для отладки).
- Тест-векторы всех форматов — обязательная часть `messenger-crypto` (gate).

## 10. Что сервер знает и чего не знает

| Сервер видит | Сервер не видит |
|--------------|-----------------|
| chat_id, sender, timestamps, размеры | Текст и медиа защищённых чатов |
| Факт и тип сообщения (text/voice/…) | message_key, GK, media_key |
| Граф контактов, подписки | Ratchet-состояния |
| wrapped keys (не может развернуть) | Приватные ключи устройств |
| escrow_blob (не может развернуть без HSM M-of-N) | |
