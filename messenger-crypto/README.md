# messenger-crypto

Крипто-ядро TIMA (фаза 1 дорожной карты). Реализует слои личного сообщения по канонической спецификации [crypto-protocol.md](../doc/03-security/crypto-protocol.md) поверх [Kodium](https://github.com/LivotovLabs/kodium) (`eu.livotov.labs:kodium`).

> **Контракт — не код, а байты.** Wire-формат зафиксирован в [schema/proto](../schema/proto/), поведение — в KAT-векторах [schema/test-vectors](../schema/test-vectors/). Реализацию можно переписывать свободно, пока `./gradlew test` зелёный.

## Состав (фаза 1.1)

| Файл | Слой | Что делает |
|------|------|-----------|
| `EnvelopeCipher` | 1 — конверт | `SecretBox(payload, message_key)` → `nonce‖box` |
| `EscrowModule` | 2 — escrow | ML-KEM-768 → HKDF (`tima/escrow/v1`, salt пустой) → wrap `message_key`; `unwrap` — для stub-анклава/тестов |
| `Mlkem768` | 2 — escrow | Провайдер ML-KEM-768 (BouncyCastle): Kodium-реализация не интероперабельна с FIPS 203, см. [ADR-0005 Поправка-1](../doc/adr/0005-kodium-readiness-gate.md) |
| `WrappedKeyService` | 4 — план Б | `Box(ephemeral, device_identity, message_key)` wrap/unwrap |
| `MessageSigner` | подпись | Ed25519 detached над `canonical_bytes` |
| `CanonicalBytes` | подпись | Сборка preimage строго по [proto/README](../schema/proto/README.md) |
| `PersonalMessageSealer` | оркестрация | `seal()` = слои 1+2+4 + подпись; `openWithWrappedKey()` = путь B |

Слой 3 (Double Ratchet, PFS) — фаза 5: `ratchet_envelope` пока всегда пуст. Protobuf-сериализация `Envelope`/`MessageBody` + zstd (`MessageSerializer`) — фаза 1.2.

## Тесты

```powershell
.\gradlew.bat test     # Windows
./gradlew test         # POSIX
```

- `VectorsTest` — KAT байт-в-байт против `../schema/test-vectors/vectors.json` (единый источник, файл подключён как тест-ресурс, копий нет). Расхождение = красный билд, тест не «подстраивается».
- `ProtocolRoundTripTest` — семантика полного протокола: путь B, мультиустройство, escrow-roundtrip, tamper-сценарии (подмена payload/метаданных/wrapped_key).

Тест-хуки с инъекцией nonce (`sealWithNonce`, `wrapWithNonce`) — `internal`, боевой API nonce не принимает.

## Требования

JDK 17 (Temurin). Первая сборка качает зависимости с Maven Central.
