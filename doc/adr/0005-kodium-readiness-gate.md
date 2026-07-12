# ADR-0005: Kodium — единственная крипто-библиотека клиента, gate перед production

**Статус:** принят · **Дата:** 2026-07-12

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
