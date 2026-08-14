# ADR-0005: Kodium — единственная крипто-библиотека клиента, gate перед production

**Статус:** принят, поправки 2026-07-12 (§Поправка-1) и 2026-08-14 (§Поправка-2) · **Дата:** 2026-07-12

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

## Поправка-2 (2026-08-14): причина найдена, upstream уведомлён, есть кандидат на замену BouncyCastle

### Диагноз Поправки-1 уточняется

Строка «ошибка в математике K-PKE (CBD/NTT/кодирование)» была **неверной догадкой**. Математика в Kodium корректна; сломан ввод в неё.

`KyberMath.xof` и `KyberMath.prf` берут SHAKE через `Digest`-грань API kotlincrypto вместо XOF-грани. У `SHAKE128()` без аргументов длина вывода 32 байта, у `SHAKE256()` — 64; `digestInto` пишет ровно столько и **не считает ошибкой буфер большего размера** — остаток не трогается.

Измерено:

| Место | Выделено | Заполняется |
|---|---|---|
| `KyberMath.kt:254` — `xof`, матрица `Â` | 1024 | 32 |
| `KyberMath.kt:264` — `prf`, шум `s`/`e`, η=2 | 128 | 64 |
| `KyberAgreement.kt:222` — implicit rejection | 64 | 64 — верно |

`SampleNTT` принимает кандидатов по условию `d < q`, а ноль ему удовлетворяет: счётчик добивается нулями и цикл завершается без исключения. Итог — в каждом полиноме `Â` не более 18 настоящих коэффициентов из 256, у `s` и `e` вторая половина тождественно нулевая. Предположения стойкости Module-LWE не выполняются; практической атаки мы не строили.

### Дефект внесён при переносе, а не унаследован

Код `io.kodium.core.fips203` восходит к [KyberKotlin](https://github.com/ronhombre/KyberKotlin) (`asia.hombre:kyber`, автор указан в шапках файлов). **В апстриме дефекта нет:** там `xof` возвращает настоящий `HashOutputStream` через `.stream()`, а `prf` получает длину аргументом конструктора `SHAKE256(n)`. Kodium подменил `asia.hombre.keccak` на `org.kotlincrypto.hash.sha3` и при этом потерял и поток, и передачу длины.

Копия при этом свежая: `PKEGenerator.generate` добавляет `parameter.K` в SHA3-512, то есть это `G(d ‖ k)` из финального FIPS 203, а не раунд-3 Kyber. Порт снят с KyberKotlin ≥ 1.2.0, подмена хеша — единственный регресс.

### Upstream уведомлён

[LivotovLabs/kodium#11](https://github.com/LivotovLabs/kodium/issues/11), 2026-08-14, с воспроизводимым стендом. Пункт «Действие» Поправки-1 закрыт.

### Кандидат на замену BouncyCastle — KyberKotlin

Проверено заменой, не рассуждением (стенд — `doc_add/kodium-mlkem/Probe3.java`):

| Проверка | Результат |
|---|---|
| Наш KAT `mlkem768_escrow`: `sha256(pub)` из seed | **совпал с эталоном noble** |
| Детерминированный keygen через публичный `RandomProvider` | доступен |
| BouncyCastle инкапсулирует на ключ KyberKotlin | shared совпал |
| KyberKotlin инкапсулирует на ключ BouncyCastle | shared совпал |

Что это даёт: `bcprov-jdk18on` весит 8,68 МБ, `kyber` + `keccak` + `crypto-rand` — 163 КБ суммарно, **в 53 раза меньше** (при APK в 31,5 МБ существенно). И KyberKotlin — чистый KMP, тогда как BouncyCastle навсегда фиксирует `messenger-crypto` как JVM-модуль и закрывает дорогу к iOS.

Что теряем: BouncyCastle — многолетняя, широко развёрнутая библиотека, на которой стоят вендоры HSM. KyberKotlin — один автор, без формального аудита, и его README сам предупреждает, что версии до 1.2.0 реализовывали не ML-KEM, а Kyber. Настоящий контрагент escrow — будущий HSM, а не BouncyCastle.

**Решение: замена выполнена 2026-08-14.**

```kotlin
implementation("asia.hombre:kyber:2.0.1")                   // в продукт
testImplementation("org.bouncycastle:bcprov-jdk18on:1.80")  // оракул для тестов
```

BouncyCastle остался в тестовой области. Перекрёстная сверка двух независимых реализаций стала тестом, который идёт на каждой сборке, — ровно та дисциплина, отсутствие которой погубило Kodium. Именно она закрывает главный минус KyberKotlin (один автор, нет аудита): регрессия в нём падает на сборке, а не в бою.

Пункт 1 ADR по смыслу не меняется: исключение из правила «только Kodium» сделано Поправкой-1, здесь лишь уточняется, какая вторая библиотека занимает это место.

### Что изменилось в коде

| Файл | Изменение |
|---|---|
| `messenger-crypto/build.gradle.kts` | `bcprov` → `implementation("asia.hombre:kyber:2.0.1")`, `bcprov` в `testImplementation` |
| `Mlkem768.kt` | четыре функции переписаны на `MLKEM_768` / `KyberEncapsulationKey` / `KyberCipherText` |
| `CrossImplementationTest.kt` | добавлены сверка ML-KEM в обе стороны и проверка implicit rejection |

Ни один вызывающий код не тронут: `EscrowModule`, `PersonalMessageSealer`, `GroupKeyManager` ходят через обёртку, которая ради этого и заводилась.

### Ловушка, найденная при переходе

`KyberKeyGenerator` запрашивает у источника случайности **сначала `z`, потом `d`** — порядок, обратный нашему layout `d ‖ z` в тест-хуке `keyPairFromSeed`. Половины меняются местами внутри обёртки.

Существенно, что **KAT-вектор этого не поймал бы никогда**: `mlkem768_escrow` использует seed из 64 одинаковых байт, в нём `d` и `z` неразличимы. Порядок определён отдельной сверкой с BouncyCastle на seed, где `d ≠ z`. Урок общий: канареечный вектор проверяет то, что в нём различимо, и молчит про остальное.

### Проверка

`./gradlew test` в `messenger-crypto`: **85 тестов, 0 провалов, 0 пропущено**. Вектор `mlkem768_escrow` совпал с эталоном noble уже на KyberKotlin; `CrossImplementationTest` — 7 тестов, включая обе стороны инкапсуляции.

Замена полностью обратима: провайдер меняется в одном файле, критерий приёмки прежний — зелёный `mlkem768_escrow`.
