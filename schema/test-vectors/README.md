# Тест-векторы крипто-ядра (KAT)

> Каноничные известные ответы (Known Answer Tests). Реализация `messenger-crypto` (Kotlin/Kodium) и серверная (Go) **обязаны** воспроизводить эти байты. Расхождение = красный билд. Это и есть контракт, который делает крипто-ядро неломающимся: реализацию можно переписывать свободно, пока векторы зелёные.

## Почему это надёжно

Эталон — **tweetnacl-js** (независимая, широко проверенная реализация NaCl) + **@noble** (HKDF, ML-KEM). **Kodium — порт TweetNaCl**, поэтому совпадение с tweetnacl-js байт-в-байт доказывает корректность Kodium-обёрток. Вся «случайность» (nonce, seed) в векторах **зафиксирована** → вывод детерминирован и воспроизводим на любой машине.

## Файлы

| Файл | Что |
|------|-----|
| [vectors.json](./vectors.json) | Сами векторы (входы + ожидаемые байты) |
| [gen/generate.mjs](./gen/generate.mjs) | Генератор (эталонная реализация) |
| [gen/package.json](./gen/package.json) | Зависимости генератора |

Регенерация: `cd gen && npm install && npm run gen`. Вывод должен быть идентичным (детерминирован).

## Что покрыто

| Вектор | Проверяет | Заморожено |
|--------|-----------|------------|
| `secretbox` | `Kodium.encryptSymmetric(key,data)` = `nonce‖box` при фикс. nonce | Точные байты |
| `box_wrap` | `Kodium.encrypt(eph, recip_pub, message_key)` = `nonce‖box` (wrapped_key) | Точные байты |
| `ed25519` | `signDetached` из seed (общий с Box); подпись 64 B; verify=true | Точные байты |
| `hkdf_sha256` | HKDF-SHA256 для escrow (`hkdf(mlkem_shared)`) | Точные байты |
| `canonical_bytes` | Сборка подписываемого preimage (layout — proto/README §canonical_bytes) + его sha256 | Точные байты |
| `mlkem768_escrow` | ML-KEM-768: keygen(seed) детерминирован (зафиксирован sha256 pk); ct=1088; инвариант `decap(ct,sk)=shared` | Инвариант, не ct |
| `message_body` | Protobuf-байты `MessageBody` (Wire, заморожены 2026-07-12 на первом зелёном прогоне Kotlin) — референс для Go | Точные байты |
| `media_chunk_keys` | `chunk_key[i] = HKDF-SHA256(media_key, salt пустой, "chunk:"+i, 32)` — чанкование медиа | Точные байты |

## Что НЕ заморожено намеренно (и почему это правильно)

1. **ML-KEM ciphertext** — encapsulate рандомизирован (по стандарту). Escrow-инвариант — не «те же байты ct», а «decapsulate восстанавливает shared». Заморожен `sha256(public_key)` от `keygen(seed)` — этого достаточно, чтобы поймать расхождение реализации KEM.
2. **zstd-сжатие** — вывод компрессора зависит от версии/уровня. Нормативна только **распаковка**: получатель обязан корректно `decompress`. Контракт: `encrypt(zstd(body))` на отправителе, `unzstd(decrypt(...))` на получателе — сжатый вид не сверяется по байтам.
3. ~~**protobuf-сериализация MessageBody**~~ — **заморожена 2026-07-12** вектором `message_body` на первом зелёном прогоне Kotlin-реализации (Wire 5.2.1 — референс для Go). Источник байтов — `messenger-crypto/SerializerTest.FROZEN_BODY`; генератор переносит константу, сам её не вычисляет.

## Как реализация потребляет векторы

```kotlin
// commonTest (messenger-crypto)
val v = Json.parseToJsonElement(readResource("vectors.json")).jsonObject["vectors"]!!.jsonObject

// пример: secretbox
val sb = v["secretbox"]!!.jsonObject
val out = Kodium.encryptSymmetricWithNonce(              // тест-хук: инъекция фикс. nonce
    key = sb["key"]!!.hex(), nonce = sb["nonce"]!!.hex(), data = sb["plaintext_hex"]!!.hex()
).getOrThrow()
assertEquals(sb["kodium_output_hex"]!!.content, out.toHex())
```

> Для векторов с фиксированным nonce нужен тест-хук инъекции nonce (боевой код nonce генерирует сам). Это стандартная практика KAT — тестируется примитивный слой, не рандом.

## Правило изменений

Если новый вектор ломает старый — значит меняется **формат**, а не «чинится баг». Такое изменение = новый `format_version` в [envelope.proto](../proto/envelope.proto) + запись в CHANGELOG схемы. Молчаливая смена формата запрещена.
