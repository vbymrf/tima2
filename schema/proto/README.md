# Крипто-схемы (контракт wire-формата)

> Источник истины для байтов на проводе. Реализация (`messenger-crypto` на Kotlin, серверный Go) генерируется/сверяется с этими схемами. Меняется только через PR со схемой + проверку обратной совместимости (см. ниже). Семантика — [03-security/crypto-protocol.md](../../doc/03-security/crypto-protocol.md).

## Файлы

| Файл | Что описывает |
|------|---------------|
| [envelope.proto](./envelope.proto) | `Envelope` — конверт личного сообщения (метаданные + ciphertext + wrapped keys + подпись) |
| [message_body.proto](./message_body.proto) | `MessageBody` — открытый текст ДО сжатия/шифрования (text + entities + media) |

## Конвейер шифрования (как схемы соединяются)

```
MessageBody ──protobuf──► bytes ──zstd──► Kodium.encryptSymmetric(message_key) ──► Envelope.encrypted_payload
message_key ──► ML-KEM(escrow_pub) ────────────────────────────────────────────► Envelope.escrow
message_key ──► Kodium.encrypt(ephemeral, device_pub) [× устройства] ───────────► Envelope.wrapped_keys[]
canonical_bytes(Envelope) ──► Kodium.signDetached(device_key) ──────────────────► Envelope.signature
```

## `canonical_bytes` — что подписывается (строго)

Подпись Ed25519 берётся не от protobuf-сериализации (она не детерминирована между реализациями), а от **явной конкатенации** с длинными префиксами. Все целые — **little-endian**; строки — UTF-8; длинные префиксы — `uint32 LE`.

```
canonical_bytes =
    u32(format_version)
  ⊕ u64(meta.message_id)
  ⊕ lp(meta.chat_id)             // lp(x) = u32(len(x)) ⊕ x
  ⊕ lp(meta.sender_id)
  ⊕ lp(meta.sender_device)
  ⊕ u32(meta.kind)               // числовое значение enum
  ⊕ u64(meta.created_at_unix_ms)
  ⊕ u64(meta.reply_to)
  ⊕ sha256(encrypted_payload)
  ⊕ sha256(escrow.mlkem_ct ⊕ escrow.wrapped_message_key)
  ⊕ sha256(sender_ephemeral_pub)
  ⊕ sha256(ratchet_envelope)     // sha256(пустых байт), если поля нет
```

- `⊕` — конкатенация байтов; `sha256` — 32 байта.
- Подписываются **хэши** ciphertext-блобов (компактно и стабильно), не сами блобы.
- `wrapped_keys[]` в подпись **не входят**: они per-recipient и управляются сервером (план Б). Их целостность обеспечивается тем, что развёрнутый из них `message_key` обязан корректно открыть подписанный `encrypted_payload` (MAC SecretBox). Подмена wrapped_key ведёт к провалу расшифровки, не к принятию поддельного текста.
- Порядок и состав полей **фиксированы** — изменение = новый `format_version` и новый тест-вектор.

## `group_message_canonical_bytes` — подпись сообщения группы

Сообщение группы ходит без конверта `Envelope` (нет per-message escrow и wrapped_keys — GK у участников, escrow один на версию GK; crypto-protocol.md §4.1). `payload` — тот же `MessageBody` конвейер: private-группа `SecretBox(zstd(protobuf(body)), GK)`, публичная — plaintext protobuf. Подписывается:

```
group_message_canonical_bytes =
    lp("tima.group_message.v1")    // доменная метка: не пересекается с canonical_bytes конверта
  ⊕ lp(group_id)
  ⊕ lp(sender_id)
  ⊕ lp(sender_device)
  ⊕ u32(kind)                     // ContentKind из envelope.proto
  ⊕ u64(created_at_unix_ms)
  ⊕ u64(thread_root)              // 0 = сообщение вне ветки
  ⊕ u64(reply_to)                 // 0 = нет
  ⊕ u32(gk_version)               // 0 = публичная группа (plaintext)
  ⊕ sha256(payload)
```

`message_id` в preimage не входит — его назначает сервер при приёме. Версию раскладки несёт доменная метка (`…v1`); изменение = новая метка + новый тест-вектор (`group_message_canonical`).

## Правила эволюции (обратная совместимость)

1. **Никогда** не менять номер и тип существующего поля. Удаляемое → `reserved`.
2. Новые поля — только новые номера, семантика «отсутствует = дефолт».
3. `format_version` растёт лишь при несовместимой смене раскладки `canonical_bytes` или конвейера.
4. Каждое изменение сопровождается обновлением тест-векторов ([../test-vectors/](../test-vectors/)); CI гоняет их против обеих реализаций (Kotlin/Go) — расхождение = красный билд.
5. Единицы `Entity.offset/length` — **UTF-16 code units** (строки Kotlin/Compose). Зафиксировано, чтобы избежать классического interop-бага с эмодзи/суррогатами.

## Тулинг (фаза 0)

`protoc` + плагины: `protoc-gen-go` (сервер), Wire или `protoc` kotlin (клиент). Генерация — из этого каталога; коммитятся `.proto`, генерённый код — артефакт сборки. См. [ADR-0009](../../doc/adr/0009-schema-first-api.md).
