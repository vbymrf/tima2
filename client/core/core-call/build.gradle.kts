plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

// core-call — звонок: контракт и его исполнение платформой (ПЛАН-ЗВОНКОВ.md, Plan.md §3.8).
//
// Отдельный модуль по той же причине, что core-media: медиа целиком платформенное. На
// Android это livekit-android с libwebrtc внутри, на ПК маршрут ещё не выбран (К7.1), на
// iOS нет машины. Общее здесь — только контракт и состояния, и ни один экран не должен
// знать, чем звонок исполнен.
//
// Compose сюда НЕ подключается намеренно: звонок — не экран. Экран звонка живёт в feature,
// и рисует он то, что этот модуль отдал состоянием.
kotlin {
    jvmToolchain(17)

    jvm()
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // Самая тяжёлая зависимость клиента: ~21 МБ нативных библиотек на четыре
            // архитектуры (libwebrtc 144.7559.14 внутри). Стоит того только потому, что
            // альтернатива — писать свой клиентский стек WebRTC (Plan.md §3.8, маршрут B).
            //
            // Тянет за собой com.github.davidliu:audioswitch с JitPack — репозиторий
            // добавлен в settings.gradle.kts, и там же сказано зачем.
            implementation(libs.livekit.android)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "io.tima.core.call"
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
