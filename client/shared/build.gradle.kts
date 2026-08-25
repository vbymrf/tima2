plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// shared — КОМПОЗИЦИОННЫЙ КОРЕНЬ, общий для всех платформ (Plan.md §2.2).
//
// Здесь всё, что соединяет модули в приложение: вход, окружение, сеть, отправитель,
// приёмник и навигация верхнего уровня. Платформенным приложениям остаётся ровно то, что
// платформенно по существу: окно или Activity, каталог данных, драйвер базы.
//
// ПОЧЕМУ ОБЩИЙ, А НЕ ПО КОПИИ В КАЖДОМ ПРИЛОЖЕНИИ. Потому что в v1 так и было, и Android
// с Desktop разошлись в мелочах МОЛЧА: одна платформа получала починку, другая нет, и
// узнавали об этом от людей. Сборка приложения — это правила поведения (когда отправлять,
// что делать при обрыве, куда идти после входа), а правила не бывают платформенными.
kotlin {
    jvmToolchain(17)

    jvm()
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // ── ПОЧЕМУ implementation, А НЕ api ───────────────────────────────
            //
            // Раньше shared переэкспортировал все восемь зависимостей. Смысла в
            // этом нет: оба входа (app-android, app-desktop) объявляют нужное им
            // сами. Зато цена есть — переэкспорт делает чужой публичный API
            // частью своего, и модуль, который взял shared, незаметно получает
            // право импортировать что угодно из восьми модулей. Так и появляются
            // теневые рёбра: код компилируется, а в манифесте о зависимости
            // ничего не сказано.
            implementation(projects.core.coreUi)
            implementation(projects.core.coreDatabase)
            implementation(projects.core.coreEncryption)
            implementation(projects.core.coreSecrets)
            implementation(projects.core.coreNetwork)
            implementation(projects.feature.featureChat)
            implementation(projects.feature.featureGroup)
            implementation(projects.feature.featureAuth)
            implementation(projects.feature.featureShell)
            // Объявлено по факту использования: типы этих модулей стоят в коде
            // shared, а компилировалось это через чужой api-экспорт.
            implementation(projects.core.coreOutbox)
            implementation(projects.domain.domainAccount)
            implementation(projects.domain.domainChat)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            // Часы — kotlinx-datetime: System.currentTimeMillis есть только на JVM, и
            // именно из-за таких вызовов сборка приложения раньше не могла быть общей.
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // Сборку приложения можно проверить БЕЗ ОКНА: секрет, база и очередь — обычный
        // код. Именно здесь ломается первый живой запуск, а не в отрисовке. Проверка на
        // JVM, потому что ей нужна файловая база.
        jvmTest.dependencies {
            implementation(libs.sqldelight.driver.jvm)
        }
    }
}

android {
    namespace = "io.tima.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false }
}

tasks.matching { it.name.startsWith("lint") }.configureEach { enabled = false }

// Хостовые unit-тесты Android здесь не нужны по той же причине, что в test-harness: они
// наследуют commonTest и прогнали бы то же самое, что уже идёт на таргете jvm.
tasks.matching { it.name.endsWith("UnitTest") }.configureEach { enabled = false }
