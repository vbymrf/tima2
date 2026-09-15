plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// core-media — картинка с устройства: выбрать, раскодировать, обрезать, закодировать
// (ПЛАН-КОНТАКТОВ.md, Д8: аватар). Заведён 2026-09-15.
//
// Отдельный модуль по той же причине, что и core-contacts: половина поведения ОБЯЗАНА
// отличаться по платформам. Выбор картинки на Android — системный выборщик фото через
// ActivityResult, на ПК — файловый диалог AWT, на iOS приложения пока нет. Кодек тоже
// платформенный: Android умеет Bitmap, остальные — Skia.
//
// Общего здесь ровно две вещи: порт загрузки в медиа-хранилище (чистый интерфейс, сеть
// его реализует в core-network) и обрезка квадратом — она считается на ImageBitmap и от
// платформы не зависит.
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
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
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
