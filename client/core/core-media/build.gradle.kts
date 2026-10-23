plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// core-media — картинка с устройства: выбрать, раскодировать, обрезать, закодировать
// (ПЛАН-(Д)-КОНТАКТОВ.md, Д8: аватар). Заведён 2026-09-15.
//
// Отдельный модуль по той же причине, что и core-contacts: половина поведения ОБЯЗАНА
// отличаться по платформам. Выбор картинки на Android — системный выборщик фото через
// ActivityResult, на ПК — файловый диалог AWT, на iOS приложения пока нет. Кодек тоже
// платформенный: Android умеет Bitmap, остальные — Skia.
//
// Общего здесь ровно две вещи: порт загрузки в медиа-хранилище (чистый интерфейс, сеть
// его реализует в core-network) и обрезка квадратом — она считается на ImageBitmap и от
// платформы не зависит.
//
// С 2026-10-18 здесь же сборка загрузчика картинок Sketch под наш формат (ПЛАН-(ЗК) ЗК2, ЗК3):
// источник — номер файла, сети у загрузчика нет, кеша расшифрованного на диске нет.
kotlin {
    jvmToolchain(17)

    jvm()
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            // Загрузчик картинок (ПЛАН-(ЗК)): ядро без сети и анимированный WebP. `api` — потому
            // что `TimaSketch` отдаёт тип `Sketch` наружу: его держат корень приложения и core-ui.
            api(libs.sketch.compose.core)
            implementation(libs.sketch.animated.webp)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.concentus)
            // Свой движок окна проигрывания (ПЛАН-(ВМ) ВМ5): файлы, HLS.
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.ui)
            // Звук в фоне с уведомлением ▶/⏸ (ПЛАН-(ВМ) Р22).
            implementation(libs.media3.session)
            // Браузерный движок, YouTube (ПЛАН-(ВМ) Р14).
            implementation(libs.android.youtube.player)
        }
        jvmMain.dependencies {
            implementation(libs.concentus)
            // WebView2 по COM (ПЛАН-(ВМ) Р15): вызовы и окна Windows.
            implementation(libs.jna)
            implementation(libs.jna.platform)
            // Только мост к Media Foundation и его DLL — без транзитивных (см. libs.versions.toml).
            implementation(libs.composemediaplayer.jvm.get().let { "${it.module}:${it.versionConstraint.requiredVersion}" }) {
                isTransitive = false
            }
        }
        // Кодек голосовых (Concentus, ПЛАН-(ВМ) §2.1) — одна Java-библиотека на Android и ПК, один
        // исходник на обе платформы: каталог `jvmShared` входит в обе.
        androidMain { kotlin.srcDir("src/jvmShared/kotlin") }
        jvmMain { kotlin.srcDir("src/jvmShared/kotlin") }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // Обрезка рисует в ImageBitmap — под JVM это Skia, и её родная библиотека
            // приезжает только с настольным Compose. Без него тесты падают на
            // org.jetbrains.skia.ColorSpace ещё до первой проверки.
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "io.tima.core.media"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint { abortOnError = false }
}

tasks.matching { it.name.startsWith("lint") }.configureEach { enabled = false }
tasks.matching { it.name.endsWith("UnitTest") }.configureEach { enabled = false }
