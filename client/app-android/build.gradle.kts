plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Вход для Android. Здесь и только здесь разрешено знать о платформе как о
// платформе (Plan.md §1.3): Activity, манифест, разрешения, push, сервисы.
//
// Приложение намеренно пустое: задача К1.9 — доказать, что тулчейн AGP + Compose
// собирается. Интерфейс приезжает в К5, по макету и из дизайн-системы.
kotlin {
    jvmToolchain(17)
    androidTarget()

    sourceSets {
        androidMain.dependencies {
            // Аксессор compose.material3 объявлен deprecated в Compose 1.11
            // («Specify dependency directly»), и предупреждение мы терпим осознанно:
            // прямая координата org.jetbrains.compose.material3:material3 в линии
            // 1.11 существует ТОЛЬКО в alpha (1.11.0-alpha01…07), а последняя
            // стабильная — 1.9.0. То есть «починка» означала бы либо alpha в
            // зависимостях, либо расхождение версий с рантаймом Compose 1.11.1.
            // Аксессор этим и ценен: он всегда даёт версию плагина.
            implementation(compose.material3)
            implementation(libs.androidx.activity.compose)
            implementation(project(":core:core-model"))
            // Хранилище секретов и база — те модули, у которых на Android СВОЁ
            // поведение: AndroidKeyStore вместо DPAPI, AndroidSqliteDriver вместо
            // sqlite-jdbc. Остальные модули приезжают jvm-вариантом, и это правильно:
            // движок сети на Android тот же OkHttp.
            implementation(project(":core:core-secrets"))
            implementation(project(":core:core-database"))
        }
    }
}

android {
    namespace = "io.tima.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.tima.app"
        // API 26+ — из tech-stack.md. Пересмотр минимума — отдельное решение,
        // а не побочный эффект правки сборки.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "2.0.0-каркас"

        // ── ABI намеренно НЕ ограничен ──────────────────────────────────────
        // В v1 стоял abiFilters = arm64-v8a, и там же капсом было записано
        // следствие: эмулятор x86_64 перестаёт работать, проверять надо на
        // телефоне (инвентарь поведения, пункт 13). Решение было верным, потому
        // что WebRTC и zstd тянут нативные библиотеки под все ABI и раздувают APK.
        //
        // Здесь нативного кода пока нет вовсе, поэтому ограничивать нечего, и
        // ограничение не вводится: эмулятор работает. Вопрос возвращается в К2,
        // когда приедет zstd, и решается тогда осознанно — например, все ABI в
        // отладочной сборке и один в релизной.
    }

    // ── Подпись ─────────────────────────────────────────────────────────────
    // Инвентарь поведения, пункт 14: отладочный ключ генерируется на каждой
    // машине свой, поэтому смена машины сборки давала «Приложение конфликтует с
    // другим пакетом» у всех установленных — Android не даёт обновить приложение
    // с другой подписью. Стабильный ключ лежит в keystore/ вне гита; его потеря
    // означает переустановку у всех пользователей.
    //
    // Ключа нет (свежий клон или CI) — собираем отладочной подписью, чтобы сборка
    // не падала. Настройка появится вместе с распространением, в К7.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Инвентарь поведения, пункт 15: дубликаты метаданных из зависимостей ломают
    // слияние ресурсов APK. Появятся вместе с зависимостями — тогда и список.
}

// ── Линт Android отключён, и это осознанно ────────────────────────────────────
//
// Прогон 2026-08-20 упал так: «Module was compiled with an incompatible version of
// Kotlin. The binary version of its metadata is 2.3.0, expected version is 2.0.0» —
// и следом `lintVitalAnalyzeRelease FAILED`, `lintAnalyzeDebug FAILED`. Падал не
// код, а сам анализатор: у линта из AGP свой встроенный Kotlin, и он отстаёт от
// проектного (2.3.10).
//
// На пустом приложении линт не проверяет ничего, а качество у нас держат
// архитектурные правила и тесты. Поэтому линт выключен целиком, а не «abortOnError
// = false»: внутренний сбой анализатора этим флагом не гасится.
//
// Вернуть — когда в приложении появится настоящий код И версии сойдутся. Тогда же
// проверить, не решает ли вопрос одно обновление AGP.
tasks.matching { it.name.startsWith("lint") }.configureEach { enabled = false }
