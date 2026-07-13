# client — клиент TIMA (фаза 3)

Kotlin Multiplatform + Compose Multiplatform (ADR-0001): один код UI и логики,
цели MVP — **Android** (APK) и **Desktop/Windows** (отладка без эмулятора). iOS — при появлении Mac.

Крипто-SDK фазы 1 подключён живым проектом: `includeBuild("../messenger-crypto")`
подменяет `io.tima:messenger-crypto` на исходники — клиент всегда собран против текущего SDK.

## Сборка (Windows)

```powershell
cd C:\!tima2\client
# APK (отладочный): composeApp/build/outputs/apk/debug/composeApp-debug.apk
& "$env:USERPROFILE\gradle-8.14.3\bin\gradle" :composeApp:assembleDebug
# Desktop: запуск окна
& "$env:USERPROFILE\gradle-8.14.3\bin\gradle" :composeApp:run
```

Особенности сети этой машины: прямые запросы к Google Maven (dl.google.com) отваливаются
по таймауту (sdkmanager при этом качает) — репозитории в `settings.gradle.kts` идут через
зеркало Aliyun; MSI-дистрибутив desktop не собирается (WiX качается с GitHub CDN) —
распространение пока uber-jar (`packageUberJarForCurrentOS`).

## Что уже умеет

Вход по контракту Auth (api-overview.md): телефон → SMS-код (dev-сервер возвращает
`dev_code` прямо на экран) → генерация ключей устройства (Kodium: один seed → X25519 + Ed25519)
→ `register` → device JWT. Сессия — `session.json` (Android: internal storage;
Windows: `~/.tima`). Секрет устройства на диске завёрнут в `SecretVault`:
Android — AES-256-GCM ключом из **Android Keystore** (аппаратный, из устройства
не извлекается; старые plaintext-сессии мигрируют при первом запуске);
Windows — пока файл в профиле, DPAPI/Credential Manager — отдельная итерация.

Чат 1-на-1 (`chat/TimaChatService`, общий JVM-код в `jvmCommon`): собеседник ищется
по телефону (`/users/lookup`), `chat_id` детерминированный — sha256 доменной метки
и отсортированной пары user_id, стороны совпадают без договорённости. Отправка —
полный конверт crypto-protocol.md §3: `MessageBody → zstd → SecretBox(message_key)`,
escrow ML-KEM (публичный ключ анклава с `/escrow/pubkey`), wrapped keys всем
устройствам обеих сторон, подпись Ed25519 по canonical_bytes. Приём — путь B
(подпись → wrapped_key → конверт), история REST-ом и live по WS (`sync.pull`,
`message.new` + `ack`). Сервер видит только ciphertext.

E2E-тест `ChatEndToEndTest` (desktopTest) гоняет всё это против живого dev-стека;
без поднятого сервера пропускается. Запуск: `gradle :composeApp:desktopTest`.

Адрес сервера редактируется на экране входа; по умолчанию `http://127.0.0.1:8080`
(desktop) и `http://10.0.2.2:8080` (эмулятор Android смотрит на хост).

## Структура

```
composeApp/src/commonMain/   — UI (Compose), TimaApi (Ktor), Session
composeApp/src/androidMain/  — MainActivity, манифест, session-файл, zstd-jni AAR
composeApp/src/desktopMain/  — main() c окном, session-файл в ~/.tima
```

Проверка на эмуляторе: `emulator -avd tima_test`, затем
`adb install composeApp/build/outputs/apk/debug/composeApp-debug.apk`
(сервер поднимается как в [server/README.md](../server/README.md)).
