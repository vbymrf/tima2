plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

// core-notify — показ уведомлений (ПЛАН-УВЕДОМЛЕНИЙ.md, У5).
//
// Отдельный модуль по той же причине, что core-contacts: **показ целиком
// платформенный**, а общего здесь только договор о том, что показывается. На Android это
// `NotificationManager` с каналами важности, на ПК — значок в трее средствами AWT, на
// iOS показа нет вовсе и стоит честная пустота, а не заглушка, притворяющаяся показом.
//
// Правила «что заслуживает уведомления» здесь НЕТ и быть не должно: они знают про
// блокировку, про свои устройства и про проверку подписи — это `shared`. Сюда приходит
// готовая строка, отсюда уходит только показ.
kotlin {
    jvmToolchain(17)

    jvm()
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Журнал: невыданное разрешение на уведомления — половина «у меня ничего не
            // приходит», и по отчёту это должно быть видно сразу.
            implementation(projects.core.coreDiag)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "io.tima.core.notify"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Линт AGP выключен по той же причине, что и в остальных модулях: у него свой
    // встроенный Kotlin, он отстаёт от проектного и падает на метаданных, а не на коде.
    lint { abortOnError = false }
}

tasks.matching { it.name.startsWith("lint") }.configureEach { enabled = false }
