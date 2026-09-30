# ADR-0031. Зависимости: действующий список и где его правда

**Дата:** 2026-09-29
**Статус:** принят
**Опирается на:** [ADR-0001](0001-kmp-compose-client.md) — клиент на KMP и Compose; [ADR-0002](0002-go-backend.md) — сервер на Go; [ADR-0006](0006-livekit-media-policy.md) — звонки на LiveKit
**Заменяет в части библиотек и версий:** [tech-stack.md](../02-architecture/tech-stack.md) — он остаётся обзором выбора

---

## Контекст

Заказчик спросил 2026-09-29, есть ли документ со списком используемых библиотек. Был
[tech-stack.md](../02-architecture/tech-stack.md) — «актуализировано 2026-07-12», без версий, — и при
сверке со сборкой он разошёлся с ней в обе стороны:

- указаны, но не используются: Koin, Konsist, chi или echo, livekit/server-sdk-go,
  prometheus/client_golang, golang-migrate;
- используются, но не указаны: LiveKit Android, `livekit_ffi.dll`, openpnp-capture, JNA,
  Wire, kotlincrypto, BouncyCastle (в проверках);
- версий нет ни одной, хотя все они закреплены в файлах сборки.

Документ, который никто не сверяет со сборкой, врёт не сразу, а через месяц — и именно
тогда, когда по нему принимают решение.

## Решение

1. **Правда — файлы сборки**, а не этот документ:

   | Что | Файл |
   |---|---|
   | клиент | `client/gradle/libs.versions.toml` |
   | шифрование | `messenger-crypto/build.gradle.kts` — **мимо каталога**, версии строками |
   | готовые DLL звонков ПК | `client/core/core-call-desktop/build.gradle.kts` — адрес и sha256 |
   | сервер | `server/go.mod` |
   | образы стенда | `server/deploy/docker-compose*.yml` |
   | форки и справочные исходники | [third-party/README.md](../../third-party/README.md) |

2. **Этот ADR — действующий список с назначением.** Добавили, заменили или убрали
   зависимость — строка здесь правится тем же коммитом. Номер версии, расходящийся со
   сборкой, — ошибка этого документа, а не сборки.
3. **tech-stack.md** остаётся обзором «что выбрано и почему»; его устаревшие строки
   помечены, за библиотеками и версиями он отсылает сюда.

Сверено по вершине ветки `stand` 2026-09-29.

---

## Клиент — общий код

| Библиотека | Версия | Зачем |
|---|---|---|
| Kotlin Multiplatform | 2.3.10 | язык и общий код всех платформ |
| Compose Multiplatform | 1.11.1 | интерфейс: Android, ПК, iOS |
| SQLDelight | 2.3.2 | локальная база; драйверы — свой на платформу |
| Ktor Client | 3.5.1 | HTTP и живой канал (WebSocket); движки OkHttp (JVM, Android) и Darwin (Apple) |
| kotlinx-serialization-json | 1.7.3 | JSON ответов сервера |
| kotlinx-datetime | 0.6.2 | время в общем коде (`java.time` запрещён архитектурным правилом) |
| kotlinx-coroutines | 1.10.2 | корутины; в каталоге — для тестов транспорта |

## Клиент — Android

| Библиотека | Версия | Зачем |
|---|---|---|
| Android Gradle Plugin | 8.13.2 | сборка APK |
| androidx.activity-compose | 1.9.3 | окно приложения на Compose |
| androidx.camera: camera-camera2, camera-lifecycle, camera-view | 1.4.1 | сканер кода подключения в «Фраза и устройства» — только Android (заказчик 2026-09-30, 1б) |
| com.google.zxing:core | 3.5.3 | чтение QR в том же сканере; Apache 2.0, чистая Java, без сервисов Google. APK +2 МБ вместе с CameraX |
| **LiveKit Android** | 2.28.2 | звонки; внутри libwebrtc 144.7559.14. Самая тяжёлая зависимость: ~21 МБ нативных библиотек на четыре архитектуры |

## Клиент — ПК (Windows)

| Библиотека | Версия | Зачем |
|---|---|---|
| JNA, jna-platform | 5.14.0 | вызовы DLL и Windows: DPAPI, маршрут до сервера, Core Audio, реестр |
| Wire | 5.2.1 | код по протоколу `livekit-ffi`; версия та же, что у шифрования, — два рантайма Wire в одной сборке нельзя |

**Готовые нативные библиотеки звонков** — скачиваются сборкой с GitHub, сверяются по
sha256, подменённый файл роняет сборку:

| DLL | Версия | Откуда | sha256 |
|---|---|---|---|
| `livekit_ffi.dll` | livekit-ffi 0.12.80 (внутри libwebrtc `webrtc-89d790b`) | выпуски `livekit/rust-sdks` | `7118d627…a57a7c552` |
| `openpnp-capture.dll` | 0.0.30 | выпуски `openpnp/openpnp-capture` | `32802669…8e77b21` |

Что эта сборка умеет и чего нет на Windows: кодирует только программно (OpenH264,
libvpx, libaom — аппаратных кодеров в ней нет), принимает VP8, VP9 и H.264; AV1
заявлен, но не раскодируется — декодер dav1d включён только в сборку для macOS.

## Шифрование — `messenger-crypto`

Версии строками в своём `build.gradle.kts`, мимо каталога клиента.

| Библиотека | Версия | Зачем |
|---|---|---|
| **Kodium** (`eu.livotov.labs:kodium`) | 1.0.0 | единственная криптобиблиотека протокола: SecretBox, Box, Ed25519, HKDF ([ADR-0005](0005-kodium-readiness-gate.md)) |
| Kyber (`asia.hombre:kyber`) | 2.0.1 | ML-KEM-768; **наш форк** с целями Apple, ставится в `~/.m2` ([third-party/README](../../third-party/README.md)) |
| KeccakKotlin | форк | нужен Kyber; тоже наш форк |
| kotlincrypto sha2 | 0.8.0 | SHA-256 чистым Kotlin, на всех платформах |
| zstd-kmp | 0.4.0 | сжатие тела сообщения до шифрования; все платформы, включая Apple |
| Wire | 5.2.1 | код по схеме `schema/proto` ([ADR-0009](0009-schema-first-api.md)) |

## Проверки

| Библиотека | Версия | Зачем |
|---|---|---|
| kotlin-test | как Kotlin | тесты всех модулей |
| ktor-client-mock | 3.5.1 | транспорт без сети |
| kotlinx-coroutines-test | 1.10.2 | время в тестах корутин |
| androidx.test runner / core | 1.6.2 / 1.6.1 | инструментальные проверки: AndroidKeyStore можно только запустить |
| BouncyCastle (`bcprov-jdk18on`) | 1.80 | **только проверки на JVM**: независимый эталон, с которым на каждой сборке сверяется ML-KEM (`CrossImplementationTest`, ADR-0005 Поправка-2). В продукт не едет |

Архитектурные правила — **обычными тестами** в `architecture-tests`, не Konsist
(почему — там же, в `build.gradle.kts`).

## Сервер — Go

| Библиотека | Версия | Зачем |
|---|---|---|
| Go | 1.26, тулчейн 1.26.7 закреплён | версия сервера не зависит от даты сборки образа |
| coder/websocket | 1.8.15 | живой канал |
| jackc/pgx | 5.10.0 | PostgreSQL |
| redis/go-redis | 9.21.0 | Redis: потоки, Pub/Sub |
| minio/minio-go | 7.2.1 | хранилище файлов |
| golang-jwt/jwt | 5.3.1 | токены доступа и **пропуска в комнаты LiveKit** — без их SDK |
| golang.org/x/crypto | 0.54.0 | криптография сервера |
| google.golang.org/protobuf | 1.36.11 | конверты по схеме |

Маршрутизатор HTTP — стандартная библиотека, ни chi, ни echo. Миграции — свой код
(`server/migrations`), не golang-migrate.

## Стенд — образы

| Образ | Версия | Зачем |
|---|---|---|
| caddy | 2 | край: TLS, маршрут к серверу ([ADR-0008](0008-caddy-edge.md)) |
| postgres | 16 | база |
| redis | 7 | очереди и Pub/Sub |
| minio | `RELEASE.2025-09-07T16-13-09Z` | хранилище файлов |
| livekit-server | 1.13.7 | сервер звонков (SFU), один узел, без Redis между узлами |
| tima-backend, tima-escrow-stub | свои | сервер и заглушка escrow |

## Справочные исходники

Не зависимости — копии для чтения, в сборку не входят. Список с тегами — в
[third-party/README.md](../../third-party/README.md): исходники `livekit-ffi` того же тега
0.12.80, openpnp-capture 0.0.30, сервер LiveKit, LocalVQE и другие.

---

## Последствия

- Вопрос «на чём мы стоим» отвечается одним документом, и ответ сверяем со сборкой.
- Добавление зависимости видно в ревью дважды: в файле сборки и строкой здесь. Строки
  нет — ревью просит её дописать.
- Устаревшее в tech-stack.md не удалено, а помечено: по нему видно, какие выборы
  2026-07 так и не случились (chi или echo, Koin, Konsist, Prometheus).
