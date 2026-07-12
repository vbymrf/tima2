# ADR-0005: Kodium — единственная крипто-библиотека клиента, gate перед production

**Статус:** принят, поправка 2026-07-12 (§Поправка-1) · **Дата:** 2026-07-12

## Контекст

**Kodium** (`eu.livotov.labs:kodium`) — pure Kotlin Multiplatform криптобиблиотека: TweetNaCl (Box/SecretBox/Sign), X3DH, Double Ratchet, PQXDH, ML-KEM-768 (FIPS 203), HKDF, экспорт/импорт сессий. Покрывает ~70% нужных примитивов; не аудирована независимо.

## Решение

1. Вся клиентская криптография — **только через Kodium**. Запрещено подключать вторые криптобиблиотеки или писать собственные примитивы.
2. Недостающие 30% — прикладной модуль **`messenger-crypto`** поверх Kodium (не форк библиотеки): `EnvelopeCipher`, `WrappedKeyService`, `EscrowModule`, `PersonalChatProtocol`, `GroupChatProtocol` (GK rotation), `MediaCipher` (chunked), `MessageSerializer` (Protobuf + zstd).
3. **Readiness gate** — до публичного релиза (не беты) обязательны:
   - независимый security-аудит Kodium и модуля `messenger-crypto`;
   - верификация Signed PreKey в X3DH handshake;
   - тест-векторы на все форматы [crypto-protocol.md](../03-security/crypto-protocol.md);
   - фаззинг десериализации конвертов.

## Последствия

- Закрытая бета возможна до аудита; публичный релиз — нет.
- Обновления Kodium проходят через pinned-версию и changelog-ревью (криптобиблиотека не обновляется «автоматически»).

## Поправка-1 (2026-07-12): ML-KEM-768 — через BouncyCastle, не Kodium

**Находка.** При первом прогоне KAT-векторов ([schema/test-vectors](../../schema/test-vectors/)) реализация ML-KEM-768 в Kodium 1.0.0 не прошла канарейку `public_key_sha256`: из одного seed Kodium выдаёт **другой публичный ключ**, чем эталон. Диагностика:

- ρ (последние 32 байта ek) совпадает с эталоном → деривация `G(d‖k)` корректна;
- t̂ (первые 1152 байта) расходится → ошибка в математике K-PKE (CBD/NTT/кодирование);
- перекрёстные encapsulate/decapsulate с эталоном дают **разные shared secret в обе стороны**;
- внутренний round-trip Kodium при этом сходится — реализация самосогласована, но не интероперабельна с FIPS 203.

Подтверждено двумя независимыми реализациями: `@noble/post-quantum` (NIST ACVP-tested, генератор векторов) и BouncyCastle (`keygen(seed)` совпал с noble байт-в-байт). Виновник — Kodium.

**Почему это блокер.** Escrow (ADR-0004) требует, чтобы HSM/анклав со стандартной FIPS 203-реализацией декапсулировал клиентские `escrow_blob`. С Kodium ML-KEM весь escrow-слой не работает.

**Решение.** Пункт 1 уточняется: NaCl-слой (Box/SecretBox/Sign/HKDF, X3DH, Double Ratchet) — только Kodium (все KAT сошлись байт-в-байт); **ML-KEM-768 — `org.bouncycastle:bcprov-jdk18on` (pinned)** через изолированный провайдер `messenger-crypto/Mlkem768.kt`. При исправлении Kodium upstream провайдер меняется в одном месте; критерий возврата — зелёный вектор `mlkem768_escrow`.

**Действие.** Сообщить об ошибке в upstream (LivotovLabs/kodium): ML-KEM-768 нестандартный t̂ при корректном ρ; PQXDH/PQDoubleRatchet Kodium до исправления считать «Kodium-только» (несовместимы с другими FIPS 203-реализациями) — на фазу 5 не влияет (classical ratchet), учесть в gate.
